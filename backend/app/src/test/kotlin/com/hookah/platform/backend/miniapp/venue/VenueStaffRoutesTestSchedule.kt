package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.staff.StaffScheduleIntervalState
import com.hookah.platform.backend.miniapp.venue.staff.resolveStaffScheduleInterval
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VenueStaffRoutesTestSchedule {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `owner and manager create update cancel with strict CAS and atomic audit`() =
        testApplication {
            val now = Instant.parse("2026-08-01T10:00:00Z")
            val jdbcUrl = buildJdbcUrl("staff-schedule-admin")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(staffScheduleClock = Clock.fixed(now, ZoneOffset.UTC)),
                )
            }
            client.get("/health")

            val ownerId = 8101L
            val managerId = 8102L
            val venueId = seedVenue(jdbcUrl, ownerId, "UTC")
            seedMembership(jdbcUrl, venueId, managerId, "MANAGER")
            val displayOnlyProfileId = seedProfile(jdbcUrl, venueId, ownerId, "Без аккаунта")
            val ownerToken = issueToken(config, ownerId)
            val managerToken = issueToken(config, managerId)

            val createResponse =
                client.post("/api/venue/$venueId/staff/shifts") {
                    bearer(ownerToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueStaffScheduleCreateRequest.serializer(),
                            VenueStaffScheduleCreateRequest(
                                staffProfileId = displayOnlyProfileId,
                                shiftDate = "2026-08-02",
                                startsAt = "22:00",
                                endsAt = "06:00",
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, createResponse.status)
            val created = createResponse.decodeMutation().shift
            assertEquals(displayOnlyProfileId, created.staffProfileId)
            assertEquals("Без аккаунта", created.displayName)
            assertEquals("scheduled", created.computedStatus)
            assertEquals("SCHEDULED", created.cancelConfirmationState)
            assertTrue(created.endsNextDay)
            assertEquals("scheduled", created.storedStatus)
            assertFalse(created.isGuestVisible)
            assertFalse(created.manuallyMarkedActive)

            val listResponse =
                client.get("/api/venue/$venueId/staff/shifts?from=2026-08-01&to=2026-08-07") {
                    bearer(managerToken)
                }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val list = json.decodeFromString(VenueStaffScheduleListResponse.serializer(), listResponse.bodyAsText())
            assertEquals("UTC", list.timezone)
            assertEquals("2026-08-01", list.venueToday)
            assertEquals(listOf(created.id), list.shifts.map { it.id })

            val updateResponse =
                client.put("/api/venue/$venueId/staff/shifts/${created.id}") {
                    bearer(ownerToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueStaffScheduleUpdateRequest.serializer(),
                            VenueStaffScheduleUpdateRequest(
                                shiftDate = "2026-08-03",
                                startsAt = "21:00",
                                endsAt = "05:00",
                                expectedUpdatedAt = created.updatedAt,
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, updateResponse.status)
            val updated = updateResponse.decodeMutation().shift
            assertEquals("2026-08-03", updated.shiftDate)
            assertNotEquals(created.updatedAt, updated.updatedAt)
            assertFalse(updated.isGuestVisible)
            assertFalse(updated.manuallyMarkedActive)

            val staleResponse =
                client.put("/api/venue/$venueId/staff/shifts/${created.id}") {
                    bearer(ownerToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueStaffScheduleUpdateRequest.serializer(),
                            VenueStaffScheduleUpdateRequest(
                                shiftDate = "2026-10-31",
                                startsAt = "20:00",
                                endsAt = "04:00",
                                expectedUpdatedAt = created.updatedAt,
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Conflict, staleResponse.status)
            assertApiErrorEnvelope(staleResponse, ApiErrorCodes.STAFF_SHIFT_STALE)
            assertTrue(staleResponse.bodyAsText().contains("График изменился. Обновите данные и повторите действие."))

            val cancelResponse =
                client.post("/api/venue/$venueId/staff/shifts/${created.id}/cancel") {
                    bearer(ownerToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueStaffScheduleCancelRequest.serializer(),
                            VenueStaffScheduleCancelRequest(
                                expectedUpdatedAt = updated.updatedAt,
                                expectedConfirmationState = "SCHEDULED",
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, cancelResponse.status)
            val canceled = cancelResponse.decodeMutation().shift
            assertEquals("canceled", canceled.computedStatus)
            assertEquals("canceled", canceled.storedStatus)
            assertNull(canceled.cancelConfirmationState)
            assertFalse(canceled.isGuestVisible)

            assertEquals(
                listOf("STAFF_SHIFT_CREATED", "STAFF_SHIFT_UPDATED", "STAFF_SHIFT_CANCELED"),
                auditActions(jdbcUrl, created.id),
            )
            val auditPayload = lastAuditPayload(jdbcUrl, created.id)
            assertEquals(ownerId, lastAuditActorUserId(jdbcUrl, created.id))
            assertFalse(auditPayload.contains("actorUserId"))
            assertTrue(auditPayload.contains("\"venueTimezone\":\"UTC\""))
            assertFalse(auditPayload.contains("telegram", ignoreCase = true))
            assertFalse(auditPayload.contains("username", ignoreCase = true))

            val managerCreated =
                client.post("/api/venue/$venueId/staff/shifts") {
                    bearer(managerToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueStaffScheduleCreateRequest.serializer(),
                            VenueStaffScheduleCreateRequest(
                                displayOnlyProfileId,
                                "2026-08-04",
                                "10:00",
                                "18:00",
                            ),
                        ),
                    )
                }.decodeMutation().shift
            val managerUpdated =
                client.updateShiftRequest(
                    venueId = venueId,
                    shiftId = managerCreated.id,
                    token = managerToken,
                    expectedUpdatedAt = managerCreated.updatedAt,
                    shiftDate = "2026-08-05",
                    startsAt = "11:00",
                    endsAt = "19:00",
                ).decodeMutation().shift
            val managerCanceled =
                client.cancelShiftRequest(
                    venueId = venueId,
                    shiftId = managerCreated.id,
                    token = managerToken,
                    expectedUpdatedAt = managerUpdated.updatedAt,
                    expectedConfirmationState = "SCHEDULED",
                )
            assertEquals(HttpStatusCode.OK, managerCanceled.status)
            assertEquals("canceled", managerCanceled.decodeMutation().shift.computedStatus)
        }

    @Test
    fun `admin schedule returns effective hours and does not enforce them as shift bounds`() =
        testApplication {
            val now = Instant.parse("2026-08-01T21:30:00Z")
            val jdbcUrl = buildJdbcUrl("staff-schedule-effective-hours")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(staffScheduleClock = Clock.fixed(now, ZoneOffset.UTC)),
                )
            }
            client.get("/health")

            val ownerId = 8151L
            val venueId = seedVenue(jdbcUrl, ownerId, "Asia/Tomsk")
            val profileId = seedProfile(jdbcUrl, venueId, ownerId, "Вне часов")
            seedWeeklyHours(jdbcUrl, venueId, weekday = 7, opensAt = "18:00", closesAt = "02:00")
            seedWeeklyHours(jdbcUrl, venueId, weekday = 1, opensAt = "17:00", closesAt = "01:00")
            seedWeeklyHours(jdbcUrl, venueId, weekday = 2, opensAt = "10:00", closesAt = "22:00")
            seedWeeklyHours(jdbcUrl, venueId, weekday = 4, opensAt = "00:00", closesAt = "00:00")
            seedDateOverride(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                serviceDate = LocalDate.parse("2026-08-03"),
                opensAt = "20:00",
                closesAt = "04:00",
            )
            seedDateOverride(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                serviceDate = LocalDate.parse("2026-08-04"),
                opensAt = "00:00",
                closesAt = "00:00",
                isClosed = true,
            )
            val token = issueToken(config, ownerId)

            val outsideHoursCreate =
                client.post("/api/venue/$venueId/staff/shifts") {
                    bearer(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueStaffScheduleCreateRequest.serializer(),
                            VenueStaffScheduleCreateRequest(
                                staffProfileId = profileId,
                                shiftDate = "2026-08-02",
                                startsAt = "10:00",
                                endsAt = "12:00",
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, outsideHoursCreate.status)
            val created = outsideHoursCreate.decodeMutation().shift
            assertEquals("10:00", created.startsAt)
            assertEquals("12:00", created.endsAt)

            val listResponse =
                client.get("/api/venue/$venueId/staff/shifts?from=2026-08-02&to=2026-08-06") {
                    bearer(token)
                }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val payload =
                json.decodeFromString(VenueStaffScheduleListResponse.serializer(), listResponse.bodyAsText())
            assertEquals("Asia/Tomsk", payload.timezone)
            assertEquals("2026-08-02", payload.venueToday)
            assertEquals(listOf(created.id), payload.shifts.map { it.id })
            assertEquals(
                listOf("2026-08-02", "2026-08-03", "2026-08-04", "2026-08-05", "2026-08-06"),
                payload.effectiveHours.map { it.serviceDate },
            )

            val effectiveHours = payload.effectiveHours.associateBy { it.serviceDate }
            val weeklyOvernight = checkNotNull(effectiveHours["2026-08-02"])
            assertEquals(VenueStaffScheduleEffectiveHoursState.OPEN, weeklyOvernight.state)
            assertEquals("18:00", weeklyOvernight.opensAt)
            assertEquals("02:00", weeklyOvernight.closesAt)
            assertTrue(weeklyOvernight.endsNextDay)

            val changedHours = checkNotNull(effectiveHours["2026-08-03"])
            assertEquals(VenueStaffScheduleEffectiveHoursState.OPEN, changedHours.state)
            assertEquals("20:00", changedHours.opensAt)
            assertEquals("04:00", changedHours.closesAt)
            assertTrue(changedHours.endsNextDay)

            val closed = checkNotNull(effectiveHours["2026-08-04"])
            assertEquals(VenueStaffScheduleEffectiveHoursState.CLOSED, closed.state)
            assertNull(closed.opensAt)
            assertNull(closed.closesAt)
            assertFalse(closed.endsNextDay)

            val notConfigured = checkNotNull(effectiveHours["2026-08-05"])
            assertEquals(VenueStaffScheduleEffectiveHoursState.NOT_CONFIGURED, notConfigured.state)
            assertNull(notConfigured.opensAt)
            assertNull(notConfigured.closesAt)
            assertFalse(notConfigured.endsNextDay)

            val fullDay = checkNotNull(effectiveHours["2026-08-06"])
            assertEquals(VenueStaffScheduleEffectiveHoursState.OPEN, fullDay.state)
            assertEquals("00:00", fullDay.opensAt)
            assertEquals("00:00", fullDay.closesAt)
            assertTrue(fullDay.endsNextDay)
        }

    @Test
    fun `staff sees only own overlapping colleagues and has no admin access`() =
        testApplication {
            val now = Instant.parse("2026-08-01T20:00:00Z")
            val jdbcUrl = buildJdbcUrl("staff-schedule-self")
            val platformOnlyId = 8205L
            val config = buildConfig(jdbcUrl, platformOwnerId = platformOnlyId)
            environment { this.config = config }
            application {
                moduleWithOverrides(ModuleOverrides(staffScheduleClock = Clock.fixed(now, ZoneOffset.UTC)))
            }
            client.get("/health")

            val ownerId = 8201L
            val staffId = 8202L
            val venueId = seedVenue(jdbcUrl, ownerId, "UTC")
            seedMembership(jdbcUrl, venueId, staffId, "STAFF")
            val ownProfile = seedProfile(jdbcUrl, venueId, ownerId, "Сотрудник", linkedUserId = staffId)
            val duplicateOwnProfile = seedProfile(jdbcUrl, venueId, ownerId, "Второй профиль", linkedUserId = staffId)
            val displayOnlyProfile = seedProfile(jdbcUrl, venueId, ownerId, "Коллега без аккаунта")
            val unrelatedProfile = seedProfile(jdbcUrl, venueId, ownerId, "Не пересекается")
            val canceledProfile = seedProfile(jdbcUrl, venueId, ownerId, "Отменён")
            val ownShift = seedShift(jdbcUrl, venueId, ownProfile, LocalDate.parse("2026-08-01"), "22:00", "06:00")
            seedShift(jdbcUrl, venueId, duplicateOwnProfile, LocalDate.parse("2026-08-02"), "01:00", "02:00")
            seedShift(jdbcUrl, venueId, displayOnlyProfile, LocalDate.parse("2026-08-02"), "01:00", "05:00")
            seedShift(jdbcUrl, venueId, unrelatedProfile, LocalDate.parse("2026-08-02"), "06:00", "10:00")
            seedShift(
                jdbcUrl,
                venueId,
                canceledProfile,
                LocalDate.parse("2026-08-02"),
                "01:00",
                "04:00",
                status = "canceled",
            )
            val staffToken = issueToken(config, staffId)

            val response =
                client.get("/api/venue/$venueId/staff/shifts/me?from=2026-08-01&to=2026-08-02") {
                    bearer(staffToken)
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val responseBody = response.bodyAsText()
            assertFalse(responseBody.contains("linkedUserId"))
            assertFalse(responseBody.contains("updatedAt"))
            assertFalse(responseBody.contains("isGuestVisible"))
            assertFalse(responseBody.contains("telegram", ignoreCase = true))
            val payload = json.decodeFromString(VenueStaffOwnScheduleResponse.serializer(), responseBody)
            val own = payload.shifts.single { it.id == ownShift }
            assertEquals("scheduled", own.computedStatus)
            assertEquals(listOf(displayOnlyProfile), own.colleagues.map { it.staffProfileId })
            assertEquals("Коллега без аккаунта", own.colleagues.single().displayName)

            val fullScheduleResponse =
                client.get("/api/venue/$venueId/staff/shifts?from=2026-08-01&to=2026-08-02") {
                    bearer(staffToken)
                }
            assertEquals(HttpStatusCode.Forbidden, fullScheduleResponse.status)

            val mutationResponse =
                client.post("/api/venue/$venueId/staff/shifts") {
                    bearer(staffToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueStaffScheduleCreateRequest.serializer(),
                            VenueStaffScheduleCreateRequest(ownProfile, "2026-08-03", "10:00", "18:00"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Forbidden, mutationResponse.status)
            assertTrue(auditActions(jdbcUrl, ownShift).isEmpty())

            val guestId = 8203L
            seedUser(jdbcUrl, guestId)
            val guestResponse =
                client.get("/api/venue/$venueId/staff/shifts/me?from=2026-08-01&to=2026-08-02") {
                    bearer(issueToken(config, guestId))
                }
            assertEquals(HttpStatusCode.Forbidden, guestResponse.status)

            val foreignOwnerId = 8204L
            seedVenue(jdbcUrl, foreignOwnerId, "UTC")
            val foreignResponse =
                client.get("/api/venue/$venueId/staff/shifts?from=2026-08-01&to=2026-08-02") {
                    bearer(issueToken(config, foreignOwnerId))
                }
            assertEquals(HttpStatusCode.Forbidden, foreignResponse.status)

            seedUser(jdbcUrl, platformOnlyId)
            val platformOnlyResponse =
                client.get("/api/venue/$venueId/staff/shifts?from=2026-08-01&to=2026-08-02") {
                    bearer(issueToken(config, platformOnlyId))
                }
            assertEquals(HttpStatusCode.Forbidden, platformOnlyResponse.status)
        }

    @Test
    fun `venue timezone resolves DST gap overlap full day and bounded periods`() =
        testApplication {
            val now = Instant.parse("2027-02-01T12:00:00Z")
            val jdbcUrl = buildJdbcUrl("staff-schedule-time")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application {
                moduleWithOverrides(ModuleOverrides(staffScheduleClock = Clock.fixed(now, ZoneOffset.UTC)))
            }
            client.get("/health")

            val ownerId = 8301L
            val venueId = seedVenue(jdbcUrl, ownerId, "America/New_York")
            val profileId = seedProfile(jdbcUrl, venueId, ownerId, "DST")
            val ownerToken = issueToken(config, ownerId)

            val gapResponse =
                client.post("/api/venue/$venueId/staff/shifts") {
                    bearer(ownerToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueStaffScheduleCreateRequest.serializer(),
                            VenueStaffScheduleCreateRequest(profileId, "2027-03-14", "02:30", "03:30"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, gapResponse.status)
            assertApiErrorEnvelope(gapResponse, ApiErrorCodes.STAFF_SHIFT_INVALID_INTERVAL)

            val normalFullDayResponse =
                client.post("/api/venue/$venueId/staff/shifts") {
                    bearer(ownerToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueStaffScheduleCreateRequest.serializer(),
                            VenueStaffScheduleCreateRequest(profileId, "2027-02-02", "13:00", "13:00"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, normalFullDayResponse.status)
            assertTrue(normalFullDayResponse.decodeMutation().shift.endsNextDay)

            val tooWide =
                client.get("/api/venue/$venueId/staff/shifts?from=2027-02-01&to=2027-03-04") {
                    bearer(ownerToken)
                }
            assertEquals(HttpStatusCode.BadRequest, tooWide.status)
            val reversed =
                client.get("/api/venue/$venueId/staff/shifts?from=2027-02-02&to=2027-02-01") {
                    bearer(ownerToken)
                }
            assertEquals(HttpStatusCode.BadRequest, reversed.status)
            val missing =
                client.get("/api/venue/$venueId/staff/shifts?from=2027-02-01") {
                    bearer(ownerToken)
                }
            assertEquals(HttpStatusCode.BadRequest, missing.status)

            val overlap =
                resolveStaffScheduleInterval(
                    shiftDate = LocalDate.parse("2026-11-01"),
                    startsAt = LocalTime.parse("01:30"),
                    endsAt = LocalTime.parse("01:45"),
                    zoneId = ZoneId.of("America/New_York"),
                )
            assertEquals(StaffScheduleIntervalState.VALID, overlap.state)
            assertEquals(Instant.parse("2026-11-01T05:30:00Z"), overlap.interval?.startsAt)
            val fallFullDay =
                resolveStaffScheduleInterval(
                    shiftDate = LocalDate.parse("2026-11-01"),
                    startsAt = LocalTime.parse("01:30"),
                    endsAt = LocalTime.parse("01:30"),
                    zoneId = ZoneId.of("America/New_York"),
                )
            assertEquals(StaffScheduleIntervalState.INVALID, fallFullDay.state)
        }

    @Test
    fun `lifecycle is recomputed and scheduled confirmation cannot cancel active shift`() =
        testApplication {
            val mutableClock = MutableClock(Instant.parse("2026-08-01T09:00:00Z"))
            val jdbcUrl = buildJdbcUrl("staff-schedule-lifecycle")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { moduleWithOverrides(ModuleOverrides(staffScheduleClock = mutableClock)) }
            client.get("/health")

            val ownerId = 8401L
            val venueId = seedVenue(jdbcUrl, ownerId, "UTC")
            val profileId = seedProfile(jdbcUrl, venueId, ownerId, "Активная смена")
            val token = issueToken(config, ownerId)
            val createdResponse =
                client.post("/api/venue/$venueId/staff/shifts") {
                    bearer(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueStaffScheduleCreateRequest.serializer(),
                            VenueStaffScheduleCreateRequest(profileId, "2026-08-01", "10:00", "12:00"),
                        ),
                    )
                }
            val created = createdResponse.decodeMutation().shift
            assertEquals("scheduled", created.computedStatus)

            mutableClock.current = Instant.parse("2026-08-01T10:30:00Z")
            val updateActive =
                client.put("/api/venue/$venueId/staff/shifts/${created.id}") {
                    bearer(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueStaffScheduleUpdateRequest.serializer(),
                            VenueStaffScheduleUpdateRequest(
                                "2026-08-01",
                                "10:30",
                                "12:30",
                                created.updatedAt,
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Conflict, updateActive.status)
            assertApiErrorEnvelope(updateActive, ApiErrorCodes.STAFF_SHIFT_IMMUTABLE)

            val oldConfirmation =
                client.post("/api/venue/$venueId/staff/shifts/${created.id}/cancel") {
                    bearer(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueStaffScheduleCancelRequest.serializer(),
                            VenueStaffScheduleCancelRequest(created.updatedAt, "SCHEDULED"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Conflict, oldConfirmation.status)
            assertApiErrorEnvelope(oldConfirmation, ApiErrorCodes.STAFF_SHIFT_CONFIRMATION_STALE)

            val activeList =
                client.get("/api/venue/$venueId/staff/shifts?from=2026-08-01&to=2026-08-01") {
                    bearer(token)
                }
            val active =
                json.decodeFromString(
                    VenueStaffScheduleListResponse.serializer(),
                    activeList.bodyAsText(),
                ).shifts.single()
            assertEquals("active", active.computedStatus)
            assertEquals("ACTIVE", active.cancelConfirmationState)

            val activeCancel =
                client.post("/api/venue/$venueId/staff/shifts/${created.id}/cancel") {
                    bearer(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueStaffScheduleCancelRequest.serializer(),
                            VenueStaffScheduleCancelRequest(active.updatedAt, "ACTIVE"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, activeCancel.status)
            assertEquals("canceled", activeCancel.decodeMutation().shift.computedStatus)
        }

    @Test
    fun `horizon completed stale cancel and Today overlay fail closed without false audit`() =
        testApplication {
            val now = Instant.parse("2026-08-01T10:00:00Z")
            val jdbcUrl = buildJdbcUrl("staff-schedule-guards")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application {
                moduleWithOverrides(ModuleOverrides(staffScheduleClock = Clock.fixed(now, ZoneOffset.UTC)))
            }
            client.get("/health")

            val ownerId = 8451L
            val venueId = seedVenue(jdbcUrl, ownerId, "UTC")
            val token = issueToken(config, ownerId)
            val createProfile = seedProfile(jdbcUrl, venueId, ownerId, "Горизонт")

            val pastCreate =
                client.createShiftRequest(venueId, token, createProfile, "2026-07-31", "12:00", "20:00")
            assertEquals(HttpStatusCode.BadRequest, pastCreate.status)
            val overHorizon =
                client.createShiftRequest(venueId, token, createProfile, "2026-10-31", "12:00", "20:00")
            assertEquals(HttpStatusCode.BadRequest, overHorizon.status)

            val completedProfile = seedProfile(jdbcUrl, venueId, ownerId, "Завершённая")
            val completedId =
                seedShift(
                    jdbcUrl,
                    venueId,
                    completedProfile,
                    LocalDate.parse("2026-08-01"),
                    "08:00",
                    "09:00",
                )
            val overlayProfile = seedProfile(jdbcUrl, venueId, ownerId, "Today overlay")
            val overlayId =
                seedShift(
                    jdbcUrl,
                    venueId,
                    overlayProfile,
                    LocalDate.parse("2026-08-02"),
                    "10:00",
                    "18:00",
                    isGuestVisible = true,
                )
            val list =
                client.get("/api/venue/$venueId/staff/shifts?from=2026-08-01&to=2026-08-02") {
                    bearer(token)
                }.let {
                    json.decodeFromString(VenueStaffScheduleListResponse.serializer(), it.bodyAsText())
                }
            val completed = list.shifts.single { it.id == completedId }
            val overlay = list.shifts.single { it.id == overlayId }
            assertEquals("completed", completed.computedStatus)
            assertNull(completed.cancelConfirmationState)
            assertTrue(overlay.isGuestVisible)

            val completedUpdate =
                client.updateShiftRequest(
                    venueId,
                    completed.id,
                    token,
                    completed.updatedAt,
                    "2026-08-02",
                    "08:00",
                    "09:00",
                )
            assertEquals(HttpStatusCode.Conflict, completedUpdate.status)
            assertApiErrorEnvelope(completedUpdate, ApiErrorCodes.STAFF_SHIFT_IMMUTABLE)
            val completedCancel =
                client.cancelShiftRequest(venueId, completed.id, token, completed.updatedAt, "SCHEDULED")
            assertEquals(HttpStatusCode.Conflict, completedCancel.status)
            assertApiErrorEnvelope(completedCancel, ApiErrorCodes.STAFF_SHIFT_IMMUTABLE)

            val overlayUpdate =
                client.updateShiftRequest(
                    venueId,
                    overlay.id,
                    token,
                    overlay.updatedAt,
                    "2026-08-03",
                    "11:00",
                    "19:00",
                )
            assertEquals(HttpStatusCode.Conflict, overlayUpdate.status)
            assertApiErrorEnvelope(overlayUpdate, ApiErrorCodes.STAFF_SHIFT_TODAY_OVERRIDE)
            val overlayCancel =
                client.cancelShiftRequest(venueId, overlay.id, token, overlay.updatedAt, "SCHEDULED")
            assertEquals(HttpStatusCode.OK, overlayCancel.status)
            val canceledOverlay = overlayCancel.decodeMutation().shift
            assertEquals("canceled", canceledOverlay.storedStatus)
            assertTrue(canceledOverlay.isGuestVisible)
            assertEquals(Triple("10:00", "18:00", true), shiftState(jdbcUrl, overlay.id))
            assertEquals(listOf("STAFF_SHIFT_CANCELED"), auditActions(jdbcUrl, overlay.id))
            assertTrue(auditActions(jdbcUrl, completed.id).isEmpty())

            val staleProfile = seedProfile(jdbcUrl, venueId, ownerId, "Stale cancel")
            val staleCreated =
                client.createShiftRequest(venueId, token, staleProfile, "2026-08-03", "10:00", "18:00")
                    .decodeMutation()
                    .shift
            val staleUpdated =
                client.updateShiftRequest(
                    venueId,
                    staleCreated.id,
                    token,
                    staleCreated.updatedAt,
                    "2026-08-03",
                    "11:00",
                    "19:00",
                ).decodeMutation().shift
            val staleCancel =
                client.cancelShiftRequest(
                    venueId,
                    staleCreated.id,
                    token,
                    staleCreated.updatedAt,
                    "SCHEDULED",
                )
            assertEquals(HttpStatusCode.Conflict, staleCancel.status)
            assertApiErrorEnvelope(staleCancel, ApiErrorCodes.STAFF_SHIFT_STALE)
            assertEquals(staleUpdated.updatedAt, currentUpdatedAt(jdbcUrl, staleCreated.id).toString())
            assertEquals(listOf("STAFF_SHIFT_CREATED", "STAFF_SHIFT_UPDATED"), auditActions(jdbcUrl, staleCreated.id))

            val fallbackOwner = 8452L
            val fallbackVenue = seedVenue(jdbcUrl, fallbackOwner, timezone = null)
            val fallbackResponse =
                client.get("/api/venue/$fallbackVenue/staff/shifts?from=2026-08-01&to=2026-08-01") {
                    bearer(issueToken(config, fallbackOwner))
                }
            val fallbackPayload =
                json.decodeFromString(VenueStaffScheduleListResponse.serializer(), fallbackResponse.bodyAsText())
            assertEquals("Europe/Moscow", fallbackPayload.timezone)
            assertEquals("2026-08-01", fallbackPayload.venueToday)
        }

    @Test
    fun `invalid legacy intervals are repairable only while future and remain hidden from staff`() =
        testApplication {
            val mutableClock = MutableClock(Instant.parse("2027-02-01T12:00:00Z"))
            val jdbcUrl = buildJdbcUrl("staff-schedule-invalid-legacy")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { moduleWithOverrides(ModuleOverrides(staffScheduleClock = mutableClock)) }
            client.get("/health")

            val ownerId = 8471L
            val staffId = 8472L
            val venueId = seedVenue(jdbcUrl, ownerId, "America/New_York")
            seedMembership(jdbcUrl, venueId, staffId, "STAFF")
            val futureRepairProfile =
                seedProfile(jdbcUrl, venueId, ownerId, "Future repair", linkedUserId = staffId)
            val futureCancelProfile = seedProfile(jdbcUrl, venueId, ownerId, "Future cancel")
            val todayProfile = seedProfile(jdbcUrl, venueId, ownerId, "Today invalid")
            val pastProfile = seedProfile(jdbcUrl, venueId, ownerId, "Past invalid")
            val invalidDate = LocalDate.parse("2027-03-14")
            val futureRepairId = seedShift(jdbcUrl, venueId, futureRepairProfile, invalidDate, "02:30", "03:30")
            val futureCancelId = seedShift(jdbcUrl, venueId, futureCancelProfile, invalidDate, "02:30", "03:30")
            val todayInvalidId = seedShift(jdbcUrl, venueId, todayProfile, invalidDate, "02:30", "03:30")
            val pastInvalidId = seedShift(jdbcUrl, venueId, pastProfile, invalidDate, "02:30", "03:30")
            val ownerToken = issueToken(config, ownerId)
            val staffToken = issueToken(config, staffId)

            val futureList =
                client.get("/api/venue/$venueId/staff/shifts?from=2027-03-14&to=2027-03-14") {
                    bearer(ownerToken)
                }.let {
                    assertEquals(HttpStatusCode.OK, it.status)
                    json.decodeFromString(VenueStaffScheduleListResponse.serializer(), it.bodyAsText())
                }
            val repairCandidate = futureList.shifts.single { it.id == futureRepairId }
            val cancelCandidate = futureList.shifts.single { it.id == futureCancelId }
            assertEquals(setOf("UPDATE", "CANCEL"), repairCandidate.warning?.allowedActions?.toSet())
            assertEquals("INVALID_INTERVAL", repairCandidate.cancelConfirmationState)

            val hiddenFromStaff =
                client.get("/api/venue/$venueId/staff/shifts/me?from=2027-03-14&to=2027-03-14") {
                    bearer(staffToken)
                }
            assertEquals(HttpStatusCode.OK, hiddenFromStaff.status)
            assertTrue(hiddenFromStaff.bodyAsText().contains("\"shifts\":[]"))

            val repaired =
                client.updateShiftRequest(
                    venueId = venueId,
                    shiftId = futureRepairId,
                    token = ownerToken,
                    expectedUpdatedAt = repairCandidate.updatedAt,
                    shiftDate = "2027-03-14",
                    startsAt = "03:30",
                    endsAt = "04:30",
                ).decodeMutation().shift
            assertNull(repaired.warning)
            assertEquals("scheduled", repaired.computedStatus)
            assertEquals(listOf("STAFF_SHIFT_UPDATED"), auditActions(jdbcUrl, futureRepairId))

            val futureCanceled =
                client.cancelShiftRequest(
                    venueId = venueId,
                    shiftId = futureCancelId,
                    token = ownerToken,
                    expectedUpdatedAt = cancelCandidate.updatedAt,
                    expectedConfirmationState = "INVALID_INTERVAL",
                )
            assertEquals(HttpStatusCode.OK, futureCanceled.status)
            val canceledInvalid = futureCanceled.decodeMutation().shift
            assertEquals("canceled", canceledInvalid.computedStatus)
            assertTrue(canceledInvalid.warning?.allowedActions?.isEmpty() == true)
            assertEquals(listOf("STAFF_SHIFT_CANCELED"), auditActions(jdbcUrl, futureCancelId))

            mutableClock.current = Instant.parse("2027-03-14T16:00:00Z")
            val todayList =
                client.get("/api/venue/$venueId/staff/shifts?from=2027-03-14&to=2027-03-14") {
                    bearer(ownerToken)
                }.let {
                    json.decodeFromString(VenueStaffScheduleListResponse.serializer(), it.bodyAsText())
                }
            val todayInvalid = todayList.shifts.single { it.id == todayInvalidId }
            assertEquals(setOf("CANCEL"), todayInvalid.warning?.allowedActions?.toSet())
            assertEquals("INVALID_INTERVAL", todayInvalid.cancelConfirmationState)
            val todayRepair =
                client.updateShiftRequest(
                    venueId,
                    todayInvalidId,
                    ownerToken,
                    todayInvalid.updatedAt,
                    "2027-03-15",
                    "10:00",
                    "18:00",
                )
            assertEquals(HttpStatusCode.Conflict, todayRepair.status)
            assertApiErrorEnvelope(todayRepair, ApiErrorCodes.STAFF_SHIFT_IMMUTABLE)
            val todayCancel =
                client.cancelShiftRequest(
                    venueId,
                    todayInvalidId,
                    ownerToken,
                    todayInvalid.updatedAt,
                    "INVALID_INTERVAL",
                )
            assertEquals(HttpStatusCode.OK, todayCancel.status)
            assertEquals(listOf("STAFF_SHIFT_CANCELED"), auditActions(jdbcUrl, todayInvalidId))

            mutableClock.current = Instant.parse("2027-03-20T16:00:00Z")
            val pastList =
                client.get("/api/venue/$venueId/staff/shifts?from=2027-03-14&to=2027-03-14") {
                    bearer(ownerToken)
                }.let {
                    json.decodeFromString(VenueStaffScheduleListResponse.serializer(), it.bodyAsText())
                }
            val pastInvalid = pastList.shifts.single { it.id == pastInvalidId }
            assertTrue(pastInvalid.warning?.allowedActions?.isEmpty() == true)
            assertNull(pastInvalid.cancelConfirmationState)
            val pastRepair =
                client.updateShiftRequest(
                    venueId,
                    pastInvalidId,
                    ownerToken,
                    pastInvalid.updatedAt,
                    "2027-03-21",
                    "10:00",
                    "18:00",
                )
            assertEquals(HttpStatusCode.Conflict, pastRepair.status)
            assertApiErrorEnvelope(pastRepair, ApiErrorCodes.STAFF_SHIFT_IMMUTABLE)
            val pastCancel =
                client.cancelShiftRequest(
                    venueId,
                    pastInvalidId,
                    ownerToken,
                    pastInvalid.updatedAt,
                    "INVALID_INTERVAL",
                )
            assertEquals(HttpStatusCode.Conflict, pastCancel.status)
            assertApiErrorEnvelope(pastCancel, ApiErrorCodes.STAFF_SHIFT_IMMUTABLE)
            assertTrue(auditActions(jdbcUrl, pastInvalidId).isEmpty())
        }

    @Test
    fun `duplicate no-op foreign id and concurrent CAS are fail safe`() =
        testApplication {
            val now = Instant.parse("2026-08-01T10:00:00Z")
            val jdbcUrl = buildJdbcUrl("staff-schedule-cas")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application {
                moduleWithOverrides(ModuleOverrides(staffScheduleClock = Clock.fixed(now, ZoneOffset.UTC)))
            }
            client.get("/health")

            val ownerId = 8501L
            val foreignOwnerId = 8502L
            val venueId = seedVenue(jdbcUrl, ownerId, "UTC")
            val foreignVenueId = seedVenue(jdbcUrl, foreignOwnerId, "UTC")
            val profileId = seedProfile(jdbcUrl, venueId, ownerId, "CAS")
            val foreignProfileId = seedProfile(jdbcUrl, foreignVenueId, foreignOwnerId, "Чужой")
            val foreignShiftId =
                seedShift(
                    jdbcUrl,
                    foreignVenueId,
                    foreignProfileId,
                    LocalDate.parse("2026-08-02"),
                    "10:00",
                    "18:00",
                )
            val token = issueToken(config, ownerId)
            val created =
                client.post("/api/venue/$venueId/staff/shifts") {
                    bearer(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueStaffScheduleCreateRequest.serializer(),
                            VenueStaffScheduleCreateRequest(profileId, "2026-08-02", "11:00", "19:00"),
                        ),
                    )
                }.decodeMutation().shift

            val duplicate =
                client.post("/api/venue/$venueId/staff/shifts") {
                    bearer(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueStaffScheduleCreateRequest.serializer(),
                            VenueStaffScheduleCreateRequest(profileId, "2026-08-02", "12:00", "20:00"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Conflict, duplicate.status)
            assertApiErrorEnvelope(duplicate, ApiErrorCodes.STAFF_SHIFT_DATE_CONFLICT)

            val noOp =
                client.put("/api/venue/$venueId/staff/shifts/${created.id}") {
                    bearer(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueStaffScheduleUpdateRequest.serializer(),
                            VenueStaffScheduleUpdateRequest(
                                created.shiftDate,
                                created.startsAt,
                                created.endsAt,
                                created.updatedAt,
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, noOp.status)
            assertEquals(created.updatedAt, noOp.decodeMutation().shift.updatedAt)
            assertEquals(listOf("STAFF_SHIFT_CREATED"), auditActions(jdbcUrl, created.id))

            val concurrentResponses =
                coroutineScope {
                    listOf(
                        async {
                            client.updateShift(venueId, created.id, token, created.updatedAt, "12:00", "20:00")
                        },
                        async {
                            client.updateShift(venueId, created.id, token, created.updatedAt, "13:00", "21:00")
                        },
                    ).awaitAll()
                }
            assertEquals(1, concurrentResponses.count { it.status == HttpStatusCode.OK })
            assertEquals(1, concurrentResponses.count { it.status == HttpStatusCode.Conflict })
            assertEquals(2, auditActions(jdbcUrl, created.id).size)

            val foreignIdResponse =
                client.put("/api/venue/$venueId/staff/shifts/$foreignShiftId") {
                    bearer(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueStaffScheduleUpdateRequest.serializer(),
                            VenueStaffScheduleUpdateRequest(
                                "2026-08-03",
                                "10:00",
                                "18:00",
                                Instant.parse("2026-08-01T00:00:00Z").toString(),
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.NotFound, foreignIdResponse.status)
        }

    private suspend fun io.ktor.client.HttpClient.createShiftRequest(
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

    private suspend fun io.ktor.client.HttpClient.updateShiftRequest(
        venueId: Long,
        shiftId: Long,
        token: String,
        expectedUpdatedAt: String,
        shiftDate: String,
        startsAt: String,
        endsAt: String,
    ): HttpResponse =
        put("/api/venue/$venueId/staff/shifts/$shiftId") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    VenueStaffScheduleUpdateRequest.serializer(),
                    VenueStaffScheduleUpdateRequest(shiftDate, startsAt, endsAt, expectedUpdatedAt),
                ),
            )
        }

    private suspend fun io.ktor.client.HttpClient.updateShift(
        venueId: Long,
        shiftId: Long,
        token: String,
        expectedUpdatedAt: String,
        startsAt: String,
        endsAt: String,
    ): HttpResponse =
        updateShiftRequest(
            venueId = venueId,
            shiftId = shiftId,
            token = token,
            expectedUpdatedAt = expectedUpdatedAt,
            shiftDate = "2026-08-02",
            startsAt = startsAt,
            endsAt = endsAt,
        )

    private suspend fun io.ktor.client.HttpClient.cancelShiftRequest(
        venueId: Long,
        shiftId: Long,
        token: String,
        expectedUpdatedAt: String,
        expectedConfirmationState: String,
    ): HttpResponse =
        post("/api/venue/$venueId/staff/shifts/$shiftId/cancel") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    VenueStaffScheduleCancelRequest.serializer(),
                    VenueStaffScheduleCancelRequest(expectedUpdatedAt, expectedConfirmationState),
                ),
            )
        }

    private suspend fun HttpResponse.decodeMutation(): VenueStaffScheduleMutationResponse =
        json.decodeFromString(VenueStaffScheduleMutationResponse.serializer(), bodyAsText())

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
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

    private fun seedVenue(
        jdbcUrl: String,
        ownerId: Long,
        timezone: String?,
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
            if (timezone != null) {
                connection.prepareStatement(
                    "INSERT INTO venue_settings (venue_id, timezone) VALUES (?, ?)",
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setString(2, timezone)
                    statement.executeUpdate()
                }
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
            VALUES (?, 'user', 'Test', 'User')
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
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO staff_profiles (
                    venue_id, linked_user_id, display_name, role_label, subtype,
                    is_guest_visible, created_by_user_id, updated_by_user_id
                )
                VALUES (?, ?, ?, 'Сотрудник', 'other', FALSE, ?, ?)
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
                statement.setLong(4, actorUserId)
                statement.setLong(5, actorUserId)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    check(rs.next())
                    rs.getLong(1)
                }
            }
        }

    private fun seedWeeklyHours(
        jdbcUrl: String,
        venueId: Long,
        weekday: Int,
        opensAt: String,
        closesAt: String,
        isClosed: Boolean = false,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_booking_hours (venue_id, weekday, opens_at, closes_at, is_closed)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setInt(2, weekday)
                statement.setObject(3, LocalTime.parse(opensAt))
                statement.setObject(4, LocalTime.parse(closesAt))
                statement.setBoolean(5, isClosed)
                statement.executeUpdate()
            }
        }
    }

    private fun seedDateOverride(
        jdbcUrl: String,
        venueId: Long,
        serviceDate: LocalDate,
        opensAt: String,
        closesAt: String,
        isClosed: Boolean = false,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_booking_hours_overrides (
                    venue_id, service_date, opens_at, closes_at, is_closed
                )
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setObject(2, serviceDate)
                statement.setObject(3, LocalTime.parse(opensAt))
                statement.setObject(4, LocalTime.parse(closesAt))
                statement.setBoolean(5, isClosed)
                statement.executeUpdate()
            }
        }
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
        updatedAt: Instant = Instant.parse("2026-08-01T00:00:00Z"),
    ): Long =
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
        }

    private fun auditActions(
        jdbcUrl: String,
        shiftId: Long,
    ): List<String> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT action FROM audit_log WHERE entity_type = 'staff_shift' AND entity_id = ? ORDER BY id",
            ).use { statement ->
                statement.setLong(1, shiftId)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getString(1))
                    }
                }
            }
        }

    private fun shiftState(
        jdbcUrl: String,
        shiftId: Long,
    ): Triple<String, String, Boolean> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT starts_at, ends_at, is_guest_visible FROM staff_shifts WHERE id = ?",
            ).use { statement ->
                statement.setLong(1, shiftId)
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    Triple(
                        rs.getObject(1, LocalTime::class.java).toString(),
                        rs.getObject(2, LocalTime::class.java).toString(),
                        rs.getBoolean(3),
                    )
                }
            }
        }

    private fun currentUpdatedAt(
        jdbcUrl: String,
        shiftId: Long,
    ): Instant =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT updated_at FROM staff_shifts WHERE id = ?").use { statement ->
                statement.setLong(1, shiftId)
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getTimestamp(1).toInstant()
                }
            }
        }

    private fun lastAuditPayload(
        jdbcUrl: String,
        shiftId: Long,
    ): String =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM audit_log
                WHERE entity_type = 'staff_shift' AND entity_id = ?
                ORDER BY id DESC
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, shiftId)
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getString(1)
                }
            }
        }

    private fun lastAuditActorUserId(
        jdbcUrl: String,
        shiftId: Long,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT actor_user_id
                FROM audit_log
                WHERE entity_type = 'staff_shift' AND entity_id = ?
                ORDER BY id DESC
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, shiftId)
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getLong(1)
                }
            }
        }

    private class MutableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current
    }
}
