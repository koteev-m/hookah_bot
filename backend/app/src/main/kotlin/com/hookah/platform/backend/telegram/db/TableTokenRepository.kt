package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.telegram.TableContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

class TableTokenRepository(private val dataSource: DataSource?) {
    suspend fun resolve(token: String): TableContext? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val sql = """
                    SELECT v.id, v.name, vt.id, vt.table_number, v.staff_chat_id
                    FROM table_tokens tt
                    JOIN venue_tables vt ON vt.id = tt.table_id
                    JOIN venues v ON v.id = vt.venue_id
                    WHERE tt.token = ? AND tt.is_active = true AND vt.is_active = true
                """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, token)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            TableContext(
                                venueId = rs.getLong(1),
                                venueName = rs.getString(2),
                                tableId = rs.getLong(3),
                                tableNumber = rs.getInt(4),
                                tableToken = token,
                                staffChatId = rs.getLong(5).takeIf { !rs.wasNull() }
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }
    }
}
