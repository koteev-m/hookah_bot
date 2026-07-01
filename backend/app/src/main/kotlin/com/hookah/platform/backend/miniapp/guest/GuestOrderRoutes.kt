package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.api.ActiveOrderDto
import com.hookah.platform.backend.miniapp.guest.api.ActiveOrderResponse
import com.hookah.platform.backend.miniapp.guest.api.ActiveOrderServiceChargeDto
import com.hookah.platform.backend.miniapp.guest.api.AddBatchItemDto
import com.hookah.platform.backend.miniapp.guest.api.AddBatchRequest
import com.hookah.platform.backend.miniapp.guest.api.AddBatchResponse
import com.hookah.platform.backend.miniapp.guest.api.CartPreviewDiscountDto
import com.hookah.platform.backend.miniapp.guest.api.CartPreviewDto
import com.hookah.platform.backend.miniapp.guest.api.CartPreviewItemDto
import com.hookah.platform.backend.miniapp.guest.api.CartPreviewRequest
import com.hookah.platform.backend.miniapp.guest.api.CartPreviewResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestBillRequestRequest
import com.hookah.platform.backend.miniapp.guest.api.GuestBillRequestResponse
import com.hookah.platform.backend.miniapp.guest.api.OrderBatchDto
import com.hookah.platform.backend.miniapp.guest.api.OrderBatchItemDto
import com.hookah.platform.backend.miniapp.guest.api.SelectedOrderItemOptionDto
import com.hookah.platform.backend.miniapp.guest.db.GuestMenuRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTabModel
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionStatus
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.miniapp.venue.orders.toOrderBillSnapshot
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.telegram.BillPaymentMethod
import com.hookah.platform.backend.telegram.NewBatchNotification
import com.hookah.platform.backend.telegram.NewBatchPromotionDiscount
import com.hookah.platform.backend.telegram.StaffBillRequestNotification
import com.hookah.platform.backend.telegram.StaffChatNotifier
import com.hookah.platform.backend.telegram.TableContext
import com.hookah.platform.backend.telegram.billPaymentMethodLabel
import com.hookah.platform.backend.telegram.db.CreatedOrderBatch
import com.hookah.platform.backend.telegram.db.CreatedOrderPromotionDiscount
import com.hookah.platform.backend.telegram.db.GuestOrderCartPreview
import com.hookah.platform.backend.telegram.db.OrderBatchItemInput
import com.hookah.platform.backend.telegram.db.OrderItemSelectedOptionDetails
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.StaffCallStatus
import com.hookah.platform.backend.telegram.db.UserRepository
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import com.hookah.platform.backend.telegram.toStaffOrderBatchLiveBlocks
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant
import java.util.Locale

private const val ITEMS_MIN_SIZE = 1
private const val ITEMS_MAX_SIZE = 50
private const val QTY_MIN = 1
private const val QTY_MAX = 50
private const val COMMENT_MAX_LENGTH = 500
private const val ITEM_PREFERENCE_NOTE_MAX_LENGTH = 200
private const val IDEMPOTENCY_KEY_MAX_LENGTH = 128
private const val DEFAULT_CURRENCY = "RUB"

