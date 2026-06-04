package com.hookah.platform.backend.ai

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiAssistantServiceTest {
    @Test
    fun `draft promotion text uses fake client and returns structured draft`() =
        runBlocking {
            val service = testService(client = FakeAiAssistantClient())

            val answer =
                service.draftText(
                    testCommand(
                        type = AiDraftTextType.PROMOTION_TEXT,
                        brief = "кальян + чай по будням до 18:00",
                    ),
                )

            assertTrue(answer.text.contains("Черновик. Проверьте текст перед публикацией."))
            assertTrue(answer.text.contains("Название:"))
            assertTrue(answer.text.contains("Описание:"))
            assertTrue(answer.text.contains("Условия:"))
        }

    @Test
    fun `draft review reply uses fake client and remains draft only`() =
        runBlocking {
            val service = testService(client = FakeAiAssistantClient())

            val answer =
                service.draftText(
                    testCommand(
                        type = AiDraftTextType.FEEDBACK_REPLY,
                        brief = "Долго ждали заказ",
                    ),
                )

            assertTrue(answer.text.contains("Черновик. Проверьте текст перед отправкой."))
            assertTrue(answer.text.contains("Спасибо за обратную связь"))
        }

    @Test
    fun `draft input is capped by max input chars`() =
        runBlocking {
            val client = CapturingAiClient()
            val service =
                testService(
                    client = client,
                    config = AiAssistantConfig(enabled = true, maxInputChars = 12),
                )

            service.draftText(
                testCommand(
                    type = AiDraftTextType.BANNER_TEXT,
                    brief = "abcdefghijklmnop",
                ),
            )

            assertEquals(AiAssistantService.TOOL_DRAFT_BANNER_TEXT, client.lastRequest.toolName)
            assertTrue(client.lastRequest.sanitizedPrompt.contains("abcdefghijkl"))
            assertFalse(client.lastRequest.sanitizedPrompt.contains("mnop"))
        }

    @Test
    fun `draft audit logs metadata without raw prompt`() =
        runBlocking {
            val auditLogger = RecordingAiAuditLogger()
            val service =
                testService(
                    client = FakeAiAssistantClient(),
                    auditLogger = auditLogger,
                )

            service.draftText(
                testCommand(
                    type = AiDraftTextType.BANNER_TEXT,
                    brief = "секретный тестовый ввод",
                ),
            )

            val event = auditLogger.events.single()
            assertEquals(200L, event.principal.userId)
            assertEquals(AiAssistantRole.OWNER, event.principal.role)
            assertEquals(10L, event.venueId)
            assertEquals(AiAssistantService.TOOL_DRAFT_BANNER_TEXT, event.toolName)
            assertTrue(event.success)
            assertEquals(null, event.entityId)
        }

    @Test
    fun `venue summary uses fake client and logs metadata`() =
        runBlocking {
            val toolRegistry = mockk<AiToolRegistry>()
            coEvery { toolRegistry.runVenueSummary(any()) } returns
                VenueSummaryResult(
                    venueId = 10L,
                    type = AiVenueSummaryType.FEEDBACK,
                    title = "⭐ Сводка по отзывам",
                    summaryLines = listOf("Последних отзывов в выборке: 3", "Низкие оценки: 1"),
                    attentionLines = listOf("Есть отзывы, на которые стоит ответить: 1."),
                )
            val auditLogger = RecordingAiAuditLogger()
            val service =
                testService(
                    client = FakeAiAssistantClient(),
                    toolRegistry = toolRegistry,
                    auditLogger = auditLogger,
                )

            val answer =
                service.summarizeVenue(
                    AiVenueSummaryCommand(
                        principal = AiAssistantPrincipal(userId = 200L, role = AiAssistantRole.OWNER),
                        venueId = 10L,
                        type = AiVenueSummaryType.FEEDBACK,
                    ),
                )

            assertTrue(answer.text.contains("⭐ Сводка по отзывам"))
            assertTrue(answer.text.contains("Низкие оценки: 1"))
            assertTrue(answer.text.contains("Главное:"))
            assertTrue(answer.text.contains("Требует внимания:"))
            assertFalse(answer.text.contains("Подготовь короткую управленческую сводку"))
            assertFalse(answer.text.contains("Детерминированные данные"))
            assertFalse(answer.text.contains("Правила безопасности"))
            val event = auditLogger.events.single()
            assertEquals(AiAssistantService.TOOL_SUMMARY_FEEDBACK, event.toolName)
            assertEquals(10L, event.venueId)
            assertTrue(event.success)
        }

    @Test
    fun `fake venue summaries do not expose internal prompt for any summary type`() =
        runBlocking {
            val client = FakeAiAssistantClient()
            val assembler = AiContextAssembler()

            AiVenueSummaryType.entries.forEach { type ->
                val prompt =
                    assembler.buildVenueSummaryPrompt(
                        VenueSummaryResult(
                            venueId = 10L,
                            type = type,
                            title = type.title,
                            summaryLines = listOf("Активно: 1", "Проверка: данные есть"),
                            attentionLines = listOf("Есть пункт для проверки."),
                            sourceNotes = listOf("Часть данных может быть неполной."),
                        ),
                    )

                val response =
                    client.complete(
                        AiAssistantCompletionRequest(
                            systemPromptVersion = "ai-assistant-test",
                            toolName = type.toolName,
                            sanitizedPrompt = prompt,
                            maxOutputTokens = 600,
                        ),
                    )

                assertTrue(response.text.contains(type.title))
                assertTrue(response.text.contains("Главное:"))
                assertTrue(response.text.contains("Требует внимания:"))
                assertFalse(response.text.contains("Подготовь короткую управленческую сводку"))
                assertFalse(response.text.contains("Детерминированные данные"))
                assertFalse(response.text.contains("Правила безопасности"))
                assertFalse(response.text.contains("read-only сводка"))
            }
        }

    private fun testService(
        client: AiAssistantClient,
        config: AiAssistantConfig = AiAssistantConfig(enabled = true, systemPromptVersion = "ai-assistant-test"),
        auditLogger: AiAuditLogger = NoopAiAuditLogger,
        toolRegistry: AiToolRegistry = mockk(relaxed = true),
    ): AiAssistantService =
        AiAssistantService(
            config = config,
            client = client,
            toolRegistry = toolRegistry,
            contextAssembler = AiContextAssembler(),
            auditLogger = auditLogger,
        )

    private fun testCommand(
        type: AiDraftTextType,
        brief: String,
    ): AiDraftTextCommand =
        AiDraftTextCommand(
            principal = AiAssistantPrincipal(userId = 200L, role = AiAssistantRole.OWNER),
            venueId = 10L,
            type = type,
            brief = brief,
        )

    private class CapturingAiClient : AiAssistantClient {
        lateinit var lastRequest: AiAssistantCompletionRequest

        override suspend fun complete(request: AiAssistantCompletionRequest): AiAssistantCompletionResponse {
            lastRequest = request
            return AiAssistantCompletionResponse("Черновик. Проверьте текст перед публикацией.")
        }
    }

    private class RecordingAiAuditLogger : AiAuditLogger {
        val events = mutableListOf<AiAuditEvent>()

        override suspend fun log(event: AiAuditEvent) {
            events += event
        }
    }
}
