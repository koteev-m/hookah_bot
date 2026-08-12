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
const val MENU_OPTION_DELETED_AUDIT_ACTION = "MENU_OPTION_DELETED"
const val MENU_OPTION_CREATED_AUDIT_ACTION = "MENU_OPTION_CREATED"
const val MENU_OPTION_RENAMED_AUDIT_ACTION = "MENU_OPTION_RENAMED"
const val MENU_OPTION_PRICE_CHANGED_AUDIT_ACTION = "MENU_OPTION_PRICE_CHANGED"
const val MENU_OPTION_AVAILABILITY_CHANGED_AUDIT_ACTION = "MENU_OPTION_AVAILABILITY_CHANGED"
const val MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION = "MENU_ITEM_AVAILABILITY_CHANGED"
internal const val MENU_DELETE_AFFECTED_RULE_SAMPLE_LIMIT = 50
internal const val MENU_DELETE_AUDIT_PAYLOAD_MAX_BYTES = 4096
internal const val MENU_ITEM_DELETE_AFFECTED_RULE_SAMPLE_LIMIT = MENU_DELETE_AFFECTED_RULE_SAMPLE_LIMIT
internal const val MENU_ITEM_DELETE_AUDIT_PAYLOAD_MAX_BYTES = MENU_DELETE_AUDIT_PAYLOAD_MAX_BYTES

enum class MenuItemDeleteSource {
    VENUE_MINI_APP,
    TELEGRAM_BOT,
}

enum class MenuItemAvailabilitySource {
    VENUE_MINI_APP,
    TELEGRAM_BOT,
}

enum class MenuCategoryDeleteSource {
    VENUE_MINI_APP,
    TELEGRAM_BOT,
}

enum class MenuOptionDeleteSource {
    VENUE_MINI_APP,
    TELEGRAM_BOT,
}

enum class MenuOptionCreateSource {
    VENUE_MINI_APP,
    TELEGRAM_BOT,
}

enum class MenuOptionRenameSource {
    VENUE_MINI_APP,
    TELEGRAM_BOT,
}

