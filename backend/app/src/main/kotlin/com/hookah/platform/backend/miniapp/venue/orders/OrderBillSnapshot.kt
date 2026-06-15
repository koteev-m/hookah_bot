package com.hookah.platform.backend.miniapp.venue.orders

private const val DEFAULT_BILL_CURRENCY = "RUB"

data class OrderBillSnapshot(
    val grossTotalMinor: Long,
    val manualDiscountTotalMinor: Long,
    val promoDiscountTotalMinor: Long,
    val loyaltyDiscountTotalMinor: Long,
    val excludedTotalMinor: Long,
    val canceledTotalMinor: Long,
    val rejectedTotalMinor: Long,
    val finalPayableTotalMinor: Long,
    val currency: String,
    val activeItems: List<OrderBillActiveItemSnapshot>,
    val promoDiscounts: List<OrderBillDiscountSnapshot>,
    val loyaltyDiscounts: List<OrderBillDiscountSnapshot>,
    val excludedItems: List<OrderBillExcludedItemSnapshot>,
    val serviceCharges: List<OrderBillServiceChargeSnapshot> = emptyList(),
)

data class OrderBillActiveItemSnapshot(
    val batchId: Long,
    val batchLabel: String,
    val batchItemId: Long,
    val itemId: Long,
    val name: String,
    val qty: Int,
    val selectedOption: OrderBillSelectedOptionSnapshot? = null,
    val preferenceNote: String? = null,
    val lineGrossMinor: Long,
    val manualDiscountMinor: Long,
    val promoDiscountMinor: Long,
    val linePayableMinor: Long,
    val currency: String?,
    val discountPercent: Int?,
)

data class OrderBillSelectedOptionSnapshot(
    val optionId: Long? = null,
    val name: String,
    val priceDeltaMinor: Long,
)

data class OrderBillDiscountSnapshot(
    val label: String,
    val discountMinor: Long,
    val currency: String,
    val ruleType: String?,
)

data class OrderBillServiceChargeSnapshot(
    val id: Long,
    val source: String,
    val sourceRequestId: Long?,
    val label: String,
    val qty: Int,
    val unitPriceMinor: Long,
    val totalMinor: Long,
    val currency: String,
)

data class OrderBillExcludedItemSnapshot(
    val batchId: Long,
    val batchLabel: String,
    val batchItemId: Long,
    val itemId: Long,
    val name: String,
    val qty: Int,
    val selectedOption: OrderBillSelectedOptionSnapshot? = null,
    val preferenceNote: String? = null,
    val lineGrossMinor: Long,
    val currency: String,
    val status: String,
    val reason: String?,
)

fun OrderDetail.toOrderBillSnapshot(defaultCurrency: String = DEFAULT_BILL_CURRENCY): OrderBillSnapshot {
    val labeledBatches = batches.mapIndexed { index, batch -> billBatchLabel(index) to batch }
    val activeItems =
        labeledBatches
            .filterNot { (_, batch) -> batch.isCanceledForBill() }
            .flatMap { (label, batch) ->
                batch.items
                    .filter { item -> item.isActiveBillItem() }
                    .map { item -> item.toActiveItemSnapshot(batch.batchId, label) }
            }
    val promoDiscounts =
        promotionDiscounts
            .filterNot { discount -> discount.isLoyaltyDiscount() }
            .map { discount -> discount.toBillDiscountSnapshot() }
    val loyaltyDiscounts =
        promotionDiscounts
            .filter { discount -> discount.isLoyaltyDiscount() }
            .map { discount -> discount.toBillDiscountSnapshot() }
    val serviceCharges = serviceCharges.map { charge -> charge.toBillServiceChargeSnapshot() }
    val excludedItems = buildExcludedItemSnapshots(labeledBatches, defaultCurrency)
    return OrderBillSnapshot(
        grossTotalMinor = activeItems.sumOf { item -> item.lineGrossMinor } + serviceCharges.sumOf { it.totalMinor },
        manualDiscountTotalMinor = activeItems.sumOf { item -> item.manualDiscountMinor },
        promoDiscountTotalMinor = promoDiscounts.sumOf { discount -> discount.discountMinor },
        loyaltyDiscountTotalMinor = loyaltyDiscounts.sumOf { discount -> discount.discountMinor },
        excludedTotalMinor =
            excludedItems
                .filter { item -> item.status == "excluded" }
                .sumOf { item -> item.lineGrossMinor },
        canceledTotalMinor =
            excludedItems
                .filter { item -> item.status == "canceled" }
                .sumOf { item -> item.lineGrossMinor },
        rejectedTotalMinor =
            excludedItems
                .filter { item -> item.status == "rejected_batch" }
                .sumOf { item -> item.lineGrossMinor },
        finalPayableTotalMinor =
            activeItems.sumOf { item -> item.linePayableMinor } + serviceCharges.sumOf { it.totalMinor },
        currency = resolveBillCurrency(defaultCurrency),
        activeItems = activeItems,
        promoDiscounts = promoDiscounts,
        loyaltyDiscounts = loyaltyDiscounts,
        excludedItems = excludedItems,
        serviceCharges = serviceCharges,
    )
}

