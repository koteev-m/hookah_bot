package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.db.StaffChatNotificationRepository
import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import com.hookah.platform.backend.telegram.db.TelegramOutboxStatus
import com.hookah.platform.backend.test.PostgresTestEnv
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TelegramOutboxWorkerTest {
    @Test
    fun `worker sends queued message`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val repository = TelegramOutboxRepository(dataSource)
            val json = Json { ignoreUnknownKeys = true }
            val outboxEnqueuer = TelegramOutboxEnqueuer(repository, json)
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val rateLimiter = TelegramRateLimiter { }
            val config = TelegramOutboxConfig(batchSize = 1, maxConcurrency = 1)
            val worker =
                TelegramOutboxWorker(
                    repository = repository,
                    apiClientProvider = { apiClient },
                    json = json,
                    rateLimiter = rateLimiter,
                    config = config,
                    scope = CoroutineScope(Dispatchers.IO),
                )

            outboxEnqueuer.enqueueSendMessage(123L, "hello")
            coEvery { apiClient.callMethod("sendMessage", any()) } returns
                TelegramCallResult.Success(JsonNull)

            worker.processOnce()

            coVerify(exactly = 1) { apiClient.callMethod("sendMessage", any()) }

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "SELECT status FROM telegram_outbox WHERE chat_id = 123",
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        assertEquals(TelegramOutboxStatus.SENT.name, resultSet.getString("status"))
                    }
                }
            }

            dataSource.close()
        }

    @Test
    fun `retry is scheduled on 429`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val repository = TelegramOutboxRepository(dataSource)
            val json = Json { ignoreUnknownKeys = true }
            val outboxEnqueuer = TelegramOutboxEnqueuer(repository, json)
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val rateLimiter = TelegramRateLimiter { }
            val fixedNow = Instant.parse("2024-02-10T12:00:00Z")
            val config = TelegramOutboxConfig(batchSize = 1, maxConcurrency = 1, minBackoffSeconds = 1)
            val worker =
                TelegramOutboxWorker(
                    repository = repository,
                    apiClientProvider = { apiClient },
                    json = json,
                    rateLimiter = rateLimiter,
                    config = config,
                    scope = CoroutineScope(Dispatchers.IO),
                    nowProvider = { fixedNow },
                )

            outboxEnqueuer.enqueueSendMessage(999L, "hello")
            coEvery { apiClient.callMethod("sendMessage", any()) } returns
                TelegramCallResult.Failure(
                    errorCode = 429,
                    description = "Too Many Requests",
                    retryAfterSeconds = 5,
                )

            worker.processOnce()

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "SELECT status, next_attempt_at FROM telegram_outbox WHERE chat_id = 999",
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        assertEquals(TelegramOutboxStatus.NEW.name, resultSet.getString("status"))
                        val nextAttempt = resultSet.getTimestamp("next_attempt_at")
                        assertNotNull(nextAttempt)
                        assertEquals(Timestamp.from(fixedNow.plusSeconds(5)), nextAttempt)
                    }
                }
            }

            dataSource.close()
        }

    @Test
    fun `sendMessage permanent failure is marked failed without retry`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val repository = TelegramOutboxRepository(dataSource)
            val json = Json { ignoreUnknownKeys = true }
            val outboxEnqueuer = TelegramOutboxEnqueuer(repository, json)
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val worker =
                TelegramOutboxWorker(
                    repository = repository,
                    apiClientProvider = { apiClient },
                    json = json,
                    rateLimiter = TelegramRateLimiter { },
                    config = TelegramOutboxConfig(batchSize = 1, maxConcurrency = 1),
                    scope = CoroutineScope(Dispatchers.IO),
                )

            outboxEnqueuer.enqueueSendMessage(456L, "hello")
            coEvery { apiClient.callMethod("sendMessage", any()) } returns
                TelegramCallResult.Failure(
                    errorCode = 403,
                    description = "Forbidden",
                    retryAfterSeconds = null,
                )

            worker.processOnce()

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "SELECT status, next_attempt_at FROM telegram_outbox WHERE chat_id = 456",
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        assertEquals(TelegramOutboxStatus.FAILED.name, resultSet.getString("status"))
                        assertNull(resultSet.getTimestamp("next_attempt_at"))
                    }
                }
            }

            dataSource.close()
        }

    @Test
    fun `answerCallbackQuery failure is marked failed without retry`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val repository = TelegramOutboxRepository(dataSource)
            val json = Json { ignoreUnknownKeys = true }
            val outboxEnqueuer = TelegramOutboxEnqueuer(repository, json)
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val worker =
                TelegramOutboxWorker(
                    repository = repository,
                    apiClientProvider = { apiClient },
                    json = json,
                    rateLimiter = TelegramRateLimiter { },
                    config = TelegramOutboxConfig(batchSize = 1, maxConcurrency = 1),
                    scope = CoroutineScope(Dispatchers.IO),
                )

            outboxEnqueuer.enqueueAnswerCallbackQuery(321L, "callback-id")
            coEvery { apiClient.callMethod("answerCallbackQuery", any()) } returns
                TelegramCallResult.Failure(
                    errorCode = 429,
                    description = "Too Many Requests",
                    retryAfterSeconds = 3,
                )

            worker.processOnce()

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "SELECT status, next_attempt_at FROM telegram_outbox WHERE method = 'answerCallbackQuery'",
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        assertEquals(TelegramOutboxStatus.FAILED.name, resultSet.getString("status"))
                        assertNull(resultSet.getTimestamp("next_attempt_at"))
                    }
                }
            }

            dataSource.close()
        }

    @Test
    fun `live order sendMessage stores returned message id`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val repository = TelegramOutboxRepository(dataSource)
            val staffChatRepository = StaffChatNotificationRepository(dataSource)
            val json = Json { ignoreUnknownKeys = true }
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val worker =
                TelegramOutboxWorker(
                    repository = repository,
                    apiClientProvider = { apiClient },
                    json = json,
                    rateLimiter = TelegramRateLimiter { },
                    config = TelegramOutboxConfig(batchSize = 1, maxConcurrency = 1),
                    scope = CoroutineScope(Dispatchers.IO),
                )
            val orderId =
                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    createLiveOrderFixture(connection)
                }
            val payload =
                json.encodeToString(
                    SendMessagePayload.serializer(),
                    SendMessagePayload(chatId = 777L, text = "live bill"),
                )
            staffChatRepository.enqueueOrderMessage(
                orderId = orderId,
                venueId = 1L,
                chatId = 777L,
                method = "sendMessage",
                payloadJson = payload,
            )
            coEvery { apiClient.callMethod("sendMessage", any()) } returns
                TelegramCallResult.Success(messageIdResult(333L))

            worker.processOnce()

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "SELECT message_id FROM telegram_staff_chat_order_messages WHERE order_id = ?",
                ).use { statement ->
                    statement.setLong(1, orderId)
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        assertEquals(333L, resultSet.getLong("message_id"))
                    }
                }
            }

            dataSource.close()
        }

    @Test
    fun `live order edit failure queues fallback message and stores fallback message id`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val repository = TelegramOutboxRepository(dataSource)
            val staffChatRepository = StaffChatNotificationRepository(dataSource)
            val json = Json { ignoreUnknownKeys = true }
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val worker =
                TelegramOutboxWorker(
                    repository = repository,
                    apiClientProvider = { apiClient },
                    json = json,
                    rateLimiter = TelegramRateLimiter { },
                    config = TelegramOutboxConfig(batchSize = 1, maxConcurrency = 1),
                    scope = CoroutineScope(Dispatchers.IO),
                )
            val orderId =
                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    createLiveOrderFixture(connection)
                }
            staffChatRepository.upsertOrderMessage(
                orderId = orderId,
                venueId = 1L,
                chatId = 777L,
                messageId = 111L,
            )
            val payload =
                json.encodeToString(
                    EditMessageTextPayload.serializer(),
                    EditMessageTextPayload(chatId = 777L, messageId = 111L, text = "fresh bill"),
                )
            staffChatRepository.enqueueOrderMessage(
                orderId = orderId,
                venueId = 1L,
                chatId = 777L,
                method = "editMessageText",
                payloadJson = payload,
            )
            coEvery { apiClient.callMethod("editMessageText", any()) } returns
                TelegramCallResult.Failure(
                    errorCode = 400,
                    description = "Bad Request: message to edit not found",
                    retryAfterSeconds = null,
                )
            coEvery { apiClient.callMethod("sendMessage", any()) } returns
                TelegramCallResult.Success(messageIdResult(444L))

            worker.processOnce()
            worker.processOnce()

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "SELECT message_id FROM telegram_staff_chat_order_messages WHERE order_id = ?",
                ).use { statement ->
                    statement.setLong(1, orderId)
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        assertEquals(444L, resultSet.getLong("message_id"))
                    }
                }
                connection.prepareStatement(
                    """
                    SELECT COUNT(*) AS fallback_count
                    FROM telegram_outbox
                    WHERE method = 'sendMessage'
                      AND payload_json LIKE '%fresh bill%'
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        assertEquals(1L, resultSet.getLong("fallback_count"))
                    }
                }
            }

            dataSource.close()
        }

    private fun messageIdResult(messageId: Long): JsonObject =
        buildJsonObject {
            put("message_id", messageId)
        }

    private fun createLiveOrderFixture(connection: Connection): Long {
        connection.prepareStatement(
            """
            INSERT INTO users (telegram_user_id, first_name)
            VALUES (1001, 'Guest')
            ON CONFLICT (telegram_user_id) DO NOTHING
            """.trimIndent(),
        ).use { it.executeUpdate() }
        val venueId =
            connection.prepareStatement(
                """
                INSERT INTO venues (name, status, staff_chat_id)
                VALUES ('Venue', 'active_published', 777)
                RETURNING id
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    resultSet.getLong("id")
                }
            }
        val tableId =
            connection.prepareStatement(
                """
                INSERT INTO venue_tables (venue_id, table_number)
                VALUES (?, 1)
                RETURNING id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    resultSet.getLong("id")
                }
            }
        return connection.prepareStatement(
            """
            INSERT INTO orders (venue_id, table_id, status)
            VALUES (?, ?, 'ACTIVE')
            RETURNING id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getLong("id")
            }
        }
    }
}