fun Route.guestOrderRoutes(
    guestRateLimitConfig: GuestRateLimitConfig,
    rateLimiter: RateLimiter,
    tableTokenResolver: suspend (String) -> TableContext?,
    guestVenueRepository: GuestVenueRepository,
    guestMenuRepository: GuestMenuRepository,
    subscriptionRepository: SubscriptionRepository,
    ordersRepository: OrdersRepository,
    staffCallRepository: StaffCallRepository,
    tableSessionRepository: TableSessionRepository,
    tableSessionConfig: TableSessionConfig,
    guestTabsRepository: GuestTabsRepository,
    staffChatNotifier: StaffChatNotifier?,
    userRepository: UserRepository = UserRepository(null),
    venueSettingsRepository: VenueSettingsRepository = VenueSettingsRepository(null),
    venueOrdersRepository: VenueOrdersRepository = VenueOrdersRepository(null),
) {
    get("/order/active") {
        val rawToken = call.request.queryParameters["tableToken"]
        val token = validateTableToken(rawToken)
        val table = tableTokenResolver(token) ?: throw NotFoundException()
        ensureGuestActionAvailable(table.venueId, guestVenueRepository, subscriptionRepository)
        val userId = call.requireUserId()
        val tableSessionId = parseOptionalPositiveLong(call.request.queryParameters["tableSessionId"], "tableSessionId")
        val tabId = parseOptionalPositiveLong(call.request.queryParameters["tabId"], "tabId")

        val scopedActiveOrder =
            if (tableSessionId != null || tabId != null) {
                if (tableSessionId == null || tabId == null) {
                    throw InvalidInputException("tableSessionId and tabId must be provided together")
                }
                val tableSession =
                    tableSessionRepository.touchActiveSession(
                        tableSessionId = tableSessionId,
                        venueId = table.venueId,
                        tableId = table.tableId,
                        ttl = tableSessionConfig.ttl,
                    ) ?: throw NotFoundException()
                val member =
                    guestTabsRepository.isTabMember(
                        tabId = tabId,
                        venueId = table.venueId,
                        tableSessionId = tableSession.id,
                        userId = userId,
                    )
                if (!member) {
                    throw ForbiddenException("Tab access denied")
                }
                tableSession.id to (tabId to ordersRepository.findActiveOrderDetailsForTab(tableSession.id, tabId))
            } else {
                val tableSession =
                    tableSessionRepository.resolveActiveSession(
                        venueId = table.venueId,
                        tableId = table.tableId,
                        ttl = tableSessionConfig.ttl,
                    )
                val personalTab =
                    guestTabsRepository.ensurePersonalTab(
                        venueId = table.venueId,
                        tableSessionId = tableSession.id,
                        userId = userId,
                    )
                val activeOrderDetails =
                    ordersRepository.findActiveOrderDetailsForTab(
                        tableSession.id,
                        personalTab.id,
                    )
                tableSession.id to (personalTab.id to activeOrderDetails)
            }
        val activeOrder = scopedActiveOrder.second.second
        call.respond(
            ActiveOrderResponse(
                order =
                    activeOrder?.toDto(
                        table = table,
                        tableSessionId = scopedActiveOrder.first,
                        tabId = scopedActiveOrder.second.first,
                    ),
            ),
        )
    }

    post("/order/preview") {
        val request = call.receive<CartPreviewRequest>()
        val token = validateTableToken(request.tableToken)
        val tabId = normalizeTabId(request.tabId)
        val normalizedItems = normalizeItems(request.items)
        val table = tableTokenResolver(token) ?: throw NotFoundException()
        val tableSession =
            tableSessionRepository.findSessionForTable(
                tableSessionId = request.tableSessionId,
                venueId = table.venueId,
                tableId = table.tableId,
            ) ?: throw NotFoundException()
        if (
            tableSession.status != TableSessionStatus.ACTIVE ||
            tableSession.endedAt != null ||
            !tableSession.expiresAt.isAfter(Instant.now())
        ) {
            throw NotFoundException()
        }
        val userId = call.requireUserId()
        ensureGuestActionAvailable(table.venueId, guestVenueRepository, subscriptionRepository)
        val member =
            guestTabsRepository.isTabMember(
                tabId = tabId,
                venueId = table.venueId,
                tableSessionId = tableSession.id,
                userId = userId,
            )
        if (!member) {
            throw ForbiddenException("Tab access denied")
        }
        val itemIds = normalizedItems.map { it.itemId }.toSet()
        val availableItems =
            guestMenuRepository.findAvailableItemIds(
                venueId = table.venueId,
                itemIds = itemIds,
            )
        if (availableItems.size != itemIds.size) {
            throw InvalidInputException("Some items are unavailable")
        }
        val preview =
            ordersRepository.previewGuestOrderBatch(
                venueId = table.venueId,
                userId = userId,
                items = normalizedItems,
                venueZoneId = venueSettingsRepository.resolveZoneId(table.venueId),
            ) ?: throw NotFoundException()
        call.respond(CartPreviewResponse(preview = preview.toDto()))
    }

    post("/order/bill-request") {
        val request = call.receive<GuestBillRequestRequest>()
        val token = validateTableToken(request.tableToken)
        val tabId = normalizeTabId(request.tabId)
        val paymentMethod = normalizeBillPaymentMethod(request.paymentMethod)
        val table = tableTokenResolver(token) ?: throw NotFoundException()
        val tableSession =
            tableSessionRepository.touchActiveSession(
                tableSessionId = request.tableSessionId,
                venueId = table.venueId,
                tableId = table.tableId,
                ttl = tableSessionConfig.ttl,
            ) ?: throw NotFoundException()
        val userId = call.requireUserId()
        ensureGuestActionAvailable(table.venueId, guestVenueRepository, subscriptionRepository)
        val member =
            guestTabsRepository.isTabMember(
                tabId = tabId,
                venueId = table.venueId,
                tableSessionId = tableSession.id,
                userId = userId,
            )
        if (!member) {
            throw ForbiddenException("Tab access denied")
        }
        val activeOrder =
            ordersRepository.findActiveOrderDetailsForTab(
                tableSessionId = tableSession.id,
                tabId = tabId,
            ) ?: throw NotFoundException()
        val activeOrderDto =
            activeOrder.toDto(
                table = table,
                tableSessionId = tableSession.id,
                tabId = tabId,
            )
        val tabs =
            guestTabsRepository.listTabsForUser(
                venueId = table.venueId,
                tableSessionId = tableSession.id,
                userId = userId,
            )
        val accountLabel = guestBillRequestAccountLabel(tabId = tabId, tabs = tabs)
        val created =
            staffCallRepository.createGuestBillRequest(
                venueId = table.venueId,
                tableId = table.tableId,
                tableSessionId = tableSession.id,
                tabId = tabId,
                orderId = activeOrder.orderId,
                createdByUserId = userId,
                paymentMethod = paymentMethod,
            )
        if (!created.alreadyActive) {
            staffChatNotifier?.notifyBillRequestNow(
                StaffBillRequestNotification(
                    venueId = table.venueId,
                    staffCallId = created.id,
                    tableLabel = table.tableNumber.toString(),
                    orderId = activeOrder.orderId,
                    orderDisplayLabel = orderDisplayLabel(activeOrder.displayNumber, activeOrder.orderId),
                    accountLabel = accountLabel,
                    billTotalMinor = activeOrderDto.finalPayableTotalMinor,
                    billCurrency = activeOrderDto.currency,
                    paymentMethod = created.paymentMethod,
                    guestDisplayName =
                        runCatching { userRepository.findGuestProfile(userId)?.guestDisplayName }
                            .getOrNull(),
                ),
            )
        }
        val message =
            if (created.alreadyActive) {
                "Запрос на счёт уже отправлен. Персонал скоро подойдёт."
            } else {
                "Персонал получил запрос на счёт."
            }
        call.respond(
            GuestBillRequestResponse(
                staffCallId = created.id,
                createdAtEpochSeconds = created.createdAt.epochSecond,
                status = created.status.dbValue,
                statusLabel = guestBillRequestStatusLabel(created.status),
                paymentMethod = created.paymentMethod.name,
                paymentMethodLabel = billPaymentMethodLabel(created.paymentMethod),
                alreadyActive = created.alreadyActive,
                message = message,
            ),
        )
    }

    route("/order/add-batch") {
        installGuestAddBatchRateLimit(
            endpoint = "guest.order.add-batch",
            policy = guestRateLimitConfig.addBatch,
            rateLimiter = rateLimiter,
            tableTokenResolver = tableTokenResolver,
            resolvedTableAttribute = addBatchResolvedTableAttribute,
        )

        post {
            val request = call.receive<AddBatchRequest>()
            val token = validateTableToken(request.tableToken)
            val tabId = normalizeTabId(request.tabId)
            val idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey)
            val normalizedItems = normalizeItems(request.items)
            val comment = normalizeComment(request.comment)
            val table =
                call.rateLimitResolvedTableOrNull(addBatchResolvedTableAttribute)
                    ?: (tableTokenResolver(token) ?: throw NotFoundException())
            val tableSession =
                tableSessionRepository.touchActiveSession(
                    tableSessionId = request.tableSessionId,
                    venueId = table.venueId,
                    tableId = table.tableId,
                    ttl = tableSessionConfig.ttl,
                ) ?: throw NotFoundException()
            val userId = call.requireUserId()
            ensureGuestActionAvailable(table.venueId, guestVenueRepository, subscriptionRepository)
            guestTabsRepository.ensurePersonalTab(
                venueId = table.venueId,
                tableSessionId = tableSession.id,
                userId = userId,
            )
            val member =
                guestTabsRepository.isTabMember(
                    tabId = tabId,
                    venueId = table.venueId,
                    tableSessionId = tableSession.id,
                    userId = userId,
                )
            if (!member) {
                throw ForbiddenException("Tab access denied")
            }

            val itemIds = normalizedItems.map { it.itemId }.toSet()
            val availableItems =
                guestMenuRepository.findAvailableItemIds(
                    venueId = table.venueId,
                    itemIds = itemIds,
                )
            if (availableItems.size != itemIds.size) {
                throw InvalidInputException("Some items are unavailable")
            }

            val batch =
                ordersRepository.createGuestOrderBatch(
                    tableId = table.tableId,
                    venueId = table.venueId,
                    tableSessionId = tableSession.id,
                    userId = userId,
                    idempotencyKey = idempotencyKey,
                    tabId = tabId,
                    comment = comment,
                    items = normalizedItems,
                    venueZoneId = venueSettingsRepository.resolveZoneId(table.venueId),
                ) ?: throw NotFoundException()

            notifyStaffChat(
                notifier = staffChatNotifier,
                table = table,
                batch = batch,
                comment = comment,
                items = normalizedItems,
                guestMenuRepository = guestMenuRepository,
                userRepository = userRepository,
                userId = userId,
                venueOrdersRepository = venueOrdersRepository,
            )

            call.respond(
                AddBatchResponse(
                    orderId = batch.orderId,
                    batchId = batch.batchId,
                ),
            )
        }
    }
}

