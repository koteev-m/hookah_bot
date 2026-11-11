package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingCommand
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingRepository
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingSale
import com.hookah.platform.backend.miniapp.venue.menu.MenuSemanticType
import com.hookah.platform.backend.miniapp.venue.orders.OrderActionActor
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoyaltyLifecycleH2Test : LoyaltyLifecycleContract(false)

class LoyaltyLifecyclePostgresTest : LoyaltyLifecycleContract(true) {
    @Test
    fun `exact correction and durable replay survive two independent JVM lifetimes`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                val quote = order(1, status = "ACTIVE")
                redeem(quote)
                val dataSource = ds as com.zaxxer.hikari.HikariDataSource
                val operation = UUID.randomUUID().toString()
                val unit = unit(source, 1)
                val loaderPaths =
                    generateSequence(this@LoyaltyLifecyclePostgresTest.javaClass.classLoader) { it.parent }
                        .filterIsInstance<java.net.URLClassLoader>().flatMap {
                            it.urLs.asSequence()
                        }.map { java.nio.file.Path.of(it.toURI()).toString() }.toList()
                val classpath =
                    (loaderPaths + System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                        .distinct().joinToString(
                            java.io.File.pathSeparator,
                        )
                val pids = mutableSetOf<Long>()
                for (replayed in listOf(false, true)) {
                    val directory = java.nio.file.Path.of("build", "loyalty-restart-evidence")
                    java.nio.file.Files.createDirectories(directory)
                    val output = java.nio.file.Files.createTempFile(directory, "correction-", ".log")
                    val builder =
                        ProcessBuilder(
                            java.nio.file.Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                "java",
                            ).toString(),
                            "-Xmx256m", "-cp", classpath, LoyaltyCorrectionRestartProbe::class.java.name,
                            venue.toString(), source.order.toString(), unit.toString(), operation,
                        ).redirectErrorStream(true).redirectOutput(output.toFile())
                    builder.environment().apply {
                        remove("_JAVA_OPTIONS")
                        remove("JAVA_TOOL_OPTIONS")
                        remove("JDK_JAVA_OPTIONS")
                        put("HT_LOYALTY_TEST_PROBE", "1")
                        put("HT_LOYALTY_TEST_JDBC", dataSource.jdbcUrl)
                        put("HT_LOYALTY_TEST_USER", dataSource.username)
                        put("HT_LOYALTY_TEST_PASSWORD", dataSource.password)
                    }
                    val process = builder.start()
                    pids.add(process.pid())
                    try {
                        assertTrue(
                            process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS),
                            "Restart probe must finish within hard deadline",
                        )
                        assertEquals(0, process.exitValue(), "Restart probe failed; test-only evidence: $output")
                        assertTrue(java.nio.file.Files.readString(output).contains("LOYALTY_UNIT_REPLAY=$replayed"))
                    } finally {
                        process.destroyForcibly()
                        assertTrue(process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }
                assertEquals(2, pids.size)
                assertEquals(1L, number("SELECT count(*) FROM loyalty_unit_corrections"))
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM loyalty_redemptions " +
                            "WHERE status='RESERVED' AND " +
                            "funding_basis='VENUE_COMPENSATION'",
                    ),
                )
                delivery(quote)
                assertEquals(1L, number("SELECT count(*) FROM loyalty_correction_expenses"))
            }
        }
}

