package com.hookah.platform.backend.miniapp.venue.accounting

import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.PostgresTestDatabase
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Loopback-only native server, launched in separate JVM lifetimes against one disposable database. */
object AccountingCreateReplayServer {
    @JvmStatic fun main(args: Array<String>) {
        check(System.getenv("HT_CREATE_REPLAY_PROBE") == "1")
        val db =
            PostgresTestDatabase(
                "",
                System.getenv("HT_CREATE_JDBC"),
                System.getenv("HT_CREATE_USER"),
                System.getenv("HT_CREATE_PASSWORD"),
            )
        val config =
            PostgresTestEnv.buildConfig(db).apply {
                put("billing.subscriptionJob.enabled", "false")
                put("cors.allowedHosts", "http://127.0.0.1:${args[2]}")
            }
        val server =
            embeddedServer(Netty, environment = applicationEnvironment { this.config = config }, configure = {
                connectors =
                    mutableListOf(
                        EngineConnectorBuilder().apply {
                            host = "127.0.0.1"
                            port = args[1].toInt()
                        },
                    )
                connectionGroupSize = 1
                workerGroupSize = 2
                callGroupSize = 2
            }, module = { module() }).start(false)
        Runtime.getRuntime().addShutdownHook(Thread { server.stop(100, 1500) })
        val port = runBlocking { server.engine.resolvedConnectors().single().port }
        Files.writeString(
            Path.of(args[0]),
            buildJsonObject {
                put("base", "http://127.0.0.1:$port")
                put("token", SessionTokenService(SessionTokenConfig.from(config, "test")).issueToken(101).token)
                put("managerToken", SessionTokenService(SessionTokenConfig.from(config, "test")).issueToken(102).token)
            }.toString(),
        )
        Thread.currentThread().join()
    }
}

