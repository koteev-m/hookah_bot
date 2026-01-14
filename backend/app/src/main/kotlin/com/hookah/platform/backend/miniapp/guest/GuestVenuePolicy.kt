package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.ServiceSuspendedException

class GuestVenuePolicy(private val mode: VenueVisibilityMode) {
    fun ensureVenueReadable(status: String) {
        when (status) {
            "active_published",
            "active_hidden" -> Unit
            "suspended_by_platform" -> when (mode) {
                VenueVisibilityMode.EXPLAIN_SUSPENDED -> throw ServiceSuspendedException()
                VenueVisibilityMode.HIDE_SUSPENDED -> throw NotFoundException()
            }
            else -> throw NotFoundException()
        }
    }
}
