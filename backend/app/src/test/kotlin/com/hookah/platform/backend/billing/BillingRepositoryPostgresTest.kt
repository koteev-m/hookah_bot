package com.hookah.platform.backend.billing

import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.test.PostgresTestEnv
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BillingRepositoryPostgresTest {
    private fun insertVenue(connection: Connection): Long {
        return connection.prepareStatement(
            """
                INSERT INTO venues (name, city, address, status)
                VALUES ('Billing Venue', 'Moscow', 'Tverskaya 1', ?)
            """.trimIndent(),
            java.sql.Statement.RETURN_GENERATED_KEYS
        ).use { statement ->
            statement.setString(1, VenueStatus.PUBLISHED.dbValue)
            statement.executeUpdate()
            statement.generatedKeys.use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    @Test
    fun `createInvoice is idempotent for same period`() = runBlocking {
        val database = PostgresTestEnv.createDatabase()
        val dataSource = PostgresTestEnv.createDataSource(database)
        try {
            val venueId = dataSource.connection.use { connection ->
                insertVenue(connection)
            }
            val repository = BillingInvoiceRepository(dataSource)
            val periodStart = LocalDate.of(2024, 1, 1)
            val periodEnd = LocalDate.of(2024, 1, 31)
            val dueAt = Instant.now()

            val first = repository.createInvoice(
                venueId = venueId,
                periodStart = periodStart,
                periodEnd = periodEnd,
                dueAt = dueAt,
                amountMinor = 1500,
                currency = "RUB",
                description = "January subscription",
                provider = "FAKE",
                providerInvoiceId = null,
                paymentUrl = null,
                providerRawPayload = null,
                status = InvoiceStatus.OPEN,
                paidAt = null,
                actorUserId = null
            )
            val second = repository.createInvoice(
                venueId = venueId,
                periodStart = periodStart,
                periodEnd = periodEnd,
                dueAt = dueAt,
                amountMinor = 1500,
                currency = "RUB",
                description = "January subscription",
                provider = "FAKE",
                providerInvoiceId = null,
                paymentUrl = null,
                providerRawPayload = null,
                status = InvoiceStatus.OPEN,
                paidAt = null,
                actorUserId = null
            )

            assertEquals(first.id, second.id)

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                        SELECT COUNT(*)
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
                        rs.next()
                        assertEquals(1, rs.getInt(1))
                    }
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `insertPaymentEventIdempotent ignores duplicate events`() = runBlocking {
        val database = PostgresTestEnv.createDatabase()
        val dataSource = PostgresTestEnv.createDataSource(database)
        try {
            val venueId = dataSource.connection.use { connection ->
                insertVenue(connection)
            }
            val invoiceRepository = BillingInvoiceRepository(dataSource)
            val paymentRepository = BillingPaymentRepository(dataSource)
            val invoice = invoiceRepository.createInvoice(
                venueId = venueId,
                periodStart = LocalDate.of(2024, 2, 1),
                periodEnd = LocalDate.of(2024, 2, 29),
                dueAt = Instant.now(),
                amountMinor = 2000,
                currency = "RUB",
                description = "February subscription",
                provider = "FAKE",
                providerInvoiceId = null,
                paymentUrl = null,
                providerRawPayload = null,
                status = InvoiceStatus.OPEN,
                paidAt = null,
                actorUserId = null
            )

            val inserted = paymentRepository.insertPaymentEventIdempotent(
                invoiceId = invoice.id,
                provider = "FAKE",
                providerEventId = "event-1",
                amountMinor = 2000,
                currency = "RUB",
                status = PaymentStatus.SUCCEEDED,
                occurredAt = Instant.now(),
                rawPayload = null
            )
            val duplicate = paymentRepository.insertPaymentEventIdempotent(
                invoiceId = invoice.id,
                provider = "FAKE",
                providerEventId = "event-1",
                amountMinor = 2000,
                currency = "RUB",
                status = PaymentStatus.SUCCEEDED,
                occurredAt = Instant.now(),
                rawPayload = null
            )

            assertTrue(inserted)
            assertTrue(!duplicate)

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM billing_payments"
                ).use { statement ->
                    statement.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(1, rs.getInt(1))
                    }
                }
            }
        } finally {
            dataSource.close()
        }
    }
}
