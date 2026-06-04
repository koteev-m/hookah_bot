package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.venue.menu.MenuSemanticType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.util.Locale
import javax.sql.DataSource

enum class LoyaltyProgramType(val dbValue: String) {
    NTH_HOOKAH_FREE("NTH_HOOKAH_FREE"),
    ;

    companion object {
        fun fromDb(value: String?): LoyaltyProgramType? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.dbValue == normalized }
        }
    }
}

enum class LoyaltyProgramStatus(val dbValue: String) {
    DRAFT("DRAFT"),
    ACTIVE("ACTIVE"),
    PAUSED("PAUSED"),
    ARCHIVED("ARCHIVED"),
    ;

    companion object {
        fun fromDb(value: String?): LoyaltyProgramStatus? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.dbValue == normalized }
        }
    }
}

data class LoyaltyProgram(
    val id: Long,
    val venueId: Long,
    val venueName: String?,
    val programType: LoyaltyProgramType,
    val status: LoyaltyProgramStatus,
    val nthValue: Int,
    val maxRedemptionsPerVisit: Int,
    val createdByUserId: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class LoyaltyProgramTargetType(val dbValue: String) {
    CATEGORY_TYPE("CATEGORY_TYPE"),
    MENU_ITEM("MENU_ITEM"),
    ;

    companion object {
        fun fromDb(value: String?): LoyaltyProgramTargetType? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.dbValue == normalized }
        }
    }
}

data class LoyaltyProgramTarget(
    val id: Long,
    val programId: Long,
    val targetType: LoyaltyProgramTargetType,
    val semanticType: MenuSemanticType?,
    val menuItemId: Long?,
    val menuItemName: String? = null,
)

data class GuestLoyaltyProgress(
    val programId: Long,
    val venueId: Long,
    val venueName: String,
    val userId: Long,
    val nthValue: Int,
    val progressCount: Int,
    val rewardsAvailable: Int,
    val rewardsReserved: Int,
    val updatedAt: Instant?,
)

data class LoyaltyProgressSummary(
    val usersWithProgress: Long,
    val rewardsAvailable: Long,
)

data class LoyaltyAccrualResult(
    val orderId: Long,
    val itemsCounted: Int,
    val progressAdded: Int,
    val rewardsAdded: Int,
)

data class LoyaltyCartItem(
    val lineId: Long? = null,
    val menuItemId: Long,
    val itemName: String,
    val qty: Int,
    val priceMinor: Long,
    val currency: String,
)

data class LoyaltyRedemptionPreview(
    val lineId: Long? = null,
    val menuItemId: Long,
    val itemName: String,
    val discountMinor: Long,
    val currency: String,
)

data class LoyaltyCheckoutItem(
    val orderBatchItemId: Long,
    val menuItemId: Long,
    val itemName: String,
    val qty: Int,
    val priceMinor: Long,
    val currency: String,
    val effectiveType: MenuSemanticType?,
)

data class LoyaltyRedemptionResult(
    val redeemedOrderBatchItemId: Long,
    val discount: CreatedOrderPromotionDiscount,
)

class LoyaltyRepository(private val dataSource: DataSource?) {
    suspend fun createProgram(
        venueId: Long,
        nthValue: Int,
        createdByUserId: Long,
    ): LoyaltyProgram {
        validateNthValue(nthValue)
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val id =
                            connection.prepareStatement(
                                """
                                INSERT INTO loyalty_programs (
                                    venue_id,
                                    program_type,
                                    status,
                                    nth_value,
                                    max_redemptions_per_visit,
                                    created_by_user_id
                                )
                                VALUES (?, ?, ?, ?, 1, ?)
                                """.trimIndent(),
                                Statement.RETURN_GENERATED_KEYS,
                            ).use { statement ->
                                statement.setLong(1, venueId)
                                statement.setString(2, LoyaltyProgramType.NTH_HOOKAH_FREE.dbValue)
                                statement.setString(3, LoyaltyProgramStatus.DRAFT.dbValue)
                                statement.setInt(4, nthValue)
                                statement.setLong(5, createdByUserId)
                                statement.executeUpdate()
                                statement.generatedKeys.use { keys ->
                                    if (!keys.next()) throw SQLException("No generated key for loyalty program")
                                    keys.getLong(1)
                                }
                            }
                        insertDefaultEarnTarget(connection, id)
                        insertDefaultRewardTarget(connection, id)
                        val created = selectProgram(connection, venueId, id)
                            ?: throw SQLException("Created loyalty program not found")
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

    suspend fun createOrUpdateDraftProgram(
        venueId: Long,
        nthValue: Int,
        createdByUserId: Long,
    ): LoyaltyProgram {
        validateNthValue(nthValue)
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val existing = selectFirstProgramForVenue(connection, venueId)
                        val programId =
                            if (existing == null) {
                                connection.prepareStatement(
                                    """
                                    INSERT INTO loyalty_programs (
                                        venue_id,
                                        program_type,
                                        status,
                                        nth_value,
                                        max_redemptions_per_visit,
                                        created_by_user_id
                                    )
                                    VALUES (?, ?, ?, ?, 1, ?)
                                    """.trimIndent(),
                                    Statement.RETURN_GENERATED_KEYS,
                                ).use { statement ->
                                    statement.setLong(1, venueId)
                                    statement.setString(2, LoyaltyProgramType.NTH_HOOKAH_FREE.dbValue)
                                    statement.setString(3, LoyaltyProgramStatus.DRAFT.dbValue)
                                    statement.setInt(4, nthValue)
                                    statement.setLong(5, createdByUserId)
                                    statement.executeUpdate()
                                    statement.generatedKeys.use { keys ->
                                        if (!keys.next()) throw SQLException("No generated key for loyalty program")
                                        keys.getLong(1)
                                    }
                                }
                            } else {
                                connection.prepareStatement(
                                    """
                                    UPDATE loyalty_programs
                                    SET nth_value = ?,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE venue_id = ? AND id = ?
                                    """.trimIndent(),
                                ).use { statement ->
                                    statement.setInt(1, nthValue)
                                    statement.setLong(2, venueId)
                                    statement.setLong(3, existing.id)
                                    statement.executeUpdate()
                                }
                                existing.id
                            }
                        ensureDefaultTargets(connection, programId)
                        val program = selectProgram(connection, venueId, programId)
                            ?: throw SQLException("Loyalty program not found after save")
                        connection.commit()
                        program
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

    suspend fun listProgramsForVenue(venueId: Long): List<LoyaltyProgram> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${programColumns()}
                        FROM loyalty_programs lp
                        LEFT JOIN venues v ON v.id = lp.venue_id
                        WHERE lp.venue_id = ?
                          AND lp.status <> ?
                        ORDER BY
                            CASE lp.status
                                WHEN 'ACTIVE' THEN 0
                                WHEN 'DRAFT' THEN 1
                                WHEN 'PAUSED' THEN 2
                                ELSE 3
                            END,
                            lp.id DESC
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setString(2, LoyaltyProgramStatus.ARCHIVED.dbValue)
                        statement.executeQuery().use { rs -> rs.toPrograms() }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun getProgramForVenue(
        venueId: Long,
        programId: Long,
    ): LoyaltyProgram? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection -> selectProgram(connection, venueId, programId) }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun getActiveProgram(venueId: Long): LoyaltyProgram? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection -> selectActiveProgram(connection, venueId) }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listEarnTargets(
        venueId: Long,
        programId: Long,
    ): List<LoyaltyProgramTarget> = listTargets(venueId, programId, LOYALTY_EARN_TARGETS_TABLE)

