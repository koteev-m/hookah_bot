package com.hookah.platform.backend.billing

import com.hookah.platform.backend.analytics.KpiRepository
import com.hookah.platform.backend.analytics.KpiRequest
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingHooks
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.subscription.VenueAvailabilityResolver
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ProductionBillingPostgresTest {
    private suspend fun HttpClient.billingStamp(
        venue: Long,
        token: String,
    ): String {
        val response = get("/api/platform/venues/$venue/billing") { bearerAuth(token) }
        assertEquals(200, response.status.value)
        val overview = Json.decodeFromString<OwnerBillingOverviewResponse>(response.bodyAsText())
        return assertNotNull(overview.lifecycleUpdatedAt)
    }

    private fun DataSource.number(sql: String): Long =
        connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    private fun DataSource.exec(sql: String) {
        connection.use { c -> c.createStatement().use { it.execute(sql) } }
    }

    private fun DataSource.venue(): Long =
        number("INSERT INTO venues(name,status,created_at) VALUES ('Test','PUBLISHED','2026-01-01') RETURNING id")

    private fun DataSource.invoice(
        v: Long,
        start: String,
        end: String,
        state: String,
    ): Long =
        number(
            "INSERT INTO billing_invoices(venue_id,period_start,period_end,due_at,amount_minor,currency," +
                "description,provider,status,created_at) VALUES ($v,'$start','$end','$start',105,'RUB'," +
                "'fixture','fake','$state','2026-01-01') RETURNING id",
        )

    @Test
    fun `V143 preserves previously granted days and creates no replay keys for old records`() {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database, migrate = false).use { ds ->
            fun flyway() =
                org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql")
            flyway().target("142").load().migrate()
            val venue = ds.venue()
            ds.exec(
                "INSERT INTO billing_adjustments(venue_id,kind,days,reason,previous_paid_through,new_paid_through," +
                    "actor_user_id) VALUES ($venue,'COURTESY_DAYS',3,'legacy','2026-10-31','2026-11-03',7001)",
            )
            val upgraded = flyway().load()
            assertEquals(1, upgraded.migrate().migrationsExecuted)
            upgraded.validate()
            assertEquals(0, upgraded.migrate().migrationsExecuted)
            assertEquals(3L, ds.number("SELECT sum(days) FROM billing_adjustments WHERE operation_key IS NULL"))
        }
    }

    @Test
    fun `old debt cannot restore access and manual invoice time is UTC across writers`() =
        runBlocking {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                val invoices = BillingInvoiceRepository(ds)
                val subscriptions = SubscriptionRepository(ds)
                val now = Instant.parse("2026-11-10T12:00:00Z")
                val clock = Clock.fixed(now, ZoneOffset.UTC)
                val service =
                    BillingService(
                        FakeBillingProvider(),
                        invoices,
                        BillingPaymentRepository(ds),
                        SubscriptionBillingHooks(subscriptions, clock),
                    )
                val defaultZone = TimeZone.getDefault()
                try {
                    for (zone in listOf("UTC", "Europe/Moscow")) {
                        TimeZone.setDefault(TimeZone.getTimeZone(zone))
                        val venue = ds.venue()
                        subscriptions.updateStatus(venue, SubscriptionStatus.SUSPENDED_BY_PLATFORM)
                        val old = ds.invoice(venue, "2026-09-01", "2026-09-30", "PAST_DUE")
                        ds.invoice(venue, "2026-11-01", "2026-11-30", "OPEN")
                        repeat(2) { service.applyManualPayment(assertNotNull(invoices.getInvoiceById(old)), now, 7) }
                        assertEquals(now, invoices.getInvoiceById(old)?.paidAt)
                        val status = subscriptions.getSubscriptionStatus(venue)
                        assertEquals(SubscriptionStatus.SUSPENDED_BY_PLATFORM, status)
                        assertFalse(VenueAvailabilityResolver.resolve(VenueStatus.PUBLISHED, status).available)
                        assertEquals(
                            1L,
                            ds.number("SELECT count(*) FROM billing_invoices WHERE venue_id=$venue AND status='OPEN'"),
                        )
                    }
                    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
                    assertEquals(
                        2L,
                        ds.number(
                            "SELECT count(*) FROM billing_invoices " +
                                "WHERE paid_at=TIMESTAMP '2026-11-10 12:00:00' AND amount_minor=105",
                        ),
                    )
                    assertEquals(210L, ds.number("SELECT sum(amount_minor) FROM billing_payments"))
                    val request = KpiRequest("2026-11-01", "2026-11-11", "invoices")
                    for ((cutoff, expected) in listOf(now to "0", now.plusSeconds(1) to "2")) {
                        val snapshot = KpiRepository(ds, Clock.fixed(cutoff, ZoneOffset.UTC)).platform(request)
                        assertEquals(expected, snapshot.metrics.getValue("invoices.manualConfirmed").value)
                    }
                    val venue = ds.venue()
                    subscriptions.updateStatus(venue, SubscriptionStatus.PAST_DUE)
                    val old = ds.invoice(venue, "2026-09-01", "2026-09-30", "PAST_DUE")
                    val current = ds.invoice(venue, "2026-11-01", "2026-11-30", "OPEN")
                    service.applyManualPayment(assertNotNull(invoices.getInvoiceById(old)), now, 7)
                    assertEquals(SubscriptionStatus.PAST_DUE, subscriptions.getSubscriptionStatus(venue))
                    service.applyManualPayment(assertNotNull(invoices.getInvoiceById(current)), now, 7)
                    assertEquals(SubscriptionStatus.ACTIVE, subscriptions.getSubscriptionStatus(venue))
                } finally {
                    TimeZone.setDefault(defaultZone)
                }
            }
        }

    @Test
    fun `courtesy HTTP retries serialize including audit failure and a lost response`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            val config = PostgresTestEnv.buildConfig(database)
            config.put("platform.ownerUserId", "7001")
            config.put("billing.subscription.intervalSeconds", "0")
            environment { this.config = config }
            application { module() }
            client.get("/health")
            val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))
            val owner = tokens.issueToken(7001).token
            PostgresTestEnv.createDataSource(database, migrate = false).use { ds ->
                ds.exec("INSERT INTO users(telegram_user_id) VALUES (7001)")
                val v = ds.venue()
                val last = java.time.LocalDate.now().plusDays(2)
                ds.invoice(v, last.minusDays(30).toString(), last.toString(), "PAID")
                ds.exec("INSERT INTO venue_subscriptions(venue_id,status) VALUES ($v,'PAST_DUE')")

                suspend fun grant(
                    key: UUID?,
                    days: Int = 3,
                    token: String = owner,
                    reason: String = "test",
                ) = client.post("/api/platform/venues/$v/billing/courtesy-days") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    if (key != null) header("Idempotency-Key", key.toString())
                    setBody("""{"days":$days,"reason":"$reason"}""")
                }

                val key = UUID.randomUUID()
                assertEquals(428, grant(null).status.value)
                assertEquals(403, grant(key, token = tokens.issueToken(7002).token).status.value)
                ds.exec(
                    "ALTER TABLE audit_log ADD CONSTRAINT refuse_grant CHECK (action <> 'BILLING_COURTESY_DAYS_ADDED')",
                )
                assertEquals(503, grant(key).status.value)
                assertEquals(0L, ds.number("SELECT count(*) FROM billing_adjustments"))
                assertEquals(
                    1L,
                    ds.number("SELECT count(*) FROM venue_subscriptions WHERE venue_id=$v AND status='PAST_DUE'"),
                )
                ds.exec("ALTER TABLE audit_log DROP CONSTRAINT refuse_grant")
                val results = coroutineScope { List(3) { async { grant(key) } }.awaitAll() }
                assertEquals(listOf(200, 200, 200), results.map { it.status.value })
                val original = results.first().bodyAsText()
                for (response in results.drop(1)) assertEquals(original, response.bodyAsText())
                ds.connection.use { connection ->
                    kotlin.test.assertFailsWith<com.hookah.platform.backend.api.BillingOperationConflictException> {
                        BillingAdjustmentRepository(ds).replay(connection, key, v, 7002, 3, "test")
                    }
                }
                assertEquals(409, grant(key, 4).status.value)
                assertEquals(409, grant(key, reason = "test ").status.value)
                assertEquals(200, grant(UUID.randomUUID()).status.value)
                assertEquals(original, grant(key).bodyAsText())
                assertEquals(6L, ds.number("SELECT SUM(days) FROM billing_adjustments WHERE venue_id=$v"))
                assertEquals(2L, ds.number("SELECT count(*) FROM audit_log WHERE action='BILLING_COURTESY_DAYS_ADDED'"))
            }
        }

    @Test
    fun `free overview retains payable invoice outside fifty paid history rows`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            val config = PostgresTestEnv.buildConfig(database)
            config.put("platform.ownerUserId", "7001")
            config.put("billing.subscription.intervalSeconds", "0")
            environment { this.config = config }
            application { module() }
            client.get("/health")
            val owner = SessionTokenService(SessionTokenConfig.from(config, "test")).issueToken(7001).token
            PostgresTestEnv.createDataSource(database, migrate = false).use { ds ->
                ds.exec("INSERT INTO users(telegram_user_id) VALUES (7001)")
                val venue = ds.venue()
                ds.exec(
                    "INSERT INTO venue_subscription_settings(venue_id,paid_start_date,base_price_minor," +
                        "currency,updated_by_user_id) VALUES ($venue,'2021-01-01',0,'RUB',7001)",
                )
                val id = ds.invoice(venue, "2020-12-01", "2020-12-31", "PAST_DUE")
                ds.exec(
                    "INSERT INTO billing_invoices(venue_id,period_start,period_end,due_at," +
                        "amount_minor,currency,description,provider,status) " +
                        "SELECT $venue,d,d+interval '1 month'-interval '1 day',d,105,'RUB','fixture','fake','PAID' " +
                        "FROM generate_series(0,49) n, LATERAL (SELECT date '2021-01-01'+n*interval '1 month' AS d) t",
                )
                ds.exec("INSERT INTO venue_members(venue_id,user_id,role) VALUES ($venue,7001,'OWNER')")
                val paths = listOf("/api/venue/$venue/subscription", "/api/platform/venues/$venue/billing")
                for (path in paths) {
                    val result = client.get(path) { bearerAuth(owner) }
                    assertEquals(200, result.status.value)
                    val read = Json.decodeFromString<OwnerBillingOverviewResponse>(result.bodyAsText())
                    assertEquals(50, read.invoices.size)
                    assertFalse(read.invoices.any { it.status != "PAID" })
                    val page = assertNotNull(read.outstandingInvoices)
                    assertEquals(id, page.items.single().id)
                    assertEquals("PAST_DUE", page.items.single().status)
                    assertFalse(page.hasMore)
                    assertEquals(null, page.nextAfterId)
                }
                val response = client.post("/api/platform/venues/$venue/billing/checkout") { bearerAuth(owner) }
                assertEquals(200, response.status.value)
                val overview = Json.decodeFromString<OwnerBillingOverviewResponse>(response.bodyAsText())
                assertEquals(0, overview.priceMinor)
                assertEquals(50, overview.invoices.size)
                assertFalse(overview.invoices.any { it.id == id })
                assertEquals(id, overview.payableInvoice?.id)
                assertEquals(51L, ds.number("SELECT count(*) FROM billing_invoices WHERE venue_id=$venue"))
            }
        }

    @Test
    fun `outstanding invoices remain individually discoverable outside both owners history pages`() =
        resumeApiTest { ds, tokens ->
            val venue = ds.venue()
            val foreign = ds.venue()
            ds.exec("INSERT INTO users(telegram_user_id) SELECT generate_series(7001,7003)")
            ds.exec("INSERT INTO venue_members(venue_id,user_id,role) VALUES ($venue,7002,'OWNER')")
            ds.exec(
                "INSERT INTO venue_subscription_settings(venue_id,paid_start_date,base_price_minor,currency," +
                    "updated_by_user_id) VALUES ($venue,'2021-01-01',0,'RUB',7001)",
            )
            val overdue = ds.invoice(venue, "2020-11-01", "2020-11-30", "PAST_DUE")
            val open = ds.invoice(venue, "2020-12-01", "2020-12-31", "OPEN")
            ds.exec("UPDATE billing_invoices SET amount_minor=5000 WHERE id=$overdue")
            ds.exec("UPDATE billing_invoices SET amount_minor=1000, due_at='2099-01-15' WHERE id=$open")
            ds.invoice(foreign, "2020-11-01", "2020-11-30", "PAST_DUE")

            fun seed(
                count: Int,
                status: String,
                start: String,
            ) {
                ds.exec(
                    "INSERT INTO billing_invoices(venue_id,period_start,period_end,due_at,amount_minor,currency," +
                        "description,provider,status) SELECT $venue,d,d,d,105,'RUB','fixture','fake','$status' " +
                        "FROM generate_series(0,${count - 1}) n, LATERAL (SELECT date '$start'+n AS d) t",
                )
            }

            seed(50, "PAID", "2021-01-01")
            val routes =
                mapOf("/api/platform/venues/$venue/billing" to 7001L, "/api/venue/$venue/subscription" to 7002L)

            suspend fun read(
                path: String,
                actor: Long,
                after: Long = 0,
            ): OwnerBillingOverviewResponse {
                val suffix = if (after == 0L) "" else "?outstandingAfterId=$after"
                val response = client.get(path + suffix) { bearerAuth(tokens.issueToken(actor).token) }
                assertEquals(200, response.status.value)
                val body = response.bodyAsText()
                val overview = Json.decodeFromString<OwnerBillingOverviewResponse>(body)
                assertEquals(50, overview.invoices.size)
                assertEquals(0, overview.priceMinor)
                return overview
            }

            suspend fun page(
                path: String,
                actor: Long,
                after: Long = 0,
            ): Pair<List<OwnerBillingInvoiceDto>, Long?> {
                val overview = read(path, actor, after)
                val page = assertNotNull(overview.outstandingInvoices, "missing outstanding invoice page")
                assertEquals(page.nextAfterId != null, page.hasMore)
                return page.items to page.nextAfterId
            }

            fun invoiceFingerprint() =
                ds.number(
                    "SELECT hashtextextended(string_agg(row_to_json(i)::text, ',' ORDER BY id), 0) " +
                        "FROM billing_invoices i WHERE venue_id=$venue",
                )

            val beforeRead = invoiceFingerprint()
            for ((path, actor) in routes) {
                val overview = read(path, actor)
                assertEquals(open, overview.payableInvoice?.id)
                assertFalse(overview.invoices.any { it.status != "PAID" })
                val (rows, next) = page(path, actor)
                assertEquals(listOf(overdue, open), rows.map { it.id })
                assertEquals(listOf(5000, 1000), rows.map { it.amountMinor })
                assertEquals(listOf("PAST_DUE", "OPEN"), rows.map { it.status })
                assertEquals(listOf("2020-11-01", "2020-12-01"), rows.map { it.periodStart })
                assertEquals(listOf("2020-11-30", "2020-12-31"), rows.map { it.periodEnd })
                assertEquals(listOf("2020-11-01", "2099-01-15"), rows.map { it.dueAt.take(10) })
                assertEquals(listOf("RUB", "RUB"), rows.map { it.currency })
                assertEquals(null, next)
                assertEquals(403, client.get(path) { bearerAuth(tokens.issueToken(7003).token) }.status.value)
                val invalid = client.get("$path?outstandingAfterId=-1") { bearerAuth(tokens.issueToken(actor).token) }
                assertEquals(400, invalid.status.value)
            }
            assertEquals(beforeRead, invoiceFingerprint())
            assertEquals(0L, ds.number("SELECT count(*) FROM billing_payments"))
            assertEquals(52L, ds.number("SELECT count(*) FROM billing_invoices WHERE venue_id=$venue"))
            val payment =
                client.post("/api/platform/invoices/$open/mark-paid") {
                    bearerAuth(tokens.issueToken(7001).token)
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(200, payment.status.value)
            for ((path, actor) in routes) assertEquals(listOf(overdue), page(path, actor).first.map { it.id })
            seed(53, "PAST_DUE", "2022-01-01")
            for ((path, actor) in routes) {
                val (first, next) = page(path, actor)
                assertEquals(50, first.size)
                assertEquals(overdue, first.first().id)
                assertEquals(first.last().id, next)
                val (last, end) = page(path, actor, assertNotNull(next))
                assertEquals(null, end)
                assertEquals(4, last.size)
                assertEquals(54, (first + last).distinctBy { it.id }.size)
                assertFalse((first + last).any { it.status != "PAST_DUE" })
            }
            ds.exec("UPDATE billing_invoices SET status='PAID' WHERE venue_id=$venue")
            for ((path, actor) in routes) {
                assertEquals(null, read(path, actor).payableInvoice)
                assertEquals(emptyList<OwnerBillingInvoiceDto>() to null, page(path, actor))
            }
        }

    @Test
    fun `venue and platform overview each keep one snapshot across a concurrent mark paid`() =
        resumeApiTest { ds, tokens ->
            ds.exec("INSERT INTO users(telegram_user_id) VALUES (7001),(7002)")
            val venue = ds.venue()
            ds.exec("INSERT INTO venue_members(venue_id,user_id,role) VALUES ($venue,7002,'OWNER')")
            ds.exec("INSERT INTO venue_subscriptions(venue_id,status) VALUES ($venue,'SUSPENDED_BY_PLATFORM')")
            val overdue = ds.invoice(venue, "2020-11-01", "2020-11-30", "PAST_DUE")
            val open = ds.invoice(venue, "2020-12-01", "2020-12-31", "OPEN")
            ds.exec("UPDATE billing_invoices SET amount_minor=5000 WHERE id=$overdue")
            ds.exec("UPDATE billing_invoices SET amount_minor=1000 WHERE id=$open")
            val owner = tokens.issueToken(7001).token
            val routes =
                mapOf(
                    "/api/platform/venues/$venue/billing" to owner,
                    "/api/venue/$venue/subscription" to tokens.issueToken(7002).token,
                )

            suspend fun read(path: String): OwnerBillingOverviewResponse {
                val response = client.get(path) { bearerAuth(routes.getValue(path)) }
                assertEquals(200, response.status.value, path)
                return Json.decodeFromString(response.bodyAsText())
            }

            val before = routes.keys.associateWith { read(it) }
            val waiting =
                """
                SELECT count(*) FROM pg_locks
                WHERE relation = 'venue_subscriptions'::regclass AND mode = 'AccessShareLock' AND NOT granted
                  AND pg_backend_pid() = ANY(pg_blocking_pids(pid))
                """.trimIndent()
            val overlap =
                coroutineScope {
                    ds.connection.use { barrier ->
                        barrier.autoCommit = false
                        barrier.createStatement().use { sql ->
                            fun blockedReaders(): Int =
                                sql.executeQuery(waiting).use { rows ->
                                    rows.next()
                                    rows.getInt(1)
                                }

                            // The overview reads outstanding invoices before it reads venue_subscriptions.
                            sql.execute("LOCK TABLE venue_subscriptions IN ACCESS EXCLUSIVE MODE")
                            val reads = routes.keys.associateWith { async(Dispatchers.IO) { read(it) } }
                            try {
                                withTimeout(20_000) {
                                    while (blockedReaders() != reads.size) delay(10)
                                    val payment =
                                        client.post("/api/platform/invoices/$open/mark-paid") {
                                            bearerAuth(owner)
                                            contentType(ContentType.Application.Json)
                                            setBody("{}")
                                        }
                                    assertEquals(200, payment.status.value)
                                }
                            } finally {
                                barrier.rollback()
                            }
                            reads.mapValues { it.value.await() }
                        }
                    }
                }

            for ((path, old) in overlap) {
                val items = assertNotNull(old.outstandingInvoices).items
                assertEquals(listOf(overdue, open), items.map { it.id }, path)
                assertEquals(listOf(5000, 1000), items.map { it.amountMinor }, path)
                assertEquals(listOf("PAST_DUE", "OPEN"), items.map { it.status }, path)
                assertEquals("OPEN", old.invoices.single { it.id == open }.status, path)
                assertEquals(open, old.payableInvoice?.id, path)
                assertEquals(null, old.basePaidThrough, path)
                assertEquals(before.getValue(path), old, path)

                val fresh = read(path)
                assertEquals("PAID", fresh.invoices.single { it.id == open }.status, path)
                assertEquals(listOf(overdue), assertNotNull(fresh.outstandingInvoices).items.map { it.id }, path)
                assertEquals(overdue, fresh.payableInvoice?.id, path)
                assertEquals("2020-12-31", fresh.basePaidThrough, path)
                assertEquals(old.subscriptionStatus, fresh.subscriptionStatus, path)
            }

            // Fail a later SELECT after the outstanding page was read in each GET.
            ds.exec("ALTER TABLE venue_subscriptions RENAME TO unavailable_subscriptions")
            try {
                for ((path, token) in routes) {
                    val response = client.get(path) { bearerAuth(token) }
                    assertEquals(503, response.status.value, path)
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    val error = body.getValue("error").jsonObject
                    assertEquals("DATABASE_UNAVAILABLE", error.getValue("code").jsonPrimitive.content)
                    assertFalse("outstandingInvoices" in body || "invoices" in body || "payableInvoice" in body)
                }
            } finally {
                ds.exec("ALTER TABLE unavailable_subscriptions RENAME TO venue_subscriptions")
            }
            for (path in routes.keys) assertEquals(overdue, read(path).payableInvoice?.id)
        }

    private fun resumeApiTest(block: suspend ApplicationTestBuilder.(DataSource, SessionTokenService) -> Unit) {
        val database = PostgresTestEnv.createDatabase()
        testApplication {
            val config = PostgresTestEnv.buildConfig(database)
            config.put("platform.ownerUserId", "7001")
            config.put("billing.subscription.intervalSeconds", "0")
            environment { this.config = config }
            application { module() }
            client.get("/health")
            val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))
            PostgresTestEnv.createDataSource(database, migrate = false).use { ds -> block(ds, tokens) }
        }
    }

    @Test
    fun `manual resume guards roles and guest access and rolls status back on audit failure`() =
        resumeApiTest { ds, tokens ->
            val owner = tokens.issueToken(7001).token
            ds.exec("INSERT INTO users(telegram_user_id) SELECT generate_series(7001,7005)")
            val venue = ds.venue()
            ds.exec("INSERT INTO venue_subscriptions(venue_id,status) VALUES ($venue,'SUSPENDED_BY_PLATFORM')")
            listOf("OWNER", "MANAGER", "STAFF").forEachIndexed { i, role ->
                ds.exec("INSERT INTO venue_members(venue_id,user_id,role) VALUES ($venue,${7003 + i},'$role')")
            }

            val observed = client.billingStamp(venue, owner)

            suspend fun resume(
                user: Long? = 7001,
                reason: String = "Проверка доступа",
            ) = client.post("/api/platform/venues/$venue/billing/resume-subscription") {
                user?.let { bearerAuth(tokens.issueToken(it).token) }
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"$reason","expectedUpdatedAt":"$observed"}""")
            }

            suspend fun guest() =
                client.get("/api/guest/venue/$venue") { bearerAuth(tokens.issueToken(7002).token) }.status.value

            suspend fun pay(id: Long) =
                client.post("/api/platform/invoices/$id/mark-paid") {
                    bearerAuth(owner)
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }.status.value

            val today = java.time.LocalDate.now(ZoneOffset.UTC)
            ds.exec(
                "INSERT INTO venue_subscription_settings(venue_id,base_price_minor,currency,updated_by_user_id)" +
                    " VALUES ($venue,0,'RUB',7001)",
            )
            val old = ds.invoice(venue, today.minusMonths(2).toString(), today.minusMonths(1).toString(), "OPEN")
            assertEquals(200, pay(old))
            assertEquals(404, guest())
            assertEquals(401, resume(null).status.value)
            for (user in 7002L..7005L) assertEquals(403, resume(user).status.value)
            assertEquals(400, resume(reason = "  ").status.value)
            assertEquals(409, resume().status.value)
            val inv = ds.invoice(venue, today.toString(), today.plusDays(6).toString(), "OPEN")
            assertEquals(200, pay(inv))
            for (user in 7002L..7005L) assertEquals(403, resume(user).status.value)
            assertEquals(404, guest())
            val debt = ds.invoice(venue, today.minusDays(12).toString(), today.minusDays(3).toString(), "PAST_DUE")

            fun financialRows(): List<String> =
                listOf("billing_invoices", "billing_payments", "billing_adjustments").map { table ->
                    ds.connection.use { c ->
                        c.createStatement().use { s ->
                            s.executeQuery(
                                "SELECT COALESCE(jsonb_agg(t ORDER BY id)::text,'[]') FROM $table t",
                            ).use {
                                it.next()
                                it.getString(1)
                            }
                        }
                    }
                }
            val money = financialRows()
            val action = "BILLING_SUBSCRIPTION_RESUMED"
            ds.exec("ALTER TABLE audit_log ADD CONSTRAINT reject_resume CHECK (action <> '$action')")
            assertEquals(503, resume().status.value)
            assertEquals(404, guest())
            assertEquals(0L, ds.number("SELECT count(*) FROM audit_log WHERE action='$action'"))
            ds.exec("ALTER TABLE audit_log DROP CONSTRAINT reject_resume")
            val codes = coroutineScope { List(3) { async { resume().status.value } }.awaitAll() }
            assertEquals(listOf(200, 409, 409), codes.sorted())
            assertEquals(409, resume().status.value)
            assertEquals(200, guest())
            assertEquals(1L, ds.number("SELECT count(*) FROM venue_subscriptions WHERE status='ACTIVE'"))
            assertEquals(1L, ds.number("SELECT count(*) FROM venues WHERE id=$venue AND status='PUBLISHED'"))
            assertEquals(
                1L,
                ds.number("SELECT count(*) FROM billing_invoices WHERE id=$debt AND status='PAST_DUE'"),
            )
            assertEquals(
                1L,
                ds.number(
                    """
                    SELECT count(*) FROM audit_log WHERE action='$action'
                      AND actor_user_id=7001 AND entity_id=$venue
                      AND payload_json::jsonb->>'reason'='Проверка доступа'
                      AND payload_json::jsonb->>'basis'='paid'
                    """.trimIndent(),
                ),
            )
            assertEquals(money, financialRows())
            // A delayed request from the old screen must also fail after a fresh suspension.
            SubscriptionRepository(ds).updateStatus(venue, SubscriptionStatus.SUSPENDED_BY_PLATFORM)
            assertEquals(409, resume().status.value)
            assertEquals(404, guest())
            assertEquals(1L, ds.number("SELECT count(*) FROM audit_log WHERE action='$action'"))
        }

    @Test
    fun `resume accepts a live trial or confirmed courtesy covering today only`() =
        resumeApiTest { ds, tokens ->
            val token = tokens.issueToken(7001).token
            ds.exec("INSERT INTO users(telegram_user_id) VALUES (7001)")
            val venue = ds.venue()
            ds.exec("UPDATE venues SET status='HIDDEN' WHERE id=$venue")
            ds.exec("INSERT INTO venue_subscriptions(venue_id,status) VALUES ($venue,'SUSPENDED_BY_PLATFORM')")

            val observed = client.billingStamp(venue, token)

            suspend fun resume() =
                client.post("/api/platform/venues/$venue/billing/resume-subscription") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"reason":"Разрешено владельцем","expectedUpdatedAt":"$observed"}""")
                }.status.value

            fun adjustment(
                start: Int,
                end: Int,
            ) = ds.number(
                "INSERT INTO billing_adjustments (venue_id,kind,days,reason," +
                    "previous_paid_through,new_paid_through," +
                    "actor_user_id) VALUES ($venue,'COURTESY_DAYS',${end - start},'test',CURRENT_DATE+($start)," +
                    "CURRENT_DATE+($end),7001) RETURNING id",
            )

            fun confirm(id: Long) =
                ds.exec(
                    "INSERT INTO audit_log(actor_user_id,action,entity_type,entity_id,payload_json) VALUES" +
                        " (7001,'BILLING_COURTESY_DAYS_ADDED','billing_adjustment',$id,'{}')",
                )
            ds.exec(
                "UPDATE venue_subscriptions SET trial_end=CURRENT_TIMESTAMP-interval '1 day' WHERE venue_id=$venue",
            )
            val today = java.time.LocalDate.now(ZoneOffset.UTC)
            val invoice = ds.invoice(venue, today.minusDays(4).toString(), today.plusDays(2).toString(), "PAID")
            ds.exec("UPDATE billing_invoices SET paid_at=CURRENT_TIMESTAMP AT TIME ZONE 'UTC' WHERE id=$invoice")
            assertEquals(409, resume())

            fun payment(
                status: String,
                amount: Int,
                event: String,
            ) = ds.exec(
                """
                INSERT INTO billing_payments(invoice_id,provider,provider_event_id,amount_minor,
                                             currency,status,occurred_at)
                VALUES ($invoice,'fake','$event',$amount,'RUB','$status',CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                """.trimIndent(),
            )
            payment("SUCCEEDED", 1, "short-payment")
            assertEquals(409, resume())
            payment("SUCCEEDED", 105, "paid")
            ds.connection.use { refund ->
                refund.autoCommit = false
                val pid =
                    refund.createStatement().use { statement ->
                        statement.executeUpdate(
                            """
                            INSERT INTO billing_payments(invoice_id,provider,provider_event_id,
                                                         amount_minor,currency,status,occurred_at)
                            VALUES ($invoice,'fake','refund',105,'RUB','REFUNDED',now())
                            """.trimIndent(),
                        )
                        statement.executeQuery("SELECT pg_backend_pid()").use {
                            it.next()
                            it.getLong(1)
                        }
                    }
                coroutineScope {
                    val request = async { resume() }
                    try {
                        withContext(Dispatchers.IO) {
                            withTimeout(10_000) {
                                val waiting =
                                    "SELECT count(*) FROM pg_stat_activity WHERE $pid=ANY(pg_blocking_pids(pid))"
                                while (ds.number(waiting) == 0L) delay(20)
                            }
                        }
                    } finally {
                        refund.commit()
                    }
                    assertEquals(409, request.await())
                }
            }
            confirm(adjustment(-8, -2))
            confirm(adjustment(0, 4))
            val current = adjustment(-1, 0)
            assertEquals(409, resume())
            confirm(current)
            assertEquals(200, resume())
            assertEquals(1L, ds.number("SELECT count(*) FROM venue_subscriptions WHERE status='ACTIVE'"))
            // A separate venue has a valid trial and no courtesy or payment.
            val trial = ds.venue()
            ds.exec(
                "INSERT INTO venue_subscriptions(venue_id,status,trial_end) VALUES" +
                    " ($trial,'SUSPENDED_BY_PLATFORM',CURRENT_TIMESTAMP+interval '1 day')",
            )
            val trialStamp = client.billingStamp(trial, token)
            val response =
                client.post("/api/platform/venues/$trial/billing/resume-subscription") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"reason":"Испытательный период","expectedUpdatedAt":"$trialStamp"}""")
                }
            assertEquals(200, response.status.value)
            assertEquals(
                1L,
                ds.number("SELECT count(*) FROM venue_subscriptions WHERE venue_id=$trial AND status='TRIAL'"),
            )
            assertEquals(1L, ds.number("SELECT count(*) FROM venues WHERE id=$venue AND status='HIDDEN'"))
            assertEquals(3L, ds.number("SELECT count(*) FROM billing_adjustments"))
        }
}
