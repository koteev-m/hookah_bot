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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
class AccountingPercentChannelsPostgresTest {
    @Test
    fun `Bot Mini App fallback percent accounting and analytics preserve one source per purchase over HTTP`() {
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
                    "db.jdbcUrl" to db.jdbcUrl, "db.user" to db.user, "db.password" to db.password,
                    "db.maxPoolSize" to "6", "telegram.enabled" to "true", "telegram.token" to "test-token",
                    "telegram.mode" to "webhook", "telegram.webhookSecretToken" to "secret",
                    "telegram.staffChatLinkSecretPepper" to "pepper", "telegram.trafficPolicy" to "PRODUCT",
                    "billing.subscriptionJob.enabled" to "false",
                )
            val outbound = ConcurrentLinkedQueue<String>()
            val server =
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
            try {
                val base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().single().port }}"
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
                webhook(data = "continue_in_bot:41")
                webhook(data = "bot_menu_item_add_to_cart:21:21")
                webhook(data = "bot_menu_cart_checkout")
                assertEquals(
                    "1",
                    scalar("SELECT COUNT(*) FROM orders"),
                    "Structured Bot checkout must use the real writer",
                )
                val firstOrder = scalar("SELECT id FROM orders").toLong()
                val firstBatch = scalar("SELECT id FROM order_batches").toLong()
                // Re-delivery of the same callback/update cannot create a second purchase.
                val oldUpdate = update
                request(
                    "/telegram/webhook",
                    """{"update_id":$oldUpdate,"callback_query":{"id":"percent-$oldUpdate",""" +
                        """"from":{"id":301},"message":{"message_id":1,"chat":{"id":301,""" +
                        """"type":"private"}},"data":"bot_menu_cart_checkout"}}""",
                    null,
                )
                assertEquals("1", scalar("SELECT COUNT(*) FROM orders"))
                val add =
                    """{"tableToken":"percent-11","tableSessionId":41,"tabId":51,""" +
                        """"idempotencyKey":"miniapp-percent-1","items":[{"itemId":21,"qty":1}]}"""
                val added = request("/api/guest/order/add-batch", add, 301)
                assertEquals(firstOrder, added["orderId"]!!.jsonPrimitive.long)
                assertEquals(added, request("/api/guest/order/add-batch", add, 301))
                assertEquals("1", scalar("SELECT COUNT(*) FROM orders"))
                assertEquals("2", scalar("SELECT COUNT(*) FROM order_batch_items"))
                var report = Json.decodeFromJsonElement<AccountingState>(request("/api/venue/1/accounting")).current!!
                assertEquals(listOf("order:$firstOrder"), report.sales.map { it.id })
                assertEquals(300000, report.totals.grossMinor)
                assertEquals(0, report.totals.paidMinor)
                assertEquals("0", scalar("SELECT COUNT(*) FROM venue_accounting_sales"))
                val writer = VenueOrdersRepository(ds)
                val actor = OrderActionActor(101, VenueRole.OWNER, AnalyticsChannel.MINIAPP)
                runBlocking {
                    for (batch in listOf(firstBatch, added["batchId"]!!.jsonPrimitive.long)) {
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
                    }
                    assertNotNull(writer.updateOrderStatus(1, firstOrder, OrderWorkflowStatus.CLOSED, actor))
                }
                val digital = runBlocking { AccountingRepository(ds).state(1, 101) }.current!!.sales.single()
                assertTrue(digital.orderClosed)
                // Editing the accounting projection never reprices the guest bill or adds a merchandise sale.
                val edit =
                    command(
                        "EDIT",
                        report.shiftId,
                        digital,
                    ).copy(
                        reason = "Учётная скидка",
                        lines =
                            digital.lines.map {
                                AccountingLineInput(
                                    id = it.id,
                                    qty = it.qty,
                                    unitPriceMinor = it.unitPriceMinor,
                                    discountPercent = "10",
                                )
                            },
                    )
                report =
                    Json.decodeFromJsonElement(
                        request("/api/venue/1/accounting/commands", Json.encodeToString(edit)),
                    )
                assertEquals(30000, report.totals.discountMinor)
                assertEquals(0, report.totals.paidMinor)
                val pay =
                    command(
                        "PAY",
                        report.shiftId,
                        report.sales.single(),
                    ).copy(paymentMethod = "CARD", amountMinor = 270000)
                report =
                    Json.decodeFromJsonElement(
                        request("/api/venue/1/accounting/commands", Json.encodeToString(pay)),
                    )
                assertEquals(270000, report.totals.paidMinor)
                val today = LocalDate.now(ZoneOffset.UTC)
                val productPath = "/api/venue/1/stats/v1?section=products&from=$today&to=${today.plusDays(1)}"
                val captures = linkedMapOf<String, JsonElement>()
                captures["digital"] = request("/api/venue/1/accounting")
                captures["digitalProducts"] = request(productPath)
                // Text fallback retains one order and an explicit reconciliation issue, without guessed snapshots.
                webhook(302, text = "/start percent-13")
                webhook(302, text = "✍️ Быстрый заказ")
                webhook(302, text = "Что-нибудь к чаю")
                val dialog =
                    Json.parseToJsonElement(
                        scalar("SELECT payload FROM telegram_dialog_state WHERE chat_id=302"),
                    ).jsonObject
                val quick = dialog["requestId"]!!.jsonPrimitive.content
                webhook(302, data = "quick_order_confirm:$quick")
                webhook(302, data = "quick_order_confirm:$quick")
                val fallback =
                    runBlocking {
                        AccountingRepository(
                            ds,
                        ).state(1, 101)
                    }.current!!.sales.single { it.tableId == 13L }
                assertTrue(fallback.lines.isEmpty())
                assertNotNull(fallback.issue)
                assertEquals("2", scalar("SELECT COUNT(*) FROM orders"))
                assertEquals("1", scalar("SELECT COUNT(*) FROM venue_accounting_sales"))
                // Manual sale is a separate purchase, even at the same table with the same menu item.
                val manual =
                    command(
                        "CREATE",
                        report.shiftId,
                    ).copy(
                        tableId = 11,
                        reason = "Скидка 10%",
                        lines =
                            listOf(
                                AccountingLineInput(
                                    menuItemId = 21,
                                    qty = 2,
                                    unitPriceMinor = 150000,
                                    discountPercent = "10",
                                ),
                            ),
                    )
                for (bad in listOf(
                    manual.copy(
                        requestId = "http-percent-invalid",
                        lines = manual.lines.map { it.copy(discountPercent = "100.01") },
                    ),
                    manual.copy(
                        requestId = "http-percent-dual",
                        lines = manual.lines.map { it.copy(discountMinor = 1) },
                    ),
                    manual.copy(requestId = "http-percent-no-reason", reason = null),
                )) {
                    val error = request("/api/venue/1/accounting/commands", Json.encodeToString(bad), expected = 400)
                    assertNotNull(error["error"]!!.jsonObject["details"]!!.jsonObject["field"])
                }
                val priceError =
                    request(
                        "/api/venue/1/accounting/commands",
                        Json.encodeToString(
                            manual.copy(
                                requestId = "http-price-conflict",
                                lines = manual.lines.map { it.copy(unitPriceMinor = 100) },
                            ),
                        ),
                        expected = 409,
                    )
                assertEquals(
                    "price",
                    priceError["error"]!!.jsonObject["details"]!!.jsonObject["field"]!!.jsonPrimitive.content,
                )

                report =
                    Json.decodeFromJsonElement(
                        request("/api/venue/1/accounting/commands", Json.encodeToString(manual)),
                    )
                assertEquals(600000, report.totals.grossMinor)
                assertEquals(60000, report.totals.discountMinor)
                assertEquals(540000, report.totals.accruedMinor)
                assertEquals(270000, report.totals.paidMinor)
                assertEquals(
                    report,
                    Json.decodeFromJsonElement(
                        request("/api/venue/1/accounting/commands", Json.encodeToString(manual)),
                    ),
                )
                captures["manual"] = request("/api/venue/1/accounting")
                captures["products"] = request(productPath)
                captures["productCategory"] = request("$productPath&expandedCategoryKey=21")

                fun units(root: JsonElement) =
                    root.jsonObject["products"]!!.jsonObject["summary"]!!
                        .jsonObject["units"]!!.jsonObject["value"]!!.jsonPrimitive.content
                assertEquals("2", units(captures.getValue("digitalProducts")))
                assertEquals("4", units(captures.getValue("products")))
                assertEquals("1", scalar("SELECT COUNT(*) FROM venue_accounting_sales WHERE id LIKE 'manual:%'"))
                assertEquals("1", scalar("SELECT COUNT(*) FROM venue_accounting_sales WHERE id LIKE 'order:%'"))
                assertEquals("1", scalar("SELECT COUNT(*) FROM audit_log WHERE action='VENUE_ACCOUNTING_CREATE'"))
                // No new analytics GET may mutate any database table; queue workers are quiescent first.
                val until = System.nanoTime() + 15_000_000_000L
                while (scalar("SELECT COUNT(*) FROM telegram_outbox WHERE status NOT IN ('SENT','FAILED')") != "0" &&
                    System.nanoTime() < until
                    ) Thread.sleep(50)

                fun fingerprint() =
                    ds.connection.use { c ->
                        val tables =
                            c.createStatement().use {
                                    s ->
                                s.executeQuery(
                                    "SELECT tablename FROM pg_tables WHERE schemaname=current_schema() " +
                                        "ORDER BY tablename",
                                ).use {
                                        r ->
                                    buildList { while (r.next()) add(r.getString(1)) }
                                }
                            }
                        tables.associateWith {
                                t ->
                            c.createStatement().use {
                                    s ->
                                s.executeQuery(
                                    "SELECT md5(COALESCE(string_agg(v,',' ORDER BY v),'')) FROM " +
                                        "(SELECT row_to_json(t)::text v FROM \"$t\" t)x",
                                ).use {
                                    it.next()
                                    it.getString(1)
                                }
                            }
                        }
                    }
                val before = fingerprint()
                repeat(2) {
                    request(productPath)
                    request("/api/venue/1/accounting")
                }
                request("/api/venue/1/accounting", actor = 201, expected = 403)
                request(
                    "/api/venue/1/accounting/commands",
                    Json.encodeToString(manual.copy(requestId = "foreign-percent")),
                    actor = 301,
                    expected = 403,
                )
                assertEquals(before, fingerprint())
                // Normalize only nondeterministic values for a checked-in API-to-adapter browser contract.
                val ids = linkedMapOf<String, String>()

                fun canonicalDate(date: LocalDate) =
                    LocalDate.of(2026, 11, 13)
                        .plusDays(java.time.temporal.ChronoUnit.DAYS.between(today, date)).toString()

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
                                    key == "reportHash" -> JsonPrimitive("captured-report-hash")
                                    Regex(
                                        "\\d{4}-\\d{2}-\\d{2}T.*",
                                    ).matches(
                                        e.content,
                                    ) ->
                                        if (key in setOf("from", "to")) {
                                            JsonPrimitive(
                                                canonicalDate(
                                                    java.time.Instant.parse(e.content)
                                                        .atOffset(ZoneOffset.UTC).toLocalDate(),
                                                ) +
                                                    "T00:00:00Z",
                                            )
                                        } else {
                                            JsonPrimitive("2026-11-13T12:00:00Z")
                                        }
                                    Regex(
                                        "\\d{4}-\\d{2}-\\d{2}",
                                    ).matches(
                                        e.content,
                                    ) ->
                                        JsonPrimitive(
                                            LocalDate.of(
                                                2026,
                                                11,
                                                13,
                                            ).plusDays(
                                                java.time.temporal.ChronoUnit.DAYS.between(
                                                    today,
                                                    LocalDate.parse(e.content),
                                                ),
                                            ).toString(),
                                        )
                                    Regex(
                                        ".*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*",
                                    ).matches(e.content) ->
                                        JsonPrimitive(
                                            ids.getOrPut(e.content) {
                                                if (e.content.startsWith(
                                                        "manual:",
                                                    )
                                                ) {
                                                    "manual:captured"
                                                } else {
                                                    "line-${ids.size}"
                                                }
                                            },
                                        )
                                    else -> e
                                }
                            }
                    }
                val capture = normalize(JsonObject(captures))
                val fixture =
                    generateSequence(Path.of("").toAbsolutePath()) {
                        it.parent
                    }.map {
                        it.resolve(
                            "miniapp/e2e/fixtures/accounting-percent-http.json",
                        )
                    }.first { Files.isDirectory(it.parent) }
                if (System.getenv("ACCOUNTING_PERCENT_CAPTURE") == "1") {
                    Files.writeString(
                        fixture,
                        Json {
                            prettyPrint = true
                        }.encodeToString(capture) + "\n",
                    )
                }
                assertEquals(Json.parseToJsonElement(Files.readString(fixture)), capture)
                System.getenv("ACCOUNTING_PERCENT_LIVE_DIR")?.let { dir ->
                    val live = Path.of(dir)
                    Files.createDirectories(live)
                    Files.writeString(
                        live.resolve("ready.json"),
                        buildJsonObject {
                            put("base", base)
                            put("token", tokens.issueToken(101).token)
                            put("venueId", 1)
                        }.toString(),
                    )
                    try {
                        val end = System.nanoTime() + 300_000_000_000L
                        while (!Files.exists(live.resolve("done")) && System.nanoTime() < end) Thread.sleep(50)
                        assertTrue(Files.exists(live.resolve("done")))
                    } finally {
                        Files.deleteIfExists(live.resolve("ready.json"))
                    }
                    assertEquals(before, fingerprint())
                }
                // Reject the synthetic text request through the existing workflow, then freeze finances.
                runBlocking {
                    assertTrue(
                        writer.rejectOrder(1, fallback.orderId!!, "OTHER", "Synthetic reconciliation", actor)!!.applied,
                    )
                }
                val beforeClose =
                    Json.decodeFromJsonElement<AccountingState>(
                        request("/api/venue/1/accounting"),
                    ).current!!
                assertEquals(300000, beforeClose.sales.single { it.orderId == firstOrder }.originalPayableMinor)
                val closed =
                    Json.decodeFromJsonElement<AccountingReport>(
                        request(
                            "/api/venue/1/accounting/commands",
                            Json.encodeToString(
                                command(
                                    "CLOSE",
                                    report.shiftId,
                                ).copy(expectedReportHash = beforeClose.reportHash, acknowledgeUnpaid = true),
                            ),
                        ),
                    )
                assertEquals(60000, closed.totals.discountMinor)
                val finance =
                    request(
                        "/api/venue/1/stats/v1?section=finance&from=$today&to=${today.plusDays(1)}",
                    )["closedShiftFinances"]!!.jsonArray.single().jsonObject["metrics"]!!.jsonObject
                assertEquals("60000", finance["discounts"]!!.jsonObject["value"]!!.jsonPrimitive.content)
                assertEquals("540000", finance["accrued"]!!.jsonObject["value"]!!.jsonPrimitive.content)
                assertEquals("270000", finance["operatorConfirmed"]!!.jsonObject["value"]!!.jsonPrimitive.content)
                sql("UPDATE menu_items SET price_minor=999999 WHERE id=21")
                assertEquals(closed, Json.decodeFromJsonElement(request("/api/venue/1/accounting/${report.shiftId}")))
                request(
                    "/api/venue/1/accounting/commands",
                    Json.encodeToString(manual.copy(requestId = "closed-percent-sale")),
                    expected = 409,
                )
                assertTrue(outbound.contains("sendMessage"))
                val evidence = Path.of("build/test-artifacts/accounting-percent-channels.json")
                Files.createDirectories(evidence.parent)
                Files.writeString(
                    evidence,
                    buildJsonObject {
                        put("bot", true)
                        put("miniApp", true)
                        put("fallbackIncomplete", true)
                        put("readOnlyFingerprint", true)
                        put("manualGrossMinor", 300000)
                        put("manualDiscountMinor", 30000)
                        put("manualAccruedMinor", 270000)
                        put("automaticPayment", false)
                        put("productUnits", 4)
                    }.toString(),
                )
            } finally {
                server.stop(100, 1500)
            }
        }
    }
}
