package com.hookah.platform.backend.miniapp.subscription.db

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

class SubscriptionRepository(private val dataSource: DataSource?) {
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
                    ensureRowExistsForVenue(connection, venueId)
                    connection.prepareStatement(
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
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
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
