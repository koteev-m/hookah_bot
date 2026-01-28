package com.hookah.platform.backend.miniapp.venue.staff

import io.ktor.server.config.ApplicationConfig
import java.util.Locale
import org.slf4j.LoggerFactory

data class StaffInviteConfig(
    val defaultTtlSeconds: Long,
    val maxTtlSeconds: Long,
    val secretPepper: String
) {
    companion object {
        private const val DEFAULT_TTL_SECONDS = 7 * 24 * 3600L
        private const val DEFAULT_MAX_TTL_SECONDS = 30 * 24 * 3600L
        private const val MIN_TTL_SECONDS = 60L

        fun from(config: ApplicationConfig, appEnv: String): StaffInviteConfig {
            val ttlSeconds = config.propertyOrNull("venue.staffInviteTtlSeconds")
                ?.getString()
                ?.toLongOrNull()
                ?.takeIf { it >= MIN_TTL_SECONDS }
                ?: DEFAULT_TTL_SECONDS
            val maxTtlSeconds = config.propertyOrNull("venue.staffInviteMaxTtlSeconds")
                ?.getString()
                ?.toLongOrNull()
                ?.takeIf { it >= ttlSeconds }
                ?: DEFAULT_MAX_TTL_SECONDS
            val pepperRaw = config.propertyOrNull("venue.staffInviteSecretPepper")
                ?.getString()
                ?.takeIf { it.isNotBlank() }
            val normalizedEnv = appEnv.trim().lowercase(Locale.ROOT)
            val secretPepper = when {
                !pepperRaw.isNullOrBlank() -> pepperRaw.trim()
                normalizedEnv == "prod" -> {
                    val logger = LoggerFactory.getLogger(StaffInviteConfig::class.java)
                    logger.error("venue.staffInviteSecretPepper is required in prod environment")
                    error("staff invite pepper must be configured")
                }
                else -> "dev-invite-pepper"
            }

            return StaffInviteConfig(
                defaultTtlSeconds = ttlSeconds,
                maxTtlSeconds = maxTtlSeconds,
                secretPepper = secretPepper
            )
        }
    }
}
