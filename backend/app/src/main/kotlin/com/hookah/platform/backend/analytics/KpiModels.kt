package com.hookah.platform.backend.analytics

import com.hookah.platform.backend.api.InvalidInputException
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

const val KPI_DEFINITION_VERSION = "2026-11-analytics-v1"

@Serializable
data class KpiValue(
    // Decimal strings preserve BIGINT and money precision in JavaScript consumers.
    val value: String?,
    val observedValue: String?,
    val unit: String,
    val basis: String,
    val availability: String,
    val coverage: String,
    val samples: Long,
    val unknown: Long,
    val reason: String? = null,
)

internal fun kpi(
    value: Number?,
    basis: String,
    samples: Long = 0,
    unknown: Long = 0,
    unit: String = "COUNT",
    reason: String? = null,
): KpiValue =
    KpiValue(
        value = if (unknown == 0L) value.decimalString() else null,
        observedValue = value.decimalString(),
        unit = unit,
        basis = basis,
        availability =
            if (unknown > 0) {
                "PARTIAL"
            } else if (value == null) {
                "UNAVAILABLE"
            } else {
                "AVAILABLE"
            },
        coverage =
            when {
                basis == "UNSUPPORTED" -> "UNAVAILABLE_SOURCE"
                unknown > 0 -> "PARTIAL"
                else -> "COMPLETE_RECORDED_SOURCE"
            },
        samples = samples,
        unknown = unknown,
        reason =
            reason ?: if (unknown > 0) {
                "LEGACY_HISTORY_UNKNOWN"
            } else if (value == null) {
                "NO_MEASURABLE_SAMPLE"
            } else {
                null
            },
    )

private fun Number?.decimalString(): String? = if (this is BigDecimal) toPlainString() else this?.toString()

internal fun ratio(
    numerator: Long,
    denominator: Long,
): BigDecimal? =
    if (denominator == 0L) null else BigDecimal(numerator).divide(BigDecimal(denominator), 6, RoundingMode.HALF_UP)

@Serializable
data class KpiPeriod(val from: String, val to: String, val timezone: String, val interval: String = "[from,to)")

internal data class KpiWindow(val from: Instant, val to: Instant, val zone: ZoneId, val asOf: Instant) {
    val observedTo: Instant get() = minOf(to, asOf)

    fun dto() = KpiPeriod(from.toString(), to.toString(), zone.id)
}

internal data class KpiRequest(
    val from: String,
    val to: String,
    val section: String = "all",
    val tableId: Long? = null,
    val search: String? = null,
    val category: String? = null,
    val currency: String? = null,
    val sort: String = "popular",
    val page: Int = 1,
    val categoryKey: String? = null,
    val expandedCategoryKey: String? = null,
) {
    fun window(
        zone: ZoneId,
        asOf: Instant,
    ): KpiWindow {
        val start = parseDate(from)
        val end = parseDate(to)
        val days = ChronoUnit.DAYS.between(start, end)
        if (days !in 1..370) throw InvalidInputException("ANALYTICS_RANGE_MUST_BE_1_TO_370_DAYS")
        return KpiWindow(
            start.atStartOfDay(zone).toInstant(),
            end.atStartOfDay(zone).toInstant(),
            zone,
            asOf.truncatedTo(ChronoUnit.MICROS),
        )
    }

    private fun parseDate(value: String): LocalDate =
        try {
            if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) throw IllegalArgumentException()
            LocalDate.parse(value)
        } catch (_: Exception) {
            throw InvalidInputException("ANALYTICS_INVALID_DATE")
        }
}

@Serializable
data class KpiBucket(val key: String, val count: Long)

@Serializable
data class FinancialKpis(val currency: String, val metrics: Map<String, KpiValue>)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class KpiSnapshot(
    val definitionVersion: String = KPI_DEFINITION_VERSION,
    val asOf: String,
    val period: KpiPeriod,
    val venueId: Long? = null,
    val section: String,
    val tableId: Long? = null,
    val metrics: Map<String, KpiValue>,
    val breakdowns: Map<String, List<KpiBucket>> = emptyMap(),
    val closedShiftFinances: List<FinancialKpis> = emptyList(),
    val openShiftFinances: List<FinancialKpis> = emptyList(),
    val invoiceFinances: Map<String, List<FinancialKpis>> = emptyMap(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val products: ProductReport? = null,
    val limitations: List<String> =
        listOf("RECORDED_DOMAIN_FACTS_ONLY", "STATE_BREAKDOWNS_ARE_SNAPSHOT_NOT_TRANSITIONS"),
)
