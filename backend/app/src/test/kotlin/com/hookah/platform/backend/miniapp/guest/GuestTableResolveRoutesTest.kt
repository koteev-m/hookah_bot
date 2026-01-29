package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.TableResolveResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import kotlinx.serialization.json.Json

class GuestTableResolveRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `missing authorization returns unauthorized`() = testApplication {
        val config = MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to ""
        )

        environment { this.config = config }
        application { module() }

        val response = client.get("/api/guest/table/resolve?tableToken=any-token")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.UNAUTHORIZED)
    }

    @Test
    fun `invalid token format returns invalid input without resolve`() = testApplication {
        var resolveCalls = 0
        val config = MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to ""
        )

        environment { this.config = config }
        application {
            module(
                ModuleOverrides(
                    tableTokenResolver = {
                        resolveCalls += 1
                        fail("tableTokenResolver must not be called for invalid tokens")
                    }
                )
            )
        }

        val token = issueToken(config)
        val invalidTokens = listOf(
            "   bad token  ",
            "русский",
            "x".repeat(129)
        )

        invalidTokens.forEach { invalid ->
            val encoded = invalid.encodeURLParameter()
            val response = client.get("/api/guest/table/resolve?tableToken=$encoded") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
        }

        assertEquals(0, resolveCalls)
    }

    @Test
    fun `missing table token returns invalid input without resolve`() = testApplication {
        var resolveCalls = 0
        val config = MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to ""
        )

        environment { this.config = config }
        application {
            module(
                ModuleOverrides(
                    tableTokenResolver = {
                        resolveCalls += 1
                        fail("tableTokenResolver must not be called for missing tokens")
                    }
                )
            )
        }

        val token = issueToken(config)
        val response = client.get("/api/guest/table/resolve") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
        assertEquals(0, resolveCalls)
    }

    @Test
    fun `blank table token returns invalid input without resolve`() = testApplication {
        var resolveCalls = 0
        val config = MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to ""
        )

        environment { this.config = config }
        application {
            module(
                ModuleOverrides(
                    tableTokenResolver = {
                        resolveCalls += 1
                        fail("tableTokenResolver must not be called for blank tokens")
                    }
                )
            )
        }

        val token = issueToken(config)
        val encoded = "   ".encodeURLParameter()
        val response = client.get("/api/guest/table/resolve?tableToken=$encoded") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
        assertEquals(0, resolveCalls)
    }

    @Test
    fun `unknown token returns not found`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-table-unknown")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")
        val token = issueToken(config)

        val response = client.get("/api/guest/table/resolve?tableToken=missing-token") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
    }

    @Test
    fun `known token for suspended venue returns not found`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-table-suspended")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val tokenValue = "suspended-token"
        val venueId = seedVenue(jdbcUrl, VenueStatus.SUSPENDED.dbValue)
        val tableId = seedTable(jdbcUrl, venueId, 7)
        seedTableToken(jdbcUrl, tableId, tokenValue)
        seedSubscription(jdbcUrl, venueId, "active")

        val token = issueToken(config)

        val response = client.get("/api/guest/table/resolve?tableToken=$tokenValue") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
    }

    @Test
    fun `known token for published venue returns available`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-table-trial")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val tokenValue = "published-token"
        val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
        val tableId = seedTable(jdbcUrl, venueId, 3)
        seedTableToken(jdbcUrl, tableId, tokenValue)

        val token = issueToken(config)

        val response = client.get("/api/guest/table/resolve?tableToken=$tokenValue") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString(TableResolveResponse.serializer(), response.bodyAsText())
        assertEquals(venueId, payload.venueId)
        assertEquals(tableId, payload.tableId)
        assertEquals("3", payload.tableNumber)
        assertEquals(VenueStatus.PUBLISHED.dbValue, payload.venueStatus)
        assertEquals("trial", payload.subscriptionStatus)
        assertEquals(true, payload.available)
        assertNull(payload.unavailableReason)
    }

    @Test
    fun `known token for past due subscription returns unavailable`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-table-past-due")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val tokenValue = "past-due-token"
        val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
        val tableId = seedTable(jdbcUrl, venueId, 12)
        seedTableToken(jdbcUrl, tableId, tokenValue)
        seedSubscription(jdbcUrl, venueId, "PAST_DUE")

        val token = issueToken(config)

        val response = client.get("/api/guest/table/resolve?tableToken=$tokenValue") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString(TableResolveResponse.serializer(), response.bodyAsText())
        assertEquals(venueId, payload.venueId)
        assertEquals(tableId, payload.tableId)
        assertEquals("12", payload.tableNumber)
        assertEquals(VenueStatus.PUBLISHED.dbValue, payload.venueStatus)
        assertEquals("past_due", payload.subscriptionStatus)
        assertEquals(false, payload.available)
        assertEquals("SUBSCRIPTION_BLOCKED", payload.unavailableReason)
    }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String): MapApplicationConfig {
        return MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to ""
        )
    }

    private fun issueToken(config: MapApplicationConfig): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(TELEGRAM_USER_ID).token
    }

    private fun seedVenue(jdbcUrl: String, status: String): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    INSERT INTO venues (name, city, address, status)
                    VALUES (?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { statement ->
                statement.setString(1, "Venue")
                statement.setString(2, "City")
                statement.setString(3, "Address")
                statement.setString(4, status)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert venue")
    }

    private fun seedTable(jdbcUrl: String, venueId: Long, tableNumber: Int): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    INSERT INTO venue_tables (venue_id, table_number)
                    VALUES (?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setInt(2, tableNumber)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert table")
    }

    private fun seedTableToken(jdbcUrl: String, tableId: Long, token: String) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    INSERT INTO table_tokens (token, table_id, is_active)
                    VALUES (?, ?, true)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, token)
                statement.setLong(2, tableId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedSubscription(jdbcUrl: String, venueId: Long, status: String) {
        val now = Instant.now()
        val trialEnd = now.plus(14, ChronoUnit.DAYS)
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, status.uppercase())
                statement.setTimestamp(3, Timestamp.from(trialEnd))
                statement.setTimestamp(4, Timestamp.from(now))
                statement.setTimestamp(5, Timestamp.from(now))
                statement.executeUpdate()
            }
        }
    }

    private companion object {
        const val TELEGRAM_USER_ID: Long = 456L
    }
}
