package com.hookah.platform.backend.api

import io.ktor.http.HttpStatusCode

class VenueInfoMediaInvalidException(
    message: String = "Не удалось прочитать файл. Выберите JPEG, PNG или безопасный PDF.",
) : ApiException(
        code = "VENUE_INFO_MEDIA_INVALID",
        httpStatus = HttpStatusCode.BadRequest,
        message = message,
    )

class VenueInfoMediaConflictException : ApiException(
    code = "VENUE_INFO_MEDIA_CONFLICT",
    httpStatus = HttpStatusCode.Conflict,
    message =
        "Материалы изменились или операция ещё выполняется. " +
            "Обновите список; для повтора загрузки используйте тот же файл.",
)

class VenueInfoMediaUnavailableException(
    message: String = "Хранилище материалов временно недоступно. Сохранённые вложения не изменены. Попробуйте позже.",
) : ApiException(
        code = "VENUE_INFO_MEDIA_UNAVAILABLE",
        httpStatus = HttpStatusCode.ServiceUnavailable,
        message = message,
    )
