package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.module
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TelegramTrafficPolicyStartupTest {
    @Test
    fun `staging application fails closed on invalid policy before database initialization`() {
        val invalidPolicies =
            listOf(
                emptyMap(),
                mapOf("telegram.trafficPolicy" to ""),
                mapOf("telegram.trafficPolicy" to "UNRESTRICTED"),
                mapOf("telegram.trafficPolicy" to "unknown-policy"),
                mapOf(
                    "telegram.trafficPolicy" to "ALLOWLIST",
                    "telegram.allowedUserIds" to "",
                    "telegram.allowedChatIds" to "101",
                ),
                mapOf(
                    "telegram.trafficPolicy" to "ALLOWLIST",
                    "telegram.allowedUserIds" to "101",
                    "telegram.allowedChatIds" to "",
                ),
                mapOf(
                    "telegram.trafficPolicy" to "ALLOWLIST",
                    "telegram.allowedUserIds" to "711111111111111111,711111111111111111",
                    "telegram.allowedChatIds" to "711111111111111111",
                ),
            )

        invalidPolicies.forEach { policyConfig ->
            val error =
                assertFailsWith<IllegalStateException> {
                    testApplication {
                        environment {
                            config =
                                MapApplicationConfig(
                                    "app.env" to "staging",
                                    "db.jdbcUrl" to "jdbc:must-not-be-opened",
                                    *policyConfig.map { (key, value) -> key to value }.toTypedArray(),
                                )
                        }
                        application { module() }
                        startApplication()
                    }
                }

            assertContains(error.message.orEmpty(), "telegram.")
            assertFalse(error.message.orEmpty().contains("jdbc:must-not-be-opened"))
            assertFalse(error.message.orEmpty().contains("711111111111111111"))
        }
    }
}
