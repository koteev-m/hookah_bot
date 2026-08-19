package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.booking.formatBookingDisplayLabel
import com.hookah.platform.backend.miniapp.guest.db.BookingRecord
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val guestAttendanceTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun buildGuestAttendanceStaffChatText(
    booking: BookingRecord,
    guestDisplayName: String?,
    zoneId: ZoneId,
): String {
    val bookingLabel =
        formatBookingDisplayLabel(
            bookingId = booking.id,
            displayNumber = booking.displayNumber,
            scheduledAt = booking.scheduledAt,
            venueZoneId = zoneId,
        )
    val deadlineText =
        booking.arrivalDeadlineAt
            ?.let { LocalDateTime.ofInstant(it, zoneId).format(guestAttendanceTimeFormatter) }
            ?: "не указано"
    val guestName = guestDisplayName?.takeIf { it.isNotBlank() } ?: "имя не указано"
    val partySize = booking.partySize?.toString() ?: "не указано"
    return buildString {
        append("✅ Гость подтвердил визит")
        append("\n\n").append(bookingLabel)
        append("\nГость: ").append(guestName)
        append("\nГостей: ").append(partySize)
        append("\nДержим стол до ").append(deadlineText)
    }
}
