package com.hookah.platform.backend.miniapp.venue.orders

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.venue.VenuePermission
import com.hookah.platform.backend.miniapp.venue.VenuePermissions
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.miniapp.venue.resolveVenueRole
import com.hookah.platform.backend.telegram.StaffBillUpdateChange
import com.hookah.platform.backend.telegram.StaffBillUpdateNotifier
import com.hookah.platform.backend.telegram.StaffBillUpdatedNotification
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val DEFAULT_QUEUE_LIMIT = 20
private const val MAX_QUEUE_LIMIT = 100
private const val DEFAULT_CURRENCY = "RUB"
private val instantFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

fun Route.venueOrderRoutes(
    venueAccessRepository: VenueAccessRepository,
    venueOrdersRepository: VenueOrdersRepository,
    outboxEnqueuer: TelegramOutboxEnqueuer? = null,
    staffBillUpdateNotifier: StaffBillUpdateNotifier? = null,
) {
    route("/venue") {
        get("/orders/queue") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            val permissions = VenuePermissions.forRole(role)
            if (!permissions.contains(VenuePermission.ORDER_QUEUE_VIEW)) {
                throw ForbiddenException()
            }

            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_QUEUE_LIMIT
            if (limit <= 0 || limit > MAX_QUEUE_LIMIT) {
                throw InvalidInputException("limit must be between 1 and $MAX_QUEUE_LIMIT")
            }
            val statusRaw = call.request.queryParameters["status"] ?: "new"
            val batchStatus =
                if (statusRaw.equals("all", ignoreCase = true)) {
                    null
                } else {
                    val status =
                        OrderWorkflowStatus.fromApi(statusRaw)
                            ?: throw InvalidInputException(
                                "status must be one of: all, new, accepted, cooking, delivering, delivered, closed",
                            )
                    OrderBatchStatus.fromWorkflow(status)
                        ?: throw InvalidInputException("queue status is not supported")
                }
            val cursor =
                OrderQueueCursor.parse(call.request.queryParameters["cursor"])
                    ?: if (call.request.queryParameters.contains("cursor")) {
                        throw InvalidInputException(
                            "cursor must be in format <epochSec>:<nano>:<batchId> or <epochMs>:<batchId>",
                        )
                    } else {
                        null
                    }

            val result =
                venueOrdersRepository.listOperationalQueueByOrder(
                    venueId = venueId,
                    status = batchStatus,
                    limit = limit,
                    cursor = cursor,
                )
            call.respond(
                OrdersQueueResponse(
                    items =
                        result.items.map { item -> item.toQueueDto() },
                    nextCursor = result.nextCursor?.encode(),
                ),
            )
        }

        get("/orders/{orderId}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            val permissions = VenuePermissions.forRole(role)
            if (!permissions.contains(VenuePermission.ORDER_QUEUE_VIEW)) {
                throw ForbiddenException()
            }
            val orderId =
                call.parameters["orderId"]?.toLongOrNull()
                    ?: throw InvalidInputException("orderId must be a number")
            val detail =
                venueOrdersRepository.loadOrderDetail(venueId, orderId)
                    ?: throw NotFoundException()
            call.respond(
                OrderDetailResponse(
                    order = detail.toDto(),
                ),
            )
        }

        get("/orders/{orderId}/audit") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            val permissions = VenuePermissions.forRole(role)
            if (!permissions.contains(VenuePermission.ORDER_QUEUE_VIEW)) {
                throw ForbiddenException()
            }
            val orderId =
                call.parameters["orderId"]?.toLongOrNull()
                    ?: throw InvalidInputException("orderId must be a number")
            val audit = venueOrdersRepository.loadAudit(venueId, orderId)
            if (audit.isEmpty()) {
                venueOrdersRepository.loadOrderDetail(venueId, orderId) ?: throw NotFoundException()
                call.respond(OrderAuditResponse(items = emptyList()))
                return@get
            }
            call.respond(
                OrderAuditResponse(
                    items =
                        audit.map { entry ->
                            OrderAuditEntryDto(
                                orderId = entry.orderId,
                                actorUserId = entry.actorUserId,
                                actorRole = entry.actorRole,
                                action = entry.action,
                                fromStatus = entry.fromStatus.toApi(),
                                toStatus = entry.toStatus.toApi(),
                                reasonCode = entry.reasonCode,
                                reasonText = entry.reasonText,
                                createdAt = formatInstant(entry.createdAt),
                            )
                        },
                ),
            )
        }

        post("/orders/{orderId}/status") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            val permissions = VenuePermissions.forRole(role)
            if (!permissions.contains(VenuePermission.ORDER_STATUS_UPDATE)) {
                throw ForbiddenException()
            }
            val orderId =
                call.parameters["orderId"]?.toLongOrNull()
                    ?: throw InvalidInputException("orderId must be a number")
            val request = call.receive<OrderStatusRequest>()
            val nextStatus =
                OrderWorkflowStatus.fromApi(request.nextStatus)
                    ?: throw InvalidInputException(
                        "nextStatus must be one of: new, accepted, cooking, delivering, delivered, closed",
                    )
            if (role == VenueRole.STAFF) {
                val allowedForStaff =
                    setOf(
                        OrderWorkflowStatus.ACCEPTED,
                        OrderWorkflowStatus.COOKING,
                        OrderWorkflowStatus.DELIVERING,
                        OrderWorkflowStatus.DELIVERED,
                        OrderWorkflowStatus.CLOSED,
                    )
                if (!allowedForStaff.contains(nextStatus)) {
                    throw ForbiddenException()
                }
            }
            val detailBeforeUpdate =
                venueOrdersRepository.loadOrderDetail(venueId, orderId)
                    ?: throw NotFoundException()
            val result =
                applyVenueOrderStatusTransition(
                    venueOrdersRepository = venueOrdersRepository,
                    venueId = venueId,
                    orderId = orderId,
                    nextStatus = nextStatus,
                    actor = OrderActionActor(userId, role),
                ) ?: throw NotFoundException()
            if (!result.applied) {
                throw InvalidInputException("Invalid status transition")
            }
            if (nextStatus == OrderWorkflowStatus.CLOSED) {
                notifyGuestsAboutOrderClosed(outboxEnqueuer, detailBeforeUpdate)
            } else {
                notifyGuestAboutOrderStatusChange(outboxEnqueuer, detailBeforeUpdate, nextStatus)
            }
            call.respond(
                OrderStatusResponse(
                    orderId = result.orderId,
                    status = result.status.toApi(),
                    updatedAt = formatInstant(result.updatedAt),
                ),
            )
        }

        post("/orders/{orderId}/reject") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            if (role == VenueRole.STAFF) {
                throw ForbiddenException()
            }
            val orderId =
                call.parameters["orderId"]?.toLongOrNull()
                    ?: throw InvalidInputException("orderId must be a number")
            val request = call.receive<OrderRejectRequest>()
            val reasonCode = request.reasonCode.trim()
            if (reasonCode.isBlank()) {
                throw InvalidInputException("reasonCode must not be blank")
            }
            val reasonText = request.reasonText?.trim()?.takeIf { it.isNotBlank() }
            val result =
                venueOrdersRepository.rejectOrder(
                    venueId = venueId,
                    orderId = orderId,
                    reasonCode = reasonCode,
                    reasonText = reasonText,
                    actor = OrderActionActor(userId, role),
                ) ?: throw NotFoundException()
            if (!result.applied) {
                throw InvalidInputException("Order already closed")
            }
            call.respond(
                OrderStatusResponse(
                    orderId = result.orderId,
                    status = result.status.toApi(),
                    updatedAt = formatInstant(result.updatedAt),
                ),
            )
        }

        post("/orders/{orderId}/close") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            val permissions = VenuePermissions.forRole(role)
            if (!permissions.contains(VenuePermission.ORDER_STATUS_UPDATE)) {
                throw ForbiddenException()
            }
            val orderId =
                call.parameters["orderId"]?.toLongOrNull()
                    ?: throw InvalidInputException("orderId must be a number")
            val detailBeforeUpdate =
                venueOrdersRepository.loadOrderDetail(venueId, orderId)
                    ?: throw NotFoundException()
            val result =
                applyVenueOrderStatusTransition(
                    venueOrdersRepository = venueOrdersRepository,
                    venueId = venueId,
                    orderId = orderId,
                    nextStatus = OrderWorkflowStatus.CLOSED,
                    actor = OrderActionActor(userId, role),
                ) ?: throw NotFoundException()
            if (!result.applied) {
                throw InvalidInputException("Invalid status transition")
            }
            notifyGuestsAboutOrderClosed(outboxEnqueuer, detailBeforeUpdate)
            call.respond(
                OrderStatusResponse(
                    orderId = result.orderId,
                    status = result.status.toApi(),
                    updatedAt = formatInstant(result.updatedAt),
                ),
            )
        }

        post("/orders/{orderId}/items/{batchItemId}/exclude") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            requireBillEditRole(role)
            val orderId =
                call.parameters["orderId"]?.toLongOrNull()
                    ?: throw InvalidInputException("orderId must be a number")
            val batchItemId =
                call.parameters["batchItemId"]?.toLongOrNull()
                    ?: throw InvalidInputException("batchItemId must be a number")
            val request = call.receive<OrderBillItemExcludeRequest>()
            val reasonText = request.reasonText.trim()
            if (reasonText.isBlank()) {
                throw InvalidInputException("reasonText must not be blank")
            }
            val applied =
                venueOrdersRepository.excludeBatchItemFromBill(
                    venueId = venueId,
                    orderId = orderId,
                    batchItemId = batchItemId,
                    reasonText = reasonText.take(200),
                    actor = OrderActionActor(userId, role),
                )
            call.respondBillMutationResult(
                venueOrdersRepository = venueOrdersRepository,
                venueId = venueId,
                orderId = orderId,
                applied = applied,
                staffBillUpdateNotifier = staffBillUpdateNotifier,
                staffBillUpdateChange = StaffBillUpdateChange.ITEM_EXCLUDED,
            )
        }

        post("/orders/{orderId}/items/{batchItemId}/restore") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            requireBillEditRole(role)
            val orderId =
                call.parameters["orderId"]?.toLongOrNull()
                    ?: throw InvalidInputException("orderId must be a number")
            val batchItemId =
                call.parameters["batchItemId"]?.toLongOrNull()
                    ?: throw InvalidInputException("batchItemId must be a number")
            val applied =
                venueOrdersRepository.restoreBatchItemToBill(
                    venueId = venueId,
                    orderId = orderId,
                    batchItemId = batchItemId,
                    actor = OrderActionActor(userId, role),
                )
            call.respondBillMutationResult(
                venueOrdersRepository = venueOrdersRepository,
                venueId = venueId,
                orderId = orderId,
                applied = applied,
                staffBillUpdateNotifier = staffBillUpdateNotifier,
                staffBillUpdateChange = StaffBillUpdateChange.ITEM_RESTORED,
            )
        }

        post("/orders/{orderId}/items/{batchItemId}/discount") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            requireBillEditRole(role)
            val orderId =
                call.parameters["orderId"]?.toLongOrNull()
                    ?: throw InvalidInputException("orderId must be a number")
            val batchItemId =
                call.parameters["batchItemId"]?.toLongOrNull()
                    ?: throw InvalidInputException("batchItemId must be a number")
            val request = call.receive<OrderBillItemDiscountRequest>()
            if (request.discountPercent !in 0..100) {
                throw InvalidInputException("discountPercent must be between 0 and 100")
            }
            val applied =
                venueOrdersRepository.setBatchItemDiscountPercent(
                    venueId = venueId,
                    orderId = orderId,
                    batchItemId = batchItemId,
                    discountPercent = request.discountPercent,
                    actor = OrderActionActor(userId, role),
                )
            call.respondBillMutationResult(
                venueOrdersRepository = venueOrdersRepository,
                venueId = venueId,
                orderId = orderId,
                applied = applied,
                staffBillUpdateNotifier = staffBillUpdateNotifier,
                staffBillUpdateChange = StaffBillUpdateChange.MANUAL_DISCOUNT,
            )
        }
    }
}

