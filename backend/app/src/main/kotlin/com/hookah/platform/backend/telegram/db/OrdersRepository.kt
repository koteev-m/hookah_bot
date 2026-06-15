package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.analytics.AnalyticsEventRecord
import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.analytics.analyticsCorrelationPayload
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.miniapp.venue.menu.MenuSemanticType
import com.hookah.platform.backend.promotions.PromotionRuleCartItem
import com.hookah.platform.backend.promotions.PromotionRuleEngine
import com.hookah.platform.backend.telegram.ActiveOrderSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Date
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.sql.DataSource

data class OrderBatchItemInput(
    val itemId: Long,
    val qty: Int,
    val selectedOptionId: Long? = null,
    val preferenceNote: String? = null,
)

data class OrderItemSelectedOptionDetails(
    val optionId: Long? = null,
    val name: String,
    val priceDeltaMinor: Long,
)

data class OrderBatchItemDetails(
    val itemId: Long,
    val qty: Int,
    val itemName: String? = null,
    val selectedOption: OrderItemSelectedOptionDetails? = null,
    val preferenceNote: String? = null,
    val priceMinor: Long? = null,
    val currency: String? = null,
    val discountPercent: Int? = null,
    val promoDiscountMinor: Long = 0L,
    val isPromotionReward: Boolean = false,
)

data class OrderBatchDetails(
    val batchId: Long,
    val comment: String?,
    val items: List<OrderBatchItemDetails>,
)

data class ActiveOrderDetails(
    val orderId: Long,
    val status: String,
    val batches: List<OrderBatchDetails>,
    val displayNumber: Int? = null,
    val displayDate: LocalDate? = null,
    val promotionDiscounts: List<CreatedOrderPromotionDiscount> = emptyList(),
    val serviceCharges: List<OrderServiceChargeDetails> = emptyList(),
)

data class OrderServiceChargeDetails(
    val id: Long,
    val source: String,
    val sourceRequestId: Long?,
    val label: String,
    val qty: Int,
    val unitPriceMinor: Long,
    val totalMinor: Long,
    val currency: String,
)

data class CreatedOrderBatch(
    val orderId: Long,
    val batchId: Long,
    val idempotencyReplay: Boolean,
    val displayNumber: Int? = null,
    val displayDate: LocalDate? = null,
    val isFirstBatch: Boolean = true,
    val promotionDiscounts: List<CreatedOrderPromotionDiscount> = emptyList(),
    val items: List<CreatedOrderBatchItem> = emptyList(),
)

data class CreatedOrderPromotionDiscount(
    val label: String,
    val discountMinor: Long,
    val currency: String,
    val ruleType: String? = null,
)

data class CreatedOrderBatchItem(
    val itemId: Long,
    val itemName: String,
    val qty: Int,
    val selectedOption: OrderItemSelectedOptionDetails? = null,
    val preferenceNote: String? = null,
    val priceMinor: Long,
    val currency: String,
    val promoDiscountMinor: Long = 0L,
    val isPromotionReward: Boolean = false,
)

data class GuestOrderCartPreview(
    val items: List<GuestOrderCartPreviewItem>,
    val grossTotalMinor: Long,
    val promoDiscountTotalMinor: Long,
    val loyaltyDiscountTotalMinor: Long,
    val finalPayableTotalMinor: Long,
    val currency: String,
    val discounts: List<CreatedOrderPromotionDiscount>,
)

data class GuestOrderCartPreviewItem(
    val itemId: Long,
    val itemName: String,
    val qty: Int,
    val selectedOption: OrderItemSelectedOptionDetails? = null,
    val preferenceNote: String? = null,
    val priceMinor: Long,
    val currency: String,
    val lineGrossMinor: Long,
    val discountMinor: Long,
    val linePayableMinor: Long,
    val isPromotionReward: Boolean = false,
)

data class UserActiveOrderSummary(
    val orderId: Long,
    val venueId: Long,
    val venueName: String,
    val status: String,
    val tabType: String? = null,
    val items: List<UserActiveOrderItemSummary> = emptyList(),
    val displayNumber: Int? = null,
    val displayDate: LocalDate? = null,
    val promotionDiscounts: List<CreatedOrderPromotionDiscount> = emptyList(),
)

data class UserActiveOrderItemSummary(
    val itemId: Long,
    val itemName: String,
    val qty: Int,
    val selectedOption: OrderItemSelectedOptionDetails? = null,
    val preferenceNote: String? = null,
    val priceMinor: Long? = null,
    val currency: String? = null,
    val discountPercent: Int? = null,
    val promoDiscountMinor: Long = 0L,
    val isPromotionReward: Boolean = false,
)

private data class ActiveOrderHeader(
    val orderId: Long,
    val status: String,
    val displayNumber: Int?,
    val displayDate: LocalDate?,
)

private data class OrderDisplay(
    val displayNumber: Int?,
    val displayDate: LocalDate?,
)

