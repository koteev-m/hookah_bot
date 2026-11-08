package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.miniapp.guest.db.BookingStatus
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.moduleWithOverrides
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeatingReviewFixHttpTest {
    @Test
    fun `Hall two client revisions protect SEAT and time changes notify only the owning guest once`() =
        testApplication {
            val url =
                "jdbc:h2:mem:review_fix_http_${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
            val cfg =
                MapApplicationConfig(
                    "app.env" to "test",
                    "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to url,
                    "db.user" to "sa",
                    "db.password" to "",
                    "telegram.enabled" to "false",
                    "billing.subscriptionJob.enabled" to "false",
                )
            environment { config = cfg }
            application { moduleWithOverrides(ModuleOverrides(seatingClock = fixClock(20))) }
            client.get("/health")
            val otherClient = createClient { }
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    username = "sa"
                    password = ""
                },
            ).use { ds ->
                val token = SessionTokenService(SessionTokenConfig.from(cfg, "test")).issueToken(OWNER).token

                suspend fun send(
                    venue: Long,
                    command: SeatingCommand,
                    second: Boolean = false,
                    expected: HttpStatusCode = HttpStatusCode.OK,
                ): String {
                    val response =
                        (if (second) otherClient else client).post("/api/venue/$venue/hall/commands") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(seatingJson.encodeToString(command))
                        }
                    val body = response.bodyAsText()
                    assertEquals(expected, response.status, body)
                    if (expected == HttpStatusCode.Conflict) assertTrue(body.contains("SEATING_CONFLICT"), body)
                    return body
                }
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
                    val rev = repo.state(venue, STAFF).bookings.single().revision
                    val seat = cmd("SEAT").copy(bookingId = b.id, revision = rev)
                    send(
                        venue,
                        cmd(
                            "ASSIGN_BOOKING",
                        ).copy(id = b.id, tableId = tables[0], revision = rev, localEnd = "2030-01-07T23:00"),
                    )
                    val row = fullRow(ds, "bookings", b.id)
                    val effects = sideEffectCounts(ds)
                    send(venue, seat, second = true, expected = HttpStatusCode.Conflict)
                    assertEquals(row, fullRow(ds, "bookings", b.id))
                    assertEquals(effects, sideEffectCounts(ds))
                    val accepted = seat.copy(requestId = UUID.randomUUID().toString(), revision = rev + 1)
                    val result = send(venue, accepted, second = true)
                    assertEquals(result, send(venue, accepted, second = true))
                    send(
                        venue,
                        accepted.copy(requestId = UUID.randomUUID().toString()),
                        expected = HttpStatusCode.Conflict,
                    )
                }
                val (venue, tables) = seedSeating(ds)
                val (foreignVenue, foreignTables) = seedSeating(ds)
                prepareGuestChoice(ds, venue, tables)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val b =
                    repo.guestBooking(
                        venue,
                        1003,
                        cmd(
                            "GUEST_BOOKING",
                        ).copy(
                            tableId = tables[0],
                            partySize = 2,
                            localStart = "2030-01-07T20:00",
                            comment = "Private guest note",
                        ),
                    )
                GuestBookingRepository(ds).updateByVenue(b.id, venue, BookingStatus.CONFIRMED, actorUserId = OWNER)
                val change =
                    cmd(
                        "ASSIGN_BOOKING",
                    ).copy(id = b.id, revision = 1, tableId = tables[0], localStart = "2030-01-07T21:00")
                val before = sideEffectCounts(ds)[1]
                val result = send(venue, change)
                assertEquals(result, send(venue, change))
                send(
                    venue,
                    change.copy(requestId = UUID.randomUUID().toString(), localStart = "2030-01-07T22:00"),
                    second = true,
                    expected = HttpStatusCode.Conflict,
                )
                send(
                    foreignVenue,
                    change.copy(requestId = UUID.randomUUID().toString(), tableId = foreignTables[0]),
                    expected = HttpStatusCode.NotFound,
                )
                val unchanged = change.copy(requestId = UUID.randomUUID().toString(), revision = 2, tableId = tables[1])
                send(venue, unchanged)
                val blocker = repo.command(venue, STAFF, offline(tables[2], "2030-01-07T22:00"))
                send(
                    venue,
                    change.copy(
                        requestId = UUID.randomUUID().toString(),
                        revision = 3,
                        tableId = tables[2],
                        localStart = "2030-01-07T22:00",
                    ),
                    expected = HttpStatusCode.Conflict,
                )
                assertEquals(before + 1, sideEffectCounts(ds)[1])
                ds.connection.use { c ->
                    val messages =
                        c.rows(
                            "SELECT chat_id, payload_json, status FROM telegram_outbox WHERE dedupe_key = ?",
                            "hall-time:$venue:${b.id}:$OWNER:${change.requestId}",
                        ) {
                            Triple(it.getLong(1), it.getString(2), it.getString(3))
                        }
                    assertEquals(1, messages.size)
                    assertEquals(1003L, messages.single().first)
                    assertEquals("NEW", messages.single().third)
                    assertTrue(messages.single().second.contains("21:00"))
                    assertFalse(messages.single().second.contains("Private guest note"))
                }
                // Offline contacts have no Telegram recipient and retain the established save contract.
                send(
                    venue,
                    cmd(
                        "ASSIGN_BOOKING",
                    ).copy(id = blocker.id, revision = 0, tableId = tables[2], localStart = "2030-01-08T20:00"),
                )
                assertEquals(before + 1, sideEffectCounts(ds)[1])
                assertEquals("CHANGED", fullRow(ds, "bookings", b.id)["status"])
            }
        }

    @Test
    fun `unavailable guest delivery retains save contract and transactional reminder scheduling`() =
        testApplication {
            val url =
                "jdbc:h2:mem:review_fix_policy_${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
            val cfg =
                MapApplicationConfig(
                    "app.env" to "test", "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to url, "db.user" to "sa", "db.password" to "",
                    "telegram.enabled" to "false", "billing.subscriptionJob.enabled" to "false",
                    "telegram.trafficPolicy" to "ALLOWLIST", "telegram.allowedUserIds" to "1001",
                    "telegram.allowedChatIds" to "1001", "booking.reminders.enabled" to "true",
                    "booking.reminders.intervalSeconds" to "3600",
                )
            environment { config = cfg }
            application { moduleWithOverrides(ModuleOverrides(seatingClock = seatingClock)) }
            client.get("/health")
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    username = "sa"
                    password = ""
                },
            ).use { ds ->
                val (venue, tables) = seedSeating(ds)
                prepareGuestChoice(ds, venue, tables)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val b =
                    repo.guestBooking(
                        venue,
                        1003,
                        cmd("GUEST_BOOKING").copy(
                            tableId = tables[0],
                            partySize = 2,
                            localStart = "2030-01-07T20:00",
                        ),
                    )
                GuestBookingRepository(ds).updateByVenue(b.id, venue, BookingStatus.CONFIRMED, actorUserId = OWNER)
                val token = SessionTokenService(SessionTokenConfig.from(cfg, "test")).issueToken(OWNER).token
                val change =
                    cmd("ASSIGN_BOOKING").copy(
                        id = b.id,
                        revision = 1,
                        tableId = tables[0],
                        localStart = "2030-01-07T21:00",
                    )

                suspend fun send(): String {
                    val response =
                        client.post("/api/venue/$venue/hall/commands") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(seatingJson.encodeToString(change))
                        }
                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    return response.bodyAsText()
                }
                val saved = send()
                assertEquals(SeatingResult(b.id, 2), seatingJson.decodeFromString(saved))
                val effects = sideEffectCounts(ds)
                assertEquals(0, effects[1])

                fun reminders() =
                    ds.connection.use { c ->
                        c.rows(
                            "SELECT id, status, dedupe_key FROM booking_reminders WHERE booking_id = ? ORDER BY id",
                            b.id,
                        ) {
                            Triple(it.getLong(1), it.getString(2), it.getString(3))
                        }
                    }
                val planned = reminders()
                assertTrue(planned.isNotEmpty())
                assertTrue(planned.all { it.second in setOf("PENDING", "SKIPPED") })
                assertEquals(saved, send())
                assertEquals(effects, sideEffectCounts(ds))
                assertEquals(planned, reminders())
                assertEquals("CHANGED", fullRow(ds, "bookings", b.id)["status"])
            }
        }
}
