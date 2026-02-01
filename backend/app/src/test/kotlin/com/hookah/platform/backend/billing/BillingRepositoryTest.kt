package com.hookah.platform.backend.billing

import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class BillingRepositoryTest {
    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeAll
        @JvmStatic
        fun setup() {
            val dbName = "billing_repo_${UUID.randomUUID()}"
            dataSource = HikariDataSource(
                HikariConfig().apply {
                    driverClassName = "org.h2.Driver"
                    jdbcUrl = "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
                    maximumPoolSize = 3
                }
            )
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/h2")
                .load()
                .migrate()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            dataSource.close()
        }

        private fun insertVenue(connection: Connection): Long {
            return connection.prepareStatement(
                """
                    INSERT INTO venues (name, status)
                    VALUES ('Billing Venue', ?)
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
    }

    @Test
    fun `migrations create billing tables`() {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                    SELECT COUNT(*)
                    FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_NAME = 'billing_invoices'
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    rs.next()
                    assertEquals(1, rs.getInt(1))
                }
            }
            connection.prepareStatement(
                """
                    SELECT COUNT(*)
                    FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_NAME = 'billing_payments'
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    rs.next()
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `createInvoice is idempotent for same period`() = runBlocking {
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
    }

    @Test
    fun `insertPaymentEventIdempotent ignores duplicate events`() = runBlocking {
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
    }

    @Test
    fun `markInvoicePaid updates status and paid_at`() = runBlocking {
        val venueId = dataSource.connection.use { connection ->
            insertVenue(connection)
        }
        val repository = BillingInvoiceRepository(dataSource)
        val invoice = repository.createInvoice(
            venueId = venueId,
            periodStart = LocalDate.of(2024, 3, 1),
            periodEnd = LocalDate.of(2024, 3, 31),
            dueAt = Instant.now(),
            amountMinor = 2500,
            currency = "RUB",
            description = "March subscription",
            provider = "FAKE",
            providerInvoiceId = null,
            paymentUrl = null,
            providerRawPayload = null,
            status = InvoiceStatus.OPEN,
            paidAt = null,
            actorUserId = null
        )
        val paidAt = Instant.now()

        val updated = repository.markInvoicePaid(invoice.id, paidAt, actorUserId = 42)

        assertTrue(updated)

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                    SELECT status, paid_at
                    FROM billing_invoices
                    WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, invoice.id)
                statement.executeQuery().use { rs ->
                    rs.next()
                    assertEquals(InvoiceStatus.PAID.dbValue, rs.getString("status"))
                    assertNotNull(rs.getTimestamp("paid_at"))
                }
            }
        }
    }
}
