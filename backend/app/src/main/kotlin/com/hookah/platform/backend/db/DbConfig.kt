package com.hookah.platform.backend.db

import io.ktor.server.config.ApplicationConfig

data class DbConfig(
    val jdbcUrl: String?,
    val user: String?,
    val password: String?,
    val maxPoolSize: Int?,
    val connectionTimeoutMs: Long?
) {
    val isEnabled: Boolean = !jdbcUrl.isNullOrBlank()

    companion object {
        fun from(applicationConfig: ApplicationConfig): DbConfig {
            return DbConfig(
                jdbcUrl = applicationConfig.propertyOrNull("db.jdbcUrl")?.getString()?.takeIf { it.isNotBlank() },
                user = applicationConfig.propertyOrNull("db.user")?.getString()?.takeIf { it.isNotBlank() },
                password = applicationConfig.propertyOrNull("db.password")?.getString()?.takeIf { it.isNotBlank() },
                maxPoolSize = applicationConfig.propertyOrNull("db.maxPoolSize")
                    ?.getString()
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 },
                connectionTimeoutMs = applicationConfig.propertyOrNull("db.connectionTimeoutMs")
                    ?.getString()
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
            )
        }
    }
}
