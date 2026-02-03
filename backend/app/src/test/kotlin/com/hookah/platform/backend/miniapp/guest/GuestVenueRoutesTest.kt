package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.CatalogResponse
import com.hookah.platform.backend.miniapp.guest.api.VenueResponse
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class GuestVenueRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `catalog without auth returns unauthorized`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "app.env" to appEnv,
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to ""
            )
        }

        application { module() }

        val response = client.get("/api/guest/catalog")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.UNAUTHORIZED)
    }

    @Test
    fun `catalog returns only published venues`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-catalog")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venues = seedVenues(jdbcUrl)
        val token = issueToken(config)

        val response = client.get("/api/guest/catalog") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString(CatalogResponse.serializer(), response.bodyAsText())
        assertEquals(1, payload.venues.size)
        assertEquals(venues.publishedId, payload.venues.first().id)
    }

    @Test
    fun `catalog hides blocked subscriptions`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-catalog-blocked")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val publishedId = DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            insertVenue(connection, "Open", "City", "Address", VenueStatus.PUBLISHED.dbValue)
        }
        val blockedId = DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            insertVenue(connection, "Blocked", "City", "Address", VenueStatus.PUBLISHED.dbValue)
        }
        seedSubscription(jdbcUrl, publishedId, "ACTIVE")
        seedSubscription(jdbcUrl, blockedId, "SUSPENDED_BY_PLATFORM")
        val token = issueToken(config)

        val response = client.get("/api/guest/catalog") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString(CatalogResponse.serializer(), response.bodyAsText())
        assertEquals(listOf(publishedId), payload.venues.map { it.id })
    }

    @Test
    fun `venue by id respects visibility rules`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-venue")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venues = seedVenues(jdbcUrl)
        val token = issueToken(config)

        val publishedResponse = client.get("/api/guest/venue/${venues.publishedId}") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, publishedResponse.status)
        val publishedPayload = json.decodeFromString(VenueResponse.serializer(), publishedResponse.bodyAsText())
        assertEquals(VenueStatus.PUBLISHED.dbValue, publishedPayload.venue.status)

        val hiddenResponse = client.get("/api/guest/venue/${venues.hiddenId}") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.NotFound, hiddenResponse.status)
        assertApiErrorEnvelope(hiddenResponse, ApiErrorCodes.NOT_FOUND)

        val suspendedResponse = client.get("/api/guest/venue/${venues.suspendedId}") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.NotFound, suspendedResponse.status)
        assertApiErrorEnvelope(suspendedResponse, ApiErrorCodes.NOT_FOUND)
    }

    @Test
    fun `venue by id with blocked subscription returns not found`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-venue-blocked")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            insertVenue(connection, "Blocked", "City", "Address", VenueStatus.PUBLISHED.dbValue)
        }
        seedSubscription(jdbcUrl, venueId, "PAST_DUE")
        val token = issueToken(config)

        val response = client.get("/api/guest/venue/$venueId") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
    }

    @Test
    fun `venue by id with unknown id returns not found`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-unknown")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venues = seedVenues(jdbcUrl)
        val token = issueToken(config)
        val missingId = maxOf(venues.publishedId, venues.hiddenId, venues.suspendedId) + 100

        val response = client.get("/api/guest/venue/$missingId") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
    }

    @Test
    fun `venue by id without auth returns unauthorized`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "app.env" to appEnv,
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to ""
            )
        }

        application { module() }

        val response = client.get("/api/guest/venue/1")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.UNAUTHORIZED)
    }

    @Test
    fun `venue by id with invalid id returns invalid input`() = testApplication {
        val config = MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to ""
        )

        environment { this.config = config }
        application { module() }

        val token = issueToken(config)

        val response = client.get("/api/guest/venue/not-a-number") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
    }

    @Test
    fun `catalog with db disabled returns database unavailable`() = testApplication {
        val config = MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to ""
        )

        environment { this.config = config }
        application { module() }

        val token = issueToken(config)

        val response = client.get("/api/guest/catalog") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
    }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String): MapApplicationConfig {
        val entries = mutableListOf(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to ""
        )
        return MapApplicationConfig(*entries.toTypedArray())
    }

    private fun issueToken(config: MapApplicationConfig): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(TELEGRAM_USER_ID).token
    }

    private fun seedVenues(jdbcUrl: String): SeededVenues {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val publishedId = insertVenue(connection, "Published", "City", "Address", VenueStatus.PUBLISHED.dbValue)
            val hiddenId = insertVenue(connection, "Hidden", "City", "Address", VenueStatus.HIDDEN.dbValue)
            val suspendedId = insertVenue(connection, "Suspended", "City", "Address", VenueStatus.SUSPENDED.dbValue)
            return SeededVenues(publishedId, hiddenId, suspendedId)
        }
    }

    private fun insertVenue(
        connection: java.sql.Connection,
        name: String,
        city: String,
        address: String,
        status: String
    ): Long {
        connection.prepareStatement(
            """
                INSERT INTO venues (name, city, address, status)
                VALUES (?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS
        ).use { statement ->
            statement.setString(1, name)
            statement.setString(2, city)
            statement.setString(3, address)
            statement.setString(4, status)
            statement.executeUpdate()
            statement.generatedKeys.use { rs ->
                if (rs.next()) {
                    return rs.getLong(1)
                }
            }
        }
        error("Failed to insert venue")
    }

    private fun seedSubscription(jdbcUrl: String, venueId: Long, status: String) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, status)
                statement.executeUpdate()
            }
        }
    }

    private data class SeededVenues(
        val publishedId: Long,
        val hiddenId: Long,
        val suspendedId: Long
    )

    private companion object {
        const val TELEGRAM_USER_ID: Long = 777L
    }
}
