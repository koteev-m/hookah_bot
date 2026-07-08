package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.ConfigException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.location.VenueLocationDisplay
import com.hookah.platform.backend.location.buildYandexVenueRouteUrl
import com.hookah.platform.backend.location.formatVenueDisplayAddress
import com.hookah.platform.backend.miniapp.guest.api.CatalogResponse
import com.hookah.platform.backend.miniapp.guest.api.CatalogVenueDto
import com.hookah.platform.backend.miniapp.guest.api.GuestTodayStaffDto
import com.hookah.platform.backend.miniapp.guest.api.GuestTodayStaffResponse
import com.hookah.platform.backend.miniapp.guest.api.MenuCategoryDto
import com.hookah.platform.backend.miniapp.guest.api.MenuItemDto
import com.hookah.platform.backend.miniapp.guest.api.MenuItemOptionDto
import com.hookah.platform.backend.miniapp.guest.api.MenuResponse
import com.hookah.platform.backend.miniapp.guest.api.VenueDto
import com.hookah.platform.backend.miniapp.guest.api.VenueInfoSectionDto
import com.hookah.platform.backend.miniapp.guest.api.VenueInfoSectionMediaDto
import com.hookah.platform.backend.miniapp.guest.api.VenueInfoSectionsResponse
import com.hookah.platform.backend.miniapp.guest.api.VenueResponse
import com.hookah.platform.backend.miniapp.guest.api.VenueTodayScheduleDto
import com.hookah.platform.backend.miniapp.guest.db.GuestMenuRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.db.MenuCategoryModel
import com.hookah.platform.backend.miniapp.guest.db.MenuItemModel
import com.hookah.platform.backend.miniapp.guest.db.MenuItemOptionModel
import com.hookah.platform.backend.miniapp.guest.db.MenuModel
import com.hookah.platform.backend.miniapp.guest.db.VenueShort
import com.hookah.platform.backend.miniapp.guest.db.effectiveType
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.containsOpenInstant
import com.hookah.platform.backend.miniapp.venue.formatScheduleRange
import com.hookah.platform.backend.miniapp.venue.formatScheduleTime
import com.hookah.platform.backend.miniapp.venue.staff.PublicVenueStaffToday
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffProfileRepository
import com.hookah.platform.backend.telegram.TelegramDownloadedFile
import com.hookah.platform.backend.telegram.db.VenueBookingHoursRepository
import com.hookah.platform.backend.telegram.db.VenueInfoSection
import com.hookah.platform.backend.telegram.db.VenueInfoSectionMediaAttachment
import com.hookah.platform.backend.telegram.db.VenueInfoSectionMediaRepository
import com.hookah.platform.backend.telegram.db.VenueInfoSectionsRepository
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Instant
import java.time.LocalDateTime

fun Route.guestVenueRoutes(
    guestVenueRepository: GuestVenueRepository,
    guestMenuRepository: GuestMenuRepository,
    venueStaffProfileRepository: VenueStaffProfileRepository,
    venueInfoSectionsRepository: VenueInfoSectionsRepository,
    venueInfoSectionMediaRepository: VenueInfoSectionMediaRepository,
    subscriptionRepository: SubscriptionRepository,
    venueBookingHoursRepository: VenueBookingHoursRepository,
    venueSettingsRepository: VenueSettingsRepository,
) {
    get("/catalog") {
        val venues = guestVenueRepository.listCatalogVenues()
        val schedules =
            venues.associate { venue ->
                venue.id to
                    buildGuestTodaySchedule(
                        venueId = venue.id,
                        venueBookingHoursRepository = venueBookingHoursRepository,
                        venueSettingsRepository = venueSettingsRepository,
                    )
            }
        call.respond(CatalogResponse(venues = venues.map { it.toCatalogDto(schedules[it.id]) }))
    }

    get("/venue/{id}") {
        val rawId = call.parameters["id"] ?: throw InvalidInputException("id is required")
        val venueId = rawId.toLongOrNull() ?: throw InvalidInputException("id must be a number")
        val venue = ensureGuestBrowseAvailable(venueId, guestVenueRepository, subscriptionRepository)
        val todaySchedule =
            buildGuestTodaySchedule(
                venueId = venue.id,
                venueBookingHoursRepository = venueBookingHoursRepository,
                venueSettingsRepository = venueSettingsRepository,
            )
        val todayStaff =
            buildGuestTodayStaff(
                venueId = venue.id,
                venueStaffProfileRepository = venueStaffProfileRepository,
                venueSettingsRepository = venueSettingsRepository,
            )
        call.respond(VenueResponse(venue = venue.toVenueDto(todaySchedule, todayStaff)))
    }

    get("/venue/{id}/today-staff") {
        val rawId = call.parameters["id"] ?: throw InvalidInputException("id is required")
        val venueId = rawId.toLongOrNull() ?: throw InvalidInputException("id must be a number")
        ensureGuestBrowseAvailable(venueId, guestVenueRepository, subscriptionRepository)
        val todayStaff =
            buildGuestTodayStaff(
                venueId = venueId,
                venueStaffProfileRepository = venueStaffProfileRepository,
                venueSettingsRepository = venueSettingsRepository,
            )
        call.respond(GuestTodayStaffResponse(venueId = venueId, staff = todayStaff))
    }

    get("/venue/{id}/info-sections") {
        val rawId = call.parameters["id"] ?: throw InvalidInputException("id is required")
        val venueId = rawId.toLongOrNull() ?: throw InvalidInputException("id must be a number")
        ensureGuestBrowseAvailable(venueId, guestVenueRepository, subscriptionRepository)
        val sections =
            buildGuestInfoSections(
                venueId = venueId,
                venueInfoSectionsRepository = venueInfoSectionsRepository,
                venueInfoSectionMediaRepository = venueInfoSectionMediaRepository,
            )
        call.respond(sections)
    }

    get("/venue/{id}/menu") {
        val rawId = call.parameters["id"] ?: throw InvalidInputException("id is required")
        val venueId = rawId.toLongOrNull() ?: throw InvalidInputException("id must be a number")
        ensureGuestBrowseAvailable(venueId, guestVenueRepository, subscriptionRepository)
        val menu = guestMenuRepository.getMenu(venueId)
        call.respond(menu.toResponse())
    }
}

