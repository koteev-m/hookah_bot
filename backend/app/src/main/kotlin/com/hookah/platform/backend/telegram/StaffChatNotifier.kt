package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.miniapp.venue.orders.OrderBillActiveItemSnapshot
import com.hookah.platform.backend.miniapp.venue.orders.OrderBillDiscountSnapshot
import com.hookah.platform.backend.miniapp.venue.orders.OrderBillExcludedItemSnapshot
import com.hookah.platform.backend.miniapp.venue.orders.OrderBillServiceChargeSnapshot
import com.hookah.platform.backend.miniapp.venue.orders.OrderBillSnapshot
import com.hookah.platform.backend.miniapp.venue.orders.OrderDetail
import com.hookah.platform.backend.miniapp.venue.orders.OrderPendingShiftExtension
import com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.miniapp.venue.orders.toOrderBillSnapshot
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.StaffCallStatus
import com.hookah.platform.backend.telegram.db.StaffChatNotificationClaim
import com.hookah.platform.backend.telegram.db.StaffChatNotificationRepository
import com.hookah.platform.backend.telegram.db.StaffOrderActivityItem
import com.hookah.platform.backend.telegram.db.VenueRepository
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class NewBatchNotification(
    val venueId: Long,
    val orderId: Long,
    val batchId: Long,
    val tableLabel: String,
    val itemsSummary: String?,
    val comment: String?,
    val displayNumber: Int? = null,
    val isFirstBatch: Boolean = true,
    val isReplacementBeforeAccept: Boolean = false,
    val guestDisplayName: String? = null,
    val promotionDiscounts: List<NewBatchPromotionDiscount> = emptyList(),
    val totalPayableMinor: Long? = null,
    val totalCurrency: String? = null,
    val status: OrderWorkflowStatus? = null,
    val bill: OrderBillSnapshot? = null,
    val batches: List<StaffOrderBatchLiveBlock> = emptyList(),
    val updatedAt: Instant? = null,
)

data class NewBatchPromotionDiscount(
    val label: String,
    val discountMinor: Long,
    val currency: String,
    val ruleType: String? = null,
)

data class StaffCallNotification(
    val venueId: Long,
    val staffCallId: Long,
    val tableLabel: String,
    val reason: StaffCallReason,
    val comment: String?,
    val tableSessionId: Long?,
    val orderId: Long? = null,
    val type: StaffCallNotificationType = StaffCallNotificationType.NORMAL,
    val guestDisplayName: String? = null,
)

data class StaffBillRequestNotification(
    val venueId: Long,
    val staffCallId: Long,
    val tableLabel: String,
    val orderId: Long,
    val orderDisplayLabel: String,
    val accountLabel: String,
    val billTotalMinor: Long,
    val billCurrency: String,
    val paymentMethod: BillPaymentMethod,
    val guestDisplayName: String? = null,
)

enum class StaffCallNotificationType {
    NORMAL,
    RELOCATION,
}

data class BookingStaffNotification(
    val venueId: Long,
    val bookingId: Long,
    val event: BookingStaffNotificationEvent,
    val scheduledAtText: String?,
    val partySize: Int?,
    val comment: String?,
    val displayNumber: Int? = null,
    val cancelReasonText: String? = null,
    val actorDisplayName: String? = null,
    val guestDisplayName: String? = null,
)

enum class BookingStaffNotificationEvent(val dedupeCode: Long) {
    CREATED(1L),
    UPDATED(2L),
    CANCELLED(3L),
    VENUE_CANCELLED(4L),
}

data class StaffBillUpdatedNotification(
    val venueId: Long,
    val orderId: Long,
    val displayNumber: Int?,
    val tableLabel: String,
    val change: StaffBillUpdateChange,
    val bill: OrderBillSnapshot,
    val batches: List<StaffOrderBatchLiveBlock> = emptyList(),
    val status: OrderWorkflowStatus,
    val actionBatchId: Long,
    val pendingShiftExtension: OrderPendingShiftExtension? = null,
    val updatedAt: Instant,
)

data class StaffOrderBatchLiveBlock(
    val batchId: Long,
    val label: String,
    val status: OrderWorkflowStatus,
    val comment: String?,
    val tabId: Long? = null,
)

internal data class StaffOrderActivityBlock(
    val staffCallId: Long,
    val reason: StaffCallReason,
    val status: StaffCallStatus,
    val comment: String?,
    val accountLabel: String?,
    val paymentMethod: BillPaymentMethod?,
    val billTotalMinor: Long?,
    val billCurrency: String?,
    val guestDisplayName: String?,
)

enum class StaffBillUpdateChange {
    MANUAL_DISCOUNT,
    ITEM_EXCLUDED,
    ITEM_RESTORED,
    STATUS_UPDATED,
    SHIFT_EXTENSION_REQUESTED,
    SHIFT_EXTENSION_APPROVED,
    SHIFT_EXTENSION_REJECTED,
}

enum class StaffChatNotificationResult {
    SENT_OR_QUEUED,
    SKIPPED_NO_STAFF_CHAT,
    SKIPPED_DISABLED,
    SKIPPED_DUPLICATE,
    SKIPPED_INACTIVE,
    FAILED_ENQUEUE,
}

private const val STAFF_ORDER_ACTIVITY_LIMIT = 5

interface StaffBillUpdateNotifier {
    suspend fun notifyBillUpdatedNow(event: StaffBillUpdatedNotification): StaffChatNotificationResult
}

private enum class StaffChatNotificationSetting {
    ORDERS,
    STAFF_CALLS,
    CANCELLATIONS,
}

private data class StaffOrderLiveMessage(
    val venueId: Long,
    val orderId: Long,
    val actionBatchId: Long,
    val displayNumber: Int?,
    val tableLabel: String,
    val status: OrderWorkflowStatus,
    val bill: OrderBillSnapshot,
    val batches: List<StaffOrderBatchLiveBlock>,
    val pendingShiftExtension: OrderPendingShiftExtension? = null,
    val updatedAt: Instant,
    val change: StaffBillUpdateChange? = null,
    val includeStaffCallId: Long? = null,
)

