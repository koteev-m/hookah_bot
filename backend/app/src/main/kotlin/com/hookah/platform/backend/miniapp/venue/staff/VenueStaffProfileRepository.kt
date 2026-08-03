package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.StaffShiftConfirmationStaleException
import com.hookah.platform.backend.api.StaffShiftDateConflictException
import com.hookah.platform.backend.api.StaffShiftImmutableException
import com.hookah.platform.backend.api.StaffShiftInvalidIntervalException
import com.hookah.platform.backend.api.StaffShiftStaleException
import com.hookah.platform.backend.api.StaffShiftTodayOverrideException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.sql.DataSource

class VenueStaffProfileRepository(
    private val dataSource: DataSource?,
    private val json: Json = Json,
) {
    suspend fun listProfiles(
        venueId: Long,
        today: LocalDate,
        linkedUserId: Long? = null,
    ): List<VenueStaffProfileWithTodayShift> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val linkedFilter = if (linkedUserId == null) "" else "AND sp.linked_user_id = ?"
                    connection.prepareStatement(
                        """
                        SELECT
                            sp.id AS profile_id,
                            sp.venue_id AS profile_venue_id,
                            sp.linked_user_id,
                            sp.display_name,
                            sp.role_label,
                            sp.subtype,
                            sp.photo_ref,
                            sp.bio,
                            sp.tags,
                            sp.is_guest_visible AS profile_is_guest_visible,
                            sp.created_by_user_id AS profile_created_by_user_id,
                            sp.updated_by_user_id AS profile_updated_by_user_id,
                            sp.published_at,
                            sp.disabled_at,
                            sp.created_at AS profile_created_at,
                            sp.updated_at AS profile_updated_at,
                            ss.id AS shift_id,
                            ss.venue_id AS shift_venue_id,
                            ss.staff_profile_id,
                            ss.shift_date,
                            ss.starts_at,
                            ss.ends_at,
                            ss.status AS shift_status,
                            ss.is_guest_visible AS shift_is_guest_visible,
                            ss.manually_marked_active,
                            ss.created_by_user_id AS shift_created_by_user_id,
                            ss.updated_by_user_id AS shift_updated_by_user_id,
                            ss.created_at AS shift_created_at,
                            ss.updated_at AS shift_updated_at
                        FROM staff_profiles sp
                        LEFT JOIN staff_shifts ss
                          ON ss.staff_profile_id = sp.id
                         AND ss.venue_id = sp.venue_id
                         AND ss.shift_date = ?
                        WHERE sp.venue_id = ?
                        $linkedFilter
                        ORDER BY sp.created_at, sp.id
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, today)
                        statement.setLong(2, venueId)
                        if (linkedUserId != null) {
                            statement.setLong(3, linkedUserId)
                        }
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<VenueStaffProfileWithTodayShift>()
                            while (rs.next()) {
                                result.add(
                                    VenueStaffProfileWithTodayShift(
                                        profile = rs.toStaffProfile(),
                                        todayShift = rs.toStaffShiftOrNull(),
                                    ),
                                )
                            }
                            result
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findProfile(
        venueId: Long,
        profileId: Long,
    ): VenueStaffProfile? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT
                            id AS profile_id,
                            venue_id AS profile_venue_id,
                            linked_user_id,
                            display_name,
                            role_label,
                            subtype,
                            photo_ref,
                            bio,
                            tags,
                            is_guest_visible AS profile_is_guest_visible,
                            created_by_user_id AS profile_created_by_user_id,
                            updated_by_user_id AS profile_updated_by_user_id,
                            published_at,
                            disabled_at,
                            created_at AS profile_created_at,
                            updated_at AS profile_updated_at
                        FROM staff_profiles
                        WHERE venue_id = ? AND id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, profileId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                rs.toStaffProfile()
                            } else {
                                null
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun createProfile(
        venueId: Long,
        actorUserId: Long,
        input: StaffProfileWrite,
    ): VenueStaffProfile {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        INSERT INTO staff_profiles (
                            venue_id,
                            linked_user_id,
                            display_name,
                            role_label,
                            subtype,
                            photo_ref,
                            bio,
                            tags,
                            is_guest_visible,
                            created_by_user_id,
                            updated_by_user_id,
                            published_at,
                            disabled_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setNullableLong(2, input.linkedUserId)
                        statement.setString(3, input.displayName)
                        statement.setNullableString(4, input.roleLabel)
                        statement.setString(5, input.subtype)
                        statement.setNullableString(6, input.photoRef)
                        statement.setNullableString(7, input.bio)
                        statement.setNullableString(8, encodeTags(input.tags))
                        statement.setBoolean(9, input.isGuestVisible)
                        statement.setLong(10, actorUserId)
                        statement.setLong(11, actorUserId)
                        if (input.isGuestVisible) {
                            statement.setTimestamp(12, Timestamp.from(Instant.now()))
                            statement.setNull(13, Types.TIMESTAMP)
                        } else {
                            statement.setNull(12, Types.TIMESTAMP)
                            statement.setNull(13, Types.TIMESTAMP)
                        }
                        statement.executeUpdate()
                        val profileId =
                            statement.generatedKeys.use { rs ->
                                if (rs.next()) {
                                    rs.getLong(1)
                                } else {
                                    throw DatabaseUnavailableException()
                                }
                            }
                        findProfileInConnection(connection, venueId, profileId) ?: throw DatabaseUnavailableException()
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateProfile(
        venueId: Long,
        profileId: Long,
        actorUserId: Long,
        input: StaffProfileWrite,
        publishedAt: Instant?,
        disabledAt: Instant?,
    ): VenueStaffProfile? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        UPDATE staff_profiles
                        SET linked_user_id = ?,
                            display_name = ?,
                            role_label = ?,
                            subtype = ?,
                            photo_ref = ?,
                            bio = ?,
                            tags = ?,
                            is_guest_visible = ?,
                            updated_by_user_id = ?,
                            published_at = ?,
                            disabled_at = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE venue_id = ? AND id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setNullableLong(1, input.linkedUserId)
                        statement.setString(2, input.displayName)
                        statement.setNullableString(3, input.roleLabel)
                        statement.setString(4, input.subtype)
                        statement.setNullableString(5, input.photoRef)
                        statement.setNullableString(6, input.bio)
                        statement.setNullableString(7, encodeTags(input.tags))
                        statement.setBoolean(8, input.isGuestVisible)
                        statement.setLong(9, actorUserId)
                        statement.setNullableInstant(10, publishedAt)
                        statement.setNullableInstant(11, disabledAt)
                        statement.setLong(12, venueId)
                        statement.setLong(13, profileId)
                        if (statement.executeUpdate() == 0) {
                            return@withContext null
                        }
                    }
                    findProfileInConnection(connection, venueId, profileId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun publishProfile(
        venueId: Long,
        profileId: Long,
        actorUserId: Long,
    ): VenueStaffProfile? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        UPDATE staff_profiles
                        SET is_guest_visible = TRUE,
                            published_at = COALESCE(published_at, CURRENT_TIMESTAMP),
                            disabled_at = NULL,
                            updated_by_user_id = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE venue_id = ? AND id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, actorUserId)
                        statement.setLong(2, venueId)
                        statement.setLong(3, profileId)
                        if (statement.executeUpdate() == 0) {
                            return@withContext null
                        }
                    }
                    findProfileInConnection(connection, venueId, profileId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun hideProfile(
        venueId: Long,
        profileId: Long,
        actorUserId: Long,
    ): VenueStaffProfile? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        UPDATE staff_profiles
                        SET is_guest_visible = FALSE,
                            disabled_at = CURRENT_TIMESTAMP,
                            updated_by_user_id = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE venue_id = ? AND id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, actorUserId)
                        statement.setLong(2, venueId)
                        statement.setLong(3, profileId)
                        if (statement.executeUpdate() == 0) {
                            return@withContext null
                        }
                    }
                    findProfileInConnection(connection, venueId, profileId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun upsertTodayShift(
        venueId: Long,
        staffProfileId: Long,
        shiftDate: LocalDate,
        actorUserId: Long,
        input: StaffShiftWrite,
    ): VenueStaffShift {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        if (!lockProfileInConnection(connection, venueId, staffProfileId)) {
                            throw DatabaseUnavailableException()
                        }
                        val existing =
                            findShiftForUpdateInConnection(
                                connection = connection,
                                venueId = venueId,
                                staffProfileId = staffProfileId,
                                shiftDate = shiftDate,
                            )
                        val shift =
                            if (existing == null) {
                                insertShiftInConnection(
                                    connection = connection,
                                    venueId = venueId,
                                    staffProfileId = staffProfileId,
                                    shiftDate = shiftDate,
                                    actorUserId = actorUserId,
                                    input = input,
                                )
                            } else {
                                updateTodayShiftInConnection(
                                    connection = connection,
                                    existing = existing,
                                    actorUserId = actorUserId,
                                    input = input,
                                )
                            }
                        connection.commit()
                        shift
                    } catch (e: Exception) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        connection.autoCommit = initialAutoCommit
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listScheduledShifts(
        venueId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<VenueStaffScheduledShift> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT
                            $scheduleShiftSelectColumns
                        FROM staff_shifts ss
                        JOIN staff_profiles sp
                          ON sp.id = ss.staff_profile_id
                         AND sp.venue_id = ss.venue_id
                        WHERE ss.venue_id = ?
                          AND ss.shift_date BETWEEN ? AND ?
                        ORDER BY ss.shift_date, ss.starts_at NULLS LAST, sp.display_name, ss.id
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setObject(2, from)
                        statement.setObject(3, to)
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(rs.toScheduledShift())
                                }
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun createScheduledShift(
        venueId: Long,
        staffProfileId: Long,
        shiftDate: LocalDate,
        startsAt: LocalTime,
        endsAt: LocalTime,
        actorUserId: Long,
        now: Instant,
        zoneId: ZoneId,
        auditLogRepository: AuditLogRepository,
    ): VenueStaffScheduledShift? =
        inTransaction { connection ->
            if (!lockProfileInConnection(connection, venueId, staffProfileId)) {
                return@inTransaction null
            }
            val updatedAt = now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            val shiftId =
                try {
                    connection.prepareStatement(
                        """
                        INSERT INTO staff_shifts (
                            venue_id,
                            staff_profile_id,
                            shift_date,
                            starts_at,
                            ends_at,
                            status,
                            is_guest_visible,
                            manually_marked_active,
                            created_by_user_id,
                            updated_by_user_id,
                            updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, FALSE, FALSE, ?, ?, ?)
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, staffProfileId)
                        statement.setObject(3, shiftDate)
                        statement.setObject(4, startsAt)
                        statement.setObject(5, endsAt)
                        statement.setString(6, STAFF_SHIFT_SCHEDULED_STATUS)
                        statement.setLong(7, actorUserId)
                        statement.setLong(8, actorUserId)
                        statement.setTimestamp(9, Timestamp.from(updatedAt))
                        statement.executeUpdate()
                        statement.generatedKeys.use { rs ->
                            if (rs.next()) rs.getLong(1) else throw DatabaseUnavailableException()
                        }
                    }
                } catch (e: SQLException) {
                    if (e.isUniqueViolation()) {
                        throw StaffShiftDateConflictException()
                    }
                    throw e
                }
            val created =
                findScheduledShiftInConnection(connection, venueId, shiftId, forUpdate = false)
                    ?: throw DatabaseUnavailableException()
            val interval =
                resolveStaffScheduleInterval(shiftDate, startsAt, endsAt, zoneId).interval
                    ?: throw StaffShiftInvalidIntervalException()
            val lifecycle = computeStaffScheduleLifecycle(created.shift.status, interval, now)
            appendStaffScheduleAudit(
                connection = connection,
                auditLogRepository = auditLogRepository,
                actorUserId = actorUserId,
                action = STAFF_SHIFT_CREATED_AUDIT_ACTION,
                old = null,
                new = created,
                oldLifecycle = null,
                newLifecycle = lifecycle,
                zoneId = zoneId,
            )
            created
        }

    suspend fun updateScheduledShift(
        venueId: Long,
        shiftId: Long,
        shiftDate: LocalDate,
        startsAt: LocalTime,
        endsAt: LocalTime,
        expectedUpdatedAt: Instant,
        actorUserId: Long,
        now: Instant,
        venueToday: LocalDate,
        zoneId: ZoneId,
        auditLogRepository: AuditLogRepository,
    ): VenueStaffScheduledShift? =
        inTransaction { connection ->
            val current =
                findScheduledShiftInConnection(connection, venueId, shiftId, forUpdate = true)
                    ?: return@inTransaction null
            if (current.shift.updatedAt != expectedUpdatedAt) {
                throw StaffShiftStaleException()
            }
            val oldResolution =
                resolveStaffScheduleInterval(
                    shiftDate = current.shift.shiftDate,
                    startsAt = current.shift.startsAt,
                    endsAt = current.shift.endsAt,
                    zoneId = zoneId,
                )
            val oldLifecycle =
                when (oldResolution.state) {
                    StaffScheduleIntervalState.INCOMPLETE -> return@inTransaction null
                    StaffScheduleIntervalState.INVALID -> {
                        if (current.shift.status.equals(STAFF_SHIFT_CANCELED_STATUS, ignoreCase = true) ||
                            !current.shift.shiftDate.isAfter(venueToday)
                        ) {
                            throw StaffShiftImmutableException()
                        }
                        if (!current.shift.hasScheduleDefaults()) {
                            throw StaffShiftTodayOverrideException()
                        }
                        null
                    }
                    StaffScheduleIntervalState.VALID -> {
                        val lifecycle =
                            computeStaffScheduleLifecycle(
                                storedStatus = current.shift.status,
                                interval = checkNotNull(oldResolution.interval),
                                now = now,
                            )
                        if (lifecycle != StaffScheduleLifecycle.SCHEDULED) {
                            throw StaffShiftImmutableException()
                        }
                        if (!current.shift.hasScheduleDefaults()) {
                            throw StaffShiftTodayOverrideException()
                        }
                        lifecycle
                    }
                }
            if (shiftDate.isAfter(venueToday.plusDays(STAFF_SCHEDULE_FUTURE_DAYS))) {
                throw InvalidInputException("Смену можно запланировать не более чем на 90 дней вперёд.")
            }
            val newResolution = resolveStaffScheduleInterval(shiftDate, startsAt, endsAt, zoneId)
            val newInterval =
                newResolution.interval
                    ?.takeIf { newResolution.state == StaffScheduleIntervalState.VALID }
                    ?: throw StaffShiftInvalidIntervalException()
            if (!newInterval.startsAt.isAfter(now)) {
                throw InvalidInputException("Начало смены должно быть в будущем.")
            }
            if (current.shift.shiftDate == shiftDate &&
                current.shift.startsAt == startsAt &&
                current.shift.endsAt == endsAt
            ) {
                return@inTransaction current
            }
            val nextUpdatedAt = nextStaffShiftUpdatedAt(now, current.shift.updatedAt)
            val updatedCount =
                try {
                    connection.prepareStatement(
                        """
                        UPDATE staff_shifts
                        SET shift_date = ?,
                            starts_at = ?,
                            ends_at = ?,
                            updated_by_user_id = ?,
                            updated_at = ?
                        WHERE venue_id = ?
                          AND id = ?
                          AND updated_at = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, shiftDate)
                        statement.setObject(2, startsAt)
                        statement.setObject(3, endsAt)
                        statement.setLong(4, actorUserId)
                        statement.setTimestamp(5, Timestamp.from(nextUpdatedAt))
                        statement.setLong(6, venueId)
                        statement.setLong(7, shiftId)
                        statement.setTimestamp(8, Timestamp.from(expectedUpdatedAt))
                        statement.executeUpdate()
                    }
                } catch (e: SQLException) {
                    if (e.isUniqueViolation()) {
                        throw StaffShiftDateConflictException()
                    }
                    throw e
                }
            if (updatedCount != 1) {
                throw StaffShiftStaleException()
            }
            val updated =
                findScheduledShiftInConnection(connection, venueId, shiftId, forUpdate = false)
                    ?: throw DatabaseUnavailableException()
            val newLifecycle = computeStaffScheduleLifecycle(updated.shift.status, newInterval, now)
            appendStaffScheduleAudit(
                connection = connection,
                auditLogRepository = auditLogRepository,
                actorUserId = actorUserId,
                action = STAFF_SHIFT_UPDATED_AUDIT_ACTION,
                old = current,
                new = updated,
                oldLifecycle = oldLifecycle,
                newLifecycle = newLifecycle,
                zoneId = zoneId,
                oldValidationState =
                    STAFF_SHIFT_INVALID_INTERVAL_VALIDATION_STATE.takeIf {
                        oldResolution.state == StaffScheduleIntervalState.INVALID
                    },
            )
            updated
        }

    suspend fun cancelScheduledShift(
        venueId: Long,
        shiftId: Long,
        expectedUpdatedAt: Instant,
        expectedConfirmationState: StaffScheduleConfirmationState,
        actorUserId: Long,
        now: Instant,
        venueToday: LocalDate,
        zoneId: ZoneId,
        auditLogRepository: AuditLogRepository,
    ): VenueStaffScheduledShift? =
        inTransaction { connection ->
            val current =
                findScheduledShiftInConnection(connection, venueId, shiftId, forUpdate = true)
                    ?: return@inTransaction null
            if (current.shift.updatedAt != expectedUpdatedAt) {
                throw StaffShiftStaleException()
            }
            if (current.shift.status.equals(STAFF_SHIFT_CANCELED_STATUS, ignoreCase = true)) {
                throw StaffShiftImmutableException()
            }
            val oldResolution =
                resolveStaffScheduleInterval(
                    shiftDate = current.shift.shiftDate,
                    startsAt = current.shift.startsAt,
                    endsAt = current.shift.endsAt,
                    zoneId = zoneId,
                )
            val oldLifecycle: StaffScheduleLifecycle?
            val actualConfirmationState: StaffScheduleConfirmationState
            when (oldResolution.state) {
                StaffScheduleIntervalState.INCOMPLETE -> return@inTransaction null
                StaffScheduleIntervalState.INVALID -> {
                    if (StaffScheduleAllowedAction.CANCEL !in
                        invalidStaffScheduleAllowedActions(current.shift, venueToday)
                    ) {
                        throw StaffShiftImmutableException()
                    }
                    oldLifecycle = null
                    actualConfirmationState = StaffScheduleConfirmationState.INVALID_INTERVAL
                }
                StaffScheduleIntervalState.VALID -> {
                    oldLifecycle =
                        computeStaffScheduleLifecycle(
                            storedStatus = current.shift.status,
                            interval = checkNotNull(oldResolution.interval),
                            now = now,
                        )
                    if (oldLifecycle == StaffScheduleLifecycle.COMPLETED ||
                        oldLifecycle == StaffScheduleLifecycle.CANCELED
                    ) {
                        throw StaffShiftImmutableException()
                    }
                    actualConfirmationState =
                        staffScheduleConfirmationState(oldLifecycle)
                            ?: throw StaffShiftImmutableException()
                }
            }
            if (expectedConfirmationState != actualConfirmationState) {
                throw StaffShiftConfirmationStaleException()
            }
            val nextUpdatedAt = nextStaffShiftUpdatedAt(now, current.shift.updatedAt)
            val updatedCount =
                connection.prepareStatement(
                    """
                    UPDATE staff_shifts
                    SET status = ?,
                        updated_by_user_id = ?,
                        updated_at = ?
                    WHERE venue_id = ?
                      AND id = ?
                      AND updated_at = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, STAFF_SHIFT_CANCELED_STATUS)
                    statement.setLong(2, actorUserId)
                    statement.setTimestamp(3, Timestamp.from(nextUpdatedAt))
                    statement.setLong(4, venueId)
                    statement.setLong(5, shiftId)
                    statement.setTimestamp(6, Timestamp.from(expectedUpdatedAt))
                    statement.executeUpdate()
                }
            if (updatedCount != 1) {
                throw StaffShiftStaleException()
            }
            val updated =
                findScheduledShiftInConnection(connection, venueId, shiftId, forUpdate = false)
                    ?: throw DatabaseUnavailableException()
            appendStaffScheduleAudit(
                connection = connection,
                auditLogRepository = auditLogRepository,
                actorUserId = actorUserId,
                action = STAFF_SHIFT_CANCELED_AUDIT_ACTION,
                old = current,
                new = updated,
                oldLifecycle = oldLifecycle,
                newLifecycle = StaffScheduleLifecycle.CANCELED,
                zoneId = zoneId,
                oldValidationState =
                    STAFF_SHIFT_INVALID_INTERVAL_VALIDATION_STATE.takeIf {
                        oldResolution.state == StaffScheduleIntervalState.INVALID
                    },
            )
            updated
        }

    suspend fun listPublicTodayStaff(
        venueId: Long,
        shiftDate: LocalDate,
    ): List<PublicVenueStaffToday> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT
                            sp.id AS profile_id,
                            sp.display_name,
                            sp.role_label,
                            sp.subtype,
                            sp.photo_ref,
                            sp.bio,
                            sp.tags,
                            ss.id AS shift_id,
                            ss.shift_date,
                            ss.starts_at,
                            ss.ends_at,
                            ss.status AS shift_status,
                            ss.manually_marked_active
                        FROM staff_shifts ss
                        JOIN staff_profiles sp
                          ON sp.id = ss.staff_profile_id
                         AND sp.venue_id = ss.venue_id
                        WHERE ss.venue_id = ?
                          AND ss.shift_date = ?
                          AND ss.is_guest_visible = TRUE
                          AND ss.status IN ('scheduled', 'active')
                          AND sp.is_guest_visible = TRUE
                          AND sp.published_at IS NOT NULL
                          AND sp.disabled_at IS NULL
                        ORDER BY
                            CASE WHEN ss.status = 'active' THEN 0 ELSE 1 END,
                            ss.starts_at NULLS LAST,
                            sp.display_name,
                            sp.id
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setObject(2, shiftDate)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<PublicVenueStaffToday>()
                            while (rs.next()) {
                                result.add(
                                    PublicVenueStaffToday(
                                        id = rs.getLong("profile_id"),
                                        displayName = rs.getString("display_name"),
                                        roleLabel = rs.getString("role_label"),
                                        subtype = rs.getString("subtype"),
                                        photoRef = rs.getString("photo_ref"),
                                        bio = rs.getString("bio"),
                                        tags = decodeTags(rs.getString("tags")),
                                        shiftId = rs.getLong("shift_id"),
                                        shiftDate = rs.getObject("shift_date", LocalDate::class.java),
                                        startsAt = rs.getNullableLocalTime("starts_at"),
                                        endsAt = rs.getNullableLocalTime("ends_at"),
                                        shiftStatus = rs.getString("shift_status"),
                                        manuallyMarkedActive = rs.getBoolean("manually_marked_active"),
                                    ),
                                )
                            }
                            result
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun findProfileInConnection(
        connection: Connection,
        venueId: Long,
        profileId: Long,
    ): VenueStaffProfile? =
        connection.prepareStatement(
            """
            SELECT
                id AS profile_id,
                venue_id AS profile_venue_id,
                linked_user_id,
                display_name,
                role_label,
                subtype,
                photo_ref,
                bio,
                tags,
                is_guest_visible AS profile_is_guest_visible,
                created_by_user_id AS profile_created_by_user_id,
                updated_by_user_id AS profile_updated_by_user_id,
                published_at,
                disabled_at,
                created_at AS profile_created_at,
                updated_at AS profile_updated_at
            FROM staff_profiles
            WHERE venue_id = ? AND id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, profileId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    rs.toStaffProfile()
                } else {
                    null
                }
            }
        }

    private fun findShiftForUpdateInConnection(
        connection: Connection,
        venueId: Long,
        staffProfileId: Long,
        shiftDate: LocalDate,
    ): VenueStaffShift? =
        connection.prepareStatement(
            """
            SELECT
                id AS shift_id,
                venue_id AS shift_venue_id,
                staff_profile_id,
                shift_date,
                starts_at,
                ends_at,
                status AS shift_status,
                is_guest_visible AS shift_is_guest_visible,
                manually_marked_active,
                created_by_user_id AS shift_created_by_user_id,
                updated_by_user_id AS shift_updated_by_user_id,
                created_at AS shift_created_at,
                updated_at AS shift_updated_at
            FROM staff_shifts
            WHERE venue_id = ? AND staff_profile_id = ? AND shift_date = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, staffProfileId)
            statement.setObject(3, shiftDate)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    rs.toStaffShift()
                } else {
                    null
                }
            }
        }

    private fun insertShiftInConnection(
        connection: Connection,
        venueId: Long,
        staffProfileId: Long,
        shiftDate: LocalDate,
        actorUserId: Long,
        input: StaffShiftWrite,
    ): VenueStaffShift {
        val shiftId =
            connection.prepareStatement(
                """
                INSERT INTO staff_shifts (
                    venue_id,
                    staff_profile_id,
                    shift_date,
                    starts_at,
                    ends_at,
                    status,
                    is_guest_visible,
                    manually_marked_active,
                    created_by_user_id,
                    updated_by_user_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, staffProfileId)
                statement.setObject(3, shiftDate)
                statement.setNullableLocalTime(4, input.startsAt)
                statement.setNullableLocalTime(5, input.endsAt)
                statement.setString(6, input.status)
                statement.setBoolean(7, input.isGuestVisible)
                statement.setBoolean(8, input.manuallyMarkedActive)
                statement.setLong(9, actorUserId)
                statement.setLong(10, actorUserId)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        rs.getLong(1)
                    } else {
                        throw DatabaseUnavailableException()
                    }
                }
            }
        return findShiftByIdInConnection(connection, shiftId) ?: throw DatabaseUnavailableException()
    }

    private fun updateTodayShiftInConnection(
        connection: Connection,
        existing: VenueStaffShift,
        actorUserId: Long,
        input: StaffShiftWrite,
    ): VenueStaffShift {
        val updatedAt = nextStaffShiftUpdatedAt(Instant.now(), existing.updatedAt)
        connection.prepareStatement(
            """
            UPDATE staff_shifts
            SET starts_at = ?,
                ends_at = ?,
                status = ?,
                is_guest_visible = ?,
                manually_marked_active = ?,
                updated_by_user_id = ?,
                updated_at = ?
            WHERE venue_id = ? AND id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setNullableLocalTime(1, input.startsAt ?: existing.startsAt)
            statement.setNullableLocalTime(2, input.endsAt ?: existing.endsAt)
            statement.setString(3, input.status)
            statement.setBoolean(4, input.isGuestVisible)
            statement.setBoolean(5, input.manuallyMarkedActive)
            statement.setLong(6, actorUserId)
            statement.setTimestamp(7, Timestamp.from(updatedAt))
            statement.setLong(8, existing.venueId)
            statement.setLong(9, existing.id)
            statement.executeUpdate()
        }
        return findShiftByIdInConnection(connection, existing.id) ?: throw DatabaseUnavailableException()
    }

    private fun lockProfileInConnection(
        connection: Connection,
        venueId: Long,
        staffProfileId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT id
            FROM staff_profiles
            WHERE venue_id = ? AND id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, staffProfileId)
            statement.executeQuery().use { it.next() }
        }

    private fun findScheduledShiftInConnection(
        connection: Connection,
        venueId: Long,
        shiftId: Long,
        forUpdate: Boolean,
    ): VenueStaffScheduledShift? {
        if (forUpdate && !lockScheduledShiftInConnection(connection, venueId, shiftId)) {
            return null
        }
        return connection.prepareStatement(
            """
            SELECT
                $scheduleShiftSelectColumns
            FROM staff_shifts ss
            JOIN staff_profiles sp
              ON sp.id = ss.staff_profile_id
             AND sp.venue_id = ss.venue_id
            WHERE ss.venue_id = ? AND ss.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, shiftId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toScheduledShift() else null
            }
        }
    }

    private fun lockScheduledShiftInConnection(
        connection: Connection,
        venueId: Long,
        shiftId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT id
            FROM staff_shifts
            WHERE venue_id = ? AND id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, shiftId)
            statement.executeQuery().use { it.next() }
        }

    private fun appendStaffScheduleAudit(
        connection: Connection,
        auditLogRepository: AuditLogRepository,
        actorUserId: Long,
        action: String,
        old: VenueStaffScheduledShift?,
        new: VenueStaffScheduledShift,
        oldLifecycle: StaffScheduleLifecycle?,
        newLifecycle: StaffScheduleLifecycle,
        zoneId: ZoneId,
        oldValidationState: String? = null,
    ) {
        auditLogRepository.appendJson(
            connection = connection,
            actorUserId = actorUserId,
            action = action,
            entityType = STAFF_SHIFT_AUDIT_ENTITY_TYPE,
            entityId = new.shift.id,
            payload =
                buildJsonObject {
                    put("actorUserId", actorUserId)
                    put("venueId", new.shift.venueId)
                    put("staffProfileId", new.shift.staffProfileId)
                    put("shiftId", new.shift.id)
                    putNullableString("oldShiftDate", old?.shift?.shiftDate?.toString())
                    put("newShiftDate", new.shift.shiftDate.toString())
                    putNullableString("oldStartsAt", old?.shift?.startsAt?.toString())
                    putNullableString("newStartsAt", new.shift.startsAt?.toString())
                    putNullableString("oldEndsAt", old?.shift?.endsAt?.toString())
                    putNullableString("newEndsAt", new.shift.endsAt?.toString())
                    putNullableString("oldLifecycle", oldLifecycle?.name)
                    put("newLifecycle", newLifecycle.name)
                    putNullableBoolean(
                        "oldCanceled",
                        old?.shift?.status?.equals(STAFF_SHIFT_CANCELED_STATUS, ignoreCase = true),
                    )
                    put("newCanceled", new.shift.status.equals(STAFF_SHIFT_CANCELED_STATUS, ignoreCase = true))
                    put("venueTimezone", zoneId.id)
                    if (oldValidationState != null) {
                        put("oldValidationState", oldValidationState)
                    }
                },
        )
    }

    private fun ResultSet.toScheduledShift(): VenueStaffScheduledShift =
        VenueStaffScheduledShift(
            profile = toStaffProfile(),
            shift = toStaffShift(),
        )

    private suspend fun <T> inTransaction(block: (Connection) -> T): T {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val result = block(connection)
                        connection.commit()
                        result
                    } catch (e: Exception) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        connection.autoCommit = initialAutoCommit
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun findShiftByIdInConnection(
        connection: Connection,
        shiftId: Long,
    ): VenueStaffShift? =
        connection.prepareStatement(
            """
            SELECT
                id AS shift_id,
                venue_id AS shift_venue_id,
                staff_profile_id,
                shift_date,
                starts_at,
                ends_at,
                status AS shift_status,
                is_guest_visible AS shift_is_guest_visible,
                manually_marked_active,
                created_by_user_id AS shift_created_by_user_id,
                updated_by_user_id AS shift_updated_by_user_id,
                created_at AS shift_created_at,
                updated_at AS shift_updated_at
            FROM staff_shifts
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, shiftId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    rs.toStaffShift()
                } else {
                    null
                }
            }
        }

    private fun ResultSet.toStaffProfile(): VenueStaffProfile =
        VenueStaffProfile(
            id = getLong("profile_id"),
            venueId = getLong("profile_venue_id"),
            linkedUserId = getNullableLong("linked_user_id"),
            displayName = getString("display_name"),
            roleLabel = getString("role_label"),
            subtype = getString("subtype"),
            photoRef = getString("photo_ref"),
            bio = getString("bio"),
            tags = decodeTags(getString("tags")),
            isGuestVisible = getBoolean("profile_is_guest_visible"),
            createdByUserId = getLong("profile_created_by_user_id"),
            updatedByUserId = getNullableLong("profile_updated_by_user_id"),
            publishedAt = getNullableInstant("published_at"),
            disabledAt = getNullableInstant("disabled_at"),
            createdAt = getNullableInstant("profile_created_at") ?: Instant.EPOCH,
            updatedAt = getNullableInstant("profile_updated_at") ?: Instant.EPOCH,
        )

    private fun ResultSet.toStaffShiftOrNull(): VenueStaffShift? {
        val id = getLong("shift_id")
        if (wasNull()) {
            return null
        }
        return toStaffShift(id)
    }

    private fun ResultSet.toStaffShift(id: Long = getLong("shift_id")): VenueStaffShift =
        VenueStaffShift(
            id = id,
            venueId = getLong("shift_venue_id"),
            staffProfileId = getLong("staff_profile_id"),
            shiftDate = getObject("shift_date", LocalDate::class.java),
            startsAt = getNullableLocalTime("starts_at"),
            endsAt = getNullableLocalTime("ends_at"),
            status = getString("shift_status"),
            isGuestVisible = getBoolean("shift_is_guest_visible"),
            manuallyMarkedActive = getBoolean("manually_marked_active"),
            createdByUserId = getLong("shift_created_by_user_id"),
            updatedByUserId = getNullableLong("shift_updated_by_user_id"),
            createdAt = getNullableInstant("shift_created_at") ?: Instant.EPOCH,
            updatedAt = getNullableInstant("shift_updated_at") ?: Instant.EPOCH,
        )

    private fun encodeTags(tags: List<String>): String? =
        tags.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it) }

    private fun decodeTags(raw: String?): List<String> =
        raw
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?: emptyList()
}

