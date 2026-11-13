package com.hookah.platform.backend.analytics

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingCommand
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingLine
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingLineInput
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingReport
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingRepository
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingSale
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingTotals
import com.hookah.platform.backend.miniapp.venue.dashboard.VenueDashboardRepository
import com.hookah.platform.backend.miniapp.venue.orders.OrderActionActor
import com.hookah.platform.backend.miniapp.venue.orders.OrderBatchStatus
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.support.BookingConversationCheckpoint
import com.hookah.platform.backend.support.SupportMessageAuthorRole
import com.hookah.platform.backend.support.SupportMessageSource
import com.hookah.platform.backend.support.SupportThreadRepository
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.http.parametersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.Statement
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class KpiReadModelsH2Test : KpiReadModelsContract(false)

internal class KpiReadModelsPostgresTest : KpiReadModelsContract(true) {
    @Test
    fun `PostgreSQL non UTC session cannot shift naive billing due dates`() =
        fixture { f ->
            f.exec(
                "INSERT INTO billing_invoices(venue_id, period_start, period_end, due_at, amount_minor, currency, " +
                    "description, provider, status, created_at) VALUES (${f.venue}, " +
                    "DATE '2026-11-01', DATE '2026-11-30', " +
                    "TIMESTAMP '2026-11-10 13:00:00', 100, 'RUB', 'x', 'fake', " +
                    "'OPEN', TIMESTAMP '2026-11-01 00:00:00')",
            )
            f.ds.connection.use { c -> c.createStatement().use { it.execute("SET TIME ZONE 'Asia/Tokyo'") } }
            val report = runBlocking { f.repo.platform(request) }
            assertEquals("0", report.metrics.getValue("invoices.overdue").value)
        }

    @Test
    fun `PostgreSQL explain range and platform counts over nontrivial data`() =
        fixture { f ->
            f.exec(
                "INSERT INTO venues(name, status, created_at) SELECT 'Scale', 'DRAFT', " +
                    "TIMESTAMPTZ '2025-01-01' FROM generate_series(1,10000)",
            )
            f.exec(
                "INSERT INTO order_batches(order_id, source, status, created_at, status_history_complete) " +
                    "SELECT ${f.order},'MINIAPP','NEW',TIMESTAMPTZ '2025-01-01',TRUE FROM generate_series(1,20000)",
            )
            f.exec(
                "INSERT INTO " +
                    "order_batch_status_facts(batch_id, transition_sequence, order_id, " +
                    "venue_id, from_status, to_status, occurred_at, producer, channel) " +
                    "SELECT id,1, order_id,${f.venue},'NEW','ACCEPTED', created_at,'TEST','UNKNOWN' FROM order_batches",
            )
            f.exec(
                "INSERT INTO orders(venue_id, table_id, table_session_id, status, " +
                    "created_at, acceptance_history_complete) " +
                    "SELECT ${f.venue}, ${f.table}, ${f.session}, 'CLOSED', " +
                    "CASE WHEN n % 100 = 0 THEN TIMESTAMPTZ '2026-11-05' ELSE TIMESTAMPTZ '2025-01-01' END, TRUE " +
                    "FROM generate_series(1,10000) n",
            )
            f.exec(
                "INSERT INTO venue_accounting_shifts(venue_id, status, timezone, " +
                    "opened_at, closed_at, final_report) " +
                    "SELECT ${f.venue}, 'CLOSED', 'UTC', TIMESTAMPTZ '2025-01-01', " +
                    "CASE WHEN n % 100 = 0 THEN TIMESTAMPTZ '2026-11-05' ELSE TIMESTAMPTZ '2025-01-02' END, '{}' " +
                    "FROM generate_series(1,5000) n",
            )
            val category = f.insert("INSERT INTO menu_categories(venue_id,name) VALUES (${f.venue},'Scale')")
            val product =
                f.insert(
                    "INSERT INTO menu_items(venue_id,category_id,name,price_minor) VALUES " +
                        "(${f.venue},$category,'Scale',100)",
                )
            f.exec(
                "INSERT INTO order_batch_items(order_batch_id,menu_item_id,qty," +
                    "item_name_snapshot,base_unit_price_minor_snapshot,currency_snapshot," +
                    "fulfilled_at) SELECT id,$product,1,'Historical scale',100,'RUB',CASE WHEN " +
                    "MOD(id,100)=0 THEN TIMESTAMPTZ '2026-11-05' ELSE TIMESTAMPTZ '2025-01-01' " +
                    "END FROM order_batches",
            )
            f.exec("ANALYZE")
            val queries =
                listOf(
                    WAAV_SQL to listOf(now.minusSeconds(168 * 3600), now),
                    PRODUCT_DELIVERY_SQL to listOf(f.venue, start, now),
                    "SELECT COUNT(*) FROM orders WHERE venue_id = ? AND " +
                        "created_at >= ? AND created_at < ?" to listOf(f.venue, start, now),
                    "SELECT status,COUNT(*) FROM venues GROUP BY status" to emptyList(),
                    "SELECT final_report FROM venue_accounting_shifts WHERE venue_id = " +
                        "? AND closed_at >= ? AND closed_at < ?" to listOf(f.venue, start, now),
                )
            val plans =
                f.ds.connection.use { c ->
                    queries.map { (sql, args) ->
                        sql + "\n" +
                            c.read(
                                "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) $sql",
                                args,
                            ) { it.getString(1) }.joinToString("\n")
                    }
                }
            // Both existing indexes bound occurrence time; optimizer may drive from a small orders relation.
            assertTrue(plans.first().contains("Index Scan using idx_batch_status_facts_"), plans.first())
            val output = java.nio.file.Path.of("build/reports/kpi-query-plans.txt")
            java.nio.file.Files.createDirectories(output.parent)
            java.nio.file.Files.writeString(output, plans.joinToString("\n\n"))
            val started = System.nanoTime()
            val report = runBlocking { f.repo.platform(request) }
            java.nio.file.Files.writeString(
                output,
                "\nFull platform snapshot: ${(System.nanoTime() - started) / 1_000_000} ms\n",
                java.nio.file.StandardOpenOption.APPEND,
            )
            assertEquals("10001", report.metrics.getValue("venues.total").value)
        }
}

internal abstract class KpiReadModelsContract(private val postgres: Boolean) {
    protected val start = Instant.parse("2026-11-01T00:00:00Z")
    protected val now = Instant.parse("2026-11-10T12:00:00Z")
    protected val clock = Clock.fixed(now, ZoneOffset.UTC)
    protected val request = KpiRequest("2026-11-01", "2026-11-11")

    @Test
    fun `UTC microsecond precision survives duration aggregation on both databases`() =
        fixture { f ->
            val call = f.call("COME")
            f.exec(
                "UPDATE staff_calls SET status = 'ACK', " +
                    "created_at = TIMESTAMP WITH TIME ZONE '2026-11-09 00:00:00.000100+00', " +
                    "acknowledged_at = TIMESTAMP WITH TIME ZONE '2026-11-09 00:00:00.000900+00' WHERE id = $call",
            )
            assertEquals("0.800", f.venueReport().metrics.getValue("calls.firstResponse").value)
            val boundary = f.call("COME")
            f.exec(
                "UPDATE staff_calls SET status = 'ACK', acknowledged_at = " +
                    "TIMESTAMP WITH TIME ZONE '2026-11-10 12:00:00+00' WHERE id = $boundary",
            )
            val nanos = KpiRepository(f.ds, Clock.fixed(now.plusNanos(499), ZoneOffset.UTC))
            val snapshot = runBlocking { nanos.venue(f.venue, 7, request) }
            assertEquals(now.toString(), snapshot.asOf)
            assertEquals("1", snapshot.metrics.getValue("calls.acknowledged").value)
        }

    @Test
    fun `empty sources distinguish zero unavailable ratio and missing subscription without writing`() =
        fixture { f ->
            val before = f.fingerprint()
            val report = f.venueReport()
            assertEquals("0", report.metrics.getValue("batches.created").value)
            assertNull(report.metrics.getValue("bookings.attendance").value)
            assertEquals(emptyList(), report.closedShiftFinances)
            val platform = runBlocking { f.repo.platform(request) }
            assertEquals("0", platform.metrics.getValue("venues.waav").value)
            assertEquals("1", platform.breakdowns.getValue("subscriptions.states").single().count.toString())
            assertNull(platform.metrics.getValue("venues.guestAvailable").value)
            assertEquals(before, f.fingerprint())
        }

