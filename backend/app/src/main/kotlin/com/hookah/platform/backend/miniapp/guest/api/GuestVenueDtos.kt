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
    val todaySchedule: VenueTodayScheduleDto? = null,
    val isFavorite: Boolean,
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
    val todaySchedule: VenueTodayScheduleDto? = null,
    val todayStaff: List<GuestTodayStaffDto> = emptyList(),
    val status: String,
    val isFavorite: Boolean,
)

@Serializable
data class GuestTodayStaffResponse(
    val venueId: Long,
    val staff: List<GuestTodayStaffDto>,
)

@Serializable
data class GuestTodayStaffDto(
    val id: Long,
    val displayName: String,
    val roleLabel: String? = null,
    val subtype: String,
    val photoRef: String? = null,
    val bio: String? = null,
    val tags: List<String> = emptyList(),
    val shiftDate: String,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val shiftStatus: String,
)

@Serializable
data class VenueTodayScheduleDto(
    val date: String,
    val opensAt: String? = null,
    val closesAt: String? = null,
    val isConfigured: Boolean = true,
    val isClosed: Boolean,
    val isOpenNow: Boolean,
    val statusLabel: String,
    val timeLabel: String? = null,
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
