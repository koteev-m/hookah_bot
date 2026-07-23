package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Date
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import javax.sql.DataSource

enum class VisitSource {
    BOOKING_SEATED,
    ORDER_CLOSED,
    MERGED,
}

data class VisitRecord(
    val id: Long,
    val venueId: Long,
    val userId: Long,
    val bookingId: Long?,
    val tableSessionId: Long?,
    val orderId: Long?,
    val source: VisitSource,
    val occurredAt: Instant,
    val serviceDate: LocalDate?,
)

data class GuestVisitHistoryItem(
    val visitId: Long,
    val venueId: Long,
    val venueName: String,
    val venueCity: String?,
    val occurredAt: Instant,
    val serviceDate: LocalDate?,
    val source: VisitSource,
    val totalMinor: Long?,
    val currency: String?,
    val hasBooking: Boolean,
    val orderLabels: List<String>,
)

data class GuestVisitDetail(
    val visitId: Long,
    val venueId: Long,
    val venueName: String,
    val venueCity: String?,
    val occurredAt: Instant,
    val serviceDate: LocalDate?,
    val source: VisitSource,
    val booking: GuestVisitBooking?,
    val orders: List<GuestVisitOrder>,
    val totalMinor: Long?,
    val currency: String?,
)

data class GuestVisitBooking(
    val bookingId: Long,
    val displayNumber: Int?,
    val partySize: Int?,
    val status: String,
)

data class GuestVisitOrder(
    val orderId: Long,
    val displayNumber: Int?,
    val displayDate: LocalDate?,
    val items: List<GuestVisitOrderItem>,
    val totalMinor: Long?,
    val currency: String?,
    val promotionDiscounts: List<GuestVisitPromotionDiscount> = emptyList(),
)

data class GuestVisitPromotionDiscount(
    val label: String,
    val discountMinor: Long,
    val currency: String,
    val ruleType: String? = null,
)

data class GuestVisitOrderItemOption(
    val optionId: Long? = null,
    val name: String,
    val priceDeltaMinor: Long,
)

data class GuestVisitOrderItem(
    val itemId: Long,
    val itemName: String,
    val qty: Int,
    val selectedOption: GuestVisitOrderItemOption? = null,
    val preferenceNote: String? = null,
    val priceMinor: Long?,
    val currency: String?,
    val discountPercent: Int?,
    val promoDiscountMinor: Long = 0L,
    val isPromotionReward: Boolean = false,
    val totalMinor: Long?,
)

data class OrderClosedVisitsResult(
    val orderId: Long,
    val participantsCount: Int,
    val recordedCount: Int,
    val visitIds: List<Long> = emptyList(),
)

class VisitRepository(private val dataSource: DataSource?) {
    suspend fun recordBookingSeatedVisit(
        bookingId: Long,
        now: Instant = Instant.now(),
    ): VisitRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val visit = recordBookingSeatedVisit(connection, bookingId, now)
                        connection.commit()
                        visit
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

    fun recordBookingSeatedVisit(
        connection: Connection,
        bookingId: Long,
        now: Instant = Instant.now(),
    ): VisitRecord? {
        val booking = loadSeatedBooking(connection, bookingId) ?: return null
        findVisitByBookingId(connection, bookingId)?.let { return it }

        val mergeVisitId =
            findSameServiceDateVisit(
                connection = connection,
                venueId = booking.venueId,
                userId = booking.userId,
                serviceDate = booking.serviceDate,
                requireBooking = false,
            )
        if (mergeVisitId != null) {
            return updateVisitForBookingMerge(connection, mergeVisitId, booking, now)
        }

        return insertVisit(
            connection = connection,
            venueId = booking.venueId,
            userId = booking.userId,
            bookingId = booking.id,
            tableSessionId = null,
            orderId = null,
            source = VisitSource.BOOKING_SEATED,
            occurredAt = booking.seatedAt ?: now,
            serviceDate = booking.serviceDate,
        )
    }

