package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.ActiveOrderResponse
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
import io.ktor.client.HttpClient
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
import kotlin.test.assertFalse
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
                client.get("/api/guest/table/resolve?tableToken=tabs-resolve-token&resolveMode=create") {
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
    fun `create shared tab is idempotent for same owner and table session`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-tabs-shared-idempotent")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 33)
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)

            val ownerToken = issueToken(config, userId = 90909)
            val requestBody =
                json.encodeToString(
                    CreateSharedTabRequest.serializer(),
                    CreateSharedTabRequest(tableSessionId),
                )

            val firstCreateResponse =
                client.post("/api/guest/tabs/shared") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    setBody(requestBody)
                }
            assertEquals(HttpStatusCode.OK, firstCreateResponse.status)
            val firstTab = json.decodeFromString(GuestTabResponse.serializer(), firstCreateResponse.bodyAsText()).tab

            val secondCreateResponse =
                client.post("/api/guest/tabs/shared") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    setBody(requestBody)
                }
            assertEquals(HttpStatusCode.OK, secondCreateResponse.status)
            val secondTab = json.decodeFromString(GuestTabResponse.serializer(), secondCreateResponse.bodyAsText()).tab

            assertEquals(firstTab.id, secondTab.id)

            val tabsResponse =
                client.get("/api/guest/tabs?table_session_id=$tableSessionId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, tabsResponse.status)
            val tabsPayload = json.decodeFromString(GuestTabsResponse.serializer(), tabsResponse.bodyAsText())
            assertEquals(1, tabsPayload.tabs.count { it.type == "SHARED" && it.status == "ACTIVE" })
        }

    @Test
    fun `active order lookup does not leak shared tab batches across table sessions`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-tabs-session-active-isolation")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 38)
            seedTableToken(jdbcUrl, tableId, "tabs-session-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val firstSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val secondSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Session item")

            val ownerToken = issueToken(config, userId = 81818)
            val firstSharedTabId = createSharedTab(client, ownerToken, firstSessionId)
            val secondSharedTabId = createSharedTab(client, ownerToken, secondSessionId)

            val addBatchResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            AddBatchRequest(
                                tableToken = "tabs-session-token",
                                tableSessionId = firstSessionId,
                                tabId = firstSharedTabId,
                                idempotencyKey = "tabs-session-active-1",
                                items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
                                comment = "session one",
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, addBatchResponse.status)

            val firstActiveResponse =
                client.get(
                    "/api/guest/order/active?tableToken=tabs-session-token&tableSessionId=$firstSessionId&tabId=$firstSharedTabId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            val secondActiveResponse =
                client.get(
                    "/api/guest/order/active?tableToken=tabs-session-token&tableSessionId=$secondSessionId&tabId=$secondSharedTabId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            val crossSessionResponse =
                client.get(
                    "/api/guest/order/active?tableToken=tabs-session-token&tableSessionId=$secondSessionId&tabId=$firstSharedTabId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }

            assertEquals(HttpStatusCode.OK, firstActiveResponse.status)
            assertEquals(HttpStatusCode.OK, secondActiveResponse.status)
            assertEquals(HttpStatusCode.Forbidden, crossSessionResponse.status)
            assertTrue(
                json.decodeFromString(ActiveOrderResponse.serializer(), firstActiveResponse.bodyAsText()).order != null,
            )
            assertEquals(
                null,
                json.decodeFromString(ActiveOrderResponse.serializer(), secondActiveResponse.bodyAsText()).order,
            )
        }

    @Test
    fun `invite returns four digit code and removes expired invites`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-tabs-invite-code")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 37)
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val ownerUserId = 88888L
            val ownerToken = issueToken(config, userId = ownerUserId)
            val tabId = createSharedTab(client, ownerToken, tableSessionId)

            seedExpiredInvite(jdbcUrl, tabId, ownerUserId, token = "0001")
            assertEquals(1, countExpiredInvites(jdbcUrl))

            val inviteToken = createTabInvite(client, ownerToken, tableSessionId, tabId)
            assertTrue(Regex("\\d{4}").matches(inviteToken))
            assertEquals(0, countExpiredInvites(jdbcUrl))
        }

    @Test
    fun `join shared closes empty owner-only shared tabs of joiner in same session`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-tabs-join-cleanup-empty")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 34)
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)

            val ownerToken = issueToken(config, userId = 11111)
            val anotherOwnerToken = issueToken(config, userId = 22222)

            val ownSharedTabId = createSharedTab(client, ownerToken, tableSessionId)
            val targetSharedTabId = createSharedTab(client, anotherOwnerToken, tableSessionId)
            val targetInviteToken = createTabInvite(client, anotherOwnerToken, tableSessionId, targetSharedTabId)

            joinSharedTab(client, ownerToken, tableSessionId, targetInviteToken)

            assertEquals("CLOSED", fetchTabStatus(jdbcUrl, ownSharedTabId))
            assertEquals("ACTIVE", fetchTabStatus(jdbcUrl, targetSharedTabId))

            val tabsResponse =
                client.get("/api/guest/tabs?table_session_id=$tableSessionId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, tabsResponse.status)
            val tabsPayload = json.decodeFromString(GuestTabsResponse.serializer(), tabsResponse.bodyAsText())
            assertFalse(tabsPayload.tabs.any { it.id == ownSharedTabId })
            assertTrue(tabsPayload.tabs.any { it.id == targetSharedTabId })
        }

    @Test
    fun `join shared does not close own non-empty shared tab`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-tabs-join-cleanup-nonempty")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 35)
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)

            val ownerToken = issueToken(config, userId = 33333)
            val anotherOwnerToken = issueToken(config, userId = 44444)

            val ownSharedTabId = createSharedTab(client, ownerToken, tableSessionId)
            seedOrderBatchForTab(jdbcUrl, venueId, tableId, ownSharedTabId)
            val targetSharedTabId = createSharedTab(client, anotherOwnerToken, tableSessionId)
            val targetInviteToken = createTabInvite(client, anotherOwnerToken, tableSessionId, targetSharedTabId)

            joinSharedTab(client, ownerToken, tableSessionId, targetInviteToken)

            assertEquals("ACTIVE", fetchTabStatus(jdbcUrl, ownSharedTabId))
        }

    @Test
    fun `join shared does not close own shared tab with other members`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-tabs-join-cleanup-members")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 36)
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)

            val ownerToken = issueToken(config, userId = 55555)
            val anotherOwnerToken = issueToken(config, userId = 66666)
            val memberToken = issueToken(config, userId = 77777)

            val ownSharedTabId = createSharedTab(client, ownerToken, tableSessionId)
            val ownSharedInviteToken = createTabInvite(client, ownerToken, tableSessionId, ownSharedTabId)
            joinSharedTab(client, memberToken, tableSessionId, ownSharedInviteToken)

            val targetSharedTabId = createSharedTab(client, anotherOwnerToken, tableSessionId)
            val targetInviteToken = createTabInvite(client, anotherOwnerToken, tableSessionId, targetSharedTabId)
            joinSharedTab(client, ownerToken, tableSessionId, targetInviteToken)

            assertEquals("ACTIVE", fetchTabStatus(jdbcUrl, ownSharedTabId))
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

    private suspend fun createSharedTab(
        client: HttpClient,
        authToken: String,
        tableSessionId: Long,
    ): Long {
        val response =
            client.post("/api/guest/tabs/shared") {
                contentType(ContentType.Application.Json)
                headers { append(HttpHeaders.Authorization, "Bearer $authToken") }
                setBody(
                    json.encodeToString(
                        CreateSharedTabRequest.serializer(),
                        CreateSharedTabRequest(tableSessionId),
                    ),
                )
            }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.decodeFromString(GuestTabResponse.serializer(), response.bodyAsText()).tab.id
    }

    private suspend fun createTabInvite(
        client: HttpClient,
        authToken: String,
        tableSessionId: Long,
        tabId: Long,
    ): String {
        val response =
            client.post("/api/guest/tabs/$tabId/invite") {
                contentType(ContentType.Application.Json)
                headers { append(HttpHeaders.Authorization, "Bearer $authToken") }
                setBody(
                    json.encodeToString(
                        CreateTabInviteRequest.serializer(),
                        CreateTabInviteRequest(tableSessionId, 600),
                    ),
                )
            }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.decodeFromString(CreateTabInviteResponse.serializer(), response.bodyAsText()).token
    }

    private suspend fun joinSharedTab(
        client: HttpClient,
        authToken: String,
        tableSessionId: Long,
        token: String,
    ) {
        val response =
            client.post("/api/guest/tabs/join") {
                contentType(ContentType.Application.Json)
                headers { append(HttpHeaders.Authorization, "Bearer $authToken") }
                setBody(
                    json.encodeToString(
                        JoinTabRequest.serializer(),
                        JoinTabRequest(tableSessionId = tableSessionId, token = token, consent = true),
                    ),
                )
            }
        assertEquals(HttpStatusCode.OK, response.status)
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

    private fun seedOrderBatchForTab(
        jdbcUrl: String,
        venueId: Long,
        tableId: Long,
        tabId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val orderId =
                connection.prepareStatement(
                    """
                    INSERT INTO orders (venue_id, table_id, table_session_id, status)
                    VALUES (?, ?, (SELECT table_session_id FROM tab WHERE id = ?), 'ACTIVE')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, tableId)
                    statement.setLong(3, tabId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) {
                            rs.getLong(1)
                        } else {
                            error("Failed to insert order")
                        }
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO order_batches (order_id, source, status, items_snapshot, tab_id)
                VALUES (?, 'MINIAPP', 'NEW', ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, orderId)
                statement.setString(2, "[]")
                statement.setLong(3, tabId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedExpiredInvite(
        jdbcUrl: String,
        tabId: Long,
        createdBy: Long,
        token: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO tab_invite (tab_id, token, expires_at, created_by)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, tabId)
                statement.setString(2, token)
                statement.setTimestamp(3, Timestamp.from(Instant.now().minus(60, ChronoUnit.SECONDS)))
                statement.setLong(4, createdBy)
                statement.executeUpdate()
            }
        }
    }

    private fun countExpiredInvites(jdbcUrl: String): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*) AS c
                FROM tab_invite
                WHERE expires_at <= CURRENT_TIMESTAMP
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getInt("c")
                    }
                }
            }
        }
        return 0
    }

    private fun fetchTabStatus(
        jdbcUrl: String,
        tabId: Long,
    ): String? {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT status
                FROM tab
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, tabId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getString("status")
                    }
                }
            }
        }
        return null
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
