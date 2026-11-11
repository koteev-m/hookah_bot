package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.AccountingConflictException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter
import com.hookah.platform.backend.miniapp.venue.VenueRoleMapping
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingSale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.util.UUID

/** Provenance for the existing loyalty balances/ledger; accounting remains payment authority. */
internal class LoyaltyPaidLifecycle(private val audit: TransactionalAuditLogWriter) {
    data class Target(val kind: String, val type: String, val semantic: String?, val item: Long?, val name: String?)

    data class Terms(val id: String, val nth: Int, val targets: List<Target>) {
        fun matches(
            kind: String,
            item: Long,
            semantic: String?,
        ) = targets.any {
            it.kind == kind && (
                (it.type == "MENU_ITEM" && it.item == item) ||
                    (it.type == "CATEGORY_TYPE" && it.semantic == semantic)
            )
        }
    }

    data class Reward(val cycle: String?, val terms: Terms)

    private data class Cycle(
        val id: String,
        val program: Long,
        val venue: Long,
        val user: Long,
        val terms: Terms,
        val legacy: Int,
        val state: String,
    )

    private data class Purchase(
        val item: Long,
        val batch: Long,
        val menuItem: Long,
        val guest: Long,
        val qty: Int,
        val price: Long,
        val semantic: String?,
    )

    fun terms(
        c: Connection,
        id: String,
    ): Terms =
        c.lq(
            "SELECT nth_value FROM loyalty_terms_versions WHERE id = ?",
            id,
        ) {
            Terms(
                id,
                it.getInt(1),
                c.lq(
                    "SELECT target_kind, target_type, semantic_type, menu_item_id, " +
                        "menu_item_name FROM loyalty_terms_targets " +
                        "WHERE version_id = ? ORDER BY target_kind, target_type, semantic_type, menu_item_id",
                    id,
                ) { r ->
                    Target(
                        r.getString(1),
                        r.getString(2),
                        r.getString(3),
                        r.getObject(4)?.let { r.getLong(4) },
                        r.getString(5),
                    )
                },
            )
        }.single()

    fun currentTerms(
        c: Connection,
        program: Long,
    ): Terms {
        val nth = c.lq("SELECT nth_value FROM loyalty_programs WHERE id = ?", program) { it.getInt(1) }.single()
        val targets =
            listOf("EARN" to "loyalty_program_earn_targets", "REWARD" to "loyalty_program_reward_targets")
                .flatMap { (kind, table) ->
                    c.lq(
                        "SELECT t.target_type, t.semantic_type, t.menu_item_id, mi.name FROM $table t " +
                            "LEFT JOIN menu_items mi ON mi.id = t.menu_item_id WHERE t.program_id = ? " +
                            "ORDER BY t.target_type, t.semantic_type, t.menu_item_id",
                        program,
                    ) {
                        Target(
                            kind,
                            it.getString(1),
                            it.getString(2),
                            it.getObject(3)?.let {
                                    _ ->
                                it.getLong(3)
                            },
                            it.getString(4),
                        )
                    }
                }
        val components =
            listOf(program.toString(), nth.toString()) +
                targets.flatMap {
                    listOf(it.kind, it.type, it.semantic, it.item?.toString(), it.name)
                }
        val fingerprint =
            components.joinToString("") { value ->
                if (value == null) "N" else "S${value.length}:$value"
            }
        val hash =
            MessageDigest.getInstance(
                "SHA-256",
            ).digest(fingerprint.toByteArray()).joinToString("") { "%02x".format(it) }
        val id = "terms-$hash"
        if (c.lq("SELECT id FROM loyalty_terms_versions WHERE id = ?", id) { it.getString(1) }.isEmpty()) {
            c.le(
                "INSERT INTO loyalty_terms_versions (id, program_id, nth_value, origin) " +
                    "VALUES (?, ?, ?, 'PAYMENT_POLICY')",
                id,
                program,
                nth,
            )
            targets.forEach { t ->
                c.le(
                    "INSERT INTO loyalty_terms_targets " +
                        "(version_id, target_kind, target_type, semantic_type, menu_item_id, " +
                        "menu_item_name) VALUES (?, ?, ?, ?, ?, ?)",
                    id,
                    t.kind,
                    t.type,
                    t.semantic,
                    t.item,
                    t.name,
                )
            }
        }
        return Terms(id, nth, targets)
    }

    fun reward(
        c: Connection,
        program: Long,
        guest: Long,
        item: Long,
        semantic: String?,
    ): Reward? {
        val earned =
            cycles(c, program, guest).firstOrNull {
                it.state == "AVAILABLE" && it.terms.matches("REWARD", item, semantic)
            }
        if (earned != null) return Reward(earned.id, earned.terms)
        return c.lq(
            "SELECT terms_id FROM loyalty_legacy_balances WHERE program_id = ? AND user_id = ? " +
                "AND rewards_remaining > 0",
            program,
            guest,
        ) { terms(c, it.getString(1)) }
            .firstOrNull { it.matches("REWARD", item, semantic) }?.let { Reward(null, it) }
    }

    fun sessionUsed(
        c: Connection,
        program: Long,
        guest: Long,
        session: Long,
    ): Boolean =
        c.lq(
            "SELECT r.id FROM loyalty_redemptions r JOIN orders o ON o.id = r.order_id " +
                "WHERE r.program_id = ? AND r.user_id = ? AND o.table_session_id = ? " +
                "AND r.status IN ('RESERVED', 'APPLIED')",
            program,
            guest,
            session,
        ) { it.getLong(1) }.isNotEmpty()

    fun reserve(
        c: Connection,
        program: Long,
        venue: Long,
        guest: Long,
        order: Long,
        batch: Long,
        item: Long,
        application: Long,
        amount: Long,
        key: String,
        reward: Reward,
        session: Long,
    ) {
        if (reward.cycle == null) {
            check(
                c.le(
                    "UPDATE loyalty_legacy_balances SET rewards_remaining = rewards_remaining - 1 " +
                        "WHERE program_id = ? AND user_id = ? AND rewards_remaining > 0",
                    program, guest,
                ) == 1,
            )
        } else {
            check(
                c.le(
                    "UPDATE loyalty_accumulation_cycles SET state = 'RESERVED' WHERE id = ? AND " +
                        "state = 'AVAILABLE'",
                    reward.cycle,
                ) == 1,
            )
        }
        c.le(
            "INSERT INTO loyalty_redemptions (program_id, venue_id, user_id, order_id, " +
                "batch_id, order_batch_item_id, " +
                "order_promotion_application_id, status, discount_minor, dedupe_key, " +
                "origin, cycle_id, terms_id, table_session_id, active_slot) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 'RESERVED', ?, ?, 'PAYMENT_POLICY', ?, ?, ?, 1)",
            program, venue, guest, order, batch, item, application, amount, key, reward.cycle, reward.terms.id, session,
        )
        balance(c, program, guest, 0, -1, 1)
        ledger(c, program, venue, guest, order, batch, item, 0, -1, key)
        event(c, guest, venue, order, "LOYALTY_REWARD_RESERVED", amount)
    }

