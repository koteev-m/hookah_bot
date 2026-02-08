package com.hookah.platform.backend.miniapp.guest.api

import kotlinx.serialization.Serializable

@Serializable
data class TableResolveResponse(
    val venueId: Long,
    val tableId: Long,
    val tableNumber: String,
    val venueStatus: String,
    val subscriptionStatus: String,
    val available: Boolean,
    val unavailableReason: String?,
)
