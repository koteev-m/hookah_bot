package com.hookah.platform.backend.miniapp.venue.promotions

import kotlinx.serialization.Serializable

@Serializable
data class VenuePromotionListResponse(
    val venueId: Long,
    val timezone: String,
    val items: List<VenuePromotionDto>,
)

@Serializable
data class VenuePromotionResponse(
    val promotion: VenuePromotionDto,
)

@Serializable
data class VenuePromotionDto(
    val id: Long,
    val title: String,
    val description: String,
    val terms: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val status: String,
)

@Serializable
data class VenuePromotionCreateRequest(
    val title: String,
    val description: String,
    val terms: String? = null,
    val startsAt: String,
    val endsAt: String,
)

@Serializable
data class VenuePromotionUpdateRequest(
    val title: String,
    val description: String,
    val terms: String? = null,
    val startsAt: String,
    val endsAt: String,
)

@Serializable
data class VenuePromotionStatusRequest(
    val status: String,
)
