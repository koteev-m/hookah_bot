package com.hookah.platform.backend.miniapp.venue.bookings

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.db.BookingRecord
import com.hookah.platform.backend.miniapp.guest.db.BookingStatus
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.venue.VenuePermission
import com.hookah.platform.backend.miniapp.venue.VenuePermissions
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.miniapp.venue.resolveVenueRole
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
private data class VenueBookingChangeRequest(
    val scheduledAt: String? = null,
    val scheduledLocalDate: String? = null,
    val scheduledLocalTime: String? = null,
)

@Serializable
private data class VenueBookingCancelRequest(
    val reasonText: String? = null,
)

@Serializable
private data class VenueBookingStatusResponse(
    val bookingId: Long,
    val status: String,
    val scheduledAt: String? = null,
)

@Serializable
private data class VenueBookingListResponse(
    val items: List<VenueBookingDto>,
)

@Serializable
private data class VenueBookingDto(
    val bookingId: Long,
    val displayNumber: Int? = null,
    val status: String,
    val scheduledAt: String,
    val scheduledAtDisplay: String? = null,
    val scheduledLocalDate: String? = null,
    val scheduledLocalTime: String? = null,
    val serviceDate: String? = null,
    val arrivalDeadlineAt: String? = null,
    val arrivalDeadlineAtDisplay: String? = null,
    val partySize: Int? = null,
    val comment: String? = null,
    val guestDisplayName: String? = null,
    val lastGuestConfirmationAt: String? = null,
)

