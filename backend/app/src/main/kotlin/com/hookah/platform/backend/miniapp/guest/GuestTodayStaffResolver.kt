package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.miniapp.guest.api.GuestTodayStaffDto
import com.hookah.platform.backend.miniapp.venue.staff.PublicVenueStaffToday
import com.hookah.platform.backend.miniapp.venue.staff.StaffScheduleIntervalState
import com.hookah.platform.backend.miniapp.venue.staff.StaffScheduleLifecycle
import com.hookah.platform.backend.miniapp.venue.staff.TodayStaffSource
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffModuleSettings
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffProfileRepository
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffScheduledShift
import com.hookah.platform.backend.miniapp.venue.staff.computeStaffScheduleLifecycle
import com.hookah.platform.backend.miniapp.venue.staff.resolveStaffScheduleInterval
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class GuestTodayStaffResolver(
    private val venueStaffProfileRepository: VenueStaffProfileRepository,
) {
    suspend fun resolve(
        venueId: Long,
        now: Instant,
        zoneId: ZoneId,
        settings: VenueStaffModuleSettings,
    ): List<GuestTodayStaffDto> {
        if (!settings.teamScheduleModuleEnabled || !settings.guestTeamVisible) {
            return emptyList()
        }
        val today = LocalDateTime.ofInstant(now, zoneId).toLocalDate()
        return when (settings.todayStaffSource) {
            TodayStaffSource.MANUAL ->
                venueStaffProfileRepository
                    .listPublicTodayStaff(venueId, today)
                    .map { it.toGuestDto() }
            TodayStaffSource.SCHEDULE ->
                venueStaffProfileRepository
                    .listScheduledShifts(
                        venueId = venueId,
                        from = today.minusDays(1),
                        to = today,
                    )
                    .mapNotNull { it.toActivePublicCandidate(now, zoneId) }
                    .sortedWith(
                        compareBy(
                            { it.startsAt },
                            { it.row.profile.displayName },
                            { it.row.profile.id },
                            { it.row.shift.id },
                        ),
                    )
                    .distinctBy { it.row.profile.id }
                    .map { candidate ->
                        GuestTodayStaffDto(
                            id = candidate.row.profile.id,
                            displayName = candidate.row.profile.displayName,
                            roleLabel = candidate.row.profile.roleLabel,
                            subtype = candidate.row.profile.subtype,
                            photoRef = null,
                            bio = candidate.row.profile.bio,
                            tags = candidate.row.profile.tags,
                            shiftDate = today.toString(),
                            startsAt = null,
                            endsAt = null,
                            shiftStatus = "active",
                        )
                    }
        }
    }
}

private data class ActivePublicStaffCandidate(
    val row: VenueStaffScheduledShift,
    val startsAt: Instant,
)

private fun VenueStaffScheduledShift.toActivePublicCandidate(
    now: Instant,
    zoneId: ZoneId,
): ActivePublicStaffCandidate? {
    if (!profile.isGuestVisible || profile.publishedAt == null || profile.disabledAt != null) return null
    if (shift.status.lowercase(Locale.ROOT) !in PUBLIC_SCHEDULE_STORED_STATUSES) return null
    val resolution = resolveStaffScheduleInterval(shift.shiftDate, shift.startsAt, shift.endsAt, zoneId)
    val interval = resolution.interval ?: return null
    if (resolution.state != StaffScheduleIntervalState.VALID) return null
    if (computeStaffScheduleLifecycle(shift.status, interval, now) != StaffScheduleLifecycle.ACTIVE) return null
    return ActivePublicStaffCandidate(row = this, startsAt = interval.startsAt)
}

private fun PublicVenueStaffToday.toGuestDto(): GuestTodayStaffDto =
    GuestTodayStaffDto(
        id = id,
        displayName = displayName,
        roleLabel = roleLabel,
        subtype = subtype,
        photoRef = null,
        bio = bio,
        tags = tags,
        shiftDate = shiftDate.toString(),
        startsAt = startsAt?.toString(),
        endsAt = endsAt?.toString(),
        shiftStatus = shiftStatus,
    )

private val PUBLIC_SCHEDULE_STORED_STATUSES = setOf("scheduled", "active")
