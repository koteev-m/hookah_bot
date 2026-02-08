package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
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
            application { module() }

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
            application { module() }

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
}
