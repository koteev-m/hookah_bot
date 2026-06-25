package com.hookah.platform.backend.miniapp.guest.api

import kotlinx.serialization.Serializable

@Serializable
data class CatalogResponse(val venues: List<CatalogVenueDto>)

@Serializable
data class CatalogVenueDto(
    val id: Long,
    val name: String,
    val city: String? = null,
    val address: String? = null,
    val countryCode: String? = null,
    val formattedAddress: String? = null,
    val displayAddress: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val routeUrl: String? = null,
    val guestContact: String? = null,
    val cardDescription: String? = null,
)

@Serializable
data class VenueResponse(val venue: VenueDto)

@Serializable
data class VenueDto(
    val id: Long,
    val name: String,
    val city: String? = null,
    val address: String? = null,
    val countryCode: String? = null,
    val formattedAddress: String? = null,
    val displayAddress: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val routeUrl: String? = null,
    val guestContact: String? = null,
    val cardDescription: String? = null,
    val status: String,
)

@Serializable
data class VenueInfoSectionsResponse(
    val venueId: Long,
    val sections: List<VenueInfoSectionDto>,
)

@Serializable
data class VenueInfoSectionDto(
    val id: Long,
    val type: String,
    val title: String,
    val displayTitle: String,
    val text: String? = null,
    val mediaCount: Int,
    val media: List<VenueInfoSectionMediaDto>,
)

@Serializable
data class VenueInfoSectionMediaDto(
    val id: Long,
    val mediaType: String,
    val sortOrder: Int,
    val url: String,
)
