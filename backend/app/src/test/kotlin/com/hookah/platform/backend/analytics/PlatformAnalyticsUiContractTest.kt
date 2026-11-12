package com.hookah.platform.backend.analytics

import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.migrateH2OnboardingFixture
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.h2.jdbcx.JdbcDataSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.Statement
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformAnalyticsUiContractTest {
    @Test
    fun `real loopback HTTP captures platform aggregates RBAC and read only facts for the screen`() {
        val url =
            "jdbc:h2:mem:platform_ui_${UUID.randomUUID()};MODE=PostgreSQL;" +
                "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        val config =
            MapApplicationConfig(
                "app.env" to "test",
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to url,
                "db.user" to "sa",
                "db.password" to "",
                "telegram.enabled" to "false",
                "billing.subscriptionJob.enabled" to "false",
                "telegram.platformOwnerId" to "99",
            )
        migrateH2OnboardingFixture(
            JdbcDataSource().apply {
                setURL(url)
                user = "sa"
                password = ""
            },
        )
        val server =
            embeddedServer(
                Netty,
                environment = applicationEnvironment { this.config = config },
                configure = {
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
                },
                module = { module() },
            ).start(false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            val base = "http://127.0.0.1:$port"
            val http = HttpClient.newHttpClient()
            val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))

            fun get(
                path: String,
                actor: Long? = 99,
            ): HttpResponse<String> {
                val request = HttpRequest.newBuilder(URI(base + path))
                if (actor != null) request.header("Authorization", "Bearer ${tokens.issueToken(actor).token}")
                return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
            }

            fun sql(value: String): Long =
                DriverManager.getConnection(url, "sa", "").use { c ->
                    c.prepareStatement(value, Statement.RETURN_GENERATED_KEYS).use { s ->
                        s.executeUpdate()
                        s.generatedKeys.use { if (it.next()) it.getLong(1) else 0 }
                    }
                }

            fun rows(): Map<String, List<String>> =
                DriverManager.getConnection(url, "sa", "").use { c ->
                    val tables =
                        c.read(
                            "SELECT table_name FROM information_schema.tables WHERE table_schema='public'",
                        ) { it.getString(1) }
                    tables.associateWith { table ->
                        c.read("SELECT * FROM \"$table\"") { r ->
                            (1..r.metaData.columnCount).map { r.getString(it) }.toString()
                        }.sorted()
                    }
                }
            val path = "/api/platform/analytics/v1?from=2026-03-28&to=2026-03-31"
            val captures = linkedMapOf<String, JsonElement>()

            fun capture(name: String) {
                val before = rows()
                val response = get(path)
                assertEquals(200, response.statusCode(), response.body())
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
                val body = Json.parseToJsonElement(response.body()).jsonObject
                assertEquals("UTC", body.getValue("period").jsonObject.getValue("timezone").jsonPrimitive.content)
                for (secret in listOf("PRIVATE_MESSAGE", "PRIVATE_CONTACT", "PRIVATE_CHAT", "RAW_PROVIDER_SECRET")) {
                    assertFalse(response.body().contains(secret))
                }
                if (name == "active") {
                    val elapsed =
                        java.time.Duration.between(
                            Instant.parse("2026-03-29T00:00:00Z"),
                            Instant.parse(body.getValue("asOf").jsonPrimitive.content),
                        )
                    val age =
                        body.getValue(
                            "metrics",
                        ).jsonObject.getValue(
                            "support.oldestActiveAge",
                        ).jsonObject.getValue("value").jsonPrimitive.content.toBigDecimal()
                    assertEquals(
                        0,
                        age.compareTo(java.math.BigDecimal(elapsed.toNanos()).divide(java.math.BigDecimal(1000000))),
                    )
                }
                captures[name] =
                    if (name == "active") body else JsonObject(body + ("asOf" to JsonPrimitive("2026-11-12T12:00:00Z")))
                assertEquals(before, rows(), "analytics GET must not write any domain table")
            }
            capture("empty")
            sql("INSERT INTO users(telegram_user_id) VALUES (7),(8),(9),(10),(99)")
            val statuses =
                listOf("PUBLISHED", "PUBLISHED", "DRAFT", "HIDDEN", "PAUSED", "SUSPENDED", "ARCHIVED", "DELETED")
            for ((index, status) in statuses.withIndex()) {
                val venue =
                    sql(
                        "INSERT INTO venues(name,status,created_at) VALUES ('Fixture $index'," +
                            "'$status',TIMESTAMP '2026-03-28 00:00:00')",
                    )
                val sub =
                    listOf(
                        "ACTIVE",
                        "TRIAL",
                        "PAST_DUE",
                        "SUSPENDED",
                        "SUSPENDED_BY_PLATFORM",
                        "CANCELED",
                        "UNKNOWN",
                        "ACTIVE",
                    )[index]
                if (index != 6) sql("INSERT INTO venue_subscriptions(venue_id,status) VALUES ($venue,'$sub')")
            }
            sql("UPDATE venues SET staff_chat_id = -123456 WHERE id = 1")
            sql("INSERT INTO venue_members(venue_id,user_id,role) VALUES (1,7,'OWNER'),(1,8,'MANAGER'),(1,9,'STAFF')")
            val currencies = listOf("IQD", "MGA", "USD", "USD", "RUB", "KWD", "CLF", "JPY")
            for ((index, currency) in currencies.withIndex()) {
                val status = if (index in listOf(0, 4)) "PAID" else "OPEN"
                val amount = if (currency == "USD") 2000000000 else 12345
                val invoice =
                    sql(
                        "INSERT INTO billing_invoices(venue_id,period_start,period_end,due_at,amount_minor,currency," +
                            "description,provider,status,created_at,paid_at) VALUES (1,DATE '2026-01-${index + 10}'," +
                            "DATE '2099-01-${index + 10}',TIMESTAMP '2026-03-28 00:00:00',$amount,'$currency'," +
                            "'PRIVATE_CONTACT','fake','$status',TIMESTAMP '2026-03-28 00:00:00'," +
                            "TIMESTAMP '2026-03-29 00:00:00')",
                    )
                if (index == 0) {
                    for (event in listOf("manual:$invoice", "duplicate")) {
                        sql(
                            "INSERT INTO billing_payments(invoice_id,provider,provider_event_id," +
                                "amount_minor,currency," +
                                "status,occurred_at,raw_payload) VALUES ($invoice,'fake','$event',12345,'IQD'," +
                                "'SUCCEEDED',TIMESTAMP '2026-03-29 00:00:00','RAW_PROVIDER_SECRET')",
                        )
                    }
                    sql(
                        "INSERT INTO billing_payments(invoice_id,provider,provider_event_id,amount_minor,currency," +
                            "status,occurred_at) VALUES ($invoice,'fake','mismatch',1,'IQD'," +
                            "'SUCCEEDED',TIMESTAMP '2026-03-29 00:00:00')",
                    )
                }
            }
            for (end in listOf("2099-03-01", "2099-02-01")) {
                sql(
                    "INSERT INTO billing_adjustments(venue_id,kind,days,reason,previous_paid_through," +
                        "new_paid_through," +
                        "actor_user_id,created_at) VALUES (1,'COURTESY_DAYS',30,'PRIVATE_CONTACT',DATE '2099-01-01'," +
                        "DATE '$end',99,TIMESTAMP '2026-03-29 00:00:00')",
                )
            }
            for (status in listOf("PENDING", "APPROVED", "REJECTED", "CANCELLED")) {
                sql(
                    "INSERT INTO venue_connection_requests(telegram_user_id,venue_name,city,contact," +
                        "status,created_at," +
                        "linked_venue_id) VALUES (7,'Fixture','City','PRIVATE_CONTACT','$status'," +
                        "TIMESTAMP '2026-03-28 00:00:00',${if (status == "APPROVED") "1" else "NULL"})",
                )
            }
            for (type in listOf("SUPPORT_TICKET", "BOOKING_THREAD", "VENUE_CHAT")) {
                val thread =
                    sql(
                        "INSERT INTO support_threads(venue_id,guest_user_id,category,status,title," +
                            "thread_type,created_at) " +
                            "VALUES (1,7,'OTHER','CLOSED','PRIVATE_MESSAGE','$type',TIMESTAMP WITH " +
                            "TIME ZONE '2026-03-29 00:00:00+00')",
                    )
                for ((role, source, minute) in listOf(
                    Triple("GUEST", "GUEST_MINIAPP", 1),
                    Triple("SYSTEM", "SYSTEM", 2),
                    Triple("PLATFORM", "PLATFORM_MINIAPP", 3),
                )) {
                    sql(
                        "INSERT INTO support_messages(thread_id,author_user_id,author_role,source,text,created_at) " +
                            "VALUES ($thread,7,'$role','$source','PRIVATE_MESSAGE',TIMESTAMP WITH " +
                            "TIME ZONE '2026-03-29 00:0$minute:00+00')",
                    )
                }
            }
            val table = sql("INSERT INTO venue_tables(venue_id,table_number) VALUES (1,1)")
            val session =
                sql(
                    "INSERT INTO table_sessions(venue_id,table_id,status,started_at,last_activity_at,expires_at) " +
                        "VALUES (1,$table,'ACTIVE',TIMESTAMP '2026-03-28 00:00:00',TIMESTAMP " +
                        "'2026-03-28 00:00:00',TIMESTAMP '2099-01-01 00:00:00')",
                )
            val order =
                sql(
                    "INSERT INTO orders(venue_id,table_id,table_session_id,status,created_at," +
                        "acceptance_history_complete) VALUES (1,$table,$session,'ACTIVE',TIMESTAMP " +
                        "'2026-03-28 00:00:00',TRUE)",
                )
            val batch =
                sql(
                    "INSERT INTO order_batches(order_id,status,source,created_at," +
                        "status_history_complete) VALUES ($order,'ACCEPTED','MINIAPP',TIMESTAMP " +
                        "'2026-03-28 00:00:00',TRUE)",
                )
            sql(
                "INSERT INTO order_batch_status_facts(batch_id,transition_sequence,order_id,venue_id," +
                    "from_status,to_status," +
                    "occurred_at,producer,channel) VALUES ($batch,1,$order,1,'NEW','ACCEPTED'," +
                    "TIMESTAMP WITH TIME ZONE '${Instant.now().minusSeconds(3600)}'," +
                    "'VENUE_ORDERS_REPOSITORY','MINIAPP')",
            )
            capture("complete")
            sql("DELETE FROM venue_subscriptions WHERE venue_id = 2")
            sql("UPDATE order_batches SET status_history_complete = FALSE")
            sql(
                "INSERT INTO support_threads(venue_id,guest_user_id,category,status,title,thread_type,created_at) " +
                    "VALUES (1,7,'OTHER','CLOSED','PRIVATE_MESSAGE','SUPPORT_TICKET',TIMESTAMP WITH " +
                    "TIME ZONE '2026-03-29 00:00:00+00')",
            )
            capture("partial")
            sql(
                "INSERT INTO support_threads(venue_id,guest_user_id,category,status,title,thread_type,created_at) " +
                    "VALUES (1,7,'OTHER','NEW','PRIVATE_MESSAGE','SUPPORT_TICKET',TIMESTAMP WITH " +
                    "TIME ZONE '2026-03-29 00:00:00+00')",
            )
            capture("active")
            val metrics = captures.getValue("complete").jsonObject.getValue("metrics").jsonObject
            assertEquals(
                "180000.000",
                metrics.getValue("support.ttfr").jsonObject.getValue("value").jsonPrimitive.content,
            )
            assertEquals("1", metrics.getValue("support.total").jsonObject.getValue("value").jsonPrimitive.content)
            assertEquals("1", metrics.getValue("venues.waav").jsonObject.getValue("value").jsonPrimitive.content)
            assertEquals(
                "1",
                metrics.getValue("invoices.manualConfirmed").jsonObject.getValue("value").jsonPrimitive.content,
            )
            for (actor in listOf(7L, 8L, 9L, 10L)) assertEquals(403, get(path, actor).statusCode())
            assertEquals(401, get(path, null).statusCode())
            for (suffix in listOf("&tableId=1", "&currency=RUB", "&section=nope", "&from=2026-01-01")) {
                assertEquals(400, get(path + suffix).statusCode())
            }
            for (section in listOf("venues", "subscriptions", "invoices", "onboarding", "support")) {
                assertEquals(200, get("$path&section=$section").statusCode())
            }
            assertEquals(403, get("/api/venue/1/stats/v1?from=2026-03-28&to=2026-03-31").statusCode())
            val actual = JsonObject(captures)
            val output = Path.of("build/test-artifacts/platform-analytics-http.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), actual))
            val fixture =
                generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                    .map { it.resolve("miniapp/e2e/fixtures/platform-analytics-http.json") }
                    .firstOrNull(Files::isRegularFile)
                    ?: error("Missing fixture; actual real HTTP capture: $output")
            val expected = Json.parseToJsonElement(Files.readString(fixture)).jsonObject

            // Active queue age is checked against the actual response asOf above, not a wall-clock golden value.
            fun stable(value: JsonObject): JsonObject =
                JsonObject(
                    value.mapValues { (key, entry) ->
                        if (key != "active") {
                            entry
                        } else {
                            JsonObject(
                                entry.jsonObject.mapValues { (field, item) ->
                                    if (field == "asOf") {
                                        JsonPrimitive("VOLATILE")
                                    } else if (field != "metrics") {
                                        item
                                    } else {
                                        JsonObject(
                                            item.jsonObject.mapValues { (metric, data) ->
                                                if (metric != "support.oldestActiveAge") {
                                                    data
                                                } else {
                                                    JsonObject(
                                                        data.jsonObject.mapValues { (property, part) ->
                                                            if (property in setOf("value", "observedValue")) {
                                                                JsonPrimitive(
                                                                    "VOLATILE",
                                                                )
                                                            } else {
                                                                part
                                                            }
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    },
                )
            assertEquals(stable(expected), stable(actual))
            // Optional local end-to-end handoff keeps this same real Ktor server alive for Chromium.
            // No production token or data: the synthetic session is scoped to this isolated H2 process.
            System.getenv("PLATFORM_ANALYTICS_LIVE_DIR")?.let { directory ->
                val dir = Path.of(directory)
                Files.createDirectories(dir)
                val before = rows()
                Files.writeString(
                    dir.resolve("ready.json"),
                    JsonObject(
                        mapOf("base" to JsonPrimitive(base), "token" to JsonPrimitive(tokens.issueToken(99).token)),
                    ).toString(),
                )
                val deadline = System.nanoTime() + 180_000_000_000L
                while (!Files.exists(dir.resolve("done")) && System.nanoTime() < deadline) Thread.sleep(100)
                assertTrue(Files.exists(dir.resolve("done")), "Chromium live HTTP handoff timed out")
                assertEquals(before, rows(), "Live browser analytics reads must not write")
                Files.deleteIfExists(dir.resolve("ready.json"))
            }
        } finally {
            server.stop(100, 1000)
        }
    }
}
