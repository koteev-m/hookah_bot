package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GuestMenuRepository(private val dataSource: DataSource?) {
    suspend fun getMenu(venueId: Long): MenuModel {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val categories = connection.prepareStatement(
                        """
                            SELECT id, name, sort_order
                            FROM menu_categories
                            WHERE venue_id = ?
                            ORDER BY sort_order, id
                        """.trimIndent()
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

                    val itemsByCategory = connection.prepareStatement(
                        """
                            SELECT id, category_id, name, price_minor, currency, is_available, sort_order
                            FROM menu_items
                            WHERE venue_id = ?
                            ORDER BY sort_order, id
                        """.trimIndent()
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

                    val mappedCategories = categories.map { category ->
                        category.copy(items = itemsByCategory[category.id].orEmpty())
                    }

                    MenuModel(
                        venueId = venueId,
                        categories = mappedCategories
                    )
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun mapCategory(rs: ResultSet): MenuCategoryModel = MenuCategoryModel(
        id = rs.getLong("id"),
        name = rs.getString("name"),
        sortOrder = rs.getInt("sort_order"),
        items = emptyList()
    )

    private fun mapItem(rs: ResultSet): MenuItemModel = MenuItemModel(
        id = rs.getLong("id"),
        name = rs.getString("name"),
        priceMinor = rs.getLong("price_minor"),
        currency = rs.getString("currency"),
        isAvailable = rs.getBoolean("is_available"),
        sortOrder = rs.getInt("sort_order")
    )
}

data class MenuModel(
    val venueId: Long,
    val categories: List<MenuCategoryModel>
)

data class MenuCategoryModel(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val items: List<MenuItemModel>
)

data class MenuItemModel(
    val id: Long,
    val name: String,
    val priceMinor: Long,
    val currency: String,
    val isAvailable: Boolean,
    val sortOrder: Int
)
