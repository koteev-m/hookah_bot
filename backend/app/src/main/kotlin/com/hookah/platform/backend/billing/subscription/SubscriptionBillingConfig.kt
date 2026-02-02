package com.hookah.platform.backend.billing.subscription

import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import io.ktor.server.config.ApplicationConfig
import java.time.ZoneId
import org.slf4j.LoggerFactory

data class SubscriptionBillingConfig(
    val timeZone: ZoneId,
    val leadDays: Long,
    val reminderDays: Long,
    val intervalSeconds: Long
) {
    companion object {
        fun from(config: ApplicationConfig): SubscriptionBillingConfig {
            val logger = LoggerFactory.getLogger(SubscriptionBillingConfig::class.java)
            val timeZoneRaw = config.propertyOrNull("billing.subscription.timeZone")?.getString()?.trim()
            val timeZone = try {
                if (timeZoneRaw.isNullOrBlank()) ZoneId.of("UTC") else ZoneId.of(timeZoneRaw)
            } catch (e: Exception) {
                logger.warn("billing.subscription.timeZone is invalid: {}", sanitizeTelegramForLog(timeZoneRaw))
                ZoneId.of("UTC")
            }
            val leadDays = config.propertyOrNull("billing.subscription.leadDays")?.getString()?.toLongOrNull() ?: 7L
            val reminderDays = config.propertyOrNull("billing.subscription.reminderDays")?.getString()?.toLongOrNull() ?: 3L
            val intervalSeconds = config.propertyOrNull("billing.subscription.intervalSeconds")?.getString()?.toLongOrNull() ?: 60L
            return SubscriptionBillingConfig(
                timeZone = timeZone,
                leadDays = leadDays,
                reminderDays = reminderDays,
                intervalSeconds = intervalSeconds
            )
        }
    }
}
