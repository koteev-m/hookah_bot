package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.api.ActiveOrderDto
import com.hookah.platform.backend.miniapp.guest.api.ActiveOrderResponse
import com.hookah.platform.backend.miniapp.guest.api.AddBatchItemDto
import com.hookah.platform.backend.miniapp.guest.api.AddBatchRequest
import com.hookah.platform.backend.miniapp.guest.api.AddBatchResponse
import com.hookah.platform.backend.miniapp.guest.api.OrderBatchDto
import com.hookah.platform.backend.miniapp.guest.api.OrderBatchItemDto
import com.hookah.platform.backend.miniapp.guest.db.GuestMenuRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.telegram.NewBatchNotification
import com.hookah.platform.backend.telegram.StaffChatNotifier
import com.hookah.platform.backend.telegram.TableContext
import com.hookah.platform.backend.telegram.db.OrderBatchItemInput
import com.hookah.platform.backend.telegram.db.OrdersRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val ITEMS_MIN_SIZE = 1
private const val ITEMS_MAX_SIZE = 50
private const val QTY_MIN = 1
private const val QTY_MAX = 50
private const val COMMENT_MAX_LENGTH = 500
private const val IDEMPOTENCY_KEY_MAX_LENGTH = 128

fun Route.guestOrderRoutes(
    guestRateLimitConfig: GuestRateLimitConfig,
    rateLimiter: RateLimiter,
    tableTokenResolver: suspend (String) -> TableContext?,
    guestVenueRepository: GuestVenueRepository,
    guestMenuRepository: GuestMenuRepository,
    subscriptionRepository: SubscriptionRepository,
    ordersRepository: OrdersRepository,
    staffChatNotifier: StaffChatNotifier?,
) {
    get("/order/active") {
        val rawToken = call.request.queryParameters["tableToken"]
        val token = validateTableToken(rawToken)
        val table = tableTokenResolver(token) ?: throw NotFoundException()
        ensureGuestActionAvailable(table.venueId, guestVenueRepository, subscriptionRepository)

        val activeOrder = ordersRepository.findActiveOrderDetails(table.tableId)
        call.respond(
            ActiveOrderResponse(
                order = activeOrder?.toDto(table),
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
            val idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey)
            val normalizedItems = normalizeItems(request.items)
            val comment = normalizeComment(request.comment)
            val table =
                call.rateLimitResolvedTableOrNull(addBatchResolvedTableAttribute)
                    ?: (tableTokenResolver(token) ?: throw NotFoundException())
            val userId = call.requireUserId()
            ensureGuestActionAvailable(table.venueId, guestVenueRepository, subscriptionRepository)

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
                    userId = userId,
                    idempotencyKey = idempotencyKey,
                    comment = comment,
                    items = normalizedItems,
                ) ?: throw NotFoundException()

            if (!batch.idempotencyReplay) {
                notifyStaffChat(
                    notifier = staffChatNotifier,
                    table = table,
                    orderId = batch.orderId,
                    batchId = batch.batchId,
                    comment = comment,
                    items = normalizedItems,
                    guestMenuRepository = guestMenuRepository,
                )
            }

            call.respond(
                AddBatchResponse(
                    orderId = batch.orderId,
                    batchId = batch.batchId,
                ),
            )
        }
    }
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

private fun normalizeItems(items: List<AddBatchItemDto>): List<OrderBatchItemInput> {
    if (items.isEmpty()) {
        throw InvalidInputException("items must not be empty")
    }
    if (items.size > ITEMS_MAX_SIZE) {
        throw InvalidInputException("items size must be <= $ITEMS_MAX_SIZE")
    }
    val grouped = linkedMapOf<Long, Int>()
    items.forEach { item ->
        if (item.itemId <= 0) {
            throw InvalidInputException("itemId must be positive")
        }
        if (item.qty !in QTY_MIN..QTY_MAX) {
            throw InvalidInputException("qty must be between $QTY_MIN and $QTY_MAX")
        }
        grouped[item.itemId] = (grouped[item.itemId] ?: 0) + item.qty
    }
    if (grouped.size < ITEMS_MIN_SIZE || grouped.size > ITEMS_MAX_SIZE) {
        throw InvalidInputException("items size must be between $ITEMS_MIN_SIZE and $ITEMS_MAX_SIZE")
    }
    return grouped.map { (itemId, qty) ->
        if (qty !in QTY_MIN..QTY_MAX) {
            throw InvalidInputException("qty must be between $QTY_MIN and $QTY_MAX")
        }
        OrderBatchItemInput(itemId = itemId, qty = qty)
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

private fun com.hookah.platform.backend.telegram.db.ActiveOrderDetails.toDto(table: TableContext): ActiveOrderDto =
    ActiveOrderDto(
        orderId = orderId,
        venueId = table.venueId,
        tableId = table.tableId,
        tableNumber = table.tableNumber.toString(),
        status = status,
        batches =
            batches.map { batch ->
                OrderBatchDto(
                    batchId = batch.batchId,
                    comment = batch.comment,
                    items =
                        batch.items.map { item ->
                            OrderBatchItemDto(
                                itemId = item.itemId,
                                qty = item.qty,
                            )
                        },
                )
            },
    )

private suspend fun notifyStaffChat(
    notifier: StaffChatNotifier?,
    table: TableContext,
    orderId: Long,
    batchId: Long,
    comment: String?,
    items: List<OrderBatchItemInput>,
    guestMenuRepository: GuestMenuRepository,
) {
    if (notifier == null) {
        return
    }
    val itemIds = items.map { it.itemId }.toSet()
    val itemNames = runCatching { guestMenuRepository.findItemNames(table.venueId, itemIds) }.getOrDefault(emptyMap())
    val summary =
        items.joinToString(separator = ", ") { item ->
            val name = itemNames[item.itemId] ?: "item#${item.itemId}"
            "$name x${item.qty}"
        }
    notifier.notifyNewBatch(
        NewBatchNotification(
            venueId = table.venueId,
            orderId = orderId,
            batchId = batchId,
            tableLabel = table.tableNumber.toString(),
            itemsSummary = summary,
            comment = comment,
        ),
    )
}
