package com.hookah.platform.backend.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.server.config.MapApplicationConfig
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class OpenAiAssistantClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `fake provider remains default`() {
        val config = AiAssistantConfig.from(MapApplicationConfig())
        val httpClient = HttpClient(Java)
        try {
            val client = createAiAssistantClient(config, httpClient, json)

            assertEquals(AiAssistantConfig.PROVIDER_FAKE, config.normalizedProvider)
            assertFalse(config.writeActionsEnabled)
            assertIs<FakeAiAssistantClient>(client)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `openai provider is selected when configured`() {
        val config =
            AiAssistantConfig.from(
                MapApplicationConfig(
                    "ai.enabled" to "true",
                    "ai.provider" to "openai",
                    "ai.apiKey" to "test-key",
                    "ai.model" to "test-model",
                ),
            )
        val httpClient = HttpClient(Java)
        try {
            val client = createAiAssistantClient(config, httpClient, json)

            assertEquals(AiAssistantConfig.PROVIDER_OPENAI, config.normalizedProvider)
            assertIs<OpenAiAssistantClient>(client)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `openai provider requires api key when enabled`() {
        assertFailsWith<IllegalStateException> {
            AiAssistantConfig.from(
                MapApplicationConfig(
                    "ai.enabled" to "true",
                    "ai.provider" to "openai",
                    "ai.model" to "test-model",
                ),
            )
        }
    }

    @Test
    fun `openai provider requires model when enabled`() {
        assertFailsWith<IllegalStateException> {
            AiAssistantConfig.from(
                MapApplicationConfig(
                    "ai.enabled" to "true",
                    "ai.provider" to "openai",
                    "ai.apiKey" to "test-key",
                ),
            )
        }
    }

    @Test
    fun `openai client builds responses api request`() =
        runBlocking {
            val transport = RecordingTransport()
            val client =
                OpenAiAssistantClient(
                    apiKey = "test-key",
                    model = "test-model",
                    timeoutMs = 5_000,
                    endpoint = "https://api.openai.test/v1/responses",
                    transport = transport,
                )

            val response =
                client.complete(
                    AiAssistantCompletionRequest(
                        systemPromptVersion = "ai-test",
                        toolName = AiAssistantService.TOOL_SUMMARY_PROMOTION,
                        sanitizedPrompt = "Сводка без секретов",
                        maxOutputTokens = 321,
                    ),
                )

            assertEquals("Готовая сводка.", response.text)
            assertEquals("https://api.openai.test/v1/responses", transport.endpoint)
            assertEquals("test-key", transport.apiKey)
            assertEquals(5_000, transport.timeoutMs)
            val payload = transport.payload
            assertEquals("test-model", payload["model"]?.jsonPrimitive?.contentOrNull)
            assertEquals("Сводка без секретов", payload["input"]?.jsonPrimitive?.contentOrNull)
            assertEquals(321, payload["max_output_tokens"]?.jsonPrimitive?.int)
            assertTrue(payload["instructions"]?.jsonPrimitive?.contentOrNull?.contains("только финальным") == true)
            assertFalse(payload.containsKey("tools"))
            assertFalse(payload.containsKey("stream"))
        }

    @Test
    fun `provider failure returns safe user message`() =
        runBlocking {
            val client =
                OpenAiAssistantClient(
                    apiKey = "test-key",
                    model = "test-model",
                    timeoutMs = 5_000,
                    transport = FailingTransport(),
                )
            val service =
                AiAssistantService(
                    config =
                        AiAssistantConfig(
                            enabled = true,
                            provider = "openai",
                            apiKey = "test-key",
                            model = "test-model",
                        ),
                    client = client,
                    toolRegistry = mockk(relaxed = true),
                    contextAssembler = AiContextAssembler(),
                )

            val answer =
                service.draftText(
                    AiDraftTextCommand(
                        principal = AiAssistantPrincipal(userId = 200L, role = AiAssistantRole.OWNER),
                        venueId = 10L,
                        type = AiDraftTextType.PROMOTION_TEXT,
                        brief = "акция на кальян",
                    ),
                )

            assertEquals("Не удалось подготовить черновик, попробуйте позже.", answer.text)
        }

    private class RecordingTransport : OpenAiResponsesTransport {
        lateinit var endpoint: String
        lateinit var apiKey: String
        lateinit var payload: JsonObject
        var timeoutMs: Long = 0

        override suspend fun postResponses(
            endpoint: String,
            apiKey: String,
            payload: JsonObject,
            timeoutMs: Long,
        ): JsonObject {
            this.endpoint = endpoint
            this.apiKey = apiKey
            this.payload = payload
            this.timeoutMs = timeoutMs
            return buildJsonObject {
                put("output_text", "Готовая сводка.")
            }
        }
    }

    private class FailingTransport : OpenAiResponsesTransport {
        override suspend fun postResponses(
            endpoint: String,
            apiKey: String,
            payload: JsonObject,
            timeoutMs: Long,
        ): JsonObject = throw AiAssistantProviderException("provider failed")
    }
}
