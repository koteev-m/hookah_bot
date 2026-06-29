package com.hookah.platform.backend.config

import com.hookah.platform.backend.platform.PlatformConfig
import com.hookah.platform.backend.telegram.TelegramBotConfig
import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformOwnerIdResolverTest {
    @Test
    fun `api and bot prefer telegram platform owner id`() {
        val config =
            buildConfig(
                "telegram.platformOwnerId" to "101",
                "platform.ownerUserId" to "202",
            )

        assertResolvedOwner(config, expected = 101L)
    }

    @Test
    fun `api and bot use platform owner user id fallback`() {
        val config =
            buildConfig(
                "platform.ownerUserId" to "202",
            )

        assertResolvedOwner(config, expected = 202L)
    }

    @Test
    fun `api and bot use legacy owner telegram id fallback`() {
        val config = buildConfig()
        val environment = mapOf("OWNER_TELEGRAM_ID" to "303")

        assertResolvedOwner(config, environment, expected = 303L)
    }

    @Test
    fun `api and bot ignore invalid higher precedence owner id`() {
        val config =
            buildConfig(
                "telegram.platformOwnerId" to "not-a-number",
                "platform.ownerUserId" to "202",
            )

        assertResolvedOwner(config, expected = 202L)
    }

    @Test
    fun `api and bot keep deterministic precedence on conflicting ids`() {
        val config =
            buildConfig(
                "telegram.platformOwnerId" to "101",
                "platform.ownerUserId" to "202",
            )
        val environment = mapOf("OWNER_TELEGRAM_ID" to "303")

        assertResolvedOwner(config, environment, expected = 101L)
    }

    private fun assertResolvedOwner(
        config: MapApplicationConfig,
        environment: Map<String, String> = emptyMap(),
        expected: Long,
    ) {
        val apiConfig = PlatformConfig.from(config, environment)
        val botConfig = TelegramBotConfig.from(config, appEnv = "test", environment = environment)

        assertEquals(expected, apiConfig.ownerUserId)
        assertEquals(expected, botConfig.platformOwnerId)
    }

    private fun buildConfig(vararg values: Pair<String, String>): MapApplicationConfig {
        val entries =
            mutableListOf(
                "telegram.enabled" to "false",
                "telegram.staffChatLinkSecretPepper" to "pepper",
            )
        entries.addAll(values)
        return MapApplicationConfig(*entries.toTypedArray())
    }
}
