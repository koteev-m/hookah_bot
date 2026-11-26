package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.miniapp.venue.orders.OrderBatchItemStatus
import com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.miniapp.venue.orders.toOrderBillSnapshot
import com.hookah.platform.backend.telegram.db.BotCheckoutScope
import com.hookah.platform.backend.telegram.db.BotPreparedCheckoutRepository
import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import com.hookah.platform.backend.telegram.db.UserRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.time.Instant

internal const val GUEST_CHECKOUT_STAFF_METHOD = "guestCheckoutStaff"

internal enum class CheckoutBatchKind { FIRST, ADD_ON, REPLACEMENT }

private data class BatchHistory(
    val id: Long,
    val tab: Long?,
    val author: Long?,
    val createdAt: Instant,
    val isNew: Boolean,
    val cancellations: MutableMap<Long, Instant?>,
)

private data class CheckoutClassification(val kind: CheckoutBatchKind, val observed: Set<Long>)

private fun List<BatchHistory>.classify(
    batchId: Long,
    previouslyObserved: (Long) -> Set<Long>? = { null },
): CheckoutClassification {
    val batch = first { it.id == batchId }
    val prior = filter { it.tab == batch.tab && it.id < batch.id }
    val own = prior.filter { batch.author != null && it.author == batch.author }
    val previous = own.maxByOrNull { it.id }
    val canceled = own.filter { it.isNew }.flatMap { it.cancellations.entries }
    val observed = canceled.mapTo(mutableSetOf()) { it.key }
    val kind =
        when {
            prior.isEmpty() -> CheckoutBatchKind.FIRST
            previous == null || observed.isEmpty() -> CheckoutBatchKind.ADD_ON
            else -> {
                val saved = previouslyObserved(previous.id)
                // Older handoffs lack an observation. A cancellation on the most recent batch is new;
                // for earlier batches only a recorded cancellation after that send is attributable.
                val replacement =
                    if (saved != null) {
                        observed.any { it !in saved }
                    } else {
                        previous.isNew && previous.cancellations.isNotEmpty() ||
                            canceled.any { (_, time) -> time != null && time > previous.createdAt }
                    }
                if (replacement) CheckoutBatchKind.REPLACEMENT else CheckoutBatchKind.ADD_ON
            }
        }
    return CheckoutClassification(kind, observed)
}

@Serializable
internal data class GuestCheckoutStaffNotice(
    val venueId: Long,
    val orderId: Long,
    val batchId: Long,
    val liveCard: Boolean,
    val guestUserId: Long? = null,
    val kind: CheckoutBatchKind? = null,
    val observedCanceledItems: Set<Long>? = null,
)

