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

enum class TelegramInboundUpdateStatus {
    PENDING,
    PROCESSING,
    RETRY,
    FAILED,
    PROCESSED,
}

data class TelegramInboundUpdate(
    val id: Long,
    val updateId: Long,
    val payloadJson: String,
    val attempts: Int,
    val receivedAt: Instant,
)

class TelegramInboundUpdateQueueRepository(private val dataSource: DataSource?) {
    private val logger = LoggerFactory.getLogger(TelegramInboundUpdateQueueRepository::class.java)

    suspend fun enqueue(
        updateId: Long,
        payloadJson: String,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        INSERT INTO telegram_inbound_updates (update_id, payload_json)
                        VALUES (?, ?)
                        ON CONFLICT (update_id) DO NOTHING
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, updateId)
                        statement.setString(2, payloadJson)
                        statement.executeUpdate() > 0
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
    ): List<TelegramInboundUpdate> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val selectSql =
                            """
                            SELECT id, update_id, payload_json, attempts, received_at
                            FROM telegram_inbound_updates
                            WHERE status IN (?, ?, ?)
                              AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                            ORDER BY received_at
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                            """.trimIndent()
                        val updates = mutableListOf<TelegramInboundUpdate>()
                        connection.prepareStatement(selectSql).use { statement ->
                            statement.setString(1, TelegramInboundUpdateStatus.PENDING.name)
                            statement.setString(2, TelegramInboundUpdateStatus.RETRY.name)
                            statement.setString(3, TelegramInboundUpdateStatus.PROCESSING.name)
                            statement.setTimestamp(4, Timestamp.from(now))
                            statement.setInt(5, limit)
                            statement.executeQuery().use { resultSet ->
                                while (resultSet.next()) {
                                    val id = resultSet.getLong("id")
                                    val updateId = resultSet.getLong("update_id")
                                    val payloadJson = resultSet.getString("payload_json")
                                    val attempts = resultSet.getInt("attempts") + 1
                                    val receivedAt = resultSet.getTimestamp("received_at").toInstant()
                                    updates.add(
                                        TelegramInboundUpdate(
                                            id = id,
                                            updateId = updateId,
                                            payloadJson = payloadJson,
                                            attempts = attempts,
                                            receivedAt = receivedAt,
                                        ),
                                    )
                                }
                            }
                        }

                        val updateSql =
                            """
                            UPDATE telegram_inbound_updates
                            SET status = ?, attempts = ?, last_error = NULL, next_attempt_at = ?
                            WHERE id = ?
                            """.trimIndent()
                        val lockUntil = now.plus(visibilityTimeout)
                        connection.prepareStatement(updateSql).use { statement ->
                            for (update in updates) {
                                statement.setString(1, TelegramInboundUpdateStatus.PROCESSING.name)
                                statement.setInt(2, update.attempts)
                                statement.setTimestamp(3, Timestamp.from(lockUntil))
                                statement.setLong(4, update.id)
                                statement.addBatch()
                            }
                            if (updates.isNotEmpty()) {
                                statement.executeBatch()
                            }
                        }
                        connection.commit()
                        updates
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

    suspend fun markProcessed(
        id: Long,
        processedAt: Instant,
    ) {
        updateStatus(
            id = id,
            status = TelegramInboundUpdateStatus.PROCESSED,
            processedAt = processedAt,
            lastError = null,
            nextAttemptAt = null,
        )
    }

    suspend fun markFailed(
        id: Long,
        status: TelegramInboundUpdateStatus,
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
                        FROM telegram_inbound_updates
                        WHERE status != ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, TelegramInboundUpdateStatus.PROCESSED.name)
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

    private suspend fun updateStatus(
        id: Long,
        status: TelegramInboundUpdateStatus,
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
                        UPDATE telegram_inbound_updates
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
        logger.warn("Telegram inbound queue {} failed: {}", action, safeMessage)
        logger.debugTelegramException(throwable) { "Telegram inbound queue $action exception" }
    }
}
