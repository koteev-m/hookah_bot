package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.api.CatalogResponse
import com.hookah.platform.backend.miniapp.guest.api.CatalogVenueDto
import com.hookah.platform.backend.miniapp.guest.api.VenueDto
import com.hookah.platform.backend.miniapp.guest.api.VenueResponse
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.db.VenueShort
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.guestVenueRoutes(
    guestVenueRepository: GuestVenueRepository,
    guestVenuePolicy: GuestVenuePolicy
) {
    get("/catalog") {
        val venues = guestVenueRepository.listCatalogVenues()
        call.respond(CatalogResponse(venues = venues.map { it.toCatalogDto() }))
    }

    get("/venue/{id}") {
        val rawId = call.parameters["id"] ?: throw InvalidInputException("id is required")
        val venueId = rawId.toLongOrNull() ?: throw InvalidInputException("id must be a number")
        val venue = guestVenueRepository.findVenueByIdForGuest(venueId) ?: throw NotFoundException()
        guestVenuePolicy.ensureVenueReadable(venue.status)
        call.respond(VenueResponse(venue = venue.toVenueDto()))
    }
}

private fun VenueShort.toCatalogDto(): CatalogVenueDto = CatalogVenueDto(
    id = id,
    name = name,
    city = city,
    address = address
)

private fun VenueShort.toVenueDto(): VenueDto = VenueDto(
    id = id,
    name = name,
    city = city,
    address = address,
    status = status
)
