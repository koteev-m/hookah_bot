package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.miniapp.guest.api.VenueDto
import com.hookah.platform.backend.miniapp.guest.api.VenueInfoSectionDto
import kotlinx.serialization.Serializable

@Serializable
enum class VenueGuestPreviewMode {
    PUBLISHED_PUBLIC,
    PRIVATE_DRAFT,
}

@Serializable
enum class VenueGuestPreviewSource {
    SAVED_STATE,
}

@Serializable
data class VenueGuestPreviewResponse(
    val mode: VenueGuestPreviewMode,
    val venueAvailabilityLabel: String?,
    val venue: VenueDto,
    val infoSections: List<VenueInfoSectionDto>,
    val source: VenueGuestPreviewSource,
)
