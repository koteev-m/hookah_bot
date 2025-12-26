package com.hookah.platform.backend.telegram

import io.ktor.server.config.ApplicationConfig
import java.util.Locale
import org.slf4j.LoggerFactory

data class TelegramBotConfig(
    val enabled: Boolean,
    val token: String?,
    val mode: Mode,
    val webhookPath: String,
    val webhookSecretToken: String?,
    val webAppPublicUrl: String?,
    val platformOwnerId: Long?,
    val longPollingTimeoutSeconds: Int,
    val staffChatLinkTtlSeconds: Long,
    val staffChatLinkSecretPepper: String,
    val requireStaffChatAdmin: Boolean
) {
    enum class Mode { LONG_POLLING, WEBHOOK }

    companion object {
        fun from(config: ApplicationConfig): TelegramBotConfig {
            val section = config.config("telegram")
            val enabled = section.propertyOrNull("enabled")?.getString()?.toBoolean() ?: false
            val token = section.propertyOrNull("token")?.getString()?.takeIf { it.isNotBlank() }
            val mode = when (section.propertyOrNull("mode")?.getString()?.lowercase(Locale.ROOT)) {
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
            val staffChatLinkTtlSeconds =
                section.propertyOrNull("staffChatLinkTtlSeconds")?.getString()?.toLongOrNull()
                    ?.takeIf { it in 60..3600 } ?: 900L
            val staffChatLinkSecretPepperRaw =
                section.propertyOrNull("staffChatLinkSecretPepper")?.getString()
            val staffChatLinkSecretPepper = when {
                enabled && staffChatLinkSecretPepperRaw.isNullOrBlank() -> {
                    val logger = LoggerFactory.getLogger(TelegramBotConfig::class.java)
                    logger.error("telegram.staffChatLinkSecretPepper is required when telegram bot is enabled")
                    error("staff chat link pepper must be configured")
                }

                !enabled && staffChatLinkSecretPepperRaw.isNullOrBlank() -> "dev-pepper"
                else -> staffChatLinkSecretPepperRaw!!.trim()
            }
            val requireStaffChatAdmin =
                section.propertyOrNull("requireStaffChatAdmin")?.getString()?.toBooleanStrictOrNull() ?: true

            return TelegramBotConfig(
                enabled = enabled,
                token = token,
                mode = mode,
                webhookPath = webhookPath,
                webhookSecretToken = webhookSecretToken,
                webAppPublicUrl = webAppPublicUrl,
                platformOwnerId = platformOwnerId,
                longPollingTimeoutSeconds = longPollingTimeoutSeconds,
                staffChatLinkTtlSeconds = staffChatLinkTtlSeconds,
                staffChatLinkSecretPepper = staffChatLinkSecretPepper,
                requireStaffChatAdmin = requireStaffChatAdmin
            )
        }
    }
}