class StaffChatNotifier(
    private val venueRepository: VenueRepository,
    private val notificationRepository: StaffChatNotificationRepository,
    private val venueSettingsRepository: VenueSettingsRepository? = null,
    private val isTelegramActive: () -> Boolean,
    private val scope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val venueMiniAppUrl: (Long) -> String? = { null },
    private val venueOrdersRepository: VenueOrdersRepository? = null,
    private val staffCallRepository: StaffCallRepository? = null,
) : StaffBillUpdateNotifier {
    private val logger = LoggerFactory.getLogger(StaffChatNotifier::class.java)

    fun notifyNewBatch(event: NewBatchNotification) {
        scope.launch {
            try {
                notifyNewBatchNow(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val safeMessage = sanitizeTelegramForLog(e.message)
                logger.warn("Failed to notify staff chat for new batch: {}", safeMessage)
                logger.debugTelegramException(e) { "notifyNewBatch exception" }
            }
        }
    }

    suspend fun notifyNewBatchNow(event: NewBatchNotification): StaffChatNotificationResult {
        if (event.status != null && event.bill != null && event.updatedAt != null) {
            return notifyLiveOrderMessageNow(
                event =
                    StaffOrderLiveMessage(
                        venueId = event.venueId,
                        orderId = event.orderId,
                        actionBatchId = event.batchId,
                        displayNumber = event.displayNumber,
                        tableLabel = event.tableLabel,
                        status = event.status,
                        bill = event.bill,
                        batches = event.batches,
                        updatedAt = event.updatedAt,
                    ),
                notificationKey = event.batchId,
            )
        }
        val summary = event.itemsSummary?.takeIf { it.isNotBlank() } ?: "без деталей"
        val comment = event.comment?.takeIf { it.isNotBlank() }
        val links = comment?.let { extractLinks(it) }.orEmpty()
        return notifyTextNow(
            venueId = event.venueId,
            notificationKey = event.batchId,
            setting = StaffChatNotificationSetting.ORDERS,
            messageBuilder = { venue ->
                buildNewBatchNotificationText(
                    venueName = venue.name,
                    tableLabel = event.tableLabel,
                    itemsSummary = summary,
                    comment = comment,
                    links = links,
                    displayNumber = event.displayNumber,
                    isFirstBatch = event.isFirstBatch,
                    isReplacementBeforeAccept = event.isReplacementBeforeAccept,
                    guestDisplayName = event.guestDisplayName,
                    promotionDiscounts = event.promotionDiscounts,
                    totalPayableMinor = event.totalPayableMinor,
                    totalCurrency = event.totalCurrency,
                )
            },
            replyMarkup =
                TelegramKeyboards.inlineStaffChatOrderActions(
                    venueId = event.venueId,
                    orderId = event.orderId,
                    batchId = event.batchId,
                    status = OrderWorkflowStatus.NEW,
                    webAppUrl = venueMiniAppUrl(event.venueId),
                ),
        )
    }

    suspend fun notifyStaffCallNow(event: StaffCallNotification): StaffChatNotificationResult {
        if (
            event.orderId != null &&
            isOrderLinkEligibleStaffCall(
                reason = event.reason,
                comment = event.comment,
                type = event.type,
            )
        ) {
            val liveResult =
                notifyOrderActivityCardNow(
                    venueId = event.venueId,
                    orderId = event.orderId,
                    notificationKey = staffCallNotificationKey(event.staffCallId),
                    includeStaffCallId = event.staffCallId,
                    change = StaffBillUpdateChange.STATUS_UPDATED,
                )
            if (liveResult != null) {
                return liveResult
            }
        }
        return notifyTextNow(
            venueId = event.venueId,
            notificationKey = staffCallNotificationKey(event.staffCallId),
            setting = StaffChatNotificationSetting.STAFF_CALLS,
            messageBuilder = { venue ->
                buildStaffCallNotificationText(
                    venueName = venue.name,
                    tableLabel = event.tableLabel,
                    reason = event.reason,
                    comment = event.comment,
                    type = event.type,
                    guestDisplayName = event.guestDisplayName,
                )
            },
            replyMarkup = TelegramKeyboards.inlineStaffChatStaffCallAck(event.venueId, event.staffCallId),
        )
    }

    suspend fun notifyBillRequestNow(event: StaffBillRequestNotification): StaffChatNotificationResult {
        val liveResult =
            notifyOrderActivityCardNow(
                venueId = event.venueId,
                orderId = event.orderId,
                notificationKey = staffCallNotificationKey(event.staffCallId),
                includeStaffCallId = event.staffCallId,
                change = StaffBillUpdateChange.STATUS_UPDATED,
            )
        if (liveResult != null) {
            return liveResult
        }
        return notifyTextNow(
            venueId = event.venueId,
            notificationKey = staffCallNotificationKey(event.staffCallId),
            setting = StaffChatNotificationSetting.STAFF_CALLS,
            messageBuilder = { venue ->
                buildStaffBillRequestNotificationText(
                    venueName = venue.name,
                    tableLabel = event.tableLabel,
                    orderDisplayLabel = event.orderDisplayLabel,
                    accountLabel = event.accountLabel,
                    billTotalMinor = event.billTotalMinor,
                    billCurrency = event.billCurrency,
                    paymentMethod = event.paymentMethod,
                    guestDisplayName = event.guestDisplayName,
                )
            },
            replyMarkup = TelegramKeyboards.inlineStaffChatStaffCallAck(event.venueId, event.staffCallId),
        )
    }

    suspend fun notifyBookingNow(event: BookingStaffNotification): StaffChatNotificationResult =
        notifyTextNow(
            venueId = event.venueId,
            notificationKey = bookingNotificationKey(event.bookingId, event.event),
            setting =
                when (event.event) {
                    BookingStaffNotificationEvent.CANCELLED,
                    BookingStaffNotificationEvent.VENUE_CANCELLED,
                    -> StaffChatNotificationSetting.CANCELLATIONS
                    BookingStaffNotificationEvent.CREATED,
                    BookingStaffNotificationEvent.UPDATED,
                    -> null
                },
            messageBuilder = { venue ->
                buildBookingStaffNotificationText(
                    venueName = venue.name,
                    event = event.event,
                    displayNumber = event.displayNumber,
                    scheduledAtText = event.scheduledAtText,
                    partySize = event.partySize,
                    comment = event.comment,
                    cancelReasonText = event.cancelReasonText,
                    actorDisplayName = event.actorDisplayName,
                    guestDisplayName = event.guestDisplayName,
                )
            },
            replyMarkup =
                if (event.event == BookingStaffNotificationEvent.CREATED) {
                    TelegramKeyboards.inlineVenueStaffBookingActions(
                        venueId = event.venueId,
                        bookingId = event.bookingId,
                        canConfirm = true,
                    )
                } else {
                    ReplyKeyboardRemove(removeKeyboard = true)
                },
        )

    suspend fun notifyTestMessageNow(venueId: Long): StaffChatNotificationResult =
        notifyTextWithoutClaimNow(
            venueId = venueId,
            setting = null,
            messageBuilder = { venue ->
                """
                ✅ Тестовое сообщение

                Чат персонала для «${venue.name}» подключён и получает сообщения.
                """.trimIndent()
            },
        )

    override suspend fun notifyBillUpdatedNow(event: StaffBillUpdatedNotification): StaffChatNotificationResult =
        notifyLiveOrderMessageNow(
            event =
                StaffOrderLiveMessage(
                    venueId = event.venueId,
                    orderId = event.orderId,
                    actionBatchId = event.actionBatchId,
                    displayNumber = event.displayNumber,
                    tableLabel = event.tableLabel,
                    status = event.status,
                    bill = event.bill,
                    batches = event.batches,
                    updatedAt = event.updatedAt,
                    pendingShiftExtension = event.pendingShiftExtension,
                    change = event.change,
                ),
            notificationKey = null,
        )

    private suspend fun notifyOrderActivityCardNow(
        venueId: Long,
        orderId: Long,
        notificationKey: Long?,
        includeStaffCallId: Long?,
        change: StaffBillUpdateChange?,
    ): StaffChatNotificationResult? {
        val detail =
            runCatching { venueOrdersRepository?.loadOrderDetail(venueId = venueId, orderId = orderId) }
                .onFailure { e ->
                    logger.warn(
                        "Failed to load order detail for staff chat activity card venue_id={} order_id={}: {}",
                        venueId,
                        orderId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) { "load order detail for staff chat activity card" }
                }
                .getOrNull()
                ?: return null
        return notifyLiveOrderMessageNow(
            event =
                StaffOrderLiveMessage(
                    venueId = venueId,
                    orderId = orderId,
                    actionBatchId = staffOrderActivityActionBatchId(detail),
                    displayNumber = detail.displayNumber,
                    tableLabel = detail.tableNumber.toString(),
                    status = detail.status,
                    bill = detail.toOrderBillSnapshot(),
                    batches = detail.toStaffOrderBatchLiveBlocks(),
                    pendingShiftExtension = detail.pendingShiftExtension,
                    updatedAt = detail.updatedAt,
                    change = change,
                    includeStaffCallId = includeStaffCallId,
                ),
            notificationKey = notificationKey,
        )
    }

    suspend fun refreshOrderActivityCardNow(
        venueId: Long,
        orderId: Long,
        includeStaffCallId: Long? = null,
    ): StaffChatNotificationResult? =
        notifyOrderActivityCardNow(
            venueId = venueId,
            orderId = orderId,
            notificationKey = null,
            includeStaffCallId = includeStaffCallId,
            change = StaffBillUpdateChange.STATUS_UPDATED,
        )

    suspend fun rememberOrderMessageNow(
        venueId: Long,
        orderId: Long,
        chatId: Long,
        messageId: Long,
    ): Boolean =
        notificationRepository.upsertOrderMessage(
            orderId = orderId,
            venueId = venueId,
            chatId = chatId,
            messageId = messageId,
        )

    private suspend fun notifyLiveOrderMessageNow(
        event: StaffOrderLiveMessage,
        notificationKey: Long?,
    ): StaffChatNotificationResult {
        val logKey = notificationKey ?: event.orderId
        if (!isTelegramActive()) {
            logResult(event.venueId, logKey, StaffChatNotificationResult.SKIPPED_INACTIVE)
            return StaffChatNotificationResult.SKIPPED_INACTIVE
        }
        val venue = venueRepository.findVenueById(event.venueId)
        val chatId = venue?.staffChatId
        if (venue == null || chatId == null) {
            logResult(event.venueId, logKey, StaffChatNotificationResult.SKIPPED_NO_STAFF_CHAT)
            return StaffChatNotificationResult.SKIPPED_NO_STAFF_CHAT
        }
        if (!isEnabled(event.venueId, StaffChatNotificationSetting.ORDERS, staffChatLinked = true)) {
            logResult(event.venueId, logKey, StaffChatNotificationResult.SKIPPED_DISABLED)
            return StaffChatNotificationResult.SKIPPED_DISABLED
        }
        val venueZoneId = resolveVenueZoneId(event.venueId)
        val activities = loadStaffOrderActivities(event)
        val text =
            buildStaffOrderLiveMessageText(
                venueName = venue.name,
                tableLabel = event.tableLabel,
                displayNumber = event.displayNumber,
                status = event.status,
                bill = event.bill,
                batches = event.batches,
                pendingShiftExtension = event.pendingShiftExtension,
                updatedAt = event.updatedAt,
                venueZoneId = venueZoneId,
                change = event.change,
                activities = activities,
            )
        val actionTarget = staffOrderLiveActionTarget(event)
        val replyMarkup =
            TelegramKeyboards.inlineStaffChatOrderActions(
                venueId = event.venueId,
                orderId = event.orderId,
                batchId = actionTarget.batchId,
                status = actionTarget.status,
                webAppUrl = venueMiniAppUrl(event.venueId),
                batchLabel = actionTarget.label,
                pendingShiftExtensionRequestId = event.pendingShiftExtension?.requestId,
                staffCallAction = staffOrderActivityInlineAction(event.venueId, activities),
            )
        val existingMessage = notificationRepository.findOrderMessage(event.orderId)
        val (method, payloadJson) =
            if (existingMessage?.messageId != null) {
                "editMessageText" to
                    json.encodeToString(
                        EditMessageTextPayload.serializer(),
                        buildEditMessageTextPayload(
                            json = json,
                            chatId = chatId,
                            messageId = existingMessage.messageId,
                            text = text,
                            replyMarkup = replyMarkup,
                        ),
                    )
            } else {
                "sendMessage" to
                    json.encodeToString(
                        SendMessagePayload.serializer(),
                        buildSendMessagePayload(
                            json = json,
                            chatId = chatId,
                            text = text,
                            replyMarkup = replyMarkup,
                        ),
                    )
            }
        val claimResult =
            if (notificationKey != null) {
                notificationRepository.tryClaimAndEnqueueOrderMessage(
                    notificationKey = notificationKey,
                    orderId = event.orderId,
                    venueId = event.venueId,
                    chatId = chatId,
                    method = method,
                    payloadJson = payloadJson,
                )
            } else if (notificationRepository.enqueueOrderMessage(
                    orderId = event.orderId,
                    venueId = event.venueId,
                    chatId = chatId,
                    method = method,
                    payloadJson = payloadJson,
                )
            ) {
                StaffChatNotificationClaim.CLAIMED
            } else {
                StaffChatNotificationClaim.ERROR
            }
        val result =
            when (claimResult) {
                StaffChatNotificationClaim.CLAIMED -> StaffChatNotificationResult.SENT_OR_QUEUED
                StaffChatNotificationClaim.ALREADY -> StaffChatNotificationResult.SKIPPED_DUPLICATE
                StaffChatNotificationClaim.ERROR -> StaffChatNotificationResult.FAILED_ENQUEUE
            }
        logResult(event.venueId, logKey, result)
        return result
    }

    private suspend fun loadStaffOrderActivities(event: StaffOrderLiveMessage): List<StaffOrderActivityBlock> {
        val repository = staffCallRepository ?: return emptyList()
        val items =
            runCatching {
                repository.listOrderActivity(
                    venueId = event.venueId,
                    orderId = event.orderId,
                    includeStaffCallId = event.includeStaffCallId,
                    limit = STAFF_ORDER_ACTIVITY_LIMIT,
                )
            }.onFailure { e ->
                logger.warn(
                    "Failed to load staff order activity venue_id={} order_id={}: {}",
                    event.venueId,
                    event.orderId,
                    sanitizeTelegramForLog(e.message),
                )
                logger.debugTelegramException(e) { "load staff order activity" }
            }.getOrDefault(emptyList())
        return items.map { item -> item.toStaffOrderActivityBlock(event) }
    }

    private fun StaffOrderActivityItem.toStaffOrderActivityBlock(
        event: StaffOrderLiveMessage,
    ): StaffOrderActivityBlock {
        val billTotal =
            if (reason == StaffCallReason.BILL) {
                staffOrderActivityBillTotal(event, tabId)
            } else {
                null
            }
        return StaffOrderActivityBlock(
            staffCallId = staffCallId,
            reason = reason,
            status = status,
            comment = comment,
            accountLabel = tabDisplayLabel,
            paymentMethod = paymentMethod,
            billTotalMinor = billTotal?.first,
            billCurrency = billTotal?.second,
            guestDisplayName = guestDisplayName,
        )
    }

    private fun staffOrderActivityBillTotal(
        event: StaffOrderLiveMessage,
        tabId: Long?,
    ): Pair<Long, String> {
        val tabBatchIds =
            tabId?.let { id ->
                event.batches
                    .filter { batch -> batch.tabId == id }
                    .map { batch -> batch.batchId }
                    .toSet()
                    .takeIf { it.isNotEmpty() }
            }
        val itemTotal =
            if (tabBatchIds == null) {
                event.bill.activeItems.sumOf { it.linePayableMinor }
            } else {
                event.bill.activeItems
                    .filter { item -> item.batchId in tabBatchIds }
                    .sumOf { item -> item.linePayableMinor }
            }
        return (itemTotal + event.bill.serviceCharges.sumOf { it.totalMinor }) to event.bill.currency
    }

    private fun staffOrderActivityInlineAction(
        venueId: Long,
        activities: List<StaffOrderActivityBlock>,
    ): StaffChatStaffCallInlineAction? {
        val active = activities.filter { it.status == StaffCallStatus.NEW || it.status == StaffCallStatus.ACK }
        val target = active.singleOrNull() ?: return null
        val (text, callbackPrefix) =
            when (target.status) {
                StaffCallStatus.NEW ->
                    (if (target.reason == StaffCallReason.BILL) "✅ Принять счёт" else "✅ Принять вызов") to
                        "sc_call_ack"
                StaffCallStatus.ACK ->
                    (if (target.reason == StaffCallReason.BILL) "✅ Счёт вынесен" else "✅ Выполнено") to
                        "sc_call_done"
                else -> return null
            }
        return StaffChatStaffCallInlineAction(
            text = text,
            callbackData = "$callbackPrefix:$venueId:${target.staffCallId}",
        )
    }

    private suspend fun notifyTextNow(
        venueId: Long,
        notificationKey: Long,
        setting: StaffChatNotificationSetting?,
        messageBuilder: (com.hookah.platform.backend.telegram.db.VenueShort) -> String,
        replyMarkup: ReplyMarkup? = ReplyKeyboardRemove(removeKeyboard = true),
    ): StaffChatNotificationResult {
        if (!isTelegramActive()) {
            logResult(venueId, notificationKey, StaffChatNotificationResult.SKIPPED_INACTIVE)
            return StaffChatNotificationResult.SKIPPED_INACTIVE
        }
        val venue = venueRepository.findVenueById(venueId)
        val chatId = venue?.staffChatId
        if (venue == null || chatId == null) {
            logResult(venueId, notificationKey, StaffChatNotificationResult.SKIPPED_NO_STAFF_CHAT)
            return StaffChatNotificationResult.SKIPPED_NO_STAFF_CHAT
        }
        if (!isEnabled(venueId, setting, staffChatLinked = true)) {
            logResult(venueId, notificationKey, StaffChatNotificationResult.SKIPPED_DISABLED)
            return StaffChatNotificationResult.SKIPPED_DISABLED
        }
        val message = messageBuilder(venue)
        val payloadJson =
            json.encodeToString(
                SendMessagePayload.serializer(),
                buildSendMessagePayload(
                    json = json,
                    chatId = chatId,
                    text = message,
                    replyMarkup = replyMarkup,
                ),
            )
        val claimResult =
            notificationRepository.tryClaimAndEnqueue(
                notificationKey = notificationKey,
                chatId = chatId,
                method = "sendMessage",
                payloadJson = payloadJson,
            )
        val result =
            when (claimResult) {
                StaffChatNotificationClaim.CLAIMED -> StaffChatNotificationResult.SENT_OR_QUEUED
                StaffChatNotificationClaim.ALREADY -> StaffChatNotificationResult.SKIPPED_DUPLICATE
                StaffChatNotificationClaim.ERROR -> StaffChatNotificationResult.FAILED_ENQUEUE
            }
        logResult(venueId, notificationKey, result)
        return result
    }

    private suspend fun notifyTextWithoutClaimNow(
        venueId: Long,
        setting: StaffChatNotificationSetting?,
        messageBuilder: (com.hookah.platform.backend.telegram.db.VenueShort) -> String,
        replyMarkup: ReplyMarkup? = ReplyKeyboardRemove(removeKeyboard = true),
    ): StaffChatNotificationResult {
        if (!isTelegramActive()) {
            logResult(venueId, notificationKey = 0L, StaffChatNotificationResult.SKIPPED_INACTIVE)
            return StaffChatNotificationResult.SKIPPED_INACTIVE
        }
        val venue = venueRepository.findVenueById(venueId)
        val chatId = venue?.staffChatId
        if (venue == null || chatId == null) {
            logResult(venueId, notificationKey = 0L, StaffChatNotificationResult.SKIPPED_NO_STAFF_CHAT)
            return StaffChatNotificationResult.SKIPPED_NO_STAFF_CHAT
        }
        if (!isEnabled(venueId, setting, staffChatLinked = true)) {
            logResult(venueId, notificationKey = 0L, StaffChatNotificationResult.SKIPPED_DISABLED)
            return StaffChatNotificationResult.SKIPPED_DISABLED
        }
        val payloadJson =
            json.encodeToString(
                SendMessagePayload.serializer(),
                buildSendMessagePayload(
                    json = json,
                    chatId = chatId,
                    text = messageBuilder(venue),
                    replyMarkup = replyMarkup,
                ),
            )
        val result =
            if (notificationRepository.enqueue(chatId, "sendMessage", payloadJson)) {
                StaffChatNotificationResult.SENT_OR_QUEUED
            } else {
                StaffChatNotificationResult.FAILED_ENQUEUE
            }
        logResult(venueId, notificationKey = 0L, result)
        return result
    }

    private suspend fun isEnabled(
        venueId: Long,
        setting: StaffChatNotificationSetting?,
        staffChatLinked: Boolean,
    ): Boolean {
        if (setting == null) {
            return true
        }
        if (staffChatLinked) {
            return true
        }
        val settings =
            runCatching { venueSettingsRepository?.find(venueId) }
                .onFailure { e ->
                    logger.warn(
                        "Failed to load staff chat notification settings venue_id={}: {}",
                        venueId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) { "load staff chat notification settings" }
                }
                .getOrNull() ?: return true
        return when (setting) {
            StaffChatNotificationSetting.ORDERS -> settings.notifyOrdersEnabled
            StaffChatNotificationSetting.STAFF_CALLS -> settings.notifyStaffCallsEnabled
            StaffChatNotificationSetting.CANCELLATIONS -> settings.notifyCancellationsEnabled
        }
    }

    private suspend fun resolveVenueZoneId(venueId: Long): ZoneId {
        val timezone =
            runCatching { venueSettingsRepository?.find(venueId)?.timezone }
                .onFailure { e ->
                    logger.warn(
                        "Failed to load venue timezone for staff chat live message venue_id={}: {}",
                        venueId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) { "load staff chat venue timezone" }
                }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE
        return runCatching { ZoneId.of(timezone) }
            .onFailure { logger.warn("Invalid venue timezone venue_id={} timezone={}", venueId, timezone) }
            .getOrDefault(defaultStaffChatVenueZoneId)
    }

    private fun logResult(
        venueId: Long,
        notificationKey: Long,
        result: StaffChatNotificationResult,
    ) {
        when (result) {
            StaffChatNotificationResult.SENT_OR_QUEUED ->
                logger.info(
                    "Staff chat notification result={} venue_id={} key={}",
                    result,
                    venueId,
                    notificationKey,
                )
            StaffChatNotificationResult.SKIPPED_DUPLICATE ->
                logger.debug(
                    "Staff chat notification result={} venue_id={} key={}",
                    result,
                    venueId,
                    notificationKey,
                )
            StaffChatNotificationResult.SKIPPED_NO_STAFF_CHAT,
            StaffChatNotificationResult.SKIPPED_DISABLED,
            StaffChatNotificationResult.SKIPPED_INACTIVE,
            StaffChatNotificationResult.FAILED_ENQUEUE,
            ->
                logger.warn(
                    "Staff chat notification result={} venue_id={} key={}",
                    result,
                    venueId,
                    notificationKey,
                )
        }
    }
}

private fun extractLinks(text: String): List<String> {
    val regex = Regex("(https?://\\S+)")
    return regex.findAll(text)
        .map { it.value.trimEnd(',', '.', ';') }
        .filter { it.isNotBlank() }
        .take(5)
        .toList()
}

internal fun buildNewBatchNotificationText(
    venueName: String,
    tableLabel: String,
    itemsSummary: String?,
    comment: String?,
    links: List<String> = emptyList(),
    displayNumber: Int? = null,
    isFirstBatch: Boolean = true,
    isReplacementBeforeAccept: Boolean = false,
    statusLine: String? = null,
    guestDisplayName: String? = null,
    promotionDiscounts: List<NewBatchPromotionDiscount> = emptyList(),
    totalPayableMinor: Long? = null,
    totalCurrency: String? = null,
): String =
    buildString {
        when {
            isReplacementBeforeAccept -> {
                append("Заказ")
                displayNumber?.let { append(" №").append(it) }
                append(" обновлён")
            }
            isFirstBatch -> {
                append("🆕 Новый заказ")
                displayNumber?.let { append(" №").append(it) }
            }
            else -> {
                append("➕ Дозаказ к заказу")
                displayNumber?.let { append(" №").append(it) }
            }
        }
        append('\n')
        append("Заведение: ").append(venueName).append('\n')
        append("Стол: ").append(tableLabel).append('\n')
        append("Гость: ").append(guestDisplayNameOrFallback(guestDisplayName)).append('\n')
        append("Состав:\n").append(formatStaffChatOrderItems(itemsSummary))
        if (promotionDiscounts.isNotEmpty()) {
            append('\n')
            append(formatStaffChatPromotionDiscounts(promotionDiscounts))
            if (totalPayableMinor != null && !totalCurrency.isNullOrBlank()) {
                append('\n')
                append("Итого к оплате: ").append(formatStaffChatMoney(totalPayableMinor, totalCurrency))
            }
        }
        if (!comment.isNullOrBlank()) {
            append('\n').append("Комментарий: ").append(comment)
        }
        if (links.isNotEmpty()) {
            append('\n').append("Ссылки: ").append(links.joinToString(" "))
        }
        append("\n\nЕсли позиция закончилась или нужно изменить счёт — откройте детали заказа в боте.")
        statusLine?.takeIf { it.isNotBlank() }?.let {
            append("\n\n").append(it)
        }
    }

private fun formatStaffChatPromotionDiscounts(discounts: List<NewBatchPromotionDiscount>): String {
    val lines =
        discounts
            .filter { discount -> discount.discountMinor > 0L && discount.currency.isNotBlank() }
            .map { discount ->
                val label = discount.label.takeIf { it.isNotBlank() } ?: "Акция"
                label to "−${formatStaffChatMoney(discount.discountMinor, discount.currency)}"
            }
    if (lines.isEmpty()) return ""
    if (lines.size == 1) {
        val (label, amount) = lines.single()
        return "🎁 $label: $amount"
    }
    return buildString {
        append("🎁 Акции:")
        lines.forEach { (label, amount) ->
            append("\n• ").append(label).append(": ").append(amount)
        }
    }
}

private val staffChatLiveMessageUpdatedAtFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

private val defaultStaffChatVenueZoneId: ZoneId =
    ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE)

