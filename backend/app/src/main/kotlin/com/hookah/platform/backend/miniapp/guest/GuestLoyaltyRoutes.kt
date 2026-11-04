package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.telegram.db.LoyaltyProgramTarget
import com.hookah.platform.backend.telegram.db.LoyaltyProgramType
import com.hookah.platform.backend.telegram.db.LoyaltyRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
private data class GuestLoyaltyResponse(val items: List<GuestLoyaltyProgramResponse>)

@Serializable
private data class GuestLoyaltyProgramResponse(
    val venueId: Long,
    val venueName: String,
    val programType: String,
    val nthValue: Int,
    val progressCount: Int,
    val rewardsAvailable: Int,
    val rewardsReserved: Int,
    val earnTargets: List<GuestLoyaltyTarget>,
    val rewardTargets: List<GuestLoyaltyTarget>,
)

@Serializable
private data class GuestLoyaltyTarget(val type: String, val name: String?)

fun Route.guestLoyaltyRoutes(
    loyalty: LoyaltyRepository,
    venues: GuestVenueRepository,
    subscriptions: SubscriptionRepository,
) {
    get("/loyalty") {
        val userId = call.requireUserId()
        val venueId =
            call.request.queryParameters["venueId"]?.let {
                it.toLongOrNull()?.takeIf { id -> id > 0 } ?: throw InvalidInputException("Invalid venue id")
            }
        val programs =
            if (venueId != null) {
                ensureGuestBrowseAvailableReadOnly(venueId, venues, subscriptions)
                listOfNotNull(loyalty.getGuestProgress(venueId, userId))
            } else {
                loyalty.listGuestProgress(userId)
            }
        val items =
            programs.map { program ->
                GuestLoyaltyProgramResponse(
                    venueId = program.venueId,
                    venueName = program.venueName,
                    programType = LoyaltyProgramType.NTH_HOOKAH_FREE.dbValue,
                    nthValue = program.nthValue,
                    progressCount = program.progressCount,
                    rewardsAvailable = program.rewardsAvailable,
                    rewardsReserved = program.rewardsReserved,
                    earnTargets =
                        loyalty.listEarnTargets(
                            program.venueId,
                            program.programId,
                        ).map { it.toGuestTarget() },
                    rewardTargets =
                        loyalty.listRewardTargets(
                            program.venueId,
                            program.programId,
                        ).map { it.toGuestTarget() },
                )
            }
        call.respond(GuestLoyaltyResponse(items))
    }
}

private fun LoyaltyProgramTarget.toGuestTarget() = GuestLoyaltyTarget(targetType.dbValue, menuItemName)