private fun normalizeTabId(tabId: Long): Long {
    if (tabId <= 0) {
        throw InvalidInputException("tabId must be positive")
    }
    return tabId
}

private fun parseOptionalPositiveLong(
    raw: String?,
    fieldName: String,
): Long? {
    if (raw.isNullOrBlank()) {
        return null
    }
    val parsed = raw.toLongOrNull() ?: throw InvalidInputException("$fieldName must be positive")
    if (parsed <= 0) {
        throw InvalidInputException("$fieldName must be positive")
    }
    return parsed
}

private fun normalizeBillPaymentMethod(raw: String): BillPaymentMethod {
    val normalized = raw.trim().uppercase(Locale.ROOT)
    if (normalized.isBlank()) {
        throw InvalidInputException("paymentMethod is required")
    }
    return runCatching { BillPaymentMethod.valueOf(normalized) }
        .getOrElse {
            throw InvalidInputException(
                "paymentMethod must be one of ${BillPaymentMethod.values().joinToString { it.name }}",
            )
        }
}

private fun guestBillRequestAccountLabel(
    tabId: Long,
    tabs: List<GuestTabModel>,
): String {
    val tab = tabs.firstOrNull { it.id == tabId }
    return when (tab?.type?.uppercase(Locale.ROOT)) {
        "PERSONAL" -> "Личный счёт"
        "SHARED" -> {
            val sharedTabs = tabs.filter { it.type.equals("SHARED", ignoreCase = true) && it.status == "ACTIVE" }
            if (sharedTabs.size <= 1) {
                "Общий счёт"
            } else {
                val index = sharedTabs.indexOfFirst { it.id == tabId }
                if (index >= 0) "Общий счёт ${index + 1}" else "Общий счёт"
            }
        }
        else -> "Счёт #$tabId"
    }
}

