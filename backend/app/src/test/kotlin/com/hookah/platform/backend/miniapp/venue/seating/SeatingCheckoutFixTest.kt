package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.api.OrderIdempotencyPayloadMismatchException
import com.hookah.platform.backend.api.SeatingConflictException
import com.hookah.platform.backend.miniapp.guest.db.BookingStatus
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.venue.tables.VenueTableRepository
import com.hookah.platform.backend.telegram.db.CreatedOrderBatch
import com.hookah.platform.backend.telegram.db.GuestOrderWriteCheckpoint
import com.hookah.platform.backend.telegram.db.OrderBatchItemInput
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal data class CheckoutSeat(
    val venue: Long,
    val tables: List<Long>,
    val seat: Long,
    val session: Long,
    val tab: Long,
    val token: String,
)

internal suspend fun checkoutSeat(ds: HikariDataSource): CheckoutSeat {
    val (venue, tables) = seedSeating(ds)
    val repo = SeatingRepository(ds, clock = seatingClock)
    val seat = repo.command(venue, STAFF, cmd("SEAT").copy(tableId = tables[0], partySize = 2))
    val session = repo.state(venue, STAFF).tables[0].seating!!.tableSessionId
    val tab = GuestTabsRepository(ds).ensurePersonalTab(venue, session, 1003).id
    val token = VenueTableRepository(ds).listTablesWithTokens(venue).single { it.tableId == tables[0] }.token!!
    return CheckoutSeat(venue, tables, seat.id, session, tab, token)
}

internal suspend fun checkout(
    ds: DataSource,
    seat: CheckoutSeat,
    key: String,
    connected: Boolean = false,
    items: List<OrderBatchItemInput> = emptyList(),
    hook: (GuestOrderWriteCheckpoint) -> Unit = {},
): CreatedOrderBatch? {
    val repo = OrdersRepository(ds, clock = seatingClock, guestOrderWriteCheckpoint = hook)
    if (!connected) {
        return repo.createGuestOrderBatch(
            tableId = seat.tables[0], venueId = seat.venue, tableSessionId = seat.session,
            userId = 1003, idempotencyKey = key, tabId = seat.tab, comment = "Synthetic checkout", items = items,
            venueZoneId = ZoneId.of("Europe/Moscow"), chatOrder = items.isEmpty(), expectedTableToken = seat.token,
        )
    }
    return ds.connection.use { c ->
        c.autoCommit = false
        try {
            repo.createGuestOrderBatch(
                c, seat.tables[0], seat.venue, seat.session, 1003, key, seat.tab, "Synthetic checkout", items,
                venueZoneId = ZoneId.of("Europe/Moscow"), chatOrder = items.isEmpty(), expectedTableToken = seat.token,
            ).also { c.commit() }
        } catch (e: Exception) {
            c.rollback()
            throw e
        }
    }
}

internal fun checkoutCounts(ds: DataSource): Map<String, Int> =
    ds.connection.use { c ->
        listOf(
            "orders",
            "order_batches",
            "guest_batch_idempotency",
            "audit_log",
            "telegram_outbox",
            "venue_accounting_shifts",
        ).associateWith { table ->
            c.rows("SELECT COUNT(*) FROM $table") { it.getInt(1) }.single()
        }
    }

