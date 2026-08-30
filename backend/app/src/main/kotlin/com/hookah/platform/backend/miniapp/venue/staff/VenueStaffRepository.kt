package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.TransactionalTargetedAuditLogWriter
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.VenueRoleMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.Locale
import javax.sql.DataSource

class VenueStaffRepository(
    private val dataSource: DataSource?,
    private val auditLogWriter: TransactionalTargetedAuditLogWriter = AuditLogRepository(dataSource),
) {
    private val logger = LoggerFactory.getLogger(VenueStaffRepository::class.java)

    suspend fun listMembers(
        venueId: Long,
        allowedRoles: Set<VenueRole>? = null,
    ): List<VenueStaffMember> {
        val ds = dataSource ?: return emptyList()
        if (allowedRoles != null && allowedRoles.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                loadMemberProjections(connection, venueId, allowedRoles = allowedRoles)
            }
        }
    }

    suspend fun findMember(
        venueId: Long,
        userId: Long,
    ): VenueStaffMember? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                loadMemberProjections(connection, venueId, userId = userId).singleOrNull()
            }
        }
    }

    suspend fun updateRoleWithOwnerGuard(
        venueId: Long,
        actorUserId: Long,
        targetUserId: Long,
        newRole: VenueRole,
        source: VenueStaffMutationSource,
    ): VenueStaffUpdateResult {
        val ds = dataSource ?: return VenueStaffUpdateResult.DatabaseError
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val initialAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val members =
                        lockMembershipsForMutation(
                            connection = connection,
                            venueId = venueId,
                            actorUserId = actorUserId,
                            targetUserId = targetUserId,
                        )
                    val actorMember = members.firstOrNull { it.userId == actorUserId }
                    if (actorMember == null || !isOwnerLikeRole(actorMember.role)) {
                        return@use rollbackAndReturn(connection) { VenueStaffUpdateResult.Forbidden }
                    }
                    val targetMember =
                        members.firstOrNull { it.userId == targetUserId }
                            ?: return@use rollbackAndReturn(connection) { VenueStaffUpdateResult.NotFound }
                    val ownerLikeCount = members.count { isOwnerLikeRole(it.role) }
                    val oldRole = targetMember.role.trim().uppercase(Locale.ROOT)
                    if (oldRole == newRole.name) {
                        val currentMember =
                            loadMemberProjections(connection, venueId, userId = targetUserId).singleOrNull()
                                ?: throw SQLException("Locked venue member projection disappeared")
                        connection.commit()
                        return@use VenueStaffUpdateResult.Success(
                            member = currentMember,
                            outcome = VenueStaffMutationOutcome.NO_OP,
                        )
                    }
                    val isDemotion = isOwnerLikeRole(oldRole) && newRole != VenueRole.OWNER
                    if (isDemotion && ownerLikeCount <= 1) {
                        return@use rollbackAndReturn(connection) { VenueStaffUpdateResult.LastOwner }
                    }
                    connection.prepareStatement(
                        """
                        UPDATE venue_members
                        SET role = ?
                        WHERE venue_id = ? AND user_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, newRole.name)
                        statement.setLong(2, venueId)
                        statement.setLong(3, targetUserId)
                        if (statement.executeUpdate() == 0) {
                            return@use rollbackAndReturn(connection) { VenueStaffUpdateResult.NotFound }
                        }
                    }
                    val updatedMember =
                        loadMemberProjections(connection, venueId, userId = targetUserId).singleOrNull()
                            ?: throw IllegalStateException("Updated venue member disappeared")
                    auditLogWriter.appendTargetedJson(
                        connection = connection,
                        actorUserId = actorUserId,
                        targetUserId = targetUserId,
                        action = VENUE_STAFF_ROLE_CHANGED_ACTION,
                        entityType = "venue",
                        entityId = venueId,
                        payload =
                            buildJsonObject {
                                put("oldRole", oldRole)
                                put("newRole", newRole.name)
                                put("source", source.name)
                            },
                    )
                    connection.commit()
                    VenueStaffUpdateResult.Success(
                        member = updatedMember,
                        outcome = VenueStaffMutationOutcome.APPLIED,
                    )
                } catch (e: Exception) {
                    rollbackBestEffort(connection)
                    logMutationFailure(VENUE_STAFF_ROLE_CHANGED_ACTION, venueId, e)
                    VenueStaffUpdateResult.DatabaseError
                } finally {
                    connection.autoCommit = initialAutoCommit
                }
            }
        }
    }

    suspend fun removeMemberWithOwnerGuard(
        venueId: Long,
        actorUserId: Long,
        targetUserId: Long,
        source: VenueStaffMutationSource,
    ): VenueStaffRemoveResult {
        val ds = dataSource ?: return VenueStaffRemoveResult.DatabaseError
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val initialAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val members =
                        lockMembershipsForMutation(
                            connection = connection,
                            venueId = venueId,
                            actorUserId = actorUserId,
                            targetUserId = targetUserId,
                        )
                    val actorMember = members.firstOrNull { it.userId == actorUserId }
                    if (actorMember == null || !isOwnerLikeRole(actorMember.role)) {
                        return@use rollbackAndReturn(connection) { VenueStaffRemoveResult.Forbidden }
                    }
                    val targetMember =
                        members.firstOrNull { it.userId == targetUserId }
                            ?: return@use rollbackAndReturn(connection) { VenueStaffRemoveResult.NotFound }
                    val ownerLikeCount = members.count { isOwnerLikeRole(it.role) }
                    val oldRole = targetMember.role.trim().uppercase(Locale.ROOT)
                    if (isOwnerLikeRole(oldRole) && ownerLikeCount <= 1) {
                        return@use rollbackAndReturn(connection) { VenueStaffRemoveResult.LastOwner }
                    }
                    connection.prepareStatement(
                        """
                        DELETE FROM venue_members
                        WHERE venue_id = ? AND user_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, targetUserId)
                        if (statement.executeUpdate() == 0) {
                            return@use rollbackAndReturn(connection) { VenueStaffRemoveResult.NotFound }
                        }
                    }
                    auditLogWriter.appendTargetedJson(
                        connection = connection,
                        actorUserId = actorUserId,
                        targetUserId = targetUserId,
                        action = VENUE_STAFF_MEMBER_REMOVED_ACTION,
                        entityType = "venue",
                        entityId = venueId,
                        payload =
                            buildJsonObject {
                                put("oldRole", oldRole)
                                put("source", source.name)
                            },
                    )
                    connection.commit()
                    VenueStaffRemoveResult.Success
                } catch (e: Exception) {
                    rollbackBestEffort(connection)
                    logMutationFailure(VENUE_STAFF_MEMBER_REMOVED_ACTION, venueId, e)
                    VenueStaffRemoveResult.DatabaseError
                } finally {
                    connection.autoCommit = initialAutoCommit
                }
            }
        }
    }

    suspend fun createMember(
        venueId: Long,
        userId: Long,
        role: String,
        invitedByUserId: Long?,
    ): VenueStaffMember? {
        val ds = dataSource ?: return null
        val createdAt = Instant.now()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                createMemberInTransaction(connection, venueId, userId, role, invitedByUserId, createdAt)
            }
        }
    }

    fun createMemberInTransaction(
        connection: Connection,
        venueId: Long,
        userId: Long,
        role: String,
        invitedByUserId: Long?,
        createdAt: Instant = Instant.now(),
    ): VenueStaffMember? {
        return try {
            connection.prepareStatement(
                """
                INSERT INTO venue_members (venue_id, user_id, role, created_at, invited_by_user_id)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.setTimestamp(4, java.sql.Timestamp.from(createdAt))
                if (invitedByUserId != null) {
                    statement.setLong(
                        5,
                        invitedByUserId,
                    )
                } else {
                    statement.setNull(5, java.sql.Types.BIGINT)
                }
                statement.executeUpdate()
            }
            loadMemberProjections(connection, venueId, userId = userId).singleOrNull()
        } catch (e: Exception) {
            logger.warn("Failed to create venue member error_type={}", e::class.simpleName ?: "unknown")
            null
        }
    }

    private fun lockMembershipsForMutation(
        connection: Connection,
        venueId: Long,
        actorUserId: Long,
        targetUserId: Long,
    ): List<LockedVenueMembership> {
        return connection.prepareStatement(
            """
            SELECT user_id, role
            FROM venue_members
            WHERE venue_id = ?
              AND (user_id IN (?, ?) OR UPPER(role) = 'OWNER')
            ORDER BY user_id
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, actorUserId)
            statement.setLong(3, targetUserId)
            statement.executeQuery().use { rs ->
                val members = mutableListOf<LockedVenueMembership>()
                while (rs.next()) {
                    members.add(
                        LockedVenueMembership(
                            userId = rs.getLong("user_id"),
                            role = rs.getString("role"),
                        ),
                    )
                }
                members
            }
        }
    }

    private fun loadMemberProjections(
        connection: Connection,
        venueId: Long,
        userId: Long? = null,
        allowedRoles: Set<VenueRole>? = null,
    ): List<VenueStaffMember> {
        val normalizedRoles = allowedRoles?.map { it.name }?.sorted().orEmpty()
        val userFilter = if (userId == null) "" else "AND vm.user_id = ?"
        val roleFilter =
            if (allowedRoles == null) {
                ""
            } else {
                "AND UPPER(vm.role) IN (${normalizedRoles.joinToString(",") { "?" }})"
            }
        return connection.prepareStatement(
            """
            SELECT vm.user_id,
                   vm.role,
                   vm.created_at,
                   vm.invited_by_user_id,
                   u.username,
                   u.first_name,
                   u.last_name,
                   COALESCE(active_links.active_profile_count, 0) AS active_profile_count,
                   active_links.single_profile_id,
                   linked_profile.display_name AS linked_profile_display_name
            FROM venue_members vm
            JOIN users u ON u.telegram_user_id = vm.user_id
            LEFT JOIN (
                SELECT venue_id,
                       linked_user_id,
                       COUNT(*) AS active_profile_count,
                       MIN(id) AS single_profile_id
                FROM staff_profiles
                WHERE linked_user_id IS NOT NULL
                  AND disabled_at IS NULL
                  AND venue_id = ?
                GROUP BY venue_id, linked_user_id
            ) active_links
              ON active_links.venue_id = vm.venue_id
             AND active_links.linked_user_id = vm.user_id
            LEFT JOIN staff_profiles linked_profile
              ON linked_profile.venue_id = vm.venue_id
             AND linked_profile.id = active_links.single_profile_id
            WHERE vm.venue_id = ?
              $userFilter
              $roleFilter
            ORDER BY vm.created_at, vm.user_id
            """.trimIndent(),
        ).use { statement ->
            var parameterIndex = 1
            statement.setLong(parameterIndex++, venueId)
            statement.setLong(parameterIndex++, venueId)
            if (userId != null) statement.setLong(parameterIndex++, userId)
            normalizedRoles.forEach { role -> statement.setString(parameterIndex++, role) }
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val memberUserId = rs.getLong("user_id")
                        val rawRole = rs.getString("role")
                        val role = VenueRoleMapping.fromDb(rawRole)
                        if (role == null) {
                            logger.warn("Unknown venue role in membership projection")
                        } else {
                            val username = normalizeTelegramUsername(rs.getString("username"))
                            val activeProfileCount = rs.getInt("active_profile_count")
                            val singleProfileId =
                                rs.getLong("single_profile_id")
                                    .takeIf { !rs.wasNull() && activeProfileCount == 1 }
                            val linkedProfileDisplayName =
                                rs.getString("linked_profile_display_name")
                                    ?.takeIf { activeProfileCount == 1 }
                            add(
                                VenueStaffMember(
                                    venueId = venueId,
                                    userId = memberUserId,
                                    role = role.name,
                                    createdAt = rs.getTimestamp("created_at").toInstant(),
                                    invitedByUserId = rs.getLong("invited_by_user_id").takeIf { !rs.wasNull() },
                                    displayName =
                                        buildSafeMemberDisplayName(
                                            firstName = rs.getString("first_name"),
                                            lastName = rs.getString("last_name"),
                                            username = username,
                                        ),
                                    username = username,
                                    linkedStaffProfileId = singleProfileId,
                                    linkedStaffProfileDisplayName = linkedProfileDisplayName,
                                    profileLinkState =
                                        when {
                                            activeProfileCount > 1 -> VenueStaffProfileLinkState.DUPLICATE_LINK_DETECTED
                                            activeProfileCount == 1 -> VenueStaffProfileLinkState.LINKED
                                            else -> VenueStaffProfileLinkState.NOT_LINKED
                                        },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun isOwnerLikeRole(role: String): Boolean {
        return role.trim().uppercase(Locale.ROOT) == "OWNER"
    }

    private fun rollbackBestEffort(connection: Connection) {
        runCatching { connection.rollback() }
    }

    private fun logMutationFailure(
        action: String,
        venueId: Long,
        error: Exception,
    ) {
        val sqlState =
            generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<SQLException>()
                .firstOrNull()
                ?.sqlState
        logger.warn(
            "Venue staff membership mutation failed action={} venueId={} exceptionClass={} sqlState={}",
            action,
            venueId,
            error::class.java.simpleName,
            sqlState,
        )
    }

    private fun <T> rollbackAndReturn(
        connection: Connection,
        block: () -> T,
    ): T {
        runCatching { connection.rollback() }
        return block()
    }
}

private data class LockedVenueMembership(
    val userId: Long,
    val role: String,
)

data class VenueStaffMember(
    val venueId: Long,
    val userId: Long,
    val role: String,
    val createdAt: Instant,
    val invitedByUserId: Long?,
    val displayName: String = DEFAULT_STAFF_MEMBER_DISPLAY_NAME,
    val username: String? = null,
    val active: Boolean = true,
    val linkedStaffProfileId: Long? = null,
    val linkedStaffProfileDisplayName: String? = null,
    val profileLinkState: VenueStaffProfileLinkState = VenueStaffProfileLinkState.NOT_LINKED,
)

enum class VenueStaffProfileLinkState {
    NOT_LINKED,
    LINKED,
    DUPLICATE_LINK_DETECTED,
    PROTECTED,
}

sealed interface VenueStaffUpdateResult {
    data class Success(
        val member: VenueStaffMember,
        val outcome: VenueStaffMutationOutcome = VenueStaffMutationOutcome.APPLIED,
    ) : VenueStaffUpdateResult

    data object Forbidden : VenueStaffUpdateResult

    data object NotFound : VenueStaffUpdateResult

    data object LastOwner : VenueStaffUpdateResult

    data object DatabaseError : VenueStaffUpdateResult
}

sealed interface VenueStaffRemoveResult {
    data object Success : VenueStaffRemoveResult

    data object Forbidden : VenueStaffRemoveResult

    data object NotFound : VenueStaffRemoveResult

    data object LastOwner : VenueStaffRemoveResult

    data object DatabaseError : VenueStaffRemoveResult
}

enum class VenueStaffMutationOutcome {
    APPLIED,
    NO_OP,
}

enum class VenueStaffMutationSource {
    VENUE_MINI_APP,
    TELEGRAM_BOT,
}

const val VENUE_STAFF_ROLE_CHANGED_ACTION = "VENUE_STAFF_ROLE_CHANGED"
const val VENUE_STAFF_MEMBER_REMOVED_ACTION = "VENUE_STAFF_MEMBER_REMOVED"

internal fun normalizeTelegramUsername(raw: String?): String? =
    raw
        ?.trim()
        ?.removePrefix("@")
        ?.takeIf { it.isNotBlank() }

internal fun buildSafeMemberDisplayName(
    firstName: String?,
    lastName: String?,
    username: String?,
): String {
    val fullName =
        listOfNotNull(firstName?.trim(), lastName?.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
    return fullName.takeIf { it.isNotBlank() }
        ?: username
        ?: DEFAULT_STAFF_MEMBER_DISPLAY_NAME
}

private const val DEFAULT_STAFF_MEMBER_DISPLAY_NAME = "Сотрудник"
