package com.hookah.platform.backend.miniapp.guest.api

import kotlinx.serialization.Serializable

@Serializable
data class TableResolveResponse(
    val venueId: Long,
    val venueName: String,
    val tableId: Long,
    val tableSessionId: Long,
    val tableSessionStatus: String,
    val tableSessionActive: Boolean,
    val tableSessionInactiveReason: String? = null,
    val tableNumber: String,
    val venueStatus: String,
    val subscriptionStatus: String,
    val available: Boolean,
    val unavailableReason: String?,
)

@Serializable
data class TableSessionEndRequest(
    val tableToken: String,
    val tableSessionId: Long,
)

@Serializable
data class TableSessionEndResponse(
    val ended: Boolean,
    val tableSessionId: Long,
    val blockedReason: String?,
    val message: String?,
)

@Serializable
data class TableRestoreResponse(
    val context: RestoredTableContextResponse?,
)

@Serializable
data class RestoredTableContextResponse(
    val tableToken: String,
    val tabId: Long,
    val venueId: Long,
    val venueName: String,
    val tableId: Long,
    val tableSessionId: Long,
    val tableSessionStatus: String,
    val tableSessionActive: Boolean,
    val tableSessionInactiveReason: String? = null,
    val tableNumber: String,
    val venueStatus: String,
    val subscriptionStatus: String,
    val available: Boolean,
    val unavailableReason: String?,
)
