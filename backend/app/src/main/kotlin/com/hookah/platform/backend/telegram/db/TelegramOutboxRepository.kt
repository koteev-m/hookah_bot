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
)

class TelegramOutboxRepository(private val dataSource: DataSource?) {
    private val logger = LoggerFactory.getLogger(TelegramOutboxRepository::class.java)

    suspend fun enqueue(
        chatId: Long,
        method: String,
        payloadJson: String,
    ) {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        INSERT INTO telegram_outbox (chat_id, method, payload_json)
                        VALUES (?, ?, ?)
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, chatId)
                        statement.setString(2, method)
                        statement.setString(3, payloadJson)
                        statement.executeUpdate()
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
                            SELECT id, chat_id, method, payload_json, attempts
                            FROM telegram_outbox
                            WHERE status IN (?, ?)
                              AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                            ORDER BY created_at
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
                                    items.add(
                                        TelegramOutboxMessage(
                                            id = id,
                                            chatId = chatId,
                                            method = method,
                                            payloadJson = payloadJson,
                                            attempts = attempts,
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
