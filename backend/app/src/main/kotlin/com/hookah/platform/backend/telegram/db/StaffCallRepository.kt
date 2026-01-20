package com.hookah.platform.backend.telegram.db

import java.time.Instant
import java.sql.SQLException
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.telegram.StaffCallReason

class StaffCallRepository(private val dataSource: DataSource?) {
    suspend fun createStaffCall(
        venueId: Long,
        tableId: Long,
        createdByUserId: Long?,
        reason: StaffCallReason,
        comment: String?
    ): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val sql = """
                    INSERT INTO staff_calls (venue_id, table_id, created_by_user_id, reason, comment, status)
                    VALUES (?, ?, ?, ?, ?, 'NEW')
                    RETURNING id
                """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, tableId)
                    if (createdByUserId != null) statement.setLong(3, createdByUserId)
                    else statement.setNull(3, java.sql.Types.BIGINT)
                    statement.setString(4, reason.name)
                    statement.setString(5, comment)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                }
            }
        }
    }

    suspend fun createGuestStaffCall(
        venueId: Long,
        tableId: Long,
        reason: StaffCallReason,
        comment: String?
    ): CreatedStaffCall {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    if (connection.metaData.databaseProductName.contains("H2", ignoreCase = true)) {
                        val sql = """
                            INSERT INTO staff_calls (venue_id, table_id, created_by_user_id, reason, comment, status)
                            VALUES (?, ?, NULL, ?, ?, 'NEW')
                        """.trimIndent()
                        connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { statement ->
                            statement.setLong(1, venueId)
                            statement.setLong(2, tableId)
                            statement.setString(3, reason.name)
                            statement.setString(4, comment)
                            statement.executeUpdate()
                            statement.generatedKeys.use { keys ->
                                if (keys.next()) {
                                    val id = keys.getLong(1)
                                    val createdAt = connection.prepareStatement(
                                        "SELECT created_at FROM staff_calls WHERE id = ?"
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

                    val sql = """
                        INSERT INTO staff_calls (venue_id, table_id, created_by_user_id, reason, comment, status)
                        VALUES (?, ?, NULL, ?, ?, 'NEW')
                        RETURNING id, created_at
                    """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, tableId)
                        statement.setString(3, reason.name)
                        statement.setString(4, comment)
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
}

data class CreatedStaffCall(
    val id: Long,
    val createdAt: Instant
)
