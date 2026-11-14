package com.hookah.platform.backend.miniapp.venue.accounting

import com.hookah.platform.backend.analytics.KpiRepository
import com.hookah.platform.backend.analytics.KpiRequest
import com.hookah.platform.backend.api.AccountingConflictException
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.test.PostgresTestEnv
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.flywaydb.core.Flyway
import java.sql.SQLException
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountingChatReconciliationH2Test : AccountingChatReconciliationContract() {
    override fun database() =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:chat_${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
                username = "sa"
                password = ""
            },
        ).also { Flyway.configure().dataSource(it).locations("classpath:db/migration/h2").load().migrate() }
}

class AccountingChatReconciliationPostgresTest : AccountingChatReconciliationContract() {
    override fun database() = PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase())

    @Test fun `concurrent text reconciliation preserves CAS and one existing digital identity`() =
        runBlocking {
            database().use { ds ->
                val initial = chatFixture(ds)
                val sale = initial.sales.single()
                val one = textCommand(initial, sale, 75000)
                val two = textCommand(initial, sale, 90000)
                SqlBarrier(ds, "UPDATE venue_accounting_sales", after = true).use { first ->
                    SqlBarrier(ds, "SELECT id FROM venues", hold = false).use { second ->
                        val a = async { AccountingRepository(first).execute(1, 101, one) }
                        val holder = first.awaitEntered()
                        val b = async { runCatching { AccountingRepository(second).execute(1, 102, two) } }
                        val waiter = second.awaitEntered()
                        withTimeout(10000) {
                            while (ds.connection.use {
                                        c ->
                                    c.createStatement().use {
                                            s ->
                                        s.executeQuery("SELECT $holder = ANY(pg_blocking_pids($waiter))").use {
                                            it.next()
                                            it.getBoolean(1)
                                        }
                                    }
                                } == false
                                ) delay(20)
                        }
                        first.release()
                        assertEquals(75000, a.await().sales.single().payable)
                        assertTrue(b.await().exceptionOrNull() is AccountingConflictException)
                    }
                }
                ds.connection.use {
                        c ->
                    assertEquals(1, c.count("venue_accounting_sales"))
                    assertEquals(1, c.count("venue_accounting_commands", "request_id='${one.requestId}'"))
                    assertEquals(1, c.count("audit_log", "action='VENUE_ACCOUNTING_RECONCILE_TEXT'"))
                }
            }
        }
}

abstract class AccountingChatReconciliationContract {
    protected abstract fun database(): HikariDataSource

