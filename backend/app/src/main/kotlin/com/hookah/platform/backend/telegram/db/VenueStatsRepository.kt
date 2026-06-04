package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

data class VenueStatsReport(
    val ordersCount: Long,
    val revenueMinor: Long,
    val averageCheckMinor: Long,
    val discountMinor: Long,
    val cancelledItemsCount: Long,
    val currency: String,
    val topItems: List<VenueStatsTopItem>,
)

data class VenueStatsTopItem(
    val itemName: String,
    val qty: Long,
)

class VenueStatsRepository(
    private val dataSource: DataSource?,
) {
    suspend fun loadVenueStats(
        venueId: Long,
        periodStart: Instant,
    ): VenueStatsReport {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val periodStartUtc = OffsetDateTime.ofInstant(periodStart, ZoneOffset.UTC)
                    val summary = loadSummary(connection, venueId, periodStartUtc)
                    val cancelledItemsCount = loadCancelledItemsCount(connection, venueId, periodStartUtc)
                    val topItems = loadTopItems(connection, venueId, periodStartUtc)
                    val averageCheckMinor =
                        if (summary.ordersCount > 0) {
                            summary.revenueMinor / summary.ordersCount
                        } else {
                            0L
                        }
                    VenueStatsReport(
                        ordersCount = summary.ordersCount,
                        revenueMinor = summary.revenueMinor,
                        averageCheckMinor = averageCheckMinor,
                        discountMinor = summary.discountMinor,
                        cancelledItemsCount = cancelledItemsCount,
                        currency = summary.currency,
                        topItems = topItems,
                    )
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun loadSummary(
        connection: Connection,
        venueId: Long,
        periodStart: OffsetDateTime,
    ): VenueStatsSummary {
        val sql =
            """
            WITH active_items AS (
                SELECT
                    o.id AS order_id,
                    obi.qty AS qty,
                    COALESCE(mi.price_minor, 0) AS price_minor,
                    COALESCE(mi.currency, 'RUB') AS currency,
                    CASE
                        WHEN obi.discount_percent BETWEEN 1 AND 100 THEN obi.discount_percent
                        ELSE 0
                    END AS discount_percent,
                    COALESCE(promo.discount_minor, 0) AS promo_discount_minor
                FROM orders o
                JOIN order_batches ob ON ob.order_id = o.id
                JOIN order_batch_items obi ON obi.order_batch_id = ob.id
                LEFT JOIN menu_items mi ON mi.id = obi.menu_item_id
                LEFT JOIN (
                    SELECT order_batch_item_id, SUM(discount_minor) AS discount_minor
                    FROM order_batch_item_promotion_adjustments
                    GROUP BY order_batch_item_id
                ) promo ON promo.order_batch_item_id = obi.id
                WHERE o.venue_id = ?
                  AND o.status IN ('ACTIVE', 'CLOSED')
                  AND ob.created_at >= ?
                  AND ob.status IN ('DELIVERED', 'CLOSED')
                  AND ob.rejected_reason_code IS NULL
                  AND ob.rejected_reason_text IS NULL
                  AND obi.is_excluded = FALSE
                  AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
            )
            SELECT
                COUNT(DISTINCT order_id) AS orders_count,
                COALESCE(SUM(GREATEST(price_minor * qty - price_minor * qty * discount_percent / 100 - promo_discount_minor, 0)), 0) AS revenue_minor,
                COALESCE(SUM(price_minor * qty * discount_percent / 100 + promo_discount_minor), 0) AS discount_minor,
                COALESCE(MAX(currency), 'RUB') AS currency
            FROM active_items
            """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, venueId)
            statement.setObject(2, periodStart)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    VenueStatsSummary(
                        ordersCount = rs.getLong("orders_count"),
                        revenueMinor = rs.getLong("revenue_minor"),
                        discountMinor = rs.getLong("discount_minor"),
                        currency = rs.getString("currency")?.takeIf { it.isNotBlank() } ?: "RUB",
                    )
                } else {
                    VenueStatsSummary()
                }
            }
        }
    }

    private fun loadCancelledItemsCount(
        connection: Connection,
        venueId: Long,
        periodStart: OffsetDateTime,
    ): Long {
        // TODO: Count cancellations by excluded_at/rejected_at once cancellation-time reporting is required.
        val sql =
            """
            SELECT COALESCE(SUM(obi.qty), 0) AS cancelled_items_count
            FROM orders o
            JOIN order_batches ob ON ob.order_id = o.id
            JOIN order_batch_items obi ON obi.order_batch_id = ob.id
            WHERE o.venue_id = ?
              AND ob.created_at >= ?
              AND (
                  obi.is_excluded = TRUE
                  OR COALESCE(obi.item_status, 'ACTIVE') = 'CANCELED'
                  OR ob.status = 'REJECTED'
                  OR ob.rejected_reason_code IS NOT NULL
                  OR ob.rejected_reason_text IS NOT NULL
              )
            """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, venueId)
            statement.setObject(2, periodStart)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getLong("cancelled_items_count") else 0L
            }
        }
    }

    private fun loadTopItems(
        connection: Connection,
        venueId: Long,
        periodStart: OffsetDateTime,
    ): List<VenueStatsTopItem> {
        val sql =
            """
            SELECT
                obi.menu_item_id,
                mi.name AS item_name,
                SUM(obi.qty) AS qty
            FROM orders o
            JOIN order_batches ob ON ob.order_id = o.id
            JOIN order_batch_items obi ON obi.order_batch_id = ob.id
            LEFT JOIN menu_items mi ON mi.id = obi.menu_item_id
            WHERE o.venue_id = ?
              AND o.status IN ('ACTIVE', 'CLOSED')
              AND ob.created_at >= ?
              AND ob.status IN ('DELIVERED', 'CLOSED')
              AND ob.rejected_reason_code IS NULL
              AND ob.rejected_reason_text IS NULL
              AND obi.is_excluded = FALSE
              AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
            GROUP BY obi.menu_item_id, mi.name
            ORDER BY SUM(obi.qty) DESC, MIN(obi.id) ASC
            LIMIT 3
            """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, venueId)
            statement.setObject(2, periodStart)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val itemId = rs.getLong("menu_item_id")
                        add(
                            VenueStatsTopItem(
                                itemName = rs.getString("item_name")?.takeIf { it.isNotBlank() } ?: "Позиция #$itemId",
                                qty = rs.getLong("qty"),
                            ),
                        )
                    }
                }
            }
        }
    }

    private data class VenueStatsSummary(
        val ordersCount: Long = 0L,
        val revenueMinor: Long = 0L,
        val discountMinor: Long = 0L,
        val currency: String = "RUB",
    )
}
