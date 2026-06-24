package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import javax.sql.DataSource
import java.sql.Date as SqlDate

enum class BookingStatus {
    PENDING,
    CONFIRMED,
    CHANGED,
    CANCELED,
    EXPIRED,
    NO_SHOW,
    SEATED,

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
    val displayNumber: Int? = null,
    val displayDate: LocalDate? = null,
    val cancelReasonText: String? = null,
    val canceledByRole: String? = null,
    val canceledByUserId: Long? = null,
    val arrivalDeadlineAt: Instant? = null,
    val seatedAt: Instant? = null,
    val noShowAt: Instant? = null,
    val expiredAt: Instant? = null,
    val lastGuestConfirmationAt: Instant? = null,
    val venueConfirmedAt: Instant? = null,
    val lastRescheduledAt: Instant? = null,
    val guestDisplayName: String? = null,
)

data class UserBookingSummaryRecord(
    val id: Long,
    val venueId: Long,
    val venueName: String,
    val scheduledAt: Instant,
    val partySize: Int?,
    val comment: String? = null,
    val status: BookingStatus,
    val displayNumber: Int? = null,
    val displayDate: LocalDate? = null,
    val arrivalDeadlineAt: Instant? = null,
    val lastGuestConfirmationAt: Instant? = null,
    val guestDisplayName: String? = null,
)

data class ExpireBookingsResult(
    val expiredCount: Int,
)

enum class BookingReminderKind {
    DAY_OF_VISIT,
    PRE_VISIT,
}

enum class BookingReminderStatus {
    PENDING,
    QUEUED,
    SENT,
    CANCELED,
    SKIPPED,
    FAILED,
}

data class BookingReminderScheduleResult(
    val pendingCount: Int,
    val skippedCount: Int,
    val canceledCount: Int,
)

data class BookingReminderDelivery(
    val reminderId: Long,
    val bookingId: Long,
    val kind: BookingReminderKind,
    val scheduledFor: Instant,
    val attempts: Int,
    val venueId: Long,
    val venueName: String,
    val userId: Long,
    val scheduledAt: Instant,
    val partySize: Int?,
    val displayNumber: Int?,
    val displayDate: LocalDate?,
    val arrivalDeadlineAt: Instant?,
)

