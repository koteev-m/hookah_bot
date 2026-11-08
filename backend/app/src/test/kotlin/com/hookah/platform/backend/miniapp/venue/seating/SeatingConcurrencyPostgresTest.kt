package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.api.SeatingConflictException
import com.hookah.platform.backend.test.PostgresTestEnv
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeatingConcurrencyPostgresTest {
    @Test
    fun `two reservations in both orders have one winner and unrelated venue progresses`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 8
                val (venue, tables) = seedSeating(ds)
                val (independent, independentTables) = seedSeating(ds)
                val first = manual(tables[0], "2030-01-07T20:00")
                val results =
                    race(ds, venue, first, first.copy(requestId = cmd("x").requestId)) {
                        SeatingRepository(
                            ds,
                            clock = seatingClock,
                        ).command(independent, STAFF, manual(independentTables[0], "2030-01-07T20:00"))
                    }
                assertEquals(1, results.count { it.isSuccess })
                assertTrue(results.last().exceptionOrNull() is SeatingConflictException)
                assertEquals(1, SeatingRepository(ds, clock = seatingClock).state(venue, STAFF).bookings.size)
                val reverse =
                    race(ds, venue, manual(tables[1], "2030-01-07T20:30"), manual(tables[1], "2030-01-07T20:00")) {}
                assertEquals(1, reverse.count { it.isSuccess })
                ds.connection.use {
                        c ->
                    assertEquals(
                        2,
                        c.rows("SELECT COUNT(*) FROM bookings WHERE venue_id = ?", venue) { it.getInt(1) }.single(),
                    )
                }
            }
        }

    @Test
    fun `booking against seating and extension obey both controlled operation orders`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 8
                val (venue, tables) = seedSeating(ds)
                val repo = SeatingRepository(ds, clock = seatingClock)
                assertEquals(
                    listOf(
                        true,
                        false,
                    ),
                    race(
                        ds,
                        venue,
                        manual(tables[0], "2030-01-07T18:30"),
                        cmd("SEAT").copy(tableId = tables[0], partySize = 2),
                    ) {
                    }.map { it.isSuccess },
                )
                assertEquals(
                    listOf(
                        true,
                        false,
                    ),
                    race(
                        ds,
                        venue,
                        cmd("SEAT").copy(tableId = tables[1], partySize = 2),
                        manual(tables[1], "2030-01-07T18:30"),
                    ) {
                    }.map { it.isSuccess },
                )
                val seat = repo.state(venue, STAFF).tables.single { it.id == tables[1] }.seating!!
                assertEquals(
                    listOf(
                        true,
                        false,
                    ),
                    race(
                        ds,
                        venue,
                        manual(tables[1], "2030-01-07T20:00"),
                        cmd("EXTEND").copy(id = seat.id, revision = seat.revision, localEnd = "2030-01-07T20:30"),
                    ) {
                    }.map { it.isSuccess },
                )
                val next = repo.command(venue, STAFF, cmd("SEAT").copy(tableId = tables[2], partySize = 2))
                assertEquals(
                    listOf(
                        true,
                        false,
                    ),
                    race(
                        ds,
                        venue,
                        cmd("EXTEND").copy(id = next.id, revision = 0, localEnd = "2030-01-07T20:30"),
                        manual(tables[2], "2030-01-07T20:00"),
                    ) {
                    }.map { it.isSuccess },
                )
            }
        }

    @Test
    fun `competing moves reciprocal moves release and deactivate are atomic`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 8
                val (venue, tables) = seedSeating(ds)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val a = repo.command(venue, STAFF, cmd("SEAT").copy(tableId = tables[0], partySize = 2))
                val b = repo.command(venue, STAFF, cmd("SEAT").copy(tableId = tables[1], partySize = 2))
                val crossing =
                    race(
                        ds,
                        venue,
                        cmd("MOVE").copy(id = a.id, revision = 0, tableId = tables[1]),
                        cmd("MOVE").copy(id = b.id, revision = 0, tableId = tables[0]),
                    ) {
                    }
                assertTrue(crossing.all { it.exceptionOrNull() is SeatingConflictException })
                val moves =
                    race(
                        ds,
                        venue,
                        cmd("MOVE").copy(id = a.id, revision = 0, tableId = tables[2]),
                        cmd("MOVE").copy(id = b.id, revision = 0, tableId = tables[2]),
                    ) {
                    }
                assertEquals(listOf(true, false), moves.map { it.isSuccess })
                assertEquals(
                    listOf(
                        true,
                        true,
                    ),
                    race(
                        ds,
                        venue,
                        cmd("RELEASE").copy(id = a.id, revision = 1),
                        cmd("SEAT").copy(tableId = tables[2], partySize = 2),
                    ) {
                    }.map { it.isSuccess },
                )
                assertEquals(
                    listOf(
                        false,
                        true,
                    ),
                    race(
                        ds,
                        venue,
                        cmd("SEAT").copy(tableId = tables[1], partySize = 2),
                        cmd("RELEASE").copy(id = b.id, revision = 0),
                    ) {
                    }.map { it.isSuccess },
                )
                val disable = cmd("TABLE").copy(id = tables[0], revision = 1, number = 1, capacity = 4, active = false)
                assertEquals(
                    listOf(
                        true,
                        false,
                    ),
                    race(ds, venue, disable, manual(tables[0], "2030-01-07T20:00"), actor = OWNER) {
                    }.map { it.isSuccess },
                )
                assertEquals(
                    listOf(
                        true,
                        false,
                    ),
                    race(
                        ds,
                        venue,
                        manual(tables[1], "2030-01-07T20:00"),
                        cmd("TABLE").copy(id = tables[1], revision = 1, number = 3, capacity = 4, active = false),
                        actor = OWNER,
                    ) {
                    }.map { it.isSuccess },
                )
                ds.connection.use { c ->
                    assertEquals(
                        0,
                        c.rows(
                            """
                            SELECT COUNT(*) FROM (SELECT table_id FROM table_seatings WHERE released_at IS NULL GROUP BY
                            table_id HAVING COUNT(*) > 1) d
                            """.trimIndent(),
                        ) { it.getInt(1) }.single(),
                    )
                    assertEquals(1, c.rows("SELECT COUNT(*) FROM table_seating_moves") { it.getInt(1) }.single())
                }
            }
        }

    @Test
    fun `manual against guest reservation in both orders uses same PostgreSQL reservation authority`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 8
                val (venue, tables) = seedSeating(ds)
                prepareGuestChoice(ds, venue, tables)
                val manual = manual(tables[0], "2030-01-07T20:00")
                val guest =
                    cmd(
                        "GUEST_BOOKING",
                    ).copy(tableId = tables[0], localStart = "2030-01-07T20:00", partySize = 2)
                assertEquals(listOf(true, false), race(ds, venue, manual, guest) {}.map { it.isSuccess })
                assertEquals(
                    listOf(
                        true,
                        false,
                    ),
                    race(
                        ds,
                        venue,
                        guest.copy(tableId = tables[1]),
                        manual.copy(tableId = tables[1], requestId = cmd("x").requestId),
                    ) {
                    }.map { it.isSuccess },
                )
                val records = SeatingRepository(ds, clock = seatingClock).state(venue, STAFF).bookings
                assertEquals(listOf("CONFIRMED", "PENDING"), records.map { it.status })
                ds.connection.use { c ->
                    assertEquals(
                        1,
                        c.rows("SELECT COUNT(*) FROM audit_log WHERE action = 'SEATING_GUEST_BOOKING'") {
                            it.getInt(1)
                        }.single(),
                    )
                    assertEquals(
                        1,
                        c.rows("SELECT COUNT(*) FROM audit_log WHERE action = 'SEATING_MANUAL_BOOKING'") {
                            it.getInt(1)
                        }.single(),
                    )
                }
            }
        }

    @Test
    fun `guest competitors and disabling table choice serialize in both orders`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 8
                val (venue, tables) = seedSeating(ds)
                ds.connection.use { it.write("INSERT INTO users(telegram_user_id) VALUES (1004)") }
                prepareGuestChoice(ds, venue, tables)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val guest =
                    cmd(
                        "GUEST_BOOKING",
                    ).copy(tableId = tables[0], localStart = "2030-01-07T20:00", partySize = 2)
                assertEquals(
                    listOf(true, false),
                    race(
                        ds,
                        venue,
                        guest,
                        guest.copy(requestId = cmd("x").requestId),
                        secondGuest = 1004,
                    ) {}.map { it.isSuccess },
                )
                val firstRules = repo.state(venue, OWNER).rules
                val off =
                    cmd("RULES").copy(
                        revision = firstRules.revision,
                        rules = firstRules.copy(usePlan = false, guestTableChoice = false),
                    )
                val nextGuest = guest.copy(requestId = cmd("x").requestId, tableId = tables[1])
                assertEquals(
                    listOf(true, false),
                    race(ds, venue, off, nextGuest, actor = OWNER) {}.map { it.isSuccess },
                )
                repo.command(venue, OWNER, cmd("RULES").copy(revision = firstRules.revision + 1, rules = firstRules))
                assertEquals(
                    listOf(true, true),
                    race(
                        ds,
                        venue,
                        nextGuest,
                        off.copy(requestId = cmd("x").requestId, revision = firstRules.revision + 2),
                        actor = OWNER,
                    ) {}.map { it.isSuccess },
                )
                val bookings = repo.state(venue, OWNER).bookings
                assertEquals(2, bookings.size)
                assertTrue(bookings.all { it.status == "PENDING" })
                ds.connection.use { c ->
                    assertEquals(
                        2,
                        c.rows("SELECT COUNT(*) FROM audit_log WHERE action = 'SEATING_GUEST_BOOKING'") {
                            it.getInt(1)
                        }.single(),
                    )
                }
            }
        }

    private suspend fun race(
        ds: HikariDataSource,
        venue: Long,
        first: SeatingCommand,
        second: SeatingCommand,
        actor: Long = STAFF,
        secondGuest: Long = 1003,
        during: suspend () -> Unit,
    ): List<Result<SeatingResult>> =
        kotlinx.coroutines.coroutineScope {
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val secondEntered = CountDownLatch(1)
            val secondPid = AtomicInteger()
            val winner =
                SeatingRepository(ds, clock = seatingClock, checkpoint = { phase, _ ->
                    if (phase == "LOCKED") {
                        held.countDown()
                        check(release.await(20, TimeUnit.SECONDS))
                    }
                })
            val loser =
                SeatingRepository(ds, clock = seatingClock, checkpoint = { phase, c ->
                    if (phase == "BEFORE_LOCK") {
                        secondPid.set(c.rows("SELECT pg_backend_pid()") { it.getInt(1) }.single())
                        secondEntered.countDown()
                    }
                })
            val one =
                async(Dispatchers.IO) {
                    runCatching {
                        if (first.action == "GUEST_BOOKING") {
                            winner.guestBooking(
                                venue,
                                1003,
                                first,
                            )
                        } else {
                            winner.command(venue, actor, first)
                        }
                    }
                }
            check(held.await(20, TimeUnit.SECONDS))
            val two =
                async(Dispatchers.IO) {
                    runCatching {
                        if (second.action == "GUEST_BOOKING") {
                            loser.guestBooking(
                                venue,
                                secondGuest,
                                second,
                            )
                        } else {
                            loser.command(venue, actor, second)
                        }
                    }
                }
            try {
                check(secondEntered.await(20, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var observed = false
                ds.connection.use { c ->
                    while (!observed && System.nanoTime() < deadline) {
                        observed =
                            c.rows("SELECT cardinality(pg_blocking_pids(?)) > 0", secondPid.get()) {
                                it.getBoolean(1)
                            }.single()
                        Thread.yield()
                    }
                }
                assertTrue(observed, "Independent PostgreSQL connection must be blocked on the winning transaction")
                during()
            } finally {
                release.countDown()
            }
            listOf(one.await(), two.await())
        }

    private fun manual(
        table: Long,
        start: String,
    ) = cmd(
        "MANUAL_BOOKING",
    ).copy(tableId = table, localStart = start, partySize = 2, name = "Synthetic offline guest")
}
