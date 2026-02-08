package com.hookah.platform.backend.billing.subscription

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionBillingConfigTest {
    @Test
    fun `negative lead and reminder days are clamped to zero`() {
        val config =
            MapApplicationConfig(
                "billing.subscription.leadDays" to "-2",
                "billing.subscription.reminderDays" to "-5",
                "billing.subscription.intervalSeconds" to "60",
            )

        val result = SubscriptionBillingConfig.from(config)

        assertEquals(0L, result.leadDays)
        assertEquals(0L, result.reminderDays)
    }

    @Test
    fun `intervalSeconds equal to zero disables job`() {
        val config = MapApplicationConfig("billing.subscription.intervalSeconds" to "0")

        val result = SubscriptionBillingConfig.from(config)

        assertEquals(0L, result.intervalSeconds)
    }

    @Test
    fun `invalid intervalSeconds disables job`() {
        val config = MapApplicationConfig("billing.subscription.intervalSeconds" to "nope")

        val result = SubscriptionBillingConfig.from(config)

        assertEquals(0L, result.intervalSeconds)
    }
}
