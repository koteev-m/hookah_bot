package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.miniapp.venue.tables.TableNumberConflictException
import com.hookah.platform.backend.miniapp.venue.tables.VenueTableRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
}
