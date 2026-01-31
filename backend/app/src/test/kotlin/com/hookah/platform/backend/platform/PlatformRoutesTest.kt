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
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

    @Test
    fun `missing owner config cannot access platform me`() = testApplication {
        val jdbcUrl = buildJdbcUrl("platform-missing-owner")
        val config = buildConfig(jdbcUrl, platformOwnerId = null)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val token = issueToken(config, userId = 404L)
        val response = client.get("/api/platform/me") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
    }

    @Test
    fun `non owner cannot list platform users`() = testApplication {
        val jdbcUrl = buildJdbcUrl("platform-users-rbac")
        val config = buildConfig(jdbcUrl, platformOwnerId = 505L)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val token = issueToken(config, userId = 606L)
        val response = client.get("/api/platform/users") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
    }

    @Test
    fun `owner can list platform users`() = testApplication {
        val jdbcUrl = buildJdbcUrl("platform-users-owner")
        val ownerId = 707L
        val config = buildConfig(jdbcUrl, platformOwnerId = ownerId)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        seedUser(jdbcUrl, 9001L, "first", "User")
        seedUser(jdbcUrl, 9002L, "second", "User")
        val token = issueToken(config, userId = ownerId)
        val response = client.get("/api/platform/users?limit=10") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString(PlatformUserListResponse.serializer(), response.bodyAsText())
        assertTrue(payload.users.isNotEmpty())
        assertEquals(9002L, payload.users.first().userId)
    }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String, platformOwnerId: Long?): MapApplicationConfig {
        val values = mutableListOf(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
            "venue.staffInviteSecretPepper" to "invite-pepper"
        )

        if (platformOwnerId != null) {
            values.add("platform.ownerUserId" to platformOwnerId.toString())
        }

        return MapApplicationConfig(*values.toTypedArray())
    }

    private fun issueToken(config: MapApplicationConfig, userId: Long): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(userId).token
    }

    private fun seedUser(jdbcUrl: String, userId: Long, firstName: String, lastName: String) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    INSERT INTO users (telegram_user_id, username, first_name, last_name, created_at, updated_at)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, "user$userId")
                statement.setString(3, firstName)
                statement.setString(4, lastName)
                statement.executeUpdate()
            }
        }
    }

    @Serializable
    private data class PlatformMeResponse(
        val ok: Boolean,
        val ownerUserId: Long
    )

    @Serializable
    private data class PlatformUserListResponse(
        val users: List<PlatformUserDto>
    )

    @Serializable
    private data class PlatformUserDto(
        val userId: Long,
        val username: String?,
        val displayName: String,
        val lastSeenAt: String
    )
}
