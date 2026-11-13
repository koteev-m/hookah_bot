package com.hookah.platform.backend.analytics

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingSale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant

internal const val PRODUCT_DELIVERY_SQL =
    "SELECT i.menu_item_id,i.item_name_snapshot,i.qty,i.base_unit_price_minor_snapshot,i.currency_snapshot," +
        "op.option_name_snapshot,op.price_delta_minor_snapshot,GREATEST(COALESCE(r.reward_qty,0), " +
        "CASE WHEN lr.id IS NULL THEN 0 ELSE 1 END),i.id,b.order_id FROM order_batch_items i " +
        "JOIN order_batches b ON b.id=i.order_batch_id JOIN orders o ON o.id=b.order_id " +
        "LEFT JOIN order_batch_item_options op ON op.order_batch_item_id=i.id " +
        "LEFT JOIN order_promotion_reward_items r ON r.reward_order_batch_item_id=i.id " +
        "LEFT JOIN loyalty_redemptions lr ON lr.order_batch_item_id=i.id AND lr.venue_id=o.venue_id " +
        "AND lr.status='APPLIED' " +
        "WHERE o.venue_id=? AND o.status <> 'CANCELLED' AND b.status <> 'REJECTED' " +
        "AND i.is_excluded=FALSE AND COALESCE(i.item_status,'ACTIVE')='ACTIVE' " +
        "AND COALESCE(b.first_delivered_at,i.fulfilled_at)>=? AND COALESCE(b.first_delivered_at,i.fulfilled_at)<? " +
        "ORDER BY COALESCE(b.first_delivered_at,i.fulfilled_at),i.id"

@Serializable
data class ProductRow(
    val itemId: Long,
    val name: String,
    val category: String,
    val currentlyAvailable: Boolean,
    val source: String,
    val currency: String,
    val units: String,
    val giftUnits: String?,
    val nonGiftUnits: String?,
    val snapshotGrossMinor: String?,
    val options: List<String>,
    val catalogCreatedAt: String? = null,
    val categoryActive: Boolean? = null,
    val categoryId: Long? = null,
)

@Serializable
data class ProductCurrencyTotal(val currency: String, val gross: KpiValue)

@Serializable
data class ProductTotals(val units: KpiValue, val giftUnits: KpiValue, val grossAmounts: List<ProductCurrencyTotal>)

@Serializable
data class ProductCategoryOption(val key: String, val id: Long?, val name: String)

@Serializable
data class ProductCategoryTotal(val category: ProductCategoryOption, val totals: ProductTotals)

@Serializable
data class ProductCategoryItem(
    val itemId: Long,
    val name: String,
    val currency: String,
    val units: KpiValue,
    val gross: KpiValue,
    val sources: List<ProductRow>,
)

@Serializable
data class ProductCategoryPage(
    val key: String,
    val page: Int,
    val totalRows: Int,
    val rows: List<ProductCategoryItem>,
    val pageSize: Int = 50,
)

@Serializable
data class ProductReport(
    val rows: List<ProductRow>,
    val totalRows: Int,
    val page: Int,
    val pageSize: Int = 50,
    val categories: List<String>,
    val currencies: List<String>,
    val coverage: KpiValue,
    val unassignedManualUnits: String,
    val basis: String = "ITEM_FULFILLED_OR_BATCH_FIRST_DELIVERED_AT;MANUAL_SALE_CREATED_AT",
    val netAmount: KpiValue = kpi(null, "UNSUPPORTED", reason = "ORDER_ADJUSTMENTS_NOT_ALLOCATED"),
    val summary: ProductTotals? = null,
    val categoryOptions: List<ProductCategoryOption> = emptyList(),
    val categoryTotals: List<ProductCategoryTotal> = emptyList(),
    val categoryPage: ProductCategoryPage? = null,
)

