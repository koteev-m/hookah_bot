package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.miniapp.guest.api.CatalogResponse
import com.hookah.platform.backend.miniapp.guest.api.CatalogVenueDto
import com.hookah.platform.backend.miniapp.guest.api.MenuCategoryDto
import com.hookah.platform.backend.miniapp.guest.api.MenuItemDto
import com.hookah.platform.backend.miniapp.guest.api.MenuResponse
import com.hookah.platform.backend.miniapp.guest.api.VenueDto
import com.hookah.platform.backend.miniapp.guest.api.VenueResponse
import com.hookah.platform.backend.miniapp.guest.db.GuestMenuRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.db.MenuCategoryModel
import com.hookah.platform.backend.miniapp.guest.db.MenuItemModel
import com.hookah.platform.backend.miniapp.guest.db.MenuModel
import com.hookah.platform.backend.miniapp.guest.db.VenueShort
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.guestVenueRoutes(
    guestVenueRepository: GuestVenueRepository,
    guestMenuRepository: GuestMenuRepository,
    subscriptionRepository: SubscriptionRepository
) {
    get("/catalog") {
        val venues = guestVenueRepository.listCatalogVenues()
        call.respond(CatalogResponse(venues = venues.map { it.toCatalogDto() }))
    }

    get("/venue/{id}") {
        val rawId = call.parameters["id"] ?: throw InvalidInputException("id is required")
        val venueId = rawId.toLongOrNull() ?: throw InvalidInputException("id must be a number")
        val venue = ensureGuestBrowseAvailable(venueId, guestVenueRepository, subscriptionRepository)
        call.respond(VenueResponse(venue = venue.toVenueDto()))
    }

    get("/venue/{id}/menu") {
        val rawId = call.parameters["id"] ?: throw InvalidInputException("id is required")
        val venueId = rawId.toLongOrNull() ?: throw InvalidInputException("id must be a number")
        ensureGuestBrowseAvailable(venueId, guestVenueRepository, subscriptionRepository)
        val menu = guestMenuRepository.getMenu(venueId)
        call.respond(menu.toResponse())
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
    status = status.dbValue
)

private fun MenuModel.toResponse(): MenuResponse = MenuResponse(
    venueId = venueId,
    categories = categories.map { it.toDto() }
)

private fun MenuCategoryModel.toDto(): MenuCategoryDto = MenuCategoryDto(
    id = id,
    name = name,
    items = items.map { it.toDto() }
)

private fun MenuItemModel.toDto(): MenuItemDto = MenuItemDto(
    id = id,
    name = name,
    priceMinor = priceMinor,
    currency = currency,
    isAvailable = isAvailable
)
