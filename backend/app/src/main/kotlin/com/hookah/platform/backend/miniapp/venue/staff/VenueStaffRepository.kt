package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.VenueRoleMapping
import com.hookah.platform.backend.telegram.debugTelegramException
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.util.Locale
import javax.sql.DataSource

class VenueStaffRepository(private val dataSource: DataSource?) {
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

    suspend fun updateRole(
        venueId: Long,
        userId: Long,
        role: String,
    ): Boolean {
        val ds = dataSource ?: return false
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE venue_members
                    SET role = ?
                    WHERE venue_id = ? AND user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, role)
                    statement.setLong(2, venueId)
                    statement.setLong(3, userId)
                    statement.executeUpdate() > 0
                }
            }
        }
    }

    suspend fun updateRoleWithOwnerGuard(
        venueId: Long,
        userId: Long,
        newRole: String,
    ): VenueStaffUpdateResult {
        val ds = dataSource ?: return VenueStaffUpdateResult.DatabaseError
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val initialAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val members = loadMembersForOwnerGuard(connection, venueId, userId)
                    val currentMember =
                        members.firstOrNull { it.userId == userId }
                            ?: return@use rollbackAndReturn(connection) { VenueStaffUpdateResult.NotFound }
                    val ownerLikeCount = members.count { isOwnerLikeRole(it.role) }
                    val isDemotion = isOwnerLikeRole(currentMember.role) && !isOwnerLikeRole(newRole)
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
                        statement.setString(1, newRole)
                        statement.setLong(2, venueId)
                        statement.setLong(3, userId)
                        if (statement.executeUpdate() == 0) {
                            return@use rollbackAndReturn(connection) { VenueStaffUpdateResult.NotFound }
                        }
                    }
                    val updatedMember =
                        loadMemberProjections(connection, venueId, userId = userId).singleOrNull()
                            ?: throw IllegalStateException("Updated venue member disappeared")
                    connection.commit()
                    VenueStaffUpdateResult.Success(updatedMember)
                } catch (e: Exception) {
                    rollbackBestEffort(connection)
                    logger.warn(
                        "Failed to update venue member venueId={} userId={}: {}",
                        venueId,
                        userId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(
                        e,
                    ) { "updateRoleWithOwnerGuard exception venueId=$venueId userId=$userId" }
                    VenueStaffUpdateResult.DatabaseError
                } finally {
                    connection.autoCommit = initialAutoCommit
                }
            }
        }
    }

    suspend fun removeMember(
        venueId: Long,
        userId: Long,
    ): Boolean {
        val ds = dataSource ?: return false
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    DELETE FROM venue_members
                    WHERE venue_id = ? AND user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, userId)
                    statement.executeUpdate() > 0
                }
            }
        }
    }

    suspend fun removeMemberWithOwnerGuard(
        venueId: Long,
        userId: Long,
    ): VenueStaffRemoveResult {
        val ds = dataSource ?: return VenueStaffRemoveResult.DatabaseError
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val initialAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val members = loadMembersForOwnerGuard(connection, venueId, userId)
                    val currentMember =
                        members.firstOrNull { it.userId == userId }
                            ?: return@use rollbackAndReturn(connection) { VenueStaffRemoveResult.NotFound }
                    val ownerLikeCount = members.count { isOwnerLikeRole(it.role) }
                    if (isOwnerLikeRole(currentMember.role) && ownerLikeCount <= 1) {
                        return@use rollbackAndReturn(connection) { VenueStaffRemoveResult.LastOwner }
                    }
                    connection.prepareStatement(
                        """
                        DELETE FROM venue_members
                        WHERE venue_id = ? AND user_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, userId)
                        if (statement.executeUpdate() == 0) {
                            return@use rollbackAndReturn(connection) { VenueStaffRemoveResult.NotFound }
                        }
                    }
                    connection.commit()
                    VenueStaffRemoveResult.Success
                } catch (e: Exception) {
                    rollbackBestEffort(connection)
                    logger.warn(
                        "Failed to remove venue member venueId={} userId={}: {}",
                        venueId,
                        userId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(
                        e,
                    ) { "removeMemberWithOwnerGuard exception venueId=$venueId userId=$userId" }
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
            logger.warn(
                "Failed to create venue member venueId={} userId={}: {}",
                venueId,
                userId,
                sanitizeTelegramForLog(e.message),
            )
            logger.debugTelegramException(e) { "createMember exception venueId=$venueId userId=$userId" }
            null
        }
    }

    private fun loadMembersForOwnerGuard(
        connection: Connection,
        venueId: Long,
        userId: Long,
    ): List<VenueStaffMember> {
        return connection.prepareStatement(
            """
            SELECT user_id, role, created_at, invited_by_user_id
            FROM venue_members
            WHERE venue_id = ? AND (user_id = ? OR UPPER(role) = 'OWNER')
            ORDER BY user_id
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, userId)
            statement.executeQuery().use { rs ->
                val members = mutableListOf<VenueStaffMember>()
                while (rs.next()) {
                    members.add(
                        VenueStaffMember(
                            venueId = venueId,
                            userId = rs.getLong("user_id"),
                            role = rs.getString("role"),
                            createdAt = rs.getTimestamp("created_at").toInstant(),
                            invitedByUserId = rs.getLong("invited_by_user_id").takeIf { !rs.wasNull() },
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
                            logger.warn(
                                "Unknown venue role {} for venueId={} userId={}",
                                rawRole,
                                venueId,
                                memberUserId,
                            )
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

    private fun <T> rollbackAndReturn(
        connection: Connection,
        block: () -> T,
    ): T {
        runCatching { connection.rollback() }
        return block()
    }
}

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
    data class Success(val member: VenueStaffMember) : VenueStaffUpdateResult

    data object NotFound : VenueStaffUpdateResult

    data object LastOwner : VenueStaffUpdateResult

    data object DatabaseError : VenueStaffUpdateResult
}

sealed interface VenueStaffRemoveResult {
    data object Success : VenueStaffRemoveResult

    data object NotFound : VenueStaffRemoveResult

    data object LastOwner : VenueStaffRemoveResult

    data object DatabaseError : VenueStaffRemoveResult
}

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
