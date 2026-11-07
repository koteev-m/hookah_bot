package com.hookah.platform.backend.miniapp.venue.accounting
import kotlinx.serialization.Serializable

@Serializable
data class AccountingLine(
    val id: String,
    val name: String,
    val qty: Int,
    val unitPriceMinor: Long,
    val menuItemId: Long? = null,
    val source: String = "MANUAL",
    val tabId: Long? = null,
    val baseDiscountMinor: Long = 0,
    val baseGiftMinor: Long = 0,
    val discountMinor: Long = 0,
    val giftReason: String? = null,
) {
    val gross: Long get() = unitPriceMinor * qty
    val discount: Long get() = baseDiscountMinor + discountMinor
    val gift: Long get() = if (giftReason != null) gross - discount else baseGiftMinor
    val payable: Long get() = gross - discount - gift
}

@Serializable
data class AccountingPayment(
    val id: String,
    val method: String,
    val amountMinor: Long,
    val recordedAt: String,
    val voidReason: String? = null,
)

@Serializable
data class AccountingSale(
    val id: String,
    val tableId: Long,
    val tableLabel: String,
    val source: String,
    val orderId: Long? = null,
    val displayNumber: Int? = null,
    val orderClosed: Boolean = true,
    val createdAt: String,
    val currency: String = "RUB",
    val lines: List<AccountingLine>,
    val payments: List<AccountingPayment> = emptyList(),
    val version: Long = 0,
    val cancelReason: String? = null,
    val disputeReason: String? = null,
    val originalPayableMinor: Long? = null,
    val issue: String? = null,
) {
    val payable: Long get() = if (cancelReason == null) lines.sumOf { it.payable } else 0
    val paid: Long get() = payments.filter { it.voidReason == null }.sumOf { it.amountMinor }
}

@Serializable
data class AccountingTotals(
    val salesCount: Int,
    val ordersCount: Int,
    val grossMinor: Long,
    val discountMinor: Long,
    val giftMinor: Long,
    val accruedMinor: Long,
    val paidMinor: Long,
    val unpaidMinor: Long,
    val cashMinor: Long,
    val cardMinor: Long,
    val transferMinor: Long,
)

@Serializable
data class AccountingBreakdown(
    val label: String,
    val qty: Long,
    val accruedMinor: Long,
)

@Serializable
data class AccountingReport(
    val shiftId: Long,
    val venueId: Long,
    val status: String,
    val timezone: String,
    val openedAt: String,
    val closedAt: String? = null,
    val currency: String = "RUB",
    val sales: List<AccountingSale>,
    val totals: AccountingTotals,
    val byTable: List<AccountingBreakdown>,
    val byItem: List<AccountingBreakdown>,
    val bySource: List<AccountingBreakdown>,
    val unpaidSaleIds: List<String>,
    val disputedSaleIds: List<String>,
    val reportHash: String = "",
)

@Serializable
data class AccountingShiftHeader(
    val id: Long,
    val status: String,
    val timezone: String,
    val openedAt: String,
    val closedAt: String? = null,
)

@Serializable
data class AccountingState(
    val current: AccountingReport? = null,
    val history: List<AccountingShiftHeader> = emptyList(),
    val nextHistoryBeforeId: Long? = null,
)

@Serializable
data class AccountingLineInput(
    val id: String? = null,
    val menuItemId: Long? = null,
    val name: String? = null,
    val qty: Int = 1,
    val unitPriceMinor: Long? = null,
    val discountMinor: Long = 0,
    val giftReason: String? = null,
)

@Serializable
data class AccountingCommand(
    val requestId: String,
    val action: String,
    val shiftId: Long? = null,
    val saleId: String? = null,
    val expectedVersion: Long? = null,
    val tableId: Long? = null,
    val lines: List<AccountingLineInput> = emptyList(),
    val reason: String? = null,
    val paymentMethod: String? = null,
    val amountMinor: Long? = null,
    val paymentId: String? = null,
    val expectedReportHash: String? = null,
    val acknowledgeUnpaid: Boolean = false,
)

internal fun accountingTotals(sales: List<AccountingSale>): AccountingTotals {
    val active = sales.filter { it.cancelReason == null }
    val payments = sales.flatMap { it.payments }.filter { it.voidReason == null }
    return AccountingTotals(
        salesCount = active.size,
        ordersCount = active.mapNotNull { it.orderId }.distinct().size,
        grossMinor = active.sumOf { sale -> sale.lines.sumOf { it.gross } },
        discountMinor = active.sumOf { sale -> sale.lines.sumOf { it.discount } },
        giftMinor = active.sumOf { sale -> sale.lines.sumOf { it.gift } },
        accruedMinor = active.sumOf { it.payable },
        paidMinor = payments.sumOf { it.amountMinor },
        unpaidMinor = sales.sumOf { (it.payable - it.paid).coerceAtLeast(0) },
        cashMinor = payments.filter { it.method == "CASH" }.sumOf { it.amountMinor },
        cardMinor = payments.filter { it.method == "CARD" }.sumOf { it.amountMinor },
        transferMinor = payments.filter { it.method == "TRANSFER" }.sumOf { it.amountMinor },
    )
}
