package com.hookah.platform.backend.analytics

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.sql.SQLException
import java.sql.Savepoint
import javax.sql.DataSource

data class AnalyticsEventRecord(
    val eventType: String,
    val payload: JsonObject,
    val venueId: Long? = null,
    val tableId: Long? = null,
    val tableSessionId: Long? = null,
    val orderId: Long? = null,
    val batchId: Long? = null,
    val tabId: Long? = null,
    val idempotencyKey: String,
)

class AnalyticsEventRepository(private val dataSource: DataSource?) {
    suspend fun append(event: AnalyticsEventRecord): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    append(connection, event)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    fun append(
        connection: Connection,
        event: AnalyticsEventRecord,
    ): Boolean {
        val sql =
            """
            INSERT INTO analytics_events (
                event_type,
                payload_json,
                venue_id,
                table_id,
                table_session_id,
                order_id,
                batch_id,
                tab_id,
                idempotency_key
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        if (connection.autoCommit) {
            return tryAppend(sql, connection, event)
        }

        val savepoint = connection.setSavepoint()
        return try {
            tryAppend(sql, connection, event)
        } catch (e: SQLException) {
            try {
                connection.rollback(savepoint)
            } catch (_: SQLException) {
                // no-op
            }
            if (e.isDuplicateKeyViolation()) {
                false
            } else {
                throw e
            }
        } finally {
            releaseSavepoint(connection, savepoint)
        }
    }

    private fun tryAppend(
        sql: String,
        connection: Connection,
        event: AnalyticsEventRecord,
    ): Boolean {
        return try {
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, event.eventType)
                statement.setString(2, event.payload.toString())
                statement.setObject(3, event.venueId)
                statement.setObject(4, event.tableId)
                statement.setObject(5, event.tableSessionId)
                statement.setObject(6, event.orderId)
                statement.setObject(7, event.batchId)
                statement.setObject(8, event.tabId)
                statement.setString(9, event.idempotencyKey)
                statement.executeUpdate() > 0
            }
        } catch (e: SQLException) {
            if (e.isDuplicateKeyViolation()) {
                false
            } else {
                throw e
            }
        }
    }

    private fun releaseSavepoint(
        connection: Connection,
        savepoint: Savepoint,
    ) {
        try {
            connection.releaseSavepoint(savepoint)
        } catch (_: SQLException) {
            // no-op
        }
    }

    private fun SQLException.isDuplicateKeyViolation(): Boolean {
        if (sqlState == "23505") {
            return true
        }
        return generateSequence(nextException) { it.nextException }.any { it.sqlState == "23505" }
    }
}

fun analyticsCorrelationPayload(
    venueId: Long? = null,
    tableId: Long? = null,
    tableSessionId: Long? = null,
    orderId: Long? = null,
    batchId: Long? = null,
    tabId: Long? = null,
    extra: Map<String, String?> = emptyMap(),
): JsonObject {
    return buildJsonObject {
        venueId?.let { put("venueId", JsonPrimitive(it)) }
        tableId?.let { put("tableId", JsonPrimitive(it)) }
        tableSessionId?.let { put("tableSessionId", JsonPrimitive(it)) }
        orderId?.let { put("orderId", JsonPrimitive(it)) }
        batchId?.let { put("batchId", JsonPrimitive(it)) }
        tabId?.let { put("tabId", JsonPrimitive(it)) }
        extra.forEach { (key, value) ->
            value?.let { put(key, JsonPrimitive(it)) }
        }
    }
}
