package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.billing.OwnerBillingOverviewResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.sql.DriverManager
import java.sql.Statement
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
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
            assertEquals(0, countInvoices(jdbcUrl, venueId))
            assertEquals(0, countSubscriptionRows(jdbcUrl, venueId))

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

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(
        jdbcUrl: String,
        genericCheckout: Boolean = false,
    ): MapApplicationConfig {
        val values =
            mutableMapOf(
                "app.env" to appEnv,
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to jdbcUrl,
                "db.user" to "sa",
                "db.password" to "",
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
