package com.hookah.platform.backend.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject

sealed class ApiException(
    val code: String,
    val httpStatus: HttpStatusCode,
    override val message: String,
    val details: JsonObject? = null
) : RuntimeException(message)

class UnauthorizedException(
    details: JsonObject? = null
) : ApiException(
    code = ApiErrorCodes.UNAUTHORIZED,
    httpStatus = HttpStatusCode.Unauthorized,
    message = "Unauthorized",
    details = details
)

class InvalidInputException(
    message: String = "Invalid input",
    details: JsonObject? = null
) : ApiException(
    code = ApiErrorCodes.INVALID_INPUT,
    httpStatus = HttpStatusCode.BadRequest,
    message = message,
    details = details
)

class NotFoundException(
    message: String = "Not found",
    details: JsonObject? = null
) : ApiException(
    code = ApiErrorCodes.NOT_FOUND,
    httpStatus = HttpStatusCode.NotFound,
    message = message,
    details = details
)

class ServiceSuspendedException(
    message: String = "Service suspended",
    details: JsonObject? = null
) : ApiException(
    code = ApiErrorCodes.SERVICE_SUSPENDED,
    httpStatus = HttpStatusCode.Locked,
    message = message,
    details = details
)

class ConfigException(
    message: String = "Service unavailable",
    details: JsonObject? = null
) : ApiException(
    code = ApiErrorCodes.CONFIG_ERROR,
    httpStatus = HttpStatusCode.ServiceUnavailable,
    message = message,
    details = details
)

class InitDataInvalidException(
    details: JsonObject? = null
) : ApiException(
    code = ApiErrorCodes.INITDATA_INVALID,
    httpStatus = HttpStatusCode.Unauthorized,
    message = "Invalid initData",
    details = details
)

class DatabaseUnavailableException(
    details: JsonObject? = null
) : ApiException(
    code = ApiErrorCodes.DATABASE_UNAVAILABLE,
    httpStatus = HttpStatusCode.ServiceUnavailable,
    message = "Database unavailable",
    details = details
)
