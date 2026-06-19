package com.hookah.platform.backend.miniapp.guest

import io.ktor.server.config.ApplicationConfig
import java.time.Duration

data class BookingReminderWorkerConfig(
    val enabled: Boolean,
    val interval: Duration,
    val batchSize: Int,
) {
    companion object {
        fun from(config: ApplicationConfig): BookingReminderWorkerConfig {
            val enabled =
                config.propertyOrNull("booking.reminders.enabled")
                    ?.getString()
                    ?.trim()
                    ?.toBooleanStrictOrNull()
                    ?: false
            val intervalSeconds =
                config.propertyOrNull("booking.reminders.intervalSeconds")
                    ?.getString()
                    ?.trim()
                    ?.toLongOrNull()
                    ?: 60L
            val batchSize =
                config.propertyOrNull("booking.reminders.batchSize")
                    ?.getString()
                    ?.trim()
                    ?.toIntOrNull()
                    ?: 100
            require(intervalSeconds > 0) { "booking.reminders.intervalSeconds must be > 0" }
            require(batchSize > 0) { "booking.reminders.batchSize must be > 0" }
            return BookingReminderWorkerConfig(
                enabled = enabled,
                interval = Duration.ofSeconds(intervalSeconds),
                batchSize = batchSize,
            )
        }
    }
}
