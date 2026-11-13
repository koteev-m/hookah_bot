package com.hookah.platform.backend.analytics

import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingCommand
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingLineInput
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingRepository
import com.hookah.platform.backend.miniapp.venue.orders.OrderActionActor
import com.hookah.platform.backend.miniapp.venue.orders.OrderBatchStatus
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.module
import com.hookah.platform.backend.support.SupportMessageAuthorRole
import com.hookah.platform.backend.support.SupportMessageSource
import com.hookah.platform.backend.support.SupportThreadRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Statement
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VenueOperationsPostgresHttpTest {
    @Test
    fun `committed PostgreSQL writers reach authenticated HTTP and real browser adapter without read writes`() {
        val db = PostgresTestEnv.createDatabase()
        val ds = PostgresTestEnv.createDataSource(db)

        fun exec(sql: String) = ds.connection.use { c -> c.createStatement().use { it.executeUpdate(sql) } }

        fun insert(sql: String): Long =
            ds.connection.use { c ->
                c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use {
                        s ->
                    s.executeUpdate()
                    s.generatedKeys.use {
                        it.next()
                        it.getLong(1)
                    }
                }
            }
        val venue = insert("INSERT INTO venues(name,status) VALUES ('Operations fixture','PUBLISHED')")
        exec("INSERT INTO users(telegram_user_id) VALUES (7)")
        exec("INSERT INTO venue_members(venue_id,user_id,role) VALUES ($venue,7,'OWNER')")
        exec("INSERT INTO venue_settings(venue_id,timezone) VALUES ($venue,'UTC')")
        val table = insert("INSERT INTO venue_tables(venue_id,table_number) VALUES ($venue,1)")
        val session =
            insert(
                "INSERT INTO table_sessions(venue_id,table_id,status,started_at," +
                    "last_activity_at,expires_at) VALUES ($venue,$table,'ACTIVE'," +
                    "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 day')",
            )
        val order =
            insert(
                "INSERT INTO orders(venue_id,table_id,table_session_id,status," +
                    "acceptance_history_complete) VALUES ($venue,$table,$session,'ACTIVE',TRUE)",
            )
        val tab =
            insert(
                "INSERT INTO tab(venue_id,table_session_id,type,owner_user_id) VALUES ($venue,$session,'PERSONAL',7)",
            )
        val category = insert("INSERT INTO menu_categories(venue_id,name) VALUES ($venue,'Напитки')")
        val item =
            insert(
                "INSERT INTO menu_items(venue_id,category_id,name,price_minor) VALUES " +
                    "($venue,$category,'Текущий чай',99999)",
            )
        val actor =
            OrderActionActor(7, com.hookah.platform.backend.miniapp.venue.VenueRole.OWNER, AnalyticsChannel.MINIAPP)
        val writer = VenueOrdersRepository(ds)
        repeat(2) {
            val batch =
                insert(
                    "INSERT INTO order_batches(order_id,tab_id,source,status," +
                        "status_history_complete) VALUES ($order,$tab,'MINIAPP','NEW',TRUE)",
                )
            exec(
                "INSERT INTO order_batch_items(order_batch_id,menu_item_id,qty," +
                    "item_name_snapshot,base_unit_price_minor_snapshot,currency_snapshot) " +
                    "VALUES ($batch,$item,2,'Исторический чай',12345,'IQD')",
            )
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
        }
        runBlocking {
            assertTrue(
                writer.updateOrderStatus(
                    venue,
                    order,
                    com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus.CLOSED,
                    actor,
                )!!.applied,
            )
        }
        val table2 = insert("INSERT INTO venue_tables(venue_id,table_number) VALUES ($venue,2)")
        val session2 =
            insert(
                "INSERT INTO " +
                    "table_sessions(venue_id,table_id,status,started_at,last_activity_at,expires_at) VALUES " +
                    "($venue,$table2,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + " +
                    "INTERVAL '1 day')",
            )
        val giftOrder =
            insert(
                "INSERT INTO " +
                    "orders(venue_id,table_id,table_session_id,status,acceptance_history_complete,closed_at) " +
                    "VALUES ($venue,$table2,$session2,'CLOSED',TRUE,CURRENT_TIMESTAMP)",
            )
        val giftTab =
            insert(
                "INSERT INTO tab(venue_id,table_session_id,type,status) VALUES " +
                    "($venue,$session2,'SHARED','CLOSED')",
            )
        val giftBatch =
            insert(
                "INSERT INTO " +
                    "order_batches(order_id,tab_id,source,status,status_history_complete,first_delivered_at) " +
                    "VALUES ($giftOrder,$giftTab,'MINIAPP','DELIVERED',TRUE,CURRENT_TIMESTAMP)",
            )
        val giftItem =
            insert(
                "INSERT INTO menu_items(venue_id,category_id,name,price_minor) VALUES " +
                    "($venue,$category,'Текущее название',99999)",
            )
        insert(
            "INSERT INTO " +
                "order_batch_items(order_batch_id,menu_item_id,qty,item_name_snapshot," +
                "base_unit_price_minor_snapshot,currency_snapshot) VALUES ($giftBatch,$giftItem,3," +
                "'Подарок из учёта',100,'RUB')",
        )
        val shift =
            insert(
                "INSERT INTO venue_accounting_shifts(venue_id,status,open_slot,timezone,opened_at) " +
                    "VALUES ($venue,'OPEN',1,'UTC',CURRENT_TIMESTAMP)",
            )
        exec("UPDATE orders SET accounting_shift_id=$shift WHERE id=$giftOrder")
        val accounting = AccountingRepository(ds, AuditLogRepository(ds))
        val sale = runBlocking { accounting.detail(venue, 7, shift) }!!.sales.single()
        runBlocking {
            accounting.execute(
                venue,
                7,
                AccountingCommand(
                    "http-gift-request-001",
                    "EDIT",
                    shift,
                    sale.id,
                    sale.version,
                    lines = sale.lines.map { AccountingLineInput(id = it.id, qty = it.qty, giftReason = "Gift") },
                    reason = "Gift",
                ),
            )
        }
        exec("INSERT INTO users(telegram_user_id) VALUES (8)")
        val support = SupportThreadRepository(ds)
        val seatedBooking =
            insert(
                "INSERT INTO bookings(venue_id,user_id,scheduled_at,status,display_date,display_number) " +
                    "VALUES ($venue,8,CURRENT_TIMESTAMP,'SEATED',CURRENT_DATE,1)",
            )
        val closedBooking =
            insert(
                "INSERT INTO bookings(venue_id,user_id,scheduled_at,status,display_date,display_number) " +
                    "VALUES ($venue,8,CURRENT_TIMESTAMP,'CONFIRMED',CURRENT_DATE,2)",
            )
        for (booking in listOf(seatedBooking, closedBooking)) {
            val message =
                runBlocking {
                    support.addBookingMessage(
                        booking,
                        8,
                        SupportMessageAuthorRole.GUEST,
                        SupportMessageSource.GUEST_BOT,
                        "Private guest text never in analytics",
                        expectedGuestUserId = 8,
                        expectedVenueId = venue,
                    )
                }!!
            if (booking == closedBooking) {
                exec(
                    "UPDATE support_threads SET status='CLOSED' WHERE id=${message.thread.id}",
                )
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
            val base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().single().port }}"
            val token = SessionTokenService(SessionTokenConfig.from(config, "test")).issueToken(7).token
            val from = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()
            val to = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString()
            val url = "$base/api/venue/$venue/stats/v1?from=$from&to=$to&section=products"
            val http = HttpClient.newHttpClient()
            val response =
                http.send(
                    HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer $token").build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(200, response.statusCode())
            val result = Json.parseToJsonElement(response.body()).jsonObject
            assertEquals(
                "7",
                result.getValue(
                    "products",
                ).jsonObject.getValue("coverage").jsonObject.getValue("value").jsonPrimitive.content,
            )
            val dir = Path.of("build/test-artifacts/operations-postgres")
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("products.json"), response.body())

            fun get(path: String): String {
                val value =
                    http.send(
                        HttpRequest.newBuilder(
                            URI.create(base + path),
                        ).header("Authorization", "Bearer $token").build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                assertEquals(200, value.statusCode())
                return value.body()
            }
            val additions = get("/api/venue/$venue/stats/v1?from=$from&to=$to&section=additions")
            val metrics = Json.parseToJsonElement(additions).jsonObject.getValue("metrics").jsonObject
            assertEquals(
                "1",
                metrics.getValue("additions.completedTabs").jsonObject.getValue("value").jsonPrimitive.content,
            )
            assertEquals("1", metrics.getValue("additions.openTabs").jsonObject.getValue("value").jsonPrimitive.content)
            val dashboard = get("/api/venue/$venue/dashboard?monitoringVersion=3")
            val tasks = Json.parseToJsonElement(dashboard).jsonObject.getValue("attention").jsonArray
            assertEquals(1, tasks.size)
            assertEquals(
                seatedBooking.toString(),
                tasks.single().jsonObject.getValue("bookingId").jsonPrimitive.content,
            )
            assertTrue(!dashboard.contains("Private guest text"))
            Files.writeString(dir.resolve("additions.json"), additions)
            Files.writeString(dir.resolve("dashboard.json"), dashboard)

            System.getenv("VENUE_OPERATIONS_LIVE_DIR")?.let { path ->
                val live = Path.of(path)
                Files.createDirectories(live)

                fun fingerprint() =
                    ds.connection.use { c ->
                        c.read(
                            "SELECT tablename FROM pg_tables WHERE schemaname=current_schema() ORDER BY tablename",
                        ) { it.getString(1) }
                            .associateWith { table ->
                                c.read(
                                    "SELECT md5(COALESCE(string_agg(v,',' ORDER BY v),'')) FROM " +
                                        "(SELECT row_to_json(t)::text v FROM \"$table\" t) x",
                                ) {
                                    it.getString(1)
                                }.single()
                            }
                    }
                val before = fingerprint()
                Files.writeString(
                    live.resolve("ready.json"),
                    JsonObject(
                        mapOf(
                            "base" to JsonPrimitive(base),
                            "token" to JsonPrimitive(token),
                            "venueId" to JsonPrimitive(venue),
                        ),
                    ).toString(),
                )
                val deadline = System.nanoTime() + 180_000_000_000L
                while (!Files.exists(live.resolve("done")) && System.nanoTime() < deadline) Thread.sleep(100)
                assertTrue(Files.exists(live.resolve("done")), "Browser handoff did not finish")
                assertEquals(before, fingerprint())
                Files.deleteIfExists(live.resolve("ready.json"))
            }
        } finally {
            server.stop(100, 1000)
            ds.close()
        }
    }
}