class OrdersRepository(
    private val dataSource: DataSource?,
    private val analyticsEventRepository: AnalyticsEventRepository? = null,
    private val promotionApplicationRepository: PromotionApplicationRepository? = null,
    private val venuePromotionRuleRepository: VenuePromotionRuleRepository? = null,
    private val loyaltyRepository: LoyaltyRepository? = null,
) {
    suspend fun findActiveOrderId(tableSessionId: Long): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT id FROM orders WHERE table_session_id = ? AND status = 'ACTIVE'",
                ).use { statement ->
                    statement.setLong(1, tableSessionId)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                }
            }
        }
    }

    suspend fun findActiveOrderSummary(tableSessionId: Long): ActiveOrderSummary? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT id, status, display_number, display_date FROM orders WHERE " +
                        "table_session_id = ? AND status = 'ACTIVE'",
                ).use { statement ->
                    statement.setLong(1, tableSessionId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            ActiveOrderSummary(
                                id = rs.getLong("id"),
                                status = rs.getString("status"),
                                displayNumber =
                                    rs.getInt("display_number").let {
                                            value ->
                                        if (rs.wasNull()) null else value
                                    },
                                displayDate = rs.getDate("display_date")?.toLocalDate(),
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }
    }

    suspend fun findActiveOrderSummaryForTab(
        tableSessionId: Long,
        tabId: Long,
    ): ActiveOrderSummary? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT o.id, o.status, o.display_number, o.display_date
                    FROM orders o
                    WHERE o.table_session_id = ?
                      AND o.status = 'ACTIVE'
                      AND EXISTS (
                        SELECT 1
                        FROM order_batches ob
                        WHERE ob.order_id = o.id
                          AND ob.tab_id = ?
                      )
                    ORDER BY o.id DESC
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, tableSessionId)
                    statement.setLong(2, tabId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            ActiveOrderSummary(
                                id = rs.getLong("id"),
                                status = rs.getString("status"),
                                displayNumber =
                                    rs.getInt("display_number").let {
                                            value ->
                                        if (rs.wasNull()) null else value
                                    },
                                displayDate = rs.getDate("display_date")?.toLocalDate(),
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }
    }

    suspend fun findActiveOrderDetails(tableSessionId: Long): ActiveOrderDetails? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val order =
                        connection.prepareStatement(
                            "SELECT id, status, display_number, display_date FROM orders WHERE " +
                                "table_session_id = ? AND status = 'ACTIVE'",
                        ).use { statement ->
                            statement.setLong(1, tableSessionId)
                            statement.executeQuery().use { rs ->
                                if (rs.next()) {
                                    ActiveOrderHeader(
                                        orderId = rs.getLong("id"),
                                        status = rs.getString("status"),
                                        displayNumber =
                                            rs.getInt("display_number").let {
                                                    value ->
                                                if (rs.wasNull()) null else value
                                            },
                                        displayDate = rs.getDate("display_date")?.toLocalDate(),
                                    )
                                } else {
                                    null
                                }
                            }
                        } ?: return@use null

                    val batches =
                        connection.prepareStatement(
                            """
                            SELECT id, guest_comment
                            FROM order_batches
                            WHERE order_id = ?
                              AND status <> 'REJECTED'
                              AND status <> 'CLOSED'
                              AND rejected_reason_code IS NULL
                              AND rejected_reason_text IS NULL
                            ORDER BY id
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, order.orderId)
                            statement.executeQuery().use { rs ->
                                val result = mutableListOf<Pair<Long, String?>>()
                                while (rs.next()) {
                                    result.add(rs.getLong("id") to rs.getString("guest_comment"))
                                }
                                result
                            }
                        }

                    val itemsByBatch = loadBatchItems(connection, batches.map { it.first })

                    ActiveOrderDetails(
                        orderId = order.orderId,
                        status = order.status,
                        displayNumber = order.displayNumber,
                        displayDate = order.displayDate,
                        promotionDiscounts =
                            loadPromotionDiscountsForBatches(
                                connection,
                                order.orderId,
                                batches.map { it.first },
                            ),
                        serviceCharges = loadOrderServiceCharges(connection, order.orderId),
                        batches =
                            batches.map { (batchId, comment) ->
                                OrderBatchDetails(
                                    batchId = batchId,
                                    comment = comment,
                                    items = itemsByBatch[batchId].orEmpty(),
                                )
                            },
                    )
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findActiveOrderDetailsForTab(
        tableSessionId: Long,
        tabId: Long,
    ): ActiveOrderDetails? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val order =
                        connection.prepareStatement(
                            """
                            SELECT o.id, o.status, o.display_number, o.display_date
                            FROM orders o
                            WHERE o.table_session_id = ?
                              AND o.status = 'ACTIVE'
                              AND EXISTS (
                                SELECT 1
                                FROM order_batches ob
                                WHERE ob.order_id = o.id
                                  AND ob.tab_id = ?
                              )
                            ORDER BY o.id DESC
                            LIMIT 1
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, tableSessionId)
                            statement.setLong(2, tabId)
                            statement.executeQuery().use { rs ->
                                if (rs.next()) {
                                    ActiveOrderHeader(
                                        orderId = rs.getLong("id"),
                                        status = rs.getString("status"),
                                        displayNumber =
                                            rs.getInt("display_number").let {
                                                    value ->
                                                if (rs.wasNull()) null else value
                                            },
                                        displayDate = rs.getDate("display_date")?.toLocalDate(),
                                    )
                                } else {
                                    null
                                }
                            }
                        } ?: return@use null

                    val batches =
                        connection.prepareStatement(
                            """
                            SELECT id, guest_comment
                            FROM order_batches
                            WHERE order_id = ?
                              AND tab_id = ?
                              AND status <> 'REJECTED'
                              AND status <> 'CLOSED'
                              AND rejected_reason_code IS NULL
                              AND rejected_reason_text IS NULL
                            ORDER BY id
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, order.orderId)
                            statement.setLong(2, tabId)
                            statement.executeQuery().use { rs ->
                                val result = mutableListOf<Pair<Long, String?>>()
                                while (rs.next()) {
                                    result.add(rs.getLong("id") to rs.getString("guest_comment"))
                                }
                                result
                            }
                        }

                    if (batches.isEmpty()) {
                        return@use null
                    }

                    val itemsByBatch = loadBatchItems(connection, batches.map { it.first })
                    ActiveOrderDetails(
                        orderId = order.orderId,
                        status = order.status,
                        displayNumber = order.displayNumber,
                        displayDate = order.displayDate,
                        promotionDiscounts =
                            loadPromotionDiscountsForBatches(
                                connection,
                                order.orderId,
                                batches.map { it.first },
                            ),
                        serviceCharges = loadOrderServiceCharges(connection, order.orderId),
                        batches =
                            batches.map { (batchId, comment) ->
                                OrderBatchDetails(
                                    batchId = batchId,
                                    comment = comment,
                                    items = itemsByBatch[batchId].orEmpty(),
                                )
                            },
                    )
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listActiveOrderSummariesForUser(
        userId: Long,
        limit: Int = 20,
    ): List<UserActiveOrderSummary> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT DISTINCT o.id, o.venue_id, v.name AS venue_name, o.status, o.display_number, o.display_date
                        FROM orders o
                        JOIN venues v ON v.id = o.venue_id
                        WHERE o.status = 'ACTIVE'
                          AND EXISTS (
                              SELECT 1
                              FROM order_batches ob
                              LEFT JOIN guest_batch_idempotency gbi
                                ON gbi.batch_id = ob.id
                               AND gbi.user_id = ?
                              WHERE ob.order_id = o.id
                                AND (
                                    ob.author_user_id = ?
                                    OR gbi.user_id IS NOT NULL
                                    OR EXISTS (
                                        SELECT 1
                                        FROM tab_member tm
                                        WHERE tm.tab_id = ob.tab_id
                                          AND tm.user_id = ?
                                    )
                                )
                          )
                        ORDER BY o.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.setLong(2, userId)
                        statement.setLong(3, userId)
                        statement.setInt(4, limit)
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    val orderId = rs.getLong("id")
                                    add(
                                        UserActiveOrderSummary(
                                            orderId = orderId,
                                            venueId = rs.getLong("venue_id"),
                                            venueName = rs.getString("venue_name"),
                                            status = rs.getString("status"),
                                            tabType = loadUserTabTypeForOrder(connection, userId, orderId),
                                            items = loadOrderItemsSummaryForUser(connection, orderId, userId),
                                            displayNumber =
                                                rs.getInt("display_number").let {
                                                        value ->
                                                    if (rs.wasNull()) null else value
                                                },
                                            displayDate = rs.getDate("display_date")?.toLocalDate(),
                                            promotionDiscounts = loadPromotionDiscountsForOrder(connection, orderId),
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

    private fun loadUserTabTypeForOrder(
        connection: Connection,
        userId: Long,
        orderId: Long,
    ): String? =
        connection.prepareStatement(
            """
            SELECT t.type
            FROM order_batches ob
            JOIN tab t ON t.id = ob.tab_id
            LEFT JOIN guest_batch_idempotency gbi
              ON gbi.batch_id = ob.id
             AND gbi.user_id = ?
            WHERE ob.order_id = ?
              AND (
                  ob.author_user_id = ?
                  OR gbi.user_id IS NOT NULL
                  OR EXISTS (
                      SELECT 1
                      FROM tab_member tm
                      WHERE tm.tab_id = ob.tab_id
                        AND tm.user_id = ?
                  )
              )
            ORDER BY CASE
                WHEN t.type = 'SHARED' THEN 0
                WHEN t.type = 'PERSONAL' THEN 1
                ELSE 2
            END
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, orderId)
            statement.setLong(3, userId)
            statement.setLong(4, userId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getString("type") else null
            }
        }

    private fun loadOrderItemsSummaryForUser(
        connection: Connection,
        orderId: Long,
        userId: Long,
    ): List<UserActiveOrderItemSummary> =
        connection.prepareStatement(
            """
            SELECT
                obi.menu_item_id,
                mi.name AS item_name,
                obiop.menu_item_option_id,
                obiop.option_name_snapshot,
                obiop.price_delta_minor_snapshot,
                obi.preference_note,
                SUM(obi.qty) AS qty,
                CASE
                    WHEN mi.price_minor IS NULL THEN NULL
                    ELSE mi.price_minor + COALESCE(obiop.price_delta_minor_snapshot, 0)
                END AS price_minor,
                mi.currency,
                obi.discount_percent,
                COALESCE(SUM(promo.discount_minor), 0) AS promo_discount_minor,
                CASE WHEN opri.reward_order_batch_item_id IS NULL THEN FALSE ELSE TRUE END AS is_promotion_reward
            FROM order_batches ob
            JOIN order_batch_items obi ON obi.order_batch_id = ob.id
            LEFT JOIN menu_items mi ON mi.id = obi.menu_item_id
            LEFT JOIN order_batch_item_options obiop ON obiop.order_batch_item_id = obi.id
            LEFT JOIN (
                SELECT order_batch_item_id, SUM(discount_minor) AS discount_minor
                FROM order_batch_item_promotion_adjustments
                GROUP BY order_batch_item_id
            ) promo ON promo.order_batch_item_id = obi.id
            LEFT JOIN order_promotion_reward_items opri ON opri.reward_order_batch_item_id = obi.id
            LEFT JOIN guest_batch_idempotency gbi
              ON gbi.batch_id = ob.id
             AND gbi.user_id = ?
            WHERE ob.order_id = ?
              AND ob.status <> 'REJECTED'
              AND ob.status <> 'CLOSED'
              AND ob.rejected_reason_code IS NULL
              AND ob.rejected_reason_text IS NULL
              AND obi.is_excluded = FALSE
              AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
              AND (
                  ob.author_user_id = ?
                  OR gbi.user_id IS NOT NULL
                  OR EXISTS (
                      SELECT 1
                      FROM tab_member tm
                      WHERE tm.tab_id = ob.tab_id
                        AND tm.user_id = ?
                  )
              )
            GROUP BY
                obi.menu_item_id,
                mi.name,
                obiop.menu_item_option_id,
                obiop.option_name_snapshot,
                obiop.price_delta_minor_snapshot,
                obi.preference_note,
                mi.price_minor,
                mi.currency,
                obi.discount_percent,
                opri.reward_order_batch_item_id
            ORDER BY MIN(obi.id) ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, orderId)
            statement.setLong(3, userId)
            statement.setLong(4, userId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val itemId = rs.getLong("menu_item_id")
                        val itemName = rs.getString("item_name")?.takeIf { it.isNotBlank() } ?: "Позиция #$itemId"
                        val qty = rs.getInt("qty")
                        add(
                            UserActiveOrderItemSummary(
                                itemId = itemId,
                                itemName = itemName,
                                qty = qty,
                                selectedOption = rs.toSelectedOptionDetails(),
                                preferenceNote = rs.getString("preference_note"),
                                priceMinor =
                                    rs.getLong("price_minor").let {
                                            value ->
                                        if (rs.wasNull()) null else value
                                    },
                                currency = rs.getString("currency"),
                                discountPercent =
                                    rs.getInt("discount_percent").let {
                                            value ->
                                        if (rs.wasNull()) null else value
                                    },
                                promoDiscountMinor = rs.getLong("promo_discount_minor"),
                                isPromotionReward = rs.getBoolean("is_promotion_reward"),
                            ),
                        )
                    }
                }
            }
        }

    suspend fun getOrCreateActiveOrderId(
        tableId: Long,
        venueId: Long,
        tableSessionId: Long,
        venueZoneId: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val existing = findActiveOrderForUpdate(connection, tableSessionId)
                    if (existing != null) {
                        connection.commit()
                        return@use existing
                    }
                    val orderId =
                        try {
                            insertActiveOrder(connection, venueId, tableId, tableSessionId, venueZoneId)
                        } catch (e: SQLException) {
                            if (e.sqlState == "23505") {
                                connection.rollback()
                                val activeId = findActiveOrderForUpdate(connection, tableSessionId)
                                connection.commit()
                                return@use activeId
                            }
                            throw e
                        }
                    connection.commit()
                    orderId
                } catch (e: Exception) {
                    connection.rollback()
                    throw e
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    suspend fun createOrderBatch(
        orderId: Long,
        authorUserId: Long?,
        guestComment: String,
        tabId: Long? = null,
    ): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val sql =
                    """
                    INSERT INTO order_batches (order_id, tab_id, author_user_id, source, status, guest_comment)
                    VALUES (?, ?, ?, 'CHAT', 'NEW', ?)
                    RETURNING id
                    """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, orderId)
                    if (tabId != null) {
                        statement.setLong(2, tabId)
                    } else {
                        statement.setNull(2, java.sql.Types.BIGINT)
                    }
                    if (authorUserId != null) {
                        statement.setLong(3, authorUserId)
                    } else {
                        statement.setNull(3, java.sql.Types.BIGINT)
                    }
                    statement.setString(4, guestComment)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                }
            }
        }
    }

    suspend fun createGuestOrderBatch(
        tableId: Long,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
        idempotencyKey: String,
        tabId: Long,
        comment: String?,
        items: List<OrderBatchItemInput>,
        venueZoneId: ZoneId = ZoneId.systemDefault(),
        selectedGiftChoices: Map<Long, Long> = emptyMap(),
        skippedGiftRuleIds: Set<Long> = emptySet(),
    ): CreatedOrderBatch? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        if (!lockTable(connection, tableId)) {
                            connection.rollback()
                            return@use null
                        }
                        val checkoutMenuItems =
                            loadCheckoutMenuItems(connection, venueId, items.map { it.itemId }.toSet())
                        if (checkoutMenuItems.size != items.map { it.itemId }.toSet().size) {
                            connection.rollback()
                            return@use null
                        }
                        val selectedOptionsByKey = resolveSelectedOptions(connection, venueId, items)
                        val existing =
                            findBatchIdempotency(
                                connection = connection,
                                venueId = venueId,
                                tableSessionId = tableSessionId,
                                userId = userId,
                                idempotencyKey = idempotencyKey,
                            )
                        if (existing != null) {
                            val orderDisplay = loadOrderDisplay(connection, existing.orderId)
                            val promotionDiscounts = loadPromotionDiscountsForBatch(connection, existing.batchId)
                            connection.commit()
                            return@use CreatedOrderBatch(
                                orderId = existing.orderId,
                                batchId = existing.batchId,
                                idempotencyReplay = true,
                                displayNumber = orderDisplay.displayNumber,
                                displayDate = orderDisplay.displayDate,
                                promotionDiscounts = promotionDiscounts,
                            )
                        }

                        val existingOrderId = findActiveOrderForUpdate(connection, tableSessionId)
                        val orderId =
                            existingOrderId
                                ?: insertActiveOrder(connection, venueId, tableId, tableSessionId, venueZoneId)
                        val orderDisplay = loadOrderDisplay(connection, orderId)
                        val batchId = insertOrderBatch(connection, orderId, tabId, comment)
                        val insertedItems = insertBatchItems(connection, batchId, items, selectedOptionsByKey)
                        val loyaltyRedemption =
                            applyLoyaltyRedemptionForBatch(
                                connection = connection,
                                orderId = orderId,
                                batchId = batchId,
                                venueId = venueId,
                                userId = userId,
                                insertedItems = insertedItems,
                                checkoutMenuItems = checkoutMenuItems,
                            )
                        val promotionResult =
                            applyPromotionRulesForBatch(
                                connection = connection,
                                orderId = orderId,
                                batchId = batchId,
                                venueId = venueId,
                                userId = userId,
                                insertedItems = insertedItems,
                                checkoutMenuItems = checkoutMenuItems,
                                venueZoneId = venueZoneId,
                                selectedGiftChoices = selectedGiftChoices,
                                skippedGiftRuleIds = skippedGiftRuleIds,
                                excludedBatchItemIds = setOfNotNull(loyaltyRedemption?.redeemedOrderBatchItemId),
                            )
                        val createdItems = loadCreatedOrderBatchItems(connection, batchId)
                        insertBatchIdempotency(
                            connection = connection,
                            venueId = venueId,
                            tableSessionId = tableSessionId,
                            userId = userId,
                            idempotencyKey = idempotencyKey,
                            orderId = orderId,
                            batchId = batchId,
                        )
                        analyticsEventRepository?.append(
                            connection = connection,
                            event =
                                AnalyticsEventRecord(
                                    eventType = "batch_created",
                                    payload =
                                        analyticsCorrelationPayload(
                                            venueId = venueId,
                                            tableId = tableId,
                                            tableSessionId = tableSessionId,
                                            orderId = orderId,
                                            batchId = batchId,
                                            tabId = tabId,
                                        ),
                                    venueId = venueId,
                                    tableId = tableId,
                                    tableSessionId = tableSessionId,
                                    orderId = orderId,
                                    batchId = batchId,
                                    tabId = tabId,
                                    idempotencyKey = "batch_created:$venueId:$batchId",
                                ),
                        )
                        connection.commit()
                        CreatedOrderBatch(
                            orderId = orderId,
                            batchId = batchId,
                            idempotencyReplay = false,
                            displayNumber = orderDisplay.displayNumber,
                            displayDate = orderDisplay.displayDate,
                            isFirstBatch = existingOrderId == null,
                            promotionDiscounts = promotionResult.discounts + listOfNotNull(loyaltyRedemption?.discount),
                            items = createdItems,
                        )
                    } catch (e: SQLException) {
                        connection.rollback()
                        if (e.sqlState == "23505") {
                            findBatchIdempotencyInNewConnection(
                                ds = ds,
                                venueId = venueId,
                                tableSessionId = tableSessionId,
                                userId = userId,
                                idempotencyKey = idempotencyKey,
                            )?.let { existing ->
                                return@use CreatedOrderBatch(
                                    orderId = existing.orderId,
                                    batchId = existing.batchId,
                                    idempotencyReplay = true,
                                    displayNumber = existing.displayNumber,
                                    displayDate = existing.displayDate,
                                    promotionDiscounts = loadPromotionDiscountsForBatch(connection, existing.batchId),
                                )
                            }
                        }
                        throw e
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

    private fun lockTable(
        connection: Connection,
        tableId: Long,
    ): Boolean {
        return connection.prepareStatement(
            "SELECT id FROM venue_tables WHERE id = ? FOR UPDATE",
        ).use { statement ->
            statement.setLong(1, tableId)
            statement.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun lockVenue(
        connection: Connection,
        venueId: Long,
    ) {
        connection.prepareStatement(
            "SELECT id FROM venues WHERE id = ? FOR UPDATE",
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) error("Venue $venueId not found")
            }
        }
    }

    private fun findActiveOrderForUpdate(
        connection: Connection,
        tableSessionId: Long,
    ): Long? {
        return connection.prepareStatement(
            "SELECT id FROM orders WHERE table_session_id = ? AND status = 'ACTIVE' FOR UPDATE",
        ).use { statement ->
            statement.setLong(1, tableSessionId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else null }
        }
    }

    private fun insertActiveOrder(
        connection: Connection,
        venueId: Long,
        tableId: Long,
        tableSessionId: Long,
        venueZoneId: ZoneId = ZoneId.systemDefault(),
    ): Long {
        lockVenue(connection, venueId)
        val displayDate = LocalDate.now(venueZoneId)
        val displayNumber = nextOrderDisplayNumber(connection, venueId, displayDate)
        return connection.prepareStatement(
            """
            INSERT INTO orders (venue_id, table_id, table_session_id, status, display_number, display_date)
            VALUES (?, ?, ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.setLong(3, tableSessionId)
            statement.setInt(4, displayNumber)
            statement.setDate(5, Date.valueOf(displayDate))
            statement.executeUpdate()
            statement.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else error("Failed to create order") }
        }
    }

    private fun nextOrderDisplayNumber(
        connection: Connection,
        venueId: Long,
        displayDate: LocalDate,
    ): Int =
        connection.prepareStatement(
            """
            SELECT COALESCE(MAX(display_number), 0) + 1 AS next_number
            FROM orders
            WHERE venue_id = ?
              AND display_date = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setDate(2, Date.valueOf(displayDate))
            statement.executeQuery().use { rs -> if (rs.next()) rs.getInt("next_number") else 1 }
        }

    private fun loadOrderDisplay(
        connection: Connection,
        orderId: Long,
    ): OrderDisplay =
        connection.prepareStatement(
            """
            SELECT display_number, display_date
            FROM orders
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    OrderDisplay(
                        displayNumber = rs.getInt("display_number").let { value -> if (rs.wasNull()) null else value },
                        displayDate = rs.getDate("display_date")?.toLocalDate(),
                    )
                } else {
                    OrderDisplay(displayNumber = null, displayDate = null)
                }
            }
        }

    private fun insertOrderBatch(
        connection: Connection,
        orderId: Long,
        tabId: Long,
        comment: String?,
    ): Long {
        val sql =
            """
            INSERT INTO order_batches (order_id, tab_id, author_user_id, source, status, guest_comment)
            VALUES (?, ?, NULL, 'MINIAPP', 'NEW', ?)
            """.trimIndent()
        return connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            statement.setLong(1, orderId)
            statement.setLong(2, tabId)
            if (comment != null) {
                statement.setString(3, comment)
            } else {
                statement.setNull(3, Types.VARCHAR)
            }
            statement.executeUpdate()
            statement.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else error("Failed to create batch") }
        }
    }

    private fun insertBatchItems(
        connection: Connection,
        batchId: Long,
        items: List<OrderBatchItemInput>,
        selectedOptionsByKey: Map<OrderBatchItemInputKey, CheckoutSelectedOption> = emptyMap(),
    ): List<InsertedOrderBatchItem> {
        val insertedItems = mutableListOf<InsertedOrderBatchItem>()
        connection.prepareStatement(
            """
            INSERT INTO order_batch_items (order_batch_id, menu_item_id, qty, preference_note)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            items.forEach { item ->
                statement.setLong(1, batchId)
                statement.setLong(2, item.itemId)
                statement.setInt(3, item.qty)
                if (item.preferenceNote != null) {
                    statement.setString(4, item.preferenceNote)
                } else {
                    statement.setNull(4, Types.VARCHAR)
                }
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    if (!keys.next()) error("Failed to insert batch item")
                    insertedItems.add(
                        InsertedOrderBatchItem(
                            batchItemId = keys.getLong(1),
                            menuItemId = item.itemId,
                            qty = item.qty,
                            preferenceNote = item.preferenceNote,
                            selectedOption = selectedOptionsByKey[item.toKey()],
                        ),
                    )
                }
            }
        }
        insertedItems.forEach { inserted ->
            inserted.selectedOption?.let { option ->
                insertBatchItemSelectedOption(connection, inserted.batchItemId, option)
            }
        }
        return insertedItems
    }

    private fun insertBatchItem(
        connection: Connection,
        batchId: Long,
        item: OrderBatchItemInput,
        selectedOption: CheckoutSelectedOption? = null,
    ): InsertedOrderBatchItem =
        connection.prepareStatement(
            """
            INSERT INTO order_batch_items (order_batch_id, menu_item_id, qty, preference_note)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, batchId)
            statement.setLong(2, item.itemId)
            statement.setInt(3, item.qty)
            if (item.preferenceNote != null) {
                statement.setString(4, item.preferenceNote)
            } else {
                statement.setNull(4, Types.VARCHAR)
            }
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (!keys.next()) error("Failed to insert batch item")
                InsertedOrderBatchItem(
                    batchItemId = keys.getLong(1),
                    menuItemId = item.itemId,
                    qty = item.qty,
                    preferenceNote = item.preferenceNote,
                    selectedOption = selectedOption,
                )
                    .also { inserted ->
                        selectedOption?.let { option ->
                            insertBatchItemSelectedOption(connection, inserted.batchItemId, option)
                        }
                    }
            }
        }

    private fun insertBatchItemSelectedOption(
        connection: Connection,
        batchItemId: Long,
        option: CheckoutSelectedOption,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO order_batch_item_options (
                order_batch_item_id,
                menu_item_option_id,
                option_name_snapshot,
                price_delta_minor_snapshot
            )
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, batchItemId)
            statement.setLong(2, option.optionId)
            statement.setString(3, option.name)
            statement.setLong(4, option.priceDeltaMinor)
            statement.executeUpdate()
        }
    }

    private fun resolveSelectedOptions(
        connection: Connection,
        venueId: Long,
        items: List<OrderBatchItemInput>,
    ): Map<OrderBatchItemInputKey, CheckoutSelectedOption> {
        val selectedOptionIds = items.mapNotNull { it.selectedOptionId }.toSet()
        if (selectedOptionIds.isEmpty()) {
            return emptyMap()
        }
        val placeholders = selectedOptionIds.joinToString(",") { "?" }
        val optionsById =
            connection.prepareStatement(
                """
                SELECT id, item_id, name, price_delta_minor
                FROM menu_item_options
                WHERE venue_id = ?
                  AND is_available = TRUE
                  AND id IN ($placeholders)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                selectedOptionIds.forEachIndexed { index, optionId ->
                    statement.setLong(index + 2, optionId)
                }
                statement.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            val optionId = rs.getLong("id")
                            put(
                                optionId,
                                CheckoutSelectedOption(
                                    optionId = optionId,
                                    itemId = rs.getLong("item_id"),
                                    name =
                                        rs.getString("name")?.takeIf { it.isNotBlank() }
                                            ?: "Опция #$optionId",
                                    priceDeltaMinor = rs.getLong("price_delta_minor"),
                                ),
                            )
                        }
                    }
                }
            }
        return items
            .mapNotNull { item ->
                val optionId = item.selectedOptionId ?: return@mapNotNull null
                val option =
                    optionsById[optionId]
                        ?: throw InvalidInputException("Selected option is unavailable")
                if (option.itemId != item.itemId) {
                    throw InvalidInputException("Selected option does not belong to item")
                }
                item.toKey() to option
            }
            .toMap()
    }

    private fun loadCheckoutMenuItems(
        connection: Connection,
        venueId: Long,
        itemIds: Set<Long>,
    ): Map<Long, CheckoutMenuItem> {
        if (itemIds.isEmpty()) return emptyMap()
        val placeholders = itemIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            SELECT mi.id,
                   mi.name,
                   mi.price_minor,
                   mi.currency,
                   COALESCE(mi.item_type, mc.category_type, 'OTHER') AS effective_type
            FROM menu_items mi
            LEFT JOIN menu_categories mc ON mc.id = mi.category_id
            WHERE mi.venue_id = ?
              AND mi.is_available = TRUE
              AND mi.id IN ($placeholders)
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
                            CheckoutMenuItem(
                                itemId = itemId,
                                name = rs.getString("name")?.takeIf { it.isNotBlank() } ?: "Позиция #$itemId",
                                priceMinor = rs.getLong("price_minor"),
                                currency = rs.getString("currency")?.takeIf { it.isNotBlank() } ?: "RUB",
                                effectiveType = MenuSemanticType.fromDb(rs.getString("effective_type")),
                            ),
                        )
                    }
                }
            }
        }
    }

    suspend fun previewGuestOrderBatch(
        venueId: Long,
        userId: Long,
        items: List<OrderBatchItemInput>,
        venueZoneId: ZoneId = ZoneId.systemDefault(),
    ): GuestOrderCartPreview? {
        if (items.isEmpty()) {
            return null
        }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val itemIds = items.map { it.itemId }.toSet()
        val inputs =
            withContext(Dispatchers.IO) {
                try {
                    ds.connection.use { connection ->
                        val checkoutMenuItems = loadCheckoutMenuItems(connection, venueId, itemIds)
                        if (checkoutMenuItems.size != itemIds.size) {
                            return@use null
                        }
                        val selectedOptionsByKey = resolveSelectedOptions(connection, venueId, items)
                        val rules =
                            venuePromotionRuleRepository
                                ?.listActiveRulesForVenueAt(connection, venueId, Instant.now())
                                .orEmpty()
                        CartPreviewInputs(
                            checkoutMenuItems = checkoutMenuItems,
                            selectedOptionsByKey = selectedOptionsByKey,
                            activeRules = rules,
                        )
                    }
                } catch (e: SQLException) {
                    throw DatabaseUnavailableException()
                }
            } ?: return null

        val baseItems =
            items.mapIndexedNotNull { index, input ->
                val menuItem = inputs.checkoutMenuItems[input.itemId] ?: return@mapIndexedNotNull null
                val selectedOption = inputs.selectedOptionsByKey[input.toKey()]
                CartPreviewBaseItem(
                    lineId = index.toLong() + 1L,
                    itemId = input.itemId,
                    itemName = menuItem.name,
                    qty = input.qty,
                    selectedOption = selectedOption?.toDetails(),
                    preferenceNote = input.preferenceNote,
                    priceMinor = menuItem.effectivePriceMinor(selectedOption),
                    currency = menuItem.currency,
                    effectiveType = menuItem.effectiveType,
                )
            }
        if (baseItems.size != items.size) {
            return null
        }

        val loyaltyPreview =
            loyaltyRepository?.previewRedemptionForCart(
                venueId = venueId,
                userId = userId,
                items =
                    baseItems.map { item ->
                        LoyaltyCartItem(
                            lineId = item.lineId,
                            menuItemId = item.itemId,
                            itemName = item.itemName,
                            qty = item.qty,
                            priceMinor = item.priceMinor,
                            currency = item.currency,
                        )
                    },
            )
        val loyaltyLineIds = setOfNotNull(loyaltyPreview?.lineId)
        val promotionPreview =
            PromotionRuleEngine.preview(
                venueId = venueId,
                now = Instant.now(),
                venueZoneId = venueZoneId,
                cartItems =
                    baseItems
                        .filterNot { item -> item.lineId in loyaltyLineIds }
                        .map { item ->
                            PromotionRuleCartItem(
                                lineId = item.lineId,
                                menuItemId = item.itemId,
                                itemName = item.itemName,
                                qty = item.qty,
                                priceMinor = item.priceMinor,
                                currency = item.currency,
                                effectiveType = item.effectiveType,
                            )
                        },
                activeRules = inputs.activeRules,
            )
        val rulesById = inputs.activeRules.associateBy { it.id }
        val promoDiscounts =
            promotionPreview.adjustments
                .groupBy { adjustment ->
                    val rule = rulesById[adjustment.ruleId]
                    CartPreviewDiscountKey(
                        label = adjustment.label.takeIf { it.isNotBlank() } ?: "Акция",
                        ruleType = rule?.ruleType?.dbValue,
                        currency = adjustment.currency,
                    )
                }
                .map { (key, adjustments) ->
                    CreatedOrderPromotionDiscount(
                        label = key.label,
                        discountMinor = adjustments.sumOf { it.discountMinor },
                        currency = key.currency,
                        ruleType = key.ruleType,
                    )
                }
        val giftDiscounts =
            promotionPreview.gifts.map { gift ->
                val rule = rulesById[gift.ruleId]
                CreatedOrderPromotionDiscount(
                    label = gift.label.takeIf { it.isNotBlank() } ?: "${gift.rewardItemName} в подарок",
                    discountMinor = gift.rewardPriceMinor * gift.rewardQty.toLong(),
                    currency = gift.currency,
                    ruleType = rule?.ruleType?.dbValue,
                )
            }
        val loyaltyDiscount =
            loyaltyPreview?.let { preview ->
                CreatedOrderPromotionDiscount(
                    label = "Лояльность: бесплатный кальян",
                    discountMinor = preview.discountMinor,
                    currency = preview.currency,
                    ruleType = "LOYALTY_NTH_HOOKAH",
                )
            }
        val promoDiscountsByLine =
            promotionPreview.adjustments
                .mapNotNull { adjustment -> adjustment.lineId?.let { lineId -> lineId to adjustment.discountMinor } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, discounts) -> discounts.sum() }
        val previewItems =
            baseItems.map { item ->
                val promoDiscount = promoDiscountsByLine[item.lineId] ?: 0L
                val loyaltyDiscount = if (loyaltyPreview?.lineId == item.lineId) loyaltyPreview.discountMinor else 0L
                val discount = promoDiscount + loyaltyDiscount
                val gross = item.lineGrossMinor()
                GuestOrderCartPreviewItem(
                    itemId = item.itemId,
                    itemName = item.itemName,
                    qty = item.qty,
                    selectedOption = item.selectedOption,
                    preferenceNote = item.preferenceNote,
                    priceMinor = item.priceMinor,
                    currency = item.currency,
                    lineGrossMinor = gross,
                    discountMinor = discount,
                    linePayableMinor = (gross - discount).coerceAtLeast(0L),
                )
            } +
                promotionPreview.gifts.map { gift ->
                    val gross = gift.rewardPriceMinor * gift.rewardQty.toLong()
                    GuestOrderCartPreviewItem(
                        itemId = gift.rewardMenuItemId,
                        itemName = gift.rewardItemName,
                        qty = gift.rewardQty,
                        priceMinor = gift.rewardPriceMinor,
                        currency = gift.currency,
                        lineGrossMinor = gross,
                        discountMinor = gross,
                        linePayableMinor = 0L,
                        isPromotionReward = true,
                    )
                }
        val allDiscounts = promoDiscounts + giftDiscounts + listOfNotNull(loyaltyDiscount)
        val currency =
            previewItems.firstOrNull { it.currency.isNotBlank() }?.currency
                ?: allDiscounts.firstOrNull { it.currency.isNotBlank() }?.currency
                ?: "RUB"
        return GuestOrderCartPreview(
            items = previewItems,
            grossTotalMinor = previewItems.sumOf { it.lineGrossMinor },
            promoDiscountTotalMinor = (promoDiscounts + giftDiscounts).sumOf { it.discountMinor },
            loyaltyDiscountTotalMinor = loyaltyDiscount?.discountMinor ?: 0L,
            finalPayableTotalMinor = previewItems.sumOf { it.linePayableMinor },
            currency = currency,
            discounts = allDiscounts,
        )
    }

    private fun applyPromotionRulesForBatch(
        connection: Connection,
        orderId: Long,
        batchId: Long,
        venueId: Long,
        userId: Long,
        insertedItems: List<InsertedOrderBatchItem>,
        checkoutMenuItems: Map<Long, CheckoutMenuItem>,
        venueZoneId: ZoneId,
        selectedGiftChoices: Map<Long, Long> = emptyMap(),
        skippedGiftRuleIds: Set<Long> = emptySet(),
        excludedBatchItemIds: Set<Long> = emptySet(),
    ): PromotionRulesApplicationResult {
        val applicationRepository = promotionApplicationRepository ?: return PromotionRulesApplicationResult()
        val ruleRepository = venuePromotionRuleRepository ?: return PromotionRulesApplicationResult()
        if (insertedItems.isEmpty()) return PromotionRulesApplicationResult()
        val now = Instant.now()
        val rules = ruleRepository.listActiveRulesForVenueAt(connection, venueId, now)
        if (rules.isEmpty()) return PromotionRulesApplicationResult()
        val cartItems =
            insertedItems.mapNotNull { inserted ->
                if (inserted.batchItemId in excludedBatchItemIds) return@mapNotNull null
                val menuItem = checkoutMenuItems[inserted.menuItemId] ?: return@mapNotNull null
                PromotionRuleCartItem(
                    lineId = inserted.batchItemId,
                    menuItemId = inserted.menuItemId,
                    itemName = menuItem.name,
                    qty = inserted.qty,
                    priceMinor = menuItem.effectivePriceMinor(inserted.selectedOption),
                    currency = menuItem.currency,
                    effectiveType = menuItem.effectiveType,
                )
            }
        val preview =
            PromotionRuleEngine.preview(
                venueId = venueId,
                now = now,
                venueZoneId = venueZoneId,
                cartItems = cartItems,
                activeRules = rules,
                selectedGiftChoices = selectedGiftChoices,
                skippedGiftRuleIds = skippedGiftRuleIds,
            )
        val freshRewardItems =
            loadCheckoutMenuItems(
                connection = connection,
                venueId = venueId,
                itemIds = preview.gifts.map { it.rewardMenuItemId }.toSet(),
            )
        val eligibleGifts =
            preview.gifts.filter { gift -> freshRewardItems.containsKey(gift.rewardMenuItemId) }
        if (preview.adjustments.isEmpty() && eligibleGifts.isEmpty()) return PromotionRulesApplicationResult()
        val rulesById = rules.associateBy { it.id }
        val insertedByBatchItemId = insertedItems.associateBy { it.batchItemId }
        val percentApplications =
            preview.adjustments
                .filter { adjustment -> adjustment.lineId != null && adjustment.discountMinor > 0L }
                .groupBy { adjustment -> adjustment.ruleId to adjustment.currency }
                .mapNotNull { (key, adjustments) ->
                    val (ruleId, currency) = key
                    val rule = rulesById[ruleId] ?: return@mapNotNull null
                    val title = rule.promotionTitle?.takeIf { it.isNotBlank() } ?: "Счастливые часы"
                    val adjustmentInputs =
                        adjustments.mapNotNull { adjustment ->
                            val batchItemId = adjustment.lineId ?: return@mapNotNull null
                            val inserted = insertedByBatchItemId[batchItemId] ?: return@mapNotNull null
                            val menuItem = checkoutMenuItems[inserted.menuItemId] ?: return@mapNotNull null
                            PromotionAdjustmentInput(
                                orderBatchItemId = batchItemId,
                                menuItemId = inserted.menuItemId,
                                discountMinor = adjustment.discountMinor,
                                discountPercent = adjustment.percent,
                                originalPriceMinor = menuItem.effectivePriceMinor(inserted.selectedOption),
                                quantity = inserted.qty,
                                currency = currency,
                            )
                        }
                    if (adjustmentInputs.isEmpty()) {
                        null
                    } else {
                        PromotionApplicationInput(
                            orderId = orderId,
                            batchId = batchId,
                            venueId = venueId,
                            userId = userId,
                            promotionId = rule.promotionId,
                            ruleId = rule.id,
                            titleSnapshot = title,
                            ruleType = rule.ruleType.dbValue,
                            targetType = rule.targetType.dbValue,
                            targetValue = rule.targetValue.dbValue,
                            discountPercent = rule.discountPercent,
                            discountTotalMinor = adjustmentInputs.sumOf { it.discountMinor },
                            currency = currency,
                            dedupeKey = "batch:$batchId:rule:${rule.id}:$currency",
                            adjustments = adjustmentInputs,
                        )
                    }
                }
        val giftApplications =
            eligibleGifts.mapNotNull { gift ->
                val rule = rulesById[gift.ruleId] ?: return@mapNotNull null
                val rewardMenuItem = freshRewardItems[gift.rewardMenuItemId] ?: return@mapNotNull null
                val rewardInserted =
                    insertBatchItem(
                        connection = connection,
                        batchId = batchId,
                        item = OrderBatchItemInput(itemId = gift.rewardMenuItemId, qty = gift.rewardQty),
                    )
                val discountMinor = rewardMenuItem.priceMinor * gift.rewardQty.toLong()
                val label = gift.label.takeIf { it.isNotBlank() } ?: "${rewardMenuItem.name} в подарок"
                PromotionApplicationInput(
                    orderId = orderId,
                    batchId = batchId,
                    venueId = venueId,
                    userId = userId,
                    promotionId = rule.promotionId,
                    ruleId = rule.id,
                    titleSnapshot = label,
                    ruleType = rule.ruleType.dbValue,
                    targetType = rule.targetType.dbValue,
                    targetValue = rule.targetValue.dbValue,
                    discountPercent = null,
                    discountTotalMinor = discountMinor,
                    currency = rewardMenuItem.currency,
                    dedupeKey =
                        "batch:$batchId:rule:${rule.id}:gift:${gift.rewardMenuItemId}:" +
                            "${rewardMenuItem.currency}",
                    adjustments =
                        listOf(
                            PromotionAdjustmentInput(
                                orderBatchItemId = rewardInserted.batchItemId,
                                menuItemId = gift.rewardMenuItemId,
                                discountMinor = discountMinor,
                                discountPercent = 100,
                                originalPriceMinor = rewardMenuItem.priceMinor,
                                quantity = gift.rewardQty,
                                currency = rewardMenuItem.currency,
                            ),
                        ),
                    rewardItems =
                        listOf(
                            PromotionRewardItemInput(
                                triggerOrderBatchItemId = gift.triggerLineId,
                                rewardOrderBatchItemId = rewardInserted.batchItemId,
                                rewardMenuItemId = gift.rewardMenuItemId,
                                rewardQty = gift.rewardQty,
                                labelSnapshot = label,
                            ),
                        ),
                )
            }
        val applications = percentApplications + giftApplications
        applicationRepository.persistApplications(connection, applications)
        return PromotionRulesApplicationResult(
            discounts =
                applications.map { application ->
                    CreatedOrderPromotionDiscount(
                        label = application.titleSnapshot,
                        discountMinor = application.discountTotalMinor,
                        currency = application.currency,
                        ruleType = application.ruleType,
                    )
                },
        )
    }

    private fun applyLoyaltyRedemptionForBatch(
        connection: Connection,
        orderId: Long,
        batchId: Long,
        venueId: Long,
        userId: Long,
        insertedItems: List<InsertedOrderBatchItem>,
        checkoutMenuItems: Map<Long, CheckoutMenuItem>,
    ): LoyaltyRedemptionResult? {
        val repository = loyaltyRepository ?: return null
        val checkoutItems =
            insertedItems.mapNotNull { inserted ->
                val menuItem = checkoutMenuItems[inserted.menuItemId] ?: return@mapNotNull null
                LoyaltyCheckoutItem(
                    orderBatchItemId = inserted.batchItemId,
                    menuItemId = inserted.menuItemId,
                    itemName = menuItem.name,
                    qty = inserted.qty,
                    priceMinor = menuItem.effectivePriceMinor(inserted.selectedOption),
                    currency = menuItem.currency,
                    effectiveType = menuItem.effectiveType,
                )
            }
        return repository.applyRedemptionForBatch(
            connection = connection,
            orderId = orderId,
            batchId = batchId,
            venueId = venueId,
            userId = userId,
            checkoutItems = checkoutItems,
        )
    }

    private fun loadPromotionDiscountsForBatch(
        connection: Connection,
        batchId: Long,
    ): List<CreatedOrderPromotionDiscount> =
        connection.prepareStatement(
            """
            WITH application_discounts AS (
                SELECT
                    CASE
                        WHEN opa.rule_type = 'GIFT_WITH_ITEM' THEN COALESCE(MAX(opri.label_snapshot), opa.title_snapshot)
                        ELSE opa.title_snapshot
                    END AS promo_label,
                    opa.rule_type,
                    opa.currency,
                    COALESCE(SUM(obipa.discount_minor), 0) AS discount_minor,
                    MIN(opa.id) AS first_application_id
                FROM order_promotion_applications opa
                JOIN order_batch_item_promotion_adjustments obipa ON obipa.application_id = opa.id
                JOIN order_batch_items obi ON obi.id = obipa.order_batch_item_id
                JOIN order_batches ob ON ob.id = obi.order_batch_id
                LEFT JOIN order_promotion_reward_items opri ON opri.application_id = opa.id
                WHERE opa.batch_id = ?
                  AND ob.status <> 'REJECTED'
                  AND ob.status <> 'CLOSED'
                  AND ob.rejected_reason_code IS NULL
                  AND ob.rejected_reason_text IS NULL
                  AND obi.is_excluded = FALSE
                  AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
                GROUP BY opa.id, opa.title_snapshot, opa.rule_type, opa.currency
            )
            SELECT promo_label,
                   rule_type,
                   currency,
                   COALESCE(SUM(discount_minor), 0) AS discount_minor
            FROM application_discounts
            GROUP BY promo_label, rule_type, currency
            ORDER BY MIN(first_application_id)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, batchId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val discountMinor = rs.getLong("discount_minor")
                        if (discountMinor > 0L) {
                            add(
                                CreatedOrderPromotionDiscount(
                                    label = rs.getString("promo_label"),
                                    discountMinor = discountMinor,
                                    currency = rs.getString("currency"),
                                    ruleType = rs.getString("rule_type"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun loadPromotionDiscountsForOrder(
        connection: Connection,
        orderId: Long,
    ): List<CreatedOrderPromotionDiscount> =
        connection.prepareStatement(
            """
            WITH application_discounts AS (
                SELECT
                    CASE
                        WHEN opa.rule_type = 'GIFT_WITH_ITEM' THEN COALESCE(MAX(opri.label_snapshot), opa.title_snapshot)
                        ELSE opa.title_snapshot
                    END AS promo_label,
                    opa.rule_type,
                    opa.currency,
                    COALESCE(SUM(obipa.discount_minor), 0) AS discount_minor,
                    MIN(opa.id) AS first_application_id
                FROM order_promotion_applications opa
                JOIN order_batch_item_promotion_adjustments obipa ON obipa.application_id = opa.id
                JOIN order_batch_items obi ON obi.id = obipa.order_batch_item_id
                JOIN order_batches ob ON ob.id = obi.order_batch_id
                LEFT JOIN order_promotion_reward_items opri ON opri.application_id = opa.id
                WHERE opa.order_id = ?
                  AND ob.status <> 'REJECTED'
                  AND ob.status <> 'CLOSED'
                  AND ob.rejected_reason_code IS NULL
                  AND ob.rejected_reason_text IS NULL
                  AND obi.is_excluded = FALSE
                  AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
                GROUP BY opa.id, opa.title_snapshot, opa.rule_type, opa.currency
            )
            SELECT promo_label,
                   rule_type,
                   currency,
                   COALESCE(SUM(discount_minor), 0) AS discount_minor
            FROM application_discounts
            GROUP BY promo_label, rule_type, currency
            ORDER BY MIN(first_application_id)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val discountMinor = rs.getLong("discount_minor")
                        if (discountMinor > 0L) {
                            add(
                                CreatedOrderPromotionDiscount(
                                    label = rs.getString("promo_label"),
                                    discountMinor = discountMinor,
                                    currency = rs.getString("currency"),
                                    ruleType = rs.getString("rule_type"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun loadPromotionDiscountsForBatches(
        connection: Connection,
        orderId: Long,
        batchIds: List<Long>,
    ): List<CreatedOrderPromotionDiscount> {
        if (batchIds.isEmpty()) return emptyList()
        val placeholders = batchIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            WITH application_discounts AS (
                SELECT
                    CASE
                        WHEN opa.rule_type = 'GIFT_WITH_ITEM' THEN COALESCE(MAX(opri.label_snapshot), opa.title_snapshot)
                        ELSE opa.title_snapshot
                    END AS promo_label,
                    opa.rule_type,
                    opa.currency,
                    COALESCE(SUM(obipa.discount_minor), 0) AS discount_minor,
                    MIN(opa.id) AS first_application_id
                FROM order_promotion_applications opa
                JOIN order_batch_item_promotion_adjustments obipa ON obipa.application_id = opa.id
                JOIN order_batch_items obi ON obi.id = obipa.order_batch_item_id
                JOIN order_batches ob ON ob.id = obi.order_batch_id
                LEFT JOIN order_promotion_reward_items opri ON opri.application_id = opa.id
                WHERE opa.order_id = ?
                  AND ob.id IN ($placeholders)
                  AND ob.status <> 'REJECTED'
                  AND ob.status <> 'CLOSED'
                  AND ob.rejected_reason_code IS NULL
                  AND ob.rejected_reason_text IS NULL
                  AND obi.is_excluded = FALSE
                  AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
                GROUP BY opa.id, opa.title_snapshot, opa.rule_type, opa.currency
            )
            SELECT promo_label,
                   rule_type,
                   currency,
                   COALESCE(SUM(discount_minor), 0) AS discount_minor
            FROM application_discounts
            GROUP BY promo_label, rule_type, currency
            ORDER BY MIN(first_application_id)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, orderId)
            batchIds.forEachIndexed { index, batchId ->
                statement.setLong(index + 2, batchId)
            }
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val discountMinor = rs.getLong("discount_minor")
                        if (discountMinor > 0L) {
                            add(
                                CreatedOrderPromotionDiscount(
                                    label = rs.getString("promo_label"),
                                    discountMinor = discountMinor,
                                    currency = rs.getString("currency"),
                                    ruleType = rs.getString("rule_type"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun loadOrderServiceCharges(
        connection: Connection,
        orderId: Long,
    ): List<OrderServiceChargeDetails> =
        connection.prepareStatement(
            """
            SELECT id,
                   source,
                   source_request_id,
                   label,
                   qty,
                   unit_price_minor,
                   total_minor,
                   currency
            FROM order_service_charges
            WHERE order_id = ?
            ORDER BY created_at, id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            OrderServiceChargeDetails(
                                id = rs.getLong("id"),
                                source = rs.getString("source"),
                                sourceRequestId =
                                    rs.getLong("source_request_id").let { value ->
                                        if (rs.wasNull()) null else value
                                    },
                                label = rs.getString("label"),
                                qty = rs.getInt("qty"),
                                unitPriceMinor = rs.getLong("unit_price_minor"),
                                totalMinor = rs.getLong("total_minor"),
                                currency = rs.getString("currency"),
                            ),
                        )
                    }
                }
            }
        }

    private fun buildCreatedOrderBatchItems(
        insertedItems: List<InsertedOrderBatchItem>,
        checkoutMenuItems: Map<Long, CheckoutMenuItem>,
    ): List<CreatedOrderBatchItem> =
        insertedItems.mapNotNull { inserted ->
            val menuItem = checkoutMenuItems[inserted.menuItemId] ?: return@mapNotNull null
            CreatedOrderBatchItem(
                itemId = inserted.menuItemId,
                itemName = menuItem.name,
                qty = inserted.qty,
                selectedOption = inserted.selectedOption?.toDetails(),
                preferenceNote = inserted.preferenceNote,
                priceMinor = menuItem.priceMinor,
                currency = menuItem.currency,
            )
        }

    private fun loadCreatedOrderBatchItems(
        connection: Connection,
        batchId: Long,
    ): List<CreatedOrderBatchItem> =
        connection.prepareStatement(
            """
            SELECT obi.menu_item_id,
                   COALESCE(mi.name, 'Позиция #' || obi.menu_item_id) AS item_name,
                   obi.qty,
                   obi.preference_note,
                   obiop.menu_item_option_id,
                   obiop.option_name_snapshot,
                   obiop.price_delta_minor_snapshot,
                   COALESCE(mi.price_minor, 0) + COALESCE(obiop.price_delta_minor_snapshot, 0) AS price_minor,
                   COALESCE(mi.currency, 'RUB') AS currency,
                   COALESCE(promo.discount_minor, 0) AS promo_discount_minor,
                   CASE WHEN opri.id IS NULL THEN FALSE ELSE TRUE END AS is_promotion_reward
            FROM order_batch_items obi
            LEFT JOIN menu_items mi ON mi.id = obi.menu_item_id
            LEFT JOIN order_batch_item_options obiop ON obiop.order_batch_item_id = obi.id
            LEFT JOIN (
                SELECT order_batch_item_id, SUM(discount_minor) AS discount_minor
                FROM order_batch_item_promotion_adjustments
                GROUP BY order_batch_item_id
            ) promo ON promo.order_batch_item_id = obi.id
            LEFT JOIN order_promotion_reward_items opri ON opri.reward_order_batch_item_id = obi.id
            WHERE obi.order_batch_id = ?
              AND obi.is_excluded = FALSE
              AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
            ORDER BY obi.id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, batchId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            CreatedOrderBatchItem(
                                itemId = rs.getLong("menu_item_id"),
                                itemName = rs.getString("item_name"),
                                qty = rs.getInt("qty"),
                                selectedOption = rs.toSelectedOptionDetails(),
                                preferenceNote = rs.getString("preference_note"),
                                priceMinor = rs.getLong("price_minor"),
                                currency = rs.getString("currency"),
                                promoDiscountMinor = rs.getLong("promo_discount_minor"),
                                isPromotionReward = rs.getBoolean("is_promotion_reward"),
                            ),
                        )
                    }
                }
            }
        }

    private data class StoredBatchIdempotency(
        val orderId: Long,
        val batchId: Long,
        val displayNumber: Int?,
        val displayDate: LocalDate?,
    )

    private data class InsertedOrderBatchItem(
        val batchItemId: Long,
        val menuItemId: Long,
        val qty: Int,
        val preferenceNote: String? = null,
        val selectedOption: CheckoutSelectedOption? = null,
    )

    private data class PromotionRulesApplicationResult(
        val discounts: List<CreatedOrderPromotionDiscount> = emptyList(),
    )

    private data class CartPreviewInputs(
        val checkoutMenuItems: Map<Long, CheckoutMenuItem>,
        val selectedOptionsByKey: Map<OrderBatchItemInputKey, CheckoutSelectedOption>,
        val activeRules: List<VenuePromotionRule>,
    )

    private data class CartPreviewBaseItem(
        val lineId: Long,
        val itemId: Long,
        val itemName: String,
        val qty: Int,
        val selectedOption: OrderItemSelectedOptionDetails? = null,
        val preferenceNote: String? = null,
        val priceMinor: Long,
        val currency: String,
        val effectiveType: MenuSemanticType,
    ) {
        fun lineGrossMinor(): Long = priceMinor * qty.toLong()
    }

    private data class CartPreviewDiscountKey(
        val label: String,
        val ruleType: String?,
        val currency: String,
    )

    private data class CheckoutMenuItem(
        val itemId: Long,
        val name: String,
        val priceMinor: Long,
        val currency: String,
        val effectiveType: MenuSemanticType,
    )

    private data class OrderBatchItemInputKey(
        val itemId: Long,
        val selectedOptionId: Long?,
        val preferenceNote: String?,
    )

    private data class CheckoutSelectedOption(
        val optionId: Long,
        val itemId: Long,
        val name: String,
        val priceDeltaMinor: Long,
    ) {
        fun toDetails(): OrderItemSelectedOptionDetails =
            OrderItemSelectedOptionDetails(
                optionId = optionId,
                name = name,
                priceDeltaMinor = priceDeltaMinor,
            )
    }

    private fun findBatchIdempotency(
        connection: Connection,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
        idempotencyKey: String,
    ): StoredBatchIdempotency? {
        return connection.prepareStatement(
            """
            SELECT gbi.order_id, gbi.batch_id, o.display_number, o.display_date
            FROM guest_batch_idempotency gbi
            JOIN orders o ON o.id = gbi.order_id
            WHERE gbi.venue_id = ? AND gbi.table_session_id = ? AND gbi.user_id = ? AND gbi.idempotency_key = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableSessionId)
            statement.setLong(3, userId)
            statement.setString(4, idempotencyKey)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredBatchIdempotency(
                        orderId = rs.getLong("order_id"),
                        batchId = rs.getLong("batch_id"),
                        displayNumber = rs.getInt("display_number").let { value -> if (rs.wasNull()) null else value },
                        displayDate = rs.getDate("display_date")?.toLocalDate(),
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun insertBatchIdempotency(
        connection: Connection,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
        idempotencyKey: String,
        orderId: Long,
        batchId: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO guest_batch_idempotency (
                venue_id,
                table_session_id,
                user_id,
                idempotency_key,
                order_id,
                batch_id
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableSessionId)
            statement.setLong(3, userId)
            statement.setString(4, idempotencyKey)
            statement.setLong(5, orderId)
            statement.setLong(6, batchId)
            statement.executeUpdate()
        }
    }

    private fun findBatchIdempotencyInNewConnection(
        ds: DataSource,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
        idempotencyKey: String,
    ): StoredBatchIdempotency? {
        return ds.connection.use { lookupConnection ->
            findBatchIdempotency(
                connection = lookupConnection,
                venueId = venueId,
                tableSessionId = tableSessionId,
                userId = userId,
                idempotencyKey = idempotencyKey,
            )
        }
    }

    private fun loadBatchItems(
        connection: Connection,
        batchIds: List<Long>,
    ): Map<Long, List<OrderBatchItemDetails>> {
        if (batchIds.isEmpty()) {
            return emptyMap()
        }
        val placeholders = batchIds.joinToString(",") { "?" }
        val sql =
            """
            SELECT obi.id,
                   obi.order_batch_id,
                   obi.menu_item_id,
                   obi.qty,
                   obi.preference_note,
                   obi.discount_percent,
                   COALESCE(promo.discount_minor, 0) AS promo_discount_minor,
                   CASE WHEN opri.id IS NULL THEN FALSE ELSE TRUE END AS is_promotion_reward,
                   mi.name AS item_name,
                   obiop.menu_item_option_id,
                   obiop.option_name_snapshot,
                   obiop.price_delta_minor_snapshot,
                   CASE
                       WHEN mi.price_minor IS NULL THEN NULL
                       ELSE mi.price_minor + COALESCE(obiop.price_delta_minor_snapshot, 0)
                   END AS price_minor,
                   mi.currency
            FROM order_batch_items obi
            LEFT JOIN menu_items mi ON mi.id = obi.menu_item_id
            LEFT JOIN order_batch_item_options obiop ON obiop.order_batch_item_id = obi.id
            LEFT JOIN (
                SELECT order_batch_item_id, SUM(discount_minor) AS discount_minor
                FROM order_batch_item_promotion_adjustments
                GROUP BY order_batch_item_id
            ) promo ON promo.order_batch_item_id = obi.id
            LEFT JOIN order_promotion_reward_items opri ON opri.reward_order_batch_item_id = obi.id
            WHERE obi.order_batch_id IN ($placeholders)
              AND obi.is_excluded = FALSE
              AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
            ORDER BY obi.order_batch_id, obi.id
            """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            batchIds.forEachIndexed { index, batchId ->
                statement.setLong(index + 1, batchId)
            }
            statement.executeQuery().use { rs ->
                val result = linkedMapOf<Long, MutableList<OrderBatchItemDetails>>()
                while (rs.next()) {
                    val batchId = rs.getLong("order_batch_id")
                    val items = result.getOrPut(batchId) { mutableListOf() }
                    val itemId = rs.getLong("menu_item_id")
                    items.add(
                        OrderBatchItemDetails(
                            itemId = itemId,
                            qty = rs.getInt("qty"),
                            itemName = rs.getString("item_name")?.takeIf { it.isNotBlank() },
                            selectedOption = rs.toSelectedOptionDetails(),
                            preferenceNote = rs.getString("preference_note"),
                            priceMinor = rs.getLong("price_minor").let { value -> if (rs.wasNull()) null else value },
                            currency = rs.getString("currency"),
                            discountPercent =
                                rs.getInt("discount_percent").let {
                                        value ->
                                    if (rs.wasNull()) null else value
                                },
                            promoDiscountMinor = rs.getLong("promo_discount_minor"),
                            isPromotionReward = rs.getBoolean("is_promotion_reward"),
                        ),
                    )
                }
                result
            }
        }
    }

    private fun OrderBatchItemInput.toKey(): OrderBatchItemInputKey =
        OrderBatchItemInputKey(
            itemId = itemId,
            selectedOptionId = selectedOptionId,
            preferenceNote = preferenceNote,
        )

    private fun CheckoutMenuItem.effectivePriceMinor(selectedOption: CheckoutSelectedOption?): Long =
        priceMinor + (selectedOption?.priceDeltaMinor ?: 0L)

    private fun ResultSet.toSelectedOptionDetails(): OrderItemSelectedOptionDetails? {
        val optionId =
            getLong("menu_item_option_id").let { value ->
                if (wasNull()) null else value
            }
        val optionName = getString("option_name_snapshot")?.takeIf { it.isNotBlank() } ?: return null
        val priceDeltaMinor = getLong("price_delta_minor_snapshot")
        return OrderItemSelectedOptionDetails(
            optionId = optionId,
            name = optionName,
            priceDeltaMinor = priceDeltaMinor,
        )
    }
}
