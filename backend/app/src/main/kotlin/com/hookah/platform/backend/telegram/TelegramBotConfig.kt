package com.hookah.platform.backend.telegram

import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory
import java.util.Locale

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
    val requireStaffChatAdmin: Boolean,
    val outbox: TelegramOutboxConfig = TelegramOutboxConfig(),
) {
    enum class Mode { LONG_POLLING, WEBHOOK }

    companion object {
        fun from(
            config: ApplicationConfig,
            appEnv: String,
        ): TelegramBotConfig {
            val section = config.config("telegram")
            val enabled = section.propertyOrNull("enabled")?.getString()?.toBoolean() ?: false
            val token = section.propertyOrNull("token")?.getString()?.takeIf { it.isNotBlank() }
            val mode =
                when (section.propertyOrNull("mode")?.getString()?.lowercase(Locale.ROOT)) {
                    "webhook" -> Mode.WEBHOOK
                    else -> Mode.LONG_POLLING
                }
            val webhookPath =
                section.propertyOrNull("webhookPath")?.getString()?.takeIf { it.isNotBlank() }
                    ?: "/telegram/webhook"
            val webhookSecretToken =
                section.propertyOrNull("webhookSecretToken")?.getString()?.takeIf { it.isNotBlank() }
            if (appEnv == "prod" && enabled && mode == Mode.WEBHOOK && webhookSecretToken.isNullOrBlank()) {
                val logger = LoggerFactory.getLogger(TelegramBotConfig::class.java)
                logger.error("telegram.webhookSecretToken is required for webhook mode in prod")
                error("telegram.webhookSecretToken must be configured for env=$appEnv")
            }
            val webAppPublicUrl = section.propertyOrNull("webAppPublicUrl")?.getString()?.takeIf { it.isNotBlank() }
            val platformOwnerId = section.propertyOrNull("platformOwnerId")?.getString()?.toLongOrNull()
            val longPollingTimeoutSeconds =
                section.propertyOrNull("longPollingTimeoutSeconds")?.getString()?.toIntOrNull() ?: 25
            val staffChatLinkTtlSeconds =
                section.propertyOrNull("staffChatLinkTtlSeconds")?.getString()?.toLongOrNull()
                    ?.takeIf { it in 60..3600 } ?: 900L
            val staffChatLinkSecretPepperRaw =
                section.propertyOrNull("staffChatLinkSecretPepper")?.getString()
            val staffChatLinkSecretPepper =
                when {
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
            val outboxSection = runCatching { section.config("outbox") }.getOrNull()
            val outbox =
                TelegramOutboxConfig(
                    pollIntervalMillis =
                        outboxSection?.propertyOrNull("pollIntervalMillis")?.getString()?.toLongOrNull()
                            ?: TelegramOutboxConfig().pollIntervalMillis,
                    batchSize =
                        outboxSection?.propertyOrNull("batchSize")?.getString()?.toIntOrNull()
                            ?: TelegramOutboxConfig().batchSize,
                    visibilityTimeoutSeconds =
                        outboxSection?.propertyOrNull("visibilityTimeoutSeconds")?.getString()?.toLongOrNull()
                            ?: TelegramOutboxConfig().visibilityTimeoutSeconds,
                    maxAttempts =
                        outboxSection?.propertyOrNull("maxAttempts")?.getString()?.toIntOrNull()
                            ?: TelegramOutboxConfig().maxAttempts,
                    maxConcurrency =
                        outboxSection?.propertyOrNull("maxConcurrency")?.getString()?.toIntOrNull()
                            ?: TelegramOutboxConfig().maxConcurrency,
                    perChatMinIntervalMillis =
                        outboxSection?.propertyOrNull("perChatMinIntervalMillis")?.getString()?.toLongOrNull()
                            ?: TelegramOutboxConfig().perChatMinIntervalMillis,
                    minBackoffSeconds =
                        outboxSection?.propertyOrNull("minBackoffSeconds")?.getString()?.toLongOrNull()
                            ?: TelegramOutboxConfig().minBackoffSeconds,
                    maxBackoffSeconds =
                        outboxSection?.propertyOrNull("maxBackoffSeconds")?.getString()?.toLongOrNull()
                            ?: TelegramOutboxConfig().maxBackoffSeconds,
                ).normalized()

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
                requireStaffChatAdmin = requireStaffChatAdmin,
                outbox = outbox,
            )
        }
    }
}
