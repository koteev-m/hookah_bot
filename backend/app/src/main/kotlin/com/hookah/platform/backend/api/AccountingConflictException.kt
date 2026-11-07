package com.hookah.platform.backend.api
import io.ktor.http.HttpStatusCode

class AccountingConflictException(message: String) : ApiException(
    code = "ACCOUNTING_CONFLICT",
    httpStatus = HttpStatusCode.Conflict,
    message = message,
)
