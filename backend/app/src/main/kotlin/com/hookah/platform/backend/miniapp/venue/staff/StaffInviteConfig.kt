package com.hookah.platform.backend.miniapp.venue.staff

import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory
import java.util.Locale

data class StaffInviteConfig(
    val defaultTtlSeconds: Long,
    val maxTtlSeconds: Long,
    val secretPepper: String,
) {
    companion object {
        private const val DEFAULT_TTL_SECONDS = 7 * 24 * 3600L
        private const val DEFAULT_MAX_TTL_SECONDS = 30 * 24 * 3600L
        private const val MIN_TTL_SECONDS = 60L
        private val placeholderSeparator = Regex("[^a-z0-9]+")
        private val explicitSecretPlaceholderMarkers = setOf("example", "placeholder")
        private val knownExplicitSecretPlaceholders =
            setOf(
                "change-me",
                "please-change-me",
                "please-set-when-enabled",
                "set-when-enabled",
                "replace-me",
                "replace-with-secret",
                "dev-invite-pepper",
                "local-dev-pepper",
                "example",
                "example-secret",
                "example-pepper",
                "example-invite-pepper",
                "placeholder",
                "placeholder-secret",
                "placeholder-pepper",
                "placeholder-invite-pepper",
                "your-secret",
                "your-secret-here",
                "your-pepper",
                "your-pepper-here",
            )

        fun from(
            config: ApplicationConfig,
            appEnv: String,
            requireExplicitSecret: Boolean = false,
        ): StaffInviteConfig {
            val ttlSeconds =
                config.propertyOrNull("venue.staffInviteTtlSeconds")
                    ?.getString()
                    ?.toLongOrNull()
                    ?.takeIf { it >= MIN_TTL_SECONDS }
                    ?: DEFAULT_TTL_SECONDS
            val maxTtlSeconds =
                config.propertyOrNull("venue.staffInviteMaxTtlSeconds")
                    ?.getString()
                    ?.toLongOrNull()
                    ?.takeIf { it >= ttlSeconds }
                    ?: DEFAULT_MAX_TTL_SECONDS
            val configuredPepper =
                config.propertyOrNull("venue.staffInviteSecretPepper")
                    ?.getString()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            val normalizedEnv = appEnv.trim().lowercase(Locale.ROOT)
            val secretRequired =
                normalizedEnv == "prod" || normalizedEnv == "production" || requireExplicitSecret
            val secretPepper =
                when {
                    configuredPepper == null && secretRequired -> failRequiredSecret()
                    configuredPepper == null -> "dev-invite-pepper"
                    requireExplicitSecret && isKnownExplicitSecretPlaceholder(configuredPepper) ->
                        failRequiredSecret()
                    else -> configuredPepper
                }

            return StaffInviteConfig(
                defaultTtlSeconds = ttlSeconds,
                maxTtlSeconds = maxTtlSeconds,
                secretPepper = secretPepper,
            )
        }

        private fun isKnownExplicitSecretPlaceholder(value: String): Boolean {
            val normalized =
                value
                    .lowercase(Locale.ROOT)
                    .replace(placeholderSeparator, "-")
                    .trim('-')
            return normalized in knownExplicitSecretPlaceholders ||
                normalized.split('-').any { it in explicitSecretPlaceholderMarkers }
        }

        private fun failRequiredSecret(): Nothing {
            val logger = LoggerFactory.getLogger(StaffInviteConfig::class.java)
            logger.error("venue.staffInviteSecretPepper must be explicitly configured in this environment")
            error("staff invite pepper must be explicitly configured")
        }
    }
}
