package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.configureTelegramCommandMenuSafely
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class TelegramApplicationMenuTest {
    @Test
    fun `global button opens authenticated home directly and commands remain secondary`() =
        runBlocking {
            val requests = mutableListOf<Pair<String, JsonObject>>()
            val api = client(requests, "https://example.com/miniapp/?mode=platform&lang=ru#/orders")
            try {
                configureTelegramCommandMenuSafely(api)
                val button = requests.single { it.first == "setChatMenuButton" }.second
                assertFalse(button.containsKey("chat_id"))
                val menu = button.getValue("menu_button").jsonObject
                assertEquals("web_app", menu.getValue("type").jsonPrimitive.content)
                assertEquals("📱 Приложение", menu.getValue("text").jsonPrimitive.content)
                assertEquals(
                    "https://example.com/miniapp/?lang=ru&mode=home",
                    menu.getValue("web_app").jsonObject.getValue("url").jsonPrimitive.content,
                )
                requests.filter { it.first == "setMyCommands" }.forEach { (_, payload) ->
                    assertEquals(
                        listOf("start", "support"),
                        payload.getValue("commands").jsonArray.map {
                            it.jsonObject.getValue("command").jsonPrimitive.content
                        },
                    )
                    assertFalse(payload.toString().contains("Обновить"))
                    assertFalse(payload.toString().contains("Главное меню"))
                }
                assertFalse(requests.any { it.first == "sendMessage" })
            } finally {
                api.close()
            }
        }

    @Test
    fun `disabled missing and invalid URLs use commands fallback`() =
        runBlocking {
            for (url in listOf(
                null,
                "",
                "http://example.com/app",
                "javascript:alert(1)",
                "https://",
                "https://user:pass@example.com",
                "https://example.com:0/",
                "https://example.com/%ZZ",
            )) {
                assertNull(buildHomeWebAppUrl(url))
                val requests = mutableListOf<Pair<String, JsonObject>>()
                val api = client(requests, url)
                try {
                    assertIs<TelegramCallResult.Success>(api.setApplicationMenuButton())
                    assertEquals("{\"menu_button\":{\"type\":\"commands\"}}", requests.single().second.toString())
                } finally {
                    api.close()
                }
            }
            val requests = mutableListOf<Pair<String, JsonObject>>()
            val api = client(requests, "https://example.com/", enabled = false)
            try {
                assertIs<TelegramCallResult.Success>(api.setApplicationMenuButton())
                assertEquals(
                    "commands",
                    requests.single().second.getValue("menu_button").jsonObject.getValue("type").jsonPrimitive.content,
                )
            } finally {
                api.close()
            }
        }

    @Test
    fun `home URL removes duplicate encoded navigation and table context`() {
        assertEquals(
            "https://example.com/miniapp/?lang=ru&mode=home",
            buildHomeWebAppUrl(
                "https://example.com/miniapp/?%6Dode=guest&mode=venue&table_token=secret" +
                    "&venueId=1&screen=menu&lang=ru#route",
            ),
        )
    }

    @Test
    fun `API refusal is reported and safe startup does not throw or send chat messages`() =
        runBlocking {
            val requests = mutableListOf<Pair<String, JsonObject>>()
            val api = client(requests, "https://example.com/", refusal = true)
            try {
                assertIs<TelegramCallResult.Failure>(api.setApplicationMenuButton())
                configureTelegramCommandMenuSafely(api)
                assertFalse(requests.any { it.first == "sendMessage" })
            } finally {
                api.close()
            }
        }

    private fun client(
        requests: MutableList<Pair<String, JsonObject>>,
        url: String?,
        enabled: Boolean = true,
        refusal: Boolean = false,
    ): TelegramApiClient {
        val json = Json { ignoreUnknownKeys = true }
        val http =
            HttpClient(
                MockEngine { request ->
                    val payload = json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    requests += request.url.encodedPath.substringAfterLast('/') to payload
                    respond(
                        if (refusal) {
                            "{\"ok\":false,\"error_code\":400,\"description\":\"Invalid button\"}"
                        } else {
                            "{\"ok\":true,\"result\":true}"
                        },
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            ) {
                install(ContentNegotiation) { json(json) }
            }
        return TelegramApiClient(
            "synthetic",
            http,
            json,
            TelegramTrafficPolicy.product(),
            webAppPublicUrl = url,
            miniAppEntryEnabled = enabled,
        )
    }
}
