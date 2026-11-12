package com.hookah.platform.backend.analytics

import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.subscription.VenueAvailabilityResolver
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import java.time.ZoneOffset

internal const val WAAV_SQL =
    "SELECT COUNT(DISTINCT f.venue_id) FROM order_batch_status_facts f " +
        "JOIN order_batches b ON b.id = f.batch_id AND b.order_id = f.order_id " +
        "JOIN orders o ON o.id = f.order_id AND o.venue_id = f.venue_id " +
        "WHERE f.to_status IN ('ACCEPTED','DELIVERED') AND f.occurred_at >= ? AND f.occurred_at < ?"

internal const val MULTIPLE_MATCHING_SUCCESSES_SQL =
    "SELECT COUNT(*) FROM billing_invoices i JOIN (" +
        "SELECT invoice_id, provider, amount_minor, currency FROM billing_payments " +
        "WHERE status = 'SUCCEEDED' AND occurred_at < ? " +
        "GROUP BY invoice_id, provider, amount_minor, currency HAVING COUNT(*) > 1" +
        ") p ON p.invoice_id = i.id AND p.provider = i.provider " +
        "AND p.amount_minor = i.amount_minor AND p.currency = i.currency WHERE i.created_at < ?"

internal fun KpiCollector.platform(section: String) {
    val range = listOf(window.from, window.observedTo)
    if (section in setOf("all", "venues")) {
        states("venues.lifecycle", "venues", "status", "created_at < ?", listOf(window.asOf))
        count("venues.total", "venues", "created_at < ?", listOf(window.asOf), "SNAPSHOT_AS_OF")
        count("venues.created", "venues", "created_at >= ? AND created_at < ?", range, "VENUE_CREATED_AT")
        val combinations =
            c.read(
                "SELECT v.status, s.status, COUNT(*) FROM venues v LEFT JOIN " +
                    "venue_subscriptions s ON s.venue_id = v.id " +
                    "WHERE v.created_at < ? GROUP BY v.status, s.status",
                listOf(window.asOf),
            ) { Triple(it.getString(1), it.getString(2), it.getLong(3)) }
        val available =
            combinations.filter {
                val lifecycle = VenueStatus.fromDb(it.first)
                lifecycle != null &&
                    VenueAvailabilityResolver.resolve(lifecycle, SubscriptionStatus.fromDb(it.second)).available
            }.sumOf { it.third }
        val missing =
            combinations.filter { it.second == null && VenueStatus.fromDb(it.first) == VenueStatus.PUBLISHED }
                .sumOf { it.third }
        metrics["venues.guestAvailable"] =
            kpi(
                available, "SNAPSHOT_AVAILABILITY_RESOLVER", unknown = missing,
                reason = if (missing > 0) "SUBSCRIPTION_MISSING" else null,
            )
        count(
            "venues.staffChatLinked",
            "venues",
            "created_at < ? AND staff_chat_id IS NOT NULL",
            listOf(window.asOf),
            "SNAPSHOT_CONFIRMED_LINK_NOT_DELIVERY",
        )
        val unknown =
            c.count(
                "SELECT COUNT(DISTINCT o.venue_id) FROM orders o JOIN order_batches b ON b.order_id = o.id " +
                    "WHERE NOT b.status_history_complete AND b.created_at < ?",
                listOf(window.asOf),
            )
        val waav = c.count(WAAV_SQL, listOf(window.asOf.minusSeconds(168 * 3600), window.asOf))
        metrics["venues.waav"] = kpi(waav, "ROLLING_168_HOURS_BATCH_TRANSITIONS", waav, unknown)
    }
    if (section in setOf("all", "subscriptions")) {
        val subscriptions =
            c.read(
                "SELECT s.status, COUNT(*) FROM venues v LEFT JOIN venue_subscriptions s ON s.venue_id = v.id " +
                    "WHERE v.created_at < ? GROUP BY s.status",
                listOf(window.asOf),
            ) { it.getString(1) to it.getLong(2) }
        breakdowns["subscriptions.states"] =
            subscriptions.groupBy {
                if (it.first == null) "MISSING" else SubscriptionStatus.fromDb(it.first).name
            }.toSortedMap().map { (key, rows) -> KpiBucket(key, rows.sumOf { it.second }) }
        val paid =
            "(SELECT MAX(i.period_end) FROM billing_invoices i WHERE i.venue_id = " +
                "v.id AND i.status = 'PAID' AND i.created_at < ?)"
        val courtesy =
            "(SELECT a.new_paid_through FROM billing_adjustments a WHERE a.venue_id = v.id " +
                "AND a.kind = 'COURTESY_DAYS' AND a.created_at < ? " +
                "ORDER BY a.new_paid_through DESC, a.id DESC LIMIT 1)"
        val rows =
            c.read(
                "SELECT p.base_end, p.courtesy_end, COUNT(*) FROM (SELECT $paid AS " +
                    "base_end, $courtesy AS courtesy_end " +
                    "FROM venues v WHERE v.created_at < ?) p GROUP BY p.base_end, p.courtesy_end",
                listOf(window.asOf, window.asOf, window.asOf),
            ) {
                Triple(it.getDate(1)?.toLocalDate(), it.getDate(2)?.toLocalDate(), it.getLong(3))
            }
        val today = window.asOf.atZone(ZoneOffset.UTC).toLocalDate()
        for (effective in listOf(false, true)) {
            val dates =
                rows.map { (base, courtesyEnd, count) ->
                    (if (effective) listOfNotNull(base, courtesyEnd).maxOrNull() else base) to count
                }
            val key = if (effective) "effectivePaidThrough" else "paidThrough"
            metrics["subscriptions.${key}Current"] =
                kpi(
                    dates.filter { it.first?.let { d -> d >= today } == true }.sumOf { it.second },
                    "SNAPSHOT_INVOICE_PERIOD_END_INCLUSIVE_DATE",
                    unknown = dates.filter { it.first == null }.sumOf { it.second },
                    reason = if (dates.any { it.first == null }) "NO_RECORDED_PAID_PERIOD" else null,
                )
            breakdowns["subscriptions.$key"] =
                dates.groupBy { it.first?.toString() ?: "UNKNOWN" }.toSortedMap()
                    .map { (key, values) -> KpiBucket(key, values.sumOf { it.second }) }
        }
        count(
            "subscriptions.withCourtesy",
            "venues v",
            "EXISTS (SELECT 1 FROM billing_adjustments a " +
                "WHERE a.venue_id = v.id AND a.created_at < ?)",
            listOf(window.asOf),
            "SNAPSHOT_COURTESY_NOT_PAYMENT",
        )
    }
    if (section in setOf("all", "invoices")) {
        states("invoices.states", "billing_invoices", "status", "created_at < ?", listOf(window.asOf))
        for ((name, condition) in listOf(
            "open" to "status IN ('OPEN','PAST_DUE')",
            "overdue" to "status IN ('OPEN','PAST_DUE') AND due_at < ?",
            "paid" to "status = 'PAID'",
        )) {
            val args = if (name == "overdue") listOf(window.asOf, window.asOf) else listOf(window.asOf)
            count("invoices.$name", "billing_invoices", "created_at < ? AND $condition", args, "SNAPSHOT_INVOICE_STATE")
            val currencyRows =
                c.read(
                    "SELECT currency, SUM(CAST(amount_minor AS BIGINT)), COUNT(*) FROM billing_invoices " +
                        "WHERE created_at < ? AND $condition GROUP BY currency ORDER BY currency",
                    args,
                ) { Triple(it.getString(1), it.getBigDecimal(2), it.getLong(3)) }
            val valid = currencyRows.filter { runCatching { java.util.Currency.getInstance(it.first) }.isSuccess }
            val unknown = currencyRows.filterNot { it in valid }.sumOf { it.third }
            metrics["invoices.$name.currencyCoverage"] =
                kpi(
                    valid.sumOf { it.third }, "SNAPSHOT_INVOICE_STATE",
                    unknown = unknown, reason = if (unknown > 0) "INVALID_CURRENCY" else null,
                )
            invoiceFinances[name] =
                valid.map { (currency, amount, count) ->
                    FinancialKpis(
                        currency,
                        mapOf(
                            "amount" to
                                kpi(
                                    amount, "SNAPSHOT_INVOICE_STATE", count,
                                    unit = "MINOR_UNITS",
                                ),
                        ),
                    )
                }
        }
        val match =
            "p.invoice_id = i.id AND p.status = 'SUCCEEDED' AND p.amount_minor = i.amount_minor " +
                "AND p.currency = i.currency AND p.provider = i.provider AND p.occurred_at < ?"
        count(
            "invoices.manualConfirmed",
            "billing_invoices i",
            "i.status = 'PAID' AND i.paid_at >= ? AND i.paid_at < ? " +
                "AND EXISTS (SELECT 1 FROM billing_payments p WHERE $match AND " +
                "p.provider_event_id = CONCAT('manual:', CAST(i.id AS VARCHAR)))",
            range + window.asOf,
            "INVOICE_PAID_AT_MATCHED_MANUAL_CONFIRMATION",
        )
        count(
            "invoices.paidWithoutMatchingEvidence",
            "billing_invoices i",
            "i.status = 'PAID' AND i.created_at < ? " +
                "AND NOT EXISTS (SELECT 1 FROM billing_payments p WHERE $match)",
            listOf(window.asOf, window.asOf),
            "SNAPSHOT_RECONCILIATION_WARNING",
        )
        count(
            "invoices.inconsistentSuccessfulEvents",
            "billing_payments p JOIN billing_invoices i ON i.id = p.invoice_id",
            "p.status = 'SUCCEEDED' AND p.occurred_at < ? AND (i.status <> " +
                "'PAID' OR p.amount_minor <> i.amount_minor " +
                "OR p.currency <> i.currency OR p.provider <> i.provider)",
            listOf(window.asOf),
            "SNAPSHOT_RECONCILIATION_WARNING",
        )
        val multiple = c.count(MULTIPLE_MATCHING_SUCCESSES_SQL, listOf(window.asOf, window.asOf))
        metrics["invoices.multipleMatchingSuccesses"] = kpi(multiple, "SNAPSHOT_RECONCILIATION_WARNING", multiple)
        metrics["invoices.bankSettlement"] =
            kpi(
                null,
                "UNSUPPORTED",
                reason = "NO_BANK_SETTLEMENT_SOURCE",
                unit = "MINOR_UNITS",
            )
    }
    if (section in setOf("all", "onboarding")) {
        count("onboarding.total", "venue_connection_requests", "created_at < ?", listOf(window.asOf), "SNAPSHOT_AS_OF")
        count(
            "onboarding.created",
            "venue_connection_requests",
            "created_at >= ? AND created_at < ?",
            range,
            "REQUEST_CREATED_AT",
        )
        states("onboarding.states", "venue_connection_requests", "status", "created_at < ?", listOf(window.asOf))
        count(
            "onboarding.linked",
            "venue_connection_requests r",
            "r.created_at < ? AND EXISTS (SELECT 1 FROM venues v WHERE v.id = r.linked_venue_id)",
            listOf(window.asOf),
            "SNAPSHOT_CONFIRMED_VENUE_LINK",
        )
    }
    if (section in setOf("all", "support")) {
        val tickets = "thread_type = 'SUPPORT_TICKET' AND created_at < ?"
        count("support.total", "support_threads", tickets, listOf(window.asOf), "SNAPSHOT_SUPPORT_TICKET")
        val active = "$tickets AND status IN ('OPEN','NEW','IN_PROGRESS','WAITING_USER')"
        count("support.active", "support_threads", active, listOf(window.asOf), "SNAPSHOT_SUPPORT_TICKET")
        val age = c.milliseconds("created_at", "?")
        metrics["support.oldestActiveAge"] =
            c.read(
                "SELECT MAX($age), COUNT(*) FROM support_threads WHERE $active",
                listOf(window.asOf, window.asOf),
            ) { kpi(it.getBigDecimal(1), "SNAPSHOT_SUPPORT_TICKET", it.getLong(2), unit = "MILLISECONDS") }.single()
        val cohort = "t.thread_type = 'SUPPORT_TICKET' AND t.created_at >= ? AND t.created_at < ?"
        val response =
            "(SELECT MIN(m.created_at) FROM support_messages m WHERE m.thread_id = t.id " +
                "AND m.author_role IN ('VENUE','PLATFORM') AND m.author_user_id IS NOT NULL AND m.source <> 'SYSTEM' " +
                "AND m.created_at >= t.created_at AND m.created_at < ?)"
        val projection = "(SELECT t.created_at, $response AS replied_at FROM support_threads t WHERE $cohort) replies"
        val args = listOf(window.asOf) + range
        val unknown = c.count("SELECT COUNT(*) FROM $projection WHERE replied_at IS NULL", args)
        count("support.created", "support_threads t", cohort, range, "TICKET_CREATED_AT")
        metrics["support.ttfrUnknown"] = kpi(unknown, "TICKET_CREATED_AT_NO_HUMAN_REPLY")
        duration(
            "support.ttfr",
            projection,
            "created_at",
            "replied_at",
            "1 = 1",
            args,
            "TICKET_CREATED_AT_FIRST_RECORDED_HUMAN_REPLY",
            unknown,
            reason = if (unknown > 0) "NO_RECORDED_HUMAN_REPLY" else null,
        )
        metrics["support.resolutionTime"] =
            kpi(
                null,
                "UNSUPPORTED",
                reason = "NO_COMPLETE_RESOLUTION_HISTORY",
                unit = "MILLISECONDS",
            )
    }
}
