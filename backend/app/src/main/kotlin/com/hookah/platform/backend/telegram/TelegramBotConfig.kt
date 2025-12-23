package com.hookah.platform.backend.telegram

import io.ktor.server.config.ApplicationConfig

data class TelegramBotConfig(
    val enabled: Boolean,
    val token: String?,
    val mode: Mode,
    val webhookPath: String,
    val webhookSecretToken: String?,
    val webAppPublicUrl: String?,
    val platformOwnerId: Long?,
    val longPollingTimeoutSeconds: Int
) {
    enum class Mode { LONG_POLLING, WEBHOOK }

    companion object {
        fun from(config: ApplicationConfig): TelegramBotConfig {
            val section = config.config("telegram")
            val enabled = section.propertyOrNull("enabled")?.getString()?.toBoolean() ?: false
            val token = section.propertyOrNull("token")?.getString()?.takeIf { it.isNotBlank() }
            val mode = when (section.propertyOrNull("mode")?.getString()?.lowercase()) {
                "webhook" -> Mode.WEBHOOK
                else -> Mode.LONG_POLLING
            }
            val webhookPath = section.propertyOrNull("webhookPath")?.getString()?.takeIf { it.isNotBlank() }
                ?: "/telegram/webhook"
            val webhookSecretToken =
                section.propertyOrNull("webhookSecretToken")?.getString()?.takeIf { it.isNotBlank() }
            val webAppPublicUrl = section.propertyOrNull("webAppPublicUrl")?.getString()?.takeIf { it.isNotBlank() }
            val platformOwnerId = section.propertyOrNull("platformOwnerId")?.getString()?.toLongOrNull()
            val longPollingTimeoutSeconds =
                section.propertyOrNull("longPollingTimeoutSeconds")?.getString()?.toIntOrNull() ?: 25

            return TelegramBotConfig(
                enabled = enabled,
                token = token,
                mode = mode,
                webhookPath = webhookPath,
                webhookSecretToken = webhookSecretToken,
                webAppPublicUrl = webAppPublicUrl,
                platformOwnerId = platformOwnerId,
                longPollingTimeoutSeconds = longPollingTimeoutSeconds
            )
        }
    }
}
