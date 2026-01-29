package com.hookah.platform.backend.miniapp.subscription

import com.hookah.platform.backend.miniapp.venue.VenueStatus
import java.util.Locale

enum class SubscriptionStatus(val wire: String) {
    TRIAL("trial"),
    ACTIVE("active"),
    PAST_DUE("past_due"),
    SUSPENDED("suspended"),
    UNKNOWN("unknown");

    companion object {
        fun fromDb(value: String?): SubscriptionStatus {
            return when (value?.lowercase(Locale.ROOT)) {
                "trial" -> TRIAL
                "active" -> ACTIVE
                "past_due" -> PAST_DUE
                "suspended" -> SUSPENDED
                else -> UNKNOWN
            }
        }
    }
}

data class VenueAvailability(
    val venueStatus: VenueStatus,
    val subscriptionStatus: String,
    val available: Boolean,
    val reason: String?
)

object VenueAvailabilityResolver {
    fun resolve(venueStatus: VenueStatus, subscriptionStatus: SubscriptionStatus): VenueAvailability {
        if (venueStatus != VenueStatus.PUBLISHED) {
            return VenueAvailability(
                venueStatus = venueStatus,
                subscriptionStatus = subscriptionStatus.wire,
                available = false,
                reason = "VENUE_NOT_AVAILABLE"
            )
        }

        if (subscriptionStatus == SubscriptionStatus.PAST_DUE ||
            subscriptionStatus == SubscriptionStatus.SUSPENDED ||
            subscriptionStatus == SubscriptionStatus.UNKNOWN
        ) {
            return VenueAvailability(
                venueStatus = venueStatus,
                subscriptionStatus = subscriptionStatus.wire,
                available = false,
                reason = "SUBSCRIPTION_BLOCKED"
            )
        }

        return VenueAvailability(
            venueStatus = venueStatus,
            subscriptionStatus = subscriptionStatus.wire,
            available = true,
            reason = null
        )
    }
}
