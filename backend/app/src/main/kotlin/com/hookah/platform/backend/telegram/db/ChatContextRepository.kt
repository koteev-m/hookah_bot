package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.telegram.TableContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

class ChatContextRepository(private val dataSource: DataSource?) {
    suspend fun saveContext(chatId: Long, userId: Long, context: TableContext) {
        val ds = dataSource ?: return
        withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val sql = """
                    INSERT INTO telegram_chat_context (chat_id, user_id, venue_id, table_id, table_token, updated_at)
                    VALUES (?, ?, ?, ?, ?, now())
                    ON CONFLICT (chat_id) DO UPDATE SET
                        user_id = EXCLUDED.user_id,
                        venue_id = EXCLUDED.venue_id,
                        table_id = EXCLUDED.table_id,
                        table_token = EXCLUDED.table_token,
                        updated_at = now()
                """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, chatId)
                    statement.setLong(2, userId)
                    statement.setLong(3, context.venueId)
                    statement.setLong(4, context.tableId)
                    statement.setString(5, context.tableToken)
                    statement.executeUpdate()
                }
            }
        }
    }

    suspend fun clear(chatId: Long) {
        val ds = dataSource ?: return
        withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement("DELETE FROM telegram_chat_context WHERE chat_id = ?").use { statement ->
                    statement.setLong(1, chatId)
                    statement.executeUpdate()
                }
            }
        }
    }

    suspend fun get(chatId: Long): StoredChatContext? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val sql = """
                    SELECT user_id, table_token
                    FROM telegram_chat_context
                    WHERE chat_id = ?
                """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, chatId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            val token = rs.getString("table_token") ?: return@use null
                            StoredChatContext(userId = rs.getLong("user_id"), tableToken = token)
                        } else null
                    }
                }
            }
        }
    }
}

data class StoredChatContext(
    val userId: Long,
    val tableToken: String
)
