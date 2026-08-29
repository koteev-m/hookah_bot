package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.telegram.TelegramTrafficPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.sql.Connection
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

class TelegramOutboxRepository(
    private val dataSource: DataSource?,
    private val trafficPolicy: TelegramTrafficPolicy,
) {
    private val logger = LoggerFactory.getLogger(TelegramOutboxRepository::class.java)

    suspend fun enqueue(
        chatId: Long,
        method: String,
        payloadJson: String,
        dedupeKey: String? = null,
    ): Boolean {
        if (!trafficPolicy.allowsOutboundChat(chatId)) return false
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        if (!isProductGenericEnqueueRecipientAuthorized(connection, chatId)) {
                            connection.rollback()
                            false
                        } else {
                            enqueueLegacyOnConnection(connection, chatId, method, payloadJson, dedupeKey)
                            connection.commit()
                            true
                        }
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
                logFailure("enqueue", e)
                throw DatabaseUnavailableException()
            } catch (e: Throwable) {
                logFailure("enqueue", e)
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun enqueueForVenue(
        venueId: Long,
        chatId: Long,
        method: String,
        payloadJson: String,
        dedupeKey: String? = null,
    ): Boolean {
        if (!trafficPolicy.allowsOutboundChat(chatId)) return false
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        if (!isProductVenueStaffChatAuthorized(connection, venueId, chatId)) {
                            connection.rollback()
                            false
                        } else {
                            enqueueLegacyOnConnection(connection, chatId, method, payloadJson, dedupeKey)
                            connection.commit()
                            true
                        }
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
                logFailure("enqueue for venue", e)
                throw DatabaseUnavailableException()
            } catch (e: Throwable) {
                logFailure("enqueue for venue", e)
                throw DatabaseUnavailableException()
            }
        }
    }

    fun enqueueStrictBookingOnConnection(
        connection: Connection,
        chatId: Long,
        method: String,
        payloadJson: String,
        dedupeKey: String? = null,
    ): Boolean {
        if (!trafficPolicy.allowsOutboundChat(chatId)) return false
        if (!isProductGenericEnqueueRecipientAuthorized(connection, chatId)) return false
        val normalizedDedupeKey = normalizeDedupeKey(dedupeKey)
        if (normalizedDedupeKey != null) {
            findOutboxEnvelope(connection, normalizedDedupeKey)?.let { existing ->
                if (existing.matchesStrict(chatId, method, payloadJson)) return true
                throw outboxDedupeConflict()
            }
        }
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
        val savepoint =
            if (normalizedDedupeKey != null && !connection.autoCommit) {
                connection.setSavepoint()
            } else {
                null
            }
        try {
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, chatId)
                statement.setString(2, method)
                statement.setString(3, payloadJson)
                if (normalizedDedupeKey != null) statement.setString(4, normalizedDedupeKey)
                statement.executeUpdate()
            }
        } catch (e: SQLException) {
            if (savepoint != null) {
                try {
                    connection.rollback(savepoint)
                } catch (rollbackFailure: SQLException) {
                    e.addSuppressed(rollbackFailure)
                    throw e
                }
            }
            if (normalizedDedupeKey != null && isUniqueViolation(e)) {
                findOutboxEnvelope(connection, normalizedDedupeKey)?.let { existing ->
                    if (existing.matchesStrict(chatId, method, payloadJson)) return true
                    throw outboxDedupeConflict()
                }
            }
            throw e
        } finally {
            if (savepoint != null) {
                runCatching { connection.releaseSavepoint(savepoint) }
            }
        }
        return true
    }

    private fun enqueueLegacyOnConnection(
        connection: Connection,
        chatId: Long,
        method: String,
        payloadJson: String,
        dedupeKey: String?,
    ) {
        val normalizedDedupeKey = normalizeDedupeKey(dedupeKey)
        if (normalizedDedupeKey != null && findOutboxEnvelope(connection, normalizedDedupeKey) != null) return
        val sql =
            if (normalizedDedupeKey == null) {
                "INSERT INTO telegram_outbox (chat_id, method, payload_json) VALUES (?, ?, ?)"
            } else {
                "INSERT INTO telegram_outbox (chat_id, method, payload_json, dedupe_key) VALUES (?, ?, ?, ?)"
            }
        val savepoint =
            if (normalizedDedupeKey != null && !connection.autoCommit) {
                connection.setSavepoint()
            } else {
                null
            }
        try {
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, chatId)
                statement.setString(2, method)
                statement.setString(3, payloadJson)
                if (normalizedDedupeKey != null) statement.setString(4, normalizedDedupeKey)
                statement.executeUpdate()
            }
        } catch (e: SQLException) {
            if (savepoint != null) {
                try {
                    connection.rollback(savepoint)
                } catch (rollbackFailure: SQLException) {
                    e.addSuppressed(rollbackFailure)
                    throw e
                }
            }
            if (normalizedDedupeKey != null && isUniqueViolation(e)) return
            throw e
        } finally {
            if (savepoint != null) {
                runCatching { connection.releaseSavepoint(savepoint) }
            }
        }
    }

    private fun findOutboxEnvelope(
        connection: Connection,
        dedupeKey: String,
    ): OutboxEnvelope? =
        connection.prepareStatement(
            """
            SELECT chat_id, method, payload_json
            FROM telegram_outbox
            WHERE dedupe_key = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, dedupeKey)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    null
                } else {
                    OutboxEnvelope(
                        chatId = resultSet.getLong("chat_id"),
                        method = resultSet.getString("method"),
                        payloadJson = resultSet.getString("payload_json"),
                    )
                }
            }
        }

    private fun normalizeDedupeKey(dedupeKey: String?): String? = dedupeKey?.trim()?.takeIf { it.isNotEmpty() }

    private fun outboxDedupeConflict(): SQLException =
        SQLException("Telegram outbox dedupe key belongs to a different envelope", "23505")

    private fun isUniqueViolation(exception: SQLException): Boolean {
        var current: SQLException? = exception
        while (current != null) {
            if (current.sqlState == "23505") return true
            current = current.nextException
        }
        return false
    }

    private data class OutboxEnvelope(
        val chatId: Long,
        val method: String,
        val payloadJson: String,
    ) {
        fun matchesStrict(
            expectedChatId: Long,
            expectedMethod: String,
            expectedPayloadJson: String,
        ): Boolean =
            chatId == expectedChatId &&
                method == expectedMethod &&
                canonicalJson(payloadJson) == canonicalJson(expectedPayloadJson)
    }

    companion object {
        private val strictJson = Json { ignoreUnknownKeys = false }

        private fun canonicalJson(payloadJson: String): String =
            canonicalize(strictJson.parseToJsonElement(payloadJson)).toString()

        private fun canonicalize(element: JsonElement): JsonElement =
            when (element) {
                is JsonObject ->
                    JsonObject(
                        element.entries
                            .sortedBy { it.key }
                            .associate { (key, value) -> key to canonicalize(value) },
                    )
                is JsonArray -> JsonArray(element.map(::canonicalize))
                else -> element
            }
    }

    suspend fun claimBatch(
        limit: Int,
        now: Instant,
        visibilityTimeout: Duration,
    ): List<TelegramOutboxMessage> {
        val claimScope = trafficPolicy.outboundClaimScope
        val eligibleChatIds = claimScope.eligibleChatIds.sorted()
        if (!claimScope.unrestricted && !claimScope.productAuthoritative && eligibleChatIds.isEmpty()) {
            return emptyList()
        }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val chatEligibilitySql =
                            when {
                                claimScope.unrestricted -> ""
                                claimScope.productAuthoritative ->
                                    """
                                    AND (
                                        (o.chat_id > 0 AND EXISTS (
                                            SELECT 1
                                            FROM users recipient_user
                                            WHERE recipient_user.telegram_user_id = o.chat_id
                                        ))
                                        OR
                                        (o.chat_id < 0 AND EXISTS (
                                            SELECT 1
                                            FROM venues recipient_venue
                                            WHERE recipient_venue.staff_chat_id = o.chat_id
                                        ))
                                    )
                                    AND (
                                        NOT EXISTS (
                                            SELECT 1
                                            FROM telegram_staff_chat_order_outbox_links live_guard
                                            WHERE live_guard.outbox_id = o.id
                                        )
                                        OR EXISTS (
                                            SELECT 1
                                            FROM telegram_staff_chat_order_outbox_links live_guard
                                            JOIN telegram_staff_chat_order_messages live_message
                                              ON live_message.order_id = live_guard.order_id
                                            JOIN venues live_venue
                                              ON live_venue.id = live_message.venue_id
                                            WHERE live_guard.outbox_id = o.id
                                              AND live_message.chat_id = o.chat_id
                                              AND live_venue.staff_chat_id = o.chat_id
                                        )
                                    )
                                    """.trimIndent()
                                else -> "AND o.chat_id IN (${eligibleChatIds.joinToString(",") { "?" }})"
                            }
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
                              AND o.chat_id <> 0
                              AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= ?)
                              $chatEligibilitySql
                            ORDER BY o.created_at, o.id
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                            """.trimIndent()
                        val items = mutableListOf<TelegramOutboxMessage>()
                        connection.prepareStatement(selectSql).use { statement ->
                            statement.setString(1, TelegramOutboxStatus.NEW.name)
                            statement.setString(2, TelegramOutboxStatus.SENDING.name)
                            statement.setTimestamp(3, Timestamp.from(now))
                            var parameterIndex = 4
                            eligibleChatIds
                                .takeIf { !claimScope.unrestricted && !claimScope.productAuthoritative }
                                ?.forEach { chatId ->
                                    statement.setLong(parameterIndex++, chatId)
                                }
                            statement.setInt(parameterIndex, limit)
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

    suspend fun isRecipientAuthorized(
        chatId: Long,
        staffLiveOrderId: Long? = null,
    ): Boolean {
        if (!trafficPolicy.allowsOutboundChat(chatId)) return false
        if (!trafficPolicy.productMode) return true
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    if (staffLiveOrderId != null) {
                        isProductOrderStaffChatAuthorized(connection, staffLiveOrderId, chatId)
                    } else {
                        isProductRecipientAuthorized(connection, chatId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                logFailure("authorize recipient", e)
                throw DatabaseUnavailableException()
            } catch (e: Throwable) {
                logFailure("authorize recipient", e)
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
        if (!trafficPolicy.allowsOutboundChat(chatId)) return
        val ds = dataSource ?: throw DatabaseUnavailableException()
        withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    if (!isProductOrderStaffChatAuthorized(connection, orderId, chatId)) return@use
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
        if (!trafficPolicy.allowsOutboundChat(chatId)) return
        val ds = dataSource ?: throw DatabaseUnavailableException()
        withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        if (!isProductOrderStaffChatAuthorized(connection, orderId, chatId)) {
                            connection.rollback()
                            return@use
                        }
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

    private fun isProductRecipientAuthorized(
        connection: Connection,
        chatId: Long,
    ): Boolean {
        if (!trafficPolicy.productMode) return true
        val sql =
            if (chatId > 0) {
                "SELECT 1 FROM users WHERE telegram_user_id = ? LIMIT 1 FOR SHARE"
            } else {
                "SELECT 1 FROM venues WHERE staff_chat_id = ? LIMIT 1 FOR SHARE"
            }
        return connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, chatId)
            statement.executeQuery().use { resultSet -> resultSet.next() }
        }
    }

    private fun isProductGenericEnqueueRecipientAuthorized(
        connection: Connection,
        chatId: Long,
    ): Boolean {
        if (!trafficPolicy.productMode) return true
        if (chatId <= 0) return false
        return connection.prepareStatement(
            "SELECT 1 FROM users WHERE telegram_user_id = ? LIMIT 1 FOR SHARE",
        ).use { statement ->
            statement.setLong(1, chatId)
            statement.executeQuery().use { resultSet -> resultSet.next() }
        }
    }

    private fun isProductVenueStaffChatAuthorized(
        connection: Connection,
        venueId: Long,
        chatId: Long,
    ): Boolean {
        if (!trafficPolicy.productMode) return true
        return connection.prepareStatement(
            """
            SELECT 1
            FROM venues
            WHERE id = ?
              AND staff_chat_id = ?
            LIMIT 1
            FOR SHARE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, chatId)
            statement.executeQuery().use { resultSet -> resultSet.next() }
        }
    }

    private fun isProductOrderStaffChatAuthorized(
        connection: Connection,
        orderId: Long,
        chatId: Long,
    ): Boolean {
        if (!trafficPolicy.productMode) return true
        return connection.prepareStatement(
            """
            SELECT 1
            FROM telegram_staff_chat_order_messages live_message
            JOIN venues live_venue ON live_venue.id = live_message.venue_id
            WHERE live_message.order_id = ?
              AND live_message.chat_id = ?
              AND live_venue.staff_chat_id = ?
            LIMIT 1
            FOR SHARE OF live_venue
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.setLong(2, chatId)
            statement.setLong(3, chatId)
            statement.executeQuery().use { resultSet -> resultSet.next() }
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
        logger.warn(
            "Telegram outbox operation failed operation={} error_type={}",
            action,
            throwable::class.simpleName ?: "unknown",
        )
    }
}
