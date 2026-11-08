package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.venue.seating.CheckoutSeat
import com.hookah.platform.backend.miniapp.venue.seating.STAFF
import com.hookah.platform.backend.miniapp.venue.seating.SeatingRepository
import com.hookah.platform.backend.miniapp.venue.seating.assertCheckoutOpening
import com.hookah.platform.backend.miniapp.venue.seating.checkoutCounts
import com.hookah.platform.backend.miniapp.venue.seating.checkoutSeat
import com.hookah.platform.backend.miniapp.venue.seating.cmd
import com.hookah.platform.backend.miniapp.venue.seating.fullRow
import com.hookah.platform.backend.miniapp.venue.seating.seatingClock
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.GuestOrderWriteCheckpoint
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.QuickOrderRequest
import com.hookah.platform.backend.test.PostgresTestEnv
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramQuickOrderConcurrencyPostgresTest {
    @Test
    fun `rejected request cannot execute from an older callback after MOVE back or RELEASE and new seating`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 10
                for (action in listOf("MOVE", "RELEASE")) {
                    val seat = checkoutSeat(ds)
                    val hall = SeatingRepository(ds, clock = seatingClock)
                    val gate = QuickOrderSqlGate(ds)
                    val first = Client(ds, seat, gate.source)
                    val second = Client(ds, seat)
                    val original = first.begin()
                    val id = original.payload.getValue("requestId")
                    hall.command(
                        seat.venue,
                        STAFF,
                        cmd(action).copy(id = seat.seat, revision = 0, tableId = seat.tables[1]),
                    )
                    val pending = async(Dispatchers.IO) { first.callback(id) }
                    try {
                        assertTrue(gate.entered.await(15, TimeUnit.SECONDS))
                        val before = checkoutCounts(ds)
                        second.callback(id)
                        assertEquals(DialogState(DialogStateType.NONE), first.dialogs.get(100))
                        assertEquals(before, checkoutCounts(ds))
                        if (action == "MOVE") {
                            hall.command(
                                seat.venue,
                                STAFF,
                                cmd("MOVE").copy(id = seat.seat, revision = 1, tableId = seat.tables[0]),
                            )
                        } else {
                            hall.command(seat.venue, STAFF, cmd("SEAT").copy(tableId = seat.tables[0], partySize = 2))
                            val session = hall.state(seat.venue, STAFF).tables[0].seating!!.tableSessionId
                            assertNotEquals(seat.session, session)
                            GuestTabsRepository(ds).ensurePersonalTab(seat.venue, session, 1003)
                        }
                        val returned = checkoutCounts(ds)
                        val session = fullRow(ds, "table_sessions", seat.session)
                        val tab = fullRow(ds, "tab", seat.tab)
                        gate.release.countDown()
                        pending.await()
                        println(
                            "CALLBACK_RACE $action before=$returned after=${checkoutCounts(
                                ds,
                            )} state=${first.dialogs.get(100)}",
                        )
                        assertEquals(returned, checkoutCounts(ds), "A completed request must never create a batch")
                        assertEquals(DialogState(DialogStateType.NONE), first.dialogs.get(100))
                        assertEquals(session, fullRow(ds, "table_sessions", seat.session))
                        assertEquals(tab, fullRow(ds, "tab", seat.tab))
                        second.callback(id)
                        assertEquals(returned, checkoutCounts(ds))
                    } finally {
                        gate.release.countDown()
                    }
                }
            }
        }

    @Test
    fun `parallel confirms replay one batch while another guest and venue progress`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 10
                val seat = checkoutSeat(ds)
                val holding =
                    QuickOrderSqlGate(ds, { it.startsWith("SELECT payload FROM telegram_dialog_state") }, true)
                val waiting = QuickOrderSqlGate(ds, { false })
                val first = Client(ds, seat, holding.source)
                val second = Client(ds, seat, waiting.source)
                val id = first.begin().payload.getValue("requestId")
                val before = checkoutCounts(ds)
                val one = async(Dispatchers.IO) { first.callback(id) }
                assertTrue(holding.entered.await(15, TimeUnit.SECONDS))
                val two = async(Dispatchers.IO) { second.callback(id) }
                try {
                    assertBlocked(ds, holding, waiting, "FROM venues")
                    val otherSeat =
                        checkoutSeat(ds).let {
                            it.copy(tab = GuestTabsRepository(ds).ensurePersonalTab(it.venue, it.session, 1001).id)
                        }
                    val other = Client(ds, otherSeat, chatId = 200, userId = 1001)
                    val otherState = other.begin()
                    other.callback(id)
                    assertEquals(otherState, other.dialogs.get(200))
                    withTimeout(5000) { other.callback(otherState.payload.getValue("requestId")) }
                    assertEquals(DialogState(DialogStateType.NONE), other.dialogs.get(200))
                    assertEquals(before.getValue("order_batches") + 1, checkoutCounts(ds)["order_batches"])
                    assertCheckoutOpening(ds, otherSeat.venue, actor = 1001)
                } finally {
                    holding.release.countDown()
                }
                one.await()
                two.await()
                val after = checkoutCounts(ds)
                for (table in listOf("orders", "order_batches", "guest_batch_idempotency")) {
                    assertEquals(before.getValue(table) + 2, after[table])
                }
                assertEquals(before["telegram_outbox"], after["telegram_outbox"])
                for (table in listOf("venue_accounting_shifts", "venue_accounting_events")) {
                    assertEquals(before.getValue(table) + 2, after[table])
                }
                assertCheckoutOpening(ds, seat.venue)
                assertTrue(first.messages.contains("Запрос отправлен, ожидайте подтверждения."))
                assertTrue(second.messages.contains("Запрос отправлен, ожидайте подтверждения."))
                assertEquals(DialogState(DialogStateType.NONE), first.dialogs.get(100))
                println("CALLBACK_PARALLEL one batch per request; independent venue and foreign callback PASS")
            }
        }

    @Test
    fun `confirm and cancel or edit serialize on the durable request in both operation orders`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.maximumPoolSize = 10
                for (action in listOf("cancel", "edit")) for (confirmFirst in listOf(false, true)) {
                    val seat = checkoutSeat(ds)
                    val holding =
                        QuickOrderSqlGate(ds, { it.startsWith("SELECT payload FROM telegram_dialog_state") }, true)
                    val waiting = QuickOrderSqlGate(ds, { false })
                    val confirm = Client(ds, seat, if (confirmFirst) holding.source else waiting.source)
                    val change = Client(ds, seat, dialogsSource = if (confirmFirst) waiting.source else holding.source)
                    val original = confirm.begin()
                    val id = original.payload.getValue("requestId")
                    val before = checkoutCounts(ds)
                    val first =
                        async(
                            Dispatchers.IO,
                        ) { if (confirmFirst) confirm.callback(id) else change.callback(id, action) }
                    assertTrue(holding.entered.await(15, TimeUnit.SECONDS))
                    val second =
                        async(
                            Dispatchers.IO,
                        ) { if (confirmFirst) change.callback(id, action) else confirm.callback(id) }
                    try {
                        assertBlocked(ds, holding, waiting, "FROM telegram_dialog_state")
                    } finally {
                        holding.release.countDown()
                    }
                    first.await()
                    second.await()
                    val after = checkoutCounts(ds)
                    for (table in listOf("orders", "order_batches", "guest_batch_idempotency")) {
                        assertEquals(before.getValue(table) + if (confirmFirst) 1 else 0, after[table])
                    }
                    for (table in listOf("audit_log", "venue_accounting_shifts", "venue_accounting_events")) {
                        assertEquals(before.getValue(table) + if (confirmFirst) 1 else 0, after[table])
                    }
                    assertEquals(before["telegram_outbox"], after["telegram_outbox"])
                    assertCheckoutOpening(ds, seat.venue, expected = if (confirmFirst) 1 else 0)
                    val acceptedText = if (action == "edit") "Напишите детали заказа." else "Быстрый заказ отменён."
                    assertEquals(!confirmFirst, change.messages.contains(acceptedText))
                    if (confirmFirst) {
                        assertTrue(
                            change.messages.contains("Запрос уже отправлен или завершён. Проверьте свои заказы."),
                        )
                        assertEquals(DialogState(DialogStateType.NONE), confirm.dialogs.get(100))
                    } else if (action == "edit") {
                        assertEquals(
                            DialogState(DialogStateType.QUICK_ORDER_WAIT_TEXT, original.payload - "text" - "requestId"),
                            change.dialogs.get(100),
                        )
                        change.text("Replacement request")
                        val replacement = change.dialogs.get(100)
                        assertNotEquals(id, replacement.payload["requestId"])
                        for (oldAction in listOf("confirm", "edit", "cancel")) {
                            confirm.callback(id, oldAction)
                            assertEquals(replacement, change.dialogs.get(100))
                            assertEquals(after, checkoutCounts(ds))
                        }
                        change.callback(replacement.payload.getValue("requestId"))
                        assertEquals(after.getValue("order_batches") + 1, checkoutCounts(ds)["order_batches"])
                    } else {
                        confirm.callback(id)
                        assertEquals(DialogState(DialogStateType.NONE), confirm.dialogs.get(100))
                        assertEquals(after, checkoutCounts(ds))
                    }
                    println("CALLBACK_TRANSITION action=$action confirmFirst=$confirmFirst PASS")
                }
            }
        }

    @Test
    fun `lost commit response refuses cancellation and editing while precommit error retains safe retry`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                for (committed in listOf(false, true)) {
                    val seat = checkoutSeat(ds)
                    val fail = AtomicBoolean(true)
                    val source =
                        object : DataSource by ds {
                            override fun getConnection(): Connection {
                                val real = ds.connection
                                return Proxy.newProxyInstance(
                                    Connection::class.java.classLoader,
                                    arrayOf(Connection::class.java),
                                ) { _, method, args ->
                                    try {
                                        val result = method.invoke(real, *(args ?: emptyArray()))
                                        if (committed && method.name == "commit" && fail.compareAndSet(true, false)) {
                                            throw SQLException("Synthetic lost commit response")
                                        }
                                        result
                                    } catch (e: InvocationTargetException) {
                                        throw e.targetException
                                    }
                                } as Connection
                            }
                        }
                    val orders =
                        OrdersRepository(source, clock = seatingClock, guestOrderWriteCheckpoint = {
                            if (!committed && it == GuestOrderWriteCheckpoint.AFTER_IDEMPOTENCY_WRITE &&
                                fail.compareAndSet(true, false)
                            ) {
                                throw SQLException("Synthetic precommit failure")
                            }
                        })
                    val first = Client(ds, seat, ordersOverride = orders)
                    val restarted = Client(ds, seat)
                    val original = first.begin()
                    val id = original.payload.getValue("requestId")
                    val before = checkoutCounts(ds)
                    val session = fullRow(ds, "table_sessions", seat.session)
                    val tab = fullRow(ds, "tab", seat.tab)
                    first.callback(id)
                    assertEquals(original, first.dialogs.get(100))
                    val uncertain = checkoutCounts(ds)
                    for (table in listOf("orders", "order_batches", "guest_batch_idempotency")) {
                        assertEquals(before.getValue(table) + if (committed) 1 else 0, uncertain[table])
                    }
                    for (table in listOf("audit_log", "venue_accounting_shifts", "venue_accounting_events")) {
                        assertEquals(before.getValue(table) + if (committed) 1 else 0, uncertain[table])
                    }
                    assertEquals(before["telegram_outbox"], uncertain["telegram_outbox"])
                    assertCheckoutOpening(ds, seat.venue, expected = if (committed) 1 else 0)
                    if (committed) {
                        for (action in listOf("cancel", "edit")) {
                            restarted.callback(id, action)
                            assertEquals(original, restarted.dialogs.get(100))
                            assertEquals(uncertain, checkoutCounts(ds))
                        }
                    }
                    restarted.callback(id)
                    assertEquals(DialogState(DialogStateType.NONE), restarted.dialogs.get(100))
                    val after = checkoutCounts(ds)
                    assertEquals(before.getValue("order_batches") + 1, after["order_batches"])
                    assertCheckoutOpening(ds, seat.venue)
                    restarted.callback(id)
                    assertEquals(after, checkoutCounts(ds))
                    assertEquals(session, fullRow(ds, "table_sessions", seat.session))
                    assertEquals(tab, fullRow(ds, "tab", seat.tab))
                    println("CALLBACK_RECOVERY committed=$committed exact request replay PASS")
                }
            }
        }

    @Test
    fun `both writer overloads enforce request identity and terminal denial without a router cleanup`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                for (connected in listOf(false, true)) {
                    val seat = checkoutSeat(ds)
                    val client = Client(ds, seat)
                    val original = client.begin()
                    val id = original.payload.getValue("requestId")
                    val repo = OrdersRepository(ds, clock = seatingClock)

                    suspend fun submit(request: QuickOrderRequest) =
                        if (connected) {
                            ds.connection.use { c ->
                                c.autoCommit = false
                                try {
                                    repo.createGuestOrderBatch(
                                        c, seat.tables[0], seat.venue, seat.session, 1003, "bot-quick:$id", seat.tab,
                                        original.payload.getValue(
                                            "text",
                                        ),
                                        emptyList(), chatOrder = true, expectedTableToken = seat.token,
                                        quickOrderRequest = request,
                                    ).also {
                                        c.commit()
                                    }
                                } catch (e: Exception) {
                                    c.rollback()
                                    throw e
                                }
                            }
                        } else {
                            repo.createGuestOrderBatch(
                                seat.tables[0], seat.venue, seat.session, 1003, "bot-quick:$id", seat.tab,
                                original.payload.getValue(
                                    "text",
                                ),
                                emptyList(), chatOrder = true, expectedTableToken = seat.token,
                                quickOrderRequest = request,
                            )
                        }
                    val before = checkoutCounts(ds)
                    assertNull(submit(QuickOrderRequest(200, id)))
                    assertEquals(original, client.dialogs.get(100))
                    assertEquals(before, checkoutCounts(ds))
                    val hall = SeatingRepository(ds, clock = seatingClock)
                    hall.command(
                        seat.venue,
                        STAFF,
                        cmd("MOVE").copy(id = seat.seat, revision = 0, tableId = seat.tables[1]),
                    )
                    assertNull(submit(QuickOrderRequest(100, id)))
                    assertEquals(DialogState(DialogStateType.NONE), client.dialogs.get(100))
                    hall.command(
                        seat.venue,
                        STAFF,
                        cmd("MOVE").copy(id = seat.seat, revision = 1, tableId = seat.tables[0]),
                    )
                    val returned = checkoutCounts(ds)
                    assertNull(submit(QuickOrderRequest(100, id)))
                    assertEquals(returned, checkoutCounts(ds))
                    println("CALLBACK_WRITER connected=$connected terminal refusal PASS")
                }
            }
        }

    @Test
    fun `failed Telegram refusal notification cannot reopen a terminal request`() =
        runBlocking<Unit> {
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                val seat = checkoutSeat(ds)
                val client = Client(ds, seat)
                val hall = SeatingRepository(ds, clock = seatingClock)
                val id = client.begin().payload.getValue("requestId")
                var notified = false
                coEvery {
                    client.outbox.enqueueSendMessage(100, "Доступ к счёту изменился. Откройте QR-код ещё раз.", any())
                } coAnswers {
                    assertEquals(DialogState(DialogStateType.NONE), client.dialogs.get(100))
                    notified = true
                    throw SQLException("Synthetic notification failure")
                }
                hall.command(
                    seat.venue,
                    STAFF,
                    cmd("MOVE").copy(id = seat.seat, revision = 0, tableId = seat.tables[1]),
                )
                val before = checkoutCounts(ds)
                client.callback(id)
                assertTrue(notified)
                assertEquals(DialogState(DialogStateType.NONE), client.dialogs.get(100))
                assertEquals(before, checkoutCounts(ds))
                hall.command(
                    seat.venue,
                    STAFF,
                    cmd("MOVE").copy(id = seat.seat, revision = 1, tableId = seat.tables[0]),
                )
                val returned = checkoutCounts(ds)
                client.callback(id)
                assertEquals(returned, checkoutCounts(ds))
                assertEquals(DialogState(DialogStateType.NONE), client.dialogs.get(100))
            }
        }

    private suspend fun assertBlocked(
        ds: DataSource,
        holding: QuickOrderSqlGate,
        waiting: QuickOrderSqlGate,
        table: String,
    ) {
        val query =
            withTimeout(8000) {
                var observed: String? = null
                while (observed == null) {
                    observed =
                        ds.connection.use { c ->
                            c.prepareStatement(
                                "SELECT query FROM pg_stat_activity WHERE pid = ? AND ? = ANY(pg_blocking_pids(pid))",
                            ).use {
                                    s ->
                                s.setInt(1, waiting.pid.get())
                                s.setInt(2, holding.pid.get())
                                s.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                            }
                        }
                    if (observed == null) delay(10)
                }
                observed
            }
        assertNotEquals(holding.pid.get(), waiting.pid.get())
        assertTrue(query.contains(table), query)
        println("CALLBACK_LOCK firstPid=${holding.pid.get()} secondPid=${waiting.pid.get()} blockedOn=$query")
    }

    private class Client(
        ds: DataSource,
        seat: CheckoutSeat,
        ordersSource: DataSource = ds,
        dialogsSource: DataSource = ds,
        ordersOverride: OrdersRepository? = null,
        val chatId: Long = 100,
        val userId: Long = 1003,
    ) {
        val fixture =
            TelegramBotRouterTableTokenTest().also {
                it.setup()
                it.stubQuickOrderSeat(seat, chatId, userId)
            }
        val messages: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val outbox: TelegramOutboxEnqueuer =
            mockk(relaxed = true) {
                coEvery { enqueueSendMessage(any(), any(), any()) } answers {
                    messages.add(secondArg())
                    TelegramOutboxEnqueueOutcome.ENQUEUED
                }
            }
        val dialogs = DialogStateRepository(dialogsSource, Json)
        val router =
            fixture.quickOrderRouter(
                dialogs,
                ordersOverride ?: OrdersRepository(ordersSource, clock = seatingClock),
                outbox,
            )

        suspend fun begin(): DialogState {
            text("✍️ Быстрый заказ")
            text("Synthetic callback race")
            return dialogs.get(chatId).also { assertEquals(DialogStateType.QUICK_ORDER_WAIT_CONFIRM, it.state) }
        }

        suspend fun text(text: String) = fixture.sendQuickOrderText(router, text, chatId, userId)

        suspend fun callback(
            id: String,
            action: String = "confirm",
        ) = fixture.sendQuickOrderCallback(
            router,
            id,
            action,
            userId,
            chatId,
        )
    }
}

