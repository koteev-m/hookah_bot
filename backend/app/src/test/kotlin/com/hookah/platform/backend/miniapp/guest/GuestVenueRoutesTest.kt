package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.CatalogResponse
import com.hookah.platform.backend.miniapp.guest.api.VenueResponse
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
        assertEquals("active_published", publishedPayload.venue.status)

        val hiddenResponse = client.get("/api/guest/venue/${venues.hiddenId}") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, hiddenResponse.status)
        val hiddenPayload = json.decodeFromString(VenueResponse.serializer(), hiddenResponse.bodyAsText())
        assertEquals("active_hidden", hiddenPayload.venue.status)

        val suspendedResponse = client.get("/api/guest/venue/${venues.suspendedId}") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.Locked, suspendedResponse.status)
        assertApiErrorEnvelope(suspendedResponse, ApiErrorCodes.SERVICE_SUSPENDED)
    }

    @Test
    fun `venue by id hides suspended when configured`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-hide")
        val config = buildConfig(jdbcUrl, suspendedMode = "hide")

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venues = seedVenues(jdbcUrl)
        val token = issueToken(config)

        val response = client.get("/api/guest/venue/${venues.suspendedId}") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
    }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String, suspendedMode: String? = null): MapApplicationConfig {
        val entries = mutableListOf(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to ""
        )
        if (suspendedMode != null) {
            entries.add("api.guest.suspendedMode" to suspendedMode)
        }
        return MapApplicationConfig(*entries.toTypedArray())
    }

    private fun issueToken(config: MapApplicationConfig): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(TELEGRAM_USER_ID).token
    }

    private fun seedVenues(jdbcUrl: String): SeededVenues {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val publishedId = insertVenue(connection, "Published", "City", "Address", "active_published")
            val hiddenId = insertVenue(connection, "Hidden", "City", "Address", "active_hidden")
            val suspendedId = insertVenue(connection, "Suspended", "City", "Address", "suspended_by_platform")
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

    private data class SeededVenues(
        val publishedId: Long,
        val hiddenId: Long,
        val suspendedId: Long
    )

    private companion object {
        const val TELEGRAM_USER_ID: Long = 777L
    }
}
