package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.telegram.BillPaymentMethod
import com.hookah.platform.backend.telegram.StaffCallReason
import com.hookah.platform.backend.telegram.billPaymentMethodLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.sql.DataSource

private val ACTIVE_STAFF_CALL_WINDOW: Duration = Duration.ofHours(24)

class StaffCallRepository(private val dataSource: DataSource?) {
    suspend fun listActiveByVenue(
        venueId: Long,
        limit: Int,
    ): List<StaffCallQueueItem> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val activeSince = Instant.now().minus(ACTIVE_STAFF_CALL_WINDOW)
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT sc.id,
                               sc.table_id,
                               vt.table_number,
                               sc.reason,
                               sc.comment,
                               sc.status,
                               sc.created_at,
                               sc.order_id,
                               sc.tab_id,
                               sc.payment_method,
                               o.display_number,
                               t.type AS tab_type,
                               u.guest_display_name
                        FROM staff_calls sc
                        JOIN venue_tables vt ON vt.id = sc.table_id
                        LEFT JOIN orders o ON o.id = sc.order_id
                        LEFT JOIN tab t ON t.id = sc.tab_id
                        LEFT JOIN users u ON u.telegram_user_id = sc.created_by_user_id
                        WHERE sc.venue_id = ?
                          AND sc.status IN ('NEW', 'ACK')
                          AND sc.created_at >= ?
                        ORDER BY sc.created_at DESC, sc.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setTimestamp(2, Timestamp.from(activeSince))
                        statement.setInt(3, limit)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<StaffCallQueueItem>()
                            while (rs.next()) {
                                result.add(rs.toStaffCallQueueItem())
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

    suspend fun listByGuestTableSession(
        venueId: Long,
        tableId: Long,
        tableSessionId: Long,
        userId: Long,
        limit: Int,
    ): List<StaffCallQueueItem> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val activeSince = Instant.now().minus(ACTIVE_STAFF_CALL_WINDOW)
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT sc.id,
                               sc.table_id,
                               vt.table_number,
                               sc.reason,
                               sc.comment,
                               sc.status,
                               sc.created_at,
                               sc.order_id,
                               sc.tab_id,
                               sc.payment_method,
                               o.display_number,
                               t.type AS tab_type,
                               u.guest_display_name
                        FROM staff_calls sc
                        JOIN venue_tables vt ON vt.id = sc.table_id
                        LEFT JOIN orders o ON o.id = sc.order_id
                        LEFT JOIN tab t ON t.id = sc.tab_id
                        LEFT JOIN users u ON u.telegram_user_id = sc.created_by_user_id
                        WHERE sc.venue_id = ?
                          AND sc.table_id = ?
                          AND sc.table_session_id = ?
                          AND sc.created_by_user_id = ?
                          AND sc.status IN ('NEW', 'ACK', 'DONE')
                          AND sc.created_at >= ?
                        ORDER BY sc.created_at DESC, sc.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, tableId)
                        statement.setLong(3, tableSessionId)
                        statement.setLong(4, userId)
                        statement.setTimestamp(5, Timestamp.from(activeSince))
                        statement.setInt(6, limit)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<StaffCallQueueItem>()
                            while (rs.next()) {
                                result.add(rs.toStaffCallQueueItem())
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

    suspend fun createStaffCall(
        venueId: Long,
        tableId: Long,
        createdByUserId: Long?,
        reason: StaffCallReason,
        comment: String?,
        tableSessionId: Long? = null,
        orderId: Long? = null,
    ): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val sql =
                    """
                    INSERT INTO staff_calls (
                        venue_id, table_id, table_session_id, created_by_user_id, reason, comment, status, order_id
                    )
                    VALUES (?, ?, ?, ?, ?, ?, 'NEW', ?)
                    RETURNING id
                    """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, tableId)
                    if (tableSessionId != null) {
                        statement.setLong(3, tableSessionId)
                    } else {
                        statement.setNull(3, java.sql.Types.BIGINT)
                    }
                    if (createdByUserId != null) {
                        statement.setLong(4, createdByUserId)
                    } else {
                        statement.setNull(4, java.sql.Types.BIGINT)
                    }
                    statement.setString(5, reason.name)
                    statement.setString(6, comment)
                    if (orderId != null) {
                        statement.setLong(7, orderId)
                    } else {
                        statement.setNull(7, java.sql.Types.BIGINT)
                    }
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                }
            }
        }
    }

    suspend fun createGuestStaffCall(
        venueId: Long,
        tableId: Long,
        tableSessionId: Long,
        createdByUserId: Long?,
        reason: StaffCallReason,
        comment: String?,
        orderId: Long? = null,
    ): CreatedStaffCall {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val isH2 = connection.metaData.databaseProductName.contains("H2", ignoreCase = true)
                    if (createdByUserId != null) {
                        ensureUserExists(connection, createdByUserId, isH2)
                    }
                    if (isH2) {
                        val sql =
                            """
                            INSERT INTO staff_calls (
                                venue_id, table_id, table_session_id, created_by_user_id, reason, comment, status,
                                order_id
                            )
                            VALUES (?, ?, ?, ?, ?, ?, 'NEW', ?)
                            """.trimIndent()
                        connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { statement ->
                            statement.setLong(1, venueId)
                            statement.setLong(2, tableId)
                            statement.setLong(3, tableSessionId)
                            if (createdByUserId != null) {
                                statement.setLong(4, createdByUserId)
                            } else {
                                statement.setNull(4, java.sql.Types.BIGINT)
                            }
                            statement.setString(5, reason.name)
                            statement.setString(6, comment)
                            if (orderId != null) {
                                statement.setLong(7, orderId)
                            } else {
                                statement.setNull(7, java.sql.Types.BIGINT)
                            }
                            statement.executeUpdate()
                            statement.generatedKeys.use { keys ->
                                if (keys.next()) {
                                    val id = keys.getLong(1)
                                    val createdAt =
                                        connection.prepareStatement(
                                            "SELECT created_at FROM staff_calls WHERE id = ?",
                                        ).use { select ->
                                            select.setLong(1, id)
                                            select.executeQuery().use { rs ->
                                                if (rs.next()) {
                                                    rs.getTimestamp("created_at")?.toInstant() ?: Instant.now()
                                                } else {
                                                    Instant.now()
                                                }
                                            }
                                        }
                                    return@withContext CreatedStaffCall(id = id, createdAt = createdAt)
                                }
                            }
                        }
                        throw DatabaseUnavailableException()
                    }

                    val sql =
                        """
                        INSERT INTO staff_calls (
                            venue_id, table_id, table_session_id, created_by_user_id, reason, comment, status,
                            order_id
                        )
                        VALUES (?, ?, ?, ?, ?, ?, 'NEW', ?)
                        RETURNING id, created_at
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, tableId)
                        statement.setLong(3, tableSessionId)
                        if (createdByUserId != null) {
                            statement.setLong(4, createdByUserId)
                        } else {
                            statement.setNull(4, java.sql.Types.BIGINT)
                        }
                        statement.setString(5, reason.name)
                        statement.setString(6, comment)
                        if (orderId != null) {
                            statement.setLong(7, orderId)
                        } else {
                            statement.setNull(7, java.sql.Types.BIGINT)
                        }
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                val createdAt = rs.getTimestamp("created_at").toInstant()
                                return@withContext CreatedStaffCall(id = rs.getLong("id"), createdAt = createdAt)
                            }
                        }
                    }
                }
                throw DatabaseUnavailableException()
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun countActiveOrderActivity(
        venueId: Long,
        orderId: Long,
    ): Int {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT COUNT(*) AS count
                        FROM staff_calls
                        WHERE venue_id = ?
                          AND order_id = ?
                          AND status IN ('NEW', 'ACK')
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, orderId)
                        statement.executeQuery().use { rs -> if (rs.next()) rs.getInt("count") else 0 }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listOrderActivity(
        venueId: Long,
        orderId: Long,
        includeStaffCallId: Long? = null,
        limit: Int = 5,
    ): List<StaffOrderActivityItem> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT sc.id,
                               sc.reason,
                               sc.comment,
                               sc.status,
                               sc.created_at,
                               sc.tab_id,
                               sc.payment_method,
                               t.type AS tab_type,
                               u.guest_display_name
                        FROM staff_calls sc
                        LEFT JOIN tab t ON t.id = sc.tab_id
                        LEFT JOIN users u ON u.telegram_user_id = sc.created_by_user_id
                        WHERE sc.venue_id = ?
                          AND sc.order_id = ?
                          AND (
                              sc.status IN ('NEW', 'ACK')
                              OR (? IS NOT NULL AND sc.id = ?)
                          )
                        ORDER BY
                          CASE WHEN sc.status IN ('NEW', 'ACK') THEN 0 ELSE 1 END,
                          sc.created_at DESC,
                          sc.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, orderId)
                        if (includeStaffCallId != null) {
                            statement.setLong(3, includeStaffCallId)
                            statement.setLong(4, includeStaffCallId)
                        } else {
                            statement.setNull(3, java.sql.Types.BIGINT)
                            statement.setNull(4, java.sql.Types.BIGINT)
                        }
                        statement.setInt(5, limit)
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(rs.toStaffOrderActivityItem())
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

    suspend fun completeActiveBillRequestsForOrder(
        venueId: Long,
        orderId: Long,
    ): List<CompletedStaffCallStatusUpdate> =
        resolveActiveCallsForClosedOrder(
            venueId = venueId,
            orderId = orderId,
            billOnly = true,
        )

    suspend fun resolveActiveCallsForClosedOrder(
        venueId: Long,
        orderId: Long,
    ): List<CompletedStaffCallStatusUpdate> =
        resolveActiveCallsForClosedOrder(
            venueId = venueId,
            orderId = orderId,
            billOnly = false,
        )

    private suspend fun resolveActiveCallsForClosedOrder(
        venueId: Long,
        orderId: Long,
        billOnly: Boolean,
    ): List<CompletedStaffCallStatusUpdate> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    try {
                        connection.autoCommit = false
                        val forUpdateClause =
                            if (connection.metaData.databaseProductName.contains("H2", ignoreCase = true)) {
                                "FOR UPDATE"
                            } else {
                                "FOR UPDATE OF sc"
                            }
                        val tableSessionId =
                            connection.prepareStatement(
                                """
                                SELECT table_session_id
                                FROM orders
                                WHERE id = ?
                                  AND venue_id = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, orderId)
                                statement.setLong(2, venueId)
                                statement.executeQuery().use { rs ->
                                    if (rs.next()) {
                                        rs.getNullableLong("table_session_id")
                                    } else {
                                        null
                                    }
                                }
                            }
                        val activeCalls =
                            connection.prepareStatement(
                                """
                                SELECT sc.id,
                                       sc.status,
                                       sc.reason,
                                       sc.comment,
                                       sc.order_id,
                                       sc.tab_id,
                                       sc.payment_method,
                                       o.display_number,
                                       t.type AS tab_type,
                                       vt.table_number,
                                       u.guest_display_name
                                FROM staff_calls sc
                                JOIN venue_tables vt ON vt.id = sc.table_id
                                LEFT JOIN orders o ON o.id = sc.order_id
                                LEFT JOIN tab t ON t.id = sc.tab_id
                                LEFT JOIN users u ON u.telegram_user_id = sc.created_by_user_id
                                WHERE sc.venue_id = ?
                                  AND sc.status IN ('NEW', 'ACK')
                                  AND (
                                      sc.order_id = ?
                                      OR (
                                          ? = FALSE
                                          AND ? IS NOT NULL
                                          AND sc.table_session_id = ?
                                          AND (
                                              sc.order_id IS NULL
                                              OR NOT EXISTS (
                                                  SELECT 1
                                                  FROM orders linked_active_order
                                                  WHERE linked_active_order.id = sc.order_id
                                                    AND linked_active_order.venue_id = sc.venue_id
                                                    AND linked_active_order.status = 'ACTIVE'
                                              )
                                          )
                                          AND NOT EXISTS (
                                              SELECT 1
                                              FROM orders other_active_order
                                              WHERE other_active_order.venue_id = sc.venue_id
                                                AND other_active_order.table_session_id = sc.table_session_id
                                                AND other_active_order.status = 'ACTIVE'
                                                AND other_active_order.id <> ?
                                          )
                                      )
                                  )
                                  AND (? = FALSE OR sc.reason = 'BILL')
                                ORDER BY sc.created_at DESC, sc.id DESC
                                $forUpdateClause
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, venueId)
                                statement.setLong(2, orderId)
                                statement.setBoolean(3, billOnly)
                                if (tableSessionId != null) {
                                    statement.setLong(4, tableSessionId)
                                    statement.setLong(5, tableSessionId)
                                } else {
                                    statement.setNull(4, java.sql.Types.BIGINT)
                                    statement.setNull(5, java.sql.Types.BIGINT)
                                }
                                statement.setLong(6, orderId)
                                statement.setBoolean(7, billOnly)
                                statement.executeQuery().use { rs ->
                                    buildList {
                                        while (rs.next()) {
                                            val fromStatus =
                                                StaffCallStatus.fromDb(rs.getString("status"))
                                                    ?: StaffCallStatus.NEW
                                            val reason =
                                                runCatching { StaffCallReason.valueOf(rs.getString("reason")) }
                                                    .getOrDefault(StaffCallReason.OTHER)
                                            val nextStatus =
                                                if (reason == StaffCallReason.BILL) {
                                                    StaffCallStatus.DONE
                                                } else {
                                                    StaffCallStatus.CANCELLED
                                                }
                                            add(
                                                CompletedStaffCallStatusUpdate(
                                                    fromStatus = fromStatus,
                                                    result =
                                                        StaffCallStatusUpdateResult(
                                                            staffCallId = rs.getLong("id"),
                                                            status = nextStatus,
                                                            applied = true,
                                                            tableNumber = rs.getInt("table_number"),
                                                            reason = reason,
                                                            comment = rs.getString("comment"),
                                                            orderId = rs.getNullableLong("order_id"),
                                                            tabId = rs.getNullableLong("tab_id"),
                                                            paymentMethod =
                                                                rs.getString("payment_method")
                                                                    ?.toBillPaymentMethod(),
                                                            orderDisplayLabel =
                                                                orderDisplayLabel(
                                                                    displayNumber =
                                                                        rs.getNullableInt("display_number"),
                                                                    orderId = rs.getNullableLong("order_id"),
                                                                ),
                                                            tabDisplayLabel = tabDisplayLabel(rs.getString("tab_type")),
                                                            guestDisplayName = rs.getString("guest_display_name"),
                                                        ),
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        if (activeCalls.isEmpty()) {
                            connection.commit()
                            return@use emptyList()
                        }
                        activeCalls.forEach { completion ->
                            val updated =
                                connection.prepareStatement(
                                    """
                                    UPDATE staff_calls
                                    SET status = ?
                                    WHERE id = ?
                                      AND venue_id = ?
                                      AND status IN ('NEW', 'ACK')
                                    """.trimIndent(),
                                ).use { statement ->
                                    statement.setString(1, completion.result.status.dbValue)
                                    statement.setLong(2, completion.result.staffCallId)
                                    statement.setLong(3, venueId)
                                    statement.executeUpdate()
                                }
                            if (updated != 1) {
                                runCatching { connection.rollback() }
                                throw DatabaseUnavailableException()
                            }
                        }
                        connection.commit()
                        activeCalls
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw DatabaseUnavailableException()
                    } catch (e: Exception) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = true }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun createGuestBillRequest(
        venueId: Long,
        tableId: Long,
        tableSessionId: Long,
        tabId: Long,
        orderId: Long,
        createdByUserId: Long,
        paymentMethod: BillPaymentMethod,
    ): CreatedGuestBillRequest {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val isH2 = connection.metaData.databaseProductName.contains("H2", ignoreCase = true)
                    try {
                        connection.autoCommit = false
                        ensureUserExists(connection, createdByUserId, isH2)
                        connection.prepareStatement(
                            """
                            SELECT id
                            FROM table_sessions
                            WHERE id = ?
                              AND venue_id = ?
                              AND table_id = ?
                            FOR UPDATE
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, tableSessionId)
                            statement.setLong(2, venueId)
                            statement.setLong(3, tableId)
                            statement.executeQuery().use { rs ->
                                if (!rs.next()) {
                                    connection.rollback()
                                    throw DatabaseUnavailableException()
                                }
                            }
                        }
                        val existing =
                            connection.prepareStatement(
                                """
                                SELECT id, created_at, status, payment_method
                                FROM staff_calls
                                WHERE venue_id = ?
                                  AND table_session_id = ?
                                  AND tab_id = ?
                                  AND reason = 'BILL'
                                  AND status IN ('NEW', 'ACK')
                                ORDER BY created_at DESC, id DESC
                                LIMIT 1
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, venueId)
                                statement.setLong(2, tableSessionId)
                                statement.setLong(3, tabId)
                                statement.executeQuery().use { rs ->
                                    if (rs.next()) {
                                        CreatedGuestBillRequest(
                                            id = rs.getLong("id"),
                                            createdAt = rs.getTimestamp("created_at").toInstant(),
                                            status =
                                                StaffCallStatus.fromDb(rs.getString("status"))
                                                    ?: StaffCallStatus.NEW,
                                            paymentMethod =
                                                rs.getString("payment_method")?.toBillPaymentMethod()
                                                    ?: paymentMethod,
                                            alreadyActive = true,
                                        )
                                    } else {
                                        null
                                    }
                                }
                            }
                        if (existing != null) {
                            connection.commit()
                            return@withContext existing
                        }
                        val created =
                            if (isH2) {
                                insertGuestBillRequestH2(
                                    connection = connection,
                                    venueId = venueId,
                                    tableId = tableId,
                                    tableSessionId = tableSessionId,
                                    tabId = tabId,
                                    orderId = orderId,
                                    createdByUserId = createdByUserId,
                                    paymentMethod = paymentMethod,
                                )
                            } else {
                                insertGuestBillRequestPostgres(
                                    connection = connection,
                                    venueId = venueId,
                                    tableId = tableId,
                                    tableSessionId = tableSessionId,
                                    tabId = tabId,
                                    orderId = orderId,
                                    createdByUserId = createdByUserId,
                                    paymentMethod = paymentMethod,
                                )
                            }
                        connection.commit()
                        created
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw DatabaseUnavailableException()
                    } catch (e: Exception) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = true }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun insertGuestBillRequestPostgres(
        connection: java.sql.Connection,
        venueId: Long,
        tableId: Long,
        tableSessionId: Long,
        tabId: Long,
        orderId: Long,
        createdByUserId: Long,
        paymentMethod: BillPaymentMethod,
    ): CreatedGuestBillRequest {
        val sql =
            """
            INSERT INTO staff_calls (
                venue_id, table_id, table_session_id, created_by_user_id, reason, comment, status,
                order_id, tab_id, payment_method
            )
            VALUES (?, ?, ?, ?, 'BILL', NULL, 'NEW', ?, ?, ?)
            RETURNING id, created_at
            """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.setLong(3, tableSessionId)
            statement.setLong(4, createdByUserId)
            statement.setLong(5, orderId)
            statement.setLong(6, tabId)
            statement.setString(7, paymentMethod.name)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    CreatedGuestBillRequest(
                        id = rs.getLong("id"),
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                        status = StaffCallStatus.NEW,
                        paymentMethod = paymentMethod,
                        alreadyActive = false,
                    )
                } else {
                    throw DatabaseUnavailableException()
                }
            }
        }
    }

    private fun insertGuestBillRequestH2(
        connection: java.sql.Connection,
        venueId: Long,
        tableId: Long,
        tableSessionId: Long,
        tabId: Long,
        orderId: Long,
        createdByUserId: Long,
        paymentMethod: BillPaymentMethod,
    ): CreatedGuestBillRequest {
        val sql =
            """
            INSERT INTO staff_calls (
                venue_id, table_id, table_session_id, created_by_user_id, reason, comment, status,
                order_id, tab_id, payment_method
            )
            VALUES (?, ?, ?, ?, 'BILL', NULL, 'NEW', ?, ?, ?)
            """.trimIndent()
        return connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.setLong(3, tableSessionId)
            statement.setLong(4, createdByUserId)
            statement.setLong(5, orderId)
            statement.setLong(6, tabId)
            statement.setString(7, paymentMethod.name)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (!keys.next()) {
                    throw DatabaseUnavailableException()
                }
                val id = keys.getLong(1)
                val createdAt =
                    connection.prepareStatement("SELECT created_at FROM staff_calls WHERE id = ?").use { select ->
                        select.setLong(1, id)
                        select.executeQuery().use { rs ->
                            if (rs.next()) {
                                rs.getTimestamp("created_at")?.toInstant() ?: Instant.now()
                            } else {
                                Instant.now()
                            }
                        }
                    }
                CreatedGuestBillRequest(
                    id = id,
                    createdAt = createdAt,
                    status = StaffCallStatus.NEW,
                    paymentMethod = paymentMethod,
                    alreadyActive = false,
                )
            }
        }
    }

    private fun ensureUserExists(
        connection: java.sql.Connection,
        userId: Long,
        isH2: Boolean,
    ) {
        val sql =
            if (isH2) {
                """
                MERGE INTO users (telegram_user_id)
                KEY (telegram_user_id)
                VALUES (?)
                """.trimIndent()
            } else {
                """
                INSERT INTO users (telegram_user_id)
                VALUES (?)
                ON CONFLICT (telegram_user_id) DO NOTHING
                """.trimIndent()
            }
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, userId)
            statement.executeUpdate()
        }
    }

    suspend fun ackStaffCall(
        venueId: Long,
        staffCallId: Long,
        actorUserId: Long,
    ): StaffCallStatusUpdateResult? =
        updateStaffCallStatus(
            venueId = venueId,
            staffCallId = staffCallId,
            expectedCurrentStatus = StaffCallStatus.NEW,
            nextStatus = StaffCallStatus.ACK,
            actorUserId = actorUserId,
        )

    suspend fun doneStaffCall(
        venueId: Long,
        staffCallId: Long,
        actorUserId: Long,
    ): StaffCallStatusUpdateResult? =
        updateStaffCallStatus(
            venueId = venueId,
            staffCallId = staffCallId,
            expectedCurrentStatus = StaffCallStatus.ACK,
            nextStatus = StaffCallStatus.DONE,
            actorUserId = actorUserId,
        )

    private suspend fun updateStaffCallStatus(
        venueId: Long,
        staffCallId: Long,
        expectedCurrentStatus: StaffCallStatus,
        nextStatus: StaffCallStatus,
        actorUserId: Long,
    ): StaffCallStatusUpdateResult? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    try {
                        connection.autoCommit = false
                        val forUpdateClause =
                            if (connection.metaData.databaseProductName.contains("H2", ignoreCase = true)) {
                                "FOR UPDATE"
                            } else {
                                "FOR UPDATE OF sc"
                            }
                        val current =
                            connection.prepareStatement(
                                """
                                SELECT sc.status,
                                       sc.reason,
                                       sc.comment,
                                       sc.order_id,
                                       sc.tab_id,
                                       sc.payment_method,
                                       o.display_number,
                                       t.type AS tab_type,
                                       vt.table_number,
                                       u.guest_display_name
                                FROM staff_calls sc
                                JOIN venue_tables vt ON vt.id = sc.table_id
                                LEFT JOIN orders o ON o.id = sc.order_id
                                LEFT JOIN tab t ON t.id = sc.tab_id
                                LEFT JOIN users u ON u.telegram_user_id = sc.created_by_user_id
                                WHERE sc.id = ?
                                  AND sc.venue_id = ?
                                $forUpdateClause
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, staffCallId)
                                statement.setLong(2, venueId)
                                statement.executeQuery().use { rs ->
                                    if (!rs.next()) {
                                        return@use null
                                    }
                                    val status = StaffCallStatus.fromDb(rs.getString("status")) ?: return@use null
                                    val reason =
                                        runCatching { StaffCallReason.valueOf(rs.getString("reason")) }
                                            .getOrDefault(StaffCallReason.OTHER)
                                    StaffCallStatusUpdateResult(
                                        staffCallId = staffCallId,
                                        status = status,
                                        applied = false,
                                        tableNumber = rs.getInt("table_number"),
                                        reason = reason,
                                        comment = rs.getString("comment"),
                                        orderId = rs.getNullableLong("order_id"),
                                        tabId = rs.getNullableLong("tab_id"),
                                        paymentMethod = rs.getString("payment_method")?.toBillPaymentMethod(),
                                        orderDisplayLabel =
                                            orderDisplayLabel(
                                                displayNumber = rs.getNullableInt("display_number"),
                                                orderId = rs.getNullableLong("order_id"),
                                            ),
                                        tabDisplayLabel = tabDisplayLabel(rs.getString("tab_type")),
                                        guestDisplayName = rs.getString("guest_display_name"),
                                    )
                                }
                            } ?: run {
                                runCatching { connection.rollback() }
                                return@use null
                            }
                        if (current.status != expectedCurrentStatus) {
                            runCatching { connection.rollback() }
                            return@use current
                        }
                        val updated =
                            connection.prepareStatement(
                                """
                                UPDATE staff_calls
                                SET status = ?
                                WHERE id = ?
                                  AND venue_id = ?
                                  AND status = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, nextStatus.dbValue)
                                statement.setLong(2, staffCallId)
                                statement.setLong(3, venueId)
                                statement.setString(4, expectedCurrentStatus.dbValue)
                                statement.executeUpdate()
                            }
                        if (updated != 1) {
                            runCatching { connection.rollback() }
                            return@use current
                        }
                        connection.commit()
                        current.copy(status = nextStatus, applied = true)
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw DatabaseUnavailableException()
                    } catch (e: Exception) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = true }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }
}

data class CreatedStaffCall(
    val id: Long,
    val createdAt: Instant,
)

data class CreatedGuestBillRequest(
    val id: Long,
    val createdAt: Instant,
    val status: StaffCallStatus,
    val paymentMethod: BillPaymentMethod,
    val alreadyActive: Boolean,
)

data class StaffCallQueueItem(
    val id: Long,
    val tableId: Long,
    val tableNumber: Int,
    val reason: String,
    val comment: String?,
    val status: String,
    val createdAt: Instant,
    val orderId: Long? = null,
    val tabId: Long? = null,
    val paymentMethod: BillPaymentMethod? = null,
    val paymentMethodLabel: String? = paymentMethod?.let { billPaymentMethodLabel(it) },
    val orderDisplayLabel: String? = null,
    val tabDisplayLabel: String? = null,
    val guestDisplayName: String? = null,
)

data class StaffOrderActivityItem(
    val staffCallId: Long,
    val reason: StaffCallReason,
    val comment: String?,
    val status: StaffCallStatus,
    val createdAt: Instant,
    val tabId: Long? = null,
    val paymentMethod: BillPaymentMethod? = null,
    val tabDisplayLabel: String? = null,
    val guestDisplayName: String? = null,
)

enum class StaffCallStatus(
    val dbValue: String,
) {
    NEW("NEW"),
    ACK("ACK"),
    DONE("DONE"),
    CANCELLED("CANCELLED"),
    ;

    companion object {
        fun fromDb(raw: String?): StaffCallStatus? =
            values().firstOrNull { it.dbValue.equals(raw?.trim(), ignoreCase = true) }
    }
}

data class StaffCallStatusUpdateResult(
    val staffCallId: Long,
    val status: StaffCallStatus,
    val applied: Boolean,
    val tableNumber: Int,
    val reason: StaffCallReason,
    val comment: String?,
    val orderId: Long? = null,
    val tabId: Long? = null,
    val paymentMethod: BillPaymentMethod? = null,
    val orderDisplayLabel: String? = null,
    val tabDisplayLabel: String? = null,
    val guestDisplayName: String? = null,
)

data class CompletedStaffCallStatusUpdate(
    val fromStatus: StaffCallStatus,
    val result: StaffCallStatusUpdateResult,
)

private fun ResultSet.toStaffCallQueueItem(): StaffCallQueueItem {
    val orderId = getNullableLong("order_id")
    val tabType = getString("tab_type")
    val paymentMethod = getString("payment_method")?.toBillPaymentMethod()
    return StaffCallQueueItem(
        id = getLong("id"),
        tableId = getLong("table_id"),
        tableNumber = getInt("table_number"),
        reason = getString("reason"),
        comment = getString("comment"),
        status = getString("status"),
        createdAt = getTimestamp("created_at").toInstant(),
        orderId = orderId,
        tabId = getNullableLong("tab_id"),
        paymentMethod = paymentMethod,
        orderDisplayLabel =
            orderDisplayLabel(
                displayNumber = getNullableInt("display_number"),
                orderId = orderId,
            ),
        tabDisplayLabel = tabDisplayLabel(tabType),
        guestDisplayName = getString("guest_display_name"),
    )
}

private fun ResultSet.toStaffOrderActivityItem(): StaffOrderActivityItem {
    val reason =
        runCatching { StaffCallReason.valueOf(getString("reason")) }
            .getOrDefault(StaffCallReason.OTHER)
    val status = StaffCallStatus.fromDb(getString("status")) ?: StaffCallStatus.NEW
    return StaffOrderActivityItem(
        staffCallId = getLong("id"),
        reason = reason,
        comment = getString("comment"),
        status = status,
        createdAt = getTimestamp("created_at").toInstant(),
        tabId = getNullableLong("tab_id"),
        paymentMethod = getString("payment_method")?.toBillPaymentMethod(),
        tabDisplayLabel = tabDisplayLabel(getString("tab_type")),
        guestDisplayName = getString("guest_display_name"),
    )
}

private fun ResultSet.getNullableLong(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

private fun ResultSet.getNullableInt(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}

private fun String.toBillPaymentMethod(): BillPaymentMethod? =
    runCatching { BillPaymentMethod.valueOf(trim().uppercase(Locale.ROOT)) }.getOrNull()

private fun orderDisplayLabel(
    displayNumber: Int?,
    orderId: Long?,
): String? =
    when {
        displayNumber != null -> "Заказ №$displayNumber"
        orderId != null -> "Заказ #$orderId"
        else -> null
    }

private fun tabDisplayLabel(tabType: String?): String? =
    when (tabType?.uppercase(Locale.ROOT)) {
        "PERSONAL" -> "Личный счёт"
        "SHARED" -> "Общий счёт"
        else -> null
    }
