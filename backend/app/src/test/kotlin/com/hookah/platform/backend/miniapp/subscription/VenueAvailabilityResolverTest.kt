package com.hookah.platform.backend.miniapp.subscription

import com.hookah.platform.backend.miniapp.venue.VenueStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VenueAvailabilityResolverTest {
    @Test
    fun `trial subscription with active venue is available`() {
        val availability =
            VenueAvailabilityResolver.resolve(
                venueStatus = VenueStatus.PUBLISHED,
                subscriptionStatus = SubscriptionStatus.TRIAL,
            )

        assertTrue(availability.available)
        assertNull(availability.reason)
        assertEquals("trial", availability.subscriptionStatus)
    }

    @Test
    fun `past due subscription blocks active venue`() {
        val availability =
            VenueAvailabilityResolver.resolve(
                venueStatus = VenueStatus.PUBLISHED,
                subscriptionStatus = SubscriptionStatus.PAST_DUE,
            )

        assertFalse(availability.available)
        assertEquals("SUBSCRIPTION_BLOCKED", availability.reason)
        assertEquals("past_due", availability.subscriptionStatus)
    }

    @Test
    fun `suspended by platform blocks active venue`() {
        val availability =
            VenueAvailabilityResolver.resolve(
                venueStatus = VenueStatus.PUBLISHED,
                subscriptionStatus = SubscriptionStatus.SUSPENDED_BY_PLATFORM,
            )

        assertFalse(availability.available)
        assertEquals("SUBSCRIPTION_BLOCKED", availability.reason)
        assertEquals("suspended_by_platform", availability.subscriptionStatus)
    }

    @Test
    fun `suspended venue is not available even with active subscription`() {
        val availability =
            VenueAvailabilityResolver.resolve(
                venueStatus = VenueStatus.SUSPENDED,
                subscriptionStatus = SubscriptionStatus.ACTIVE,
            )

        assertFalse(availability.available)
        assertEquals("VENUE_NOT_AVAILABLE", availability.reason)
        assertEquals("active", availability.subscriptionStatus)
    }
}
