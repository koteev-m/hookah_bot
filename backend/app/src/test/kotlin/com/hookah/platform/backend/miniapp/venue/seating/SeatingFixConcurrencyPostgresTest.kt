package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.SeatingConflictException
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.telegram.StaffCallReason
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

typealias FixCheckpoint = (String, Connection) -> Unit

class SeatingFixConcurrencyPostgresTest {
    @Test
    fun `release versus manual and guest reservation preserves preparation in both orders`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 8
                for (guest in listOf(false, true)) for (releaseFirst in listOf(false, true)) {
                    val (venue, tables) = seedSeating(ds)
                    prepareGuestChoice(ds, venue, tables)
                    val setup = SeatingRepository(ds, clock = seatingClock)
                    setBuffer(setup, venue, 10)
                    val seated = setup.command(venue, STAFF, cmd("SEAT").copy(tableId = tables[0], partySize = 2))
                    val release = cmd("RELEASE").copy(id = seated.id, revision = 0)
                    val book =
                        if (guest) {
                            cmd(
                                "GUEST_BOOKING",
                            ).copy(
                                tableId = tables[0],
                                partySize = 2,
                                localStart = "2030-01-07T20:01",
                            )
                        } else {
                            offline(tables[0], "2030-01-07T20:01")
                        }

                    suspend fun operation(
                        releasing: Boolean,
                        check: FixCheckpoint,
                    ): Any {
                        val repo = SeatingRepository(ds, clock = fixClock(20), checkpoint = check)
                        return if (releasing) {
                            repo.command(venue, STAFF, release)
                        } else if (guest) {
                            repo.guestBooking(venue, 1003, book)
                        } else {
                            repo.command(venue, STAFF, book)
                        }
                    }
                    val results =
                        race(
                            ds,
                            "F1 guest=$guest releaseFirst=$releaseFirst",
                            "LOCKED",
                            { operation(releaseFirst, it) },
                            { operation(!releaseFirst, it) },
                        )
                    assertTrue(results[if (releaseFirst) 0 else 1].isSuccess)
                    assertTrue(results[if (releaseFirst) 1 else 0].exceptionOrNull() is SeatingConflictException)
                    val state = SeatingRepository(ds, clock = fixClock(20)).state(venue, STAFF)
                    assertEquals("PREPARING", state.tables.first().state)
                    assertTrue(state.bookings.isEmpty())
                    ds.connection.use { c ->
                        assertEquals(
                            0,
                            c.rows(
                                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ? " +
                                    "AND action IN ('SEATING_MANUAL_BOOKING','SEATING_GUEST_BOOKING')",
                                venue,
                            ) {
                                it.getInt(1)
                            }.single(),
                        )
                    }
                    val later = SeatingRepository(ds, clock = fixClock(20, 9))
                    assertEquals(SeatingResult(seated.id, 1), later.command(venue, STAFF, release))
                    if (guest) {
                        later.guestBooking(venue, 1003, book.copy(localStart = "2030-01-07T20:10"))
                    } else {
                        later.command(venue, STAFF, book.copy(localStart = "2030-01-07T20:10"))
                    }
                }
            }
        }

    @Test
    fun `release and ordinary Mini App or Bot call cannot both commit in either order`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 8
                for (bot in listOf(false, true)) for (releaseFirst in listOf(false, true)) {
                    val (venue, tables) = seedSeating(ds)
                    val (otherVenue, otherTables) = seedSeating(ds)
                    val setup = SeatingRepository(ds, clock = seatingClock)
                    val seated = setup.command(venue, STAFF, cmd("SEAT").copy(tableId = tables[0], partySize = 2))
                    val session = setup.state(venue, STAFF).tables.first().seating!!.tableSessionId
                    val release = cmd("RELEASE").copy(id = seated.id, revision = 0)

                    suspend fun operation(
                        releasing: Boolean,
                        check: FixCheckpoint,
                    ): Any? =
                        if (releasing) {
                            SeatingRepository(
                                ds,
                                clock = seatingClock,
                                checkpoint = check,
                            ).command(venue, STAFF, release)
                        } else {
                            val calls = StaffCallRepository(ds, checkpoint = check)
                            if (bot) {
                                calls.createStaffCall(venue, tables[0], 1003, StaffCallReason.COALS, null, session)
                            } else {
                                calls.createGuestStaffCall(venue, tables[0], session, 1003, StaffCallReason.COALS, null)
                            }
                        }
                    val before = sideEffectCounts(ds)
                    val results =
                        race(
                            ds,
                            "F5 bot=$bot releaseFirst=$releaseFirst",
                            "BEFORE_COMMIT",
                            { operation(releaseFirst, it) },
                            { operation(!releaseFirst, it) },
                        ) {
                            withTimeout(5000) {
                                SeatingRepository(ds, clock = seatingClock).command(
                                    otherVenue,
                                    STAFF,
                                    cmd("SEAT").copy(tableId = otherTables[0], partySize = 2),
                                )
                            }
                        }
                    assertTrue(results[0].isSuccess)
                    if (releaseFirst) {
                        assertTrue(results[1].exceptionOrNull() is NotFoundException)
                    } else {
                        assertTrue(results[1].exceptionOrNull() is SeatingConflictException)
                    }
                    ds.connection.use { c ->
                        assertEquals(
                            0,
                            c.rows(
                                """
                                SELECT COUNT(*) FROM staff_calls sc JOIN table_sessions s ON s.id = sc.table_session_id
                                WHERE s.id = ? AND s.status = 'ENDED' AND sc.status IN ('NEW','ACK')
                                """.trimIndent(),
                                session,
                            ) { it.getInt(1) }.single(),
                        )
                        val count =
                            c.rows(
                                "SELECT COUNT(*) FROM staff_calls WHERE table_session_id = ?",
                                session,
                            ) { it.getInt(1) }.single()
                        assertEquals(if (releaseFirst) 0 else 1, count)
                        assertEquals(
                            if (releaseFirst) "ENDED" else "ACTIVE",
                            fullRow(ds, "table_sessions", session)["status"],
                        )
                        assertEquals(
                            if (releaseFirst) 1 else 0,
                            c.rows(
                                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'SEATING_RELEASE'",
                                venue,
                            ) {
                                it.getInt(1)
                            }.single(),
                        )
                    }
                    assertEquals(before[1], sideEffectCounts(ds)[1], "No false Telegram delivery/outbox")
                    if (!releaseFirst) {
                        val calls = StaffCallRepository(ds)
                        val call = calls.listActiveByVenue(venue, 10).single()
                        calls.ackStaffCall(venue, call.id, STAFF)
                        calls.doneStaffCall(venue, call.id, STAFF)
                        setup.command(venue, STAFF, release)
                    }
                    assertEquals(SeatingResult(seated.id, 1), setup.command(venue, STAFF, release))
                    assertTrue(runCatching { operation(false) { _, _ -> } }.exceptionOrNull() is NotFoundException)
                }
            }
        }

    @Test
    fun `cancel versus update has atomic full row status and stale revision in both orders`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 8
                for (cancelFirst in listOf(false, true)) {
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
                    val before = fullRow(ds, "bookings", b.id)
                    val effects = sideEffectCounts(ds)

                    suspend fun operation(
                        cancel: Boolean,
                        check: FixCheckpoint,
                    ): Any? {
                        val legacy = GuestBookingRepository(ds, checkpoint = check)
                        return if (cancel) {
                            legacy.cancelByGuest(b.id, venue, 1003)
                        } else {
                            legacy.updateByGuest(b.id, venue, 1003, fixClock(23).instant(), 3, "New time")
                        }
                    }
                    val result =
                        race(
                            ds,
                            "F6 cancelFirst=$cancelFirst",
                            "BEFORE_COMMIT",
                            { operation(cancelFirst, it) },
                            { operation(!cancelFirst, it) },
                        )
                    assertTrue(result.all { it.isSuccess })
                    assertNotNull(result[0].getOrNull())
                    if (cancelFirst) assertNull(result[1].getOrNull()) else assertNotNull(result[1].getOrNull())
                    val after = fullRow(ds, "bookings", b.id)
                    assertEquals("CANCELED", after["status"])
                    assertEquals(if (cancelFirst) "1" else "2", after["seating_revision"])
                    if (cancelFirst) {
                        for (key in listOf(
                            "scheduled_at",
                            "agreed_end_at",
                            "party_size",
                            "comment",
                            "duration_minutes",
                            "buffer_minutes",
                        )) assertEquals(before[key], after[key], key)
                    }
                    repeat(2) {
                        assertNull(operation(false) { _, _ -> })
                        assertEquals(after, fullRow(ds, "bookings", b.id))
                        assertEquals(effects, sideEffectCounts(ds))
                    }
                    val stale =
                        runCatching {
                            repo.command(
                                venue,
                                OWNER,
                                cmd("ASSIGN_BOOKING").copy(id = b.id, revision = 0, tableId = tables[1]),
                            )
                        }
                    assertTrue(stale.exceptionOrNull() is NotFoundException)
                    assertEquals(after, fullRow(ds, "bookings", b.id))
                    assertEquals(effects, sideEffectCounts(ds))
                }
            }
        }

    @Test
    fun `call completion versus movement keeps historical table or current table according to commit order`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 8
                for (moveFirst in listOf(false, true)) {
                    val (venue, tables) = seedSeating(ds)
                    val repo = SeatingRepository(ds, clock = seatingClock)
                    val seat = repo.command(venue, STAFF, cmd("SEAT").copy(tableId = tables[0], partySize = 2))
                    val session = repo.state(venue, STAFF).tables.first().seating!!.tableSessionId
                    val calls = StaffCallRepository(ds)
                    val call = calls.createGuestStaffCall(venue, tables[0], session, 1003, StaffCallReason.COALS, null)
                    calls.ackStaffCall(venue, call.id, STAFF)
                    val before = fullRow(ds, "staff_calls", call.id)

                    suspend fun operation(
                        move: Boolean,
                        check: FixCheckpoint,
                    ): Any? =
                        if (move) {
                            SeatingRepository(ds, clock = seatingClock, checkpoint = check).command(
                                venue,
                                STAFF,
                                cmd("MOVE").copy(id = seat.id, revision = 0, tableId = tables[1]),
                            )
                        } else {
                            StaffCallRepository(ds, checkpoint = check).doneStaffCall(venue, call.id, STAFF)
                        }
                    val results =
                        race(
                            ds,
                            "F3 moveFirst=$moveFirst",
                            "BEFORE_COMMIT",
                            { operation(moveFirst, it) },
                            { operation(!moveFirst, it) },
                        )
                    assertTrue(results.all { it.isSuccess })
                    val after = fullRow(ds, "staff_calls", call.id)
                    assertEquals(
                        before + mapOf("status" to "DONE", "table_id" to tables[if (moveFirst) 1 else 0].toString()),
                        after,
                    )
                    ds.connection.use { c ->
                        assertEquals(
                            if (moveFirst) 1 else 0,
                            c.rows(
                                "SELECT COUNT(*) FROM audit_log WHERE action = 'STAFF_CALL_MOVED' AND entity_id = ?",
                                call.id,
                            ) {
                                it.getInt(1)
                            }.single(),
                        )
                    }
                    assertFalse(calls.listActiveByVenue(venue, 20).any { it.id == call.id })
                }
            }
        }

    private suspend fun race(
        ds: HikariDataSource,
        label: String,
        holdPhase: String,
        first: suspend (FixCheckpoint) -> Any?,
        second: suspend (FixCheckpoint) -> Any?,
        during: suspend () -> Unit = {},
    ): List<Result<Any?>> =
        coroutineScope {
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val firstPid = AtomicInteger()
            val secondPid = AtomicInteger()
            val one =
                async(Dispatchers.IO) {
                    runCatching {
                        first { phase, c ->
                            if (phase == holdPhase) {
                                firstPid.set(c.rows("SELECT pg_backend_pid()") { it.getInt(1) }.single())
                                held.countDown()
                                check(release.await(20, TimeUnit.SECONDS))
                            }
                        }
                    }
                }
            check(held.await(20, TimeUnit.SECONDS))
            val two =
                async(Dispatchers.IO) {
                    runCatching {
                        second { phase, c ->
                            if (phase == "BEFORE_LOCK") {
                                secondPid.set(c.rows("SELECT pg_backend_pid()") { it.getInt(1) }.single())
                                entered.countDown()
                            }
                        }
                    }
                }
            try {
                check(entered.await(20, TimeUnit.SECONDS))
                assertNotEquals(firstPid.get(), secondPid.get())
                var blocked = false
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                ds.connection.use { c ->
                    while (!blocked && System.nanoTime() < deadline) {
                        blocked =
                            c.rows("SELECT ? = ANY(pg_blocking_pids(?))", firstPid.get(), secondPid.get()) {
                                it.getBoolean(1)
                            }.single()
                        Thread.yield()
                    }
                }
                assertTrue(blocked, "Expected exact production transaction blocker: $label")
                during()
            } finally {
                release.countDown()
            }
            val results = listOf(one.await(), two.await())
            println(
                "FIX_PG $label firstPid=${firstPid.get()} secondPid=${secondPid.get()} blocked=true results=$results",
            )
            results
        }
}
