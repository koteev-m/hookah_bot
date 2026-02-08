package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

class GuestVenueRepository(private val dataSource: DataSource?) {
    suspend fun listCatalogVenues(): List<VenueShort> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val blockedStatuses = SubscriptionStatus.blockedDbValues
        val blockedPlaceholders = blockedStatuses.joinToString(",") { "?" }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT v.id, v.name, v.city, v.address, v.status
                        FROM venues v
                        LEFT JOIN venue_subscriptions vs ON vs.venue_id = v.id
                        WHERE v.status = ?
                          AND (vs.status IS NULL OR LOWER(vs.status) NOT IN ($blockedPlaceholders))
                        ORDER BY v.id ASC
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, VenueStatus.PUBLISHED.dbValue)
                        blockedStatuses.forEachIndexed { index, status ->
                            statement.setString(index + 2, status)
                        }
                        statement.executeQuery().use { rs ->
                            val venues = mutableListOf<VenueShort>()
                            while (rs.next()) {
                                mapVenueShort(rs)?.let { venues.add(it) }
                            }
                            venues
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findVenueByIdForGuest(id: Long): VenueShort? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT id, name, city, address, status
                        FROM venues
                        WHERE id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, id)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                mapVenueShort(rs)
                            } else {
                                null
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun mapVenueShort(rs: ResultSet): VenueShort? {
        val status = VenueStatus.fromDb(rs.getString("status")) ?: return null
        return VenueShort(
            id = rs.getLong("id"),
            name = rs.getString("name"),
            city = rs.getString("city"),
            address = rs.getString("address"),
            status = status,
        )
    }
}

data class VenueShort(
    val id: Long,
    val name: String,
    val city: String?,
    val address: String?,
    val status: VenueStatus,
)
