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
data class GuestBookingConfirmRequest(
    val bookingId: Long,
    val attendanceScheduleVersion: Long? = null,
)

@Serializable
data class GuestBookingResponse(
    val bookingId: Long,
    val venueId: Long,
    val status: String,
    val scheduledAt: String,
    val partySize: Int?,
    val comment: String?,
    val lastGuestConfirmationAt: String? = null,
    val attendanceScheduleVersion: Long? = null,
    val displayNumber: Int? = null,
    val displayLabel: String? = null,
    val venueName: String? = null,
    val statusLabel: String? = null,
    val scheduledAtDisplay: String? = null,
    val scheduledLocalDate: String? = null,
    val scheduledLocalTime: String? = null,
    val arrivalDeadlineAt: String? = null,
    val arrivalDeadlineAtDisplay: String? = null,
    val arrivalDeadlineTimeDisplay: String? = null,
    val canChange: Boolean? = null,
    val canCancel: Boolean? = null,
)

@Serializable
data class GuestBookingListResponse(
    val items: List<GuestBookingResponse>,
)
