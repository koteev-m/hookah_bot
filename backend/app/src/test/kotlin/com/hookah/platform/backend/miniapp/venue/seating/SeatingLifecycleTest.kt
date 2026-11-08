package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.SeatingConflictException
import com.hookah.platform.backend.miniapp.guest.db.BookingStatus
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackRepository
import com.hookah.platform.backend.miniapp.guest.db.VisitRepository
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

internal suspend fun prepareGuestChoice(
    ds: HikariDataSource,
    venue: Long,
    tables: List<Long>,
) {
    val repo = SeatingRepository(ds, clock = seatingClock)
    val hall = repo.command(venue, OWNER, cmd("HALL").copy(name = "Synthetic plan"))
    val image = UUID.randomUUID().toString()
    ds.connection.use { c ->
        c.write(
            """
            INSERT INTO hall_plan_media(id, venue_id, hall_id, actor_user_id, request_id, object_key,
            checksum_sha256, input_sha256, mime_type, size_bytes, width, height, status) VALUES (?, ?, ?, ?, ?,
            ?, ?, ?,'image/png', 1, 800, 400,'READY')
            """.trimIndent(),
            image, venue, hall.id, OWNER, image, "venue-info/$image.png", "0".repeat(64), "0".repeat(64),
        )
    }
    repo.command(
        venue,
        OWNER,
        cmd("PLAN").copy(id = hall.id, revision = 0, planId = image, confirmPlanReplacement = true),
    )
    val state = repo.state(venue, OWNER)
    state.tables.filter { it.id in tables }.forEach { table ->
        repo.command(
            venue,
            OWNER,
            cmd(
                "TABLE",
            ).copy(
                id = table.id,
                revision = table.revision,
                number = table.number,
                capacity = 4,
                hallId = hall.id,
                layout = TableLayout(.5, .5, .1, .1),
            ),
        )
    }
    val rules = repo.state(venue, OWNER).rules
    repo.command(
        venue,
        OWNER,
        cmd("RULES").copy(revision = rules.revision, rules = rules.copy(usePlan = true, guestTableChoice = true)),
    )
}

