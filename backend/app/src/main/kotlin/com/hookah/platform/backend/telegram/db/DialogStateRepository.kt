package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.telegram.DialogState
import com.hookah.platform.backend.telegram.DialogStateType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import javax.sql.DataSource

class DialogStateRepository(
    private val dataSource: DataSource?,
    private val json: Json
) {
    suspend fun get(chatId: Long): DialogState {
        val ds = dataSource ?: return DialogState(DialogStateType.NONE)
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val sql = "SELECT state, payload FROM telegram_dialog_state WHERE chat_id = ?"
                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, chatId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            val state = DialogStateType.valueOf(rs.getString("state"))
                            val payloadJson = rs.getString("payload")
                            val payload = json.decodeFromString(MapSerializer, payloadJson)
                            DialogState(state, payload)
                        } else {
                            DialogState(DialogStateType.NONE)
                        }
                    }
                }
            }
        }
    }

    suspend fun set(chatId: Long, state: DialogState) {
        val ds = dataSource ?: return
        withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val sql = """
                    INSERT INTO telegram_dialog_state (chat_id, state, payload, updated_at)
                    VALUES (?, ?, ?::jsonb, now())
                    ON CONFLICT (chat_id) DO UPDATE SET
                        state = EXCLUDED.state,
                        payload = EXCLUDED.payload,
                        updated_at = now()
                """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, chatId)
                    statement.setString(2, state.state.name)
                    val payloadJson = json.encodeToJsonElement(MapSerializer, state.payload).toString()
                    statement.setString(3, payloadJson)
                    statement.executeUpdate()
                }
            }
        }
    }

    suspend fun clear(chatId: Long) {
        val ds = dataSource ?: return
        withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement("DELETE FROM telegram_dialog_state WHERE chat_id = ?").use { statement ->
                    statement.setLong(1, chatId)
                    statement.executeUpdate()
                }
            }
        }
    }

    private companion object {
        val MapSerializer = MapSerializer(String.serializer(), String.serializer())
    }
}
