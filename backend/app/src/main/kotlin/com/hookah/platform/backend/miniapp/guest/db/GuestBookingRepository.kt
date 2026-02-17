package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.Locale
import javax.sql.DataSource

enum class BookingStatus {
    PENDING,
    CONFIRMED,
    CHANGED,
    CANCELED,

    ;

    fun toApi(): String = name.lowercase(Locale.ROOT)

    companion object {
        fun fromDb(value: String): BookingStatus? = entries.firstOrNull { it.name == value }
    }
}

data class BookingRecord(
    val id: Long,
    val venueId: Long,
    val userId: Long,
    val scheduledAt: Instant,
    val partySize: Int?,
    val comment: String?,
    val status: BookingStatus,
)

class GuestBookingRepository(private val dataSource: DataSource?) {
    suspend fun create(
        venueId: Long,
        userId: Long,
        scheduledAt: Instant,
        partySize: Int?,
        comment: String?,
    ): BookingRecord {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    val bookingId =
                        connection.prepareStatement(
                            """
                            INSERT INTO bookings (venue_id, user_id, scheduled_at, party_size, comment, status)
                            VALUES (?, ?, ?, ?, ?, 'PENDING')
                            """.trimIndent(),
                            Statement.RETURN_GENERATED_KEYS,
                        ).use { statement ->
                            statement.setLong(1, venueId)
                            statement.setLong(2, userId)
                            statement.setTimestamp(3, Timestamp.from(scheduledAt))
                            if (partySize == null) {
                                statement.setNull(4, java.sql.Types.INTEGER)
                            } else {
                                statement.setInt(4, partySize)
                            }
                            statement.setString(5, comment)
                            statement.executeUpdate()
                            statement.generatedKeys.use { keys ->
                                if (keys.next()) keys.getLong(1) else throw DatabaseUnavailableException()
                            }
                        }
                    loadById(connection, bookingId)
                        ?: throw DatabaseUnavailableException()
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    suspend fun updateByGuest(
        bookingId: Long,
        venueId: Long,
        userId: Long,
        scheduledAt: Instant,
        partySize: Int?,
        comment: String?,
    ): BookingRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE bookings
                            SET scheduled_at = ?,
                                party_size = ?,
                                comment = ?,
                                status = 'PENDING',
                                updated_at = CURRENT_TIMESTAMP
                            WHERE id = ? AND venue_id = ? AND user_id = ? AND status IN ('PENDING', 'CHANGED', 'CONFIRMED')
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setTimestamp(1, Timestamp.from(scheduledAt))
                            if (partySize == null) {
                                statement.setNull(2, java.sql.Types.INTEGER)
                            } else {
                                statement.setInt(2, partySize)
                            }
                            statement.setString(3, comment)
                            statement.setLong(4, bookingId)
                            statement.setLong(5, venueId)
                            statement.setLong(6, userId)
                            statement.executeUpdate()
                        }
                    if (updated <= 0) {
                        return@use null
                    }
                    loadById(connection, bookingId)
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    suspend fun cancelByGuest(
        bookingId: Long,
        venueId: Long,
        userId: Long,
    ): BookingRecord? = updateStatus(bookingId, venueId, userId, BookingStatus.CANCELED)

    suspend fun updateByVenue(
        bookingId: Long,
        venueId: Long,
        nextStatus: BookingStatus,
        scheduledAt: Instant? = null,
    ): BookingRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    val updated =
                        if (scheduledAt == null) {
                            connection.prepareStatement(
                                """
                                UPDATE bookings
                                SET status = ?, updated_at = CURRENT_TIMESTAMP
                                WHERE id = ? AND venue_id = ? AND status IN ('PENDING', 'CONFIRMED', 'CHANGED')
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, nextStatus.name)
                                statement.setLong(2, bookingId)
                                statement.setLong(3, venueId)
                                statement.executeUpdate()
                            }
                        } else {
                            connection.prepareStatement(
                                """
                                UPDATE bookings
                                SET status = ?, scheduled_at = ?, updated_at = CURRENT_TIMESTAMP
                                WHERE id = ? AND venue_id = ? AND status IN ('PENDING', 'CONFIRMED', 'CHANGED')
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, nextStatus.name)
                                statement.setTimestamp(2, Timestamp.from(scheduledAt))
                                statement.setLong(3, bookingId)
                                statement.setLong(4, venueId)
                                statement.executeUpdate()
                            }
                        }
                    if (updated <= 0) {
                        return@use null
                    }
                    loadById(connection, bookingId)
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    suspend fun listByUser(
        venueId: Long,
        userId: Long,
        limit: Int = 20,
    ): List<BookingRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT id, venue_id, user_id, scheduled_at, party_size, comment, status
                        FROM bookings
                        WHERE venue_id = ? AND user_id = ?
                        ORDER BY created_at DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, userId)
                        statement.setInt(3, limit)
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(mapBooking(rs))
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    private suspend fun updateStatus(
        bookingId: Long,
        venueId: Long,
        userId: Long,
        nextStatus: BookingStatus,
    ): BookingRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE bookings
                            SET status = ?, updated_at = CURRENT_TIMESTAMP
                            WHERE id = ? AND venue_id = ? AND user_id = ? AND status IN ('PENDING', 'CONFIRMED', 'CHANGED')
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, nextStatus.name)
                            statement.setLong(2, bookingId)
                            statement.setLong(3, venueId)
                            statement.setLong(4, userId)
                            statement.executeUpdate()
                        }
                    if (updated <= 0) return@use null
                    loadById(connection, bookingId)
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    private fun loadById(
        connection: java.sql.Connection,
        bookingId: Long,
    ): BookingRecord? {
        return connection.prepareStatement(
            """
            SELECT id, venue_id, user_id, scheduled_at, party_size, comment, status
            FROM bookings
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, bookingId)
            statement.executeQuery().use { rs -> if (rs.next()) mapBooking(rs) else null }
        }
    }

    private fun mapBooking(rs: java.sql.ResultSet): BookingRecord {
        return BookingRecord(
            id = rs.getLong("id"),
            venueId = rs.getLong("venue_id"),
            userId = rs.getLong("user_id"),
            scheduledAt = rs.getTimestamp("scheduled_at").toInstant(),
            partySize = rs.getInt("party_size").let { if (rs.wasNull()) null else it },
            comment = rs.getString("comment"),
            status = BookingStatus.fromDb(rs.getString("status")) ?: BookingStatus.PENDING,
        )
    }
}
