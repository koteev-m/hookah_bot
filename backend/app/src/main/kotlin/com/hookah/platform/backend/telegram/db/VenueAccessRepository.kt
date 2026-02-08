package com.hookah.platform.backend.telegram.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource

class VenueAccessRepository(private val dataSource: DataSource?) {
    data class VenueMembership(
        val venueId: Long,
        val role: String,
    )

    suspend fun hasVenueRole(userId: Long): Boolean {
        val ds = dataSource ?: return false
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement("SELECT 1 FROM venue_members WHERE user_id = ? LIMIT 1").use { statement ->
                    statement.setLong(1, userId)
                    statement.executeQuery().use { rs -> rs.next() }
                }
            }
        }
    }

    suspend fun hasVenueAdminOrOwner(
        userId: Long,
        venueId: Long,
    ): Boolean {
        val ds = dataSource ?: return false
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection -> hasVenueAdminOrOwner(connection, userId, venueId) }
        }
    }

    fun hasVenueAdminOrOwner(
        connection: Connection,
        userId: Long,
        venueId: Long,
    ): Boolean {
        return connection.prepareStatement(
            """
            SELECT 1 FROM venue_members
            WHERE user_id = ? AND venue_id = ? AND role IN ('OWNER', 'ADMIN')
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs -> rs.next() }
        }
    }

    suspend fun listVenueMemberships(userId: Long): List<VenueMembership> {
        val ds = dataSource ?: return emptyList()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT venue_id, role
                    FROM venue_members
                    WHERE user_id = ?
                    ORDER BY venue_id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.executeQuery().use { rs ->
                        val result = mutableListOf<VenueMembership>()
                        while (rs.next()) {
                            result.add(
                                VenueMembership(
                                    venueId = rs.getLong("venue_id"),
                                    role = rs.getString("role"),
                                ),
                            )
                        }
                        result
                    }
                }
            }
        }
    }

    suspend fun findVenueMembership(
        userId: Long,
        venueId: Long,
    ): VenueMembership? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT venue_id, role
                    FROM venue_members
                    WHERE user_id = ? AND venue_id = ?
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setLong(2, venueId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) {
                            return@withContext null
                        }
                        VenueMembership(
                            venueId = rs.getLong("venue_id"),
                            role = rs.getString("role"),
                        )
                    }
                }
            }
        }
    }
}
