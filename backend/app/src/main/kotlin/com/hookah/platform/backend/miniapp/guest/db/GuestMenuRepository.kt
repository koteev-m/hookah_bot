package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

class GuestMenuRepository(private val dataSource: DataSource?) {
    suspend fun getMenu(venueId: Long): MenuModel {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val categories =
                        connection.prepareStatement(
                            """
                            SELECT id, name, sort_order
                            FROM menu_categories
                            WHERE venue_id = ?
                            ORDER BY sort_order, id
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, venueId)
                            statement.executeQuery().use { rs ->
                                val result = mutableListOf<MenuCategoryModel>()
                                while (rs.next()) {
                                    result.add(mapCategory(rs))
                                }
                                result
                            }
                        }

                    val itemsByCategory =
                        connection.prepareStatement(
                            """
                            SELECT id, category_id, name, price_minor, currency, is_available, sort_order
                            FROM menu_items
                            WHERE venue_id = ?
                              AND is_available = true
                            ORDER BY sort_order, id
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, venueId)
                            statement.executeQuery().use { rs ->
                                val result = mutableMapOf<Long, MutableList<MenuItemModel>>()
                                while (rs.next()) {
                                    val categoryId = rs.getLong("category_id")
                                    val items = result.getOrPut(categoryId) { mutableListOf() }
                                    items.add(mapItem(rs))
                                }
                                result
                            }
                        }

                    val mappedCategories =
                        categories.map { category ->
                            category.copy(items = itemsByCategory[category.id].orEmpty())
                        }

                    MenuModel(
                        venueId = venueId,
                        categories = mappedCategories,
                    )
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findAvailableItemIds(
        venueId: Long,
        itemIds: Set<Long>,
    ): Set<Long> {
        if (itemIds.isEmpty()) {
            return emptySet()
        }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val placeholders = itemIds.joinToString(",") { "?" }
                    val sql =
                        """
                        SELECT id
                        FROM menu_items
                        WHERE venue_id = ?
                          AND is_available = true
                          AND id IN ($placeholders)
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, venueId)
                        itemIds.forEachIndexed { index, itemId ->
                            statement.setLong(index + 2, itemId)
                        }
                        statement.executeQuery().use { rs ->
                            val result = mutableSetOf<Long>()
                            while (rs.next()) {
                                result.add(rs.getLong("id"))
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

    suspend fun findItemNames(
        venueId: Long,
        itemIds: Set<Long>,
    ): Map<Long, String> {
        if (itemIds.isEmpty()) {
            return emptyMap()
        }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val placeholders = itemIds.joinToString(",") { "?" }
                    val sql =
                        """
                        SELECT id, name
                        FROM menu_items
                        WHERE venue_id = ?
                          AND id IN ($placeholders)
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, venueId)
                        itemIds.forEachIndexed { index, itemId ->
                            statement.setLong(index + 2, itemId)
                        }
                        statement.executeQuery().use { rs ->
                            val result = mutableMapOf<Long, String>()
                            while (rs.next()) {
                                result[rs.getLong("id")] = rs.getString("name")
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

    private fun mapCategory(rs: ResultSet): MenuCategoryModel =
        MenuCategoryModel(
            id = rs.getLong("id"),
            name = rs.getString("name"),
            sortOrder = rs.getInt("sort_order"),
            items = emptyList(),
        )

    private fun mapItem(rs: ResultSet): MenuItemModel =
        MenuItemModel(
            id = rs.getLong("id"),
            name = rs.getString("name"),
            priceMinor = rs.getLong("price_minor"),
            currency = rs.getString("currency"),
            isAvailable = rs.getBoolean("is_available"),
            sortOrder = rs.getInt("sort_order"),
        )
}

data class MenuModel(
    val venueId: Long,
    val categories: List<MenuCategoryModel>,
)

data class MenuCategoryModel(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val items: List<MenuItemModel>,
)

data class MenuItemModel(
    val id: Long,
    val name: String,
    val priceMinor: Long,
    val currency: String,
    val isAvailable: Boolean,
    val sortOrder: Int,
)
