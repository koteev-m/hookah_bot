package com.hookah.platform.backend.miniapp.venue.staff

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

class StaffInviteRepository(
    private val dataSource: DataSource?,
    private val pepper: String,
    private val now: () -> Instant = { Instant.now() }
) {
    private val logger = LoggerFactory.getLogger(StaffInviteRepository::class.java)
    private val random = SecureRandom()

    init {
        require(pepper.isNotBlank()) { "staff invite pepper must not be blank" }
    }

    suspend fun createInvite(
        venueId: Long,
        createdByUserId: Long,
        role: String,
        ttlSeconds: Long
    ): StaffInviteCodeResult? {
        val ds = dataSource ?: return null
        val code = generateCode()
        val codeHash = hashCode(code)
        val codeHint = code.take(3)
        val nowTs = now()
        val expiresAt = nowTs.plusSeconds(ttlSeconds)
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                try {
                    connection.prepareStatement(
                        """
                            INSERT INTO venue_staff_invites (
                                code_hash, code_hint, venue_id, role, created_by_user_id, created_at, expires_at
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, codeHash)
                        statement.setString(2, codeHint)
                        statement.setLong(3, venueId)
                        statement.setString(4, role)
                        statement.setLong(5, createdByUserId)
                        statement.setTimestamp(6, java.sql.Timestamp.from(nowTs))
                        statement.setTimestamp(7, java.sql.Timestamp.from(expiresAt))
                        statement.executeUpdate()
                    }
                    StaffInviteCodeResult(code = code, expiresAt = expiresAt, ttlSeconds = ttlSeconds)
                } catch (e: Exception) {
                    logger.warn(
                        "Failed to create staff invite venueId={} createdByUserId={}: {}",
                        venueId,
                        createdByUserId,
                        sanitizeTelegramForLog(e.message)
                    )
                    logger.debugTelegramException(e) { "createInvite exception venueId=$venueId" }
                    null
                }
            }
        }
    }

    suspend fun acceptInvite(
        code: String,
        userId: Long,
        createMember: suspend (Connection, Long, String, Long?) -> VenueStaffMember?
    ): StaffInviteAcceptResult {
        val ds = dataSource ?: return StaffInviteAcceptResult.DatabaseError
        val normalizedCode = StaffInviteCodeFormat.normalizeCode(code) ?: return StaffInviteAcceptResult.InvalidOrExpired
        val codeHash = hashCode(normalizedCode)
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val initialAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val invite = loadInvite(connection, codeHash) ?: return@use rollbackAndReturn(connection) {
                        StaffInviteAcceptResult.InvalidOrExpired
                    }
                    val nowTs = now()
                    if (invite.usedAt != null || !invite.expiresAt.isAfter(nowTs)) {
                        return@use rollbackAndReturn(connection) { StaffInviteAcceptResult.InvalidOrExpired }
                    }
                    val existingMember = loadMember(connection, invite.venueId, userId)
                    if (existingMember != null) {
                        markInviteUsed(connection, codeHash, nowTs, userId)
                        connection.commit()
                        return@use StaffInviteAcceptResult.Success(existingMember, alreadyMember = true)
                    }
                    val member = createMember(connection, invite.venueId, invite.role, invite.createdByUserId)
                        ?: return@use rollbackAndReturn(connection) { StaffInviteAcceptResult.DatabaseError }
                    markInviteUsed(connection, codeHash, nowTs, userId)
                    connection.commit()
                    StaffInviteAcceptResult.Success(member, alreadyMember = false)
                } catch (e: Exception) {
                    rollbackBestEffort(connection)
                    logger.warn(
                        "Failed to accept staff invite userId={}: {}",
                        userId,
                        sanitizeTelegramForLog(e.message)
                    )
                    logger.debugTelegramException(e) { "acceptInvite exception userId=$userId" }
                    StaffInviteAcceptResult.DatabaseError
                } finally {
                    connection.autoCommit = initialAutoCommit
                }
            }
        }
    }

    private fun loadInvite(connection: Connection, codeHash: String): StaffInviteRow? {
        return connection.prepareStatement(
            """
                SELECT venue_id, role, created_by_user_id, expires_at, used_at
                FROM venue_staff_invites
                WHERE code_hash = ?
                FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, codeHash)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StaffInviteRow(
                        venueId = rs.getLong("venue_id"),
                        role = rs.getString("role"),
                        createdByUserId = rs.getLong("created_by_user_id"),
                        expiresAt = rs.getTimestamp("expires_at").toInstant(),
                        usedAt = rs.getTimestamp("used_at")?.toInstant()
                    )
                } else null
            }
        }
    }

    private fun loadMember(connection: Connection, venueId: Long, userId: Long): VenueStaffMember? {
        return connection.prepareStatement(
            """
                SELECT role, created_at, invited_by_user_id
                FROM venue_members
                WHERE venue_id = ? AND user_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, userId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    VenueStaffMember(
                        venueId = venueId,
                        userId = userId,
                        role = rs.getString("role"),
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                        invitedByUserId = rs.getLong("invited_by_user_id").takeIf { !rs.wasNull() }
                    )
                } else null
            }
        }
    }

    private fun markInviteUsed(connection: Connection, codeHash: String, nowTs: Instant, userId: Long) {
        connection.prepareStatement(
            """
                UPDATE venue_staff_invites
                SET used_at = ?, used_by_user_id = ?
                WHERE code_hash = ?
            """.trimIndent()
        ).use { statement ->
            statement.setTimestamp(1, java.sql.Timestamp.from(nowTs))
            statement.setLong(2, userId)
            statement.setString(3, codeHash)
            statement.executeUpdate()
        }
    }

    private fun generateCode(length: Int = 10): String {
        val builder = StringBuilder(length)
        repeat(length) {
            val idx = random.nextInt(StaffInviteCodeFormat.CODE_ALPHABET.length)
            builder.append(StaffInviteCodeFormat.CODE_ALPHABET[idx])
        }
        return builder.toString()
    }

    private fun hashCode(code: String): String {
        require(StaffInviteCodeFormat.isLikelyValidCodeFormat(code)) { "invalid code format" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pepper.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(code.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun rollbackBestEffort(connection: Connection) {
        runCatching { connection.rollback() }
    }

    private fun <T> rollbackAndReturn(connection: Connection, block: () -> T): T {
        runCatching { connection.rollback() }
        return block()
    }
}

data class StaffInviteCodeResult(
    val code: String,
    val expiresAt: Instant,
    val ttlSeconds: Long
)

sealed interface StaffInviteAcceptResult {
    data class Success(val member: VenueStaffMember, val alreadyMember: Boolean) : StaffInviteAcceptResult
    data object InvalidOrExpired : StaffInviteAcceptResult
    data object DatabaseError : StaffInviteAcceptResult
}

private data class StaffInviteRow(
    val venueId: Long,
    val role: String,
    val createdByUserId: Long,
    val expiresAt: Instant,
    val usedAt: Instant?
)
