package com.hookah.platform.backend

import com.hookah.platform.backend.telegram.TelegramApiClient
import com.hookah.platform.backend.telegram.TelegramLongPollingWorker
import com.hookah.platform.backend.telegram.TelegramTrafficPolicy
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.http.URLProtocol
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
import kotlin.test.assertTrue

class ProductionTrafficGateTest {
    @TempDir
    lateinit var decisions: Path

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