internal fun OrderDetail.toStaffOrderBatchLiveBlocks(): List<StaffOrderBatchLiveBlock> =
    batches.mapIndexed { index, batch ->
        StaffOrderBatchLiveBlock(
            batchId = batch.batchId,
            label = staffOrderBatchLabel(index),
            status = batch.status,
            comment = batch.comment,
            tabId = batch.tabId,
        )
    }

private fun staffOrderActivityActionBatchId(detail: OrderDetail): Long =
    detail.batches.lastOrNull()?.batchId ?: detail.orderId

internal fun buildStaffOrderLiveMessageText(
    venueName: String,
    tableLabel: String,
    displayNumber: Int?,
    status: OrderWorkflowStatus,
    bill: OrderBillSnapshot,
    batches: List<StaffOrderBatchLiveBlock> = emptyList(),
    pendingShiftExtension: OrderPendingShiftExtension? = null,
    updatedAt: Instant,
    venueZoneId: ZoneId = defaultStaffChatVenueZoneId,
    change: StaffBillUpdateChange? = null,
    activities: List<StaffOrderActivityBlock> = emptyList(),
): String =
    buildString {
        append("🧾 Заказ")
        displayNumber?.let { append(" №").append(it) }
        append('\n')
        append("Заведение: ").append(venueName).append('\n')
        append("Стол: ").append(tableLabel).append('\n')
        append("Статус: ").append(staffOrderStatusLabel(status)).append('\n')
        change?.let { updateChange ->
            append("Изменение: ").append(staffBillUpdateChangeLabel(updateChange)).append('\n')
        }
        if (activities.isNotEmpty()) {
            append("\nОперативно:\n")
            append(formatStaffOrderActivities(activities))
            append('\n')
        }
        val excludedItems = bill.excludedItems.filter { item -> item.status == "excluded" }
        if (batches.isEmpty()) {
            append("\nАктивные позиции:\n")
            append(formatStaffBillActiveItems(bill.activeItems, bill.currency))
            pendingShiftExtension?.let { extension ->
                append("\n\n")
                append(formatStaffPendingShiftExtension(extension))
            }
            if (bill.serviceCharges.isNotEmpty()) {
                append("\n\nДополнительно:\n")
                append(formatStaffBillServiceCharges(bill.serviceCharges, bill.currency))
            }
            if (excludedItems.isNotEmpty()) {
                append("\n\nИсключено из счёта:\n")
                append(formatStaffBillExcludedItems(excludedItems))
            }
        } else {
            append("\n")
            append(formatStaffOrderBatchBlocks(batches, bill, status))
            pendingShiftExtension?.let { extension ->
                append("\n\n")
                append(formatStaffPendingShiftExtension(extension))
            }
            if (bill.serviceCharges.isNotEmpty()) {
                append("\n\nДополнительно:\n")
                append(formatStaffBillServiceCharges(bill.serviceCharges, bill.currency))
            }
        }
        append("\n\nСумма до скидок: ").append(formatStaffChatMoney(bill.grossTotalMinor, bill.currency))
        if (bill.manualDiscountTotalMinor > 0L) {
            append("\nСкидка заведения: −").append(formatStaffChatMoney(bill.manualDiscountTotalMinor, bill.currency))
        }
        appendStaffBillDiscountLines("Акции", bill.promoDiscounts)
        appendStaffBillDiscountLines("Лояльность", bill.loyaltyDiscounts)
        if (bill.excludedTotalMinor > 0L) {
            append("\nИсключено: −").append(formatStaffChatMoney(bill.excludedTotalMinor, bill.currency))
        }
        append("\nК оплате: ").append(formatStaffChatMoney(bill.finalPayableTotalMinor, bill.currency))
        append("\nОбновлено: ").append(formatStaffChatLiveMessageUpdatedAt(updatedAt, venueZoneId))
        if (status == OrderWorkflowStatus.CLOSED) {
            append("\n\nСчёт закрыт.")
        }
    }