    fun delivered(
        c: Connection,
        venue: Long,
        order: Long,
        batches: List<Long>,
        actor: Long,
    ) {
        requireOperator(c, venue, actor, false)
        batches.forEach { batch ->
            c.le(
                "UPDATE order_batch_items SET fulfilled_at = CURRENT_TIMESTAMP, fulfilled_by_user_id = ? " +
                    "WHERE order_batch_id = ? AND fulfilled_at IS NULL AND is_excluded = FALSE " +
                    "AND item_status = 'ACTIVE' " +
                    "AND EXISTS (SELECT 1 FROM order_batches b WHERE b.id = ? AND b.order_id = " +
                    "? AND b.status = 'DELIVERED')",
                actor,
                batch,
                batch,
                order,
            )
        }
        batches.forEach { batch ->
            c.le(
                "UPDATE loyalty_redemptions SET issued_at = CURRENT_TIMESTAMP, " +
                    "issued_by_user_id = ?, delivery_version = " +
                    "delivery_version + 1 WHERE batch_id = ? AND " +
                    "venue_id = ? AND origin " +
                    "= 'PAYMENT_POLICY' AND status = 'RESERVED' AND EXISTS (SELECT 1 FROM " +
                    "order_batch_items i WHERE i.id = order_batch_item_id AND " +
                    "i.is_excluded = FALSE AND i.item_status = 'ACTIVE' AND i.fulfilled_at " +
                    "IS NOT NULL)",
                actor,
                batch,
                venue,
            )
        }
        onOrderChanged(c, venue, order, actor)
    }

    fun onOrderChanged(
        c: Connection,
        venue: Long,
        order: Long,
        actor: Long,
    ) {
        c.lq(
            "SELECT r.id, r.status, r.issued_at, i.is_excluded, i.item_status, b.status, o.status " +
                "FROM loyalty_redemptions r JOIN order_batch_items i ON i.id = r.order_batch_item_id " +
                "JOIN order_batches b ON b.id = r.batch_id JOIN orders o ON o.id = r.order_id " +
                "WHERE r.venue_id = ? AND r.order_id = ? AND r.origin = 'PAYMENT_POLICY' " +
                "AND r.status = 'RESERVED' ORDER BY r.id",
            venue,
            order,
        ) {
            val canceled =
                it.getBoolean(4) ||
                    it.getString(5) != "ACTIVE" ||
                    it.getString(6) in listOf("REJECTED", "CANCELED", "CANCELLED") ||
                    it.getString(7) == "CANCELLED"
            Triple(it.getLong(1), canceled, it.getObject(3) != null)
        }.forEach { (id, canceled, issued) ->
            if (canceled) {
                release(c, id, actor, "Отмена до подтверждённой выдачи")
            } else if (issued) {
                val row = redemption(c, id)
                c.le(
                    "UPDATE loyalty_redemptions SET status = 'APPLIED', updated_at = " +
                        "CURRENT_TIMESTAMP WHERE id = ? AND status = 'RESERVED'",
                    id,
                )
                row.cycle?.let { c.le("UPDATE loyalty_accumulation_cycles SET state = 'APPLIED' WHERE id = ?", it) }
                balance(c, row.program, row.user, 0, 0, -1)
                val version =
                    c.lq(
                        "SELECT delivery_version FROM " +
                            "loyalty_redemptions WHERE id = ?",
                        id,
                    ) { it.getInt(1) }.single()
                ledger(
                    c, row.program, row.venue, row.user, order, row.batch, row.item, 0, 0,
                    "loyalty:issued:$id:v:$version",
                )
                event(c, actor, venue, id, "LOYALTY_REWARD_APPLIED", row.amount)
                val issued = redemption(c, id)
                if (issued.compensated) ensureExpense(c, issued, actor, issued.sourceOrder ?: order)
            }
        }
        c.lq(
            "SELECT s.snapshot_json FROM venue_accounting_sales s WHERE s.id = ? " +
                "AND EXISTS (SELECT 1 FROM orders o WHERE o.id = ? AND o.venue_id = ? AND " +
                "o.accounting_shift_id = s.shift_id)",
            "order:$order",
            order,
            venue,
        ) { Json.decodeFromString<AccountingSale>(it.getString(1)) }.singleOrNull()?.let {
            onSale(c, venue, it, "sale:${it.id}:v:${it.version}", actor)
        }
    }

