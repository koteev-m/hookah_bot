package com.hookah.platform.backend.miniapp.venue.orders

import com.hookah.platform.backend.analytics.AnalyticsEventRecord
import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.analytics.analyticsCorrelationPayload
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackRepository
import com.hookah.platform.backend.miniapp.guest.db.VisitRepository
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.telegram.db.LoyaltyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import javax.sql.DataSource

data class OrderQueueCursor(
    val createdAt: Instant,
    val batchId: Long,
) {
    fun encode(): String = "${createdAt.epochSecond}:${createdAt.nano}:$batchId"

    companion object {
        fun parse(raw: String?): OrderQueueCursor? {
            if (raw.isNullOrBlank()) {
                return null
            }
            val parts = raw.split(":")
            return when (parts.size) {
                3 -> {
                    val epochSecond = parts[0].toLongOrNull() ?: return null
                    val nano = parts[1].toLongOrNull() ?: return null
                    if (nano < 0 || nano > 999_999_999) {
                        return null
                    }
                    val batchId = parts[2].toLongOrNull() ?: return null
                    OrderQueueCursor(Instant.ofEpochSecond(epochSecond, nano), batchId)
                }
                2 -> {
                    val epochMs = parts[0].toLongOrNull() ?: return null
                    val batchId = parts[1].toLongOrNull() ?: return null
                    OrderQueueCursor(Instant.ofEpochMilli(epochMs), batchId)
                }
                else -> null
            }
        }
    }
}

data class OrderQueueItem(
    val orderId: Long,
    val batchId: Long,
    val tableNumber: Int,
    val createdAt: Instant,
    val comment: String?,
    val itemsCount: Int,
    val status: OrderWorkflowStatus,
    val activeBatchesCount: Int = 1,
    val displayNumber: Int? = null,
    val displayDate: LocalDate? = null,
    val guestDisplayName: String? = null,
    val promoDiscountMinor: Long = 0L,
    val payableMinor: Long? = null,
    val currency: String? = null,
    val promotionDiscounts: List<OrderPromotionDiscount> = emptyList(),
    val pendingShiftExtension: OrderPendingShiftExtension? = null,
)

data class OrderQueueResult(
    val items: List<OrderQueueItem>,
    val nextCursor: OrderQueueCursor?,
)

data class OrderDetail(
    val orderId: Long,
    val venueId: Long,
    val tableId: Long,
    val tableNumber: Int,
    val status: OrderWorkflowStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val batches: List<OrderBatchDetail>,
    val displayNumber: Int? = null,
    val displayDate: LocalDate? = null,
    val promotionDiscounts: List<OrderPromotionDiscount> = emptyList(),
    val serviceCharges: List<OrderServiceChargeDetail> = emptyList(),
    val pendingShiftExtension: OrderPendingShiftExtension? = null,
)

data class OrderPendingShiftExtension(
    val requestId: Long,
    val orderId: Long,
    val tableSessionId: Long,
    val tabId: Long,
    val tableId: Long,
    val tableNumber: Int,
    val durationMinutes: Int,
    val priceMinor: Long,
    val currency: String,
    val requestedAt: Instant,
    val status: String,
)

data class OrderBatchDetail(
    val batchId: Long,
    val status: OrderWorkflowStatus,
    val source: String,
    val comment: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val rejectedReasonCode: String?,
    val rejectedReasonText: String?,
    val authorUserId: Long? = null,
    val guestDisplayName: String? = null,
    val items: List<OrderBatchItemDetail>,
    val promotionDiscounts: List<OrderPromotionDiscount> = emptyList(),
)

data class OrderPromotionDiscount(
    val label: String,
    val discountMinor: Long,
    val currency: String,
    val ruleType: String? = null,
)

data class OrderServiceChargeDetail(
    val id: Long,
    val source: String,
    val sourceRequestId: Long?,
    val label: String,
    val qty: Int,
    val unitPriceMinor: Long,
    val totalMinor: Long,
    val currency: String,
    val createdAt: Instant,
)

data class OrderBatchItemDetail(
    val batchItemId: Long,
    val itemId: Long,
    val name: String,
    val qty: Int,
    val selectedOption: OrderBatchItemSelectedOption? = null,
    val preferenceNote: String? = null,
    val priceMinor: Long? = null,
    val currency: String? = null,
    val isExcluded: Boolean = false,
    val excludedReasonText: String? = null,
    val discountPercent: Int? = null,
    val promoDiscountMinor: Long = 0L,
    val itemStatus: OrderBatchItemStatus = OrderBatchItemStatus.ACTIVE,
    val canceledReasonCode: String? = null,
    val canceledReasonText: String? = null,
    val canceledAt: Instant? = null,
    val canceledByUserId: Long? = null,
)

data class OrderBatchItemSelectedOption(
    val optionId: Long? = null,
    val name: String,
    val priceDeltaMinor: Long,
)

enum class OrderBatchItemStatus(
    val dbValue: String,
) {
    ACTIVE("ACTIVE"),
    CANCELED("CANCELED"),
    ;

    companion object {
        fun fromDb(raw: String?): OrderBatchItemStatus =
            entries.firstOrNull { it.dbValue.equals(raw, ignoreCase = true) } ?: ACTIVE
    }
}

data class OrderAuditEntry(
    val orderId: Long,
    val actorUserId: Long,
    val actorRole: String,
    val action: String,
    val fromStatus: OrderWorkflowStatus,
    val toStatus: OrderWorkflowStatus,
    val reasonCode: String?,
    val reasonText: String?,
    val createdAt: Instant,
)

data class OrderActionActor(
    val userId: Long,
    val role: VenueRole,
)

data class OrderStatusUpdateResult(
    val orderId: Long,
    val status: OrderWorkflowStatus,
    val updatedAt: Instant,
    val applied: Boolean,
)

data class BatchStatusUpdateResult(
    val orderId: Long,
    val batchId: Long,
    val status: OrderWorkflowStatus,
    val updatedAt: Instant,
    val applied: Boolean,
)

data class CancelBatchItemResult(
    val orderId: Long,
    val batchId: Long,
    val batchItemId: Long,
    val itemName: String,
    val guestUserId: Long?,
    val applied: Boolean,
)

