package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.miniapp.venue.staff.TodayStaffSource
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffModuleSettings
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffModuleSettingsRepository
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffModuleSettingsWrite
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.Instant

@Serializable
data class VenueStaffModuleSettingsResponse(
    val teamScheduleModuleEnabled: Boolean,
    val guestTeamVisible: Boolean,
    val todayStaffSource: String,
    val updatedAt: String,
)

fun Route.venueStaffModuleSettingsRoutes(
    venueAccessRepository: VenueAccessRepository,
    settingsRepository: VenueStaffModuleSettingsRepository,
    auditLogRepository: AuditLogRepository,
) {
    route("/venue/{venueId}/staff-module-settings") {
        get {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireStaffModuleSettingsPermission(venueAccessRepository, userId, venueId)
            call.respond(settingsRepository.get(venueId).toResponse())
        }

        put {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireStaffModuleSettingsPermission(venueAccessRepository, userId, venueId)
            val body = call.receive<JsonObject>()
            if (body.keys != STAFF_MODULE_SETTINGS_UPDATE_FIELDS) {
                throw InvalidInputException("Передайте полный объект настроек без лишних полей.")
            }
            val input =
                VenueStaffModuleSettingsWrite(
                    teamScheduleModuleEnabled = body.requireBoolean("teamScheduleModuleEnabled"),
                    guestTeamVisible = body.requireBoolean("guestTeamVisible"),
                    todayStaffSource = parseTodayStaffSource(body.requireString("todayStaffSource")),
                )
            val saved =
                settingsRepository.update(
                    venueId = venueId,
                    actorUserId = userId,
                    expectedUpdatedAt = parseExpectedSettingsUpdatedAt(body.requireString("expectedUpdatedAt")),
                    input = input,
                    auditLogRepository = auditLogRepository,
                )
            call.respond(saved.toResponse())
        }
    }
}

private suspend fun requireStaffModuleSettingsPermission(
    venueAccessRepository: VenueAccessRepository,
    userId: Long,
    venueId: Long,
) {
    val role = resolveVenueRole(venueAccessRepository, userId, venueId)
    if (VenuePermission.STAFF_MODULE_SETTINGS_MANAGE !in VenuePermissions.forRole(role)) {
        throw ForbiddenException()
    }
}

private fun parseTodayStaffSource(raw: String): TodayStaffSource =
    runCatching { TodayStaffSource.valueOf(raw) }
        .getOrElse { throw InvalidInputException("todayStaffSource must be MANUAL or SCHEDULE") }

private fun parseExpectedSettingsUpdatedAt(raw: String): Instant =
    runCatching { Instant.parse(raw) }
        .getOrElse { throw InvalidInputException("expectedUpdatedAt must be an ISO-8601 instant") }

private fun JsonObject.requireBoolean(name: String): Boolean {
    val primitive = this[name] as? JsonPrimitive
    return primitive?.takeIf { !it.isString }?.booleanOrNull
        ?: throw InvalidInputException("Передайте полный объект настроек без лишних полей.")
}

private fun JsonObject.requireString(name: String): String {
    val primitive = this[name] as? JsonPrimitive
    return primitive?.takeIf { it.isString }?.content
        ?: throw InvalidInputException("Передайте полный объект настроек без лишних полей.")
}

private fun VenueStaffModuleSettings.toResponse(): VenueStaffModuleSettingsResponse =
    VenueStaffModuleSettingsResponse(
        teamScheduleModuleEnabled = teamScheduleModuleEnabled,
        guestTeamVisible = guestTeamVisible,
        todayStaffSource = todayStaffSource.name,
        updatedAt = updatedAt.toString(),
    )

private val STAFF_MODULE_SETTINGS_UPDATE_FIELDS =
    setOf(
        "teamScheduleModuleEnabled",
        "guestTeamVisible",
        "todayStaffSource",
        "expectedUpdatedAt",
    )
