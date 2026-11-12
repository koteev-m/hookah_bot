package com.hookah.platform.backend.analytics

import com.hookah.platform.backend.billing.BillingAdjustmentRepository
import com.hookah.platform.backend.billing.BillingInvoiceRepository
import com.hookah.platform.backend.billing.BillingPaymentRepository
import com.hookah.platform.backend.billing.PaymentStatus
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PlatformBillingKpiH2Test : PlatformBillingKpiContract(false)

internal class PlatformBillingKpiPostgresTest : PlatformBillingKpiContract(true) {
    @Test
    fun `dense PostgreSQL snapshot preserves results without a payment scan per invoice`() =
        fixture { f ->
            // The independent review workload, retained as an executable regression fixture.
            f.exec(
                "INSERT INTO venues(name,status,created_at) SELECT 'Scale','PUBLISHED'," +
                    "TIMESTAMPTZ '2026-01-01' FROM generate_series(1,1000)",
            )
            f.exec(
                "INSERT INTO billing_invoices(venue_id,period_start,period_end,due_at,amount_minor,currency," +
                    "description,provider,status,created_at,paid_at) SELECT v.id," +
                    "(DATE '2026-01-01'+(n-1)*INTERVAL '1 month')::date," +
                    "(DATE '2026-01-01'+n*INTERVAL '1 month'-INTERVAL '1 day')::date," +
                    "(DATE '2026-01-01'+(n-1)*INTERVAL '1 month')::timestamp,1000,'RUB','scale','fake'," +
                    "CASE WHEN n<=10 THEN 'PAID' ELSE 'OPEN' END,TIMESTAMP '2026-01-01',TIMESTAMP '2026-10-31' " +
                    "FROM venues v CROSS JOIN generate_series(1,12) n",
            )
            f.exec(
                "INSERT INTO billing_payments(invoice_id,provider,provider_event_id,amount_minor,currency," +
                    "status,occurred_at) SELECT id,'fake','scale:'||id,1000,'RUB','SUCCEEDED'," +
                    "TIMESTAMP '2026-10-31' FROM billing_invoices WHERE status='PAID'",
            )
            f.exec(
                "INSERT INTO billing_adjustments(venue_id,kind,days,reason,previous_paid_through,new_paid_through," +
                    "actor_user_id,created_at) SELECT id,'COURTESY_DAYS',30,'scale',DATE '2026-10-31'," +
                    "DATE '2026-11-30',7,TIMESTAMP '2026-11-01' FROM venues",
            )
            f.exec("INSERT INTO users(telegram_user_id) VALUES (7)")
            val table = f.insert("INSERT INTO venue_tables(venue_id,table_number) VALUES (${f.venue},1)")
            val session =
                f.insert(
                    "INSERT INTO table_sessions(venue_id,table_id,status,started_at,last_activity_at,expires_at) " +
                        "VALUES (${f.venue},$table,'ACTIVE',TIMESTAMPTZ '2026-11-01'," +
                        "TIMESTAMPTZ '2026-11-01',TIMESTAMPTZ '2030-01-01')",
                )
            val order =
                f.insert(
                    "INSERT INTO orders(venue_id,table_id,table_session_id,status,created_at," +
                        "acceptance_history_complete) " +
                        "VALUES (${f.venue},$table,$session,'ACTIVE',TIMESTAMPTZ '2026-11-01',TRUE)",
                )
            f.exec(
                "INSERT INTO order_batches(order_id,source,status,created_at,status_history_complete) " +
                    "SELECT $order,'MINIAPP','ACCEPTED',TIMESTAMPTZ '2025-01-01',TRUE FROM generate_series(1,20000)",
            )
            f.exec(
                "INSERT INTO order_batch_status_facts(batch_id,transition_sequence,order_id,venue_id," +
                    "from_status,to_status,occurred_at,producer,channel) " +
                    "SELECT id,1,order_id,${f.venue},'NEW','ACCEPTED',TIMESTAMPTZ '2026-11-09','TEST','UNKNOWN' " +
                    "FROM order_batches",
            )
            f.exec(
                "INSERT INTO support_threads(venue_id,guest_user_id,category,status,title,thread_type,created_at) " +
                    "SELECT ${f.venue},7,'OTHER','NEW','scale','SUPPORT_TICKET',TIMESTAMPTZ '2026-11-02' " +
                    "FROM generate_series(1,1000)",
            )
            f.exec(
                "INSERT INTO support_messages(thread_id,author_user_id,author_role,source,text,created_at) " +
                    "SELECT t.id,7,CASE WHEN n=1 THEN 'PLATFORM' ELSE 'GUEST' END," +
                    "CASE WHEN n=1 THEN 'PLATFORM_MINIAPP' ELSE 'GUEST_MINIAPP' END,'scale'," +
                    "TIMESTAMPTZ '2026-11-02'+n*INTERVAL '1 minute' FROM support_threads t " +
                    "CROSS JOIN generate_series(1,20) n",
            )
            f.exec("ANALYZE")
            val before = f.fingerprint()
            val evidence =
                StringBuilder(
                    "1001 venues, 12012 invoices, 10010 payments, 1001 courtesy, " +
                        "20000 facts, 1000 support tickets, 20000 messages\n",
                )
            val plans =
                f.ds.connection.use { c ->
                    assertEquals(12012, c.count("SELECT COUNT(*) FROM billing_invoices"))
                    assertEquals(10010, c.count("SELECT COUNT(*) FROM billing_payments"))
                    evidence.append(c.read("SELECT version()") { it.getString(1) }.single()).append('\n')
                    listOf(LEGACY_MULTIPLE_MATCHING_SUCCESSES_SQL, MULTIPLE_MATCHING_SUCCESSES_SQL).map { sql ->
                        val count = c.count(sql, listOf(now, now))
                        assertEquals(0, count)
                        val plan =
                            c.read("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) $sql", listOf(now, now)) {
                                it.getString(1)
                            }.joinToString("\n")
                        evidence.append("\n$sql\nresult=$count\n$plan\n")
                        plan
                    }
                }
            val oldStart = System.nanoTime()
            val old = f.snapshot(source = legacyMatchingDataSource(f.ds))
            evidence.append("\nLegacy full Platform snapshot ms=${(System.nanoTime() - oldStart) / 1_000_000}\n")
            val newStart = System.nanoTime()
            val current = f.snapshot()
            evidence.append("New full Platform snapshot ms=${(System.nanoTime() - newStart) / 1_000_000}\n")
            val output = Path.of("build/reports/kpi-billing-dense-query-plans.txt")
            Files.createDirectories(output.parent)
            Files.writeString(output, evidence.toString())
            assertEquals(old, current, "the complete Platform DTO must be identical on the same fixture and asOf")
            assertEquals("1001", current.metrics.getValue("venues.total").value)
            assertEquals("10010", current.metrics.getValue("invoices.paid").value)
            assertEquals("1", current.metrics.getValue("venues.waav").observedValue)
            assertEquals("60000.000", current.metrics.getValue("support.ttfr").value)
            assertEquals(before, f.fingerprint())
            assertTrue(Regex("Seq Scan on billing_payments.*loops=12012").containsMatchIn(plans[0]), plans[0])
            assertFalse(plans[1].contains("SubPlan"), plans[1])
            assertTrue(Regex("Seq Scan on billing_payments.*loops=1\\)").containsMatchIn(plans[1]), plans[1])

            fun milliseconds(plan: String) =
                Regex(
                    "Execution Time: ([0-9.]+) ms",
                ).find(plan)!!.groupValues[1].toDouble()
            assertTrue(milliseconds(plans[1]) < milliseconds(plans[0]) / 4, evidence.toString())
        }
}

