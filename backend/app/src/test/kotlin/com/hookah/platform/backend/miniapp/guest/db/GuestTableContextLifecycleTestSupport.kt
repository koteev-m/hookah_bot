package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal class GuestTableContextLifecycleTestDatabase private constructor(
    val dataSource: JdbcDataSource,
    val actorUserId: Long,
    val chatId: Long,
    val venueId: Long,
    val tableId: Long,
    val tableToken: String,
) {
    fun lifecycleRepository(
        checkpoint: (GuestTableActivationCheckpoint) -> Unit = {},
    ): GuestTableContextLifecycleRepository {
        val analyticsEventRepository = AnalyticsEventRepository(dataSource)
        return GuestTableContextLifecycleRepository(
            dataSource = dataSource,
            tableTokenRepository = TableTokenRepository(dataSource),
            subscriptionRepository = SubscriptionRepository(dataSource),
            tableSessionRepository = TableSessionRepository(dataSource, analyticsEventRepository),
            guestTabsRepository = GuestTabsRepository(dataSource),
            chatContextRepository = ChatContextRepository(dataSource),
            dialogStateRepository = DialogStateRepository(dataSource, Json),
            activationCheckpoint = checkpoint,
        )
    }

    fun createActiveSession(
        startedAt: Instant,
        lastActivityAt: Instant,
        expiresAt: Instant,
    ): Long =
        connection { connection ->
            connection.prepareStatement(
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
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, tableId)
                statement.setTimestamp(3, Timestamp.from(startedAt))
                statement.setTimestamp(4, Timestamp.from(lastActivityAt))
                statement.setTimestamp(5, Timestamp.from(expiresAt))
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    check(keys.next())
                    keys.getLong(1)
                }
            }
        }

    fun createPersonalTab(tableSessionId: Long): Long =
        connection { connection ->
            val tabId =
                connection.prepareStatement(
                    """
                    INSERT INTO tab (venue_id, table_session_id, type, owner_user_id, status)
                    VALUES (?, ?, 'PERSONAL', ?, 'ACTIVE')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, tableSessionId)
                    statement.setLong(3, actorUserId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        check(keys.next())
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                "INSERT INTO tab_member (tab_id, user_id, role) VALUES (?, ?, 'OWNER')",
            ).use { statement ->
                statement.setLong(1, tabId)
                statement.setLong(2, actorUserId)
                statement.executeUpdate()
            }
            tabId
        }

    fun insertExit(
        tableSessionId: Long,
        exitedAt: Instant,
    ) {
        update(
            "INSERT INTO guest_table_session_exits (user_id, table_session_id, exited_at) VALUES (?, ?, ?)",
        ) { statement ->
            statement.setLong(1, actorUserId)
            statement.setLong(2, tableSessionId)
            statement.setTimestamp(3, Timestamp.from(exitedAt))
        }
    }

    fun insertContext(
        tableToken: String = this.tableToken,
        updatedAt: Instant,
        venueId: Long? = this.venueId,
        tableId: Long? = this.tableId,
    ) {
        update(
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
        ) { statement ->
            statement.setLong(1, chatId)
            statement.setLong(2, actorUserId)
            statement.setObject(3, venueId)
            statement.setObject(4, tableId)
            statement.setString(5, tableToken)
            statement.setTimestamp(6, Timestamp.from(updatedAt))
        }
    }

    fun insertDialog(
        state: String = "QUICK_ORDER_WAIT_TEXT",
        payload: String = "{\"draft\":\"keep\"}",
    ) {
        update(
            """
            INSERT INTO telegram_dialog_state (chat_id, state, payload, updated_at)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, chatId)
            statement.setString(2, state)
            statement.setString(3, payload)
        }
    }

    fun createActiveStaffCall(tableSessionId: Long) {
        update(
            """
            INSERT INTO staff_calls (
                venue_id,
                table_id,
                created_by_user_id,
                reason,
                status,
                table_session_id
            )
            VALUES (?, ?, ?, 'COME', 'NEW', ?)
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.setLong(3, actorUserId)
            statement.setLong(4, tableSessionId)
        }
    }

    fun seedUnrelatedActivationState(now: Instant) {
        connection { connection ->
            val unrelatedUserId = actorUserId + 1
            val unrelatedChatId = chatId + 1
            connection.prepareStatement(
                "INSERT INTO users (telegram_user_id, username, first_name) VALUES (?, 'unrelated', 'Unrelated')",
            ).use { statement ->
                statement.setLong(1, unrelatedUserId)
                statement.executeUpdate()
            }
            val unrelatedTableId =
                connection.prepareStatement(
                    "INSERT INTO venue_tables (venue_id, table_number, is_active) VALUES (?, 99, TRUE)",
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        check(keys.next())
                        keys.getLong(1)
                    }
                }
            val unrelatedSessionId =
                connection.prepareStatement(
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
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, unrelatedTableId)
                    statement.setTimestamp(3, Timestamp.from(now.minusSeconds(7_200)))
                    statement.setTimestamp(4, Timestamp.from(now.minusSeconds(3_600)))
                    statement.setTimestamp(5, Timestamp.from(now.plusSeconds(7_200)))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        check(keys.next())
                        keys.getLong(1)
                    }
                }
            val unrelatedTabId =
                connection.prepareStatement(
                    """
                    INSERT INTO tab (venue_id, table_session_id, type, owner_user_id, created_at, status)
                    VALUES (?, ?, 'PERSONAL', ?, ?, 'ACTIVE')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, unrelatedSessionId)
                    statement.setLong(3, unrelatedUserId)
                    statement.setTimestamp(4, Timestamp.from(now.minusSeconds(3_000)))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        check(keys.next())
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                "INSERT INTO tab_member (tab_id, user_id, role, created_at) VALUES (?, ?, 'OWNER', ?)",
            ).use { statement ->
                statement.setLong(1, unrelatedTabId)
                statement.setLong(2, unrelatedUserId)
                statement.setTimestamp(3, Timestamp.from(now.minusSeconds(3_000)))
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO guest_table_session_exits (user_id, table_session_id, exited_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setLong(1, unrelatedUserId)
                statement.setLong(2, unrelatedSessionId)
                statement.setTimestamp(3, Timestamp.from(now.minusSeconds(120)))
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO telegram_dialog_state (chat_id, state, payload, updated_at)
                VALUES (?, 'QUICK_ORDER_WAIT_TEXT', ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, unrelatedChatId)
                statement.setString(2, """{"draft":"unrelated"}""")
                statement.setTimestamp(3, Timestamp.from(now.minusSeconds(90)))
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO telegram_chat_context (
                    chat_id,
                    user_id,
                    venue_id,
                    table_id,
                    table_token,
                    updated_at
                )
                VALUES (?, ?, ?, ?, 'UNRELATED_CONTEXT', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, unrelatedChatId)
                statement.setLong(2, unrelatedUserId)
                statement.setLong(3, venueId)
                statement.setLong(4, unrelatedTableId)
                statement.setTimestamp(5, Timestamp.from(now.minusSeconds(60)))
                statement.executeUpdate()
            }
            connection.prepareStatement(
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
                statement.setTimestamp(1, Timestamp.from(now.minusSeconds(45)))
                statement.setLong(2, venueId)
                statement.setLong(3, unrelatedTableId)
                statement.setLong(4, unrelatedSessionId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
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
                statement.setTimestamp(2, Timestamp.from(now.minusSeconds(30)))
                statement.setTimestamp(3, Timestamp.from(now.plusSeconds(30)))
                statement.executeUpdate()
            }
        }
    }

    fun activationAuthoritativeSnapshot(): TestActivationAuthoritativeSnapshot =
        connection(::loadTestActivationAuthoritativeSnapshot)

    fun sessionSnapshot(tableSessionId: Long): TestSessionSnapshot? =
        queryOne(
            """
            SELECT id, started_at, last_activity_at, expires_at, ended_at, status
            FROM table_sessions
            WHERE id = ?
            """.trimIndent(),
            bind = { it.setLong(1, tableSessionId) },
        ) { rs ->
            TestSessionSnapshot(
                id = rs.getLong("id"),
                startedAt = rs.getTimestamp("started_at").toInstant(),
                lastActivityAt = rs.getTimestamp("last_activity_at").toInstant(),
                expiresAt = rs.getTimestamp("expires_at").toInstant(),
                endedAt = rs.getTimestamp("ended_at")?.toInstant(),
                status = rs.getString("status"),
            )
        }

    fun contextSnapshot(): TestContextSnapshot? =
        queryOne(
            """
            SELECT chat_id, user_id, venue_id, table_id, table_token, updated_at
            FROM telegram_chat_context
            WHERE chat_id = ?
            """.trimIndent(),
            bind = { it.setLong(1, chatId) },
        ) { rs ->
            TestContextSnapshot(
                chatId = rs.getLong("chat_id"),
                userId = rs.getLong("user_id"),
                venueId = rs.getLong("venue_id").takeIf { !rs.wasNull() },
                tableId = rs.getLong("table_id").takeIf { !rs.wasNull() },
                tableToken = rs.getString("table_token"),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }

    fun dialogSnapshot(): Pair<String, String>? =
        queryOne(
            "SELECT state, payload FROM telegram_dialog_state WHERE chat_id = ?",
            bind = { it.setLong(1, chatId) },
        ) { rs -> rs.getString("state") to rs.getString("payload") }

    fun exitSnapshot(tableSessionId: Long): Instant? =
        queryOne(
            """
            SELECT exited_at
            FROM guest_table_session_exits
            WHERE user_id = ? AND table_session_id = ?
            """.trimIndent(),
            bind = { statement ->
                statement.setLong(1, actorUserId)
                statement.setLong(2, tableSessionId)
            },
        ) { rs -> rs.getTimestamp("exited_at").toInstant() }

    fun countRows(tableName: String): Int =
        connection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $tableName").use { rs ->
                    check(rs.next())
                    rs.getInt(1)
                }
            }
        }

    fun update(
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit = {},
    ): Int =
        connection { connection ->
            connection.prepareStatement(sql).use { statement ->
                bind(statement)
                statement.executeUpdate()
            }
        }

    fun <T> connection(block: (Connection) -> T): T = dataSource.connection.use(block)

    private fun <T> queryOne(
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit,
        map: (ResultSet) -> T,
    ): T? =
        connection { connection ->
            connection.prepareStatement(sql).use { statement ->
                bind(statement)
                statement.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
            }
        }

    companion object {
        const val DEFAULT_ACTOR_USER_ID = 9_001L
        const val DEFAULT_CHAT_ID = 8_001L
        const val DEFAULT_TABLE_TOKEN = "TABLE_TOKEN_CONFIRMED"

        fun create(name: String): GuestTableContextLifecycleTestDatabase {
            val jdbcUrl =
                "jdbc:h2:mem:$name-${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
            Flyway
                .configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration/h2")
                .load()
                .migrate()
            val dataSource =
                JdbcDataSource().apply {
                    setURL(jdbcUrl)
                    user = "sa"
                    password = ""
                }
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE telegram_chat_context (
                            chat_id BIGINT PRIMARY KEY,
                            user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
                            venue_id BIGINT NULL REFERENCES venues(id) ON DELETE SET NULL,
                            table_id BIGINT NULL REFERENCES venue_tables(id) ON DELETE SET NULL,
                            table_token VARCHAR(64) NULL,
                            updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )
                        """.trimIndent(),
                    )
                }
                connection.prepareStatement(
                    "INSERT INTO users (telegram_user_id, username, first_name) VALUES (?, 'platform', 'Platform')",
                ).use { statement ->
                    statement.setLong(1, DEFAULT_ACTOR_USER_ID)
                    statement.executeUpdate()
                }
                val venueId =
                    connection.prepareStatement(
                        """
                        INSERT INTO venues (name, city, address, status)
                        VALUES ('Test Venue', 'Москва', 'Тверская, 1', 'PUBLISHED')
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.executeUpdate()
                        statement.generatedKeys.use { keys ->
                            check(keys.next())
                            keys.getLong(1)
                        }
                    }
                connection.prepareStatement(
                    """
                    MERGE INTO venue_subscriptions (venue_id, status, updated_at)
                    KEY (venue_id)
                    VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeUpdate()
                }
                val tableId =
                    connection.prepareStatement(
                        """
                        INSERT INTO venue_tables (venue_id, table_number, is_active)
                        VALUES (?, 7, TRUE)
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeUpdate()
                        statement.generatedKeys.use { keys ->
                            check(keys.next())
                            keys.getLong(1)
                        }
                    }
                connection.prepareStatement(
                    "INSERT INTO table_tokens (token, table_id, is_active) VALUES (?, ?, TRUE)",
                ).use { statement ->
                    statement.setString(1, DEFAULT_TABLE_TOKEN)
                    statement.setLong(2, tableId)
                    statement.executeUpdate()
                }
                return GuestTableContextLifecycleTestDatabase(
                    dataSource = dataSource,
                    actorUserId = DEFAULT_ACTOR_USER_ID,
                    chatId = DEFAULT_CHAT_ID,
                    venueId = venueId,
                    tableId = tableId,
                    tableToken = DEFAULT_TABLE_TOKEN,
                )
            }
        }
    }
}

