package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.api.StaffCallRequest
import com.hookah.platform.backend.miniapp.guest.api.StaffCallResponse
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.telegram.TableContext
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.guestStaffCallRoutes(
    guestRateLimitConfig: GuestRateLimitConfig,
    rateLimiter: RateLimiter,
    tableTokenResolver: suspend (String) -> TableContext?,
    guestVenueRepository: GuestVenueRepository,
    subscriptionRepository: SubscriptionRepository,
    staffCallRepository: StaffCallRepository,
) {
    route("/staff-call") {
        installGuestStaffCallRateLimit(
            endpoint = "guest.staff-call",
            policy = guestRateLimitConfig.staffCall,
            rateLimiter = rateLimiter,
            tableTokenResolver = tableTokenResolver,
            resolvedTableAttribute = staffCallResolvedTableAttribute,
        )

        post {
            val request = call.receive<StaffCallRequest>()
            val token = validateTableToken(request.tableToken)
            val reason = normalizeStaffCallReason(request.reason)
            val comment = normalizeStaffCallComment(request.comment)

            val table =
                call.rateLimitResolvedTableOrNull(staffCallResolvedTableAttribute)
                    ?: (tableTokenResolver(token) ?: throw NotFoundException())
            ensureGuestActionAvailable(table.venueId, guestVenueRepository, subscriptionRepository)

            val created =
                staffCallRepository.createGuestStaffCall(
                    venueId = table.venueId,
                    tableId = table.tableId,
                    reason = reason,
                    comment = comment,
                )

            call.respond(
                StaffCallResponse(
                    staffCallId = created.id,
                    createdAtEpochSeconds = created.createdAt.epochSecond,
                ),
            )
        }
    }
}
