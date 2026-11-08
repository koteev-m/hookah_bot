package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.api.SeatingConflictException
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.VisitRepository
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingRepository
import com.hookah.platform.backend.miniapp.venue.accounting.command
import com.hookah.platform.backend.miniapp.venue.accounting.manual
import com.hookah.platform.backend.miniapp.venue.orders.OrderActionActor
import com.hookah.platform.backend.miniapp.venue.orders.OrderStatusUpdateResult
import com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.miniapp.venue.tables.VenueTableRepository
import com.hookah.platform.backend.telegram.db.CreatedOrderBatch
import com.hookah.platform.backend.telegram.db.OrderBatchItemInput
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeatingCheckoutConcurrencyPostgresTest {
    @Test
    fun `Bot checkout and Hall release or move validate the committed context in both orders`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 10
                for (action in listOf("RELEASE", "MOVE")) for (hallFirst in listOf(true, false)) {
                    for (connected in listOf(false, true)) {
                        val s = checkoutSeat(ds)
                        val key = UUID.randomUUID().toString()
                        val closedShift =
                            ds.connection.use { c ->
                                c.insert(
                                    "INSERT INTO venue_accounting_shifts(venue_id,status,timezone,opened_at," +
                                        "closed_at,final_report) VALUES (?,'CLOSED','Europe/Moscow',?,?, " +
                                        "'unchanged-debt-123')",
                                    s.venue,
                                    seatingClock.instant(),
                                    seatingClock.instant(),
                                )
                            }
                        val financialBefore = fullRow(ds, "venue_accounting_shifts", closedShift)
                        val effects = checkoutCounts(ds)

                        suspend fun operation(
                            hall: Boolean,
                            source: DataSource,
                        ): Any? =
                            if (hall) {
                                SeatingRepository(source, clock = seatingClock).command(
                                    s.venue,
                                    STAFF,
                                    cmd(action).copy(id = s.seat, revision = 0, tableId = s.tables[1]),
                                )
                            } else {
                                checkout(source, s, key, connected)
                            }
                        val results =
                            controlledRace(
                                ds,
                                "$action hallFirst=$hallFirst connected=$connected",
                                { it.startsWith("SELECT id FROM venues") },
                                { operation(hallFirst, it) },
                                { operation(!hallFirst, it) },
                            )
                        val bot = results[if (hallFirst) 1 else 0]
                        val hall = results[if (hallFirst) 0 else 1]
                        assertTrue(bot.isSuccess, results.toString())
                        if (hallFirst) {
                            assertTrue(hall.isSuccess, results.toString())
                            assertNull(bot.getOrThrow())
                            assertEquals(effects["orders"], checkoutCounts(ds)["orders"])
                            assertEquals(effects["order_batches"], checkoutCounts(ds)["order_batches"])
                            assertEquals(
                                effects["guest_batch_idempotency"],
                                checkoutCounts(ds)["guest_batch_idempotency"],
                            )
                        } else {
                            val created = assertNotNull(bot.getOrThrow() as CreatedOrderBatch?)
                            if (action == "RELEASE") {
                                assertTrue(hall.exceptionOrNull() is SeatingConflictException)
                            } else {
                                assertTrue(hall.isSuccess, results.toString())
                            }
                            val order = fullRow(ds, "orders", created.orderId)
                            assertEquals(s.tables[if (action == "MOVE") 1 else 0].toString(), order["table_id"])
                            assertEquals(s.session.toString(), order["table_session_id"])
                            assertEquals(s.tab.toString(), fullRow(ds, "order_batches", created.batchId)["tab_id"])
                            assertEquals(effects.getValue("orders") + 1, checkoutCounts(ds)["orders"])
                            assertEquals(effects.getValue("order_batches") + 1, checkoutCounts(ds)["order_batches"])
                            if (action == "RELEASE") {
                                val replay = assertNotNull(checkout(ds, s, key, connected))
                                assertTrue(replay.idempotencyReplay)
                                assertEquals(created.batchId, replay.batchId)
                            }
                        }
                        val released = hallFirst && action == "RELEASE"
                        assertEquals(
                            if (released) "ENDED" else "ACTIVE",
                            fullRow(ds, "table_sessions", s.session)["status"],
                        )
                        assertEquals(if (released) "CLOSED" else "ACTIVE", fullRow(ds, "tab", s.tab)["status"])
                        val state = SeatingRepository(ds, clock = seatingClock).state(s.venue, STAFF)
                        assertEquals(if (released || action == "MOVE") "FREE" else "OCCUPIED", state.tables[0].state)
                        assertEquals(financialBefore, fullRow(ds, "venue_accounting_shifts", closedShift))
                        assertEquals(effects["telegram_outbox"], checkoutCounts(ds)["telegram_outbox"])
                        assertEquals(
                            effects.getValue("audit_log") + (if (hall.isSuccess) 1 else 0) + (if (hallFirst) 0 else 1),
                            checkoutCounts(ds)["audit_log"],
                        )
                        assertCheckoutOpening(ds, s.venue, expected = if (hallFirst) 0 else 1)
                        println(
                            "P1P2_FINAL $action hallFirst=$hallFirst connected=$connected " +
                                "session=${fullRow(ds, "table_sessions", s.session)} " +
                                "tab=${fullRow(ds, "tab", s.tab)} " +
                                "effects=${checkoutCounts(ds)}",
                        )
                    }
                }
            }
        }

    @Test
    fun `Bot and Mini App checkout share locks without mixing companies and other venue progresses`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 10
                for (botFirst in listOf(true, false)) for (sameCompany in listOf(true, false)) {
                    val first = checkoutSeat(ds)
                    val second =
                        if (sameCompany) {
                            first
                        } else {
                            val r = SeatingRepository(ds, clock = seatingClock)
                            val seat =
                                r.command(
                                    first.venue,
                                    STAFF,
                                    cmd("SEAT").copy(tableId = first.tables[1], partySize = 2),
                                )
                            val session = r.state(first.venue, STAFF).tables[1].seating!!.tableSessionId
                            val tab = GuestTabsRepository(ds).ensurePersonalTab(first.venue, session, 1003).id
                            val token =
                                VenueTableRepository(ds).listTablesWithTokens(first.venue)
                                    .single { it.tableId == first.tables[1] }.token!!
                            first.copy(
                                tables = listOf(first.tables[1], first.tables[0]),
                                seat = seat.id,
                                session = session,
                                tab = tab,
                                token = token,
                            )
                        }
                    val other = checkoutSeat(ds)
                    val item =
                        ds.connection.use { c ->
                            val category =
                                c.insert(
                                    "INSERT INTO menu_categories(venue_id,name,sort_order) VALUES (?,'Test',0)",
                                    second.venue,
                                )
                            c.insert(
                                "INSERT INTO menu_items(venue_id,category_id,name,price_minor) VALUES (?,?,'Test',100)",
                                second.venue,
                                category,
                            )
                        }
                    val items = listOf(OrderBatchItemInput(itemId = item, qty = 1))

                    suspend fun operation(
                        bot: Boolean,
                        source: DataSource,
                    ): Any? =
                        checkout(
                            source,
                            if (bot) first else second,
                            UUID.randomUUID().toString(),
                            connected = !bot,
                            items = if (bot) emptyList() else items,
                        )
                    val result =
                        controlledRace(
                            ds,
                            "mixed botFirst=$botFirst sameCompany=$sameCompany",
                            { it.startsWith("SELECT id FROM venues") },
                            { operation(botFirst, it) },
                            { operation(!botFirst, it) },
                        ) {
                            withTimeout(5000) { assertNotNull(checkout(ds, other, UUID.randomUUID().toString())) }
                        }
                    assertTrue(result.all { it.isSuccess && it.getOrNull() != null }, result.toString())
                    val one = result[0].getOrThrow() as CreatedOrderBatch
                    val two = result[1].getOrThrow() as CreatedOrderBatch
                    if (sameCompany) {
                        assertEquals(
                            one.orderId,
                            two.orderId,
                        )
                    } else {
                        assertNotEquals(one.orderId, two.orderId)
                    }
                    assertNotEquals(one.batchId, two.batchId)
                    val bot = result[if (botFirst) 0 else 1].getOrThrow() as CreatedOrderBatch
                    val miniapp = result[if (botFirst) 1 else 0].getOrThrow() as CreatedOrderBatch
                    assertEquals(first.tab.toString(), fullRow(ds, "order_batches", bot.batchId)["tab_id"])
                    assertEquals(second.tab.toString(), fullRow(ds, "order_batches", miniapp.batchId)["tab_id"])
                    assertCheckoutOpening(ds, first.venue)
                    assertCheckoutOpening(ds, other.venue)
                    ds.connection.use { c ->
                        assertEquals(
                            0,
                            c.rows(
                                "SELECT COUNT(*) FROM orders o JOIN table_sessions s " +
                                    "ON s.id=o.table_session_id WHERE o.table_id<>s.table_id OR o.venue_id<>s.venue_id",
                            ) {
                                it.getInt(1)
                            }.single(),
                        )
                    }
                }
            }
        }

    @Test
    fun `concurrent Bot retries commit one batch and one idempotency record`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 8
                val s = checkoutSeat(ds)
                val key = UUID.randomUUID().toString()
                val before = checkoutCounts(ds)
                val results =
                    controlledRace(
                        ds,
                        "same Bot request",
                        { it.startsWith("SELECT id FROM venues") },
                        { checkout(it, s, key) },
                        { checkout(it, s, key, connected = true) },
                    )
                assertTrue(results.all { it.isSuccess })
                val created = results.map { it.getOrThrow() as CreatedOrderBatch }
                assertEquals(created[0].batchId, created[1].batchId)
                assertTrue(created[1].idempotencyReplay)
                val after = checkoutCounts(ds)
                for (table in listOf("orders", "order_batches", "guest_batch_idempotency")) {
                    assertEquals(before.getValue(table) + 1, after[table])
                }
                assertEquals(before.getValue("audit_log") + 1, after["audit_log"])
                assertCheckoutOpening(ds, s.venue)
                assertEquals(before["telegram_outbox"], after["telegram_outbox"])
            }
        }

    @Test
    fun `first sale versus SEAT or session release preserves closed debt and financial snapshot in both orders`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 10
                for (action in listOf("SEAT", "RELEASE")) for (saleFirst in listOf(false, true)) {
                    val s = checkoutSeat(ds)
                    val accounting = AccountingRepository(ds, clock = seatingClock)
                    val debt = accounting.execute(s.venue, STAFF, manual(0).copy(shiftId = null, tableId = s.tables[0]))
                    val frozen =
                        accounting.execute(
                            s.venue,
                            OWNER,
                            command(
                                "CLOSE",
                                debt.shiftId,
                            ).copy(expectedReportHash = debt.reportHash, acknowledgeUnpaid = true),
                        )
                    val financialRow = fullRow(ds, "venue_accounting_shifts", frozen.shiftId)
                    val before = checkoutCounts(ds)
                    val sale = manual(0).copy(shiftId = null, tableId = s.tables[0])
                    val hallCommand =
                        if (action == "SEAT") {
                            cmd(action).copy(tableId = s.tables[1], partySize = 2)
                        } else {
                            cmd(action).copy(id = s.seat, revision = 0)
                        }

                    suspend fun operation(
                        sell: Boolean,
                        source: DataSource,
                    ): Any? =
                        if (sell) {
                            AccountingRepository(source, clock = seatingClock).execute(s.venue, STAFF, sale)
                        } else {
                            SeatingRepository(source, clock = seatingClock).command(s.venue, STAFF, hallCommand)
                        }
                    val results =
                        controlledRace(
                            ds,
                            "auto-open $action saleFirst=$saleFirst",
                            { it.startsWith("SELECT id FROM venues") },
                            { operation(saleFirst, it) },
                            { operation(!saleFirst, it) },
                        )
                    assertTrue(results.all { it.isSuccess }, results.toString())
                    val current = assertNotNull(accounting.state(s.venue, STAFF).current)
                    assertEquals(current, accounting.execute(s.venue, STAFF, sale))
                    assertNotEquals(frozen.shiftId, current.shiftId)
                    assertEquals(10000, current.totals.unpaidMinor)
                    assertEquals(0, current.totals.paidMinor)
                    assertEquals(10000, frozen.totals.unpaidMinor)
                    assertEquals(frozen, accounting.detail(s.venue, OWNER, frozen.shiftId))
                    assertEquals(financialRow, fullRow(ds, "venue_accounting_shifts", frozen.shiftId))
                    val after = checkoutCounts(ds)
                    assertEquals(before.getValue("venue_accounting_shifts") + 1, after["venue_accounting_shifts"])
                    assertEquals(before.getValue("venue_accounting_events") + 2, after["venue_accounting_events"])
                    assertEquals(before.getValue("audit_log") + 3, after["audit_log"])
                    assertEquals(before["orders"], after["orders"])
                    assertEquals(before["order_batches"], after["order_batches"])
                    assertEquals(before["telegram_outbox"], after["telegram_outbox"])
                    assertEquals(
                        if (action == "RELEASE") "ENDED" else "ACTIVE",
                        fullRow(ds, "table_sessions", s.session)["status"],
                    )
                    ds.connection.use { c ->
                        assertEquals(
                            1,
                            c.rows(
                                "SELECT COUNT(*) FROM venue_accounting_sales WHERE shift_id=?",
                                current.shiftId,
                            ) { it.getInt(1) }.single(),
                        )
                        assertEquals(
                            listOf(STAFF),
                            c.rows(
                                "SELECT actor_user_id FROM venue_accounting_events " +
                                    "WHERE shift_id=? AND action='AUTO_OPEN'",
                                current.shiftId,
                            ) { it.getLong(1) },
                        )
                    }
                }
            }
        }

    @Test
    fun `auto-open adoption and legacy order closing serialize without deadlock or changing closed history`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 8
                for (saleFirst in listOf(false, true)) {
                    val s = checkoutSeat(ds)
                    val order =
                        assertNotNull(
                            OrdersRepository(ds, clock = seatingClock)
                                .getOrCreateActiveOrderId(s.tables[0], s.venue, s.session),
                        )
                    ds.connection.use { c ->
                        val category =
                            c.insert(
                                "INSERT INTO menu_categories(venue_id,name,sort_order) VALUES (?,'Legacy',0)",
                                s.venue,
                            )
                        val item =
                            c.insert(
                                "INSERT INTO menu_items(venue_id,category_id,name,price_minor) " +
                                    "VALUES (?,?,'Legacy tea',100)",
                                s.venue,
                                category,
                            )
                        val batch =
                            c.insert(
                                "INSERT INTO order_batches(order_id,tab_id,author_user_id,source,status) " +
                                    "VALUES (?,?,1003,'MINIAPP','DELIVERED')",
                                order,
                                s.tab,
                            )
                        c.write(
                            "INSERT INTO order_batch_items(order_batch_id,menu_item_id,qty,item_name_snapshot," +
                                "base_unit_price_minor_snapshot,currency_snapshot) " +
                                "VALUES (?,?,1,'Legacy tea',100,'RUB')",
                            batch,
                            item,
                        )
                    }
                    assertCheckoutOpening(ds, s.venue, expected = 0)
                    val sale = manual(0).copy(shiftId = null, tableId = s.tables[0])
                    val before = checkoutCounts(ds)

                    suspend fun operation(
                        sell: Boolean,
                        source: DataSource,
                    ): Any? =
                        if (sell) {
                            AccountingRepository(source, clock = seatingClock).execute(s.venue, STAFF, sale)
                        } else {
                            VenueOrdersRepository(source, visitRepository = VisitRepository(ds)).updateOrderStatus(
                                s.venue,
                                order,
                                OrderWorkflowStatus.CLOSED,
                                OrderActionActor(STAFF, VenueRole.STAFF),
                            )
                        }
                    val results =
                        controlledRace(
                            ds,
                            "auto-open order close saleFirst=$saleFirst",
                            { it.startsWith("SELECT id FROM venues") },
                            { operation(saleFirst, it) },
                            { operation(!saleFirst, it) },
                        )
                    assertTrue(results.all { it.isSuccess }, results.toString())
                    assertTrue((results[if (saleFirst) 1 else 0].getOrThrow() as OrderStatusUpdateResult).applied)
                    val current =
                        assertNotNull(AccountingRepository(ds, clock = seatingClock).state(s.venue, STAFF).current)
                    val closed = fullRow(ds, "orders", order)
                    assertEquals("CLOSED", closed["status"])
                    assertEquals(if (saleFirst) current.shiftId.toString() else null, closed["accounting_shift_id"])
                    assertEquals(if (saleFirst) 10100 else 10000, current.totals.unpaidMinor)
                    assertEquals(0, current.totals.paidMinor)
                    assertEquals(1, current.sales.count { it.source == "MANUAL" })
                    val after = checkoutCounts(ds)
                    assertEquals(before["orders"], after["orders"])
                    assertEquals(before["order_batches"], after["order_batches"])
                    assertEquals(before.getValue("venue_accounting_shifts") + 1, after["venue_accounting_shifts"])
                    assertEquals(before.getValue("venue_accounting_events") + 2, after["venue_accounting_events"])
                    assertEquals(before.getValue("audit_log") + 2, after["audit_log"])
                    ds.connection.use { c ->
                        assertEquals(
                            1,
                            c.rows("SELECT COUNT(*) FROM order_audit_log WHERE order_id=?", order) {
                                it.getInt(1)
                            }.single(),
                        )
                        assertEquals(
                            1,
                            c.rows("SELECT COUNT(*) FROM visits WHERE order_id=? AND user_id=1003", order) {
                                it.getInt(1)
                            }.single(),
                        )
                    }
                }
            }
        }

    private suspend fun controlledRace(
        ds: HikariDataSource,
        label: String,
        afterSql: (String) -> Boolean,
        first: suspend (DataSource) -> Any?,
        second: suspend (DataSource) -> Any?,
        during: suspend () -> Unit = {},
    ): List<Result<Any?>> =
        coroutineScope {
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val firstPid = AtomicInteger()
            val secondPid = AtomicInteger()
            val once = AtomicBoolean()

            fun observed(
                pid: AtomicInteger,
                holding: Boolean,
            ): DataSource =
                object : DataSource by ds {
                    override fun getConnection(): Connection {
                        val real = ds.connection
                        real.createStatement().use { it.execute("SET statement_timeout = '20s'") }
                        pid.set(real.rows("SELECT pg_backend_pid()") { it.getInt(1) }.single())
                        if (!holding) entered.countDown()
                        return Proxy.newProxyInstance(
                            Connection::class.java.classLoader,
                            arrayOf(Connection::class.java),
                        ) { _, method, args ->
                            try {
                                val value = method.invoke(real, *(args ?: emptyArray()))
                                if (holding && method.name == "prepareStatement" &&
                                    args?.firstOrNull() is String && value is PreparedStatement
                                ) {
                                    val sql = (args[0] as String).replace(Regex("\\s+"), " ").trim()
                                    Proxy.newProxyInstance(
                                        PreparedStatement::class.java.classLoader,
                                        arrayOf(PreparedStatement::class.java),
                                    ) { _, pm, pa ->
                                        try {
                                            val answer = pm.invoke(value, *(pa ?: emptyArray()))
                                            if (pm.name == "executeQuery" && afterSql(sql) &&
                                                once.compareAndSet(false, true)
                                            ) {
                                                held.countDown()
                                                check(release.await(20, TimeUnit.SECONDS))
                                            }
                                            answer
                                        } catch (e: InvocationTargetException) {
                                            throw e.targetException
                                        }
                                    }
                                } else {
                                    value
                                }
                            } catch (e: InvocationTargetException) {
                                throw e.targetException
                            }
                        } as Connection
                    }
                }
            val one = async(Dispatchers.IO) { runCatching { first(observed(firstPid, true)) } }
            assertTrue(held.await(20, TimeUnit.SECONDS), "First production lock: $label")
            val two = async(Dispatchers.IO) { runCatching { second(observed(secondPid, false)) } }
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                assertNotEquals(firstPid.get(), secondPid.get())
                val waitingQuery =
                    withTimeout(8000) {
                        var query: String? = null
                        while (query == null) {
                            query =
                                ds.connection.use {
                                        c ->
                                    c.rows(
                                        "SELECT query FROM pg_stat_activity WHERE pid = ? " +
                                            "AND ? = ANY(pg_blocking_pids(pid))",
                                        secondPid.get(), firstPid.get(),
                                    ) {
                                        it.getString(1)
                                    }.singleOrNull()
                                }
                            if (query == null) delay(10)
                        }
                        query
                    }
                assertTrue(waitingQuery.contains("FROM venues"), waitingQuery)
                assertTrue(waitingQuery.contains("FOR NO KEY UPDATE"), waitingQuery)
                during()
                println(
                    "P1P2_LOCK $label firstPid=${firstPid.get()} secondPid=${secondPid.get()} " +
                        "blockedOn=$waitingQuery",
                )
            } finally {
                release.countDown()
            }
            listOf(one.await(), two.await()).also { println("P1P2_RESULTS $label $it") }
        }
}
