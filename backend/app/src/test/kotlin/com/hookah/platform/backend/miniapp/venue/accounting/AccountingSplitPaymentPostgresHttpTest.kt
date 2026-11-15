package com.hookah.platform.backend.miniapp.venue.accounting

import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountingSplitPaymentPostgresHttpTest {
    @Test fun `payment UI HTTP PostgreSQL restart replay list and KPI share authoritative totals`() {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database).use { ds ->
            seedAccounting(ds)
            val config =
                PostgresTestEnv.buildConfig(database).apply {
                    put("billing.subscriptionJob.enabled", "false")
                    put("cors.allowedHosts", "http://127.0.0.1:${System.getenv("MINIAPP_E2E_PORT") ?: "5179"}")
                }

            var listenPort = 0

            fun start() =
                embeddedServer(Netty, environment = applicationEnvironment { this.config = config }, configure = {
                    connectors =
                        mutableListOf(
                            EngineConnectorBuilder().apply {
                                host = "127.0.0.1"
                                port = listenPort
                            },
                        )
                    connectionGroupSize = 1
                    workerGroupSize = 2
                    callGroupSize = 2
                }, module = { module() }).start(false)
            var server = start()
            try {
                listenPort = runBlocking { server.engine.resolvedConnectors().single().port }
                val base = "http://127.0.0.1:$listenPort"
                val token = SessionTokenService(SessionTokenConfig.from(config, "test")).issueToken(101).token
                val http = HttpClient.newHttpClient()

                fun request(
                    path: String,
                    body: AccountingCommand? = null,
                    expected: Int = 200,
                ): String {
                    val req = HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer $token")
                    if (body != null) {
                        req.header(
                            "Content-Type",
                            "application/json",
                        ).POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(body)))
                    }
                    val result = http.send(req.build(), HttpResponse.BodyHandlers.ofString())
                    assertEquals(expected, result.statusCode(), result.body())
                    return result.body()
                }

                fun restart() {
                    server.stop(100, 1500)
                    server = start()
                    assertEquals(listenPort, runBlocking { server.engine.resolvedConnectors().single().port })
                }
                val liveDir = System.getenv("ACCOUNTING_PAYMENT_LIVE_DIR")?.let { Path.of(it) }
                if (liveDir != null) {
                    Files.createDirectory(liveDir)

                    fun ready() =
                        Files.writeString(
                            liveDir.resolve("ready.json"),
                            buildJsonObject {
                                put("base", base)
                                put("token", token)
                            }.toString(),
                        )
                    val origin = "http://127.0.0.1:${System.getenv("MINIAPP_E2E_PORT") ?: "5179"}"

                    fun probe(
                        method: String,
                        source: String,
                    ): HttpResponse<String> {
                        val req =
                            HttpRequest.newBuilder(URI.create(base + "/api/venue/1/accounting/commands"))
                                .header("Origin", source)
                        if (method == "OPTIONS") {
                            req.header("Access-Control-Request-Method", "POST")
                                .header("Access-Control-Request-Headers", "authorization,content-type")
                                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        } else {
                            req.header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(splitManual())))
                        }
                        return http.send(req.build(), HttpResponse.BodyHandlers.ofString())
                    }
                    val allowed = probe("OPTIONS", origin)
                    val deniedOptions = probe("OPTIONS", "http://127.0.0.1:5180")
                    val deniedPost = probe("POST", "http://127.0.0.1:5180")
                    assertEquals(200, allowed.statusCode())
                    assertEquals(origin, allowed.headers().firstValue("access-control-allow-origin").orElse(null))
                    assertEquals(403, deniedOptions.statusCode())
                    assertEquals(403, deniedPost.statusCode())
                    assertEquals("", deniedPost.body())
                    assertEquals(0, ds.connection.use { it.count("venue_accounting_sales") })
                    Files.writeString(
                        liveDir.resolve("cors-probes.json"),
                        buildJsonObject {
                            put("allowedOrigin", origin)
                            put("allowedOptionsStatus", allowed.statusCode())
                            put("allowOrigin", allowed.headers().firstValue("access-control-allow-origin").orElse(""))
                            put("deniedOrigin", "http://127.0.0.1:5180")
                            put("deniedOptionsStatus", deniedOptions.statusCode())
                            put("deniedPostStatus", deniedPost.statusCode())
                            put("deniedPostBody", deniedPost.body())
                            put("salesAfterDeniedPost", 0)
                        }.toString(),
                    )
                    ready()
                    val end = System.nanoTime() + 240000000000L
                    while (!Files.exists(liveDir.resolve("restart")) && System.nanoTime() < end) Thread.sleep(40)
                    assertTrue(Files.exists(liveDir.resolve("restart")), "Browser must lose a committed split response")
                    restart()
                    ready()
                    Files.writeString(liveDir.resolve("restarted"), "READY")
                    while (!Files.exists(liveDir.resolve("done")) && System.nanoTime() < end) Thread.sleep(40)
                    assertTrue(
                        Files.exists(liveDir.resolve("done")),
                        "Browser must verify paid DOM list and current/closed KPI",
                    )
                    Files.deleteIfExists(liveDir.resolve("ready.json"))
                } else {
                    val first =
                        Json.decodeFromString<AccountingReport>(
                            request("/api/venue/1/accounting/commands", splitManual(190500)),
                        )
                    val second =
                        Json.decodeFromString<AccountingReport>(
                            request("/api/venue/1/accounting/commands", splitManual().copy(shiftId = first.shiftId)),
                        )
                    val cmd = splitCommand(second, second.sales.single { it.payable == 99000L })
                    val paid = request("/api/venue/1/accounting/commands", cmd)
                    restart()
                    assertEquals(paid, request("/api/venue/1/accounting/commands", cmd))
                    request("/api/venue/1/accounting/commands", cmd.copy(acknowledgePartial = true), 409)
                    val report = Json.decodeFromString<AccountingReport>(paid)
                    request(
                        "/api/venue/1/accounting/commands",
                        command(
                            "CLOSE",
                            report.shiftId,
                        ).copy(expectedReportHash = report.reportHash, acknowledgeUnpaid = true),
                    )
                }
                val repo = AccountingRepository(ds)
                val state = runBlocking { repo.state(1, 101) }
                val closed = runBlocking { repo.detail(1, 101, state.history.single().id) }
                assertEquals("CLOSED", closed.status)
                assertEquals(2, closed.totals.salesCount)
                assertEquals(289500, closed.totals.accruedMinor)
                assertEquals(99000, closed.totals.paidMinor)
                assertEquals(190500, closed.totals.unpaidMinor)
                assertEquals(listOf(40000L, 59000L), closed.sales.flatMap { it.payments }.map { it.amountMinor })

                fun business() =
                    ds.connection.use { c ->
                        listOf(
                            "venue_accounting_shifts",
                            "venue_accounting_sales",
                            "venue_accounting_events",
                            "venue_accounting_commands",
                            "audit_log",
                            "orders",
                            "order_batches",
                            "telegram_outbox",
                        ).associateWith {
                            c.count(it)
                        }
                    }
                val before = business()
                val day = LocalDate.now(java.time.ZoneId.of("Europe/Moscow"))
                val path = "/api/venue/1/stats/v1?section=finance&from=${day.minusDays(7)}&to=${day.plusDays(1)}"
                val stats = request(path)
                assertTrue(stats.contains("144750"))
                assertEquals(before, business())
                assertEquals(2, before.getValue("venue_accounting_sales"))
                assertEquals(1, ds.connection.use { it.count("audit_log", "action='VENUE_ACCOUNTING_PAY_SPLIT'") })
                assertEquals(0, before.getValue("orders"))
                assertEquals(0, before.getValue("telegram_outbox"))
                if (liveDir != null) {
                    Files.writeString(
                        liveDir.resolve("verified.json"),
                        buildJsonObject {
                            put("status", "PASS")
                            put("sales", 2)
                            put("accruedMinor", 289500)
                            put("paidMinor", 99000)
                            put("cashMinor", 40000)
                            put("cardMinor", 59000)
                            put("averageMinor", 144750)
                            put("getBusinessWrites", 0)
                            put("restartedReplay", true)
                        }.toString(),
                    )
                }
            } finally {
                server.stop(100, 1500)
            }
        }
    }
}
