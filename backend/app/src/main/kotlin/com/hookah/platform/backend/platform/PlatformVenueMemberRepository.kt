package com.hookah.platform.backend.platform

import com.hookah.platform.backend.telegram.debugTelegramException
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class PlatformVenueMemberRepository(private val dataSource: DataSource?) {
    private val logger = LoggerFactory.getLogger(PlatformVenueMemberRepository::class.java)

    suspend fun assignOwner(
        venueId: Long,
        userId: Long,
        role: String,
        invitedByUserId: Long?
    ): PlatformOwnerAssignmentResult {
        val ds = dataSource ?: return PlatformOwnerAssignmentResult.DatabaseError
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val initialAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    if (!venueExists(connection, venueId)) {
                        return@use rollbackAndReturn(connection) { PlatformOwnerAssignmentResult.NotFound }
                    }
                    val existing = loadMember(connection, venueId, userId)
                    if (existing != null) {
                        val updatedRole = ensureRole(connection, venueId, userId, role, existing.role)
                        connection.commit()
                        return@use PlatformOwnerAssignmentResult.Success(
                            member = existing.copy(role = updatedRole),
                            alreadyMember = true
                        )
                    }
                    val createdAt = Instant.now()
                    val inserted = insertMember(connection, venueId, userId, role, invitedByUserId, createdAt)
                    if (inserted == null) {
                        val after = loadMember(connection, venueId, userId)
                        if (after != null) {
                            val updatedRole = ensureRole(connection, venueId, userId, role, after.role)
                            connection.commit()
                            return@use PlatformOwnerAssignmentResult.Success(
                                member = after.copy(role = updatedRole),
                                alreadyMember = true
                            )
                        }
                        return@use rollbackAndReturn(connection) { PlatformOwnerAssignmentResult.DatabaseError }
                    }
                    connection.commit()
                    PlatformOwnerAssignmentResult.Success(member = inserted, alreadyMember = false)
                } catch (e: Exception) {
                    rollbackBestEffort(connection)
                    logger.warn(
                        "Failed to assign venue owner venueId={} userId={}: {}",
                        venueId,
                        userId,
                        sanitizeTelegramForLog(e.message)
                    )
                    logger.debugTelegramException(e) { "assignOwner exception venueId=$venueId userId=$userId" }
                    PlatformOwnerAssignmentResult.DatabaseError
                } finally {
                    connection.autoCommit = initialAutoCommit
                }
            }
        }
    }

    private fun venueExists(connection: Connection, venueId: Long): Boolean {
        return connection.prepareStatement("SELECT 1 FROM venues WHERE id = ?").use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun loadMember(connection: Connection, venueId: Long, userId: Long): PlatformVenueMember? {
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
                    PlatformVenueMember(
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

    private fun ensureRole(
        connection: Connection,
        venueId: Long,
        userId: Long,
        targetRole: String,
        currentRole: String
    ): String {
        if (currentRole.equals(targetRole, ignoreCase = true)) return currentRole
        updateRole(connection, venueId, userId, targetRole)
        return targetRole
    }

    private fun updateRole(connection: Connection, venueId: Long, userId: Long, role: String) {
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
            statement.executeUpdate()
        }
    }

    private fun insertMember(
        connection: Connection,
        venueId: Long,
        userId: Long,
        role: String,
        invitedByUserId: Long?,
        createdAt: Instant
    ): PlatformVenueMember? {
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
                if (invitedByUserId != null) {
                    statement.setLong(5, invitedByUserId)
                } else {
                    statement.setNull(5, java.sql.Types.BIGINT)
                }
                statement.executeUpdate()
            }
            PlatformVenueMember(
                venueId = venueId,
                userId = userId,
                role = role,
                createdAt = createdAt,
                invitedByUserId = invitedByUserId
            )
        } catch (e: SQLException) {
            logger.debugTelegramException(e) { "insertMember exception venueId=$venueId userId=$userId" }
            null
        }
    }

    private fun rollbackBestEffort(connection: Connection) {
        runCatching { connection.rollback() }
    }

    private fun <T> rollbackAndReturn(connection: Connection, block: () -> T): T {
        runCatching { connection.rollback() }
        return block()
    }
}

data class PlatformVenueMember(
    val venueId: Long,
    val userId: Long,
    val role: String,
    val createdAt: Instant,
    val invitedByUserId: Long?
)

sealed interface PlatformOwnerAssignmentResult {
    data class Success(val member: PlatformVenueMember, val alreadyMember: Boolean) : PlatformOwnerAssignmentResult
    data object NotFound : PlatformOwnerAssignmentResult
    data object DatabaseError : PlatformOwnerAssignmentResult
}
