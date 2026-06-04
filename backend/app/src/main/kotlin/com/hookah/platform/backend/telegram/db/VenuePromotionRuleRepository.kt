package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.venue.menu.MenuSemanticType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Time
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalTime
import java.util.Locale
import javax.sql.DataSource

enum class PromotionRuleType(val dbValue: String) {
    HAPPY_HOURS_PERCENT("HAPPY_HOURS_PERCENT"),
    GIFT_WITH_ITEM("GIFT_WITH_ITEM"),
    ;

    companion object {
        fun fromDb(value: String?): PromotionRuleType? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.dbValue == normalized }
        }
    }
}

enum class PromotionRuleTargetType(val dbValue: String) {
    CATEGORY_TYPE("CATEGORY_TYPE"),
    MENU_ITEM("MENU_ITEM"),
    ;

    companion object {
        fun fromDb(value: String?): PromotionRuleTargetType? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.dbValue == normalized }
        }
    }
}

enum class PromotionRewardMode(val dbValue: String) {
    FIXED_ITEM("FIXED_ITEM"),
    CHOICE_ITEMS("CHOICE_ITEMS"),
    ;

    companion object {
        fun fromDb(value: String?): PromotionRewardMode {
            val normalized = value?.trim()?.uppercase(Locale.ROOT)
            return entries.firstOrNull { it.dbValue == normalized } ?: FIXED_ITEM
        }
    }
}

data class VenuePromotionRule(
    val id: Long,
    val promotionId: Long?,
    val promotionTitle: String?,
    val venueId: Long,
    val ruleType: PromotionRuleType,
    val targetType: PromotionRuleTargetType,
    val targetValue: MenuSemanticType,
    val discountPercent: Int,
    val startsTime: LocalTime?,
    val endsTime: LocalTime?,
    val daysOfWeek: Set<Int>?,
    val status: VenuePromotionStatus,
    val priority: Int,
    val stackable: Boolean = false,
    val conflictGroup: String? = null,
    val maxApplicationsPerItem: Int = 1,
    val createdByUserId: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val targets: List<PromotionRuleTarget> = emptyList(),
    val reward: PromotionRuleReward? = null,
)

data class PromotionRuleTarget(
    val id: Long?,
    val ruleId: Long,
    val targetType: PromotionRuleTargetType,
    val semanticType: MenuSemanticType?,
    val menuItemId: Long?,
    val menuItemName: String? = null,
)

data class PromotionRuleTargetMenuItem(
    val id: Long,
    val name: String,
    val semanticType: MenuSemanticType,
)

data class PromotionRuleReward(
    val id: Long,
    val ruleId: Long,
    val rewardMenuItemId: Long,
    val rewardMenuItemName: String,
    val rewardMode: PromotionRewardMode = PromotionRewardMode.FIXED_ITEM,
    val rewardQty: Int,
    val maxRewardsPerBatch: Int,
    val priceMinor: Long,
    val currency: String,
    val isAvailable: Boolean,
    val options: List<PromotionRuleRewardOption> = emptyList(),
)

data class PromotionRuleRewardOption(
    val id: Long?,
    val rewardId: Long,
    val menuItemId: Long,
    val menuItemName: String,
    val priceMinor: Long,
    val currency: String,
    val isAvailable: Boolean,
)

