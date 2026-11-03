package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.ensureGuestBrowseAvailableReadOnly
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
private data class VenuePublishRequest(val confirmed: Boolean = false)

@Serializable
private enum class GuestPublicationAccess {
    AVAILABLE,
    UNAVAILABLE,
    UNVERIFIED,
}

@Serializable
private data class VenuePublicationResponse(
    val name: String,
    val status: String?,
    val ownerCanPublish: Boolean,
    val missing: List<VenuePublicationRequirement>?,
    val guestAccess: GuestPublicationAccess,
    val outcome: VenuePublicationOutcome? = null,
)

fun Route.venuePublicationRoutes(
    publication: VenuePublicationService,
    venues: GuestVenueRepository,
    subscriptions: SubscriptionRepository,
    maintenanceActive: Boolean,
) {
    suspend fun view(
        venueId: Long,
        state: VenuePublicationState,
        outcome: VenuePublicationOutcome? = null,
    ): VenuePublicationResponse {
        val access =
            if (state.status != VenueStatus.PUBLISHED || maintenanceActive) {
                GuestPublicationAccess.UNAVAILABLE
            } else {
                try {
                    ensureGuestBrowseAvailableReadOnly(venueId, venues, subscriptions)
                    GuestPublicationAccess.AVAILABLE
                } catch (_: NotFoundException) {
                    GuestPublicationAccess.UNAVAILABLE
                } catch (_: DatabaseUnavailableException) {
                    GuestPublicationAccess.UNVERIFIED
                }
            }
        return VenuePublicationResponse(
            state.name,
            state.status?.dbValue,
            state.ownerCanPublish,
            state.missing,
            access,
            outcome,
        )
    }

    get("/venue/{venueId}/publication") {
        val id = call.publicationVenueId()
        val state = publication.inspect(id, call.requireUserId(), call.request.queryParameters["check"] == "true")
        call.response.header("Cache-Control", "no-store")
        call.respond(view(id, state))
    }
    post("/venue/{venueId}/publication") {
        val actor = call.requireUserId()
        val id = call.publicationVenueId()
        if (!call.receive<VenuePublishRequest>().confirmed) {
            throw InvalidInputException("Подтвердите публикацию заведения.")
        }
        val result = publication.publish(id, actor, VenuePublicationSource.VENUE_MINI_APP)
        call.response.header("Cache-Control", "no-store")
        call.respond(view(id, result.state, result.outcome))
    }
}

private fun ApplicationCall.publicationVenueId(): Long =
    parameters["venueId"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw NotFoundException()
