package com.hookah.platform.backend.miniapp.venue.menu

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.MenuItemDeleteBlockedByFixedRewardException
import com.hookah.platform.backend.api.MenuShiftCheckStaleException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import javax.sql.DataSource

internal const val MENU_SHIFT_CHECK_MAX_CHANGES = 500
const val MENU_SHIFT_CHECK_COMPLETED_AUDIT_ACTION = "MENU_SHIFT_CHECK_COMPLETED"
const val MENU_ITEM_DELETED_AUDIT_ACTION = "MENU_ITEM_DELETED"
const val MENU_CATEGORY_DELETED_AUDIT_ACTION = "MENU_CATEGORY_DELETED"
internal const val MENU_DELETE_AFFECTED_RULE_SAMPLE_LIMIT = 50
internal const val MENU_DELETE_AUDIT_PAYLOAD_MAX_BYTES = 4096
internal const val MENU_ITEM_DELETE_AFFECTED_RULE_SAMPLE_LIMIT = MENU_DELETE_AFFECTED_RULE_SAMPLE_LIMIT
internal const val MENU_ITEM_DELETE_AUDIT_PAYLOAD_MAX_BYTES = MENU_DELETE_AUDIT_PAYLOAD_MAX_BYTES

enum class MenuItemDeleteSource {
    VENUE_MINI_APP,
    TELEGRAM_BOT,
}

enum class MenuCategoryDeleteSource {
    VENUE_MINI_APP,
    TELEGRAM_BOT,
}

data class VenueMenuCategory(
    val id: Long,
    val venueId: Long,
    val name: String,
    val sortOrder: Int,
    val items: List<VenueMenuItem>,
    val categoryType: MenuSemanticType = MenuSemanticType.OTHER,
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
    val options: List<VenueMenuOption>,
    val itemType: MenuSemanticType? = null,
)

fun VenueMenuItem.effectiveType(category: VenueMenuCategory): MenuSemanticType = itemType ?: category.categoryType

data class VenueMenuOption(
    val id: Long,
    val venueId: Long,
    val itemId: Long,
    val name: String,
    val priceDeltaMinor: Long,
    val isAvailable: Boolean,
    val sortOrder: Int,
)

data class VenueMenuShiftCheckResult(
    val categories: List<VenueMenuCategory>,
    val changedItemCount: Int,
    val changedOptionCount: Int,
    val reviewedItemCount: Int,
    val reviewedOptionCount: Int,
    val availableItemCount: Int,
    val availableOptionCount: Int,
)