class GuestCheckoutNotifications(
    private val outbox: TelegramOutboxRepository,
    private val enqueuer: TelegramOutboxEnqueuer,
    private val venueOrders: VenueOrdersRepository,
    private val notifier: StaffChatNotifier,
    private val prepared: BotPreparedCheckoutRepository?,
    private val json: Json,
    private val enabled: Boolean,
    private val users: UserRepository? = null,
    private val recoverLegacy: Boolean = true,
) {
    fun stageStaff(
        connection: Connection,
        venueId: Long,
        orderId: Long,
        batchId: Long,
        liveCard: Boolean,
        guestUserId: Long? = null,
    ) {
        if (!enabled) return
        val classification = classifyCheckout(connection, venueId, orderId, batchId)
        val notice =
            GuestCheckoutStaffNotice(
                venueId,
                orderId,
                batchId,
                liveCard,
                guestUserId,
                classification.kind,
                classification.observed,
            )
        outbox.enqueueGuestCheckoutStaffOnConnection(
            connection,
            venueId,
            json.encodeToString(GuestCheckoutStaffNotice.serializer(), notice),
            "guest-checkout-staff:$batchId",
        )
    }

    private fun classifyCheckout(
        connection: Connection,
        venueId: Long,
        orderId: Long,
        batchId: Long,
    ): CheckoutClassification {
        val batches =
            connection.prepareStatement(
                """
                SELECT b.id, b.tab_id, b.status, b.created_at,
                       COALESCE(b.author_user_id, (SELECT g.user_id FROM guest_batch_idempotency g
                           WHERE g.batch_id=b.id ORDER BY g.id DESC LIMIT 1)) AS author,
                       i.id AS canceled_item, i.canceled_at
                FROM order_batches target JOIN orders o ON o.id=target.order_id
                JOIN order_batches b ON b.order_id=o.id AND b.tab_id IS NOT DISTINCT FROM target.tab_id
                LEFT JOIN order_batch_items i ON i.order_batch_id=b.id AND i.item_status='CANCELED' AND b.status='NEW'
                WHERE o.venue_id=? AND o.id=? AND target.id=? AND b.id<=target.id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, orderId)
                statement.setLong(3, batchId)
                statement.executeQuery().use { rows ->
                    val history = mutableMapOf<Long, BatchHistory>()
                    while (rows.next()) {
                        val id = rows.getLong("id")
                        val batch =
                            history.getOrPut(id) {
                                BatchHistory(
                                    id,
                                    rows.getLong("tab_id").takeUnless { rows.wasNull() },
                                    rows.getLong("author").takeUnless { rows.wasNull() },
                                    rows.getTimestamp("created_at").toInstant(),
                                    rows.getString("status") == "NEW",
                                    mutableMapOf(),
                                )
                            }
                        val canceledItem = rows.getLong("canceled_item")
                        if (!rows.wasNull()) {
                            batch.cancellations[canceledItem] = rows.getTimestamp("canceled_at")?.toInstant()
                        }
                    }
                    history.values.toList()
                }
            }
        return batches.classify(batchId) { previous ->
            connection.prepareStatement(
                "SELECT payload_json FROM telegram_outbox WHERE dedupe_key=? AND method=?",
            ).use {
                it.setString(1, "guest-checkout-staff:$previous")
                it.setString(2, GUEST_CHECKOUT_STAFF_METHOD)
                it.executeQuery().use { rows ->
                    if (!rows.next()) return@use null
                    json.decodeFromString<GuestCheckoutStaffNotice>(rows.getString(1)).observedCanceledItems
                }
            }
        }
    }

    fun completeBot(
        connection: Connection,
        scope: BotCheckoutScope,
        checkoutId: String,
        venueId: Long,
        orderId: Long,
        batchId: Long,
        displayNumber: Int?,
        recalculated: Boolean = false,
        replay: Boolean = false,
    ) {
        // The original completion removed this exact preparation in the same commit as both notices.
        if (replay && prepared?.find(connection, scope)?.id != checkoutId) return
        stageStaff(
            connection,
            venueId,
            orderId,
            batchId,
            liveCard = false,
            guestUserId = scope.userId,
        )
        if (enabled) {
            val label = displayNumber?.let { "Заказ №$it" } ?: "Заказ #$orderId"
            val prefix = if (recalculated) "Условия акции изменились. Итог корзины пересчитан.\n\n" else ""
            enqueuer.enqueueCheckoutReceiptInTransaction(
                connection,
                scope.chatId,
                "$prefix✅ Заказ отправлен.\n$label.",
                "guest-checkout-receipt:$batchId",
            )
        }
        prepared?.remove(connection, scope, checkoutId)
    }

    // Older writers could commit a batch without staging either notice.
    suspend fun recoverPrepared() {
        if (!enabled || !recoverLegacy) return
        prepared?.recoverCommitted { connection, checkout ->
            completeBot(
                connection,
                checkout.scope,
                checkout.checkoutId,
                checkout.venueId,
                checkout.orderId,
                checkout.batchId,
                checkout.displayNumber,
            )
        }
    }

    internal suspend fun publishStaff(
        chatId: Long,
        notice: GuestCheckoutStaffNotice,
    ): Boolean {
        val detail =
            venueOrders.loadOrderDetail(
                notice.venueId, notice.orderId, checkoutSnapshot = !notice.liveCard, useMenuFallback = false,
            ) ?: return true
        val batch = detail.batches.firstOrNull { it.batchId == notice.batchId } ?: return true
        val kind =
            notice.kind ?: detail.batches.map { entry ->
                val canceled = entry.items.filter { it.itemStatus == OrderBatchItemStatus.CANCELED }
                BatchHistory(
                    entry.batchId, entry.tabId, entry.authorUserId, entry.createdAt,
                    entry.status == OrderWorkflowStatus.NEW,
                    canceled.associateTo(mutableMapOf()) { it.batchItemId to it.canceledAt },
                )
            }.classify(batch.batchId).kind
        val currency = batch.items.map { it.currency }.distinct().singleOrNull()
        val payable =
            if (currency != null && batch.items.all { it.priceMinor != null } &&
                batch.promotionDiscounts.all { it.currency == currency }
            ) {
                (
                    batch.items.sumOf { requireNotNull(it.priceMinor) * it.qty } -
                        batch.promotionDiscounts.sumOf { it.discountMinor }
                ).coerceAtLeast(0L)
            } else {
                null
            }
        val result =
            notifier.notifyNewBatchNow(
                NewBatchNotification(
                    venueId = notice.venueId,
                    orderId = notice.orderId,
                    batchId = notice.batchId,
                    tableLabel = detail.tableNumber.toString(),
                    itemsSummary =
                        batch.items.joinToString(", ") { item ->
                            val option = item.selectedOption?.let { " · ${it.name}" }.orEmpty()
                            val note = item.preferenceNote?.let { "; пожелание: $it" }.orEmpty()
                            val amount =
                                if (item.priceMinor != null && item.currency != null) {
                                    " — " + formatStaffChatMoney(item.priceMinor * item.qty, item.currency)
                                } else {
                                    ""
                                }
                            "${item.name}$option x${item.qty}$note$amount"
                        }.ifEmpty { if (batch.source == "CHAT") "Быстрый заказ (чат)" else "без деталей" },
                    totalPayableMinor = payable,
                    totalCurrency = currency,
                    comment = batch.comment,
                    displayNumber = detail.displayNumber,
                    isFirstBatch = kind == CheckoutBatchKind.FIRST,
                    isReplacementBeforeAccept = kind == CheckoutBatchKind.REPLACEMENT,
                    guestDisplayName =
                        batch.guestDisplayName ?: notice.guestUserId?.let {
                            users?.findGuestProfile(it)?.guestDisplayName
                        },
                    promotionDiscounts =
                        batch.promotionDiscounts.map { discount ->
                            NewBatchPromotionDiscount(
                                discount.label,
                                discount.discountMinor,
                                discount.currency,
                                discount.ruleType,
                            )
                        },
                    status = detail.status.takeIf { notice.liveCard },
                    bill = detail.toOrderBillSnapshot("RUB").takeIf { notice.liveCard },
                    batches = if (notice.liveCard) detail.toStaffOrderBatchLiveBlocks() else emptyList(),
                    updatedAt = detail.updatedAt.takeIf { notice.liveCard },
                ),
                checkoutChatId = chatId,
            )
        return result != StaffChatNotificationResult.FAILED_ENQUEUE
    }
}
