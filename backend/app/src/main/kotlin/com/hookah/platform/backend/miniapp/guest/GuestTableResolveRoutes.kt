package com.hookah.platform.backend.miniapp.guest

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

fun Route.guestTableResolveRoutes(
    tableTokenResolver: suspend (String) -> TableContext?,
    guestVenueRepository: GuestVenueRepository,
    subscriptionRepository: SubscriptionRepository,
) {
    get("/table/resolve") {
        val rawToken = call.request.queryParameters["tableToken"]
        val token = validateTableToken(rawToken)

        val table = tableTokenResolver(token) ?: throw NotFoundException()
        val venue = ensureVenuePublishedForGuest(table.venueId, guestVenueRepository)
        val subscriptionStatus = subscriptionRepository.getSubscriptionStatus(table.venueId)
        val availability = VenueAvailabilityResolver.resolve(venue.status, subscriptionStatus)

        call.respond(
            TableResolveResponse(
                venueId = table.venueId,
                tableId = table.tableId,
                tableNumber = table.tableNumber.toString(),
                venueStatus = availability.venueStatus.dbValue,
                subscriptionStatus = availability.subscriptionStatus,
                available = availability.available,
                unavailableReason = availability.reason,
            ),
        )
    }
}
