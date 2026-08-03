package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.miniapp.venue.staff.STAFF_SHIFT_CANCELED_STATUS
import com.hookah.platform.backend.miniapp.venue.staff.STAFF_SHIFT_SCHEDULED_STATUS
import com.hookah.platform.backend.miniapp.venue.staff.StaffScheduleIntervalState
import com.hookah.platform.backend.miniapp.venue.staff.StaffScheduleLifecycle
import com.hookah.platform.backend.miniapp.venue.staff.StaffScheduleResolvedInterval
import com.hookah.platform.backend.miniapp.venue.staff.computeStaffScheduleLifecycle
import com.hookah.platform.backend.miniapp.venue.staff.nextStaffShiftUpdatedAt
import com.hookah.platform.backend.miniapp.venue.staff.resolveStaffScheduleInterval
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VenueStaffRoutesTestTimeContract {
    @Test
    fun `overnight and equal clock intervals use the next local day`() {
        val overnight =
            resolveStaffScheduleInterval(
                shiftDate = LocalDate.parse("2026-08-10"),
                startsAt = LocalTime.parse("22:00"),
                endsAt = LocalTime.parse("06:00"),
                zoneId = ZoneId.of("UTC"),
            )
        val overnightInterval = assertNotNull(overnight.interval)

        assertEquals(StaffScheduleIntervalState.VALID, overnight.state)
        assertTrue(overnightInterval.endsNextDay)
        assertEquals(Instant.parse("2026-08-10T22:00:00Z"), overnightInterval.startsAt)
        assertEquals(Instant.parse("2026-08-11T06:00:00Z"), overnightInterval.endsAt)
        assertEquals(Duration.ofHours(8), Duration.between(overnightInterval.startsAt, overnightInterval.endsAt))

        val fullDay =
            resolveStaffScheduleInterval(
                shiftDate = LocalDate.parse("2026-08-10"),
                startsAt = LocalTime.parse("09:00"),
                endsAt = LocalTime.parse("09:00"),
                zoneId = ZoneId.of("UTC"),
            )
        val fullDayInterval = assertNotNull(fullDay.interval)

        assertEquals(StaffScheduleIntervalState.VALID, fullDay.state)
        assertTrue(fullDayInterval.endsNextDay)
        assertEquals(Duration.ofHours(24), Duration.between(fullDayInterval.startsAt, fullDayInterval.endsAt))
    }

    @Test
    fun `DST gap rejects and overlap uses the earlier valid offset`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val gap =
            resolveStaffScheduleInterval(
                shiftDate = LocalDate.parse("2026-03-29"),
                startsAt = LocalTime.parse("02:30"),
                endsAt = LocalTime.parse("03:30"),
                zoneId = berlin,
            )

        assertEquals(StaffScheduleIntervalState.INVALID, gap.state)
        assertEquals(null, gap.interval)

        val overlap =
            resolveStaffScheduleInterval(
                shiftDate = LocalDate.parse("2026-10-25"),
                startsAt = LocalTime.parse("02:30"),
                endsAt = LocalTime.parse("03:30"),
                zoneId = berlin,
            )
        val overlapInterval = assertNotNull(overlap.interval)

        assertEquals(StaffScheduleIntervalState.VALID, overlap.state)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), overlapInterval.startsAt)
        assertEquals(Instant.parse("2026-10-25T02:30:00Z"), overlapInterval.endsAt)
    }

    @Test
    fun `fall back interval longer than twenty four hours is rejected`() {
        val resolution =
            resolveStaffScheduleInterval(
                shiftDate = LocalDate.parse("2026-10-25"),
                startsAt = LocalTime.parse("01:30"),
                endsAt = LocalTime.parse("01:30"),
                zoneId = ZoneId.of("Europe/Berlin"),
            )

        assertEquals(StaffScheduleIntervalState.INVALID, resolution.state)
        assertEquals(null, resolution.interval)
    }

    @Test
    fun `lifecycle uses exact start and end boundaries and stored cancellation wins`() {
        val interval =
            StaffScheduleResolvedInterval(
                startsAt = Instant.parse("2026-08-10T10:00:00Z"),
                endsAt = Instant.parse("2026-08-10T18:00:00Z"),
                endsNextDay = false,
            )

        assertEquals(
            StaffScheduleLifecycle.SCHEDULED,
            computeStaffScheduleLifecycle(
                STAFF_SHIFT_SCHEDULED_STATUS,
                interval,
                Instant.parse("2026-08-10T09:59:59.999Z"),
            ),
        )
        assertEquals(
            StaffScheduleLifecycle.ACTIVE,
            computeStaffScheduleLifecycle(
                STAFF_SHIFT_SCHEDULED_STATUS,
                interval,
                Instant.parse("2026-08-10T10:00:00Z"),
            ),
        )
        assertEquals(
            StaffScheduleLifecycle.ACTIVE,
            computeStaffScheduleLifecycle(
                STAFF_SHIFT_SCHEDULED_STATUS,
                interval,
                Instant.parse("2026-08-10T17:59:59.999Z"),
            ),
        )
        assertEquals(
            StaffScheduleLifecycle.COMPLETED,
            computeStaffScheduleLifecycle(
                STAFF_SHIFT_SCHEDULED_STATUS,
                interval,
                Instant.parse("2026-08-10T18:00:00Z"),
            ),
        )
        assertEquals(
            StaffScheduleLifecycle.CANCELED,
            computeStaffScheduleLifecycle(
                STAFF_SHIFT_CANCELED_STATUS,
                interval,
                Instant.parse("2026-08-10T09:00:00Z"),
            ),
        )
    }

    @Test
    fun `updated at token is millisecond precision and strictly monotonic`() {
        val previous = Instant.parse("2026-08-01T10:15:30.987654Z")

        assertEquals(
            Instant.parse("2026-08-01T10:15:31.123Z"),
            nextStaffShiftUpdatedAt(
                now = Instant.parse("2026-08-01T10:15:31.123999Z"),
                previous = previous,
            ),
        )
        assertEquals(
            Instant.parse("2026-08-01T10:15:30.988Z"),
            nextStaffShiftUpdatedAt(
                now = Instant.parse("2026-08-01T10:15:30.500Z"),
                previous = previous,
            ),
        )
    }
}
