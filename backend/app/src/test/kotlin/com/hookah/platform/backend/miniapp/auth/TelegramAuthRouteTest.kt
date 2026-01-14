package com.hookah.platform.backend.miniapp.auth

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.api.TelegramAuthRequest
import com.hookah.platform.backend.miniapp.api.TelegramAuthResponse
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.test.Test
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramAuthRouteTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val botToken = "test-bot-token"

    @Test
    fun `valid initData returns token`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "app.env" to "test",
                "api.session.jwtSecret" to "test-secret",
                "api.session.issuer" to "test-issuer",
                "api.session.audience" to "test-audience",
                "db.jdbcUrl" to "jdbc:h2:mem:miniapp-auth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "db.user" to "sa",
                "db.password" to "",
                "telegram.token" to botToken
            )
        }
        application { module() }

        val now = Instant.now().epochSecond
        val userJson = """{"id":12345,"username":"john","first_name":"John","last_name":"Doe"}"""
        val initData = generateValidInitData(botToken, userJson, now)

        val response = client.post("/api/auth/telegram") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(TelegramAuthRequest(initData)))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString<TelegramAuthResponse>(response.bodyAsText())
        assertTrue(payload.token.isNotBlank())
        assertTrue(payload.expiresAtEpochSeconds > now)
        assertEquals(12345, payload.user.telegramUserId)
        assertEquals("john", payload.user.username)
        assertEquals("John", payload.user.firstName)
        assertEquals("Doe", payload.user.lastName)
    }

    @Test
    fun `invalid hash returns unauthorized`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "app.env" to "test",
                "api.session.jwtSecret" to "test-secret",
                "api.session.issuer" to "test-issuer",
                "api.session.audience" to "test-audience",
                "db.jdbcUrl" to "",
                "telegram.token" to botToken
            )
        }
        application { module() }

        val now = Instant.now().epochSecond
        val userJson = """{"id":12345,"username":"john"}"""
        val initData = generateValidInitData(botToken, userJson, now)
        val tampered = initData.replace(Regex("hash=[^&]+"), "hash=deadbeef")

        val response = client.post("/api/auth/telegram") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(TelegramAuthRequest(tampered)))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.INITDATA_INVALID)
    }

    @Test
    fun `missing bot token returns config error`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "app.env" to "test",
                "api.session.jwtSecret" to "test-secret",
                "api.session.issuer" to "test-issuer",
                "api.session.audience" to "test-audience",
                "db.jdbcUrl" to ""
            )
        }
        application { module() }

        val now = Instant.now().epochSecond
        val userJson = """{"id":12345,"username":"john"}"""
        val initData = generateValidInitData(botToken, userJson, now)

        val response = client.post("/api/auth/telegram") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(TelegramAuthRequest(initData)))
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.CONFIG_ERROR)
    }

    @Test
    fun `invalid json returns invalid input`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "app.env" to "test",
                "api.session.jwtSecret" to "test-secret"
            )
        }
        application { module() }

        val response = client.post("/api/auth/telegram") {
            contentType(ContentType.Application.Json)
            setBody("{")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
    }

    @Test
    fun `missing initData returns invalid input`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "app.env" to "test",
                "api.session.jwtSecret" to "test-secret"
            )
        }
        application { module() }

        val response = client.post("/api/auth/telegram") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
    }

    @Test
    fun `db disabled returns database unavailable`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "app.env" to "test",
                "api.session.jwtSecret" to "test-secret",
                "telegram.token" to botToken
            )
        }
        application { module() }

        val now = Instant.now().epochSecond
        val userJson = """{"id":12345,"username":"john","first_name":"John","last_name":"Doe"}"""
        val initData = generateValidInitData(botToken, userJson, now)

        val response = client.post("/api/auth/telegram") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(TelegramAuthRequest(initData)))
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
    }

    private fun generateValidInitData(
        botToken: String,
        userJson: String,
        authDate: Long,
        extraFields: Map<String, String> = emptyMap()
    ): String {
        val params = LinkedHashMap<String, String>()
        params["auth_date"] = authDate.toString()
        params["user"] = userJson
        params.putAll(extraFields)

        val dataCheckString = buildDataCheckString(params)
        val hash = calculateTelegramInitDataHash(botToken, dataCheckString)

        val finalParams = LinkedHashMap(params)
        finalParams["hash"] = hash

        return finalParams.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
    }

}
