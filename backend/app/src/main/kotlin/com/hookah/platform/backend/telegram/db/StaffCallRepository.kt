package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.telegram.StaffCallReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
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
                               u.guest_display_name
                        FROM staff_calls sc
                        JOIN venue_tables vt ON vt.id = sc.table_id
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
                                result.add(
                                    StaffCallQueueItem(
                                        id = rs.getLong("id"),
                                        tableId = rs.getLong("table_id"),
                                        tableNumber = rs.getInt("table_number"),
                                        reason = rs.getString("reason"),
                                        comment = rs.getString("comment"),
                                        status = rs.getString("status"),
                                        createdAt = rs.getTimestamp("created_at").toInstant(),
                                        guestDisplayName = rs.getString("guest_display_name"),
                                    ),
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
                               u.guest_display_name
                        FROM staff_calls sc
                        JOIN venue_tables vt ON vt.id = sc.table_id
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
                                result.add(
                                    StaffCallQueueItem(
                                        id = rs.getLong("id"),
                                        tableId = rs.getLong("table_id"),
                                        tableNumber = rs.getInt("table_number"),
                                        reason = rs.getString("reason"),
                                        comment = rs.getString("comment"),
                                        status = rs.getString("status"),
                                        createdAt = rs.getTimestamp("created_at").toInstant(),
                                        guestDisplayName = rs.getString("guest_display_name"),
                                    ),
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

    suspend fun createStaffCall(
        venueId: Long,
        tableId: Long,
        createdByUserId: Long?,
        reason: StaffCallReason,
        comment: String?,
        tableSessionId: Long? = null,
    ): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val sql =
                    """
                    INSERT INTO staff_calls (venue_id, table_id, table_session_id, created_by_user_id, reason, comment, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'NEW')
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
                            INSERT INTO staff_calls (venue_id, table_id, table_session_id, created_by_user_id, reason, comment, status)
                            VALUES (?, ?, ?, ?, ?, ?, 'NEW')
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
                        INSERT INTO staff_calls (venue_id, table_id, table_session_id, created_by_user_id, reason, comment, status)
                        VALUES (?, ?, ?, ?, ?, ?, 'NEW')
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
                                       vt.table_number,
                                       u.guest_display_name
                                FROM staff_calls sc
                                JOIN venue_tables vt ON vt.id = sc.table_id
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

data class StaffCallQueueItem(
    val id: Long,
    val tableId: Long,
    val tableNumber: Int,
    val reason: String,
    val comment: String?,
    val status: String,
    val createdAt: Instant,
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
    val guestDisplayName: String? = null,
)
