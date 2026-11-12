package com.hookah.platform.backend.analytics

import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.orders.OrderActionActor
import com.hookah.platform.backend.miniapp.venue.orders.OrderBatchStatus
import com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.sql.Statement
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyticsFoundationH2Test : AnalyticsFoundationContract(false)

class AnalyticsFoundationPostgresTest : AnalyticsFoundationContract(true) {
    @Test
    fun `real PostgreSQL batch delivery serializes with order closure`() {
        fixture { f ->
            val batch = f.batch("ACCEPTED")
            race(
                f,
                { hook ->
                    runBlocking {
                        f.orders(hook).updateBatchStatus(
                            f.venue,
                            batch,
                            OrderBatchStatus.ACCEPTED,
                            OrderBatchStatus.DELIVERED,
                            actor,
                        )
                    }
                },
                { hook ->
                    runBlocking {
                        f.orders(
                            hook,
                        ).updateOrderStatus(f.venue, f.order, OrderWorkflowStatus.CLOSED, actor)
                    }
                },
            )
            assertEquals("CLOSED", f.string("SELECT status FROM orders WHERE id = ${f.order}"))
            assertEquals(1L, f.number("SELECT count(*) FROM order_batch_status_facts"))
            assertNull(f.instant("SELECT first_accepted_at FROM order_batches WHERE id = $batch"))
            assertEquals(now, f.instant("SELECT first_delivered_at FROM order_batches WHERE id = $batch"))
        }
    }

    @Test
    fun `real PostgreSQL locks serialize single bulk order and call writers`() {
        fixture { f ->
            val batch = f.batch()
            race(
                f,
                {
                        hook ->
                    runBlocking {
                        f.orders(
                            hook,
                        ).updateBatchStatus(f.venue, batch, OrderBatchStatus.NEW, OrderBatchStatus.ACCEPTED, actor)
                    }
                },
                { hook -> runBlocking { f.orders(hook).acceptAllNewBatches(f.venue, f.order, actor) } },
            )
            assertEquals(1L, f.number("SELECT count(*) FROM order_batch_status_facts"))
            val call = f.call()
            race(
                f,
                { hook -> runBlocking { StaffCallRepository(f.ds, hook, clock).ackStaffCall(f.venue, call, 7) } },
                { hook -> runBlocking { StaffCallRepository(f.ds, hook, clock).ackStaffCall(f.venue, call, 7) } },
            )
            assertEquals(now, f.instant("SELECT acknowledged_at FROM staff_calls WHERE id = $call"))
            race(
                f,
                { hook -> runBlocking { f.orders(hook).deliverAllAcceptedBatches(f.venue, f.order, actor) } },
                {
                        hook ->
                    runBlocking {
                        f.orders(
                            hook,
                        ).updateBatchStatus(
                            f.venue,
                            batch,
                            OrderBatchStatus.ACCEPTED,
                            OrderBatchStatus.DELIVERED,
                            actor,
                        )
                    }
                },
            )
            assertEquals(2L, f.number("SELECT count(*) FROM order_batch_status_facts"))
            val bill = f.call("BILL")
            race(
                f,
                {
                        hook ->
                    runBlocking {
                        f.orders(
                            hook,
                        ).updateOrderStatus(f.venue, f.order, OrderWorkflowStatus.CLOSED, actor)
                    }
                },
                {
                        hook ->
                    runBlocking {
                        StaffCallRepository(
                            f.ds,
                            hook,
                            clock,
                        ).resolveActiveCallsForClosedOrder(f.venue, f.order)
                    }
                },
            )
            assertEquals("ORDER_CLOSED", f.string("SELECT completion_kind FROM staff_calls WHERE id = $bill"))
            assertEquals("ORDER_CLOSED", f.string("SELECT cancellation_kind FROM staff_calls WHERE id = $call"))
            assertEquals(
                2L,
                f.number("SELECT count(*) FROM analytics_events WHERE event_type = 'batch_status_changed'"),
            )
        }
    }

    @Test
    fun `real PostgreSQL operator call completion races automatic resolution`() {
        fixture { f ->
            val call = f.call("BILL", "ACK")
            race(
                f,
                { hook -> runBlocking { StaffCallRepository(f.ds, hook, clock).doneStaffCall(f.venue, call, 7) } },
                {
                        hook ->
                    runBlocking {
                        StaffCallRepository(
                            f.ds,
                            hook,
                            clock,
                        ).resolveActiveCallsForClosedOrder(f.venue, f.order)
                    }
                },
            )
            assertEquals("OPERATOR", f.string("SELECT completion_kind FROM staff_calls WHERE id = $call"))
            assertNull(f.instant("SELECT acknowledged_at FROM staff_calls WHERE id = $call"))
        }
    }

