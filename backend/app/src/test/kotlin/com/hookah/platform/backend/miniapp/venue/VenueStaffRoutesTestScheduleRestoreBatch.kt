package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffProfileRepository
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.headers
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VenueStaffRoutesTestScheduleRestoreBatch {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `restore keeps row identity supports old and new time and never publishes Guest Today`() =
        testApplication {
            val now = Instant.parse("2026-08-01T10:00:00Z")
            val jdbcUrl = buildJdbcUrl("staff-schedule-restore")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application {
                moduleWithOverrides(ModuleOverrides(staffScheduleClock = Clock.fixed(now, ZoneOffset.UTC)))
            }
            client.get("/health")

            val ownerId = 9101L
            val venueId = seedVenue(jdbcUrl, ownerId, "UTC")
            val oldTimeProfile =
                seedProfile(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    actorUserId = ownerId,
                    displayName = "Старое время",
                    isGuestVisible = true,
                    published = true,
                )
            val newTimeProfile =
                seedProfile(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    actorUserId = ownerId,
                    displayName = "Новое время",
                    isGuestVisible = true,
                    published = true,
                )
            val scalarCreateProfile = seedProfile(jdbcUrl, venueId, ownerId, "Обычное создание")
            val oldTime =
                seedShift(
                    jdbcUrl,
                    venueId,
                    oldTimeProfile,
                    LocalDate.parse("2026-08-01"),
                    "18:00",
                    "02:00",
                    status = "canceled",
                )
            val newTime =
                seedShift(
                    jdbcUrl,
                    venueId,
                    newTimeProfile,
                    LocalDate.parse("2026-08-02"),
                    "18:00",
                    "02:00",
                    status = "canceled",
                )
            val token = issueToken(config, ownerId)

            val canceledCreateConflict =
                client.createShift(
                    venueId,
                    token,
                    oldTimeProfile,
                    "2026-08-01",
                    "18:00",
                    "02:00",
                )
            assertEquals(HttpStatusCode.Conflict, canceledCreateConflict.status)
            assertApiErrorEnvelope(canceledCreateConflict, ApiErrorCodes.STAFF_SHIFT_CANCELED_CONFLICT)
            val conflictBody = canceledCreateConflict.bodyAsText()
            assertTrue(conflictBody.contains(oldTime.id.toString()))
            assertTrue(conflictBody.contains("canceled", ignoreCase = true))
            assertFalse(conflictBody.contains("linkedUserId"))
            assertFalse(conflictBody.contains("telegram", ignoreCase = true))
            assertFalse(conflictBody.contains("username", ignoreCase = true))

            val restoredOldTime =
                client.restoreShift(
                    venueId = venueId,
                    shiftId = oldTime.id,
                    token = token,
                    expectedUpdatedAt = oldTime.updatedAt.toString(),
                )
            assertEquals(HttpStatusCode.OK, restoredOldTime.status)
            val oldTimeResult = restoredOldTime.decodeMutation().shift
            assertEquals(oldTime.id, oldTimeResult.id)
            assertEquals("18:00", oldTimeResult.startsAt)
            assertEquals("02:00", oldTimeResult.endsAt)
            assertEquals("scheduled", oldTimeResult.storedStatus)
            assertEquals("scheduled", oldTimeResult.computedStatus)
            assertFalse(oldTimeResult.isGuestVisible)
            assertFalse(oldTimeResult.manuallyMarkedActive)
            assertNotEquals(oldTime.updatedAt.toString(), oldTimeResult.updatedAt)
            assertEquals(1, countProfileDateRows(jdbcUrl, oldTimeProfile, LocalDate.parse("2026-08-01")))

            val restoredNewTime =
                client.restoreShift(
                    venueId = venueId,
                    shiftId = newTime.id,
                    token = token,
                    expectedUpdatedAt = newTime.updatedAt.toString(),
                    startsAt = "20:00",
                    endsAt = "04:00",
                )
            assertEquals(HttpStatusCode.OK, restoredNewTime.status)
            val newTimeResult = restoredNewTime.decodeMutation().shift
            assertEquals(newTime.id, newTimeResult.id)
            assertEquals("20:00", newTimeResult.startsAt)
            assertEquals("04:00", newTimeResult.endsAt)
            assertEquals(1, countProfileDateRows(jdbcUrl, newTimeProfile, LocalDate.parse("2026-08-02")))

            val oldSnapshot = loadShift(jdbcUrl, oldTime.id)
            assertEquals("scheduled", oldSnapshot.status)
            assertFalse(oldSnapshot.isGuestVisible)
            assertFalse(oldSnapshot.manuallyMarkedActive)
            val publicToday =
                VenueStaffProfileRepository(dataSource(jdbcUrl), json)
                    .listPublicTodayStaff(venueId, LocalDate.parse("2026-08-01"))
            assertTrue(publicToday.isEmpty())

            val oldAudit = auditEntries(jdbcUrl, oldTime.id).single()
            assertEquals("STAFF_SHIFT_RESTORED", oldAudit.action)
            assertEquals(ownerId, oldAudit.actorUserId)
            assertTrue(oldAudit.payload.contains("\"oldLifecycle\":\"CANCELED\""))
            assertTrue(oldAudit.payload.contains("\"newLifecycle\":\"SCHEDULED\""))
            assertTrue(oldAudit.payload.contains("\"venueTimezone\":\"UTC\""))
            assertFalse(oldAudit.payload.contains("\"actorUserId\""))
            assertFalse(oldAudit.payload.contains("telegram", ignoreCase = true))
            assertFalse(oldAudit.payload.contains("username", ignoreCase = true))
            assertEquals(listOf("STAFF_SHIFT_RESTORED"), auditActions(jdbcUrl, newTime.id))

            val scalarCreate =
                client.createShift(
                    venueId,
                    token,
                    scalarCreateProfile,
                    "2026-08-03",
                    "11:00",
                    "19:00",
                )
            assertEquals(HttpStatusCode.OK, scalarCreate.status)
            val scalarCreated = scalarCreate.decodeMutation().shift
            assertEquals("scheduled", scalarCreated.storedStatus)
            assertFalse(scalarCreated.isGuestVisible)
            assertFalse(scalarCreated.manuallyMarkedActive)
            assertEquals(listOf("STAFF_SHIFT_CREATED"), auditActions(jdbcUrl, scalarCreated.id))

            val scheduledConflict =
                client.createShift(
                    venueId,
                    token,
                    scalarCreateProfile,
                    "2026-08-03",
                    "12:00",
                    "20:00",
                )
            assertEquals(HttpStatusCode.Conflict, scheduledConflict.status)
            assertApiErrorEnvelope(scheduledConflict, ApiErrorCodes.STAFF_SHIFT_DATE_CONFLICT)
            assertTrue(scheduledConflict.bodyAsText().contains("Смена уже запланирована на эту дату."))
        }

    @Test
    fun `restore rejects stale non canceled past foreign partial and Today override without audit`() =
        testApplication {
            val now = Instant.parse("2026-08-01T10:00:00Z")
            val jdbcUrl = buildJdbcUrl("staff-schedule-restore-denials")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application {
                moduleWithOverrides(ModuleOverrides(staffScheduleClock = Clock.fixed(now, ZoneOffset.UTC)))
            }
            client.get("/health")

            val ownerId = 9201L
            val foreignOwnerId = 9202L
            val venueId = seedVenue(jdbcUrl, ownerId, "UTC")
            val foreignVenueId = seedVenue(jdbcUrl, foreignOwnerId, "UTC")
            val token = issueToken(config, ownerId)
            val stale = seedCanceledShift(jdbcUrl, venueId, ownerId, "Stale", "2026-08-02")
            val scheduled = seedStatusShift(jdbcUrl, venueId, ownerId, "Scheduled", "scheduled", "2026-08-02")
            val active = seedStatusShift(jdbcUrl, venueId, ownerId, "Active", "active", "2026-08-02")
            val completed = seedStatusShift(jdbcUrl, venueId, ownerId, "Completed", "completed", "2026-08-02")
            val past = seedCanceledShift(jdbcUrl, venueId, ownerId, "Past", "2026-07-31")
            val overrideProfile = seedProfile(jdbcUrl, venueId, ownerId, "Today overlay")
            val todayOverride =
                seedShift(
                    jdbcUrl,
                    venueId,
                    overrideProfile,
                    LocalDate.parse("2026-08-02"),
                    "18:00",
                    "02:00",
                    status = "canceled",
                    isGuestVisible = true,
                    manuallyMarkedActive = true,
                )
            val foreignProfile = seedProfile(jdbcUrl, foreignVenueId, foreignOwnerId, "Foreign")
            val foreignShift =
                seedShift(
                    jdbcUrl,
                    foreignVenueId,
                    foreignProfile,
                    LocalDate.parse("2026-08-02"),
                    "18:00",
                    "02:00",
                    status = "canceled",
                )

            val staleResponse =
                client.restoreShift(
                    venueId,
                    stale.id,
                    token,
                    stale.updatedAt.minusSeconds(1).toString(),
                )
            assertEquals(HttpStatusCode.Conflict, staleResponse.status)
            assertApiErrorEnvelope(staleResponse, ApiErrorCodes.STAFF_SHIFT_STALE)

            listOf(scheduled, active, completed).forEach { shift ->
                val response = client.restoreShift(venueId, shift.id, token, shift.updatedAt.toString())
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertApiErrorEnvelope(response, ApiErrorCodes.STAFF_SHIFT_IMMUTABLE)
            }

            val pastResponse = client.restoreShift(venueId, past.id, token, past.updatedAt.toString())
            assertEquals(HttpStatusCode.Conflict, pastResponse.status)
            assertApiErrorEnvelope(pastResponse, ApiErrorCodes.STAFF_SHIFT_IMMUTABLE)

            val overrideResponse =
                client.restoreShift(venueId, todayOverride.id, token, todayOverride.updatedAt.toString())
            assertEquals(HttpStatusCode.Conflict, overrideResponse.status)
            assertApiErrorEnvelope(overrideResponse, ApiErrorCodes.STAFF_SHIFT_TODAY_OVERRIDE)
            val overrideSnapshot = loadShift(jdbcUrl, todayOverride.id)
            assertTrue(overrideSnapshot.isGuestVisible)
            assertTrue(overrideSnapshot.manuallyMarkedActive)

            val foreignResponse =
                client.restoreShift(venueId, foreignShift.id, token, foreignShift.updatedAt.toString())
            assertEquals(HttpStatusCode.NotFound, foreignResponse.status)
            assertApiErrorEnvelope(foreignResponse, ApiErrorCodes.NOT_FOUND)
            assertFalse(foreignResponse.bodyAsText().contains("Foreign"))

            val partialInterval =
                client.restoreShift(
                    venueId,
                    stale.id,
                    token,
                    stale.updatedAt.toString(),
                    startsAt = "20:00",
                )
            assertEquals(HttpStatusCode.BadRequest, partialInterval.status)
            assertApiErrorEnvelope(partialInterval, ApiErrorCodes.INVALID_INPUT)

            listOf(stale, scheduled, active, completed, past, todayOverride, foreignShift).forEach { shift ->
                assertTrue(auditActions(jdbcUrl, shift.id).isEmpty())
            }
            assertEquals("canceled", loadShift(jdbcUrl, stale.id).status)
            assertEquals("canceled", loadShift(jdbcUrl, past.id).status)
        }

    @Test
    fun `Owner and Manager restore and batch while Staff Guest foreign and Platform actors are denied`() =
        testApplication {
            val now = Instant.parse("2026-08-01T10:00:00Z")
            val jdbcUrl = buildJdbcUrl("staff-schedule-restore-rbac")
            val platformId = 9306L
            val config = buildConfig(jdbcUrl, platformOwnerId = platformId)
            environment { this.config = config }
            application {
                moduleWithOverrides(ModuleOverrides(staffScheduleClock = Clock.fixed(now, ZoneOffset.UTC)))
            }
            client.get("/health")

            val ownerId = 9301L
            val managerId = 9302L
            val staffId = 9303L
            val guestId = 9304L
            val foreignOwnerId = 9305L
            val venueId = seedVenue(jdbcUrl, ownerId, "UTC")
            seedMembership(jdbcUrl, venueId, managerId, "MANAGER")
            seedMembership(jdbcUrl, venueId, staffId, "STAFF")
            seedUser(jdbcUrl, guestId)
            seedUser(jdbcUrl, platformId)
            seedVenue(jdbcUrl, foreignOwnerId, "UTC")
            val ownerShift = seedCanceledShift(jdbcUrl, venueId, ownerId, "Owner restore", "2026-08-02")
            val managerShift = seedCanceledShift(jdbcUrl, venueId, ownerId, "Manager restore", "2026-08-03")
            val deniedShift = seedCanceledShift(jdbcUrl, venueId, ownerId, "SECRET PROFILE", "2026-08-04")
            val managerBatchProfile = seedProfile(jdbcUrl, venueId, ownerId, "Manager batch")
            val deniedBatchProfile = seedProfile(jdbcUrl, venueId, ownerId, "Denied batch")
            val ownerToken = issueToken(config, ownerId)
            val managerToken = issueToken(config, managerId)

            val ownerRestore =
                client.restoreShift(
                    venueId,
                    ownerShift.id,
                    ownerToken,
                    ownerShift.updatedAt.toString(),
                )
            assertEquals(HttpStatusCode.OK, ownerRestore.status)
            val managerRestore =
                client.restoreShift(
                    venueId,
                    managerShift.id,
                    managerToken,
                    managerShift.updatedAt.toString(),
                )
            assertEquals(HttpStatusCode.OK, managerRestore.status)

            val managerBatch =
                client.batchShifts(
                    venueId,
                    managerToken,
                    listOf(createAssignment(managerBatchProfile, "2026-08-05", "10:00", "18:00")),
                )
            assertEquals(HttpStatusCode.OK, managerBatch.status)
            assertEquals(1, managerBatch.decodeBatchMutation().shifts.size)

            val deniedActors =
                listOf(
                    issueToken(config, staffId),
                    issueToken(config, guestId),
                    issueToken(config, foreignOwnerId),
                    issueToken(config, platformId),
                )
            deniedActors.forEach { deniedToken ->
                val restore =
                    client.restoreShift(
                        venueId,
                        deniedShift.id,
                        deniedToken,
                        deniedShift.updatedAt.toString(),
                    )
                assertEquals(HttpStatusCode.Forbidden, restore.status)
                assertApiErrorEnvelope(restore, ApiErrorCodes.FORBIDDEN)
                val body = restore.bodyAsText()
                assertFalse(body.contains("SECRET PROFILE"))
                assertFalse(body.contains(deniedShift.updatedAt.toString()))

                val batch =
                    client.batchShifts(
                        venueId,
                        deniedToken,
                        listOf(createAssignment(deniedBatchProfile, "2026-08-06", "10:00", "18:00")),
                    )
                assertEquals(HttpStatusCode.Forbidden, batch.status)
                assertApiErrorEnvelope(batch, ApiErrorCodes.FORBIDDEN)
            }

            assertEquals("canceled", loadShift(jdbcUrl, deniedShift.id).status)
            assertEquals(0, countProfileDateRows(jdbcUrl, deniedBatchProfile, LocalDate.parse("2026-08-06")))
            assertEquals(ownerId, auditEntries(jdbcUrl, ownerShift.id).single().actorUserId)
            assertEquals(managerId, auditEntries(jdbcUrl, managerShift.id).single().actorUserId)
            val managerCreatedId = auditEntityIds(jdbcUrl, "STAFF_SHIFT_CREATED").single()
            assertEquals(managerId, auditEntries(jdbcUrl, managerCreatedId).single().actorUserId)
        }

    @Test
    fun `restore and batch roll back every write when transactional audit fails`() =
        testApplication {
            val now = Instant.parse("2026-08-01T10:00:00Z")
            val jdbcUrl = buildJdbcUrl("staff-schedule-audit-rollback")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application {
                moduleWithOverrides(ModuleOverrides(staffScheduleClock = Clock.fixed(now, ZoneOffset.UTC)))
            }
            client.get("/health")

            val ownerId = 9401L
            val venueId = seedVenue(jdbcUrl, ownerId, "UTC")
            val restoreShift = seedCanceledShift(jdbcUrl, venueId, ownerId, "Restore rollback", "2026-08-02")
            val firstCreate = seedProfile(jdbcUrl, venueId, ownerId, "First create rollback")
            val secondCreate = seedProfile(jdbcUrl, venueId, ownerId, "Second create rollback")
            installStaffShiftAuditFailure(jdbcUrl)
            val token = issueToken(config, ownerId)

            val restoreResponse =
                client.restoreShift(
                    venueId,
                    restoreShift.id,
                    token,
                    restoreShift.updatedAt.toString(),
                )
            assertEquals(HttpStatusCode.ServiceUnavailable, restoreResponse.status)
            assertApiErrorEnvelope(restoreResponse, ApiErrorCodes.DATABASE_UNAVAILABLE)
            val afterRestore = loadShift(jdbcUrl, restoreShift.id)
            assertEquals("canceled", afterRestore.status)
            assertEquals(restoreShift.updatedAt, afterRestore.updatedAt)

            val batchResponse =
                client.batchShifts(
                    venueId,
                    token,
                    listOf(
                        createAssignment(firstCreate, "2026-08-03", "10:00", "18:00"),
                        createAssignment(secondCreate, "2026-08-03", "12:00", "20:00"),
                    ),
                )
            assertEquals(HttpStatusCode.ServiceUnavailable, batchResponse.status)
            assertApiErrorEnvelope(batchResponse, ApiErrorCodes.DATABASE_UNAVAILABLE)
            assertEquals(0, countProfileDateRows(jdbcUrl, firstCreate, LocalDate.parse("2026-08-03")))
            assertEquals(0, countProfileDateRows(jdbcUrl, secondCreate, LocalDate.parse("2026-08-03")))
            assertTrue(auditActions(jdbcUrl, restoreShift.id).isEmpty())
            assertTrue(allStaffShiftAuditActions(jdbcUrl).isEmpty())
        }

    @Test
    fun `batch atomically creates common and per profile intervals and mixes explicit restore`() =
        testApplication {
            val now = Instant.parse("2026-08-01T10:00:00Z")
            val jdbcUrl = buildJdbcUrl("staff-schedule-batch-success")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application {
                moduleWithOverrides(ModuleOverrides(staffScheduleClock = Clock.fixed(now, ZoneOffset.UTC)))
            }
            client.get("/health")

            val ownerId = 9501L
            val staffId = 9502L
            val managerId = 9503L
            val venueId = seedVenue(jdbcUrl, ownerId, "UTC")
            seedMembership(jdbcUrl, venueId, staffId, "STAFF")
            seedMembership(jdbcUrl, venueId, managerId, "MANAGER")
            val first = seedProfile(jdbcUrl, venueId, ownerId, "Первый")
            val second = seedProfile(jdbcUrl, venueId, ownerId, "Staff linked", linkedUserId = staffId)
            val third = seedProfile(jdbcUrl, venueId, ownerId, "Manager linked", linkedUserId = managerId)
            val restoreProfile = seedProfile(jdbcUrl, venueId, ownerId, "Восстановить")
            val canceled =
                seedShift(
                    jdbcUrl,
                    venueId,
                    restoreProfile,
                    LocalDate.parse("2026-08-02"),
                    "17:00",
                    "01:00",
                    status = "canceled",
                )
            val token = issueToken(config, ownerId)

            val response =
                client.batchShifts(
                    venueId,
                    token,
                    listOf(
                        createAssignment(first, "2026-08-02", "18:00", "02:00"),
                        createAssignment(second, "2026-08-02", "18:00", "02:00"),
                        createAssignment(third, "2026-08-02", "20:00", "00:00"),
                        restoreAssignment(
                            restoreProfile,
                            "2026-08-02",
                            "19:00",
                            "03:00",
                            canceled.updatedAt,
                        ),
                    ),
                )
            assertEquals(HttpStatusCode.OK, response.status)
            val result = response.decodeBatchMutation().shifts
            assertEquals(4, result.size)
            val byProfile = result.associateBy { it.staffProfileId }
            assertEquals("18:00", byProfile.getValue(first).startsAt)
            assertEquals("02:00", byProfile.getValue(first).endsAt)
            assertEquals("18:00", byProfile.getValue(second).startsAt)
            assertEquals("02:00", byProfile.getValue(second).endsAt)
            assertEquals("20:00", byProfile.getValue(third).startsAt)
            assertEquals("00:00", byProfile.getValue(third).endsAt)
            assertEquals(canceled.id, byProfile.getValue(restoreProfile).id)
            assertEquals("19:00", byProfile.getValue(restoreProfile).startsAt)
            assertEquals("03:00", byProfile.getValue(restoreProfile).endsAt)
            result.forEach { shift ->
                assertEquals("scheduled", shift.storedStatus)
                assertFalse(shift.isGuestVisible)
                assertFalse(shift.manuallyMarkedActive)
            }

            assertEquals(4, countVenueDateRows(jdbcUrl, venueId, LocalDate.parse("2026-08-02")))
            assertEquals(1, countProfileDateRows(jdbcUrl, restoreProfile, LocalDate.parse("2026-08-02")))
            assertEquals(
                mapOf("STAFF_SHIFT_CREATED" to 3, "STAFF_SHIFT_RESTORED" to 1),
                allStaffShiftAuditActions(jdbcUrl).groupingBy { it }.eachCount(),
            )
            assertEquals(listOf("STAFF_SHIFT_RESTORED"), auditActions(jdbcUrl, canceled.id))
            assertEquals(4, auditEntityIds(jdbcUrl).distinct().size)
        }

    @Test
    fun `batch validation conflicts and stale restore leave zero partial rows and audits`() =
        testApplication {
            val now = Instant.parse("2027-02-01T12:00:00Z")
            val jdbcUrl = buildJdbcUrl("staff-schedule-batch-denials")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application {
                moduleWithOverrides(ModuleOverrides(staffScheduleClock = Clock.fixed(now, ZoneOffset.UTC)))
            }
            client.get("/health")

            val ownerId = 9601L
            val foreignOwnerId = 9602L
            val venueId = seedVenue(jdbcUrl, ownerId, "America/New_York")
            val foreignVenueId = seedVenue(jdbcUrl, foreignOwnerId, "America/New_York")
            val token = issueToken(config, ownerId)
            val duplicateProfile = seedProfile(jdbcUrl, venueId, ownerId, "Duplicate")
            val foreignProfile = seedProfile(jdbcUrl, foreignVenueId, foreignOwnerId, "Foreign")
            val conflictProfile = seedProfile(jdbcUrl, venueId, ownerId, "Scheduled conflict")
            val conflictClean = seedProfile(jdbcUrl, venueId, ownerId, "Scheduled clean")
            seedShift(
                jdbcUrl,
                venueId,
                conflictProfile,
                LocalDate.parse("2027-02-04"),
                "10:00",
                "18:00",
            )
            val staleRestore = seedCanceledShift(jdbcUrl, venueId, ownerId, "Stale restore", "2027-02-05")
            val staleClean = seedProfile(jdbcUrl, venueId, ownerId, "Stale clean")
            val invalidClean = seedProfile(jdbcUrl, venueId, ownerId, "Invalid clean")
            val missingCas = seedCanceledShift(jdbcUrl, venueId, ownerId, "Missing CAS", "2027-02-06")
            val canceledCreate = seedCanceledShift(jdbcUrl, venueId, ownerId, "Canceled create", "2027-02-07")
            val canceledClean = seedProfile(jdbcUrl, venueId, ownerId, "Canceled clean")

            val duplicate =
                client.batchShifts(
                    venueId,
                    token,
                    listOf(
                        createAssignment(duplicateProfile, "2027-02-02", "10:00", "18:00"),
                        createAssignment(duplicateProfile, "2027-02-02", "12:00", "20:00"),
                    ),
                )
            assertEquals(HttpStatusCode.BadRequest, duplicate.status)
            assertApiErrorEnvelope(duplicate, ApiErrorCodes.INVALID_INPUT)
            assertEquals(0, countProfileDateRows(jdbcUrl, duplicateProfile, LocalDate.parse("2027-02-02")))

            val missingProfile =
                client.batchShifts(
                    venueId,
                    token,
                    listOf(createAssignment(999_999L, "2027-02-03", "10:00", "18:00")),
                )
            assertEquals(HttpStatusCode.NotFound, missingProfile.status)
            assertApiErrorEnvelope(missingProfile, ApiErrorCodes.NOT_FOUND)

            val foreign =
                client.batchShifts(
                    venueId,
                    token,
                    listOf(createAssignment(foreignProfile, "2027-02-03", "10:00", "18:00")),
                )
            assertEquals(HttpStatusCode.NotFound, foreign.status)
            assertApiErrorEnvelope(foreign, ApiErrorCodes.NOT_FOUND)
            assertFalse(foreign.bodyAsText().contains("Foreign"))

            val scheduledConflict =
                client.batchShifts(
                    venueId,
                    token,
                    listOf(
                        createAssignment(conflictClean, "2027-02-04", "12:00", "20:00"),
                        createAssignment(conflictProfile, "2027-02-04", "11:00", "19:00"),
                    ),
                )
            assertEquals(HttpStatusCode.Conflict, scheduledConflict.status)
            assertApiErrorEnvelope(scheduledConflict, ApiErrorCodes.STAFF_SHIFT_DATE_CONFLICT)
            assertEquals(0, countProfileDateRows(jdbcUrl, conflictClean, LocalDate.parse("2027-02-04")))

            val stale =
                client.batchShifts(
                    venueId,
                    token,
                    listOf(
                        createAssignment(staleClean, "2027-02-05", "12:00", "20:00"),
                        restoreAssignment(
                            staleRestore.staffProfileId,
                            "2027-02-05",
                            "11:00",
                            "19:00",
                            staleRestore.updatedAt.minusSeconds(1),
                        ),
                    ),
                )
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertApiErrorEnvelope(stale, ApiErrorCodes.STAFF_SHIFT_STALE)
            assertEquals(0, countProfileDateRows(jdbcUrl, staleClean, LocalDate.parse("2027-02-05")))
            assertEquals("canceled", loadShift(jdbcUrl, staleRestore.id).status)

            val invalid =
                client.batchShifts(
                    venueId,
                    token,
                    listOf(
                        createAssignment(invalidClean, "2027-02-06", "10:00", "18:00"),
                        createAssignment(duplicateProfile, "2027-03-14", "02:30", "03:30"),
                    ),
                )
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertApiErrorEnvelope(invalid, ApiErrorCodes.STAFF_SHIFT_INVALID_INTERVAL)
            assertEquals(0, countProfileDateRows(jdbcUrl, invalidClean, LocalDate.parse("2027-02-06")))

            val missingExpectedUpdatedAt =
                client.batchShifts(
                    venueId,
                    token,
                    listOf(
                        VenueStaffScheduleBatchAssignmentRequest(
                            staffProfileId = missingCas.staffProfileId,
                            shiftDate = "2027-02-06",
                            startsAt = "10:00",
                            endsAt = "18:00",
                            operation = VenueStaffScheduleBatchOperation.RESTORE,
                            expectedUpdatedAt = null,
                        ),
                    ),
                )
            assertEquals(HttpStatusCode.BadRequest, missingExpectedUpdatedAt.status)
            assertApiErrorEnvelope(missingExpectedUpdatedAt, ApiErrorCodes.INVALID_INPUT)
            assertEquals("canceled", loadShift(jdbcUrl, missingCas.id).status)

            val canceledConflict =
                client.batchShifts(
                    venueId,
                    token,
                    listOf(
                        createAssignment(canceledClean, "2027-02-07", "12:00", "20:00"),
                        createAssignment(canceledCreate.staffProfileId, "2027-02-07", "10:00", "18:00"),
                    ),
                )
            assertEquals(HttpStatusCode.Conflict, canceledConflict.status)
            assertApiErrorEnvelope(canceledConflict, ApiErrorCodes.STAFF_SHIFT_CANCELED_CONFLICT)
            assertEquals(0, countProfileDateRows(jdbcUrl, canceledClean, LocalDate.parse("2027-02-07")))
            assertEquals("canceled", loadShift(jdbcUrl, canceledCreate.id).status)

            val oversizedProfiles =
                (1..51).map { index ->
                    seedProfile(jdbcUrl, venueId, ownerId, "Oversized $index")
                }
            val oversized =
                client.batchShifts(
                    venueId,
                    token,
                    oversizedProfiles.map { profileId ->
                        createAssignment(profileId, "2027-02-08", "10:00", "18:00")
                    },
                )
            assertEquals(HttpStatusCode.BadRequest, oversized.status)
            assertApiErrorEnvelope(oversized, ApiErrorCodes.INVALID_INPUT)
            assertEquals(0, countVenueDateRows(jdbcUrl, venueId, LocalDate.parse("2027-02-08")))

            assertTrue(allStaffShiftAuditActions(jdbcUrl).isEmpty())
        }

    @Test
    fun `concurrent create and restore and two restores have exactly one mutation winner`() =
        testApplication {
            val now = Instant.parse("2026-08-01T10:00:00Z")
            val jdbcUrl = buildJdbcUrl("staff-schedule-restore-concurrency")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application {
                moduleWithOverrides(ModuleOverrides(staffScheduleClock = Clock.fixed(now, ZoneOffset.UTC)))
            }
            client.get("/health")

            val ownerId = 9701L
            val venueId = seedVenue(jdbcUrl, ownerId, "UTC")
            val createRestore = seedCanceledShift(jdbcUrl, venueId, ownerId, "Create restore", "2026-08-02")
            val doubleRestore = seedCanceledShift(jdbcUrl, venueId, ownerId, "Double restore", "2026-08-03")
            val token = issueToken(config, ownerId)

            val createRestoreResponses =
                coroutineScope {
                    listOf(
                        async {
                            client.createShift(
                                venueId,
                                token,
                                createRestore.staffProfileId,
                                "2026-08-02",
                                "19:00",
                                "03:00",
                            )
                        },
                        async {
                            client.restoreShift(
                                venueId,
                                createRestore.id,
                                token,
                                createRestore.updatedAt.toString(),
                            )
                        },
                    ).awaitAll()
                }
            assertEquals(1, createRestoreResponses.count { it.status == HttpStatusCode.OK })
            assertEquals(1, createRestoreResponses.count { it.status == HttpStatusCode.Conflict })
            assertEquals(
                1,
                countProfileDateRows(jdbcUrl, createRestore.staffProfileId, LocalDate.parse("2026-08-02")),
            )
            assertEquals("scheduled", loadShift(jdbcUrl, createRestore.id).status)
            assertEquals(listOf("STAFF_SHIFT_RESTORED"), auditActions(jdbcUrl, createRestore.id))

            val doubleRestoreResponses =
                coroutineScope {
                    listOf(
                        async {
                            client.restoreShift(
                                venueId,
                                doubleRestore.id,
                                token,
                                doubleRestore.updatedAt.toString(),
                                "20:00",
                                "04:00",
                            )
                        },
                        async {
                            client.restoreShift(
                                venueId,
                                doubleRestore.id,
                                token,
                                doubleRestore.updatedAt.toString(),
                                "21:00",
                                "05:00",
                            )
                        },
                    ).awaitAll()
                }
            assertEquals(1, doubleRestoreResponses.count { it.status == HttpStatusCode.OK })
            assertEquals(1, doubleRestoreResponses.count { it.status == HttpStatusCode.Conflict })
            assertEquals(
                1,
                countProfileDateRows(jdbcUrl, doubleRestore.staffProfileId, LocalDate.parse("2026-08-03")),
            )
            val winningTimes = loadShift(jdbcUrl, doubleRestore.id).let { it.startsAt to it.endsAt }
            assertTrue(
                winningTimes == (LocalTime.parse("20:00") to LocalTime.parse("04:00")) ||
                    winningTimes == (LocalTime.parse("21:00") to LocalTime.parse("05:00")),
            )
            assertEquals(listOf("STAFF_SHIFT_RESTORED"), auditActions(jdbcUrl, doubleRestore.id))
        }

    private suspend fun HttpClient.createShift(
        venueId: Long,
        token: String,
        staffProfileId: Long,
        shiftDate: String,
        startsAt: String,
        endsAt: String,
    ): HttpResponse =
        post("/api/venue/$venueId/staff/shifts") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    VenueStaffScheduleCreateRequest.serializer(),
                    VenueStaffScheduleCreateRequest(staffProfileId, shiftDate, startsAt, endsAt),
                ),
            )
        }

    private suspend fun HttpClient.restoreShift(
        venueId: Long,
        shiftId: Long,
        token: String,
        expectedUpdatedAt: String,
        startsAt: String? = null,
        endsAt: String? = null,
    ): HttpResponse =
        post("/api/venue/$venueId/staff/shifts/$shiftId/restore") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    VenueStaffScheduleRestoreRequest.serializer(),
                    VenueStaffScheduleRestoreRequest(expectedUpdatedAt, startsAt, endsAt),
                ),
            )
        }

    private suspend fun HttpClient.batchShifts(
        venueId: Long,
        token: String,
        assignments: List<VenueStaffScheduleBatchAssignmentRequest>,
    ): HttpResponse =
        post("/api/venue/$venueId/staff/shifts/batch") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    VenueStaffScheduleBatchRequest.serializer(),
                    VenueStaffScheduleBatchRequest(assignments),
                ),
            )
        }

    private suspend fun HttpResponse.decodeMutation(): VenueStaffScheduleMutationResponse =
        json.decodeFromString(VenueStaffScheduleMutationResponse.serializer(), bodyAsText())

    private suspend fun HttpResponse.decodeBatchMutation(): VenueStaffScheduleBatchMutationResponse =
        json.decodeFromString(VenueStaffScheduleBatchMutationResponse.serializer(), bodyAsText())

    private fun createAssignment(
        profileId: Long,
        shiftDate: String,
        startsAt: String,
        endsAt: String,
    ): VenueStaffScheduleBatchAssignmentRequest =
        VenueStaffScheduleBatchAssignmentRequest(
            staffProfileId = profileId,
            shiftDate = shiftDate,
            startsAt = startsAt,
            endsAt = endsAt,
            operation = VenueStaffScheduleBatchOperation.CREATE,
        )

    private fun restoreAssignment(
        profileId: Long,
        shiftDate: String,
        startsAt: String,
        endsAt: String,
        expectedUpdatedAt: Instant,
    ): VenueStaffScheduleBatchAssignmentRequest =
        VenueStaffScheduleBatchAssignmentRequest(
            staffProfileId = profileId,
            shiftDate = shiftDate,
            startsAt = startsAt,
            endsAt = endsAt,
            operation = VenueStaffScheduleBatchOperation.RESTORE,
            expectedUpdatedAt = expectedUpdatedAt.toString(),
        )

    private fun HttpRequestBuilder.bearer(token: String) {
        headers { append(HttpHeaders.Authorization, "Bearer $token") }
    }

    private fun buildJdbcUrl(prefix: String): String =
        "jdbc:h2:mem:$prefix-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
            "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"

    private fun buildConfig(
        jdbcUrl: String,
        platformOwnerId: Long? = null,
    ): MapApplicationConfig =
        MapApplicationConfig(
            *(
                listOf(
                    "app.env" to "test",
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
    ): String =
        SessionTokenService(SessionTokenConfig.from(config, "test"))
            .issueToken(userId)
            .token

    private fun dataSource(jdbcUrl: String): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(jdbcUrl)
            user = "sa"
            password = ""
        }

    private fun seedVenue(
        jdbcUrl: String,
        ownerId: Long,
        timezone: String,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            seedUser(connection, ownerId)
            val venueId =
                connection.prepareStatement(
                    "INSERT INTO venues (name, city, address, status) VALUES ('Venue', 'City', 'Address', 'PUBLISHED')",
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        check(rs.next())
                        rs.getLong(1)
                    }
                }
            connection.prepareStatement(
                "INSERT INTO venue_members (venue_id, user_id, role) VALUES (?, ?, 'OWNER')",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, ownerId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO venue_settings (venue_id, timezone) VALUES (?, ?)",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, timezone)
                statement.executeUpdate()
            }
            venueId
        }

    private fun seedMembership(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        role: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            seedUser(connection, userId)
            connection.prepareStatement(
                "INSERT INTO venue_members (venue_id, user_id, role) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
        }
    }

    private fun seedUser(
        jdbcUrl: String,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection -> seedUser(connection, userId) }
    }

    private fun seedUser(
        connection: Connection,
        userId: Long,
    ) {
        connection.prepareStatement(
            """
            MERGE INTO users (telegram_user_id, username, first_name, last_name)
            KEY (telegram_user_id)
            VALUES (?, 'fixture-user', 'Fixture', 'User')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeUpdate()
        }
    }

    private fun seedProfile(
        jdbcUrl: String,
        venueId: Long,
        actorUserId: Long,
        displayName: String,
        linkedUserId: Long? = null,
        isGuestVisible: Boolean = false,
        published: Boolean = false,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO staff_profiles (
                    venue_id, linked_user_id, display_name, role_label, subtype,
                    is_guest_visible, created_by_user_id, updated_by_user_id, published_at
                )
                VALUES (?, ?, ?, 'Сотрудник', 'other', ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                if (linkedUserId == null) {
                    statement.setNull(2, java.sql.Types.BIGINT)
                } else {
                    statement.setLong(2, linkedUserId)
                }
                statement.setString(3, displayName)
                statement.setBoolean(4, isGuestVisible)
                statement.setLong(5, actorUserId)
                statement.setLong(6, actorUserId)
                if (published) {
                    statement.setTimestamp(7, Timestamp.from(Instant.parse("2026-08-01T00:00:00Z")))
                } else {
                    statement.setNull(7, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                }
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    check(rs.next())
                    rs.getLong(1)
                }
            }
        }

    private fun seedCanceledShift(
        jdbcUrl: String,
        venueId: Long,
        actorUserId: Long,
        displayName: String,
        shiftDate: String,
    ): ShiftSnapshot {
        val profileId = seedProfile(jdbcUrl, venueId, actorUserId, displayName)
        return seedShift(
            jdbcUrl,
            venueId,
            profileId,
            LocalDate.parse(shiftDate),
            "18:00",
            "02:00",
            status = "canceled",
            updatedAt = Instant.parse("2026-08-01T09:00:00Z"),
        )
    }

    private fun seedStatusShift(
        jdbcUrl: String,
        venueId: Long,
        actorUserId: Long,
        displayName: String,
        status: String,
        shiftDate: String,
    ): ShiftSnapshot {
        val profileId = seedProfile(jdbcUrl, venueId, actorUserId, displayName)
        return seedShift(
            jdbcUrl,
            venueId,
            profileId,
            LocalDate.parse(shiftDate),
            "18:00",
            "02:00",
            status = status,
            updatedAt = Instant.parse("2026-08-01T09:00:00Z"),
        )
    }

    private fun seedShift(
        jdbcUrl: String,
        venueId: Long,
        profileId: Long,
        shiftDate: LocalDate,
        startsAt: String,
        endsAt: String,
        status: String = "scheduled",
        isGuestVisible: Boolean = false,
        manuallyMarkedActive: Boolean = false,
        updatedAt: Instant = Instant.parse("2026-08-01T09:00:00Z"),
    ): ShiftSnapshot =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val actorId =
                connection.prepareStatement(
                    "SELECT created_by_user_id FROM staff_profiles WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, profileId)
                    statement.executeQuery().use { rs ->
                        check(rs.next())
                        rs.getLong(1)
                    }
                }
            val shiftId =
                connection.prepareStatement(
                    """
                    INSERT INTO staff_shifts (
                        venue_id, staff_profile_id, shift_date, starts_at, ends_at, status,
                        is_guest_visible, manually_marked_active, created_by_user_id, updated_by_user_id,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, profileId)
                    statement.setObject(3, shiftDate)
                    statement.setObject(4, LocalTime.parse(startsAt))
                    statement.setObject(5, LocalTime.parse(endsAt))
                    statement.setString(6, status)
                    statement.setBoolean(7, isGuestVisible)
                    statement.setBoolean(8, manuallyMarkedActive)
                    statement.setLong(9, actorId)
                    statement.setLong(10, actorId)
                    statement.setTimestamp(11, Timestamp.from(updatedAt))
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        check(rs.next())
                        rs.getLong(1)
                    }
                }
            loadShift(connection, shiftId)
        }

    private fun loadShift(
        jdbcUrl: String,
        shiftId: Long,
    ): ShiftSnapshot =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection -> loadShift(connection, shiftId) }

    private fun loadShift(
        connection: Connection,
        shiftId: Long,
    ): ShiftSnapshot =
        connection.prepareStatement(
            """
            SELECT id, staff_profile_id, shift_date, starts_at, ends_at, status,
                   is_guest_visible, manually_marked_active, updated_at
            FROM staff_shifts
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, shiftId)
            statement.executeQuery().use { rs ->
                check(rs.next())
                ShiftSnapshot(
                    id = rs.getLong("id"),
                    staffProfileId = rs.getLong("staff_profile_id"),
                    shiftDate = rs.getObject("shift_date", LocalDate::class.java),
                    startsAt = rs.getObject("starts_at", LocalTime::class.java),
                    endsAt = rs.getObject("ends_at", LocalTime::class.java),
                    status = rs.getString("status"),
                    isGuestVisible = rs.getBoolean("is_guest_visible"),
                    manuallyMarkedActive = rs.getBoolean("manually_marked_active"),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                )
            }
        }

    private fun countProfileDateRows(
        jdbcUrl: String,
        profileId: Long,
        shiftDate: LocalDate,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM staff_shifts WHERE staff_profile_id = ? AND shift_date = ?",
            ).use { statement ->
                statement.setLong(1, profileId)
                statement.setObject(2, shiftDate)
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getInt(1)
                }
            }
        }

    private fun countVenueDateRows(
        jdbcUrl: String,
        venueId: Long,
        shiftDate: LocalDate,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM staff_shifts WHERE venue_id = ? AND shift_date = ?",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setObject(2, shiftDate)
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getInt(1)
                }
            }
        }

    private fun auditEntries(
        jdbcUrl: String,
        shiftId: Long,
    ): List<AuditEntry> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT actor_user_id, action, payload_json
                FROM audit_log
                WHERE entity_type = 'staff_shift' AND entity_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, shiftId)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                AuditEntry(
                                    actorUserId = rs.getLong("actor_user_id"),
                                    action = rs.getString("action"),
                                    payload = rs.getString("payload_json"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun auditActions(
        jdbcUrl: String,
        shiftId: Long,
    ): List<String> = auditEntries(jdbcUrl, shiftId).map { it.action }

    private fun allStaffShiftAuditActions(jdbcUrl: String): List<String> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT action FROM audit_log WHERE entity_type = 'staff_shift' ORDER BY id",
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getString(1))
                    }
                }
            }
        }

    private fun auditEntityIds(
        jdbcUrl: String,
        action: String? = null,
    ): List<Long> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val actionFilter = if (action == null) "" else "AND action = ?"
            connection.prepareStatement(
                """
                SELECT entity_id
                FROM audit_log
                WHERE entity_type = 'staff_shift'
                  $actionFilter
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                if (action != null) statement.setString(1, action)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getLong(1))
                    }
                }
            }
        }

    private fun installStaffShiftAuditFailure(jdbcUrl: String) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    ALTER TABLE audit_log
                    ADD CONSTRAINT reject_staff_schedule_audit
                    CHECK (entity_type <> 'staff_shift')
                    """.trimIndent(),
                )
            }
        }
    }

    private data class ShiftSnapshot(
        val id: Long,
        val staffProfileId: Long,
        val shiftDate: LocalDate,
        val startsAt: LocalTime,
        val endsAt: LocalTime,
        val status: String,
        val isGuestVisible: Boolean,
        val manuallyMarkedActive: Boolean,
        val updatedAt: Instant,
    )

    private data class AuditEntry(
        val actorUserId: Long,
        val action: String,
        val payload: String,
    )
}
