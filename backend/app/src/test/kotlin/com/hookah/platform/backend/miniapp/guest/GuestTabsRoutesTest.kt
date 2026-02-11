package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.AddBatchItemDto
import com.hookah.platform.backend.miniapp.guest.api.AddBatchRequest
import com.hookah.platform.backend.miniapp.guest.api.CreateSharedTabRequest
import com.hookah.platform.backend.miniapp.guest.api.CreateTabInviteRequest
import com.hookah.platform.backend.miniapp.guest.api.CreateTabInviteResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestTabResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestTabsResponse
import com.hookah.platform.backend.miniapp.guest.api.JoinTabRequest
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuestTabsRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `personal tab auto-created after table resolve`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-tabs-personal")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 30)
            seedTableToken(jdbcUrl, tableId, "tabs-resolve-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")

            val token = issueToken(config, userId = 10101)
            val resolveResponse =
                client.get("/api/guest/table/resolve?tableToken=tabs-resolve-token") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, resolveResponse.status)
            val tableSessionId =
                json.parseToJsonElement(
                    resolveResponse.bodyAsText(),
                ).jsonObject["tableSessionId"]!!.jsonPrimitive.content.toLong()

            val tabsResponse =
                client.get("/api/guest/tabs?table_session_id=$tableSessionId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, tabsResponse.status)
            val payload = json.decodeFromString(GuestTabsResponse.serializer(), tabsResponse.bodyAsText())
            assertEquals(1, payload.tabs.size)
            assertEquals("PERSONAL", payload.tabs.first().type)
            assertEquals(10101, payload.tabs.first().ownerUserId)
        }

    @Test
    fun `shared tab requires join and consent`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-tabs-shared")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 31)
            seedTableToken(jdbcUrl, tableId, "tabs-shared-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Lemonade")

            val ownerToken = issueToken(config, userId = 20202)
            val sharedTabResponse =
                client.post("/api/guest/tabs/shared") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    setBody(
                        json.encodeToString(
                            CreateSharedTabRequest.serializer(),
                            CreateSharedTabRequest(tableSessionId),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, sharedTabResponse.status)
            val sharedTab = json.decodeFromString(GuestTabResponse.serializer(), sharedTabResponse.bodyAsText()).tab

            val inviteResponse =
                client.post("/api/guest/tabs/${sharedTab.id}/invite") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    setBody(
                        json.encodeToString(
                            CreateTabInviteRequest.serializer(),
                            CreateTabInviteRequest(tableSessionId, 600),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, inviteResponse.status)
            val inviteToken =
                json.decodeFromString(CreateTabInviteResponse.serializer(), inviteResponse.bodyAsText()).token

            val guestToken = issueToken(config, userId = 30303)
            val preJoinBatchResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            AddBatchRequest(
                                tableToken = "tabs-shared-token",
                                tableSessionId = tableSessionId,
                                tabId = sharedTab.id,
                                idempotencyKey = "tabs-shared-nojoin-1",
                                items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
                                comment = null,
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Forbidden, preJoinBatchResponse.status)
            assertApiErrorEnvelope(preJoinBatchResponse, ApiErrorCodes.FORBIDDEN)

            val noConsentResponse =
                client.post("/api/guest/tabs/join") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                    setBody(
                        json.encodeToString(
                            JoinTabRequest.serializer(),
                            JoinTabRequest(tableSessionId = tableSessionId, token = inviteToken, consent = false),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, noConsentResponse.status)

            val joinResponse =
                client.post("/api/guest/tabs/join") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                    setBody(
                        json.encodeToString(
                            JoinTabRequest.serializer(),
                            JoinTabRequest(tableSessionId = tableSessionId, token = inviteToken, consent = true),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, joinResponse.status)

            val postJoinBatchResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            AddBatchRequest(
                                tableToken = "tabs-shared-token",
                                tableSessionId = tableSessionId,
                                tabId = sharedTab.id,
                                idempotencyKey = "tabs-shared-join-1",
                                items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
                                comment = "joined",
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, postJoinBatchResponse.status)
            val tabsResponse =
                client.get("/api/guest/tabs?table_session_id=$tableSessionId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                }
            val tabsPayload = json.decodeFromString(GuestTabsResponse.serializer(), tabsResponse.bodyAsText())
            assertTrue(tabsPayload.tabs.any { it.id == sharedTab.id })
        }

    @Test
    fun `join tab rejects too long token`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-tabs-join-token-limit")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 32)
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val guestToken = issueToken(config, userId = 40404)

            val tooLongToken = "x".repeat(129)
            val response =
                client.post("/api/guest/tabs/join") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                    setBody(
                        json.encodeToString(
                            JoinTabRequest.serializer(),
                            JoinTabRequest(tableSessionId = tableSessionId, token = tooLongToken, consent = true),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String): MapApplicationConfig =
        MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
            "telegram.botToken" to "123456:ABCDEF",
            "telegram.webAppUrl" to "https://example.com/app",
            "telegram.webhookSecret" to "secret",
            "telegram.webhookPath" to "/webhook/test",
            "telegram.enabled" to "false",
        )

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long,
    ): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(userId).token
    }

    private fun seedVenue(
        jdbcUrl: String,
        status: String,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venues (name, city, address, status)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, "Venue")
                statement.setString(2, "City")
                statement.setString(3, "Address")
                statement.setString(4, status)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) return rs.getLong(1)
                }
            }
            error("Failed to insert venue")
        }

    private fun seedTable(
        jdbcUrl: String,
        venueId: Long,
        tableNumber: Int,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_tables (venue_id, table_number)
                VALUES (?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setInt(2, tableNumber)
                statement.executeUpdate()
                statement.generatedKeys.use { rs -> if (rs.next()) return rs.getLong(1) }
            }
            error("Failed to insert table")
        }

    private fun seedTableToken(
        jdbcUrl: String,
        tableId: Long,
        token: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO table_tokens (token, table_id, is_active)
                VALUES (?, ?, true)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, token)
                statement.setLong(2, tableId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedSubscription(
        jdbcUrl: String,
        venueId: Long,
        status: String,
    ) {
        val now = Instant.now()
        val trialEnd = now.plus(14, ChronoUnit.DAYS)
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, status)
                statement.setTimestamp(3, Timestamp.from(trialEnd))
                statement.setTimestamp(4, Timestamp.from(now))
                statement.setTimestamp(5, Timestamp.from(now))
                statement.executeUpdate()
            }
        }
    }

    private fun seedTableSession(
        jdbcUrl: String,
        venueId: Long,
        tableId: Long,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO table_sessions (venue_id, table_id, started_at, last_activity_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                val now = Instant.now()
                statement.setLong(1, venueId)
                statement.setLong(2, tableId)
                statement.setTimestamp(3, Timestamp.from(now))
                statement.setTimestamp(4, Timestamp.from(now))
                statement.setTimestamp(5, Timestamp.from(now.plus(1, ChronoUnit.HOURS)))
                statement.executeUpdate()
                statement.generatedKeys.use { rs -> if (rs.next()) return rs.getLong(1) }
            }
            error("Failed to insert session")
        }

    private fun seedMenuCategory(
        jdbcUrl: String,
        venueId: Long,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO menu_categories (venue_id, name, sort_order)
                VALUES (?, ?, 0)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, "Drinks")
                statement.executeUpdate()
                statement.generatedKeys.use { rs -> if (rs.next()) return rs.getLong(1) }
            }
            error("Failed to insert category")
        }

    private fun seedMenuItem(
        jdbcUrl: String,
        venueId: Long,
        categoryId: Long,
        name: String,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO menu_items (
                    venue_id,
                    category_id,
                    name,
                    description,
                    price_minor,
                    currency,
                    is_available,
                    sort_order
                )
                VALUES (?, ?, ?, ?, ?, ?, true, 0)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, categoryId)
                statement.setString(3, name)
                statement.setString(4, "desc")
                statement.setLong(5, 100)
                statement.setString(6, "RUB")
                statement.executeUpdate()
                statement.generatedKeys.use { rs -> if (rs.next()) return rs.getLong(1) }
            }
            error("Failed to insert menu item")
        }
}
