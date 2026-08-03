package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.VenueRoleMapping
import com.hookah.platform.backend.telegram.debugTelegramException
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

class StaffInviteRepository(
    private val dataSource: DataSource?,
    private val pepper: String,
    private val now: () -> Instant = { Instant.now() },
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
        ttlSeconds: Long,
    ): StaffInviteCodeResult? =
        createInviteInternal(
            venueId = venueId,
            createdByUserId = createdByUserId,
            role = role,
            ttlSeconds = ttlSeconds,
            auditLogRepository = null,
        )

    suspend fun createInvite(
        venueId: Long,
        createdByUserId: Long,
        role: String,
        ttlSeconds: Long,
        auditLogRepository: AuditLogRepository,
    ): StaffInviteCodeResult? =
        createInviteInternal(
            venueId = venueId,
            createdByUserId = createdByUserId,
            role = role,
            ttlSeconds = ttlSeconds,
            auditLogRepository = auditLogRepository,
        )

    private suspend fun createInviteInternal(
        venueId: Long,
        createdByUserId: Long,
        role: String,
        ttlSeconds: Long,
        auditLogRepository: AuditLogRepository?,
    ): StaffInviteCodeResult? {
        val ds = dataSource ?: return null
        val code = generateCode()
        val codeHash = hashCode(code)
        val handle = deriveOpaqueHandle(codeHash)
        val codeHint = code.take(3)
        val nowTs = now()
        val expiresAt = nowTs.plusSeconds(ttlSeconds)
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val initialAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        """
                        INSERT INTO venue_staff_invites (
                            code_hash, code_hint, venue_id, role, created_by_user_id, created_at, expires_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
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
                    if (auditLogRepository != null) {
                        appendInviteAudit(
                            connection = connection,
                            auditLogRepository = auditLogRepository,
                            actorUserId = createdByUserId,
                            action = STAFF_INVITE_CREATED_AUDIT_ACTION,
                            venueId = venueId,
                            handle = handle,
                            targetRole = role,
                        )
                    }
                    connection.commit()
                    StaffInviteCodeResult(
                        code = code,
                        expiresAt = expiresAt,
                        ttlSeconds = ttlSeconds,
                        handle = handle,
                    )
                } catch (e: Exception) {
                    rollbackBestEffort(connection)
                    logger.warn(
                        "Failed to create staff invite venueId={} createdByUserId={}: {}",
                        venueId,
                        createdByUserId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) { "createInvite exception venueId=$venueId" }
                    null
                } finally {
                    connection.autoCommit = initialAutoCommit
                }
            }
        }
    }

    suspend fun listPendingInvites(
        venueId: Long,
        allowedRoles: Set<String>,
    ): List<PendingStaffInvite>? {
        val ds = dataSource ?: return null
        val roles = normalizeAllowedRoles(allowedRoles)
        if (roles.isEmpty()) {
            return emptyList()
        }
        val nowTs = now()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                try {
                    loadPendingInviteRows(
                        connection = connection,
                        venueId = venueId,
                        allowedRoles = roles,
                        nowTs = nowTs,
                    ).map { row -> row.toPendingInvite() }
                } catch (e: Exception) {
                    logger.warn(
                        "Failed to list pending staff invites venueId={}: {}",
                        venueId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) { "listPendingInvites exception venueId=$venueId" }
                    null
                }
            }
        }
    }

    suspend fun revokePendingInvite(
        venueId: Long,
        handle: String,
        actorUserId: Long,
        allowedRoles: Set<String>,
        auditLogRepository: AuditLogRepository,
    ): StaffInviteRevokeResult {
        val ds = dataSource ?: return StaffInviteRevokeResult.DatabaseError
        val normalizedHandle = normalizeOpaqueHandle(handle) ?: return StaffInviteRevokeResult.InvalidOrExpired
        val roles = normalizeAllowedRoles(allowedRoles)
        if (roles.isEmpty()) {
            return StaffInviteRevokeResult.InvalidOrExpired
        }
        val nowTs = now()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val initialAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val row =
                        loadPendingInviteRows(
                            connection = connection,
                            venueId = venueId,
                            allowedRoles = roles,
                            nowTs = nowTs,
                        ).firstOrNull { candidate ->
                            opaqueHandlesEqual(deriveOpaqueHandle(candidate.codeHash), normalizedHandle)
                        } ?: return@use rollbackAndReturn(connection) {
                            StaffInviteRevokeResult.InvalidOrExpired
                        }
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE venue_staff_invites
                            SET revoked_at = ?, revoked_by_user_id = ?
                            WHERE code_hash = ?
                              AND venue_id = ?
                              AND role = ?
                              AND used_at IS NULL
                              AND revoked_at IS NULL
                              AND expires_at > ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setTimestamp(1, java.sql.Timestamp.from(nowTs))
                            statement.setLong(2, actorUserId)
                            statement.setString(3, row.codeHash)
                            statement.setLong(4, venueId)
                            statement.setString(5, row.role)
                            statement.setTimestamp(6, java.sql.Timestamp.from(nowTs))
                            statement.executeUpdate()
                        }
                    if (updated != 1) {
                        return@use rollbackAndReturn(connection) { StaffInviteRevokeResult.InvalidOrExpired }
                    }
                    appendInviteAudit(
                        connection = connection,
                        auditLogRepository = auditLogRepository,
                        actorUserId = actorUserId,
                        action = STAFF_INVITE_REVOKED_AUDIT_ACTION,
                        venueId = venueId,
                        handle = normalizedHandle,
                        targetRole = row.role,
                    )
                    connection.commit()
                    StaffInviteRevokeResult.Success(
                        invite = row.toPendingInvite(),
                        revokedAt = nowTs,
                    )
                } catch (e: Exception) {
                    rollbackBestEffort(connection)
                    logger.warn(
                        "Failed to revoke pending staff invite venueId={} actorUserId={}: {}",
                        venueId,
                        actorUserId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) {
                        "revokePendingInvite exception venueId=$venueId actorUserId=$actorUserId"
                    }
                    StaffInviteRevokeResult.DatabaseError
                } finally {
                    connection.autoCommit = initialAutoCommit
                }
            }
        }
    }

    suspend fun previewInvite(code: String): StaffInvitePreviewResult {
        val ds = dataSource ?: return StaffInvitePreviewResult.DatabaseError
        val normalizedCode =
            StaffInviteCodeFormat.normalizeCode(code) ?: return StaffInvitePreviewResult.InvalidOrExpired
        val codeHash = hashCode(normalizedCode)
        val nowTs = now()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                try {
                    val invite =
                        loadActiveInvite(connection, codeHash, nowTs, forUpdate = false)
                            ?: return@use StaffInvitePreviewResult.InvalidOrExpired
                    StaffInvitePreviewResult.Success(
                        StaffInvitePreview(
                            venueId = invite.venueId,
                            role = invite.role,
                            createdByUserId = invite.createdByUserId,
                            expiresAt = invite.expiresAt,
                        ),
                    )
                } catch (e: Exception) {
                    logger.warn(
                        "Failed to preview staff invite: {}",
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) { "previewInvite exception" }
                    StaffInvitePreviewResult.DatabaseError
                }
            }
        }
    }

    suspend fun acceptInvite(
        code: String,
        userId: Long,
        createMember: suspend (Connection, Long, String, Long?) -> VenueStaffMember?,
    ): StaffInviteAcceptResult {
        val ds = dataSource ?: return StaffInviteAcceptResult.DatabaseError
        val normalizedCode =
            StaffInviteCodeFormat.normalizeCode(code) ?: return StaffInviteAcceptResult.InvalidOrExpired
        val codeHash = hashCode(normalizedCode)
        val nowTs = now()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val initialAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val invite =
                        loadActiveInvite(connection, codeHash, nowTs) ?: return@use rollbackAndReturn(connection) {
                            StaffInviteAcceptResult.InvalidOrExpired
                        }
                    val existingMember = loadMember(connection, invite.venueId, userId, forUpdate = true)
                    if (existingMember != null) {
                        val resolvedExisting = VenueRoleMapping.fromDb(existingMember.role)
                        val resolvedInvited = VenueRoleMapping.fromDb(invite.role)
                        if (resolvedExisting == null || resolvedInvited == null) {
                            return@use rollbackAndReturn(connection) { StaffInviteAcceptResult.DatabaseError }
                        }
                        val normalizedExistingMember = existingMember.copy(role = resolvedExisting.name)
                        val existingRank = venueRoleRank(resolvedExisting)
                        val invitedRank = venueRoleRank(resolvedInvited)
                        if (invitedRank > existingRank) {
                            val updatedMember =
                                updateMemberRole(connection, normalizedExistingMember, resolvedInvited.name)
                            if (!markInviteUsed(connection, codeHash, nowTs, userId)) {
                                return@use rollbackAndReturn(connection) {
                                    StaffInviteAcceptResult.InvalidOrExpired
                                }
                            }
                            connection.commit()
                            return@use StaffInviteAcceptResult.Success(
                                member = updatedMember,
                                alreadyMember = true,
                                invitedRole = invite.role,
                                inviteCreatedByUserId = invite.createdByUserId,
                                roleChanged = true,
                            )
                        }
                        if (!markInviteUsed(connection, codeHash, nowTs, userId)) {
                            return@use rollbackAndReturn(connection) { StaffInviteAcceptResult.InvalidOrExpired }
                        }
                        connection.commit()
                        return@use StaffInviteAcceptResult.Success(
                            member = normalizedExistingMember,
                            alreadyMember = true,
                            invitedRole = invite.role,
                            inviteCreatedByUserId = invite.createdByUserId,
                            keptHigherRole = existingRank > invitedRank,
                        )
                    }
                    val member = createMember(connection, invite.venueId, invite.role, invite.createdByUserId)
                    if (member == null) {
                        val existingAfterInsert = loadMember(connection, invite.venueId, userId, forUpdate = true)
                        if (existingAfterInsert != null) {
                            if (!markInviteUsed(connection, codeHash, nowTs, userId)) {
                                return@use rollbackAndReturn(connection) {
                                    StaffInviteAcceptResult.InvalidOrExpired
                                }
                            }
                            connection.commit()
                            return@use StaffInviteAcceptResult.Success(
                                member = existingAfterInsert,
                                alreadyMember = true,
                                invitedRole = invite.role,
                                inviteCreatedByUserId = invite.createdByUserId,
                            )
                        }
                        return@use rollbackAndReturn(connection) { StaffInviteAcceptResult.DatabaseError }
                    }
                    if (!markInviteUsed(connection, codeHash, nowTs, userId)) {
                        return@use rollbackAndReturn(connection) { StaffInviteAcceptResult.InvalidOrExpired }
                    }
                    connection.commit()
                    StaffInviteAcceptResult.Success(
                        member = member,
                        alreadyMember = false,
                        invitedRole = invite.role,
                        inviteCreatedByUserId = invite.createdByUserId,
                    )
                } catch (e: Exception) {
                    rollbackBestEffort(connection)
                    logger.warn(
                        "Failed to accept staff invite userId={}: {}",
                        userId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) { "acceptInvite exception userId=$userId" }
                    StaffInviteAcceptResult.DatabaseError
                } finally {
                    connection.autoCommit = initialAutoCommit
                }
            }
        }
    }

    suspend fun declineInvite(
        code: String,
        userId: Long,
    ): StaffInviteDeclineResult {
        val ds = dataSource ?: return StaffInviteDeclineResult.DatabaseError
        val normalizedCode =
            StaffInviteCodeFormat.normalizeCode(code) ?: return StaffInviteDeclineResult.InvalidOrExpired
        val codeHash = hashCode(normalizedCode)
        val nowTs = now()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val initialAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val invite =
                        loadActiveInvite(connection, codeHash, nowTs) ?: return@use rollbackAndReturn(connection) {
                            StaffInviteDeclineResult.InvalidOrExpired
                        }
                    if (!markInviteUsed(connection, codeHash, nowTs, userId)) {
                        return@use rollbackAndReturn(connection) { StaffInviteDeclineResult.InvalidOrExpired }
                    }
                    connection.commit()
                    StaffInviteDeclineResult.Success
                } catch (e: Exception) {
                    rollbackBestEffort(connection)
                    logger.warn(
                        "Failed to decline staff invite userId={}: {}",
                        userId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) { "declineInvite exception userId=$userId" }
                    StaffInviteDeclineResult.DatabaseError
                } finally {
                    connection.autoCommit = initialAutoCommit
                }
            }
        }
    }

    private fun loadActiveInvite(
        connection: Connection,
        codeHash: String,
        nowTs: Instant,
        forUpdate: Boolean = true,
    ): StaffInviteRow? {
        val lockClause = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT venue_id, role, created_by_user_id, expires_at
            FROM venue_staff_invites
            WHERE code_hash = ?
              AND used_at IS NULL
              AND revoked_at IS NULL
              AND expires_at > ?
            $lockClause
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, codeHash)
            statement.setTimestamp(2, java.sql.Timestamp.from(nowTs))
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StaffInviteRow(
                        venueId = rs.getLong("venue_id"),
                        role = rs.getString("role"),
                        createdByUserId = rs.getLong("created_by_user_id"),
                        expiresAt = rs.getTimestamp("expires_at").toInstant(),
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun loadMember(
        connection: Connection,
        venueId: Long,
        userId: Long,
        forUpdate: Boolean = false,
    ): VenueStaffMember? {
        val lockClause = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT role, created_at, invited_by_user_id
            FROM venue_members
            WHERE venue_id = ? AND user_id = ?
            $lockClause
            """.trimIndent(),
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
                        invitedByUserId = rs.getLong("invited_by_user_id").takeIf { !rs.wasNull() },
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun updateMemberRole(
        connection: Connection,
        member: VenueStaffMember,
        role: String,
    ): VenueStaffMember {
        connection.prepareStatement(
            """
            UPDATE venue_members
            SET role = ?
            WHERE venue_id = ? AND user_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, role)
            statement.setLong(2, member.venueId)
            statement.setLong(3, member.userId)
            statement.executeUpdate()
        }
        return member.copy(role = role)
    }

    private fun venueRoleRank(role: VenueRole): Int =
        when (role) {
            VenueRole.STAFF -> 1
            VenueRole.MANAGER -> 2
            VenueRole.OWNER -> 3
        }

    private fun markInviteUsed(
        connection: Connection,
        codeHash: String,
        nowTs: Instant,
        userId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            UPDATE venue_staff_invites
            SET used_at = ?, used_by_user_id = ?
            WHERE code_hash = ?
              AND used_at IS NULL
              AND revoked_at IS NULL
              AND expires_at > ?
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, java.sql.Timestamp.from(nowTs))
            statement.setLong(2, userId)
            statement.setString(3, codeHash)
            statement.setTimestamp(4, java.sql.Timestamp.from(nowTs))
            statement.executeUpdate() == 1
        }

    private fun loadPendingInviteRows(
        connection: Connection,
        venueId: Long,
        allowedRoles: List<String>,
        nowTs: Instant,
    ): List<PendingStaffInviteRow> {
        val rolePlaceholders = allowedRoles.joinToString(separator = ",") { "?" }
        return connection.prepareStatement(
            """
            SELECT code_hash, role, created_at, expires_at
            FROM venue_staff_invites
            WHERE venue_id = ?
              AND role IN ($rolePlaceholders)
              AND used_at IS NULL
              AND revoked_at IS NULL
              AND expires_at > ?
            ORDER BY created_at DESC, code_hash
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            allowedRoles.forEachIndexed { index, role ->
                statement.setString(index + 2, role)
            }
            statement.setTimestamp(allowedRoles.size + 2, java.sql.Timestamp.from(nowTs))
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            PendingStaffInviteRow(
                                codeHash = rs.getString("code_hash"),
                                role = rs.getString("role"),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                                expiresAt = rs.getTimestamp("expires_at").toInstant(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun PendingStaffInviteRow.toPendingInvite(): PendingStaffInvite =
        PendingStaffInvite(
            handle = deriveOpaqueHandle(codeHash),
            role = role,
            createdAt = createdAt,
            expiresAt = expiresAt,
        )

    private fun normalizeAllowedRoles(roles: Set<String>): List<String> =
        roles
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

    private fun deriveOpaqueHandle(codeHash: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pepper.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal("$STAFF_INVITE_HANDLE_CONTEXT:$codeHash".toByteArray(Charsets.UTF_8))
        return STAFF_INVITE_HANDLE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun normalizeOpaqueHandle(raw: String): String? {
        val handle = raw.trim()
        if (handle.length != STAFF_INVITE_HANDLE_LENGTH || !handle.startsWith(STAFF_INVITE_HANDLE_PREFIX)) {
            return null
        }
        val encoded = handle.removePrefix(STAFF_INVITE_HANDLE_PREFIX)
        if (encoded.any { !it.isLetterOrDigit() && it != '-' && it != '_' }) {
            return null
        }
        return handle
    }

    private fun opaqueHandlesEqual(
        expected: String,
        actual: String,
    ): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            actual.toByteArray(Charsets.US_ASCII),
        )

    private fun appendInviteAudit(
        connection: Connection,
        auditLogRepository: AuditLogRepository,
        actorUserId: Long,
        action: String,
        venueId: Long,
        handle: String,
        targetRole: String,
    ) {
        auditLogRepository.appendJson(
            connection = connection,
            actorUserId = actorUserId,
            action = action,
            entityType = STAFF_INVITE_AUDIT_ENTITY_TYPE,
            entityId = null,
            payload =
                buildJsonObject {
                    put("venueId", venueId)
                    put("inviteHandle", handle)
                    put("targetRole", targetRole.trim().uppercase(Locale.ROOT))
                },
        )
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

    private fun <T> rollbackAndReturn(
        connection: Connection,
        block: () -> T,
    ): T {
        runCatching { connection.rollback() }
        return block()
    }
}

data class StaffInviteCodeResult(
    val code: String,
    val expiresAt: Instant,
    val ttlSeconds: Long,
    val handle: String = "",
)

data class PendingStaffInvite(
    val handle: String,
    val role: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)

sealed interface StaffInviteRevokeResult {
    data class Success(
        val invite: PendingStaffInvite,
        val revokedAt: Instant,
    ) : StaffInviteRevokeResult

    data object InvalidOrExpired : StaffInviteRevokeResult

    data object DatabaseError : StaffInviteRevokeResult
}

data class StaffInvitePreview(
    val venueId: Long,
    val role: String,
    val createdByUserId: Long,
    val expiresAt: Instant,
)

sealed interface StaffInvitePreviewResult {
    data class Success(val invite: StaffInvitePreview) : StaffInvitePreviewResult

    data object InvalidOrExpired : StaffInvitePreviewResult

    data object DatabaseError : StaffInvitePreviewResult
}

sealed interface StaffInviteAcceptResult {
    data class Success(
        val member: VenueStaffMember,
        val alreadyMember: Boolean,
        val invitedRole: String,
        val inviteCreatedByUserId: Long,
        val roleChanged: Boolean = false,
        val keptHigherRole: Boolean = false,
    ) : StaffInviteAcceptResult

    data object InvalidOrExpired : StaffInviteAcceptResult

    data object DatabaseError : StaffInviteAcceptResult
}

sealed interface StaffInviteDeclineResult {
    data object Success : StaffInviteDeclineResult

    data object InvalidOrExpired : StaffInviteDeclineResult

    data object DatabaseError : StaffInviteDeclineResult
}

private data class StaffInviteRow(
    val venueId: Long,
    val role: String,
    val createdByUserId: Long,
    val expiresAt: Instant,
)

private data class PendingStaffInviteRow(
    val codeHash: String,
    val role: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)

const val STAFF_INVITE_CREATED_AUDIT_ACTION = "STAFF_INVITE_CREATED"
const val STAFF_INVITE_REVOKED_AUDIT_ACTION = "STAFF_INVITE_REVOKED"

private const val STAFF_INVITE_AUDIT_ENTITY_TYPE = "staff_invite"
private const val STAFF_INVITE_HANDLE_PREFIX = "sih_"
private const val STAFF_INVITE_HANDLE_CONTEXT = "staff-invite-handle-v1"
private const val STAFF_INVITE_HANDLE_LENGTH = 47
