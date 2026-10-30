package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.RequestBodyTooLargeException
import com.hookah.platform.backend.miniapp.security.MiniAppAbuseProtection
import com.hookah.platform.backend.miniapp.security.enforceMiniAppRateLimit
import com.hookah.platform.backend.miniapp.security.receiveBoundedJson
import com.hookah.platform.backend.miniapp.venue.media.VENUE_INFO_MEDIA_MAX_BYTES
import com.hookah.platform.backend.miniapp.venue.media.VenueInfoMediaRepository
import com.hookah.platform.backend.miniapp.venue.media.VenueInfoMediaService
import com.hookah.platform.backend.telegram.TelegramDownloadedFile
import com.hookah.platform.backend.telegram.db.VenueInfoSectionMediaRepository
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

@Serializable
private data class InfoMediaAction(val revision: Long, val mediaId: String? = null, val action: String)

internal fun Route.venueInfoMediaRoutes(
    service: VenueInfoMediaService,
    repository: VenueInfoMediaRepository,
    legacy: VenueInfoSectionMediaRepository,
    abuse: MiniAppAbuseProtection,
    telegram: (
        suspend (String) -> TelegramDownloadedFile?
    )?,
) {
    get("/venue/{venueId}/info-media") {
        call.response.header("Cache-Control", "no-store, private")
        call.respond(service.state(call.requireVenueId(), call.requireUserId()))
    }
    get("/venue/{venueId}/info-sections/{sectionId}/media-operations/{requestId}") {
        call.response.header("Cache-Control", "no-store, private")
        val actor = call.requireUserId()
        enforceMiniAppRateLimit(call, abuse.tryInfoMediaRead(actor))
        call.respond(
            repository.operation(
                call.requireVenueId(),
                call.parameters["sectionId"]?.toLongOrNull() ?: throw InvalidInputException(),
                actor,
                call.parameters["requestId"] ?: throw InvalidInputException(),
            ),
        )
    }
    post("/venue/{venueId}/info-sections/{sectionId}/media") {
        val actor = call.requireUserId()
        val venue = call.requireVenueId()
        enforceMiniAppRateLimit(call, abuse.tryInfoMediaUpload(actor, venue))
        val section = call.parameters["sectionId"]?.toLongOrNull() ?: throw InvalidInputException()
        val revision = call.request.headers["X-Media-Revision"]?.toLongOrNull() ?: throw InvalidInputException()
        val request = call.request.headers["Idempotency-Key"] ?: throw InvalidInputException()
        call.respond(
            service.upload(
                venue,
                section,
                actor,
                revision,
                request,
                call.request.headers["Content-Type"],
                call.request.headers["X-Replaces-Media"],
            ) {
                if ((call.request.headers["Content-Length"]?.toLongOrNull() ?: 0) > VENUE_INFO_MEDIA_MAX_BYTES) {
                    throw RequestBodyTooLargeException()
                }
                val channel = call.receiveChannel()
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count == -1) break
                    if (output.size() + count > VENUE_INFO_MEDIA_MAX_BYTES) throw RequestBodyTooLargeException()
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            },
        )
    }
    post("/venue/{venueId}/info-sections/{sectionId}/media-actions") {
        val actor = call.requireUserId()
        val venue = call.requireVenueId()
        enforceMiniAppRateLimit(call, abuse.tryInfoMediaPrivacy(actor))
        val body = call.receiveBoundedJson<InfoMediaAction>(Json { ignoreUnknownKeys = false }, 1024)
        call.respond(
            service.mutate(
                venue,
                call.parameters["sectionId"]?.toLongOrNull() ?: throw InvalidInputException(),
                actor,
                body.revision,
                body.mediaId,
                body.action,
            ),
        )
    }
    get("/venue/{venueId}/info-sections/{sectionId}/media/{mediaId}/content") {
        val actor = call.requireUserId()
        val venue = call.requireVenueId()
        enforceMiniAppRateLimit(call, abuse.tryInfoMediaRead(actor))
        repository.authorize(venue, actor)
        val section = call.parameters["sectionId"]?.toLongOrNull() ?: throw InvalidInputException()
        if (repository.list(venue, actor).none { it.id == section }) throw NotFoundException()
        val reference = call.parameters["mediaId"] ?: throw InvalidInputException()
        val media = legacy.findManagedByReference(section, reference) ?: throw NotFoundException()
        val bytes = service.bytes(media, telegram) ?: throw NotFoundException()
        repository.authorize(venue, actor)
        if (legacy.findManagedByReference(section, reference)?.id != media.id) throw NotFoundException()
        call.response.header("Cache-Control", "no-store, private")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header(
            "Content-Disposition",
            "attachment; filename=venue-material.${if (media.mediaType == "pdf") {
                "pdf"
            } else if (bytes.contentType?.toString() == "image/png") {
                "png"
            } else {
                "jpg"
            }}",
        )
        call.respondBytes(bytes.bytes, bytes.contentType ?: io.ktor.http.ContentType.Application.OctetStream)
    }
}
