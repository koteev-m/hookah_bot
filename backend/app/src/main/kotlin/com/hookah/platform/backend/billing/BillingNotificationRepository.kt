package com.hookah.platform.backend.billing

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

enum class BillingNotificationKind(val dbValue: String) {
    UPCOMING_DUE("UPCOMING_DUE"),
}

class BillingNotificationRepository(private val dataSource: DataSource?) {
    suspend fun insertNotificationIdempotent(
        invoiceId: Long,
        kind: BillingNotificationKind,
        sentAt: Instant,
        payloadJson: String,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val inserted =
                        insertNotification(
                            connection = connection,
                            invoiceId = invoiceId,
                            kind = kind,
                            sentAt = sentAt,
                            payloadJson = payloadJson,
                        )
                    inserted > 0
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun insertNotification(
        connection: Connection,
        invoiceId: Long,
        kind: BillingNotificationKind,
        sentAt: Instant,
        payloadJson: String,
    ): Int {
        val isH2 = connection.metaData.databaseProductName.equals("H2", ignoreCase = true)
        val sql =
            if (isH2) {
                """
                INSERT INTO billing_notifications (invoice_id, kind, sent_at, payload_json)
                SELECT ?, ?, ?, ?
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM billing_notifications
                    WHERE invoice_id = ?
                      AND kind = ?
                )
                """.trimIndent()
            } else {
                """
                INSERT INTO billing_notifications (invoice_id, kind, sent_at, payload_json)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (invoice_id, kind) DO NOTHING
                """.trimIndent()
            }
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            statement.setLong(1, invoiceId)
            statement.setString(2, kind.dbValue)
            statement.setTimestamp(3, Timestamp.from(sentAt))
            statement.setString(4, payloadJson)
            if (isH2) {
                statement.setLong(5, invoiceId)
                statement.setString(6, kind.dbValue)
            }
            return statement.executeUpdate()
        }
    }
}
