package com.hookah.platform.backend.telegram.db

import java.sql.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

class VenueAccessRepository(private val dataSource: DataSource?) {
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

    suspend fun hasVenueAdminOrOwner(userId: Long, venueId: Long): Boolean {
        val ds = dataSource ?: return false
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection -> hasVenueAdminOrOwner(connection, userId, venueId) }
        }
    }

    fun hasVenueAdminOrOwner(connection: Connection, userId: Long, venueId: Long): Boolean {
        return connection.prepareStatement(
            """
                SELECT 1 FROM venue_members
                WHERE user_id = ? AND venue_id = ? AND role IN ('OWNER', 'ADMIN')
                LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs -> rs.next() }
        }
    }
}