private fun formatStaffOrderActivities(activities: List<StaffOrderActivityBlock>): String =
    activities
        .take(STAFF_ORDER_ACTIVITY_LIMIT)
        .joinToString("\n") { activity ->
            when (activity.reason) {
                StaffCallReason.BILL -> formatStaffOrderBillRequestActivity(activity)
                else -> formatStaffOrderStaffCallActivity(activity)
            }
        }

private fun formatStaffOrderBillRequestActivity(activity: StaffOrderActivityBlock): String =
    buildString {
        append("• Запрос счёта")
        activity.accountLabel?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        append(" — ").append(staffCallStatusLabel(activity.status))
        activity.paymentMethod?.let { method ->
            append(", ").append(billPaymentMethodLabel(method))
        }
        if (activity.billTotalMinor != null && !activity.billCurrency.isNullOrBlank()) {
            append(", к оплате ").append(formatStaffChatMoney(activity.billTotalMinor, activity.billCurrency))
        }
        activity.guestDisplayName?.takeIf { it.isNotBlank() }?.let { guest ->
            append(", гость ").append(guest)
        }
    }

private fun formatStaffOrderStaffCallActivity(activity: StaffOrderActivityBlock): String =
    buildString {
        append("• Вызов: ").append(staffCallReasonLabel(activity.reason))
        append(" — ").append(staffCallStatusLabel(activity.status))
        staffCallUserFacingComment(activity.comment)?.let { comment ->
            append(", ").append(comment.take(120))
        }
        activity.guestDisplayName?.takeIf { it.isNotBlank() }?.let { guest ->
            append(", гость ").append(guest)
        }
    }

