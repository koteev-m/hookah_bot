package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.ModuleOverrides
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
import kotlin.test.assertTrue

class SeatingCheckoutFixHttpTest {
    @Test
    fun `HTTP SEAT rejects changed terms and STAFF assignment but accepts agreed booking and walk in`() =
        testApplication {
            val url =
                "jdbc:h2:mem:p1p2_http_${UUID.randomUUID()};MODE=PostgreSQL;" +
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
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    username = "sa"
                    password = ""
                },
            ).use { ds ->
                val (venue, tables) = seedSeating(ds)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val booking = repo.command(venue, STAFF, offline(tables[0]))
                val token = SessionTokenService(SessionTokenConfig.from(cfg, "test")).issueToken(STAFF).token

                suspend fun send(
                    command: SeatingCommand,
                    expected: HttpStatusCode,
                ): String {
                    val response =
                        client.post("/api/venue/$venue/hall/commands") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(seatingJson.encodeToString(command))
                        }
                    assertEquals(expected, response.status, response.bodyAsText())
                    return response.bodyAsText()
                }
                val before = fullRow(ds, "bookings", booking.id)
                val effects = sideEffectCounts(ds)
                val seat = cmd("SEAT").copy(bookingId = booking.id, revision = 0)
                for (invalid in listOf(seat.copy(partySize = 5), seat.copy(localEnd = "2030-01-07T21:00"))) {
                    assertTrue(send(invalid, HttpStatusCode.Conflict).contains("Посадка сохраняет"))
                    assertEquals(before, fullRow(ds, "bookings", booking.id))
                    assertEquals(effects, sideEffectCounts(ds))
                }
                send(seat.copy(revision = 1), HttpStatusCode.Conflict)
                send(
                    cmd("ASSIGN_BOOKING").copy(id = booking.id, revision = 0, tableId = tables[0], partySize = 5),
                    HttpStatusCode.Forbidden,
                )
                assertEquals(before, fullRow(ds, "bookings", booking.id))
                assertEquals(effects, sideEffectCounts(ds))
                val result = send(seat.copy(partySize = 2, localEnd = "2030-01-07T22:00"), HttpStatusCode.OK)
                assertEquals(result, send(seat.copy(partySize = 2, localEnd = "2030-01-07T22:00"), HttpStatusCode.OK))
                send(
                    cmd("SEAT").copy(
                        tableId = tables[1],
                        partySize = 3,
                        localEnd = "2030-01-07T21:00",
                        shorterVisitAcknowledged = true,
                    ),
                    HttpStatusCode.OK,
                )
                val state = repo.state(venue, STAFF)
                assertEquals(2, state.tables[0].seating!!.partySize)
                assertEquals("2030-01-07T19:00:00Z", state.tables[0].seating!!.agreedEndAt)
                assertEquals(3, state.tables[1].seating!!.partySize)
                assertEquals("2030-01-07T18:00:00Z", state.tables[1].seating!!.agreedEndAt)
            }
        }
}
