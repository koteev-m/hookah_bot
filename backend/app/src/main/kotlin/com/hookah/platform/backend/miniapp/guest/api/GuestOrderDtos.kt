package com.hookah.platform.backend.miniapp.guest.api

import kotlinx.serialization.Serializable

@Serializable
data class ActiveOrderResponse(
    val order: ActiveOrderDto?,
)

@Serializable
data class ActiveOrderDto(
    val orderId: Long,
    val venueId: Long,
    val tableId: Long,
    val tableNumber: String,
    val status: String,
    val batches: List<OrderBatchDto>,
)

@Serializable
data class OrderBatchDto(
    val batchId: Long,
    val comment: String?,
    val items: List<OrderBatchItemDto>,
)

@Serializable
data class OrderBatchItemDto(
    val itemId: Long,
    val qty: Int,
)

@Serializable
data class AddBatchRequest(
    val tableToken: String,
    val idempotencyKey: String,
    val items: List<AddBatchItemDto>,
    val comment: String? = null,
)

@Serializable
data class AddBatchItemDto(
    val itemId: Long,
    val qty: Int,
)

@Serializable
data class AddBatchResponse(
    val orderId: Long,
    val batchId: Long,
)
