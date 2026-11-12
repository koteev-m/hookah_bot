package com.hookah.platform.backend.analytics

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset

internal fun <T> Connection.read(
    sql: String,
    args: List<Any?> = emptyList(),
    row: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { s ->
        args.forEachIndexed { index, value ->
            s.setObject(index + 1, if (value is Instant) value.atOffset(ZoneOffset.UTC) else value)
        }
        s.executeQuery().use { r -> buildList { while (r.next()) add(row(r)) } }
    }

internal fun Connection.count(
    sql: String,
    args: List<Any?> = emptyList(),
): Long =
    read(sql, args) {
        it.getLong(1)
    }.single()

internal fun Connection.milliseconds(
    start: String,
    end: String,
): String =
    if (metaData.databaseProductName == "PostgreSQL") {
        "EXTRACT(EPOCH FROM ($end - $start)) * 1000"
    } else {
        "DATEDIFF('MICROSECOND', $start, $end) / 1000.0"
    }

internal class KpiCollector(val c: Connection, val window: KpiWindow) {
    val invoiceFinances = linkedMapOf<String, List<FinancialKpis>>()
    val metrics = linkedMapOf<String, KpiValue>()
    val breakdowns = linkedMapOf<String, List<KpiBucket>>()

    fun count(
        key: String,
        from: String,
        where: String,
        args: List<Any?>,
        basis: String,
        unknown: Long = 0,
    ) {
        val n = c.count("SELECT COUNT(*) FROM $from WHERE $where", args)
        metrics[key] = kpi(n, basis, n, unknown)
    }

    fun states(
        key: String,
        from: String,
        column: String,
        where: String,
        args: List<Any?>,
    ) {
        breakdowns[key] =
            c.read(
                "SELECT $column, COUNT(*) FROM $from WHERE $where GROUP BY $column ORDER BY $column", args,
            ) { KpiBucket(it.getString(1) ?: "UNKNOWN", it.getLong(2)) }
    }

    fun duration(
        key: String,
        from: String,
        start: String,
        end: String,
        where: String,
        args: List<Any?>,
        basis: String,
        unknown: Long,
        reason: String? = null,
    ) {
        val expression = c.milliseconds(start, end)
        metrics[key] =
            c.read(
                "SELECT AVG(CAST(($expression) AS DECIMAL(30,3))), COUNT(*) FROM $from " +
                    "WHERE $where AND $end IS NOT NULL AND $end >= $start",
                args,
            ) {
                kpi(
                    it.getBigDecimal(1)?.setScale(
                        3,
                        java.math.RoundingMode.HALF_UP,
                    ),
                    basis,
                    it.getLong(2),
                    unknown,
                    "MILLISECONDS",
                    reason,
                )
            }.single()
    }
}
