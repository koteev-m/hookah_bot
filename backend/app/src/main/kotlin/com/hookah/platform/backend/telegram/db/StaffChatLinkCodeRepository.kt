package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.telegram.debugTelegramException
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

class StaffChatLinkCodeRepository(
    private val dataSource: DataSource?,
    private val pepper: String,
    private val ttlSeconds: Long,
    private val now: () -> Instant = { Instant.now() },
) {
    private val logger = LoggerFactory.getLogger(StaffChatLinkCodeRepository::class.java)
    private val random = SecureRandom()

    init {
        require(pepper.isNotBlank()) { "staff chat link pepper must not be blank" }
    }

    suspend fun createLinkCode(
        venueId: Long,
        createdByUserId: Long,
    ): LinkCodeResult? {
        val ds = dataSource ?: return null
        val code = generateCode()
        val codeHash = hashCode(code)
        val codeHint = code.take(3)
        val nowTs = now()
        val expiresAt = nowTs.plusSeconds(ttlSeconds)
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        """
                        UPDATE telegram_staff_chat_link_codes
                        SET revoked_at = ?
                        WHERE venue_id = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setTimestamp(1, java.sql.Timestamp.from(nowTs))
                        statement.setLong(2, venueId)
                        statement.setTimestamp(3, java.sql.Timestamp.from(nowTs))
                        statement.executeUpdate()
                    }
                    connection.prepareStatement(
                        """
                        INSERT INTO telegram_staff_chat_link_codes (
                            code_hash, code_hint, venue_id, created_by_user_id, created_at, expires_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, codeHash)
                        statement.setString(2, codeHint)
                        statement.setLong(3, venueId)
                        statement.setLong(4, createdByUserId)
                        statement.setTimestamp(5, java.sql.Timestamp.from(nowTs))
                        statement.setTimestamp(6, java.sql.Timestamp.from(expiresAt))
                        statement.executeUpdate()
                    }
                    connection.commit()
                    LinkCodeResult(code = code, expiresAt = expiresAt, ttlSeconds = ttlSeconds)
                } catch (e: Exception) {
                    connection.rollback()
                    logger.warn(
                        "Failed to create staff chat link code venueId={}: {}",
                        venueId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) { "createLinkCode exception venueId=$venueId" }
                    null
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    @Deprecated("Use linkAndBindWithCode for atomic flow")
    suspend fun consumeLinkCode(
        code: String,
        usedByUserId: Long,
        chatId: Long,
        messageId: Long?,
        authorize: suspend (Connection, Long) -> Boolean,
        bind: suspend (Connection, Long) -> BindResult,
    ): LinkAndBindResult = linkAndBindWithCode(code, usedByUserId, chatId, messageId, authorize, bind)

    suspend fun linkAndBindWithCode(
        code: String,
        usedByUserId: Long,
        chatId: Long,
        messageId: Long?,
        authorize: suspend (Connection, Long) -> Boolean,
        bind: suspend (Connection, Long) -> BindResult,
    ): LinkAndBindResult {
        val ds = dataSource ?: return LinkAndBindResult.DatabaseError
        val normalizedCode = StaffChatLinkCodeFormat.normalizeCode(code)
        if (normalizedCode == null) {
            return LinkAndBindResult.InvalidOrExpired
        }
        val codeHash = hashCode(normalizedCode)
        return withContext(Dispatchers.IO) {
            ds.connection.use use@{ connection ->
                val initialAutoCommit = connection.autoCommit
                connection.autoCommit = false
                var record: LinkCodeDbRow? = null
                try {
                    record =
                        connection.prepareStatement(
                            """
                            SELECT venue_id, expires_at, revoked_at, used_at
                            FROM telegram_staff_chat_link_codes
                            WHERE code_hash = ?
                            FOR UPDATE
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, codeHash)
                            statement.executeQuery().use { rs ->
                                if (rs.next()) {
                                    LinkCodeDbRow(
                                        venueId = rs.getLong("venue_id"),
                                        expiresAt = rs.getTimestamp("expires_at").toInstant(),
                                        revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
                                        usedAt = rs.getTimestamp("used_at")?.toInstant(),
                                    )
                                } else {
                                    null
                                }
                            }
                        }

                    if (record == null) {
                        return@use rollbackAndReturn(connection) { LinkAndBindResult.InvalidOrExpired }
                    }

                    val nowTs = now()
                    val expired = !record.expiresAt.isAfter(nowTs)
                    if (record.revokedAt != null || record.usedAt != null || expired) {
                        return@use rollbackAndReturn(connection) { LinkAndBindResult.InvalidOrExpired }
                    }

                    val authorized =
                        runCatching { authorize(connection, record.venueId) }.getOrElse { throwable ->
                            logger.warn(
                                "Authorization check failed venueId={} chatId={} userId={}: {}",
                                record.venueId,
                                chatId,
                                usedByUserId,
                                sanitizeTelegramForLog(throwable.message),
                            )
                            logger.debugTelegramException(
                                throwable,
                            ) { "Authorization check exception venueId=${record.venueId}" }
                            false
                        }
                    if (!authorized) {
                        return@use rollbackAndReturn(connection) {
                            LinkAndBindResult.Unauthorized(record.venueId)
                        }
                    }

                    val bindResult =
                        runCatching { bind(connection, record.venueId) }.getOrElse { throwable ->
                            logger.warn(
                                "Bind attempt failed venueId={} chatId={} userId={}: {}",
                                record.venueId,
                                chatId,
                                usedByUserId,
                                sanitizeTelegramForLog(throwable.message),
                            )
                            logger.debugTelegramException(throwable) {
                                "bind callback exception venueId=${record.venueId} chatId=$chatId userId=$usedByUserId"
                            }
                            null
                        } ?: return@use rollbackAndReturn(connection) { LinkAndBindResult.DatabaseError }

                    when (bindResult) {
                        is BindResult.Success -> {
                            markCodeUsed(connection, codeHash, nowTs, usedByUserId, chatId, messageId)
                            connection.commit()
                            LinkAndBindResult.Success(bindResult.venueId, bindResult.venueName)
                        }

                        is BindResult.AlreadyBoundSameChat -> {
                            markCodeUsed(connection, codeHash, nowTs, usedByUserId, chatId, messageId)
                            connection.commit()
                            LinkAndBindResult.AlreadyBoundSameChat(bindResult.venueId, bindResult.venueName)
                        }

                        is BindResult.ChatAlreadyLinked -> {
                            rollbackAndReturn(connection) { LinkAndBindResult.ChatAlreadyLinked(bindResult.venueId) }
                        }

                        BindResult.NotFound -> {
                            rollbackAndReturn(connection) { LinkAndBindResult.InvalidOrExpired }
                        }

                        BindResult.DatabaseError -> {
                            rollbackAndReturn(connection) { LinkAndBindResult.DatabaseError }
                        }
                    }
                } catch (e: Exception) {
                    rollbackBestEffort(connection)
                    logger.warn(
                        "Failed to link staff chat with code for chatId={} userId={} venueId={}: {}",
                        chatId,
                        usedByUserId,
                        record?.venueId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) {
                        "linkAndBindWithCode exception chatId=$chatId userId=$usedByUserId venueId=${record?.venueId}"
                    }
                    LinkAndBindResult.DatabaseError
                } finally {
                    connection.autoCommit = initialAutoCommit
                }
            }
        }
    }

    suspend fun findActiveCodeForVenue(venueId: Long): ActiveLinkCode? {
        val ds = dataSource ?: return null
        val nowTs = now()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT code_hint, expires_at
                    FROM telegram_staff_chat_link_codes
                    WHERE venue_id = ? AND revoked_at IS NULL AND used_at IS NULL AND expires_at > ?
                    ORDER BY expires_at DESC
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setTimestamp(2, java.sql.Timestamp.from(nowTs))
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            ActiveLinkCode(
                                codeHint = rs.getString("code_hint"),
                                expiresAt = rs.getTimestamp("expires_at").toInstant(),
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }
    }

    private fun generateCode(length: Int = 10): String {
        val builder = StringBuilder(length)
        repeat(length) {
            val idx = random.nextInt(StaffChatLinkCodeFormat.CODE_ALPHABET.length)
            builder.append(StaffChatLinkCodeFormat.CODE_ALPHABET[idx])
        }
        return builder.toString()
    }

    private fun hashCode(code: String): String {
        require(StaffChatLinkCodeFormat.isLikelyValidCodeFormat(code)) { "invalid code format" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pepper.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(code.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun markCodeUsed(
        connection: Connection,
        codeHash: String,
        nowTs: Instant,
        usedByUserId: Long,
        chatId: Long,
        messageId: Long?,
    ) {
        connection.prepareStatement(
            """
            UPDATE telegram_staff_chat_link_codes
            SET used_at = ?, used_by_user_id = ?, used_in_chat_id = ?, used_message_id = ?
            WHERE code_hash = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, java.sql.Timestamp.from(nowTs))
            statement.setLong(2, usedByUserId)
            statement.setLong(3, chatId)
            if (messageId != null) statement.setLong(4, messageId) else statement.setNull(4, java.sql.Types.BIGINT)
            statement.setString(5, codeHash)
            val updatedRows = statement.executeUpdate()
            if (updatedRows != 1) {
                logger.warn(
                    "Unexpected rows updated when marking code used codeHash={} chatId={} userId={} updated={}",
                    codeHash,
                    chatId,
                    usedByUserId,
                    updatedRows,
                )
                throw IllegalStateException("Failed to mark code as used")
            }
        }
    }

    private fun rollbackBestEffort(connection: Connection) {
        runCatching { connection.rollback() }
    }

    private fun <T> rollbackAndReturn(
        connection: Connection,
        block: () -> T,
    ): T {
        runCatching { connection.rollback() }
        return block()
    }
}

data class LinkCodeResult(
    val code: String,
    val expiresAt: Instant,
    val ttlSeconds: Long,
)

sealed interface LinkAndBindResult {
    data class Success(val venueId: Long, val venueName: String) : LinkAndBindResult

    data class AlreadyBoundSameChat(val venueId: Long, val venueName: String) : LinkAndBindResult

    data class ChatAlreadyLinked(val venueId: Long?) : LinkAndBindResult

    data class Unauthorized(val venueId: Long) : LinkAndBindResult

    data object InvalidOrExpired : LinkAndBindResult

    data object DatabaseError : LinkAndBindResult
}

data class ActiveLinkCode(
    val codeHint: String?,
    val expiresAt: Instant,
)

private data class LinkCodeDbRow(
    val venueId: Long,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val usedAt: Instant?,
)