private fun orderDisplayLabel(
    displayNumber: Int?,
    orderId: Long,
): String = displayNumber?.let { "Заказ №$it" } ?: "Заказ #$orderId"

private fun guestBillRequestStatusLabel(status: StaffCallStatus): String =
    when (status) {
        StaffCallStatus.NEW -> "Запрос на счёт отправлен"
        StaffCallStatus.ACK -> "Персонал принял запрос"
        StaffCallStatus.DONE -> "Запрос на счёт закрыт"
        StaffCallStatus.CANCELLED -> "Запрос на счёт отменён"
    }

private fun normalizeIdempotencyKey(idempotencyKey: String): String {
    val normalized = idempotencyKey.trim()
    if (normalized.isEmpty()) {
        throw InvalidInputException("idempotencyKey must not be blank")
    }
    if (normalized.length > IDEMPOTENCY_KEY_MAX_LENGTH) {
        throw InvalidInputException("idempotencyKey length must be <= $IDEMPOTENCY_KEY_MAX_LENGTH")
    }
    return normalized
}

private data class NormalizedItemKey(
    val itemId: Long,
    val selectedOptionId: Long?,
    val preferenceNote: String?,
)

private fun normalizeItems(items: List<AddBatchItemDto>): List<OrderBatchItemInput> {
    if (items.isEmpty()) {
        throw InvalidInputException("items must not be empty")
    }
    if (items.size > ITEMS_MAX_SIZE) {
        throw InvalidInputException("items size must be <= $ITEMS_MAX_SIZE")
    }
    val grouped = linkedMapOf<NormalizedItemKey, Int>()
    items.forEach { item ->
        if (item.itemId <= 0) {
            throw InvalidInputException("itemId must be positive")
        }
        item.selectedOptionId?.let { selectedOptionId ->
            if (selectedOptionId <= 0) {
                throw InvalidInputException("selectedOptionId must be positive")
            }
        }
        if (item.qty !in QTY_MIN..QTY_MAX) {
            throw InvalidInputException("qty must be between $QTY_MIN and $QTY_MAX")
        }
        val preferenceNote = normalizeItemPreferenceNote(item.preferenceNote)
        val key = NormalizedItemKey(item.itemId, item.selectedOptionId, preferenceNote)
        grouped[key] = (grouped[key] ?: 0) + item.qty
    }
    if (grouped.size < ITEMS_MIN_SIZE || grouped.size > ITEMS_MAX_SIZE) {
        throw InvalidInputException("items size must be between $ITEMS_MIN_SIZE and $ITEMS_MAX_SIZE")
    }
    return grouped.map { (key, qty) ->
        if (qty !in QTY_MIN..QTY_MAX) {
            throw InvalidInputException("qty must be between $QTY_MIN and $QTY_MAX")
        }
        OrderBatchItemInput(
            itemId = key.itemId,
            qty = qty,
            selectedOptionId = key.selectedOptionId,
            preferenceNote = key.preferenceNote,
        )
    }
}

