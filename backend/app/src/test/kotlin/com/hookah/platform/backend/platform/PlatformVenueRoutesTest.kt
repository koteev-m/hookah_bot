package com.hookah.platform.backend.platform

import com.hookah.platform.backend.api.ApiErrorCodes
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
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class PlatformVenueRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `non owner cannot create or change status`() = testApplication {
        val jdbcUrl = buildJdbcUrl("platform-venues-rbac")
        val ownerId = 1001L
        val config = buildConfig(jdbcUrl, ownerId)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenue(jdbcUrl)
        val token = issueToken(config, userId = 2002L)

        val createResponse = client.post("/api/platform/venues") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Forbidden Venue"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, createResponse.status)
        assertApiErrorEnvelope(createResponse, ApiErrorCodes.FORBIDDEN)

        val statusResponse = client.post("/api/platform/venues/$venueId/status") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            contentType(ContentType.Application.Json)
            setBody("""{"action":"publish"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, statusResponse.status)
        assertApiErrorEnvelope(statusResponse, ApiErrorCodes.FORBIDDEN)
    }

    @Test
    fun `publish requires owner membership`() = testApplication {
        val jdbcUrl = buildJdbcUrl("platform-venues-owner-check")
        val ownerId = 3003L
        val config = buildConfig(jdbcUrl, ownerId)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val token = issueToken(config, userId = ownerId)
        val createResponse = client.post("/api/platform/venues") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Draft Venue"}""")
        }

        assertEquals(HttpStatusCode.OK, createResponse.status)
        val created = json.decodeFromString(PlatformVenueResponse.serializer(), createResponse.bodyAsText())

        val publishResponse = client.post("/api/platform/venues/${created.venue.id}/status") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            contentType(ContentType.Application.Json)
            setBody("""{"action":"publish"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, publishResponse.status)
        assertApiErrorEnvelope(publishResponse, ApiErrorCodes.INVALID_INPUT)
    }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String, ownerId: Long): MapApplicationConfig {
        return MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
            "platform.ownerUserId" to ownerId.toString(),
            "venue.staffInviteSecretPepper" to "invite-pepper"
        )
    }

    private fun issueToken(config: MapApplicationConfig, userId: Long): String {
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
                Statement.RETURN_GENERATED_KEYS
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

    @Serializable
    private data class PlatformVenueResponse(
        val venue: PlatformVenueDetailDto
    )
}
