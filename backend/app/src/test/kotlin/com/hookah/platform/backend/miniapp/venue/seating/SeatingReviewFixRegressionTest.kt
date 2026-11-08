package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.api.SeatingConflictException
import com.hookah.platform.backend.miniapp.guest.db.BookingStatus
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.TelegramTrafficPolicy
import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SeatingReviewFixRegressionTest {
    @Test
    fun `extension after MOVE ignores completed preparation but retains snapshots and future conflicts`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val initial = SeatingRepository(ds, clock = seatingClock)
                setBuffer(initial, venue, 10)
                val a = initial.command(venue, STAFF, cmd("SEAT").copy(tableId = tables[0], partySize = 2))
                val b = initial.command(venue, STAFF, cmd("SEAT").copy(tableId = tables[1], partySize = 2))
                val before = fullRow(ds, "table_seatings", a.id)
                setBuffer(initial, venue, 0, 60)
                SeatingRepository(
                    ds,
                    clock = fixClock(19),
                ).command(venue, STAFF, cmd("RELEASE").copy(id = b.id, revision = 0))
                val move = cmd("MOVE").copy(id = a.id, revision = 0, tableId = tables[1])
                assertFailsWith<SeatingConflictException> {
                    SeatingRepository(ds, clock = fixClock(19, 9)).command(venue, STAFF, move)
                }
                val current = SeatingRepository(ds, clock = fixClock(19, 10))
                current.command(venue, STAFF, move)
                current.command(venue, STAFF, offline(tables[1], "2030-01-07T21:10"))
                val extend = cmd("EXTEND").copy(id = a.id, revision = 1, localEnd = "2030-01-07T20:30")
                val moved = fullRow(ds, "table_seatings", a.id)
                val effects = sideEffectCounts(ds)
                assertFailsWith<SeatingConflictException> {
                    current.command(venue, STAFF, extend.copy(localEnd = "2030-01-07T21:01"))
                }
                val failing =
                    SeatingRepository(ds, clock = fixClock(19, 10), checkpoint = { phase, _ ->
                        if (phase == "BEFORE_COMMIT") error("Synthetic rollback after extension")
                    })
                assertFailsWith<IllegalStateException> { failing.command(venue, STAFF, extend) }
                assertEquals(moved, fullRow(ds, "table_seatings", a.id))
                assertEquals(effects, sideEffectCounts(ds))
                val saved = current.command(venue, STAFF, extend)
                assertEquals(saved, SeatingRepository(ds, clock = fixClock(19, 11)).command(venue, STAFF, extend))
                val afterPreparation = SeatingRepository(ds, clock = fixClock(19, 20))
                val finalExtend = cmd("EXTEND").copy(id = a.id, revision = 2, localEnd = "2030-01-07T21:00")
                val finalResult = afterPreparation.command(venue, STAFF, finalExtend)
                assertEquals(finalResult, afterPreparation.command(venue, STAFF, finalExtend))
                val after = fullRow(ds, "table_seatings", a.id)
                for (field in listOf(
                    "started_at",
                    "duration_minutes",
                    "buffer_minutes",
                    "visit_id",
                    "table_session_id",
                )) {
                    assertEquals(before[field], after[field], field)
                }
                assertEquals("2030-01-07T18:00:00Z", current.state(venue, STAFF).tables[1].seating!!.agreedEndAt)
                assertFailsWith<SeatingConflictException> {
                    current.command(venue, STAFF, extend.copy(requestId = UUID.randomUUID().toString()))
                }
                assertEquals(after, fullRow(ds, "table_seatings", a.id))
            }
        }

    @Test
    fun `manual and guest SEAT require current booking revision but exact replay survives status change`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                for (guest in listOf(false, true)) {
                    val (venue, tables) = seedSeating(ds)
                    val repo = SeatingRepository(ds, clock = seatingClock)
                    val b =
                        if (guest) {
                            prepareGuestChoice(ds, venue, tables)
                            repo.guestBooking(
                                venue,
                                1003,
                                cmd(
                                    "GUEST_BOOKING",
                                ).copy(tableId = tables[0], partySize = 2, localStart = "2030-01-07T20:00"),
                            ).also {
                                GuestBookingRepository(
                                    ds,
                                ).updateByVenue(it.id, venue, BookingStatus.CONFIRMED, actorUserId = OWNER)
                            }
                        } else {
                            repo.command(venue, STAFF, offline(tables[0]))
                        }
                    val oldRevision = repo.state(venue, STAFF).bookings.single().revision
                    repo.command(
                        venue,
                        OWNER,
                        cmd(
                            "ASSIGN_BOOKING",
                        ).copy(id = b.id, revision = oldRevision, tableId = tables[0], localEnd = "2030-01-07T23:00"),
                    )
                    val row = fullRow(ds, "bookings", b.id)
                    val effects = sideEffectCounts(ds)
                    val at20 = SeatingRepository(ds, clock = fixClock(20))
                    val seat = cmd("SEAT").copy(bookingId = b.id, revision = oldRevision)
                    for (revision in listOf(oldRevision, null)) {
                        assertFailsWith<SeatingConflictException> {
                            at20.command(
                                venue,
                                STAFF,
                                seat.copy(revision = revision),
                            )
                        }
                    }
                    assertEquals(row, fullRow(ds, "bookings", b.id))
                    assertEquals(effects, sideEffectCounts(ds))
                    assertNull(at20.state(venue, STAFF).tables[0].seating)
                    val accepted = seat.copy(revision = oldRevision + 1)
                    val result = at20.command(venue, STAFF, accepted)
                    assertEquals(result, at20.command(venue, STAFF, accepted))
                    val committed = sideEffectCounts(ds)
                    assertFailsWith<SeatingConflictException> {
                        at20.command(venue, STAFF, accepted.copy(requestId = UUID.randomUUID().toString()))
                    }
                    assertEquals(committed, sideEffectCounts(ds))
                    assertEquals("SEATED", fullRow(ds, "bookings", b.id)["status"])
                }
            }
        }

    @Test
    fun `cancelled booking rejects stale SEAT atomically and legacy arrival still replays`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val b = repo.command(venue, STAFF, offline(tables[0]))
                val bookings = GuestBookingRepository(ds)
                assertNotNull(bookings.updateByVenue(b.id, venue, BookingStatus.CANCELED, actorUserId = OWNER))
                val before = fullRow(ds, "bookings", b.id)
                val effects = sideEffectCounts(ds)
                assertFailsWith<SeatingConflictException> {
                    repo.command(venue, STAFF, cmd("SEAT").copy(bookingId = b.id, revision = 0))
                }
                assertEquals(before, fullRow(ds, "bookings", b.id))
                assertEquals(effects, sideEffectCounts(ds))
                val live = repo.command(venue, STAFF, offline(tables[0]))
                // Trusted legacy arrival reads the revision under the same lock, without a public bypass flag.
                val arrival = SeatingRepository(ds, clock = fixClock(20))
                val first = arrival.seatBookingArrival(venue, STAFF, live.id)
                assertEquals(first, arrival.seatBookingArrival(venue, STAFF, live.id))
            }
        }

    @Test
    fun `time change outbox failure and late transaction failure roll back the whole booking operation`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                prepareGuestChoice(ds, venue, tables)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val b =
                    repo.guestBooking(
                        venue,
                        1003,
                        cmd("GUEST_BOOKING").copy(tableId = tables[0], partySize = 2, localStart = "2030-01-07T20:00"),
                    )
                GuestBookingRepository(ds).updateByVenue(b.id, venue, BookingStatus.CONFIRMED, actorUserId = OWNER)
                // PRODUCT recipient FOR SHARE is exercised by the PostgreSQL tests; H2 has no FOR SHARE.
                val policy = TelegramTrafficPolicy.unrestricted()
                val outbox = TelegramOutboxEnqueuer(TelegramOutboxRepository(ds, policy), seatingJson, policy)
                val change =
                    cmd(
                        "ASSIGN_BOOKING",
                    ).copy(id = b.id, revision = 1, tableId = tables[0], localStart = "2030-01-07T21:00")
                val before = fullRow(ds, "bookings", b.id)
                val effects = sideEffectCounts(ds)
                assertFailsWith<IllegalStateException> {
                    repo.command(venue, OWNER, change) { _, _ -> error("Synthetic unavailable outbox") }
                }
                val failing =
                    SeatingRepository(ds, clock = seatingClock, checkpoint = {
                            phase,
                            _,
                        ->
                        if (phase == "BEFORE_COMMIT") error("Synthetic late rollback")
                    })
                assertFailsWith<IllegalStateException> {
                    failing.command(
                        venue,
                        OWNER,
                        change,
                    ) { c, _ ->
                        outbox.enqueueBookingSendMessageInTransaction(
                            c,
                            1003,
                            "Synthetic",
                            dedupeKey = change.requestId,
                        )
                    }
                }
                assertEquals(before, fullRow(ds, "bookings", b.id))
                assertEquals(effects, sideEffectCounts(ds))
                val saved =
                    repo.command(
                        venue,
                        OWNER,
                        change,
                    ) { c, _ ->
                        outbox.enqueueBookingSendMessageInTransaction(
                            c,
                            1003,
                            "Synthetic",
                            dedupeKey = change.requestId,
                        )
                    }
                assertEquals(
                    saved,
                    repo.command(venue, OWNER, change) { _, _ -> error("Replay must not notify twice") },
                )
                assertEquals(effects[1] + 1, sideEffectCounts(ds)[1])
            }
        }
}
