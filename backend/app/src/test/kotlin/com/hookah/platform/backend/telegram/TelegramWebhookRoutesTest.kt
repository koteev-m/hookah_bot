package com.hookah.platform.backend.telegram

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.miniapp.api.TelegramAuthRequest
import com.hookah.platform.backend.miniapp.auth.buildDataCheckString
import com.hookah.platform.backend.miniapp.auth.calculateTelegramInitDataHash
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.telegram.db.TelegramInboundUpdateQueueRepository
import com.hookah.platform.backend.test.PostgresTestDatabase
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramWebhookRoutesTest {
    private val sentMessages = ConcurrentLinkedQueue<JsonObject>()
    private val sentChats = ConcurrentLinkedQueue<Long>()
    private val providerFailures = ConcurrentLinkedQueue<String>()

    @AfterTest
    fun verifySyntheticProviderBoundary() {
        assertTrue(providerFailures.isEmpty(), providerFailures.joinToString())
    }

    private fun botVisitConfig(db: PostgresTestDatabase): MapApplicationConfig =
        PostgresTestEnv.buildConfig(db).apply {
            put("telegram.enabled", "true")
            put("telegram.token", "test-token")
            put("telegram.mode", "webhook")
            put("telegram.platformOwnerId", FIRST_PRODUCT_USER_ID.toString())
            put("telegram.webhookSecretToken", "secret")
            put("telegram.staffChatLinkSecretPepper", "pepper")
            put("telegram.trafficPolicy", "PRODUCT")
            put("telegram.webAppPublicUrl", "https://miniapp.example.test/miniapp/")
            put("billing.subscription.intervalSeconds", "0")
        }

    // The application serving the next Mini App button starts with empty process-local cart maps.
    private fun startOrdinaryVisit(db: PostgresTestDatabase) =
        testApplication {
            environment { config = botVisitConfig(db) }
            application { moduleWithOverrides(syntheticOverrides()) }
            client.get("/health")
            DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { connection ->
                connection.createStatement().use { sql ->
                    sql.executeUpdate(
                        "INSERT INTO venues (id,name,city,address,status) OVERRIDING SYSTEM VALUE " +
                            "VALUES (101,'Fixture','City','Fixture','PUBLISHED')",
                    )
                    sql.executeUpdate(
                        "INSERT INTO venue_tables(id,venue_id,table_number) OVERRIDING SYSTEM VALUE VALUES (201,101,4)",
                    )
                    sql.executeUpdate("INSERT INTO table_tokens(token,table_id) VALUES ('bot-closure',201)")
                    sql.executeUpdate("INSERT INTO venue_subscriptions(venue_id,status) VALUES (101,'ACTIVE')")
                }
            }
            val actor = ALLOWED_USER_ID
            val response =
                client.post("/telegram/webhook") {
                    contentType(ContentType.Application.Json)
                    headers { append("X-Telegram-Bot-Api-Secret-Token", "secret") }
                    setBody(
                        """{"update_id":91,"message":{"message_id":91,"chat":{"id":$actor,"type":"private"},
                            "from":{"id":$actor},"text":"/start bot-closure"}}""",
                    )
                }
            assertEquals(HttpStatusCode.OK, response.status)
            withTimeout(15000) {
                while (db.scalar("SELECT table_session_id FROM telegram_chat_context WHERE user_id=$actor") == null) {
                    delay(20)
                }
            }
        }

    @Test
    fun `an ordinary bot cart action refreshes its exact session before expiry`() =
        testApplication {
            val db = PostgresTestEnv.createDatabase()
            startOrdinaryVisit(db)
            environment { config = botVisitConfig(db) }
            application { moduleWithOverrides(syntheticOverrides(callbackId = "cart-activity")) }
            client.get("/health")
            val actor = ALLOWED_USER_ID
            val session =
                checkNotNull(db.scalar("SELECT table_session_id FROM telegram_chat_context WHERE user_id=$actor"))
            DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { conn ->
                conn.createStatement().use {
                    it.executeUpdate(
                        "UPDATE table_sessions SET expires_at=now()+interval '30 seconds' WHERE id=$session",
                    )
                }
            }
            val response =
                client.post("/telegram/webhook") {
                    contentType(ContentType.Application.Json)
                    headers { append("X-Telegram-Bot-Api-Secret-Token", "secret") }
                    setBody(
                        """{"update_id":92,"callback_query":{"id":"cart-activity","chat_instance":"test",
                            "from":{"id":$actor},"message":{"message_id":91,"chat":{"id":$actor,"type":"private"}},
                            "data":"bot_menu_cart_clear"}}""",
                    )
                }
            assertEquals(HttpStatusCode.OK, response.status)
            withTimeout(15000) {
                val ownedBill = "SELECT COUNT(*) FROM tab WHERE table_session_id=$session AND owner_user_id=$actor"
                while (db.scalar(ownedBill) != "1") {
                    delay(20)
                }
            }
            val refreshed =
                "SELECT COUNT(*) FROM table_sessions WHERE id=$session AND expires_at>now()+interval '5 minutes'"
            assertEquals("1", db.scalar(refreshed))
            assertEquals(session, db.scalar("SELECT table_session_id FROM telegram_chat_context WHERE user_id=$actor"))
        }

    @Test
    fun `confirmed bot exit before the first bill protects the old Mini App button in both clients`() =
        testApplication {
            val db = PostgresTestEnv.createDatabase()
            val user = ALLOWED_USER_ID
            startOrdinaryVisit(db)
            environment { config = botVisitConfig(db) }
            application { moduleWithOverrides(syntheticOverrides()) }
            client.get("/health")
            val second = createClient {}

            suspend fun login(
                http: HttpClient,
                actor: Long = user,
            ): String {
                val response =
                    http.post("/api/auth/telegram") {
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(TelegramAuthRequest(syntheticInitData(actor))))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("token").jsonPrimitive.content
            }
            val firstAuth = login(client)
            val secondAuth = login(second)
            var update = 100

            suspend fun message(
                text: String,
                actor: Long = user,
            ) {
                ++update
                val response =
                    client.post("/telegram/webhook") {
                        contentType(ContentType.Application.Json)
                        headers { append("X-Telegram-Bot-Api-Secret-Token", "secret") }
                        setBody(
                            """{"update_id":$update,"message":{"message_id":$update,
                                "chat":{"id":$actor,"type":"private"},"from":{"id":$actor},"text":"$text"}}""",
                        )
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

            suspend fun awaitMessage(predicate: (JsonObject) -> Boolean): JsonObject =
                withTimeout(15000) {
                    while (sentMessages.none(predicate)) delay(20)
                    sentMessages.first(predicate)
                }
            val counts = listOf("tab", "tab_member", "orders", "order_batches", "venue_subscriptions", "audit_log")

            fun snapshot() = counts.associateWith { db.scalar("SELECT COUNT(*) FROM $it") }
            val before = snapshot()
            val session =
                checkNotNull(db.scalar("SELECT id FROM table_sessions WHERE table_id=201 AND status='ACTIVE'"))
            message("📱 Заказывать в Mini App")
            val link = awaitMessage { it["text"]?.jsonPrimitive?.content?.startsWith("📱 Mini App") == true }
            val buttons = link.getValue("reply_markup").jsonObject.getValue("inline_keyboard").jsonArray
            val entry = buttons.flatMap { it.jsonArray }.first { it.jsonObject.containsKey("web_app") }
            val pointer = Url(entry.jsonObject.getValue("web_app").jsonObject.getValue("url").jsonPrimitive.content)
            val qr = checkNotNull(pointer.parameters["tableToken"] ?: pointer.parameters["table_token"])
            assertEquals("bot-closure", qr)
            assertEquals(session, pointer.parameters["tableSessionId"])
            assertEquals(before, snapshot())
            message("🚪 Завершить визит")
            awaitMessage { it["text"]?.jsonPrimitive?.content?.startsWith("Визит завершён.") == true }
            val markerSql =
                "SELECT exit_event_id FROM guest_table_session_exits " +
                    "WHERE user_id=$user AND table_session_id=$session"
            val event = checkNotNull(db.scalar(markerSql))

            suspend fun getTable(
                http: HttpClient,
                auth: String,
            ): JsonObject {
                val response =
                    http.get("/api/guest/table/resolve?tableToken=$qr&resolveMode=create") {
                        headers { append(HttpHeaders.Authorization, "Bearer $auth") }
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
            for ((http, auth) in listOf(client to firstAuth, second to secondAuth)) {
                val result = getTable(http, auth)
                assertEquals("false", result.getValue("tableSessionActive").jsonPrimitive.content)
                assertEquals(event, result.getValue("exitEventId").jsonPrimitive.content)
            }
            assertEquals(event, db.scalar(markerSql))
            assertEquals("ACTIVE", db.scalar("SELECT status FROM table_sessions WHERE id=$session"))
            val activeChatSql =
                "SELECT COUNT(*) FROM telegram_chat_context " +
                    "WHERE user_id=$user AND table_token IS NOT NULL"
            assertEquals("0", db.scalar(activeChatSql))
            assertEquals(before, snapshot())

            val scanned = getTable(second, secondAuth)
            val scannedEvent = scanned.getValue("exitEventId").jsonPrimitive.content
            assertEquals(event, scannedEvent)
            assertEquals(session, scanned.getValue("tableSessionId").jsonPrimitive.content)

            suspend fun rejoin(
                http: HttpClient,
                auth: String,
                expected: HttpStatusCode,
                confirmation: String = scannedEvent,
            ) {
                val response =
                    http.post("/api/guest/table/session/rejoin") {
                        headers { append(HttpHeaders.Authorization, "Bearer $auth") }
                        contentType(ContentType.Application.Json)
                        setBody("""{"tableToken":"$qr","tableSessionId":$session,"exitEventId":"$confirmation"}""")
                    }
                assertEquals(expected, response.status)
                if (expected == HttpStatusCode.OK) {
                    assertEquals(
                        "true",
                        Json.parseToJsonElement(response.bodyAsText()).jsonObject
                            .getValue("tableSessionActive").jsonPrimitive.content,
                    )
                }
            }

            suspend fun hasVisit(query: String = "scope=active-visits"): Boolean {
                val response =
                    client.get("/api/guest/table/restore?$query") {
                        headers { append(HttpHeaders.Authorization, "Bearer $firstAuth") }
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("context") != JsonNull
            }

            suspend fun readTabs(expected: HttpStatusCode) {
                val response =
                    second.get("/api/guest/tabs?table_session_id=$session") {
                        headers { append(HttpHeaders.Authorization, "Bearer $secondAuth") }
                    }
                assertEquals(expected, response.status)
                if (expected == HttpStatusCode.OK) {
                    assertTrue(
                        Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("tabs").jsonArray.isEmpty(),
                    )
                }
            }
            assertFalse(hasVisit())
            readTabs(HttpStatusCode.Forbidden)
            rejoin(second, secondAuth, HttpStatusCode.OK)
            assertNull(db.scalar(markerSql))
            val freshWebView = createClient {}
            val freshRead =
                freshWebView.get("/api/guest/table/resolve?tableToken=$qr&tableSessionId=$session") {
                    headers { append(HttpHeaders.Authorization, "Bearer $firstAuth") }
                }
            assertEquals(HttpStatusCode.OK, freshRead.status)
            val orderRead =
                freshWebView.get("/api/guest/order/active?tableToken=$qr") {
                    headers { append(HttpHeaders.Authorization, "Bearer $firstAuth") }
                }
            assertEquals(HttpStatusCode.OK, orderRead.status)
            assertTrue(Json.parseToJsonElement(orderRead.bodyAsText()).jsonObject["order"].toString() == "null")
            assertEquals(before, snapshot())
            assertTrue(hasVisit())
            assertTrue(hasVisit("tableToken=$qr&tableSessionId=$session"))
            assertFalse(hasVisit("tableToken=other-fixture&tableSessionId=$session"))
            readTabs(HttpStatusCode.OK)
            assertEquals("1", db.scalar(activeChatSql))
            assertEquals(before, snapshot())
            rejoin(client, firstAuth, HttpStatusCode.Conflict)
            message("🚪 Завершить визит")
            withTimeout(15000) {
                while (db.scalar(markerSql) == null) delay(20)
            }
            val laterExit = checkNotNull(db.scalar(markerSql))
            assertNotEquals(event, laterExit)
            rejoin(second, secondAuth, HttpStatusCode.Conflict)
            assertEquals(laterExit, db.scalar(markerSql))
            assertFalse(hasVisit())
            assertEquals("1", db.scalar("SELECT COUNT(*) FROM table_sessions"))
            assertEquals(before, snapshot())

            suspend fun command(
                path: String,
                payload: String,
                auth: String = secondAuth,
            ) = second.post("/api/guest/$path") {
                headers { append(HttpHeaders.Authorization, "Bearer $auth") }
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            val bill = """{"tableSessionId":$session}"""
            val exit = """{"tableToken":"$qr","tableSessionId":$session}"""
            assertEquals(HttpStatusCode.Forbidden, command("tabs/personal", bill).status)
            rejoin(second, secondAuth, HttpStatusCode.OK, laterExit)
            val deniedActor = login(second, SECOND_PRODUCT_USER_ID)
            assertEquals(HttpStatusCode.Forbidden, command("tabs/personal", bill, deniedActor).status)
            assertEquals(HttpStatusCode.NotFound, command("table/session/end", exit, deniedActor).status)
            val ended = command("table/session/end", exit)
            assertEquals(HttpStatusCode.OK, ended.status)
            assertEquals(
                "true",
                Json.parseToJsonElement(ended.bodyAsText()).jsonObject.getValue("ended").jsonPrimitive.content,
            )
            val miniAppExit = checkNotNull(db.scalar(markerSql))
            assertNotEquals(laterExit, miniAppExit)
            val verify = "/api/guest/table/resolve?tableToken=$qr&tableSessionId=$session&verifyOnly=true"
            val check = client.get(verify) { headers { append(HttpHeaders.Authorization, "Bearer $firstAuth") } }
            assertEquals(HttpStatusCode.OK, check.status)
            assertEquals(
                miniAppExit,
                Json.parseToJsonElement(check.bodyAsText()).jsonObject.getValue("exitEventId").jsonPrimitive.content,
            )
            assertEquals(miniAppExit, db.scalar(markerSql))
            assertEquals("ACTIVE", db.scalar("SELECT status FROM table_sessions WHERE id=$session"))
            assertEquals(before, snapshot())
            rejoin(second, secondAuth, HttpStatusCode.Conflict, laterExit)
            rejoin(second, secondAuth, HttpStatusCode.OK, miniAppExit)
            readTabs(HttpStatusCode.OK)
            assertEquals(before, snapshot())
            val firstBill = command("tabs/personal", bill)
            val sameBill = command("tabs/personal", bill, firstAuth)
            assertEquals(HttpStatusCode.OK, firstBill.status)
            assertEquals(HttpStatusCode.OK, sameBill.status)
            val tab = Json.parseToJsonElement(firstBill.bodyAsText()).jsonObject.getValue("tab").jsonObject
            assertEquals(tab, Json.parseToJsonElement(sameBill.bodyAsText()).jsonObject.getValue("tab"))
            assertEquals("PERSONAL", tab.getValue("type").jsonPrimitive.content)
            assertEquals(session, tab.getValue("tableSessionId").jsonPrimitive.content)
            assertEquals("1", db.scalar("SELECT COUNT(*) FROM tab_member WHERE user_id=$user"))
            assertEquals("1", db.scalar("SELECT COUNT(*) FROM tab WHERE table_session_id=$session"))
            assertEquals(before - setOf("tab", "tab_member"), snapshot() - setOf("tab", "tab_member"))
            assertEquals(HttpStatusCode.OK, command("table/session/end", exit).status)

            // A Platform prompt for an exited actor cannot provision a missing subscription.
            val platform = FIRST_PRODUCT_USER_ID
            login(second, platform)
            DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { connection ->
                connection.createStatement().use { sql ->
                    sql.executeUpdate("DELETE FROM venue_subscriptions WHERE venue_id=101")
                    sql.executeUpdate(
                        "INSERT INTO guest_table_session_exits(user_id,table_session_id,exited_at) " +
                            "VALUES ($platform,$session,now())",
                    )
                }
            }
            val beforePrompt = snapshot()
            readTabs(HttpStatusCode.Locked)
            assertEquals(HttpStatusCode.Locked, command("tabs/personal", bill).status)
            assertEquals(beforePrompt, snapshot())
            message("/start bot-closure", platform)
            awaitMessage {
                it["chat_id"]?.jsonPrimitive?.content == platform.toString() &&
                    it["text"]?.jsonPrimitive?.content?.contains("подписк", ignoreCase = true) == true
            }
            assertEquals(beforePrompt, snapshot())
            assertEquals("1", db.scalar("SELECT COUNT(*) FROM table_sessions"))
            assertEquals("2", db.scalar("SELECT COUNT(*) FROM guest_table_session_exits"))
            DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { connection ->
                connection.createStatement().use { sql ->
                    sql.executeUpdate("INSERT INTO venue_subscriptions(venue_id,status) VALUES (101,'ACTIVE')")
                    sql.executeUpdate(
                        "INSERT INTO venue_tables(id,venue_id,table_number) OVERRIDING SYSTEM VALUE " +
                            "VALUES (202,101,5),(203,101,6)",
                    )
                    sql.executeUpdate(
                        "INSERT INTO table_tokens(token,table_id) VALUES ('second-qr',202),('third-qr',203)",
                    )
                }
            }
            message("/start second-qr")

            suspend fun awaitTable(id: Long) =
                withTimeout(15000) {
                    val sql =
                        "SELECT table_id FROM telegram_chat_context " +
                            "WHERE user_id=$user AND table_token IS NOT NULL"
                    while (db.scalar(sql) != id.toString()) delay(
                        20,
                    )
                }
            awaitTable(202)
            val oldPhysical = checkNotNull(db.scalar("SELECT id FROM table_sessions WHERE table_id=202"))
            val previousMarker =
                "SELECT exit_event_id FROM guest_table_session_exits " +
                    "WHERE user_id=$user AND table_session_id=$oldPhysical"
            message("🚪 Завершить визит")
            withTimeout(15000) { while (db.scalar(previousMarker) == null) delay(20) }
            val oldEvent = checkNotNull(db.scalar(previousMarker))
            message("/start third-qr")
            awaitTable(203)
            val beforeDenied = snapshot()
            message("/start second-qr")
            val denial =
                awaitMessage { it["text"]?.jsonPrimitive?.content?.contains("больше не связан с чатом") == true }
            assertFalse(denial.toString().contains("Снова за столом"))
            assertEquals("203", db.scalar("SELECT table_id FROM telegram_chat_context WHERE user_id=$user"))
            assertEquals(oldEvent, db.scalar(previousMarker))
            DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { connection ->
                connection.createStatement().use { sql ->
                    sql.executeUpdate("UPDATE table_sessions SET status='ENDED', ended_at=now() WHERE id=$oldPhysical")
                    sql.executeUpdate(
                        "INSERT INTO table_sessions(venue_id,table_id,status,started_at,last_activity_at,expires_at) " +
                            "VALUES (101,202,'ACTIVE',now(),now(),now()+interval '1 hour')",
                    )
                }
            }
            val exactOld =
                client.get(
                    "/api/guest/table/resolve?tableToken=second-qr&tableSessionId=$oldPhysical&verifyOnly=true",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $firstAuth") }
                }
            assertEquals(HttpStatusCode.OK, exactOld.status)
            val body = Json.parseToJsonElement(exactOld.bodyAsText()).jsonObject
            assertEquals(oldPhysical, body.getValue("tableSessionId").jsonPrimitive.content)
            assertEquals("false", body.getValue("tableSessionActive").jsonPrimitive.content)
            val stale = """{"tableToken":"second-qr","tableSessionId":$oldPhysical,"exitEventId":"$oldEvent"}"""
            assertEquals(HttpStatusCode.Conflict, command("table/session/rejoin", stale).status)
            assertEquals(beforeDenied, snapshot())
            assertEquals(oldEvent, db.scalar(previousMarker))
        }

    @Test
    fun `maintenance webhook gate acknowledges denied identity before queue persistence`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                TelegramInboundUpdateQueueRepository(dataSource).enqueue(
                    29,
                    """
                    {"update_id":29,"message":{"message_id":1,
                    "chat":{"id":$DENIED_USER_ID,"type":"private"},
                    "from":{"id":$DENIED_USER_ID},"text":"/start"}}
                    """.trimIndent().replace("\n", ""),
                )
                dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        INSERT INTO telegram_outbox (chat_id, method, payload_json, attempts, last_error)
                        VALUES (?, 'sendMessage', ?, 2, 'preserve-denied')
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, DENIED_USER_ID)
                        statement.setString(2, """{"chat_id":$DENIED_USER_ID,"text":"preserve denied"}""")
                        statement.executeUpdate()
                    }
                }
            }
            val deniedInboundBefore = database.scalar("SELECT row_to_json(t)::text FROM telegram_inbound_updates t")
            val deniedOutboxBefore = database.scalar("SELECT row_to_json(t)::text FROM telegram_outbox t")
            environment {
                config =
                    MapApplicationConfig(
                        "app.env" to "test",
                        "api.session.jwtSecret" to "test-secret",
                        "db.jdbcUrl" to database.jdbcUrl,
                        "db.user" to database.user,
                        "db.password" to database.password,
                        "db.maxPoolSize" to "3",
                        "telegram.enabled" to "true",
                        "telegram.token" to "test-token",
                        "telegram.mode" to "webhook",
                        "telegram.webhookSecretToken" to "secret",
                        "telegram.staffChatLinkSecretPepper" to "pepper",
                        "telegram.trafficPolicy" to "PRODUCT",
                        "staging.maintenance.mode" to "V126_SMOKE",
                        "staging.maintenance.allowedUserIds" to ALLOWED_USER_ID.toString(),
                        "staging.maintenance.allowedChatIds" to ALLOWED_USER_ID.toString(),
                    )
            }
            application {
                moduleWithOverrides(syntheticOverrides())
            }

            listOf(
                DENIED_USER_ID to HttpStatusCode.ServiceUnavailable,
                ALLOWED_USER_ID to HttpStatusCode.OK,
            ).forEach { (userId, expectedStatus) ->
                val response =
                    client.post("/api/auth/telegram") {
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(TelegramAuthRequest(syntheticInitData(userId))))
                    }
                assertEquals(expectedStatus, response.status)
            }
            assertEquals("0", database.scalar("SELECT COUNT(*) FROM users WHERE telegram_user_id = $DENIED_USER_ID"))
            assertEquals("1", database.scalar("SELECT COUNT(*) FROM users WHERE telegram_user_id = $ALLOWED_USER_ID"))

            listOf(30L to DENIED_USER_ID, 31L to ALLOWED_USER_ID).forEach { (updateId, userId) ->
                val response =
                    client.post("/telegram/webhook") {
                        contentType(ContentType.Application.Json)
                        headers { append("X-Telegram-Bot-Api-Secret-Token", "secret") }
                        setBody(
                            "{\"update_id\":$updateId,\"message\":{\"message_id\":1," +
                                "\"chat\":{\"id\":$userId,\"type\":\"private\"}," +
                                "\"from\":{\"id\":$userId},\"text\":\"/start\"}}",
                        )
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

            withContext(Dispatchers.IO) {
                withTimeout(15_000) {
                    while (
                        database.scalar(
                            "SELECT status FROM telegram_inbound_updates WHERE update_id = 31",
                        ) != "PROCESSED" ||
                        database.scalar(
                            "SELECT COUNT(*) FROM telegram_outbox WHERE chat_id = $ALLOWED_USER_ID AND status = 'SENT'",
                        ) != "1"
                    ) {
                        assertTrue(providerFailures.isEmpty(), providerFailures.joinToString())
                        delay(50)
                    }
                }
            }
            assertEquals(listOf(ALLOWED_USER_ID), sentChats.toList())
            assertEquals(
                deniedInboundBefore,
                database.scalar("SELECT row_to_json(t)::text FROM telegram_inbound_updates t WHERE update_id = 29"),
            )
            assertEquals(
                deniedOutboxBefore,
                database.scalar("SELECT row_to_json(t)::text FROM telegram_outbox t WHERE chat_id = $DENIED_USER_ID"),
            )
            assertEquals("0", database.scalar("SELECT COUNT(*) FROM users WHERE telegram_user_id = $DENIED_USER_ID"))
            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "SELECT update_id FROM telegram_inbound_updates WHERE update_id IN (30, 31)",
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(31L, resultSet.getLong("update_id"))
                        assertFalse(resultSet.next())
                    }
                }
            }
        }

    @Test
    fun `missing telegram webhook secret returns forbidden`() =
        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "db.jdbcUrl" to "",
                        "telegram.enabled" to "true",
                        "telegram.token" to "test-token",
                        "telegram.mode" to "webhook",
                        "telegram.webhookSecretToken" to "secret",
                        "telegram.staffChatLinkSecretPepper" to "pepper",
                    )
            }
            application {
                moduleWithOverrides(
                    syntheticOverrides(),
                )
            }

            val response = client.post("/telegram/webhook")
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `valid telegram webhook secret returns ok`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            environment {
                config =
                    MapApplicationConfig(
                        "app.env" to "test",
                        "api.session.jwtSecret" to "test-secret",
                        "db.jdbcUrl" to database.jdbcUrl,
                        "db.user" to database.user,
                        "db.password" to database.password,
                        "db.maxPoolSize" to "3",
                        "telegram.enabled" to "true",
                        "telegram.token" to "test-token",
                        "telegram.mode" to "webhook",
                        "telegram.webhookSecretToken" to "secret",
                        "telegram.staffChatLinkSecretPepper" to "pepper",
                    )
            }
            application {
                moduleWithOverrides(
                    syntheticOverrides(),
                )
            }

            val invalidResponse =
                client.post("/telegram/webhook") {
                    headers { append("X-Telegram-Bot-Api-Secret-Token", "wrong") }
                }
            assertEquals(HttpStatusCode.Forbidden, invalidResponse.status)

            val response =
                client.post("/telegram/webhook") {
                    contentType(ContentType.Application.Json)
                    headers { append("X-Telegram-Bot-Api-Secret-Token", "secret") }
                    setBody("""{"update_id":1}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement(
                    "SELECT status FROM telegram_inbound_updates WHERE update_id = 1",
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                    }
                }
            }
        }

    @Test
    fun `allowlist denial is acknowledged before webhook enqueue`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val applicationLogger = LoggerFactory.getLogger("Application") as Logger
            applicationLogger.addAppender(appender)
            try {
                environment {
                    config =
                        MapApplicationConfig(
                            "app.env" to "staging",
                            "api.session.jwtSecret" to "test-secret",
                            "db.jdbcUrl" to database.jdbcUrl,
                            "db.user" to database.user,
                            "db.password" to database.password,
                            "db.maxPoolSize" to "3",
                            "telegram.enabled" to "true",
                            "telegram.token" to SENSITIVE_BOT_TOKEN,
                            "telegram.mode" to "webhook",
                            "telegram.webhookSecretToken" to SENSITIVE_WEBHOOK_SECRET,
                            "telegram.staffChatLinkSecretPepper" to "pepper",
                            "telegram.trafficPolicy" to "ALLOWLIST",
                            "telegram.allowedUserIds" to ALLOWED_USER_ID.toString(),
                            "telegram.allowedChatIds" to ALLOWED_USER_ID.toString(),
                        )
                }
                application {
                    moduleWithOverrides(
                        syntheticOverrides(),
                    )
                }

                val deniedResponse =
                    client.post("/telegram/webhook") {
                        contentType(ContentType.Application.Json)
                        headers { append("X-Telegram-Bot-Api-Secret-Token", SENSITIVE_WEBHOOK_SECRET) }
                        setBody(
                            "{\"update_id\":2,\"message\":{\"message_id\":1," +
                                "\"chat\":{\"id\":$DENIED_USER_ID,\"type\":\"private\"}," +
                                "\"from\":{\"id\":$DENIED_USER_ID}," +
                                "\"text\":\"$PAYLOAD_SENTINEL\"}}",
                        )
                    }
                assertEquals(HttpStatusCode.OK, deniedResponse.status)

                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM telegram_inbound_updates WHERE update_id = 2",
                    ).use { statement ->
                        statement.executeQuery().use { resultSet ->
                            resultSet.next()
                            assertEquals(0, resultSet.getInt(1))
                        }
                    }
                }

                val denialLogs = appender.list.joinToString("\n") { it.formattedMessage }
                assertTrue(denialLogs.contains("source=webhook"))
                assertTrue(denialLogs.contains("reason=ACTOR_NOT_ALLOWED"))
                assertFalse(denialLogs.contains(DENIED_USER_ID.toString()))
                assertFalse(denialLogs.contains(ALLOWED_USER_ID.toString()))
                assertFalse(denialLogs.contains(PAYLOAD_SENTINEL))
                assertFalse(denialLogs.contains(SENSITIVE_BOT_TOKEN))
                assertFalse(denialLogs.contains(SENSITIVE_WEBHOOK_SECRET))

                val allowedResponse =
                    client.post("/telegram/webhook") {
                        contentType(ContentType.Application.Json)
                        headers { append("X-Telegram-Bot-Api-Secret-Token", SENSITIVE_WEBHOOK_SECRET) }
                        setBody(
                            "{\"update_id\":3,\"message\":{\"message_id\":1," +
                                "\"chat\":{\"id\":$ALLOWED_USER_ID,\"type\":\"private\"}," +
                                "\"from\":{\"id\":$ALLOWED_USER_ID}}}",
                        )
                    }
                assertEquals(HttpStatusCode.OK, allowedResponse.status)
                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM telegram_inbound_updates WHERE update_id = 3",
                    ).use { statement ->
                        statement.executeQuery().use { resultSet ->
                            resultSet.next()
                            assertEquals(1, resultSet.getInt(1))
                        }
                    }
                }
            } finally {
                applicationLogger.detachAppender(appender)
                appender.stop()
            }
        }

    @Test
    fun `malformed webhook payload returns bad request without enqueue or sensitive logs`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val applicationLogger = LoggerFactory.getLogger("Application") as Logger
            applicationLogger.addAppender(appender)
            try {
                environment {
                    config =
                        MapApplicationConfig(
                            "app.env" to "staging",
                            "api.session.jwtSecret" to "test-secret",
                            "db.jdbcUrl" to database.jdbcUrl,
                            "db.user" to database.user,
                            "db.password" to database.password,
                            "db.maxPoolSize" to "3",
                            "telegram.enabled" to "true",
                            "telegram.token" to SENSITIVE_BOT_TOKEN,
                            "telegram.mode" to "webhook",
                            "telegram.webhookSecretToken" to SENSITIVE_WEBHOOK_SECRET,
                            "telegram.staffChatLinkSecretPepper" to "pepper",
                            "telegram.trafficPolicy" to "ALLOWLIST",
                            "telegram.allowedUserIds" to ALLOWED_USER_ID.toString(),
                            "telegram.allowedChatIds" to ALLOWED_USER_ID.toString(),
                        )
                }
                application {
                    moduleWithOverrides(
                        syntheticOverrides(),
                    )
                }

                val response =
                    client.post("/telegram/webhook") {
                        contentType(ContentType.Application.Json)
                        headers { append("X-Telegram-Bot-Api-Secret-Token", SENSITIVE_WEBHOOK_SECRET) }
                        setBody(
                            "{\"update_id\":4,\"message\":{\"message_id\":1," +
                                "\"chat\":{\"id\":$ALLOWED_USER_ID,\"type\":\"private\"}," +
                                "\"from\":{\"id\":$ALLOWED_USER_ID}," +
                                "\"text\":\"$MALFORMED_PAYLOAD_SENTINEL\"}",
                        )
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)

                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM telegram_inbound_updates WHERE update_id = 4",
                    ).use { statement ->
                        statement.executeQuery().use { resultSet ->
                            resultSet.next()
                            assertEquals(0, resultSet.getInt(1))
                        }
                    }
                }

                val logs = appender.list.joinToString("\n") { it.formattedMessage }
                assertTrue(logs.contains("errorType=invalid_payload"))
                assertFalse(logs.contains(ALLOWED_USER_ID.toString()))
                assertFalse(logs.contains(MALFORMED_PAYLOAD_SENTINEL))
                assertFalse(logs.contains(SENSITIVE_BOT_TOKEN))
                assertFalse(logs.contains(SENSITIVE_WEBHOOK_SECRET))
            } finally {
                applicationLogger.detachAppender(appender)
                appender.stop()
            }
        }

    @Test
    fun `product webhook aggregate limit safely acknowledges without enqueue or sensitive logs`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            val limiter =
                TelegramProductAbuseLimiter(
                    globalLimits =
                        TelegramProductAbuseLimiter.Category.entries.associateWith { category ->
                            TelegramProductAbuseLimiter.Limit(
                                maxAttempts =
                                    if (category == TelegramProductAbuseLimiter.Category.PRIVATE_TRAFFIC) 1 else 100,
                                window = Duration.ofMinutes(1),
                            )
                        },
                    inviteDigestKey = ByteArray(32) { 9 },
                )
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val applicationLogger = LoggerFactory.getLogger("Application") as Logger
            applicationLogger.addAppender(appender)
            try {
                environment {
                    config =
                        MapApplicationConfig(
                            "app.env" to "test",
                            "api.session.jwtSecret" to "test-secret",
                            "db.jdbcUrl" to database.jdbcUrl,
                            "db.user" to database.user,
                            "db.password" to database.password,
                            "db.maxPoolSize" to "3",
                            "telegram.enabled" to "true",
                            "telegram.token" to SENSITIVE_BOT_TOKEN,
                            "telegram.mode" to "webhook",
                            "telegram.webhookSecretToken" to SENSITIVE_WEBHOOK_SECRET,
                            "telegram.staffChatLinkSecretPepper" to "pepper",
                            "telegram.trafficPolicy" to "PRODUCT",
                        )
                }
                application {
                    moduleWithOverrides(
                        ModuleOverrides(
                            telegramHttpClientFactory = ::syntheticTelegramClient,
                            telegramWebhookProductAbuseLimiter = limiter,
                            telegramCommandMenuConfigurator = {},
                        ),
                    )
                }

                val invalidLinkResponse =
                    client.post("/telegram/webhook") {
                        contentType(ContentType.Application.Json)
                        headers { append("X-Telegram-Bot-Api-Secret-Token", SENSITIVE_WEBHOOK_SECRET) }
                        setBody(
                            "{\"update_id\":20,\"message\":{\"message_id\":1," +
                                "\"chat\":{\"id\":-733333333333333330,\"type\":\"supergroup\"}," +
                                "\"from\":{\"id\":733333333333333330}," +
                                "\"text\":\"/link $MALFORMED_LINK_CODE_SENTINEL\"}}",
                        )
                    }
                assertEquals(HttpStatusCode.OK, invalidLinkResponse.status)

                listOf(21L to FIRST_PRODUCT_USER_ID, 22L to SECOND_PRODUCT_USER_ID).forEach { (updateId, userId) ->
                    val response =
                        client.post("/telegram/webhook") {
                            contentType(ContentType.Application.Json)
                            headers { append("X-Telegram-Bot-Api-Secret-Token", SENSITIVE_WEBHOOK_SECRET) }
                            setBody(
                                "{\"update_id\":$updateId,\"message\":{\"message_id\":1," +
                                    "\"chat\":{\"id\":$userId,\"type\":\"private\"}," +
                                    "\"from\":{\"id\":$userId},\"text\":\"$PRODUCT_PAYLOAD_SENTINEL\"}}",
                            )
                        }
                    assertEquals(HttpStatusCode.OK, response.status)
                }

                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        "SELECT update_id FROM telegram_inbound_updates " +
                            "WHERE update_id IN (20, 21, 22) ORDER BY update_id",
                    ).use { statement ->
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            assertEquals(21L, resultSet.getLong(1))
                            assertFalse(resultSet.next())
                        }
                    }
                }

                val logs = appender.list.joinToString("\n") { it.formattedMessage }
                assertTrue(logs.contains("source=webhook reason=RATE_LIMIT_PRIVATE_TRAFFIC"))
                assertFalse(logs.contains(FIRST_PRODUCT_USER_ID.toString()))
                assertFalse(logs.contains(SECOND_PRODUCT_USER_ID.toString()))
                assertFalse(logs.contains(PRODUCT_PAYLOAD_SENTINEL))
                assertFalse(logs.contains(MALFORMED_LINK_CODE_SENTINEL))
                assertFalse(logs.contains(SENSITIVE_BOT_TOKEN))
                assertFalse(logs.contains(SENSITIVE_WEBHOOK_SECRET))
            } finally {
                applicationLogger.detachAppender(appender)
                appender.stop()
            }
        }

    private fun syntheticInitData(userId: Long): String {
        val fields =
            linkedMapOf(
                "auth_date" to Instant.now().epochSecond.toString(),
                "user" to """{"id":$userId,"first_name":"Synthetic"}""",
            )
        fields["hash"] = calculateTelegramInitDataHash("test-token", buildDataCheckString(fields))
        return fields.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
    }

    private fun syntheticOverrides(callbackId: String? = null): ModuleOverrides =
        ModuleOverrides(
            telegramHttpClientFactory = { json -> syntheticTelegramClient(json, callbackId) },
            telegramCommandMenuConfigurator = {},
        )

    private fun syntheticTelegramClient(json: Json): HttpClient = syntheticTelegramClient(json, null)

    private fun syntheticTelegramClient(
        json: Json,
        callbackId: String?,
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                try {
                    check(request.url.protocol.name == "https" && request.url.host == "api.telegram.org")
                    check(request.url.port == 443 && request.method == HttpMethod.Post)
                    check(request.url.parameters.isEmpty())
                    if (callbackId != null && request.url.encodedPath == "/bottest-token/answerCallbackQuery") {
                        val payload = json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        check(payload.getValue("callback_query_id").jsonPrimitive.content == callbackId)
                        return@MockEngine respond(
                            """{"ok":true,"result":true}""",
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }
                    check(
                        request.url.encodedPath in
                            setOf("/bottest-token/sendMessage", "/bot$SENSITIVE_BOT_TOKEN/sendMessage"),
                    )
                    val body = (request.body as TextContent).text
                    val chatId = json.parseToJsonElement(body).jsonObject.getValue("chat_id").jsonPrimitive.long
                    check(chatId in setOf(ALLOWED_USER_ID, FIRST_PRODUCT_USER_ID))
                    sentMessages.add(json.parseToJsonElement(body).jsonObject)
                    sentChats.add(chatId)
                    respond(
                        """{"ok":true,"result":{"message_id":7001}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                } catch (failure: Throwable) {
                    providerFailures.add("Unexpected synthetic Telegram request: ${failure::class.simpleName}")
                    throw failure
                }
            },
        ) {
            install(ContentNegotiation) { json(json) }
        }

    private fun PostgresTestDatabase.scalar(sql: String): String? =
        DriverManager.getConnection("$jdbcUrl&connectTimeout=3&socketTimeout=3", user, password).use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = 3
                statement.executeQuery().use { result ->
                    if (result.next()) result.getString(1) else null
                }
            }
        }

    private companion object {
        const val ALLOWED_USER_ID = 711111111111111111L
        const val DENIED_USER_ID = 722222222222222222L
        const val SENSITIVE_BOT_TOKEN = "777777:SENSITIVE_WEBHOOK_BOT_TOKEN"
        const val SENSITIVE_WEBHOOK_SECRET = "SENSITIVE_WEBHOOK_SECRET"
        const val PAYLOAD_SENTINEL = "WEBHOOK_PAYLOAD_SENTINEL"
        const val MALFORMED_PAYLOAD_SENTINEL = "MALFORMED_WEBHOOK_PAYLOAD_SENTINEL"
        const val FIRST_PRODUCT_USER_ID = 733333333333333331L
        const val SECOND_PRODUCT_USER_ID = 733333333333333332L
        const val PRODUCT_PAYLOAD_SENTINEL = "PRODUCT_WEBHOOK_PAYLOAD_SENTINEL"
        const val MALFORMED_LINK_CODE_SENTINEL = "MALFORMED_LINK_CODE_SENTINEL"
    }
}