data class VenueStaffProfile(
    val id: Long,
    val venueId: Long,
    val linkedUserId: Long?,
    val displayName: String,
    val roleLabel: String?,
    val subtype: String,
    val photoRef: String?,
    val bio: String?,
    val tags: List<String>,
    val isGuestVisible: Boolean,
    val createdByUserId: Long,
    val updatedByUserId: Long?,
    val publishedAt: Instant?,
    val disabledAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class VenueStaffShift(
    val id: Long,
    val venueId: Long,
    val staffProfileId: Long,
    val shiftDate: LocalDate,
    val startsAt: LocalTime?,
    val endsAt: LocalTime?,
    val status: String,
    val isGuestVisible: Boolean,
    val manuallyMarkedActive: Boolean,
    val createdByUserId: Long,
    val updatedByUserId: Long?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class VenueStaffProfileWithTodayShift(
    val profile: VenueStaffProfile,
    val todayShift: VenueStaffShift?,
)

data class StaffProfileWrite(
    val linkedUserId: Long?,
    val displayName: String,
    val roleLabel: String?,
    val subtype: String,
    val photoRef: String?,
    val bio: String?,
    val tags: List<String>,
    val isGuestVisible: Boolean,
)

data class StaffShiftWrite(
    val startsAt: LocalTime?,
    val endsAt: LocalTime?,
    val status: String,
    val isGuestVisible: Boolean,
    val manuallyMarkedActive: Boolean,
)

data class PublicVenueStaffToday(
    val id: Long,
    val displayName: String,
    val roleLabel: String?,
    val subtype: String,
    val photoRef: String?,
    val bio: String?,
    val tags: List<String>,
    val shiftId: Long,
    val shiftDate: LocalDate,
    val startsAt: LocalTime?,
    val endsAt: LocalTime?,
    val shiftStatus: String,
    val manuallyMarkedActive: Boolean,
)

private fun java.sql.PreparedStatement.setNullableLong(
    index: Int,
    value: Long?,
) {
    if (value == null) {
        setNull(index, Types.BIGINT)
    } else {
        setLong(index, value)
    }
}

private fun java.sql.PreparedStatement.setNullableString(
    index: Int,
    value: String?,
) {
    if (value == null) {
        setNull(index, Types.VARCHAR)
    } else {
        setString(index, value)
    }
}

private fun java.sql.PreparedStatement.setNullableInstant(
    index: Int,
    value: Instant?,
) {
    if (value == null) {
        setNull(index, Types.TIMESTAMP)
    } else {
        setTimestamp(index, Timestamp.from(value))
    }
}

private fun java.sql.PreparedStatement.setNullableLocalTime(
    index: Int,
    value: LocalTime?,
) {
    if (value == null) {
        setNull(index, Types.TIME)
    } else {
        setObject(index, value)
    }
}

private fun ResultSet.getNullableLong(column: String): Long? {
    val value = getLong(column)
    return value.takeIf { !wasNull() }
}

private fun ResultSet.getNullableInstant(column: String): Instant? = getTimestamp(column)?.toInstant()

private fun ResultSet.getNullableLocalTime(column: String): LocalTime? = getObject(column, LocalTime::class.java)

private fun SQLException.isUniqueViolation(): Boolean =
    generateSequence(this) { it.nextException }
        .any { it.sqlState == "23505" || it.errorCode == 23505 }

private fun JsonObjectBuilder.putNullableString(
    key: String,
    value: String?,
) {
    if (value == null) {
        put(key, JsonNull)
    } else {
        put(key, value)
    }
}

private fun JsonObjectBuilder.putNullableBoolean(
    key: String,
    value: Boolean?,
) {
    if (value == null) {
        put(key, JsonNull)
    } else {
        put(key, value)
    }
}

private const val STAFF_SHIFT_AUDIT_ENTITY_TYPE = "staff_shift"
private const val STAFF_SHIFT_CREATED_AUDIT_ACTION = "STAFF_SHIFT_CREATED"
private const val STAFF_SHIFT_UPDATED_AUDIT_ACTION = "STAFF_SHIFT_UPDATED"
private const val STAFF_SHIFT_CANCELED_AUDIT_ACTION = "STAFF_SHIFT_CANCELED"
private const val STAFF_SHIFT_INVALID_INTERVAL_VALIDATION_STATE = "INVALID_INTERVAL"
private const val STAFF_SCHEDULE_FUTURE_DAYS = 90L

private val scheduleShiftSelectColumns =
    """
    sp.id AS profile_id,
    sp.venue_id AS profile_venue_id,
    sp.linked_user_id,
    sp.display_name,
    sp.role_label,
    sp.subtype,
    sp.photo_ref,
    sp.bio,
    sp.tags,
    sp.is_guest_visible AS profile_is_guest_visible,
    sp.created_by_user_id AS profile_created_by_user_id,
    sp.updated_by_user_id AS profile_updated_by_user_id,
    sp.published_at,
    sp.disabled_at,
    sp.created_at AS profile_created_at,
    sp.updated_at AS profile_updated_at,
    ss.id AS shift_id,
    ss.venue_id AS shift_venue_id,
    ss.staff_profile_id,
    ss.shift_date,
    ss.starts_at,
    ss.ends_at,
    ss.status AS shift_status,
    ss.is_guest_visible AS shift_is_guest_visible,
    ss.manually_marked_active,
    ss.created_by_user_id AS shift_created_by_user_id,
    ss.updated_by_user_id AS shift_updated_by_user_id,
    ss.created_at AS shift_created_at,
    ss.updated_at AS shift_updated_at
    """.trimIndent()
