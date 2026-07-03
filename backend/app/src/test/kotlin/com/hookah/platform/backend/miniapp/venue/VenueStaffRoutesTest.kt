package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VenueStaffRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `owner and manager can list staff members`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-list-allowed")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 1101L
            val managerId = 1102L
            val staffId = 1103L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, managerId, "MANAGER", venueId)
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            val ownerToken = issueToken(config, ownerId)
            val managerToken = issueToken(config, managerId)

            val ownerResponse =
                client.get("/api/venue/$venueId/staff") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            val managerResponse =
                client.get("/api/venue/$venueId/staff") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }

            assertEquals(HttpStatusCode.OK, ownerResponse.status)
            assertEquals(HttpStatusCode.OK, managerResponse.status)
            val ownerPayload = json.decodeFromString(StaffListResponse.serializer(), ownerResponse.bodyAsText())
            val managerPayload = json.decodeFromString(StaffListResponse.serializer(), managerResponse.bodyAsText())
            assertEquals(setOf(ownerId, managerId, staffId), ownerPayload.members.map { it.userId }.toSet())
            assertEquals(setOf(ownerId, managerId, staffId), managerPayload.members.map { it.userId }.toSet())
        }

    @Test
    fun `staff cannot list staff members directly`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-list-denied")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 1201L
            val staffId = 1202L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            val staffToken = issueToken(config, staffId)

            val response =
                client.get("/api/venue/$venueId/staff") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `staff cannot create invite update roles or remove members`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-management-denied")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 1301L
            val staffId = 1302L
            val targetStaffId = 1303L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            seedVenueMembership(jdbcUrl, targetStaffId, "STAFF", venueId)
            val staffToken = issueToken(config, staffId)

            val inviteResponse =
                client.post("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(StaffInviteRequest.serializer(), StaffInviteRequest(role = "STAFF")))
                }
            val updateResponse =
                client.patch("/api/venue/$venueId/staff/$targetStaffId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffUpdateRoleRequest.serializer(),
                            StaffUpdateRoleRequest(role = "MANAGER"),
                        ),
                    )
                }
            val removeResponse =
                client.delete("/api/venue/$venueId/staff/$targetStaffId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }

            assertEquals(HttpStatusCode.Forbidden, inviteResponse.status)
            assertApiErrorEnvelope(inviteResponse, ApiErrorCodes.FORBIDDEN)
            assertEquals(HttpStatusCode.Forbidden, updateResponse.status)
            assertApiErrorEnvelope(updateResponse, ApiErrorCodes.FORBIDDEN)
            assertEquals(HttpStatusCode.Forbidden, removeResponse.status)
            assertApiErrorEnvelope(removeResponse, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `owner can create invite and accept it`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-invite")
            val config = buildConfig(jdbcUrl, botUsername = "HookahInviteBot")

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 1001L
            val inviteeId = 2002L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedUser(jdbcUrl, inviteeId)
            val ownerToken = issueToken(config, ownerId)

            val inviteResponse =
                client.post("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(StaffInviteRequest.serializer(), StaffInviteRequest(role = "STAFF")))
                }

            assertEquals(HttpStatusCode.OK, inviteResponse.status)
            val invitePayload = json.decodeFromString(StaffInviteResponse.serializer(), inviteResponse.bodyAsText())
            assertTrue(invitePayload.inviteCode.isNotBlank())
            val startPayload = "staff_invite_${invitePayload.inviteCode}"
            assertEquals("STAFF", invitePayload.role)
            assertEquals("Venue", invitePayload.venueName)
            assertEquals(startPayload, invitePayload.startPayload)
            assertEquals("https://t.me/HookahInviteBot?start=$startPayload", invitePayload.deepLink)
            val fallbackCommand = "/start $startPayload"
            assertEquals(fallbackCommand, invitePayload.fallbackCommand)
            assertEquals(invitePayload.deepLink, invitePayload.copyText)
            assertTrue(invitePayload.instructions.contains(invitePayload.deepLink!!))
            assertTrue(invitePayload.instructions.contains(fallbackCommand))

            val inviteeToken = issueToken(config, inviteeId)
            val acceptResponse =
                client.post("/api/venue/staff/invites/accept") {
                    headers { append(HttpHeaders.Authorization, "Bearer $inviteeToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffInviteAcceptRequest.serializer(),
                            StaffInviteAcceptRequest(inviteCode = invitePayload.inviteCode),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, acceptResponse.status)
            val acceptPayload =
                json.decodeFromString(
                    StaffInviteAcceptResponse.serializer(),
                    acceptResponse.bodyAsText(),
                )
            assertEquals(venueId, acceptPayload.venueId)
            assertEquals(inviteeId, acceptPayload.member.userId)
            assertEquals("STAFF", acceptPayload.member.role)
        }

    @Test
    fun `used staff invite is rejected on repeat accept`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-invite-used")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 2101L
            val firstInviteeId = 2102L
            val secondInviteeId = 2103L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedUser(jdbcUrl, firstInviteeId)
            seedUser(jdbcUrl, secondInviteeId)
            val ownerToken = issueToken(config, ownerId)

            val inviteResponse =
                client.post("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(StaffInviteRequest.serializer(), StaffInviteRequest(role = "STAFF")))
                }
            assertEquals(HttpStatusCode.OK, inviteResponse.status)
            val invitePayload = json.decodeFromString(StaffInviteResponse.serializer(), inviteResponse.bodyAsText())

            val firstAcceptResponse =
                client.post("/api/venue/staff/invites/accept") {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, firstInviteeId)}") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffInviteAcceptRequest.serializer(),
                            StaffInviteAcceptRequest(inviteCode = invitePayload.inviteCode),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, firstAcceptResponse.status)

            val repeatAcceptResponse =
                client.post("/api/venue/staff/invites/accept") {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, secondInviteeId)}") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffInviteAcceptRequest.serializer(),
                            StaffInviteAcceptRequest(inviteCode = invitePayload.inviteCode),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, repeatAcceptResponse.status)
            assertApiErrorEnvelope(repeatAcceptResponse, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `expired staff invite is rejected on accept`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-invite-expired")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 2201L
            val inviteeId = 2202L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedUser(jdbcUrl, inviteeId)
            val ownerToken = issueToken(config, ownerId)

            val inviteResponse =
                client.post("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(StaffInviteRequest.serializer(), StaffInviteRequest(role = "STAFF")))
                }
            assertEquals(HttpStatusCode.OK, inviteResponse.status)
            val invitePayload = json.decodeFromString(StaffInviteResponse.serializer(), inviteResponse.bodyAsText())
            expireStaffInvite(jdbcUrl, venueId, invitePayload.inviteCode)

            val acceptResponse =
                client.post("/api/venue/staff/invites/accept") {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, inviteeId)}") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffInviteAcceptRequest.serializer(),
                            StaffInviteAcceptRequest(inviteCode = invitePayload.inviteCode),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, acceptResponse.status)
            assertApiErrorEnvelope(acceptResponse, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `manager can invite staff only`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-invite-manager")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val managerId = 3003L
            val venueId = seedVenueMembership(jdbcUrl, managerId, "MANAGER")
            val token = issueToken(config, managerId)

            val forbiddenResponse =
                client.post("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(StaffInviteRequest.serializer(), StaffInviteRequest(role = "MANAGER")))
                }

            assertEquals(HttpStatusCode.Forbidden, forbiddenResponse.status)
            assertApiErrorEnvelope(forbiddenResponse, ApiErrorCodes.FORBIDDEN)

            val allowedResponse =
                client.post("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(StaffInviteRequest.serializer(), StaffInviteRequest(role = "STAFF")))
                }

            assertEquals(HttpStatusCode.OK, allowedResponse.status)
            val payload = json.decodeFromString(StaffInviteResponse.serializer(), allowedResponse.bodyAsText())
            assertTrue(payload.inviteCode.isNotBlank())
        }

    @Test
    fun `owner cannot create owner invite from venue staff flow`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-owner-invite-blocked")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 3201L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            val token = issueToken(config, ownerId)

            val response =
                client.post("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(StaffInviteRequest.serializer(), StaffInviteRequest(role = "OWNER")))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `staff accepting manager invite is upgraded`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-upgrade-manager")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 3301L
            val staffId = 3302L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            val ownerToken = issueToken(config, ownerId)

            val inviteResponse =
                client.post("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(StaffInviteRequest.serializer(), StaffInviteRequest(role = "MANAGER")))
                }
            assertEquals(HttpStatusCode.OK, inviteResponse.status)
            val invitePayload = json.decodeFromString(StaffInviteResponse.serializer(), inviteResponse.bodyAsText())

            val staffToken = issueToken(config, staffId)
            val acceptResponse =
                client.post("/api/venue/staff/invites/accept") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffInviteAcceptRequest.serializer(),
                            StaffInviteAcceptRequest(inviteCode = invitePayload.inviteCode),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, acceptResponse.status)
            val acceptPayload =
                json.decodeFromString(StaffInviteAcceptResponse.serializer(), acceptResponse.bodyAsText())
            assertEquals(true, acceptPayload.alreadyMember)
            assertEquals("MANAGER", acceptPayload.member.role)
        }

    @Test
    fun `manager accepting staff invite is not downgraded`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("manager-no-downgrade-staff")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 3401L
            val managerId = 3402L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, managerId, "MANAGER", venueId)
            val ownerToken = issueToken(config, ownerId)

            val inviteResponse =
                client.post("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(StaffInviteRequest.serializer(), StaffInviteRequest(role = "STAFF")))
                }
            assertEquals(HttpStatusCode.OK, inviteResponse.status)
            val invitePayload = json.decodeFromString(StaffInviteResponse.serializer(), inviteResponse.bodyAsText())

            val managerToken = issueToken(config, managerId)
            val acceptResponse =
                client.post("/api/venue/staff/invites/accept") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffInviteAcceptRequest.serializer(),
                            StaffInviteAcceptRequest(inviteCode = invitePayload.inviteCode),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, acceptResponse.status)
            val acceptPayload =
                json.decodeFromString(StaffInviteAcceptResponse.serializer(), acceptResponse.bodyAsText())
            assertEquals(true, acceptPayload.alreadyMember)
            assertEquals("MANAGER", acceptPayload.member.role)
        }

    @Test
    fun `admin role input is rejected for new staff assignments`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-admin-input")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 3101L
            val staffId = 3102L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            val token = issueToken(config, ownerId)

            val inviteResponse =
                client.post("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(StaffInviteRequest.serializer(), StaffInviteRequest(role = "ADMIN")))
                }

            assertEquals(HttpStatusCode.BadRequest, inviteResponse.status)
            assertApiErrorEnvelope(inviteResponse, ApiErrorCodes.INVALID_INPUT)

            val updateResponse =
                client.patch("/api/venue/$venueId/staff/$staffId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffUpdateRoleRequest.serializer(),
                            StaffUpdateRoleRequest(role = "ADMIN"),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, updateResponse.status)
            assertApiErrorEnvelope(updateResponse, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `cannot demote last owner`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-owner-demote")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 4004L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            val token = issueToken(config, ownerId)

            val response =
                client.patch("/api/venue/$venueId/staff/$ownerId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffUpdateRoleRequest.serializer(),
                            StaffUpdateRoleRequest(role = "STAFF"),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `owner can demote one of two owners but not the last`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-owner-demote-two")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 4101L
            val otherOwnerId = 4102L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, otherOwnerId, "OWNER", venueId)
            val token = issueToken(config, ownerId)

            val demoteOtherResponse =
                client.patch("/api/venue/$venueId/staff/$otherOwnerId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffUpdateRoleRequest.serializer(),
                            StaffUpdateRoleRequest(role = "STAFF"),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, demoteOtherResponse.status)

            val demoteLastResponse =
                client.patch("/api/venue/$venueId/staff/$ownerId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffUpdateRoleRequest.serializer(),
                            StaffUpdateRoleRequest(role = "STAFF"),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, demoteLastResponse.status)
            assertApiErrorEnvelope(demoteLastResponse, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `owner can remove staff`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-remove")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 5005L
            val staffId = 5006L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            val token = issueToken(config, ownerId)

            val response =
                client.delete("/api/venue/$venueId/staff/$staffId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(
        jdbcUrl: String,
        botUsername: String? = null,
    ): MapApplicationConfig {
        val entries =
            mutableMapOf(
                "app.env" to appEnv,
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to jdbcUrl,
                "db.user" to "sa",
                "db.password" to "",
                "venue.staffInviteSecretPepper" to "invite-pepper",
            )
        if (botUsername != null) {
            entries["telegram.botUsername"] = botUsername
        }
        return MapApplicationConfig(*entries.toList().toTypedArray())
    }

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long,
    ): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(userId).token
    }

    private fun seedVenueMembership(
        jdbcUrl: String,
        userId: Long,
        role: String,
        venueId: Long? = null,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            seedUser(connection, userId)
            val resolvedVenueId =
                venueId ?: connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setString(1, VenueStatus.PUBLISHED.dbValue)
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
                statement.setLong(1, resolvedVenueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
            return resolvedVenueId
        }
    }

    private fun seedUser(
        jdbcUrl: String,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            seedUser(connection, userId)
        }
    }

    private fun seedUser(
        connection: java.sql.Connection,
        userId: Long,
    ) {
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
    }

    private fun expireStaffInvite(
        jdbcUrl: String,
        venueId: Long,
        code: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                UPDATE venue_staff_invites
                SET expires_at = ?
                WHERE venue_id = ? AND code_hint = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setTimestamp(1, java.sql.Timestamp.from(java.time.Instant.parse("2000-01-01T00:00:00Z")))
                statement.setLong(2, venueId)
                statement.setString(3, code.take(3))
                statement.executeUpdate()
            }
        }
    }

    @Serializable
    private data class StaffListResponse(
        val members: List<StaffMemberDto>,
    )

    @Serializable
    private data class StaffInviteRequest(
        val role: String,
        val expiresIn: Long? = null,
    )

    @Serializable
    private data class StaffInviteResponse(
        val inviteCode: String,
        val expiresAt: String,
        val ttlSeconds: Long,
        val instructions: String,
        val role: String? = null,
        val venueName: String? = null,
        val startPayload: String? = null,
        val deepLink: String? = null,
        val fallbackCommand: String? = null,
        val copyText: String? = null,
    )

    @Serializable
    private data class StaffInviteAcceptRequest(
        val inviteCode: String,
    )

    @Serializable
    private data class StaffInviteAcceptResponse(
        val venueId: Long,
        val member: StaffMemberDto,
        val alreadyMember: Boolean,
    )

    @Serializable
    private data class StaffMemberDto(
        val userId: Long,
        val role: String,
    )

    @Serializable
    private data class StaffUpdateRoleRequest(
        val role: String,
    )
}
