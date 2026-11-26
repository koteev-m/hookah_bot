package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GuestTableContextActivationPostgresTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("rollbackScenarios")
    fun `every required PostgreSQL activation branch rolls back the full authoritative snapshot`(
        scenario: TestActivationRollbackScenario,
    ) = runBlocking {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database).use { dataSource ->
            val now = Instant.parse("2026-08-05T10:00:00Z")
            val fixture = seedFixture(dataSource.connection, now, scenario)
            val analyticsEventRepository = AnalyticsEventRepository(dataSource)
            var checkpointReached = false
            val repository =
                GuestTableContextLifecycleRepository(
                    dataSource = dataSource,
                    tableTokenRepository = TableTokenRepository(dataSource),
                    subscriptionRepository = SubscriptionRepository(dataSource),
                    tableSessionRepository = TableSessionRepository(dataSource, analyticsEventRepository),
                    guestTabsRepository = GuestTabsRepository(dataSource),
                    chatContextRepository = ChatContextRepository(dataSource),
                    dialogStateRepository = DialogStateRepository(dataSource, Json),
                    activationCheckpoint = { checkpoint ->
                        if (checkpoint == scenario.checkpoint) {
                            checkpointReached = true
                            throw SQLException("injected PostgreSQL activation failure")
                        }
                    },
                )
            val before =
                dataSource.connection.use(::loadTestActivationAuthoritativeSnapshot)

            val result =
                repository.activate(
                    actorUserId = fixture.actorUserId,
                    chatId = fixture.chatId,
                    tableToken = fixture.tableToken,
                    expectedVenueId = fixture.venueId,
                    expectedTableId = fixture.tableId,
                    ttl = Duration.ofHours(4),
                    now = now,
                    expectedExitEventId =
                        TableSessionRepository(
                            dataSource,
                        ).currentQrExit(fixture.actorUserId, fixture.venueId, fixture.tableId, now),
                )

            assertTrue(checkpointReached, scenario.toString())
            assertEquals(GuestTableActivationResult.DatabaseUnavailable, result, scenario.toString())
            assertEquals(
                before,
                dataSource.connection.use(::loadTestActivationAuthoritativeSnapshot),
                scenario.toString(),
            )
        }
    }

    @Test
    fun `review09 addressed Mini App quick order binds an existing visit and dialog atomically`() =
        runBlocking {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                val now = Instant.now()
                val fixture =
                    seedFixture(
                        ds.connection,
                        now,
                        rollbackScenarios().first {
                            it.sessionState == TestActivationSessionState.NEW &&
                                it.contextState != TestActivationContextState.UPDATE
                        },
                    )
                val tabs = GuestTabsRepository(ds)
                val chats = ChatContextRepository(ds)
                val dialogs = DialogStateRepository(ds, Json)
                val sessions = TableSessionRepository(ds)
                val live =
                    ds.connection.use {
                        it.insertSession(
                            fixture.venueId,
                            fixture.tableId,
                            now.minusSeconds(90),
                            now.minusSeconds(30),
                            now.plusSeconds(900),
                        )
                    }
                val tab = tabs.ensurePersonalTab(fixture.venueId, live, fixture.actorUserId)
                val repository =
                    GuestTableContextLifecycleRepository(
                        ds,
                        TableTokenRepository(ds),
                        SubscriptionRepository(ds),
                        sessions,
                        tabs,
                        chats,
                        dialogs,
                    )
                val unbound = ds.connection.use(::loadTestActivationAuthoritativeSnapshot)
                val exact = MiniAppFallbackVisit(live, 0, tab.id)

                suspend fun handoff(
                    address: MiniAppFallbackVisit,
                    ordinary: Boolean = true,
                ) = repository.activate(
                    fixture.actorUserId, fixture.chatId, fixture.tableToken, fixture.venueId, fixture.tableId,
                    Duration.ofHours(2), now, ordinaryBot = ordinary, fallback = address,
                )

                assertIs<GuestTableActivationResult.Denied>(handoff(exact.copy(visitGeneration = 12)))
                assertIs<GuestTableActivationResult.Denied>(handoff(exact.copy(tableSessionId = live + 1)))
                assertIs<GuestTableActivationResult.Denied>(handoff(exact.copy(tabId = tab.id + 1)))
                assertIs<GuestTableActivationResult.Denied>(handoff(exact, ordinary = false))
                assertEquals(unbound, ds.connection.use(::loadTestActivationAuthoritativeSnapshot))

                val applied = assertIs<GuestTableActivationResult.Applied>(handoff(exact))
                assertEquals(live, applied.tableSession.id)
                assertEquals(live, chats.get(fixture.chatId)?.tableSessionId)
                assertEquals(tab.id.toString(), dialogs.get(fixture.chatId).payload["tabId"])
                assertEquals("0", dialogs.get(fixture.chatId).payload["visitGeneration"])
                val committed = ds.connection.use(::loadTestActivationAuthoritativeSnapshot)
                assertEquals(unbound.sessions, committed.sessions)
                assertEquals(unbound.exits, committed.exits)
                assertIs<GuestTableActivationResult.Denied>(handoff(exact))
                assertEquals(committed, ds.connection.use(::loadTestActivationAuthoritativeSnapshot))
            }
        }

    @Test
    fun `ordinary QR binds the locked physical session and rejects a later seat occupant`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { ds ->
                val now = Instant.now()
                val fixture = seedFixture(ds.connection, now, rollbackScenarios().first())
                val chats = ChatContextRepository(ds)
                val sessions = TableSessionRepository(ds)
                val seat = fixture.tableId
                val actor = fixture.actorUserId
                val chat = fixture.chatId
                var sawLockedSeat = false
                val repository =
                    GuestTableContextLifecycleRepository(
                        ds,
                        TableTokenRepository(ds),
                        SubscriptionRepository(ds),
                        sessions,
                        GuestTabsRepository(ds),
                        chats,
                        DialogStateRepository(ds, Json),
                        activationCheckpoint = { point ->
                            if (point == GuestTableActivationCheckpoint.AFTER_SESSION) {
                                ds.connection.use { competing ->
                                    competing.createStatement().use { sql ->
                                        val locked =
                                            assertFailsWith<SQLException> {
                                                sql.executeQuery(
                                                    "SELECT id FROM venue_tables WHERE id = $seat FOR UPDATE NOWAIT",
                                                )
                                            }
                                        assertEquals("55P03", locked.sqlState)
                                        sawLockedSeat = true
                                    }
                                }
                            }
                        },
                    )
                val activation =
                    assertIs<GuestTableActivationResult.Applied>(
                        repository.activate(
                            fixture.actorUserId,
                            fixture.chatId,
                            fixture.tableToken,
                            fixture.venueId,
                            fixture.tableId,
                            Duration.ofHours(2),
                            now,
                            ordinaryBot = true,
                        ),
                    )
                assertTrue(sawLockedSeat)
                val original = activation.tableSession.id
                assertEquals(original, chats.get(fixture.chatId)?.tableSessionId)
                ds.connection.use { conn ->
                    assertTrue(repository.hasExactBotVisit(conn, actor, activation.context, original, now))
                    conn.createStatement().use { sql ->
                        val replacement =
                            sql.executeQuery("SELECT id FROM table_sessions WHERE table_id <> $seat").use {
                                assertTrue(it.next())
                                it.getLong(1)
                            }
                        sql.executeUpdate(
                            "UPDATE table_sessions SET status = 'ENDED', ended_at = now() WHERE id = $original",
                        )
                        sql.executeUpdate("UPDATE table_sessions SET table_id = $seat WHERE id = $replacement")
                        assertFalse(repository.hasExactBotVisit(conn, actor, activation.context, replacement, now))
                        for (source in listOf(MiniAppFallbackVisit(null), MiniAppFallbackVisit(original))) {
                            assertIs<GuestTableActivationResult.Denied>(
                                repository.activate(
                                    actor, chat, fixture.tableToken, fixture.venueId, seat, Duration.ofHours(2),
                                    now, ordinaryBot = true, fallback = source,
                                ),
                            )
                        }
                        assertEquals(original, chats.get(fixture.chatId)?.tableSessionId)
                        sql.executeUpdate(
                            "UPDATE telegram_chat_context SET table_session_id = NULL WHERE chat_id = $chat",
                        )
                        assertFalse(repository.hasExactBotVisit(conn, actor, activation.context, replacement, now))
                    }
                }
            }
        }

    @Test
    fun `cart activity extends only its authorized unexpired physical visit`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { ds ->
                val now = Instant.now()
                val f = seedFixture(ds.connection, now, rollbackScenarios().first())
                val repository =
                    GuestTableContextLifecycleRepository(
                        ds,
                        TableTokenRepository(ds),
                        SubscriptionRepository(ds),
                        TableSessionRepository(ds),
                        GuestTabsRepository(ds),
                        ChatContextRepository(ds),
                        DialogStateRepository(ds, Json),
                    )
                val ttl = Duration.ofHours(2)
                val visit =
                    assertIs<GuestTableActivationResult.Applied>(
                        repository.activate(
                            f.actorUserId,
                            f.chatId,
                            f.tableToken,
                            f.venueId,
                            f.tableId,
                            ttl,
                            ordinaryBot = true,
                        ),
                    )
                val id = visit.tableSession.id

                fun execute(sql: String) =
                    ds.connection.use { it.createStatement().use { st -> st.executeUpdate(sql) } }

                suspend fun inspect(
                    actor: Long,
                    duration: Duration?,
                ) = repository.ordinaryBotVisit(actor, f.chatId, visit.context, id, duration)

                suspend fun activeVisit(duration: Duration? = null) =
                    assertIs<PlatformGuestContextValidationResult.Active>(inspect(f.actorUserId, duration)).tableSession

                execute("UPDATE table_sessions SET expires_at = now() + interval '15 seconds' WHERE id=$id")
                val before = activeVisit()
                assertIs<PlatformGuestContextValidationResult.Inactive>(inspect(f.actorUserId + 1, ttl))
                assertEquals(before, activeVisit())
                val refreshed = activeVisit(ttl)
                assertEquals(id, refreshed.id)
                assertTrue(refreshed.expiresAt > before.expiresAt.plus(Duration.ofHours(1)))
                execute("UPDATE table_sessions SET expires_at = now() - interval '1 second' WHERE id=$id")
                assertIs<PlatformGuestContextValidationResult.Inactive>(inspect(f.actorUserId, ttl))
                execute("UPDATE table_sessions SET status='ENDED', ended_at=now() WHERE id=$id")
                execute("UPDATE table_sessions SET table_id=${f.tableId} WHERE id<>$id")
                val snapshot = ds.connection.use(::loadTestActivationAuthoritativeSnapshot)
                assertIs<PlatformGuestContextValidationResult.Inactive>(inspect(f.actorUserId, ttl))
                assertEquals(snapshot, ds.connection.use(::loadTestActivationAuthoritativeSnapshot))
            }
        }

    @Test
    fun `a delayed fallback preserves the current chat before and across exit`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { ds ->
                val f = seedFixture(ds.connection, Instant.now(), rollbackScenarios().first())
                val actor = f.actorUserId
                val chats = ChatContextRepository(ds)
                val exitLocked = CountDownLatch(1)
                val finishExit = CountDownLatch(1)
                val repository =
                    GuestTableContextLifecycleRepository(
                        ds,
                        TableTokenRepository(ds),
                        SubscriptionRepository(ds),
                        TableSessionRepository(ds),
                        GuestTabsRepository(ds),
                        chats,
                        DialogStateRepository(ds, Json),
                        teardownCheckpoint = {
                            if (it == GuestTableTeardownCheckpoint.AFTER_CONTEXT_LOCK) {
                                exitLocked.countDown()
                                check(finishExit.await(15, TimeUnit.SECONDS))
                            }
                        },
                    )
                val ttl = Duration.ofHours(2)
                val visit =
                    assertIs<GuestTableActivationResult.Applied>(
                        repository.activate(
                            actor,
                            f.chatId,
                            f.tableToken,
                            f.venueId,
                            f.tableId,
                            ttl,
                            ordinaryBot = true,
                        ),
                    )

                fun number(sql: String): Long =
                    ds.connection.use { c ->
                        c.createStatement().use { st ->
                            st.executeQuery(sql).use { rows ->
                                check(rows.next())
                                rows.getLong(1)
                            }
                        }
                    }
                val a = number("SELECT id FROM table_sessions WHERE table_id<>${f.tableId}")
                val seatA = number("SELECT table_id FROM table_sessions WHERE id=$a")
                ds.connection.use { c ->
                    c.createStatement().use { st ->
                        st.executeUpdate("INSERT INTO table_tokens(token,table_id) VALUES ('fixture-a',$seatA)")
                        st.executeUpdate(
                            "INSERT INTO tab_member(tab_id,user_id,role) " +
                                "SELECT id,$actor,'MEMBER' FROM tab WHERE table_session_id=$a",
                        )
                    }
                }

                suspend fun fallback(chatId: Long) =
                    repository.activate(
                        actor,
                        chatId,
                        "fixture-a",
                        f.venueId,
                        seatA,
                        ttl,
                        ordinaryBot = true,
                        fallback = MiniAppFallbackVisit(a),
                    )

                // Existing membership admits the old fallback only for an absent chat.
                assertIs<GuestTableActivationResult.Applied>(fallback(f.chatId + 100))
                assertIs<GuestTableActivationResult.Denied>(fallback(f.chatId))
                assertEquals(visit.tableSession.id, chats.get(f.chatId)?.tableSessionId)
                val exit = async(Dispatchers.IO) { repository.teardownByChat(actor, f.chatId, ordinaryBotVisit = true) }
                check(exitLocked.await(10, TimeUnit.SECONDS))
                val late = async(Dispatchers.IO) { fallback(f.chatId) }
                try {
                    val blockedChatLock =
                        "SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() " +
                            "AND wait_event_type='Lock' AND query LIKE '%FROM telegram_chat_context%FOR UPDATE%'"
                    withTimeout(10000) {
                        while (number(blockedChatLock) == 0L) delay(10)
                    }
                } finally {
                    finishExit.countDown()
                }
                assertTrue(assertIs<GuestTableTeardownResult.Cleared>(exit.await()).exitRecorded)
                assertIs<GuestTableActivationResult.Denied>(late.await())
                val marker =
                    "SELECT exit_event_id FROM guest_table_session_exits " +
                        "WHERE user_id=$actor AND table_session_id=${visit.tableSession.id}"
                val eventId = number(marker)
                assertEquals(
                    visit.tableSession.id,
                    number(
                        "SELECT table_session_id FROM telegram_chat_context " +
                            "WHERE chat_id=${f.chatId} AND table_token IS NULL",
                    ),
                )
                val after = ds.connection.use(::loadTestActivationAuthoritativeSnapshot)
                assertIs<GuestTableActivationResult.Denied>(fallback(f.chatId))
                assertEquals(eventId, number(marker))
                assertEquals(after, ds.connection.use(::loadTestActivationAuthoritativeSnapshot))
            }
        }

    private fun seedFixture(
        connection: Connection,
        now: Instant,
        scenario: TestActivationRollbackScenario,
    ): PostgresActivationFixture {
        val actorUserId = 9_001L
        val unrelatedUserId = 9_002L
        val chatId = 8_001L
        val unrelatedChatId = 8_002L
        val tableToken = "POSTGRES_CONFIRMED_TOKEN"
        connection.use {
            it.prepareStatement(
                "INSERT INTO users (telegram_user_id, username, first_name) VALUES (?, 'platform', 'Platform')",
            ).use { statement ->
                statement.setLong(1, actorUserId)
                statement.executeUpdate()
            }
            it.prepareStatement(
                "INSERT INTO users (telegram_user_id, username, first_name) VALUES (?, 'unrelated', 'Unrelated')",
            ).use { statement ->
                statement.setLong(1, unrelatedUserId)
                statement.executeUpdate()
            }
            val venueId =
                it.insertReturningId(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Postgres Venue', 'Москва', 'Тверская, 1', 'PUBLISHED')
                    """.trimIndent(),
                )
            it.prepareStatement(
                "INSERT INTO venue_subscriptions (venue_id, status) VALUES (?, 'ACTIVE')",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeUpdate()
            }
            val tableId =
                it.insertReturningId(
                    "INSERT INTO venue_tables (venue_id, table_number, is_active) VALUES (?, 7, TRUE)",
                ) { statement -> statement.setLong(1, venueId) }
            val unrelatedTableId =
                it.insertReturningId(
                    "INSERT INTO venue_tables (venue_id, table_number, is_active) VALUES (?, 99, TRUE)",
                ) { statement -> statement.setLong(1, venueId) }
            it.prepareStatement(
                "INSERT INTO table_tokens (token, table_id, is_active) VALUES (?, ?, TRUE)",
            ).use { statement ->
                statement.setString(1, tableToken)
                statement.setLong(2, tableId)
                statement.executeUpdate()
            }
            val unrelatedSessionId =
                it.insertSession(
                    venueId = venueId,
                    tableId = unrelatedTableId,
                    startedAt = now.minus(Duration.ofHours(2)),
                    lastActivityAt = now.minus(Duration.ofHours(1)),
                    expiresAt = now.plus(Duration.ofHours(2)),
                )
            val unrelatedTabId =
                it.insertReturningId(
                    """
                    INSERT INTO tab (venue_id, table_session_id, type, owner_user_id, created_at, status)
                    VALUES (?, ?, 'PERSONAL', ?, ?, 'ACTIVE')
                    """.trimIndent(),
                ) { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, unrelatedSessionId)
                    statement.setLong(3, unrelatedUserId)
                    statement.setTimestamp(4, Timestamp.from(now.minus(Duration.ofMinutes(50))))
                }
            it.prepareStatement(
                "INSERT INTO tab_member (tab_id, user_id, role, created_at) VALUES (?, ?, 'OWNER', ?)",
            ).use { statement ->
                statement.setLong(1, unrelatedTabId)
                statement.setLong(2, unrelatedUserId)
                statement.setTimestamp(3, Timestamp.from(now.minus(Duration.ofMinutes(50))))
                statement.executeUpdate()
            }
            it.insertExit(
                userId = unrelatedUserId,
                tableSessionId = unrelatedSessionId,
                exitedAt = now.minus(Duration.ofMinutes(2)),
            )

            if (scenario.sessionState == TestActivationSessionState.EXISTING) {
                val tableSessionId =
                    it.insertSession(
                        venueId = venueId,
                        tableId = tableId,
                        startedAt = now.minus(Duration.ofHours(2)),
                        lastActivityAt = now.minus(Duration.ofHours(1)),
                        expiresAt = now.plus(Duration.ofMinutes(30)),
                    )
                it.insertExit(
                    userId = actorUserId,
                    tableSessionId = tableSessionId,
                    exitedAt = now.minus(Duration.ofMinutes(20)),
                )
            }

            it.insertDialog(
                chatId = chatId,
                payload = """{"draft":"target"}""",
                updatedAt = now.minus(Duration.ofMinutes(10)),
            )
            it.insertDialog(
                chatId = unrelatedChatId,
                payload = """{"draft":"unrelated"}""",
                updatedAt = now.minus(Duration.ofMinutes(9)),
            )
            if (scenario.contextState == TestActivationContextState.UPDATE) {
                it.insertContext(
                    chatId = chatId,
                    userId = actorUserId,
                    venueId = venueId,
                    tableId = tableId,
                    tableToken = "PREVIOUS_CONTEXT",
                    updatedAt = now.minus(Duration.ofDays(1)),
                )
            }
            it.insertContext(
                chatId = unrelatedChatId,
                userId = unrelatedUserId,
                venueId = venueId,
                tableId = unrelatedTableId,
                tableToken = "UNRELATED_CONTEXT",
                updatedAt = now.minus(Duration.ofMinutes(1)),
            )
            it.prepareStatement(
                """
                INSERT INTO analytics_events (
                    created_at,
                    event_type,
                    payload_json,
                    venue_id,
                    table_id,
                    table_session_id,
                    idempotency_key
                )
                VALUES (?, 'unrelated_fixture', '{}', ?, ?, ?, 'unrelated-activation-fixture')
                """.trimIndent(),
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now.minus(Duration.ofSeconds(45))))
                statement.setLong(2, venueId)
                statement.setLong(3, unrelatedTableId)
                statement.setLong(4, unrelatedSessionId)
                statement.executeUpdate()
            }
            it.prepareStatement(
                """
                INSERT INTO telegram_outbox (
                    chat_id,
                    method,
                    payload_json,
                    status,
                    attempts,
                    created_at,
                    next_attempt_at,
                    dedupe_key
                )
                VALUES (?, 'sendMessage', '{}', 'NEW', 0, ?, ?, 'unrelated-activation-fixture')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, unrelatedChatId)
                statement.setTimestamp(2, Timestamp.from(now.minus(Duration.ofSeconds(30))))
                statement.setTimestamp(3, Timestamp.from(now.plus(Duration.ofSeconds(30))))
                statement.executeUpdate()
            }
            return PostgresActivationFixture(
                actorUserId = actorUserId,
                chatId = chatId,
                venueId = venueId,
                tableId = tableId,
                tableToken = tableToken,
            )
        }
    }

    private fun Connection.insertSession(
        venueId: Long,
        tableId: Long,
        startedAt: Instant,
        lastActivityAt: Instant,
        expiresAt: Instant,
    ): Long =
        insertReturningId(
            """
            INSERT INTO table_sessions (
                venue_id,
                table_id,
                started_at,
                last_activity_at,
                expires_at,
                ended_at,
                status
            )
            VALUES (?, ?, ?, ?, ?, NULL, 'ACTIVE')
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.setTimestamp(3, Timestamp.from(startedAt))
            statement.setTimestamp(4, Timestamp.from(lastActivityAt))
            statement.setTimestamp(5, Timestamp.from(expiresAt))
        }

    private fun Connection.insertExit(
        userId: Long,
        tableSessionId: Long,
        exitedAt: Instant,
    ) {
        prepareStatement(
            "INSERT INTO guest_table_session_exits (user_id, table_session_id, exited_at) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, tableSessionId)
            statement.setTimestamp(3, Timestamp.from(exitedAt))
            statement.executeUpdate()
        }
    }

    private fun Connection.insertDialog(
        chatId: Long,
        payload: String,
        updatedAt: Instant,
    ) {
        prepareStatement(
            """
            INSERT INTO telegram_dialog_state (chat_id, state, payload, updated_at)
            VALUES (?, 'QUICK_ORDER_WAIT_TEXT', ?::jsonb, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, chatId)
            statement.setString(2, payload)
            statement.setTimestamp(3, Timestamp.from(updatedAt))
            statement.executeUpdate()
        }
    }

    private fun Connection.insertContext(
        chatId: Long,
        userId: Long,
        venueId: Long,
        tableId: Long,
        tableToken: String,
        updatedAt: Instant,
    ) {
        prepareStatement(
            """
            INSERT INTO telegram_chat_context (
                chat_id,
                user_id,
                venue_id,
                table_id,
                table_token,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, chatId)
            statement.setLong(2, userId)
            statement.setLong(3, venueId)
            statement.setLong(4, tableId)
            statement.setString(5, tableToken)
            statement.setTimestamp(6, Timestamp.from(updatedAt))
            statement.executeUpdate()
        }
    }

    private fun Connection.insertReturningId(
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit = {},
    ): Long =
        prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            bind(statement)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                check(keys.next())
                keys.getLong(1)
            }
        }

    private data class PostgresActivationFixture(
        val actorUserId: Long,
        val chatId: Long,
        val venueId: Long,
        val tableId: Long,
        val tableToken: String,
    )

    companion object {
        @JvmStatic
        fun rollbackScenarios(): List<TestActivationRollbackScenario> =
            listOf(
                TestActivationSessionState.NEW to TestActivationContextState.INSERT,
                TestActivationSessionState.EXISTING to TestActivationContextState.UPDATE,
            ).flatMap { (sessionState, contextState) ->
                GuestTableActivationCheckpoint.entries.map { checkpoint ->
                    TestActivationRollbackScenario(
                        sessionState = sessionState,
                        contextState = contextState,
                        checkpoint = checkpoint,
                    )
                }
            }
    }
}
