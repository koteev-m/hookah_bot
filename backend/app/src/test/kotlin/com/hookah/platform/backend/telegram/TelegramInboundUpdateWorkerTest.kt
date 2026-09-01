package com.hookah.platform.backend.telegram

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.hookah.platform.backend.maintenance.StagingMaintenancePolicy
import com.hookah.platform.backend.telegram.db.TelegramInboundUpdateQueueRepository
import com.hookah.platform.backend.telegram.db.TelegramInboundUpdateStatus
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelegramInboundUpdateWorkerTest {
    @Test
    fun `maintenance queued defense leaves denied row unchanged and processes allowed row behind it`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val repository = TelegramInboundUpdateQueueRepository(dataSource)
            val processedUpdateIds = mutableListOf<Long>()
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val workerLogger = LoggerFactory.getLogger(TelegramInboundUpdateWorker::class.java) as Logger
            workerLogger.addAppender(appender)
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
                TelegramInboundUpdateWorker(
                    repository = repository,
                    processUpdate = { update -> processedUpdateIds += update.updateId },
                    json = Json { ignoreUnknownKeys = true },
                    scope = CoroutineScope(Dispatchers.IO),
                    trafficPolicy = TelegramTrafficPolicy.product(),
                    maintenancePolicy = maintenancePolicy,
                )
            try {
                repository.enqueue(49, "{malformed-$MAINTENANCE_PAYLOAD_SENTINEL")
                repository.enqueue(
                    50,
                    """
                    {"update_id":50,"message":{"message_id":1,"chat":{"id":202,"type":"private"},
                    "from":{"id":202},
                    "text":"$MAINTENANCE_PAYLOAD_SENTINEL token=$MAINTENANCE_TOKEN_SENTINEL"}}
                    """.trimIndent().replace("\n", ""),
                )
                repository.enqueue(
                    51,
                    """
                    {"update_id":51,"message":{"message_id":1,"chat":{"id":101,"type":"private"},
                    "from":{"id":101},"text":"allowed"}}
                    """.trimIndent().replace("\n", ""),
                )
                val malformedBefore = inboundSnapshot(database.jdbcUrl, database.user, database.password, 49L)
                val deniedBefore = inboundSnapshot(database.jdbcUrl, database.user, database.password, 50L)

                assertTrue(worker.processOnce())

                assertEquals(listOf(51L), processedUpdateIds)
                assertEquals(malformedBefore, inboundSnapshot(database.jdbcUrl, database.user, database.password, 49L))
                assertEquals(deniedBefore, inboundSnapshot(database.jdbcUrl, database.user, database.password, 50L))
                assertEquals(
                    TelegramInboundUpdateStatus.PROCESSED.name,
                    inboundSnapshot(database.jdbcUrl, database.user, database.password, 51L).status,
                )
                val logs = appender.list.joinToString("\n") { it.formattedMessage }
                assertTrue(logs.contains("reason=MAINTENANCE_INVALID_PAYLOAD"))
                assertTrue(logs.contains("reason=MAINTENANCE_ACTOR_NOT_ALLOWED"))
                assertFalse(logs.contains(MAINTENANCE_PAYLOAD_SENTINEL))
                assertFalse(logs.contains(MAINTENANCE_TOKEN_SENTINEL))
            } finally {
                workerLogger.detachAppender(appender)
                appender.stop()
                dataSource.close()
            }
        }

    @Test
    fun `worker processes queued update`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val repository = TelegramInboundUpdateQueueRepository(dataSource)
            val processedUpdateIds = mutableListOf<Long>()
            val json = Json { ignoreUnknownKeys = true }
            val worker =
                TelegramInboundUpdateWorker(
                    repository = repository,
                    processUpdate = { update -> processedUpdateIds += update.updateId },
                    json = json,
                    scope = CoroutineScope(Dispatchers.IO),
                    trafficPolicy = TelegramTrafficPolicy.unrestricted(),
                )

            repository.enqueue(42, """{"update_id":42}""")
            worker.processOnce()

            assertEquals(listOf(42L), processedUpdateIds)

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
    fun `worker does not route historical queued update denied by policy`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val workerLogger = LoggerFactory.getLogger(TelegramInboundUpdateWorker::class.java) as Logger
            workerLogger.addAppender(appender)
            val repository = TelegramInboundUpdateQueueRepository(dataSource)
            val processedUpdateIds = mutableListOf<Long>()
            val policy =
                TelegramTrafficPolicy.from(
                    MapApplicationConfig(
                        "telegram.trafficPolicy" to "ALLOWLIST",
                        "telegram.allowedUserIds" to "101",
                        "telegram.allowedChatIds" to "101",
                    ),
                    "staging",
                )
            val worker =
                TelegramInboundUpdateWorker(
                    repository = repository,
                    processUpdate = { update -> processedUpdateIds += update.updateId },
                    json = Json { ignoreUnknownKeys = true },
                    scope = CoroutineScope(Dispatchers.IO),
                    trafficPolicy = policy,
                )

            try {
                repository.enqueue(
                    43,
                    "{\"update_id\":43,\"message\":{\"message_id\":1," +
                        "\"chat\":{\"id\":$SENSITIVE_DENIED_USER_ID,\"type\":\"private\"}," +
                        "\"from\":{\"id\":$SENSITIVE_DENIED_USER_ID}," +
                        "\"text\":\"$PAYLOAD_SENTINEL token=$SENSITIVE_TOKEN\"}}",
                )
                worker.processOnce()

                assertTrue(processedUpdateIds.isEmpty())
                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        "SELECT status FROM telegram_inbound_updates WHERE update_id = 43",
                    ).use { statement ->
                        statement.executeQuery().use { resultSet ->
                            resultSet.next()
                            assertEquals(TelegramInboundUpdateStatus.PROCESSED.name, resultSet.getString("status"))
                        }
                    }
                }
                val logs = appender.list.joinToString("\n") { it.formattedMessage }
                assertTrue(logs.contains("source=webhook_queue"))
                assertTrue(logs.contains("reason=ACTOR_NOT_ALLOWED"))
                assertFalse(logs.contains(SENSITIVE_DENIED_USER_ID.toString()))
                assertFalse(logs.contains(PAYLOAD_SENTINEL))
                assertFalse(logs.contains(SENSITIVE_TOKEN))
            } finally {
                workerLogger.detachAppender(appender)
                appender.stop()
                dataSource.close()
            }
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

            val firstLockUntil = now.plus(visibilityTimeout)
            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "SELECT status, attempts, next_attempt_at FROM telegram_inbound_updates WHERE update_id = 101",
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        assertEquals(TelegramInboundUpdateStatus.PROCESSING.name, resultSet.getString("status"))
                        assertEquals(1, resultSet.getInt("attempts"))
                        val nextAttemptAt = resultSet.getTimestamp("next_attempt_at")
                        assertNotNull(nextAttemptAt)
                        assertEquals(Timestamp.from(firstLockUntil), nextAttemptAt)
                    }
                }
            }

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

    private fun inboundSnapshot(
        jdbcUrl: String,
        user: String,
        password: String,
        updateId: Long,
    ): InboundSnapshot =
        DriverManager.getConnection(jdbcUrl, user, password).use { connection ->
            connection.prepareStatement(
                """
                SELECT status, attempts, last_error, processed_at, next_attempt_at, payload_json
                FROM telegram_inbound_updates
                WHERE update_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, updateId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    InboundSnapshot(
                        status = resultSet.getString("status"),
                        attempts = resultSet.getInt("attempts"),
                        lastError = resultSet.getString("last_error"),
                        processedAt = resultSet.getTimestamp("processed_at")?.toInstant(),
                        nextAttemptAt = resultSet.getTimestamp("next_attempt_at")?.toInstant(),
                        payloadJson = resultSet.getString("payload_json"),
                    )
                }
            }
        }

    private data class InboundSnapshot(
        val status: String,
        val attempts: Int,
        val lastError: String?,
        val processedAt: Instant?,
        val nextAttemptAt: Instant?,
        val payloadJson: String,
    )

    private companion object {
        const val MAINTENANCE_PAYLOAD_SENTINEL = "maintenance-private-payload"
        const val MAINTENANCE_TOKEN_SENTINEL = "maintenance-private-token"
        const val SENSITIVE_DENIED_USER_ID = 202L
        const val PAYLOAD_SENTINEL = "INBOUND_PAYLOAD_SENTINEL"
        const val SENSITIVE_TOKEN = "777777:SENSITIVE_INBOUND_TOKEN"
    }
}
