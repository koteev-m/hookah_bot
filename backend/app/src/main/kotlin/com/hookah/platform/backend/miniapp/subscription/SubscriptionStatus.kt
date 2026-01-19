package com.hookah.platform.backend.miniapp.subscription

import com.hookah.platform.backend.miniapp.guest.VenueStatuses

enum class SubscriptionStatus(val wire: String) {
    TRIAL("trial"),
    ACTIVE("active"),
    PAST_DUE("past_due"),
    SUSPENDED("suspended"),
    UNKNOWN("unknown");

    companion object {
        fun fromDb(value: String?): SubscriptionStatus {
            return when (value?.lowercase()) {
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
    val venueStatus: String,
    val subscriptionStatus: String,
    val available: Boolean,
    val reason: String?
)

object VenueAvailabilityResolver {
    fun resolve(venueStatus: String, subscriptionStatus: SubscriptionStatus): VenueAvailability {
        if (venueStatus == VenueStatuses.SUSPENDED_BY_PLATFORM) {
            return VenueAvailability(
                venueStatus = venueStatus,
                subscriptionStatus = subscriptionStatus.wire,
                available = false,
                reason = "SERVICE_SUSPENDED"
            )
        }

        if (venueStatus != VenueStatuses.ACTIVE_PUBLISHED && venueStatus != VenueStatuses.ACTIVE_HIDDEN) {
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
