package com.hookah.platform.backend.platform

import com.hookah.platform.backend.api.ApiErrorCodes
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.DriverManager
import java.sql.Statement
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformVenueRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `non owner cannot create or change status`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-venues-rbac")
            val ownerId = 1001L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            val token = issueToken(config, userId = 2002L)

            val createResponse =
                client.post("/api/platform/venues") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"Forbidden Venue"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, createResponse.status)
            assertApiErrorEnvelope(createResponse, ApiErrorCodes.FORBIDDEN)

            val statusResponse =
                client.post("/api/platform/venues/$venueId/status") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"action":"publish"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, statusResponse.status)
            assertApiErrorEnvelope(statusResponse, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `publish requires owner membership`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-venues-owner-check")
            val ownerId = 3003L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val token = issueToken(config, userId = ownerId)
            val createResponse =
                client.post("/api/platform/venues") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"Draft Venue"}""")
                }

            assertEquals(HttpStatusCode.OK, createResponse.status)
            val created = json.decodeFromString(PlatformVenueResponse.serializer(), createResponse.bodyAsText())

            val publishResponse =
                client.post("/api/platform/venues/${created.venue.id}/status") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"action":"publish"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, publishResponse.status)
            assertApiErrorEnvelope(publishResponse, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `assign owner is idempotent and unlocks publish`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-venues-assign-owner")
            val ownerId = 7007L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            seedUser(jdbcUrl, ownerId)
            seedUser(jdbcUrl, 8111L)
            val token = issueToken(config, userId = ownerId)

            val assignResponse =
                client.post("/api/platform/venues/$venueId/owners") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformOwnerAssignRequest.serializer(),
                            PlatformOwnerAssignRequest(userId = 8111L),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, assignResponse.status)
            val assignPayload =
                json.decodeFromString(
                    PlatformOwnerAssignResponse.serializer(),
                    assignResponse.bodyAsText(),
                )
            assertEquals(true, assignPayload.ok)
            assertEquals(false, assignPayload.alreadyMember)

            val repeatResponse =
                client.post("/api/platform/venues/$venueId/owners") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformOwnerAssignRequest.serializer(),
                            PlatformOwnerAssignRequest(userId = 8111L),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, repeatResponse.status)
            val repeatPayload =
                json.decodeFromString(
                    PlatformOwnerAssignResponse.serializer(),
                    repeatResponse.bodyAsText(),
                )
            assertEquals(true, repeatPayload.alreadyMember)

            val publishResponse =
                client.post("/api/platform/venues/$venueId/status") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"action":"publish"}""")
                }

            assertEquals(HttpStatusCode.OK, publishResponse.status)
        }

    @Test
    fun `owner invite creates invite and accept makes owner`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-owner-invite")
            val ownerId = 9009L
            val inviteeId = 9010L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            seedUser(jdbcUrl, ownerId)
            seedUser(jdbcUrl, inviteeId)
            val token = issueToken(config, userId = ownerId)

            val inviteResponse =
                client.post("/api/platform/venues/$venueId/owner-invite") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformOwnerInviteRequest.serializer(),
                            PlatformOwnerInviteRequest(ttlSeconds = 120),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, inviteResponse.status)
            val invitePayload =
                json.decodeFromString(
                    PlatformOwnerInviteResponse.serializer(),
                    inviteResponse.bodyAsText(),
                )
            assertTrue(invitePayload.code.isNotBlank())
            assertTrue(Instant.parse(invitePayload.expiresAt).isAfter(Instant.now()))

            val inviteeToken = issueToken(config, userId = inviteeId)
            val acceptResponse =
                client.post("/api/venue/staff/invites/accept") {
                    headers { append(HttpHeaders.Authorization, "Bearer $inviteeToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffInviteAcceptRequest.serializer(),
                            StaffInviteAcceptRequest(inviteCode = invitePayload.code),
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
            assertEquals("OWNER", acceptPayload.member.role)
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(
        jdbcUrl: String,
        ownerId: Long,
    ): MapApplicationConfig {
        return MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
            "platform.ownerUserId" to ownerId.toString(),
            "venue.staffInviteSecretPepper" to "invite-pepper",
        )
    }

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long,
    ): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(userId).token
    }

    private fun seedVenue(jdbcUrl: String): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venues (name, city, address, status)
                VALUES ('Seed', 'City', 'Address', ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, VenueStatus.DRAFT.dbValue)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) return rs.getLong(1)
                }
            }
        }
        error("Failed to insert venue")
    }

    private fun seedUser(
        jdbcUrl: String,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO users (telegram_user_id, username, first_name, last_name, created_at, updated_at)
                VALUES (?, ?, 'Test', 'User', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, "user$userId")
                statement.executeUpdate()
            }
        }
    }

    @Serializable
    private data class PlatformVenueResponse(
        val venue: PlatformVenueDetailDto,
    )

    @Serializable
    private data class PlatformOwnerAssignRequest(
        val userId: Long,
        val role: String? = null,
    )

    @Serializable
    private data class PlatformOwnerAssignResponse(
        val ok: Boolean,
        val alreadyMember: Boolean,
        val role: String,
    )

    @Serializable
    private data class PlatformOwnerInviteRequest(
        val ttlSeconds: Long? = null,
    )

    @Serializable
    private data class PlatformOwnerInviteResponse(
        val code: String,
        val expiresAt: String,
        val instructions: String,
        val deepLink: String?,
    )

    @Serializable
    private data class StaffInviteAcceptRequest(
        val inviteCode: String,
    )

    @Serializable
    private data class StaffInviteAcceptResponse(
        val venueId: Long,
        val member: VenueStaffMemberDto,
        val alreadyMember: Boolean,
    )

    @Serializable
    private data class VenueStaffMemberDto(
        val userId: Long,
        val role: String,
        val createdAt: String,
        val invitedByUserId: Long? = null,
    )
}
