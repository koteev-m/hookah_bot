package com.hookah.platform.backend.miniapp.venue.orders

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.STAFF_CALL_AUDIT_SOURCE_VENUE_MINIAPP
import com.hookah.platform.backend.miniapp.venue.VenuePermission
import com.hookah.platform.backend.miniapp.venue.VenuePermissions
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.appendStaffCallStatusAuditBestEffort
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.miniapp.venue.resolveVenueRole
import com.hookah.platform.backend.telegram.StaffBillUpdateChange
import com.hookah.platform.backend.telegram.StaffBillUpdateNotifier
import com.hookah.platform.backend.telegram.StaffBillUpdatedNotification
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import com.hookah.platform.backend.telegram.toStaffOrderBatchLiveBlocks
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val DEFAULT_QUEUE_LIMIT = 20
private const val MAX_QUEUE_LIMIT = 100
private const val DEFAULT_CURRENCY = "RUB"
private val instantFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
private val venueOrderRoutesLogger = LoggerFactory.getLogger("VenueOrderRoutes")

fun Route.venueOrderRoutes(
    venueAccessRepository: VenueAccessRepository,
    venueOrdersRepository: VenueOrdersRepository,
    outboxEnqueuer: TelegramOutboxEnqueuer? = null,
    staffBillUpdateNotifier: StaffBillUpdateNotifier? = null,
    staffCallRepository: StaffCallRepository? = null,
    auditLogRepository: AuditLogRepository? = null,
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
                        result.items.map { item ->
                            item.toQueueDto(
                                includePendingShiftExtension =
                                    permissions.contains(VenuePermission.SHIFT_EXTENSION_VIEW),
                            )
                        },
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
                    order =
                        detail.toDto(
                            includePendingShiftExtension =
                                permissions.contains(VenuePermission.SHIFT_EXTENSION_VIEW),
                        ),
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
                completeBillRequestsForClosedOrder(
                    staffCallRepository = staffCallRepository,
                    auditLogRepository = auditLogRepository,
                    venueId = venueId,
                    orderId = orderId,
                    actorUserId = userId,
                )
                notifyGuestsAboutOrderClosed(outboxEnqueuer, detailBeforeUpdate)
            } else {
                notifyGuestAboutOrderStatusChange(outboxEnqueuer, detailBeforeUpdate, nextStatus)
            }
            notifyStaffChatOrderLiveMessage(
                staffBillUpdateNotifier = staffBillUpdateNotifier,
                venueOrdersRepository = venueOrdersRepository,
                venueId = venueId,
                orderId = orderId,
                change = StaffBillUpdateChange.STATUS_UPDATED,
            )
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
            completeBillRequestsForClosedOrder(
                staffCallRepository = staffCallRepository,
                auditLogRepository = auditLogRepository,
                venueId = venueId,
                orderId = orderId,
                actorUserId = userId,
            )
            notifyGuestsAboutOrderClosed(outboxEnqueuer, detailBeforeUpdate)
            notifyStaffChatOrderLiveMessage(
                staffBillUpdateNotifier = staffBillUpdateNotifier,
                venueOrdersRepository = venueOrdersRepository,
                venueId = venueId,
                orderId = orderId,
                change = StaffBillUpdateChange.STATUS_UPDATED,
            )
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

private fun OrderQueueItem.toQueueDto(includePendingShiftExtension: Boolean = true): OrderQueueItemDto =
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
        pendingShiftExtension = pendingShiftExtension.takeIf { includePendingShiftExtension }?.toDto(),
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
            batches = detail.toStaffOrderBatchLiveBlocks(),
            status = detail.status,
            actionBatchId = staffChatActionBatchId(detail),
            pendingShiftExtension = detail.pendingShiftExtension,
            updatedAt = detail.updatedAt,
        ),
    )
    respond(OrderBillItemAdjustmentResponse(order = detail.toDto()))
}

