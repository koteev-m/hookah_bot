package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import com.hookah.platform.backend.telegram.debugTelegramException
import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class StaffChatLinkCodeRepository(
    private val dataSource: DataSource?,
    private val pepper: String,
    private val ttlSeconds: Long,
    private val now: () -> Instant = { Instant.now() }
) {
    private val logger = LoggerFactory.getLogger(StaffChatLinkCodeRepository::class.java)
    private val random = SecureRandom()
    private val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    init {
        require(pepper.isNotBlank()) { "staff chat link pepper must not be blank" }
    }

    suspend fun createLinkCode(venueId: Long, createdByUserId: Long): LinkCodeResult? {
        val ds = dataSource ?: return null
        val code = generateCode()
        val codeHash = hashCode(code)
        val codeHint = code.take(3)
        val expiresAt = now().plusSeconds(ttlSeconds)
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        """
                            UPDATE telegram_staff_chat_link_codes
                            SET revoked_at = now()
                            WHERE venue_id = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > now()
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement(
                        """
                            INSERT INTO telegram_staff_chat_link_codes (
                                code_hash, code_hint, venue_id, created_by_user_id, created_at, expires_at
                            )
                            VALUES (?, ?, ?, ?, now(), ?)
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, codeHash)
                        statement.setString(2, codeHint)
                        statement.setLong(3, venueId)
                        statement.setLong(4, createdByUserId)
                        statement.setTimestamp(5, java.sql.Timestamp.from(expiresAt))
                        statement.executeUpdate()
                    }
                    connection.commit()
                    LinkCodeResult(code = code, expiresAt = expiresAt, ttlSeconds = ttlSeconds)
                } catch (e: Exception) {
                    connection.rollback()
                    logger.warn(
                        "Failed to create staff chat link code venueId={}: {}",
                        venueId,
                        sanitizeTelegramForLog(e.message)
                    )
                    logger.debugTelegramException(e) { "createLinkCode exception venueId=$venueId" }
                    null
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    suspend fun consumeLinkCode(
        code: String,
        usedByUserId: Long,
        chatId: Long,
        messageId: Long?,
        authorize: suspend (Connection, Long) -> Boolean
    ): ConsumeResult {
        val ds = dataSource ?: return ConsumeResult.DatabaseError
        val codeHash = hashCode(code)
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val record = connection.prepareStatement(
                        """
                            SELECT venue_id, expires_at, revoked_at, used_at
                            FROM telegram_staff_chat_link_codes
                            WHERE code_hash = ?
                            FOR UPDATE
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, codeHash)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                LinkCodeDbRow(
                                    venueId = rs.getLong("venue_id"),
                                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                                    revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
                                    usedAt = rs.getTimestamp("used_at")?.toInstant()
                                )
                            } else {
                                null
                            }
                        }
                    } ?: return@withContext ConsumeResult.InvalidOrExpired

                    val nowTs = now()
                    if (record.revokedAt != null || record.usedAt != null || record.expiresAt.isBefore(nowTs)) {
                        connection.rollback()
                        return@withContext ConsumeResult.InvalidOrExpired
                    }

                    val authorized = runCatching { authorize(connection, record.venueId) }.getOrElse { throwable ->
                        logger.warn(
                            "Authorization check failed venueId={}: {}",
                            record.venueId,
                            sanitizeTelegramForLog(throwable.message)
                        )
                        logger.debugTelegramException(throwable) { "Authorization check exception venueId=${record.venueId}" }
                        false
                    }
                    if (!authorized) {
                        connection.rollback()
                        return@withContext ConsumeResult.Unauthorized(record.venueId)
                    }

                    connection.prepareStatement(
                        """
                            UPDATE telegram_staff_chat_link_codes
                            SET used_at = ?, used_by_user_id = ?, used_in_chat_id = ?, used_message_id = ?
                            WHERE code_hash = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setTimestamp(1, java.sql.Timestamp.from(nowTs))
                        statement.setLong(2, usedByUserId)
                        statement.setLong(3, chatId)
                        if (messageId != null) statement.setLong(4, messageId) else statement.setNull(4, java.sql.Types.BIGINT)
                        statement.setString(5, codeHash)
                        statement.executeUpdate()
                    }
                    connection.commit()
                    ConsumeResult.Success(record.venueId)
                } catch (e: Exception) {
                    connection.rollback()
                    logger.warn("Failed to consume staff chat link code: {}", sanitizeTelegramForLog(e.message))
                    logger.debugTelegramException(e) { "consumeLinkCode exception" }
                    ConsumeResult.DatabaseError
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    suspend fun findActiveCodeForVenue(venueId: Long): ActiveLinkCode? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                        SELECT code_hint, expires_at
                        FROM telegram_staff_chat_link_codes
                        WHERE venue_id = ? AND revoked_at IS NULL AND used_at IS NULL AND expires_at > now()
                        ORDER BY expires_at DESC
                        LIMIT 1
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            ActiveLinkCode(
                                codeHint = rs.getString("code_hint"),
                                expiresAt = rs.getTimestamp("expires_at").toInstant()
                            )
                        } else null
                    }
                }
            }
        }
    }

    private fun generateCode(length: Int = 10): String {
        val builder = StringBuilder(length)
        repeat(length) {
            val idx = random.nextInt(alphabet.length)
            builder.append(alphabet[idx])
        }
        return builder.toString()
    }

    private fun hashCode(code: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pepper.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(code.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

data class LinkCodeResult(
    val code: String,
    val expiresAt: Instant,
    val ttlSeconds: Long
)

sealed interface ConsumeResult {
    data class Success(val venueId: Long) : ConsumeResult
    data class Unauthorized(val venueId: Long) : ConsumeResult
    data object InvalidOrExpired : ConsumeResult
    data object DatabaseError : ConsumeResult
}

data class ActiveLinkCode(
    val codeHint: String?,
    val expiresAt: Instant
)

private data class LinkCodeDbRow(
    val venueId: Long,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val usedAt: Instant?
)
