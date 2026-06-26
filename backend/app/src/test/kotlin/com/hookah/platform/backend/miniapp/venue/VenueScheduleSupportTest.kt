package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.telegram.db.VenueBookingHours
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VenueScheduleSupportTest {
    @Test
    fun `overnight hours stay open after midnight and booking starts stop one hour before close`() {
        val serviceDate = LocalDate.of(2030, 1, 10)
        val hours =
            VenueBookingHours(
                venueId = 1,
                weekday = serviceDate.dayOfWeek.value,
                opensAt = LocalTime.of(18, 0),
                closesAt = LocalTime.of(2, 0),
                isClosed = false,
            )

        assertTrue(hours.containsOpenInstant(serviceDate, LocalDateTime.of(2030, 1, 10, 23, 30)))
        assertTrue(hours.containsOpenInstant(serviceDate, LocalDateTime.of(2030, 1, 11, 1, 30)))
        assertFalse(hours.containsOpenInstant(serviceDate, LocalDateTime.of(2030, 1, 11, 2, 0)))
        assertTrue(hours.containsBookingStart(serviceDate, LocalDateTime.of(2030, 1, 11, 1, 0)))
        assertFalse(hours.containsBookingStart(serviceDate, LocalDateTime.of(2030, 1, 11, 1, 30)))
    }

    @Test
    fun `equal open and close time means full day unless closed flag is set`() {
        val serviceDate = LocalDate.of(2030, 1, 10)
        val fullDay =
            VenueBookingHours(
                venueId = 1,
                weekday = serviceDate.dayOfWeek.value,
                opensAt = LocalTime.MIDNIGHT,
                closesAt = LocalTime.MIDNIGHT,
                isClosed = false,
            )
        val closed =
            fullDay.copy(isClosed = true)

        assertTrue(fullDay.containsOpenInstant(serviceDate, LocalDateTime.of(2030, 1, 10, 12, 0)))
        assertTrue(fullDay.containsBookingStart(serviceDate, LocalDateTime.of(2030, 1, 10, 23, 0)))
        assertFalse(fullDay.containsBookingStart(serviceDate, LocalDateTime.of(2030, 1, 10, 23, 30)))
        assertFalse(closed.containsOpenInstant(serviceDate, LocalDateTime.of(2030, 1, 10, 12, 0)))
        assertFalse(closed.containsBookingStart(serviceDate, LocalDateTime.of(2030, 1, 10, 12, 0)))
    }
}
