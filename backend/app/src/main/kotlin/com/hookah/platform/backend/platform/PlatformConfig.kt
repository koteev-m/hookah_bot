package com.hookah.platform.backend.platform

import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory

data class PlatformConfig(
    val ownerUserId: Long?
) {
    companion object {
        fun from(config: ApplicationConfig): PlatformConfig {
            val logger = LoggerFactory.getLogger(PlatformConfig::class.java)
            val rawOwnerId = config.propertyOrNull("platform.ownerUserId")?.getString()?.trim()
            val ownerUserId = rawOwnerId?.toLongOrNull()
            if (!rawOwnerId.isNullOrBlank() && ownerUserId == null) {
                logger.warn("platform.ownerUserId is invalid: {}", sanitizeTelegramForLog(rawOwnerId))
            }
            return PlatformConfig(ownerUserId = ownerUserId)
        }
    }
}