private fun normalizeComment(comment: String?): String? {
    val trimmed = comment?.trim().orEmpty()
    if (trimmed.isEmpty()) {
        return null
    }
    if (trimmed.length > COMMENT_MAX_LENGTH) {
        throw InvalidInputException("comment length must be <= $COMMENT_MAX_LENGTH")
    }
    return trimmed
}

private fun normalizeItemPreferenceNote(preferenceNote: String?): String? {
    val trimmed = preferenceNote?.trim().orEmpty()
    if (trimmed.isEmpty()) {
        return null
    }
    if (trimmed.length > ITEM_PREFERENCE_NOTE_MAX_LENGTH) {
        throw InvalidInputException("preferenceNote length must be <= $ITEM_PREFERENCE_NOTE_MAX_LENGTH")
    }
    return trimmed
}

private fun com.hookah.platform.backend.telegram.db.ActiveOrderDetails.toDto(
    table: TableContext,
    tableSessionId: Long,
    tabId: Long,
): ActiveOrderDto {
    val allItems = batches.flatMap { it.items }
    val currency =
        allItems.firstOrNull { !it.currency.isNullOrBlank() }?.currency
            ?: promotionDiscounts.firstOrNull { it.currency.isNotBlank() }?.currency
            ?: serviceCharges.firstOrNull { it.currency.isNotBlank() }?.currency
            ?: DEFAULT_CURRENCY
    val manualDiscountTotal = allItems.sumOf { item -> item.manualDiscountMinor() }
    val promoDiscounts = promotionDiscounts.filterNot { it.isLoyaltyDiscount() }
    val loyaltyDiscounts = promotionDiscounts.filter { it.isLoyaltyDiscount() }
    val serviceChargeTotal = serviceCharges.sumOf { charge -> charge.totalMinor }
    return ActiveOrderDto(
        orderId = orderId,
        displayNumber = displayNumber,
        displayDate = displayDate?.toString(),
        venueId = table.venueId,
        tableId = table.tableId,
        tableSessionId = tableSessionId,
        tabId = tabId,
        tableNumber = table.tableNumber.toString(),
        status = status,
        grossTotalMinor = allItems.sumOf { item -> item.lineGrossMinor() } + serviceChargeTotal,
        manualDiscountTotalMinor = manualDiscountTotal,
        promoDiscountTotalMinor = promoDiscounts.sumOf { it.discountMinor },
        loyaltyDiscountTotalMinor = loyaltyDiscounts.sumOf { it.discountMinor },
        finalPayableTotalMinor = allItems.sumOf { item -> item.linePayableMinor() } + serviceChargeTotal,
        currency = currency,
        discounts =
            promotionDiscounts.map { discount ->
                com.hookah.platform.backend.miniapp.guest.api.ActiveOrderDiscountDto(
                    label = discount.label,
                    discountMinor = discount.discountMinor,
                    currency = discount.currency,
                    ruleType = discount.ruleType,
                )
            },
        serviceCharges =
            serviceCharges.map { charge ->
                ActiveOrderServiceChargeDto(
                    id = charge.id,
                    source = charge.source,
                    sourceRequestId = charge.sourceRequestId,
                    label = charge.label,
                    qty = charge.qty,
                    unitPriceMinor = charge.unitPriceMinor,
                    totalMinor = charge.totalMinor,
                    currency = charge.currency,
                )
            },
        batches =
            batches.map { batch ->
                OrderBatchDto(
                    batchId = batch.batchId,
                    status = batch.status,
                    comment = batch.comment,
                    items =
                        batch.items.map { item ->
                            OrderBatchItemDto(
                                itemId = item.itemId,
                                qty = item.qty,
                                name = item.itemName,
                                selectedOption = item.selectedOption?.toDto(),
                                preferenceNote = item.preferenceNote,
                                priceMinor = item.priceMinor,
                                currency = item.currency,
                                lineGrossMinor = item.lineGrossMinor(),
                                manualDiscountMinor = item.manualDiscountMinor(),
                                promoDiscountMinor = item.promoDiscountMinor.coerceAtLeast(0L),
                                linePayableMinor = item.linePayableMinor(),
                                isPromotionReward = item.isPromotionReward,
                            )
                        },
                )
            },
    )
}

