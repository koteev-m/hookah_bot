package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Locale
import javax.sql.DataSource

class GuestVenueRepository(private val dataSource: DataSource?) {
    suspend fun listCatalogVenues(
        query: String? = null,
        city: String? = null,
    ): List<VenueShort> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val blockedStatuses = SubscriptionStatus.blockedDbValues
        val blockedPlaceholders = blockedStatuses.joinToString(",") { "?" }
        val normalizedQuery = query?.lowercase(Locale.ROOT)
        val normalizedCity = city?.lowercase(Locale.ROOT)
        val queryPattern = normalizedQuery?.let { "%${escapeLikePattern(it)}%" }
        val sql =
            buildString {
                append(
                    """
                    SELECT v.id, v.name, v.city, v.address, v.country_code, v.formatted_address,
                           v.latitude, v.longitude, v.guest_contact, v.card_description, v.status
                    FROM venues v
                    LEFT JOIN venue_subscriptions vs ON vs.venue_id = v.id
                    WHERE v.status = ?
                      AND (vs.status IS NULL OR LOWER(vs.status) NOT IN ($blockedPlaceholders))
                    """.trimIndent(),
                )
                if (queryPattern != null) {
                    append("\n  AND (")
                    append("\n    LOWER(v.name) LIKE ? ESCAPE '!'")
                    append("\n    OR LOWER(v.city) LIKE ? ESCAPE '!'")
                    append("\n    OR LOWER(v.address) LIKE ? ESCAPE '!'")
                    append("\n    OR LOWER(v.formatted_address) LIKE ? ESCAPE '!'")
                    append("\n  )")
                }
                if (normalizedCity != null) {
                    append("\n  AND LOWER(v.city) = ?")
                }
                append("\nORDER BY v.id ASC")
            }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(sql).use { statement ->
                        var parameterIndex = 1
                        statement.setString(parameterIndex++, VenueStatus.PUBLISHED.dbValue)
                        blockedStatuses.forEachIndexed { index, status ->
                            statement.setString(parameterIndex + index, status)
                        }
                        parameterIndex += blockedStatuses.size
                        if (queryPattern != null) {
                            repeat(CATALOG_QUERY_FIELD_COUNT) {
                                statement.setString(parameterIndex++, queryPattern)
                            }
                        }
                        if (normalizedCity != null) {
                            statement.setString(parameterIndex, normalizedCity)
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

    private fun escapeLikePattern(value: String): String =
        buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    LIKE_ESCAPE -> append("!!")
                    '%' -> append("!%")
                    '_' -> append("!_")
                    else -> append(character)
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
                        SELECT id, name, city, address, country_code, formatted_address,
                               latitude, longitude, guest_contact, card_description, status
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
            countryCode = rs.getString("country_code"),
            formattedAddress = rs.getString("formatted_address"),
            latitude = rs.getDouble("latitude").takeIf { !rs.wasNull() },
            longitude = rs.getDouble("longitude").takeIf { !rs.wasNull() },
            guestContact = rs.getString("guest_contact"),
            cardDescription = rs.getString("card_description"),
            status = status,
        )
    }

    private companion object {
        const val LIKE_ESCAPE = '!'
        const val CATALOG_QUERY_FIELD_COUNT = 4
    }
}

data class VenueShort(
    val id: Long,
    val name: String,
    val city: String?,
    val address: String?,
    val countryCode: String? = null,
    val formattedAddress: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val status: VenueStatus,
    val guestContact: String? = null,
    val cardDescription: String? = null,
)