    @Test
    fun `dates are half open DST aware UTC and strictly validated`() {
        val spring = KpiRequest("2026-03-29", "2026-03-30").window(ZoneId.of("Europe/Berlin"), now)
        val fall = KpiRequest("2026-10-25", "2026-10-26").window(ZoneId.of("Europe/Berlin"), now)
        assertEquals(23, Duration.between(spring.from, spring.to).toHours())
        assertEquals(25, Duration.between(fall.from, fall.to).toHours())
        assertFailsWith<InvalidInputException> { KpiRequest("2026-02-30", "2026-03-02").window(ZoneOffset.UTC, now) }
        assertFailsWith<InvalidInputException> { KpiRequest("2025-01-01", "2027-01-01").window(ZoneOffset.UTC, now) }
        assertFailsWith<InvalidInputException> { KpiRequest("2026-01-01", "2026-01-01").window(ZoneOffset.UTC, now) }
        assertFailsWith<InvalidInputException> { parametersOf("source", "MINIAPP").kpiRequest(false) }
        assertFailsWith<InvalidInputException> { parametersOf("from" to listOf("a", "b")).kpiRequest(false) }
    }

    @Test
    fun `multiple batches late accept reject repeat delivery and cohort use authoritative facts`() =
        fixture { f ->
            val old = f.batch("2026-10-01 00:00:00+00")
            val current = f.batch()
            f.batch()
            val writer =
                VenueOrdersRepository(
                    f.ds,
                    AnalyticsEventRepository(f.ds),
                    clock = Clock.fixed(now.minusSeconds(1), ZoneOffset.UTC),
                )
            val actor = OrderActionActor(7, VenueRole.MANAGER, AnalyticsChannel.TELEGRAM)
            runBlocking {
                writer.updateBatchStatus(f.venue, old, OrderBatchStatus.NEW, OrderBatchStatus.ACCEPTED, actor)
                writer.updateBatchStatus(f.venue, current, OrderBatchStatus.NEW, OrderBatchStatus.ACCEPTED, actor)
                writer.updateBatchStatus(f.venue, current, OrderBatchStatus.ACCEPTED, OrderBatchStatus.DELIVERED, actor)
                writer.updateBatchStatus(f.venue, old, OrderBatchStatus.ACCEPTED, OrderBatchStatus.REJECTED, actor)
                writer.updateBatchStatus(f.venue, old, OrderBatchStatus.REJECTED, OrderBatchStatus.ACCEPTED, actor)
                writer.updateBatchStatus(f.venue, old, OrderBatchStatus.REJECTED, OrderBatchStatus.ACCEPTED, actor)
            }
            val r = f.venueReport().metrics
            assertEquals("1", r.getValue("orders.firstAccepted").value)
            assertEquals("2", r.getValue("batches.accepted").value)
            assertEquals("1", r.getValue("batches.delivered").value)
            assertEquals("1", r.getValue("batches.rejected").value)
            assertEquals("0.500000", r.getValue("batches.cohortAcceptedRate").value)
            assertEquals("0.500000", r.getValue("batches.cohortDeliveredRate").value)
            assertEquals("1", runBlocking { f.repo.platform(request) }.metrics.getValue("venues.waav").value)
        }

    @Test
    fun `legacy closed and later fact remain partial and read does not repair`() =
        fixture { f ->
            val b = f.batch(complete = false)
            f.exec("UPDATE orders SET acceptance_history_complete = FALSE WHERE id = ${f.order}")
            f.exec("UPDATE orders SET status = 'CLOSED' WHERE id = ${f.order}")
            val before = f.fingerprint()
            val r = f.venueReport().metrics
            assertNull(r.getValue("batches.delivered").value)
            assertEquals("0", r.getValue("batches.delivered").observedValue)
            assertEquals(1, r.getValue("batches.delivered").unknown)
            assertEquals(before, f.fingerprint())
            f.exec("UPDATE orders SET status = 'ACTIVE' WHERE id = ${f.order}")
            runBlocking {
                VenueOrdersRepository(f.ds, clock = Clock.fixed(now.minusSeconds(1), ZoneOffset.UTC)).updateBatchStatus(
                    f.venue,
                    b,
                    OrderBatchStatus.NEW,
                    OrderBatchStatus.ACCEPTED,
                    OrderActionActor(7, VenueRole.MANAGER),
                )
            }
            assertNull(f.venueReport().metrics.getValue("orders.firstAccepted").value)
            assertEquals("1", f.venueReport().metrics.getValue("batches.accepted").observedValue)
        }

    @Test
    fun `boundaries future and rolling WAAV are independent of selected dates`() =
        fixture { f ->
            for (time in listOf(start, now.minusSeconds(168 * 3600), now)) {
                val b = f.batch()
                f.exec(
                    "INSERT INTO order_batch_status_facts VALUES ($b,1,${f.order},${f.venue},'NEW','DELIVERED'," +
                        "TIMESTAMP WITH TIME ZONE '$time','TEST','UNKNOWN')",
                )
            }
            assertEquals("2", f.venueReport().metrics.getValue("batches.delivered").value)
            val p = runBlocking { f.repo.platform(KpiRequest("2020-01-01", "2020-01-02")) }
            assertEquals("1", p.metrics.getValue("venues.waav").value)
            assertEquals("UTC", p.period.timezone)
            assertEquals(now.toString(), p.asOf)
        }

    @Test
    fun `calls distinguish ACK operator automatic completion and legacy response times`() =
        fixture { f ->
            val manual = f.call("COME")
            val bill = f.call("BILL")
            val other = f.call("COME")
            val legacy = f.call("COME")
            f.exec("UPDATE staff_calls SET status_history_complete = FALSE, status = 'DONE' WHERE id = $legacy")
            val repo = StaffCallRepository(f.ds, clock = Clock.fixed(now.minusSeconds(2), ZoneOffset.UTC))
            runBlocking {
                repo.ackStaffCall(f.venue, manual, 7)
                repo.doneStaffCall(f.venue, manual, 7)
                repo.resolveActiveCallsForClosedOrder(f.venue, f.order)
            }
            val r = f.venueReport().metrics
            assertEquals("1", r.getValue("calls.operatorCompleted").observedValue)
            assertEquals("1", r.getValue("calls.autoCompleted").observedValue)
            assertEquals("1", r.getValue("calls.cancelled").observedValue)
            assertEquals(1, r.getValue("calls.firstResponse").samples)
            assertEquals(1, r.getValue("calls.firstResponse").unknown)
            assertNotNull(bill)
            assertNotNull(other)
        }

    @Test
    fun `booking scheduled cohort statuses and attendance do not count changed as seated`() =
        fixture { f ->
            for ((index, status) in listOf(
                "SEATED",
                "NO_SHOW",
                "CHANGED",
                "CANCELED",
                "EXPIRED",
                "CONFIRMED",
            ).withIndex()) {
                f.exec(
                    "INSERT INTO " +
                        "bookings(venue_id, user_id, scheduled_at, status, created_at, " +
                        "display_date, display_number) VALUES " +
                        "(${f.venue},7,TIMESTAMP WITH TIME ZONE '2026-11-09 " +
                        "12:00:00+00','$status',TIMESTAMP WITH TIME ZONE '2026-10-01 " +
                        "00:00:00+00',DATE '2026-11-09',${index + 1})",
                )
            }
            val r = f.venueReport().metrics
            assertEquals("0", r.getValue("bookings.created").value)
            assertEquals("6", r.getValue("bookings.scheduled").value)
            assertEquals("0.500000", r.getValue("bookings.attendance").value)
            assertEquals("2", r.getValue("bookings.unresolved").value)
            f.exec(
                "UPDATE bookings SET scheduled_at = TIMESTAMP WITH TIME ZONE " +
                    "'2026-12-01 00:00:00+00' WHERE status = 'CHANGED'",
            )
            assertEquals("5", f.venueReport().metrics.getValue("bookings.scheduled").value)
        }