class SeatingCheckoutFixTest {
    @Test
    fun `booking SEAT rejects differing terms without effects but accepts matching or omitted legacy fields`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                for (explicit in listOf(false, true)) for (guest in listOf(false, true)) {
                    val (venue, tables) = seedSeating(ds)
                    val initial = SeatingRepository(ds, clock = seatingClock)
                    val booking =
                        if (guest) {
                            prepareGuestChoice(ds, venue, tables)
                            initial.guestBooking(
                                venue,
                                1003,
                                cmd("GUEST_BOOKING").copy(
                                    tableId = tables[0],
                                    partySize = 2,
                                    localStart = "2030-01-07T20:00",
                                ),
                            ).also {
                                GuestBookingRepository(ds).updateByVenue(
                                    it.id,
                                    venue,
                                    BookingStatus.CONFIRMED,
                                    actorUserId = OWNER,
                                )
                            }
                        } else {
                            initial.command(venue, STAFF, offline(tables[0]))
                        }
                    val revision = initial.state(venue, STAFF).bookings.single().revision
                    val repo = SeatingRepository(ds, clock = fixClock(20))
                    val before = fullRow(ds, "bookings", booking.id)
                    val effects = sideEffectCounts(ds)
                    val command = cmd("SEAT").copy(bookingId = booking.id, revision = revision)
                    for (bad in listOf(
                        command.copy(partySize = 5),
                        command.copy(localEnd = "2030-01-07T21:00", shorterVisitAcknowledged = true),
                        command.copy(partySize = 5, localEnd = "2030-01-07T21:00"),
                        command.copy(revision = revision + 1),
                    )) {
                        assertFailsWith<SeatingConflictException> { repo.command(venue, STAFF, bad) }
                        assertEquals(before, fullRow(ds, "bookings", booking.id))
                        assertEquals(effects, sideEffectCounts(ds))
                        assertNull(repo.state(venue, STAFF).tables[0].seating)
                    }
                    val valid = if (explicit) command.copy(partySize = 2, localEnd = "2030-01-07T22:00") else command
                    val saved = repo.command(venue, STAFF, valid)
                    assertEquals(saved, repo.command(venue, STAFF, valid))
                    val seat = repo.state(venue, STAFF).tables[0].seating!!
                    assertEquals(2, seat.partySize)
                    assertEquals("2030-01-07T19:00:00Z", seat.agreedEndAt)
                }
            }
        }

    @Test
    fun `chat checkout rejects released moved closed tab and revoked QR contexts without writes`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val modes = listOf("RELEASE", "MOVE", "TAB", "TOKEN", "EXPIRED")
                for (mode in modes) for (connected in listOf(false, true)) {
                    val s = checkoutSeat(ds)
                    if (mode in listOf("RELEASE", "MOVE")) {
                        SeatingRepository(ds, clock = seatingClock).command(
                            s.venue,
                            STAFF,
                            cmd(mode).copy(id = s.seat, revision = 0, tableId = s.tables[1]),
                        )
                    } else {
                        ds.connection.use { c ->
                            if (mode == "TAB") {
                                c.write("UPDATE tab SET status = 'CLOSED' WHERE id = ?", s.tab)
                            } else if (mode == "EXPIRED") {
                                c.write(
                                    "UPDATE table_sessions SET expires_at = ? WHERE id = ?",
                                    seatingClock.instant(),
                                    s.session,
                                )
                            } else {
                                c.write("UPDATE table_tokens SET is_active = FALSE WHERE token = ?", s.token)
                            }
                        }
                    }
                    val before = checkoutCounts(ds)
                    assertNull(checkout(ds, s, UUID.randomUUID().toString(), connected))
                    assertEquals(before, checkoutCounts(ds))
                    if (mode in listOf("RELEASE", "MOVE", "EXPIRED")) {
                        assertNull(
                            OrdersRepository(ds, clock = seatingClock).getOrCreateActiveOrderId(
                                s.tables[0],
                                s.venue,
                                s.session,
                            ),
                        )
                        assertEquals(before, checkoutCounts(ds))
                    }
                }
            }
        }

    @Test
    fun `chat checkout replay fingerprint and failures preserve atomic batch source author and accounting`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                for (connected in listOf(false, true)) {
                    val s = checkoutSeat(ds)
                    val key = UUID.randomUUID().toString()
                    val before = checkoutCounts(ds)
                    for (phase in GuestOrderWriteCheckpoint.entries) {
                        assertFailsWith<IllegalStateException> {
                            checkout(ds, s, key, connected) { if (it == phase) error("Synthetic checkout rollback") }
                        }
                        assertEquals(before, checkoutCounts(ds))
                    }
                    val saved = assertNotNull(checkout(ds, s, key, connected))
                    val after = checkoutCounts(ds)
                    val replay = assertNotNull(checkout(ds, s, key, !connected))
                    assertTrue(replay.idempotencyReplay)
                    assertEquals(saved.batchId, replay.batchId)
                    assertEquals(after, checkoutCounts(ds))
                    assertFailsWith<OrderIdempotencyPayloadMismatchException> {
                        OrdersRepository(ds, clock = seatingClock).createGuestOrderBatch(
                            s.tables[0], s.venue, s.session, 1003, key, s.tab, "Changed text", emptyList(),
                            chatOrder = true, expectedTableToken = s.token,
                        )
                    }
                    assertEquals(after, checkoutCounts(ds))
                    val batch = fullRow(ds, "order_batches", saved.batchId)
                    assertEquals("CHAT", batch["source"])
                    assertEquals("1003", batch["author_user_id"])
                    assertEquals(s.tab.toString(), batch["tab_id"])
                    assertNull(fullRow(ds, "orders", saved.orderId)["accounting_shift_id"])
                    assertEquals(before["venue_accounting_shifts"], after["venue_accounting_shifts"])
                    assertEquals(before["telegram_outbox"], after["telegram_outbox"])
                }
            }
        }
}
