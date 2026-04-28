package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.analytics.AnalyticsEventRecord
import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.analytics.analyticsCorrelationPayload
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.telegram.ActiveOrderSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Date
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.time.LocalDate
import java.time.ZoneId
import javax.sql.DataSource

data class OrderBatchItemInput(
    val itemId: Long,
    val qty: Int,
)

data class OrderBatchItemDetails(
    val itemId: Long,
    val qty: Int,
    val itemName: String? = null,
    val priceMinor: Long? = null,
    val currency: String? = null,
    val discountPercent: Int? = null,
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
    val displayNumber: Int? = null,
    val displayDate: LocalDate? = null,
)

data class CreatedOrderBatch(
    val orderId: Long,
    val batchId: Long,
    val idempotencyReplay: Boolean,
    val displayNumber: Int? = null,
    val displayDate: LocalDate? = null,
)

data class UserActiveOrderSummary(
    val orderId: Long,
    val venueId: Long,
    val venueName: String,
    val status: String,
    val tabType: String? = null,
    val items: List<UserActiveOrderItemSummary> = emptyList(),
    val displayNumber: Int? = null,
    val displayDate: LocalDate? = null,
)

data class UserActiveOrderItemSummary(
    val itemId: Long,
    val itemName: String,
    val qty: Int,
    val priceMinor: Long? = null,
    val currency: String? = null,
    val discountPercent: Int? = null,
)

private data class ActiveOrderHeader(
    val orderId: Long,
    val status: String,
    val displayNumber: Int?,
    val displayDate: LocalDate?,
)

