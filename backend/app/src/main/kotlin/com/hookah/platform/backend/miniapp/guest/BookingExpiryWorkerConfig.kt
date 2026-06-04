package com.hookah.platform.backend.miniapp.guest

import io.ktor.server.config.ApplicationConfig
import java.time.Duration

data class BookingExpiryWorkerConfig(
    val enabled: Boolean,
    val interval: Duration,
    val batchSize: Int,
) {
    companion object {
        fun from(config: ApplicationConfig): BookingExpiryWorkerConfig {
            val enabled =
                config.propertyOrNull("booking.expiry.enabled")
                    ?.getString()
                    ?.trim()
                    ?.toBooleanStrictOrNull()
                    ?: true
            val intervalSeconds =
                config.propertyOrNull("booking.expiry.intervalSeconds")
                    ?.getString()
                    ?.trim()
                    ?.toLongOrNull()
                    ?: 60L
            val batchSize =
                config.propertyOrNull("booking.expiry.batchSize")
                    ?.getString()
                    ?.trim()
                    ?.toIntOrNull()
                    ?: 100
            require(intervalSeconds > 0) { "booking.expiry.intervalSeconds must be > 0" }
            require(batchSize > 0) { "booking.expiry.batchSize must be > 0" }
            return BookingExpiryWorkerConfig(
                enabled = enabled,
                interval = Duration.ofSeconds(intervalSeconds),
                batchSize = batchSize,
            )
        }
    }
}
