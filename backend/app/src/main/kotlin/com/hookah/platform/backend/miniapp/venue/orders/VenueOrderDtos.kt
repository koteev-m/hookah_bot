package com.hookah.platform.backend.miniapp.venue.orders

import kotlinx.serialization.Serializable

@Serializable
data class OrdersQueueResponse(
    val items: List<OrderQueueItemDto>,
    val nextCursor: String?,
)

@Serializable
data class OrderQueueItemDto(
    val orderId: Long,
    val batchId: Long,
    val displayNumber: Int? = null,
    val activeBatchesCount: Int = 1,
    val tableNumber: String,
    val tableLabel: String,
    val createdAt: String,
    val comment: String?,
    val itemsCount: Int,
    val status: String,
)

@Serializable
data class OrderDetailResponse(
    val order: OrderDetailDto,
)

@Serializable
data class OrderDetailDto(
    val orderId: Long,
    val displayNumber: Int? = null,
    val displayDate: String? = null,
    val venueId: Long,
    val tableId: Long,
    val tableNumber: String,
    val tableLabel: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val bill: OrderBillDto,
    val batches: List<OrderBatchDto>,
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
    val promotionDiscounts: List<OrderBillDiscountDto>,
    val items: List<OrderBatchItemDto>,
)

@Serializable
data class OrderBatchItemDto(
    val batchItemId: Long,
    val itemId: Long,
    val name: String,
    val qty: Int,
    val priceMinor: Long? = null,
    val currency: String? = null,
    val lineGrossMinor: Long,
    val manualDiscountMinor: Long,
    val promoDiscountMinor: Long,
    val linePayableMinor: Long,
    val isExcluded: Boolean,
    val excludedReasonText: String? = null,
    val discountPercent: Int? = null,
    val itemStatus: String,
    val canceledReasonCode: String? = null,
    val canceledReasonText: String? = null,
    val canceledAt: String? = null,
    val canceledByUserId: Long? = null,
)

@Serializable
data class OrderBillDto(
    val grossTotalMinor: Long,
    val manualDiscountTotalMinor: Long,
    val promoDiscountTotalMinor: Long,
    val loyaltyDiscountTotalMinor: Long,
    val excludedTotalMinor: Long,
    val canceledTotalMinor: Long,
    val rejectedTotalMinor: Long,
    val finalPayableTotalMinor: Long,
    val currency: String,
    val promoDiscounts: List<OrderBillDiscountDto>,
    val loyaltyDiscounts: List<OrderBillDiscountDto>,
    val excludedItems: List<OrderBillExcludedItemDto>,
)

@Serializable
data class OrderBillDiscountDto(
    val label: String,
    val discountMinor: Long,
    val currency: String,
    val ruleType: String? = null,
)

@Serializable
data class OrderBillExcludedItemDto(
    val batchId: Long,
    val batchLabel: String,
    val batchItemId: Long,
    val itemId: Long,
    val name: String,
    val qty: Int,
    val lineGrossMinor: Long,
    val currency: String,
    val status: String,
    val reason: String? = null,
)

@Serializable
data class OrderStatusRequest(
    val nextStatus: String,
)

@Serializable
data class OrderStatusResponse(
    val orderId: Long,
    val status: String,
    val updatedAt: String,
)

@Serializable
data class OrderRejectRequest(
    val reasonCode: String,
    val reasonText: String? = null,
)

@Serializable
data class OrderBillItemExcludeRequest(
    val reasonText: String,
)

@Serializable
data class OrderBillItemDiscountRequest(
    val discountPercent: Int,
)

@Serializable
data class OrderBillItemAdjustmentResponse(
    val order: OrderDetailDto,
)

@Serializable
data class OrderAuditResponse(
    val items: List<OrderAuditEntryDto>,
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
    val createdAt: String,
)
