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
import kotlin.test.assertEquals

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
}