private fun com.hookah.platform.backend.telegram.db.CreatedOrderPromotionDiscount.isLoyaltyDiscount(): Boolean =
    ruleType.equals("LOYALTY_NTH_HOOKAH", ignoreCase = true) ||
        label.contains("Лояльность", ignoreCase = true)

private fun GuestOrderCartPreview.toDto(): CartPreviewDto =
    CartPreviewDto(
        grossTotalMinor = grossTotalMinor,
        promoDiscountTotalMinor = promoDiscountTotalMinor,
        loyaltyDiscountTotalMinor = loyaltyDiscountTotalMinor,
        finalPayableTotalMinor = finalPayableTotalMinor,
        currency = currency,
        discounts = discounts.map { it.toPreviewDto() },
        items =
            items.map { item ->
                CartPreviewItemDto(
                    itemId = item.itemId,
                    name = item.itemName,
                    qty = item.qty,
                    selectedOption = item.selectedOption?.toDto(),
                    preferenceNote = item.preferenceNote,
                    priceMinor = item.priceMinor,
                    currency = item.currency,
                    lineGrossMinor = item.lineGrossMinor,
                    discountMinor = item.discountMinor,
                    linePayableMinor = item.linePayableMinor,
                    isPromotionReward = item.isPromotionReward,
                )
            },
    )

private fun CreatedOrderPromotionDiscount.toPreviewDto(): CartPreviewDiscountDto =
    CartPreviewDiscountDto(
        label = label,
        discountMinor = discountMinor,
        currency = currency,
        ruleType = ruleType,
    )

private fun com.hookah.platform.backend.telegram.db.OrderBatchItemDetails.lineGrossMinor(): Long =
    priceMinor?.let { it * qty } ?: 0L

private fun com.hookah.platform.backend.telegram.db.OrderBatchItemDetails.manualDiscountMinor(): Long =
    discountPercent?.takeIf { it in 1..100 }?.let { lineGrossMinor() * it / 100 } ?: 0L

private fun com.hookah.platform.backend.telegram.db.OrderBatchItemDetails.linePayableMinor(): Long =
    (lineGrossMinor() - manualDiscountMinor() - promoDiscountMinor.coerceAtLeast(0L)).coerceAtLeast(0L)

