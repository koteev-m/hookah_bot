package com.hookah.platform.backend.telegram

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.hookah.platform.backend.maintenance.StagingMaintenancePolicy
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramLongPollingWorkerTest {
    @Test
    fun `maintenance denial advances offset before router`() =
        runBlocking {
            val processed = mutableListOf<Long>()
            val maintenancePolicy =
                StagingMaintenancePolicy.from(
                    MapApplicationConfig(
                        "staging.maintenance.mode" to "V126_SMOKE",
                        "staging.maintenance.allowedUserIds" to "101",
                        "staging.maintenance.allowedChatIds" to "101,-100500",
                    ),
                    "staging",
                )
            val worker =
                TelegramLongPollingWorker(
                    getUpdates = { _, _ -> emptyList() },
                    getWebhookUrl = { "" },
                    processUpdate = { update -> processed += update.updateId },
                    trafficPolicy = TelegramTrafficPolicy.product(),
                    maintenancePolicy = maintenancePolicy,
                    timeoutSeconds = 1,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                )

            worker.processBatch(
                listOf(
                    privateUpdate(updateId = 30, userId = 999),
                    privateUpdate(updateId = 31, userId = 101),
                ),
            )

            assertEquals(listOf(31L), processed)
            assertEquals(32L, worker.currentOffset())
        }

    @Test
    fun `denied updates advance offset without reaching router`() =
        runBlocking {
            val processed = mutableListOf<Long>()
            val worker = worker(processed)

            worker.processBatch(
                listOf(
                    privateUpdate(updateId = 12, userId = 999),
                    privateUpdate(updateId = 10, userId = 101),
                    privateUpdate(updateId = 11, userId = 999),
                ),
            )

            assertEquals(listOf(10L), processed)
            assertEquals(13L, worker.currentOffset())
        }

    @Test
    fun `router failure preserves offset and defers later updates for retry`() =
        runBlocking {
            val processed = mutableListOf<Long>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val worker =
                TelegramLongPollingWorker(
                    getUpdates = { _, _ -> emptyList() },
                    getWebhookUrl = { "" },
                    processUpdate = { update ->
                        processed += update.updateId
                        if (update.updateId == 20L) error("sentinel payload must not be logged")
                    },
                    trafficPolicy = allowlist(),
                    timeoutSeconds = 1,
                    scope = scope,
                )

            worker.processBatch(
                listOf(
                    privateUpdate(updateId = 19, userId = 101),
                    privateUpdate(updateId = 20, userId = 101),
                    privateUpdate(updateId = 21, userId = 101),
                ),
            )

            assertEquals(listOf(19L, 20L), processed)
            assertEquals(20L, worker.currentOffset())
            scope.cancel()
        }

    @Test
    fun `configured webhook blocks long polling preflight`() =
        runBlocking {
            var updateFetchAttempted = false
            val worker =
                TelegramLongPollingWorker(
                    getUpdates = { _, _ ->
                        updateFetchAttempted = true
                        emptyList()
                    },
                    getWebhookUrl = { "https://example.invalid/telegram" },
                    processUpdate = {},
                    trafficPolicy = allowlist(),
                    timeoutSeconds = 1,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                )

            val error = assertFailsWith<IllegalStateException> { worker.start() }
            assertEquals("Telegram long polling preflight failed", error.message)
            assertFalse(updateFetchAttempted)
        }

    @Test
    fun `preflight failure logs no webhook URL token or provider payload`() =
        runBlocking {
            val tokenSentinel = "token-sentinel-711111111111111111"
            val webhookSentinel = "https://example.invalid/sentinel-payload-1002222222222"
            val logger = LoggerFactory.getLogger(TelegramLongPollingWorker::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(appender)
            val originalLevel = logger.level
            logger.level = Level.TRACE
            try {
                val worker =
                    TelegramLongPollingWorker(
                        getUpdates = { _, _ -> emptyList() },
                        getWebhookUrl = { throw IllegalStateException("$tokenSentinel $webhookSentinel") },
                        processUpdate = {},
                        trafficPolicy = allowlist(),
                        timeoutSeconds = 1,
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    )

                val error = assertFailsWith<IllegalStateException> { worker.start() }
                assertEquals("Telegram long polling preflight failed", error.message)
                val logs = appender.list.joinToString("\n") { it.formattedMessage }
                assertTrue(logs.contains("Telegram long polling preflight failed"))
                assertFalse(logs.contains(tokenSentinel))
                assertFalse(logs.contains(webhookSentinel))
                assertFalse(logs.contains("711111111111111111"))
                assertFalse(logs.contains("-1002222222222"))
            } finally {
                logger.detachAppender(appender)
                logger.level = originalLevel
                appender.stop()
            }
        }

    @Test
    fun `only one long poller can run in a JVM and lease is released after stop`() =
        runBlocking {
            val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val first = blockingWorker(firstScope)
            val firstJob = first.start()

            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val second = blockingWorker(secondScope)
            assertFailsWith<IllegalStateException> { second.start() }

            firstJob.cancelAndJoin()
            val restartedJob = second.start()
            restartedJob.cancelAndJoin()
            firstScope.cancel()
            secondScope.cancel()
        }

    private fun worker(processed: MutableList<Long>): TelegramLongPollingWorker {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return TelegramLongPollingWorker(
            getUpdates = { _, _ -> emptyList() },
            getWebhookUrl = { "" },
            processUpdate = { update -> processed += update.updateId },
            trafficPolicy = allowlist(),
            timeoutSeconds = 1,
            scope = scope,
        )
    }

    private fun blockingWorker(scope: CoroutineScope): TelegramLongPollingWorker =
        TelegramLongPollingWorker(
            getUpdates = { _, _ -> awaitCancellation() },
            getWebhookUrl = { "" },
            processUpdate = {},
            trafficPolicy = allowlist(),
            timeoutSeconds = 1,
            scope = scope,
        )

    private fun allowlist(): TelegramTrafficPolicy =
        TelegramTrafficPolicy.from(
            MapApplicationConfig(
                "telegram.trafficPolicy" to "ALLOWLIST",
                "telegram.allowedUserIds" to "101",
                "telegram.allowedChatIds" to "101,-100500",
            ),
            "staging",
        )

    private fun privateUpdate(
        updateId: Long,
        userId: Long,
    ): TelegramUpdate =
        TelegramUpdate(
            updateId = updateId,
            message =
                Message(
                    messageId = updateId,
                    chat = Chat(id = userId, type = "private"),
                    fromUser = User(id = userId),
                ),
        )
}
