package com.hookah.platform.backend.billing

import com.hookah.platform.backend.api.DatabaseUnavailableException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BillingInvoiceRepository(private val dataSource: DataSource?) {
    suspend fun createInvoice(
        venueId: Long,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        dueAt: Instant,
        amountMinor: Int,
        currency: String,
        description: String,
        provider: String,
        providerInvoiceId: String?,
        paymentUrl: String?,
        status: InvoiceStatus,
        paidAt: Instant?,
        actorUserId: Long?
    ): BillingInvoice {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val insertedId = insertInvoice(
                            connection = connection,
                            venueId = venueId,
                            periodStart = periodStart,
                            periodEnd = periodEnd,
                            dueAt = dueAt,
                            amountMinor = amountMinor,
                            currency = currency,
                            description = description,
                            provider = provider,
                            providerInvoiceId = providerInvoiceId,
                            paymentUrl = paymentUrl,
                            status = status,
                            paidAt = paidAt,
                            actorUserId = actorUserId
                        )
                        val invoice = if (insertedId != null) {
                            loadInvoiceById(connection, insertedId)
                        } else {
                            loadInvoiceByPeriod(connection, venueId, periodStart, periodEnd)
                        } ?: throw SQLException("Invoice not found after upsert")
                        connection.commit()
                        invoice
                    } catch (e: CancellationException) {
                        rollbackBestEffort(connection)
                        throw e
                    } catch (e: SQLException) {
                        rollbackBestEffort(connection)
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun markInvoicePastDue(invoiceId: Long): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                            UPDATE billing_invoices
                            SET status = ?,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?
                              AND status = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, InvoiceStatus.PAST_DUE.dbValue)
                        statement.setLong(2, invoiceId)
                        statement.setString(3, InvoiceStatus.OPEN.dbValue)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun markInvoicePaid(invoiceId: Long, paidAt: Instant, actorUserId: Long?): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                            UPDATE billing_invoices
                            SET status = ?,
                                paid_at = ?,
                                updated_at = CURRENT_TIMESTAMP,
                                updated_by_user_id = ?
                            WHERE id = ?
                              AND status IN (?, ?)
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, InvoiceStatus.PAID.dbValue)
                        statement.setTimestamp(2, Timestamp.from(paidAt))
                        setLongOrNull(statement, 3, actorUserId)
                        statement.setLong(4, invoiceId)
                        statement.setString(5, InvoiceStatus.OPEN.dbValue)
                        statement.setString(6, InvoiceStatus.PAST_DUE.dbValue)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listOpenInvoicesDueBefore(now: Instant): List<BillingInvoice> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                            SELECT *
                            FROM billing_invoices
                            WHERE status = ?
                              AND due_at < ?
                            ORDER BY due_at ASC, id ASC
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, InvoiceStatus.OPEN.dbValue)
                        statement.setTimestamp(2, Timestamp.from(now))
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(mapInvoice(rs))
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

    suspend fun listInvoicesByVenue(venueId: Long, limit: Int, offset: Int): List<BillingInvoice> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                            SELECT *
                            FROM billing_invoices
                            WHERE venue_id = ?
                            ORDER BY period_start DESC, id DESC
                            LIMIT ?
                            OFFSET ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setInt(2, limit)
                        statement.setInt(3, offset)
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(mapInvoice(rs))
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

    private fun insertInvoice(
        connection: Connection,
        venueId: Long,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        dueAt: Instant,
        amountMinor: Int,
        currency: String,
        description: String,
        provider: String,
        providerInvoiceId: String?,
        paymentUrl: String?,
        status: InvoiceStatus,
        paidAt: Instant?,
        actorUserId: Long?
    ): Long? {
        val isH2 = connection.metaData.databaseProductName.equals("H2", ignoreCase = true)
        val sql = if (isH2) {
            """
                INSERT INTO billing_invoices (
                    venue_id,
                    period_start,
                    period_end,
                    due_at,
                    amount_minor,
                    currency,
                    description,
                    provider,
                    provider_invoice_id,
                    payment_url,
                    status,
                    paid_at,
                    updated_by_user_id
                )
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM billing_invoices
                    WHERE venue_id = ?
                      AND period_start = ?
                      AND period_end = ?
                )
            """.trimIndent()
        } else {
            """
                INSERT INTO billing_invoices (
                    venue_id,
                    period_start,
                    period_end,
                    due_at,
                    amount_minor,
                    currency,
                    description,
                    provider,
                    provider_invoice_id,
                    payment_url,
                    status,
                    paid_at,
                    updated_by_user_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (venue_id, period_start, period_end) DO NOTHING
            """.trimIndent()
        }
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            statement.setLong(1, venueId)
            statement.setDate(2, java.sql.Date.valueOf(periodStart))
            statement.setDate(3, java.sql.Date.valueOf(periodEnd))
            statement.setTimestamp(4, Timestamp.from(dueAt))
            statement.setInt(5, amountMinor)
            statement.setString(6, currency)
            statement.setString(7, description)
            statement.setString(8, provider)
            setStringOrNull(statement, 9, providerInvoiceId)
            setStringOrNull(statement, 10, paymentUrl)
            statement.setString(11, status.dbValue)
            setTimestampOrNull(statement, 12, paidAt)
            setLongOrNull(statement, 13, actorUserId)
            if (isH2) {
                statement.setLong(14, venueId)
                statement.setDate(15, java.sql.Date.valueOf(periodStart))
                statement.setDate(16, java.sql.Date.valueOf(periodEnd))
            }
            val updated = statement.executeUpdate()
            if (updated == 0) {
                return null
            }
            statement.generatedKeys.use { keys ->
                return if (keys.next()) keys.getLong(1) else null
            }
        }
    }

    private fun loadInvoiceById(connection: Connection, invoiceId: Long): BillingInvoice? {
        connection.prepareStatement(
            """
                SELECT *
                FROM billing_invoices
                WHERE id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, invoiceId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    return null
                }
                return mapInvoice(rs)
            }
        }
    }

    private fun loadInvoiceByPeriod(
        connection: Connection,
        venueId: Long,
        periodStart: LocalDate,
        periodEnd: LocalDate
    ): BillingInvoice? {
        connection.prepareStatement(
            """
                SELECT *
                FROM billing_invoices
                WHERE venue_id = ?
                  AND period_start = ?
                  AND period_end = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setDate(2, java.sql.Date.valueOf(periodStart))
            statement.setDate(3, java.sql.Date.valueOf(periodEnd))
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    return null
                }
                return mapInvoice(rs)
            }
        }
    }

    private fun mapInvoice(rs: ResultSet): BillingInvoice {
        val status = InvoiceStatus.fromDb(rs.getString("status"))
            ?: throw SQLException("Unknown invoice status")
        return BillingInvoice(
            id = rs.getLong("id"),
            venueId = rs.getLong("venue_id"),
            periodStart = rs.getDate("period_start").toLocalDate(),
            periodEnd = rs.getDate("period_end").toLocalDate(),
            dueAt = rs.getTimestamp("due_at").toInstant(),
            amountMinor = rs.getInt("amount_minor"),
            currency = rs.getString("currency"),
            description = rs.getString("description"),
            provider = rs.getString("provider"),
            providerInvoiceId = rs.getString("provider_invoice_id"),
            paymentUrl = rs.getString("payment_url"),
            status = status,
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            paidAt = rs.getTimestamp("paid_at")?.toInstant(),
            updatedByUserId = rs.getLongOrNull("updated_by_user_id")
        )
    }

    private fun ResultSet.getLongOrNull(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    private fun setStringOrNull(statement: java.sql.PreparedStatement, index: Int, value: String?) {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR)
        } else {
            statement.setString(index, value)
        }
    }

    private fun setLongOrNull(statement: java.sql.PreparedStatement, index: Int, value: Long?) {
        if (value == null) {
            statement.setNull(index, Types.BIGINT)
        } else {
            statement.setLong(index, value)
        }
    }

    private fun setTimestampOrNull(statement: java.sql.PreparedStatement, index: Int, value: Instant?) {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP)
        } else {
            statement.setTimestamp(index, Timestamp.from(value))
        }
    }

    private fun rollbackBestEffort(connection: Connection) {
        runCatching { connection.rollback() }
    }
}
