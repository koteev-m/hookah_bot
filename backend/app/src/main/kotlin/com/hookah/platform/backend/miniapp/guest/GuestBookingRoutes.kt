package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.VenueScheduleNotConfiguredException
import com.hookah.platform.backend.miniapp.guest.api.GuestBookingCancelRequest
import com.hookah.platform.backend.miniapp.guest.api.GuestBookingConfirmRequest
import com.hookah.platform.backend.miniapp.guest.api.GuestBookingCreateRequest
import com.hookah.platform.backend.miniapp.guest.api.GuestBookingListResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestBookingResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestBookingUpdateRequest
import com.hookah.platform.backend.miniapp.guest.db.BookingRecord
import com.hookah.platform.backend.miniapp.guest.db.BookingStatus
import com.hookah.platform.backend.miniapp.guest.db.GuestAttendanceConfirmationStatus
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.db.UserBookingSummaryRecord
import com.hookah.platform.backend.miniapp.guest.db.attendanceScheduleVersionEpochSeconds
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.BookingStartAvailability
import com.hookah.platform.backend.miniapp.venue.checkBookingStartAvailability
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.telegram.BookingStaffNotification
import com.hookah.platform.backend.telegram.BookingStaffNotificationEvent
import com.hookah.platform.backend.telegram.ReplyKeyboardRemove
import com.hookah.platform.backend.telegram.StaffChatNotifier
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.buildGuestAttendanceStaffChatText
import com.hookah.platform.backend.telegram.db.UserRepository
import com.hookah.platform.backend.telegram.db.VenueBookingHoursRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val BOOKING_COMMENT_MAX_LENGTH = 500
private val bookingInstantFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
private val bookingDisplayFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")
private val bookingTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val guestActionableBookingStatuses =
    setOf(
        BookingStatus.PENDING,
        BookingStatus.CONFIRMED,
        BookingStatus.CHANGED,
    )

