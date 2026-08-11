package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.analytics.AnalyticsEventRecord
import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.analytics.analyticsCorrelationPayload
import com.hookah.platform.backend.api.CartMenuSelectionIssue
import com.hookah.platform.backend.api.CartMenuSelectionKind
import com.hookah.platform.backend.api.CartMenuSelectionReason
import com.hookah.platform.backend.api.CartMenuSelectionUnavailableException
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.OrderIdempotencyPayloadMismatchException
import com.hookah.platform.backend.api.OrderIdempotencyReplayUnverifiableException
import com.hookah.platform.backend.miniapp.venue.menu.MenuSemanticType
import com.hookah.platform.backend.promotions.GiftDecisionCartItem
import com.hookah.platform.backend.promotions.GiftDecisionCartScope
import com.hookah.platform.backend.promotions.GiftDecisionCommand
import com.hookah.platform.backend.promotions.GiftDecisionScopeClaims
import com.hookah.platform.backend.promotions.GiftDecisionScopeTokenService
import com.hookah.platform.backend.promotions.InvalidGiftDecisionScopeException
import com.hookah.platform.backend.promotions.PromotionGiftDecision
import com.hookah.platform.backend.promotions.PromotionGiftOffer
import com.hookah.platform.backend.promotions.PromotionGiftOfferStatus
import com.hookah.platform.backend.promotions.PromotionGiftRewardItem
import com.hookah.platform.backend.promotions.PromotionRuleCartItem
import com.hookah.platform.backend.promotions.PromotionRuleEngine
import com.hookah.platform.backend.promotions.PromotionRulePreviewGiftChoice
import com.hookah.platform.backend.promotions.decisionOfferIdentityOrNull
import com.hookah.platform.backend.promotions.matchesAuthoritativeScope
import com.hookah.platform.backend.promotions.toPromotionGiftDecision
import com.hookah.platform.backend.telegram.ActiveOrderSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Date
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.sql.DataSource

data class OrderBatchItemInput(
    val cartLineRef: String? = null,
    val itemId: Long,
    val qty: Int,
    val selectedOptionId: Long? = null,
    val preferenceNote: String? = null,
)

enum class GuestOrderWriteCheckpoint {
    AFTER_ORDER_BATCH_WRITE,
    AFTER_IDEMPOTENCY_WRITE,
}

class GiftDecisionRequiredException(
    val offer: PromotionGiftOffer,
) : RuntimeException("Gift decision is stale or incomplete")

const val GIFT_DECISION_STALE_MESSAGE = "Корзина изменилась. Проверьте подарок ещё раз."

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
    val status: String = "NEW",
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
    val pricing: GuestOrderCartPreview? = null,
    val recalculated: Boolean = false,
)

data class CreatedOrderPromotionDiscount(
    val label: String,
    val discountMinor: Long,
    val currency: String,
    val ruleType: String? = null,
    val promotionId: Long? = null,
    val ruleId: Long? = null,
    val ruleVersion: Int? = null,
    val originalAmountMinor: Long? = null,
    val finalAmountMinor: Long? = null,
    val eligibleLineIds: List<Long> = emptyList(),
)

data class CreatedOrderBatchItem(
    val lineId: Long? = null,
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
    val pricingFingerprint: String,
    val giftChoices: List<PromotionRulePreviewGiftChoice> = emptyList(),
    val giftOffer: PromotionGiftOffer = PromotionGiftOffer(PromotionGiftOfferStatus.NO_GIFT),
    val cartFingerprint: String = "",
    val decisionScopeToken: String? = null,
    val decisionScopeExpiresAtEpochSeconds: Long? = null,
    val giftDecisionStale: Boolean = false,
    val giftDecisionMessage: String? = null,
)

data class GuestOrderPromotionLineAdjustment(
    val promotionId: Long?,
    val promotionTitle: String,
    val ruleId: Long,
    val ruleVersion: Int,
    val ruleType: String,
    val originalAmountMinor: Long,
    val discountMinor: Long,
    val finalAmountMinor: Long,
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
    val baseUnitPriceMinor: Long = priceMinor - (selectedOption?.priceDeltaMinor ?: 0L),
    val selectedOptionDeltaMinor: Long = selectedOption?.priceDeltaMinor ?: 0L,
    val promotionAdjustment: GuestOrderPromotionLineAdjustment? = null,
)

