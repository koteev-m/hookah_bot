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
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class BillingServiceTest {
    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeAll
        @JvmStatic
        fun setup() {
            val dbName = "billing_service_${UUID.randomUUID()}"
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
    fun `applyPaymentEvent is idempotent for duplicated provider event`() = runBlocking {
        val venueId = dataSource.connection.use { connection ->
            insertVenue(connection)
        }
        val invoiceRepository = BillingInvoiceRepository(dataSource)
        val paymentRepository = BillingPaymentRepository(dataSource)
        val invoice = invoiceRepository.createInvoice(
            venueId = venueId,
            periodStart = LocalDate.of(2024, 4, 1),
            periodEnd = LocalDate.of(2024, 4, 30),
            dueAt = Instant.now(),
            amountMinor = 3000,
            currency = "RUB",
            description = "April subscription",
            provider = "FAKE",
            providerInvoiceId = "fake-invoice-1",
            paymentUrl = null,
            providerRawPayload = null,
            status = InvoiceStatus.OPEN,
            paidAt = null,
            actorUserId = null
        )
        val service = BillingService(
            provider = FakeBillingProvider(),
            invoiceRepository = invoiceRepository,
            paymentRepository = paymentRepository
        )
        val event = PaymentEvent.Paid(
            provider = "FAKE",
            providerEventId = "event-1",
            providerInvoiceId = invoice.providerInvoiceId,
            amountMinor = 3000,
            currency = "RUB",
            occurredAt = Instant.now(),
            rawPayload = null
        )

        service.applyPaymentEvent(event)
        service.applyPaymentEvent(event)

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM billing_payments"
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    rs.next()
                    assertEquals(1, rs.getInt(1))
                }
            }
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
