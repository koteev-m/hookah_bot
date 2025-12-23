package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.telegram.StaffCallReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

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
}