    private fun race(
        f: Fixture,
        first: ((String, Connection) -> Unit) -> Any?,
        second: ((String, Connection) -> Unit) -> Any?,
    ) {
        val held = CountDownLatch(1)
        val contender = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pids = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a =
                pool.submit<Any?> {
                    first { phase, c ->
                        if (phase == "BEFORE_LOCK") {
                            pids.add(
                                c.createStatement().use {
                                    it.executeQuery("SELECT pg_backend_pid()").use {
                                            r ->
                                        r.next()
                                        r.getInt(1)
                                    }
                                },
                            )
                        }
                        if (phase == "BEFORE_COMMIT") {
                            held.countDown()
                            check(release.await(15, TimeUnit.SECONDS))
                        }
                    }
                }
            assertTrue(held.await(15, TimeUnit.SECONDS))
            val b =
                pool.submit<Any?> {
                    second { phase, c ->
                        if (phase == "BEFORE_LOCK") {
                            pids.add(
                                c.createStatement().use {
                                    it.executeQuery("SELECT pg_backend_pid()").use {
                                            r ->
                                        r.next()
                                        r.getInt(1)
                                    }
                                },
                            )
                            contender.countDown()
                        }
                    }
                }
            assertTrue(contender.await(15, TimeUnit.SECONDS))
            assertEquals(2, pids.size)
            assertFalse(b.isDone)
            release.countDown()
            a.get(20, TimeUnit.SECONDS)
            b.get(20, TimeUnit.SECONDS)
            assertEquals(
                0L,
                f.number("SELECT count(*) FROM pg_locks WHERE pid IN (${pids.joinToString(",")}) AND NOT granted"),
            )
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}

abstract class AnalyticsFoundationContract(private val postgres: Boolean) {
    protected val now: Instant = Instant.parse("2031-02-03T10:11:12.123456Z")
    protected val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    protected val actor = OrderActionActor(7, VenueRole.STAFF, AnalyticsChannel.TELEGRAM)

    @Test
    fun `legacy nonempty migration validate repeat and new facts preserve unknown`() =
        fixture(legacyRows = true) { f ->
            assertNull(f.instant("SELECT first_accepted_at FROM orders WHERE id = ${f.order}"))
            assertFalse(f.boolean("SELECT acceptance_history_complete FROM orders WHERE id = ${f.order}"))
            val b = f.batch("ACCEPTED")
            runBlocking {
                f.orders().updateBatchStatus(
                    f.venue,
                    b,
                    OrderBatchStatus.ACCEPTED,
                    OrderBatchStatus.DELIVERED,
                    actor,
                )
            }
            assertNull(f.instant("SELECT first_accepted_at FROM order_batches WHERE id = $b"))
            assertEquals(now, f.instant("SELECT first_delivered_at FROM order_batches WHERE id = $b"))
            assertFalse(f.boolean("SELECT status_history_complete FROM order_batches WHERE id = $b"))
            f.flyway().validate()
            assertEquals(0, f.flyway().migrate().migrationsExecuted)
        }

