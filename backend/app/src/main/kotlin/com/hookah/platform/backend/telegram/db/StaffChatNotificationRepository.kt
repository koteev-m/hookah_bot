package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.telegram.debugTelegramException
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import javax.sql.DataSource

open class StaffChatNotificationRepository(private val dataSource: DataSource?) {
    private val logger = LoggerFactory.getLogger(StaffChatNotificationRepository::class.java)

    suspend fun wasBatchNotified(batchId: Long): Boolean {
        val ds = dataSource ?: return false
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT 1 FROM telegram_staff_chat_notifications WHERE batch_id = ? LIMIT 1"
                ).use { statement ->
                    statement.setLong(1, batchId)
                    statement.executeQuery().use { rs -> rs.next() }
                }
            }
        }
    }

    suspend fun markBatchNotified(batchId: Long, chatId: Long): Boolean {
        val ds = dataSource ?: return false
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                try {
                    val isH2 = connection.metaData.databaseProductName?.equals("H2", ignoreCase = true) == true
                    if (isH2) {
                        connection.prepareStatement(
                            """
                                MERGE INTO telegram_staff_chat_notifications (batch_id, chat_id, sent_at)
                                KEY (batch_id)
                                VALUES (?, ?, CURRENT_TIMESTAMP)
                            """.trimIndent()
                        ).use { statement ->
                            statement.setLong(1, batchId)
                            statement.setLong(2, chatId)
                            statement.executeUpdate()
                        }
                        true
                    } else {
                        connection.prepareStatement(
                            """
                                INSERT INTO telegram_staff_chat_notifications (batch_id, chat_id, sent_at)
                                VALUES (?, ?, now())
                                ON CONFLICT (batch_id) DO NOTHING
                            """.trimIndent()
                        ).use { statement ->
                            statement.setLong(1, batchId)
                            statement.setLong(2, chatId)
                            statement.executeUpdate() > 0
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(
                        "Failed to mark staff chat notification batchId={}: {}",
                        batchId,
                        sanitizeTelegramForLog(e.message)
                    )
                    logger.debugTelegramException(e) { "markBatchNotified exception batchId=$batchId" }
                    false
                }
            }
        }
    }
}