    @Test
    fun `frozen financial reports currencies manual sale count and open shift are separate`() =
        fixture { f ->
            for (currency in listOf("RUB", "USD")) {
                val report =
                    AccountingReport(
                        1, f.venue, "CLOSED", "UTC", start.toString(), now.minusSeconds(1).toString(), currency,
                        emptyList(),
                        AccountingTotals(
                            2,
                            1,
                            600,
                            20,
                            30,
                            550,
                            400,
                            150,
                            200,
                            100,
                            100,
                        ),
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        emptyList(),
                    )
                val sql =
                    "INSERT INTO " +
                        "venue_accounting_shifts(venue_id, status, timezone, opened_at, closed_at, final_report) " +
                        "VALUES (?,'CLOSED','UTC',?,?,?)"
                f.ds.connection.use { c ->
                    c.prepareStatement(sql).use { s ->
                        s.setLong(1, f.venue)
                        s.setObject(2, start.atOffset(ZoneOffset.UTC))
                        s.setObject(3, now.minusSeconds(1).atOffset(ZoneOffset.UTC))
                        s.setString(4, Json.encodeToString(report))
                        s.executeUpdate()
                    }
                }
            }
            f.exec(
                "INSERT INTO venue_accounting_shifts(venue_id, status, open_slot, timezone, opened_at) VALUES " +
                    "(${f.venue},'OPEN',1,'UTC',TIMESTAMP WITH TIME ZONE '2026-11-10 00:00:00+00')",
            )
            f.exec(
                "UPDATE orders SET accounting_shift_id = (SELECT id FROM venue_accounting_shifts " +
                    "WHERE status = 'OPEN' AND venue_id = ${f.venue}) WHERE id = ${f.order}",
            )
            val before = f.fingerprint()
            val r = f.venueReport()
            assertEquals(listOf("RUB", "USD"), r.closedShiftFinances.map { it.currency })
            for (group in r.closedShiftFinances) {
                assertEquals("550", group.metrics.getValue("accrued").value)
                assertEquals("275", group.metrics.getValue("averageAccruedSale").value)
                assertEquals("400", group.metrics.getValue("operatorConfirmed").value)
            }
            assertEquals("0", r.openShiftFinances.single().metrics.getValue("accrued").value)
            assertEquals(before, f.fingerprint())
        }

    @Test
    fun `platform invoices avoid fanout and warn about duplicate and mismatched events`() =
        fixture { f ->
            val invoice =
                f.insert(
                    "INSERT INTO " +
                        "billing_invoices(venue_id, period_start, period_end, due_at, amount_minor, " +
                        "currency, description, provider, status, paid_at) " +
                        "VALUES (${f.venue},DATE '2026-11-01',DATE '2026-11-30',TIMESTAMP " +
                        "'2026-11-01 00:00:00',2000000000,'USD','x','fake','PAID',TIMESTAMP '2026-11-09 00:00:00')",
                )
            for (month in listOf("01", "02")) {
                f.exec(
                    "INSERT INTO billing_invoices(venue_id, period_start, period_end, due_at, amount_minor, " +
                        "currency, description, provider, status) VALUES (${f.venue}, DATE '2025-$month-01', " +
                        "DATE '2025-$month-28', TIMESTAMP '2025-$month-01 00:00:00', " +
                        "2000000000, 'USD', 'x', 'fake', 'OPEN')",
                )
            }
            f.exec("UPDATE billing_invoices SET created_at = TIMESTAMP '2026-11-01 00:00:00'")
            for (event in listOf("manual:$invoice", "duplicate")) f.exec(
                "INSERT INTO " +
                    "billing_payments(invoice_id, provider, provider_event_id, " +
                    "amount_minor, currency, status, occurred_at) " +
                    "VALUES ($invoice,'fake','$event',2000000000,'USD','SUCCEEDED',TIMESTAMP '2026-11-09 00:00:00')",
            )
            f.exec(
                "INSERT INTO " +
                    "billing_payments(invoice_id, provider, provider_event_id, " +
                    "amount_minor, currency, status, occurred_at) " +
                    "VALUES ($invoice,'fake','bad',3,'RUB','SUCCEEDED',TIMESTAMP '2026-11-09 00:00:00')",
            )
            f.exec(
                "INSERT INTO " +
                    "billing_adjustments(venue_id, kind, days, reason, previous_paid_through, " +
                    "new_paid_through, actor_user_id, created_at) " +
                    "VALUES (${f.venue},'COURTESY_DAYS',5,'x',DATE '2026-11-30',DATE " +
                    "'2026-12-05',7,TIMESTAMP '2026-11-09 00:00:00')",
            )
            val r = runBlocking { f.repo.platform(request) }
            assertEquals("4000000000", r.invoiceFinances.getValue("open").single().metrics.getValue("amount").value)
            assertEquals("1", r.metrics.getValue("invoices.manualConfirmed").value)
            assertEquals("1", r.metrics.getValue("invoices.multipleMatchingSuccesses").value)
            assertEquals("1", r.metrics.getValue("invoices.inconsistentSuccessfulEvents").value)
            assertEquals("2000000000", r.invoiceFinances.getValue("paid").single().metrics.getValue("amount").value)
            assertEquals("2026-12-05", r.breakdowns.getValue("subscriptions.effectivePaidThrough").single().key)
            assertEquals("2026-11-30", r.breakdowns.getValue("subscriptions.paidThrough").single().key)
            f.exec(
                "UPDATE billing_invoices SET currency = 'ZZZ' WHERE status = " +
                    "'OPEN' AND period_start = DATE '2025-01-01'",
            )
            val invalid = runBlocking { f.repo.platform(request) }
            assertNull(invalid.metrics.getValue("invoices.open.currencyCoverage").value)
            assertEquals("INVALID_CURRENCY", invalid.metrics.getValue("invoices.open.currencyCoverage").reason)
            assertEquals(
                "2000000000",
                invalid.invoiceFinances.getValue("open").single().metrics.getValue("amount").value,
            )
        }

    @Test
    fun `support only tickets and human messages establish TTFR`() =
        fixture { f ->
            for (type in listOf("SUPPORT_TICKET", "BOOKING_THREAD", "VENUE_CHAT")) {
                val id =
                    f.insert(
                        "INSERT INTO " +
                            "support_threads(venue_id, guest_user_id, category, status, " +
                            "title, thread_type, created_at) " +
                            "VALUES (${f.venue},7,'OTHER','NEW','private','$type',TIMESTAMP " +
                            "WITH TIME ZONE '2026-11-09 00:00:00+00')",
                    )
                for ((role, source, minute) in listOf(
                    Triple("GUEST", "GUEST_MINIAPP", 1),
                    Triple("SYSTEM", "SYSTEM", 2),
                    Triple("PLATFORM", "PLATFORM_MINIAPP", 3),
                )) {
                    f.exec(
                        "INSERT INTO support_messages(thread_id, author_user_id, " +
                            "author_role, source, text, created_at) " +
                            "VALUES ($id,7,'$role','$source','secret',TIMESTAMP WITH TIME " +
                            "ZONE '2026-11-09 00:0$minute:00+00')",
                    )
                }
            }
            val r = runBlocking { f.repo.platform(request) }
            assertEquals("1", r.metrics.getValue("support.active").value)
            assertEquals("180000.000", r.metrics.getValue("support.ttfr").value)
            assertTrue(!Json.encodeToString(r).contains("secret"))
        }

    @Test
    fun `membership revoked staff foreign table and foreign venue denied`() =
        fixture { f ->
            val foreign = f.insert("INSERT INTO venues(name, status) VALUES ('Foreign','PUBLISHED')")
            runBlocking { assertFailsWith<ForbiddenException> { f.repo.venue(foreign, 7, request) } }
            val foreignTable = f.insert("INSERT INTO venue_tables(venue_id, table_number) VALUES ($foreign, 1)")
            runBlocking {
                assertFailsWith<InvalidInputException> {
                    f.repo.venue(f.venue, 7, request.copy(section = "orders", tableId = foreignTable))
                }
            }
            f.exec("UPDATE venue_members SET role = 'STAFF' WHERE venue_id = ${f.venue}")
            assertFailsWith<ForbiddenException> { f.venueReport() }
            f.exec("DELETE FROM venue_members WHERE venue_id = ${f.venue}")
            assertFailsWith<ForbiddenException> { f.venueReport() }
        }

