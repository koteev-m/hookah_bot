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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccountingSplitPaymentH2Test : AccountingSplitPaymentContract() {
    override fun database() =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:split_${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
                username = "sa"
                password = ""
            },
        ).also { Flyway.configure().dataSource(it).locations("classpath:db/migration/h2").load().migrate() }
}

class AccountingSplitPaymentPostgresTest : AccountingSplitPaymentContract() {
    override fun database() = PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase())

    @Test fun `split versus PAY EDIT CLOSE and replay serialize with actual PostgreSQL blocking`() =
        runBlocking {
            for (other in listOf("PAY", "EDIT", "CLOSE", "REPLAY")) for (splitFirst in listOf(true, false)) {
                database().use { ds ->
                    seedAccounting(ds)
                    val repo = AccountingRepository(ds)
                    val initial = repo.execute(1, 101, splitManual())
                    val sale = initial.sales.single()
                    val split = splitCommand(initial, sale)
                    val competing =
                        when (other) {
                            "PAY" ->
                                command(
                                    "PAY",
                                    initial.shiftId,
                                    sale,
                                ).copy(paymentMethod = "TRANSFER", amountMinor = 99000)
                            "EDIT" ->
                                command(
                                    "EDIT",
                                    initial.shiftId,
                                    sale,
                                ).copy(
                                    reason = "Correction",
                                    lines =
                                        listOf(
                                            AccountingLineInput(
                                                id = sale.lines.single().id,
                                                name = "Tea",
                                                unitPriceMinor = 90000,
                                            ),
                                        ),
                                )
                            "CLOSE" ->
                                command(
                                    "CLOSE",
                                    initial.shiftId,
                                ).copy(expectedReportHash = initial.reportHash, acknowledgeUnpaid = true)
                            else -> split
                        }
                    val firstCommand = if (splitFirst) split else competing
                    val secondCommand = if (splitFirst) competing else split
                    SqlBarrier(ds, "INSERT INTO venue_accounting_events", after = true).use { first ->
                        SqlBarrier(ds, "SELECT id FROM venues", hold = false).use { second ->
                            val a = async { AccountingRepository(first).execute(1, 101, firstCommand) }
                            val holder = first.awaitEntered()
                            val b =
                                async { runCatching { AccountingRepository(second).execute(1, 101, secondCommand) } }
                            val waiter = second.awaitEntered()
                            assertNotEquals(holder, waiter)
                            withTimeout(10000) {
                                while (!ds.connection.use { c ->
                                        c.createStatement().use { s ->
                                            s.executeQuery("SELECT $holder = ANY(pg_blocking_pids($waiter))").use {
                                                it.next()
                                                it.getBoolean(1)
                                            }
                                        }
                                    }
                                    ) delay(20)
                            }
                            first.release()
                            a.await()
                            if (other == "REPLAY") {
                                assertTrue(b.await().isSuccess)
                            } else {
                                assertTrue(b.await().exceptionOrNull() is AccountingConflictException)
                            }
                        }
                    }
                    val final = repo.detail(1, 101, initial.shiftId)
                    assertEquals(if (other == "CLOSE" && !splitFirst) "CLOSED" else "OPEN", final.status)
                    val expectedPaid = if (splitFirst || other == "REPLAY" || other == "PAY") 99000L else 0L
                    assertEquals(expectedPaid, final.totals.paidMinor)
                    assertEquals(1, final.totals.salesCount)
                    assertEquals(
                        if (splitFirst || other == "REPLAY") {
                            2
                        } else if (other == "PAY") {
                            1
                        } else {
                            0
                        },
                        final.sales.single().payments.size,
                    )
                }
            }
        }

    @Test fun `failure after writing both split components restores snapshot event command and audit`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                val initial = repo.execute(1, 101, splitManual())
                val cmd = splitCommand(initial, initial.sales.single())
                SqlBarrier(ds, "UPDATE venue_accounting_sales", after = true, hold = false, fail = true).use { broken ->
                    assertFailsWith<DatabaseUnavailableException> { AccountingRepository(broken).execute(1, 101, cmd) }
                }
                assertEquals(initial.sales.single().id, initial.createdSaleId)
                assertEquals(initial.copy(createdSaleId = null), repo.detail(1, 101, initial.shiftId))
                assertEquals(99000, repo.execute(1, 101, cmd).totals.paidMinor)
            }
        }
}

abstract class AccountingSplitPaymentContract {
    protected abstract fun database(): HikariDataSource

