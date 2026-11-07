package com.hookah.platform.backend.miniapp.venue.accounting
import com.hookah.platform.backend.api.AccountingConflictException
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter
import com.hookah.platform.backend.miniapp.venue.VenueRoleMapping
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

class AccountingRepository(
    private val dataSource: DataSource?,
    private val audit: TransactionalAuditLogWriter = AuditLogRepository(dataSource),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val orders = VenueOrdersRepository(dataSource)
    private val json = Json { encodeDefaults = true }

    suspend fun state(
        venueId: Long,
        actor: Long,
        beforeShiftId: Long? = null,
    ): AccountingState =
        transaction(venueId, actor) { c, role ->
            val current = headers(c, venueId).firstOrNull { it.status == "OPEN" }
            val history =
                if (role == "STAFF") {
                    emptyList()
                } else {
                    c.query(
                        "SELECT id, status, timezone, opened_at, closed_at FROM venue_accounting_shifts " +
                            "WHERE venue_id = ? AND status = 'CLOSED' AND id < ? ORDER BY id DESC LIMIT 51",
                        venueId,
                        beforeShiftId ?: Long.MAX_VALUE,
                    ) { it.toHeader() }
                }
            AccountingState(
                current = current?.let { report(c, venueId, it) },
                history = history.take(50),
                nextHistoryBeforeId = if (history.size > 50) history[49].id else null,
            )
        }

    suspend fun detail(
        venueId: Long,
        actor: Long,
        shiftId: Long,
    ): AccountingReport =
        transaction(venueId, actor) { c, role ->
            val shift = header(c, venueId, shiftId)
            if (shift.status == "CLOSED" && role == "STAFF") throw ForbiddenException()
            report(c, venueId, shift)
        }

    suspend fun execute(
        venueId: Long,
        actor: Long,
        command: AccountingCommand,
    ): AccountingReport =
        transaction(venueId, actor) { c, role ->
            if (!command.requestId.matches(Regex("[A-Za-z0-9-]{16,80}"))) invalid("Недопустимый ключ операции.")
            if (command.reason != null && command.reason.length > 500) invalid("Причина должна быть до 500 символов.")
            if (command.lines.size > 100) invalid("В продаже может быть не более 100 позиций.")
            if (command.action == "CLOSE" && role == "STAFF") throw ForbiddenException()
            val fingerprint = sha256(json.encodeToString(command))
            val replay =
                c.query(
                    "SELECT actor_user_id, fingerprint, shift_id FROM venue_accounting_commands " +
                        "WHERE venue_id = ? AND request_id = ?",
                    venueId,
                    command.requestId,
                ) { Triple(it.getLong(1), it.getString(2), it.getLong(3)) }.singleOrNull()
            if (replay != null) {
                if (replay.first != actor || replay.second != fingerprint) {
                    conflict(
                        "Ключ уже использован с другими данными. Обновите отчёт.",
                    )
                }
                val shift = header(c, venueId, replay.third)
                if (shift.status == "CLOSED" && role == "STAFF") throw ForbiddenException()
                return@transaction report(c, venueId, shift)
            }
            val shift =
                if (command.action == "OPEN") {
                    open(c, venueId, actor)
                } else {
                    header(c, venueId, command.shiftId ?: invalid("Не указана смена.")).also {
                        if (it.status != "OPEN") {
                            conflict(
                                "Смена закрыта. Финансовые записи доступны только для чтения.",
                            )
                        }
                    }
                }
            if (command.action != "OPEN") {
                val before = report(c, venueId, shift)
                if (command.action == "CLOSE") {
                    close(c, actor, before, command)
                } else {
                    mutateSale(c, actor, before, command)
                }
            }
            c.exec(
                "INSERT INTO venue_accounting_commands (venue_id, request_id, actor_user_id, " +
                    "fingerprint, shift_id) VALUES (?, ?, ?, ?, ?)",
                venueId,
                command.requestId,
                actor,
                fingerprint,
                shift.id,
            )
            report(c, venueId, header(c, venueId, shift.id))
        }

    private fun open(
        c: Connection,
        venueId: Long,
        actor: Long,
    ): AccountingShiftHeader {
        if (headers(c, venueId).any { it.status == "OPEN" }) conflict("В заведении уже есть открытая учётная смена.")
        val zone =
            c.query("SELECT timezone FROM venue_settings WHERE venue_id = ?", venueId) { it.getString(1) }
                .singleOrNull()?.takeIf { it.isNotBlank() } ?: VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE
        if (runCatching { ZoneId.of(zone) }.isFailure) invalid("Проверьте часовой пояс заведения в настройках.")
        val now = now()
        val id =
            c.prepareStatement(
                "INSERT INTO venue_accounting_shifts (venue_id, status, open_slot, timezone, " +
                    "opened_at) VALUES (?, 'OPEN', 1, ?, ?)",
                Statement.RETURN_GENERATED_KEYS,
            ).use { s ->
                s.setLong(1, venueId)
                s.setString(2, zone)
                s.setObject(3, now)
                s.executeUpdate()
                s.generatedKeys.use { rs ->
                    check(rs.next())
                    rs.getLong(1)
                }
            }
        // Only current active visits are adopted. Past closed orders are never guessed/backfilled.
        val active =
            c.query(
                "SELECT id FROM orders WHERE venue_id = ? AND status = 'ACTIVE' AND " +
                    "accounting_shift_id IS NULL ORDER BY id FOR UPDATE",
                venueId,
            ) { it.getLong(1) }
        active.forEach { c.exec("UPDATE orders SET accounting_shift_id = ? WHERE id = ?", id, it) }
        record(
            c,
            actor,
            id,
            null,
            "OPEN",
            "null",
            json.encodeToString(active),
            buildJsonObject {
                put("adoptedOrders", active.size)
            },
        )
        return header(c, venueId, id)
    }

    private fun close(
        c: Connection,
        actor: Long,
        report: AccountingReport,
        command: AccountingCommand,
    ) {
        if (report.reportHash != command.expectedReportHash) {
            conflict(
                "Отчёт изменился. Проверьте новый итог и подтвердите закрытие ещё раз.",
            )
        }
        if (report.sales.any {
                !it.orderClosed
            }
        ) {
            conflict("Сначала завершите активные заказы в разделе «Заказы». Завершение заказа не означает оплату.")
        }
        if (report.sales.any {
                it.issue != null
            }
        ) {
            conflict("Сначала устраните расхождения исходных счетов. Закрытие не выполнено.")
        }
        if ((report.unpaidSaleIds.isNotEmpty() || report.disputedSaleIds.isNotEmpty()) && !command.acknowledgeUnpaid) {
            conflict(
                "Подтвердите закрытие с неоплаченными или спорными записями. После закрытия " +
                    "их нельзя исправить в этой смене.",
            )
        }
        val closed = report.copy(status = "CLOSED", closedAt = now().toInstant().toString())
        val saved = json.encodeToString(closed)
        c.exec(
            "UPDATE venue_accounting_shifts SET status = 'CLOSED', open_slot = NULL, " +
                "closed_at = ?, final_report = ? WHERE id = ?",
            OffsetDateTime.parse(closed.closedAt),
            saved,
            report.shiftId,
        )
        record(c, actor, report.shiftId, null, "CLOSE", json.encodeToString(report), saved, money(report.totals))
    }

    private fun mutateSale(
        c: Connection,
        actor: Long,
        report: AccountingReport,
        command: AccountingCommand,
    ) {
        val old =
            if (command.action == "CREATE") {
                null
            } else {
                report.sales.find {
                    it.id == command.saleId
                } ?: throw NotFoundException()
            }
        if (old != null && old.version != command.expectedVersion) {
            conflict(
                "Запись уже изменена. Обновите отчёт перед исправлением или оплатой.",
            )
        }
        if (old != null && (!old.orderClosed || old.issue != null)) {
            conflict("Сначала завершите и проверьте исходный заказ. Его расчётная сумма ещё может измениться.")
        }
        val changed =
            when (command.action) {
                "CREATE" -> {
                    val tableId = command.tableId ?: invalid("Выберите стол.")
                    val label =
                        c.query(
                            "SELECT table_number FROM venue_tables WHERE id = ? AND venue_id = ? AND " +
                                "is_active = TRUE",
                            tableId, report.venueId,
                        ) {
                            "Стол ${it.getInt(1)}"
                        }.singleOrNull() ?: throw NotFoundException()
                    AccountingSale(
                        id = "manual:${UUID.randomUUID()}",
                        tableId = tableId,
                        tableLabel = label,
                        source = "MANUAL",
                        createdAt = now().toInstant().toString(),
                        lines = lines(c, report.venueId, command, null),
                    )
                }
                "EDIT" -> {
                    reason(command)
                    val sale = checkNotNull(old)
                    if (sale.cancelReason != null) {
                        conflict(
                            "Отменённая запись сохраняется в истории и не редактируется.",
                        )
                    }
                    sale.copy(lines = lines(c, report.venueId, command, sale))
                }
                "PAY" -> {
                    val sale = checkNotNull(old)
                    if (sale.cancelReason != null) conflict("Нельзя оплатить отменённую запись.")
                    if (command.paymentMethod !in setOf("CASH", "CARD", "TRANSFER")) {
                        invalid(
                            "Выберите наличные, карту или перевод.",
                        )
                    }
                    val amount = command.amountMinor ?: invalid("Введите сумму оплаты.")
                    if (amount <= 0 || amount > sale.payable - sale.paid) {
                        conflict(
                            "Сумма должна быть больше нуля и не превышать неоплаченный остаток.",
                        )
                    }
                    sale.copy(
                        payments =
                            sale.payments +
                                AccountingPayment(
                                    command.requestId,
                                    checkNotNull(command.paymentMethod),
                                    amount,
                                    now().toInstant().toString(),
                                ),
                    )
                }
                "VOID_PAYMENT" -> {
                    val sale = checkNotNull(old)
                    val reason = reason(command)
                    if (sale.payments.none {
                            it.id == command.paymentId && it.voidReason == null
                        }
                    ) {
                        conflict("Оплата уже исправлена или не найдена.")
                    }
                    sale.copy(
                        payments =
                            sale.payments.map {
                                if (it.id == command.paymentId) {
                                    it.copy(
                                        voidReason = reason,
                                    )
                                } else {
                                    it
                                }
                            },
                    )
                }
                "CANCEL" -> checkNotNull(old).copy(cancelReason = reason(command))
                "DISPUTE" -> checkNotNull(old).copy(disputeReason = reason(command))
                "RESOLVE" -> {
                    reason(command)
                    checkNotNull(old).copy(disputeReason = null)
                }
                else -> invalid("Неизвестная операция учёта.")
            }.let { it.copy(version = (old?.version ?: 0) + 1) }
        if (changed.paid > changed.payable) {
            conflict(
                "Исправление создаёт переплату. Сначала исправьте ошибочную запись оплаты. " +
                    "Реальные деньги не возвращаются.",
            )
        }
        if (changed.lines.sumOf { it.gross } > MAX_SALE_MINOR) invalid("Слишком большая сумма продажи.")
        val newSales = report.sales.filterNot { it.id == changed.id } + changed
        if (accountingTotals(newSales).grossMinor > MAX_SHIFT_MINOR) invalid("Превышен допустимый объём смены.")
        val saved = json.encodeToString(changed)
        val updated =
            c.exec(
                "UPDATE venue_accounting_sales SET snapshot_json = ? WHERE id = ? AND shift_id = ?",
                saved,
                changed.id,
                report.shiftId,
            )
        if (updated == 0) {
            c.exec(
                "INSERT INTO venue_accounting_sales (id, shift_id, snapshot_json) VALUES (?, ?, ?)",
                changed.id,
                report.shiftId,
                saved,
            )
        }
        record(
            c, actor, report.shiftId, changed.id, command.action,
            old?.let { json.encodeToString(it) } ?: "null", saved,
            buildJsonObject {
                put("old", money(accountingTotals(listOfNotNull(old))))
                put("new", money(accountingTotals(listOf(changed))))
            },
            command.reason?.trim(),
        )
    }

    private fun lines(
        c: Connection,
        venueId: Long,
        command: AccountingCommand,
        old: AccountingSale?,
    ): List<AccountingLine> {
        if (command.lines.isEmpty() || command.lines.size > 100) invalid("Добавьте от 1 до 100 позиций.")
        if (command.lines.mapNotNull {
                it.id
            }.distinct().size != command.lines.count { it.id != null }
        ) {
            invalid("Позиция указана повторно.")
        }
        if (old?.orderId != null && command.lines.map { it.id }.toSet() != old.lines.map { it.id }.toSet()) {
            invalid(
                "Состав цифрового заказа сохраняется. Для исправления используйте скидку, " +
                    "подарок или отмену учётной записи.",
            )
        }
        return command.lines.map { input ->
            val previous =
                input.id?.let {
                        id ->
                    old?.lines?.find { it.id == id } ?: invalid("Позиция не найдена в записи.")
                }
            if (input.qty !in 1..1000) invalid("Количество должно быть от 1 до 1000.")
            val base =
                when {
                    old?.orderId != null -> {
                        val original = checkNotNull(previous)
                        if (
                            input.qty != original.qty ||
                            (input.unitPriceMinor != null && input.unitPriceMinor != original.unitPriceMinor)
                        ) {
                            invalid("Цену и количество цифрового заказа нельзя менять в учётном журнале.")
                        }
                        original
                    }
                    previous == null && input.menuItemId != null ->
                        c.query(
                            "SELECT name, price_minor, currency FROM menu_items WHERE id = ? AND " +
                                "venue_id = ? AND is_available = TRUE",
                            input.menuItemId, venueId,
                        ) { rs ->
                            if (rs.getString(3).trim() != "RUB") invalid("MVP учитывает только рублёвые продажи.")
                            AccountingLine(
                                UUID.randomUUID().toString(),
                                rs.getString(1),
                                input.qty,
                                rs.getLong(2),
                                input.menuItemId,
                            )
                        }.singleOrNull() ?: throw NotFoundException()
                    else ->
                        AccountingLine(
                            id = previous?.id ?: UUID.randomUUID().toString(),
                            name =
                                input.name?.trim()?.takeIf { it.length in 1..200 }
                                    ?: invalid("Введите название позиции (до 200 символов)."),
                            qty = input.qty,
                            unitPriceMinor = input.unitPriceMinor ?: invalid("Введите цену позиции."),
                            menuItemId = previous?.menuItemId,
                        )
                }
            if (base.unitPriceMinor !in 0..MAX_UNIT_MINOR) {
                invalid(
                    "Цена должна быть неотрицательной и не превышать лимит.",
                )
            }
            val giftReason =
                input.giftReason?.let {
                    it.trim().takeIf {
                            text ->
                        text.length in 1..500
                    } ?: invalid("Укажите причину подарка.")
                }
            if (
                input.discountMinor < 0 ||
                input.discountMinor > base.gross - base.baseDiscountMinor - base.baseGiftMinor
            ) {
                invalid(
                    "Скидка превышает доступную сумму позиции.",
                )
            }
            if (base.baseDiscountMinor > 0 && input.discountMinor > 0) {
                invalid(
                    "Ручная скидка не суммируется с действующей скидкой заказа.",
                )
            }
            if (giftReason != null && (input.discountMinor > 0 || base.baseDiscountMinor > 0)) {
                invalid(
                    "Подарок и скидка одной позиции не суммируются.",
                )
            }
            if (input.discountMinor > 0) reason(command)
            if (base.unitPriceMinor == 0L && giftReason == null && base.baseGiftMinor == 0L) {
                invalid(
                    "Для бесплатной позиции обязательна причина.",
                )
            }
            base.copy(discountMinor = input.discountMinor, giftReason = giftReason)
        }
    }

    private fun report(
        c: Connection,
        venueId: Long,
        shift: AccountingShiftHeader,
    ): AccountingReport {
        if (shift.status == "CLOSED") {
            return c.query(
                "SELECT final_report FROM venue_accounting_shifts WHERE id = ? AND venue_id = ?",
                shift.id,
                venueId,
            ) { json.decodeFromString<AccountingReport>(it.getString(1)) }.single()
        }
        val saved =
            c.query("SELECT snapshot_json FROM venue_accounting_sales WHERE shift_id = ? ORDER BY id", shift.id) {
                json.decodeFromString<AccountingSale>(it.getString(1))
            }.associateBy { it.id }
        val digital =
            c.query(
                "SELECT id, status FROM orders WHERE accounting_shift_id = ? AND venue_id = ? ORDER BY id FOR UPDATE",
                shift.id,
                venueId,
            ) { it.getLong(1) to it.getString(2) }.map { (id, status) ->
                saved["order:$id"] ?: checkNotNull(
                    orders.loadOrderDetail(
                        c,
                        venueId,
                        id,
                    ),
                ).toAccountingSale(status != "ACTIVE").let {
                        sale ->
                    val missing =
                        c.query(
                            "SELECT COUNT(*) FROM order_batch_items i JOIN order_batches b ON b.id = " +
                                "i.order_batch_id " +
                                "WHERE b.order_id = ? AND (i.base_unit_price_minor_snapshot IS NULL OR " +
                                "i.item_name_snapshot IS NULL OR i.currency_snapshot IS NULL)",
                            id,
                        ) { it.getInt(1) }.single()
                    val issue =
                        if (missing > 0) {
                            "Нет неизменяемого снимка исходного счёта. Сумма не включена в итог; нужна сверка."
                        } else {
                            sale.issue
                        }
                    if (issue != null) {
                        sale.copy(issue = issue, lines = emptyList(), originalPayableMinor = null)
                    } else if (status == "CANCELLED") {
                        sale.copy(cancelReason = "Исходный заказ отменён")
                    } else {
                        sale
                    }
                }
            }
        val sales = (digital + saved.values.filter { it.orderId == null }).sortedBy { it.id }
        val active = sales.filter { it.cancelReason == null }
        val allLines = active.flatMap { it.lines }

        fun group(
            lines: List<AccountingLine>,
            key: (AccountingLine) -> String,
        ) = lines.groupBy(key).toSortedMap().map { (label, rows) ->
            AccountingBreakdown(label, rows.sumOf { it.qty.toLong() }, rows.sumOf { it.payable })
        }
        val report =
            AccountingReport(
                shiftId = shift.id,
                venueId = venueId,
                status = shift.status,
                timezone = shift.timezone,
                openedAt = shift.openedAt,
                sales = sales, totals = accountingTotals(sales),
                byTable =
                    active.groupBy { it.tableId }.values.map { rows ->
                        AccountingBreakdown(rows.first().tableLabel, rows.size.toLong(), rows.sumOf { it.payable })
                    }.sortedBy { it.label },
                byItem = group(allLines) { "${it.name} · ${it.unitPriceMinor} коп." },
                bySource = group(allLines) { it.source },
                unpaidSaleIds = sales.filter { it.payable > it.paid }.map { it.id },
                disputedSaleIds = sales.filter { it.disputeReason != null || it.issue != null }.map { it.id },
            )
        return report.copy(reportHash = sha256(json.encodeToString(report)))
    }

    private fun header(
        c: Connection,
        venueId: Long,
        id: Long,
    ): AccountingShiftHeader =
        c.query(
            "SELECT id, status, timezone, opened_at, closed_at FROM " +
                "venue_accounting_shifts WHERE venue_id = ? AND id = ?",
            venueId, id,
        ) { it.toHeader() }.singleOrNull() ?: throw NotFoundException()

    private fun headers(
        c: Connection,
        venueId: Long,
    ): List<AccountingShiftHeader> =
        c.query(
            "SELECT id, status, timezone, opened_at, closed_at FROM " +
                "venue_accounting_shifts WHERE venue_id = ? ORDER BY id DESC LIMIT 100",
            venueId,
        ) { it.toHeader() }

    private fun ResultSet.toHeader() =
        AccountingShiftHeader(
            getLong("id"),
            getString("status"),
            getString("timezone"),
            getTimestamp("opened_at").toInstant().toString(),
            getTimestamp("closed_at")?.toInstant()?.toString(),
        )

    private fun record(
        c: Connection,
        actor: Long,
        shift: Long,
        sale: String?,
        action: String,
        old: String,
        new: String,
        payload: JsonObject,
        reason: String? = null,
    ) {
        c.exec(
            "INSERT INTO venue_accounting_events (shift_id, sale_id, actor_user_id, " +
                "created_at, action, reason_text, old_json, new_json) VALUES (?, ?, ?, ?, ?, " +
                "?, ?, ?)",
            shift, sale, actor, now(), action, reason, old, new,
        )
        audit.appendJson(c, actor, "VENUE_ACCOUNTING_$action", "venue_accounting_shift", shift, payload)
    }

    private fun money(totals: AccountingTotals) =
        buildJsonObject {
            put("grossMinor", totals.grossMinor)
            put("discountMinor", totals.discountMinor)
            put("giftMinor", totals.giftMinor)
            put("accruedMinor", totals.accruedMinor)
            put("paidMinor", totals.paidMinor)
            put("cashMinor", totals.cashMinor)
            put("cardMinor", totals.cardMinor)
            put("transferMinor", totals.transferMinor)
        }

    private suspend fun <T> transaction(
        venueId: Long,
        actor: Long,
        block: (Connection, String) -> T,
    ): T {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { c ->
                    c.autoCommit = false
                    try {
                        // Same venue -> membership lock order as role removal. Recheck after waiting.
                        // Keep FK checks by order/extension writers compatible while we wait for their order lock.
                        val venueLock =
                            if (c.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)) {
                                "FOR NO KEY UPDATE"
                            } else {
                                "FOR UPDATE"
                            }
                        if (c.query("SELECT id FROM venues WHERE id = ? $venueLock", venueId) {
                                it.getLong(1)
                            }.isEmpty()
                        ) {
                            throw ForbiddenException()
                        }
                        val role =
                            c.query(
                                "SELECT role FROM venue_members WHERE venue_id = ? AND user_id = ? FOR UPDATE",
                                venueId,
                                actor,
                            ) {
                                VenueRoleMapping.fromDb(it.getString(1))?.name
                            }.singleOrNull()
                        if (role !in setOf("OWNER", "MANAGER", "STAFF")) throw ForbiddenException()
                        block(c, checkNotNull(role)).also { c.commit() }
                    } catch (e: Throwable) {
                        c.rollback()
                        throw e
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private fun reason(command: AccountingCommand): String =
        command.reason?.trim()?.takeIf { it.length in 1..500 }
            ?: invalid("Укажите причину (до 500 символов).")

    private fun invalid(message: String): Nothing = throw InvalidInputException(message)

    private fun conflict(message: String): Nothing = throw AccountingConflictException(message)

    companion object {
        private const val MAX_UNIT_MINOR = 1_000_000_000L
        private const val MAX_SALE_MINOR = 10_000_000_000_000L
        private const val MAX_SHIFT_MINOR = 100_000_000_000_000L
    }
}

private fun sha256(value: String): String =
    MessageDigest.getInstance(
        "SHA-256",
    ).digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
    }

private fun Connection.exec(
    sql: String,
    vararg args: Any?,
): Int =
    prepareStatement(sql).use { s ->
        args.forEachIndexed { index, value -> s.setObject(index + 1, value) }
        s.executeUpdate()
    }

private fun <T> Connection.query(
    sql: String,
    vararg args: Any?,
    map: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { s ->
        args.forEachIndexed { index, value -> s.setObject(index + 1, value) }
        s.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }
