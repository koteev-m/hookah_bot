package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter
import com.hookah.platform.backend.miniapp.venue.VenueRoleMapping
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingSale
import com.hookah.platform.backend.miniapp.venue.menu.MenuSemanticType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
    val status: String = "ACTIVE",
    val cycles: List<LoyaltyCycleSummary> = emptyList(),
    val rewardConditions: List<LoyaltyRewardCondition> = emptyList(),
    val rewardsApplied: Int = 0,
    val legacyProgress: Int = 0,
    val legacyAvailable: Int = 0,
    val legacyUnresolvedProgress: Int = 0,
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

class LoyaltyRepository(
    private val dataSource: DataSource?,
    audit: TransactionalAuditLogWriter =
        AuditLogRepository(
            dataSource,
        ),
) {
    private val paid = LoyaltyPaidLifecycle(audit)

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
                        lockVenue(connection, venueId)
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
                        val created =
                            selectProgram(connection, venueId, id)
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
                        lockVenue(connection, venueId)
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
                        val program =
                            selectProgram(connection, venueId, programId)
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
                        lockVenue(connection, venueId)
                        val existing = selectProgram(connection, venueId, programId)
                        if (existing == null || existing.status == status) {
                            connection.commit()
                            return@use existing
                        }
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
                            lp.status AS program_status,
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
                          AND lp.status IN ('ACTIVE', 'PAUSED', 'ARCHIVED')
                        ORDER BY CASE WHEN lp.status = 'ACTIVE' THEN 0 ELSE 1 END, lp.id DESC
                        LIMIT 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.setLong(2, userId)
                        statement.setLong(3, venueId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                enrichGuestProgress(
                                    connection,
                                    rs.toGuestProgress().copy(status = rs.getString("program_status")),
                                )
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
                            lp.status AS program_status,
                            COALESCE(glp.progress_count, 0) AS progress_count,
                            COALESCE(glp.rewards_available, 0) AS rewards_available,
                            COALESCE(glp.rewards_reserved, 0) AS rewards_reserved,
                            glp.updated_at
                        FROM loyalty_programs lp
                        JOIN guest_loyalty_progress glp
                          ON glp.program_id = lp.id
                         AND glp.user_id = ?
                        LEFT JOIN venues v ON v.id = lp.venue_id
                        WHERE lp.status IN ('ACTIVE', 'PAUSED', 'ARCHIVED')
                          AND (glp.progress_count > 0 OR glp.rewards_available > 0 OR glp.rewards_reserved > 0
                               OR EXISTS (
                                   SELECT 1 FROM loyalty_redemptions lr
                                   WHERE lr.program_id = lp.id AND lr.venue_id = lp.venue_id
                                     AND lr.user_id = glp.user_id AND lr.origin = 'PAYMENT_POLICY'
                                     AND lr.status = 'APPLIED'
                               ))
                        ORDER BY glp.updated_at DESC, lp.id DESC
                        LIMIT 20
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.setLong(2, userId)
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) add(
                                    enrichGuestProgress(
                                        connection,
                                        rs.toGuestProgress().copy(status = rs.getString("program_status")),
                                    ),
                                )
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun rewardUsedInSession(
        venueId: Long,
        programId: Long,
        userId: Long,
        sessionId: Long,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { c ->
                val ownsProgram =
                    c.lq(
                        "SELECT id FROM loyalty_programs WHERE id = ? AND venue_id = ?",
                        programId,
                        venueId,
                    ) {
                        it.getLong(1)
                    }.isNotEmpty()
                ownsProgram && paid.sessionUsed(c, programId, userId, sessionId)
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
        tableSessionId: Long? = null,
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
                    if (tableSessionId != null &&
                        paid.sessionUsed(connection, program.id, userId, tableSessionId)
                    ) {
                        return@use null
                    }
                    items
                        .filter { it.qty > 0 && it.priceMinor > 0L }
                        .mapNotNull { item ->
                            val effectiveType = itemTypes[item.menuItemId] ?: return@mapNotNull null
                            item.takeIf {
                                paid.reward(
                                    connection,
                                    program.id,
                                    userId,
                                    item.menuItemId,
                                    effectiveType.dbValue,
                                ) != null
                            }
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

    // Retained for legacy callers. Operational close is not a payment authority.
    fun accrueForClosedOrder(
        connection: Connection,
        orderId: Long,
    ): LoyaltyAccrualResult {
        check(!connection.autoCommit) { "Loyalty accrual requires a transaction" }
        return LoyaltyAccrualResult(orderId, 0, 0, 0)
    }

    fun authorizeOperator(
        connection: Connection,
        venueId: Long,
        actor: Long,
        expectedRole: String,
    ) {
        lockVenue(connection, venueId)
        val role =
            connection.lq(
                "SELECT role FROM venue_members WHERE venue_id = ? AND user_id = ? FOR UPDATE",
                venueId,
                actor,
            ) {
                VenueRoleMapping.fromDb(it.getString(1))?.name
            }.singleOrNull()
        if (role !in setOf("OWNER", "MANAGER", "STAFF") ||
            role != expectedRole
        ) {
            throw com.hookah.platform.backend.api.ForbiddenException()
        }
    }

    fun reconcilePaidSale(
        connection: Connection,
        venueId: Long,
        sale: AccountingSale,
        fact: String,
        actor: Long,
    ): LoyaltyAccrualResult {
        lockVenue(connection, venueId)
        val stored =
            connection.lq(
                "SELECT s.snapshot_json FROM " +
                    "venue_accounting_sales s JOIN " +
                    "venue_accounting_shifts sh ON sh.id = " +
                    "s.shift_id WHERE s.id = ? AND sh.venue_id = ?",
                sale.id,
                venueId,
            ) {
                Json.decodeFromString<AccountingSale>(it.getString(1))
            }.singleOrNull()
        if (stored != sale) throw com.hookah.platform.backend.api.ForbiddenException()
        return paid.onSale(connection, venueId, sale, fact, actor)
    }

    fun confirmBatchDelivery(
        connection: Connection,
        venueId: Long,
        orderId: Long,
        batchIds: List<Long>,
        actor: Long,
    ) = paid.delivered(connection, venueId, orderId, batchIds, actor)

    fun reconcileOrder(
        connection: Connection,
        venueId: Long,
        orderId: Long,
        actor: Long,
    ) = paid.onOrderChanged(connection, venueId, orderId, actor)

    fun confirmRewardDelivery(
        connection: Connection,
        venueId: Long,
        orderId: Long,
        redemptionId: Long,
        actor: Long,
        requestId: String,
    ) = paid.deliveryOperation(
        connection,
        venueId,
        orderId,
        redemptionId,
        actor,
        requestId,
        "CONFIRM",
        "Подтверждена фактическая выдача наградной единицы",
    )

    fun correctRewardDelivery(
        connection: Connection,
        venueId: Long,
        orderId: Long,
        redemptionId: Long,
        actor: Long,
        requestId: String,
        reason: String,
    ) = paid.deliveryOperation(connection, venueId, orderId, redemptionId, actor, requestId, "CORRECT", reason)

    fun cancelCompensatedReward(
        connection: Connection,
        venueId: Long,
        orderId: Long,
        redemptionId: Long,
        actor: Long,
        requestId: String,
        reason: String,
    ): Boolean = paid.cancelCompensation(connection, venueId, orderId, redemptionId, actor, requestId, reason)

    fun correctPaidUnit(
        connection: Connection,
        venueId: Long,
        orderId: Long,
        unitId: Long,
        actor: Long,
        requestId: String,
        reason: String,
    ): Boolean = paid.correctUnit(connection, venueId, orderId, unitId, actor, requestId, reason)

    fun applyRedemptionForBatch(
        connection: Connection,
        orderId: Long,
        batchId: Long,
        venueId: Long,
        userId: Long,
        checkoutItems: List<LoyaltyCheckoutItem>,
    ): LoyaltyRedemptionResult? {
        check(!connection.autoCommit) { "Loyalty redemption requires a transaction" }
        if (checkoutItems.isEmpty()) return null
        lockVenue(connection, venueId)
        val program = selectActiveProgram(connection, venueId) ?: return null
        val session =
            connection.lq("SELECT table_session_id FROM orders WHERE id = ? AND venue_id = ?", orderId, venueId) {
                it.getObject(1)?.let { _ -> it.getLong(1) }
            }.singleOrNull() ?: return null
        if (paid.sessionUsed(connection, program.id, userId, session)) return null
        val progress = selectProgress(connection, program, userId, forUpdate = true) ?: return null
        if (progress.rewardsAvailable <= 0) return null
        val selected =
            checkoutItems
                .filter { it.qty > 0 && it.priceMinor > 0L }
                .filter {
                        item ->
                    paid.reward(connection, program.id, userId, item.menuItemId, item.effectiveType?.dbValue) != null
                }
                .minWithOrNull(compareBy<LoyaltyCheckoutItem> { it.priceMinor }.thenBy { it.orderBatchItemId })
                ?: return null
        if (promotionAdjustmentExists(connection, selected.orderBatchItemId)) return null
        val dedupeKey = "loyalty:reserve:batch:$batchId:user:$userId"
        val reward =
            paid.reward(
                connection,
                program.id,
                userId,
                selected.menuItemId,
                selected.effectiveType?.dbValue,
            ) ?: return null
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
        paid.reserve(
            connection, program.id, venueId, userId, orderId, batchId, selected.orderBatchItemId,
            applicationId, selected.priceMinor, dedupeKey, reward, session,
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
                LoyaltyProgramTargetType.CATEGORY_TYPE ->
                    target.semanticType != null && target.semanticType == effectiveType

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
                        lockVenue(connection, venueId)
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
                        lockVenue(connection, venueId)
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

    private fun lockVenue(
        connection: Connection,
        venueId: Long,
    ) {
        val lock = if (connection.metaData.databaseProductName == "PostgreSQL") "FOR NO KEY UPDATE" else "FOR UPDATE"
        connection.prepareStatement("SELECT id FROM venues WHERE id = ? $lock").use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs -> if (!rs.next()) throw SQLException("Loyalty venue not found") }
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
                if (keys.next()) {
                    keys.getLong(
                        1,
                    )
                } else {
                    throw SQLException("Failed to create loyalty promotion application")
                }
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

    private fun enrichGuestProgress(
        c: Connection,
        progress: GuestLoyaltyProgress,
    ): GuestLoyaltyProgress {
        fun names(
            t: LoyaltyPaidLifecycle.Terms,
            kind: String,
        ) = t.targets.filter { it.kind == kind }.map {
            if (it.type == "CATEGORY_TYPE") "Кальяны из меню" else it.name ?: "Позиция #${it.item}"
        }
        val cycles =
            c.lq(
                "SELECT id, terms_id, legacy_units FROM loyalty_accumulation_cycles " +
                    "WHERE program_id = ? AND user_id = ? AND state = 'OPEN' ORDER BY " +
                    "created_at, id",
                progress.programId,
                progress.userId,
            ) { r ->
                val t = paid.terms(c, r.getString(2))
                val qty =
                    r.getInt(3) +
                        c.lq(
                            "SELECT COUNT(*) FROM loyalty_paid_units WHERE cycle_id = ? AND active " +
                                "= TRUE",
                            r.getString(1),
                        ) { it.getInt(1) }.single()
                val origin =
                    c.lq(
                        "SELECT origin FROM loyalty_terms_versions WHERE id = ?",
                        t.id,
                    ) { it.getString(1) }.single()
                LoyaltyCycleSummary(t.nth, qty, names(t, "EARN"), origin, names(t, "REWARD"))
            }
        val versions =
            c.lq(
                "SELECT terms_id, COUNT(*) AS qty FROM loyalty_accumulation_cycles " +
                    "WHERE program_id = ? AND user_id = ? AND state = 'AVAILABLE' GROUP BY " +
                    "terms_id",
                progress.programId, progress.userId,
            ) { it.getString(1) to it.getInt(2) } +
                c.lq(
                    "SELECT terms_id, rewards_remaining FROM loyalty_legacy_balances WHERE " +
                        "program_id = ? AND user_id = ? AND rewards_remaining > 0",
                    progress.programId, progress.userId,
                ) { it.getString(1) to it.getInt(2) }
        val conditions =
            versions.map { (id, qty) ->
                val t = paid.terms(c, id)
                LoyaltyRewardCondition(
                    t.nth,
                    qty,
                    names(
                        t,
                        "REWARD",
                    ),
                    c.lq("SELECT origin FROM loyalty_terms_versions WHERE id = ?", id) {
                        it.getString(1)
                    }.single(),
                )
            }
        val legacy =
            c.lq(
                "SELECT progress_initial, rewards_remaining FROM " +
                    "loyalty_legacy_balances WHERE program_id = ? AND user_id = ?",
                progress.programId,
                progress.userId,
            ) { it.getInt(1) to it.getInt(2) }.singleOrNull()
        val heldLegacy =
            c.lq(
                "SELECT b.progress_initial, t.nth_value FROM " +
                    "loyalty_legacy_balances b JOIN " +
                    "loyalty_terms_versions t ON t.id=b.terms_id " +
                    "WHERE b.program_id=? AND b.user_id=?",
                progress.programId, progress.userId,
            ) {
                if (it.getInt(1) >= it.getInt(2) - 1) it.getInt(1) else 0
            }.singleOrNull() ?: 0
        val remainingLegacy =
            c.lq(
                "SELECT COALESCE(SUM(legacy_units),0) FROM " +
                    "loyalty_accumulation_cycles WHERE program_id " +
                    "= ? AND user_id = ? AND state = 'OPEN'",
                progress.programId,
                progress.userId,
            ) { it.getInt(1) }.single()
        return progress.copy(
            progressCount = cycles.firstOrNull()?.progressCount ?: if (heldLegacy > 0) 0 else progress.progressCount,
            legacyUnresolvedProgress = heldLegacy,
            nthValue = cycles.firstOrNull()?.nthValue ?: progress.nthValue,
            cycles = cycles,
            rewardConditions = conditions,
            rewardsApplied =
                c.lq(
                    "SELECT COUNT(*) FROM loyalty_redemptions WHERE program_id = ? AND " +
                        "user_id = ? AND origin = 'PAYMENT_POLICY' AND status = 'APPLIED'",
                    progress.programId,
                    progress.userId,
                ) { it.getInt(1) }.single(),
            legacyProgress = heldLegacy + remainingLegacy,
            legacyAvailable = legacy?.second ?: 0,
        )
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
