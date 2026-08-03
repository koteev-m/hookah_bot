package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.HttpClient
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
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VenueStaffRoutesTestManagerProfileParity {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `manager manages display only and staff linked profiles with safe transactional audit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("manager-profile-parity")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 71_001L
            val managerId = 71_002L
            val staffId = 71_003L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, managerId, "MANAGER", venueId)
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            val managerToken = issueToken(config, managerId)

            val displayOnly =
                createProfile(
                    client = client,
                    venueId = venueId,
                    token = managerToken,
                    request =
                        StaffProfileCreateRequest(
                            displayName = "Карточка без доступа",
                            photoRef = "opaque-photo-ref-must-not-leak",
                            bio = "private display bio must not leak",
                            tags = listOf("private-display-tag"),
                        ),
                )
            assertNull(displayOnly.linkedUserId)

            val staffLinked =
                createProfile(
                    client = client,
                    venueId = venueId,
                    token = managerToken,
                    request =
                        StaffProfileCreateRequest(
                            displayName = "Сотрудник",
                            subtype = "waiter",
                            linkedUserId = staffId,
                            bio = "private staff bio must not leak",
                            tags = listOf("private-staff-tag"),
                        ),
                )
            assertEquals(staffId, staffLinked.linkedUserId)

            val updatedDisplay =
                patchProfile(
                    client = client,
                    venueId = venueId,
                    profileId = displayOnly.id,
                    token = managerToken,
                    request = StaffProfileUpdateRequest(displayName = "Карточка обновлена"),
                ).requireProfile()
            assertEquals("Карточка обновлена", updatedDisplay.displayName)

            val updatedStaff =
                patchProfile(
                    client = client,
                    venueId = venueId,
                    profileId = staffLinked.id,
                    token = managerToken,
                    request = StaffProfileUpdateRequest(roleLabel = "Старший сотрудник"),
                ).requireProfile()
            assertEquals("Старший сотрудник", updatedStaff.roleLabel)

            assertVisible(
                setVisibility(client, venueId, displayOnly.id, managerToken, "publish").requireProfile(),
                expected = true,
            )
            assertVisible(
                setVisibility(client, venueId, displayOnly.id, managerToken, "hide").requireProfile(),
                expected = false,
            )
            assertVisible(
                setVisibility(client, venueId, staffLinked.id, managerToken, "publish").requireProfile(),
                expected = true,
            )
            assertVisible(
                setVisibility(client, venueId, staffLinked.id, managerToken, "hide").requireProfile(),
                expected = false,
            )

            val auditBeforeNoOps = fetchProfileAuditRows(jdbcUrl)
            assertEquals(8, auditBeforeNoOps.size)
            assertEquals(2, auditBeforeNoOps.count { it.action == "STAFF_PROFILE_CREATED" })
            assertEquals(2, auditBeforeNoOps.count { it.action == "STAFF_PROFILE_UPDATED" })
            assertEquals(2, auditBeforeNoOps.count { it.action == "STAFF_PROFILE_PUBLISHED" })
            assertEquals(2, auditBeforeNoOps.count { it.action == "STAFF_PROFILE_HIDDEN" })

            val displayCreateAudit =
                auditBeforeNoOps.single {
                    it.action == "STAFF_PROFILE_CREATED" && it.entityId == displayOnly.id
                }
            assertEquals("DISPLAY_ONLY", displayCreateAudit.payload.string("newLinkageClass"))
            assertFalse("targetRole" in displayCreateAudit.payload)

            val staffCreateAudit =
                auditBeforeNoOps.single {
                    it.action == "STAFF_PROFILE_CREATED" && it.entityId == staffLinked.id
                }
            assertEquals("STAFF_LINKED", staffCreateAudit.payload.string("newLinkageClass"))
            assertEquals("STAFF", staffCreateAudit.payload.string("targetRole"))

            val displayUpdateAudit =
                auditBeforeNoOps.single {
                    it.action == "STAFF_PROFILE_UPDATED" && it.entityId == displayOnly.id
                }
            assertEquals(listOf("displayName"), displayUpdateAudit.payload.stringList("changedFields"))

            auditBeforeNoOps.forEach { row ->
                assertEquals(managerId, row.actorUserId)
                assertEquals("staff_profile", row.entityType)
                assertEquals(venueId, row.payload.long("venueId"))
                assertEquals(row.entityId, row.payload.long("staffProfileId"))
                assertTrue(row.payload.keys.all { it in safeProfileAuditKeys })
                val payloadText = row.payload.toString()
                sensitiveTestValues.forEach { sensitiveValue ->
                    assertFalse(
                        payloadText.contains(sensitiveValue),
                        "Audit payload leaked test value: $sensitiveValue",
                    )
                }
                assertFalse("linkedUserId" in row.payload)
                assertFalse("telegramUserId" in row.payload)
                assertFalse("username" in row.payload)
            }

            patchProfile(
                client = client,
                venueId = venueId,
                profileId = displayOnly.id,
                token = managerToken,
                request = StaffProfileUpdateRequest(displayName = "Карточка обновлена"),
            ).requireProfile()
            setVisibility(client, venueId, displayOnly.id, managerToken, "hide").requireProfile()

            assertEquals(auditBeforeNoOps, fetchProfileAuditRows(jdbcUrl))
        }

    @Test
    fun `manager cannot mutate protected profiles or link missing and foreign members`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("manager-protected-profile-parity")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 72_001L
            val managerId = 72_002L
            val otherManagerId = 72_003L
            val staffId = 72_004L
            val foreignOwnerId = 72_005L
            val foreignStaffId = 72_006L
            val missingUserId = 99_999_999L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, managerId, "MANAGER", venueId)
            seedVenueMembership(jdbcUrl, otherManagerId, "MANAGER", venueId)
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            val foreignVenueId = seedVenueMembership(jdbcUrl, foreignOwnerId, "OWNER")
            seedVenueMembership(jdbcUrl, foreignStaffId, "STAFF", foreignVenueId)
            val ownerToken = issueToken(config, ownerId)
            val managerToken = issueToken(config, managerId)

            val ownerProfile =
                createProfile(
                    client,
                    venueId,
                    ownerToken,
                    StaffProfileCreateRequest(
                        displayName = "Владелец",
                        subtype = "admin",
                        linkedUserId = ownerId,
                    ),
                )
            val otherManagerProfile =
                createProfile(
                    client,
                    venueId,
                    ownerToken,
                    StaffProfileCreateRequest(
                        displayName = "Другой менеджер",
                        subtype = "admin",
                        linkedUserId = otherManagerId,
                    ),
                )
            val ownManagerProfile =
                createProfile(
                    client,
                    venueId,
                    ownerToken,
                    StaffProfileCreateRequest(
                        displayName = "Текущий менеджер",
                        subtype = "admin",
                        linkedUserId = managerId,
                    ),
                )
            assertEquals(3, fetchProfileAuditRows(jdbcUrl).size)

            val protectedResponses =
                listOf(
                    patchProfile(
                        client,
                        venueId,
                        ownerProfile.id,
                        managerToken,
                        StaffProfileUpdateRequest(displayName = "Нельзя изменить владельца"),
                    ),
                    patchProfile(
                        client,
                        venueId,
                        otherManagerProfile.id,
                        managerToken,
                        StaffProfileUpdateRequest(displayName = "Нельзя изменить менеджера"),
                    ),
                    patchProfile(
                        client,
                        venueId,
                        otherManagerProfile.id,
                        managerToken,
                        StaffProfileUpdateRequest(unlinkUser = true),
                    ),
                    patchProfile(
                        client,
                        venueId,
                        ownerProfile.id,
                        managerToken,
                        StaffProfileUpdateRequest(linkedUserId = staffId),
                    ),
                    setVisibility(client, venueId, otherManagerProfile.id, managerToken, "publish"),
                    setVisibility(client, venueId, otherManagerProfile.id, managerToken, "hide"),
                    patchProfile(
                        client,
                        venueId,
                        ownManagerProfile.id,
                        managerToken,
                        StaffProfileUpdateRequest(displayName = "Нельзя менять свою связь"),
                    ),
                    patchProfile(
                        client,
                        venueId,
                        ownManagerProfile.id,
                        managerToken,
                        StaffProfileUpdateRequest(unlinkUser = true),
                    ),
                    setVisibility(client, venueId, ownManagerProfile.id, managerToken, "publish"),
                )
            protectedResponses.forEach { response ->
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
            }

            val missingTargetResponse =
                postProfile(
                    client,
                    venueId,
                    managerToken,
                    StaffProfileCreateRequest(displayName = "Нет участника", linkedUserId = missingUserId),
                )
            assertEquals(HttpStatusCode.BadRequest, missingTargetResponse.status)
            assertApiErrorEnvelope(missingTargetResponse, ApiErrorCodes.INVALID_INPUT)

            val foreignTargetResponse =
                postProfile(
                    client,
                    venueId,
                    managerToken,
                    StaffProfileCreateRequest(
                        displayName = "Чужой участник",
                        linkedUserId = foreignStaffId,
                    ),
                )
            assertEquals(HttpStatusCode.BadRequest, foreignTargetResponse.status)
            assertApiErrorEnvelope(foreignTargetResponse, ApiErrorCodes.INVALID_INPUT)

            val safeOwnEdit =
                patchProfile(
                    client,
                    venueId,
                    ownManagerProfile.id,
                    managerToken,
                    StaffProfileUpdateRequest(
                        photoRef = "manager-private-photo-must-not-leak",
                        bio = "manager private bio must not leak",
                        tags = listOf("manager-private-tag"),
                    ),
                ).requireProfile()
            assertEquals(managerId, safeOwnEdit.linkedUserId)
            assertEquals("manager private bio must not leak", safeOwnEdit.bio)

            val audits = fetchProfileAuditRows(jdbcUrl)
            assertEquals(4, audits.size)
            val safeOwnAudit = audits.single { it.actorUserId == managerId }
            assertEquals("STAFF_PROFILE_UPDATED", safeOwnAudit.action)
            assertEquals(ownManagerProfile.id, safeOwnAudit.entityId)
            assertEquals("PROTECTED", safeOwnAudit.payload.string("oldLinkageClass"))
            assertEquals("PROTECTED", safeOwnAudit.payload.string("newLinkageClass"))
            assertEquals("MANAGER", safeOwnAudit.payload.string("targetRole"))
            assertEquals(listOf("bio", "photoRef", "tags"), safeOwnAudit.payload.stringList("changedFields"))
            val safeOwnPayloadText = safeOwnAudit.payload.toString()
            assertFalse(safeOwnPayloadText.contains("manager-private-photo-must-not-leak"))
            assertFalse(safeOwnPayloadText.contains("manager private bio must not leak"))
            assertFalse(safeOwnPayloadText.contains("manager-private-tag"))
        }

    @Test
    fun `owner keeps broad profile controls and staff keeps own safe edit only`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("owner-staff-profile-parity")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 73_001L
            val managerId = 73_002L
            val staffId = 73_003L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, managerId, "MANAGER", venueId)
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            val ownerToken = issueToken(config, ownerId)
            val staffToken = issueToken(config, staffId)

            val managerProfile =
                createProfile(
                    client,
                    venueId,
                    ownerToken,
                    StaffProfileCreateRequest(
                        displayName = "Менеджер",
                        subtype = "admin",
                        linkedUserId = managerId,
                    ),
                )
            assertEquals(
                "Менеджер обновлён владельцем",
                patchProfile(
                    client,
                    venueId,
                    managerProfile.id,
                    ownerToken,
                    StaffProfileUpdateRequest(displayName = "Менеджер обновлён владельцем"),
                ).requireProfile().displayName,
            )
            assertNull(
                patchProfile(
                    client,
                    venueId,
                    managerProfile.id,
                    ownerToken,
                    StaffProfileUpdateRequest(unlinkUser = true),
                ).requireProfile().linkedUserId,
            )
            assertEquals(
                managerId,
                patchProfile(
                    client,
                    venueId,
                    managerProfile.id,
                    ownerToken,
                    StaffProfileUpdateRequest(linkedUserId = managerId),
                ).requireProfile().linkedUserId,
            )
            assertVisible(
                setVisibility(client, venueId, managerProfile.id, ownerToken, "publish").requireProfile(),
                expected = true,
            )
            assertVisible(
                setVisibility(client, venueId, managerProfile.id, ownerToken, "hide").requireProfile(),
                expected = false,
            )

            val staffProfile =
                createProfile(
                    client,
                    venueId,
                    ownerToken,
                    StaffProfileCreateRequest(
                        displayName = "Сотрудник",
                        subtype = "waiter",
                        linkedUserId = staffId,
                    ),
                )
            val auditCountBeforeStaffMutation = fetchProfileAuditRows(jdbcUrl).size
            val safeStaffEdit =
                patchProfile(
                    client,
                    venueId,
                    staffProfile.id,
                    staffToken,
                    StaffProfileUpdateRequest(
                        photoRef = "staff-private-photo-must-not-leak",
                        bio = "staff private bio must not leak",
                        tags = listOf("staff-private-tag"),
                    ),
                ).requireProfile()
            assertEquals(staffId, safeStaffEdit.linkedUserId)
            assertEquals("Сотрудник", safeStaffEdit.displayName)
            assertEquals("staff private bio must not leak", safeStaffEdit.bio)

            val forbiddenStaffResponses =
                listOf(
                    patchProfile(
                        client,
                        venueId,
                        staffProfile.id,
                        staffToken,
                        StaffProfileUpdateRequest(displayName = "Нельзя"),
                    ),
                    patchProfile(
                        client,
                        venueId,
                        staffProfile.id,
                        staffToken,
                        StaffProfileUpdateRequest(unlinkUser = true),
                    ),
                    setVisibility(client, venueId, staffProfile.id, staffToken, "publish"),
                )
            forbiddenStaffResponses.forEach { response ->
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
            }

            val auditsAfterStaffMutations = fetchProfileAuditRows(jdbcUrl)
            assertEquals(auditCountBeforeStaffMutation + 1, auditsAfterStaffMutations.size)
            val staffAudit = auditsAfterStaffMutations.single { it.actorUserId == staffId }
            assertEquals("STAFF_PROFILE_UPDATED", staffAudit.action)
            assertEquals(staffProfile.id, staffAudit.entityId)
            assertEquals("STAFF_LINKED", staffAudit.payload.string("newLinkageClass"))
            assertEquals("STAFF", staffAudit.payload.string("targetRole"))
            val staffPayloadText = staffAudit.payload.toString()
            assertFalse(staffPayloadText.contains("staff-private-photo-must-not-leak"))
            assertFalse(staffPayloadText.contains("staff private bio must not leak"))
            assertFalse(staffPayloadText.contains("staff-private-tag"))
        }

    @Test
    fun `manager Today mutation allows display only and active staff linked profiles and preserves planned times`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("manager-today-allowed")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 75_001L
            val managerId = 75_002L
            val staffId = 75_003L
            val guestId = 75_004L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, managerId, "MANAGER", venueId)
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            seedUser(jdbcUrl, guestId)
            seedVenueTimezone(jdbcUrl, venueId, "UTC")
            val managerToken = issueToken(config, managerId)
            val guestToken = issueToken(config, guestId)
            val today = LocalDate.now(ZoneId.of("UTC"))

            val displayOnly =
                createProfile(
                    client = client,
                    venueId = venueId,
                    token = managerToken,
                    request =
                        StaffProfileCreateRequest(
                            displayName = "Ручная карточка",
                            isGuestVisible = true,
                        ),
                )
            val staffLinked =
                createProfile(
                    client = client,
                    venueId = venueId,
                    token = managerToken,
                    request =
                        StaffProfileCreateRequest(
                            displayName = "Активный сотрудник",
                            subtype = "waiter",
                            linkedUserId = staffId,
                            isGuestVisible = true,
                        ),
                )
            seedPlannedTodayShift(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                profileId = displayOnly.id,
                shiftDate = today,
                actorUserId = ownerId,
                startsAt = LocalTime.of(9, 15),
                endsAt = LocalTime.of(18, 45),
            )

            assertTrue(guestTodayNames(client, venueId, guestToken).isEmpty())

            val displayResponse = mutateTodayShift(client, venueId, displayOnly.id, managerToken)
            assertEquals(HttpStatusCode.OK, displayResponse.status, displayResponse.bodyAsText())
            val displayState = fetchTodayShiftState(jdbcUrl, venueId, displayOnly.id, today)
            assertEquals("active", displayState.status)
            assertTrue(displayState.isGuestVisible)
            assertTrue(displayState.manuallyMarkedActive)
            assertEquals(LocalTime.of(9, 15), displayState.startsAt)
            assertEquals(LocalTime.of(18, 45), displayState.endsAt)
            assertEquals(today, displayState.shiftDate)

            val staffResponse = mutateTodayShift(client, venueId, staffLinked.id, managerToken)
            assertEquals(HttpStatusCode.OK, staffResponse.status, staffResponse.bodyAsText())
            val staffState = fetchTodayShiftState(jdbcUrl, venueId, staffLinked.id, today)
            assertEquals("active", staffState.status)
            assertTrue(staffState.isGuestVisible)
            assertTrue(staffState.manuallyMarkedActive)

            assertEquals(
                setOf("Ручная карточка", "Активный сотрудник"),
                guestTodayNames(client, venueId, guestToken).toSet(),
            )
            assertEquals(2, countTodaySuccessAudits(jdbcUrl))
        }

    @Test
    fun `manager Today mutation denies every protected linkage and leaves complete shift state unchanged`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("manager-today-protected")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 76_001L
            val managerId = 76_002L
            val otherManagerId = 76_003L
            val removedStaffId = 76_004L
            val orphanedUserId = 76_005L
            val foreignOwnerId = 76_006L
            val foreignStaffId = 76_007L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, managerId, "MANAGER", venueId)
            seedVenueMembership(jdbcUrl, otherManagerId, "MANAGER", venueId)
            seedVenueMembership(jdbcUrl, removedStaffId, "STAFF", venueId)
            seedUser(jdbcUrl, orphanedUserId)
            val foreignVenueId = seedVenueMembership(jdbcUrl, foreignOwnerId, "OWNER")
            seedVenueMembership(jdbcUrl, foreignStaffId, "STAFF", foreignVenueId)
            seedVenueTimezone(jdbcUrl, venueId, "UTC")
            seedVenueTimezone(jdbcUrl, foreignVenueId, "UTC")
            val managerToken = issueToken(config, managerId)
            val today = LocalDate.now(ZoneId.of("UTC"))

            val ownerProfile = seedPublishedProfile(jdbcUrl, venueId, ownerId, ownerId, "Владелец")
            val otherManagerProfile =
                seedPublishedProfile(jdbcUrl, venueId, otherManagerId, ownerId, "Другой менеджер")
            val ownManagerProfile =
                seedPublishedProfile(jdbcUrl, venueId, managerId, ownerId, "Текущий менеджер")
            val orphanedProfile =
                seedPublishedProfile(jdbcUrl, venueId, orphanedUserId, ownerId, "Нет membership")
            val removedStaffProfile =
                seedPublishedProfile(jdbcUrl, venueId, removedStaffId, ownerId, "Удалённый сотрудник")
            removeVenueMembership(jdbcUrl, venueId, removedStaffId)
            val foreignLinkedProfile =
                seedPublishedProfile(jdbcUrl, venueId, foreignStaffId, ownerId, "Чужая membership")
            val foreignProfile =
                seedPublishedProfile(
                    jdbcUrl,
                    foreignVenueId,
                    foreignStaffId,
                    foreignOwnerId,
                    "Чужой профиль",
                )

            val sameVenueProtected =
                listOf(
                    ownerProfile,
                    otherManagerProfile,
                    ownManagerProfile,
                    orphanedProfile,
                    removedStaffProfile,
                    foreignLinkedProfile,
                )
            sameVenueProtected.forEach { profileId ->
                seedPlannedTodayShift(jdbcUrl, venueId, profileId, today, ownerId)
            }
            seedPlannedTodayShift(jdbcUrl, foreignVenueId, foreignProfile, today, foreignOwnerId)

            sameVenueProtected.forEach { profileId ->
                val before = fetchTodayShiftState(jdbcUrl, venueId, profileId, today)
                val auditBefore = countTodaySuccessAudits(jdbcUrl)
                val response = mutateTodayShift(client, venueId, profileId, managerToken)

                assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
                val error = assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN).error
                val errorText = error.message + error.details.orEmpty().toString()
                listOf("linked_user", "linkedUserId", "telegram", "membership", "owner", "manager")
                    .forEach { sensitiveField ->
                        assertFalse(errorText.contains(sensitiveField, ignoreCase = true))
                    }
                listOf(ownerId, otherManagerId, managerId, removedStaffId, orphanedUserId, foreignStaffId)
                    .forEach { sensitiveUserId ->
                        assertFalse(errorText.contains(sensitiveUserId.toString()))
                    }
                assertEquals(before, fetchTodayShiftState(jdbcUrl, venueId, profileId, today))
                assertEquals(auditBefore, countTodaySuccessAudits(jdbcUrl))
            }

            val foreignBefore = fetchTodayShiftState(jdbcUrl, foreignVenueId, foreignProfile, today)
            val foreignResponse = mutateTodayShift(client, venueId, foreignProfile, managerToken)
            assertEquals(HttpStatusCode.NotFound, foreignResponse.status, foreignResponse.bodyAsText())
            assertApiErrorEnvelope(foreignResponse, ApiErrorCodes.NOT_FOUND)
            assertEquals(foreignBefore, fetchTodayShiftState(jdbcUrl, foreignVenueId, foreignProfile, today))
            assertEquals(0, countTodaySuccessAudits(jdbcUrl))
        }

    @Test
    fun `owner keeps Today rights while staff guest platform only and foreign actors are denied`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("today-actor-matrix")
            val platformOnlyId = 77_005L
            val config = buildConfig(jdbcUrl, platformOwnerId = platformOnlyId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 77_001L
            val managerId = 77_002L
            val staffId = 77_003L
            val guestId = 77_004L
            val foreignManagerId = 77_006L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, managerId, "MANAGER", venueId)
            seedVenueMembership(jdbcUrl, staffId, "STAFF", venueId)
            seedUser(jdbcUrl, guestId)
            seedUser(jdbcUrl, platformOnlyId)
            seedVenueMembership(jdbcUrl, foreignManagerId, "MANAGER")
            seedVenueTimezone(jdbcUrl, venueId, "UTC")
            val today = LocalDate.now(ZoneId.of("UTC"))
            val protectedManagerProfile =
                seedPublishedProfile(jdbcUrl, venueId, managerId, ownerId, "Профиль менеджера")
            seedPlannedTodayShift(jdbcUrl, venueId, protectedManagerProfile, today, ownerId)
            val displayOnlyProfile =
                seedPublishedProfile(jdbcUrl, venueId, null, ownerId, "Карточка без связи")
            seedPlannedTodayShift(jdbcUrl, venueId, displayOnlyProfile, today, ownerId)

            val ownerResponse =
                mutateTodayShift(
                    client,
                    venueId,
                    protectedManagerProfile,
                    issueToken(config, ownerId),
                )
            assertEquals(HttpStatusCode.OK, ownerResponse.status, ownerResponse.bodyAsText())
            val ownerState = fetchTodayShiftState(jdbcUrl, venueId, protectedManagerProfile, today)
            assertEquals("active", ownerState.status)
            assertEquals(LocalTime.of(10, 0), ownerState.startsAt)
            assertEquals(LocalTime.of(19, 0), ownerState.endsAt)
            assertEquals(1, countTodaySuccessAudits(jdbcUrl))

            val deniedTokens =
                listOf(
                    issueToken(config, staffId),
                    issueToken(config, guestId),
                    issueToken(config, platformOnlyId),
                    issueToken(config, foreignManagerId),
                )
            deniedTokens.forEach { token ->
                val before = fetchTodayShiftState(jdbcUrl, venueId, displayOnlyProfile, today)
                val auditBefore = countTodaySuccessAudits(jdbcUrl)
                val response = mutateTodayShift(client, venueId, displayOnlyProfile, token, status = "canceled")

                assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
                assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
                assertEquals(before, fetchTodayShiftState(jdbcUrl, venueId, displayOnlyProfile, today))
                assertEquals(auditBefore, countTodaySuccessAudits(jdbcUrl))
            }
        }

    @Test
    fun `manager Today mutation rolls back when transactional audit insert fails`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("today-audit-rollback")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 78_001L
            val managerId = 78_002L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, managerId, "MANAGER", venueId)
            seedVenueTimezone(jdbcUrl, venueId, "UTC")
            val today = LocalDate.now(ZoneId.of("UTC"))
            val profileId = seedPublishedProfile(jdbcUrl, venueId, null, ownerId, "Без связи")
            seedPlannedTodayShift(jdbcUrl, venueId, profileId, today, ownerId)
            val before = fetchTodayShiftState(jdbcUrl, venueId, profileId, today)

            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        ALTER TABLE audit_log
                        ADD CONSTRAINT reject_staff_today_audit
                        CHECK (entity_type <> 'staff_shift')
                        """.trimIndent(),
                    )
                }
            }

            val response = mutateTodayShift(client, venueId, profileId, issueToken(config, managerId))

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
            assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
            assertEquals(before, fetchTodayShiftState(jdbcUrl, venueId, profileId, today))
            assertEquals(0, countTodaySuccessAudits(jdbcUrl))
        }

    @Test
    fun `manager Today mutation rechecks protected membership after deterministic profile lock race`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("today-linkage-race")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 79_001L
            val managerId = 79_002L
            val targetStaffId = 79_003L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, managerId, "MANAGER", venueId)
            seedVenueMembership(jdbcUrl, targetStaffId, "STAFF", venueId)
            seedVenueTimezone(jdbcUrl, venueId, "UTC")
            val today = LocalDate.now(ZoneId.of("UTC"))
            val profileId =
                seedPublishedProfile(jdbcUrl, venueId, targetStaffId, ownerId, "Сотрудник в гонке")
            seedPlannedTodayShift(jdbcUrl, venueId, profileId, today, ownerId)
            val before = fetchTodayShiftState(jdbcUrl, venueId, profileId, today)

            DriverManager.getConnection(jdbcUrl, "sa", "").use { barrierConnection ->
                barrierConnection.autoCommit = false
                val blockerSessionId = lockProfileAndReadSessionId(barrierConnection, venueId, profileId)
                try {
                    coroutineScope {
                        val pendingMutation =
                            async(Dispatchers.IO) {
                                mutateTodayShift(
                                    client,
                                    venueId,
                                    profileId,
                                    issueToken(config, managerId),
                                )
                            }
                        try {
                            waitForBlockedSession(jdbcUrl, blockerSessionId)

                            val roleResponse =
                                patchMemberRole(
                                    client = client,
                                    venueId = venueId,
                                    targetUserId = targetStaffId,
                                    token = issueToken(config, ownerId),
                                    role = "MANAGER",
                                )
                            assertEquals(HttpStatusCode.OK, roleResponse.status, roleResponse.bodyAsText())
                            assertEquals("MANAGER", readMembershipRole(jdbcUrl, venueId, targetStaffId))
                        } finally {
                            barrierConnection.commit()
                        }
                        val mutationResponse = pendingMutation.await()
                        assertEquals(
                            HttpStatusCode.Forbidden,
                            mutationResponse.status,
                            mutationResponse.bodyAsText(),
                        )
                        assertApiErrorEnvelope(mutationResponse, ApiErrorCodes.FORBIDDEN)
                    }
                } finally {
                    runCatching { barrierConnection.rollback() }
                }
            }

            assertEquals(before, fetchTodayShiftState(jdbcUrl, venueId, profileId, today))
            assertEquals(0, countTodaySuccessAudits(jdbcUrl))
        }

    @Test
    fun `profile mutation rolls back when transactional audit insert fails`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("profile-audit-rollback")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 74_001L
            val managerId = 74_002L
            val venueId = seedVenueMembership(jdbcUrl, ownerId, "OWNER")
            seedVenueMembership(jdbcUrl, managerId, "MANAGER", venueId)
            val managerToken = issueToken(config, managerId)

            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        ALTER TABLE audit_log
                        ADD CONSTRAINT reject_staff_profile_audit
                        CHECK (entity_type <> 'staff_profile')
                        """.trimIndent(),
                    )
                }
            }

            val response =
                postProfile(
                    client,
                    venueId,
                    managerToken,
                    StaffProfileCreateRequest(displayName = "Должно откатиться"),
                )

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
            assertEquals(0, countProfiles(jdbcUrl, venueId))
            assertTrue(fetchProfileAuditRows(jdbcUrl).isEmpty())
        }

    private suspend fun createProfile(
        client: HttpClient,
        venueId: Long,
        token: String,
        request: StaffProfileCreateRequest,
    ): StaffProfileDto = postProfile(client, venueId, token, request).requireProfile()

    private suspend fun postProfile(
        client: HttpClient,
        venueId: Long,
        token: String,
        request: StaffProfileCreateRequest,
    ): HttpResponse =
        client.post("/api/venue/$venueId/staff/profiles") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(StaffProfileCreateRequest.serializer(), request))
        }

    private suspend fun patchProfile(
        client: HttpClient,
        venueId: Long,
        profileId: Long,
        token: String,
        request: StaffProfileUpdateRequest,
    ): HttpResponse =
        client.patch("/api/venue/$venueId/staff/profiles/$profileId") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(StaffProfileUpdateRequest.serializer(), request))
        }

    private suspend fun setVisibility(
        client: HttpClient,
        venueId: Long,
        profileId: Long,
        token: String,
        action: String,
    ): HttpResponse =
        client.post("/api/venue/$venueId/staff/profiles/$profileId/$action") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

    private suspend fun mutateTodayShift(
        client: HttpClient,
        venueId: Long,
        profileId: Long,
        token: String,
        status: String = "active",
        isGuestVisible: Boolean = true,
    ): HttpResponse =
        client.post("/api/venue/$venueId/staff/profiles/$profileId/today-shift") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    TodayShiftRequest.serializer(),
                    TodayShiftRequest(status = status, isGuestVisible = isGuestVisible),
                ),
            )
        }

    private suspend fun patchMemberRole(
        client: HttpClient,
        venueId: Long,
        targetUserId: Long,
        token: String,
        role: String,
    ): HttpResponse =
        client.patch("/api/venue/$venueId/staff/$targetUserId") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            contentType(ContentType.Application.Json)
            setBody("""{"role":"$role"}""")
        }

    private suspend fun guestTodayNames(
        client: HttpClient,
        venueId: Long,
        token: String,
    ): List<String> {
        val response =
            client.get("/api/guest/venue/$venueId/today-staff") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
            }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
            .getValue("staff")
            .jsonArray
            .map { it.jsonObject.getValue("displayName").jsonPrimitive.content }
    }

    private suspend fun HttpResponse.requireProfile(): StaffProfileDto {
        val body = bodyAsText()
        assertEquals(HttpStatusCode.OK, status, body)
        return json.decodeFromString(StaffProfileDto.serializer(), body)
    }

    private fun assertVisible(
        profile: StaffProfileDto,
        expected: Boolean,
    ) {
        assertEquals(expected, profile.isGuestVisible)
        if (expected) {
            assertTrue(profile.publishedAt?.isNotBlank() == true)
            assertNull(profile.disabledAt)
        } else {
            assertTrue(profile.disabledAt?.isNotBlank() == true)
        }
    }

    private fun fetchProfileAuditRows(jdbcUrl: String): List<ProfileAuditRow> {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT actor_user_id, action, entity_type, entity_id, payload_json
                FROM audit_log
                WHERE entity_type = 'staff_profile'
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    val rows = mutableListOf<ProfileAuditRow>()
                    while (rs.next()) {
                        rows +=
                            ProfileAuditRow(
                                actorUserId = rs.getLong("actor_user_id"),
                                action = rs.getString("action"),
                                entityType = rs.getString("entity_type"),
                                entityId = rs.getLong("entity_id"),
                                payload = json.parseToJsonElement(rs.getString("payload_json")).jsonObject,
                            )
                    }
                    return rows
                }
            }
        }
    }

    private fun countProfiles(
        jdbcUrl: String,
        venueId: Long,
    ): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM staff_profiles WHERE venue_id = ?",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    return rs.getInt(1)
                }
            }
        }
    }

    private fun seedUser(
        jdbcUrl: String,
        userId: Long,
    ) {
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
        }
    }

    private fun seedVenueTimezone(
        jdbcUrl: String,
        venueId: Long,
        timezone: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "INSERT INTO venue_settings (venue_id, timezone) VALUES (?, ?)",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, timezone)
                statement.executeUpdate()
            }
        }
    }

    private fun seedPublishedProfile(
        jdbcUrl: String,
        venueId: Long,
        linkedUserId: Long?,
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
                    published_at
                )
                VALUES (?, ?, ?, 'other', TRUE, ?, ?, CURRENT_TIMESTAMP)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                if (linkedUserId == null) {
                    statement.setNull(2, Types.BIGINT)
                } else {
                    statement.setLong(2, linkedUserId)
                }
                statement.setString(3, displayName)
                statement.setLong(4, actorUserId)
                statement.setLong(5, actorUserId)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    assertTrue(rs.next())
                    rs.getLong(1)
                }
            }
        }

    private fun seedPlannedTodayShift(
        jdbcUrl: String,
        venueId: Long,
        profileId: Long,
        shiftDate: LocalDate,
        actorUserId: Long,
        startsAt: LocalTime = LocalTime.of(10, 0),
        endsAt: LocalTime = LocalTime.of(19, 0),
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
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
                statement.setLong(2, profileId)
                statement.setObject(3, shiftDate)
                statement.setObject(4, startsAt)
                statement.setObject(5, endsAt)
                statement.setLong(6, actorUserId)
                statement.setLong(7, actorUserId)
                statement.setTimestamp(8, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")))
                statement.executeUpdate()
            }
        }
    }

    private fun fetchTodayShiftState(
        jdbcUrl: String,
        venueId: Long,
        profileId: Long,
        shiftDate: LocalDate,
    ): TodayShiftState =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT shift_date,
                       starts_at,
                       ends_at,
                       status,
                       is_guest_visible,
                       manually_marked_active,
                       updated_by_user_id,
                       updated_at
                FROM staff_shifts
                WHERE venue_id = ? AND staff_profile_id = ? AND shift_date = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, profileId)
                statement.setObject(3, shiftDate)
                statement.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    TodayShiftState(
                        shiftDate = rs.getObject("shift_date", LocalDate::class.java),
                        startsAt = rs.getObject("starts_at", LocalTime::class.java),
                        endsAt = rs.getObject("ends_at", LocalTime::class.java),
                        status = rs.getString("status"),
                        isGuestVisible = rs.getBoolean("is_guest_visible"),
                        manuallyMarkedActive = rs.getBoolean("manually_marked_active"),
                        updatedByUserId = rs.getLong("updated_by_user_id").takeIf { !rs.wasNull() },
                        updatedAt = rs.getTimestamp("updated_at").toInstant(),
                    )
                }
            }
        }

    private fun countTodaySuccessAudits(jdbcUrl: String): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM audit_log
                WHERE entity_type = 'staff_shift'
                  AND action LIKE 'staff_shift_marked_%'
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    rs.getInt(1)
                }
            }
        }

    private fun removeVenueMembership(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "DELETE FROM venue_members WHERE venue_id = ? AND user_id = ?",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun lockProfileAndReadSessionId(
        connection: java.sql.Connection,
        venueId: Long,
        profileId: Long,
    ): Int {
        connection.prepareStatement(
            "SELECT id FROM staff_profiles WHERE venue_id = ? AND id = ? FOR UPDATE",
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, profileId)
            statement.executeQuery().use { rs -> assertTrue(rs.next()) }
        }
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT SESSION_ID()").use { rs ->
                assertTrue(rs.next())
                rs.getInt(1)
            }
        }
    }

    private suspend fun waitForBlockedSession(
        jdbcUrl: String,
        blockerSessionId: Int,
    ) {
        withTimeout(10_000) {
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.prepareStatement(
                    """
                    SELECT COUNT(*)
                    FROM INFORMATION_SCHEMA.SESSIONS
                    WHERE BLOCKER_ID = ? AND SESSION_STATE = 'BLOCKED'
                    """.trimIndent(),
                ).use { statement ->
                    while (true) {
                        statement.setInt(1, blockerSessionId)
                        val blocked =
                            statement.executeQuery().use { rs ->
                                assertTrue(rs.next())
                                rs.getInt(1) > 0
                            }
                        if (blocked) return@withTimeout
                        yield()
                    }
                }
            }
        }
    }

    private fun readMembershipRole(
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
                statement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(
        jdbcUrl: String,
        platformOwnerId: Long? = null,
    ): MapApplicationConfig =
        MapApplicationConfig(
            *(
                listOf(
                    "app.env" to appEnv,
                    "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to jdbcUrl,
                    "db.user" to "sa",
                    "db.password" to "",
                    "venue.staffInviteSecretPepper" to "invite-pepper",
                ) + listOfNotNull(platformOwnerId?.let { "platform.ownerUserId" to it.toString() })
            ).toTypedArray(),
        )

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
            val resolvedVenueId =
                venueId ?: connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', 'PUBLISHED')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert venue")
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

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.long(key: String): Long = string(key).toLong()

    private fun JsonObject.stringList(key: String): List<String> =
        getValue(key).jsonArray.map { it.jsonPrimitive.content }

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
    private data class TodayShiftRequest(
        val status: String,
        val isGuestVisible: Boolean,
    )

    @Serializable
    private data class StaffProfileDto(
        val id: Long,
        val linkedUserId: Long? = null,
        val displayName: String,
        val roleLabel: String? = null,
        val subtype: String,
        val photoRef: String? = null,
        val bio: String? = null,
        val tags: List<String> = emptyList(),
        val isGuestVisible: Boolean,
        val publishedAt: String? = null,
        val disabledAt: String? = null,
    )

    private data class ProfileAuditRow(
        val actorUserId: Long,
        val action: String,
        val entityType: String,
        val entityId: Long,
        val payload: JsonObject,
    )

    private data class TodayShiftState(
        val shiftDate: LocalDate,
        val startsAt: LocalTime?,
        val endsAt: LocalTime?,
        val status: String,
        val isGuestVisible: Boolean,
        val manuallyMarkedActive: Boolean,
        val updatedByUserId: Long?,
        val updatedAt: Instant,
    )

    private companion object {
        val safeProfileAuditKeys =
            setOf(
                "venueId",
                "staffProfileId",
                "changedFields",
                "oldLinkageClass",
                "newLinkageClass",
                "targetRole",
                "oldPublished",
                "newPublished",
                "oldHidden",
                "newHidden",
            )

        val sensitiveTestValues =
            setOf(
                "opaque-photo-ref-must-not-leak",
                "private display bio must not leak",
                "private-display-tag",
                "private staff bio must not leak",
                "private-staff-tag",
            )
    }
}
