package com.hookah.platform.backend.miniapp.venue.accounting
import com.hookah.platform.backend.api.AccountingConflictException
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.VenueStatsRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountingRepositoryH2Test : AccountingRepositoryContract() {
    override fun database(): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:accounting_${UUID.randomUUID()}" +
                    ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
                username = "sa"
                password = ""
                maximumPoolSize = 5
            },
        ).also {
            Flyway.configure().dataSource(it).locations("classpath:db/migration/h2").load().migrate()
        }
}

class AccountingRepositoryPostgresTest : AccountingRepositoryContract() {
    override fun database(): HikariDataSource = PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase())

    @Test
    fun `revocation committed while payment waits is rechecked before writing`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                val shift = repo.execute(1, 101, command("OPEN"))
                val sale = repo.execute(1, 103, manual(shift.shiftId)).sales.single()
                ds.connection.use { revoke ->
                    revoke.autoCommit = false
                    revoke.exec("UPDATE venues SET updated_at = CURRENT_TIMESTAMP WHERE id = 1")
                    val pid =
                        revoke.createStatement().use { s ->
                            s.executeQuery("SELECT pg_backend_pid()").use { rs ->
                                rs.next()
                                rs.getInt(1)
                            }
                        }
                    val pending =
                        async {
                            runCatching {
                                repo.execute(
                                    1,
                                    103,
                                    command(
                                        "PAY",
                                        shift.shiftId,
                                        sale,
                                    ).copy(
                                        paymentMethod = "CASH",
                                        amountMinor = 100,
                                    ),
                                )
                            }
                        }
                    try {
                        withTimeout(10_000) {
                            ds.connection.use { observer ->
                                while (observer.createStatement().use { s ->
                                        s.executeQuery(
                                            "SELECT COUNT(*) FROM pg_stat_activity WHERE $pid = " +
                                                "ANY(pg_blocking_pids(pid))",
                                        ).use { rs ->
                                            rs.next()
                                            rs.getInt(1)
                                        }
                                    } == 0
                                    ) delay(20)
                            }
                        }
                        revoke.exec("DELETE FROM venue_members WHERE venue_id = 1 AND user_id = 103")
                        revoke.commit()
                        assertTrue(pending.await().exceptionOrNull() is ForbiddenException)
                        assertEquals(0, repo.detail(1, 101, shift.shiftId).totals.paidMinor)
                    } finally {
                        revoke.rollback()
                    }
                }
            }
        }
}

abstract class AccountingRepositoryContract {
    protected abstract fun database(): HikariDataSource