private fun staffCallStatusLabel(status: StaffCallStatus): String =
    when (status) {
        StaffCallStatus.NEW -> "новый"
        StaffCallStatus.ACK -> "в работе"
        StaffCallStatus.DONE -> "выполнен"
        StaffCallStatus.CANCELLED -> "отменён"
    }

private fun formatStaffChatLiveMessageUpdatedAt(
    updatedAt: Instant,
    venueZoneId: ZoneId,
): String = staffChatLiveMessageUpdatedAtFormatter.format(updatedAt.atZone(venueZoneId))

private data class StaffOrderActionTarget(
    val batchId: Long,
    val status: OrderWorkflowStatus,
    val label: String?,
)

private fun staffOrderLiveActionTarget(event: StaffOrderLiveMessage): StaffOrderActionTarget {
    if (event.status == OrderWorkflowStatus.CLOSED) {
        return StaffOrderActionTarget(
            batchId = event.actionBatchId,
            status = OrderWorkflowStatus.CLOSED,
            label = staffOrderBatchLabelFor(event.actionBatchId, event.batches),
        )
    }
    val activeBatches =
        event.batches.filter { batch ->
            batch.status != OrderWorkflowStatus.CLOSED && event.hasOperationalItems(batch.batchId)
        }
    val nextBatch =
        activeBatches.firstOrNull { batch -> batch.status == OrderWorkflowStatus.NEW }
            ?: activeBatches.firstOrNull { batch ->
                batch.status == OrderWorkflowStatus.ACCEPTED ||
                    batch.status == OrderWorkflowStatus.COOKING ||
                    batch.status == OrderWorkflowStatus.DELIVERING
            }
    if (nextBatch != null) {
        return StaffOrderActionTarget(
            batchId = nextBatch.batchId,
            status = nextBatch.status,
            label = nextBatch.label,
        )
    }
    val deliveredBatch =
        activeBatches.lastOrNull { batch -> batch.status == OrderWorkflowStatus.DELIVERED }
    val allActiveBatchesDelivered =
        activeBatches.isNotEmpty() &&
            activeBatches.all { batch -> batch.status == OrderWorkflowStatus.DELIVERED }
    return StaffOrderActionTarget(
        batchId = deliveredBatch?.batchId ?: event.actionBatchId,
        status =
            if (allActiveBatchesDelivered) {
                OrderWorkflowStatus.DELIVERED
            } else {
                event.status
            },
        label = deliveredBatch?.label ?: staffOrderBatchLabelFor(event.actionBatchId, event.batches),
    )
}

