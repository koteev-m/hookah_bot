package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VenueRbacRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `venue me without auth returns unauthorized`() =
        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.env" to appEnv,
                        "api.session.jwtSecret" to "test-secret",
                        "db.jdbcUrl" to "",
                    )
            }

            application { module() }

            val response = client.get("/api/venue/me")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.UNAUTHORIZED)
        }

    @Test
    fun `owner can generate staff chat link code`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-owner")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val token = issueToken(config)

            val response =
                client.post("/api/venue/$venueId/staff-chat/link-code") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(StaffChatLinkCodeResponse.serializer(), response.bodyAsText())
            assertTrue(payload.code.isNotBlank())
            assertTrue(payload.ttlSeconds > 0)
        }

    @Test
    fun `manager can generate staff chat link code`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-manager")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val token = issueToken(config)

            val response =
                client.post("/api/venue/$venueId/staff-chat/link-code") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(StaffChatLinkCodeResponse.serializer(), response.bodyAsText())
            assertTrue(payload.code.isNotBlank())
            assertTrue(payload.ttlSeconds > 0)
        }

    @Test
    fun `staff cannot generate staff chat link code`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-staff")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val token = issueToken(config)

            val response =
                client.post("/api/venue/$venueId/staff-chat/link-code") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `legacy admin can generate staff chat link code as manager alias`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-admin-link-code")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "ADMIN")
            val token = issueToken(config)

            val response =
                client.post("/api/venue/$venueId/staff-chat/link-code") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(StaffChatLinkCodeResponse.serializer(), response.bodyAsText())
            assertTrue(payload.code.isNotBlank())
            assertTrue(payload.ttlSeconds > 0)
        }

    @Test
    fun `manager cannot unlink staff chat`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-manager-unlink")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val token = issueToken(config)

            val response =
                client.post("/api/venue/$venueId/staff-chat/unlink") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `venue me returns permissions for each role`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-me")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerVenueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val managerVenueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val staffVenueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val token = issueToken(config)

            val response =
                client.get("/api/venue/me") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(VenueMeResponse.serializer(), response.bodyAsText())
            assertEquals(TELEGRAM_USER_ID, payload.userId)

            val venuesById = payload.venues.associateBy { it.venueId }
            assertEquals(setOf(ownerVenueId, managerVenueId, staffVenueId), venuesById.keys)

            assertEquals("OWNER", venuesById.getValue(ownerVenueId).role)
            assertEquals("Venue", venuesById.getValue(ownerVenueId).venueName)
            assertEquals("City", venuesById.getValue(ownerVenueId).venueCity)
            assertEquals("PUBLISHED", venuesById.getValue(ownerVenueId).venueStatus)
            assertEquals(
                setOf(
                    "STAFF_CHAT_LINK",
                    "VENUE_SETTINGS",
                    "ORDER_STATUS_UPDATE",
                    "ORDER_QUEUE_VIEW",
                    "BOOKING_VIEW",
                    "BOOKING_ARRIVAL_UPDATE",
                    "BOOKING_MANAGE",
                    "SHIFT_EXTENSION_VIEW",
                    "SHIFT_EXTENSION_CONFIRM",
                    "SHIFT_EXTENSION_SETTINGS",
                    "MENU_VIEW",
                    "MENU_MANAGE",
                    "MENU_AVAILABILITY_MANAGE",
                    "TABLE_VIEW",
                    "TABLE_MANAGE",
                    "TABLE_TOKEN_ROTATE",
                    "TABLE_TOKEN_ROTATE_ALL",
                    "TABLE_QR_EXPORT",
                ),
                venuesById.getValue(ownerVenueId).permissions.toSet(),
            )

            assertEquals("MANAGER", venuesById.getValue(managerVenueId).role)
            assertEquals(
                setOf(
                    "STAFF_CHAT_LINK",
                    "ORDER_STATUS_UPDATE",
                    "ORDER_QUEUE_VIEW",
                    "BOOKING_VIEW",
                    "BOOKING_ARRIVAL_UPDATE",
                    "BOOKING_MANAGE",
                    "SHIFT_EXTENSION_VIEW",
                    "SHIFT_EXTENSION_CONFIRM",
                    "SHIFT_EXTENSION_SETTINGS",
                    "MENU_VIEW",
                    "MENU_MANAGE",
                    "MENU_AVAILABILITY_MANAGE",
                    "TABLE_VIEW",
                    "TABLE_MANAGE",
                    "TABLE_QR_EXPORT",
                ),
                venuesById.getValue(managerVenueId).permissions.toSet(),
            )

            assertEquals("STAFF", venuesById.getValue(staffVenueId).role)
            assertEquals(
                setOf(
                    "ORDER_QUEUE_VIEW",
                    "ORDER_STATUS_UPDATE",
                    "BOOKING_VIEW",
                    "BOOKING_ARRIVAL_UPDATE",
                    "SHIFT_EXTENSION_VIEW",
                    "SHIFT_EXTENSION_CONFIRM",
                    "MENU_VIEW",
                    "MENU_AVAILABILITY_MANAGE",
                    "TABLE_VIEW",
                ),
                venuesById.getValue(staffVenueId).permissions.toSet(),
            )
        }

    @Test
    fun `venue me ignores deleted venue memberships`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-me-deleted")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER", VenueStatus.DELETED)
            val token = issueToken(config)

            val response =
                client.get("/api/venue/me") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `venue me maps legacy admin to manager permissions`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-admin-alias")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "ADMIN")
            val token = issueToken(config)

            val response =
                client.get("/api/venue/me") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(VenueMeResponse.serializer(), response.bodyAsText())
            val venue = payload.venues.single { it.venueId == venueId }
            assertEquals("MANAGER", venue.role)
            assertEquals(
                setOf(
                    "STAFF_CHAT_LINK",
                    "ORDER_STATUS_UPDATE",
                    "ORDER_QUEUE_VIEW",
                    "BOOKING_VIEW",
                    "BOOKING_ARRIVAL_UPDATE",
                    "BOOKING_MANAGE",
                    "SHIFT_EXTENSION_VIEW",
                    "SHIFT_EXTENSION_CONFIRM",
                    "SHIFT_EXTENSION_SETTINGS",
                    "MENU_VIEW",
                    "MENU_MANAGE",
                    "MENU_AVAILABILITY_MANAGE",
                    "TABLE_VIEW",
                    "TABLE_MANAGE",
                    "TABLE_QR_EXPORT",
                ),
                venue.permissions.toSet(),
            )
        }

    @Test
    fun `staff can list acknowledge and close staff calls`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-staff-calls")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val staffCallId = seedStaffCall(jdbcUrl, venueId, status = "NEW")
            val oldStaffCallId =
                seedStaffCall(
                    jdbcUrl,
                    venueId,
                    status = "NEW",
                    createdAt = Instant.now().minusSeconds(48 * 60 * 60),
                    tableNumber = 13,
                )
            val doneStaffCallId = seedStaffCall(jdbcUrl, venueId, status = "DONE", tableNumber = 14)
            val token = issueToken(config)

            val listResponse =
                client.get("/api/venue/$venueId/staff-calls") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, listResponse.status)
            val listPayload = json.decodeFromString(VenueStaffCallsResponse.serializer(), listResponse.bodyAsText())
            assertEquals(listOf(staffCallId), listPayload.items.map { it.id })
            assertTrue(oldStaffCallId !in listPayload.items.map { it.id })
            assertTrue(doneStaffCallId !in listPayload.items.map { it.id })
            assertEquals("NEW", listPayload.items.single().status)
            assertEquals("Консультация", listPayload.items.single().reasonLabel)

            val ackResponse =
                client.post("/api/venue/$venueId/staff-calls/$staffCallId/ack") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, ackResponse.status)
            val ackPayload = json.decodeFromString(VenueStaffCallActionResponse.serializer(), ackResponse.bodyAsText())
            assertTrue(ackPayload.applied)
            assertEquals("ACK", ackPayload.call.status)

            val doneResponse =
                client.post("/api/venue/$venueId/staff-calls/$staffCallId/done") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, doneResponse.status)
            val donePayload =
                json.decodeFromString(
                    VenueStaffCallActionResponse.serializer(),
                    doneResponse.bodyAsText(),
                )
            assertTrue(donePayload.applied)
            assertEquals("DONE", donePayload.call.status)

            val listAfterDoneResponse =
                client.get("/api/venue/$venueId/staff-calls") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, listAfterDoneResponse.status)
            val listAfterDonePayload =
                json.decodeFromString(VenueStaffCallsResponse.serializer(), listAfterDoneResponse.bodyAsText())
            assertEquals(emptyList(), listAfterDonePayload.items.map { it.id })
        }

    @Test
    fun `owner can acknowledge and close staff calls`() =
        assertVenueRoleCanAcknowledgeAndCloseStaffCall("OWNER", "venue-owner-staff-calls")

    @Test
    fun `manager can acknowledge and close staff calls`() =
        assertVenueRoleCanAcknowledgeAndCloseStaffCall("MANAGER", "venue-manager-staff-calls")

    @Test
    fun `done from new staff call is not applied`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-staff-call-invalid-transition")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val staffCallId = seedStaffCall(jdbcUrl, venueId, status = "NEW")
            val token = issueToken(config)

            val doneResponse =
                client.post("/api/venue/$venueId/staff-calls/$staffCallId/done") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, doneResponse.status)
            val payload =
                json.decodeFromString(VenueStaffCallActionResponse.serializer(), doneResponse.bodyAsText())
            assertFalse(payload.applied)
            assertEquals("NEW", payload.call.status)

            val listResponse =
                client.get("/api/venue/$venueId/staff-calls") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val listPayload = json.decodeFromString(VenueStaffCallsResponse.serializer(), listResponse.bodyAsText())
            assertEquals(listOf(staffCallId), listPayload.items.map { it.id })
        }

    @Test
    fun `staff cannot access staff calls from another venue`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-staff-calls-forbidden")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val foreignVenueId = seedVenueMembership(jdbcUrl, 777L, "STAFF")
            val token = issueToken(config)

            val response =
                client.get("/api/venue/$foreignVenueId/staff-calls") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `staff call reason labels preserve consultation coals and bill`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-staff-call-labels")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            seedStaffCall(jdbcUrl, venueId, status = "NEW", reason = "COME", tableNumber = 12)
            seedStaffCall(jdbcUrl, venueId, status = "NEW", reason = "COALS", tableNumber = 13)
            seedStaffCall(jdbcUrl, venueId, status = "NEW", reason = "BILL", tableNumber = 14)
            val token = issueToken(config)

            val response =
                client.get("/api/venue/$venueId/staff-calls") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(VenueStaffCallsResponse.serializer(), response.bodyAsText())
            assertEquals(
                setOf("Консультация", "Заменить угли", "Принести счёт"),
                payload.items.map { it.reasonLabel }.toSet(),
            )
        }

    private fun assertVenueRoleCanAcknowledgeAndCloseStaffCall(
        role: String,
        prefix: String,
    ) = testApplication {
        val jdbcUrl = buildJdbcUrl(prefix)
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, role)
        val staffCallId = seedStaffCall(jdbcUrl, venueId, status = "NEW")
        val token = issueToken(config)

        val ackResponse =
            client.post("/api/venue/$venueId/staff-calls/$staffCallId/ack") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
            }
        assertEquals(HttpStatusCode.OK, ackResponse.status)
        val ackPayload = json.decodeFromString(VenueStaffCallActionResponse.serializer(), ackResponse.bodyAsText())
        assertTrue(ackPayload.applied)
        assertEquals("ACK", ackPayload.call.status)

        val doneResponse =
            client.post("/api/venue/$venueId/staff-calls/$staffCallId/done") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
            }
        assertEquals(HttpStatusCode.OK, doneResponse.status)
        val donePayload =
            json.decodeFromString(VenueStaffCallActionResponse.serializer(), doneResponse.bodyAsText())
        assertTrue(donePayload.applied)
        assertEquals("DONE", donePayload.call.status)
    }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String): MapApplicationConfig {
        return MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
        )
    }

    private fun issueToken(config: MapApplicationConfig): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(TELEGRAM_USER_ID).token
    }

    private fun seedVenueMembership(
        jdbcUrl: String,
        userId: Long,
        role: String,
        status: VenueStatus = VenueStatus.PUBLISHED,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO users (telegram_user_id, username, first_name, last_name)
                KEY (telegram_user_id)
                VALUES (?, 'user', 'Test', 'User')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
            val venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setString(1, status.dbValue)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) {
                            rs.getLong(1)
                        } else {
                            error("Failed to insert venue")
                        }
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO venue_members (venue_id, user_id, role)
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
            return venueId
        }
    }

    private fun seedStaffCall(
        jdbcUrl: String,
        venueId: Long,
        status: String,
        createdAt: Instant = Instant.now(),
        tableNumber: Int = 12,
        reason: String = "COME",
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val tableId =
                connection.prepareStatement(
                    """
                    INSERT INTO venue_tables (venue_id, table_number, is_active)
                    VALUES (?, ?, true)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setInt(2, tableNumber)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert table")
                    }
                }
            return connection.prepareStatement(
                """
                INSERT INTO staff_calls (venue_id, table_id, reason, comment, status, created_at)
                VALUES (?, ?, ?, 'Нужна помощь', ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, tableId)
                statement.setString(3, reason)
                statement.setString(4, status)
                statement.setTimestamp(5, Timestamp.from(createdAt))
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert staff call")
                }
            }
        }
    }

    @Serializable
    private data class StaffChatLinkCodeResponse(
        val code: String,
        val expiresAt: String,
        val ttlSeconds: Long,
    )

    @Serializable
    private data class VenueMeResponse(
        val userId: Long,
        val venues: List<VenueAccessDto>,
    )

    @Serializable
    private data class VenueAccessDto(
        val venueId: Long,
        val venueName: String? = null,
        val venueCity: String? = null,
        val venueStatus: String? = null,
        val role: String,
        val permissions: List<String>,
    )

    @Serializable
    private data class VenueStaffCallsResponse(
        val items: List<VenueStaffCallDto>,
    )

    @Serializable
    private data class VenueStaffCallActionResponse(
        val call: VenueStaffCallDto,
        val applied: Boolean,
    )

    @Serializable
    private data class VenueStaffCallDto(
        val id: Long,
        val status: String,
        val reasonLabel: String,
    )

    private companion object {
        const val TELEGRAM_USER_ID: Long = 909L
    }
}
