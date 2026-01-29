package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.SubscriptionBlockedException
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.subscription.VenueAvailabilityResolver
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository

suspend fun ensureGuestActionAvailable(
    venueId: Long,
    guestVenueRepository: GuestVenueRepository,
    subscriptionRepository: SubscriptionRepository
) {
    val venue = ensureVenuePublishedForGuest(venueId, guestVenueRepository)
    val subscriptionStatus = subscriptionRepository.getSubscriptionStatus(venueId)
    val availability = VenueAvailabilityResolver.resolve(venue.status, subscriptionStatus)
    if (availability.available) {
        return
    }
    when (availability.reason) {
        "SUBSCRIPTION_BLOCKED" -> throw SubscriptionBlockedException()
        else -> throw NotFoundException()
    }
}