fun Route.venueBookingRoutes(
    venueAccessRepository: VenueAccessRepository,
    guestBookingRepository: GuestBookingRepository,
    outboxEnqueuer: TelegramOutboxEnqueuer,
    venueSettingsRepository: VenueSettingsRepository = VenueSettingsRepository(null),
) {
    route("/venue/bookings") {
        get {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            val permissions = VenuePermissions.forRole(role)
            if (!permissions.contains(VenuePermission.BOOKING_VIEW)) {
                throw ForbiddenException()
            }
            val bookings = guestBookingRepository.listActiveByVenue(venueId = venueId)
            val zoneId = venueSettingsRepository.resolveZoneId(venueId)
            val holdMinutes = guestBookingRepository.getHoldMinutes(venueId)
            call.respond(
                VenueBookingListResponse(
                    items =
                        bookings.map { booking ->
                            booking.toVenueBookingDto(zoneId = zoneId, holdMinutes = holdMinutes)
                        },
                ),
            )
        }

        post("{bookingId}/confirm") {
            val booking =
                call.performVenueStatusUpdate(
                    venueAccessRepository = venueAccessRepository,
                    guestBookingRepository = guestBookingRepository,
                    requiredPermission = VenuePermission.BOOKING_MANAGE,
                    status = BookingStatus.CONFIRMED,
                )
            val zoneId = venueSettingsRepository.resolveZoneId(booking.venueId)
            outboxEnqueuer.enqueueSendMessage(
                chatId = booking.userId,
                text =
                    "✅ ${formatBookingDisplayLabel(booking)} в " +
                        "${formatBookingVenueName(guestBookingRepository, booking.venueId)} " +
                        "подтверждена на ${formatBookingNotificationTime(booking.scheduledAt, zoneId)}",
            )
            guestBookingRepository.scheduleRemindersForBooking(
                bookingId = booking.id,
                now = Instant.now(),
                venueZoneId = zoneId,
            )
            call.respond(VenueBookingStatusResponse(bookingId = booking.id, status = booking.status.toApi()))
        }

        post("{bookingId}/change") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            val permissions = VenuePermissions.forRole(role)
            if (!permissions.contains(VenuePermission.BOOKING_MANAGE)) {
                throw ForbiddenException()
            }
            val bookingId =
                call.parameters["bookingId"]?.toLongOrNull()
                    ?: throw InvalidInputException("bookingId must be a number")
            val request = call.receive<VenueBookingChangeRequest>()
            val zoneId = venueSettingsRepository.resolveZoneId(venueId)
            val scheduledAt = parseBookingInstant(request, zoneId)
            val updated =
                guestBookingRepository.updateByVenue(
                    bookingId = bookingId,
                    venueId = venueId,
                    nextStatus = BookingStatus.CHANGED,
                    scheduledAt = scheduledAt,
                    venueZoneId = zoneId,
                ) ?: throw NotFoundException()
            outboxEnqueuer.enqueueSendMessage(
                chatId = updated.userId,
                text =
                    "🕒 ${formatBookingDisplayLabel(updated)} в " +
                        "${formatBookingVenueName(guestBookingRepository, updated.venueId)} " +
                        "перенесена. Новое время: ${formatBookingNotificationTime(updated.scheduledAt, zoneId)}",
            )
            guestBookingRepository.scheduleRemindersForBooking(
                bookingId = updated.id,
                now = Instant.now(),
                venueZoneId = zoneId,
            )
            call.respond(
                VenueBookingStatusResponse(
                    bookingId = updated.id,
                    status = updated.status.toApi(),
                    scheduledAt = updated.scheduledAt.toString(),
                ),
            )
        }

        post("{bookingId}/cancel") {
            val request = runCatching { call.receive<VenueBookingCancelRequest>() }.getOrNull()
            val cancelReason = normalizeCancelReason(request?.reasonText)
            val booking =
                call.performVenueStatusUpdate(
                    venueAccessRepository = venueAccessRepository,
                    guestBookingRepository = guestBookingRepository,
                    requiredPermission = VenuePermission.BOOKING_MANAGE,
                    status = BookingStatus.CANCELED,
                    cancelReasonText = cancelReason,
                )
            val zoneId = venueSettingsRepository.resolveZoneId(booking.venueId)
            outboxEnqueuer.enqueueSendMessage(
                chatId = booking.userId,
                text =
                    "❌ ${formatBookingDisplayLabel(booking)} в " +
                        "${formatBookingVenueName(guestBookingRepository, booking.venueId)} " +
                        "на ${formatBookingNotificationTime(booking.scheduledAt, zoneId)} отменена заведением.\n" +
                        "Причина: ${cancelReason ?: "не указана"}",
            )
            call.respond(VenueBookingStatusResponse(bookingId = booking.id, status = booking.status.toApi()))
        }

        post("{bookingId}/seat") {
            val booking =
                call.performVenueStatusUpdate(
                    venueAccessRepository = venueAccessRepository,
                    guestBookingRepository = guestBookingRepository,
                    requiredPermission = VenuePermission.BOOKING_ARRIVAL_UPDATE,
                    status = BookingStatus.SEATED,
                )
            call.respond(VenueBookingStatusResponse(bookingId = booking.id, status = booking.status.toApi()))
        }

        post("{bookingId}/no-show") {
            val booking =
                call.performVenueStatusUpdate(
                    venueAccessRepository = venueAccessRepository,
                    guestBookingRepository = guestBookingRepository,
                    requiredPermission = VenuePermission.BOOKING_ARRIVAL_UPDATE,
                    status = BookingStatus.NO_SHOW,
                )
            call.respond(VenueBookingStatusResponse(bookingId = booking.id, status = booking.status.toApi()))
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.performVenueStatusUpdate(
    venueAccessRepository: VenueAccessRepository,
    guestBookingRepository: GuestBookingRepository,
    requiredPermission: VenuePermission,
    status: BookingStatus,
    cancelReasonText: String? = null,
): BookingRecord {
    val userId = requireUserId()
    val venueId = requireVenueId()
    val role = resolveVenueRole(venueAccessRepository, userId, venueId)
    val permissions = VenuePermissions.forRole(role)
    if (!permissions.contains(requiredPermission)) {
        throw ForbiddenException()
    }
    val bookingId = parameters["bookingId"]?.toLongOrNull() ?: throw InvalidInputException("bookingId must be a number")
    return when (status) {
        BookingStatus.SEATED ->
            guestBookingRepository.markSeated(
                bookingId = bookingId,
                venueId = venueId,
                actorUserId = userId,
            )
        BookingStatus.NO_SHOW ->
            guestBookingRepository.markNoShow(
                bookingId = bookingId,
                venueId = venueId,
                actorUserId = userId,
            )
        else ->
            guestBookingRepository.updateByVenue(
                bookingId = bookingId,
                venueId = venueId,
                nextStatus = status,
                scheduledAt = null,
                cancelReasonText = if (status == BookingStatus.CANCELED) cancelReasonText else null,
                canceledByRole = if (status == BookingStatus.CANCELED) role.name else null,
                canceledByUserId = if (status == BookingStatus.CANCELED) userId else null,
            )
    } ?: throw NotFoundException()
}

private fun BookingRecord.toVenueBookingDto(
    zoneId: ZoneId,
    holdMinutes: Int,
): VenueBookingDto {
    val localDateTime = scheduledAt.atZone(zoneId).toLocalDateTime()
    val effectiveDeadlineAt = arrivalDeadlineAt ?: scheduledAt.plus(Duration.ofMinutes(holdMinutes.toLong()))
    return VenueBookingDto(
        bookingId = id,
        displayNumber = displayNumber,
        status = status.toApi(),
        scheduledAt = scheduledAt.toString(),
        scheduledAtDisplay = formatBookingNotificationTime(scheduledAt, zoneId),
        scheduledLocalDate = localDateTime.toLocalDate().toString(),
        scheduledLocalTime = BOOKING_LOCAL_TIME_FORMAT.format(localDateTime),
        serviceDate = (displayDate ?: localDateTime.toLocalDate()).toString(),
        arrivalDeadlineAt = effectiveDeadlineAt.toString(),
        arrivalDeadlineAtDisplay = formatBookingNotificationTime(effectiveDeadlineAt, zoneId),
        partySize = partySize,
        comment = comment,
        guestDisplayName = guestDisplayName,
        lastGuestConfirmationAt = lastGuestConfirmationAt?.toString(),
    )
}

private fun formatBookingDisplayLabel(booking: BookingRecord): String =
    booking.displayNumber?.let { "Бронь №$it" } ?: "Бронь"

private suspend fun formatBookingVenueName(
    guestBookingRepository: GuestBookingRepository,
    venueId: Long,
): String = guestBookingRepository.findVenueName(venueId)?.takeIf { it.isNotBlank() } ?: "заведении"

private fun formatBookingNotificationTime(
    scheduledAt: Instant,
    venueZoneId: ZoneId,
): String = BOOKING_NOTIFICATION_TIME_FORMAT.format(scheduledAt.atZone(venueZoneId))

private fun normalizeCancelReason(value: String?): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (trimmed.length > MAX_CANCEL_REASON_LENGTH) {
        throw InvalidInputException("reasonText must be at most $MAX_CANCEL_REASON_LENGTH characters")
    }
    return trimmed
}

private const val MAX_CANCEL_REASON_LENGTH = 500

private val BOOKING_NOTIFICATION_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.forLanguageTag("ru-RU"))

private fun parseBookingInstant(
    request: VenueBookingChangeRequest,
    zoneId: ZoneId,
): Instant {
    request.scheduledAt?.trim()?.takeIf { it.isNotBlank() }?.let { value ->
        return runCatching { Instant.parse(value) }.getOrElse {
            throw InvalidInputException("scheduledAt must be ISO-8601 instant")
        }
    }
    val localDate = request.scheduledLocalDate?.trim()?.takeIf { it.isNotBlank() }
    val localTime = request.scheduledLocalTime?.trim()?.takeIf { it.isNotBlank() }
    if (localDate == null || localTime == null) {
        throw InvalidInputException("scheduledAt or scheduledLocalDate/scheduledLocalTime must be provided")
    }
    return runCatching {
        val date = LocalDate.parse(localDate)
        val time = LocalTime.parse(localTime)
        LocalDateTime.of(date, time).atZone(zoneId).toInstant()
    }.getOrElse {
        throw InvalidInputException("scheduledLocalDate/scheduledLocalTime must be valid venue-local date and time")
    }
}

private val BOOKING_LOCAL_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
