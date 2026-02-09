package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import com.hookah.platform.backend.telegram.db.TelegramOutboxStatus
import com.hookah.platform.backend.test.PostgresTestEnv
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
}
