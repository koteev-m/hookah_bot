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
