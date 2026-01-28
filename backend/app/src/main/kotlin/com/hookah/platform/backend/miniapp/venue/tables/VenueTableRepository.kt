package com.hookah.platform.backend.miniapp.venue.tables

import com.hookah.platform.backend.api.DatabaseUnavailableException
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VenueTableSummary(
    val tableId: Long,
    val tableNumber: Int,
    val isActive: Boolean,
    val activeTokenIssuedAt: Instant?
)

data class VenueTableCreated(
    val tableId: Long,
    val tableNumber: Int,
    val tokenIssuedAt: Instant
)

data class VenueTableToken(
    val tableId: Long,
    val tableNumber: Int,
    val token: String,
    val issuedAt: Instant
)

class VenueTableRepository(private val dataSource: DataSource?) {
    private val tokenGenerator = TableTokenGenerator()

    suspend fun listTables(venueId: Long): List<VenueTableSummary> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                            SELECT vt.id,
                                   vt.table_number,
                                   vt.is_active,
                                   tt.issued_at
                            FROM venue_tables vt
                            LEFT JOIN table_tokens tt
                              ON tt.table_id = vt.id AND tt.is_active = true
                            WHERE vt.venue_id = ?
                              AND vt.is_active = true
                            ORDER BY vt.table_number, vt.id
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<VenueTableSummary>()
                            while (rs.next()) {
                                result.add(
                                    VenueTableSummary(
                                        tableId = rs.getLong("id"),
                                        tableNumber = rs.getInt("table_number"),
                                        isActive = rs.getBoolean("is_active"),
                                        activeTokenIssuedAt = rs.getTimestamp("issued_at")?.toInstant()
                                    )
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

    suspend fun listTablesWithTokens(venueId: Long): List<VenueTableToken> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                            SELECT vt.id,
                                   vt.table_number,
                                   tt.token,
                                   tt.issued_at
                            FROM venue_tables vt
                            JOIN table_tokens tt
                              ON tt.table_id = vt.id AND tt.is_active = true
                            WHERE vt.venue_id = ?
                              AND vt.is_active = true
                            ORDER BY vt.table_number, vt.id
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<VenueTableToken>()
                            while (rs.next()) {
                                result.add(
                                    VenueTableToken(
                                        tableId = rs.getLong("id"),
                                        tableNumber = rs.getInt("table_number"),
                                        token = rs.getString("token"),
                                        issuedAt = rs.getTimestamp("issued_at").toInstant()
                                    )
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

    suspend fun ensureTablesAvailable(venueId: Long, startNumber: Int, count: Int) {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                            SELECT table_number
                            FROM venue_tables
                            WHERE venue_id = ?
                              AND table_number BETWEEN ? AND ?
                            LIMIT 1
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setInt(2, startNumber)
                        statement.setInt(3, startNumber + count - 1)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                throw TableNumberConflictException()
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findNextTableNumber(venueId: Long): Int {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                            SELECT COALESCE(MAX(table_number), 0) AS max_number
                            FROM venue_tables
                            WHERE venue_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                rs.getInt("max_number") + 1
                            } else {
                                1
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun batchCreateTables(venueId: Long, startNumber: Int, count: Int): List<VenueTableCreated> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val result = mutableListOf<VenueTableCreated>()
                        for (index in 0 until count) {
                            val tableNumber = startNumber + index
                            val tableId = insertTable(connection, venueId, tableNumber)
                            val issuedAt = Instant.now()
                            insertToken(connection, tableId, issuedAt)
                            result.add(
                                VenueTableCreated(
                                    tableId = tableId,
                                    tableNumber = tableNumber,
                                    tokenIssuedAt = issuedAt
                                )
                            )
                        }
                        connection.commit()
                        result
                    } catch (e: Exception) {
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

    suspend fun rotateToken(venueId: Long, tableId: Long): VenueTableCreated? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val tableNumber = loadTableNumber(connection, venueId, tableId, forUpdate = true) ?: return@use null
                        revokeActiveToken(connection, tableId)
                        val issuedAt = Instant.now()
                        insertToken(connection, tableId, issuedAt)
                        connection.commit()
                        VenueTableCreated(tableId = tableId, tableNumber = tableNumber, tokenIssuedAt = issuedAt)
                    } catch (e: Exception) {
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

    suspend fun rotateTokens(venueId: Long, tableIds: List<Long>): List<VenueTableCreated> {
        if (tableIds.isEmpty()) {
            return emptyList()
        }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val result = mutableListOf<VenueTableCreated>()
                        for (tableId in tableIds) {
                            val tableNumber = loadTableNumber(connection, venueId, tableId, forUpdate = true) ?: continue
                            revokeActiveToken(connection, tableId)
                            val issuedAt = Instant.now()
                            insertToken(connection, tableId, issuedAt)
                            result.add(
                                VenueTableCreated(
                                    tableId = tableId,
                                    tableNumber = tableNumber,
                                    tokenIssuedAt = issuedAt
                                )
                            )
                        }
                        connection.commit()
                        result
                    } catch (e: Exception) {
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

    suspend fun listTableIds(venueId: Long): List<Long> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                            SELECT id
                            FROM venue_tables
                            WHERE venue_id = ?
                              AND is_active = true
                            ORDER BY table_number, id
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<Long>()
                            while (rs.next()) {
                                result.add(rs.getLong("id"))
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

    private fun insertTable(connection: Connection, venueId: Long, tableNumber: Int): Long {
        return connection.prepareStatement(
            """
                INSERT INTO venue_tables (venue_id, table_number, is_active)
                VALUES (?, ?, true)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setInt(2, tableNumber)
            try {
                statement.executeUpdate()
            } catch (e: SQLException) {
                if (e.sqlState == "23505") {
                    throw TableNumberConflictException()
                }
                throw e
            }
            statement.generatedKeys.use { rs ->
                if (rs.next()) {
                    rs.getLong(1)
                } else {
                    error("Failed to insert table")
                }
            }
        }
    }

    private fun insertToken(connection: Connection, tableId: Long, issuedAt: Instant): String {
        repeat(5) {
            val token = tokenGenerator.generate()
            try {
                connection.prepareStatement(
                    """
                        INSERT INTO table_tokens (token, table_id, is_active, issued_at)
                        VALUES (?, ?, true, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, token)
                    statement.setLong(2, tableId)
                    statement.setTimestamp(3, Timestamp.from(issuedAt))
                    statement.executeUpdate()
                }
                return token
            } catch (e: SQLException) {
                if (e.sqlState != "23505") {
                    throw e
                }
            }
        }
        throw DatabaseUnavailableException()
    }

    private fun revokeActiveToken(connection: Connection, tableId: Long) {
        connection.prepareStatement(
            """
                UPDATE table_tokens
                SET is_active = false,
                    revoked_at = now()
                WHERE table_id = ? AND is_active = true
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, tableId)
            statement.executeUpdate()
        }
    }

    private fun loadTableNumber(
        connection: Connection,
        venueId: Long,
        tableId: Long,
        forUpdate: Boolean
    ): Int? {
        val sqlSuffix = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
                SELECT table_number
                FROM venue_tables
                WHERE id = ? AND venue_id = ? AND is_active = true$sqlSuffix
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, tableId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getInt("table_number") else null
            }
        }
    }
}

class TableNumberConflictException : RuntimeException()

private class TableTokenGenerator {
    private val secureRandom = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val tokenBytes = 32

    fun generate(): String {
        val buffer = ByteArray(tokenBytes)
        secureRandom.nextBytes(buffer)
        return encoder.encodeToString(buffer)
    }
}