private fun OrderItemSelectedOptionDetails.toDto(): SelectedOrderItemOptionDto =
    SelectedOrderItemOptionDto(
        optionId = optionId,
        name = name,
        priceDeltaMinor = priceDeltaMinor,
    )

private suspend fun notifyStaffChat(
    notifier: StaffChatNotifier?,
    table: TableContext,
    batch: CreatedOrderBatch,
    comment: String?,
    items: List<OrderBatchItemInput>,
    guestMenuRepository: GuestMenuRepository,
    userRepository: UserRepository,
    userId: Long,
    venueOrdersRepository: VenueOrdersRepository,
) {
    if (notifier == null) {
        return
    }
    val summary = staffChatCreatedBatchItemsSummary(batch, items, table.venueId, guestMenuRepository)
    val totalCurrency = batch.items.firstOrNull()?.currency
    val totalPayableMinor =
        batch.items
            .takeIf { it.isNotEmpty() }
            ?.sumOf { item -> item.priceMinor * item.qty }
            ?.let { gross -> gross - batch.promotionDiscounts.sumOf { it.discountMinor } }
            ?.coerceAtLeast(0L)
    val detail = runCatching { venueOrdersRepository.loadOrderDetail(table.venueId, batch.orderId) }.getOrNull()
    notifier.notifyNewBatchNow(
        NewBatchNotification(
            venueId = table.venueId,
            orderId = batch.orderId,
            batchId = batch.batchId,
            tableLabel = table.tableNumber.toString(),
            itemsSummary = summary,
            comment = comment,
            displayNumber = batch.displayNumber,
            isFirstBatch = batch.isFirstBatch,
            guestDisplayName =
                runCatching { userRepository.findGuestProfile(userId)?.guestDisplayName }
                    .getOrNull(),
            promotionDiscounts =
                batch.promotionDiscounts.map { discount ->
                    NewBatchPromotionDiscount(
                        label = discount.label,
                        discountMinor = discount.discountMinor,
                        currency = discount.currency,
                        ruleType = discount.ruleType,
                    )
                },
            totalPayableMinor = totalPayableMinor,
            totalCurrency = totalCurrency,
            status = detail?.status,
            bill = detail?.toOrderBillSnapshot(DEFAULT_CURRENCY),
            batches = detail?.toStaffOrderBatchLiveBlocks().orEmpty(),
            updatedAt = detail?.updatedAt,
        ),
    )
}

private suspend fun staffChatCreatedBatchItemsSummary(
    batch: CreatedOrderBatch,
    fallbackItems: List<OrderBatchItemInput>,
    venueId: Long,
    guestMenuRepository: GuestMenuRepository,
): String {
    if (batch.items.isNotEmpty()) {
        return batch.items.joinToString(separator = ", ") { item ->
            val itemName = item.selectedOption?.let { option -> "${item.itemName} · ${option.name}" } ?: item.itemName
            val note = item.preferenceNote?.takeIf { it.isNotBlank() }?.let { "; пожелание: $it" }.orEmpty()
            "$itemName x${item.qty}$note — ${formatStaffSummaryMoney(item.priceMinor * item.qty, item.currency)}"
        }
    }
    val itemIds = fallbackItems.map { it.itemId }.toSet()
    val itemNames = runCatching { guestMenuRepository.findItemNames(venueId, itemIds) }.getOrDefault(emptyMap())
    return fallbackItems.joinToString(separator = ", ") { item ->
        val name = itemNames[item.itemId] ?: "item#${item.itemId}"
        "$name x${item.qty}"
    }
}

private fun formatStaffSummaryMoney(
    minor: Long,
    currency: String,
): String {
    val normalizedCurrency = currency.uppercase(Locale.ROOT)
    return if (normalizedCurrency == "RUB" && minor % 100L == 0L) {
        "%,d ₽".format(Locale.US, minor / 100L).replace(',', ' ')
    } else {
        val amount = minor / 100.0
        when (normalizedCurrency) {
            "RUB" -> String.format(Locale.US, "%.2f ₽", amount)
            else -> String.format(Locale.US, "%.2f %s", amount, normalizedCurrency)
        }
    }
}
