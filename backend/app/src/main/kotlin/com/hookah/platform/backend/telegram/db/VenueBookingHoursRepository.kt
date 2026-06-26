package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException
import java.sql.Time
import java.time.LocalDate
import java.time.LocalTime
import javax.sql.DataSource
import java.sql.Date as SqlDate

data class VenueBookingHours(
    val venueId: Long,
    val weekday: Int,
    val opensAt: LocalTime,
    val closesAt: LocalTime,
    val isClosed: Boolean = false,
    val guestNote: String? = null,
)

data class VenueBookingDateOverride(
    val venueId: Long,
    val serviceDate: LocalDate,
    val opensAt: LocalTime,
    val closesAt: LocalTime,
    val isClosed: Boolean = false,
    val guestNote: String? = null,
)

class VenueBookingHoursRepository(private val dataSource: DataSource?) {
    private val botTestVenueNames =
        listOf(
            "Тестовая кальянная",
            "Дым и Лёд",
            "Hookah Lounge 24",
        )

    private val defaultTestOpensAt = LocalTime.of(18, 0)
    private val defaultTestClosesAt = LocalTime.MIDNIGHT
    private val closedDayTimeMarker = LocalTime.MIDNIGHT

    suspend fun findByVenueAndDate(
        venueId: Long,
        date: LocalDate,
    ): VenueBookingHours? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val weekday = date.dayOfWeek.value
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val overrideHours =
                        connection.prepareStatement(
                            """
                            SELECT opens_at, closes_at, is_closed, guest_note
                            FROM venue_booking_hours_overrides
                            WHERE venue_id = ? AND service_date = ?
                            LIMIT 1
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, venueId)
                            statement.setDate(2, SqlDate.valueOf(date))
                            statement.executeQuery().use { rs ->
                                if (!rs.next()) {
                                    null
                                } else {
                                    val opensAt = rs.getTime("opens_at")?.toLocalTime()
                                    val closesAt = rs.getTime("closes_at")?.toLocalTime()
                                    if (opensAt == null || closesAt == null) {
                                        null
                                    } else {
                                        VenueBookingHours(
                                            venueId = venueId,
                                            weekday = weekday,
                                            opensAt = opensAt,
                                            closesAt = closesAt,
                                            isClosed = rs.getBoolean("is_closed"),
                                            guestNote = rs.getString("guest_note"),
                                        )
                                    }
                                }
                            }
                        }
                    if (overrideHours != null) {
                        return@withContext overrideHours
                    }
                    connection.prepareStatement(
                        """
                        SELECT venue_id, weekday, opens_at, closes_at, is_closed
                        FROM venue_booking_hours
                        WHERE venue_id = ? AND weekday = ?
                        LIMIT 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setInt(2, weekday)
                        statement.executeQuery().use { rs ->
                            if (!rs.next()) {
                                null
                            } else {
                                val opensAt = rs.getTime("opens_at")?.toLocalTime()
                                val closesAt = rs.getTime("closes_at")?.toLocalTime()
                                if (opensAt == null || closesAt == null) {
                                    null
                                } else {
                                    VenueBookingHours(
                                        venueId = rs.getLong("venue_id"),
                                        weekday = rs.getInt("weekday"),
                                        opensAt = opensAt,
                                        closesAt = closesAt,
                                        isClosed = rs.getBoolean("is_closed"),
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun hasConfiguredHours(venueId: Long): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT COUNT(DISTINCT weekday) AS cnt
                        FROM venue_booking_hours
                        WHERE venue_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) rs.getInt("cnt") == 7 else false
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listWeeklyHours(venueId: Long): List<VenueBookingHours> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT venue_id, weekday, opens_at, closes_at, is_closed
                        FROM venue_booking_hours
                        WHERE venue_id = ?
                        ORDER BY weekday
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<VenueBookingHours>()
                            while (rs.next()) {
                                val opensAt = rs.getTime("opens_at")?.toLocalTime()
                                val closesAt = rs.getTime("closes_at")?.toLocalTime()
                                if (opensAt != null && closesAt != null) {
                                    result +=
                                        VenueBookingHours(
                                            venueId = rs.getLong("venue_id"),
                                            weekday = rs.getInt("weekday"),
                                            opensAt = opensAt,
                                            closesAt = closesAt,
                                            isClosed = rs.getBoolean("is_closed"),
                                        )
                                }
                            }
                            result
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun upsertWeekdayHours(
        venueId: Long,
        weekday: Int,
        opensAt: LocalTime,
        closesAt: LocalTime,
        isClosed: Boolean = false,
    ): Boolean {
        if (weekday !in 1..7) return false
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        if (!venueExists(connection, venueId)) {
                            connection.rollback()
                            return@withContext false
                        }
                        val updatedRows =
                            connection.prepareStatement(
                                """
                                UPDATE venue_booking_hours
                                SET opens_at = ?, closes_at = ?, is_closed = ?, updated_at = CURRENT_TIMESTAMP
                                WHERE venue_id = ? AND weekday = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setTime(1, Time.valueOf(opensAt))
                                statement.setTime(2, Time.valueOf(closesAt))
                                statement.setBoolean(3, isClosed)
                                statement.setLong(4, venueId)
                                statement.setInt(5, weekday)
                                statement.executeUpdate()
                            }
                        if (updatedRows == 0) {
                            connection.prepareStatement(
                                """
                                INSERT INTO venue_booking_hours (venue_id, weekday, opens_at, closes_at, is_closed)
                                VALUES (?, ?, ?, ?, ?)
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, venueId)
                                statement.setInt(2, weekday)
                                statement.setTime(3, Time.valueOf(opensAt))
                                statement.setTime(4, Time.valueOf(closesAt))
                                statement.setBoolean(5, isClosed)
                                statement.executeUpdate()
                            }
                        }
                        connection.commit()
                        true
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun closeWeekday(
        venueId: Long,
        weekday: Int,
    ): Boolean =
        upsertWeekdayHours(
            venueId = venueId,
            weekday = weekday,
            opensAt = closedDayTimeMarker,
            closesAt = closedDayTimeMarker,
            isClosed = true,
        )

    suspend fun listDateOverrides(
        venueId: Long,
        limit: Int = 30,
    ): List<VenueBookingDateOverride> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT venue_id, service_date, opens_at, closes_at, is_closed, guest_note
                        FROM venue_booking_hours_overrides
                        WHERE venue_id = ?
                        ORDER BY service_date
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setInt(2, limit.coerceIn(1, 200))
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<VenueBookingDateOverride>()
                            while (rs.next()) {
                                val serviceDate = rs.getDate("service_date")?.toLocalDate()
                                val opensAt = rs.getTime("opens_at")?.toLocalTime()
                                val closesAt = rs.getTime("closes_at")?.toLocalTime()
                                if (serviceDate != null && opensAt != null && closesAt != null) {
                                    result +=
                                        VenueBookingDateOverride(
                                            venueId = rs.getLong("venue_id"),
                                            serviceDate = serviceDate,
                                            opensAt = opensAt,
                                            closesAt = closesAt,
                                            isClosed = rs.getBoolean("is_closed"),
                                            guestNote = rs.getString("guest_note"),
                                        )
                                }
                            }
                            result
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findDateOverride(
        venueId: Long,
        serviceDate: LocalDate,
    ): VenueBookingDateOverride? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT venue_id, service_date, opens_at, closes_at, is_closed, guest_note
                        FROM venue_booking_hours_overrides
                        WHERE venue_id = ? AND service_date = ?
                        LIMIT 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setDate(2, SqlDate.valueOf(serviceDate))
                        statement.executeQuery().use { rs ->
                            if (!rs.next()) {
                                null
                            } else {
                                val date = rs.getDate("service_date")?.toLocalDate()
                                val opensAt = rs.getTime("opens_at")?.toLocalTime()
                                val closesAt = rs.getTime("closes_at")?.toLocalTime()
                                if (date == null || opensAt == null || closesAt == null) {
                                    null
                                } else {
                                    VenueBookingDateOverride(
                                        venueId = rs.getLong("venue_id"),
                                        serviceDate = date,
                                        opensAt = opensAt,
                                        closesAt = closesAt,
                                        isClosed = rs.getBoolean("is_closed"),
                                        guestNote = rs.getString("guest_note"),
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun upsertDateOverride(
        venueId: Long,
        serviceDate: LocalDate,
        opensAt: LocalTime,
        closesAt: LocalTime,
        isClosed: Boolean = false,
        guestNote: String? = null,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        if (!venueExists(connection, venueId)) {
                            connection.rollback()
                            return@withContext false
                        }
                        upsertDateOverride(connection, venueId, serviceDate, opensAt, closesAt, isClosed, guestNote)
                        connection.commit()
                        true
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun upsertDateOverrideRange(
        venueId: Long,
        fromDate: LocalDate,
        toDate: LocalDate,
        opensAt: LocalTime,
        closesAt: LocalTime,
        isClosed: Boolean = false,
        guestNote: String? = null,
    ): Boolean {
        if (toDate.isBefore(fromDate)) return false
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        if (!venueExists(connection, venueId)) {
                            connection.rollback()
                            return@withContext false
                        }
                        var date = fromDate
                        while (!date.isAfter(toDate)) {
                            upsertDateOverride(connection, venueId, date, opensAt, closesAt, isClosed, guestNote)
                            date = date.plusDays(1)
                        }
                        connection.commit()
                        true
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun deleteDateOverride(
        venueId: Long,
        serviceDate: LocalDate,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        DELETE FROM venue_booking_hours_overrides
                        WHERE venue_id = ? AND service_date = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setDate(2, SqlDate.valueOf(serviceDate))
                        statement.executeUpdate() > 0
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun deleteDateOverrideRange(
        venueId: Long,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): Boolean {
        if (toDate.isBefore(fromDate)) return false
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        DELETE FROM venue_booking_hours_overrides
                        WHERE venue_id = ? AND service_date BETWEEN ? AND ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setDate(2, SqlDate.valueOf(fromDate))
                        statement.setDate(3, SqlDate.valueOf(toDate))
                        statement.executeUpdate() > 0
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun replaceDailyHoursForAllWeek(
        venueId: Long,
        opensAt: LocalTime,
        closesAt: LocalTime,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        if (!venueExists(connection, venueId)) {
                            connection.rollback()
                            return@withContext false
                        }
                        connection.prepareStatement(
                            """
                            DELETE FROM venue_booking_hours
                            WHERE venue_id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, venueId)
                            statement.executeUpdate()
                        }
                        connection.prepareStatement(
                            """
                            INSERT INTO venue_booking_hours (venue_id, weekday, opens_at, closes_at, is_closed)
                            VALUES (?, ?, ?, ?, FALSE)
                            """.trimIndent(),
                        ).use { statement ->
                            (1..7).forEach { weekday ->
                                statement.setLong(1, venueId)
                                statement.setInt(2, weekday)
                                statement.setTime(3, Time.valueOf(opensAt))
                                statement.setTime(4, Time.valueOf(closesAt))
                                statement.addBatch()
                            }
                            statement.executeBatch()
                        }
                        connection.commit()
                        true
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun ensureBotTestVenueBookingHours() {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        botTestVenueNames.forEach { venueName ->
                            val venueIds = findVenueIdsByName(connection, venueName)
                            venueIds.forEach { venueId ->
                                (1..7).forEach { weekday ->
                                    connection.prepareStatement(
                                        """
                                        INSERT INTO venue_booking_hours (venue_id, weekday, opens_at, closes_at, is_closed)
                                        SELECT ?, ?, ?, ?, FALSE
                                        WHERE NOT EXISTS (
                                            SELECT 1
                                            FROM venue_booking_hours
                                            WHERE venue_id = ? AND weekday = ?
                                        )
                                        """.trimIndent(),
                                    ).use { statement ->
                                        statement.setLong(1, venueId)
                                        statement.setInt(2, weekday)
                                        statement.setTime(3, Time.valueOf(defaultTestOpensAt))
                                        statement.setTime(4, Time.valueOf(defaultTestClosesAt))
                                        statement.setLong(5, venueId)
                                        statement.setInt(6, weekday)
                                        statement.executeUpdate()
                                    }
                                }
                            }
                        }
                        connection.commit()
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        connection.autoCommit = true
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun venueExists(
        connection: java.sql.Connection,
        venueId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT 1
            FROM venues
            WHERE id = ?
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun upsertDateOverride(
        connection: java.sql.Connection,
        venueId: Long,
        serviceDate: LocalDate,
        opensAt: LocalTime,
        closesAt: LocalTime,
        isClosed: Boolean,
        guestNote: String?,
    ) {
        val updatedRows =
            connection.prepareStatement(
                """
                UPDATE venue_booking_hours_overrides
                SET opens_at = ?, closes_at = ?, is_closed = ?, guest_note = ?, updated_at = CURRENT_TIMESTAMP
                WHERE venue_id = ? AND service_date = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setTime(1, Time.valueOf(opensAt))
                statement.setTime(2, Time.valueOf(closesAt))
                statement.setBoolean(3, isClosed)
                statement.setString(4, guestNote)
                statement.setLong(5, venueId)
                statement.setDate(6, SqlDate.valueOf(serviceDate))
                statement.executeUpdate()
            }
        if (updatedRows == 0) {
            connection.prepareStatement(
                """
                INSERT INTO venue_booking_hours_overrides
                    (venue_id, service_date, opens_at, closes_at, is_closed, guest_note)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setDate(2, SqlDate.valueOf(serviceDate))
                statement.setTime(3, Time.valueOf(opensAt))
                statement.setTime(4, Time.valueOf(closesAt))
                statement.setBoolean(5, isClosed)
                statement.setString(6, guestNote)
                statement.executeUpdate()
            }
        }
    }

    private fun findVenueIdsByName(
        connection: java.sql.Connection,
        venueName: String,
    ): List<Long> =
        connection.prepareStatement(
            """
            SELECT id
            FROM venues
            WHERE LOWER(name) = LOWER(?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, venueName)
            statement.executeQuery().use { rs ->
                val result = mutableListOf<Long>()
                while (rs.next()) {
                    result += rs.getLong("id")
                }
                result
            }
        }
}