abstract class LoyaltyLifecycleContract(private val postgres: Boolean) {
    protected suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val ds =
            if (postgres) {
                PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase())
            } else {
                JdbcDataSource().apply {
                    setURL(
                        "jdbc:h2:mem:loyalty-${UUID.randomUUID()};MODE=PostgreSQL" +
                            ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    )
                    user = "sa"
                    password = ""
                    Flyway.configure().dataSource(this).locations("classpath:db/migration/h2").load().migrate()
                }
            }
        try {
            val f = Fixture(ds)
            f.sql("INSERT INTO users (telegram_user_id) VALUES (9001001),(9001002),(9001003)")
            f.sql("INSERT INTO venues (name,city,address,status) VALUES ('Loyalty','Test','Test','PUBLISHED')")
            f.venue = f.number("SELECT id FROM venues")
            f.sql("INSERT INTO venue_members (venue_id,user_id,role) VALUES (${f.venue},9001001,'OWNER')")
            f.shift =
                AccountingRepository(ds).execute(
                    f.venue, 9001001, AccountingCommand(UUID.randomUUID().toString(), "OPEN"),
                ).shiftId
            f.sql("INSERT INTO venue_tables (venue_id,table_number) VALUES (${f.venue},1)")
            f.table = f.number("SELECT id FROM venue_tables")
            f.sql("INSERT INTO menu_categories (venue_id,name,category_type) VALUES (${f.venue},'Hookahs','HOOKAH')")
            val category = f.number("SELECT id FROM menu_categories")
            f.sql(
                "INSERT INTO menu_items" +
                    " (venue_id,category_id,name,price_minor,currency)" +
                    " VALUES (${f.venue},$category,'Hookah',1000,'RUB')",
            )
            f.item = f.number("SELECT id FROM menu_items")
            f.program = f.repo.createProgram(f.venue, 3, 9001001).id
            f.repo.setProgramStatus(f.venue, f.program, LoyaltyProgramStatus.ACTIVE)
            block(f)
        } finally {
            (ds as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `confirm history original sequence is exactly CONFIRM CORRECT CANCEL`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                val quote = order(3, status = "ACTIVE")
                redeem(quote)
                val sourceBill = immutableOrder(source)
                val quoteBill = immutableOrder(quote)
                val sourceSale = sale(source.order)
                val quoteSale = sale(quote.order)
                sql("INSERT INTO venue_members(venue_id,user_id,role) VALUES ($venue,9001003,'STAFF')")
                val confirm = UUID.randomUUID().toString()
                val correction = UUID.randomUUID().toString()
                val cancellation = UUID.randomUUID().toString()
                delivery(quote, id = confirm, actor = 9001003, role = VenueRole.STAFF)
                delivery(quote, false, correction)
                correct(source, 1)
                cancelCompensation(quote, cancellation)
                sql("DELETE FROM venue_members WHERE venue_id=$venue AND user_id=9001003")
                assertEquals(2L, number("SELECT count(*) FROM loyalty_delivery_operations"))
                assertEquals(1L, number("SELECT count(*) FROM loyalty_delivery_operations WHERE action='CONFIRM'"))
                assertEquals(1L, number("SELECT count(*) FROM loyalty_delivery_operations WHERE action='CORRECT'"))
                assertEquals(1L, number("SELECT count(*) FROM loyalty_compensation_cancellations"))
                val orders = VenueOrdersRepository(ds)
                assertTrue(orders.loadAudit(venue, quote.order).isEmpty())

                fun state() =
                    ds.connection.use { c ->
                        listOf(
                            "orders", "order_batch_items", "venue_accounting_sales", "guest_loyalty_ledger",
                            "guest_loyalty_progress", "loyalty_redemptions", "audit_log", "loyalty_delivery_operations",
                            "loyalty_compensation_cancellations", "loyalty_unit_corrections",
                            "loyalty_correction_expenses",
                            "order_promotion_applications", "order_batch_item_promotion_adjustments",
                        ).associateWith { table ->
                            c.lq("SELECT * FROM $table") { r -> (1..r.metaData.columnCount).map { r.getString(it) } }
                                .sortedBy { it.toString() }
                        }
                    }
                val beforeRead = state()
                val history = orders.loadOrderDetail(venue, quote.order)!!.loyaltyHistory
                assertEquals(3, history.size)
                assertEquals(
                    listOf("DELIVERY_CONFIRMED", "DELIVERY_CORRECTED", "COMPENSATION_CANCELED"),
                    history.map { it.action },
                )
                assertEquals(
                    listOf("REWARD_APPLIED", "DELIVERY_MARK_CORRECTED", "UNISSUED_COMPENSATION_CANCELED"),
                    history.map { it.result },
                )
                assertEquals(listOf(9001003L, 9001001L, 9001001L), history.map { it.actorUserId })
                assertNull(history.first().reason)
                assertEquals("Ошибочная отметка о фактической выдаче", history[1].reason)
                assertEquals("Бонус не выдан: гость отказался", history[2].reason)
                assertEquals(1, history.map { it.redemptionId }.distinct().size)
                assertTrue(history.all { it.batchItemId == quote.line && it.itemName == "Hookah" })
                ds.connection.use { c ->
                    val events =
                        c.lq(
                            "SELECT id,actor_user_id,created_at,payload_json,action FROM audit_log " +
                                "WHERE action IN ('LOYALTY_REWARD_APPLIED','LOYALTY_DELIVERY_CORRECTED') ORDER BY id",
                        ) { r ->
                            listOf(
                                r.getLong(2).toString(), r.getTimestamp(3).toInstant().toString(),
                                Json.parseToJsonElement(r.getString(4)).jsonObject["reason"]
                                    ?.jsonPrimitive?.contentOrNull,
                            )
                        } +
                            c.lq(
                                "SELECT actor_user_id,created_at,reason FROM loyalty_compensation_cancellations",
                            ) { r ->
                                listOf(
                                    r.getLong(1).toString(), r.getTimestamp(2).toInstant().toString(), r.getString(3),
                                )
                            }
                    assertEquals(events, history.map { listOf(it.actorUserId.toString(), it.occurredAt, it.reason) })
                    assertTrue(c.loyaltyOrderHistory(venue + 1000, quote.order).isEmpty())
                }
                repeat(
                    3,
                ) {
                    assertEquals(
                        history,
                        VenueOrdersRepository(ds).loadOrderDetail(venue, quote.order)!!.loyaltyHistory,
                    )
                }
                assertNull(orders.loadOrderDetail(venue + 1000, quote.order))
                assertEquals(beforeRead, state())
                sql("INSERT INTO venue_members(venue_id,user_id,role) VALUES ($venue,9001003,'STAFF')")
                delivery(quote, id = confirm, actor = 9001003, role = VenueRole.STAFF)
                delivery(quote, false, correction)
                assertTrue(cancelCompensation(quote, cancellation))
                sql("DELETE FROM venue_members WHERE venue_id=$venue AND user_id=9001003")
                assertEquals(history, orders.loadOrderDetail(venue, quote.order)!!.loyaltyHistory)
                assertEquals(beforeRead, state())
                assertEquals(sourceBill, immutableOrder(source))
                assertEquals(quoteBill, immutableOrder(quote))
                assertEquals(sourceSale, sale(source.order))
                assertEquals(quoteSale, sale(quote.order))
            }
        }

    @Test
    fun `review P2 management correction and cancellation history is durable ordered and read only`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                val quote = order(1, status = "ACTIVE")
                redeem(quote)
                delivery(quote)
                correct(source, 1)
                val firstCorrection = UUID.randomUUID().toString()
                delivery(quote, false, firstCorrection)
                delivery(quote)
                val secondCorrection = UUID.randomUUID().toString()
                delivery(quote, false, secondCorrection)
                val cancellation = UUID.randomUUID().toString()
                cancelCompensation(quote, cancellation)
                val orders = VenueOrdersRepository(ds, loyaltyRepository = repo)
                assertTrue(orders.loadAudit(venue, quote.order).isEmpty())

                fun state() =
                    ds.connection.use { c ->
                        listOf(
                            "orders", "order_batch_items", "venue_accounting_sales", "guest_loyalty_ledger",
                            "guest_loyalty_progress", "loyalty_redemptions", "audit_log", "loyalty_delivery_operations",
                            "loyalty_compensation_cancellations", "loyalty_unit_corrections",
                            "loyalty_correction_expenses",
                            "order_promotion_applications", "order_batch_item_promotion_adjustments",
                        ).associateWith { table ->
                            c.lq("SELECT * FROM $table") { r -> (1..r.metaData.columnCount).map { r.getString(it) } }
                                .sortedBy { it.toString() }
                        }
                    }
                val before = state()
                val history = orders.loadOrderDetail(venue, quote.order)!!.loyaltyHistory
                assertEquals(5, history.size)
                assertEquals(2, history.count { it.action == "DELIVERY_CORRECTED" })
                assertEquals(2, history.count { it.action == "DELIVERY_CONFIRMED" })
                assertTrue(history.filter { it.action == "DELIVERY_CONFIRMED" }.all { it.reason == null })
                assertEquals(1, history.count { it.action == "COMPENSATION_CANCELED" })
                assertEquals(5, history.map { it.id }.distinct().size)
                assertEquals(
                    history,
                    history.sortedWith(
                        compareBy<OrderLoyaltyHistoryEntry> {
                            java.time.Instant.parse(it.occurredAt)
                        }.thenBy { it.id },
                    ),
                )
                assertTrue(
                    history.all {
                        it.actorUserId == 9001001L && it.batchItemId == quote.line &&
                            it.itemName == "Hookah" && it.compensationCancellation
                    },
                )
                ds.connection.use { c ->
                    val facts =
                        c.lq(
                            "SELECT id,actor_user_id,created_at,payload_json,action FROM audit_log " +
                                "WHERE action IN ('LOYALTY_DELIVERY_CORRECTED','LOYALTY_REWARD_APPLIED')",
                        ) {
                            val prefix =
                                if (it.getString(5) == "LOYALTY_REWARD_APPLIED") {
                                    "delivery-confirmation"
                                } else {
                                    "delivery-correction"
                                }
                            listOf(
                                "$prefix:${it.getLong(1)}", it.getLong(2).toString(),
                                it.getTimestamp(3).toInstant().toString(),
                                Json.parseToJsonElement(it.getString(4))
                                    .jsonObject["reason"]?.jsonPrimitive?.contentOrNull,
                            )
                        } +
                            c.lq(
                                "SELECT redemption_id,actor_user_id,created_at,reason " +
                                    "FROM loyalty_compensation_cancellations",
                            ) {
                                listOf(
                                    "compensation-cancellation:${it.getLong(1)}", it.getLong(2).toString(),
                                    it.getTimestamp(3).toInstant().toString(), it.getString(4),
                                )
                            }
                    assertEquals(
                        facts.sortedBy { it.first() },
                        history.map {
                            listOf(it.id, it.actorUserId.toString(), it.occurredAt, it.reason)
                        }.sortedBy { it.first() },
                    )
                    assertTrue(c.loyaltyOrderHistory(venue + 1000, quote.order).isEmpty())
                }
                repeat(
                    3,
                ) {
                    assertEquals(
                        history,
                        VenueOrdersRepository(ds).loadOrderDetail(venue, quote.order)!!.loyaltyHistory,
                    )
                }
                assertNull(orders.loadOrderDetail(venue + 1000, quote.order))
                assertEquals(before, state())
                delivery(quote, false, secondCorrection)
                assertTrue(cancelCompensation(quote, cancellation))
                assertEquals(history, orders.loadOrderDetail(venue, quote.order)!!.loyaltyHistory)
                assertEquals(before, state())
                sql(
                    "UPDATE audit_log SET created_at=TIMESTAMP '2026-11-10 10:00:00' " +
                        "WHERE action IN ('LOYALTY_DELIVERY_CORRECTED','LOYALTY_REWARD_APPLIED')",
                )
                sql("UPDATE loyalty_compensation_cancellations SET created_at=TIMESTAMP '2026-11-10 10:00:00'")
                val tied = orders.loadOrderDetail(venue, quote.order)!!.loyaltyHistory
                assertEquals(tied.map { it.id }.sorted(), tied.map { it.id })
            }
        }

    @Test
    fun `review P2 applied only rewards keep own guest program visible with zero current balance`() =
        runBlocking {
            fixture {
                earn(order(2).order)
                val quote = order(1, status = "ACTIVE")
                redeem(quote)
                delivery(quote)
                val selected = repo.getGuestProgress(venue, 9001002)!!
                assertEquals(
                    listOf(0, 0, 0, 1),
                    listOf(
                        selected.progressCount,
                        selected.rewardsAvailable,
                        selected.rewardsReserved,
                        selected.rewardsApplied,
                    ),
                )
                assertEquals(listOf(selected), repo.listGuestProgress(9001002))
                assertEquals(listOf(selected), LoyaltyRepository(ds).listGuestProgress(9001002))
                sql("INSERT INTO guest_loyalty_progress(program_id,venue_id,user_id) VALUES ($program,$venue,9001003)")
                assertTrue(repo.listGuestProgress(9001003).isEmpty())
                assertNull(repo.getGuestProgress(venue + 1000, 9001002))
                earn(order(1).order)
                assertEquals(1, repo.listGuestProgress(9001002).single().progressCount)
                earn(order(1).order)
                val next = order(1, status = "ACTIVE")
                redeem(next)
                delivery(next)
                val used = repo.listGuestProgress(9001002).single()
                assertEquals(2, used.rewardsApplied)
                assertEquals(listOf(0, 0, 0), listOf(used.progressCount, used.rewardsAvailable, used.rewardsReserved))
                for (status in listOf("PAUSED", "ARCHIVED")) {
                    sql("UPDATE loyalty_programs SET status='$status' WHERE id=$program")
                    assertEquals(2, repo.listGuestProgress(9001002).single().rewardsApplied)
                    assertEquals(repo.getGuestProgress(venue, 9001002), repo.listGuestProgress(9001002).single())
                }
                sql("UPDATE loyalty_programs SET status='DRAFT' WHERE id=$program")
                assertTrue(repo.listGuestProgress(9001002).isEmpty())
                assertNull(repo.getGuestProgress(venue, 9001002))
            }
        }

    @Test
    fun `review P2 guest balances distinguish earned reserve return from invalid compensation cancellation`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                val quote = order(1, status = "ACTIVE")
                redeem(quote)
                sql("UPDATE order_batch_items SET item_status='CANCELED' WHERE id=${quote.line}")
                ds.connection.use { c ->
                    c.autoCommit = false
                    repo.reconcileOrder(c, venue, quote.order, 9001001)
                    c.commit()
                }
                assertEquals(1, repo.listGuestProgress(9001002).single().rewardsAvailable)
                val next = order(1, status = "ACTIVE")
                redeem(next)
                correct(source, 1)
                cancelCompensation(next)
                val current = repo.getGuestProgress(venue, 9001002)!!
                assertEquals(0, current.rewardsAvailable)
                assertEquals(0, current.rewardsReserved)
                assertEquals(0, current.rewardsApplied)
                assertEquals(current, repo.listGuestProgress(9001002).single())
                assertTrue(current.progressCount >= 0)
            }
        }

    @Test
    fun `first fully paid purchase threshold multiple rewards and persisted exact retry`() =
        runBlocking {
            fixture {
                val first = order(1)
                ds.connection.use { c ->
                    assertFailsWith<IllegalStateException> { repo.accrueForClosedOrder(c, first.order) }
                }
                assertEquals(1, earn(first.order).progressAdded)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.progressCount)
                assertEquals(0, earn(first.order).itemsCounted)
                earn(order(5).order)
                assertEquals(3, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.progressCount)
                assertEquals(0, LoyaltyRepository(ds).accrueForClosedOrder(first.order).itemsCounted)
                assertEquals(6L, number("SELECT count(*) FROM guest_loyalty_ledger"))
                assertEquals(6L, number("SELECT sum(delta_progress) FROM guest_loyalty_ledger"))
                val replacement = repo.createProgram(venue, 5, 9001001)
                repo.setProgramStatus(venue, replacement.id, LoyaltyProgramStatus.ACTIVE)
                assertEquals(0, earn(first.order).itemsCounted)
                assertEquals(6L, number("SELECT count(*) FROM guest_loyalty_ledger"))
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.progressCount)
            }
        }

    @Test
    fun `earn uses immutable price and option snapshots never current price or missing price`() =
        runBlocking {
            fixture {
                val paid = order(1)
                sql("UPDATE menu_items SET price_minor=0 WHERE id=$item")
                assertEquals(1, earn(paid.order).progressAdded)
                val free = order(1, price = 0)
                sql("UPDATE menu_items SET price_minor=5000 WHERE id=$item")
                assertEquals(0, earn(free.order).progressAdded)
                val missing = order(1)
                sql("UPDATE order_batch_items SET base_unit_price_minor_snapshot=NULL WHERE id=${missing.line}")
                assertEquals(0, earn(missing.order).progressAdded)
                val option = order(1, price = 0)
                sql(
                    "INSERT INTO order_batch_item_options" +
                        " (order_batch_item_id," +
                        "option_name_snapshot,price_delta_minor_snapshot) VALUES (${option.line},'Flavor',100)",
                )
                assertEquals(1, earn(option.order).progressAdded)
            }
        }

    @Test
    fun `excluded canceled rejected free and wrong target lines do not earn`() =
        runBlocking {
            fixture {
                val excluded = order(1)
                sql("UPDATE order_batch_items SET is_excluded=TRUE WHERE id=${excluded.line}")
                val canceled = order(1)
                sql("UPDATE order_batch_items SET item_status='CANCELED' WHERE id=${canceled.line}")
                val rejected = order(1)
                sql("UPDATE order_batches SET status='REJECTED' WHERE id=${rejected.batch}")
                for (o in listOf(
                    excluded,
                    canceled,
                    rejected,
                    order(1, price = 0),
                )) assertEquals(0, earn(o.order).itemsCounted)
                val other = order(1)
                sql("UPDATE order_batch_items SET loyalty_semantic_type_snapshot='OTHER' WHERE id=${other.line}")
                assertEquals(0, earn(other.order).itemsCounted)
                assertEquals(0L, number("SELECT count(*) FROM guest_loyalty_ledger"))
            }
        }

    @Test
    fun `foreign or missing program activation cannot pause own active program`() =
        runBlocking {
            fixture {
                sql("INSERT INTO venues (name,city,address,status) VALUES ('Other','Test','Test','PUBLISHED')")
                val other = number("SELECT max(id) FROM venues")
                val foreign = repo.createProgram(other, 5, 9001001)
                assertNull(repo.setProgramStatus(venue, foreign.id, LoyaltyProgramStatus.ACTIVE))
                assertNull(repo.setProgramStatus(venue, Long.MAX_VALUE, LoyaltyProgramStatus.ACTIVE))
                assertEquals(program, repo.getActiveProgram(venue)?.id)
                val unchanged = repo.getProgramForVenue(venue, program)
                assertEquals(unchanged, repo.setProgramStatus(venue, program, LoyaltyProgramStatus.ACTIVE))
                assertEquals(LoyaltyProgramStatus.DRAFT, repo.getProgramForVenue(other, foreign.id)?.status)
                repo.setProgramStatus(venue, program, LoyaltyProgramStatus.PAUSED)
                val closed = order(2)
                assertEquals(0, earn(closed.order).itemsCounted)
                repo.setProgramStatus(venue, program, LoyaltyProgramStatus.ACTIVE)
                assertEquals(0, earn(closed.order).rewardsAdded)
                assertEquals(1, earn(order(2).order).rewardsAdded)
            }
        }

    @Test
    fun `parallel same event retries and different orders earn exactly once without deadlock`() =
        runBlocking {
            fixture {
                val a = order(1)
                val b = order(1)
                val held = if (postgres) ds.connection else null
                try {
                    held?.let { c ->
                        c.autoCommit = false
                        c.createStatement().use {
                            it.executeQuery("SELECT id FROM venues WHERE id=$venue FOR NO KEY UPDATE").close()
                        }
                    }
                    coroutineScope {
                        val attempts =
                            listOf(a, a, b, b).map { o ->
                                async(Dispatchers.IO) { earn(o.order) }
                            }
                        held?.let { c ->
                            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                            var waiting = false
                            try {
                                while (!waiting && System.nanoTime() < deadline) {
                                    c.createStatement().use { statement ->
                                        statement.execute("SELECT pg_stat_clear_snapshot()")
                                        statement.executeQuery(
                                            "SELECT count(*) FROM pg_stat_activity " +
                                                "WHERE pg_backend_pid() = ANY(pg_blocking_pids(pid))",
                                        ).use { rs ->
                                            rs.next()
                                            waiting = rs.getInt(1) >= 1
                                        }
                                    }
                                    Thread.yield()
                                }
                            } finally {
                                c.commit()
                            }
                            assertTrue(waiting, "Accrual must wait on the actual PostgreSQL venue lock")
                        }
                        attempts.forEach { it.await() }
                    }
                } finally {
                    held?.close()
                }
                assertEquals(2L, number("SELECT count(*) FROM guest_loyalty_ledger"))
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.progressCount)
            }
        }

    @Test
    fun `shared tab credits batch guests separately and personal tab resolves staff entered purchase`() =
        runBlocking {
            fixture {
                val a = order(1)
                val b = order(1)
                val session = number("SELECT table_session_id FROM orders WHERE id=${a.order}")
                sql(
                    "INSERT INTO tab (venue_id,table_session_id,type,status) " +
                        "VALUES ($venue,$session,'SHARED','ACTIVE')",
                )
                val shared = number("SELECT max(id) FROM tab")
                sql("UPDATE order_batches SET order_id=${a.order},author_user_id=9001003 WHERE id=${b.batch}")
                sql("UPDATE order_batches SET tab_id=$shared WHERE order_id=${a.order}")
                earn(a.order)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.progressCount)
                assertEquals(1, repo.getGuestProgress(venue, 9001003)?.progressCount)
                val personal = order(1)
                val personalSession = number("SELECT table_session_id FROM orders WHERE id=${personal.order}")
                sql(
                    "INSERT INTO tab (venue_id,table_session_id,type,owner_user_id,status) " +
                        "VALUES ($venue,$personalSession,'PERSONAL',9001002,'ACTIVE')",
                )
                val tab = number("SELECT max(id) FROM tab")
                sql("UPDATE order_batches SET author_user_id=9001001,tab_id=$tab WHERE id=${personal.batch}")
                earn(personal.order)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(1, repo.getGuestProgress(venue, 9001003)?.progressCount)
                assertEquals(0, repo.getGuestProgress(venue, 9001001)?.progressCount)
            }
        }

    @Test
    fun `preview is read only redemption is durable and another guest cannot spend reward`() =
        runBlocking {
            fixture {
                earn(order(2).order)
                val o = order(1, status = "ACTIVE")
                val preview =
                    repo.previewRedemptionForCart(
                        venue,
                        9001002,
                        listOf(LoyaltyCartItem(null, item, "Hookah", 1, 1000, "RUB")),
                    )
                assertEquals(1000L, preview?.discountMinor)
                val session = number("SELECT table_session_id FROM orders WHERE id=${o.order}")
                assertEquals(false, repo.rewardUsedInSession(venue, program, 9001002, session))
                assertEquals(0L, number("SELECT count(*) FROM loyalty_redemptions"))
                assertNull(redeem(o, 9001003))
                assertEquals(1000L, redeem(o)?.discount?.discountMinor)
                assertNull(redeem(o))
                assertEquals(true, repo.rewardUsedInSession(venue, program, 9001002, session))
                assertEquals(false, repo.rewardUsedInSession(venue, program, 9001003, session))
                assertNull(
                    repo.previewRedemptionForCart(
                        venue,
                        9001002,
                        listOf(LoyaltyCartItem(null, item, "Hookah", 1, 1000, "RUB")),
                    ),
                )
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(1L, number("SELECT count(*) FROM loyalty_redemptions WHERE status='RESERVED'"))
                assertEquals(1L, number("SELECT count(*) FROM order_batch_item_promotion_adjustments"))
                assertEquals(1000L, number("SELECT discount_minor FROM order_batch_item_promotion_adjustments"))
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsReserved)
            }
        }

    @Test
    fun `parallel checkout transactions cannot spend one reward twice`() =
        runBlocking {
            fixture {
                earn(order(2).order)
                val a = order(1, status = "ACTIVE")
                val b = order(1, status = "ACTIVE")
                val results =
                    coroutineScope { listOf(a, b).map { async(Dispatchers.IO) { redeem(it) } }.map { it.await() } }
                assertEquals(1, results.count { it != null })
                assertEquals(1L, number("SELECT count(*) FROM loyalty_redemptions"))
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
            }
        }

    @Test
    fun `ledger failure rolls earn and redemption financial writes back atomically`() =
        runBlocking {
            fixture {
                val o = order(2)
                sql("ALTER TABLE guest_loyalty_ledger ADD CONSTRAINT deny_earn CHECK (event_type <> 'EARN')")
                assertFailsWith<com.hookah.platform.backend.api.DatabaseUnavailableException> {
                    earn(
                        o.order,
                    )
                }
                assertEquals(0L, number("SELECT count(*) FROM guest_loyalty_progress"))
                sql("ALTER TABLE guest_loyalty_ledger DROP CONSTRAINT deny_earn")
                earn(o.order)
                sql("ALTER TABLE guest_loyalty_ledger ADD CONSTRAINT deny_redeem CHECK (delta_rewards >= 0)")
                assertFailsWith<SQLException> { redeem(order(1, status = "ACTIVE")) }
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(0L, number("SELECT count(*) FROM loyalty_redemptions"))
                assertEquals(0L, number("SELECT count(*) FROM order_promotion_applications"))
                assertEquals(0L, number("SELECT count(*) FROM order_batch_item_promotion_adjustments"))
            }
        }

    @Test
    fun `closed and partial receipts do not earn full settlement earns once`() =
        runBlocking {
            fixture {
                val o = order(2)
                assertEquals(0, repo.accrueForClosedOrder(o.order).progressAdded)
                payment(o, 999)
                assertEquals(0L, number("SELECT count(*) FROM guest_loyalty_ledger"))
                val key = UUID.randomUUID().toString()
                val before = sale(o.order)
                val cmd =
                    AccountingCommand(
                        key,
                        "PAY",
                        shiftId = shift,
                        saleId = before.id,
                        expectedVersion = before.version,
                        paymentMethod = "CASH",
                        amountMinor = 1001,
                    )
                AccountingRepository(ds).execute(venue, 9001001, cmd)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(
                    2L,
                    number(
                        "SELECT count(*) FROM guest_loyalty_ledger WHERE event_type='EARN' AND " +
                            "payment_fact IS NOT NULL",
                    ),
                )
                AccountingRepository(ds).execute(venue, 9001001, cmd)
                assertEquals(2L, number("SELECT count(*) FROM guest_loyalty_ledger"))
                assertEquals(2000L, sale(o.order).paid)
            }
        }

    @Test
    fun `payment without authoritative fulfillment or guest identity earns nothing`() =
        runBlocking {
            fixture {
                val notIssued = order(2)
                sql("UPDATE order_batch_items SET fulfilled_at=NULL WHERE id=${notIssued.line}")
                earn(notIssued.order)
                val unattributed = order(2)
                sql("UPDATE order_batches SET author_user_id=NULL WHERE id=${unattributed.batch}")
                earn(unattributed.order)
                assertEquals(0L, number("SELECT count(*) FROM guest_loyalty_ledger"))
            }
        }

    @Test
    fun `cycle terms and earned reward survive edits and new cycle uses current targets`() =
        runBlocking {
            fixture {
                earn(order(1).order)
                val oldItem = item
                sql(
                    "INSERT INTO menu_items " +
                        "(venue_id,category_id,name,price_minor,currency) SELECT " +
                        "venue_id,category_id,'New hookah',1000,'RUB' FROM menu_items WHERE " +
                        "id=$item",
                )
                val newItem = number("SELECT max(id) FROM menu_items")
                repo.createOrUpdateDraftProgram(venue, 4, 9001001)
                sql("DELETE FROM loyalty_program_earn_targets WHERE program_id=$program")
                sql(
                    "INSERT INTO loyalty_program_earn_targets " +
                        "(program_id,target_type,menu_item_id) VALUES " +
                        "($program,'MENU_ITEM',$newItem)",
                )
                sql("DELETE FROM loyalty_program_reward_targets WHERE program_id=$program")
                sql(
                    "INSERT INTO loyalty_program_reward_targets " +
                        "(program_id,target_type,menu_item_id) VALUES " +
                        "($program,'MENU_ITEM',$newItem)",
                )
                assertEquals(
                    listOf("Кальяны из меню"),
                    repo.getGuestProgress(venue, 9001002)?.cycles?.single()?.earnTargets,
                )
                assertEquals(
                    listOf("Кальяны из меню"),
                    repo.getGuestProgress(venue, 9001002)?.cycles?.single()?.rewardTargets,
                )
                assertEquals(1, earn(order(1).order).rewardsAdded)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(0, earn(order(1).order).progressAdded)
                assertTrue(redeem(order(1, status = "ACTIVE")) != null)
                item = newItem
                assertEquals(1, earn(order(3).order).rewardsAdded)
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM loyalty_accumulation_cycles c JOIN " +
                            "loyalty_terms_versions t ON t.id=c.terms_id WHERE t.nth_value=4 AND " +
                            "c.state='AVAILABLE'",
                    ),
                )
                assertTrue(oldItem != newItem)
            }
        }

    @Test
    fun `reserved cancellation restores balance and visit slot exactly once`() =
        runBlocking {
            fixture {
                earn(order(4).order)
                val o = order(1, status = "ACTIVE")
                assertTrue(redeem(o) != null)
                assertNull(redeem(lineOn(o)))
                val ops = VenueOrdersRepository(ds, loyaltyRepository = repo)
                val actor = OrderActionActor(9001001, VenueRole.OWNER)
                assertTrue(ops.excludeBatchItemFromBill(venue, o.order, o.line, "Отмена до выдачи", actor))
                assertTrue(ops.excludeBatchItemFromBill(venue, o.order, o.line, "Повтор отмены", actor))
                assertEquals(2, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsReserved)
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM guest_loyalty_ledger WHERE dedupe_key LIKE " +
                            "'loyalty:release:%'",
                    ),
                )
                assertFailsWith<com.hookah.platform.backend.api.InvalidInputException> {
                    ops.restoreBatchItemToBill(venue, o.order, o.line, actor)
                }
                assertTrue(redeem(lineOn(o)) != null)
            }
        }

    @Test
    fun `two shared guests redeem independently while concurrent batches respect guest session limit`() =
        runBlocking {
            fixture {
                earn(order(4).order)
                val paidB = order(2)
                sql("UPDATE order_batches SET author_user_id=9001003 WHERE id=${paidB.batch}")
                earn(paidB.order)
                val o = order(1, status = "ACTIVE")
                val another = lineOn(o)
                val attempts =
                    coroutineScope {
                        listOf(
                            o,
                            another,
                        ).map { async(Dispatchers.IO) { redeem(it) } }.map { it.await() }
                    }
                assertEquals(1, attempts.count { it != null })
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                val guestB = lineOn(o, 9001003)
                assertTrue(redeem(guestB, 9001003) != null)
                assertEquals(2L, number("SELECT count(*) FROM loyalty_redemptions WHERE status='RESERVED'"))
                assertEquals(
                    2L,
                    number("SELECT count(DISTINCT user_id) FROM loyalty_redemptions WHERE status='RESERVED'"),
                )
                val duplicateLine = lineOn(o).line
                assertFailsWith<SQLException> {
                    sql(
                        "INSERT INTO loyalty_redemptions " +
                            "(program_id,venue_id,user_id,order_id,batch_i" +
                            "d,order_batch_item_id,order_promotion_applica" +
                            "tion_id,status,discount_minor,dedupe_key,orig" +
                            "in,terms_id,table_session_id,active_slot) " +
                            "SELECT " +
                            "program_id,venue_id,user_id,order_id,batch_id" +
                            ",$duplicateLine,order_promotion_application_i" +
                            "d,'RESERVED',discount_minor,'forged-duplicate" +
                            "','PAYMENT_POLICY',terms_id,table_session_id," +
                            "1 FROM loyalty_redemptions WHERE " +
                            "user_id=9001002",
                    )
                }
            }
        }

    @Test
    fun `existing delivered action applies reservation and cancellation after issue does not refund`() =
        runBlocking {
            fixture {
                earn(order(2).order)
                val o = order(1, status = "ACTIVE")
                redeem(o)
                val ops = VenueOrdersRepository(ds, loyaltyRepository = repo)
                val actor = OrderActionActor(9001001, VenueRole.OWNER)
                assertTrue(ops.deliverAllAcceptedBatches(venue, o.order, actor)?.applied == true)
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM loyalty_redemptions WHERE status='APPLIED' AND " +
                            "issued_at IS NOT NULL",
                    ),
                )
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsReserved)
                assertTrue(ops.deliverAllAcceptedBatches(venue, o.order, actor)?.applied == false)
                assertTrue(ops.excludeBatchItemFromBill(venue, o.order, o.line, "Отмена после выдачи", actor))
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                val key = UUID.randomUUID().toString()
                delivery(o, confirm = false, id = key)
                delivery(o, confirm = false, id = key)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(1L, number("SELECT count(*) FROM loyalty_delivery_operations WHERE action='CORRECT'"))
            }
        }

    @Test
    fun `durable delivery replay cannot reapply after manager correction and staff cannot correct`() =
        runBlocking {
            fixture {
                earn(order(2).order)
                val o = order(1, status = "ACTIVE")
                redeem(o)
                val confirmed = UUID.randomUUID().toString()
                delivery(o, id = confirmed)
                sql("INSERT INTO venue_members (venue_id,user_id,role) VALUES ($venue,9001003,'STAFF')")
                assertFailsWith<com.hookah.platform.backend.api.ForbiddenException> {
                    delivery(o, false, actor = 9001003, role = VenueRole.STAFF)
                }
                delivery(o, false)
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM loyalty_redemptions WHERE status='RESERVED' AND " +
                            "issued_at IS NULL",
                    ),
                )
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsReserved)
                delivery(o, id = confirmed)
                assertEquals(0L, number("SELECT count(*) FROM loyalty_redemptions WHERE status='APPLIED'"))
                assertFailsWith<com.hookah.platform.backend.api.InvalidInputException> {
                    delivery(
                        o,
                        false,
                        id = confirmed,
                    )
                }
                delivery(o)
                assertEquals(1L, number("SELECT count(*) FROM loyalty_redemptions WHERE status='APPLIED'"))
                sql("UPDATE venue_members SET role='ADMIN' WHERE venue_id=$venue AND user_id=9001001")
                delivery(o, false, role = VenueRole.MANAGER)
                assertEquals(1L, number("SELECT count(*) FROM loyalty_redemptions WHERE status='RESERVED'"))
                delivery(o, role = VenueRole.MANAGER)
                assertEquals(1L, number("SELECT count(*) FROM loyalty_redemptions WHERE status='APPLIED'"))
                sql("DELETE FROM venue_members WHERE venue_id=$venue AND user_id=9001001")
                assertFailsWith<com.hookah.platform.backend.api.ForbiddenException> { delivery(o, false) }
            }
        }

    @Test
    fun `void source receipt compensates unused reward and repayment restores original cycle once`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                voidPayment(source)
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.progressCount)
                assertEquals(2L, number("SELECT count(*) FROM guest_loyalty_ledger WHERE event_type='ADJUST'"))
                assertEquals(2L, number("SELECT count(*) FROM guest_loyalty_ledger WHERE event_type='EARN'"))
                payment(source, 2000)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(1L, number("SELECT count(*) FROM loyalty_accumulation_cycles"))
            }
        }

    @Test
    fun `source correction preserves live promise and used reward expense has no future debt`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                val o = order(1, status = "ACTIVE")
                redeem(o)
                voidPayment(source)
                assertEquals(0L, sale(source.order).paid)
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM loyalty_redemptions " +
                            "WHERE status='RESERVED' AND " +
                            "funding_basis='VENUE_COMPENSATION'",
                    ),
                )
                assertEquals(0L, number("SELECT count(*) FROM loyalty_correction_expenses"))
                VenueOrdersRepository(
                    ds,
                    loyaltyRepository = repo,
                ).excludeBatchItemFromBill(
                    venue,
                    o.order,
                    o.line,
                    "Отмена резерва перед исправлением",
                    OrderActionActor(9001001, VenueRole.OWNER),
                )
                assertEquals(1L, number("SELECT count(*) FROM loyalty_redemptions WHERE status='CANCELED'"))
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsReserved)
                payment(source, 2000)
                val used = lineOn(o)
                redeem(used)
                delivery(used)
                voidPayment(source)
                assertEquals(1L, number("SELECT count(*) FROM loyalty_correction_expenses"))
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                earn(order(2).order)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(1L, number("SELECT count(*) FROM loyalty_correction_expenses"))
                payment(source, 2000)
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM " +
                            "loyalty_correction_expenses WHERE " +
                            "resolved_at IS NOT NULL",
                    ),
                )
                voidPayment(source)
                assertEquals(1L, number("SELECT count(*) FROM loyalty_correction_expenses"))
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM audit_log WHERE " +
                            "action='LOYALTY_VENUE_EXPENSE_REACTIVATED'",
                    ),
                )
                assertEquals(1L, number("SELECT count(*) FROM loyalty_correction_expenses WHERE resolved_at IS NULL"))
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
            }
        }

    @Test
    fun `multiple option units one free unit and paid remainder reconcile bill and accounting`() =
        runBlocking {
            fixture {
                earn(order(2).order)
                val o = order(3, status = "ACTIVE")
                sql(
                    "INSERT INTO order_batch_item_options " +
                        "(order_batch_item_id,option_name_snapshot,price_delta_minor_snapshot) " +
                        "VALUES (${o.line},'Flavor',500)",
                )
                assertEquals(1500L, redeem(o)?.discount?.discountMinor)
                val ops = VenueOrdersRepository(ds, loyaltyRepository = repo)
                val actor = OrderActionActor(9001001, VenueRole.OWNER)
                ops.deliverAllAcceptedBatches(venue, o.order, actor)
                ops.updateOrderStatus(
                    venue,
                    o.order,
                    com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus.CLOSED,
                    actor,
                )
                assertEquals(3000L, sale(o.order).payable)
                assertEquals(2, earn(o.order).progressAdded)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(
                    1L,
                    number(
                        "SELECT quantity FROM order_batch_item_promotion_adjustments WHERE " +
                            "order_batch_item_id=${o.line}",
                    ),
                )
            }
        }

    @Test
    fun `audit failure rolls receipt and earn back and delivery failure preserves reservation`() =
        runBlocking {
            fixture {
                val source = order(2)
                sql("ALTER TABLE audit_log ADD CONSTRAINT deny_loyalty_audit CHECK (action <> 'LOYALTY_PAID_EARN')")
                assertFailsWith<com.hookah.platform.backend.api.DatabaseUnavailableException> { earn(source.order) }
                assertEquals(0L, sale(source.order).paid)
                assertEquals(0L, number("SELECT count(*) FROM guest_loyalty_ledger"))
                sql("ALTER TABLE audit_log DROP CONSTRAINT deny_loyalty_audit")
                earn(source.order)
                val o = order(1, status = "ACTIVE")
                redeem(o)
                sql(
                    "ALTER TABLE guest_loyalty_ledger ADD CONSTRAINT deny_issue CHECK " +
                        "(dedupe_key NOT LIKE 'loyalty:issued:%')",
                )
                assertFailsWith<Exception> { delivery(o) }
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM loyalty_redemptions WHERE status='RESERVED' AND " +
                            "issued_at IS NULL",
                    ),
                )
                assertEquals(0L, number("SELECT count(*) FROM loyalty_delivery_operations"))
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsReserved)
            }
        }

    @Test
    fun `rejection releases reservation and full manual discount earns no units`() =
        runBlocking {
            fixture {
                earn(order(2).order)
                val o = order(1, status = "ACTIVE")
                redeem(o)
                sql("UPDATE order_batches SET status='NEW' WHERE id=${o.batch}")
                VenueOrdersRepository(
                    ds,
                    loyaltyRepository = repo,
                ).rejectOrder(
                    venue,
                    o.order,
                    "CUSTOMER_REQUEST",
                    "До выдачи",
                    OrderActionActor(9001001, VenueRole.OWNER),
                )
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                val free = order(2)
                sql("UPDATE order_batch_items SET discount_percent=100 WHERE id=${free.line}")
                assertEquals(0, earn(free.order).progressAdded)
                val discounted = order(2)
                sql("UPDATE order_batch_items SET discount_percent=50 WHERE id=${discounted.line}")
                assertEquals(2, earn(discounted.order).progressAdded)
            }
        }

    @Test
    fun `two receipt writers with stale sale version cannot double earn`() =
        runBlocking {
            fixture {
                val o = order(2)
                val original = sale(o.order)
                val commands =
                    (1..2).map {
                        AccountingCommand(
                            UUID.randomUUID().toString(),
                            "PAY",
                            shiftId = shift,
                            saleId = original.id,
                            expectedVersion = original.version,
                            paymentMethod = "CASH",
                            amountMinor = original.payable,
                        )
                    }
                val results =
                    coroutineScope {
                        commands.map { command ->
                            async(Dispatchers.IO) {
                                runCatching { AccountingRepository(ds).execute(venue, 9001001, command) }
                            }
                        }.map { it.await() }
                    }
                assertEquals(1, results.count { it.isSuccess })
                assertTrue(
                    results.single {
                        it.isFailure
                    }.exceptionOrNull() is com.hookah.platform.backend.api.AccountingConflictException,
                )
                val successful = commands[results.indexOfFirst { it.isSuccess }]
                AccountingRepository(ds).execute(venue, 9001001, successful)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(2L, number("SELECT count(*) FROM guest_loyalty_ledger WHERE event_type='EARN'"))
            }
        }

    @Test
    fun `source compensation keeps remaining units across visits without fragmented cycles`() =
        runBlocking {
            fixture {
                val a = order(1)
                val b = order(1)
                earn(a.order)
                earn(b.order)
                earn(order(1).order)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                voidPayment(a)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.progressCount)
                assertEquals(
                    1L,
                    number("SELECT count(*) FROM guest_loyalty_ledger WHERE previous_cycle_id IS NOT NULL"),
                )
                assertEquals(2L, number("SELECT count(*) FROM loyalty_paid_units WHERE active=TRUE"))
                val used = order(1, status = "ACTIVE")
                redeem(used)
                delivery(used)
                payment(a, 1000)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.progressCount)
                earn(order(1).order)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.progressCount)
                assertEquals(4L, number("SELECT count(*) FROM loyalty_paid_units WHERE active=TRUE"))
            }
        }

    @Test
    fun `exact paid unit correction preserves closed multi quantity bills receipts and sponsored promise`() =
        runBlocking {
            fixture {
                val source = order(3)
                earn(source.order)
                val quote = order(3, price = 1700, status = "ACTIVE")
                redeem(quote)
                delivery(quote)
                sql("UPDATE order_batches SET status='DELIVERED' WHERE id=${quote.batch}")
                sql("UPDATE orders SET status='CLOSED' WHERE id=${quote.order}")
                delivery(quote, false)
                val sourceBill = immutableOrder(source)
                val quoteBill = immutableOrder(quote)
                val originalSourceSale = sale(source.order)
                val originalQuoteSale = sale(quote.order)
                val operation = UUID.randomUUID().toString()
                assertEquals(false, correct(source, 1, operation))
                assertEquals(
                    2L,
                    number(
                        "SELECT count(*) FROM loyalty_paid_units " +
                            "WHERE order_id=${source.order} AND " +
                            "active=TRUE",
                    ),
                )
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM " +
                            "loyalty_unit_corrections WHERE unit_index=1 " +
                            "AND actor_user_id=9001001 AND " +
                            "guest_user_id=9001002",
                    ),
                )
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM loyalty_redemptions " +
                            "WHERE status='RESERVED' AND " +
                            "funding_basis='VENUE_COMPENSATION'",
                    ),
                )
                assertEquals(0L, number("SELECT count(*) FROM loyalty_correction_expenses"))
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsReserved)
                assertEquals(sourceBill, immutableOrder(source))
                assertEquals(quoteBill, immutableOrder(quote))
                assertEquals(originalSourceSale, sale(source.order))
                assertEquals(originalQuoteSale, sale(quote.order))
                delivery(quote)
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM loyalty_redemptions " +
                            "WHERE status='APPLIED' AND " +
                            "funding_basis='VENUE_COMPENSATION'",
                    ),
                )
                assertEquals(1700L, number("SELECT amount_minor FROM loyalty_correction_expenses"))
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM " +
                            "loyalty_correction_expenses WHERE " +
                            "issued_at_snapshot IS NOT NULL AND " +
                            "correction_id IS NOT NULL",
                    ),
                )
                assertEquals(true, correct(source, 1, operation))
                assertEquals(1L, number("SELECT count(*) FROM loyalty_correction_expenses"))
                assertEquals(originalSourceSale, sale(source.order))
                assertEquals(originalQuoteSale, sale(quote.order))
                assertEquals(quoteBill, immutableOrder(quote))
            }
        }

    @Test
    fun `canceled sponsored reserve never restores invalid reward or earns on receipt replay`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                val quote = order(1, status = "ACTIVE")
                redeem(quote)
                correct(source, 1)
                VenueOrdersRepository(
                    ds,
                    loyaltyRepository = repo,
                ).excludeBatchItemFromBill(
                    venue,
                    quote.order,
                    quote.line,
                    "Гость отменил до выдачи",
                    OrderActionActor(9001001, VenueRole.OWNER),
                )
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsReserved)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.progressCount)
                assertEquals(0L, number("SELECT count(*) FROM loyalty_correction_expenses"))
                val ledger = number("SELECT count(*) FROM guest_loyalty_ledger")
                VenueOrdersRepository(
                    ds,
                    loyaltyRepository = repo,
                ).excludeBatchItemFromBill(
                    venue,
                    quote.order,
                    quote.line,
                    "Повтор отмены",
                    OrderActionActor(9001001, VenueRole.OWNER),
                )
                ds.connection.use {
                        c ->
                    c.autoCommit = false
                    repo.reconcilePaidSale(c, venue, sale(source.order), "test-replay", 9001001)
                    c.commit()
                }
                assertEquals(ledger, number("SELECT count(*) FROM guest_loyalty_ledger"))
                assertEquals(1L, number("SELECT count(*) FROM loyalty_paid_units WHERE active=TRUE"))
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM loyalty_redemptions " +
                            "WHERE status='CANCELED' AND active_slot IS " +
                            "NULL",
                    ),
                )
            }
        }

    @Test
    fun `already issued bonus has one expense despite multiple unit corrections and menu price changes`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                val quote = order(1, price = 1300, status = "ACTIVE")
                redeem(quote)
                delivery(quote)
                sql("UPDATE menu_items SET price_minor=999999 WHERE id=$item")
                correct(source, 1)
                correct(source, 2)
                assertEquals(1L, number("SELECT count(*) FROM loyalty_correction_expenses"))
                assertEquals(1300L, number("SELECT amount_minor FROM loyalty_correction_expenses"))
                assertEquals(2L, number("SELECT count(*) FROM loyalty_unit_corrections"))
                assertEquals(2L, number("SELECT count(*) FROM guest_loyalty_ledger WHERE event_type='EARN'"))
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.progressCount)
                earn(order(2).order)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(1L, number("SELECT count(*) FROM audit_log WHERE action='LOYALTY_VENUE_EXPENSE'"))
            }
        }

    @Test
    fun `unit correction replay binds actor payload and order and different request refuses corrected unit`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                val id = UUID.randomUUID().toString()
                assertEquals(false, correct(source, 1, id))
                val before = number("SELECT count(*) FROM audit_log")
                assertEquals(true, correct(source, 1, id))
                assertFailsWith<com.hookah.platform.backend.api.InvalidInputException> {
                    correct(source, 1, id, reason = "Другая причина")
                }
                assertFailsWith<com.hookah.platform.backend.api.AccountingConflictException> { correct(source, 1) }
                assertEquals(before, number("SELECT count(*) FROM audit_log"))
                assertEquals(1L, number("SELECT count(*) FROM loyalty_unit_corrections"))
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM guest_loyalty_ledger " +
                            "WHERE paid_unit_id=${unit(source,1)} AND " +
                            "delta_progress=-1",
                    ),
                )
            }
        }

    @Test
    fun `correction and physical issue serialize into one sponsored expense`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                val quote = order(1, status = "ACTIVE")
                redeem(quote)
                concurrent({ correct(source, 1) }, { delivery(quote) })
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM loyalty_redemptions " +
                            "WHERE status='APPLIED' AND " +
                            "funding_basis='VENUE_COMPENSATION'",
                    ),
                )
                assertEquals(1L, number("SELECT count(*) FROM loyalty_correction_expenses WHERE resolved_at IS NULL"))
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsReserved)
            }
        }

    @Test
    fun `correction and reserve cancellation serialize without restoring invalid right`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                val quote = order(1, status = "ACTIVE")
                redeem(quote)
                concurrent({ correct(source, 1) }, {
                    VenueOrdersRepository(
                        ds,
                        loyaltyRepository = repo,
                    ).excludeBatchItemFromBill(
                        venue,
                        quote.order,
                        quote.line,
                        "Отмена до выдачи",
                        OrderActionActor(9001001, VenueRole.OWNER),
                    )
                })
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsReserved)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.progressCount)
                assertEquals(0L, number("SELECT count(*) FROM loyalty_correction_expenses"))
            }
        }

    @Test
    fun `two corrections of the same unit have one effect while different units remain independent`() =
        runBlocking {
            fixture {
                val source = order(6)
                earn(source.order)
                val outcomes = java.util.concurrent.ConcurrentLinkedQueue<String>()
                concurrent(
                    {
                        try {
                            correct(source, 1)
                            outcomes.add("done")
                        } catch (
                            e: com.hookah.platform.backend.api.AccountingConflictException,
                        ) {
                            outcomes.add("conflict")
                        }
                    },
                    {
                        try {
                            correct(source, 1)
                            outcomes.add("done")
                        } catch (
                            e: com.hookah.platform.backend.api.AccountingConflictException,
                        ) {
                            outcomes.add("conflict")
                        }
                    },
                )
                assertEquals(listOf("conflict", "done"), outcomes.sorted())
                concurrent({ correct(source, 3) }, { correct(source, 5) })
                assertEquals(true, correct(source, 1, correctionRequest(source, 1)))
                assertEquals(3L, number("SELECT count(*) FROM loyalty_unit_corrections"))
                assertEquals(3L, number("SELECT count(*) FROM loyalty_paid_units WHERE active=TRUE"))
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.progressCount)
            }
        }

    @Test
    fun `checkout race preserves only the actual promise without consuming another earned reward`() =
        runBlocking {
            fixture {
                val source = order(4)
                earn(source.order)
                val quote = order(1, status = "ACTIVE")
                concurrent({ correct(source, 1) }, { redeemKnown(quote) })
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsReserved)
                val sponsored =
                    number("SELECT count(*) FROM loyalty_redemptions WHERE funding_basis='VENUE_COMPENSATION'")
                assertEquals(if (sponsored == 1L) 1 else 0, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(0L, number("SELECT count(*) FROM loyalty_correction_expenses"))
            }
        }

    @Test
    fun `correction requires manager current membership and affects only the attributed guest`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                sql("INSERT INTO users (telegram_user_id) VALUES (9001004)")
                val other = order(2)
                sql(
                    "UPDATE order_batches SET " +
                        "author_user_id=9001004 WHERE " +
                        "id=${other.batch}",
                )
                earn(other.order)
                sql("INSERT INTO venue_members (venue_id,user_id,role) VALUES ($venue,9001003,'STAFF')")
                assertFailsWith<com.hookah.platform.backend.api.ForbiddenException> {
                    correct(source, 1, actor = 9001003, role = VenueRole.STAFF)
                }
                assertFailsWith<com.hookah.platform.backend.api.ForbiddenException> {
                    correct(
                        source,
                        1,
                        actor = 9001004,
                    )
                }
                sql("UPDATE venue_members SET role='ADMIN' WHERE venue_id=$venue AND user_id=9001001")
                val request = UUID.randomUUID().toString()
                correct(source, 1, request, role = VenueRole.MANAGER)
                assertEquals(1, repo.getGuestProgress(venue, 9001004)?.rewardsAvailable)
                sql("DELETE FROM venue_members WHERE venue_id=$venue AND user_id=9001001")
                assertFailsWith<com.hookah.platform.backend.api.ForbiddenException> {
                    correct(source, 1, request, role = VenueRole.MANAGER)
                }
            }
        }

    @Test
    fun `missing unit identity refuses before any financial or correction writes`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                sql("UPDATE order_batch_items SET base_unit_price_minor_snapshot=NULL WHERE id=${source.line}")
                val before = number("SELECT count(*) FROM audit_log")
                assertFailsWith<com.hookah.platform.backend.api.InvalidInputException> { correct(source, 1) }
                assertEquals(0L, number("SELECT count(*) FROM loyalty_unit_corrections"))
                assertEquals(before, number("SELECT count(*) FROM audit_log"))
                assertEquals(2L, number("SELECT count(*) FROM loyalty_paid_units WHERE active=TRUE"))
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
            }
        }

    @Test
    fun `ledger audit and venue expense failures roll exact unit correction back`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                val quote = order(1, status = "ACTIVE")
                redeem(quote)
                delivery(quote)
                val ledger = number("SELECT count(*) FROM guest_loyalty_ledger")
                val audit = number("SELECT count(*) FROM audit_log")
                val commands =
                    listOf(
                        "ALTER TABLE guest_loyalty_ledger ADD " +
                            "CONSTRAINT unit_fault CHECK (NOT " +
                            "(event_type='ADJUST' AND paid_unit_id IS NOT " +
                            "NULL AND delta_progress<0))",
                        "ALTER TABLE audit_log ADD CONSTRAINT unit_fault CHECK (action<>'LOYALTY_PAID_UNIT_CORRECTED')",
                        "ALTER TABLE loyalty_correction_expenses ADD CONSTRAINT unit_fault CHECK (amount_minor<0)",
                    )
                for ((i, command) in commands.withIndex()) {
                    sql(command)
                    assertFailsWith<com.hookah.platform.backend.api.DatabaseUnavailableException> { correct(source, 1) }
                    sql(
                        "ALTER TABLE ${listOf(
                            "guest_loyalty_ledger",
                            "audit_log",
                            "loyalty_correction_expenses",
                        )[i]} DROP CONSTRAINT unit_fault",
                    )
                    assertEquals(0L, number("SELECT count(*) FROM loyalty_unit_corrections"))
                    assertEquals(2L, number("SELECT count(*) FROM loyalty_paid_units WHERE active=TRUE"))
                    assertEquals(
                        0L,
                        number(
                            "SELECT count(*) FROM loyalty_redemptions " +
                                "WHERE funding_basis='VENUE_COMPENSATION'",
                        ),
                    )
                    assertEquals(ledger, number("SELECT count(*) FROM guest_loyalty_ledger"))
                    assertEquals(audit, number("SELECT count(*) FROM audit_log"))
                }
                correct(source, 1)
                assertEquals(1L, number("SELECT count(*) FROM loyalty_correction_expenses"))
            }
        }

    @Test
    fun `closed sponsored unit cancellation preserves paid neighbors and never earns for canceled free unit`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                val quote = order(3, status = "ACTIVE")
                redeem(quote)
                delivery(quote)
                sql("UPDATE order_batches SET status='DELIVERED' WHERE id=${quote.batch}")
                sql(
                    "UPDATE order_batch_items SET " +
                        "fulfilled_at=CURRENT_TIMESTAMP,fulfilled_by_u" +
                        "ser_id=9001001 WHERE id=${quote.line}",
                )
                sql("UPDATE orders SET status='CLOSED' WHERE id=${quote.order}")
                delivery(quote, false)
                correct(source, 1)
                val immutable = immutableOrder(quote)
                val beforeSale = sale(quote.order)
                val operation = UUID.randomUUID().toString()
                assertEquals(false, cancelCompensation(quote, operation))
                assertEquals(true, cancelCompensation(quote, operation))
                assertEquals(immutable, immutableOrder(quote))
                assertEquals(beforeSale, sale(quote.order))
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsReserved)
                assertEquals(0L, number("SELECT count(*) FROM loyalty_correction_expenses"))
                assertEquals(1L, number("SELECT count(*) FROM loyalty_compensation_cancellations"))
                assertFailsWith<com.hookah.platform.backend.api.AccountingConflictException> {
                    cancelCompensation(
                        quote,
                    )
                }
                assertFailsWith<com.hookah.platform.backend.api.InvalidInputException> { delivery(quote) }
                payment(quote, 2000)
                assertEquals(
                    2L,
                    number(
                        "SELECT count(*) FROM loyalty_paid_units " +
                            "WHERE order_id=${quote.order} AND active=TRUE",
                    ),
                )
                assertEquals(
                    0L,
                    number(
                        "SELECT count(*) FROM loyalty_paid_units " +
                            "WHERE order_id=${quote.order} AND " +
                            "unit_index=1",
                    ),
                )
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.progressCount)
            }
        }

    @Test
    fun `mistaken sponsored issue correction preserves commitment and later issue has one active expense`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                val quote = order(1, status = "ACTIVE")
                redeem(quote)
                delivery(quote)
                correct(source, 1)
                delivery(quote, false)
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM loyalty_redemptions " +
                            "WHERE status='RESERVED' AND " +
                            "funding_basis='VENUE_COMPENSATION'",
                    ),
                )
                assertEquals(0L, number("SELECT count(*) FROM loyalty_correction_expenses WHERE resolved_at IS NULL"))
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                val confirmed = UUID.randomUUID().toString()
                delivery(quote, id = confirmed)
                delivery(quote, id = confirmed)
                assertEquals(1L, number("SELECT count(*) FROM loyalty_correction_expenses WHERE resolved_at IS NULL"))
                assertEquals(
                    2L,
                    number("SELECT count(DISTINCT delivery_version_snapshot) FROM loyalty_correction_expenses"),
                )
                assertEquals(
                    1000L,
                    number(
                        "SELECT sum(amount_minor) FROM " +
                            "loyalty_correction_expenses WHERE " +
                            "resolved_at IS NULL",
                    ),
                )
                assertFailsWith<com.hookah.platform.backend.api.AccountingConflictException> {
                    cancelCompensation(
                        quote,
                    )
                }
            }
        }

    @Test
    fun `financial unit correction and compensation cancellation deny foreign scope and staff even on replay`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                val quote = order(1, status = "ACTIVE")
                redeem(quote)
                sql("INSERT INTO venues(name,city,address,status) VALUES ('Other','T','T','PUBLISHED')")
                val other = number("SELECT max(id) FROM venues")
                sql(
                    "INSERT INTO venue_members " +
                        "(venue_id,user_id,role) VALUES " +
                        "($other,9001001,'OWNER'),($venue,9001003,'STA" +
                        "FF')",
                )
                assertFailsWith<com.hookah.platform.backend.api.ForbiddenException> {
                    VenueOrdersRepository(
                        ds,
                        loyaltyRepository = repo,
                    ).correctLoyaltyUnit(
                        other,
                        source.order,
                        unit(source, 1),
                        OrderActionActor(9001001, VenueRole.OWNER),
                        UUID.randomUUID().toString(),
                        "Чужое заведение",
                    )
                }
                correct(source, 1)
                assertFailsWith<com.hookah.platform.backend.api.ForbiddenException> {
                    cancelCompensation(quote, actor = 9001003, role = VenueRole.STAFF)
                }
                val request = UUID.randomUUID().toString()
                cancelCompensation(quote, request)
                sql("DELETE FROM venue_members WHERE venue_id=$venue AND user_id=9001001")
                assertFailsWith<com.hookah.platform.backend.api.ForbiddenException> {
                    cancelCompensation(
                        quote,
                        request,
                    )
                }
                assertEquals(1L, number("SELECT count(*) FROM loyalty_compensation_cancellations"))
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
            }
        }

    @Test
    fun `sponsored issue and explicit cancellation have one atomic outcome`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                val quote = order(1, status = "ACTIVE")
                redeem(quote)
                correct(source, 1)
                val outcomes = java.util.concurrent.ConcurrentLinkedQueue<String>()
                concurrent(
                    {
                        try {
                            delivery(quote)
                            outcomes.add("issued")
                        } catch (
                            e: com.hookah.platform.backend.api.InvalidInputException,
                        ) {
                            outcomes.add("canceled-first")
                        }
                    },
                    {
                        try {
                            cancelCompensation(quote)
                            outcomes.add("canceled")
                        } catch (
                            e: com.hookah.platform.backend.api.AccountingConflictException,
                        ) {
                            outcomes.add("issued-first")
                        }
                    },
                )
                assertEquals(2, outcomes.size)
                val applied = number("SELECT count(*) FROM loyalty_redemptions WHERE status='APPLIED'")
                assertEquals(
                    applied,
                    number("SELECT count(*) FROM loyalty_correction_expenses WHERE resolved_at IS NULL"),
                )
                assertEquals(1L - applied, number("SELECT count(*) FROM loyalty_compensation_cancellations"))
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsReserved)
            }
        }

    @Test
    fun `compensation cancellation audit failure leaves promise and slot intact`() =
        runBlocking {
            fixture {
                val source = order(2)
                earn(source.order)
                val quote = order(1, status = "ACTIVE")
                redeem(quote)
                correct(source, 1)
                val ledger = number("SELECT count(*) FROM guest_loyalty_ledger")
                sql(
                    "ALTER TABLE audit_log ADD CONSTRAINT " +
                        "cancellation_fault CHECK " +
                        "(action<>'LOYALTY_COMPENSATION_CANCELED')",
                )
                assertFailsWith<com.hookah.platform.backend.api.DatabaseUnavailableException> {
                    cancelCompensation(
                        quote,
                    )
                }
                sql("ALTER TABLE audit_log DROP CONSTRAINT cancellation_fault")
                assertEquals(0L, number("SELECT count(*) FROM loyalty_compensation_cancellations"))
                assertEquals(
                    1L,
                    number(
                        "SELECT count(*) FROM loyalty_redemptions " +
                            "WHERE status='RESERVED' AND active_slot=1",
                    ),
                )
                assertEquals(1, repo.getGuestProgress(venue, 9001002)?.rewardsReserved)
                assertEquals(0, repo.getGuestProgress(venue, 9001002)?.rewardsAvailable)
                assertEquals(ledger, number("SELECT count(*) FROM guest_loyalty_ledger"))
                cancelCompensation(quote)
                assertEquals(1L, number("SELECT count(*) FROM loyalty_compensation_cancellations"))
            }
        }

    protected data class Order(val order: Long, val batch: Long, val line: Long)

    protected class Fixture(val ds: DataSource) {
        val repo = LoyaltyRepository(ds)
        var venue = 0L
        var table = 0L
        var item = 0L
        var program = 0L
        var shift = 0L

        fun sql(text: String) = ds.connection.use { c -> c.createStatement().use { it.execute(text) } }

        fun number(text: String) =
            ds.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery(text).use {
                        it.next()
                        it.getLong(1)
                    }
                }
            }

        suspend fun cancelCompensation(
            o: Order,
            request: String = UUID.randomUUID().toString(),
            actor: Long = 9001001,
            role: VenueRole = VenueRole.OWNER,
        ): Boolean {
            val id = number("SELECT id FROM loyalty_redemptions WHERE order_batch_item_id=${o.line}")
            return VenueOrdersRepository(
                ds,
                loyaltyRepository = repo,
            ).cancelLoyaltyCompensation(
                venue,
                o.order,
                id,
                OrderActionActor(actor, role),
                request,
                "Бонус не выдан: гость отказался",
            )
        }

        fun unit(
            o: Order,
            index: Int,
        ) = number(
            "SELECT id FROM loyalty_paid_units WHERE " +
                "order_batch_item_id=${o.line} AND " +
                "unit_index=$index",
        )

        fun correctionRequest(
            o: Order,
            index: Int,
        ): String {
            val id = unit(o, index)
            return ds.connection.use { c ->
                c.prepareStatement(
                    "SELECT request_id FROM " +
                        "loyalty_unit_corrections WHERE paid_unit_id=?",
                ).use { st ->
                    st.setLong(1, id)
                    st.executeQuery().use { r ->
                        check(r.next())
                        r.getString(1)
                    }
                }
            }
        }

        suspend fun correct(
            o: Order,
            index: Int,
            request: String = UUID.randomUUID().toString(),
            reason: String = "Ошибочно учтена конкретная единица",
            actor: Long = 9001001,
            role: VenueRole = VenueRole.OWNER,
        ): Boolean =
            VenueOrdersRepository(
                ds,
                loyaltyRepository = LoyaltyRepository(ds),
            ).correctLoyaltyUnit(venue, o.order, unit(o, index), OrderActionActor(actor, role), request, reason)

        suspend fun immutableOrder(o: Order) =
            VenueOrdersRepository(
                ds,
                loyaltyRepository = repo,
            ).loadOrderDetail(
                venue,
                o.order,
            )!!.copy(
                loyaltyRewards = emptyList(),
                loyaltyExpenses = emptyList(),
                loyaltyUnits = emptyList(),
                loyaltyHistory = emptyList(),
            )

        fun redeemKnown(o: Order): LoyaltyRedemptionResult? =
            ds.connection.use { c ->
                c.autoCommit = false
                try {
                    val result =
                        repo.applyRedemptionForBatch(
                            c,
                            o.order,
                            o.batch,
                            venue,
                            9001002,
                            listOf(
                                LoyaltyCheckoutItem(o.line, item, "Hookah", 1, 1000, "RUB", MenuSemanticType.HOOKAH),
                            ),
                        )
                    c.commit()
                    result
                } catch (e: Throwable) {
                    c.rollback()
                    throw e
                }
            }

        suspend fun concurrent(
            a: suspend () -> Any?,
            b: suspend () -> Any?,
        ) {
            val pg = ds.connection.use { it.metaData.databaseProductName.contains("PostgreSQL") }
            val held = if (pg) ds.connection else null
            try {
                held?.let {
                    it.autoCommit = false
                    it.createStatement().use { s ->
                        s.executeQuery(
                            "SELECT id FROM venues WHERE id=$venue FOR NO " +
                                "KEY UPDATE",
                        ).close()
                    }
                }
                coroutineScope {
                    val jobs = listOf(a, b).map { action -> async(Dispatchers.IO) { action() } }
                    held?.let { c ->
                        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
                        var waiting = false
                        try {
                            while (!waiting && System.nanoTime() < deadline) {
                                c.createStatement().use {
                                        st ->
                                    st.execute("SELECT pg_stat_clear_snapshot()")
                                    st.executeQuery(
                                        "SELECT count(*) FROM pg_stat_activity WHERE " +
                                            "pid <> pg_backend_pid() AND " +
                                            "wait_event_type='Lock' AND query LIKE " +
                                            "'%venues%' AND " +
                                            "cardinality(pg_blocking_pids(pid))>0",
                                    ).use { r ->
                                        r.next()
                                        waiting = r.getInt(1) >= 2
                                    }
                                }
                                Thread.yield()
                            }
                        } finally {
                            c.commit()
                        }
                        assertTrue(waiting, "Both actual PostgreSQL writers must wait on the canonical venue lock")
                    }
                    jobs.forEach { it.await() }
                }
            } finally {
                held?.close()
            }
        }

        fun order(
            qty: Int,
            price: Long = 1000,
            status: String = "CLOSED",
            sessionId: Long? = null,
        ): Order {
            if (sessionId == null) {
                sql(
                    "INSERT INTO table_sessions (venue_id,table_id,status,started_at,last_activity_at,expires_at)" +
                        " VALUES ($venue,$table,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP," +
                        "TIMESTAMP '2099-01-01 00:00:00')",
                )
            }
            val session = sessionId ?: number("SELECT max(id) FROM table_sessions")
            sql(
                "INSERT INTO orders (venue_id,table_id,table_session_id,status,accounting_shift_id)" +
                    " VALUES ($venue,$table,$session,'$status',$shift)",
            )
            val order = number("SELECT max(id) FROM orders")
            val batchStatus = if (status == "CLOSED") "DELIVERED" else "ACCEPTED"
            val fulfilledAt = if (status == "CLOSED") "CURRENT_TIMESTAMP" else "NULL"
            val fulfilledBy = if (status == "CLOSED") "9001001" else "NULL"
            sql(
                "INSERT INTO order_batches (order_id,author_user_id,source,status,created_at,updated_at)" +
                    " VALUES ($order,9001002,'MINIAPP','$batchStatus',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            )
            val batch = number("SELECT max(id) FROM order_batches")
            sql(
                "INSERT INTO order_batch_items" +
                    " (order_batch_id," +
                    "menu_item_id," +
                    "qty," +
                    "base_unit_price_minor_snapshot," +
                    "item_name_snapshot,currency_snapshot,loyalty_" +
                    "semantic_type_snapshot,fulfilled_at,fulfilled" +
                    "_by_user_id)" +
                    " VALUES ($batch,$item,$qty,$price,'Hookah','RUB','HOOKAH',$fulfilledAt,$fulfilledBy)",
            )
            return Order(order, batch, number("SELECT max(id) FROM order_batch_items"))
        }

        suspend fun sale(order: Long): AccountingSale =
            AccountingRepository(
                ds,
            ).state(venue, 9001001).current!!.sales.single {
                it.orderId == order
            }

        suspend fun earn(order: Long): LoyaltyAccrualResult {
            val sale = sale(order)
            if (sale.payable <= 0 || !sale.orderClosed || sale.issue != null) {
                return LoyaltyAccrualResult(
                    order,
                    0,
                    0,
                    0,
                )
            }
            val before = number("SELECT COUNT(*) FROM guest_loyalty_ledger WHERE order_id=$order AND event_type='EARN'")
            val rewards =
                number(
                    "SELECT COALESCE(SUM(delta_rewards),0) FROM guest_loyalty_ledger WHERE " +
                        "order_id=$order",
                )
            AccountingRepository(ds).execute(
                venue,
                9001001,
                AccountingCommand(
                    UUID.nameUUIDFromBytes("paid:$order".toByteArray()).toString(),
                    "PAY",
                    shiftId = shift,
                    saleId = sale.id,
                    expectedVersion = 0,
                    paymentMethod = "CASH",
                    amountMinor = sale.payable,
                ),
            )
            val units =
                (
                    number(
                        "SELECT COUNT(*) FROM guest_loyalty_ledger WHERE order_id=$order AND " +
                            "event_type='EARN'",
                    ) - before
                ).toInt()
            val added =
                (
                    number(
                        "SELECT COALESCE(SUM(delta_rewards),0) FROM guest_loyalty_ledger WHERE " +
                            "order_id=$order",
                    ) - rewards
                ).toInt()
            return LoyaltyAccrualResult(order, units, units, added)
        }

        suspend fun payment(
            o: Order,
            amount: Long,
            id: String = UUID.randomUUID().toString(),
        ) {
            val sale = sale(o.order)
            AccountingRepository(ds).execute(
                venue,
                9001001,
                AccountingCommand(
                    id,
                    "PAY",
                    shiftId = shift,
                    saleId = sale.id,
                    expectedVersion = sale.version,
                    paymentMethod = "CARD",
                    amountMinor = amount,
                ),
            )
        }

        suspend fun voidPayment(o: Order) {
            val sale = sale(o.order)
            AccountingRepository(ds).execute(
                venue,
                9001001,
                AccountingCommand(
                    UUID.randomUUID().toString(),
                    "VOID_PAYMENT",
                    shiftId = shift,
                    saleId = sale.id,
                    expectedVersion = sale.version,
                    paymentId =
                        sale.payments.last {
                            it.voidReason == null
                        }.id,
                    reason = "Исправление тестовой оплаты",
                ),
            )
        }

        suspend fun delivery(
            o: Order,
            confirm: Boolean = true,
            id: String = UUID.randomUUID().toString(),
            actor: Long = 9001001,
            role: VenueRole = VenueRole.OWNER,
        ) {
            val reward =
                number(
                    "SELECT id FROM loyalty_redemptions WHERE " +
                        "order_batch_item_id=${o.line} ORDER BY id DESC LIMIT 1",
                )
            VenueOrdersRepository(ds, loyaltyRepository = repo).correctLoyaltyDelivery(
                venue,
                o.order,
                reward,
                OrderActionActor(actor, role),
                id,
                if (confirm) "" else "Ошибочная отметка о фактической выдаче",
                confirm,
            )
        }

        fun lineOn(
            o: Order,
            guest: Long = 9001002,
        ): Order {
            sql(
                "INSERT INTO order_batches " +
                    "(order_id,author_user_id,source,status,create" +
                    "d_at,updated_at) VALUES " +
                    "(${o.order},$guest,'MINIAPP','ACCEPTED',CURRE" +
                    "NT_TIMESTAMP,CURRENT_TIMESTAMP)",
            )
            val b = number("SELECT max(id) FROM order_batches")
            sql(
                "INSERT INTO order_batch_items " +
                    "(order_batch_id,menu_item_id,qty,base_unit_pr" +
                    "ice_minor_snapshot,item_name_snapshot,currenc" +
                    "y_snapshot,loyalty_semantic_type_snapshot) " +
                    "VALUES " +
                    "($b,$item,1,1000,'Hookah','RUB','HOOKAH')",
            )
            return Order(o.order, b, number("SELECT max(id) FROM order_batch_items"))
        }

        fun redeem(
            o: Order,
            user: Long = 9001002,
        ): LoyaltyRedemptionResult? =
            ds.connection.use { c ->
                c.autoCommit = false
                try {
                    val result =
                        LoyaltyRepository(
                            ds,
                        ).applyRedemptionForBatch(
                            c,
                            o.order,
                            o.batch,
                            venue,
                            user,
                            listOf(
                                LoyaltyCheckoutItem(
                                    o.line,
                                    item,
                                    "Hookah",
                                    number(
                                        "SELECT qty FROM order_batch_items WHERE " +
                                            "id=${o.line}",
                                    ).toInt(),
                                    number(
                                        "SELECT base_unit_price_minor_snapshot FROM order_batch_items WHERE " +
                                            "id=${o.line}",
                                    ) +
                                        number(
                                            "SELECT " +
                                                "COALESCE(SUM(price_delta_minor_snapshot),0) " +
                                                "FROM " +
                                                "order_batch_item_options WHERE " +
                                                "order_batch_item_id=${o.line}",
                                        ),
                                    "RUB",
                                    MenuSemanticType.HOOKAH,
                                ),
                            ),
                        )
                    c.commit()
                    result
                } catch (e: Exception) {
                    c.rollback()
                    throw e
                }
            }
    }
}

