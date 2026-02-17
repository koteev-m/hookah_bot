package com.hookah.platform.backend.miniapp.guest.api

import kotlinx.serialization.Serializable

@Serializable
data class GuestBookingCreateRequest(
    val venueId: Long,
    val scheduledAt: String,
    val partySize: Int? = null,
    val comment: String? = null,
)

@Serializable
data class GuestBookingUpdateRequest(
    val bookingId: Long,
    val scheduledAt: String,
    val partySize: Int? = null,
    val comment: String? = null,
)

@Serializable
data class GuestBookingCancelRequest(
    val bookingId: Long,
)

@Serializable
data class GuestBookingResponse(
    val bookingId: Long,
    val venueId: Long,
    val status: String,
    val scheduledAt: String,
    val partySize: Int?,
    val comment: String?,
)

@Serializable
data class GuestBookingListResponse(
    val items: List<GuestBookingResponse>,
)
