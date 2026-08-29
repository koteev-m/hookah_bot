package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.security.MiniAppAbuseConfig
import com.hookah.platform.backend.miniapp.security.MiniAppAbuseProtection
import com.hookah.platform.backend.miniapp.security.MiniAppRateLimitPolicy
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.staff.VENUE_STAFF_MEMBER_REMOVED_ACTION
import com.hookah.platform.backend.miniapp.venue.staff.VENUE_STAFF_ROLE_CHANGED_ACTION
import com.hookah.platform.backend.module
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.telegram.User
import com.hookah.platform.backend.telegram.db.UserRepository
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.h2.jdbcx.JdbcDataSource
import java.sql.DriverManager
import java.sql.Statement
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VenueStaffRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `owner and manager receive safe identity projection with server side role scope`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-list-allowed")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 1101L
            val managerId = 1102L
            val staffId = 1103L
            val usernameOnlyStaffId = 1104L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, managerId, "MANAGER", venueId)
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            seedVenueMembership(jdbcUrl, usernameOnlyStaffId, "STAFF", venueId)
            seedUserIdentity(jdbcUrl, staffId, "max_kataev", "Максим", "Катаев")
            seedUserIdentity(jdbcUrl, usernameOnlyStaffId, "only_username", null, null)
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
            assertEquals(
                setOf(ownerId, managerId, staffId, usernameOnlyStaffId),
                ownerPayload.members.map { it.userId }.toSet(),
            )
            assertEquals(listOf(staffId, usernameOnlyStaffId), managerPayload.members.map { it.userId })
            val staff = managerPayload.members.single { it.userId == staffId }
            assertEquals("Максим Катаев", staff.displayName)
            assertEquals("max_kataev", staff.username)
            assertEquals("STAFF", staff.role)
            assertTrue(staff.active)
            assertEquals("NOT_LINKED", staff.profileLinkState)
            assertEquals(null, staff.linkedStaffProfileId)
            val usernameOnlyStaff = managerPayload.members.single { it.userId == usernameOnlyStaffId }
            assertEquals("only_username", usernameOnlyStaff.displayName)
            assertEquals("only_username", usernameOnlyStaff.username)
            val responseText = managerResponse.bodyAsText()
            assertTrue("invitedByUserId" !in responseText)
            assertTrue("createdAt" !in responseText)
            assertTrue("phone" !in responseText.lowercase())
            assertTrue("inviteCode" !in responseText)
        }

    @Test
    fun `member identity falls back safely when username is missing`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-list-missing-username")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 1151L
            val staffId = 1152L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            seedUserIdentity(jdbcUrl, staffId, null, "Максим", "Катаев")

            val response =
                client.get("/api/venue/$venueId/staff") {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, ownerId)}") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val member =
                json.decodeFromString(StaffListResponse.serializer(), response.bodyAsText())
                    .members.single { it.userId == staffId }
            assertEquals("Максим Катаев", member.displayName)
            assertEquals(null, member.username)
        }

    @Test
    fun `create from member rereads identity ignores forged authority and creates hidden active draft`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-create-from-member-authority")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 1161L
            val staffId = 1162L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            seedUserIdentity(jdbcUrl, staffId, "old_username", "Старое", "Имя")
            val ownerToken = issueToken(config, ownerId)

            val beforeUpsert = loadStaffMember(client, venueId, ownerToken, staffId)
            assertEquals("Старое Имя", beforeUpsert.displayName)
            UserRepository(h2DataSource(jdbcUrl)).upsert(
                User(
                    id = staffId,
                    username = "new_username",
                    firstName = "Новое",
                    lastName = "Имя",
                ),
            )
            val afterUpsert = loadStaffMember(client, venueId, ownerToken, staffId)
            assertEquals("Новое Имя", afterUpsert.displayName)
            assertEquals("new_username", afterUpsert.username)

            val response =
                client.post("/api/venue/$venueId/staff/profiles/from-member") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "userId": $staffId,
                          "subtype": "waiter",
                          "displayName": "FORGED_NAME",
                          "username": "forged_username",
                          "isGuestVisible": true,
                          "publishedAt": "2020-01-01T00:00:00Z",
                          "actorUserId": 999999,
                          "venueId": 999999,
                          "membershipRole": "OWNER"
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val responseText = response.bodyAsText()
            assertFalse(responseText.contains("FORGED_NAME"))
            assertFalse(responseText.contains("forged_username"))
            val created = json.decodeFromString(StaffProfileDto.serializer(), responseText)
            assertEquals(staffId, created.linkedUserId)
            assertEquals("STAFF_LINKED", created.linkageClass)
            assertTrue(created.canManage)
            assertFalse(created.isSelf)
            assertEquals("Новое Имя", created.displayName)
            assertEquals(false, created.isGuestVisible)
            assertEquals(null, created.publishedAt)
            assertEquals(null, created.disabledAt)
            assertEquals(1, countActiveLinkedProfiles(jdbcUrl, venueId, staffId))

            val auditPayload = loadSingleStaffProfileAuditPayload(jdbcUrl, venueId)
            assertTrue(auditPayload.contains("\"newLinkageClass\":\"STAFF_LINKED\""))
            assertTrue(auditPayload.contains("\"targetRole\":\"STAFF\""))
            listOf(
                staffId.toString(),
                "new_username",
                "Новое Имя",
                "FORGED_NAME",
                "forged_username",
                "isGuestVisible",
                "publishedAt",
            ).forEach { sensitive -> assertFalse(auditPayload.contains(sensitive)) }
        }

    @Test
    fun `create from member validates subtype and generic create cannot link`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-create-from-member-validation")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 1166L
            val staffId = 1167L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            val ownerToken = issueToken(config, ownerId)

            val invalidBodies =
                listOf(
                    """{"userId":$staffId}""",
                    """{"userId":$staffId,"subtype":"other"}""",
                    """{"userId":$staffId,"subtype":"other","roleLabel":"   "}""",
                    """{"userId":$staffId,"subtype":"waiter","roleLabel":"Лишняя роль"}""",
                )
            invalidBodies.forEach { body ->
                val response =
                    client.post("/api/venue/$venueId/staff/profiles/from-member") {
                        headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
                assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
            }

            val bypassResponse =
                client.post("/api/venue/$venueId/staff/profiles") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffProfileCreateRequest.serializer(),
                            StaffProfileCreateRequest(
                                displayName = "Forged linked profile",
                                linkedUserId = staffId,
                                isGuestVisible = true,
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, bypassResponse.status)
            val bypassBody = bypassResponse.bodyAsText()
            assertApiErrorEnvelope(bypassResponse, ApiErrorCodes.INVALID_INPUT)
            assertFalse(bypassBody.contains(staffId.toString()))
            assertFalse(bypassBody.contains("STAFF"))
            assertEquals(0, countActiveLinkedProfiles(jdbcUrl, venueId, staffId))
            assertEquals(0, countStaffProfileSuccessAudits(jdbcUrl, venueId))
        }

    @Test
    fun `member projection reports linked and duplicate states without automatic cleanup`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-link-state")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 1171L
            val staffId = 1172L
            val managerId = 1173L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            seedVenueMembership(jdbcUrl, managerId, "MANAGER", venueId)
            seedUserIdentity(jdbcUrl, staffId, null, "Максим", null)
            val ownerToken = issueToken(config, ownerId)
            val managerToken = issueToken(config, managerId)

            val firstResponse =
                postStaffProfile(
                    client = client,
                    venueId = venueId,
                    token = ownerToken,
                    request = StaffProfileCreateRequest(displayName = "Максим", linkedUserId = staffId),
                )
            assertEquals(HttpStatusCode.OK, firstResponse.status)
            val first = json.decodeFromString(StaffProfileDto.serializer(), firstResponse.bodyAsText())

            val linkedMember = loadStaffMember(client, venueId, ownerToken, staffId)
            assertEquals("LINKED", linkedMember.profileLinkState)
            assertEquals(first.id, linkedMember.linkedStaffProfileId)
            assertEquals("Максим", linkedMember.linkedStaffProfileDisplayName)

            val deniedSecond =
                postStaffProfile(
                    client = client,
                    venueId = venueId,
                    token = ownerToken,
                    request = StaffProfileCreateRequest(displayName = "Дубль", linkedUserId = staffId),
                )
            assertProfileLinkConflict(deniedSecond, "LINKED", first.id)
            assertEquals(1, countActiveLinkedProfiles(jdbcUrl, venueId, staffId))
            assertEquals(1, countStaffProfileSuccessAudits(jdbcUrl, venueId))

            val duplicateId = seedActiveStaffProfile(jdbcUrl, venueId, staffId, ownerId, "Старый дубль")
            val duplicateMember = loadStaffMember(client, venueId, ownerToken, staffId)
            assertEquals("DUPLICATE_LINK_DETECTED", duplicateMember.profileLinkState)
            assertEquals(null, duplicateMember.linkedStaffProfileId)
            assertEquals(null, duplicateMember.linkedStaffProfileDisplayName)

            val managerProfilesResponse =
                client.get("/api/venue/$venueId/staff/profiles") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, managerProfilesResponse.status)
            val managerProfilesBody = managerProfilesResponse.bodyAsText()
            val duplicateProfiles =
                json.decodeFromString(StaffProfilesResponse.serializer(), managerProfilesBody).profiles
                    .filter { it.id == first.id || it.id == duplicateId }
            assertEquals(2, duplicateProfiles.size)
            duplicateProfiles.forEach { duplicateProfile ->
                assertEquals(null, duplicateProfile.linkedUserId)
                assertEquals("DUPLICATE_LINK_DETECTED", duplicateProfile.linkageClass)
                assertFalse(duplicateProfile.canManage)
                assertFalse(duplicateProfile.isSelf)
            }
            assertFalse(managerProfilesBody.contains(staffId.toString()))

            val managerMutationResponse =
                client.patch("/api/venue/$venueId/staff/profiles/$duplicateId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffProfileUpdateRequest.serializer(),
                            StaffProfileUpdateRequest(displayName = "Менеджер не должен менять дубль"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Forbidden, managerMutationResponse.status)
            val managerMutationBody = managerMutationResponse.bodyAsText()
            assertApiErrorEnvelope(managerMutationResponse, ApiErrorCodes.FORBIDDEN)
            assertFalse(managerMutationBody.contains(staffId.toString()))
            assertFalse(managerMutationBody.contains("linkedUserId"))
            assertEquals(2, countActiveLinkedProfiles(jdbcUrl, venueId, staffId))
            assertEquals(1, countStaffProfileSuccessAudits(jdbcUrl, venueId))

            val ownerProfilesResponse =
                client.get("/api/venue/$venueId/staff/profiles") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, ownerProfilesResponse.status)
            json.decodeFromString(StaffProfilesResponse.serializer(), ownerProfilesResponse.bodyAsText())
                .profiles
                .filter { it.id == first.id || it.id == duplicateId }
                .forEach { duplicateProfile ->
                    assertEquals(staffId, duplicateProfile.linkedUserId)
                    assertEquals("DUPLICATE_LINK_DETECTED", duplicateProfile.linkageClass)
                    assertTrue(duplicateProfile.canManage)
                }

            val deniedWithExistingDuplicate =
                postStaffProfile(
                    client = client,
                    venueId = venueId,
                    token = ownerToken,
                    request = StaffProfileCreateRequest(displayName = "Третий", linkedUserId = staffId),
                )
            assertProfileLinkConflict(deniedWithExistingDuplicate, "DUPLICATE_LINK_DETECTED", null)
            assertEquals(2, countActiveLinkedProfiles(jdbcUrl, venueId, staffId))
            assertEquals(1, countStaffProfileSuccessAudits(jdbcUrl, venueId))

            val unlinkResponse =
                client.patch("/api/venue/$venueId/staff/profiles/$duplicateId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffProfileUpdateRequest.serializer(),
                            StaffProfileUpdateRequest(unlinkUser = true),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, unlinkResponse.status)
            assertEquals(1, countActiveLinkedProfiles(jdbcUrl, venueId, staffId))
            assertEquals("LINKED", loadStaffMember(client, venueId, ownerToken, staffId).profileLinkState)
        }

    @Test
    fun `relink and reactivation cannot create a second active linked profile`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-link-reactivation")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 1181L
            val staffId = 1182L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            val ownerToken = issueToken(config, ownerId)

            val hiddenCandidate =
                postStaffProfile(
                    client,
                    venueId,
                    ownerToken,
                    StaffProfileCreateRequest(displayName = "Скрытая", linkedUserId = staffId),
                ).let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(StaffProfileDto.serializer(), response.bodyAsText())
                }
            val publishCandidate =
                client.post("/api/venue/$venueId/staff/profiles/${hiddenCandidate.id}/publish") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, publishCandidate.status)
            val hideResponse =
                client.post("/api/venue/$venueId/staff/profiles/${hiddenCandidate.id}/hide") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, hideResponse.status)

            val active =
                postStaffProfile(
                    client,
                    venueId,
                    ownerToken,
                    StaffProfileCreateRequest(displayName = "Основная", linkedUserId = staffId),
                ).let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(StaffProfileDto.serializer(), response.bodyAsText())
                }

            val publishHidden =
                client.post("/api/venue/$venueId/staff/profiles/${hiddenCandidate.id}/publish") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertProfileLinkConflict(publishHidden, "LINKED", active.id)

            val displayOnly =
                postStaffProfile(
                    client,
                    venueId,
                    ownerToken,
                    StaffProfileCreateRequest(displayName = "Без связи"),
                ).let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(StaffProfileDto.serializer(), response.bodyAsText())
                }
            val relinkResponse =
                client.patch("/api/venue/$venueId/staff/profiles/${displayOnly.id}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffProfileUpdateRequest.serializer(),
                            StaffProfileUpdateRequest(linkedUserId = staffId),
                        ),
                    )
                }
            assertProfileLinkConflict(relinkResponse, "LINKED", active.id)

            val hiddenDisplayOnly =
                postStaffProfile(
                    client,
                    venueId,
                    ownerToken,
                    StaffProfileCreateRequest(displayName = "Скрытая без связи", isGuestVisible = true),
                ).let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(StaffProfileDto.serializer(), response.bodyAsText())
                }
            val hideDisplayOnly =
                client.post("/api/venue/$venueId/staff/profiles/${hiddenDisplayOnly.id}/hide") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, hideDisplayOnly.status)
            val linkHiddenResponse =
                client.patch("/api/venue/$venueId/staff/profiles/${hiddenDisplayOnly.id}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffProfileUpdateRequest.serializer(),
                            StaffProfileUpdateRequest(linkedUserId = staffId),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, linkHiddenResponse.status)
            val linkedHidden = json.decodeFromString(StaffProfileDto.serializer(), linkHiddenResponse.bodyAsText())
            assertEquals(staffId, linkedHidden.linkedUserId)
            assertTrue(linkedHidden.disabledAt != null)
            val publishLinkedHidden =
                client.post("/api/venue/$venueId/staff/profiles/${hiddenDisplayOnly.id}/publish") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertProfileLinkConflict(publishLinkedHidden, "LINKED", active.id)
            assertEquals(1, countActiveLinkedProfiles(jdbcUrl, venueId, staffId))
        }

    @Test
    fun `concurrent double link has one winner and one safe conflict`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-link-concurrent")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 1191L
            val staffId = 1192L
            val managerId = 1193L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            seedVenueMembership(jdbcUrl, managerId, "MANAGER", venueId)
            val ownerToken = issueToken(config, ownerId)
            val managerToken = issueToken(config, managerId)

            val responses =
                coroutineScope {
                    listOf(
                        async {
                            postStaffProfile(
                                client,
                                venueId,
                                ownerToken,
                                StaffProfileCreateRequest(displayName = "Первый", linkedUserId = staffId),
                            )
                        },
                        async {
                            postStaffProfile(
                                client,
                                venueId,
                                managerToken,
                                StaffProfileCreateRequest(displayName = "Второй", linkedUserId = staffId),
                            )
                        },
                    ).map { it.await() }
                }

            assertEquals(1, responses.count { it.status == HttpStatusCode.OK })
            assertEquals(1, responses.count { it.status == HttpStatusCode.Conflict })
            assertProfileLinkConflict(responses.single { it.status == HttpStatusCode.Conflict }, "LINKED", null)
            assertEquals(1, countActiveLinkedProfiles(jdbcUrl, venueId, staffId))
            assertEquals(1, countStaffProfileSuccessAudits(jdbcUrl, venueId))
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
            assertEquals("STAFF", loadVenueMemberRole(jdbcUrl, venueId, targetStaffId))
            assertTrue(loadStaffMembershipAuditRows(jdbcUrl, venueId).isEmpty())
        }

    @Test
    fun `manager and foreign owner cannot mutate staff membership or write audit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-management-owner-scope-denied")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 1311L
            val managerId = 1312L
            val targetStaffId = 1313L
            val foreignOwnerId = 1314L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, managerId, "MANAGER", venueId)
            seedVenueMembership(jdbcUrl, targetStaffId, "STAFF", venueId)
            seedVenueMembership(jdbcUrl, foreignOwnerId, "OWNER")
            val managerToken = issueToken(config, managerId)
            val foreignOwnerToken = issueToken(config, foreignOwnerId)

            val responses =
                listOf(
                    patchStaffRole(client, venueId, targetStaffId, managerToken, "MANAGER"),
                    deleteStaffMember(client, venueId, targetStaffId, managerToken),
                    patchStaffRole(client, venueId, targetStaffId, foreignOwnerToken, "MANAGER"),
                    deleteStaffMember(client, venueId, targetStaffId, foreignOwnerToken),
                )

            responses.forEach { response ->
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
            }
            assertEquals("STAFF", loadVenueMemberRole(jdbcUrl, venueId, targetStaffId))
            assertTrue(loadStaffMembershipAuditRows(jdbcUrl, venueId).isEmpty())
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
            assertEquals("Test User", acceptPayload.member.displayName)
            assertEquals("user", acceptPayload.member.username)
            assertEquals("STAFF", acceptPayload.member.role)
            assertTrue(acceptPayload.member.active)
            assertEquals("NOT_LINKED", acceptPayload.member.profileLinkState)
        }

    @Test
    fun `oversized declared and chunked invite acceptance bodies are rejected before invite mutation`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-invite-accept-body-bound")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 2041L
            val inviteeId = 2042L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedUser(jdbcUrl, inviteeId)
            val inviteResponse =
                client.post("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, ownerId)}") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffInviteRequest.serializer(),
                            StaffInviteRequest(role = "STAFF"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, inviteResponse.status)
            val inviteCode =
                json.decodeFromString(StaffInviteResponse.serializer(), inviteResponse.bodyAsText()).inviteCode
            val padding = "x".repeat(STAFF_INVITE_ACCEPT_REQUEST_MAX_BYTES)
            val oversizedBody = """{"inviteCode":"$inviteCode","padding":"$padding"}"""
            val inviteeToken = issueToken(config, inviteeId)

            val declaredLengthResponse =
                client.post("/api/venue/staff/invites/accept") {
                    headers { append(HttpHeaders.Authorization, "Bearer $inviteeToken") }
                    contentType(ContentType.Application.Json)
                    setBody(oversizedBody)
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, declaredLengthResponse.status)
            assertApiErrorEnvelope(declaredLengthResponse, ApiErrorCodes.INVALID_INPUT)
            assertFalse(declaredLengthResponse.bodyAsText().contains(inviteCode))

            val bodyBytes = oversizedBody.encodeToByteArray()
            val chunkedResponse =
                client.post("/api/venue/staff/invites/accept") {
                    headers { append(HttpHeaders.Authorization, "Bearer $inviteeToken") }
                    setBody(
                        object : OutgoingContent.WriteChannelContent() {
                            override val contentType: ContentType = ContentType.Application.Json
                            override val contentLength: Long? = null

                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                channel.writeFully(bodyBytes)
                            }
                        },
                    )
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, chunkedResponse.status)
            assertApiErrorEnvelope(chunkedResponse, ApiErrorCodes.INVALID_INPUT)
            assertFalse(chunkedResponse.bodyAsText().contains(inviteCode))

            val normalAcceptResponse =
                client.post("/api/venue/staff/invites/accept") {
                    headers { append(HttpHeaders.Authorization, "Bearer $inviteeToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffInviteAcceptRequest.serializer(),
                            StaffInviteAcceptRequest(inviteCode = inviteCode),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, normalAcceptResponse.status)
            val accepted =
                json.decodeFromString(
                    StaffInviteAcceptResponse.serializer(),
                    normalAcceptResponse.bodyAsText(),
                )
            assertEquals(venueId, accepted.venueId)
            assertEquals(inviteeId, accepted.member.userId)
        }

    @Test
    fun `repeated invalid invite acceptance is rate limited with a generic response`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-invite-accept-rate")
            val config = buildConfig(jdbcUrl)
            val protection =
                MiniAppAbuseProtection(
                    config =
                        MiniAppAbuseConfig(
                            inviteAcceptGlobal =
                                MiniAppRateLimitPolicy(100, Duration.ofMinutes(1)),
                            inviteAcceptSubject =
                                MiniAppRateLimitPolicy(1, Duration.ofMinutes(1)),
                            inviteAcceptDigest =
                                MiniAppRateLimitPolicy(100, Duration.ofMinutes(1)),
                        ),
                    digestKey = ByteArray(32) { 5 },
                )

            environment { this.config = config }
            application {
                moduleWithOverrides(ModuleOverrides(miniAppAbuseProtection = protection))
            }
            client.get("/health")

            val userId = 2051L
            seedUser(jdbcUrl, userId)
            val token = issueToken(config, userId)
            val requestBody =
                json.encodeToString(
                    StaffInviteAcceptRequest.serializer(),
                    StaffInviteAcceptRequest(inviteCode = "ABCDEFGHJK"),
                )

            val first =
                client.post("/api/venue/staff/invites/accept") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
            assertEquals(HttpStatusCode.BadRequest, first.status)
            assertApiErrorEnvelope(first, ApiErrorCodes.INVALID_INPUT)

            val limited =
                client.post("/api/venue/staff/invites/accept") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertApiErrorEnvelope(limited, ApiErrorCodes.RATE_LIMITED)
            assertTrue(limited.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.let { it > 0L } == true)
            assertFalse(limited.bodyAsText().contains("ABCDEFGHJK"))
        }

    @Test
    fun `active pending invite cap returns 429 without a second row or audit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-invite-active-cap")
            val config = buildConfig(jdbcUrl)
            val protection =
                MiniAppAbuseProtection(
                    config =
                        MiniAppAbuseConfig(
                            inviteCreateGlobal =
                                MiniAppRateLimitPolicy(100, Duration.ofMinutes(1)),
                            inviteCreateActorVenue =
                                MiniAppRateLimitPolicy(100, Duration.ofMinutes(1)),
                            maxActivePendingInvitesPerVenueRole = 1,
                        ),
                    digestKey = ByteArray(32) { 6 },
                )

            environment { this.config = config }
            application {
                moduleWithOverrides(ModuleOverrides(miniAppAbuseProtection = protection))
            }
            client.get("/health")

            val ownerId = 2061L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            val token = issueToken(config, ownerId)
            val requestBody =
                json.encodeToString(
                    StaffInviteRequest.serializer(),
                    StaffInviteRequest(role = "STAFF"),
                )

            val first =
                client.post("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
            assertEquals(HttpStatusCode.OK, first.status)

            val limited =
                client.post("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertApiErrorEnvelope(limited, ApiErrorCodes.RATE_LIMITED)
            assertTrue(limited.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.let { it > 0L } == true)
            assertEquals(1, countPendingStaffInvites(jdbcUrl, venueId, "STAFF"))
            assertEquals(1, loadInviteAuditRows(jdbcUrl).count { it.action == "STAFF_INVITE_CREATED" })
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
            val acceptAudit =
                loadInviteAuditRows(jdbcUrl).single { it.action == "STAFF_INVITE_ACCEPTED" }
            val acceptPayload = json.parseToJsonElement(acceptAudit.payload).jsonObject
            assertEquals(
                setOf(
                    "venueId",
                    "inviteHandle",
                    "targetRole",
                    "alreadyMember",
                    "roleChanged",
                    "keptHigherRole",
                ),
                acceptPayload.keys,
            )
            assertEquals("STAFF", acceptPayload.getValue("targetRole").jsonPrimitive.content)
            assertFalse(acceptAudit.payload.contains(invitePayload.inviteCode))
            assertEquals(1, loadInviteAuditRows(jdbcUrl).count { it.action == "STAFF_INVITE_ACCEPTED" })
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

            listOf("OWNER", "ADMIN").forEach { blockedRole ->
                val blockedResponse =
                    client.post("/api/venue/$venueId/staff/invites") {
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                        contentType(ContentType.Application.Json)
                        setBody(
                            json.encodeToString(
                                StaffInviteRequest.serializer(),
                                StaffInviteRequest(role = blockedRole),
                            ),
                        )
                    }
                assertEquals(HttpStatusCode.BadRequest, blockedResponse.status)
                assertApiErrorEnvelope(blockedResponse, ApiErrorCodes.INVALID_INPUT)
            }

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
    fun `pending invite lifecycle filters manager access revokes staff and keeps audit payload safe`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-invite-pending-lifecycle")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 3101L
            val managerId = 3102L
            val staffId = 3103L
            val foreignManagerId = 3104L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, managerId, "MANAGER", venueId)
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            seedVenueMembership(jdbcUrl, foreignManagerId, "MANAGER")
            val ownerToken = issueToken(config, ownerId)
            val managerToken = issueToken(config, managerId)

            val staffInvite =
                client.post("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(StaffInviteRequest.serializer(), StaffInviteRequest(role = "STAFF")))
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(StaffInviteResponse.serializer(), response.bodyAsText())
                }
            client.post("/api/venue/$venueId/staff/invites") {
                headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(StaffInviteRequest.serializer(), StaffInviteRequest(role = "MANAGER")))
            }.also { response -> assertEquals(HttpStatusCode.OK, response.status) }

            val managerPending =
                client.get("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    val responseText = response.bodyAsText()
                    assertPendingInviteProjectionIsIdentityFree(responseText)
                    json.decodeFromString(PendingInvitesResponse.serializer(), responseText)
                }
            assertEquals(listOf("STAFF"), managerPending.invites.map { it.role })
            assertTrue(managerPending.invites.single().handle.startsWith("sih_"))
            assertEquals("PENDING", managerPending.invites.single().status)

            val ownerPending =
                client.get("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    val responseText = response.bodyAsText()
                    assertPendingInviteProjectionIsIdentityFree(responseText)
                    json.decodeFromString(PendingInvitesResponse.serializer(), responseText)
                }
            assertEquals(setOf("STAFF", "MANAGER"), ownerPending.invites.map { it.role }.toSet())
            val staffHandle = ownerPending.invites.single { it.role == "STAFF" }.handle
            val managerHandle = ownerPending.invites.single { it.role == "MANAGER" }.handle

            val protectedRevoke =
                client.post("/api/venue/$venueId/staff/invites/$managerHandle/revoke") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.BadRequest, protectedRevoke.status)
            assertApiErrorEnvelope(protectedRevoke, ApiErrorCodes.INVALID_INPUT)

            val revoke =
                client.post("/api/venue/$venueId/staff/invites/$staffHandle/revoke") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, revoke.status)
            assertTrue(json.decodeFromString(StaffInviteRevokeResponse.serializer(), revoke.bodyAsText()).ok)

            val repeatedRevoke =
                client.post("/api/venue/$venueId/staff/invites/$staffHandle/revoke") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.BadRequest, repeatedRevoke.status)
            assertApiErrorEnvelope(repeatedRevoke, ApiErrorCodes.INVALID_INPUT)

            val revokedAccept =
                client.post("/api/venue/staff/invites/accept") {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, foreignManagerId)}") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffInviteAcceptRequest.serializer(),
                            StaffInviteAcceptRequest(inviteCode = staffInvite.inviteCode),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, revokedAccept.status)
            assertApiErrorEnvelope(revokedAccept, ApiErrorCodes.INVALID_INPUT)

            val staffList =
                client.get("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, staffId)}") }
                }
            assertEquals(HttpStatusCode.Forbidden, staffList.status)
            assertApiErrorEnvelope(staffList, ApiErrorCodes.FORBIDDEN)

            val foreignList =
                client.get("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, foreignManagerId)}") }
                }
            assertEquals(HttpStatusCode.Forbidden, foreignList.status)
            assertApiErrorEnvelope(foreignList, ApiErrorCodes.FORBIDDEN)

            val audits = loadInviteAuditRows(jdbcUrl)
            assertEquals(2, audits.count { it.action == "STAFF_INVITE_CREATED" })
            assertEquals(1, audits.count { it.action == "STAFF_INVITE_REVOKED" })
            audits.forEach { audit ->
                assertTrue(audit.payload.contains("inviteHandle"))
                assertTrue(audit.payload.contains("targetRole"))
                assertTrue(!audit.payload.contains(staffInvite.inviteCode))
                assertTrue(!audit.payload.contains("code_hash", ignoreCase = true))
                assertTrue(!audit.payload.contains("telegram", ignoreCase = true))
            }
        }

    @Test
    fun `used and expired invites cannot be revoked`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-invite-revoke-terminal")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 3151L
            val inviteeId = 3152L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedUser(jdbcUrl, inviteeId)
            val token = issueToken(config, ownerId)

            suspend fun createStaffInvite(): StaffInviteResponse =
                client.post("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(StaffInviteRequest.serializer(), StaffInviteRequest(role = "STAFF")))
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(StaffInviteResponse.serializer(), response.bodyAsText())
                }

            suspend fun pendingStaffHandle(): String =
                client.get("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(PendingInvitesResponse.serializer(), response.bodyAsText())
                        .invites.single { it.role == "STAFF" }.handle
                }

            val usedInvite = createStaffInvite()
            val usedHandle = pendingStaffHandle()
            client.post("/api/venue/staff/invites/accept") {
                headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, inviteeId)}") }
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        StaffInviteAcceptRequest.serializer(),
                        StaffInviteAcceptRequest(inviteCode = usedInvite.inviteCode),
                    ),
                )
            }.also { response -> assertEquals(HttpStatusCode.OK, response.status) }
            val usedRevoke =
                client.post("/api/venue/$venueId/staff/invites/$usedHandle/revoke") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.BadRequest, usedRevoke.status)

            val expiredInvite = createStaffInvite()
            val expiredHandle = pendingStaffHandle()
            expireStaffInvite(jdbcUrl, venueId, expiredInvite.inviteCode)
            val expiredRevoke =
                client.post("/api/venue/$venueId/staff/invites/$expiredHandle/revoke") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.BadRequest, expiredRevoke.status)

            assertEquals(0, loadInviteAuditRows(jdbcUrl).count { it.action == "STAFF_INVITE_REVOKED" })
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
            assertEquals("STAFF", loadVenueMemberRole(jdbcUrl, venueId, staffId))
            assertTrue(loadStaffMembershipAuditRows(jdbcUrl, venueId).isEmpty())
        }

    @Test
    fun `owner role change writes one exact audit and same role remains a no op`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-role-audit")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 3201L
            val staffId = 3202L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            val token = issueToken(config, ownerId)

            val appliedResponse = patchStaffRole(client, venueId, staffId, token, "MANAGER")

            assertEquals(HttpStatusCode.OK, appliedResponse.status)
            val appliedBody = appliedResponse.bodyAsText()
            assertEquals("MANAGER", json.decodeFromString(StaffMemberDto.serializer(), appliedBody).role)
            assertFalse(appliedBody.contains("target_user_id"))
            assertFalse(appliedBody.contains("targetUserId"))
            assertEquals("MANAGER", loadVenueMemberRole(jdbcUrl, venueId, staffId))

            val audit = loadStaffMembershipAuditRows(jdbcUrl, venueId).single()
            assertEquals(ownerId, audit.actorUserId)
            assertEquals(staffId, audit.targetUserId)
            assertEquals(VENUE_STAFF_ROLE_CHANGED_ACTION, audit.action)
            assertEquals("venue", audit.entityType)
            assertEquals(venueId, audit.entityId)
            val payload = json.parseToJsonElement(audit.payload).jsonObject
            assertEquals(setOf("oldRole", "newRole", "source"), payload.keys)
            assertEquals("STAFF", payload.getValue("oldRole").jsonPrimitive.content)
            assertEquals("MANAGER", payload.getValue("newRole").jsonPrimitive.content)
            assertEquals("VENUE_MINI_APP", payload.getValue("source").jsonPrimitive.content)
            assertFalse(audit.payload.contains(ownerId.toString()))
            assertFalse(audit.payload.contains(staffId.toString()))

            val noOpResponse = patchStaffRole(client, venueId, staffId, token, "MANAGER")

            assertEquals(HttpStatusCode.OK, noOpResponse.status)
            assertEquals("MANAGER", json.decodeFromString(StaffMemberDto.serializer(), noOpResponse.bodyAsText()).role)
            assertEquals(listOf(audit), loadStaffMembershipAuditRows(jdbcUrl, venueId))
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

            val removeResponse = deleteStaffMember(client, venueId, ownerId, token)

            assertEquals(HttpStatusCode.BadRequest, removeResponse.status)
            assertApiErrorEnvelope(removeResponse, ApiErrorCodes.INVALID_INPUT)
            assertEquals("OWNER", loadVenueMemberRole(jdbcUrl, venueId, ownerId))
            assertTrue(loadStaffMembershipAuditRows(jdbcUrl, venueId).isEmpty())
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
            val responseBody = response.bodyAsText()
            assertTrue(json.decodeFromString(StaffRemoveResponse.serializer(), responseBody).ok)
            assertFalse(responseBody.contains("target_user_id"))
            assertFalse(responseBody.contains("targetUserId"))
            assertEquals(null, loadVenueMemberRole(jdbcUrl, venueId, staffId))

            val audit = loadStaffMembershipAuditRows(jdbcUrl, venueId).single()
            assertEquals(ownerId, audit.actorUserId)
            assertEquals(staffId, audit.targetUserId)
            assertEquals(VENUE_STAFF_MEMBER_REMOVED_ACTION, audit.action)
            assertEquals("venue", audit.entityType)
            assertEquals(venueId, audit.entityId)
            val payload = json.parseToJsonElement(audit.payload).jsonObject
            assertEquals(setOf("oldRole", "source"), payload.keys)
            assertEquals("STAFF", payload.getValue("oldRole").jsonPrimitive.content)
            assertEquals("VENUE_MINI_APP", payload.getValue("source").jsonPrimitive.content)
            assertFalse(audit.payload.contains(ownerId.toString()))
            assertFalse(audit.payload.contains(staffId.toString()))

            val repeatedResponse = deleteStaffMember(client, venueId, staffId, token)

            assertEquals(HttpStatusCode.NotFound, repeatedResponse.status)
            assertApiErrorEnvelope(repeatedResponse, ApiErrorCodes.NOT_FOUND)
            assertEquals(listOf(audit), loadStaffMembershipAuditRows(jdbcUrl, venueId))
        }

    @Test
    fun `audit failure returns safe error and rolls back role change and removal`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-membership-audit-failure")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 5101L
            val roleTargetId = 5102L
            val removalTargetId = 5103L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, roleTargetId, "STAFF", venueId)
            seedVenueMembership(jdbcUrl, removalTargetId, "MANAGER", venueId)
            rejectStaffMembershipAuditWrites(jdbcUrl)
            val token = issueToken(config, ownerId)

            val roleResponse = patchStaffRole(client, venueId, roleTargetId, token, "MANAGER")
            val removalResponse = deleteStaffMember(client, venueId, removalTargetId, token)

            assertEquals(HttpStatusCode.ServiceUnavailable, roleResponse.status)
            assertApiErrorEnvelope(roleResponse, ApiErrorCodes.DATABASE_UNAVAILABLE)
            assertEquals(HttpStatusCode.ServiceUnavailable, removalResponse.status)
            assertApiErrorEnvelope(removalResponse, ApiErrorCodes.DATABASE_UNAVAILABLE)
            assertEquals("STAFF", loadVenueMemberRole(jdbcUrl, venueId, roleTargetId))
            assertEquals("MANAGER", loadVenueMemberRole(jdbcUrl, venueId, removalTargetId))
            assertTrue(loadStaffMembershipAuditRows(jdbcUrl, venueId).isEmpty())
            listOf(roleResponse.bodyAsText(), removalResponse.bodyAsText()).forEach { body ->
                assertFalse(body.contains("target_user_id"))
                assertFalse(body.contains("targetUserId"))
                assertFalse(body.contains("\"ok\":true"))
            }
        }

    @Test
    fun `owner can create publish hide profile and mark today shift`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-profile-owner")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 6101L
            val staffId = 6102L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            val ownerToken = issueToken(config, ownerId)

            val createResponse =
                postStaffProfile(
                    client = client,
                    venueId = venueId,
                    token = ownerToken,
                    request =
                        StaffProfileCreateRequest(
                            displayName = "Иван",
                            roleLabel = "Мастер",
                            subtype = "hookah_master",
                            linkedUserId = staffId,
                            bio = "Любит крепкие чаши",
                            tags = listOf("крепко", "ягоды"),
                        ),
                )

            assertEquals(HttpStatusCode.OK, createResponse.status)
            val created = json.decodeFromString(StaffProfileDto.serializer(), createResponse.bodyAsText())
            assertEquals(staffId, created.linkedUserId)
            assertEquals(false, created.isGuestVisible)

            val publishResponse =
                client.post("/api/venue/$venueId/staff/profiles/${created.id}/publish") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, publishResponse.status)
            val published = json.decodeFromString(StaffProfileDto.serializer(), publishResponse.bodyAsText())
            assertEquals(true, published.isGuestVisible)
            assertTrue(published.publishedAt?.isNotBlank() == true)

            val shiftResponse =
                client.post("/api/venue/$venueId/staff/profiles/${created.id}/today-shift") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffShiftUpsertRequest.serializer(),
                            StaffShiftUpsertRequest(status = "active", isGuestVisible = true),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, shiftResponse.status)
            val shift = json.decodeFromString(StaffShiftResponse.serializer(), shiftResponse.bodyAsText()).shift
            assertEquals("active", shift.status)
            assertEquals(true, shift.manuallyMarkedActive)

            val listResponse =
                client.get("/api/venue/$venueId/staff/profiles") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val listPayload = json.decodeFromString(StaffProfilesResponse.serializer(), listResponse.bodyAsText())
            assertEquals("active", listPayload.profiles.single().todayShift?.status)

            val hideResponse =
                client.post("/api/venue/$venueId/staff/profiles/${created.id}/hide") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, hideResponse.status)
            val hidden = json.decodeFromString(StaffProfileDto.serializer(), hideResponse.bodyAsText())
            assertEquals(false, hidden.isGuestVisible)
            assertTrue(hidden.disabledAt?.isNotBlank() == true)
        }

    @Test
    fun `today shift preserves planned times and advances updated at when times are omitted`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-today-preserves-planned-times")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 6151L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            val ownerToken = issueToken(config, ownerId)
            val profile =
                client.post("/api/venue/$venueId/staff/profiles") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffProfileCreateRequest.serializer(),
                            StaffProfileCreateRequest(displayName = "Плановая смена", subtype = "waiter"),
                        ),
                    )
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(StaffProfileDto.serializer(), response.bodyAsText())
                }
            val oldUpdatedAt = Instant.parse("2000-01-01T00:00:00Z")
            val today = LocalDate.now(ZoneId.of("UTC"))

            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.prepareStatement(
                    "INSERT INTO venue_settings (venue_id, timezone) VALUES (?, 'UTC')",
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    INSERT INTO staff_shifts (
                        venue_id,
                        staff_profile_id,
                        shift_date,
                        starts_at,
                        ends_at,
                        status,
                        is_guest_visible,
                        manually_marked_active,
                        created_by_user_id,
                        updated_by_user_id,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, 'scheduled', FALSE, FALSE, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, profile.id)
                    statement.setObject(3, today)
                    statement.setObject(4, LocalTime.of(9, 0))
                    statement.setObject(5, LocalTime.of(17, 0))
                    statement.setLong(6, ownerId)
                    statement.setLong(7, ownerId)
                    statement.setTimestamp(8, java.sql.Timestamp.from(oldUpdatedAt))
                    statement.executeUpdate()
                }
            }

            val response =
                client.post("/api/venue/$venueId/staff/profiles/${profile.id}/today-shift") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"status":"active","isGuestVisible":true}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val shift = json.decodeFromString(StaffShiftResponse.serializer(), response.bodyAsText()).shift
            assertEquals("09:00", shift.startsAt)
            assertEquals("17:00", shift.endsAt)
            assertNotEquals(oldUpdatedAt.toString(), shift.updatedAt)
        }

    @Test
    fun `staff can edit own linked draft fields only and cannot publish`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-profile-own-edit")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 6201L
            val staffId = 6202L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            val ownerToken = issueToken(config, ownerId)
            val staffToken = issueToken(config, staffId)
            seedUserIdentity(jdbcUrl, staffId, null, "Алина", null)

            val profile =
                postStaffProfile(
                    client = client,
                    venueId = venueId,
                    token = ownerToken,
                    request =
                        StaffProfileCreateRequest(
                            displayName = "Алина",
                            subtype = "waiter",
                            linkedUserId = staffId,
                        ),
                ).let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(StaffProfileDto.serializer(), response.bodyAsText())
                }

            val ownEditResponse =
                client.patch("/api/venue/$venueId/staff/profiles/${profile.id}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffProfileUpdateRequest.serializer(),
                            StaffProfileUpdateRequest(
                                bio = "Помогает с посадкой",
                                photoRef = "photo-ref",
                                tags = listOf("зал"),
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, ownEditResponse.status)
            val ownEdit = json.decodeFromString(StaffProfileDto.serializer(), ownEditResponse.bodyAsText())
            assertEquals("Алина", ownEdit.displayName)
            assertEquals("Помогает с посадкой", ownEdit.bio)
            assertEquals(listOf("зал"), ownEdit.tags)

            val forbiddenNameResponse =
                client.patch("/api/venue/$venueId/staff/profiles/${profile.id}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffProfileUpdateRequest.serializer(),
                            StaffProfileUpdateRequest(displayName = "Другое имя"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Forbidden, forbiddenNameResponse.status)
            assertApiErrorEnvelope(forbiddenNameResponse, ApiErrorCodes.FORBIDDEN)

            val publishResponse =
                client.post("/api/venue/$venueId/staff/profiles/${profile.id}/publish") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, publishResponse.status)
            assertApiErrorEnvelope(publishResponse, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `manager today shift preserves scheduled status and can publish display-only profile`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-profile-manager-shift")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 6301L
            val managerId = 6302L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, managerId, "MANAGER", venueId)
            val ownerToken = issueToken(config, ownerId)
            val managerToken = issueToken(config, managerId)
            val profile =
                client.post("/api/venue/$venueId/staff/profiles") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffProfileCreateRequest.serializer(),
                            StaffProfileCreateRequest(displayName = "Павел", subtype = "admin"),
                        ),
                    )
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(StaffProfileDto.serializer(), response.bodyAsText())
                }

            val activeResponse =
                client.post("/api/venue/$venueId/staff/profiles/${profile.id}/today-shift") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffShiftUpsertRequest.serializer(),
                            StaffShiftUpsertRequest(status = "active"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, activeResponse.status)

            val scheduledResponse =
                client.post("/api/venue/$venueId/staff/profiles/${profile.id}/today-shift") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffShiftUpsertRequest.serializer(),
                            StaffShiftUpsertRequest(status = "scheduled"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Forbidden, scheduledResponse.status)
            assertApiErrorEnvelope(scheduledResponse, ApiErrorCodes.FORBIDDEN)

            val publishResponse =
                client.post("/api/venue/$venueId/staff/profiles/${profile.id}/publish") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, publishResponse.status)
            val published = json.decodeFromString(StaffProfileDto.serializer(), publishResponse.bodyAsText())
            assertTrue(published.isGuestVisible)
        }

    @Test
    fun `foreign venue user cannot manage profile or shift`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-profile-foreign")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 6401L
            val foreignOwnerId = 6402L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, foreignOwnerId, "OWNER")
            val ownerToken = issueToken(config, ownerId)
            val foreignToken = issueToken(config, foreignOwnerId)
            val profile =
                client.post("/api/venue/$venueId/staff/profiles") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffProfileCreateRequest.serializer(),
                            StaffProfileCreateRequest(displayName = "Мария", subtype = "other"),
                        ),
                    )
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(StaffProfileDto.serializer(), response.bodyAsText())
                }

            val updateResponse =
                client.patch("/api/venue/$venueId/staff/profiles/${profile.id}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $foreignToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffProfileUpdateRequest.serializer(),
                            StaffProfileUpdateRequest(displayName = "Нельзя"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Forbidden, updateResponse.status)
            assertApiErrorEnvelope(updateResponse, ApiErrorCodes.FORBIDDEN)

            val shiftResponse =
                client.post("/api/venue/$venueId/staff/profiles/${profile.id}/today-shift") {
                    headers { append(HttpHeaders.Authorization, "Bearer $foreignToken") }
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(StaffShiftUpsertRequest.serializer(), StaffShiftUpsertRequest()))
                }
            assertEquals(HttpStatusCode.Forbidden, shiftResponse.status)
            assertApiErrorEnvelope(shiftResponse, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `disabled module guards optional staff routes after access checks and keeps core directory available`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("staff-module-guards")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 64_101L
            val staffId = 64_102L
            val foreignOwnerId = 64_103L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            seedVenueMembership(jdbcUrl, foreignOwnerId, "OWNER")
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO venue_settings (
                        venue_id,
                        timezone,
                        team_schedule_module_enabled,
                        guest_team_visible,
                        today_staff_source
                    )
                    VALUES (?, 'UTC', FALSE, TRUE, 'MANUAL')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeUpdate()
                }
            }
            val ownerToken = issueToken(config, ownerId)
            val staffToken = issueToken(config, staffId)
            val foreignToken = issueToken(config, foreignOwnerId)
            val today = LocalDate.now(ZoneId.of("UTC"))

            val directoryResponse =
                client.get("/api/venue/$venueId/staff") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, directoryResponse.status)
            assertEquals(
                setOf(ownerId, staffId),
                json.decodeFromString(StaffListResponse.serializer(), directoryResponse.bodyAsText())
                    .members.map { it.userId }.toSet(),
            )
            val inviteListResponse =
                client.get("/api/venue/$venueId/staff/invites") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, inviteListResponse.status)

            val profileCreateResponse =
                client.post("/api/venue/$venueId/staff/profiles") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffProfileCreateRequest.serializer(),
                            StaffProfileCreateRequest(displayName = "Не создавать", subtype = "waiter"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Conflict, profileCreateResponse.status)
            assertApiErrorEnvelope(profileCreateResponse, ApiErrorCodes.STAFF_MODULE_DISABLED)

            val profileListResponse =
                client.get("/api/venue/$venueId/staff/profiles") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.Conflict, profileListResponse.status)
            assertApiErrorEnvelope(profileListResponse, ApiErrorCodes.STAFF_MODULE_DISABLED)

            val todayResponse =
                client.get("/api/venue/$venueId/staff/shifts/today") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.Conflict, todayResponse.status)
            assertApiErrorEnvelope(todayResponse, ApiErrorCodes.STAFF_MODULE_DISABLED)

            val adminScheduleResponse =
                client.get("/api/venue/$venueId/staff/shifts?from=$today&to=$today") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.Conflict, adminScheduleResponse.status)
            assertApiErrorEnvelope(adminScheduleResponse, ApiErrorCodes.STAFF_MODULE_DISABLED)

            val scheduleCreateResponse =
                client.post("/api/venue/$venueId/staff/shifts") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"staffProfileId":999999,"shiftDate":"$today","startsAt":"10:00","endsAt":"18:00"}""",
                    )
                }
            assertEquals(HttpStatusCode.Conflict, scheduleCreateResponse.status)
            assertApiErrorEnvelope(scheduleCreateResponse, ApiErrorCodes.STAFF_MODULE_DISABLED)

            val ownScheduleResponse =
                client.get("/api/venue/$venueId/staff/shifts/me?from=$today&to=$today") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.Conflict, ownScheduleResponse.status)
            assertApiErrorEnvelope(ownScheduleResponse, ApiErrorCodes.STAFF_MODULE_DISABLED)

            val foreignProfileResponse =
                client.get("/api/venue/$venueId/staff/profiles") {
                    headers { append(HttpHeaders.Authorization, "Bearer $foreignToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, foreignProfileResponse.status)
            assertApiErrorEnvelope(foreignProfileResponse, ApiErrorCodes.FORBIDDEN)

            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE venue_settings
                    SET team_schedule_module_enabled = TRUE,
                        today_staff_source = 'SCHEDULE'
                    WHERE venue_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeUpdate()
                }
            }
            val profile =
                client.post("/api/venue/$venueId/staff/profiles") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            StaffProfileCreateRequest.serializer(),
                            StaffProfileCreateRequest(displayName = "Источник графика", subtype = "waiter"),
                        ),
                    )
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    json.decodeFromString(StaffProfileDto.serializer(), response.bodyAsText())
                }
            val manualMutationResponse =
                client.post("/api/venue/$venueId/staff/profiles/${profile.id}/today-shift") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(StaffShiftUpsertRequest.serializer(), StaffShiftUpsertRequest()))
                }
            assertEquals(HttpStatusCode.Conflict, manualMutationResponse.status)
            assertApiErrorEnvelope(manualMutationResponse, ApiErrorCodes.TODAY_STAFF_SOURCE_SCHEDULE)
            assertFalse(manualMutationResponse.bodyAsText().contains("teamScheduleModuleEnabled"))
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM staff_shifts WHERE venue_id = ? AND staff_profile_id = ?",
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, profile.id)
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals(0, rs.getInt(1))
                    }
                }
            }
        }

    private suspend fun patchStaffRole(
        client: HttpClient,
        venueId: Long,
        targetUserId: Long,
        token: String,
        role: String,
    ): HttpResponse =
        client.patch("/api/venue/$venueId/staff/$targetUserId") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    StaffUpdateRoleRequest.serializer(),
                    StaffUpdateRoleRequest(role = role),
                ),
            )
        }

    private suspend fun deleteStaffMember(
        client: HttpClient,
        venueId: Long,
        targetUserId: Long,
        token: String,
    ): HttpResponse =
        client.delete("/api/venue/$venueId/staff/$targetUserId") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

    private suspend fun postStaffProfile(
        client: HttpClient,
        venueId: Long,
        token: String,
        request: StaffProfileCreateRequest,
    ): HttpResponse {
        val linkedUserId = request.linkedUserId
        if (linkedUserId != null) {
            return client.post("/api/venue/$venueId/staff/profiles/from-member") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        StaffProfileCreateFromMemberRequest.serializer(),
                        StaffProfileCreateFromMemberRequest(
                            userId = linkedUserId,
                            subtype = request.subtype,
                            roleLabel =
                                if (request.subtype == "other") {
                                    request.roleLabel ?: "Сотрудник"
                                } else {
                                    null
                                },
                        ),
                    ),
                )
            }
        }
        return client.post("/api/venue/$venueId/staff/profiles") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(StaffProfileCreateRequest.serializer(), request))
        }
    }

    private suspend fun loadStaffMember(
        client: HttpClient,
        venueId: Long,
        token: String,
        userId: Long,
    ): StaffMemberDto {
        val response =
            client.get("/api/venue/$venueId/staff") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
            }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.decodeFromString(StaffListResponse.serializer(), response.bodyAsText())
            .members.single { it.userId == userId }
    }

    private suspend fun assertProfileLinkConflict(
        response: HttpResponse,
        expectedState: String,
        expectedProfileId: Long?,
    ) {
        assertEquals(HttpStatusCode.Conflict, response.status)
        val error = json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
        assertEquals(ApiErrorCodes.STAFF_PROFILE_LINK_CONFLICT, error.getValue("code").jsonPrimitive.content)
        val details = error.getValue("details").jsonObject
        assertEquals(expectedState, details.getValue("profileLinkState").jsonPrimitive.content)
        if (expectedProfileId != null) {
            assertEquals(expectedProfileId, details.getValue("linkedStaffProfileId").jsonPrimitive.content.toLong())
        }
        assertTrue("userId" !in details)
        assertTrue("username" !in details)
        assertTrue("linkedStaffProfileDisplayName" !in details)
    }

    private fun assertPendingInviteProjectionIsIdentityFree(responseText: String) {
        assertTrue("userId" !in responseText)
        assertTrue("username" !in responseText)
        assertTrue("displayName" !in responseText)
        assertTrue("firstName" !in responseText)
        assertTrue("lastName" !in responseText)
        assertTrue("phone" !in responseText.lowercase())
        assertTrue("inviteCode" !in responseText)
    }

    private fun seedUserIdentity(
        jdbcUrl: String,
        userId: Long,
        username: String?,
        firstName: String?,
        lastName: String?,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                UPDATE users
                SET username = ?, first_name = ?, last_name = ?
                WHERE telegram_user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, username)
                statement.setString(2, firstName)
                statement.setString(3, lastName)
                statement.setLong(4, userId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedActiveStaffProfile(
        jdbcUrl: String,
        venueId: Long,
        linkedUserId: Long,
        actorUserId: Long,
        displayName: String,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO staff_profiles (
                    venue_id,
                    linked_user_id,
                    display_name,
                    subtype,
                    is_guest_visible,
                    created_by_user_id,
                    updated_by_user_id,
                    published_at,
                    disabled_at
                )
                VALUES (?, ?, ?, 'other', FALSE, ?, ?, NULL, NULL)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, linkedUserId)
                statement.setString(3, displayName)
                statement.setLong(4, actorUserId)
                statement.setLong(5, actorUserId)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    check(rs.next())
                    rs.getLong(1)
                }
            }
        }

    private fun countActiveLinkedProfiles(
        jdbcUrl: String,
        venueId: Long,
        linkedUserId: Long,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM staff_profiles
                WHERE venue_id = ? AND linked_user_id = ? AND disabled_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, linkedUserId)
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getInt(1)
                }
            }
        }

    private fun countStaffProfileSuccessAudits(
        jdbcUrl: String,
        venueId: Long,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM audit_log
                WHERE entity_type = 'staff_profile'
                  AND payload_json LIKE ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "%\"venueId\":$venueId%")
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getInt(1)
                }
            }
        }

    private fun loadSingleStaffProfileAuditPayload(
        jdbcUrl: String,
        venueId: Long,
    ): String =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM audit_log
                WHERE entity_type = 'staff_profile'
                  AND payload_json LIKE ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "%\"venueId\":$venueId%")
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    val payload = rs.getString("payload_json")
                    check(!rs.next())
                    payload
                }
            }
        }

    private fun loadStaffMembershipAuditRows(
        jdbcUrl: String,
        venueId: Long,
    ): List<StaffMembershipAuditRow> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT actor_user_id, target_user_id, action, entity_type, entity_id, payload_json
                FROM audit_log
                WHERE entity_type = 'venue'
                  AND entity_id = ?
                  AND action IN (?, ?)
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, VENUE_STAFF_ROLE_CHANGED_ACTION)
                statement.setString(3, VENUE_STAFF_MEMBER_REMOVED_ACTION)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                StaffMembershipAuditRow(
                                    actorUserId = rs.getLong("actor_user_id"),
                                    targetUserId = rs.getLong("target_user_id").takeIf { !rs.wasNull() },
                                    action = rs.getString("action"),
                                    entityType = rs.getString("entity_type"),
                                    entityId = rs.getLong("entity_id").takeIf { !rs.wasNull() },
                                    payload = rs.getString("payload_json"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun loadVenueMemberRole(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
    ): String? =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT role FROM venue_members WHERE venue_id = ? AND user_id = ?",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.getString("role") else null }
            }
        }

    private fun rejectStaffMembershipAuditWrites(jdbcUrl: String) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    ALTER TABLE audit_log
                    ADD CONSTRAINT reject_staff_membership_audit
                    CHECK (action NOT IN ('$VENUE_STAFF_ROLE_CHANGED_ACTION', '$VENUE_STAFF_MEMBER_REMOVED_ACTION'))
                    """.trimIndent(),
                )
            }
        }
    }

    private fun h2DataSource(jdbcUrl: String): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(jdbcUrl)
            user = "sa"
            password = ""
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

    private fun loadInviteAuditRows(jdbcUrl: String): List<InviteAuditRow> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT action, payload_json
                FROM audit_log
                WHERE entity_type = 'staff_invite'
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                InviteAuditRow(
                                    action = rs.getString("action"),
                                    payload = rs.getString("payload_json"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun countPendingStaffInvites(
        jdbcUrl: String,
        venueId: Long,
        role: String,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM venue_staff_invites
                WHERE venue_id = ?
                  AND role = ?
                  AND used_at IS NULL
                  AND revoked_at IS NULL
                  AND expires_at > CURRENT_TIMESTAMP
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, role)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    resultSet.getInt(1)
                }
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
    private data class PendingInvitesResponse(
        val invites: List<PendingInviteDto>,
    )

    @Serializable
    private data class PendingInviteDto(
        val handle: String,
        val role: String,
        val status: String,
        val createdAt: String,
        val expiresAt: String,
    )

    @Serializable
    private data class StaffInviteRevokeResponse(
        val ok: Boolean,
    )

    private data class InviteAuditRow(
        val action: String,
        val payload: String,
    )

    private data class StaffMembershipAuditRow(
        val actorUserId: Long,
        val targetUserId: Long?,
        val action: String,
        val entityType: String,
        val entityId: Long?,
        val payload: String,
    )

    @Serializable
    private data class StaffMemberDto(
        val userId: Long,
        val displayName: String,
        val username: String? = null,
        val role: String,
        val active: Boolean,
        val linkedStaffProfileId: Long? = null,
        val linkedStaffProfileDisplayName: String? = null,
        val profileLinkState: String,
    )

    @Serializable
    private data class StaffUpdateRoleRequest(
        val role: String,
    )

    @Serializable
    private data class StaffRemoveResponse(
        val ok: Boolean,
    )

    @Serializable
    private data class StaffProfileCreateRequest(
        val displayName: String,
        val roleLabel: String? = null,
        val subtype: String = "other",
        val linkedUserId: Long? = null,
        val photoRef: String? = null,
        val bio: String? = null,
        val tags: List<String> = emptyList(),
        val isGuestVisible: Boolean = false,
    )

    @Serializable
    private data class StaffProfileCreateFromMemberRequest(
        val userId: Long,
        val subtype: String,
        val roleLabel: String? = null,
    )

    @Serializable
    private data class StaffProfileUpdateRequest(
        val displayName: String? = null,
        val roleLabel: String? = null,
        val subtype: String? = null,
        val linkedUserId: Long? = null,
        val unlinkUser: Boolean = false,
        val photoRef: String? = null,
        val bio: String? = null,
        val tags: List<String>? = null,
        val isGuestVisible: Boolean? = null,
    )

    @Serializable
    private data class StaffProfilesResponse(
        val profiles: List<StaffProfileDto>,
    )

    @Serializable
    private data class StaffProfileDto(
        val id: Long,
        val linkedUserId: Long? = null,
        val linkageClass: String,
        val canManage: Boolean,
        val isSelf: Boolean,
        val displayName: String,
        val roleLabel: String? = null,
        val subtype: String,
        val photoRef: String? = null,
        val bio: String? = null,
        val tags: List<String> = emptyList(),
        val isGuestVisible: Boolean,
        val publishedAt: String? = null,
        val disabledAt: String? = null,
        val todayShift: StaffShiftDto? = null,
    )

    @Serializable
    private data class StaffShiftUpsertRequest(
        val status: String = "active",
        val startsAt: String? = null,
        val endsAt: String? = null,
        val isGuestVisible: Boolean? = null,
    )

    @Serializable
    private data class StaffShiftResponse(
        val shift: StaffShiftDto,
    )

    @Serializable
    private data class StaffShiftDto(
        val id: Long,
        val staffProfileId: Long,
        val shiftDate: String,
        val startsAt: String? = null,
        val endsAt: String? = null,
        val status: String,
        val isGuestVisible: Boolean,
        val manuallyMarkedActive: Boolean,
        val updatedAt: String,
    )
}