private fun buildExcludedItemSnapshots(
    labeledBatches: List<Pair<String, OrderBatchDetail>>,
    defaultCurrency: String,
): List<OrderBillExcludedItemSnapshot> =
    labeledBatches.flatMap { (label, batch) ->
        batch.items.mapNotNull { item ->
            val status =
                when {
                    batch.isCanceledForBill() -> "rejected_batch"
                    item.itemStatus == OrderBatchItemStatus.CANCELED -> "canceled"
                    item.isExcluded -> "excluded"
                    else -> null
                } ?: return@mapNotNull null
            OrderBillExcludedItemSnapshot(
                batchId = batch.batchId,
                batchLabel = label,
                batchItemId = item.batchItemId,
                itemId = item.itemId,
                name = item.name,
                qty = item.qty,
                selectedOption = item.selectedOption?.toBillSelectedOptionSnapshot(),
                preferenceNote = item.preferenceNote,
                lineGrossMinor = item.lineGrossMinor(),
                currency = item.currency?.takeIf { it.isNotBlank() } ?: defaultCurrency,
                status = status,
                reason =
                    when (status) {
                        "rejected_batch" -> batch.rejectedReasonText ?: batch.rejectedReasonCode
                        "canceled" -> item.canceledReasonText ?: item.canceledReasonCode
                        else -> item.excludedReasonText
                    },
            )
        }
    }

private fun OrderBatchItemDetail.toActiveItemSnapshot(
    batchId: Long,
    batchLabel: String,
): OrderBillActiveItemSnapshot =
    OrderBillActiveItemSnapshot(
        batchId = batchId,
        batchLabel = batchLabel,
        batchItemId = batchItemId,
        itemId = itemId,
        name = name,
        qty = qty,
        selectedOption = selectedOption?.toBillSelectedOptionSnapshot(),
        preferenceNote = preferenceNote,
        lineGrossMinor = lineGrossMinor(),
        manualDiscountMinor = manualDiscountMinor(),
        promoDiscountMinor = promoDiscountMinor.coerceAtLeast(0L),
        linePayableMinor = payableMinor(),
        currency = currency,
        discountPercent = discountPercent?.takeIf { it in 1..100 },
    )

private fun OrderPromotionDiscount.toBillDiscountSnapshot(): OrderBillDiscountSnapshot =
    OrderBillDiscountSnapshot(
        label = label,
        discountMinor = discountMinor,
        currency = currency,
        ruleType = ruleType,
    )

private fun OrderBatchItemSelectedOption.toBillSelectedOptionSnapshot(): OrderBillSelectedOptionSnapshot =
    OrderBillSelectedOptionSnapshot(
        optionId = optionId,
        name = name,
        priceDeltaMinor = priceDeltaMinor,
    )

private fun OrderServiceChargeDetail.toBillServiceChargeSnapshot(): OrderBillServiceChargeSnapshot =
    OrderBillServiceChargeSnapshot(
        id = id,
        source = source,
        sourceRequestId = sourceRequestId,
        label = label,
        qty = qty,
        unitPriceMinor = unitPriceMinor,
        totalMinor = totalMinor,
        currency = currency,
    )

private fun OrderDetail.resolveBillCurrency(defaultCurrency: String): String =
    batches
        .flatMap { batch -> batch.items }
        .firstOrNull { item -> !item.currency.isNullOrBlank() }
        ?.currency
        ?: promotionDiscounts.firstOrNull { discount -> discount.currency.isNotBlank() }?.currency
        ?: serviceCharges.firstOrNull { charge -> charge.currency.isNotBlank() }?.currency
        ?: defaultCurrency

private fun OrderBatchItemDetail.lineGrossMinor(): Long = priceMinor?.let { price -> price * qty } ?: 0L

private fun OrderBatchItemDetail.manualDiscountMinor(): Long =
    discountPercent?.takeIf { it in 1..100 }?.let { discount -> lineGrossMinor() * discount / 100 } ?: 0L

private fun OrderBatchItemDetail.payableMinor(): Long =
    (lineGrossMinor() - manualDiscountMinor() - promoDiscountMinor.coerceAtLeast(0L)).coerceAtLeast(0L)

private fun OrderBatchItemDetail.isActiveBillItem(): Boolean = !isExcluded && itemStatus == OrderBatchItemStatus.ACTIVE

private fun OrderBatchDetail.isCanceledForBill(): Boolean =
    status == OrderWorkflowStatus.CLOSED ||
        !rejectedReasonCode.isNullOrBlank() ||
        !rejectedReasonText.isNullOrBlank()

private fun OrderPromotionDiscount.isLoyaltyDiscount(): Boolean =
    ruleType.equals("LOYALTY_NTH_HOOKAH", ignoreCase = true) ||
        label.contains("Лояльность", ignoreCase = true)

private fun billBatchLabel(index: Int): String = if (index == 0) "Основной заказ" else "Дозаказ $index"
