package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.PostgresTestDatabase
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuestExperienceRoutesTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `personal data and venue replies enforce guest ownership and safe retries`(postgres: Boolean) {
        val database = testDatabase(postgres)
        val config = PostgresTestEnv.buildConfig(database)
        testApplication {
            environment { this.config = config }
            application { module() }
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            val venue =
                database.connect().use { connection ->
                    connection.update(
                        "INSERT INTO users (telegram_user_id, first_name) VALUES (1001, 'First'), (1002, 'Second')",
                    )
                    connection.insert("INSERT INTO venues (name, status) VALUES ('Venue', 'PUBLISHED')")
                }
            val first = token(config, 1001)
            val second = token(config, 1002)

            suspend fun edit(
                field: String,
                body: String,
                token: String = first,
            ) = client.put("/api/guest/profile/$field") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/guest/profile").status)
            assertEquals(HttpStatusCode.BadRequest, edit("name", """{"displayName":"line\nwrap"}""").status)
            assertEquals(HttpStatusCode.BadRequest, edit("birthday", """{"birthday":"31.02"}""").status)
            val renamed = edit("name", """{"displayName":"   Имя   гостя  ","telegramUserId":1002}""").payload()
            assertEquals("Имя гостя", renamed.getValue("displayName").jsonPrimitive.content)
            assertEquals(HttpStatusCode.OK, edit("birthday", """{"birthday":"29.02"}""").status)
            assertEquals(HttpStatusCode.OK, edit("birthday", """{"birthday":"29.02"}""").status)
            assertEquals(HttpStatusCode.BadRequest, edit("birthday", """{"birthday":"28.02"}""").status)
            val otherProfile = client.get("/api/guest/profile") { bearerAuth(second) }.payload()
            assertFalse(
                otherProfile.values.any {
                    it.toString().contains("Имя гостя") || it.toString().contains("29.02")
                },
            )
            database.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT guest_display_name, birthday_day FROM users WHERE telegram_user_id = 1001",
                    ).use {
                        assertTrue(it.next())
                        assertEquals("Имя гостя", it.getString(1))
                        assertEquals(29, it.getInt(2))
                    }
                }
            }

            val candidateDates = listOf("01.03", "02.03")
            val birthdayWrites =
                database.connect().use { gate ->
                    if (postgres) {
                        gate.autoCommit = false
                        gate.createStatement().use {
                            it.execute("SELECT 1 FROM users WHERE telegram_user_id = 1002 FOR UPDATE")
                        }
                    }
                    coroutineScope {
                        val pending =
                            candidateDates.map { date ->
                                async { edit("birthday", """{"birthday":"$date"}""", second) }
                            }
                        if (postgres) {
                            try {
                                database.connect().use { observer -> observer.awaitBlockedBirthdayWrites() }
                            } finally {
                                gate.rollback()
                            }
                        }
                        pending.map { it.await() }
                    }
                }
            assertEquals(1, birthdayWrites.count { it.status == HttpStatusCode.OK })
            assertEquals(1, birthdayWrites.count { it.status == HttpStatusCode.BadRequest })
            val acceptedBirthday = candidateDates[birthdayWrites.indexOfFirst { it.status == HttpStatusCode.OK }]
            assertEquals(
                acceptedBirthday,
                client.get("/api/guest/profile") {
                    bearerAuth(second)
                }.payload().getValue("birthday").jsonPrimitive.content,
            )

            val chat =
                client.post("/api/guest/support/venue-chats") {
                    bearerAuth(first)
                    contentType(ContentType.Application.Json)
                    setBody("""{"venueId":$venue}""")
                }.payload().getValue("thread").jsonObject.getValue("threadId").jsonPrimitive.content
            val key = UUID.randomUUID()

            suspend fun send(
                token: String,
                text: String = "Question",
                threadId: String = chat,
            ) = client.post("/api/guest/support/threads/$threadId/messages") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"message":"$text","clientMessageId":"$key"}""")
            }

            assertEquals(HttpStatusCode.NotFound, send(second).status)
            val replies =
                coroutineScope {
                    val initial = async { send(first) }
                    val retry = async { send(first) }
                    listOf(initial.await(), retry.await())
                }.map { it.payload().getValue("message").jsonObject.getValue("messageId") }
            assertEquals(replies.first(), replies.last())
            assertEquals(HttpStatusCode.Conflict, send(first, "Different question").status)
            val thread =
                client.get("/api/guest/support/threads/$chat?surface=CONVERSATIONS") {
                    bearerAuth(first)
                }.payload()
            assertEquals(1, thread.getValue("messages").jsonArray.size)
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/guest/support/threads/$chat?surface=SUPPORT") { bearerAuth(first) }.status,
            )
            val otherVenue =
                database.connect().use {
                    it.insert("INSERT INTO venues (name, status) VALUES ('Other venue', 'PUBLISHED')")
                }
            val otherChat =
                client.post("/api/guest/support/venue-chats") {
                    bearerAuth(first)
                    contentType(ContentType.Application.Json)
                    setBody("""{"venueId":$otherVenue}""")
                }.payload().getValue("thread").jsonObject.getValue("threadId").jsonPrimitive.content
            val otherReply =
                send(first, "Different question", otherChat).payload().getValue("message").jsonObject
            assertEquals(otherChat, otherReply.getValue("threadId").jsonPrimitive.content)
            assertTrue(otherReply.getValue("messageId") != replies.first())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `retention reads preserve visibility historical ordering and tenant boundaries`(postgres: Boolean) {
        val database = testDatabase(postgres)
        val config = PostgresTestEnv.buildConfig(database)
        testApplication {
            environment { this.config = config }
            application { module() }
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            val fixture = database.connect().use(::seedRetention)
            val first = token(config, 1001)
            val second = token(config, 1002)

            val guestPaths =
                listOf("/catalog/promotions", "/bookings/archive", "/visits", "/favorites/venues", "/loyalty")
            for (path in guestPaths) {
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/guest$path").status)
            }
            val promotions =
                client.get(
                    "/api/guest/catalog/promotions",
                ) { bearerAuth(first) }.payload().getValue("items").jsonArray
            assertEquals(
                listOf("Listed offer"),
                promotions.map {
                    it.jsonObject.getValue(
                        "promotion",
                    ).jsonObject.getValue("title").jsonPrimitive.content
                },
            )
            val unlisted =
                client.get("/api/guest/venue/${fixture.unlisted}") {
                    bearerAuth(first)
                }.payload().getValue("venue").jsonObject
            assertEquals(
                "Unlisted offer",
                unlisted.getValue("promotions").jsonArray.single().jsonObject.getValue("title").jsonPrimitive.content,
            )

            val bookings =
                client.get(
                    "/api/guest/bookings/archive",
                ) { bearerAuth(first) }.payload().getValue("items").jsonArray
            assertEquals(
                listOf("no_show", "canceled"),
                bookings.map { it.jsonObject.getValue("status").jsonPrimitive.content },
            )
            val archivePage =
                client.get("/api/guest/bookings/archive") {
                    bearerAuth(first)
                    parameter("beforeId", bookings.first().jsonObject.getValue("bookingId").jsonPrimitive.content)
                }.payload().getValue("items").jsonArray
            assertEquals(1, archivePage.size)

            suspend fun visits(
                userToken: String,
                withOrders: Boolean,
                cursor: Long? = null,
            ) = client.get("/api/guest/visits") {
                bearerAuth(userToken)
                parameter("limit", 1)
                parameter("withOrders", withOrders)
                cursor?.let { parameter("cursor", it) }
            }.payload().getValue("items").jsonArray

            assertEquals(
                fixture.newerVisit.toString(),
                visits(first, false).single().jsonObject.getValue("visitId").jsonPrimitive.content,
            )
            val nextVisit = visits(first, false, fixture.newerVisit)
            assertEquals(
                fixture.olderVisit.toString(),
                nextVisit.single().jsonObject.getValue("visitId").jsonPrimitive.content,
            )
            assertEquals(
                fixture.olderVisit.toString(),
                visits(first, true).single().jsonObject.getValue("visitId").jsonPrimitive.content,
            )
            assertTrue(visits(second, true).isEmpty())

            val initialFavorites =
                client.get("/api/guest/favorites/venues?limit=1") { bearerAuth(first) }.payload()
            val cursor = initialFavorites.getValue("nextCursor").jsonPrimitive.content
            val nextFavorites =
                client.get("/api/guest/favorites/venues?limit=1") {
                    bearerAuth(first)
                    parameter("cursor", cursor)
                }.payload()
            val favoriteIds =
                listOf(initialFavorites, nextFavorites).map { page ->
                    page.getValue("venues").jsonArray.single().jsonObject.getValue("venueId").jsonPrimitive.content
                }
            assertEquals(listOf(fixture.unlisted.toString(), fixture.listed.toString()), favoriteIds)
            assertTrue(
                client.get("/api/guest/favorites/venues?limit=1") {
                    bearerAuth(first)
                    parameter("cursor", nextFavorites.getValue("nextCursor").jsonPrimitive.content)
                }.payload().getValue("venues").jsonArray.isEmpty(),
            )
            assertTrue(
                client.get("/api/guest/favorites/venues") {
                    bearerAuth(second)
                }.payload().getValue("venues").jsonArray.isEmpty(),
            )

            val loyalty =
                client.get("/api/guest/loyalty") {
                    bearerAuth(first)
                }.payload().getValue("items").jsonArray.single().jsonObject
            assertEquals("5", loyalty.getValue("nthValue").jsonPrimitive.content)
            assertEquals("2", loyalty.getValue("rewardsAvailable").jsonPrimitive.content)
            assertEquals("1", loyalty.getValue("rewardsReserved").jsonPrimitive.content)
            assertTrue(
                client.get("/api/guest/loyalty") { bearerAuth(second) }.payload().getValue("items").jsonArray.isEmpty(),
            )
            val noProgress =
                client.get("/api/guest/loyalty?venueId=${fixture.listed}") {
                    bearerAuth(second)
                }.payload().getValue("items").jsonArray.single().jsonObject
            assertEquals("0", noProgress.getValue("rewardsAvailable").jsonPrimitive.content)
            database.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT user_id, progress_count, rewards_available, rewards_reserved " +
                            "FROM guest_loyalty_progress",
                    ).use {
                        assertTrue(it.next())
                        assertEquals(1001L, it.getLong(1))
                        assertEquals(3, it.getInt(2))
                        assertEquals(2, it.getInt(3))
                        assertEquals(1, it.getInt(4))
                        assertFalse(it.next())
                    }
                    for (table in listOf("guest_loyalty_ledger", "loyalty_redemptions")) {
                        statement.executeQuery("SELECT COUNT(*) FROM $table").use {
                            assertTrue(it.next())
                            assertEquals(0, it.getInt(1))
                        }
                    }
                }
            }
        }
    }

    private suspend fun Connection.awaitBlockedBirthdayWrites() =
        withTimeout(5_000) {
            val sql =
                """
                SELECT COUNT(*)
                FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock'
                  AND query LIKE '%UPDATE users%' AND query LIKE '%SET birthday_month%'
                """.trimIndent()
            createStatement().use { statement ->
                while (true) {
                    val blocked =
                        statement.executeQuery(sql).use { rows ->
                            check(rows.next())
                            rows.getInt(1)
                        }
                    if (blocked >= 2) break
                    delay(10)
                }
            }
        }

    private fun seedRetention(connection: Connection): RetentionFixture {
        connection.update("INSERT INTO users (telegram_user_id) VALUES (1001), (1002)")
        val listed = connection.insert("INSERT INTO venues (name, status) VALUES ('Listed', 'PUBLISHED')")
        val unlisted =
            connection.insert(
                "INSERT INTO venues (name, status, catalog_listed) VALUES ('Unlisted', 'PUBLISHED', FALSE)",
            )
        val hidden = connection.insert("INSERT INTO venues (name, status) VALUES ('Hidden', 'HIDDEN')")
        val blocked = connection.insert("INSERT INTO venues (name, status) VALUES ('Blocked', 'PUBLISHED')")
        connection.update("INSERT INTO venue_subscriptions (venue_id, status) VALUES ($blocked, 'SUSPENDED')")
        for ((id, title) in listOf(
            listed to "Listed",
            unlisted to "Unlisted",
            hidden to "Hidden",
            blocked to "Blocked",
        )) {
            connection.update(
                "INSERT INTO venue_promotions (venue_id, title, description, status, created_by_user_id) " +
                    "VALUES ($id, '$title offer', 'Terms', 'ACTIVE', 1001)",
            )
        }
        connection.update(
            "INSERT INTO venue_promotions (venue_id, title, description, status, ends_at, created_by_user_id) " +
                "VALUES ($listed, 'Expired', 'Terms', 'ACTIVE', TIMESTAMP '2020-01-01 00:00:00', 1001), " +
                "($listed, 'Draft', 'Terms', 'DRAFT', NULL, 1001), ($listed, 'Paused', 'Terms', 'PAUSED', NULL, 1001)",
        )
        for ((index, status) in listOf("CANCELED", "NO_SHOW", "CONFIRMED", "CANCELED").withIndex()) {
            val userId = if (index == 3) 1002 else 1001
            val date = "2030-01-0${index + 1}"
            connection.update(
                "INSERT INTO bookings (venue_id, user_id, scheduled_at, status, display_date, display_number) " +
                    "VALUES ($listed, $userId, TIMESTAMP '$date 12:00:00', '$status', DATE '$date', 1)",
            )
        }
        val table = connection.insert("INSERT INTO venue_tables (venue_id, table_number) VALUES ($listed, 1)")
        val session =
            connection.insert(
                "INSERT INTO table_sessions (venue_id, table_id, started_at, last_activity_at, expires_at, status) " +
                    "VALUES ($listed, $table, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'ACTIVE')",
            )
        val order =
            connection.insert(
                "INSERT INTO orders (venue_id, table_id, table_session_id, status) " +
                    "VALUES ($listed, $table, $session, 'CLOSED')",
            )
        connection.update(
            "INSERT INTO order_batches (order_id, author_user_id, source, status) " +
                "VALUES ($order, 1001, 'MINIAPP', 'DELIVERED')",
        )
        val newerVisit =
            connection.insert(
                "INSERT INTO visits (venue_id, user_id, source, occurred_at, service_date) " +
                    "VALUES ($listed, 1001, 'ORDER_CLOSED', TIMESTAMP '2030-02-01 12:00:00', DATE '2030-02-01')",
            )
        val olderVisit =
            connection.insert(
                "INSERT INTO visits (venue_id, user_id, order_id, source, occurred_at, service_date) " +
                    "VALUES ($listed, 1001, $order, 'ORDER_CLOSED', " +
                    "TIMESTAMP '2030-01-01 12:00:00', DATE '2030-01-01')",
            )
        connection.update(
            "INSERT INTO visits (venue_id, user_id, order_id, source, occurred_at, service_date) " +
                "VALUES ($listed, 1002, $order, 'ORDER_CLOSED', TIMESTAMP '2030-01-01 12:00:00', DATE '2030-01-01')",
        )
        connection.update(
            "INSERT INTO guest_favorite_venues (user_id, venue_id, created_at) VALUES " +
                "(1001, $listed, TIMESTAMP '2030-01-01 00:00:00'), " +
                "(1001, $unlisted, TIMESTAMP '2030-01-01 00:00:00'), " +
                "(1001, $hidden, TIMESTAMP '2030-02-01 00:00:00'), " +
                "(1001, $blocked, TIMESTAMP '2030-02-01 00:00:00')",
        )
        val program =
            connection.insert(
                "INSERT INTO loyalty_programs (venue_id, program_type, status, nth_value, created_by_user_id) " +
                    "VALUES ($listed, 'NTH_HOOKAH_FREE', 'ACTIVE', 5, 1001)",
            )
        connection.update(
            "INSERT INTO guest_loyalty_progress " +
                "(program_id, venue_id, user_id, progress_count, rewards_available, rewards_reserved) " +
                "VALUES ($program, $listed, 1001, 3, 2, 1)",
        )
        return RetentionFixture(listed, unlisted, newerVisit, olderVisit)
    }

    private fun testDatabase(postgres: Boolean): PostgresTestDatabase {
        val schema = "guest_experience_${UUID.randomUUID().toString().replace("-", "")}"
        if (!postgres) {
            return PostgresTestDatabase(
                schema,
                "jdbc:h2:mem:$schema;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa",
                "",
            )
        }
        val port =
            System.getenv("GUEST_EXPERIENCE_TEST_PG_PORT")?.toIntOrNull()
                ?: return PostgresTestEnv.createDatabase()
        val url = "jdbc:postgresql://127.0.0.1:$port/guest_experience_test"
        DriverManager.getConnection(url, "guest_experience_test", "").use { it.update("CREATE SCHEMA $schema") }
        return PostgresTestDatabase(schema, "$url?currentSchema=$schema", "guest_experience_test", "")
    }

    private fun PostgresTestDatabase.connect() = DriverManager.getConnection(jdbcUrl, user, password)

    private fun token(
        config: MapApplicationConfig,
        userId: Long,
    ) = SessionTokenService(SessionTokenConfig.from(config, "test")).issueToken(userId).token

    private suspend fun HttpResponse.payload() =
        bodyAsText().let {
            assertEquals(HttpStatusCode.OK, status, it)
            Json.parseToJsonElement(it).jsonObject
        }

    private fun Connection.update(sql: String) = createStatement().use { it.executeUpdate(sql) }

    private fun Connection.insert(sql: String): Long =
        prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                check(keys.next())
                keys.getLong("id")
            }
        }

    private data class RetentionFixture(
        val listed: Long,
        val unlisted: Long,
        val newerVisit: Long,
        val olderVisit: Long,
    )
}
