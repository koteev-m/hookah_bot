package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import org.flywaydb.core.Flyway
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuestExitEventMigrationTest {
    @Test
    fun `postgres backfills populated exit markers and generates a fresh identity per exit`() {
        val db = PostgresTestEnv.createDatabase()
        verifyMigration(db.jdbcUrl, db.user, db.password, "postgresql", 140)
    }

    @Test
    fun `h2 backfills populated exit markers and generates a fresh identity per exit`() {
        val url = "jdbc:h2:mem:exits-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        verifyMigration(url, "sa", "", "h2", 141)
    }

    @Test
    fun `postgres preserves legacy chat without granting session access`() {
        val db = PostgresTestEnv.createDatabase()
        verifyChatBinding(db.jdbcUrl, db.user, db.password, "postgresql", 141)
    }

    @Test
    fun `h2 preserves legacy chat without granting session access`() {
        val url = "jdbc:h2:mem:chat-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        verifyChatBinding(url, "sa", "", "h2", 142)
    }

    private fun verifyChatBinding(
        url: String,
        user: String,
        password: String,
        dialect: String,
        previous: Int,
    ) {
        fun flyway(target: Int) =
            Flyway.configure().dataSource(url, user, password)
                .locations("classpath:db/migration/$dialect").target(target.toString()).load()

        flyway(previous).migrate()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { sql ->
                if (dialect == "h2") {
                    sql.execute(
                        "CREATE TABLE telegram_chat_context " +
                            "(chat_id BIGINT PRIMARY KEY, user_id BIGINT, table_token VARCHAR(64))",
                    )
                }
                sql.executeUpdate("INSERT INTO users (telegram_user_id, first_name) VALUES (501, 'Guest')")
                sql.executeUpdate(
                    "INSERT INTO telegram_chat_context (chat_id, user_id, table_token) VALUES (601, 501, 'old')",
                )
                val current = flyway(previous + 1)
                assertEquals(1, current.migrate().migrationsExecuted)
                current.validate()
                sql.executeQuery(
                    "SELECT table_token, table_session_id FROM telegram_chat_context WHERE chat_id = 601",
                ).use {
                    assertTrue(it.next())
                    assertEquals("old", it.getString(1))
                    assertEquals(null, it.getObject(2))
                }
                assertFailsWith<SQLException> {
                    sql.executeUpdate("UPDATE telegram_chat_context SET table_session_id = 9999 WHERE chat_id = 601")
                }
                assertEquals(0, current.migrate().migrationsExecuted)
            }
        }
    }

    private fun verifyMigration(
        url: String,
        user: String,
        password: String,
        dialect: String,
        previous: Int,
    ) {
        fun migrate(target: Int) =
            Flyway.configure().dataSource(url, user, password)
                .locations("classpath:db/migration/$dialect").target(target.toString()).load().migrate()
        migrate(previous)
        val at = Instant.parse("2026-01-01T00:00:00Z")
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use {
                it.executeUpdate("INSERT INTO users (telegram_user_id, first_name) VALUES (501, 'A'), (502, 'B')")
                it.executeUpdate(
                    "INSERT INTO venues (id, name, city, address, status) " +
                        "OVERRIDING SYSTEM VALUE VALUES (101, 'Venue', 'City', 'Address', 'PUBLISHED')",
                )
                it.executeUpdate(
                    "INSERT INTO venue_tables (id, venue_id, table_number) " +
                        "OVERRIDING SYSTEM VALUE VALUES (201, 101, 1)",
                )
                it.executeUpdate(
                    "INSERT INTO table_sessions (id, venue_id, table_id, " +
                        "started_at, last_activity_at, expires_at, status) " +
                        "OVERRIDING SYSTEM VALUE VALUES (301, 101, 201, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'ACTIVE')",
                )
            }
            val insert =
                "INSERT INTO guest_table_session_exits (user_id, table_session_id, exited_at) VALUES (?, 301, ?)"
            connection.prepareStatement(insert).use { statement ->
                for (actor in listOf(501L, 502L)) {
                    statement.setLong(1, actor)
                    statement.setTimestamp(2, Timestamp.from(at))
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertEquals(1, migrate(previous + 1).migrationsExecuted)
            val table = "guest_table_session_exits"
            val meta = connection.metaData
            meta.getPrimaryKeys(null, connection.schema, table).use { keys ->
                val columns = buildSet { while (keys.next()) add(keys.getString("COLUMN_NAME")) }
                assertEquals(setOf("user_id", "table_session_id"), columns)
            }
            meta.getColumns(null, connection.schema, table, "exit_event_id").use { column ->
                assertTrue(column.next())
                assertEquals("NO", column.getString("IS_NULLABLE"))
                assertEquals("YES", column.getString("IS_AUTOINCREMENT"))
            }
            val uniqueColumns = mutableMapOf<String, MutableList<String>>()
            meta.getIndexInfo(null, connection.schema, table, true, false).use { indexes ->
                while (indexes.next()) {
                    val index = indexes.getString("INDEX_NAME") ?: continue
                    uniqueColumns.getOrPut(index) { mutableListOf() }.add(indexes.getString("COLUMN_NAME"))
                }
            }
            assertTrue(listOf("exit_event_id") in uniqueColumns.values)
            val repo = TableSessionRepository(null)
            val events =
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT user_id, table_session_id, exited_at, exit_event_id " +
                            "FROM guest_table_session_exits ORDER BY user_id",
                    ).use { rows ->
                        buildMap {
                            while (rows.next()) {
                                assertEquals(301L, rows.getLong(2))
                                assertEquals(at, rows.getTimestamp(3).toInstant())
                                put(rows.getLong(1), rows.getLong(4))
                            }
                        }
                    }
                }
            assertEquals(setOf(501L, 502L), events.keys)
            assertEquals(2, events.values.toSet().size)
            assertTrue(events.values.all { it > 0 })
            assertFailsWith<SQLException> {
                connection.prepareStatement(insert).use {
                    it.setLong(1, 501)
                    it.setTimestamp(2, Timestamp.from(at))
                    it.executeUpdate()
                }
            }
            connection.autoCommit = false
            repo.recordUserExit(connection, 501, 301, at)
            val current = checkNotNull(repo.findExitEventId(connection, 501, 301))
            assertEquals(events.getValue(501), current)
            assertFalse(repo.consumeExitEvent(connection, 501, 301, events.getValue(502)))
            assertEquals(current, repo.findExitEventId(connection, 501, 301))
            assertEquals(events.getValue(502), repo.findExitEventId(connection, 502, 301))
            connection.commit()
            connection.autoCommit = true

            // Upgrade an existing physical session with two independent, outstanding exit events.
            migrate(previous + 3)
            assertEquals(1, migrate(previous + 4).migrationsExecuted)
            assertEquals(0, migrate(previous + 4).migrationsExecuted)
            assertEquals(0L, repo.visitGeneration(connection, 501, 301))
            assertEquals(0L, repo.visitGeneration(connection, 502, 301))
            assertEquals(current, repo.findExitEventId(connection, 501, 301))
            assertEquals(events.getValue(502), repo.findExitEventId(connection, 502, 301))
            connection.autoCommit = false
            assertTrue(repo.consumeExitEvent(connection, 501, 301, current))
            connection.commit()
            assertEquals(current, repo.visitGeneration(connection, 501, 301))
            assertEquals(0L, repo.visitGeneration(connection, 502, 301))
            assertEquals(events.getValue(502), repo.findExitEventId(connection, 502, 301))
        }
    }
}
