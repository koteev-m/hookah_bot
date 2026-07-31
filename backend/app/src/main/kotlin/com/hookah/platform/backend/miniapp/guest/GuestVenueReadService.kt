package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.location.VenueLocationDisplay
import com.hookah.platform.backend.location.buildYandexVenueRouteUrl
import com.hookah.platform.backend.location.formatVenueDisplayAddress
import com.hookah.platform.backend.miniapp.guest.api.GuestTodayStaffDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVenueDateExceptionDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVenuePromotionDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVenueScheduleDayDto
import com.hookah.platform.backend.miniapp.guest.api.VenueDto
import com.hookah.platform.backend.miniapp.guest.api.VenueInfoSectionDto
import com.hookah.platform.backend.miniapp.guest.api.VenueInfoSectionMediaDto
import com.hookah.platform.backend.miniapp.guest.api.VenueInfoSectionsResponse
import com.hookah.platform.backend.miniapp.guest.api.VenueResponse
import com.hookah.platform.backend.miniapp.guest.api.VenueTodayScheduleDto
import com.hookah.platform.backend.miniapp.guest.db.GuestFavoritesRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.db.VenueShort
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.miniapp.venue.containsOpenInstant
import com.hookah.platform.backend.miniapp.venue.formatScheduleRange
import com.hookah.platform.backend.miniapp.venue.formatScheduleTime
import com.hookah.platform.backend.miniapp.venue.staff.PublicVenueStaffToday
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffProfileRepository
import com.hookah.platform.backend.telegram.db.VenueBookingDateOverride
import com.hookah.platform.backend.telegram.db.VenueBookingHours
import com.hookah.platform.backend.telegram.db.VenueBookingHoursRepository
import com.hookah.platform.backend.telegram.db.VenueInfoSection
import com.hookah.platform.backend.telegram.db.VenueInfoSectionMediaAttachment
import com.hookah.platform.backend.telegram.db.VenueInfoSectionMediaRepository
import com.hookah.platform.backend.telegram.db.VenueInfoSectionsRepository
import com.hookah.platform.backend.telegram.db.VenuePromotion
import com.hookah.platform.backend.telegram.db.VenuePromotionRepository
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

internal enum class GuestVenueReadVisibility {
    PUBLISHED_PUBLIC,
    PRIVATE_DRAFT,
}

