package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.api.StaffModuleDisabledException
import com.hookah.platform.backend.api.TodayStaffSourceScheduleException
import java.sql.Connection

class VenueStaffModuleGuard(
    private val settingsRepository: VenueStaffModuleSettingsRepository,
) {
    suspend fun requireEnabledAfterAccessCheck(venueId: Long): VenueStaffModuleSettings {
        val settings = settingsRepository.get(venueId)
        return settings.requireEnabled()
    }

    suspend fun requireManualTodaySourceAfterAccessCheck(venueId: Long) {
        requireEnabledAfterAccessCheck(venueId).requireManualTodaySource()
    }
}

internal fun lockVenueForStaffModuleGuard(
    connection: Connection,
    venueId: Long,
) {
    connection.prepareStatement("SELECT id FROM venues WHERE id = ? FOR UPDATE").use { statement ->
        statement.setLong(1, venueId)
        statement.executeQuery().use { resultSet -> check(resultSet.next()) }
    }
}

internal fun requireStaffModuleEnabledAfterVenueLock(
    connection: Connection,
    venueId: Long,
): VenueStaffModuleSettings = loadStaffModuleSettings(connection, venueId).requireEnabled()

internal fun requireManualTodaySourceAfterVenueLock(
    connection: Connection,
    venueId: Long,
) {
    requireStaffModuleEnabledAfterVenueLock(connection, venueId).requireManualTodaySource()
}

private fun loadStaffModuleSettings(
    connection: Connection,
    venueId: Long,
): VenueStaffModuleSettings =
    connection.prepareStatement(
        """
        SELECT team_schedule_module_enabled,
               guest_team_visible,
               today_staff_source,
               updated_at
        FROM venue_settings
        WHERE venue_id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setLong(1, venueId)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) {
                VenueStaffModuleSettingsRepository.defaults()
            } else {
                VenueStaffModuleSettings(
                    teamScheduleModuleEnabled = resultSet.getBoolean("team_schedule_module_enabled"),
                    guestTeamVisible = resultSet.getBoolean("guest_team_visible"),
                    todayStaffSource = TodayStaffSource.valueOf(resultSet.getString("today_staff_source")),
                    updatedAt = resultSet.getTimestamp("updated_at").toInstant(),
                )
            }
        }
    }

private fun VenueStaffModuleSettings.requireEnabled(): VenueStaffModuleSettings {
    if (!teamScheduleModuleEnabled) throw StaffModuleDisabledException()
    return this
}

private fun VenueStaffModuleSettings.requireManualTodaySource() {
    if (todayStaffSource == TodayStaffSource.SCHEDULE) throw TodayStaffSourceScheduleException()
}