    suspend fun recordOrderClosedVisits(
        orderId: Long,
        now: Instant = Instant.now(),
    ): OrderClosedVisitsResult {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val result = recordOrderClosedVisits(connection, orderId, now)
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

    fun recordOrderClosedVisits(
        connection: Connection,
        orderId: Long,
        now: Instant = Instant.now(),
    ): OrderClosedVisitsResult {
        val order =
            loadClosedOrder(connection, orderId)
                ?: return OrderClosedVisitsResult(orderId = orderId, participantsCount = 0, recordedCount = 0)
        val participants = loadRealGuestParticipants(connection, order)
        var recorded = 0
        val visitIds = mutableListOf<Long>()
        participants.forEach { userId ->
            val existingForSession = findVisitByTableSessionAndUser(connection, order.tableSessionId, userId)
            if (existingForSession != null) {
                val visit =
                    updateVisitForOrderMerge(
                        connection,
                        existingForSession.id,
                        order,
                        hasBooking = existingForSession.bookingId != null,
                    )
                visitIds.add(visit.id)
                recorded++
                return@forEach
            }
            val bookingVisitId =
                findSameServiceDateVisit(
                    connection = connection,
                    venueId = order.venueId,
                    userId = userId,
                    serviceDate = order.serviceDate,
                    requireBooking = true,
                )
            if (bookingVisitId != null) {
                val visit = updateVisitForOrderMerge(connection, bookingVisitId, order, hasBooking = true)
                visitIds.add(visit.id)
            } else {
                val visit =
                    insertVisit(
                        connection = connection,
                        venueId = order.venueId,
                        userId = userId,
                        bookingId = null,
                        tableSessionId = order.tableSessionId,
                        orderId = order.id,
                        source = VisitSource.ORDER_CLOSED,
                        occurredAt = now,
                        serviceDate = order.serviceDate,
                    )
                visitIds.add(visit.id)
            }
            recorded++
        }
        return OrderClosedVisitsResult(
            orderId = orderId,
            participantsCount = participants.size,
            recordedCount = recorded,
            visitIds = visitIds.distinct(),
        )
    }

    suspend fun getVisitCount(
        userId: Long,
        venueId: Long? = null,
    ): Long {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        if (venueId == null) {
                            "SELECT COUNT(*) AS visit_count FROM visits WHERE user_id = ?"
                        } else {
                            "SELECT COUNT(*) AS visit_count FROM visits WHERE user_id = ? AND venue_id = ?"
                        }
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, userId)
                        if (venueId != null) statement.setLong(2, venueId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) rs.getLong("visit_count") else 0L
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findRecentVisits(
        userId: Long,
        limit: Int = 20,
    ): List<VisitRecord> {
        require(limit > 0) { "limit must be > 0" }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT id, venue_id, user_id, booking_id, table_session_id, order_id, source, occurred_at, service_date
                        FROM visits
                        WHERE user_id = ?
                        ORDER BY occurred_at DESC, id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.setInt(2, limit)
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) add(rs.toVisitRecord())
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listGuestVisitHistory(
        userId: Long,
        limit: Int = 20,
        cursor: Long? = null,
    ): List<GuestVisitHistoryItem> {
        val normalizedLimit = limit.coerceIn(1, 50)
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val visits = loadGuestVisitHeaders(connection, userId, normalizedLimit, cursor)
                    visits.map { visit ->
                        val orders = loadGuestVisitOrders(connection, visit, userId)
                        val total = combineOrderTotals(orders)
                        GuestVisitHistoryItem(
                            visitId = visit.id,
                            venueId = visit.venueId,
                            venueName = visit.venueName,
                            venueCity = visit.venueCity,
                            occurredAt = visit.occurredAt,
                            serviceDate = visit.serviceDate,
                            source = visit.source,
                            totalMinor = total.first,
                            currency = total.second,
                            hasBooking = visit.bookingId != null,
                            orderLabels = orders.map { formatOrderLabel(it) },
                        )
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun getGuestVisitDetail(
        userId: Long,
        visitId: Long,
    ): GuestVisitDetail? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val visit = loadGuestVisitHeader(connection, userId, visitId) ?: return@use null
                    val orders = loadGuestVisitOrders(connection, visit, userId)
                    val total = combineOrderTotals(orders)
                    GuestVisitDetail(
                        visitId = visit.id,
                        venueId = visit.venueId,
                        venueName = visit.venueName,
                        venueCity = visit.venueCity,
                        occurredAt = visit.occurredAt,
                        serviceDate = visit.serviceDate,
                        source = visit.source,
                        booking = visit.bookingId?.let { loadGuestVisitBooking(connection, it) },
                        orders = orders,
                        totalMinor = total.first,
                        currency = total.second,
                    )
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun loadGuestVisitHeaders(
        connection: Connection,
        userId: Long,
        limit: Int,
        cursor: Long?,
    ): List<GuestVisitHeader> {
        val cursorCondition = if (cursor == null) "" else "AND v.id < ?"
        return connection.prepareStatement(
            """
            SELECT v.id,
                   v.venue_id,
                   venue.name AS venue_name,
                   venue.city AS venue_city,
                   v.booking_id,
                   v.table_session_id,
                   v.order_id,
                   v.source,
                   v.occurred_at,
                   v.service_date
            FROM visits v
            JOIN venues venue ON venue.id = v.venue_id
            LEFT JOIN bookings b ON b.id = v.booking_id
            WHERE v.user_id = ?
              AND (v.booking_id IS NULL OR b.status = 'SEATED')
              $cursorCondition
            ORDER BY v.occurred_at DESC, v.id DESC
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            var index = 1
            statement.setLong(index++, userId)
            if (cursor != null) statement.setLong(index++, cursor)
            statement.setInt(index, limit)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toGuestVisitHeader())
                }
            }
        }
    }

    private fun loadGuestVisitHeader(
        connection: Connection,
        userId: Long,
        visitId: Long,
    ): GuestVisitHeader? =
        connection.prepareStatement(
            """
            SELECT v.id,
                   v.venue_id,
                   venue.name AS venue_name,
                   venue.city AS venue_city,
                   v.booking_id,
                   v.table_session_id,
                   v.order_id,
                   v.source,
                   v.occurred_at,
                   v.service_date
            FROM visits v
            JOIN venues venue ON venue.id = v.venue_id
            LEFT JOIN bookings b ON b.id = v.booking_id
            WHERE v.user_id = ?
              AND v.id = ?
              AND (v.booking_id IS NULL OR b.status = 'SEATED')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, visitId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toGuestVisitHeader() else null }
        }

    private fun loadGuestVisitBooking(
        connection: Connection,
        bookingId: Long,
    ): GuestVisitBooking? =
        connection.prepareStatement(
            """
            SELECT id, display_number, party_size, status
            FROM bookings
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, bookingId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    GuestVisitBooking(
                        bookingId = rs.getLong("id"),
                        displayNumber = rs.getInt("display_number").let { if (rs.wasNull()) null else it },
                        partySize = rs.getInt("party_size").let { if (rs.wasNull()) null else it },
                        status = rs.getString("status"),
                    )
                } else {
                    null
                }
            }
        }

    private fun loadGuestVisitOrders(
        connection: Connection,
        visit: GuestVisitHeader,
        userId: Long,
    ): List<GuestVisitOrder> {
        if (visit.tableSessionId == null && visit.orderId == null) return emptyList()
        val scopeCondition =
            if (visit.tableSessionId != null) {
                "o.table_session_id = ?"
            } else {
                "o.id = ?"
            }
        val scopeValue = visit.tableSessionId ?: visit.orderId ?: return emptyList()
        return connection.prepareStatement(
            """
            SELECT o.id, o.display_number, o.display_date
            FROM orders o
            WHERE o.status = 'CLOSED'
              AND $scopeCondition
              AND EXISTS (
                  SELECT 1
                  FROM order_batches ob
                  WHERE ob.order_id = o.id
                    AND ${validGuestBatchPredicate("ob")}
              )
            ORDER BY o.created_at ASC, o.id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, scopeValue)
            bindGuestBatchPredicate(statement, startIndex = 2, userId = userId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val orderId = rs.getLong("id")
                        val items = loadGuestVisitOrderItems(connection, orderId, userId)
                        val total = combineItemTotals(items)
                        val promotionDiscounts = loadGuestVisitPromotionDiscounts(connection, orderId, userId)
                        add(
                            GuestVisitOrder(
                                orderId = orderId,
                                displayNumber = rs.getInt("display_number").let { if (rs.wasNull()) null else it },
                                displayDate = rs.getDate("display_date")?.toLocalDate(),
                                items = items,
                                totalMinor = total.first,
                                currency = total.second,
                                promotionDiscounts = promotionDiscounts,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun loadGuestVisitOrderItems(
        connection: Connection,
        orderId: Long,
        userId: Long,
    ): List<GuestVisitOrderItem> =
        connection.prepareStatement(
            """
            SELECT obi.menu_item_id,
                   COALESCE(mi.name, 'Позиция #' || obi.menu_item_id) AS item_name,
                   SUM(obi.qty) AS qty,
                   obiop.menu_item_option_id,
                   obiop.option_name_snapshot,
                   obiop.price_delta_minor_snapshot,
                   obi.preference_note,
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
            WHERE ob.order_id = ?
              AND obi.is_excluded = FALSE
              AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
              AND ${validGuestBatchPredicate("ob")}
            GROUP BY obi.menu_item_id,
                     mi.name,
                     mi.price_minor,
                     mi.currency,
                     obiop.menu_item_option_id,
                     obiop.option_name_snapshot,
                     obiop.price_delta_minor_snapshot,
                     obi.preference_note,
                     obi.discount_percent,
                     opri.reward_order_batch_item_id
            ORDER BY MIN(obi.id) ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, orderId)
            bindGuestBatchPredicate(statement, startIndex = 2, userId = userId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val qty = rs.getInt("qty")
                        val priceMinor = rs.getLong("price_minor").let { if (rs.wasNull()) null else it }
                        val discountPercent = rs.getInt("discount_percent").let { if (rs.wasNull()) null else it }
                        val promoDiscountMinor = rs.getLong("promo_discount_minor")
                        add(
                            GuestVisitOrderItem(
                                itemId = rs.getLong("menu_item_id"),
                                itemName = rs.getString("item_name"),
                                qty = qty,
                                selectedOption = rs.toGuestVisitOrderItemOption(),
                                preferenceNote = rs.getString("preference_note")?.takeIf { it.isNotBlank() },
                                priceMinor = priceMinor,
                                currency = rs.getString("currency"),
                                discountPercent = discountPercent,
                                promoDiscountMinor = promoDiscountMinor,
                                isPromotionReward = rs.getBoolean("is_promotion_reward"),
                                totalMinor = calculateLineTotal(priceMinor, qty, discountPercent, promoDiscountMinor),
                            ),
                        )
                    }
                }
            }
        }

    private fun java.sql.ResultSet.toGuestVisitOrderItemOption(): GuestVisitOrderItemOption? {
        val name = getString("option_name_snapshot")?.takeIf { it.isNotBlank() } ?: return null
        return GuestVisitOrderItemOption(
            optionId = getLong("menu_item_option_id").let { if (wasNull()) null else it },
            name = name,
            priceDeltaMinor = getLong("price_delta_minor_snapshot"),
        )
    }

    private fun loadGuestVisitPromotionDiscounts(
        connection: Connection,
        orderId: Long,
        userId: Long,
    ): List<GuestVisitPromotionDiscount> =
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
                  AND obi.is_excluded = FALSE
                  AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
                  AND ${validGuestBatchPredicate("ob")}
                GROUP BY opa.id, opa.title_snapshot, opa.rule_type, opa.currency
            )
            SELECT promo_label,
                   rule_type,
                   currency,
                   COALESCE(SUM(discount_minor), 0) AS discount_minor,
                   MIN(first_application_id) AS first_application_id
            FROM application_discounts
            GROUP BY promo_label, rule_type, currency
            ORDER BY first_application_id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, orderId)
            bindGuestBatchPredicate(statement, startIndex = 2, userId = userId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val discountMinor = rs.getLong("discount_minor")
                        if (discountMinor > 0L) {
                            add(
                                GuestVisitPromotionDiscount(
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

    private fun validGuestBatchPredicate(batchAlias: String): String =
        """
        $batchAlias.status NOT IN ('REJECTED', 'CANCELED', 'CANCELLED')
        AND EXISTS (
            SELECT 1
            FROM order_batch_items obi_valid
            WHERE obi_valid.order_batch_id = $batchAlias.id
              AND obi_valid.is_excluded = FALSE
              AND COALESCE(obi_valid.item_status, 'ACTIVE') = 'ACTIVE'
        )
        AND (
            EXISTS (
                SELECT 1
                FROM guest_batch_idempotency gbi_valid
                WHERE gbi_valid.batch_id = $batchAlias.id
                  AND gbi_valid.user_id = ?
            )
            OR $batchAlias.author_user_id = ?
            OR EXISTS (
                SELECT 1
                FROM tab t_valid
                WHERE t_valid.id = $batchAlias.tab_id
                  AND t_valid.type = 'PERSONAL'
                  AND t_valid.owner_user_id = ?
            )
        )
        """.trimIndent()

    private fun bindGuestBatchPredicate(
        statement: java.sql.PreparedStatement,
        startIndex: Int,
        userId: Long,
    ) {
        statement.setLong(startIndex, userId)
        statement.setLong(startIndex + 1, userId)
        statement.setLong(startIndex + 2, userId)
    }

    private fun combineOrderTotals(orders: List<GuestVisitOrder>): Pair<Long?, String?> =
        combineTotals(orders.mapNotNull { order -> order.totalMinor?.let { it to order.currency } })

    private fun combineItemTotals(items: List<GuestVisitOrderItem>): Pair<Long?, String?> =
        combineTotals(items.mapNotNull { item -> item.totalMinor?.let { it to item.currency } })

    private fun combineTotals(values: List<Pair<Long, String?>>): Pair<Long?, String?> {
        var currency: String? = null
        var total = 0L
        var hasTotal = false
        values.forEach { (minor, itemCurrency) ->
            val normalizedCurrency = itemCurrency?.takeIf { it.isNotBlank() } ?: return@forEach
            if (currency == null) {
                currency = normalizedCurrency
            }
            if (currency == normalizedCurrency) {
                total += minor
                hasTotal = true
            }
        }
        return if (hasTotal) total to currency else null to null
    }

    private fun calculateLineTotal(
        priceMinor: Long?,
        qty: Int,
        discountPercent: Int?,
        promoDiscountMinor: Long,
    ): Long? {
        val price = priceMinor ?: return null
        val base = price * qty.toLong()
        val manualDiscount = discountPercent?.takeIf { it in 1..100 }?.let { base * it / 100 } ?: 0L
        return (base - manualDiscount - promoDiscountMinor.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    private fun formatOrderLabel(order: GuestVisitOrder): String =
        order.displayNumber?.let { "№$it" } ?: "#${order.orderId}"

    private fun loadSeatedBooking(
        connection: Connection,
        bookingId: Long,
    ): SeatedBooking? =
        connection.prepareStatement(
            """
            SELECT id, venue_id, user_id, display_date, seated_at
            FROM bookings
            WHERE id = ?
              AND status = 'SEATED'
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, bookingId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    SeatedBooking(
                        id = rs.getLong("id"),
                        venueId = rs.getLong("venue_id"),
                        userId = rs.getLong("user_id"),
                        serviceDate = rs.getDate("display_date")?.toLocalDate(),
                        seatedAt = rs.getTimestamp("seated_at")?.toInstant(),
                    )
                } else {
                    null
                }
            }
        }

    private fun loadClosedOrder(
        connection: Connection,
        orderId: Long,
    ): ClosedOrder? =
        connection.prepareStatement(
            """
            SELECT id, venue_id, table_session_id, display_date
            FROM orders
            WHERE id = ?
              AND status = 'CLOSED'
              AND table_session_id IS NOT NULL
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    ClosedOrder(
                        id = rs.getLong("id"),
                        venueId = rs.getLong("venue_id"),
                        tableSessionId = rs.getLong("table_session_id"),
                        serviceDate = rs.getDate("display_date")?.toLocalDate(),
                    )
                } else {
                    null
                }
            }
        }

    private fun loadRealGuestParticipants(
        connection: Connection,
        order: ClosedOrder,
    ): List<Long> =
        connection.prepareStatement(
            """
            SELECT DISTINCT participant.user_id
            FROM (
                SELECT gbi.user_id
                FROM guest_batch_idempotency gbi
                JOIN order_batches ob ON ob.id = gbi.batch_id
                    AND ob.order_id = gbi.order_id
                WHERE gbi.order_id = ?
                  AND gbi.table_session_id = ?
                  AND ob.status NOT IN ('REJECTED', 'CANCELED', 'CANCELLED')
                  AND EXISTS (
                      SELECT 1
                      FROM order_batch_items obi
                      WHERE obi.order_batch_id = ob.id
                        AND obi.is_excluded = FALSE
                        AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
                  )

                UNION

                SELECT ob.author_user_id AS user_id
                FROM order_batches ob
                WHERE ob.order_id = ?
                  AND ob.author_user_id IS NOT NULL
                  AND ob.status NOT IN ('REJECTED', 'CANCELED', 'CANCELLED')
                  AND EXISTS (
                      SELECT 1
                      FROM order_batch_items obi
                      WHERE obi.order_batch_id = ob.id
                        AND obi.is_excluded = FALSE
                        AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
                  )

                UNION

                SELECT t.owner_user_id AS user_id
                FROM order_batches ob
                JOIN tab t ON t.id = ob.tab_id
                WHERE ob.order_id = ?
                  AND t.type = 'PERSONAL'
                  AND t.owner_user_id IS NOT NULL
                  AND ob.status NOT IN ('REJECTED', 'CANCELED', 'CANCELLED')
                  AND ob.rejected_reason_code IS NULL
                  AND ob.rejected_reason_text IS NULL
                  AND EXISTS (
                      SELECT 1
                      FROM order_batch_items obi
                      WHERE obi.order_batch_id = ob.id
                        AND obi.is_excluded = FALSE
                        AND COALESCE(obi.item_status, 'ACTIVE') = 'ACTIVE'
                  )
            ) participant
            WHERE participant.user_id IS NOT NULL
              AND EXISTS (
                  SELECT 1
                  FROM users u
                  WHERE u.telegram_user_id = participant.user_id
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM venue_members vm
                  WHERE vm.venue_id = ?
                    AND vm.user_id = participant.user_id
              )
            ORDER BY participant.user_id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, order.id)
            statement.setLong(2, order.tableSessionId)
            statement.setLong(3, order.id)
            statement.setLong(4, order.id)
            statement.setLong(5, order.venueId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.getLong("user_id"))
                }
            }
        }

    private fun findVisitByBookingId(
        connection: Connection,
        bookingId: Long,
    ): VisitRecord? =
        connection.prepareStatement(
            """
            SELECT id, venue_id, user_id, booking_id, table_session_id, order_id, source, occurred_at, service_date
            FROM visits
            WHERE booking_id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, bookingId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toVisitRecord() else null }
        }

    private fun findVisitByTableSessionAndUser(
        connection: Connection,
        tableSessionId: Long,
        userId: Long,
    ): VisitRecord? =
        connection.prepareStatement(
            """
            SELECT id, venue_id, user_id, booking_id, table_session_id, order_id, source, occurred_at, service_date
            FROM visits
            WHERE table_session_id = ?
              AND user_id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, tableSessionId)
            statement.setLong(2, userId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toVisitRecord() else null }
        }