    @Test
    fun `manual menu snapshot partial mixed payments correction and immutable midnight close`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val clock = AccountingClock(Instant.parse("2026-11-07T20:55:00Z"))
                val repo = AccountingRepository(ds, clock = clock)
                val shift = repo.execute(1, 103, command("OPEN"))
                assertEquals("Europe/Moscow", shift.timezone)
                val create =
                    command("CREATE", shift.shiftId).copy(
                        tableId = 11,
                        lines =
                            listOf(
                                AccountingLineInput(
                                    menuItemId = 21,
                                    qty = 2,
                                ),
                                AccountingLineInput(
                                    name = "Подарок",
                                    qty = 1,
                                    unitPriceMinor = 5000,
                                    giftReason = "Комплимент",
                                ),
                            ),
                    )
                val sale = repo.execute(1, 103, create).sales.single()
                assertEquals(20000, sale.payable)
                ds.connection.use { it.exec("UPDATE menu_items SET price_minor = 77777, name = 'New' WHERE id = 21") }
                val repeated = repo.execute(1, 103, create)
                assertEquals(1, repeated.sales.size)
                assertEquals("Чай", repeated.sales.single().lines.first().name)
                assertEquals(10000, repeated.sales.single().lines.first().unitPriceMinor)
                var report =
                    repo.execute(
                        1,
                        103,
                        command(
                            "PAY",
                            shift.shiftId,
                            sale,
                        ).copy(
                            paymentMethod = "CASH",
                            amountMinor = 5000,
                        ),
                    )
                val cardCommand =
                    command(
                        "PAY",
                        shift.shiftId,
                        report.sales.single(),
                    ).copy(
                        paymentMethod = "CARD",
                        amountMinor = 7000,
                    )
                report = repo.execute(1, 102, cardCommand)
                assertEquals(12000, report.totals.paidMinor)
                assertEquals(8000, report.totals.unpaidMinor)
                assertEquals(5000, report.totals.giftMinor)
                assertEquals(2, report.byItem.size)
                assertEquals(listOf("MANUAL"), report.bySource.map { it.label })
                assertFailsWith<AccountingConflictException> {
                    repo.execute(
                        1,
                        102,
                        cardCommand.copy(amountMinor = 6000),
                    )
                }
                assertEquals(report, repo.execute(1, 102, cardCommand))
                assertFailsWith<AccountingConflictException> {
                    repo.execute(
                        1,
                        103,
                        command(
                            "PAY",
                            shift.shiftId,
                            report.sales.single(),
                        ).copy(
                            paymentMethod = "TRANSFER",
                            amountMinor = 8001,
                        ),
                    )
                }
                val cash = report.sales.single().payments.first()
                report =
                    repo.execute(
                        1,
                        103,
                        command(
                            "VOID_PAYMENT",
                            shift.shiftId,
                            report.sales.single(),
                        ).copy(
                            paymentId = cash.id,
                            reason = "Ошибочный способ",
                        ),
                    )
                assertEquals(0, report.totals.cashMinor)
                report =
                    repo.execute(
                        1,
                        103,
                        command(
                            "PAY",
                            shift.shiftId,
                            report.sales.single(),
                        ).copy(
                            paymentMethod = "TRANSFER",
                            amountMinor = 13000,
                        ),
                    )
                assertEquals(20000, report.totals.paidMinor)
                assertEquals(0, report.totals.unpaidMinor)
                assertFailsWith<ForbiddenException> {
                    repo.execute(
                        1,
                        103,
                        command(
                            "CLOSE",
                            shift.shiftId,
                        ).copy(expectedReportHash = report.reportHash),
                    )
                }
                clock.current = Instant.parse("2026-11-08T00:15:00Z")
                val closed =
                    repo.execute(
                        1,
                        102,
                        command(
                            "CLOSE",
                            shift.shiftId,
                        ).copy(expectedReportHash = report.reportHash),
                    )
                assertEquals("CLOSED", closed.status)
                assertEquals("2026-11-07T20:55:00Z", closed.openedAt)
                assertEquals("2026-11-08T00:15:00Z", closed.closedAt)
                assertEquals(closed, repo.detail(1, 101, shift.shiftId))
                assertFailsWith<ForbiddenException> { repo.detail(1, 103, shift.shiftId) }
                assertTrue(repo.state(1, 103).history.isEmpty())
                assertFailsWith<AccountingConflictException> {
                    repo.execute(
                        1,
                        101,
                        command(
                            "PAY",
                            shift.shiftId,
                            closed.sales.single(),
                        ).copy(
                            paymentMethod = "CASH",
                            amountMinor = 1,
                        ),
                    )
                }
                ds.connection.use { c ->
                    assertEquals(0, c.count("orders"))
                    assertEquals(0, c.count("table_sessions"))
                    assertEquals(7, c.count("venue_accounting_events"))
                    assertEquals(7, c.count("audit_log", "action LIKE 'VENUE_ACCOUNTING_%'"))
                    assertEquals(2, c.count("venue_accounting_events", "actor_user_id = 102"))
                    assertEquals(1, c.count("venue_accounting_events", "reason_text = 'Ошибочный способ'"))
                }
            }
        }

    @Test
    fun `digital orders reuse canonical bill snapshots gifts sources and are included exactly once`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                seedDigitalOrder(ds, 31, closed = false)
                val shift = repo.execute(1, 103, command("OPEN"))
                assertEquals(1, shift.sales.size)
                val sale = shift.sales.single()
                assertEquals(10000, sale.payable)
                assertEquals("MINIAPP", sale.source)
                assertEquals(1, shift.totals.ordersCount)
                assertFailsWith<AccountingConflictException> {
                    repo.execute(
                        1,
                        103,
                        command(
                            "PAY",
                            shift.shiftId,
                            sale,
                        ).copy(
                            paymentMethod = "CASH",
                            amountMinor = 1,
                        ),
                    )
                }
                assertFailsWith<AccountingConflictException> {
                    repo.execute(
                        1,
                        101,
                        command(
                            "CLOSE",
                            shift.shiftId,
                        ).copy(
                            expectedReportHash = shift.reportHash,
                            acknowledgeUnpaid = true,
                        ),
                    )
                }
                ds.connection.use { c ->
                    c.exec("UPDATE orders SET status = 'CLOSED' WHERE id = 31")
                    c.exec("UPDATE menu_items SET price_minor = 90000, name = 'Changed' WHERE id = 21")
                }
                val refreshed = repo.detail(1, 103, shift.shiftId)
                assertEquals(10000, refreshed.totals.accruedMinor)
                assertEquals("Чай", refreshed.sales.single().lines.single().name)
                val gift =
                    repo.execute(
                        1,
                        103,
                        command("EDIT", shift.shiftId, refreshed.sales.single()).copy(
                            reason = "Комплимент заведения",
                            lines =
                                listOf(
                                    AccountingLineInput(
                                        id = refreshed.sales.single().lines.single().id,
                                        qty = 1,
                                        giftReason = "Комплимент заведения",
                                    ),
                                ),
                        ),
                    )
                assertEquals(10000, gift.totals.giftMinor)
                assertEquals(0, gift.totals.accruedMinor)
                assertEquals(10000, gift.sales.single().originalPayableMinor)
                assertEquals(1, repo.detail(1, 103, shift.shiftId).sales.size)
                ds.connection.use { c ->
                    assertEquals(1, c.count("orders"))
                    assertEquals(1, c.count("order_batches"))
                    assertEquals(0, c.count("order_batch_items", "discount_percent > 0 OR is_excluded = TRUE"))
                }
            }
        }

    @Test
    fun `order writer binds new order to current shift and prior closed orders stay outside`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                seedDigitalOrder(ds, 31, closed = true)
                val repo = AccountingRepository(ds)
                val shift = repo.execute(1, 101, command("OPEN"))
                assertTrue(shift.sales.isEmpty())
                val id =
                    assertNotNull(
                        OrdersRepository(ds).getOrCreateActiveOrderId(
                            tableId = 11,
                            venueId = 1,
                            tableSessionId = 41,
                        ),
                    )
                ds.connection.use { c ->
                    c.prepareStatement("SELECT accounting_shift_id FROM orders WHERE id = ?").use { s ->
                        s.setLong(1, id)
                        s.executeQuery().use { rs ->
                            assertTrue(rs.next())
                            assertEquals(shift.shiftId, rs.getLong(1))
                        }
                    }
                }
                assertEquals(1, repo.detail(1, 103, shift.shiftId).sales.size)
            }
        }

    @Test
    fun `two venues revoked role foreign tables and stale versions cannot mutate`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                val a = repo.execute(1, 101, command("OPEN"))
                val b = repo.execute(2, 201, command("OPEN"))
                assertFailsWith<ForbiddenException> { repo.state(2, 103) }
                assertFailsWith<NotFoundException> { repo.detail(1, 101, b.shiftId) }
                assertFailsWith<NotFoundException> { repo.execute(1, 103, manual(a.shiftId).copy(tableId = 12)) }
                assertFailsWith<NotFoundException> {
                    repo.execute(
                        1,
                        103,
                        manual(a.shiftId).copy(lines = listOf(AccountingLineInput(menuItemId = 22))),
                    )
                }
                val sale = repo.execute(1, 103, manual(a.shiftId)).sales.single()
                repo.execute(1, 102, command("PAY", a.shiftId, sale).copy(paymentMethod = "CARD", amountMinor = 100))
                assertFailsWith<AccountingConflictException> {
                    repo.execute(
                        1,
                        103,
                        command(
                            "CANCEL",
                            a.shiftId,
                            sale,
                        ).copy(reason = "Stale"),
                    )
                }
                ds.connection.use { it.exec("DELETE FROM venue_members WHERE venue_id = 1 AND user_id = 103") }
                assertFailsWith<ForbiddenException> { repo.execute(1, 103, manual(a.shiftId)) }
                assertEquals(1, repo.detail(1, 101, a.shiftId).sales.size)
                assertTrue(repo.detail(2, 201, b.shiftId).sales.isEmpty())
            }
        }

    @Test
    fun `audit failure rolls back sale payment command and protected event together`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val normal = AccountingRepository(ds)
                val shift = normal.execute(1, 101, command("OPEN"))
                val sale = normal.execute(1, 103, manual(shift.shiftId)).sales.single()
                val broken =
                    AccountingRepository(
                        ds,
                        audit = {
                                _,
                                _,
                                _,
                                _,
                                _,
                                _,
                            ->
                            throw SQLException("Synthetic failure")
                        },
                    )
                val pay = command("PAY", shift.shiftId, sale).copy(paymentMethod = "CASH", amountMinor = 100)
                assertFailsWith<DatabaseUnavailableException> { broken.execute(1, 103, pay) }
                assertEquals(0, normal.detail(1, 103, shift.shiftId).totals.paidMinor)
                ds.connection.use { c ->
                    assertEquals(2, c.count("venue_accounting_events"))
                    assertEquals(2, c.count("venue_accounting_commands"))
                }
                assertEquals(100, normal.execute(1, 103, pay).totals.paidMinor)
                val report = normal.detail(1, 101, shift.shiftId)
                assertFailsWith<DatabaseUnavailableException> {
                    broken.execute(
                        1,
                        101,
                        command(
                            "CLOSE",
                            shift.shiftId,
                        ).copy(
                            expectedReportHash = report.reportHash,
                            acknowledgeUnpaid = true,
                        ),
                    )
                }
                assertEquals("OPEN", normal.detail(1, 101, shift.shiftId).status)
            }
        }

    @Test
    fun `concurrent open and repeated payment have one winner without overpayment`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                val opens =
                    listOf(
                        101L,
                        102L,
                    ).map { actor ->
                        async {
                            runCatching {
                                repo.execute(
                                    1,
                                    actor,
                                    command("OPEN"),
                                )
                            }
                        }
                    }.awaitAll()
                assertEquals(1, opens.count { it.isSuccess })
                assertTrue(opens.single { it.isFailure }.exceptionOrNull() is AccountingConflictException)
                val shift = assertNotNull(repo.state(1, 103).current)
                val sale = repo.execute(1, 103, manual(shift.shiftId)).sales.single()
                val pay = command("PAY", shift.shiftId, sale).copy(paymentMethod = "CARD", amountMinor = 10000)
                val payments = (1..2).map { async { repo.execute(1, 103, pay) } }.awaitAll()
                assertEquals(payments[0], payments[1])
                assertEquals(10000, payments[0].totals.paidMinor)
                assertEquals(1, payments[0].sales.single().payments.size)
                ds.connection.use { c -> assertEquals(3, c.count("venue_accounting_events")) }
            }
        }

    @Test
    fun `free lines discount cancellation dispute close confirmation and stale preview are explicit`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                val shift = repo.execute(1, 103, command("OPEN"))
                assertFailsWith<InvalidInputException> {
                    repo.execute(
                        1,
                        103,
                        manual(shift.shiftId).copy(
                            lines =
                                listOf(
                                    AccountingLineInput(
                                        name = "Zero",
                                        unitPriceMinor = 0,
                                    ),
                                ),
                        ),
                    )
                }
                assertFailsWith<InvalidInputException> {
                    repo.execute(
                        1,
                        103,
                        manual(shift.shiftId).copy(
                            lines =
                                listOf(
                                    AccountingLineInput(
                                        name = "Bad",
                                        unitPriceMinor = -1,
                                    ),
                                ),
                        ),
                    )
                }
                assertFailsWith<InvalidInputException> {
                    repo.execute(
                        1,
                        103,
                        manual(shift.shiftId).copy(
                            lines =
                                listOf(
                                    AccountingLineInput(
                                        name = "Bad",
                                        unitPriceMinor = Long.MAX_VALUE,
                                    ),
                                ),
                        ),
                    )
                }
                val discounted =
                    repo.execute(
                        1,
                        103,
                        manual(shift.shiftId).copy(
                            reason = "Скидка",
                            lines =
                                listOf(
                                    AccountingLineInput(
                                        name = "Чай",
                                        unitPriceMinor = 10000,
                                        discountMinor = 1000,
                                    ),
                                ),
                        ),
                    )
                assertEquals(1000, discounted.totals.discountMinor)
                assertEquals(9000, discounted.totals.unpaidMinor)
                assertFailsWith<AccountingConflictException> {
                    repo.execute(
                        1,
                        102,
                        command(
                            "CLOSE",
                            shift.shiftId,
                        ).copy(
                            expectedReportHash = shift.reportHash,
                            acknowledgeUnpaid = true,
                        ),
                    )
                }
                assertFailsWith<AccountingConflictException> {
                    repo.execute(
                        1,
                        102,
                        command(
                            "CLOSE",
                            shift.shiftId,
                        ).copy(expectedReportHash = discounted.reportHash),
                    )
                }
                val disputed =
                    repo.execute(
                        1,
                        103,
                        command(
                            "DISPUTE",
                            shift.shiftId,
                            discounted.sales.single(),
                        ).copy(reason = "Нужна сверка"),
                    )
                assertEquals(1, disputed.disputedSaleIds.size)
                val cancelled =
                    repo.execute(
                        1,
                        103,
                        command(
                            "CANCEL",
                            shift.shiftId,
                            disputed.sales.single(),
                        ).copy(reason = "Дубль"),
                    )
                assertEquals(0, cancelled.totals.accruedMinor)
                assertEquals(0, cancelled.totals.salesCount)
                assertEquals(1, cancelled.sales.size)
                val closed =
                    repo.execute(
                        1,
                        102,
                        command(
                            "CLOSE",
                            shift.shiftId,
                        ).copy(
                            expectedReportHash = cancelled.reportHash,
                            acknowledgeUnpaid = true,
                        ),
                    )
                assertEquals(cancelled.totals, closed.totals)
                assertNull(repo.state(1, 103).current)
            }
        }

    @Test
    fun `history pages stay venue scoped and legacy manager alias retains access`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                val shift = repo.execute(1, 101, command("OPEN"))
                repo.execute(1, 101, command("CLOSE", shift.shiftId).copy(expectedReportHash = shift.reportHash))
                ds.connection.use { c ->
                    c.exec("UPDATE venue_members SET role = 'ADMIN' WHERE venue_id = 1 AND user_id = 102")
                    repeat(51) {
                        c.exec(
                            "INSERT INTO venue_accounting_shifts " +
                                "(venue_id, status, open_slot, timezone, opened_at, closed_at, final_report) " +
                                "SELECT venue_id, status, open_slot, timezone, opened_at, closed_at, final_report " +
                                "FROM venue_accounting_shifts WHERE id = ${shift.shiftId}",
                        )
                    }
                }
                val first = repo.state(1, 102)
                assertEquals(50, first.history.size)
                val second = repo.state(1, 102, assertNotNull(first.nextHistoryBeforeId))
                assertEquals(2, second.history.size)
                assertNull(second.nextHistoryBeforeId)
                assertTrue(
                    first.history.map { it.id }.toSet().intersect(second.history.map { it.id }.toSet()).isEmpty(),
                )
                assertTrue(repo.state(1, 103, first.nextHistoryBeforeId).history.isEmpty())
                assertTrue(repo.state(2, 201).history.isEmpty())
            }
        }

    @Test
    fun `distinct competing payments from two roles cannot exceed the balance`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                val shift = repo.execute(1, 101, command("OPEN"))
                val sale = repo.execute(1, 103, manual(shift.shiftId)).sales.single()
                val results =
                    listOf(102L, 103L).map { actor ->
                        async {
                            runCatching {
                                repo.execute(
                                    1,
                                    actor,
                                    command("PAY", shift.shiftId, sale).copy(
                                        paymentMethod = "CASH",
                                        amountMinor = 8000,
                                    ),
                                )
                            }
                        }
                    }.awaitAll()
                assertEquals(1, results.count { it.isSuccess })
                assertTrue(results.single { it.isFailure }.exceptionOrNull() is AccountingConflictException)
                val report = repo.detail(1, 101, shift.shiftId)
                assertEquals(8000, report.totals.paidMinor)
                assertEquals(2000, report.totals.unpaidMinor)
                assertEquals(1, report.sales.single().payments.size)
            }
        }

    @Test
    fun `database rejects second open shift null slot and cross venue order link`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                seedDigitalOrder(ds, 31, closed = true)
                val repo = AccountingRepository(ds)
                repo.execute(1, 101, command("OPEN"))
                val foreign = repo.execute(2, 201, command("OPEN"))
                ds.connection.use { c ->
                    assertFailsWith<SQLException> {
                        c.exec(
                            "INSERT INTO venue_accounting_shifts (venue_id, status, open_slot, timezone, " +
                                "opened_at) VALUES (1, 'OPEN', 1, 'UTC', CURRENT_TIMESTAMP)",
                        )
                    }
                    assertFailsWith<SQLException> {
                        c.exec(
                            "INSERT INTO venue_accounting_shifts (venue_id, status, open_slot, timezone, " +
                                "opened_at) VALUES (1, 'OPEN', NULL, 'UTC', CURRENT_TIMESTAMP)",
                        )
                    }
                    assertFailsWith<SQLException> {
                        c.exec(
                            "UPDATE orders SET accounting_shift_id = ${foreign.shiftId} WHERE id = 31",
                        )
                    }
                    assertEquals(2, c.count("venue_accounting_shifts"))
                }
            }
        }

    @Test
    fun `digital mixed sources option service snapshots and legacy estimate remain distinct from receipts`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                seedDigitalOrder(ds, 31, closed = false)
                val repo = AccountingRepository(ds)
                val shift = repo.execute(1, 101, command("OPEN"))
                ds.connection.use { c ->
                    c.exec(
                        "INSERT INTO order_batches (id, order_id, source, status) " +
                            "OVERRIDING SYSTEM VALUE VALUES (52, 31, 'CHAT', 'DELIVERED')",
                    )
                    c.exec(
                        "INSERT INTO order_batch_items (id, order_batch_id, menu_item_id, qty, item_name_snapshot, " +
                            "base_unit_price_minor_snapshot, currency_snapshot) " +
                            "OVERRIDING SYSTEM VALUE VALUES (62, 52, 21, 1, 'Чай', 10000, 'RUB')",
                    )
                    c.exec(
                        "INSERT INTO order_batch_item_options " +
                            "(order_batch_item_id, option_name_snapshot, price_delta_minor_snapshot) " +
                            "VALUES (62, 'Большой', 500)",
                    )
                    c.exec(
                        "INSERT INTO order_service_charges " +
                            "(order_id, venue_id, table_session_id, source, label, qty, " +
                            "unit_price_minor, total_minor) " +
                            "VALUES (31, 1, 41, 'SHIFT_EXTENSION', 'Продление', 1, 5000, 5000)",
                    )
                    c.exec("UPDATE orders SET status = 'CLOSED' WHERE id = 31")
                    c.exec("UPDATE menu_items SET name = 'New price', price_minor = 99999 WHERE id = 21")
                }
                val current = repo.detail(1, 103, shift.shiftId)
                assertEquals(25500, current.totals.accruedMinor)
                assertEquals(0, current.totals.paidMinor)
                assertEquals(1, current.totals.ordersCount)
                assertEquals("MIXED", current.sales.single().source)
                assertEquals(setOf("CHAT", "MINIAPP", "SERVICE_CHARGE"), current.bySource.map { it.label }.toSet())
                assertEquals(25500, current.bySource.sumOf { it.accruedMinor })
                assertEquals(listOf("Чай", "Чай · Большой", "Продление"), current.sales.single().lines.map { it.name })
                val stats = VenueStatsRepository(ds).loadVenueStats(1, Instant.EPOCH)
                assertEquals(20500, stats.revenueMinor)
                assertEquals("Чай", stats.topItems.single().itemName)
                val paid =
                    repo.execute(
                        1,
                        103,
                        command("PAY", shift.shiftId, current.sales.single()).copy(
                            paymentMethod = "CARD",
                            amountMinor = 25500,
                        ),
                    )
                assertEquals(25500, paid.totals.paidMinor)
                assertEquals(1, paid.sales.size)
                assertEquals(0, paid.totals.unpaidMinor)
            }
        }

    @Test
    fun `missing immutable digital price is disputed and never trusted as a payment`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                seedDigitalOrder(ds, 31, closed = false)
                val repo = AccountingRepository(ds)
                val shift = repo.execute(1, 101, command("OPEN"))
                ds.connection.use { c ->
                    c.exec("UPDATE order_batch_items SET base_unit_price_minor_snapshot = NULL WHERE id = 61")
                    c.exec("UPDATE orders SET status = 'CLOSED' WHERE id = 31")
                }
                val report = repo.detail(1, 103, shift.shiftId)
                assertNotNull(report.sales.single().issue)
                assertEquals(1, report.disputedSaleIds.size)
                assertFailsWith<AccountingConflictException> {
                    repo.execute(
                        1,
                        103,
                        command(
                            "PAY",
                            shift.shiftId,
                            report.sales.single(),
                        ).copy(
                            paymentMethod = "CASH",
                            amountMinor = 1,
                        ),
                    )
                }
                assertFailsWith<AccountingConflictException> {
                    repo.execute(
                        1,
                        101,
                        command(
                            "CLOSE",
                            shift.shiftId,
                        ).copy(
                            expectedReportHash = report.reportHash,
                            acknowledgeUnpaid = true,
                        ),
                    )
                }
                assertEquals(0, report.totals.paidMinor)
            }
        }
}

