package com.hookah.platform.backend.api

import io.ktor.http.HttpStatusCode

class StaffPhotoUnavailableException : ApiException(
    code = "STAFF_PHOTO_UNAVAILABLE",
    httpStatus = HttpStatusCode.ServiceUnavailable,
    message = "Загрузка фотографий временно недоступна",
)

class StaffPhotoConflictException : ApiException(
    code = "STAFF_PHOTO_CONFLICT",
    httpStatus = HttpStatusCode.Conflict,
    message = "Состояние фотографии изменилось. Обновите карточку перед новым действием.",
)

class StaffPhotoInvalidException(message: String = "Не удалось прочитать фото. Выберите JPEG или PNG.") : ApiException(
    code = "STAFF_PHOTO_INVALID",
    httpStatus = HttpStatusCode.BadRequest,
    message = message,
)
