package com.hookah.platform.backend.miniapp.subscription

import com.hookah.platform.backend.miniapp.venue.VenueStatus
import java.util.Locale

enum class SubscriptionStatus(val wire: String) {
    TRIAL("trial"),
    ACTIVE("active"),
    PAST_DUE("past_due"),
    CANCELED("canceled"),
    SUSPENDED("suspended"),
    SUSPENDED_BY_PLATFORM("suspended_by_platform"),
    UNKNOWN("unknown"),
    ;

    companion object {
        private val blockedForGuest =
            setOf(
                CANCELED,
                SUSPENDED,
                SUSPENDED_BY_PLATFORM,
                UNKNOWN,
            )
        val blockedDbValues: List<String> = blockedForGuest.map { it.wire }

        fun fromDb(value: String?): SubscriptionStatus {
            return when (value?.lowercase(Locale.ROOT)) {
                "trial" -> TRIAL
                "active" -> ACTIVE
                "past_due" -> PAST_DUE
                "canceled", "cancelled" -> CANCELED
                "suspended" -> SUSPENDED
                "suspended_by_platform" -> SUSPENDED_BY_PLATFORM
                else -> UNKNOWN
            }
        }
    }

    fun isBlockedForGuest(): Boolean = blockedForGuest.contains(this)
}

data class VenueAvailability(
    val venueStatus: VenueStatus,
    val subscriptionStatus: String,
    val available: Boolean,
    val reason: String?,
)

object VenueAvailabilityResolver {
    fun resolve(
        venueStatus: VenueStatus,
        subscriptionStatus: SubscriptionStatus,
    ): VenueAvailability {
        if (venueStatus != VenueStatus.PUBLISHED) {
            return VenueAvailability(
                venueStatus = venueStatus,
                subscriptionStatus = subscriptionStatus.wire,
                available = false,
                reason = "VENUE_NOT_AVAILABLE",
            )
        }

        if (subscriptionStatus.isBlockedForGuest()) {
            return VenueAvailability(
                venueStatus = venueStatus,
                subscriptionStatus = subscriptionStatus.wire,
                available = false,
                reason = "SUBSCRIPTION_BLOCKED",
            )
        }

        return VenueAvailability(
            venueStatus = venueStatus,
            subscriptionStatus = subscriptionStatus.wire,
            available = true,
            reason = null,
        )
    }
}
