package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.ServiceSuspendedException

class GuestVenuePolicy(private val mode: VenueVisibilityMode) {
    fun ensureVenueReadable(status: String) {
        when (status) {
            VenueStatuses.ACTIVE_PUBLISHED,
            VenueStatuses.ACTIVE_HIDDEN -> Unit
            VenueStatuses.SUSPENDED_BY_PLATFORM -> when (mode) {
                VenueVisibilityMode.EXPLAIN_SUSPENDED -> throw ServiceSuspendedException()
                VenueVisibilityMode.HIDE_SUSPENDED -> throw NotFoundException()
            }
            else -> throw NotFoundException()
        }
    }
}
