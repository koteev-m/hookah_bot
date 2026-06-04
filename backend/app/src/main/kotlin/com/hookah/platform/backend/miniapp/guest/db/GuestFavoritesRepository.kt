package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

class GuestFavoritesRepository(private val dataSource: DataSource?) {
    suspend fun addVenueFavorite(
        userId: Long,
        venueId: Long,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    if (!isVenueVisibleForGuest(connection, venueId)) {
                        return@withContext false
                    }
                    upsertVenueFavorite(connection, userId, venueId)
                    true
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun removeVenueFavorite(
        userId: Long,
        venueId: Long,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        DELETE FROM guest_favorite_venues
                        WHERE user_id = ? AND venue_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.setLong(2, venueId)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun isVenueFavorite(
        userId: Long,
        venueId: Long,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT 1
                        FROM guest_favorite_venues
                        WHERE user_id = ? AND venue_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.setLong(2, venueId)
                        statement.executeQuery().use { rs -> rs.next() }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listFavoriteVenues(
        userId: Long,
        limit: Int = 20,
    ): List<FavoriteVenue> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val blockedStatuses = SubscriptionStatus.blockedDbValues
        val blockedPlaceholders = blockedStatuses.joinToString(",") { "?" }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT v.id, v.name, v.city, v.address, fv.created_at
                        FROM guest_favorite_venues fv
                        JOIN venues v ON v.id = fv.venue_id
                        LEFT JOIN venue_subscriptions vs ON vs.venue_id = v.id
                        WHERE fv.user_id = ?
                          AND v.status = ?
                          AND (vs.status IS NULL OR LOWER(vs.status) NOT IN ($blockedPlaceholders))
                        ORDER BY fv.created_at DESC, v.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.setString(2, VenueStatus.PUBLISHED.dbValue)
                        blockedStatuses.forEachIndexed { index, status ->
                            statement.setString(index + 3, status)
                        }
                        statement.setInt(blockedStatuses.size + 3, limit.coerceIn(1, 50))
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<FavoriteVenue>()
                            while (rs.next()) {
                                result.add(rs.toFavoriteVenue())
                            }
                            result
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun addItemFavorite(
        userId: Long,
        venueId: Long,
        menuItemId: Long,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    if (!isVenueVisibleForGuest(connection, venueId) ||
                        !isItemAvailableInVenue(connection, venueId, menuItemId)
                    ) {
                        return@withContext false
                    }
                    upsertItemFavorite(connection, userId, venueId, menuItemId)
                    true
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun removeItemFavorite(
        userId: Long,
        menuItemId: Long,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        DELETE FROM guest_favorite_items
                        WHERE user_id = ? AND menu_item_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.setLong(2, menuItemId)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun isItemFavorite(
        userId: Long,
        menuItemId: Long,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT 1
                        FROM guest_favorite_items
                        WHERE user_id = ? AND menu_item_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.setLong(2, menuItemId)
                        statement.executeQuery().use { rs -> rs.next() }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listFavoriteItemsForVenue(
        userId: Long,
        venueId: Long,
        limit: Int = 20,
    ): List<FavoriteMenuItem> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT mi.id, mi.venue_id, mi.category_id, mi.name, mi.price_minor, mi.currency, fi.created_at
                        FROM guest_favorite_items fi
                        JOIN menu_items mi ON mi.id = fi.menu_item_id
                        WHERE fi.user_id = ?
                          AND fi.venue_id = ?
                          AND mi.venue_id = fi.venue_id
                          AND mi.is_available = true
                        ORDER BY fi.created_at DESC, mi.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.setLong(2, venueId)
                        statement.setInt(3, limit.coerceIn(1, 50))
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<FavoriteMenuItem>()
                            while (rs.next()) {
                                result.add(rs.toFavoriteMenuItem())
                            }
                            result
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun isVenueVisibleForGuest(
        connection: Connection,
        venueId: Long,
    ): Boolean {
        val blockedStatuses = SubscriptionStatus.blockedDbValues
        val blockedPlaceholders = blockedStatuses.joinToString(",") { "?" }
        connection.prepareStatement(
            """
            SELECT 1
            FROM venues v
            LEFT JOIN venue_subscriptions vs ON vs.venue_id = v.id
            WHERE v.id = ?
              AND v.status = ?
              AND (vs.status IS NULL OR LOWER(vs.status) NOT IN ($blockedPlaceholders))
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, VenueStatus.PUBLISHED.dbValue)
            blockedStatuses.forEachIndexed { index, status ->
                statement.setString(index + 3, status)
            }
            statement.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun isItemAvailableInVenue(
        connection: Connection,
        venueId: Long,
        menuItemId: Long,
    ): Boolean {
        connection.prepareStatement(
            """
            SELECT 1
            FROM menu_items
            WHERE id = ?
              AND venue_id = ?
              AND is_available = true
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, menuItemId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun upsertVenueFavorite(
        connection: Connection,
        userId: Long,
        venueId: Long,
    ) {
        if (isH2(connection)) {
            connection.prepareStatement(
                """
                MERGE INTO guest_favorite_venues (user_id, venue_id)
                KEY (user_id, venue_id)
                VALUES (?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, venueId)
                statement.executeUpdate()
            }
        } else {
            connection.prepareStatement(
                """
                INSERT INTO guest_favorite_venues (user_id, venue_id)
                VALUES (?, ?)
                ON CONFLICT (user_id, venue_id) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, venueId)
                statement.executeUpdate()
            }
        }
    }

    private fun upsertItemFavorite(
        connection: Connection,
        userId: Long,
        venueId: Long,
        menuItemId: Long,
    ) {
        if (isH2(connection)) {
            connection.prepareStatement(
                """
                MERGE INTO guest_favorite_items (user_id, venue_id, menu_item_id)
                KEY (user_id, menu_item_id)
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, venueId)
                statement.setLong(3, menuItemId)
                statement.executeUpdate()
            }
        } else {
            connection.prepareStatement(
                """
                INSERT INTO guest_favorite_items (user_id, venue_id, menu_item_id)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id, menu_item_id) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, venueId)
                statement.setLong(3, menuItemId)
                statement.executeUpdate()
            }
        }
    }

    private fun isH2(connection: Connection): Boolean =
        connection.metaData.databaseProductName.equals("H2", ignoreCase = true)
}

data class FavoriteVenue(
    val venueId: Long,
    val name: String,
    val city: String?,
    val address: String?,
)

data class FavoriteMenuItem(
    val itemId: Long,
    val venueId: Long,
    val categoryId: Long,
    val name: String,
    val priceMinor: Long,
    val currency: String,
)

private fun ResultSet.toFavoriteVenue(): FavoriteVenue =
    FavoriteVenue(
        venueId = getLong("id"),
        name = getString("name"),
        city = getString("city"),
        address = getString("address"),
    )

private fun ResultSet.toFavoriteMenuItem(): FavoriteMenuItem =
    FavoriteMenuItem(
        itemId = getLong("id"),
        venueId = getLong("venue_id"),
        categoryId = getLong("category_id"),
        name = getString("name"),
        priceMinor = getLong("price_minor"),
        currency = getString("currency"),
    )
