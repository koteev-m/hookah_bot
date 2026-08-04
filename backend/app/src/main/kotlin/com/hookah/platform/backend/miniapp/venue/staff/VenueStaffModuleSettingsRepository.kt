package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.StaffModuleSettingsStaleException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.VenuePermission
import com.hookah.platform.backend.miniapp.venue.VenuePermissions
import com.hookah.platform.backend.miniapp.venue.VenueRoleMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

enum class TodayStaffSource {
    MANUAL,
    SCHEDULE,
}

data class VenueStaffModuleSettings(
    val teamScheduleModuleEnabled: Boolean,
    val guestTeamVisible: Boolean,
    val todayStaffSource: TodayStaffSource,
    val updatedAt: Instant,
)

data class VenueStaffModuleSettingsWrite(
    val teamScheduleModuleEnabled: Boolean,
    val guestTeamVisible: Boolean,
    val todayStaffSource: TodayStaffSource,
)

class VenueStaffModuleSettingsRepository(
    private val dataSource: DataSource?,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun get(venueId: Long): VenueStaffModuleSettings {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    select(connection, venueId) ?: defaults()
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun getAll(venueIds: Collection<Long>): Map<Long, VenueStaffModuleSettings> {
        val ids = venueIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val result = ids.associateWith { defaults() }.toMutableMap()
                    val placeholders = ids.joinToString(",") { "?" }
                    connection.prepareStatement(
                        """
                        SELECT venue_id,
                               team_schedule_module_enabled,
                               guest_team_visible,
                               today_staff_source,
                               updated_at
                        FROM venue_settings
                        WHERE venue_id IN ($placeholders)
                        """.trimIndent(),
                    ).use { statement ->
                        ids.forEachIndexed { index, venueId -> statement.setLong(index + 1, venueId) }
                        statement.executeQuery().use { resultSet ->
                            while (resultSet.next()) {
                                result[resultSet.getLong("venue_id")] = resultSet.toSettings()
                            }
                        }
                    }
                    result
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun update(
        venueId: Long,
        actorUserId: Long,
        expectedUpdatedAt: Instant,
        input: VenueStaffModuleSettingsWrite,
        auditLogRepository: AuditLogRepository,
    ): VenueStaffModuleSettings {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    lockVenue(connection, venueId)
                    requireSettingsActorAuthorized(connection, venueId, actorUserId)
                    val persisted = select(connection, venueId, forUpdate = true)
                    val current = persisted ?: defaults()
                    if (current.updatedAt != expectedUpdatedAt) {
                        throw StaffModuleSettingsStaleException()
                    }
                    val changedFields = current.changedFields(input)
                    if (persisted != null && changedFields.isEmpty()) {
                        connection.commit()
                        return@withContext current
                    }

                    val updatedAt = nextUpdatedAt(current.updatedAt)
                    val saved =
                        VenueStaffModuleSettings(
                            teamScheduleModuleEnabled = input.teamScheduleModuleEnabled,
                            guestTeamVisible = input.guestTeamVisible,
                            todayStaffSource = input.todayStaffSource,
                            updatedAt = updatedAt,
                        )
                    if (persisted == null) {
                        insert(connection, venueId, saved)
                    } else {
                        update(connection, venueId, saved)
                    }
                    if (changedFields.isNotEmpty()) {
                        auditLogRepository.appendJson(
                            connection = connection,
                            actorUserId = actorUserId,
                            action = STAFF_MODULE_SETTINGS_UPDATED_ACTION,
                            entityType = STAFF_MODULE_SETTINGS_AUDIT_ENTITY_TYPE,
                            entityId = venueId,
                            payload =
                                buildJsonObject {
                                    put("venueId", venueId)
                                    put(
                                        "changedFields",
                                        buildJsonArray { changedFields.forEach { add(JsonPrimitive(it)) } },
                                    )
                                    put("old", current.toAuditJson())
                                    put("new", saved.toAuditJson())
                                },
                        )
                    }
                    connection.commit()
                    saved
                } catch (e: SQLException) {
                    connection.rollback()
                    throw DatabaseUnavailableException()
                } catch (e: Throwable) {
                    connection.rollback()
                    throw e
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }
        }
    }

    private fun lockVenue(
        connection: Connection,
        venueId: Long,
    ) {
        connection.prepareStatement("SELECT id FROM venues WHERE id = ? FOR UPDATE").use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { it.next() }
        }
    }

    private fun requireSettingsActorAuthorized(
        connection: Connection,
        venueId: Long,
        actorUserId: Long,
    ) {
        val role =
            connection.prepareStatement(
                """
                SELECT role
                FROM venue_members
                WHERE venue_id = ? AND user_id = ?
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, actorUserId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) VenueRoleMapping.fromDb(resultSet.getString("role")) else null
                }
            }
        if (role == null || VenuePermission.STAFF_MODULE_SETTINGS_MANAGE !in VenuePermissions.forRole(role)) {
            throw ForbiddenException()
        }
    }

    private fun select(
        connection: Connection,
        venueId: Long,
        forUpdate: Boolean = false,
    ): VenueStaffModuleSettings? =
        connection.prepareStatement(
            """
            SELECT team_schedule_module_enabled,
                   guest_team_visible,
                   today_staff_source,
                   updated_at
            FROM venue_settings
            WHERE venue_id = ?
            ${if (forUpdate) "FOR UPDATE" else ""}
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toSettings() else null
            }
        }

    private fun insert(
        connection: Connection,
        venueId: Long,
        settings: VenueStaffModuleSettings,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO venue_settings (
                venue_id,
                team_schedule_module_enabled,
                guest_team_visible,
                today_staff_source,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setBoolean(2, settings.teamScheduleModuleEnabled)
            statement.setBoolean(3, settings.guestTeamVisible)
            statement.setString(4, settings.todayStaffSource.name)
            statement.setTimestamp(5, Timestamp.from(settings.updatedAt))
            statement.executeUpdate()
        }
    }

    private fun update(
        connection: Connection,
        venueId: Long,
        settings: VenueStaffModuleSettings,
    ) {
        connection.prepareStatement(
            """
            UPDATE venue_settings
            SET team_schedule_module_enabled = ?,
                guest_team_visible = ?,
                today_staff_source = ?,
                updated_at = ?
            WHERE venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setBoolean(1, settings.teamScheduleModuleEnabled)
            statement.setBoolean(2, settings.guestTeamVisible)
            statement.setString(3, settings.todayStaffSource.name)
            statement.setTimestamp(4, Timestamp.from(settings.updatedAt))
            statement.setLong(5, venueId)
            statement.executeUpdate()
        }
    }

    private fun nextUpdatedAt(current: Instant): Instant {
        return nextStaffShiftUpdatedAt(clock.instant(), current)
    }

    private fun ResultSet.toSettings(): VenueStaffModuleSettings =
        VenueStaffModuleSettings(
            teamScheduleModuleEnabled = getBoolean("team_schedule_module_enabled"),
            guestTeamVisible = getBoolean("guest_team_visible"),
            todayStaffSource = TodayStaffSource.valueOf(getString("today_staff_source")),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    companion object {
        val MISSING_ROW_UPDATED_AT: Instant = Instant.EPOCH

        fun defaults(): VenueStaffModuleSettings =
            VenueStaffModuleSettings(
                teamScheduleModuleEnabled = true,
                guestTeamVisible = true,
                todayStaffSource = TodayStaffSource.MANUAL,
                updatedAt = MISSING_ROW_UPDATED_AT,
            )
    }
}

private fun VenueStaffModuleSettings.changedFields(input: VenueStaffModuleSettingsWrite): List<String> =
    buildList {
        if (teamScheduleModuleEnabled != input.teamScheduleModuleEnabled) add("teamScheduleModuleEnabled")
        if (guestTeamVisible != input.guestTeamVisible) add("guestTeamVisible")
        if (todayStaffSource != input.todayStaffSource) add("todayStaffSource")
    }

private fun VenueStaffModuleSettings.toAuditJson() =
    buildJsonObject {
        put("teamScheduleModuleEnabled", teamScheduleModuleEnabled)
        put("guestTeamVisible", guestTeamVisible)
        put("todayStaffSource", todayStaffSource.name)
    }

private const val STAFF_MODULE_SETTINGS_UPDATED_ACTION = "STAFF_MODULE_SETTINGS_UPDATED"
private const val STAFF_MODULE_SETTINGS_AUDIT_ENTITY_TYPE = "venue_staff_module_settings"
