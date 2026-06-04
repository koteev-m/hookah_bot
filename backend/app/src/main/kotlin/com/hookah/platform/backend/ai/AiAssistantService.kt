package com.hookah.platform.backend.ai

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId

data class AiPromotionDiagnosticsCommand(
    val principal: AiAssistantPrincipal,
    val venueId: Long,
    val promotionId: Long,
    val ruleId: Long? = null,
    val now: Instant = Instant.now(),
    val venueZoneId: ZoneId = ZoneId.systemDefault(),
)

enum class AiDraftTextType {
    PROMOTION_TEXT,
    FEEDBACK_REPLY,
    BANNER_TEXT,
    ;

    val toolName: String
        get() =
            when (this) {
                PROMOTION_TEXT -> AiAssistantService.TOOL_DRAFT_PROMOTION_TEXT
                FEEDBACK_REPLY -> AiAssistantService.TOOL_DRAFT_FEEDBACK_REPLY
                BANNER_TEXT -> AiAssistantService.TOOL_DRAFT_BANNER_TEXT
            }
}

data class AiDraftTextCommand(
    val principal: AiAssistantPrincipal,
    val venueId: Long,
    val type: AiDraftTextType,
    val brief: String,
)

data class AiAssistantAnswer(
    val text: String,
)