class AccountingCreateReplayPostgresHttpTest {
    @Test fun `CREATE across separate JVMs pays original and preserves identical concurrent sale`() =
        runBlocking<Unit> {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { ds ->
                seedAccounting(ds)
                val live = System.getenv("ACCOUNTING_CREATE_LIVE_DIR")?.let { Path.of(it) }
                val dir = live ?: Files.createTempDirectory(Path.of("build"), "create-replay-")
                if (live != null) Files.createDirectory(dir)
                val ready = dir.resolve("ready.json")
                val loaderPaths =
                    generateSequence(javaClass.classLoader) { it.parent }
                        .filterIsInstance<java.net.URLClassLoader>().flatMap {
                            it.urLs.asSequence()
                        }.map { Path.of(it.toURI()).toString() }.toList()
                val classpath =
                    (loaderPaths + System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                        .distinct().joinToString(
                            java.io.File.pathSeparator,
                        )

                fun start(
                    port: Int,
                    label: String,
                ): Process {
                    val builder =
                        ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-Xmx384m",
                            "-cp",
                            classpath,
                            AccountingCreateReplayServer::class.java.name,
                            ready.toString(),
                            port.toString(),
                            System.getenv("MINIAPP_E2E_PORT") ?: "5183",
                        )
                            .redirectErrorStream(true).redirectOutput(dir.resolve("server-$label.log").toFile())
                    builder.environment().apply {
                        remove("_JAVA_OPTIONS")
                        remove("JAVA_TOOL_OPTIONS")
                        remove("JDK_JAVA_OPTIONS")
                        put("HT_CREATE_REPLAY_PROBE", "1")
                        put("HT_CREATE_JDBC", database.jdbcUrl)
                        put("HT_CREATE_USER", database.user)
                        put("HT_CREATE_PASSWORD", database.password)
                    }
                    return builder.start()
                }

                fun await(
                    name: String,
                    process: Process,
                ) {
                    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(180)
                    while (!Files.exists(dir.resolve(name)) && System.nanoTime() < end) {
                        assertTrue(process.isAlive, "Child exited before $name; see $dir")
                        Thread.sleep(40)
                    }
                    assertTrue(Files.exists(dir.resolve(name)), "Missing $name; see $dir")
                }

                fun stop(process: Process) {
                    process.destroy()
                    if (!process.waitFor(15, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                    }
                    assertFalse(process.isAlive)
                }
                var server = start(0, "first")
                val firstPid = server.pid()
                try {
                    await("ready.json", server)
                    val session = Json.parseToJsonElement(Files.readString(ready)).jsonObject
                    val base = session.getValue("base").jsonPrimitive.content
                    val token = session.getValue("token").jsonPrimitive.content
                    val manager = session.getValue("managerToken").jsonPrimitive.content
                    val client = HttpClient.newHttpClient()

                    fun request(
                        path: String,
                        body: AccountingCommand? = null,
                        actorToken: String = token,
                        status: Int = 200,
                    ): String {
                        val builder =
                            HttpRequest.newBuilder(
                                URI.create(base + path),
                            ).header("Authorization", "Bearer $actorToken")
                        if (body != null) {
                            builder.header(
                                "Content-Type",
                                "application/json",
                            ).POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(body)))
                        }
                        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                        assertEquals(status, response.statusCode(), response.body())
                        return response.body()
                    }
                    val path = "/api/venue/1/accounting/commands"
                    val create =
                        splitManual().copy(
                            lines = listOf(AccountingLineInput(name = "Чай", unitPriceMinor = 99000)),
                        )
                    var initial: AccountingReport? = null
                    if (live == null) {
                        initial = Json.decodeFromString(request(path, create))
                        assertEquals(
                            initial!!.createdSaleId,
                            Json.decodeFromString<AccountingState>(
                                request("/api/venue/1/accounting"),
                            ).current!!.sales.single().id,
                        )
                    } else {
                        await("reloaded", server)
                    }
                    val before = AccountingRepository(ds).state(1, 101).current!!
                    val originalId = before.sales.single().id
                    // Identical values cannot identify the manager's independent sale.
                    val second =
                        Json.decodeFromString<AccountingReport>(
                            request(
                                path,
                                splitManual().copy(
                                    shiftId = before.shiftId,
                                    lines =
                                        before.sales.single().lines.map {
                                            AccountingLineInput(
                                                name = it.name,
                                                qty = it.qty,
                                                unitPriceMinor = it.unitPriceMinor,
                                            )
                                        },
                                ),
                                manager,
                            ),
                        )
                    val otherId = second.createdSaleId!!
                    assertNotEquals(originalId, otherId)
                    stop(server)
                    val firstExit = server.exitValue()
                    Files.delete(ready)
                    server = start(URI.create(base).port, "second")
                    await("ready.json", server)
                    assertNotEquals(firstPid, server.pid())
                    Files.writeString(
                        dir.resolve("restarted"),
                        buildJsonObject {
                            put("firstPid", firstPid)
                            put("firstExit", firstExit)
                            put("secondPid", server.pid())
                            put("originalSaleId", originalId)
                            put("otherSaleId", otherId)
                            put("sameDatabase", true)
                        }.toString(),
                    )
                    if (live == null) {
                        val replay = Json.decodeFromString<AccountingReport>(request(path, create))
                        assertEquals(initial!!.createdSaleId, replay.createdSaleId)
                        request(path, create.copy(reason = "changed"), status = 409)
                        request(path, create, actorToken = manager, status = 409)
                        request("/api/venue/2/accounting/commands", create, status = 403)
                        val original = replay.sales.single { it.id == originalId }
                        request(
                            path,
                            command("PAY", replay.shiftId, original).copy(paymentMethod = "CASH", amountMinor = 99000),
                        )
                    } else {
                        await("done", server)
                    }
                    val final = AccountingRepository(ds).state(1, 101).current!!
                    assertEquals(if (live == null) 2 else 3, final.totals.salesCount)
                    assertEquals(if (live == null) 198000 else 388500, final.totals.accruedMinor)
                    assertEquals(99000, final.totals.paidMinor)
                    assertEquals(99000, final.sales.single { it.id == originalId }.paid)
                    assertEquals(0, final.sales.single { it.id == otherId }.paid)
                    ds.connection.use { c ->
                        assertEquals(
                            if (live == null) 2 else 3,
                            c.count("venue_accounting_commands", "created_sale_id IS NOT NULL"),
                        )
                        assertEquals(
                            if (live == null) 2 else 3,
                            c.count("audit_log", "action='VENUE_ACCOUNTING_CREATE'"),
                        )
                        assertEquals(1, c.count("audit_log", "action='VENUE_ACCOUNTING_PAY'"))
                        assertEquals(0, c.count("orders"))
                        assertEquals(0, c.count("telegram_outbox"))
                    }

                    fun business() =
                        ds.connection.use { c ->
                            listOf(
                                "venue_accounting_shifts",
                                "venue_accounting_sales",
                                "venue_accounting_events",
                                "venue_accounting_commands",
                                "audit_log",
                            ).associateWith {
                                c.count(it)
                            }
                        }
                    val counts = business()
                    val day = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Moscow"))
                    val stats =
                        request("/api/venue/1/stats/v1?section=finance&from=${day.minusDays(7)}&to=${day.plusDays(1)}")
                    assertTrue(stats.contains("99000"))
                    assertEquals(counts, business())
                    Files.writeString(
                        dir.resolve("verified.json"),
                        buildJsonObject {
                            put("status", "PASS")
                            put("originalSaleId", originalId)
                            put("paidSaleId", originalId)
                            put("otherSaleId", otherId)
                            put("otherPaidMinor", 0)
                            put("actualJvmRestart", true)
                            put("getBusinessWrites", 0)
                        }.toString(),
                    )
                } finally {
                    stop(server)
                    Files.deleteIfExists(ready)
                }
            }
        }
}
