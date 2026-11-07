package com.hookah.platform.backend.miniapp.venue.accounting
import com.hookah.platform.backend.miniapp.venue.orders.OrderBatchItemStatus
import com.hookah.platform.backend.miniapp.venue.orders.OrderDetail
import com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus
import com.hookah.platform.backend.miniapp.venue.orders.toOrderBillSnapshot

/** Projection of the canonical bill; no guest/session/order is created for accounting. */
internal fun OrderDetail.toAccountingSale(closed: Boolean): AccountingSale {
    val bill = toOrderBillSnapshot()
    val items = batches.flatMap { it.items }.associateBy { it.batchItemId }
    val batchById = batches.associateBy { it.batchId }
    val lines =
        bill.activeItems.map { item ->
            val original = items.getValue(item.batchItemId)
            val reward = original.isPromotionReward
            AccountingLine(
                id = "item:${item.batchItemId}",
                name = item.name + (item.selectedOption?.let { " · ${it.name}" } ?: ""),
                qty = item.qty,
                unitPriceMinor = original.priceMinor ?: 0,
                menuItemId = item.itemId,
                source = batchById.getValue(item.batchId).source,
                tabId = batchById.getValue(item.batchId).tabId,
                baseDiscountMinor = item.manualDiscountMinor + if (reward) 0 else item.promoDiscountMinor,
                baseGiftMinor = if (reward) item.promoDiscountMinor else 0,
            )
        } +
            bill.serviceCharges.map { charge ->
                AccountingLine(
                    id = "service:${charge.id}",
                    name = charge.label,
                    qty = charge.qty,
                    unitPriceMinor = charge.unitPriceMinor,
                    source = "SERVICE_CHARGE",
                )
            }
    val sources = batches.map { it.source }.distinct()
    val missingPrice =
        batches.any { batch ->
            batch.status != OrderWorkflowStatus.CLOSED &&
                batch.items.any {
                    !it.isExcluded && it.itemStatus == OrderBatchItemStatus.ACTIVE && it.priceMinor == null
                }
        }
    val issue =
        when {
            lines.any { it.qty !in 1..1000 || it.unitPriceMinor !in 0..1_000_000_000L } ->
                "Сумма исходного счёта выходит за допустимые пределы учёта. Нужна сверка."
            missingPrice -> "Нет достоверной цены позиции. Нужна сверка исходного счёта."
            bill.currency != "RUB" || bill.activeItems.any { it.currency != "RUB" } ||
                bill.serviceCharges.any { it.currency != "RUB" } ->
                "MVP учитывает только рублёвые счета. Нужна сверка валюты."
            lines.any { it.payable < 0 } || lines.sumOf { it.payable } != bill.finalPayableTotalMinor ->
                "Сумма позиций расходится с исходным счётом. Нужна сверка."
            else -> null
        }
    return AccountingSale(
        id = "order:$orderId",
        orderId = orderId,
        displayNumber = displayNumber,
        tableId = tableId,
        tableLabel = "Стол $tableNumber",
        source = sources.singleOrNull() ?: "MIXED",
        orderClosed = closed,
        createdAt = createdAt.toString(),
        lines = lines,
        originalPayableMinor = bill.finalPayableTotalMinor,
        issue = issue,
    )
}