    @Test
    fun `registered WALK IN seating survives MOVE without counting people or duplicate sessions`() =
        fixture { f ->
            val visit =
                f.insert(
                    "INSERT INTO visits(venue_id, table_session_id, source, occurred_at) " +
                        "VALUES (${f.venue}, ${f.session}, 'WALK_IN', TIMESTAMP WITH " +
                        "TIME ZONE '2026-11-02 00:00:00+00')",
                )
            f.exec(
                "INSERT INTO table_seatings(venue_id, table_id, visit_id, " +
                    "table_session_id, party_size, started_at, created_by_user_id) " +
                    "VALUES (${f.venue}, ${f.table}, $visit, ${f.session}, 4, TIMESTAMP " +
                    "WITH TIME ZONE '2026-11-02 00:00:00+00', 7)",
            )
            assertEquals("1", f.venueReport().metrics.getValue("seatings.registeredSessions").value)
            val moved = f.insert("INSERT INTO venue_tables(venue_id, table_number) VALUES (${f.venue}, 2)")
            f.exec("UPDATE table_seatings SET table_id = $moved WHERE venue_id = ${f.venue}")
            assertEquals("1", f.venueReport().metrics.getValue("seatings.registeredSessions").value)
            val filtered = runBlocking { f.repo.venue(f.venue, 7, request.copy(section = "seatings", tableId = moved)) }
            assertEquals("1", filtered.metrics.getValue("seatings.registeredSessions").value)
        }

    @Test
    fun `delivered top retains snapshot name and exclusions with no fanout from repeated delivery`() =
        fixture { f ->
            val category = f.insert("INSERT INTO menu_categories(venue_id, name) VALUES (${f.venue}, 'Menu')")
            val item =
                f.insert(
                    "INSERT INTO menu_items(venue_id, category_id, name, price_minor) " +
                        "VALUES (${f.venue}, $category, 'Renamed', 99999)",
                )
            val b = f.batch()
            f.exec(
                "UPDATE order_batches SET first_delivered_at = TIMESTAMP WITH TIME ZONE '2026-11-09 00:00:00+00', " +
                    "status = 'DELIVERED' WHERE id = $b",
            )
            val missing = f.venueReport().metrics.getValue("items.deliveredSnapshotCoverage")
            assertNull(missing.value)
            assertEquals(1, missing.unknown)
            f.exec(
                "INSERT INTO order_batch_items(order_batch_id, menu_item_id, qty, item_name_snapshot, " +
                    "base_unit_price_minor_snapshot, currency_snapshot) VALUES ($b, $item, 3, 'Original', 100, 'RUB')",
            )
            assertEquals(listOf(KpiBucket("Original", 3)), f.venueReport().breakdowns.getValue("items.deliveredTop"))
            f.exec("UPDATE order_batch_items SET is_excluded = TRUE WHERE order_batch_id = $b")
            assertEquals(emptyList(), f.venueReport().breakdowns.getValue("items.deliveredTop"))
            assertEquals("1", f.venueReport().metrics.getValue("items.excludedSnapshot").value)
        }

    @Test
    fun `known missing snapshots and corrupted frozen report do not become zero money`() =
        fixture { f ->
            f.exec(
                "INSERT INTO venue_accounting_shifts(venue_id, status, timezone, opened_at, closed_at, final_report) " +
                    "VALUES (${f.venue}, 'CLOSED', 'UTC', TIMESTAMP WITH TIME ZONE '2026-11-01 00:00:00+00', " +
                    "TIMESTAMP WITH TIME ZONE '2026-11-02 00:00:00+00', '{}')",
            )
            val report = f.venueReport()
            assertNull(report.metrics.getValue("finance.closedReports").value)
            assertEquals("REPORT_MISSING_OR_INCONSISTENT", report.metrics.getValue("finance.closedReports").reason)
            assertEquals(emptyList(), report.closedShiftFinances)
        }

    @Test
    fun `published venue availability follows subscription resolver including past due and unknown`() =
        fixture { f ->
            f.exec("INSERT INTO venue_subscriptions(venue_id, status) VALUES (${f.venue}, 'PAST_DUE')")
            assertEquals("1", runBlocking { f.repo.platform(request) }.metrics.getValue("venues.guestAvailable").value)
            f.exec("UPDATE venue_subscriptions SET status = 'SUSPENDED' WHERE venue_id = ${f.venue}")
            assertEquals("0", runBlocking { f.repo.platform(request) }.metrics.getValue("venues.guestAvailable").value)
            f.exec("UPDATE venues SET status = 'HIDDEN' WHERE id = ${f.venue}")
            f.exec("UPDATE venue_subscriptions SET status = 'ACTIVE' WHERE venue_id = ${f.venue}")
            assertEquals("0", runBlocking { f.repo.platform(request) }.metrics.getValue("venues.guestAvailable").value)
        }

    @Test
    fun `tab scoped completed additions and complete product quantity do not merge personal bills`() =
        fixture { f ->
            val cat = f.insert("INSERT INTO menu_categories(venue_id,name) VALUES (${f.venue},'Drinks')")
            val tea =
                f.insert(
                    "INSERT INTO menu_items(venue_id,category_id,name,price_minor) VALUES " +
                        "(${f.venue},$cat,'Changed',99999)",
                )
            val hookah =
                f.insert(
                    "INSERT INTO menu_items(venue_id,category_id,name,price_minor) VALUES " +
                        "(${f.venue},$cat,'Hookah',99999)",
                )
            val dessert =
                f.insert(
                    "INSERT INTO menu_items(venue_id,category_id,name,price_minor) VALUES " +
                        "(${f.venue},$cat,'Dessert',99999)",
                )
            val tab1 =
                f.insert(
                    "INSERT INTO tab(venue_id,table_session_id,type) VALUES (${f.venue},${f.session},'SHARED')",
                )
            val tab2 =
                f.insert(
                    "INSERT INTO tab(venue_id,table_session_id,type,owner_user_id) VALUES " +
                        "(${f.venue},${f.session},'PERSONAL',7)",
                )
            val rejected = f.batch()
            f.exec(
                "UPDATE order_batches SET tab_id=$tab1,status='REJECTED'," +
                    "first_delivered_at=TIMESTAMP WITH TIME ZONE '2026-11-09 00:00:00+00' WHERE id=$rejected",
            )
            f.exec(
                "INSERT INTO order_batch_items(order_batch_id,menu_item_id,qty,item_name_snapshot," +
                    "base_unit_price_minor_snapshot,currency_snapshot) " +
                    "VALUES ($rejected,$tea,100,'Rejected tea',100,'RUB')",
            )
            for ((index, pair) in listOf(tab1 to 2, tab1 to 1, tab1 to 1, tab2 to 1).withIndex()) {
                val (tab, qty) = pair
                val product =
                    if (index == 2) {
                        dessert
                    } else if (index == 3) {
                        hookah
                    } else {
                        tea
                    }
                val b = f.batch()
                f.exec(
                    "UPDATE order_batches SET tab_id=$tab,status='DELIVERED'," +
                        "first_delivered_at=TIMESTAMP WITH TIME ZONE '2026-11-09 00:00:00+00' WHERE " +
                        "id=$b",
                )
                if (index == 0) {
                    f.exec(
                        "INSERT INTO order_batch_items(order_batch_id,menu_item_id,qty," +
                            "item_name_snapshot,base_unit_price_minor_snapshot,currency_snapshot) " +
                            "VALUES ($b,$hookah,1,'Hookah',100,'RUB')",
                    )
                }
                f.exec(
                    "INSERT INTO order_batch_items(order_batch_id,menu_item_id,qty," +
                        "item_name_snapshot,base_unit_price_minor_snapshot,currency_snapshot) " +
                        "VALUES ($b,$product,$qty,'Original',100,'RUB')",
                )
            }
            f.exec(
                "UPDATE orders SET status='CLOSED',closed_at=TIMESTAMP WITH TIME ZONE " +
                    "'2026-11-09 01:00:00+00' WHERE id=${f.order}",
            )
            f.exec("UPDATE tab SET status='CLOSED' WHERE id IN ($tab1,$tab2)")
            val before = f.fingerprint()
            val report = runBlocking { f.repo.venue(f.venue, 7, request.copy(section = "additions")) }
            assertEquals("2", report.metrics.getValue("additions.completedTabs").value)
            assertEquals("1", report.metrics.getValue("additions.none").value)
            assertEquals("1", report.metrics.getValue("additions.twoOrMore").value)
            assertEquals("0.500000", report.metrics.getValue("additions.share").value)
            assertEquals("2", report.metrics.getValue("additions.count").value)
            val products = runBlocking { f.repo.venue(f.venue, 7, request.copy(section = "products")) }.products!!
            assertEquals("3", products.rows.single { it.itemId == tea }.units)
            assertEquals("300", products.rows.single { it.itemId == tea }.snapshotGrossMinor)
            assertEquals("Original", products.rows.single { it.itemId == tea }.name)
            assertEquals(before, f.fingerprint())
        }

