package com.hookah.platform.backend

import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
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
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
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
                val stopped = CompletableDeferred<Unit>()
                val writers =
                    gate.supervise(this, botEnabled = false) {
                        launch {
                            writer.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                stopped.complete(Unit)
                            }
                        }
                    }
                assertNull(withTimeoutOrNull(250) { writer.await() })
                decisions.resolve("open").deleteExisting()
                decisions.resolve("recovery-hold").deleteExisting()
                decisions.resolve("recovery-accepted").writeText("reviewed")
                assertNull(withTimeoutOrNull(250) { writer.await() })
                decisions.resolve("open").writeText(ticket)
                withTimeout(3_000) { writer.await() }
                decisions.resolve("recovery-hold").writeText("incident")
                withTimeout(3_000) { stopped.await() }
                writers.cancelAndJoin()
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
                suspend fun readiness() = api.recoveryPollingState()
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
                coEvery { client.dispatchOutbox(42L, "sendMessage", any()) } returnsMany
                    listOf(TelegramCallResult.Failure(503, "Unavailable", null), TelegramCallResult.Success(JsonNull))
                var now = java.time.Instant.now()
                val pauseBeforeSend = AtomicBoolean(false)
                val worker =
                    TelegramOutboxWorker(
                        repository = repo,
                        apiClientProvider = { client },
                        json = Json,
                        rateLimiter =
                            TelegramRateLimiter {
                                if (pauseBeforeSend.get()) {
                                    gate.pollingFailed(TelegramLongPollingWorker.PollingFailure.NETWORK)
                                }
                            },
                        config = TelegramOutboxConfig(maxAttempts = 2),
                        nowProvider = { now },
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
                    pauseBeforeSend.set(true)
                    repeat(4) {
                        gate.pollingSucceeded()
                        worker.processOnce()
                        assertEquals("NEW", status())
                    }
                    ds.connection.use { c ->
                        c.createStatement().use { statement ->
                            statement.executeQuery("SELECT attempts FROM telegram_outbox WHERE chat_id=42").use {
                                assertTrue(it.next())
                                assertEquals(0, it.getInt(1))
                            }
                        }
                    }
                    coVerify(exactly = 0) { client.dispatchOutbox(any(), any(), any()) }
                    pauseBeforeSend.set(false)
                    gate.pollingSucceeded()
                    worker.processOnce()
                    assertEquals("NEW", status())
                    coVerify(exactly = 0) { client.dispatchOutbox(any(), any(), any()) }
                    decisions.resolve("open").writeText(ticket)
                    assertTrue(gate.admitsHttp(true))
                    gate.pollingFailed(TelegramLongPollingWorker.PollingFailure.NETWORK)
                    assertTrue(gate.admitsHttp(true))
                    worker.processOnce()
                    assertEquals("NEW", status())
                    coVerify(exactly = 0) { client.dispatchOutbox(any(), any(), any()) }
                    pauseBeforeSend.set(true)
                    repeat(4) {
                        gate.pollingSucceeded()
                        worker.processOnce()
                        assertEquals("NEW", status())
                    }
                    ds.connection.use { c ->
                        c.createStatement().use { statement ->
                            statement.executeQuery("SELECT attempts FROM telegram_outbox WHERE chat_id=42").use {
                                assertTrue(it.next())
                                assertEquals(0, it.getInt(1))
                            }
                        }
                    }
                    coVerify(exactly = 0) { client.dispatchOutbox(any(), any(), any()) }
                    pauseBeforeSend.set(false)
                    gate.pollingSucceeded()
                    worker.processOnce()
                    assertEquals("NEW", status())
                    now = now.plusSeconds(90)
                    worker.processOnce()
                    assertEquals("SENT", status())
                    coVerify(exactly = 2) { client.dispatchOutbox(42L, "sendMessage", any()) }
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
    fun `admitted HTTP survives extended Telegram outage and the same poller resumes its pending update`() =
        runBlocking {
            val clock = AtomicLong()
            val gate = ProductionTrafficGate(decisions, "sha", 100, clock::get)
            val available = AtomicBoolean(false)
            val attempts = AtomicInteger()
            val shortProbesDuringOutage = AtomicInteger()
            val processed = mutableListOf<Long>()
            val firstAck = CompletableDeferred<Unit>()
            val delivered = CompletableDeferred<Unit>()

            fun pending(id: Long) =
                """{"ok":true,"result":[{"update_id":$id,"message":{"message_id":$id,
                    "chat":{"id":11,"type":"private"},"from":{"id":11},"text":"/start"}}]}"""
            val webhookAvailable = AtomicBoolean(false)
            val webhookTried = CompletableDeferred<Unit>()
            val recovered = AtomicBoolean(false)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { call ->
                val response =
                    if (call.requestURI.path.endsWith("/getWebhookInfo")) {
                        webhookTried.complete(Unit)
                        if (webhookAvailable.get()) {
                            """{"ok":true,"result":{"url":"","pending_update_count":0}}"""
                        } else {
                            """{"ok":false,"error_code":503}"""
                        }
                    } else {
                        attempts.incrementAndGet()
                        val probe = call.requestURI.query?.contains("timeout=0") == true
                        when {
                            !available.get() && firstAck.isCompleted && probe -> {
                                shortProbesDuringOutage.incrementAndGet()
                                pending(42)
                            }
                            !available.get() -> """{"ok":false,"error_code":503}"""
                            recovered.get() && call.requestURI.query?.contains("offset=43") == true -> {
                                delivered.complete(Unit)
                                """{"ok":true,"result":[]}"""
                            }
                            call.requestURI.query?.contains("offset=42") == true -> {
                                firstAck.complete(Unit)
                                if (recovered.get()) pending(42) else """{"ok":true,"result":[]}"""
                            }
                            // An offset-free probe sees the oldest unacknowledged update.
                            recovered.get() -> pending(42)
                            else -> pending(41)
                        }
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
                    processUpdate = { processed.add(it.updateId) },
                    canLongPoll = { gate.admitsHttp(true) },
                    canDispatch = gate::telegramDispatchAllowed,
                    onProductionFailure = gate::pollingFailed,
                    errorDelayMillis = 20,
                    trafficPolicy = policy,
                    timeoutSeconds = 1,
                    scope = this,
                    onPollingSuccess = gate::pollingSucceeded,
                )
            val supervisor = gate.supervise(this, polling = true, start = worker::start)
            try {
                val ticket = "sha ${gate.instance}"
                decisions.resolve("activate").writeText(ticket)
                withTimeout(3_000) { webhookTried.await() }
                assertEquals(0, attempts.get())
                webhookAvailable.set(true)
                withTimeout(12_000) { while (attempts.get() < 3) delay(20) }
                assertEquals("degraded", gate.pollingState(true))
                assertFalse(gate.admitsHttp(true))
                available.set(true)
                withTimeout(12_000) { while (gate.pollingState(true) != "running") delay(20) }
                decisions.resolve("open").writeText(ticket)
                assertTrue(gate.admitsHttp(true))
                withTimeout(5_000) { firstAck.await() }
                assertEquals(listOf(41L), processed)
                available.set(false)
                val before = attempts.get()
                withTimeout(12_000) { while (attempts.get() < before + 2) delay(20) }
                clock.set(101)
                assertTrue(gate.admitsHttp(true))
                assertEquals("degraded", gate.pollingState(true))
                assertFalse(gate.telegramDispatchAllowed())
                recovered.set(true)
                available.set(true)
                withTimeout(8_000) { delivered.await() }
                assertEquals(listOf(41L, 42L), processed)
                assertEquals(0, shortProbesDuringOutage.get())
                assertEquals("running", gate.pollingState(true))
                assertTrue(gate.admitsHttp(true))
                decisions.resolve("open").deleteExisting()
                assertFalse(gate.admitsHttp(true))
                assertFalse(gate.telegramDispatchAllowed())
            } finally {
                supervisor.cancelAndJoin()
                http.close()
                server.stop(0)
            }
        }

    @Test
    fun `production ingress serves authenticated Mini App during outage and drains outbox after recovery`() =
        testApplication {
            val db = PostgresTestEnv.createDatabase()
            val config =
                PostgresTestEnv.buildConfig(db).apply {
                    put("app.env", "prod")
                    put("app.version", "fixture")
                    put("miniapp.staticDir", decisions.resolve("assets").toString())
                    put("telegram.enabled", "true")
                    put("telegram.token", "fixture")
                    put("telegram.trafficPolicy", "PRODUCT")
                    put("telegram.staffChatLinkSecretPepper", "fixture-pepper")
                    put("venue.staffInviteSecretPepper", "fixture-pepper")
                    put("billing.webhookSecret", "fixture-secret")
                    put("billing.subscription.intervalSeconds", "0")
                    put("telegram.outbox.pollIntervalMillis", "50")
                }
            java.nio.file.Files.createDirectory(decisions.resolve("assets"))
            decisions.resolve("assets/index.html").writeText("<html>fixture</html>")
            environment { this.config = config }
            val clock = AtomicLong()
            val gate = ProductionTrafficGate(decisions, "fixture", 40_000_000_000L, clock::get)
            val outage = AtomicBoolean(false)
            val failedPolls = AtomicInteger()
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            val sends = AtomicInteger()
            val firstSend = CompletableDeferred<Unit>()
            val finishFirstSend = CompletableDeferred<Unit>()
            application {
                moduleWithOverrides(
                    ModuleOverrides(
                        productionTrafficGate = gate,
                        telegramCommandMenuConfigurator = {},
                        telegramHttpClientFactory = { codec ->
                            HttpClient(
                                MockEngine { request ->
                                    val result =
                                        when (request.url.encodedPath.substringAfterLast('/')) {
                                            "getWebhookInfo" -> """{"url":""}"""
                                            "getUpdates" -> {
                                                if (outage.get()) {
                                                    if (failedPolls.incrementAndGet() % 2 == 1) {
                                                        throw java.net.SocketTimeoutException()
                                                    }
                                                    return@MockEngine respond(
                                                        """{"ok":true,"result":[""",
                                                        headers = jsonHeaders,
                                                    )
                                                }
                                                delay(10)
                                                "[]"
                                            }
                                            "sendMessage" -> {
                                                if (sends.incrementAndGet() == 1) {
                                                    firstSend.complete(Unit)
                                                    finishFirstSend.await()
                                                }
                                                """{"message_id":5}"""
                                            }
                                            else -> error("unexpected Telegram operation")
                                        }
                                    respond(
                                        """{"ok":true,"result":$result}""",
                                        headers = jsonHeaders,
                                    )
                                },
                            ) { install(ContentNegotiation) { json(codec) } }
                        },
                    ),
                )
            }
            startApplication()

            suspend fun ingress(
                path: String,
                token: String? = null,
            ) = client.get(path) {
                header("X-Hookah-Pilot-Ingress", "public")
                token?.let { bearerAuth(it) }
            }
            val ticket = "fixture ${gate.instance}"
            decisions.resolve("activate").writeText(ticket)
            withTimeout(5_000) { while (gate.pollingState(true) != "running") delay(10) }
            assertEquals(HttpStatusCode.ServiceUnavailable, ingress("/miniapp/").status)
            decisions.resolve("open").writeText(ticket)
            assertEquals(HttpStatusCode.OK, ingress("/health").status)
            val token = SessionTokenService(SessionTokenConfig.from(config, "prod")).issueToken(42).token
            PostgresTestEnv.createDataSource(db, false).use { ds ->
                ds.connection.use { connection ->
                    connection.createStatement().use { stmt ->
                        stmt.executeUpdate("INSERT INTO users (telegram_user_id) VALUES (42) ON CONFLICT DO NOTHING")
                    }
                }
                val policy = TelegramTrafficPolicy.product()
                val queue = TelegramOutboxEnqueuer(TelegramOutboxRepository(ds, policy), Json, policy)
                queue.enqueueSendMessage(42, "in flight", dedupeKey = "in-flight-notice")
                withTimeout(8_000) { firstSend.await() }
                outage.set(true)
                withTimeout(5_000) { while (gate.pollingState(true) != "degraded") delay(10) }
                clock.set(61_000_000_000L)
                assertEquals("<html>fixture</html>", ingress("/miniapp/").bodyAsText())
                assertEquals(HttpStatusCode.Unauthorized, ingress("/api/guest/catalog").status)
                assertEquals(HttpStatusCode.OK, ingress("/api/guest/catalog", token).status)
                assertTrue(client.get("/metrics").bodyAsText().contains("production_telegram_polling_degraded 1.0"))
                withTimeout(5_000) { while (failedPolls.get() < 2) delay(20) }
                finishFirstSend.complete(Unit)
                repeat(2) { queue.enqueueSendMessage(42, "fixture", dedupeKey = "one-notice") }

                fun queueState(): List<String> =
                    ds.connection.use { c ->
                        c.createStatement().use { query ->
                            val sql = "SELECT status FROM telegram_outbox WHERE chat_id=42 ORDER BY id"
                            query.executeQuery(sql).use { rows ->
                                buildList { while (rows.next()) add(rows.getString(1)) }
                            }
                        }
                    }
                withTimeout(5_000) { while (queueState() != listOf("SENT", "NEW")) delay(20) }
                assertEquals(1, sends.get())
                outage.set(false)
                withTimeout(10_000) { while (queueState() != listOf("SENT", "SENT")) delay(20) }
                assertEquals(2, sends.get())
            }
            assertEquals("running", gate.pollingState(true))
            assertTrue(client.get("/metrics").bodyAsText().contains("production_telegram_polling_degraded 0.0"))
            outage.set(true)
            withTimeout(5_000) { while (gate.pollingState(true) != "degraded") delay(10) }
            decisions.resolve("open").deleteExisting()
            outage.set(false)
            withTimeout(5_000) { while (gate.pollingState(true) != "running") delay(10) }
            assertEquals(HttpStatusCode.ServiceUnavailable, ingress("/api/guest/catalog", token).status)
        }

    @Test
    fun `Telegram authorization denial and poller conflict revoke the admitted JVM without retry`() =
        runBlocking {
            for (code in listOf(401, 403, 409)) {
                val gate = ProductionTrafficGate(decisions, "sha")
                val reject = AtomicBoolean(false)
                val failures = AtomicInteger()
                val codec = Json { ignoreUnknownKeys = true }
                val http =
                    HttpClient(
                        MockEngine {
                            if (reject.get()) {
                                failures.incrementAndGet()
                                if (code == 403) {
                                    respond(
                                        """{"ok":false,"error_code":403}""",
                                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                } else {
                                    respond("denied", status = HttpStatusCode.fromValue(code))
                                }
                            } else {
                                delay(10)
                                respond(
                                    """{"ok":true,"result":[]}""",
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }
                        },
                    ) { install(ContentNegotiation) { json(codec) } }
                val api = TelegramApiClient("fixture", http, codec, TelegramTrafficPolicy.product())
                val worker =
                    TelegramLongPollingWorker(
                        getWebhookUrl = { "" },
                        getUpdates = api::getUpdates,
                        processUpdate = { error("unexpected update") },
                        trafficPolicy = TelegramTrafficPolicy.product(),
                        scope = this,
                        timeoutSeconds = 1,
                        onPollingSuccess = gate::pollingSucceeded,
                        canDispatch = gate::telegramDispatchAllowed,
                        onProductionFailure = gate::pollingFailed,
                    )
                val job = gate.supervise(this, polling = true, start = worker::start)
                try {
                    val ticket = "sha ${gate.instance}"
                    decisions.resolve("activate").writeText(ticket)
                    withTimeout(5_000) { while (gate.pollingState(true) != "running") delay(10) }
                    decisions.resolve("open").writeText(ticket)
                    assertTrue(gate.admitsHttp(true))
                    reject.set(true)
                    withTimeout(5_000) { while (gate.pollingState(true) != "revoked") delay(10) }
                    reject.set(false)
                    gate.pollingSucceeded()
                    assertFalse(gate.admitsHttp(true))
                    assertFalse(gate.telegramDispatchAllowed())
                    assertEquals(1, failures.get())
                } finally {
                    job.cancelAndJoin()
                    http.close()
                }
            }
        }

    @Test
    fun `one final process owns polling and public admission`() =
        runBlocking {
            val clock = AtomicLong()
            val gate = ProductionTrafficGate(decisions, "revision", 100, clock::get)
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
                clock.set(101)
                open.writeText(ticket)
                assertFalse(gate.admitsHttp(true))
                gate.pollingSucceeded()
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