    @Test fun `atomic split pays 990 once stable component identities durable replay and single correction`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                val initial = repo.execute(1, 103, splitManual())
                val cmd = splitCommand(initial, initial.sales.single())
                val paid = repo.execute(1, 103, cmd)
                assertEquals(1, paid.totals.salesCount)
                assertEquals(99000, paid.totals.paidMinor)
                assertEquals(0, paid.totals.unpaidMinor)
                assertEquals(40000, paid.totals.cashMinor)
                assertEquals(59000, paid.totals.cardMinor)
                assertEquals(initial.sales.single().version + 1, paid.sales.single().version)
                assertEquals(
                    listOf("${cmd.requestId}:CASH", "${cmd.requestId}:CARD"),
                    paid.sales.single().payments.map { it.id },
                )
                assertEquals(1, paid.sales.single().payments.map { it.recordedAt }.distinct().size)
                assertEquals(paid, AccountingRepository(ds).execute(1, 103, cmd))
                assertFailsWith<AccountingConflictException> { repo.execute(1, 102, cmd) }
                assertFailsWith<AccountingConflictException> {
                    repo.execute(1, 103, cmd.copy(paymentComponents = cmd.paymentComponents.reversed()))
                }
                val corrected =
                    repo.execute(
                        1,
                        103,
                        command(
                            "VOID_PAYMENT",
                            paid.shiftId,
                            paid.sales.single(),
                        ).copy(paymentId = "${cmd.requestId}:CASH", reason = "Wrong method"),
                    )
                assertEquals(59000, corrected.totals.paidMinor)
                assertEquals(40000, corrected.totals.unpaidMinor)
                assertEquals(59000, corrected.totals.cardMinor)
                assertEquals(0, corrected.totals.cashMinor)
                assertEquals(2, corrected.sales.single().payments.size)
                ds.connection.use { c ->
                    assertEquals(1, c.count("venue_accounting_commands", "request_id='${cmd.requestId}'"))
                    assertEquals(1, c.count("venue_accounting_events", "action='PAY_SPLIT'"))
                    assertEquals(1, c.count("audit_log", "action='VENUE_ACCOUNTING_PAY_SPLIT'"))
                }
            }
        }

    @Test fun `split validates every component and explicit partial acknowledgment before any write`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                val initial = repo.execute(1, 101, splitManual())
                val valid = splitCommand(initial, initial.sales.single())
                val invalid =
                    listOf(
                        emptyList(),
                        listOf(AccountingPaymentInput("CASH", 99000)),
                        listOf(AccountingPaymentInput("CASH", 0), AccountingPaymentInput("CARD", 99000)),
                        listOf(AccountingPaymentInput("CASH", -1), AccountingPaymentInput("CARD", 99001)),
                        listOf(AccountingPaymentInput("CASH", 1), AccountingPaymentInput("CASH", 98999)),
                        listOf(AccountingPaymentInput("SPLIT", 1), AccountingPaymentInput("CARD", 98999)),
                        listOf(AccountingPaymentInput("CASH", Long.MAX_VALUE), AccountingPaymentInput("CARD", 1)),
                    )
                for (parts in invalid) assertFailsWith<InvalidInputException> {
                    repo.execute(1, 101, valid.copy(paymentComponents = parts))
                }
                assertFailsWith<AccountingConflictException> {
                    repo.execute(
                        1,
                        101,
                        valid.copy(
                            paymentComponents =
                                listOf(
                                    AccountingPaymentInput("CASH", 99000),
                                    AccountingPaymentInput("CARD", 1),
                                ),
                        ),
                    )
                }
                val partial =
                    valid.copy(
                        paymentComponents =
                            listOf(
                                AccountingPaymentInput("CASH", 10000),
                                AccountingPaymentInput("TRANSFER", 30000),
                            ),
                    )
                assertFailsWith<InvalidInputException> { repo.execute(1, 101, partial) }
                assertEquals(initial.sales.single().id, initial.createdSaleId)
                assertEquals(initial.copy(createdSaleId = null), repo.detail(1, 101, initial.shiftId))
                val paid = repo.execute(1, 101, partial.copy(acknowledgePartial = true))
                assertEquals(59000, paid.totals.unpaidMinor)
                val final =
                    repo.execute(
                        1,
                        101,
                        command(
                            "PAY",
                            paid.shiftId,
                            paid.sales.single(),
                        ).copy(paymentMethod = "CARD", amountMinor = 59000),
                    )
                assertEquals(0, final.totals.unpaidMinor)
                assertEquals(3, final.sales.single().payments.size)
            }
        }

    @Test fun `split audit failure rolls back both receipts and retry commits exactly one operation`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                val initial = repo.execute(1, 101, splitManual())
                val cmd = splitCommand(initial, initial.sales.single())
                val broken =
                    AccountingRepository(ds, audit = {
                            _,
                            _,
                            _,
                            _,
                            _,
                            _,
                        ->
                        throw SQLException("Synthetic audit failure")
                    })
                assertFailsWith<DatabaseUnavailableException> { broken.execute(1, 101, cmd) }
                assertEquals(initial.sales.single().id, initial.createdSaleId)
                assertEquals(initial.copy(createdSaleId = null), repo.detail(1, 101, initial.shiftId))
                ds.connection.use { c ->
                    assertEquals(0, c.count("venue_accounting_events", "action='PAY_SPLIT'"))
                    assertEquals(0, c.count("venue_accounting_commands", "request_id='${cmd.requestId}'"))
                    assertEquals(0, c.count("audit_log", "action='VENUE_ACCOUNTING_PAY_SPLIT'"))
                }
                assertEquals(99000, repo.execute(1, 101, cmd).totals.paidMinor)
            }
        }

    @Test fun `split cannot cross tenant revoked closed canceled active or unknown sale boundaries`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                val initial = repo.execute(1, 101, splitManual())
                val cmd = splitCommand(initial, initial.sales.single())
                assertFailsWith<ForbiddenException> { repo.execute(1, 201, cmd) }
                assertFailsWith<Exception> { repo.execute(2, 201, cmd) }
                val canceled =
                    repo.execute(
                        1,
                        101,
                        command("CANCEL", initial.shiftId, initial.sales.single()).copy(reason = "Canceled"),
                    )
                assertFailsWith<AccountingConflictException> {
                    repo.execute(1, 101, cmd.copy(expectedVersion = canceled.sales.single().version))
                }
                val closed =
                    repo.execute(
                        1,
                        101,
                        command("CLOSE", initial.shiftId).copy(expectedReportHash = canceled.reportHash),
                    )
                assertFailsWith<AccountingConflictException> {
                    repo.execute(1, 101, cmd.copy(expectedVersion = closed.sales.single().version))
                }
                ds.connection.use { it.exec("DELETE FROM venue_members WHERE venue_id=1 AND user_id=103") }
                assertFailsWith<ForbiddenException> { repo.execute(1, 103, cmd) }
                assertEquals(0, repo.detail(1, 101, initial.shiftId).totals.paidMinor)
            }
        }

    @Test fun `financial totals denominator ignores payment components and GET writes nothing`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                val first = repo.execute(1, 101, splitManual(190500))
                val second = repo.execute(1, 101, splitManual().copy(shiftId = first.shiftId))
                val paid = repo.execute(1, 101, splitCommand(second, second.sales.single { it.payable == 99000L }))

                fun counts() =
                    ds.connection.use { c ->
                        listOf(
                            "venue_accounting_sales",
                            "venue_accounting_events",
                            "venue_accounting_commands",
                            "audit_log",
                            "orders",
                            "order_batches",
                            "telegram_outbox",
                        ).map {
                            c.count(it)
                        }
                    }
                val before = counts()
                val day = LocalDate.now(java.time.ZoneId.of("Europe/Moscow"))
                val kpi =
                    KpiRepository(
                        ds,
                    ).venue(1, 101, KpiRequest(day.minusDays(30).toString(), day.plusDays(1).toString(), "finance"))
                val metrics = kpi.openShiftFinances.single().metrics
                assertEquals("289500", metrics.getValue("accrued").value)
                assertEquals("2", metrics.getValue("sales").value)
                assertEquals("144750", metrics.getValue("averageAccruedSale").value)
                assertEquals(first.openedAt, kpi.openShiftOpenedAt)
                repo.state(1, 101)
                repo.detail(1, 101, paid.shiftId)
                assertEquals(before, counts())
                val closed =
                    repo.execute(
                        1,
                        101,
                        command(
                            "CLOSE",
                            paid.shiftId,
                        ).copy(expectedReportHash = paid.reportHash, acknowledgeUnpaid = true),
                    )
                val historical =
                    KpiRepository(
                        ds,
                    ).venue(1, 101, KpiRequest(day.minusDays(30).toString(), day.plusDays(1).toString(), "finance"))
                assertEquals(
                    "144750",
                    historical.closedShiftFinances.single().metrics.getValue("averageAccruedSale").value,
                )
                assertTrue(historical.openShiftFinances.isEmpty())
                assertEquals(closed, repo.detail(1, 101, closed.shiftId))
            }
        }
}

internal fun splitManual(amount: Long = 99000) =
    command(
        "CREATE",
    ).copy(tableId = 11, lines = listOf(AccountingLineInput(name = "Tea", unitPriceMinor = amount)))

internal fun splitCommand(
    report: AccountingReport,
    sale: AccountingSale,
) = command(
    "PAY_SPLIT",
    report.shiftId,
    sale,
).copy(paymentComponents = listOf(AccountingPaymentInput("CASH", 40000), AccountingPaymentInput("CARD", 59000)))
