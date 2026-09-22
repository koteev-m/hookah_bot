package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import com.hookah.platform.backend.telegram.db.TelegramOutboxStatus
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramGuestEntryOutboxOrderingTest {
    @Test
    fun `temporary QR keyboard removal failure cannot be overtaken by choice or bot keyboard`() =
        runBlocking { verifyGuestEntryDelivery(failFirstRemoval = true, repeatedEntry = false) }

    @Test
    fun `repeated QR entry keeps keyboard removals choices and bot selection in delivery order`() =
        runBlocking { verifyGuestEntryDelivery(failFirstRemoval = true, repeatedEntry = true) }

    @Test
    fun `normal QR entry removes keyboard before choice and restores it after bot selection`() =
        runBlocking { verifyGuestEntryDelivery(failFirstRemoval = false, repeatedEntry = false) }

    @Test
    fun `concurrent claims cannot overtake sending QR entry and terminal failures retain queue policy`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val policy = TelegramTrafficPolicy.unrestricted()
                val repository = TelegramOutboxRepository(dataSource, policy)
                val enqueuer = TelegramOutboxEnqueuer(repository, Json, policy)
                val now = Instant.parse("2030-01-01T00:00:00Z")
                enqueuer.enqueueSendMessage(123L, "REMOVE", dedupeKey = "guest-qr-order-entry:concurrent:remove")
                enqueuer.enqueueSendMessage(123L, "CHOICE", dedupeKey = "guest-qr-order-entry:concurrent:choice")
                val start = CompletableDeferred<Unit>()
                val claims =
                    List(2) {
                        async(Dispatchers.IO) {
                            start.await()
                            repository.claimBatch(20, now, Duration.ofMinutes(2))
                        }
                    }
                start.complete(Unit)
                val claimed = claims.awaitAll().flatten()
                assertEquals(1, claimed.size)
                assertTrue(claimed.single().payloadJson.contains("REMOVE"))
                assertEquals(emptyList(), repository.claimBatch(20, now, Duration.ofMinutes(2)))

                repository.markFailed(
                    claimed.single().id,
                    TelegramOutboxStatus.FAILED,
                    "synthetic permanent failure",
                    now,
                    null,
                )
                val afterTerminalFailure = repository.claimBatch(20, now, Duration.ofMinutes(2))
                assertEquals(1, afterTerminalFailure.size)
                assertTrue(afterTerminalFailure.single().payloadJson.contains("CHOICE"))
            }
        }

    private suspend fun verifyGuestEntryDelivery(
        failFirstRemoval: Boolean,
        repeatedEntry: Boolean,
    ) {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database).use { dataSource ->
            val policy = TelegramTrafficPolicy.unrestricted()
            val json = Json { ignoreUnknownKeys = true }
            val repository = TelegramOutboxRepository(dataSource, policy)
            val enqueuer = TelegramOutboxEnqueuer(repository, json, policy)
            val delivered = mutableListOf<String>()
            val attempted = mutableListOf<String>()
            var keyboardVisible = true
            var now = Instant.parse("2030-01-01T00:00:00Z")
            val client =
                HttpClient(
                    MockEngine { request ->
                        val payload = json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val text = payload.getValue("text").jsonPrimitive.content
                        attempted += text
                        val fail = failFirstRemoval && text == "REMOVE_1" && attempted.count { it == text } == 1
                        if (fail) {
                            respond(
                                """{"ok":false,"error_code":500,"description":"synthetic temporary failure"}""",
                                HttpStatusCode.InternalServerError,
                                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        } else {
                            delivered += text
                            val markup = payload["reply_markup"]?.jsonObject
                            if (markup?.get("remove_keyboard")?.jsonPrimitive?.booleanOrNull == true) {
                                keyboardVisible = false
                            }
                            if (markup?.containsKey("keyboard") == true) keyboardVisible = true
                            respond(
                                """{"ok":true,"result":{"message_id":7001}}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        }
                    },
                ) {
                    install(ContentNegotiation) { json(json) }
                }
            val apiClient = TelegramApiClient("synthetic-token", client, json, policy)
            val worker =
                TelegramOutboxWorker(
                    repository = repository,
                    apiClientProvider = { apiClient },
                    json = json,
                    rateLimiter = TelegramRateLimiter { },
                    config = TelegramOutboxConfig(batchSize = 20, maxConcurrency = 1),
                    scope = CoroutineScope(Dispatchers.IO),
                    nowProvider = { now },
                )
            try {
                suspend fun enqueueEntry(number: Int) {
                    enqueuer.enqueueSendMessage(
                        123L,
                        "REMOVE_$number",
                        ReplyKeyboardRemove(removeKeyboard = true),
                        dedupeKey = "guest-qr-order-entry:$number:remove",
                    )
                    enqueuer.enqueueSendMessage(
                        123L,
                        "CHOICE_$number",
                        TelegramKeyboards.inlineTableEntryChoice("https://mini.app/", "SYNTHETIC_TOKEN", 55L),
                        dedupeKey = "guest-qr-order-entry:$number:choice",
                    )
                }

                suspend fun enqueueBotSelection(number: Int) {
                    enqueuer.enqueueSendMessage(
                        123L,
                        "BOT_$number",
                        TelegramKeyboards.tableContextBotFlow(),
                        dedupeKey = "guest-qr-order-entry:$number:bot",
                    )
                }

                enqueueEntry(1)
                enqueuer.enqueueSendMessage(456L, "OTHER_CHAT")
                enqueuer.enqueueSendMessage(123L, "SAME_CHAT_HELP")
                worker.processOnce()
                // A previously delivered choice can be pressed while the new QR removal is retrying.
                enqueueBotSelection(1)
                if (repeatedEntry) {
                    enqueueEntry(2)
                    enqueueBotSelection(2)
                }
                repeat(6) { worker.processOnce() }
                val deliveredBeforeRetry = delivered.toList()
                now = now.plusSeconds(120)
                repeat(8) { worker.processOnce() }
                val expected =
                    buildList {
                        add("REMOVE_1")
                        add("CHOICE_1")
                        add("BOT_1")
                        if (repeatedEntry) addAll(listOf("REMOVE_2", "CHOICE_2", "BOT_2"))
                    }
                println("QR_R1 attempted=$attempted delivered=$delivered beforeRetry=$deliveredBeforeRetry")
                if (failFirstRemoval) {
                    assertEquals(listOf("OTHER_CHAT", "SAME_CHAT_HELP"), deliveredBeforeRetry)
                }
                assertEquals(expected, delivered.filter { it != "OTHER_CHAT" && it != "SAME_CHAT_HELP" })
                assertTrue(keyboardVisible, "A delayed removal must not erase the selected bot keyboard")
                assertEquals(if (failFirstRemoval) 2 else 1, attempted.count { it == "REMOVE_1" })
                assertEquals(1, delivered.count { it == "OTHER_CHAT" })
                assertEquals(1, delivered.count { it == "SAME_CHAT_HELP" })
            } finally {
                apiClient.close()
            }
        }
    }
}