    fun onSale(
        c: Connection,
        venue: Long,
        sale: AccountingSale,
        fact: String,
        actor: Long,
    ): LoyaltyAccrualResult {
        check(!c.autoCommit)
        val order = sale.orderId ?: return LoyaltyAccrualResult(0, 0, 0, 0)
        if (sale.id != "order:$order") return LoyaltyAccrualResult(order, 0, 0, 0)
        val state =
            c.lq(
                "SELECT status FROM orders WHERE id = ? AND " +
                    "venue_id = ? AND table_id = ?",
                order, venue, sale.tableId,
            ) {
                it.getString(1)
            }.singleOrNull() ?: throw ForbiddenException()
        val closed = state == "CLOSED"
        val paid =
            closed &&
                sale.orderClosed &&
                sale.cancelReason == null &&
                sale.disputeReason == null &&
                sale.issue == null &&
                sale.payable > 0 && sale.paid == sale.payable && sale.payments.any { it.voidReason == null }
        val candidates = if (paid) purchases(c, venue, order) else emptyList()
        val desired =
            candidates.flatMap { p ->
                val line =
                    sale.lines.singleOrNull {
                        it.id == "item:${p.item}" &&
                            it.menuItemId == p.menuItem &&
                            it.qty == p.qty &&
                            it.unitPriceMinor == p.price
                    }
                val free =
                    c.lq(
                        "SELECT COUNT(*) FROM loyalty_redemptions WHERE order_batch_item_id = ? AND " +
                            "(status IN ('RESERVED', 'APPLIED') OR " +
                            "(status = 'CANCELED' AND funding_basis = " +
                            "'VENUE_COMPENSATION'))",
                        p.item,
                    ) {
                        it.getInt(1)
                    }.single()
                val qty =
                    if (line == null || line.giftReason != null || line.payable <= 0) {
                        0
                    } else {
                        minOf(
                            (p.qty - free).coerceAtLeast(0),
                            line.payable.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        )
                    }
                (free + 1..free + qty).map { index -> (p.item to index) to p }
            }.toMap()
        val existing =
            c.lq(
                "SELECT id, order_batch_item_id, unit_index, active FROM loyalty_paid_units " +
                    "WHERE order_id = ? ORDER BY id",
                order,
            ) {
                Triple(it.getLong(1), it.getLong(2) to it.getInt(3), it.getBoolean(4))
            }
        val affected = linkedSetOf<Pair<Long, Long>>()
        var earned = 0
        var rewards = 0
        existing.forEach { (id, key, active) ->
            val wanted =
                key in desired &&
                    c.lq(
                        "SELECT id FROM loyalty_unit_corrections WHERE paid_unit_id = ?", id,
                    ) { it.getLong(1) }.isEmpty()
            if (active != wanted) {
                val changedCycle = changeUnit(c, id, wanted, fact, actor)
                affected.add(changedCycle.program to changedCycle.user)
            }
        }
        val known = existing.map { it.second }.toSet()
        val program =
            c.lq(
                "SELECT id FROM loyalty_programs WHERE venue_id = ? AND status = 'ACTIVE' ORDER BY id DESC",
                venue,
            ) {
                it.getLong(1)
            }.firstOrNull()
        if (program != null) {
            desired.filterKeys { it !in known }.forEach { (key, p) ->
                if (p.guest <= 0 ||
                    c.lq(
                        "SELECT telegram_user_id FROM users WHERE " +
                            "telegram_user_id = ? AND NOT EXISTS (SELECT " +
                            "1 FROM venue_members WHERE venue_id = ? AND " +
                            "user_id = ?)",
                        p.guest, venue, p.guest,
                    ) { it.getLong(1) }.isEmpty()
                ) {
                    return@forEach
                }
                // Never reinterpret a legacy close event as confirmed payment.
                if (c.lq(
                        "SELECT id FROM guest_loyalty_ledger WHERE order_batch_item_id = ? AND event_type = 'EARN'",
                        p.item,
                    ) {
                        it.getLong(1)
                    }.isNotEmpty()
                ) {
                    // New payment-policy EARN entries for another unit on this line are allowed.
                    if (c.lq("SELECT id FROM loyalty_paid_units WHERE order_batch_item_id = ?", p.item) {
                            it.getLong(1)
                        }.isEmpty()
                    ) {
                        return@forEach
                    }
                }
                val open = cycles(c, program, p.guest).firstOrNull { it.state == "OPEN" }
                val cycle =
                    if (open != null) {
                        open.takeIf { it.terms.matches("EARN", p.menuItem, p.semantic) }
                    } else {
                        currentTerms(c, program).takeIf { it.matches("EARN", p.menuItem, p.semantic) }?.let { t ->
                            val id = UUID.randomUUID().toString()
                            c.le(
                                "INSERT INTO loyalty_accumulation_cycles (id, program_id, venue_id, " +
                                    "user_id, terms_id, state) VALUES (?, ?, ?, ?, ?, 'OPEN')",
                                id,
                                program,
                                venue,
                                p.guest,
                                t.id,
                            )
                            Cycle(id, program, venue, p.guest, t, 0, "OPEN")
                        }
                    }
                if (cycle == null) return@forEach
                affected.add(cycle.program to cycle.user)
                val before = count(c, cycle)
                val id =
                    c.li(
                        "INSERT INTO loyalty_paid_units (cycle_id, order_id, batch_id, " +
                            "order_batch_item_id, unit_index, payment_fact) VALUES (?, ?, ?, ?, ?, ?)",
                        cycle.id,
                        order,
                        p.batch,
                        p.item,
                        key.second,
                        fact,
                    )
                val rewardDelta = transition(c, cycle, before, before + 1, actor, order)
                ledger(
                    c,
                    program,
                    venue,
                    p.guest,
                    order,
                    p.batch,
                    p.item,
                    1,
                    rewardDelta,
                    "loyalty:unit:$id:g:1",
                    fact,
                    cycle.id,
                    id,
                )
                earned++
                rewards += rewardDelta
            }
        }
        affected.forEach { (p, guest) -> rebalance(c, p, guest, actor, order, fact) }
        if (sale.cancelReason != null) {
            c.lq(
                "SELECT id FROM loyalty_redemptions WHERE order_id = ? AND venue_id = ? AND " +
                    "origin = 'PAYMENT_POLICY' AND status = 'RESERVED'",
                order,
                venue,
            ) {
                it.getLong(1)
            }
                .forEach { release(c, it, actor, "Отмена учётной продажи до выдачи") }
        }
        if (earned > 0) event(c, actor, venue, order, "LOYALTY_PAID_EARN", earned.toLong())
        return LoyaltyAccrualResult(order, earned, earned, rewards)
    }

    private fun purchases(
        c: Connection,
        venue: Long,
        order: Long,
    ): List<Purchase> =
        c.lq(
            "SELECT i.id, b.id, i.menu_item_id, i.qty, i.base_unit_price_minor_snapshot " +
                "+ COALESCE(opt.price_delta_minor_snapshot, 0), " +
                "i.loyalty_semantic_type_snapshot, COALESCE(g.user_id, CASE WHEN NOT EXISTS " +
                "(SELECT 1 FROM venue_members m WHERE m.venue_id = o.venue_id " +
                "AND m.user_id = b.author_user_id) THEN b.author_user_id END, CASE WHEN " +
                "t.type = 'PERSONAL' THEN t.owner_user_id END) AS guest " +
                "FROM orders o JOIN order_batches b ON b.order_id = o.id JOIN " +
                "order_batch_items i ON i.order_batch_id = b.id " +
                "JOIN menu_items mi ON mi.id = i.menu_item_id AND mi.venue_id = o.venue_id " +
                "LEFT JOIN order_batch_item_options opt ON opt.order_batch_item_id = i.id " +
                "LEFT JOIN guest_batch_idempotency g ON g.batch_id = b.id AND g.order_id = " +
                "o.id " +
                "LEFT JOIN tab t ON t.id = b.tab_id WHERE o.id = ? AND o.venue_id = ? AND " +
                "i.id >= (SELECT first_item_id FROM loyalty_payment_policy WHERE id = 1) " +
                "AND i.fulfilled_at IS NOT NULL AND i.base_unit_price_minor_snapshot IS NOT " +
                "NULL AND i.loyalty_semantic_type_snapshot = 'HOOKAH' " +
                "AND i.is_excluded = FALSE AND i.item_status = 'ACTIVE' AND b.status = 'DELIVERED' " +
                "AND NOT EXISTS (SELECT 1 FROM order_promotion_reward_items r WHERE " +
                "r.reward_order_batch_item_id = i.id) ORDER BY i.id",
            order,
            venue,
        ) {
            Purchase(
                it.getLong(1),
                it.getLong(2),
                it.getLong(3),
                it.getObject(7)?.let { _ -> it.getLong(7) } ?: 0,
                it.getInt(4),
                it.getLong(5),
                it.getString(6),
            )
        }.filter { it.price > 0 }

