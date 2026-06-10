package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.ConfigException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.api.CatalogResponse
import com.hookah.platform.backend.miniapp.guest.api.CatalogVenueDto
import com.hookah.platform.backend.miniapp.guest.api.MenuCategoryDto
import com.hookah.platform.backend.miniapp.guest.api.MenuItemDto
import com.hookah.platform.backend.miniapp.guest.api.MenuItemOptionDto
import com.hookah.platform.backend.miniapp.guest.api.MenuResponse
import com.hookah.platform.backend.miniapp.guest.api.VenueDto
import com.hookah.platform.backend.miniapp.guest.api.VenueInfoSectionDto
import com.hookah.platform.backend.miniapp.guest.api.VenueInfoSectionMediaDto
import com.hookah.platform.backend.miniapp.guest.api.VenueInfoSectionsResponse
import com.hookah.platform.backend.miniapp.guest.api.VenueResponse
import com.hookah.platform.backend.miniapp.guest.db.GuestMenuRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.db.MenuCategoryModel
import com.hookah.platform.backend.miniapp.guest.db.MenuItemModel
import com.hookah.platform.backend.miniapp.guest.db.MenuItemOptionModel
import com.hookah.platform.backend.miniapp.guest.db.MenuModel
import com.hookah.platform.backend.miniapp.guest.db.VenueShort
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.telegram.TelegramDownloadedFile
import com.hookah.platform.backend.telegram.db.VenueInfoSection
import com.hookah.platform.backend.telegram.db.VenueInfoSectionMediaAttachment
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
    guestMenuRepository: GuestMenuRepository,
    venueInfoSectionsRepository: VenueInfoSectionsRepository,
    venueInfoSectionMediaRepository: VenueInfoSectionMediaRepository,
    subscriptionRepository: SubscriptionRepository,
) {
    get("/catalog") {
        val venues = guestVenueRepository.listCatalogVenues()
        call.respond(CatalogResponse(venues = venues.map { it.toCatalogDto() }))
    }

    get("/venue/{id}") {
        val rawId = call.parameters["id"] ?: throw InvalidInputException("id is required")
        val venueId = rawId.toLongOrNull() ?: throw InvalidInputException("id must be a number")
        val venue = ensureGuestBrowseAvailable(venueId, guestVenueRepository, subscriptionRepository)
        call.respond(VenueResponse(venue = venue.toVenueDto()))
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

private fun VenueShort.toCatalogDto(): CatalogVenueDto =
    CatalogVenueDto(
        id = id,
        name = name,
        city = city,
        address = address,
        guestContact = guestContact,
        cardDescription = cardDescription,
    )

private fun VenueShort.toVenueDto(): VenueDto =
    VenueDto(
        id = id,
        name = name,
        city = city,
        address = address,
        guestContact = guestContact,
        cardDescription = cardDescription,
        status = status.dbValue,
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
        items = items.map { it.toDto() },
    )

private fun MenuItemModel.toDto(): MenuItemDto =
    MenuItemDto(
        id = id,
        name = name,
        priceMinor = priceMinor,
        currency = currency,
        isAvailable = isAvailable,
        options = options.map { it.toDto() },
    )

private fun MenuItemOptionModel.toDto(): MenuItemOptionDto =
    MenuItemOptionDto(
        id = id,
        name = name,
        priceDeltaMinor = priceDeltaMinor,
        isAvailable = isAvailable,
    )