private fun OrderQueueItem.toQueueDto(): OrderQueueItemDto =
    OrderQueueItemDto(
        orderId = orderId,
        batchId = batchId,
        displayNumber = displayNumber,
        activeBatchesCount = activeBatchesCount,
        tableNumber = tableNumber.toString(),
        tableLabel = tableNumber.toString(),
        createdAt = formatInstant(createdAt),
        comment = comment,
        itemsCount = itemsCount,
        status = status.toApi(),
    )

private fun requireBillEditRole(role: VenueRole) {
    if (role == VenueRole.STAFF) {
        throw ForbiddenException()
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondBillMutationResult(
    venueOrdersRepository: VenueOrdersRepository,
    venueId: Long,
    orderId: Long,
    applied: Boolean,
    staffBillUpdateNotifier: StaffBillUpdateNotifier?,
    staffBillUpdateChange: StaffBillUpdateChange,
) {
    val detail =
        venueOrdersRepository.loadOrderDetail(venueId, orderId)
            ?: throw NotFoundException()
    if (!applied) {
        throw InvalidInputException("Bill item update was not applied")
    }
    staffBillUpdateNotifier?.notifyBillUpdatedNow(
        StaffBillUpdatedNotification(
            venueId = venueId,
            orderId = orderId,
            displayNumber = detail.displayNumber,
            tableLabel = detail.tableNumber.toString(),
            change = staffBillUpdateChange,
            bill = detail.toOrderBillSnapshot(DEFAULT_CURRENCY),
        ),
    )
    respond(OrderBillItemAdjustmentResponse(order = detail.toDto()))
}

private suspend fun applyVenueOrderStatusTransition(
    venueOrdersRepository: VenueOrdersRepository,
    venueId: Long,
    orderId: Long,
    nextStatus: OrderWorkflowStatus,
    actor: OrderActionActor,
): OrderStatusUpdateResult? {
    if (nextStatus != OrderWorkflowStatus.DELIVERED) {
        return venueOrdersRepository.updateOrderStatus(
            venueId = venueId,
            orderId = orderId,
            nextStatus = nextStatus,
            actor = actor,
        )
    }
    val detail = venueOrdersRepository.loadOrderDetail(venueId = venueId, orderId = orderId) ?: return null
    val transitions =
        when (detail.status) {
            OrderWorkflowStatus.ACCEPTED ->
                listOf(
                    OrderWorkflowStatus.COOKING,
                    OrderWorkflowStatus.DELIVERING,
                    OrderWorkflowStatus.DELIVERED,
                )
            OrderWorkflowStatus.COOKING ->
                listOf(
                    OrderWorkflowStatus.DELIVERING,
                    OrderWorkflowStatus.DELIVERED,
                )
            OrderWorkflowStatus.DELIVERING -> listOf(OrderWorkflowStatus.DELIVERED)
            else -> listOf(OrderWorkflowStatus.DELIVERED)
        }
    var lastResult: OrderStatusUpdateResult? = null
    for (transition in transitions) {
        val stepResult =
            venueOrdersRepository.updateOrderStatus(
                venueId = venueId,
                orderId = orderId,
                nextStatus = transition,
                actor = actor,
            ) ?: return null
        if (!stepResult.applied) {
            return stepResult
        }
        lastResult = stepResult
    }
    return lastResult
}

private suspend fun notifyGuestAboutOrderStatusChange(
    outboxEnqueuer: TelegramOutboxEnqueuer?,
    detail: OrderDetail,
    nextStatus: OrderWorkflowStatus,
) {
    val currentBatch = currentVenueOrderBatch(detail) ?: return
    val guestUserId = currentBatch.authorUserId ?: return
    val message =
        guestOrderStatusNotificationMessage(
            nextStatus = nextStatus,
            isReorder = !isCurrentBatchFirstInOrder(detail, currentBatch),
        ) ?: return
    outboxEnqueuer?.enqueueSendMessage(guestUserId, message)
}

private suspend fun notifyGuestsAboutOrderClosed(
    outboxEnqueuer: TelegramOutboxEnqueuer?,
    detail: OrderDetail,
) {
    val guestUserIds =
        detail.batches
            .asSequence()
            .mapNotNull { batch -> batch.authorUserId }
            .distinct()
            .toList()
    guestUserIds.forEach { guestUserId ->
        outboxEnqueuer?.enqueueSendMessage(
            guestUserId,
            "✅ Счёт закрыт. Спасибо, что были с нами!\nБудем рады видеть вас снова.",
        )
    }
}

private fun guestOrderStatusNotificationMessage(
    nextStatus: OrderWorkflowStatus,
    isReorder: Boolean,
): String? =
    when (nextStatus) {
        OrderWorkflowStatus.ACCEPTED ->
            if (isReorder) {
                "✅ Ваш дозаказ принят."
            } else {
                "✅ Ваш заказ принят."
            }
        OrderWorkflowStatus.DELIVERED ->
            if (isReorder) {
                "✅ Ваш дозаказ доставлен."
            } else {
                "✅ Ваш заказ доставлен."
            }
        else -> null
    }

private fun currentVenueOrderBatch(detail: OrderDetail): OrderBatchDetail? =
    detail.batches
        .asSequence()
        .filter { batch -> batch.status != OrderWorkflowStatus.CLOSED }
        .maxWithOrNull(compareBy({ batch -> batch.createdAt }, { batch -> batch.batchId }))

private fun firstVenueOrderBatch(detail: OrderDetail): OrderBatchDetail? =
    detail.batches
        .asSequence()
        .filter { batch -> batch.status != OrderWorkflowStatus.CLOSED }
        .minWithOrNull(compareBy({ batch -> batch.createdAt }, { batch -> batch.batchId }))

private fun isCurrentBatchFirstInOrder(
    detail: OrderDetail,
    currentBatch: OrderBatchDetail?,
): Boolean {
    if (currentBatch == null) {
        return true
    }
    val firstBatch = firstVenueOrderBatch(detail) ?: return true
    return firstBatch.batchId == currentBatch.batchId
}

private fun OrderDetail.toDto(): OrderDetailDto {
    return OrderDetailDto(
        orderId = orderId,
        displayNumber = displayNumber,
        displayDate = displayDate?.toString(),
        venueId = venueId,
        tableId = tableId,
        tableNumber = tableNumber.toString(),
        tableLabel = tableNumber.toString(),
        status = status.toApi(),
        createdAt = formatInstant(createdAt),
        updatedAt = formatInstant(updatedAt),
        bill = buildBillDto(),
        batches =
            batches.map { batch ->
                OrderBatchDto(
                    batchId = batch.batchId,
                    status = batch.status.toApi(),
                    source = batch.source,
                    comment = batch.comment,
                    createdAt = formatInstant(batch.createdAt),
                    updatedAt = formatInstant(batch.updatedAt),
                    rejectedReasonCode = batch.rejectedReasonCode,
                    rejectedReasonText = batch.rejectedReasonText,
                    promotionDiscounts = batch.promotionDiscounts.map { it.toDto() },
                    items =
                        batch.items.map { item ->
                            item.toDto(batch)
                        },
                )
            },
    )
}

private fun OrderBatchItemDetail.toDto(batch: OrderBatchDetail): OrderBatchItemDto {
    val lineGrossMinor = lineGrossMinor()
    val activePayableItem = !isCancelledBatch(batch) && isActiveBillItem()
    return OrderBatchItemDto(
        batchItemId = batchItemId,
        itemId = itemId,
        name = name,
        qty = qty,
        priceMinor = priceMinor,
        currency = currency,
        lineGrossMinor = lineGrossMinor,
        manualDiscountMinor = if (activePayableItem) manualDiscountMinor() else 0,
        promoDiscountMinor = if (activePayableItem) promoDiscountMinor.coerceAtLeast(0L) else 0,
        linePayableMinor = if (activePayableItem) payableMinor() else 0,
        isExcluded = isExcluded,
        excludedReasonText = excludedReasonText,
        discountPercent = discountPercent?.takeIf { it in 1..100 },
        itemStatus = itemStatus.dbValue.lowercase(),
        canceledReasonCode = canceledReasonCode,
        canceledReasonText = canceledReasonText,
        canceledAt = canceledAt?.let { formatInstant(it) },
        canceledByUserId = canceledByUserId,
    )
}

private fun OrderDetail.buildBillDto(): OrderBillDto {
    val snapshot = toOrderBillSnapshot(DEFAULT_CURRENCY)
    return OrderBillDto(
        grossTotalMinor = snapshot.grossTotalMinor,
        manualDiscountTotalMinor = snapshot.manualDiscountTotalMinor,
        promoDiscountTotalMinor = snapshot.promoDiscountTotalMinor,
        loyaltyDiscountTotalMinor = snapshot.loyaltyDiscountTotalMinor,
        excludedTotalMinor = snapshot.excludedTotalMinor,
        canceledTotalMinor = snapshot.canceledTotalMinor,
        rejectedTotalMinor = snapshot.rejectedTotalMinor,
        finalPayableTotalMinor = snapshot.finalPayableTotalMinor,
        currency = snapshot.currency,
        promoDiscounts = snapshot.promoDiscounts.map { it.toDto() },
        loyaltyDiscounts = snapshot.loyaltyDiscounts.map { it.toDto() },
        excludedItems = snapshot.excludedItems.map { it.toDto() },
    )
}

private fun OrderBatchItemDetail.lineGrossMinor(): Long = priceMinor?.let { it * qty } ?: 0L

private fun OrderBatchItemDetail.manualDiscountMinor(): Long =
    discountPercent?.takeIf { it in 1..100 }?.let { lineGrossMinor() * it / 100 } ?: 0L

private fun OrderBatchItemDetail.payableMinor(): Long =
    (lineGrossMinor() - manualDiscountMinor() - promoDiscountMinor.coerceAtLeast(0L)).coerceAtLeast(0L)

private fun OrderBatchItemDetail.isActiveBillItem(): Boolean = !isExcluded && itemStatus == OrderBatchItemStatus.ACTIVE

private fun isCancelledBatch(batch: OrderBatchDetail): Boolean =
    batch.status == OrderWorkflowStatus.CLOSED ||
        !batch.rejectedReasonCode.isNullOrBlank() ||
        !batch.rejectedReasonText.isNullOrBlank()

private fun OrderPromotionDiscount.toDto(): OrderBillDiscountDto =
    OrderBillDiscountDto(
        label = label,
        discountMinor = discountMinor,
        currency = currency,
        ruleType = ruleType,
    )

private fun OrderBillDiscountSnapshot.toDto(): OrderBillDiscountDto =
    OrderBillDiscountDto(
        label = label,
        discountMinor = discountMinor,
        currency = currency,
        ruleType = ruleType,
    )

private fun OrderBillExcludedItemSnapshot.toDto(): OrderBillExcludedItemDto =
    OrderBillExcludedItemDto(
        batchId = batchId,
        batchLabel = batchLabel,
        batchItemId = batchItemId,
        itemId = itemId,
        name = name,
        qty = qty,
        lineGrossMinor = lineGrossMinor,
        currency = currency,
        status = status,
        reason = reason,
    )

private fun formatInstant(value: java.time.Instant): String {
    return instantFormatter.format(value.atOffset(ZoneOffset.UTC))
}
