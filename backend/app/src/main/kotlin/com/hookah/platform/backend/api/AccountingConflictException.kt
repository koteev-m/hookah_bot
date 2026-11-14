package com.hookah.platform.backend.api
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject

class AccountingConflictException(message: String, details: JsonObject? = null) : ApiException(
    code = "ACCOUNTING_CONFLICT",
    httpStatus = HttpStatusCode.Conflict,
    message = message,
    details = details,
)
