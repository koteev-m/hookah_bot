package com.hookah.platform.backend.api

import io.ktor.http.HttpStatusCode

class SeatingConflictException(message: String) : ApiException(
    code = "SEATING_CONFLICT",
    httpStatus = HttpStatusCode.Conflict,
    message = message,
)
