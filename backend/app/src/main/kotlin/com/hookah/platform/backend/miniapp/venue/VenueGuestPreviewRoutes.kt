package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.GuestVenueReadService
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

const val GUEST_PREVIEW_UNAVAILABLE_MESSAGE = "Заведение сейчас недоступно для гостевого просмотра."

fun Route.venueGuestPreviewRoutes(
    venueAccessRepository: VenueAccessRepository,
    guestVenueReadService: GuestVenueReadService,
) {
    route("/venue/{venueId}/guest-preview") {
        get {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireGuestPreviewAccess(venueAccessRepository, userId, venueId)
            call.respond(
                loadGuestPreviewOrUnavailable {
                    guestVenueReadService.getVenue(userId = userId, venueId = venueId)
                },
            )
        }

        get("/info-sections") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireGuestPreviewAccess(venueAccessRepository, userId, venueId)
            call.respond(
                loadGuestPreviewOrUnavailable {
                    guestVenueReadService.getInfoSections(venueId)
                },
            )
        }
    }
}

private suspend fun requireGuestPreviewAccess(
    venueAccessRepository: VenueAccessRepository,
    userId: Long,
    venueId: Long,
) {
    val membership =
        venueAccessRepository.findVenueMembershipIncludingDeleted(userId, venueId)
            ?: throw ForbiddenException()
    val role = VenueRoleMapping.fromDb(membership.role) ?: throw ForbiddenException()
    if (role !in setOf(VenueRole.OWNER, VenueRole.MANAGER)) {
        throw ForbiddenException()
    }
}

private suspend fun <T> loadGuestPreviewOrUnavailable(block: suspend () -> T): T =
    try {
        block()
    } catch (_: NotFoundException) {
        throw NotFoundException(GUEST_PREVIEW_UNAVAILABLE_MESSAGE)
    }
