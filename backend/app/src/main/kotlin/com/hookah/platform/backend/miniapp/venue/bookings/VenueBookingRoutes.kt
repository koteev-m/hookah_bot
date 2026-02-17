package com.hookah.platform.backend.miniapp.venue.bookings

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.db.BookingStatus
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.venue.VenuePermission
import com.hookah.platform.backend.miniapp.venue.VenuePermissions
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.miniapp.venue.resolveVenueRole
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
private data class VenueBookingChangeRequest(
    val scheduledAt: String,
)

@Serializable
private data class VenueBookingStatusResponse(
    val bookingId: Long,
    val status: String,
    val scheduledAt: String? = null,
)

fun Route.venueBookingRoutes(
    venueAccessRepository: VenueAccessRepository,
    guestBookingRepository: GuestBookingRepository,
    outboxEnqueuer: TelegramOutboxEnqueuer,
) {
    route("/venue/bookings") {
        post("{bookingId}/confirm") {
            val booking =
                call.performVenueStatusUpdate(
                    venueAccessRepository = venueAccessRepository,
                    guestBookingRepository = guestBookingRepository,
                    status = BookingStatus.CONFIRMED,
                )
            outboxEnqueuer.enqueueSendMessage(
                chatId = booking.userId,
                text = "✅ Бронь #${booking.id} подтверждена заведением",
            )
            call.respond(VenueBookingStatusResponse(bookingId = booking.id, status = booking.status.toApi()))
        }

        post("{bookingId}/change") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            val permissions = VenuePermissions.forRole(role)
            if (!permissions.contains(VenuePermission.ORDER_STATUS_UPDATE)) {
                throw ForbiddenException()
            }
            val bookingId =
                call.parameters["bookingId"]?.toLongOrNull()
                    ?: throw InvalidInputException("bookingId must be a number")
            val request = call.receive<VenueBookingChangeRequest>()
            val scheduledAt = parseBookingInstant(request.scheduledAt)
            val updated =
                guestBookingRepository.updateByVenue(
                    bookingId = bookingId,
                    venueId = venueId,
                    nextStatus = BookingStatus.CHANGED,
                    scheduledAt = scheduledAt,
                ) ?: throw NotFoundException()
            outboxEnqueuer.enqueueSendMessage(
                chatId = updated.userId,
                text = "🕒 Бронь #${updated.id} перенесена. Новое время: ${request.scheduledAt}",
            )
            call.respond(
                VenueBookingStatusResponse(
                    bookingId = updated.id,
                    status = updated.status.toApi(),
                    scheduledAt = request.scheduledAt,
                ),
            )
        }

        post("{bookingId}/cancel") {
            val booking =
                call.performVenueStatusUpdate(
                    venueAccessRepository = venueAccessRepository,
                    guestBookingRepository = guestBookingRepository,
                    status = BookingStatus.CANCELED,
                )
            outboxEnqueuer.enqueueSendMessage(
                chatId = booking.userId,
                text = "❌ Бронь #${booking.id} отменена заведением",
            )
            call.respond(VenueBookingStatusResponse(bookingId = booking.id, status = booking.status.toApi()))
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.performVenueStatusUpdate(
    venueAccessRepository: VenueAccessRepository,
    guestBookingRepository: GuestBookingRepository,
    status: BookingStatus,
): com.hookah.platform.backend.miniapp.guest.db.BookingRecord {
    val userId = requireUserId()
    val venueId = requireVenueId()
    val role = resolveVenueRole(venueAccessRepository, userId, venueId)
    val permissions = VenuePermissions.forRole(role)
    if (!permissions.contains(VenuePermission.ORDER_STATUS_UPDATE)) {
        throw ForbiddenException()
    }
    val bookingId = parameters["bookingId"]?.toLongOrNull() ?: throw InvalidInputException("bookingId must be a number")
    return guestBookingRepository.updateByVenue(
        bookingId = bookingId,
        venueId = venueId,
        nextStatus = status,
        scheduledAt = null,
    ) ?: throw NotFoundException()
}

private fun parseBookingInstant(value: String): Instant {
    val trimmed = value.trim()
    if (trimmed.isBlank()) {
        throw InvalidInputException("scheduledAt must not be blank")
    }
    return runCatching { Instant.parse(trimmed) }.getOrElse {
        throw InvalidInputException("scheduledAt must be ISO-8601 instant")
    }
}