    @Test
    fun `complete product source includes unsold identities and pagination independent search`() =
        fixture { f ->
            val cat = f.insert("INSERT INTO menu_categories(venue_id,name) VALUES (${f.venue},'Same')")
            repeat(61) {
                f.insert(
                    "INSERT INTO menu_items(venue_id,category_id,name,price_minor) VALUES " +
                        "(${f.venue},$cat,'Identical',100)",
                )
            }
            val report =
                runBlocking {
                    f.repo.venue(f.venue, 7, request.copy(section = "products", page = 2, search = "Ident"))
                }.products!!
            assertEquals(61, report.totalRows)
            assertEquals(11, report.rows.size)
            assertEquals(11, report.rows.map { it.itemId }.distinct().size)
            assertTrue(report.rows.all { it.units == "0" })
            assertEquals("AVAILABLE", report.coverage.availability)
        }

    @Test
    fun `legacy missing time and missing tab remain unknown instead of zero repeat share`() =
        fixture { f ->
            f.batch(complete = false)
            f.exec(
                "UPDATE orders SET status='CLOSED',closed_at=TIMESTAMP WITH TIME ZONE " +
                    "'2026-11-09 00:00:00+00' WHERE id=${f.order}",
            )
            val additions = runBlocking { f.repo.venue(f.venue, 7, request.copy(section = "additions")) }
            assertNull(additions.metrics.getValue("additions.share").value)
            assertTrue(additions.metrics.getValue("additions.share").unknown > 0)
            val products = runBlocking { f.repo.venue(f.venue, 7, request.copy(section = "products")) }.products!!
            assertEquals("PARTIAL", products.coverage.availability)
        }

    @Test
    fun `product filters are strict scoped and money sort needs currency`() {
        assertFailsWith<InvalidInputException> {
            parametersOf(
                "from" to listOf("2026-11-01"),
                "to" to listOf("2026-11-02"),
                "section" to listOf("calls"),
                "search" to listOf("x"),
            ).kpiRequest(false)
        }
        assertFailsWith<InvalidInputException> {
            parametersOf(
                "from" to listOf("2026-11-01"),
                "to" to listOf("2026-11-02"),
                "section" to listOf("products"),
                "sort" to listOf("amount"),
            ).kpiRequest(false)
        }
    }

