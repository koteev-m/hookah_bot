package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.api.TableResolveResponse
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.subscription.VenueAvailabilityResolver
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.telegram.TableContext
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private const val TABLE_TOKEN_MAX_LENGTH = 128

fun Route.guestTableResolveRoutes(
    tableTokenResolver: suspend (String) -> TableContext?,
    guestVenueRepository: GuestVenueRepository,
    subscriptionRepository: SubscriptionRepository
) {
    get("/table/resolve") {
        val rawToken = call.request.queryParameters["tableToken"]
        val token = validateTableToken(rawToken)

        val table = tableTokenResolver(token) ?: throw NotFoundException()
        val venue = guestVenueRepository.findVenueByIdForGuest(table.venueId) ?: throw NotFoundException()
        val subscriptionStatus = subscriptionRepository.getSubscriptionStatus(table.venueId)
        val availability = VenueAvailabilityResolver.resolve(venue.status, subscriptionStatus)

        call.respond(
            TableResolveResponse(
                venueId = table.venueId,
                tableId = table.tableId,
                tableNumber = table.tableNumber.toString(),
                venueStatus = availability.venueStatus,
                subscriptionStatus = availability.subscriptionStatus,
                available = availability.available,
                unavailableReason = availability.reason
            )
        )
    }
}

private fun validateTableToken(rawToken: String?): String {
    val trimmed = rawToken?.trim() ?: throw InvalidInputException("tableToken is required")
    if (trimmed.isEmpty()) {
        throw InvalidInputException("tableToken is required")
    }
    if (trimmed.length > TABLE_TOKEN_MAX_LENGTH) {
        throw InvalidInputException("tableToken length must be <= $TABLE_TOKEN_MAX_LENGTH")
    }
    if (trimmed.any { it.code !in 0x21..0x7E }) {
        throw InvalidInputException("tableToken contains invalid characters")
    }
    return trimmed
}