private fun StaffOrderLiveMessage.hasOperationalItems(batchId: Long): Boolean =
    bill.activeItems.any { item -> item.batchId == batchId } ||
        bill.excludedItems.any { item ->
            item.batchId == batchId && item.status == "excluded"
        }

private fun formatStaffOrderBatchBlocks(
    batches: List<StaffOrderBatchLiveBlock>,
    bill: OrderBillSnapshot,
    orderStatus: OrderWorkflowStatus,
): String =
    batches.joinToString(separator = "\n\n") { batch ->
        val activeItems = bill.activeItems.filter { item -> item.batchId == batch.batchId }
        val nonPayableItems = bill.excludedItems.filter { item -> item.batchId == batch.batchId }
        buildString {
            append(batch.label)
            append(" — ")
            append(
                if (orderStatus == OrderWorkflowStatus.CLOSED) {
                    staffOrderStatusLabel(OrderWorkflowStatus.CLOSED)
                } else {
                    staffOrderStatusLabel(batch.status)
                },
            )
            batch.comment?.takeIf { it.isNotBlank() }?.let { comment ->
                append("\nКомментарий: ").append(comment)
            }
            append("\nАктивные позиции:\n")
            append(formatStaffBillActiveItems(activeItems, bill.currency))
            val excludedItems = nonPayableItems.filter { item -> item.status == "excluded" }
            if (excludedItems.isNotEmpty()) {
                append("\nИсключено из счёта:\n")
                append(formatStaffBillExcludedItems(excludedItems))
            }
            val canceledItems =
                nonPayableItems.filter { item ->
                    item.status == "canceled" || item.status == "rejected_batch"
                }
            if (canceledItems.isNotEmpty()) {
                append("\nНе оплачивается:\n")
                append(formatStaffBillExcludedItems(canceledItems))
            }
        }
    }

