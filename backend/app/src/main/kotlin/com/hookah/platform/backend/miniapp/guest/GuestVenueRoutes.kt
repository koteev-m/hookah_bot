package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.ConfigException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.location.VenueLocationDisplay
import com.hookah.platform.backend.location.buildYandexVenueRouteUrl
import com.hookah.platform.backend.location.formatVenueDisplayAddress
import com.hookah.platform.backend.miniapp.guest.api.CatalogResponse
import com.hookah.platform.backend.miniapp.guest.api.CatalogVenueDto
import com.hookah.platform.backend.miniapp.guest.api.GuestTodayStaffResponse
import com.hookah.platform.backend.miniapp.guest.api.MenuCategoryDto
import com.hookah.platform.backend.miniapp.guest.api.MenuItemDto
import com.hookah.platform.backend.miniapp.guest.api.MenuItemOptionDto
import com.hookah.platform.backend.miniapp.guest.api.MenuResponse
import com.hookah.platform.backend.miniapp.guest.api.VenueTodayScheduleDto
import com.hookah.platform.backend.miniapp.guest.db.GuestFavoritesRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestMenuRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.db.MenuCategoryModel
import com.hookah.platform.backend.miniapp.guest.db.MenuItemModel
import com.hookah.platform.backend.miniapp.guest.db.MenuItemOptionModel
import com.hookah.platform.backend.miniapp.guest.db.MenuModel
import com.hookah.platform.backend.miniapp.guest.db.VenueShort
import com.hookah.platform.backend.miniapp.guest.db.effectiveType
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.telegram.TelegramDownloadedFile
import com.hookah.platform.backend.telegram.db.VenueInfoSectionMediaRepository
import com.hookah.platform.backend.telegram.db.VenueInfoSectionsRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.guestVenueRoutes(
    guestVenueRepository: GuestVenueRepository,
    guestFavoritesRepository: GuestFavoritesRepository,
    guestMenuRepository: GuestMenuRepository,
    subscriptionRepository: SubscriptionRepository,
    guestVenueReadService: GuestVenueReadService,
) {
    get("/catalog") {
        val userId = call.requireUserId()
        val query = normalizeCatalogFilter(call.request.queryParameters["q"], "q")
        val city = normalizeCatalogFilter(call.request.queryParameters["city"], "city")
        val venues = guestVenueRepository.listCatalogVenues(query = query, city = city)
        val favoriteVenueIds =
            guestFavoritesRepository.findFavoriteVenueIds(
                userId = userId,
                venueIds = venues.map { it.id },
            )
        val schedules = guestVenueReadService.getTodaySchedules(venues.map { it.id })
        call.respond(
            CatalogResponse(
                venues = venues.map { it.toCatalogDto(schedules[it.id], it.id in favoriteVenueIds) },
            ),
        )
    }

    get("/venue/{id}") {
        val userId = call.requireUserId()
        val rawId = call.parameters["id"] ?: throw InvalidInputException("id is required")
        val venueId = rawId.toLongOrNull() ?: throw InvalidInputException("id must be a number")
        call.respond(guestVenueReadService.getVenue(userId = userId, venueId = venueId))
    }

    get("/venue/{id}/today-staff") {
        val rawId = call.parameters["id"] ?: throw InvalidInputException("id is required")
        val venueId = rawId.toLongOrNull() ?: throw InvalidInputException("id must be a number")
        val todayStaff = guestVenueReadService.getTodayStaff(venueId)
        call.respond(GuestTodayStaffResponse(venueId = venueId, staff = todayStaff))
    }

    get("/venue/{id}/info-sections") {
        val rawId = call.parameters["id"] ?: throw InvalidInputException("id is required")
        val venueId = rawId.toLongOrNull() ?: throw InvalidInputException("id must be a number")
        call.respond(guestVenueReadService.getInfoSections(venueId))
    }

    get("/venue/{id}/menu") {
        val rawId = call.parameters["id"] ?: throw InvalidInputException("id is required")
        val venueId = rawId.toLongOrNull() ?: throw InvalidInputException("id must be a number")
        ensureGuestBrowseAvailable(venueId, guestVenueRepository, subscriptionRepository)
        val menu = guestMenuRepository.getMenu(venueId)
        call.respond(menu.toResponse())
    }
}

private fun normalizeCatalogFilter(
    rawValue: String?,
    fieldName: String,
): String? {
    val normalized = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (normalized.length > CATALOG_FILTER_MAX_LENGTH) {
        throw InvalidInputException("$fieldName length must be <= $CATALOG_FILTER_MAX_LENGTH")
    }
    return normalized
}

private const val CATALOG_FILTER_MAX_LENGTH = 100

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

private fun VenueShort.toCatalogDto(
    todaySchedule: VenueTodayScheduleDto?,
    isFavorite: Boolean,
): CatalogVenueDto =
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
        isFavorite = isFavorite,
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