internal data class TestSessionSnapshot(
    val id: Long,
    val startedAt: Instant,
    val lastActivityAt: Instant,
    val expiresAt: Instant,
    val endedAt: Instant?,
    val status: String,
)

internal data class TestContextSnapshot(
    val chatId: Long,
    val userId: Long,
    val venueId: Long?,
    val tableId: Long?,
    val tableToken: String,
    val updatedAt: Instant,
)

enum class TestActivationSessionState {
    NEW,
    EXISTING,
}

enum class TestActivationContextState {
    INSERT,
    UPDATE,
}

data class TestActivationRollbackScenario(
    val sessionState: TestActivationSessionState,
    val contextState: TestActivationContextState,
    val checkpoint: GuestTableActivationCheckpoint,
) {
    override fun toString(): String = "${sessionState.name}/${contextState.name}/${checkpoint.name}"
}

internal data class TestActivationAuthoritativeSnapshot(
    val sessions: List<TestActivationSessionRow>,
    val exits: List<TestActivationExitRow>,
    val dialogs: List<TestActivationDialogRow>,
    val contexts: List<TestActivationContextRow>,
    val tabs: List<TestActivationTabRow>,
    val tabMembers: List<TestActivationTabMemberRow>,
    val analyticsEvents: List<TestActivationAnalyticsRow>,
    val telegramOutbox: List<TestActivationOutboxRow>,
)

