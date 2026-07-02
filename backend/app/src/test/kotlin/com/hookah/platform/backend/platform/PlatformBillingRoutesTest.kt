package com.hookah.platform.backend.platform

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.billing.OwnerBillingOverviewResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlatformBillingRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `billing overview is read only and hides fake checkout URLs`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-billing-overview-read-only")
            val ownerId = 5101L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            seedInvoice(jdbcUrl, venueId)
            val token = issueToken(config, userId = ownerId)

            val response =
                client.get("/api/platform/venues/$venueId/billing") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload =
                json.decodeFromString(
                    OwnerBillingOverviewResponse.serializer(),
                    response.bodyAsText(),
                )
            assertEquals(venueId, payload.venueId)
            assertEquals("unknown", payload.subscriptionStatus)
            assertFalse(payload.paymentAvailable)
            assertEquals(null, payload.checkoutUrl)
            assertEquals(null, payload.invoices.single().checkoutUrl)
            assertEquals(1, countInvoices(jdbcUrl, venueId))
            assertEquals(0, countSubscriptionRows(jdbcUrl, venueId))
        }

    @Test
    fun `platform checkout ensure reports fake provider manual only without creating invoice`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-billing-fake-checkout")
            val ownerId = 5151L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            seedSubscriptionSettings(jdbcUrl, venueId)
            val token = issueToken(config, userId = ownerId)

            val response =
                client.post("/api/platform/venues/$venueId/billing/checkout") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload =
                json.decodeFromString(
                    OwnerBillingOverviewResponse.serializer(),
                    response.bodyAsText(),
                )
            assertFalse(payload.paymentAvailable)
            assertEquals("fake_provider_manual_only", payload.unavailableReason)
            assertEquals(null, payload.checkoutUrl)
            assertEquals(0, countInvoices(jdbcUrl, venueId))
            assertEquals(1, countAuditActions(jdbcUrl, "BILLING_CHECKOUT_ENSURE", ownerId))
        }

    @Test
    fun `platform checkout ensure is idempotent and audited`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-billing-checkout-idempotent")
            val ownerId = 5202L
            val config = buildConfig(jdbcUrl, ownerId, genericCheckout = true)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            seedSubscriptionSettings(jdbcUrl, venueId)
            val token = issueToken(config, userId = ownerId)

            repeat(2) {
                val response =
                    client.post("/api/platform/venues/$venueId/billing/checkout") {
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val payload =
                    json.decodeFromString(
                        OwnerBillingOverviewResponse.serializer(),
                        response.bodyAsText(),
                    )
                assertTrue(payload.paymentAvailable)
                assertTrue(payload.checkoutUrl?.startsWith("https://pay.example.test/checkout?") == true)
            }

            assertEquals(1, countInvoices(jdbcUrl, venueId))
            assertEquals(2, countAuditActions(jdbcUrl, "BILLING_CHECKOUT_ENSURE", ownerId))
        }

    @Test
    fun `non owner cannot access billing endpoints`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-billing-rbac")
            val ownerId = 1201L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            val invoiceId = seedInvoice(jdbcUrl, venueId)
            val token = issueToken(config, userId = 2202L)

            val listResponse =
                client.get("/api/platform/venues/$venueId/invoices?limit=10&offset=0") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.Forbidden, listResponse.status)
            assertApiErrorEnvelope(listResponse, ApiErrorCodes.FORBIDDEN)

            val overviewResponse =
                client.get("/api/platform/venues/$venueId/billing") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.Forbidden, overviewResponse.status)
            assertApiErrorEnvelope(overviewResponse, ApiErrorCodes.FORBIDDEN)

            val checkoutResponse =
                client.post("/api/platform/venues/$venueId/billing/checkout") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.Forbidden, checkoutResponse.status)
            assertApiErrorEnvelope(checkoutResponse, ApiErrorCodes.FORBIDDEN)

            val markResponse =
                client.post("/api/platform/invoices/$invoiceId/mark-paid") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformMarkInvoicePaidRequest.serializer(),
                            PlatformMarkInvoicePaidRequest(),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Forbidden, markResponse.status)
            assertApiErrorEnvelope(markResponse, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `mark paid is idempotent and stores payment once`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-billing-idempotent")
            val ownerId = 3303L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            val invoiceId = seedInvoice(jdbcUrl, venueId)
            val token = issueToken(config, userId = ownerId)

            val firstResponse =
                client.post("/api/platform/invoices/$invoiceId/mark-paid") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformMarkInvoicePaidRequest.serializer(),
                            PlatformMarkInvoicePaidRequest(),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, firstResponse.status)
            val firstBody =
                json.decodeFromString(
                    PlatformMarkInvoicePaidResponse.serializer(),
                    firstResponse.bodyAsText(),
                )
            assertEquals(true, firstBody.ok)
            assertEquals(false, firstBody.alreadyPaid)
            assertEquals(1, countPayments(jdbcUrl, "manual:$invoiceId"))
            assertEquals(1, countAuditActions(jdbcUrl, "BILLING_MARK_PAID", ownerId))
            assertTrue(lastAuditPayload(jdbcUrl, "BILLING_MARK_PAID").contains("manual_platform_action"))
            assertTrue(lastAuditPayload(jdbcUrl, "BILLING_MARK_PAID").contains("previousStatus"))

            val invoiceAfterFirst = invoiceStatus(jdbcUrl, invoiceId)
            assertEquals("PAID", invoiceAfterFirst.first)
            assertNotNull(invoiceAfterFirst.second)

            val secondResponse =
                client.post("/api/platform/invoices/$invoiceId/mark-paid") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformMarkInvoicePaidRequest.serializer(),
                            PlatformMarkInvoicePaidRequest(),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, secondResponse.status)
            val secondBody =
                json.decodeFromString(
                    PlatformMarkInvoicePaidResponse.serializer(),
                    secondResponse.bodyAsText(),
                )
            assertEquals(true, secondBody.ok)
            assertEquals(true, secondBody.alreadyPaid)
            assertEquals(1, countPayments(jdbcUrl, "manual:$invoiceId"))
        }

    @Test
    fun `mark paid rejects non payable invoices`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-billing-invalid-status")
            val ownerId = 4404L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            val invoiceId = seedInvoice(jdbcUrl, venueId, status = "VOID")
            val token = issueToken(config, userId = ownerId)

            val response =
                client.post("/api/platform/invoices/$invoiceId/mark-paid") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformMarkInvoicePaidRequest.serializer(),
                            PlatformMarkInvoicePaidRequest(),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
            assertEquals(0, countPayments(jdbcUrl, "manual:$invoiceId"))

            val invoiceAfter = invoiceStatus(jdbcUrl, invoiceId)
            assertEquals("VOID", invoiceAfter.first)
            assertEquals(null, invoiceAfter.second)
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(
        jdbcUrl: String,
        ownerId: Long,
        genericCheckout: Boolean = false,
    ): MapApplicationConfig {
        val values =
            mutableMapOf(
                "app.env" to appEnv,
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to jdbcUrl,
                "db.user" to "sa",
                "db.password" to "",
                "platform.ownerUserId" to ownerId.toString(),
                "venue.staffInviteSecretPepper" to "invite-pepper",
            )
        if (genericCheckout) {
            values["billing.provider"] = "generic_hmac"
            values["billing.generic.checkoutBaseUrl"] = "https://pay.example.test/checkout"
            values["billing.generic.signingSecret"] = "test-signing-secret"
        }
        return MapApplicationConfig(*values.toList().toTypedArray())
    }

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long,
    ): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(userId).token
    }

    private fun seedVenue(jdbcUrl: String): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venues (name, city, address, status)
                VALUES ('Seed', 'City', 'Address', ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, VenueStatus.DRAFT.dbValue)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) return rs.getLong(1)
                }
            }
        }
        error("Failed to insert venue")
    }

    private fun seedInvoice(
        jdbcUrl: String,
        venueId: Long,
        status: String = "OPEN",
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
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
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                val today = LocalDate.now()
                statement.setLong(1, venueId)
                statement.setDate(2, java.sql.Date.valueOf(today))
                statement.setDate(3, java.sql.Date.valueOf(today.plusDays(30)))
                statement.setTimestamp(4, Timestamp.from(Instant.now().plusSeconds(86400)))
                statement.setInt(5, 12000)
                statement.setString(6, "RUB")
                statement.setString(7, "Subscription")
                statement.setString(8, "FAKE")
                statement.setString(9, "fake-invoice-$venueId")
                statement.setString(10, "fake://invoice/fake-invoice-$venueId")
                statement.setString(11, status)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) return rs.getLong(1)
                }
            }
        }
        error("Failed to insert invoice")
    }

    private fun seedSubscriptionSettings(
        jdbcUrl: String,
        venueId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_subscription_settings (
                    venue_id,
                    trial_end_date,
                    paid_start_date,
                    base_price_minor,
                    price_override_minor,
                    currency,
                    updated_by_user_id
                )
                VALUES (?, ?, ?, ?, NULL, 'RUB', ?)
                """.trimIndent(),
            ).use { statement ->
                val paidStart = LocalDate.now().minusDays(1)
                statement.setLong(1, venueId)
                statement.setDate(2, java.sql.Date.valueOf(paidStart.minusDays(14)))
                statement.setDate(3, java.sql.Date.valueOf(paidStart))
                statement.setInt(4, 15000)
                statement.setLong(5, 1L)
                statement.executeUpdate()
            }
        }
    }

    private fun countInvoices(
        jdbcUrl: String,
        venueId: Long,
    ): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM billing_invoices
                WHERE venue_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) return rs.getInt(1)
                }
            }
        }
        return 0
    }

    private fun countSubscriptionRows(
        jdbcUrl: String,
        venueId: Long,
    ): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM venue_subscriptions
                WHERE venue_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) return rs.getInt(1)
                }
            }
        }
        return 0
    }

    private fun countPayments(
        jdbcUrl: String,
        providerEventId: String,
    ): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM billing_payments
                WHERE provider_event_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, providerEventId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) return rs.getInt(1)
                }
            }
        }
        return 0
    }

    private fun invoiceStatus(
        jdbcUrl: String,
        invoiceId: Long,
    ): Pair<String, Instant?> {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT status, paid_at
                FROM billing_invoices
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, invoiceId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getString("status") to rs.getTimestamp("paid_at")?.toInstant()
                    }
                }
            }
        }
        error("Invoice not found")
    }

    private fun countAuditActions(
        jdbcUrl: String,
        action: String,
        actorUserId: Long,
    ): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM audit_log
                WHERE action = ?
                  AND actor_user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, action)
                statement.setLong(2, actorUserId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) return rs.getInt(1)
                }
            }
        }
        return 0
    }

    private fun lastAuditPayload(
        jdbcUrl: String,
        action: String,
    ): String {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM audit_log
                WHERE action = ?
                ORDER BY id DESC
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, action)
                statement.executeQuery().use { rs ->
                    if (rs.next()) return rs.getString("payload_json")
                }
            }
        }
        return ""
    }
}
