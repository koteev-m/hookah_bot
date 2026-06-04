package com.hookah.platform.backend.miniapp.guest.api

import kotlinx.serialization.Serializable

@Serializable
data class GuestFavoriteVenuesResponse(
    val venues: List<GuestFavoriteVenueDto>,
)

@Serializable
data class GuestFavoriteVenueDto(
    val venueId: Long,
    val name: String,
    val city: String? = null,
    val address: String? = null,
)

@Serializable
data class GuestFavoriteItemsResponse(
    val items: List<GuestFavoriteItemDto>,
)

@Serializable
data class GuestFavoriteItemDto(
    val itemId: Long,
    val venueId: Long,
    val categoryId: Long,
    val name: String,
    val priceMinor: Long,
    val currency: String,
)

@Serializable
data class GuestFavoriteMutationResponse(
    val ok: Boolean,
)