    @Test fun `completed text reconciliation confirms only unallocated net and permits acknowledged frozen close`() =
        runBlocking {
            database().use { ds ->
                val initial = chatFixture(ds)
                val old = initial.sales.single()
                val repo = AccountingRepository(ds)
                assertNotNull(old.issue)
                assertTrue(old.textReconciliationAvailable)
                assertFailsWith<AccountingConflictException> {
                    repo.execute(1, 101, command("EDIT", initial.shiftId, old).copy(reason = "No bypass"))
                }
                assertFailsWith<AccountingConflictException> {
                    repo.execute(
                        1,
                        101,
                        command(
                            "CLOSE",
                            initial.shiftId,
                        ).copy(expectedReportHash = initial.reportHash, acknowledgeUnpaid = true),
                    )
                }
                val cmd = textCommand(initial, old, 75000)
                val result = repo.execute(1, 102, cmd)
                val sale = result.sales.single()
                assertEquals(old.id, sale.id)
                assertTrue(sale.lines.isEmpty())
                assertNull(sale.issue)
                assertNull(sale.sourceWarning)
                assertEquals(75000, result.totals.accruedMinor)
                assertEquals(75000, result.totals.unallocatedAccruedMinor)
                assertTrue(result.totals.financialDetailIncomplete)
                assertEquals(0, result.totals.discountMinor)
                assertEquals(0, result.totals.giftMinor)
                assertEquals(0, sale.paid)
                assertEquals(102, sale.textReconciliation!!.actorUserId)
                assertEquals(old.issue, sale.textReconciliation.originalIssue)
                assertEquals(result, AccountingRepository(ds).execute(1, 102, cmd))
                assertFailsWith<AccountingConflictException> { repo.execute(1, 102, cmd.copy(amountMinor = 75001)) }
                assertFailsWith<AccountingConflictException> {
                    repo.execute(1, 102, cmd.copy(requestId = UUID.randomUUID().toString()))
                }
                assertFailsWith<AccountingConflictException> {
                    repo.execute(1, 101, command("RESOLVE", result.shiftId, sale).copy(reason = "Cannot invent detail"))
                }
                val paid =
                    repo.execute(
                        1,
                        103,
                        command("PAY", result.shiftId, sale).copy(paymentMethod = "CARD", amountMinor = 75000),
                    )
                assertEquals(75000, paid.totals.paidMinor)
                assertFailsWith<AccountingConflictException> {
                    repo.execute(
                        1,
                        101,
                        textCommand(paid, paid.sales.single(), 74000),
                    )
                }
                assertFailsWith<AccountingConflictException> {
                    repo.execute(
                        1,
                        101,
                        command(
                            "CLOSE",
                            result.shiftId,
                        ).copy(expectedReportHash = result.reportHash, acknowledgeUnpaid = true),
                    )
                }
                assertFailsWith<AccountingConflictException> {
                    repo.execute(1, 101, command("CLOSE", result.shiftId).copy(expectedReportHash = paid.reportHash))
                }
                val closed =
                    repo.execute(
                        1,
                        101,
                        command(
                            "CLOSE",
                            result.shiftId,
                        ).copy(expectedReportHash = paid.reportHash, acknowledgeUnpaid = true),
                    )
                assertEquals("CLOSED", closed.status)
                assertEquals(sale.textReconciliation, closed.sales.single().textReconciliation)
                assertEquals(closed, AccountingRepository(ds).detail(1, 101, result.shiftId))
                assertEquals(closed, AccountingRepository(ds).execute(1, 102, cmd))
                assertFailsWith<AccountingConflictException> {
                    repo.execute(
                        1,
                        101,
                        textCommand(closed, closed.sales.single(), 90000),
                    )
                }
                val today = LocalDate.now(ZoneId.of(initial.timezone))
                val finance =
                    KpiRepository(
                        ds,
                    ).venue(
                        1,
                        101,
                        KpiRequest(today.toString(), today.plusDays(1).toString(), section = "finance"),
                    ).closedShiftFinances.single().metrics
                assertEquals("75000", finance.getValue("accrued").value)
                assertEquals("75000", finance.getValue("operatorConfirmed").value)
                assertEquals("75000", finance.getValue("unallocatedAccrued").value)
                for (key in listOf("discounts", "gifts")) {
                    assertEquals("PARTIAL", finance.getValue(key).availability)
                    assertNull(finance.getValue(key).value)
                }
                ds.connection.use { c ->
                    assertEquals(1, c.count("venue_accounting_sales"))
                    assertEquals(0, c.count("venue_accounting_sales", "id LIKE 'manual:%'"))
                    assertEquals(1, c.count("orders"))
                    assertEquals(1, c.count("order_batches"))
                    assertEquals(0, c.count("order_batch_items"))
                    assertEquals(0, c.count("telegram_outbox"))
                    assertEquals(1, c.count("audit_log", "action='VENUE_ACCOUNTING_RECONCILE_TEXT'"))
                    c.createStatement().use {
                            s ->
                        s.executeQuery(
                            "SELECT old_json,new_json,actor_user_id FROM venue_accounting_events " +
                                "WHERE action='RECONCILE_TEXT'",
                        ).use {
                                q ->
                            q.next()
                            assertTrue(q.getString(1).contains("Текстовый заказ"))
                            assertTrue(q.getString(2).contains("Кассовый документ"))
                            assertEquals(102, q.getLong(3))
                        }
                    }
                }
            }
        }

    @Test fun `unknown zero unverified currency corrupt snapshots active and foreign sources cannot reconcile`() =
        runBlocking {
            database().use { ds ->
                val initial = chatFixture(ds)
                val cmd = textCommand(initial, initial.sales.single(), 75000)
                val repo = AccountingRepository(ds)
                for (bad in listOf(
                    cmd.copy(amountMinor = null),
                    cmd.copy(amountMinor = 0),
                    cmd.copy(amountMinor = -1),
                    cmd.copy(amountMinor = 10000000000001),
                    cmd.copy(confirmationSource = null),
                    cmd.copy(confirmationSource = " "),
                    cmd.copy(reason = ""),
                    cmd.copy(confirmationSource = "x".repeat(501)),
                )) {
                    assertFailsWith<InvalidInputException> { repo.execute(1, 101, bad) }
                }
                assertFailsWith<ForbiddenException> { repo.execute(1, 103, cmd) }
                assertFailsWith<ForbiddenException> { repo.execute(1, 201, cmd) }
                ds.connection.use { it.exec("UPDATE orders SET status='ACTIVE' WHERE id=31") }
                assertFailsWith<AccountingConflictException> { repo.execute(1, 101, cmd) }
                ds.connection.use { c ->
                    c.exec("UPDATE orders SET status='CLOSED' WHERE id=31")
                    c.exec(
                        "INSERT INTO order_batch_items(id,order_batch_id,menu_item_id,qty,item_name_snapshot," +
                            "base_unit_price_minor_snapshot,currency_snapshot) OVERRIDING SYSTEM VALUE " +
                            "VALUES (61,51,21,1,'Bad',100,'EUR')",
                    )
                }
                assertFailsWith<AccountingConflictException> { repo.execute(1, 101, cmd) }
                ds.connection.use {
                    it.exec(
                        "UPDATE order_batch_items SET currency_snapshot='RUB'," +
                            "base_unit_price_minor_snapshot=NULL WHERE id=61",
                    )
                }
                assertFailsWith<AccountingConflictException> { repo.execute(1, 101, cmd) }
                ds.connection.use {
                    it.exec(
                        "UPDATE order_batch_items SET base_unit_price_minor_snapshot=100 WHERE id=61",
                    )
                }
                // A structured CHAT line without an unpriced text batch is not this reconciliation type.
                assertFailsWith<AccountingConflictException> { repo.execute(1, 101, cmd) }
                ds.connection.use {
                        c ->
                    assertEquals(0, c.count("venue_accounting_sales"))
                    assertEquals(0, c.count("audit_log", "action='VENUE_ACCOUNTING_RECONCILE_TEXT'"))
                    assertEquals(1, c.count("venue_accounting_commands"))
                }
            }
        }

