package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ApiErrorCodes
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
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class VenueRbacRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `venue me without auth returns unauthorized`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "app.env" to appEnv,
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to ""
            )
        }

        application { module() }

        val response = client.get("/api/venue/me")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.UNAUTHORIZED)
    }

    @Test
    fun `owner can generate staff chat link code`() = testApplication {
        val jdbcUrl = buildJdbcUrl("venue-owner")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
        val token = issueToken(config)

        val response = client.post("/api/venue/$venueId/staff-chat/link-code") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString(StaffChatLinkCodeResponse.serializer(), response.bodyAsText())
        assertTrue(payload.code.isNotBlank())
        assertTrue(payload.ttlSeconds > 0)
    }

    @Test
    fun `manager cannot generate staff chat link code`() = testApplication {
        val jdbcUrl = buildJdbcUrl("venue-manager")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
        val token = issueToken(config)

        val response = client.post("/api/venue/$venueId/staff-chat/link-code") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
    }

    @Test
    fun `staff cannot generate staff chat link code`() = testApplication {
        val jdbcUrl = buildJdbcUrl("venue-staff")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
        val token = issueToken(config)

        val response = client.post("/api/venue/$venueId/staff-chat/link-code") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
    }

    @Test
    fun `venue me returns permissions for each role`() = testApplication {
        val jdbcUrl = buildJdbcUrl("venue-me")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val ownerVenueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
        val managerVenueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
        val staffVenueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
        val token = issueToken(config)

        val response = client.get("/api/venue/me") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString(VenueMeResponse.serializer(), response.bodyAsText())
        assertEquals(TELEGRAM_USER_ID, payload.userId)

        val venuesById = payload.venues.associateBy { it.venueId }
        assertEquals(setOf(ownerVenueId, managerVenueId, staffVenueId), venuesById.keys)

        assertEquals("OWNER", venuesById.getValue(ownerVenueId).role)
        assertEquals(
            setOf("STAFF_CHAT_LINK", "VENUE_SETTINGS", "ORDER_STATUS_UPDATE", "ORDER_QUEUE_VIEW"),
            venuesById.getValue(ownerVenueId).permissions.toSet()
        )

        assertEquals("MANAGER", venuesById.getValue(managerVenueId).role)
        assertEquals(
            setOf("ORDER_STATUS_UPDATE", "ORDER_QUEUE_VIEW"),
            venuesById.getValue(managerVenueId).permissions.toSet()
        )

        assertEquals("STAFF", venuesById.getValue(staffVenueId).role)
        assertEquals(
            setOf("ORDER_QUEUE_VIEW", "ORDER_STATUS_UPDATE"),
            venuesById.getValue(staffVenueId).permissions.toSet()
        )
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

    private fun seedVenueMembership(jdbcUrl: String, userId: Long, role: String): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    MERGE INTO users (telegram_user_id, username, first_name, last_name)
                    KEY (telegram_user_id)
                    VALUES (?, 'user', 'Test', 'User')
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
            val venueId = connection.prepareStatement(
                """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', 'active_published')
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { statement ->
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        rs.getLong(1)
                    } else {
                        error("Failed to insert venue")
                    }
                }
            }
            connection.prepareStatement(
                """
                    INSERT INTO venue_members (venue_id, user_id, role)
                    VALUES (?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
            return venueId
        }
    }

    @Serializable
    private data class StaffChatLinkCodeResponse(
        val code: String,
        val expiresAt: String,
        val ttlSeconds: Long
    )

    @Serializable
    private data class VenueMeResponse(
        val userId: Long,
        val venues: List<VenueAccessDto>
    )

    @Serializable
    private data class VenueAccessDto(
        val venueId: Long,
        val role: String,
        val permissions: List<String>
    )

    private companion object {
        const val TELEGRAM_USER_ID: Long = 909L
    }
}
