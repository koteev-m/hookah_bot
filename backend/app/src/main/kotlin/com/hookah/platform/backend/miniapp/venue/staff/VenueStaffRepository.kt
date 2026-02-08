package com.hookah.platform.backend.miniapp.venue.staff

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

    suspend fun listMembers(venueId: Long): List<VenueStaffMember> {
        val ds = dataSource ?: return emptyList()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT user_id, role, created_at, invited_by_user_id
                    FROM venue_members
                    WHERE venue_id = ?
                    ORDER BY created_at, user_id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { rs ->
                        val result = mutableListOf<VenueStaffMember>()
                        while (rs.next()) {
                            val roleRaw = rs.getString("role")
                            val role = VenueRoleMapping.fromDb(roleRaw)
                            if (role == null) {
                                logger.warn(
                                    "Unknown venue role {} for venueId={} userId={}",
                                    roleRaw,
                                    venueId,
                                    rs.getLong("user_id"),
                                )
                                continue
                            }
                            result.add(
                                VenueStaffMember(
                                    venueId = venueId,
                                    userId = rs.getLong("user_id"),
                                    role = role.name,
                                    createdAt = rs.getTimestamp("created_at").toInstant(),
                                    invitedByUserId = rs.getLong("invited_by_user_id").takeIf { !rs.wasNull() },
                                ),
                            )
                        }
                        result
                    }
                }
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
                connection.prepareStatement(
                    """
                    SELECT role, created_at, invited_by_user_id
                    FROM venue_members
                    WHERE venue_id = ? AND user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, userId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            val roleRaw = rs.getString("role")
                            val role = VenueRoleMapping.fromDb(roleRaw) ?: return@withContext null
                            VenueStaffMember(
                                venueId = venueId,
                                userId = userId,
                                role = role.name,
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                                invitedByUserId = rs.getLong("invited_by_user_id").takeIf { !rs.wasNull() },
                            )
                        } else {
                            null
                        }
                    }
                }
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
                    connection.commit()
                    VenueStaffUpdateResult.Success(
                        currentMember.copy(role = newRole),
                    )
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
            VenueStaffMember(
                venueId = venueId,
                userId = userId,
                role = role,
                createdAt = createdAt,
                invitedByUserId = invitedByUserId,
            )
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
            WHERE venue_id = ? AND (user_id = ? OR UPPER(role) IN ('OWNER','ADMIN'))
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

    private fun isOwnerLikeRole(role: String): Boolean {
        return role.trim().uppercase(Locale.ROOT) in setOf("OWNER", "ADMIN")
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
)

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
