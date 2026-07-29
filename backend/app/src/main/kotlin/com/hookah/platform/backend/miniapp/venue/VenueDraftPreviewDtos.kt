package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.miniapp.guest.api.GuestVenueDateExceptionDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVenuePromotionDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVenueScheduleDayDto
import com.hookah.platform.backend.miniapp.guest.api.VenueTodayScheduleDto
import kotlinx.serialization.Serializable

@Serializable
data class VenueDraftPreviewResponse(
    val previewMode: String,
    val venueStatus: String,
    val guestAvailable: Boolean,
    val unavailableReason: String,
    val publicCandidate: VenueDraftPreviewVenueDto,
    val infoSections: List<VenueDraftPreviewInfoSectionDto>,
    val mediaAvailableAfterPublication: Boolean,
)

@Serializable
data class VenueDraftPreviewVenueDto(
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
    val weeklyHours: List<GuestVenueScheduleDayDto>,
    val dateExceptions: List<GuestVenueDateExceptionDto>,
    val todayStaff: List<VenueDraftPreviewStaffDto>,
    val timezone: String,
    val promotions: List<GuestVenuePromotionDto>,
)

@Serializable
data class VenueDraftPreviewStaffDto(
    val displayName: String,
    val roleLabel: String? = null,
    val subtype: String,
    val bio: String? = null,
    val tags: List<String>,
    val shiftDate: String,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val shiftStatus: String,
)

@Serializable
data class VenueDraftPreviewInfoSectionDto(
    val displayTitle: String,
    val text: String,
)
