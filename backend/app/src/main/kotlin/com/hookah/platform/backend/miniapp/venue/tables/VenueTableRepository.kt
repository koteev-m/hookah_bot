package com.hookah.platform.backend.miniapp.venue.tables

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.VenuePermission
import com.hookah.platform.backend.miniapp.venue.VenuePermissions
import com.hookah.platform.backend.miniapp.venue.VenueRoleMapping
import com.hookah.platform.backend.miniapp.venue.seating.authorizeSeating
import com.hookah.platform.backend.miniapp.venue.seating.conflict
import com.hookah.platform.backend.miniapp.venue.seating.lockSeatingRules
import com.hookah.platform.backend.miniapp.venue.seating.rows
import com.hookah.platform.backend.miniapp.venue.seating.seatingPolicyTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import javax.sql.DataSource

data class VenueTableSummary(
    val tableId: Long,
    val tableNumber: Int,
    val isActive: Boolean,
    val activeTokenIssuedAt: Instant?,
)

data class VenueTableCreated(
    val tableId: Long,
    val tableNumber: Int,
    val tokenIssuedAt: Instant,
)

data class VenueTableToken(
    val tableId: Long,
    val tableNumber: Int,
    val token: String,
    val issuedAt: Instant,
)

data class VenueTableOwnerSummary(
    val tableId: Long,
    val tableNumber: Int,
    val capacity: Int?,
    val isActive: Boolean,
    val token: String?,
)

