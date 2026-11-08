package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.telegram.DialogState
import com.hookah.platform.backend.telegram.DialogStateType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

data class QuickOrderRequest(val chatId: Long, val requestId: String)

class DialogStateRepository(
    private val dataSource: DataSource?,
    private val json: Json,
) {
    suspend fun get(chatId: Long): DialogState {
        val ds = dataSource ?: return DialogState(DialogStateType.NONE)
        return withContext(Dispatchers.IO) {
            try {
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
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun set(
        chatId: Long,
        state: DialogState,
    ) {
        val ds = dataSource ?: return
        withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
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

    /** Checkout holds this same row lock until its order/idempotency transaction commits. */
    suspend fun transitionQuickOrderRequest(
        chatId: Long,
        requestId: String,
        nextState: DialogState = DialogState(DialogStateType.NONE),
        allowCommitted: Boolean = false,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val changed =
                            transitionQuickOrderRequest(connection, chatId, requestId, nextState, allowCommitted)
                        connection.commit()
                        changed
                    } catch (e: Exception) {
                        connection.rollback()
                        throw e
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    internal fun lockQuickOrderRequest(
        connection: Connection,
        chatId: Long,
        requestId: String,
    ): DialogState? =
        connection.prepareStatement(
            """
            SELECT payload FROM telegram_dialog_state
            WHERE chat_id = ? AND state = 'QUICK_ORDER_WAIT_CONFIRM' AND payload ->> 'requestId' = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, chatId)
            statement.setString(2, requestId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    DialogState(
                        DialogStateType.QUICK_ORDER_WAIT_CONFIRM,
                        json.decodeFromString(MapSerializer, rs.getString(1)),
                    )
                } else {
                    null
                }
            }
        }

    internal fun transitionQuickOrderRequest(
        connection: Connection,
        chatId: Long,
        requestId: String,
        nextState: DialogState = DialogState(DialogStateType.NONE),
        allowCommitted: Boolean = false,
    ): Boolean {
        val current = lockQuickOrderRequest(connection, chatId, requestId) ?: return false
        // A separate statement after acquiring the dialog lock sees a checkout that just committed.
        // This read takes no order/session locks, so edit/cancel never acquire venue after dialog.
        if (!allowCommitted && hasCommittedQuickOrder(connection, current)) return false
        connection.prepareStatement(
            "UPDATE telegram_dialog_state SET state = ?, payload = ?::jsonb, updated_at = now() WHERE chat_id = ?",
        ).use { statement ->
            statement.setString(1, nextState.state.name)
            statement.setString(2, json.encodeToJsonElement(MapSerializer, nextState.payload).toString())
            statement.setLong(3, chatId)
            return statement.executeUpdate() == 1
        }
    }

    private fun hasCommittedQuickOrder(
        connection: Connection,
        state: DialogState,
    ): Boolean {
        val sessionId = state.payload["tableSessionId"]?.toLongOrNull() ?: return false
        return connection.prepareStatement(
            """
            SELECT 1 FROM guest_batch_idempotency
            WHERE venue_id = (SELECT venue_id FROM table_sessions WHERE id = ?)
              AND table_session_id = ? AND idempotency_key = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, sessionId)
            statement.setLong(2, sessionId)
            statement.setString(3, "bot-quick:${state.payload.getValue("requestId")}")
            statement.executeQuery().use { it.next() }
        }
    }

    fun clear(
        connection: Connection,
        chatId: Long,
    ) {
        connection.prepareStatement("DELETE FROM telegram_dialog_state WHERE chat_id = ?").use { statement ->
            statement.setLong(1, chatId)
            statement.executeUpdate()
        }
    }

    private companion object {
        val MapSerializer = MapSerializer(String.serializer(), String.serializer())
    }
}
