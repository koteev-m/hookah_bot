package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.telegram.db.GuestProfile
import com.hookah.platform.backend.telegram.db.UserRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.sql.SQLException

@Serializable
private data class GuestProfileResponse(
    val displayName: String?,
    val birthday: String?,
)

@Serializable
private data class GuestDisplayNameRequest(val displayName: String)

@Serializable
private data class GuestBirthdayRequest(val birthday: String)

fun Route.guestProfileRoutes(users: UserRepository) {
    route("/profile") {
        get {
            val userId = call.requireUserId()
            val profile = readProfile { users.findGuestProfile(userId) } ?: throw NotFoundException()
            call.respond(profile.toGuestProfileResponse())
        }

        put("/name") {
            val userId = call.requireUserId()
            val request = call.receive<GuestDisplayNameRequest>()
            val name =
                try {
                    UserRepository.normalizeGuestDisplayName(request.displayName)
                } catch (_: IllegalArgumentException) {
                    throw InvalidInputException("Имя должно быть одной строкой от 1 до 40 символов.")
                }
            val profile = readProfile { users.saveGuestDisplayName(userId, name) } ?: throw NotFoundException()
            call.respond(profile.toGuestProfileResponse())
        }

        put("/birthday") {
            val userId = call.requireUserId()
            val request = call.receive<GuestBirthdayRequest>()
            val date =
                UserRepository.parseGuestBirthday(request.birthday)
                    ?: throw InvalidInputException("Введите реальную дату в формате ДД.ММ.")
            val profile =
                readProfile {
                    users.saveBirthdayOnce(userId, date.monthValue, date.dayOfMonth)
                        ?: users.findGuestProfile(userId)
                } ?: throw NotFoundException()
            if (profile.birthdayMonth != date.monthValue || profile.birthdayDay != date.dayOfMonth) {
                throw InvalidInputException("День рождения уже сохранён. Изменить его самостоятельно нельзя.")
            }
            call.respond(profile.toGuestProfileResponse())
        }
    }
}

private suspend fun readProfile(block: suspend () -> GuestProfile?): GuestProfile? =
    try {
        block()
    } catch (_: SQLException) {
        throw DatabaseUnavailableException()
    }

private fun GuestProfile.toGuestProfileResponse(): GuestProfileResponse {
    val month = birthdayMonth
    val day = birthdayDay
    val birthday = if (month != null && day != null) "%02d.%02d".format(day, month) else null
    return GuestProfileResponse(guestDisplayName, birthday)
}