class VenuePromotionRuleRepository(private val dataSource: DataSource?) {
    suspend fun createHappyHoursRule(
        venueId: Long,
        promotionId: Long?,
        targetValue: MenuSemanticType,
        discountPercent: Int,
        createdByUserId: Long,
        startsTime: LocalTime? = null,
        endsTime: LocalTime? = null,
        daysOfWeek: Set<Int>? = null,
        priority: Int = 100,
    ): VenuePromotionRule {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        validateDiscountPercent(discountPercent)
        validateTimeWindow(startsTime, endsTime)
        val normalizedDays = normalizeDaysOfWeek(daysOfWeek)
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        if (promotionId != null && !promotionBelongsToVenue(connection, venueId, promotionId)) {
                            throw SQLException("Promotion does not belong to venue")
                        }
	                        val id =
	                            connection.prepareStatement(
                                """
                                INSERT INTO promotion_rules (
                                    promotion_id,
                                    venue_id,
                                    rule_type,
                                    target_type,
                                    target_value,
                                    discount_percent,
                                    starts_time,
                                    ends_time,
                                    days_of_week,
                                    status,
                                    priority,
                                    created_by_user_id
                                )
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """.trimIndent(),
                                Statement.RETURN_GENERATED_KEYS,
                            ).use { statement ->
                                setNullableLong(statement, 1, promotionId)
                                statement.setLong(2, venueId)
                                statement.setString(3, PromotionRuleType.HAPPY_HOURS_PERCENT.dbValue)
                                statement.setString(4, PromotionRuleTargetType.CATEGORY_TYPE.dbValue)
                                statement.setString(5, targetValue.dbValue)
                                statement.setInt(6, discountPercent)
                                setNullableTime(statement, 7, startsTime)
                                setNullableTime(statement, 8, endsTime)
                                statement.setString(9, normalizedDays?.joinToString(","))
                                statement.setString(10, VenuePromotionStatus.ACTIVE.dbValue)
                                statement.setInt(11, priority)
                                statement.setLong(12, createdByUserId)
                                statement.executeUpdate()
                                statement.generatedKeys.use { keys ->
                                    if (!keys.next()) throw SQLException("No generated key for promotion rule")
	                                    keys.getLong(1)
	                                }
	                            }
	                        replaceRuleTargetsWithCategory(connection, venueId, id, targetValue)
	                        val created = selectRule(connection, venueId, id) ?: throw SQLException("Created promotion rule not found")
	                        connection.commit()
                        created
                    } catch (e: Exception) {
                        connection.rollback()
                        throw e
                    } finally {
                        connection.autoCommit = true
                    }
                }
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun createGiftWithItemRule(
        venueId: Long,
        promotionId: Long?,
        targetValue: MenuSemanticType,
        rewardMenuItemId: Long,
        createdByUserId: Long,
        rewardQty: Int = 1,
        maxRewardsPerBatch: Int = 1,
        startsTime: LocalTime? = null,
        endsTime: LocalTime? = null,
        daysOfWeek: Set<Int>? = null,
        priority: Int = 100,
    ): VenuePromotionRule {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        validateRewardConfig(rewardQty, maxRewardsPerBatch)
        validateTimeWindow(startsTime, endsTime)
        val normalizedDays = normalizeDaysOfWeek(daysOfWeek)
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        if (promotionId != null && !promotionBelongsToVenue(connection, venueId, promotionId)) {
                            throw SQLException("Promotion does not belong to venue")
                        }
                        require(loadRewardMenuItem(connection, venueId, rewardMenuItemId) != null) {
                            "reward menu item must belong to venue"
                        }
                        val id =
                            connection.prepareStatement(
                                """
                                INSERT INTO promotion_rules (
                                    promotion_id,
                                    venue_id,
                                    rule_type,
                                    target_type,
                                    target_value,
                                    discount_percent,
                                    starts_time,
                                    ends_time,
                                    days_of_week,
                                    status,
                                    priority,
                                    created_by_user_id
                                )
                                VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)
                                """.trimIndent(),
                                Statement.RETURN_GENERATED_KEYS,
                            ).use { statement ->
                                setNullableLong(statement, 1, promotionId)
                                statement.setLong(2, venueId)
                                statement.setString(3, PromotionRuleType.GIFT_WITH_ITEM.dbValue)
                                statement.setString(4, PromotionRuleTargetType.CATEGORY_TYPE.dbValue)
                                statement.setString(5, targetValue.dbValue)
                                setNullableTime(statement, 6, startsTime)
                                setNullableTime(statement, 7, endsTime)
                                statement.setString(8, normalizedDays?.joinToString(","))
                                statement.setString(9, VenuePromotionStatus.ACTIVE.dbValue)
                                statement.setInt(10, priority)
                                statement.setLong(11, createdByUserId)
                                statement.executeUpdate()
                                statement.generatedKeys.use { keys ->
                                    if (!keys.next()) throw SQLException("No generated key for promotion rule")
                                    keys.getLong(1)
                                }
                            }
                        replaceRuleTargetsWithCategory(connection, venueId, id, targetValue)
                        upsertRuleReward(
                            connection = connection,
                            ruleId = id,
                            rewardMenuItemId = rewardMenuItemId,
                            rewardQty = rewardQty,
                            maxRewardsPerBatch = maxRewardsPerBatch,
                            rewardMode = PromotionRewardMode.FIXED_ITEM,
                        )
                        val created = selectRule(connection, venueId, id) ?: throw SQLException("Created promotion rule not found")
                        connection.commit()
                        created
                    } catch (e: Exception) {
                        connection.rollback()
                        throw e
                    } finally {
                        connection.autoCommit = true
                    }
                }
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateGiftWithItemReward(
        venueId: Long,
        ruleId: Long,
        rewardMenuItemId: Long,
        rewardQty: Int = 1,
        maxRewardsPerBatch: Int = 1,
    ): VenuePromotionRule? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        validateRewardConfig(rewardQty, maxRewardsPerBatch)
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val result =
                            if (!ruleBelongsToVenue(connection, venueId, ruleId)) {
                                null
                            } else {
                                require(loadRewardMenuItem(connection, venueId, rewardMenuItemId) != null) {
                                    "reward menu item must belong to venue"
                                }
                                upsertRuleReward(
                                    connection = connection,
                                    ruleId = ruleId,
                                    rewardMenuItemId = rewardMenuItemId,
                                    rewardQty = rewardQty,
                                    maxRewardsPerBatch = maxRewardsPerBatch,
                                    rewardMode = PromotionRewardMode.FIXED_ITEM,
                                )
                                connection.prepareStatement(
                                    """
                                    UPDATE promotion_rules
                                    SET updated_at = CURRENT_TIMESTAMP
                                    WHERE venue_id = ? AND id = ?
                                    """.trimIndent(),
                                ).use { statement ->
                                    statement.setLong(1, venueId)
                                    statement.setLong(2, ruleId)
                                    statement.executeUpdate()
                                }
                                selectRule(connection, venueId, ruleId)
                            }
                        connection.commit()
                        result
                    } catch (e: Exception) {
                        connection.rollback()
                        throw e
                    } finally {
                        connection.autoCommit = true
                    }
                }
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateGiftWithItemRewardOptions(
        venueId: Long,
        ruleId: Long,
        rewardMenuItemIds: List<Long>,
        rewardQty: Int = 1,
        maxRewardsPerBatch: Int = 1,
    ): VenuePromotionRule? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        validateRewardConfig(rewardQty, maxRewardsPerBatch)
        val distinctRewardIds = rewardMenuItemIds.distinct()
        require(distinctRewardIds.size >= 2) { "choice reward requires at least two options" }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val result =
                            if (!ruleBelongsToVenue(connection, venueId, ruleId)) {
                                null
                            } else {
                                requireRewardMenuItemsBelongToVenue(connection, venueId, distinctRewardIds)
                                upsertRuleReward(
                                    connection = connection,
                                    ruleId = ruleId,
                                    rewardMenuItemId = distinctRewardIds.first(),
                                    rewardQty = rewardQty,
                                    maxRewardsPerBatch = maxRewardsPerBatch,
                                    rewardMode = PromotionRewardMode.CHOICE_ITEMS,
                                    rewardOptionMenuItemIds = distinctRewardIds,
                                )
                                connection.prepareStatement(
                                    """
                                    UPDATE promotion_rules
                                    SET updated_at = CURRENT_TIMESTAMP
                                    WHERE venue_id = ? AND id = ?
                                    """.trimIndent(),
                                ).use { statement ->
                                    statement.setLong(1, venueId)
                                    statement.setLong(2, ruleId)
                                    statement.executeUpdate()
                                }
                                selectRule(connection, venueId, ruleId)
                            }
                        connection.commit()
                        result
                    } catch (e: Exception) {
                        connection.rollback()
                        throw e
                    } finally {
                        connection.autoCommit = true
                    }
                }
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateHappyHoursRule(
        venueId: Long,
        ruleId: Long,
        targetValue: MenuSemanticType? = null,
        discountPercent: Int? = null,
        startsTime: LocalTime? = null,
        endsTime: LocalTime? = null,
        clearTimeWindow: Boolean = false,
        daysOfWeek: Set<Int>? = null,
        clearDaysOfWeek: Boolean = false,
        priority: Int? = null,
    ): VenuePromotionRule? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        discountPercent?.let { validateDiscountPercent(it) }
        if (!clearTimeWindow && (startsTime != null || endsTime != null)) {
            validateTimeWindow(startsTime, endsTime)
        }
        val normalizedDays = normalizeDaysOfWeek(daysOfWeek)
        val updates = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        targetValue?.let {
            updates += "target_value = ?"
            values += it.dbValue
        }
        discountPercent?.let {
            updates += "discount_percent = ?"
            values += it
        }
        if (clearTimeWindow) {
            updates += "starts_time = ?"
            values += null
            updates += "ends_time = ?"
            values += null
        } else if (startsTime != null || endsTime != null) {
            updates += "starts_time = ?"
            values += startsTime
            updates += "ends_time = ?"
            values += endsTime
        }
        if (clearDaysOfWeek) {
            updates += "days_of_week = ?"
            values += null
        } else if (daysOfWeek != null) {
            updates += "days_of_week = ?"
            values += normalizedDays?.joinToString(",")
        }
        priority?.let {
            updates += "priority = ?"
            values += it
        }
	        if (updates.isEmpty()) return getRuleForManagement(venueId, ruleId)
	        updates += "updated_at = CURRENT_TIMESTAMP"
	        return withContext(Dispatchers.IO) {
	            try {
	                ds.connection.use { connection ->
	                    connection.autoCommit = false
	                    try {
	                        val updated =
	                            connection.prepareStatement(
	                                """
	                                UPDATE promotion_rules
	                                SET ${updates.joinToString(", ")}
	                                WHERE venue_id = ? AND id = ?
	                                """.trimIndent(),
	                            ).use { statement ->
	                                values.forEachIndexed { index, value -> setStatementValue(statement, index + 1, value) }
	                                statement.setLong(values.size + 1, venueId)
	                                statement.setLong(values.size + 2, ruleId)
	                                statement.executeUpdate()
	                            }
	                        val result =
	                            if (updated == 0) {
	                                null
	                            } else {
	                                targetValue?.let { replaceRuleTargetsWithCategory(connection, venueId, ruleId, it) }
	                                selectRule(connection, venueId, ruleId)
	                            }
	                        connection.commit()
	                        result
	                    } catch (e: Exception) {
	                        connection.rollback()
	                        throw e
	                    } finally {
	                        connection.autoCommit = true
	                    }
	                }
	            } catch (e: IllegalArgumentException) {
	                throw e
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
	        }
	    }

	    suspend fun replaceRuleTargetsWithCategory(
	        venueId: Long,
	        ruleId: Long,
	        semanticType: MenuSemanticType,
	    ): VenuePromotionRule? {
	        val ds = dataSource ?: throw DatabaseUnavailableException()
	        return withContext(Dispatchers.IO) {
	            try {
	                ds.connection.use { connection ->
	                    connection.autoCommit = false
	                    try {
	                        val exists = ruleBelongsToVenue(connection, venueId, ruleId)
	                        val result =
	                            if (!exists) {
	                                null
	                            } else {
	                                connection.prepareStatement(
	                                    """
	                                    UPDATE promotion_rules
	                                    SET target_type = ?,
	                                        target_value = ?,
	                                        updated_at = CURRENT_TIMESTAMP
	                                    WHERE venue_id = ? AND id = ?
	                                    """.trimIndent(),
	                                ).use { statement ->
	                                    statement.setString(1, PromotionRuleTargetType.CATEGORY_TYPE.dbValue)
	                                    statement.setString(2, semanticType.dbValue)
	                                    statement.setLong(3, venueId)
	                                    statement.setLong(4, ruleId)
	                                    statement.executeUpdate()
	                                }
	                                replaceRuleTargetsWithCategory(connection, venueId, ruleId, semanticType)
	                                selectRule(connection, venueId, ruleId)
	                            }
	                        connection.commit()
	                        result
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

	    suspend fun replaceRuleTargetsWithMenuItems(
	        venueId: Long,
	        ruleId: Long,
	        menuItemIds: List<Long>,
	    ): VenuePromotionRule? {
	        val ds = dataSource ?: throw DatabaseUnavailableException()
	        val distinctItemIds = menuItemIds.distinct()
	        require(distinctItemIds.isNotEmpty()) { "menu_item_ids must not be empty" }
	        return withContext(Dispatchers.IO) {
	            try {
	                ds.connection.use { connection ->
	                    connection.autoCommit = false
	                    try {
	                        val exists = ruleBelongsToVenue(connection, venueId, ruleId)
	                        val result =
	                            if (!exists) {
	                                null
	                            } else {
	                                val items = loadTargetSelectionItems(connection, venueId, distinctItemIds)
	                                require(items.size == distinctItemIds.size) { "menu items must belong to venue" }
	                                val semanticTypes = items.map { it.semanticType }.toSet()
	                                require(semanticTypes.size == 1) { "menu item targets must share the same semantic type" }
	                                val semanticType = semanticTypes.single()
	                                connection.prepareStatement(
	                                    """
	                                    UPDATE promotion_rules
	                                    SET target_type = ?,
	                                        target_value = ?,
	                                        updated_at = CURRENT_TIMESTAMP
	                                    WHERE venue_id = ? AND id = ?
	                                    """.trimIndent(),
	                                ).use { statement ->
	                                    statement.setString(1, PromotionRuleTargetType.CATEGORY_TYPE.dbValue)
	                                    statement.setString(2, semanticType.dbValue)
	                                    statement.setLong(3, venueId)
	                                    statement.setLong(4, ruleId)
	                                    statement.executeUpdate()
	                                }
	                                deleteRuleTargets(connection, ruleId)
	                                distinctItemIds.forEach { menuItemId ->
	                                    insertMenuItemTarget(connection, ruleId, menuItemId)
	                                }
	                                selectRule(connection, venueId, ruleId)
	                            }
	                        connection.commit()
	                        result
	                    } catch (e: Exception) {
	                        connection.rollback()
	                        throw e
	                    } finally {
	                        connection.autoCommit = true
	                    }
	                }
	            } catch (e: IllegalArgumentException) {
	                throw e
	            } catch (e: SQLException) {
	                throw DatabaseUnavailableException()
	            }
	        }
	    }

	    suspend fun listRuleTargets(ruleId: Long): List<PromotionRuleTarget> {
	        val ds = dataSource ?: throw DatabaseUnavailableException()
	        return withContext(Dispatchers.IO) {
	            try {
	                ds.connection.use { connection -> loadTargetsForRuleIds(connection, listOf(ruleId))[ruleId].orEmpty() }
	            } catch (e: SQLException) {
	                throw DatabaseUnavailableException()
	            }
	        }
	    }

	    suspend fun listMenuItemsForTargetSelection(
	        venueId: Long,
	        semanticType: MenuSemanticType,
	    ): List<PromotionRuleTargetMenuItem> {
	        val ds = dataSource ?: throw DatabaseUnavailableException()
	        return withContext(Dispatchers.IO) {
	            try {
	                ds.connection.use { connection -> loadTargetSelectionItems(connection, venueId, semanticType) }
	            } catch (e: SQLException) {
	                throw DatabaseUnavailableException()
	            }
	        }
	    }

	    suspend fun setRuleStatus(
        venueId: Long,
        ruleId: Long,
        status: VenuePromotionStatus,
    ): VenuePromotionRule? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE promotion_rules
                            SET status = ?,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE venue_id = ? AND id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, status.dbValue)
                            statement.setLong(2, venueId)
                            statement.setLong(3, ruleId)
                            statement.executeUpdate()
                        }
                    if (updated == 0) null else selectRule(connection, venueId, ruleId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateRuleSchedule(
        venueId: Long,
        ruleId: Long,
        startsTime: LocalTime,
        endsTime: LocalTime,
        daysOfWeek: Set<Int>,
    ): VenuePromotionRule? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        validateSchedule(startsTime, endsTime, daysOfWeek)
        val normalizedDays = normalizeDaysOfWeek(daysOfWeek) ?: throw IllegalArgumentException("days_of_week is required")
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE promotion_rules
                            SET starts_time = ?,
                                ends_time = ?,
                                days_of_week = ?,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE venue_id = ? AND id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setTime(1, Time.valueOf(startsTime))
                            statement.setTime(2, Time.valueOf(endsTime))
                            statement.setString(3, normalizedDays.joinToString(","))
                            statement.setLong(4, venueId)
                            statement.setLong(5, ruleId)
                            statement.executeUpdate()
                        }
                    if (updated == 0) null else selectRule(connection, venueId, ruleId)
                }
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun clearRuleSchedule(
        venueId: Long,
        ruleId: Long,
    ): VenuePromotionRule? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE promotion_rules
                            SET starts_time = NULL,
                                ends_time = NULL,
                                days_of_week = NULL,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE venue_id = ? AND id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, venueId)
                            statement.setLong(2, ruleId)
                            statement.executeUpdate()
                        }
                    if (updated == 0) null else selectRule(connection, venueId, ruleId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateRuleCompatibility(
        venueId: Long,
        ruleId: Long,
        stackable: Boolean,
        conflictGroup: String? = null,
    ): VenuePromotionRule? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val normalizedConflictGroup = conflictGroup?.trim()?.takeIf { it.isNotBlank() }
        require(normalizedConflictGroup == null || normalizedConflictGroup.length <= 64) {
            "conflict_group must be at most 64 characters"
        }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE promotion_rules
                            SET stackable = ?,
                                conflict_group = ?,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE venue_id = ? AND id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setBoolean(1, stackable)
                            statement.setString(2, normalizedConflictGroup)
                            statement.setLong(3, venueId)
                            statement.setLong(4, ruleId)
                            statement.executeUpdate()
                        }
                    if (updated == 0) null else selectRule(connection, venueId, ruleId)
                }
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun archiveRule(
        venueId: Long,
        promotionId: Long,
        ruleId: Long,
    ): VenuePromotionRule? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE promotion_rules
                            SET status = ?,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE venue_id = ?
                              AND promotion_id = ?
                              AND id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, VenuePromotionStatus.ARCHIVED.dbValue)
                            statement.setLong(2, venueId)
                            statement.setLong(3, promotionId)
                            statement.setLong(4, ruleId)
                            statement.executeUpdate()
                        }
                    if (updated == 0) null else selectRule(connection, venueId, ruleId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findDuplicateHappyHoursRule(
        venueId: Long,
        promotionId: Long,
        targetValue: MenuSemanticType,
        targetMenuItemIds: List<Long> = emptyList(),
        discountPercent: Int,
        startsTime: LocalTime? = null,
        endsTime: LocalTime? = null,
        daysOfWeek: Set<Int>? = null,
        stackable: Boolean = false,
        conflictGroup: String? = null,
        maxApplicationsPerItem: Int = 1,
    ): VenuePromotionRule? {
        validateDiscountPercent(discountPercent)
        validateTimeWindow(startsTime, endsTime)
        val normalizedDays = normalizeDaysOfWeek(daysOfWeek)
        val targetSignature = buildTargetSignature(targetValue, targetMenuItemIds)
        val normalizedConflictGroup = normalizeConflictGroup(conflictGroup)
        return listRulesForPromotionManagement(venueId, promotionId).firstOrNull { rule ->
            rule.ruleType == PromotionRuleType.HAPPY_HOURS_PERCENT &&
                rule.discountPercent == discountPercent &&
                ruleMatchesRuleConfig(
                    rule = rule,
                    startsTime = startsTime,
                    endsTime = endsTime,
                    daysOfWeek = normalizedDays,
                    stackable = stackable,
                    conflictGroup = normalizedConflictGroup,
                    maxApplicationsPerItem = maxApplicationsPerItem,
                ) &&
                rule.targetSignature() == targetSignature
        }
    }

    suspend fun findDuplicateGiftWithItemRule(
        venueId: Long,
        promotionId: Long,
        targetValue: MenuSemanticType,
        targetMenuItemIds: List<Long> = emptyList(),
        rewardMode: PromotionRewardMode,
        rewardMenuItemId: Long? = null,
        rewardOptionMenuItemIds: List<Long> = emptyList(),
        rewardQty: Int = 1,
        maxRewardsPerBatch: Int = 1,
        startsTime: LocalTime? = null,
        endsTime: LocalTime? = null,
        daysOfWeek: Set<Int>? = null,
        stackable: Boolean = false,
        conflictGroup: String? = null,
        maxApplicationsPerItem: Int = 1,
    ): VenuePromotionRule? {
        validateRewardConfig(rewardQty, maxRewardsPerBatch)
        validateTimeWindow(startsTime, endsTime)
        val normalizedDays = normalizeDaysOfWeek(daysOfWeek)
        val targetSignature = buildTargetSignature(targetValue, targetMenuItemIds)
        val normalizedConflictGroup = normalizeConflictGroup(conflictGroup)
        val rewardOptionIds = rewardOptionMenuItemIds.distinct().sorted()
        return listRulesForPromotionManagement(venueId, promotionId).firstOrNull { rule ->
            rule.ruleType == PromotionRuleType.GIFT_WITH_ITEM &&
                ruleMatchesRuleConfig(
                    rule = rule,
                    startsTime = startsTime,
                    endsTime = endsTime,
                    daysOfWeek = normalizedDays,
                    stackable = stackable,
                    conflictGroup = normalizedConflictGroup,
                    maxApplicationsPerItem = maxApplicationsPerItem,
                ) &&
                rule.targetSignature() == targetSignature &&
                rule.rewardMatches(
                    rewardMode = rewardMode,
                    rewardMenuItemId = rewardMenuItemId,
                    rewardOptionMenuItemIds = rewardOptionIds,
                    rewardQty = rewardQty,
                    maxRewardsPerBatch = maxRewardsPerBatch,
                )
        }
    }

    suspend fun listRulesForVenueManagement(
        venueId: Long,
        limit: Int = 100,
    ): List<VenuePromotionRule> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${ruleColumns()}
                        FROM promotion_rules r
                        LEFT JOIN venue_promotions p ON p.id = r.promotion_id
                        WHERE r.venue_id = ?
                          AND r.status <> ?
                        ORDER BY r.priority ASC, r.updated_at DESC, r.id DESC
                        LIMIT ?
                        """.trimIndent(),
	                    ).use { statement ->
	                        statement.setLong(1, venueId)
	                        statement.setString(2, VenuePromotionStatus.ARCHIVED.dbValue)
	                        statement.setInt(3, limit.coerceIn(1, 200))
	                        statement.executeQuery().use { rs -> attachTargets(connection, rs.toRules()) }
	                    }
	                }
	            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listRulesForPromotionManagement(
        venueId: Long,
        promotionId: Long,
        limit: Int = 100,
    ): List<VenuePromotionRule> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${ruleColumns()}
                        FROM promotion_rules r
                        LEFT JOIN venue_promotions p ON p.id = r.promotion_id
                        WHERE r.venue_id = ?
                          AND r.promotion_id = ?
                          AND r.status <> ?
                        ORDER BY r.priority ASC, r.updated_at DESC, r.id DESC
                        LIMIT ?
                        """.trimIndent(),
	                    ).use { statement ->
	                        statement.setLong(1, venueId)
	                        statement.setLong(2, promotionId)
	                        statement.setString(3, VenuePromotionStatus.ARCHIVED.dbValue)
	                        statement.setInt(4, limit.coerceIn(1, 200))
	                        statement.executeQuery().use { rs -> attachTargets(connection, rs.toRules()) }
	                    }
	                }
	            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listActiveRulesForVenueAt(
        venueId: Long,
        now: Instant = Instant.now(),
        limit: Int = 100,
    ): List<VenuePromotionRule> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${ruleColumns()}
                        FROM promotion_rules r
                        JOIN venue_promotions p ON p.id = r.promotion_id
                        WHERE r.venue_id = ?
                          AND r.status = ?
                          AND p.status = ?
                          AND (p.starts_at IS NULL OR p.starts_at <= ?)
                          AND (p.ends_at IS NULL OR p.ends_at >= ?)
                        ORDER BY r.priority ASC, COALESCE(r.discount_percent, 0) DESC, r.id ASC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setString(2, VenuePromotionStatus.ACTIVE.dbValue)
                        statement.setString(3, VenuePromotionStatus.ACTIVE.dbValue)
                        statement.setTimestamp(4, Timestamp.from(now))
                        statement.setTimestamp(5, Timestamp.from(now))
                        statement.setInt(6, limit.coerceIn(1, 200))
                        statement.executeQuery().use { rs -> attachTargets(connection, rs.toRules()) }
                    }
                }
	            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    fun listActiveRulesForVenueAt(
        connection: Connection,
        venueId: Long,
        now: Instant = Instant.now(),
        limit: Int = 100,
    ): List<VenuePromotionRule> =
        connection.prepareStatement(
            """
            SELECT ${ruleColumns()}
            FROM promotion_rules r
            JOIN venue_promotions p ON p.id = r.promotion_id
            WHERE r.venue_id = ?
              AND r.status = ?
              AND p.status = ?
              AND (p.starts_at IS NULL OR p.starts_at <= ?)
              AND (p.ends_at IS NULL OR p.ends_at >= ?)
            ORDER BY r.priority ASC, COALESCE(r.discount_percent, 0) DESC, r.id ASC
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, VenuePromotionStatus.ACTIVE.dbValue)
            statement.setString(3, VenuePromotionStatus.ACTIVE.dbValue)
            statement.setTimestamp(4, Timestamp.from(now))
            statement.setTimestamp(5, Timestamp.from(now))
            statement.setInt(6, limit.coerceIn(1, 200))
            statement.executeQuery().use { rs -> attachTargets(connection, rs.toRules()) }
        }

    suspend fun getRuleForManagement(
        venueId: Long,
        ruleId: Long,
    ): VenuePromotionRule? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection -> selectRule(connection, venueId, ruleId) }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun selectRule(
        connection: Connection,
        venueId: Long,
        ruleId: Long,
    ): VenuePromotionRule? =
        connection.prepareStatement(
            """
            SELECT ${ruleColumns()}
            FROM promotion_rules r
            LEFT JOIN venue_promotions p ON p.id = r.promotion_id
            WHERE r.venue_id = ? AND r.id = ?
            """.trimIndent(),
	        ).use { statement ->
	            statement.setLong(1, venueId)
	            statement.setLong(2, ruleId)
	            statement.executeQuery().use { rs ->
	                if (rs.next()) {
	                    attachTargets(connection, listOfNotNull(rs.toRule())).firstOrNull()
	                } else {
	                    null
	                }
	            }
	        }

	    private fun attachTargets(
	        connection: Connection,
	        rules: List<VenuePromotionRule>,
	    ): List<VenuePromotionRule> {
	        if (rules.isEmpty()) return rules
	        val targetsByRuleId = loadTargetsForRuleIds(connection, rules.map { it.id })
	        val rewardsByRuleId = loadRewardsForRuleIds(connection, rules.map { it.id })
	        return rules.map { rule ->
	            val targets = targetsByRuleId[rule.id].orEmpty().ifEmpty { listOf(rule.legacyTarget()) }
	            rule.copy(targets = targets, reward = rewardsByRuleId[rule.id])
	        }
	    }

	    private fun VenuePromotionRule.legacyTarget(): PromotionRuleTarget =
	        PromotionRuleTarget(
	            id = null,
	            ruleId = id,
	            targetType = PromotionRuleTargetType.CATEGORY_TYPE,
	            semanticType = targetValue,
	            menuItemId = null,
	            menuItemName = null,
	        )

	    private fun loadTargetsForRuleIds(
	        connection: Connection,
	        ruleIds: List<Long>,
	    ): Map<Long, List<PromotionRuleTarget>> {
	        val ids = ruleIds.distinct()
	        if (ids.isEmpty()) return emptyMap()
	        val placeholders = ids.joinToString(",") { "?" }
	        return connection.prepareStatement(
	            """
	            SELECT
	                prt.id,
	                prt.rule_id,
	                prt.target_type,
	                prt.semantic_type,
	                prt.menu_item_id,
	                mi.name AS menu_item_name
	            FROM promotion_rule_targets prt
	            LEFT JOIN menu_items mi ON mi.id = prt.menu_item_id
	            WHERE prt.rule_id IN ($placeholders)
	            ORDER BY prt.id ASC
	            """.trimIndent(),
	        ).use { statement ->
	            ids.forEachIndexed { index, ruleId -> statement.setLong(index + 1, ruleId) }
	            statement.executeQuery().use { rs ->
	                buildMap<Long, MutableList<PromotionRuleTarget>> {
	                    while (rs.next()) {
	                        val ruleId = rs.getLong("rule_id")
	                        val targetType = PromotionRuleTargetType.fromDb(rs.getString("target_type")) ?: continue
	                        val menuItemId = rs.getLong("menu_item_id").let { if (rs.wasNull()) null else it }
	                        getOrPut(ruleId) { mutableListOf() }.add(
	                            PromotionRuleTarget(
	                                id = rs.getLong("id"),
	                                ruleId = ruleId,
	                                targetType = targetType,
	                                semanticType = MenuSemanticType.nullableFromDb(rs.getString("semantic_type")),
	                                menuItemId = menuItemId,
	                                menuItemName = rs.getString("menu_item_name"),
	                            ),
	                        )
	                    }
	                }
	            }
	        }
	    }

    private fun loadRewardsForRuleIds(
        connection: Connection,
        ruleIds: List<Long>,
    ): Map<Long, PromotionRuleReward> {
        val ids = ruleIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        val rewards =
            connection.prepareStatement(
            """
            SELECT
                prr.id,
                prr.rule_id,
                prr.reward_menu_item_id,
                prr.reward_mode,
                prr.reward_qty,
                prr.max_rewards_per_batch,
                COALESCE(mi.name, 'Позиция #' || prr.reward_menu_item_id) AS reward_menu_item_name,
                mi.price_minor,
                COALESCE(mi.currency, 'RUB') AS currency,
                COALESCE(mi.is_available, FALSE) AS is_available
            FROM promotion_rule_rewards prr
            LEFT JOIN menu_items mi ON mi.id = prr.reward_menu_item_id
            WHERE prr.rule_id IN ($placeholders)
            ORDER BY prr.id ASC
            """.trimIndent(),
            ).use { statement ->
                ids.forEachIndexed { index, ruleId -> statement.setLong(index + 1, ruleId) }
                statement.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            val ruleId = rs.getLong("rule_id")
                            if (!containsKey(ruleId)) {
                                put(
                                    ruleId,
                                    PromotionRuleReward(
                                        id = rs.getLong("id"),
                                        ruleId = ruleId,
                                        rewardMenuItemId = rs.getLong("reward_menu_item_id"),
                                        rewardMenuItemName = rs.getString("reward_menu_item_name"),
                                        rewardMode = PromotionRewardMode.fromDb(rs.getString("reward_mode")),
                                        rewardQty = rs.getInt("reward_qty"),
                                        maxRewardsPerBatch = rs.getInt("max_rewards_per_batch"),
                                        priceMinor = rs.getLong("price_minor").let { value -> if (rs.wasNull()) 0L else value },
                                        currency = rs.getString("currency")?.takeIf { it.isNotBlank() } ?: "RUB",
                                        isAvailable = rs.getBoolean("is_available"),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        if (rewards.isEmpty()) return emptyMap()
        val optionsByRewardId = loadRewardOptionsForRewardIds(connection, rewards.values.map { it.id })
        return rewards.mapValues { (_, reward) ->
            reward.copy(options = optionsByRewardId[reward.id].orEmpty())
        }
    }

    private fun upsertRuleReward(
        connection: Connection,
        ruleId: Long,
        rewardMenuItemId: Long,
        rewardQty: Int,
        maxRewardsPerBatch: Int,
        rewardMode: PromotionRewardMode,
        rewardOptionMenuItemIds: List<Long> = emptyList(),
    ) {
        connection.prepareStatement("DELETE FROM promotion_rule_rewards WHERE rule_id = ?").use { statement ->
            statement.setLong(1, ruleId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO promotion_rule_rewards (
                rule_id,
                reward_menu_item_id,
                reward_mode,
                reward_qty,
                max_rewards_per_batch
            )
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, ruleId)
            statement.setLong(2, rewardMenuItemId)
            statement.setString(3, rewardMode.dbValue)
            statement.setInt(4, rewardQty)
            statement.setInt(5, maxRewardsPerBatch)
            statement.executeUpdate()
            val rewardId =
                statement.generatedKeys.use { keys ->
                    if (!keys.next()) throw SQLException("No generated key for promotion rule reward")
                    keys.getLong(1)
                }
            if (rewardMode == PromotionRewardMode.CHOICE_ITEMS) {
                insertRewardOptions(connection, rewardId, rewardOptionMenuItemIds)
            }
        }
    }

    private fun loadRewardOptionsForRewardIds(
        connection: Connection,
        rewardIds: List<Long>,
    ): Map<Long, List<PromotionRuleRewardOption>> {
        val ids = rewardIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            SELECT
                pro.id,
                pro.reward_id,
                pro.menu_item_id,
                COALESCE(mi.name, 'Позиция #' || pro.menu_item_id) AS menu_item_name,
                mi.price_minor,
                COALESCE(mi.currency, 'RUB') AS currency,
                COALESCE(mi.is_available, FALSE) AS is_available
            FROM promotion_rule_reward_options pro
            LEFT JOIN menu_items mi ON mi.id = pro.menu_item_id
            WHERE pro.reward_id IN ($placeholders)
            ORDER BY pro.id ASC
            """.trimIndent(),
        ).use { statement ->
            ids.forEachIndexed { index, rewardId -> statement.setLong(index + 1, rewardId) }
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            PromotionRuleRewardOption(
                                id = rs.getLong("id"),
                                rewardId = rs.getLong("reward_id"),
                                menuItemId = rs.getLong("menu_item_id"),
                                menuItemName = rs.getString("menu_item_name"),
                                priceMinor = rs.getLong("price_minor").let { value -> if (rs.wasNull()) 0L else value },
                                currency = rs.getString("currency")?.takeIf { it.isNotBlank() } ?: "RUB",
                                isAvailable = rs.getBoolean("is_available"),
                            ),
                        )
                    }
                }.groupBy { it.rewardId }
            }
        }
    }

    private fun insertRewardOptions(
        connection: Connection,
        rewardId: Long,
        rewardMenuItemIds: List<Long>,
    ) {
        rewardMenuItemIds.distinct().forEach { menuItemId ->
            connection.prepareStatement(
                """
                INSERT INTO promotion_rule_reward_options (
                    reward_id,
                    menu_item_id
                )
                VALUES (?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, rewardId)
                statement.setLong(2, menuItemId)
                statement.executeUpdate()
            }
        }
    }

    private fun loadRewardMenuItem(
        connection: Connection,
        venueId: Long,
        rewardMenuItemId: Long,
    ): PromotionRuleTargetMenuItem? =
        connection.prepareStatement(
            """
            SELECT
                mi.id,
                mi.name,
                COALESCE(mi.item_type, mc.category_type) AS effective_type
            FROM menu_items mi
            JOIN menu_categories mc ON mc.id = mi.category_id AND mc.venue_id = mi.venue_id
            WHERE mi.venue_id = ?
              AND mi.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, rewardMenuItemId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    PromotionRuleTargetMenuItem(
                        id = rs.getLong("id"),
                        name = rs.getString("name")?.takeIf { it.isNotBlank() } ?: "Позиция #${rs.getLong("id")}",
                        semanticType = MenuSemanticType.fromDb(rs.getString("effective_type")),
                    )
                }
            }
        }

    private fun requireRewardMenuItemsBelongToVenue(
        connection: Connection,
        venueId: Long,
        rewardMenuItemIds: List<Long>,
    ) {
        val ids = rewardMenuItemIds.distinct()
        require(ids.isNotEmpty()) { "reward menu items are required" }
        val placeholders = ids.joinToString(",") { "?" }
        val count =
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM menu_items
                WHERE venue_id = ?
                  AND id IN ($placeholders)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                ids.forEachIndexed { index, itemId -> statement.setLong(index + 2, itemId) }
                statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        require(count == ids.size) { "reward menu items must belong to venue" }
    }

	    private fun replaceRuleTargetsWithCategory(
	        connection: Connection,
	        venueId: Long,
	        ruleId: Long,
	        semanticType: MenuSemanticType,
	    ) {
	        require(ruleBelongsToVenue(connection, venueId, ruleId)) { "rule must belong to venue" }
	        deleteRuleTargets(connection, ruleId)
	        connection.prepareStatement(
	            """
	            INSERT INTO promotion_rule_targets (rule_id, target_type, semantic_type, menu_item_id)
	            VALUES (?, ?, ?, NULL)
	            """.trimIndent(),
	        ).use { statement ->
	            statement.setLong(1, ruleId)
	            statement.setString(2, PromotionRuleTargetType.CATEGORY_TYPE.dbValue)
	            statement.setString(3, semanticType.dbValue)
	            statement.executeUpdate()
	        }
	    }

	    private fun deleteRuleTargets(
	        connection: Connection,
	        ruleId: Long,
	    ) {
	        connection.prepareStatement("DELETE FROM promotion_rule_targets WHERE rule_id = ?").use { statement ->
	            statement.setLong(1, ruleId)
	            statement.executeUpdate()
	        }
	    }

	    private fun insertMenuItemTarget(
	        connection: Connection,
	        ruleId: Long,
	        menuItemId: Long,
	    ) {
	        connection.prepareStatement(
	            """
	            INSERT INTO promotion_rule_targets (rule_id, target_type, semantic_type, menu_item_id)
	            VALUES (?, ?, NULL, ?)
	            """.trimIndent(),
	        ).use { statement ->
	            statement.setLong(1, ruleId)
	            statement.setString(2, PromotionRuleTargetType.MENU_ITEM.dbValue)
	            statement.setLong(3, menuItemId)
	            statement.executeUpdate()
	        }
	    }

	    private fun loadTargetSelectionItems(
	        connection: Connection,
	        venueId: Long,
	        semanticType: MenuSemanticType,
	    ): List<PromotionRuleTargetMenuItem> =
	        connection.prepareStatement(
	            """
	            SELECT
	                mi.id,
	                mi.name,
	                COALESCE(mi.item_type, mc.category_type) AS effective_type,
	                mc.sort_order AS category_sort_order,
	                mi.sort_order AS item_sort_order
	            FROM menu_items mi
	            JOIN menu_categories mc ON mc.id = mi.category_id AND mc.venue_id = mi.venue_id
	            WHERE mi.venue_id = ?
	              AND COALESCE(mi.item_type, mc.category_type) = ?
	            ORDER BY mc.sort_order, mi.sort_order, mi.id
	            """.trimIndent(),
	        ).use { statement ->
	            statement.setLong(1, venueId)
	            statement.setString(2, semanticType.dbValue)
	            statement.executeQuery().use { rs -> rs.toTargetSelectionItems() }
	        }

	    private fun loadTargetSelectionItems(
	        connection: Connection,
	        venueId: Long,
	        menuItemIds: List<Long>,
	    ): List<PromotionRuleTargetMenuItem> {
	        if (menuItemIds.isEmpty()) return emptyList()
	        val placeholders = menuItemIds.joinToString(",") { "?" }
	        return connection.prepareStatement(
	            """
	            SELECT
	                mi.id,
	                mi.name,
	                COALESCE(mi.item_type, mc.category_type) AS effective_type,
	                mc.sort_order AS category_sort_order,
	                mi.sort_order AS item_sort_order
	            FROM menu_items mi
	            JOIN menu_categories mc ON mc.id = mi.category_id AND mc.venue_id = mi.venue_id
	            WHERE mi.venue_id = ?
	              AND mi.id IN ($placeholders)
	            ORDER BY mc.sort_order, mi.sort_order, mi.id
	            """.trimIndent(),
	        ).use { statement ->
	            statement.setLong(1, venueId)
	            menuItemIds.forEachIndexed { index, itemId -> statement.setLong(index + 2, itemId) }
	            statement.executeQuery().use { rs -> rs.toTargetSelectionItems() }
	        }
	    }

	    private fun ResultSet.toTargetSelectionItems(): List<PromotionRuleTargetMenuItem> {
	        val items = mutableListOf<PromotionRuleTargetMenuItem>()
	        while (next()) {
	            items.add(
	                PromotionRuleTargetMenuItem(
	                    id = getLong("id"),
	                    name = getString("name")?.takeIf { it.isNotBlank() } ?: "Позиция #${getLong("id")}",
	                    semanticType = MenuSemanticType.fromDb(getString("effective_type")),
	                ),
	            )
	        }
	        return items
	    }

	    private fun ruleBelongsToVenue(
	        connection: Connection,
	        venueId: Long,
	        ruleId: Long,
	    ): Boolean =
	        connection.prepareStatement(
	            """
	            SELECT 1
	            FROM promotion_rules
	            WHERE venue_id = ? AND id = ?
	            """.trimIndent(),
	        ).use { statement ->
	            statement.setLong(1, venueId)
	            statement.setLong(2, ruleId)
	            statement.executeQuery().use { rs -> rs.next() }
	        }

	    private fun promotionBelongsToVenue(
        connection: Connection,
        venueId: Long,
        promotionId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT 1
            FROM venue_promotions
            WHERE venue_id = ? AND id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, promotionId)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun ruleColumns(): String =
        """
        r.id,
        r.promotion_id,
        p.title AS promotion_title,
        r.venue_id,
        r.rule_type,
        r.target_type,
        r.target_value,
        r.discount_percent,
        r.starts_time,
        r.ends_time,
        r.days_of_week,
        r.status,
        r.priority,
        r.stackable,
        r.conflict_group,
        r.max_applications_per_item,
        r.created_by_user_id,
        r.created_at,
        r.updated_at
        """.trimIndent()

    private fun ResultSet.toRules(): List<VenuePromotionRule> {
        val rules = mutableListOf<VenuePromotionRule>()
        while (next()) {
            toRule()?.let { rules.add(it) }
        }
        return rules
    }

    private fun ResultSet.toRule(): VenuePromotionRule? {
        val ruleType = PromotionRuleType.fromDb(getString("rule_type")) ?: return null
        val targetType = PromotionRuleTargetType.fromDb(getString("target_type")) ?: return null
        val status = VenuePromotionStatus.fromDb(getString("status")) ?: return null
        val promotionIdValue = getLong("promotion_id")
        val promotionId = if (wasNull()) null else promotionIdValue
        return VenuePromotionRule(
            id = getLong("id"),
            promotionId = promotionId,
            promotionTitle = getString("promotion_title"),
            venueId = getLong("venue_id"),
            ruleType = ruleType,
            targetType = targetType,
            targetValue = MenuSemanticType.fromDb(getString("target_value")),
            discountPercent = getInt("discount_percent"),
            startsTime = getTime("starts_time")?.toLocalTime(),
            endsTime = getTime("ends_time")?.toLocalTime(),
            daysOfWeek = parseDaysOfWeek(getString("days_of_week")),
            status = status,
            priority = getInt("priority"),
            stackable = getBoolean("stackable"),
            conflictGroup = getString("conflict_group")?.trim()?.takeIf { it.isNotBlank() },
            maxApplicationsPerItem = getInt("max_applications_per_item").coerceAtLeast(1),
            createdByUserId = getLong("created_by_user_id"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )
    }

    private fun buildTargetSignature(
        targetValue: MenuSemanticType,
        targetMenuItemIds: List<Long>,
    ): List<String> {
        val itemIds = targetMenuItemIds.distinct().sorted()
        return if (itemIds.isEmpty()) {
            listOf("${PromotionRuleTargetType.CATEGORY_TYPE.dbValue}:${targetValue.dbValue}")
        } else {
            itemIds.map { "${PromotionRuleTargetType.MENU_ITEM.dbValue}:$it" }
        }
    }

    private fun VenuePromotionRule.targetSignature(): List<String> =
        targets
            .mapNotNull { target ->
                when (target.targetType) {
                    PromotionRuleTargetType.CATEGORY_TYPE ->
                        target.semanticType?.let { "${PromotionRuleTargetType.CATEGORY_TYPE.dbValue}:${it.dbValue}" }
                    PromotionRuleTargetType.MENU_ITEM ->
                        target.menuItemId?.let { "${PromotionRuleTargetType.MENU_ITEM.dbValue}:$it" }
                }
            }
            .sorted()

    private fun ruleMatchesRuleConfig(
        rule: VenuePromotionRule,
        startsTime: LocalTime?,
        endsTime: LocalTime?,
        daysOfWeek: Set<Int>?,
        stackable: Boolean,
        conflictGroup: String?,
        maxApplicationsPerItem: Int,
    ): Boolean =
        rule.startsTime == startsTime &&
            rule.endsTime == endsTime &&
            rule.daysOfWeek == daysOfWeek &&
            rule.stackable == stackable &&
            rule.conflictGroup == conflictGroup &&
            rule.maxApplicationsPerItem == maxApplicationsPerItem.coerceAtLeast(1)

    private fun VenuePromotionRule.rewardMatches(
        rewardMode: PromotionRewardMode,
        rewardMenuItemId: Long?,
        rewardOptionMenuItemIds: List<Long>,
        rewardQty: Int,
        maxRewardsPerBatch: Int,
    ): Boolean {
        val rewardConfig = reward ?: return false
        if (rewardConfig.rewardMode != rewardMode) return false
        if (rewardConfig.rewardQty != rewardQty) return false
        if (rewardConfig.maxRewardsPerBatch != maxRewardsPerBatch) return false
        return when (rewardMode) {
            PromotionRewardMode.FIXED_ITEM ->
                rewardMenuItemId != null && rewardConfig.rewardMenuItemId == rewardMenuItemId
            PromotionRewardMode.CHOICE_ITEMS ->
                rewardConfig.options.map { it.menuItemId }.distinct().sorted() == rewardOptionMenuItemIds
        }
    }

    private fun validateDiscountPercent(percent: Int) {
        require(percent in 1..100) { "discount_percent must be between 1 and 100" }
    }

    private fun validateRewardConfig(
        rewardQty: Int,
        maxRewardsPerBatch: Int,
    ) {
        require(rewardQty >= 1) { "reward_qty must be positive" }
        require(maxRewardsPerBatch >= 1) { "max_rewards_per_batch must be positive" }
    }

    private fun validateTimeWindow(
        startsTime: LocalTime?,
        endsTime: LocalTime?,
    ) {
        require((startsTime == null && endsTime == null) || (startsTime != null && endsTime != null)) {
            "starts_time and ends_time must be set together"
        }
        if (startsTime != null && endsTime != null) {
            require(startsTime < endsTime) { "overnight promotion rule time windows are not supported yet" }
        }
    }

    private fun validateSchedule(
        startsTime: LocalTime,
        endsTime: LocalTime,
        daysOfWeek: Set<Int>,
    ) {
        require(daysOfWeek.isNotEmpty()) { "days_of_week is required" }
        validateTimeWindow(startsTime, endsTime)
    }

    private fun normalizeDaysOfWeek(daysOfWeek: Set<Int>?): Set<Int>? =
        daysOfWeek
            ?.takeIf { it.isNotEmpty() }
            ?.also { days -> require(days.all { it in 1..7 }) { "days_of_week must contain values 1..7" } }
            ?.toSortedSet()

    private fun normalizeConflictGroup(conflictGroup: String?): String? =
        conflictGroup
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.also { require(it.length <= 64) { "conflict_group must be at most 64 characters" } }

    private fun parseDaysOfWeek(value: String?): Set<Int>? =
        value
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it in 1..7 }
            ?.toSortedSet()
            ?.takeIf { it.isNotEmpty() }

    private fun setNullableLong(
        statement: java.sql.PreparedStatement,
        index: Int,
        value: Long?,
    ) {
        if (value == null) {
            statement.setObject(index, null)
        } else {
            statement.setLong(index, value)
        }
    }

    private fun setNullableTime(
        statement: java.sql.PreparedStatement,
        index: Int,
        value: LocalTime?,
    ) {
        if (value == null) {
            statement.setTime(index, null)
        } else {
            statement.setTime(index, Time.valueOf(value))
        }
    }

    private fun setStatementValue(
        statement: java.sql.PreparedStatement,
        index: Int,
        value: Any?,
    ) {
        when (value) {
            null -> statement.setObject(index, null)
            is String -> statement.setString(index, value)
            is Int -> statement.setInt(index, value)
            is LocalTime -> statement.setTime(index, Time.valueOf(value))
            is Instant -> statement.setTimestamp(index, Timestamp.from(value))
            else -> statement.setObject(index, value)
        }
    }
}
