package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.SQLException
import javax.sql.DataSource

class IdempotencyRepository(private val dataSource: DataSource?) {
    private val logger = LoggerFactory.getLogger(IdempotencyRepository::class.java)

    suspend fun tryAcquire(
        updateId: Long,
        chatId: Long?,
        messageId: Long?,
    ): Boolean {
        val ds = dataSource ?: return true
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        INSERT INTO telegram_processed_updates (update_id, chat_id, message_id)
                        VALUES (?, ?, ?)
                        ON CONFLICT DO NOTHING
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, updateId)
                        if (chatId != null) {
                            statement.setLong(2, chatId)
                        } else {
                            statement.setNull(2, java.sql.Types.BIGINT)
                        }
                        if (messageId != null) {
                            statement.setLong(3, messageId)
                        } else {
                            statement.setNull(3, java.sql.Types.BIGINT)
                        }
                        val inserted = statement.executeUpdate()
                        inserted > 0
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                if (isUniqueViolation(e)) {
                    false
                } else {
                    logInsertFailure(e)
                    throw DatabaseUnavailableException()
                }
            } catch (e: Throwable) {
                logInsertFailure(e)
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun logInsertFailure(throwable: Throwable) {
        logger.warn(
            "Telegram idempotency operation failed operation=insert error_type={}",
            throwable::class.simpleName ?: "unknown",
        )
    }

    private fun isUniqueViolation(throwable: SQLException): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            val sqlException = current as? SQLException
            if (sqlException?.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                return true
            }
            current =
                when (current) {
                    is SQLException -> current.nextException ?: current.cause
                    else -> current.cause
                }
        }
        return false
    }

    private companion object {
        private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
    }
}
