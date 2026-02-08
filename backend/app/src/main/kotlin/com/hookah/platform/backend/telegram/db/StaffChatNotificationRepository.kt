package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.telegram.debugTelegramException
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.SQLException
import javax.sql.DataSource

open class StaffChatNotificationRepository(private val dataSource: DataSource?) {
    private val logger = LoggerFactory.getLogger(StaffChatNotificationRepository::class.java)

    suspend fun tryClaim(
        batchId: Long,
        chatId: Long,
    ): StaffChatNotificationClaim {
        val ds = dataSource ?: return StaffChatNotificationClaim.ERROR
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                try {
                    connection.prepareStatement(
                        """
                        INSERT INTO telegram_staff_chat_notifications (batch_id, chat_id, sent_at)
                        VALUES (?, ?, CURRENT_TIMESTAMP)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, batchId)
                        statement.setLong(2, chatId)
                        statement.executeUpdate()
                    }
                    StaffChatNotificationClaim.CLAIMED
                } catch (e: SQLException) {
                    if (e.sqlState == "23505") {
                        StaffChatNotificationClaim.ALREADY
                    } else {
                        logger.warn(
                            "Failed to claim staff chat notification batchId={}: {}",
                            batchId,
                            sanitizeTelegramForLog(e.message),
                        )
                        logger.debugTelegramException(e) { "tryClaim exception batchId=$batchId" }
                        StaffChatNotificationClaim.ERROR
                    }
                } catch (e: Exception) {
                    logger.warn(
                        "Failed to claim staff chat notification batchId={}: {}",
                        batchId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) { "tryClaim exception batchId=$batchId" }
                    StaffChatNotificationClaim.ERROR
                }
            }
        }
    }

    suspend fun releaseClaim(batchId: Long): Boolean {
        val ds = dataSource ?: return false
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                try {
                    connection.prepareStatement(
                        "DELETE FROM telegram_staff_chat_notifications WHERE batch_id = ?",
                    ).use { statement ->
                        statement.setLong(1, batchId)
                        statement.executeUpdate() > 0
                    }
                } catch (e: Exception) {
                    logger.warn(
                        "Failed to release staff chat notification batchId={}: {}",
                        batchId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) { "releaseClaim exception batchId=$batchId" }
                    false
                }
            }
        }
    }
}

enum class StaffChatNotificationClaim {
    CLAIMED,
    ALREADY,
    ERROR,
}
