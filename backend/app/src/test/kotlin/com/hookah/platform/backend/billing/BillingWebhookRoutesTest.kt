package com.hookah.platform.backend.billing

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BillingWebhookRoutesTest {
    private class StaticBillingProvider(private val event: PaymentEvent) : BillingProvider {
        override fun providerName(): String = event.provider

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
            return ProviderInvoiceResult(providerInvoiceId = null, paymentUrl = null, rawPayload = null)
        }

        override suspend fun handleWebhook(call: io.ktor.server.application.ApplicationCall): PaymentEvent = event
    }

    private fun insertVenue(connection: Connection): Long {
        return connection.prepareStatement(
            """
            INSERT INTO venues (name, city, address, status)
            VALUES ('Billing Venue', 'Test City', 'Test Address', ?)
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

    @Test
    fun `missing webhook secret returns forbidden`() =
        testApplication {
            val config =
                BillingConfig(
                    provider = "fake",
                    webhookSecret = "super-secret",
                    webhookIpAllowlist = null,
                    webhookIpAllowlistUseXForwardedFor = false,
                )
            val providerRegistry = BillingProviderRegistry(listOf(FakeBillingProvider()))
            val billingService =
                BillingService(
                    provider = FakeBillingProvider(),
                    invoiceRepository = mockk(relaxed = true),
                    paymentRepository = mockk(relaxed = true),
                )

            application {
                install(StatusPages) {
                    exception<ForbiddenException> { call, _ ->
                        call.respond(HttpStatusCode.Forbidden)
                    }
                }
                routing {
                    billingWebhookRoutes(config, providerRegistry, billingService)
                }
            }

            val response = client.post("/api/billing/webhook")
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `invalid webhook secret returns forbidden`() =
        testApplication {
            val config =
                BillingConfig(
                    provider = "fake",
                    webhookSecret = "super-secret",
                    webhookIpAllowlist = null,
                    webhookIpAllowlistUseXForwardedFor = false,
                )
            val providerRegistry = BillingProviderRegistry(listOf(FakeBillingProvider()))
            val billingService =
                BillingService(
                    provider = FakeBillingProvider(),
                    invoiceRepository = mockk(relaxed = true),
                    paymentRepository = mockk(relaxed = true),
                )

            application {
                install(StatusPages) {
                    exception<ForbiddenException> { call, _ ->
                        call.respond(HttpStatusCode.Forbidden)
                    }
                }
                routing {
                    billingWebhookRoutes(config, providerRegistry, billingService)
                }
            }

            val response =
                client.post("/api/billing/webhook") {
                    headers { append("X-Webhook-Secret-Token", "wrong-secret") }
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `valid webhook secret returns ok`() =
        testApplication {
            val config =
                BillingConfig(
                    provider = "fake",
                    webhookSecret = "super-secret",
                    webhookIpAllowlist = null,
                    webhookIpAllowlistUseXForwardedFor = false,
                )
            val providerRegistry = BillingProviderRegistry(listOf(FakeBillingProvider()))
            val billingService =
                BillingService(
                    provider = FakeBillingProvider(),
                    invoiceRepository = mockk(relaxed = true),
                    paymentRepository = mockk(relaxed = true),
                )

            application {
                install(StatusPages) {
                    exception<ForbiddenException> { call, _ ->
                        call.respond(HttpStatusCode.Forbidden)
                    }
                }
                routing {
                    billingWebhookRoutes(config, providerRegistry, billingService)
                }
            }

            val response =
                client.post("/api/billing/webhook") {
                    headers { append("X-Webhook-Secret-Token", "super-secret") }
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `allowlist allows forwarded ip`() =
        testApplication {
            val config =
                BillingConfig(
                    provider = "fake",
                    webhookSecret = "super-secret",
                    webhookIpAllowlist = com.hookah.platform.backend.security.IpAllowlist.parse("203.0.113.10"),
                    webhookIpAllowlistUseXForwardedFor = true,
                )
            val providerRegistry = BillingProviderRegistry(listOf(FakeBillingProvider()))
            val billingService =
                BillingService(
                    provider = FakeBillingProvider(),
                    invoiceRepository = mockk(relaxed = true),
                    paymentRepository = mockk(relaxed = true),
                )

            application {
                install(StatusPages) {
                    exception<ForbiddenException> { call, _ ->
                        call.respond(HttpStatusCode.Forbidden)
                    }
                }
                routing {
                    billingWebhookRoutes(config, providerRegistry, billingService)
                }
            }

            val response =
                client.post("/api/billing/webhook") {
                    headers {
                        append("X-Webhook-Secret-Token", "super-secret")
                        append("X-Forwarded-For", "203.0.113.10")
                    }
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `allowlist blocks forwarded ip not in allowlist`() =
        testApplication {
            val config =
                BillingConfig(
                    provider = "fake",
                    webhookSecret = "super-secret",
                    webhookIpAllowlist = com.hookah.platform.backend.security.IpAllowlist.parse("203.0.113.10"),
                    webhookIpAllowlistUseXForwardedFor = true,
                )
            val providerRegistry = BillingProviderRegistry(listOf(FakeBillingProvider()))
            val billingService =
                BillingService(
                    provider = FakeBillingProvider(),
                    invoiceRepository = mockk(relaxed = true),
                    paymentRepository = mockk(relaxed = true),
                )

            application {
                install(StatusPages) {
                    exception<ForbiddenException> { call, _ ->
                        call.respond(HttpStatusCode.Forbidden)
                    }
                }
                routing {
                    billingWebhookRoutes(config, providerRegistry, billingService)
                }
            }

            val response =
                client.post("/api/billing/webhook") {
                    headers {
                        append("X-Webhook-Secret-Token", "super-secret")
                        append("X-Forwarded-For", "203.0.113.11")
                    }
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `duplicate webhook delivery is idempotent`() =
        testApplication {
            val dbName = "billing_webhook_${UUID.randomUUID()}"
            val dataSource =
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
            try {
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
                        provider = "test",
                        providerInvoiceId = "invoice-1",
                        paymentUrl = null,
                        providerRawPayload = null,
                        status = InvoiceStatus.OPEN,
                        paidAt = null,
                        actorUserId = null,
                    )
                val event =
                    PaymentEvent.Paid(
                        provider = "test",
                        providerEventId = "event-1",
                        providerInvoiceId = invoice.providerInvoiceId,
                        amountMinor = 3000,
                        currency = "RUB",
                        occurredAt = Instant.now(),
                        rawPayload = null,
                    )
                val provider = StaticBillingProvider(event)
                val config =
                    BillingConfig(
                        provider = "test",
                        webhookSecret = "super-secret",
                        webhookIpAllowlist = null,
                        webhookIpAllowlistUseXForwardedFor = false,
                    )
                val providerRegistry = BillingProviderRegistry(listOf(provider))
                val billingService =
                    BillingService(
                        provider = provider,
                        invoiceRepository = invoiceRepository,
                        paymentRepository = paymentRepository,
                    )

                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, _ ->
                            call.respond(HttpStatusCode.Forbidden)
                        }
                    }
                    routing {
                        billingWebhookRoutes(config, providerRegistry, billingService)
                    }
                }

                repeat(2) {
                    val response =
                        client.post("/api/billing/webhook") {
                            headers { append("X-Webhook-Secret-Token", "super-secret") }
                        }
                    assertEquals(HttpStatusCode.OK, response.status)
                }

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
            } finally {
                dataSource.close()
            }
        }
}
