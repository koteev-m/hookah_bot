package com.hookah.platform.backend.miniapp.session

import com.hookah.platform.backend.api.ApiErrorCodes
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.fail

class SessionAuthTest {
    private val appEnv = "test"

    private fun buildConfig(): MapApplicationConfig =
        MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "api.session.ttlSeconds" to "7200",
            "api.session.issuer" to "test-issuer",
            "api.session.audience" to "test-audience",
        )

    @Test
    fun `maintenance rechecks every old jwt and returns generic 503 for denied or unauthenticated callers`() {
        val tokenConfig = buildConfig()
        val tokenService = SessionTokenService(SessionTokenConfig.from(tokenConfig, appEnv))
        val issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val allowedToken = tokenService.issueToken(TELEGRAM_USER_ID, now = issuedAt).token
        val deniedToken = tokenService.issueToken(OTHER_TELEGRAM_USER_ID, now = issuedAt).token

        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.env" to appEnv,
                        "api.session.jwtSecret" to "test-secret",
                        "api.session.ttlSeconds" to "7200",
                        "api.session.issuer" to "test-issuer",
                        "api.session.audience" to "test-audience",
                        "telegram.trafficPolicy" to "PRODUCT",
                        "staging.maintenance.mode" to "V126_SMOKE",
                        "staging.maintenance.allowedUserIds" to TELEGRAM_USER_ID.toString(),
                        "staging.maintenance.allowedChatIds" to TELEGRAM_USER_ID.toString(),
                    )
            }
            application { module() }

            val allowedResponse =
                client.get("/api/guest/_ping") {
                    headers { append(HttpHeaders.Authorization, "Bearer $allowedToken") }
                }
            assertEquals(HttpStatusCode.OK, allowedResponse.status)

            listOf(
                "/api/guest/_ping",
                "/api/venue/1/public-card",
                "/api/platform/me",
            ).forEach { path ->
                val deniedResponse =
                    client.get(path) {
                        headers { append(HttpHeaders.Authorization, "Bearer $deniedToken") }
                    }
                assertEquals(HttpStatusCode.ServiceUnavailable, deniedResponse.status, path)
                assertApiErrorEnvelope(deniedResponse, ApiErrorCodes.SERVICE_UNAVAILABLE)
            }

            val unauthenticated = client.get("/api/guest/_ping")
            assertEquals(HttpStatusCode.ServiceUnavailable, unauthenticated.status)
            assertApiErrorEnvelope(unauthenticated, ApiErrorCodes.SERVICE_UNAVAILABLE)

            val publicMedia = client.get("/api/guest/venue/1/info-sections/1/media/1")
            assertEquals(HttpStatusCode.ServiceUnavailable, publicMedia.status)
            assertApiErrorEnvelope(publicMedia, ApiErrorCodes.SERVICE_UNAVAILABLE)

            val billingWebhook = client.post("/api/billing/webhook")
            assertEquals(HttpStatusCode.ServiceUnavailable, billingWebhook.status)
            assertApiErrorEnvelope(billingWebhook, ApiErrorCodes.SERVICE_UNAVAILABLE)
        }
    }

    @Test
    fun `should reject request without Authorization header`() =
        testApplication {
            environment {
                config = buildConfig()
            }

            application { module() }

            val response = client.get("/api/guest/_ping")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.UNAUTHORIZED)
        }

    @Test
    fun `should allow request with valid bearer token`() =
        testApplication {
            val config = buildConfig()
            environment {
                this.config = config
            }

            application { module() }

            val sessionTokenService = SessionTokenService(SessionTokenConfig.from(config, appEnv))
            val issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
            val issuedToken = sessionTokenService.issueToken(TELEGRAM_USER_ID, now = issuedAt)

            val response =
                client.get("/api/guest/_ping") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer ${issuedToken.token}")
                    }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(true, payload["ok"]?.jsonPrimitive?.booleanOrNull)
        }

    @Test
    fun `valid token issued before allowlist removal is forbidden on every protected request`() {
        val beforeRemovalConfig =
            MapApplicationConfig(
                "app.env" to appEnv,
                "api.session.jwtSecret" to "test-secret",
                "api.session.ttlSeconds" to "7200",
                "api.session.issuer" to "test-issuer",
                "api.session.audience" to "test-audience",
                "telegram.trafficPolicy" to "ALLOWLIST",
                "telegram.allowedUserIds" to TELEGRAM_USER_ID.toString(),
                "telegram.allowedChatIds" to TELEGRAM_USER_ID.toString(),
            )
        val issuedToken =
            SessionTokenService(SessionTokenConfig.from(beforeRemovalConfig, appEnv))
                .issueToken(TELEGRAM_USER_ID, now = Instant.now().truncatedTo(ChronoUnit.SECONDS))

        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.env" to appEnv,
                        "api.session.jwtSecret" to "test-secret",
                        "api.session.ttlSeconds" to "7200",
                        "api.session.issuer" to "test-issuer",
                        "api.session.audience" to "test-audience",
                        "telegram.trafficPolicy" to "ALLOWLIST",
                        "telegram.allowedUserIds" to OTHER_TELEGRAM_USER_ID.toString(),
                        "telegram.allowedChatIds" to OTHER_TELEGRAM_USER_ID.toString(),
                    )
            }

            application { module() }

            val response =
                client.get("/api/guest/_ping") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer ${issuedToken.token}")
                    }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
        }
    }

    @Test
    fun `should return envelope for unknown api route`() =
        testApplication {
            environment {
                config = buildConfig()
            }

            application { module() }

            val response = client.get("/api/unknown")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
        }

    @Test
    fun `should return envelope for api root`() =
        testApplication {
            environment {
                config = buildConfig()
            }

            application { module() }

            val response = client.get("/api")

            val expectedCode =
                when (response.status) {
                    HttpStatusCode.NotFound -> ApiErrorCodes.NOT_FOUND
                    HttpStatusCode.MethodNotAllowed -> ApiErrorCodes.INVALID_INPUT
                    else -> fail("Unexpected status ${response.status}")
                }
            assertApiErrorEnvelope(response, expectedCode)
        }

    @Test
    fun `should return envelope for method not allowed on api route`() =
        testApplication {
            val config = buildConfig()
            environment {
                this.config = config
            }

            application { module() }

            val sessionTokenService = SessionTokenService(SessionTokenConfig.from(config, appEnv))
            val issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
            val issuedToken = sessionTokenService.issueToken(TELEGRAM_USER_ID, now = issuedAt)

            val response =
                client.post("/api/guest/_ping") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer ${issuedToken.token}")
                    }
                }

            assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
        }

    private companion object {
        const val TELEGRAM_USER_ID: Long = 1234L
        const val OTHER_TELEGRAM_USER_ID: Long = 5678L
    }
}
