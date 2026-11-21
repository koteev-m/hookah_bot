package com.hookah.platform.backend.billing.subscription

import io.ktor.server.config.MapApplicationConfig
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SubscriptionBillingConfigTest {
    @Test
    fun `negative lead reminder and grace days are clamped to zero`() {
        val config =
            MapApplicationConfig(
                "billing.subscription.leadDays" to "-2",
                "billing.subscription.reminderDays" to "-5",
                "billing.subscription.graceDays" to "-7",
                "billing.subscription.intervalSeconds" to "60",
            )

        val result = SubscriptionBillingConfig.from(config)

        assertEquals(0L, result.leadDays)
        assertEquals(0L, result.reminderDays)
        assertEquals(0L, result.graceDays)
    }

    @Test
    fun `intervalSeconds equal to zero disables job`() {
        val config = MapApplicationConfig("billing.subscription.intervalSeconds" to "0")

        val result = SubscriptionBillingConfig.from(config)

        assertEquals(0L, result.intervalSeconds)
        val parent = Job()
        try {
            SubscriptionBillingJob(mockk(), result.intervalSeconds).start(CoroutineScope(parent))
            assertFalse(parent.children.any(), "Disabled billing must not schedule even an initial tick")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `invalid intervalSeconds disables job`() {
        val config = MapApplicationConfig("billing.subscription.intervalSeconds" to "nope")

        val result = SubscriptionBillingConfig.from(config)

        assertEquals(0L, result.intervalSeconds)
    }
}
