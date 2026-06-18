package com.hookah.platform.backend.miniapp.guest.api

import kotlinx.serialization.Serializable

@Serializable
data class StaffCallRequest(
    val tableToken: String,
    val tableSessionId: Long,
    val reason: String,
    val comment: String? = null,
)

@Serializable
data class StaffCallResponse(
    val staffCallId: Long,
    val createdAtEpochSeconds: Long,
    val status: String,
    val statusLabel: String,
)

@Serializable
data class StaffCallStatusResponse(
    val items: List<StaffCallStatusDto>,
)

@Serializable
data class StaffCallStatusDto(
    val staffCallId: Long,
    val status: String,
    val statusLabel: String,
    val createdAtEpochSeconds: Long,
    val reason: String,
    val reasonLabel: String,
    val comment: String?,
)
