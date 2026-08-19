package com.hookah.platform.backend.booking

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BookingDisplayLabelTest {
    private val fixture = loadBookingDisplayLabelFixture()

    @Test
    fun `shared fixture keeps authoritative labels and stable fallbacks aligned`() {
        fixture.cases.forEach { case ->
            assertEquals(
                case.expectedLabel,
                formatBookingDisplayLabel(
                    bookingId = case.bookingId,
                    displayNumber = case.displayNumber,
                    scheduledAt = case.scheduledAt?.let(Instant::parse),
                    venueZoneId = resolveBookingDisplayZoneId(case.venueTimezone),
                ),
                case.id,
            )
        }

        val sameNumberLabels =
            fixture.cases
                .filter { it.id == "normal-display-number" || it.id == "same-number-different-date" }
                .map { it.expectedLabel }
        assertEquals(2, sameNumberLabels.size)
        assertNotEquals(sameNumberLabels.first(), sameNumberLabels.last())
    }

    @Test
    fun `shared fixture keeps invalid and missing timezone on the product default`() {
        val productDefault = ZoneId.of(fixture.defaultTimezone)

        assertEquals(productDefault, defaultBookingDisplayZoneId())
        fixture.cases
            .filter { it.id == "invalid-timezone-fallback" || it.id == "missing-timezone-fallback" }
            .forEach { case -> assertEquals(productDefault, resolveBookingDisplayZoneId(case.venueTimezone), case.id) }
    }
}

private fun loadBookingDisplayLabelFixture(): BookingDisplayLabelFixture {
    val resource =
        requireNotNull(
            BookingDisplayLabelTest::class.java.classLoader.getResource("booking-display-label-cases.json"),
        ) { "Missing shared booking display label fixture" }
    return Json.decodeFromString(resource.readText())
}

@Serializable
private data class BookingDisplayLabelFixture(
    val defaultTimezone: String,
    val browserTimezones: List<String>,
    val cases: List<BookingDisplayLabelCase>,
)

@Serializable
private data class BookingDisplayLabelCase(
    val id: String,
    val bookingId: Long,
    val displayNumber: Int?,
    val displayLabel: String?,
    val scheduledAt: String?,
    val scheduledAtDisplay: String?,
    val venueTimezone: String?,
    val legacyLabel: String?,
    val expectedLabel: String,
)
