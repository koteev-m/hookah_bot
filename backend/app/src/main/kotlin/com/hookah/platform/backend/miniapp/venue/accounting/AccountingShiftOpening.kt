package com.hookah.platform.backend.miniapp.venue.accounting

import com.hookah.platform.backend.api.AccountingConflictException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.Statement
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Called only by authorized writes, on their transaction, before taking order locks. */
internal class AccountingShiftOpening(
    private val clock: Clock,
    private val audit: TransactionalAuditLogWriter = AuditLogRepository(null),
) {
    fun lockVenue(
        c: Connection,
        venueId: Long,
    ) {
        // NO KEY UPDATE serializes writers without conflicting with existing FK references.
        val lock = if (c.metaData.databaseProductName == "PostgreSQL") "FOR NO KEY UPDATE" else "FOR UPDATE"
        c.prepareStatement("SELECT id FROM venues WHERE id = ? $lock").use { s ->
            s.setLong(1, venueId)
            s.executeQuery().use { check(it.next()) }
        }
    }

    fun open(
        c: Connection,
        venueId: Long,
        actor: Long,
        trigger: String? = null,
    ): Long {
        check(!c.autoCommit) { "Opening a shift requires the sale transaction" }
        lockVenue(c, venueId)
        val existing =
            c.prepareStatement(
                "SELECT id FROM venue_accounting_shifts WHERE venue_id = ? AND status = 'OPEN'",
            ).use { s ->
                s.setLong(1, venueId)
                s.executeQuery().use { if (it.next()) it.getLong(1) else null }
            }
        if (existing != null) {
            if (trigger == null) throw AccountingConflictException("В заведении уже есть открытая учётная смена.")
            return existing
        }
        val zone =
            c.prepareStatement("SELECT timezone FROM venue_settings WHERE venue_id = ?").use { s ->
                s.setLong(1, venueId)
                s.executeQuery().use { if (it.next()) it.getString(1) else null }
            }?.takeIf { it.isNotBlank() } ?: VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE
        if (runCatching { ZoneId.of(zone) }.isFailure) {
            throw InvalidInputException("Проверьте часовой пояс заведения в настройках.")
        }
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val id =
            c.prepareStatement(
                "INSERT INTO venue_accounting_shifts (venue_id, status, open_slot, timezone, opened_at) " +
                    "VALUES (?, 'OPEN', 1, ?, ?)",
                Statement.RETURN_GENERATED_KEYS,
            ).use { s ->
                s.setLong(1, venueId)
                s.setString(2, zone)
                s.setObject(3, now)
                s.executeUpdate()
                s.generatedKeys.use {
                    check(it.next())
                    it.getLong(1)
                }
            }
        // Preserve legacy active-order adoption; closed historical orders are never reassigned.
        val active =
            c.prepareStatement(
                "SELECT id FROM orders WHERE venue_id = ? AND status = 'ACTIVE' " +
                    "AND accounting_shift_id IS NULL ORDER BY id FOR UPDATE",
            ).use { s ->
                s.setLong(1, venueId)
                s.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
            }
        active.forEach { orderId ->
            c.prepareStatement("UPDATE orders SET accounting_shift_id = ? WHERE id = ?").use { s ->
                s.setLong(1, id)
                s.setLong(2, orderId)
                s.executeUpdate()
            }
        }
        val action = if (trigger == null) "OPEN" else "AUTO_OPEN"
        val payload =
            buildJsonObject {
                put("adoptedOrders", active.size)
                if (trigger != null) put("trigger", trigger)
            }
        c.prepareStatement(
            "INSERT INTO venue_accounting_events (shift_id, sale_id, actor_user_id, created_at, action, " +
                "reason_text, old_json, new_json) VALUES (?, NULL, ?, ?, ?, NULL, 'null', ?)",
        ).use { s ->
            s.setLong(1, id)
            s.setLong(2, actor)
            s.setObject(3, now)
            s.setString(4, action)
            s.setString(5, Json.encodeToString(active))
            s.executeUpdate()
        }
        // actor is the real initiator (including a Guest), never an invented staff/system identity.
        audit.appendJson(c, actor, "VENUE_ACCOUNTING_$action", "venue_accounting_shift", id, payload)
        return id
    }
}
