package com.hookah.platform.backend.miniapp.venue.accounting

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.analytics.AnalyticsChannel
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.orders.OrderActionActor
import com.hookah.platform.backend.miniapp.venue.orders.OrderBatchStatus
import com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real channel handlers and PostgreSQL. The only provider boundary is an in-memory Telegram HTTP engine. */
class AccountingChatReconciliationPostgresHttpTest {
    @Test
    fun `completed CHAT webhook order has a guarded financial reconciliation and close path`() {
        val db = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(db).use { ds ->
            seedAccounting(ds)

            fun sql(text: String) = ds.connection.use { it.exec(text) }

            fun scalar(text: String) =
                ds.connection.use { c ->
                    c.createStatement().use { s ->
                        s.executeQuery(text).use {
                            it.next()
                            it.getString(1)
                        }
                    }
                }
            sql("UPDATE venue_settings SET timezone='UTC' WHERE venue_id=1")
            sql("UPDATE menu_items SET price_minor=150000 WHERE id=21")
            sql("INSERT INTO users(telegram_user_id) VALUES (302)")
            sql("INSERT INTO venue_tables(id,venue_id,table_number) OVERRIDING SYSTEM VALUE VALUES (13,1,2)")
            sql("INSERT INTO venue_subscriptions(venue_id,status,paid_start) VALUES (1,'ACTIVE',CURRENT_TIMESTAMP)")
            for ((session, table, tab, user) in listOf(listOf(41, 11, 51, 301), listOf(42, 13, 52, 302))) {
                sql("INSERT INTO table_tokens(table_id,token,is_active) VALUES ($table,'percent-$table',TRUE)")
                sql(
                    "INSERT INTO table_sessions(id,venue_id,table_id,started_at,last_activity_at,expires_at,status) " +
                        "OVERRIDING SYSTEM VALUE VALUES ($session,1,$table,CURRENT_TIMESTAMP," +
                        "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '10 day','ACTIVE')",
                )
                sql(
                    "INSERT INTO tab(id,venue_id,table_session_id,type,owner_user_id,status) " +
                        "OVERRIDING SYSTEM VALUE VALUES ($tab,1,$session,'PERSONAL',$user,'ACTIVE')",
                )
                sql("INSERT INTO tab_member(tab_id,user_id,role) VALUES ($tab,$user,'OWNER')")
            }
            val config =
                MapApplicationConfig(
                    "app.env" to "test", "api.session.jwtSecret" to "test-secret",
                    "cors.allowedHosts" to "http://127.0.0.1:5177",
                    "db.jdbcUrl" to db.jdbcUrl, "db.user" to db.user, "db.password" to db.password,
                    "db.maxPoolSize" to "6", "telegram.enabled" to "true", "telegram.token" to "test-token",
                    "telegram.mode" to "webhook", "telegram.webhookSecretToken" to "secret",
                    "telegram.staffChatLinkSecretPepper" to "pepper", "telegram.trafficPolicy" to "PRODUCT",
                    "billing.subscriptionJob.enabled" to "false",
                )
            val outbound = ConcurrentLinkedQueue<String>()

            fun startServer() =
                embeddedServer(Netty, environment = applicationEnvironment { this.config = config }, configure = {
                    connectors =
                        mutableListOf(
                            EngineConnectorBuilder().apply {
                                host = "127.0.0.1"
                                port = 0
                            },
                        )
                    connectionGroupSize = 1
                    workerGroupSize = 2
                    callGroupSize = 2
                }, module = {
                    moduleWithOverrides(
                        ModuleOverrides(
                            tableSessionCleanupEnabled = false,
                            telegramCommandMenuConfigurator = {},
                            telegramHttpClientFactory = {
                                    json ->
                                io.ktor.client.HttpClient(
                                    MockEngine { request ->
                                        check(
                                            request.url.host == "api.telegram.org" &&
                                                request.url.protocol.name == "https",
                                        )
                                        outbound.add(request.url.encodedPath.substringAfterLast('/'))
                                        val result =
                                            if (request.url.encodedPath.endsWith(
                                                    "answerCallbackQuery",
                                                )
                                            ) {
                                                "true"
                                            } else {
                                                """{"message_id":7001}"""
                                            }
                                        respond(
                                            """{"ok":true,"result":$result}""",
                                            HttpStatusCode.OK,
                                            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                                        )
                                    },
                                ) { install(ContentNegotiation) { json(json) } }
                            },
                        ),
                    )
                }).start(false)
            var server = startServer()
            try {
                var base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().single().port }}"
                val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))
                val http = HttpClient.newHttpClient()

                fun request(
                    path: String,
                    body: String? = null,
                    actor: Long? = 101,
                    expected: Int = 200,
                ): JsonObject {
                    val b = HttpRequest.newBuilder(URI.create(base + path))
                    if (actor != null) b.header("Authorization", "Bearer ${tokens.issueToken(actor).token}")
                    if (body != null) {
                        b.header(
                            "Content-Type",
                            "application/json",
                        ).POST(HttpRequest.BodyPublishers.ofString(body))
                    }
                    if (path == "/telegram/webhook") b.header("X-Telegram-Bot-Api-Secret-Token", "secret")
                    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
                    assertEquals(expected, r.statusCode(), "$path: ${r.body()}")
                    return if (r.body().isBlank()) {
                        JsonObject(
                            emptyMap(),
                        )
                    } else {
                        Json.parseToJsonElement(r.body()).jsonObject
                    }
                }
                var update = 5000

                fun webhook(
                    user: Int = 301,
                    text: String? = null,
                    data: String? = null,
                ) {
                    val id = ++update
                    val message =
                        buildJsonObject {
                            put("message_id", id)
                            put(
                                "chat",
                                buildJsonObject {
                                    put("id", user)
                                    put("type", "private")
                                },
                            )
                            put("from", buildJsonObject { put("id", user) })
                            put("text", text ?: "")
                        }
                    val payload =
                        if (data == null) {
                            "\"message\":$message"
                        } else {
                            val callback =
                                buildJsonObject {
                                    put("id", "percent-$id")
                                    put("from", buildJsonObject { put("id", user) })
                                    put("message", message)
                                    put("data", data)
                                }
                            "\"callback_query\":$callback"
                        }
                    request("/telegram/webhook", "{\"update_id\":$id,$payload}", null)
                    val end = System.nanoTime() + 15_000_000_000L
                    while (scalar("SELECT status FROM telegram_inbound_updates WHERE update_id=$id") != "PROCESSED" &&
                        System.nanoTime() < end
                        ) Thread.sleep(30)
                    assertEquals("PROCESSED", scalar("SELECT status FROM telegram_inbound_updates WHERE update_id=$id"))
                }
                webhook(text = "/start percent-11")
                webhook(text = "✍️ Быстрый заказ")
                webhook(text = "Заказ без товарной расшифровки")
                val dialog =
                    Json.parseToJsonElement(
                        scalar("SELECT payload FROM telegram_dialog_state WHERE chat_id=301"),
                    ).jsonObject
                webhook(data = "quick_order_confirm:${dialog["requestId"]!!.jsonPrimitive.content}")
                val order = scalar("SELECT id FROM orders").toLong()
                val batch = scalar("SELECT id FROM order_batches").toLong()
                val writer = VenueOrdersRepository(ds)
                val actor = OrderActionActor(101, VenueRole.OWNER, AnalyticsChannel.MINIAPP)
                runBlocking {
                    assertTrue(
                        writer.updateBatchStatus(
                            1,
                            batch,
                            OrderBatchStatus.NEW,
                            OrderBatchStatus.ACCEPTED,
                            actor,
                        )!!.applied,
                    )
                    assertTrue(
                        writer.updateBatchStatus(
                            1,
                            batch,
                            OrderBatchStatus.ACCEPTED,
                            OrderBatchStatus.DELIVERED,
                            actor,
                        )!!.applied,
                    )
                    assertTrue(writer.updateOrderStatus(1, order, OrderWorkflowStatus.CLOSED, actor)!!.applied)
                    assertEquals(false, writer.rejectOrder(1, order, "OTHER", "Too late", actor)!!.applied)
                }
                assertEquals("CLOSED", scalar("SELECT status FROM orders"))
                val initial = Json.decodeFromJsonElement<AccountingState>(request("/api/venue/1/accounting")).current!!
                val sale = initial.sales.single()
                assertEquals("order:$order", sale.id)
                assertTrue(sale.orderClosed)
                assertTrue(sale.lines.isEmpty())
                assertNotNull(sale.issue)
                assertEquals(initial.shiftId.toString(), scalar("SELECT accounting_shift_id FROM orders"))
                for (action in listOf("EDIT", "CANCEL", "DISPUTE", "RESOLVE")) {
                    request(
                        "/api/venue/1/accounting/commands",
                        Json.encodeToString(command(action, initial.shiftId, sale).copy(reason = "Reproduce P1")),
                        expected = 409,
                    )
                }
                request(
                    "/api/venue/1/accounting/commands",
                    Json.encodeToString(
                        command(
                            "CLOSE",
                            initial.shiftId,
                        ).copy(expectedReportHash = initial.reportHash, acknowledgeUnpaid = true),
                    ),
                    expected = 409,
                )
                assertEquals("0", scalar("SELECT COUNT(*) FROM venue_accounting_sales"))
                val evidence = Path.of("build/test-artifacts/accounting-chat-before.json")
                Files.createDirectories(evidence.parent)
                Files.writeString(
                    evidence,
                    buildJsonObject {
                        put("handler", "telegram/webhook QUICK_ORDER")
                        put("orderClosed", true)
                        put("issue", sale.issue)
                        put("editBlocked", true)
                        put("closeBlocked", true)
                        put("rejectApplied", false)
                    }.toString(),
                )
                val until = System.nanoTime() + 15000000000L
                while (System.nanoTime() < until &&
                    scalar("SELECT COUNT(*) FROM telegram_outbox WHERE status NOT IN ('SENT','FAILED')") != "0"
                ) {
                    Thread.sleep(50)
                }

                fun fingerprint(all: Boolean = true) =
                    ds.connection.use { c ->
                        val tables =
                            c.createStatement().use {
                                    st ->
                                st.executeQuery(
                                    "SELECT tablename FROM pg_tables " +
                                        "WHERE schemaname=current_schema() ORDER BY tablename",
                                ).use {
                                        q ->
                                    buildList { while (q.next()) add(q.getString(1)) }
                                }
                            }
                        tables.filter {
                            all || it in
                                listOf(
                                    "orders", "order_batches", "order_batch_items", "tab",
                                    "tab_member", "table_sessions", "telegram_outbox",
                                )
                        }.associateWith {
                                t ->
                            scalar(
                                "SELECT md5(COALESCE(string_agg(v,',' ORDER BY v),'')) " +
                                    "FROM (SELECT row_to_json(t)::text v FROM \"$t\" t)x",
                            )
                        }
                    }
                val readBefore = fingerprint()
                repeat(2) { request("/api/venue/1/accounting") }
                assertEquals(readBefore, fingerprint())
                val sourceBefore = fingerprint(false)
                val captures = linkedMapOf<String, JsonElement>("initial" to request("/api/venue/1/accounting"))
                val cmd = textCommand(initial, sale, 75000).copy(reason = "Подтверждено по документу")
                request("/api/venue/1/accounting/commands", Json.encodeToString(cmd), actor = 103, expected = 403)
                request("/api/venue/1/accounting/commands", Json.encodeToString(cmd), actor = 201, expected = 403)
                request(
                    "/api/venue/1/accounting/commands",
                    Json.encodeToString(cmd.copy(amountMinor = 0)),
                    expected = 400,
                )

                fun restart() {
                    server.stop(100, 1500)
                    server = startServer()
                    base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().single().port }}"
                }
                val liveDir = System.getenv("ACCOUNTING_CHAT_LIVE_DIR")?.let { Path.of(it) }
                if (liveDir != null) {
                    // A stale marker must never stand in for a new browser execution.
                    Files.createDirectory(liveDir)

                    fun ready() =
                        Files.writeString(
                            liveDir.resolve("ready.json"),
                            buildJsonObject {
                                put("base", base)
                                put("token", tokens.issueToken(101).token)
                            }.toString(),
                        )
                    ready()
                    val end = System.nanoTime() + 300000000000L
                    while (!Files.exists(liveDir.resolve("restart")) && System.nanoTime() < end) Thread.sleep(50)
                    assertTrue(Files.exists(liveDir.resolve("restart")))
                    restart()
                    ready()
                    Files.writeString(liveDir.resolve("restarted"), "READY")
                    while (!Files.exists(liveDir.resolve("done")) && System.nanoTime() < end) Thread.sleep(50)
                    assertTrue(Files.exists(liveDir.resolve("done")))
                    Files.deleteIfExists(liveDir.resolve("ready.json"))
                    captures["reconciled"] =
                        Json.parseToJsonElement(Files.readString(liveDir.resolve("reconciled.json")))
                } else {
                    val reconciled = request("/api/venue/1/accounting/commands", Json.encodeToString(cmd))
                    captures["reconciled"] =
                        buildJsonObject {
                            put("current", reconciled)
                            put("history", JsonArray(emptyList()))
                            put("nextHistoryBeforeId", JsonNull)
                        }
                    restart()
                    assertEquals(reconciled, request("/api/venue/1/accounting/commands", Json.encodeToString(cmd)))
                    request(
                        "/api/venue/1/accounting/commands",
                        Json.encodeToString(cmd.copy(amountMinor = 76000)),
                        expected = 409,
                    )
                    val current = Json.decodeFromJsonElement<AccountingReport>(reconciled)
                    request(
                        "/api/venue/1/accounting/commands",
                        Json.encodeToString(
                            command(
                                "CLOSE",
                                initial.shiftId,
                            ).copy(expectedReportHash = initial.reportHash, acknowledgeUnpaid = true),
                        ),
                        expected = 409,
                    )
                    request(
                        "/api/venue/1/accounting/commands",
                        Json.encodeToString(
                            command("CLOSE", initial.shiftId).copy(expectedReportHash = current.reportHash),
                        ),
                        expected = 409,
                    )
                    request(
                        "/api/venue/1/accounting/commands",
                        Json.encodeToString(
                            command(
                                "CLOSE",
                                initial.shiftId,
                            ).copy(expectedReportHash = current.reportHash, acknowledgeUnpaid = true),
                        ),
                    )
                }
                val closed =
                    Json.decodeFromJsonElement<AccountingReport>(
                        request("/api/venue/1/accounting/${initial.shiftId}"),
                    )
                assertEquals("CLOSED", closed.status)
                assertEquals(75000, closed.totals.accruedMinor)
                assertEquals(0, closed.totals.paidMinor)
                assertEquals(75000, closed.totals.unallocatedAccruedMinor)
                assertEquals("1", scalar("SELECT COUNT(*) FROM venue_accounting_sales"))
                assertEquals("0", scalar("SELECT COUNT(*) FROM venue_accounting_sales WHERE id LIKE 'manual:%'"))
                assertEquals(
                    "1",
                    scalar("SELECT COUNT(*) FROM audit_log WHERE action='VENUE_ACCOUNTING_RECONCILE_TEXT'"),
                )
                assertEquals(sourceBefore, fingerprint(false))
                captures["closed"] = request("/api/venue/1/accounting/${initial.shiftId}")
                val today = LocalDate.now(ZoneOffset.UTC)
                val prefix = "/api/venue/1/stats/v1?from=$today&to=${today.plusDays(1)}"
                val beforeGets = fingerprint()
                captures["finance"] = request("$prefix&section=finance")
                captures["products"] = request("$prefix&section=products")
                assertEquals(beforeGets, fingerprint())
                val finance =
                    captures.getValue(
                        "finance",
                    ).jsonObject["closedShiftFinances"]!!.jsonArray.single().jsonObject["metrics"]!!.jsonObject
                assertEquals("75000", finance["unallocatedAccrued"]!!.jsonObject["value"]!!.jsonPrimitive.content)
                assertEquals("PARTIAL", finance["discounts"]!!.jsonObject["availability"]!!.jsonPrimitive.content)
                val units =
                    captures.getValue(
                        "products",
                    ).jsonObject["products"]!!.jsonObject["summary"]!!.jsonObject["units"]!!.jsonObject
                assertEquals("0", units["observedValue"]!!.jsonPrimitive.content)
                assertEquals(JsonNull, units["value"])

                fun normalize(
                    e: JsonElement,
                    key: String = "",
                ): JsonElement =
                    when (e) {
                        is JsonObject -> JsonObject(e.mapValues { (k, v) -> normalize(v, k) })
                        is JsonArray -> JsonArray(e.map { normalize(it, key) })
                        is JsonPrimitive ->
                            if (!e.isString) {
                                e
                            } else {
                                when {
                                    key == "reportHash" -> JsonPrimitive("captured-chat-report-hash")
                                    Regex("\\d{4}-\\d{2}-\\d{2}T.*").matches(e.content) ->
                                        if (key in listOf("from", "to")) {
                                            JsonPrimitive(
                                                LocalDate.of(2026, 11, 13).plusDays(
                                                    java.time.temporal.ChronoUnit.DAYS.between(
                                                        today,
                                                        java.time.Instant.parse(e.content)
                                                            .atOffset(ZoneOffset.UTC).toLocalDate(),
                                                    ),
                                                ).toString() + "T00:00:00Z",
                                            )
                                        } else {
                                            JsonPrimitive("2026-11-13T12:00:00Z")
                                        }
                                    else -> e
                                }
                            }
                    }
                val captured = normalize(JsonObject(captures))
                val fixture =
                    generateSequence(Path.of("").toAbsolutePath()) {
                        it.parent
                    }.map {
                        it.resolve("miniapp/e2e/fixtures/accounting-chat-reconciliation-http.json")
                    }.first { Files.isDirectory(it.parent) }
                if (System.getenv("ACCOUNTING_CHAT_CAPTURE") == "1") {
                    Files.writeString(
                        fixture,
                        Json {
                            prettyPrint = true
                        }.encodeToString(captured) + "\n",
                    )
                }
                assertEquals(Json.parseToJsonElement(Files.readString(fixture)), captured)
                Files.writeString(
                    Path.of("build/test-artifacts/accounting-chat-after.json"),
                    buildJsonObject {
                        put("sameOrder", true)
                        put("sameSale", true)
                        put("restartedReplay", true)
                        put("unallocatedAccruedMinor", 75000)
                        put("automaticPayment", false)
                        put("readOnlyFingerprint", true)
                        put("closed", true)
                    }.toString(),
                )
            } finally {
                server.stop(100, 1500)
            }
        }
    }
}
