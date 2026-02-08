package com.hookah.platform.backend.billing.subscription

import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory
import java.time.ZoneId

data class SubscriptionBillingConfig(
    val timeZone: ZoneId,
    val leadDays: Long,
    val reminderDays: Long,
    val intervalSeconds: Long,
) {
    companion object {
        fun from(config: ApplicationConfig): SubscriptionBillingConfig {
            val logger = LoggerFactory.getLogger(SubscriptionBillingConfig::class.java)
            val timeZoneRaw = config.propertyOrNull("billing.subscription.timeZone")?.getString()?.trim()
            val timeZone =
                try {
                    if (timeZoneRaw.isNullOrBlank()) ZoneId.of("UTC") else ZoneId.of(timeZoneRaw)
                } catch (e: Exception) {
                    logger.warn("billing.subscription.timeZone is invalid: {}", sanitizeTelegramForLog(timeZoneRaw))
                    ZoneId.of("UTC")
                }
            val leadDaysRaw = config.propertyOrNull("billing.subscription.leadDays")?.getString()?.trim()
            val leadDaysParsed = leadDaysRaw?.toLongOrNull() ?: 7L
            val leadDays =
                if (leadDaysParsed < 0) {
                    logger.warn(
                        "billing.subscription.leadDays is negative ({}), clamping to 0",
                        sanitizeTelegramForLog(leadDaysRaw),
                    )
                    0L
                } else {
                    leadDaysParsed
                }
            val reminderDaysRaw = config.propertyOrNull("billing.subscription.reminderDays")?.getString()?.trim()
            val reminderDaysParsed = reminderDaysRaw?.toLongOrNull() ?: 3L
            val reminderDays =
                if (reminderDaysParsed < 0) {
                    logger.warn(
                        "billing.subscription.reminderDays is negative ({}), clamping to 0",
                        sanitizeTelegramForLog(reminderDaysRaw),
                    )
                    0L
                } else {
                    reminderDaysParsed
                }
            val intervalSecondsRaw = config.propertyOrNull("billing.subscription.intervalSeconds")?.getString()?.trim()
            val intervalSeconds =
                if (intervalSecondsRaw.isNullOrBlank()) {
                    60L
                } else {
                    val parsedInterval = intervalSecondsRaw.toLongOrNull()
                    when {
                        parsedInterval == null -> {
                            logger.warn(
                                "billing.subscription.intervalSeconds is invalid ({}), " +
                                    "subscription billing job disabled",
                                sanitizeTelegramForLog(intervalSecondsRaw),
                            )
                            0L
                        }
                        parsedInterval <= 0 -> {
                            logger.warn(
                                "billing.subscription.intervalSeconds must be positive ({}), " +
                                    "subscription billing job disabled",
                                sanitizeTelegramForLog(intervalSecondsRaw),
                            )
                            0L
                        }
                        else -> parsedInterval
                    }
                }
            return SubscriptionBillingConfig(
                timeZone = timeZone,
                leadDays = leadDays,
                reminderDays = reminderDays,
                intervalSeconds = intervalSeconds,
            )
        }
    }
}