private data class ProductAccumulator(
    val id: Long,
    var name: String,
    val category: String,
    val available: Boolean,
    val source: String,
    val currency: String,
    val categoryId: Long?,
    var units: Long = 0,
    var gifts: Long = 0,
    var gross: BigDecimal = BigDecimal.ZERO,
    var missingPrice: Boolean = false,
    var unknownGifts: Boolean = false,
    val options: MutableSet<String> = sortedSetOf(),
)

/** Complete authoritative range is reduced before search/sort/page; never a projection of top-20. */
internal fun KpiCollector.products(
    venue: Long,
    request: KpiRequest,
): ProductReport {
    data class Catalog(
        val id: Long,
        val name: String,
        val category: String,
        val available: Boolean,
        val currency: String,
        val createdAt: String,
        val active: Boolean,
        val categoryId: Long,
    )
    val catalog =
        c.read(
            "SELECT m.id,m.name,cat.name,m.is_available,m.currency,m.created_at,cat.is_active,cat.id " +
                "FROM menu_items m " +
                "JOIN menu_categories cat ON cat.id=m.category_id AND " +
                "cat.venue_id=m.venue_id WHERE m.venue_id = ? ORDER BY m.id",
            listOf(venue),
        ) {
            Catalog(
                it.getLong(1),
                it.getString(2),
                it.getString(3) ?: "",
                it.getBoolean(4) && it.getBoolean(7),
                it.getString(5),
                it.getTimestamp(6).toInstant().toString(),
                it.getBoolean(7),
                it.getLong(8),
            )
        }.associateBy { it.id }
    val rows = linkedMapOf<Triple<Long, String, String>, ProductAccumulator>()
    var unknown =
        c.count(
            "SELECT COUNT(*) FROM order_batches b JOIN orders o ON o.id=b.order_id WHERE o.venue_id=? " +
                "AND b.created_at < ? AND NOT b.status_history_complete",
            listOf(venue, window.observedTo),
        )
    var historyUnknown = unknown

    fun accumulator(
        id: Long,
        name: String?,
        source: String,
        currency: String,
    ): ProductAccumulator {
        val item = catalog[id]
        return rows.getOrPut(Triple(id, source, currency)) {
            ProductAccumulator(
                id,
                name ?: item?.name ?: "Архивная позиция",
                item?.category ?: "",
                item?.available ?: false,
                source,
                currency,
                item?.categoryId,
            )
        }.also { if (name != null) it.name = name }
    }
    val json = Json { ignoreUnknownKeys = true }
    val accounting =
        c.read(
            "SELECT a.id,a.snapshot_json FROM venue_accounting_sales a JOIN venue_accounting_shifts " +
                "s ON s.id=a.shift_id " +
                "WHERE s.venue_id=? ORDER BY a.id",
            listOf(venue),
        ) { it.getString(1) to runCatching { json.decodeFromString<AccountingSale>(it.getString(2)) }.getOrNull() }
    val digitalGifts =
        accounting.filter { (id, sale) ->
            id.startsWith("order:") || sale?.lines?.any { it.giftReason != null || it.baseGiftMinor != 0L } == true
        }.groupBy { (id, sale) -> id.removePrefix("order:").toLongOrNull() ?: sale?.orderId }
    c.read(PRODUCT_DELIVERY_SQL, listOf(venue, window.from, window.observedTo)) { r ->
        val qty = r.getLong(3)
        val gift = r.getLong(8).coerceIn(0, qty)
        val row = accumulator(r.getLong(1), r.getString(2), "DIGITAL_DELIVERY", r.getString(5) ?: "UNKNOWN")
        row.units = Math.addExact(row.units, qty)
        val price = r.getBigDecimal(4)
        val unitPrice = price?.plus(r.getBigDecimal(7) ?: BigDecimal.ZERO)
        var knownGifts: Long? = gift
        for ((recordId, sale) in digitalGifts[r.getLong(10)].orEmpty()) {
            val line = sale?.lines?.singleOrNull { it.id == "item:${r.getLong(9)}" }
            // Accounting is evidence about this exact delivered line, never another quantity source.
            val hasGift = line?.let { it.giftReason != null || it.baseGiftMinor != 0L } == true
            if (sale == null || (line == null && sale.lines.any { it.giftReason != null || it.baseGiftMinor != 0L })) {
                knownGifts = null
            } else if (hasGift) {
                val valid =
                    recordId == "order:${r.getLong(10)}" && sale.id == recordId &&
                        sale.orderId == r.getLong(10) && sale.issue == null && sale.cancelReason == null &&
                        sale.disputeReason == null && sale.currency == row.currency &&
                        line.menuItemId == row.id && line.qty.toLong() == qty && unitPrice != null &&
                        line.unitPriceMinor.toBigDecimal().compareTo(unitPrice) == 0 &&
                        line.baseDiscountMinor == 0L && line.discountMinor == 0L && line.baseGiftMinor >= 0 &&
                        line.baseGiftMinor.toBigDecimal() <= unitPrice * qty.toBigDecimal()
                knownGifts =
                    if (!valid || knownGifts == null) {
                        null
                    } else {
                        when {
                            line!!.giftReason != null -> qty
                            line.baseGiftMinor.toBigDecimal() == unitPrice!! * qty.toBigDecimal() -> qty
                            line.baseGiftMinor.toBigDecimal() == unitPrice!! * gift.toBigDecimal() -> knownGifts
                            else -> null // A monetary adjustment alone does not allocate indivisible units.
                        }
                    }
            }
        }
        if (knownGifts == null) {
            row.unknownGifts = true
            unknown++
        } else {
            row.gifts = Math.addExact(row.gifts, knownGifts)
        }
        if (price == null || price.signum() < 0 || r.getString(2) == null || r.getString(5) == null) {
            row.missingPrice = true
            unknown++
        } else {
            row.gross += (price + (r.getBigDecimal(7) ?: BigDecimal.ZERO)) * qty.toBigDecimal()
        }
        r.getString(6)?.let { row.options.add(it) }
    }
    val textUnknown =
        c.count(
            "SELECT COUNT(*) FROM order_batches b JOIN orders o ON o.id=b.order_id WHERE o.venue_id=? " +
                "AND b.first_delivered_at>=? AND b.first_delivered_at<? " +
                "AND NOT EXISTS (SELECT 1 FROM order_batch_items i WHERE i.order_batch_id=b.id)",
            listOf(venue, window.from, window.observedTo),
        )
    unknown = Math.addExact(unknown, textUnknown)
    historyUnknown = Math.addExact(historyUnknown, textUnknown)
    var unassigned = 0L
    for ((_, sale) in accounting) {
        if (sale == null) {
            unknown++
            historyUnknown++
        } else if (sale.orderId == null && sale.cancelReason == null && sale.disputeReason == null) {
            val at = runCatching { Instant.parse(sale.createdAt) }.getOrNull()
            if (at == null) {
                unknown++
                historyUnknown++
            } else if (at >= window.from && at < window.observedTo) {
                if (sale.source != "MANUAL" || sale.issue != null ||
                    sale.lines.any { it.qty <= 0 || it.unitPriceMinor < 0 }
                ) {
                    unknown++
                    historyUnknown++
                } else {
                    for (line in sale.lines) {
                        val id = line.menuItemId
                        if (id == null) {
                            unassigned = Math.addExact(unassigned, line.qty.toLong())
                        } else {
                            val row = accumulator(id, line.name, "MANUAL_SALE", sale.currency)
                            row.units = Math.addExact(row.units, line.qty.toLong())
                            if (line.giftReason != null) {
                                row.gifts = Math.addExact(row.gifts, line.qty.toLong())
                            } else if (line.baseGiftMinor != 0L) {
                                row.unknownGifts = true
                                unknown++
                            }
                            row.gross += line.unitPriceMinor.toBigDecimal() * line.qty.toBigDecimal()
                        }
                    }
                }
            }
        }
    }
    val represented = rows.keys.map { it.first }.toHashSet()
    for (item in catalog.values) if (item.id !in represented) {
        accumulator(
            item.id,
            item.name,
            "DIGITAL_DELIVERY",
            item.currency,
        )
    }
    val categories = catalog.values.map { it.category }.distinct().sorted()
    val currencies = rows.values.map { it.currency }.distinct().sorted()
    val categoryOptions =
        rows.values.map {
            ProductCategoryOption(
                it.categoryId?.toString() ?: "uncategorized",
                it.categoryId,
                it.category.ifEmpty {
                    "Без категории"
                },
            )
        }.distinctBy { it.key }.sortedWith(compareBy({ it.name }, { it.key }))
    if (listOfNotNull(request.categoryKey, request.expandedCategoryKey).any { key ->
            key != "uncategorized" && categoryOptions.none { it.key == key }
        }
    ) {
        throw InvalidInputException("ANALYTICS_INVALID_PRODUCT_FILTER")
    }
    val matchingIds =
        rows.values.filter {
            request.search == null || it.name.contains(request.search, ignoreCase = true)
        }.map { it.id }.toHashSet()
    val scoped =
        rows.values.filter {
            it.id in matchingIds &&
                (request.category == null || it.category == request.category) &&
                (
                    request.categoryKey == null ||
                        (it.categoryId?.toString() ?: "uncategorized") == request.categoryKey
                )
        }
    val filtered = scoped.filter { request.currency == null || it.currency == request.currency }
    val history = Math.addExact(historyUnknown, unassigned)

    fun total(
        values: List<ProductAccumulator>,
        currency: String? = request.currency,
        scope: List<ProductAccumulator> = values,
    ): ProductTotals {
        val unknownCurrencies = scope.count { it.currency == "UNKNOWN" }.toLong()
        val excludedCurrency = if (currency != null && currency != "UNKNOWN") unknownCurrencies else 0L
        val amounts =
            (values.map { it.currency } + listOfNotNull(currency)).distinct().sorted().map { cur ->
                val same = values.filter { it.currency == cur }
                ProductCurrencyTotal(
                    cur,
                    kpi(
                        same.fold(BigDecimal.ZERO) { sum, row -> sum + row.gross },
                        "HISTORICAL_GROSS_BEFORE_DISCOUNTS_GIFTS",
                        unknown =
                            Math.addExact(
                                Math.addExact(history, if (cur == "UNKNOWN") 0L else unknownCurrencies),
                                same.count { it.missingPrice }.toLong(),
                            ),
                        unit = "MINOR",
                        reason = if (cur != "UNKNOWN" && unknownCurrencies > 0) "PRODUCT_CURRENCY_UNKNOWN" else null,
                    ),
                )
            }
        return ProductTotals(
            kpi(
                values.fold(java.math.BigInteger.ZERO) { sum, row -> sum + row.units.toBigInteger() },
                "RECORDED_PRODUCT_UNITS",
                unknown = Math.addExact(history, excludedCurrency),
                reason = if (excludedCurrency > 0) "PRODUCT_CURRENCY_UNKNOWN" else null,
            ),
            kpi(
                values.fold(
                    java.math.BigInteger.ZERO,
                ) { sum, row -> sum + row.gifts.toBigInteger() },
                "RECORDED_GIFT_UNITS",
                unknown =
                    Math.addExact(
                        Math.addExact(history, excludedCurrency),
                        values.count {
                            it.unknownGifts
                        }.toLong(),
                    ),
                reason = if (excludedCurrency > 0) "PRODUCT_CURRENCY_UNKNOWN" else null,
            ),
            amounts,
        )
    }

    fun sourceRow(it: ProductAccumulator) =
        ProductRow(
            it.id, it.name, it.category, it.available, it.source, it.currency,
            it.units.toString(), if (it.unknownGifts) null else it.gifts.toString(),
            if (it.unknownGifts) null else (it.units - it.gifts).toString(),
            if (it.missingPrice) null else it.gross.toPlainString(), it.options.toList(),
            catalog[it.id]?.createdAt, catalog[it.id]?.active, it.categoryId,
        )
    val groups =
        filtered.groupBy { it.id to it.currency }.values.map { sources ->
            val ordered = sources.sortedBy { it.source }
            val totals = total(ordered, ordered.first().currency, scoped.filter { it.id == ordered.first().id })
            ProductCategoryItem(
                ordered.first().id,
                ordered.first().name,
                ordered.first().currency,
                totals.units,
                totals.grossAmounts.single().gross,
                ordered.map(::sourceRow),
            )
        }
    val categoryTotals =
        categoryOptions.mapNotNull { category ->
            val values = filtered.filter { (it.categoryId?.toString() ?: "uncategorized") == category.key }
            val scope = scoped.filter { (it.categoryId?.toString() ?: "uncategorized") == category.key }
            if (values.isEmpty() && (
                    request.currency == null || request.currency == "UNKNOWN" ||
                        scope.none { it.currency == "UNKNOWN" }
                )
            ) {
                null
            } else {
                ProductCategoryTotal(category, total(values, scope = scope))
            }
        }
    val categoryPage =
        request.expandedCategoryKey?.let { key ->
            val selected = groups.filter { (it.sources.first().categoryId?.toString() ?: "uncategorized") == key }
            val ordered =
                when (request.sort) {
                    "rare" ->
                        selected.sortedWith(
                            compareBy({ it.units.observedValue!!.toBigInteger() }, { it.itemId }, { it.currency }),
                        )
                    "amount" ->
                        selected.sortedWith(
                            compareByDescending<ProductCategoryItem> {
                                it.gross.observedValue!!.toBigDecimal()
                            }.thenBy { it.itemId }.thenBy { it.currency },
                        )
                    else ->
                        selected.sortedWith(
                            compareByDescending<ProductCategoryItem> {
                                it.units.observedValue!!.toBigInteger()
                            }.thenBy { it.itemId }.thenBy { it.currency },
                        )
                }
            ProductCategoryPage(key, request.page, ordered.size, ordered.drop((request.page - 1) * 50).take(50))
        }
    val sorted =
        when (request.sort) {
            "rare" -> filtered.sortedWith(compareBy({ it.units }, { it.id }, { it.source }, { it.currency }))
            "amount" ->
                filtered.sortedWith(
                    compareByDescending<ProductAccumulator> {
                        if (it.missingPrice) BigDecimal(-1) else it.gross
                    }.thenBy { it.id }.thenBy { it.source },
                )
            else ->
                filtered.sortedWith(
                    compareByDescending<ProductAccumulator> {
                        it.units
                    }.thenBy { it.id }.thenBy { it.source }.thenBy { it.currency },
                )
        }
    return ProductReport(
        rows =
            sorted.drop((request.page - 1) * 50).take(50).map(::sourceRow),
        totalRows = filtered.size,
        page = request.page,
        categories = categories,
        currencies = currencies,
        coverage =
            kpi(
                rows.values.fold(java.math.BigInteger.ZERO) { sum, row ->
                    sum + row.units.toBigInteger()
                },
                "CONFIRMED_ITEMS_AND_MANUAL_SALES",
                unknown = Math.addExact(unknown, unassigned),
            ),
        unassignedManualUnits = unassigned.toString(),
        summary = total(filtered, scope = scoped),
        categoryOptions = categoryOptions,
        categoryTotals = categoryTotals,
        categoryPage = categoryPage,
    )
}

