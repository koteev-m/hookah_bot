package com.hookah.platform.backend.ai

import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory
import java.util.Locale

data class AiAssistantConfig(
    val enabled: Boolean = false,
    val provider: String = PROVIDER_FAKE,
    val apiKey: String? = null,
    val model: String? = null,
    val timeoutMs: Long = 10_000,
    val maxInputChars: Int = 8_000,
    val maxOutputTokens: Int = 700,
    val rateLimitPerUserPerHour: Int = 20,
    val logRawPrompts: Boolean = false,
    val writeActionsEnabled: Boolean = false,
    val systemPromptVersion: String = "ai-assistant-v1a",
) {
    val normalizedProvider: String = provider.trim().lowercase(Locale.ROOT).ifBlank { PROVIDER_FAKE }

    companion object {
        const val PROVIDER_FAKE = "fake"
        const val PROVIDER_OPENAI = "openai"
        private val logger = LoggerFactory.getLogger(AiAssistantConfig::class.java)

        fun from(config: ApplicationConfig): AiAssistantConfig {
            val result =
                AiAssistantConfig(
                    enabled = config.propertyOrNull("ai.enabled")?.getString()?.toBooleanStrictOrNull() ?: false,
                    provider = config.propertyOrNull("ai.provider")?.getString()?.takeIf { it.isNotBlank() } ?: PROVIDER_FAKE,
                    apiKey = config.propertyOrNull("ai.apiKey")?.getString()?.takeIf { it.isNotBlank() },
                    model = config.propertyOrNull("ai.model")?.getString()?.takeIf { it.isNotBlank() },
                    timeoutMs =
                        config.propertyOrNull("ai.timeoutMs")?.getString()?.toLongOrNull()
                            ?.coerceIn(1_000, 120_000)
                            ?: 10_000,
                    maxInputChars =
                        config.propertyOrNull("ai.maxInputChars")?.getString()?.toIntOrNull()
                            ?.coerceIn(500, 50_000)
                            ?: 8_000,
                    maxOutputTokens =
                        config.propertyOrNull("ai.maxOutputTokens")?.getString()?.toIntOrNull()
                            ?.coerceIn(100, 8_000)
                            ?: 700,
                    rateLimitPerUserPerHour =
                        config.propertyOrNull("ai.rateLimitPerUserPerHour")?.getString()?.toIntOrNull()
                            ?.coerceIn(1, 500)
                            ?: 20,
                    logRawPrompts = config.propertyOrNull("ai.logRawPrompts")?.getString()?.toBooleanStrictOrNull() ?: false,
                    writeActionsEnabled =
                        config.propertyOrNull("ai.writeActionsEnabled")?.getString()?.toBooleanStrictOrNull() ?: false,
                    systemPromptVersion =
                        config.propertyOrNull("ai.systemPromptVersion")?.getString()?.takeIf { it.isNotBlank() }
                            ?: "ai-assistant-v1a",
                )
            if (result.enabled && result.normalizedProvider != PROVIDER_FAKE && result.apiKey.isNullOrBlank()) {
                logger.error("AI assistant provider '{}' requires ai.apiKey when enabled", result.normalizedProvider)
                error("ai.apiKey must be configured when ai assistant provider is '${result.normalizedProvider}'")
            }
            if (result.enabled && result.normalizedProvider == PROVIDER_OPENAI && result.model.isNullOrBlank()) {
                logger.error("AI assistant provider '{}' requires ai.model when enabled", result.normalizedProvider)
                error("ai.model must be configured when ai assistant provider is '${result.normalizedProvider}'")
            }
            return result
        }
    }
}
