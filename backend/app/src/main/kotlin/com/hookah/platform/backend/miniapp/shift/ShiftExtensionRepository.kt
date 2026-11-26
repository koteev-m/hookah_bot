package com.hookah.platform.backend.miniapp.shift

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.ShiftExtensionTermsChangedException
import com.hookah.platform.backend.api.VisitGenerationChangedException
import com.hookah.platform.backend.api.VisitVersionRequiredException
import com.hookah.platform.backend.miniapp.venue.seating.lockSeatingVenue
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
    val updatedAt: Instant = Instant.EPOCH,
) {
    fun terms(): ShiftExtensionTerms? =
        priceMinor?.takeIf { enabled }?.let {
            val revision = updatedAt.epochSecond * 1_000_000 + updatedAt.nano / 1_000
            ShiftExtensionTerms(durationMinutes, it, currency, revision.toString())
        }
}

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
    val visitGeneration: Long = 0,
    val confirmedTerms: ShiftExtensionTerms? = null,
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

internal data class GuestExtensionWindow(val orderId: Long, val paidUntil: Instant?)

class ShiftExtensionRepository(
    private val dataSource: DataSource?,
) {
    internal suspend fun guestExtensionWindow(
        venueId: Long,
        tableId: Long,
        sessionId: Long,
        tabId: Long,
        userId: Long,
    ): GuestExtensionWindow {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    // One statement observes either side of a committed close/release.
                    connection.prepareStatement(
                        """
                        SELECT CASE WHEN EXISTS (
                            SELECT 1 FROM table_seatings seat
                            WHERE seat.table_session_id = ts.id AND seat.venue_id = ts.venue_id AND seat.released_at IS NULL
                        ) THEN NULL ELSE ts.expires_at END AS paid_until,
                        EXISTS (
                            SELECT 1 FROM tab t JOIN tab_member tm ON tm.tab_id = t.id
                            WHERE t.id = ? AND t.table_session_id = ts.id AND t.venue_id = ts.venue_id
                              AND t.status = 'ACTIVE' AND tm.user_id = ?
                        ) AS is_member,
                        (SELECT MAX(o.id) FROM orders o
                         WHERE o.table_session_id = ts.id AND o.venue_id = ts.venue_id AND o.table_id = ts.table_id
                           AND o.status = 'ACTIVE' AND EXISTS (
                               SELECT 1 FROM order_batches b WHERE b.order_id = o.id AND b.tab_id = ?
                           )) AS order_id
                        FROM table_sessions ts
                        WHERE ts.id = ? AND ts.venue_id = ? AND ts.table_id = ?
                          AND ts.status = 'ACTIVE' AND ts.ended_at IS NULL AND ts.expires_at > CURRENT_TIMESTAMP
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, tabId)
                        statement.setLong(2, userId)
                        statement.setLong(3, tabId)
                        statement.setLong(4, sessionId)
                        statement.setLong(5, venueId)
                        statement.setLong(6, tableId)
                        statement.executeQuery().use { rows ->
                            if (!rows.next()) throw NotFoundException("Active table session not found")
                            if (!rows.getBoolean("is_member")) throw ForbiddenException("Tab access denied")
                            val orderId = rows.getLong("order_id")
                            if (rows.wasNull()) throw NotFoundException("Active order not found")
                            GuestExtensionWindow(orderId, rows.getTimestamp("paid_until")?.toInstant())
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun hasStaffManagedSeating(
        venueId: Long,
        sessionId: Long,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { hasStaffManagedSeating(it, venueId, sessionId) }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun hasStaffManagedSeating(
        connection: Connection,
        venueId: Long,
        sessionId: Long,
    ): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM table_seatings WHERE venue_id=? AND table_session_id=? AND released_at IS NULL",
        ).use {
            it.setLong(1, venueId)
            it.setLong(2, sessionId)
            it.executeQuery().use { rows -> rows.next() }
        }

    private fun requirePaidWindow(
        connection: Connection,
        venueId: Long,
        sessionId: Long,
    ) {
        if (hasStaffManagedSeating(connection, venueId, sessionId)) {
            throw InvalidInputException("Время этой посадки меняет персонал в зале. Платное продление недоступно.")
        }
    }

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
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val record = createPendingRequest(connection, command)
                        connection.commit()
                        record
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

    internal fun findExactReplay(
        connection: Connection,
        command: CreateShiftExtensionRequestCommand,
    ): ShiftExtensionRequestRecord? {
        val key = requireShiftExtensionKey(command.idempotencyKey)
        val comment = normalizeComment(command.comment)
        val registeredRequestId =
            connection.prepareStatement(
                """
                SELECT request_id, visit_generation, request_comment, terms_revision
                FROM shift_extension_request_keys
                WHERE venue_id = ? AND table_session_id = ? AND requested_by_user_id = ? AND idempotency_key = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, command.venueId)
                statement.setLong(2, command.tableSessionId)
                statement.setLong(3, command.requestedByUserId)
                statement.setString(4, key)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) return@use null
                    if (rows.getLong("visit_generation") != command.visitGeneration ||
                        rows.getString("request_comment") != comment
                    ) {
                        throw VisitGenerationChangedException()
                    }
                    rows.getString("terms_revision")?.let { revision ->
                        val terms = command.confirmedTerms ?: throw VisitVersionRequiredException()
                        if (terms.revision != revision) throw VisitGenerationChangedException()
                    }
                    rows.getLong("request_id")
                }
            }
        val result =
            if (registeredRequestId != null) {
                loadRequest(connection, command.venueId, registeredRequestId) ?: throw VisitGenerationChangedException()
            } else {
                findExistingByIdempotency(
                    connection,
                    command.venueId,
                    command.tableSessionId,
                    command.requestedByUserId,
                    key,
                )
                    ?.also { if (it.comment != comment) throw VisitGenerationChangedException() }
            }
        return result?.also {
            if (it.orderId != command.orderId || it.tabId != command.tabId ||
                it.tableId != command.tableId || it.tableSessionId != command.tableSessionId
            ) {
                throw VisitGenerationChangedException()
            }
            val terms = command.confirmedTerms
            if (terms != null && !it.matchesTerms(terms)) throw VisitGenerationChangedException()
        }
    }

    private fun registerRequestKey(
        connection: Connection,
        command: CreateShiftExtensionRequestCommand,
        key: String,
        comment: String?,
        requestId: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO shift_extension_request_keys
                (venue_id, table_session_id, requested_by_user_id, idempotency_key, visit_generation, request_id, request_comment, terms_revision)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, command.venueId)
            statement.setLong(2, command.tableSessionId)
            statement.setLong(3, command.requestedByUserId)
            statement.setString(4, key)
            statement.setLong(5, command.visitGeneration)
            statement.setLong(6, requestId)
            statement.setNullableString(7, comment)
            statement.setLong(8, requireNotNull(command.confirmedTerms).revision.toLong())
            statement.executeUpdate()
        }
    }

    fun createPendingRequest(
        connection: Connection,
        command: CreateShiftExtensionRequestCommand,
        now: Instant = Instant.now(),
    ): ShiftExtensionRequestRecord {
        lockSeatingVenue(connection, command.venueId)
        ensureRequestingActorIsTabMember(connection, command)
        val normalizedIdempotencyKey = requireShiftExtensionKey(command.idempotencyKey)
        findExactReplay(connection, command)?.let { return it }
        val normalizedComment = normalizeComment(command.comment)
        requirePaidWindow(connection, command.venueId, command.tableSessionId)
        val confirmed = command.confirmedTerms ?: throw VisitVersionRequiredException()
        val settings = loadSettings(connection, command.venueId, forUpdate = true)
        if (settings?.terms() != confirmed) throw ShiftExtensionTermsChangedException()

        val existingPending =
            findPendingForUpdate(
                connection = connection,
                venueId = command.venueId,
                tableSessionId = command.tableSessionId,
                tabId = command.tabId,
            )
        if (existingPending != null) {
            if (existingPending.orderId != command.orderId) throw VisitGenerationChangedException()
            if (!existingPending.matchesTerms(confirmed)) throw VisitGenerationChangedException()
            registerRequestKey(connection, command, normalizedIdempotencyKey, normalizedComment, existingPending.id)
            return existingPending
        }

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

        val requestedUntil = command.currentOrderableUntil.plus(Duration.ofMinutes(settings.durationMinutes.toLong()))
        val requestId =
            insertRequest(
                connection = connection,
                command = command,
                settings = settings,
                priceMinor = confirmed.priceMinor,
                currentOrderableUntil = command.currentOrderableUntil,
                requestedUntil = requestedUntil,
                idempotencyKey = normalizedIdempotencyKey,
                comment = normalizedComment,
                now = now,
            )
        registerRequestKey(connection, command, normalizedIdempotencyKey, normalizedComment, requestId)
        return loadRequest(connection, command.venueId, requestId) ?: throw DatabaseUnavailableException()
    }

    private fun ensureRequestingActorIsTabMember(
        connection: Connection,
        command: CreateShiftExtensionRequestCommand,
    ) {
        val isMember =
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
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, command.tabId)
                statement.setLong(2, command.venueId)
                statement.setLong(3, command.tableSessionId)
                statement.setLong(4, command.requestedByUserId)
                statement.executeQuery().use { result -> result.next() }
            }
        if (!isMember) {
            throw ForbiddenException("Tab access denied")
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
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        lockSeatingVenue(connection, venueId)
                        val decision =
                            loadRequestForDecision(connection, venueId, requestId)
                                ?: throw NotFoundException()
                        val request = decision.request
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
                        if (decision.orderStatus != "ACTIVE") {
                            throw InvalidInputException(
                                "Заказ уже завершён. Продление не добавлено. Обновите список запросов.",
                            )
                        }
                        requirePaidWindow(connection, venueId, request.tableSessionId)
                        val settings =
                            loadSettings(connection, venueId, forUpdate = true)
                                ?: throw NotFoundException("Extension settings not found")
                        ensureExtensionLimit(connection, settings, request.tableSessionId)
                        val now = Instant.now()
                        val previousDeadline =
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
                        if (!chargeInserted) throw InvalidInputException("Начисление для этой заявки уже существует.")
                        val newDeadline = previousDeadline.plus(Duration.ofMinutes(request.durationMinutes.toLong()))
                        extendSession(connection, request, newDeadline, now)
                        decideRequest(
                            connection = connection,
                            venueId = venueId,
                            requestId = requestId,
                            status = ShiftExtensionRequestStatus.APPROVED,
                            actorUserId = actorUserId,
                            reasonText = null,
                            serviceStart = previousDeadline,
                            serviceEnd = newDeadline,
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
                            loadRequestForDecision(connection, venueId, requestId)?.request
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
    ): ShiftExtensionSettings? = loadSettings(connection, venueId)?.takeIf { it.enabled }

    private fun loadSettings(
        connection: Connection,
        venueId: Long,
        forUpdate: Boolean = false,
    ): ShiftExtensionSettings? =
        connection.prepareStatement(
            """
            SELECT venue_id, enabled, duration_minutes, price_minor, currency, max_extensions_per_session, updated_at
            FROM shift_extension_settings
            WHERE venue_id = ?
            """.trimIndent() + if (forUpdate) " FOR UPDATE" else "",
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                ShiftExtensionSettings(
                    venueId = rs.getLong("venue_id"),
                    enabled = rs.getBoolean("enabled"),
                    durationMinutes = rs.getInt("duration_minutes"),
                    priceMinor = rs.getLong("price_minor").let { if (rs.wasNull()) null else it },
                    currency = rs.getString("currency"),
                    maxExtensionsPerSession =
                        rs.getInt(
                            "max_extensions_per_session",
                        ).let { if (rs.wasNull()) null else it },
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                )
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
                updated_at = GREATEST(CURRENT_TIMESTAMP, updated_at + INTERVAL '0.000001' SECOND)
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

    private data class LockedDecisionRequest(
        val request: ShiftExtensionRequestRecord,
        val orderStatus: String,
    )

    private fun loadRequestForDecision(
        connection: Connection,
        venueId: Long,
        requestId: Long,
    ): LockedDecisionRequest? {
        val context = loadRequest(connection, venueId, requestId) ?: return null
        // Match guest ordering: session -> tab -> order -> request. Never hold the request while waiting for context.
        // NO KEY UPDATE still excludes context writers but permits FK checks by an order closure holding the order.
        val contextLock =
            if (connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)) {
                "FOR NO KEY UPDATE"
            } else {
                "FOR UPDATE"
            }
        for ((table, id) in listOf("table_sessions" to context.tableSessionId, "tab" to context.tabId)) {
            val exists =
                connection.prepareStatement(
                    "SELECT id FROM $table WHERE id = ? AND venue_id = ? $contextLock",
                ).use { statement ->
                    statement.setLong(1, id)
                    statement.setLong(2, venueId)
                    statement.executeQuery().use { it.next() }
                }
            if (!exists) return null
        }
        val orderStatus =
            connection.prepareStatement(
                "SELECT status FROM orders WHERE id = ? AND venue_id = ? FOR UPDATE",
            ).use { statement ->
                statement.setLong(1, context.orderId)
                statement.setLong(2, venueId)
                statement.executeQuery().use { if (it.next()) it.getString("status") else null }
            } ?: return null
        val request = loadRequestForUpdate(connection, venueId, requestId) ?: return null
        return LockedDecisionRequest(request, orderStatus)
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
    ): Instant {
        ensureVenuePublished(connection, venueId)
        return connection.prepareStatement(
            """
            SELECT ts.expires_at
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
            statement.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp(1).toInstant() else null }
        } ?: throw NotFoundException("Active table order context not found")
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
            throw InvalidInputException("Достигнут лимит продлений для этого посещения стола.")
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
        newDeadline: Instant,
        now: Instant,
    ) {
        val updated =
            connection.prepareStatement(
                """
                UPDATE table_sessions
                SET last_activity_at = ?,
                    expires_at = ?
                WHERE id = ?
                  AND venue_id = ?
                  AND status = 'ACTIVE'
                  AND ended_at IS NULL
                  AND expires_at > ?
                """.trimIndent(),
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.setTimestamp(2, Timestamp.from(newDeadline))
                statement.setLong(3, request.tableSessionId)
                statement.setLong(4, request.venueId)
                statement.setTimestamp(5, Timestamp.from(now))
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
        serviceStart: Instant? = null,
        serviceEnd: Instant? = null,
        now: Instant,
    ) {
        connection.prepareStatement(
            """
            UPDATE shift_extension_requests
            SET status = ?,
                decided_by_user_id = ?,
                decided_at = ?,
                reject_reason = ?,
                updated_at = ?,
                current_orderable_until = COALESCE(?, current_orderable_until),
                requested_until = COALESCE(?, requested_until)
            WHERE id = ?
              AND venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, status.dbValue)
            statement.setLong(2, actorUserId)
            statement.setTimestamp(3, Timestamp.from(now))
            statement.setNullableString(4, reasonText)
            statement.setTimestamp(5, Timestamp.from(now))
            statement.setTimestamp(6, serviceStart?.let(Timestamp::from))
            statement.setTimestamp(7, serviceEnd?.let(Timestamp::from))
            statement.setLong(8, requestId)
            statement.setLong(9, venueId)
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

internal fun requireShiftExtensionKey(raw: String?): String {
    val normalized = raw?.trim().orEmpty()
    if (normalized.isBlank()) throw VisitVersionRequiredException()
    if (normalized.any { it.isISOControl() }) throw InvalidInputException("Invalid idempotencyKey")
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

private fun ShiftExtensionRequestRecord.matchesTerms(terms: ShiftExtensionTerms): Boolean =
    durationMinutes == terms.durationMinutes && priceMinor == terms.priceMinor && currency == terms.currency
