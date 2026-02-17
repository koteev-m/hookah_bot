package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.api.GuestBookingCancelRequest
import com.hookah.platform.backend.miniapp.guest.api.GuestBookingCreateRequest
import com.hookah.platform.backend.miniapp.guest.api.GuestBookingListResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestBookingResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestBookingUpdateRequest
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.db.VenueRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val BOOKING_COMMENT_MAX_LENGTH = 500
private val bookingInstantFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

fun Route.guestBookingRoutes(
    guestVenueRepository: GuestVenueRepository,
    subscriptionRepository: SubscriptionRepository,
    guestBookingRepository: GuestBookingRepository,
    venueRepository: VenueRepository,
    outboxEnqueuer: TelegramOutboxEnqueuer,
) {
    route("/booking") {
        post("/create") {
            val request = call.receive<GuestBookingCreateRequest>()
            val venueId = normalizePositiveLong(request.venueId, "venueId")
            ensureGuestActionAvailable(venueId, guestVenueRepository, subscriptionRepository)
            val userId = call.requireUserId()
            val created =
                guestBookingRepository.create(
                    venueId = venueId,
                    userId = userId,
                    scheduledAt = parseBookingInstant(request.scheduledAt),
                    partySize = normalizePartySize(request.partySize),
                    comment = normalizeBookingComment(request.comment),
                )
            notifyVenueStaffAboutBooking(
                venueRepository = venueRepository,
                outboxEnqueuer = outboxEnqueuer,
                venueId = created.venueId,
                text = "📅 Новое бронирование #${created.id} на ${formatBookingInstant(created.scheduledAt)}",
            )
            call.respond(created.toResponse())
        }

        post("/update") {
            val request = call.receive<GuestBookingUpdateRequest>()
            val bookingId = normalizePositiveLong(request.bookingId, "bookingId")
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            ensureGuestActionAvailable(venueId, guestVenueRepository, subscriptionRepository)
            val updated =
                guestBookingRepository.updateByGuest(
                    bookingId = bookingId,
                    venueId = venueId,
                    userId = userId,
                    scheduledAt = parseBookingInstant(request.scheduledAt),
                    partySize = normalizePartySize(request.partySize),
                    comment = normalizeBookingComment(request.comment),
                ) ?: throw NotFoundException()
            notifyVenueStaffAboutBooking(
                venueRepository = venueRepository,
                outboxEnqueuer = outboxEnqueuer,
                venueId = updated.venueId,
                text = "📅 Обновлено бронирование #${updated.id}. Новый слот: ${formatBookingInstant(updated.scheduledAt)}",
            )
            call.respond(updated.toResponse())
        }

        post("/cancel") {
            val request = call.receive<GuestBookingCancelRequest>()
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            ensureGuestActionAvailable(venueId, guestVenueRepository, subscriptionRepository)
            val canceled =
                guestBookingRepository.cancelByGuest(
                    bookingId = normalizePositiveLong(request.bookingId, "bookingId"),
                    venueId = venueId,
                    userId = userId,
                ) ?: throw NotFoundException()
            notifyVenueStaffAboutBooking(
                venueRepository = venueRepository,
                outboxEnqueuer = outboxEnqueuer,
                venueId = canceled.venueId,
                text = "📅 Гость отменил бронирование #${canceled.id}",
            )
            call.respond(canceled.toResponse())
        }

        get {
            val venueId = call.requireVenueId()
            ensureGuestActionAvailable(venueId, guestVenueRepository, subscriptionRepository)
            val userId = call.requireUserId()
            val bookings = guestBookingRepository.listByUser(venueId = venueId, userId = userId)
            call.respond(GuestBookingListResponse(items = bookings.map { it.toResponse() }))
        }
    }
}

private suspend fun notifyVenueStaffAboutBooking(
    venueRepository: VenueRepository,
    outboxEnqueuer: TelegramOutboxEnqueuer,
    venueId: Long,
    text: String,
) {
    val venue = venueRepository.findVenueById(venueId) ?: return
    val chatId = venue.staffChatId ?: return
    outboxEnqueuer.enqueueSendMessage(chatId = chatId, text = text)
}

private fun normalizePositiveLong(value: Long, fieldName: String): Long {
    if (value <= 0) {
        throw InvalidInputException("$fieldName must be positive")
    }
    return value
}

private fun normalizePartySize(value: Int?): Int? {
    if (value == null) {
        return null
    }
    if (value !in 1..30) {
        throw InvalidInputException("partySize must be between 1 and 30")
    }
    return value
}

private fun normalizeBookingComment(value: String?): String? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) {
        return null
    }
    if (trimmed.length > BOOKING_COMMENT_MAX_LENGTH) {
        throw InvalidInputException("comment length must be <= $BOOKING_COMMENT_MAX_LENGTH")
    }
    return trimmed
}

private fun parseBookingInstant(value: String): Instant {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        throw InvalidInputException("scheduledAt must not be blank")
    }
    return runCatching { Instant.parse(trimmed) }.getOrElse {
        throw InvalidInputException("scheduledAt must be ISO-8601 instant")
    }
}

private fun formatBookingInstant(value: Instant): String = bookingInstantFormatter.format(value.atOffset(ZoneOffset.UTC))

private fun com.hookah.platform.backend.miniapp.guest.db.BookingRecord.toResponse(): GuestBookingResponse =
    GuestBookingResponse(
        bookingId = id,
        venueId = venueId,
        status = status.toApi(),
        scheduledAt = formatBookingInstant(scheduledAt),
        partySize = partySize,
        comment = comment,
    )