fun Route.guestBookingRoutes(
    guestVenueRepository: GuestVenueRepository,
    subscriptionRepository: SubscriptionRepository,
    guestBookingRepository: GuestBookingRepository,
    venueRepository: VenueRepository,
    outboxEnqueuer: TelegramOutboxEnqueuer,
    staffChatNotifier: StaffChatNotifier? = null,
    userRepository: UserRepository = UserRepository(null),
    venueSettingsRepository: VenueSettingsRepository = VenueSettingsRepository(null),
    venueBookingHoursRepository: VenueBookingHoursRepository,
) {
    get("/bookings") {
        val userId = call.requireUserId()
        val bookings =
            guestBookingRepository
                .listActiveByUser(userId = userId, limit = 50)
                .map { booking ->
                    val zoneId = venueSettingsRepository.resolveZoneId(booking.venueId)
                    booking.toResponse(zoneId = zoneId)
                }
        call.respond(GuestBookingListResponse(items = bookings))
    }

    route("/booking") {
        post("/create") {
            val request = call.receive<GuestBookingCreateRequest>()
            val venueId = normalizePositiveLong(request.venueId, "venueId")
            ensureGuestActionAvailable(venueId, guestVenueRepository, subscriptionRepository)
            val userId = call.requireUserId()
            val venueZoneId = venueSettingsRepository.resolveZoneId(venueId)
            val scheduledAt = parseBookingInstant(request.scheduledAt)
            requireBookingWithinVenueHours(
                venueBookingHoursRepository = venueBookingHoursRepository,
                venueId = venueId,
                scheduledAt = scheduledAt,
                zoneId = venueZoneId,
            )
            val created =
                guestBookingRepository.create(
                    venueId = venueId,
                    userId = userId,
                    scheduledAt = scheduledAt,
                    partySize = normalizePartySize(request.partySize),
                    comment = normalizeBookingComment(request.comment),
                    venueZoneId = venueZoneId,
                )
            notifyVenueStaffAboutBooking(
                staffChatNotifier = staffChatNotifier,
                venueRepository = venueRepository,
                outboxEnqueuer = outboxEnqueuer,
                notification =
                    BookingStaffNotification(
                        venueId = created.venueId,
                        bookingId = created.id,
                        event = BookingStaffNotificationEvent.CREATED,
                        scheduledAtText = formatBookingInstant(created.scheduledAt, venueZoneId),
                        partySize = created.partySize,
                        comment = created.comment,
                        displayNumber = created.displayNumber,
                        guestDisplayName = loadGuestDisplayName(userRepository, userId),
                    ),
            )
            call.respond(
                created.toResponse(
                    venueName = guestBookingRepository.findVenueName(venueId),
                    zoneId = venueZoneId,
                ),
            )
        }

        post("/update") {
            val request = call.receive<GuestBookingUpdateRequest>()
            val bookingId = normalizePositiveLong(request.bookingId, "bookingId")
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val venueZoneId = venueSettingsRepository.resolveZoneId(venueId)
            ensureGuestActionAvailable(venueId, guestVenueRepository, subscriptionRepository)
            val scheduledAt = parseBookingInstant(request.scheduledAt)
            requireBookingWithinVenueHours(
                venueBookingHoursRepository = venueBookingHoursRepository,
                venueId = venueId,
                scheduledAt = scheduledAt,
                zoneId = venueZoneId,
            )
            val updated =
                guestBookingRepository.updateByGuest(
                    bookingId = bookingId,
                    venueId = venueId,
                    userId = userId,
                    scheduledAt = scheduledAt,
                    partySize = normalizePartySize(request.partySize),
                    comment = normalizeBookingComment(request.comment),
                    venueZoneId = venueZoneId,
                ) ?: throw NotFoundException()
            notifyVenueStaffAboutBooking(
                staffChatNotifier = staffChatNotifier,
                venueRepository = venueRepository,
                outboxEnqueuer = outboxEnqueuer,
                notification =
                    BookingStaffNotification(
                        venueId = updated.venueId,
                        bookingId = updated.id,
                        event = BookingStaffNotificationEvent.UPDATED,
                        scheduledAtText = formatBookingInstant(updated.scheduledAt, venueZoneId),
                        partySize = updated.partySize,
                        comment = updated.comment,
                        displayNumber = updated.displayNumber,
                        guestDisplayName = loadGuestDisplayName(userRepository, userId),
                    ),
            )
            call.respond(
                updated.toResponse(
                    venueName = guestBookingRepository.findVenueName(venueId),
                    zoneId = venueZoneId,
                ),
            )
        }

        post("/cancel") {
            val request = call.receive<GuestBookingCancelRequest>()
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val venueZoneId = venueSettingsRepository.resolveZoneId(venueId)
            ensureGuestActionAvailable(venueId, guestVenueRepository, subscriptionRepository)
            val canceled =
                guestBookingRepository.cancelByGuest(
                    bookingId = normalizePositiveLong(request.bookingId, "bookingId"),
                    venueId = venueId,
                    userId = userId,
                ) ?: throw NotFoundException()
            notifyVenueStaffAboutBooking(
                staffChatNotifier = staffChatNotifier,
                venueRepository = venueRepository,
                outboxEnqueuer = outboxEnqueuer,
                notification =
                    BookingStaffNotification(
                        venueId = canceled.venueId,
                        bookingId = canceled.id,
                        event = BookingStaffNotificationEvent.CANCELLED,
                        scheduledAtText = formatBookingInstant(canceled.scheduledAt, venueZoneId),
                        partySize = canceled.partySize,
                        comment = canceled.comment,
                        displayNumber = canceled.displayNumber,
                        guestDisplayName = loadGuestDisplayName(userRepository, userId),
                    ),
            )
            call.respond(
                canceled.toResponse(
                    venueName = guestBookingRepository.findVenueName(venueId),
                    zoneId = venueZoneId,
                ),
            )
        }

        post("/confirm") {
            val request = call.receive<GuestBookingConfirmRequest>()
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            ensureGuestActionAvailable(venueId, guestVenueRepository, subscriptionRepository)
            val bookingId = normalizePositiveLong(request.bookingId, "bookingId")
            guestBookingRepository.findActiveByGuest(
                bookingId = bookingId,
                venueId = venueId,
                userId = userId,
            ) ?: throw NotFoundException()
            val result =
                guestBookingRepository.confirmGuestAttendance(
                    bookingId = bookingId,
                    userId = userId,
                    expectedScheduleVersionEpochSeconds = request.attendanceScheduleVersion,
                )
            val confirmed =
                when (result.status) {
                    GuestAttendanceConfirmationStatus.APPLIED,
                    GuestAttendanceConfirmationStatus.ALREADY_CONFIRMED,
                    -> result.booking ?: throw NotFoundException()
                    GuestAttendanceConfirmationStatus.STALE,
                    GuestAttendanceConfirmationStatus.NOT_ELIGIBLE,
                    GuestAttendanceConfirmationStatus.TERMINAL,
                    GuestAttendanceConfirmationStatus.NOT_FOUND,
                    -> throw NotFoundException()
                }
            if (result.status == GuestAttendanceConfirmationStatus.APPLIED) {
                notifyVenueStaffAboutGuestAttendance(
                    venueRepository = venueRepository,
                    venueSettingsRepository = venueSettingsRepository,
                    outboxEnqueuer = outboxEnqueuer,
                    booking = confirmed,
                    guestDisplayName = loadGuestDisplayName(userRepository, userId),
                    scheduleVersionEpochSeconds = result.scheduleVersionEpochSeconds,
                )
            }
            call.respond(
                confirmed.toResponse(
                    venueName = guestBookingRepository.findVenueName(venueId),
                    zoneId = venueSettingsRepository.resolveZoneId(venueId),
                ),
            )
        }

        get {
            val venueId = call.requireVenueId()
            ensureGuestActionAvailable(venueId, guestVenueRepository, subscriptionRepository)
            val userId = call.requireUserId()
            val bookings =
                guestBookingRepository.listByUser(
                    venueId = venueId,
                    userId = userId,
                )
            val venueName = guestBookingRepository.findVenueName(venueId)
            val zoneId = venueSettingsRepository.resolveZoneId(venueId)
            call.respond(
                GuestBookingListResponse(
                    items = bookings.map { it.toResponse(venueName, zoneId) },
                ),
            )
        }
    }
}

