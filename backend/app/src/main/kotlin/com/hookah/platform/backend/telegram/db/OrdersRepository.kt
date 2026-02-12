package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.analytics.AnalyticsEventRecord
import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.analytics.analyticsCorrelationPayload
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.telegram.ActiveOrderSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import javax.sql.DataSource

data class OrderBatchItemInput(
    val itemId: Long,
    val qty: Int,
)

data class OrderBatchItemDetails(
    val itemId: Long,
    val qty: Int,
)

data class OrderBatchDetails(
    val batchId: Long,
    val comment: String?,
    val items: List<OrderBatchItemDetails>,
)

data class ActiveOrderDetails(
    val orderId: Long,
    val status: String,
    val batches: List<OrderBatchDetails>,
)

data class CreatedOrderBatch(
    val orderId: Long,
    val batchId: Long,
    val idempotencyReplay: Boolean,
)

class OrdersRepository(
    private val dataSource: DataSource?,
    private val analyticsEventRepository: AnalyticsEventRepository? = null,
) {
    suspend fun findActiveOrderId(tableId: Long): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT id FROM orders WHERE table_id = ? AND status = 'ACTIVE'",
                ).use { statement ->
                    statement.setLong(1, tableId)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                }
            }
        }
    }

    suspend fun findActiveOrderSummary(tableId: Long): ActiveOrderSummary? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT id, status FROM orders WHERE table_id = ? AND status = 'ACTIVE'",
                ).use { statement ->
                    statement.setLong(1, tableId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            ActiveOrderSummary(rs.getLong(1), rs.getString(2))
                        } else {
                            null
                        }
                    }
                }
            }
        }
    }

    suspend fun findActiveOrderDetails(tableId: Long): ActiveOrderDetails? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val order =
                        connection.prepareStatement(
                            "SELECT id, status FROM orders WHERE table_id = ? AND status = 'ACTIVE'",
                        ).use { statement ->
                            statement.setLong(1, tableId)
                            statement.executeQuery().use { rs ->
                                if (rs.next()) {
                                    rs.getLong("id") to rs.getString("status")
                                } else {
                                    null
                                }
                            }
                        } ?: return@use null

                    val batches =
                        connection.prepareStatement(
                            """
                            SELECT id, guest_comment
                            FROM order_batches
                            WHERE order_id = ?
                            ORDER BY id
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, order.first)
                            statement.executeQuery().use { rs ->
                                val result = mutableListOf<Pair<Long, String?>>()
                                while (rs.next()) {
                                    result.add(rs.getLong("id") to rs.getString("guest_comment"))
                                }
                                result
                            }
                        }

                    val itemsByBatch = loadBatchItems(connection, batches.map { it.first })

                    ActiveOrderDetails(
                        orderId = order.first,
                        status = order.second,
                        batches =
                            batches.map { (batchId, comment) ->
                                OrderBatchDetails(
                                    batchId = batchId,
                                    comment = comment,
                                    items = itemsByBatch[batchId].orEmpty(),
                                )
                            },
                    )
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun getOrCreateActiveOrderId(
        tableId: Long,
        venueId: Long,
    ): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val existing = findActiveOrderForUpdate(connection, tableId)
                    if (existing != null) {
                        connection.commit()
                        return@use existing
                    }
                    val orderId =
                        try {
                            connection.prepareStatement(
                                """
                                INSERT INTO orders (venue_id, table_id, status)
                                VALUES (?, ?, 'ACTIVE')
                                RETURNING id
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, venueId)
                                statement.setLong(2, tableId)
                                statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                            }
                        } catch (e: SQLException) {
                            if (e.sqlState == "23505") {
                                connection.rollback()
                                val activeId = findActiveOrderForUpdate(connection, tableId)
                                connection.commit()
                                return@use activeId
                            }
                            throw e
                        }
                    connection.commit()
                    orderId
                } catch (e: Exception) {
                    connection.rollback()
                    throw e
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    suspend fun createOrderBatch(
        orderId: Long,
        authorUserId: Long?,
        guestComment: String,
    ): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val sql =
                    """
                    INSERT INTO order_batches (order_id, author_user_id, source, status, guest_comment)
                    VALUES (?, ?, 'CHAT', 'NEW', ?)
                    RETURNING id
                    """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, orderId)
                    if (authorUserId != null) {
                        statement.setLong(2, authorUserId)
                    } else {
                        statement.setNull(2, java.sql.Types.BIGINT)
                    }
                    statement.setString(3, guestComment)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                }
            }
        }
    }

    suspend fun createGuestOrderBatch(
        tableId: Long,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
        idempotencyKey: String,
        tabId: Long,
        comment: String?,
        items: List<OrderBatchItemInput>,
    ): CreatedOrderBatch? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        if (!lockTable(connection, tableId)) {
                            connection.rollback()
                            return@use null
                        }
                        val existing =
                            findBatchIdempotency(
                                connection = connection,
                                venueId = venueId,
                                tableSessionId = tableSessionId,
                                userId = userId,
                                idempotencyKey = idempotencyKey,
                            )
                        if (existing != null) {
                            connection.commit()
                            return@use CreatedOrderBatch(
                                orderId = existing.orderId,
                                batchId = existing.batchId,
                                idempotencyReplay = true,
                            )
                        }

                        val orderId =
                            findActiveOrderForUpdate(connection, tableId)
                                ?: insertActiveOrder(connection, venueId, tableId)
                        val batchId = insertOrderBatch(connection, orderId, tabId, comment)
                        insertBatchItems(connection, batchId, items)
                        insertBatchIdempotency(
                            connection = connection,
                            venueId = venueId,
                            tableSessionId = tableSessionId,
                            userId = userId,
                            idempotencyKey = idempotencyKey,
                            orderId = orderId,
                            batchId = batchId,
                        )
                        analyticsEventRepository?.append(
                            connection = connection,
                            event =
                                AnalyticsEventRecord(
                                    eventType = "batch_created",
                                    payload =
                                        analyticsCorrelationPayload(
                                            venueId = venueId,
                                            tableId = tableId,
                                            tableSessionId = tableSessionId,
                                            orderId = orderId,
                                            batchId = batchId,
                                            tabId = tabId,
                                        ),
                                    venueId = venueId,
                                    tableId = tableId,
                                    tableSessionId = tableSessionId,
                                    orderId = orderId,
                                    batchId = batchId,
                                    tabId = tabId,
                                    idempotencyKey =
                                        "batch_created:$venueId:$tableSessionId:$userId:$idempotencyKey",
                                ),
                        )
                        connection.commit()
                        CreatedOrderBatch(
                            orderId = orderId,
                            batchId = batchId,
                            idempotencyReplay = false,
                        )
                    } catch (e: SQLException) {
                        connection.rollback()
                        if (e.sqlState == "23505") {
                            findBatchIdempotencyInNewConnection(
                                ds = ds,
                                venueId = venueId,
                                tableSessionId = tableSessionId,
                                userId = userId,
                                idempotencyKey = idempotencyKey,
                            )?.let { existing ->
                                return@use CreatedOrderBatch(
                                    orderId = existing.orderId,
                                    batchId = existing.batchId,
                                    idempotencyReplay = true,
                                )
                            }
                        }
                        throw e
                    } finally {
                        connection.autoCommit = true
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun lockTable(
        connection: Connection,
        tableId: Long,
    ): Boolean {
        return connection.prepareStatement(
            "SELECT id FROM venue_tables WHERE id = ? FOR UPDATE",
        ).use { statement ->
            statement.setLong(1, tableId)
            statement.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun findActiveOrderForUpdate(
        connection: Connection,
        tableId: Long,
    ): Long? {
        return connection.prepareStatement(
            "SELECT id FROM orders WHERE table_id = ? AND status = 'ACTIVE' FOR UPDATE",
        ).use { statement ->
            statement.setLong(1, tableId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else null }
        }
    }

    private fun insertActiveOrder(
        connection: Connection,
        venueId: Long,
        tableId: Long,
    ): Long {
        return connection.prepareStatement(
            """
            INSERT INTO orders (venue_id, table_id, status)
            VALUES (?, ?, 'ACTIVE')
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.executeUpdate()
            statement.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else error("Failed to create order") }
        }
    }

    private fun insertOrderBatch(
        connection: Connection,
        orderId: Long,
        tabId: Long,
        comment: String?,
    ): Long {
        val sql =
            """
            INSERT INTO order_batches (order_id, tab_id, author_user_id, source, status, guest_comment)
            VALUES (?, ?, NULL, 'MINIAPP', 'NEW', ?)
            """.trimIndent()
        return connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            statement.setLong(1, orderId)
            statement.setLong(2, tabId)
            if (comment != null) {
                statement.setString(3, comment)
            } else {
                statement.setNull(3, Types.VARCHAR)
            }
            statement.executeUpdate()
            statement.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else error("Failed to create batch") }
        }
    }

    private fun insertBatchItems(
        connection: Connection,
        batchId: Long,
        items: List<OrderBatchItemInput>,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO order_batch_items (order_batch_id, menu_item_id, qty)
            VALUES (?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            items.forEach { item ->
                statement.setLong(1, batchId)
                statement.setLong(2, item.itemId)
                statement.setInt(3, item.qty)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private data class StoredBatchIdempotency(
        val orderId: Long,
        val batchId: Long,
    )

    private fun findBatchIdempotency(
        connection: Connection,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
        idempotencyKey: String,
    ): StoredBatchIdempotency? {
        return connection.prepareStatement(
            """
            SELECT order_id, batch_id
            FROM guest_batch_idempotency
            WHERE venue_id = ? AND table_session_id = ? AND user_id = ? AND idempotency_key = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableSessionId)
            statement.setLong(3, userId)
            statement.setString(4, idempotencyKey)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredBatchIdempotency(orderId = rs.getLong("order_id"), batchId = rs.getLong("batch_id"))
                } else {
                    null
                }
            }
        }
    }

    private fun insertBatchIdempotency(
        connection: Connection,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
        idempotencyKey: String,
        orderId: Long,
        batchId: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO guest_batch_idempotency (
                venue_id,
                table_session_id,
                user_id,
                idempotency_key,
                order_id,
                batch_id
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableSessionId)
            statement.setLong(3, userId)
            statement.setString(4, idempotencyKey)
            statement.setLong(5, orderId)
            statement.setLong(6, batchId)
            statement.executeUpdate()
        }
    }

    private fun findBatchIdempotencyInNewConnection(
        ds: DataSource,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
        idempotencyKey: String,
    ): StoredBatchIdempotency? {
        return ds.connection.use { lookupConnection ->
            findBatchIdempotency(
                connection = lookupConnection,
                venueId = venueId,
                tableSessionId = tableSessionId,
                userId = userId,
                idempotencyKey = idempotencyKey,
            )
        }
    }

    private fun loadBatchItems(
        connection: Connection,
        batchIds: List<Long>,
    ): Map<Long, List<OrderBatchItemDetails>> {
        if (batchIds.isEmpty()) {
            return emptyMap()
        }
        val placeholders = batchIds.joinToString(",") { "?" }
        val sql =
            """
            SELECT id, order_batch_id, menu_item_id, qty
            FROM order_batch_items
            WHERE order_batch_id IN ($placeholders)
            ORDER BY order_batch_id, id
            """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            batchIds.forEachIndexed { index, batchId ->
                statement.setLong(index + 1, batchId)
            }
            statement.executeQuery().use { rs ->
                val result = linkedMapOf<Long, MutableList<OrderBatchItemDetails>>()
                while (rs.next()) {
                    val batchId = rs.getLong("order_batch_id")
                    val items = result.getOrPut(batchId) { mutableListOf() }
                    items.add(
                        OrderBatchItemDetails(
                            itemId = rs.getLong("menu_item_id"),
                            qty = rs.getInt("qty"),
                        ),
                    )
                }
                result
            }
        }
    }
}
