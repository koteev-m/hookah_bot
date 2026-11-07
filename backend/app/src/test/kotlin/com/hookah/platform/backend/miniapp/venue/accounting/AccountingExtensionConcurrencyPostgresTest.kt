package com.hookah.platform.backend.miniapp.venue.accounting

import com.hookah.platform.backend.api.AccountingConflictException
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.miniapp.guest.db.VisitRepository
import com.hookah.platform.backend.miniapp.shift.ShiftExtensionRepository
import com.hookah.platform.backend.miniapp.shift.ShiftExtensionRequestStatus
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.orders.OrderActionActor
import com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.miniapp.venue.orders.toOrderBillSnapshot
import com.hookah.platform.backend.test.PostgresTestEnv
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccountingExtensionConcurrencyPostgresTest {
    @Test
    fun `extension wins and order closure includes its charge without FK deadlock`() =
        runBlocking {
            fixture().use { f ->
                SqlBarrier(f.ds, "INSERT INTO order_service_charges").use { extension ->
                    SqlBarrier(f.ds, "SELECT status, updated_at FROM orders", hold = false).use { closing ->
                        val approve = async { ShiftExtensionRepository(extension).approveRequest(1, 81, 102) }
                        val extensionPid = extension.awaitEntered()
                        val close = async { f.closeOrder(closing) }
                        awaitBlocked(f.ds, extensionPid, closing.awaitEntered())
                        extension.release()
                        assertTrue(approve.await().applied)
                        close.await()
                    }
                }
                assertEquals(1, f.count("visits"))
                f.assertAmounts(15000, 0)
                f.pay(10000)
                f.assertAmounts(15000, 10000)
                f.pay(5000)
                f.assertFrozen(15000, 15000)
                assertEquals(1, f.count("order_service_charges"))
            }
        }

    @Test
    fun `order closure wins and waiting extension rechecks CLOSED with no partial write`() =
        runBlocking {
            fixture().use { f ->
                val before = f.extensionState()
                SqlBarrier(f.ds, "UPDATE orders SET status = 'CLOSED'", after = true).use { closing ->
                    SqlBarrier(f.ds, "SELECT status FROM orders", hold = false).use { extension ->
                        val close = async { f.closeOrder(closing) }
                        val closePid = closing.awaitEntered()
                        val approve =
                            async { runCatching { ShiftExtensionRepository(extension).approveRequest(1, 81, 102) } }
                        awaitBlocked(f.ds, closePid, extension.awaitEntered())
                        closing.release()
                        close.await()
                        val failure = approve.await().exceptionOrNull()
                        assertTrue(failure is InvalidInputException)
                        assertTrue(failure.message.orEmpty().contains("Продление не добавлено"))
                    }
                }
                assertEquals(before, f.extensionState())
                assertEquals(1, f.count("visits"))
                f.pay(10000)
                f.assertFrozen(10000, 10000)
            }
        }

    @Test
    fun `shift close wins over waiting extension and rejected approval has no successful audit`() =
        runBlocking {
            fixture().use { f ->
                f.closeOrder()
                f.pay(10000)
                val preview = f.report()
                val before = f.extensionState()
                val auditBefore = f.count("audit_log")
                SqlBarrier(f.ds, "UPDATE venue_accounting_shifts SET status = 'CLOSED'").use { closing ->
                    SqlBarrier(f.ds, "SELECT status FROM orders", hold = false).use { extension ->
                        val close =
                            async {
                                AccountingRepository(closing).execute(
                                    1,
                                    101,
                                    command("CLOSE", f.shift).copy(
                                        expectedReportHash = preview.reportHash,
                                    ),
                                )
                            }
                        val closePid = closing.awaitEntered()
                        val approve =
                            async { runCatching { ShiftExtensionRepository(extension).approveRequest(1, 81, 102) } }
                        awaitBlocked(f.ds, closePid, extension.awaitEntered())
                        closing.release()
                        val frozen = close.await()
                        assertTrue(approve.await().exceptionOrNull() is InvalidInputException)
                        assertEquals(frozen, f.report())
                        assertEquals(10000, frozen.totals.accruedMinor)
                        assertEquals(0, frozen.totals.unpaidMinor)
                    }
                }
                assertEquals(before, f.extensionState())
                assertEquals(auditBefore + 1, f.count("audit_log"))
                val after = f.allState()
                assertTrue(
                    runCatching { f.extensions.approveRequest(1, 81, 102) }.exceptionOrNull() is InvalidInputException,
                )
                assertEquals(after, f.allState())
                f.assertAmounts(10000, 10000)
            }
        }

    @Test
    fun `payment waiting for extension refuses active source then settles the complete bill`() =
        runBlocking {
            fixture().use { f ->
                val before = f.report()
                val auditBefore = f.count("audit_log")
                SqlBarrier(f.ds, "INSERT INTO order_service_charges").use { extension ->
                    SqlBarrier(f.ds, "SELECT id, status FROM orders WHERE accounting_shift_id", hold = false).use {
                            payment ->
                        val approve = async { ShiftExtensionRepository(extension).approveRequest(1, 81, 102) }
                        val extensionPid = extension.awaitEntered()
                        val pay =
                            async {
                                runCatching {
                                    AccountingRepository(payment).execute(
                                        1,
                                        103,
                                        command(
                                            "PAY",
                                            f.shift,
                                            before.sales.single(),
                                        ).copy(paymentMethod = "CASH", amountMinor = 10000),
                                    )
                                }
                            }
                        awaitBlocked(f.ds, extensionPid, payment.awaitEntered())
                        extension.release()
                        assertTrue(approve.await().applied)
                        assertTrue(pay.await().exceptionOrNull() is AccountingConflictException)
                    }
                }
                assertEquals(auditBefore, f.count("audit_log"))
                assertEquals(0, f.count("venue_accounting_sales"))
                assertEquals(1, f.count("venue_accounting_commands"))
                f.assertAmounts(15000, 0)
                f.closeOrder()
                f.pay(15000)
                f.assertFrozen(15000, 15000)
            }
        }

    @Test
    fun `payment snapshot wins and pending extension cannot change the settled source`() =
        runBlocking {
            fixture().use { f ->
                f.closeOrder()
                val before = f.extensionState()
                val sale = f.report().sales.single()
                SqlBarrier(f.ds, "INSERT INTO venue_accounting_sales", after = true).use { payment ->
                    SqlBarrier(f.ds, "SELECT status FROM orders", hold = false).use { extension ->
                        val pay =
                            async {
                                AccountingRepository(payment).execute(
                                    1,
                                    103,
                                    command("PAY", f.shift, sale)
                                        .copy(paymentMethod = "CASH", amountMinor = 10000),
                                )
                            }
                        val paymentPid = payment.awaitEntered()
                        val approve =
                            async { runCatching { ShiftExtensionRepository(extension).approveRequest(1, 81, 102) } }
                        awaitBlocked(f.ds, paymentPid, extension.awaitEntered())
                        payment.release()
                        assertEquals(10000, pay.await().totals.paidMinor)
                        assertTrue(approve.await().exceptionOrNull() is InvalidInputException)
                    }
                }
                assertEquals(before, f.extensionState())
                assertEquals(2, f.count("venue_accounting_events"))
                assertEquals(2, f.count("audit_log"))
                f.assertFrozen(10000, 10000)
            }
        }

    @Test
    fun `concurrent duplicate approval and replay after close add exactly one charge`() =
        runBlocking {
            fixture().use { f ->
                SqlBarrier(f.ds, "INSERT INTO order_service_charges").use { first ->
                    SqlBarrier(f.ds, "SELECT id FROM table_sessions", hold = false).use { second ->
                        val a = async { ShiftExtensionRepository(first).approveRequest(1, 81, 102) }
                        val firstPid = first.awaitEntered()
                        val b = async { ShiftExtensionRepository(second).approveRequest(1, 81, 102) }
                        awaitBlocked(f.ds, firstPid, second.awaitEntered())
                        first.release()
                        assertTrue(a.await().applied)
                        assertFalse(b.await().applied)
                    }
                }
                assertEquals(1, f.count("order_service_charges"))
                f.closeOrder()
                f.pay(15000)
                f.assertFrozen(15000, 15000)
                val before = f.allState()
                val replay = f.extensions.approveRequest(1, 81, 102)
                assertFalse(replay.applied)
                assertEquals(ShiftExtensionRequestStatus.APPROVED, replay.request.status)
                assertEquals(before, f.allState())
            }
        }

    @Test
    fun `failed extension rolls back charge session decision and payment audit`() =
        runBlocking {
            fixture().use { f ->
                val before = f.allState()
                val sale = f.report().sales.single()
                SqlBarrier(f.ds, "UPDATE orders SET updated_at", after = true, fail = true).use { extension ->
                    SqlBarrier(f.ds, "SELECT id, status FROM orders WHERE accounting_shift_id", hold = false).use {
                            payment ->
                        val approve =
                            async { runCatching { ShiftExtensionRepository(extension).approveRequest(1, 81, 102) } }
                        val extensionPid = extension.awaitEntered()
                        val pay =
                            async {
                                runCatching {
                                    AccountingRepository(payment).execute(
                                        1,
                                        103,
                                        command("PAY", f.shift, sale).copy(paymentMethod = "CASH", amountMinor = 10000),
                                    )
                                }
                            }
                        awaitBlocked(f.ds, extensionPid, payment.awaitEntered())
                        extension.release()
                        assertTrue(approve.await().exceptionOrNull() is DatabaseUnavailableException)
                        assertTrue(pay.await().exceptionOrNull() is AccountingConflictException)
                    }
                }
                assertEquals(before, f.allState())
                assertTrue(f.extensions.approveRequest(1, 81, 102).applied)
                f.closeOrder()
                f.pay(15000)
                f.assertFrozen(15000, 15000)
            }
        }

    private suspend fun fixture(): Fixture {
        val ds = PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase())
        ds.maximumPoolSize = 8
        seedAccounting(ds)
        seedDigitalOrder(ds, 31, closed = false)
        ds.connection.use { c ->
            c.exec(
                "INSERT INTO tab (id, venue_id, table_session_id, type, owner_user_id) " +
                    "VALUES (71, 1, 41, 'PERSONAL', 301)",
            )
            c.exec("INSERT INTO tab_member (tab_id, user_id, role) VALUES (71, 301, 'OWNER')")
            c.exec("UPDATE order_batches SET tab_id = 71, author_user_id = 301 WHERE id = 51")
            c.exec(
                "INSERT INTO shift_extension_requests (id, venue_id, table_session_id, table_id, tab_id, " +
                    "order_id, requested_by_user_id, duration_minutes, price_minor, " +
                    "current_orderable_until, requested_until) " +
                    "OVERRIDING SYSTEM VALUE VALUES (81, 1, 41, 11, 71, 31, 301, 60, 5000, " +
                    "CURRENT_TIMESTAMP + INTERVAL '1' DAY, CURRENT_TIMESTAMP + INTERVAL '25' HOUR)",
            )
        }
        val shift = AccountingRepository(ds).execute(1, 101, command("OPEN")).shiftId
        return Fixture(ds, shift)
    }

    private class Fixture(val ds: HikariDataSource, val shift: Long) : AutoCloseable {
        val accounting = AccountingRepository(ds)
        val extensions = ShiftExtensionRepository(ds)

        suspend fun report() = accounting.detail(1, 101, shift)

        suspend fun closeOrder(source: DataSource = ds) {
            val result =
                VenueOrdersRepository(source, visitRepository = VisitRepository(source)).updateOrderStatus(
                    1,
                    31,
                    OrderWorkflowStatus.CLOSED,
                    OrderActionActor(102, VenueRole.MANAGER),
                )
            assertTrue(result?.applied == true)
        }

        suspend fun pay(amount: Long) =
            accounting.execute(
                1,
                103,
                command("PAY", shift, report().sales.single())
                    .copy(paymentMethod = "CASH", amountMinor = amount),
            )

        suspend fun assertAmounts(
            accrued: Long,
            paid: Long,
        ) {
            val canonical =
                VenueOrdersRepository(
                    ds,
                ).loadOrderDetail(1, 31)!!.toOrderBillSnapshot().finalPayableTotalMinor
            val totals = report().totals
            assertEquals(accrued, canonical)
            assertEquals(canonical, totals.accruedMinor)
            assertEquals(paid, totals.paidMinor)
            assertEquals(accrued - paid, totals.unpaidMinor)
            assertEquals(totals.paidMinor, totals.cashMinor + totals.cardMinor + totals.transferMinor)
        }

        suspend fun assertFrozen(
            accrued: Long,
            paid: Long,
        ) {
            val preview = report()
            val frozen =
                accounting.execute(
                    1,
                    101,
                    command("CLOSE", shift).copy(
                        expectedReportHash = preview.reportHash,
                        acknowledgeUnpaid = true,
                    ),
                )
            assertEquals("CLOSED", frozen.status)
            assertEquals(preview.totals, frozen.totals)
            assertEquals(frozen, report())
            assertAmounts(accrued, paid)
        }

        fun count(table: String) = ds.connection.use { it.count(table) }

        fun extensionState() =
            ds.connection.use { c ->
                listOf(
                    rows(c, "SELECT * FROM order_service_charges ORDER BY id"),
                    rows(
                        c,
                        "SELECT status, decided_by_user_id, decided_at, reject_reason, updated_at " +
                            "FROM shift_extension_requests WHERE id = 81",
                    ),
                    rows(c, "SELECT last_activity_at, expires_at, status, ended_at FROM table_sessions WHERE id = 41"),
                )
            }

        fun allState() =
            extensionState() +
                ds.connection.use { c ->
                    listOf(
                        rows(c, "SELECT status, updated_at FROM orders WHERE id = 31"),
                        rows(c, "SELECT * FROM audit_log ORDER BY id"),
                        rows(c, "SELECT * FROM venue_accounting_events ORDER BY id"),
                        rows(c, "SELECT * FROM venue_accounting_commands ORDER BY request_id"),
                        rows(c, "SELECT * FROM venue_accounting_sales ORDER BY id"),
                    )
                }

        override fun close() = ds.close()
    }

    companion object {
        private fun rows(
            c: Connection,
            sql: String,
        ): List<List<String?>> =
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    buildList {
                        while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) })
                    }
                }
            }

        private suspend fun awaitBlocked(
            ds: DataSource,
            holder: Int,
            waiter: Int,
        ) = withTimeout(10_000) {
            assertNotEquals(holder, waiter)
            ds.connection.use { c ->
                c.prepareStatement("SELECT ? = ANY(pg_blocking_pids(?))").use { s ->
                    s.setInt(1, holder)
                    s.setInt(2, waiter)
                    while (!s.executeQuery().use {
                            it.next()
                            it.getBoolean(1)
                        }
                        ) delay(10)
                }
            }
        }
    }
}

