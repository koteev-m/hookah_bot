package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.billing.BillingOverviewService
import com.hookah.platform.backend.billing.appendBillingCheckoutEnsureAudit
import com.hookah.platform.backend.billing.toResponse
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.venueBillingRoutes(
    venueAccessRepository: VenueAccessRepository,
    billingOverviewService: BillingOverviewService,
    auditLogRepository: AuditLogRepository,
) {
    route("/venue") {
        get("/{venueId}/subscription") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireVenueOwner(venueAccessRepository, userId, venueId)
            val overview = billingOverviewService.readOverview(venueId) ?: throw NotFoundException()
            call.respond(overview.toResponse(billingOverviewService))
        }

        post("/{venueId}/subscription/checkout") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireVenueOwner(venueAccessRepository, userId, venueId)
            val overview =
                billingOverviewService.ensureCheckout(
                    venueId = venueId,
                    allowAdvance = false,
                ) ?: throw NotFoundException()
            appendBillingCheckoutEnsureAudit(
                auditLogRepository = auditLogRepository,
                actorUserId = userId,
                actorType = "venue_owner",
                overview = overview,
            )
            call.respond(overview.toResponse(billingOverviewService))
        }
    }
}

private suspend fun requireVenueOwner(
    venueAccessRepository: VenueAccessRepository,
    userId: Long,
    venueId: Long,
) {
    val role =
        resolveVenueRole(
            venueAccessRepository = venueAccessRepository,
            userId = userId,
            venueId = venueId,
        )
    if (role != VenueRole.OWNER) {
        throw ForbiddenException()
    }
}
