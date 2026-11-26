package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.telegram.TableContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class ChatContextRepository(private val dataSource: DataSource?) {
    suspend fun clear(chatId: Long) {
        val ds = dataSource ?: return
        withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    clear(connection, chatId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun get(chatId: Long): StoredChatContext? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    get(connection, chatId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    // The first confirmation has no chat row to lock. Serialize that actor's
    // confirmations before reading the binding, including competing WebViews.
    fun lockActorForBinding(
        connection: Connection,
        actorUserId: Long,
    ): Boolean {
        val lock = if (connection.metaData.databaseProductName == "PostgreSQL") "FOR NO KEY UPDATE" else "FOR UPDATE"
        return connection.prepareStatement("SELECT telegram_user_id FROM users WHERE telegram_user_id = ? $lock").use {
            it.setLong(1, actorUserId)
            it.executeQuery().use { rows -> rows.next() }
        }
    }

    fun saveContext(
        connection: Connection,
        chatId: Long,
        userId: Long,
        context: TableContext,
        updatedAt: Instant,
        tableSessionId: Long,
    ) {
        val updated =
            connection.prepareStatement(
                """
                UPDATE telegram_chat_context
                SET user_id = ?,
                    venue_id = ?,
                    table_id = ?,
                    table_token = ?,
                    updated_at = ?,
                    table_session_id = ?
                WHERE chat_id = ? AND user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, context.venueId)
                statement.setLong(3, context.tableId)
                statement.setString(4, context.tableToken)
                statement.setTimestamp(5, Timestamp.from(updatedAt))
                statement.setLong(6, tableSessionId)
                statement.setLong(7, chatId)
                statement.setLong(8, userId)
                statement.executeUpdate()
            }
        if (updated > 0) {
            return
        }
        insertContext(connection, chatId, userId, context, updatedAt, tableSessionId)
    }

    private fun insertContext(
        connection: Connection,
        chatId: Long,
        userId: Long,
        context: TableContext,
        updatedAt: Instant,
        tableSessionId: Long,
    ) {
        val savepoint = if (connection.autoCommit) null else connection.setSavepoint()
        try {
            connection.prepareStatement(
                """
                INSERT INTO telegram_chat_context (chat_id, user_id, venue_id, table_id, table_token, updated_at, table_session_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, chatId)
                statement.setLong(2, userId)
                statement.setLong(3, context.venueId)
                statement.setLong(4, context.tableId)
                statement.setString(5, context.tableToken)
                statement.setTimestamp(6, Timestamp.from(updatedAt))
                statement.setLong(7, tableSessionId)
                statement.executeUpdate()
            }
        } catch (e: SQLException) {
            if (!e.isDuplicateKeyViolation()) {
                throw e
            }
            savepoint?.let { connection.rollback(it) }
            val updated =
                connection.prepareStatement(
                    """
                    UPDATE telegram_chat_context
                    SET user_id = ?,
                        venue_id = ?,
                        table_id = ?,
                        table_token = ?,
                        updated_at = ?,
                        table_session_id = ?
                    WHERE chat_id = ? AND user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setLong(2, context.venueId)
                    statement.setLong(3, context.tableId)
                    statement.setString(4, context.tableToken)
                    statement.setTimestamp(5, Timestamp.from(updatedAt))
                    statement.setLong(6, tableSessionId)
                    statement.setLong(7, chatId)
                    statement.setLong(8, userId)
                    statement.executeUpdate()
                }
            if (updated != 1) {
                throw SQLException("Failed to save chat context")
            }
        } finally {
            savepoint?.let { runCatching { connection.releaseSavepoint(it) } }
        }
    }

    fun clear(
        connection: Connection,
        chatId: Long,
        userId: Long? = null,
    ) {
        val userFilter = if (userId == null) "" else " AND user_id = ?"
        connection.prepareStatement(
            "DELETE FROM telegram_chat_context WHERE chat_id = ?$userFilter",
        ).use { statement ->
            statement.setLong(1, chatId)
            userId?.let { statement.setLong(2, it) }
            statement.executeUpdate()
        }
    }

    fun get(
        connection: Connection,
        chatId: Long,
    ): StoredChatContext? =
        connection.prepareStatement(
            """
            SELECT c.*, COALESCE(g.consumed_exit_event_id, 0) AS generation
            FROM telegram_chat_context c LEFT JOIN guest_visit_generations g
              ON g.user_id = c.user_id AND g.table_session_id = c.table_session_id
            WHERE chat_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, chatId)
            statement.executeQuery().use {
                    rs ->
                if (rs.next()) rs.toStoredChatContext()?.copy(visitGeneration = rs.getLong("generation")) else null
            }
        }

    fun getForUpdate(
        connection: Connection,
        chatId: Long,
        userId: Long? = null,
    ): StoredChatContext? = lockBinding(connection, chatId, userId)?.context

    data class LockedBinding(val userId: Long, val context: StoredChatContext?)

    fun lockBinding(
        connection: Connection,
        chatId: Long,
        userId: Long? = null,
    ): LockedBinding? {
        val userFilter = if (userId == null) "" else " AND user_id = ?"
        return connection.prepareStatement(
            """
            SELECT chat_id, user_id, venue_id, table_id, table_token, updated_at, table_session_id
            FROM telegram_chat_context
            WHERE chat_id = ?
              $userFilter
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, chatId)
            userId?.let { statement.setLong(2, it) }
            statement.executeQuery().use { rs ->
                if (rs.next()) LockedBinding(rs.getLong("user_id"), rs.toStoredChatContext()) else null
            }
        }
    }

    // A null token is invisible to all active-context readers. It retains the Bot chat
    // binding for an explicitly exited visit that never obtained a personal bill.
    fun detachTableToken(
        connection: Connection,
        chatId: Long,
        userId: Long,
        now: Instant,
    ) {
        connection.prepareStatement(
            "UPDATE telegram_chat_context SET table_token = NULL, updated_at = ? WHERE chat_id = ? AND user_id = ?",
        ).use {
            it.setTimestamp(1, Timestamp.from(now))
            it.setLong(2, chatId)
            it.setLong(3, userId)
            check(it.executeUpdate() == 1)
        }
    }

    fun findExitedBotChat(
        connection: Connection,
        userId: Long,
        venueId: Long,
        tableId: Long,
        sessionId: Long,
        eventId: Long,
        forUpdate: Boolean,
    ): Long? =
        connection.prepareStatement(
            """
            SELECT c.chat_id FROM telegram_chat_context c
            WHERE c.user_id = ? AND c.venue_id = ? AND c.table_id = ? AND c.table_token IS NULL
              AND EXISTS (
                  SELECT 1 FROM guest_table_session_exits e
                  JOIN table_sessions s ON s.id = e.table_session_id
                  WHERE e.user_id = c.user_id AND e.table_session_id = ? AND e.exit_event_id = ?
                    AND e.exited_at = c.updated_at AND e.table_session_id = c.table_session_id
                    AND s.venue_id = c.venue_id AND s.table_id = c.table_id
              )
              AND NOT EXISTS (
                  SELECT 1 FROM tab t WHERE t.table_session_id = ? AND t.owner_user_id = c.user_id
              )
            ORDER BY c.chat_id LIMIT 2
            """.trimIndent() + if (forUpdate) " FOR UPDATE" else "",
        ).use {
            it.setLong(1, userId)
            it.setLong(2, venueId)
            it.setLong(3, tableId)
            it.setLong(4, sessionId)
            it.setLong(5, eventId)
            it.setLong(6, sessionId)
            it.executeQuery().use { rows ->
                if (rows.next()) {
                    val chatId = rows.getLong(1)
                    chatId.takeUnless { rows.next() }
                } else {
                    null
                }
            }
        }

    fun findActorTokenContextsForUpdate(
        connection: Connection,
        userId: Long,
        tableToken: String,
        venueId: Long? = null,
        tableId: Long? = null,
    ): List<StoredChatContext> {
        val venueFilter = if (venueId == null) "" else " AND venue_id = ?"
        val tableFilter = if (tableId == null) "" else " AND table_id = ?"
        return connection.prepareStatement(
            """
            SELECT chat_id, user_id, venue_id, table_id, table_token, updated_at, table_session_id
            FROM telegram_chat_context
            WHERE user_id = ?
              AND table_token = ?
              $venueFilter
              $tableFilter
            ORDER BY chat_id
            LIMIT 2
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            var parameterIndex = 1
            statement.setLong(parameterIndex++, userId)
            statement.setString(parameterIndex++, tableToken)
            venueId?.let { statement.setLong(parameterIndex++, it) }
            tableId?.let { statement.setLong(parameterIndex, it) }
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        rs.toStoredChatContext()?.let(::add)
                    }
                }
            }
        }
    }

    fun findActorContexts(
        connection: Connection,
        userId: Long,
    ): List<StoredChatContext> =
        connection.prepareStatement(
            """
            SELECT chat_id, user_id, venue_id, table_id, table_token, updated_at, table_session_id
            FROM telegram_chat_context WHERE user_id = ? AND table_token IS NOT NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) rows.toStoredChatContext()?.let(::add) }
            }
        }

    fun findActorTableContexts(
        connection: Connection,
        userId: Long,
        venueId: Long,
        tableId: Long,
    ): List<StoredChatContext> =
        connection.prepareStatement(
            """
            SELECT chat_id, user_id, venue_id, table_id, table_token, updated_at, table_session_id
            FROM telegram_chat_context
            WHERE user_id = ?
              AND venue_id = ?
              AND table_id = ?
            ORDER BY chat_id
            LIMIT 2
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, venueId)
            statement.setLong(3, tableId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        rs.toStoredChatContext()?.let(::add)
                    }
                }
            }
        }

    fun findActorTableContextsForUpdate(
        connection: Connection,
        userId: Long,
        venueId: Long,
        tableId: Long,
    ): List<StoredChatContext> =
        connection.prepareStatement(
            """
            SELECT chat_id, user_id, venue_id, table_id, table_token, updated_at, table_session_id
            FROM telegram_chat_context
            WHERE user_id = ?
              AND venue_id = ?
              AND table_id = ?
            ORDER BY chat_id
            LIMIT 2
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, venueId)
            statement.setLong(3, tableId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        rs.toStoredChatContext()?.let(::add)
                    }
                }
            }
        }

    private fun ResultSet.toStoredChatContext(): StoredChatContext? {
        val token = getString("table_token") ?: return null
        return StoredChatContext(
            userId = getLong("user_id"),
            tableToken = token,
            chatId = getLong("chat_id"),
            venueId = getLong("venue_id").takeIf { !wasNull() },
            tableId = getLong("table_id").takeIf { !wasNull() },
            tableSessionId = getLong("table_session_id").takeUnless { wasNull() },
            updatedAt = getTimestamp("updated_at")?.toInstant(),
        )
    }

    private fun SQLException.isDuplicateKeyViolation(): Boolean {
        if (sqlState == "23505") {
            return true
        }
        return generateSequence(nextException) { it.nextException }.any { it.sqlState == "23505" }
    }
}

data class StoredChatContext(
    val userId: Long,
    val tableToken: String,
    val chatId: Long? = null,
    val venueId: Long? = null,
    val tableId: Long? = null,
    val updatedAt: Instant? = null,
    val tableSessionId: Long? = null,
    val visitGeneration: Long = 0,
)
