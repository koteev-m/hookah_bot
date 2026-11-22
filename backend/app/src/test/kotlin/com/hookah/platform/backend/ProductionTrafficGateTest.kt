package com.hookah.platform.backend

import com.hookah.platform.backend.telegram.Chat
import com.hookah.platform.backend.telegram.Message
import com.hookah.platform.backend.telegram.TelegramApiClient
import com.hookah.platform.backend.telegram.TelegramCallResult
import com.hookah.platform.backend.telegram.TelegramLongPollingWorker
import com.hookah.platform.backend.telegram.TelegramOutboxConfig
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.TelegramOutboxWorker
import com.hookah.platform.backend.telegram.TelegramRateLimiter
import com.hookah.platform.backend.telegram.TelegramTrafficPolicy
import com.hookah.platform.backend.telegram.TelegramUpdate
import com.hookah.platform.backend.telegram.User
import com.hookah.platform.backend.telegram.db.IdempotencyRepository
import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductionTrafficGateTest {
    @TempDir
    lateinit var decisions: Path

    @Test
    fun `disaster recovery hold survives restart and denies even matching activation and ingress tickets`() =
        runBlocking {
            decisions.resolve("recovery-hold").writeText("incident")
            repeat(2) {
                val gate = ProductionTrafficGate(decisions, "sha")
                val ticket = "sha ${gate.instance}"
                decisions.resolve("activate").writeText(ticket)
                decisions.resolve("open").writeText(ticket)
                assertFalse(gate.admitsHttp(false))
                assertNull(withTimeoutOrNull(250) { gate.awaitActivation() })
                val writer = CompletableDeferred<Unit>()
                gate.startDatabaseWriters(this) { writer.complete(Unit) }
                assertNull(withTimeoutOrNull(250) { writer.await() })
                decisions.resolve("open").deleteExisting()
                decisions.resolve("recovery-hold").deleteExisting()
                decisions.resolve("recovery-accepted").writeText("reviewed")
                assertNull(withTimeoutOrNull(250) { writer.await() })
                decisions.resolve("open").writeText(ticket)
                withTimeout(3_000) { writer.await() }
                decisions.resolve("recovery-hold").writeText("incident")
            }
        }

    @Test
    fun `recovering poller checks backlog without acknowledging or processing commands`() =
        runBlocking {
            val gate = ProductionTrafficGate(decisions, "sha")
            val hold = decisions.resolve("recovery-hold")
            hold.writeText("review")
            val ticket = "sha ${gate.instance}"
            decisions.resolve("activate").writeText(ticket)
            decisions.resolve("open").writeText(ticket)
            val pending = AtomicBoolean(true)
            val reads = AtomicInteger()
            val booking = TelegramUpdate(42, Message(9, Chat(11, "private"), fromUser = User(11), text = "/book"))
            val codec = Json { ignoreUnknownKeys = true }
            val http =
                HttpClient(
                    MockEngine { request ->
                        val data =
                            when (request.url.encodedPath.substringAfterLast('/')) {
                                "getWebhookInfo" -> """{"url":"","pending_update_count":0}"""
                                "getUpdates" -> {
                                    assertEquals(null, request.url.parameters["offset"])
                                    assertEquals("0", request.url.parameters["timeout"])
                                    reads.incrementAndGet()
                                    if (pending.get()) {
                                        "[" + codec.encodeToString(TelegramUpdate.serializer(), booking) + "]"
                                    } else {
                                        "[]"
                                    }
                                }
                                else -> error("unexpected Telegram operation")
                            }
                        respond(
                            """{"ok":true,"result":$data}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(codec) } }
            val api = TelegramApiClient("fixture", http, codec, TelegramTrafficPolicy.product())
            val commands = CompletableDeferred<Unit>()
            val worker =
                gate.supervise(this, polling = true) {
                    commands.complete(Unit)
                    launch { awaitCancellation() }
                }
            try {
                suspend fun readiness() = gate.recordRecoveryPoll(api.recoveryPollingState())
                assertEquals("backlog", readiness())
                assertEquals("backlog", readiness())
                assertTrue(pending.get())
                assertNull(withTimeoutOrNull(250) { commands.await() })
                assertFalse(gate.admitsHttp(true))
                java.nio.file.Files.move(hold, decisions.resolve("recovery-accepted"))
                assertTrue(ProductionTrafficGate(decisions, "sha").telegramRecoveryPending())
                assertEquals("backlog", readiness())
                assertNull(withTimeoutOrNull(250) { commands.await() })
                pending.set(false)
                assertEquals("empty", readiness())
                assertEquals(4, reads.get())
                assertFalse(commands.isCompleted)
                decisions.resolve("telegram-recovery-cleared").writeText(ticket)
                assertFalse(gate.admitsHttp(true))
                withTimeout(3_000) { commands.await() }
                gate.pollingSucceeded()
                assertTrue(gate.admitsHttp(true))
                val restarted = ProductionTrafficGate(decisions, "sha")
                assertNull(withTimeoutOrNull(250) { restarted.awaitActivation() })
            } finally {
                worker.cancelAndJoin()
                http.close()
            }
        }

    @Test
    fun `prepared activation polls without dispatching booking commands or starting outbox`() =
        runBlocking {
            val gate = ProductionTrafficGate(decisions, "sha")
            val ticket = "sha ${gate.instance}"
            val prepared = CompletableDeferred<Unit>()
            val command = CompletableDeferred<String?>()
            val outbox = CompletableDeferred<Unit>()
            val booking = TelegramUpdate(42, Message(9, Chat(11, "private"), fromUser = User(11), text = "/book"))
            val worker =
                TelegramLongPollingWorker(
                    getUpdates = { offset, timeout ->
                        if (timeout == 0) {
                            assertNull(offset)
                            prepared.complete(Unit)
                        } else {
                            delay(10)
                        }
                        if (offset == null) listOf(booking) else emptyList()
                    },
                    getWebhookUrl = { "" },
                    processUpdate = { command.complete(it.message?.text) },
                    trafficPolicy = TelegramTrafficPolicy.product(),
                    canDispatch = gate::telegramDispatchAllowed,
                    onPollingSuccess = gate::pollingSucceeded,
                    timeoutSeconds = 1,
                    scope = this,
                )
            val polling = gate.supervise(this, polling = true, start = worker::start)
            val sender =
                gate.supervise(this) {
                    outbox.complete(Unit)
                    launch { awaitCancellation() }
                }
            try {
                decisions.resolve("activate").writeText(ticket)
                withTimeout(3_000) { prepared.await() }
                assertNull(withTimeoutOrNull(250) { command.await() })
                assertFalse(outbox.isCompleted)
                assertNull(worker.currentOffset())
                assertFalse(ProductionTrafficGate(decisions, "sha").telegramDispatchAllowed())
                decisions.resolve("open").writeText(ticket)
                assertEquals("/book", withTimeout(3_000) { command.await() })
                withTimeout(3_000) { outbox.await() }
            } finally {
                polling.cancelAndJoin()
                sender.cancelAndJoin()
            }
        }

    @Test
    fun `closed admission leaves queued outbox rows untouched even when the worker ticks`() =
        runBlocking {
            val gate = ProductionTrafficGate(decisions, "sha")
            val ticket = "sha ${gate.instance}"
            val policy = TelegramTrafficPolicy.product()
            PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase()).use { ds ->
                ds.connection.use { c ->
                    c.createStatement().use { it.executeUpdate("INSERT INTO users (telegram_user_id) VALUES (42)") }
                }
                val repo = TelegramOutboxRepository(ds, policy)
                TelegramOutboxEnqueuer(repo, Json, policy).enqueueSendMessage(42, "fixture")

                fun status() =
                    ds.connection.use { c ->
                        c.createStatement().use { statement ->
                            statement.executeQuery("SELECT status FROM telegram_outbox WHERE chat_id=42").use {
                                check(it.next())
                                it.getString(1)
                            }
                        }
                    }

                val client = mockk<TelegramApiClient>()
                coEvery { client.dispatchOutbox(42L, "sendMessage", any()) } returns
                    TelegramCallResult.Success(JsonNull)
                val worker =
                    TelegramOutboxWorker(
                        repository = repo,
                        apiClientProvider = { client },
                        json = Json,
                        rateLimiter = TelegramRateLimiter { },
                        config = TelegramOutboxConfig(),
                        scope = this,
                        canDispatch = gate::telegramDispatchAllowed,
                    )
                decisions.resolve("activate").writeText(ticket)
                val started = CompletableDeferred<Unit>()
                val poller =
                    gate.supervise(this, polling = true) {
                        started.complete(Unit)
                        launch { awaitCancellation() }
                    }
                try {
                    withTimeout(3_000) { started.await() }
                    gate.pollingSucceeded()
                    worker.processOnce()
                    assertEquals("NEW", status())
                    coVerify(exactly = 0) { client.dispatchOutbox(any(), any(), any()) }
                    decisions.resolve("open").writeText(ticket)
                    worker.processOnce()
                    assertEquals("SENT", status())
                    coVerify(exactly = 1) { client.dispatchOutbox(42L, "sendMessage", any()) }
                } finally {
                    poller.cancelAndJoin()
                }
            }
        }

    @Test
    fun `confirmed update cannot be claimed again by a restarted router`() =
        runBlocking {
            val db = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(db).use { assertTrue(IdempotencyRepository(it).tryAcquire(42, 9, 3)) }
            PostgresTestEnv.createDataSource(db, false).use {
                assertFalse(IdempotencyRepository(it).tryAcquire(42, 9, 3))
            }
        }

    @Test
    fun `failed database probe latches the instance until a fresh explicit admission`() =
        runBlocking {
            val gate = ProductionTrafficGate(decisions, "sha")
            val postgres = org.testcontainers.containers.PostgreSQLContainer("postgres:17-alpine")
            postgres.start()
            try {
                fun probe() =
                    java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
                        .use { it.isValid(2) }
                val ticket = "sha ${gate.instance}"
                decisions.resolve("activate").writeText(ticket)
                decisions.resolve("open").writeText(ticket)
                assertTrue(gate.checkDatabase(this, ::probe))
                assertTrue(gate.admitsHttp(false))
                val url = postgres.jdbcUrl
                postgres.stop()
                assertFalse(
                    gate.checkDatabase(this) {
                        java.sql.DriverManager.getConnection(
                            url,
                            postgres.username,
                            postgres.password,
                        ).use { it.isValid(2) }
                    },
                )
                assertFalse(gate.admitsHttp(false))
                postgres.start()
                assertTrue(gate.checkDatabase(this, ::probe))
                assertFalse(gate.admitsHttp(false))
                val replacement = ProductionTrafficGate(decisions, "sha")
                assertFalse(replacement.admitsHttp(false))
                assertTrue(replacement.checkDatabase(this, ::probe))
                val renewed = "sha ${replacement.instance}"
                decisions.resolve("activate").writeText(renewed)
                decisions.resolve("open").writeText(renewed)
                assertTrue(replacement.admitsHttp(false))
            } finally {
                postgres.stop()
            }
        }

    @Test
    fun `only a successful getUpdates from this worker admits and extended failure revokes`() =
        runBlocking {
            val clock = AtomicLong()
            val gate = ProductionTrafficGate(decisions, "sha", 100, clock::get)
            val available = AtomicBoolean(false)
            val attempts = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { call ->
                val response =
                    if (call.requestURI.path.endsWith("/getWebhookInfo")) {
                        """{"ok":true,"result":{"url":"","has_custom_certificate":false,"pending_update_count":0}}"""
                    } else {
                        attempts.incrementAndGet()
                        if (available.get()) """{"ok":true,"result":[]}""" else """{"ok":false,"error_code":503}"""
                    }
                val bytes = response.toByteArray()
                call.responseHeaders.add("Content-Type", "application/json")
                call.sendResponseHeaders(200, bytes.size.toLong())
                call.responseBody.use { it.write(bytes) }
            }
            server.start()
            val codec = Json { ignoreUnknownKeys = true }
            val http = HttpClient(Java) { install(ContentNegotiation) { json(codec) } }
            http.plugin(HttpSend).intercept { request ->
                request.url.protocol = URLProtocol.HTTP
                request.url.host = "127.0.0.1"
                request.url.port = server.address.port
                execute(request)
            }
            val policy = TelegramTrafficPolicy.product()
            val client = TelegramApiClient("fixture", http, codec, policy)
            val worker =
                TelegramLongPollingWorker(
                    getUpdates = client::getUpdates,
                    getWebhookUrl = { client.getWebhookInfo().url },
                    processUpdate = {},
                    trafficPolicy = policy,
                    timeoutSeconds = 1,
                    scope = this,
                    onPollingSuccess = gate::pollingSucceeded,
                )
            val supervisor = gate.supervise(this, polling = true, start = worker::start)
            try {
                val ticket = "sha ${gate.instance}"
                decisions.resolve("activate").writeText(ticket)
                withTimeout(12_000) { while (attempts.get() < 3) delay(20) }
                assertEquals("standby", gate.pollingState(true))
                assertFalse(gate.admitsHttp(true))
                available.set(true)
                withTimeout(12_000) { while (gate.pollingState(true) != "running") delay(20) }
                decisions.resolve("open").writeText(ticket)
                assertTrue(gate.admitsHttp(true))
                available.set(false)
                val before = attempts.get()
                withTimeout(12_000) { while (attempts.get() < before + 2) delay(20) }
                clock.set(101)
                assertFalse(gate.admitsHttp(true))
                assertEquals("revoked", gate.pollingState(true))
            } finally {
                supervisor.cancelAndJoin()
                http.close()
                server.stop(0)
            }
        }

    @Test
    fun `one final process owns polling and public admission`() =
        runBlocking {
            val gate = ProductionTrafficGate(decisions, "revision")
            val ticket = "revision ${gate.instance}"
            val activation = decisions.resolve("activate")
            val open = decisions.resolve("open")
            var starts = 0
            var concurrent = 0
            val supervisor =
                gate.supervise(this, polling = true) {
                    starts++
                    launch {
                        concurrent++
                        try {
                            awaitCancellation()
                        } finally {
                            concurrent--
                        }
                    }
                }
            try {
                open.writeText("revision old-instance")
                activation.writeText("revision old-instance")
                assertFalse(gate.admitsHttp(true))
                activation.writeText(ticket)
                withTimeout(3000) { while (concurrent == 0) delay(10) }
                assertFalse(gate.admitsHttp(true))
                assertEquals("standby", gate.pollingState(true))
                gate.pollingSucceeded()
                open.writeText(ticket)
                assertTrue(gate.admitsHttp(true))
                assertFalse(ProductionTrafficGate(decisions, "revision").admitsHttp(true))
                delay(250)
                assertEquals(1, starts)
                assertEquals(1, concurrent)
                activation.deleteExisting()
                assertFalse(gate.admitsHttp(true))
                withTimeout(3000) { while (concurrent != 0) delay(10) }
            } finally {
                supervisor.cancelAndJoin()
            }
        }
}
