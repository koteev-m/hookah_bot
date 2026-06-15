package com.hookah.platform.backend.miniapp.guest.api

import kotlinx.serialization.Serializable

@Serializable
data class ActiveOrderResponse(
    val order: ActiveOrderDto?,
)

@Serializable
data class ActiveOrderDto(
    val orderId: Long,
    val displayNumber: Int? = null,
    val displayDate: String? = null,
    val venueId: Long,
    val tableId: Long,
    val tableSessionId: Long? = null,
    val tabId: Long? = null,
    val tableNumber: String,
    val status: String,
    val grossTotalMinor: Long,
    val manualDiscountTotalMinor: Long,
    val promoDiscountTotalMinor: Long,
    val loyaltyDiscountTotalMinor: Long,
    val finalPayableTotalMinor: Long,
    val currency: String,
    val discounts: List<ActiveOrderDiscountDto>,
    val serviceCharges: List<ActiveOrderServiceChargeDto> = emptyList(),
    val batches: List<OrderBatchDto>,
)

@Serializable
data class ActiveOrderDiscountDto(
    val label: String,
    val discountMinor: Long,
    val currency: String,
    val ruleType: String? = null,
)

@Serializable
data class ActiveOrderServiceChargeDto(
    val id: Long,
    val source: String,
    val sourceRequestId: Long? = null,
    val label: String,
    val qty: Int,
    val unitPriceMinor: Long,
    val totalMinor: Long,
    val currency: String,
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
    val name: String? = null,
    val selectedOption: SelectedOrderItemOptionDto? = null,
    val preferenceNote: String? = null,
    val priceMinor: Long? = null,
    val currency: String? = null,
    val lineGrossMinor: Long = 0,
    val manualDiscountMinor: Long = 0,
    val promoDiscountMinor: Long = 0,
    val linePayableMinor: Long = 0,
    val isPromotionReward: Boolean = false,
)

@Serializable
data class SelectedOrderItemOptionDto(
    val optionId: Long? = null,
    val name: String,
    val priceDeltaMinor: Long,
)

@Serializable
data class AddBatchRequest(
    val tableToken: String,
    val tableSessionId: Long,
    val tabId: Long,
    val idempotencyKey: String,
    val items: List<AddBatchItemDto>,
    val comment: String? = null,
)

@Serializable
data class AddBatchItemDto(
    val itemId: Long,
    val qty: Int,
    val selectedOptionId: Long? = null,
    val preferenceNote: String? = null,
)

@Serializable
data class AddBatchResponse(
    val orderId: Long,
    val batchId: Long,
)

@Serializable
data class CartPreviewRequest(
    val tableToken: String,
    val tableSessionId: Long,
    val tabId: Long,
    val items: List<AddBatchItemDto>,
)

@Serializable
data class CartPreviewResponse(
    val preview: CartPreviewDto,
)

@Serializable
data class CartPreviewDto(
    val grossTotalMinor: Long,
    val promoDiscountTotalMinor: Long,
    val loyaltyDiscountTotalMinor: Long,
    val finalPayableTotalMinor: Long,
    val currency: String,
    val discounts: List<CartPreviewDiscountDto>,
    val items: List<CartPreviewItemDto>,
)

@Serializable
data class CartPreviewDiscountDto(
    val label: String,
    val discountMinor: Long,
    val currency: String,
    val ruleType: String? = null,
)

@Serializable
data class CartPreviewItemDto(
    val itemId: Long,
    val name: String,
    val qty: Int,
    val selectedOption: SelectedOrderItemOptionDto? = null,
    val preferenceNote: String? = null,
    val priceMinor: Long,
    val currency: String,
    val lineGrossMinor: Long,
    val discountMinor: Long,
    val linePayableMinor: Long,
    val isPromotionReward: Boolean = false,
)