class GuestBookingRepository(
    private val dataSource: DataSource?,
    private val visitRepository: VisitRepository? = null,
) {
    suspend fun getHoldMinutes(venueId: Long): Int {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    getOrCreateHoldMinutes(connection, venueId)
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    suspend fun findVenueName(venueId: Long): String? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT name
                        FROM venues
                        WHERE id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) rs.getString("name") else null
                        }
                    }
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    suspend fun updateHoldMinutes(
        venueId: Long,
        minutes: Int,
    ): Int {
        require(isValidHoldMinutes(minutes)) { "hold minutes must be between $MIN_HOLD_MINUTES and $MAX_HOLD_MINUTES" }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        UPDATE venue_booking_settings
                        SET hold_minutes = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE venue_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setInt(1, minutes)
                        statement.setLong(2, venueId)
                        val updated = statement.executeUpdate()
                        if (updated == 0) {
                            connection.prepareStatement(
                                """
                                INSERT INTO venue_booking_settings (venue_id, hold_minutes)
                                VALUES (?, ?)
                                """.trimIndent(),
                            ).use { insert ->
                                insert.setLong(1, venueId)
                                insert.setInt(2, minutes)
                                insert.executeUpdate()
                            }
                        }
                    }
                    minutes
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    suspend fun create(
        venueId: Long,
        userId: Long,
        scheduledAt: Instant,
        partySize: Int?,
        comment: String?,
        venueZoneId: ZoneId = ZoneId.systemDefault(),
        serviceDate: LocalDate? = null,
    ): BookingRecord {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val displayDate = serviceDate ?: bookingDisplayDate(scheduledAt, venueZoneId)
                        val displayNumber = nextDisplayNumber(connection, venueId, displayDate)
                        val holdMinutes = getOrCreateHoldMinutes(connection, venueId)
                        val arrivalDeadlineAt = bookingDeadline(scheduledAt, holdMinutes)
                        val bookingId =
                            connection.prepareStatement(
                                """
                                INSERT INTO bookings (
                                    venue_id,
                                    user_id,
                                    scheduled_at,
                                    party_size,
                                    comment,
                                    status,
                                    display_date,
                                    display_number,
                                    arrival_deadline_at
                                )
                                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
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
                                statement.setDate(6, SqlDate.valueOf(displayDate))
                                statement.setInt(7, displayNumber)
                                statement.setTimestamp(8, Timestamp.from(arrivalDeadlineAt))
                                statement.executeUpdate()
                                statement.generatedKeys.use { keys ->
                                    if (keys.next()) keys.getLong(1) else throw DatabaseUnavailableException()
                                }
                            }
                        val created = loadById(connection, bookingId) ?: throw DatabaseUnavailableException()
                        connection.commit()
                        created
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
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
        venueZoneId: ZoneId = ZoneId.systemDefault(),
        serviceDate: LocalDate? = null,
    ): BookingRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    val current = loadById(connection, bookingId) ?: return@use null
                    val displayDate = serviceDate ?: bookingDisplayDate(scheduledAt, venueZoneId)
                    val displayNumber =
                        if (current.displayDate == displayDate && current.displayNumber != null) {
                            current.displayNumber
                        } else {
                            nextDisplayNumber(connection, venueId, displayDate)
                        }
                    val holdMinutes = getOrCreateHoldMinutes(connection, venueId)
                    val arrivalDeadlineAt = bookingDeadline(scheduledAt, holdMinutes)
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE bookings
                            SET scheduled_at = ?,
                                party_size = ?,
                                comment = ?,
                                status = 'PENDING',
                                display_date = ?,
                                display_number = ?,
                                arrival_deadline_at = ?,
                                last_guest_confirmation_at = NULL,
                                venue_confirmed_at = NULL,
                                last_rescheduled_at = NULL,
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
                            statement.setDate(4, SqlDate.valueOf(displayDate))
                            statement.setInt(5, displayNumber)
                            statement.setTimestamp(6, Timestamp.from(arrivalDeadlineAt))
                            statement.setLong(7, bookingId)
                            statement.setLong(8, venueId)
                            statement.setLong(9, userId)
                            statement.executeUpdate()
                        }
                    if (updated <= 0) {
                        return@use null
                    }
                    cancelPendingReminders(connection, bookingId)
                    loadById(connection, bookingId)
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    suspend fun findByVenue(
        bookingId: Long,
        venueId: Long,
    ): BookingRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT b.id, b.venue_id, b.user_id, b.scheduled_at, b.party_size, b.comment, b.status,
                               b.display_number, b.display_date, b.cancel_reason_text, b.canceled_by_role, b.canceled_by_user_id,
                               b.arrival_deadline_at, b.seated_at, b.no_show_at, b.expired_at,
                               b.last_guest_confirmation_at, b.venue_confirmed_at, b.last_rescheduled_at,
                               u.guest_display_name
                        FROM bookings b
                        LEFT JOIN users u ON u.telegram_user_id = b.user_id
                        WHERE b.id = ? AND b.venue_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, bookingId)
                        statement.setLong(2, venueId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) mapBooking(rs) else null
                        }
                    }
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    suspend fun findActiveByGuest(
        bookingId: Long,
        venueId: Long,
        userId: Long,
    ): BookingRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT b.id, b.venue_id, b.user_id, b.scheduled_at, b.party_size, b.comment, b.status,
                               b.display_number, b.display_date, b.cancel_reason_text, b.canceled_by_role, b.canceled_by_user_id,
                               b.arrival_deadline_at, b.seated_at, b.no_show_at, b.expired_at,
                               b.last_guest_confirmation_at, b.venue_confirmed_at, b.last_rescheduled_at,
                               u.guest_display_name
                        FROM bookings b
                        LEFT JOIN users u ON u.telegram_user_id = b.user_id
                        WHERE b.id = ? AND b.venue_id = ? AND b.user_id = ?
                          AND b.status IN ('PENDING', 'CONFIRMED', 'CHANGED')
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, bookingId)
                        statement.setLong(2, venueId)
                        statement.setLong(3, userId)
                        statement.executeQuery().use { rs ->
                            if (!rs.next()) {
                                null
                            } else {
                                val booking = mapBooking(rs)
                                val holdMinutes = getOrCreateHoldMinutes(connection, venueId)
                                booking.takeIf {
                                    isActiveAt(
                                        it.scheduledAt,
                                        it.arrivalDeadlineAt,
                                        holdMinutes,
                                        Instant.now(),
                                    )
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
        cancelReasonText: String? = null,
        canceledByRole: String? = null,
        canceledByUserId: Long? = null,
        venueZoneId: ZoneId = ZoneId.systemDefault(),
        serviceDate: LocalDate? = null,
    ): BookingRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    val current = loadById(connection, bookingId) ?: return@use null
                    val now = Instant.now()
                    val updated =
                        if (scheduledAt == null) {
                            val deadlineFallback =
                                if (nextStatus in ACTIVE_STATUSES) {
                                    val holdMinutes = getOrCreateHoldMinutes(connection, venueId)
                                    current.arrivalDeadlineAt
                                        ?: bookingDeadline(current.scheduledAt, holdMinutes)
                                } else {
                                    current.arrivalDeadlineAt
                                }
                            connection.prepareStatement(
                                """
                                UPDATE bookings
                                SET status = ?,
                                    cancel_reason_text = ?,
                                    canceled_by_role = ?,
                                    canceled_by_user_id = ?,
                                    arrival_deadline_at = ?,
                                    venue_confirmed_at = CASE
                                        WHEN ? IN ('CONFIRMED', 'CHANGED') AND venue_confirmed_at IS NULL THEN ?
                                        ELSE venue_confirmed_at
                                    END,
                                    updated_at = CURRENT_TIMESTAMP
                                WHERE id = ? AND venue_id = ? AND status IN ('PENDING', 'CONFIRMED', 'CHANGED')
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, nextStatus.name)
                                statement.setString(
                                    2,
                                    if (nextStatus == BookingStatus.CANCELED) cancelReasonText else null,
                                )
                                statement.setString(
                                    3,
                                    if (nextStatus == BookingStatus.CANCELED) canceledByRole else null,
                                )
                                if (nextStatus == BookingStatus.CANCELED && canceledByUserId != null) {
                                    statement.setLong(4, canceledByUserId)
                                } else {
                                    statement.setNull(4, java.sql.Types.BIGINT)
                                }
                                setNullableTimestamp(statement, 5, deadlineFallback)
                                statement.setString(6, nextStatus.name)
                                statement.setTimestamp(7, Timestamp.from(now))
                                statement.setLong(8, bookingId)
                                statement.setLong(9, venueId)
                                statement.executeUpdate()
                            }
                        } else {
                            val displayDate = serviceDate ?: bookingDisplayDate(scheduledAt, venueZoneId)
                            val displayNumber =
                                if (current.displayDate == displayDate && current.displayNumber != null) {
                                    current.displayNumber
                                } else {
                                    nextDisplayNumber(connection, venueId, displayDate)
                                }
                            val holdMinutes = getOrCreateHoldMinutes(connection, venueId)
                            val arrivalDeadlineAt = bookingDeadline(scheduledAt, holdMinutes)
                            connection.prepareStatement(
                                """
                                UPDATE bookings
                                SET status = ?,
                                    scheduled_at = ?,
                                    display_date = ?,
                                    display_number = ?,
                                    arrival_deadline_at = ?,
                                    seated_at = NULL,
                                    no_show_at = NULL,
                                    expired_at = NULL,
                                    last_guest_confirmation_at = NULL,
                                    venue_confirmed_at = COALESCE(venue_confirmed_at, ?),
                                    last_rescheduled_at = ?,
                                    cancel_reason_text = NULL,
                                    canceled_by_role = NULL,
                                    canceled_by_user_id = NULL,
                                    updated_at = CURRENT_TIMESTAMP
                                WHERE id = ? AND venue_id = ? AND status IN ('PENDING', 'CONFIRMED', 'CHANGED')
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, nextStatus.name)
                                statement.setTimestamp(2, Timestamp.from(scheduledAt))
                                statement.setDate(3, SqlDate.valueOf(displayDate))
                                statement.setInt(4, displayNumber)
                                statement.setTimestamp(5, Timestamp.from(arrivalDeadlineAt))
                                statement.setTimestamp(6, Timestamp.from(now))
                                statement.setTimestamp(7, Timestamp.from(now))
                                statement.setLong(8, bookingId)
                                statement.setLong(9, venueId)
                                statement.executeUpdate()
                            }
                        }
                    if (updated <= 0) {
                        return@use null
                    }
                    if (nextStatus !in ACTIVE_STATUSES) {
                        cancelPendingReminders(connection, bookingId)
                    }
                    loadById(connection, bookingId)
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    suspend fun markSeated(
        venueId: Long,
        bookingId: Long,
        actorUserId: Long? = null,
    ): BookingRecord? =
        updateTerminalLifecycleStatus(
            venueId = venueId,
            bookingId = bookingId,
            nextStatus = BookingStatus.SEATED,
            timestampColumn = "seated_at",
        )

    suspend fun markNoShow(
        venueId: Long,
        bookingId: Long,
        actorUserId: Long? = null,
    ): BookingRecord? =
        updateTerminalLifecycleStatus(
            venueId = venueId,
            bookingId = bookingId,
            nextStatus = BookingStatus.NO_SHOW,
            timestampColumn = "no_show_at",
        )

    suspend fun expireOverdue(now: Instant = Instant.now()): Int = expireOverdueBookings(now = now).expiredCount

    suspend fun expireOverdueBookings(
        now: Instant = Instant.now(),
        limit: Int = DEFAULT_EXPIRY_BATCH_SIZE,
    ): ExpireBookingsResult {
        require(limit > 0) { "limit must be > 0" }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val dueIds = selectOverdueBookingIdsForExpiry(connection, now, limit)
                        val updated =
                            if (dueIds.isEmpty()) {
                                0
                            } else {
                                expireBookingIds(connection, dueIds, now)
                            }
                        connection.commit()
                        ExpireBookingsResult(expiredCount = updated)
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    private fun selectOverdueBookingIdsForExpiry(
        connection: java.sql.Connection,
        now: Instant,
        limit: Int,
    ): List<Long> {
        val isH2 = connection.metaData.databaseProductName.contains("H2", ignoreCase = true)
        val effectiveDeadlineExpression =
            if (isH2) {
                "COALESCE(b.arrival_deadline_at, DATEADD('MINUTE', COALESCE(vbs.hold_minutes, 30), b.scheduled_at))"
            } else {
                "COALESCE(b.arrival_deadline_at, " +
                    "b.scheduled_at + (COALESCE(vbs.hold_minutes, 30) * INTERVAL '1 minute'))"
            }
        val forUpdateClause = if (isH2) "" else "FOR UPDATE OF b SKIP LOCKED"
        return connection.prepareStatement(
            """
            SELECT b.id
            FROM bookings b
            LEFT JOIN venue_booking_settings vbs ON vbs.venue_id = b.venue_id
            WHERE b.status IN ('PENDING', 'CONFIRMED', 'CHANGED')
              AND $effectiveDeadlineExpression < ?
            ORDER BY $effectiveDeadlineExpression ASC, b.id ASC
            LIMIT ?
            $forUpdateClause
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setInt(2, limit)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(rs.getLong("id"))
                    }
                }
            }
        }
    }

    private fun expireBookingIds(
        connection: java.sql.Connection,
        bookingIds: List<Long>,
        now: Instant,
    ): Int {
        val placeholders = bookingIds.joinToString(",") { "?" }
        val updated =
            connection.prepareStatement(
                """
                UPDATE bookings
                SET status = 'EXPIRED',
                    expired_at = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE status IN ('PENDING', 'CONFIRMED', 'CHANGED')
                  AND id IN ($placeholders)
                """.trimIndent(),
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                bookingIds.forEachIndexed { index, bookingId ->
                    statement.setLong(index + 2, bookingId)
                }
                statement.executeUpdate()
            }
        if (updated > 0) {
            cancelPendingReminders(connection, bookingIds)
        }
        return updated
    }

    suspend fun scheduleRemindersForBooking(
        bookingId: Long,
        now: Instant = Instant.now(),
        venueZoneId: ZoneId = ZoneId.systemDefault(),
    ): BookingReminderScheduleResult {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val booking =
                            loadById(
                                connection,
                                bookingId,
                            ) ?: return@use BookingReminderScheduleResult(
                                0, 0, 0,
                            )
                        if (booking.status !in REMINDER_ELIGIBLE_STATUSES) {
                            val canceledCount = cancelUnsentM7cReminders(connection, bookingId)
                            connection.commit()
                            return@use BookingReminderScheduleResult(0, 0, canceledCount)
                        }
                        if (hasQueuedOrSentM7cReminder(connection, booking.id)) {
                            val canceledCount = cancelUnsentM7cReminders(connection, bookingId)
                            connection.commit()
                            return@use BookingReminderScheduleResult(0, 0, canceledCount)
                        }
                        val plan = buildReminderPlan(booking, now, venueZoneId)
                        val keepDedupeKey =
                            plan?.let {
                                reminderDedupeKey(
                                    bookingId = booking.id,
                                    kind = it.kind,
                                    scheduledFor = it.scheduledFor,
                                    anchorAt = it.anchorAt,
                                )
                            }
                        val canceledCount =
                            cancelUnsentM7cReminders(
                                connection = connection,
                                bookingId = bookingId,
                                exceptDedupeKey = keepDedupeKey,
                            )
                        var pendingCount = 0
                        var skippedCount = 0
                        plan?.let {
                            if (upsertReminder(connection, booking.id, plan)) {
                                when (plan.status) {
                                    BookingReminderStatus.PENDING -> pendingCount++
                                    BookingReminderStatus.SKIPPED -> skippedCount++
                                    else -> Unit
                                }
                            }
                        }
                        connection.commit()
                        BookingReminderScheduleResult(
                            pendingCount = pendingCount,
                            skippedCount = skippedCount,
                            canceledCount = canceledCount,
                        )
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    suspend fun cancelPendingReminders(bookingId: Long): Int {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    cancelPendingReminders(connection, bookingId)
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    suspend fun pickDueReminders(
        now: Instant = Instant.now(),
        limit: Int = DEFAULT_REMINDER_BATCH_SIZE,
    ): List<BookingReminderDelivery> {
        require(limit > 0) { "limit must be > 0" }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val reminders = selectDueReminders(connection, now, limit)
                        connection.commit()
                        reminders
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    suspend fun markReminderQueued(reminderId: Long): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        UPDATE booking_reminders
                        SET status = ?,
                            attempts = attempts + 1,
                            sent_at = NULL,
                            last_error = NULL,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                          AND status = ?
                          AND policy_version = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, BookingReminderStatus.QUEUED.name)
                        statement.setLong(2, reminderId)
                        statement.setString(3, BookingReminderStatus.PENDING.name)
                        statement.setString(4, REMINDER_POLICY_M7C)
                        statement.executeUpdate() > 0
                    }
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    suspend fun markReminderFailed(
        reminderId: Long,
        lastError: String?,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        UPDATE booking_reminders
                        SET status = ?,
                            last_error = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                          AND status = ?
                          AND policy_version = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, BookingReminderStatus.FAILED.name)
                        statement.setString(2, lastError?.take(1000))
                        statement.setLong(3, reminderId)
                        statement.setString(4, BookingReminderStatus.PENDING.name)
                        statement.setString(5, REMINDER_POLICY_M7C)
                        statement.executeUpdate() > 0
                    }
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    suspend fun markGuestConfirmed(
        bookingId: Long,
        userId: Long,
        now: Instant = Instant.now(),
    ): BookingRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE bookings
                            SET last_guest_confirmation_at = COALESCE(last_guest_confirmation_at, ?),
                                updated_at = CURRENT_TIMESTAMP
                            WHERE id = ? AND user_id = ? AND status IN ('CONFIRMED', 'CHANGED')
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setTimestamp(1, Timestamp.from(now))
                            statement.setLong(2, bookingId)
                            statement.setLong(3, userId)
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

    suspend fun findActiveByGuest(
        bookingId: Long,
        userId: Long,
    ): BookingRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT b.id, b.venue_id, b.user_id, b.scheduled_at, b.party_size, b.comment, b.status,
                               b.display_number, b.display_date, b.cancel_reason_text, b.canceled_by_role, b.canceled_by_user_id,
                               b.arrival_deadline_at, b.seated_at, b.no_show_at, b.expired_at,
                               b.last_guest_confirmation_at, b.venue_confirmed_at, b.last_rescheduled_at,
                               u.guest_display_name
                        FROM bookings b
                        LEFT JOIN users u ON u.telegram_user_id = b.user_id
                        WHERE b.id = ? AND b.user_id = ?
                          AND b.status IN ('PENDING', 'CONFIRMED', 'CHANGED')
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, bookingId)
                        statement.setLong(2, userId)
                        statement.executeQuery().use { rs ->
                            if (!rs.next()) {
                                null
                            } else {
                                val booking = mapBooking(rs)
                                val holdMinutes = getOrCreateHoldMinutes(connection, booking.venueId)
                                booking.takeIf {
                                    isActiveAt(it.scheduledAt, it.arrivalDeadlineAt, holdMinutes, Instant.now())
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
                        SELECT b.id, b.venue_id, b.user_id, b.scheduled_at, b.party_size, b.comment, b.status,
                               b.display_number, b.display_date, b.cancel_reason_text, b.canceled_by_role, b.canceled_by_user_id,
                               b.arrival_deadline_at, b.seated_at, b.no_show_at, b.expired_at,
                               b.last_guest_confirmation_at, b.venue_confirmed_at, b.last_rescheduled_at,
                               u.guest_display_name
                        FROM bookings b
                        LEFT JOIN users u ON u.telegram_user_id = b.user_id
                        WHERE b.venue_id = ? AND b.user_id = ?
                        ORDER BY b.created_at DESC
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

    suspend fun listActiveByUser(
        userId: Long,
        limit: Int = 20,
    ): List<UserBookingSummaryRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT b.id,
                               b.venue_id,
                               v.name AS venue_name,
                               b.scheduled_at,
                               b.party_size,
                               b.comment,
                               b.status,
                               b.display_number,
                               b.display_date,
                               b.arrival_deadline_at,
                               b.last_guest_confirmation_at,
                               u.guest_display_name
                        FROM bookings b
                        JOIN venues v ON v.id = b.venue_id
                        LEFT JOIN users u ON u.telegram_user_id = b.user_id
                        WHERE b.user_id = ?
                          AND b.status IN ('PENDING', 'CONFIRMED', 'CHANGED')
                        ORDER BY b.scheduled_at ASC, b.id DESC
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.executeQuery().use { rs ->
                            val now = Instant.now()
                            val holdMinutesByVenue = mutableMapOf<Long, Int>()
                            buildList {
                                while (rs.next()) {
                                    add(
                                        UserBookingSummaryRecord(
                                            id = rs.getLong("id"),
                                            venueId = rs.getLong("venue_id"),
                                            venueName = rs.getString("venue_name"),
                                            scheduledAt = rs.getTimestamp("scheduled_at").toInstant(),
                                            partySize = rs.getInt("party_size").let { if (rs.wasNull()) null else it },
                                            comment = rs.getString("comment"),
                                            status =
                                                BookingStatus.fromDb(rs.getString("status"))
                                                    ?: BookingStatus.PENDING,
                                            displayNumber =
                                                rs.getInt("display_number").let { if (rs.wasNull()) null else it },
                                            displayDate = rs.getDate("display_date")?.toLocalDate(),
                                            arrivalDeadlineAt = rs.getTimestamp("arrival_deadline_at")?.toInstant(),
                                            lastGuestConfirmationAt =
                                                rs.getTimestamp("last_guest_confirmation_at")?.toInstant(),
                                            guestDisplayName = rs.getString("guest_display_name"),
                                        ),
                                    )
                                }
                            }.filter { booking ->
                                val holdMinutes =
                                    holdMinutesByVenue.getOrPut(booking.venueId) {
                                        getOrCreateHoldMinutes(connection, booking.venueId)
                                    }
                                isActiveAt(booking.scheduledAt, booking.arrivalDeadlineAt, holdMinutes, now)
                            }.take(limit)
                        }
                    }
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    suspend fun listActiveByVenue(
        venueId: Long,
        limit: Int = 20,
    ): List<BookingRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT b.id, b.venue_id, b.user_id, b.scheduled_at, b.party_size, b.comment, b.status,
                               b.display_number, b.display_date, b.cancel_reason_text, b.canceled_by_role, b.canceled_by_user_id,
                               b.arrival_deadline_at, b.seated_at, b.no_show_at, b.expired_at,
                               b.last_guest_confirmation_at, b.venue_confirmed_at, b.last_rescheduled_at,
                               u.guest_display_name
                        FROM bookings b
                        LEFT JOIN users u ON u.telegram_user_id = b.user_id
                        WHERE b.venue_id = ?
                          AND b.status IN ('PENDING', 'CONFIRMED', 'CHANGED')
                        ORDER BY b.scheduled_at ASC, b.id DESC
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        val holdMinutes = getOrCreateHoldMinutes(connection, venueId)
                        statement.executeQuery().use { rs ->
                            val now = Instant.now()
                            buildList {
                                while (rs.next()) {
                                    add(mapBooking(rs))
                                }
                            }.filter { booking ->
                                isActiveAt(booking.scheduledAt, booking.arrivalDeadlineAt, holdMinutes, now)
                            }.take(limit)
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
                            SET status = ?,
                                cancel_reason_text = NULL,
                                canceled_by_role = ?,
                                canceled_by_user_id = ?,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE id = ? AND venue_id = ? AND user_id = ? AND status IN ('PENDING', 'CONFIRMED', 'CHANGED')
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, nextStatus.name)
                            statement.setString(2, if (nextStatus == BookingStatus.CANCELED) "GUEST" else null)
                            if (nextStatus == BookingStatus.CANCELED) {
                                statement.setLong(3, userId)
                            } else {
                                statement.setNull(3, java.sql.Types.BIGINT)
                            }
                            statement.setLong(4, bookingId)
                            statement.setLong(5, venueId)
                            statement.setLong(6, userId)
                            statement.executeUpdate()
                        }
                    if (updated <= 0) return@use null
                    cancelPendingReminders(connection, bookingId)
                    loadById(connection, bookingId)
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    private suspend fun updateTerminalLifecycleStatus(
        venueId: Long,
        bookingId: Long,
        nextStatus: BookingStatus,
        timestampColumn: String,
    ): BookingRecord? {
        require(nextStatus in TERMINAL_STATUSES) { "nextStatus must be terminal" }
        require(timestampColumn == "seated_at" || timestampColumn == "no_show_at" || timestampColumn == "expired_at")
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    val now = Instant.now()
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE bookings
                            SET status = ?,
                                $timestampColumn = ?,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE id = ? AND venue_id = ? AND status IN ('PENDING', 'CONFIRMED', 'CHANGED')
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, nextStatus.name)
                            statement.setTimestamp(2, Timestamp.from(now))
                            statement.setLong(3, bookingId)
                            statement.setLong(4, venueId)
                            statement.executeUpdate()
                        }
                    if (updated <= 0) return@use null
                    cancelPendingReminders(connection, bookingId)
                    if (nextStatus == BookingStatus.SEATED) {
                        visitRepository?.recordBookingSeatedVisit(connection, bookingId, now)
                    }
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
            SELECT b.id, b.venue_id, b.user_id, b.scheduled_at, b.party_size, b.comment, b.status,
                   b.display_number, b.display_date, b.cancel_reason_text, b.canceled_by_role, b.canceled_by_user_id,
                   b.arrival_deadline_at, b.seated_at, b.no_show_at, b.expired_at,
                   b.last_guest_confirmation_at, b.venue_confirmed_at, b.last_rescheduled_at,
                   u.guest_display_name
            FROM bookings b
            LEFT JOIN users u ON u.telegram_user_id = b.user_id
            WHERE b.id = ?
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
            displayNumber = rs.getInt("display_number").let { if (rs.wasNull()) null else it },
            displayDate = rs.getDate("display_date")?.toLocalDate(),
            cancelReasonText = rs.getString("cancel_reason_text"),
            canceledByRole = rs.getString("canceled_by_role"),
            canceledByUserId = rs.getLong("canceled_by_user_id").let { if (rs.wasNull()) null else it },
            arrivalDeadlineAt = rs.getTimestamp("arrival_deadline_at")?.toInstant(),
            seatedAt = rs.getTimestamp("seated_at")?.toInstant(),
            noShowAt = rs.getTimestamp("no_show_at")?.toInstant(),
            expiredAt = rs.getTimestamp("expired_at")?.toInstant(),
            lastGuestConfirmationAt = rs.getTimestamp("last_guest_confirmation_at")?.toInstant(),
            venueConfirmedAt = rs.getTimestamp("venue_confirmed_at")?.toInstant(),
            lastRescheduledAt = rs.getTimestamp("last_rescheduled_at")?.toInstant(),
            guestDisplayName = rs.getString("guest_display_name"),
        )
    }

    private data class ReminderPlan(
        val kind: BookingReminderKind,
        val scheduledFor: Instant,
        val status: BookingReminderStatus,
        val anchorAt: Instant?,
    )

    private fun buildReminderPlan(
        booking: BookingRecord,
        now: Instant,
        venueZoneId: ZoneId,
    ): ReminderPlan? {
        if (booking.status !in REMINDER_ELIGIBLE_STATUSES) return null
        val anchorAt =
            booking.lastRescheduledAt
                ?: booking.venueConfirmedAt
                ?: return skippedReminderPlan(now = now, anchorAt = null)
        val preferred =
            adaptiveReminderTarget(
                booking = booking,
                anchorAt = anchorAt,
                now = now,
                venueZoneId = venueZoneId,
                hoursBeforeVisit = 24,
                requiredAnchorGap = Duration.ofHours(6),
            )
        if (preferred != null) {
            return ReminderPlan(
                kind = BookingReminderKind.PRE_VISIT,
                scheduledFor = preferred,
                status = BookingReminderStatus.PENDING,
                anchorAt = anchorAt,
            )
        }
        val fallback =
            adaptiveReminderTarget(
                booking = booking,
                anchorAt = anchorAt,
                now = now,
                venueZoneId = venueZoneId,
                hoursBeforeVisit = 3,
                requiredAnchorGap = Duration.ofHours(2),
            )
        if (fallback != null) {
            return ReminderPlan(
                kind = BookingReminderKind.PRE_VISIT,
                scheduledFor = fallback,
                status = BookingReminderStatus.PENDING,
                anchorAt = anchorAt,
            )
        }
        return skippedReminderPlan(now = now, anchorAt = anchorAt)
    }

    private fun skippedReminderPlan(
        now: Instant,
        anchorAt: Instant?,
    ): ReminderPlan =
        ReminderPlan(
            kind = BookingReminderKind.PRE_VISIT,
            scheduledFor = now,
            status = BookingReminderStatus.SKIPPED,
            anchorAt = anchorAt,
        )

    private fun adaptiveReminderTarget(
        booking: BookingRecord,
        anchorAt: Instant,
        now: Instant,
        venueZoneId: ZoneId,
        hoursBeforeVisit: Long,
        requiredAnchorGap: Duration,
    ): Instant? {
        val scheduledLocal = LocalDateTime.ofInstant(booking.scheduledAt, venueZoneId)
        val originalTarget = scheduledLocal.minusHours(hoursBeforeVisit).atZone(venueZoneId)
        val adjustedTarget = adjustReminderToQuietWindow(originalTarget)
        val adjustedInstant = adjustedTarget.toInstant()
        if (!adjustedInstant.isAfter(now)) return null
        if (!adjustedInstant.isBefore(booking.scheduledAt)) return null
        if (anchorAt.plus(requiredAnchorGap).isAfter(adjustedInstant)) return null
        return adjustedInstant
    }

    private fun adjustReminderToQuietWindow(target: ZonedDateTime): ZonedDateTime {
        val localTime = target.toLocalTime()
        return when {
            localTime.isAfter(REMINDER_QUIET_WINDOW_END) ->
                ZonedDateTime.of(target.toLocalDate(), REMINDER_QUIET_WINDOW_END, target.zone)
            localTime.isBefore(REMINDER_QUIET_WINDOW_START) ->
                ZonedDateTime.of(target.toLocalDate().minusDays(1), REMINDER_QUIET_WINDOW_END, target.zone)
            else -> target
        }
    }

    private fun upsertReminder(
        connection: java.sql.Connection,
        bookingId: Long,
        plan: ReminderPlan,
    ): Boolean {
        val dedupeKey = reminderDedupeKey(bookingId, plan.kind, plan.scheduledFor, plan.anchorAt)
        val existing =
            connection.prepareStatement(
                """
                SELECT id, status
                FROM booking_reminders
                WHERE dedupe_key = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, dedupeKey)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong("id") to rs.getString("status") else null
                }
            }
        if (existing != null) {
            val (id, status) = existing
            if (status == BookingReminderStatus.QUEUED.name || status == BookingReminderStatus.SENT.name) return false
            if (status == plan.status.name) return false
            connection.prepareStatement(
                """
                UPDATE booking_reminders
                SET status = ?,
                    attempts = 0,
                    sent_at = NULL,
                    last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, plan.status.name)
                statement.setLong(2, id)
                statement.executeUpdate()
            }
            return true
        }
        connection.prepareStatement(
            """
            INSERT INTO booking_reminders (booking_id, kind, scheduled_for, status, dedupe_key, policy_version)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, bookingId)
            statement.setString(2, plan.kind.name)
            statement.setTimestamp(3, Timestamp.from(plan.scheduledFor))
            statement.setString(4, plan.status.name)
            statement.setString(5, dedupeKey)
            statement.setString(6, REMINDER_POLICY_M7C)
            statement.executeUpdate()
        }
        return true
    }

    private fun hasQueuedOrSentM7cReminder(
        connection: java.sql.Connection,
        bookingId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT 1
            FROM booking_reminders
            WHERE booking_id = ?
              AND policy_version = ?
              AND status IN (?, ?)
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, bookingId)
            statement.setString(2, REMINDER_POLICY_M7C)
            statement.setString(3, BookingReminderStatus.QUEUED.name)
            statement.setString(4, BookingReminderStatus.SENT.name)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun cancelUnsentM7cReminders(
        connection: java.sql.Connection,
        bookingId: Long,
        exceptDedupeKey: String? = null,
    ): Int =
        connection.prepareStatement(
            """
            UPDATE booking_reminders
            SET status = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE booking_id = ?
              AND policy_version = ?
              AND status IN (?, ?, ?)
              AND (? IS NULL OR dedupe_key <> ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, BookingReminderStatus.CANCELED.name)
            statement.setLong(2, bookingId)
            statement.setString(3, REMINDER_POLICY_M7C)
            statement.setString(4, BookingReminderStatus.PENDING.name)
            statement.setString(5, BookingReminderStatus.FAILED.name)
            statement.setString(6, BookingReminderStatus.SKIPPED.name)
            statement.setString(7, exceptDedupeKey)
            statement.setString(8, exceptDedupeKey)
            statement.executeUpdate()
        }

    private fun selectDueReminders(
        connection: java.sql.Connection,
        now: Instant,
        limit: Int,
    ): List<BookingReminderDelivery> {
        val forUpdateClause =
            if (connection.metaData.databaseProductName.contains("H2", ignoreCase = true)) {
                ""
            } else {
                "FOR UPDATE OF br SKIP LOCKED"
            }
        return connection.prepareStatement(
            """
            SELECT br.id AS reminder_id,
                   br.booking_id,
                   br.kind,
                   br.scheduled_for,
                   br.attempts,
                   b.venue_id,
                   b.user_id,
                   b.scheduled_at,
                   b.party_size,
                   b.display_number,
                   b.display_date,
                   b.arrival_deadline_at,
                   vbs.hold_minutes,
                   v.name AS venue_name
            FROM booking_reminders br
            JOIN bookings b ON b.id = br.booking_id
            JOIN venues v ON v.id = b.venue_id
            LEFT JOIN venue_booking_settings vbs ON vbs.venue_id = b.venue_id
            WHERE br.policy_version = ?
              AND br.status = 'PENDING'
              AND br.scheduled_for <= ?
              AND b.status IN ('CONFIRMED', 'CHANGED')
            ORDER BY br.scheduled_for ASC, br.id ASC
            LIMIT ?
            $forUpdateClause
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, REMINDER_POLICY_M7C)
            statement.setTimestamp(2, Timestamp.from(now))
            statement.setInt(3, limit)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val scheduledAt = rs.getTimestamp("scheduled_at").toInstant()
                        val holdMinutes =
                            rs.getInt("hold_minutes").let {
                                if (rs.wasNull()) DEFAULT_HOLD_MINUTES else it
                            }
                        val arrivalDeadlineAt =
                            rs.getTimestamp("arrival_deadline_at")?.toInstant()
                                ?: scheduledAt.plus(Duration.ofMinutes(holdMinutes.toLong()))
                        add(
                            BookingReminderDelivery(
                                reminderId = rs.getLong("reminder_id"),
                                bookingId = rs.getLong("booking_id"),
                                kind = BookingReminderKind.valueOf(rs.getString("kind")),
                                scheduledFor = rs.getTimestamp("scheduled_for").toInstant(),
                                attempts = rs.getInt("attempts"),
                                venueId = rs.getLong("venue_id"),
                                venueName = rs.getString("venue_name"),
                                userId = rs.getLong("user_id"),
                                scheduledAt = scheduledAt,
                                partySize = rs.getInt("party_size").let { if (rs.wasNull()) null else it },
                                displayNumber = rs.getInt("display_number").let { if (rs.wasNull()) null else it },
                                displayDate = rs.getDate("display_date")?.toLocalDate(),
                                arrivalDeadlineAt = arrivalDeadlineAt,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun cancelPendingReminders(
        connection: java.sql.Connection,
        bookingId: Long,
    ): Int = cancelPendingReminders(connection, listOf(bookingId))

    private fun cancelPendingReminders(
        connection: java.sql.Connection,
        bookingIds: List<Long>,
    ): Int {
        if (bookingIds.isEmpty()) return 0
        val placeholders = bookingIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            UPDATE booking_reminders
            SET status = 'CANCELED',
                updated_at = CURRENT_TIMESTAMP
            WHERE booking_id IN ($placeholders)
              AND (
                  (policy_version = '$REMINDER_POLICY_M7C' AND status IN ('PENDING', 'FAILED', 'SKIPPED'))
                  OR (policy_version = 'LEGACY' AND status = 'PENDING')
              )
            """.trimIndent(),
        ).use { statement ->
            bookingIds.forEachIndexed { index, bookingId -> statement.setLong(index + 1, bookingId) }
            statement.executeUpdate()
        }
    }

    private fun reminderDedupeKey(
        bookingId: Long,
        kind: BookingReminderKind,
        scheduledFor: Instant,
        anchorAt: Instant?,
    ): String =
        buildString {
            append("booking:")
            append(bookingId)
            append(":m7c:")
            append(kind.name)
            append(':')
            append(scheduledFor.epochSecond)
            anchorAt?.let {
                append(':')
                append(it.epochSecond)
            }
        }

    private fun bookingDisplayDate(
        scheduledAt: Instant,
        venueZoneId: ZoneId,
    ): LocalDate = LocalDateTime.ofInstant(scheduledAt, venueZoneId).toLocalDate()

    private fun bookingDeadline(
        scheduledAt: Instant,
        holdMinutes: Int,
    ): Instant = scheduledAt.plus(Duration.ofMinutes(holdMinutes.toLong()))

    private fun getOrCreateHoldMinutes(
        connection: java.sql.Connection,
        venueId: Long,
    ): Int {
        findHoldMinutes(connection, venueId)
            ?.takeIf { isValidHoldMinutes(it) }
            ?.let { return it }
        runCatching {
            connection.prepareStatement(
                """
                INSERT INTO venue_booking_settings (venue_id, hold_minutes)
                VALUES (?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setInt(2, DEFAULT_HOLD_MINUTES)
                statement.executeUpdate()
            }
        }
        return findHoldMinutes(connection, venueId) ?: DEFAULT_HOLD_MINUTES
    }

    private fun findHoldMinutes(
        connection: java.sql.Connection,
        venueId: Long,
    ): Int? =
        connection.prepareStatement(
            """
            SELECT hold_minutes
            FROM venue_booking_settings
            WHERE venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getInt("hold_minutes") else null
            }
        }

    private fun setNullableTimestamp(
        statement: java.sql.PreparedStatement,
        index: Int,
        value: Instant?,
    ) {
        if (value == null) {
            statement.setNull(index, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        } else {
            statement.setTimestamp(index, Timestamp.from(value))
        }
    }

    private fun isActiveAt(
        scheduledAt: Instant,
        arrivalDeadlineAt: Instant?,
        holdMinutes: Int,
        now: Instant,
    ): Boolean = effectiveDeadlineAt(scheduledAt, arrivalDeadlineAt, holdMinutes) >= now

    private fun effectiveDeadlineAt(
        scheduledAt: Instant,
        arrivalDeadlineAt: Instant?,
        holdMinutes: Int,
    ): Instant = arrivalDeadlineAt ?: bookingDeadline(scheduledAt, holdMinutes)

    private fun isValidHoldMinutes(minutes: Int): Boolean = minutes in MIN_HOLD_MINUTES..MAX_HOLD_MINUTES

    private fun nextDisplayNumber(
        connection: java.sql.Connection,
        venueId: Long,
        displayDate: LocalDate,
    ): Int =
        connection.prepareStatement(
            """
            SELECT COALESCE(MAX(display_number), 0) + 1 AS next_number
            FROM bookings
            WHERE venue_id = ? AND display_date = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setDate(2, SqlDate.valueOf(displayDate))
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getInt("next_number") else 1
            }
        }

    companion object {
        const val DEFAULT_HOLD_MINUTES = 30
        const val DEFAULT_EXPIRY_BATCH_SIZE = 100
        const val DEFAULT_REMINDER_BATCH_SIZE = 100
        const val MIN_HOLD_MINUTES = 10
        const val MAX_HOLD_MINUTES = 240
        val ACTIVE_STATUSES = setOf(BookingStatus.PENDING, BookingStatus.CONFIRMED, BookingStatus.CHANGED)
        val TERMINAL_STATUSES =
            setOf(BookingStatus.CANCELED, BookingStatus.EXPIRED, BookingStatus.NO_SHOW, BookingStatus.SEATED)
        private const val REMINDER_POLICY_M7C = "M7C"
        private val REMINDER_ELIGIBLE_STATUSES = setOf(BookingStatus.CONFIRMED, BookingStatus.CHANGED)
        private val REMINDER_QUIET_WINDOW_START = LocalTime.of(10, 0)
        private val REMINDER_QUIET_WINDOW_END = LocalTime.of(22, 0)
    }
}