    private data class PaidUnitState(
        val cycle: String,
        val order: Long,
        val batch: Long,
        val item: Long,
        val generation: Int,
    )

    private fun changeUnit(
        c: Connection,
        id: Long,
        active: Boolean,
        fact: String,
        actor: Long,
    ): Cycle {
        val unit =
            c.lq(
                "SELECT cycle_id, order_id, batch_id, order_batch_item_id, generation FROM " +
                    "loyalty_paid_units WHERE id = ?",
                id,
            ) {
                PaidUnitState(it.getString(1), it.getLong(2), it.getLong(3), it.getLong(4), it.getInt(5))
            }.single()
        var cycle = cycle(c, unit.cycle)
        if (active && count(c, cycle) >= cycle.terms.nth - 1) {
            val previous = cycle
            val target =
                cycles(c, cycle.program, cycle.user).firstOrNull {
                    it.id != previous.id && it.state in setOf("OPEN", "EMPTY") && sameTerms(it.terms, previous.terms)
                } ?: run {
                    val restored = UUID.randomUUID().toString()
                    c.le(
                        "INSERT INTO loyalty_accumulation_cycles (id, " +
                            "program_id, venue_id, user_id, terms_id, " +
                            "state) VALUES (?, ?, ?, ?, ?, 'EMPTY')",
                        restored, previous.program, previous.venue, previous.user, previous.terms.id,
                    )
                    cycle(c, restored)
                }
            c.le("UPDATE loyalty_paid_units SET cycle_id = ? WHERE id = ?", target.id, id)
            ledger(
                c, target.program, target.venue, target.user, unit.order, unit.batch, unit.item, 0, 0,
                "loyalty:restore-allocation:$id:g:${unit.generation + 1}", fact, target.id, id, previous.id,
            )
            event(c, actor, target.venue, unit.order, "LOYALTY_UNIT_ALLOCATION_RESTORED", 0)
            cycle = target
        }
        val before = count(c, cycle)
        val generation = unit.generation + 1
        c.le(
            "UPDATE loyalty_paid_units SET active = ?, generation = ?, payment_fact = ? WHERE id = ?",
            active,
            generation,
            fact,
            id,
        )
        val delta = if (active) 1 else -1
        val rewardDelta = transition(c, cycle, before, before + delta, actor, unit.order)
        ledger(
            c,
            cycle.program,
            cycle.venue,
            cycle.user,
            unit.order,
            unit.batch,
            unit.item,
            delta,
            rewardDelta,
            "loyalty:unit:$id:g:$generation",
            fact,
            cycle.id,
            id,
        )
        event(c, actor, cycle.venue, unit.order, "LOYALTY_PURCHASE_COMPENSATION", delta.toLong())
        return cycle
    }

    private fun sameTerms(
        a: Terms,
        b: Terms,
    ): Boolean =
        a.nth == b.nth &&
            a.targets.map { listOf(it.kind, it.type, it.semantic, it.item) }.toSet() ==
            b.targets.map { listOf(it.kind, it.type, it.semantic, it.item) }.toSet()

    private fun rebalance(
        c: Connection,
        program: Long,
        guest: Long,
        actor: Long,
        order: Long,
        fact: String,
    ) {
        val open = cycles(c, program, guest).filter { it.state == "OPEN" }
        open.forEachIndexed { index, initial ->
            var destination = cycle(c, initial.id)
            if (destination.state != "OPEN") return@forEachIndexed
            open.drop(index + 1).filter { sameTerms(it.terms, destination.terms) }.forEach { donorInitial ->
                while (destination.state == "OPEN") {
                    val donor = cycle(c, donorInitial.id)
                    if (donor.state != "OPEN") break
                    val unit =
                        c.lq(
                            "SELECT id, order_id, batch_id, " +
                                "order_batch_item_id FROM loyalty_paid_units " +
                                "WHERE cycle_id = ? AND active = TRUE ORDER " +
                                "BY id",
                            donor.id,
                        ) {
                            listOf(it.getLong(1), it.getLong(2), it.getLong(3), it.getLong(4))
                        }.firstOrNull() ?: break
                    val beforeTo = count(c, destination)
                    val beforeFrom = count(c, donor)
                    c.le("UPDATE loyalty_paid_units SET cycle_id = ? WHERE id = ?", destination.id, unit[0])
                    val delta =
                        transition(c, destination, beforeTo, beforeTo + 1, actor, order) +
                            transition(c, donor, beforeFrom, beforeFrom - 1, actor, order)
                    ledger(
                        c, program, destination.venue, guest, unit[1], unit[2], unit[3], 0, delta,
                        "loyalty:reallocate:${UUID.randomUUID()}", fact, destination.id, unit[0], donor.id,
                    )
                    audit.appendJson(
                        c,
                        actor,
                        "LOYALTY_CYCLE_REALLOCATED",
                        "loyalty",
                        unit[0],
                        buildJsonObject {
                            put("venueId", destination.venue)
                            put("fromCycle", donor.id)
                            put("toCycle", destination.id)
                        },
                    )
                    destination = cycle(c, destination.id)
                }
            }
        }
    }

