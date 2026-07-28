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
    val status: String,
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
    val previewFingerprint: String? = null,
    val giftDecision: GiftDecisionDto? = null,
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
    val pricing: CartPreviewDto,
    val recalculated: Boolean,
    val submitted: Boolean = true,
)

@Serializable
data class AddBatchRecalculationResponse(
    val submitted: Boolean,
    val pricing: CartPreviewDto,
    val recalculated: Boolean = true,
)

@Serializable
data class GuestBillRequestRequest(
    val tableToken: String,
    val tableSessionId: Long,
    val tabId: Long,
    val paymentMethod: String,
)

@Serializable
data class GuestBillRequestResponse(
    val staffCallId: Long,
    val createdAtEpochSeconds: Long,
    val status: String,
    val statusLabel: String,
    val paymentMethod: String,
    val paymentMethodLabel: String,
    val alreadyActive: Boolean,
    val message: String,
)

@Serializable
data class CartPreviewRequest(
    val tableToken: String,
    val tableSessionId: Long,
    val tabId: Long,
    val items: List<AddBatchItemDto>,
    val comment: String? = null,
    val giftDecision: GiftDecisionDto? = null,
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
    val pricingFingerprint: String,
    val giftOffer: GiftOfferDto,
    val cartFingerprint: String = "",
    val decisionScopeToken: String? = null,
    val decisionScopeExpiresAtEpochSeconds: Long? = null,
    val giftDecisionStale: Boolean = false,
    val giftDecisionMessage: String? = null,
)

@Serializable
data class GiftDecisionDto(
    val action: String,
    val selectedMenuItemId: Long? = null,
    val decisionScopeToken: String? = null,
    @Deprecated("Promotion identity is server-owned and ignored")
    val promotionId: Long? = null,
    @Deprecated("Promotion identity is server-owned and ignored")
    val ruleId: Long? = null,
    @Deprecated("Promotion identity is server-owned and ignored")
    val ruleVersion: Int? = null,
)

@Serializable
data class GiftRewardItemDto(
    val menuItemId: Long,
    val name: String,
    val originalUnitPriceMinor: Long,
    val currency: String,
)

@Serializable
data class GiftOfferDto(
    val status: String,
    val promotionId: Long? = null,
    val promotionTitle: String? = null,
    val ruleId: Long? = null,
    val ruleVersion: Int? = null,
    val triggerLineId: Long? = null,
    val triggerMenuItemId: Long? = null,
    val triggerItemName: String? = null,
    val fixedRewardItem: GiftRewardItemDto? = null,
    val selectableRewardItems: List<GiftRewardItemDto> = emptyList(),
    val selectedRewardItem: GiftRewardItemDto? = null,
    val unavailableReason: String? = null,
)

@Serializable
data class CartPreviewDiscountDto(
    val label: String,
    val discountMinor: Long,
    val currency: String,
    val ruleType: String? = null,
    val promotionId: Long? = null,
    val ruleId: Long? = null,
    val ruleVersion: Int? = null,
    val originalAmountMinor: Long? = null,
    val finalAmountMinor: Long? = null,
    val eligibleLineIds: List<Long> = emptyList(),
)

@Serializable
data class CartPreviewPromotionAdjustmentDto(
    val promotionId: Long? = null,
    val promotionTitle: String,
    val ruleId: Long,
    val ruleVersion: Int,
    val ruleType: String,
    val originalAmountMinor: Long,
    val discountMinor: Long,
    val finalAmountMinor: Long,
    val currency: String,
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
    val baseUnitPriceMinor: Long,
    val selectedOptionDeltaMinor: Long,
    val promotionAdjustment: CartPreviewPromotionAdjustmentDto? = null,
)