class AiAssistantService(
    private val config: AiAssistantConfig,
    private val client: AiAssistantClient,
    private val toolRegistry: AiToolRegistry,
    private val contextAssembler: AiContextAssembler,
    private val auditLogger: AiAuditLogger = NoopAiAuditLogger,
) {
    val isEnabled: Boolean
        get() = config.enabled

    suspend fun diagnosePromotion(command: AiPromotionDiagnosticsCommand): AiAssistantAnswer {
        if (!config.enabled) {
            return AiAssistantAnswer("Помощник отключён в конфигурации.")
        }
        val eventBase =
            AiAuditEvent(
                principal = command.principal,
                venueId = command.venueId,
                entityId = command.promotionId,
                toolName = TOOL_PROMOTION_DIAGNOSTICS,
                promptVersion = config.systemPromptVersion,
                success = false,
            )
        return try {
            val diagnostics =
                toolRegistry.runPromotionDiagnostics(
                    PromotionDiagnosticsRequest(
                        venueId = command.venueId,
                        promotionId = command.promotionId,
                        ruleId = command.ruleId,
                        now = command.now,
                        venueZoneId = command.venueZoneId,
                    ),
                )
            val prompt =
                contextAssembler
                    .buildPromotionDiagnosticsPrompt(diagnostics)
                    .take(config.maxInputChars)
            val response =
                client.complete(
                    AiAssistantCompletionRequest(
                        systemPromptVersion = config.systemPromptVersion,
                        toolName = TOOL_PROMOTION_DIAGNOSTICS,
                        sanitizedPrompt = prompt,
                        maxOutputTokens = config.maxOutputTokens,
                    ),
                )
            auditLogger.log(eventBase.copy(success = true))
            AiAssistantAnswer(response.text)
        } catch (e: DatabaseUnavailableException) {
            auditLogger.log(eventBase.copy(failureCode = "DATABASE_UNAVAILABLE"))
            AiAssistantAnswer("База недоступна, попробуйте позже.")
        } catch (e: IllegalArgumentException) {
            auditLogger.log(eventBase.copy(failureCode = "INVALID_REQUEST"))
            AiAssistantAnswer("Не удалось выполнить диагностику: ${e.message ?: "проверьте параметры."}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            auditLogger.log(eventBase.copy(failureCode = "CLIENT_ERROR"))
            AiAssistantAnswer("Не удалось выполнить диагностику, попробуйте позже.")
        }
    }

    suspend fun draftText(command: AiDraftTextCommand): AiAssistantAnswer {
        if (!config.enabled) {
            return AiAssistantAnswer("Помощник отключён в конфигурации.")
        }
        val eventBase =
            AiAuditEvent(
                principal = command.principal,
                venueId = command.venueId,
                entityId = null,
                toolName = command.type.toolName,
                promptVersion = config.systemPromptVersion,
                success = false,
            )
        return try {
            val sanitizedBrief = command.brief.trim().take(config.maxInputChars)
            require(sanitizedBrief.isNotBlank()) { "краткое описание пустое." }
            val prompt =
                contextAssembler
                    .buildDraftTextPrompt(command.type, sanitizedBrief)
                    .take(config.maxInputChars + DRAFT_PROMPT_OVERHEAD_CHARS)
            val response =
                client.complete(
                    AiAssistantCompletionRequest(
                        systemPromptVersion = config.systemPromptVersion,
                        toolName = command.type.toolName,
                        sanitizedPrompt = prompt,
                        maxOutputTokens = config.maxOutputTokens,
                    ),
                )
            auditLogger.log(eventBase.copy(success = true))
            AiAssistantAnswer(response.text)
        } catch (e: IllegalArgumentException) {
            auditLogger.log(eventBase.copy(failureCode = "INVALID_REQUEST"))
            AiAssistantAnswer("Не удалось подготовить черновик: ${e.message ?: "проверьте текст."}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            auditLogger.log(eventBase.copy(failureCode = "CLIENT_ERROR"))
            AiAssistantAnswer("Не удалось подготовить черновик, попробуйте позже.")
        }
    }

    suspend fun summarizeVenue(command: AiVenueSummaryCommand): AiAssistantAnswer {
        if (!config.enabled) {
            return AiAssistantAnswer("Помощник отключён в конфигурации.")
        }
        val eventBase =
            AiAuditEvent(
                principal = command.principal,
                venueId = command.venueId,
                entityId = null,
                toolName = command.type.toolName,
                promptVersion = config.systemPromptVersion,
                success = false,
            )
        return try {
            val summary =
                toolRegistry.runVenueSummary(
                    VenueSummaryRequest(
                        venueId = command.venueId,
                        type = command.type,
                        now = command.now,
                        venueZoneId = command.venueZoneId,
                    ),
                )
            val prompt =
                contextAssembler
                    .buildVenueSummaryPrompt(summary)
                    .take(config.maxInputChars)
            val response =
                client.complete(
                    AiAssistantCompletionRequest(
                        systemPromptVersion = config.systemPromptVersion,
                        toolName = command.type.toolName,
                        sanitizedPrompt = prompt,
                        maxOutputTokens = config.maxOutputTokens,
                    ),
                )
            auditLogger.log(eventBase.copy(success = true))
            AiAssistantAnswer(response.text)
        } catch (e: DatabaseUnavailableException) {
            auditLogger.log(eventBase.copy(failureCode = "DATABASE_UNAVAILABLE"))
            AiAssistantAnswer("База недоступна, попробуйте позже.")
        } catch (e: IllegalArgumentException) {
            auditLogger.log(eventBase.copy(failureCode = "INVALID_REQUEST"))
            AiAssistantAnswer("Не удалось подготовить сводку: ${e.message ?: "проверьте параметры."}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            auditLogger.log(eventBase.copy(failureCode = "CLIENT_ERROR"))
            AiAssistantAnswer("Не удалось подготовить сводку, попробуйте позже.")
        }
    }

    companion object {
        const val TOOL_PROMOTION_DIAGNOSTICS = "promotion_diagnostics"
        const val TOOL_DRAFT_PROMOTION_TEXT = "DRAFT_PROMOTION_TEXT"
        const val TOOL_DRAFT_FEEDBACK_REPLY = "DRAFT_FEEDBACK_REPLY"
        const val TOOL_DRAFT_BANNER_TEXT = "DRAFT_BANNER_TEXT"
        const val TOOL_SUMMARY_PROMOTION = "SUMMARY_PROMOTION"
        const val TOOL_SUMMARY_FEEDBACK = "SUMMARY_FEEDBACK"
        const val TOOL_SUMMARY_LOYALTY = "SUMMARY_LOYALTY"
        const val TOOL_SUMMARY_ORDERS = "SUMMARY_ORDERS"
        private const val DRAFT_PROMPT_OVERHEAD_CHARS = 2_000
    }
}