    @Test fun `reconciliation audit failure rolls back snapshot command history and money atomically`() =
        runBlocking {
            database().use { ds ->
                val initial = chatFixture(ds)
                val cmd = textCommand(initial, initial.sales.single(), 75000)
                val broken =
                    AccountingRepository(ds, audit = {
                            _,
                            _,
                            action,
                            _,
                            _,
                            _,
                        ->
                        if (action == "VENUE_ACCOUNTING_RECONCILE_TEXT") {
                            throw SQLException(
                                "Synthetic after-write failure",
                            )
                        }
                    })
                assertFailsWith<DatabaseUnavailableException> { broken.execute(1, 101, cmd) }
                assertEquals(initial, AccountingRepository(ds).detail(1, 101, initial.shiftId))
                ds.connection.use {
                        c ->
                    assertEquals(0, c.count("venue_accounting_sales"))
                    assertEquals(0, c.count("venue_accounting_events", "action='RECONCILE_TEXT'"))
                    assertEquals(0, c.count("venue_accounting_commands", "request_id='${cmd.requestId}'"))
                }
                assertEquals(75000, AccountingRepository(ds).execute(1, 101, cmd).totals.accruedMinor)
            }
        }

    @Test fun `mixed text confirmed total is never allocated to guessed goods discounts or gifts`() =
        runBlocking {
            database().use { ds ->
                val initial = chatFixture(ds)
                ds.connection.use { c ->
                    c.exec("UPDATE order_batches SET source='MINIAPP' WHERE id=51")
                    c.exec(
                        "INSERT INTO order_batch_items(id,order_batch_id,menu_item_id,qty,item_name_snapshot," +
                            "base_unit_price_minor_snapshot,currency_snapshot) OVERRIDING SYSTEM VALUE " +
                            "VALUES (61,51,21,1,'Tea',10000,'RUB')",
                    )
                    c.exec(
                        "INSERT INTO order_batches(id,order_id,source,status) OVERRIDING SYSTEM VALUE " +
                            "VALUES (52,31,'CHAT','DELIVERED')",
                    )
                }
                val repo = AccountingRepository(ds)
                val mixed = repo.detail(1, 101, initial.shiftId)
                assertTrue(mixed.sales.single().textReconciliationAvailable)
                val result = repo.execute(1, 101, textCommand(mixed, mixed.sales.single(), 3000))
                assertEquals(3000, result.totals.accruedMinor)
                assertEquals(3000, result.totals.unallocatedAccruedMinor)
                assertEquals(10000, result.totals.grossMinor)
                assertEquals(0, result.totals.discountMinor)
                assertEquals(0, result.totals.giftMinor)
                assertTrue(result.byItem.isEmpty())
                assertEquals(3000, result.bySource.sumOf { it.accruedMinor })
                assertEquals(0, result.bySource.sumOf { it.qty })
                assertEquals(1, result.sales.single().lines.single().qty)
                val corrected = repo.execute(1, 102, textCommand(result, result.sales.single(), 5000))
                assertEquals(5000, corrected.totals.accruedMinor)
                assertEquals(1, corrected.sales.size)
                assertEquals(2, ds.connection.use { it.count("audit_log", "action='VENUE_ACCOUNTING_RECONCILE_TEXT'") })
            }
        }
}

internal suspend fun chatFixture(ds: HikariDataSource): AccountingReport {
    seedAccounting(ds)
    seedDigitalOrder(ds, 31, closed = true)
    ds.connection.use {
            c ->
        c.exec("DELETE FROM order_batch_items WHERE id=61")
        c.exec("UPDATE order_batches SET source='CHAT' WHERE id=51")
    }
    val opened = AccountingRepository(ds).execute(1, 101, command("OPEN"))
    ds.connection.use { it.exec("UPDATE orders SET accounting_shift_id=${opened.shiftId} WHERE id=31") }
    return AccountingRepository(ds).detail(1, 101, opened.shiftId)
}

internal fun textCommand(
    report: AccountingReport,
    sale: AccountingSale,
    amount: Long,
) = command(
    "RECONCILE_TEXT",
    report.shiftId,
    sale,
).copy(
    amountMinor = amount,
    confirmationSource = "Кассовый документ №17",
    reason = "Подтверждён итог выполненного текстового заказа",
)
