package com.hookah.platform.backend.miniapp.guest.api

import kotlinx.serialization.Serializable

@Serializable
data class GuestVisitListResponse(
    val items: List<GuestVisitListItemDto>,
)

@Serializable
data class GuestVisitListItemDto(
    val visitId: Long,
    val venueId: Long,
    val venueName: String,
    val venueCity: String? = null,
    val occurredAt: String,
    val serviceDate: String? = null,
    val source: String,
    val totalMinor: Long? = null,
    val currency: String? = null,
    val hasBooking: Boolean,
    val orderLabels: List<String>,
)

@Serializable
data class GuestVisitDetailResponse(
    val visit: GuestVisitDetailDto,
)

@Serializable
data class GuestVisitDetailDto(
    val visitId: Long,
    val venueId: Long,
    val venueName: String,
    val venueCity: String? = null,
    val occurredAt: String,
    val serviceDate: String? = null,
    val source: String,
    val booking: GuestVisitBookingDto? = null,
    val orders: List<GuestVisitOrderDto>,
    val totalMinor: Long? = null,
    val currency: String? = null,
    val feedback: GuestVisitFeedbackDto? = null,
)

@Serializable
data class GuestVisitFeedbackDto(
    val eligible: Boolean,
    val submitted: Boolean,
    val rating: Int? = null,
    val tags: List<String> = emptyList(),
    val comment: String? = null,
    val publicReviewUrl: String? = null,
)

@Serializable
data class GuestVisitFeedbackSubmitRequest(
    val rating: Int,
    val tags: List<String> = emptyList(),
    val comment: String? = null,
)

@Serializable
data class GuestVisitFeedbackSubmitResponse(
    val feedback: GuestVisitFeedbackDto,
)

@Serializable
data class GuestVisitBookingDto(
    val bookingId: Long,
    val displayNumber: Int? = null,
    val partySize: Int? = null,
    val status: String,
)

@Serializable
data class GuestVisitOrderDto(
    val orderId: Long,
    val displayNumber: Int? = null,
    val displayDate: String? = null,
    val items: List<GuestVisitOrderItemDto>,
    val totalMinor: Long? = null,
    val currency: String? = null,
    val promotionDiscounts: List<GuestVisitPromotionDiscountDto>,
)

@Serializable
data class GuestVisitPromotionDiscountDto(
    val label: String,
    val discountMinor: Long,
    val currency: String,
    val ruleType: String? = null,
)

@Serializable
data class GuestVisitOrderItemOptionDto(
    val name: String,
    val priceDeltaMinor: Long,
)

@Serializable
data class GuestVisitOrderItemDto(
    val itemId: Long,
    val itemName: String,
    val qty: Int,
    val selectedOption: GuestVisitOrderItemOptionDto? = null,
    val preferenceNote: String? = null,
    val priceMinor: Long? = null,
    val currency: String? = null,
    val discountPercent: Int? = null,
    val totalMinor: Long? = null,
)

@Serializable
data class GuestVisitRepeatPlanRequest(
    val tableSessionId: Long,
    val tabId: Long,
    val orderId: Long? = null,
)

@Serializable
data class GuestVisitRepeatPlanResponse(
    val eligibleLines: List<GuestVisitRepeatEligibleLineDto>,
    val skippedLines: List<GuestVisitRepeatSkippedLineDto>,
    val currentTotal: GuestVisitRepeatMoneyDto,
    val sourceOrderId: Long,
    val venueId: Long,
)

@Serializable
data class GuestVisitRepeatEligibleLineDto(
    val itemId: Long,
    val itemName: String,
    val quantity: Int,
    val selectedOption: GuestVisitRepeatOptionDto? = null,
    val preferenceNote: String? = null,
    val currentItemPrice: GuestVisitRepeatMoneyDto,
    val currentUnitPrice: GuestVisitRepeatMoneyDto,
    val currentLineTotal: GuestVisitRepeatMoneyDto,
)

@Serializable
data class GuestVisitRepeatOptionDto(
    val optionId: Long,
    val name: String,
    val currentPriceDelta: GuestVisitRepeatMoneyDto,
)

@Serializable
data class GuestVisitRepeatSkippedLineDto(
    val itemName: String,
    val quantity: Int,
    val selectedOptionName: String? = null,
    val reason: String,
    val message: String,
)

@Serializable
data class GuestVisitRepeatMoneyDto(
    val amountMinor: Long,
    val currency: String,
)