    @Test
    fun `single bulk whole-order rejection retain first acceptance and compatible events`() =
        fixture { f ->
            val one = f.batch()
            val two = f.batch()
            val repo = f.orders()
            runBlocking {
                assertTrue(
                    repo.updateBatchStatus(
                        f.venue,
                        one,
                        OrderBatchStatus.NEW,
                        OrderBatchStatus.ACCEPTED,
                        actor,
                    )!!.applied,
                )
                assertFalse(
                    repo.updateBatchStatus(
                        f.venue,
                        one,
                        OrderBatchStatus.NEW,
                        OrderBatchStatus.ACCEPTED,
                        actor,
                    )!!.applied,
                )
                assertFalse(
                    repo.updateBatchStatus(
                        f.venue,
                        one,
                        OrderBatchStatus.ACCEPTED,
                        OrderBatchStatus.ACCEPTED,
                        actor,
                    )!!.applied,
                )
                assertNull(f.instant("SELECT first_accepted_at FROM order_batches WHERE id = $two"))
                assertTrue(repo.acceptAllNewBatches(f.venue, f.order, actor)!!.applied)
                assertTrue(repo.deliverAllAcceptedBatches(f.venue, f.order, actor)!!.applied)
            }
            assertEquals(now, f.instant("SELECT first_accepted_at FROM orders WHERE id = ${f.order}"))
            assertEquals(4L, f.number("SELECT count(*) FROM order_batch_status_facts"))
            val three = f.batch()
            val later =
                VenueOrdersRepository(
                    f.ds,
                    AnalyticsEventRepository(f.ds),
                    clock = Clock.fixed(now.plusSeconds(30), ZoneOffset.UTC),
                )
            runBlocking {
                later.updateOrderStatus(f.venue, f.order, OrderWorkflowStatus.ACCEPTED, actor)
                later.rejectLatestBatch(f.venue, f.order, "OTHER", "private comment", actor)
            }
            assertEquals(now, f.instant("SELECT first_accepted_at FROM orders WHERE id = ${f.order}"))
            assertEquals(
                now.plusSeconds(30),
                f.instant("SELECT first_accepted_at FROM order_batches WHERE id = $three"),
            )
            assertNull(f.instant("SELECT first_delivered_at FROM order_batches WHERE id = $three"))
            val payload =
                Json.parseToJsonElement(
                    f.string(
                        "SELECT payload_json FROM analytics_events WHERE batch_id = $three ORDER BY id DESC LIMIT 1",
                    ),
                ).jsonObject
            assertEquals("REJECTED", payload.getValue("batchToStatus").jsonPrimitive.content)
            assertEquals("delivered", payload.getValue("toStatus").jsonPrimitive.content)
            assertEquals(now.plusSeconds(30).toString(), payload.getValue("occurredAt").jsonPrimitive.content)

            assertEquals("2", payload.getValue("contractVersion").jsonPrimitive.content)
            assertEquals("TELEGRAM", payload.getValue("channel").jsonPrimitive.content)
            assertTrue(payload.containsKey("fromStatus") && payload.containsKey("toStatus"))
            assertFalse(payload.toString().contains("private comment"))
            assertFalse(payload.containsKey("reasonCode"))
            assertEquals(
                6L,
                f.number("SELECT count(*) FROM analytics_events WHERE event_type = 'batch_status_changed'"),
            )
            assertEquals(
                0L,
                f.number("SELECT count(*) FROM analytics_events WHERE event_type = 'order_batch_status_changed'"),
            )
            assertEquals("order_batch_status_changed", canonicalAnalyticsEventType("batch_status_changed"))
            assertEquals("order_batch_created", canonicalAnalyticsEventType("batch_created"))
        }

    @Test
    fun `repeat transition identity and rejection closure never invent delivery`() =
        fixture { f ->
            val b = f.batch()
            runBlocking {
                val repo = f.orders()
                repo.updateBatchStatus(f.venue, b, OrderBatchStatus.NEW, OrderBatchStatus.ACCEPTED, actor)
                repo.updateBatchStatus(f.venue, b, OrderBatchStatus.ACCEPTED, OrderBatchStatus.PREPARING, actor)
                // Existing low-level expected-status writer accepts reentry; no new workflow edge is added.
                repo.updateBatchStatus(f.venue, b, OrderBatchStatus.PREPARING, OrderBatchStatus.ACCEPTED, actor)
                repo.updateBatchStatus(f.venue, b, OrderBatchStatus.ACCEPTED, OrderBatchStatus.PREPARING, actor)
                repo.rejectOrder(f.venue, f.order, "OTHER", null, actor)
            }
            assertEquals(5L, f.number("SELECT count(*) FROM analytics_events"))
            assertEquals(2L, f.number("SELECT count(*) FROM order_batch_status_facts WHERE to_status = 'ACCEPTED'"))
            assertEquals(now, f.instant("SELECT first_accepted_at FROM order_batches WHERE id = $b"))
            assertNull(f.instant("SELECT first_delivered_at FROM order_batches WHERE id = $b"))
            assertEquals("CLOSED", f.string("SELECT status FROM orders WHERE id = ${f.order}"))
        }