internal abstract class PlatformBillingKpiContract(private val postgres: Boolean) {
    protected val now = Instant.parse("2026-11-10T12:00:00Z")
    protected val request = KpiRequest("2026-11-01", "2026-11-11")

    @Test
    fun `courtesy selection agrees with billing for shared baseline both orders ties and extensions`() {
        for (ends in listOf(
            listOf("2026-11-30", "2026-11-01"),
            listOf("2026-11-01", "2026-11-30"),
            listOf("2026-11-30", "2026-11-30"),
            listOf("2026-11-01", "2026-11-30", "2026-12-05"),
        )) {
            fixture { f ->
                f.invoice(periodEnd = "2026-10-31", status = "PAID")
                val adjustments = BillingAdjustmentRepository(f.ds)
                val invoices = BillingInvoiceRepository(f.ds)
                val base = assertNotNull(runBlocking { invoices.findLatestPaidInvoicePeriodEnd(f.venue) })
                assertEquals(LocalDate.parse("2026-10-31"), base)
                var previous = base
                var expectedId = 0L
                var expectedEnd = base
                for ((index, end) in ends.withIndex()) {
                    val next = LocalDate.parse(end)
                    val grant = f.courtesy(next, if (ends.size == 3) previous else base, index)
                    if (next >= expectedEnd) {
                        expectedId = grant
                        expectedEnd = next
                    }
                    previous = next
                    val selected = runBlocking { adjustments.findLatestCourtesyAdjustment(f.venue) }
                    assertEquals(expectedId, selected?.id, "billing tie-break is highest ID at maximum end date")
                    assertEquals(expectedEnd, selected?.newPaidThrough)
                    val before = f.fingerprint()
                    repeat(2) {
                        val report = f.snapshot()
                        assertEquals(
                            listOf(KpiBucket(expectedEnd.toString(), 1)),
                            report.breakdowns.getValue("subscriptions.effectivePaidThrough"),
                            "saved grant order: $ends",
                        )
                        assertEquals(
                            if (expectedEnd >= now.atZone(ZoneOffset.UTC).toLocalDate()) "1" else "0",
                            report.metrics.getValue("subscriptions.effectivePaidThroughCurrent").value,
                        )
                        assertEquals("0", report.metrics.getValue("subscriptions.paidThroughCurrent").value)
                        assertEquals(
                            listOf(KpiBucket(base.toString(), 1)),
                            report.breakdowns["subscriptions.paidThrough"],
                        )
                    }
                    assertEquals(before, f.fingerprint())
                }
            }
        }
    }

