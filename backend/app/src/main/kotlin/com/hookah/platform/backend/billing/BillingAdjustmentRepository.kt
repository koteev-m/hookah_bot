package com.hookah.platform.backend.billing

import com.hookah.platform.backend.api.BillingOperationConflictException
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.NotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

@Serializable
private data class CourtesyReceipt(val requestedReason: String, val overview: OwnerBillingOverviewResponse)

class BillingAdjustmentRepository(private val dataSource: DataSource?) {
    internal suspend fun <T> withConnection(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            try {
                (dataSource ?: throw DatabaseUnavailableException()).connection.use(block)
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }

    internal suspend fun <T> withVenueTransaction(
        venueId: Long,
        block: (Connection) -> T,
    ): T =
        withConnection { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("SELECT id FROM venues WHERE id = ? FOR UPDATE").use { lock ->
                    lock.setLong(1, venueId)
                    lock.executeQuery().use { if (!it.next()) throw NotFoundException() }
                }
                block(connection).also { connection.commit() }
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }

    internal fun replay(
        connection: Connection,
        operation: UUID,
        venueId: Long,
        actor: Long,
        days: Int,
        reason: String,
    ): OwnerBillingOverviewResponse? =
        connection.prepareStatement(
            "SELECT venue_id, actor_user_id, days, operation_response " +
                "FROM billing_adjustments WHERE operation_key = ?",
        ).use { query ->
            query.setObject(1, operation)
            query.executeQuery().use { rows ->
                if (!rows.next()) return null
                if (venueId != rows.getLong(1) || actor != rows.getLong(2) ||
                    days != rows.getInt(3)
                ) {
                    throw BillingOperationConflictException()
                }
                val receipt = Json.decodeFromString<CourtesyReceipt>(rows.getString(4))
                if (receipt.requestedReason != reason) throw BillingOperationConflictException()
                receipt.overview
            }
        }

    internal fun recordResponse(
        connection: Connection,
        adjustmentId: Long,
        key: UUID,
        requestedReason: String,
        response: OwnerBillingOverviewResponse,
    ) {
        connection.prepareStatement(
            "UPDATE billing_adjustments SET operation_key = ?, operation_response = ? WHERE id = ?",
        ).use { statement ->
            statement.setObject(1, key)
            val receipt = CourtesyReceipt(requestedReason, response)
            statement.setString(2, Json.encodeToString(CourtesyReceipt.serializer(), receipt))
            statement.setLong(3, adjustmentId)
            try {
                check(statement.executeUpdate() == 1)
            } catch (e: SQLException) {
                if (e.sqlState == "23505") throw BillingOperationConflictException()
                throw e
            }
        }
    }

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
                    createCourtesyDaysAdjustment(
                        connection,
                        venueId,
                        days,
                        reason,
                        previousPaidThrough,
                        newPaidThrough,
                        actorUserId,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    internal fun createCourtesyDaysAdjustment(
        connection: Connection,
        venueId: Long,
        days: Int,
        reason: String,
        previousPaidThrough: LocalDate,
        newPaidThrough: LocalDate,
        actorUserId: Long,
    ): BillingAdjustment {
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
        return loadById(connection, insertedId) ?: throw SQLException("Adjustment not found after insert")
    }

    suspend fun findLatestCourtesyAdjustment(venueId: Long): BillingAdjustment? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    findLatestCourtesyAdjustment(connection, venueId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    internal fun findLatestCourtesyAdjustment(
        connection: Connection,
        venueId: Long,
    ): BillingAdjustment? {
        return connection.prepareStatement(
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

    suspend fun countCourtesyDays(venueId: Long): Int {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    countCourtesyDays(connection, venueId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    internal fun countCourtesyDays(
        connection: Connection,
        venueId: Long,
    ): Int {
        return connection.prepareStatement(
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