fun Route.guestVenueInfoMediaRoutes(
    guestVenueRepository: GuestVenueRepository,
    venueInfoSectionsRepository: VenueInfoSectionsRepository,
    venueInfoSectionMediaRepository: VenueInfoSectionMediaRepository,
    subscriptionRepository: SubscriptionRepository,
    telegramFileDownloader: (suspend (String) -> TelegramDownloadedFile?)? = null,
) {
    get("/venue/{id}/info-sections/{sectionId}/media/{mediaId}") {
        val venueId = call.requireLongParameter("id")
        val sectionId = call.requireLongParameter("sectionId")
        val mediaId = call.requireLongParameter("mediaId")
        ensureGuestBrowseAvailable(venueId, guestVenueRepository, subscriptionRepository)

        val section =
            venueInfoSectionsRepository
                .findSectionById(venueId = venueId, sectionId = sectionId)
                ?.takeIf { it.isVisible }
                ?: throw NotFoundException()
        val media =
            venueInfoSectionMediaRepository.findById(sectionId = section.id, mediaId = mediaId)
                ?: throw NotFoundException()
        val downloader = telegramFileDownloader ?: throw ConfigException("Media proxy is not configured")
        val downloaded = downloader(media.telegramFileId) ?: throw NotFoundException()
        val contentType = downloaded.contentType.toGuestMediaContentType(media.mediaType)
        if (media.mediaType.equals("pdf", ignoreCase = true)) {
            call.response.headers.append(
                HttpHeaders.ContentDisposition,
                "inline; filename=\"venue-info-$mediaId.pdf\"",
            )
        }
        call.respondBytes(
            bytes = downloaded.bytes,
            contentType = contentType,
        )
    }
}

