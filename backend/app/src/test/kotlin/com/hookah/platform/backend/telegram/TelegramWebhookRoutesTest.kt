package com.hookah.platform.backend.telegram

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramWebhookRoutesTest {
    @Test
    fun `missing telegram webhook secret returns forbidden`() =
        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "db.jdbcUrl" to "",
                        "telegram.enabled" to "true",
                        "telegram.token" to "test-token",
                        "telegram.mode" to "webhook",
                        "telegram.webhookSecretToken" to "secret",
                        "telegram.staffChatLinkSecretPepper" to "pepper",
                    )
            }
            application {
                moduleWithOverrides(
                    ModuleOverrides(telegramCommandMenuConfigurator = {}),
                )
            }

            val response = client.post("/telegram/webhook")
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `valid telegram webhook secret returns ok`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            environment {
                config =
                    MapApplicationConfig(
                        "app.env" to "test",
                        "api.session.jwtSecret" to "test-secret",
                        "db.jdbcUrl" to database.jdbcUrl,
                        "db.user" to database.user,
                        "db.password" to database.password,
                        "db.maxPoolSize" to "3",
                        "telegram.enabled" to "true",
                        "telegram.token" to "test-token",
                        "telegram.mode" to "webhook",
                        "telegram.webhookSecretToken" to "secret",
                        "telegram.staffChatLinkSecretPepper" to "pepper",
                    )
            }
            application {
                moduleWithOverrides(
                    ModuleOverrides(telegramCommandMenuConfigurator = {}),
                )
            }

            val invalidResponse =
                client.post("/telegram/webhook") {
                    headers { append("X-Telegram-Bot-Api-Secret-Token", "wrong") }
                }
            assertEquals(HttpStatusCode.Forbidden, invalidResponse.status)

            val response =
                client.post("/telegram/webhook") {
                    contentType(ContentType.Application.Json)
                    headers { append("X-Telegram-Bot-Api-Secret-Token", "secret") }
                    setBody("""{"update_id":1}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "SELECT status FROM telegram_inbound_updates WHERE update_id = 1",
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                    }
                }
            }
        }

    @Test
    fun `allowlist denial is acknowledged before webhook enqueue`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val applicationLogger = LoggerFactory.getLogger("Application") as Logger
            applicationLogger.addAppender(appender)
            try {
                environment {
                    config =
                        MapApplicationConfig(
                            "app.env" to "staging",
                            "api.session.jwtSecret" to "test-secret",
                            "db.jdbcUrl" to database.jdbcUrl,
                            "db.user" to database.user,
                            "db.password" to database.password,
                            "db.maxPoolSize" to "3",
                            "telegram.enabled" to "true",
                            "telegram.token" to SENSITIVE_BOT_TOKEN,
                            "telegram.mode" to "webhook",
                            "telegram.webhookSecretToken" to SENSITIVE_WEBHOOK_SECRET,
                            "telegram.staffChatLinkSecretPepper" to "pepper",
                            "telegram.trafficPolicy" to "ALLOWLIST",
                            "telegram.allowedUserIds" to ALLOWED_USER_ID.toString(),
                            "telegram.allowedChatIds" to ALLOWED_USER_ID.toString(),
                        )
                }
                application {
                    moduleWithOverrides(
                        ModuleOverrides(telegramCommandMenuConfigurator = {}),
                    )
                }

                val deniedResponse =
                    client.post("/telegram/webhook") {
                        contentType(ContentType.Application.Json)
                        headers { append("X-Telegram-Bot-Api-Secret-Token", SENSITIVE_WEBHOOK_SECRET) }
                        setBody(
                            "{\"update_id\":2,\"message\":{\"message_id\":1," +
                                "\"chat\":{\"id\":$DENIED_USER_ID,\"type\":\"private\"}," +
                                "\"from\":{\"id\":$DENIED_USER_ID}," +
                                "\"text\":\"$PAYLOAD_SENTINEL\"}}",
                        )
                    }
                assertEquals(HttpStatusCode.OK, deniedResponse.status)

                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM telegram_inbound_updates WHERE update_id = 2",
                    ).use { statement ->
                        statement.executeQuery().use { resultSet ->
                            resultSet.next()
                            assertEquals(0, resultSet.getInt(1))
                        }
                    }
                }

                val denialLogs = appender.list.joinToString("\n") { it.formattedMessage }
                assertTrue(denialLogs.contains("source=webhook"))
                assertTrue(denialLogs.contains("reason=ACTOR_NOT_ALLOWED"))
                assertFalse(denialLogs.contains(DENIED_USER_ID.toString()))
                assertFalse(denialLogs.contains(ALLOWED_USER_ID.toString()))
                assertFalse(denialLogs.contains(PAYLOAD_SENTINEL))
                assertFalse(denialLogs.contains(SENSITIVE_BOT_TOKEN))
                assertFalse(denialLogs.contains(SENSITIVE_WEBHOOK_SECRET))

                val allowedResponse =
                    client.post("/telegram/webhook") {
                        contentType(ContentType.Application.Json)
                        headers { append("X-Telegram-Bot-Api-Secret-Token", SENSITIVE_WEBHOOK_SECRET) }
                        setBody(
                            "{\"update_id\":3,\"message\":{\"message_id\":1," +
                                "\"chat\":{\"id\":$ALLOWED_USER_ID,\"type\":\"private\"}," +
                                "\"from\":{\"id\":$ALLOWED_USER_ID}}}",
                        )
                    }
                assertEquals(HttpStatusCode.OK, allowedResponse.status)
                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM telegram_inbound_updates WHERE update_id = 3",
                    ).use { statement ->
                        statement.executeQuery().use { resultSet ->
                            resultSet.next()
                            assertEquals(1, resultSet.getInt(1))
                        }
                    }
                }
            } finally {
                applicationLogger.detachAppender(appender)
                appender.stop()
            }
        }

    @Test
    fun `malformed webhook payload returns bad request without enqueue or sensitive logs`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val applicationLogger = LoggerFactory.getLogger("Application") as Logger
            applicationLogger.addAppender(appender)
            try {
                environment {
                    config =
                        MapApplicationConfig(
                            "app.env" to "staging",
                            "api.session.jwtSecret" to "test-secret",
                            "db.jdbcUrl" to database.jdbcUrl,
                            "db.user" to database.user,
                            "db.password" to database.password,
                            "db.maxPoolSize" to "3",
                            "telegram.enabled" to "true",
                            "telegram.token" to SENSITIVE_BOT_TOKEN,
                            "telegram.mode" to "webhook",
                            "telegram.webhookSecretToken" to SENSITIVE_WEBHOOK_SECRET,
                            "telegram.staffChatLinkSecretPepper" to "pepper",
                            "telegram.trafficPolicy" to "ALLOWLIST",
                            "telegram.allowedUserIds" to ALLOWED_USER_ID.toString(),
                            "telegram.allowedChatIds" to ALLOWED_USER_ID.toString(),
                        )
                }
                application {
                    moduleWithOverrides(
                        ModuleOverrides(telegramCommandMenuConfigurator = {}),
                    )
                }

                val response =
                    client.post("/telegram/webhook") {
                        contentType(ContentType.Application.Json)
                        headers { append("X-Telegram-Bot-Api-Secret-Token", SENSITIVE_WEBHOOK_SECRET) }
                        setBody(
                            "{\"update_id\":4,\"message\":{\"message_id\":1," +
                                "\"chat\":{\"id\":$ALLOWED_USER_ID,\"type\":\"private\"}," +
                                "\"from\":{\"id\":$ALLOWED_USER_ID}," +
                                "\"text\":\"$MALFORMED_PAYLOAD_SENTINEL\"}",
                        )
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)

                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM telegram_inbound_updates WHERE update_id = 4",
                    ).use { statement ->
                        statement.executeQuery().use { resultSet ->
                            resultSet.next()
                            assertEquals(0, resultSet.getInt(1))
                        }
                    }
                }

                val logs = appender.list.joinToString("\n") { it.formattedMessage }
                assertTrue(logs.contains("errorType=invalid_payload"))
                assertFalse(logs.contains(ALLOWED_USER_ID.toString()))
                assertFalse(logs.contains(MALFORMED_PAYLOAD_SENTINEL))
                assertFalse(logs.contains(SENSITIVE_BOT_TOKEN))
                assertFalse(logs.contains(SENSITIVE_WEBHOOK_SECRET))
            } finally {
                applicationLogger.detachAppender(appender)
                appender.stop()
            }
        }

    private companion object {
        const val ALLOWED_USER_ID = 711111111111111111L
        const val DENIED_USER_ID = 722222222222222222L
        const val SENSITIVE_BOT_TOKEN = "777777:SENSITIVE_WEBHOOK_BOT_TOKEN"
        const val SENSITIVE_WEBHOOK_SECRET = "SENSITIVE_WEBHOOK_SECRET"
        const val PAYLOAD_SENTINEL = "WEBHOOK_PAYLOAD_SENTINEL"
        const val MALFORMED_PAYLOAD_SENTINEL = "MALFORMED_WEBHOOK_PAYLOAD_SENTINEL"
    }
}