internal fun command(
    action: String,
    shift: Long? = null,
    sale: AccountingSale? = null,
) = AccountingCommand(
    requestId = UUID.randomUUID().toString(),
    action = action,
    shiftId = shift,
    saleId = sale?.id,
    expectedVersion = sale?.version,
)

internal fun manual(shift: Long) =
    command("CREATE", shift).copy(
        tableId = 11,
        lines = listOf(AccountingLineInput(name = "Чай", qty = 1, unitPriceMinor = 10000)),
    )

internal fun seedAccounting(ds: HikariDataSource) =
    ds.connection.use { c ->
        c.exec("INSERT INTO users (telegram_user_id) VALUES (101), (102), (103), (201), (301)")
        c.exec(
            "INSERT INTO venues (id, name, status) OVERRIDING SYSTEM VALUE VALUES (1, " +
                "'A', 'PUBLISHED'), (2, 'B', 'PUBLISHED')",
        )
        c.exec(
            "INSERT INTO venue_members (venue_id, user_id, role) VALUES (1, 101, " +
                "'OWNER'), (1, 102, 'MANAGER'), (1, 103, 'STAFF'), (2, 201, 'OWNER')",
        )
        c.exec("INSERT INTO venue_settings (venue_id, timezone) VALUES (1, 'Europe/Moscow'), (2, 'Asia/Vladivostok')")
        c.exec(
            "INSERT INTO venue_tables (id, venue_id, table_number) OVERRIDING SYSTEM " +
                "VALUE VALUES (11, 1, 1), (12, 2, 1)",
        )
        c.exec(
            "INSERT INTO menu_categories (id, venue_id, name, sort_order, is_active) " +
                "OVERRIDING SYSTEM VALUE VALUES (21, 1, 'Menu', 0, TRUE), (22, 2, 'Menu', 0, " +
                "TRUE)",
        )
        c.exec(
            "INSERT INTO menu_items (id, venue_id, category_id, name, price_minor, " +
                "currency, is_available) OVERRIDING SYSTEM VALUE VALUES (21, 1, 21, 'Чай', " +
                "10000, 'RUB', TRUE), (22, 2, 22, 'B', 10000, 'RUB', TRUE)",
        )
    }

