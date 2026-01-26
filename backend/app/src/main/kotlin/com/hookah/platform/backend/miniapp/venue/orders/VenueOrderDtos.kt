package com.hookah.platform.backend.miniapp.venue.orders

import kotlinx.serialization.Serializable

@Serializable
data class OrdersQueueResponse(
    val items: List<OrderQueueItemDto>,
    val nextCursor: String?
)

@Serializable
data class OrderQueueItemDto(
    val orderId: Long,
    val batchId: Long,
    val tableNumber: String,
    val tableLabel: String,
    val createdAt: String,
    val comment: String?,
    val itemsCount: Int,
    val status: String
)

@Serializable
data class OrderDetailResponse(
    val order: OrderDetailDto
)

@Serializable
data class OrderDetailDto(
    val orderId: Long,
    val venueId: Long,
    val tableId: Long,
    val tableNumber: String,
    val tableLabel: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val batches: List<OrderBatchDto>
)

@Serializable
data class OrderBatchDto(
    val batchId: Long,
    val status: String,
    val source: String,
    val comment: String?,
    val createdAt: String,
    val updatedAt: String,
    val rejectedReasonCode: String? = null,
    val rejectedReasonText: String? = null,
    val items: List<OrderBatchItemDto>
)

@Serializable
data class OrderBatchItemDto(
    val itemId: Long,
    val name: String,
    val qty: Int
)

@Serializable
data class OrderStatusRequest(
    val nextStatus: String
)

@Serializable
data class OrderStatusResponse(
    val orderId: Long,
    val status: String,
    val updatedAt: String
)

@Serializable
data class OrderRejectRequest(
    val reasonCode: String,
    val reasonText: String? = null
)

@Serializable
data class OrderAuditResponse(
    val items: List<OrderAuditEntryDto>
)

@Serializable
data class OrderAuditEntryDto(
    val orderId: Long,
    val actorUserId: Long,
    val actorRole: String,
    val action: String,
    val fromStatus: String,
    val toStatus: String,
    val reasonCode: String? = null,
    val reasonText: String? = null,
    val createdAt: String
)
