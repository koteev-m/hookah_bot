package com.hookah.platform.backend.maintenance

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.module
import com.hookah.platform.backend.moduleWithOverrides
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StagingMaintenanceStartupTest {
    @Test
    fun `staging active mode fails closed before database initialization for every invalid identity contract`() {
        val invalidMaintenanceConfigs =
            listOf(
                mapOf("staging.maintenance.mode" to "unknown"),
                mapOf(
                    "staging.maintenance.mode" to "V126_SMOKE",
                    "staging.maintenance.allowedUserIds" to "",
                    "staging.maintenance.allowedChatIds" to SENSITIVE_USER_ID,
                ),
                mapOf(
                    "staging.maintenance.mode" to "V126_SMOKE",
                    "staging.maintenance.allowedUserIds" to SENSITIVE_USER_ID,
                    "staging.maintenance.allowedChatIds" to "-$SENSITIVE_USER_ID",
                ),
                mapOf(
                    "staging.maintenance.mode" to "V126_SMOKE",
                    "staging.maintenance.allowedUserIds" to "$SENSITIVE_USER_ID,$SENSITIVE_USER_ID",
                    "staging.maintenance.allowedChatIds" to SENSITIVE_USER_ID,
                ),
            )

        invalidMaintenanceConfigs.forEach { maintenanceConfig ->
            val error =
                assertFailsWith<IllegalStateException> {
                    testApplication {
                        environment {
                            config =
                                baseStagingConfig(
                                    "db.jdbcUrl" to SENSITIVE_UNREACHABLE_JDBC_URL,
                                    *maintenanceConfig.map { (key, value) -> key to value }.toTypedArray(),
                                )
                        }
                        application { module() }
                        startApplication()
                    }
                }

            assertContains(error.message.orEmpty(), "staging.maintenance")
            assertFalse(error.message.orEmpty().contains(SENSITIVE_USER_ID))
            assertFalse(error.message.orEmpty().contains(SENSITIVE_UNREACHABLE_JDBC_URL))
        }
    }

    @Test
    fun `active mode requires underlying product policy`() {
        val error =
            assertFailsWith<IllegalStateException> {
                testApplication {
                    environment {
                        config =
                            baseStagingConfig(
                                "telegram.trafficPolicy" to "ALLOWLIST",
                                "telegram.allowedUserIds" to SENSITIVE_USER_ID,
                                "telegram.allowedChatIds" to SENSITIVE_USER_ID,
                                *activeMaintenanceConfig(),
                            )
                    }
                    application { module() }
                    startApplication()
                }
            }

        assertContains(error.message.orEmpty(), "V126_SMOKE requires TELEGRAM_TRAFFIC_POLICY=PRODUCT")
        assertFalse(error.message.orEmpty().contains(SENSITIVE_USER_ID))
    }

    @Test
    fun `active mode starts without command configuration while off ignores maintenance lists`() {
        var activeCommandConfigurationStarted = false
        testApplication {
            environment {
                config =
                    baseStagingConfig(
                        "telegram.enabled" to "true",
                        "telegram.token" to "test-bot-token",
                        "telegram.mode" to "webhook",
                        "telegram.webhookPath" to "/telegram/webhook",
                        "telegram.webhookSecretToken" to "test-webhook-secret",
                        "telegram.staffChatLinkSecretPepper" to "test-link-pepper",
                        *activeMaintenanceConfig(),
                    )
            }
            application {
                moduleWithOverrides(
                    ModuleOverrides(
                        telegramCommandMenuConfigurator = { activeCommandConfigurationStarted = true },
                    ),
                )
            }
            startApplication()
        }
        assertFalse(activeCommandConfigurationStarted)

        var offStarted = false
        testApplication {
            environment {
                config =
                    baseStagingConfig(
                        "staging.maintenance.mode" to "OFF",
                        "staging.maintenance.allowedUserIds" to "malformed ignored value",
                        "staging.maintenance.allowedChatIds" to "also ignored",
                    )
            }
            application {
                module()
                offStarted = true
            }
            startApplication()
        }
        assertTrue(offStarted)
    }

    private fun baseStagingConfig(vararg entries: Pair<String, String>): MapApplicationConfig =
        MapApplicationConfig(
            "app.env" to "staging",
            "api.session.jwtSecret" to "startup-test-secret",
            "telegram.trafficPolicy" to "PRODUCT",
            "venue.staffInviteSecretPepper" to "startup-invite-secret",
            *entries,
        )

    private fun activeMaintenanceConfig(): Array<Pair<String, String>> =
        arrayOf(
            "staging.maintenance.mode" to "V126_SMOKE",
            "staging.maintenance.allowedUserIds" to SENSITIVE_USER_ID,
            "staging.maintenance.allowedChatIds" to "$SENSITIVE_USER_ID,$SENSITIVE_GROUP_ID",
        )

    private companion object {
        const val SENSITIVE_USER_ID = "711111111111111111"
        const val SENSITIVE_GROUP_ID = "-1002222222222"
        const val SENSITIVE_UNREACHABLE_JDBC_URL = "jdbc:must-not-be-opened-maintenance"
    }
}
