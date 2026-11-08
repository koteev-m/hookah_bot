package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.SeatingConflictException
import com.hookah.platform.backend.miniapp.venue.tables.VenueTableRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val OWNER = 1001L
internal const val STAFF = 1002L
internal val seatingClock: Clock = Clock.fixed(Instant.parse("2030-01-07T15:00:00Z"), ZoneOffset.UTC)

internal fun cmd(action: String) = SeatingCommand(UUID.randomUUID().toString(), action)

internal fun h2Seating(): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:halls_${UUID.randomUUID()};MODE=PostgreSQL;" +
                "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
            username = "sa"
            password = ""
            maximumPoolSize = 6
        },
    ).also { Flyway.configure().dataSource(it).locations("classpath:db/migration/h2").load().migrate() }

internal suspend fun seedSeating(ds: HikariDataSource): Pair<Long, List<Long>> {
    val venue =
        ds.connection.use { c ->
            for (actor in listOf(OWNER, STAFF, 1003L)) {
                if (c.rows("SELECT telegram_user_id FROM users WHERE telegram_user_id = ?", actor) {
                        it.getLong(1)
                    }.isEmpty()
                ) {
                    c.write("INSERT INTO users(telegram_user_id) VALUES (?)", actor)
                }
            }
            val id =
                c.insert(
                    """
                    INSERT INTO venues(name, city, address, status) VALUES ('Synthetic hall','Test','Test','PUBLISHED')
                    """.trimIndent(),
                )
            c.write(
                "INSERT INTO venue_members(venue_id,user_id,role) VALUES (?,?,'OWNER'), (?,?,'STAFF')",
                id,
                OWNER,
                id,
                STAFF,
            )
            c.write("INSERT INTO venue_settings(venue_id,timezone) VALUES (?,'Europe/Moscow')", id)
            for (day in 1..7) c.write(
                """
                INSERT INTO venue_booking_hours(venue_id, weekday, opens_at, closes_at, is_closed) VALUES (?, ?,
                TIME '10:00:00', TIME '02:00:00', FALSE)
                """.trimIndent(),
                id,
                day,
            )
            id
        }
    val tables = VenueTableRepository(ds).batchCreateTables(venue, listOf(1, 3, 5)).map { it.tableId }
    val repo = SeatingRepository(ds, clock = seatingClock)
    tables.forEachIndexed {
            i,
            id,
        ->
        repo.command(
            venue,
            OWNER,
            cmd("TABLE").copy(id = id, revision = 0, number = listOf(1, 3, 5)[i], capacity = 4, active = true),
        )
    }
    repo.command(
        venue,
        OWNER,
        cmd("RULES").copy(revision = 0, rules = SeatingRules(limitDuration = true, durationMinutes = 120)),
    )
    return venue to tables
}

