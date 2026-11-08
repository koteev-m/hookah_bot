package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.test.PostgresTestEnv
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeatingMigrationTest {
    @Test
    fun `H2 forward migration preserves legacy QR bookings sessions visits and closed financial snapshot`() {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:seating_forward_${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
                username = "sa"
                password = ""
            },
        ).use { verifyForward(it, "h2", "132") }
    }

    @Test
    fun `PostgreSQL forward migration preserves legacy QR bookings sessions visits and closed financial snapshot`() {
        PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase(), migrate = false).use {
            verifyForward(it, "postgresql", "131")
        }
    }

    private fun verifyForward(
        ds: HikariDataSource,
        dialect: String,
        previous: String,
    ) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/$dialect").target(previous).load().migrate()
        val snapshot = "{\"syntheticClosedReport\":true,\"paidMinor\":12345}"
        val values =
            ds.connection.use { c ->
                c.write("INSERT INTO users(telegram_user_id) VALUES (2101)")
                val venue =
                    c.insert(
                        """
                        INSERT INTO venues(name, city, address, status) VALUES ('Migration venue','Test','Test','PUBLISHED')
                        """.trimIndent(),
                    )
                val table =
                    c.insert(
                        "INSERT INTO venue_tables(venue_id,table_number,is_active) VALUES (?,3,TRUE)",
                        venue,
                    )
                c.write(
                    "INSERT INTO table_tokens(table_id,token,is_active) VALUES (?,'synthetic-migration-qr',TRUE)",
                    table,
                )
                val booking =
                    c.insert(
                        """
                        INSERT INTO bookings(venue_id, user_id, scheduled_at, party_size, status, display_date,
                        display_number) VALUES (?, 2101, CURRENT_TIMESTAMP, 2,'SEATED', CURRENT_DATE, 1)
                        """.trimIndent(),
                        venue,
                    )
                val session =
                    c.insert(
                        """
                        INSERT INTO table_sessions(venue_id, table_id, started_at, last_activity_at, expires_at, ended_at,
                        status) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP,'ENDED')
                        """.trimIndent(),
                        venue,
                        table,
                    )
                val shift =
                    c.insert(
                        """
                        INSERT INTO venue_accounting_shifts(venue_id, status, timezone, opened_at, closed_at, final_report)
                        VALUES (?,'CLOSED','Europe/Moscow', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                        """.trimIndent(),
                        venue,
                        snapshot,
                    )
                val order =
                    c.insert(
                        """
                        INSERT INTO orders(venue_id, table_id, table_session_id, status, accounting_shift_id) VALUES (?, ?,
                        ?,'CLOSED', ?)
                        """.trimIndent(),
                        venue,
                        table,
                        session,
                        shift,
                    )
                val visit =
                    c.insert(
                        """
                        INSERT INTO visits(venue_id, user_id, booking_id, table_session_id, order_id, source, occurred_at)
                        VALUES (?, 2101, ?, ?, ?,'MERGED', CURRENT_TIMESTAMP)
                        """.trimIndent(),
                        venue,
                        booking,
                        session,
                        order,
                    )
                listOf(venue, table, booking, session, shift, order, visit)
            }
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/$dialect").load().migrate()
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/$dialect").load().validate()
        ds.connection.use { c ->
            assertEquals(
                "synthetic-migration-qr",
                c.rows(
                    "SELECT token FROM table_tokens WHERE table_id = ?",
                    values[1],
                ) {
                    it.getString(1)
                }.single(),
            )
            val table =
                c.rows("SELECT table_number,capacity,hall_id FROM venue_tables WHERE id = ?", values[1]) {
                    Triple(it.getInt(1), it.intOrNull("capacity"), it.longOrNull("hall_id"))
                }.single()
            assertEquals(3, table.first)
            assertNull(table.second)
            assertNull(table.third)
            val booking =
                c.rows(
                    "SELECT user_id,table_id,agreed_end_at,offline_guest_name FROM bookings WHERE id = ?",
                    values[2],
                ) {
                    listOf(it.getObject(1), it.getObject(2), it.getObject(3), it.getObject(4))
                }.single()
            assertEquals(2101L, (booking[0] as Number).toLong())
            assertEquals(listOf(null, null, null), booking.drop(1))
            assertEquals(
                snapshot,
                c.rows("SELECT final_report FROM venue_accounting_shifts WHERE id = ?", values[4]) {
                    it.getString(1)
                }.single(),
            )
            assertEquals(
                values[5],
                c.rows("SELECT order_id FROM visits WHERE id = ?", values[6]) { it.getLong(1) }.single(),
            )
            assertEquals(
                values[3],
                c.rows("SELECT table_session_id FROM orders WHERE id = ?", values[5]) {
                    it.getLong(1)
                }.single(),
            )
            assertEquals(0, c.rows("SELECT COUNT(*) FROM table_seatings") { it.getInt(1) }.single())
        }
    }
}