    private fun transition(
        c: Connection,
        initial: Cycle,
        before: Int,
        after: Int,
        actor: Long,
        sourceOrder: Long,
    ): Int {
        val cycle = initial
        if (cycle.state in setOf("RESERVED", "APPLIED")) {
            val r =
                c.lq(
                    "SELECT id FROM loyalty_redemptions WHERE cycle_id = ? AND status IN ('RESERVED','APPLIED')",
                    cycle.id,
                ) {
                    redemption(c, it.getLong(1))
                }.single()
            if (after < cycle.terms.nth - 1) {
                if (!r.compensated) {
                    c.le(
                        "UPDATE loyalty_redemptions SET funding_basis " +
                            "= 'VENUE_COMPENSATION', compensated_at = " +
                            "CURRENT_TIMESTAMP, " +
                            "compensation_source_order_id = ? WHERE id = ?",
                        sourceOrder,
                        r.id,
                    )
                    audit.appendJson(
                        c,
                        actor,
                        "LOYALTY_COMMITMENT_VENUE_FUNDED",
                        "loyalty",
                        r.id,
                        buildJsonObject {
                            put("venueId", cycle.venue)
                            put("sourceOrderId", sourceOrder)
                            put("cycleId", cycle.id)
                            put("oldFunding", "EARNED")
                            put("newFunding", "VENUE_COMPENSATION")
                            put("status", r.status)
                            put("validUnits", after)
                            put("guestPayableChanged", false)
                        },
                    )
                    ledger(
                        c, r.program, r.venue, r.user, sourceOrder, r.batch, r.item, 0, 0,
                        "loyalty:venue-funding:${UUID.randomUUID()}", cycleId = cycle.id,
                    )
                }
                if (cycle.state == "APPLIED") ensureExpense(c, redemption(c, r.id), actor, sourceOrder)
            } else {
                if (r.compensated) {
                    c.le("UPDATE loyalty_redemptions SET funding_basis = 'EARNED' WHERE id = ?", r.id)
                }
                if (c.le(
                        "UPDATE loyalty_correction_expenses SET " +
                            "resolved_at = CURRENT_TIMESTAMP, active_slot " +
                            "= NULL WHERE cycle_id = ? AND resolved_at IS " +
                            "NULL",
                        cycle.id,
                    ) > 0
                ) {
                    event(c, actor, cycle.venue, sourceOrder, "LOYALTY_EXPENSE_SOURCE_RESTORED", 0)
                }
            }
            return 0
        }
        val state =
            if (after >= cycle.terms.nth - 1) {
                "AVAILABLE"
            } else if (after == 0) {
                "EMPTY"
            } else {
                "OPEN"
            }
        val rewardDelta = (if (state == "AVAILABLE") 1 else 0) - (if (cycle.state == "AVAILABLE") 1 else 0)
        val progressDelta = (if (state == "OPEN") after else 0) - (if (cycle.state == "OPEN") before else 0)
        c.le("UPDATE loyalty_accumulation_cycles SET state = ? WHERE id = ?", state, cycle.id)
        balance(c, cycle.program, cycle.user, progressDelta, rewardDelta, 0)
        return rewardDelta
    }

    private fun release(
        c: Connection,
        id: Long,
        actor: Long,
        reason: String,
    ) {
        val r = redemption(c, id)
        if (r.status != "RESERVED" && r.status != "APPLIED") return
        c.le(
            "UPDATE loyalty_redemptions SET status = 'CANCELED', active_slot = NULL, " +
                "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            id,
        )
        if (r.compensated) {
            balance(c, r.program, r.user, 0, 0, if (r.status == "RESERVED") -1 else 0)
            r.cycle?.let {
                val source = cycle(c, it)
                c.le("UPDATE loyalty_accumulation_cycles SET state = 'EMPTY' WHERE id = ?", it)
                transition(c, source.copy(state = "EMPTY"), 0, count(c, source), actor, r.order)
                rebalance(c, r.program, r.user, actor, r.order, "compensation-canceled:$id")
            }
            ledger(
                c, r.program, r.venue, r.user, r.order, r.batch, r.item, 0, 0,
                "loyalty:release:$id", cycleId = r.cycle,
            )
        } else {
            if (r.cycle == null) {
                c.le(
                    "UPDATE loyalty_legacy_balances SET " +
                        "rewards_remaining = rewards_remaining + 1 " +
                        "WHERE program_id = ? AND user_id = ?",
                    r.program,
                    r.user,
                )
            } else {
                c.le("UPDATE loyalty_accumulation_cycles SET state = 'AVAILABLE' WHERE id = ?", r.cycle)
            }
            balance(c, r.program, r.user, 0, 1, if (r.status == "RESERVED") -1 else 0)
            ledger(c, r.program, r.venue, r.user, r.order, r.batch, r.item, 0, 1, "loyalty:release:$id")
        }
        event(c, actor, r.venue, id, "LOYALTY_REWARD_CANCELED", r.amount, reason)
    }

    fun deliveryOperation(
        c: Connection,
        venue: Long,
        order: Long,
        id: Long,
        actor: Long,
        request: String,
        action: String,
        reason: String,
    ) {
        requireOperator(c, venue, actor, action == "CORRECT")
        if (action !in setOf("CONFIRM", "CORRECT") ||
            !request.matches(Regex("[A-Za-z0-9-]{16,80}")) ||
            reason.isBlank() ||
            reason.codePointCount(0, reason.length) > 500
        ) {
            throw InvalidInputException("Укажите ключ операции и причину исправления (до 500 символов).")
        }
        val replay =
            c.lq(
                "SELECT redemption_id, actor_user_id, reason, action FROM " +
                    "loyalty_delivery_operations WHERE venue_id = ? AND request_id = ?",
                venue,
                request,
            ) {
                listOf(it.getLong(1), it.getLong(2), it.getString(3), it.getString(4))
            }.singleOrNull()
        if (replay != null) {
            if (replay != listOf(id, actor, reason, action)) {
                throw InvalidInputException(
                    "Ключ операции уже использован с другими данными.",
                )
            }
            return
        }
        val r = redemption(c, id)
        if (r.venue != venue || r.order != order) throw ForbiddenException()
        val inactive =
            c.lq(
                "SELECT i.is_excluded, i.item_status, b.status, o.status FROM order_batch_items i " +
                    "JOIN order_batches b ON b.id = i.order_batch_id JOIN orders o ON o.id " +
                    "= b.order_id WHERE i.id = ?",
                r.item,
            ) {
                it.getBoolean(1) ||
                    it.getString(2) == "CANCELED" ||
                    it.getString(3) == "REJECTED" ||
                    it.getString(4) == "CANCELLED"
            }.single() || c.lq("SELECT snapshot_json FROM venue_accounting_sales WHERE id = ?", "order:$order") {
                Json.decodeFromString<AccountingSale>(it.getString(1)).cancelReason != null
            }.singleOrNull() == true
        c.le(
            "INSERT INTO loyalty_delivery_operations (venue_id, request_id, " +
                "redemption_id, action, issued_at_snapshot, actor_user_id, reason) " +
                "SELECT ?, ?, id, ?, issued_at, ?, ? FROM loyalty_redemptions WHERE id = ?",
            venue,
            request,
            action,
            actor,
            reason,
            id,
        )
        if (action == "CONFIRM") {
            if (r.status != "RESERVED" || inactive) {
                throw InvalidInputException(
                    "Нельзя подтвердить выдачу: резерв не действует или позиция отменена.",
                )
            }
            c.le(
                "UPDATE loyalty_redemptions SET issued_at = CURRENT_TIMESTAMP, " +
                    "issued_by_user_id = ?, delivery_version = delivery_version + 1 WHERE id = ?",
                actor,
                id,
            )
            onOrderChanged(c, venue, order, actor)
        } else {
            if (r.status != "APPLIED") throw InvalidInputException("Исправление доступно только для выданной награды.")
            if (inactive) {
                release(c, id, actor, reason)
                r.cycle?.let { cycleId ->
                    val restored = cycle(c, cycleId)
                    val valid = count(c, restored)
                    transition(c, restored, valid, valid, actor, order)
                }
            } else {
                c.le(
                    "UPDATE loyalty_redemptions SET status = 'RESERVED', issued_at = NULL, " +
                        "issued_by_user_id = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    id,
                )
                r.cycle?.let { c.le("UPDATE loyalty_accumulation_cycles SET state = 'RESERVED' WHERE id = ?", it) }
                balance(c, r.program, r.user, 0, 0, 1)
                ledger(
                    c,
                    r.program,
                    r.venue,
                    r.user,
                    order,
                    r.batch,
                    r.item,
                    0,
                    0,
                    "loyalty:delivery-correction:$request",
                )
            }
            r.cycle?.let {
                c.le(
                    "UPDATE loyalty_correction_expenses SET resolved_at = " +
                        "CURRENT_TIMESTAMP, active_slot = NULL WHERE cycle_id = ? AND resolved_at IS NULL",
                    it,
                )
            }
            event(c, actor, venue, id, "LOYALTY_DELIVERY_CORRECTED", r.amount, reason)
        }
    }

