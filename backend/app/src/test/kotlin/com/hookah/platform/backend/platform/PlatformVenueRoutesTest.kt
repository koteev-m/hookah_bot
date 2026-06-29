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
import io.ktor.client.request.put
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
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

            val inviteResponse =
                client.post("/api/platform/venues/$venueId/owner-invite") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"ttlSeconds":120}""")
                }

            assertEquals(HttpStatusCode.Forbidden, inviteResponse.status)
            assertApiErrorEnvelope(inviteResponse, ApiErrorCodes.FORBIDDEN)
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
    fun `platform owner can set venue owner quota`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-owner-quota-update")
            val ownerId = 7217L
            val targetOwnerId = 8333L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            seedUser(jdbcUrl, ownerId)
            seedUser(jdbcUrl, targetOwnerId)
            val token = issueToken(config, userId = ownerId)

            val response =
                client.put("/api/platform/venue-owner-accounts/$targetOwnerId/quota") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"allowedVenuesCount":2,"notes":"manual limit","commercialNote":"terms"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload =
                json.decodeFromString(
                    PlatformVenueOwnerAccountResponse.serializer(),
                    response.bodyAsText(),
                )
            assertEquals(targetOwnerId, payload.account.primaryOwnerUserId)
            assertEquals(2, payload.account.allowedVenuesCount)
            assertEquals(0, payload.quota.usedVenuesCount)
            assertEquals(2, payload.quota.availableVenuesCount)
        }

    @Test
    fun `owner assignment cannot bypass exhausted venue quota`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-owner-quota-blocks")
            val ownerId = 7317L
            val targetOwnerId = 8444L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            seedUser(jdbcUrl, ownerId)
            seedUser(jdbcUrl, targetOwnerId)
            val existingVenueId = seedVenue(jdbcUrl)
            val targetVenueId = seedVenue(jdbcUrl)
            val ownerAccountId = seedOwnerAccount(jdbcUrl, targetOwnerId, allowedVenuesCount = 1)
            linkVenueToOwnerAccount(jdbcUrl, existingVenueId, ownerAccountId)
            val token = issueToken(config, userId = ownerId)

            val response =
                client.post("/api/platform/venues/$targetVenueId/owners") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformOwnerAssignRequest.serializer(),
                            PlatformOwnerAssignRequest(userId = targetOwnerId),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `assign owner rejects admin role alias`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-venues-reject-admin-owner")
            val ownerId = 7117L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            seedUser(jdbcUrl, ownerId)
            seedUser(jdbcUrl, 8222L)
            val token = issueToken(config, userId = ownerId)

            val assignResponse =
                client.post("/api/platform/venues/$venueId/owners") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformOwnerAssignRequest.serializer(),
                            PlatformOwnerAssignRequest(userId = 8222L, role = "ADMIN"),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, assignResponse.status)
            assertApiErrorEnvelope(assignResponse, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `platform cockpit can list venue open detail and see subscription basics`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-cockpit-smoke")
            val ownerId = 8123L
            val venueOwnerId = 9123L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            seedUser(jdbcUrl, ownerId)
            seedUser(jdbcUrl, venueOwnerId)
            val venueId = seedVenue(jdbcUrl)
            seedOwnerMembership(jdbcUrl, venueId, venueOwnerId)
            seedSubscription(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                status = "ACTIVE",
                trialEnd = Instant.now().plus(7, ChronoUnit.DAYS),
                paidStart = Instant.now().minus(1, ChronoUnit.DAYS),
            )
            val token = issueToken(config, userId = ownerId)

            val listResponse =
                client.get("/api/platform/venues?limit=10") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val listPayload =
                json.decodeFromString(
                    PlatformVenueListResponse.serializer(),
                    listResponse.bodyAsText(),
                )
            val listedVenue = listPayload.venues.single { it.id == venueId }
            assertEquals("Seed", listedVenue.name)
            assertEquals("DRAFT", listedVenue.status)
            assertEquals(1, listedVenue.ownersCount)
            assertNotNull(listedVenue.subscriptionSummary)
            assertEquals(true, listedVenue.subscriptionSummary.isPaid)

            val detailResponse =
                client.get("/api/platform/venues/$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, detailResponse.status)
            val detailPayload =
                json.decodeFromString(
                    PlatformVenueDetailResponse.serializer(),
                    detailResponse.bodyAsText(),
                )
            assertEquals(venueId, detailPayload.venue.id)
            assertEquals("City", detailPayload.venue.city)
            assertEquals("Address", detailPayload.venue.address)
            assertEquals(listOf(venueOwnerId), detailPayload.owners.map { it.userId })
            assertNotNull(detailPayload.subscriptionSummary)
            assertEquals(true, detailPayload.subscriptionSummary.isPaid)
        }

    @Test
    fun `platform venue list excludes deleted venues but keeps archived filter`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-venues-deleted-filter")
            val ownerId = 8124L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val activeId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED)
            val archivedId = seedVenue(jdbcUrl, VenueStatus.ARCHIVED)
            val deletedId = seedVenue(jdbcUrl, VenueStatus.DELETED)
            val token = issueToken(config, userId = ownerId)

            val listResponse =
                client.get("/api/platform/venues?limit=10") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, listResponse.status)
            val listPayload =
                json.decodeFromString(
                    PlatformVenueListResponse.serializer(),
                    listResponse.bodyAsText(),
                )
            assertTrue(listPayload.venues.any { it.id == activeId })
            assertTrue(listPayload.venues.any { it.id == archivedId })
            assertTrue(listPayload.venues.none { it.id == deletedId })

            val archiveResponse =
                client.get("/api/platform/venues?status=ARCHIVED&limit=10") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, archiveResponse.status)
            val archivePayload =
                json.decodeFromString(
                    PlatformVenueListResponse.serializer(),
                    archiveResponse.bodyAsText(),
                )
            assertEquals(listOf(archivedId), archivePayload.venues.map { it.id })
        }

    @Test
    fun `owner invite returns fallback copy without bot username`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-owner-invite-fallback")
            val ownerId = 9011L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            seedUser(jdbcUrl, ownerId)
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
            val startPayload = "staff_invite_${invitePayload.code}"
            assertEquals(null, invitePayload.deepLink)
            assertEquals("/start $startPayload", invitePayload.copyText)
            assertTrue(invitePayload.instructions.contains(invitePayload.copyText))
        }

    @Test
    fun `owner invite creates invite and accept makes owner`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-owner-invite")
            val ownerId = 9009L
            val inviteeId = 9010L
            val config = buildConfig(jdbcUrl, ownerId, botUsername = "HookahInviteBot")

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
            val startPayload = "staff_invite_${invitePayload.code}"
            assertTrue(startPayload.matches(Regex("[A-Za-z0-9_-]+")))
            assertTrue(startPayload.length <= 64)
            assertEquals("https://t.me/HookahInviteBot?start=$startPayload", invitePayload.deepLink)
            assertEquals(invitePayload.deepLink, invitePayload.copyText)
            assertTrue(invitePayload.instructions.contains("/start $startPayload"))
            val createAuditPayloads = loadAuditPayloads(jdbcUrl, "VENUE_OWNER_INVITE_CREATE")
            assertEquals(1, createAuditPayloads.size)
            assertTrue(createAuditPayloads.single().contains("\"venueId\":$venueId"))
            assertTrue(createAuditPayloads.single().contains("\"role\":\"OWNER\""))
            assertFalse(createAuditPayloads.single().contains(invitePayload.code))

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
            assertEquals(inviteeId, loadPrimaryOwnerForVenueAccount(jdbcUrl, venueId))
            val acceptAuditPayloads = loadAuditPayloads(jdbcUrl, "VENUE_OWNER_INVITE_ACCEPT")
            assertEquals(1, acceptAuditPayloads.size)
            assertTrue(acceptAuditPayloads.single().contains("\"venueId\":$venueId"))
            assertTrue(acceptAuditPayloads.single().contains("\"acceptedUserId\":$inviteeId"))
            assertTrue(acceptAuditPayloads.single().contains("\"role\":\"OWNER\""))
            assertFalse(acceptAuditPayloads.single().contains(invitePayload.code))
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(
        jdbcUrl: String,
        ownerId: Long,
        botUsername: String? = null,
    ): MapApplicationConfig {
        val entries =
            mutableListOf(
                "app.env" to appEnv,
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to jdbcUrl,
                "db.user" to "sa",
                "db.password" to "",
                "platform.ownerUserId" to ownerId.toString(),
                "venue.staffInviteSecretPepper" to "invite-pepper",
            )
        if (botUsername != null) {
            entries.add("telegram.botUsername" to botUsername)
        }
        return MapApplicationConfig(*entries.toTypedArray())
    }

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long,
    ): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(userId).token
    }

    private fun seedVenue(
        jdbcUrl: String,
        status: VenueStatus = VenueStatus.DRAFT,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venues (name, city, address, status)
                VALUES ('Seed', 'City', 'Address', ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, status.dbValue)
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

    private fun seedOwnerMembership(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_members (venue_id, user_id, role)
                VALUES (?, ?, 'OWNER')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedSubscription(
        jdbcUrl: String,
        venueId: Long,
        status: String,
        trialEnd: Instant?,
        paidStart: Instant?,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, status)
                statement.setTimestamp(3, trialEnd?.let { Timestamp.from(it) })
                statement.setTimestamp(4, paidStart?.let { Timestamp.from(it) })
                statement.executeUpdate()
            }
        }
    }

    private fun seedOwnerAccount(
        jdbcUrl: String,
        ownerUserId: Long,
        allowedVenuesCount: Int,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_owner_accounts (primary_owner_user_id, allowed_venues_count)
                VALUES (?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, ownerUserId)
                statement.setInt(2, allowedVenuesCount)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) return rs.getLong(1)
                }
            }
        }
        error("Failed to insert owner account")
    }

    private fun linkVenueToOwnerAccount(
        jdbcUrl: String,
        venueId: Long,
        ownerAccountId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                UPDATE venues
                SET owner_account_id = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, ownerAccountId)
                statement.setLong(2, venueId)
                statement.executeUpdate()
            }
        }
    }

    private fun loadPrimaryOwnerForVenueAccount(
        jdbcUrl: String,
        venueId: Long,
    ): Long? {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT voa.primary_owner_user_id
                FROM venues v
                JOIN venue_owner_accounts voa ON voa.id = v.owner_account_id
                WHERE v.id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { rs ->
                    return if (rs.next()) rs.getLong("primary_owner_user_id") else null
                }
            }
        }
    }

    private fun loadAuditPayloads(
        jdbcUrl: String,
        action: String,
    ): List<String> {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM audit_log
                WHERE action = ?
                ORDER BY created_at
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, action)
                statement.executeQuery().use { rs ->
                    val result = mutableListOf<String>()
                    while (rs.next()) {
                        result.add(rs.getString("payload_json"))
                    }
                    return result
                }
            }
        }
    }

    @Serializable
    private data class PlatformVenueResponse(
        val venue: PlatformVenueDetailDto,
    )

    @Serializable
    private data class PlatformVenueListResponse(
        val venues: List<PlatformVenueSummaryDto>,
    )

    @Serializable
    private data class PlatformVenueSummaryDto(
        val id: Long,
        val name: String,
        val city: String? = null,
        val status: String,
        val createdAt: String,
        val ownersCount: Int,
        val subscriptionSummary: PlatformSubscriptionSummaryDto? = null,
    )

    @Serializable
    private data class PlatformVenueDetailResponse(
        val venue: PlatformVenueDetailDto,
        val owners: List<PlatformVenueOwnerDto>,
        val subscriptionSummary: PlatformSubscriptionSummaryDto? = null,
    )

    @Serializable
    private data class PlatformVenueOwnerDto(
        val userId: Long,
        val role: String,
        val username: String? = null,
        val firstName: String? = null,
        val lastName: String? = null,
    )

    @Serializable
    private data class PlatformSubscriptionSummaryDto(
        val trialEndDate: String? = null,
        val paidStartDate: String? = null,
        val isPaid: Boolean? = null,
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
        val copyText: String,
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
