package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.api.SeatingConflictException
import com.hookah.platform.backend.miniapp.guest.db.BookingStatus
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.guest.db.VisitRepository
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.orders.OrderActionActor
import com.hookah.platform.backend.miniapp.venue.orders.OrderStatusUpdateResult
import com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.TelegramTrafficPolicy
import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
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
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeatingReviewFixPostgresTest {
    @Test
    fun `MOVE and RELEASE versus closing use venue first with independent other venue progress`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 10
                for (action in listOf("MOVE", "RELEASE")) for (closeFirst in listOf(true, false)) {
                    val (venue, tables) = seedSeating(ds)
                    val (otherVenue, otherTables) = seedSeating(ds)
                    val repo = SeatingRepository(ds, clock = seatingClock)
                    val seat = repo.command(venue, STAFF, cmd("SEAT").copy(tableId = tables[0], partySize = 2))
                    val session = repo.state(venue, STAFF).tables[0].seating!!.tableSessionId
                    val (order, tab, shift) =
                        ds.connection.use { c ->
                            val shift =
                                c.insert(
                                    "INSERT INTO venue_accounting_shifts(venue_id,status," +
                                        "open_slot,timezone,opened_at) " +
                                        "VALUES (?,'OPEN',1,'Europe/Moscow',?)",
                                    venue,
                                    seatingClock.instant(),
                                )
                            val tab =
                                c.insert(
                                    "INSERT INTO tab(venue_id,table_session_id,type,owner_user_id) " +
                                        "VALUES (?,?,'PERSONAL',1003)",
                                    venue,
                                    session,
                                )
                            val category =
                                c.insert(
                                    "INSERT INTO menu_categories(venue_id,name,sort_order) " +
                                        "VALUES (?,'Synthetic',0)",
                                    venue,
                                )
                            val item =
                                c.insert(
                                    "INSERT INTO menu_items(venue_id,category_id,name,price_minor) " +
                                        "VALUES (?,?,'Synthetic',100)",
                                    venue,
                                    category,
                                )
                            val order =
                                c.insert(
                                    "INSERT INTO orders(venue_id,table_id,table_session_id,status," +
                                        "accounting_shift_id) " +
                                        "VALUES (?,?,?,'ACTIVE',?)",
                                    venue,
                                    tables[0],
                                    session,
                                    shift,
                                )
                            val batch =
                                c.insert(
                                    "INSERT INTO order_batches(order_id,author_user_id,source,status) " +
                                        "VALUES (?,1003,'MINIAPP','DELIVERED')",
                                    order,
                                )
                            c.write(
                                "INSERT INTO order_batch_items(order_batch_id,menu_item_id,qty) " +
                                    "VALUES (?,?,1)",
                                batch,
                                item,
                            )
                            Triple(order, tab, shift)
                        }
                    val initialShift = fullRow(ds, "venue_accounting_shifts", shift)
                    val initialTab = fullRow(ds, "tab", tab)
                    val mutation =
                        cmd(
                            action,
                        ).copy(id = seat.id, revision = 0, tableId = if (action == "MOVE") tables[1] else null)

                    suspend fun operate(
                        close: Boolean,
                        source: DataSource,
                    ): Any? =
                        if (close) {
                            VenueOrdersRepository(
                                source,
                                visitRepository = VisitRepository(ds),
                            ).updateOrderStatus(
                                venue,
                                order,
                                OrderWorkflowStatus.CLOSED,
                                OrderActionActor(STAFF, VenueRole.STAFF),
                            )
                        } else {
                            SeatingRepository(source, clock = seatingClock).command(venue, STAFF, mutation)
                        }
                    val results =
                        controlledRace(ds, "R1 $action closeFirst=$closeFirst", { sql ->
                            if (closeFirst) {
                                sql.startsWith(
                                    "SELECT status, updated_at FROM orders",
                                )
                            } else {
                                sql.contains("FROM orders WHERE table_session_id")
                            }
                        }, { operate(closeFirst, it) }, { operate(!closeFirst, it) }) {
                            withTimeout(
                                5000,
                            ) {
                                repo.command(
                                    otherVenue,
                                    STAFF,
                                    cmd("SEAT").copy(tableId = otherTables[0], partySize = 2),
                                )
                            }
                        }
                    val close = results[if (closeFirst) 0 else 1]
                    val hall = results[if (closeFirst) 1 else 0]
                    assertTrue(close.isSuccess, close.toString())
                    assertTrue((close.getOrThrow() as OrderStatusUpdateResult).applied)
                    if (action == "RELEASE" && !closeFirst) {
                        assertTrue(hall.exceptionOrNull() is SeatingConflictException, hall.toString())
                        assertNull(fullRow(ds, "table_seatings", seat.id)["released_at"])
                        assertEquals(initialTab, fullRow(ds, "tab", tab))
                        repo.command(venue, STAFF, mutation) // New valid state, not a deadlock retry.
                    } else {
                        assertTrue(hall.isSuccess, hall.toString())
                    }
                    assertEquals(initialShift, fullRow(ds, "venue_accounting_shifts", shift))
                    val finalOrder = fullRow(ds, "orders", order)
                    assertEquals("CLOSED", finalOrder["status"])
                    assertEquals(shift.toString(), finalOrder["accounting_shift_id"])
                    assertEquals(session.toString(), finalOrder["table_session_id"])
                    assertEquals(
                        (if (action == "MOVE" && !closeFirst) tables[1] else tables[0]).toString(),
                        finalOrder["table_id"],
                    )
                    assertEquals(session.toString(), fullRow(ds, "tab", tab)["table_session_id"])
                    assertEquals(
                        if (action == "MOVE") "ACTIVE" else "ENDED",
                        fullRow(ds, "table_sessions", session)["status"],
                    )
                    assertEquals(if (action == "MOVE") "ACTIVE" else "CLOSED", fullRow(ds, "tab", tab)["status"])
                    ds.connection.use { c ->
                        assertEquals(
                            1,
                            c.rows(
                                "SELECT COUNT(*) FROM orders WHERE table_session_id = ?",
                                session,
                            ) { it.getInt(1) }.single(),
                        )
                        assertEquals(
                            1,
                            c.rows("SELECT COUNT(*) FROM visits WHERE order_id = ? AND user_id = 1003", order) {
                                it.getInt(1)
                            }.single(),
                        )
                        assertEquals(
                            1,
                            c.rows(
                                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                                venue,
                                "SEATING_$action",
                            ) {
                                it.getInt(1)
                            }.single(),
                        )
                        for (table in listOf(
                            "venue_accounting_sales",
                            "venue_accounting_events",
                            "billing_payments",
                        )) assertEquals(
                            0,
                            c.rows("SELECT COUNT(*) FROM $table") {
                                it.getInt(1)
                            }.single(),
                        )
                    }
                    val effects = sideEffectCounts(ds)
                    assertFalse(
                        VenueOrdersRepository(
                            ds,
                            visitRepository = VisitRepository(ds),
                        ).updateOrderStatus(
                            venue,
                            order,
                            OrderWorkflowStatus.CLOSED,
                            OrderActionActor(STAFF, VenueRole.STAFF),
                        )!!.applied,
                    )
                    repo.command(venue, STAFF, mutation)
                    assertEquals(effects, sideEffectCounts(ds))
                    println(
                        "FIX02_FINAL R1 action=$action closeFirst=$closeFirst order=$finalOrder seat=${fullRow(
                            ds,
                            "table_seatings",
                            seat.id,
                        )} session=${fullRow(ds, "table_sessions", session)} tab=${fullRow(ds, "tab", tab)}",
                    )
                }
            }
        }

    @Test
    fun `SEAT versus manual or guest UPDATE and CANCEL serialize both orders`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 8
                for (guest in listOf(
                    false,
                    true,
                )) for (cancel in listOf(false, true)) for (seatFirst in listOf(false, true)) {
                    val (venue, tables) = seedSeating(ds)
                    val repo = SeatingRepository(ds, clock = seatingClock)
                    val b =
                        if (guest) {
                            prepareGuestChoice(ds, venue, tables)
                            repo.guestBooking(
                                venue,
                                1003,
                                cmd("GUEST_BOOKING").copy(
                                    tableId = tables[0],
                                    partySize = 2,
                                    localStart = "2030-01-07T20:00",
                                ),
                            ).also {
                                GuestBookingRepository(
                                    ds,
                                ).updateByVenue(it.id, venue, BookingStatus.CONFIRMED, actorUserId = OWNER)
                            }
                        } else {
                            repo.command(venue, STAFF, offline(tables[0]))
                        }
                    val rev = if (guest) 1L else 0L
                    val seat = cmd("SEAT").copy(bookingId = b.id, revision = rev)
                    val update =
                        cmd("ASSIGN_BOOKING").copy(
                            id = b.id,
                            revision = rev,
                            tableId = tables[0],
                            localEnd = "2030-01-07T23:00",
                        )

                    suspend fun operate(
                        seating: Boolean,
                        source: DataSource,
                    ): Any? =
                        when {
                            seating -> SeatingRepository(source, clock = fixClock(20)).command(venue, STAFF, seat)
                            cancel && guest -> GuestBookingRepository(source).cancelByGuest(b.id, venue, 1003)
                            cancel ->
                                GuestBookingRepository(
                                    source,
                                ).updateByVenue(b.id, venue, BookingStatus.CANCELED, actorUserId = OWNER)
                            guest ->
                                GuestBookingRepository(source).updateByGuest(
                                    b.id,
                                    venue,
                                    1003,
                                    fixClock(21).instant(),
                                    3,
                                    "Synthetic",
                                    ZoneId.of("Europe/Moscow"),
                                )
                            else -> SeatingRepository(source, clock = seatingClock).command(venue, OWNER, update)
                        }
                    val results =
                        controlledRace(ds, "R3 guest=$guest cancel=$cancel seatFirst=$seatFirst", {
                            it.startsWith("SELECT id FROM venues")
                        }, { operate(seatFirst, it) }, { operate(!seatFirst, it) })
                    assertTrue(results[0].isSuccess, results.toString())
                    val final = fullRow(ds, "bookings", b.id)
                    if (seatFirst) {
                        if (!guest && !cancel) {
                            assertTrue(results[1].exceptionOrNull() is SeatingConflictException)
                        } else {
                            assertTrue(results[1].isSuccess)
                            assertNull(results[1].getOrThrow())
                        }
                        assertEquals("SEATED", final["status"])
                        assertNotNull(repo.state(venue, STAFF).tables[0].seating)
                    } else {
                        assertTrue(results[1].exceptionOrNull() is SeatingConflictException, results.toString())
                        assertNull(repo.state(venue, STAFF).tables[0].seating)
                        assertEquals(
                            if (cancel) {
                                "CANCELED"
                            } else if (guest) {
                                "PENDING"
                            } else {
                                "CONFIRMED"
                            },
                            final["status"],
                        )
                    }
                    println("FIX02_FINAL R3 guest=$guest cancel=$cancel seatFirst=$seatFirst booking=$final")
                }
            }
        }

    @Test
    fun `concurrent time changes queue only the winning guest notification`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 8
                for (firstTime in listOf(21, 22)) {
                    val (venue, tables) = seedSeating(ds)
                    prepareGuestChoice(ds, venue, tables)
                    val repo = SeatingRepository(ds, clock = seatingClock)
                    val b =
                        repo.guestBooking(
                            venue,
                            1003,
                            cmd(
                                "GUEST_BOOKING",
                            ).copy(tableId = tables[0], partySize = 2, localStart = "2030-01-07T20:00"),
                        )
                    GuestBookingRepository(ds).updateByVenue(b.id, venue, BookingStatus.CONFIRMED, actorUserId = OWNER)
                    val policy = TelegramTrafficPolicy.product()
                    val outbox = TelegramOutboxEnqueuer(TelegramOutboxRepository(ds, policy), seatingJson, policy)
                    val first =
                        cmd(
                            "ASSIGN_BOOKING",
                        ).copy(id = b.id, revision = 1, tableId = tables[0], localStart = "2030-01-07T$firstTime:00")
                    val second =
                        first.copy(
                            requestId = cmd("ASSIGN_BOOKING").requestId,
                            localStart = "2030-01-07T${43 - firstTime}:00",
                        )

                    suspend fun operate(
                        source: DataSource,
                        command: SeatingCommand,
                    ) = SeatingRepository(source, clock = seatingClock).command(venue, OWNER, command) { c, _ ->
                        outbox.enqueueBookingSendMessageInTransaction(
                            c,
                            1003,
                            "Synthetic ${command.localStart}",
                            dedupeKey = command.requestId,
                        )
                    }
                    val before = sideEffectCounts(ds)
                    val results =
                        controlledRace(ds, "R4 firstTime=$firstTime", {
                            it.startsWith("SELECT id FROM venues")
                        }, { operate(it, first) }, { operate(it, second) })
                    assertTrue(results[0].isSuccess)
                    assertTrue(results[1].exceptionOrNull() is SeatingConflictException)
                    assertEquals(results[0].getOrThrow(), operate(ds, first))
                    assertEquals(before[1] + 1, sideEffectCounts(ds)[1])
                    assertEquals(
                        fixClock(firstTime).instant().toString(),
                        repo.state(venue, STAFF).bookings.single().scheduledAt,
                    )
                    println(
                        "FIX02_FINAL R4 firstTime=$firstTime booking=${fullRow(
                            ds,
                            "bookings",
                            b.id,
                        )} effects=${sideEffectCounts(ds)}",
                    )
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
                    "FIX02_LOCK $label firstPid=${firstPid.get()} secondPid=${secondPid.get()} " +
                        "blockedOn=$waitingQuery",
                )
            } finally {
                release.countDown()
            }
            listOf(one.await(), two.await()).also { println("FIX02_RESULTS $label $it") }
        }
}
