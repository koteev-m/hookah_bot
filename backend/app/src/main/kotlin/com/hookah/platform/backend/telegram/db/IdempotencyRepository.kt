package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import com.hookah.platform.backend.telegram.debugTelegramException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource
import org.slf4j.LoggerFactory

class IdempotencyRepository(private val dataSource: DataSource?) {
    private val logger = LoggerFactory.getLogger(IdempotencyRepository::class.java)

    suspend fun tryAcquire(updateId: Long, chatId: Long?, messageId: Long?): Boolean {
        val ds = dataSource ?: return true
        return withContext(Dispatchers.IO) {
            runCatching {
                ds.connection.use { connection ->
                    val sql = """
                        INSERT INTO telegram_processed_updates (update_id, chat_id, message_id)
                        VALUES (?, ?, ?)
                        ON CONFLICT DO NOTHING
                    """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, updateId)
                        if (chatId != null) statement.setLong(2, chatId)
                        else statement.setNull(2, java.sql.Types.BIGINT)
                        if (messageId != null) statement.setLong(3, messageId)
                        else statement.setNull(3, java.sql.Types.BIGINT)
                        val inserted = statement.executeUpdate()
                        inserted > 0
                    }
                }
            }.getOrElse { throwable ->
                logInsertFailure(throwable)
                false
            }
        }
    }

    private fun logInsertFailure(throwable: Throwable) {
        val safeMessage = sanitizeTelegramForLog(throwable.message ?: throwable::class.simpleName.orEmpty())
        logger.warn("Idempotency insert failed: {}", safeMessage)
        logger.debugTelegramException(throwable) { "Idempotency insert exception" }
    }
}
