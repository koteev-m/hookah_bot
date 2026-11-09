package com.hookah.platform.backend.miniapp.venue.dashboard

import com.hookah.platform.backend.miniapp.venue.seating.OWNER
import com.hookah.platform.backend.miniapp.venue.seating.rows
import com.hookah.platform.backend.miniapp.venue.seating.seedSeating
import com.hookah.platform.backend.miniapp.venue.seating.write
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VenueDashboardPostgresTest {
    @Test
    fun `late lower IDs survive status changes rollback and independent venue progress`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 5
                val (venue, tables) = seedSeating(ds)
                val (other, otherTables) = seedSeating(ds)
                val order = ds.connection.use { it.dashboardOrder(venue, tables[0]) }
                val repo = VenueDashboardRepository(ds)
                ds.connection.use { delayed ->
                    ds.connection.use { committed ->
                        assertNotEquals(
                            delayed.rows("SELECT pg_backend_pid()") { it.getInt(1) }.single(),
                            committed.rows("SELECT pg_backend_pid()") { it.getInt(1) }.single(),
                        )
                        delayed.autoCommit = false
                        val lowBatch = delayed.dashboardBatch(order)
                        val lowCall = delayed.dashboardCall(venue, tables[0])
                        val highBatch = committed.dashboardBatch(order)
                        val highCall = committed.dashboardCall(venue, tables[0])
                        val during = repo.snapshot(venue, OWNER)
                        assertEquals(
                            setOf("batch:$highBatch", "call:$highCall"),
                            during.attention.map { it.id }.toSet(),
                        )
                        // Explicit independent transactions hold the lower IDs uncommitted throughout
                        // this read and the other tenant's progress. No clock/sleep determines order.
                        val otherBatch = committed.dashboardBatch(committed.dashboardOrder(other, otherTables[0]))
                        assertEquals(listOf("batch:$otherBatch"), repo.snapshot(other, OWNER).attention.map { it.id })
                        delayed.commit()
                        val after = repo.snapshot(venue, OWNER)
                        assertEquals(
                            during.attention.map { it.id }.toSet() + setOf("batch:$lowBatch", "call:$lowCall"),
                            after.attention.map { it.id }.toSet(),
                        )
                        committed.write("UPDATE order_batches SET status='ACCEPTED' WHERE id = ?", lowBatch)
                        committed.write("UPDATE staff_calls SET status='DONE' WHERE id = ?", lowCall)
                        assertFalse(
                            repo.snapshot(
                                venue,
                                OWNER,
                            ).attention.any { it.id == "batch:$lowBatch" || it.id == "call:$lowCall" },
                        )
                        assertEquals(
                            during.attention.map { it.id }.toSet(),
                            repo.snapshot(venue, OWNER).attention.map { it.id }.toSet(),
                        )
                        val rolledBack = delayed.dashboardBatch(order)
                        delayed.rollback()
                        assertFalse("batch:$rolledBack" in repo.snapshot(venue, OWNER).attention.map { it.id })
                        committed.write("UPDATE orders SET status='CLOSED' WHERE id = ?", order)
                        committed.write("UPDATE staff_calls SET status='DONE' WHERE venue_id = ?", venue)
                        assertTrue(repo.snapshot(venue, OWNER).attention.isEmpty())
                        assertEquals(0, repo.snapshot(venue, OWNER).attentionTotalAtLeast)
                    }
                }
            }
        }
}
