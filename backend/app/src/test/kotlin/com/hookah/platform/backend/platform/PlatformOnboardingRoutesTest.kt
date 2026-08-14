package com.hookah.platform.backend.platform

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestRepository
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import com.hookah.platform.backend.test.migrateH2OnboardingFixture
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.h2.jdbcx.JdbcDataSource
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Types
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlatformOnboardingRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `platform owner lists filters and opens requests through production registration with exact safe dto`() =
        testApplication {
            val fixture = fixture("platform-onboarding-list-detail")
            environment { config = fixture.config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(fixture.jdbcUrl, PLATFORM_OWNER_ID, "platform_owner", "Петр", "Платформа")
            seedUser(
                fixture.jdbcUrl,
                APPLICANT_ID,
                "safe_applicant",
                "Анна",
                "Владелец",
                privateGuestName = "PRIVATE-GUEST-NAME",
            )
            val pendingId =
                seedRequest(
                    jdbcUrl = fixture.jdbcUrl,
                    applicantUserId = APPLICANT_ID,
                    venueName = "Первая заявка",
                    city = "Москва",
                    contact = "+7 900 111-22-33",
                    comment = "PRIVATE-CONTACT-COMMENT",
                    status = "PENDING",
                )
            val linkedVenueId =
                seedVenue(
                    fixture.jdbcUrl,
                    "Связанное заведение",
                    "Казань",
                    "DRAFT",
                )
            val linkedId =
                seedRequest(
                    jdbcUrl = fixture.jdbcUrl,
                    applicantUserId = APPLICANT_ID,
                    venueName = "Полная заявка",
                    city = "Казань",
                    contact = "@safe_contact",
                    comment = "Полный комментарий",
                    status = "APPROVED",
                    linkedVenueId = linkedVenueId,
                    trialConfigured = true,
                    trialEndsOn = LocalDate.parse("2026-09-30"),
                    currentPriceRub = 2_500,
                    futurePriceRub = 3_000,
                    futurePriceEffectiveOn = LocalDate.parse("2027-01-01"),
                    commercialNote = "Условия платформы",
                )
            val platformToken = issueToken(fixture.config, PLATFORM_OWNER_ID)

            val listResponse =
                client.get("/api/platform/onboarding/requests?status=PENDING&q=%D0%BF%D0%B5%D1%80%D0%B2%D0%B0%D1%8F") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val listBody = listResponse.bodyAsText()
            val list = json.decodeFromString(PlatformOnboardingRequestListResponse.serializer(), listBody)
            assertEquals(listOf(pendingId), list.requests.map { it.id })
            assertEquals(APPLICANT_ID, list.requests.single().applicant.userId)
            assertEquals("+7 900 111-22-33", list.requests.single().contact)
            assertEquals(setOf("requests"), json.parseToJsonElement(listBody).jsonObject.keys)

            val detailResponse =
                client.get("/api/platform/onboarding/requests/$linkedId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }
            assertEquals(HttpStatusCode.OK, detailResponse.status)
            val detailBody = detailResponse.bodyAsText()
            val detail = json.decodeFromString(PlatformOnboardingRequestResponse.serializer(), detailBody)
            assertEquals(linkedVenueId, detail.request.linkedVenueId)
            assertEquals("safe_applicant", detail.request.applicant.username)
            assertEquals("Условия платформы", detail.request.commercialNote)

            val root = json.parseToJsonElement(detailBody).jsonObject
            assertEquals(setOf("request"), root.keys)
            val requestObject = root.getValue("request").jsonObject
            assertEquals(REQUEST_DTO_KEYS, requestObject.keys)
            assertEquals(APPLICANT_DTO_KEYS, requestObject.getValue("applicant").jsonObject.keys)
            assertFalse(detailBody.contains("PRIVATE-GUEST-NAME"))
            assertFalse(detailBody.contains("guestDisplayName"))
            assertFalse(detailBody.contains("birthday"))
            assertFalse(detailBody.contains("initData"))
            assertFalse(detailBody.contains("phone"))
            assertFalse(detailBody.contains("rawTelegram"))
        }

    @Test
    fun `platform decisions ignore spoof targets and write one truthful audit each`() =
        testApplication {
            val fixture = fixture("platform-onboarding-decisions")
            environment { config = fixture.config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(fixture.jdbcUrl, PLATFORM_OWNER_ID, "platform", "Петр", "Платформа")
            seedUser(fixture.jdbcUrl, APPLICANT_ID, "applicant", "Анна", "Владелец")
            seedUser(fixture.jdbcUrl, SPOOF_USER_ID, "spoof", "Чужой", "Владелец")
            val approveId = seedRequest(fixture.jdbcUrl, APPLICANT_ID, "Approve", "Москва", "@a", null)
            val rejectId = seedRequest(fixture.jdbcUrl, APPLICANT_ID, "Reject", "Тула", "@b", null)
            val closeId =
                seedRequest(
                    fixture.jdbcUrl,
                    APPLICANT_ID,
                    "Close",
                    "Сочи",
                    "@c",
                    null,
                    status = "APPROVED",
                )
            val spoofRequestId = seedRequest(fixture.jdbcUrl, SPOOF_USER_ID, "Spoof", "Омск", "@spoof", null)
            val spoofVenueId = seedVenue(fixture.jdbcUrl, "Spoof target", "Омск", "DRAFT")
            val token = issueToken(fixture.config, PLATFORM_OWNER_ID)
            val spoofBody =
                """
                {
                  "actorUserId": $SPOOF_USER_ID,
                  "applicantUserId": $SPOOF_USER_ID,
                  "ownerUserId": $SPOOF_USER_ID,
                  "requestId": $spoofRequestId,
                  "linkedVenueId": $spoofVenueId,
                  "source": "TELEGRAM"
                }
                """.trimIndent()

            val approve =
                client.post("/api/platform/onboarding/requests/$approveId/approve") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(spoofBody)
                }
            val reject =
                client.post("/api/platform/onboarding/requests/$rejectId/reject") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(spoofBody)
                }
            val close =
                client.post("/api/platform/onboarding/requests/$closeId/close") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(spoofBody)
                }

            assertEquals(HttpStatusCode.OK, approve.status)
            assertEquals(HttpStatusCode.OK, reject.status)
            assertEquals(HttpStatusCode.OK, close.status)
            val approved = decodeRequest(approve.bodyAsText())
            val rejected = decodeRequest(reject.bodyAsText())
            val closed = decodeRequest(close.bodyAsText())
            assertEquals(approveId, approved.id)
            assertEquals(rejectId, rejected.id)
            assertEquals(closeId, closed.id)
            assertEquals("APPROVED", approved.status)
            assertEquals("REJECTED", rejected.status)
            assertEquals("CANCELLED", closed.status)
            listOf(approved, rejected, closed).forEach { request ->
                assertEquals(APPLICANT_ID, request.applicant.userId)
                assertEquals(null, request.linkedVenueId)
            }
            assertEquals("PENDING", requestStatus(fixture.jdbcUrl, spoofRequestId))
            assertEquals(0, auditRows(fixture.jdbcUrl, spoofRequestId).size)
            assertEquals(0, membershipCount(fixture.jdbcUrl, SPOOF_USER_ID))
            assertEquals(
                listOf(VenueConnectionRequestRepository.AUDIT_APPROVED),
                auditRows(fixture.jdbcUrl, approveId).map { it.action },
            )
            assertEquals(
                listOf(VenueConnectionRequestRepository.AUDIT_REJECTED),
                auditRows(fixture.jdbcUrl, rejectId).map { it.action },
            )
            assertEquals(
                listOf(VenueConnectionRequestRepository.AUDIT_CANCELLED),
                auditRows(fixture.jdbcUrl, closeId).map { it.action },
            )
            listOf(approveId, rejectId, closeId).forEach { requestId ->
                val audit = auditRows(fixture.jdbcUrl, requestId).single()
                assertEquals(PLATFORM_OWNER_ID, audit.actorUserId)
                assertEquals("PLATFORM_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
                assertEquals(setOf("requestId", "status", "source"), audit.payload.keys)
            }
        }

    @Test
    fun `commercial terms route validates and persists only path request with server actor`() =
        testApplication {
            val fixture = fixture("platform-onboarding-terms")
            environment { config = fixture.config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(fixture.jdbcUrl, PLATFORM_OWNER_ID, "platform", "Петр", "Платформа")
            seedUser(fixture.jdbcUrl, APPLICANT_ID, "applicant", "Анна", "Владелец")
            seedUser(fixture.jdbcUrl, SPOOF_USER_ID, "spoof", "Чужой", "Пользователь")
            val requestId =
                seedRequest(
                    fixture.jdbcUrl,
                    APPLICANT_ID,
                    "Terms",
                    "Москва",
                    "@terms",
                    null,
                    status = "APPROVED",
                )
            val otherRequestId =
                seedRequest(
                    fixture.jdbcUrl,
                    SPOOF_USER_ID,
                    "Other",
                    "Омск",
                    "@other",
                    null,
                    status = "APPROVED",
                )
            val token = issueToken(fixture.config, PLATFORM_OWNER_ID)

            val invalid =
                client.put("/api/platform/onboarding/requests/$requestId/commercial-terms") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"trialConfigured":true,"currentPriceRub":2500,"futurePriceRub":3000}""",
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertApiErrorEnvelope(invalid, ApiErrorCodes.INVALID_INPUT)
            assertEquals(0, auditRows(fixture.jdbcUrl, requestId).size)

            val response =
                client.put("/api/platform/onboarding/requests/$requestId/commercial-terms") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "trialConfigured": true,
                          "trialEndsOn": "2026-10-15",
                          "currentPriceRub": 2500,
                          "futurePriceRub": 3000,
                          "futurePriceEffectiveOn": "2027-01-01",
                          "commercialNote": "  Индивидуальные условия  ",
                          "actorUserId": $SPOOF_USER_ID,
                          "applicantUserId": $SPOOF_USER_ID,
                          "requestId": $otherRequestId,
                          "linkedVenueId": 999999
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val updated = decodeRequest(response.bodyAsText())
            assertEquals(requestId, updated.id)
            assertEquals(APPLICANT_ID, updated.applicant.userId)
            assertEquals(null, updated.linkedVenueId)
            assertEquals("2026-10-15", updated.trialEndsOn)
            assertEquals(2_500L, updated.currentPriceRub)
            assertEquals(3_000L, updated.futurePriceRub)
            assertEquals("Индивидуальные условия", updated.commercialNote)
            assertEquals(null, requestCurrentPrice(fixture.jdbcUrl, otherRequestId))

            val audit = auditRows(fixture.jdbcUrl, requestId).single()
            assertEquals(VenueConnectionRequestRepository.AUDIT_TERMS_UPDATED, audit.action)
            assertEquals(PLATFORM_OWNER_ID, audit.actorUserId)
            assertEquals(setOf("requestId", "status", "source"), audit.payload.keys)
            assertFalse(audit.payload.toString().contains(SPOOF_USER_ID.toString()))
            assertFalse(audit.payload.toString().contains("linkedVenueId"))
        }

    @Test
    fun `create link for first applicant ignores spoof targets and retry returns one authoritative owner`() =
        testApplication {
            val fixture = fixture("platform-onboarding-first-applicant-link")
            environment { config = fixture.config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(fixture.jdbcUrl, PLATFORM_OWNER_ID, "platform", "Петр", "Платформа")
            seedUser(fixture.jdbcUrl, APPLICANT_ID, "first_applicant", "Анна", "Первый владелец")
            seedUser(fixture.jdbcUrl, SPOOF_USER_ID, "spoof_owner", "Чужой", "Владелец")
            val spoofVenueId = seedVenue(fixture.jdbcUrl, "Чужая цель", "Омск", "DRAFT")
            val requestId =
                seedRequest(
                    fixture.jdbcUrl,
                    APPLICANT_ID,
                    "Первое заведение",
                    "Сочи",
                    "+7 900 000-00-00",
                    "Первая заявка",
                    status = "APPROVED",
                    trialConfigured = true,
                    currentPriceRub = 0,
                    commercialNote = "baseline first venue",
                )
            val token = issueToken(fixture.config, PLATFORM_OWNER_ID)

            assertEquals(0, membershipCount(fixture.jdbcUrl, APPLICANT_ID))
            assertEquals(0, ownerAccountCount(fixture.jdbcUrl, APPLICANT_ID))

            val firstResponse =
                client.post("/api/platform/onboarding/requests/$requestId/create-and-link") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "actorUserId": $SPOOF_USER_ID,
                          "applicantUserId": $SPOOF_USER_ID,
                          "ownerUserId": $SPOOF_USER_ID,
                          "requestId": 999999,
                          "linkedVenueId": $spoofVenueId
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, firstResponse.status)
            val first =
                json.decodeFromString(
                    PlatformOnboardingCreateLinkResponse.serializer(),
                    firstResponse.bodyAsText(),
                )
            assertTrue(first.created)
            assertNotEquals(spoofVenueId, first.venueId)
            assertEquals(first.venueId, first.request.linkedVenueId)
            assertEquals(APPLICANT_ID, first.request.applicant.userId)
            assertEquals("DRAFT", venueStatus(fixture.jdbcUrl, first.venueId))
            assertEquals("Сочи", venueCity(fixture.jdbcUrl, first.venueId))
            assertEquals(1, membershipCount(fixture.jdbcUrl, APPLICANT_ID, first.venueId, "OWNER"))
            assertEquals(0, membershipCount(fixture.jdbcUrl, SPOOF_USER_ID))
            assertEquals(0, membershipCount(fixture.jdbcUrl, APPLICANT_ID, spoofVenueId, "OWNER"))
            assertEquals(1, ownerAccountCount(fixture.jdbcUrl, APPLICANT_ID))
            assertEquals(1, ownerAccountLimit(fixture.jdbcUrl, APPLICANT_ID))
            assertEquals(1, rowCountForVenue(fixture.jdbcUrl, "venue_settings", first.venueId))
            assertEquals(1, rowCountForVenue(fixture.jdbcUrl, "venue_subscription_settings", first.venueId))
            assertEquals(1, rowCountForVenue(fixture.jdbcUrl, "venue_subscriptions", first.venueId))
            assertEquals(0, rowCountForVenue(fixture.jdbcUrl, "menu_categories", first.venueId))

            val retryResponse =
                client.post("/api/platform/onboarding/requests/$requestId/create-and-link") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, retryResponse.status)
            val retry =
                json.decodeFromString(
                    PlatformOnboardingCreateLinkResponse.serializer(),
                    retryResponse.bodyAsText(),
                )
            assertFalse(retry.created)
            assertEquals(first.venueId, retry.venueId)
            assertEquals(1, membershipCount(fixture.jdbcUrl, APPLICANT_ID, first.venueId, "OWNER"))
            assertEquals(2, venueCount(fixture.jdbcUrl))

            val audits = auditRows(fixture.jdbcUrl, requestId)
            assertEquals(
                listOf(
                    "VENUE_CREATE",
                    "VENUE_OWNER_ASSIGN",
                    VenueConnectionRequestRepository.AUDIT_LINKED,
                ),
                audits.map { it.action },
            )
            assertTrue(audits.all { it.actorUserId == PLATFORM_OWNER_ID })
            val ownerAudit = audits.single { it.action == "VENUE_OWNER_ASSIGN" }
            assertEquals(APPLICANT_ID, ownerAudit.payload.getValue("userId").jsonPrimitive.content.toLong())
            assertEquals(first.venueId, ownerAudit.payload.getValue("venueId").jsonPrimitive.content.toLong())
        }

    @Test
    fun `request mutation routes reject invalid transitions without writes`() =
        testApplication {
            val fixture = fixture("platform-onboarding-invalid-transitions")
            environment { config = fixture.config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(fixture.jdbcUrl, PLATFORM_OWNER_ID, "platform", "Петр", "Платформа")
            seedUser(fixture.jdbcUrl, APPLICANT_ID, "applicant", "Анна", "Владелец")
            val pendingId = seedRequest(fixture.jdbcUrl, APPLICANT_ID, "Pending", "Москва", "@p", null)
            val rejectedId =
                seedRequest(
                    fixture.jdbcUrl,
                    APPLICANT_ID,
                    "Rejected",
                    "Омск",
                    "@r",
                    null,
                    status = "REJECTED",
                )
            val token = issueToken(fixture.config, PLATFORM_OWNER_ID)

            val endpoints =
                listOf(
                    Endpoint(HttpMethod.Post, "/api/platform/onboarding/requests/$pendingId/close"),
                    Endpoint(
                        HttpMethod.Put,
                        "/api/platform/onboarding/requests/$pendingId/commercial-terms",
                        VALID_TERMS_BODY,
                    ),
                    Endpoint(HttpMethod.Post, "/api/platform/onboarding/requests/$pendingId/create-and-link"),
                    Endpoint(HttpMethod.Post, "/api/platform/onboarding/requests/$rejectedId/approve"),
                    Endpoint(HttpMethod.Post, "/api/platform/onboarding/requests/$rejectedId/reject"),
                )
            endpoints.forEach { endpoint ->
                val response =
                    client.request(endpoint.path) {
                        method = endpoint.method
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                        endpoint.body?.let {
                            contentType(ContentType.Application.Json)
                            setBody(it)
                        }
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status, endpoint.path)
                assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
            }
            assertEquals("PENDING", requestStatus(fixture.jdbcUrl, pendingId))
            assertEquals("REJECTED", requestStatus(fixture.jdbcUrl, rejectedId))
            assertEquals(0, auditRows(fixture.jdbcUrl, pendingId).size)
            assertEquals(0, auditRows(fixture.jdbcUrl, rejectedId).size)
        }

    @Test
    fun `request detail and every mutation return not found for missing authoritative request`() =
        testApplication {
            val fixture = fixture("platform-onboarding-not-found")
            environment { config = fixture.config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(fixture.jdbcUrl, PLATFORM_OWNER_ID, "platform", "Петр", "Платформа")
            val token = issueToken(fixture.config, PLATFORM_OWNER_ID)
            val missingId = 9_999_999L
            val endpoints =
                listOf(
                    Endpoint(HttpMethod.Get, "/api/platform/onboarding/requests/$missingId"),
                    Endpoint(HttpMethod.Post, "/api/platform/onboarding/requests/$missingId/approve"),
                    Endpoint(HttpMethod.Post, "/api/platform/onboarding/requests/$missingId/reject"),
                    Endpoint(HttpMethod.Post, "/api/platform/onboarding/requests/$missingId/close"),
                    Endpoint(
                        HttpMethod.Put,
                        "/api/platform/onboarding/requests/$missingId/commercial-terms",
                        VALID_TERMS_BODY,
                    ),
                    Endpoint(HttpMethod.Post, "/api/platform/onboarding/requests/$missingId/create-and-link"),
                )

            endpoints.forEach { endpoint ->
                val response =
                    client.request(endpoint.path) {
                        method = endpoint.method
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                        endpoint.body?.let {
                            contentType(ContentType.Application.Json)
                            setBody(it)
                        }
                    }
                assertEquals(HttpStatusCode.NotFound, response.status, endpoint.path)
                assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
            }
        }

    @Test
    fun `audit failure rolls back platform decision and returns safe database error`() =
        testApplication {
            val fixture = fixture("platform-onboarding-audit-failure")
            environment { config = fixture.config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(fixture.jdbcUrl, PLATFORM_OWNER_ID, "platform", "Петр", "Платформа")
            seedUser(fixture.jdbcUrl, APPLICANT_ID, "applicant", "Анна", "Владелец")
            val requestId = seedRequest(fixture.jdbcUrl, APPLICANT_ID, "Rollback", "Москва", "@rollback", null)
            dropTable(fixture.jdbcUrl, "audit_log")
            val token = issueToken(fixture.config, PLATFORM_OWNER_ID)

            val response =
                client.post("/api/platform/onboarding/requests/$requestId/approve") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
            assertEquals("PENDING", requestStatus(fixture.jdbcUrl, requestId))
            val body = response.bodyAsText()
            assertFalse(body.contains("audit_log"))
            assertFalse(body.contains("SQLException"))
            assertFalse(body.contains("Rollback"))
            assertFalse(body.contains("@rollback"))
        }

    @Test
    fun `request repository read failure returns safe database error without facts`() =
        testApplication {
            val fixture = fixture("platform-onboarding-read-failure")
            environment { config = fixture.config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(fixture.jdbcUrl, PLATFORM_OWNER_ID, "platform", "Петр", "Платформа")
            dropTable(fixture.jdbcUrl, "venue_connection_requests")
            val token = issueToken(fixture.config, PLATFORM_OWNER_ID)

            val response =
                client.get("/api/platform/onboarding/requests") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
            val body = response.bodyAsText()
            assertFalse(body.contains("venue_connection_requests"))
            assertFalse(body.contains("SELECT"))
            assertFalse(body.contains("SQLException"))
        }

    @Test
    fun `ordinary venue owner manager and staff are denied every request route before request facts`() =
        testApplication {
            val fixture = fixture("platform-onboarding-rbac")
            environment { config = fixture.config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(fixture.jdbcUrl, PLATFORM_OWNER_ID, "platform", "Петр", "Платформа")
            seedUser(fixture.jdbcUrl, APPLICANT_ID, "applicant", "Анна", "Владелец")
            val requestId =
                seedRequest(
                    fixture.jdbcUrl,
                    APPLICANT_ID,
                    "SECRET-VENUE-FACT",
                    "SECRET-CITY-FACT",
                    "SECRET-CONTACT-FACT",
                    "SECRET-COMMENT-FACT",
                )
            val roleActors =
                listOf(
                    RoleActor(ORDINARY_USER_ID, null),
                    RoleActor(VENUE_OWNER_ID, "OWNER"),
                    RoleActor(MANAGER_ID, "MANAGER"),
                    RoleActor(STAFF_ID, "STAFF"),
                )
            roleActors.forEach { actor ->
                seedUser(fixture.jdbcUrl, actor.userId, "user${actor.userId}", "Role", actor.role ?: "Guest")
                actor.role?.let { role ->
                    val venueId = seedVenue(fixture.jdbcUrl, "$role venue", "Москва", "PUBLISHED")
                    seedMembership(fixture.jdbcUrl, venueId, actor.userId, role)
                }
            }
            val endpoints =
                listOf(
                    Endpoint(HttpMethod.Get, "/api/platform/onboarding/requests"),
                    Endpoint(HttpMethod.Get, "/api/platform/onboarding/requests/$requestId"),
                    Endpoint(HttpMethod.Post, "/api/platform/onboarding/requests/$requestId/approve"),
                    Endpoint(HttpMethod.Post, "/api/platform/onboarding/requests/$requestId/reject"),
                    Endpoint(HttpMethod.Post, "/api/platform/onboarding/requests/$requestId/close"),
                    Endpoint(
                        HttpMethod.Put,
                        "/api/platform/onboarding/requests/$requestId/commercial-terms",
                        VALID_TERMS_BODY,
                    ),
                    Endpoint(HttpMethod.Post, "/api/platform/onboarding/requests/$requestId/create-and-link"),
                )
            dropTable(fixture.jdbcUrl, "venue_connection_requests")

            roleActors.forEach { actor ->
                val token = issueToken(fixture.config, actor.userId)
                endpoints.forEach { endpoint ->
                    val response =
                        client.request(endpoint.path) {
                            method = endpoint.method
                            headers { append(HttpHeaders.Authorization, "Bearer $token") }
                            endpoint.body?.let {
                                contentType(ContentType.Application.Json)
                                setBody(it)
                            }
                        }
                    assertEquals(HttpStatusCode.Forbidden, response.status, "${actor.role} ${endpoint.path}")
                    assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
                    val body = response.bodyAsText()
                    assertFalse(body.contains("SECRET-VENUE-FACT"))
                    assertFalse(body.contains("SECRET-CITY-FACT"))
                    assertFalse(body.contains("SECRET-CONTACT-FACT"))
                    assertFalse(body.contains("SECRET-COMMENT-FACT"))
                }
            }
            assertEquals(0, auditRows(fixture.jdbcUrl, requestId).size)
        }

    @Test
    fun `venue list includes city and exactly every active operational owner`() =
        testApplication {
            val fixture = fixture("platform-venue-owner-projection")
            environment { config = fixture.config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(fixture.jdbcUrl, PLATFORM_OWNER_ID, "platform", "Петр", "Платформа")
            seedUser(
                fixture.jdbcUrl,
                OWNER_ONE_ID,
                "owner_one",
                "Анна",
                "Первая",
                privateGuestName = "OWNER-ONE-PRIVATE-PROFILE",
            )
            seedUser(fixture.jdbcUrl, OWNER_TWO_ID, "owner_two", "Борис", "Второй")
            seedUser(fixture.jdbcUrl, FORMER_OWNER_ID, "former_owner", "Бывший", "Владелец")
            seedUser(fixture.jdbcUrl, COMMERCIAL_OWNER_ID, "commercial", "Коммерческий", "Аккаунт")
            seedUser(fixture.jdbcUrl, STAFF_ID, "staff", "Сотрудник", "Тест")
            val accountId = seedOwnerAccount(fixture.jdbcUrl, COMMERCIAL_OWNER_ID, 7)
            val venueId =
                seedVenue(
                    fixture.jdbcUrl,
                    "Проекционное заведение",
                    "Екатеринбург",
                    "PUBLISHED",
                    ownerAccountId = accountId,
                )
            seedMembership(fixture.jdbcUrl, venueId, OWNER_ONE_ID, "OWNER")
            seedMembership(fixture.jdbcUrl, venueId, OWNER_TWO_ID, "OWNER")
            seedMembership(fixture.jdbcUrl, venueId, FORMER_OWNER_ID, "OWNER")
            deleteMembership(fixture.jdbcUrl, venueId, FORMER_OWNER_ID)
            seedMembership(fixture.jdbcUrl, venueId, FORMER_OWNER_ID, "MANAGER")
            seedMembership(fixture.jdbcUrl, venueId, STAFF_ID, "STAFF")
            val token = issueToken(fixture.config, PLATFORM_OWNER_ID)

            val response =
                client.get(
                    "/api/platform/venues" +
                        "?q=%D0%BF%D1%80%D0%BE%D0%B5%D0%BA%D1%86%D0%B8%D0%BE%D0%BD%D0%BD%D0%BE%D0%B5",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val payload = json.decodeFromString(PlatformVenueListResponse.serializer(), body)
            val venue = payload.venues.single()
            assertEquals(venueId, venue.id)
            assertEquals("Екатеринбург", venue.city)
            assertEquals(2, venue.ownersCount)
            assertEquals(listOf(OWNER_ONE_ID, OWNER_TWO_ID), venue.owners.map { it.userId })
            assertTrue(venue.owners.all { it.role == "OWNER" })
            assertFalse(venue.owners.any { it.userId == FORMER_OWNER_ID })
            assertFalse(venue.owners.any { it.userId == COMMERCIAL_OWNER_ID })
            assertFalse(venue.owners.any { it.userId == STAFF_ID })

            val root = json.parseToJsonElement(body).jsonObject
            assertEquals(setOf("venues"), root.keys)
            val venueObject = root.getValue("venues").jsonArray.single().jsonObject
            assertEquals(VENUE_SUMMARY_DTO_KEYS, venueObject.keys)
            venueObject.getValue("owners").jsonArray.forEach { owner ->
                assertEquals(VENUE_OWNER_DTO_KEYS, owner.jsonObject.keys)
            }
            assertFalse(body.contains("OWNER-ONE-PRIVATE-PROFILE"))
            assertFalse(body.contains("guestDisplayName"))
            assertFalse(body.contains("ownerAccountId"))
            assertFalse(body.contains("primaryOwnerUserId"))
            assertFalse(body.contains("allowedVenuesCount"))
            assertFalse(body.contains("commercialNote"))
        }

    @Test
    fun `venue and owner reads deny every non platform role before facts`() =
        testApplication {
            val fixture = fixture("platform-owner-read-rbac")
            environment { config = fixture.config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(fixture.jdbcUrl, PLATFORM_OWNER_ID, "platform", "Петр", "Платформа")
            seedUser(fixture.jdbcUrl, OWNER_ONE_ID, "secret_owner", "SECRET-OWNER-NAME", "Private")
            val secretVenueId = seedVenue(fixture.jdbcUrl, "SECRET-VENUE-NAME", "SECRET-CITY", "PUBLISHED")
            seedMembership(fixture.jdbcUrl, secretVenueId, OWNER_ONE_ID, "OWNER")
            val actors =
                listOf(
                    RoleActor(ORDINARY_USER_ID, null),
                    RoleActor(VENUE_OWNER_ID, "OWNER"),
                    RoleActor(MANAGER_ID, "MANAGER"),
                    RoleActor(STAFF_ID, "STAFF"),
                )
            actors.forEach { actor ->
                seedUser(fixture.jdbcUrl, actor.userId, "actor${actor.userId}", "Actor", actor.role ?: "Guest")
                actor.role?.let { role ->
                    val ownVenue = seedVenue(fixture.jdbcUrl, "$role own", "Москва", "DRAFT")
                    seedMembership(fixture.jdbcUrl, ownVenue, actor.userId, role)
                }
            }
            val endpoints =
                listOf(
                    "/api/platform/venues",
                    "/api/platform/venues/$secretVenueId",
                    "/api/platform/owners",
                    "/api/platform/owners/$OWNER_ONE_ID",
                )
            dropAllDatabaseObjects(fixture.jdbcUrl)

            actors.forEach { actor ->
                val token = issueToken(fixture.config, actor.userId)
                endpoints.forEach { path ->
                    val response =
                        client.get(path) {
                            headers { append(HttpHeaders.Authorization, "Bearer $token") }
                        }
                    assertEquals(HttpStatusCode.Forbidden, response.status, "${actor.role} $path")
                    assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
                    val body = response.bodyAsText()
                    assertFalse(body.contains("SECRET-VENUE-NAME"))
                    assertFalse(body.contains("SECRET-CITY"))
                    assertFalse(body.contains("SECRET-OWNER-NAME"))
                    assertFalse(body.contains("secret_owner"))
                }
            }
        }

    @Test
    fun `owners list aggregates one to many and many to one with exact status counts`() =
        testApplication {
            val fixture = fixture("platform-owner-list-aggregation")
            environment { config = fixture.config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            val graph = seedOwnershipGraph(fixture.jdbcUrl)
            val token = issueToken(fixture.config, PLATFORM_OWNER_ID)

            val response =
                client.get("/api/platform/owners") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val payload =
                json.decodeFromString(
                    PlatformOperationalOwnerListResponse.serializer(),
                    response.bodyAsText(),
                )
            assertEquals(setOf(OWNER_ONE_ID, OWNER_TWO_ID), payload.owners.map { it.userId }.toSet())
            val ownerOne = payload.owners.single { it.userId == OWNER_ONE_ID }
            assertEquals(2, ownerOne.venueCount)
            assertEquals(mapOf("PUBLISHED" to 1, "DRAFT" to 1), ownerOne.venueStatusCounts)
            val ownerTwo = payload.owners.single { it.userId == OWNER_TWO_ID }
            assertEquals(2, ownerTwo.venueCount)
            assertEquals(mapOf("DRAFT" to 1, "HIDDEN" to 1), ownerTwo.venueStatusCounts)
            assertFalse(payload.owners.any { it.userId == FORMER_OWNER_ID })
            assertFalse(payload.owners.any { it.userId == COMMERCIAL_OWNER_ID })

            val statusResponse =
                client.get("/api/platform/owners?status=DRAFT") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            val statusPayload =
                json.decodeFromString(
                    PlatformOperationalOwnerListResponse.serializer(),
                    statusResponse.bodyAsText(),
                )
            assertEquals(setOf(OWNER_ONE_ID, OWNER_TWO_ID), statusPayload.owners.map { it.userId }.toSet())

            val queryResponse =
                client.get("/api/platform/owners?q=owner_one") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            val queryPayload =
                json.decodeFromString(
                    PlatformOperationalOwnerListResponse.serializer(),
                    queryResponse.bodyAsText(),
                )
            assertEquals(listOf(OWNER_ONE_ID), queryPayload.owners.map { it.userId })
            assertEquals(setOf(graph.publishedVenueId, graph.sharedDraftVenueId), ownerOneVenueIds(fixture.jdbcUrl))
        }

    @Test
    fun `owner detail returns exact safe identity and venue detail link ids`() =
        testApplication {
            val fixture = fixture("platform-owner-detail-projection")
            environment { config = fixture.config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            val graph = seedOwnershipGraph(fixture.jdbcUrl)
            val token = issueToken(fixture.config, PLATFORM_OWNER_ID)

            val response =
                client.get("/api/platform/owners/$OWNER_ONE_ID") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val payload = json.decodeFromString(PlatformOperationalOwnerResponse.serializer(), body)
            assertEquals(OWNER_ONE_ID, payload.owner.userId)
            assertEquals(2, payload.owner.venueCount)
            assertEquals(
                setOf(graph.publishedVenueId, graph.sharedDraftVenueId),
                payload.venues.map { it.id }.toSet(),
            )
            assertEquals("Москва", payload.venues.single { it.id == graph.publishedVenueId }.city)
            assertFalse(payload.venues.any { it.id == graph.deletedVenueId })

            val root = json.parseToJsonElement(body).jsonObject
            assertEquals(setOf("owner", "venues"), root.keys)
            assertEquals(OPERATIONAL_OWNER_DTO_KEYS, root.getValue("owner").jsonObject.keys)
            root.getValue("venues").jsonArray.forEach { venue ->
                assertEquals(OPERATIONAL_OWNER_VENUE_DTO_KEYS, venue.jsonObject.keys)
            }
            assertFalse(body.contains("OWNER-PRIVATE-PROFILE"))
            assertFalse(body.contains("guestDisplayName"))
            assertFalse(body.contains("birthday"))
            assertFalse(body.contains("ownerAccountId"))
            assertFalse(body.contains("primaryOwnerUserId"))
            assertFalse(body.contains("allowedVenuesCount"))
            assertFalse(body.contains("commercialNote"))
            assertFalse(body.contains("invitedByUserId"))
        }

    @Test
    fun `owner detail not found and invalid owner filter are safe`() =
        testApplication {
            val fixture = fixture("platform-owner-invalid-not-found")
            environment { config = fixture.config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(fixture.jdbcUrl, PLATFORM_OWNER_ID, "platform", "Петр", "Платформа")
            val token = issueToken(fixture.config, PLATFORM_OWNER_ID)

            val missing =
                client.get("/api/platform/owners/999999") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertApiErrorEnvelope(missing, ApiErrorCodes.NOT_FOUND)

            val invalidId =
                client.get("/api/platform/owners/not-a-user") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.NotFound, invalidId.status)
            assertApiErrorEnvelope(invalidId, ApiErrorCodes.NOT_FOUND)

            val invalidStatus =
                client.get("/api/platform/owners?status=NEEDS_INFO") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.BadRequest, invalidStatus.status)
            assertApiErrorEnvelope(invalidStatus, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `venue and owner projection database failures return safe service unavailable`() =
        testApplication {
            val fixture = fixture("platform-owner-projection-db-failure")
            environment { config = fixture.config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(fixture.jdbcUrl, PLATFORM_OWNER_ID, "platform", "Петр", "Платформа")
            dropTable(fixture.jdbcUrl, "venue_members")
            val token = issueToken(fixture.config, PLATFORM_OWNER_ID)

            listOf("/api/platform/venues", "/api/platform/owners").forEach { path ->
                val response =
                    client.get(path) {
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    }
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status, path)
                assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
                val body = response.bodyAsText()
                assertFalse(body.contains("venue_members"))
                assertFalse(body.contains("SELECT"))
                assertFalse(body.contains("SQLException"))
            }
        }

    private fun decodeRequest(body: String): PlatformOnboardingRequestDto =
        json.decodeFromString(PlatformOnboardingRequestResponse.serializer(), body).request

    private fun fixture(prefix: String): Fixture {
        val jdbcUrl = buildJdbcUrl(prefix)
        val dataSource =
            JdbcDataSource().apply {
                setURL(jdbcUrl)
                user = "sa"
                password = ""
            }
        migrateH2OnboardingFixture(dataSource)
        return Fixture(jdbcUrl = jdbcUrl, config = buildConfig(jdbcUrl))
    }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
            "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String): MapApplicationConfig =
        MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
            "platform.ownerUserId" to PLATFORM_OWNER_ID.toString(),
            "venue.staffInviteSecretPepper" to "invite-pepper",
            "billing.subscription.intervalSeconds" to "0",
        )

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long,
    ): String = SessionTokenService(SessionTokenConfig.from(config, appEnv)).issueToken(userId).token

    private fun seedUser(
        jdbcUrl: String,
        userId: Long,
        username: String,
        firstName: String,
        lastName: String,
        privateGuestName: String? = null,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO users (
                    telegram_user_id, username, first_name, last_name,
                    guest_display_name, birthday_month, birthday_day
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, username)
                statement.setString(3, firstName)
                statement.setString(4, lastName)
                statement.setString(5, privateGuestName)
                if (privateGuestName == null) {
                    statement.setNull(6, Types.SMALLINT)
                    statement.setNull(7, Types.SMALLINT)
                } else {
                    statement.setInt(6, 12)
                    statement.setInt(7, 31)
                }
                statement.executeUpdate()
            }
        }
    }

    private fun seedVenue(
        jdbcUrl: String,
        name: String,
        city: String,
        status: String,
        ownerAccountId: Long? = null,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venues (name, city, address, status, owner_account_id)
                VALUES (?, ?, 'PRIVATE-VENUE-ADDRESS', ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, name)
                statement.setString(2, city)
                statement.setString(3, status)
                if (ownerAccountId == null) {
                    statement.setNull(4, Types.BIGINT)
                } else {
                    statement.setLong(4, ownerAccountId)
                }
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    check(keys.next()) { "Failed to seed venue" }
                    keys.getLong(1)
                }
            }
        }

    private fun seedMembership(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        role: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
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
        }
    }

    private fun deleteMembership(
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
                statement.executeUpdate()
            }
        }
    }

    private fun seedRequest(
        jdbcUrl: String,
        applicantUserId: Long,
        venueName: String,
        city: String,
        contact: String,
        comment: String?,
        status: String = "PENDING",
        linkedVenueId: Long? = null,
        trialConfigured: Boolean = false,
        trialEndsOn: LocalDate? = null,
        currentPriceRub: Long? = null,
        futurePriceRub: Long? = null,
        futurePriceEffectiveOn: LocalDate? = null,
        commercialNote: String? = null,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_connection_requests (
                    telegram_user_id, venue_name, city, contact, comment, status,
                    linked_venue_id, trial_configured, trial_ends_on, current_price_rub,
                    future_price_rub, future_price_effective_on, commercial_note
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, applicantUserId)
                statement.setString(2, venueName)
                statement.setString(3, city)
                statement.setString(4, contact)
                statement.setString(5, comment)
                statement.setString(6, status)
                setNullableLong(statement, 7, linkedVenueId)
                statement.setBoolean(8, trialConfigured)
                if (trialEndsOn == null) {
                    statement.setNull(9, Types.DATE)
                } else {
                    statement.setDate(9, java.sql.Date.valueOf(trialEndsOn))
                }
                setNullableLong(statement, 10, currentPriceRub)
                setNullableLong(statement, 11, futurePriceRub)
                if (futurePriceEffectiveOn == null) {
                    statement.setNull(12, Types.DATE)
                } else {
                    statement.setDate(12, java.sql.Date.valueOf(futurePriceEffectiveOn))
                }
                statement.setString(13, commercialNote)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    check(keys.next()) { "Failed to seed request" }
                    keys.getLong(1)
                }
            }
        }

    private fun seedOwnerAccount(
        jdbcUrl: String,
        ownerUserId: Long,
        allowedVenuesCount: Int,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_owner_accounts (
                    primary_owner_user_id, allowed_venues_count, notes, commercial_note
                )
                VALUES (?, ?, 'PRIVATE-ACCOUNT-NOTES', 'PRIVATE-COMMERCIAL-NOTES')
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, ownerUserId)
                statement.setInt(2, allowedVenuesCount)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    check(keys.next()) { "Failed to seed owner account" }
                    keys.getLong(1)
                }
            }
        }

    private fun seedOwnershipGraph(jdbcUrl: String): OwnershipGraph {
        seedUser(jdbcUrl, PLATFORM_OWNER_ID, "platform", "Петр", "Платформа")
        seedUser(
            jdbcUrl,
            OWNER_ONE_ID,
            "owner_one",
            "Анна",
            "Первая",
            privateGuestName = "OWNER-PRIVATE-PROFILE",
        )
        seedUser(jdbcUrl, OWNER_TWO_ID, "owner_two", "Борис", "Второй")
        seedUser(jdbcUrl, FORMER_OWNER_ID, "former_owner", "Бывший", "Владелец")
        seedUser(jdbcUrl, COMMERCIAL_OWNER_ID, "commercial", "Коммерческий", "Аккаунт")
        val commercialAccountId = seedOwnerAccount(jdbcUrl, COMMERCIAL_OWNER_ID, 10)
        val publishedVenueId = seedVenue(jdbcUrl, "Опубликованное", "Москва", "PUBLISHED")
        val sharedDraftVenueId =
            seedVenue(
                jdbcUrl,
                "Общий черновик",
                "Казань",
                "DRAFT",
                ownerAccountId = commercialAccountId,
            )
        val hiddenVenueId = seedVenue(jdbcUrl, "Скрытое", "Сочи", "HIDDEN")
        val deletedVenueId = seedVenue(jdbcUrl, "Удаленное", "Омск", "DELETED")
        seedMembership(jdbcUrl, publishedVenueId, OWNER_ONE_ID, "OWNER")
        seedMembership(jdbcUrl, sharedDraftVenueId, OWNER_ONE_ID, "OWNER")
        seedMembership(jdbcUrl, sharedDraftVenueId, OWNER_TWO_ID, "OWNER")
        seedMembership(jdbcUrl, hiddenVenueId, OWNER_TWO_ID, "OWNER")
        seedMembership(jdbcUrl, deletedVenueId, OWNER_ONE_ID, "OWNER")
        seedMembership(jdbcUrl, publishedVenueId, FORMER_OWNER_ID, "MANAGER")
        return OwnershipGraph(
            publishedVenueId = publishedVenueId,
            sharedDraftVenueId = sharedDraftVenueId,
            hiddenVenueId = hiddenVenueId,
            deletedVenueId = deletedVenueId,
        )
    }

    private fun auditRows(
        jdbcUrl: String,
        requestId: Long,
    ): List<AuditRow> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT actor_user_id, action, payload_json
                FROM audit_log
                WHERE entity_type = 'venue_connection_request' AND entity_id = ?
                   OR action IN ('VENUE_CREATE', 'VENUE_OWNER_ASSIGN')
                      AND payload_json LIKE ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, requestId)
                statement.setString(2, "%\"requestId\":$requestId%")
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                AuditRow(
                                    actorUserId = resultSet.getLong("actor_user_id"),
                                    action = resultSet.getString("action"),
                                    payload = json.parseToJsonElement(resultSet.getString("payload_json")).jsonObject,
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun requestStatus(
        jdbcUrl: String,
        requestId: Long,
    ): String = queryString(jdbcUrl, "SELECT status FROM venue_connection_requests WHERE id = ?", requestId)

    private fun requestCurrentPrice(
        jdbcUrl: String,
        requestId: Long,
    ): Long? =
        queryNullableLong(
            jdbcUrl,
            "SELECT current_price_rub FROM venue_connection_requests WHERE id = ?",
            requestId,
        )

    private fun venueStatus(
        jdbcUrl: String,
        venueId: Long,
    ): String = queryString(jdbcUrl, "SELECT status FROM venues WHERE id = ?", venueId)

    private fun venueCity(
        jdbcUrl: String,
        venueId: Long,
    ): String = queryString(jdbcUrl, "SELECT city FROM venues WHERE id = ?", venueId)

    private fun membershipCount(
        jdbcUrl: String,
        userId: Long,
        venueId: Long? = null,
        role: String? = null,
    ): Int {
        val conditions = mutableListOf("user_id = ?")
        if (venueId != null) conditions += "venue_id = ?"
        if (role != null) conditions += "UPPER(role) = ?"
        return DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM venue_members WHERE ${conditions.joinToString(" AND ")}",
            ).use { statement ->
                var index = 1
                statement.setLong(index++, userId)
                venueId?.let { statement.setLong(index++, it) }
                role?.let { statement.setString(index, it.uppercase()) }
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }
    }

    private fun ownerAccountCount(
        jdbcUrl: String,
        ownerUserId: Long,
    ): Int = queryInt(jdbcUrl, "SELECT COUNT(*) FROM venue_owner_accounts WHERE primary_owner_user_id = ?", ownerUserId)

    private fun ownerAccountLimit(
        jdbcUrl: String,
        ownerUserId: Long,
    ): Int =
        queryInt(
            jdbcUrl,
            "SELECT allowed_venues_count FROM venue_owner_accounts WHERE primary_owner_user_id = ?",
            ownerUserId,
        )

    private fun rowCountForVenue(
        jdbcUrl: String,
        tableName: String,
        venueId: Long,
    ): Int {
        check(tableName in SAFE_VENUE_TABLE_NAMES)
        return queryInt(jdbcUrl, "SELECT COUNT(*) FROM $tableName WHERE venue_id = ?", venueId)
    }

    private fun venueCount(jdbcUrl: String): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM venues").use { resultSet ->
                    check(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }

    private fun ownerOneVenueIds(jdbcUrl: String): Set<Long> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT v.id
                FROM venues v
                JOIN venue_members vm ON vm.venue_id = v.id
                WHERE vm.user_id = ? AND UPPER(vm.role) = 'OWNER' AND UPPER(v.status) <> 'DELETED'
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, OWNER_ONE_ID)
                statement.executeQuery().use { resultSet ->
                    buildSet {
                        while (resultSet.next()) add(resultSet.getLong(1))
                    }
                }
            }
        }

    private fun queryString(
        jdbcUrl: String,
        sql: String,
        id: Long,
    ): String =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    resultSet.getString(1)
                }
            }
        }

    private fun queryInt(
        jdbcUrl: String,
        sql: String,
        id: Long,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }

    private fun queryNullableLong(
        jdbcUrl: String,
        sql: String,
        id: Long,
    ): Long? =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    resultSet.getLong(1).takeIf { !resultSet.wasNull() }
                }
            }
        }

    private fun dropTable(
        jdbcUrl: String,
        tableName: String,
    ) {
        check(tableName in setOf("audit_log", "venue_connection_requests", "venue_members"))
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement -> statement.execute("DROP TABLE $tableName") }
        }
    }

    private fun dropAllDatabaseObjects(jdbcUrl: String) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement -> statement.execute("DROP ALL OBJECTS") }
        }
    }

    private fun setNullableLong(
        statement: java.sql.PreparedStatement,
        index: Int,
        value: Long?,
    ) {
        if (value == null) statement.setNull(index, Types.BIGINT) else statement.setLong(index, value)
    }

    private data class Fixture(
        val jdbcUrl: String,
        val config: MapApplicationConfig,
    )

    private data class Endpoint(
        val method: HttpMethod,
        val path: String,
        val body: String? = null,
    )

    private data class RoleActor(
        val userId: Long,
        val role: String?,
    )

    private data class AuditRow(
        val actorUserId: Long,
        val action: String,
        val payload: JsonObject,
    )

    private data class OwnershipGraph(
        val publishedVenueId: Long,
        val sharedDraftVenueId: Long,
        val hiddenVenueId: Long,
        val deletedVenueId: Long,
    )

    private companion object {
        const val PLATFORM_OWNER_ID = 88_001L
        const val APPLICANT_ID = 88_002L
        const val SPOOF_USER_ID = 88_003L
        const val ORDINARY_USER_ID = 88_004L
        const val VENUE_OWNER_ID = 88_005L
        const val MANAGER_ID = 88_006L
        const val STAFF_ID = 88_007L
        const val OWNER_ONE_ID = 88_008L
        const val OWNER_TWO_ID = 88_009L
        const val FORMER_OWNER_ID = 88_010L
        const val COMMERCIAL_OWNER_ID = 88_011L

        val REQUEST_DTO_KEYS =
            setOf(
                "id",
                "applicant",
                "venueName",
                "city",
                "contact",
                "comment",
                "status",
                "createdAt",
                "linkedVenueId",
                "trialConfigured",
                "trialEndsOn",
                "currentPriceRub",
                "futurePriceRub",
                "futurePriceEffectiveOn",
                "commercialNote",
            )
        val APPLICANT_DTO_KEYS = setOf("userId", "username", "firstName", "lastName")
        val VENUE_SUMMARY_DTO_KEYS =
            setOf(
                "id",
                "name",
                "city",
                "status",
                "createdAt",
                "ownersCount",
                "owners",
                "subscriptionSummary",
            )
        val VENUE_OWNER_DTO_KEYS = setOf("userId", "role", "username", "firstName", "lastName")
        val OPERATIONAL_OWNER_DTO_KEYS =
            setOf("userId", "username", "firstName", "lastName", "venueCount", "venueStatusCounts")
        val OPERATIONAL_OWNER_VENUE_DTO_KEYS = setOf("id", "name", "city", "status", "createdAt")
        val SAFE_VENUE_TABLE_NAMES =
            setOf("venue_settings", "venue_subscription_settings", "venue_subscriptions", "menu_categories")
        val VALID_TERMS_BODY =
            """
            {
              "trialConfigured": true,
              "currentPriceRub": 2500
            }
            """.trimIndent()
    }
}
