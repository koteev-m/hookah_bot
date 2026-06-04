package com.hookah.platform.backend.miniapp.guest

import io.ktor.server.config.ApplicationConfig
import java.time.Duration

data class VisitFeedbackWorkerConfig(
    val enabled: Boolean,
    val interval: Duration,
    val batchSize: Int,
) {
    companion object {
        fun from(config: ApplicationConfig): VisitFeedbackWorkerConfig {
            val enabled =
                config.propertyOrNull("visit.feedback.enabled")
                    ?.getString()
                    ?.trim()
                    ?.toBooleanStrictOrNull()
                    ?: true
            val intervalSeconds =
                config.propertyOrNull("visit.feedback.intervalSeconds")
                    ?.getString()
                    ?.trim()
                    ?.toLongOrNull()
                    ?: 60L
            val batchSize =
                config.propertyOrNull("visit.feedback.batchSize")
                    ?.getString()
                    ?.trim()
                    ?.toIntOrNull()
                    ?: 100
            require(intervalSeconds > 0) { "visit.feedback.intervalSeconds must be > 0" }
            require(batchSize > 0) { "visit.feedback.batchSize must be > 0" }
            return VisitFeedbackWorkerConfig(
                enabled = enabled,
                interval = Duration.ofSeconds(intervalSeconds),
                batchSize = batchSize,
            )
        }
    }
}
