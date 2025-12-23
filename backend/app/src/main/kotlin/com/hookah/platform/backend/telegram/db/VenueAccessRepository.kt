package com.hookah.platform.backend.telegram.db

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
}