internal data class TestActivationSessionRow(
    val id: Long,
    val venueId: Long,
    val tableId: Long,
    val startedAt: Instant,
    val lastActivityAt: Instant,
    val expiresAt: Instant,
    val endedAt: Instant?,
    val status: String,
)

internal data class TestActivationExitRow(
    val userId: Long,
    val tableSessionId: Long,
    val exitedAt: Instant,
)

internal data class TestActivationDialogRow(
    val chatId: Long,
    val state: String,
    val payload: String,
    val updatedAt: Instant,
)

internal data class TestActivationContextRow(
    val chatId: Long,
    val userId: Long,
    val venueId: Long?,
    val tableId: Long?,
    val tableToken: String?,
    val updatedAt: Instant,
)

internal data class TestActivationTabRow(
    val id: Long,
    val venueId: Long,
    val tableSessionId: Long,
    val type: String,
    val ownerUserId: Long?,
    val createdAt: Instant,
    val status: String,
)

internal data class TestActivationTabMemberRow(
    val tabId: Long,
    val userId: Long,
    val role: String,
    val createdAt: Instant,
)

internal data class TestActivationAnalyticsRow(
    val id: Long,
    val createdAt: Instant,
    val eventType: String,
    val payloadJson: String,
    val venueId: Long?,
    val tableId: Long?,
    val tableSessionId: Long?,
    val orderId: Long?,
    val batchId: Long?,
    val tabId: Long?,
    val idempotencyKey: String,
)