    @Test
    fun `ACK DONE replay auto DONE and cancel preserve timing provenance`() =
        fixture { f ->
            val call = f.call()
            val bill = f.call("BILL")
            val other = f.call()
            val legacy = f.call("COME", "ACK")
            val repo = StaffCallRepository(f.ds, clock = clock)
            runBlocking {
                assertTrue(repo.ackStaffCall(f.venue, call, 7, AnalyticsChannel.MINIAPP)!!.applied)
                val later = StaffCallRepository(f.ds, clock = Clock.fixed(now.plusSeconds(5), ZoneOffset.UTC))
                assertFalse(later.ackStaffCall(f.venue, call, 7)!!.applied)
                assertTrue(later.doneStaffCall(f.venue, call, 7, AnalyticsChannel.TELEGRAM)!!.applied)
                assertFalse(later.doneStaffCall(f.venue, call, 7)!!.applied)
                assertTrue(later.doneStaffCall(f.venue, legacy, 7)!!.applied)
                repo.resolveActiveCallsForClosedOrder(f.venue, f.order)
                assertTrue(repo.resolveActiveCallsForClosedOrder(f.venue, f.order).isEmpty())
            }
            assertEquals(now, f.instant("SELECT acknowledged_at FROM staff_calls WHERE id = $call"))
            assertEquals(now.plusSeconds(5), f.instant("SELECT completed_at FROM staff_calls WHERE id = $call"))
            assertEquals("OPERATOR", f.string("SELECT completion_kind FROM staff_calls WHERE id = $call"))
            assertEquals("MINIAPP", f.string("SELECT acknowledged_channel FROM staff_calls WHERE id = $call"))
            assertNull(f.instant("SELECT acknowledged_at FROM staff_calls WHERE id = $legacy"))
            assertNull(f.instant("SELECT acknowledged_at FROM staff_calls WHERE id = $bill"))
            assertEquals("ORDER_CLOSED", f.string("SELECT completion_kind FROM staff_calls WHERE id = $bill"))
            assertEquals(now, f.instant("SELECT cancelled_at FROM staff_calls WHERE id = $other"))
            assertNull(f.instant("SELECT completed_at FROM staff_calls WHERE id = $other"))
        }

    @Test
    fun `failure after writes rolls back batch facts analytics and call timestamps`() =
        fixture { f ->
            val b = f.batch()
            val call = f.call()
            val fail: (String, Connection) -> Unit = { phase, _ -> if (phase == "BEFORE_COMMIT") error("injected") }
            assertFailsWith<IllegalStateException> {
                runBlocking {
                    f.orders(
                        fail,
                    ).acceptAllNewBatches(f.venue, f.order, actor)
                }
            }
            assertEquals("NEW", f.string("SELECT status FROM order_batches WHERE id = $b"))
            assertNull(f.instant("SELECT first_accepted_at FROM order_batches WHERE id = $b"))
            assertNull(f.instant("SELECT first_accepted_at FROM orders WHERE id = ${f.order}"))
            assertEquals(0L, f.number("SELECT count(*) FROM order_batch_status_facts"))
            assertEquals(0L, f.number("SELECT count(*) FROM analytics_events"))
            assertFailsWith<IllegalStateException> {
                runBlocking { StaffCallRepository(f.ds, fail, clock).ackStaffCall(f.venue, call, 7) }
            }
            assertNull(f.instant("SELECT acknowledged_at FROM staff_calls WHERE id = $call"))
            assertEquals("NEW", f.string("SELECT status FROM staff_calls WHERE id = $call"))
            assertFailsWith<IllegalStateException> {
                runBlocking {
                    StaffCallRepository(
                        f.ds,
                        fail,
                        clock,
                    ).resolveActiveCallsForClosedOrder(f.venue, f.order)
                }
            }
            assertNull(f.instant("SELECT cancelled_at FROM staff_calls WHERE id = $call"))
            assertEquals("NEW", f.string("SELECT status FROM staff_calls WHERE id = $call"))
        }

    @Test
    fun `foreign venue cannot create transition facts`() =
        fixture { f ->
            val b = f.batch()
            val call = f.call()
            val foreign = f.insert("INSERT INTO venues(name, status) VALUES ('Other', 'PUBLISHED')")
            runBlocking {
                assertNull(
                    f.orders().updateBatchStatus(foreign, b, OrderBatchStatus.NEW, OrderBatchStatus.ACCEPTED, actor),
                )
                assertNull(StaffCallRepository(f.ds).ackStaffCall(foreign, call, 7))
            }
            assertEquals(0L, f.number("SELECT count(*) FROM order_batch_status_facts"))
            assertNull(f.instant("SELECT acknowledged_at FROM staff_calls WHERE id = $call"))
        }

