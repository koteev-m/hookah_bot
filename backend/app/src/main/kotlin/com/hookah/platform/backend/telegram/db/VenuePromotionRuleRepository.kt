package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.InvalidInputException
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
import java.time.ZoneId
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
    MENU_CATEGORY("MENU_CATEGORY"),
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
    val version: Int = 1,
    val weekdayWindows: List<PromotionWeekdayWindow> = emptyList(),
    val executableTargetType: PromotionRuleTargetType? = null,
    val promotionStartsAt: Instant? = null,
    val promotionEndsAt: Instant? = null,
)

data class PromotionRuleTarget(
    val id: Long?,
    val ruleId: Long,
    val targetType: PromotionRuleTargetType,
    val semanticType: MenuSemanticType?,
    val menuItemId: Long?,
    val menuItemName: String? = null,
    val menuCategoryId: Long? = null,
    val menuCategoryName: String? = null,
)

data class PromotionWeekdayWindow(
    val weekday: Int,
    val startsMinute: Int,
    val endsMinute: Int,
)

data class HappyHoursRuleTargetInput(
    val targetType: PromotionRuleTargetType,
    val menuItemId: Long? = null,
    val menuCategoryId: Long? = null,
)

data class HappyHoursActivationReadiness(
    val isReady: Boolean,
    val errors: List<String>,
    val rule: VenuePromotionRule?,
    val venueTimezone: String,
    val ruleCount: Int = if (rule == null) 0 else 1,
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
    suspend fun createHappyHoursDraftRule(
        venueId: Long,
        promotionId: Long,
        target: HappyHoursRuleTargetInput,
        discountPercent: Int,
        weekdayWindows: List<PromotionWeekdayWindow>,
        createdByUserId: Long,
        priority: Int = 100,
    ): VenuePromotionRule {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val created =
                            createHappyHoursDraftRule(
                                connection = connection,
                                venueId = venueId,
                                promotionId = promotionId,
                                target = target,
                                discountPercent = discountPercent,
                                weekdayWindows = weekdayWindows,
                                createdByUserId = createdByUserId,
                                priority = priority,
                            )
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

    fun createHappyHoursDraftRule(
        connection: Connection,
        venueId: Long,
        promotionId: Long,
        target: HappyHoursRuleTargetInput,
        discountPercent: Int,
        weekdayWindows: List<PromotionWeekdayWindow>,
        createdByUserId: Long,
        priority: Int = 100,
    ): VenuePromotionRule {
        validateDiscountPercent(discountPercent)
        val normalizedWindows = normalizeWeekdayWindows(weekdayWindows)
        validateHappyHoursTargetInput(target)
        val promotion =
            selectPromotionRuleContext(
                connection = connection,
                venueId = venueId,
                promotionId = promotionId,
                forUpdate = true,
            ) ?: throw IllegalArgumentException("promotion must belong to venue")
        requireHappyHoursPromotionCanBeConfigured(promotion)
        require(!hasNonArchivedHappyHoursRule(connection, venueId, promotionId)) {
            "promotion already has a Happy Hours rule"
        }
        val resolvedTarget = resolveHappyHoursTarget(connection, venueId, target)
        val legacySchedule = normalizedWindows.toLegacyScheduleProjection()
        val ruleId =
            connection.prepareStatement(
                """
                INSERT INTO promotion_rules (
                    promotion_id,
                    venue_id,
                    rule_type,
                    target_type,
                    target_value,
                    executable_target_type,
                    discount_percent,
                    starts_time,
                    ends_time,
                    days_of_week,
                    status,
                    priority,
                    stackable,
                    conflict_group,
                    max_applications_per_item,
                    version,
                    created_by_user_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, NULL, 1, 1, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, promotionId)
                statement.setLong(2, venueId)
                statement.setString(3, PromotionRuleType.HAPPY_HOURS_PERCENT.dbValue)
                statement.setString(4, PromotionRuleTargetType.CATEGORY_TYPE.dbValue)
                statement.setString(5, resolvedTarget.semanticType.dbValue)
                statement.setString(6, resolvedTarget.input.targetType.dbValue)
                statement.setInt(7, discountPercent)
                setNullableTime(statement, 8, legacySchedule?.startsTime)
                setNullableTime(statement, 9, legacySchedule?.endsTime)
                statement.setString(10, legacySchedule?.daysOfWeek?.joinToString(","))
                statement.setString(11, VenuePromotionStatus.DRAFT.dbValue)
                statement.setInt(12, priority)
                statement.setLong(13, createdByUserId)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    if (!keys.next()) throw SQLException("No generated key for promotion rule")
                    keys.getLong(1)
                }
            }
        replaceExecutableHappyHoursTarget(connection, ruleId, resolvedTarget)
        replaceWeekdayWindows(connection, ruleId, normalizedWindows)
        return selectRule(connection, venueId, ruleId)
            ?: throw SQLException("Created Happy Hours rule not found")
    }

    suspend fun updateHappyHoursDraftRule(
        venueId: Long,
        promotionId: Long,
        ruleId: Long,
        target: HappyHoursRuleTargetInput,
        discountPercent: Int,
        weekdayWindows: List<PromotionWeekdayWindow>,
    ): VenuePromotionRule? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val result =
                            updateHappyHoursDraftRule(
                                connection = connection,
                                venueId = venueId,
                                promotionId = promotionId,
                                ruleId = ruleId,
                                target = target,
                                discountPercent = discountPercent,
                                weekdayWindows = weekdayWindows,
                            )
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

    fun updateHappyHoursDraftRule(
        connection: Connection,
        venueId: Long,
        promotionId: Long,
        ruleId: Long,
        target: HappyHoursRuleTargetInput,
        discountPercent: Int,
        weekdayWindows: List<PromotionWeekdayWindow>,
    ): VenuePromotionRule? {
        validateDiscountPercent(discountPercent)
        val normalizedWindows = normalizeWeekdayWindows(weekdayWindows)
        validateHappyHoursTargetInput(target)
        val promotion =
            selectPromotionRuleContext(
                connection = connection,
                venueId = venueId,
                promotionId = promotionId,
                forUpdate = true,
            ) ?: return null
        requireHappyHoursPromotionCanBeConfigured(promotion)
        val current =
            selectHappyHoursRuleForUpdate(
                connection = connection,
                venueId = venueId,
                promotionId = promotionId,
                ruleId = ruleId,
            ) ?: return null
        require(current.status != VenuePromotionStatus.ARCHIVED) {
            "archived Happy Hours rule cannot be edited"
        }
        val resolvedTarget = resolveHappyHoursTarget(connection, venueId, target)
        val legacySchedule = normalizedWindows.toLegacyScheduleProjection()
        val updated =
            connection.prepareStatement(
                """
                UPDATE promotion_rules
                SET target_type = ?,
                    target_value = ?,
                    executable_target_type = ?,
                    discount_percent = ?,
                    starts_time = ?,
                    ends_time = ?,
                    days_of_week = ?,
                    stackable = FALSE,
                    conflict_group = NULL,
                    max_applications_per_item = 1,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE venue_id = ?
                  AND promotion_id = ?
                  AND id = ?
                  AND rule_type = ?
                  AND status <> ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, PromotionRuleTargetType.CATEGORY_TYPE.dbValue)
                statement.setString(2, resolvedTarget.semanticType.dbValue)
                statement.setString(3, resolvedTarget.input.targetType.dbValue)
                statement.setInt(4, discountPercent)
                setNullableTime(statement, 5, legacySchedule?.startsTime)
                setNullableTime(statement, 6, legacySchedule?.endsTime)
                statement.setString(7, legacySchedule?.daysOfWeek?.joinToString(","))
                statement.setLong(8, venueId)
                statement.setLong(9, promotionId)
                statement.setLong(10, ruleId)
                statement.setString(11, PromotionRuleType.HAPPY_HOURS_PERCENT.dbValue)
                statement.setString(12, VenuePromotionStatus.ARCHIVED.dbValue)
                statement.executeUpdate()
            }
        if (updated != 1) {
            return null
        }
        replaceExecutableHappyHoursTarget(connection, ruleId, resolvedTarget)
        replaceWeekdayWindows(connection, ruleId, normalizedWindows)
        return selectRule(connection, venueId, ruleId)
    }

    suspend fun validateHappyHoursActivationReadiness(
        venueId: Long,
        promotionId: Long,
    ): HappyHoursActivationReadiness {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    validateHappyHoursActivationReadiness(connection, venueId, promotionId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    fun validateHappyHoursActivationReadiness(
        connection: Connection,
        venueId: Long,
        promotionId: Long,
    ): HappyHoursActivationReadiness =
        validateHappyHoursActivationReadinessOnConnection(connection, venueId, promotionId)

    fun listHappyHoursRulesForPromotionManagement(
        connection: Connection,
        venueId: Long,
        promotionId: Long,
    ): List<VenuePromotionRule> = loadNonArchivedHappyHoursRules(connection, venueId, promotionId)

    fun synchronizeHappyHoursPromotionStatus(
        connection: Connection,
        venueId: Long,
        promotionId: Long,
        status: VenuePromotionStatus,
    ) {
        check(!connection.autoCommit) {
            "Happy Hours status synchronization must run inside the caller transaction"
        }
        if (
            selectPromotionRuleContext(
                connection = connection,
                venueId = venueId,
                promotionId = promotionId,
                forUpdate = true,
            ) == null
        ) {
            throw InvalidInputException("Акция не найдена.")
        }
        lockPromotionRulesForMutation(connection, venueId, promotionId)
        val rules =
            when (status) {
                VenuePromotionStatus.ACTIVE -> {
                    val readiness =
                        validateHappyHoursActivationReadiness(connection, venueId, promotionId)
                    if (!readiness.isReady) {
                        throw InvalidInputException(readiness.errors.joinToString(" "))
                    }
                    listHappyHoursRulesForPromotionManagement(connection, venueId, promotionId)
                        .takeIf { it.isNotEmpty() }
                        ?: throw InvalidInputException("Правило Happy Hours не настроено.")
                }

                VenuePromotionStatus.PAUSED ->
                    listHappyHoursRulesForPromotionManagement(connection, venueId, promotionId)
                        .takeIf { it.isNotEmpty() }
                        ?: throw InvalidInputException("Правило Happy Hours не настроено.")

                else ->
                    throw InvalidInputException(
                        "Для Happy Hours поддерживается только включение или приостановка.",
                    )
            }
        rules.forEach { rule ->
            setRuleStatus(connection, venueId, rule.id, status)
                ?: throw InvalidInputException("Правило Happy Hours не найдено.")
        }
    }

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
                        if (
                            promotionId != null &&
                            selectPromotionRuleContext(
                                connection = connection,
                                venueId = venueId,
                                promotionId = promotionId,
                                forUpdate = true,
                            ) == null
                        ) {
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
                                    executable_target_type,
                                    discount_percent,
                                    starts_time,
                                    ends_time,
                                    days_of_week,
                                    status,
                                    priority,
                                    created_by_user_id
                                )
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """.trimIndent(),
                                Statement.RETURN_GENERATED_KEYS,
                            ).use { statement ->
                                setNullableLong(statement, 1, promotionId)
                                statement.setLong(2, venueId)
                                statement.setString(3, PromotionRuleType.HAPPY_HOURS_PERCENT.dbValue)
                                statement.setString(4, PromotionRuleTargetType.CATEGORY_TYPE.dbValue)
                                statement.setString(5, targetValue.dbValue)
                                statement.setNull(6, java.sql.Types.VARCHAR)
                                statement.setInt(7, discountPercent)
                                setNullableTime(statement, 8, startsTime)
                                setNullableTime(statement, 9, endsTime)
                                statement.setString(10, normalizedDays?.joinToString(","))
                                statement.setString(11, VenuePromotionStatus.DRAFT.dbValue)
                                statement.setInt(12, priority)
                                statement.setLong(13, createdByUserId)
                                statement.executeUpdate()
                                statement.generatedKeys.use { keys ->
                                    if (!keys.next()) throw SQLException("No generated key for promotion rule")
                                    keys.getLong(1)
                                }
                            }
                        replaceRuleTargetsWithCategory(connection, venueId, id, targetValue)
                        replaceWeekdayWindows(
                            connection = connection,
                            ruleId = id,
                            windows = legacyScheduleToNormalizedWindows(startsTime, endsTime, normalizedDays),
                        )
                        val created =
                            selectRule(
                                connection,
                                venueId,
                                id,
                            ) ?: throw SQLException(
                                "Created promotion rule not found",
                            )
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
                        if (
                            promotionId != null &&
                            selectPromotionRuleContext(
                                connection = connection,
                                venueId = venueId,
                                promotionId = promotionId,
                                forUpdate = true,
                            ) == null
                        ) {
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
                        val created =
                            selectRule(
                                connection,
                                venueId,
                                id,
                            ) ?: throw SQLException(
                                "Created promotion rule not found",
                            )
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
                            if (lockPromotionAndRuleForMutation(connection, venueId, ruleId) == null) {
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
                                    SET version = version + 1,
                                        updated_at = CURRENT_TIMESTAMP
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
                            if (lockPromotionAndRuleForMutation(connection, venueId, ruleId) == null) {
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
                                    SET version = version + 1,
                                        updated_at = CURRENT_TIMESTAMP
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
            updates += "executable_target_type = ?"
            values += null
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
        updates += "version = version + 1"
        updates += "updated_at = CURRENT_TIMESTAMP"
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        if (lockPromotionAndRuleForMutation(connection, venueId, ruleId) == null) {
                            connection.rollback()
                            return@use null
                        }
                        val updated =
                            connection.prepareStatement(
                                """
                                UPDATE promotion_rules
                                SET ${updates.joinToString(", ")}
                                WHERE venue_id = ? AND id = ?
                                """.trimIndent(),
                            ).use { statement ->
                                values.forEachIndexed {
                                        index,
                                        value,
                                    ->
                                    setStatementValue(statement, index + 1, value)
                                }
                                statement.setLong(values.size + 1, venueId)
                                statement.setLong(values.size + 2, ruleId)
                                statement.executeUpdate()
                            }
                        val result =
                            if (updated == 0) {
                                null
                            } else {
                                targetValue?.let { replaceRuleTargetsWithCategory(connection, venueId, ruleId, it) }
                                val updatedRule = selectRule(connection, venueId, ruleId)
                                if (
                                    updatedRule != null &&
                                    (
                                        clearTimeWindow ||
                                            startsTime != null ||
                                            endsTime != null ||
                                            clearDaysOfWeek ||
                                            daysOfWeek != null
                                    )
                                ) {
                                    replaceWeekdayWindows(
                                        connection = connection,
                                        ruleId = ruleId,
                                        windows =
                                            legacyScheduleToNormalizedWindows(
                                                startsTime = updatedRule.startsTime,
                                                endsTime = updatedRule.endsTime,
                                                daysOfWeek = updatedRule.daysOfWeek,
                                            ),
                                    )
                                    selectRule(connection, venueId, ruleId)
                                } else {
                                    updatedRule
                                }
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
                        val exists = lockPromotionAndRuleForMutation(connection, venueId, ruleId) != null
                        val result =
                            if (!exists) {
                                null
                            } else {
                                connection.prepareStatement(
                                    """
                                    UPDATE promotion_rules
                                    SET target_type = ?,
                                        target_value = ?,
                                        executable_target_type =
                                            CASE
                                                WHEN rule_type = ? THEN executable_target_type
                                                ELSE NULL
                                            END,
                                        version = version + 1,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE venue_id = ? AND id = ?
                                    """.trimIndent(),
                                ).use { statement ->
                                    statement.setString(1, PromotionRuleTargetType.CATEGORY_TYPE.dbValue)
                                    statement.setString(2, semanticType.dbValue)
                                    statement.setString(3, PromotionRuleType.HAPPY_HOURS_PERCENT.dbValue)
                                    statement.setLong(4, venueId)
                                    statement.setLong(5, ruleId)
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
                        val exists = lockPromotionAndRuleForMutation(connection, venueId, ruleId) != null
                        val result =
                            if (!exists) {
                                null
                            } else {
                                val items = loadTargetSelectionItems(connection, venueId, distinctItemIds)
                                require(items.size == distinctItemIds.size) { "menu items must belong to venue" }
                                val semanticTypes = items.map { it.semanticType }.toSet()
                                require(
                                    semanticTypes.size == 1,
                                ) { "menu item targets must share the same semantic type" }
                                val semanticType = semanticTypes.single()
                                connection.prepareStatement(
                                    """
                                    UPDATE promotion_rules
                                    SET target_type = ?,
                                        target_value = ?,
                                        executable_target_type = ?,
                                        version = version + 1,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE venue_id = ? AND id = ?
                                    """.trimIndent(),
                                ).use { statement ->
                                    statement.setString(1, PromotionRuleTargetType.CATEGORY_TYPE.dbValue)
                                    statement.setString(2, semanticType.dbValue)
                                    statement.setString(3, PromotionRuleTargetType.MENU_ITEM.dbValue)
                                    statement.setLong(4, venueId)
                                    statement.setLong(5, ruleId)
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
                ds.connection.use { connection ->
                    loadTargetsForRuleIds(connection, listOf(ruleId))[ruleId].orEmpty() +
                        loadMenuCategoryTargetsForRuleIds(connection, listOf(ruleId))[ruleId].orEmpty()
                }
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
                    val originalAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val result = setRuleStatus(connection, venueId, ruleId, status)
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

    fun setRuleStatus(
        connection: Connection,
        venueId: Long,
        ruleId: Long,
        status: VenuePromotionStatus,
    ): VenuePromotionRule? {
        check(!connection.autoCommit) {
            "Promotion rule status mutation must run inside the caller transaction"
        }
        if (lockPromotionAndRuleForMutation(connection, venueId, ruleId) == null) {
            return null
        }
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
        return if (updated == 0) null else selectRule(connection, venueId, ruleId)
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
        val normalizedDays =
            normalizeDaysOfWeek(daysOfWeek)
                ?: throw IllegalArgumentException("days_of_week is required")
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        if (lockPromotionAndRuleForMutation(connection, venueId, ruleId) == null) {
                            connection.rollback()
                            return@use null
                        }
                        val updated =
                            connection.prepareStatement(
                                """
                                UPDATE promotion_rules
                                SET starts_time = ?,
                                    ends_time = ?,
                                    days_of_week = ?,
                                    version = version + 1,
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
                        val result =
                            if (updated == 0) {
                                null
                            } else {
                                replaceWeekdayWindows(
                                    connection = connection,
                                    ruleId = ruleId,
                                    windows = legacyScheduleToNormalizedWindows(startsTime, endsTime, normalizedDays),
                                )
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

    suspend fun clearRuleSchedule(
        venueId: Long,
        ruleId: Long,
    ): VenuePromotionRule? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        if (lockPromotionAndRuleForMutation(connection, venueId, ruleId) == null) {
                            connection.rollback()
                            return@use null
                        }
                        val updated =
                            connection.prepareStatement(
                                """
                                UPDATE promotion_rules
                                SET starts_time = NULL,
                                    ends_time = NULL,
                                    days_of_week = NULL,
                                    version = version + 1,
                                    updated_at = CURRENT_TIMESTAMP
                                WHERE venue_id = ? AND id = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, venueId)
                                statement.setLong(2, ruleId)
                                statement.executeUpdate()
                            }
                        val result =
                            if (updated == 0) {
                                null
                            } else {
                                replaceWeekdayWindows(
                                    connection = connection,
                                    ruleId = ruleId,
                                    windows = legacyScheduleToNormalizedWindows(null, null, null),
                                )
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
                    connection.autoCommit = false
                    try {
                        val current = lockPromotionAndRuleForMutation(connection, venueId, ruleId)
                        require(
                            current?.ruleType != PromotionRuleType.HAPPY_HOURS_PERCENT ||
                                (!stackable && normalizedConflictGroup == null),
                        ) {
                            "Happy Hours rules are non-stackable"
                        }
                        val result =
                            if (current == null) {
                                null
                            } else {
                                val updated =
                                    connection.prepareStatement(
                                        """
                                        UPDATE promotion_rules
                                        SET stackable = ?,
                                            conflict_group = ?,
                                            version = version + 1,
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

    suspend fun archiveRule(
        venueId: Long,
        promotionId: Long,
        ruleId: Long,
    ): VenuePromotionRule? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val locked =
                            lockPromotionAndRuleForMutation(
                                connection = connection,
                                venueId = venueId,
                                ruleId = ruleId,
                                expectedPromotionId = promotionId,
                            )
                        val result =
                            if (locked == null) {
                                null
                            } else {
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
        limit: Int = Int.MAX_VALUE,
    ): List<VenuePromotionRule> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val originalAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val result = listActiveRulesForVenueAt(connection, venueId, now, limit)
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

    fun listActiveRulesForVenueAt(
        connection: Connection,
        venueId: Long,
        now: Instant = Instant.now(),
        limit: Int = Int.MAX_VALUE,
    ): List<VenuePromotionRule> {
        check(!connection.autoCommit) {
            "Active promotion rules must be loaded inside the caller transaction"
        }
        val promotionIds = lockActivePromotionParents(connection, venueId, now)
        if (promotionIds.isEmpty()) {
            return emptyList()
        }
        val placeholders = promotionIds.joinToString(",") { "?" }
        val rules =
            connection.prepareStatement(
                """
                SELECT ${ruleColumns()}
                FROM promotion_rules r
                JOIN venue_promotions p ON p.id = r.promotion_id
                WHERE r.venue_id = ?
                  AND r.status = ?
                  AND r.promotion_id IN ($placeholders)
                ORDER BY r.promotion_id ASC, r.id ASC
                ${sharedRuleLockClause(connection)}
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, VenuePromotionStatus.ACTIVE.dbValue)
                promotionIds.forEachIndexed { index, promotionId ->
                    statement.setLong(index + 3, promotionId)
                }
                statement.executeQuery().use { rs -> rs.toRules() }
            }
        return attachTargets(connection, rules)
            .sortedWith(
                compareBy<VenuePromotionRule> { it.priority }
                    .thenByDescending { it.discountPercent }
                    .thenBy { it.id },
            )
            .take(limit.coerceAtLeast(1))
    }

    private fun lockActivePromotionParents(
        connection: Connection,
        venueId: Long,
        now: Instant,
    ): List<Long> =
        connection.prepareStatement(
            """
            SELECT p.id
            FROM venue_promotions p
            WHERE p.venue_id = ?
              AND p.status = ?
              AND (p.starts_at IS NULL OR p.starts_at <= ?)
              AND (p.ends_at IS NULL OR p.ends_at >= ?)
              AND EXISTS (
                SELECT 1
                FROM promotion_rules r
                WHERE r.promotion_id = p.id
                  AND r.venue_id = p.venue_id
                  AND r.status = ?
              )
            ORDER BY p.id ASC
            ${sharedPromotionLockClause(connection)}
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, VenuePromotionStatus.ACTIVE.dbValue)
            statement.setTimestamp(3, Timestamp.from(now))
            statement.setTimestamp(4, Timestamp.from(now))
            statement.setString(5, VenuePromotionStatus.ACTIVE.dbValue)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(rs.getLong("id"))
                    }
                }
            }
        }

    private fun sharedPromotionLockClause(connection: Connection): String =
        if (connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)) {
            "FOR SHARE OF p"
        } else {
            "FOR UPDATE"
        }

    private fun sharedRuleLockClause(connection: Connection): String =
        if (connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)) {
            "FOR SHARE OF r"
        } else {
            "FOR UPDATE"
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
        val ruleIds = rules.map { it.id }
        val targetsByRuleId = loadTargetsForRuleIds(connection, ruleIds)
        val categoryTargetsByRuleId = loadMenuCategoryTargetsForRuleIds(connection, ruleIds)
        val weekdayWindowsByRuleId = loadWeekdayWindowsForRuleIds(connection, ruleIds)
        val rewardsByRuleId = loadRewardsForRuleIds(connection, ruleIds)
        return rules.map { rule ->
            val explicitTargets =
                targetsByRuleId[rule.id].orEmpty() + categoryTargetsByRuleId[rule.id].orEmpty()
            val targets =
                if (explicitTargets.isEmpty() && rule.executableTargetType == null) {
                    listOf(rule.legacyTarget())
                } else {
                    explicitTargets
                }
            rule.copy(
                targets = targets,
                reward = rewardsByRuleId[rule.id],
                weekdayWindows = weekdayWindowsByRuleId[rule.id].orEmpty(),
            )
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
            menuCategoryId = null,
            menuCategoryName = null,
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
                                menuCategoryId = null,
                                menuCategoryName = null,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun loadMenuCategoryTargetsForRuleIds(
        connection: Connection,
        ruleIds: List<Long>,
    ): Map<Long, List<PromotionRuleTarget>> {
        val ids = ruleIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            SELECT
                prct.id,
                prct.rule_id,
                prct.menu_category_id,
                mc.name AS menu_category_name,
                mc.category_type
            FROM promotion_rule_menu_category_targets prct
            JOIN promotion_rules r ON r.id = prct.rule_id
            JOIN menu_categories mc
              ON mc.id = prct.menu_category_id
             AND mc.venue_id = r.venue_id
            WHERE prct.rule_id IN ($placeholders)
            ORDER BY prct.id ASC
            """.trimIndent(),
        ).use { statement ->
            ids.forEachIndexed { index, ruleId -> statement.setLong(index + 1, ruleId) }
            statement.executeQuery().use { rs ->
                buildMap<Long, MutableList<PromotionRuleTarget>> {
                    while (rs.next()) {
                        val ruleId = rs.getLong("rule_id")
                        getOrPut(ruleId) { mutableListOf() }.add(
                            PromotionRuleTarget(
                                id = rs.getLong("id"),
                                ruleId = ruleId,
                                targetType = PromotionRuleTargetType.MENU_CATEGORY,
                                semanticType = MenuSemanticType.fromDb(rs.getString("category_type")),
                                menuItemId = null,
                                menuItemName = null,
                                menuCategoryId = rs.getLong("menu_category_id"),
                                menuCategoryName = rs.getString("menu_category_name"),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun loadWeekdayWindowsForRuleIds(
        connection: Connection,
        ruleIds: List<Long>,
    ): Map<Long, List<PromotionWeekdayWindow>> {
        val ids = ruleIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            SELECT rule_id, weekday, starts_minute, ends_minute
            FROM promotion_rule_weekday_windows
            WHERE rule_id IN ($placeholders)
            ORDER BY rule_id, weekday, starts_minute, ends_minute
            """.trimIndent(),
        ).use { statement ->
            ids.forEachIndexed { index, ruleId -> statement.setLong(index + 1, ruleId) }
            statement.executeQuery().use { rs ->
                buildMap<Long, MutableList<PromotionWeekdayWindow>> {
                    while (rs.next()) {
                        val ruleId = rs.getLong("rule_id")
                        getOrPut(ruleId) { mutableListOf() }.add(
                            PromotionWeekdayWindow(
                                weekday = rs.getInt("weekday"),
                                startsMinute = rs.getInt("starts_minute"),
                                endsMinute = rs.getInt("ends_minute"),
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
                                        priceMinor =
                                            rs.getLong("price_minor").let {
                                                    value ->
                                                if (rs.wasNull()) 0L else value
                                            },
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
        connection.prepareStatement(
            "DELETE FROM promotion_rule_menu_category_targets WHERE rule_id = ?",
        ).use { statement ->
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

    private fun replaceExecutableHappyHoursTarget(
        connection: Connection,
        ruleId: Long,
        target: ResolvedHappyHoursTarget,
    ) {
        deleteRuleTargets(connection, ruleId)
        when (target.input.targetType) {
            PromotionRuleTargetType.MENU_ITEM ->
                insertMenuItemTarget(
                    connection = connection,
                    ruleId = ruleId,
                    menuItemId = requireNotNull(target.input.menuItemId),
                )
            PromotionRuleTargetType.MENU_CATEGORY ->
                connection.prepareStatement(
                    """
                    INSERT INTO promotion_rule_menu_category_targets (rule_id, menu_category_id)
                    VALUES (?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, ruleId)
                    statement.setLong(2, requireNotNull(target.input.menuCategoryId))
                    statement.executeUpdate()
                }
            PromotionRuleTargetType.CATEGORY_TYPE ->
                error("CATEGORY_TYPE is not supported by the Phase 2 Happy Hours editor")
        }
    }

    private fun replaceWeekdayWindows(
        connection: Connection,
        ruleId: Long,
        windows: List<PromotionWeekdayWindow>,
    ) {
        connection.prepareStatement(
            "DELETE FROM promotion_rule_weekday_windows WHERE rule_id = ?",
        ).use { statement ->
            statement.setLong(1, ruleId)
            statement.executeUpdate()
        }
        if (windows.isEmpty()) return
        connection.prepareStatement(
            """
            INSERT INTO promotion_rule_weekday_windows (
                rule_id,
                weekday,
                starts_minute,
                ends_minute
            )
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            windows.forEach { window ->
                statement.setLong(1, ruleId)
                statement.setInt(2, window.weekday)
                statement.setInt(3, window.startsMinute)
                statement.setInt(4, window.endsMinute)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun resolveHappyHoursTarget(
        connection: Connection,
        venueId: Long,
        target: HappyHoursRuleTargetInput,
    ): ResolvedHappyHoursTarget =
        when (target.targetType) {
            PromotionRuleTargetType.MENU_ITEM -> {
                val menuItemId = requireNotNull(target.menuItemId)
                connection.prepareStatement(
                    """
                    SELECT
                        COALESCE(mi.item_type, mc.category_type) AS effective_type
                    FROM menu_items mi
                    JOIN menu_categories mc
                      ON mc.id = mi.category_id
                     AND mc.venue_id = mi.venue_id
                    WHERE mi.id = ?
                      AND mi.venue_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, menuItemId)
                    statement.setLong(2, venueId)
                    statement.executeQuery().use { rs ->
                        require(rs.next()) { "menu item target must belong to venue" }
                        ResolvedHappyHoursTarget(
                            input = target,
                            semanticType = MenuSemanticType.fromDb(rs.getString("effective_type")),
                        )
                    }
                }
            }
            PromotionRuleTargetType.MENU_CATEGORY -> {
                val menuCategoryId = requireNotNull(target.menuCategoryId)
                connection.prepareStatement(
                    """
                    SELECT category_type
                    FROM menu_categories
                    WHERE id = ?
                      AND venue_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, menuCategoryId)
                    statement.setLong(2, venueId)
                    statement.executeQuery().use { rs ->
                        require(rs.next()) { "menu category target must belong to venue" }
                        ResolvedHappyHoursTarget(
                            input = target,
                            semanticType = MenuSemanticType.fromDb(rs.getString("category_type")),
                        )
                    }
                }
            }
            PromotionRuleTargetType.CATEGORY_TYPE ->
                throw IllegalArgumentException(
                    "Phase 2 Happy Hours target must be MENU_ITEM or MENU_CATEGORY",
                )
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

    private fun lockPromotionAndRuleForMutation(
        connection: Connection,
        venueId: Long,
        ruleId: Long,
        expectedPromotionId: Long? = null,
    ): LockedPromotionRule? {
        check(!connection.autoCommit) {
            "Promotion rule mutation must run inside the caller transaction"
        }
        val discoveredPromotionId =
            connection.prepareStatement(
                """
                SELECT promotion_id
                FROM promotion_rules
                WHERE venue_id = ? AND id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, ruleId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return null
                    }
                    rs.getLong("promotion_id").let { value -> if (rs.wasNull()) null else value }
                }
            }
        if (expectedPromotionId != null && discoveredPromotionId != expectedPromotionId) {
            return null
        }
        if (
            discoveredPromotionId != null &&
            selectPromotionRuleContext(
                connection = connection,
                venueId = venueId,
                promotionId = discoveredPromotionId,
                forUpdate = true,
            ) == null
        ) {
            return null
        }
        return connection.prepareStatement(
            """
            SELECT promotion_id, rule_type, status
            FROM promotion_rules
            WHERE venue_id = ? AND id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, ruleId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    val lockedPromotionId =
                        rs.getLong("promotion_id").let { value -> if (rs.wasNull()) null else value }
                    if (
                        lockedPromotionId != discoveredPromotionId ||
                        (expectedPromotionId != null && lockedPromotionId != expectedPromotionId)
                    ) {
                        null
                    } else {
                        LockedPromotionRule(
                            promotionId = lockedPromotionId,
                            ruleType =
                                PromotionRuleType.fromDb(rs.getString("rule_type"))
                                    ?: throw SQLException("Unknown promotion rule type"),
                            status =
                                VenuePromotionStatus.fromDb(rs.getString("status"))
                                    ?: throw SQLException("Unknown promotion rule status"),
                        )
                    }
                }
            }
        }
    }

    private fun lockPromotionRulesForMutation(
        connection: Connection,
        venueId: Long,
        promotionId: Long,
    ) {
        connection.prepareStatement(
            """
            SELECT id
            FROM promotion_rules
            WHERE venue_id = ? AND promotion_id = ?
            ORDER BY id
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, promotionId)
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    rs.getLong("id")
                }
            }
        }
    }

    private fun selectPromotionRuleContext(
        connection: Connection,
        venueId: Long,
        promotionId: Long,
        forUpdate: Boolean = false,
    ): PromotionRuleContext? {
        val lockClause = if (forUpdate) "FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT template_type, status, starts_at, ends_at
            FROM venue_promotions
            WHERE venue_id = ?
              AND id = ?
            $lockClause
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, promotionId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    val templateType =
                        VenuePromotionTemplateType.fromDb(rs.getString("template_type"))
                            ?: VenuePromotionTemplateType.TEXT_ONLY
                    val status =
                        VenuePromotionStatus.fromDb(rs.getString("status"))
                            ?: throw SQLException("Unknown promotion status")
                    PromotionRuleContext(
                        templateType = templateType,
                        status = status,
                        startsAt = rs.getTimestamp("starts_at")?.toInstant(),
                        endsAt = rs.getTimestamp("ends_at")?.toInstant(),
                    )
                }
            }
        }
    }

    private fun requireHappyHoursPromotionCanBeConfigured(context: PromotionRuleContext) {
        require(context.templateType == VenuePromotionTemplateType.HAPPY_HOURS_PERCENT) {
            "promotion template must be HAPPY_HOURS_PERCENT"
        }
        require(context.status != VenuePromotionStatus.ARCHIVED) {
            "archived promotion cannot be configured"
        }
    }

    private fun hasNonArchivedHappyHoursRule(
        connection: Connection,
        venueId: Long,
        promotionId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT 1
            FROM promotion_rules
            WHERE venue_id = ?
              AND promotion_id = ?
              AND rule_type = ?
              AND status <> ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, promotionId)
            statement.setString(3, PromotionRuleType.HAPPY_HOURS_PERCENT.dbValue)
            statement.setString(4, VenuePromotionStatus.ARCHIVED.dbValue)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun selectHappyHoursRuleForUpdate(
        connection: Connection,
        venueId: Long,
        promotionId: Long,
        ruleId: Long,
    ): HappyHoursRuleForUpdate? =
        connection.prepareStatement(
            """
            SELECT status
            FROM promotion_rules
            WHERE venue_id = ?
              AND promotion_id = ?
              AND id = ?
              AND rule_type = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, promotionId)
            statement.setLong(3, ruleId)
            statement.setString(4, PromotionRuleType.HAPPY_HOURS_PERCENT.dbValue)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    HappyHoursRuleForUpdate(
                        status =
                            VenuePromotionStatus.fromDb(rs.getString("status"))
                                ?: throw SQLException("Unknown promotion rule status"),
                    )
                }
            }
        }

    private fun validateHappyHoursActivationReadinessOnConnection(
        connection: Connection,
        venueId: Long,
        promotionId: Long,
    ): HappyHoursActivationReadiness {
        val fallbackTimezone = VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE
        val context = selectPromotionRuleContext(connection, venueId, promotionId)
        if (context == null) {
            return HappyHoursActivationReadiness(
                isReady = false,
                errors = listOf("Акция не найдена."),
                rule = null,
                venueTimezone = fallbackTimezone,
                ruleCount = 0,
            )
        }

        val errors = mutableListOf<String>()
        if (context.templateType != VenuePromotionTemplateType.HAPPY_HOURS_PERCENT) {
            errors += "Для автоматической скидки выберите тип «Счастливые часы — скидка %»."
        }
        if (context.status == VenuePromotionStatus.ARCHIVED) {
            errors += "Архивную акцию нельзя активировать."
        }
        if (context.startsAt == null || context.endsAt == null || !context.startsAt.isBefore(context.endsAt)) {
            errors += "Укажите корректный общий период действия акции."
        }

        val rawTimezone = selectVenueTimezone(connection, venueId)
        val venueTimezone =
            rawTimezone?.trim()?.takeIf { it.isNotEmpty() }
                ?: fallbackTimezone
        if (runCatching { ZoneId.of(venueTimezone) }.isFailure) {
            errors += "Часовой пояс заведения указан некорректно."
        }

        val rules = loadNonArchivedHappyHoursRules(connection, venueId, promotionId)
        val rule = rules.firstOrNull()
        if (rules.isEmpty()) {
            errors += "Для акции должно быть настроено правило «Счастливые часы»."
        }
        rules.forEach { candidate ->
            if (candidate.discountPercent !in 1..100) {
                errors += "Процент скидки должен быть от 1 до 100."
            }
            if (candidate.stackable || candidate.conflictGroup != null || candidate.maxApplicationsPerItem != 1) {
                errors += "Для этой акции нельзя включать суммирование скидок."
            }
            if (candidate.version < 1) {
                errors += "Версия правила указана некорректно."
            }
            val normalizedWindows =
                runCatching { normalizeWeekdayWindows(candidate.weekdayWindows) }
                    .onFailure {
                        errors +=
                            when {
                                candidate.weekdayWindows.isEmpty() ->
                                    "Добавьте хотя бы одно временное окно."
                                else ->
                                    "Временные окна должны быть корректными и не пересекаться."
                            }
                    }.getOrNull()
            if (normalizedWindows != null && normalizedWindows.isEmpty()) {
                errors += "Добавьте хотя бы одно временное окно."
            }
            val executableTargetType = candidate.executableTargetType
            if (executableTargetType == null) {
                if (candidate.targets.isEmpty()) {
                    errors += "Выберите цель акции."
                } else if (!targetsBelongToVenue(connection, venueId, candidate.targets)) {
                    errors += "Цель акции должна принадлежать заведению."
                }
            } else if (
                executableTargetType !in
                setOf(
                    PromotionRuleTargetType.MENU_ITEM,
                    PromotionRuleTargetType.MENU_CATEGORY,
                )
            ) {
                errors += "Выберите категорию или позицию меню."
            } else if (
                candidate.targets.size != 1 ||
                candidate.targets.single().targetType != executableTargetType
            ) {
                errors += "Для правила должна быть выбрана ровно одна категория или позиция меню."
            } else {
                if (!targetsBelongToVenue(connection, venueId, candidate.targets)) {
                    errors += "Цель акции должна принадлежать заведению."
                }
            }
        }
        return HappyHoursActivationReadiness(
            isReady = errors.isEmpty(),
            errors = errors.distinct(),
            rule = rule,
            venueTimezone = venueTimezone,
            ruleCount = rules.size,
        )
    }

    private fun loadNonArchivedHappyHoursRules(
        connection: Connection,
        venueId: Long,
        promotionId: Long,
    ): List<VenuePromotionRule> =
        connection.prepareStatement(
            """
            SELECT ${ruleColumns()}
            FROM promotion_rules r
            LEFT JOIN venue_promotions p ON p.id = r.promotion_id
            WHERE r.venue_id = ?
              AND r.promotion_id = ?
              AND r.rule_type = ?
              AND r.status <> ?
            ORDER BY r.id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, promotionId)
            statement.setString(3, PromotionRuleType.HAPPY_HOURS_PERCENT.dbValue)
            statement.setString(4, VenuePromotionStatus.ARCHIVED.dbValue)
            statement.executeQuery().use { rs -> attachTargets(connection, rs.toRules()) }
        }

    private fun selectVenueTimezone(
        connection: Connection,
        venueId: Long,
    ): String? =
        connection.prepareStatement(
            "SELECT timezone FROM venue_settings WHERE venue_id = ?",
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getString("timezone") else null }
        }

    private fun targetsBelongToVenue(
        connection: Connection,
        venueId: Long,
        targets: List<PromotionRuleTarget>,
    ): Boolean =
        targets.all { target ->
            when (target.targetType) {
                PromotionRuleTargetType.CATEGORY_TYPE -> target.semanticType != null
                PromotionRuleTargetType.MENU_ITEM ->
                    target.menuItemId?.let { menuItemId ->
                        menuItemBelongsToVenue(connection, venueId, menuItemId)
                    } ?: false
                PromotionRuleTargetType.MENU_CATEGORY ->
                    target.menuCategoryId?.let { menuCategoryId ->
                        menuCategoryBelongsToVenue(connection, venueId, menuCategoryId)
                    } ?: false
            }
        }

    private fun menuItemBelongsToVenue(
        connection: Connection,
        venueId: Long,
        menuItemId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT 1
            FROM menu_items mi
            JOIN menu_categories mc
              ON mc.id = mi.category_id
             AND mc.venue_id = mi.venue_id
            WHERE mi.venue_id = ?
              AND mi.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, menuItemId)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun menuCategoryBelongsToVenue(
        connection: Connection,
        venueId: Long,
        menuCategoryId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT 1
            FROM menu_categories
            WHERE venue_id = ?
              AND id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, menuCategoryId)
            statement.executeQuery().use { rs -> rs.next() }
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
        p.starts_at AS promotion_starts_at,
        p.ends_at AS promotion_ends_at,
        r.venue_id,
        r.rule_type,
        r.target_type,
        r.target_value,
        r.executable_target_type,
        r.discount_percent,
        r.starts_time,
        r.ends_time,
        r.days_of_week,
        r.status,
        r.priority,
        r.stackable,
        r.conflict_group,
        r.max_applications_per_item,
        r.version,
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
            promotionStartsAt = getTimestamp("promotion_starts_at")?.toInstant(),
            promotionEndsAt = getTimestamp("promotion_ends_at")?.toInstant(),
            venueId = getLong("venue_id"),
            ruleType = ruleType,
            targetType = targetType,
            targetValue = MenuSemanticType.fromDb(getString("target_value")),
            executableTargetType = PromotionRuleTargetType.fromDb(getString("executable_target_type")),
            discountPercent = getInt("discount_percent"),
            startsTime = getTime("starts_time")?.toLocalTime(),
            endsTime = getTime("ends_time")?.toLocalTime(),
            daysOfWeek = parseDaysOfWeek(getString("days_of_week")),
            status = status,
            priority = getInt("priority"),
            stackable = getBoolean("stackable"),
            conflictGroup = getString("conflict_group")?.trim()?.takeIf { it.isNotBlank() },
            maxApplicationsPerItem = getInt("max_applications_per_item").coerceAtLeast(1),
            version = getInt("version").coerceAtLeast(1),
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
                    PromotionRuleTargetType.MENU_CATEGORY ->
                        target.menuCategoryId?.let { "${PromotionRuleTargetType.MENU_CATEGORY.dbValue}:$it" }
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

    private fun validateHappyHoursTargetInput(target: HappyHoursRuleTargetInput) {
        when (target.targetType) {
            PromotionRuleTargetType.MENU_ITEM -> {
                require(target.menuItemId != null && target.menuItemId > 0L) {
                    "menu_item_id must be positive"
                }
                require(target.menuCategoryId == null) {
                    "menu_category_id must be empty for MENU_ITEM target"
                }
            }
            PromotionRuleTargetType.MENU_CATEGORY -> {
                require(target.menuCategoryId != null && target.menuCategoryId > 0L) {
                    "menu_category_id must be positive"
                }
                require(target.menuItemId == null) {
                    "menu_item_id must be empty for MENU_CATEGORY target"
                }
            }
            PromotionRuleTargetType.CATEGORY_TYPE ->
                throw IllegalArgumentException(
                    "Phase 2 Happy Hours target must be MENU_ITEM or MENU_CATEGORY",
                )
        }
    }

    private fun normalizeWeekdayWindows(windows: List<PromotionWeekdayWindow>): List<PromotionWeekdayWindow> {
        require(windows.isNotEmpty()) { "weekday_windows must not be empty" }
        val normalized =
            windows
                .onEach { window ->
                    require(window.weekday in 1..7) { "weekday must be between 1 and 7" }
                    require(window.startsMinute in 0..1439) {
                        "starts_minute must be between 0 and 1439"
                    }
                    require(window.endsMinute in 1..1440) {
                        "ends_minute must be between 1 and 1440"
                    }
                    require(window.startsMinute < window.endsMinute) {
                        "overnight promotion rule time windows are not supported"
                    }
                }.sortedWith(
                    compareBy<PromotionWeekdayWindow> { it.weekday }
                        .thenBy { it.startsMinute }
                        .thenBy { it.endsMinute },
                )
        normalized
            .groupBy { it.weekday }
            .values
            .forEach { sameDay ->
                sameDay.zipWithNext().forEach { (previous, current) ->
                    require(current.startsMinute >= previous.endsMinute) {
                        "weekday promotion rule time windows must not overlap"
                    }
                }
            }
        return normalized
    }

    private fun legacyScheduleToNormalizedWindows(
        startsTime: LocalTime?,
        endsTime: LocalTime?,
        daysOfWeek: Set<Int>?,
    ): List<PromotionWeekdayWindow> {
        validateTimeWindow(startsTime, endsTime)
        val weekdays = daysOfWeek?.takeIf { it.isNotEmpty() }?.toSortedSet() ?: (1..7).toSet()
        require(weekdays.all { it in 1..7 }) { "days_of_week must contain values 1..7" }
        val startsMinute = startsTime?.let { it.hour * 60 + it.minute } ?: 0
        val endsMinute = endsTime?.let { it.hour * 60 + it.minute } ?: 1440
        return normalizeWeekdayWindows(
            weekdays.map { weekday ->
                PromotionWeekdayWindow(
                    weekday = weekday,
                    startsMinute = startsMinute,
                    endsMinute = endsMinute,
                )
            },
        )
    }

    private fun List<PromotionWeekdayWindow>.toLegacyScheduleProjection(): LegacyScheduleProjection? {
        if (isEmpty()) return null
        val distinctTimeRanges = map { it.startsMinute to it.endsMinute }.distinct()
        if (distinctTimeRanges.size != 1) return null
        val (startsMinute, endsMinute) = distinctTimeRanges.single()
        if (startsMinute == 0 && endsMinute == 1440 && map { it.weekday }.toSet() == (1..7).toSet()) {
            return null
        }
        if (endsMinute == 1440) return null
        return LegacyScheduleProjection(
            startsTime = LocalTime.of(startsMinute / 60, startsMinute % 60),
            endsTime = LocalTime.of(endsMinute / 60, endsMinute % 60),
            daysOfWeek = map { it.weekday }.toSortedSet(),
        )
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

    private data class PromotionRuleContext(
        val templateType: VenuePromotionTemplateType,
        val status: VenuePromotionStatus,
        val startsAt: Instant?,
        val endsAt: Instant?,
    )

    private data class HappyHoursRuleForUpdate(
        val status: VenuePromotionStatus,
    )

    private data class LockedPromotionRule(
        val promotionId: Long?,
        val ruleType: PromotionRuleType,
        val status: VenuePromotionStatus,
    )

    private data class ResolvedHappyHoursTarget(
        val input: HappyHoursRuleTargetInput,
        val semanticType: MenuSemanticType,
    )

    private data class LegacyScheduleProjection(
        val startsTime: LocalTime,
        val endsTime: LocalTime,
        val daysOfWeek: Set<Int>,
    )
}
