package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.db.TelegramInboundUpdateQueueRepository
import com.hookah.platform.backend.telegram.db.TelegramInboundUpdateStatus
import com.hookah.platform.backend.test.PostgresTestEnv
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TelegramInboundUpdateWorkerTest {
    @Test
    fun `worker processes queued update`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val repository = TelegramInboundUpdateQueueRepository(dataSource)
            val router: TelegramBotRouter = mockk(relaxed = true)
            val json = Json { ignoreUnknownKeys = true }
            val worker =
                TelegramInboundUpdateWorker(
                    repository = repository,
                    router = router,
                    json = json,
                    scope = CoroutineScope(Dispatchers.IO),
                )

            repository.enqueue(42, """{"update_id":42}""")
            worker.processOnce()

            coVerify(exactly = 1) { router.process(match { it.updateId == 42L }) }

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "SELECT status FROM telegram_inbound_updates WHERE update_id = 42",
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        assertEquals(TelegramInboundUpdateStatus.PROCESSED.name, resultSet.getString("status"))
                    }
                }
            }

            dataSource.close()
        }

    @Test
    fun `reclaims processing after visibility timeout`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val repository = TelegramInboundUpdateQueueRepository(dataSource)
            val now = Instant.parse("2024-02-10T12:00:00Z")
            val visibilityTimeout = Duration.ofMinutes(2)

            repository.enqueue(101, """{"update_id":101}""")

            val firstClaim = repository.claimBatch(1, now, visibilityTimeout)
            assertEquals(1, firstClaim.size)
            assertEquals(1, firstClaim.first().attempts)

            val later = now.plus(visibilityTimeout).plusSeconds(1)
            val secondClaim = repository.claimBatch(1, later, visibilityTimeout)
            assertEquals(1, secondClaim.size)
            assertEquals(2, secondClaim.first().attempts)

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "SELECT status, attempts, next_attempt_at FROM telegram_inbound_updates WHERE update_id = 101",
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        assertEquals(TelegramInboundUpdateStatus.PROCESSING.name, resultSet.getString("status"))
                        assertEquals(2, resultSet.getInt("attempts"))
                        val nextAttemptAt = resultSet.getTimestamp("next_attempt_at")
                        assertNotNull(nextAttemptAt)
                        assertEquals(Timestamp.from(later.plus(visibilityTimeout)), nextAttemptAt)
                    }
                }
            }

            dataSource.close()
        }
}
