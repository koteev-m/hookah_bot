package com.hookah.platform.backend.telegram

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TelegramWebhookConfigTest {
    @Test
    fun `webhook secret is required in prod`() {
        val config = MapApplicationConfig(
            "telegram.enabled" to "true",
            "telegram.mode" to "webhook",
            "telegram.webhookPath" to "/telegram/webhook",
            "telegram.staffChatLinkSecretPepper" to "pepper"
        )

        assertFailsWith<IllegalStateException> {
            TelegramBotConfig.from(config, "prod")
        }
    }
}
