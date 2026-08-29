package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter
import com.hookah.platform.backend.miniapp.venue.TransactionalTargetedAuditLogWriter
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.VenueRoleMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
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
        auditLogRepository: TransactionalAuditLogWriter,
    ): StaffInviteCodeResult? =
        createInviteInternal(
            venueId = venueId,
            createdByUserId = createdByUserId,
            role = role,
            ttlSeconds = ttlSeconds,
            auditLogWriter = auditLogRepository,
            maxActivePendingPerVenueRole = null,
        ).inviteOrNull()

    suspend fun createBoundedInvite(
        venueId: Long,
        createdByUserId: Long,
        role: String,
        ttlSeconds: Long,
        maxActivePendingPerVenueRole: Int,
        auditLogRepository: TransactionalAuditLogWriter,
    ): StaffInviteCreateResult {
        require(maxActivePendingPerVenueRole > 0) {
            "maxActivePendingPerVenueRole must be positive"
        }
        return createInviteInternal(
            venueId = venueId,
            createdByUserId = createdByUserId,
            role = role,
            ttlSeconds = ttlSeconds,
            auditLogWriter = auditLogRepository,
            maxActivePendingPerVenueRole = maxActivePendingPerVenueRole,
        )
    }

    private suspend fun createInviteInternal(
        venueId: Long,
        createdByUserId: Long,
        role: String,
        ttlSeconds: Long,
        auditLogWriter: TransactionalAuditLogWriter?,
        maxActivePendingPerVenueRole: Int?,
    ): StaffInviteCreateResult {
        val ds = dataSource ?: return StaffInviteCreateResult.DatabaseError
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
                    if (maxActivePendingPerVenueRole != null) {
                        if (!lockVenueForInviteCreation(connection, venueId)) {
                            return@use rollbackAndReturn(connection) {
                                StaffInviteCreateResult.DatabaseError
                            }
                        }
                        activePendingInviteRetryAfterSeconds(
                            connection = connection,
                            venueId = venueId,
                            role = role,
                            nowTs = nowTs,
                            maxActivePending = maxActivePendingPerVenueRole,
                        )?.let { retryAfterSeconds ->
                            return@use rollbackAndReturn(connection) {
                                StaffInviteCreateResult.RateLimited(retryAfterSeconds)
                            }
                        }
                    }
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
                    if (auditLogWriter != null) {
                        appendInviteAudit(
                            connection = connection,
                            auditLogWriter = auditLogWriter,
                            actorUserId = createdByUserId,
                            action = createAuditActionFor(role),
                            venueId = venueId,
                            handle = handle,
                            targetRole = role,
                        )
                    }
                    connection.commit()
                    StaffInviteCreateResult.Success(
                        StaffInviteCodeResult(
                            code = code,
                            expiresAt = expiresAt,
                            ttlSeconds = ttlSeconds,
                            handle = handle,
                        ),
                    )
                } catch (e: Exception) {
                    rollbackBestEffort(connection)
                    logger.warn("Failed to create staff invite error_type={}", e::class.simpleName ?: "unknown")
                    StaffInviteCreateResult.DatabaseError
                } finally {
                    connection.autoCommit = initialAutoCommit
                }
            }
        }
    }

    private fun lockVenueForInviteCreation(
        connection: Connection,
        venueId: Long,
    ): Boolean =
        connection.prepareStatement(
            "SELECT id FROM venues WHERE id = ? FOR UPDATE",
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { resultSet -> resultSet.next() }
        }

    private fun activePendingInviteRetryAfterSeconds(
        connection: Connection,
        venueId: Long,
        role: String,
        nowTs: Instant,
        maxActivePending: Int,
    ): Long? =
        connection.prepareStatement(
            """
            SELECT COUNT(*) AS active_count, MIN(expires_at) AS earliest_expiry
            FROM venue_staff_invites
            WHERE venue_id = ?
              AND role = ?
              AND used_at IS NULL
              AND revoked_at IS NULL
              AND expires_at > ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, role)
            statement.setTimestamp(3, java.sql.Timestamp.from(nowTs))
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "active invite count query returned no row" }
                if (resultSet.getLong("active_count") < maxActivePending) {
                    null
                } else {
                    val earliestExpiry =
                        resultSet.getTimestamp("earliest_expiry")?.toInstant()
                            ?: return@use 1L
                    val retryAfterMillis =
                        Duration.between(nowTs, earliestExpiry).toMillis().coerceAtLeast(1L)
                    ((retryAfterMillis + 999L) / 1_000L).coerceAtLeast(1L)
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
                    logger.warn("Failed to list pending staff invites error_type={}", e::class.simpleName ?: "unknown")
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
        auditLogRepository: TransactionalAuditLogWriter,
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
                        auditLogWriter = auditLogRepository,
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
                    logger.warn("Failed to revoke pending staff invite error_type={}", e::class.simpleName ?: "unknown")
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
                    logger.warn("Failed to preview staff invite error_type={}", e::class.simpleName ?: "unknown")
                    StaffInvitePreviewResult.DatabaseError
                }
            }
        }
    }

    suspend fun acceptInvite(
        code: String,
        userId: Long,
        auditLogWriter: TransactionalTargetedAuditLogWriter,
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
                            return@use commitAcceptedInvite(
                                connection = connection,
                                auditLogWriter = auditLogWriter,
                                codeHash = codeHash,
                                actorUserId = userId,
                                result =
                                    StaffInviteAcceptResult.Success(
                                        member = updatedMember,
                                        alreadyMember = true,
                                        invitedRole = invite.role,
                                        roleChanged = true,
                                    ),
                            )
                        }
                        if (!markInviteUsed(connection, codeHash, nowTs, userId)) {
                            return@use rollbackAndReturn(connection) { StaffInviteAcceptResult.InvalidOrExpired }
                        }
                        return@use commitAcceptedInvite(
                            connection = connection,
                            auditLogWriter = auditLogWriter,
                            codeHash = codeHash,
                            actorUserId = userId,
                            result =
                                StaffInviteAcceptResult.Success(
                                    member = normalizedExistingMember,
                                    alreadyMember = true,
                                    invitedRole = invite.role,
                                    keptHigherRole = existingRank > invitedRank,
                                ),
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
                            return@use commitAcceptedInvite(
                                connection = connection,
                                auditLogWriter = auditLogWriter,
                                codeHash = codeHash,
                                actorUserId = userId,
                                result =
                                    StaffInviteAcceptResult.Success(
                                        member = existingAfterInsert,
                                        alreadyMember = true,
                                        invitedRole = invite.role,
                                    ),
                            )
                        }
                        return@use rollbackAndReturn(connection) { StaffInviteAcceptResult.DatabaseError }
                    }
                    if (!markInviteUsed(connection, codeHash, nowTs, userId)) {
                        return@use rollbackAndReturn(connection) { StaffInviteAcceptResult.InvalidOrExpired }
                    }
                    commitAcceptedInvite(
                        connection = connection,
                        auditLogWriter = auditLogWriter,
                        codeHash = codeHash,
                        actorUserId = userId,
                        result =
                            StaffInviteAcceptResult.Success(
                                member = member,
                                alreadyMember = false,
                                invitedRole = invite.role,
                            ),
                    )
                } catch (e: Exception) {
                    rollbackBestEffort(connection)
                    logger.warn("Failed to accept staff invite error_type={}", e::class.simpleName ?: "unknown")
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
                    logger.warn("Failed to decline staff invite error_type={}", e::class.simpleName ?: "unknown")
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
        auditLogWriter: TransactionalAuditLogWriter,
        actorUserId: Long,
        action: String,
        venueId: Long,
        handle: String,
        targetRole: String,
    ) {
        val ownerAction = action == VENUE_OWNER_INVITE_CREATE_AUDIT_ACTION
        auditLogWriter.appendJson(
            connection = connection,
            actorUserId = actorUserId,
            action = action,
            entityType = if (ownerAction) VENUE_OWNER_INVITE_AUDIT_ENTITY_TYPE else STAFF_INVITE_AUDIT_ENTITY_TYPE,
            entityId = venueId.takeIf { ownerAction },
            payload =
                buildJsonObject {
                    put("venueId", venueId)
                    put("inviteHandle", handle)
                    put("targetRole", targetRole.trim().uppercase(Locale.ROOT))
                },
        )
    }

    private fun commitAcceptedInvite(
        connection: Connection,
        auditLogWriter: TransactionalTargetedAuditLogWriter,
        codeHash: String,
        actorUserId: Long,
        result: StaffInviteAcceptResult.Success,
    ): StaffInviteAcceptResult.Success {
        val action = acceptAuditActionFor(result.invitedRole)
        val ownerAction = action == VENUE_OWNER_INVITE_ACCEPT_AUDIT_ACTION
        auditLogWriter.appendTargetedJson(
            connection = connection,
            actorUserId = actorUserId,
            targetUserId = result.member.userId,
            action = action,
            entityType = if (ownerAction) VENUE_OWNER_INVITE_AUDIT_ENTITY_TYPE else STAFF_INVITE_AUDIT_ENTITY_TYPE,
            entityId = result.member.venueId.takeIf { ownerAction },
            payload =
                buildJsonObject {
                    put("venueId", result.member.venueId)
                    put("inviteHandle", deriveOpaqueHandle(codeHash))
                    put("targetRole", result.invitedRole.trim().uppercase(Locale.ROOT))
                    put("alreadyMember", result.alreadyMember)
                    put("roleChanged", result.roleChanged)
                    put("keptHigherRole", result.keptHigherRole)
                },
        )
        connection.commit()
        return result
    }

    private fun createAuditActionFor(role: String): String =
        if (role.equals(VenueRole.OWNER.name, ignoreCase = true)) {
            VENUE_OWNER_INVITE_CREATE_AUDIT_ACTION
        } else {
            STAFF_INVITE_CREATED_AUDIT_ACTION
        }

    private fun acceptAuditActionFor(role: String): String =
        if (role.equals(VenueRole.OWNER.name, ignoreCase = true)) {
            VENUE_OWNER_INVITE_ACCEPT_AUDIT_ACTION
        } else {
            STAFF_INVITE_ACCEPTED_AUDIT_ACTION
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

sealed interface StaffInviteCreateResult {
    data class Success(val invite: StaffInviteCodeResult) : StaffInviteCreateResult

    data class RateLimited(val retryAfterSeconds: Long) : StaffInviteCreateResult

    data object DatabaseError : StaffInviteCreateResult
}

private fun StaffInviteCreateResult.inviteOrNull(): StaffInviteCodeResult? =
    (this as? StaffInviteCreateResult.Success)?.invite

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
const val STAFF_INVITE_ACCEPTED_AUDIT_ACTION = "STAFF_INVITE_ACCEPTED"
const val STAFF_INVITE_REVOKED_AUDIT_ACTION = "STAFF_INVITE_REVOKED"
const val VENUE_OWNER_INVITE_CREATE_AUDIT_ACTION = "VENUE_OWNER_INVITE_CREATE"
const val VENUE_OWNER_INVITE_ACCEPT_AUDIT_ACTION = "VENUE_OWNER_INVITE_ACCEPT"

private const val STAFF_INVITE_AUDIT_ENTITY_TYPE = "staff_invite"
private const val VENUE_OWNER_INVITE_AUDIT_ENTITY_TYPE = "venue"
private const val STAFF_INVITE_HANDLE_PREFIX = "sih_"
private const val STAFF_INVITE_HANDLE_CONTEXT = "staff-invite-handle-v1"
private const val STAFF_INVITE_HANDLE_LENGTH = 47
