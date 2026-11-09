package com.hookah.platform.backend.miniapp.venue.dashboard

import com.hookah.platform.backend.miniapp.venue.seating.OWNER
import com.hookah.platform.backend.miniapp.venue.seating.rows
import com.hookah.platform.backend.miniapp.venue.seating.seedSeating
import com.hookah.platform.backend.miniapp.venue.seating.write
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VenueDashboardCapacityPostgresTest {
    private val json =
        Json {
            encodeDefaults = true
            prettyPrint = true
        }
    private val wireJson = Json { encodeDefaults = true }

    @Test
    fun `200000 and 400000 history rows do not enter snapshots ledger protocol or polling scans`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 5
                val (venue, tables) = seedSeating(ds)
                val (foreign, foreignTables) = seedSeating(ds)
                val repo = VenueDashboardRepository(ds)
                val metrics = mutableListOf<JsonElement>()
                ds.connection.use { c ->
                    val historyOrder = c.dashboardOrder(venue, tables[0])
                    val session =
                        c.rows(
                            "SELECT table_session_id FROM orders WHERE id = ?",
                            historyOrder,
                        ) { it.getLong(1) }.single()
                    c.dashboardBatch(c.dashboardOrder(foreign, foreignTables[0]))
                    c.dashboardCall(foreign, foreignTables[0])
                    var currentOrder = 0L
                    var first = 0L
                    var append = 0L
                    var historicalAppend = 0L
                    var call = 0L
                    for (historySize in listOf(200000, 400000)) {
                        c.write(
                            "INSERT INTO order_batches(order_id,source,status) " +
                                "SELECT ?,'MINIAPP','DELIVERED' FROM generate_series(1,200000)",
                            historyOrder,
                        )
                        c.write(
                            "INSERT INTO staff_calls(venue_id,table_id,reason,status) " +
                                "SELECT ?,?,'COME','DONE' FROM generate_series(1,200000)",
                            venue,
                            tables[0],
                        )
                        c.write(
                            "INSERT INTO orders(venue_id,table_id,table_session_id,status) " +
                                "SELECT ?,?,?,'CLOSED' FROM generate_series(1,100000)",
                            venue,
                            tables[0],
                            session,
                        )
                        if (historySize == 200000) {
                            currentOrder = c.dashboardOrder(venue, tables[1])
                            first = c.dashboardBatch(currentOrder)
                            append = c.dashboardBatch(currentOrder)
                            historicalAppend = c.dashboardBatch(historyOrder)
                            call = c.dashboardCall(venue, tables[0])
                        }
                        c.createStatement().use { s ->
                            for (table in listOf(
                                "orders",
                                "order_batches",
                                "staff_calls",
                                "venue_tables",
                            )) s.execute("ANALYZE $table")
                        }
                        val timings = mutableListOf<Double>()
                        var responseBytes = 0
                        repeat(8) {
                            val started = System.nanoTime()
                            val snapshot = repo.snapshot(venue, OWNER)
                            responseBytes = wireJson.encodeToString(snapshot).toByteArray().size
                            timings.add((System.nanoTime() - started) / 1000000.0)
                            assertTrue(snapshot.attentionComplete)
                            assertEquals(
                                setOf(
                                    "batch:$first",
                                    "batch:$append",
                                    "batch:$historicalAppend",
                                    "call:$call",
                                ),
                                snapshot.attention.map {
                                    it.id
                                }.toSet(),
                            )
                            assertEquals("ORDER_CREATED", snapshot.attention.single { it.id == "batch:$first" }.kind)
                            assertEquals("BATCH_ADDED", snapshot.attention.single { it.id == "batch:$append" }.kind)
                            assertEquals(2, snapshot.activeOrders)
                            assertTrue(responseBytes < 10000)
                        }
                        val plans =
                            listOf(
                                PENDING_BATCHES_SQL,
                                PENDING_CALLS_SQL,
                                "SELECT COUNT(*) FROM orders WHERE venue_id = ? AND status = 'ACTIVE'",
                                "SELECT COUNT(*) FROM staff_calls WHERE venue_id = ? AND status = 'ACK'",
                            ).map { sql ->
                                val plan = explain(c, sql, venue)
                                assertNoHistoricalScan(plan)
                                buildJsonObject {
                                    put("sql", sql)
                                    put("explainAnalyzeBuffers", plan)
                                }
                            }
                        metrics.add(
                            buildJsonObject {
                                put("historicalBatches", historySize)
                                put("historicalDoneCalls", historySize)
                                put("historicalClosedOrders", historySize / 2)
                                put("pendingEvents", 4)
                                put("responseBytes", responseBytes)
                                put("snapshotAndSerializationMs", JsonArray(timings.map { JsonPrimitive(it) }))
                                put("plans", JsonArray(plans))
                            },
                        )
                    }
                    ds.connection.use { delayed ->
                        assertNotEquals(
                            c.rows("SELECT pg_backend_pid()") {
                                it.getInt(1)
                            }.single(),
                            delayed.rows("SELECT pg_backend_pid()") { it.getInt(1) }.single(),
                        )
                        delayed.autoCommit = false
                        val low = delayed.dashboardBatch(currentOrder)
                        val high = c.dashboardBatch(currentOrder)
                        assertFalse(repo.snapshot(venue, OWNER).attention.any { it.id == "batch:$low" })
                        assertTrue(repo.snapshot(venue, OWNER).attention.any { it.id == "batch:$high" })
                        delayed.commit()
                        assertTrue(repo.snapshot(venue, OWNER).attention.any { it.id == "batch:$low" })
                        val gone = delayed.dashboardBatch(currentOrder, "ACCEPTED")
                        delayed.commit()
                        assertFalse(repo.snapshot(venue, OWNER).attention.any { it.id == "batch:$gone" })
                    }
                    repeat(260) { c.dashboardBatch(currentOrder) }
                    val overflow = repo.snapshot(venue, OWNER)
                    assertFalse(overflow.attentionComplete)
                    assertEquals(VENUE_EVENT_CAPACITY, overflow.attention.size)
                    assertEquals(VENUE_EVENT_CAPACITY + 1, overflow.attentionTotalAtLeast)
                    val overflowBytes = wireJson.encodeToString(overflow).toByteArray().size
                    assertTrue(overflowBytes <= VENUE_DASHBOARD_MAX_BYTES)
                    val out = Path.of("build", "venue-dashboard-capacity.json")
                    Files.createDirectories(out.parent)
                    Files.writeString(
                        out,
                        json.encodeToString(
                            buildJsonObject {
                                put("scope", "Synthetic PostgreSQL 17; warm local DB; not production latency")
                                put("measurements", JsonArray(metrics))
                                put("overflowResponseBytes", overflowBytes)
                                put("capacity", VENUE_EVENT_CAPACITY)
                                put("responseMaxBytes", VENUE_DASHBOARD_MAX_BYTES)
                                put("lateLowerIdAfter400000", true)
                                put("completedBeforeObservationUnavailable", true)
                            },
                        ),
                    )
                }
            }
        }

    private fun explain(
        c: Connection,
        sql: String,
        venue: Long,
    ): JsonElement =
        c.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $sql").use { s ->
            s.setLong(1, venue)
            s.executeQuery().use { rs ->
                check(rs.next())
                Json.parseToJsonElement(rs.getString(1)).jsonArray.single()
            }
        }

    private fun assertNoHistoricalScan(value: JsonElement) {
        if (value is JsonObject) {
            val relation = value["Relation Name"]?.jsonPrimitive?.content
            if (relation in setOf("orders", "order_batches", "staff_calls")) {
                assertFalse(
                    value["Node Type"]?.jsonPrimitive?.content in setOf("Seq Scan", "Parallel Seq Scan"),
                    value.toString(),
                )
                val visits =
                    (
                        (value["Actual Rows"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) +
                            (value["Rows Removed by Filter"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0)
                    ) *
                        (value["Actual Loops"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 1.0)
                assertTrue(visits < 4096, "Historical index/filter walk: $value")
            }
            value.values.forEach { assertNoHistoricalScan(it) }
        } else if (value is JsonArray) {
            value.forEach { assertNoHistoricalScan(it) }
        }
    }
}