internal fun KpiCollector.additions(venue: Long) {
    val base =
        "order_batches b JOIN orders o ON o.id=b.order_id LEFT JOIN tab t ON t.id=b.tab_id " +
            "AND t.venue_id=o.venue_id AND t.table_session_id=o.table_session_id"
    val scope = "o.venue_id=? AND o.status='CLOSED' AND o.closed_at>=? AND o.closed_at<?"
    val args = listOf(venue, window.from, window.observedTo)
    val unknown =
        c.count("SELECT COUNT(*) FROM $base WHERE $scope AND (t.id IS NULL OR NOT b.status_history_complete)", args) +
            c.count(
                "SELECT COUNT(*) FROM orders WHERE venue_id=? AND status='CLOSED' AND closed_at IS NULL",
                listOf(venue),
            )
    val performed =
        "b.status <> 'REJECTED' AND EXISTS (SELECT 1 FROM order_batch_items i WHERE i.order_batch_id=b.id " +
            "AND NOT i.is_excluded AND COALESCE(i.item_status,'ACTIVE')='ACTIVE' AND " +
            "(i.fulfilled_at < ? OR b.first_delivered_at < ?))"
    val counts =
        c.read(
            "SELECT o.id,b.tab_id,COUNT(*) FROM $base WHERE $scope AND t.id IS NOT NULL " +
                "AND t.status='CLOSED' AND $performed GROUP BY o.id,b.tab_id",
            args + listOf(window.asOf, window.asOf),
        ) { it.getLong(3) }
    val tabs = counts.size.toLong()
    metrics["additions.completedTabs"] = kpi(tabs, "CLOSED_TABS_OF_ORDERS_CLOSED_AT", unknown = unknown)
    metrics["additions.none"] = kpi(counts.count { it == 1L }, "CLOSED_TABS_OF_ORDERS_CLOSED_AT", unknown = unknown)
    metrics["additions.one"] = kpi(counts.count { it == 2L }, "CLOSED_TABS_OF_ORDERS_CLOSED_AT", unknown = unknown)
    metrics["additions.twoOrMore"] =
        kpi(counts.count { it >= 3L }, "CLOSED_TABS_OF_ORDERS_CLOSED_AT", unknown = unknown)
    metrics["additions.count"] = kpi(counts.sumOf { it - 1 }, "CLOSED_TABS_OF_ORDERS_CLOSED_AT", unknown = unknown)
    metrics["additions.share"] =
        kpi(
            ratio(
                counts.count {
                    it > 1
                }.toLong(),
                tabs,
            ),
            "CLOSED_TABS_OF_ORDERS_CLOSED_AT", tabs, unknown, "RATIO",
        )
    val openTabs =
        c.count(
            "SELECT COUNT(*) FROM (SELECT o.id,b.tab_id FROM $base WHERE o.venue_id=? " +
                "AND o.status <> 'CANCELLED' AND t.status='ACTIVE' AND $performed GROUP BY " +
                "o.id,b.tab_id) x",
            listOf(venue, window.asOf, window.asOf),
        )
    val unknownOpen =
        c.count(
            "SELECT COUNT(*) FROM $base WHERE o.venue_id=? AND o.status <> 'CANCELLED' " +
                "AND (t.id IS NULL OR NOT b.status_history_complete)",
            listOf(venue),
        )
    metrics["additions.openTabs"] = kpi(openTabs, "ACTIVE_TABS_WITH_FULFILLED_ITEMS_SNAPSHOT", unknown = unknownOpen)
    val completed =
        "WITH performed AS (SELECT b.id,b.order_id,b.tab_id,b.first_delivered_at " +
            "FROM $base WHERE $scope AND t.status='CLOSED' AND $performed) "
    breakdowns["additions.items"] =
        c.read(
            completed +
                "SELECT i.menu_item_id, MAX(i.item_name_snapshot), SUM(CAST(i.qty AS BIGINT)) FROM performed p " +
                "JOIN order_batch_items i ON i.order_batch_id=p.id WHERE p.id > (SELECT " +
                "MIN(first_send.id) FROM performed first_send " +
                "WHERE first_send.order_id=p.order_id AND first_send.tab_id=p.tab_id) AND NOT i.is_excluded " +
                "AND COALESCE(i.item_status,'ACTIVE')='ACTIVE' AND i.item_name_snapshot IS NOT NULL " +
                "AND COALESCE(p.first_delivered_at,i.fulfilled_at) < ? " +
                "GROUP BY i.menu_item_id ORDER BY SUM(CAST(i.qty AS BIGINT)) DESC,i.menu_item_id LIMIT 20",
            args + listOf(window.asOf, window.asOf, window.asOf),
        ) { KpiBucket("${it.getLong(1)}:${it.getString(2)}", it.getLong(3)) }
}
