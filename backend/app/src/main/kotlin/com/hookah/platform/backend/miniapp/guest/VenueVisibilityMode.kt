package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.ConfigException
import io.ktor.server.config.ApplicationConfig
import java.util.Locale

enum class VenueVisibilityMode {
    EXPLAIN_SUSPENDED,
    HIDE_SUSPENDED
}

fun resolveVenueVisibilityMode(appConfig: ApplicationConfig): VenueVisibilityMode {
    val rawValue = appConfig.propertyOrNull("api.guest.suspendedMode")
        ?.getString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return VenueVisibilityMode.EXPLAIN_SUSPENDED

    return when (rawValue.lowercase(Locale.ROOT)) {
        "explain" -> VenueVisibilityMode.EXPLAIN_SUSPENDED
        "hide" -> VenueVisibilityMode.HIDE_SUSPENDED
        else -> throw ConfigException("api.guest.suspendedMode must be 'explain' or 'hide'")
    }
}
