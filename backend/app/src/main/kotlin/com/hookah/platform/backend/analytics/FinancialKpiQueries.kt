package com.hookah.platform.backend.analytics

import com.hookah.platform.backend.miniapp.venue.accounting.AccountingReport
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingRepository
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingShiftHeader
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode

internal fun KpiCollector.finances(venueId: Long): Pair<List<FinancialKpis>, List<FinancialKpis>> {
    val json = Json { ignoreUnknownKeys = true }
    val closed =
        c.read(
            "SELECT final_report FROM venue_accounting_shifts WHERE venue_id = ? AND status = 'CLOSED' " +
                "AND closed_at >= ? AND closed_at < ? ORDER BY id",
            listOf(venueId, window.from, window.observedTo),
        ) { runCatching { json.decodeFromString<AccountingReport>(it.getString(1)) }.getOrNull() }
    val open =
        c.read(
            "SELECT id, timezone, opened_at FROM venue_accounting_shifts WHERE " +
                "venue_id = ? AND status = 'OPEN' AND opened_at < ?",
            listOf(venueId, window.asOf),
        ) { AccountingShiftHeader(it.getLong(1), "OPEN", it.getString(2), it.getTimestamp(3).toInstant().toString()) }
            .map { AccountingRepository(null).readOnlyReport(c, venueId, it) }

    fun valid(r: AccountingReport?): Boolean =
        r != null && r.venueId == venueId &&
            runCatching {
                java.util.Currency.getInstance(r.currency)
            }.isSuccess && r.sales.all { it.currency == r.currency && it.issue == null }
    val unknown = closed.count { !valid(it) }.toLong()
    metrics["finance.closedReports"] =
        kpi(
            closed.count { valid(it) }, "SHIFT_CLOSED_AT_FROZEN_REPORT", closed.size.toLong(), unknown,
            reason = if (unknown > 0) "REPORT_MISSING_OR_INCONSISTENT" else null,
        )
    metrics["finance.openShifts"] = kpi(open.size, "CURRENT_OPEN_SHIFT_PROVISIONAL", open.size.toLong())
    val openUnknown = open.count { !valid(it) }.toLong()
    metrics["finance.openReportCoverage"] =
        kpi(
            open.count { valid(it) }, "CURRENT_OPEN_SHIFT_PROVISIONAL",
            open.size.toLong(), openUnknown, reason = if (openUnknown > 0) "REPORT_MISSING_OR_INCONSISTENT" else null,
        )

    fun aggregate(
        reports: List<AccountingReport>,
        basis: String,
        missing: Long,
    ): List<FinancialKpis> =
        reports.groupBy { it.currency }.toSortedMap().map { (currency, rows) ->
            val selectors =
                linkedMapOf<String, (AccountingReport) -> Long>(
                    "accrued" to { it.totals.accruedMinor }, "operatorConfirmed" to { it.totals.paidMinor },
                    "discounts" to { it.totals.discountMinor }, "gifts" to { it.totals.giftMinor },
                    "unpaid" to { it.totals.unpaidMinor }, "sales" to { it.totals.salesCount.toLong() },
                    "cash" to { it.totals.cashMinor },
                    "card" to { it.totals.cardMinor },
                    "transfer" to { it.totals.transferMinor },
                )
            val amounts =
                selectors.mapValues { (name, selector) ->
                    val total =
                        try {
                            rows.fold(0L) { sum, row -> Math.addExact(sum, selector(row)) }
                        } catch (
                            _: ArithmeticException,
                        ) {
                            null
                        }
                    kpi(
                        total,
                        basis,
                        rows.size.toLong(),
                        missing,
                        if (name == "sales") "COUNT" else "MINOR_UNITS",
                        if (total == null) {
                            "NUMERIC_OVERFLOW"
                        } else if (missing > 0) {
                            "REPORT_MISSING_OR_INCONSISTENT"
                        } else {
                            null
                        },
                    )
                }.toMutableMap()
            val sales = amounts.getValue("sales").value?.toLong()
            val accrued = amounts.getValue("accrued").value?.toLong()
            amounts["averageAccruedSale"] =
                kpi(
                    if (sales == null || sales == 0L || accrued == null) {
                        null
                    } else {
                        BigDecimal(accrued).divide(BigDecimal(sales), 0, RoundingMode.HALF_UP)
                    },
                    basis, sales ?: 0, missing, "MINOR_UNITS",
                )
            FinancialKpis(currency, amounts)
        }
    return aggregate(closed.filterNotNull().filter { valid(it) }, "SHIFT_CLOSED_AT_FROZEN_REPORT", unknown) to
        aggregate(open.filter { valid(it) }, "CURRENT_OPEN_SHIFT_PROVISIONAL", openUnknown)
}
