package com.hookah.platform.backend.miniapp.shift

import kotlinx.serialization.Serializable

@Serializable
data class GuestShiftExtensionOptionsResponse(
    val available: Boolean,
    val unavailableReason: String? = null,
    val durationMinutes: Int? = null,
    val priceMinor: Long? = null,
    val currency: String? = null,
    val tableSessionId: Long? = null,
    val tabId: Long? = null,
    val orderId: Long? = null,
    val currentOrderableUntil: String? = null,
    val proposedOrderableUntil: String? = null,
    val pendingRequest: ShiftExtensionRequestDto? = null,
)

@Serializable
data class GuestShiftExtensionRequest(
    val tableToken: String,
    val tableSessionId: Long,
    val tabId: Long,
    val idempotencyKey: String? = null,
    val comment: String? = null,
)

@Serializable
data class ShiftExtensionRequestResponse(
    val request: ShiftExtensionRequestDto,
)

@Serializable
data class ShiftExtensionRequestsResponse(
    val items: List<ShiftExtensionRequestDto>,
)

@Serializable
data class ShiftExtensionDecisionRequest(
    val reasonText: String? = null,
)

@Serializable
data class ShiftExtensionDecisionResponse(
    val request: ShiftExtensionRequestDto,
    val applied: Boolean,
)

@Serializable
data class ShiftExtensionRequestDto(
    val id: Long,
    val venueId: Long,
    val tableSessionId: Long,
    val tableId: Long,
    val tableNumber: String? = null,
    val tabId: Long,
    val orderId: Long,
    val requestedByUserId: Long,
    val status: String,
    val durationMinutes: Int,
    val priceMinor: Long,
    val currency: String,
    val currentOrderableUntil: String,
    val requestedUntil: String,
    val comment: String? = null,
    val decidedByUserId: Long? = null,
    val decidedAt: String? = null,
    val rejectReason: String? = null,
    val createdAt: String,
    val updatedAt: String,
)