    protected fun fixture(
        legacyRows: Boolean = false,
        block: (Fixture) -> Unit,
    ) {
        val ds: DataSource =
            if (postgres) {
                PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase(), migrate = false)
            } else {
                JdbcDataSource().apply {
                    setURL(
                        "jdbc:h2:mem:analytics_${UUID.randomUUID()};MODE=PostgreSQL;" +
                            "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    )
                    user = "sa"
                }
            }
        try {
            val location = "classpath:db/migration/" + if (postgres) "postgresql" else "h2"
            Flyway.configure().dataSource(
                ds,
            ).locations(location).target(if (postgres) "137" else "138").load().migrate()
            val f = Fixture(ds, location)
            val oldBatch = if (legacyRows) f.batch("DELIVERED") else null
            val oldCall = if (legacyRows) f.call("BILL", "DONE") else null
            // Nonempty previous schema: migrated rows must remain unknown.
            f.flyway().migrate()
            assertEquals(if (postgres) "138" else "139", f.flyway().info().current().version.version)
            if (legacyRows) {
                assertNull(f.instant("SELECT first_delivered_at FROM order_batches WHERE id = $oldBatch"))
                assertNull(f.instant("SELECT first_accepted_at FROM order_batches WHERE id = $oldBatch"))
                assertNull(f.instant("SELECT completed_at FROM staff_calls WHERE id = $oldCall"))
            }
            block(f)
        } finally {
            (ds as? AutoCloseable)?.close()
        }
    }

    protected inner class Fixture(val ds: DataSource, private val location: String) {
        val venue = insert("INSERT INTO venues(name, status) VALUES ('Venue', 'PUBLISHED')")
        val table =
            insert(
                "INSERT INTO venue_tables(venue_id, table_number) " +
                    "VALUES ($venue, 1)",
            )
        val session =
            insert(
                "INSERT INTO table_sessions(venue_id, table_id, status, started_at, last_activity_at, expires_at) " +
                    "VALUES ($venue, $table, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, " +
                    "TIMESTAMP WITH TIME ZONE '2032-01-01 00:00:00+00')",
            )
        val order =
            insert(
                "INSERT INTO orders(venue_id, table_id, table_session_id, status) " +
                    "VALUES ($venue, $table, $session, 'ACTIVE')",
            )

        init {
            ds.connection.use { it.createStatement().executeUpdate("INSERT INTO users(telegram_user_id) VALUES (7)") }
        }

        fun flyway(): Flyway = Flyway.configure().dataSource(ds).locations(location).load()

        fun orders(hook: (String, Connection) -> Unit = { _, _ -> }): VenueOrdersRepository =
            VenueOrdersRepository(
                ds,
                AnalyticsEventRepository(ds),
                clock = clock,
                transitionCheckpoint = hook,
            )

        fun batch(status: String = "NEW"): Long =
            insert(
                "INSERT INTO order_batches(order_id, source, status, created_at) " +
                    "VALUES ($order, 'MINIAPP', '$status', TIMESTAMP WITH TIME ZONE '2020-01-01 00:00:00+00')",
            )

        fun call(
            reason: String = "COME",
            status: String = "NEW",
        ): Long =
            insert(
                "INSERT INTO staff_calls(venue_id, table_id, order_id, reason, status) " +
                    "VALUES ($venue, $table, $order, '$reason', '$status')",
            )

        fun insert(sql: String): Long =
            ds.connection.use { c ->
                c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use {
                    it.executeUpdate()
                    it.generatedKeys.use { r ->
                        check(r.next())
                        r.getLong(1)
                    }
                }
            }

        fun boolean(sql: String): Boolean =
            ds.connection.use { c ->
                c.createStatement().use {
                    it.executeQuery(sql).use { r ->
                        check(r.next())
                        r.getBoolean(1)
                    }
                }
            }

        fun number(sql: String): Long =
            ds.connection.use { c ->
                c.createStatement().use {
                    it.executeQuery(sql).use { r ->
                        check(r.next())
                        r.getLong(1)
                    }
                }
            }

        fun string(sql: String): String =
            ds.connection.use { c ->
                c.createStatement().use {
                    it.executeQuery(sql).use { r ->
                        check(r.next())
                        r.getString(1)
                    }
                }
            }

        fun instant(sql: String): Instant? =
            ds.connection.use { c ->
                c.createStatement().use {
                    it.executeQuery(sql).use { r ->
                        check(r.next())
                        r.getTimestamp(1)?.toInstant()
                    }
                }
            }
    }
}
