package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.db.TelegramInboundUpdateQueueRepository
import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TelegramQueueDepthMetricsTest {
    @Test
    fun `inbound queue depth increases after enqueue`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val repository = TelegramInboundUpdateQueueRepository(dataSource)

            assertEquals(0L, repository.queueDepth())
            repository.enqueue(1001L, "{\"update_id\":1001}")
            assertEquals(1L, repository.queueDepth())

            dataSource.close()
        }

    @Test
    fun `outbound queue depth increases after enqueue`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val repository = TelegramOutboxRepository(dataSource)
            val enqueuer = TelegramOutboxEnqueuer(repository, Json { ignoreUnknownKeys = true })

            assertEquals(0L, repository.queueDepth())
            enqueuer.enqueueSendMessage(chatId = 321L, text = "hello")
            assertEquals(1L, repository.queueDepth())

            dataSource.close()
        }
}