    @Test
    fun `gift quantity and historical manual lines remain separate from digital accounting projection`() =
        fixture { f ->
            val cat = f.insert("INSERT INTO menu_categories(venue_id,name) VALUES (${f.venue},'Menu')")
            val item =
                f.insert(
                    "INSERT INTO menu_items(venue_id,category_id,name,price_minor) VALUES " +
                        "(${f.venue},$cat,'Same',99999)",
                )
            val b = f.batch()
            f.exec(
                "UPDATE order_batches SET status='DELIVERED',first_delivered_at=TIMESTAMP " +
                    "WITH TIME ZONE '2026-11-09 00:00:00+00' WHERE id=$b",
            )
            val line =
                f.insert(
                    "INSERT INTO order_batch_items(order_batch_id,menu_item_id,qty," +
                        "item_name_snapshot,base_unit_price_minor_snapshot,currency_snapshot) " +
                        "VALUES ($b,$item,3,'Historic',100,'RUB')",
                )
            val rule =
                f.insert(
                    "INSERT INTO promotion_rules(venue_id,rule_type,target_type,target_value," +
                        "discount_percent,status,created_by_user_id) VALUES (${f.venue}," +
                        "'HAPPY_HOURS_PERCENT','CATEGORY_TYPE','TEA',10,'ACTIVE',7)",
                )
            val application =
                f.insert(
                    "INSERT INTO order_promotion_applications(order_id,batch_id,venue_id," +
                        "user_id,rule_id,title_snapshot,rule_type,target_type,target_value," +
                        "discount_percent,discount_total_minor,dedupe_key) VALUES (${f.order},$b," +
                        "${f.venue},7,$rule,'Gift','HAPPY_HOURS_PERCENT','CATEGORY_TYPE','TEA',10," +
                        "100,'gift-test')",
                )
            f.exec(
                "INSERT INTO order_promotion_reward_items(application_id," +
                    "reward_order_batch_item_id,reward_menu_item_id,reward_qty,label_snapshot) " +
                    "VALUES ($application,$line,$item,1,'Gift')",
            )
            val shift =
                f.insert(
                    "INSERT INTO venue_accounting_shifts(venue_id,status,open_slot,timezone," +
                        "opened_at) VALUES (${f.venue},'OPEN',1,'UTC',TIMESTAMP WITH TIME ZONE " +
                        "'2026-11-01 00:00:00+00')",
                )
            val manual =
                """
                {"id":"manual","tableId":${f.table},"tableLabel":"Table","source":"MANUAL","createdAt":"2026-11-09T00:00:00Z","currency":"RUB","lines":[{"id":"m","name":"Manual historic","qty":2,"unitPriceMinor":250,"menuItemId":$item}]}
                """.trimIndent()
            f.ds.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO venue_accounting_sales(id,shift_id,snapshot_json) VALUES (?,?,?)",
                ).use {
                    it.setString(1, "manual")
                    it.setLong(2, shift)
                    it.setString(3, manual)
                    it.executeUpdate()
                    it.setString(1, "projection")
                    it.setString(
                        3,
                        manual.replace("\"source\":\"MANUAL\"", "\"source\":\"MINIAPP\",\"orderId\":${f.order}"),
                    )
                    it.executeUpdate()
                    for ((id, snapshot) in listOf(
                        "unlinked-projection" to manual.replace("\"source\":\"MANUAL\"", "\"source\":\"MINIAPP\""),
                        "invalid-quantity" to manual.replace("\"qty\":2", "\"qty\":-1"),
                        "invalid-price" to manual.replace("\"unitPriceMinor\":250", "\"unitPriceMinor\":-250"),
                    )) {
                        it.setString(1, id)
                        it.setString(3, snapshot)
                        it.executeUpdate()
                    }
                }
            }
            val result = runBlocking { f.repo.venue(f.venue, 7, request.copy(section = "products")) }.products!!
            assertEquals("3", result.rows.single { it.source == "DIGITAL_DELIVERY" }.units)
            assertEquals("1", result.rows.single { it.source == "DIGITAL_DELIVERY" }.giftUnits)
            assertEquals("2", result.rows.single { it.source == "DIGITAL_DELIVERY" }.nonGiftUnits)
            assertEquals("2", result.rows.single { it.source == "MANUAL_SALE" }.units)
            assertEquals("500", result.rows.single { it.source == "MANUAL_SALE" }.snapshotGrossMinor)
            assertNull(result.netAmount.value)
            f.exec("DELETE FROM order_promotion_reward_items WHERE reward_order_batch_item_id=$line")
            val program =
                f.insert(
                    "INSERT INTO loyalty_programs(venue_id,program_type,status,nth_value,created_by_user_id) " +
                        "VALUES (${f.venue},'NTH_HOOKAH_FREE','ACTIVE',3,7)",
                )
            f.insert(
                "INSERT INTO loyalty_redemptions(program_id,venue_id,user_id,order_id,batch_id," +
                    "order_batch_item_id,order_promotion_application_id,status,discount_minor,dedupe_key) " +
                    "VALUES ($program,${f.venue},7,${f.order},$b,$line,$application,'APPLIED',100,'loyalty-gift')",
            )
            val loyalty = runBlocking { f.repo.venue(f.venue, 7, request.copy(section = "products")) }.products!!
            assertEquals("3", loyalty.rows.single { it.source == "DIGITAL_DELIVERY" }.units)
            assertEquals("1", loyalty.rows.single { it.source == "DIGITAL_DELIVERY" }.giftUnits)
            assertEquals("2", loyalty.rows.single { it.source == "DIGITAL_DELIVERY" }.nonGiftUnits)
            f.exec("UPDATE order_batch_items SET is_excluded=TRUE WHERE id=$line")
            assertEquals(
                "0",
                runBlocking {
                    f.repo.venue(f.venue, 7, request.copy(section = "products"))
                }.products!!.rows.filter { it.source == "DIGITAL_DELIVERY" }.sumOf { it.units.toLong() }.toString(),
            )

            fun products() = runBlocking { f.repo.venue(f.venue, 7, request.copy(section = "products")) }.products!!
            f.exec("UPDATE order_batch_items SET is_excluded=FALSE WHERE id=$line")
            for (cancel in listOf(
                "UPDATE order_batch_items SET item_status='CANCELED' WHERE id=$line",
                "UPDATE order_batches SET status='REJECTED' WHERE id=$b",
                "UPDATE orders SET status='CANCELLED' WHERE id=${f.order}",
            )) {
                f.exec(cancel)
                assertEquals(
                    "0",
                    products().rows.filter { it.source == "DIGITAL_DELIVERY" }.sumOf { it.units.toLong() }.toString(),
                )
                f.exec("UPDATE order_batch_items SET item_status='ACTIVE' WHERE id=$line")
                f.exec("UPDATE order_batches SET status='DELIVERED' WHERE id=$b")
                f.exec("UPDATE orders SET status='ACTIVE' WHERE id=${f.order}")
            }
            f.insert(
                "INSERT INTO order_batch_item_options(order_batch_item_id,option_name_snapshot," +
                    "price_delta_minor_snapshot) VALUES ($line,'Historic option',75)",
            )
            val option = products().rows.single { it.source == "DIGITAL_DELIVERY" }
            assertEquals("3", option.units)
            assertEquals("525", option.snapshotGrossMinor)
            assertEquals(listOf("Historic option"), option.options)
            f.exec("UPDATE order_batch_items SET base_unit_price_minor_snapshot=9223372036854775807 WHERE id=$line")
            assertEquals(
                "27670116110564327646",
                products().rows.single { it.source == "DIGITAL_DELIVERY" }.snapshotGrossMinor,
            )
            f.exec(
                "UPDATE order_batch_items SET base_unit_price_minor_snapshot=NULL," +
                    "item_name_snapshot=NULL WHERE id=$line",
            )
            val missing = products()
            assertEquals("PARTIAL", missing.coverage.availability)
            assertNull(missing.rows.single { it.source == "DIGITAL_DELIVERY" }.snapshotGrossMinor)
            val text = f.batch()
            f.exec(
                "UPDATE order_batches SET status='DELIVERED',guest_comment='Legacy free text without item identity'," +
                    "first_delivered_at=TIMESTAMP WITH TIME ZONE '2026-11-09 00:00:00+00' WHERE id=$text",
            )
            assertEquals(missing.coverage.unknown + 1, products().coverage.unknown)
            f.ds.connection.use { c ->
                c.prepareStatement("UPDATE venue_accounting_sales SET snapshot_json=? WHERE id='manual'").use {
                    it.setString(
                        1,
                        manual.replace("\"menuItemId\":$item", "\"menuItemId\":null")
                            .replace("Manual historic", "Same"),
                    )
                    it.executeUpdate()
                }
            }
            val unassigned = products()
            assertEquals("2", unassigned.unassignedManualUnits)
            assertTrue(unassigned.rows.none { it.source == "MANUAL_SALE" })
            assertEquals("PARTIAL", unassigned.coverage.availability)
            assertTrue(unassigned.coverage.unknown >= 2)
        }

    @Test
    fun `accounting EDIT gifts preserve exact delivered identity without duplicating quantities`() =
        fixture { f ->
            val category = f.insert("INSERT INTO menu_categories(venue_id,name) VALUES (${f.venue},'Gift')")
            val item =
                f.insert(
                    "INSERT INTO menu_items(venue_id,category_id,name,price_minor) VALUES " +
                        "(${f.venue},$category,'Renamed',99999)",
                )
            val batch = f.batch()
            f.exec(
                "UPDATE order_batches SET status='DELIVERED',first_delivered_at=TIMESTAMP WITH TIME ZONE " +
                    "'2026-11-09 00:00:00+00' WHERE id=$batch",
            )
            val line =
                f.insert(
                    "INSERT INTO " +
                        "order_batch_items(order_batch_id,menu_item_id,qty,item_name_snapshot," +
                        "base_unit_price_minor_snapshot,currency_snapshot) VALUES ($batch,$item,3,'Historical'," +
                        "100,'RUB')",
                )
            val shift =
                f.insert(
                    "INSERT INTO venue_accounting_shifts(venue_id,status,open_slot,timezone,opened_at) " +
                        "VALUES (${f.venue},'OPEN',1,'UTC',TIMESTAMP WITH TIME ZONE '2026-11-01 00:00:00+00')",
                )
            f.exec(
                "UPDATE orders SET status='CLOSED',accounting_shift_id=$shift,closed_at=TIMESTAMP WITH " +
                    "TIME ZONE '2026-11-09 01:00:00+00' WHERE id=${f.order}",
            )
            val accounting =
                AccountingRepository(
                    f.ds,
                    AuditLogRepository(f.ds),
                    Clock.fixed(now.minusSeconds(1), ZoneOffset.UTC),
                )

            fun report() = runBlocking { f.repo.venue(f.venue, 7, request.copy(section = "products")) }.products!!
            val initial = runBlocking { accounting.detail(f.venue, 7, shift) }!!.sales.single()
            assertEquals("0", report().rows.single().giftUnits)
            val command =
                AccountingCommand(
                    "gift-edit-request-001",
                    "EDIT",
                    shift,
                    initial.id,
                    initial.version,
                    lines =
                        initial.lines.map {
                            AccountingLineInput(
                                id = it.id,
                                qty = it.qty,
                                giftReason = "Venue gift",
                            )
                        },
                    reason = "Venue gift",
                )
            val changed = runBlocking { accounting.execute(f.venue, 7, command) }
            assertEquals(300L, changed.totals.giftMinor)
            assertEquals(0L, changed.totals.accruedMinor)
            assertNull(changed.sales.single().issue)
            repeat(2) {
                runBlocking { accounting.execute(f.venue, 7, command) }
                val before = f.fingerprint()
                val product = report()
                assertEquals("3", product.rows.single().units)
                assertEquals("3", product.rows.single().giftUnits)
                assertEquals("0", product.rows.single().nonGiftUnits)
                assertEquals("300", product.rows.single().snapshotGrossMinor)
                assertEquals("AVAILABLE", product.coverage.availability)
                assertEquals(before, f.fingerprint())
            }
            val manual =
                AccountingCommand(
                    "manual-gift-request-001",
                    "CREATE",
                    shiftId = shift,
                    tableId = f.table,
                    lines = listOf(AccountingLineInput(menuItemId = item, qty = 2, giftReason = "Manual gift")),
                )
            runBlocking { accounting.execute(f.venue, 7, manual) }
            assertEquals("3", report().rows.single { it.source == "DIGITAL_DELIVERY" }.units)
            assertEquals("2", report().rows.single { it.source == "MANUAL_SALE" }.giftUnits)
        }

    @Test
    fun `ambiguous accounting gift amounts remain unknown and canonical gifts never double count`() =
        fixture { f ->
            val cat = f.insert("INSERT INTO menu_categories(venue_id,name) VALUES (${f.venue},'Gift')")
            val item =
                f.insert(
                    "INSERT INTO menu_items(venue_id,category_id,name,price_minor) VALUES " +
                        "(${f.venue},$cat,'Tea',99999)",
                )
            val batch = f.batch()
            f.exec(
                "UPDATE order_batches SET status='DELIVERED',first_delivered_at=TIMESTAMP WITH TIME ZONE " +
                    "'2026-11-09 00:00:00+00' WHERE id=$batch",
            )
            val id =
                f.insert(
                    "INSERT INTO " +
                        "order_batch_items(order_batch_id,menu_item_id,qty,item_name_snapshot," +
                        "base_unit_price_minor_snapshot,currency_snapshot) VALUES ($batch,$item,3,'Tea',100," +
                        "'RUB')",
                )
            val shift =
                f.insert(
                    "INSERT INTO venue_accounting_shifts(venue_id,status,open_slot,timezone,opened_at) " +
                        "VALUES (${f.venue},'OPEN',1,'UTC',CURRENT_TIMESTAMP)",
                )
            val base =
                AccountingSale(
                    "order:${f.order}",
                    f.table,
                    "Table",
                    "MINIAPP",
                    orderId = f.order,
                    createdAt = "2026-11-09T00:00:00Z",
                    lines = listOf(AccountingLine("item:$id", "Tea", 3, 100, item, baseGiftMinor = 100)),
                )

            fun save(sale: AccountingSale) {
                f.exec("DELETE FROM venue_accounting_sales")
                f.ds.connection.use { c ->
                    c.prepareStatement(
                        "INSERT INTO venue_accounting_sales(id,shift_id,snapshot_json) VALUES (?,?,?)",
                    ).use {
                        it.setString(1, sale.id)
                        it.setLong(2, shift)
                        it.setString(3, Json.encodeToString(sale))
                        it.executeUpdate()
                    }
                }
            }

            fun products() = runBlocking { f.repo.venue(f.venue, 7, request.copy(section = "products")) }.products!!
            save(base)
            assertNull(products().rows.single().giftUnits)
            assertNull(products().rows.single().nonGiftUnits)
            assertEquals("PARTIAL", products().coverage.availability)
            val rule =
                f.insert(
                    "INSERT INTO " +
                        "promotion_rules(venue_id,rule_type,target_type,target_value,discount_percent,status," +
                        "created_by_user_id) VALUES (${f.venue},'HAPPY_HOURS_PERCENT','CATEGORY_TYPE','TEA',10," +
                        "'ACTIVE',7)",
                )
            val application =
                f.insert(
                    "INSERT INTO " +
                        "order_promotion_applications(order_id,batch_id,venue_id,user_id,rule_id,title_snapshot," +
                        "rule_type,target_type,target_value,discount_percent,discount_total_minor,dedupe_key) " +
                        "VALUES (${f.order},$batch,${f.venue},7,$rule,'Gift','HAPPY_HOURS_PERCENT'," +
                        "'CATEGORY_TYPE','TEA',10,100,'gift')",
                )
            f.exec(
                "INSERT INTO " +
                    "order_promotion_reward_items(application_id,reward_order_batch_item_id," +
                    "reward_menu_item_id,reward_qty,label_snapshot) VALUES ($application,$id,$item,1,'Gift')",
            )
            assertEquals("1", products().rows.single().giftUnits)
            assertEquals("2", products().rows.single().nonGiftUnits)
            save(base.copy(lines = base.lines.map { it.copy(giftReason = "Whole line") }))
            assertEquals("3", products().rows.single().giftUnits)
            val program =
                f.insert(
                    "INSERT INTO loyalty_programs(venue_id,program_type,status,nth_value,created_by_user_id) " +
                        "VALUES (${f.venue},'NTH_HOOKAH_FREE','ACTIVE',3,7)",
                )
            f.exec(
                "INSERT INTO " +
                    "loyalty_redemptions(program_id,venue_id,user_id,order_id,batch_id,order_batch_item_id," +
                    "order_promotion_application_id,status,discount_minor,dedupe_key) VALUES ($program," +
                    "${f.venue},7,${f.order},$batch,$id,$application,'APPLIED',100,'gift')",
            )
            save(base)
            assertEquals("1", products().rows.single().giftUnits)
            save(base.copy(lines = base.lines.map { it.copy(baseGiftMinor = 150) }))
            assertNull(products().rows.single().giftUnits)
            save(base.copy(lines = base.lines.map { it.copy(id = "legacy-combined", baseGiftMinor = 200) }))
            assertNull(products().rows.single().giftUnits)
            save(base.copy(currency = "IQD"))
            assertNull(products().rows.single().giftUnits)
            save(base.copy(lines = base.lines.map { it.copy(baseGiftMinor = 0) }))
            assertEquals("1", products().rows.single().giftUnits)
            val other =
                f.insert(
                    "INSERT INTO menu_items(venue_id,category_id,name,price_minor,currency) VALUES " +
                        "(${f.venue},$cat,'Other',99999,'IQD')",
                )
            f.exec(
                "INSERT INTO " +
                    "order_batch_items(order_batch_id,menu_item_id,qty,item_name_snapshot," +
                    "base_unit_price_minor_snapshot,currency_snapshot) VALUES ($batch,$other,1,'Historical " +
                    "other',100,'IQD')",
            )
            assertEquals(setOf("RUB", "IQD"), products().currencies.toSet())
            assertEquals("0", products().rows.single { it.itemId == other }.giftUnits)
            save(base.copy(lines = base.lines.map { it.copy(id = "legacy-combined", baseGiftMinor = 200) }))
            assertTrue(products().rows.all { it.giftUnits == null && it.nonGiftUnits == null })
            assertEquals(setOf("300", "100"), products().rows.map { it.snapshotGrossMinor }.toSet())
        }

    @Test
    fun `completed additions require CLOSED tab while ACTIVE tabs remain a separate snapshot`() =
        fixture { f ->
            val tab =
                f.insert(
                    "INSERT INTO tab(venue_id,table_session_id,type) VALUES " +
                        "(${f.venue},${f.session},'SHARED')",
                )
            val category = f.insert("INSERT INTO menu_categories(venue_id,name) VALUES (${f.venue},'Menu')")
            val item =
                f.insert(
                    "INSERT INTO menu_items(venue_id,category_id,name,price_minor) VALUES " +
                        "(${f.venue},$category,'Tea',100)",
                )
            val batch = f.batch()
            f.exec(
                "UPDATE order_batches SET tab_id=$tab,status='DELIVERED',first_delivered_at=TIMESTAMP " +
                    "WITH TIME ZONE '2026-11-09 00:00:00+00' WHERE id=$batch",
            )
            f.exec(
                "INSERT INTO " +
                    "order_batch_items(order_batch_id,menu_item_id,qty,item_name_snapshot," +
                    "base_unit_price_minor_snapshot,currency_snapshot) VALUES ($batch,$item,1,'Tea',100," +
                    "'RUB')",
            )
            f.exec(
                "UPDATE orders SET status='CLOSED',closed_at=TIMESTAMP WITH TIME ZONE '2026-11-09 " +
                    "01:00:00+00' WHERE id=${f.order}",
            )

            fun stats() = runBlocking { f.repo.venue(f.venue, 7, request.copy(section = "additions")) }
            assertEquals("0", stats().metrics.getValue("additions.completedTabs").value)
            assertNull(stats().metrics.getValue("additions.share").value)
            assertEquals("1", stats().metrics.getValue("additions.openTabs").value)
            f.exec("UPDATE tab SET status='CLOSED' WHERE id=$tab")
            val before = f.fingerprint()
            repeat(2) { assertEquals("1", stats().metrics.getValue("additions.completedTabs").value) }
            assertEquals(before, f.fingerprint())
            assertEquals("0", stats().metrics.getValue("additions.openTabs").value)
            f.exec("UPDATE orders SET status='ACTIVE' WHERE id=${f.order}")
            assertEquals("0", stats().metrics.getValue("additions.completedTabs").value)
            f.exec("UPDATE tab SET status='ACTIVE' WHERE id=$tab")
            assertEquals("1", stats().metrics.getValue("additions.openTabs").value)
            assertFailsWith<java.sql.SQLException> { f.exec("UPDATE tab SET status='PAID' WHERE id=$tab") }
            f.exec("UPDATE orders SET status='CLOSED',closed_at=NULL WHERE id=${f.order}")
            f.exec("UPDATE tab SET status='CLOSED' WHERE id=$tab")
            assertEquals("PARTIAL", stats().metrics.getValue("additions.completedTabs").availability)
            f.exec("UPDATE order_batches SET tab_id=NULL WHERE id=$batch")
            assertEquals("PARTIAL", stats().metrics.getValue("additions.share").availability)
        }

    @Test
    fun `booking attention follows committed guest conversation not booking status or closed thread`() =
        fixture { f ->
            f.exec("INSERT INTO users(telegram_user_id) VALUES (8),(9)")
            f.exec("INSERT INTO venue_members(venue_id,user_id,role) VALUES (${f.venue},9,'STAFF')")
            val booking =
                f.insert(
                    "INSERT INTO bookings(venue_id,user_id,scheduled_at,status,display_date,display_number) " +
                        "VALUES (${f.venue},8,CURRENT_TIMESTAMP,'SEATED',CURRENT_DATE,1)",
                )
            f.insert(
                "INSERT INTO support_threads(venue_id,guest_user_id,category,status,thread_type," +
                    "assignee_scope,created_source,title) " +
                    "VALUES (${f.venue},8,'OTHER','OPEN','VENUE_CHAT','VENUE','GUEST_BOT','Unrelated')",
            )
            val support = SupportThreadRepository(f.ds)

            fun send(repo: SupportThreadRepository = support) =
                runBlocking {
                    repo.addBookingMessage(
                        booking,
                        8,
                        SupportMessageAuthorRole.GUEST,
                        SupportMessageSource.GUEST_BOT,
                        "Private message and contact not for dashboard",
                        expectedGuestUserId = 8,
                        expectedVenueId = f.venue,
                    )!!
                }
            val first = send()
            val dashboard = VenueDashboardRepository(f.ds)

            fun snapshot() = runBlocking { dashboard.snapshot(f.venue, 7, 3) }
            for (status in listOf("CONFIRMED", "CHANGED", "SEATED")) {
                f.exec("UPDATE bookings SET status='$status' WHERE id=$booking")
                val before = f.fingerprint()
                val result = snapshot()
                assertEquals(listOf(booking), result.attention.mapNotNull { it.bookingId })
                assertEquals(first.thread.id, result.attention.single().threadId)
                assertTrue(first.thread.id != booking)
                assertTrue(result.attentionComplete)
                assertEquals(1, result.attentionTotalAtLeast)
                assertFalse(Json.encodeToString(result).contains("Private message"))
                assertEquals(before, f.fingerprint())
            }
            send()
            assertEquals(1, snapshot().attention.size)
            for (status in listOf("CLOSED", "RESOLVED")) {
                f.exec("UPDATE support_threads SET status='$status' WHERE id=${first.thread.id}")
                assertTrue(snapshot().attention.isEmpty())
            }
            f.exec("UPDATE support_threads SET status='IN_PROGRESS' WHERE id=${first.thread.id}")
            f.exec(
                "INSERT INTO support_thread_reads(thread_id,user_id,last_read_message_id) SELECT " +
                    "${first.thread.id},7,MAX(id) FROM support_messages",
            )
            assertTrue(snapshot().attention.isEmpty())
            runBlocking {
                support.addBookingMessage(
                    booking,
                    7,
                    SupportMessageAuthorRole.VENUE,
                    SupportMessageSource.STAFF_CHAT,
                    "Staff response",
                    expectedVenueId = f.venue,
                )
            }
            assertTrue(snapshot().attention.isEmpty())
            val inserted = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val delayed =
                SupportThreadRepository(f.ds, bookingConversationCheckpoint = { phase ->
                    if (phase == BookingConversationCheckpoint.AFTER_MESSAGE_WRITE) {
                        inserted.countDown()
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                })
            val future = java.util.concurrent.CompletableFuture.supplyAsync { send(delayed) }
            try {
                assertTrue(inserted.await(10, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(snapshot().attention.isEmpty())
            } finally {
                release.countDown()
            }
            future.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(1, snapshot().attention.size)
            assertTrue(runBlocking { dashboard.snapshot(f.venue, 9, 3) }.attention.isEmpty())
            val foreign = f.insert("INSERT INTO venues(name,status) VALUES ('Foreign','PUBLISHED')")
            f.exec("UPDATE bookings SET venue_id=$foreign WHERE id=$booking")
            assertTrue(snapshot().attention.isEmpty())
            f.exec("UPDATE bookings SET venue_id=${f.venue} WHERE id=$booking")
            f.ds.connection.use { c ->
                c.prepareStatement(
                    "INSERT INTO bookings(venue_id,user_id,scheduled_at,status,display_date,display_number) " +
                        "VALUES (?,8,CURRENT_TIMESTAMP,'PENDING',CURRENT_DATE,?)",
                ).use { st ->
                    repeat(257) {
                        st.setLong(1, f.venue)
                        st.setInt(2, it + 2)
                        st.addBatch()
                    }
                    st.executeBatch()
                }
            }
            val overflow = snapshot()
            assertFalse(overflow.attentionComplete)
            assertEquals(256, overflow.attention.size)
            assertEquals(257, overflow.attentionTotalAtLeast)
            val before = f.fingerprint()
            snapshot()
            assertEquals(before, f.fingerprint())
            f.exec("DELETE FROM venue_members WHERE venue_id=${f.venue} AND user_id=7")
            assertFailsWith<ForbiddenException> { snapshot() }
        }

    protected fun fixture(block: (Fixture) -> Unit) {
        val ds: DataSource =
            if (postgres) {
                PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase())
            } else {
                JdbcDataSource().apply {
                    setURL(
                        "jdbc:h2:mem:kpi_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    )
                    user = "sa"
                }
            }
        try {
            if (!postgres) Flyway.configure().dataSource(ds).locations("classpath:db/migration/h2").load().migrate()
            block(Fixture(ds))
        } finally {
            (ds as? AutoCloseable)?.close()
        }
    }

    protected inner class Fixture(val ds: DataSource) {
        val repo = KpiRepository(ds, clock)
        val venue =
            insert(
                "INSERT INTO venues(name, status, created_at) VALUES " +
                    "('Venue','PUBLISHED',TIMESTAMP WITH TIME ZONE '2026-11-01 00:00:00+00')",
            )
        val table = insert("INSERT INTO venue_tables(venue_id, table_number) VALUES ($venue,1)")
        val session =
            insert(
                "INSERT INTO " +
                    "table_sessions(venue_id, table_id, status, started_at, last_activity_at, expires_at) " +
                    "VALUES " +
                    "($venue,$table,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,TIMESTAMP " +
                    "WITH TIME ZONE '2030-01-01 00:00:00+00')",
            )
        val order =
            insert(
                "INSERT INTO " +
                    "orders(venue_id, table_id, table_session_id, status, created_at, acceptance_history_complete) " +
                    "VALUES ($venue,$table,$session,'ACTIVE',TIMESTAMP WITH TIME ZONE '2026-11-01 00:00:00+00',TRUE)",
            )

        init {
            exec("INSERT INTO users(telegram_user_id) VALUES (7)")
            exec("INSERT INTO venue_members(venue_id, user_id, role) VALUES ($venue,7,'OWNER')")
            exec("INSERT INTO venue_settings(venue_id, timezone) VALUES ($venue,'UTC')")
        }

        fun venueReport() = runBlocking { repo.venue(venue, 7, request) }

        fun batch(
            created: String = "2026-11-02 00:00:00+00",
            complete: Boolean = true,
        ) = insert(
            "INSERT INTO order_batches(order_id, source, status, created_at, status_history_complete) " +
                "VALUES ($order,'MINIAPP','NEW',TIMESTAMP WITH TIME ZONE '$created',$complete)",
        )

        fun call(reason: String) =
            insert(
                "INSERT INTO " +
                    "staff_calls(venue_id, table_id, order_id, reason, status, created_at, status_history_complete) " +
                    "VALUES ($venue,$table,$order,'$reason','NEW',TIMESTAMP WITH TIME " +
                    "ZONE '2026-11-09 00:00:00+00',TRUE)",
            )

        fun exec(sql: String) {
            ds.connection.use { it.createStatement().use { s -> s.executeUpdate(sql) } }
        }

        fun insert(sql: String): Long =
            ds.connection.use { c ->
                c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { s ->
                    s.executeUpdate()
                    s.generatedKeys.use { r ->
                        check(r.next())
                        r.getLong(1)
                    }
                }
            }

        fun fingerprint(): List<String> =
            ds.connection.use { c ->
                listOf(
                    "analytics_events",
                    "order_batches",
                    "orders",
                    "staff_calls",
                    "venue_subscriptions",
                    "venue_accounting_events",
                    "venue_accounting_commands",
                    "venue_accounting_sales",
                    "tab",
                    "support_threads",
                    "support_messages",
                    "support_thread_reads",
                )
                    .map { table ->
                        c.read("SELECT * FROM $table") { r ->
                            (1..r.metaData.columnCount).joinToString { r.getString(it).orEmpty() }
                        }.sorted().joinToString()
                    }
            }
    }
}