internal fun buildStaffBillUpdatedNotificationText(
    venueName: String,
    tableLabel: String,
    displayNumber: Int?,
    change: StaffBillUpdateChange,
    bill: OrderBillSnapshot,
): String =
    buildString {
        append("⚠️ Счёт обновлён")
        displayNumber?.let { append(" №").append(it) }
        append('\n')
        append("Заведение: ").append(venueName).append('\n')
        append("Стол: ").append(tableLabel).append('\n')
        append("Изменение: ").append(staffBillUpdateChangeLabel(change)).append('\n')
        append("\nАктивные позиции:\n")
        append(formatStaffBillActiveItems(bill.activeItems, bill.currency))
        if (bill.serviceCharges.isNotEmpty()) {
            append("\n\nДополнительно:\n")
            append(formatStaffBillServiceCharges(bill.serviceCharges, bill.currency))
        }
        val excludedItems = bill.excludedItems.filter { item -> item.status == "excluded" }
        if (excludedItems.isNotEmpty()) {
            append("\n\nИсключено из счёта:\n")
            append(formatStaffBillExcludedItems(excludedItems))
        }
        append("\n\nСумма до скидок: ").append(formatStaffChatMoney(bill.grossTotalMinor, bill.currency))
        if (bill.manualDiscountTotalMinor > 0L) {
            append("\nСкидка заведения: −").append(formatStaffChatMoney(bill.manualDiscountTotalMinor, bill.currency))
        }
        appendStaffBillDiscountLines("Акции", bill.promoDiscounts)
        appendStaffBillDiscountLines("Лояльность", bill.loyaltyDiscounts)
        if (bill.excludedTotalMinor > 0L) {
            append("\nИсключено: −").append(formatStaffChatMoney(bill.excludedTotalMinor, bill.currency))
        }
        append("\nК оплате: ").append(formatStaffChatMoney(bill.finalPayableTotalMinor, bill.currency))
        append("\n\nПроверьте актуальный счёт перед закрытием.")
    }

private fun staffBillUpdateChangeLabel(change: StaffBillUpdateChange): String =
    when (change) {
        StaffBillUpdateChange.MANUAL_DISCOUNT -> "скидка заведения"
        StaffBillUpdateChange.ITEM_EXCLUDED -> "позиция исключена"
        StaffBillUpdateChange.ITEM_RESTORED -> "позиция восстановлена"
        StaffBillUpdateChange.STATUS_UPDATED -> "статус заказа"
        StaffBillUpdateChange.SHIFT_EXTENSION_REQUESTED -> "запрос на продление работы"
        StaffBillUpdateChange.SHIFT_EXTENSION_APPROVED -> "продление работы подтверждено"
        StaffBillUpdateChange.SHIFT_EXTENSION_REJECTED -> "продление работы отклонено"
    }

private fun staffOrderStatusLabel(status: OrderWorkflowStatus): String =
    when (status) {
        OrderWorkflowStatus.NEW -> "новый"
        OrderWorkflowStatus.ACCEPTED -> "принят"
        OrderWorkflowStatus.COOKING -> "готовится"
        OrderWorkflowStatus.DELIVERING -> "выносится"
        OrderWorkflowStatus.DELIVERED -> "доставлен"
        OrderWorkflowStatus.CLOSED -> "счёт закрыт"
    }

private fun staffOrderBatchLabel(index: Int): String =
    if (index == 0) {
        "Основной заказ"
    } else {
        "Дозаказ №$index"
    }

private fun staffOrderBatchLabelFor(
    batchId: Long,
    batches: List<StaffOrderBatchLiveBlock>,
): String? = batches.firstOrNull { batch -> batch.batchId == batchId }?.label

private fun formatStaffBillActiveItems(
    items: List<OrderBillActiveItemSnapshot>,
    fallbackCurrency: String,
): String =
    items
        .takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { item ->
            buildString {
                append("• ").append(item.displayName()).append(" ×").append(item.qty)
                append(" — ")
                append(formatStaffChatMoney(item.linePayableMinor, item.currency ?: fallbackCurrency))
                item.discountPercent?.let { discountPercent ->
                    append(" (скидка ").append(discountPercent).append("%)")
                }
                item.preferenceNote?.takeIf { it.isNotBlank() }?.let { note ->
                    append('\n').append("  Пожелание: ").append(note)
                }
            }
        }
        ?: "• нет активных позиций"

private fun formatStaffBillExcludedItems(items: List<OrderBillExcludedItemSnapshot>): String =
    items.joinToString("\n") { item ->
        buildString {
            append("• ").append(item.displayName()).append(" ×").append(item.qty)
            append(" — ").append(formatStaffChatMoney(item.lineGrossMinor, item.currency))
            item.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                append("; причина: ").append(reason)
            }
            item.preferenceNote?.takeIf { it.isNotBlank() }?.let { note ->
                append('\n').append("  Пожелание: ").append(note)
            }
        }
    }

private fun OrderBillActiveItemSnapshot.displayName(): String =
    selectedOption?.let { option -> "$name · ${option.name}" } ?: name

private fun OrderBillExcludedItemSnapshot.displayName(): String =
    selectedOption?.let { option -> "$name · ${option.name}" } ?: name

private fun formatStaffBillServiceCharges(
    charges: List<OrderBillServiceChargeSnapshot>,
    fallbackCurrency: String,
): String =
    charges
        .takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { charge ->
            buildString {
                append("• ").append(charge.label).append(" ×").append(charge.qty)
                append(" — ")
                append(
                    formatStaffChatMoney(
                        charge.totalMinor,
                        charge.currency.takeIf { it.isNotBlank() } ?: fallbackCurrency,
                    ),
                )
            }
        }
        ?: "• нет дополнительных начислений"

private fun formatStaffPendingShiftExtension(extension: OrderPendingShiftExtension): String =
    buildString {
        append("⏳ Запрос на продление работы заведения")
        append('\n')
        append("На ")
            .append(formatStaffShiftExtensionDuration(extension.durationMinutes))
            .append(" — ")
            .append(formatStaffChatMoney(extension.priceMinor, extension.currency))
        append('\n')
        append("Гость ожидает подтверждения")
    }

private fun formatStaffShiftExtensionDuration(durationMinutes: Int): String =
    if (durationMinutes % 60 == 0) {
        val hours = durationMinutes / 60
        when {
            hours == 1 -> "1 час"
            hours in 2..4 -> "$hours часа"
            else -> "$hours часов"
        }
    } else {
        "$durationMinutes мин"
    }

private fun StringBuilder.appendStaffBillDiscountLines(
    title: String,
    discounts: List<OrderBillDiscountSnapshot>,
) {
    val lines =
        discounts
            .filter { discount -> discount.discountMinor > 0L && discount.currency.isNotBlank() }
            .map { discount ->
                val label = discount.label.takeIf { it.isNotBlank() } ?: title
                label to "−${formatStaffChatMoney(discount.discountMinor, discount.currency)}"
            }
    if (lines.isEmpty()) return
    append('\n').append(title).append(':')
    lines.forEach { (label, amount) ->
        append("\n• ").append(label).append(": ").append(amount)
    }
}

private fun formatStaffChatOrderItems(itemsSummary: String?): String {
    val items =
        itemsSummary
            ?.lineSequence()
            ?.flatMap { line -> line.splitToSequence(",") }
            ?.map { item -> item.trim() }
            ?.filter { item -> item.isNotBlank() }
            ?.map { item ->
                val normalized = item.replace(Regex("""\s+x(\d+)\b"""), " ×$1")
                if (normalized.startsWith("•")) normalized else "• $normalized"
            }
            ?.toList()
            .orEmpty()
    return items.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "без деталей"
}

