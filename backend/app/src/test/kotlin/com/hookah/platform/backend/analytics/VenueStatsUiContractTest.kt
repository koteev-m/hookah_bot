package com.hookah.platform.backend.analytics

import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingReport
import com.hookah.platform.backend.miniapp.venue.accounting.AccountingTotals
import com.hookah.platform.backend.module
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class VenueStatsUiContractTest {
    @Test
    fun `real currency minor units response matches the money screen fixture without rescaling`() =
        testApplication {
            val url =
                "jdbc:h2:mem:stats_money_${UUID.randomUUID()};MODE=PostgreSQL;" +
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
                )
            environment { this.config = config }
            application { module() }
            client.get("/health")
            val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))
            val values =
                linkedMapOf(
                    "IQD" to 12345L, "MGA" to 12345L, "RUB" to 900719925474099312L,
                    "EUR" to Long.MAX_VALUE, "USD" to 1L, "JPY" to 12345L, "KRW" to 0L,
                    "KWD" to 12345L, "BHD" to 12345L, "CLF" to 12345L, "XXX" to 12345L,
                )
            DriverManager.getConnection(url, "sa", "").use { c ->
                c.createStatement().use { s ->
                    s.executeUpdate("INSERT INTO venues(name,status) VALUES ('Money fixture','PUBLISHED')")
                    s.executeUpdate("INSERT INTO users(telegram_user_id) VALUES (7)")
                    s.executeUpdate("INSERT INTO venue_members(venue_id,user_id,role) VALUES (1,7,'OWNER')")
                    s.executeUpdate("INSERT INTO venue_settings(venue_id,timezone) VALUES (1,'Europe/Berlin')")
                }
                values.entries.forEachIndexed { index, (currency, amount) ->
                    val report =
                        AccountingReport(
                            (index + 1).toLong(), 1, "CLOSED", "Europe/Berlin",
                            "2026-03-28T00:00:00Z", "2026-03-29T00:00:00Z", currency, emptyList(),
                            AccountingTotals(1, 1, amount, 0, 0, amount, 0, amount, 0, 0, 0),
                            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                        )
                    c.prepareStatement(
                        "INSERT INTO venue_accounting_shifts(venue_id,status,timezone," +
                            "opened_at,closed_at,final_report) " +
                            "VALUES (1,'CLOSED','Europe/Berlin',TIMESTAMP WITH TIME ZONE '2026-03-28 00:00:00+00'," +
                            "TIMESTAMP WITH TIME ZONE '2026-03-29 00:00:00+00',?)",
                    ).use { s ->
                        s.setString(1, Json.encodeToString(report))
                        s.executeUpdate()
                    }
                }
            }
            val response =
                client.get("/api/venue/1/stats/v1?from=2026-03-28&to=2026-03-31") {
                    header(HttpHeaders.Authorization, "Bearer ${tokens.issueToken(7).token}")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val actual = JsonObject(body + ("asOf" to JsonPrimitive("2026-11-10T12:00:00Z")))
            val output = Path.of("build/test-artifacts/venue-stats-money-http.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, Json { prettyPrint = true }.encodeToString(actual))
            val fixture =
                generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                    .map { it.resolve("miniapp/e2e/fixtures/venue-stats-money-http.json") }
                    .firstOrNull(Files::isRegularFile)
                    ?: error("Missing browser money fixture; real capture is at $output")
            assertEquals(Json.parseToJsonElement(Files.readString(fixture)), actual)
        }

    @Test
    fun `real stats HTTP responses match the Venue UI fixture including legacy currencies and DST`() =
        testApplication {
            val url =
                "jdbc:h2:mem:stats_ui_${UUID.randomUUID()};MODE=PostgreSQL;" +
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
            environment { this.config = config }
            application { module() }
            client.get("/health")
            val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))
            val stamp = "TIMESTAMP WITH TIME ZONE"

            fun sql(value: String): Long =
                DriverManager.getConnection(url, "sa", "").use { c ->
                    c.prepareStatement(value, Statement.RETURN_GENERATED_KEYS).use { s ->
                        s.executeUpdate()
                        s.generatedKeys.use { r -> if (r.next()) r.getLong(1) else 0 }
                    }
                }
            val venue = sql("INSERT INTO venues(name,status) VALUES ('Микс','PUBLISHED')")
            sql("INSERT INTO users(telegram_user_id) VALUES (7),(8),(9),(10),(99)")
            sql("INSERT INTO venue_settings(venue_id,timezone) VALUES ($venue,'Europe/Berlin')")
            sql(
                "INSERT INTO venue_members(venue_id,user_id,role) " +
                    "VALUES ($venue,7,'OWNER'),($venue,8,'MANAGER'),($venue,9,'STAFF')",
            )
            val path = "/api/venue/$venue/stats/v1?from=2026-03-28&to=2026-03-31"

            suspend fun capture(suffix: String = ""): JsonObject {
                val response =
                    client.get(path + suffix) {
                        header(HttpHeaders.Authorization, "Bearer ${tokens.issueToken(7).token}")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                // Normalize only the current server clock, never facts, money, coverage, period or response defaults.
                return JsonObject(body + ("asOf" to JsonPrimitive("2026-11-10T12:00:00Z")))
            }
            val captures = linkedMapOf<String, JsonElement>()
            captures["empty"] = capture()
            val table = sql("INSERT INTO venue_tables(venue_id,table_number) VALUES ($venue,1)")
            val moved = sql("INSERT INTO venue_tables(venue_id,table_number) VALUES ($venue,2)")
            val session =
                sql(
                    "INSERT INTO table_sessions(venue_id,table_id,status,started_at,last_activity_at,expires_at) " +
                        "VALUES ($venue,$table,'ACTIVE',$stamp '2026-03-28 10:00:00+00'," +
                        "$stamp '2026-03-28 10:00:00+00',$stamp '2030-01-01 00:00:00+00')",
                )
            val order =
                sql(
                    "INSERT INTO orders(venue_id,table_id,table_session_id,status,created_at,first_accepted_at," +
                        "acceptance_history_complete) VALUES ($venue,$table,$session,'ACTIVE'," +
                        "$stamp '2026-03-28 10:00:00+00',$stamp '2026-03-29 11:00:00+00',TRUE)",
                )
            val category = sql("INSERT INTO menu_categories(venue_id,name) VALUES ($venue,'Напитки')")
            val item =
                sql(
                    "INSERT INTO menu_items(venue_id,category_id,name,price_minor) " +
                        "VALUES ($venue,$category,'Новое имя',99999)",
                )
            for (index in 1..3) {
                val delivered = if (index <= 2) "$stamp '2026-03-29 12:00:00+00'" else "NULL"
                val batch =
                    sql(
                        "INSERT INTO order_batches(order_id,source,status,created_at,first_accepted_at," +
                            "first_delivered_at,status_history_complete) VALUES ($order,'MINIAPP'," +
                            "'${if (index <= 2) "DELIVERED" else "REJECTED"}',$stamp '2026-03-28 10:00:00+00'," +
                            "$stamp '2026-03-29 11:00:00+00',$delivered,TRUE)",
                    )
                for ((sequence, status) in listOf(
                    "ACCEPTED",
                    if (index <= 2) "DELIVERED" else "REJECTED",
                ).withIndex()) {
                    sql(
                        "INSERT INTO order_batch_status_facts(batch_id,transition_sequence,order_id,venue_id," +
                            "from_status,to_status,occurred_at,producer,channel) VALUES ($batch,${sequence + 1}," +
                            "$order,$venue,'${if (sequence == 0) "NEW" else "ACCEPTED"}','$status'," +
                            "$stamp '2026-03-29 ${if (sequence == 0) "11" else "12"}:00:00+00'," +
                            "'HTTP_FIXTURE','UNKNOWN')",
                    )
                }
                sql(
                    "INSERT INTO order_batch_items(order_batch_id,menu_item_id,qty,item_name_snapshot," +
                        "base_unit_price_minor_snapshot,currency_snapshot) " +
                        "VALUES ($batch,$item,2,'Чай из заказа',100,'RUB')",
                )
            }
            for ((index, status) in listOf(
                "SEATED",
                "NO_SHOW",
                "CHANGED",
                "CANCELED",
                "EXPIRED",
                "CONFIRMED",
            ).withIndex()) {
                sql(
                    "INSERT INTO bookings(venue_id,user_id,scheduled_at,status,created_at,display_date," +
                        "display_number) VALUES ($venue,7,$stamp '2026-03-29 12:00:00+00','$status'," +
                        "$stamp '${if (index == 0) "2026-03-28" else "2026-03-01"} 00:00:00+00'," +
                        "DATE '2026-03-29',${index + 1})",
                )
            }
            val visit =
                sql(
                    "INSERT INTO visits(venue_id,table_session_id,source,occurred_at) " +
                        "VALUES ($venue,$session,'WALK_IN',$stamp '2026-03-28 10:00:00+00')",
                )
            sql(
                "INSERT INTO table_seatings(venue_id,table_id,visit_id,table_session_id,party_size,started_at," +
                    "created_by_user_id) VALUES ($venue,$moved,$visit,$session,4," +
                    "$stamp '2026-03-28 10:00:00+00',7)",
            )
            for ((status, kind) in listOf(
                "ACK" to null,
                "DONE" to "OPERATOR",
                "DONE" to "ORDER_CLOSED",
                "CANCELLED" to null,
            )) {
                val ack = if (status == "ACK" || kind == "OPERATOR") "$stamp '2026-03-29 10:05:00+00'" else "NULL"
                val completed = if (status == "DONE") "$stamp '2026-03-29 10:10:00+00'" else "NULL"
                val cancelled = if (status == "CANCELLED") "$stamp '2026-03-29 10:10:00+00'" else "NULL"
                sql(
                    "INSERT INTO staff_calls(venue_id,table_id,order_id,reason,status,created_at," +
                        "status_history_complete," +
                        "acknowledged_at,completed_at,completion_kind,cancelled_at,cancellation_kind) " +
                        "VALUES ($venue,$table,$order,'BILL','$status',$stamp '2026-03-29 10:00:00+00'," +
                        "TRUE,$ack,$completed,${kind?.let { "'$it'" } ?: "NULL"},$cancelled," +
                        "${if (status == "CANCELLED") "'ORDER_CLOSED'" else "NULL"})",
                )
            }
            for (currency in listOf("RUB", "USD")) {
                val accrued = if (currency == "RUB") 900719925474099312L else 12345L
                val report =
                    AccountingReport(
                        1, venue, "CLOSED", "Europe/Berlin", "2026-03-28T00:00:00Z", "2026-03-29T00:00:00Z", currency,
                        emptyList(),
                        AccountingTotals(2, 1, accrued + 50, 20, 30, accrued, 400, accrued - 400, 200, 100, 100),
                        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                    )
                DriverManager.getConnection(url, "sa", "").use { c ->
                    c.prepareStatement(
                        "INSERT INTO venue_accounting_shifts(venue_id,status,timezone,opened_at," +
                            "closed_at,final_report) " +
                            "VALUES ($venue,'CLOSED','Europe/Berlin',$stamp '2026-03-28 00:00:00+00'," +
                            "$stamp '2026-03-29 00:00:00+00',?)",
                    ).use { s ->
                        s.setString(1, Json.encodeToString(report))
                        s.executeUpdate()
                    }
                }
            }
            sql(
                "INSERT INTO venue_accounting_shifts(venue_id,status,open_slot,timezone,opened_at) " +
                    "VALUES ($venue,'OPEN',1,'Europe/Berlin',$stamp '2026-03-30 00:00:00+00')",
            )
            captures["complete"] = capture()
            captures["ordersAtTable"] = capture("&section=orders&tableId=$table")
            captures["seatingsAfterMove"] = capture("&section=seatings&tableId=$moved")
            val period = captures.getValue("complete").jsonObject.getValue("period").jsonObject
            assertEquals(JsonPrimitive("2026-03-27T23:00:00Z"), period["from"])
            assertEquals(JsonPrimitive("2026-03-30T22:00:00Z"), period["to"])
            sql("UPDATE orders SET acceptance_history_complete=FALSE WHERE id=$order")
            sql("UPDATE order_batches SET status_history_complete=FALSE WHERE order_id=$order")
            sql("UPDATE staff_calls SET status_history_complete=FALSE WHERE status='ACK'")
            sql(
                "INSERT INTO venue_accounting_shifts(venue_id,status,timezone,opened_at," +
                    "closed_at,final_report) " +
                    "VALUES ($venue,'CLOSED','Europe/Berlin',$stamp '2026-03-28 00:00:00+00'," +
                    "$stamp '2026-03-29 00:00:00+00','{}')",
            )
            captures["partial"] = capture()
            for (user in listOf(8L, 9L, 10L, 99L)) {
                assertEquals(
                    if (user == 8L) HttpStatusCode.OK else HttpStatusCode.Forbidden,
                    client.get(
                        path,
                    ) { header(HttpHeaders.Authorization, "Bearer ${tokens.issueToken(user).token}") }.status,
                )
            }
            val actual = JsonObject(captures)
            val output = Path.of("build/test-artifacts/venue-stats-http.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, Json { prettyPrint = true }.encodeToString(actual))
            val fixture =
                generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                    .map { it.resolve("miniapp/e2e/fixtures/venue-stats-http.json") }.firstOrNull(Files::isRegularFile)
                    ?: error("Missing browser fixture; real capture is at $output")
            assertEquals(Json.parseToJsonElement(Files.readString(fixture)), actual)
        }
}
