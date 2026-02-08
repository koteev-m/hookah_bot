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
)

@Serializable
data class VenueResponse(val venue: VenueDto)

@Serializable
data class VenueDto(
    val id: Long,
    val name: String,
    val city: String? = null,
    val address: String? = null,
    val status: String,
)
