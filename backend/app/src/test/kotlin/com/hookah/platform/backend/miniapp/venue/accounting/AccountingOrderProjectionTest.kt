package com.hookah.platform.backend.miniapp.venue.accounting

import com.hookah.platform.backend.miniapp.venue.orders.OrderBatchDetail
import com.hookah.platform.backend.miniapp.venue.orders.OrderBatchItemDetail
import com.hookah.platform.backend.miniapp.venue.orders.OrderDetail
import com.hookah.platform.backend.miniapp.venue.orders.OrderServiceChargeDetail
import com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountingOrderProjectionTest {
    @Test
    fun `canonical promotion gift is distinct from discount service charge and payment`() {
        val instant = Instant.parse("2026-11-07T20:00:00Z")
        val order =
            OrderDetail(
                orderId = 1,
                venueId = 1,
                tableId = 1,
                tableNumber = 1,
                status = OrderWorkflowStatus.CLOSED,
                createdAt = instant,
                updatedAt = instant,
                batches =
                    listOf(
                        OrderBatchDetail(
                            batchId = 1,
                            tabId = 7,
                            status = OrderWorkflowStatus.DELIVERED,
                            source = "MINIAPP",
                            comment = null,
                            createdAt = instant,
                            updatedAt = instant,
                            rejectedReasonCode = null,
                            rejectedReasonText = null,
                            items =
                                listOf(
                                    OrderBatchItemDetail(
                                        batchItemId = 1,
                                        itemId = 1,
                                        name = "Чай",
                                        qty = 1,
                                        priceMinor = 11000,
                                        currency = "RUB",
                                        discountPercent = 10,
                                    ),
                                    OrderBatchItemDetail(
                                        batchItemId = 2,
                                        itemId = 2,
                                        name = "Подарок по акции",
                                        qty = 1,
                                        priceMinor = 2000,
                                        currency = "RUB",
                                        promoDiscountMinor = 2000,
                                        isPromotionReward = true,
                                    ),
                                ),
                        ),
                    ),
                serviceCharges =
                    listOf(
                        OrderServiceChargeDetail(
                            1, "SHIFT_EXTENSION", null, "Продление", 1, 5000, 5000, "RUB", instant,
                        ),
                    ),
            )
        val sale = order.toAccountingSale(closed = true)
        assertNull(sale.issue)
        val totals = accountingTotals(listOf(sale))
        assertEquals(18000, totals.grossMinor)
        assertEquals(1100, totals.discountMinor)
        assertEquals(2000, totals.giftMinor)
        assertEquals(14900, totals.accruedMinor)
        assertEquals(0, totals.paidMinor)
        assertEquals(14900, totals.unpaidMinor)
        assertEquals(listOf(7L, 7L, null), sale.lines.map { it.tabId })
    }
}
