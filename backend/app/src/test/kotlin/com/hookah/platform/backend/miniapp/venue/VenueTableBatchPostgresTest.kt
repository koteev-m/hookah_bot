package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.venue.seating.insert
import com.hookah.platform.backend.miniapp.venue.seating.rows
import com.hookah.platform.backend.miniapp.venue.seating.write
import com.hookah.platform.backend.miniapp.venue.tables.TableNumberConflictException
import com.hookah.platform.backend.miniapp.venue.tables.VenueTableRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.DriverManager
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VenueTableBatchPostgresTest {
    @Test
    fun `concurrent batches commit one complete winner with tokens and audit and rollback audit failure`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { ds ->
                val venueId =
                    ds.connection.use { connection ->
                        connection.createStatement().use { statement ->
                            statement.execute("INSERT INTO users (telegram_user_id) VALUES (12345)")
                            statement.executeQuery(
                                "INSERT INTO venues (name, city, address, status) " +
                                    "VALUES ('Test', 'Test', 'Test', 'PUBLISHED') RETURNING id",
                            ).use { rs ->
                                rs.next()
                                rs.getLong(1)
                            }
                        }
                    }
                val repository = VenueTableRepository(ds)
                val audit = AuditLogRepository(ds)
                val start = CompletableDeferred<Unit>()
                val results =
                    listOf(listOf(1, 3, 20), listOf(8, 12, 20)).map { numbers ->
                        async {
                            start.await()
                            runCatching {
                                repository.batchCreateTables(venueId, numbers) { connection, tables ->
                                    audit.appendJson(
                                        connection,
                                        12345,
                                        "TABLES_BATCH_CREATE",
                                        "venue",
                                        venueId,
                                        buildJsonObject { put("count", tables.size) },
                                    )
                                }
                            }
                        }
                    }
                start.complete(Unit)
                val completed = results.awaitAll()
                assertEquals(1, completed.count { it.isSuccess })
                assertTrue(completed.single { it.isFailure }.exceptionOrNull() is TableNumberConflictException)
                val winner = completed.single { it.isSuccess }.getOrThrow()
                assertEquals(
                    winner.map { it.tableId }.toSet(),
                    repository.listTablesWithTokens(venueId).map { it.tableId }.toSet(),
                )
                assertEquals(3, repository.listTables(venueId).size)
                assertFailsWith<IllegalStateException> {
                    repository.batchCreateTables(venueId, listOf(90, 99)) { _, _ -> error("synthetic audit failure") }
                }
                ds.connection.use { connection ->
                    for ((query, count) in listOf(
                        "SELECT COUNT(*) FROM venue_tables" to 3,
                        "SELECT COUNT(*) FROM table_tokens" to 3,
                        "SELECT COUNT(*) FROM audit_log WHERE action = 'TABLES_BATCH_CREATE'" to 1,
                    )) {
                        connection.createStatement().use { statement ->
                            statement.executeQuery(query).use { rs ->
                                rs.next()
                                assertEquals(count, rs.getInt(1))
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun `audit rejection rolls back first QR issuance and single selected and all rotations`() =
        runBlocking {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                val venue = seedOwnerVenue(ds)
                val tables = VenueTableRepository(ds)
                val ids = tables.batchCreateTables(venue, listOf(1, 2)).map { it.tableId }
                val original = tables.listTablesWithTokens(venue)
                val missing =
                    ds.connection.use { connection ->
                        connection.write("ALTER TABLE audit_log ADD CHECK (action NOT LIKE 'TABLE_TOKEN_%')")
                        connection.insert("INSERT INTO venue_tables (venue_id, table_number) VALUES (?, 3)", venue)
                    }
                assertFailsWith<DatabaseUnavailableException> { tables.ensureActiveToken(venue, missing, OWNER) }
                assertFailsWith<DatabaseUnavailableException> { tables.rotateToken(venue, ids.first(), OWNER) }
                assertFailsWith<DatabaseUnavailableException> { tables.rotateTokens(venue, ids, OWNER) }
                assertFailsWith<DatabaseUnavailableException> { tables.rotateTokens(venue, null, OWNER) }

                assertTrue(original == tables.listTablesWithTokens(venue))
                ds.connection.use { connection ->
                    val totalTokens = connection.rows("SELECT count(*) FROM table_tokens") { it.getInt(1) }.single()
                    assertEquals(ids.size, totalTokens)
                    assertEquals(0, connection.rows("SELECT count(*) FROM audit_log") { it.getInt(1) }.single())
                }
            }
        }

    @Test
    fun `overlapping single selected and all rotations each commit one audit and one active QR per table`() =
        runBlocking {
            val db = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(db).use { ds ->
                val venue = seedOwnerVenue(ds)
                val tables = VenueTableRepository(ds)
                val ids = tables.batchCreateTables(venue, listOf(1, 2)).map { it.tableId }
                val original = tables.listTablesWithTokens(venue)
                // Keep the pool's three connections available to the three independent writers.
                DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { guard ->
                    guard.autoCommit = false
                    guard.rows("SELECT id FROM venues WHERE id = ? FOR NO KEY UPDATE", venue) { it.getLong(1) }
                    val single = async { tables.rotateToken(venue, ids.first(), OWNER) }
                    val batch = async { tables.rotateTokens(venue, ids.reversed(), OWNER) }
                    val all = async { tables.rotateTokens(venue, null, OWNER) }
                    try {
                        withTimeout(10_000) {
                            val waiters =
                                """
                                SELECT count(DISTINCT waiting.pid) FROM pg_locks waiting
                                JOIN pg_locks held ON held.pid = waiting.pid
                                WHERE NOT waiting.granted AND held.granted AND held.relation = 'venues'::regclass
                                  AND held.mode = 'RowShareLock'
                                """.trimIndent()
                            while (guard.rows(waiters) { it.getInt(1) }.single() != 3) delay(20)
                        }
                    } finally {
                        guard.rollback()
                    }
                    assertEquals(ids.first(), single.await()?.tableId)
                    assertEquals(ids.reversed(), batch.await().map { it.tableId })
                    assertEquals(ids, all.await().map { it.tableId })
                }
                val current = tables.listTablesWithTokens(venue)
                assertEquals(ids, current.map { it.tableId })
                assertTrue(current.zip(original).all { (now, old) -> now.token != old.token })
                ds.connection.use { connection ->
                    val revoked =
                        connection.rows(
                            "SELECT table_id FROM table_tokens WHERE NOT is_active " +
                                "AND revoked_at IS NOT NULL ORDER BY table_id",
                        ) { it.getLong(1) }
                    assertEquals(listOf(ids[0], ids[0], ids[0], ids[1], ids[1]), revoked)
                    val actions =
                        connection.rows(
                            "SELECT action FROM audit_log WHERE actor_user_id = ? AND entity_type = 'venue' " +
                                "AND entity_id = ? ORDER BY action",
                            OWNER,
                            venue,
                        ) { it.getString(1) }
                    assertEquals(
                        listOf("TABLE_TOKEN_ROTATE", "TABLE_TOKEN_ROTATE_ALL", "TABLE_TOKEN_ROTATE_BATCH"),
                        actions,
                    )
                }
            }
        }

    private fun seedOwnerVenue(ds: DataSource): Long =
        ds.connection.use { connection ->
            connection.write("INSERT INTO users (telegram_user_id) VALUES (?)", OWNER)
            val venue =
                connection.insert(
                    "INSERT INTO venues (name, city, address, status) VALUES ('Test', 'Test', 'Test', 'PUBLISHED')",
                )
            connection.write("INSERT INTO venue_members (venue_id, user_id, role) VALUES (?, ?, 'OWNER')", venue, OWNER)
            venue
        }

    private companion object {
        const val OWNER = 12345L
    }
}