    private data class Redemption(
        val id: Long,
        val program: Long,
        val venue: Long,
        val user: Long,
        val order: Long,
        val batch: Long,
        val item: Long,
        val cycle: String?,
        val status: String,
        val amount: Long,
        val compensated: Boolean,
        val sourceOrder: Long?,
    )

    private fun redemption(
        c: Connection,
        id: Long,
    ): Redemption =
        c.lq(
            "SELECT program_id, venue_id, user_id, order_id, batch_id, " +
                "order_batch_item_id, cycle_id, status, discount_minor, funding_basis, compensation_source_order_id " +
                "FROM loyalty_redemptions WHERE id = ? AND origin = 'PAYMENT_POLICY'",
            id,
        ) {
            Redemption(
                id,
                it.getLong(1),
                it.getLong(2),
                it.getLong(3),
                it.getLong(4),
                it.getLong(5),
                it.getLong(6),
                it.getString(7),
                it.getString(8),
                it.getLong(9),
                it.getString(10) == "VENUE_COMPENSATION",
                it.getObject(11)?.let { _ -> it.getLong(11) },
            )
        }.singleOrNull() ?: throw InvalidInputException("Награда не найдена или относится к прежней политике.")

    fun cancelCompensation(
        c: Connection,
        venue: Long,
        order: Long,
        id: Long,
        actor: Long,
        request: String,
        reason: String,
    ): Boolean {
        check(!c.autoCommit)
        requireOperator(c, venue, actor, true)
        if (!request.matches(Regex("[A-Za-z0-9-]{16,80}")) || reason.isBlank() ||
            reason.codePointCount(0, reason.length) > 500
        ) {
            throw InvalidInputException(
                "Укажите причину отмены невыданного бонуса до " +
                    "500 символов.",
            )
        }
        val replay =
            c.lq(
                "SELECT redemption_id, actor_user_id, reason " +
                    "FROM loyalty_compensation_cancellations " +
                    "WHERE venue_id = ? AND request_id = ?",
                venue,
                request,
            ) { listOf(it.getLong(1), it.getLong(2), it.getString(3)) }.singleOrNull()
        if (replay != null) {
            if (replay != listOf(id, actor, reason)) {
                throw InvalidInputException(
                    "Ключ операции уже использован с другими данными.",
                )
            }
            val original = redemption(c, id)
            if (original.venue != venue || original.order != order) throw ForbiddenException()
            return true
        }
        val r = redemption(c, id)
        if (r.venue != venue || r.order != order) throw ForbiddenException()
        if (r.status != "RESERVED" || !r.compensated) {
            throw AccountingConflictException(
                "Отменить можно только невыданный бонус за " +
                    "счёт заведения. Обновите заказ; " +
                    "подтверждённую выдачу при ошибке исправляет " +
                    "управляющий с причиной.",
            )
        }
        c.le(
            "INSERT INTO " +
                "loyalty_compensation_cancellations " +
                "(redemption_id, venue_id, request_id, " +
                "actor_user_id, reason) VALUES (?, ?, ?, ?, ?)",
            id,
            venue,
            request,
            actor,
            reason,
        )
        release(c, id, actor, reason)
        event(c, actor, venue, id, "LOYALTY_COMPENSATION_CANCELED", r.amount, reason)
        return false
    }

