package com.hookah.platform.backend.telegram.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

class TelegramVenueContextRepository(private val dataSource: DataSource?) {
    suspend fun getSelectedVenue(
        chatId: Long,
        userId: Long,
    ): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT venue_id
                    FROM telegram_venue_context
                    WHERE chat_id = ? AND user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, chatId)
                    statement.setLong(2, userId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.getLong("venue_id") else null
                    }
                }
            }
        }
    }

    suspend fun setSelectedVenue(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ) {
        val ds = dataSource ?: return
        withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO telegram_venue_context (chat_id, user_id, venue_id, updated_at)
                    VALUES (?, ?, ?, now())
                    ON CONFLICT (chat_id, user_id) DO UPDATE SET
                        venue_id = EXCLUDED.venue_id,
                        updated_at = now()
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, chatId)
                    statement.setLong(2, userId)
                    statement.setLong(3, venueId)
                    statement.executeUpdate()
                }
            }
        }
    }

    suspend fun clearSelectedVenue(
        chatId: Long,
        userId: Long,
    ) {
        val ds = dataSource ?: return
        withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    "DELETE FROM telegram_venue_context WHERE chat_id = ? AND user_id = ?",
                ).use { statement ->
                    statement.setLong(1, chatId)
                    statement.setLong(2, userId)
                    statement.executeUpdate()
                }
            }
        }
    }
}
