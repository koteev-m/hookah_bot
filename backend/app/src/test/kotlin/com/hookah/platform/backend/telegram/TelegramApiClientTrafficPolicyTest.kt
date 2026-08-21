package com.hookah.platform.backend.telegram

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.hookah.platform.backend.configureTelegramCommandMenuSafely
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramApiClientTrafficPolicyTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val trafficPolicy =
        TelegramTrafficPolicy.from(
            MapApplicationConfig(
                "telegram.trafficPolicy" to "ALLOWLIST",
                "telegram.allowedUserIds" to ALLOWED_PRIVATE_CHAT.toString(),
                "telegram.allowedChatIds" to "$ALLOWED_PRIVATE_CHAT,$ALLOWED_GROUP_CHAT",
            ),
            appEnv = "staging",
        )

    @Test
    fun `denied direct chat operations do not call Telegram`() =
        runBlocking {
            val paths = mutableListOf<String>()
            val apiClient = apiClient(paths)

            assertNull(apiClient.sendMessage(DENIED_CHAT, "do not send"))
            assertFalse(
                apiClient.sendPhotoBytes(
                    chatId = DENIED_CHAT,
                    photoBytes = byteArrayOf(1),
                    filename = "blocked.png",
                ),
            )
            assertFalse(apiClient.deleteMessage(DENIED_CHAT, 1L))
            assertNull(apiClient.getChat(DENIED_CHAT))
            assertNull(apiClient.getChatMember(ALLOWED_GROUP_CHAT, DENIED_CHAT))
            assertIs<TelegramCallResult.TrafficDenied>(
                apiClient.dispatchOutbox(
                    envelopeChatId = DENIED_CHAT,
                    method = "sendMessage",
                    payload = sendMessagePayload(DENIED_CHAT),
                ),
            )
            assertIs<TelegramCallResult.TrafficDenied>(
                apiClient.dispatchOutbox(
                    envelopeChatId = DENIED_CHAT,
                    method = "editMessageText",
                    payload = chatPayload(DENIED_CHAT, "message_id" to 1L, "text" to "do not edit"),
                ),
            )
            assertIs<TelegramCallResult.TrafficDenied>(
                apiClient.dispatchOutbox(
                    envelopeChatId = DENIED_CHAT,
                    method = "answerCallbackQuery",
                    payload = answerCallbackPayload(),
                ),
            )

            assertTrue(paths.isEmpty())
            apiClient.close()
        }

    @Test
    fun `outbox dispatcher rejects unknown missing and mismatched targets without network`() =
        runBlocking {
            val paths = mutableListOf<String>()
            val apiClient = apiClient(paths)

            val invalid =
                listOf(
                    apiClient.dispatchOutbox(
                        ALLOWED_PRIVATE_CHAT,
                        "unknownMethod",
                        sendMessagePayload(ALLOWED_PRIVATE_CHAT),
                    ),
                    apiClient.dispatchOutbox(
                        ALLOWED_PRIVATE_CHAT,
                        "editMessageText",
                        chatPayload(ALLOWED_GROUP_CHAT, "message_id" to 1L, "text" to "mismatched edit"),
                    ),
                    apiClient.dispatchOutbox(
                        ALLOWED_PRIVATE_CHAT,
                        "sendMessage",
                        buildJsonObject { put("text", "missing target") },
                    ),
                    apiClient.dispatchOutbox(
                        ALLOWED_PRIVATE_CHAT,
                        "answerCallbackQuery",
                        buildJsonObject { put("callback_query_id", "") },
                    ),
                )

            invalid.forEach { result ->
                val failure = assertIs<TelegramCallResult.Failure>(result)
                assertEquals(TelegramCallResult.Failure.Origin.LOCAL_ENVELOPE_VALIDATION, failure.origin)
            }
            assertIs<TelegramCallResult.TrafficDenied>(
                apiClient.dispatchOutbox(
                    0L,
                    "answerCallbackQuery",
                    answerCallbackPayload(),
                ),
            )
            assertTrue(paths.isEmpty())

            assertIs<TelegramCallResult.Success>(
                apiClient.dispatchOutbox(
                    ALLOWED_PRIVATE_CHAT,
                    "sendMessage",
                    sendMessagePayload(ALLOWED_PRIVATE_CHAT),
                ),
            )
            assertIs<TelegramCallResult.Success>(
                apiClient.dispatchOutbox(
                    ALLOWED_GROUP_CHAT,
                    "answerCallbackQuery",
                    answerCallbackPayload(),
                ),
            )
            assertIs<TelegramCallResult.Success>(
                apiClient.dispatchOutbox(
                    ALLOWED_PRIVATE_CHAT,
                    "editMessageText",
                    chatPayload(ALLOWED_PRIVATE_CHAT, "message_id" to 1L, "text" to "edit"),
                ),
            )
            assertIs<TelegramCallResult.Success>(
                apiClient.dispatchOutbox(
                    ALLOWED_PRIVATE_CHAT,
                    "sendPhoto",
                    chatPayload(ALLOWED_PRIVATE_CHAT, "photo" to "file-photo"),
                ),
            )
            assertIs<TelegramCallResult.Success>(
                apiClient.dispatchOutbox(
                    ALLOWED_PRIVATE_CHAT,
                    "sendDocument",
                    chatPayload(ALLOWED_PRIVATE_CHAT, "document" to "file-document"),
                ),
            )
            assertEquals(
                listOf(
                    "/bot$FAKE_TOKEN/sendMessage",
                    "/bot$FAKE_TOKEN/answerCallbackQuery",
                    "/bot$FAKE_TOKEN/editMessageText",
                    "/bot$FAKE_TOKEN/sendPhoto",
                    "/bot$FAKE_TOKEN/sendDocument",
                ),
                paths,
            )
            apiClient.close()
        }

    @Test
    fun `zero chat callback is denied even in unrestricted compatibility mode`() =
        runBlocking {
            val paths = mutableListOf<String>()
            val apiClient = apiClient(paths, policy = TelegramTrafficPolicy.unrestricted())
            try {
                assertIs<TelegramCallResult.TrafficDenied>(
                    apiClient.dispatchOutbox(
                        0L,
                        "answerCallbackQuery",
                        answerCallbackPayload(),
                    ),
                )
                assertTrue(paths.isEmpty())
            } finally {
                apiClient.close()
            }
        }

    @Test
    fun `typed bot-global operations and webhook inspection remain available`() =
        runBlocking {
            val paths = mutableListOf<String>()
            val apiClient = apiClient(paths)
            val commands =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("command", "start")
                            put("description", "Start")
                        },
                    )
                }

            assertEquals("", apiClient.getWebhookInfo().url)
            assertIs<TelegramCallResult.Success>(apiClient.deleteMyCommands("all_private_chats"))
            assertIs<TelegramCallResult.Success>(apiClient.setMyCommands(null, commands))
            assertIs<TelegramCallResult.Success>(apiClient.setMyCommands("all_group_chats", commands))
            assertIs<TelegramCallResult.Success>(apiClient.setCommandsMenuButton())

            assertEquals(
                listOf(
                    "/bot$FAKE_TOKEN/getWebhookInfo",
                    "/bot$FAKE_TOKEN/deleteMyCommands",
                    "/bot$FAKE_TOKEN/setMyCommands",
                    "/bot$FAKE_TOKEN/setMyCommands",
                    "/bot$FAKE_TOKEN/setChatMenuButton",
                ),
                paths,
            )
            apiClient.close()
        }

    @Test
    fun `provider failures do not log ids token or payload`() =
        runBlocking {
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val apiLogger = LoggerFactory.getLogger(TelegramApiClient::class.java) as Logger
            apiLogger.addAppender(appender)
            val paths = mutableListOf<String>()
            val apiClient =
                apiClient(
                    paths = paths,
                    token = SENSITIVE_TOKEN,
                    forcedResponse =
                        """
                        {
                          "ok": false,
                          "error_code": 400,
                          "description": "$PAYLOAD_SENTINEL user=$ALLOWED_PRIVATE_CHAT chat=$ALLOWED_GROUP_CHAT token=$SENSITIVE_TOKEN",
                          "parameters": { "retry_after": 7 }
                        }
                        """.trimIndent(),
                )
            try {
                assertNull(apiClient.sendMessage(ALLOWED_PRIVATE_CHAT, PAYLOAD_SENTINEL))
                assertNull(apiClient.getChatMember(ALLOWED_GROUP_CHAT, ALLOWED_PRIVATE_CHAT))
                val exceptionApiClient =
                    apiClient(
                        paths = paths,
                        token = SENSITIVE_TOKEN,
                        forcedFailure =
                            IllegalStateException(
                                "$PAYLOAD_SENTINEL user=$ALLOWED_PRIVATE_CHAT " +
                                    "chat=$ALLOWED_GROUP_CHAT token=$SENSITIVE_TOKEN",
                            ),
                    )
                try {
                    assertFalse(exceptionApiClient.deleteMessage(ALLOWED_PRIVATE_CHAT, ALLOWED_PRIVATE_CHAT))
                } finally {
                    exceptionApiClient.close()
                }

                val logs = appender.list.joinToString("\n") { it.formattedMessage }
                assertTrue(logs.contains("operation=sendMessage"))
                assertTrue(logs.contains("operation=getChatMember"))
                assertTrue(logs.contains("error_code=400"))
                assertFalse(logs.contains(PAYLOAD_SENTINEL))
                assertFalse(logs.contains(ALLOWED_PRIVATE_CHAT.toString()))
                assertFalse(logs.contains(ALLOWED_GROUP_CHAT.toString()))
                assertFalse(logs.contains(SENSITIVE_TOKEN))
            } finally {
                apiClient.close()
                apiLogger.detachAppender(appender)
                appender.stop()
            }
        }

    @Test
    fun `bot global transport failure is contained without logging token or request payload`() =
        runBlocking {
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val applicationLogger = LoggerFactory.getLogger("Application") as Logger
            applicationLogger.addAppender(appender)
            val paths = mutableListOf<String>()
            val apiClient =
                apiClient(
                    paths = paths,
                    token = SENSITIVE_TOKEN,
                    forcedFailure =
                        IllegalStateException(
                            "$PAYLOAD_SENTINEL user=$ALLOWED_PRIVATE_CHAT " +
                                "chat=$ALLOWED_GROUP_CHAT token=$SENSITIVE_TOKEN",
                        ),
                )
            try {
                configureTelegramCommandMenuSafely(apiClient)

                val logs = appender.list.joinToString("\n") { it.formattedMessage }
                assertTrue(logs.contains("Telegram command menu configuration failed"))
                assertTrue(logs.contains("errorType=IllegalStateException"))
                assertFalse(logs.contains(PAYLOAD_SENTINEL))
                assertFalse(logs.contains(ALLOWED_PRIVATE_CHAT.toString()))
                assertFalse(logs.contains(ALLOWED_GROUP_CHAT.toString()))
                assertFalse(logs.contains(SENSITIVE_TOKEN))
            } finally {
                apiClient.close()
                applicationLogger.detachAppender(appender)
                appender.stop()
            }
        }

    private fun apiClient(
        paths: MutableList<String>,
        token: String = FAKE_TOKEN,
        forcedResponse: String? = null,
        forcedFailure: Throwable? = null,
        policy: TelegramTrafficPolicy = trafficPolicy,
    ): TelegramApiClient {
        val client =
            HttpClient(
                MockEngine { request ->
                    paths += request.url.encodedPath
                    forcedFailure?.let { throw it }
                    if (forcedResponse != null) {
                        respondJson(forcedResponse)
                    } else if (request.url.encodedPath.endsWith("/getWebhookInfo")) {
                        respondJson("""{"ok":true,"result":{"url":""}}""")
                    } else {
                        respondJson("""{"ok":true,"result":true}""")
                    }
                },
            ) {
                install(ContentNegotiation) { json(this@TelegramApiClientTrafficPolicyTest.json) }
            }
        return TelegramApiClient(
            token = token,
            client = client,
            json = json,
            trafficPolicy = policy,
        )
    }

    private fun sendMessagePayload(chatId: Long) =
        buildJsonObject {
            put("chat_id", chatId)
            put("text", "test")
        }

    private fun answerCallbackPayload() =
        buildJsonObject {
            put("callback_query_id", "callback-test")
        }

    private fun chatPayload(
        chatId: Long,
        vararg values: Pair<String, Any>,
    ) = buildJsonObject {
        put("chat_id", chatId)
        values.forEach { (key, value) ->
            when (value) {
                is Long -> put(key, value)
                is String -> put(key, value)
                else -> error("Unsupported test payload value")
            }
        }
    }

    private fun MockRequestHandleScope.respondJson(content: String) =
        respond(
            content = content,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )

    private companion object {
        const val ALLOWED_PRIVATE_CHAT = 101L
        const val ALLOWED_GROUP_CHAT = -100202L
        const val DENIED_CHAT = 303L
        const val FAKE_TOKEN = "123456:test_token"
        const val SENSITIVE_TOKEN = "777777:SENSITIVE_TOKEN"
        const val PAYLOAD_SENTINEL = "PAYLOAD_SENTINEL"
    }
}
