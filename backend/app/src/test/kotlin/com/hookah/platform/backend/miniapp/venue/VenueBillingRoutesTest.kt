package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.billing.OwnerBillingOverviewResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.platform.PlatformAddCourtesyDaysRequest
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

class VenueBillingRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `venue subscription get is owner only and read only`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-billing-read-only")
            val config = buildConfig(jdbcUrl, genericCheckout = true)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerUserId = 6101L
            val managerUserId = 6102L
            val staffUserId = 6103L
            val venueId = seedVenueWithMembership(jdbcUrl, ownerUserId, "OWNER")
            addMembership(jdbcUrl, venueId, managerUserId, "MANAGER")
            addMembership(jdbcUrl, venueId, staffUserId, "STAFF")
            seedSubscriptionSettings(jdbcUrl, venueId)

            val ownerToken = issueToken(config, ownerUserId)
            val managerToken = issueToken(config, managerUserId)
            val staffToken = issueToken(config, staffUserId)
            val invoiceCountBefore = countInvoices(jdbcUrl, venueId)
            val subscriptionRowsBefore = countSubscriptionRows(jdbcUrl, venueId)
            val adjustmentRowsBefore = countAdjustments(jdbcUrl, venueId)

            val ownerResponse =
                client.get("/api/venue/$venueId/subscription") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, ownerResponse.status)
            val payload =
                json.decodeFromString(
                    OwnerBillingOverviewResponse.serializer(),
                    ownerResponse.bodyAsText(),
                )
            assertEquals(venueId, payload.venueId)
            assertEquals(0, invoiceCountBefore)
            assertEquals(0, subscriptionRowsBefore)
            assertEquals(0, adjustmentRowsBefore)
            assertEquals(invoiceCountBefore, countInvoices(jdbcUrl, venueId))
            assertEquals(subscriptionRowsBefore, countSubscriptionRows(jdbcUrl, venueId))
            assertEquals(adjustmentRowsBefore, countAdjustments(jdbcUrl, venueId))

            val managerResponse =
                client.get("/api/venue/$venueId/subscription") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, managerResponse.status)
            assertApiErrorEnvelope(managerResponse, ApiErrorCodes.FORBIDDEN)

            val managerCheckoutResponse =
                client.post("/api/venue/$venueId/subscription/checkout") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, managerCheckoutResponse.status)
            assertApiErrorEnvelope(managerCheckoutResponse, ApiErrorCodes.FORBIDDEN)

            val staffCheckoutResponse =
                client.post("/api/venue/$venueId/subscription/checkout") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, staffCheckoutResponse.status)
            assertApiErrorEnvelope(staffCheckoutResponse, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `venue owner manager and staff cannot add courtesy days`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-billing-courtesy-rbac")
            val config = buildConfig(jdbcUrl, platformOwnerId = 9999L)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerUserId = 6111L
            val managerUserId = 6112L
            val staffUserId = 6113L
            val venueId = seedVenueWithMembership(jdbcUrl, ownerUserId, "OWNER")
            addMembership(jdbcUrl, venueId, managerUserId, "MANAGER")
            addMembership(jdbcUrl, venueId, staffUserId, "STAFF")

            listOf(ownerUserId, managerUserId, staffUserId).forEach { userId ->
                val response =
                    client.post("/api/platform/venues/$venueId/billing/courtesy-days") {
                        headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, userId)}") }
                        contentType(ContentType.Application.Json)
                        setBody(
                            json.encodeToString(
                                PlatformAddCourtesyDaysRequest.serializer(),
                                PlatformAddCourtesyDaysRequest(days = 1, reason = "Service outage"),
                            ),
                        )
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
            }
            assertEquals(0, countAdjustments(jdbcUrl, venueId))
        }

    @Test
    fun `venue owner checkout ensure is idempotent audited and scoped to own venue`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-billing-checkout")
            val config = buildConfig(jdbcUrl, genericCheckout = true)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerUserId = 6201L
            val otherOwnerUserId = 6202L
            val venueId = seedVenueWithMembership(jdbcUrl, ownerUserId, "OWNER")
            val otherVenueId = seedVenueWithMembership(jdbcUrl, otherOwnerUserId, "OWNER")
            seedSubscriptionSettings(jdbcUrl, venueId)
            seedSubscriptionSettings(jdbcUrl, otherVenueId)

            val ownerToken = issueToken(config, ownerUserId)
            val otherOwnerToken = issueToken(config, otherOwnerUserId)

            val crossVenueResponse =
                client.post("/api/venue/$venueId/subscription/checkout") {
                    headers { append(HttpHeaders.Authorization, "Bearer $otherOwnerToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, crossVenueResponse.status)
            assertApiErrorEnvelope(crossVenueResponse, ApiErrorCodes.FORBIDDEN)

            repeat(2) {
                val response =
                    client.post("/api/venue/$venueId/subscription/checkout") {
                        headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
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
            assertEquals(0, countInvoices(jdbcUrl, otherVenueId))
            assertEquals(2, countAuditActions(jdbcUrl, "BILLING_CHECKOUT_ENSURE", ownerUserId))
        }

    @Test
    fun `venue owner checkout outside lead window does not create next invoice`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-billing-lead-window")
            val config = buildConfig(jdbcUrl, genericCheckout = true)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerUserId = 6251L
            val venueId = seedVenueWithMembership(jdbcUrl, ownerUserId, "OWNER")
            seedSubscriptionSettings(jdbcUrl, venueId)
            val today = LocalDate.now()
            val paidThrough = today.plusDays(30)
            seedInvoice(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                periodStart = today.minusDays(1),
                periodEnd = paidThrough,
                status = "PAID",
            )
            val ownerToken = issueToken(config, ownerUserId)

            val response =
                client.post("/api/venue/$venueId/subscription/checkout") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val payload =
                json.decodeFromString(
                    OwnerBillingOverviewResponse.serializer(),
                    response.bodyAsText(),
                )
            assertFalse(payload.checkoutEnsureAvailable)
            assertEquals("advance_window_not_open", payload.unavailableReason)
            assertEquals(paidThrough.toString(), payload.paidThrough)
            assertEquals(paidThrough.plusDays(1).toString(), payload.nextPaymentDate)
            assertEquals(1, countInvoices(jdbcUrl, venueId))
        }

    @Test
    fun `venue owner sees courtesy adjusted paid through`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-billing-courtesy-visible")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerUserId = 6261L
            val venueId = seedVenueWithMembership(jdbcUrl, ownerUserId, "OWNER")
            seedSubscriptionSettings(jdbcUrl, venueId)
            val basePaidThrough = LocalDate.of(2026, 8, 1)
            val adjustedPaidThrough = LocalDate.of(2026, 8, 4)
            seedInvoice(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                periodStart = LocalDate.of(2026, 7, 2),
                periodEnd = basePaidThrough,
                status = "PAID",
            )
            seedCourtesyAdjustment(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                days = 3,
                previousPaidThrough = basePaidThrough,
                newPaidThrough = adjustedPaidThrough,
                actorUserId = 9999L,
            )
            val ownerToken = issueToken(config, ownerUserId)

            val response =
                client.get("/api/venue/$venueId/subscription") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val payload =
                json.decodeFromString(
                    OwnerBillingOverviewResponse.serializer(),
                    response.bodyAsText(),
                )
            assertEquals(basePaidThrough.toString(), payload.basePaidThrough)
            assertEquals(adjustedPaidThrough.toString(), payload.paidThrough)
            assertEquals(adjustedPaidThrough.plusDays(1).toString(), payload.nextPaymentDate)
            assertEquals(3, payload.courtesyDays)
            assertEquals(3, payload.lastCourtesyDays)
        }

    @Test
    fun `venue owner sees fake manual invoice but cannot mark paid`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-billing-fake-manual")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerUserId = 6301L
            val venueId = seedVenueWithMembership(jdbcUrl, ownerUserId, "OWNER")
            seedSubscriptionSettings(jdbcUrl, venueId)
            val ownerToken = issueToken(config, ownerUserId)

            val ensureResponse =
                client.post("/api/venue/$venueId/subscription/checkout") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, ensureResponse.status)
            val ensured =
                json.decodeFromString(
                    OwnerBillingOverviewResponse.serializer(),
                    ensureResponse.bodyAsText(),
                )
            assertFalse(ensured.paymentAvailable)
            assertEquals("fake_provider_manual_only", ensured.unavailableReason)
            assertEquals(null, ensured.checkoutUrl)
            val invoice = assertNotNull(ensured.payableInvoice)
            assertEquals("OPEN", invoice.status)
            assertEquals(null, invoice.checkoutUrl)
            assertEquals(1, countInvoices(jdbcUrl, venueId))

            val markPaidResponse =
                client.post("/api/platform/invoices/${invoice.id}/mark-paid") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, markPaidResponse.status)
            assertApiErrorEnvelope(markPaidResponse, ApiErrorCodes.FORBIDDEN)
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(
        jdbcUrl: String,
        genericCheckout: Boolean = false,
        platformOwnerId: Long? = null,
    ): MapApplicationConfig {
        val values =
            mutableMapOf(
                "app.env" to appEnv,
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to jdbcUrl,
                "db.user" to "sa",
                "db.password" to "",
                "venue.staffInviteSecretPepper" to "invite-pepper",
                "billing.subscription.intervalSeconds" to "0",
            )
        if (platformOwnerId != null) {
            values["platform.ownerUserId"] = platformOwnerId.toString()
        }
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

    private fun seedVenueWithMembership(
        jdbcUrl: String,
        userId: Long,
        role: String,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            mergeUser(jdbcUrl, userId)
            val venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setString(1, VenueStatus.PUBLISHED.dbValue)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert venue")
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO venue_members (venue_id, user_id, role)
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
            return venueId
        }
    }

    private fun seedInvoice(
        jdbcUrl: String,
        venueId: Long,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        status: String,
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
                    paid_at,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setDate(2, java.sql.Date.valueOf(periodStart))
                statement.setDate(3, java.sql.Date.valueOf(periodEnd))
                statement.setTimestamp(4, Timestamp.from(Instant.now().plusSeconds(86400)))
                statement.setInt(5, 12000)
                statement.setString(6, "RUB")
                statement.setString(7, "Subscription")
                statement.setString(8, "FAKE")
                statement.setString(9, "fake-invoice-$venueId-$periodStart")
                statement.setString(10, "fake://invoice/fake-invoice-$venueId-$periodStart")
                statement.setTimestamp(
                    11,
                    if (status == "PAID") Timestamp.from(Instant.now()) else null,
                )
                statement.setString(12, status)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) return rs.getLong(1)
                }
            }
        }
        error("Failed to insert invoice")
    }

    private fun seedCourtesyAdjustment(
        jdbcUrl: String,
        venueId: Long,
        days: Int,
        previousPaidThrough: LocalDate,
        newPaidThrough: LocalDate,
        actorUserId: Long,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO billing_adjustments (
                    venue_id,
                    kind,
                    days,
                    reason,
                    previous_paid_through,
                    new_paid_through,
                    actor_user_id
                )
                VALUES (?, 'COURTESY_DAYS', ?, 'Service outage', ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setInt(2, days)
                statement.setDate(3, java.sql.Date.valueOf(previousPaidThrough))
                statement.setDate(4, java.sql.Date.valueOf(newPaidThrough))
                statement.setLong(5, actorUserId)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) return rs.getLong(1)
                }
            }
        }
        error("Failed to insert billing adjustment")
    }

    private fun addMembership(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        role: String,
    ) {
        mergeUser(jdbcUrl, userId)
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_members (venue_id, user_id, role)
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
        }
    }

    private fun mergeUser(
        jdbcUrl: String,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO users (telegram_user_id, username, first_name, last_name)
                KEY (telegram_user_id)
                VALUES (?, 'user', 'Test', 'User')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
        }
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

    private fun countAdjustments(
        jdbcUrl: String,
        venueId: Long,
    ): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM billing_adjustments
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
}