    private fun findSameServiceDateVisit(
        connection: Connection,
        venueId: Long,
        userId: Long,
        serviceDate: LocalDate?,
        requireBooking: Boolean,
    ): Long? {
        val serviceDateCondition = if (serviceDate == null) "service_date IS NULL" else "service_date = ?"
        val bookingCondition = if (requireBooking) "AND booking_id IS NOT NULL" else ""
        return connection.prepareStatement(
            """
            SELECT id
            FROM visits
            WHERE venue_id = ?
              AND user_id = ?
              AND $serviceDateCondition
              $bookingCondition
            ORDER BY id DESC
            LIMIT 1
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, userId)
            if (serviceDate != null) statement.setDate(3, Date.valueOf(serviceDate))
            statement.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else null }
        }
    }

    private fun updateVisitForBookingMerge(
        connection: Connection,
        visitId: Long,
        booking: SeatedBooking,
        now: Instant,
    ): VisitRecord {
        connection.prepareStatement(
            """
            UPDATE visits
            SET booking_id = COALESCE(booking_id, ?),
                source = CASE
                    WHEN table_session_id IS NOT NULL OR order_id IS NOT NULL THEN 'MERGED'
                    ELSE 'BOOKING_SEATED'
                END,
                occurred_at = COALESCE(occurred_at, ?),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, booking.id)
            statement.setTimestamp(2, Timestamp.from(booking.seatedAt ?: now))
            statement.setLong(3, visitId)
            statement.executeUpdate()
        }
        return loadVisitById(connection, visitId) ?: throw SQLException("Visit $visitId not found after booking merge")
    }

    private fun updateVisitForOrderMerge(
        connection: Connection,
        visitId: Long,
        order: ClosedOrder,
        hasBooking: Boolean,
    ): VisitRecord {
        connection.prepareStatement(
            """
            UPDATE visits
            SET table_session_id = COALESCE(table_session_id, ?),
                order_id = COALESCE(order_id, ?),
                source = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, order.tableSessionId)
            statement.setLong(2, order.id)
            statement.setString(3, if (hasBooking) VisitSource.MERGED.name else VisitSource.ORDER_CLOSED.name)
            statement.setLong(4, visitId)
            statement.executeUpdate()
        }
        return loadVisitById(connection, visitId) ?: throw SQLException("Visit $visitId not found after order merge")
    }

    private fun insertVisit(
        connection: Connection,
        venueId: Long,
        userId: Long,
        bookingId: Long?,
        tableSessionId: Long?,
        orderId: Long?,
        source: VisitSource,
        occurredAt: Instant,
        serviceDate: LocalDate?,
    ): VisitRecord =
        connection.prepareStatement(
            """
            INSERT INTO visits (
                venue_id,
                user_id,
                booking_id,
                table_session_id,
                order_id,
                source,
                occurred_at,
                service_date
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, userId)
            setNullableLong(statement, 3, bookingId)
            setNullableLong(statement, 4, tableSessionId)
            setNullableLong(statement, 5, orderId)
            statement.setString(6, source.name)
            statement.setTimestamp(7, Timestamp.from(occurredAt))
            if (serviceDate == null) {
                statement.setNull(8, java.sql.Types.DATE)
            } else {
                statement.setDate(8, Date.valueOf(serviceDate))
            }
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (!keys.next()) throw SQLException("Failed to create visit")
                loadVisitById(connection, keys.getLong(1)) ?: throw SQLException("Visit not found after insert")
            }
        }

    private fun loadVisitById(
        connection: Connection,
        visitId: Long,
    ): VisitRecord? =
        connection.prepareStatement(
            """
            SELECT id, venue_id, user_id, booking_id, table_session_id, order_id, source, occurred_at, service_date
            FROM visits
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, visitId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toVisitRecord() else null }
        }

    private fun java.sql.ResultSet.toVisitRecord(): VisitRecord =
        VisitRecord(
            id = getLong("id"),
            venueId = getLong("venue_id"),
            userId = getLong("user_id"),
            bookingId = getLong("booking_id").let { if (wasNull()) null else it },
            tableSessionId = getLong("table_session_id").let { if (wasNull()) null else it },
            orderId = getLong("order_id").let { if (wasNull()) null else it },
            source = VisitSource.valueOf(getString("source")),
            occurredAt = getTimestamp("occurred_at").toInstant(),
            serviceDate = getDate("service_date")?.toLocalDate(),
        )

    private fun java.sql.ResultSet.toGuestVisitHeader(): GuestVisitHeader =
        GuestVisitHeader(
            id = getLong("id"),
            venueId = getLong("venue_id"),
            venueName = getString("venue_name"),
            venueCity = getString("venue_city"),
            bookingId = getLong("booking_id").let { if (wasNull()) null else it },
            tableSessionId = getLong("table_session_id").let { if (wasNull()) null else it },
            orderId = getLong("order_id").let { if (wasNull()) null else it },
            source = VisitSource.valueOf(getString("source")),
            occurredAt = getTimestamp("occurred_at").toInstant(),
            serviceDate = getDate("service_date")?.toLocalDate(),
        )

    private fun setNullableLong(
        statement: java.sql.PreparedStatement,
        index: Int,
        value: Long?,
    ) {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT)
        } else {
            statement.setLong(index, value)
        }
    }

    private data class SeatedBooking(
        val id: Long,
        val venueId: Long,
        val userId: Long,
        val serviceDate: LocalDate?,
        val seatedAt: Instant?,
    )

    private data class ClosedOrder(
        val id: Long,
        val venueId: Long,
        val tableSessionId: Long,
        val serviceDate: LocalDate?,
    )

    private data class GuestVisitHeader(
        val id: Long,
        val venueId: Long,
        val venueName: String,
        val venueCity: String?,
        val bookingId: Long?,
        val tableSessionId: Long?,
        val orderId: Long?,
        val source: VisitSource,
        val occurredAt: Instant,
        val serviceDate: LocalDate?,
    )
}
