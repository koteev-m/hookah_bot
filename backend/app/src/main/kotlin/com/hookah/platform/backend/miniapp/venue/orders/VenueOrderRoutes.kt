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
private val instantFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

fun Route.venueOrderRoutes(
    venueAccessRepository: VenueAccessRepository,
    venueOrdersRepository: VenueOrdersRepository
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
            val status = OrderWorkflowStatus.fromApi(statusRaw)
                ?: throw InvalidInputException("status must be one of: new, accepted, cooking, delivering, delivered, closed")
            val batchStatus = OrderBatchStatus.fromWorkflow(status)
                ?: throw InvalidInputException("queue status is not supported")
            val cursor = OrderQueueCursor.parse(call.request.queryParameters["cursor"])
                ?: if (call.request.queryParameters.contains("cursor")) {
                    throw InvalidInputException("cursor must be in format <epochSec>:<nano>:<batchId> or <epochMs>:<batchId>")
                } else {
                    null
                }

            val result = venueOrdersRepository.listQueue(
                venueId = venueId,
                status = batchStatus,
                limit = limit,
                cursor = cursor
            )
            call.respond(
                OrdersQueueResponse(
                    items = result.items.map { item ->
                        OrderQueueItemDto(
                            orderId = item.orderId,
                            batchId = item.batchId,
                            tableNumber = item.tableNumber.toString(),
                            tableLabel = item.tableNumber.toString(),
                            createdAt = formatInstant(item.createdAt),
                            comment = item.comment,
                            itemsCount = item.itemsCount,
                            status = item.status.toApi()
                        )
                    },
                    nextCursor = result.nextCursor?.encode()
                )
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
            val orderId = call.parameters["orderId"]?.toLongOrNull()
                ?: throw InvalidInputException("orderId must be a number")
            val detail = venueOrdersRepository.loadOrderDetail(venueId, orderId)
                ?: throw NotFoundException()
            call.respond(
                OrderDetailResponse(
                    order = detail.toDto()
                )
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
            val orderId = call.parameters["orderId"]?.toLongOrNull()
                ?: throw InvalidInputException("orderId must be a number")
            val audit = venueOrdersRepository.loadAudit(venueId, orderId)
            if (audit.isEmpty()) {
                venueOrdersRepository.loadOrderDetail(venueId, orderId) ?: throw NotFoundException()
                call.respond(OrderAuditResponse(items = emptyList()))
                return@get
            }
            call.respond(
                OrderAuditResponse(
                    items = audit.map { entry ->
                        OrderAuditEntryDto(
                            orderId = entry.orderId,
                            actorUserId = entry.actorUserId,
                            actorRole = entry.actorRole,
                            action = entry.action,
                            fromStatus = entry.fromStatus.toApi(),
                            toStatus = entry.toStatus.toApi(),
                            reasonCode = entry.reasonCode,
                            reasonText = entry.reasonText,
                            createdAt = formatInstant(entry.createdAt)
                        )
                    }
                )
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
            val orderId = call.parameters["orderId"]?.toLongOrNull()
                ?: throw InvalidInputException("orderId must be a number")
            val request = call.receive<OrderStatusRequest>()
            val nextStatus = OrderWorkflowStatus.fromApi(request.nextStatus)
                ?: throw InvalidInputException("nextStatus must be one of: new, accepted, cooking, delivering, delivered, closed")
            if (role == VenueRole.STAFF) {
                val allowedForStaff = setOf(
                    OrderWorkflowStatus.ACCEPTED,
                    OrderWorkflowStatus.COOKING,
                    OrderWorkflowStatus.DELIVERING,
                    OrderWorkflowStatus.DELIVERED
                )
                if (!allowedForStaff.contains(nextStatus)) {
                    throw ForbiddenException()
                }
            }
            val result = venueOrdersRepository.updateOrderStatus(
                venueId = venueId,
                orderId = orderId,
                nextStatus = nextStatus,
                actor = OrderActionActor(userId, role)
            ) ?: throw NotFoundException()
            if (!result.applied) {
                throw InvalidInputException("Invalid status transition")
            }
            call.respond(
                OrderStatusResponse(
                    orderId = result.orderId,
                    status = result.status.toApi(),
                    updatedAt = formatInstant(result.updatedAt)
                )
            )
        }

        post("/orders/{orderId}/reject") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            if (role == VenueRole.STAFF) {
                throw ForbiddenException()
            }
            val orderId = call.parameters["orderId"]?.toLongOrNull()
                ?: throw InvalidInputException("orderId must be a number")
            val request = call.receive<OrderRejectRequest>()
            val reasonCode = request.reasonCode.trim()
            if (reasonCode.isBlank()) {
                throw InvalidInputException("reasonCode must not be blank")
            }
            val reasonText = request.reasonText?.trim()?.takeIf { it.isNotBlank() }
            val result = venueOrdersRepository.rejectOrder(
                venueId = venueId,
                orderId = orderId,
                reasonCode = reasonCode,
                reasonText = reasonText,
                actor = OrderActionActor(userId, role)
            ) ?: throw NotFoundException()
            if (!result.applied) {
                throw InvalidInputException("Order already closed")
            }
            call.respond(
                OrderStatusResponse(
                    orderId = result.orderId,
                    status = result.status.toApi(),
                    updatedAt = formatInstant(result.updatedAt)
                )
            )
        }
    }
}

private fun OrderDetail.toDto(): OrderDetailDto {
    return OrderDetailDto(
        orderId = orderId,
        venueId = venueId,
        tableId = tableId,
        tableNumber = tableNumber.toString(),
        tableLabel = tableNumber.toString(),
        status = status.toApi(),
        createdAt = formatInstant(createdAt),
        updatedAt = formatInstant(updatedAt),
        batches = batches.map { batch ->
            OrderBatchDto(
                batchId = batch.batchId,
                status = batch.status.toApi(),
                source = batch.source,
                comment = batch.comment,
                createdAt = formatInstant(batch.createdAt),
                updatedAt = formatInstant(batch.updatedAt),
                rejectedReasonCode = batch.rejectedReasonCode,
                rejectedReasonText = batch.rejectedReasonText,
                items = batch.items.map { item ->
                    OrderBatchItemDto(
                        itemId = item.itemId,
                        name = item.name,
                        qty = item.qty
                    )
                }
            )
        }
    )
}

private fun formatInstant(value: java.time.Instant): String {
    return instantFormatter.format(value.atOffset(ZoneOffset.UTC))
}
