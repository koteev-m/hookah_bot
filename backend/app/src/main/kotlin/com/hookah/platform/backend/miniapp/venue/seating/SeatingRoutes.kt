package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.RequestBodyTooLargeException
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.ensureGuestActionAvailable
import com.hookah.platform.backend.miniapp.security.MiniAppAbuseProtection
import com.hookah.platform.backend.miniapp.security.enforceMiniAppRateLimit
import com.hookah.platform.backend.miniapp.security.receiveBoundedJson
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.bookings.buildBookingChangedGuestNotification
import com.hookah.platform.backend.miniapp.venue.media.VENUE_INFO_MEDIA_MAX_BYTES
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.telegram.BookingStaffNotification
import com.hookah.platform.backend.telegram.BookingStaffNotificationEvent
import com.hookah.platform.backend.telegram.StaffChatNotifier
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter

internal fun Route.seatingRoutes(
    repository: SeatingRepository,
    plans: HallPlanService,
    abuse: MiniAppAbuseProtection,
    bookings: GuestBookingRepository,
    notifier: StaffChatNotifier?,
    outbox: TelegramOutboxEnqueuer,
    bookingRemindersEnabled: Boolean,
) {
    get("/venue/{venueId}/hall") {
        call.response.header("Cache-Control", "no-store, private")
        val state =
            repository.state(
                call.requireVenueId(),
                call.requireUserId(),
            ).copy(imageUploadAvailable = plans.available)
        call.respondText(seatingJson.encodeToString(state), ContentType.Application.Json)
    }
    post("/venue/{venueId}/hall/commands") {
        val cmd = call.receiveBoundedJson<SeatingCommand>(seatingJson, 16384)
        val venue = call.requireVenueId()
        val actor = call.requireUserId()
        val result =
            repository.command(venue, actor, cmd) { connection, bookingId ->
                val booking = checkNotNull(bookings.findByVenue(connection, bookingId, venue))
                val guest = checkNotNull(booking.userId)
                val zone = seatingZone(connection, venue)
                val venueName = bookings.findVenueName(connection, venue)?.takeIf { it.isNotBlank() } ?: "заведение"
                outbox.enqueueBookingSendMessageInTransaction(
                    connection = connection,
                    chatId = guest,
                    text = buildBookingChangedGuestNotification(booking, venueName, zone),
                    dedupeKey = "hall-time:$venue:$bookingId:$actor:${cmd.requestId}",
                )
                if (bookingRemindersEnabled) {
                    bookings.scheduleRemindersForBooking(connection, bookingId, Instant.now(), zone)
                }
            }
        if (cmd.action == "MANUAL_BOOKING") {
            notifyNewTableBooking(
                repository,
                bookings,
                notifier,
                call.requireVenueId(),
                result.id,
            )
        }
        call.respondText(seatingJson.encodeToString(result), ContentType.Application.Json)
    }
    post("/venue/{venueId}/hall/{hallId}/plans") {
        val venue = call.requireVenueId()
        val actor = call.requireUserId()
        enforceMiniAppRateLimit(call, abuse.tryInfoMediaUpload(actor, venue))
        val result =
            plans.upload(
                venue,
                call.hallId(),
                actor,
                call.request.headers["Idempotency-Key"] ?: throw InvalidInputException(),
                call.request.headers["Content-Type"],
            ) {
                val channel = call.receiveChannel()
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (output.size() + n > VENUE_INFO_MEDIA_MAX_BYTES) throw RequestBodyTooLargeException()
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
        call.respondText(seatingJson.encodeToString(result), ContentType.Application.Json)
    }
    get("/venue/{venueId}/hall/{hallId}/plans/{planId}") {
        val actor = call.requireUserId()
        enforceMiniAppRateLimit(call, abuse.tryInfoMediaRead(actor))
        val (data, mime) =
            plans.bytes(
                call.requireVenueId(),
                call.hallId(),
                call.parameters["planId"] ?: throw InvalidInputException(),
                actor,
            )
        call.response.header("Cache-Control", "no-store, private")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respondBytes(data, ContentType.parse(mime))
    }
}

internal fun Route.guestSeatingRoutes(
    repository: SeatingRepository,
    abuse: MiniAppAbuseProtection,
    plans: HallPlanService,
    venues: GuestVenueRepository,
    subscriptions: SubscriptionRepository,
    bookings: GuestBookingRepository,
    notifier: StaffChatNotifier?,
) {
    get("/venues/{venueId}/table-availability") {
        val venue = call.requireVenueId()
        ensureGuestActionAvailable(venue, venues, subscriptions)
        val result =
            repository.availability(
                venue,
                call.request.queryParameters["localStart"],
                call.request.queryParameters["partySize"]?.toIntOrNull() ?: 1,
            )
        call.response.header("Cache-Control", "no-store")
        call.respondText(seatingJson.encodeToString(result), ContentType.Application.Json)
    }
    post("/venues/{venueId}/table-bookings") {
        val venue = call.requireVenueId()
        ensureGuestActionAvailable(venue, venues, subscriptions)
        val cmd = call.receiveBoundedJson<SeatingCommand>(seatingJson, 16384)
        val result = repository.guestBooking(venue, call.requireUserId(), cmd)
        notifyNewTableBooking(repository, bookings, notifier, venue, result.id)
        call.respondText(seatingJson.encodeToString(result), ContentType.Application.Json)
    }
    get("/venues/{venueId}/hall/{hallId}/plans/{planId}") {
        enforceMiniAppRateLimit(call, abuse.tryInfoMediaRead(call.requireUserId()))
        enforceMiniAppRateLimit(call, abuse.tryInfoMediaPublicRead())
        val venue = call.requireVenueId()
        ensureGuestActionAvailable(venue, venues, subscriptions)
        val (bytes, mime) =
            plans.bytes(
                venue,
                call.hallId(),
                call.parameters["planId"] ?: throw InvalidInputException(),
                null,
            )
        ensureGuestActionAvailable(venue, venues, subscriptions)
        call.response.header("Cache-Control", "no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respondBytes(bytes, ContentType.parse(mime))
    }
}

private fun ApplicationCall.hallId(): Long = parameters["hallId"]?.toLongOrNull() ?: throw InvalidInputException()

private suspend fun notifyNewTableBooking(
    repository: SeatingRepository,
    bookings: GuestBookingRepository,
    notifier: StaffChatNotifier?,
    venue: Long,
    id: Long,
) {
    if (notifier == null) return
    val booking = bookings.findByVenue(id, venue) ?: return
    val zone = repository.transaction { seatingZone(it, venue) }
    notifier.notifyBookingNow(
        BookingStaffNotification(
            venueId = venue, bookingId = id, event = BookingStaffNotificationEvent.CREATED,
            status = booking.status, scheduledAt = booking.scheduledAt,
            scheduledAtText =
                DateTimeFormatter.ofPattern(
                    "dd.MM.yyyy HH:mm",
                ).withZone(zone).format(booking.scheduledAt),
            partySize = booking.partySize, comment = null, displayNumber = booking.displayNumber,
            guestDisplayName = if (booking.source == "STAFF") "Создана сотрудником" else booking.guestDisplayName,
        ),
        zone,
    )
}