class SeatingLifecycleTest {
    @Test
    fun `offline confirmed arrival retains one visit and no fabricated user`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val users = ds.connection.use { c -> c.rows("SELECT COUNT(*) FROM users") { it.getInt(1) }.single() }
                val booking =
                    repo.command(
                        venue,
                        STAFF,
                        cmd("MANUAL_BOOKING").copy(
                            tableId = tables[0],
                            localStart = "2030-01-07T20:00",
                            partySize = 2,
                            name = "Offline visitor",
                        ),
                    )
                val arrival =
                    SeatingRepository(ds, clock = Clock.fixed(Instant.parse("2030-01-07T17:00:00Z"), ZoneOffset.UTC))
                val command = cmd("SEAT").copy(bookingId = booking.id, revision = 0)
                val result = arrival.command(venue, STAFF, command)
                assertEquals(result, arrival.command(venue, STAFF, command))
                val visit = VisitRepository(ds).recordBookingSeatedVisit(booking.id)!!
                assertNull(visit.userId)
                assertEquals(arrival.state(venue, STAFF).tables.first().seating!!.visitId, visit.id)
                assertNull(VisitFeedbackRepository(ds).scheduleFeedbackRequestForVisit(visit.id))
                ds.connection.use { c ->
                    assertEquals(users, c.rows("SELECT COUNT(*) FROM users") { it.getInt(1) }.single())
                    assertEquals(
                        1,
                        c.rows(
                            "SELECT COUNT(*) FROM visits WHERE booking_id = ?",
                            booking.id,
                        ) { it.getInt(1) }.single(),
                    )
                    assertEquals(0, c.rows("SELECT COUNT(*) FROM venue_accounting_shifts") { it.getInt(1) }.single())
                }
            }
        }

    @Test
    fun `guest pending and changed reserve until cancellation and only confirmed can seat`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                prepareGuestChoice(ds, venue, tables)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val guest =
                    cmd(
                        "GUEST_BOOKING",
                    ).copy(tableId = tables[0], localStart = "2030-01-07T20:00", partySize = 2)
                val saved = repo.guestBooking(venue, 1003, guest)
                assertEquals(saved, repo.guestBooking(venue, 1003, guest))
                assertEquals("PENDING", repo.state(venue, OWNER).bookings.single().status)
                val enabledRules = repo.state(venue, OWNER).rules
                repo.command(
                    venue,
                    OWNER,
                    cmd("RULES").copy(
                        revision = enabledRules.revision,
                        rules = enabledRules.copy(usePlan = false, guestTableChoice = false),
                    ),
                )
                assertEquals(saved, repo.guestBooking(venue, 1003, guest))
                assertFailsWith<SeatingConflictException> {
                    repo.guestBooking(venue, 1003, guest.copy(requestId = UUID.randomUUID().toString()))
                }
                repo.command(
                    venue,
                    OWNER,
                    cmd("RULES").copy(
                        revision = enabledRules.revision + 1,
                        rules = enabledRules,
                    ),
                )
                assertFailsWith<SeatingConflictException> {
                    repo.command(
                        venue,
                        STAFF,
                        cmd(
                            "SEAT",
                        ).copy(
                            bookingId = saved.id,
                            tableId = tables[0],
                            revision = repo.state(venue, STAFF).bookings.single().revision,
                        ),
                    )
                }
                val bookings = GuestBookingRepository(ds)
                assertFailsWith<SeatingConflictException> {
                    bookings.updateByGuest(
                        saved.id,
                        venue,
                        1003,
                        Instant.parse("2030-01-07T17:00:00Z"),
                        null,
                        null,
                        ZoneId.of("Europe/Moscow"),
                    )
                }
                assertFailsWith<ForbiddenException> {
                    bookings.updateByVenue(saved.id, venue, BookingStatus.CONFIRMED, actorUserId = STAFF)
                }
                bookings.updateByVenue(saved.id, venue, BookingStatus.CONFIRMED, actorUserId = OWNER)
                bookings.updateByVenue(
                    saved.id,
                    venue,
                    BookingStatus.CHANGED,
                    Instant.parse("2030-01-07T18:00:00Z"),
                    actorUserId = OWNER,
                    venueZoneId = ZoneId.of("Europe/Moscow"),
                )
                assertFailsWith<SeatingConflictException> {
                    repo.command(
                        venue,
                        STAFF,
                        cmd(
                            "SEAT",
                        ).copy(
                            bookingId = saved.id,
                            tableId = tables[0],
                            revision = repo.state(venue, STAFF).bookings.single().revision,
                        ),
                    )
                }
                assertFailsWith<SeatingConflictException> {
                    repo.guestBooking(
                        venue,
                        1003,
                        guest.copy(requestId = UUID.randomUUID().toString(), localStart = "2030-01-07T21:00"),
                    )
                }
                bookings.cancelByGuest(saved.id, venue, 1003)
                val replacement = repo.guestBooking(venue, 1003, guest.copy(requestId = UUID.randomUUID().toString()))
                bookings.updateByVenue(replacement.id, venue, BookingStatus.CONFIRMED, actorUserId = OWNER)
                val lateRepo =
                    SeatingRepository(ds, clock = Clock.fixed(Instant.parse("2030-01-07T17:15:00Z"), ZoneOffset.UTC))
                lateRepo.command(
                    venue,
                    STAFF,
                    cmd("SEAT").copy(bookingId = replacement.id, tableId = tables[0], revision = 1),
                )
                assertEquals(
                    "2030-01-07T19:00:00Z",
                    lateRepo.state(venue, STAFF).tables.single {
                        it.id == tables[0]
                    }.seating!!.agreedEndAt,
                )
                assertEquals(
                    1,
                    ds.connection.use {
                        it.rows(
                            "SELECT COUNT(*) FROM visits WHERE booking_id = ?",
                            replacement.id,
                        ) { r -> r.getInt(1) }.single()
                    },
                )
            }
        }

    @Test
    fun `buffer 10 requires 1950 end and snapshots survive rule changes`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val repo = SeatingRepository(ds, clock = seatingClock)
                repo.command(
                    venue,
                    OWNER,
                    cmd("RULES").copy(revision = 1, rules = SeatingRules(limitDuration = true, bufferMinutes = 10)),
                )
                repo.command(
                    venue,
                    STAFF,
                    cmd(
                        "MANUAL_BOOKING",
                    ).copy(tableId = tables[0], localStart = "2030-01-07T20:00", partySize = 2, name = "Synthetic"),
                )
                val seat =
                    cmd(
                        "SEAT",
                    ).copy(
                        tableId = tables[0],
                        partySize = 2,
                        localEnd = "2030-01-07T20:00",
                        shorterVisitAcknowledged = true,
                    )
                assertFailsWith<SeatingConflictException> { repo.command(venue, STAFF, seat) }
                repo.command(venue, STAFF, seat.copy(localEnd = "2030-01-07T19:50"))
                repo.command(
                    venue,
                    OWNER,
                    cmd("RULES").copy(revision = 2, rules = SeatingRules(limitDuration = false, bufferMinutes = 0)),
                )
                assertFailsWith<SeatingConflictException> {
                    repo.command(
                        venue,
                        STAFF,
                        cmd(
                            "MANUAL_BOOKING",
                        ).copy(
                            tableId = tables[0],
                            localStart = "2030-01-07T22:00",
                            localEnd = "2030-01-07T23:00",
                            partySize = 2,
                            name = "Synthetic",
                        ),
                    )
                }
            }
        }

    @Test
    fun `timezone DST gaps overlaps overnight closed dates and 24h unlimited have explicit boundaries`() =
        runBlocking<Unit> {
            assertFailsWith<InvalidInputException> { localInstant("2030-03-31T02:30", ZoneId.of("Europe/Berlin")) }
            assertFailsWith<InvalidInputException> { localInstant("2030-10-27T02:30", ZoneId.of("Europe/Berlin")) }
            assertEquals(
                Instant.parse("2030-03-31T01:30:00Z"),
                localInstant("2030-03-31T03:30", ZoneId.of("Europe/Berlin")),
            )
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val booking =
                    cmd(
                        "MANUAL_BOOKING",
                    ).copy(tableId = tables[0], partySize = 2, name = "Synthetic", localStart = "2030-01-08T01:00")
                assertFailsWith<SeatingConflictException> { repo.command(venue, STAFF, booking) }
                repo.command(venue, STAFF, booking.copy(localEnd = "2030-01-08T02:00", shorterVisitAcknowledged = true))
                ds.connection.use { c ->
                    c.write(
                        """
                        INSERT INTO venue_booking_hours_overrides(venue_id, service_date, opens_at, closes_at, is_closed)
                        VALUES (?, DATE '2030-01-09', TIME '00:00:00', TIME '00:00:00', TRUE)
                        """.trimIndent(),
                        venue,
                    )
                    c.write(
                        """
                        UPDATE venue_booking_hours SET opens_at = TIME '00:00:00', closes_at = TIME '00:00:00' WHERE
                        venue_id = ?
                        """.trimIndent(),
                        venue,
                    )
                }
                repo.command(venue, OWNER, cmd("RULES").copy(revision = 1, rules = SeatingRules()))
                assertFailsWith<SeatingConflictException> {
                    repo.command(
                        venue,
                        STAFF,
                        booking.copy(localStart = "2030-01-08T20:00"),
                    )
                }
                assertFailsWith<SeatingConflictException> {
                    repo.command(
                        venue,
                        STAFF,
                        booking.copy(localStart = "2030-01-08T20:00", localEnd = "2030-01-10T01:00"),
                    )
                }
                repo.command(venue, STAFF, cmd("SEAT").copy(tableId = tables[1], partySize = 2))
                assertNull(repo.state(venue, STAFF).tables.single { it.id == tables[1] }.seating!!.agreedEndAt)
                val later =
                    SeatingRepository(ds, clock = Clock.fixed(Instant.parse("2030-02-10T12:00:00Z"), ZoneOffset.UTC))
                assertEquals("OCCUPIED", later.state(venue, STAFF).tables.single { it.id == tables[1] }.state)
            }
        }

    @Test
    fun `move retains session visit active order and closed report while old table context and release are isolated`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val id = repo.command(venue, STAFF, cmd("SEAT").copy(tableId = tables[0], partySize = 2)).id
                val before = repo.state(venue, STAFF).tables.single { it.id == tables[0] }.seating!!
                val sessions = TableSessionRepository(ds)
                assertFalse(sessions.closeSession(before.tableSessionId, venue, seatingClock.instant()))
                val order =
                    ds.connection.use {
                            c ->
                        c.insert(
                            "INSERT INTO orders(venue_id,table_id,table_session_id,status) VALUES (?,?,?,'ACTIVE')",
                            venue,
                            tables[0],
                            before.tableSessionId,
                        )
                    }
                assertFailsWith<SeatingConflictException> {
                    repo.command(
                        venue,
                        STAFF,
                        cmd("RELEASE").copy(id = id, revision = 0),
                    )
                }
                repo.command(venue, STAFF, cmd("MOVE").copy(id = id, revision = 0, tableId = tables[1]))
                val moved = repo.state(venue, STAFF).tables.single { it.id == tables[1] }.seating!!
                assertEquals(before.visitId, moved.visitId)
                assertEquals(before.tableSessionId, moved.tableSessionId)
                assertNull(sessions.findSessionForTable(before.tableSessionId, venue, tables[0]))
                assertEquals(
                    before.tableSessionId,
                    sessions.resolveActiveSession(venue, tables[1], Duration.ofHours(2), seatingClock.instant()).id,
                )
                val nextCompany =
                    sessions.resolveActiveSession(
                        venue,
                        tables[0],
                        Duration.ofHours(2),
                        seatingClock.instant(),
                    )
                assertNotEquals(before.tableSessionId, nextCompany.id)
                ds.connection.use { c ->
                    assertEquals(
                        tables[1],
                        c.rows("SELECT table_id FROM orders WHERE id = ?", order) { it.getLong(1) }.single(),
                    )
                    c.write("UPDATE orders SET status = 'CLOSED' WHERE id = ?", order)
                    c.insert(
                        """
                        INSERT INTO venue_accounting_shifts(venue_id, status, timezone, opened_at, closed_at, final_report)
                        VALUES (?,'CLOSED','Europe/Moscow', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,'synthetic-unpaid-12345')
                        """.trimIndent(),
                        venue,
                    )
                }
                assertNull(VisitFeedbackRepository(ds).scheduleFeedbackRequestForVisit(before.visitId))
                repo.command(venue, STAFF, cmd("RELEASE").copy(id = id, revision = 1))
                val afterRelease =
                    sessions.resolveActiveSession(
                        venue,
                        tables[1],
                        Duration.ofHours(2),
                        seatingClock.instant(),
                    )
                assertNotEquals(before.tableSessionId, afterRelease.id)
                ds.connection.use { c ->
                    assertEquals(
                        "synthetic-unpaid-12345",
                        c.rows("SELECT final_report FROM venue_accounting_shifts") {
                            it.getString(1)
                        }.single(),
                    )
                    assertEquals(1, c.rows("SELECT COUNT(*) FROM orders") { it.getInt(1) }.single())
                    assertEquals(1, c.rows("SELECT COUNT(*) FROM visits") { it.getInt(1) }.single())
                    assertEquals(1, c.rows("SELECT COUNT(*) FROM table_seating_moves") { it.getInt(1) }.single())
                }
            }
        }

    @Test
    fun `legacy reconciliation unassigned obligations and revoked replay`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val session =
                    TableSessionRepository(
                        ds,
                    ).resolveActiveSession(venue, tables[0], Duration.ofHours(2), seatingClock.instant())
                assertEquals("RECONCILE", repo.state(venue, STAFF).tables.first().state)
                val seat = cmd("SEAT").copy(tableId = tables[0], partySize = 2)
                assertFailsWith<SeatingConflictException> { repo.command(venue, STAFF, seat) }
                repo.command(venue, STAFF, seat.copy(reconcileLegacySession = true))
                assertEquals(session.id, repo.state(venue, STAFF).tables.first().seating!!.tableSessionId)
                val manual =
                    cmd(
                        "MANUAL_BOOKING",
                    ).copy(localStart = "2030-01-07T20:00", partySize = 2, name = "Unassigned")
                val saved = repo.command(venue, STAFF, manual)
                assertEquals(1, repo.state(venue, OWNER).unresolvedBookings)
                assertFailsWith<SeatingConflictException> {
                    GuestBookingRepository(ds).markSeated(venue, saved.id, STAFF)
                }
                assertFailsWith<SeatingConflictException> { prepareGuestChoice(ds, venue, tables) }
                val booking = repo.state(venue, OWNER).bookings.single()
                repo.command(
                    venue,
                    OWNER,
                    cmd("ASSIGN_BOOKING").copy(id = saved.id, revision = booking.revision, tableId = tables[1]),
                )
                assertEquals(0, repo.state(venue, OWNER).unresolvedBookings)
                val bookings = GuestBookingRepository(ds)
                bookings.updateByVenue(
                    saved.id,
                    venue,
                    BookingStatus.CHANGED,
                    Instant.parse("2030-01-07T18:00:00Z"),
                    actorUserId = OWNER,
                    venueZoneId = ZoneId.of("Europe/Moscow"),
                )
                assertEquals("CONFIRMED", repo.state(venue, OWNER).bookings.single().status)
                val legacyGuest =
                    bookings.create(
                        venue,
                        1003,
                        Instant.parse("2030-01-08T17:00:00Z"),
                        null,
                        null,
                        ZoneId.of("Europe/Moscow"),
                    )
                bookings.updateByVenue(legacyGuest.id, venue, BookingStatus.CONFIRMED, actorUserId = OWNER)
                assertFailsWith<SeatingConflictException> { bookings.markSeated(venue, legacyGuest.id, STAFF) }
                val legacyState = repo.state(venue, OWNER).bookings.single { it.id == legacyGuest.id }
                repo.command(
                    venue,
                    OWNER,
                    cmd("ASSIGN_BOOKING").copy(
                        id = legacyGuest.id,
                        revision = legacyState.revision,
                        tableId = tables[2],
                        partySize = 3,
                    ),
                )
                assertEquals(3, repo.state(venue, OWNER).bookings.single { it.id == legacyGuest.id }.partySize)
                ds.connection.use {
                    it.write(
                        "DELETE FROM venue_members WHERE venue_id = ? AND user_id = ?",
                        venue,
                        STAFF,
                    )
                }
                assertFailsWith<ForbiddenException> { repo.command(venue, STAFF, manual) }
            }
        }
}
