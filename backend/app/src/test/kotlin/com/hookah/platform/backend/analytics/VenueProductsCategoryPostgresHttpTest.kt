package com.hookah.platform.backend.analytics

import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingLine
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingSale
import com.hookah.platform.backend.miniapp.venue.orders.OrderActionActor
import com.hookah.platform.backend.miniapp.venue.orders.OrderBatchStatus
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Statement
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VenueProductsCategoryPostgresHttpTest {
    @Test
    fun `product categories complete range HTTP preserves RBAC read only and real click contract`() {
        val db = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(db).use { ds ->
            fun exec(sql: String) = ds.connection.use { c -> c.createStatement().use { it.executeUpdate(sql) } }

            fun insert(sql: String): Long =
                ds.connection.use { c ->
                    c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { s ->
                        s.executeUpdate()
                        s.generatedKeys.use {
                            it.next()
                            it.getLong(1)
                        }
                    }
                }
            val venue = insert("INSERT INTO venues(name,status) VALUES ('Categories fixture','PUBLISHED')")
            val partial = insert("INSERT INTO venues(name,status) VALUES ('Partial fixture','PUBLISHED')")
            val foreign = insert("INSERT INTO venues(name,status) VALUES ('Foreign','PUBLISHED')")
            exec("INSERT INTO users(telegram_user_id) VALUES (7),(8),(9),(10),(99)")
            exec(
                """INSERT INTO venue_members(venue_id,user_id,role) VALUES
                ($venue,7,'OWNER'),($venue,8,'MANAGER'),($venue,9,'STAFF'),($partial,7,'OWNER')""",
            )
            exec("INSERT INTO venue_settings(venue_id,timezone) VALUES ($venue,'UTC'),($partial,'UTC')")

            fun order(v: Long): Long {
                val table = insert("INSERT INTO venue_tables(venue_id,table_number) VALUES ($v,1)")
                val session =
                    insert(
                        """INSERT INTO table_sessions(venue_id,table_id,status,
                    started_at,last_activity_at,expires_at) VALUES ($v,$table,'ACTIVE',
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '1 day')""",
                    )
                return insert(
                    """INSERT INTO orders(venue_id,table_id,table_session_id,status,
                    acceptance_history_complete) VALUES ($v,$table,$session,'ACTIVE',TRUE)""",
                )
            }
            val order = order(venue)
            val batch =
                insert(
                    """INSERT INTO order_batches(order_id,source,status,status_history_complete)
                VALUES ($order,'MINIAPP','NEW',TRUE)""",
                )
            val cat = insert("INSERT INTO menu_categories(venue_id,name) VALUES ($venue,'Напитки')")
            val other = insert("INSERT INTO menu_categories(venue_id,name) VALUES ($venue,'Напитки')")
            val zeros = insert("INSERT INTO menu_categories(venue_id,name) VALUES ($venue,'Услуги без продаж')")
            var first = 0L
            for (n in 1..65) {
                val category = if (n <= 60) cat else other
                val item =
                    insert(
                        """INSERT INTO menu_items(venue_id,category_id,name,price_minor,currency)
                    VALUES ($venue,$category,'Текущее имя $n',999999,'RUB')""",
                    )
                if (n == 1) first = item
                exec(
                    """INSERT INTO order_batch_items(order_batch_id,menu_item_id,qty,item_name_snapshot,
                    base_unit_price_minor_snapshot,currency_snapshot)
                    VALUES ($batch,$item,2,'Товар ${n.toString().padStart(3,'0')}',100,'RUB')""",
                )
            }
            for (n in 1..2) exec(
                """INSERT INTO menu_items(venue_id,category_id,name,price_minor,currency)
                VALUES ($venue,$cat,'Пока не заказывали $n',100,'RUB')""",
            )
            exec(
                """INSERT INTO menu_items(venue_id,category_id,name,price_minor,currency)
                VALUES ($venue,$zeros,'Очень длинное название услуги для проверки компактной строки',900,'EUR')""",
            )
            val actor = OrderActionActor(7, VenueRole.OWNER, AnalyticsChannel.MINIAPP)
            val writer = VenueOrdersRepository(ds)
            runBlocking {
                assertTrue(
                    writer.updateBatchStatus(
                        venue,
                        batch,
                        OrderBatchStatus.NEW,
                        OrderBatchStatus.ACCEPTED,
                        actor,
                    )!!.applied,
                )
                assertTrue(
                    writer.updateBatchStatus(
                        venue,
                        batch,
                        OrderBatchStatus.ACCEPTED,
                        OrderBatchStatus.DELIVERED,
                        actor,
                    )!!.applied,
                )
            }
            val table =
                ds.connection.use {
                        c ->
                    c.read("SELECT table_id FROM orders WHERE id=?", listOf(order)) { it.getLong(1) }.single()
                }
            val shift =
                ds.connection.use { c ->
                    c.read("SELECT id FROM venue_accounting_shifts WHERE venue_id=? AND status='OPEN'", listOf(venue)) {
                        it.getLong(1)
                    }.singleOrNull()
                } ?: insert(
                    """INSERT INTO venue_accounting_shifts(venue_id,status,open_slot,timezone,opened_at)
                VALUES ($venue,'OPEN',1,'UTC',CURRENT_TIMESTAMP)""",
                )
            val now = java.time.Instant.now().toString()
            val sale =
                AccountingSale(
                    id = "manual-category",
                    tableId = table,
                    tableLabel = "Table",
                    source = "MANUAL",
                    createdAt = now,
                    currency = "RUB",
                    lines =
                        listOf(
                            AccountingLine(
                                id = "m",
                                menuItemId = first,
                                name = "Ручное имя",
                                qty = 3,
                                unitPriceMinor = 100,
                                giftReason = "Gift",
                            ),
                        ),
                )
            ds.connection.use { c ->
                c.prepareStatement("INSERT INTO venue_accounting_sales(id,shift_id,snapshot_json) VALUES (?,?,?)").use {
                        s ->
                    s.setString(1, sale.id)
                    s.setLong(2, shift)
                    s.setString(3, Json.encodeToString(sale))
                    s.executeUpdate()
                }
            }
            val pcat = insert("INSERT INTO menu_categories(venue_id,name) VALUES ($partial,'Неполная история')")
            val pi =
                insert(
                    "INSERT INTO menu_items(venue_id,category_id,name,price_minor) " +
                        "VALUES ($partial,$pcat,'Без цены',999)",
                )
            val po = order(partial)
            val pb =
                insert(
                    """INSERT INTO order_batches(order_id,source,status,status_history_complete,first_delivered_at)
                VALUES ($po,'MINIAPP','DELIVERED',FALSE,CURRENT_TIMESTAMP)""",
                )
            exec(
                """INSERT INTO order_batch_items(order_batch_id,menu_item_id,qty,item_name_snapshot,
                currency_snapshot) VALUES ($pb,$pi,2,'Без цены','RUB')""",
            )
            val currencyVenue = insert("INSERT INTO venues(name,status) VALUES ('Currency fixture','PUBLISHED')")
            exec("INSERT INTO venue_members(venue_id,user_id,role) VALUES ($currencyVenue,7,'OWNER')")
            exec("INSERT INTO venue_settings(venue_id,timezone) VALUES ($currencyVenue,'UTC')")
            val currencyCategory =
                insert("INSERT INTO menu_categories(venue_id,name) VALUES ($currencyVenue,'Валюта неизвестна')")
            val cleanCategory =
                insert("INSERT INTO menu_categories(venue_id,name) VALUES ($currencyVenue,'Без продаж')")
            val currencyItem =
                insert(
                    "INSERT INTO menu_items(venue_id,category_id,name,price_minor) VALUES " +
                        "($currencyVenue,$currencyCategory,'Текущая цена',999999)",
                )
            exec(
                "INSERT INTO menu_items(venue_id,category_id,name,price_minor) VALUES " +
                    "($currencyVenue,$cleanCategory,'Без продаж',999999)",
            )
            val currencyOrder = order(currencyVenue)
            val currencyBatch =
                insert(
                    "INSERT INTO " +
                        "order_batches(order_id,source,status,status_history_complete,first_delivered_at) " +
                        "VALUES ($currencyOrder,'MINIAPP','DELIVERED',TRUE,CURRENT_TIMESTAMP)",
                )
            exec(
                "INSERT INTO " +
                    "order_batch_items(order_batch_id,menu_item_id,qty,item_name_snapshot," +
                    "base_unit_price_minor_snapshot)" +
                    " VALUES ($currencyBatch,$currencyItem,2,'Исторический товар',100)",
            )
            val currencyShift =
                insert(
                    "INSERT INTO venue_accounting_shifts(venue_id,status,open_slot,timezone,opened_at) " +
                        "VALUES ($currencyVenue,'OPEN',1,'UTC',CURRENT_TIMESTAMP)",
                )
            for ((currency, qty) in listOf("RUB" to 3, "USD" to 4)) {
                val value =
                    sale.copy(
                        id = "currency-$currency",
                        currency = currency,
                        lines =
                            listOf(
                                AccountingLine(
                                    id = currency,
                                    menuItemId = currencyItem,
                                    name = "Ручная подпись",
                                    qty = qty,
                                    unitPriceMinor = 100,
                                ),
                            ),
                    )
                ds.connection.use { c ->
                    c.prepareStatement(
                        "INSERT INTO venue_accounting_sales(id,shift_id,snapshot_json) VALUES (?,?,?)",
                    ).use {
                            st ->
                        st.setString(1, value.id)
                        st.setLong(2, currencyShift)
                        st.setString(3, Json.encodeToString(value))
                        st.executeUpdate()
                    }
                }
            }
            val config =
                MapApplicationConfig(
                    "app.env" to "test",
                    "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to db.jdbcUrl,
                    "db.user" to db.user,
                    "db.password" to db.password,
                    "telegram.enabled" to "false",
                    "telegram.platformOwnerId" to "99",
                    "billing.subscriptionJob.enabled" to "false",
                )
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
                }, module = { module() }).start(false)
            try {
                val base = "http://127.0.0.1:${runBlocking {server.engine.resolvedConnectors().single().port}}"
                val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))
                val http = HttpClient.newHttpClient()
                val today = LocalDate.now(ZoneOffset.UTC)
                val prefix = "/api/venue/$venue/stats/v1?from=$today&to=${today.plusDays(1)}&section=products"

                fun get(
                    path: String = prefix,
                    user: Long? = 7,
                    expected: Int = 200,
                ): JsonObject {
                    val builder = HttpRequest.newBuilder(URI.create(base + path))
                    if (user != null)builder.header("Authorization", "Bearer ${tokens.issueToken(user).token}")
                    val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                    assertEquals(expected, response.statusCode(), path)
                    return Json.parseToJsonElement(response.body()).jsonObject
                }

                fun fingerprint() =
                    ds.connection.use { c ->
                        c.read("SELECT tablename FROM pg_tables WHERE schemaname=current_schema() ORDER BY tablename") {
                            it.getString(1)
                        }.associateWith {
                                t ->
                            c.read(
                                "SELECT md5(COALESCE(string_agg(v,',' ORDER BY v),'')) FROM " +
                                    "(SELECT row_to_json(t)::text v FROM \"$t\" t)x",
                            ) {
                                it.getString(1)
                            }.single()
                        }
                    }
                val before = fingerprint()
                val root = get()
                assertEquals(
                    JsonPrimitive("133"),
                    root["products"]!!.jsonObject["summary"]!!.jsonObject["units"]!!.jsonObject["value"],
                )
                for (user in listOf(9L, 10L, 99L))get(user = user, expected = 403)
                get(user = null, expected = 401)
                get(user = 8)
                get(prefix.replace("/venue/$venue/", "/venue/$foreign/"), expected = 403)
                val one = get(prefix + "&expandedCategoryKey=$cat")
                val two = get(prefix + "&expandedCategoryKey=$cat&page=2")
                assertEquals(one["products"]!!.jsonObject["summary"], two["products"]!!.jsonObject["summary"])
                get(prefix + "&expandedCategoryKey=-1", expected = 400)
                assertEquals(before, fingerprint())
                exec("DELETE FROM venue_members WHERE venue_id=$venue AND user_id=8")
                val revoked = fingerprint()
                get(user = 8, expected = 403)
                assertEquals(revoked, fingerprint())
                exec("INSERT INTO venue_members(venue_id,user_id,role) VALUES ($venue,8,'MANAGER')")
                val afterSetup = fingerprint()
                val output = Path.of("build/test-artifacts/products-categories")
                Files.createDirectories(output)
                Files.writeString(output.resolve("root.json"), root.toString())
                Files.writeString(output.resolve("page-one.json"), one.toString())
                Files.writeString(output.resolve("page-two.json"), two.toString())
                val mergedQuery = prefix + "&search=" + URLEncoder.encode("Товар 001", UTF_8)
                val mergedSearch = get(mergedQuery)
                val mergedCategory = get(mergedQuery + "&expandedCategoryKey=$cat")
                assertEquals(
                    JsonPrimitive("5"),
                    mergedSearch["products"]!!.jsonObject["summary"]!!.jsonObject["units"]!!.jsonObject["value"],
                )
                assertEquals(
                    JsonPrimitive("500"),
                    mergedCategory["products"]!!.jsonObject["categoryPage"]!!
                        .jsonObject["rows"]!!.jsonArray.single().jsonObject["gross"]!!.jsonObject["value"],
                )
                val currencyPrefix = prefix.replace("/venue/$venue/", "/venue/$currencyVenue/")
                val currencyCaptures =
                    buildJsonObject {
                        for ((name, suffix) in listOf(
                            "unknownRoot" to "",
                            "unknownRub" to "&currency=RUB",
                            "unknownUsd" to "&currency=USD",
                            "unknownOnly" to "&currency=UNKNOWN",
                            "unknownClean" to "&currency=RUB&categoryKey=$cleanCategory",
                            "unknownEmpty" to "&currency=RUB&search=absent",
                        )) {
                            val response = get(currencyPrefix + suffix + "&expandedCategoryKey=$currencyCategory")
                            val products = response["products"]!!.jsonObject
                            val totals = products["summary"]!!.jsonObject
                            if (name in listOf("unknownRub", "unknownUsd")) {
                                assertEquals(JsonPrimitive("PARTIAL"), totals["units"]!!.jsonObject["availability"])
                                assertEquals(
                                    JsonPrimitive(if (name == "unknownRub") "3" else "4"),
                                    totals["units"]!!.jsonObject["observedValue"],
                                )
                                assertEquals(
                                    totals,
                                    products["categoryTotals"]!!.jsonArray.single {
                                        it.jsonObject["category"]!!.jsonObject["key"] ==
                                            JsonPrimitive(currencyCategory.toString())
                                    }.jsonObject["totals"],
                                )
                                assertEquals(
                                    JsonPrimitive("PARTIAL"),
                                    totals["grossAmounts"]!!.jsonArray.single()
                                        .jsonObject["gross"]!!.jsonObject["availability"],
                                )
                                val searched =
                                    get(
                                        currencyPrefix + suffix + "&search=" + URLEncoder.encode("Исторический", UTF_8),
                                    )
                                assertEquals(totals, searched["products"]!!.jsonObject["summary"])
                            }
                            if (name in listOf("unknownClean", "unknownEmpty")) {
                                assertEquals(
                                    JsonPrimitive("0"),
                                    totals["units"]!!.jsonObject["value"],
                                )
                            }
                            put(name, response)
                        }
                    }
                val captures =
                    buildJsonObject {
                        put("root", root)
                        put("mergedSearch", mergedSearch)
                        put("mergedCategory", mergedCategory)
                        currencyCaptures.forEach { (name, value) -> put(name, value) }
                        put("pageOne", one)
                        put("pageTwo", two)
                        put("other", get(prefix + "&expandedCategoryKey=$other"))
                        put("zero", get(prefix + "&expandedCategoryKey=$zeros"))
                        put("search", get(prefix + "&search=065"))
                        put("empty", get(prefix + "&search=absent"))
                        put("partial", get(prefix.replace("/venue/$venue/", "/venue/$partial/")))
                        put(
                            "partialCategory",
                            get(prefix.replace("/venue/$venue/", "/venue/$partial/") + "&expandedCategoryKey=$pcat"),
                        )
                    }
                Files.writeString(
                    output.resolve("http-fixture.json"),
                    Json { prettyPrint = true }.encodeToString(captures),
                )
                ds.connection.use { c ->
                    c.prepareStatement("EXPLAIN (ANALYZE,BUFFERS,FORMAT TEXT) $PRODUCT_DELIVERY_SQL").use { s ->
                        s.setLong(1, venue)
                        s.setObject(2, today.atStartOfDay().atOffset(ZoneOffset.UTC))
                        s.setObject(3, today.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC))
                        Files.writeString(
                            output.resolve("query-plan.txt"),
                            s.executeQuery().use {
                                    rs ->
                                buildString { while (rs.next())appendLine(rs.getString(1)) }
                            },
                        )
                    }
                }
                System.getenv("VENUE_PRODUCTS_CATEGORY_LIVE_DIR")?.let { path ->
                    val live = Path.of(path)
                    Files.createDirectories(live)
                    Files.writeString(
                        live.resolve("ready.json"),
                        buildJsonObject {
                            put("base", base)
                            put("token", tokens.issueToken(7).token)
                            put("venueId", venue)
                            put("partialVenueId", partial)
                            put("currencyVenueId", currencyVenue)
                            put("currencyCategoryKey", currencyCategory.toString())
                            put("cleanCategoryKey", cleanCategory.toString())
                            put("categoryKey", cat.toString())
                            put("otherCategoryKey", other.toString())
                        }.toString(),
                    )
                    try {
                        val until = System.nanoTime() + 300_000_000_000L
                        while (!Files.exists(live.resolve("done")) && System.nanoTime() < until)Thread.sleep(50)
                        assertTrue(Files.exists(live.resolve("done")))
                    } finally {
                        Files.deleteIfExists(live.resolve("ready.json"))
                    }
                }
                assertEquals(afterSetup, fingerprint())
            } finally {
                server.stop(100, 1000)
            }
        }
    }
}