internal data class TestActivationOutboxRow(
    val id: Long,
    val chatId: Long,
    val method: String,
    val payloadJson: String,
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val createdAt: Instant,
    val processedAt: Instant?,
    val nextAttemptAt: Instant?,
    val dedupeKey: String?,
)

internal fun loadTestActivationAuthoritativeSnapshot(connection: Connection): TestActivationAuthoritativeSnapshot =
    TestActivationAuthoritativeSnapshot(
        sessions =
            connection.queryRows(
                """
                SELECT id, venue_id, table_id, started_at, last_activity_at, expires_at, ended_at, status
                FROM table_sessions
                ORDER BY id
                """.trimIndent(),
            ) { rs ->
                TestActivationSessionRow(
                    id = rs.getLong("id"),
                    venueId = rs.getLong("venue_id"),
                    tableId = rs.getLong("table_id"),
                    startedAt = rs.getTimestamp("started_at").toInstant(),
                    lastActivityAt = rs.getTimestamp("last_activity_at").toInstant(),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                    endedAt = rs.getTimestamp("ended_at")?.toInstant(),
                    status = rs.getString("status"),
                )
            },
        exits =
            connection.queryRows(
                """
                SELECT user_id, table_session_id, exited_at
                FROM guest_table_session_exits
                ORDER BY user_id, table_session_id
                """.trimIndent(),
            ) { rs ->
                TestActivationExitRow(
                    userId = rs.getLong("user_id"),
                    tableSessionId = rs.getLong("table_session_id"),
                    exitedAt = rs.getTimestamp("exited_at").toInstant(),
                )
            },
        dialogs =
            connection.queryRows(
                """
                SELECT chat_id, state, payload, updated_at
                FROM telegram_dialog_state
                ORDER BY chat_id
                """.trimIndent(),
            ) { rs ->
                TestActivationDialogRow(
                    chatId = rs.getLong("chat_id"),
                    state = rs.getString("state"),
                    payload = rs.getString("payload"),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                )
            },
        contexts =
            connection.queryRows(
                """
                SELECT chat_id, user_id, venue_id, table_id, table_token, updated_at
                FROM telegram_chat_context
                ORDER BY chat_id
                """.trimIndent(),
            ) { rs ->
                TestActivationContextRow(
                    chatId = rs.getLong("chat_id"),
                    userId = rs.getLong("user_id"),
                    venueId = rs.nullableLong("venue_id"),
                    tableId = rs.nullableLong("table_id"),
                    tableToken = rs.getString("table_token"),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                )
            },
        tabs =
            connection.queryRows(
                """
                SELECT id, venue_id, table_session_id, type, owner_user_id, created_at, status
                FROM tab
                ORDER BY id
                """.trimIndent(),
            ) { rs ->
                TestActivationTabRow(
                    id = rs.getLong("id"),
                    venueId = rs.getLong("venue_id"),
                    tableSessionId = rs.getLong("table_session_id"),
                    type = rs.getString("type"),
                    ownerUserId = rs.nullableLong("owner_user_id"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    status = rs.getString("status"),
                )
            },
        tabMembers =
            connection.queryRows(
                """
                SELECT tab_id, user_id, role, created_at
                FROM tab_member
                ORDER BY tab_id, user_id
                """.trimIndent(),
            ) { rs ->
                TestActivationTabMemberRow(
                    tabId = rs.getLong("tab_id"),
                    userId = rs.getLong("user_id"),
                    role = rs.getString("role"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            },
        analyticsEvents =
            connection.queryRows(
                """
                SELECT
                    id,
                    created_at,
                    event_type,
                    payload_json,
                    venue_id,
                    table_id,
                    table_session_id,
                    order_id,
                    batch_id,
                    tab_id,
                    idempotency_key
                FROM analytics_events
                ORDER BY id
                """.trimIndent(),
            ) { rs ->
                TestActivationAnalyticsRow(
                    id = rs.getLong("id"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    eventType = rs.getString("event_type"),
                    payloadJson = rs.getString("payload_json"),
                    venueId = rs.nullableLong("venue_id"),
                    tableId = rs.nullableLong("table_id"),
                    tableSessionId = rs.nullableLong("table_session_id"),
                    orderId = rs.nullableLong("order_id"),
                    batchId = rs.nullableLong("batch_id"),
                    tabId = rs.nullableLong("tab_id"),
                    idempotencyKey = rs.getString("idempotency_key"),
                )
            },
        telegramOutbox =
            connection.queryRows(
                """
                SELECT
                    id,
                    chat_id,
                    method,
                    payload_json,
                    status,
                    attempts,
                    last_error,
                    created_at,
                    processed_at,
                    next_attempt_at,
                    dedupe_key
                FROM telegram_outbox
                ORDER BY id
                """.trimIndent(),
            ) { rs ->
                TestActivationOutboxRow(
                    id = rs.getLong("id"),
                    chatId = rs.getLong("chat_id"),
                    method = rs.getString("method"),
                    payloadJson = rs.getString("payload_json"),
                    status = rs.getString("status"),
                    attempts = rs.getInt("attempts"),
                    lastError = rs.getString("last_error"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    processedAt = rs.getTimestamp("processed_at")?.toInstant(),
                    nextAttemptAt = rs.getTimestamp("next_attempt_at")?.toInstant(),
                    dedupeKey = rs.getString("dedupe_key"),
                )
            },
    )

private fun <T> Connection.queryRows(
    sql: String,
    map: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { statement ->
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(map(rs))
                }
            }
        }
    }

private fun ResultSet.nullableLong(column: String): Long? =
    getLong(column).let { value -> value.takeUnless { wasNull() } }
