package com.hookah.platform.backend.billing

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.LocalDate
import javax.sql.DataSource

class BillingAdjustmentRepository(private val dataSource: DataSource?) {
    suspend fun createCourtesyDaysAdjustment(
        venueId: Long,
        days: Int,
        reason: String,
        previousPaidThrough: LocalDate,
        newPaidThrough: LocalDate,
        actorUserId: Long,
    ): BillingAdjustment {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val insertedId =
                        connection.prepareStatement(
                            """
                            INSERT INTO billing_adjustments (
                                venue_id,
                                kind,
                                days,
                                reason,
                                previous_paid_through,
                                new_paid_through,
                                actor_user_id
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            Statement.RETURN_GENERATED_KEYS,
                        ).use { statement ->
                            statement.setLong(1, venueId)
                            statement.setString(2, BillingAdjustmentKind.COURTESY_DAYS.dbValue)
                            statement.setInt(3, days)
                            statement.setString(4, reason)
                            statement.setDate(5, java.sql.Date.valueOf(previousPaidThrough))
                            statement.setDate(6, java.sql.Date.valueOf(newPaidThrough))
                            statement.setLong(7, actorUserId)
                            statement.executeUpdate()
                            statement.generatedKeys.use { keys ->
                                if (!keys.next()) {
                                    throw SQLException("Adjustment id not returned")
                                }
                                keys.getLong(1)
                            }
                        }
                    loadById(connection, insertedId) ?: throw SQLException("Adjustment not found after insert")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findLatestCourtesyAdjustment(venueId: Long): BillingAdjustment? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT *
                        FROM billing_adjustments
                        WHERE venue_id = ?
                          AND kind = ?
                        ORDER BY new_paid_through DESC, id DESC
                        LIMIT 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setString(2, BillingAdjustmentKind.COURTESY_DAYS.dbValue)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) mapAdjustment(rs) else null
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun countCourtesyDays(venueId: Long): Int {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT COALESCE(SUM(days), 0)
                        FROM billing_adjustments
                        WHERE venue_id = ?
                          AND kind = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setString(2, BillingAdjustmentKind.COURTESY_DAYS.dbValue)
                        statement.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun loadById(
        connection: Connection,
        adjustmentId: Long,
    ): BillingAdjustment? {
        connection.prepareStatement(
            """
            SELECT *
            FROM billing_adjustments
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, adjustmentId)
            statement.executeQuery().use { rs ->
                return if (rs.next()) mapAdjustment(rs) else null
            }
        }
    }

    private fun mapAdjustment(rs: ResultSet): BillingAdjustment {
        val kind =
            BillingAdjustmentKind.fromDb(rs.getString("kind"))
                ?: throw SQLException("Unknown billing adjustment kind")
        return BillingAdjustment(
            id = rs.getLong("id"),
            venueId = rs.getLong("venue_id"),
            kind = kind,
            days = rs.getInt("days"),
            reason = rs.getString("reason"),
            previousPaidThrough = rs.getDate("previous_paid_through").toLocalDate(),
            newPaidThrough = rs.getDate("new_paid_through").toLocalDate(),
            actorUserId = rs.getLong("actor_user_id"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }
}
