package com.hookah.platform.backend.billing

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import javax.sql.DataSource

class BillingPaymentRepository(private val dataSource: DataSource?) {
    suspend fun insertPaymentEventIdempotent(
        invoiceId: Long,
        provider: String,
        providerEventId: String,
        amountMinor: Int,
        currency: String,
        status: PaymentStatus,
        occurredAt: java.time.Instant,
        rawPayload: String?,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val isH2 = connection.metaData.databaseProductName.equals("H2", ignoreCase = true)
                    val sql =
                        if (isH2) {
                            """
                            INSERT INTO billing_payments (
                                invoice_id,
                                provider,
                                provider_event_id,
                                amount_minor,
                                currency,
                                status,
                                occurred_at,
                                raw_payload
                            )
                            SELECT ?, ?, ?, ?, ?, ?, ?, ?
                            WHERE NOT EXISTS (
                                SELECT 1
                                FROM billing_payments
                                WHERE provider = ?
                                  AND provider_event_id = ?
                            )
                            """.trimIndent()
                        } else {
                            """
                            INSERT INTO billing_payments (
                                invoice_id,
                                provider,
                                provider_event_id,
                                amount_minor,
                                currency,
                                status,
                                occurred_at,
                                raw_payload
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (provider, provider_event_id) DO NOTHING
                            """.trimIndent()
                        }
                    connection.prepareStatement(
                        sql,
                    ).use { statement ->
                        statement.setLong(1, invoiceId)
                        statement.setString(2, provider)
                        statement.setString(3, providerEventId)
                        statement.setInt(4, amountMinor)
                        statement.setString(5, currency)
                        statement.setString(6, status.dbValue)
                        statement.setTimestamp(7, Timestamp.from(occurredAt))
                        if (rawPayload == null) {
                            statement.setNull(8, java.sql.Types.VARCHAR)
                        } else {
                            statement.setString(8, rawPayload)
                        }
                        if (isH2) {
                            statement.setString(9, provider)
                            statement.setString(10, providerEventId)
                        }
                        statement.executeUpdate() > 0
                    }
                }
            } catch (e: SQLException) {
                if (e.sqlState == "23505") {
                    return@withContext false
                }
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun mapPayment(rs: ResultSet): BillingPayment {
        val status =
            PaymentStatus.fromDb(rs.getString("status"))
                ?: throw SQLException("Unknown payment status")
        return BillingPayment(
            id = rs.getLong("id"),
            invoiceId = rs.getLong("invoice_id"),
            provider = rs.getString("provider"),
            providerEventId = rs.getString("provider_event_id"),
            amountMinor = rs.getInt("amount_minor"),
            currency = rs.getString("currency"),
            status = status,
            occurredAt = rs.getTimestamp("occurred_at").toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            rawPayload = rs.getString("raw_payload"),
        )
    }
}