    @Test
    fun `courtesy cutoff is exclusive and courtesy alone never proves a payment`() =
        fixture { f ->
            val base = LocalDate.parse("2026-10-31")
            f.courtesy(LocalDate.parse("2026-11-30"), base, 0)
            f.courtesy(LocalDate.parse("2026-11-01"), base, 1)
            f.courtesy(LocalDate.parse("2026-12-05"), LocalDate.parse("2026-11-30"), 2)
            for ((cutoff, end) in listOf(
                "2026-11-04T00:00:00Z" to null,
                "2026-11-05T00:00:00Z" to null,
                "2026-11-05T00:00:00.000001Z" to "2026-11-30",
                "2026-11-06T00:00:00Z" to "2026-11-30",
                "2026-11-06T00:00:00.000001Z" to "2026-11-30",
                "2026-11-07T00:00:00Z" to "2026-11-30",
                "2026-11-07T00:00:00.000001Z" to "2026-12-05",
            )) {
                val before = f.fingerprint()
                val report = f.snapshot(Instant.parse(cutoff))
                assertEquals(
                    listOf(KpiBucket(end ?: "UNKNOWN", 1)),
                    report.breakdowns["subscriptions.effectivePaidThrough"],
                )
                val effective = report.metrics.getValue("subscriptions.effectivePaidThroughCurrent")
                assertEquals(if (end == null) null else "1", effective.value)
                assertEquals(if (end == null) 1L else 0L, effective.unknown)
                val paid = report.metrics.getValue("subscriptions.paidThroughCurrent")
                assertNull(paid.value)
                assertEquals(1, paid.unknown)
                assertEquals("NO_RECORDED_PAID_PERIOD", paid.reason)
                assertEquals("0", report.metrics.getValue("invoices.paid").value)
                assertEquals("0", report.metrics.getValue("invoices.manualConfirmed").value)
                assertEquals(emptyList(), report.invoiceFinances["paid"])
                assertEquals(before, f.fingerprint())
            }
        }

    @Test
    fun `missing courtesy retains base or unknown and invalid extensions are not stored`() =
        fixture { f ->
            assertNull(runBlocking { BillingAdjustmentRepository(f.ds).findLatestCourtesyAdjustment(f.venue) })
            assertNull(f.snapshot().metrics.getValue("subscriptions.effectivePaidThroughCurrent").value)
            assertEquals("0", f.snapshot().metrics.getValue("subscriptions.withCourtesy").value)
            f.invoice(status = "PAID", periodEnd = "2026-11-30")
            assertEquals("1", f.snapshot().metrics.getValue("subscriptions.effectivePaidThroughCurrent").value)
            assertEquals("1", f.snapshot().metrics.getValue("subscriptions.paidThroughCurrent").value)
            assertFailsWith<SQLException> {
                f.exec(
                    "INSERT INTO billing_adjustments(venue_id,kind,days,reason,previous_paid_through," +
                        "new_paid_through,actor_user_id) VALUES (${f.venue},'COURTESY_DAYS',1,'test'," +
                        "DATE '2026-11-30',DATE '2026-11-01',7)",
                )
            }
            assertEquals("0", f.snapshot().metrics.getValue("subscriptions.withCourtesy").value)
        }

