package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ConfigException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.GuestVenueReadService
import com.hookah.platform.backend.telegram.TelegramDownloadedFile
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueInfoSectionMediaRepository
import com.hookah.platform.backend.telegram.db.VenueInfoSectionsRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

const val GUEST_PREVIEW_UNAVAILABLE_MESSAGE = "Заведение сейчас недоступно для гостевого просмотра."

private const val DRAFT_PREVIEW_AVAILABILITY_LABEL = "Заведение ещё не опубликовано."
private const val HIDDEN_PREVIEW_AVAILABILITY_LABEL = "Заведение временно скрыто."
private const val SUSPENDED_PREVIEW_AVAILABILITY_LABEL = "Заведение приостановлено."

fun Route.venueGuestPreviewRoutes(
    venueAccessRepository: VenueAccessRepository,
    guestVenueReadService: GuestVenueReadService,
    venueInfoSectionsRepository: VenueInfoSectionsRepository,
    venueInfoSectionMediaRepository: VenueInfoSectionMediaRepository,
    telegramFileDownloader: (suspend (String) -> TelegramDownloadedFile?)? = null,
) {
    route("/venue/{venueId}/guest-preview") {
        get {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireGuestPreviewAccess(venueAccessRepository, userId, venueId)
            call.respond(
                loadGuestPreviewOrUnavailable {
                    buildGuestPreviewResponse(
                        guestVenueReadService = guestVenueReadService,
                        userId = userId,
                        venueId = venueId,
                    )
                },
            )
        }

        get("/info-sections/{sectionId}/media/{mediaId}") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireGuestPreviewAccess(venueAccessRepository, userId, venueId)
            val sectionId = call.requirePreviewLongParameter("sectionId")
            val mediaId = call.requirePreviewLongParameter("mediaId")
            val section =
                venueInfoSectionsRepository
                    .findSectionById(venueId = venueId, sectionId = sectionId)
                    ?.takeIf { it.isVisible }
                    ?: throw NotFoundException(GUEST_PREVIEW_UNAVAILABLE_MESSAGE)
            val media =
                venueInfoSectionMediaRepository.findById(sectionId = section.id, mediaId = mediaId)
                    ?: throw NotFoundException(GUEST_PREVIEW_UNAVAILABLE_MESSAGE)
            val downloader = telegramFileDownloader ?: throw ConfigException("Media proxy is not configured")
            val downloaded =
                downloader(media.telegramFileId)
                    ?: throw NotFoundException(GUEST_PREVIEW_UNAVAILABLE_MESSAGE)
            val contentType = downloaded.contentType.toGuestPreviewMediaContentType(media.mediaType)
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
}

private suspend fun buildGuestPreviewResponse(
    guestVenueReadService: GuestVenueReadService,
    userId: Long,
    venueId: Long,
): VenueGuestPreviewResponse {
    try {
        val venue = guestVenueReadService.getVenue(userId = userId, venueId = venueId)
        val infoSections = guestVenueReadService.getInfoSections(venueId)
        return VenueGuestPreviewResponse(
            mode = VenueGuestPreviewMode.PUBLISHED_PUBLIC,
            venueAvailabilityLabel = null,
            venue = venue.venue,
            infoSections = infoSections.sections,
            source = VenueGuestPreviewSource.SAVED_STATE,
        )
    } catch (_: NotFoundException) {
        // Parent availability is the only Guest guard bypassed by the private saved-state projection.
    }

    val venue = guestVenueReadService.getVenueForPrivatePreview(venueId)
    val infoSections = guestVenueReadService.getInfoSectionsForPrivatePreview(venueId)
    val currentVenueStatus =
        VenueStatus.fromDb(venue.venue.status)
            ?: throw NotFoundException(GUEST_PREVIEW_UNAVAILABLE_MESSAGE)
    return VenueGuestPreviewResponse(
        mode = VenueGuestPreviewMode.PRIVATE_DRAFT,
        venueAvailabilityLabel = currentVenueStatus.toPrivatePreviewAvailabilityLabel(),
        venue = venue.venue,
        infoSections = infoSections.sections,
        source = VenueGuestPreviewSource.SAVED_STATE,
    )
}

private suspend fun requireGuestPreviewAccess(
    venueAccessRepository: VenueAccessRepository,
    userId: Long,
    venueId: Long,
): VenueStatus {
    val membership =
        venueAccessRepository.findVenueMembershipIncludingDeleted(userId, venueId)
            ?: throw ForbiddenException()
    val role = VenueRoleMapping.fromDb(membership.role) ?: throw ForbiddenException()
    if (role !in setOf(VenueRole.OWNER, VenueRole.MANAGER)) {
        throw ForbiddenException()
    }
    val status =
        VenueStatus.fromDb(membership.venueStatus)
            ?: throw NotFoundException(GUEST_PREVIEW_UNAVAILABLE_MESSAGE)
    if (status !in PREVIEWABLE_VENUE_STATUSES) {
        throw NotFoundException(GUEST_PREVIEW_UNAVAILABLE_MESSAGE)
    }
    return status
}

private fun VenueStatus.toPrivatePreviewAvailabilityLabel(): String =
    when (this) {
        VenueStatus.DRAFT -> DRAFT_PREVIEW_AVAILABILITY_LABEL
        VenueStatus.HIDDEN -> HIDDEN_PREVIEW_AVAILABILITY_LABEL
        VenueStatus.PUBLISHED,
        VenueStatus.PAUSED,
        VenueStatus.SUSPENDED,
        -> SUSPENDED_PREVIEW_AVAILABILITY_LABEL
        VenueStatus.ARCHIVED,
        VenueStatus.DELETED,
        -> throw NotFoundException(GUEST_PREVIEW_UNAVAILABLE_MESSAGE)
    }

private suspend fun <T> loadGuestPreviewOrUnavailable(block: suspend () -> T): T =
    try {
        block()
    } catch (_: NotFoundException) {
        throw NotFoundException(GUEST_PREVIEW_UNAVAILABLE_MESSAGE)
    }

private fun ApplicationCall.requirePreviewLongParameter(name: String): Long {
    val raw = parameters[name] ?: throw InvalidInputException("$name is required")
    return raw.toLongOrNull() ?: throw InvalidInputException("$name must be a number")
}

private fun ContentType?.toGuestPreviewMediaContentType(mediaType: String): ContentType =
    when (mediaType.lowercase()) {
        "image" -> this?.takeIf { it.contentType.equals("image", ignoreCase = true) } ?: ContentType.Image.JPEG
        "pdf" -> ContentType.parse("application/pdf")
        else -> this ?: ContentType.Application.OctetStream
    }

private val PREVIEWABLE_VENUE_STATUSES =
    setOf(
        VenueStatus.DRAFT,
        VenueStatus.PUBLISHED,
        VenueStatus.HIDDEN,
        VenueStatus.PAUSED,
        VenueStatus.SUSPENDED,
    )
