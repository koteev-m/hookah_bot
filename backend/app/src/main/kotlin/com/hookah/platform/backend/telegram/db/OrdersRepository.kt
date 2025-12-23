package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.telegram.ActiveOrderSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

class OrdersRepository(private val dataSource: DataSource?) {
    suspend fun findActiveOrderId(tableId: Long): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT id FROM orders WHERE table_id = ? AND status = 'ACTIVE'"
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
                    "SELECT id, status FROM orders WHERE table_id = ? AND status = 'ACTIVE'"
                ).use { statement ->
                    statement.setLong(1, tableId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            ActiveOrderSummary(rs.getLong(1), rs.getString(2))
                        } else null
                    }
                }
            }
        }
    }

    suspend fun getOrCreateActiveOrderId(tableId: Long, venueId: Long): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val existing = connection.prepareStatement(
                        "SELECT id FROM orders WHERE table_id = ? AND status = 'ACTIVE' FOR UPDATE"
                    ).use { statement ->
                        statement.setLong(1, tableId)
                        statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                    }
                    val orderId = existing ?: connection.prepareStatement(
                        """
                            INSERT INTO orders (venue_id, table_id, status)
                            VALUES (?, ?, 'ACTIVE')
                            RETURNING id
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, tableId)
                        statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
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
        guestComment: String
    ): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val sql = """
                    INSERT INTO order_batches (order_id, author_user_id, source, status, guest_comment)
                    VALUES (?, ?, 'CHAT', 'NEW', ?)
                    RETURNING id
                """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, orderId)
                    if (authorUserId != null) statement.setLong(2, authorUserId)
                    else statement.setNull(2, java.sql.Types.BIGINT)
                    statement.setString(3, guestComment)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                }
            }
        }
    }
}