    @Test
    fun `matching success warning counts invoices once across statuses venues currencies and deduped retries`() =
        fixture { f ->
            assertEquals("0", f.snapshot().metrics.getValue("invoices.multipleMatchingSuccesses").value)
            val foreign =
                f.insert(
                    "INSERT INTO venues(name,status,created_at) VALUES ('Other','DRAFT',TIMESTAMP '2026-11-01')",
                )
            for ((index, status) in listOf("DRAFT", "OPEN", "PAID", "PAST_DUE", "VOID").withIndex()) {
                val currency = if (index == 1) "USD" else "RUB"
                val invoice = f.invoice(if (index % 2 == 0) f.venue else foreign, status, currency)
                f.payment(invoice, "one-$invoice", currency = currency)
                assertEquals(
                    index.toString(),
                    f.snapshot().metrics.getValue("invoices.multipleMatchingSuccesses").value,
                )
                f.payment(invoice, "two-$invoice", currency = currency)
                f.payment(invoice, "three-$invoice", currency = currency)
                f.payment(invoice, "three-$invoice", currency = currency, inserted = false)
                assertEquals(
                    (index + 1).toString(),
                    f.snapshot().metrics.getValue("invoices.multipleMatchingSuccesses").value,
                )
            }
            val report = f.snapshot()
            assertEquals(listOf("RUB", "USD"), report.invoiceFinances.getValue("open").map { it.currency }.sorted())
            assertEquals("100", report.invoiceFinances.getValue("paid").single().metrics.getValue("amount").value)
            assertEquals("0", report.metrics.getValue("invoices.paidWithoutMatchingEvidence").value)
            assertEquals("12", report.metrics.getValue("invoices.inconsistentSuccessfulEvents").value)
            f.assertLegacyEquivalent()
        }

    @Test
    fun `mismatches non successes invoice identity and asOf boundaries do not form a matching pair`() =
        fixture { f ->
            val invoice = f.invoice(status = "PAID")
            val other = f.invoice(status = "OPEN")
            f.payment(invoice, "one")
            f.payment(other, "other-invoice")
            f.payment(invoice, "amount", amount = 101)
            f.payment(invoice, "amount-second", amount = 101)
            f.payment(invoice, "currency", currency = "USD")
            f.payment(invoice, "currency-second", currency = "USD")
            f.payment(invoice, "provider", provider = "other")
            f.payment(invoice, "provider-second", provider = "other")
            f.payment(invoice, "failed", status = PaymentStatus.FAILED)
            f.payment(invoice, "refunded", status = PaymentStatus.REFUNDED)
            f.payment(invoice, "at-cutoff", occurred = now)
            f.payment(invoice, "after-cutoff", occurred = now.plusSeconds(1))
            val future = f.invoice(status = "VOID")
            f.exec("UPDATE billing_invoices SET created_at = TIMESTAMP '2026-11-10 12:00:00' WHERE id = $future")
            f.payment(future, "future-one")
            f.payment(future, "future-two")
            assertEquals("0", f.snapshot().metrics.getValue("invoices.multipleMatchingSuccesses").value)
            f.assertLegacyEquivalent()
            f.payment(invoice, "before-cutoff", occurred = now.minusNanos(1000))
            val before = f.fingerprint()
            repeat(2) {
                val report = f.snapshot()
                assertEquals("1", report.metrics.getValue("invoices.multipleMatchingSuccesses").value)
                assertEquals("9", report.metrics.getValue("invoices.inconsistentSuccessfulEvents").value)
                assertEquals("100", report.invoiceFinances.getValue("paid").single().metrics.getValue("amount").value)
                assertNull(report.metrics.getValue("invoices.bankSettlement").value)
            }
            assertEquals(before, f.fingerprint())
            f.assertLegacyEquivalent()
        }

    protected fun fixture(block: (Fixture) -> Unit) {
        val ds: DataSource =
            if (postgres) {
                PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase())
            } else {
                JdbcDataSource().apply {
                    setURL("jdbc:h2:mem:billing_kpi_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
                    user = "sa"
                }
            }
        // Keep H2 alive between connections without retaining it after the test.
        ds.connection.use {
            try {
                if (!postgres) Flyway.configure().dataSource(ds).locations("classpath:db/migration/h2").load().migrate()
                block(Fixture(ds))
            } finally {
                (ds as? AutoCloseable)?.close()
            }
        }
    }

