package com.hookah.platform.backend.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

object ApiErrorCodes {
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val FORBIDDEN = "FORBIDDEN"
    const val INVALID_INPUT = "INVALID_INPUT"
    const val INITDATA_INVALID = "INITDATA_INVALID"
    const val SERVICE_SUSPENDED = "SERVICE_SUSPENDED"
    const val SUBSCRIPTION_BLOCKED = "SUBSCRIPTION_BLOCKED"
    const val NOT_FOUND = "NOT_FOUND"
    const val DATABASE_UNAVAILABLE = "DATABASE_UNAVAILABLE"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
    const val CONFIG_ERROR = "CONFIG_ERROR"
    const val VENUE_SCHEDULE_NOT_CONFIGURED = "VENUE_SCHEDULE_NOT_CONFIGURED"
    const val VENUE_CLOSED_ON_SELECTED_DATE = "VENUE_CLOSED_ON_SELECTED_DATE"
    const val VENUE_BOOKING_OUTSIDE_HOURS = "VENUE_BOOKING_OUTSIDE_HOURS"
}

@Serializable
data class ApiErrorEnvelope(
    val error: ApiError,
    val requestId: String? = null,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val details: JsonObject? = null,
)
