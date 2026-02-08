package com.hookah.platform.backend.billing

import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class BillingServiceTest {
    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeAll
        @JvmStatic
        fun setup() {
            val dbName = "billing_service_${UUID.randomUUID()}"
            dataSource =
                HikariDataSource(
                    HikariConfig().apply {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl =
                            "jdbc:h2:mem:$dbName;MODE=PostgreSQL;" +
                            "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
                        maximumPoolSize = 3
                    },
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
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, VenueStatus.PUBLISHED.dbValue)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

        private fun invoiceStatusFor(
            connection: Connection,
            invoiceId: Long,
        ): Pair<String, Instant?> {
            return connection.prepareStatement(
                """
                SELECT status, paid_at
                FROM billing_invoices
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, invoiceId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    val status = rs.getString("status")
                    val paidAt = rs.getTimestamp("paid_at")?.toInstant()
                    status to paidAt
                }
            }
        }
    }

    @Test
    fun `applyPaymentEvent is idempotent for duplicated provider event`() =
        runBlocking {
            val venueId =
                dataSource.connection.use { connection ->
                    insertVenue(connection)
                }
            val invoiceRepository = BillingInvoiceRepository(dataSource)
            val paymentRepository = BillingPaymentRepository(dataSource)
            val invoice =
                invoiceRepository.createInvoice(
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
                    actorUserId = null,
                )
            val service =
                BillingService(
                    provider = FakeBillingProvider(),
                    invoiceRepository = invoiceRepository,
                    paymentRepository = paymentRepository,
                )
            val event =
                PaymentEvent.Paid(
                    provider = "FAKE",
                    providerEventId = "event-1",
                    providerInvoiceId = invoice.providerInvoiceId,
                    amountMinor = 3000,
                    currency = "RUB",
                    occurredAt = Instant.now(),
                    rawPayload = null,
                )

            service.applyPaymentEvent(event)
            service.applyPaymentEvent(event)

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT COUNT(*)
                    FROM billing_payments
                    WHERE provider = ?
                      AND provider_event_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, event.provider)
                    statement.setString(2, event.providerEventId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(1, rs.getInt(1))
                    }
                }
                val (status, paidAt) = invoiceStatusFor(connection, invoice.id)
                assertEquals(InvoiceStatus.PAID.dbValue, status)
                assertNotNull(paidAt)
            }
        }

    @Test
    fun `applyPaymentEvent reconciles paid invoice even if payment event already stored`() =
        runBlocking {
            val venueId =
                dataSource.connection.use { connection ->
                    insertVenue(connection)
                }
            val invoiceRepository = BillingInvoiceRepository(dataSource)
            val paymentRepository = BillingPaymentRepository(dataSource)
            val invoice =
                invoiceRepository.createInvoice(
                    venueId = venueId,
                    periodStart = LocalDate.of(2024, 5, 1),
                    periodEnd = LocalDate.of(2024, 5, 31),
                    dueAt = Instant.now(),
                    amountMinor = 4500,
                    currency = "RUB",
                    description = "May subscription",
                    provider = "FAKE",
                    providerInvoiceId = "fake-invoice-2",
                    paymentUrl = null,
                    providerRawPayload = null,
                    status = InvoiceStatus.OPEN,
                    paidAt = null,
                    actorUserId = null,
                )
            val event =
                PaymentEvent.Paid(
                    provider = "FAKE",
                    providerEventId = "event-2",
                    providerInvoiceId = invoice.providerInvoiceId,
                    amountMinor = 4500,
                    currency = "RUB",
                    occurredAt = Instant.now(),
                    rawPayload = null,
                )

            paymentRepository.insertPaymentEventIdempotent(
                invoiceId = invoice.id,
                provider = event.provider,
                providerEventId = event.providerEventId,
                amountMinor = event.amountMinor,
                currency = event.currency,
                status = PaymentStatus.SUCCEEDED,
                occurredAt = event.occurredAt,
                rawPayload = event.rawPayload,
            )

            val service =
                BillingService(
                    provider = FakeBillingProvider(),
                    invoiceRepository = invoiceRepository,
                    paymentRepository = paymentRepository,
                )

            service.applyPaymentEvent(event)

            dataSource.connection.use { connection ->
                val (status, paidAt) = invoiceStatusFor(connection, invoice.id)
                assertEquals(InvoiceStatus.PAID.dbValue, status)
                assertNotNull(paidAt)
            }
        }

    @Test
    fun `applyPaymentEvent does not mark paid when amount or currency mismatch`() =
        runBlocking {
            val venueId =
                dataSource.connection.use { connection ->
                    insertVenue(connection)
                }
            val invoiceRepository = BillingInvoiceRepository(dataSource)
            val paymentRepository = BillingPaymentRepository(dataSource)
            val invoice =
                invoiceRepository.createInvoice(
                    venueId = venueId,
                    periodStart = LocalDate.of(2024, 6, 1),
                    periodEnd = LocalDate.of(2024, 6, 30),
                    dueAt = Instant.now(),
                    amountMinor = 5000,
                    currency = "RUB",
                    description = "June subscription",
                    provider = "FAKE",
                    providerInvoiceId = "fake-invoice-3",
                    paymentUrl = null,
                    providerRawPayload = null,
                    status = InvoiceStatus.OPEN,
                    paidAt = null,
                    actorUserId = null,
                )
            val event =
                PaymentEvent.Paid(
                    provider = "FAKE",
                    providerEventId = "event-3",
                    providerInvoiceId = invoice.providerInvoiceId,
                    amountMinor = 5200,
                    currency = "USD",
                    occurredAt = Instant.now(),
                    rawPayload = null,
                )
            val service =
                BillingService(
                    provider = FakeBillingProvider(),
                    invoiceRepository = invoiceRepository,
                    paymentRepository = paymentRepository,
                )

            service.applyPaymentEvent(event)

            dataSource.connection.use { connection ->
                val (status, paidAt) = invoiceStatusFor(connection, invoice.id)
                assertEquals(InvoiceStatus.OPEN.dbValue, status)
                assertEquals(null, paidAt)
            }
        }

    @Test
    fun `createDraftOrOpenInvoice is idempotent for provider calls`() =
        runBlocking {
            val venueId =
                dataSource.connection.use { connection ->
                    insertVenue(connection)
                }
            val invoiceRepository = BillingInvoiceRepository(dataSource)
            val paymentRepository = BillingPaymentRepository(dataSource)
            val provider = CountingBillingProvider()
            val service =
                BillingService(
                    provider = provider,
                    invoiceRepository = invoiceRepository,
                    paymentRepository = paymentRepository,
                )

            val first =
                service.createDraftOrOpenInvoice(
                    venueId = venueId,
                    amountMinor = 6000,
                    currency = "RUB",
                    description = "July subscription",
                    periodStart = LocalDate.of(2024, 7, 1),
                    periodEnd = LocalDate.of(2024, 7, 31),
                    dueAt = Instant.now(),
                )
            val second =
                service.createDraftOrOpenInvoice(
                    venueId = venueId,
                    amountMinor = 6000,
                    currency = "RUB",
                    description = "July subscription",
                    periodStart = LocalDate.of(2024, 7, 1),
                    periodEnd = LocalDate.of(2024, 7, 31),
                    dueAt = Instant.now(),
                )

            assertEquals(1, provider.createCalls)
            assertEquals("fake-${first.id}", first.providerInvoiceId)
            assertEquals("fake-${first.id}", second.providerInvoiceId)
        }

    private class CountingBillingProvider : BillingProvider {
        var createCalls: Int = 0

        override fun providerName(): String = "FAKE"

        override suspend fun createInvoice(
            invoiceId: Long,
            venueId: Long,
            amountMinor: Int,
            currency: String,
            description: String,
            periodStart: LocalDate,
            periodEnd: LocalDate,
            dueAt: Instant,
        ): ProviderInvoiceResult {
            createCalls += 1
            val providerInvoiceId = "fake-$invoiceId"
            return ProviderInvoiceResult(
                providerInvoiceId = providerInvoiceId,
                paymentUrl = "fake://invoice/$providerInvoiceId",
                rawPayload = null,
            )
        }

        override suspend fun handleWebhook(call: io.ktor.server.application.ApplicationCall): PaymentEvent? = null
    }
}