    protected inner class Fixture(val ds: DataSource) {
        val venue =
            insert("INSERT INTO venues(name,status,created_at) VALUES ('Venue','PUBLISHED',TIMESTAMP '2026-11-01')")
        private var invoiceSequence = 0L

        fun snapshot(
            asOf: Instant = now,
            source: DataSource = ds,
        ): KpiSnapshot = runBlocking { KpiRepository(source, Clock.fixed(asOf, ZoneOffset.UTC)).platform(request) }

        fun courtesy(
            end: LocalDate,
            previous: LocalDate,
            offset: Int,
        ): Long {
            val grant =
                runBlocking {
                    BillingAdjustmentRepository(ds).createCourtesyDaysAdjustment(
                        venue,
                        ChronoUnit.DAYS.between(previous, end).toInt(),
                        "test",
                        previous,
                        end,
                        7,
                    )
                }
            exec(
                "UPDATE billing_adjustments SET created_at = TIMESTAMP '2026-11-${5 + offset} 00:00:00' " +
                    "WHERE id = ${grant.id}",
            )
            return grant.id
        }

        fun invoice(
            venueId: Long = venue,
            status: String = "OPEN",
            currency: String = "RUB",
            periodEnd: String = "2026-11-30",
        ): Long {
            val periodStart = LocalDate.parse("2020-01-01").plusDays(invoiceSequence++)
            return insert(
                "INSERT INTO billing_invoices(venue_id,period_start,period_end,due_at,amount_minor,currency," +
                    "description,provider,status,created_at,paid_at) VALUES ($venueId," +
                    "DATE '$periodStart',DATE '$periodEnd'," +
                    "TIMESTAMP '2026-11-01',100,'$currency','test','fake','$status',TIMESTAMP '2026-11-01'," +
                    "${if (status == "PAID") "TIMESTAMP '2026-11-02'" else "NULL"})",
            )
        }

        fun payment(
            invoice: Long,
            event: String,
            amount: Int = 100,
            currency: String = "RUB",
            provider: String = "fake",
            status: PaymentStatus = PaymentStatus.SUCCEEDED,
            occurred: Instant = now.minusSeconds(60),
            inserted: Boolean = true,
        ) {
            assertEquals(
                inserted,
                runBlocking {
                    BillingPaymentRepository(ds).insertPaymentEventIdempotent(
                        invoice,
                        provider,
                        event,
                        amount,
                        currency,
                        status,
                        occurred,
                        null,
                    )
                },
            )
        }

        fun assertLegacyEquivalent() {
            val expected = ds.connection.use { it.count(LEGACY_MULTIPLE_MATCHING_SUCCESSES_SQL, listOf(now, now)) }
            assertEquals(expected.toString(), snapshot().metrics.getValue("invoices.multipleMatchingSuccesses").value)
        }

        fun fingerprint(): List<String> =
            ds.connection.use { c ->
                listOf(
                    "billing_adjustments",
                    "billing_invoices",
                    "billing_payments",
                    "venue_subscriptions",
                    "analytics_events",
                )
                    .map { table ->
                        c.read("SELECT * FROM $table") { r ->
                            (1..r.metaData.columnCount).joinToString { r.getString(it).orEmpty() }
                        }.sorted().joinToString()
                    }
            }

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
    }
}

private const val LEGACY_MULTIPLE_MATCHING_SUCCESSES_SQL =
    "SELECT COUNT(*) FROM billing_invoices i WHERE i.created_at < ? AND " +
        "(SELECT COUNT(*) FROM billing_payments p WHERE p.invoice_id = i.id AND p.status = 'SUCCEEDED' " +
        "AND p.amount_minor = i.amount_minor AND p.currency = i.currency " +
        "AND p.provider = i.provider AND p.occurred_at < ?) > 1"

// Substitute only the old warning query; preserve the real repository snapshot, transaction and all other SQL.
private fun legacyMatchingDataSource(source: DataSource): DataSource =
    object : DataSource by source {
        override fun getConnection(): Connection {
            val connection = source.connection
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, args ->
                val actual = args?.map { it }?.toTypedArray()
                if (method.name == "prepareStatement" && actual?.firstOrNull() == MULTIPLE_MATCHING_SUCCESSES_SQL) {
                    actual[0] = LEGACY_MULTIPLE_MATCHING_SUCCESSES_SQL
                }
                try {
                    method.invoke(connection, *(actual ?: emptyArray()))
                } catch (error: InvocationTargetException) {
                    throw error.targetException
                }
            } as Connection
        }
    }
