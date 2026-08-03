package com.hookah.platform.backend.miniapp.venue.staff

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class StaffScheduleLifecycle {
    SCHEDULED,
    ACTIVE,
    COMPLETED,
    CANCELED,
}

enum class StaffScheduleConfirmationState {
    SCHEDULED,
    ACTIVE,
    INVALID_INTERVAL,
}

enum class StaffScheduleAllowedAction {
    UPDATE,
    CANCEL,
}

enum class StaffScheduleIntervalState {
    VALID,
    INCOMPLETE,
    INVALID,
}

data class StaffScheduleResolvedInterval(
    val startsAt: Instant,
    val endsAt: Instant,
    val endsNextDay: Boolean,
)

data class StaffScheduleIntervalResolution(
    val state: StaffScheduleIntervalState,
    val interval: StaffScheduleResolvedInterval? = null,
)

data class VenueStaffScheduledShift(
    val profile: VenueStaffProfile,
    val shift: VenueStaffShift,
)

fun resolveStaffScheduleInterval(
    shiftDate: LocalDate,
    startsAt: LocalTime?,
    endsAt: LocalTime?,
    zoneId: ZoneId,
): StaffScheduleIntervalResolution {
    if (startsAt == null || endsAt == null) {
        return StaffScheduleIntervalResolution(StaffScheduleIntervalState.INCOMPLETE)
    }
    val endsNextDay = !endsAt.isAfter(startsAt)
    val startLocal = LocalDateTime.of(shiftDate, startsAt)
    val endLocal = LocalDateTime.of(if (endsNextDay) shiftDate.plusDays(1) else shiftDate, endsAt)
    val startInstant =
        resolveEarlierValidInstant(startLocal, zoneId)
            ?: return StaffScheduleIntervalResolution(StaffScheduleIntervalState.INVALID)
    val endInstant =
        resolveEarlierValidInstant(endLocal, zoneId)
            ?: return StaffScheduleIntervalResolution(StaffScheduleIntervalState.INVALID)
    val duration = Duration.between(startInstant, endInstant)
    if (duration.isZero || duration.isNegative || duration > MAX_STAFF_SHIFT_DURATION) {
        return StaffScheduleIntervalResolution(StaffScheduleIntervalState.INVALID)
    }
    return StaffScheduleIntervalResolution(
        state = StaffScheduleIntervalState.VALID,
        interval =
            StaffScheduleResolvedInterval(
                startsAt = startInstant,
                endsAt = endInstant,
                endsNextDay = endsNextDay,
            ),
    )
}

fun computeStaffScheduleLifecycle(
    storedStatus: String,
    interval: StaffScheduleResolvedInterval,
    now: Instant,
): StaffScheduleLifecycle =
    when {
        storedStatus.equals(STAFF_SHIFT_CANCELED_STATUS, ignoreCase = true) -> StaffScheduleLifecycle.CANCELED
        now.isBefore(interval.startsAt) -> StaffScheduleLifecycle.SCHEDULED
        now.isBefore(interval.endsAt) -> StaffScheduleLifecycle.ACTIVE
        else -> StaffScheduleLifecycle.COMPLETED
    }

fun staffScheduleConfirmationState(lifecycle: StaffScheduleLifecycle): StaffScheduleConfirmationState? =
    when (lifecycle) {
        StaffScheduleLifecycle.SCHEDULED -> StaffScheduleConfirmationState.SCHEDULED
        StaffScheduleLifecycle.ACTIVE -> StaffScheduleConfirmationState.ACTIVE
        StaffScheduleLifecycle.COMPLETED,
        StaffScheduleLifecycle.CANCELED,
        -> null
    }

fun invalidStaffScheduleAllowedActions(
    shift: VenueStaffShift,
    venueToday: LocalDate,
): Set<StaffScheduleAllowedAction> {
    if (shift.status.equals(STAFF_SHIFT_CANCELED_STATUS, ignoreCase = true)) {
        return emptySet()
    }
    return when {
        shift.shiftDate.isBefore(venueToday) -> emptySet()
        shift.shiftDate == venueToday -> setOf(StaffScheduleAllowedAction.CANCEL)
        shift.hasScheduleDefaults() -> setOf(StaffScheduleAllowedAction.UPDATE, StaffScheduleAllowedAction.CANCEL)
        else -> setOf(StaffScheduleAllowedAction.CANCEL)
    }
}

fun VenueStaffShift.hasScheduleDefaults(): Boolean =
    status.equals(STAFF_SHIFT_SCHEDULED_STATUS, ignoreCase = true) &&
        !isGuestVisible &&
        !manuallyMarkedActive

fun nextStaffShiftUpdatedAt(
    now: Instant,
    previous: Instant,
): Instant {
    val normalizedNow = now.truncatedTo(ChronoUnit.MILLIS)
    val minimumNext = previous.truncatedTo(ChronoUnit.MILLIS).plusMillis(1)
    return if (normalizedNow.isAfter(minimumNext)) normalizedNow else minimumNext
}

private fun resolveEarlierValidInstant(
    localDateTime: LocalDateTime,
    zoneId: ZoneId,
): Instant? {
    val offsets = zoneId.rules.getValidOffsets(localDateTime)
    val earlierOffset = offsets.firstOrNull() ?: return null
    return localDateTime.toInstant(earlierOffset)
}

const val STAFF_SHIFT_SCHEDULED_STATUS = "scheduled"
const val STAFF_SHIFT_CANCELED_STATUS = "canceled"

private val MAX_STAFF_SHIFT_DURATION: Duration = Duration.ofHours(24)