class VenueTableRepository(private val dataSource: DataSource?) {
    private fun <T> tableMutation(
        ds: javax.sql.DataSource,
        venue: Long,
        action: (java.sql.Connection) -> T,
    ): T =
        ds.connection.use { c ->
            c.autoCommit = false
            try {
                lockSeatingRules(c, venue)
                action(c).also { c.commit() }
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }

    private val audit = AuditLogRepository(dataSource)
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
                        """.trimIndent(),
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
                                        activeTokenIssuedAt = rs.getTimestamp("issued_at")?.toInstant(),
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

    suspend fun listTablesWithTokens(
        venueId: Long,
        tableId: Long? = null,
    ): List<VenueTableToken> {
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
                        AND (? IS NULL OR vt.id = ?)
                        ORDER BY vt.table_number, vt.id
                        LIMIT 501
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setObject(2, tableId, java.sql.Types.BIGINT)
                        statement.setObject(3, tableId, java.sql.Types.BIGINT)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<VenueTableToken>()
                            while (rs.next()) {
                                result.add(
                                    VenueTableToken(
                                        tableId = rs.getLong("id"),
                                        tableNumber = rs.getInt("table_number"),
                                        token = rs.getString("token"),
                                        issuedAt = rs.getTimestamp("issued_at").toInstant(),
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

    suspend fun listOwnerTables(venueId: Long): List<VenueTableOwnerSummary> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT vt.id,
                               vt.table_number,
                               vt.capacity,
                               vt.is_active,
                               tt.token
                        FROM venue_tables vt
                        LEFT JOIN table_tokens tt
                          ON tt.table_id = vt.id AND tt.is_active = true
                        WHERE vt.venue_id = ?
                        ORDER BY vt.table_number, vt.id
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<VenueTableOwnerSummary>()
                            while (rs.next()) {
                                result.add(
                                    VenueTableOwnerSummary(
                                        tableId = rs.getLong("id"),
                                        tableNumber = rs.getInt("table_number"),
                                        capacity = rs.getInt("capacity").takeIf { !rs.wasNull() },
                                        isActive = rs.getBoolean("is_active"),
                                        token = rs.getString("token"),
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

    suspend fun ensureTablesAvailable(
        venueId: Long,
        startNumber: Int,
        count: Int,
    ) {
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
                        """.trimIndent(),
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
                        """.trimIndent(),
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

    suspend fun batchCreateTables(
        venueId: Long,
        startNumber: Int,
        count: Int,
    ): List<VenueTableCreated> = batchCreateTables(venueId, List(count) { startNumber + it })

    suspend fun batchCreateTables(
        venueId: Long,
        tableNumbers: List<Int>,
        actorUserId: Long? = null,
        beforeCommit: (Connection, List<VenueTableCreated>) -> Unit = { _, _ -> },
    ): List<VenueTableCreated> {
        require(tableNumbers.size in 1..500 && tableNumbers.all { it > 0 })
        require(tableNumbers.distinct().size == tableNumbers.size)
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        lockSeatingRules(connection, venueId)
                        actorUserId?.let { authorizeSeating(connection, venueId, it, true) }
                        val result = mutableListOf<VenueTableCreated>()
                        for (tableNumber in tableNumbers) {
                            val tableId = insertTable(connection, venueId, tableNumber)
                            val issuedAt = Instant.now()
                            insertToken(connection, tableId, issuedAt)
                            result.add(
                                VenueTableCreated(
                                    tableId = tableId,
                                    tableNumber = tableNumber,
                                    tokenIssuedAt = issuedAt,
                                ),
                            )
                        }
                        beforeCommit(connection, result)
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

    suspend fun createTable(
        venueId: Long,
        tableNumber: Int,
        capacity: Int?,
        actorUserId: Long? = null,
    ): VenueTableCreated {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        lockSeatingRules(connection, venueId)
                        actorUserId?.let { authorizeSeating(connection, venueId, it, true) }
                        val tableId = insertTable(connection, venueId, tableNumber, capacity)
                        val issuedAt = Instant.now()
                        insertToken(connection, tableId, issuedAt)
                        connection.commit()
                        VenueTableCreated(
                            tableId = tableId,
                            tableNumber = tableNumber,
                            tokenIssuedAt = issuedAt,
                        )
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

    suspend fun updateTableNumber(
        venueId: Long,
        tableId: Long,
        tableNumber: Int,
        actorUserId: Long? = null,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                tableMutation(ds, venueId) { connection ->
                    actorUserId?.let { authorizeSeating(connection, venueId, it, true) }
                    connection.prepareStatement(
                        """
                        UPDATE venue_tables
                        SET table_number = ?, seating_revision = seating_revision + 1
                        WHERE id = ? AND venue_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setInt(1, tableNumber)
                        statement.setLong(2, tableId)
                        statement.setLong(3, venueId)
                        try {
                            statement.executeUpdate() > 0
                        } catch (e: SQLException) {
                            if (e.sqlState == "23505") {
                                throw TableNumberConflictException()
                            }
                            throw e
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateTableCapacity(
        venueId: Long,
        tableId: Long,
        capacity: Int,
        actorUserId: Long? = null,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                tableMutation(ds, venueId) { connection ->
                    actorUserId?.let { authorizeSeating(connection, venueId, it, true) }
                    val required =
                        connection.rows(
                            """
                            SELECT party_size FROM bookings WHERE table_id = ? AND status IN ('PENDING','CONFIRMED','CHANGED')
                            UNION ALL SELECT party_size FROM table_seatings WHERE table_id = ? AND released_at IS NULL
                            """.trimIndent(),
                            tableId, tableId,
                        ) {
                            it.getInt(1)
                        }.maxOrNull() ?: 0
                    if (capacity < required) conflict("Вместимость меньше числа гостей в действующих обязательствах.")
                    connection.prepareStatement(
                        """
                        UPDATE venue_tables
                        SET capacity = ?, seating_revision = seating_revision + 1
                        WHERE id = ? AND venue_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setInt(1, capacity)
                        statement.setLong(2, tableId)
                        statement.setLong(3, venueId)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun setTableActive(
        venueId: Long,
        tableId: Long,
        isActive: Boolean,
        actorUserId: Long? = null,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                tableMutation(ds, venueId) { connection ->
                    actorUserId?.let { authorizeSeating(connection, venueId, it, true) }
                    if (!isActive && (
                            connection.rows(
                                "SELECT id FROM bookings WHERE table_id = ? " +
                                    "AND status IN ('PENDING','CONFIRMED','CHANGED')",
                                tableId,
                            ) {
                                it.getLong(1)
                            }.isNotEmpty() ||
                                connection.rows(
                                    "SELECT id FROM table_seatings WHERE table_id = ? AND released_at IS NULL",
                                    tableId,
                                ) {
                                    it.getLong(1)
                                }.isNotEmpty()
                        )
                    ) {
                        conflict("Сначала разрешите брони и посадки стола в разделе «Зал».")
                    }
                    connection.prepareStatement(
                        """
                        UPDATE venue_tables
                        SET is_active = ?, seating_revision = seating_revision + 1
                        WHERE id = ? AND venue_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setBoolean(1, isActive)
                        statement.setLong(2, tableId)
                        statement.setLong(3, venueId)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun rotateToken(
        venueId: Long,
        tableId: Long,
        actorUserId: Long,
    ): VenueTableCreated? =
        tokenTransaction(venueId, actorUserId, VenuePermission.TABLE_TOKEN_ROTATE) { connection ->
            val rotated = replaceToken(connection, venueId, tableId) ?: return@tokenTransaction null
            audit.appendJson(
                connection,
                actorUserId,
                "TABLE_TOKEN_ROTATE",
                "venue",
                venueId,
                buildJsonObject { put("tableId", tableId) },
            )
            rotated
        }

    suspend fun rotateTokens(
        venueId: Long,
        tableIds: List<Long>?,
        actorUserId: Long,
    ): List<VenueTableCreated> {
        return tokenTransaction(venueId, actorUserId, rotationPermission(tableIds)) { connection ->
            val requested =
                tableIds?.distinct() ?: connection.rows(
                    "SELECT id FROM venue_tables WHERE venue_id = ? AND is_active = true ORDER BY table_number, id",
                    venueId,
                ) { it.getLong(1) }
            val rotatedById =
                requested.sorted()
                    .mapNotNull { tableId -> replaceToken(connection, venueId, tableId) }
                    .associateBy { it.tableId }
            val rotated = requested.mapNotNull { rotatedById[it] }
            audit.appendJson(
                connection,
                actorUserId,
                if (tableIds == null) "TABLE_TOKEN_ROTATE_ALL" else "TABLE_TOKEN_ROTATE_BATCH",
                "venue",
                venueId,
                buildJsonObject {
                    put("count", rotated.size)
                    put("tableIds", JsonArray(rotated.map { JsonPrimitive(it.tableId) }))
                },
            )
            rotated
        }
    }

    suspend fun ensureActiveToken(
        venueId: Long,
        tableId: Long,
        actorUserId: Long,
    ): Boolean =
        tokenTransaction(venueId, actorUserId, VenuePermission.TABLE_MANAGE) { connection ->
            lockActiveTable(connection, venueId, tableId) ?: return@tokenTransaction false
            val active =
                connection.rows("SELECT token FROM table_tokens WHERE table_id = ? AND is_active = true", tableId) {
                    it.getString(1)
                }
            if (active.none { it.isNotBlank() }) {
                revokeActiveToken(connection, tableId)
                insertToken(connection, tableId, Instant.now())
                audit.appendJson(
                    connection,
                    actorUserId,
                    "TABLE_TOKEN_PROVISION",
                    "venue",
                    venueId,
                    buildJsonObject { put("tableId", tableId) },
                )
            }
            true
        }

    private suspend fun <T> tokenTransaction(
        venueId: Long,
        actorUserId: Long,
        permission: VenuePermission,
        block: (Connection) -> T,
    ): T {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                seatingPolicyTransaction(ds, venueId) { connection ->
                    val role =
                        connection.rows(
                            """
                            SELECT vm.role FROM venue_members vm
                            WHERE vm.venue_id = ? AND vm.user_id = ?
                              AND EXISTS (SELECT 1 FROM venues v WHERE v.id = vm.venue_id
                                          AND COALESCE(v.status, 'DRAFT') <> 'DELETED')
                            FOR UPDATE
                            """.trimIndent(),
                            venueId,
                            actorUserId,
                        ) { VenueRoleMapping.fromDb(it.getString(1)) }.singleOrNull()
                    if (role == null || permission !in VenuePermissions.forRole(role)) throw ForbiddenException()
                    block(connection)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun replaceToken(
        connection: Connection,
        venueId: Long,
        tableId: Long,
    ): VenueTableCreated? {
        val tableNumber = lockActiveTable(connection, venueId, tableId) ?: return null
        revokeActiveToken(connection, tableId)
        val issuedAt = Instant.now()
        insertToken(connection, tableId, issuedAt)
        return VenueTableCreated(tableId, tableNumber, issuedAt)
    }

    private fun insertTable(
        connection: Connection,
        venueId: Long,
        tableNumber: Int,
        capacity: Int? = null,
    ): Long {
        return connection.prepareStatement(
            """
            INSERT INTO venue_tables (venue_id, table_number, capacity, is_active)
            VALUES (?, ?, ?, true)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setInt(2, tableNumber)
            if (capacity == null) {
                statement.setNull(3, java.sql.Types.INTEGER)
            } else {
                statement.setInt(3, capacity)
            }
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

    private fun insertToken(
        connection: Connection,
        tableId: Long,
        issuedAt: Instant,
    ): String {
        repeat(5) {
            val token = tokenGenerator.generate()
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO table_tokens (token, table_id, is_active, issued_at)
                    VALUES (?, ?, true, ?)
                    """.trimIndent(),
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

    private fun revokeActiveToken(
        connection: Connection,
        tableId: Long,
    ) {
        connection.prepareStatement(
            """
            UPDATE table_tokens
            SET is_active = false,
                revoked_at = now()
            WHERE table_id = ? AND is_active = true
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, tableId)
            statement.executeUpdate()
        }
    }

    private fun lockActiveTable(
        connection: Connection,
        venueId: Long,
        tableId: Long,
    ): Int? =
        connection.rows(
            "SELECT table_number FROM venue_tables WHERE id = ? AND venue_id = ? AND is_active = true FOR UPDATE",
            tableId,
            venueId,
        ) { it.getInt(1) }.singleOrNull()
}

internal fun rotationPermission(tableIds: List<Long>?): VenuePermission =
    if (tableIds == null) VenuePermission.TABLE_TOKEN_ROTATE_ALL else VenuePermission.TABLE_TOKEN_ROTATE

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