    fun correctUnit(
        c: Connection,
        venue: Long,
        order: Long,
        unit: Long,
        actor: Long,
        request: String,
        reason: String,
    ): Boolean {
        check(!c.autoCommit)
        requireOperator(c, venue, actor, true)
        if (!request.matches(Regex("[A-Za-z0-9-]{16,80}")) || reason.isBlank() ||
            reason.codePointCount(0, reason.length) > 500
        ) {
            throw InvalidInputException(
                "Выберите единицу и укажите причину до 500 символов. Обновите заказ и повторите.",
            )
        }
        val replay =
            c.lq(
                "SELECT paid_unit_id, order_id, " +
                    "actor_user_id, reason FROM " +
                    "loyalty_unit_corrections WHERE venue_id = ? " +
                    "AND request_id = ?",
                venue,
                request,
            ) {
                listOf(it.getLong(1), it.getLong(2), it.getLong(3), it.getString(4))
            }.singleOrNull()
        if (replay != null) {
            if (replay != listOf(unit, order, actor, reason)) {
                throw InvalidInputException(
                    "Ключ операции уже использован с другими данными. Обновите заказ.",
                )
            }
            return true
        }
        val data =
            c.lq(
                "SELECT u.cycle_id, u.batch_id, " +
                    "u.order_batch_item_id, u.unit_index, " +
                    "u.active, u.payment_fact, i.qty, " +
                    "i.base_unit_price_minor_snapshot, " +
                    "i.loyalty_semantic_type_snapshot, " +
                    "i.fulfilled_at FROM loyalty_paid_units u " +
                    "JOIN loyalty_accumulation_cycles cy ON cy.id " +
                    "= u.cycle_id JOIN orders o ON o.id = " +
                    "u.order_id JOIN order_batch_items i ON i.id " +
                    "= u.order_batch_item_id WHERE u.id = ? AND " +
                    "u.order_id = ? AND o.venue_id = ? AND " +
                    "cy.venue_id = ?",
                unit, order, venue, venue,
            ) {
                UnitCorrectionInput(
                    it.getString(1), it.getLong(2), it.getLong(3), it.getInt(4),
                    it.getBoolean(5), it.getString(6), it.getInt(7),
                    it.getObject(8)?.let {
                            _ ->
                        it.getLong(8)
                    },
                    it.getString(9), it.getObject(10) != null,
                )
            }.singleOrNull() ?: throw ForbiddenException()
        if (c.lq(
                "SELECT id FROM loyalty_unit_corrections WHERE paid_unit_id = ?",
                unit,
            ) { it.getLong(1) }.isNotEmpty()
        ) {
            throw AccountingConflictException(
                "Эта единица уже скорректирована. Повторная " +
                    "коррекция не требуется; обновите заказ, " +
                    "чтобы увидеть причину и автора.",
            )
        }
        if (!data.active || data.index !in 1..data.qty || data.price == null ||
            data.price < 0 || data.semantic != "HOOKAH" || !data.fulfilled
        ) {
            throw InvalidInputException(
                "Нельзя достоверно подтвердить единицу и её " +
                    "исходное начисление. Ничего не изменено; " +
                    "передайте заказ управляющему для сверки.",
            )
        }
        val cy = cycle(c, data.cycle)
        if (c.lq(
                "SELECT id FROM order_batch_item_options " +
                    "WHERE order_batch_item_id = ? AND " +
                    "price_delta_minor_snapshot IS NULL",
                data.item,
            ) {
                it.getLong(1)
            }.isNotEmpty() ||
            c.lq(
                "SELECT id FROM guest_loyalty_ledger WHERE " +
                    "paid_unit_id = ? AND user_id = ? AND " +
                    "venue_id = ? AND event_type = 'EARN' AND origin = 'PAYMENT_POLICY'",
                unit, cy.user, venue,
            ) {
                it.getLong(1)
            }.isEmpty()
        ) {
            throw InvalidInputException(
                "Не хватает достоверных snapshots единицы и " +
                    "её начисления. Ничего не изменено; нужна " +
                    "сверка управляющим.",
            )
        }
        val optionPrice =
            c.lq(
                "SELECT " +
                    "COALESCE(SUM(price_delta_minor_snapshot),0) " +
                    "FROM order_batch_item_options WHERE " +
                    "order_batch_item_id = ?",
                data.item,
            ) {
                it.getLong(1)
            }.single()
        val price =
            runCatching { Math.addExact(data.price, optionPrice) }.getOrElse {
                throw InvalidInputException(
                    "Историческая цена единицы недостоверна. " +
                        "Ничего не изменено; нужна сверка управляющим.",
                )
            }
        if (price <= 0) {
            throw InvalidInputException(
                "Для этой единицы нет достоверной платной цены. Ничего не изменено; требуется сверка.",
            )
        }
        val originalLine =
            c.lq("SELECT snapshot_json FROM venue_accounting_sales WHERE id = ?", "order:$order") {
                Json.decodeFromString<AccountingSale>(it.getString(1))
            }.singleOrNull()?.lines?.singleOrNull { it.id == "item:${data.item}" }
        if (originalLine == null || originalLine.qty != data.qty || originalLine.unitPriceMinor != price) {
            throw InvalidInputException(
                "Единица расходится с историческим счётом. " +
                    "Ничего не изменено; передайте заказ " +
                    "управляющему для сверки.",
            )
        }
        val actorLabel =
            c.lq("SELECT first_name, last_name, username FROM users WHERE telegram_user_id = ?", actor) {
                listOfNotNull(it.getString(1), it.getString(2)).filter(String::isNotBlank).joinToString(" ").ifBlank {
                    it.getString(3).orEmpty()
                }.ifBlank { "Сотрудник №$actor" }
            }.single().take(200).dropLastWhile { Character.isHighSurrogate(it) }
        val correction =
            c.li(
                "INSERT INTO loyalty_unit_corrections " +
                    "(venue_id, request_id, paid_unit_id, " +
                    "order_id, order_batch_item_id, unit_index, " +
                    "guest_user_id, cycle_id_snapshot, " +
                    "payment_fact_snapshot, " +
                    "unit_price_minor_snapshot, actor_user_id, " +
                    "reason, actor_label_snapshot) VALUES (?, ?, " +
                    "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                venue, request, unit, order, data.item, data.index, cy.user, cy.id,
                data.fact, price, actor, reason, actorLabel,
            )
        changeUnit(c, unit, false, "unit-correction:$correction", actor)
        rebalance(c, cy.program, cy.user, actor, order, "unit-correction:$correction")
        audit.appendJson(
            c,
            actor,
            "LOYALTY_PAID_UNIT_CORRECTED",
            "loyalty_unit",
            unit,
            buildJsonObject {
                put("venueId", venue)
                put("orderId", order)
                put("batchItemId", data.item)
                put("unitIndex", data.index)
                put("correctionId", correction)
                put("guestUserId", cy.user)
                put("cycleId", cy.id)
                put("paymentFact", data.fact)
                put("oldEligible", true)
                put("newEligible", false)
                put("source", "VENUE_MINIAPP")
                put("reason", reason)
                put("billAndReceiptsChanged", false)
            },
        )
        return false
    }

    private data class UnitCorrectionInput(
        val cycle: String,
        val batch: Long,
        val item: Long,
        val index: Int,
        val active: Boolean,
        val fact: String,
        val qty: Int,
        val price: Long?,
        val semantic: String?,
        val fulfilled: Boolean,
    )