internal fun seedDigitalOrder(
    ds: HikariDataSource,
    id: Long,
    closed: Boolean,
) = ds.connection.use { c ->
    c.exec(
        "INSERT INTO table_sessions (id, venue_id, table_id, started_at, " +
            "last_activity_at, expires_at, status) OVERRIDING SYSTEM VALUE VALUES (41, " +
            "1, 11, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL " +
            "'1' DAY, 'ACTIVE')",
    )
    c.exec(
        "INSERT INTO orders (id, venue_id, table_id, table_session_id, status) " +
            "OVERRIDING SYSTEM VALUE VALUES ($id, 1, 11, 41, '${if (closed) "CLOSED" else "ACTIVE"}')",
    )
    c.exec(
        "INSERT INTO order_batches (id, order_id, source, status) OVERRIDING SYSTEM " +
            "VALUE VALUES (51, $id, 'MINIAPP', 'DELIVERED')",
    )
    c.exec(
        "INSERT INTO order_batch_items (id, order_batch_id, menu_item_id, qty, " +
            "item_name_snapshot, base_unit_price_minor_snapshot, currency_snapshot) " +
            "OVERRIDING SYSTEM VALUE VALUES (61, 51, 21, 1, 'Чай', 10000, 'RUB')",
    )
}

internal fun Connection.exec(sql: String) {
    createStatement().use { it.executeUpdate(sql) }
}

internal fun Connection.count(
    table: String,
    predicate: String = "1=1",
): Int =
    createStatement().use { s ->
        s.executeQuery("SELECT COUNT(*) FROM $table WHERE $predicate").use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }

internal class AccountingClock(var current: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current
}