    suspend fun listRewardTargets(
        venueId: Long,
        programId: Long,
    ): List<LoyaltyProgramTarget> = listTargets(venueId, programId, LOYALTY_REWARD_TARGETS_TABLE)

    suspend fun listHookahMenuItemsForTargetSelection(venueId: Long): List<PromotionRuleTargetMenuItem> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT
                            mi.id,
                            mi.name,
                            COALESCE(mi.item_type, mc.category_type, 'OTHER') AS semantic_type
                        FROM menu_items mi
                        JOIN menu_categories mc ON mc.id = mi.category_id
                        WHERE mi.venue_id = ?
                          AND mc.is_active = TRUE
                          AND mi.is_available = TRUE
                          AND COALESCE(mi.item_type, mc.category_type, 'OTHER') = 'HOOKAH'
                        ORDER BY mc.sort_order, mi.sort_order, mi.id
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(
                                        PromotionRuleTargetMenuItem(
                                            id = rs.getLong("id"),
                                            name = rs.getString("name"),
                                            semanticType =
                                                MenuSemanticType.fromDb(rs.getString("semantic_type"))
                                                    ?: MenuSemanticType.HOOKAH,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun replaceEarnTargetsWithAllHookahs(
        venueId: Long,
        programId: Long,
    ): LoyaltyProgram? = replaceTargetsWithAllHookahs(venueId, programId, LOYALTY_EARN_TARGETS_TABLE)

    suspend fun replaceEarnTargetsWithMenuItems(
        venueId: Long,
        programId: Long,
        menuItemIds: List<Long>,
    ): LoyaltyProgram? = replaceTargetsWithMenuItems(venueId, programId, menuItemIds, LOYALTY_EARN_TARGETS_TABLE)

    suspend fun replaceRewardTargetsWithAllHookahs(
        venueId: Long,
        programId: Long,
    ): LoyaltyProgram? = replaceTargetsWithAllHookahs(venueId, programId, LOYALTY_REWARD_TARGETS_TABLE)

    suspend fun replaceRewardTargetsWithMenuItems(
        venueId: Long,
        programId: Long,
        menuItemIds: List<Long>,
    ): LoyaltyProgram? = replaceTargetsWithMenuItems(venueId, programId, menuItemIds, LOYALTY_REWARD_TARGETS_TABLE)

    suspend fun setProgramStatus(
        venueId: Long,
        programId: Long,
        status: LoyaltyProgramStatus,
    ): LoyaltyProgram? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        if (status == LoyaltyProgramStatus.ACTIVE) {
                            connection.prepareStatement(
                                """
                                UPDATE loyalty_programs
                                SET status = ?, updated_at = CURRENT_TIMESTAMP
                                WHERE venue_id = ?
                                  AND id <> ?
                                  AND status = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, LoyaltyProgramStatus.PAUSED.dbValue)
                                statement.setLong(2, venueId)
                                statement.setLong(3, programId)
                                statement.setString(4, LoyaltyProgramStatus.ACTIVE.dbValue)
                                statement.executeUpdate()
                            }
                        }
                        val updated =
                            connection.prepareStatement(
                                """
                                UPDATE loyalty_programs
                                SET status = ?, updated_at = CURRENT_TIMESTAMP
                                WHERE venue_id = ? AND id = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, status.dbValue)
                                statement.setLong(2, venueId)
                                statement.setLong(3, programId)
                                statement.executeUpdate()
                            }
                        val program = if (updated == 0) null else selectProgram(connection, venueId, programId)
                        connection.commit()
                        program
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

    suspend fun getGuestProgress(
        venueId: Long,
        userId: Long,
    ): GuestLoyaltyProgress? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT
                            lp.id AS program_id,
                            lp.venue_id,
                            COALESCE(v.name, 'Заведение #' || lp.venue_id) AS venue_name,
                            ? AS user_id,
                            lp.nth_value,
                            COALESCE(glp.progress_count, 0) AS progress_count,
                            COALESCE(glp.rewards_available, 0) AS rewards_available,
                            COALESCE(glp.rewards_reserved, 0) AS rewards_reserved,
                            glp.updated_at
                        FROM loyalty_programs lp
                        LEFT JOIN venues v ON v.id = lp.venue_id
                        LEFT JOIN guest_loyalty_progress glp
                          ON glp.program_id = lp.id
                         AND glp.user_id = ?
                        WHERE lp.venue_id = ?
                          AND lp.status = ?
                        ORDER BY lp.id DESC
                        LIMIT 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.setLong(2, userId)
                        statement.setLong(3, venueId)
                        statement.setString(4, LoyaltyProgramStatus.ACTIVE.dbValue)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) rs.toGuestProgress() else null
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listGuestProgress(userId: Long): List<GuestLoyaltyProgress> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT
                            lp.id AS program_id,
                            lp.venue_id,
                            COALESCE(v.name, 'Заведение #' || lp.venue_id) AS venue_name,
                            ? AS user_id,
                            lp.nth_value,
                            COALESCE(glp.progress_count, 0) AS progress_count,
                            COALESCE(glp.rewards_available, 0) AS rewards_available,
                            COALESCE(glp.rewards_reserved, 0) AS rewards_reserved,
                            glp.updated_at
                        FROM loyalty_programs lp
                        JOIN guest_loyalty_progress glp
                          ON glp.program_id = lp.id
                         AND glp.user_id = ?
                        LEFT JOIN venues v ON v.id = lp.venue_id
                        WHERE lp.status = ?
                          AND (glp.progress_count > 0 OR glp.rewards_available > 0 OR glp.rewards_reserved > 0)
                        ORDER BY glp.updated_at DESC, lp.id DESC
                        LIMIT 20
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.setLong(2, userId)
                        statement.setString(3, LoyaltyProgramStatus.ACTIVE.dbValue)
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) add(rs.toGuestProgress())
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun getProgressSummary(
        venueId: Long,
        programId: Long,
    ): LoyaltyProgressSummary {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT
                            COALESCE(SUM(
                                CASE
                                    WHEN glp.progress_count > 0
                                      OR glp.rewards_available > 0
                                      OR glp.rewards_reserved > 0
                                    THEN 1
                                    ELSE 0
                                END
                            ), 0) AS users_with_progress,
                            COALESCE(SUM(glp.rewards_available), 0) AS rewards_available
                        FROM guest_loyalty_progress glp
                        JOIN loyalty_programs lp ON lp.id = glp.program_id
                        WHERE lp.venue_id = ?
                          AND lp.id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, programId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                LoyaltyProgressSummary(
                                    usersWithProgress = rs.getLong("users_with_progress"),
                                    rewardsAvailable = rs.getLong("rewards_available"),
                                )
                            } else {
                                LoyaltyProgressSummary(usersWithProgress = 0, rewardsAvailable = 0)
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun accrueForClosedOrder(orderId: Long): LoyaltyAccrualResult {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val result = accrueForClosedOrder(connection, orderId)
                        connection.commit()
                        result
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun previewRedemptionForCart(
        venueId: Long,
        userId: Long,
        items: List<LoyaltyCartItem>,
    ): LoyaltyRedemptionPreview? {
        if (items.isEmpty()) return null
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val program = selectActiveProgram(connection, venueId) ?: return@use null
                    val progress = selectProgress(connection, program, userId, forUpdate = false) ?: return@use null
                    if (progress.rewardsAvailable <= 0) return@use null
                    val itemTypes = loadAvailableMenuItemTypes(connection, venueId, items.map { it.menuItemId }.toSet())
                    val targets = selectTargets(connection, venueId, program.id, LOYALTY_REWARD_TARGETS_TABLE)
                    items
                        .filter { it.qty > 0 && it.priceMinor > 0L }
                        .mapNotNull { item ->
                            val effectiveType = itemTypes[item.menuItemId] ?: return@mapNotNull null
                            item.takeIf { matchesLoyaltyTarget(targets, item.menuItemId, effectiveType) }
                        }
                        .minWithOrNull(compareBy<LoyaltyCartItem> { it.priceMinor }.thenBy { it.menuItemId })
                        ?.let { selected ->
                            LoyaltyRedemptionPreview(
                                lineId = selected.lineId,
                                menuItemId = selected.menuItemId,
                                itemName = selected.itemName,
                                discountMinor = selected.priceMinor,
                                currency = selected.currency,
                            )
                        }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    fun accrueForClosedOrder(
        connection: Connection,
        orderId: Long,
    ): LoyaltyAccrualResult {
        val program = selectActiveProgramForClosedOrder(connection, orderId)
            ?: return LoyaltyAccrualResult(orderId = orderId, itemsCounted = 0, progressAdded = 0, rewardsAdded = 0)
        val rows = loadEligibleEarnRows(connection, orderId, program.id)
        val paidRequired = paidRequiredForReward(program.nthValue)
        var countedItems = 0
        var progressAdded = 0
        var rewardsAdded = 0
        rows.forEach { row ->
            val dedupeKey = "loyalty:earn:program:${program.id}:item:${row.orderBatchItemId}"
            if (ledgerExists(connection, dedupeKey)) {
                return@forEach
            }
            val progress = lockOrCreateProgress(connection, program, row.userId)
            val nextTotal = progress.progressCount + row.qty
            val earnedRewards = nextTotal / paidRequired
            val nextProgress = nextTotal % paidRequired
            insertLedger(
                connection = connection,
                program = program,
                row = row,
                deltaProgress = row.qty,
                deltaRewards = earnedRewards,
                dedupeKey = dedupeKey,
            )
            updateProgress(
                connection = connection,
                program = program,
                userId = row.userId,
                progressCount = nextProgress,
                rewardsDelta = earnedRewards,
            )
            countedItems += 1
            progressAdded += row.qty
            rewardsAdded += earnedRewards
        }
        return LoyaltyAccrualResult(
            orderId = orderId,
            itemsCounted = countedItems,
            progressAdded = progressAdded,
            rewardsAdded = rewardsAdded,
        )
    }

    fun applyRedemptionForBatch(
        connection: Connection,
        orderId: Long,
        batchId: Long,
        venueId: Long,
        userId: Long,
        checkoutItems: List<LoyaltyCheckoutItem>,
    ): LoyaltyRedemptionResult? {
        if (checkoutItems.isEmpty()) return null
        val program = selectActiveProgram(connection, venueId) ?: return null
        if (redemptionExistsForOrder(connection, program.id, userId, orderId)) return null
        val progress = selectProgress(connection, program, userId, forUpdate = true) ?: return null
        if (progress.rewardsAvailable <= 0) return null
        val targets = selectTargets(connection, venueId, program.id, LOYALTY_REWARD_TARGETS_TABLE)
        val selected =
            checkoutItems
                .filter { it.qty > 0 && it.priceMinor > 0L }
                .filter { item -> matchesLoyaltyTarget(targets, item.menuItemId, item.effectiveType) }
                .minWithOrNull(compareBy<LoyaltyCheckoutItem> { it.priceMinor }.thenBy { it.orderBatchItemId })
                ?: return null
        if (promotionAdjustmentExists(connection, selected.orderBatchItemId)) return null
        val dedupeKey = "loyalty:redeem:program:${program.id}:order:$orderId"
        if (ledgerExists(connection, dedupeKey) || redemptionExistsByDedupeKey(connection, dedupeKey)) return null

        val ledgerRule = ensureLoyaltyLedgerRule(connection, program)
        val applicationId =
            insertLoyaltyPromotionApplication(
                connection = connection,
                orderId = orderId,
                batchId = batchId,
                venueId = venueId,
                userId = userId,
                promotionId = ledgerRule.promotionId,
                ruleId = ledgerRule.ruleId,
                discountMinor = selected.priceMinor,
                currency = selected.currency,
                dedupeKey = dedupeKey,
            )
        insertLoyaltyPromotionAdjustment(
            connection = connection,
            applicationId = applicationId,
            item = selected,
        )
        insertLoyaltyRedemption(
            connection = connection,
            program = program,
            userId = userId,
            orderId = orderId,
            batchId = batchId,
            orderBatchItemId = selected.orderBatchItemId,
            applicationId = applicationId,
            discountMinor = selected.priceMinor,
            dedupeKey = dedupeKey,
        )
        decrementAvailableReward(connection, program, userId)
        insertRedemptionLedger(
            connection = connection,
            program = program,
            userId = userId,
            orderId = orderId,
            batchId = batchId,
            orderBatchItemId = selected.orderBatchItemId,
            dedupeKey = dedupeKey,
        )
        return LoyaltyRedemptionResult(
            redeemedOrderBatchItemId = selected.orderBatchItemId,
            discount =
                CreatedOrderPromotionDiscount(
                    label = LOYALTY_REDEMPTION_LABEL,
                    discountMinor = selected.priceMinor,
                    currency = selected.currency,
                    ruleType = LOYALTY_RULE_TYPE,
                ),
        )
    }

    private fun insertDefaultEarnTarget(
        connection: Connection,
        programId: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO loyalty_program_earn_targets (program_id, target_type, semantic_type)
            VALUES (?, 'CATEGORY_TYPE', 'HOOKAH')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, programId)
            statement.executeUpdate()
        }
    }

    private fun loadAvailableMenuItemTypes(
        connection: Connection,
        venueId: Long,
        menuItemIds: Set<Long>,
    ): Map<Long, MenuSemanticType?> {
        if (menuItemIds.isEmpty()) return emptyMap()
        val placeholders = menuItemIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            SELECT mi.id,
                   COALESCE(mi.item_type, mc.category_type, 'OTHER') AS effective_type
            FROM menu_items mi
            LEFT JOIN menu_categories mc ON mc.id = mi.category_id
            WHERE mi.venue_id = ?
              AND mi.is_available = TRUE
              AND mi.id IN ($placeholders)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            menuItemIds.forEachIndexed { index, itemId -> statement.setLong(index + 2, itemId) }
            statement.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        put(rs.getLong("id"), MenuSemanticType.fromDb(rs.getString("effective_type")))
                    }
                }
            }
        }
    }

    private fun matchesLoyaltyTarget(
        targets: List<LoyaltyProgramTarget>,
        menuItemId: Long,
        effectiveType: MenuSemanticType?,
    ): Boolean =
        targets.any { target ->
            when (target.targetType) {
                LoyaltyProgramTargetType.CATEGORY_TYPE -> target.semanticType != null && target.semanticType == effectiveType
                LoyaltyProgramTargetType.MENU_ITEM -> target.menuItemId == menuItemId
            }
        }

    private fun insertDefaultRewardTarget(
        connection: Connection,
        programId: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO loyalty_program_reward_targets (program_id, target_type, semantic_type)
            VALUES (?, 'CATEGORY_TYPE', 'HOOKAH')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, programId)
            statement.executeUpdate()
        }
    }

    private fun ensureDefaultTargets(
        connection: Connection,
        programId: Long,
    ) {
        ensureDefaultTarget(connection, LOYALTY_EARN_TARGETS_TABLE, programId)
        ensureDefaultTarget(connection, LOYALTY_REWARD_TARGETS_TABLE, programId)
    }

    private fun ensureDefaultTarget(
        connection: Connection,
        tableName: String,
        programId: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO $tableName (program_id, target_type, semantic_type)
            SELECT ?, 'CATEGORY_TYPE', 'HOOKAH'
            WHERE NOT EXISTS (
                SELECT 1
                FROM $tableName
                WHERE program_id = ?
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, programId)
            statement.setLong(2, programId)
            statement.executeUpdate()
        }
    }

    private suspend fun listTargets(
        venueId: Long,
        programId: Long,
        tableName: String,
    ): List<LoyaltyProgramTarget> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    selectTargets(connection, venueId, programId, tableName)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun selectTargets(
        connection: Connection,
        venueId: Long,
        programId: Long,
        tableName: String,
    ): List<LoyaltyProgramTarget> =
        connection.prepareStatement(
            """
            SELECT
                target.id,
                target.program_id,
                target.target_type,
                target.semantic_type,
                target.menu_item_id,
                mi.name AS menu_item_name
            FROM $tableName target
            JOIN loyalty_programs lp ON lp.id = target.program_id
            LEFT JOIN menu_items mi ON mi.id = target.menu_item_id
            WHERE lp.venue_id = ?
              AND target.program_id = ?
            ORDER BY target.target_type, mi.name, target.id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, programId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toLoyaltyTarget())
                }
            }
        }

    private suspend fun replaceTargetsWithAllHookahs(
        venueId: Long,
        programId: Long,
        tableName: String,
    ): LoyaltyProgram? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val program = selectProgram(connection, venueId, programId)
                        if (program == null) {
                            connection.rollback()
                            return@use null
                        }
                        if (program.status == LoyaltyProgramStatus.ARCHIVED) {
                            connection.rollback()
                            return@use null
                        }
                        connection.prepareStatement("DELETE FROM $tableName WHERE program_id = ?").use { statement ->
                            statement.setLong(1, programId)
                            statement.executeUpdate()
                        }
                        ensureDefaultTarget(connection, tableName, programId)
                        touchProgram(connection, venueId, programId)
                        val updated = selectProgram(connection, venueId, programId)
                        connection.commit()
                        updated
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

    private suspend fun replaceTargetsWithMenuItems(
        venueId: Long,
        programId: Long,
        menuItemIds: List<Long>,
        tableName: String,
    ): LoyaltyProgram? {
        val normalizedIds = menuItemIds.distinct()
        require(normalizedIds.isNotEmpty()) { "menuItemIds must not be empty" }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val program = selectProgram(connection, venueId, programId)
                        if (program == null) {
                            connection.rollback()
                            return@use null
                        }
                        if (program.status == LoyaltyProgramStatus.ARCHIVED) {
                            connection.rollback()
                            return@use null
                        }
                        if (!menuItemsBelongToVenueHookah(connection, venueId, normalizedIds)) {
                            throw IllegalArgumentException("Selected menu items must be HOOKAH items from the venue")
                        }
                        connection.prepareStatement("DELETE FROM $tableName WHERE program_id = ?").use { statement ->
                            statement.setLong(1, programId)
                            statement.executeUpdate()
                        }
                        connection.prepareStatement(
                            """
                            INSERT INTO $tableName (program_id, target_type, semantic_type, menu_item_id)
                            VALUES (?, 'MENU_ITEM', NULL, ?)
                            """.trimIndent(),
                        ).use { statement ->
                            normalizedIds.forEach { itemId ->
                                statement.setLong(1, programId)
                                statement.setLong(2, itemId)
                                statement.addBatch()
                            }
                            statement.executeBatch()
                        }
                        touchProgram(connection, venueId, programId)
                        val updated = selectProgram(connection, venueId, programId)
                        connection.commit()
                        updated
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

    private fun menuItemsBelongToVenueHookah(
        connection: Connection,
        venueId: Long,
        menuItemIds: List<Long>,
    ): Boolean {
        val placeholders = menuItemIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            SELECT COUNT(*) AS matched_count
            FROM menu_items mi
            JOIN menu_categories mc ON mc.id = mi.category_id
            WHERE mi.venue_id = ?
              AND mi.id IN ($placeholders)
              AND COALESCE(mi.item_type, mc.category_type, 'OTHER') = 'HOOKAH'
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            menuItemIds.forEachIndexed { index, itemId -> statement.setLong(index + 2, itemId) }
            statement.executeQuery().use { rs ->
                rs.next() && rs.getInt("matched_count") == menuItemIds.size
            }
        }
    }

    private fun touchProgram(
        connection: Connection,
        venueId: Long,
        programId: Long,
    ) {
        connection.prepareStatement(
            """
            UPDATE loyalty_programs
            SET updated_at = CURRENT_TIMESTAMP
            WHERE venue_id = ? AND id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, programId)
            statement.executeUpdate()
        }
    }

    private fun selectProgram(
        connection: Connection,
        venueId: Long,
        programId: Long,
    ): LoyaltyProgram? =
        connection.prepareStatement(
            """
            SELECT ${programColumns()}
            FROM loyalty_programs lp
            LEFT JOIN venues v ON v.id = lp.venue_id
            WHERE lp.venue_id = ? AND lp.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, programId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toProgram() else null }
        }

    private fun selectFirstProgramForVenue(
        connection: Connection,
        venueId: Long,
    ): LoyaltyProgram? =
        connection.prepareStatement(
            """
            SELECT ${programColumns()}
            FROM loyalty_programs lp
            LEFT JOIN venues v ON v.id = lp.venue_id
            WHERE lp.venue_id = ?
              AND lp.status <> ?
            ORDER BY lp.id DESC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, LoyaltyProgramStatus.ARCHIVED.dbValue)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toProgram() else null }
        }

    private fun selectActiveProgram(
        connection: Connection,
        venueId: Long,
    ): LoyaltyProgram? =
        connection.prepareStatement(
            """
            SELECT ${programColumns()}
            FROM loyalty_programs lp
            LEFT JOIN venues v ON v.id = lp.venue_id
            WHERE lp.venue_id = ?
              AND lp.status = ?
            ORDER BY lp.id DESC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, LoyaltyProgramStatus.ACTIVE.dbValue)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toProgram() else null }
        }

    private fun selectActiveProgramForClosedOrder(
        connection: Connection,
        orderId: Long,
    ): LoyaltyProgram? =
        connection.prepareStatement(
            """
            SELECT ${programColumns()}
            FROM orders o
            JOIN loyalty_programs lp ON lp.venue_id = o.venue_id
            LEFT JOIN venues v ON v.id = lp.venue_id
            WHERE o.id = ?
              AND o.status = 'CLOSED'
              AND lp.status = ?
              AND lp.program_type = ?
            ORDER BY lp.id DESC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.setString(2, LoyaltyProgramStatus.ACTIVE.dbValue)
            statement.setString(3, LoyaltyProgramType.NTH_HOOKAH_FREE.dbValue)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toProgram() else null }
        }

    private fun loadEligibleEarnRows(
        connection: Connection,
        orderId: Long,
        programId: Long,
    ): List<EarnRow> =
        connection.prepareStatement(
            """
            WITH item_promos AS (
                SELECT order_batch_item_id, SUM(discount_minor) AS promo_discount_minor
                FROM order_batch_item_promotion_adjustments
                GROUP BY order_batch_item_id
            ),
            candidates AS (
                SELECT
                    o.id AS order_id,
                    o.venue_id,
                    ob.id AS batch_id,
                    obi.id AS order_batch_item_id,
                    obi.qty,
                    COALESCE(
                        gbi.user_id,
                        CASE
                            WHEN ob.author_user_id IS NOT NULL
                             AND NOT EXISTS (
                                 SELECT 1
                                 FROM venue_members vm_author
                                 WHERE vm_author.venue_id = o.venue_id
                                   AND vm_author.user_id = ob.author_user_id
                             )
                            THEN ob.author_user_id
                            ELSE NULL
                        END,
                        CASE
                            WHEN t.type = 'PERSONAL' THEN t.owner_user_id
                            ELSE NULL
                        END
                    ) AS user_id,
                    GREATEST(
                        COALESCE(mi.price_minor, 0) * obi.qty
                        - CASE
                            WHEN obi.discount_percent BETWEEN 1 AND 100
                            THEN COALESCE(mi.price_minor, 0) * obi.qty * obi.discount_percent / 100
                            ELSE 0
                          END
                        - COALESCE(item_promos.promo_discount_minor, 0),
                        0
                    ) AS payable_minor
                FROM orders o
                JOIN order_batches ob ON ob.order_id = o.id
                JOIN order_batch_items obi ON obi.order_batch_id = ob.id
                JOIN menu_items mi ON mi.id = obi.menu_item_id
                LEFT JOIN menu_categories mc ON mc.id = mi.category_id
                LEFT JOIN guest_batch_idempotency gbi
                  ON gbi.batch_id = ob.id
                 AND gbi.order_id = o.id
                LEFT JOIN tab t ON t.id = ob.tab_id
                LEFT JOIN order_promotion_reward_items opri ON opri.reward_order_batch_item_id = obi.id
                LEFT JOIN loyalty_redemptions lr
                  ON lr.order_batch_item_id = obi.id
                 AND lr.status IN ('RESERVED', 'APPLIED')
                LEFT JOIN item_promos ON item_promos.order_batch_item_id = obi.id
                WHERE o.id = ?
                  AND o.status = 'CLOSED'
                  AND ob.status NOT IN ('REJECTED', 'CANCELED', 'CANCELLED')
                  AND ob.rejected_reason_code IS NULL
                  AND ob.rejected_reason_text IS NULL
                  AND obi.is_excluded = FALSE
                  AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
                  AND opri.id IS NULL
                  AND lr.id IS NULL
                  AND COALESCE(mi.item_type, mc.category_type, 'OTHER') = 'HOOKAH'
                  AND EXISTS (
                      SELECT 1
                      FROM loyalty_program_earn_targets lpet
                      WHERE lpet.program_id = ?
                        AND (
                            (
                                lpet.target_type = 'CATEGORY_TYPE'
                                AND lpet.semantic_type = COALESCE(mi.item_type, mc.category_type, 'OTHER')
                            )
                            OR
                            (
                                lpet.target_type = 'MENU_ITEM'
                                AND lpet.menu_item_id = mi.id
                            )
                        )
                  )
            )
            SELECT order_id, venue_id, batch_id, order_batch_item_id, user_id, qty
            FROM candidates c
            WHERE c.user_id IS NOT NULL
              AND c.payable_minor > 0
              AND EXISTS (
                  SELECT 1
                  FROM users u
                  WHERE u.telegram_user_id = c.user_id
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM venue_members vm
                  WHERE vm.venue_id = c.venue_id
                    AND vm.user_id = c.user_id
              )
            ORDER BY c.order_batch_item_id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.setLong(2, programId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            EarnRow(
                                orderId = rs.getLong("order_id"),
                                venueId = rs.getLong("venue_id"),
                                batchId = rs.getLong("batch_id"),
                                orderBatchItemId = rs.getLong("order_batch_item_id"),
                                userId = rs.getLong("user_id"),
                                qty = rs.getInt("qty"),
                            ),
                        )
                    }
                }
            }
        }

    private fun lockOrCreateProgress(
        connection: Connection,
        program: LoyaltyProgram,
        userId: Long,
    ): ProgressRow {
        connection.prepareStatement(
            """
            INSERT INTO guest_loyalty_progress (
                program_id, venue_id, user_id, progress_count, rewards_available, rewards_reserved
            )
            SELECT ?, ?, ?, 0, 0, 0
            WHERE NOT EXISTS (
                SELECT 1
                FROM guest_loyalty_progress
                WHERE program_id = ? AND user_id = ?
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, program.id)
            statement.setLong(2, program.venueId)
            statement.setLong(3, userId)
            statement.setLong(4, program.id)
            statement.setLong(5, userId)
            statement.executeUpdate()
        }
        return connection.prepareStatement(
            """
            SELECT progress_count, rewards_available, rewards_reserved
            FROM guest_loyalty_progress
            WHERE program_id = ? AND user_id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, program.id)
            statement.setLong(2, userId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) throw SQLException("Missing loyalty progress row")
                ProgressRow(
                    progressCount = rs.getInt("progress_count"),
                    rewardsAvailable = rs.getInt("rewards_available"),
                    rewardsReserved = rs.getInt("rewards_reserved"),
                )
            }
        }
    }

    private fun selectProgress(
        connection: Connection,
        program: LoyaltyProgram,
        userId: Long,
        forUpdate: Boolean,
    ): ProgressRow? {
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT progress_count, rewards_available, rewards_reserved
            FROM guest_loyalty_progress
            WHERE program_id = ? AND user_id = ?
            $suffix
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, program.id)
            statement.setLong(2, userId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    ProgressRow(
                        progressCount = rs.getInt("progress_count"),
                        rewardsAvailable = rs.getInt("rewards_available"),
                        rewardsReserved = rs.getInt("rewards_reserved"),
                    )
                }
            }
        }
    }

    private fun redemptionExistsForOrder(
        connection: Connection,
        programId: Long,
        userId: Long,
        orderId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT 1
            FROM loyalty_redemptions
            WHERE program_id = ?
              AND user_id = ?
              AND order_id = ?
              AND status IN ('RESERVED', 'APPLIED')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, programId)
            statement.setLong(2, userId)
            statement.setLong(3, orderId)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun redemptionExistsByDedupeKey(
        connection: Connection,
        dedupeKey: String,
    ): Boolean =
        connection.prepareStatement("SELECT 1 FROM loyalty_redemptions WHERE dedupe_key = ?").use { statement ->
            statement.setString(1, dedupeKey)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun promotionAdjustmentExists(
        connection: Connection,
        orderBatchItemId: Long,
    ): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM order_batch_item_promotion_adjustments WHERE order_batch_item_id = ?",
        ).use { statement ->
            statement.setLong(1, orderBatchItemId)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun ledgerExists(
        connection: Connection,
        dedupeKey: String,
    ): Boolean =
        connection.prepareStatement("SELECT 1 FROM guest_loyalty_ledger WHERE dedupe_key = ?").use { statement ->
            statement.setString(1, dedupeKey)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun insertLedger(
        connection: Connection,
        program: LoyaltyProgram,
        row: EarnRow,
        deltaProgress: Int,
        deltaRewards: Int,
        dedupeKey: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO guest_loyalty_ledger (
                program_id,
                venue_id,
                user_id,
                event_type,
                delta_progress,
                delta_rewards,
                order_id,
                batch_id,
                order_batch_item_id,
                dedupe_key
            )
            VALUES (?, ?, ?, 'EARN', ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, program.id)
            statement.setLong(2, program.venueId)
            statement.setLong(3, row.userId)
            statement.setInt(4, deltaProgress)
            statement.setInt(5, deltaRewards)
            statement.setLong(6, row.orderId)
            statement.setLong(7, row.batchId)
            statement.setLong(8, row.orderBatchItemId)
            statement.setString(9, dedupeKey)
            statement.executeUpdate()
        }
    }

    private fun insertRedemptionLedger(
        connection: Connection,
        program: LoyaltyProgram,
        userId: Long,
        orderId: Long,
        batchId: Long,
        orderBatchItemId: Long,
        dedupeKey: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO guest_loyalty_ledger (
                program_id,
                venue_id,
                user_id,
                event_type,
                delta_progress,
                delta_rewards,
                order_id,
                batch_id,
                order_batch_item_id,
                dedupe_key
            )
            VALUES (?, ?, ?, 'ADJUST', 0, -1, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, program.id)
            statement.setLong(2, program.venueId)
            statement.setLong(3, userId)
            statement.setLong(4, orderId)
            statement.setLong(5, batchId)
            statement.setLong(6, orderBatchItemId)
            statement.setString(7, dedupeKey)
            statement.executeUpdate()
        }
    }

    private fun decrementAvailableReward(
        connection: Connection,
        program: LoyaltyProgram,
        userId: Long,
    ) {
        val updated =
            connection.prepareStatement(
                """
                UPDATE guest_loyalty_progress
                SET rewards_available = rewards_available - 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE program_id = ?
                  AND user_id = ?
                  AND rewards_available > 0
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, program.id)
                statement.setLong(2, userId)
                statement.executeUpdate()
            }
        if (updated == 0) {
            throw SQLException("No loyalty reward available for redemption")
        }
    }

    private fun ensureLoyaltyLedgerRule(
        connection: Connection,
        program: LoyaltyProgram,
    ): LoyaltyLedgerRule {
        val title = "$LOYALTY_SYSTEM_PROMOTION_TITLE ${program.id}"
        connection.prepareStatement(
            """
            SELECT vp.id AS promotion_id, pr.id AS rule_id
            FROM venue_promotions vp
            JOIN promotion_rules pr ON pr.promotion_id = vp.id
            WHERE vp.venue_id = ?
              AND vp.title = ?
              AND vp.template_type = ?
              AND pr.rule_type = ?
            ORDER BY pr.id DESC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, program.venueId)
            statement.setString(2, title)
            statement.setString(3, "LOYALTY_NTH_HOOKAH")
            statement.setString(4, LOYALTY_RULE_TYPE)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    return LoyaltyLedgerRule(
                        promotionId = rs.getLong("promotion_id"),
                        ruleId = rs.getLong("rule_id"),
                    )
                }
            }
        }
        val promotionId =
            connection.prepareStatement(
                """
                INSERT INTO venue_promotions (
                    venue_id,
                    title,
                    description,
                    terms,
                    status,
                    created_by_user_id,
                    template_type
                )
                VALUES (?, ?, 'Системная запись для списаний лояльности.', NULL, 'ARCHIVED', ?, 'LOYALTY_NTH_HOOKAH')
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, program.venueId)
                statement.setString(2, title)
                statement.setLong(3, program.createdByUserId)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    if (keys.next()) keys.getLong(1) else throw SQLException("Failed to create loyalty promotion shell")
                }
            }
        val ruleId =
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
                VALUES (?, ?, ?, 'CATEGORY_TYPE', 'HOOKAH', NULL, NULL, NULL, NULL, 'ARCHIVED', 100, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, promotionId)
                statement.setLong(2, program.venueId)
                statement.setString(3, LOYALTY_RULE_TYPE)
                statement.setLong(4, program.createdByUserId)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    if (keys.next()) keys.getLong(1) else throw SQLException("Failed to create loyalty promotion rule")
                }
            }
        return LoyaltyLedgerRule(promotionId = promotionId, ruleId = ruleId)
    }

    private fun insertLoyaltyPromotionApplication(
        connection: Connection,
        orderId: Long,
        batchId: Long,
        venueId: Long,
        userId: Long,
        promotionId: Long,
        ruleId: Long,
        discountMinor: Long,
        currency: String,
        dedupeKey: String,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO order_promotion_applications (
                order_id,
                batch_id,
                venue_id,
                user_id,
                promotion_id,
                rule_id,
                title_snapshot,
                rule_type,
                target_type,
                target_value,
                discount_percent,
                discount_total_minor,
                currency,
                dedupe_key
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CATEGORY_TYPE', 'HOOKAH', 100, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.setLong(2, batchId)
            statement.setLong(3, venueId)
            statement.setLong(4, userId)
            statement.setLong(5, promotionId)
            statement.setLong(6, ruleId)
            statement.setString(7, LOYALTY_REDEMPTION_LABEL)
            statement.setString(8, LOYALTY_RULE_TYPE)
            statement.setLong(9, discountMinor)
            statement.setString(10, currency)
            statement.setString(11, dedupeKey)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (keys.next()) keys.getLong(1) else throw SQLException("Failed to create loyalty promotion application")
            }
        }

    private fun insertLoyaltyPromotionAdjustment(
        connection: Connection,
        applicationId: Long,
        item: LoyaltyCheckoutItem,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO order_batch_item_promotion_adjustments (
                application_id,
                order_batch_item_id,
                menu_item_id,
                discount_minor,
                discount_percent,
                original_price_minor,
                quantity,
                currency
            )
            VALUES (?, ?, ?, ?, 100, ?, 1, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, applicationId)
            statement.setLong(2, item.orderBatchItemId)
            statement.setLong(3, item.menuItemId)
            statement.setLong(4, item.priceMinor)
            statement.setLong(5, item.priceMinor)
            statement.setString(6, item.currency)
            statement.executeUpdate()
        }
    }

    private fun insertLoyaltyRedemption(
        connection: Connection,
        program: LoyaltyProgram,
        userId: Long,
        orderId: Long,
        batchId: Long,
        orderBatchItemId: Long,
        applicationId: Long,
        discountMinor: Long,
        dedupeKey: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO loyalty_redemptions (
                program_id,
                venue_id,
                user_id,
                order_id,
                batch_id,
                order_batch_item_id,
                order_promotion_application_id,
                status,
                discount_minor,
                dedupe_key
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, 'APPLIED', ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, program.id)
            statement.setLong(2, program.venueId)
            statement.setLong(3, userId)
            statement.setLong(4, orderId)
            statement.setLong(5, batchId)
            statement.setLong(6, orderBatchItemId)
            statement.setLong(7, applicationId)
            statement.setLong(8, discountMinor)
            statement.setString(9, dedupeKey)
            statement.executeUpdate()
        }
    }

    private fun updateProgress(
        connection: Connection,
        program: LoyaltyProgram,
        userId: Long,
        progressCount: Int,
        rewardsDelta: Int,
    ) {
        connection.prepareStatement(
            """
            UPDATE guest_loyalty_progress
            SET progress_count = ?,
                rewards_available = rewards_available + ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE program_id = ? AND user_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, progressCount)
            statement.setInt(2, rewardsDelta)
            statement.setLong(3, program.id)
            statement.setLong(4, userId)
            statement.executeUpdate()
        }
    }

    private fun programColumns(): String =
        """
        lp.id,
        lp.venue_id,
        v.name AS venue_name,
        lp.program_type,
        lp.status,
        lp.nth_value,
        lp.max_redemptions_per_visit,
        lp.created_by_user_id,
        lp.created_at,
        lp.updated_at
        """.trimIndent()

    private fun ResultSet.toPrograms(): List<LoyaltyProgram> =
        buildList {
            while (next()) add(toProgram())
        }

    private fun ResultSet.toProgram(): LoyaltyProgram =
        LoyaltyProgram(
            id = getLong("id"),
            venueId = getLong("venue_id"),
            venueName = getString("venue_name"),
            programType = LoyaltyProgramType.fromDb(getString("program_type")) ?: LoyaltyProgramType.NTH_HOOKAH_FREE,
            status = LoyaltyProgramStatus.fromDb(getString("status")) ?: LoyaltyProgramStatus.DRAFT,
            nthValue = getInt("nth_value"),
            maxRedemptionsPerVisit = getInt("max_redemptions_per_visit"),
            createdByUserId = getLong("created_by_user_id"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    private fun ResultSet.toGuestProgress(): GuestLoyaltyProgress =
        GuestLoyaltyProgress(
            programId = getLong("program_id"),
            venueId = getLong("venue_id"),
            venueName = getString("venue_name"),
            userId = getLong("user_id"),
            nthValue = getInt("nth_value"),
            progressCount = getInt("progress_count"),
            rewardsAvailable = getInt("rewards_available"),
            rewardsReserved = getInt("rewards_reserved"),
            updatedAt = getTimestamp("updated_at")?.toInstant(),
        )

    private fun ResultSet.toLoyaltyTarget(): LoyaltyProgramTarget {
        val menuItemId = getLong("menu_item_id").let { if (wasNull()) null else it }
        return LoyaltyProgramTarget(
            id = getLong("id"),
            programId = getLong("program_id"),
            targetType =
                LoyaltyProgramTargetType.fromDb(getString("target_type"))
                    ?: LoyaltyProgramTargetType.CATEGORY_TYPE,
            semanticType = MenuSemanticType.fromDb(getString("semantic_type")),
            menuItemId = menuItemId,
            menuItemName = getString("menu_item_name"),
        )
    }

    private fun validateNthValue(nthValue: Int) {
        require(nthValue in 2..50) { "nth_value must be between 2 and 50" }
    }

    private fun paidRequiredForReward(nthValue: Int): Int = (nthValue - 1).coerceAtLeast(1)

    private data class EarnRow(
        val orderId: Long,
        val venueId: Long,
        val batchId: Long,
        val orderBatchItemId: Long,
        val userId: Long,
        val qty: Int,
    )

    private data class ProgressRow(
        val progressCount: Int,
        val rewardsAvailable: Int,
        val rewardsReserved: Int,
    )

    private data class LoyaltyLedgerRule(
        val promotionId: Long,
        val ruleId: Long,
    )

    private companion object {
        const val LOYALTY_EARN_TARGETS_TABLE = "loyalty_program_earn_targets"
        const val LOYALTY_REWARD_TARGETS_TABLE = "loyalty_program_reward_targets"
        const val LOYALTY_RULE_TYPE = "LOYALTY_NTH_HOOKAH"
        const val LOYALTY_REDEMPTION_LABEL = "Лояльность: бесплатный кальян"
        const val LOYALTY_SYSTEM_PROMOTION_TITLE = "[SYSTEM] Loyalty program"
    }
}