private class QuickOrderSqlGate(
    ds: DataSource,
    private val matches: (String) -> Boolean = { it.startsWith("SELECT id FROM venues") },
    private val afterExecution: Boolean = false,
) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val pid = AtomicInteger()
    private val once = AtomicBoolean()
    val source =
        object : DataSource by ds {
            override fun getConnection(): Connection {
                val real = ds.connection
                real.createStatement().use { statement ->
                    statement.execute("SET statement_timeout = '20s'")
                    statement.executeQuery("SELECT pg_backend_pid()").use { rs ->
                        rs.next()
                        pid.set(rs.getInt(1))
                    }
                }
                return Proxy.newProxyInstance(
                    Connection::class.java.classLoader,
                    arrayOf(Connection::class.java),
                ) { _, method, args ->
                    try {
                        val sql = (args?.firstOrNull() as? String)?.replace(Regex("\\s+"), " ")?.trim()
                        if (method.name == "prepareStatement" && sql != null && matches(sql)) {
                            if (!afterExecution && once.compareAndSet(false, true)) pause()
                            val statement = method.invoke(real, *(args ?: emptyArray())) as PreparedStatement
                            Proxy.newProxyInstance(
                                PreparedStatement::class.java.classLoader,
                                arrayOf(PreparedStatement::class.java),
                            ) { _, call, values ->
                                try {
                                    call.invoke(statement, *(values ?: emptyArray())).also {
                                        if (afterExecution && call.name.startsWith("execute") &&
                                            once.compareAndSet(false, true)
                                        ) {
                                            pause()
                                        }
                                    }
                                } catch (e: InvocationTargetException) {
                                    throw e.targetException
                                }
                            }
                        } else {
                            method.invoke(real, *(args ?: emptyArray()))
                        }
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                } as Connection
            }
        }

    private fun pause() {
        entered.countDown()
        check(release.await(20, TimeUnit.SECONDS))
    }
}
