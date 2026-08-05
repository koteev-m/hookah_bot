package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
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
