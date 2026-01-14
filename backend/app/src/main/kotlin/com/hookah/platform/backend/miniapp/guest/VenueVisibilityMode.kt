package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.ConfigException
import io.ktor.server.config.ApplicationConfig
import java.util.Locale

enum class VenueVisibilityMode {
    EXPLAIN_SUSPENDED,
    HIDE_SUSPENDED
}

fun resolveVenueVisibilityMode(appConfig: ApplicationConfig): VenueVisibilityMode {
    val configKey = "api.guest.suspendedMode"
    val envKey = "API_GUEST_SUSPENDED_MODE"
    val configValue = appConfig.propertyOrNull(configKey)?.getString()
    val envValue = System.getenv(envKey)

    return parseVenueVisibilityMode(configValue, configKey, envKey)
        ?: parseVenueVisibilityMode(envValue, configKey, envKey)
        ?: VenueVisibilityMode.EXPLAIN_SUSPENDED
}

private fun parseVenueVisibilityMode(
    rawValue: String?,
    configKey: String,
    envKey: String
): VenueVisibilityMode? {
    val normalized = rawValue?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    return when (normalized) {
        "explain" -> VenueVisibilityMode.EXPLAIN_SUSPENDED
        "hide" -> VenueVisibilityMode.HIDE_SUSPENDED
        else -> throw ConfigException("$configKey or $envKey must be 'explain' or 'hide'")
    }
}
