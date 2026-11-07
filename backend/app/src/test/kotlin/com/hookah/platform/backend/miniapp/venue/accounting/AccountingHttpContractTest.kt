package com.hookah.platform.backend.miniapp.venue.accounting

import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.orders.OrderActionActor
import com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.module
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountingHttpContractTest {
    @Test
    fun `actual HTTP defaults and receipt responses match the browser contract fixture`() =
        testApplication {
            val url =
                "jdbc:h2:mem:accounting_wire_${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
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
            val token = SessionTokenService(SessionTokenConfig.from(config, "test")).issueToken(103).token
            val captured = linkedMapOf<String, JsonElement>()

            suspend fun read(path: String): JsonElement {
                val response =
                    client.get("/api/venue/1/accounting$path") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                return Json.parseToJsonElement(response.bodyAsText())
            }

            suspend fun send(command: AccountingCommand): JsonElement {
                val response =
                    client.post("/api/venue/1/accounting/commands") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(command))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                return Json.parseToJsonElement(response.bodyAsText())
            }
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    username = "sa"
                    password = ""
                },
            ).use { ds ->
                seedAccounting(ds)
                captured["empty"] =
                    read("").also {
                        assertEquals(JsonNull, it.jsonObject.getValue("current"))
                        assertEquals(JsonArray(emptyList()), it.jsonObject.getValue("history"))
                    }
                val opened = send(command("OPEN"))
                val shift = Json.decodeFromJsonElement(AccountingReport.serializer(), opened)
                captured["created"] = send(manual(shift.shiftId))
                val created = Json.decodeFromJsonElement(AccountingReport.serializer(), captured.getValue("created"))
                val sale = created.sales.single()
                val wireSale = captured.getValue("created").jsonObject.getValue("sales").jsonArray.single().jsonObject
                assertEquals(JsonPrimitive(true), wireSale.getValue("orderClosed"))
                assertEquals(JsonArray(emptyList()), wireSale.getValue("payments"))
                val wireLine = wireSale.getValue("lines").jsonArray.single().jsonObject
                for (key in listOf("baseDiscountMinor", "baseGiftMinor", "discountMinor")) {
                    assertEquals(JsonPrimitive(0), wireLine.getValue(key))
                }
                assertEquals(JsonNull, wireLine.getValue("giftReason"))
                assertEquals(JsonPrimitive(10000), wireLine.getValue("unitPriceMinor"))
                captured["current"] = read("")
                captured["detail"] = read("/${shift.shiftId}")
                assertEquals(captured["created"], captured["detail"])
                val pay =
                    command("PAY", shift.shiftId, sale).copy(
                        requestId = "contract-payment-01",
                        paymentMethod = "CASH",
                        amountMinor = 4000,
                    )
                val invalid =
                    client.post("/api/venue/1/accounting/commands") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(pay.copy(amountMinor = -1)))
                    }
                assertEquals(HttpStatusCode.Conflict, invalid.status)
                assertTrue(invalid.bodyAsText().contains("ACCOUNTING_CONFLICT"))
                captured["paid"] = send(pay)
                assertEquals(captured["paid"], send(pay))
                val paid = Json.decodeFromJsonElement(AccountingReport.serializer(), captured.getValue("paid"))
                assertEquals(4000, paid.totals.paidMinor)
                assertEquals(6000, paid.totals.unpaidMinor)
                assertEquals(
                    JsonNull,
                    captured.getValue("paid").jsonObject.getValue("sales").jsonArray.single()
                        .jsonObject.getValue("payments").jsonArray.single().jsonObject.getValue("voidReason"),
                )
                captured["settled"] =
                    send(
                        command("PAY", shift.shiftId, paid.sales.single()).copy(
                            requestId = "contract-payment-02", paymentMethod = "CARD", amountMinor = 6000,
                        ),
                    )
                seedDigitalOrder(ds, 31, closed = false)
                ds.connection.use { it.exec("UPDATE orders SET accounting_shift_id = ${shift.shiftId} WHERE id = 31") }
                captured["digitalActive"] = read("/${shift.shiftId}")
                VenueOrdersRepository(ds).updateOrderStatus(
                    1,
                    31,
                    OrderWorkflowStatus.CLOSED,
                    OrderActionActor(103, VenueRole.STAFF),
                )
                captured["digitalClosed"] = read("/${shift.shiftId}")
                for ((key, closed) in listOf("digitalActive" to false, "digitalClosed" to true)) {
                    val order =
                        captured.getValue(key).jsonObject.getValue("sales").jsonArray
                            .single { it.jsonObject.getValue("id") == JsonPrimitive("order:31") }.jsonObject
                    assertEquals(JsonPrimitive(closed), order.getValue("orderClosed"))
                    assertEquals(JsonPrimitive(0), order.getValue("version"))
                }
                val replacements =
                    mapOf(sale.id to "manual:http-contract", sale.lines.single().id to "line:http-contract")
                val actual = normalize(JsonObject(captured), replacements)
                val generated = Path.of("build/test-artifacts/accounting-http.json")
                Files.createDirectories(generated.parent)
                Files.writeString(generated, Json { prettyPrint = true }.encodeToString(actual))
                val fixture =
                    generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                        .map { it.resolve("miniapp/e2e/fixtures/accounting-http.json") }
                        .firstOrNull(Files::isRegularFile)
                        ?: error("Missing browser fixture; actual HTTP capture is at $generated")
                assertEquals(Json.parseToJsonElement(Files.readString(fixture)), actual)
            }
        }

    // Normalize only generated identity/time/hash, never money, defaults or response structure.
    private fun normalize(
        value: JsonElement,
        replacements: Map<String, String>,
        key: String = "",
    ): JsonElement =
        when (value) {
            is JsonObject -> JsonObject(value.mapValues { (name, child) -> normalize(child, replacements, name) })
            is JsonArray -> JsonArray(value.map { normalize(it, replacements) })
            is JsonPrimitive ->
                when {
                    value == JsonNull || !value.isString -> value
                    key == "reportHash" -> JsonPrimitive("http-contract-hash")
                    key in setOf("openedAt", "closedAt", "createdAt", "recordedAt") ->
                        JsonPrimitive("2026-11-07T20:55:00Z")
                    else -> replacements[value.jsonPrimitive.content]?.let(::JsonPrimitive) ?: value
                }
        }
}
