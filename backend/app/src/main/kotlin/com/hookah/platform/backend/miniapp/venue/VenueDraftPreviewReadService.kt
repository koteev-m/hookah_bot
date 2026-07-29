package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.location.VenueLocationDisplay
import com.hookah.platform.backend.location.buildYandexVenueRouteUrl
import com.hookah.platform.backend.location.formatVenueDisplayAddress
import com.hookah.platform.backend.miniapp.guest.GuestVenueReadService
import com.hookah.platform.backend.miniapp.guest.api.GuestVenueDateExceptionDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVenuePromotionDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVenueScheduleDayDto
import com.hookah.platform.backend.miniapp.guest.api.VenueTodayScheduleDto
import com.hookah.platform.backend.miniapp.venue.staff.PublicVenueStaffToday
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffProfileRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueBookingDateOverride
import com.hookah.platform.backend.telegram.db.VenueBookingHours
import com.hookah.platform.backend.telegram.db.VenueBookingHoursRepository
import com.hookah.platform.backend.telegram.db.VenueInfoSectionMediaRepository
import com.hookah.platform.backend.telegram.db.VenueInfoSectionsRepository
import com.hookah.platform.backend.telegram.db.VenuePromotion
import com.hookah.platform.backend.telegram.db.VenuePromotionRepository
import com.hookah.platform.backend.telegram.db.VenuePublicCardSettings
import com.hookah.platform.backend.telegram.db.VenueRepository
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

const val DRAFT_PREVIEW_UNAVAILABLE_MESSAGE = "Предпросмотр черновика недоступен."

class VenueDraftPreviewReadService(
    private val venueAccessRepository: VenueAccessRepository,
    private val venueRepository: VenueRepository,
    private val venueBookingHoursRepository: VenueBookingHoursRepository,
    private val venueSettingsRepository: VenueSettingsRepository,
    private val venueStaffProfileRepository: VenueStaffProfileRepository,
    private val venueInfoSectionsRepository: VenueInfoSectionsRepository,
    private val venueInfoSectionMediaRepository: VenueInfoSectionMediaRepository,
    private val venuePromotionRepository: VenuePromotionRepository,
    private val guestVenueReadService: GuestVenueReadService,
) {
    suspend fun getDraftPreview(
        userId: Long,
        venueId: Long,
    ): VenueDraftPreviewResponse {
        val membership = venueAccessRepository.findVenueMembershipIncludingDeleted(userId, venueId)
        if (membership == null) {
            if (venueRepository.findPublicCardSettings(venueId) == null) {
                throw NotFoundException(DRAFT_PREVIEW_UNAVAILABLE_MESSAGE)
            }
            throw ForbiddenException()
        }

        val status =
            VenueStatus.fromDb(membership.venueStatus)
                ?: throw NotFoundException(DRAFT_PREVIEW_UNAVAILABLE_MESSAGE)
        if (status == VenueStatus.DELETED) {
            throw NotFoundException(DRAFT_PREVIEW_UNAVAILABLE_MESSAGE)
        }
        val role = VenueRoleMapping.fromDb(membership.role) ?: throw ForbiddenException()
        if (role !in setOf(VenueRole.OWNER, VenueRole.MANAGER)) {
            throw ForbiddenException()
        }
        if (status != VenueStatus.DRAFT) {
            throw NotFoundException(DRAFT_PREVIEW_UNAVAILABLE_MESSAGE)
        }

        val publicCard =
            venueRepository.findPublicCardSettings(venueId)
                ?: throw NotFoundException(DRAFT_PREVIEW_UNAVAILABLE_MESSAGE)
        val now = Instant.now()
        val zoneId =
            venueSettingsRepository.resolveZoneId(
                venueId = venueId,
                fallback = ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE),
            )
        val today = LocalDateTime.ofInstant(now, zoneId).toLocalDate()
        val weeklyHours = venueBookingHoursRepository.listWeeklyHours(venueId)
        val dateExceptions =
            venueBookingHoursRepository.listDateOverrides(
                venueId = venueId,
                limit = MAX_DRAFT_PREVIEW_DATE_EXCEPTIONS,
                fromDate = today,
            )
        val todayStaff = venueStaffProfileRepository.listPublicTodayStaff(venueId, today)
        val promotions =
            venuePromotionRepository.listActivePromotionsForDraftPreview(
                venueId = venueId,
                now = now,
            )
        val visibleSections = venueInfoSectionsRepository.listSections(venueId).filter { it.isVisible }
        val mediaCounts = venueInfoSectionMediaRepository.countBySectionIds(visibleSections.map { it.id })
        val textSections =
            visibleSections.mapNotNull { section ->
                val text = section.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                VenueDraftPreviewInfoSectionDto(
                    displayTitle =
                        when (section.sectionType) {
                            "menu" -> "📖 Фото-меню"
                            else -> section.title
                        },
                    text = text,
                )
            }

        return VenueDraftPreviewResponse(
            previewMode = VenueStatus.DRAFT.dbValue,
            venueStatus = VenueStatus.DRAFT.dbValue,
            guestAvailable = false,
            unavailableReason = "VENUE_NOT_PUBLISHED",
            publicCandidate =
                publicCard.toDraftPreviewDto(
                    todaySchedule = guestVenueReadService.getTodaySchedule(venueId),
                    weeklyHours = weeklyHours.map { it.toDraftPreviewDto() },
                    dateExceptions = dateExceptions.map { it.toDraftPreviewDto() },
                    todayStaff = todayStaff.map { it.toDraftPreviewDto() },
                    timezone = zoneId.id,
                    promotions = promotions.map { it.toDraftPreviewDto() },
                ),
            infoSections = textSections,
            mediaAvailableAfterPublication =
                visibleSections.any { section -> (mediaCounts[section.id] ?: 0) > 0 },
        )
    }

    private companion object {
        const val MAX_DRAFT_PREVIEW_DATE_EXCEPTIONS = 100
    }
}

