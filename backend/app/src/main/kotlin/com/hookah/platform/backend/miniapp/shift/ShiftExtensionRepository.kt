package com.hookah.platform.backend.miniapp.shift

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

private const val SHIFT_EXTENSION_SOURCE = "SHIFT_EXTENSION"
private const val SHIFT_EXTENSION_DEFAULT_LABEL = "Продление работы на 1 час"
private const val MAX_COMMENT_LENGTH = 500
private const val MAX_IDEMPOTENCY_KEY_LENGTH = 128

enum class ShiftExtensionRequestStatus(val dbValue: String) {
    PENDING("PENDING"),
    APPROVED("APPROVED"),
    REJECTED("REJECTED"),
    CANCELLED("CANCELLED"),
    ;

    companion object {
        fun fromDb(raw: String?): ShiftExtensionRequestStatus =
            entries.firstOrNull { status -> status.dbValue.equals(raw, ignoreCase = true) } ?: PENDING
    }
}

data class ShiftExtensionSettings(
    val venueId: Long,
    val enabled: Boolean,
    val durationMinutes: Int,
    val priceMinor: Long?,
    val currency: String,
    val maxExtensionsPerSession: Int?,
)

data class ShiftExtensionRequestRecord(
    val id: Long,
    val venueId: Long,
    val tableSessionId: Long,
    val tableId: Long,
    val tableNumber: Int?,
    val tabId: Long,
    val orderId: Long,
    val requestedByUserId: Long,
    val status: ShiftExtensionRequestStatus,
    val durationMinutes: Int,
    val priceMinor: Long,
    val currency: String,
    val currentOrderableUntil: Instant,
    val requestedUntil: Instant,
    val comment: String?,
    val decidedByUserId: Long?,
    val decidedAt: Instant?,
    val rejectReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CreateShiftExtensionRequestCommand(
    val venueId: Long,
    val tableSessionId: Long,
    val tableId: Long,
    val tabId: Long,
    val orderId: Long,
    val requestedByUserId: Long,
    val currentOrderableUntil: Instant,
    val idempotencyKey: String?,
    val comment: String?,
)

data class UpdateShiftExtensionSettingsCommand(
    val venueId: Long,
    val enabled: Boolean,
    val durationMinutes: Int,
    val priceMinor: Long?,
    val currency: String,
    val maxExtensionsPerSession: Int?,
)

data class ShiftExtensionDecisionResult(
    val request: ShiftExtensionRequestRecord,
    val orderId: Long,
    val applied: Boolean,
)

class ShiftExtensionRepository(
    private val dataSource: DataSource?,
) {
    suspend fun findActiveSettings(venueId: Long): ShiftExtensionSettings? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    loadActiveSettings(connection, venueId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun getSettings(venueId: Long): ShiftExtensionSettings {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    loadSettings(connection, venueId) ?: defaultSettings(venueId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun upsertSettings(command: UpdateShiftExtensionSettingsCommand): ShiftExtensionSettings {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val updated = updateSettings(connection, command)
                        if (updated == 0) {
                            insertSettings(connection, command)
                        }
                        connection.commit()
                        loadSettings(connection, command.venueId) ?: throw DatabaseUnavailableException()
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

    suspend fun findPendingRequest(
        tableSessionId: Long,
        tabId: Long,
    ): ShiftExtensionRequestRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        baseRequestSelect() +
                            """
                            WHERE ser.table_session_id = ?
                              AND ser.tab_id = ?
                              AND ser.status = 'PENDING'
                            ORDER BY ser.created_at DESC, ser.id DESC
                            LIMIT 1
                            """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, tableSessionId)
                        statement.setLong(2, tabId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) rs.toShiftExtensionRequestRecord() else null
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun createPendingRequest(command: CreateShiftExtensionRequestCommand): ShiftExtensionRequestRecord {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val normalizedComment = normalizeComment(command.comment)
        val normalizedIdempotencyKey = normalizeIdempotencyKey(command.idempotencyKey)
        val now = Instant.now()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val existingByIdempotency =
                            normalizedIdempotencyKey?.let { idempotencyKey ->
                                findExistingByIdempotency(
                                    connection = connection,
                                    venueId = command.venueId,
                                    tableSessionId = command.tableSessionId,
                                    requestedByUserId = command.requestedByUserId,
                                    idempotencyKey = idempotencyKey,
                                )
                            }
                        if (existingByIdempotency != null) {
                            connection.commit()
                            return@use existingByIdempotency
                        }

                        val existingPending =
                            findPendingForUpdate(
                                connection = connection,
                                venueId = command.venueId,
                                tableSessionId = command.tableSessionId,
                                tabId = command.tabId,
                            )
                        if (existingPending != null) {
                            connection.commit()
                            return@use existingPending
                        }

                        val settings =
                            loadActiveSettings(connection, command.venueId)
                                ?: throw InvalidInputException("Shift extension is not configured")
                        val priceMinor =
                            settings.priceMinor
                                ?: throw InvalidInputException("Shift extension price is not configured")
                        ensureActiveContext(
                            connection = connection,
                            venueId = command.venueId,
                            tableSessionId = command.tableSessionId,
                            tableId = command.tableId,
                            tabId = command.tabId,
                            orderId = command.orderId,
                            now = now,
                        )
                        ensureExtensionLimit(
                            connection = connection,
                            settings = settings,
                            tableSessionId = command.tableSessionId,
                        )

                        val requestedUntil =
                            command.currentOrderableUntil.plus(Duration.ofMinutes(settings.durationMinutes.toLong()))
                        val requestId =
                            insertRequest(
                                connection = connection,
                                command = command,
                                settings = settings,
                                priceMinor = priceMinor,
                                currentOrderableUntil = command.currentOrderableUntil,
                                requestedUntil = requestedUntil,
                                idempotencyKey = normalizedIdempotencyKey,
                                comment = normalizedComment,
                                now = now,
                            )
                        connection.commit()
                        loadRequest(connection, command.venueId, requestId) ?: throw DatabaseUnavailableException()
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

    suspend fun listRequests(
        venueId: Long,
        status: ShiftExtensionRequestStatus?,
    ): List<ShiftExtensionRequestRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        buildString {
                            append(baseRequestSelect())
                            append("WHERE ser.venue_id = ?")
                            if (status != null) {
                                append("\n  AND ser.status = ?")
                            }
                            append("\nORDER BY ser.created_at DESC, ser.id DESC")
                        }
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, venueId)
                        if (status != null) {
                            statement.setString(2, status.dbValue)
                        }
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(rs.toShiftExtensionRequestRecord())
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

    suspend fun approveRequest(
        venueId: Long,
        requestId: Long,
        actorUserId: Long,
    ): ShiftExtensionDecisionResult {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val now = Instant.now()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val request =
                            loadRequestForUpdate(connection, venueId, requestId)
                                ?: throw NotFoundException()
                        if (request.status == ShiftExtensionRequestStatus.APPROVED) {
                            connection.commit()
                            return@use ShiftExtensionDecisionResult(
                                request = request,
                                orderId = request.orderId,
                                applied = false,
                            )
                        }
                        if (request.status != ShiftExtensionRequestStatus.PENDING) {
                            throw InvalidInputException("Shift extension request is not pending")
                        }
                        ensureActiveContext(
                            connection = connection,
                            venueId = venueId,
                            tableSessionId = request.tableSessionId,
                            tableId = request.tableId,
                            tabId = request.tabId,
                            orderId = request.orderId,
                            now = now,
                        )
                        val chargeInserted =
                            insertServiceChargeIfMissing(
                                connection = connection,
                                request = request,
                                actorUserId = actorUserId,
                                now = now,
                            )
                        extendSession(connection, request, now)
                        decideRequest(
                            connection = connection,
                            venueId = venueId,
                            requestId = requestId,
                            status = ShiftExtensionRequestStatus.APPROVED,
                            actorUserId = actorUserId,
                            reasonText = null,
                            now = now,
                        )
                        touchOrder(connection, request.orderId, now)
                        connection.commit()
                        ShiftExtensionDecisionResult(
                            request =
                                loadRequest(connection, venueId, requestId)
                                    ?: throw DatabaseUnavailableException(),
                            orderId = request.orderId,
                            applied = chargeInserted,
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

    suspend fun rejectRequest(
        venueId: Long,
        requestId: Long,
        actorUserId: Long,
        reasonText: String?,
    ): ShiftExtensionDecisionResult {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val now = Instant.now()
        val normalizedReason = normalizeComment(reasonText)
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val request =
                            loadRequestForUpdate(connection, venueId, requestId)
                                ?: throw NotFoundException()
                        if (request.status == ShiftExtensionRequestStatus.REJECTED) {
                            connection.commit()
                            return@use ShiftExtensionDecisionResult(
                                request = request,
                                orderId = request.orderId,
                                applied = false,
                            )
                        }
                        if (request.status != ShiftExtensionRequestStatus.PENDING) {
                            throw InvalidInputException("Shift extension request is not pending")
                        }
                        ensureVenuePublished(connection, venueId)
                        decideRequest(
                            connection = connection,
                            venueId = venueId,
                            requestId = requestId,
                            status = ShiftExtensionRequestStatus.REJECTED,
                            actorUserId = actorUserId,
                            reasonText = normalizedReason,
                            now = now,
                        )
                        touchOrder(connection, request.orderId, now)
                        connection.commit()
                        ShiftExtensionDecisionResult(
                            request =
                                loadRequest(connection, venueId, requestId)
                                    ?: throw DatabaseUnavailableException(),
                            orderId = request.orderId,
                            applied = true,
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

    private fun loadActiveSettings(
        connection: Connection,
        venueId: Long,
    ): ShiftExtensionSettings? =
        connection.prepareStatement(
            """
            SELECT venue_id, enabled, duration_minutes, price_minor, currency, max_extensions_per_session
            FROM shift_extension_settings
            WHERE venue_id = ?
              AND enabled = TRUE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    ShiftExtensionSettings(
                        venueId = rs.getLong("venue_id"),
                        enabled = rs.getBoolean("enabled"),
                        durationMinutes = rs.getInt("duration_minutes"),
                        priceMinor =
                            rs.getLong("price_minor").let { value ->
                                if (rs.wasNull()) null else value
                            },
                        currency = rs.getString("currency"),
                        maxExtensionsPerSession =
                            rs.getInt("max_extensions_per_session").let { value ->
                                if (rs.wasNull()) null else value
                            },
                    )
                } else {
                    null
                }
            }
        }

    private fun loadSettings(
        connection: Connection,
        venueId: Long,
    ): ShiftExtensionSettings? =
        connection.prepareStatement(
            """
            SELECT venue_id, enabled, duration_minutes, price_minor, currency, max_extensions_per_session
            FROM shift_extension_settings
            WHERE venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    ShiftExtensionSettings(
                        venueId = rs.getLong("venue_id"),
                        enabled = rs.getBoolean("enabled"),
                        durationMinutes = rs.getInt("duration_minutes"),
                        priceMinor =
                            rs.getLong("price_minor").let { value ->
                                if (rs.wasNull()) null else value
                            },
                        currency = rs.getString("currency"),
                        maxExtensionsPerSession =
                            rs.getInt("max_extensions_per_session").let { value ->
                                if (rs.wasNull()) null else value
                            },
                    )
                } else {
                    null
                }
            }
        }

    private fun updateSettings(
        connection: Connection,
        command: UpdateShiftExtensionSettingsCommand,
    ): Int =
        connection.prepareStatement(
            """
            UPDATE shift_extension_settings
            SET enabled = ?,
                duration_minutes = ?,
                price_minor = ?,
                currency = ?,
                max_extensions_per_session = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setBoolean(1, command.enabled)
            statement.setInt(2, command.durationMinutes)
            if (command.priceMinor == null) {
                statement.setNull(3, Types.BIGINT)
            } else {
                statement.setLong(3, command.priceMinor)
            }
            statement.setString(4, command.currency)
            if (command.maxExtensionsPerSession == null) {
                statement.setNull(5, Types.INTEGER)
            } else {
                statement.setInt(5, command.maxExtensionsPerSession)
            }
            statement.setLong(6, command.venueId)
            statement.executeUpdate()
        }

    private fun insertSettings(
        connection: Connection,
        command: UpdateShiftExtensionSettingsCommand,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO shift_extension_settings (
                venue_id,
                enabled,
                duration_minutes,
                price_minor,
                currency,
                max_extensions_per_session
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, command.venueId)
            statement.setBoolean(2, command.enabled)
            statement.setInt(3, command.durationMinutes)
            if (command.priceMinor == null) {
                statement.setNull(4, Types.BIGINT)
            } else {
                statement.setLong(4, command.priceMinor)
            }
            statement.setString(5, command.currency)
            if (command.maxExtensionsPerSession == null) {
                statement.setNull(6, Types.INTEGER)
            } else {
                statement.setInt(6, command.maxExtensionsPerSession)
            }
            statement.executeUpdate()
        }
    }

    private fun insertRequest(
        connection: Connection,
        command: CreateShiftExtensionRequestCommand,
        settings: ShiftExtensionSettings,
        priceMinor: Long,
        currentOrderableUntil: Instant,
        requestedUntil: Instant,
        idempotencyKey: String?,
        comment: String?,
        now: Instant,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO shift_extension_requests (
                venue_id,
                table_session_id,
                table_id,
                tab_id,
                order_id,
                requested_by_user_id,
                status,
                duration_minutes,
                price_minor,
                currency,
                current_orderable_until,
                requested_until,
                comment,
                idempotency_key,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, command.venueId)
            statement.setLong(2, command.tableSessionId)
            statement.setLong(3, command.tableId)
            statement.setLong(4, command.tabId)
            statement.setLong(5, command.orderId)
            statement.setLong(6, command.requestedByUserId)
            statement.setInt(7, settings.durationMinutes)
            statement.setLong(8, priceMinor)
            statement.setString(9, settings.currency)
            statement.setTimestamp(10, Timestamp.from(currentOrderableUntil))
            statement.setTimestamp(11, Timestamp.from(requestedUntil))
            statement.setNullableString(12, comment)
            statement.setNullableString(13, idempotencyKey)
            statement.setTimestamp(14, Timestamp.from(now))
            statement.setTimestamp(15, Timestamp.from(now))
            statement.executeUpdate()
            statement.generatedKeys.use { rs ->
                if (rs.next()) rs.getLong(1) else throw DatabaseUnavailableException()
            }
        }

    private fun findExistingByIdempotency(
        connection: Connection,
        venueId: Long,
        tableSessionId: Long,
        requestedByUserId: Long,
        idempotencyKey: String,
    ): ShiftExtensionRequestRecord? =
        connection.prepareStatement(
            baseRequestSelect() +
                """
                WHERE ser.venue_id = ?
                  AND ser.table_session_id = ?
                  AND ser.requested_by_user_id = ?
                  AND ser.idempotency_key = ?
                ORDER BY ser.created_at DESC, ser.id DESC
                LIMIT 1
                """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableSessionId)
            statement.setLong(3, requestedByUserId)
            statement.setString(4, idempotencyKey)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toShiftExtensionRequestRecord() else null }
        }

    private fun findPendingForUpdate(
        connection: Connection,
        venueId: Long,
        tableSessionId: Long,
        tabId: Long,
    ): ShiftExtensionRequestRecord? {
        val requestId =
            connection.prepareStatement(
                """
                SELECT id
                FROM shift_extension_requests
                WHERE venue_id = ?
                  AND table_session_id = ?
                  AND tab_id = ?
                  AND status = 'PENDING'
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, tableSessionId)
                statement.setLong(3, tabId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else null }
            } ?: return null
        return loadRequest(connection, venueId, requestId)
    }

    private fun loadRequest(
        connection: Connection,
        venueId: Long,
        requestId: Long,
    ): ShiftExtensionRequestRecord? =
        connection.prepareStatement(
            baseRequestSelect() +
                """
                WHERE ser.venue_id = ?
                  AND ser.id = ?
                """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, requestId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toShiftExtensionRequestRecord() else null }
        }

    private fun loadRequestForUpdate(
        connection: Connection,
        venueId: Long,
        requestId: Long,
    ): ShiftExtensionRequestRecord? {
        val locked =
            connection.prepareStatement(
                """
                SELECT id
                FROM shift_extension_requests
                WHERE venue_id = ?
                  AND id = ?
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, requestId)
                statement.executeQuery().use { rs -> rs.next() }
            }
        if (!locked) {
            return null
        }
        return loadRequest(connection, venueId, requestId)
    }

    private fun ensureActiveContext(
        connection: Connection,
        venueId: Long,
        tableSessionId: Long,
        tableId: Long,
        tabId: Long,
        orderId: Long,
        now: Instant,
    ) {
        ensureVenuePublished(connection, venueId)
        val active =
            connection.prepareStatement(
                """
                SELECT 1
                FROM orders o
                JOIN table_sessions ts ON ts.id = o.table_session_id
                JOIN tab t ON t.id = ?
                WHERE o.id = ?
                  AND o.venue_id = ?
                  AND o.table_id = ?
                  AND o.table_session_id = ?
                  AND o.status = 'ACTIVE'
                  AND ts.venue_id = ?
                  AND ts.table_id = ?
                  AND ts.status = 'ACTIVE'
                  AND ts.ended_at IS NULL
                  AND ts.expires_at > ?
                  AND t.venue_id = ?
                  AND t.table_session_id = ?
                  AND t.status = 'ACTIVE'
                  AND EXISTS (
                      SELECT 1
                      FROM order_batches ob
                      WHERE ob.order_id = o.id
                        AND ob.tab_id = t.id
                  )
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, tabId)
                statement.setLong(2, orderId)
                statement.setLong(3, venueId)
                statement.setLong(4, tableId)
                statement.setLong(5, tableSessionId)
                statement.setLong(6, venueId)
                statement.setLong(7, tableId)
                statement.setTimestamp(8, Timestamp.from(now))
                statement.setLong(9, venueId)
                statement.setLong(10, tableSessionId)
                statement.executeQuery().use { rs -> rs.next() }
            }
        if (!active) {
            throw NotFoundException("Active table order context not found")
        }
    }

    private fun ensureVenuePublished(
        connection: Connection,
        venueId: Long,
    ) {
        val published =
            connection.prepareStatement(
                """
                SELECT 1
                FROM venues
                WHERE id = ?
                  AND status = 'PUBLISHED'
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { rs -> rs.next() }
            }
        if (!published) {
            throw NotFoundException("Venue is not available")
        }
    }

    private fun ensureExtensionLimit(
        connection: Connection,
        settings: ShiftExtensionSettings,
        tableSessionId: Long,
    ) {
        val max = settings.maxExtensionsPerSession ?: return
        val approvedCount =
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM shift_extension_requests
                WHERE table_session_id = ?
                  AND status = 'APPROVED'
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, tableSessionId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        if (approvedCount >= max) {
            throw InvalidInputException("Shift extension limit reached")
        }
    }

    private fun insertServiceChargeIfMissing(
        connection: Connection,
        request: ShiftExtensionRequestRecord,
        actorUserId: Long,
        now: Instant,
    ): Boolean {
        val exists =
            connection.prepareStatement(
                """
                SELECT 1
                FROM order_service_charges
                WHERE source = ?
                  AND source_request_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, SHIFT_EXTENSION_SOURCE)
                statement.setLong(2, request.id)
                statement.executeQuery().use { rs -> rs.next() }
            }
        if (exists) {
            return false
        }
        connection.prepareStatement(
            """
            INSERT INTO order_service_charges (
                order_id,
                venue_id,
                table_session_id,
                tab_id,
                source,
                source_request_id,
                label,
                qty,
                unit_price_minor,
                total_minor,
                currency,
                created_by_user_id,
                created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, request.orderId)
            statement.setLong(2, request.venueId)
            statement.setLong(3, request.tableSessionId)
            statement.setLong(4, request.tabId)
            statement.setString(5, SHIFT_EXTENSION_SOURCE)
            statement.setLong(6, request.id)
            statement.setString(7, serviceChargeLabel(request.durationMinutes))
            statement.setLong(8, request.priceMinor)
            statement.setLong(9, request.priceMinor)
            statement.setString(10, request.currency)
            statement.setLong(11, actorUserId)
            statement.setTimestamp(12, Timestamp.from(now))
            statement.executeUpdate()
        }
        return true
    }

    private fun extendSession(
        connection: Connection,
        request: ShiftExtensionRequestRecord,
        now: Instant,
    ) {
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
                statement.setTimestamp(2, Timestamp.from(request.requestedUntil))
                statement.setTimestamp(3, Timestamp.from(request.requestedUntil))
                statement.setLong(4, request.tableSessionId)
                statement.setLong(5, request.venueId)
                statement.setTimestamp(6, Timestamp.from(now))
                statement.executeUpdate()
            }
        if (updated <= 0) {
            throw NotFoundException("Active table session not found")
        }
    }

    private fun decideRequest(
        connection: Connection,
        venueId: Long,
        requestId: Long,
        status: ShiftExtensionRequestStatus,
        actorUserId: Long,
        reasonText: String?,
        now: Instant,
    ) {
        connection.prepareStatement(
            """
            UPDATE shift_extension_requests
            SET status = ?,
                decided_by_user_id = ?,
                decided_at = ?,
                reject_reason = ?,
                updated_at = ?
            WHERE id = ?
              AND venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, status.dbValue)
            statement.setLong(2, actorUserId)
            statement.setTimestamp(3, Timestamp.from(now))
            statement.setNullableString(4, reasonText)
            statement.setTimestamp(5, Timestamp.from(now))
            statement.setLong(6, requestId)
            statement.setLong(7, venueId)
            statement.executeUpdate()
        }
    }

    private fun touchOrder(
        connection: Connection,
        orderId: Long,
        now: Instant,
    ) {
        connection.prepareStatement(
            """
            UPDATE orders
            SET updated_at = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setLong(2, orderId)
            statement.executeUpdate()
        }
    }

    private fun baseRequestSelect(): String =
        """
        SELECT ser.id,
               ser.venue_id,
               ser.table_session_id,
               ser.table_id,
               vt.table_number,
               ser.tab_id,
               ser.order_id,
               ser.requested_by_user_id,
               ser.status,
               ser.duration_minutes,
               ser.price_minor,
               ser.currency,
               ser.current_orderable_until,
               ser.requested_until,
               ser.comment,
               ser.decided_by_user_id,
               ser.decided_at,
               ser.reject_reason,
               ser.created_at,
               ser.updated_at
        FROM shift_extension_requests ser
        LEFT JOIN venue_tables vt ON vt.id = ser.table_id
        """.trimIndent() + "\n"

    private fun java.sql.ResultSet.toShiftExtensionRequestRecord(): ShiftExtensionRequestRecord =
        ShiftExtensionRequestRecord(
            id = getLong("id"),
            venueId = getLong("venue_id"),
            tableSessionId = getLong("table_session_id"),
            tableId = getLong("table_id"),
            tableNumber =
                getInt("table_number").let { value ->
                    if (wasNull()) null else value
                },
            tabId = getLong("tab_id"),
            orderId = getLong("order_id"),
            requestedByUserId = getLong("requested_by_user_id"),
            status = ShiftExtensionRequestStatus.fromDb(getString("status")),
            durationMinutes = getInt("duration_minutes"),
            priceMinor = getLong("price_minor"),
            currency = getString("currency"),
            currentOrderableUntil = getTimestamp("current_orderable_until").toInstant(),
            requestedUntil = getTimestamp("requested_until").toInstant(),
            comment = getString("comment"),
            decidedByUserId =
                getLong("decided_by_user_id").let { value ->
                    if (wasNull()) null else value
                },
            decidedAt = getTimestamp("decided_at")?.toInstant(),
            rejectReason = getString("reject_reason"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )
}

private fun java.sql.PreparedStatement.setNullableString(
    index: Int,
    value: String?,
) {
    if (value == null) {
        setNull(index, Types.VARCHAR)
    } else {
        setString(index, value)
    }
}

private fun normalizeComment(raw: String?): String? {
    val normalized = raw?.trim().orEmpty()
    if (normalized.isBlank()) {
        return null
    }
    if (normalized.length > MAX_COMMENT_LENGTH) {
        throw InvalidInputException("comment length must be <= $MAX_COMMENT_LENGTH")
    }
    return normalized
}

private fun normalizeIdempotencyKey(raw: String?): String? {
    val normalized = raw?.trim().orEmpty()
    if (normalized.isBlank()) {
        return null
    }
    if (normalized.length > MAX_IDEMPOTENCY_KEY_LENGTH) {
        throw InvalidInputException("idempotencyKey length must be <= $MAX_IDEMPOTENCY_KEY_LENGTH")
    }
    return normalized
}

private fun defaultSettings(venueId: Long): ShiftExtensionSettings =
    ShiftExtensionSettings(
        venueId = venueId,
        enabled = false,
        durationMinutes = 60,
        priceMinor = null,
        currency = "RUB",
        maxExtensionsPerSession = null,
    )

private fun serviceChargeLabel(durationMinutes: Int): String =
    if (durationMinutes == 60) {
        SHIFT_EXTENSION_DEFAULT_LABEL
    } else {
        "Продление работы на $durationMinutes мин."
    }