class SeatingRepositoryTest {
    @Test
    fun `manual offline reservation interval shortened seating move release and exact replay`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val request =
                    cmd(
                        "MANUAL_BOOKING",
                    ).copy(
                        tableId = tables[1],
                        localStart = "2030-01-07T20:00",
                        partySize = 3,
                        name = "Телефонный гость",
                        phone = "+70000000000",
                    )
                val booked = repo.command(venue, STAFF, request)
                assertEquals(booked, repo.command(venue, STAFF, request))
                val booking = repo.state(venue, STAFF).bookings.single()
                assertEquals("CONFIRMED", booking.status)
                assertEquals("STAFF", booking.source)
                assertEquals("2030-01-07T19:00:00Z", booking.agreedEndAt)
                ds.connection.use {
                        c ->
                    assertNull(
                        c.rows(
                            "SELECT user_id FROM bookings WHERE id = ?",
                            booked.id,
                        ) { it.longOrNull("user_id") }.single(),
                    )
                }
                assertFailsWith<SeatingConflictException> {
                    repo.command(venue, OWNER, request.copy(requestId = UUID.randomUUID().toString()))
                }
                val seated = repo.command(venue, STAFF, cmd("SEAT").copy(tableId = tables[1], partySize = 2))
                val state = repo.state(venue, STAFF)
                assertEquals("OCCUPIED", state.tables.single { it.id == tables[1] }.state)
                val visit = state.tables.single { it.id == tables[1] }.seating!!.visitId
                assertFailsWith<SeatingConflictException> {
                    repo.command(
                        venue,
                        STAFF,
                        cmd("EXTEND").copy(id = seated.id, revision = 0, localEnd = "2030-01-07T20:30"),
                    )
                }
                val moved =
                    repo.command(
                        venue,
                        STAFF,
                        cmd("MOVE").copy(id = seated.id, revision = 0, tableId = tables[2]),
                    )
                assertEquals(visit, repo.state(venue, STAFF).tables.single { it.id == tables[2] }.seating!!.visitId)
                val result = repo.command(venue, STAFF, cmd("RELEASE").copy(id = seated.id, revision = moved.revision))
                assertEquals(2, result.revision)
                ds.connection.use { c ->
                    assertEquals(0, c.rows("SELECT COUNT(*) FROM orders") { it.getInt(1) }.single())
                    assertEquals(0, c.rows("SELECT COUNT(*) FROM venue_accounting_shifts") { it.getInt(1) }.single())
                    assertEquals(1, c.rows("SELECT COUNT(*) FROM visits") { it.getInt(1) }.single())
                }
            }
        }

    @Test
    fun `explicit shortened visits buffer and immutable agreed end`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val now = Clock.fixed(Instant.parse("2030-01-07T15:30:00Z"), ZoneOffset.UTC)
                val repo = SeatingRepository(ds, clock = now)
                repo.command(
                    venue,
                    STAFF,
                    cmd(
                        "MANUAL_BOOKING",
                    ).copy(tableId = tables[1], localStart = "2030-01-07T20:00", partySize = 2, name = "Офлайн"),
                )
                val seating = cmd("SEAT").copy(tableId = tables[1], partySize = 2, localEnd = "2030-01-07T20:00")
                assertFailsWith<SeatingConflictException> { repo.command(venue, STAFF, seating) }
                repo.command(venue, STAFF, seating.copy(shorterVisitAcknowledged = true))
                val original = repo.state(venue, OWNER).bookings.single().agreedEndAt
                repo.command(
                    venue,
                    OWNER,
                    cmd(
                        "RULES",
                    ).copy(
                        revision = 1,
                        rules = SeatingRules(limitDuration = true, durationMinutes = 90, bufferMinutes = 10),
                    ),
                )
                assertEquals(original, repo.state(venue, OWNER).bookings.single().agreedEndAt)
                assertFailsWith<SeatingConflictException> {
                    repo.command(
                        venue,
                        STAFF,
                        cmd(
                            "MANUAL_BOOKING",
                        ).copy(tableId = tables[1], localStart = "2030-01-07T19:50", partySize = 2, name = "Другой"),
                    )
                }
            }
        }

    @Test
    fun `staff settings denied and rollback audit failure leaves no booking or operation`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val repo = SeatingRepository(ds, clock = seatingClock)
                assertFailsWith<ForbiddenException> {
                    repo.command(
                        venue,
                        STAFF,
                        cmd("HALL").copy(name = "Недоступно"),
                    )
                }
                assertFailsWith<ForbiddenException> {
                    repo.command(
                        venue,
                        1003,
                        cmd("MANUAL_BOOKING").copy(name = "Чужой"),
                    )
                }
                val bad =
                    SeatingRepository(ds, clock = seatingClock, checkpoint = {
                            phase,
                            _,
                        ->
                        if (phase == "BEFORE_COMMIT") error("synthetic rollback")
                    })
                val request =
                    cmd(
                        "MANUAL_BOOKING",
                    ).copy(tableId = tables[1], localStart = "2030-01-07T20:00", partySize = 2, name = "Офлайн")
                assertFailsWith<IllegalStateException> { bad.command(venue, STAFF, request) }
                assertTrue(repo.state(venue, OWNER).bookings.isEmpty())
                ds.connection.use {
                        c ->
                    assertEquals(
                        0,
                        c.rows("SELECT COUNT(*) FROM audit_log WHERE action = 'SEATING_MANUAL_BOOKING'") {
                            it.getInt(1)
                        }.single(),
                    )
                }
                repo.command(venue, STAFF, request)
            }
        }

    @Test
    fun `halls and geometry preserve table and QR identity including unknown capacity`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val tokens = VenueTableRepository(ds).listTablesWithTokens(venue)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val hall = repo.command(venue, OWNER, cmd("HALL").copy(name = "Первый"))
                val other = repo.command(venue, OWNER, cmd("HALL").copy(name = "Второй", active = false))
                assertFalse(repo.state(venue, OWNER).halls.single { it.id == other.id }.active)
                assertFailsWith<SeatingConflictException> {
                    repo.command(
                        venue,
                        OWNER,
                        cmd("HALL").copy(name = "Первый"),
                    )
                }
                repo.command(
                    venue,
                    OWNER,
                    cmd("HALL").copy(id = other.id, revision = 0, name = "Переименован", active = true),
                )
                assertTrue(repo.state(venue, OWNER).halls.single { it.id == other.id }.active)
                repo.command(
                    venue,
                    OWNER,
                    cmd(
                        "TABLE",
                    ).copy(
                        id = tables[0],
                        revision = 1,
                        number = 9,
                        hallId = hall.id,
                        layout = TableLayout(.3, .4, .1, .15, 45.0, "ELLIPSE"),
                    ),
                )
                val state = repo.state(venue, OWNER)
                assertEquals(2, state.halls.size)
                val t = state.tables.single { it.id == tables[0] }
                assertNull(t.capacity)
                assertEquals(45.0, t.layout!!.rotation)
                assertEquals(
                    tokens.map {
                        it.token
                    }.toSet(),
                    VenueTableRepository(ds).listTablesWithTokens(venue).map { it.token }.toSet(),
                )
                assertFalse(state.rules.usePlan)
                assertFalse(state.rules.guestTableChoice)
            }
        }

    @Test
    fun `unlimited has no two hour promise and overdue stays occupied`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val repo = SeatingRepository(ds, clock = seatingClock)
                repo.command(venue, OWNER, cmd("RULES").copy(revision = 1, rules = SeatingRules()))
                repo.command(venue, STAFF, cmd("SEAT").copy(tableId = tables[0], partySize = 2))
                val seat = repo.state(venue, STAFF).tables.single { it.id == tables[0] }.seating!!
                assertEquals("2030-01-07T23:00:00Z", seat.agreedEndAt)
                val late =
                    SeatingRepository(ds, clock = Clock.fixed(Instant.parse("2030-01-08T10:00:00Z"), ZoneOffset.UTC))
                assertEquals("OVERDUE", late.state(venue, STAFF).tables.single { it.id == tables[0] }.state)
            }
        }
}
