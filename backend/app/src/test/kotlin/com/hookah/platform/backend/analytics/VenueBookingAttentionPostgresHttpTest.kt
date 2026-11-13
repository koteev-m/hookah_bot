package com.hookah.platform.backend.analytics

import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VenueBookingAttentionPostgresHttpTest {
    @Test
    fun `booking attention exact thread HTTP preserves isolation read markers and unseen new messages`() {
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
            val venue = insert("INSERT INTO venues(name,status) VALUES ('Attention fixture','PUBLISHED')")
            val foreign = insert("INSERT INTO venues(name,status) VALUES ('Foreign','PUBLISHED')")
            exec("INSERT INTO users(telegram_user_id) VALUES (7),(8),(9),(10),(99)")
            exec(
                "INSERT INTO venue_members(venue_id,user_id,role) VALUES ($venue,7,'OWNER'),($venue,9," +
                    "'MANAGER'),($venue,10,'STAFF')",
            )
            exec("INSERT INTO venue_settings(venue_id,timezone) VALUES ($venue,'UTC'),($foreign,'UTC')")
            exec("INSERT INTO venue_booking_settings(venue_id) VALUES ($venue),($foreign)")
            val unrelated =
                insert(
                    "INSERT INTO support_threads(venue_id,guest_user_id,category,status,thread_type," +
                        "assignee_scope,created_source,title) VALUES ($venue,8,'OTHER','OPEN','VENUE_CHAT'," +
                        "'VENUE','GUEST_BOT','Other')",
                )
            val repo = SupportThreadRepository(ds)
            var display = 0

            fun booking(
                v: Long,
                status: String,
            ) = insert(
                "INSERT INTO bookings(venue_id,user_id,scheduled_at,status,display_date,display_number) " +
                    "VALUES ($v,8,CURRENT_TIMESTAMP + INTERVAL '1 day','$status',CURRENT_DATE,${++display})",
            )

            fun send(
                b: Long,
                v: Long,
                text: String,
            ) = runBlocking {
                repo.addBookingMessage(
                    b,
                    8,
                    SupportMessageAuthorRole.GUEST,
                    SupportMessageSource.GUEST_BOT,
                    text,
                    expectedGuestUserId = 8,
                    expectedVenueId = v,
                )!!
            }
            val foreignMessage = send(booking(foreign, "SEATED"), foreign, "Foreign private text")
            val targets = linkedMapOf<String, JsonObject>()
            val ids = linkedMapOf<String, Long>()
            val threads = linkedMapOf<String, Long>()
            for (status in listOf("SEATED", "CONFIRMED", "CHANGED", "PENDING", "CLOSED", "RESOLVED")) {
                val b = booking(venue, if (status in setOf("CLOSED", "RESOLVED")) "CONFIRMED" else status)
                ids[status] = b
                if (status == "PENDING") continue
                val text = "Локальный текст $status"
                val message = send(b, venue, text)
                if (status in setOf("CLOSED", "RESOLVED")) {
                    exec(
                        "UPDATE support_threads SET status='$status' WHERE id=${message.thread.id}",
                    )
                }
                threads[status] = message.thread.id
                targets[status] =
                    buildJsonObject {
                        put("bookingId", b)
                        put("threadId", message.thread.id)
                        put("text", text)
                    }
            }
            assertNotEquals(ids.getValue("SEATED"), threads.getValue("SEATED"))
            repeat(101) { send(booking(venue, "CONFIRMED"), venue, "Соседний разговор $it") }
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
                val base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().single().port }}"
                val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))
                val http = HttpClient.newHttpClient()

                fun get(
                    path: String,
                    user: Long = 7,
                    expected: Int = 200,
                ): JsonObject {
                    val response =
                        http.send(
                            HttpRequest.newBuilder(
                                URI.create(base + path),
                            ).header("Authorization", "Bearer ${tokens.issueToken(user).token}").build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    assertEquals(expected, response.statusCode(), path)
                    return Json.parseToJsonElement(response.body()).jsonObject
                }

                fun fingerprint() =
                    ds.connection.use { c ->
                        c.read("SELECT tablename FROM pg_tables WHERE schemaname=current_schema() ORDER BY tablename") {
                            it.getString(1)
                        }.associateWith {
                                table ->
                            c.read(
                                "SELECT md5(COALESCE(string_agg(v,',' ORDER BY v),'')) FROM " +
                                    "(SELECT row_to_json(t)::text v FROM \"$table\" t) x",
                            ) {
                                it.getString(1)
                            }.single()
                        }
                    }

                fun marker(
                    thread: Long,
                    user: Long,
                ) = ds.connection.use { c ->
                    c.read(
                        "SELECT last_read_message_id FROM support_thread_reads WHERE thread_id=? AND user_id=?",
                        listOf(thread, user),
                    ) {
                        it.getLong(1)
                    }.singleOrNull()
                }
                val dashboard = "/api/venue/$venue/dashboard?monitoringVersion=3"
                val prefix = "/api/venue/$venue/support/threads"
                get("/api/venue/me")
                val activeBookings = get("/api/venue/bookings?venueId=$venue").getValue("items").jsonArray
                assertTrue(
                    activeBookings.none {
                        it.jsonObject.getValue("bookingId").jsonPrimitive.long == ids.getValue("SEATED")
                    },
                )
                val before = fingerprint()
                repeat(2) {
                    for (user in listOf(7L, 9L)) {
                        val result = get(dashboard, user)
                        val events = result.getValue("attention").jsonArray.map { it.jsonObject }
                        for (status in listOf("SEATED", "CONFIRMED", "CHANGED")) {
                            val task = events.single { it["bookingId"]?.jsonPrimitive?.longOrNull == ids[status] }
                            assertEquals(threads[status], task.getValue("threadId").jsonPrimitive.long)
                        }
                        assertTrue(
                            events.none {
                                it["bookingId"]?.jsonPrimitive?.longOrNull in listOf(ids["CLOSED"], ids["RESOLVED"])
                            },
                        )
                        assertTrue("Локальный текст" !in result.toString() && "Foreign private" !in result.toString())
                        assertTrue(
                            events.single {
                                it["bookingId"]?.jsonPrimitive?.longOrNull == ids["PENDING"]
                            }["threadId"] == null,
                        )
                    }
                }
                assertTrue(get(dashboard, 10).getValue("attention").jsonArray.isEmpty())
                for (user in listOf(
                    8L,
                    10L,
                    99L,
                )) get("$prefix/${threads["SEATED"]}?threadTypes=BOOKING_THREAD", user, 403)
                get("/api/venue/$foreign/support/threads/${threads["SEATED"]}?threadTypes=BOOKING_THREAD", 7, 403)
                get("$prefix/${foreignMessage.thread.id}?threadTypes=BOOKING_THREAD", 7, 404)
                get("$prefix/$unrelated?threadTypes=BOOKING_THREAD", 7, 404)
                get("$prefix/99999999?threadTypes=BOOKING_THREAD", 7, 404)
                val inventory = get("$prefix?threadTypes=BOOKING_THREAD").getValue("items").jsonArray
                assertEquals(100, inventory.size)
                assertTrue(
                    inventory.none {
                        it.jsonObject.getValue("threadId").jsonPrimitive.long == threads.getValue("SEATED")
                    },
                )
                assertEquals(before, fingerprint())
                val detail = get("$prefix/${threads["CONFIRMED"]}?threadTypes=BOOKING_THREAD", 9)
                assertEquals(
                    threads["CONFIRMED"],
                    detail.getValue("thread").jsonObject.getValue("threadId").jsonPrimitive.long,
                )
                assertTrue(marker(threads.getValue("CONFIRMED"), 9) != null)
                assertEquals(null, marker(threads.getValue("CONFIRMED"), 7))
                exec("DELETE FROM venue_members WHERE venue_id=$venue AND user_id=9")
                val revoked = fingerprint()
                get("$prefix/${threads["SEATED"]}?threadTypes=BOOKING_THREAD", 9, 403)
                assertEquals(revoked, fingerprint())
                exec("INSERT INTO venue_members(venue_id,user_id,role) VALUES ($venue,9,'MANAGER')")
                val output = Path.of("build/test-artifacts/booking-attention")
                Files.createDirectories(output)
                Files.writeString(output.resolve("dashboard.json"), get(dashboard).toString())
                val livePath = System.getenv("VENUE_BOOKING_ATTENTION_LIVE_DIR")
                if (livePath == null) {
                    val target = threads.getValue("SEATED")
                    val oldRead = get("$prefix/$target?threadTypes=BOOKING_THREAD")
                    val boundary = marker(target, 7)!!
                    assertTrue(oldRead.getValue("messages").jsonArray.isNotEmpty())
                    val next = send(ids.getValue("SEATED"), venue, "Новое после снимка")
                    assertTrue(boundary < next.message.id)
                    assertEquals(boundary, marker(target, 7))
                    assertTrue(
                        get(dashboard).getValue("attention").jsonArray.any {
                            it.jsonObject["threadId"]?.jsonPrimitive?.longOrNull == target
                        },
                    )
                } else {
                    val live = Path.of(livePath)
                    Files.createDirectories(live)
                    Files.writeString(
                        live.resolve("ready.json"),
                        buildJsonObject {
                            put("base", base)
                            put("token", tokens.issueToken(7).token)
                            put("venueId", venue)
                            put("pendingBookingId", ids.getValue("PENDING"))
                            put("targets", JsonObject(targets))
                        }.toString(),
                    )
                    var appended = false
                    val until = System.nanoTime() + 180_000_000_000L
                    while (!Files.exists(live.resolve("done")) && System.nanoTime() < until) {
                        if (!appended && Files.exists(live.resolve("append"))) {
                            val target = threads.getValue("SEATED")
                            val boundary = marker(target, 7)!!
                            val next = send(ids.getValue("SEATED"), venue, "Новое после снимка")
                            assertTrue(boundary < next.message.id)
                            assertEquals(boundary, marker(target, 7))
                            Files.writeString(live.resolve("appended"), next.message.id.toString())
                            appended = true
                        }
                        Thread.sleep(50)
                    }
                    assertTrue(Files.exists(live.resolve("done")), "Browser handoff did not finish")
                    assertTrue(appended)
                    assertTrue(
                        threads.values.filter {
                            it in listOf(threads["SEATED"], threads["CONFIRMED"], threads["CHANGED"])
                        }.all { marker(it, 7) != null },
                    )
                    assertEquals(null, marker(threads.getValue("CLOSED"), 7))
                    Files.deleteIfExists(live.resolve("ready.json"))
                }
            } finally {
                server.stop(100, 1000)
            }
        }
    }
}