/** Stops a real JDBC transaction at a known statement; pg_blocking_pids proves the other connection is waiting. */
private class SqlBarrier(
    private val delegate: DataSource,
    private val prefix: String,
    private val after: Boolean = false,
    hold: Boolean = true,
    private val fail: Boolean = false,
) : DataSource by delegate, AutoCloseable {
    val entered = CompletableDeferred<Int>()
    private val once = AtomicBoolean()
    private val latch = CountDownLatch(if (hold) 1 else 0)

    override fun getConnection(): Connection {
        val connection = delegate.connection
        connection.createStatement().use {
            it.execute("SET lock_timeout = '12s'")
            it.execute("SET statement_timeout = '15s'")
        }
        val pid =
            connection.createStatement().use {
                it.executeQuery("SELECT pg_backend_pid()").use {
                        r ->
                    r.next()
                    r.getInt(1)
                }
            }
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            val result = invoke(connection, method, args)
            val sql = (args?.firstOrNull() as? String)?.replace(Regex("\\s+"), " ")?.trim()
            if (method.name == "prepareStatement" &&
                sql?.startsWith(prefix) == true &&
                once.compareAndSet(false, true)
            ) {
                Proxy.newProxyInstance(
                    PreparedStatement::class.java.classLoader,
                    arrayOf(PreparedStatement::class.java),
                ) {
                        _, operation, parameters ->
                    val execute = operation.name in setOf("executeQuery", "executeUpdate", "execute")
                    if (execute && !after) stop(pid)
                    val value = invoke(checkNotNull(result), operation, parameters)
                    if (execute && after) stop(pid)
                    value
                }
            } else {
                result
            }
        } as Connection
    }

    suspend fun awaitEntered(): Int = withTimeout(10_000) { entered.await() }

    private fun stop(pid: Int) {
        entered.complete(pid)
        check(latch.await(15, TimeUnit.SECONDS)) { "Transaction barrier timed out" }
        if (fail) throw SQLException("Synthetic extension failure before commit")
    }

    fun release() = latch.countDown()

    override fun close() = release()

    private fun invoke(
        target: Any,
        method: Method,
        args: Array<out Any?>?,
    ): Any? =
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
}
