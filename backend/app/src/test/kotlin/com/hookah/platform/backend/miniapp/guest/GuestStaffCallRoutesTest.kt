package com.hookah.platform.backend.miniapp.guest

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.StaffCallRequest
import com.hookah.platform.backend.miniapp.guest.api.StaffCallResponse
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRecord
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionStatus
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.module
import com.hookah.platform.backend.telegram.StaffCallNotification
import com.hookah.platform.backend.telegram.StaffCallNotificationType
import com.hookah.platform.backend.telegram.StaffCallReason
import com.hookah.platform.backend.telegram.StaffChatNotificationResult
import com.hookah.platform.backend.telegram.StaffChatNotifier
import com.hookah.platform.backend.telegram.TableContext
import com.hookah.platform.backend.telegram.db.CreatedStaffCall
import com.hookah.platform.backend.telegram.db.StaffCallRepository
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
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GuestStaffCallRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `unauthorized staff call returns 401`() =
        testApplication {
            val config =
                MapApplicationConfig(
                    "app.env" to appEnv,
                    "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to "",
                )

            environment { this.config = config }
            application { module() }

            val request =
                StaffCallRequest(
                    tableToken = "token",
                    tableSessionId = 1,
                    reason = "BILL",
                    comment = null,
                )
            val response =
                client.post("/api/guest/staff-call") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(StaffCallRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.UNAUTHORIZED)
        }

    @Test
    fun `staff call requires table session id`() =
        testApplication {
            val config =
                MapApplicationConfig(
                    "app.env" to appEnv,
                    "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to "",
                )

            environment { this.config = config }
            application { module() }

            val token = issueToken(config)
            val response =
                client.post("/api/guest/staff-call") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(
                        """
                        {
                          "tableToken": "staff-token",
                          "reason": "BILL",
                          "comment": null
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `happy path staff call`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-staff-call")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 10)
            seedTableToken(jdbcUrl, tableId, "staff-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val token = issueToken(config)
            val request =
                StaffCallRequest(
                    tableToken = "staff-token",
                    tableSessionId = tableSessionId,
                    reason = " bill ",
                    comment = "  Нужны угли  ",
                )
            val response =
                client.post("/api/guest/staff-call") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(StaffCallRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(StaffCallResponse.serializer(), response.bodyAsText())
            assertTrue(payload.staffCallId > 0)
            assertTrue(payload.createdAtEpochSeconds > 0)

            val stored = fetchStaffCall(jdbcUrl, payload.staffCallId)
            assertNotNull(stored)
            assertEquals("BILL", stored.reason)
            assertEquals("Нужны угли", stored.comment)
            assertEquals(TELEGRAM_USER_ID, stored.createdByUserId)
        }

    @Test
    fun `api staff call notification uses readable staff call event`() =
        testApplication {
            val config =
                MapApplicationConfig(
                    "app.env" to appEnv,
                    "api.session.jwtSecret" to "test-secret",
                )
            val sessionConfig = SessionTokenConfig.from(config, appEnv)
            val algorithm = Algorithm.HMAC256(sessionConfig.jwtSecret)
            val guestVenueRepository: GuestVenueRepository = mockk()
            val subscriptionRepository: SubscriptionRepository = mockk()
            val staffCallRepository: StaffCallRepository = mockk()
            val tableSessionRepository: TableSessionRepository = mockk()
            val staffChatNotifier: StaffChatNotifier = mockk()
            val rateLimiter =
                object : RateLimiter {
                    override fun tryAcquire(
                        key: GuestRateLimitKey,
                        limit: Int,
                        window: Duration,
                        now: Instant,
                    ): Boolean = true
                }
            val table =
                TableContext(
                    venueId = 10L,
                    venueName = "Mix",
                    tableId = 11L,
                    tableNumber = 105,
                    tableToken = "staff-token",
                    staffChatId = -777L,
                )

            coEvery {
                tableSessionRepository.touchActiveSession(
                    tableSessionId = 55L,
                    venueId = 10L,
                    tableId = 11L,
                    ttl = any(),
                    now = any(),
                )
            } returns
                TableSessionRecord(
                    id = 55L,
                    venueId = 10L,
                    tableId = 11L,
                    startedAt = Instant.parse("2026-04-29T10:00:00Z"),
                    lastActivityAt = Instant.parse("2026-04-29T10:00:00Z"),
                    expiresAt = Instant.parse("2026-04-29T12:00:00Z"),
                    endedAt = null,
                    status = TableSessionStatus.ACTIVE,
                )
            coEvery { guestVenueRepository.findVenueByIdForGuest(10L) } returns
                com.hookah.platform.backend.miniapp.guest.db.VenueShort(
                    id = 10L,
                    name = "Mix",
                    city = "City",
                    address = "Address",
                    status = VenueStatus.PUBLISHED,
                )
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery {
                staffCallRepository.createGuestStaffCall(
                    venueId = 10L,
                    tableId = 11L,
                    tableSessionId = 55L,
                    createdByUserId = TELEGRAM_USER_ID,
                    reason = StaffCallReason.BILL,
                    comment = "Нужны угли",
                )
            } returns CreatedStaffCall(id = 6L, createdAt = Instant.parse("2026-04-29T10:10:00Z"))
            coEvery { staffChatNotifier.notifyStaffCallNow(any()) } returns StaffChatNotificationResult.SENT_OR_QUEUED

            application {
                install(ContentNegotiation) { json(this@GuestStaffCallRoutesTest.json) }
                install(Authentication) {
                    jwt("miniapp-session") {
                        verifier(
                            JWT
                                .require(algorithm)
                                .withIssuer(sessionConfig.issuer)
                                .withAudience(sessionConfig.audience)
                                .build(),
                        )
                        validate { credentials ->
                            val subject = credentials.payload.subject ?: return@validate null
                            subject.toLongOrNull() ?: return@validate null
                            JWTPrincipal(credentials.payload)
                        }
                    }
                }
                routing {
                    authenticate("miniapp-session") {
                        guestStaffCallRoutes(
                            guestRateLimitConfig =
                                GuestRateLimitConfig(
                                    staffCall = GuestRateLimitPolicy(5, Duration.ofSeconds(30)),
                                    addBatch = GuestRateLimitPolicy(5, Duration.ofSeconds(10)),
                                ),
                            rateLimiter = rateLimiter,
                            tableTokenResolver = { token -> if (token == "staff-token") table else null },
                            guestVenueRepository = guestVenueRepository,
                            subscriptionRepository = subscriptionRepository,
                            staffCallRepository = staffCallRepository,
                            tableSessionRepository = tableSessionRepository,
                            tableSessionConfig =
                                TableSessionConfig(
                                    ttl = Duration.ofHours(2),
                                    cleanupInterval = Duration.ofMinutes(1),
                                ),
                            staffChatNotifier = staffChatNotifier,
                        )
                    }
                }
            }

            val token = SessionTokenService(sessionConfig).issueToken(TELEGRAM_USER_ID).token
            val request =
                StaffCallRequest(
                    tableToken = "staff-token",
                    tableSessionId = 55L,
                    reason = "BILL",
                    comment = "Нужны угли",
                )
            val response =
                client.post("/staff-call") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(StaffCallRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify {
                staffChatNotifier.notifyStaffCallNow(
                    match<StaffCallNotification> {
                        it.venueId == 10L &&
                            it.staffCallId == 6L &&
                            it.tableLabel == "105" &&
                            it.reason == StaffCallReason.BILL &&
                            it.comment == "Нужны угли" &&
                            it.tableSessionId == 55L &&
                            it.orderId == null &&
                            it.type == StaffCallNotificationType.NORMAL
                    },
                )
            }
        }

    @Test
    fun `invalid reason rejects staff call`() =
        testApplication {
            val config =
                MapApplicationConfig(
                    "app.env" to appEnv,
                    "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to "",
                )

            environment { this.config = config }
            application { module() }

            val token = issueToken(config)
            val invalidReasons =
                listOf(
                    "   ",
                    "кириллица",
                    "a",
                    "A".repeat(33),
                )
            invalidReasons.forEach { reason ->
                val request =
                    StaffCallRequest(
                        tableToken = "valid-token",
                        tableSessionId = 1,
                        reason = reason,
                        comment = null,
                    )
                val response =
                    client.post("/api/guest/staff-call") {
                        contentType(ContentType.Application.Json)
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                        setBody(json.encodeToString(StaffCallRequest.serializer(), request))
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
            }
        }

    @Test
    fun `invalid comment rejects staff call`() =
        testApplication {
            val config =
                MapApplicationConfig(
                    "app.env" to appEnv,
                    "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to "",
                )

            environment { this.config = config }
            application { module() }

            val token = issueToken(config)
            val request =
                StaffCallRequest(
                    tableToken = "valid-token",
                    tableSessionId = 1,
                    reason = "BILL",
                    comment = "a".repeat(501),
                )
            val response =
                client.post("/api/guest/staff-call") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(StaffCallRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `staff call rate limit blocks spam`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-staff-rate-limit")
            val config =
                buildConfig(
                    jdbcUrl = jdbcUrl,
                    guestStaffCallMaxRequests = 1,
                    guestStaffCallWindowSeconds = 60,
                )

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 11)
            seedTableToken(jdbcUrl, tableId, "staff-rate-limit-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val token = issueToken(config)
            val request =
                StaffCallRequest(
                    tableToken = "staff-rate-limit-token",
                    tableSessionId = tableSessionId,
                    reason = "BILL",
                    comment = null,
                )

            val firstResponse =
                client.post("/api/guest/staff-call") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(StaffCallRequest.serializer(), request))
                }
            val secondResponse =
                client.post("/api/guest/staff-call") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(StaffCallRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.OK, firstResponse.status)
            assertEquals(HttpStatusCode.TooManyRequests, secondResponse.status)
            assertApiErrorEnvelope(secondResponse, ApiErrorCodes.RATE_LIMITED)
        }

    @Test
    fun `unknown token returns not found`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-staff-unknown")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            seedTable(jdbcUrl, venueId, 5)
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val token = issueToken(config)
            val request =
                StaffCallRequest(
                    tableToken = "missing-token",
                    tableSessionId = 1,
                    reason = "BILL",
                    comment = null,
                )
            val response =
                client.post("/api/guest/staff-call") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(StaffCallRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
        }

    @Test
    fun `suspended venue rejects staff call`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-staff-suspended")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.SUSPENDED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 1)
            seedTableToken(jdbcUrl, tableId, "suspended-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val token = issueToken(config)
            val request =
                StaffCallRequest(
                    tableToken = "suspended-token",
                    tableSessionId = tableSessionId,
                    reason = "BILL",
                    comment = null,
                )
            val response =
                client.post("/api/guest/staff-call") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(StaffCallRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
        }

    @Test
    fun `blocked subscription rejects staff call`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-staff-blocked")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 3)
            seedTableToken(jdbcUrl, tableId, "blocked-token")
            seedSubscription(jdbcUrl, venueId, "SUSPENDED_BY_PLATFORM")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val token = issueToken(config)
            val request =
                StaffCallRequest(
                    tableToken = "blocked-token",
                    tableSessionId = tableSessionId,
                    reason = "BILL",
                    comment = null,
                )
            val response =
                client.post("/api/guest/staff-call") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(StaffCallRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.Locked, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.SUBSCRIPTION_BLOCKED)
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(
        jdbcUrl: String,
        guestStaffCallMaxRequests: Int? = null,
        guestStaffCallWindowSeconds: Long? = null,
    ): MapApplicationConfig {
        val entries =
            mutableListOf(
                "app.env" to appEnv,
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to jdbcUrl,
                "db.user" to "sa",
                "db.password" to "",
            )
        if (guestStaffCallMaxRequests != null) {
            entries.add("guest.rateLimit.staffCall.maxRequests" to guestStaffCallMaxRequests.toString())
        }
        if (guestStaffCallWindowSeconds != null) {
            entries.add("guest.rateLimit.staffCall.windowSeconds" to guestStaffCallWindowSeconds.toString())
        }
        return MapApplicationConfig(*entries.toTypedArray())
    }

    private fun issueToken(config: MapApplicationConfig): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(TELEGRAM_USER_ID).token
    }

    private fun seedVenue(
        jdbcUrl: String,
        status: String,
    ): Long {
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
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert venue")
    }

    private fun seedTable(
        jdbcUrl: String,
        venueId: Long,
        tableNumber: Int,
    ): Long {
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
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
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

    private fun seedTableSession(
        jdbcUrl: String,
        venueId: Long,
        tableId: Long,
        expiresAt: Instant = Instant.now().plus(1, ChronoUnit.HOURS),
    ): Long {
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
                statement.setTimestamp(5, Timestamp.from(expiresAt))
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert table session")
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
                statement.setString(2, status.uppercase())
                statement.setTimestamp(3, Timestamp.from(trialEnd))
                statement.setTimestamp(4, Timestamp.from(now))
                statement.setTimestamp(5, Timestamp.from(now))
                statement.executeUpdate()
            }
        }
    }

    private fun fetchStaffCall(
        jdbcUrl: String,
        staffCallId: Long,
    ): StaffCallRecord? {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT reason, comment, created_by_user_id
                FROM staff_calls
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, staffCallId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return StaffCallRecord(
                            reason = rs.getString("reason"),
                            comment = rs.getString("comment"),
                            createdByUserId = rs.getLong("created_by_user_id").let { if (rs.wasNull()) null else it },
                        )
                    }
                }
            }
        }
        return null
    }

    private data class StaffCallRecord(
        val reason: String,
        val comment: String?,
        val createdByUserId: Long?,
    )

    private companion object {
        const val TELEGRAM_USER_ID: Long = 456L
    }
}
