package com.hookah.platform.backend.miniapp.venue.orders

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.venue.VenueRole
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class OrderQueueCursor(
    val createdAt: Instant,
    val batchId: Long
) {
    fun encode(): String = "${createdAt.toEpochMilli()}:$batchId"

    companion object {
        fun parse(raw: String?): OrderQueueCursor? {
            if (raw.isNullOrBlank()) {
                return null
            }
            val parts = raw.split(":")
            if (parts.size != 2) {
                return null
            }
            val epoch = parts[0].toLongOrNull() ?: return null
            val batchId = parts[1].toLongOrNull() ?: return null
            return OrderQueueCursor(Instant.ofEpochMilli(epoch), batchId)
        }
    }
}

data class OrderQueueItem(
    val orderId: Long,
    val batchId: Long,
    val tableNumber: Int,
    val createdAt: Instant,
    val comment: String?,
    val itemsCount: Int,
    val status: OrderWorkflowStatus
)

data class OrderQueueResult(
    val items: List<OrderQueueItem>,
    val nextCursor: OrderQueueCursor?
)

data class OrderDetail(
    val orderId: Long,
    val venueId: Long,
    val tableId: Long,
    val tableNumber: Int,
    val status: OrderWorkflowStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val batches: List<OrderBatchDetail>
)

data class OrderBatchDetail(
    val batchId: Long,
    val status: OrderWorkflowStatus,
    val source: String,
    val comment: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val rejectedReasonCode: String?,
    val rejectedReasonText: String?,
    val items: List<OrderBatchItemDetail>
)

data class OrderBatchItemDetail(
    val itemId: Long,
    val name: String,
    val qty: Int
)

data class OrderAuditEntry(
    val orderId: Long,
    val actorUserId: Long,
    val actorRole: String,
    val action: String,
    val fromStatus: OrderWorkflowStatus,
    val toStatus: OrderWorkflowStatus,
    val reasonCode: String?,
    val reasonText: String?,
    val createdAt: Instant
)

data class OrderActionActor(
    val userId: Long,
    val role: VenueRole
)

data class OrderStatusUpdateResult(
    val orderId: Long,
    val status: OrderWorkflowStatus,
    val updatedAt: Instant,
    val applied: Boolean
)

