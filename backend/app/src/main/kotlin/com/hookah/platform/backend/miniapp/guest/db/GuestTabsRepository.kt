package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

data class TableSessionContext(
    val id: Long,
    val venueId: Long,
)

data class GuestTabModel(
    val id: Long,
    val venueId: Long,
    val tableSessionId: Long,
    val type: String,
    val ownerUserId: Long?,
    val status: String,
)

class GuestTabsRepository(private val dataSource: DataSource?) {
    suspend fun findActiveTableSession(tableSessionId: Long): TableSessionContext? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT id, venue_id
                        FROM table_sessions
                        WHERE id = ?
                          AND status = 'ACTIVE'
                          AND ended_at IS NULL
                          AND expires_at > now()
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, tableSessionId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                TableSessionContext(id = rs.getLong("id"), venueId = rs.getLong("venue_id"))
                            } else {
                                null
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun ensurePersonalTab(
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
    ): GuestTabModel {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val existing = findPersonalTabForUpdate(connection, venueId, tableSessionId, userId)
                        if (existing != null) {
                            ensureMember(connection, existing.id, userId, "OWNER")
                            connection.commit()
                            return@use existing
                        }
                        ensureUserExists(connection, userId)
                        val tabId = insertTab(connection, venueId, tableSessionId, "PERSONAL", userId)
                        ensureMember(connection, tabId, userId, "OWNER")
                        connection.commit()
                        GuestTabModel(
                            id = tabId,
                            venueId = venueId,
                            tableSessionId = tableSessionId,
                            type = "PERSONAL",
                            ownerUserId = userId,
                            status = "ACTIVE",
                        )
                    } catch (e: SQLException) {
                        connection.rollback()
                        if (e.sqlState == "23505") {
                            findPersonalTabInNewConnection(ds, venueId, tableSessionId, userId)?.let { return@use it }
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

    suspend fun listTabsForUser(
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
    ): List<GuestTabModel> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT t.id, t.venue_id, t.table_session_id, t.type, t.owner_user_id, t.status
                        FROM tab t
                        JOIN tab_member tm ON tm.tab_id = t.id
                        WHERE t.venue_id = ?
                          AND t.table_session_id = ?
                          AND tm.user_id = ?
                          AND t.status = 'ACTIVE'
                        ORDER BY t.id
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, tableSessionId)
                        statement.setLong(3, userId)
                        statement.executeQuery().use { rs ->
                            val tabs = mutableListOf<GuestTabModel>()
                            while (rs.next()) {
                                tabs.add(
                                    GuestTabModel(
                                        id = rs.getLong("id"),
                                        venueId = rs.getLong("venue_id"),
                                        tableSessionId = rs.getLong("table_session_id"),
                                        type = rs.getString("type"),
                                        ownerUserId = rs.getLong("owner_user_id").takeIf { !rs.wasNull() },
                                        status = rs.getString("status"),
                                    ),
                                )
                            }
                            tabs
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun createSharedTab(
        venueId: Long,
        tableSessionId: Long,
        ownerUserId: Long,
    ): GuestTabModel {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        ensureUserExists(connection, ownerUserId)
                        val tabId = insertTab(connection, venueId, tableSessionId, "SHARED", ownerUserId)
                        ensureMember(connection, tabId, ownerUserId, "OWNER")
                        connection.commit()
                        GuestTabModel(
                            id = tabId,
                            venueId = venueId,
                            tableSessionId = tableSessionId,
                            type = "SHARED",
                            ownerUserId = ownerUserId,
                            status = "ACTIVE",
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

    suspend fun createInvite(
        tabId: Long,
        venueId: Long,
        tableSessionId: Long,
        createdBy: Long,
        token: String,
        expiresAt: Instant,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val canInvite = isOwnerMember(connection, tabId, venueId, tableSessionId, createdBy)
                        if (!canInvite) {
                            connection.rollback()
                            return@use false
                        }
                        ensureUserExists(connection, createdBy)
                        connection.prepareStatement(
                            """
                            INSERT INTO tab_invite (tab_id, token, expires_at, created_by)
                            VALUES (?, ?, ?, ?)
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, tabId)
                            statement.setString(2, token)
                            statement.setTimestamp(3, Timestamp.from(expiresAt))
                            statement.setLong(4, createdBy)
                            statement.executeUpdate()
                        }
                        connection.commit()
                        true
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

    suspend fun joinByInvite(
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
        token: String,
    ): GuestTabModel? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val tab =
                            findInviteTabForUpdate(connection, venueId, tableSessionId, token) ?: run {
                                connection.rollback()
                                return@use null
                            }
                        ensureUserExists(connection, userId)
                        ensureMember(connection, tab.id, userId, "MEMBER")
                        connection.commit()
                        tab
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

    suspend fun isTabMember(
        tabId: Long,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT 1
                        FROM tab t
                        JOIN tab_member tm ON tm.tab_id = t.id
                        WHERE t.id = ?
                          AND t.venue_id = ?
                          AND t.table_session_id = ?
                          AND t.status = 'ACTIVE'
                          AND tm.user_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, tabId)
                        statement.setLong(2, venueId)
                        statement.setLong(3, tableSessionId)
                        statement.setLong(4, userId)
                        statement.executeQuery().use { it.next() }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun insertTab(
        connection: Connection,
        venueId: Long,
        tableSessionId: Long,
        type: String,
        ownerUserId: Long,
    ): Long {
        return connection.prepareStatement(
            """
            INSERT INTO tab (venue_id, table_session_id, type, owner_user_id, status)
            VALUES (?, ?, ?, ?, 'ACTIVE')
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableSessionId)
            statement.setString(3, type)
            statement.setLong(4, ownerUserId)
            statement.executeUpdate()
            statement.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else error("Failed to create tab") }
        }
    }

    private fun findPersonalTabForUpdate(
        connection: Connection,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
    ): GuestTabModel? {
        return connection.prepareStatement(
            """
            SELECT id, venue_id, table_session_id, type, owner_user_id, status
            FROM tab
            WHERE venue_id = ?
              AND table_session_id = ?
              AND type = 'PERSONAL'
              AND owner_user_id = ?
              AND status = 'ACTIVE'
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableSessionId)
            statement.setLong(3, userId)
            statement.executeQuery().use { rs -> if (rs.next()) mapTab(rs) else null }
        }
    }

    private fun findPersonalTabInNewConnection(
        dataSource: DataSource,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
    ): GuestTabModel? {
        return dataSource.connection.use { connection ->
            findPersonalTabForUpdate(connection, venueId, tableSessionId, userId)
        }
    }

    private fun findInviteTabForUpdate(
        connection: Connection,
        venueId: Long,
        tableSessionId: Long,
        token: String,
    ): GuestTabModel? {
        return connection.prepareStatement(
            """
            SELECT t.id, t.venue_id, t.table_session_id, t.type, t.owner_user_id, t.status
            FROM tab_invite ti
            JOIN tab t ON t.id = ti.tab_id
            WHERE ti.token = ?
              AND ti.expires_at > now()
              AND t.venue_id = ?
              AND t.table_session_id = ?
              AND t.type = 'SHARED'
              AND t.status = 'ACTIVE'
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, token)
            statement.setLong(2, venueId)
            statement.setLong(3, tableSessionId)
            statement.executeQuery().use { rs -> if (rs.next()) mapTab(rs) else null }
        }
    }

    private fun ensureMember(
        connection: Connection,
        tabId: Long,
        userId: Long,
        role: String,
    ) {
        val exists =
            connection.prepareStatement(
                """
                SELECT 1 FROM tab_member WHERE tab_id = ? AND user_id = ?
                """.trimIndent(),
            ).use { check ->
                check.setLong(1, tabId)
                check.setLong(2, userId)
                check.executeQuery().use { it.next() }
            }
        if (exists) {
            return
        }
        connection.prepareStatement(
            """
            INSERT INTO tab_member (tab_id, user_id, role)
            VALUES (?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, tabId)
            statement.setLong(2, userId)
            statement.setString(3, role)
            statement.executeUpdate()
        }
    }

    private fun isOwnerMember(
        connection: Connection,
        tabId: Long,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
    ): Boolean {
        return connection.prepareStatement(
            """
            SELECT 1
            FROM tab t
            JOIN tab_member tm ON tm.tab_id = t.id
            WHERE t.id = ?
              AND t.venue_id = ?
              AND t.table_session_id = ?
              AND t.type = 'SHARED'
              AND t.status = 'ACTIVE'
              AND tm.user_id = ?
              AND tm.role = 'OWNER'
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, tabId)
            statement.setLong(2, venueId)
            statement.setLong(3, tableSessionId)
            statement.setLong(4, userId)
            statement.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun ensureUserExists(
        connection: Connection,
        userId: Long,
    ) {
        connection.prepareStatement(
            """
            SELECT 1 FROM users WHERE telegram_user_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    return
                }
            }
        }
        connection.prepareStatement(
            """
            INSERT INTO users (telegram_user_id)
            VALUES (?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeUpdate()
        }
    }

    private fun mapTab(rs: java.sql.ResultSet): GuestTabModel =
        GuestTabModel(
            id = rs.getLong("id"),
            venueId = rs.getLong("venue_id"),
            tableSessionId = rs.getLong("table_session_id"),
            type = rs.getString("type"),
            ownerUserId = rs.getLong("owner_user_id").takeIf { !rs.wasNull() },
            status = rs.getString("status"),
        )
}
