package com.hookah.platform.backend.telegram

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.moduleWithOverrides
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TelegramWebhookConfigTest {
    @Test
    fun `webhook secret is required for every normalized restricted environment`() {
        restrictedEnvironmentVariants.forEach { appEnv ->
            listOf<String?>(null, "", " \t ").forEach { secret ->
                val error =
                    assertFailsWith<IllegalStateException> {
                        TelegramBotConfig.from(webhookConfig(secret), appEnv)
                    }

                assertEquals(
                    "telegram.webhookSecretToken must be configured for restricted webhook mode",
                    error.message,
                )
            }
        }
    }

    @Test
    fun `webhook secret remains optional in dev and test`() {
        listOf("dev", " DEV ", "test", " TeSt ").forEach { appEnv ->
            val parsed = TelegramBotConfig.from(webhookConfig(secret = null), appEnv)
            assertNull(parsed.webhookSecretToken)
        }
    }

    @Test
    fun `configured secret is accepted for every normalized restricted environment`() {
        restrictedEnvironmentVariants.forEach { appEnv ->
            val parsed = TelegramBotConfig.from(webhookConfig(secret = "configured-secret"), appEnv)

            assertEquals("configured-secret", parsed.webhookSecretToken)
        }
    }

    @Test
    fun `secretless production webhook fails before database route or runtime state without leaking config`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        rootLogger.addAppender(appender)
        var runtimeStarted = false
        try {
            val error =
                assertFailsWith<IllegalStateException> {
                    testApplication {
                        environment {
                            config =
                                MapApplicationConfig(
                                    "app.env" to " PrOdUcTiOn ",
                                    "api.session.jwtSecret" to "test-jwt-secret",
                                    "db.jdbcUrl" to SENSITIVE_UNREACHABLE_JDBC_URL,
                                    "telegram.enabled" to "true",
                                    "telegram.token" to SENSITIVE_BOT_TOKEN,
                                    "telegram.mode" to "webhook",
                                    "telegram.webhookPath" to "/telegram/$SENSITIVE_WEBHOOK_PATH",
                                    "telegram.staffChatLinkSecretPepper" to "test-pepper",
                                    "telegram.trafficPolicy" to "ALLOWLIST",
                                    "telegram.allowedUserIds" to SENSITIVE_USER_ID,
                                    "telegram.allowedChatIds" to "$SENSITIVE_USER_ID,$SENSITIVE_GROUP_ID",
                                )
                        }
                        application {
                            moduleWithOverrides(
                                ModuleOverrides(
                                    telegramCommandMenuConfigurator = { runtimeStarted = true },
                                ),
                            )
                        }
                        startApplication()
                    }
                }

            assertContains(
                error.message.orEmpty(),
                "telegram.webhookSecretToken must be configured for restricted webhook mode",
            )
            assertFalse(runtimeStarted)

            val logs = appender.list.joinToString("\n") { it.formattedMessage }
            assertContains(logs, "telegram.webhookSecretToken is required")
            listOf(
                SENSITIVE_UNREACHABLE_JDBC_URL,
                SENSITIVE_BOT_TOKEN,
                SENSITIVE_WEBHOOK_PATH,
                SENSITIVE_USER_ID,
                SENSITIVE_GROUP_ID,
            ).forEach { sensitiveValue ->
                assertFalse(logs.contains(sensitiveValue))
                assertFalse(error.message.orEmpty().contains(sensitiveValue))
            }
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun webhookConfig(secret: String?): MapApplicationConfig {
        val entries =
            mutableListOf(
                "telegram.enabled" to "true",
                "telegram.token" to "test-token",
                "telegram.mode" to "webhook",
                "telegram.webhookPath" to "/telegram/webhook",
                "telegram.longPollingTimeoutSeconds" to "25",
                "telegram.staffChatLinkTtlSeconds" to "900",
                "telegram.staffChatLinkSecretPepper" to "test-pepper",
            )
        if (secret != null) {
            entries += "telegram.webhookSecretToken" to secret
        }
        return MapApplicationConfig(*entries.toTypedArray())
    }

    private companion object {
        val restrictedEnvironmentVariants =
            listOf("staging", " STAGING ", "prod", " PrOd ", "production", " PrOdUcTiOn ")
        const val SENSITIVE_UNREACHABLE_JDBC_URL = "jdbc:must-not-be-opened-sensitive-production-webhook"
        const val SENSITIVE_BOT_TOKEN = "777777:SENSITIVE_PRODUCTION_WEBHOOK_TOKEN"
        const val SENSITIVE_WEBHOOK_PATH = "SENSITIVE_PRODUCTION_WEBHOOK_PATH"
        const val SENSITIVE_USER_ID = "711111111111111111"
        const val SENSITIVE_GROUP_ID = "-1002222222222"
    }
}