private suspend fun buildGuestTodaySchedule(
    venueId: Long,
    venueBookingHoursRepository: VenueBookingHoursRepository,
    venueSettingsRepository: VenueSettingsRepository,
): VenueTodayScheduleDto {
    val zoneId = venueSettingsRepository.resolveZoneId(venueId)
    val localDateTime = LocalDateTime.ofInstant(Instant.now(), zoneId)
    val today = localDateTime.toLocalDate()
    val todayHours = venueBookingHoursRepository.findByVenueAndDate(venueId, today)
    val previousDate = today.minusDays(1)
    val previousHours = venueBookingHoursRepository.findByVenueAndDate(venueId, previousDate)
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

private fun VenueShort.toCatalogDto(todaySchedule: VenueTodayScheduleDto?): CatalogVenueDto =
    CatalogVenueDto(
        id = id,
        name = name,
        city = city,
        address = address,
        countryCode = countryCode,
        formattedAddress = formattedAddress,
        displayAddress = displayAddress(),
        latitude = latitude,
        longitude = longitude,
        routeUrl = routeUrl(),
        guestContact = guestContact,
        cardDescription = cardDescription,
        todaySchedule = todaySchedule,
    )

private fun VenueShort.toVenueDto(
    todaySchedule: VenueTodayScheduleDto?,
    todayStaff: List<GuestTodayStaffDto> = emptyList(),
): VenueDto =
    VenueDto(
        id = id,
        name = name,
        city = city,
        address = address,
        countryCode = countryCode,
        formattedAddress = formattedAddress,
        displayAddress = displayAddress(),
        latitude = latitude,
        longitude = longitude,
        routeUrl = routeUrl(),
        guestContact = guestContact,
        cardDescription = cardDescription,
        todaySchedule = todaySchedule,
        todayStaff = todayStaff,
        status = status.dbValue,
    )

private fun VenueShort.displayAddress(): String? = formatVenueDisplayAddress(locationDisplay())

private fun VenueShort.routeUrl(): String = buildYandexVenueRouteUrl(locationDisplay())

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

private suspend fun buildGuestInfoSections(
    venueId: Long,
    venueInfoSectionsRepository: VenueInfoSectionsRepository,
    venueInfoSectionMediaRepository: VenueInfoSectionMediaRepository,
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
                    venueInfoSectionMediaRepository.listBySectionId(section.id).map { it.toDto(venueId, section.id) }
                } else {
                    emptyList()
                }
            section.toGuestInfoDto(media)
        }
    return VenueInfoSectionsResponse(venueId = venueId, sections = sectionDtos)
}

private suspend fun buildGuestTodayStaff(
    venueId: Long,
    venueStaffProfileRepository: VenueStaffProfileRepository,
    venueSettingsRepository: VenueSettingsRepository,
): List<GuestTodayStaffDto> {
    val zoneId = venueSettingsRepository.resolveZoneId(venueId)
    val today = LocalDateTime.ofInstant(Instant.now(), zoneId).toLocalDate()
    return venueStaffProfileRepository.listPublicTodayStaff(venueId, today).map { it.toGuestDto() }
}

private fun PublicVenueStaffToday.toGuestDto(): GuestTodayStaffDto =
    GuestTodayStaffDto(
        id = id,
        displayName = displayName,
        roleLabel = roleLabel,
        subtype = subtype,
        photoRef = photoRef,
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
        displayTitle = guestDisplayTitle(),
        text = textContent?.trim()?.takeIf { it.isNotEmpty() },
        mediaCount = media.size,
        media = media,
    )

private fun VenueInfoSection.guestDisplayTitle(): String =
    when (sectionType) {
        "menu" -> "📖 Фото-меню"
        else -> title
    }

private fun VenueInfoSectionMediaAttachment.toDto(
    venueId: Long,
    sectionId: Long,
): VenueInfoSectionMediaDto =
    VenueInfoSectionMediaDto(
        id = id,
        mediaType = mediaType,
        sortOrder = sortOrder,
        url = "/api/guest/venue/$venueId/info-sections/$sectionId/media/$id",
    )

private fun ContentType?.toGuestMediaContentType(mediaType: String): ContentType =
    when (mediaType.lowercase()) {
        "image" -> this?.takeIf { it.contentType.equals("image", ignoreCase = true) } ?: ContentType.Image.JPEG
        "pdf" -> ContentType.parse("application/pdf")
        else -> this ?: ContentType.Application.OctetStream
    }

private fun io.ktor.server.application.ApplicationCall.requireLongParameter(name: String): Long {
    val raw = parameters[name] ?: throw InvalidInputException("$name is required")
    return raw.toLongOrNull() ?: throw InvalidInputException("$name must be a number")
}

private fun MenuModel.toResponse(): MenuResponse =
    MenuResponse(
        venueId = venueId,
        categories = categories.map { it.toDto() },
    )

private fun MenuCategoryModel.toDto(): MenuCategoryDto =
    MenuCategoryDto(
        id = id,
        name = name,
        categoryType = categoryType.dbValue,
        items = items.map { it.toDto(this) },
    )

private fun MenuItemModel.toDto(category: MenuCategoryModel): MenuItemDto =
    MenuItemDto(
        id = id,
        name = name,
        priceMinor = priceMinor,
        currency = currency,
        isAvailable = isAvailable,
        itemType = itemType?.dbValue,
        effectiveItemType = effectiveType(category).dbValue,
        options = options.map { it.toDto() },
    )

private fun MenuItemOptionModel.toDto(): MenuItemOptionDto =
    MenuItemOptionDto(
        id = id,
        name = name,
        priceDeltaMinor = priceDeltaMinor,
        isAvailable = isAvailable,
    )
