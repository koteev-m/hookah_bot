package com.hookah.platform.backend.miniapp.session

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.module
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

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
        assertApiErrorEnvelope(response.bodyAsText(), ApiErrorCodes.UNAUTHORIZED)
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
        val issuedToken = sessionTokenService.issueToken(TELEGRAM_USER_ID, now = issuedAt)

        val response = client.get("/api/guest/_ping") {
            headers {
                append(HttpHeaders.Authorization, "Bearer ${issuedToken.token}")
            }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(true, payload["ok"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `should return envelope for unknown api route`() = testApplication {
        environment {
            config = buildConfig()
        }

        application { module() }

        val response = client.get("/api/unknown")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertApiErrorEnvelope(response.bodyAsText(), ApiErrorCodes.NOT_FOUND)
    }

    @Test
    fun `should return envelope for api root`() = testApplication {
        environment {
            config = buildConfig()
        }

        application { module() }

        val response = client.get("/api")

        val expectedCode = when (response.status) {
            HttpStatusCode.NotFound -> ApiErrorCodes.NOT_FOUND
            HttpStatusCode.MethodNotAllowed -> ApiErrorCodes.INVALID_INPUT
            else -> fail("Unexpected status ${response.status}")
        }
        assertApiErrorEnvelope(response.bodyAsText(), expectedCode)
    }

    @Test
    fun `should return envelope for method not allowed on api route`() = testApplication {
        val config = buildConfig()
        environment {
            this.config = config
        }

        application { module() }

        val sessionTokenService = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        val issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val issuedToken = sessionTokenService.issueToken(TELEGRAM_USER_ID, now = issuedAt)

        val response = client.post("/api/guest/_ping") {
            headers {
                append(HttpHeaders.Authorization, "Bearer ${issuedToken.token}")
            }
        }

        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        assertApiErrorEnvelope(response.bodyAsText(), ApiErrorCodes.INVALID_INPUT)
    }

    private fun assertApiErrorEnvelope(body: String, expectedCode: String) {
        val payload = Json.parseToJsonElement(body).jsonObject
        val error = payload["error"]?.jsonObject
        assertNotNull(error, "error envelope missing")
        assertEquals(expectedCode, error["code"]?.jsonPrimitive?.content)
        val requestId = payload["requestId"]?.jsonPrimitive?.content
        assertTrue(!requestId.isNullOrBlank(), "requestId must be present in API error envelope")
    }

    private companion object {
        const val TELEGRAM_USER_ID: Long = 1234L
    }
}