enum class MenuOptionAvailabilitySource {
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

data class HookahFlavorProfileNormalizationResult(
    val removedCount: Int,
    val addedCount: Int,
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
        actorUserId: Long,
        source: MenuItemAvailabilitySource,
        itemType: MenuSemanticType? = null,
        itemTypeSpecified: Boolean = false,
    ): VenueMenuItem? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val originalAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val existing = loadItemForUpdate(connection, itemId, venueId)
                        if (existing == null) {
                            connection.commit()
                            return@use null
                        }
                        if (!categoryExists(connection, venueId, existing.categoryId)) {
                            throw SQLException("Locked menu item category is missing", "40001")
                        }
                        val updatedCategoryId = categoryId ?: existing.categoryId
                        if (!categoryExists(connection, venueId, updatedCategoryId)) {
                            connection.commit()
                            return@use null
                        }
                        val updatedName = name ?: existing.name
                        val updatedPriceMinor = priceMinor ?: existing.priceMinor
                        val updatedCurrency = currency ?: existing.currency
                        val updatedIsAvailable = isAvailable ?: existing.isAvailable
                        val updatedItemType = if (itemTypeSpecified) itemType else existing.itemType
                        val isAvailabilityChange = updatedIsAvailable != existing.isAvailable
                        val hasChanges =
                            updatedCategoryId != existing.categoryId ||
                                updatedName != existing.name ||
                                updatedPriceMinor != existing.priceMinor ||
                                updatedCurrency != existing.currency ||
                                isAvailabilityChange ||
                                updatedItemType != existing.itemType
                        if (!hasChanges) {
                            connection.commit()
                            return@use existing
                        }
                        val updated =
                            connection.prepareStatement(
                                """
                                UPDATE menu_items
                                SET category_id = ?, name = ?, price_minor = ?, currency = ?,
                                    is_available = ?, item_type = ?, updated_at = now()
                                WHERE id = ? AND venue_id = ? AND is_available = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, updatedCategoryId)
                                statement.setString(2, updatedName)
                                statement.setLong(3, updatedPriceMinor)
                                statement.setString(4, updatedCurrency)
                                statement.setBoolean(5, updatedIsAvailable)
                                if (updatedItemType == null) {
                                    statement.setNull(6, java.sql.Types.VARCHAR)
                                } else {
                                    statement.setString(6, updatedItemType.dbValue)
                                }
                                statement.setLong(7, itemId)
                                statement.setLong(8, venueId)
                                statement.setBoolean(9, existing.isAvailable)
                                statement.executeUpdate()
                            }
                        if (updated != 1) {
                            throw SQLException("Locked menu item changed during update", "40001")
                        }
                        if (isAvailabilityChange) {
                            auditMenuItemAvailabilityChange(
                                connection = connection,
                                venueId = venueId,
                                itemId = itemId,
                                oldIsAvailable = existing.isAvailable,
                                newIsAvailable = updatedIsAvailable,
                                actorUserId = actorUserId,
                                source = source,
                            )
                        }
                        val result = loadItem(connection, itemId, venueId)
                        connection.commit()
                        result
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
        actorUserId: Long,
        source: MenuItemAvailabilitySource,
    ): VenueMenuItem? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val originalAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val existing = loadItemForUpdate(connection, itemId, venueId)
                        if (existing == null) {
                            connection.commit()
                            return@use null
                        }
                        if (!categoryExists(connection, venueId, existing.categoryId)) {
                            throw SQLException("Locked menu item category is missing", "40001")
                        }
                        if (existing.isAvailable == isAvailable) {
                            connection.commit()
                            return@use existing
                        }
                        val updated =
                            connection.prepareStatement(
                                """
                                UPDATE menu_items
                                SET is_available = ?, updated_at = now()
                                WHERE id = ? AND venue_id = ? AND is_available = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setBoolean(1, isAvailable)
                                statement.setLong(2, itemId)
                                statement.setLong(3, venueId)
                                statement.setBoolean(4, existing.isAvailable)
                                statement.executeUpdate()
                            }
                        if (updated != 1) {
                            throw SQLException("Locked menu item changed during availability update", "40001")
                        }
                        auditMenuItemAvailabilityChange(
                            connection = connection,
                            venueId = venueId,
                            itemId = itemId,
                            oldIsAvailable = existing.isAvailable,
                            newIsAvailable = isAvailable,
                            actorUserId = actorUserId,
                            source = source,
                        )
                        val result = loadItem(connection, itemId, venueId)
                        connection.commit()
                        result
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
        actorUserId: Long,
        source: MenuOptionCreateSource,
    ): VenueMenuOption? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val originalAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val itemScope = lockItemForOptionMutation(connection, venueId, itemId)
                        if (itemScope == null) {
                            connection.commit()
                            return@use null
                        }
                        val lockedOptions = loadItemOptionsForUpdate(connection, venueId, itemId)
                        if (itemScope.isHookahMenuSection()) {
                            ensureCanonicalProfileIsUnique(lockedOptions, name)
                        }
                        val inserted =
                            insertOption(
                                connection = connection,
                                venueId = venueId,
                                itemId = itemId,
                                name = name,
                                priceDeltaMinor = priceDeltaMinor,
                                isAvailable = isAvailable,
                                sortOrder = lockedOptions.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0,
                            )
                        auditMenuOptionCreate(
                            connection = connection,
                            venueId = venueId,
                            itemId = itemId,
                            optionId = inserted.id,
                            actorUserId = actorUserId,
                            source = source,
                        )
                        val created =
                            loadOption(connection, inserted.id, venueId)
                                ?: throw SQLException("Inserted menu option is missing", "40001")
                        connection.commit()
                        created
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

    suspend fun applyMissingBaseProfiles(
        venueId: Long,
        itemId: Long,
        actorUserId: Long,
        source: MenuOptionCreateSource,
    ): HookahBaseFlavorProfileApplyResult? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val originalAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val itemScope = lockItemForOptionMutation(connection, venueId, itemId)
                        if (itemScope == null) {
                            connection.commit()
                            return@use null
                        }
                        if (!itemScope.isHookahFlavorProfileItem()) {
                            throw InvalidInputException("base flavor profiles are available only for hookah items")
                        }
                        val lockedOptions = loadItemOptionsForUpdate(connection, venueId, itemId)
                        val missingProfiles =
                            HookahFlavorProfileService.missingBaseProfiles(
                                lockedOptions.map { it.name },
                            )
                        var nextSortOrder = lockedOptions.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
                        missingProfiles.forEach { profileName ->
                            val created =
                                insertOption(
                                    connection = connection,
                                    venueId = venueId,
                                    itemId = itemId,
                                    name = profileName,
                                    priceDeltaMinor = 0,
                                    isAvailable = true,
                                    sortOrder = nextSortOrder,
                                )
                            auditMenuOptionCreate(
                                connection = connection,
                                venueId = venueId,
                                itemId = itemId,
                                optionId = created.id,
                                actorUserId = actorUserId,
                                source = source,
                            )
                            nextSortOrder += 1
                        }
                        val options = loadItemOptions(connection, venueId, itemId)
                        connection.commit()
                        HookahBaseFlavorProfileApplyResult(
                            itemId = itemId,
                            addedCount = missingProfiles.size,
                            existingCount = HookahFlavorProfileService.baseProfiles.size - missingProfiles.size,
                            options = options,
                        )
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

    suspend fun updateOption(
        venueId: Long,
        optionId: Long,
        name: String?,
        priceDeltaMinor: Long?,
        isAvailable: Boolean?,
        actorUserId: Long,
        source: MenuOptionRenameSource,
    ): VenueMenuOption? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val originalAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val itemId = loadOptionItemId(connection, venueId, optionId)
                        val itemScope = itemId?.let { lockItemForOptionMutation(connection, venueId, it) }
                        if (itemId == null || itemScope == null) {
                            connection.commit()
                            return@use null
                        }
                        val lockedOptions = loadItemOptionsForUpdate(connection, venueId, itemId)
                        val existing = lockedOptions.firstOrNull { it.id == optionId }
                        if (existing == null) {
                            connection.commit()
                            return@use null
                        }
                        val updatedName = name ?: existing.name
                        val updatedPriceDeltaMinor = priceDeltaMinor ?: existing.priceDeltaMinor
                        val updatedIsAvailable = isAvailable ?: existing.isAvailable
                        val isRename = updatedName != existing.name
                        val isPriceChange = updatedPriceDeltaMinor != existing.priceDeltaMinor
                        val isAvailabilityChange = updatedIsAvailable != existing.isAvailable
                        if (isPriceChange && source != MenuOptionRenameSource.VENUE_MINI_APP) {
                            throw InvalidInputException("Option price changes are available only in Venue Mini App")
                        }
                        val changesCanonicalProfile =
                            name != null &&
                                HookahFlavorProfileService.normalizeFlavorNameKey(existing.name) !=
                                HookahFlavorProfileService.normalizeFlavorNameKey(updatedName)
                        if (itemScope.isHookahMenuSection() && changesCanonicalProfile) {
                            ensureCanonicalProfileIsUnique(
                                options = lockedOptions,
                                name = updatedName,
                                excludedOptionId = optionId,
                            )
                        }
                        val updated =
                            connection.prepareStatement(
                                """
                                UPDATE menu_item_options
                                SET name = ?, price_delta_minor = ?, is_available = ?, updated_at = now()
                                WHERE id = ? AND venue_id = ? AND item_id = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, updatedName)
                                statement.setLong(2, updatedPriceDeltaMinor)
                                statement.setBoolean(3, updatedIsAvailable)
                                statement.setLong(4, optionId)
                                statement.setLong(5, venueId)
                                statement.setLong(6, itemId)
                                statement.executeUpdate()
                            }
                        if (updated != 1) {
                            throw SQLException("Locked menu option changed during update", "40001")
                        }
                        if (isRename) {
                            auditMenuOptionRename(
                                connection = connection,
                                venueId = venueId,
                                itemId = itemId,
                                optionId = optionId,
                                oldName = existing.name,
                                newName = updatedName,
                                actorUserId = actorUserId,
                                source = source,
                            )
                        }
                        if (isPriceChange) {
                            auditMenuOptionPriceChange(
                                connection = connection,
                                venueId = venueId,
                                itemId = itemId,
                                optionId = optionId,
                                oldPriceDeltaMinor = existing.priceDeltaMinor,
                                newPriceDeltaMinor = updatedPriceDeltaMinor,
                                actorUserId = actorUserId,
                            )
                        }
                        if (isAvailabilityChange) {
                            auditMenuOptionAvailabilityChange(
                                connection = connection,
                                venueId = venueId,
                                itemId = itemId,
                                optionId = optionId,
                                oldIsAvailable = existing.isAvailable,
                                newIsAvailable = updatedIsAvailable,
                                actorUserId = actorUserId,
                                source = source.name,
                            )
                        }
                        val result = loadOption(connection, optionId, venueId)
                        connection.commit()
                        result
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

    suspend fun setOptionAvailability(
        venueId: Long,
        optionId: Long,
        isAvailable: Boolean,
        actorUserId: Long,
        source: MenuOptionAvailabilitySource,
    ): VenueMenuOption? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val originalAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val itemId = loadOptionItemId(connection, venueId, optionId)
                        val itemScope = itemId?.let { lockItemForOptionMutation(connection, venueId, it) }
                        if (itemId == null || itemScope == null) {
                            connection.commit()
                            return@use null
                        }
                        val existing =
                            loadItemOptionsForUpdate(connection, venueId, itemId)
                                .firstOrNull { it.id == optionId }
                        if (existing == null) {
                            connection.commit()
                            return@use null
                        }
                        if (existing.isAvailable == isAvailable) {
                            connection.commit()
                            return@use existing
                        }
                        val updated =
                            connection.prepareStatement(
                                """
                                UPDATE menu_item_options
                                SET is_available = ?, updated_at = now()
                                WHERE id = ? AND venue_id = ? AND item_id = ? AND is_available = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setBoolean(1, isAvailable)
                                statement.setLong(2, optionId)
                                statement.setLong(3, venueId)
                                statement.setLong(4, itemId)
                                statement.setBoolean(5, existing.isAvailable)
                                statement.executeUpdate()
                            }
                        if (updated != 1) {
                            throw SQLException("Locked menu option changed during availability update", "40001")
                        }
                        auditMenuOptionAvailabilityChange(
                            connection = connection,
                            venueId = venueId,
                            itemId = itemId,
                            optionId = optionId,
                            oldIsAvailable = existing.isAvailable,
                            newIsAvailable = isAvailable,
                            actorUserId = actorUserId,
                            source = source.name,
                        )
                        val result = loadOption(connection, optionId, venueId)
                        connection.commit()
                        result
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

    suspend fun deleteOption(
        venueId: Long,
        optionId: Long,
        actorUserId: Long,
        source: MenuOptionDeleteSource,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val originalAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val itemId = loadOptionItemId(connection, venueId, optionId)
                        if (itemId == null || lockItemForOptionMutation(connection, venueId, itemId) == null) {
                            connection.commit()
                            return@use false
                        }
                        val lockedOption =
                            loadItemOptionsForUpdate(connection, venueId, itemId)
                                .firstOrNull { it.id == optionId }
                        if (lockedOption == null) {
                            connection.commit()
                            return@use false
                        }
                        deleteLockedOption(connection, venueId, itemId, optionId)
                        auditMenuOptionDelete(
                            connection = connection,
                            venueId = venueId,
                            itemId = itemId,
                            optionId = optionId,
                            actorUserId = actorUserId,
                            source = source,
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

    suspend fun normalizeHookahFlavorProfiles(
        venueId: Long,
        itemId: Long,
        actorUserId: Long,
        deleteSource: MenuOptionDeleteSource,
        createSource: MenuOptionCreateSource,
    ): HookahFlavorProfileNormalizationResult? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val originalAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val itemScope = lockItemForOptionMutation(connection, venueId, itemId)
                        if (itemScope == null) {
                            connection.commit()
                            return@use null
                        }
                        if (!itemScope.isHookahMenuSection()) {
                            throw InvalidInputException("base flavor profiles are available only for hookah items")
                        }
                        val lockedOptions = loadItemOptionsForUpdate(connection, venueId, itemId)
                        val obsoleteOptions =
                            lockedOptions
                                .filter { HookahFlavorProfileService.isObsoleteProfileValue(it.name) }
                                .sortedBy { it.id }
                        val preservedOptions =
                            lockedOptions.filterNot { option ->
                                obsoleteOptions.any { obsolete -> obsolete.id == option.id }
                            }
                        val missingProfiles =
                            HookahFlavorProfileService.missingBaseProfiles(
                                preservedOptions.map { it.name },
                            )

                        obsoleteOptions.forEach { option ->
                            deleteLockedOption(connection, venueId, itemId, option.id)
                        }

                        var nextSortOrder = preservedOptions.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
                        val createdOptions =
                            missingProfiles.map { profileName ->
                                val created =
                                    insertOption(
                                        connection = connection,
                                        venueId = venueId,
                                        itemId = itemId,
                                        name = profileName,
                                        priceDeltaMinor = 0,
                                        isAvailable = true,
                                        sortOrder = nextSortOrder,
                                    )
                                nextSortOrder += 1
                                created
                            }

                        obsoleteOptions.forEach { option ->
                            auditMenuOptionDelete(
                                connection = connection,
                                venueId = venueId,
                                itemId = itemId,
                                optionId = option.id,
                                actorUserId = actorUserId,
                                source = deleteSource,
                            )
                        }
                        createdOptions.forEach { option ->
                            auditMenuOptionCreate(
                                connection = connection,
                                venueId = venueId,
                                itemId = itemId,
                                optionId = option.id,
                                actorUserId = actorUserId,
                                source = createSource,
                            )
                        }
                        connection.commit()
                        HookahFlavorProfileNormalizationResult(
                            removedCount = obsoleteOptions.size,
                            addedCount = missingProfiles.size,
                        )
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

    private fun loadOptionItemId(
        connection: Connection,
        venueId: Long,
        optionId: Long,
    ): Long? =
        connection.prepareStatement(
            """
            SELECT item_id
            FROM menu_item_options
            WHERE id = ? AND venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, optionId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getLong("item_id") else null
            }
        }

    private fun lockItemForOptionMutation(
        connection: Connection,
        venueId: Long,
        itemId: Long,
    ): OptionItemMutationScope? {
        val itemReference =
            connection.prepareStatement(
                """
                SELECT category_id, item_type
                FROM menu_items
                WHERE id = ? AND venue_id = ?
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, itemId)
                statement.setLong(2, venueId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        OptionItemCategoryReference(
                            categoryId = rs.getLong("category_id"),
                            itemType = MenuSemanticType.nullableFromDb(rs.getString("item_type")),
                        )
                    } else {
                        null
                    }
                }
            } ?: return null
        return connection.prepareStatement(
            """
            SELECT name, category_type
            FROM menu_categories
            WHERE id = ? AND venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, itemReference.categoryId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    throw SQLException("Locked menu item category is missing", "40001")
                }
                OptionItemMutationScope(
                    categoryName = rs.getString("name"),
                    categoryType = MenuSemanticType.fromDb(rs.getString("category_type")),
                    itemType = itemReference.itemType,
                )
            }
        }
    }

    private fun loadItemOptions(
        connection: Connection,
        venueId: Long,
        itemId: Long,
    ): List<VenueMenuOption> =
        connection.prepareStatement(
            """
            SELECT id, venue_id, item_id, name, price_delta_minor, is_available, sort_order
            FROM menu_item_options
            WHERE venue_id = ? AND item_id = ?
            ORDER BY sort_order, id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, itemId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(mapOption(rs))
                }
            }
        }

    private fun loadItemOptionsForUpdate(
        connection: Connection,
        venueId: Long,
        itemId: Long,
    ): List<VenueMenuOption> =
        connection.prepareStatement(
            """
            SELECT id, venue_id, item_id, name, price_delta_minor, is_available, sort_order
            FROM menu_item_options
            WHERE venue_id = ? AND item_id = ?
            ORDER BY id
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, itemId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(mapOption(rs))
                }
            }
        }

    private fun ensureCanonicalProfileIsUnique(
        options: List<VenueMenuOption>,
        name: String,
        excludedOptionId: Long? = null,
    ) {
        if (!HookahFlavorProfileService.isCanonicalProfileValue(name)) {
            return
        }
        val requestedKey = HookahFlavorProfileService.normalizeFlavorNameKey(name)
        if (
            options.any { option ->
                option.id != excludedOptionId &&
                    HookahFlavorProfileService.normalizeFlavorNameKey(option.name) == requestedKey
            }
        ) {
            throw InvalidInputException(BASE_FLAVOR_PROFILE_ALREADY_EXISTS_MESSAGE)
        }
    }

    private data class OptionItemMutationScope(
        val categoryName: String,
        val categoryType: MenuSemanticType,
        val itemType: MenuSemanticType?,
    ) {
        fun isHookahMenuSection(): Boolean = HookahFlavorProfileService.isHookahMenuSection(categoryName, categoryType)

        fun isHookahFlavorProfileItem(): Boolean =
            itemType == MenuSemanticType.HOOKAH ||
                (
                    itemType == null &&
                        HookahFlavorProfileService.isHookahMenuSection(categoryName, categoryType)
                )
    }

    private data class OptionItemCategoryReference(
        val categoryId: Long,
        val itemType: MenuSemanticType?,
    )

    private fun insertOption(
        connection: Connection,
        venueId: Long,
        itemId: Long,
        name: String,
        priceDeltaMinor: Long,
        isAvailable: Boolean,
        sortOrder: Int,
    ): VenueMenuOption {
        val optionId =
            connection.prepareStatement(
                """
                INSERT INTO menu_item_options (
                    venue_id, item_id, name, price_delta_minor, is_available, sort_order, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, now())
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, itemId)
                statement.setString(3, name)
                statement.setLong(4, priceDeltaMinor)
                statement.setBoolean(5, isAvailable)
                statement.setInt(6, sortOrder)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else throw SQLException("Failed to insert menu option")
                }
            }
        return VenueMenuOption(
            id = optionId,
            venueId = venueId,
            itemId = itemId,
            name = name,
            priceDeltaMinor = priceDeltaMinor,
            isAvailable = isAvailable,
            sortOrder = sortOrder,
        )
    }

    private fun deleteLockedOption(
        connection: Connection,
        venueId: Long,
        itemId: Long,
        optionId: Long,
    ) {
        val deleted =
            connection.prepareStatement(
                """
                DELETE FROM menu_item_options
                WHERE id = ? AND venue_id = ? AND item_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, optionId)
                statement.setLong(2, venueId)
                statement.setLong(3, itemId)
                statement.executeUpdate()
            }
        if (deleted != 1) {
            throw SQLException("Locked menu option disappeared during deletion", "40001")
        }
    }

    private fun auditMenuOptionDelete(
        connection: Connection,
        venueId: Long,
        itemId: Long,
        optionId: Long,
        actorUserId: Long,
        source: MenuOptionDeleteSource,
    ) {
        auditLogWriter.appendJson(
            connection = connection,
            actorUserId = actorUserId,
            action = MENU_OPTION_DELETED_AUDIT_ACTION,
            entityType = "menu_item_option",
            entityId = optionId,
            payload =
                buildMenuOptionDeleteAuditPayload(
                    venueId = venueId,
                    itemId = itemId,
                    optionId = optionId,
                    source = source,
                ),
        )
    }

    private fun auditMenuOptionCreate(
        connection: Connection,
        venueId: Long,
        itemId: Long,
        optionId: Long,
        actorUserId: Long,
        source: MenuOptionCreateSource,
    ) {
        auditLogWriter.appendJson(
            connection = connection,
            actorUserId = actorUserId,
            action = MENU_OPTION_CREATED_AUDIT_ACTION,
            entityType = "menu_item_option",
            entityId = optionId,
            payload =
                buildMenuOptionCreateAuditPayload(
                    venueId = venueId,
                    itemId = itemId,
                    optionId = optionId,
                    source = source,
                ),
        )
    }

    private fun auditMenuOptionRename(
        connection: Connection,
        venueId: Long,
        itemId: Long,
        optionId: Long,
        oldName: String,
        newName: String,
        actorUserId: Long,
        source: MenuOptionRenameSource,
    ) {
        auditLogWriter.appendJson(
            connection = connection,
            actorUserId = actorUserId,
            action = MENU_OPTION_RENAMED_AUDIT_ACTION,
            entityType = "menu_item_option",
            entityId = optionId,
            payload =
                buildMenuOptionRenameAuditPayload(
                    venueId = venueId,
                    itemId = itemId,
                    optionId = optionId,
                    oldName = oldName,
                    newName = newName,
                    source = source,
                ),
        )
    }

    private fun auditMenuOptionPriceChange(
        connection: Connection,
        venueId: Long,
        itemId: Long,
        optionId: Long,
        oldPriceDeltaMinor: Long,
        newPriceDeltaMinor: Long,
        actorUserId: Long,
    ) {
        auditLogWriter.appendJson(
            connection = connection,
            actorUserId = actorUserId,
            action = MENU_OPTION_PRICE_CHANGED_AUDIT_ACTION,
            entityType = "menu_item_option",
            entityId = optionId,
            payload =
                buildMenuOptionPriceChangeAuditPayload(
                    venueId = venueId,
                    itemId = itemId,
                    optionId = optionId,
                    oldPriceDeltaMinor = oldPriceDeltaMinor,
                    newPriceDeltaMinor = newPriceDeltaMinor,
                ),
        )
    }

    private fun auditMenuOptionAvailabilityChange(
        connection: Connection,
        venueId: Long,
        itemId: Long,
        optionId: Long,
        oldIsAvailable: Boolean,
        newIsAvailable: Boolean,
        actorUserId: Long,
        source: String,
    ) {
        auditLogWriter.appendJson(
            connection = connection,
            actorUserId = actorUserId,
            action = MENU_OPTION_AVAILABILITY_CHANGED_AUDIT_ACTION,
            entityType = "menu_item_option",
            entityId = optionId,
            payload =
                buildMenuOptionAvailabilityChangeAuditPayload(
                    venueId = venueId,
                    itemId = itemId,
                    optionId = optionId,
                    oldIsAvailable = oldIsAvailable,
                    newIsAvailable = newIsAvailable,
                    source = source,
                ),
        )
    }

    private fun auditMenuItemAvailabilityChange(
        connection: Connection,
        venueId: Long,
        itemId: Long,
        oldIsAvailable: Boolean,
        newIsAvailable: Boolean,
        actorUserId: Long,
        source: MenuItemAvailabilitySource,
    ) {
        auditLogWriter.appendJson(
            connection = connection,
            actorUserId = actorUserId,
            action = MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION,
            entityType = "menu_item",
            entityId = itemId,
            payload =
                buildMenuItemAvailabilityChangeAuditPayload(
                    venueId = venueId,
                    itemId = itemId,
                    oldIsAvailable = oldIsAvailable,
                    newIsAvailable = newIsAvailable,
                    source = source,
                ),
        )
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

    private fun loadItemForUpdate(
        connection: Connection,
        itemId: Long,
        venueId: Long,
    ): VenueMenuItem? {
        return connection.prepareStatement(
            """
            SELECT id, venue_id, category_id, name, price_minor, currency, is_available, sort_order, item_type
            FROM menu_items
            WHERE id = ? AND venue_id = ?
            FOR UPDATE
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

internal fun buildMenuOptionDeleteAuditPayload(
    venueId: Long,
    itemId: Long,
    optionId: Long,
    source: MenuOptionDeleteSource,
) = buildJsonObject {
    put("venueId", venueId)
    put("itemId", itemId)
    put("optionId", optionId)
    put("source", source.name)
}

internal fun buildMenuOptionCreateAuditPayload(
    venueId: Long,
    itemId: Long,
    optionId: Long,
    source: MenuOptionCreateSource,
) = buildJsonObject {
    put("venueId", venueId)
    put("itemId", itemId)
    put("optionId", optionId)
    put("source", source.name)
}

internal fun buildMenuOptionRenameAuditPayload(
    venueId: Long,
    itemId: Long,
    optionId: Long,
    oldName: String,
    newName: String,
    source: MenuOptionRenameSource,
) = buildJsonObject {
    put("venueId", venueId)
    put("itemId", itemId)
    put("optionId", optionId)
    put("oldName", oldName)
    put("newName", newName)
    put("source", source.name)
}

internal fun buildMenuOptionPriceChangeAuditPayload(
    venueId: Long,
    itemId: Long,
    optionId: Long,
    oldPriceDeltaMinor: Long,
    newPriceDeltaMinor: Long,
) = buildJsonObject {
    put("venueId", venueId)
    put("itemId", itemId)
    put("optionId", optionId)
    put("oldPriceDeltaMinor", oldPriceDeltaMinor)
    put("newPriceDeltaMinor", newPriceDeltaMinor)
    put("source", MenuOptionRenameSource.VENUE_MINI_APP.name)
}

internal fun buildMenuOptionAvailabilityChangeAuditPayload(
    venueId: Long,
    itemId: Long,
    optionId: Long,
    oldIsAvailable: Boolean,
    newIsAvailable: Boolean,
    source: String,
) = buildJsonObject {
    put("venueId", venueId)
    put("itemId", itemId)
    put("optionId", optionId)
    put("oldIsAvailable", oldIsAvailable)
    put("newIsAvailable", newIsAvailable)
    put("source", source)
}

internal fun buildMenuItemAvailabilityChangeAuditPayload(
    venueId: Long,
    itemId: Long,
    oldIsAvailable: Boolean,
    newIsAvailable: Boolean,
    source: MenuItemAvailabilitySource,
) = buildJsonObject {
    put("venueId", venueId)
    put("itemId", itemId)
    put("oldIsAvailable", oldIsAvailable)
    put("newIsAvailable", newIsAvailable)
    put("source", source.name)
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
