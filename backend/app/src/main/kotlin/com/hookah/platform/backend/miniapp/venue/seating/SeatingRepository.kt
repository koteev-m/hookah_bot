package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal val seatingJson =
    Json {
        encodeDefaults = true
        explicitNulls = true
    }

internal fun seatingDigest(bytes: ByteArray): String =
    MessageDigest.getInstance(
        "SHA-256",
    ).digest(bytes).joinToString("") {
        "%02x".format(it)
    }

class SeatingRepository(
    private val dataSource: DataSource?,
    private val audit: TransactionalAuditLogWriter = AuditLogRepository(dataSource),
    private val clock: Clock = Clock.systemUTC(),
    private val checkpoint: (String, Connection) -> Unit = { _, _ -> },
) {
    internal suspend fun <T> transaction(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            val ds = dataSource ?: throw DatabaseUnavailableException()
            ds.connection.use { c ->
                c.autoCommit = false
                try {
                    block(c).also { c.commit() }
                } catch (e: Exception) {
                    c.rollback()
                    throw e
                }
            }
        }

    suspend fun state(
        venue: Long,
        actor: Long,
    ): HallState =
        transaction { c ->
            authorizeSeating(c, venue, actor)
            state(c, venue)
        }

    internal fun state(
        c: Connection,
        venue: Long,
    ): HallState {
        val bookings = bookings(c, venue)
        val rules = readSeatingRules(c, venue)
        val seatings = currentSeatings(c, venue).associateBy { it.tableId }
        val tables =
            tables(c, venue).map { table ->
                val seat = seatings[table.id]
                val reconciliation = seat == null && needsReconciliation(c, table.id)
                val next =
                    bookings
                        .filter { it.tableId == table.id && it.status != "SEATED" }
                        .minByOrNull { it.scheduledAt }
                val overdue = seat?.agreedEndAt?.let { Instant.parse(it) <= clock.instant() } == true
                val preparing = preparationUntil(c, venue, table.id, clock.instant(), null, rules.bufferMinutes)
                table.copy(
                    state =
                        when {
                            !table.active -> "DISABLED"
                            reconciliation -> "RECONCILE"
                            overdue -> "OVERDUE"
                            seat != null -> "OCCUPIED"
                            preparing != null -> "PREPARING"
                            else -> "FREE"
                        },
                    reason =
                        if (reconciliation) {
                            "Требует сверки: найден прежний сеанс или активный заказ."
                        } else if (overdue) {
                            "Согласованное время прошло. Подтвердите освобождение или продлите визит."
                        } else if (seat == null && preparing != null) {
                            preparationReason(c, venue, preparing)
                        } else {
                            null
                        },
                    seating = seat,
                    nextBooking = next,
                )
            }
        return HallState(
            venue,
            seatingZone(c, venue).id,
            readSeatingRules(c, venue),
            halls(c, venue),
            tables,
            bookings,
            bookings.count { it.tableId == null && it.status in reservingStatuses },
        )
    }

    suspend fun availability(
        venue: Long,
        startValue: String?,
        party: Int,
    ): TableAvailability =
        transaction { c ->
            val rules = readSeatingRules(c, venue)
            val zone = seatingZone(c, venue)
            if (!rules.usePlan || !rules.guestTableChoice) {
                return@transaction TableAvailability(venue, zone.id, false, null, null, null, emptyList(), emptyList())
            }
            val unresolved = bookings(c, venue).any { it.tableId == null && it.status in reservingStatuses }
            if (unresolved) {
                return@transaction TableAvailability(
                    venue,
                    zone.id,
                    true,
                    null,
                    null,
                    "Заведение уточняет размещение ранее принятых броней. Свяжитесь с заведением для согласования.",
                    emptyList(),
                    emptyList(),
                )
            }
            val start = localInstant(startValue, zone)
            val window = serviceWindow(c, venue, start, zone)
            if (!rules.limitDuration && window.end == null) {
                return@transaction TableAvailability(
                    venue,
                    zone.id,
                    false,
                    start.toString(),
                    null,
                    "Круглосуточная работа без лимита: отправьте обычную заявку и согласуйте " +
                        "окончание с заведением. Конкретный стол не гарантируется.",
                    emptyList(),
                    emptyList(),
                )
            }
            val interval = agreedInterval(c, venue, start, null, rules)
            val publicTables =
                tables(c, venue).map { table ->
                    val prepared =
                        table.layout != null &&
                            halls(c, venue).any { hall ->
                                hall.id == table.hallId && hall.active && hall.planId != null
                            }
                    val reason =
                        if (!prepared) {
                            "Заведение уточняет схему и размещение этого стола."
                        } else {
                            availabilityReason(
                                c,
                                venue,
                                table.id,
                                party,
                                interval,
                            )
                        }
                    table.copy(
                        state = if (reason == null) "AVAILABLE" else "UNAVAILABLE",
                        reason = reason,
                        seating = null,
                        nextBooking = null,
                        revision = 0,
                    )
                }
            TableAvailability(
                venue,
                zone.id,
                true,
                start.toString(),
                interval.end?.toString(),
                null,
                halls(c, venue).filter { it.active },
                publicTables.filter { it.active },
            )
        }

    suspend fun command(
        venue: Long,
        actor: Long,
        command: SeatingCommand,
        bookingTimeChanged: ((Connection, Long) -> Unit)? = null,
    ): SeatingResult = executeCommand(venue, actor, command, bookingTimeChanged)

    internal suspend fun seatBookingArrival(
        venue: Long,
        actor: Long,
        bookingId: Long,
    ): SeatingResult =
        executeCommand(
            venue,
            actor,
            SeatingCommand(
                requestId = UUID.nameUUIDFromBytes("seat:$bookingId:$actor".toByteArray()).toString(),
                action = "SEAT",
                bookingId = bookingId,
            ),
            legacyBookingArrival = true,
        )

    private suspend fun executeCommand(
        venue: Long,
        actor: Long,
        command: SeatingCommand,
        bookingTimeChanged: ((Connection, Long) -> Unit)? = null,
        legacyBookingArrival: Boolean = false,
    ): SeatingResult =
        transaction { c ->
            checkpoint("BEFORE_LOCK", c)
            val settings = command.action in setOf("HALL", "TABLE", "RULES", "PLAN")
            val rules = lockSeatingRules(c, venue)
            authorizeSeating(c, venue, actor, settings)
            listOfNotNull(
                command.bookingId,
                command.id.takeIf { command.action == "ASSIGN_BOOKING" },
            ).distinct().sorted().forEach {
                    id ->
                c.rows("SELECT id FROM bookings WHERE id = ? AND venue_id = ? FOR UPDATE", id, venue) { it.getLong(1) }
            }
            checkpoint("LOCKED", c)
            idempotent(c, venue, actor, command) {
                val result =
                    when (command.action) {
                        "HALL" -> saveHall(c, venue, command)
                        "TABLE" -> saveTable(c, venue, command)
                        "RULES" -> saveRules(c, venue, rules, command)
                        "MANUAL_BOOKING" -> createBooking(c, venue, null, actor, command, rules)
                        "ASSIGN_BOOKING" -> assignBooking(c, venue, actor, command, rules, bookingTimeChanged)
                        "SEAT" -> {
                            // Legacy arrival has no client version. Resolve it under the same booking lock;
                            // keep its original payload stable for exact replay of an already committed arrival.
                            val arrival =
                                if (legacyBookingArrival) {
                                    val booking =
                                        bookings(c, venue).singleOrNull { it.id == command.bookingId }
                                            ?: throw NotFoundException()
                                    command.copy(revision = booking.revision)
                                } else {
                                    command
                                }
                            seat(c, venue, actor, arrival, rules)
                        }
                        "EXTEND" -> extend(c, venue, command, rules)
                        "MOVE" -> move(c, venue, actor, command, rules)
                        "RELEASE" -> release(c, venue, command)
                        "PLAN" -> savePlan(c, venue, command)
                        else -> invalid("Неизвестная операция.")
                    }
                audit.appendJson(
                    c,
                    actor,
                    "SEATING_${command.action}",
                    "venue",
                    venue,
                    buildJsonObject { put("id", result.id) },
                )
                checkpoint("BEFORE_COMMIT", c)
                result
            }
        }

    suspend fun guestBooking(
        venue: Long,
        actor: Long,
        command: SeatingCommand,
    ): SeatingResult =
        transaction { c ->
            checkpoint("BEFORE_LOCK", c)
            val rules = lockSeatingRules(c, venue)
            checkpoint("LOCKED", c)
            if (command.action != "GUEST_BOOKING") invalid("Неизвестная операция гостевого бронирования.")
            idempotent(c, venue, actor, command) {
                if (!rules.usePlan || !rules.guestTableChoice) {
                    conflict(
                        "Выбор стола выключен. Обновите форму и отправьте обычную заявку.",
                    )
                }
                if (bookings(c, venue).any { it.tableId == null && it.status in reservingStatuses }) {
                    conflict("Сначала заведение должно назначить столы для ранее принятых броней.")
                }
                val result = createBooking(c, venue, actor, actor, command, rules)
                audit.appendJson(
                    c,
                    actor,
                    "SEATING_GUEST_BOOKING",
                    "venue",
                    venue,
                    buildJsonObject { put("id", result.id) },
                )
                result
            }
        }

    private fun idempotent(
        c: Connection,
        venue: Long,
        actor: Long,
        command: SeatingCommand,
        execute: () -> SeatingResult,
    ): SeatingResult {
        if (runCatching {
                UUID.fromString(command.requestId).toString()
            }.getOrNull() != command.requestId
        ) {
            invalid("Нужен ключ повторной операции.")
        }
        val hash = seatingDigest(seatingJson.encodeToString(command).toByteArray())
        val prior =
            c.rows(
                """
                SELECT fingerprint, result_json FROM seating_operations WHERE venue_id = ? AND actor_user_id = ? AND
                request_id = ?
                """.trimIndent(),
                venue,
                actor,
                command.requestId,
            ) { it.getString(1) to it.getString(2) }.singleOrNull()
        if (prior != null) {
            if (hash != prior.first) {
                conflict(
                    "Этот ключ уже использован для другого действия. Проверьте результат предыдущей операции.",
                )
            }
            return seatingJson.decodeFromString(prior.second)
        }
        val result = execute()
        c.write(
            """
            INSERT INTO seating_operations(venue_id, actor_user_id, request_id, fingerprint, result_json) VALUES
            (?, ?, ?, ?, ?)
            """.trimIndent(),
            venue,
            actor,
            command.requestId,
            hash,
            seatingJson.encodeToString(result),
        )
        return result
    }

    private fun saveHall(
        c: Connection,
        venue: Long,
        cmd: SeatingCommand,
    ): SeatingResult {
        val name =
            cmd.name?.trim()?.takeIf {
                it.isNotEmpty() && it.length <= 100
            } ?: invalid("Название зала: от 1 до 100 символов.")
        if (halls(c, venue).any { it.name == name && it.id != cmd.id }) {
            conflict("Зал с таким названием уже существует. Выберите другое название.")
        }
        if (cmd.id == null) {
            return SeatingResult(
                c.insert(
                    "INSERT INTO venue_halls(venue_id, name, is_active) VALUES (?,?,?)",
                    venue,
                    name,
                    cmd.active ?: true,
                ),
            )
        }
        val old = halls(c, venue).singleOrNull { it.id == cmd.id } ?: throw NotFoundException()
        revision(old.revision, cmd.revision)
        if (cmd.active == false) {
            tables(c, venue).filter { it.hallId == old.id }.forEach { table -> requireNoObligations(c, table.id) }
        }
        c.write(
            """
            UPDATE venue_halls SET name = ?, is_active = ?, revision = revision + 1 WHERE id = ? AND venue_id =
            ?
            """.trimIndent(),
            name,
            cmd.active ?: old.active,
            old.id,
            venue,
        )
        return SeatingResult(old.id, old.revision + 1)
    }

    private fun saveTable(
        c: Connection,
        venue: Long,
        cmd: SeatingCommand,
    ): SeatingResult {
        val table = lockTables(c, venue, listOf(cmd.id ?: throw NotFoundException())).single()
        revision(table.revision, cmd.revision)
        if (cmd.number == null || cmd.number <= 0) invalid("Укажите положительный номер стола.")
        if (cmd.capacity != null && cmd.capacity !in 1..1000) {
            invalid(
                "Вместимость: от 1 до 1000 гостей или неизвестна.",
            )
        }
        if (cmd.hallId != null &&
            halls(c, venue).none {
                it.id == cmd.hallId && it.active
            }
        ) {
            invalid("Выберите действующий зал своего заведения.")
        }
        if (cmd.active == false) requireNoObligations(c, table.id)
        val maxParty =
            c.rows(
                """
                SELECT MAX(party_size) FROM bookings WHERE table_id = ? AND status IN
                ('PENDING','CONFIRMED','CHANGED')
                """.trimIndent(),
                table.id,
            ) {
                it.getInt(1)
            }.single()
        val seatedParty = currentSeatings(c, venue).filter { it.tableId == table.id }.maxOfOrNull { it.partySize } ?: 0
        if (maxOf(maxParty, seatedParty) > (cmd.capacity ?: 0)) {
            conflict(
                "Вместимость не может стать меньше действующих обязательств.",
            )
        }
        if (tables(
                c,
                venue,
            ).any { it.id != table.id && it.number == cmd.number }
        ) {
            conflict("Этот номер стола уже занят.")
        }
        cmd.layout?.let { layout ->
            if (cmd.hallId == null ||
                listOf(layout.x, layout.y, layout.width, layout.height, layout.rotation).any {
                    !it.isFinite()
                } ||
                layout.width !in 0.02..1.0 || layout.height !in 0.02..1.0 ||
                layout.x !in 0.0..1.0 || layout.y !in 0.0..1.0 ||
                layout.rotation !in -360.0..360.0 || layout.shape !in setOf("RECTANGLE", "ELLIPSE")
            ) {
                invalid("Проверьте положение, размер и форму стола.")
            }
        }
        c.write(
            """
            UPDATE venue_tables SET table_number = ?, capacity = ?, hall_id = ?, is_active = ?, layout_x = ?,
            layout_y = ?, layout_width = ?, layout_height = ?, layout_rotation = ?, layout_shape = ?,
            seating_revision = seating_revision + 1 WHERE id = ? AND venue_id = ?
            """.trimIndent(),
            cmd.number,
            cmd.capacity,
            cmd.hallId,
            cmd.active ?: table.active,
            cmd.layout?.x,
            cmd.layout?.y,
            cmd.layout?.width,
            cmd.layout?.height,
            cmd.layout?.rotation ?: 0.0,
            cmd.layout?.shape ?: "RECTANGLE",
            table.id,
            venue,
        )
        return SeatingResult(table.id, table.revision + 1)
    }

    private fun saveRules(
        c: Connection,
        venue: Long,
        old: SeatingRules,
        cmd: SeatingCommand,
    ): SeatingResult {
        revision(old.revision, cmd.revision)
        val rules = cmd.rules ?: invalid("Укажите правила посадки.")
        if (rules.durationMinutes !in 15..1440 || rules.bufferMinutes !in 0..240) {
            invalid(
                "Длительность: 15–1440 минут; перерыв: 0–240 минут.",
            )
        }
        if (rules.guestTableChoice && rules.usePlan) {
            if (bookings(c, venue).any {
                    it.tableId == null && it.status in reservingStatuses
                }
            ) {
                conflict("Назначьте столы всем действующим броням перед включением гостевого выбора.")
            }
            val active = tables(c, venue).filter { it.active }
            val halls = halls(c, venue)
            if (active.isEmpty() ||
                active.any {
                    it.capacity == null || it.layout == null ||
                        halls.none {
                                h ->
                            h.id == it.hallId && h.active && h.planId != null
                        }
                }
            ) {
                conflict("Настройте вместимость, зал, изображение и маркеры всех действующих столов.")
            }
            if (c.rows("SELECT COUNT(*) FROM venue_booking_hours WHERE venue_id = ?", venue) {
                    it.getInt(1)
                }.single() == 0
            ) {
                conflict("Сначала настройте рабочее расписание заведения.")
            }
        }
        c.write(
            """
            UPDATE venue_seating_rules SET use_plan = ?, guest_table_choice = ?, limit_duration = ?,
            duration_minutes = ?, buffer_minutes = ?, revision = revision + 1 WHERE venue_id = ?
            """.trimIndent(),
            rules.usePlan,
            rules.guestTableChoice && rules.usePlan,
            rules.limitDuration,
            rules.durationMinutes,
            rules.bufferMinutes,
            venue,
        )
        return SeatingResult(venue, old.revision + 1)
    }

    private fun createBooking(
        c: Connection,
        venue: Long,
        guest: Long?,
        actor: Long,
        cmd: SeatingCommand,
        rules: SeatingRules,
    ): SeatingResult {
        val start = localInstant(cmd.localStart, seatingZone(c, venue))
        if (start <= clock.instant()) invalid("Выберите будущее время бронирования.")
        val party = party(cmd.partySize)
        val table = cmd.tableId
        if (rules.guestTableChoice && table == null) conflict("Назначьте доступный стол для подтверждённой брони.")
        if (guest != null && table == null) invalid("Выберите стол.")
        if (guest != null &&
            tables(c, venue).none {
                it.id == table && it.layout != null &&
                    halls(c, venue).any {
                            hall ->
                        hall.id == it.hallId && hall.active && hall.planId != null
                    }
            }
        ) {
            conflict("Схема стола изменилась. Обновите доступность.")
        }
        val end = cmd.localEnd?.let { localInstant(it, seatingZone(c, venue)) }
        if (guest != null && end != null) invalid("Гостевой интервал задаётся правилами заведения.")
        val interval =
            if (table != null || rules.limitDuration || end != null) {
                agreedInterval(c, venue, start, end, rules, cmd.shorterVisitAcknowledged)
            } else {
                val window = serviceWindow(c, venue, start, seatingZone(c, venue))
                AgreedInterval(start, end ?: window.end, null, rules.bufferMinutes, window.date)
            }
        if (table != null) {
            lockTables(c, venue, listOf(table))
            availabilityReason(c, venue, table, party, interval)?.let(::conflict)
        }
        val name =
            if (guest == null) {
                cmd.name?.trim()?.takeIf {
                    it.isNotBlank() && it.length <= 100
                } ?: invalid("Укажите имя или обозначение гостя.")
            } else {
                null
            }
        if ((cmd.phone?.length ?: 0) > 40 || (cmd.comment?.length ?: 0) > 500) {
            invalid(
                "Телефон: до 40 символов; комментарий: до 500.",
            )
        }
        val hold =
            c.rows("SELECT hold_minutes FROM venue_booking_settings WHERE venue_id = ?", venue) {
                it.getInt(1)
            }.singleOrNull() ?: com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository.DEFAULT_HOLD_MINUTES
        val date = java.sql.Date.valueOf(interval.date)
        val number =
            c.rows(
                """
                SELECT COALESCE(MAX(display_number), 0) + 1 FROM bookings WHERE venue_id = ? AND display_date = ?
                """.trimIndent(),
                venue,
                date,
            ) {
                it.getInt(1)
            }.single()
        val id =
            c.insert(
                """
                INSERT INTO bookings(venue_id, user_id, scheduled_at, party_size, comment, status, display_date,
                display_number, arrival_deadline_at, table_id, agreed_end_at, duration_minutes, buffer_minutes,
                booking_source, created_by_user_id, offline_guest_name, offline_guest_phone, venue_confirmed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                venue,
                guest,
                start,
                party,
                cmd.comment?.trim(),
                if (guest == null) "CONFIRMED" else "PENDING",
                date,
                number,
                start.plusSeconds(
                    hold * 60L,
                ),
                table,
                interval.end,
                interval.duration,
                interval.buffer,
                if (guest == null) "STAFF" else "GUEST",
                actor,
                name,
                cmd.phone?.trim()?.takeIf {
                    it.isNotBlank()
                },
                if (guest == null) clock.instant() else null,
            )
        return SeatingResult(id)
    }

    private fun assignBooking(
        c: Connection,
        venue: Long,
        actor: Long,
        cmd: SeatingCommand,
        rules: SeatingRules,
        bookingTimeChanged: ((Connection, Long) -> Unit)?,
    ): SeatingResult {
        authorizeSeating(c, venue, actor, true)
        val booking = bookings(c, venue).singleOrNull { it.id == cmd.id } ?: throw NotFoundException()
        revision(booking.revision, cmd.revision)
        if (booking.status !in reservingStatuses) conflict("Эта бронь уже завершена.")
        val table = cmd.tableId ?: invalid("Выберите стол.")
        lockTables(c, venue, listOfNotNull(table, booking.tableId))
        val start =
            cmd.localStart?.let { localInstant(it, seatingZone(c, venue)) } ?: Instant.parse(booking.scheduledAt)
        val end =
            cmd.localEnd?.let { localInstant(it, seatingZone(c, venue)) } ?: booking.agreedEndAt?.let { old ->
                Instant.parse(old).plus(Duration.between(Instant.parse(booking.scheduledAt), start))
            }
        val appliedRules = if (booking.agreedEndAt == null) rules else snapshotRules(c, "bookings", booking.id, rules)
        val interval = agreedInterval(c, venue, start, end, appliedRules, cmd.shorterVisitAcknowledged)
        availabilityReason(
            c,
            venue,
            table,
            party(cmd.partySize ?: booking.partySize),
            interval,
            booking.id,
        )?.let(::conflict)
        val old =
            c.rows("SELECT arrival_deadline_at, display_date, display_number FROM bookings WHERE id = ?", booking.id) {
                Triple(
                    it.instant("arrival_deadline_at"),
                    it.getDate("display_date")?.toLocalDate(),
                    it.intOrNull("display_number"),
                )
            }.single()
        val date = java.sql.Date.valueOf(interval.date)
        val number =
            if (old.second == interval.date && old.third != null) {
                old.third
            } else {
                c.rows(
                    "SELECT COALESCE(MAX(display_number),0)+1 FROM bookings WHERE venue_id = ? AND display_date = ?",
                    venue,
                    date,
                ) {
                    it.getInt(1)
                }.single()
            }
        val changed = start != Instant.parse(booking.scheduledAt)
        c.write(
            """
            UPDATE bookings SET table_id = ?, party_size = ?, scheduled_at = ?, agreed_end_at = ?, duration_minutes = ?,
            buffer_minutes = ?, display_date = ?, display_number = ?, arrival_deadline_at = ?, status = ?,
            last_rescheduled_at = ?, last_guest_confirmation_at = CASE WHEN ? THEN NULL ELSE
            last_guest_confirmation_at END, seating_revision = seating_revision + 1, updated_at =
            CURRENT_TIMESTAMP WHERE id = ? AND venue_id = ?
            """.trimIndent(),
            table,
            party(
                cmd.partySize ?: booking.partySize,
            ),
            start, interval.end, interval.duration, interval.buffer, date, number,
            old.first?.plus(Duration.between(Instant.parse(booking.scheduledAt), start)),
            if (changed && booking.source == "GUEST") "CHANGED" else booking.status,
            if (changed) clock.instant() else null,
            changed,
            booking.id,
            venue,
        )
        if (changed && booking.source == "GUEST") bookingTimeChanged?.invoke(c, booking.id)
        return SeatingResult(booking.id, booking.revision + 1)
    }

    private fun seat(
        c: Connection,
        venue: Long,
        actor: Long,
        cmd: SeatingCommand,
        rules: SeatingRules,
    ): SeatingResult {
        cmd.bookingId?.let { id ->
            val currentRevision =
                c.rows(
                    "SELECT seating_revision FROM bookings WHERE id = ? AND venue_id = ?",
                    id, venue,
                ) { it.getLong(1) }.singleOrNull() ?: throw NotFoundException()
            revision(currentRevision, cmd.revision)
        }
        val booking =
            cmd.bookingId?.let {
                    id ->
                bookings(c, venue).singleOrNull { it.id == id } ?: throw NotFoundException()
            }
        if (booking != null && booking.status != "CONFIRMED") {
            conflict(
                "Посадка доступна только для подтверждённой брони.",
            )
        }
        if (booking != null) {
            val changesParty = cmd.partySize != null && cmd.partySize != booking.partySize
            val changesEnd =
                cmd.localEnd != null &&
                    localInstant(cmd.localEnd, seatingZone(c, venue)) != booking.agreedEndAt?.let(Instant::parse)
            if (changesParty || changesEnd) {
                conflict(
                    "Посадка сохраняет число гостей и окончание из брони. " +
                        "Сначала измените условия брони с владельцем или управляющим.",
                )
            }
        }
        val table = cmd.tableId ?: booking?.tableId ?: invalid("Выберите стол для посадки.")
        if (booking?.tableId != null && booking.tableId != table) conflict("Сначала измените назначенный стол в брони.")
        lockTables(c, venue, listOf(table))
        val now = clock.instant()
        val end =
            if (booking?.agreedEndAt != null) {
                Instant.parse(booking.agreedEndAt)
            } else {
                cmd.localEnd?.let {
                    localInstant(it, seatingZone(c, venue))
                }
            }
        val appliedRules = if (booking == null) rules else snapshotRules(c, "bookings", booking.id, rules)
        val interval =
            agreedInterval(c, venue, now, end, appliedRules, booking != null || cmd.shorterVisitAcknowledged, true)
        val party = party(booking?.partySize ?: cmd.partySize)
        val legacy =
            if (cmd.reconcileLegacySession) {
                c.rows(
                    """
                    SELECT s.id FROM table_sessions s WHERE s.venue_id = ? AND s.table_id = ? AND NOT EXISTS (SELECT 1
                    FROM table_seatings x WHERE x.table_session_id = s.id) AND ((s.status = 'ACTIVE' AND s.ended_at IS
                    NULL AND s.expires_at > ?) OR EXISTS (SELECT 1 FROM orders o WHERE o.table_session_id = s.id AND
                    o.status = 'ACTIVE')) ORDER BY s.id FOR UPDATE
                    """.trimIndent(),
                    venue,
                    table,
                    now,
                ) { it.getLong(1) }.let {
                        ids ->
                    if (ids.size != 1) {
                        conflict(
                            "Нельзя однозначно связать старые сеансы. Сначала завершите лишние активные " +
                                "заказы штатным действием.",
                        )
                    }
                    ids.single()
                }
            } else {
                null
            }
        if (legacy != null && c.rows(
                """
                SELECT COUNT(*) FROM orders WHERE table_id = ? AND table_session_id IS NULL AND status = 'ACTIVE'
                """.trimIndent(),
                table,
            ) {
                it.getInt(1)
            }.single() > 0
        ) {
            conflict("Найден заказ без сеанса. Завершите его штатным действием перед сверкой посадки.")
        }
        availabilityReason(c, venue, table, party, interval, booking?.id, reconciledSession = legacy)?.let(::conflict)
        val session =
            legacy ?: c.insert(
                """
                INSERT INTO table_sessions(venue_id, table_id, started_at, last_activity_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?,'ACTIVE')
                """.trimIndent(),
                venue, table, now, now, Instant.parse("9999-12-31T00:00:00Z"),
            )
        if (legacy != null) {
            c.write(
                "UPDATE table_sessions SET status = 'ACTIVE', ended_at = NULL, expires_at = ? WHERE id = ?",
                Instant.parse("9999-12-31T00:00:00Z"),
                legacy,
            )
        }
        val guest =
            booking?.let {
                c.rows("SELECT user_id FROM bookings WHERE id = ?", it.id) { r -> r.longOrNull("user_id") }.single()
            }
        if (booking != null) {
            c.write(
                """
                UPDATE bookings SET status = 'SEATED', seated_at = ?, table_id = ?, agreed_end_at = ?,
                seating_revision = seating_revision + 1 WHERE id = ? AND status = 'CONFIRMED'
                """.trimIndent(),
                now,
                table,
                interval.end,
                booking.id,
            )
        }
        val existingVisit = legacy?.let { reconcileVisit(c, venue, it, booking?.id, guest) }
        if (existingVisit != null && booking != null) {
            c.write(
                """
                UPDATE visits SET booking_id = ?, source = 'MERGED'
                WHERE id = ? AND booking_id IS NULL
                """.trimIndent(),
                booking.id,
                existingVisit,
            )
        }
        val visit =
            existingVisit ?: c.insert(
                """
                INSERT INTO visits(venue_id, user_id, booking_id, table_session_id, source, occurred_at,
                service_date) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                venue,
                guest,
                booking?.id,
                session,
                if (booking == null) "WALK_IN" else "BOOKING_SEATED",
                now,
                java.sql.Date.valueOf(interval.date),
            )
        val id =
            c.insert(
                """
                INSERT INTO table_seatings(venue_id, table_id, visit_id, booking_id, table_session_id, party_size,
                started_at, agreed_end_at, duration_minutes, buffer_minutes, created_by_user_id) VALUES (?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                venue,
                table,
                visit,
                booking?.id,
                session,
                party,
                now,
                interval.end,
                interval.duration,
                interval.buffer,
                actor,
            )
        return SeatingResult(id)
    }

    private fun extend(
        c: Connection,
        venue: Long,
        cmd: SeatingCommand,
        rules: SeatingRules,
    ): SeatingResult {
        val seat = requireSeating(c, venue, cmd)
        lockTables(c, venue, listOf(seat.tableId))
        val end = localInstant(cmd.localEnd, seatingZone(c, venue))
        val oldEnd = seat.agreedEndAt?.let(Instant::parse)
        if (oldEnd != null && end <= oldEnd || end <= clock.instant()) invalid("Укажите более позднее окончание.")
        val interval =
            agreedInterval(
                c,
                venue,
                Instant.parse(seat.startedAt),
                end,
                snapshotRules(c, "table_seatings", seat.id, rules),
                true,
            )
        val tableArrival =
            c.rows(
                "SELECT MAX(moved_at) AS arrived_at FROM table_seating_moves WHERE seating_id = ? AND to_table_id = ?",
                seat.id,
                seat.tableId,
            ) { it.instant("arrived_at") }.single() ?: interval.start
        // Keep the visit's original start and rule snapshots, but do not reoccupy the destination before MOVE.
        availabilityReason(
            c,
            venue,
            seat.tableId,
            seat.partySize,
            interval.copy(start = maxOf(interval.start, tableArrival)),
            seat.bookingId,
            seat.id,
        )?.let(::conflict)
        c.write("UPDATE table_seatings SET agreed_end_at = ?, revision = revision + 1 WHERE id = ?", end, seat.id)
        if (seat.bookingId != null) {
            c.write(
                "UPDATE bookings SET agreed_end_at = ?, seating_revision = seating_revision + 1 WHERE id = ?",
                end,
                seat.bookingId,
            )
        }
        return SeatingResult(seat.id, seat.revision + 1)
    }

    private fun reconcileVisit(
        c: Connection,
        venue: Long,
        session: Long,
        booking: Long?,
        guest: Long?,
    ): Long? {
        val visits =
            c.rows(
                """
                SELECT id, user_id, booking_id, table_session_id FROM visits
                WHERE venue_id = ? AND (table_session_id = ? OR booking_id = ?) ORDER BY id FOR UPDATE
                """.trimIndent(),
                venue,
                session,
                booking,
            ) { rs ->
                LegacyVisit(
                    rs.getLong("id"),
                    rs.longOrNull("user_id"),
                    rs.longOrNull("booking_id"),
                    rs.longOrNull("table_session_id"),
                )
            }
        if (booking == null) {
            if (visits.size > 1) conflict("Требует сверки: в прежнем сеансе несколько визитов. История не изменена.")
            return visits.singleOrNull()?.id
        }
        val exact = visits.filter { it.booking == booking }
        if (exact.size == 1 && exact.single().session == session && exact.single().guest == guest) {
            return exact.single().id
        }
        // NULL guest identity cannot establish that two offline records describe the same person.
        val matching = visits.filter { it.session == session && it.guest == guest && it.booking == null }
        if (exact.isNotEmpty() || guest == null || matching.size != 1) {
            conflict("Требует сверки: связь гостя, брони и прежнего визита не подтверждена. История не изменена.")
        }
        return matching.single().id
    }

    private data class LegacyVisit(val id: Long, val guest: Long?, val booking: Long?, val session: Long?)

    private fun move(
        c: Connection,
        venue: Long,
        actor: Long,
        cmd: SeatingCommand,
        rules: SeatingRules,
    ): SeatingResult {
        val seat = requireSeating(c, venue, cmd)
        val target = cmd.tableId ?: invalid("Выберите новый стол.")
        if (target == seat.tableId) invalid("Выберите другой стол.")
        lockTables(c, venue, listOf(seat.tableId, target))
        val now = clock.instant()
        val end = seat.agreedEndAt?.let(Instant::parse)
        if (end != null && end <= now) conflict("Сначала согласуйте новое окончание просроченного визита.")
        val interval =
            agreedInterval(c, venue, now, end, snapshotRules(c, "table_seatings", seat.id, rules), true, true)
        availabilityReason(c, venue, target, seat.partySize, interval, seat.bookingId, seat.id)?.let(::conflict)
        // Locks follow table -> session -> tab -> order; closed financial snapshots are never rewritten.
        lockSeatingContext(c, seat.tableSessionId)
        c.rows(
            "SELECT id FROM orders WHERE table_session_id = ? ORDER BY id FOR UPDATE",
            seat.tableSessionId,
        ) { it.getLong(1) }
        val calls =
            c.rows(
                """
                SELECT id, table_id FROM staff_calls WHERE venue_id = ? AND table_session_id = ?
                AND status IN ('NEW','ACK') ORDER BY id FOR UPDATE
                """.trimIndent(),
                venue,
                seat.tableSessionId,
            ) { it.getLong(1) to it.getLong(2) }
        for ((call, previousTable) in calls) {
            c.write("UPDATE staff_calls SET table_id = ? WHERE id = ?", target, call)
            audit.appendJson(
                c,
                actor,
                "STAFF_CALL_MOVED",
                "staff_call",
                call,
                buildJsonObject {
                    put("fromTableId", previousTable)
                    put("toTableId", target)
                    put("tableSessionId", seat.tableSessionId)
                },
            )
        }
        c.write("UPDATE table_sessions SET table_id = ? WHERE id = ?", target, seat.tableSessionId)
        c.write(
            "UPDATE orders SET table_id = ? WHERE table_session_id = ? AND status = 'ACTIVE'",
            target,
            seat.tableSessionId,
        )
        c.write("UPDATE table_seatings SET table_id = ?, revision = revision + 1 WHERE id = ?", target, seat.id)
        c.write(
            """
            INSERT INTO table_seating_moves(seating_id, from_table_id, to_table_id, actor_user_id, moved_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            seat.id,
            seat.tableId,
            target,
            actor,
            now,
        )
        if (seat.bookingId != null) {
            c.write(
                "UPDATE bookings SET table_id = ?, seating_revision = seating_revision + 1 WHERE id = ?",
                target,
                seat.bookingId,
            )
        }
        return SeatingResult(seat.id, seat.revision + 1)
    }

    private fun release(
        c: Connection,
        venue: Long,
        cmd: SeatingCommand,
    ): SeatingResult {
        val seat = requireSeating(c, venue, cmd)
        lockTables(c, venue, listOf(seat.tableId))
        lockSeatingContext(c, seat.tableSessionId)
        val orders =
            c.rows(
                "SELECT status FROM orders WHERE table_session_id = ? ORDER BY id FOR UPDATE",
                seat.tableSessionId,
            ) {
                it.getString(1)
            }
        if (orders.any {
                it == "ACTIVE"
            }
        ) {
            conflict(
                "Сначала завершите активный заказ штатным действием. Освобождение не " +
                    "является оплатой и не списывает долг.",
            )
        }
        val calls =
            c.rows(
                "SELECT COUNT(*) FROM staff_calls WHERE table_session_id = ? AND status IN ('NEW','ACK')",
                seat.tableSessionId,
            ) {
                it.getInt(1)
            }.single()
        if (calls > 0) conflict("Сначала завершите активные вызовы сотрудника.")
        val now = clock.instant()
        c.write("UPDATE table_sessions SET status = 'ENDED', ended_at = ? WHERE id = ?", now, seat.tableSessionId)
        c.write(
            "UPDATE tab SET status = 'CLOSED' WHERE table_session_id = ? AND status = 'ACTIVE'",
            seat.tableSessionId,
        )
        c.write(
            "DELETE FROM tab_invite WHERE tab_id IN (SELECT id FROM tab WHERE table_session_id = ?)",
            seat.tableSessionId,
        )
        c.write("UPDATE table_seatings SET released_at = ?, revision = revision + 1 WHERE id = ?", now, seat.id)
        return SeatingResult(seat.id, seat.revision + 1)
    }

    private fun savePlan(
        c: Connection,
        venue: Long,
        cmd: SeatingCommand,
    ): SeatingResult {
        val hall = halls(c, venue).singleOrNull { it.id == cmd.id } ?: throw NotFoundException()
        revision(hall.revision, cmd.revision)
        if (!cmd.confirmPlanReplacement) invalid("Проверьте предпросмотр и подтвердите новую схему.")
        if (c.rows(
                """
                SELECT id FROM hall_plan_media WHERE id = ? AND hall_id = ? AND venue_id = ? AND status = 'READY'
                """.trimIndent(),
                cmd.planId,
                hall.id,
                venue,
            ) {
                it.getString(1)
            }.isEmpty()
        ) {
            throw NotFoundException()
        }
        c.write("UPDATE venue_halls SET plan_id = ?, revision = revision + 1 WHERE id = ?", cmd.planId, hall.id)
        // Replacement must never silently reuse the old image's geometry.
        c.write(
            """
            UPDATE venue_tables SET layout_x = NULL, layout_y = NULL, layout_width = NULL, layout_height = NULL,
            seating_revision = seating_revision + 1 WHERE hall_id = ?
            """.trimIndent(),
            hall.id,
        )
        c.write(
            """
            UPDATE venue_seating_rules SET guest_table_choice = FALSE, revision = revision + 1 WHERE venue_id =
            ?
            """.trimIndent(),
            venue,
        )
        return SeatingResult(hall.id, hall.revision + 1)
    }

    private fun preparationUntil(
        c: Connection,
        venue: Long,
        table: Long,
        start: Instant,
        end: Instant?,
        nextBuffer: Int,
    ): Instant? =
        c.rows(
            """
            SELECT released_at AS freed_at, buffer_minutes FROM table_seatings
            WHERE venue_id = ? AND table_id = ? AND released_at > ?
            UNION ALL
            SELECT m.moved_at AS freed_at, s.buffer_minutes FROM table_seating_moves m
            JOIN table_seatings s ON s.id = m.seating_id
            WHERE s.venue_id = ? AND m.from_table_id = ? AND m.moved_at > ?
            """.trimIndent(),
            venue,
            table,
            start.minusSeconds(240 * 60L),
            venue,
            table,
            start.minusSeconds(240 * 60L),
        ) { rs -> rs.instant("freed_at")!! to rs.getInt("buffer_minutes") }
            .mapNotNull { (freed, buffer) ->
                val until = freed.plusSeconds(maxOf(buffer, nextBuffer) * 60L)
                until.takeIf { start < until && (end == null || end > freed) }
            }.maxOrNull()

    private fun preparationReason(
        c: Connection,
        venue: Long,
        until: Instant,
    ): String =
        "Стол освобождён. Подготовка до ${until.atZone(seatingZone(c, venue)).toLocalDateTime()}. " +
            "Выберите более позднее время или другой стол."

    internal fun availabilityReason(
        c: Connection,
        venue: Long,
        tableId: Long,
        party: Int,
        interval: AgreedInterval,
        skipBooking: Long? = null,
        skipSeating: Long? = null,
        reconciledSession: Long? = null,
    ): String? {
        val table = tables(c, venue).singleOrNull { it.id == tableId } ?: return "Стол не найден в этом заведении."
        if (!table.active || table.hallId != null &&
            halls(c, venue).none {
                it.id == table.hallId && it.active
            }
        ) {
            return "Стол или зал отключён."
        }
        if (table.capacity == null) {
            return "Вместимость не настроена."
        }
        if (party > table.capacity) {
            return "Недостаточно мест. Выберите более вместительный стол."
        }
        val end = interval.end ?: Instant.MAX
        val active = currentSeatings(c, venue).filter { it.tableId == tableId && it.id != skipSeating }
        for (seat in active) {
            val seatEnd = seat.agreedEndAt?.let(Instant::parse)
            if (seatEnd == null || seatEnd <= clock.instant() || interval.start <= clock.instant()) {
                return "Стол занят. Требуется подтверждённое освобождение или другой стол."
            }
            val previousBuffer = snapshotRules(c, "table_seatings", seat.id, SeatingRules()).bufferMinutes
            val buffer = maxOf(interval.buffer, previousBuffer) * 60L
            if (interval.start < seatEnd.plusSeconds(buffer) &&
                end > Instant.parse(seat.startedAt).minusSeconds(buffer)
            ) {
                return "Интервал пересекается с текущей посадкой и перерывом."
            }
        }
        preparationUntil(c, venue, tableId, interval.start, interval.end, interval.buffer)?.let {
            return preparationReason(c, venue, it)
        }
        if (active.isEmpty() && skipSeating == null && reconciledSession == null && needsReconciliation(c, tableId)) {
            return "Требует сверки: прежний сеанс или заказ ещё не завершён."
        }
        val reserved =
            c.rows(
                """
                SELECT id, scheduled_at, agreed_end_at, buffer_minutes FROM bookings WHERE venue_id = ? AND table_id
                = ? AND status IN ('PENDING','CONFIRMED','CHANGED')
                """.trimIndent(),
                venue,
                tableId,
            ) {
                Triple(
                    it.getLong(1),
                    it.instant("scheduled_at")!!,
                    it.instant("agreed_end_at") to it.getInt("buffer_minutes"),
                )
            }
        for ((id, start, tail) in reserved) {
            if (id == skipBooking) continue
            val gap = maxOf(interval.buffer, tail.second) * 60L
            if (tail.first == null || interval.start < tail.first!!.plusSeconds(gap) && end > start.minusSeconds(gap)) {
                return "Стол зарезервирован на этот интервал. Выберите другой стол или другое время."
            }
        }
        return null
    }

    private fun requireSeating(
        c: Connection,
        venue: Long,
        cmd: SeatingCommand,
    ): SeatingDto {
        val seat =
            currentSeatings(c, venue).singleOrNull {
                it.id == cmd.id
            } ?: conflict("Посадка уже завершена или недоступна. Обновите список.")
        revision(seat.revision, cmd.revision)
        return seat
    }

    private fun lockTables(
        c: Connection,
        venue: Long,
        ids: List<Long>,
    ): List<HallTableDto> {
        ids.distinct().sorted().forEach { id ->
            if (c.rows("SELECT id FROM venue_tables WHERE venue_id = ? AND id = ? FOR UPDATE", venue, id) {
                    it.getLong(1)
                }.isEmpty()
            ) {
                throw NotFoundException()
            }
        }
        return tables(c, venue).filter { it.id in ids }
    }

    private fun requireNoObligations(
        c: Connection,
        table: Long,
    ) {
        if (c.rows("SELECT id FROM table_seatings WHERE table_id = ? AND released_at IS NULL", table) {
                it.getLong(1)
            }.isNotEmpty() ||
            c.rows(
                "SELECT id FROM bookings WHERE table_id = ? AND status IN ('PENDING','CONFIRMED','CHANGED')",
                table,
            ) {
                it.getLong(1)
            }.isNotEmpty() || needsReconciliation(c, table)
        ) {
            conflict("Сначала разрешите посадки, сеансы и действующие брони. Отключение не отменяет обязательства.")
        }
    }

    private fun needsReconciliation(
        c: Connection,
        table: Long,
    ): Boolean =
        c.rows(
            """
            SELECT COUNT(*) FROM table_sessions s WHERE s.table_id = ? AND NOT EXISTS (SELECT 1 FROM
            table_seatings x WHERE x.table_session_id = s.id) AND ((s.status = 'ACTIVE' AND s.ended_at IS NULL
            AND s.expires_at > ?) OR EXISTS (SELECT 1 FROM orders o WHERE o.table_session_id = s.id AND o.status
            = 'ACTIVE'))
            """.trimIndent(),
            table, clock.instant(),
        ) {
            it.getInt(1)
        }.single() > 0 || c.rows(
            """
            SELECT COUNT(*) FROM orders WHERE table_id = ? AND table_session_id IS NULL AND status = 'ACTIVE'
            """.trimIndent(),
            table,
        ) {
            it.getInt(1)
        }.single() > 0

    internal fun halls(
        c: Connection,
        venue: Long,
    ): List<HallDto> =
        c.rows(
            """
            SELECT h.*, m.width, m.height FROM venue_halls h LEFT JOIN hall_plan_media m ON m.id = h.plan_id
            WHERE h.venue_id = ? ORDER BY h.id
            """.trimIndent(),
            venue,
        ) {
            HallDto(
                it.getLong("id"),
                it.getString("name"),
                it.getBoolean("is_active"),
                it.getLong("revision"),
                it.getString("plan_id"),
                it.intOrNull("width"),
                it.intOrNull("height"),
            )
        }

    private fun tables(
        c: Connection,
        venue: Long,
    ): List<HallTableDto> =
        c.rows("SELECT * FROM venue_tables WHERE venue_id = ? ORDER BY table_number", venue) {
            HallTableDto(
                it.getLong("id"),
                it.getInt("table_number"),
                it.longOrNull("hall_id"),
                it.intOrNull("capacity"),
                it.getBoolean("is_active"),
                it.getLong("seating_revision"),
                if (it.getObject("layout_x") == null) {
                    null
                } else {
                    TableLayout(
                        it.getDouble("layout_x"),
                        it.getDouble("layout_y"),
                        it.getDouble("layout_width"),
                        it.getDouble("layout_height"),
                        it.getDouble("layout_rotation"),
                        it.getString("layout_shape"),
                    )
                },
                "FREE",
            )
        }

    internal fun bookings(
        c: Connection,
        venue: Long,
    ): List<TableBookingDto> =
        c.rows(
            """
            SELECT b.*, COALESCE(b.offline_guest_name, u.guest_display_name) AS guest_name FROM bookings b LEFT
            JOIN users u ON u.telegram_user_id = b.user_id WHERE b.venue_id = ? AND (b.status IN
            ('PENDING','CONFIRMED','CHANGED') OR b.status = 'SEATED' AND EXISTS (SELECT 1 FROM table_seatings x
            WHERE x.booking_id = b.id AND x.released_at IS NULL)) ORDER BY b.scheduled_at, b.id
            """.trimIndent(),
            venue,
        ) { bookingRow(it) }

    private fun bookingRow(rs: ResultSet) =
        TableBookingDto(
            rs.getLong(
                "id",
            ),
            rs.longOrNull(
                "table_id",
            ),
            rs.instant(
                "scheduled_at",
            )!!.toString(),
            rs.instant(
                "agreed_end_at",
            )?.toString(),
            rs.intOrNull(
                "party_size",
            ),
            rs.getString(
                "status",
            ),
            rs.getString(
                "booking_source",
            ),
            rs.getString(
                "guest_name",
            ),
            rs.getString(
                "offline_guest_phone",
            ),
            rs.getString("comment"), rs.longOrNull("created_by_user_id"), rs.getLong("seating_revision"),
        )

    private fun currentSeatings(
        c: Connection,
        venue: Long,
    ): List<SeatingDto> =
        c.rows("SELECT * FROM table_seatings WHERE venue_id = ? AND released_at IS NULL ORDER BY id", venue) {
            SeatingDto(
                it.getLong(
                    "id",
                ),
                it.getLong(
                    "table_id",
                ),
                it.getLong(
                    "visit_id",
                ),
                it.getLong(
                    "table_session_id",
                ),
                it.longOrNull(
                    "booking_id",
                ),
                it.getInt(
                    "party_size",
                ),
                it.instant("started_at")!!.toString(), it.instant("agreed_end_at")?.toString(), it.getLong("revision"),
            )
        }

    private fun snapshotRules(
        c: Connection,
        table: String,
        id: Long,
        fallback: SeatingRules,
    ): SeatingRules =
        c.rows(
            "SELECT duration_minutes, buffer_minutes FROM $table WHERE id = ?",
            id,
        ) { rs ->
            val duration = rs.intOrNull("duration_minutes")
            fallback.copy(
                limitDuration = duration != null,
                durationMinutes = duration ?: fallback.durationMinutes,
                bufferMinutes = rs.getInt("buffer_minutes"),
            )
        }.single()

    private fun revision(
        actual: Long,
        expected: Long?,
    ) {
        if (actual != expected) conflict("Состояние изменилось. Обновите данные; введённые значения сохранены.")
    }

    private fun party(value: Int?): Int = value?.takeIf { it in 1..1000 } ?: invalid("Количество гостей: от 1 до 1000.")

    companion object {
        private val reservingStatuses = setOf("PENDING", "CONFIRMED", "CHANGED")
    }
}