private suspend fun notifyVenueStaffAboutBooking(
    staffChatNotifier: StaffChatNotifier?,
    venueRepository: VenueRepository,
    outboxEnqueuer: TelegramOutboxEnqueuer,
    notification: BookingStaffNotification,
) {
    if (staffChatNotifier != null) {
        staffChatNotifier.notifyBookingNow(notification)
        return
    }
    val venue = venueRepository.findVenueById(notification.venueId) ?: return
    val chatId = venue.staffChatId ?: return
    val title =
        when (notification.event) {
            BookingStaffNotificationEvent.CREATED -> "📅 Новое бронирование"
            BookingStaffNotificationEvent.UPDATED -> "📅 Обновлено бронирование"
            BookingStaffNotificationEvent.CANCELLED -> "📅 Гость отменил бронирование"
            BookingStaffNotificationEvent.VENUE_CANCELLED -> "❌ Бронирование отменено заведением"
        }
    val text =
        buildString {
            append(title)
            notification.displayNumber?.let { append(" №").append(it) }
            notification.scheduledAtText?.let { append(" на ").append(it) }
            append('\n').append("Гость: ").append(notification.guestDisplayName?.takeIf { it.isNotBlank() } ?: "Гость")
        }
    outboxEnqueuer.enqueueSendMessage(chatId = chatId, text = text)
}

private suspend fun notifyVenueStaffAboutGuestAttendance(
    venueRepository: VenueRepository,
    venueSettingsRepository: VenueSettingsRepository,
    outboxEnqueuer: TelegramOutboxEnqueuer,
    booking: BookingRecord,
    guestDisplayName: String?,
    scheduleVersionEpochSeconds: Long?,
) {
    val venue = venueRepository.findVenueById(booking.venueId) ?: return
    val chatId = venue.staffChatId ?: return
    val text =
        buildGuestAttendanceStaffChatText(
            booking = booking,
            guestDisplayName = guestDisplayName,
            zoneId = venueSettingsRepository.resolveZoneId(booking.venueId),
        )
    outboxEnqueuer.enqueueSendMessage(
        chatId = chatId,
        text = text,
        replyMarkup = ReplyKeyboardRemove(removeKeyboard = true),
        dedupeKey = guestAttendanceStaffDedupeKey(booking.id, scheduleVersionEpochSeconds),
    )
}

private fun guestAttendanceStaffDedupeKey(
    bookingId: Long,
    scheduleVersionEpochSeconds: Long?,
): String = "booking-guest-attendance:$bookingId:${scheduleVersionEpochSeconds ?: "current"}"

private suspend fun loadGuestDisplayName(
    userRepository: UserRepository,
    userId: Long,
): String? =
    runCatching { userRepository.findGuestProfile(userId)?.guestDisplayName }
        .getOrNull()