class VenueOrdersRepository(
    private val dataSource: DataSource?,
    private val analyticsEventRepository: AnalyticsEventRepository? = null,
    private val visitRepository: VisitRepository? = null,
    private val visitFeedbackRepository: VisitFeedbackRepository? = null,
    private val loyaltyRepository: LoyaltyRepository? = null,
) {
    private val logger = LoggerFactory.getLogger(VenueOrdersRepository::class.java)

    suspend fun listQueue(
        venueId: Long,
        status: OrderBatchStatus,
        limit: Int,
        cursor: OrderQueueCursor?,
    ): OrderQueueResult {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val items =
                        try {
                            loadQueueItems(connection, venueId, status, limit, cursor, itemsCountFromBatchItems = true)
                        } catch (e: SQLException) {
                            logger.warn(
                                "venue orders queue primary SQL failed; fallback without item count (venueId={}, " +
                                    "status={}, sqlState={}): {}",
                                venueId,
                                status.dbValue,
                                e.sqlState,
                                e.message,
                            )
                            loadQueueItems(connection, venueId, status, limit, cursor, itemsCountFromBatchItems = false)
                        }
                    val hasMore = items.size > limit
                    val trimmed = if (hasMore) items.dropLast(1) else items
                    val nextCursor =
                        if (hasMore) {
                            val last = trimmed.last()
                            OrderQueueCursor(last.createdAt, last.batchId)
                        } else {
                            null
                        }
                    OrderQueueResult(items = trimmed, nextCursor = nextCursor)
                }
            } catch (e: SQLException) {
                logger.warn(
                    "venue orders queue SQL failed (venueId={}, status={}, sqlState={}): {}",
                    venueId,
                    status.dbValue,
                    e.sqlState,
                    e.message,
                )
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listOperationalQueueByOrder(
        venueId: Long,
        limit: Int,
    ): List<OrderQueueItem> {
        return listOperationalQueueByOrder(
            venueId = venueId,
            status = null,
            limit = limit,
            cursor = null,
        ).items
    }

    suspend fun listOperationalQueueByOrder(
        venueId: Long,
        status: OrderBatchStatus?,
        limit: Int,
        cursor: OrderQueueCursor?,
    ): OrderQueueResult {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val items = loadOperationalQueueByOrderItems(connection, venueId, status, limit, cursor)
                    val hasMore = items.size > limit
                    val trimmed = if (hasMore) items.dropLast(1) else items
                    val discountsByOrder = loadPromotionDiscountsByOrder(connection, trimmed.map { it.orderId })
                    val pendingExtensionsByOrder =
                        loadPendingShiftExtensionsByOrderIds(connection, venueId, trimmed.map { it.orderId })
                    val withDiscounts =
                        trimmed.map { item ->
                            item.copy(
                                promotionDiscounts = discountsByOrder[item.orderId].orEmpty(),
                                pendingShiftExtension = pendingExtensionsByOrder[item.orderId],
                            )
                        }
                    val nextCursor =
                        if (hasMore) {
                            val last = trimmed.last()
                            OrderQueueCursor(last.createdAt, last.batchId)
                        } else {
                            null
                        }
                    OrderQueueResult(items = withDiscounts, nextCursor = nextCursor)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun loadOperationalQueueByOrderItems(
        connection: Connection,
        venueId: Long,
        status: OrderBatchStatus?,
        limit: Int,
        cursor: OrderQueueCursor?,
    ): MutableList<OrderQueueItem> {
        val items = mutableListOf<OrderQueueItem>()
        val sql =
            buildString {
                append(
                    """
                    SELECT o.id AS order_id,
                           o.display_number AS display_number,
                           o.display_date AS display_date,
                           vt.table_number AS table_number,
                           ob.id AS batch_id,
                           ob.created_at AS created_at,
                           ob.guest_comment AS guest_comment,
                           ob.status AS status,
                           u.guest_display_name AS guest_display_name,
                           (
                               SELECT COUNT(*)
                               FROM order_batch_items obi
                               WHERE obi.order_batch_id = ob.id
                                 AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
                           ) AS items_count,
                           (
                               SELECT COUNT(*)
                               FROM order_batches ob_active
                               WHERE ob_active.order_id = o.id
                                 AND ob_active.status IN ('NEW', 'ACCEPTED', 'PREPARING', 'DELIVERING', 'DELIVERED')
                           ) AS active_batches_count,
                           (
                               SELECT COALESCE(SUM(promo.discount_minor), 0)
                               FROM order_batches ob_total
                               JOIN order_batch_items obi_total ON obi_total.order_batch_id = ob_total.id
                               LEFT JOIN (
                                   SELECT order_batch_item_id, SUM(discount_minor) AS discount_minor
                                   FROM order_batch_item_promotion_adjustments
                                   GROUP BY order_batch_item_id
                               ) promo ON promo.order_batch_item_id = obi_total.id
                               WHERE ob_total.order_id = o.id
                                 AND ob_total.status <> 'REJECTED'
                                 AND ob_total.status <> 'CLOSED'
                                 AND ob_total.rejected_reason_code IS NULL
                                 AND ob_total.rejected_reason_text IS NULL
                                 AND obi_total.is_excluded = FALSE
                                 AND COALESCE(obi_total.item_status, 'ACTIVE') = 'ACTIVE'
                           ) AS promo_discount_minor,
                           (
                               SELECT GREATEST(
                                   COALESCE(
                                       SUM(
                                           (
                                               (
                                                   COALESCE(mi_total.price_minor, 0)
                                                   + COALESCE(obiop_total.price_delta_minor_snapshot, 0)
                                               ) * obi_total.qty
                                           )
                                           - (
                                               (
                                                   COALESCE(mi_total.price_minor, 0)
                                                   + COALESCE(obiop_total.price_delta_minor_snapshot, 0)
                                               ) * obi_total.qty * COALESCE(obi_total.discount_percent, 0) / 100
                                           )
                                           - COALESCE(promo.discount_minor, 0)
                                       ),
                                       0
                                   ),
                                   0
                               )
                               FROM order_batches ob_total
                               JOIN order_batch_items obi_total ON obi_total.order_batch_id = ob_total.id
                               LEFT JOIN menu_items mi_total ON mi_total.id = obi_total.menu_item_id
                               LEFT JOIN order_batch_item_options obiop_total
                                 ON obiop_total.order_batch_item_id = obi_total.id
                               LEFT JOIN (
                                   SELECT order_batch_item_id, SUM(discount_minor) AS discount_minor
                                   FROM order_batch_item_promotion_adjustments
                                   GROUP BY order_batch_item_id
                               ) promo ON promo.order_batch_item_id = obi_total.id
                               WHERE ob_total.order_id = o.id
                                 AND ob_total.status <> 'REJECTED'
                                 AND ob_total.status <> 'CLOSED'
                                 AND ob_total.rejected_reason_code IS NULL
                                 AND ob_total.rejected_reason_text IS NULL
                                 AND obi_total.is_excluded = FALSE
                                 AND COALESCE(obi_total.item_status, 'ACTIVE') = 'ACTIVE'
                           ) AS payable_minor,
                           (
                               SELECT MIN(mi_total.currency)
                               FROM order_batches ob_total
                               JOIN order_batch_items obi_total ON obi_total.order_batch_id = ob_total.id
                               LEFT JOIN menu_items mi_total ON mi_total.id = obi_total.menu_item_id
                               WHERE ob_total.order_id = o.id
                                 AND ob_total.status <> 'REJECTED'
                                 AND ob_total.status <> 'CLOSED'
                                 AND ob_total.rejected_reason_code IS NULL
                                 AND ob_total.rejected_reason_text IS NULL
                                 AND obi_total.is_excluded = FALSE
                                 AND COALESCE(obi_total.item_status, 'ACTIVE') = 'ACTIVE'
                           ) AS currency
                    FROM orders o
                    JOIN venue_tables vt ON vt.id = o.table_id
                    JOIN order_batches ob
                      ON ob.id = (
                          SELECT ob2.id
                          FROM order_batches ob2
                          WHERE ob2.order_id = o.id
                            AND ob2.status IN ('NEW', 'ACCEPTED', 'PREPARING', 'DELIVERING', 'DELIVERED')
                    """.trimIndent(),
                )
                if (status != null) {
                    append("\n")
                    append("                            AND ob2.status = ?")
                }
                append("\n")
                append(
                    """
                          ORDER BY ob2.created_at DESC, ob2.id DESC
                          LIMIT 1
                      )
                    LEFT JOIN users u
                      ON u.telegram_user_id = COALESCE(
                          ob.author_user_id,
                          (
                              SELECT gbi.user_id
                              FROM guest_batch_idempotency gbi
                              WHERE gbi.batch_id = ob.id
                              ORDER BY gbi.id DESC
                              LIMIT 1
                          )
                      )
                    WHERE o.venue_id = ?
                      AND o.status = 'ACTIVE'
                    """.trimIndent(),
                )
                if (cursor != null) {
                    append("\n")
                    append(
                        """
                        AND (
                            ob.created_at < ?
                            OR (ob.created_at = ? AND ob.id < ?)
                        )
                        """.trimIndent(),
                    )
                }
                append("\n")
                append(
                    """
                    ORDER BY ob.created_at DESC, ob.id DESC
                    LIMIT ?
                    """.trimIndent(),
                )
            }
        connection.prepareStatement(sql).use { statement ->
            var index = 1
            if (status != null) {
                statement.setString(index++, status.dbValue)
            }
            statement.setLong(index++, venueId)
            if (cursor != null) {
                val ts = Timestamp.from(cursor.createdAt)
                statement.setTimestamp(index++, ts)
                statement.setTimestamp(index++, ts)
                statement.setLong(index++, cursor.batchId)
            }
            statement.setInt(index, limit + 1)
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    val statusRaw = rs.getString("status")
                    val mappedStatus =
                        OrderBatchStatus.fromDb(statusRaw)?.toWorkflow()
                            ?: OrderWorkflowStatus.NEW
                    items.add(
                        OrderQueueItem(
                            orderId = rs.getLong("order_id"),
                            batchId = rs.getLong("batch_id"),
                            tableNumber = rs.getInt("table_number"),
                            createdAt = rs.getTimestamp("created_at").toInstant(),
                            comment = rs.getString("guest_comment"),
                            itemsCount = rs.getInt("items_count"),
                            status = mappedStatus,
                            activeBatchesCount = rs.getInt("active_batches_count"),
                            displayNumber =
                                rs.getInt("display_number").let {
                                        value ->
                                    if (rs.wasNull()) null else value
                                },
                            displayDate = rs.getDate("display_date")?.toLocalDate(),
                            guestDisplayName = rs.getString("guest_display_name"),
                            promoDiscountMinor = rs.getLong("promo_discount_minor"),
                            payableMinor =
                                rs.getLong("payable_minor").let {
                                        value ->
                                    if (rs.wasNull()) null else value
                                },
                            currency = rs.getString("currency"),
                        ),
                    )
                }
            }
        }
        return items
    }

    private fun loadQueueItems(
        connection: Connection,
        venueId: Long,
        status: OrderBatchStatus,
        limit: Int,
        cursor: OrderQueueCursor?,
        itemsCountFromBatchItems: Boolean,
    ): MutableList<OrderQueueItem> {
        val items = mutableListOf<OrderQueueItem>()
        val itemsCountExpr =
            if (itemsCountFromBatchItems) {
                """
                (
                    SELECT COUNT(*)
                    FROM order_batch_items obi
                    WHERE obi.order_batch_id = ob.id
                      AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
                )
                """.trimIndent()
            } else {
                "0"
            }
        val sql =
            buildString {
                append(
                    """
                    SELECT ob.id AS batch_id,
                           ob.created_at AS created_at,
                           ob.guest_comment AS guest_comment,
                           ob.status AS status,
                           o.id AS order_id,
                           o.display_number AS display_number,
                           o.display_date AS display_date,
                           vt.table_number AS table_number,
                           u.guest_display_name AS guest_display_name,
                           $itemsCountExpr AS items_count
                    FROM order_batches ob
                    JOIN orders o ON o.id = ob.order_id
                    JOIN venue_tables vt ON vt.id = o.table_id
                    LEFT JOIN users u
                      ON u.telegram_user_id = COALESCE(
                          ob.author_user_id,
                          (
                              SELECT gbi.user_id
                              FROM guest_batch_idempotency gbi
                              WHERE gbi.batch_id = ob.id
                              ORDER BY gbi.id DESC
                              LIMIT 1
                          )
                      )
                    WHERE o.venue_id = ?
                      AND o.status = 'ACTIVE'
                      AND ob.status = ?
                    """.trimIndent(),
                )
                if (cursor != null) {
                    append("\n")
                    append(
                        """
                        AND (
                            ob.created_at < ?
                            OR (ob.created_at = ? AND ob.id < ?)
                        )
                        """.trimIndent(),
                    )
                }
                append("\n")
                append(
                    """
                    ORDER BY ob.created_at DESC, ob.id DESC
                    LIMIT ?
                    """.trimIndent(),
                )
            }
        connection.prepareStatement(sql).use { statement ->
            var index = 1
            statement.setLong(index++, venueId)
            statement.setString(index++, status.dbValue)
            if (cursor != null) {
                val ts = Timestamp.from(cursor.createdAt)
                statement.setTimestamp(index++, ts)
                statement.setTimestamp(index++, ts)
                statement.setLong(index++, cursor.batchId)
            }
            statement.setInt(index, limit + 1)
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    val statusRaw = rs.getString("status")
                    val mappedStatus =
                        OrderBatchStatus.fromDb(statusRaw)?.toWorkflow()
                            ?: OrderWorkflowStatus.NEW
                    items.add(
                        OrderQueueItem(
                            orderId = rs.getLong("order_id"),
                            batchId = rs.getLong("batch_id"),
                            tableNumber = rs.getInt("table_number"),
                            createdAt = rs.getTimestamp("created_at").toInstant(),
                            comment = rs.getString("guest_comment"),
                            itemsCount = rs.getInt("items_count"),
                            status = mappedStatus,
                            displayNumber =
                                rs.getInt("display_number").let {
                                        value ->
                                    if (rs.wasNull()) null else value
                                },
                            displayDate = rs.getDate("display_date")?.toLocalDate(),
                            guestDisplayName = rs.getString("guest_display_name"),
                        ),
                    )
                }
            }
        }
        return items
    }

    suspend fun loadOrderDetail(
        venueId: Long,
        orderId: Long,
    ): OrderDetail? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val orderHeader =
                        connection.prepareStatement(
                            """
                            SELECT o.id,
                                   o.status,
                                   o.display_number,
                                   o.display_date,
                                   o.created_at,
                                   o.updated_at,
                                   o.venue_id,
                                   vt.id AS table_id,
                                   vt.table_number
                            FROM orders o
                            JOIN venue_tables vt ON vt.id = o.table_id
                            WHERE o.id = ? AND o.venue_id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, orderId)
                            statement.setLong(2, venueId)
                            statement.executeQuery().use { rs ->
                                if (rs.next()) {
                                    OrderHeader(
                                        status = rs.getString("status"),
                                        displayNumber =
                                            rs.getInt("display_number").let {
                                                    value ->
                                                if (rs.wasNull()) null else value
                                            },
                                        displayDate = rs.getDate("display_date")?.toLocalDate(),
                                        createdAt = rs.getTimestamp("created_at").toInstant(),
                                        updatedAt = rs.getTimestamp("updated_at").toInstant(),
                                        tableId = rs.getLong("table_id"),
                                        tableNumber = rs.getInt("table_number"),
                                    )
                                } else {
                                    null
                                }
                            }
                        } ?: return@use null

                    val batches =
                        connection.prepareStatement(
                            """
                            SELECT ob.id,
                                   ob.status,
                                   ob.source,
                                   ob.guest_comment,
                                   ob.created_at,
                                   ob.updated_at,
                                   ob.rejected_reason_code,
                                   ob.rejected_reason_text,
                                   COALESCE(
                                       ob.author_user_id,
                                       (
                                           SELECT gbi.user_id
                                           FROM guest_batch_idempotency gbi
                                           WHERE gbi.batch_id = ob.id
                                           ORDER BY gbi.id DESC
                                           LIMIT 1
                                       )
                                   ) AS guest_user_id,
                                   u.guest_display_name AS guest_display_name
                            FROM order_batches ob
                            LEFT JOIN users u
                              ON u.telegram_user_id = COALESCE(
                                  ob.author_user_id,
                                  (
                                      SELECT gbi.user_id
                                      FROM guest_batch_idempotency gbi
                                      WHERE gbi.batch_id = ob.id
                                      ORDER BY gbi.id DESC
                                      LIMIT 1
                                  )
                              )
                            WHERE order_id = ?
                            ORDER BY ob.created_at, ob.id
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, orderId)
                            statement.executeQuery().use { rs ->
                                val result = mutableListOf<OrderBatchDetail>()
                                while (rs.next()) {
                                    val batchStatus =
                                        OrderBatchStatus.fromDb(rs.getString("status"))
                                            ?.toWorkflow() ?: OrderWorkflowStatus.NEW
                                    result.add(
                                        OrderBatchDetail(
                                            batchId = rs.getLong("id"),
                                            status = batchStatus,
                                            source = rs.getString("source"),
                                            comment = rs.getString("guest_comment"),
                                            createdAt = rs.getTimestamp("created_at").toInstant(),
                                            updatedAt = rs.getTimestamp("updated_at").toInstant(),
                                            rejectedReasonCode = rs.getString("rejected_reason_code"),
                                            rejectedReasonText = rs.getString("rejected_reason_text"),
                                            authorUserId =
                                                rs.getLong("guest_user_id").let {
                                                        value ->
                                                    if (rs.wasNull()) null else value
                                                },
                                            guestDisplayName = rs.getString("guest_display_name"),
                                            items = emptyList(),
                                        ),
                                    )
                                }
                                result
                            }
                        }

                    val batchIds = batches.map { it.batchId }
                    val itemsByBatch = loadBatchItems(connection, batchIds)
                    val promotionDiscountsByBatch = loadPromotionDiscountsByBatch(connection, batchIds)
                    val serviceCharges = loadServiceCharges(connection, orderId)
                    val pendingShiftExtension =
                        loadPendingShiftExtensionsByOrderIds(connection, venueId, listOf(orderId))[orderId]
                    val mappedBatches =
                        batches.map { batch ->
                            batch.copy(
                                items = itemsByBatch[batch.batchId].orEmpty(),
                                promotionDiscounts = promotionDiscountsByBatch[batch.batchId].orEmpty(),
                            )
                        }

                    val workflowStatus = resolveOrderWorkflowStatus(orderHeader.status, mappedBatches)

                    OrderDetail(
                        orderId = orderId,
                        displayNumber = orderHeader.displayNumber,
                        displayDate = orderHeader.displayDate,
                        venueId = venueId,
                        tableId = orderHeader.tableId,
                        tableNumber = orderHeader.tableNumber,
                        status = workflowStatus,
                        createdAt = orderHeader.createdAt,
                        updatedAt = orderHeader.updatedAt,
                        batches = mappedBatches,
                        promotionDiscounts = mappedBatches.flatMap { it.promotionDiscounts }.mergePromotionDiscounts(),
                        serviceCharges = serviceCharges,
                        pendingShiftExtension = pendingShiftExtension,
                    )
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun loadAudit(
        venueId: Long,
        orderId: Long,
    ): List<OrderAuditEntry> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val exists =
                        connection.prepareStatement(
                            "SELECT 1 FROM orders WHERE id = ? AND venue_id = ?",
                        ).use { statement ->
                            statement.setLong(1, orderId)
                            statement.setLong(2, venueId)
                            statement.executeQuery().use { rs -> rs.next() }
                        }
                    if (!exists) {
                        return@use emptyList()
                    }
                    connection.prepareStatement(
                        """
                        SELECT order_id, actor_user_id, actor_role, action, from_status, to_status,
                               reason_code, reason_text, created_at
                        FROM order_audit_log
                        WHERE order_id = ?
                        ORDER BY created_at DESC, id DESC
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, orderId)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<OrderAuditEntry>()
                            while (rs.next()) {
                                val fromStatus =
                                    OrderWorkflowStatus.fromApi(rs.getString("from_status"))
                                        ?: OrderWorkflowStatus.NEW
                                val toStatus =
                                    OrderWorkflowStatus.fromApi(rs.getString("to_status"))
                                        ?: OrderWorkflowStatus.NEW
                                result.add(
                                    OrderAuditEntry(
                                        orderId = rs.getLong("order_id"),
                                        actorUserId = rs.getLong("actor_user_id"),
                                        actorRole = rs.getString("actor_role"),
                                        action = rs.getString("action"),
                                        fromStatus = fromStatus,
                                        toStatus = toStatus,
                                        reasonCode = rs.getString("reason_code"),
                                        reasonText = rs.getString("reason_text"),
                                        createdAt = rs.getTimestamp("created_at").toInstant(),
                                    ),
                                )
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

    suspend fun updateOrderStatus(
        venueId: Long,
        orderId: Long,
        nextStatus: OrderWorkflowStatus,
        actor: OrderActionActor,
    ): OrderStatusUpdateResult? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    try {
                        connection.autoCommit = false
                        val orderRow =
                            selectOrderForUpdate(connection, orderId, venueId) ?: run {
                                runCatching { connection.rollback() }
                                return@use null
                            }
                        val current = resolveOrderWorkflowStatus(orderRow.status, orderRow.batches)
                        val transitionAllowed =
                            if (nextStatus == OrderWorkflowStatus.CLOSED) {
                                canCloseOrder(orderRow)
                            } else {
                                allowedNextStatuses(current).contains(nextStatus)
                            }
                        if (!transitionAllowed) {
                            runCatching { connection.rollback() }
                            return@use OrderStatusUpdateResult(
                                orderId = orderId,
                                status = current,
                                updatedAt = orderRow.updatedAt,
                                applied = false,
                            )
                        }
                        val now = OffsetDateTime.now(ZoneOffset.UTC)
                        var changedBatchId: Long? = null
                        if (nextStatus == OrderWorkflowStatus.CLOSED) {
                            updateOrderStatusOnly(connection, orderId)
                            val visits = visitRepository?.recordOrderClosedVisits(connection, orderId, now.toInstant())
                            loyaltyRepository?.accrueForClosedOrder(connection, orderId)
                            visits?.visitIds.orEmpty().forEach { visitId ->
                                visitFeedbackRepository?.scheduleFeedbackRequestForVisit(
                                    connection,
                                    visitId,
                                    now.toInstant(),
                                )
                            }
                        } else {
                            val batchStatus =
                                OrderBatchStatus.fromWorkflow(nextStatus)
                                    ?: throw IllegalStateException("Missing batch status for $nextStatus")
                            val latestBatchId =
                                currentWorkflowBatchId(orderRow.batches)
                                    ?: run {
                                        runCatching { connection.rollback() }
                                        return@use OrderStatusUpdateResult(
                                            orderId = orderId,
                                            status = current,
                                            updatedAt = orderRow.updatedAt,
                                            applied = false,
                                        )
                                    }
                            val updated = updateLatestBatchStatus(connection, latestBatchId, batchStatus.dbValue, now)
                            if (updated != 1) {
                                runCatching { connection.rollback() }
                                return@use OrderStatusUpdateResult(
                                    orderId = orderId,
                                    status = current,
                                    updatedAt = orderRow.updatedAt,
                                    applied = false,
                                )
                            }
                            changedBatchId = latestBatchId
                            updateOrderTimestamp(connection, orderId, now)
                        }
                        insertAudit(
                            connection = connection,
                            orderId = orderId,
                            actor = actor,
                            action = "STATUS_CHANGE",
                            fromStatus = current,
                            toStatus = nextStatus,
                            reasonCode = null,
                            reasonText = null,
                        )
                        if (changedBatchId != null) {
                            analyticsEventRepository?.append(
                                connection = connection,
                                event =
                                    AnalyticsEventRecord(
                                        eventType = "batch_status_changed",
                                        payload =
                                            analyticsCorrelationPayload(
                                                venueId = venueId,
                                                orderId = orderId,
                                                batchId = changedBatchId,
                                                extra =
                                                    mapOf(
                                                        "fromStatus" to current.toApi(),
                                                        "toStatus" to nextStatus.toApi(),
                                                    ),
                                            ),
                                        venueId = venueId,
                                        orderId = orderId,
                                        batchId = changedBatchId,
                                        idempotencyKey =
                                            buildString {
                                                append("batch_status_changed:")
                                                append(venueId)
                                                append(':')
                                                append(orderId)
                                                append(':')
                                                append(changedBatchId)
                                                append(':')
                                                append(nextStatus.toApi())
                                            },
                                    ),
                            )
                        }
                        connection.commit()
                        OrderStatusUpdateResult(
                            orderId = orderId,
                            status = nextStatus,
                            updatedAt = now.toInstant(),
                            applied = true,
                        )
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw DatabaseUnavailableException()
                    } catch (e: Exception) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = true }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateBatchStatus(
        venueId: Long,
        batchId: Long,
        expectedCurrentStatus: OrderBatchStatus,
        nextStatus: OrderBatchStatus,
        actor: OrderActionActor,
    ): BatchStatusUpdateResult? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    try {
                        connection.autoCommit = false
                        val batchRow =
                            selectBatchForUpdate(connection, venueId, batchId) ?: run {
                                runCatching { connection.rollback() }
                                return@use null
                            }
                        val currentWorkflow = batchRow.status.toWorkflow()
                        if (
                            !batchRow.orderStatus.equals("ACTIVE", ignoreCase = true) ||
                            batchRow.status != expectedCurrentStatus
                        ) {
                            runCatching { connection.rollback() }
                            return@use BatchStatusUpdateResult(
                                orderId = batchRow.orderId,
                                batchId = batchId,
                                status = currentWorkflow,
                                updatedAt = batchRow.updatedAt,
                                applied = false,
                            )
                        }
                        val now = OffsetDateTime.now(ZoneOffset.UTC)
                        val updated =
                            connection.prepareStatement(
                                """
                                UPDATE order_batches
                                SET status = ?, updated_at = ?
                                WHERE id = ?
                                  AND status = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, nextStatus.dbValue)
                                statement.setObject(2, now)
                                statement.setLong(3, batchId)
                                statement.setString(4, expectedCurrentStatus.dbValue)
                                statement.executeUpdate()
                            }
                        if (updated != 1) {
                            runCatching { connection.rollback() }
                            return@use BatchStatusUpdateResult(
                                orderId = batchRow.orderId,
                                batchId = batchId,
                                status = currentWorkflow,
                                updatedAt = batchRow.updatedAt,
                                applied = false,
                            )
                        }
                        updateOrderTimestamp(connection, batchRow.orderId, now)
                        insertAudit(
                            connection = connection,
                            orderId = batchRow.orderId,
                            actor = actor,
                            action = "BATCH_STATUS_CHANGE",
                            fromStatus = currentWorkflow,
                            toStatus = nextStatus.toWorkflow(),
                            reasonCode = null,
                            reasonText = null,
                        )
                        analyticsEventRepository?.append(
                            connection = connection,
                            event =
                                AnalyticsEventRecord(
                                    eventType = "batch_status_changed",
                                    payload =
                                        analyticsCorrelationPayload(
                                            venueId = venueId,
                                            orderId = batchRow.orderId,
                                            batchId = batchId,
                                            extra =
                                                mapOf(
                                                    "fromStatus" to currentWorkflow.toApi(),
                                                    "toStatus" to nextStatus.toWorkflow().toApi(),
                                                ),
                                        ),
                                    venueId = venueId,
                                    orderId = batchRow.orderId,
                                    batchId = batchId,
                                    idempotencyKey =
                                        "batch_status_changed:$venueId:${batchRow.orderId}:$batchId:" +
                                            nextStatus.toWorkflow().toApi(),
                                ),
                        )
                        connection.commit()
                        BatchStatusUpdateResult(
                            orderId = batchRow.orderId,
                            batchId = batchId,
                            status = nextStatus.toWorkflow(),
                            updatedAt = now.toInstant(),
                            applied = true,
                        )
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw DatabaseUnavailableException()
                    } catch (e: Exception) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = true }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun acceptAllNewBatches(
        venueId: Long,
        orderId: Long,
        actor: OrderActionActor,
    ): OrderStatusUpdateResult? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    try {
                        connection.autoCommit = false
                        val orderRow =
                            selectOrderForUpdate(connection, orderId, venueId) ?: run {
                                runCatching { connection.rollback() }
                                return@use null
                            }
                        val current = resolveOrderWorkflowStatus(orderRow.status, orderRow.batches)
                        if (current == OrderWorkflowStatus.CLOSED) {
                            runCatching { connection.rollback() }
                            return@use OrderStatusUpdateResult(
                                orderId = orderId,
                                status = current,
                                updatedAt = orderRow.updatedAt,
                                applied = false,
                            )
                        }
                        val newBatchIds =
                            orderRow.batches
                                .filter { it.status == OrderWorkflowStatus.NEW }
                                .map { it.batchId }
                        if (newBatchIds.isEmpty()) {
                            runCatching { connection.rollback() }
                            return@use OrderStatusUpdateResult(
                                orderId = orderId,
                                status = current,
                                updatedAt = orderRow.updatedAt,
                                applied = false,
                            )
                        }
                        val now = OffsetDateTime.now(ZoneOffset.UTC)
                        val updated =
                            updateNewBatchesStatus(
                                connection = connection,
                                batchIds = newBatchIds,
                                status = OrderBatchStatus.ACCEPTED.dbValue,
                                now = now,
                            )
                        if (updated != newBatchIds.size) {
                            runCatching { connection.rollback() }
                            return@use OrderStatusUpdateResult(
                                orderId = orderId,
                                status = current,
                                updatedAt = orderRow.updatedAt,
                                applied = false,
                            )
                        }
                        updateOrderTimestamp(connection, orderId, now)
                        val resultingStatus =
                            resolveOrderWorkflowStatus(orderRow.status, loadBatchesForWorkflow(connection, orderId))
                        insertAudit(
                            connection = connection,
                            orderId = orderId,
                            actor = actor,
                            action = "ACCEPT_ALL_NEW_BATCHES",
                            fromStatus = current,
                            toStatus = resultingStatus,
                            reasonCode = null,
                            reasonText = null,
                        )
                        newBatchIds.forEach { batchId ->
                            analyticsEventRepository?.append(
                                connection = connection,
                                event =
                                    AnalyticsEventRecord(
                                        eventType = "batch_status_changed",
                                        payload =
                                            analyticsCorrelationPayload(
                                                venueId = venueId,
                                                orderId = orderId,
                                                batchId = batchId,
                                                extra =
                                                    mapOf(
                                                        "fromStatus" to OrderWorkflowStatus.NEW.toApi(),
                                                        "toStatus" to OrderWorkflowStatus.ACCEPTED.toApi(),
                                                    ),
                                            ),
                                        venueId = venueId,
                                        orderId = orderId,
                                        batchId = batchId,
                                        idempotencyKey = "batch_status_changed:$venueId:$orderId:$batchId:accepted",
                                    ),
                            )
                        }
                        connection.commit()
                        OrderStatusUpdateResult(
                            orderId = orderId,
                            status = resultingStatus,
                            updatedAt = now.toInstant(),
                            applied = true,
                        )
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw DatabaseUnavailableException()
                    } catch (e: Exception) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = true }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun deliverAllAcceptedBatches(
        venueId: Long,
        orderId: Long,
        actor: OrderActionActor,
    ): OrderStatusUpdateResult? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    try {
                        connection.autoCommit = false
                        val orderRow =
                            selectOrderForUpdate(connection, orderId, venueId) ?: run {
                                runCatching { connection.rollback() }
                                return@use null
                            }
                        val current = resolveOrderWorkflowStatus(orderRow.status, orderRow.batches)
                        if (current == OrderWorkflowStatus.CLOSED) {
                            runCatching { connection.rollback() }
                            return@use OrderStatusUpdateResult(
                                orderId = orderId,
                                status = current,
                                updatedAt = orderRow.updatedAt,
                                applied = false,
                            )
                        }
                        val acceptedBatchIds =
                            orderRow.batches
                                .filter { it.status == OrderWorkflowStatus.ACCEPTED }
                                .map { it.batchId }
                        if (acceptedBatchIds.isEmpty()) {
                            runCatching { connection.rollback() }
                            return@use OrderStatusUpdateResult(
                                orderId = orderId,
                                status = current,
                                updatedAt = orderRow.updatedAt,
                                applied = false,
                            )
                        }
                        val now = OffsetDateTime.now(ZoneOffset.UTC)
                        val updated =
                            updateAcceptedBatchesStatus(
                                connection = connection,
                                batchIds = acceptedBatchIds,
                                status = OrderBatchStatus.DELIVERED.dbValue,
                                now = now,
                            )
                        if (updated != acceptedBatchIds.size) {
                            runCatching { connection.rollback() }
                            return@use OrderStatusUpdateResult(
                                orderId = orderId,
                                status = current,
                                updatedAt = orderRow.updatedAt,
                                applied = false,
                            )
                        }
                        updateOrderTimestamp(connection, orderId, now)
                        val resultingStatus =
                            resolveOrderWorkflowStatus(orderRow.status, loadBatchesForWorkflow(connection, orderId))
                        insertAudit(
                            connection = connection,
                            orderId = orderId,
                            actor = actor,
                            action = "DELIVER_ALL_ACCEPTED_BATCHES",
                            fromStatus = current,
                            toStatus = resultingStatus,
                            reasonCode = null,
                            reasonText = null,
                        )
                        acceptedBatchIds.forEach { batchId ->
                            analyticsEventRepository?.append(
                                connection = connection,
                                event =
                                    AnalyticsEventRecord(
                                        eventType = "batch_status_changed",
                                        payload =
                                            analyticsCorrelationPayload(
                                                venueId = venueId,
                                                orderId = orderId,
                                                batchId = batchId,
                                                extra =
                                                    mapOf(
                                                        "fromStatus" to OrderWorkflowStatus.ACCEPTED.toApi(),
                                                        "toStatus" to OrderWorkflowStatus.DELIVERED.toApi(),
                                                    ),
                                            ),
                                        venueId = venueId,
                                        orderId = orderId,
                                        batchId = batchId,
                                        idempotencyKey = "batch_status_changed:$venueId:$orderId:$batchId:delivered",
                                    ),
                            )
                        }
                        connection.commit()
                        OrderStatusUpdateResult(
                            orderId = orderId,
                            status = resultingStatus,
                            updatedAt = now.toInstant(),
                            applied = true,
                        )
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw DatabaseUnavailableException()
                    } catch (e: Exception) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = true }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun rejectOrder(
        venueId: Long,
        orderId: Long,
        reasonCode: String,
        reasonText: String?,
        actor: OrderActionActor,
    ): OrderStatusUpdateResult? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    try {
                        connection.autoCommit = false
                        val orderRow =
                            selectOrderForUpdate(connection, orderId, venueId) ?: run {
                                runCatching { connection.rollback() }
                                return@use null
                            }
                        val current = resolveOrderWorkflowStatus(orderRow.status, orderRow.batches)
                        if (current == OrderWorkflowStatus.CLOSED) {
                            runCatching { connection.rollback() }
                            return@use OrderStatusUpdateResult(
                                orderId = orderId,
                                status = current,
                                updatedAt = orderRow.updatedAt,
                                applied = false,
                            )
                        }
                        val now = OffsetDateTime.now(ZoneOffset.UTC)
                        val latestBatchId =
                            currentWorkflowBatchId(orderRow.batches) ?: orderRow.batches.firstOrNull()?.batchId
                                ?: run {
                                    runCatching { connection.rollback() }
                                    return@use OrderStatusUpdateResult(
                                        orderId = orderId,
                                        status = current,
                                        updatedAt = orderRow.updatedAt,
                                        applied = false,
                                    )
                                }
                        val updated = updateLatestBatchRejected(connection, latestBatchId, reasonCode, reasonText, now)
                        if (updated != 1) {
                            runCatching { connection.rollback() }
                            return@use OrderStatusUpdateResult(
                                orderId = orderId,
                                status = current,
                                updatedAt = orderRow.updatedAt,
                                applied = false,
                            )
                        }
                        updateOrderStatusOnly(connection, orderId)
                        insertAudit(
                            connection = connection,
                            orderId = orderId,
                            actor = actor,
                            action = "REJECT",
                            fromStatus = current,
                            toStatus = OrderWorkflowStatus.CLOSED,
                            reasonCode = reasonCode,
                            reasonText = reasonText,
                        )
                        analyticsEventRepository?.append(
                            connection = connection,
                            event =
                                AnalyticsEventRecord(
                                    eventType = "batch_status_changed",
                                    payload =
                                        analyticsCorrelationPayload(
                                            venueId = venueId,
                                            orderId = orderId,
                                            batchId = latestBatchId,
                                            extra =
                                                mapOf(
                                                    "fromStatus" to current.toApi(),
                                                    "toStatus" to OrderWorkflowStatus.CLOSED.toApi(),
                                                    "reasonCode" to reasonCode,
                                                ),
                                        ),
                                    venueId = venueId,
                                    orderId = orderId,
                                    batchId = latestBatchId,
                                    idempotencyKey = "batch_status_changed:$venueId:$orderId:$latestBatchId:closed",
                                ),
                        )
                        connection.commit()
                        OrderStatusUpdateResult(
                            orderId = orderId,
                            status = OrderWorkflowStatus.CLOSED,
                            updatedAt = now.toInstant(),
                            applied = true,
                        )
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw DatabaseUnavailableException()
                    } catch (e: Exception) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = true }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun rejectLatestBatch(
        venueId: Long,
        orderId: Long,
        reasonCode: String,
        reasonText: String?,
        actor: OrderActionActor,
    ): OrderStatusUpdateResult? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    try {
                        connection.autoCommit = false
                        val orderRow =
                            selectOrderForUpdate(connection, orderId, venueId) ?: run {
                                runCatching { connection.rollback() }
                                return@use null
                            }
                        val current = resolveOrderWorkflowStatus(orderRow.status, orderRow.batches)
                        if (current == OrderWorkflowStatus.CLOSED) {
                            runCatching { connection.rollback() }
                            return@use OrderStatusUpdateResult(
                                orderId = orderId,
                                status = current,
                                updatedAt = orderRow.updatedAt,
                                applied = false,
                            )
                        }
                        val now = OffsetDateTime.now(ZoneOffset.UTC)
                        val latestBatchId =
                            currentWorkflowBatchId(orderRow.batches)
                                ?: run {
                                    runCatching { connection.rollback() }
                                    return@use OrderStatusUpdateResult(
                                        orderId = orderId,
                                        status = current,
                                        updatedAt = orderRow.updatedAt,
                                        applied = false,
                                    )
                                }
                        val updated = updateLatestBatchRejected(connection, latestBatchId, reasonCode, reasonText, now)
                        if (updated != 1) {
                            runCatching { connection.rollback() }
                            return@use OrderStatusUpdateResult(
                                orderId = orderId,
                                status = current,
                                updatedAt = orderRow.updatedAt,
                                applied = false,
                            )
                        }
                        updateOrderTimestamp(connection, orderId, now)
                        val resultingStatus =
                            resolveOrderWorkflowStatus(orderRow.status, loadBatchesForWorkflow(connection, orderId))
                        insertAudit(
                            connection = connection,
                            orderId = orderId,
                            actor = actor,
                            action = "REJECT_BATCH",
                            fromStatus = current,
                            toStatus = resultingStatus,
                            reasonCode = reasonCode,
                            reasonText = reasonText,
                        )
                        analyticsEventRepository?.append(
                            connection = connection,
                            event =
                                AnalyticsEventRecord(
                                    eventType = "batch_status_changed",
                                    payload =
                                        analyticsCorrelationPayload(
                                            venueId = venueId,
                                            orderId = orderId,
                                            batchId = latestBatchId,
                                            extra =
                                                mapOf(
                                                    "fromStatus" to current.toApi(),
                                                    "toStatus" to resultingStatus.toApi(),
                                                    "reasonCode" to reasonCode,
                                                ),
                                        ),
                                    venueId = venueId,
                                    orderId = orderId,
                                    batchId = latestBatchId,
                                    idempotencyKey = "batch_status_changed:$venueId:$orderId:$latestBatchId:rejected",
                                ),
                        )
                        connection.commit()
                        OrderStatusUpdateResult(
                            orderId = orderId,
                            status = resultingStatus,
                            updatedAt = now.toInstant(),
                            applied = true,
                        )
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw DatabaseUnavailableException()
                    } catch (e: Exception) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = true }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun excludeBatchItemFromBill(
        venueId: Long,
        orderId: Long,
        batchItemId: Long,
        reasonText: String,
        actor: OrderActionActor,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    try {
                        connection.autoCommit = false
                        val orderRow =
                            selectOrderForUpdate(connection, orderId, venueId) ?: run {
                                runCatching { connection.rollback() }
                                return@use false
                            }
                        val current = resolveOrderWorkflowStatus(orderRow.status, orderRow.batches)
                        if (current == OrderWorkflowStatus.CLOSED) {
                            runCatching { connection.rollback() }
                            return@use false
                        }
                        val now = OffsetDateTime.now(ZoneOffset.UTC)
                        val updated =
                            connection.prepareStatement(
                                """
                                UPDATE order_batch_items obi
                                SET is_excluded = TRUE,
                                    excluded_reason_text = ?,
                                    excluded_at = ?
                                WHERE obi.id = ?
                                  AND obi.is_excluded = FALSE
                                  AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
                                  AND EXISTS (
                                      SELECT 1
                                      FROM order_batches ob
                                      JOIN orders o ON o.id = ob.order_id
                                      WHERE ob.id = obi.order_batch_id
                                        AND ob.order_id = ?
                                        AND o.venue_id = ?
                                        AND o.status = 'ACTIVE'
                                        AND ob.status <> 'REJECTED'
                                  )
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, reasonText)
                                statement.setObject(2, now)
                                statement.setLong(3, batchItemId)
                                statement.setLong(4, orderId)
                                statement.setLong(5, venueId)
                                statement.executeUpdate()
                            }
                        if (updated != 1) {
                            runCatching { connection.rollback() }
                            return@use false
                        }
                        updateOrderTimestamp(connection, orderId, now)
                        insertAudit(
                            connection = connection,
                            orderId = orderId,
                            actor = actor,
                            action = "EXCLUDE_ITEM_FROM_BILL",
                            fromStatus = current,
                            toStatus = current,
                            reasonCode = "VENUE_ITEM_EXCLUDED_FROM_BILL",
                            reasonText = reasonText,
                        )
                        connection.commit()
                        true
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw DatabaseUnavailableException()
                    } catch (e: Exception) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = true }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun restoreBatchItemToBill(
        venueId: Long,
        orderId: Long,
        batchItemId: Long,
        actor: OrderActionActor,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    try {
                        connection.autoCommit = false
                        val orderRow =
                            selectOrderForUpdate(connection, orderId, venueId) ?: run {
                                runCatching { connection.rollback() }
                                return@use false
                            }
                        val current = resolveOrderWorkflowStatus(orderRow.status, orderRow.batches)
                        if (current == OrderWorkflowStatus.CLOSED) {
                            runCatching { connection.rollback() }
                            return@use false
                        }
                        val now = OffsetDateTime.now(ZoneOffset.UTC)
                        val updated =
                            connection.prepareStatement(
                                """
                                UPDATE order_batch_items obi
                                SET is_excluded = FALSE,
                                    excluded_reason_text = NULL,
                                    excluded_at = NULL
                                WHERE obi.id = ?
                                  AND obi.is_excluded = TRUE
                                  AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
                                  AND EXISTS (
                                      SELECT 1
                                      FROM order_batches ob
                                      JOIN orders o ON o.id = ob.order_id
                                      WHERE ob.id = obi.order_batch_id
                                        AND ob.order_id = ?
                                        AND o.venue_id = ?
                                        AND o.status = 'ACTIVE'
                                        AND ob.status <> 'REJECTED'
                                  )
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, batchItemId)
                                statement.setLong(2, orderId)
                                statement.setLong(3, venueId)
                                statement.executeUpdate()
                            }
                        if (updated != 1) {
                            runCatching { connection.rollback() }
                            return@use false
                        }
                        updateOrderTimestamp(connection, orderId, now)
                        insertAudit(
                            connection = connection,
                            orderId = orderId,
                            actor = actor,
                            action = "RESTORE_ITEM_TO_BILL",
                            fromStatus = current,
                            toStatus = current,
                            reasonCode = "VENUE_ITEM_RESTORED_TO_BILL",
                            reasonText = null,
                        )
                        connection.commit()
                        true
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw DatabaseUnavailableException()
                    } catch (e: Exception) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = true }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun cancelBatchItemAsUnavailable(
        venueId: Long,
        orderId: Long,
        batchItemId: Long,
        actor: OrderActionActor,
        reasonText: String = "Позиция закончилась",
    ): CancelBatchItemResult? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    try {
                        connection.autoCommit = false
                        val orderRow =
                            selectOrderForUpdate(connection, orderId, venueId) ?: run {
                                runCatching { connection.rollback() }
                                return@use null
                            }
                        val current = resolveOrderWorkflowStatus(orderRow.status, orderRow.batches)
                        if (current == OrderWorkflowStatus.CLOSED) {
                            runCatching { connection.rollback() }
                            return@use null
                        }
                        val item =
                            loadCancelableBatchItem(connection, venueId, orderId, batchItemId)
                                ?: run {
                                    runCatching { connection.rollback() }
                                    return@use null
                                }
                        if (item.itemStatus == OrderBatchItemStatus.CANCELED) {
                            runCatching { connection.rollback() }
                            return@use CancelBatchItemResult(
                                orderId = orderId,
                                batchId = item.batchId,
                                batchItemId = batchItemId,
                                itemName = item.itemName,
                                guestUserId = item.guestUserId,
                                applied = false,
                            )
                        }
                        if (item.isExcluded) {
                            runCatching { connection.rollback() }
                            return@use null
                        }
                        if (item.batchStatus !in cancelableItemBatchStatuses) {
                            runCatching { connection.rollback() }
                            return@use null
                        }
                        val now = OffsetDateTime.now(ZoneOffset.UTC)
                        val updated =
                            connection.prepareStatement(
                                """
                                UPDATE order_batch_items
                                SET item_status = 'CANCELED',
                                    canceled_reason_code = 'ITEM_UNAVAILABLE',
                                    canceled_reason_text = ?,
                                    canceled_at = ?,
                                    canceled_by_user_id = (
                                        SELECT telegram_user_id
                                        FROM users
                                        WHERE telegram_user_id = ?
                                    )
                                WHERE id = ?
                                  AND item_status = 'ACTIVE'
                                  AND is_excluded = FALSE
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, reasonText)
                                statement.setObject(2, now)
                                statement.setLong(3, actor.userId)
                                statement.setLong(4, batchItemId)
                                statement.executeUpdate()
                            }
                        if (updated != 1) {
                            runCatching { connection.rollback() }
                            return@use CancelBatchItemResult(
                                orderId = orderId,
                                batchId = item.batchId,
                                batchItemId = batchItemId,
                                itemName = item.itemName,
                                guestUserId = item.guestUserId,
                                applied = false,
                            )
                        }
                        updateOrderTimestamp(connection, orderId, now)
                        try {
                            insertAudit(
                                connection = connection,
                                orderId = orderId,
                                actor = actor,
                                action = "CANCEL_ITEM_UNAVAILABLE",
                                fromStatus = current,
                                toStatus = current,
                                reasonCode = "ITEM_UNAVAILABLE",
                                reasonText = reasonText,
                            )
                        } catch (_: SQLException) {
                            // Audit must not roll back the guest-facing correction of an unavailable item.
                        }
                        connection.commit()
                        CancelBatchItemResult(
                            orderId = orderId,
                            batchId = item.batchId,
                            batchItemId = batchItemId,
                            itemName = item.itemName,
                            guestUserId = item.guestUserId,
                            applied = true,
                        )
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw DatabaseUnavailableException()
                    } catch (e: Exception) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = true }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun setBatchItemDiscountPercent(
        venueId: Long,
        orderId: Long,
        batchItemId: Long,
        discountPercent: Int,
        actor: OrderActionActor,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    try {
                        connection.autoCommit = false
                        val orderRow =
                            selectOrderForUpdate(connection, orderId, venueId) ?: run {
                                runCatching { connection.rollback() }
                                return@use false
                            }
                        val current = resolveOrderWorkflowStatus(orderRow.status, orderRow.batches)
                        if (current == OrderWorkflowStatus.CLOSED) {
                            runCatching { connection.rollback() }
                            return@use false
                        }
                        val now = OffsetDateTime.now(ZoneOffset.UTC)
                        val updated =
                            connection.prepareStatement(
                                """
                                UPDATE order_batch_items obi
                                SET discount_percent = ?
                                WHERE obi.id = ?
                                  AND obi.is_excluded = FALSE
                                  AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
                                  AND EXISTS (
                                      SELECT 1
                                      FROM order_batches ob
                                      JOIN orders o ON o.id = ob.order_id
                                      WHERE ob.id = obi.order_batch_id
                                        AND ob.order_id = ?
                                        AND o.venue_id = ?
                                        AND o.status = 'ACTIVE'
                                        AND ob.status <> 'REJECTED'
                                  )
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setInt(1, discountPercent)
                                statement.setLong(2, batchItemId)
                                statement.setLong(3, orderId)
                                statement.setLong(4, venueId)
                                statement.executeUpdate()
                            }
                        if (updated != 1) {
                            runCatching { connection.rollback() }
                            return@use false
                        }
                        updateOrderTimestamp(connection, orderId, now)
                        insertAudit(
                            connection = connection,
                            orderId = orderId,
                            actor = actor,
                            action = "APPLY_ITEM_DISCOUNT",
                            fromStatus = current,
                            toStatus = current,
                            reasonCode = "VENUE_ITEM_DISCOUNT",
                            reasonText = "$discountPercent%",
                        )
                        connection.commit()
                        true
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw DatabaseUnavailableException()
                    } catch (e: Exception) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = true }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun resolveOrderWorkflowStatus(
        orderStatusRaw: String?,
        batches: List<OrderBatchDetail>,
    ): OrderWorkflowStatus {
        if (
            orderStatusRaw.equals("CLOSED", ignoreCase = true) ||
            orderStatusRaw.equals("CANCELLED", ignoreCase = true)
        ) {
            return OrderWorkflowStatus.CLOSED
        }
        val latestBatch = batches.filter { it.status != OrderWorkflowStatus.CLOSED }.maxByOrNull { it.createdAt }
        return latestBatch?.status ?: OrderWorkflowStatus.NEW
    }

    private fun canCloseOrder(orderRow: OrderRow): Boolean {
        if (
            orderRow.status.equals("CLOSED", ignoreCase = true) ||
            orderRow.status.equals("CANCELLED", ignoreCase = true)
        ) {
            return false
        }
        return orderRow.batches.any { batch ->
            batch.status == OrderWorkflowStatus.ACCEPTED ||
                batch.status == OrderWorkflowStatus.DELIVERED
        }
    }

    private fun currentWorkflowBatchId(batches: List<OrderBatchDetail>): Long? =
        batches
            .asSequence()
            .filter { it.status != OrderWorkflowStatus.CLOSED }
            .maxWithOrNull(compareBy<OrderBatchDetail> { it.createdAt }.thenBy { it.batchId })
            ?.batchId

    private fun loadCancelableBatchItem(
        connection: Connection,
        venueId: Long,
        orderId: Long,
        batchItemId: Long,
    ): CancelableBatchItem? {
        val locked =
            connection.prepareStatement(
                """
                SELECT obi.id
                FROM order_batch_items obi
                WHERE obi.id = ?
                  AND EXISTS (
                      SELECT 1
                      FROM order_batches ob
                      JOIN orders o ON o.id = ob.order_id
                      WHERE ob.id = obi.order_batch_id
                        AND ob.order_id = ?
                        AND o.venue_id = ?
                        AND o.status = 'ACTIVE'
                  )
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, batchItemId)
                statement.setLong(2, orderId)
                statement.setLong(3, venueId)
                statement.executeQuery().use { rs -> rs.next() }
            }
        if (!locked) {
            return null
        }
        return connection.prepareStatement(
            """
            SELECT obi.id,
                   obi.order_batch_id,
                   COALESCE(mi.name, 'Позиция #' || obi.menu_item_id) AS item_name,
                   obi.is_excluded,
                   COALESCE(obi.item_status, 'ACTIVE') AS item_status,
                   ob.status AS batch_status,
                   COALESCE(
                       ob.author_user_id,
                       (
                           SELECT gbi.user_id
                           FROM guest_batch_idempotency gbi
                           WHERE gbi.batch_id = ob.id
                           ORDER BY gbi.id DESC
                           LIMIT 1
                       )
                   ) AS guest_user_id
            FROM order_batch_items obi
            JOIN order_batches ob ON ob.id = obi.order_batch_id
            JOIN orders o ON o.id = ob.order_id
            LEFT JOIN menu_items mi ON mi.id = obi.menu_item_id
            WHERE obi.id = ?
              AND ob.order_id = ?
              AND o.venue_id = ?
              AND o.status = 'ACTIVE'
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, batchItemId)
            statement.setLong(2, orderId)
            statement.setLong(3, venueId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    CancelableBatchItem(
                        batchId = rs.getLong("order_batch_id"),
                        itemName = rs.getString("item_name"),
                        isExcluded = rs.getBoolean("is_excluded"),
                        itemStatus = OrderBatchItemStatus.fromDb(rs.getString("item_status")),
                        batchStatus =
                            OrderBatchStatus.fromDb(rs.getString("batch_status"))
                                ?: OrderBatchStatus.REJECTED,
                        guestUserId = rs.getLong("guest_user_id").let { value -> if (rs.wasNull()) null else value },
                    )
                }
            }
        }
    }

    private fun loadBatchItems(
        connection: Connection,
        batchIds: List<Long>,
    ): Map<Long, List<OrderBatchItemDetail>> {
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
                   obi.is_excluded,
                   obi.excluded_reason_text,
                   obi.discount_percent,
                   COALESCE(promo.discount_minor, 0) AS promo_discount_minor,
                   obi.item_status,
                   obi.canceled_reason_code,
                   obi.canceled_reason_text,
                   obi.canceled_at,
                   obi.canceled_by_user_id,
                   mi.name,
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
            WHERE obi.order_batch_id IN ($placeholders)
            ORDER BY obi.order_batch_id, obi.id
            """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            batchIds.forEachIndexed { index, batchId ->
                statement.setLong(index + 1, batchId)
            }
            statement.executeQuery().use { rs ->
                val result = linkedMapOf<Long, MutableList<OrderBatchItemDetail>>()
                while (rs.next()) {
                    val batchId = rs.getLong("order_batch_id")
                    val items = result.getOrPut(batchId) { mutableListOf() }
                    items.add(
                        OrderBatchItemDetail(
                            batchItemId = rs.getLong("id"),
                            itemId = rs.getLong("menu_item_id"),
                            name =
                                rs.getString("name")
                                    ?.takeIf { it.isNotBlank() }
                                    ?: "Позиция #${rs.getLong("menu_item_id")}",
                            qty = rs.getInt("qty"),
                            selectedOption = rs.toSelectedOption(),
                            preferenceNote = rs.getString("preference_note"),
                            priceMinor = rs.getLong("price_minor").let { value -> if (rs.wasNull()) null else value },
                            currency = rs.getString("currency"),
                            isExcluded = rs.getBoolean("is_excluded"),
                            excludedReasonText = rs.getString("excluded_reason_text"),
                            discountPercent =
                                rs.getInt("discount_percent").let {
                                        value ->
                                    if (rs.wasNull()) null else value
                                },
                            promoDiscountMinor = rs.getLong("promo_discount_minor"),
                            itemStatus = OrderBatchItemStatus.fromDb(rs.getString("item_status")),
                            canceledReasonCode = rs.getString("canceled_reason_code"),
                            canceledReasonText = rs.getString("canceled_reason_text"),
                            canceledAt = rs.getTimestamp("canceled_at")?.toInstant(),
                            canceledByUserId =
                                rs.getLong("canceled_by_user_id").let {
                                        value ->
                                    if (rs.wasNull()) null else value
                                },
                        ),
                    )
                }
                result
            }
        }
    }

    private fun loadPromotionDiscountsByBatch(
        connection: Connection,
        batchIds: List<Long>,
    ): Map<Long, List<OrderPromotionDiscount>> {
        if (batchIds.isEmpty()) {
            return emptyMap()
        }
        val placeholders = batchIds.joinToString(",") { "?" }
        val sql =
            """
            WITH application_discounts AS (
                SELECT
                    opa.batch_id,
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
                WHERE opa.batch_id IN ($placeholders)
                  AND ob.status <> 'REJECTED'
                  AND ob.status <> 'CLOSED'
                  AND ob.rejected_reason_code IS NULL
                  AND ob.rejected_reason_text IS NULL
                  AND obi.is_excluded = FALSE
                  AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
                GROUP BY opa.id, opa.batch_id, opa.title_snapshot, opa.rule_type, opa.currency
            )
            SELECT batch_id,
                   promo_label,
                   rule_type,
                   currency,
                   COALESCE(SUM(discount_minor), 0) AS discount_minor,
                   MIN(first_application_id) AS first_application_id
            FROM application_discounts
            GROUP BY batch_id, promo_label, rule_type, currency
            ORDER BY batch_id, first_application_id
            """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            batchIds.forEachIndexed { index, batchId ->
                statement.setLong(index + 1, batchId)
            }
            statement.executeQuery().use { rs ->
                val result = linkedMapOf<Long, MutableList<OrderPromotionDiscount>>()
                while (rs.next()) {
                    val discountMinor = rs.getLong("discount_minor")
                    if (discountMinor > 0L) {
                        result.getOrPut(rs.getLong("batch_id")) { mutableListOf() } +=
                            OrderPromotionDiscount(
                                label = rs.getString("promo_label"),
                                discountMinor = discountMinor,
                                currency = rs.getString("currency"),
                                ruleType = rs.getString("rule_type"),
                            )
                    }
                }
                result
            }
        }
    }

    private fun ResultSet.toSelectedOption(): OrderBatchItemSelectedOption? {
        val optionId =
            getLong("menu_item_option_id").let { value ->
                if (wasNull()) null else value
            }
        val name = getString("option_name_snapshot")?.takeIf { it.isNotBlank() } ?: return null
        return OrderBatchItemSelectedOption(
            optionId = optionId,
            name = name,
            priceDeltaMinor = getLong("price_delta_minor_snapshot"),
        )
    }

    private fun loadPromotionDiscountsByOrder(
        connection: Connection,
        orderIds: List<Long>,
    ): Map<Long, List<OrderPromotionDiscount>> {
        if (orderIds.isEmpty()) {
            return emptyMap()
        }
        val distinctOrderIds = orderIds.distinct()
        val placeholders = distinctOrderIds.joinToString(",") { "?" }
        val sql =
            """
            WITH application_discounts AS (
                SELECT
                    opa.order_id,
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
                WHERE opa.order_id IN ($placeholders)
                  AND ob.status <> 'REJECTED'
                  AND ob.status <> 'CLOSED'
                  AND ob.rejected_reason_code IS NULL
                  AND ob.rejected_reason_text IS NULL
                  AND obi.is_excluded = FALSE
                  AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
                GROUP BY opa.id, opa.order_id, opa.title_snapshot, opa.rule_type, opa.currency
            )
            SELECT order_id,
                   promo_label,
                   rule_type,
                   currency,
                   COALESCE(SUM(discount_minor), 0) AS discount_minor,
                   MIN(first_application_id) AS first_application_id
            FROM application_discounts
            GROUP BY order_id, promo_label, rule_type, currency
            ORDER BY order_id, first_application_id
            """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            distinctOrderIds.forEachIndexed { index, orderId ->
                statement.setLong(index + 1, orderId)
            }
            statement.executeQuery().use { rs ->
                val result = linkedMapOf<Long, MutableList<OrderPromotionDiscount>>()
                while (rs.next()) {
                    val discountMinor = rs.getLong("discount_minor")
                    if (discountMinor > 0L) {
                        result.getOrPut(rs.getLong("order_id")) { mutableListOf() } +=
                            OrderPromotionDiscount(
                                label = rs.getString("promo_label"),
                                discountMinor = discountMinor,
                                currency = rs.getString("currency"),
                                ruleType = rs.getString("rule_type"),
                            )
                    }
                }
                result
            }
        }
    }

    private fun loadPendingShiftExtensionsByOrderIds(
        connection: Connection,
        venueId: Long,
        orderIds: List<Long>,
    ): Map<Long, OrderPendingShiftExtension> {
        if (orderIds.isEmpty()) {
            return emptyMap()
        }
        val distinctOrderIds = orderIds.distinct()
        val placeholders = distinctOrderIds.joinToString(",") { "?" }
        val sql =
            """
            SELECT ser.id,
                   ser.order_id,
                   ser.table_session_id,
                   ser.tab_id,
                   ser.table_id,
                   vt.table_number,
                   ser.duration_minutes,
                   ser.price_minor,
                   ser.currency,
                   ser.created_at,
                   ser.status
            FROM shift_extension_requests ser
            JOIN venue_tables vt ON vt.id = ser.table_id
            WHERE ser.venue_id = ?
              AND ser.order_id IN ($placeholders)
              AND ser.status = 'PENDING'
            ORDER BY ser.order_id, ser.created_at DESC, ser.id DESC
            """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, venueId)
            distinctOrderIds.forEachIndexed { index, orderId ->
                statement.setLong(index + 2, orderId)
            }
            statement.executeQuery().use { rs ->
                val result = linkedMapOf<Long, OrderPendingShiftExtension>()
                while (rs.next()) {
                    val orderId = rs.getLong("order_id")
                    if (!result.containsKey(orderId)) {
                        result[orderId] =
                            OrderPendingShiftExtension(
                                requestId = rs.getLong("id"),
                                orderId = orderId,
                                tableSessionId = rs.getLong("table_session_id"),
                                tabId = rs.getLong("tab_id"),
                                tableId = rs.getLong("table_id"),
                                tableNumber = rs.getInt("table_number"),
                                durationMinutes = rs.getInt("duration_minutes"),
                                priceMinor = rs.getLong("price_minor"),
                                currency = rs.getString("currency"),
                                requestedAt = rs.getTimestamp("created_at").toInstant(),
                                status = rs.getString("status").lowercase(Locale.ROOT),
                            )
                    }
                }
                result
            }
        }
    }

    private fun loadServiceCharges(
        connection: Connection,
        orderId: Long,
    ): List<OrderServiceChargeDetail> =
        connection.prepareStatement(
            """
            SELECT id,
                   source,
                   source_request_id,
                   label,
                   qty,
                   unit_price_minor,
                   total_minor,
                   currency,
                   created_at
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
                            OrderServiceChargeDetail(
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
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                            ),
                        )
                    }
                }
            }
        }

    private fun List<OrderPromotionDiscount>.mergePromotionDiscounts(): List<OrderPromotionDiscount> =
        groupBy { discount ->
            Triple(
                discount.label.takeIf { it.isNotBlank() } ?: "Акция",
                discount.ruleType,
                discount.currency,
            )
        }.map { (key, discounts) ->
            OrderPromotionDiscount(
                label = key.first,
                ruleType = key.second,
                currency = key.third,
                discountMinor = discounts.sumOf { it.discountMinor },
            )
        }.filter { it.discountMinor > 0L }

    private fun loadBatchesForWorkflow(
        connection: Connection,
        orderId: Long,
    ): List<OrderBatchDetail> =
        connection.prepareStatement(
            """
            SELECT id, status, source, guest_comment, created_at, updated_at,
                   rejected_reason_code, rejected_reason_text
            FROM order_batches
            WHERE order_id = ?
            ORDER BY created_at, id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.executeQuery().use { rs ->
                val result = mutableListOf<OrderBatchDetail>()
                while (rs.next()) {
                    result.add(
                        OrderBatchDetail(
                            batchId = rs.getLong("id"),
                            status =
                                OrderBatchStatus.fromDb(rs.getString("status"))?.toWorkflow()
                                    ?: OrderWorkflowStatus.NEW,
                            source = rs.getString("source"),
                            comment = rs.getString("guest_comment"),
                            createdAt = rs.getTimestamp("created_at").toInstant(),
                            updatedAt = rs.getTimestamp("updated_at").toInstant(),
                            rejectedReasonCode = rs.getString("rejected_reason_code"),
                            rejectedReasonText = rs.getString("rejected_reason_text"),
                            items = emptyList(),
                        ),
                    )
                }
                result
            }
        }

    private data class OrderRow(
        val status: String,
        val updatedAt: Instant,
        val batches: List<OrderBatchDetail>,
    )

    private data class CancelableBatchItem(
        val batchId: Long,
        val itemName: String,
        val isExcluded: Boolean,
        val itemStatus: OrderBatchItemStatus,
        val batchStatus: OrderBatchStatus,
        val guestUserId: Long?,
    )

    private companion object {
        val cancelableItemBatchStatuses =
            setOf(
                OrderBatchStatus.NEW,
                OrderBatchStatus.ACCEPTED,
                OrderBatchStatus.PREPARING,
            )
    }

    private data class OrderHeader(
        val status: String,
        val displayNumber: Int?,
        val displayDate: LocalDate?,
        val createdAt: Instant,
        val updatedAt: Instant,
        val tableId: Long,
        val tableNumber: Int,
    )

    private data class BatchRow(
        val orderId: Long,
        val orderStatus: String,
        val status: OrderBatchStatus,
        val updatedAt: Instant,
    )

    private fun selectBatchForUpdate(
        connection: Connection,
        venueId: Long,
        batchId: Long,
    ): BatchRow? =
        connection.prepareStatement(
            """
            SELECT ob.order_id,
                   ob.status AS batch_status,
                   ob.updated_at,
                   o.status AS order_status
            FROM order_batches ob
            JOIN orders o ON o.id = ob.order_id
            WHERE ob.id = ?
              AND o.venue_id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, batchId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    return@use null
                }
                val status = OrderBatchStatus.fromDb(rs.getString("batch_status")) ?: return@use null
                BatchRow(
                    orderId = rs.getLong("order_id"),
                    orderStatus = rs.getString("order_status"),
                    status = status,
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                )
            }
        }

    private fun selectOrderForUpdate(
        connection: Connection,
        orderId: Long,
        venueId: Long,
    ): OrderRow? {
        val order =
            connection.prepareStatement(
                """
                SELECT status, updated_at
                FROM orders
                WHERE id = ? AND venue_id = ?
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, orderId)
                statement.setLong(2, venueId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        rs.getString("status") to rs.getTimestamp("updated_at").toInstant()
                    } else {
                        null
                    }
                }
            } ?: return null

        val batches =
            connection.prepareStatement(
                """
                SELECT id, status, source, guest_comment, created_at, updated_at,
                       rejected_reason_code, rejected_reason_text
                FROM order_batches
                WHERE order_id = ?
                ORDER BY created_at DESC, id DESC
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, orderId)
                statement.executeQuery().use { rs ->
                    val result = mutableListOf<OrderBatchDetail>()
                    while (rs.next()) {
                        val status =
                            OrderBatchStatus.fromDb(rs.getString("status"))
                                ?.toWorkflow() ?: OrderWorkflowStatus.NEW
                        result.add(
                            OrderBatchDetail(
                                batchId = rs.getLong("id"),
                                status = status,
                                source = rs.getString("source"),
                                comment = rs.getString("guest_comment"),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                                rejectedReasonCode = rs.getString("rejected_reason_code"),
                                rejectedReasonText = rs.getString("rejected_reason_text"),
                                items = emptyList(),
                            ),
                        )
                    }
                    result
                }
            }

        return OrderRow(order.first, order.second, batches)
    }

    private fun updateLatestBatchStatus(
        connection: Connection,
        batchId: Long,
        status: String,
        now: OffsetDateTime,
    ): Int {
        return connection.prepareStatement(
            """
            UPDATE order_batches
            SET status = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, status)
            statement.setObject(2, now)
            statement.setLong(3, batchId)
            statement.executeUpdate()
        }
    }

    private fun updateNewBatchesStatus(
        connection: Connection,
        batchIds: List<Long>,
        status: String,
        now: OffsetDateTime,
    ): Int {
        val placeholders = batchIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            UPDATE order_batches
            SET status = ?, updated_at = ?
            WHERE id IN ($placeholders)
              AND status = 'NEW'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, status)
            statement.setObject(2, now)
            batchIds.forEachIndexed { index, batchId ->
                statement.setLong(index + 3, batchId)
            }
            statement.executeUpdate()
        }
    }

    private fun updateAcceptedBatchesStatus(
        connection: Connection,
        batchIds: List<Long>,
        status: String,
        now: OffsetDateTime,
    ): Int {
        val placeholders = batchIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            UPDATE order_batches
            SET status = ?, updated_at = ?
            WHERE id IN ($placeholders)
              AND status = 'ACCEPTED'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, status)
            statement.setObject(2, now)
            batchIds.forEachIndexed { index, batchId ->
                statement.setLong(index + 3, batchId)
            }
            statement.executeUpdate()
        }
    }

    private fun updateLatestBatchRejected(
        connection: Connection,
        batchId: Long,
        reasonCode: String,
        reasonText: String?,
        now: OffsetDateTime,
    ): Int {
        return connection.prepareStatement(
            """
            UPDATE order_batches
            SET status = 'REJECTED',
                updated_at = ?,
                rejected_reason_code = ?,
                rejected_reason_text = ?,
                rejected_at = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, now)
            statement.setString(2, reasonCode)
            if (reasonText != null) {
                statement.setString(3, reasonText)
            } else {
                statement.setNull(3, java.sql.Types.VARCHAR)
            }
            statement.setObject(4, now)
            statement.setLong(5, batchId)
            statement.executeUpdate()
        }
    }

    private fun updateOrderStatusOnly(
        connection: Connection,
        orderId: Long,
    ) {
        connection.prepareStatement(
            """
            UPDATE orders
            SET status = 'CLOSED',
                updated_at = now()
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.executeUpdate()
        }
    }

    private fun updateOrderTimestamp(
        connection: Connection,
        orderId: Long,
        now: OffsetDateTime,
    ) {
        connection.prepareStatement(
            """
            UPDATE orders
            SET updated_at = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, now)
            statement.setLong(2, orderId)
            statement.executeUpdate()
        }
    }

    private fun insertAudit(
        connection: Connection,
        orderId: Long,
        actor: OrderActionActor,
        action: String,
        fromStatus: OrderWorkflowStatus,
        toStatus: OrderWorkflowStatus,
        reasonCode: String?,
        reasonText: String?,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO order_audit_log (
                order_id,
                actor_user_id,
                actor_role,
                action,
                from_status,
                to_status,
                reason_code,
                reason_text
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.setLong(2, actor.userId)
            statement.setString(3, actor.role.name)
            statement.setString(4, action)
            statement.setString(5, fromStatus.toApi())
            statement.setString(6, toStatus.toApi())
            if (reasonCode != null) {
                statement.setString(7, reasonCode)
            } else {
                statement.setNull(7, java.sql.Types.VARCHAR)
            }
            if (reasonText != null) {
                statement.setString(8, reasonText)
            } else {
                statement.setNull(8, java.sql.Types.VARCHAR)
            }
            statement.executeUpdate()
        }
    }
}
