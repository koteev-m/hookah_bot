package com.hookah.platform.backend.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

object ApiErrorCodes {
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val FORBIDDEN = "FORBIDDEN"
    const val INVALID_INPUT = "INVALID_INPUT"
    const val INITDATA_INVALID = "INITDATA_INVALID"
    const val SERVICE_SUSPENDED = "SERVICE_SUSPENDED"
    const val NOT_FOUND = "NOT_FOUND"
    const val DATABASE_UNAVAILABLE = "DATABASE_UNAVAILABLE"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
    const val CONFIG_ERROR = "CONFIG_ERROR"
}

@Serializable
data class ApiErrorEnvelope(
    val error: ApiError,
    val requestId: String? = null
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val details: JsonObject? = null
)
