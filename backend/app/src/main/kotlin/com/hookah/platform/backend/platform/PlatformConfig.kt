package com.hookah.platform.backend.platform

import com.hookah.platform.backend.config.PlatformOwnerIdResolver
import io.ktor.server.config.ApplicationConfig

data class PlatformConfig(
    val ownerUserId: Long?,
) {
    companion object {
        fun from(
            config: ApplicationConfig,
            environment: Map<String, String> = System.getenv(),
        ): PlatformConfig = PlatformConfig(ownerUserId = PlatformOwnerIdResolver.resolve(config, environment))
    }
}
