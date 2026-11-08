package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.media.FakeInfoStorage
import com.hookah.platform.backend.miniapp.venue.staff.syntheticPhoto
import com.hookah.platform.backend.moduleWithOverrides
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeatingHttpContractTest {
    @Test
    fun `real HTTP defaults offline booking private images public availability and role boundaries`() =
        testApplication {
            val url =
                "jdbc:h2:mem:hall_http_${UUID.randomUUID()};MODE=PostgreSQL;" +
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
            val storage = FakeInfoStorage()
            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(venueInfoMediaStorage = storage, seatingClock = seatingClock),
                )
            }
            client.get("/health")
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    username = "sa"
                    password = ""
                },
            ).use { ds ->
                val (venue, tables) = seedSeating(ds)
                val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))

                fun token(actor: Long) = tokens.issueToken(actor).token
                val root = "/api/venue/$venue/hall"

                suspend fun read(): JsonObject {
                    val response = client.get(root) { bearerAuth(token(OWNER)) }
                    assertEquals(HttpStatusCode.OK, response.status)
                    return Json.parseToJsonElement(response.bodyAsText()).jsonObject
                }

                suspend fun send(
                    command: SeatingCommand,
                    actor: Long = OWNER,
                    status: HttpStatusCode = HttpStatusCode.OK,
                ): SeatingResult? {
                    val response =
                        client.post("$root/commands") {
                            bearerAuth(token(actor))
                            contentType(ContentType.Application.Json)
                            setBody(seatingJson.encodeToString(command))
                        }
                    assertEquals(status, response.status, response.bodyAsText())
                    return if (status == HttpStatusCode.OK) {
                        seatingJson.decodeFromString(
                            response.bodyAsText(),
                        )
                    } else {
                        null
                    }
                }
                val empty = read()
                assertEquals(JsonPrimitive(false), empty.getValue("rules").jsonObject.getValue("usePlan"))
                assertEquals(JsonPrimitive(0), empty.getValue("rules").jsonObject.getValue("bufferMinutes"))
                assertEquals(JsonNull, empty.getValue("tables").jsonArray.first().jsonObject.getValue("layout"))
                assertTrue(empty.getValue("bookings").jsonArray.isEmpty())
                val request =
                    cmd(
                        "MANUAL_BOOKING",
                    ).copy(
                        tableId = tables[1],
                        localStart = "2030-01-07T20:00",
                        partySize = 2,
                        name = "Synthetic caller",
                        phone = "+70000000000",
                    )
                val saved = send(request, STAFF)
                assertEquals(saved, send(request, STAFF))
                val booked = read()
                assertEquals(
                    "CONFIRMED",
                    seatingJson.decodeFromJsonElement(HallState.serializer(), booked).bookings.single().status,
                )
                send(cmd("HALL").copy(name = "Forbidden"), STAFF, HttpStatusCode.Forbidden)
                val hall = send(cmd("HALL").copy(name = "Main"))!!.id
                send(cmd("HALL").copy(name = "Main"), OWNER, HttpStatusCode.Conflict)
                val uploaded =
                    client.post("$root/$hall/plans") {
                        bearerAuth(token(OWNER))
                        contentType(ContentType.Image.PNG)
                        header("Idempotency-Key", UUID.randomUUID().toString())
                        setBody(syntheticPhoto())
                    }
                assertEquals(HttpStatusCode.OK, uploaded.status, uploaded.bodyAsText())
                val image = seatingJson.decodeFromString<HallPlanUpload>(uploaded.bodyAsText())
                send(cmd("PLAN").copy(id = hall, revision = 0, planId = image.id, confirmPlanReplacement = true))
                for ((index, table) in tables.withIndex()) send(
                    cmd(
                        "TABLE",
                    ).copy(
                        id = table,
                        revision = 1,
                        number = listOf(1, 3, 5)[index],
                        capacity = 4,
                        hallId = hall,
                        layout = TableLayout(.2 + .3 * index, .5, .15, .2),
                    ),
                )
                val beforeRules = seatingJson.decodeFromJsonElement(HallState.serializer(), read())
                send(
                    cmd(
                        "RULES",
                    ).copy(
                        revision = beforeRules.rules.revision,
                        rules = beforeRules.rules.copy(usePlan = true, guestTableChoice = true),
                    ),
                )
                val public =
                    client.get(
                        "/api/guest/venues/$venue/table-availability?localStart=2030-01-07T18:30&partySize=2",
                    ) {
                        bearerAuth(token(1003))
                    }
                assertEquals(HttpStatusCode.OK, public.status, public.bodyAsText())
                val publicBody = public.bodyAsText()
                assertFalse(publicBody.contains("Synthetic caller"))
                assertFalse(publicBody.contains("+70000000000"))
                assertFalse(publicBody.contains("venue-info/"))
                val available = seatingJson.decodeFromString<TableAvailability>(publicBody)
                assertEquals("UNAVAILABLE", available.tables.single { it.id == tables[1] }.state)
                val guestCommand =
                    cmd(
                        "GUEST_BOOKING",
                    ).copy(tableId = tables[1], localStart = "2030-01-07T18:30", partySize = 2)
                val conflict =
                    client.post("/api/guest/venues/$venue/table-bookings") {
                        bearerAuth(token(1003))
                        contentType(ContentType.Application.Json)
                        setBody(seatingJson.encodeToString(guestCommand))
                    }
                assertEquals(HttpStatusCode.Conflict, conflict.status)
                val imageResponse =
                    client.get(
                        "/api/guest/venues/$venue/hall/$hall/plans/${image.id}",
                    ) { bearerAuth(token(1003)) }
                assertEquals(HttpStatusCode.OK, imageResponse.status)
                val ready = seatingJson.decodeFromJsonElement(HallState.serializer(), read())
                send(cmd("RULES").copy(revision = ready.rules.revision, rules = ready.rules.copy(usePlan = false)))
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.get("/api/guest/venues/$venue/hall/$hall/plans/${image.id}") {
                        bearerAuth(token(1003))
                    }.status,
                )
                assertEquals(
                    saved!!.id,
                    seatingJson.decodeFromJsonElement(HallState.serializer(), read()).bookings.single().id,
                )
                val capture = JsonObject(mapOf("empty" to empty, "booked" to booked))
                val path = Path.of("build/test-artifacts/seating-http.json")
                Files.createDirectories(path.parent)
                Files.writeString(path, seatingJson.encodeToString(capture))
                val fixture =
                    generateSequence(Path.of("").toAbsolutePath()) {
                        it.parent
                    }.map { it.resolve("miniapp/e2e/fixtures/seating-http.json") }.firstOrNull { Files.exists(it) }
                assertTrue(fixture != null, "Real HTTP fixture must be present for the Mini App contract")
                assertEquals(Json.parseToJsonElement(Files.readString(fixture)), capture)
            }
        }
}