private suspend fun notifyStaffChatOrderLiveMessage(
    staffBillUpdateNotifier: StaffBillUpdateNotifier?,
    venueOrdersRepository: VenueOrdersRepository,
    venueId: Long,
    orderId: Long,
    change: StaffBillUpdateChange,
) {
    val notifier = staffBillUpdateNotifier ?: return
    val detail = venueOrdersRepository.loadOrderDetail(venueId, orderId) ?: return
    notifier.notifyBillUpdatedNow(
        StaffBillUpdatedNotification(
            venueId = venueId,
            orderId = orderId,
            displayNumber = detail.displayNumber,
            tableLabel = detail.tableNumber.toString(),
            change = change,
            bill = detail.toOrderBillSnapshot(DEFAULT_CURRENCY),
            batches = detail.toStaffOrderBatchLiveBlocks(),
            status = detail.status,
            actionBatchId = staffChatActionBatchId(detail),
            pendingShiftExtension = detail.pendingShiftExtension,
            updatedAt = detail.updatedAt,
        ),
    )
}

private fun staffChatActionBatchId(detail: OrderDetail): Long =
    when (detail.status) {
        OrderWorkflowStatus.NEW ->
            detail.batches.lastOrNull { batch -> batch.status == OrderWorkflowStatus.NEW }
        OrderWorkflowStatus.ACCEPTED,
        OrderWorkflowStatus.COOKING,
        OrderWorkflowStatus.DELIVERING,
        ->
            detail.batches.lastOrNull { batch ->
                batch.status == OrderWorkflowStatus.ACCEPTED ||
                    batch.status == OrderWorkflowStatus.COOKING ||
                    batch.status == OrderWorkflowStatus.DELIVERING
            }
        OrderWorkflowStatus.DELIVERED ->
            detail.batches.lastOrNull { batch -> batch.status == OrderWorkflowStatus.DELIVERED }
        OrderWorkflowStatus.CLOSED ->
            detail.batches.lastOrNull()
    }?.batchId ?: detail.batches.lastOrNull()?.batchId ?: detail.orderId

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

private fun OrderDetail.toDto(includePendingShiftExtension: Boolean = true): OrderDetailDto {
    val tabLabels = tabDisplayLabelsById(batches)
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
                    tabId = batch.tabId,
                    tabType = batch.tabType,
                    tabDisplayLabel = batch.tabId?.let { tabLabels[it] } ?: batch.fallbackTabDisplayLabel(),
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
        pendingShiftExtension = pendingShiftExtension.takeIf { includePendingShiftExtension }?.toDto(),
    )
}

private fun OrderPendingShiftExtension.toDto(): OrderPendingShiftExtensionDto =
    OrderPendingShiftExtensionDto(
        requestId = requestId,
        orderId = orderId,
        tableSessionId = tableSessionId,
        tabId = tabId,
        tableId = tableId,
        tableNumber = tableNumber.toString(),
        tableLabel = tableNumber.toString(),
        durationMinutes = durationMinutes,
        priceMinor = priceMinor,
        currency = currency,
        requestedAt = formatInstant(requestedAt),
        status = status,
    )