class VenueOrdersRepository(private val dataSource: DataSource?) {
    suspend fun listQueue(
        venueId: Long,
        status: OrderBatchStatus,
        limit: Int,
        cursor: OrderQueueCursor?
    ): OrderQueueResult {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val items = mutableListOf<OrderQueueItem>()
                    val sql = buildString {
                        append(
                            """
                                SELECT ob.id AS batch_id,
                                       ob.created_at AS created_at,
                                       ob.guest_comment AS guest_comment,
                                       ob.status AS status,
                                       o.id AS order_id,
                                       vt.table_number AS table_number,
                                       COUNT(obi.id) AS items_count
                                FROM order_batches ob
                                JOIN orders o ON o.id = ob.order_id
                                JOIN venue_tables vt ON vt.id = o.table_id
                                LEFT JOIN order_batch_items obi ON obi.order_batch_id = ob.id
                                WHERE o.venue_id = ?
                                  AND o.status = 'ACTIVE'
                                  AND ob.status = ?
                            """.trimIndent()
                        )
                        if (cursor != null) {
                            append(
                                """
                                  AND (
                                      ob.created_at < ?
                                      OR (ob.created_at = ? AND ob.id < ?)
                                  )
                                """.trimIndent()
                            )
                        }
                        append(
                            """
                                GROUP BY ob.id, o.id, vt.table_number
                                ORDER BY ob.created_at DESC, ob.id DESC
                                LIMIT ?
                            """.trimIndent()
                        )
                    }
                    connection.prepareStatement(sql).use { statement ->
                        var index = 1
                        statement.setLong(index++, venueId)
                        statement.setString(index++, status.dbValue)
                        if (cursor != null) {
                            val ts = Timestamp.from(cursor.createdAt)
                            statement.setTimestamp(index++, ts)
                            statement.setTimestamp(index++, ts)
                            statement.setLong(index++, cursor.batchId)
                        }
                        statement.setInt(index, limit + 1)
                        statement.executeQuery().use { rs ->
                            while (rs.next()) {
                                val statusRaw = rs.getString("status")
                                val mappedStatus = OrderBatchStatus.fromDb(statusRaw)?.toWorkflow()
                                    ?: OrderWorkflowStatus.NEW
                                items.add(
                                    OrderQueueItem(
                                        orderId = rs.getLong("order_id"),
                                        batchId = rs.getLong("batch_id"),
                                        tableNumber = rs.getInt("table_number"),
                                        createdAt = rs.getTimestamp("created_at").toInstant(),
                                        comment = rs.getString("guest_comment"),
                                        itemsCount = rs.getInt("items_count"),
                                        status = mappedStatus
                                    )
                                )
                            }
                        }
                    }
                    val hasMore = items.size > limit
                    val trimmed = if (hasMore) items.dropLast(1) else items
                    val nextCursor = if (hasMore) {
                        val last = trimmed.last()
                        OrderQueueCursor(last.createdAt, last.batchId)
                    } else {
                        null
                    }
                    OrderQueueResult(items = trimmed, nextCursor = nextCursor)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun loadOrderDetail(venueId: Long, orderId: Long): OrderDetail? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val orderRow = connection.prepareStatement(
                        """
                            SELECT o.id,
                                   o.status,
                                   o.created_at,
                                   o.updated_at,
                                   o.venue_id,
                                   vt.id AS table_id,
                                   vt.table_number
                            FROM orders o
                            JOIN venue_tables vt ON vt.id = o.table_id
                            WHERE o.id = ? AND o.venue_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, orderId)
                        statement.setLong(2, venueId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                rs.getString("status") to rs
                            } else null
                        }
                    } ?: return@use null

                    val orderStatusRaw = orderRow.first
                    val orderRs = orderRow.second
                    val createdAt = orderRs.getTimestamp("created_at").toInstant()
                    val updatedAt = orderRs.getTimestamp("updated_at").toInstant()
                    val tableId = orderRs.getLong("table_id")
                    val tableNumber = orderRs.getInt("table_number")

                    val batches = connection.prepareStatement(
                        """
                            SELECT id, status, source, guest_comment, created_at, updated_at,
                                   rejected_reason_code, rejected_reason_text
                            FROM order_batches
                            WHERE order_id = ?
                            ORDER BY created_at, id
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, orderId)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<OrderBatchDetail>()
                            while (rs.next()) {
                                val batchStatus = OrderBatchStatus.fromDb(rs.getString("status"))
                                    ?.toWorkflow() ?: OrderWorkflowStatus.NEW
                                result.add(
                                    OrderBatchDetail(
                                        batchId = rs.getLong("id"),
                                        status = batchStatus,
                                        source = rs.getString("source"),
                                        comment = rs.getString("guest_comment"),
                                        createdAt = rs.getTimestamp("created_at").toInstant(),
                                        updatedAt = rs.getTimestamp("updated_at").toInstant(),
                                        rejectedReasonCode = rs.getString("rejected_reason_code"),
                                        rejectedReasonText = rs.getString("rejected_reason_text"),
                                        items = emptyList()
                                    )
                                )
                            }
                            result
                        }
                    }

                    val itemsByBatch = loadBatchItems(connection, batches.map { it.batchId })
                    val mappedBatches = batches.map { batch ->
                        batch.copy(items = itemsByBatch[batch.batchId].orEmpty())
                    }

                    val workflowStatus = resolveOrderWorkflowStatus(orderStatusRaw, mappedBatches)

                    OrderDetail(
                        orderId = orderId,
                        venueId = venueId,
                        tableId = tableId,
                        tableNumber = tableNumber,
                        status = workflowStatus,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        batches = mappedBatches
                    )
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun loadAudit(venueId: Long, orderId: Long): List<OrderAuditEntry> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val exists = connection.prepareStatement(
                        "SELECT 1 FROM orders WHERE id = ? AND venue_id = ?"
                    ).use { statement ->
                        statement.setLong(1, orderId)
                        statement.setLong(2, venueId)
                        statement.executeQuery().use { rs -> rs.next() }
                    }
                    if (!exists) {
                        return@use emptyList()
                    }
                    connection.prepareStatement(
                        """
                            SELECT order_id, actor_user_id, actor_role, action, from_status, to_status,
                                   reason_code, reason_text, created_at
                            FROM order_audit_log
                            WHERE order_id = ?
                            ORDER BY created_at DESC, id DESC
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, orderId)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<OrderAuditEntry>()
                            while (rs.next()) {
                                val fromStatus = OrderWorkflowStatus.fromApi(rs.getString("from_status"))
                                    ?: OrderWorkflowStatus.NEW
                                val toStatus = OrderWorkflowStatus.fromApi(rs.getString("to_status"))
                                    ?: OrderWorkflowStatus.NEW
                                result.add(
                                    OrderAuditEntry(
                                        orderId = rs.getLong("order_id"),
                                        actorUserId = rs.getLong("actor_user_id"),
                                        actorRole = rs.getString("actor_role"),
                                        action = rs.getString("action"),
                                        fromStatus = fromStatus,
                                        toStatus = toStatus,
                                        reasonCode = rs.getString("reason_code"),
                                        reasonText = rs.getString("reason_text"),
                                        createdAt = rs.getTimestamp("created_at").toInstant()
                                    )
                                )
                            }
                            result
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateOrderStatus(
        venueId: Long,
        orderId: Long,
        nextStatus: OrderWorkflowStatus,
        actor: OrderActionActor
    ): OrderStatusUpdateResult? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val orderRow = selectOrderForUpdate(connection, orderId, venueId) ?: run {
                        connection.rollback()
                        return@use null
                    }
                    val current = resolveOrderWorkflowStatus(orderRow.status, orderRow.batches)
                    if (!allowedNextStatuses(current).contains(nextStatus)) {
                        connection.rollback()
                        return@use OrderStatusUpdateResult(
                            orderId = orderId,
                            status = current,
                            updatedAt = orderRow.updatedAt,
                            applied = false
                        )
                    }
                    val now = OffsetDateTime.now(ZoneOffset.UTC)
                    if (nextStatus == OrderWorkflowStatus.CLOSED) {
                        updateOrderStatusOnly(connection, orderId)
                    } else {
                        val batchStatus = OrderBatchStatus.fromWorkflow(nextStatus)
                            ?: throw IllegalStateException("Missing batch status for $nextStatus")
                        updateLatestBatchStatus(connection, orderId, batchStatus.dbValue, now)
                        updateOrderTimestamp(connection, orderId, now)
                    }
                    insertAudit(
                        connection = connection,
                        orderId = orderId,
                        actor = actor,
                        action = "STATUS_CHANGE",
                        fromStatus = current,
                        toStatus = nextStatus,
                        reasonCode = null,
                        reasonText = null
                    )
                    connection.commit()
                    OrderStatusUpdateResult(
                        orderId = orderId,
                        status = nextStatus,
                        updatedAt = now.toInstant(),
                        applied = true
                    )
                } catch (e: Exception) {
                    connection.rollback()
                    throw e
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    suspend fun rejectOrder(
        venueId: Long,
        orderId: Long,
        reasonCode: String,
        reasonText: String?,
        actor: OrderActionActor
    ): OrderStatusUpdateResult? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val orderRow = selectOrderForUpdate(connection, orderId, venueId) ?: run {
                        connection.rollback()
                        return@use null
                    }
                    val current = resolveOrderWorkflowStatus(orderRow.status, orderRow.batches)
                    if (current == OrderWorkflowStatus.CLOSED) {
                        connection.rollback()
                        return@use OrderStatusUpdateResult(
                            orderId = orderId,
                            status = current,
                            updatedAt = orderRow.updatedAt,
                            applied = false
                        )
                    }
                    val now = OffsetDateTime.now(ZoneOffset.UTC)
                    updateLatestBatchRejected(connection, orderId, reasonCode, reasonText, now)
                    updateOrderStatusOnly(connection, orderId)
                    insertAudit(
                        connection = connection,
                        orderId = orderId,
                        actor = actor,
                        action = "REJECT",
                        fromStatus = current,
                        toStatus = OrderWorkflowStatus.CLOSED,
                        reasonCode = reasonCode,
                        reasonText = reasonText
                    )
                    connection.commit()
                    OrderStatusUpdateResult(
                        orderId = orderId,
                        status = OrderWorkflowStatus.CLOSED,
                        updatedAt = now.toInstant(),
                        applied = true
                    )
                } catch (e: Exception) {
                    connection.rollback()
                    throw e
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    private fun resolveOrderWorkflowStatus(orderStatusRaw: String?, batches: List<OrderBatchDetail>): OrderWorkflowStatus {
        if (orderStatusRaw.equals("CLOSED", ignoreCase = true) || orderStatusRaw.equals("CANCELLED", ignoreCase = true)) {
            return OrderWorkflowStatus.CLOSED
        }
        val latestBatch = batches.maxByOrNull { it.createdAt }
        return latestBatch?.status ?: OrderWorkflowStatus.NEW
    }

    private fun loadBatchItems(
        connection: Connection,
        batchIds: List<Long>
    ): Map<Long, List<OrderBatchItemDetail>> {
        if (batchIds.isEmpty()) {
            return emptyMap()
        }
        val placeholders = batchIds.joinToString(",") { "?" }
        val sql = """
            SELECT obi.id,
                   obi.order_batch_id,
                   obi.menu_item_id,
                   obi.qty,
                   mi.name
            FROM order_batch_items obi
            JOIN menu_items mi ON mi.id = obi.menu_item_id
            WHERE obi.order_batch_id IN ($placeholders)
            ORDER BY obi.order_batch_id, obi.id
        """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            batchIds.forEachIndexed { index, batchId ->
                statement.setLong(index + 1, batchId)
            }
            statement.executeQuery().use { rs ->
                val result = linkedMapOf<Long, MutableList<OrderBatchItemDetail>>()
                while (rs.next()) {
                    val batchId = rs.getLong("order_batch_id")
                    val items = result.getOrPut(batchId) { mutableListOf() }
                    items.add(
                        OrderBatchItemDetail(
                            itemId = rs.getLong("menu_item_id"),
                            name = rs.getString("name"),
                            qty = rs.getInt("qty")
                        )
                    )
                }
                result
            }
        }
    }

    private data class OrderRow(
        val status: String,
        val updatedAt: Instant,
        val batches: List<OrderBatchDetail>
    )

    private fun selectOrderForUpdate(connection: Connection, orderId: Long, venueId: Long): OrderRow? {
        val order = connection.prepareStatement(
            """
                SELECT status, updated_at
                FROM orders
                WHERE id = ? AND venue_id = ?
                FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    rs.getString("status") to rs.getTimestamp("updated_at").toInstant()
                } else null
            }
        } ?: return null

        val batches = connection.prepareStatement(
            """
                SELECT id, status, source, guest_comment, created_at, updated_at,
                       rejected_reason_code, rejected_reason_text
                FROM order_batches
                WHERE order_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.executeQuery().use { rs ->
                val result = mutableListOf<OrderBatchDetail>()
                while (rs.next()) {
                    val status = OrderBatchStatus.fromDb(rs.getString("status"))
                        ?.toWorkflow() ?: OrderWorkflowStatus.NEW
                    result.add(
                        OrderBatchDetail(
                            batchId = rs.getLong("id"),
                            status = status,
                            source = rs.getString("source"),
                            comment = rs.getString("guest_comment"),
                            createdAt = rs.getTimestamp("created_at").toInstant(),
                            updatedAt = rs.getTimestamp("updated_at").toInstant(),
                            rejectedReasonCode = rs.getString("rejected_reason_code"),
                            rejectedReasonText = rs.getString("rejected_reason_text"),
                            items = emptyList()
                        )
                    )
                }
                result
            }
        }

        return OrderRow(order.first, order.second, batches)
    }

    private fun updateLatestBatchStatus(
        connection: Connection,
        orderId: Long,
        status: String,
        now: OffsetDateTime
    ) {
        connection.prepareStatement(
            """
                UPDATE order_batches
                SET status = ?, updated_at = ?
                WHERE id = (
                    SELECT id
                    FROM order_batches
                    WHERE order_id = ?
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1
                )
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, status)
            statement.setObject(2, now)
            statement.setLong(3, orderId)
            statement.executeUpdate()
        }
    }

    private fun updateLatestBatchRejected(
        connection: Connection,
        orderId: Long,
        reasonCode: String,
        reasonText: String?,
        now: OffsetDateTime
    ) {
        connection.prepareStatement(
            """
                UPDATE order_batches
                SET status = 'REJECTED',
                    updated_at = ?,
                    rejected_reason_code = ?,
                    rejected_reason_text = ?,
                    rejected_at = ?
                WHERE id = (
                    SELECT id
                    FROM order_batches
                    WHERE order_id = ?
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1
                )
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, now)
            statement.setString(2, reasonCode)
            if (reasonText != null) {
                statement.setString(3, reasonText)
            } else {
                statement.setNull(3, java.sql.Types.VARCHAR)
            }
            statement.setObject(4, now)
            statement.setLong(5, orderId)
            statement.executeUpdate()
        }
    }

    private fun updateOrderStatusOnly(connection: Connection, orderId: Long) {
        connection.prepareStatement(
            """
                UPDATE orders
                SET status = 'CLOSED',
                    updated_at = now()
                WHERE id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.executeUpdate()
        }
    }

    private fun updateOrderTimestamp(connection: Connection, orderId: Long, now: OffsetDateTime) {
        connection.prepareStatement(
            """
                UPDATE orders
                SET updated_at = ?
                WHERE id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, now)
            statement.setLong(2, orderId)
            statement.executeUpdate()
        }
    }

    private fun insertAudit(
        connection: Connection,
        orderId: Long,
        actor: OrderActionActor,
        action: String,
        fromStatus: OrderWorkflowStatus,
        toStatus: OrderWorkflowStatus,
        reasonCode: String?,
        reasonText: String?
    ) {
        connection.prepareStatement(
            """
                INSERT INTO order_audit_log (
                    order_id,
                    actor_user_id,
                    actor_role,
                    action,
                    from_status,
                    to_status,
                    reason_code,
                    reason_text
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.setLong(2, actor.userId)
            statement.setString(3, actor.role.name)
            statement.setString(4, action)
            statement.setString(5, fromStatus.toApi())
            statement.setString(6, toStatus.toApi())
            if (reasonCode != null) {
                statement.setString(7, reasonCode)
            } else {
                statement.setNull(7, java.sql.Types.VARCHAR)
            }
            if (reasonText != null) {
                statement.setString(8, reasonText)
            } else {
                statement.setNull(8, java.sql.Types.VARCHAR)
            }
            statement.executeUpdate()
        }
    }
}
