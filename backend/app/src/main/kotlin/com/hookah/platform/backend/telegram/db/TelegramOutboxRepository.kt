package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.telegram.debugTelegramException
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

enum class TelegramOutboxStatus {
    NEW,
    SENDING,
    SENT,
    FAILED,
}

data class TelegramOutboxMessage(
    val id: Long,
    val chatId: Long,
    val method: String,
    val payloadJson: String,
    val attempts: Int,
    val staffLiveOrderId: Long? = null,
)

class TelegramOutboxRepository(private val dataSource: DataSource?) {
    private val logger = LoggerFactory.getLogger(TelegramOutboxRepository::class.java)

    suspend fun enqueue(
        chatId: Long,
        method: String,
        payloadJson: String,
        dedupeKey: String? = null,
    ) {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val normalizedDedupeKey = dedupeKey?.trim()?.takeIf { it.isNotEmpty() }
                    if (normalizedDedupeKey != null && outboxDedupeExists(connection, normalizedDedupeKey)) {
                        return@use
                    }
                    try {
                        val sql =
                            if (normalizedDedupeKey == null) {
                                """
                                INSERT INTO telegram_outbox (chat_id, method, payload_json)
                                VALUES (?, ?, ?)
                                """.trimIndent()
                            } else {
                                """
                                INSERT INTO telegram_outbox (chat_id, method, payload_json, dedupe_key)
                                VALUES (?, ?, ?, ?)
                                """.trimIndent()
                            }
                        connection.prepareStatement(sql).use { statement ->
                            statement.setLong(1, chatId)
                            statement.setString(2, method)
                            statement.setString(3, payloadJson)
                            if (normalizedDedupeKey != null) {
                                statement.setString(4, normalizedDedupeKey)
                            }
                            statement.executeUpdate()
                        }
                    } catch (e: SQLException) {
                        if (normalizedDedupeKey != null && outboxDedupeExists(connection, normalizedDedupeKey)) {
                            return@use
                        }
                        throw e
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                logFailure("enqueue", e)
                throw DatabaseUnavailableException()
            } catch (e: Throwable) {
                logFailure("enqueue", e)
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun outboxDedupeExists(
        connection: java.sql.Connection,
        dedupeKey: String,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT 1
            FROM telegram_outbox
            WHERE dedupe_key = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, dedupeKey)
            statement.executeQuery().use { resultSet -> resultSet.next() }
        }

    suspend fun claimBatch(
        limit: Int,
        now: Instant,
        visibilityTimeout: Duration,
    ): List<TelegramOutboxMessage> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val selectSql =
                            """
                            SELECT o.id,
                                   o.chat_id,
                                   o.method,
                                   o.payload_json,
                                   o.attempts,
                                   (
                                       SELECT live.order_id
                                       FROM telegram_staff_chat_order_outbox_links live
                                       WHERE live.outbox_id = o.id
                                   ) AS staff_live_order_id
                            FROM telegram_outbox o
                            WHERE o.status IN (?, ?)
                              AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= ?)
                            ORDER BY o.created_at, o.id
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                            """.trimIndent()
                        val items = mutableListOf<TelegramOutboxMessage>()
                        connection.prepareStatement(selectSql).use { statement ->
                            statement.setString(1, TelegramOutboxStatus.NEW.name)
                            statement.setString(2, TelegramOutboxStatus.SENDING.name)
                            statement.setTimestamp(3, Timestamp.from(now))
                            statement.setInt(4, limit)
                            statement.executeQuery().use { resultSet ->
                                while (resultSet.next()) {
                                    val id = resultSet.getLong("id")
                                    val chatId = resultSet.getLong("chat_id")
                                    val method = resultSet.getString("method")
                                    val payloadJson = resultSet.getString("payload_json")
                                    val attempts = resultSet.getInt("attempts") + 1
                                    val staffLiveOrderId =
                                        resultSet.getLong("staff_live_order_id").takeIf { !resultSet.wasNull() }
                                    items.add(
                                        TelegramOutboxMessage(
                                            id = id,
                                            chatId = chatId,
                                            method = method,
                                            payloadJson = payloadJson,
                                            attempts = attempts,
                                            staffLiveOrderId = staffLiveOrderId,
                                        ),
                                    )
                                }
                            }
                        }

                        val updateSql =
                            """
                            UPDATE telegram_outbox
                            SET status = ?, attempts = ?, last_error = NULL, next_attempt_at = ?
                            WHERE id = ?
                            """.trimIndent()
                        val lockUntil = now.plus(visibilityTimeout)
                        connection.prepareStatement(updateSql).use { statement ->
                            for (item in items) {
                                statement.setString(1, TelegramOutboxStatus.SENDING.name)
                                statement.setInt(2, item.attempts)
                                statement.setTimestamp(3, Timestamp.from(lockUntil))
                                statement.setLong(4, item.id)
                                statement.addBatch()
                            }
                            if (items.isNotEmpty()) {
                                statement.executeBatch()
                            }
                        }
                        connection.commit()
                        items
                    } catch (e: Exception) {
                        connection.rollback()
                        throw e
                    } finally {
                        connection.autoCommit = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                logFailure("claimBatch", e)
                throw DatabaseUnavailableException()
            } catch (e: Throwable) {
                logFailure("claimBatch", e)
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun markSent(
        id: Long,
        processedAt: Instant,
    ) {
        updateStatus(
            id = id,
            status = TelegramOutboxStatus.SENT,
            processedAt = processedAt,
            lastError = null,
            nextAttemptAt = null,
        )
    }

    suspend fun markFailed(
        id: Long,
        status: TelegramOutboxStatus,
        lastError: String?,
        processedAt: Instant?,
        nextAttemptAt: Instant?,
    ) {
        updateStatus(
            id = id,
            status = status,
            processedAt = processedAt,
            lastError = lastError,
            nextAttemptAt = nextAttemptAt,
        )
    }

    suspend fun queueDepth(): Long {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT COUNT(*) AS depth
                        FROM telegram_outbox
                        WHERE status != ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, TelegramOutboxStatus.SENT.name)
                        statement.executeQuery().use { resultSet ->
                            if (resultSet.next()) {
                                resultSet.getLong("depth")
                            } else {
                                0L
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                logFailure("queueDepth", e)
                throw DatabaseUnavailableException()
            } catch (e: Throwable) {
                logFailure("queueDepth", e)
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateStaffChatOrderMessageId(
        orderId: Long,
        chatId: Long,
        messageId: Long,
    ) {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        UPDATE telegram_staff_chat_order_messages
                        SET chat_id = ?, message_id = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE order_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, chatId)
                        statement.setLong(2, messageId)
                        statement.setLong(3, orderId)
                        statement.executeUpdate()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                logFailure("updateStaffChatOrderMessageId", e)
                throw DatabaseUnavailableException()
            } catch (e: Throwable) {
                logFailure("updateStaffChatOrderMessageId", e)
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun enqueueStaffChatOrderFallback(
        orderId: Long,
        chatId: Long,
        payloadJson: String,
    ) {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val outboxId =
                            connection.prepareStatement(
                                """
                                INSERT INTO telegram_outbox (chat_id, method, payload_json)
                                VALUES (?, ?, ?)
                                """.trimIndent(),
                                java.sql.Statement.RETURN_GENERATED_KEYS,
                            ).use { statement ->
                                statement.setLong(1, chatId)
                                statement.setString(2, "sendMessage")
                                statement.setString(3, payloadJson)
                                statement.executeUpdate()
                                statement.generatedKeys.use { keys ->
                                    if (keys.next()) {
                                        keys.getLong(1)
                                    } else {
                                        error("telegram_outbox id was not generated")
                                    }
                                }
                            }
                        connection.prepareStatement(
                            """
                            INSERT INTO telegram_staff_chat_order_outbox_links (outbox_id, order_id)
                            VALUES (?, ?)
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, outboxId)
                            statement.setLong(2, orderId)
                            statement.executeUpdate()
                        }
                        connection.commit()
                    } catch (e: Exception) {
                        connection.rollback()
                        throw e
                    } finally {
                        connection.autoCommit = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                logFailure("enqueueStaffChatOrderFallback", e)
                throw DatabaseUnavailableException()
            } catch (e: Throwable) {
                logFailure("enqueueStaffChatOrderFallback", e)
                throw DatabaseUnavailableException()
            }
        }
    }

    private suspend fun updateStatus(
        id: Long,
        status: TelegramOutboxStatus,
        processedAt: Instant?,
        lastError: String?,
        nextAttemptAt: Instant?,
    ) {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        UPDATE telegram_outbox
                        SET status = ?, processed_at = ?, last_error = ?, next_attempt_at = ?
                        WHERE id = ?
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setString(1, status.name)
                        if (processedAt != null) {
                            statement.setTimestamp(2, Timestamp.from(processedAt))
                        } else {
                            statement.setNull(2, java.sql.Types.TIMESTAMP)
                        }
                        if (lastError != null) {
                            statement.setString(3, lastError)
                        } else {
                            statement.setNull(3, java.sql.Types.VARCHAR)
                        }
                        if (nextAttemptAt != null) {
                            statement.setTimestamp(4, Timestamp.from(nextAttemptAt))
                        } else {
                            statement.setNull(4, java.sql.Types.TIMESTAMP)
                        }
                        statement.setLong(5, id)
                        statement.executeUpdate()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                logFailure("updateStatus", e)
                throw DatabaseUnavailableException()
            } catch (e: Throwable) {
                logFailure("updateStatus", e)
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun logFailure(
        action: String,
        throwable: Throwable,
    ) {
        val safeMessage = sanitizeTelegramForLog(throwable.message ?: throwable::class.simpleName.orEmpty())
        logger.warn("Telegram outbox {} failed: {}", action, safeMessage)
        logger.debugTelegramException(throwable) { "Telegram outbox $action exception" }
    }
}
