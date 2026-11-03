package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VenuePublicationPostgresTest {
    @Test
    fun `simultaneous publishes through bot and Mini App commit one transition and audit`() =
        runBlocking {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                seedPublicationFixture(ds)
                ds.connection.use { readyPublicationVenue(it, 1001) }
                val publication = VenuePublicationService(ds)
                val results =
                    VenuePublicationSource.entries.map { source ->
                        async { publication.publish(1001, 101, source).outcome }
                    }.awaitAll()
                assertEquals(
                    setOf(VenuePublicationOutcome.PUBLISHED, VenuePublicationOutcome.ALREADY_PUBLISHED),
                    results.toSet(),
                )
                ds.connection.use { assertEquals(1, it.auditCount(1001)) }
            }
        }

    @Test
    fun `publish waits for platform lock and cannot overwrite a concurrent suspension`() =
        runBlocking {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                seedPublicationFixture(ds)
                ds.connection.use { readyPublicationVenue(it, 1001) }
                val publication = VenuePublicationService(ds)
                assertEquals(emptyList(), publication.inspect(1001, 101, true).missing)
                ds.connection.use { platform ->
                    val pid =
                        platform.createStatement().use {
                            it.executeQuery("SELECT pg_backend_pid()").use { rs ->
                                assertTrue(rs.next())
                                rs.getInt(1)
                            }
                        }
                    platform.autoCommit = false
                    platform.exec("UPDATE venues SET status = 'SUSPENDED' WHERE id = 1001")
                    val pending = async { publication.publish(1001, 101, VenuePublicationSource.VENUE_MINI_APP) }
                    try {
                        withTimeout(10_000) {
                            ds.connection.use { observer ->
                                observer.createStatement().use { query ->
                                    while (!query.executeQuery(
                                            "SELECT EXISTS (SELECT 1 FROM pg_stat_activity " +
                                                "WHERE $pid = ANY(pg_blocking_pids(pid)))",
                                        ).use { rs -> rs.next() && rs.getBoolean(1) }
                                    ) {
                                        delay(20)
                                    }
                                }
                            }
                        }
                        platform.commit()
                        val result = pending.await()
                        assertEquals(VenuePublicationOutcome.INVALID_STATUS, result.outcome)
                        assertEquals(VenueStatus.SUSPENDED, result.state.status)
                    } finally {
                        platform.rollback()
                    }
                }
                ds.connection.use { assertEquals(0, it.auditCount(1001)) }
            }
        }

    @Test
    fun `audit failure rolls back draft transition`() =
        runBlocking {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                seedPublicationFixture(ds)
                ds.connection.use { readyPublicationVenue(it, 1001) }
                val publication =
                    VenuePublicationService(ds) { _, _, _, _, _, _ -> throw SQLException("Synthetic audit failure") }
                assertFailsWith<DatabaseUnavailableException> {
                    publication.publish(1001, 101, VenuePublicationSource.OWNER_BOT)
                }
                assertEquals(VenueStatus.DRAFT, publication.inspect(1001, 101, false).status)
                ds.connection.use { assertEquals(0, it.auditCount(1001)) }
            }
        }
}
