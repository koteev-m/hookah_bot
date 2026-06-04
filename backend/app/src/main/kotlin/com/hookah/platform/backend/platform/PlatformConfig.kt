package com.hookah.platform.backend.platform

import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory

data class PlatformConfig(
    val ownerUserId: Long?,
) {
    companion object {
        fun from(config: ApplicationConfig): PlatformConfig {
            val logger = LoggerFactory.getLogger(PlatformConfig::class.java)
            val telegramOwnerId =
                parseOwnerId(
                    raw = config.propertyOrNull("telegram.platformOwnerId")?.getString()?.trim(),
                    key = "telegram.platformOwnerId",
                    logger = logger,
                )
            val platformOwnerUserId =
                parseOwnerId(
                    raw = config.propertyOrNull("platform.ownerUserId")?.getString()?.trim(),
                    key = "platform.ownerUserId",
                    logger = logger,
                )
            val legacyOwnerTelegramId =
                parseOwnerId(
                    raw =
                        config.propertyOrNull("platform.legacyOwnerTelegramId")?.getString()?.trim()
                            ?: System.getenv("OWNER_TELEGRAM_ID")?.trim(),
                    key = "OWNER_TELEGRAM_ID",
                    logger = logger,
                )
            val ownerUserId = telegramOwnerId ?: platformOwnerUserId ?: legacyOwnerTelegramId
            return PlatformConfig(ownerUserId = ownerUserId)
        }

        private fun parseOwnerId(
            raw: String?,
            key: String,
            logger: org.slf4j.Logger,
        ): Long? {
            if (raw.isNullOrBlank()) return null
            val parsed = raw.toLongOrNull()
            if (parsed == null) {
                logger.warn("{} is invalid: {}", key, sanitizeTelegramForLog(raw))
            }
            return parsed
        }
    }
}