object LoyaltyCorrectionRestartProbe {
    @JvmStatic
    fun main(args: Array<String>) =
        runBlocking {
            check(System.getenv("HT_LOYALTY_TEST_PROBE") == "1")
            val jdbc = System.getenv("HT_LOYALTY_TEST_JDBC")
            val uri = java.net.URI(jdbc.removePrefix("jdbc:"))
            check(
                uri.host in setOf("localhost", "127.0.0.1") && uri.path == "/hookah_test" &&
                    Regex("(?:^|&)currentSchema=test_[a-f0-9]{32}(?:&|$)").containsMatchIn(uri.query.orEmpty()),
            )
            val config =
                com.zaxxer.hikari.HikariConfig().apply {
                    jdbcUrl = jdbc
                    username = System.getenv("HT_LOYALTY_TEST_USER")
                    password = System.getenv("HT_LOYALTY_TEST_PASSWORD")
                    maximumPoolSize = 2
                }
            val replayed =
                com.zaxxer.hikari.HikariDataSource(config).use { ds ->
                    VenueOrdersRepository(
                        ds,
                        loyaltyRepository = LoyaltyRepository(ds),
                    ).correctLoyaltyUnit(
                        args[0].toLong(),
                        args[1].toLong(),
                        args[2].toLong(),
                        OrderActionActor(9001001, VenueRole.OWNER),
                        args[3],
                        "Тест перезапуска: неверное начисление",
                    )
                }
            println("LOYALTY_UNIT_REPLAY=$replayed")
        }
}
