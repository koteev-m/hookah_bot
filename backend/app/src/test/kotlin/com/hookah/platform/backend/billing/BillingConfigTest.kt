package com.hookah.platform.backend.billing

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BillingConfigTest {
    @Test
    fun `billing webhook secret is required in prod`() {
        val config = MapApplicationConfig("billing.provider" to "fake")

        assertFailsWith<IllegalStateException> {
            BillingConfig.from(config, "prod")
        }
    }
}
