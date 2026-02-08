package com.hookah.platform.backend.miniapp.guest.api

import kotlinx.serialization.Serializable

@Serializable
data class StaffCallRequest(
    val tableToken: String,
    val reason: String,
    val comment: String? = null,
)

@Serializable
data class StaffCallResponse(
    val staffCallId: Long,
    val createdAtEpochSeconds: Long,
)
