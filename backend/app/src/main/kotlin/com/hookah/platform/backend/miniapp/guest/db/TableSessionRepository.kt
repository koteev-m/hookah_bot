package com.hookah.platform.backend.miniapp.guest.db

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

class TableSessionRepository(private val dataSource: DataSource?) {
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
                                        expires_at = ?
                                    WHERE id = ?
                                    """.trimIndent(),
                                ).use { statement ->
                                    statement.setTimestamp(1, Timestamp.from(now))
                                    statement.setTimestamp(2, Timestamp.from(nextExpiry))
                                    statement.setLong(3, existingId)
                                    statement.executeUpdate()
                                }
                                existingId
                            } else {
                                insertSession(
                                    connection = connection,
                                    venueId = venueId,
                                    tableId = tableId,
                                    now = now,
                                    expiresAt = nextExpiry,
                                )
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
                                expires_at = ?
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
                            statement.setLong(3, tableSessionId)
                            statement.setLong(4, venueId)
                            statement.setLong(5, tableId)
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
