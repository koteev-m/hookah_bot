package com.hookah.platform.backend.miniapp.subscription

import com.hookah.platform.backend.miniapp.guest.VenueStatuses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VenueAvailabilityResolverTest {
    @Test
    fun `trial subscription with active venue is available`() {
        val availability = VenueAvailabilityResolver.resolve(
            venueStatus = VenueStatuses.ACTIVE_PUBLISHED,
            subscriptionStatus = SubscriptionStatus.TRIAL
        )

        assertTrue(availability.available)
        assertNull(availability.reason)
        assertEquals("trial", availability.subscriptionStatus)
    }

    @Test
    fun `past due subscription blocks active venue`() {
        val availability = VenueAvailabilityResolver.resolve(
            venueStatus = VenueStatuses.ACTIVE_PUBLISHED,
            subscriptionStatus = SubscriptionStatus.PAST_DUE
        )

        assertFalse(availability.available)
        assertEquals("SUBSCRIPTION_BLOCKED", availability.reason)
        assertEquals("past_due", availability.subscriptionStatus)
    }

    @Test
    fun `suspended venue blocks even active subscription`() {
        val availability = VenueAvailabilityResolver.resolve(
            venueStatus = VenueStatuses.SUSPENDED_BY_PLATFORM,
            subscriptionStatus = SubscriptionStatus.ACTIVE
        )

        assertFalse(availability.available)
        assertEquals("SERVICE_SUSPENDED", availability.reason)
        assertEquals("active", availability.subscriptionStatus)
    }
}
