package com.hookah.platform.backend.miniapp.venue.accounting

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.guest.db.GuestOrderTransactionCoordinator
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.telegram.db.CreatedOrderBatch
import com.hookah.platform.backend.telegram.db.GuestOrderWriteCheckpoint
import com.hookah.platform.backend.telegram.db.OrderBatchItemInput
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountingCheckoutConcurrencyPostgresTest {
    @Test
    fun `Mini App first and Bot catalog or text append to the same active order without deadlock`() =
        runBlocking { for (chat in listOf(false, true)) appendConcurrently(miniAppFirst = true, chat = chat) }

    @Test
    fun `Bot catalog or text first and Mini App append to the same active order without deadlock`() =
        runBlocking { for (chat in listOf(false, true)) appendConcurrently(miniAppFirst = false, chat = chat) }

    @Test
    fun `first Mini App and Bot checkouts commit one opening and exact replays in either start order`() =
        runBlocking {
            for (miniAppFirst in listOf(false, true)) {
                for (chat in listOf(false, true)) {
                    appendConcurrently(miniAppFirst, chat, existingOrder = false)
                }
            }
        }

    private suspend fun appendConcurrently(
        miniAppFirst: Boolean,
        chat: Boolean,
        existingOrder: Boolean = true,
    ) = kotlinx.coroutines.coroutineScope {
        fixture().use { ds ->
            val original = if (existingOrder) assertNotNull(checkout(ds, miniApp = false)) else null
            if (!existingOrder) assertEmpty(ds)
            val firstKey = UUID.randomUUID().toString()
            val secondKey = UUID.randomUUID().toString()
            // Hold the first writer after venue acquisition; observe the second before its venue lock.
            val firstSql =
                if (miniAppFirst) "SELECT table_number, is_active FROM venue_tables" else "SELECT id FROM venue_tables"
            val secondSql =
                if (miniAppFirst) "SELECT id FROM venues" else "SELECT id, name, status, staff_chat_id FROM venues"
            SqlBarrier(ds, firstSql).use { first ->
                SqlBarrier(ds, secondSql, hold = false).use { second ->
                    val one = async { checkout(first, miniAppFirst, firstKey, chat = !miniAppFirst && chat) }
                    val holder = first.awaitEntered()
                    val two = async { checkout(second, !miniAppFirst, secondKey, chat = miniAppFirst && chat) }
                    awaitBlocked(ds, holder, second.awaitEntered())
                    first.release()
                    val firstBatch = assertNotNull(one.await())
                    val secondBatch = assertNotNull(two.await())
                    if (original != null) assertEquals(original.orderId, firstBatch.orderId)
                    assertEquals(firstBatch.orderId, secondBatch.orderId)
                    assertNotEquals(firstBatch.batchId, secondBatch.batchId)
                    assertEquals(
                        firstBatch.batchId,
                        checkout(ds, miniAppFirst, firstKey, !miniAppFirst && chat)?.batchId,
                    )
                    assertEquals(
                        secondBatch.batchId,
                        checkout(ds, !miniAppFirst, secondKey, miniAppFirst && chat)?.batchId,
                    )
                }
            }
            val batches = if (existingOrder) 3 else 2
            assertSingleOpening(ds, batches)
            val report = assertNotNull(AccountingRepository(ds).state(1, 101).current)
            assertEquals(1, report.totals.ordersCount)
            assertEquals((batches - if (chat) 1 else 0) * 10000L, report.totals.accruedMinor)
            assertEquals(0, report.totals.paidMinor)
        }
    }

    @Test
    fun `first manual sale and Bot checkout share one shift in either start order with exact replays`() =
        runBlocking {
            for (manualFirst in listOf(false, true)) for (chat in listOf(false, true)) fixture().use { ds ->
                val sale = manual(1).copy(shiftId = null)
                val orderKey = UUID.randomUUID().toString()
                SqlBarrier(ds, "INSERT INTO venue_accounting_events", after = true).use { first ->
                    SqlBarrier(ds, "SELECT id FROM venues WHERE id", hold = false).use { second ->
                        val one =
                            async {
                                if (manualFirst) {
                                    AccountingRepository(first).execute(1, 103, sale)
                                } else {
                                    assertNotNull(checkout(first, miniApp = false, key = orderKey, chat = chat))
                                }
                            }
                        val holder = first.awaitEntered()
                        val two =
                            async {
                                if (manualFirst) {
                                    assertNotNull(checkout(second, miniApp = false, key = orderKey, chat = chat))
                                } else {
                                    AccountingRepository(second).execute(1, 103, sale)
                                }
                            }
                        awaitBlocked(ds, holder, second.awaitEntered())
                        first.release()
                        one.await()
                        two.await()
                    }
                }
                val repo = AccountingRepository(ds)
                val current = assertNotNull(repo.state(1, 101).current)
                assertEquals(current, repo.execute(1, 103, sale))
                assertTrue(assertNotNull(checkout(ds, false, orderKey, chat)).idempotencyReplay)
                assertEquals(if (chat) 10000 else 20000, current.totals.accruedMinor)
                assertEquals(0, current.totals.paidMinor)
                ds.connection.use { c ->
                    assertEquals(1, c.count("venue_accounting_shifts", "status = 'OPEN'"))
                    assertEquals(1, c.count("orders", "accounting_shift_id = ${current.shiftId}"))
                    assertEquals(1, c.count("order_batches"))
                    assertEquals(1, c.count("guest_batch_idempotency"))
                    assertEquals(1, c.count("venue_accounting_sales", "shift_id = ${current.shiftId}"))
                    assertEquals(1, c.count("venue_accounting_commands"))
                    val actor = if (manualFirst) 103 else 301
                    assertEquals(
                        1,
                        c.count("venue_accounting_events", "action = 'AUTO_OPEN' AND actor_user_id = $actor"),
                    )
                    assertEquals(
                        1,
                        c.count("audit_log", "action = 'VENUE_ACCOUNTING_AUTO_OPEN' AND actor_user_id = $actor"),
                    )
                    assertEquals(1, c.count("audit_log", "action = 'VENUE_ACCOUNTING_CREATE'"))
                    assertEquals(2, c.count("audit_log"))
                }
            }
        }

    @Test
    fun `a failed first checkout rolls back opening before the other channel commits and retry adds no duplicate`() =
        runBlocking {
            for (miniAppFirst in listOf(false, true)) fixture().use { ds ->
                val failedKey = UUID.randomUUID().toString()
                val successfulKey = UUID.randomUUID().toString()
                val waitingSql =
                    if (miniAppFirst) "SELECT id FROM venues" else "SELECT id, name, status, staff_chat_id FROM venues"
                SqlBarrier(ds, "INSERT INTO venue_accounting_events", after = true, fail = true).use { first ->
                    SqlBarrier(ds, waitingSql, hold = false).use { second ->
                        val failed = async { runCatching { checkout(first, miniAppFirst, failedKey) } }
                        val holder = first.awaitEntered()
                        val waiting = async { checkout(second, !miniAppFirst, successfulKey) }
                        awaitBlocked(ds, holder, second.awaitEntered())
                        first.release()
                        assertTrue(failed.await().exceptionOrNull() is DatabaseUnavailableException)
                        val saved = assertNotNull(waiting.await())
                        assertSingleOpening(ds, batches = 1)
                        assertEquals(saved.orderId, checkout(ds, miniAppFirst, failedKey)?.orderId)
                        assertEquals(saved.batchId, checkout(ds, !miniAppFirst, successfulKey)?.batchId)
                    }
                }
                assertSingleOpening(ds, batches = 2)
            }
        }

    @Test
    fun `both transaction paths reject revoked guest scope and roll back a failure after idempotency write`() =
        runBlocking {
            for (miniApp in listOf(false, true)) fixture().use { ds ->
                ds.connection.use { it.exec("DELETE FROM tab_member WHERE tab_id = 51 AND user_id = 301") }
                assertNull(checkout(ds, miniApp))
                assertEmpty(ds)
                ds.connection.use {
                    it.exec(
                        "INSERT INTO tab_member (tab_id, user_id, role) VALUES (51, 301, 'OWNER')",
                    )
                }
                val failing =
                    OrdersRepository(ds, guestOrderWriteCheckpoint = {
                        if (it == GuestOrderWriteCheckpoint.AFTER_IDEMPOTENCY_WRITE) {
                            throw SQLException(
                                "forced rollback",
                            )
                        }
                    })
                val failure = runCatching { checkout(ds, miniApp, repository = failing) }
                assertTrue(failure.exceptionOrNull() is DatabaseUnavailableException)
                assertEmpty(ds)
            }
        }

    @Test
    fun `a blocked venue does not serialize checkout at a different venue`() =
        runBlocking {
            fixture().use { ds ->
                SqlBarrier(ds, "SELECT id FROM venue_tables").use { first ->
                    val pending = async { checkout(first, miniApp = false) }
                    first.awaitEntered()
                    try {
                        assertNotNull(withTimeout(5_000) { checkout(ds, miniApp = true, venue = 2) })
                    } finally {
                        first.release()
                    }
                    assertNotNull(pending.await())
                }
                ds.connection.use { c ->
                    assertEquals(2, c.count("venue_accounting_shifts"))
                    assertEquals(2, c.count("orders"))
                }
            }
        }

    private fun fixture(): HikariDataSource =
        PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).also { ds ->
            seedAccounting(ds)
            ds.connection.use { c ->
                for ((venue, table, session, tab) in listOf(listOf(1, 11, 41, 51), listOf(2, 12, 42, 52))) {
                    c.exec(
                        "INSERT INTO table_tokens (table_id, token, is_active) " +
                            "VALUES ($table, 'checkout-$venue', TRUE)",
                    )
                    c.exec("INSERT INTO venue_subscriptions (venue_id, status) VALUES ($venue, 'ACTIVE')")
                    c.exec(
                        "INSERT INTO table_sessions (id, venue_id, table_id, started_at, last_activity_at, " +
                            "expires_at, status) OVERRIDING SYSTEM VALUE VALUES ($session, $venue, $table, " +
                            "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '10' DAY, 'ACTIVE')",
                    )
                    c.exec(
                        "INSERT INTO tab (id, venue_id, table_session_id, type, owner_user_id, status) " +
                            "OVERRIDING SYSTEM VALUE VALUES ($tab, $venue, $session, 'PERSONAL', 301, 'ACTIVE')",
                    )
                    c.exec("INSERT INTO tab_member (tab_id, user_id, role) VALUES ($tab, 301, 'OWNER')")
                }
            }
        }

    private suspend fun checkout(
        ds: DataSource,
        miniApp: Boolean,
        key: String = UUID.randomUUID().toString(),
        chat: Boolean = false,
        venue: Long = 1,
        repository: OrdersRepository = OrdersRepository(ds),
    ): CreatedOrderBatch? {
        val table = if (venue == 1L) 11L else 12L
        val session = if (venue == 1L) 41L else 42L
        val tab = if (venue == 1L) 51L else 52L
        val items =
            if (chat) {
                emptyList()
            } else {
                listOf(
                    OrderBatchItemInput(itemId = if (venue == 1L) 21 else 22, qty = 1),
                )
            }
        val comment = if (chat) "Текстовый заказ" else null
        return if (miniApp) {
            GuestOrderTransactionCoordinator(
                ds,
                TableTokenRepository(ds),
                SubscriptionRepository(ds),
                TableSessionRepository(ds),
            ).executeAuthorized(301, "checkout-$venue", venue, table, session) { connection, _ ->
                repository.createGuestOrderBatch(connection, table, venue, session, 301, key, tab, comment, items)
            }
        } else {
            repository.createGuestOrderBatch(table, venue, session, 301, key, tab, comment, items, chatOrder = chat)
        }
    }

    private fun assertSingleOpening(
        ds: DataSource,
        batches: Int,
    ) = ds.connection.use { c ->
        assertEquals(1, c.count("venue_accounting_shifts"))
        assertEquals(1, c.count("orders", "accounting_shift_id IS NOT NULL"))
        assertEquals(batches, c.count("order_batches"))
        assertEquals(batches, c.count("guest_batch_idempotency"))
        assertEquals(1, c.count("venue_accounting_events", "action = 'AUTO_OPEN' AND actor_user_id = 301"))
        assertEquals(1, c.count("audit_log", "action = 'VENUE_ACCOUNTING_AUTO_OPEN' AND actor_user_id = 301"))
        assertEquals(1, c.count("audit_log"))
    }

    private fun assertEmpty(ds: DataSource) =
        ds.connection.use { c ->
            for (table in listOf(
                "venue_accounting_shifts",
                "venue_accounting_events",
                "orders",
                "order_batches",
                "guest_batch_idempotency",
                "audit_log",
            )) assertEquals(0, c.count(table), table)
        }

    private suspend fun awaitBlocked(
        ds: DataSource,
        holder: Int,
        waiter: Int,
    ) = withTimeout(5_000) {
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
