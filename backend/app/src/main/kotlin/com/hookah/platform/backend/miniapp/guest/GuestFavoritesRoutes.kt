package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.api.GuestFavoriteItemDto
import com.hookah.platform.backend.miniapp.guest.api.GuestFavoriteItemsResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestFavoriteMutationResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestFavoriteVenueDto
import com.hookah.platform.backend.miniapp.guest.api.GuestFavoriteVenuesResponse
import com.hookah.platform.backend.miniapp.guest.db.FavoriteMenuItem
import com.hookah.platform.backend.miniapp.guest.db.FavoriteVenue
import com.hookah.platform.backend.miniapp.guest.db.GuestFavoritesRepository
import com.hookah.platform.backend.miniapp.venue.requireUserId
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.guestFavoritesRoutes(
    guestFavoritesRepository: GuestFavoritesRepository,
) {
    route("/favorites") {
        route("/venues") {
            get {
                val userId = call.requireUserId()
                val limit = parseFavoritesLimit(call.request.queryParameters["limit"])
                val venues = guestFavoritesRepository.listFavoriteVenues(userId = userId, limit = limit)
                call.respond(GuestFavoriteVenuesResponse(venues = venues.map { it.toDto() }))
            }

            post("/{venueId}") {
                val userId = call.requireUserId()
                val venueId = parsePathId(call.parameters["venueId"], "venueId")
                val added = guestFavoritesRepository.addVenueFavorite(userId = userId, venueId = venueId)
                if (!added) {
                    throw NotFoundException()
                }
                call.respond(GuestFavoriteMutationResponse(ok = true))
            }

            delete("/{venueId}") {
                val userId = call.requireUserId()
                val venueId = parsePathId(call.parameters["venueId"], "venueId")
                guestFavoritesRepository.removeVenueFavorite(userId = userId, venueId = venueId)
                call.respond(GuestFavoriteMutationResponse(ok = true))
            }
        }

        route("/items") {
            get {
                val userId = call.requireUserId()
                val venueId =
                    call.request.queryParameters["venueId"]?.toLongOrNull()
                        ?: throw InvalidInputException("venueId must be a number")
                val limit = parseFavoritesLimit(call.request.queryParameters["limit"])
                val items =
                    guestFavoritesRepository.listFavoriteItemsForVenue(
                        userId = userId,
                        venueId = venueId,
                        limit = limit,
                    )
                call.respond(GuestFavoriteItemsResponse(items = items.map { it.toDto() }))
            }

            post("/{itemId}") {
                val userId = call.requireUserId()
                val itemId = parsePathId(call.parameters["itemId"], "itemId")
                val venueId =
                    call.request.queryParameters["venueId"]?.toLongOrNull()
                        ?: throw InvalidInputException("venueId must be a number")
                val added =
                    guestFavoritesRepository.addItemFavorite(
                        userId = userId,
                        venueId = venueId,
                        menuItemId = itemId,
                    )
                if (!added) {
                    throw NotFoundException()
                }
                call.respond(GuestFavoriteMutationResponse(ok = true))
            }

            delete("/{itemId}") {
                val userId = call.requireUserId()
                val itemId = parsePathId(call.parameters["itemId"], "itemId")
                guestFavoritesRepository.removeItemFavorite(userId = userId, menuItemId = itemId)
                call.respond(GuestFavoriteMutationResponse(ok = true))
            }
        }
    }
}

private fun parsePathId(
    raw: String?,
    name: String,
): Long = raw?.toLongOrNull() ?: throw InvalidInputException("$name must be a number")

private fun parseFavoritesLimit(raw: String?): Int =
    raw
        ?.toIntOrNull()
        ?.takeIf { it in 1..50 }
        ?: 20

private fun FavoriteVenue.toDto(): GuestFavoriteVenueDto =
    GuestFavoriteVenueDto(
        venueId = venueId,
        name = name,
        city = city,
        address = address,
    )

private fun FavoriteMenuItem.toDto(): GuestFavoriteItemDto =
    GuestFavoriteItemDto(
        itemId = itemId,
        venueId = venueId,
        categoryId = categoryId,
        name = name,
        priceMinor = priceMinor,
        currency = currency,
    )
