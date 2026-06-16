package com.hookah.platform.backend.billing

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GenericHmacBillingProviderTest {
    private fun testProvider(): GenericHmacBillingProvider {
        return GenericHmacBillingProvider(
            GenericHmacBillingProviderConfig(
                checkoutBaseUrl = "https://payments.example.test/checkout",
                merchantId = "merchant-1",
                checkoutReturnUrl = "https://miniapp.example.test/return",
                signingSecret = "provider-secret",
                signatureHeader = "X-Billing-Signature",
            ),
        )
    }

    @Test
    fun `create invoice returns external checkout payment url`() {
        val provider = testProvider()

        val result =
            runBlockingTest {
                provider.createInvoice(
                    invoiceId = 42L,
                    venueId = 10L,
                    amountMinor = 1500,
                    currency = "RUB",
                    description = "Subscription",
                    periodStart = LocalDate.of(2024, 4, 1),
                    periodEnd = LocalDate.of(2024, 4, 30),
                    dueAt = Instant.parse("2024-05-01T00:00:00Z"),
                )
            }

        assertEquals("ghbp-42", result.providerInvoiceId)
        assertNotNull(result.paymentUrl)
        assertTrue(result.paymentUrl.startsWith("https://payments.example.test/checkout?"))
        assertTrue(result.paymentUrl.contains("invoice_id=ghbp-42"))
        assertTrue(result.paymentUrl.contains("signature="))
    }

    @Test
    fun `webhook without provider signature is rejected`() =
        testApplication {
            withBillingWebhookApp(this).use { fixture ->
                val response =
                    client.post("/api/billing/webhook/generic_hmac") {
                        header("X-Webhook-Secret-Token", fixture.routeSecret)
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody(fixture.payload)
                    }

                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        }

    @Test
    fun `webhook with valid signature applies payment`() =
        testApplication {
            withBillingWebhookApp(this).use { fixture ->
                val signature = fixture.signPayload(fixture.payload)
                val response =
                    client.post("/api/billing/webhook/generic_hmac") {
                        header("X-Webhook-Secret-Token", fixture.routeSecret)
                        header(fixture.providerSignatureHeader, signature)
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody(fixture.payload)
                    }

                assertEquals(HttpStatusCode.OK, response.status)

                fixture.dataSource.connection.use { connection ->
                    val (status, paidAt) = invoiceStatusFor(connection, fixture.invoiceId)
                    assertEquals(InvoiceStatus.PAID.dbValue, status)
                    assertNotNull(paidAt)
                }
            }
        }

    @Test
    fun `duplicate provider event id does not create duplicate payment rows`() =
        testApplication {
            withBillingWebhookApp(this).use { fixture ->
                val signature = fixture.signPayload(fixture.payload)

                repeat(2) {
                    val response =
                        client.post("/api/billing/webhook/generic_hmac") {
                            header("X-Webhook-Secret-Token", fixture.routeSecret)
                            header(fixture.providerSignatureHeader, signature)
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody(fixture.payload)
                        }
                    assertEquals(HttpStatusCode.OK, response.status)
                }

                fixture.dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT COUNT(*)
                        FROM billing_payments
                        WHERE provider = ? AND provider_event_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, GenericHmacBillingProvider.PROVIDER_NAME)
                        statement.setString(2, fixture.providerEventId)
                        statement.executeQuery().use { rs ->
                            rs.next()
                            assertEquals(1, rs.getInt(1))
                        }
                    }
                }
            }
        }

    @Test
    fun `webhook with non primitive event_id is rejected with bad request`() =
        testApplication {
            withBillingWebhookApp(this).use { fixture ->
                val payload =
                    """
                    {"event_id":{},"payment_status":"paid","invoice_id":"ghbp-1000","amount_minor":3000,"currency":"RUB","occurred_at":"2024-04-10T12:00:00Z"}
                    """.trimIndent()
                val signature = fixture.signPayload(payload)

                val response =
                    client.post("/api/billing/webhook/generic_hmac") {
                        header("X-Webhook-Secret-Token", fixture.routeSecret)
                        header(fixture.providerSignatureHeader, signature)
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody(payload)
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
        }

    @Test
    fun `webhook with non primitive amount_minor is rejected with bad request`() =
        testApplication {
            withBillingWebhookApp(this).use { fixture ->
                val payload =
                    """
                    {"event_id":"evt-1001","payment_status":"paid","invoice_id":"ghbp-1000","amount_minor":{},"currency":"RUB","occurred_at":"2024-04-10T12:00:00Z"}
                    """.trimIndent()
                val signature = fixture.signPayload(payload)

                val response =
                    client.post("/api/billing/webhook/generic_hmac") {
                        header("X-Webhook-Secret-Token", fixture.routeSecret)
                        header(fixture.providerSignatureHeader, signature)
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody(payload)
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
        }

    @Test
    fun `webhook signature header is trimmed before verification`() =
        testApplication {
            withBillingWebhookApp(this).use { fixture ->
                val signature = fixture.signPayload(fixture.payload)
                val response =
                    client.post("/api/billing/webhook/generic_hmac") {
                        header("X-Webhook-Secret-Token", fixture.routeSecret)
                        header(fixture.providerSignatureHeader, "  $signature  ")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody(fixture.payload)
                    }

                assertEquals(HttpStatusCode.OK, response.status)
            }
        }

    private class BillingWebhookFixture(
        val dataSource: HikariDataSource,
        val invoiceId: Long,
        val providerEventId: String,
        val payload: String,
        val routeSecret: String,
        val providerSignatureHeader: String,
        private val signingAlgorithm: BillingSignatureAlgorithm,
        private val signingSecret: String,
    ) : AutoCloseable {
        fun signPayload(payload: String): String = signingAlgorithm.sign(payload, signingSecret)

        override fun close() {
            dataSource.close()
        }
    }

    private fun withBillingWebhookApp(testApp: io.ktor.server.testing.ApplicationTestBuilder): BillingWebhookFixture {
        val dbName = "generic_billing_${UUID.randomUUID()}"
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

        val venueId = dataSource.connection.use(::insertVenue)
        val invoiceRepository = BillingInvoiceRepository(dataSource)
        val paymentRepository = BillingPaymentRepository(dataSource)
        val routeSecret = "route-secret"
        val providerSigningSecret = "provider-secret"
        val signatureHeader = "X-Billing-Signature"
        val providerConfig =
            GenericHmacBillingProviderConfig(
                checkoutBaseUrl = "https://payments.example.test/checkout",
                merchantId = "merchant-1",
                checkoutReturnUrl = "https://miniapp.example.test/return",
                signingSecret = providerSigningSecret,
                signatureHeader = signatureHeader,
            )
        val provider = GenericHmacBillingProvider(providerConfig)
        val billingService =
            BillingService(
                provider = provider,
                invoiceRepository = invoiceRepository,
                paymentRepository = paymentRepository,
            )
        val providerInvoiceId = "ghbp-1000"
        val invoice =
            runBlockingTest {
                invoiceRepository.createInvoice(
                    venueId = venueId,
                    periodStart = LocalDate.of(2024, 4, 1),
                    periodEnd = LocalDate.of(2024, 4, 30),
                    dueAt = Instant.now(),
                    amountMinor = 3000,
                    currency = "RUB",
                    description = "April subscription",
                    provider = GenericHmacBillingProvider.PROVIDER_NAME,
                    providerInvoiceId = providerInvoiceId,
                    paymentUrl = null,
                    providerRawPayload = null,
                    status = InvoiceStatus.OPEN,
                    paidAt = null,
                    actorUserId = null,
                )
            }
        val providerEventId = "evt-1000"
        val payload =
            """
            {"event_id":"$providerEventId","payment_status":"paid","invoice_id":"$providerInvoiceId","amount_minor":3000,"currency":"RUB","occurred_at":"2024-04-10T12:00:00Z"}
            """.trimIndent()

        testApp.application {
            install(StatusPages) {
                exception<ForbiddenException> { call, _ ->
                    call.respond(HttpStatusCode.Forbidden)
                }
            }
            routing {
                billingWebhookRoutes(
                    config =
                        BillingConfig(
                            provider = "generic_hmac",
                            webhookSecret = routeSecret,
                            webhookIpAllowlist = null,
                            webhookIpAllowlistUseXForwardedFor = false,
                        ),
                    providerRegistry = BillingProviderRegistry(listOf(provider)),
                    billingService = billingService,
                )
            }
        }

        return BillingWebhookFixture(
            dataSource = dataSource,
            invoiceId = invoice.id,
            providerEventId = providerEventId,
            payload = payload,
            routeSecret = routeSecret,
            providerSignatureHeader = signatureHeader,
            signingAlgorithm = HmacSha256HexBillingSignatureAlgorithm(),
            signingSecret = providerSigningSecret,
        )
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
                rs.getString("status") to rs.getTimestamp("paid_at")?.toInstant()
            }
        }
    }
}

private fun <T> runBlockingTest(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