    private fun ensureExpense(
        c: Connection,
        r: Redemption,
        actor: Long,
        sourceOrder: Long,
    ) {
        val cycle = r.cycle ?: return
        val issued =
            c.lq(
                "SELECT issued_at, issued_by_user_id, " +
                    "delivery_version FROM loyalty_redemptions " +
                    "WHERE id = ? AND status = 'APPLIED' AND " +
                    "issued_at IS NOT NULL",
                r.id,
            ) {
                Triple(it.getObject(1), it.getLong(2), it.getInt(3))
            }.single()
        val active =
            c.lq(
                "SELECT id FROM loyalty_correction_expenses WHERE cycle_id = ? AND resolved_at IS NULL",
                cycle,
            ) {
                it.getLong(1)
            }.singleOrNull()
        if (active != null) return
        val prior =
            c.lq(
                "SELECT id FROM loyalty_correction_expenses WHERE redemption_id = ? AND delivery_version_snapshot = ?",
                r.id,
                issued.third,
            ) {
                it.getLong(1)
            }.singleOrNull()
        val correction =
            c.lq(
                "SELECT id FROM loyalty_unit_corrections WHERE cycle_id_snapshot = ? ORDER BY id DESC",
                cycle,
            ) {
                it.getLong(1)
            }.firstOrNull()
        val expense =
            if (prior != null) {
                c.le("UPDATE loyalty_correction_expenses SET resolved_at = NULL, active_slot = 1 WHERE id = ?", prior)
                prior
            } else {
                c.li(
                    "INSERT INTO loyalty_correction_expenses " +
                        "(cycle_id, active_slot, venue_id, order_id, " +
                        "redemption_id, amount_minor, reason, " +
                        "recorded_by_user_id, issued_at_snapshot, " +
                        "issued_by_user_id_snapshot, " +
                        "delivery_version_snapshot, correction_id) " +
                        "VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    cycle, r.venue, sourceOrder, r.id, r.amount,
                    "Бонус фактически выдан за счёт заведения. " +
                        "Счёт гостя и денежные поступления не " +
                        "меняются.",
                    actor, issued.first, issued.second, issued.third, correction,
                )
            }
        audit.appendJson(
            c,
            actor,
            if (prior == null) "LOYALTY_VENUE_EXPENSE" else "LOYALTY_VENUE_EXPENSE_REACTIVATED",
            "loyalty_expense",
            expense,
            buildJsonObject {
                put("venueId", r.venue)
                put("redemptionId", r.id)
                put("sourceOrderId", sourceOrder)
                put("deliveryVersion", issued.third)
                put("amountMinor", r.amount)
                put("cashReceiptCreated", false)
                correction?.let { put("correctionId", it) }
            },
        )
    }

    private fun cycle(
        c: Connection,
        id: String,
    ): Cycle =
        c.lq(
            "SELECT program_id, venue_id, user_id, terms_id, legacy_units, state FROM " +
                "loyalty_accumulation_cycles WHERE id = ?",
            id,
        ) {
            Cycle(
                id,
                it.getLong(1),
                it.getLong(2),
                it.getLong(3),
                terms(c, it.getString(4)),
                it.getInt(5),
                it.getString(6),
            )
        }.single()

    private fun cycles(
        c: Connection,
        program: Long,
        guest: Long,
    ): List<Cycle> =
        c.lq(
            "SELECT id FROM loyalty_accumulation_cycles WHERE program_id = ? AND user_id = ? ORDER BY created_at, id",
            program,
            guest,
        ) {
            cycle(c, it.getString(1))
        }

    private fun count(
        c: Connection,
        cycle: Cycle,
    ): Int =
        cycle.legacy +
            c.lq("SELECT COUNT(*) FROM loyalty_paid_units WHERE cycle_id = ? AND active = TRUE", cycle.id) {
                it.getInt(1)
            }.single()

    private fun balance(
        c: Connection,
        program: Long,
        guest: Long,
        progress: Int,
        available: Int,
        reserved: Int,
    ) {
        if (c.lq("SELECT program_id FROM guest_loyalty_progress WHERE program_id = ? AND user_id = ?", program, guest) {
                it.getLong(1)
            }.isEmpty()
        ) {
            c.le(
                "INSERT INTO guest_loyalty_progress (program_id, venue_id, user_id) SELECT " +
                    "id, venue_id, ? FROM loyalty_programs WHERE id = ?",
                guest,
                program,
            )
        }
        check(
            c.le(
                "UPDATE guest_loyalty_progress SET progress_count = progress_count + ?, " +
                    "rewards_available = rewards_available + ?, " +
                    "rewards_reserved = rewards_reserved + ?, updated_at = CURRENT_TIMESTAMP " +
                    "WHERE program_id = ? AND user_id = ? " +
                    "AND progress_count + ? >= 0 AND rewards_available + ? >= 0 AND rewards_reserved + ? >= 0",
                progress, available, reserved, program, guest, progress, available, reserved,
            ) == 1,
        )
    }

    private fun ledger(
        c: Connection,
        program: Long,
        venue: Long,
        guest: Long,
        order: Long,
        batch: Long,
        item: Long,
        progress: Int,
        rewards: Int,
        key: String,
        fact: String? = null,
        cycleId: String? = null,
        unitId: Long? = null,
        previousCycle: String? = null,
    ) {
        c.le(
            "INSERT INTO guest_loyalty_ledger (program_id, venue_id, user_id, " +
                "event_type, delta_progress, delta_rewards, " +
                "order_id, batch_id, order_batch_item_id, dedupe_key, origin, cycle_id, " +
                "payment_fact, paid_unit_id, " +
                "previous_cycle_id) VALUES (?, ?, ?, ?, ?, ?, " +
                "?, ?, ?, ?, 'PAYMENT_POLICY', ?, ?, ?, ?)",
            program,
            venue,
            guest,
            if (progress > 0) "EARN" else "ADJUST",
            progress,
            rewards,
            order,
            batch,
            item,
            key,
            cycleId,
            fact,
            unitId,
            previousCycle,
        )
    }

    private fun event(
        c: Connection,
        actor: Long,
        venue: Long,
        id: Long,
        action: String,
        amount: Long,
        reason: String? = null,
    ) {
        audit.appendJson(
            c,
            actor,
            action,
            "loyalty",
            id,
            buildJsonObject {
                put("venueId", venue)
                put("amountOrUnits", amount)
                reason?.let { put("reason", it) }
            },
        )
    }

    private fun requireOperator(
        c: Connection,
        venue: Long,
        actor: Long,
        manager: Boolean,
    ) {
        val role =
            c.lq("SELECT role FROM venue_members WHERE venue_id = ? AND user_id = ? FOR UPDATE", venue, actor) {
                VenueRoleMapping.fromDb(it.getString(1))?.name
            }.singleOrNull()
        val allowed = if (manager) setOf("OWNER", "MANAGER") else setOf("OWNER", "MANAGER", "STAFF")
        if (role !in allowed) throw ForbiddenException()
    }
}

internal fun Connection.le(
    sql: String,
    vararg values: Any?,
): Int =
    prepareStatement(sql).use { s ->
        values.forEachIndexed { i, v -> s.setObject(i + 1, v) }
        s.executeUpdate()
    }

internal fun Connection.li(
    sql: String,
    vararg values: Any?,
): Long =
    prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { s ->
        values.forEachIndexed {
                i,
                v,
            ->
            s.setObject(i + 1, v)
        }
        s.executeUpdate()
        s.generatedKeys.use {
            check(it.next())
            it.getLong(1)
        }
    }

internal fun <T> Connection.lq(
    sql: String,
    vararg values: Any?,
    map: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { s ->
        values.forEachIndexed {
                i,
                v,
            ->
            s.setObject(i + 1, v)
        }
        s.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
    }