class GuestVenueReadService(
    private val guestVenueRepository: GuestVenueRepository,
    private val guestFavoritesRepository: GuestFavoritesRepository,
    private val venueStaffProfileRepository: VenueStaffProfileRepository,
    private val venueInfoSectionsRepository: VenueInfoSectionsRepository,
    private val venueInfoSectionMediaRepository: VenueInfoSectionMediaRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val venueBookingHoursRepository: VenueBookingHoursRepository,
    private val venueSettingsRepository: VenueSettingsRepository,
    private val venuePromotionRepository: VenuePromotionRepository,
) {
    suspend fun getVenue(
        userId: Long,
        venueId: Long,
    ): VenueResponse {
        val venue = ensureGuestBrowseAvailable(venueId, guestVenueRepository, subscriptionRepository)
        return assembleVenue(
            venue = venue,
            userId = userId,
            visibility = GuestVenueReadVisibility.PUBLISHED_PUBLIC,
        )
    }

    internal suspend fun getVenueForPrivatePreview(venueId: Long): VenueResponse {
        val venue = guestVenueRepository.findVenueByIdForGuest(venueId) ?: throw NotFoundException()
        if (venue.status !in PRIVATE_PREVIEW_VENUE_STATUSES) {
            throw NotFoundException()
        }
        return assembleVenue(
            venue = venue,
            userId = null,
            visibility = GuestVenueReadVisibility.PRIVATE_DRAFT,
        )
    }

    private suspend fun assembleVenue(
        venue: VenueShort,
        userId: Long?,
        visibility: GuestVenueReadVisibility,
    ): VenueResponse {
        val isFavorite =
            if (visibility == GuestVenueReadVisibility.PUBLISHED_PUBLIC && userId != null) {
                guestFavoritesRepository.isVenueFavorite(userId = userId, venueId = venue.id)
            } else {
                false
            }
        val zoneId =
            venueSettingsRepository.resolveZoneId(
                venueId = venue.id,
                fallback = ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE),
            )
        val today = LocalDateTime.ofInstant(Instant.now(), zoneId).toLocalDate()
        val weeklyHours = venueBookingHoursRepository.listWeeklyHours(venue.id)
        val dateExceptions =
            venueBookingHoursRepository.listDateOverrides(
                venueId = venue.id,
                limit = MAX_GUEST_DATE_EXCEPTIONS,
                fromDate = today,
            )
        val todayStaff = buildTodayStaff(venue.id, today)
        val promotions =
            when (visibility) {
                GuestVenueReadVisibility.PUBLISHED_PUBLIC ->
                    venuePromotionRepository.listActivePromotionsForVenue(venue.id)
                GuestVenueReadVisibility.PRIVATE_DRAFT ->
                    venuePromotionRepository.listActivePromotionsForPrivatePreview(venue.id)
            }
        return VenueResponse(
            venue =
                venue.toVenueDto(
                    todaySchedule = buildTodaySchedules(mapOf(venue.id to zoneId)).getValue(venue.id),
                    weeklyHours = weeklyHours.map { it.toGuestDto() },
                    dateExceptions = dateExceptions.map { it.toGuestDto() },
                    todayStaff = todayStaff,
                    timezone = zoneId.id,
                    promotions = promotions.map { it.toGuestDto() },
                    isFavorite = isFavorite,
                ),
        )
    }

    suspend fun getInfoSections(venueId: Long): VenueInfoSectionsResponse {
        ensureGuestBrowseAvailable(venueId, guestVenueRepository, subscriptionRepository)
        return assembleInfoSections(venueId, GuestVenueReadVisibility.PUBLISHED_PUBLIC)
    }

    internal suspend fun getInfoSectionsForPrivatePreview(venueId: Long): VenueInfoSectionsResponse =
        assembleInfoSections(venueId, GuestVenueReadVisibility.PRIVATE_DRAFT)

    private suspend fun assembleInfoSections(
        venueId: Long,
        visibility: GuestVenueReadVisibility,
    ): VenueInfoSectionsResponse {
        val visibleSections = venueInfoSectionsRepository.listSections(venueId).filter { it.isVisible }
        val mediaCounts = venueInfoSectionMediaRepository.countBySectionIds(visibleSections.map { it.id })
        val filledSections =
            visibleSections.filter { section ->
                section.textContent?.isNotBlank() == true || (mediaCounts[section.id] ?: 0) > 0
            }
        val sectionDtos =
            filledSections.map { section ->
                val media =
                    if ((mediaCounts[section.id] ?: 0) > 0) {
                        venueInfoSectionMediaRepository.listBySectionId(section.id).map {
                            it.toDto(venueId, section.id, visibility)
                        }
                    } else {
                        emptyList()
                    }
                section.toGuestInfoDto(media)
            }
        return VenueInfoSectionsResponse(venueId = venueId, sections = sectionDtos)
    }

    suspend fun getTodayStaff(venueId: Long): List<GuestTodayStaffDto> {
        ensureGuestBrowseAvailable(venueId, guestVenueRepository, subscriptionRepository)
        val zoneId = venueSettingsRepository.resolveZoneId(venueId)
        val today = LocalDateTime.ofInstant(Instant.now(), zoneId).toLocalDate()
        return buildTodayStaff(venueId, today)
    }

    suspend fun getTodaySchedule(venueId: Long): VenueTodayScheduleDto {
        return getTodaySchedules(listOf(venueId)).getValue(venueId)
    }

    suspend fun getTodaySchedules(venueIds: Collection<Long>): Map<Long, VenueTodayScheduleDto> {
        val zoneIds = venueSettingsRepository.resolveZoneIds(venueIds)
        return buildTodaySchedules(zoneIds)
    }

    private suspend fun buildTodaySchedules(zoneIds: Map<Long, ZoneId>): Map<Long, VenueTodayScheduleDto> {
        if (zoneIds.isEmpty()) return emptyMap()
        val now = Instant.now()
        val localDateTimes = zoneIds.mapValues { (_, zoneId) -> LocalDateTime.ofInstant(now, zoneId) }
        val datesByVenueId =
            localDateTimes.mapValues { (_, localDateTime) ->
                val today = localDateTime.toLocalDate()
                setOf(today, today.minusDays(1))
            }
        val hoursByVenueId = venueBookingHoursRepository.findByVenuesAndDates(datesByVenueId)
        return localDateTimes.mapValues { (venueId, localDateTime) ->
            val today = localDateTime.toLocalDate()
            buildTodaySchedule(
                localDateTime = localDateTime,
                todayHours = hoursByVenueId[venueId]?.get(today),
                previousHours = hoursByVenueId[venueId]?.get(today.minusDays(1)),
            )
        }
    }

    private fun buildTodaySchedule(
        localDateTime: LocalDateTime,
        todayHours: VenueBookingHours?,
        previousHours: VenueBookingHours?,
    ): VenueTodayScheduleDto {
        val today = localDateTime.toLocalDate()
        val previousDate = today.minusDays(1)
        val previousOpenNow = previousHours?.containsOpenInstant(previousDate, localDateTime) == true
        val todayOpenNow = todayHours?.containsOpenInstant(today, localDateTime) == true
        val hours =
            when {
                previousOpenNow -> previousHours
                todayHours != null -> todayHours
                else -> null
            }
        if (hours == null) {
            return VenueTodayScheduleDto(
                date = today.toString(),
                isConfigured = false,
                isClosed = false,
                isOpenNow = false,
                statusLabel = "График не указан",
                timeLabel = null,
            )
        }
        val isOpenNow = previousOpenNow || todayOpenNow
        return VenueTodayScheduleDto(
            date = today.toString(),
            opensAt = if (hours.isClosed) null else formatScheduleTime(hours.opensAt),
            closesAt = if (hours.isClosed) null else formatScheduleTime(hours.closesAt),
            isConfigured = true,
            isClosed = hours.isClosed,
            isOpenNow = isOpenNow,
            statusLabel =
                when {
                    hours.isClosed -> "Закрыто сегодня"
                    isOpenNow -> "Открыто сейчас"
                    else -> "Закрыто сейчас"
                },
            timeLabel = formatScheduleRange(hours.opensAt, hours.closesAt, hours.isClosed),
        )
    }

    private suspend fun buildTodayStaff(
        venueId: Long,
        today: LocalDate,
    ): List<GuestTodayStaffDto> =
        venueStaffProfileRepository.listPublicTodayStaff(venueId, today).map { it.toGuestDto() }

    private companion object {
        const val MAX_GUEST_DATE_EXCEPTIONS = 100
        val PRIVATE_PREVIEW_VENUE_STATUSES =
            setOf(
                VenueStatus.DRAFT,
                VenueStatus.PUBLISHED,
                VenueStatus.HIDDEN,
                VenueStatus.PAUSED,
                VenueStatus.SUSPENDED,
            )
    }
}

