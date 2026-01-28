package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.miniapp.venue.VenueRoleMapping
import com.hookah.platform.backend.telegram.debugTelegramException
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
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
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { rs ->
                        val result = mutableListOf<VenueStaffMember>()
                        while (rs.next()) {
                            val roleRaw = rs.getString("role")
                            val role = VenueRoleMapping.fromDb(roleRaw)
                            if (role == null) {
                                logger.warn("Unknown venue role {} for venueId={} userId={}", roleRaw, venueId, rs.getLong("user_id"))
                                continue
                            }
                            result.add(
                                VenueStaffMember(
                                    venueId = venueId,
                                    userId = rs.getLong("user_id"),
                                    role = role.name,
                                    createdAt = rs.getTimestamp("created_at").toInstant(),
                                    invitedByUserId = rs.getLong("invited_by_user_id").takeIf { !rs.wasNull() }
                                )
                            )
                        }
                        result
                    }
                }
            }
        }
    }

    suspend fun findMember(venueId: Long, userId: Long): VenueStaffMember? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
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
                            val roleRaw = rs.getString("role")
                            val role = VenueRoleMapping.fromDb(roleRaw) ?: return@withContext null
                            VenueStaffMember(
                                venueId = venueId,
                                userId = userId,
                                role = role.name,
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                                invitedByUserId = rs.getLong("invited_by_user_id").takeIf { !rs.wasNull() }
                            )
                        } else null
                    }
                }
            }
        }
    }

    suspend fun updateRole(venueId: Long, userId: Long, role: String): Boolean {
        val ds = dataSource ?: return false
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                        UPDATE venue_members
                        SET role = ?
                        WHERE venue_id = ? AND user_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, role)
                    statement.setLong(2, venueId)
                    statement.setLong(3, userId)
                    statement.executeUpdate() > 0
                }
            }
        }
    }

    suspend fun removeMember(venueId: Long, userId: Long): Boolean {
        val ds = dataSource ?: return false
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                        DELETE FROM venue_members
                        WHERE venue_id = ? AND user_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, userId)
                    statement.executeUpdate() > 0
                }
            }
        }
    }

    suspend fun countOwners(venueId: Long): Int {
        val ds = dataSource ?: return 0
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                        SELECT COUNT(*)
                        FROM venue_members
                        WHERE venue_id = ? AND role = 'OWNER'
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        }
    }

    suspend fun createMember(
        venueId: Long,
        userId: Long,
        role: String,
        invitedByUserId: Long?
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
        createdAt: Instant = Instant.now()
    ): VenueStaffMember? {
        return try {
            connection.prepareStatement(
                """
                    INSERT INTO venue_members (venue_id, user_id, role, created_at, invited_by_user_id)
                    VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.setTimestamp(4, java.sql.Timestamp.from(createdAt))
                if (invitedByUserId != null) statement.setLong(5, invitedByUserId) else statement.setNull(5, java.sql.Types.BIGINT)
                statement.executeUpdate()
            }
            VenueStaffMember(
                venueId = venueId,
                userId = userId,
                role = role,
                createdAt = createdAt,
                invitedByUserId = invitedByUserId
            )
        } catch (e: Exception) {
            logger.warn(
                "Failed to create venue member venueId={} userId={}: {}",
                venueId,
                userId,
                sanitizeTelegramForLog(e.message)
            )
            logger.debugTelegramException(e) { "createMember exception venueId=$venueId userId=$userId" }
            null
        }
    }
}

data class VenueStaffMember(
    val venueId: Long,
    val userId: Long,
    val role: String,
    val createdAt: Instant,
    val invitedByUserId: Long?
)
