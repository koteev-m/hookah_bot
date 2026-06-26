package com.hookah.platform.backend.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject

sealed class ApiException(
    val code: String,
    val httpStatus: HttpStatusCode,
    override val message: String,
    val details: JsonObject? = null,
) : RuntimeException(message)

class UnauthorizedException(
    details: JsonObject? = null,
) : ApiException(
        code = ApiErrorCodes.UNAUTHORIZED,
        httpStatus = HttpStatusCode.Unauthorized,
        message = "Unauthorized",
        details = details,
    )

class InvalidInputException(
    message: String = "Invalid input",
    details: JsonObject? = null,
) : ApiException(
        code = ApiErrorCodes.INVALID_INPUT,
        httpStatus = HttpStatusCode.BadRequest,
        message = message,
        details = details,
    )

class ForbiddenException(
    message: String = "Forbidden",
    details: JsonObject? = null,
) : ApiException(
        code = ApiErrorCodes.FORBIDDEN,
        httpStatus = HttpStatusCode.Forbidden,
        message = message,
        details = details,
    )

class NotFoundException(
    message: String = "Not found",
    details: JsonObject? = null,
) : ApiException(
        code = ApiErrorCodes.NOT_FOUND,
        httpStatus = HttpStatusCode.NotFound,
        message = message,
        details = details,
    )

class ServiceSuspendedException(
    message: String = "Service suspended",
    details: JsonObject? = null,
) : ApiException(
        code = ApiErrorCodes.SERVICE_SUSPENDED,
        httpStatus = HttpStatusCode.Locked,
        message = message,
        details = details,
    )

class SubscriptionBlockedException(
    message: String = "Subscription blocked",
    details: JsonObject? = null,
) : ApiException(
        code = ApiErrorCodes.SUBSCRIPTION_BLOCKED,
        httpStatus = HttpStatusCode.Locked,
        message = message,
        details = details,
    )

class ConfigException(
    message: String = "Service unavailable",
    details: JsonObject? = null,
) : ApiException(
        code = ApiErrorCodes.CONFIG_ERROR,
        httpStatus = HttpStatusCode.ServiceUnavailable,
        message = message,
        details = details,
    )

class InitDataInvalidException(
    details: JsonObject? = null,
) : ApiException(
        code = ApiErrorCodes.INITDATA_INVALID,
        httpStatus = HttpStatusCode.Unauthorized,
        message = "Invalid initData",
        details = details,
    )

class TooManyRequestsException(
    message: String = "Too many requests",
    details: JsonObject? = null,
) : ApiException(
        code = ApiErrorCodes.RATE_LIMITED,
        httpStatus = HttpStatusCode.TooManyRequests,
        message = message,
        details = details,
    )

class DatabaseUnavailableException(
    details: JsonObject? = null,
) : ApiException(
        code = ApiErrorCodes.DATABASE_UNAVAILABLE,
        httpStatus = HttpStatusCode.ServiceUnavailable,
        message = "Database unavailable",
        details = details,
    )

class VenueScheduleNotConfiguredException(
    details: JsonObject? = null,
) : ApiException(
        code = ApiErrorCodes.VENUE_SCHEDULE_NOT_CONFIGURED,
        httpStatus = HttpStatusCode.BadRequest,
        message = "Заведение пока не настроило график бронирования.",
        details = details,
    )

class VenueClosedOnSelectedDateException(
    reason: String?,
    details: JsonObject? = null,
) : ApiException(
        code = ApiErrorCodes.VENUE_CLOSED_ON_SELECTED_DATE,
        httpStatus = HttpStatusCode.BadRequest,
        message =
            reason?.takeIf { it.isNotBlank() }?.let {
                "На выбранную дату заведение не работает: $it. Выберите другую дату."
            } ?: "На выбранную дату заведение не работает. Выберите другую дату.",
        details = details,
    )

class VenueBookingOutsideHoursException(
    opensAt: String,
    closesAt: String,
    details: JsonObject? = null,
) : ApiException(
        code = ApiErrorCodes.VENUE_BOOKING_OUTSIDE_HOURS,
        httpStatus = HttpStatusCode.BadRequest,
        message = "На выбранное время бронь недоступна. В этот день заведение работает с $opensAt до $closesAt.",
        details = details,
    )
