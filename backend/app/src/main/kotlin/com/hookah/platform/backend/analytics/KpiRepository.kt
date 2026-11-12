package com.hookah.platform.backend.analytics

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.miniapp.venue.VenueRoleMapping
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.time.Clock
import java.time.ZoneId
import javax.sql.DataSource

internal class KpiRepository(private val dataSource: DataSource?, private val clock: Clock = Clock.systemUTC()) {
    suspend fun venue(
        venueId: Long,
        actor: Long,
        request: KpiRequest,
    ): KpiSnapshot =
        snapshot { c ->
            val role =
                c.read("SELECT role FROM venue_members WHERE venue_id = ? AND user_id = ?", listOf(venueId, actor)) {
                    VenueRoleMapping.fromDb(it.getString(1))?.name
                }.singleOrNull()
            if (role !in setOf("OWNER", "MANAGER")) throw ForbiddenException()
            if (request.tableId != null && c.count(
                    "SELECT COUNT(*) FROM venue_tables WHERE id = ? AND venue_id = ?",
                    listOf(request.tableId, venueId),
                ) != 1L
            ) {
                throw InvalidInputException("ANALYTICS_INVALID_TABLE")
            }
            val rawZone =
                c.read("SELECT timezone FROM venue_settings WHERE venue_id = ?", listOf(venueId)) {
                    it.getString(1)
                }.singleOrNull()
            val zone =
                runCatching { ZoneId.of(rawZone ?: VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE) }
                    .getOrElse { ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE) }
            val window = request.window(zone, clock.instant())
            val collector = KpiCollector(c, window)
            collector.venueOperations(venueId, request)
            val financials =
                if (request.section in setOf("all", "finance")) {
                    collector.finances(
                        venueId,
                    )
                } else {
                    emptyList<FinancialKpis>() to emptyList()
                }
            KpiSnapshot(
                asOf = window.asOf.toString(), period = window.dto(), venueId = venueId, section = request.section,
                tableId = request.tableId, metrics = collector.metrics, breakdowns = collector.breakdowns,
                closedShiftFinances = financials.first, openShiftFinances = financials.second,
                limitations =
                    listOf(
                        "RECORDED_DOMAIN_FACTS_ONLY", "STATE_BREAKDOWNS_ARE_SNAPSHOT_NOT_TRANSITIONS",
                        "CLOSED_SHIFTS_NOT_CALENDAR_CASH_FLOW", "VOID_PAYMENT_NOT_REFUND", "SEATINGS_NOT_UNIQUE_PEOPLE",
                    ) +
                        if (rawZone == null || runCatching { ZoneId.of(rawZone) }.isFailure) {
                            listOf("VENUE_TIMEZONE_FALLBACK")
                        } else {
                            emptyList()
                        },
            )
        }

    suspend fun platform(request: KpiRequest): KpiSnapshot =
        snapshot { c ->
            val window = request.window(ZoneId.of("UTC"), clock.instant())
            val collector = KpiCollector(c, window)
            collector.platform(request.section)
            KpiSnapshot(
                invoiceFinances = collector.invoiceFinances,
                asOf = window.asOf.toString(),
                period = window.dto(),
                section = request.section,
                metrics = collector.metrics,
                breakdowns = collector.breakdowns,
                limitations =
                    listOf(
                        "RECORDED_DOMAIN_FACTS_ONLY",
                        "STATE_BREAKDOWNS_ARE_SNAPSHOT_NOT_TRANSITIONS",
                        "PAID_INVOICE_NOT_BANK_SETTLEMENT",
                        "COURTESY_NOT_PAYMENT",
                        "CHAT_LINK_NOT_DELIVERY",
                        "WAAV_WINDOW_INDEPENDENT_OF_SELECTED_PERIOD",
                    ),
            )
        }

    private suspend fun <T> snapshot(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            val ds = dataSource ?: throw DatabaseUnavailableException()
            ds.connection.use { c ->
                c.isReadOnly = true
                c.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
                c.autoCommit = false
                try {
                    // Legacy billing columns are UTC TIMESTAMP without zone. Make their comparison with
                    // Instant parameters independent of the PostgreSQL connection's session timezone.
                    if (c.metaData.databaseProductName == "PostgreSQL") {
                        c.createStatement().use { it.execute("SET LOCAL TIME ZONE 'UTC'") }
                    }
                    block(c).also { c.commit() }
                } catch (e: Throwable) {
                    c.rollback()
                    throw e
                }
            }
        }
}