private fun OrderBatchItemDetail.toDto(batch: OrderBatchDetail): OrderBatchItemDto {
    val lineGrossMinor = lineGrossMinor()
    val activePayableItem = !isCancelledBatch(batch) && isActiveBillItem()
    return OrderBatchItemDto(
        batchItemId = batchItemId,
        itemId = itemId,
        name = name,
        qty = qty,
        selectedOption = selectedOption?.toDto(),
        preferenceNote = preferenceNote,
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
    val tabLabels = tabDisplayLabelsById(batches)
    val tabsByBatchId = batches.associateBy { it.batchId }
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
        excludedItems =
            snapshot.excludedItems.map { item ->
                val batch = tabsByBatchId[item.batchId]
                item.toDto(
                    batch = batch,
                    tabDisplayLabel = batch?.tabId?.let { tabLabels[it] },
                )
            },
        serviceCharges =
            snapshot.serviceCharges.map { charge ->
                OrderBillServiceChargeDto(
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

private fun OrderBillExcludedItemSnapshot.toDto(
    batch: OrderBatchDetail?,
    tabDisplayLabel: String?,
): OrderBillExcludedItemDto =
    OrderBillExcludedItemDto(
        batchId = batchId,
        batchLabel = batchLabel,
        tabId = batch?.tabId,
        tabType = batch?.tabType,
        tabDisplayLabel = tabDisplayLabel ?: batch?.fallbackTabDisplayLabel(),
        batchItemId = batchItemId,
        itemId = itemId,
        name = name,
        qty = qty,
        selectedOption = selectedOption?.toDto(),
        preferenceNote = preferenceNote,
        lineGrossMinor = lineGrossMinor,
        currency = currency,
        status = status,
        reason = reason,
    )

private fun tabDisplayLabelsById(batches: List<OrderBatchDetail>): Map<Long, String> {
    val sharedTabIds =
        batches
            .asSequence()
            .filter { batch -> batch.tabType.equals("SHARED", ignoreCase = true) }
            .mapNotNull { it.tabId }
            .distinct()
            .toList()
    return batches
        .asSequence()
        .mapNotNull { batch ->
            val tabId = batch.tabId ?: return@mapNotNull null
            val label =
                when (batch.tabType?.uppercase()) {
                    "PERSONAL" -> "Личный счёт гостя"
                    "SHARED" ->
                        if (sharedTabIds.size <= 1) {
                            "Общий счёт"
                        } else {
                            "Общий счёт ${sharedTabIds.indexOf(tabId) + 1}"
                        }
                    else -> return@mapNotNull null
                }
            tabId to label
        }
        .toMap()
}

private fun OrderBatchDetail.fallbackTabDisplayLabel(): String? =
    when (tabType?.uppercase()) {
        "PERSONAL" -> "Личный счёт гостя"
        "SHARED" -> "Общий счёт"
        else -> null
    }

private fun OrderBatchItemSelectedOption.toDto(): OrderItemSelectedOptionDto =
    OrderItemSelectedOptionDto(
        optionId = optionId,
        name = name,
        priceDeltaMinor = priceDeltaMinor,
    )

private fun OrderBillSelectedOptionSnapshot.toDto(): OrderItemSelectedOptionDto =
    OrderItemSelectedOptionDto(
        optionId = optionId,
        name = name,
        priceDeltaMinor = priceDeltaMinor,
    )

private suspend fun completeBillRequestsForClosedOrder(
    staffCallRepository: StaffCallRepository?,
    auditLogRepository: AuditLogRepository?,
    venueId: Long,
    orderId: Long,
    actorUserId: Long,
) {
    val repository = staffCallRepository ?: return
    val completed =
        runCatching {
            repository.completeActiveBillRequestsForOrder(
                venueId = venueId,
                orderId = orderId,
            )
        }.onFailure { error ->
            venueOrderRoutesLogger.warn(
                "Failed to complete bill requests for closed order venue_id={} order_id={}: {}",
                venueId,
                orderId,
                sanitizeTelegramForLog(error.message),
            )
        }.getOrDefault(emptyList())
    val auditRepository = auditLogRepository ?: return
    completed.forEach { completion ->
        appendStaffCallStatusAuditBestEffort(
            auditLogRepository = auditRepository,
            actorUserId = actorUserId,
            venueId = venueId,
            source = STAFF_CALL_AUDIT_SOURCE_VENUE_MINIAPP,
            fromStatus = completion.fromStatus,
            result = completion.result,
            logger = venueOrderRoutesLogger,
        )
    }
}

private fun formatInstant(value: java.time.Instant): String {
    return instantFormatter.format(value.atOffset(ZoneOffset.UTC))
}
