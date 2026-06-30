package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.analytics.AnalyticsEventRecord
import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.analytics.analyticsCorrelationPayload
import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

enum class TableSessionStatus {
    ACTIVE,
    ENDED,
}

data class TableSessionRecord(
    val id: Long,
    val venueId: Long,
    val tableId: Long,
    val startedAt: Instant,
    val lastActivityAt: Instant,
    val expiresAt: Instant,
    val endedAt: Instant?,
    val status: TableSessionStatus,
)

enum class TableSessionEndBlockedReason {
    ACTIVE_ORDER,
    ACTIVE_STAFF_CALL,
}

data class EndUserTableSessionResult(
    val ended: Boolean,
    val tableSessionId: Long,
    val blockedReason: TableSessionEndBlockedReason?,
)

class TableSessionRepository(
    private val dataSource: DataSource?,
    private val analyticsEventRepository: AnalyticsEventRepository? = null,
) {
    suspend fun resolveActiveSession(
        venueId: Long,
        tableId: Long,
        ttl: Duration,
        now: Instant = Instant.now(),
    ): TableSessionRecord {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val nextExpiry = now.plus(ttl)
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        if (!lockTable(connection, venueId, tableId)) {
                            connection.rollback()
                            throw DatabaseUnavailableException()
                        }

                        val existingId = findActiveSessionForUpdate(connection, venueId, tableId, now)
                        val sessionId =
                            if (existingId != null) {
                                connection.prepareStatement(
                                    """
                                    UPDATE table_sessions
                                    SET last_activity_at = ?,
                                        expires_at = CASE WHEN expires_at > ? THEN expires_at ELSE ? END
                                    WHERE id = ?
                                    """.trimIndent(),
                                ).use { statement ->
                                    statement.setTimestamp(1, Timestamp.from(now))
                                    statement.setTimestamp(2, Timestamp.from(nextExpiry))
                                    statement.setTimestamp(3, Timestamp.from(nextExpiry))
                                    statement.setLong(4, existingId)
                                    statement.executeUpdate()
                                }
                                existingId
                            } else {
                                val createdSessionId =
                                    insertSession(
                                        connection = connection,
                                        venueId = venueId,
                                        tableId = tableId,
                                        now = now,
                                        expiresAt = nextExpiry,
                                    )
                                analyticsEventRepository?.append(
                                    connection = connection,
                                    event =
                                        AnalyticsEventRecord(
                                            eventType = "table_session_started",
                                            payload =
                                                analyticsCorrelationPayload(
                                                    venueId = venueId,
                                                    tableId = tableId,
                                                    tableSessionId = createdSessionId,
                                                ),
                                            venueId = venueId,
                                            tableId = tableId,
                                            tableSessionId = createdSessionId,
                                            idempotencyKey = "table_session_started:$createdSessionId",
                                        ),
                                )
                                createdSessionId
                            }

                        connection.commit()
                        loadById(connection, sessionId) ?: throw DatabaseUnavailableException()
                    } catch (e: SQLException) {
                        connection.rollback()
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

    suspend fun touchActiveSession(
        tableSessionId: Long,
        venueId: Long,
        tableId: Long,
        ttl: Duration,
        now: Instant = Instant.now(),
    ): TableSessionRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val nextExpiry = now.plus(ttl)
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE table_sessions
                            SET last_activity_at = ?,
                                expires_at = CASE WHEN expires_at > ? THEN expires_at ELSE ? END
                            WHERE id = ?
                              AND venue_id = ?
                              AND table_id = ?
                              AND status = 'ACTIVE'
                              AND ended_at IS NULL
                              AND expires_at > ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setTimestamp(1, Timestamp.from(now))
                            statement.setTimestamp(2, Timestamp.from(nextExpiry))
                            statement.setTimestamp(3, Timestamp.from(nextExpiry))
                            statement.setLong(4, tableSessionId)
                            statement.setLong(5, venueId)
                            statement.setLong(6, tableId)
                            statement.setTimestamp(7, Timestamp.from(now))
                            statement.executeUpdate()
                        }
                    if (updated <= 0) {
                        return@use null
                    }
                    loadById(connection, tableSessionId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun extendActiveSessionUntil(
        tableSessionId: Long,
        venueId: Long,
        extendUntil: Instant,
        now: Instant = Instant.now(),
    ): TableSessionRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE table_sessions
                            SET last_activity_at = ?,
                                expires_at = CASE WHEN expires_at > ? THEN expires_at ELSE ? END
                            WHERE id = ?
                              AND venue_id = ?
                              AND status = 'ACTIVE'
                              AND ended_at IS NULL
                              AND expires_at > ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setTimestamp(1, Timestamp.from(now))
                            statement.setTimestamp(2, Timestamp.from(extendUntil))
                            statement.setTimestamp(3, Timestamp.from(extendUntil))
                            statement.setLong(4, tableSessionId)
                            statement.setLong(5, venueId)
                            statement.setTimestamp(6, Timestamp.from(now))
                            statement.executeUpdate()
                        }
                    if (updated <= 0) {
                        return@use null
                    }
                    loadById(connection, tableSessionId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun closeExpiredSessions(now: Instant = Instant.now()): Int {
        val ds = dataSource ?: return 0
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        UPDATE table_sessions
                        SET status = 'ENDED',
                            ended_at = ?
                        WHERE status = 'ACTIVE'
                          AND ended_at IS NULL
                          AND expires_at <= ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(now))
                        statement.setTimestamp(2, Timestamp.from(now))
                        statement.executeUpdate()
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun closeSession(
        tableSessionId: Long,
        venueId: Long,
        now: Instant = Instant.now(),
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        UPDATE table_sessions
                        SET status = 'ENDED',
                            ended_at = ?
                        WHERE id = ?
                          AND venue_id = ?
                          AND status = 'ACTIVE'
                          AND ended_at IS NULL
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(now))
                        statement.setLong(2, tableSessionId)
                        statement.setLong(3, venueId)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findSessionForTable(
        tableSessionId: Long,
        venueId: Long,
        tableId: Long,
    ): TableSessionRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT id, venue_id, table_id, started_at, last_activity_at, expires_at, ended_at, status
                        FROM table_sessions
                        WHERE id = ?
                          AND venue_id = ?
                          AND table_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, tableSessionId)
                        statement.setLong(2, venueId)
                        statement.setLong(3, tableId)
                        statement.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun endUserTableSession(
        userId: Long,
        tableToken: String,
        venueId: Long,
        tableId: Long,
        tableSessionId: Long,
        now: Instant = Instant.now(),
    ): EndUserTableSessionResult? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val activeUserContext =
                            isActiveUserTableContext(
                                connection = connection,
                                userId = userId,
                                tableToken = tableToken,
                                venueId = venueId,
                                tableId = tableId,
                                tableSessionId = tableSessionId,
                                now = now,
                            )
                        if (!activeUserContext) {
                            connection.rollback()
                            return@use null
                        }
                        val blockedReason =
                            when {
                                hasActiveUserOrderObligation(
                                    connection = connection,
                                    userId = userId,
                                    venueId = venueId,
                                    tableId = tableId,
                                    tableSessionId = tableSessionId,
                                ) -> TableSessionEndBlockedReason.ACTIVE_ORDER
                                hasActiveUserStaffCall(
                                    connection = connection,
                                    userId = userId,
                                    venueId = venueId,
                                    tableId = tableId,
                                    tableSessionId = tableSessionId,
                                ) -> TableSessionEndBlockedReason.ACTIVE_STAFF_CALL
                                else -> null
                            }
                        if (blockedReason != null) {
                            connection.rollback()
                            return@use EndUserTableSessionResult(
                                ended = false,
                                tableSessionId = tableSessionId,
                                blockedReason = blockedReason,
                            )
                        }
                        recordUserExit(connection, userId, tableSessionId, now)
                        connection.commit()
                        EndUserTableSessionResult(
                            ended = true,
                            tableSessionId = tableSessionId,
                            blockedReason = null,
                        )
                    } catch (e: SQLException) {
                        connection.rollback()
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

    suspend fun clearUserExit(
        userId: Long,
        tableSessionId: Long,
    ) {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        DELETE FROM guest_table_session_exits
                        WHERE user_id = ?
                          AND table_session_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.setLong(2, tableSessionId)
                        statement.executeUpdate()
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun insertSession(
        connection: Connection,
        venueId: Long,
        tableId: Long,
        now: Instant,
        expiresAt: Instant,
    ): Long {
        return connection.prepareStatement(
            """
            INSERT INTO table_sessions (
                venue_id,
                table_id,
                started_at,
                last_activity_at,
                expires_at,
                ended_at,
                status
            )
            VALUES (?, ?, ?, ?, ?, NULL, 'ACTIVE')
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.setTimestamp(3, Timestamp.from(now))
            statement.setTimestamp(4, Timestamp.from(now))
            statement.setTimestamp(5, Timestamp.from(expiresAt))
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (keys.next()) {
                    keys.getLong(1)
                } else {
                    throw SQLException("Failed to create table session")
                }
            }
        }
    }

    private fun isActiveUserTableContext(
        connection: Connection,
        userId: Long,
        tableToken: String,
        venueId: Long,
        tableId: Long,
        tableSessionId: Long,
        now: Instant,
    ): Boolean {
        return connection.prepareStatement(
            """
            SELECT 1
            FROM table_sessions ts
            JOIN table_tokens tt ON tt.table_id = ts.table_id
            WHERE ts.id = ?
              AND ts.venue_id = ?
              AND ts.table_id = ?
              AND ts.status = 'ACTIVE'
              AND ts.ended_at IS NULL
              AND ts.expires_at > ?
              AND tt.token = ?
              AND tt.is_active = TRUE
              AND EXISTS (
                  SELECT 1
                  FROM tab t
                  JOIN tab_member tm ON tm.tab_id = t.id
                  WHERE t.venue_id = ts.venue_id
                    AND t.table_session_id = ts.id
                    AND t.status = 'ACTIVE'
                    AND tm.user_id = ?
              )
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, tableSessionId)
            statement.setLong(2, venueId)
            statement.setLong(3, tableId)
            statement.setTimestamp(4, Timestamp.from(now))
            statement.setString(5, tableToken)
            statement.setLong(6, userId)
            statement.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun hasActiveUserOrderObligation(
        connection: Connection,
        userId: Long,
        venueId: Long,
        tableId: Long,
        tableSessionId: Long,
    ): Boolean {
        return connection.prepareStatement(
            """
            SELECT 1
            FROM orders o
            JOIN order_batches ob ON ob.order_id = o.id
            LEFT JOIN tab t
              ON t.id = ob.tab_id
             AND t.table_session_id = o.table_session_id
             AND t.status = 'ACTIVE'
            LEFT JOIN tab_member tm
              ON tm.tab_id = t.id
             AND tm.user_id = ?
            WHERE o.venue_id = ?
              AND o.table_id = ?
              AND o.table_session_id = ?
              AND o.status = 'ACTIVE'
              AND ob.status <> 'REJECTED'
              AND (
                  tm.user_id IS NOT NULL
                  OR ob.author_user_id = ?
              )
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, venueId)
            statement.setLong(3, tableId)
            statement.setLong(4, tableSessionId)
            statement.setLong(5, userId)
            statement.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun hasActiveUserStaffCall(
        connection: Connection,
        userId: Long,
        venueId: Long,
        tableId: Long,
        tableSessionId: Long,
    ): Boolean {
        return connection.prepareStatement(
            """
            SELECT 1
            FROM staff_calls
            WHERE venue_id = ?
              AND table_id = ?
              AND table_session_id = ?
              AND created_by_user_id = ?
              AND status IN ('NEW', 'ACK')
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.setLong(3, tableSessionId)
            statement.setLong(4, userId)
            statement.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun recordUserExit(
        connection: Connection,
        userId: Long,
        tableSessionId: Long,
        now: Instant,
    ) {
        connection.prepareStatement(
            """
            DELETE FROM guest_table_session_exits
            WHERE user_id = ?
              AND table_session_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, tableSessionId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO guest_table_session_exits (user_id, table_session_id, exited_at)
            VALUES (?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, tableSessionId)
            statement.setTimestamp(3, Timestamp.from(now))
            statement.executeUpdate()
        }
    }

    private fun loadById(
        connection: Connection,
        id: Long,
    ): TableSessionRecord? {
        return connection.prepareStatement(
            """
            SELECT id, venue_id, table_id, started_at, last_activity_at, expires_at, ended_at, status
            FROM table_sessions
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
        }
    }

    private fun lockTable(
        connection: Connection,
        venueId: Long,
        tableId: Long,
    ): Boolean {
        return connection.prepareStatement(
            "SELECT id FROM venue_tables WHERE id = ? AND venue_id = ? FOR UPDATE",
        ).use { statement ->
            statement.setLong(1, tableId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun findActiveSessionForUpdate(
        connection: Connection,
        venueId: Long,
        tableId: Long,
        now: Instant,
    ): Long? {
        return connection.prepareStatement(
            """
            SELECT id
            FROM table_sessions
            WHERE venue_id = ?
              AND table_id = ?
              AND status = 'ACTIVE'
              AND ended_at IS NULL
              AND expires_at > ?
            ORDER BY id DESC
            LIMIT 1
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.setTimestamp(3, Timestamp.from(now))
            statement.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else null }
        }
    }

    private fun java.sql.ResultSet.toRecord(): TableSessionRecord =
        TableSessionRecord(
            id = getLong("id"),
            venueId = getLong("venue_id"),
            tableId = getLong("table_id"),
            startedAt = getTimestamp("started_at").toInstant(),
            lastActivityAt = getTimestamp("last_activity_at").toInstant(),
            expiresAt = getTimestamp("expires_at").toInstant(),
            endedAt = getTimestamp("ended_at")?.toInstant(),
            status = TableSessionStatus.valueOf(getString("status")),
        )
}