class VenueMenuRepository(
    private val dataSource: DataSource?,
    private val auditLogWriter: TransactionalAuditLogWriter = AuditLogRepository(dataSource),
) {
    suspend fun getMenu(venueId: Long): List<VenueMenuCategory> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection -> loadMenu(connection, venueId) }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun completeShiftCheck(
        venueId: Long,
        actorUserId: Long,
        itemChanges: List<MenuShiftCheckItemChange>,
        optionChanges: List<MenuShiftCheckOptionChange>,
        auditLogRepository: AuditLogRepository,
    ): VenueMenuShiftCheckResult {
        validateShiftCheckChanges(itemChanges, optionChanges)
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val originalAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val requestedItemIds =
                            (
                                itemChanges.map { it.itemId } +
                                    optionChanges.map { it.itemId }
                            ).distinct().sorted()
                        val lockedItems =
                            loadShiftCheckItemsForUpdate(
                                connection = connection,
                                venueId = venueId,
                                itemIds = requestedItemIds,
                            )
                        if (lockedItems.size != requestedItemIds.size) {
                            throw NotFoundException(
                                "Одна из позиций больше не существует.",
                            )
                        }

                        val requestedOptionIds = optionChanges.map { it.optionId }.sorted()
                        val lockedOptions =
                            loadShiftCheckOptionsForUpdate(
                                connection = connection,
                                venueId = venueId,
                                optionIds = requestedOptionIds,
                            )
                        if (lockedOptions.size != requestedOptionIds.size) {
                            throw NotFoundException("Одна из опций больше не существует.")
                        }
                        optionChanges.forEach { change ->
                            val option = checkNotNull(lockedOptions[change.optionId])
                            if (option.itemId != change.itemId) {
                                throw InvalidInputException(
                                    "Одна из опций не принадлежит заявленной позиции.",
                                )
                            }
                        }

                        val staleItem =
                            itemChanges.any { change ->
                                lockedItems[change.itemId]?.isAvailable != change.expectedIsAvailable
                            }
                        val staleOption =
                            optionChanges.any { change ->
                                lockedOptions[change.optionId]?.isAvailable != change.expectedIsAvailable
                            }
                        if (staleItem || staleOption) {
                            throw MenuShiftCheckStaleException()
                        }

                        updateShiftCheckItems(connection, venueId, itemChanges)
                        updateShiftCheckOptions(connection, venueId, optionChanges)

                        val categories = loadMenu(connection, venueId)
                        val reviewedItems = categories.flatMap { it.items }
                        val reviewedOptions = reviewedItems.flatMap { it.options }
                        val itemsMadeAvailable =
                            itemChanges.filter { it.desiredIsAvailable }.map { it.itemId }.sorted()
                        val itemsMadeUnavailable =
                            itemChanges.filterNot { it.desiredIsAvailable }.map { it.itemId }.sorted()
                        val optionsMadeAvailable =
                            optionChanges.filter { it.desiredIsAvailable }.map { it.optionId }.sorted()
                        val optionsMadeUnavailable =
                            optionChanges.filterNot { it.desiredIsAvailable }.map { it.optionId }.sorted()

                        auditLogRepository.appendJson(
                            connection = connection,
                            actorUserId = actorUserId,
                            action = MENU_SHIFT_CHECK_COMPLETED_AUDIT_ACTION,
                            entityType = "venue",
                            entityId = venueId,
                            payload =
                                buildJsonObject {
                                    put("actorUserId", actorUserId)
                                    put("venueId", venueId)
                                    put("changedItemCount", itemChanges.size)
                                    put("changedOptionCount", optionChanges.size)
                                    put("itemsMadeAvailable", itemsMadeAvailable.toJsonArray())
                                    put("itemsMadeUnavailable", itemsMadeUnavailable.toJsonArray())
                                    put("optionsMadeAvailable", optionsMadeAvailable.toJsonArray())
                                    put("optionsMadeUnavailable", optionsMadeUnavailable.toJsonArray())
                                    put("reviewedItemCount", reviewedItems.size)
                                    put("reviewedOptionCount", reviewedOptions.size)
                                },
                        )
                        connection.commit()
                        VenueMenuShiftCheckResult(
                            categories = categories,
                            changedItemCount = itemChanges.size,
                            changedOptionCount = optionChanges.size,
                            reviewedItemCount = reviewedItems.size,
                            reviewedOptionCount = reviewedOptions.size,
                            availableItemCount = reviewedItems.count { it.isAvailable },
                            availableOptionCount = reviewedOptions.count { it.isAvailable },
                        )
                    } catch (e: Exception) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        connection.autoCommit = originalAutoCommit
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun createCategory(
        venueId: Long,
        name: String,
        categoryType: MenuSemanticType = MenuSemanticType.OTHER,
    ): VenueMenuCategory {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sortOrder = nextCategorySortOrder(connection, venueId)
                    val categoryId =
                        connection.prepareStatement(
                            """
                            INSERT INTO menu_categories (venue_id, name, sort_order, category_type, updated_at)
                            VALUES (?, ?, ?, ?, now())
                            """.trimIndent(),
                            java.sql.Statement.RETURN_GENERATED_KEYS,
                        ).use { statement ->
                            statement.setLong(1, venueId)
                            statement.setString(2, name)
                            statement.setInt(3, sortOrder)
                            statement.setString(4, categoryType.dbValue)
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
                        categoryType = categoryType,
                        items = emptyList(),
                    )
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateCategory(
        venueId: Long,
        categoryId: Long,
        name: String,
    ): VenueMenuCategory? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE menu_categories
                            SET name = ?, updated_at = now()
                            WHERE id = ? AND venue_id = ?
                            """.trimIndent(),
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

    suspend fun deleteCategory(
        venueId: Long,
        categoryId: Long,
        actorUserId: Long,
        source: MenuCategoryDeleteSource,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val originalAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        if (!categoryExists(connection, venueId, categoryId)) {
                            connection.commit()
                            return@use false
                        }
                        if (categoryHasItems(connection, venueId, categoryId)) {
                            connection.commit()
                            return@use false
                        }
                        val initialReferences =
                            loadPromotionRulesReferencingCategory(connection, venueId, categoryId)
                        lockPromotionRuleReferences(connection, venueId, initialReferences)
                        if (!lockCategoryNowait(connection, venueId, categoryId)) {
                            connection.commit()
                            return@use false
                        }
                        if (categoryHasItems(connection, venueId, categoryId)) {
                            connection.commit()
                            return@use false
                        }
                        val currentReferences =
                            loadPromotionRulesReferencingCategory(connection, venueId, categoryId)
                        ensureNoNewPromotionReferences(initialReferences, currentReferences)
                        val auditPayload =
                            buildMenuCategoryDeleteAuditPayload(
                                venueId = venueId,
                                categoryId = categoryId,
                                source = source,
                                affectedRuleIds = currentReferences.map { it.ruleId },
                            )
                        ensureMenuDeleteAuditPayloadFits(auditPayload, "Menu category delete")
                        bumpPromotionRuleVersions(connection, currentReferences)
                        val deleted =
                            connection.prepareStatement(
                                """
                                DELETE FROM menu_categories
                                WHERE id = ? AND venue_id = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, categoryId)
                                statement.setLong(2, venueId)
                                statement.executeUpdate() > 0
                            }
                        if (!deleted) {
                            throw SQLException(
                                "Locked menu category disappeared during deletion",
                                "40001",
                            )
                        }
                        auditLogWriter.appendJson(
                            connection = connection,
                            actorUserId = actorUserId,
                            action = MENU_CATEGORY_DELETED_AUDIT_ACTION,
                            entityType = "menu_category",
                            entityId = categoryId,
                            payload = auditPayload,
                        )
                        connection.commit()
                        true
                    } catch (e: Exception) {
                        connection.rollback()
                        throw e
                    } finally {
                        connection.autoCommit = originalAutoCommit
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateCategoryType(
        venueId: Long,
        categoryId: Long,
        categoryType: MenuSemanticType,
    ): VenueMenuCategory? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE menu_categories
                            SET category_type = ?, updated_at = now()
                            WHERE id = ? AND venue_id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, categoryType.dbValue)
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

    suspend fun createItem(
        venueId: Long,
        categoryId: Long,
        name: String,
        priceMinor: Long,
        currency: String,
        isAvailable: Boolean,
        itemType: MenuSemanticType? = null,
    ): VenueMenuItem? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    if (!categoryExists(connection, venueId, categoryId)) {
                        return@use null
                    }
                    val sortOrder = nextItemSortOrder(connection, venueId, categoryId)
                    val itemId =
                        connection.prepareStatement(
                            """
                            INSERT INTO menu_items (
                                venue_id, category_id, name, price_minor, currency, is_available, sort_order, item_type, updated_at
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                            """.trimIndent(),
                            java.sql.Statement.RETURN_GENERATED_KEYS,
                        ).use { statement ->
                            statement.setLong(1, venueId)
                            statement.setLong(2, categoryId)
                            statement.setString(3, name)
                            statement.setLong(4, priceMinor)
                            statement.setString(5, currency)
                            statement.setBoolean(6, isAvailable)
                            statement.setInt(7, sortOrder)
                            if (itemType == null) {
                                statement.setNull(8, java.sql.Types.VARCHAR)
                            } else {
                                statement.setString(8, itemType.dbValue)
                            }
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
                        itemType = itemType,
                        options = emptyList(),
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
        isAvailable: Boolean?,
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
                        """.trimIndent(),
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

    suspend fun deleteItem(
        venueId: Long,
        itemId: Long,
        actorUserId: Long,
        source: MenuItemDeleteSource,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val originalAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val initialCategoryId =
                            loadItemCategoryId(connection, venueId, itemId)
                                ?: run {
                                    connection.commit()
                                    return@use false
                                }
                        val initialReferences =
                            loadPromotionRulesReferencingItem(connection, venueId, itemId)
                        lockPromotionRuleReferences(connection, venueId, initialReferences)
                        val lockedCategoryId = lockItemForDeleteNowait(connection, venueId, itemId)
                        if (lockedCategoryId == null) {
                            connection.commit()
                            return@use false
                        }
                        if (lockedCategoryId != initialCategoryId) {
                            throw SQLException(
                                "Menu item category changed concurrently with deletion",
                                "40001",
                            )
                        }
                        val currentReferences =
                            loadPromotionRulesReferencingItem(connection, venueId, itemId)
                        ensureNoNewPromotionReferences(initialReferences, currentReferences)
                        if (hasFixedRewardReference(connection, venueId, itemId)) {
                            throw MenuItemDeleteBlockedByFixedRewardException()
                        }
                        val auditPayload =
                            buildMenuItemDeleteAuditPayload(
                                venueId = venueId,
                                itemId = itemId,
                                categoryId = lockedCategoryId,
                                source = source,
                                affectedRuleIds = currentReferences.map { it.ruleId },
                            )
                        ensureMenuDeleteAuditPayloadFits(auditPayload, "Menu item delete")
                        rehomeChoiceRewardPrimaryReferences(connection, venueId, itemId)
                        bumpPromotionRuleVersions(connection, currentReferences)
                        val deleted =
                            connection.prepareStatement(
                                """
                                DELETE FROM menu_items
                                WHERE id = ? AND venue_id = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, itemId)
                                statement.setLong(2, venueId)
                                statement.executeUpdate() > 0
                            }
                        if (!deleted) {
                            throw SQLException(
                                "Locked menu item disappeared during deletion",
                                "40001",
                            )
                        }
                        auditLogWriter.appendJson(
                            connection = connection,
                            actorUserId = actorUserId,
                            action = MENU_ITEM_DELETED_AUDIT_ACTION,
                            entityType = "menu_item",
                            entityId = itemId,
                            payload = auditPayload,
                        )
                        connection.commit()
                        true
                    } catch (e: Exception) {
                        connection.rollback()
                        throw e
                    } finally {
                        connection.autoCommit = originalAutoCommit
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateItemType(
        venueId: Long,
        itemId: Long,
        itemType: MenuSemanticType?,
    ): VenueMenuItem? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE menu_items
                            SET item_type = ?, updated_at = now()
                            WHERE id = ? AND venue_id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            if (itemType == null) {
                                statement.setNull(1, java.sql.Types.VARCHAR)
                            } else {
                                statement.setString(1, itemType.dbValue)
                            }
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

    suspend fun setItemAvailability(
        venueId: Long,
        itemId: Long,
        isAvailable: Boolean,
    ): VenueMenuItem? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE menu_items
                            SET is_available = ?, updated_at = now()
                            WHERE id = ? AND venue_id = ?
                            """.trimIndent(),
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

    suspend fun reorderCategories(
        venueId: Long,
        categoryIds: List<Long>,
    ): Boolean {
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

    suspend fun reorderItems(
        venueId: Long,
        categoryId: Long,
        itemIds: List<Long>,
    ): Boolean {
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

    suspend fun categoryExists(
        venueId: Long,
        categoryId: Long,
    ): Boolean {
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

    suspend fun categoryHasItems(
        venueId: Long,
        categoryId: Long,
    ): Boolean {
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
                        """.trimIndent(),
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

    suspend fun itemExists(
        venueId: Long,
        itemId: Long,
    ): Boolean {
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

    suspend fun optionExists(
        venueId: Long,
        optionId: Long,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT 1
                        FROM menu_item_options
                        WHERE id = ? AND venue_id = ?
                        """.trimIndent(),
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

    suspend fun listAvailableOptionsForItem(
        venueId: Long,
        itemId: Long,
    ): List<VenueMenuOption> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT id, venue_id, item_id, name, price_delta_minor, is_available, sort_order
                        FROM menu_item_options
                        WHERE venue_id = ?
                          AND item_id = ?
                          AND is_available = true
                        ORDER BY sort_order, id
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, itemId)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<VenueMenuOption>()
                            while (rs.next()) {
                                result.add(mapOption(rs))
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

    suspend fun createOption(
        venueId: Long,
        itemId: Long,
        name: String,
        priceDeltaMinor: Long,
        isAvailable: Boolean,
    ): VenueMenuOption? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    if (!itemExists(connection, venueId, itemId)) {
                        return@use null
                    }
                    val sortOrder = nextOptionSortOrder(connection, venueId, itemId)
                    val optionId =
                        connection.prepareStatement(
                            """
                            INSERT INTO menu_item_options (
                                venue_id, item_id, name, price_delta_minor, is_available, sort_order, updated_at
                            )
                            VALUES (?, ?, ?, ?, ?, ?, now())
                            """.trimIndent(),
                            java.sql.Statement.RETURN_GENERATED_KEYS,
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
                        sortOrder = sortOrder,
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
        isAvailable: Boolean?,
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
                        """.trimIndent(),
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

    suspend fun setOptionAvailability(
        venueId: Long,
        optionId: Long,
        isAvailable: Boolean,
    ): VenueMenuOption? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE menu_item_options
                            SET is_available = ?, updated_at = now()
                            WHERE id = ? AND venue_id = ?
                            """.trimIndent(),
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

    suspend fun deleteOption(
        venueId: Long,
        optionId: Long,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val deleted =
                        connection.prepareStatement(
                            """
                            DELETE FROM menu_item_options
                            WHERE id = ? AND venue_id = ?
                            """.trimIndent(),
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

    private fun validateShiftCheckChanges(
        itemChanges: List<MenuShiftCheckItemChange>,
        optionChanges: List<MenuShiftCheckOptionChange>,
    ) {
        if (
            itemChanges.size > MENU_SHIFT_CHECK_MAX_CHANGES ||
            optionChanges.size > MENU_SHIFT_CHECK_MAX_CHANGES ||
            itemChanges.size + optionChanges.size > MENU_SHIFT_CHECK_MAX_CHANGES
        ) {
            throw InvalidInputException(
                "Количество изменений проверки меню не должно превышать " +
                    "$MENU_SHIFT_CHECK_MAX_CHANGES.",
            )
        }
        if (itemChanges.any { it.itemId <= 0 }) {
            throw InvalidInputException("itemId должен быть положительным.")
        }
        if (optionChanges.any { it.optionId <= 0 || it.itemId <= 0 }) {
            throw InvalidInputException("optionId и itemId должны быть положительными.")
        }
        if (itemChanges.map { it.itemId }.toSet().size != itemChanges.size) {
            throw InvalidInputException("Позиции не должны повторяться.")
        }
        if (optionChanges.map { it.optionId }.toSet().size != optionChanges.size) {
            throw InvalidInputException("Опции не должны повторяться.")
        }
        if (itemChanges.any { it.expectedIsAvailable == it.desiredIsAvailable }) {
            throw InvalidInputException(
                "Запрос должен содержать только изменения позиций.",
            )
        }
        if (optionChanges.any { it.expectedIsAvailable == it.desiredIsAvailable }) {
            throw InvalidInputException(
                "Запрос должен содержать только изменения опций.",
            )
        }
    }

    private fun loadMenu(
        connection: Connection,
        venueId: Long,
    ): List<VenueMenuCategory> {
        val categories =
            connection.prepareStatement(
                """
                SELECT id, venue_id, name, sort_order, category_type
                FROM menu_categories
                WHERE venue_id = ?
                ORDER BY sort_order, id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(mapCategory(rs))
                        }
                    }
                }
            }
        val itemsByCategory =
            connection.prepareStatement(
                """
                SELECT id, venue_id, category_id, name, price_minor, currency, is_available, sort_order, item_type
                FROM menu_items
                WHERE venue_id = ?
                ORDER BY sort_order, id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { rs ->
                    val result = mutableMapOf<Long, MutableList<VenueMenuItem>>()
                    while (rs.next()) {
                        val categoryId = rs.getLong("category_id")
                        result.getOrPut(categoryId) { mutableListOf() }.add(mapItem(rs))
                    }
                    result
                }
            }
        val optionsByItem =
            connection.prepareStatement(
                """
                SELECT id, venue_id, item_id, name, price_delta_minor, is_available, sort_order
                FROM menu_item_options
                WHERE venue_id = ?
                ORDER BY sort_order, id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { rs ->
                    val result = mutableMapOf<Long, MutableList<VenueMenuOption>>()
                    while (rs.next()) {
                        val itemId = rs.getLong("item_id")
                        result.getOrPut(itemId) { mutableListOf() }.add(mapOption(rs))
                    }
                    result
                }
            }
        return categories.map { category ->
            category.copy(
                items =
                    itemsByCategory[category.id].orEmpty().map { item ->
                        item.copy(options = optionsByItem[item.id].orEmpty())
                    },
            )
        }
    }

    private fun loadShiftCheckItemsForUpdate(
        connection: Connection,
        venueId: Long,
        itemIds: List<Long>,
    ): Map<Long, ShiftCheckLockedItem> {
        if (itemIds.isEmpty()) {
            return emptyMap()
        }
        val placeholders = itemIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            SELECT id, is_available
            FROM menu_items
            WHERE venue_id = ?
              AND id IN ($placeholders)
            ORDER BY id
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            itemIds.forEachIndexed { index, itemId ->
                statement.setLong(index + 2, itemId)
            }
            statement.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val itemId = rs.getLong("id")
                        put(
                            itemId,
                            ShiftCheckLockedItem(
                                isAvailable = rs.getBoolean("is_available"),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun loadShiftCheckOptionsForUpdate(
        connection: Connection,
        venueId: Long,
        optionIds: List<Long>,
    ): Map<Long, ShiftCheckLockedOption> {
        if (optionIds.isEmpty()) {
            return emptyMap()
        }
        val placeholders = optionIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            SELECT id, item_id, is_available
            FROM menu_item_options
            WHERE venue_id = ?
              AND id IN ($placeholders)
            ORDER BY id
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            optionIds.forEachIndexed { index, optionId ->
                statement.setLong(index + 2, optionId)
            }
            statement.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val optionId = rs.getLong("id")
                        put(
                            optionId,
                            ShiftCheckLockedOption(
                                itemId = rs.getLong("item_id"),
                                isAvailable = rs.getBoolean("is_available"),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun updateShiftCheckItems(
        connection: Connection,
        venueId: Long,
        changes: List<MenuShiftCheckItemChange>,
    ) {
        if (changes.isEmpty()) {
            return
        }
        val results =
            connection.prepareStatement(
                """
                UPDATE menu_items
                SET is_available = ?, updated_at = now()
                WHERE id = ? AND venue_id = ? AND is_available = ?
                """.trimIndent(),
            ).use { statement ->
                changes.sortedBy { it.itemId }.forEach { change ->
                    statement.setBoolean(1, change.desiredIsAvailable)
                    statement.setLong(2, change.itemId)
                    statement.setLong(3, venueId)
                    statement.setBoolean(4, change.expectedIsAvailable)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        if (results.size != changes.size || results.any { it == 0 || it == Statement.EXECUTE_FAILED }) {
            throw MenuShiftCheckStaleException()
        }
    }

    private fun updateShiftCheckOptions(
        connection: Connection,
        venueId: Long,
        changes: List<MenuShiftCheckOptionChange>,
    ) {
        if (changes.isEmpty()) {
            return
        }
        val results =
            connection.prepareStatement(
                """
                UPDATE menu_item_options
                SET is_available = ?, updated_at = now()
                WHERE id = ? AND venue_id = ? AND item_id = ? AND is_available = ?
                """.trimIndent(),
            ).use { statement ->
                changes.sortedBy { it.optionId }.forEach { change ->
                    statement.setBoolean(1, change.desiredIsAvailable)
                    statement.setLong(2, change.optionId)
                    statement.setLong(3, venueId)
                    statement.setLong(4, change.itemId)
                    statement.setBoolean(5, change.expectedIsAvailable)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        if (results.size != changes.size || results.any { it == 0 || it == Statement.EXECUTE_FAILED }) {
            throw MenuShiftCheckStaleException()
        }
    }

    private fun mapCategory(rs: ResultSet): VenueMenuCategory =
        VenueMenuCategory(
            id = rs.getLong("id"),
            venueId = rs.getLong("venue_id"),
            name = rs.getString("name"),
            sortOrder = rs.getInt("sort_order"),
            categoryType = MenuSemanticType.fromDb(rs.getString("category_type")),
            items = emptyList(),
        )

    private fun mapItem(rs: ResultSet): VenueMenuItem =
        VenueMenuItem(
            id = rs.getLong("id"),
            venueId = rs.getLong("venue_id"),
            categoryId = rs.getLong("category_id"),
            name = rs.getString("name"),
            priceMinor = rs.getLong("price_minor"),
            currency = rs.getString("currency"),
            isAvailable = rs.getBoolean("is_available"),
            sortOrder = rs.getInt("sort_order"),
            itemType = MenuSemanticType.nullableFromDb(rs.getString("item_type")),
            options = emptyList(),
        )

    private fun mapOption(rs: ResultSet): VenueMenuOption =
        VenueMenuOption(
            id = rs.getLong("id"),
            venueId = rs.getLong("venue_id"),
            itemId = rs.getLong("item_id"),
            name = rs.getString("name"),
            priceDeltaMinor = rs.getLong("price_delta_minor"),
            isAvailable = rs.getBoolean("is_available"),
            sortOrder = rs.getInt("sort_order"),
        )

    private fun loadCategory(
        connection: Connection,
        categoryId: Long,
        venueId: Long,
    ): VenueMenuCategory? {
        return connection.prepareStatement(
            """
            SELECT id, venue_id, name, sort_order, category_type
            FROM menu_categories
            WHERE id = ? AND venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, categoryId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) mapCategory(rs) else null
            }
        }
    }

    private fun loadItem(
        connection: Connection,
        itemId: Long,
        venueId: Long,
    ): VenueMenuItem? {
        return connection.prepareStatement(
            """
            SELECT id, venue_id, category_id, name, price_minor, currency, is_available, sort_order, item_type
            FROM menu_items
            WHERE id = ? AND venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, itemId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) mapItem(rs) else null
            }
        }
    }

    private fun loadOption(
        connection: Connection,
        optionId: Long,
        venueId: Long,
    ): VenueMenuOption? {
        return connection.prepareStatement(
            """
            SELECT id, venue_id, item_id, name, price_delta_minor, is_available, sort_order
            FROM menu_item_options
            WHERE id = ? AND venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, optionId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) mapOption(rs) else null
            }
        }
    }

    private fun categoryExists(
        connection: Connection,
        venueId: Long,
        categoryId: Long,
    ): Boolean {
        return connection.prepareStatement(
            """
            SELECT 1
            FROM menu_categories
            WHERE id = ? AND venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, categoryId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun categoryHasItems(
        connection: Connection,
        venueId: Long,
        categoryId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT 1
            FROM menu_items
            WHERE venue_id = ? AND category_id = ?
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, categoryId)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun loadPromotionRulesReferencingCategory(
        connection: Connection,
        venueId: Long,
        categoryId: Long,
    ): List<PromotionRuleReference> =
        connection.prepareStatement(
            """
            SELECT DISTINCT r.id AS rule_id, r.promotion_id
            FROM promotion_rules r
            JOIN promotion_rule_menu_category_targets target ON target.rule_id = r.id
            WHERE r.venue_id = ?
              AND target.menu_category_id = ?
            ORDER BY r.id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, categoryId)
            statement.executeQuery().use { rs -> rs.toPromotionRuleReferences() }
        }

    private fun loadPromotionRulesReferencingItem(
        connection: Connection,
        venueId: Long,
        itemId: Long,
    ): List<PromotionRuleReference> =
        connection.prepareStatement(
            """
            SELECT DISTINCT r.id AS rule_id, r.promotion_id
            FROM promotion_rules r
            WHERE r.venue_id = ?
              AND (
                    EXISTS (
                        SELECT 1
                        FROM promotion_rule_targets target
                        WHERE target.rule_id = r.id
                          AND target.menu_item_id = ?
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM promotion_rule_rewards reward
                        WHERE reward.rule_id = r.id
                          AND reward.reward_menu_item_id = ?
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM promotion_rule_rewards reward
                        JOIN promotion_rule_reward_options option ON option.reward_id = reward.id
                        WHERE reward.rule_id = r.id
                          AND option.menu_item_id = ?
                    )
              )
            ORDER BY r.id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, itemId)
            statement.setLong(3, itemId)
            statement.setLong(4, itemId)
            statement.executeQuery().use { rs -> rs.toPromotionRuleReferences() }
        }

    private fun hasFixedRewardReference(
        connection: Connection,
        venueId: Long,
        itemId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT 1
            FROM promotion_rule_rewards reward
            JOIN promotion_rules rule ON rule.id = reward.rule_id
            WHERE rule.venue_id = ?
              AND reward.reward_menu_item_id = ?
              AND reward.reward_mode = 'FIXED_ITEM'
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, itemId)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun rehomeChoiceRewardPrimaryReferences(
        connection: Connection,
        venueId: Long,
        itemId: Long,
    ) {
        val replacements = linkedMapOf<Long, Long?>()
        connection.prepareStatement(
            """
            SELECT reward.id AS reward_id, option.menu_item_id AS replacement_item_id
            FROM promotion_rule_rewards reward
            JOIN promotion_rules rule ON rule.id = reward.rule_id
            LEFT JOIN promotion_rule_reward_options option
              ON option.reward_id = reward.id
             AND option.menu_item_id <> ?
            WHERE rule.venue_id = ?
              AND reward.reward_menu_item_id = ?
              AND reward.reward_mode = 'CHOICE_ITEMS'
            ORDER BY reward.id, option.id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, itemId)
            statement.setLong(2, venueId)
            statement.setLong(3, itemId)
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    val replacementItemId =
                        rs.getLong("replacement_item_id").let { value ->
                            if (rs.wasNull()) null else value
                        }
                    replacements.putIfAbsent(rs.getLong("reward_id"), replacementItemId)
                }
            }
        }
        replacements.forEach { (rewardId, replacementItemId) ->
            if (replacementItemId == null) {
                connection.prepareStatement(
                    "DELETE FROM promotion_rule_rewards WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, rewardId)
                    statement.executeUpdate()
                }
            } else {
                connection.prepareStatement(
                    """
                    UPDATE promotion_rule_rewards
                    SET reward_menu_item_id = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, replacementItemId)
                    statement.setLong(2, rewardId)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun ResultSet.toPromotionRuleReferences(): List<PromotionRuleReference> =
        buildList {
            while (next()) {
                val promotionId = getLong("promotion_id").let { value -> if (wasNull()) null else value }
                add(
                    PromotionRuleReference(
                        ruleId = getLong("rule_id"),
                        promotionId = promotionId,
                    ),
                )
            }
        }

    private fun lockPromotionRuleReferences(
        connection: Connection,
        venueId: Long,
        references: List<PromotionRuleReference>,
    ) {
        val promotionIds = references.mapNotNull { it.promotionId }.distinct().sorted()
        if (promotionIds.isNotEmpty()) {
            val placeholders = promotionIds.joinToString(",") { "?" }
            connection.prepareStatement(
                """
                SELECT id
                FROM venue_promotions
                WHERE venue_id = ?
                  AND id IN ($placeholders)
                ORDER BY id
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                promotionIds.forEachIndexed { index, promotionId ->
                    statement.setLong(index + 2, promotionId)
                }
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        rs.getLong("id")
                    }
                }
            }
        }

        val ruleIds = references.map { it.ruleId }.distinct().sorted()
        if (ruleIds.isEmpty()) {
            return
        }
        val placeholders = ruleIds.joinToString(",") { "?" }
        connection.prepareStatement(
            """
            SELECT id
            FROM promotion_rules
            WHERE venue_id = ?
              AND id IN ($placeholders)
            ORDER BY promotion_id, id
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            ruleIds.forEachIndexed { index, ruleId ->
                statement.setLong(index + 2, ruleId)
            }
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    rs.getLong("id")
                }
            }
        }
    }

    private fun lockCategoryNowait(
        connection: Connection,
        venueId: Long,
        categoryId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT id
            FROM menu_categories
            WHERE id = ? AND venue_id = ?
            FOR UPDATE NOWAIT
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, categoryId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun loadItemCategoryId(
        connection: Connection,
        venueId: Long,
        itemId: Long,
    ): Long? =
        connection.prepareStatement(
            """
            SELECT category_id
            FROM menu_items
            WHERE id = ? AND venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, itemId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getLong("category_id") else null
            }
        }

    private fun lockItemForDeleteNowait(
        connection: Connection,
        venueId: Long,
        itemId: Long,
    ): Long? =
        connection.prepareStatement(
            """
            SELECT category_id
            FROM menu_items
            WHERE id = ? AND venue_id = ?
            FOR UPDATE NOWAIT
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, itemId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getLong("category_id") else null
            }
        }

    private fun ensureNoNewPromotionReferences(
        initial: List<PromotionRuleReference>,
        current: List<PromotionRuleReference>,
    ) {
        val initialByRuleId = initial.associateBy { it.ruleId }
        if (current.any { reference -> initialByRuleId[reference.ruleId] != reference }) {
            throw SQLException(
                "Promotion configuration changed concurrently with menu deletion",
                "40001",
            )
        }
    }

    private fun bumpPromotionRuleVersions(
        connection: Connection,
        references: List<PromotionRuleReference>,
    ) {
        val ruleIds = references.map { it.ruleId }.distinct().sorted()
        if (ruleIds.isEmpty()) {
            return
        }
        val placeholders = ruleIds.joinToString(",") { "?" }
        connection.prepareStatement(
            """
            UPDATE promotion_rules
            SET version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id IN ($placeholders)
            """.trimIndent(),
        ).use { statement ->
            ruleIds.forEachIndexed { index, ruleId ->
                statement.setLong(index + 1, ruleId)
            }
            statement.executeUpdate()
        }
    }

    private fun itemExists(
        connection: Connection,
        venueId: Long,
        itemId: Long,
    ): Boolean {
        return connection.prepareStatement(
            """
            SELECT 1
            FROM menu_items
            WHERE id = ? AND venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, itemId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun nextCategorySortOrder(
        connection: Connection,
        venueId: Long,
    ): Int {
        return connection.prepareStatement(
            """
            SELECT COALESCE(MAX(sort_order), -1) + 1 AS next_order
            FROM menu_categories
            WHERE venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getInt("next_order") else 0
            }
        }
    }

    private fun nextItemSortOrder(
        connection: Connection,
        venueId: Long,
        categoryId: Long,
    ): Int {
        return connection.prepareStatement(
            """
            SELECT COALESCE(MAX(sort_order), -1) + 1 AS next_order
            FROM menu_items
            WHERE venue_id = ? AND category_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, categoryId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getInt("next_order") else 0
            }
        }
    }

    private fun nextOptionSortOrder(
        connection: Connection,
        venueId: Long,
        itemId: Long,
    ): Int {
        return connection.prepareStatement(
            """
            SELECT COALESCE(MAX(sort_order), -1) + 1 AS next_order
            FROM menu_item_options
            WHERE venue_id = ? AND item_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, itemId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getInt("next_order") else 0
            }
        }
    }

    private fun countCategories(
        connection: Connection,
        venueId: Long,
        categoryIds: List<Long>,
    ): Int {
        val placeholders = categoryIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            SELECT COUNT(*) AS total
            FROM menu_categories
            WHERE venue_id = ? AND id IN ($placeholders)
            """.trimIndent(),
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

    private fun countItems(
        connection: Connection,
        venueId: Long,
        categoryId: Long,
        itemIds: List<Long>,
    ): Int {
        val placeholders = itemIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            SELECT COUNT(*) AS total
            FROM menu_items
            WHERE venue_id = ? AND category_id = ? AND id IN ($placeholders)
            """.trimIndent(),
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

    private fun updateCategoryOrder(
        connection: Connection,
        venueId: Long,
        categoryIds: List<Long>,
    ) {
        connection.prepareStatement(
            """
            UPDATE menu_categories
            SET sort_order = ?, updated_at = now()
            WHERE venue_id = ? AND id = ?
            """.trimIndent(),
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

    private fun updateItemOrder(
        connection: Connection,
        venueId: Long,
        itemIds: List<Long>,
    ) {
        connection.prepareStatement(
            """
            UPDATE menu_items
            SET sort_order = ?, updated_at = now()
            WHERE venue_id = ? AND id = ?
            """.trimIndent(),
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

    private data class PromotionRuleReference(
        val ruleId: Long,
        val promotionId: Long?,
    )

    private data class ShiftCheckLockedItem(
        val isAvailable: Boolean,
    )

    private data class ShiftCheckLockedOption(
        val itemId: Long,
        val isAvailable: Boolean,
    )
}

internal fun buildMenuItemDeleteAuditPayload(
    venueId: Long,
    itemId: Long,
    categoryId: Long,
    source: MenuItemDeleteSource,
    affectedRuleIds: Iterable<Long>,
) = buildJsonObject {
    put("venueId", venueId)
    put("itemId", itemId)
    put("categoryId", categoryId)
    put("source", source.name)
    put("affectedPromotionRules", buildAffectedPromotionRuleSummary(affectedRuleIds))
}

internal fun buildMenuCategoryDeleteAuditPayload(
    venueId: Long,
    categoryId: Long,
    source: MenuCategoryDeleteSource,
    affectedRuleIds: Iterable<Long>,
) = buildJsonObject {
    put("venueId", venueId)
    put("categoryId", categoryId)
    put("source", source.name)
    put("affectedPromotionRules", buildAffectedPromotionRuleSummary(affectedRuleIds))
}

private fun buildAffectedPromotionRuleSummary(affectedRuleIds: Iterable<Long>) =
    buildJsonObject {
        val sortedUniqueRuleIds = affectedRuleIds.toSet().sorted()
        val sampleRuleIds = sortedUniqueRuleIds.take(MENU_DELETE_AFFECTED_RULE_SAMPLE_LIMIT)
        val canonicalHashInput = "v1:" + sortedUniqueRuleIds.joinToString(",")
        val sha256 =
            MessageDigest
                .getInstance("SHA-256")
                .digest(canonicalHashInput.toByteArray(StandardCharsets.UTF_8))
                .toLowercaseHex()

        put("totalCount", sortedUniqueRuleIds.size)
        put(
            "sampleRuleIds",
            buildJsonArray {
                sampleRuleIds.forEach { add(JsonPrimitive(it)) }
            },
        )
        put("omittedCount", sortedUniqueRuleIds.size - sampleRuleIds.size)
        put("sha256", sha256)
    }

private fun ensureMenuDeleteAuditPayloadFits(
    payload: kotlinx.serialization.json.JsonObject,
    label: String,
) {
    val payloadBytes = payload.toString().toByteArray(StandardCharsets.UTF_8).size
    if (payloadBytes >= MENU_DELETE_AUDIT_PAYLOAD_MAX_BYTES) {
        throw SQLException("$label audit payload exceeds byte budget")
    }
}

private fun ByteArray.toLowercaseHex(): String {
    val alphabet = "0123456789abcdef"
    return buildString(size * 2) {
        this@toLowercaseHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0f])
        }
    }
}

private fun List<Long>.toJsonArray() =
    buildJsonArray {
        forEach { add(JsonPrimitive(it)) }
    }