private data class OrderDisplay(
    val displayNumber: Int?,
    val displayDate: LocalDate?,
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
                    "SELECT id, status, display_number, display_date FROM orders WHERE table_id = ? AND status = 'ACTIVE'",
                ).use { statement ->
                    statement.setLong(1, tableId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            ActiveOrderSummary(
                                id = rs.getLong("id"),
                                status = rs.getString("status"),
                                displayNumber = rs.getInt("display_number").let { value -> if (rs.wasNull()) null else value },
                                displayDate = rs.getDate("display_date")?.toLocalDate(),
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }
    }

    suspend fun findActiveOrderSummaryForTab(
        tableId: Long,
        tabId: Long,
    ): ActiveOrderSummary? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT o.id, o.status, o.display_number, o.display_date
                    FROM orders o
                    WHERE o.table_id = ?
                      AND o.status = 'ACTIVE'
                      AND EXISTS (
                        SELECT 1
                        FROM order_batches ob
                        WHERE ob.order_id = o.id
                          AND ob.tab_id = ?
                      )
                    ORDER BY o.id DESC
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, tableId)
                    statement.setLong(2, tabId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            ActiveOrderSummary(
                                id = rs.getLong("id"),
                                status = rs.getString("status"),
                                displayNumber = rs.getInt("display_number").let { value -> if (rs.wasNull()) null else value },
                                displayDate = rs.getDate("display_date")?.toLocalDate(),
                            )
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
                            "SELECT id, status, display_number, display_date FROM orders WHERE table_id = ? AND status = 'ACTIVE'",
                        ).use { statement ->
                            statement.setLong(1, tableId)
                            statement.executeQuery().use { rs ->
                                if (rs.next()) {
                                    ActiveOrderHeader(
                                        orderId = rs.getLong("id"),
                                        status = rs.getString("status"),
                                        displayNumber = rs.getInt("display_number").let { value -> if (rs.wasNull()) null else value },
                                        displayDate = rs.getDate("display_date")?.toLocalDate(),
                                    )
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
                              AND status <> 'REJECTED'
                              AND status <> 'CLOSED'
                              AND rejected_reason_code IS NULL
                              AND rejected_reason_text IS NULL
                            ORDER BY id
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, order.orderId)
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
                        orderId = order.orderId,
                        status = order.status,
                        displayNumber = order.displayNumber,
                        displayDate = order.displayDate,
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

    suspend fun findActiveOrderDetailsForTab(
        tableId: Long,
        tabId: Long,
    ): ActiveOrderDetails? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val order =
                        connection.prepareStatement(
                            """
                            SELECT o.id, o.status, o.display_number, o.display_date
                            FROM orders o
                            WHERE o.table_id = ?
                              AND o.status = 'ACTIVE'
                              AND EXISTS (
                                SELECT 1
                                FROM order_batches ob
                                WHERE ob.order_id = o.id
                                  AND ob.tab_id = ?
                              )
                            ORDER BY o.id DESC
                            LIMIT 1
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, tableId)
                            statement.setLong(2, tabId)
                            statement.executeQuery().use { rs ->
                                if (rs.next()) {
                                    ActiveOrderHeader(
                                        orderId = rs.getLong("id"),
                                        status = rs.getString("status"),
                                        displayNumber = rs.getInt("display_number").let { value -> if (rs.wasNull()) null else value },
                                        displayDate = rs.getDate("display_date")?.toLocalDate(),
                                    )
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
                              AND tab_id = ?
                              AND status <> 'REJECTED'
                              AND status <> 'CLOSED'
                              AND rejected_reason_code IS NULL
                              AND rejected_reason_text IS NULL
                            ORDER BY id
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, order.orderId)
                            statement.setLong(2, tabId)
                            statement.executeQuery().use { rs ->
                                val result = mutableListOf<Pair<Long, String?>>()
                                while (rs.next()) {
                                    result.add(rs.getLong("id") to rs.getString("guest_comment"))
                                }
                                result
                            }
                        }

                    if (batches.isEmpty()) {
                        return@use null
                    }

                    val itemsByBatch = loadBatchItems(connection, batches.map { it.first })
                    ActiveOrderDetails(
                        orderId = order.orderId,
                        status = order.status,
                        displayNumber = order.displayNumber,
                        displayDate = order.displayDate,
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

    suspend fun listActiveOrderSummariesForUser(
        userId: Long,
        limit: Int = 20,
    ): List<UserActiveOrderSummary> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT DISTINCT o.id, o.venue_id, v.name AS venue_name, o.status, o.display_number, o.display_date
                        FROM guest_batch_idempotency gbi
                        JOIN orders o ON o.id = gbi.order_id
                        JOIN venues v ON v.id = o.venue_id
                        WHERE gbi.user_id = ?
                          AND o.status = 'ACTIVE'
                        ORDER BY o.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.setInt(2, limit)
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    val orderId = rs.getLong("id")
                                    add(
                                        UserActiveOrderSummary(
                                            orderId = orderId,
                                            venueId = rs.getLong("venue_id"),
                                            venueName = rs.getString("venue_name"),
                                            status = rs.getString("status"),
                                            tabType = loadUserTabTypeForOrder(connection, userId, orderId),
                                            items = loadOrderItemsSummary(connection, orderId),
                                            displayNumber = rs.getInt("display_number").let { value -> if (rs.wasNull()) null else value },
                                            displayDate = rs.getDate("display_date")?.toLocalDate(),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun loadUserTabTypeForOrder(
        connection: Connection,
        userId: Long,
        orderId: Long,
    ): String? =
        connection.prepareStatement(
            """
            SELECT t.type
            FROM guest_batch_idempotency gbi
            JOIN order_batches ob ON ob.id = gbi.batch_id
            JOIN tab t ON t.id = ob.tab_id
            WHERE gbi.user_id = ?
              AND gbi.order_id = ?
            ORDER BY CASE
                WHEN t.type = 'SHARED' THEN 0
                WHEN t.type = 'PERSONAL' THEN 1
                ELSE 2
            END
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, orderId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getString("type") else null
            }
        }

    private fun loadOrderItemsSummary(
        connection: Connection,
        orderId: Long,
    ): List<UserActiveOrderItemSummary> =
        connection.prepareStatement(
            """
            SELECT
                obi.menu_item_id,
                mi.name AS item_name,
                SUM(obi.qty) AS qty,
                mi.price_minor,
                mi.currency,
                obi.discount_percent
            FROM order_batches ob
            JOIN order_batch_items obi ON obi.order_batch_id = ob.id
            LEFT JOIN menu_items mi ON mi.id = obi.menu_item_id
            WHERE ob.order_id = ?
              AND ob.status <> 'REJECTED'
              AND ob.status <> 'CLOSED'
              AND ob.rejected_reason_code IS NULL
              AND ob.rejected_reason_text IS NULL
              AND obi.is_excluded = FALSE
            GROUP BY obi.menu_item_id, mi.name, mi.price_minor, mi.currency, obi.discount_percent
            ORDER BY MIN(obi.id) ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val itemId = rs.getLong("menu_item_id")
                        val itemName = rs.getString("item_name")?.takeIf { it.isNotBlank() } ?: "Позиция #$itemId"
                        val qty = rs.getInt("qty")
                        add(
                            UserActiveOrderItemSummary(
                                itemId = itemId,
                                itemName = itemName,
                                qty = qty,
                                priceMinor = rs.getLong("price_minor").let { value -> if (rs.wasNull()) null else value },
                                currency = rs.getString("currency"),
                                discountPercent = rs.getInt("discount_percent").let { value -> if (rs.wasNull()) null else value },
                            ),
                        )
                    }
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
                            insertActiveOrder(connection, venueId, tableId)
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
                            val orderDisplay = loadOrderDisplay(connection, existing.orderId)
                            connection.commit()
                            return@use CreatedOrderBatch(
                                orderId = existing.orderId,
                                batchId = existing.batchId,
                                idempotencyReplay = true,
                                displayNumber = orderDisplay.displayNumber,
                                displayDate = orderDisplay.displayDate,
                            )
                        }

                        val orderId =
                            findActiveOrderForUpdate(connection, tableId)
                                ?: insertActiveOrder(connection, venueId, tableId)
                        val orderDisplay = loadOrderDisplay(connection, orderId)
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
                                    idempotencyKey = "batch_created:$venueId:$batchId",
                                ),
                        )
                        connection.commit()
                        CreatedOrderBatch(
                            orderId = orderId,
                            batchId = batchId,
                            idempotencyReplay = false,
                            displayNumber = orderDisplay.displayNumber,
                            displayDate = orderDisplay.displayDate,
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
                                    displayNumber = existing.displayNumber,
                                    displayDate = existing.displayDate,
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

    private fun lockVenue(
        connection: Connection,
        venueId: Long,
    ) {
        connection.prepareStatement(
            "SELECT id FROM venues WHERE id = ? FOR UPDATE",
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) error("Venue $venueId not found")
            }
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
        lockVenue(connection, venueId)
        val displayDate = LocalDate.now(ZoneId.systemDefault())
        val displayNumber = nextOrderDisplayNumber(connection, venueId, displayDate)
        return connection.prepareStatement(
            """
            INSERT INTO orders (venue_id, table_id, status, display_number, display_date)
            VALUES (?, ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.setInt(3, displayNumber)
            statement.setDate(4, Date.valueOf(displayDate))
            statement.executeUpdate()
            statement.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else error("Failed to create order") }
        }
    }

    private fun nextOrderDisplayNumber(
        connection: Connection,
        venueId: Long,
        displayDate: LocalDate,
    ): Int =
        connection.prepareStatement(
            """
            SELECT COALESCE(MAX(display_number), 0) + 1 AS next_number
            FROM orders
            WHERE venue_id = ?
              AND display_date = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setDate(2, Date.valueOf(displayDate))
            statement.executeQuery().use { rs -> if (rs.next()) rs.getInt("next_number") else 1 }
        }

    private fun loadOrderDisplay(
        connection: Connection,
        orderId: Long,
    ): OrderDisplay =
        connection.prepareStatement(
            """
            SELECT display_number, display_date
            FROM orders
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    OrderDisplay(
                        displayNumber = rs.getInt("display_number").let { value -> if (rs.wasNull()) null else value },
                        displayDate = rs.getDate("display_date")?.toLocalDate(),
                    )
                } else {
                    OrderDisplay(displayNumber = null, displayDate = null)
                }
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
        val displayNumber: Int?,
        val displayDate: LocalDate?,
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
            SELECT gbi.order_id, gbi.batch_id, o.display_number, o.display_date
            FROM guest_batch_idempotency gbi
            JOIN orders o ON o.id = gbi.order_id
            WHERE gbi.venue_id = ? AND gbi.table_session_id = ? AND gbi.user_id = ? AND gbi.idempotency_key = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableSessionId)
            statement.setLong(3, userId)
            statement.setString(4, idempotencyKey)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredBatchIdempotency(
                        orderId = rs.getLong("order_id"),
                        batchId = rs.getLong("batch_id"),
                        displayNumber = rs.getInt("display_number").let { value -> if (rs.wasNull()) null else value },
                        displayDate = rs.getDate("display_date")?.toLocalDate(),
                    )
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
            SELECT obi.id,
                   obi.order_batch_id,
                   obi.menu_item_id,
                   obi.qty,
                   obi.discount_percent,
                   mi.name AS item_name,
                   mi.price_minor,
                   mi.currency
            FROM order_batch_items obi
            LEFT JOIN menu_items mi ON mi.id = obi.menu_item_id
            WHERE obi.order_batch_id IN ($placeholders)
              AND obi.is_excluded = FALSE
            ORDER BY obi.order_batch_id, obi.id
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
                    val itemId = rs.getLong("menu_item_id")
                    items.add(
                        OrderBatchItemDetails(
                            itemId = itemId,
                            qty = rs.getInt("qty"),
                            itemName = rs.getString("item_name")?.takeIf { it.isNotBlank() },
                            priceMinor = rs.getLong("price_minor").let { value -> if (rs.wasNull()) null else value },
                            currency = rs.getString("currency"),
                            discountPercent = rs.getInt("discount_percent").let { value -> if (rs.wasNull()) null else value },
                        ),
                    )
                }
                result
            }
        }
    }
}
