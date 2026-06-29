package com.hookah.platform.backend.config

import io.ktor.server.config.ApplicationConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object PlatformOwnerIdResolver {
    fun resolve(
        config: ApplicationConfig,
        environment: Map<String, String> = System.getenv(),
        logger: Logger = LoggerFactory.getLogger(PlatformOwnerIdResolver::class.java),
    ): Long? {
        val valid =
            ownerIdCandidates(config, environment)
                .mapNotNull { candidate ->
                    val raw = candidate.raw?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val parsed = raw.toLongOrNull()
                    if (parsed == null) {
                        logger.warn("{} is invalid: {}", candidate.key, sanitizeConfigValueForLog(raw))
                        null
                    } else {
                        ResolvedOwnerId(candidate.key, parsed)
                    }
                }
        val selected = valid.firstOrNull() ?: return null
        val ignoredKeys =
            valid
                .filter { it.value != selected.value }
                .map { it.key }
                .distinct()
        if (ignoredKeys.isNotEmpty()) {
            logger.warn(
                "Conflicting Platform Owner Telegram user ids configured; using {} and ignoring {}",
                selected.key,
                ignoredKeys.joinToString(),
            )
        }
        return selected.value
    }

    private fun ownerIdCandidates(
        config: ApplicationConfig,
        environment: Map<String, String>,
    ): List<OwnerIdCandidate> =
        listOf(
            OwnerIdCandidate(
                "telegram.platformOwnerId",
                config.propertyOrNull("telegram.platformOwnerId")?.getString(),
            ),
            OwnerIdCandidate("PLATFORM_OWNER_TELEGRAM_ID", environment["PLATFORM_OWNER_TELEGRAM_ID"]),
            OwnerIdCandidate("platform.ownerUserId", config.propertyOrNull("platform.ownerUserId")?.getString()),
            OwnerIdCandidate("PLATFORM_OWNER_USER_ID", environment["PLATFORM_OWNER_USER_ID"]),
            OwnerIdCandidate("OWNER_TELEGRAM_ID", environment["OWNER_TELEGRAM_ID"]),
            OwnerIdCandidate(
                "platform.legacyOwnerTelegramId",
                config.propertyOrNull("platform.legacyOwnerTelegramId")?.getString(),
            ),
        )

    private fun sanitizeConfigValueForLog(value: String): String =
        value
            .replace(Regex("[\\p{Cntrl}]"), " ")
            .trim()
            .take(200)

    private data class OwnerIdCandidate(
        val key: String,
        val raw: String?,
    )

    private data class ResolvedOwnerId(
        val key: String,
        val value: Long,
    )
}
