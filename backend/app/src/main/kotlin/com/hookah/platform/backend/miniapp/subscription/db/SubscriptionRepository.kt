package com.hookah.platform.backend.miniapp.subscription.db

import com.hookah.platform.backend.analytics.AnalyticsEventRecord
import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.analytics.analyticsCorrelationPayload
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

class SubscriptionRepository(
    private val dataSource: DataSource?,
    private val analyticsEventRepository: AnalyticsEventRepository? = null,
) {
    suspend fun getSubscriptionStatus(venueId: Long): SubscriptionStatus {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    ensureRowExistsForVenue(connection, venueId)
                    connection.prepareStatement(
                        """
                        SELECT status
                        FROM venue_subscriptions
                        WHERE venue_id = ?
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
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
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
    ): SubscriptionStatus? {
        return connection.prepareStatement(
            """
            SELECT status
            FROM venue_subscriptions
            WHERE venue_id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) SubscriptionStatus.fromDb(rs.getString("status")) else null
            }
        }
    }

    private fun ensureRowExistsForVenue(
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
