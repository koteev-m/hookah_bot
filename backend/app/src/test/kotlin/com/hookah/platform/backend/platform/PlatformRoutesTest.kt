package com.hookah.platform.backend.platform

import com.hookah.platform.backend.api.ApiErrorCodes
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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class PlatformRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `non owner cannot access platform me`() = testApplication {
        val jdbcUrl = buildJdbcUrl("platform-non-owner")
        val config = buildConfig(jdbcUrl, platformOwnerId = 101L)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val token = issueToken(config, userId = 202L)
        val response = client.get("/api/platform/me") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
    }

    @Test
    fun `owner can access platform me`() = testApplication {
        val jdbcUrl = buildJdbcUrl("platform-owner")
        val ownerId = 303L
        val config = buildConfig(jdbcUrl, platformOwnerId = ownerId)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val token = issueToken(config, userId = ownerId)
        val response = client.get("/api/platform/me") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString(PlatformMeResponse.serializer(), response.bodyAsText())
        assertEquals(true, payload.ok)
        assertEquals(ownerId, payload.ownerUserId)
    }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String, platformOwnerId: Long): MapApplicationConfig {
        return MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
            "platform.ownerUserId" to platformOwnerId.toString(),
            "venue.staffInviteSecretPepper" to "invite-pepper"
        )
    }

    private fun issueToken(config: MapApplicationConfig, userId: Long): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(userId).token
    }

    @Serializable
    private data class PlatformMeResponse(
        val ok: Boolean,
        val ownerUserId: Long
    )
}
