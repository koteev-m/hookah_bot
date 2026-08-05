package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.telegram.TableContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

class TableTokenRepository(private val dataSource: DataSource?) {
    suspend fun resolve(token: String): TableContext? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    resolveActiveState(connection, token)
                        ?.takeIf { it.tokenActive && it.tableActive }
                        ?.context
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun resolveInactiveTable(token: String): TableContext? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    resolveActiveState(connection, token)
                        ?.takeIf { it.tokenActive && !it.tableActive }
                        ?.context
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    fun resolveActiveState(
        connection: Connection,
        token: String,
    ): TableTokenState? =
        connection.prepareStatement(
            """
            SELECT
                v.id AS venue_id,
                v.name AS venue_name,
                v.status AS venue_status,
                v.staff_chat_id,
                vt.id AS table_id,
                vt.table_number,
                vt.is_active AS table_active,
                tt.is_active AS token_active
            FROM table_tokens tt
            JOIN venue_tables vt ON vt.id = tt.table_id
            JOIN venues v ON v.id = vt.venue_id
            WHERE tt.token = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, token)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    return@use null
                }
                TableTokenState(
                    context =
                        TableContext(
                            venueId = rs.getLong("venue_id"),
                            venueName = rs.getString("venue_name"),
                            tableId = rs.getLong("table_id"),
                            tableNumber = rs.getInt("table_number"),
                            tableToken = token,
                            staffChatId = rs.getLong("staff_chat_id").takeIf { !rs.wasNull() },
                        ),
                    venueStatus = VenueStatus.fromDb(rs.getString("venue_status")),
                    tableActive = rs.getBoolean("table_active"),
                    tokenActive = rs.getBoolean("token_active"),
                )
            }
        }

    fun resolveForUpdate(
        connection: Connection,
        token: String,
        expectedVenueId: Long,
        expectedTableId: Long,
    ): TableTokenState? {
        val venue =
            connection.prepareStatement(
                """
                SELECT id, name, status, staff_chat_id
                FROM venues
                WHERE id = ?
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, expectedVenueId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return null
                    }
                    LockedVenue(
                        name = rs.getString("name"),
                        status = VenueStatus.fromDb(rs.getString("status")),
                        staffChatId = rs.getLong("staff_chat_id").takeIf { !rs.wasNull() },
                    )
                }
            }
        val table =
            connection.prepareStatement(
                """
                SELECT table_number, is_active
                FROM venue_tables
                WHERE id = ?
                  AND venue_id = ?
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, expectedTableId)
                statement.setLong(2, expectedVenueId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return null
                    }
                    LockedTable(
                        tableNumber = rs.getInt("table_number"),
                        active = rs.getBoolean("is_active"),
                    )
                }
            }
        val tokenState =
            connection.prepareStatement(
                """
                SELECT table_id, is_active
                FROM table_tokens
                WHERE token = ?
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, token)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return null
                    }
                    LockedToken(
                        tableId = rs.getLong("table_id"),
                        active = rs.getBoolean("is_active"),
                    )
                }
            }
        if (tokenState.tableId != expectedTableId) {
            return null
        }
        return TableTokenState(
            context =
                TableContext(
                    venueId = expectedVenueId,
                    venueName = venue.name,
                    tableId = expectedTableId,
                    tableNumber = table.tableNumber,
                    tableToken = token,
                    staffChatId = venue.staffChatId,
                ),
            venueStatus = venue.status,
            tableActive = table.active,
            tokenActive = tokenState.active,
        )
    }

    private data class LockedVenue(
        val name: String,
        val status: VenueStatus?,
        val staffChatId: Long?,
    )

    private data class LockedTable(
        val tableNumber: Int,
        val active: Boolean,
    )

    private data class LockedToken(
        val tableId: Long,
        val active: Boolean,
    )
}

data class TableTokenState(
    val context: TableContext,
    val venueStatus: VenueStatus?,
    val tableActive: Boolean,
    val tokenActive: Boolean,
)
