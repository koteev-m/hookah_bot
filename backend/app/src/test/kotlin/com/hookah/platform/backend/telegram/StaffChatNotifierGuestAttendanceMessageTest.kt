package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.miniapp.guest.db.BookingRecord
import com.hookah.platform.backend.miniapp.guest.db.BookingStatus
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaffChatNotifierGuestAttendanceMessageTest {
    @Test
    fun `guest attendance staff message uses operational booking fields without raw ids or contacts`() {
        val text =
            buildGuestAttendanceStaffChatText(
                booking =
                    BookingRecord(
                        id = 777L,
                        venueId = 10L,
                        userId = 424242L,
                        scheduledAt = Instant.parse("2030-01-10T18:05:00Z"),
                        partySize = 4,
                        comment = "у окна",
                        status = BookingStatus.CONFIRMED,
                        displayNumber = 7,
                        arrivalDeadlineAt = Instant.parse("2030-01-10T18:20:00Z"),
                    ),
                guestDisplayName = "Алексей",
                zoneId = ZoneId.of("Europe/Moscow"),
            )

        assertEquals(
            """
            ✅ Гость подтвердил визит

            Бронь №7 · 10.01.2030, 21:05
            Гость: Алексей
            Гостей: 4
            Держим стол до 21:20
            """.trimIndent(),
            text,
        )
        assertFalse(text.contains("777"))
        assertFalse(text.contains("424242"))
        assertFalse(text.contains("u424242"))
        assertFalse(text.contains("у окна"))
    }

    @Test
    fun `guest attendance staff message renders missing guest name safely`() {
        val text =
            buildGuestAttendanceStaffChatText(
                booking =
                    BookingRecord(
                        id = 778L,
                        venueId = 10L,
                        userId = 424243L,
                        scheduledAt = Instant.parse("2030-01-10T18:05:00Z"),
                        partySize = 2,
                        comment = null,
                        status = BookingStatus.CHANGED,
                        displayNumber = 8,
                        arrivalDeadlineAt = Instant.parse("2030-01-10T18:20:00Z"),
                    ),
                guestDisplayName = " ",
                zoneId = ZoneId.of("Europe/Moscow"),
            )

        assertTrue(text.contains("Гость: имя не указано"))
    }
}
