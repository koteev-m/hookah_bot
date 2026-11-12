package com.hookah.platform.backend.analytics

import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.DriverManager
import java.sql.Statement
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KpiRoutesTest {
    @Test
    fun `versioned APIs enforce identities filters read-only and preserve legacy contract`() =
        testApplication {
            val url =
                "jdbc:h2:mem:kpi_http_${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            val config =
                MapApplicationConfig(
                    "app.env" to "test",
                    "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to url,
                    "db.user" to "sa",
                    "db.password" to "",
                    "telegram.platformOwnerId" to "99",
                )
            environment { this.config = config }
            application { module() }
            client.get("/health")

            fun sql(value: String): Long =
                DriverManager.getConnection(url, "sa", "").use { c ->
                    c.prepareStatement(value, Statement.RETURN_GENERATED_KEYS).use { s ->
                        s.executeUpdate()
                        s.generatedKeys.use { if (it.next()) it.getLong(1) else 0 }
                    }
                }
            val venue = sql("INSERT INTO venues(name,status) VALUES ('Venue','PUBLISHED')")
            val foreign = sql("INSERT INTO venues(name,status) VALUES ('Foreign','PUBLISHED')")
            sql("INSERT INTO users(telegram_user_id) VALUES (7),(8),(9),(10),(99)")
            sql(
                "INSERT INTO venue_members(venue_id,user_id,role) VALUES " +
                    "($venue,7,'OWNER'),($venue,8,'MANAGER'),($venue,9,'STAFF')",
            )
            val base = LocalDate.now(ZoneOffset.UTC).minusDays(10)
            val longEnd = base.plusDays(40)
            val shortEnd = base.plusDays(1)
            val invoice =
                sql(
                    "INSERT INTO billing_invoices(venue_id,period_start,period_end,due_at,amount_minor,currency," +
                        "description,provider,status,created_at,paid_at) VALUES ($venue,DATE '${base.minusDays(30)}'," +
                        "DATE '$base',TIMESTAMP '2020-01-01',100,'RUB','test','fake','PAID'," +
                        "TIMESTAMP '2020-01-01',TIMESTAMP '2020-01-01')",
                )
            for ((days, end) in listOf(40 to longEnd, 1 to shortEnd)) {
                sql(
                    "INSERT INTO billing_adjustments(venue_id,kind,days,reason,previous_paid_through," +
                        "new_paid_through,actor_user_id,created_at) VALUES ($venue,'COURTESY_DAYS',$days,'test'," +
                        "DATE '$base',DATE '$end',99,TIMESTAMP '2020-01-01')",
                )
            }
            for (event in listOf("manual:$invoice", "second", "third")) {
                sql(
                    "INSERT INTO billing_payments(invoice_id,provider,provider_event_id,amount_minor,currency," +
                        "status,occurred_at) VALUES ($invoice,'fake','$event',100,'RUB','SUCCEEDED'," +
                        "TIMESTAMP '2020-01-01')",
                )
            }

            fun billingRows() =
                DriverManager.getConnection(url, "sa", "").use { c ->
                    listOf("billing_adjustments", "billing_invoices", "billing_payments").map { table ->
                        c.read("SELECT * FROM $table") { r ->
                            (1..r.metaData.columnCount).map { r.getString(it) }
                        }
                    }
                }
            val beforeBilling = billingRows()
            val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))
            val path = "/api/venue/$venue/stats/v1?from=2026-11-01&to=2026-11-11"
            for (user in listOf(7L, 8L, 9L, 10L, 99L)) {
                val response =
                    client.get(
                        path,
                    ) { header(HttpHeaders.Authorization, "Bearer ${tokens.issueToken(user).token}") }
                assertEquals(
                    if (user in listOf(7L, 8L)) HttpStatusCode.OK else HttpStatusCode.Forbidden,
                    response.status,
                    response.bodyAsText(),
                )
                if (response.status == HttpStatusCode.OK) {
                    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    assertEquals(KPI_DEFINITION_VERSION, body.getValue("definitionVersion").jsonPrimitive.content)
                    assertFalse(body.toString().contains("chatId"))
                    assertFalse(body.toString().contains("telegram_user_id"))
                }
            }
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
            val owner = tokens.issueToken(7).token
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get(path.replace("/$venue/", "/$foreign/")) {
                    header(HttpHeaders.Authorization, "Bearer $owner")
                }.status,
            )
            for (suffix in listOf(
                "&source=MINIAPP",
                "&from=2026-01-01",
                "&tableId=1",
                "&section=nope",
                "&paymentMethod=CASH",
            )) {
                assertEquals(
                    HttpStatusCode.BadRequest,
                    client.get(path + suffix) {
                        header(HttpHeaders.Authorization, "Bearer $owner")
                    }.status,
                )
            }
            for (user in listOf(7L, 9L, 10L, 99L)) {
                val response =
                    client.get("/api/platform/analytics/v1?from=2026-11-01&to=2026-11-11") {
                        header(HttpHeaders.Authorization, "Bearer ${tokens.issueToken(user).token}")
                    }
                assertEquals(
                    if (user == 99L) HttpStatusCode.OK else HttpStatusCode.Forbidden,
                    response.status,
                    response.bodyAsText(),
                )
            }
            repeat(2) {
                val response =
                    client.get("/api/platform/analytics/v1?from=2026-11-01&to=2026-11-11") {
                        header(HttpHeaders.Authorization, "Bearer ${tokens.issueToken(99).token}")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val effective =
                    body.getValue("breakdowns").jsonObject
                        .getValue("subscriptions.effectivePaidThrough").jsonArray
                assertTrue(effective.any { it.jsonObject.getValue("key").jsonPrimitive.content == longEnd.toString() })
                assertFalse(
                    effective.any { it.jsonObject.getValue("key").jsonPrimitive.content == shortEnd.toString() },
                )
                val multiple =
                    body.getValue(
                        "metrics",
                    ).jsonObject.getValue("invoices.multipleMatchingSuccesses").jsonObject
                assertEquals("1", multiple.getValue("value").jsonPrimitive.content)
            }
            assertEquals(beforeBilling, billingRows())
            val old =
                client.get(
                    "/api/venue/$venue/stats?period=today",
                ) { header(HttpHeaders.Authorization, "Bearer $owner") }
            assertEquals(HttpStatusCode.OK, old.status)
            assertEquals(
                setOf(
                    "venueId",
                    "period",
                    "periodTitle",
                    "periodStart",
                    "ordersCount",
                    "revenueMinor",
                    "averageCheckMinor",
                    "discountMinor", "cancelledItemsCount", "currency", "topItems",
                ),
                Json.parseToJsonElement(old.bodyAsText()).jsonObject.keys,
            )
            sql("DELETE FROM venue_members WHERE venue_id = $venue AND user_id = 7")
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get(path) { header(HttpHeaders.Authorization, "Bearer $owner") }.status,
            )
            DriverManager.getConnection(url, "sa", "").use { c ->
                for (table in listOf("venue_subscriptions", "analytics_events", "venue_accounting_commands")) {
                    c.createStatement().use { s ->
                        s.executeQuery("SELECT COUNT(*) FROM $table").use { r ->
                            assertTrue(r.next())
                            assertEquals(0, r.getLong(1), table)
                        }
                    }
                }
            }
        }
}
