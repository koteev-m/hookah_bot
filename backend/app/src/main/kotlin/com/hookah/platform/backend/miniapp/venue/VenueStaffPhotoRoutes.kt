package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.RequestBodyTooLargeException
import com.hookah.platform.backend.miniapp.security.MiniAppAbuseProtection
import com.hookah.platform.backend.miniapp.security.enforceMiniAppRateLimit
import com.hookah.platform.backend.miniapp.security.receiveBoundedJson
import com.hookah.platform.backend.miniapp.venue.staff.STAFF_PHOTO_CONSENT_POLICY
import com.hookah.platform.backend.miniapp.venue.staff.StaffPhotoService
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
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
private data class StaffPhotoActionRequest(
    val revision: Long,
    val photoId: String? = null,
    val confirmed: Boolean = false,
    val policy: String? = null,
)

internal fun Route.venueStaffPhotoRoutes(
    service: StaffPhotoService,
    access: VenueAccessRepository,
    abuse: MiniAppAbuseProtection,
) {
    get("/venue/{venueId}/staff/photos/me") {
        val actor = call.requireUserId()
        val venue = call.requireVenueId()
        resolveVenueRole(access, actor, venue)
        call.respond(service.own(venue, actor))
    }
    get("/venue/{venueId}/staff/profiles/{profileId}/photos") {
        call.respond(service.state(call.requireVenueId(), call.photoProfileId(), call.requireUserId()))
    }
    post("/venue/{venueId}/staff/profiles/{profileId}/photos") {
        val actor = call.requireUserId()
        val venue = call.requireVenueId()
        enforceMiniAppRateLimit(call, abuse.tryStaffPhotoUpload(actor, venue))
        val revision =
            call.request.headers["X-Photo-Revision"]?.toLongOrNull()?.takeIf {
                it >= 0
            } ?: throw InvalidInputException()
        val requestId = call.request.headers["Idempotency-Key"] ?: throw InvalidInputException()
        call.respond(
            service.upload(
                venue,
                call.photoProfileId(),
                actor,
                revision,
                requestId,
                call.request.headers[HttpHeaders.ContentType],
            ) {
                call.receivePhotoBytes(service.maxInputBytes)
            },
        )
    }
    for (action in listOf("consent", "revoke", "publish", "hide", "delete")) {
        post("/venue/{venueId}/staff/profiles/{profileId}/photos/$action") {
            val actor = call.requireUserId()
            val venue = call.requireVenueId()
            enforceMiniAppRateLimit(
                call,
                if (action in setOf("revoke", "hide", "delete")) {
                    abuse.tryStaffPhotoPrivacy(
                        actor,
                    )
                } else {
                    abuse.tryStaffPhotoUpload(actor, venue)
                },
            )
            val body = call.receiveBoundedJson<StaffPhotoActionRequest>(Json { ignoreUnknownKeys = false }, 1024)
            if (body.revision < 0 || action == "consent" && body.policy != STAFF_PHOTO_CONSENT_POLICY) {
                throw InvalidInputException()
            }
            call.respond(
                service.mutate(
                    venue,
                    call.photoProfileId(),
                    actor,
                    body.photoId,
                    body.revision,
                    action,
                    body.confirmed,
                ),
            )
        }
    }
    get("/venue/{venueId}/staff/profiles/{profileId}/photos/{photoId}/content") {
        val actor = call.requireUserId()
        enforceMiniAppRateLimit(call, abuse.tryStaffPhotoRead(actor))
        val bytes =
            service.privateBytes(
                call.requireVenueId(),
                call.photoProfileId(),
                actor,
                call.parameters["photoId"] ?: throw InvalidInputException(),
            )
        call.photoResponse(bytes)
    }
    get("/guest/venue/{venueId}/staff/{profileId}/photos/{photoId}") {
        val actor = call.requireUserId()
        enforceMiniAppRateLimit(call, abuse.tryStaffPhotoRead(actor))
        val bytes =
            service.guestBytes(
                call.requireVenueId(),
                call.photoProfileId(),
                call.parameters["photoId"] ?: throw InvalidInputException(),
            )
        call.photoResponse(bytes)
    }
}

private fun ApplicationCall.photoProfileId() =
    parameters["profileId"]?.toLongOrNull()?.takeIf {
        it > 0
    } ?: throw InvalidInputException()

private suspend fun ApplicationCall.photoResponse(bytes: ByteArray) {
    response.headers.append(HttpHeaders.CacheControl, "no-store, private")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append(HttpHeaders.ContentDisposition, "inline; filename=staff-photo.jpg")
    respondBytes(bytes, ContentType.Image.JPEG)
}

private suspend fun ApplicationCall.receivePhotoBytes(limit: Int): ByteArray {
    if ((request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > limit) throw RequestBodyTooLargeException()
    val channel = receiveChannel()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = channel.readAvailable(buffer, 0, buffer.size)
        if (count == -1) break
        if (output.size() + count > limit) throw RequestBodyTooLargeException()
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
