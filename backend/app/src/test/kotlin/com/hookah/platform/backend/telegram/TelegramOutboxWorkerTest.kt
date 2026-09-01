package com.hookah.platform.backend.telegram

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.hookah.platform.backend.maintenance.StagingMaintenancePolicy
import com.hookah.platform.backend.metrics.AppMetrics
import com.hookah.platform.backend.telegram.db.StaffChatNotificationClaim
import com.hookah.platform.backend.telegram.db.StaffChatNotificationRepository
import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import com.hookah.platform.backend.telegram.db.TelegramOutboxStatus
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.server.config.MapApplicationConfig
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
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramOutboxWorkerTest {
    private val trafficPolicy = TelegramTrafficPolicy.unrestricted()

    @Test
    fun `maintenance claim leaves denied rows byte identical and reaches later allowed rows`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val productPolicy = TelegramTrafficPolicy.product()
            val maintenancePolicy =
                StagingMaintenancePolicy.from(
                    MapApplicationConfig(
                        "staging.maintenance.mode" to "V126_SMOKE",
                        "staging.maintenance.allowedUserIds" to "123",
                        "staging.maintenance.allowedChatIds" to "123,-100123",
                    ),
                    "staging",
                )
            val repository = TelegramOutboxRepository(dataSource, productPolicy, maintenancePolicy)
            val enqueuer =
                TelegramOutboxEnqueuer(
                    repository,
                    Json { ignoreUnknownKeys = true },
                    productPolicy,
                    maintenancePolicy,
                )
            val now = Instant.parse("2030-01-01T00:00:00Z")

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "INSERT INTO users (telegram_user_id, first_name) " +
                        "VALUES (123, 'Allowed'), (888, 'Denied'), (999, 'Denied')",
                ).use { it.executeUpdate() }
                connection.prepareStatement(
                    """
                    INSERT INTO telegram_outbox (
                        chat_id, method, payload_json, status, attempts, last_error,
                        created_at, processed_at, next_attempt_at
                    )
                    VALUES
                        (999, 'sendMessage', '{"chat_id":999,"text":"denied-new"}',
                         'NEW', 2, 'preserve-new', TIMESTAMPTZ '2025-01-01 00:00:00Z', NULL, NULL),
                        (888, 'sendMessage', '{"chat_id":888,"text":"denied-sending"}',
                         'SENDING', 4, 'preserve-sending', TIMESTAMPTZ '2025-01-02 00:00:00Z',
                         TIMESTAMPTZ '2025-01-03 00:00:00Z', TIMESTAMPTZ '2025-01-04 00:00:00Z')
                    """.trimIndent(),
                ).use { it.executeUpdate() }
            }
            val deniedBefore = outboxSnapshots(database.jdbcUrl, database.user, database.password, setOf(888L, 999L))

            assertEquals(
                TelegramOutboxEnqueueOutcome.ENQUEUED,
                enqueuer.enqueueSendMessage(123L, "allowed behind denied"),
            )
            assertEquals(
                TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY,
                enqueuer.enqueueSendMessage(999L, "must not be inserted"),
            )

            val claimed = repository.claimBatch(10, now, java.time.Duration.ofMinutes(2))

            assertEquals(listOf(123L), claimed.map { it.chatId })
            assertEquals(
                deniedBefore,
                outboxSnapshots(database.jdbcUrl, database.user, database.password, setOf(888L, 999L)),
            )
            dataSource.close()
        }

    @Test
    fun `zero chat callback row is never claimed in unrestricted mode`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val repository = TelegramOutboxRepository(dataSource, trafficPolicy)
            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO telegram_outbox (
                        chat_id, method, payload_json, status, attempts, last_error, created_at
                    ) VALUES (
                        0, 'answerCallbackQuery', '{"callback_query_id":"historic"}',
                        'NEW', 0, 'preserve-zero', TIMESTAMPTZ '2025-01-01 00:00:00Z'
                    )
                    """.trimIndent(),
                ).use { it.executeUpdate() }
            }
            val before = outboxSnapshots(database.jdbcUrl, database.user, database.password, setOf(0L))

            val claimed =
                repository.claimBatch(
                    limit = 10,
                    now = Instant.parse("2030-01-01T00:00:00Z"),
                    visibilityTimeout = java.time.Duration.ofMinutes(2),
                )

            assertEquals(emptyList(), claimed)
            assertEquals(before, outboxSnapshots(database.jdbcUrl, database.user, database.password, setOf(0L)))
            dataSource.close()
        }

    @Test
    fun `claim skips disallowed rows without mutation and does not starve allowed rows`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val restrictedPolicy =
                TelegramTrafficPolicy.from(
                    MapApplicationConfig(
                        "telegram.trafficPolicy" to "ALLOWLIST",
                        "telegram.allowedUserIds" to "123",
                        "telegram.allowedChatIds" to "123",
                    ),
                    appEnv = "staging",
                )
            val repository = TelegramOutboxRepository(dataSource, restrictedPolicy)
            val json = Json { ignoreUnknownKeys = true }
            val outboxEnqueuer = TelegramOutboxEnqueuer(repository, json, restrictedPolicy)
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val fixedNow = Instant.parse("2030-01-01T00:00:00Z")
            val worker =
                TelegramOutboxWorker(
                    repository = repository,
                    apiClientProvider = { apiClient },
                    json = json,
                    rateLimiter = TelegramRateLimiter { },
                    config = TelegramOutboxConfig(batchSize = 10, maxConcurrency = 1),
                    scope = CoroutineScope(Dispatchers.IO),
                    nowProvider = { fixedNow },
                )

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO telegram_outbox (
                        chat_id, method, payload_json, status, attempts, last_error,
                        created_at, processed_at, next_attempt_at
                    )
                    VALUES
                        (999, 'sendMessage', '{"chat_id":999,"text":"blocked-new"}',
                         'NEW', 2, 'preserve-new', TIMESTAMPTZ '2025-01-01 00:00:00Z', NULL, NULL),
                        (888, 'sendMessage', '{"chat_id":888,"text":"blocked-sending"}',
                         'SENDING', 4, 'preserve-sending', TIMESTAMPTZ '2025-01-02 00:00:00Z',
                         TIMESTAMPTZ '2025-01-03 00:00:00Z', TIMESTAMPTZ '2025-01-04 00:00:00Z')
                    """.trimIndent(),
                ).use { it.executeUpdate() }
            }
            val before = outboxSnapshots(database.jdbcUrl, database.user, database.password, setOf(888L, 999L))

            assertEquals(
                TelegramOutboxEnqueueOutcome.ENQUEUED,
                outboxEnqueuer.enqueueSendMessage(123L, "allowed"),
            )
            coEvery { apiClient.dispatchOutbox(123L, "sendMessage", any()) } returns
                TelegramCallResult.Success(JsonNull)

            worker.processOnce()

            val after = outboxSnapshots(database.jdbcUrl, database.user, database.password, setOf(888L, 999L))
            assertEquals(before, after)
            coVerify(exactly = 1) { apiClient.dispatchOutbox(123L, "sendMessage", any()) }
            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement("SELECT status FROM telegram_outbox WHERE chat_id = 123").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        assertEquals(true, resultSet.next())
                        assertEquals(TelegramOutboxStatus.SENT.name, resultSet.getString("status"))
                    }
                }
            }

            dataSource.close()
        }

    @Test
    fun `disallowed producers create no outbox or staff notification state`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val restrictedPolicy =
                TelegramTrafficPolicy.from(
                    MapApplicationConfig(
                        "telegram.trafficPolicy" to "ALLOWLIST",
                        "telegram.allowedUserIds" to "123",
                        "telegram.allowedChatIds" to "123",
                    ),
                    appEnv = "staging",
                )
            val repository = TelegramOutboxRepository(dataSource, restrictedPolicy)
            val enqueuer =
                TelegramOutboxEnqueuer(repository, Json { ignoreUnknownKeys = true }, restrictedPolicy)
            val staffRepository = StaffChatNotificationRepository(dataSource, restrictedPolicy)

            assertEquals(
                TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY,
                enqueuer.enqueueSendMessage(999L, "blocked"),
            )
            val claim =
                staffRepository.tryClaimAndEnqueue(
                    notificationKey = 77L,
                    chatId = -100999L,
                    method = "sendMessage",
                    payloadJson = """{"chat_id":-100999,"text":"blocked"}""",
                )

            assertEquals(StaffChatNotificationClaim.SKIPPED_TRAFFIC_POLICY, claim)
            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM telegram_outbox").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        assertEquals(true, resultSet.next())
                        assertEquals(0L, resultSet.getLong(1))
                    }
                }
                connection.prepareStatement("SELECT COUNT(*) FROM telegram_staff_chat_notifications").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        assertEquals(true, resultSet.next())
                        assertEquals(0L, resultSet.getLong(1))
                    }
                }
            }

            dataSource.close()
        }

    @Test
    fun `product mode enqueues and claims only database-authorized recipients`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val productPolicy =
                TelegramTrafficPolicy.from(
                    MapApplicationConfig("telegram.trafficPolicy" to "PRODUCT"),
                    appEnv = "staging",
                )
            val repository = TelegramOutboxRepository(dataSource, productPolicy)
            val enqueuer = TelegramOutboxEnqueuer(repository, Json { ignoreUnknownKeys = true }, productPolicy)
            val userChatId = 910_001L
            val staffChatId = -100_910_001L

            assertEquals(
                TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY,
                enqueuer.enqueueSendMessage(userChatId, "unknown user"),
            )
            assertEquals(
                TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY,
                enqueuer.enqueueSendMessage(staffChatId, "unknown group"),
            )
            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.autoCommit = false
                assertEquals(
                    TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY,
                    enqueuer.enqueueBookingSendMessageInTransaction(
                        connection = connection,
                        chatId = userChatId,
                        text = "unknown booking user",
                    ),
                )
                connection.rollback()
            }

            val staffVenueId =
                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        "INSERT INTO users (telegram_user_id, first_name) VALUES (?, 'Guest')",
                    ).use { statement ->
                        statement.setLong(1, userChatId)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement(
                        """
                        INSERT INTO venues (name, status, staff_chat_id)
                        VALUES ('Venue', 'PUBLISHED', ?)
                        RETURNING id
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, staffChatId)
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            resultSet.getLong("id")
                        }
                    }
                }

            assertEquals(
                TelegramOutboxEnqueueOutcome.ENQUEUED,
                enqueuer.enqueueSendMessage(userChatId, "known user"),
            )
            assertEquals(
                TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY,
                enqueuer.enqueueSendMessage(staffChatId, "linked group"),
            )
            assertEquals(
                TelegramOutboxEnqueueOutcome.ENQUEUED,
                enqueuer.enqueueVenueSendMessage(staffVenueId, staffChatId, "linked group"),
            )
            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.autoCommit = false
                assertEquals(
                    TelegramOutboxEnqueueOutcome.ENQUEUED,
                    enqueuer.enqueueBookingSendMessageInTransaction(
                        connection = connection,
                        chatId = userChatId,
                        text = "known booking user",
                    ),
                )
                connection.commit()
            }
            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO telegram_outbox (chat_id, method, payload_json)
                    VALUES
                        (910002, 'sendMessage', '{"chat_id":910002,"text":"unknown"}'),
                        (-100910002, 'sendMessage', '{"chat_id":-100910002,"text":"unlinked"}')
                    """.trimIndent(),
                ).use { it.executeUpdate() }
            }

            val claimed =
                repository.claimBatch(
                    limit = 10,
                    now = Instant.parse("2030-01-01T00:00:00Z"),
                    visibilityTimeout = java.time.Duration.ofMinutes(2),
                )

            assertEquals(setOf(userChatId, staffChatId), claimed.map { it.chatId }.toSet())
            assertEquals(3, claimed.size)
            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    """
                    SELECT chat_id, status, attempts
                    FROM telegram_outbox
                    WHERE chat_id IN (910002, -100910002)
                    ORDER BY chat_id
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        repeat(2) {
                            assertTrue(resultSet.next())
                            assertEquals(TelegramOutboxStatus.NEW.name, resultSet.getString("status"))
                            assertEquals(0, resultSet.getInt("attempts"))
                        }
                        assertFalse(resultSet.next())
                    }
                }
            }

            dataSource.close()
        }

    @Test
    fun `product worker rechecks staff chat authority immediately before dispatch`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val productPolicy =
                TelegramTrafficPolicy.from(
                    MapApplicationConfig("telegram.trafficPolicy" to "PRODUCT"),
                    appEnv = "staging",
                )
            val repository = TelegramOutboxRepository(dataSource, productPolicy)
            val enqueuer = TelegramOutboxEnqueuer(repository, Json { ignoreUnknownKeys = true }, productPolicy)
            val staffChatId = -100_915_001L
            val venueId =
                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        """
                        INSERT INTO venues (name, status, staff_chat_id)
                        VALUES ('Venue', 'PUBLISHED', ?)
                        RETURNING id
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, staffChatId)
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            resultSet.getLong("id")
                        }
                    }
                }
            assertEquals(
                TelegramOutboxEnqueueOutcome.ENQUEUED,
                enqueuer.enqueueVenueSendMessage(venueId, staffChatId, "linked group"),
            )
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val worker =
                TelegramOutboxWorker(
                    repository = repository,
                    apiClientProvider = { apiClient },
                    json = Json { ignoreUnknownKeys = true },
                    rateLimiter =
                        TelegramRateLimiter {
                            DriverManager
                                .getConnection(database.jdbcUrl, database.user, database.password)
                                .use { connection ->
                                    connection.prepareStatement(
                                        "UPDATE venues SET staff_chat_id = NULL WHERE staff_chat_id = ?",
                                    ).use { statement ->
                                        statement.setLong(1, staffChatId)
                                        statement.executeUpdate()
                                    }
                                }
                        },
                    config = TelegramOutboxConfig(batchSize = 1, maxConcurrency = 1),
                    scope = CoroutineScope(Dispatchers.IO),
                    nowProvider = { Instant.parse("2030-01-01T00:00:00Z") },
                )

            worker.processOnce()

            coVerify(exactly = 0) { apiClient.dispatchOutbox(any(), any(), any()) }
            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "SELECT status, last_error, next_attempt_at FROM telegram_outbox WHERE chat_id = ?",
                ).use { statement ->
                    statement.setLong(1, staffChatId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(TelegramOutboxStatus.FAILED.name, resultSet.getString("status"))
                        assertEquals(
                            "Telegram outbox recipient no longer authorized",
                            resultSet.getString("last_error"),
                        )
                        assertNull(resultSet.getTimestamp("next_attempt_at"))
                    }
                }
            }

            dataSource.close()
        }

    @Test
    fun `traffic denial is terminal instead of leaving an outbox row sending`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val productPolicy =
                TelegramTrafficPolicy.from(
                    MapApplicationConfig("telegram.trafficPolicy" to "PRODUCT"),
                    appEnv = "staging",
                )
            val repository = TelegramOutboxRepository(dataSource, productPolicy)
            val enqueuer = TelegramOutboxEnqueuer(repository, Json { ignoreUnknownKeys = true }, productPolicy)
            val userChatId = 915_002L
            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "INSERT INTO users (telegram_user_id, first_name) VALUES (?, 'Guest')",
                ).use { statement ->
                    statement.setLong(1, userChatId)
                    statement.executeUpdate()
                }
            }
            assertEquals(
                TelegramOutboxEnqueueOutcome.ENQUEUED,
                enqueuer.enqueueSendMessage(userChatId, "known user"),
            )
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            coEvery { apiClient.dispatchOutbox(userChatId, "sendMessage", any()) } returns
                TelegramCallResult.TrafficDenied
            val worker =
                TelegramOutboxWorker(
                    repository = repository,
                    apiClientProvider = { apiClient },
                    json = Json { ignoreUnknownKeys = true },
                    rateLimiter = TelegramRateLimiter { },
                    config = TelegramOutboxConfig(batchSize = 1, maxConcurrency = 1),
                    scope = CoroutineScope(Dispatchers.IO),
                    nowProvider = { Instant.parse("2030-01-01T00:00:00Z") },
                )

            worker.processOnce()

            coVerify(exactly = 1) { apiClient.dispatchOutbox(userChatId, "sendMessage", any()) }
            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "SELECT status, last_error, next_attempt_at FROM telegram_outbox WHERE chat_id = ?",
                ).use { statement ->
                    statement.setLong(1, userChatId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(TelegramOutboxStatus.FAILED.name, resultSet.getString("status"))
                        assertEquals(
                            "Telegram outbox dispatch denied by traffic policy",
                            resultSet.getString("last_error"),
                        )
                        assertNull(resultSet.getTimestamp("next_attempt_at"))
                    }
                }
            }

            dataSource.close()
        }

    @Test
    fun `maintenance staff notifications require the exact reviewed and product linked venue staff chat`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val productPolicy =
                TelegramTrafficPolicy.from(
                    MapApplicationConfig("telegram.trafficPolicy" to "PRODUCT"),
                    appEnv = "staging",
                )
            val venueOneChatId = -100_920_001L
            val venueTwoChatId = -100_920_002L
            val maintenancePolicy =
                StagingMaintenancePolicy.from(
                    MapApplicationConfig(
                        "staging.maintenance.mode" to "V126_SMOKE",
                        "staging.maintenance.allowedUserIds" to "300",
                        "staging.maintenance.allowedChatIds" to "300,$venueOneChatId",
                    ),
                    "staging",
                )
            val repository = StaffChatNotificationRepository(dataSource, productPolicy, maintenancePolicy)
            val venueIds = mutableListOf<Long>()
            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, status, staff_chat_id)
                    VALUES (?, 'PUBLISHED', ?)
                    RETURNING id
                    """.trimIndent(),
                ).use { statement ->
                    listOf("One" to venueOneChatId, "Two" to venueTwoChatId).forEach { (name, chatId) ->
                        statement.setString(1, name)
                        statement.setLong(2, chatId)
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            venueIds += resultSet.getLong("id")
                        }
                    }
                }
            }
            val venueOneId = venueIds.first()
            val payload = """{"chat_id":$venueOneChatId,"text":"staff"}"""

            assertEquals(
                StaffChatNotificationClaim.SKIPPED_TRAFFIC_POLICY,
                repository.tryClaimAndEnqueue(70L, venueOneChatId, "sendMessage", payload),
            )
            assertEquals(
                StaffChatNotificationClaim.SKIPPED_TRAFFIC_POLICY,
                repository.tryClaimAndEnqueueForVenue(
                    70L,
                    venueOneId,
                    venueTwoChatId,
                    "sendMessage",
                    payload,
                ),
            )
            assertEquals(
                StaffChatNotificationClaim.CLAIMED,
                repository.tryClaimAndEnqueueForVenue(
                    70L,
                    venueOneId,
                    venueOneChatId,
                    "sendMessage",
                    payload,
                ),
            )
            assertFalse(repository.enqueueForVenue(venueOneId, venueTwoChatId, "sendMessage", payload))
            assertTrue(repository.enqueueForVenue(venueOneId, venueOneChatId, "sendMessage", payload))

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM telegram_staff_chat_notifications").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(1L, resultSet.getLong(1))
                    }
                }
                connection.prepareStatement("SELECT COUNT(*) FROM telegram_outbox").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(2L, resultSet.getLong(1))
                    }
                }
            }

            dataSource.close()
        }

    @Test
    fun `product live order target is invalidated when a venue relinks its staff chat`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val productPolicy =
                TelegramTrafficPolicy.from(
                    MapApplicationConfig("telegram.trafficPolicy" to "PRODUCT"),
                    appEnv = "staging",
                )
            val repository = StaffChatNotificationRepository(dataSource, productPolicy)
            val oldChatId = -100_930_001L
            val newChatId = -100_930_002L
            val orderId =
                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    createLiveOrderFixture(connection, staffChatId = oldChatId)
                }
            val venueId =
                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement("SELECT venue_id FROM orders WHERE id = ?").use { statement ->
                        statement.setLong(1, orderId)
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            resultSet.getLong("venue_id")
                        }
                    }
                }
            assertTrue(repository.upsertOrderMessage(orderId, venueId, oldChatId, 222L))
            assertEquals(222L, repository.findOrderMessage(orderId)?.messageId)

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement("UPDATE venues SET staff_chat_id = ? WHERE id = ?").use { statement ->
                    statement.setLong(1, newChatId)
                    statement.setLong(2, venueId)
                    statement.executeUpdate()
                }
            }

            assertNull(repository.findOrderMessage(orderId))
            assertFalse(
                repository.enqueueOrderMessage(
                    orderId,
                    venueId,
                    oldChatId,
                    "sendMessage",
                    """{"chat_id":$oldChatId,"text":"stale"}""",
                ),
            )
            assertTrue(
                repository.enqueueOrderMessage(
                    orderId,
                    venueId,
                    newChatId,
                    "sendMessage",
                    """{"chat_id":$newChatId,"text":"fresh"}""",
                ),
            )
            val refreshed = repository.findOrderMessage(orderId)
            assertNotNull(refreshed)
            assertEquals(newChatId, refreshed.chatId)
            assertNull(refreshed.messageId)

            dataSource.close()
        }

    @Test
    fun `worker sends queued message`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val repository = TelegramOutboxRepository(dataSource, trafficPolicy)
            val json = Json { ignoreUnknownKeys = true }
            val outboxEnqueuer = TelegramOutboxEnqueuer(repository, json, trafficPolicy)
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
            coEvery { apiClient.dispatchOutbox(123L, "sendMessage", any()) } returns
                TelegramCallResult.Success(JsonNull)

            worker.processOnce()

            coVerify(exactly = 1) { apiClient.dispatchOutbox(123L, "sendMessage", any()) }

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
    fun `dedupe key prevents duplicate enqueue`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val repository = TelegramOutboxRepository(dataSource, trafficPolicy)
            val json = Json { ignoreUnknownKeys = true }
            val outboxEnqueuer = TelegramOutboxEnqueuer(repository, json, trafficPolicy)

            outboxEnqueuer.enqueueSendMessage(123L, "hello", dedupeKey = "booking-reminder:77")
            outboxEnqueuer.enqueueSendMessage(123L, "hello", dedupeKey = "booking-reminder:77")

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) AS count FROM telegram_outbox WHERE dedupe_key = 'booking-reminder:77'",
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        assertEquals(1, resultSet.getInt("count"))
                    }
                }
            }

            dataSource.close()
        }

    @Test
    fun `legacy dedupe key keeps the first envelope when a retry regenerates payload`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val repository = TelegramOutboxRepository(dataSource, trafficPolicy)
            val outboxEnqueuer =
                TelegramOutboxEnqueuer(repository, Json { ignoreUnknownKeys = true }, trafficPolicy)

            outboxEnqueuer.enqueueSendMessage(123L, "original reminder", dedupeKey = "booking-reminder:78")
            outboxEnqueuer.enqueueSendMessage(456L, "regenerated reminder", dedupeKey = "booking-reminder:78")

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "SELECT chat_id, payload_json FROM telegram_outbox WHERE dedupe_key = 'booking-reminder:78'",
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        assertEquals(true, resultSet.next())
                        assertEquals(123L, resultSet.getLong("chat_id"))
                        assertEquals(true, resultSet.getString("payload_json").contains("original reminder"))
                        assertEquals(false, resultSet.next())
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
            val repository = TelegramOutboxRepository(dataSource, trafficPolicy)
            val json = Json { ignoreUnknownKeys = true }
            val outboxEnqueuer = TelegramOutboxEnqueuer(repository, json, trafficPolicy)
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
            coEvery { apiClient.dispatchOutbox(999L, "sendMessage", any()) } returns
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
            val repository = TelegramOutboxRepository(dataSource, trafficPolicy)
            val json = Json { ignoreUnknownKeys = true }
            val outboxEnqueuer = TelegramOutboxEnqueuer(repository, json, trafficPolicy)
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
            coEvery { apiClient.dispatchOutbox(456L, "sendMessage", any()) } returns
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
            val repository = TelegramOutboxRepository(dataSource, trafficPolicy)
            val json = Json { ignoreUnknownKeys = true }
            val outboxEnqueuer = TelegramOutboxEnqueuer(repository, json, trafficPolicy)
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
            coEvery { apiClient.dispatchOutbox(321L, "answerCallbackQuery", any()) } returns
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
            val repository = TelegramOutboxRepository(dataSource, trafficPolicy)
            val staffChatRepository = StaffChatNotificationRepository(dataSource, trafficPolicy)
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
            coEvery { apiClient.dispatchOutbox(777L, "sendMessage", any()) } returns
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
            val repository = TelegramOutboxRepository(dataSource, trafficPolicy)
            val staffChatRepository = StaffChatNotificationRepository(dataSource, trafficPolicy)
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
            coEvery { apiClient.dispatchOutbox(777L, "editMessageText", any()) } returns
                TelegramCallResult.Failure(
                    errorCode = 400,
                    description = "Bad Request: message to edit not found",
                    retryAfterSeconds = null,
                )
            coEvery { apiClient.dispatchOutbox(777L, "sendMessage", any()) } returns
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

    @Test
    fun `malformed payload is terminal local validation without transport fallback or metrics`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val repository = TelegramOutboxRepository(dataSource, trafficPolicy)
            val staffChatRepository = StaffChatNotificationRepository(dataSource, trafficPolicy)
            val json = Json { ignoreUnknownKeys = true }
            var telegramRequestCount = 0
            val httpClient =
                HttpClient(
                    MockEngine {
                        telegramRequestCount += 1
                        error("Telegram transport must not be called for a malformed outbox payload")
                    },
                )
            val apiClient = TelegramApiClient(MALFORMED_TOKEN_SENTINEL, httpClient, json, trafficPolicy)
            val metrics = AppMetrics()
            val fixedNow = Instant.parse("2030-01-01T00:00:00Z")
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val workerLogger = LoggerFactory.getLogger(TelegramOutboxWorker::class.java) as Logger
            workerLogger.addAppender(appender)
            val worker =
                TelegramOutboxWorker(
                    repository = repository,
                    apiClientProvider = { apiClient },
                    json = json,
                    rateLimiter = TelegramRateLimiter { },
                    config = TelegramOutboxConfig(batchSize = 1, maxConcurrency = 1),
                    scope = CoroutineScope(Dispatchers.IO),
                    nowProvider = { fixedNow },
                    metrics = metrics,
                )
            val orderId =
                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    createLiveOrderFixture(connection)
                }
            assertEquals(
                true,
                staffChatRepository.enqueueOrderMessage(
                    orderId = orderId,
                    venueId = 1L,
                    chatId = 777L,
                    method = "editMessageText",
                    payloadJson = MALFORMED_PAYLOAD_SENTINEL,
                ),
            )

            try {
                worker.processOnce()
                worker.processOnce()

                assertEquals(0, telegramRequestCount)
                assertNoOutboundMetrics(metrics)
                val logs = appender.list.joinToString("\n") { it.formattedMessage }
                assertTrue(logs.contains("reason_code=MALFORMED_JSON"))
                assertFalse(logs.contains(MALFORMED_PAYLOAD_SENTINEL))
                assertFalse(logs.contains(MALFORMED_TOKEN_SENTINEL))
                assertFalse(logs.contains("777"))

                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT status, attempts, last_error, processed_at, next_attempt_at
                        FROM telegram_outbox
                        WHERE method = 'editMessageText'
                        """.trimIndent(),
                    ).use { statement ->
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            assertEquals(TelegramOutboxStatus.FAILED.name, resultSet.getString("status"))
                            assertEquals(1, resultSet.getInt("attempts"))
                            assertEquals("Invalid Telegram outbox payload", resultSet.getString("last_error"))
                            assertEquals(Timestamp.from(fixedNow), resultSet.getTimestamp("processed_at"))
                            assertNull(resultSet.getTimestamp("next_attempt_at"))
                            assertFalse(resultSet.next())
                        }
                    }
                    connection.prepareStatement("SELECT COUNT(*) FROM telegram_outbox").use { statement ->
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            assertEquals(1L, resultSet.getLong(1))
                        }
                    }
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM telegram_staff_chat_order_outbox_links WHERE order_id = ?",
                    ).use { statement ->
                        statement.setLong(1, orderId)
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            assertEquals(1L, resultSet.getLong(1))
                        }
                    }
                }
            } finally {
                workerLogger.detachAppender(appender)
                appender.stop()
                apiClient.close()
                dataSource.close()
            }
        }

    @Test
    fun `linked edit with mismatched allowlisted envelope uses no transport retry or fallback state`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val restrictedPolicy =
                TelegramTrafficPolicy.from(
                    MapApplicationConfig(
                        "telegram.trafficPolicy" to "ALLOWLIST",
                        "telegram.allowedUserIds" to "777,888",
                        "telegram.allowedChatIds" to "777,888",
                    ),
                    appEnv = "staging",
                )
            val repository = TelegramOutboxRepository(dataSource, restrictedPolicy)
            val staffChatRepository = StaffChatNotificationRepository(dataSource, restrictedPolicy)
            val json = Json { ignoreUnknownKeys = true }
            var telegramRequestCount = 0
            val httpClient =
                HttpClient(
                    MockEngine {
                        telegramRequestCount += 1
                        error("Telegram transport must not be called for a rejected outbox envelope")
                    },
                )
            val apiClient = TelegramApiClient("test-token", httpClient, json, restrictedPolicy)
            val metrics = AppMetrics()
            val fixedNow = Instant.parse("2030-01-01T00:00:00Z")
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val workerLogger = LoggerFactory.getLogger(TelegramOutboxWorker::class.java) as Logger
            workerLogger.addAppender(appender)
            val worker =
                TelegramOutboxWorker(
                    repository = repository,
                    apiClientProvider = { apiClient },
                    json = json,
                    rateLimiter = TelegramRateLimiter { },
                    config = TelegramOutboxConfig(batchSize = 1, maxConcurrency = 1),
                    scope = CoroutineScope(Dispatchers.IO),
                    nowProvider = { fixedNow },
                    metrics = metrics,
                )
            val orderId =
                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    createLiveOrderFixture(connection)
                }
            assertEquals(
                true,
                staffChatRepository.upsertOrderMessage(
                    orderId = orderId,
                    venueId = 1L,
                    chatId = 777L,
                    messageId = 111L,
                ),
            )
            val payload =
                json.encodeToString(
                    EditMessageTextPayload.serializer(),
                    EditMessageTextPayload(chatId = 888L, messageId = 111L, text = "must not be delivered"),
                )
            assertEquals(
                true,
                staffChatRepository.enqueueOrderMessage(
                    orderId = orderId,
                    venueId = 1L,
                    chatId = 777L,
                    method = "editMessageText",
                    payloadJson = payload,
                ),
            )

            try {
                worker.processOnce()
                worker.processOnce()

                assertEquals(0, telegramRequestCount)
                assertNoOutboundMetrics(metrics)
                val logs = appender.list.joinToString("\n") { it.formattedMessage }
                assertTrue(logs.contains("reason_code=INVALID_ENVELOPE"))
                assertFalse(logs.contains("777"))
                assertFalse(logs.contains("888"))
                assertFalse(logs.contains("test-token"))
                assertFalse(logs.contains("must not be delivered"))
                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT status, attempts, last_error, processed_at, next_attempt_at
                        FROM telegram_outbox
                        WHERE method = 'editMessageText'
                        """.trimIndent(),
                    ).use { statement ->
                        statement.executeQuery().use { resultSet ->
                            assertEquals(true, resultSet.next())
                            assertEquals(TelegramOutboxStatus.FAILED.name, resultSet.getString("status"))
                            assertEquals(1, resultSet.getInt("attempts"))
                            assertEquals(
                                "Telegram outbox envelope rejected locally",
                                resultSet.getString("last_error"),
                            )
                            assertEquals(Timestamp.from(fixedNow), resultSet.getTimestamp("processed_at"))
                            assertNull(resultSet.getTimestamp("next_attempt_at"))
                            assertEquals(false, resultSet.next())
                        }
                    }
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM telegram_outbox WHERE method = 'sendMessage'",
                    ).use { statement ->
                        statement.executeQuery().use { resultSet ->
                            assertEquals(true, resultSet.next())
                            assertEquals(0L, resultSet.getLong(1))
                        }
                    }
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM telegram_staff_chat_order_outbox_links WHERE order_id = ?",
                    ).use { statement ->
                        statement.setLong(1, orderId)
                        statement.executeQuery().use { resultSet ->
                            assertEquals(true, resultSet.next())
                            assertEquals(1L, resultSet.getLong(1))
                        }
                    }
                    connection.prepareStatement(
                        "SELECT chat_id, message_id FROM telegram_staff_chat_order_messages WHERE order_id = ?",
                    ).use { statement ->
                        statement.setLong(1, orderId)
                        statement.executeQuery().use { resultSet ->
                            assertEquals(true, resultSet.next())
                            assertEquals(777L, resultSet.getLong("chat_id"))
                            assertEquals(111L, resultSet.getLong("message_id"))
                        }
                    }
                }
            } finally {
                workerLogger.detachAppender(appender)
                appender.stop()
                apiClient.close()
                dataSource.close()
            }
        }

    private fun messageIdResult(messageId: Long): JsonObject =
        buildJsonObject {
            put("message_id", messageId)
        }

    private fun assertNoOutboundMetrics(metrics: AppMetrics) {
        assertEquals(0.0, metrics.registry.get("outbound_send_success_total").counter().count())
        assertEquals(0.0, metrics.registry.get("outbound_send_failed_total").counter().count())
        assertEquals(0.0, metrics.registry.get("outbound_429_total").counter().count())
    }

    private fun outboxSnapshots(
        jdbcUrl: String,
        user: String,
        password: String,
        chatIds: Set<Long>,
    ): Map<Long, OutboxSnapshot> =
        DriverManager.getConnection(jdbcUrl, user, password).use { connection ->
            val placeholders = chatIds.joinToString(",") { "?" }
            connection.prepareStatement(
                """
                SELECT chat_id, status, attempts, last_error, processed_at, next_attempt_at
                FROM telegram_outbox
                WHERE chat_id IN ($placeholders)
                ORDER BY chat_id
                """.trimIndent(),
            ).use { statement ->
                chatIds.sorted().forEachIndexed { index, chatId -> statement.setLong(index + 1, chatId) }
                statement.executeQuery().use { resultSet ->
                    buildMap {
                        while (resultSet.next()) {
                            put(
                                resultSet.getLong("chat_id"),
                                OutboxSnapshot(
                                    status = resultSet.getString("status"),
                                    attempts = resultSet.getInt("attempts"),
                                    lastError = resultSet.getString("last_error"),
                                    processedAt = resultSet.getTimestamp("processed_at")?.toInstant(),
                                    nextAttemptAt = resultSet.getTimestamp("next_attempt_at")?.toInstant(),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private data class OutboxSnapshot(
        val status: String,
        val attempts: Int,
        val lastError: String?,
        val processedAt: Instant?,
        val nextAttemptAt: Instant?,
    )

    private companion object {
        const val MALFORMED_PAYLOAD_SENTINEL = "{malformed-payload-secret"
        const val MALFORMED_TOKEN_SENTINEL = "malformed-test-token-secret"
    }

    private fun createLiveOrderFixture(
        connection: Connection,
        staffChatId: Long = 777L,
    ): Long {
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
                VALUES ('Venue', 'PUBLISHED', ?)
                RETURNING id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, staffChatId)
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
        val tableSessionId =
            connection.prepareStatement(
                """
                INSERT INTO table_sessions (
                    venue_id,
                    table_id,
                    started_at,
                    last_activity_at,
                    expires_at,
                    status
                )
                VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour', 'ACTIVE')
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
        return connection.prepareStatement(
            """
            INSERT INTO orders (venue_id, table_id, table_session_id, status)
            VALUES (?, ?, ?, 'ACTIVE')
            RETURNING id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.setLong(3, tableSessionId)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getLong("id")
            }
        }
    }
}
