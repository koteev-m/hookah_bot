package com.hookah.platform.backend.miniapp.subscription.db

import com.hookah.platform.backend.analytics.AnalyticsEventRecord
import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.analytics.analyticsCorrelationPayload
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.SubscriptionResumeConflictException
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.sql.Types
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

data class VenueSubscriptionState(
    val venueId: Long,
    val status: SubscriptionStatus,
    val trialEnd: Instant?,
    val paidStart: Instant?,
    val updatedAt: Instant?,
)

internal enum class ResumeBasis(val status: SubscriptionStatus) {
    PAID(SubscriptionStatus.ACTIVE),
    COURTESY(SubscriptionStatus.ACTIVE),
    TRIAL(SubscriptionStatus.TRIAL),
}

class SubscriptionRepository(
    private val dataSource: DataSource?,
    private val analyticsEventRepository: AnalyticsEventRepository? = null,
) {
    suspend fun findSubscriptionState(venueId: Long): VenueSubscriptionState? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    findSubscriptionState(connection, venueId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    internal fun findSubscriptionState(
        connection: Connection,
        venueId: Long,
        forUpdate: Boolean = false,
    ): VenueSubscriptionState? {
        val lock = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT venue_id, status, trial_end, paid_start, updated_at
            FROM venue_subscriptions
            WHERE venue_id = ?$lock
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    return null
                }
                VenueSubscriptionState(
                    venueId = rs.getLong("venue_id"),
                    status = SubscriptionStatus.fromDb(rs.getString("status")),
                    trialEnd = rs.getTimestamp("trial_end")?.toInstant(),
                    paidStart = rs.getTimestamp("paid_start")?.toInstant(),
                    updatedAt = rs.getTimestamp("updated_at")?.toInstant(),
                )
            }
        }
    }

    suspend fun getSubscriptionStatus(venueId: Long): SubscriptionStatus {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    ensureRowExistsForVenue(connection, venueId)
                    getSubscriptionStatus(connection, venueId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    fun getSubscriptionStatus(
        connection: Connection,
        venueId: Long,
        forUpdate: Boolean = false,
    ): SubscriptionStatus {
        val lockClause = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT status
            FROM venue_subscriptions
            WHERE venue_id = ?$lockClause
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    SubscriptionStatus.fromDb(rs.getString("status"))
                } else {
                    SubscriptionStatus.UNKNOWN
                }
            }
        }
    }

    suspend fun ensureRowExistsForVenue(venueId: Long) {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    ensureRowExistsForVenue(connection, venueId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateStatus(
        venueId: Long,
        status: SubscriptionStatus,
        paidStart: Instant? = null,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        ensureRowExistsForVenue(connection, venueId)
                        val previousStatus = loadStatusForUpdate(connection, venueId)
                        val updated =
                            applyStatusUpdate(
                                connection = connection,
                                venueId = venueId,
                                status = status,
                                paidStart = paidStart,
                            )
                        appendStatusChangedEvent(
                            connection = connection,
                            venueId = venueId,
                            previousStatus = previousStatus,
                            nextStatus = status,
                        )
                        connection.commit()
                        updated
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = true }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateStatusIfCurrentIn(
        venueId: Long,
        allowedCurrentStatuses: Set<SubscriptionStatus>,
        nextStatus: SubscriptionStatus,
        paidStart: Instant? = null,
        requireNoUnpaidOn: java.time.LocalDate? = null,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        ensureRowExistsForVenue(connection, venueId)
                        val previousStatus = loadStatusForUpdate(connection, venueId)
                        if (previousStatus == null || previousStatus !in allowedCurrentStatuses) {
                            connection.commit()
                            return@use false
                        }
                        if (requireNoUnpaidOn != null &&
                            hasUnpaidPeriod(connection, venueId, requireNoUnpaidOn)
                        ) {
                            connection.commit()
                            return@use false
                        }
                        val shouldUpdate = previousStatus != nextStatus || paidStart != null
                        if (!shouldUpdate) {
                            connection.commit()
                            return@use true
                        }
                        val updated =
                            applyStatusUpdate(
                                connection = connection,
                                venueId = venueId,
                                status = nextStatus,
                                paidStart = paidStart,
                            )
                        appendStatusChangedEvent(
                            connection = connection,
                            venueId = venueId,
                            previousStatus = previousStatus,
                            nextStatus = nextStatus,
                        )
                        connection.commit()
                        updated
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = true }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    internal fun resumeSuspended(
        connection: Connection,
        venueId: Long,
        expectedUpdatedAt: Instant,
        clock: Clock,
    ): ResumeBasis {
        val state = findSubscriptionState(connection, venueId, forUpdate = true)
        if (state?.status != SubscriptionStatus.SUSPENDED_BY_PLATFORM) {
            throw SubscriptionResumeConflictException("Подписка не находится под административной блокировкой.")
        }
        if (state.updatedAt != expectedUpdatedAt) {
            throw SubscriptionResumeConflictException("Блокировка изменилась. Обновите данные и подтвердите заново.")
        }
        val today = LocalDate.now(clock)
        // A concurrent payment or refund holds a foreign-key lock on its invoice.
        // Wait for those writers, then evaluate the committed ledger in a fresh READ COMMITTED statement.
        connection.prepareStatement(
            """
            SELECT COUNT(*) FROM (
                SELECT id FROM billing_invoices
                WHERE venue_id = ? AND status = 'PAID' AND ? BETWEEN period_start AND period_end
                ORDER BY id FOR UPDATE
            ) locked_invoices
            """.trimIndent(),
        ).use { lock ->
            lock.setLong(1, venueId)
            lock.setObject(2, today)
            lock.executeQuery().use { check(it.next()) }
        }
        val now = clock.instant()
        if (now.atZone(clock.zone).toLocalDate() != today) {
            throw SubscriptionResumeConflictException("Начался новый расчётный день. Проверьте основание ещё раз.")
        }
        val utc = now.atOffset(ZoneOffset.UTC)
        val basis =
            connection.prepareStatement(
                """
                SELECT CASE
                    WHEN EXISTS (
                        SELECT 1 FROM billing_invoices i
                        JOIN billing_payments p ON p.invoice_id = i.id
                        WHERE i.venue_id = s.venue_id AND i.status = 'PAID' AND i.paid_at <= ?
                          AND ? BETWEEN i.period_start AND i.period_end
                          AND p.status = 'SUCCEEDED' AND p.provider = i.provider
                          AND p.amount_minor = i.amount_minor AND p.currency = i.currency AND p.occurred_at <= ?
                          AND NOT EXISTS (
                              SELECT 1 FROM billing_payments r WHERE r.invoice_id = i.id AND r.status = 'REFUNDED'
                          )
                    ) THEN 'PAID'
                    WHEN EXISTS (
                        SELECT 1 FROM billing_adjustments a
                        JOIN audit_log l ON l.entity_type = 'billing_adjustment' AND l.entity_id = a.id
                          AND l.actor_user_id = a.actor_user_id AND l.action = 'BILLING_COURTESY_DAYS_ADDED'
                        WHERE a.venue_id = s.venue_id AND a.kind = 'COURTESY_DAYS'
                          AND a.previous_paid_through < ? AND a.new_paid_through >= ?
                    ) THEN 'COURTESY'
                    WHEN s.trial_end > ? THEN 'TRIAL'
                END
                FROM venue_subscriptions s WHERE s.venue_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, utc.toLocalDateTime())
                statement.setObject(2, today)
                statement.setObject(3, utc.toLocalDateTime())
                statement.setObject(4, today)
                statement.setObject(5, today)
                statement.setObject(6, utc)
                statement.setLong(7, venueId)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getString(1)?.let(ResumeBasis::valueOf)
                }
            } ?: throw SubscriptionResumeConflictException("Отсутствует действующее основание доступа.")
        check(applyStatusUpdate(connection, venueId, basis.status, null))
        appendStatusChangedEvent(connection, venueId, state.status, basis.status)
        return basis
    }

    internal fun activatePastDueForCourtesy(
        connection: Connection,
        venueId: Long,
    ) {
        val previous = getSubscriptionStatus(connection, venueId, forUpdate = true)
        if (previous != SubscriptionStatus.PAST_DUE) return
        applyStatusUpdate(connection, venueId, SubscriptionStatus.ACTIVE, null)
        appendStatusChangedEvent(connection, venueId, previous, SubscriptionStatus.ACTIVE)
    }

    private fun hasUnpaidPeriod(
        connection: Connection,
        venueId: Long,
        date: java.time.LocalDate,
    ): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM billing_invoices WHERE venue_id = ? AND status IN ('OPEN', 'PAST_DUE') " +
                "AND period_start <= ? AND period_end >= ? LIMIT 1",
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setObject(2, date)
            statement.setObject(3, date)
            statement.executeQuery().use { it.next() }
        }

    private fun applyStatusUpdate(
        connection: Connection,
        venueId: Long,
        status: SubscriptionStatus,
        paidStart: Instant?,
    ): Boolean {
        return connection.prepareStatement(
            """
            UPDATE venue_subscriptions
            SET status = ?,
                paid_start = COALESCE(?, paid_start),
                updated_at = CURRENT_TIMESTAMP
            WHERE venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, status.name)
            if (paidStart == null) {
                statement.setNullTimestampWithTimezoneSafe(2)
            } else {
                statement.setTimestamp(2, Timestamp.from(paidStart))
            }
            statement.setLong(3, venueId)
            statement.executeUpdate() > 0
        }
    }

    private fun appendStatusChangedEvent(
        connection: Connection,
        venueId: Long,
        previousStatus: SubscriptionStatus?,
        nextStatus: SubscriptionStatus,
    ) {
        val changed = previousStatus != null && previousStatus != nextStatus
        if (!changed) {
            return
        }
        analyticsEventRepository?.append(
            connection = connection,
            event =
                AnalyticsEventRecord(
                    eventType = "subscription_status_changed",
                    payload =
                        analyticsCorrelationPayload(
                            venueId = venueId,
                            extra =
                                mapOf(
                                    "previousStatus" to previousStatus.wire,
                                    "nextStatus" to nextStatus.wire,
                                ),
                        ),
                    venueId = venueId,
                    idempotencyKey =
                        buildString {
                            append("subscription_status_changed:")
                            append(venueId)
                            append(':')
                            append(previousStatus.wire)
                            append(':')
                            append(nextStatus.wire)
                        },
                ),
        )
    }

    private fun loadStatusForUpdate(
        connection: Connection,
        venueId: Long,
    ): SubscriptionStatus? =
        getSubscriptionStatus(connection, venueId, forUpdate = true)
            .takeUnless { it == SubscriptionStatus.UNKNOWN }

    fun ensureRowExistsForVenue(
        connection: Connection,
        venueId: Long,
    ) {
        val now = Instant.now()
        val trialEnd = now.plus(14, ChronoUnit.DAYS)
        connection.prepareStatement(
            """
            INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
            SELECT ?, ?, ?, ?, ?
            WHERE NOT EXISTS (
                SELECT 1
                FROM venue_subscriptions
                WHERE venue_id = ?
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, SubscriptionStatus.TRIAL.name)
            statement.setTimestamp(3, Timestamp.from(trialEnd))
            statement.setNullTimestampWithTimezoneSafe(4)
            statement.setTimestamp(5, Timestamp.from(now))
            statement.setLong(6, venueId)
            try {
                statement.executeUpdate()
            } catch (e: SQLException) {
                if (e.sqlState != "23505") {
                    throw e
                }
            }
        }
    }

    private fun PreparedStatement.setNullTimestampWithTimezoneSafe(index: Int) {
        try {
            setNull(index, Types.TIMESTAMP_WITH_TIMEZONE)
        } catch (first: SQLException) {
            try {
                setNull(index, Types.TIMESTAMP)
            } catch (second: SQLException) {
                first.addSuppressed(second)
                throw first
            }
        }
    }
}
