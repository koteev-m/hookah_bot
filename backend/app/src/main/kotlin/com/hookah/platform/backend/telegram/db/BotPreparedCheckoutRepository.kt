package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.promotions.GiftDecisionCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

data class BotCheckoutScope(val chatId: Long, val userId: Long, val sessionId: Long, val generation: Long)

@Serializable
data class BotPreparedCheckout(
    val id: String,
    val tabId: Long,
    val comment: String?,
    val items: List<OrderBatchItemInput>,
    val gift: GiftDecisionCommand?,
    val previewFingerprint: String?,
    val sourceProcess: String? = null,
    val sourceRevision: Long? = null,
    val draft: List<BotDraftCartItem> = emptyList(),
    val draftComment: String? = null,
) {
    val idempotencyKey: String get() = "bot-cart-checkout:$id"
}

@Serializable
data class BotDraftCartItem(
    val lineId: Long,
    val itemId: Long,
    val name: String,
    val optionId: Long?,
    val optionName: String?,
    val preferenceNote: String?,
    val priceMinor: Long,
    val currency: String,
    val qty: Int,
    val orderSeq: Long,
)

class BotPreparedCheckoutRepository(private val dataSource: DataSource) {
    suspend fun find(scope: BotCheckoutScope): BotPreparedCheckout? = readWrite { find(it, scope) }

    fun find(
        connection: Connection,
        scope: BotCheckoutScope,
    ): BotPreparedCheckout? =
        connection.prepareStatement(
            "SELECT payload FROM bot_prepared_checkouts WHERE chat_id = ? AND user_id = ? AND " +
                "table_session_id = ? AND visit_generation = ?",
        ).use {
            it.setLong(1, scope.chatId)
            it.setLong(2, scope.userId)
            it.setLong(3, scope.sessionId)
            it.setLong(4, scope.generation)
            it.executeQuery().use { rows ->
                if (rows.next()) Json.decodeFromString<BotPreparedCheckout>(rows.getString(1)) else null
            }
        }

    // Call inside the participation transaction, with the chat binding and session locked.
    fun prepare(
        connection: Connection,
        scope: BotCheckoutScope,
        checkout: BotPreparedCheckout,
    ): BotPreparedCheckout {
        find(connection, scope)?.let { return it }
        connection.prepareStatement(
            """
            INSERT INTO bot_prepared_checkouts
                (chat_id, user_id, table_session_id, visit_generation, checkout_id, payload)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use {
            it.setLong(1, scope.chatId)
            it.setLong(2, scope.userId)
            it.setLong(3, scope.sessionId)
            it.setLong(4, scope.generation)
            it.setString(5, checkout.id)
            it.setString(6, Json.encodeToString(BotPreparedCheckout.serializer(), checkout))
            it.executeUpdate()
        }
        return checkout
    }

    suspend fun remove(
        scope: BotCheckoutScope,
        checkout: BotPreparedCheckout,
    ) = readWrite { remove(it, scope, checkout.id) }

    fun remove(
        connection: Connection,
        scope: BotCheckoutScope,
        checkoutId: String,
    ): Int =
        connection.prepareStatement(
            "DELETE FROM bot_prepared_checkouts WHERE chat_id = ? AND user_id = ? AND " +
                "table_session_id = ? AND visit_generation = ? AND checkout_id = ?",
        ).use {
            it.setLong(1, scope.chatId)
            it.setLong(2, scope.userId)
            it.setLong(3, scope.sessionId)
            it.setLong(4, scope.generation)
            it.setString(5, checkoutId)
            it.executeUpdate()
        }

    internal data class CommittedCheckout(
        val scope: BotCheckoutScope,
        val checkoutId: String,
        val venueId: Long,
        val orderId: Long,
        val batchId: Long,
        val displayNumber: Int?,
    )

    internal suspend fun recoverCommitted(complete: (Connection, CommittedCheckout) -> Unit) =
        readWrite { connection ->
            connection.autoCommit = false
            try {
                val locking =
                    if (connection.metaData.databaseProductName == "PostgreSQL") {
                        " FOR UPDATE OF p SKIP LOCKED"
                    } else {
                        " FOR UPDATE"
                    }
                val checkouts =
                    connection.prepareStatement(
                        """
                            SELECT p.chat_id, p.user_id, p.table_session_id, p.visit_generation, p.checkout_id,
                                   g.venue_id, g.order_id, g.batch_id, o.display_number
                            FROM bot_prepared_checkouts p
                            JOIN table_sessions session ON session.id = p.table_session_id
                        JOIN guest_batch_idempotency g ON g.venue_id = session.venue_id AND ${committedMatch("p")}
                            JOIN order_batches b ON b.id = g.batch_id AND b.order_id = g.order_id
                            JOIN orders o ON o.id = g.order_id AND o.venue_id = g.venue_id
                            ORDER BY g.id
                            LIMIT 20
                        """.trimIndent() + locking,
                    ).use { statement ->
                        statement.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) {
                                    add(
                                        CommittedCheckout(
                                            BotCheckoutScope(
                                                rows.getLong(1),
                                                rows.getLong(2),
                                                rows.getLong(3),
                                                rows.getLong(4),
                                            ),
                                            rows.getString(5),
                                            rows.getLong(6),
                                            rows.getLong(7),
                                            rows.getLong(8),
                                            rows.getInt(9).takeUnless { rows.wasNull() },
                                        ),
                                    )
                                }
                            }
                        }
                    }
                checkouts.forEach { complete(connection, it) }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }

    suspend fun pruneRetired() =
        readWrite { connection ->
            connection.createStatement().use {
                it.executeUpdate(
                    """
                    DELETE FROM bot_prepared_checkouts
                    WHERE NOT EXISTS (SELECT 1 FROM guest_batch_idempotency g WHERE ${committedMatch(
                        "bot_prepared_checkouts",
                    )})
                      AND (
                        table_session_id IN (
                            SELECT id FROM table_sessions WHERE ended_at IS NOT NULL OR expires_at <= CURRENT_TIMESTAMP
                        ) OR EXISTS (
                            SELECT 1 FROM guest_visit_generations generation
                            WHERE generation.user_id = bot_prepared_checkouts.user_id
                              AND generation.table_session_id = bot_prepared_checkouts.table_session_id
                              AND generation.consumed_exit_event_id > bot_prepared_checkouts.visit_generation
                        )
                      )
                    """.trimIndent(),
                )
            }
        }

    private fun committedMatch(prepared: String): String =
        "g.table_session_id = $prepared.table_session_id AND g.user_id = $prepared.user_id " +
            "AND g.idempotency_key = CONCAT('bot-cart-checkout:', $prepared.checkout_id) AND g.batch_id IS NOT NULL"

    private suspend fun <T> readWrite(operation: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            try {
                dataSource.connection.use(operation)
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
}
