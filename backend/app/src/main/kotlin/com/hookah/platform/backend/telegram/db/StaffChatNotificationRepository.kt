package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.telegram.debugTelegramException
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.sql.Statement
import javax.sql.DataSource

data class StaffChatOrderMessage(
    val orderId: Long,
    val venueId: Long,
    val chatId: Long,
    val messageId: Long?,
)

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

    suspend fun tryClaimAndEnqueue(
        notificationKey: Long,
        chatId: Long,
        method: String,
        payloadJson: String,
    ): StaffChatNotificationClaim {
        val ds = dataSource ?: return StaffChatNotificationClaim.ERROR
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        """
                        INSERT INTO telegram_staff_chat_notifications (batch_id, chat_id, sent_at)
                        VALUES (?, ?, CURRENT_TIMESTAMP)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, notificationKey)
                        statement.setLong(2, chatId)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement(
                        """
                        INSERT INTO telegram_outbox (chat_id, method, payload_json)
                        VALUES (?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, chatId)
                        statement.setString(2, method)
                        statement.setString(3, payloadJson)
                        statement.executeUpdate()
                    }
                    connection.commit()
                    StaffChatNotificationClaim.CLAIMED
                } catch (e: SQLException) {
                    connection.rollback()
                    if (e.sqlState == "23505") {
                        StaffChatNotificationClaim.ALREADY
                    } else {
                        logger.warn(
                            "Failed to claim and enqueue staff chat notification key={}: {}",
                            notificationKey,
                            sanitizeTelegramForLog(e.message),
                        )
                        logger.debugTelegramException(e) { "tryClaimAndEnqueue exception key=$notificationKey" }
                        StaffChatNotificationClaim.ERROR
                    }
                } catch (e: Exception) {
                    connection.rollback()
                    logger.warn(
                        "Failed to claim and enqueue staff chat notification key={}: {}",
                        notificationKey,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) { "tryClaimAndEnqueue exception key=$notificationKey" }
                    StaffChatNotificationClaim.ERROR
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    suspend fun tryClaimAndEnqueueOrderMessage(
        notificationKey: Long,
        orderId: Long,
        venueId: Long,
        chatId: Long,
        method: String,
        payloadJson: String,
    ): StaffChatNotificationClaim {
        val ds = dataSource ?: return StaffChatNotificationClaim.ERROR
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        """
                        INSERT INTO telegram_staff_chat_notifications (batch_id, chat_id, sent_at)
                        VALUES (?, ?, CURRENT_TIMESTAMP)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, notificationKey)
                        statement.setLong(2, chatId)
                        statement.executeUpdate()
                    }
                    upsertOrderMessageInTransaction(
                        connection = connection,
                        orderId = orderId,
                        venueId = venueId,
                        chatId = chatId,
                    )
                    enqueueLinkedOutboxInTransaction(
                        connection = connection,
                        orderId = orderId,
                        chatId = chatId,
                        method = method,
                        payloadJson = payloadJson,
                    )
                    connection.commit()
                    StaffChatNotificationClaim.CLAIMED
                } catch (e: SQLException) {
                    connection.rollback()
                    if (e.sqlState == "23505") {
                        StaffChatNotificationClaim.ALREADY
                    } else {
                        logger.warn(
                            "Failed to claim and enqueue staff chat order message key={}: {}",
                            notificationKey,
                            sanitizeTelegramForLog(e.message),
                        )
                        logger.debugTelegramException(e) {
                            "tryClaimAndEnqueueOrderMessage exception key=$notificationKey"
                        }
                        StaffChatNotificationClaim.ERROR
                    }
                } catch (e: Exception) {
                    connection.rollback()
                    logger.warn(
                        "Failed to claim and enqueue staff chat order message key={}: {}",
                        notificationKey,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) {
                        "tryClaimAndEnqueueOrderMessage exception key=$notificationKey"
                    }
                    StaffChatNotificationClaim.ERROR
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    suspend fun enqueue(
        chatId: Long,
        method: String,
        payloadJson: String,
    ): Boolean {
        val ds = dataSource ?: return false
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                try {
                    connection.prepareStatement(
                        """
                        INSERT INTO telegram_outbox (chat_id, method, payload_json)
                        VALUES (?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, chatId)
                        statement.setString(2, method)
                        statement.setString(3, payloadJson)
                        statement.executeUpdate() > 0
                    }
                } catch (e: Exception) {
                    logger.warn(
                        "Failed to enqueue staff chat message chatId={}: {}",
                        chatId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) { "enqueue staff chat message exception chatId=$chatId" }
                    false
                }
            }
        }
    }

    suspend fun findOrderMessage(orderId: Long): StaffChatOrderMessage? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT order_id, venue_id, chat_id, message_id
                    FROM telegram_staff_chat_order_messages
                    WHERE order_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, orderId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) {
                            return@use null
                        }
                        StaffChatOrderMessage(
                            orderId = rs.getLong("order_id"),
                            venueId = rs.getLong("venue_id"),
                            chatId = rs.getLong("chat_id"),
                            messageId = rs.getLong("message_id").takeIf { !rs.wasNull() },
                        )
                    }
                }
            }
        }
    }

    suspend fun upsertOrderMessage(
        orderId: Long,
        venueId: Long,
        chatId: Long,
        messageId: Long?,
    ): Boolean {
        val ds = dataSource ?: return false
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                try {
                    connection.autoCommit = false
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE telegram_staff_chat_order_messages
                            SET venue_id = ?, chat_id = ?, message_id = COALESCE(?, message_id), updated_at = CURRENT_TIMESTAMP
                            WHERE order_id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, venueId)
                            statement.setLong(2, chatId)
                            if (messageId != null) {
                                statement.setLong(3, messageId)
                            } else {
                                statement.setNull(3, java.sql.Types.BIGINT)
                            }
                            statement.setLong(4, orderId)
                            statement.executeUpdate()
                        }
                    if (updated == 0) {
                        connection.prepareStatement(
                            """
                            INSERT INTO telegram_staff_chat_order_messages (order_id, venue_id, chat_id, message_id)
                            VALUES (?, ?, ?, ?)
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, orderId)
                            statement.setLong(2, venueId)
                            statement.setLong(3, chatId)
                            if (messageId != null) {
                                statement.setLong(4, messageId)
                            } else {
                                statement.setNull(4, java.sql.Types.BIGINT)
                            }
                            statement.executeUpdate()
                        }
                    }
                    connection.commit()
                    true
                } catch (e: Exception) {
                    runCatching { connection.rollback() }
                    logger.warn(
                        "Failed to upsert staff chat order message orderId={}: {}",
                        orderId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) { "upsert staff chat order message exception orderId=$orderId" }
                    false
                } finally {
                    runCatching { connection.autoCommit = true }
                }
            }
        }
    }

    suspend fun enqueueOrderMessage(
        orderId: Long,
        venueId: Long,
        chatId: Long,
        method: String,
        payloadJson: String,
    ): Boolean {
        val ds = dataSource ?: return false
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                try {
                    connection.autoCommit = false
                    upsertOrderMessageInTransaction(
                        connection = connection,
                        orderId = orderId,
                        venueId = venueId,
                        chatId = chatId,
                    )
                    enqueueLinkedOutboxInTransaction(
                        connection = connection,
                        orderId = orderId,
                        chatId = chatId,
                        method = method,
                        payloadJson = payloadJson,
                    )
                    connection.commit()
                    true
                } catch (e: Exception) {
                    runCatching { connection.rollback() }
                    logger.warn(
                        "Failed to enqueue staff chat order message orderId={}: {}",
                        orderId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) { "enqueue staff chat order message exception orderId=$orderId" }
                    false
                } finally {
                    runCatching { connection.autoCommit = true }
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

    private fun upsertOrderMessageInTransaction(
        connection: java.sql.Connection,
        orderId: Long,
        venueId: Long,
        chatId: Long,
    ) {
        val updated =
            connection.prepareStatement(
                """
                UPDATE telegram_staff_chat_order_messages
                SET venue_id = ?, chat_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE order_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, chatId)
                statement.setLong(3, orderId)
                statement.executeUpdate()
            }
        if (updated == 0) {
            connection.prepareStatement(
                """
                INSERT INTO telegram_staff_chat_order_messages (order_id, venue_id, chat_id, message_id)
                VALUES (?, ?, ?, NULL)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, orderId)
                statement.setLong(2, venueId)
                statement.setLong(3, chatId)
                statement.executeUpdate()
            }
        }
    }

    private fun enqueueLinkedOutboxInTransaction(
        connection: java.sql.Connection,
        orderId: Long,
        chatId: Long,
        method: String,
        payloadJson: String,
    ) {
        val outboxId =
            connection.prepareStatement(
                """
                INSERT INTO telegram_outbox (chat_id, method, payload_json)
                VALUES (?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, chatId)
                statement.setString(2, method)
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
    }
}

enum class StaffChatNotificationClaim {
    CLAIMED,
    ALREADY,
    ERROR,
}
