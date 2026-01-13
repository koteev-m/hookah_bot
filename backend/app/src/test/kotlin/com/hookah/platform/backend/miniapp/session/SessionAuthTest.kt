package com.hookah.platform.backend.miniapp.session

import com.hookah.platform.backend.module
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals

class SessionAuthTest {
    private val appEnv = "test"

    private fun buildConfig(): MapApplicationConfig = MapApplicationConfig(
        "app.env" to appEnv,
        "api.session.jwtSecret" to "test-secret",
        "api.session.ttlSeconds" to "7200",
        "api.session.issuer" to "test-issuer",
        "api.session.audience" to "test-audience"
    )

    @Test
    fun `should reject request without Authorization header`() = testApplication {
        environment {
            config = buildConfig()
        }

        application { module() }

        val response = client.get("/api/guest/_ping")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `should allow request with valid bearer token`() = testApplication {
        val config = buildConfig()
        environment {
            this.config = config
        }

        application { module() }

        val sessionTokenService = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        val issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val issuedToken = sessionTokenService.issueToken(telegramUserId, now = issuedAt)

        val response = client.get("/api/guest/_ping") {
            headers {
                append(HttpHeaders.Authorization, "Bearer ${issuedToken.token}")
            }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"ok":true}""", response.bodyAsText())
    }

    private companion object {
        const val telegramUserId: Long = 1234L
    }
}