private fun GuestOrderCartPreview.calculatePricingFingerprint(): String {
    val canonical =
        buildString {
            append(grossTotalMinor)
            append('|')
            append(promoDiscountTotalMinor)
            append('|')
            append(loyaltyDiscountTotalMinor)
            append('|')
            append(finalPayableTotalMinor)
            append('|')
            append(currency)
            discounts
                .sortedWith(
                    compareBy<CreatedOrderPromotionDiscount> { it.ruleId ?: Long.MAX_VALUE }
                        .thenBy { it.label },
                )
                .forEach { discount ->
                    append("|d:")
                    append(discount.promotionId)
                    append(':')
                    append(discount.ruleId)
                    append(':')
                    append(discount.ruleVersion)
                    append(':')
                    append(discount.discountMinor)
                }
            items.forEach { item ->
                append("|i:")
                append(item.itemId)
                append(':')
                append(item.qty)
                append(':')
                append(item.selectedOption?.optionId)
                append(':')
                append(item.priceMinor)
                append(':')
                append(item.discountMinor)
                append(':')
                append(item.promotionAdjustment?.ruleId)
            }
            append("|g:")
            append(giftOffer.status.name)
            append(':')
            append(giftOffer.promotionId)
            append(':')
            append(giftOffer.ruleId)
            append(':')
            append(giftOffer.ruleVersion)
            append(':')
            append(giftOffer.triggerLineId)
            append(':')
            append(giftOffer.triggerMenuItemId)
            append(':')
            append(giftOffer.selectedRewardItem?.menuItemId)
            giftOffer.fixedRewardItem?.let { reward ->
                append("|gf:")
                append(reward.menuItemId)
                append(':')
                append(reward.originalUnitPriceMinor)
                append(':')
                append(reward.currency)
            }
            giftOffer.selectableRewardItems.forEach { reward ->
                append("|gc:")
                append(reward.menuItemId)
                append(':')
                append(reward.originalUnitPriceMinor)
                append(':')
                append(reward.currency)
            }
        }
    return MessageDigest
        .getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun giftDecisionCartScope(
    userId: Long,
    venueId: Long,
    tableSessionId: Long,
    tabId: Long,
    items: List<OrderBatchItemInput>,
    comment: String?,
): GiftDecisionCartScope =
    GiftDecisionCartScope(
        userId = userId,
        venueId = venueId,
        tableSessionId = tableSessionId,
        tabId = tabId,
        comment = comment,
        items =
            items.map { item ->
                GiftDecisionCartItem(
                    menuItemId = item.itemId,
                    quantity = item.qty,
                    selectedOptionIds = listOfNotNull(item.selectedOptionId),
                    note = item.preferenceNote,
                )
            },
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

private data class ActiveOrderBatchHeader(
    val batchId: Long,
    val status: String,
    val comment: String?,
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
    private val clock: Clock = Clock.systemUTC(),
    private val giftDecisionScopeTokenService: GiftDecisionScopeTokenService? = null,
    private val guestOrderWriteCheckpoint: (GuestOrderWriteCheckpoint) -> Unit = {},
) {
    suspend fun findActiveOrderId(tableSessionId: Long): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                findActiveOrderId(connection, tableSessionId)
            }
        }
    }

    fun findActiveOrderId(
        connection: Connection,
        tableSessionId: Long,
    ): Long? =
        connection.prepareStatement(
            "SELECT id FROM orders WHERE table_session_id = ? AND status = 'ACTIVE'",
        ).use { statement ->
            statement.setLong(1, tableSessionId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
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

    suspend fun findSafelyLinkableStaffCallOrderId(
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
    ): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val activeOrderIds =
                        connection.prepareStatement(
                            """
                            SELECT id
                            FROM orders
                            WHERE venue_id = ?
                              AND table_session_id = ?
                              AND status = 'ACTIVE'
                            ORDER BY id
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, venueId)
                            statement.setLong(2, tableSessionId)
                            statement.executeQuery().use { rs ->
                                buildList {
                                    while (rs.next()) {
                                        add(rs.getLong("id"))
                                    }
                                }
                            }
                        }
                    val orderId = activeOrderIds.singleOrNull() ?: return@use null
                    val participant =
                        connection.prepareStatement(
                            """
                            SELECT 1
                            FROM order_batches ob
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
                            LIMIT 1
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, userId)
                            statement.setLong(2, orderId)
                            statement.setLong(3, userId)
                            statement.setLong(4, userId)
                            statement.executeQuery().use { rs -> rs.next() }
                        }
                    orderId.takeIf { participant }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
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
                            SELECT id, status, guest_comment
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
                                val result = mutableListOf<ActiveOrderBatchHeader>()
                                while (rs.next()) {
                                    result.add(
                                        ActiveOrderBatchHeader(
                                            batchId = rs.getLong("id"),
                                            status = rs.getString("status"),
                                            comment = rs.getString("guest_comment"),
                                        ),
                                    )
                                }
                                result
                            }
                        }

                    val itemsByBatch = loadBatchItems(connection, batches.map { it.batchId })

                    ActiveOrderDetails(
                        orderId = order.orderId,
                        status = order.status,
                        displayNumber = order.displayNumber,
                        displayDate = order.displayDate,
                        promotionDiscounts =
                            loadPromotionDiscountsForBatches(
                                connection,
                                order.orderId,
                                batches.map { it.batchId },
                            ),
                        serviceCharges = loadOrderServiceCharges(connection, order.orderId),
                        batches =
                            batches.map { batch ->
                                OrderBatchDetails(
                                    batchId = batch.batchId,
                                    status = batch.status,
                                    comment = batch.comment,
                                    items = itemsByBatch[batch.batchId].orEmpty(),
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
                    findActiveOrderDetailsForTab(connection, tableSessionId, tabId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    fun findActiveOrderDetailsForTab(
        connection: Connection,
        tableSessionId: Long,
        tabId: Long,
    ): ActiveOrderDetails? {
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
                                rs.getInt("display_number").let { value ->
                                    if (rs.wasNull()) null else value
                                },
                            displayDate = rs.getDate("display_date")?.toLocalDate(),
                        )
                    } else {
                        null
                    }
                }
            } ?: return null

        val batches =
            connection.prepareStatement(
                """
                SELECT id, status, guest_comment
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
                    buildList {
                        while (rs.next()) {
                            add(
                                ActiveOrderBatchHeader(
                                    batchId = rs.getLong("id"),
                                    status = rs.getString("status"),
                                    comment = rs.getString("guest_comment"),
                                ),
                            )
                        }
                    }
                }
            }
        if (batches.isEmpty()) {
            return null
        }
        val itemsByBatch = loadBatchItems(connection, batches.map { it.batchId })
        return ActiveOrderDetails(
            orderId = order.orderId,
            status = order.status,
            displayNumber = order.displayNumber,
            displayDate = order.displayDate,
            promotionDiscounts =
                loadPromotionDiscountsForBatches(
                    connection,
                    order.orderId,
                    batches.map { it.batchId },
                ),
            serviceCharges = loadOrderServiceCharges(connection, order.orderId),
            batches =
                batches.map { batch ->
                    OrderBatchDetails(
                        batchId = batch.batchId,
                        status = batch.status,
                        comment = batch.comment,
                        items = itemsByBatch[batch.batchId].orEmpty(),
                    )
                },
        )
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
                COALESCE(promo.item_name_snapshot, obi.item_name_snapshot, mi.name) AS item_name,
                obiop.menu_item_option_id,
                obiop.option_name_snapshot,
                obiop.price_delta_minor_snapshot,
                obi.preference_note,
                SUM(obi.qty) AS qty,
                CASE
                    WHEN promo.base_unit_price_minor IS NOT NULL
                        THEN promo.base_unit_price_minor + COALESCE(promo.selected_option_delta_minor, 0)
                    WHEN COALESCE(obi.base_unit_price_minor_snapshot, mi.price_minor) IS NULL THEN NULL
                    ELSE COALESCE(obi.base_unit_price_minor_snapshot, mi.price_minor) +
                        COALESCE(obiop.price_delta_minor_snapshot, 0)
                END AS price_minor,
                COALESCE(promo.currency, obi.currency_snapshot, mi.currency) AS currency,
                obi.discount_percent,
                COALESCE(SUM(promo.discount_minor), 0) AS promo_discount_minor,
                CASE WHEN opri.reward_order_batch_item_id IS NULL THEN FALSE ELSE TRUE END AS is_promotion_reward
            FROM order_batches ob
            JOIN order_batch_items obi ON obi.order_batch_id = ob.id
            LEFT JOIN menu_items mi ON mi.id = obi.menu_item_id
            LEFT JOIN order_batch_item_options obiop ON obiop.order_batch_item_id = obi.id
            LEFT JOIN (
                SELECT order_batch_item_id,
                       SUM(discount_minor) AS discount_minor,
                       MAX(item_name_snapshot) AS item_name_snapshot,
                       MAX(base_unit_price_minor) AS base_unit_price_minor,
                       MAX(selected_option_delta_minor) AS selected_option_delta_minor,
                       MAX(currency) AS currency
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
                obi.item_name_snapshot,
                promo.item_name_snapshot,
                obiop.menu_item_option_id,
                obiop.option_name_snapshot,
                obiop.price_delta_minor_snapshot,
                obi.preference_note,
                mi.price_minor,
                mi.currency,
                obi.base_unit_price_minor_snapshot,
                obi.currency_snapshot,
                promo.base_unit_price_minor,
                promo.selected_option_delta_minor,
                promo.currency,
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
        venueZoneId: ZoneId = defaultVenueZoneId(),
    ): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val orderId =
                        getOrCreateActiveOrderId(
                            connection = connection,
                            tableId = tableId,
                            venueId = venueId,
                            tableSessionId = tableSessionId,
                            venueZoneId = venueZoneId,
                        )
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

    fun getOrCreateActiveOrderId(
        connection: Connection,
        tableId: Long,
        venueId: Long,
        tableSessionId: Long,
        venueZoneId: ZoneId = defaultVenueZoneId(),
    ): Long? {
        val existing = findActiveOrderForUpdate(connection, tableSessionId)
        if (existing != null) {
            return existing
        }
        val savepoint = connection.setSavepoint()
        return try {
            insertActiveOrder(connection, venueId, tableId, tableSessionId, venueZoneId)
        } catch (e: SQLException) {
            if (e.sqlState != "23505") {
                throw e
            }
            connection.rollback(savepoint)
            findActiveOrderForUpdate(connection, tableSessionId)
        } finally {
            runCatching { connection.releaseSavepoint(savepoint) }
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
                createOrderBatch(connection, orderId, authorUserId, guestComment, tabId)
            }
        }
    }

    fun createOrderBatch(
        connection: Connection,
        orderId: Long,
        authorUserId: Long?,
        guestComment: String,
        tabId: Long? = null,
    ): Long? {
        val isH2 = connection.metaData.databaseProductName.contains("H2", ignoreCase = true)
        val returningClause = if (isH2) "" else " RETURNING id"
        val generatedKeys = if (isH2) Statement.RETURN_GENERATED_KEYS else Statement.NO_GENERATED_KEYS
        val sql =
            """
            INSERT INTO order_batches (order_id, tab_id, author_user_id, source, status, guest_comment)
            VALUES (?, ?, ?, 'CHAT', 'NEW', ?)$returningClause
            """.trimIndent()
        return connection.prepareStatement(sql, generatedKeys).use { statement ->
            statement.setLong(1, orderId)
            if (tabId != null) {
                statement.setLong(2, tabId)
            } else {
                statement.setNull(2, Types.BIGINT)
            }
            if (authorUserId != null) {
                statement.setLong(3, authorUserId)
            } else {
                statement.setNull(3, Types.BIGINT)
            }
            statement.setString(4, guestComment)
            if (isH2) {
                statement.executeUpdate()
                statement.generatedKeys.use { keys -> if (keys.next()) keys.getLong(1) else null }
            } else {
                statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
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
        venueZoneId: ZoneId = defaultVenueZoneId(),
        selectedGiftChoices: Map<Long, Long> = emptyMap(),
        skippedGiftRuleIds: Set<Long> = emptySet(),
        giftDecision: PromotionGiftDecision? = null,
        expectedPreviewFingerprint: String? = null,
        giftDecisionCommand: GiftDecisionCommand? = null,
        venueZoneIdProvider: ((Connection) -> ZoneId)? = null,
    ): CreatedOrderBatch? {
        val requestFingerprint =
            guestOrderRequestFingerprint(
                userId = userId,
                venueId = venueId,
                tableSessionId = tableSessionId,
                tabId = tabId,
                comment = comment,
                items = items,
            )
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
                        val existing =
                            findBatchIdempotency(
                                connection = connection,
                                venueId = venueId,
                                tableSessionId = tableSessionId,
                                idempotencyKey = idempotencyKey,
                            )
                        if (existing != null) {
                            verifyIdempotencyReplay(
                                connection = connection,
                                existing = existing,
                                tableId = tableId,
                                requestFingerprint = requestFingerprint,
                            )
                            val orderDisplay = loadOrderDisplay(connection, existing.orderId)
                            val promotionDiscounts = loadPromotionDiscountsForBatch(connection, existing.batchId)
                            val createdItems = loadCreatedOrderBatchItems(connection, existing.batchId)
                            val pricing =
                                buildPersistedBatchPricing(
                                    items = createdItems,
                                    discounts = promotionDiscounts,
                                    giftOffer = loadPersistedGiftOffer(connection, existing.batchId),
                                )
                            connection.commit()
                            return@use CreatedOrderBatch(
                                orderId = existing.orderId,
                                batchId = existing.batchId,
                                idempotencyReplay = true,
                                displayNumber = orderDisplay.displayNumber,
                                displayDate = orderDisplay.displayDate,
                                promotionDiscounts = promotionDiscounts,
                                items = createdItems,
                                pricing = pricing,
                                recalculated =
                                    expectedPreviewFingerprint != null &&
                                        expectedPreviewFingerprint != pricing.pricingFingerprint,
                            )
                        }
                        if (
                            !lockActiveGuestOrderScope(
                                connection = connection,
                                venueId = venueId,
                                tableId = tableId,
                                tableSessionId = tableSessionId,
                                tabId = tabId,
                                userId = userId,
                                now = clock.instant(),
                            )
                        ) {
                            connection.rollback()
                            return@use null
                        }
                        val concurrentExisting =
                            findBatchIdempotency(
                                connection = connection,
                                venueId = venueId,
                                tableSessionId = tableSessionId,
                                idempotencyKey = idempotencyKey,
                            )
                        if (concurrentExisting != null) {
                            verifyIdempotencyReplay(
                                connection = connection,
                                existing = concurrentExisting,
                                tableId = tableId,
                                requestFingerprint = requestFingerprint,
                            )
                            val orderDisplay = loadOrderDisplay(connection, concurrentExisting.orderId)
                            val promotionDiscounts =
                                loadPromotionDiscountsForBatch(connection, concurrentExisting.batchId)
                            val createdItems = loadCreatedOrderBatchItems(connection, concurrentExisting.batchId)
                            val pricing =
                                buildPersistedBatchPricing(
                                    items = createdItems,
                                    discounts = promotionDiscounts,
                                    giftOffer = loadPersistedGiftOffer(connection, concurrentExisting.batchId),
                                )
                            connection.commit()
                            return@use CreatedOrderBatch(
                                orderId = concurrentExisting.orderId,
                                batchId = concurrentExisting.batchId,
                                idempotencyReplay = true,
                                displayNumber = orderDisplay.displayNumber,
                                displayDate = orderDisplay.displayDate,
                                promotionDiscounts = promotionDiscounts,
                                items = createdItems,
                                pricing = pricing,
                                recalculated =
                                    expectedPreviewFingerprint != null &&
                                        expectedPreviewFingerprint != pricing.pricingFingerprint,
                            )
                        }
                        val giftResolution =
                            resolveAuthoritativeGiftDecision(
                                userId = userId,
                                venueId = venueId,
                                tableSessionId = tableSessionId,
                                tabId = tabId,
                                comment = comment,
                                items = items,
                                selectedGiftChoices = selectedGiftChoices,
                                skippedGiftRuleIds = skippedGiftRuleIds,
                                giftDecision = giftDecision,
                                giftDecisionCommand = giftDecisionCommand,
                            )
                        lockGuestOrderMenuSelections(connection, items)
                        validateCartMenuSelections(connection, venueId, items)
                        val checkoutMenuItems =
                            loadCheckoutMenuItems(connection, venueId, items.map { it.itemId }.toSet())
                        if (checkoutMenuItems.size != items.map { it.itemId }.toSet().size) {
                            throw InvalidInputException("Some items are unavailable")
                        }
                        val selectedOptionsByKey = resolveSelectedOptions(connection, venueId, items)
                        val authoritativeVenueZoneId = venueZoneIdProvider?.invoke(connection) ?: venueZoneId

                        val existingOrderId = findActiveOrderForUpdate(connection, tableSessionId)
                        val orderId =
                            existingOrderId
                                ?: insertActiveOrder(
                                    connection,
                                    venueId,
                                    tableId,
                                    tableSessionId,
                                    authoritativeVenueZoneId,
                                )
                        val orderDisplay = loadOrderDisplay(connection, orderId)
                        val batchId = insertOrderBatch(connection, orderId, tabId, comment)
                        guestOrderWriteCheckpoint(GuestOrderWriteCheckpoint.AFTER_ORDER_BATCH_WRITE)
                        val insertedItems =
                            insertBatchItems(
                                connection = connection,
                                batchId = batchId,
                                items = items,
                                checkoutMenuItems = checkoutMenuItems,
                                selectedOptionsByKey = selectedOptionsByKey,
                            )
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
                                venueZoneId = authoritativeVenueZoneId,
                                selectedGiftChoices = selectedGiftChoices,
                                skippedGiftRuleIds = skippedGiftRuleIds,
                                giftDecision = giftResolution.authoritativeGiftDecision,
                                giftDecisionCommand = giftDecisionCommand,
                                giftDecisionScopeClaims = giftResolution.decisionScopeClaims,
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
                            requestFingerprint = requestFingerprint,
                        )
                        guestOrderWriteCheckpoint(GuestOrderWriteCheckpoint.AFTER_IDEMPOTENCY_WRITE)
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
                        val discounts = promotionResult.discounts + listOfNotNull(loyaltyRedemption?.discount)
                        val pricing =
                            buildPersistedBatchPricing(
                                items = createdItems,
                                discounts = discounts,
                                giftOffer = promotionResult.giftOffer,
                            )
                        CreatedOrderBatch(
                            orderId = orderId,
                            batchId = batchId,
                            idempotencyReplay = false,
                            displayNumber = orderDisplay.displayNumber,
                            displayDate = orderDisplay.displayDate,
                            isFirstBatch = existingOrderId == null,
                            promotionDiscounts = discounts,
                            items = createdItems,
                            pricing = pricing,
                            recalculated =
                                expectedPreviewFingerprint != null &&
                                    expectedPreviewFingerprint != pricing.pricingFingerprint,
                        )
                    } catch (e: SQLException) {
                        connection.rollback()
                        if (e.sqlState == "23505") {
                            findBatchIdempotency(
                                connection = connection,
                                venueId = venueId,
                                tableSessionId = tableSessionId,
                                idempotencyKey = idempotencyKey,
                            )?.let { existing ->
                                verifyIdempotencyReplay(
                                    connection = connection,
                                    existing = existing,
                                    tableId = tableId,
                                    requestFingerprint = requestFingerprint,
                                )
                                val promotionDiscounts =
                                    loadPromotionDiscountsForBatch(connection, existing.batchId)
                                val createdItems = loadCreatedOrderBatchItems(connection, existing.batchId)
                                val pricing =
                                    buildPersistedBatchPricing(
                                        items = createdItems,
                                        discounts = promotionDiscounts,
                                        giftOffer = loadPersistedGiftOffer(connection, existing.batchId),
                                    )
                                val replay =
                                    CreatedOrderBatch(
                                        orderId = existing.orderId,
                                        batchId = existing.batchId,
                                        idempotencyReplay = true,
                                        displayNumber = existing.displayNumber,
                                        displayDate = existing.displayDate,
                                        promotionDiscounts = promotionDiscounts,
                                        items = createdItems,
                                        pricing = pricing,
                                        recalculated =
                                            expectedPreviewFingerprint != null &&
                                                expectedPreviewFingerprint != pricing.pricingFingerprint,
                                    )
                                connection.commit()
                                return@use replay
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

    fun createGuestOrderBatch(
        connection: Connection,
        tableId: Long,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
        idempotencyKey: String,
        tabId: Long,
        comment: String?,
        items: List<OrderBatchItemInput>,
        venueZoneId: ZoneId = defaultVenueZoneId(),
        selectedGiftChoices: Map<Long, Long> = emptyMap(),
        skippedGiftRuleIds: Set<Long> = emptySet(),
        giftDecision: PromotionGiftDecision? = null,
        expectedPreviewFingerprint: String? = null,
        giftDecisionCommand: GiftDecisionCommand? = null,
        beforeAuthoritativeWrites: (() -> Unit)? = null,
        venueZoneIdProvider: ((Connection) -> ZoneId)? = null,
    ): CreatedOrderBatch? {
        val requestFingerprint =
            guestOrderRequestFingerprint(
                userId = userId,
                venueId = venueId,
                tableSessionId = tableSessionId,
                tabId = tabId,
                comment = comment,
                items = items,
            )
        val savepoint = connection.setSavepoint()
        return try {
            val existing =
                findBatchIdempotency(
                    connection = connection,
                    venueId = venueId,
                    tableSessionId = tableSessionId,
                    idempotencyKey = idempotencyKey,
                )
            if (existing != null) {
                verifyIdempotencyReplay(
                    connection = connection,
                    existing = existing,
                    tableId = tableId,
                    requestFingerprint = requestFingerprint,
                )
                return buildIdempotentCreatedBatch(
                    connection = connection,
                    existing = existing,
                    expectedPreviewFingerprint = expectedPreviewFingerprint,
                )
            }
            if (
                !lockActiveGuestOrderScope(
                    connection = connection,
                    venueId = venueId,
                    tableId = tableId,
                    tableSessionId = tableSessionId,
                    tabId = tabId,
                    userId = userId,
                    now = clock.instant(),
                )
            ) {
                return null
            }
            val concurrentExisting =
                findBatchIdempotency(
                    connection = connection,
                    venueId = venueId,
                    tableSessionId = tableSessionId,
                    idempotencyKey = idempotencyKey,
                )
            if (concurrentExisting != null) {
                verifyIdempotencyReplay(
                    connection = connection,
                    existing = concurrentExisting,
                    tableId = tableId,
                    requestFingerprint = requestFingerprint,
                )
                return buildIdempotentCreatedBatch(
                    connection = connection,
                    existing = concurrentExisting,
                    expectedPreviewFingerprint = expectedPreviewFingerprint,
                )
            }
            val giftResolution =
                resolveAuthoritativeGiftDecision(
                    userId = userId,
                    venueId = venueId,
                    tableSessionId = tableSessionId,
                    tabId = tabId,
                    comment = comment,
                    items = items,
                    selectedGiftChoices = selectedGiftChoices,
                    skippedGiftRuleIds = skippedGiftRuleIds,
                    giftDecision = giftDecision,
                    giftDecisionCommand = giftDecisionCommand,
                )
            lockGuestOrderMenuSelections(connection, items)
            validateCartMenuSelections(connection, venueId, items)
            val checkoutMenuItems = loadCheckoutMenuItems(connection, venueId, items.map { it.itemId }.toSet())
            if (checkoutMenuItems.size != items.map { it.itemId }.toSet().size) {
                throw InvalidInputException("Some items are unavailable")
            }
            val selectedOptionsByKey = resolveSelectedOptions(connection, venueId, items)
            val authoritativeVenueZoneId = venueZoneIdProvider?.invoke(connection) ?: venueZoneId
            beforeAuthoritativeWrites?.invoke()
            val existingOrderId = findActiveOrderForUpdate(connection, tableSessionId)
            val orderId =
                existingOrderId
                    ?: insertActiveOrder(
                        connection,
                        venueId,
                        tableId,
                        tableSessionId,
                        authoritativeVenueZoneId,
                    )
            val orderDisplay = loadOrderDisplay(connection, orderId)
            val batchId = insertOrderBatch(connection, orderId, tabId, comment)
            guestOrderWriteCheckpoint(GuestOrderWriteCheckpoint.AFTER_ORDER_BATCH_WRITE)
            val insertedItems =
                insertBatchItems(
                    connection = connection,
                    batchId = batchId,
                    items = items,
                    checkoutMenuItems = checkoutMenuItems,
                    selectedOptionsByKey = selectedOptionsByKey,
                )
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
                    venueZoneId = authoritativeVenueZoneId,
                    selectedGiftChoices = selectedGiftChoices,
                    skippedGiftRuleIds = skippedGiftRuleIds,
                    giftDecision = giftResolution.authoritativeGiftDecision,
                    giftDecisionCommand = giftDecisionCommand,
                    giftDecisionScopeClaims = giftResolution.decisionScopeClaims,
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
                requestFingerprint = requestFingerprint,
            )
            guestOrderWriteCheckpoint(GuestOrderWriteCheckpoint.AFTER_IDEMPOTENCY_WRITE)
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
            val discounts = promotionResult.discounts + listOfNotNull(loyaltyRedemption?.discount)
            val pricing =
                buildPersistedBatchPricing(
                    items = createdItems,
                    discounts = discounts,
                    giftOffer = promotionResult.giftOffer,
                )
            CreatedOrderBatch(
                orderId = orderId,
                batchId = batchId,
                idempotencyReplay = false,
                displayNumber = orderDisplay.displayNumber,
                displayDate = orderDisplay.displayDate,
                isFirstBatch = existingOrderId == null,
                promotionDiscounts = discounts,
                items = createdItems,
                pricing = pricing,
                recalculated =
                    expectedPreviewFingerprint != null &&
                        expectedPreviewFingerprint != pricing.pricingFingerprint,
            )
        } catch (e: SQLException) {
            connection.rollback(savepoint)
            if (e.sqlState == "23505") {
                findBatchIdempotency(
                    connection = connection,
                    venueId = venueId,
                    tableSessionId = tableSessionId,
                    idempotencyKey = idempotencyKey,
                )?.let { existing ->
                    verifyIdempotencyReplay(
                        connection = connection,
                        existing = existing,
                        tableId = tableId,
                        requestFingerprint = requestFingerprint,
                    )
                    return buildIdempotentCreatedBatch(
                        connection = connection,
                        existing = existing,
                        expectedPreviewFingerprint = expectedPreviewFingerprint,
                    )
                }
            }
            throw e
        } catch (e: Exception) {
            connection.rollback(savepoint)
            throw e
        } finally {
            runCatching { connection.releaseSavepoint(savepoint) }
        }
    }

    private fun buildIdempotentCreatedBatch(
        connection: Connection,
        existing: StoredBatchIdempotency,
        expectedPreviewFingerprint: String?,
    ): CreatedOrderBatch {
        val promotionDiscounts = loadPromotionDiscountsForBatch(connection, existing.batchId)
        val createdItems = loadCreatedOrderBatchItems(connection, existing.batchId)
        val pricing =
            buildPersistedBatchPricing(
                items = createdItems,
                discounts = promotionDiscounts,
                giftOffer = loadPersistedGiftOffer(connection, existing.batchId),
            )
        return CreatedOrderBatch(
            orderId = existing.orderId,
            batchId = existing.batchId,
            idempotencyReplay = true,
            displayNumber = existing.displayNumber,
            displayDate = existing.displayDate,
            promotionDiscounts = promotionDiscounts,
            items = createdItems,
            pricing = pricing,
            recalculated =
                expectedPreviewFingerprint != null &&
                    expectedPreviewFingerprint != pricing.pricingFingerprint,
        )
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

    private fun lockActiveGuestOrderScope(
        connection: Connection,
        venueId: Long,
        tableId: Long,
        tableSessionId: Long,
        tabId: Long,
        userId: Long,
        now: java.time.Instant,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT ts.id
            FROM table_sessions ts
            JOIN tab t
              ON t.id = ?
             AND t.venue_id = ts.venue_id
             AND t.table_session_id = ts.id
             AND t.status = 'ACTIVE'
            JOIN tab_member tm
              ON tm.tab_id = t.id
             AND tm.user_id = ?
             AND tm.role IN ('OWNER', 'MEMBER')
            WHERE ts.id = ?
              AND ts.venue_id = ?
              AND ts.table_id = ?
              AND ts.status = 'ACTIVE'
              AND ts.ended_at IS NULL
              AND ts.expires_at > ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, tabId)
            statement.setLong(2, userId)
            statement.setLong(3, tableSessionId)
            statement.setLong(4, venueId)
            statement.setLong(5, tableId)
            statement.setTimestamp(6, Timestamp.from(now))
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun lockGuestOrderMenuSelections(
        connection: Connection,
        items: List<OrderBatchItemInput>,
    ) {
        val itemIds = items.map { it.itemId }.distinct().sorted()
        if (itemIds.isEmpty()) {
            return
        }
        val itemPlaceholders = itemIds.joinToString(",") { "?" }
        connection.prepareStatement(
            """
            SELECT id
            FROM menu_items
            WHERE id IN ($itemPlaceholders)
            ORDER BY id
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            itemIds.forEachIndexed { index, itemId -> statement.setLong(index + 1, itemId) }
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    rs.getLong("id")
                }
            }
        }

        val selectedOptionIds = items.mapNotNull { it.selectedOptionId }.distinct().sorted()
        val optionPredicate =
            if (selectedOptionIds.isEmpty()) {
                "item_id IN ($itemPlaceholders)"
            } else {
                val optionPlaceholders = selectedOptionIds.joinToString(",") { "?" }
                "item_id IN ($itemPlaceholders) OR id IN ($optionPlaceholders)"
            }
        connection.prepareStatement(
            """
            SELECT id
            FROM menu_item_options
            WHERE $optionPredicate
            ORDER BY id
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            var parameterIndex = 1
            itemIds.forEach { itemId -> statement.setLong(parameterIndex++, itemId) }
            selectedOptionIds.forEach { optionId -> statement.setLong(parameterIndex++, optionId) }
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    rs.getLong("id")
                }
            }
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
        venueZoneId: ZoneId = defaultVenueZoneId(),
    ): Long {
        lockVenue(connection, venueId)
        val displayDate = LocalDate.ofInstant(clock.instant(), venueZoneId)
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
        checkoutMenuItems: Map<Long, CheckoutMenuItem>,
        selectedOptionsByKey: Map<OrderBatchItemInputKey, CheckoutSelectedOption> = emptyMap(),
    ): List<InsertedOrderBatchItem> {
        val insertedItems = mutableListOf<InsertedOrderBatchItem>()
        connection.prepareStatement(
            """
            INSERT INTO order_batch_items (
                order_batch_id,
                menu_item_id,
                qty,
                preference_note,
                item_name_snapshot,
                base_unit_price_minor_snapshot,
                currency_snapshot
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            items.forEach { item ->
                val menuItem =
                    checkoutMenuItems[item.itemId]
                        ?: throw InvalidInputException("Some items are unavailable")
                statement.setLong(1, batchId)
                statement.setLong(2, item.itemId)
                statement.setInt(3, item.qty)
                if (item.preferenceNote != null) {
                    statement.setString(4, item.preferenceNote)
                } else {
                    statement.setNull(4, Types.VARCHAR)
                }
                statement.setString(5, menuItem.name)
                statement.setLong(6, menuItem.priceMinor)
                statement.setString(7, menuItem.currency)
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
        checkoutMenuItem: CheckoutMenuItem,
        selectedOption: CheckoutSelectedOption? = null,
    ): InsertedOrderBatchItem =
        connection.prepareStatement(
            """
            INSERT INTO order_batch_items (
                order_batch_id,
                menu_item_id,
                qty,
                preference_note,
                item_name_snapshot,
                base_unit_price_minor_snapshot,
                currency_snapshot
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
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
            statement.setString(5, checkoutMenuItem.name)
            statement.setLong(6, checkoutMenuItem.priceMinor)
            statement.setString(7, checkoutMenuItem.currency)
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

    private fun validateCartMenuSelections(
        connection: Connection,
        venueId: Long,
        items: List<OrderBatchItemInput>,
    ) {
        val itemStates = loadCartMenuItemStates(connection, items.map { it.itemId }.toSet())
        val optionStates = loadCartMenuOptionStates(connection, items.mapNotNull { it.selectedOptionId }.toSet())
        val issues = mutableListOf<CartMenuSelectionIssue>()

        items.forEach { item ->
            val itemState = itemStates[item.itemId]
            if (itemState != null && !itemState.belongsToVenue(venueId)) {
                throw InvalidInputException("Some items are unavailable")
            }
            val optionId = item.selectedOptionId ?: return@forEach
            val optionState = optionStates[optionId]
            if (optionState != null && !optionState.belongsTo(venueId = venueId, itemId = item.itemId)) {
                throw InvalidInputException("Selected option is invalid")
            }
        }

        items.forEach { item ->
            val itemState = itemStates[item.itemId]
            if (itemState == null) {
                issues += item.toIssue(CartMenuSelectionKind.ITEM, CartMenuSelectionReason.REMOVED)
                return@forEach
            }
            if (!itemState.isAvailable) {
                issues += item.toIssue(CartMenuSelectionKind.ITEM, CartMenuSelectionReason.UNAVAILABLE)
                return@forEach
            }

            val optionId = item.selectedOptionId ?: return@forEach
            val optionState = optionStates[optionId]
            if (optionState == null) {
                issues += item.toIssue(CartMenuSelectionKind.OPTION, CartMenuSelectionReason.REMOVED)
            } else if (!optionState.isAvailable) {
                issues += item.toIssue(CartMenuSelectionKind.OPTION, CartMenuSelectionReason.UNAVAILABLE)
            }
        }

        if (issues.isEmpty()) {
            return
        }
        if (issues.any { it.cartLineRef.isBlank() }) {
            throw InvalidInputException("Some items are unavailable")
        }
        throw CartMenuSelectionUnavailableException(issues)
    }

    private fun loadCartMenuItemStates(
        connection: Connection,
        itemIds: Set<Long>,
    ): Map<Long, CartMenuItemState> {
        if (itemIds.isEmpty()) return emptyMap()
        val placeholders = itemIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            SELECT mi.id,
                   mi.venue_id,
                   mi.is_available,
                   mc.venue_id AS category_venue_id,
                   mc.is_active AS category_is_active
            FROM menu_items mi
            LEFT JOIN menu_categories mc ON mc.id = mi.category_id
            WHERE mi.id IN ($placeholders)
            """.trimIndent(),
        ).use { statement ->
            itemIds.forEachIndexed { index, itemId -> statement.setLong(index + 1, itemId) }
            statement.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val categoryVenueId = rs.getLong("category_venue_id").takeUnless { rs.wasNull() }
                        val categoryIsActive = rs.getBoolean("category_is_active").takeUnless { rs.wasNull() }
                        put(
                            rs.getLong("id"),
                            CartMenuItemState(
                                venueId = rs.getLong("venue_id"),
                                categoryVenueId = categoryVenueId,
                                isAvailable = rs.getBoolean("is_available") && categoryIsActive == true,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun loadCartMenuOptionStates(
        connection: Connection,
        optionIds: Set<Long>,
    ): Map<Long, CartMenuOptionState> {
        if (optionIds.isEmpty()) return emptyMap()
        val placeholders = optionIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            SELECT id, venue_id, item_id, is_available
            FROM menu_item_options
            WHERE id IN ($placeholders)
            """.trimIndent(),
        ).use { statement ->
            optionIds.forEachIndexed { index, optionId -> statement.setLong(index + 1, optionId) }
            statement.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        put(
                            rs.getLong("id"),
                            CartMenuOptionState(
                                venueId = rs.getLong("venue_id"),
                                itemId = rs.getLong("item_id"),
                                isAvailable = rs.getBoolean("is_available"),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun OrderBatchItemInput.toIssue(
        selectionKind: CartMenuSelectionKind,
        reason: CartMenuSelectionReason,
    ): CartMenuSelectionIssue =
        CartMenuSelectionIssue(
            cartLineRef = cartLineRef.orEmpty(),
            itemId = itemId,
            optionId = selectedOptionId,
            selectionKind = selectionKind,
            reason = reason,
        )

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
                   mi.category_id,
                   COALESCE(mi.item_type, mc.category_type, 'OTHER') AS effective_type,
                   EXISTS (
                       SELECT 1
                       FROM menu_item_options mio
                       WHERE mio.item_id = mi.id
                         AND mio.venue_id = mi.venue_id
                         AND mio.is_available = TRUE
                   ) AS requires_option_selection
            FROM menu_items mi
            JOIN menu_categories mc ON mc.id = mi.category_id
            WHERE mi.venue_id = ?
              AND mi.is_available = TRUE
              AND mc.is_active = TRUE
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
                                menuCategoryId = rs.getLong("category_id"),
                                effectiveType = MenuSemanticType.fromDb(rs.getString("effective_type")),
                                requiresOptionSelection = rs.getBoolean("requires_option_selection"),
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
        venueZoneId: ZoneId = defaultVenueZoneId(),
        selectedGiftChoices: Map<Long, Long> = emptyMap(),
        skippedGiftRuleIds: Set<Long> = emptySet(),
        giftDecision: PromotionGiftDecision? = null,
        tableSessionId: Long? = null,
        tabId: Long? = null,
        comment: String? = null,
        giftDecisionCommand: GiftDecisionCommand? = null,
    ): GuestOrderCartPreview? {
        if (items.isEmpty()) {
            return null
        }
        val legacyGiftDecisionRejected =
            giftDecisionScopeTokenService != null &&
                (
                    giftDecision != null ||
                        selectedGiftChoices.isNotEmpty() ||
                        skippedGiftRuleIds.isNotEmpty()
                )
        val effectiveSelectedGiftChoices =
            selectedGiftChoices.takeIf { giftDecisionScopeTokenService == null }.orEmpty()
        val effectiveSkippedGiftRuleIds =
            skippedGiftRuleIds.takeIf { giftDecisionScopeTokenService == null }.orEmpty()
        val decisionCartScope =
            if (
                giftDecisionScopeTokenService != null &&
                tableSessionId != null &&
                tabId != null
            ) {
                giftDecisionCartScope(
                    userId = userId,
                    venueId = venueId,
                    tableSessionId = tableSessionId,
                    tabId = tabId,
                    items = items,
                    comment = comment,
                )
            } else {
                null
            }
        var decisionScopeClaims: GiftDecisionScopeClaims? = null
        var giftDecisionStale =
            legacyGiftDecisionRejected ||
                (
                    giftDecisionScopeTokenService != null &&
                        giftDecisionCommand != null &&
                        decisionCartScope == null
                )
        if (
            giftDecisionScopeTokenService != null &&
            giftDecisionCommand != null &&
            decisionCartScope != null
        ) {
            decisionScopeClaims =
                try {
                    giftDecisionScopeTokenService.verify(
                        token = giftDecisionCommand.decisionScopeToken,
                        expectedScope = decisionCartScope,
                    )
                } catch (_: InvalidGiftDecisionScopeException) {
                    giftDecisionStale = true
                    null
                }
        }
        val verifiedGiftDecision =
            if (giftDecisionScopeTokenService == null) {
                giftDecision
            } else if (decisionScopeClaims != null && giftDecisionCommand != null) {
                decisionScopeClaims.toPromotionGiftDecision(giftDecisionCommand)
            } else {
                null
            }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val itemIds = items.map { it.itemId }.toSet()
        val now = clock.instant()
        val inputs =
            withContext(Dispatchers.IO) {
                try {
                    ds.connection.use { connection ->
                        val originalAutoCommit = connection.autoCommit
                        connection.autoCommit = false
                        try {
                            validateCartMenuSelections(connection, venueId, items)
                            val checkoutMenuItems = loadCheckoutMenuItems(connection, venueId, itemIds)
                            if (checkoutMenuItems.size != itemIds.size) {
                                connection.rollback()
                                return@use null
                            }
                            val selectedOptionsByKey = resolveSelectedOptions(connection, venueId, items)
                            val rules =
                                venuePromotionRuleRepository
                                    ?.listActiveRulesForVenueAt(connection, venueId, now)
                                    .orEmpty()
                            val result =
                                CartPreviewInputs(
                                    checkoutMenuItems = checkoutMenuItems,
                                    selectedOptionsByKey = selectedOptionsByKey,
                                    activeRules = rules,
                                )
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
                    menuCategoryId = menuItem.menuCategoryId,
                    requiredOptionsSatisfied =
                        !menuItem.requiresOptionSelection || selectedOption != null,
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
        val promotionCartItems =
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
                        menuCategoryId = item.menuCategoryId,
                        requiredOptionsSatisfied = item.requiredOptionsSatisfied,
                    )
                }
        val basePromotionPreview =
            PromotionRuleEngine.preview(
                venueId = venueId,
                now = now,
                venueZoneId = venueZoneId,
                cartItems = promotionCartItems,
                activeRules = inputs.activeRules,
                selectedGiftChoices = effectiveSelectedGiftChoices,
                skippedGiftRuleIds = effectiveSkippedGiftRuleIds,
                giftDecision = null,
            )
        val scopeMatchesCurrentOffer =
            decisionScopeClaims == null ||
                (
                    giftDecisionCommand != null &&
                        basePromotionPreview.giftOffer.matchesAuthoritativeScope(
                            claims = decisionScopeClaims,
                            command = giftDecisionCommand,
                        )
                )
        if (decisionScopeClaims != null && !scopeMatchesCurrentOffer) {
            giftDecisionStale = true
        }
        val promotionPreview =
            if (verifiedGiftDecision != null && scopeMatchesCurrentOffer) {
                PromotionRuleEngine.preview(
                    venueId = venueId,
                    now = now,
                    venueZoneId = venueZoneId,
                    cartItems = promotionCartItems,
                    activeRules = inputs.activeRules,
                    selectedGiftChoices = effectiveSelectedGiftChoices,
                    skippedGiftRuleIds = effectiveSkippedGiftRuleIds,
                    giftDecision = verifiedGiftDecision,
                ).also { resolved ->
                    if (!resolved.giftDecisionResolved) {
                        giftDecisionStale = true
                    }
                }
            } else {
                basePromotionPreview
            }
        val rulesById = inputs.activeRules.associateBy { it.id }
        val promoDiscounts =
            promotionPreview.adjustments
                .groupBy { adjustment ->
                    val rule = rulesById[adjustment.ruleId]
                    CartPreviewDiscountKey(
                        label = adjustment.label.takeIf { it.isNotBlank() } ?: "Акция",
                        ruleType = rule?.ruleType?.dbValue,
                        currency = adjustment.currency,
                        promotionId = rule?.promotionId,
                        ruleId = rule?.id,
                        ruleVersion = rule?.version,
                    )
                }
                .map { (key, adjustments) ->
                    val originalAmount =
                        adjustments.sumOf { adjustment ->
                            baseItems.firstOrNull { it.lineId == adjustment.lineId }?.lineGrossMinor() ?: 0L
                        }
                    val discountAmount = adjustments.sumOf { it.discountMinor }
                    CreatedOrderPromotionDiscount(
                        label = key.label,
                        discountMinor = discountAmount,
                        currency = key.currency,
                        ruleType = key.ruleType,
                        promotionId = key.promotionId,
                        ruleId = key.ruleId,
                        ruleVersion = key.ruleVersion,
                        originalAmountMinor = originalAmount,
                        finalAmountMinor = (originalAmount - discountAmount).coerceAtLeast(0L),
                        eligibleLineIds = adjustments.mapNotNull { it.lineId },
                    )
                }
        val giftDiscounts =
            promotionPreview.appliedGifts.map { gift ->
                val rule = rulesById[gift.ruleId]
                CreatedOrderPromotionDiscount(
                    label = gift.label.takeIf { it.isNotBlank() } ?: "${gift.rewardItemName} в подарок",
                    discountMinor = gift.rewardPriceMinor * gift.rewardQty.toLong(),
                    currency = gift.currency,
                    ruleType = rule?.ruleType?.dbValue,
                    promotionId = rule?.promotionId,
                    ruleId = rule?.id,
                    ruleVersion = rule?.version,
                    originalAmountMinor = gift.rewardPriceMinor * gift.rewardQty.toLong(),
                    finalAmountMinor = 0L,
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
        val promoAdjustmentByLine =
            promotionPreview.adjustments
                .mapNotNull { adjustment -> adjustment.lineId?.let { it to adjustment } }
                .toMap()
        val previewItems =
            baseItems.map { item ->
                val promoDiscount = promoDiscountsByLine[item.lineId] ?: 0L
                val promotionAdjustment = promoAdjustmentByLine[item.lineId]
                val promotionRule = promotionAdjustment?.let { rulesById[it.ruleId] }
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
                    baseUnitPriceMinor = item.priceMinor - (item.selectedOption?.priceDeltaMinor ?: 0L),
                    selectedOptionDeltaMinor = item.selectedOption?.priceDeltaMinor ?: 0L,
                    promotionAdjustment =
                        if (promotionAdjustment == null || promotionRule == null) {
                            null
                        } else {
                            GuestOrderPromotionLineAdjustment(
                                promotionId = promotionRule.promotionId,
                                promotionTitle =
                                    promotionRule.promotionTitle?.takeIf { it.isNotBlank() }
                                        ?: "Счастливые часы",
                                ruleId = promotionRule.id,
                                ruleVersion = promotionRule.version,
                                ruleType = promotionRule.ruleType.dbValue,
                                originalAmountMinor = gross,
                                discountMinor = promotionAdjustment.discountMinor,
                                finalAmountMinor =
                                    (gross - promotionAdjustment.discountMinor).coerceAtLeast(0L),
                            )
                        },
                )
            } +
                promotionPreview.appliedGifts.map { gift ->
                    val gross = gift.rewardPriceMinor * gift.rewardQty.toLong()
                    val promotionRule = rulesById[gift.ruleId]
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
                        promotionAdjustment =
                            promotionRule?.let { rule ->
                                GuestOrderPromotionLineAdjustment(
                                    promotionId = rule.promotionId,
                                    promotionTitle =
                                        rule.promotionTitle?.takeIf { it.isNotBlank() }
                                            ?: "Подарок при покупке",
                                    ruleId = rule.id,
                                    ruleVersion = rule.version,
                                    ruleType = rule.ruleType.dbValue,
                                    originalAmountMinor = gross,
                                    discountMinor = gross,
                                    finalAmountMinor = 0L,
                                )
                            },
                    )
                }
        val allDiscounts = promoDiscounts + giftDiscounts + listOfNotNull(loyaltyDiscount)
        val currency =
            previewItems.firstOrNull { it.currency.isNotBlank() }?.currency
                ?: allDiscounts.firstOrNull { it.currency.isNotBlank() }?.currency
                ?: "RUB"
        val result =
            GuestOrderCartPreview(
                items = previewItems,
                grossTotalMinor = previewItems.sumOf { it.lineGrossMinor },
                promoDiscountTotalMinor = (promoDiscounts + giftDiscounts).sumOf { it.discountMinor },
                loyaltyDiscountTotalMinor = loyaltyDiscount?.discountMinor ?: 0L,
                finalPayableTotalMinor = previewItems.sumOf { it.linePayableMinor },
                currency = currency,
                discounts = allDiscounts,
                pricingFingerprint = "",
                giftChoices = promotionPreview.giftChoices,
                giftOffer = promotionPreview.giftOffer,
            )
        val pricedResult = result.copy(pricingFingerprint = result.calculatePricingFingerprint())
        if (giftDecisionScopeTokenService == null || decisionCartScope == null) {
            return pricedResult
        }
        val resolvedExistingScope =
            giftDecisionCommand != null &&
                decisionScopeClaims != null &&
                !giftDecisionStale &&
                promotionPreview.giftDecisionResolved
        if (resolvedExistingScope) {
            return pricedResult.copy(
                cartFingerprint = decisionScopeClaims.cartFingerprint,
                decisionScopeToken = giftDecisionCommand.decisionScopeToken,
                decisionScopeExpiresAtEpochSeconds = decisionScopeClaims.expiresAtEpochSeconds,
            )
        }
        val offerIdentity = pricedResult.giftOffer.decisionOfferIdentityOrNull()
        val issuedScope =
            offerIdentity?.let { identity ->
                giftDecisionScopeTokenService.issue(
                    scope = decisionCartScope,
                    offer = identity,
                )
            }
        return pricedResult.copy(
            cartFingerprint =
                issuedScope?.cartFingerprint
                    ?: giftDecisionScopeTokenService.cartFingerprint(decisionCartScope, offerIdentity),
            decisionScopeToken = issuedScope?.token,
            decisionScopeExpiresAtEpochSeconds = issuedScope?.expiresAtEpochSeconds,
            giftDecisionStale = giftDecisionStale,
            giftDecisionMessage = GIFT_DECISION_STALE_MESSAGE.takeIf { giftDecisionStale },
        )
    }

    private fun buildPersistedBatchPricing(
        items: List<CreatedOrderBatchItem>,
        discounts: List<CreatedOrderPromotionDiscount>,
        giftOffer: PromotionGiftOffer = PromotionGiftOffer(PromotionGiftOfferStatus.NO_GIFT),
    ): GuestOrderCartPreview {
        val promotionDiscounts =
            discounts.filterNot { discount ->
                discount.ruleType.equals("LOYALTY_NTH_HOOKAH", ignoreCase = true) ||
                    discount.label.contains("Лояльность", ignoreCase = true)
            }
        val loyaltyDiscounts = discounts - promotionDiscounts.toSet()
        val previewItems =
            items.map { item ->
                val gross = item.priceMinor * item.qty.toLong()
                val linePromotion =
                    promotionDiscounts.firstOrNull { discount ->
                        item.lineId != null && item.lineId in discount.eligibleLineIds
                    } ?: promotionDiscounts.singleOrNull()?.takeIf { discount ->
                        discount.eligibleLineIds.isEmpty() && item.promoDiscountMinor > 0L
                    }
                GuestOrderCartPreviewItem(
                    itemId = item.itemId,
                    itemName = item.itemName,
                    qty = item.qty,
                    selectedOption = item.selectedOption,
                    preferenceNote = item.preferenceNote,
                    priceMinor = item.priceMinor,
                    currency = item.currency,
                    lineGrossMinor = gross,
                    discountMinor = item.promoDiscountMinor,
                    linePayableMinor = (gross - item.promoDiscountMinor).coerceAtLeast(0L),
                    isPromotionReward = item.isPromotionReward,
                    baseUnitPriceMinor = item.priceMinor - (item.selectedOption?.priceDeltaMinor ?: 0L),
                    selectedOptionDeltaMinor = item.selectedOption?.priceDeltaMinor ?: 0L,
                    promotionAdjustment =
                        linePromotion?.ruleId?.let { ruleId ->
                            GuestOrderPromotionLineAdjustment(
                                promotionId = linePromotion.promotionId,
                                promotionTitle =
                                    if (item.isPromotionReward) {
                                        giftOffer.promotionTitle?.takeIf { it.isNotBlank() }
                                            ?: linePromotion.label
                                    } else {
                                        linePromotion.label
                                    },
                                ruleId = ruleId,
                                ruleVersion = linePromotion.ruleVersion ?: 1,
                                ruleType = linePromotion.ruleType ?: PromotionRuleType.HAPPY_HOURS_PERCENT.dbValue,
                                originalAmountMinor = gross,
                                discountMinor = item.promoDiscountMinor,
                                finalAmountMinor = (gross - item.promoDiscountMinor).coerceAtLeast(0L),
                            )
                        },
                )
            }
        val grossTotal = previewItems.sumOf { it.lineGrossMinor }
        val promoTotal = promotionDiscounts.sumOf { it.discountMinor }
        val loyaltyTotal = loyaltyDiscounts.sumOf { it.discountMinor }
        val result =
            GuestOrderCartPreview(
                items = previewItems,
                grossTotalMinor = grossTotal,
                promoDiscountTotalMinor = promoTotal,
                loyaltyDiscountTotalMinor = loyaltyTotal,
                finalPayableTotalMinor = (grossTotal - promoTotal - loyaltyTotal).coerceAtLeast(0L),
                currency =
                    previewItems.firstOrNull()?.currency
                        ?: discounts.firstOrNull()?.currency
                        ?: "RUB",
                discounts = discounts,
                pricingFingerprint = "",
                giftOffer = giftOffer,
            )
        return result.copy(pricingFingerprint = result.calculatePricingFingerprint())
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
        giftDecision: PromotionGiftDecision? = null,
        giftDecisionCommand: GiftDecisionCommand? = null,
        giftDecisionScopeClaims: GiftDecisionScopeClaims? = null,
        excludedBatchItemIds: Set<Long> = emptySet(),
    ): PromotionRulesApplicationResult {
        val applicationRepository =
            promotionApplicationRepository
                ?: if (giftDecision == null) {
                    return PromotionRulesApplicationResult()
                } else {
                    throw GiftDecisionRequiredException(
                        PromotionGiftOffer(PromotionGiftOfferStatus.NO_GIFT),
                    )
                }
        val ruleRepository =
            venuePromotionRuleRepository
                ?: if (giftDecision == null) {
                    return PromotionRulesApplicationResult()
                } else {
                    throw GiftDecisionRequiredException(
                        PromotionGiftOffer(PromotionGiftOfferStatus.NO_GIFT),
                    )
                }
        if (insertedItems.isEmpty()) {
            if (giftDecision != null) {
                throw GiftDecisionRequiredException(
                    PromotionGiftOffer(PromotionGiftOfferStatus.NO_GIFT),
                )
            }
            return PromotionRulesApplicationResult()
        }
        val now = clock.instant()
        val rules = ruleRepository.listActiveRulesForVenueAt(connection, venueId, now)
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
                    menuCategoryId = menuItem.menuCategoryId,
                    requiredOptionsSatisfied =
                        !menuItem.requiresOptionSelection || inserted.selectedOption != null,
                )
            }
        if (giftDecisionScopeClaims != null) {
            val command =
                giftDecisionCommand
                    ?: throw GiftDecisionRequiredException(
                        PromotionGiftOffer(PromotionGiftOfferStatus.NO_GIFT),
                    )
            val currentOffer =
                PromotionRuleEngine.preview(
                    venueId = venueId,
                    now = now,
                    venueZoneId = venueZoneId,
                    cartItems = cartItems,
                    activeRules = rules,
                    selectedGiftChoices = selectedGiftChoices,
                    skippedGiftRuleIds = skippedGiftRuleIds,
                    giftDecision = null,
                ).giftOffer
            if (!currentOffer.matchesAuthoritativeScope(giftDecisionScopeClaims, command)) {
                throw GiftDecisionRequiredException(currentOffer)
            }
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
                giftDecision = giftDecision,
            )
        if (!preview.giftDecisionResolved) {
            throw GiftDecisionRequiredException(preview.giftOffer)
        }
        val freshRewardItems =
            loadCheckoutMenuItems(
                connection = connection,
                venueId = venueId,
                itemIds = preview.appliedGifts.map { it.rewardMenuItemId }.toSet(),
            )
        val eligibleGifts =
            preview.appliedGifts.filter { gift -> freshRewardItems.containsKey(gift.rewardMenuItemId) }
        if (eligibleGifts.size != preview.appliedGifts.size) {
            throw GiftDecisionRequiredException(preview.giftOffer)
        }
        if (preview.adjustments.isEmpty() && eligibleGifts.isEmpty()) {
            return PromotionRulesApplicationResult(giftOffer = preview.giftOffer)
        }
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
                                itemNameSnapshot = menuItem.name,
                                baseUnitPriceMinor = menuItem.priceMinor,
                                selectedOptionDeltaMinor = inserted.selectedOption?.priceDeltaMinor ?: 0L,
                                originalAmountMinor =
                                    menuItem.effectivePriceMinor(inserted.selectedOption) * inserted.qty.toLong(),
                                finalAmountMinor =
                                    (
                                        menuItem.effectivePriceMinor(inserted.selectedOption) *
                                            inserted.qty.toLong() -
                                            adjustment.discountMinor
                                    ).coerceAtLeast(0L),
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
                            ruleVersion = rule.version,
                            scheduleSnapshotJson = rule.scheduleSnapshotJson(),
                            targetSnapshotJson = rule.targetSnapshotJson(),
                            originalTotalMinor = adjustmentInputs.sumOf { it.originalAmountMinor },
                            finalTotalMinor = adjustmentInputs.sumOf { it.finalAmountMinor },
                            venueTimezoneSnapshot = venueZoneId.id,
                            appliedAt = now,
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
                        checkoutMenuItem = rewardMenuItem,
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
                    titleSnapshot =
                        rule.promotionTitle?.takeIf { it.isNotBlank() }
                            ?: "Подарок при покупке",
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
                                itemNameSnapshot = rewardMenuItem.name,
                                baseUnitPriceMinor = rewardMenuItem.priceMinor,
                                selectedOptionDeltaMinor = 0L,
                                originalAmountMinor = discountMinor,
                                finalAmountMinor = 0L,
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
                    ruleVersion = rule.version,
                    scheduleSnapshotJson = rule.scheduleSnapshotJson(),
                    targetSnapshotJson = rule.targetSnapshotJson(),
                    originalTotalMinor = discountMinor,
                    finalTotalMinor = 0L,
                    venueTimezoneSnapshot = venueZoneId.id,
                    appliedAt = now,
                )
            }
        val applications = percentApplications + giftApplications
        applicationRepository.persistApplications(connection, applications)
        applications.forEach { application ->
            analyticsEventRepository?.append(
                connection = connection,
                event =
                    AnalyticsEventRecord(
                        eventType = "promotion_applied",
                        payload =
                            analyticsCorrelationPayload(
                                venueId = venueId,
                                orderId = orderId,
                                batchId = batchId,
                                extra =
                                    mapOf(
                                        "promotionId" to application.promotionId?.toString(),
                                        "ruleId" to application.ruleId.toString(),
                                        "ruleVersion" to application.ruleVersion.toString(),
                                    ),
                            ),
                        venueId = venueId,
                        orderId = orderId,
                        batchId = batchId,
                        idempotencyKey = "promotion_applied:${application.dedupeKey}",
                    ),
            )
        }
        return PromotionRulesApplicationResult(
            discounts =
                applications.map { application ->
                    CreatedOrderPromotionDiscount(
                        label =
                            application.rewardItems.singleOrNull()?.labelSnapshot
                                ?: application.titleSnapshot,
                        discountMinor = application.discountTotalMinor,
                        currency = application.currency,
                        ruleType = application.ruleType,
                        promotionId = application.promotionId,
                        ruleId = application.ruleId,
                        ruleVersion = application.ruleVersion,
                        originalAmountMinor = application.originalTotalMinor,
                        finalAmountMinor = application.finalTotalMinor,
                        eligibleLineIds = application.adjustments.map { it.orderBatchItemId },
                    )
                },
            giftOffer = preview.giftOffer,
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
            SELECT
                CASE
                    WHEN opa.rule_type = 'GIFT_WITH_ITEM'
                        THEN COALESCE(
                            (
                                SELECT MAX(opri.label_snapshot)
                                FROM order_promotion_reward_items opri
                                WHERE opri.application_id = opa.id
                            ),
                            opa.title_snapshot
                        )
                    ELSE opa.title_snapshot
                END AS promo_label,
                opa.rule_type,
                opa.currency,
                opa.promotion_id,
                opa.rule_id,
                opa.rule_version,
                obipa.order_batch_item_id,
                obipa.original_amount_minor,
                obipa.discount_minor,
                obipa.final_amount_minor
            FROM order_promotion_applications opa
            JOIN order_batch_item_promotion_adjustments obipa ON obipa.application_id = opa.id
            JOIN order_batch_items obi ON obi.id = obipa.order_batch_item_id
            JOIN order_batches ob ON ob.id = obi.order_batch_id
            WHERE opa.batch_id = ?
              AND ob.status <> 'REJECTED'
              AND ob.status <> 'CLOSED'
              AND ob.rejected_reason_code IS NULL
              AND ob.rejected_reason_text IS NULL
              AND obi.is_excluded = FALSE
              AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
            ORDER BY opa.id, obipa.id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, batchId)
            statement.executeQuery().use { rs ->
                val discounts = linkedMapOf<ReplayPromotionDiscountKey, CreatedOrderPromotionDiscount>()
                while (rs.next()) {
                    val key =
                        ReplayPromotionDiscountKey(
                            label = rs.getString("promo_label"),
                            ruleType = rs.getString("rule_type"),
                            currency = rs.getString("currency"),
                            promotionId =
                                rs.getLong("promotion_id").let { value ->
                                    if (rs.wasNull()) null else value
                                },
                            ruleId = rs.getLong("rule_id"),
                            ruleVersion = rs.getInt("rule_version"),
                        )
                    val current =
                        discounts[key] ?: CreatedOrderPromotionDiscount(
                            label = key.label,
                            discountMinor = 0L,
                            currency = key.currency,
                            ruleType = key.ruleType,
                            promotionId = key.promotionId,
                            ruleId = key.ruleId,
                            ruleVersion = key.ruleVersion,
                            originalAmountMinor = 0L,
                            finalAmountMinor = 0L,
                        )
                    discounts[key] =
                        current.copy(
                            originalAmountMinor =
                                current.originalAmountMinor.orEmptyAmount() +
                                    rs.getLong("original_amount_minor"),
                            discountMinor = current.discountMinor + rs.getLong("discount_minor"),
                            finalAmountMinor =
                                current.finalAmountMinor.orEmptyAmount() +
                                    rs.getLong("final_amount_minor"),
                            eligibleLineIds =
                                current.eligibleLineIds + rs.getLong("order_batch_item_id"),
                        )
                }
                discounts.values.filter { it.discountMinor > 0L }
            }
        }

    private fun loadPersistedGiftOffer(
        connection: Connection,
        batchId: Long,
    ): PromotionGiftOffer =
        connection.prepareStatement(
            """
            SELECT opa.promotion_id,
                   opa.title_snapshot,
                   opa.rule_id,
                   opa.rule_version,
                   opri.trigger_order_batch_item_id,
                   trigger_item.menu_item_id AS trigger_menu_item_id,
                   COALESCE(
                       trigger_item.item_name_snapshot,
                       trigger_menu.name,
                       'Позиция #' || trigger_item.menu_item_id
                   ) AS trigger_item_name,
                   opri.reward_menu_item_id,
                   COALESCE(
                       reward_adjustment.item_name_snapshot,
                       reward_item.item_name_snapshot,
                       reward_menu.name,
                       'Позиция #' || opri.reward_menu_item_id
                   ) AS reward_item_name,
                   reward_adjustment.original_price_minor,
                   reward_adjustment.currency
            FROM order_promotion_applications opa
            JOIN order_promotion_reward_items opri ON opri.application_id = opa.id
            JOIN order_batch_items reward_item ON reward_item.id = opri.reward_order_batch_item_id
            JOIN order_batch_item_promotion_adjustments reward_adjustment
              ON reward_adjustment.application_id = opa.id
             AND reward_adjustment.order_batch_item_id = reward_item.id
            LEFT JOIN order_batch_items trigger_item ON trigger_item.id = opri.trigger_order_batch_item_id
            LEFT JOIN menu_items trigger_menu ON trigger_menu.id = trigger_item.menu_item_id
            LEFT JOIN menu_items reward_menu ON reward_menu.id = opri.reward_menu_item_id
            WHERE opa.batch_id = ?
              AND opa.rule_type = 'GIFT_WITH_ITEM'
            ORDER BY opa.id
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, batchId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    PromotionGiftOffer(PromotionGiftOfferStatus.NO_GIFT)
                } else {
                    val reward =
                        PromotionGiftRewardItem(
                            menuItemId = rs.getLong("reward_menu_item_id"),
                            name = rs.getString("reward_item_name"),
                            originalUnitPriceMinor = rs.getLong("original_price_minor").coerceAtLeast(0L),
                            currency = rs.getString("currency")?.takeIf { it.isNotBlank() } ?: "RUB",
                        )
                    PromotionGiftOffer(
                        status = PromotionGiftOfferStatus.GIFT_SELECTED,
                        promotionId =
                            rs.getLong("promotion_id").let { value ->
                                if (rs.wasNull()) null else value
                            },
                        promotionTitle = rs.getString("title_snapshot"),
                        ruleId = rs.getLong("rule_id"),
                        ruleVersion = rs.getInt("rule_version"),
                        triggerLineId =
                            rs.getLong("trigger_order_batch_item_id").let { value ->
                                if (rs.wasNull()) null else value
                            },
                        triggerMenuItemId =
                            rs.getLong("trigger_menu_item_id").let { value ->
                                if (rs.wasNull()) null else value
                            },
                        triggerItemName = rs.getString("trigger_item_name"),
                        selectedRewardItem = reward,
                    )
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
                lineId = inserted.batchItemId,
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
            SELECT obi.id AS order_batch_item_id,
                   obi.menu_item_id,
                   COALESCE(
                       promo.item_name_snapshot,
                       obi.item_name_snapshot,
                       mi.name,
                       'Позиция #' || obi.menu_item_id
                   ) AS item_name,
                   obi.qty,
                   obi.preference_note,
                   obiop.menu_item_option_id,
                   obiop.option_name_snapshot,
                   obiop.price_delta_minor_snapshot,
                   CASE
                       WHEN promo.base_unit_price_minor IS NOT NULL
                           THEN promo.base_unit_price_minor + COALESCE(promo.selected_option_delta_minor, 0)
                       ELSE COALESCE(obi.base_unit_price_minor_snapshot, mi.price_minor, 0) +
                           COALESCE(obiop.price_delta_minor_snapshot, 0)
                   END AS price_minor,
                   COALESCE(promo.currency, obi.currency_snapshot, mi.currency, 'RUB') AS currency,
                   COALESCE(promo.discount_minor, 0) AS promo_discount_minor,
                   CASE WHEN opri.id IS NULL THEN FALSE ELSE TRUE END AS is_promotion_reward
            FROM order_batch_items obi
            LEFT JOIN menu_items mi ON mi.id = obi.menu_item_id
            LEFT JOIN order_batch_item_options obiop ON obiop.order_batch_item_id = obi.id
            LEFT JOIN (
                SELECT order_batch_item_id,
                       SUM(discount_minor) AS discount_minor,
                       MAX(item_name_snapshot) AS item_name_snapshot,
                       MAX(base_unit_price_minor) AS base_unit_price_minor,
                       MAX(selected_option_delta_minor) AS selected_option_delta_minor,
                       MAX(currency) AS currency
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
                                lineId = rs.getLong("order_batch_item_id"),
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
        val id: Long,
        val venueId: Long,
        val tableSessionId: Long,
        val userId: Long,
        val orderId: Long,
        val batchId: Long,
        val tableId: Long,
        val tabId: Long?,
        val displayNumber: Int?,
        val displayDate: LocalDate?,
        val requestFingerprint: String?,
    )

    private data class CanonicalGuestOrderLineKey(
        val itemId: Long,
        val selectedOptionId: Long?,
        val preferenceNote: String?,
    )

    private data class CanonicalGuestOrderLine(
        val key: CanonicalGuestOrderLineKey,
        val quantity: Long,
    )

    private data class AuthoritativeGiftDecisionResolution(
        val decisionScopeClaims: GiftDecisionScopeClaims?,
        val authoritativeGiftDecision: PromotionGiftDecision?,
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
        val giftOffer: PromotionGiftOffer = PromotionGiftOffer(PromotionGiftOfferStatus.NO_GIFT),
    )

    private data class ReplayPromotionDiscountKey(
        val label: String,
        val ruleType: String?,
        val currency: String,
        val promotionId: Long?,
        val ruleId: Long,
        val ruleVersion: Int,
    )

    private data class CartPreviewInputs(
        val checkoutMenuItems: Map<Long, CheckoutMenuItem>,
        val selectedOptionsByKey: Map<OrderBatchItemInputKey, CheckoutSelectedOption>,
        val activeRules: List<VenuePromotionRule>,
    )

    private data class CartMenuItemState(
        val venueId: Long,
        val categoryVenueId: Long?,
        val isAvailable: Boolean,
    ) {
        fun belongsToVenue(expectedVenueId: Long): Boolean =
            venueId == expectedVenueId && categoryVenueId == expectedVenueId
    }

    private data class CartMenuOptionState(
        val venueId: Long,
        val itemId: Long,
        val isAvailable: Boolean,
    ) {
        fun belongsTo(
            venueId: Long,
            itemId: Long,
        ): Boolean = this.venueId == venueId && this.itemId == itemId
    }

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
        val menuCategoryId: Long,
        val requiredOptionsSatisfied: Boolean,
    ) {
        fun lineGrossMinor(): Long = priceMinor * qty.toLong()
    }

    private data class CartPreviewDiscountKey(
        val label: String,
        val ruleType: String?,
        val currency: String,
        val promotionId: Long?,
        val ruleId: Long?,
        val ruleVersion: Int?,
    )

    private data class CheckoutMenuItem(
        val itemId: Long,
        val name: String,
        val priceMinor: Long,
        val currency: String,
        val menuCategoryId: Long,
        val effectiveType: MenuSemanticType,
        val requiresOptionSelection: Boolean,
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

    private fun resolveAuthoritativeGiftDecision(
        userId: Long,
        venueId: Long,
        tableSessionId: Long,
        tabId: Long,
        comment: String?,
        items: List<OrderBatchItemInput>,
        selectedGiftChoices: Map<Long, Long>,
        skippedGiftRuleIds: Set<Long>,
        giftDecision: PromotionGiftDecision?,
        giftDecisionCommand: GiftDecisionCommand?,
    ): AuthoritativeGiftDecisionResolution {
        val service = giftDecisionScopeTokenService
        if (
            service != null &&
            (
                giftDecision != null ||
                    selectedGiftChoices.isNotEmpty() ||
                    skippedGiftRuleIds.isNotEmpty()
            )
        ) {
            throw GiftDecisionRequiredException(PromotionGiftOffer(PromotionGiftOfferStatus.NO_GIFT))
        }
        val decisionScopeClaims =
            service?.let {
                giftDecisionCommand?.let { command ->
                    try {
                        it.verify(
                            token = command.decisionScopeToken,
                            expectedScope =
                                giftDecisionCartScope(
                                    userId = userId,
                                    venueId = venueId,
                                    tableSessionId = tableSessionId,
                                    tabId = tabId,
                                    items = items,
                                    comment = comment,
                                ),
                        )
                    } catch (_: InvalidGiftDecisionScopeException) {
                        throw GiftDecisionRequiredException(PromotionGiftOffer(PromotionGiftOfferStatus.NO_GIFT))
                    }
                }
            }
        val authoritativeGiftDecision =
            if (service != null) {
                if (decisionScopeClaims != null && giftDecisionCommand != null) {
                    decisionScopeClaims.toPromotionGiftDecision(giftDecisionCommand)
                } else {
                    null
                }
            } else {
                giftDecision
            }
        return AuthoritativeGiftDecisionResolution(
            decisionScopeClaims = decisionScopeClaims,
            authoritativeGiftDecision = authoritativeGiftDecision,
        )
    }

    private fun guestOrderRequestFingerprint(
        userId: Long,
        venueId: Long,
        tableSessionId: Long,
        tabId: Long,
        comment: String?,
        items: List<OrderBatchItemInput>,
    ): String {
        val mergedLines = linkedMapOf<CanonicalGuestOrderLineKey, Long>()
        items.forEach { item ->
            val key =
                CanonicalGuestOrderLineKey(
                    itemId = item.itemId,
                    selectedOptionId = item.selectedOptionId,
                    preferenceNote = normalizeIdempotencyText(item.preferenceNote),
                )
            mergedLines[key] = Math.addExact(mergedLines[key] ?: 0L, item.qty.toLong())
        }
        val lines =
            mergedLines
                .map { (key, quantity) -> CanonicalGuestOrderLine(key = key, quantity = quantity) }
                .sortedWith(
                    compareBy<CanonicalGuestOrderLine> { it.key.itemId }
                        .thenBy { if (it.key.selectedOptionId == null) 0 else 1 }
                        .thenBy { it.key.selectedOptionId ?: 0L }
                        .thenBy { it.key.preferenceNote ?: "" },
                )
        val canonicalJson =
            buildJsonObject {
                put("version", REQUEST_FINGERPRINT_VERSION)
                put("actorUserId", userId)
                put("venueId", venueId)
                put("tableSessionId", tableSessionId)
                put("tabId", tabId)
                put(
                    "comment",
                    normalizeIdempotencyText(comment)?.let(::JsonPrimitive) ?: JsonNull,
                )
                put(
                    "lines",
                    buildJsonArray {
                        lines.forEach { line ->
                            add(
                                buildJsonObject {
                                    put("itemId", line.key.itemId)
                                    put(
                                        "selectedOption",
                                        buildJsonObject {
                                            if (line.key.selectedOptionId == null) {
                                                put("kind", "base")
                                            } else {
                                                put("kind", "option")
                                                put("optionId", line.key.selectedOptionId)
                                            }
                                        },
                                    )
                                    put(
                                        "preferenceNote",
                                        line.key.preferenceNote?.let(::JsonPrimitive) ?: JsonNull,
                                    )
                                    put("quantity", line.quantity)
                                },
                            )
                        }
                    },
                )
            }.toString()
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(canonicalJson.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        return "$REQUEST_FINGERPRINT_VERSION:$digest"
    }

    private fun normalizeIdempotencyText(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun verifyIdempotencyReplay(
        connection: Connection,
        existing: StoredBatchIdempotency,
        tableId: Long,
        requestFingerprint: String,
    ) {
        if (existing.tableId != tableId) {
            throw OrderIdempotencyPayloadMismatchException()
        }
        val persistedFingerprint =
            existing.requestFingerprint
                ?: reconstructLegacyRequestFingerprint(connection, existing)
        if (!isSupportedRequestFingerprint(persistedFingerprint)) {
            throw OrderIdempotencyReplayUnverifiableException()
        }
        if (persistedFingerprint != requestFingerprint) {
            throw OrderIdempotencyPayloadMismatchException()
        }
        if (existing.requestFingerprint == null) {
            val updated =
                connection.prepareStatement(
                    """
                    UPDATE guest_batch_idempotency
                    SET request_fingerprint = ?
                    WHERE id = ? AND request_fingerprint IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, persistedFingerprint)
                    statement.setLong(2, existing.id)
                    statement.executeUpdate()
                }
            if (updated != 1) {
                throw SQLException("Failed to upgrade legacy guest batch idempotency fingerprint", "40001")
            }
        }
    }

    private fun reconstructLegacyRequestFingerprint(
        connection: Connection,
        existing: StoredBatchIdempotency,
    ): String {
        val comment =
            connection.prepareStatement(
                """
                SELECT guest_comment
                FROM order_batches
                WHERE id = ? AND order_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, existing.batchId)
                statement.setLong(2, existing.orderId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        throw OrderIdempotencyReplayUnverifiableException()
                    }
                    rs.getString("guest_comment")
                }
            }
        val items =
            connection.prepareStatement(
                """
                SELECT obi.menu_item_id,
                       obi.qty,
                       obi.preference_note,
                       obiop.id AS option_snapshot_id,
                       obiop.menu_item_option_id
                FROM order_batch_items obi
                LEFT JOIN order_batch_item_options obiop ON obiop.order_batch_item_id = obi.id
                LEFT JOIN order_promotion_reward_items reward
                  ON reward.reward_order_batch_item_id = obi.id
                WHERE obi.order_batch_id = ?
                  AND reward.id IS NULL
                ORDER BY obi.id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, existing.batchId)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val optionSnapshotId =
                                rs.getLong("option_snapshot_id").let { value ->
                                    if (rs.wasNull()) null else value
                                }
                            val selectedOptionId =
                                rs.getLong("menu_item_option_id").let { value ->
                                    if (rs.wasNull()) null else value
                                }
                            if (optionSnapshotId != null && selectedOptionId == null) {
                                throw OrderIdempotencyReplayUnverifiableException()
                            }
                            add(
                                OrderBatchItemInput(
                                    itemId = rs.getLong("menu_item_id"),
                                    qty = rs.getInt("qty"),
                                    selectedOptionId = selectedOptionId,
                                    preferenceNote = rs.getString("preference_note"),
                                ),
                            )
                        }
                    }
                }
            }
        if (items.isEmpty()) {
            throw OrderIdempotencyReplayUnverifiableException()
        }
        return guestOrderRequestFingerprint(
            userId = existing.userId,
            venueId = existing.venueId,
            tableSessionId = existing.tableSessionId,
            tabId = existing.tabId ?: throw OrderIdempotencyReplayUnverifiableException(),
            comment = comment,
            items = items,
        )
    }

    private fun isSupportedRequestFingerprint(value: String): Boolean =
        value.length == REQUEST_FINGERPRINT_LENGTH &&
            value.startsWith("$REQUEST_FINGERPRINT_VERSION:") &&
            value.substring(REQUEST_FINGERPRINT_VERSION.length + 1).all { character ->
                character in '0'..'9' || character in 'a'..'f'
            }

    private fun findBatchIdempotency(
        connection: Connection,
        venueId: Long,
        tableSessionId: Long,
        idempotencyKey: String,
    ): StoredBatchIdempotency? {
        return connection.prepareStatement(
            """
            SELECT gbi.id AS idempotency_id,
                   gbi.venue_id,
                   gbi.table_session_id,
                   gbi.user_id,
                   gbi.order_id,
                   gbi.batch_id,
                   gbi.request_fingerprint,
                   o.table_id,
                   ob.tab_id,
                   o.display_number,
                   o.display_date
            FROM guest_batch_idempotency gbi
            JOIN orders o ON o.id = gbi.order_id
            JOIN order_batches ob ON ob.id = gbi.batch_id
            WHERE gbi.venue_id = ? AND gbi.table_session_id = ? AND gbi.idempotency_key = ?
            ORDER BY gbi.id
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableSessionId)
            statement.setString(3, idempotencyKey)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    val existing =
                        StoredBatchIdempotency(
                            id = rs.getLong("idempotency_id"),
                            venueId = rs.getLong("venue_id"),
                            tableSessionId = rs.getLong("table_session_id"),
                            userId = rs.getLong("user_id"),
                            orderId = rs.getLong("order_id"),
                            batchId = rs.getLong("batch_id"),
                            tableId = rs.getLong("table_id"),
                            tabId = rs.getLong("tab_id").let { value -> if (rs.wasNull()) null else value },
                            displayNumber =
                                rs.getInt("display_number").let { value ->
                                    if (rs.wasNull()) null else value
                                },
                            displayDate = rs.getDate("display_date")?.toLocalDate(),
                            requestFingerprint = rs.getString("request_fingerprint"),
                        )
                    if (rs.next()) {
                        throw OrderIdempotencyReplayUnverifiableException()
                    }
                    existing
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
        requestFingerprint: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO guest_batch_idempotency (
                venue_id,
                table_session_id,
                user_id,
                idempotency_key,
                order_id,
                batch_id,
                request_fingerprint
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableSessionId)
            statement.setLong(3, userId)
            statement.setString(4, idempotencyKey)
            statement.setLong(5, orderId)
            statement.setLong(6, batchId)
            statement.setString(7, requestFingerprint)
            statement.executeUpdate()
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
                   COALESCE(promo.item_name_snapshot, obi.item_name_snapshot, mi.name) AS item_name,
                   obiop.menu_item_option_id,
                   obiop.option_name_snapshot,
                   obiop.price_delta_minor_snapshot,
                   CASE
                       WHEN promo.base_unit_price_minor IS NOT NULL
                           THEN promo.base_unit_price_minor + COALESCE(promo.selected_option_delta_minor, 0)
                       WHEN COALESCE(obi.base_unit_price_minor_snapshot, mi.price_minor) IS NULL THEN NULL
                       ELSE COALESCE(obi.base_unit_price_minor_snapshot, mi.price_minor) +
                           COALESCE(obiop.price_delta_minor_snapshot, 0)
                   END AS price_minor,
                   COALESCE(promo.currency, obi.currency_snapshot, mi.currency) AS currency
            FROM order_batch_items obi
            LEFT JOIN menu_items mi ON mi.id = obi.menu_item_id
            LEFT JOIN order_batch_item_options obiop ON obiop.order_batch_item_id = obi.id
            LEFT JOIN (
                SELECT order_batch_item_id,
                       SUM(discount_minor) AS discount_minor,
                       MAX(item_name_snapshot) AS item_name_snapshot,
                       MAX(base_unit_price_minor) AS base_unit_price_minor,
                       MAX(selected_option_delta_minor) AS selected_option_delta_minor,
                       MAX(currency) AS currency
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

    private fun VenuePromotionRule.scheduleSnapshotJson(): String =
        buildJsonObject {
            put("version", version)
            promotionStartsAt?.let { put("promotionStartsAt", it.toString()) }
            promotionEndsAt?.let { put("promotionEndsAt", it.toString()) }
            put("priority", priority)
            put("stackable", stackable)
            put("conflictGroup", conflictGroup)
            put("maxApplicationsPerItem", maxApplicationsPerItem)
            put(
                "windows",
                buildJsonArray {
                    weekdayWindows.forEach { window ->
                        add(
                            buildJsonObject {
                                put("weekday", window.weekday)
                                put("startsMinute", window.startsMinute)
                                put("endsMinute", window.endsMinute)
                            },
                        )
                    }
                },
            )
        }.toString()

    private fun Long?.orEmptyAmount(): Long = this ?: 0L

    private fun VenuePromotionRule.targetSnapshotJson(): String =
        buildJsonObject {
            put(
                "targets",
                buildJsonArray {
                    targets.forEach { target ->
                        add(
                            buildJsonObject {
                                put("type", target.targetType.dbValue)
                                target.semanticType?.let { put("semanticType", it.dbValue) }
                                target.menuItemId?.let { put("menuItemId", it) }
                                target.menuCategoryId?.let { put("menuCategoryId", it) }
                            },
                        )
                    }
                },
            )
            reward?.let { configuredReward ->
                put(
                    "reward",
                    buildJsonObject {
                        put("mode", configuredReward.rewardMode.dbValue)
                        put("quantity", configuredReward.rewardQty)
                        put("maxRewardsPerBatch", configuredReward.maxRewardsPerBatch)
                        if (configuredReward.rewardMode == PromotionRewardMode.FIXED_ITEM) {
                            put("fixedMenuItemId", configuredReward.rewardMenuItemId)
                        }
                        put(
                            "allowlistMenuItemIds",
                            buildJsonArray {
                                configuredReward.options
                                    .map { it.menuItemId }
                                    .distinct()
                                    .sorted()
                                    .forEach { add(it) }
                            },
                        )
                    },
                )
            }
        }.toString()

    private companion object {
        const val REQUEST_FINGERPRINT_VERSION = "v1"
        const val REQUEST_FINGERPRINT_LENGTH = 67

        fun defaultVenueZoneId(): ZoneId = ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE)
    }

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
