package com.hookah.platform.backend.miniapp.venue.menu

import com.hookah.platform.backend.api.DatabaseUnavailableException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VenueMenuCategory(
    val id: Long,
    val venueId: Long,
    val name: String,
    val sortOrder: Int,
    val items: List<VenueMenuItem>
)

data class VenueMenuItem(
    val id: Long,
    val venueId: Long,
    val categoryId: Long,
    val name: String,
    val priceMinor: Long,
    val currency: String,
    val isAvailable: Boolean,
    val sortOrder: Int,
    val options: List<VenueMenuOption>
)

data class VenueMenuOption(
    val id: Long,
    val venueId: Long,
    val itemId: Long,
    val name: String,
    val priceDeltaMinor: Long,
    val isAvailable: Boolean,
    val sortOrder: Int
)

class VenueMenuRepository(private val dataSource: DataSource?) {
    suspend fun getMenu(venueId: Long): List<VenueMenuCategory> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val categories = connection.prepareStatement(
                        """
                            SELECT id, venue_id, name, sort_order
                            FROM menu_categories
                            WHERE venue_id = ?
                            ORDER BY sort_order, id
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<VenueMenuCategory>()
                            while (rs.next()) {
                                result.add(mapCategory(rs))
                            }
                            result
                        }
                    }

                    val itemsByCategory = connection.prepareStatement(
                        """
                            SELECT id, venue_id, category_id, name, price_minor, currency, is_available, sort_order
                            FROM menu_items
                            WHERE venue_id = ?
                            ORDER BY sort_order, id
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeQuery().use { rs ->
                            val result = mutableMapOf<Long, MutableList<VenueMenuItem>>()
                            while (rs.next()) {
                                val categoryId = rs.getLong("category_id")
                                val items = result.getOrPut(categoryId) { mutableListOf() }
                                items.add(mapItem(rs))
                            }
                            result
                        }
                    }