private fun VenuePublicCardSettings.toDraftPreviewDto(
    todaySchedule: VenueTodayScheduleDto,
    weeklyHours: List<GuestVenueScheduleDayDto>,
    dateExceptions: List<GuestVenueDateExceptionDto>,
    todayStaff: List<VenueDraftPreviewStaffDto>,
    timezone: String,
    promotions: List<GuestVenuePromotionDto>,
): VenueDraftPreviewVenueDto {
    val location =
        VenueLocationDisplay(
            name = name,
            countryCode = countryCode,
            city = city,
            address = address,
            formattedAddress = formattedAddress,
            latitude = latitude,
            longitude = longitude,
        )
    return VenueDraftPreviewVenueDto(
        id = venueId,
        name = name,
        city = city,
        address = address,
        countryCode = countryCode,
        formattedAddress = formattedAddress,
        displayAddress = formatVenueDisplayAddress(location),
        latitude = latitude,
        longitude = longitude,
        routeUrl = buildYandexVenueRouteUrl(location),
        guestContact = guestContact,
        cardDescription = cardDescription,
        todaySchedule = todaySchedule,
        weeklyHours = weeklyHours,
        dateExceptions = dateExceptions,
        todayStaff = todayStaff,
        timezone = timezone,
        promotions = promotions,
    )
}

private fun VenueBookingHours.toDraftPreviewDto(): GuestVenueScheduleDayDto =
    GuestVenueScheduleDayDto(
        weekday = weekday,
        opensAt = formatScheduleTime(opensAt),
        closesAt = formatScheduleTime(closesAt),
        isClosed = isClosed,
    )

private fun VenueBookingDateOverride.toDraftPreviewDto(): GuestVenueDateExceptionDto =
    GuestVenueDateExceptionDto(
        serviceDate = serviceDate.toString(),
        opensAt = formatScheduleTime(opensAt),
        closesAt = formatScheduleTime(closesAt),
        isClosed = isClosed,
        guestNote = guestNote,
    )

private fun PublicVenueStaffToday.toDraftPreviewDto(): VenueDraftPreviewStaffDto =
    VenueDraftPreviewStaffDto(
        displayName = displayName,
        roleLabel = roleLabel,
        subtype = subtype,
        bio = bio,
        tags = tags,
        shiftDate = shiftDate.toString(),
        startsAt = startsAt?.toString(),
        endsAt = endsAt?.toString(),
        shiftStatus = shiftStatus,
    )

private fun VenuePromotion.toDraftPreviewDto(): GuestVenuePromotionDto =
    GuestVenuePromotionDto(
        id = id,
        title = title,
        description = description,
        terms = terms,
        startsAt = startsAt?.toString(),
        endsAt = endsAt?.toString(),
        templateType = templateType.dbValue,
    )
