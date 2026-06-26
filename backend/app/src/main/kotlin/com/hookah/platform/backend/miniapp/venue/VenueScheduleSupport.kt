package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.telegram.db.VenueBookingHours
import com.hookah.platform.backend.telegram.db.VenueBookingHoursRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val scheduleTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

data class VenueScheduleWindow(
    val serviceDate: LocalDate,
    val opensAt: LocalDateTime,
    val closesAt: LocalDateTime,
)

enum class BookingStartAvailability {
    ALLOWED,
    NOT_CONFIGURED,
    OUTSIDE_HOURS,
}

fun formatScheduleTime(value: LocalTime): String = value.format(scheduleTimeFormatter)

fun parseScheduleTime(value: String?): LocalTime? {
    val normalized = value?.trim().orEmpty()
    if (normalized.isBlank()) return null
    return runCatching { LocalTime.parse(normalized, scheduleTimeFormatter) }.getOrNull()
}

fun VenueBookingHours.toScheduleWindow(serviceDate: LocalDate): VenueScheduleWindow? {
    if (isClosed) return null
    val opensAtDateTime = LocalDateTime.of(serviceDate, opensAt)
    val closesAtDateTime =
        if (!closesAt.isAfter(opensAt)) {
            LocalDateTime.of(serviceDate.plusDays(1), closesAt)
        } else {
            LocalDateTime.of(serviceDate, closesAt)
        }
    return VenueScheduleWindow(
        serviceDate = serviceDate,
        opensAt = opensAtDateTime,
        closesAt = closesAtDateTime,
    )
}

fun VenueBookingHours.containsBookingStart(
    serviceDate: LocalDate,
    scheduledLocalDateTime: LocalDateTime,
): Boolean {
    val window = toScheduleWindow(serviceDate) ?: return false
    val lastStart = window.closesAt.minusHours(1)
    return !scheduledLocalDateTime.isBefore(window.opensAt) && !scheduledLocalDateTime.isAfter(lastStart)
}

fun VenueBookingHours.containsOpenInstant(
    serviceDate: LocalDate,
    localDateTime: LocalDateTime,
): Boolean {
    val window = toScheduleWindow(serviceDate) ?: return false
    return !localDateTime.isBefore(window.opensAt) && localDateTime.isBefore(window.closesAt)
}

suspend fun VenueBookingHoursRepository.isBookingStartAllowed(
    venueId: Long,
    scheduledAt: Instant,
    zoneId: ZoneId,
): Boolean =
    checkBookingStartAvailability(
        venueId = venueId,
        scheduledAt = scheduledAt,
        zoneId = zoneId,
    ) == BookingStartAvailability.ALLOWED

suspend fun VenueBookingHoursRepository.checkBookingStartAvailability(
    venueId: Long,
    scheduledAt: Instant,
    zoneId: ZoneId,
): BookingStartAvailability {
    val scheduledLocalDateTime = LocalDateTime.ofInstant(scheduledAt, zoneId)
    val localDate = scheduledLocalDateTime.toLocalDate()
    val sameDateHours = findByVenueAndDate(venueId, localDate)
    if (sameDateHours?.containsBookingStart(localDate, scheduledLocalDateTime) == true) {
        return BookingStartAvailability.ALLOWED
    }
    val previousDate = localDate.minusDays(1)
    val previousDateHours = findByVenueAndDate(venueId, previousDate)
    if (previousDateHours?.containsBookingStart(previousDate, scheduledLocalDateTime) == true) {
        return BookingStartAvailability.ALLOWED
    }
    return if (sameDateHours == null) {
        BookingStartAvailability.NOT_CONFIGURED
    } else {
        BookingStartAvailability.OUTSIDE_HOURS
    }
}

suspend fun VenueBookingHoursRepository.isOpenNow(
    venueId: Long,
    now: Instant,
    zoneId: ZoneId,
): Boolean {
    val localDateTime = LocalDateTime.ofInstant(now, zoneId)
    val localDate = localDateTime.toLocalDate()
    val sameDateHours = findByVenueAndDate(venueId, localDate)
    if (sameDateHours?.containsOpenInstant(localDate, localDateTime) == true) {
        return true
    }
    val previousDate = localDate.minusDays(1)
    val previousDateHours = findByVenueAndDate(venueId, previousDate)
    return previousDateHours?.containsOpenInstant(previousDate, localDateTime) == true
}

fun formatScheduleRange(
    opensAt: LocalTime,
    closesAt: LocalTime,
    isClosed: Boolean,
): String {
    if (isClosed) return "Закрыто"
    if (opensAt == closesAt) return "Круглосуточно"
    return "${formatScheduleTime(opensAt)}-${formatScheduleTime(closesAt)}"
}
