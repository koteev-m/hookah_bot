package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.SeatingConflictException
import com.hookah.platform.backend.miniapp.guest.db.BookingStatus
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.guest.db.VisitRepository
import com.hookah.platform.backend.telegram.StaffCallReason
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun fixClock(
    localHour: Int,
    minute: Int = 0,
): Clock = Clock.fixed(Instant.parse("2030-01-07T%02d:%02d:00Z".format(localHour - 3, minute)), ZoneOffset.UTC)

internal fun fullRow(
    ds: HikariDataSource,
    table: String,
    id: Long,
): Map<String, String?> =
    ds.connection.use { c ->
        c.rows("SELECT * FROM $table WHERE id = ?", id) { rs ->
            (1..rs.metaData.columnCount).associate { rs.metaData.getColumnLabel(it) to rs.getString(it) }
        }.single()
    }

internal fun sideEffectCounts(ds: HikariDataSource): List<Int> =
    ds.connection.use { c ->
        listOf("audit_log", "telegram_outbox", "seating_operations").map { table ->
            c.rows("SELECT COUNT(*) FROM $table") { it.getInt(1) }.single()
        }
    }

internal fun offline(
    table: Long,
    start: String = "2030-01-07T20:00",
) = cmd("MANUAL_BOOKING").copy(tableId = table, localStart = start, partySize = 2, name = "Synthetic offline")

internal suspend fun setBuffer(
    repo: SeatingRepository,
    venue: Long,
    buffer: Int,
    duration: Int = 120,
) {
    val rules = repo.state(venue, OWNER).rules
    repo.command(
        venue,
        OWNER,
        cmd("RULES").copy(
            revision = rules.revision,
            rules = rules.copy(bufferMinutes = buffer, durationMinutes = duration),
        ),
    )
}

