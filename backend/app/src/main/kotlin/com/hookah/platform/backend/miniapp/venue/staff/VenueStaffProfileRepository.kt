package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Time
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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
                        val existingId = findShiftIdInConnection(connection, staffProfileId, shiftDate)
                        val shift =
                            if (existingId == null) {
                                insertShiftInConnection(
                                    connection = connection,
                                    venueId = venueId,
                                    staffProfileId = staffProfileId,
                                    shiftDate = shiftDate,
                                    actorUserId = actorUserId,
                                    input = input,
                                )
                            } else {
                                updateShiftInConnection(connection, existingId, actorUserId, input)
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

    private fun findShiftIdInConnection(
        connection: Connection,
        staffProfileId: Long,
        shiftDate: LocalDate,
    ): Long? =
        connection.prepareStatement(
            """
            SELECT id
            FROM staff_shifts
            WHERE staff_profile_id = ? AND shift_date = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, staffProfileId)
            statement.setObject(2, shiftDate)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    rs.getLong("id")
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

    private fun updateShiftInConnection(
        connection: Connection,
        shiftId: Long,
        actorUserId: Long,
        input: StaffShiftWrite,
    ): VenueStaffShift {
        connection.prepareStatement(
            """
            UPDATE staff_shifts
            SET starts_at = ?,
                ends_at = ?,
                status = ?,
                is_guest_visible = ?,
                manually_marked_active = ?,
                updated_by_user_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setNullableLocalTime(1, input.startsAt)
            statement.setNullableLocalTime(2, input.endsAt)
            statement.setString(3, input.status)
            statement.setBoolean(4, input.isGuestVisible)
            statement.setBoolean(5, input.manuallyMarkedActive)
            statement.setLong(6, actorUserId)
            statement.setLong(7, shiftId)
            statement.executeUpdate()
        }
        return findShiftByIdInConnection(connection, shiftId) ?: throw DatabaseUnavailableException()
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
        setTime(index, Time.valueOf(value))
    }
}

private fun ResultSet.getNullableLong(column: String): Long? {
    val value = getLong(column)
    return value.takeIf { !wasNull() }
}

private fun ResultSet.getNullableInstant(column: String): Instant? = getTimestamp(column)?.toInstant()

private fun ResultSet.getNullableLocalTime(column: String): LocalTime? = getTime(column)?.toLocalTime()
