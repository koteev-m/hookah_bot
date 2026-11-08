package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.SeatingConflictException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

internal fun Connection.write(
    sql: String,
    vararg args: Any?,
): Int =
    prepareStatement(sql).use { s ->
        args.forEachIndexed { i, value -> s.setObject(i + 1, if (value is Instant) Timestamp.from(value) else value) }
        s.executeUpdate()
    }

internal fun Connection.insert(
    sql: String,
    vararg args: Any?,
): Long =
    prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { s ->
        args.forEachIndexed { i, value -> s.setObject(i + 1, if (value is Instant) Timestamp.from(value) else value) }
        s.executeUpdate()
        s.generatedKeys.use { rs ->
            check(rs.next())
            rs.getLong(1)
        }
    }

internal fun <T> Connection.rows(
    sql: String,
    vararg args: Any?,
    map: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { s ->
        args.forEachIndexed { i, value -> s.setObject(i + 1, if (value is Instant) Timestamp.from(value) else value) }
        s.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

internal fun ResultSet.longOrNull(key: String): Long? = getLong(key).let { if (wasNull()) null else it }

internal fun ResultSet.intOrNull(key: String): Int? = getInt(key).let { if (wasNull()) null else it }

internal fun ResultSet.instant(key: String): Instant? = getTimestamp(key)?.toInstant()

internal fun conflict(message: String): Nothing = throw SeatingConflictException(message)

internal fun invalid(message: String): Nothing = throw InvalidInputException(message)

/** Canonical order: venue -> seating rules -> membership/booking -> sorted tables -> session -> tab -> order. */
internal fun lockSeatingRules(
    c: Connection,
    venue: Long,
): SeatingRules {
    lockSeatingVenue(c, venue)
    val pg = c.metaData.databaseProductName == "PostgreSQL"
    if (pg) {
        c.write("INSERT INTO venue_seating_rules(venue_id) VALUES (?) ON CONFLICT DO NOTHING", venue)
    } else if (c.rows(
            "SELECT venue_id FROM venue_seating_rules WHERE venue_id = ?",
            venue,
        ) { it.getLong(1) }.isEmpty()
    ) {
        c.write("INSERT INTO venue_seating_rules(venue_id) VALUES (?)", venue)
    }
    return c.rows("SELECT * FROM venue_seating_rules WHERE venue_id = ? FOR UPDATE", venue, map = ::rulesRow)
        .singleOrNull() ?: throw NotFoundException()
}

internal fun rulesRow(rs: ResultSet) =
    SeatingRules(
        rs.getLong("revision"),
        rs.getBoolean("use_plan"),
        rs.getBoolean("guest_table_choice"),
        rs.getBoolean("limit_duration"),
        rs.getInt("duration_minutes"),
        rs.getInt("buffer_minutes"),
    )

internal fun readSeatingRules(
    c: Connection,
    venue: Long,
): SeatingRules =
    c.rows("SELECT * FROM venue_seating_rules WHERE venue_id = ?", venue, map = ::rulesRow)
        .singleOrNull() ?: SeatingRules()

internal fun authorizeSeating(
    c: Connection,
    venue: Long,
    actor: Long,
    settings: Boolean = false,
) {
    val role =
        c.rows("SELECT role FROM venue_members WHERE venue_id = ? AND user_id = ? FOR UPDATE", venue, actor) {
            it.getString(1)
        }.singleOrNull()
    if (role !in setOf("OWNER", "MANAGER", "ADMIN", "STAFF") || settings && role == "STAFF") throw ForbiddenException()
}

internal fun seatingZone(
    c: Connection,
    venue: Long,
): ZoneId {
    val id = c.rows("SELECT timezone FROM venue_settings WHERE venue_id = ?", venue) { it.getString(1) }.singleOrNull()
    // Same documented venue default as booking display; never the server or browser timezone.
    return if (id.isNullOrBlank()) ZoneId.of("Europe/Moscow") else ZoneId.of(id)
}

internal fun localInstant(
    value: String?,
    zone: ZoneId,
): Instant {
    val local =
        value?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            ?: invalid("Укажите дату и время в часовом поясе заведения.")
    val offsets = zone.rules.getValidOffsets(local)
    if (offsets.size != 1) invalid("Это время пропущено или повторяется при переводе часов. Выберите другое время.")
    return local.toInstant(offsets.single())
}

internal data class ServiceWindow(val date: LocalDate, val start: Instant, val end: Instant?, val boundary: Instant)

internal fun serviceWindow(
    c: Connection,
    venue: Long,
    start: Instant,
    zone: ZoneId,
): ServiceWindow {
    val localDate = start.atZone(zone).toLocalDate()
    for (date in listOf(localDate, localDate.minusDays(1))) {
        val override =
            c.rows(
                """
                SELECT opens_at, closes_at, is_closed FROM venue_booking_hours_overrides WHERE venue_id = ? AND
                service_date = ?
                """.trimIndent(),
                venue,
                java.sql.Date.valueOf(date),
            ) { Triple(it.getTime(1).toLocalTime(), it.getTime(2).toLocalTime(), it.getBoolean(3)) }.singleOrNull()
        val hours =
            override ?: c.rows(
                """
                SELECT opens_at, closes_at, is_closed FROM venue_booking_hours WHERE venue_id = ? AND weekday = ?
                """.trimIndent(),
                venue, date.dayOfWeek.value,
            ) { rs ->
                Triple(rs.getTime(1).toLocalTime(), rs.getTime(2).toLocalTime(), rs.getBoolean(3))
            }.singleOrNull() ?: continue
        if (hours.third) continue
        val (opens, closes) = hours
        val a = date.atTime(opens).atZone(zone).toInstant()
        val b = (if (closes > opens) date else date.plusDays(1)).atTime(closes).atZone(zone).toInstant()
        if (start >= a && start < b) return ServiceWindow(date, a, if (opens == closes) null else b, b)
    }
    conflict("Заведение закрыто или график не настроен на выбранное время. Выберите другой интервал.")
}

internal data class AgreedInterval(
    val start: Instant,
    val end: Instant?,
    val duration: Int?,
    val buffer: Int,
    val date: LocalDate,
)

internal fun agreedInterval(
    c: Connection,
    venue: Long,
    start: Instant,
    end: Instant?,
    rules: SeatingRules,
    explicitShort: Boolean = false,
    allowOpenEnd: Boolean = false,
): AgreedInterval {
    val zone = seatingZone(c, venue)
    val window = serviceWindow(c, venue, start, zone)
    val standard = if (rules.limitDuration) start.plusSeconds(rules.durationMinutes * 60L) else window.end
    val resolved = end ?: standard
    if (resolved == null && !allowOpenEnd) {
        conflict(
            "При круглосуточной работе без лимита согласуйте окончание с заведением. " +
                "Обычная заявка не гарантирует стол.",
        )
    }
    if (resolved != null) {
        if (java.time.Duration.between(start, resolved).toDays() > 31) {
            invalid(
                "Согласованный визит не может превышать 31 день.",
            )
        }
        if (resolved <= start) invalid("Окончание должно быть позже начала.")
        if (standard != null && rules.limitDuration && resolved < standard && !explicitShort) {
            conflict("Для сокращённого визита явно укажите окончание и подтвердите, что гости предупреждены.")
        }
        if (window.end != null && resolved > window.end) {
            conflict(
                "Весь визит должен помещаться в рабочий интервал заведения.",
            )
        }
        if (window.end == null) {
            var cursor = window.boundary
            while (cursor < resolved) {
                val next = serviceWindow(c, venue, cursor, zone)
                if (next.boundary <= cursor) conflict("Не удалось проверить график визита.")
                if (next.end != null && resolved > next.end) conflict("Визит пересекает закрытое время.")
                cursor = next.boundary
            }
        }
    }
    return AgreedInterval(
        start,
        resolved,
        if (rules.limitDuration) rules.durationMinutes else null,
        rules.bufferMinutes,
        window.date,
    )
}

internal fun lockSeatingVenue(
    c: Connection,
    venue: Long,
) {
    val lock = if (c.metaData.databaseProductName == "PostgreSQL") "FOR NO KEY UPDATE" else "FOR UPDATE"
    if (c.rows("SELECT id FROM venues WHERE id = ? $lock", venue) { it.getLong(1) }.isEmpty()) throw NotFoundException()
}

internal fun lockSeatingContext(
    c: Connection,
    sessionId: Long,
) {
    // Context writers follow session -> tabs -> orders. An order closure can hold the order
    // while checking a visit FK to session/tab: exclude writers without blocking those KEY SHARE checks.
    val lock = if (c.metaData.databaseProductName == "PostgreSQL") "FOR NO KEY UPDATE" else "FOR UPDATE"
    c.rows("SELECT id FROM table_sessions WHERE id = ? $lock", sessionId) { it.getLong(1) }
    c.rows("SELECT id FROM tab WHERE table_session_id = ? ORDER BY id $lock", sessionId) { it.getLong(1) }
}

internal fun <T> seatingPolicyTransaction(
    ds: javax.sql.DataSource,
    venue: Long,
    block: (Connection) -> T,
): T =
    ds.connection.use { c ->
        c.autoCommit = false
        try {
            lockSeatingVenue(c, venue)
            block(c).also { c.commit() }
        } catch (e: Exception) {
            c.rollback()
            throw e
        }
    }
