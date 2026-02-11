package com.hookah.platform.backend.miniapp.guest

import io.ktor.server.config.ApplicationConfig
import java.time.Duration

data class TableSessionConfig(
    val ttl: Duration,
    val cleanupInterval: Duration,
) {
    companion object {
        fun from(config: ApplicationConfig): TableSessionConfig {
            val ttlSeconds =
                config.propertyOrNull("guest.tableSession.ttlSeconds")
                    ?.getString()
                    ?.trim()
                    ?.toLongOrNull()
                    ?: 7200L
            val cleanupIntervalSeconds =
                config.propertyOrNull("guest.tableSession.cleanupIntervalSeconds")
                    ?.getString()
                    ?.trim()
                    ?.toLongOrNull()
                    ?: 60L
            require(ttlSeconds > 0) { "guest.tableSession.ttlSeconds must be > 0" }
            require(cleanupIntervalSeconds > 0) { "guest.tableSession.cleanupIntervalSeconds must be > 0" }
            return TableSessionConfig(
                ttl = Duration.ofSeconds(ttlSeconds),
                cleanupInterval = Duration.ofSeconds(cleanupIntervalSeconds),
            )
        }
    }
}