private fun VenueShort.toVenueDto(
    todaySchedule: VenueTodayScheduleDto?,
    weeklyHours: List<GuestVenueScheduleDayDto>,
    dateExceptions: List<GuestVenueDateExceptionDto>,
    todayStaff: List<GuestTodayStaffDto>,
    timezone: String,
    promotions: List<GuestVenuePromotionDto>,
    isFavorite: Boolean,
): VenueDto =
    VenueDto(
        id = id,
        name = name,
        city = city,
        address = address,
        countryCode = countryCode,
        formattedAddress = formattedAddress,
        displayAddress = formatVenueDisplayAddress(locationDisplay()),
        latitude = latitude,
        longitude = longitude,
        routeUrl = buildYandexVenueRouteUrl(locationDisplay()),
        guestContact = guestContact,
        cardDescription = cardDescription,
        todaySchedule = todaySchedule,
        weeklyHours = weeklyHours,
        dateExceptions = dateExceptions,
        todayStaff = todayStaff,
        timezone = timezone,
        promotions = promotions,
        status = status.dbValue,
        isFavorite = isFavorite,
    )

private fun VenueShort.locationDisplay(): VenueLocationDisplay =
    VenueLocationDisplay(
        name = name,
        countryCode = countryCode,
        city = city,
        address = address,
        formattedAddress = formattedAddress,
        latitude = latitude,
        longitude = longitude,
    )

private fun VenueBookingHours.toGuestDto(): GuestVenueScheduleDayDto =
    GuestVenueScheduleDayDto(
        weekday = weekday,
        opensAt = formatScheduleTime(opensAt),
        closesAt = formatScheduleTime(closesAt),
        isClosed = isClosed,
    )

private fun VenueBookingDateOverride.toGuestDto(): GuestVenueDateExceptionDto =
    GuestVenueDateExceptionDto(
        serviceDate = serviceDate.toString(),
        opensAt = formatScheduleTime(opensAt),
        closesAt = formatScheduleTime(closesAt),
        isClosed = isClosed,
        guestNote = guestNote,
    )

private fun VenuePromotion.toGuestDto(): GuestVenuePromotionDto =
    GuestVenuePromotionDto(
        id = id,
        title = title,
        description = description,
        terms = terms,
        startsAt = startsAt?.toString(),
        endsAt = endsAt?.toString(),
        templateType = templateType.dbValue,
    )

private fun PublicVenueStaffToday.toGuestDto(): GuestTodayStaffDto =
    GuestTodayStaffDto(
        id = id,
        displayName = displayName,
        roleLabel = roleLabel,
        subtype = subtype,
        photoRef = null,
        bio = bio,
        tags = tags,
        shiftDate = shiftDate.toString(),
        startsAt = startsAt?.toString(),
        endsAt = endsAt?.toString(),
        shiftStatus = shiftStatus,
    )

private fun VenueInfoSection.toGuestInfoDto(media: List<VenueInfoSectionMediaDto>): VenueInfoSectionDto =
    VenueInfoSectionDto(
        id = id,
        type = sectionType,
        title = title,
        displayTitle =
            when (sectionType) {
                "menu" -> "📖 Фото-меню"
                else -> title
            },
        text = textContent?.trim()?.takeIf { it.isNotEmpty() },
        mediaCount = media.size,
        media = media,
    )

private fun VenueInfoSectionMediaAttachment.toDto(
    venueId: Long,
    sectionId: Long,
    visibility: GuestVenueReadVisibility,
): VenueInfoSectionMediaDto =
    VenueInfoSectionMediaDto(
        id = id,
        mediaType = mediaType,
        sortOrder = sortOrder,
        url =
            when (visibility) {
                GuestVenueReadVisibility.PUBLISHED_PUBLIC ->
                    "/api/guest/venue/$venueId/info-sections/$sectionId/media/$id"
                GuestVenueReadVisibility.PRIVATE_DRAFT ->
                    "/api/venue/$venueId/guest-preview/info-sections/$sectionId/media/$id"
            },
    )
