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

class StaffPhotoActionRetiredException : ApiException(
    code = "STAFF_PHOTO_ACTION_RETIRED",
    httpStatus = HttpStatusCode.Gone,
    message =
        "Обновите Mini App. Фото становится текущим после загрузки владельцем или менеджером; " +
            "отдельное согласие и публикация больше не используются.",
)

class StaffPhotoInvalidException(message: String = "Не удалось прочитать фото. Выберите JPEG или PNG.") : ApiException(
    code = "STAFF_PHOTO_INVALID",
    httpStatus = HttpStatusCode.BadRequest,
    message = message,
)
