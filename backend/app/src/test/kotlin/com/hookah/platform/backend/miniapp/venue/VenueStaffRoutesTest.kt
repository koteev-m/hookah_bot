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
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class VenueStaffRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `owner can create invite and accept it`() = testApplication {
        val jdbcUrl = buildJdbcUrl("staff-invite")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val ownerId = 1001L
        val inviteeId = 2002L
        val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
        seedUser(jdbcUrl, inviteeId)
        val ownerToken = issueToken(config, ownerId)

        val inviteResponse = client.post("/api/venue/$venueId/staff/invites") {
            headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(StaffInviteRequest.serializer(), StaffInviteRequest(role = "STAFF")))
        }

        assertEquals(HttpStatusCode.OK, inviteResponse.status)
        val invitePayload = json.decodeFromString(StaffInviteResponse.serializer(), inviteResponse.bodyAsText())
        assertTrue(invitePayload.inviteCode.isNotBlank())

        val inviteeToken = issueToken(config, inviteeId)
        val acceptResponse = client.post("/api/venue/staff/invites/accept") {
            headers { append(HttpHeaders.Authorization, "Bearer $inviteeToken") }
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    StaffInviteAcceptRequest.serializer(),
                    StaffInviteAcceptRequest(inviteCode = invitePayload.inviteCode)
                )
            )
        }

        assertEquals(HttpStatusCode.OK, acceptResponse.status)
        val acceptPayload = json.decodeFromString(StaffInviteAcceptResponse.serializer(), acceptResponse.bodyAsText())
        assertEquals(venueId, acceptPayload.venueId)
        assertEquals(inviteeId, acceptPayload.member.userId)
        assertEquals("STAFF", acceptPayload.member.role)
    }

    @Test
    fun `manager can invite staff only`() = testApplication {
        val jdbcUrl = buildJdbcUrl("staff-invite-manager")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val managerId = 3003L
        val venueId = seedVenueMembership(jdbcUrl, managerId, "MANAGER")
        val token = issueToken(config, managerId)

        val forbiddenResponse = client.post("/api/venue/$venueId/staff/invites") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(StaffInviteRequest.serializer(), StaffInviteRequest(role = "MANAGER")))
        }

        assertEquals(HttpStatusCode.Forbidden, forbiddenResponse.status)
        assertApiErrorEnvelope(forbiddenResponse, ApiErrorCodes.FORBIDDEN)
    }

    @Test
    fun `cannot demote last owner`() = testApplication {
        val jdbcUrl = buildJdbcUrl("staff-owner-demote")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val ownerId = 4004L
        val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
        val token = issueToken(config, ownerId)

        val response = client.patch("/api/venue/$venueId/staff/$ownerId") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(StaffUpdateRoleRequest.serializer(), StaffUpdateRoleRequest(role = "STAFF")))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
    }

    @Test
    fun `owner can demote one of two owners but not the last`() = testApplication {
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

        val demoteOtherResponse = client.patch("/api/venue/$venueId/staff/$otherOwnerId") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(StaffUpdateRoleRequest.serializer(), StaffUpdateRoleRequest(role = "STAFF")))
        }

        assertEquals(HttpStatusCode.OK, demoteOtherResponse.status)

        val demoteLastResponse = client.patch("/api/venue/$venueId/staff/$ownerId") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(StaffUpdateRoleRequest.serializer(), StaffUpdateRoleRequest(role = "STAFF")))
        }

        assertEquals(HttpStatusCode.BadRequest, demoteLastResponse.status)
        assertApiErrorEnvelope(demoteLastResponse, ApiErrorCodes.INVALID_INPUT)
    }

    @Test
    fun `owner can remove staff`() = testApplication {
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

        val response = client.delete("/api/venue/$venueId/staff/$staffId") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, response.status)
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
            "venue.staffInviteSecretPepper" to "invite-pepper"
        )
    }

    private fun issueToken(config: MapApplicationConfig, userId: Long): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(userId).token
    }

    private fun seedVenueMembership(jdbcUrl: String, userId: Long, role: String, venueId: Long? = null): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            seedUser(connection, userId)
            val resolvedVenueId = venueId ?: connection.prepareStatement(
                """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
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
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, resolvedVenueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
            return resolvedVenueId
        }
    }

    private fun seedUser(jdbcUrl: String, userId: Long) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            seedUser(connection, userId)
        }
    }

    private fun seedUser(connection: java.sql.Connection, userId: Long) {
        connection.prepareStatement(
            """
                MERGE INTO users (telegram_user_id, username, first_name, last_name)
                KEY (telegram_user_id)
                VALUES (?, 'user', 'Test', 'User')
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeUpdate()
        }
    }

    @Serializable
    private data class StaffInviteRequest(
        val role: String,
        val expiresIn: Long? = null
    )

    @Serializable
    private data class StaffInviteResponse(
        val inviteCode: String,
        val expiresAt: String,
        val ttlSeconds: Long,
        val instructions: String
    )

    @Serializable
    private data class StaffInviteAcceptRequest(
        val inviteCode: String
    )

    @Serializable
    private data class StaffInviteAcceptResponse(
        val venueId: Long,
        val member: StaffMemberDto,
        val alreadyMember: Boolean
    )

    @Serializable
    private data class StaffMemberDto(
        val userId: Long,
        val role: String
    )

    @Serializable
    private data class StaffUpdateRoleRequest(
        val role: String
    )
}