                    val optionsByItem = connection.prepareStatement(
                        """
                            SELECT id, venue_id, item_id, name, price_delta_minor, is_available, sort_order
                            FROM menu_item_options
                            WHERE venue_id = ?
                            ORDER BY sort_order, id
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeQuery().use { rs ->
                            val result = mutableMapOf<Long, MutableList<VenueMenuOption>>()
                            while (rs.next()) {
                                val itemId = rs.getLong("item_id")
                                val options = result.getOrPut(itemId) { mutableListOf() }
                                options.add(mapOption(rs))
                            }
                            result
                        }
                    }

                    categories.map { category ->
                        val items = itemsByCategory[category.id].orEmpty().map { item ->
                            item.copy(options = optionsByItem[item.id].orEmpty())
                        }
                        category.copy(items = items)
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun createCategory(venueId: Long, name: String): VenueMenuCategory {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sortOrder = nextCategorySortOrder(connection, venueId)
                    val categoryId = connection.prepareStatement(
                        """
                            INSERT INTO menu_categories (venue_id, name, sort_order, updated_at)
                            VALUES (?, ?, ?, now())
                        """.trimIndent(),
                        java.sql.Statement.RETURN_GENERATED_KEYS
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setString(2, name)
                        statement.setInt(3, sortOrder)
                        statement.executeUpdate()
                        statement.generatedKeys.use { rs ->
                            if (rs.next()) rs.getLong(1) else error("Failed to insert category")
                        }
                    }
                    VenueMenuCategory(
                        id = categoryId,
                        venueId = venueId,
                        name = name,
                        sortOrder = sortOrder,
                        items = emptyList()
                    )
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateCategory(venueId: Long, categoryId: Long, name: String): VenueMenuCategory? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated = connection.prepareStatement(
                        """
                            UPDATE menu_categories
                            SET name = ?, updated_at = now()
                            WHERE id = ? AND venue_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, name)
                        statement.setLong(2, categoryId)
                        statement.setLong(3, venueId)
                        statement.executeUpdate()
                    }
                    if (updated == 0) {
                        return@use null
                    }
                    loadCategory(connection, categoryId, venueId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun deleteCategory(venueId: Long, categoryId: Long): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val hasItems = connection.prepareStatement(
                        """
                            SELECT 1
                            FROM menu_items
                            WHERE venue_id = ? AND category_id = ?
                            LIMIT 1
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, categoryId)
                        statement.executeQuery().use { rs -> rs.next() }
                    }
                    if (hasItems) {
                        return@use false
                    }
                    val deleted = connection.prepareStatement(
                        """
                            DELETE FROM menu_categories
                            WHERE id = ? AND venue_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, categoryId)
                        statement.setLong(2, venueId)
                        statement.executeUpdate()
                    }
                    deleted > 0
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun createItem(
        venueId: Long,
        categoryId: Long,
        name: String,
        priceMinor: Long,
        currency: String,
        isAvailable: Boolean
    ): VenueMenuItem? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    if (!categoryExists(connection, venueId, categoryId)) {
                        return@use null
                    }
                    val sortOrder = nextItemSortOrder(connection, venueId, categoryId)
                    val itemId = connection.prepareStatement(
                        """
                            INSERT INTO menu_items (
                                venue_id, category_id, name, price_minor, currency, is_available, sort_order, updated_at
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, now())
                        """.trimIndent(),
                        java.sql.Statement.RETURN_GENERATED_KEYS
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, categoryId)
                        statement.setString(3, name)
                        statement.setLong(4, priceMinor)
                        statement.setString(5, currency)
                        statement.setBoolean(6, isAvailable)
                        statement.setInt(7, sortOrder)
                        statement.executeUpdate()
                        statement.generatedKeys.use { rs ->
                            if (rs.next()) rs.getLong(1) else error("Failed to insert item")
                        }
                    }
                    VenueMenuItem(
                        id = itemId,
                        venueId = venueId,
                        categoryId = categoryId,
                        name = name,
                        priceMinor = priceMinor,
                        currency = currency,
                        isAvailable = isAvailable,
                        sortOrder = sortOrder,
                        options = emptyList()
                    )
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateItem(
        venueId: Long,
        itemId: Long,
        categoryId: Long?,
        name: String?,
        priceMinor: Long?,
        currency: String?,
        isAvailable: Boolean?
    ): VenueMenuItem? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val existing = loadItem(connection, itemId, venueId) ?: return@use null
                    val newCategoryId = categoryId ?: existing.categoryId
                    if (newCategoryId != existing.categoryId && !categoryExists(connection, venueId, newCategoryId)) {
                        return@use null
                    }
                    connection.prepareStatement(
                        """
                            UPDATE menu_items
                            SET category_id = ?, name = ?, price_minor = ?, currency = ?, is_available = ?, updated_at = now()
                            WHERE id = ? AND venue_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, newCategoryId)
                        statement.setString(2, name ?: existing.name)
                        statement.setLong(3, priceMinor ?: existing.priceMinor)
                        statement.setString(4, currency ?: existing.currency)
                        statement.setBoolean(5, isAvailable ?: existing.isAvailable)
                        statement.setLong(6, itemId)
                        statement.setLong(7, venueId)
                        statement.executeUpdate()
                    }
                    loadItem(connection, itemId, venueId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun deleteItem(venueId: Long, itemId: Long): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val deleted = connection.prepareStatement(
                        """
                            DELETE FROM menu_items
                            WHERE id = ? AND venue_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, itemId)
                        statement.setLong(2, venueId)
                        statement.executeUpdate()
                    }
                    deleted > 0
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun setItemAvailability(venueId: Long, itemId: Long, isAvailable: Boolean): VenueMenuItem? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated = connection.prepareStatement(
                        """
                            UPDATE menu_items
                            SET is_available = ?, updated_at = now()
                            WHERE id = ? AND venue_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setBoolean(1, isAvailable)
                        statement.setLong(2, itemId)
                        statement.setLong(3, venueId)
                        statement.executeUpdate()
                    }
                    if (updated == 0) {
                        return@use null
                    }
                    loadItem(connection, itemId, venueId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun reorderCategories(venueId: Long, categoryIds: List<Long>): Boolean {
        if (categoryIds.isEmpty()) {
            return false
        }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val valid = countCategories(connection, venueId, categoryIds) == categoryIds.size
                        if (!valid) {
                            connection.rollback()
                            return@use false
                        }
                        updateCategoryOrder(connection, venueId, categoryIds)
                        connection.commit()
                        true
                    } catch (e: Exception) {
                        connection.rollback()
                        throw e
                    } finally {
                        connection.autoCommit = true
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun reorderItems(venueId: Long, categoryId: Long, itemIds: List<Long>): Boolean {
        if (itemIds.isEmpty()) {
            return false
        }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        if (!categoryExists(connection, venueId, categoryId)) {
                            connection.rollback()
                            return@use false
                        }
                        val valid = countItems(connection, venueId, categoryId, itemIds) == itemIds.size
                        if (!valid) {
                            connection.rollback()
                            return@use false
                        }
                        updateItemOrder(connection, venueId, itemIds)
                        connection.commit()
                        true
                    } catch (e: Exception) {
                        connection.rollback()
                        throw e
                    } finally {
                        connection.autoCommit = true
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun categoryExists(venueId: Long, categoryId: Long): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    categoryExists(connection, venueId, categoryId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun categoryHasItems(venueId: Long, categoryId: Long): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                            SELECT 1
                            FROM menu_items
                            WHERE venue_id = ? AND category_id = ?
                            LIMIT 1
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, categoryId)
                        statement.executeQuery().use { rs -> rs.next() }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun itemExists(venueId: Long, itemId: Long): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    itemExists(connection, venueId, itemId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun optionExists(venueId: Long, optionId: Long): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                            SELECT 1
                            FROM menu_item_options
                            WHERE id = ? AND venue_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, optionId)
                        statement.setLong(2, venueId)
                        statement.executeQuery().use { rs -> rs.next() }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun createOption(
        venueId: Long,
        itemId: Long,
        name: String,
        priceDeltaMinor: Long,
        isAvailable: Boolean
    ): VenueMenuOption? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    if (!itemExists(connection, venueId, itemId)) {
                        return@use null
                    }
                    val sortOrder = nextOptionSortOrder(connection, venueId, itemId)
                    val optionId = connection.prepareStatement(
                        """
                            INSERT INTO menu_item_options (
                                venue_id, item_id, name, price_delta_minor, is_available, sort_order, updated_at
                            )
                            VALUES (?, ?, ?, ?, ?, ?, now())
                        """.trimIndent(),
                        java.sql.Statement.RETURN_GENERATED_KEYS
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, itemId)
                        statement.setString(3, name)
                        statement.setLong(4, priceDeltaMinor)
                        statement.setBoolean(5, isAvailable)
                        statement.setInt(6, sortOrder)
                        statement.executeUpdate()
                        statement.generatedKeys.use { rs ->
                            if (rs.next()) rs.getLong(1) else error("Failed to insert option")
                        }
                    }
                    VenueMenuOption(
                        id = optionId,
                        venueId = venueId,
                        itemId = itemId,
                        name = name,
                        priceDeltaMinor = priceDeltaMinor,
                        isAvailable = isAvailable,
                        sortOrder = sortOrder
                    )
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateOption(
        venueId: Long,
        optionId: Long,
        name: String?,
        priceDeltaMinor: Long?,
        isAvailable: Boolean?
    ): VenueMenuOption? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val existing = loadOption(connection, optionId, venueId) ?: return@use null
                    connection.prepareStatement(
                        """
                            UPDATE menu_item_options
                            SET name = ?, price_delta_minor = ?, is_available = ?, updated_at = now()
                            WHERE id = ? AND venue_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, name ?: existing.name)
                        statement.setLong(2, priceDeltaMinor ?: existing.priceDeltaMinor)
                        statement.setBoolean(3, isAvailable ?: existing.isAvailable)
                        statement.setLong(4, optionId)
                        statement.setLong(5, venueId)
                        statement.executeUpdate()
                    }
                    loadOption(connection, optionId, venueId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun setOptionAvailability(venueId: Long, optionId: Long, isAvailable: Boolean): VenueMenuOption? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated = connection.prepareStatement(
                        """
                            UPDATE menu_item_options
                            SET is_available = ?, updated_at = now()
                            WHERE id = ? AND venue_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setBoolean(1, isAvailable)
                        statement.setLong(2, optionId)
                        statement.setLong(3, venueId)
                        statement.executeUpdate()
                    }
                    if (updated == 0) {
                        return@use null
                    }
                    loadOption(connection, optionId, venueId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun deleteOption(venueId: Long, optionId: Long): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val deleted = connection.prepareStatement(
                        """
                            DELETE FROM menu_item_options
                            WHERE id = ? AND venue_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, optionId)
                        statement.setLong(2, venueId)
                        statement.executeUpdate()
                    }
                    deleted > 0
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun mapCategory(rs: ResultSet): VenueMenuCategory = VenueMenuCategory(
        id = rs.getLong("id"),
        venueId = rs.getLong("venue_id"),
        name = rs.getString("name"),
        sortOrder = rs.getInt("sort_order"),
        items = emptyList()
    )

    private fun mapItem(rs: ResultSet): VenueMenuItem = VenueMenuItem(
        id = rs.getLong("id"),
        venueId = rs.getLong("venue_id"),
        categoryId = rs.getLong("category_id"),
        name = rs.getString("name"),
        priceMinor = rs.getLong("price_minor"),
        currency = rs.getString("currency"),
        isAvailable = rs.getBoolean("is_available"),
        sortOrder = rs.getInt("sort_order"),
        options = emptyList()
    )

    private fun mapOption(rs: ResultSet): VenueMenuOption = VenueMenuOption(
        id = rs.getLong("id"),
        venueId = rs.getLong("venue_id"),
        itemId = rs.getLong("item_id"),
        name = rs.getString("name"),
        priceDeltaMinor = rs.getLong("price_delta_minor"),
        isAvailable = rs.getBoolean("is_available"),
        sortOrder = rs.getInt("sort_order")
    )

    private fun loadCategory(connection: Connection, categoryId: Long, venueId: Long): VenueMenuCategory? {
        return connection.prepareStatement(
            """
                SELECT id, venue_id, name, sort_order
                FROM menu_categories
                WHERE id = ? AND venue_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, categoryId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) mapCategory(rs) else null
            }
        }
    }

    private fun loadItem(connection: Connection, itemId: Long, venueId: Long): VenueMenuItem? {
        return connection.prepareStatement(
            """
                SELECT id, venue_id, category_id, name, price_minor, currency, is_available, sort_order
                FROM menu_items
                WHERE id = ? AND venue_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, itemId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) mapItem(rs) else null
            }
        }
    }

    private fun loadOption(connection: Connection, optionId: Long, venueId: Long): VenueMenuOption? {
        return connection.prepareStatement(
            """
                SELECT id, venue_id, item_id, name, price_delta_minor, is_available, sort_order
                FROM menu_item_options
                WHERE id = ? AND venue_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, optionId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) mapOption(rs) else null
            }
        }
    }

    private fun categoryExists(connection: Connection, venueId: Long, categoryId: Long): Boolean {
        return connection.prepareStatement(
            """
                SELECT 1
                FROM menu_categories
                WHERE id = ? AND venue_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, categoryId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun itemExists(connection: Connection, venueId: Long, itemId: Long): Boolean {
        return connection.prepareStatement(
            """
                SELECT 1
                FROM menu_items
                WHERE id = ? AND venue_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, itemId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun nextCategorySortOrder(connection: Connection, venueId: Long): Int {
        return connection.prepareStatement(
            """
                SELECT COALESCE(MAX(sort_order), -1) + 1 AS next_order
                FROM menu_categories
                WHERE venue_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getInt("next_order") else 0
            }
        }
    }

    private fun nextItemSortOrder(connection: Connection, venueId: Long, categoryId: Long): Int {
        return connection.prepareStatement(
            """
                SELECT COALESCE(MAX(sort_order), -1) + 1 AS next_order
                FROM menu_items
                WHERE venue_id = ? AND category_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, categoryId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getInt("next_order") else 0
            }
        }
    }

    private fun nextOptionSortOrder(connection: Connection, venueId: Long, itemId: Long): Int {
        return connection.prepareStatement(
            """
                SELECT COALESCE(MAX(sort_order), -1) + 1 AS next_order
                FROM menu_item_options
                WHERE venue_id = ? AND item_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, itemId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getInt("next_order") else 0
            }
        }
    }

    private fun countCategories(connection: Connection, venueId: Long, categoryIds: List<Long>): Int {
        val placeholders = categoryIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
                SELECT COUNT(*) AS total
                FROM menu_categories
                WHERE venue_id = ? AND id IN ($placeholders)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, venueId)
            categoryIds.forEachIndexed { index, id ->
                statement.setLong(index + 2, id)
            }
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getInt("total") else 0
            }
        }
    }

    private fun countItems(connection: Connection, venueId: Long, categoryId: Long, itemIds: List<Long>): Int {
        val placeholders = itemIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
                SELECT COUNT(*) AS total
                FROM menu_items
                WHERE venue_id = ? AND category_id = ? AND id IN ($placeholders)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, categoryId)
            itemIds.forEachIndexed { index, id ->
                statement.setLong(index + 3, id)
            }
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getInt("total") else 0
            }
        }
    }

    private fun updateCategoryOrder(connection: Connection, venueId: Long, categoryIds: List<Long>) {
        connection.prepareStatement(
            """
                UPDATE menu_categories
                SET sort_order = ?, updated_at = now()
                WHERE venue_id = ? AND id = ?
            """.trimIndent()
        ).use { statement ->
            categoryIds.forEachIndexed { index, id ->
                statement.setInt(1, index)
                statement.setLong(2, venueId)
                statement.setLong(3, id)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun updateItemOrder(connection: Connection, venueId: Long, itemIds: List<Long>) {
        connection.prepareStatement(
            """
                UPDATE menu_items
                SET sort_order = ?, updated_at = now()
                WHERE venue_id = ? AND id = ?
            """.trimIndent()
        ).use { statement ->
            itemIds.forEachIndexed { index, id ->
                statement.setInt(1, index)
                statement.setLong(2, venueId)
                statement.setLong(3, id)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}