private fun normalizePositiveLong(
    value: Long,
    fieldName: String,
): Long {
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

private suspend fun requireBookingWithinVenueHours(
    venueBookingHoursRepository: VenueBookingHoursRepository,
    venueId: Long,
    scheduledAt: Instant,
    zoneId: ZoneId,
) {
    when (
        venueBookingHoursRepository.checkBookingStartAvailability(
            venueId = venueId,
            scheduledAt = scheduledAt,
            zoneId = zoneId,
        )
    ) {
        BookingStartAvailability.ALLOWED -> Unit
        BookingStartAvailability.NOT_CONFIGURED -> throw VenueScheduleNotConfiguredException()
        BookingStartAvailability.OUTSIDE_HOURS ->
            throw InvalidInputException(
                "scheduledAt is outside venue working hours",
            )
    }
}

private fun formatBookingInstant(
    value: Instant,
    zoneId: ZoneId = ZoneOffset.UTC,
): String = bookingInstantFormatter.format(value.atZone(zoneId))

private fun formatBookingDisplayLabel(displayNumber: Int?): String = displayNumber?.let { "Бронь №$it" } ?: "Бронь"

private fun humanizeBookingStatus(status: BookingStatus): String =
    when (status) {
        BookingStatus.PENDING -> "Ожидает подтверждения"
        BookingStatus.CONFIRMED -> "Подтверждена"
        BookingStatus.CHANGED -> "Время изменено"
        BookingStatus.CANCELED -> "Отменена"
        BookingStatus.EXPIRED -> "Истекла"
        BookingStatus.NO_SHOW -> "Не состоялась"
        BookingStatus.SEATED -> "Гость пришёл"
    }

private fun scheduledLocalDate(
    value: Instant,
    zoneId: ZoneId,
): String = LocalDateTime.ofInstant(value, zoneId).toLocalDate().toString()

private fun scheduledLocalTime(
    value: Instant,
    zoneId: ZoneId,
): String = LocalDateTime.ofInstant(value, zoneId).toLocalTime().format(bookingTimeFormatter)

private fun displayDateTime(
    value: Instant,
    zoneId: ZoneId,
): String = LocalDateTime.ofInstant(value, zoneId).format(bookingDisplayFormatter)

private fun displayTime(
    value: Instant,
    zoneId: ZoneId,
): String = LocalDateTime.ofInstant(value, zoneId).format(bookingTimeFormatter)

private fun BookingRecord.toResponse(
    venueName: String? = null,
    zoneId: ZoneId = ZoneOffset.UTC,
): GuestBookingResponse =
    GuestBookingResponse(
        bookingId = id,
        venueId = venueId,
        status = status.toApi(),
        scheduledAt = formatBookingInstant(scheduledAt, zoneId),
        partySize = partySize,
        comment = comment,
        lastGuestConfirmationAt = lastGuestConfirmationAt?.let { formatBookingInstant(it, zoneId) },
        attendanceScheduleVersion = attendanceScheduleVersionEpochSeconds(),
        displayNumber = displayNumber,
        displayLabel = formatBookingDisplayLabel(displayNumber),
        venueName = venueName,
        statusLabel = humanizeBookingStatus(status),
        scheduledAtDisplay = displayDateTime(scheduledAt, zoneId),
        scheduledLocalDate = scheduledLocalDate(scheduledAt, zoneId),
        scheduledLocalTime = scheduledLocalTime(scheduledAt, zoneId),
        arrivalDeadlineAt = arrivalDeadlineAt?.let { formatBookingInstant(it, zoneId) },
        arrivalDeadlineAtDisplay = arrivalDeadlineAt?.let { displayDateTime(it, zoneId) },
        arrivalDeadlineTimeDisplay = arrivalDeadlineAt?.let { displayTime(it, zoneId) },
        canChange = status in guestActionableBookingStatuses,
        canCancel = status in guestActionableBookingStatuses,
    )

private fun UserBookingSummaryRecord.toResponse(zoneId: ZoneId): GuestBookingResponse =
    GuestBookingResponse(
        bookingId = id,
        venueId = venueId,
        status = status.toApi(),
        scheduledAt = formatBookingInstant(scheduledAt, zoneId),
        partySize = partySize,
        comment = comment,
        lastGuestConfirmationAt = lastGuestConfirmationAt?.let { formatBookingInstant(it, zoneId) },
        attendanceScheduleVersion = attendanceScheduleVersionEpochSeconds(),
        displayNumber = displayNumber,
        displayLabel = formatBookingDisplayLabel(displayNumber),
        venueName = venueName,
        statusLabel = humanizeBookingStatus(status),
        scheduledAtDisplay = displayDateTime(scheduledAt, zoneId),
        scheduledLocalDate = scheduledLocalDate(scheduledAt, zoneId),
        scheduledLocalTime = scheduledLocalTime(scheduledAt, zoneId),
        arrivalDeadlineAt = arrivalDeadlineAt?.let { formatBookingInstant(it, zoneId) },
        arrivalDeadlineAtDisplay = arrivalDeadlineAt?.let { displayDateTime(it, zoneId) },
        arrivalDeadlineTimeDisplay = arrivalDeadlineAt?.let { displayTime(it, zoneId) },
        canChange = status in guestActionableBookingStatuses,
        canCancel = status in guestActionableBookingStatuses,
    )
