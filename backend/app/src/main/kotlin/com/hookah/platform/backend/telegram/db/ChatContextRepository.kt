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
    suspend fun saveContext(
        chatId: Long,
        userId: Long,
        context: TableContext,
    ) {
        val ds = dataSource ?: return
        withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    saveContext(connection, chatId, userId, context, Instant.now())
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

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

    fun saveContext(
        connection: Connection,
        chatId: Long,
        userId: Long,
        context: TableContext,
        updatedAt: Instant,
    ) {
        val updated =
            connection.prepareStatement(
                """
                UPDATE telegram_chat_context
                SET user_id = ?,
                    venue_id = ?,
                    table_id = ?,
                    table_token = ?,
                    updated_at = ?
                WHERE chat_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, context.venueId)
                statement.setLong(3, context.tableId)
                statement.setString(4, context.tableToken)
                statement.setTimestamp(5, Timestamp.from(updatedAt))
                statement.setLong(6, chatId)
                statement.executeUpdate()
            }
        if (updated > 0) {
            return
        }
        insertContext(connection, chatId, userId, context, updatedAt)
    }

    private fun insertContext(
        connection: Connection,
        chatId: Long,
        userId: Long,
        context: TableContext,
        updatedAt: Instant,
    ) {
        val savepoint = if (connection.autoCommit) null else connection.setSavepoint()
        try {
            connection.prepareStatement(
                """
                INSERT INTO telegram_chat_context (chat_id, user_id, venue_id, table_id, table_token, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, chatId)
                statement.setLong(2, userId)
                statement.setLong(3, context.venueId)
                statement.setLong(4, context.tableId)
                statement.setString(5, context.tableToken)
                statement.setTimestamp(6, Timestamp.from(updatedAt))
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
                        updated_at = ?
                    WHERE chat_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setLong(2, context.venueId)
                    statement.setLong(3, context.tableId)
                    statement.setString(4, context.tableToken)
                    statement.setTimestamp(5, Timestamp.from(updatedAt))
                    statement.setLong(6, chatId)
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
            SELECT chat_id, user_id, venue_id, table_id, table_token, updated_at
            FROM telegram_chat_context
            WHERE chat_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, chatId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toStoredChatContext() else null }
        }

    fun getForUpdate(
        connection: Connection,
        chatId: Long,
        userId: Long? = null,
    ): StoredChatContext? {
        val userFilter = if (userId == null) "" else " AND user_id = ?"
        return connection.prepareStatement(
            """
            SELECT chat_id, user_id, venue_id, table_id, table_token, updated_at
            FROM telegram_chat_context
            WHERE chat_id = ?
              $userFilter
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, chatId)
            userId?.let { statement.setLong(2, it) }
            statement.executeQuery().use { rs -> if (rs.next()) rs.toStoredChatContext() else null }
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
            SELECT chat_id, user_id, venue_id, table_id, table_token, updated_at
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

    fun findActorTableContexts(
        connection: Connection,
        userId: Long,
        venueId: Long,
        tableId: Long,
    ): List<StoredChatContext> =
        connection.prepareStatement(
            """
            SELECT chat_id, user_id, venue_id, table_id, table_token, updated_at
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
            SELECT chat_id, user_id, venue_id, table_id, table_token, updated_at
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
)
