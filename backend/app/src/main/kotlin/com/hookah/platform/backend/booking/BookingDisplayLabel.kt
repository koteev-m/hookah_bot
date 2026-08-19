package com.hookah.platform.backend.booking

import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val bookingDisplayLabelDateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.forLanguageTag("ru-RU"))

fun formatBookingDisplayLabel(
    bookingId: Long,
    displayNumber: Int?,
    scheduledAt: Instant?,
    venueZoneId: ZoneId,
): String {
    val identity =
        displayNumber
            ?.takeIf { scheduledAt != null }
            ?.takeIf { it > 0 }
            ?.let { "Бронь №$it" }
            ?: "Бронь #$bookingId"
    val localDateTime = scheduledAt?.atZone(venueZoneId) ?: return identity
    return "$identity · ${bookingDisplayLabelDateTimeFormatter.format(localDateTime)}"
}

fun resolveBookingDisplayZoneId(
    timezone: String?,
    fallback: ZoneId = defaultBookingDisplayZoneId(),
): ZoneId =
    timezone
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: fallback

fun defaultBookingDisplayZoneId(): ZoneId = ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE)