private fun formatStaffChatMoney(
    minor: Long,
    currency: String,
): String {
    val normalizedCurrency = currency.uppercase(Locale.ROOT)
    val amount = minor / 100.0
    return when (normalizedCurrency) {
        "RUB" ->
            if (minor % 100L == 0L) {
                "%,d ₽".format(Locale.US, minor / 100L).replace(',', ' ')
            } else {
                String.format(Locale.US, "%.2f ₽", amount)
            }
        else -> String.format(Locale.US, "%.2f %s", amount, normalizedCurrency)
    }
}

internal fun buildStaffBillRequestNotificationText(
    venueName: String,
    tableLabel: String,
    orderDisplayLabel: String?,
    accountLabel: String?,
    billTotalMinor: Long?,
    billCurrency: String?,
    paymentMethod: BillPaymentMethod?,
    statusLine: String? = null,
    guestDisplayName: String? = null,
): String =
    buildString {
        append("🧾 Запрос счёта\n")
        append("Заведение: ").append(venueName).append('\n')
        append("Стол: ").append(tableLabel).append('\n')
        append("Гость: ").append(guestDisplayNameOrFallback(guestDisplayName)).append('\n')
        append("Заказ: ").append(orderDisplayLabel?.takeIf { it.isNotBlank() } ?: "активный заказ").append('\n')
        append("Счёт: ").append(accountLabel?.takeIf { it.isNotBlank() } ?: "активный счёт").append('\n')
        if (billTotalMinor != null && !billCurrency.isNullOrBlank()) {
            append("К оплате: ").append(formatStaffChatMoney(billTotalMinor, billCurrency)).append('\n')
        } else {
            append("К оплате: уточните в Venue Mode").append('\n')
        }
        append("Оплата: ")
            .append(paymentMethod?.let(::billPaymentMethodLabel) ?: "Не указано")
        statusLine?.takeIf { it.isNotBlank() }?.let {
            append("\n\n").append(it)
        }
    }

internal fun buildStaffCallNotificationText(
    venueName: String,
    tableLabel: String,
    reason: StaffCallReason,
    comment: String?,
    type: StaffCallNotificationType = StaffCallNotificationType.NORMAL,
    statusLine: String? = null,
    guestDisplayName: String? = null,
): String =
    when (type) {
        StaffCallNotificationType.RELOCATION ->
            buildString {
                append("🚪 Запрос смены стола\n")
                append(venueName).append(" · Стол ").append(tableLabel).append('\n')
                append("Гость: ").append(guestDisplayNameOrFallback(guestDisplayName)).append('\n')
                append("Гость хочет сменить стол.")
                statusLine?.takeIf { it.isNotBlank() }?.let {
                    append("\n\n").append(it)
                }
            }
        StaffCallNotificationType.NORMAL ->
            buildString {
                append("🛎 Вызов персонала\n")
                append(venueName).append(" · Стол ").append(tableLabel).append('\n')
                append("Гость: ").append(guestDisplayNameOrFallback(guestDisplayName)).append('\n')
                append("Причина: ").append(staffCallReasonLabel(reason))
                staffCallUserFacingComment(comment)?.let {
                    append('\n').append("Комментарий: ").append(it)
                }
                statusLine?.takeIf { it.isNotBlank() }?.let {
                    append("\n\n").append(it)
                }
            }
    }

internal fun staffCallReasonLabel(reason: StaffCallReason): String =
    when (reason) {
        StaffCallReason.COALS -> "Заменить угли"
        StaffCallReason.BILL -> "Принести счёт"
        StaffCallReason.COME -> "Консультация"
        StaffCallReason.OTHER -> "Другое"
    }

internal fun isOrderLinkEligibleStaffCall(
    reason: StaffCallReason,
    comment: String?,
    type: StaffCallNotificationType = StaffCallNotificationType.NORMAL,
): Boolean {
    if (type == StaffCallNotificationType.RELOCATION) return false
    if (reason == StaffCallReason.OTHER) {
        val normalized = comment.orEmpty().lowercase(Locale.ROOT)
        if (
            normalized.contains("смена стола") ||
            normalized.contains("пересадка") ||
            normalized.contains("change table") ||
            normalized.contains("relocation")
        ) {
            return false
        }
    }
    return true
}

private fun staffCallUserFacingComment(comment: String?): String? {
    val clean =
        comment
            ?.replace(Regex("""(?i)\b(tableSessionId|orderId|tabId)\s*=\s*[^.;\s]+[.;]?"""), "")
            ?.replace(Regex("""(?i)\b(table\s*session\s*id|order\s*id|tab\s*id)\s*=?\s*\d+[.;]?"""), "")
            ?.replace(Regex("""\s+([.,;])"""), "$1")
            ?.replace(Regex("""\s{2,}"""), " ")
            ?.trim(' ', '.', ';')
    return clean?.takeIf { it.isNotBlank() }
}

internal fun buildBookingStaffNotificationText(
    venueName: String,
    event: BookingStaffNotificationEvent,
    displayNumber: Int?,
    scheduledAtText: String?,
    partySize: Int?,
    comment: String?,
    cancelReasonText: String? = null,
    actorDisplayName: String? = null,
    guestDisplayName: String? = null,
): String =
    buildString {
        if (event == BookingStaffNotificationEvent.VENUE_CANCELLED) {
            append("❌ Бронь")
            displayNumber?.let { append(" №").append(it) }
            append(" отменена заведением")
            actorDisplayName?.takeIf { it.isNotBlank() }?.let {
                append('\n').append("Отменил: ").append(it)
            }
        } else {
            append(
                when (event) {
                    BookingStaffNotificationEvent.CREATED -> "📅 Новая бронь"
                    BookingStaffNotificationEvent.UPDATED -> "📅 Бронь обновлена"
                    BookingStaffNotificationEvent.CANCELLED -> "📅 Гость отменил бронь"
                    BookingStaffNotificationEvent.VENUE_CANCELLED -> error("handled above")
                },
            )
            displayNumber?.let { append(" №").append(it) }
        }
        append('\n')
        append("Заведение: ").append(venueName)
        append('\n').append("Гость: ").append(guestDisplayNameOrFallback(guestDisplayName))
        scheduledAtText?.takeIf { it.isNotBlank() }?.let {
            append('\n').append("Дата и время: ").append(it)
        }
        partySize?.let { append('\n').append("Гостей: ").append(it) }
        comment?.takeIf { it.isNotBlank() }?.let {
            append('\n').append("Комментарий: ").append(it)
        }
        cancelReasonText?.takeIf { it.isNotBlank() }?.let {
            append('\n').append("Причина: ").append(it)
        }
    }

private fun staffCallNotificationKey(staffCallId: Long): Long = -1_000_000_000_000L - staffCallId

private fun bookingNotificationKey(
    bookingId: Long,
    event: BookingStaffNotificationEvent,
): Long = -2_000_000_000_000L - (bookingId * 10L) - event.dedupeCode

private fun guestDisplayNameOrFallback(value: String?): String = value?.trim()?.takeIf { it.isNotBlank() } ?: "Гость"
