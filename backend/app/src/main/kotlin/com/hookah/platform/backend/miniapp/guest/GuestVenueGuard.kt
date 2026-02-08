package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.db.VenueShort
import com.hookah.platform.backend.miniapp.venue.VenueStatus

suspend fun ensureVenuePublishedForGuest(
    venueId: Long,
    guestVenueRepository: GuestVenueRepository,
): VenueShort {
    val venue = guestVenueRepository.findVenueByIdForGuest(venueId) ?: throw NotFoundException()
    if (venue.status != VenueStatus.PUBLISHED) {
        throw NotFoundException()
    }
    return venue
}