class SeatingFixRegressionTest {
    @Test
    fun `actual early on-time and late release retains snapshot preparation for every writer`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                for (hour in listOf(19, 20, 21)) {
                    val (venue, tables) = seedSeating(ds)
                    prepareGuestChoice(ds, venue, tables)
                    val before = SeatingRepository(ds, clock = seatingClock)
                    setBuffer(before, venue, 10)
                    val seats =
                        tables.map {
                            before.command(
                                venue,
                                STAFF,
                                cmd("SEAT").copy(tableId = it, partySize = 2),
                            )
                        }
                    setBuffer(before, venue, 0)
                    val now = SeatingRepository(ds, clock = fixClock(hour))
                    val releases = seats.map { cmd("RELEASE").copy(id = it.id, revision = 0) }
                    releases.forEach { now.command(venue, STAFF, it) }
                    assertTrue(now.state(venue, STAFF).tables.all { it.state == "PREPARING" && it.seating == null })
                    assertTrue(now.state(venue, STAFF).tables.all { it.reason!!.contains("Подготовка") })
                    val time = "2030-01-07T%02d:09".format(hour)
                    val manual = offline(tables[0], time)
                    val guest = cmd("GUEST_BOOKING").copy(tableId = tables[1], partySize = 2, localStart = time)
                    val seat = cmd("SEAT").copy(tableId = tables[2], partySize = 2)
                    val counts = sideEffectCounts(ds)
                    assertFailsWith<SeatingConflictException> { now.command(venue, STAFF, manual) }
                    assertFailsWith<SeatingConflictException> { now.guestBooking(venue, 1003, guest) }
                    assertFailsWith<SeatingConflictException> { now.command(venue, STAFF, seat) }
                    assertEquals(counts, sideEffectCounts(ds))
                    assertTrue(now.availability(venue, time, 2).tables.all { it.state == "UNAVAILABLE" })
                    val after = SeatingRepository(ds, clock = fixClock(hour, 10))
                    // Replaying RELEASE must not restart the preparation clock.
                    releases.forEach { after.command(venue, STAFF, it) }
                    val ready = "2030-01-07T%02d:10".format(hour)
                    assertTrue(after.state(venue, STAFF).tables.all { it.state == "FREE" })
                    now.command(venue, STAFF, manual.copy(localStart = ready))
                    now.guestBooking(venue, 1003, guest.copy(localStart = ready))
                    after.command(venue, STAFF, seat)
                }
            }
        }

    @Test
    fun `movement leaves preparation at old table without changing occupancy identity`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val repo = SeatingRepository(ds, clock = seatingClock)
                setBuffer(repo, venue, 10)
                val seat = repo.command(venue, STAFF, cmd("SEAT").copy(tableId = tables[0], partySize = 2))
                val previous = repo.state(venue, STAFF).tables.first().seating!!
                val move = cmd("MOVE").copy(id = seat.id, revision = 0, tableId = tables[1])
                repo.command(venue, STAFF, move)
                assertEquals("PREPARING", repo.state(venue, STAFF).tables.first().state)
                assertEquals(previous.visitId, repo.state(venue, STAFF).tables[1].seating!!.visitId)
                assertFailsWith<SeatingConflictException> {
                    repo.command(venue, STAFF, cmd("MOVE").copy(id = seat.id, revision = 1, tableId = tables[0]))
                }
                val later = SeatingRepository(ds, clock = fixClock(18, 10))
                later.command(venue, STAFF, move)
                later.command(venue, STAFF, cmd("MOVE").copy(id = seat.id, revision = 1, tableId = tables[0]))
                assertEquals("PREPARING", later.state(venue, STAFF).tables[1].state)
            }
        }

    @Test
    fun `legacy reconciliation rejects foreign visit and chooses proven matching guest only`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                prepareGuestChoice(ds, venue, tables)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val booking =
                    repo.guestBooking(
                        venue,
                        1003,
                        cmd("GUEST_BOOKING").copy(
                            tableId = tables[0],
                            localStart = "2030-01-07T20:00",
                            partySize = 2,
                        ),
                    )
                GuestBookingRepository(
                    ds,
                ).updateByVenue(booking.id, venue, BookingStatus.CONFIRMED, actorUserId = OWNER)
                val session =
                    TableSessionRepository(
                        ds,
                    ).resolveActiveSession(venue, tables[0], Duration.ofHours(2), fixClock(20).instant())
                val foreign =
                    ds.connection.use { c ->
                        c.write("INSERT INTO users(telegram_user_id) VALUES (1004)")
                        legacyVisit(c, venue, session.id, 1004)
                    }
                val bookingBefore = fullRow(ds, "bookings", booking.id)
                val foreignBefore = fullRow(ds, "visits", foreign)
                val counts = sideEffectCounts(ds)
                val at20 = SeatingRepository(ds, clock = fixClock(20))
                val request =
                    cmd(
                        "SEAT",
                    ).copy(
                        bookingId = booking.id,
                        revision = repo.state(venue, STAFF).bookings.single().revision,
                        reconcileLegacySession = true,
                    )
                assertFailsWith<SeatingConflictException> { at20.command(venue, STAFF, request) }
                assertEquals("RECONCILE", at20.state(venue, STAFF).tables.first().state)
                assertEquals(bookingBefore, fullRow(ds, "bookings", booking.id))
                assertEquals(foreignBefore, fullRow(ds, "visits", foreign))
                assertEquals(counts, sideEffectCounts(ds))
                assertNull(VisitRepository(ds).getGuestVisitDetail(1004, foreign)!!.booking)
                val matching = ds.connection.use { legacyVisit(it, venue, session.id, 1003) }
                val result = at20.command(venue, STAFF, request)
                assertEquals(result, at20.command(venue, STAFF, request))
                assertEquals(matching, at20.state(venue, STAFF).tables.first().seating!!.visitId)
                assertEquals(foreignBefore, fullRow(ds, "visits", foreign))
                assertEquals(booking.id, VisitRepository(ds).getGuestVisitDetail(1003, matching)!!.booking!!.bookingId)
            }
        }

    @Test
    fun `offline legacy identity needs exact booking link and ambiguous walk-in history is rejected`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val booking = repo.command(venue, STAFF, offline(tables[0]))
                val session =
                    TableSessionRepository(
                        ds,
                    ).resolveActiveSession(venue, tables[0], Duration.ofHours(2), fixClock(20).instant())
                val visit = ds.connection.use { legacyVisit(it, venue, session.id, null) }
                val at20 = SeatingRepository(ds, clock = fixClock(20))
                val request =
                    cmd(
                        "SEAT",
                    ).copy(
                        bookingId = booking.id,
                        revision = repo.state(venue, STAFF).bookings.single().revision,
                        reconcileLegacySession = true,
                    )
                assertFailsWith<SeatingConflictException> { at20.command(venue, STAFF, request) }
                assertNull(fullRow(ds, "visits", visit)["booking_id"])
                // Explicit existing booking/session identity is evidence; NULL/NULL alone is not.
                ds.connection.use { it.write("UPDATE visits SET booking_id = ? WHERE id = ?", booking.id, visit) }
                val result = at20.command(venue, STAFF, request)
                assertEquals(result, at20.command(venue, STAFF, request))
                assertNull(fullRow(ds, "visits", visit)["user_id"])
                val otherSession =
                    TableSessionRepository(
                        ds,
                    ).resolveActiveSession(venue, tables[1], Duration.ofHours(2), fixClock(20).instant())
                ds.connection.use {
                        c ->
                    legacyVisit(c, venue, otherSession.id, OWNER)
                    legacyVisit(c, venue, otherSession.id, STAFF)
                }
                assertFailsWith<SeatingConflictException> {
                    at20.command(
                        venue,
                        STAFF,
                        cmd("SEAT").copy(tableId = tables[1], partySize = 2, reconcileLegacySession = true),
                    )
                }
                assertEquals("RECONCILE", at20.state(venue, STAFF).tables[1].state)
            }
        }

    @Test
    fun `reconciliation late failure rolls back visit booking session and audit`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val session =
                    TableSessionRepository(
                        ds,
                    ).resolveActiveSession(venue, tables[0], Duration.ofHours(2), seatingClock.instant())
                val old = fullRow(ds, "table_sessions", session.id)
                val counts = sideEffectCounts(ds)
                val repo =
                    SeatingRepository(ds, clock = seatingClock, checkpoint = { phase, _ ->
                        if (phase == "BEFORE_COMMIT") error("Synthetic late failure")
                    })
                assertFailsWith<IllegalStateException> {
                    repo.command(
                        venue,
                        STAFF,
                        cmd("SEAT").copy(tableId = tables[0], partySize = 2, reconcileLegacySession = true),
                    )
                }
                assertEquals(old, fullRow(ds, "table_sessions", session.id))
                assertEquals(counts, sideEffectCounts(ds))
                ds.connection.use {
                        c ->
                    assertEquals(0, c.rows("SELECT COUNT(*) FROM visits") { it.getInt(1) }.single())
                }
            }
        }

    @Test
    fun `active calls follow move once while closed history actor audit and failed move are preserved`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val seat = repo.command(venue, STAFF, cmd("SEAT").copy(tableId = tables[0], partySize = 2))
                val session = repo.state(venue, STAFF).tables.first().seating!!.tableSessionId
                val calls = StaffCallRepository(ds)
                val active =
                    calls.createGuestStaffCall(
                        venue,
                        tables[0],
                        session,
                        1003,
                        StaffCallReason.COALS,
                        "Synthetic",
                    )
                val closed =
                    calls.createGuestStaffCall(
                        venue,
                        tables[0],
                        session,
                        1003,
                        StaffCallReason.OTHER,
                        "Finished",
                    )
                calls.ackStaffCall(venue, closed.id, STAFF)
                calls.doneStaffCall(venue, closed.id, STAFF)
                val closedBefore = fullRow(ds, "staff_calls", closed.id)
                val activeBefore = fullRow(ds, "staff_calls", active.id)
                val move = cmd("MOVE").copy(id = seat.id, revision = 0, tableId = tables[1])
                val moved = repo.command(venue, STAFF, move)
                assertEquals(moved, repo.command(venue, STAFF, move))
                assertEquals(activeBefore + ("table_id" to tables[1].toString()), fullRow(ds, "staff_calls", active.id))
                assertEquals(closedBefore, fullRow(ds, "staff_calls", closed.id))
                assertEquals(
                    listOf(active.id),
                    calls.listByGuestTableSession(venue, tables[1], session, 1003, 20).map { it.id },
                )
                assertEquals(
                    listOf(closed.id),
                    calls.listByGuestTableSession(venue, tables[0], session, 1003, 20).map { it.id },
                )
                assertTrue(calls.listByGuestTableSession(venue, tables[1], session, STAFF, 20).isEmpty())
                val counts = sideEffectCounts(ds)
                val failing =
                    SeatingRepository(ds, clock = seatingClock, checkpoint = { phase, _ ->
                        if (phase == "BEFORE_COMMIT") error("Synthetic move rollback")
                    })
                assertFailsWith<IllegalStateException> {
                    failing.command(venue, STAFF, cmd("MOVE").copy(id = seat.id, revision = 1, tableId = tables[2]))
                }
                assertEquals(counts, sideEffectCounts(ds))
                assertEquals(tables[1], calls.listActiveByVenue(venue, 20).single().tableId)
                ds.connection.use { c ->
                    val audits =
                        c.rows(
                            "SELECT actor_user_id FROM audit_log WHERE action = 'STAFF_CALL_MOVED' AND entity_id = ?",
                            active.id,
                        ) {
                            it.getLong(1)
                        }
                    assertEquals(listOf(STAFF), audits)
                }
                assertFailsWith<NotFoundException> {
                    calls.createGuestStaffCall(venue, tables[0], session, 1003, StaffCallReason.COALS, null)
                }
                // A legacy call left at an earlier table must retain its actual old value in audit.
                ds.connection.use {
                        c ->
                    c.write("UPDATE staff_calls SET table_id = ? WHERE id = ?", tables[0], active.id)
                }
                repo.command(venue, STAFF, cmd("MOVE").copy(id = seat.id, revision = 1, tableId = tables[2]))
                assertEquals(tables[2], calls.listActiveByVenue(venue, 20).single().tableId)
                assertEquals(closedBefore, fullRow(ds, "staff_calls", closed.id))
                ds.connection.use { c ->
                    val metadata =
                        c.rows(
                            "SELECT payload_json FROM audit_log WHERE action = 'STAFF_CALL_MOVED' " +
                                "AND entity_id = ? ORDER BY id",
                            active.id,
                        ) { it.getString(1) }
                    assertEquals(2, metadata.size)
                    assertTrue(metadata.last().contains("\"fromTableId\":${tables[0]}"))
                    assertTrue(metadata.last().contains("\"toTableId\":${tables[2]}"))
                }
                // The injected clock must not weaken expiry: equality is already expired.
                ds.connection.use { c ->
                    c.write("UPDATE table_sessions SET expires_at = ? WHERE id = ?", fixClock(19).instant(), session)
                }
                val expiredCalls = StaffCallRepository(ds, clock = fixClock(19))
                val expiredBefore = fullRow(ds, "table_sessions", session)
                val effectsBefore = sideEffectCounts(ds)
                assertFailsWith<NotFoundException> {
                    expiredCalls.createGuestStaffCall(venue, tables[2], session, 1003, StaffCallReason.COALS, null)
                }
                assertFailsWith<NotFoundException> {
                    expiredCalls.createStaffCall(venue, tables[2], 1003, StaffCallReason.COALS, null, session)
                }
                assertEquals(expiredBefore, fullRow(ds, "table_sessions", session))
                assertEquals(effectsBefore, sideEffectCounts(ds))
                ds.connection.use { c ->
                    assertEquals(
                        2,
                        c.rows("SELECT COUNT(*) FROM staff_calls WHERE table_session_id = ?", session) {
                            it.getInt(1)
                        }.single(),
                    )
                }
            }
        }

    @Test
    fun `assigning table preserves duration and buffer snapshots across both settings changes`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                for (newBuffer in listOf(0, 30)) {
                    val (venue, tables) = seedSeating(ds)
                    val repo = SeatingRepository(ds, clock = seatingClock)
                    setBuffer(repo, venue, 10)
                    val booking = repo.command(venue, STAFF, offline(tables[0]))
                    val before = fullRow(ds, "bookings", booking.id)
                    setBuffer(repo, venue, newBuffer, 60)
                    repo.command(
                        venue,
                        OWNER,
                        cmd("ASSIGN_BOOKING").copy(id = booking.id, revision = 0, tableId = tables[1]),
                    )
                    val after = fullRow(ds, "bookings", booking.id)
                    for (key in listOf("scheduled_at", "agreed_end_at", "duration_minutes", "buffer_minutes")) {
                        assertEquals(before[key], after[key], key)
                    }
                    assertFailsWith<SeatingConflictException> {
                        repo.command(
                            venue,
                            STAFF,
                            offline(tables[1], "2030-01-07T22:00"),
                        )
                    }
                    val next = if (newBuffer == 30) "2030-01-07T22:30" else "2030-01-07T22:10"
                    repo.command(venue, STAFF, offline(tables[1], next))
                }
            }
        }

    @Test
    fun `terminal updates replay and late failures retain complete booking row and side effects`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                prepareGuestChoice(ds, venue, tables)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val b =
                    repo.guestBooking(
                        venue,
                        1003,
                        cmd("GUEST_BOOKING").copy(tableId = tables[0], localStart = "2030-01-07T20:00", partySize = 2),
                    )
                val legacy = GuestBookingRepository(ds)
                assertNotNull(legacy.cancelByGuest(b.id, venue, 1003))
                val old = fullRow(ds, "bookings", b.id)
                val counts = sideEffectCounts(ds)
                repeat(2) {
                    assertNull(
                        legacy.updateByGuest(
                            b.id,
                            venue,
                            1003,
                            fixClock(21).instant(),
                            3,
                            "Changed",
                            ZoneId.of("Europe/Moscow"),
                        ),
                    )
                    assertNull(
                        legacy.updateByVenue(
                            b.id,
                            venue,
                            BookingStatus.CONFIRMED,
                            fixClock(21).instant(),
                            actorUserId = OWNER,
                        ),
                    )
                    assertFailsWith<NotFoundException> {
                        repo.command(
                            venue,
                            OWNER,
                            cmd("ASSIGN_BOOKING").copy(id = b.id, revision = 0, tableId = tables[1]),
                        )
                    }
                    assertEquals(old, fullRow(ds, "bookings", b.id))
                    assertEquals(counts, sideEffectCounts(ds))
                }
                val live =
                    repo.guestBooking(
                        venue,
                        1003,
                        cmd("GUEST_BOOKING").copy(tableId = tables[0], localStart = "2030-01-07T20:00", partySize = 2),
                    )
                val liveBefore = fullRow(ds, "bookings", live.id)
                val beforeCounts = sideEffectCounts(ds)
                val failing =
                    GuestBookingRepository(ds, checkpoint = {
                            phase,
                            _,
                        ->
                        if (phase == "BEFORE_COMMIT") error("Late failure")
                    })
                assertFailsWith<IllegalStateException> {
                    failing.updateByGuest(
                        live.id,
                        venue,
                        1003,
                        fixClock(23).instant(),
                        2,
                        null,
                    )
                }
                assertEquals(liveBefore, fullRow(ds, "bookings", live.id))
                assertEquals(beforeCounts, sideEffectCounts(ds))
                assertNotNull(legacy.updateByGuest(live.id, venue, 1003, fixClock(23).instant(), 2, null))
                assertFailsWith<SeatingConflictException> {
                    repo.command(
                        venue,
                        OWNER,
                        cmd("ASSIGN_BOOKING").copy(id = live.id, revision = 0, tableId = tables[1]),
                    )
                }
                val shifted = fullRow(ds, "bookings", live.id)
                assertTrue(shifted["agreed_end_at"]!!.contains("22:00:00")) // 01:00 venue time, next day.
            }
        }

    private fun legacyVisit(
        c: java.sql.Connection,
        venue: Long,
        session: Long,
        guest: Long?,
    ): Long =
        c.insert(
            """
            INSERT INTO visits(venue_id, user_id, table_session_id, source, occurred_at, service_date)
            VALUES (?, ?, ?, 'ORDER_CLOSED', ?, DATE '2030-01-07')
            """.trimIndent(),
            venue,
            guest,
            session,
            fixClock(20).instant(),
        )
}
