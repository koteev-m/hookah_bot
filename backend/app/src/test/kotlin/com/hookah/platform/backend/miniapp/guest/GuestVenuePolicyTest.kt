package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.ServiceSuspendedException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GuestVenuePolicyTest {
    @Test
    fun `active statuses are allowed`() {
        val policy = GuestVenuePolicy(VenueVisibilityMode.EXPLAIN_SUSPENDED)

        policy.ensureVenueReadable(VenueStatuses.ACTIVE_PUBLISHED)
        policy.ensureVenueReadable(VenueStatuses.ACTIVE_HIDDEN)
    }

    @Test
    fun `suspended venue throws service suspended in explain mode`() {
        val policy = GuestVenuePolicy(VenueVisibilityMode.EXPLAIN_SUSPENDED)

        assertFailsWith<ServiceSuspendedException> {
            policy.ensureVenueReadable(VenueStatuses.SUSPENDED_BY_PLATFORM)
        }
    }

    @Test
    fun `suspended venue is hidden in hide mode`() {
        val policy = GuestVenuePolicy(VenueVisibilityMode.HIDE_SUSPENDED)

        assertFailsWith<NotFoundException> {
            policy.ensureVenueReadable(VenueStatuses.SUSPENDED_BY_PLATFORM)
        }
    }

    @Test
    fun `unknown status is treated as not found`() {
        val policy = GuestVenuePolicy(VenueVisibilityMode.EXPLAIN_SUSPENDED)

        assertFailsWith<NotFoundException> {
            policy.ensureVenueReadable("unknown")
        }
    }
}
