package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.db.BindResult
import com.hookah.platform.backend.telegram.db.StaffChatNotificationClaim
import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import com.hookah.platform.backend.telegram.db.UnlinkResult
import com.hookah.platform.backend.telegram.db.VenueRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.server.config.MapApplicationConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramOutboxVenueAuthorityTest {
    @Test
    fun `new send radar and activity edit share one claim rollback and PostgreSQL concurrent retry`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val ds = PostgresTestEnv.createDataSource(database)
            try {
                val repo = com.hookah.platform.backend.telegram.db.StaffChatNotificationRepository(ds, productPolicy())
                val venue = ds.connection.use { insertVenue(it, "Radar", -100920001L) }

                fun exec(sql: String) =
                    ds.connection.use {
                        it.createStatement().use { statement ->
                            statement.executeUpdate(
                                sql,
                            )
                        }
                    }

                fun count(table: String) =
                    ds.connection.use {
                        it.createStatement().use { statement ->
                            statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                                rows.next()
                                rows.getLong(1)
                            }
                        }
                    }
                val order =
                    ds.connection.use { c ->
                        c.prepareStatement(
                            "INSERT INTO venue_tables(venue_id,table_number) VALUES ($venue,1)",
                        ).use { it.executeUpdate() }
                        c.createStatement().use {
                            it.executeUpdate(
                                "INSERT INTO table_sessions(venue_id,table_id,status,started_at," +
                                    "last_activity_at,expires_at) SELECT $venue,id,'ACTIVE',CURRENT_TIMESTAMP," +
                                    "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM venue_tables WHERE venue_id=$venue",
                            )
                        }
                        c.prepareStatement(
                            "INSERT INTO orders(venue_id,table_id,table_session_id,status) SELECT " +
                                "$venue,table_id,id,'ACTIVE' FROM table_sessions WHERE venue_id=$venue",
                            java.sql.Statement.RETURN_GENERATED_KEYS,
                        ).use {
                            it.executeUpdate()
                            it.generatedKeys.use {
                                    r ->
                                r.next()
                                r.getLong(1)
                            }
                        }
                    }
                assertTrue(repo.upsertOrderMessage(order, venue, -100920001L, 333L))
                exec("ALTER TABLE telegram_outbox ADD CONSTRAINT reject_radar_test CHECK (method <> 'sendMessage')")

                suspend fun enqueue(key: Long = 991L) =
                    repo.tryClaimAndEnqueueOrderMessage(
                        key,
                        order,
                        venue,
                        -100920001L,
                        "editMessageText",
                        """{"chat_id":-100920001,"message_id":333,"text":"Activity"}""",
                        """{"chat_id":-100920001,"text":"New send"}""",
                    )
                assertEquals(StaffChatNotificationClaim.ERROR, enqueue())
                assertEquals(0, count("telegram_outbox"))
                assertEquals(0, count("telegram_staff_chat_notifications"))
                exec("ALTER TABLE telegram_outbox DROP CONSTRAINT reject_radar_test")
                val start = java.util.concurrent.CountDownLatch(1)
                val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
                try {
                    val futures =
                        (1..2).map {
                            pool.submit<StaffChatNotificationClaim> {
                                start.await()
                                runBlocking { enqueue() }
                            }
                        }
                    start.countDown()
                    val results = futures.map { it.get(15, java.util.concurrent.TimeUnit.SECONDS) }
                    assertEquals(
                        1,
                        results.count { it == StaffChatNotificationClaim.CLAIMED },
                    )
                    assertEquals(
                        1,
                        results.count { it == StaffChatNotificationClaim.ALREADY },
                    )
                } finally {
                    pool.shutdownNow()
                }
                assertEquals(2, count("telegram_outbox"))
                assertEquals(1, count("telegram_staff_chat_notifications"))
                assertEquals(2, count("telegram_staff_chat_order_outbox_links"))
                val outbox = TelegramOutboxRepository(ds, productPolicy())
                val now = java.time.Instant.parse("2030-01-01T00:00:00Z")
                val timeout = java.time.Duration.ofSeconds(30)
                val pending = outbox.claimBatch(10, now, timeout)
                assertEquals(2, pending.size)
                assertTrue(pending.all { it.staffLiveOrderId == order })
                assertEquals(1, pending.count { it.staffOrderRadar })
                exec("UPDATE telegram_outbox SET status='NEW',next_attempt_at=NULL WHERE status='SENDING'")
                val api: TelegramApiClient = mockk()
                coEvery { api.dispatchOutbox(any(), any(), any()) } returns
                    TelegramCallResult.Success(buildJsonObject { put("message_id", 900) })
                val worker =
                    TelegramOutboxWorker(
                        repository = outbox,
                        apiClientProvider = { api },
                        json = Json { ignoreUnknownKeys = true },
                        rateLimiter = TelegramRateLimiter { },
                        config = TelegramOutboxConfig(batchSize = 10, maxConcurrency = 1),
                        scope = CoroutineScope(Dispatchers.IO),
                        nowProvider = { now },
                    )
                worker.processOnce()
                coVerify(exactly = 1) { api.dispatchOutbox(-100920001L, "sendMessage", any()) }
                assertEquals(333L, repo.findOrderMessage(order)?.messageId)
                assertEquals(StaffChatNotificationClaim.CLAIMED, enqueue(992))
                val next = outbox.claimBatch(10, now, timeout)
                assertEquals(2, next.size)
                exec("UPDATE venues SET staff_chat_id=NULL WHERE id=$venue")
                ds.connection.use { insertVenue(it, "Another venue", -100920001L) }
                for (message in next) assertFalse(
                    outbox.isRecipientAuthorized(message.chatId, message.staffLiveOrderId),
                )
                exec("UPDATE telegram_outbox SET status='NEW',next_attempt_at=NULL WHERE status='SENDING'")
                assertTrue(outbox.claimBatch(10, now, timeout).isEmpty())
                assertEquals(StaffChatNotificationClaim.SKIPPED_TRAFFIC_POLICY, enqueue(993))
                assertEquals(4, count("telegram_outbox"))
                ds.connection.use { c ->
                    c.prepareStatement(
                        "INSERT INTO telegram_outbox(chat_id,method,payload_json,dedupe_key) " +
                            "VALUES (?,'sendMessage',?,?)",
                    ).use {
                        it.setLong(1, -100920001L)
                        it.setString(2, """{"chat_id":-100920001,"text":"Unknown context"}""")
                        it.setString(3, "${TelegramOutboxRepository.STAFF_ORDER_RADAR_DEDUPE_PREFIX}orphan-fixture")
                        it.executeUpdate()
                    }
                }
                worker.processOnce()
                coVerify(exactly = 1) { api.dispatchOutbox(-100920001L, "sendMessage", any()) }
                assertEquals(
                    1,
                    ds.connection.use { c ->
                        c.createStatement().use {
                            it.executeQuery("SELECT COUNT(*) FROM telegram_outbox WHERE status='FAILED'").use {
                                    r ->
                                r.next()
                                r.getInt(1)
                            }
                        }
                    },
                )
            } finally {
                ds.close()
            }
        }

    @Test
    fun `product venue enqueue rejects stale staff chat after relink`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val policy = productPolicy()
            val repository = TelegramOutboxRepository(dataSource, policy)
            val enqueuer = TelegramOutboxEnqueuer(repository, Json { ignoreUnknownKeys = true }, policy)
            val venueRepository = VenueRepository(dataSource)
            val actorUserId = 920_001L
            val staffChatId = -100_920_001L

            try {
                val (firstVenueId, secondVenueId) =
                    DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                        connection.prepareStatement(
                            "INSERT INTO users (telegram_user_id, first_name) VALUES (?, 'Owner')",
                        ).use { statement ->
                            statement.setLong(1, actorUserId)
                            statement.executeUpdate()
                        }
                        insertVenue(connection, "First", staffChatId) to insertVenue(connection, "Second", null)
                    }

                assertEquals(
                    TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY,
                    enqueuer.enqueueSendMessage(staffChatId, "generic group payload"),
                )
                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM telegram_outbox WHERE chat_id = ?",
                    ).use { statement ->
                        statement.setLong(1, staffChatId)
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            assertEquals(0L, resultSet.getLong(1))
                        }
                    }
                }

                assertEquals(
                    TelegramOutboxEnqueueOutcome.ENQUEUED,
                    enqueuer.enqueueVenueSendMessage(firstVenueId, staffChatId, "queued before relink"),
                )
                assertTrue(
                    venueRepository.unlinkStaffChatByChatId(
                        chatId = staffChatId,
                        userId = actorUserId,
                        expectedVenueId = firstVenueId,
                    ) is UnlinkResult.Success,
                )
                assertTrue(venueRepository.bindStaffChat(secondVenueId, staffChatId, actorUserId) is BindResult.Success)

                assertEquals(
                    TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY,
                    enqueuer.enqueueVenueSendMessage(firstVenueId, staffChatId, "stale venue payload"),
                )
                assertEquals(
                    TelegramOutboxEnqueueOutcome.ENQUEUED,
                    enqueuer.enqueueVenueSendMessage(secondVenueId, staffChatId, "fresh venue payload"),
                )
                assertEquals(
                    TelegramOutboxEnqueueOutcome.ENQUEUED,
                    enqueuer.enqueueVenueEditMessageText(
                        venueId = secondVenueId,
                        chatId = staffChatId,
                        messageId = 77L,
                        text = "fresh venue edit",
                    ),
                )

                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        "SELECT status, payload_json FROM telegram_outbox WHERE chat_id = ? ORDER BY id",
                    ).use { statement ->
                        statement.setLong(1, staffChatId)
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            assertEquals("FAILED", resultSet.getString("status"))
                            assertTrue(resultSet.getString("payload_json").contains("queued before relink"))
                            assertTrue(resultSet.next())
                            assertEquals("NEW", resultSet.getString("status"))
                            assertTrue(resultSet.getString("payload_json").contains("fresh venue payload"))
                            assertTrue(resultSet.next())
                            assertEquals("NEW", resultSet.getString("status"))
                            assertTrue(resultSet.getString("payload_json").contains("fresh venue edit"))
                            assertFalse(resultSet.next())
                        }
                    }
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM telegram_outbox WHERE payload_json LIKE '%stale venue payload%'",
                    ).use { statement ->
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            assertEquals(0L, resultSet.getLong(1))
                        }
                    }
                }
            } finally {
                dataSource.close()
            }
        }

    @Test
    fun `allowlist venue enqueue preserves static chat policy without database venue authority`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val allowedPrivateChatId = 930_001L
            val allowedStaffChatId = -100_930_001L
            val deniedStaffChatId = -100_930_002L
            val policy =
                TelegramTrafficPolicy.from(
                    MapApplicationConfig(
                        "telegram.trafficPolicy" to "ALLOWLIST",
                        "telegram.allowedUserIds" to allowedPrivateChatId.toString(),
                        "telegram.allowedChatIds" to "$allowedPrivateChatId,$allowedStaffChatId",
                    ),
                    appEnv = "staging",
                )
            val repository = TelegramOutboxRepository(dataSource, policy)
            val enqueuer = TelegramOutboxEnqueuer(repository, Json { ignoreUnknownKeys = true }, policy)

            try {
                assertEquals(
                    TelegramOutboxEnqueueOutcome.ENQUEUED,
                    enqueuer.enqueueVenueSendMessage(venueId = 999L, chatId = allowedStaffChatId, text = "allowed"),
                )
                assertEquals(
                    TelegramOutboxEnqueueOutcome.ENQUEUED,
                    enqueuer.enqueueVenueEditMessageText(
                        venueId = 999L,
                        chatId = allowedStaffChatId,
                        messageId = 12L,
                        text = "allowed edit",
                    ),
                )
                assertEquals(
                    TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY,
                    enqueuer.enqueueVenueSendMessage(venueId = 999L, chatId = deniedStaffChatId, text = "denied"),
                )

                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM telegram_outbox WHERE chat_id = ?",
                    ).use { statement ->
                        statement.setLong(1, allowedStaffChatId)
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            assertEquals(2L, resultSet.getLong(1))
                        }
                    }
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM telegram_outbox WHERE chat_id = ?",
                    ).use { statement ->
                        statement.setLong(1, deniedStaffChatId)
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            assertEquals(0L, resultSet.getLong(1))
                        }
                    }
                }
            } finally {
                dataSource.close()
            }
        }

    private fun productPolicy(): TelegramTrafficPolicy =
        TelegramTrafficPolicy.from(
            MapApplicationConfig("telegram.trafficPolicy" to "PRODUCT"),
            appEnv = "staging",
        )

    private fun insertVenue(
        connection: Connection,
        name: String,
        staffChatId: Long?,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO venues (name, status, staff_chat_id)
            VALUES (?, 'PUBLISHED', ?)
            RETURNING id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, name)
            if (staffChatId == null) {
                statement.setNull(2, java.sql.Types.BIGINT)
            } else {
                statement.setLong(2, staffChatId)
            }
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getLong("id")
            }
        }
}
