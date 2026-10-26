package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.maintenance.StagingMaintenancePolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TelegramDocumentDeliveryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `binary document multipart contains exact file mime filename and own recipient`() =
        runBlocking<Unit> {
            var calls = 0
            val http =
                HttpClient(
                    MockEngine { request ->
                        calls++
                        assertTrue(request.url.encodedPath.endsWith("/sendDocument"))
                        val channel = ByteChannel(autoFlush = true)
                        val writer =
                            launch {
                                (request.body as OutgoingContent.WriteChannelContent).writeTo(channel)
                                channel.close()
                            }
                        val body = channel.readRemaining().readBytes().toString(Charsets.UTF_8)
                        writer.join()
                        assertTrue(body.contains("name=chat_id") || body.contains("name=\"chat_id\""))
                        assertTrue(body.contains("12345"))
                        assertTrue(body.contains("filename=\"venue-1-tables.pdf\""))
                        assertTrue(body.contains("application/pdf"))
                        assertTrue(body.contains("%PDF-synthetic"))
                        respond(
                            """{"ok":true,"result":{"message_id":7,"chat":{"id":12345,"type":"private"}}}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(json) } }
            try {
                val api = TelegramApiClient("synthetic", http, json, TelegramTrafficPolicy.product())
                assertIs<TelegramCallResult.Success>(
                    api.sendDocumentBytes(
                        12345,
                        "%PDF-synthetic".toByteArray(),
                        "venue-1-tables.pdf",
                        ContentType.Application.Pdf,
                    ),
                )
                assertEquals(1, calls)
            } finally {
                http.close()
            }
        }

    @Test
    fun `traffic maintenance and nonprivate recipients block document upload`() =
        runBlocking<Unit> {
            var calls = 0
            val http =
                HttpClient(
                    MockEngine {
                        calls++
                        error("No network expected")
                    },
                )
            val policy =
                TelegramTrafficPolicy.from(
                    MapApplicationConfig(
                        "telegram.trafficPolicy" to "ALLOWLIST",
                        "telegram.allowedUserIds" to "12345",
                        "telegram.allowedChatIds" to "12345",
                    ),
                    "staging",
                )
            val maintenance =
                StagingMaintenancePolicy.from(
                    MapApplicationConfig(
                        "staging.maintenance.mode" to "V126_SMOKE",
                        "staging.maintenance.allowedUserIds" to "12345",
                        "staging.maintenance.allowedChatIds" to "12345",
                    ),
                    "staging",
                )
            try {
                for (api in listOf(
                    TelegramApiClient("synthetic", http, json, policy),
                    TelegramApiClient("synthetic", http, json, TelegramTrafficPolicy.product(), maintenance),
                )) {
                    for (id in listOf(999L, -12345L, 0L)) {
                        assertIs<TelegramCallResult.TrafficDenied>(
                            api.sendDocumentBytes(id, byteArrayOf(1), "qr.png", ContentType.Image.PNG),
                        )
                    }
                }
                assertEquals(0, calls)
            } finally {
                http.close()
            }
        }

    @Test
    fun `size and filename limits reject before network`() =
        runBlocking<Unit> {
            val http = HttpClient(MockEngine { error("No network expected") })
            try {
                val api = TelegramApiClient("synthetic", http, json, TelegramTrafficPolicy.product())
                assertFailsWith<IllegalArgumentException> {
                    api.sendDocumentBytes(
                        12345,
                        ByteArray(TelegramApiClient.MAX_DOCUMENT_BYTES + 1),
                        "qr.pdf",
                        ContentType.Application.Pdf,
                    )
                }
                assertFailsWith<IllegalArgumentException> {
                    api.sendDocumentBytes(12345, byteArrayOf(), "qr.pdf", ContentType.Application.Pdf)
                }
                assertFailsWith<IllegalArgumentException> {
                    api.sendDocumentBytes(12345, byteArrayOf(1), "bad\r\nfilename", ContentType.Application.Pdf)
                }
            } finally {
                http.close()
            }
        }

    @Test
    fun `unconfirmed wrong recipient blocked and rate limited responses never succeed or retry`() =
        runBlocking<Unit> {
            for (response in listOf(
                """{"ok":true}""",
                """{"ok":true,"result":{"message_id":1,"chat":{"id":999,"type":"private"}}}""",
                """{"ok":true,"result":{"message_id":1,"chat":{"id":12345,"type":"group"}}}""",
                """{"ok":false,"error_code":403}""",
                """{"ok":false,"error_code":429,"parameters":{"retry_after":30}}""",
            )) {
                var calls = 0
                val http =
                    HttpClient(
                        MockEngine {
                            calls++
                            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    ) { install(ContentNegotiation) { json(json) } }
                try {
                    val api = TelegramApiClient("synthetic", http, json, TelegramTrafficPolicy.product())
                    val result = api.sendDocumentBytes(12345, byteArrayOf(1), "qr.png", ContentType.Image.PNG)
                    assertIs<TelegramCallResult.Failure>(result)
                    if (response.contains("429")) assertEquals(30, result.retryAfterSeconds)
                    assertEquals(1, calls)
                } finally {
                    http.close()
                }
            }
        }
}
