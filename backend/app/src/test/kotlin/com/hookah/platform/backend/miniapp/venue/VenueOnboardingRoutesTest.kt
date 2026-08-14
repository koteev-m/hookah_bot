package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.platform.PlatformOnboardingCreateLinkResponse
import com.hookah.platform.backend.platform.PlatformOnboardingRequestListResponse
import com.hookah.platform.backend.platform.PlatformOnboardingRequestResponse
import com.hookah.platform.backend.platform.PlatformOperationalOwnerListResponse
import com.hookah.platform.backend.platform.PlatformOperationalOwnerResponse
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestRepository
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import com.hookah.platform.backend.test.migrateH2OnboardingFixture
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.h2.jdbcx.JdbcDataSource
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VenueOnboardingRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `active owner manages only own onboarding applications`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-onboarding-owner")
            val config = buildConfig(jdbcUrl)

            migrateFixture(jdbcUrl)
            environment { this.config = config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(jdbcUrl, OWNER_ID, "owner$OWNER_ID", "Первый", "Владелец")
            seedUser(jdbcUrl, SECOND_OWNER_ID, "owner$SECOND_OWNER_ID", "Второй", "Владелец")
            val ownerVenueId =
                seedVenueMembership(
                    jdbcUrl = jdbcUrl,
                    userId = OWNER_ID,
                    role = "OWNER",
                    name = "Первое заведение",
                    city = "Москва",
                )
            seedVenueMembership(
                jdbcUrl = jdbcUrl,
                userId = SECOND_OWNER_ID,
                role = "OWNER",
                name = "Чужое заведение",
                city = "Казань",
            )
            val ownerToken = issueToken(config, OWNER_ID)
            val secondOwnerToken = issueToken(config, SECOND_OWNER_ID)

            val initialResponse =
                client.get("/api/venue/ownership") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, initialResponse.status)
            val initial = json.decodeFromString(VenueOwnershipResponse.serializer(), initialResponse.bodyAsText())
            assertEquals(listOf(ownerVenueId), initial.venues.map { it.venueId })
            assertTrue(initial.applications.isEmpty())

            val submitResponse =
                client.post("/api/venue/ownership/applications") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "venueName": "  Новый Lounge  ",
                          "city": "  Тула  ",
                          "contact": "  @first_owner  ",
                          "comment": "  Нужна консультация  "
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, submitResponse.status)
            val submitted =
                json.decodeFromString(
                    VenueConnectionApplicationWriteResponse.serializer(),
                    submitResponse.bodyAsText(),
                )
            assertEquals(true, submitted.created)
            assertEquals("Новый Lounge", submitted.application.venueName)
            assertEquals("Тула", submitted.application.city)
            assertEquals("@first_owner", submitted.application.contact)
            assertEquals("Нужна консультация", submitted.application.comment)
            assertEquals("PENDING", submitted.application.status)
            val requestId = submitted.application.id

            val ownListResponse =
                client.get("/api/venue/ownership") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, ownListResponse.status)
            val ownList = json.decodeFromString(VenueOwnershipResponse.serializer(), ownListResponse.bodyAsText())
            assertEquals(listOf(requestId), ownList.applications.map { it.id })

            val foreignListResponse =
                client.get("/api/venue/ownership") {
                    headers { append(HttpHeaders.Authorization, "Bearer $secondOwnerToken") }
                }
            assertEquals(HttpStatusCode.OK, foreignListResponse.status)
            val foreignList =
                json.decodeFromString(VenueOwnershipResponse.serializer(), foreignListResponse.bodyAsText())
            assertTrue(foreignList.applications.isEmpty())

            val foreignEditResponse =
                client.put("/api/venue/ownership/applications/$requestId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $secondOwnerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"venueName":"Чужая правка","city":"Омск","contact":"@foreign"}
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.NotFound, foreignEditResponse.status)
            assertApiErrorEnvelope(foreignEditResponse, ApiErrorCodes.NOT_FOUND)

            val foreignCancelResponse =
                client.post("/api/venue/ownership/applications/$requestId/cancel") {
                    headers { append(HttpHeaders.Authorization, "Bearer $secondOwnerToken") }
                }
            assertEquals(HttpStatusCode.NotFound, foreignCancelResponse.status)
            assertApiErrorEnvelope(foreignCancelResponse, ApiErrorCodes.NOT_FOUND)

            val editResponse =
                client.put("/api/venue/ownership/applications/$requestId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "venueName": "Обновленный Lounge",
                          "city": "Рязань",
                          "contact": "+7 900 000-00-00",
                          "comment": null
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, editResponse.status)
            val edited =
                json.decodeFromString(
                    VenueConnectionApplicationWriteResponse.serializer(),
                    editResponse.bodyAsText(),
                )
            assertEquals(requestId, edited.application.id)
            assertEquals("Обновленный Lounge", edited.application.venueName)
            assertEquals("Рязань", edited.application.city)
            assertEquals(null, edited.application.comment)
            assertEquals("PENDING", edited.application.status)

            val cancelResponse =
                client.post("/api/venue/ownership/applications/$requestId/cancel") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, cancelResponse.status)
            val cancelled =
                json.decodeFromString(
                    VenueConnectionApplicationWriteResponse.serializer(),
                    cancelResponse.bodyAsText(),
                )
            assertEquals(requestId, cancelled.application.id)
            assertEquals("CANCELLED", cancelled.application.status)
            assertEquals(1, countRows(jdbcUrl, "venue_connection_requests"))
        }

    @Test
    fun `canonical equivalent owner route retry returns the authoritative pending application once`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-onboarding-exact-retry")
            val config = buildConfig(jdbcUrl)

            migrateFixture(jdbcUrl)
            environment { this.config = config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(jdbcUrl, OWNER_ID, "owner$OWNER_ID", "Первый", "Владелец")
            seedVenueMembership(jdbcUrl, OWNER_ID, "OWNER", "Работающее заведение", "Москва")
            val token = issueToken(config, OWNER_ID)

            val firstResponse =
                client.post("/api/venue/ownership/applications") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "venueName": "Smoke One",
                          "city": "New York",
                          "contact": "@Owner",
                          "comment": "Call Me"
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, firstResponse.status)
            val first =
                json.decodeFromString(
                    VenueConnectionApplicationWriteResponse.serializer(),
                    firstResponse.bodyAsText(),
                )
            assertEquals(true, first.created)
            assertEquals("PENDING", first.application.status)
            assertEquals(null, first.application.linkedVenueId)

            val retryResponse =
                client.post("/api/venue/ownership/applications") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "venueName": "  smoke   one  ",
                          "city": "  new   york  ",
                          "contact": "  @owner  ",
                          "comment": "  call   me  "
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, retryResponse.status)
            val retry =
                json.decodeFromString(
                    VenueConnectionApplicationWriteResponse.serializer(),
                    retryResponse.bodyAsText(),
                )
            assertEquals(false, retry.created)
            assertEquals(first.application.id, retry.application.id)
            assertEquals("Smoke One", retry.application.venueName)
            assertEquals("New York", retry.application.city)
            assertEquals("@Owner", retry.application.contact)
            assertEquals("Call Me", retry.application.comment)
            assertEquals("PENDING", retry.application.status)
            assertEquals(null, retry.application.linkedVenueId)
            assertEquals(1, countRows(jdbcUrl, "venue_connection_requests"))
            assertEquals(1, countAudit(jdbcUrl, VenueConnectionRequestRepository.AUDIT_SUBMITTED))
            assertEquals(
                1,
                countAudit(
                    jdbcUrl,
                    VenueConnectionRequestRepository.AUDIT_SUBMITTED,
                    first.application.id,
                ),
            )
        }

    @Test
    fun `distinct second owner route application is listed and audited as its own physical request`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-onboarding-distinct")
            val config = buildConfig(jdbcUrl)

            migrateFixture(jdbcUrl)
            environment { this.config = config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(jdbcUrl, OWNER_ID, "owner$OWNER_ID", "Первый", "Владелец")
            seedVenueMembership(jdbcUrl, OWNER_ID, "OWNER", "Работающее заведение", "Москва")
            val token = issueToken(config, OWNER_ID)

            val firstResponse =
                client.post("/api/venue/ownership/applications") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"venueName":"Smoke One","city":"Москва","contact":"@owner"}
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, firstResponse.status)
            val first =
                json.decodeFromString(
                    VenueConnectionApplicationWriteResponse.serializer(),
                    firstResponse.bodyAsText(),
                )
            assertEquals(true, first.created)

            val secondResponse =
                client.post("/api/venue/ownership/applications") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"venueName":"Smoke Two","city":"Москва","contact":"@owner"}
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, secondResponse.status)
            val second =
                json.decodeFromString(
                    VenueConnectionApplicationWriteResponse.serializer(),
                    secondResponse.bodyAsText(),
                )
            assertEquals(true, second.created)
            assertTrue(first.application.id != second.application.id)

            val ownListResponse =
                client.get("/api/venue/ownership") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, ownListResponse.status)
            val ownList = json.decodeFromString(VenueOwnershipResponse.serializer(), ownListResponse.bodyAsText())
            assertEquals(listOf(second.application.id, first.application.id), ownList.applications.map { it.id })
            assertEquals(setOf("Smoke One", "Smoke Two"), ownList.applications.map { it.venueName }.toSet())
            assertTrue(ownList.applications.all { it.status == "PENDING" && it.linkedVenueId == null })

            assertEquals(2, countRows(jdbcUrl, "venue_connection_requests"))
            assertEquals(2, countAudit(jdbcUrl, VenueConnectionRequestRepository.AUDIT_SUBMITTED))
            assertEquals(
                1,
                countAudit(jdbcUrl, VenueConnectionRequestRepository.AUDIT_SUBMITTED, first.application.id),
            )
            assertEquals(
                1,
                countAudit(jdbcUrl, VenueConnectionRequestRepository.AUDIT_SUBMITTED, second.application.id),
            )
        }

    @Test
    fun `owner route ignores authority and link spoof fields while entry policy remains server selected`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-onboarding-spoof")
            val config = buildConfig(jdbcUrl)

            migrateFixture(jdbcUrl)
            environment { this.config = config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(jdbcUrl, OWNER_ID, "owner$OWNER_ID", "Первый", "Владелец")
            seedUser(jdbcUrl, MANAGER_ID, "manager$MANAGER_ID", "Менеджер", "Тест")
            seedUser(jdbcUrl, SPOOFED_AUTHORITY_ID, "spoofed$SPOOFED_AUTHORITY_ID", "Чужой", "Актор")
            seedVenueMembership(jdbcUrl, OWNER_ID, "OWNER", "Owner Venue", "Москва")
            val foreignVenueId = seedVenueMembership(jdbcUrl, MANAGER_ID, "MANAGER", "Manager Venue", "Казань")
            val ownerToken = issueToken(config, OWNER_ID)
            val managerToken = issueToken(config, MANAGER_ID)
            val spoofBody =
                """
                {
                  "venueName": "Server Authority",
                  "city": "Тула",
                  "contact": "@owner",
                  "applicantUserId": $SPOOFED_AUTHORITY_ID,
                  "telegramUserId": $SPOOFED_AUTHORITY_ID,
                  "actorUserId": $SPOOFED_AUTHORITY_ID,
                  "ownerId": $SPOOFED_AUTHORITY_ID,
                  "ownerUserId": $SPOOFED_AUTHORITY_ID,
                  "source": "PLATFORM_MINI_APP",
                  "entryPolicy": "TELEGRAM_AUTHENTICATED",
                  "venueId": $foreignVenueId,
                  "linkedVenueId": $foreignVenueId,
                  "linkTargetVenueId": $foreignVenueId
                }
                """.trimIndent()

            val response =
                client.post("/api/venue/ownership/applications") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(spoofBody)
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val submitted =
                json.decodeFromString(
                    VenueConnectionApplicationWriteResponse.serializer(),
                    response.bodyAsText(),
                )
            assertEquals(true, submitted.created)
            assertEquals(null, submitted.application.linkedVenueId)

            val stored = loadApplication(jdbcUrl, submitted.application.id)
            assertEquals(OWNER_ID, stored.applicantUserId)
            assertEquals("PENDING", stored.status)
            assertEquals(null, stored.linkedVenueId)
            assertEquals(0, countApplicationsByApplicant(jdbcUrl, SPOOFED_AUTHORITY_ID))
            assertEquals(0, countRows(jdbcUrl, "venue_owner_accounts"))

            val audit = loadSingleAudit(jdbcUrl, VenueConnectionRequestRepository.AUDIT_SUBMITTED)
            assertEquals(OWNER_ID, audit.actorUserId)
            assertEquals(submitted.application.id, audit.entityId)
            val payload = json.decodeFromString(JsonObject.serializer(), audit.payloadJson)
            assertEquals(setOf("requestId", "status", "source"), payload.keys)
            assertEquals(submitted.application.id, payload.getValue("requestId").jsonPrimitive.long)
            assertEquals("PENDING", payload.getValue("status").jsonPrimitive.content)
            assertEquals("VENUE_MINI_APP", payload.getValue("source").jsonPrimitive.content)

            val deniedResponse =
                client.post("/api/venue/ownership/applications") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(spoofBody.replace("Server Authority", "Policy Spoof"))
                }
            assertEquals(HttpStatusCode.Forbidden, deniedResponse.status)
            assertApiErrorEnvelope(deniedResponse, ApiErrorCodes.FORBIDDEN)
            assertEquals(1, countRows(jdbcUrl, "venue_connection_requests"))
            assertEquals(1, countAudit(jdbcUrl, VenueConnectionRequestRepository.AUDIT_SUBMITTED))
        }

    @Test
    fun `submission audit failure returns safe database unavailable without partial request or false success`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-onboarding-submit-audit-failure")
            val config = buildConfig(jdbcUrl)

            migrateFixture(jdbcUrl)
            environment { this.config = config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(jdbcUrl, OWNER_ID, "owner$OWNER_ID", "Первый", "Владелец")
            seedVenueMembership(jdbcUrl, OWNER_ID, "OWNER", "Работающее заведение", "Москва")
            rejectSubmissionAudit(jdbcUrl)
            val token = issueToken(config, OWNER_ID)

            val response =
                client.post("/api/venue/ownership/applications") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "venueName": "Rollback Venue",
                          "city": "Москва",
                          "contact": "@rollback-secret"
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
            val body = response.bodyAsText()
            assertFalse(body.contains("audit_log"))
            assertFalse(body.contains("venue_connection_requests"))
            assertFalse(body.contains("SQLException"))
            assertFalse(body.contains("reject_venue_onboarding_submit_audit"))
            assertFalse(body.contains("VENUE_CONNECTION_REQUEST_SUBMITTED"))
            assertFalse(body.contains("Rollback Venue"))
            assertFalse(body.contains("@rollback-secret"))
            assertFalse(body.contains(OWNER_ID.toString()))
            assertFalse(body.contains("\"application\""))
            assertFalse(body.contains("\"created\""))
            assertEquals(0, countRows(jdbcUrl, "venue_connection_requests"))
            assertEquals(0, countAudit(jdbcUrl, VenueConnectionRequestRepository.AUDIT_SUBMITTED))
            assertEquals(0, countRows(jdbcUrl, "audit_log"))

            val ownershipResponse =
                client.get("/api/venue/ownership") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, ownershipResponse.status)
            val ownership =
                json.decodeFromString(VenueOwnershipResponse.serializer(), ownershipResponse.bodyAsText())
            assertTrue(ownership.applications.isEmpty())
        }

    @Test
    fun `approved unlinked canonical retry returns exact authoritative route status and link fields`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-onboarding-approved-unlinked-retry")
            val config = buildConfig(jdbcUrl, platformOwnerId = PLATFORM_OWNER_ID)

            migrateFixture(jdbcUrl)
            environment { this.config = config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(jdbcUrl, OWNER_ID, "owner$OWNER_ID", "Первый", "Владелец")
            seedUser(jdbcUrl, PLATFORM_OWNER_ID, "platform$PLATFORM_OWNER_ID", "Петр", "Платформа")
            seedVenueMembership(jdbcUrl, OWNER_ID, "OWNER", "Работающее заведение", "Москва")
            val ownerToken = issueToken(config, OWNER_ID)
            val platformToken = issueToken(config, PLATFORM_OWNER_ID)

            val firstResponse =
                client.post("/api/venue/ownership/applications") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "venueName": "Approval Lounge",
                          "city": "Сочи",
                          "contact": "@owner",
                          "comment": "Connect Me"
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, firstResponse.status)
            val first =
                json.decodeFromString(
                    VenueConnectionApplicationWriteResponse.serializer(),
                    firstResponse.bodyAsText(),
                )

            val approveResponse =
                client.post("/api/platform/onboarding/requests/${first.application.id}/approve") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }
            assertEquals(HttpStatusCode.OK, approveResponse.status)
            val approved =
                json.decodeFromString(
                    PlatformOnboardingRequestResponse.serializer(),
                    approveResponse.bodyAsText(),
                )
            assertEquals("APPROVED", approved.request.status)
            assertEquals(null, approved.request.linkedVenueId)

            val retryResponse =
                client.post("/api/venue/ownership/applications") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "venueName": "  approval   lounge  ",
                          "city": "  СОЧИ  ",
                          "contact": "  @OWNER  ",
                          "comment": "  connect   me  "
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, retryResponse.status)
            val retry =
                json.decodeFromString(
                    VenueConnectionApplicationWriteResponse.serializer(),
                    retryResponse.bodyAsText(),
                )
            assertEquals(false, retry.created)
            assertEquals(first.application.id, retry.application.id)
            assertEquals("Approval Lounge", retry.application.venueName)
            assertEquals("Сочи", retry.application.city)
            assertEquals("@owner", retry.application.contact)
            assertEquals("Connect Me", retry.application.comment)
            assertEquals("APPROVED", retry.application.status)
            assertEquals(null, retry.application.linkedVenueId)
            assertEquals(1, countRows(jdbcUrl, "venue_connection_requests"))
            assertEquals(0, countApplicationsByStatus(jdbcUrl, "PENDING"))
            assertEquals(1, countApplicationsByStatus(jdbcUrl, "APPROVED"))
            assertEquals(1, countAudit(jdbcUrl, VenueConnectionRequestRepository.AUDIT_SUBMITTED))
            assertEquals(1, countAudit(jdbcUrl, VenueConnectionRequestRepository.AUDIT_APPROVED))
        }

    @Test
    fun `inactive owner unaffiliated manager staff and platform only actors cannot use venue ownership mutations`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-onboarding-rbac")
            val config = buildConfig(jdbcUrl, platformOwnerId = PLATFORM_OWNER_ID)

            migrateFixture(jdbcUrl)
            environment { this.config = config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(jdbcUrl, OWNER_ID, "owner$OWNER_ID", "Разрешенный", "Владелец")
            seedUser(jdbcUrl, MANAGER_ID, "manager$MANAGER_ID", "Менеджер", "Тест")
            seedUser(jdbcUrl, STAFF_ID, "staff$STAFF_ID", "Сотрудник", "Тест")
            seedUser(jdbcUrl, UNAFFILIATED_ID, "guest$UNAFFILIATED_ID", "Гость", "Тест")
            seedUser(jdbcUrl, PLATFORM_OWNER_ID, "platform$PLATFORM_OWNER_ID", "Платформа", "Владелец")
            seedUser(jdbcUrl, ARCHIVED_OWNER_ID, "archived$ARCHIVED_OWNER_ID", "Архивный", "Владелец")
            seedVenueMembership(jdbcUrl, OWNER_ID, "OWNER", "Owner Venue", "Москва")
            seedVenueMembership(jdbcUrl, MANAGER_ID, "MANAGER", "Manager Venue", "Самара")
            seedVenueMembership(jdbcUrl, STAFF_ID, "STAFF", "Staff Venue", "Пермь")
            seedVenueMembership(
                jdbcUrl,
                ARCHIVED_OWNER_ID,
                "OWNER",
                "Archived Venue",
                "Омск",
                status = VenueStatus.ARCHIVED,
            )

            val ownerToken = issueToken(config, OWNER_ID)
            val submitResponse =
                client.post("/api/venue/ownership/applications") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"venueName":"Защищенная заявка","city":"Москва","contact":"@owner"}
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, submitResponse.status)
            val requestId =
                json.decodeFromString(
                    VenueConnectionApplicationWriteResponse.serializer(),
                    submitResponse.bodyAsText(),
                ).application.id

            listOf(MANAGER_ID, STAFF_ID, UNAFFILIATED_ID, PLATFORM_OWNER_ID, ARCHIVED_OWNER_ID).forEach { userId ->
                val token = issueToken(config, userId)

                val listResponse =
                    client.get("/api/venue/ownership") {
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    }
                assertEquals(HttpStatusCode.Forbidden, listResponse.status, "list userId=$userId")
                assertApiErrorEnvelope(listResponse, ApiErrorCodes.FORBIDDEN)

                val createResponse =
                    client.post("/api/venue/ownership/applications") {
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {"venueName":"Запрещено","city":"Тверь","contact":"@denied"}
                            """.trimIndent(),
                        )
                    }
                assertEquals(HttpStatusCode.Forbidden, createResponse.status, "create userId=$userId")
                assertApiErrorEnvelope(createResponse, ApiErrorCodes.FORBIDDEN)

                val editResponse =
                    client.put("/api/venue/ownership/applications/$requestId") {
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {"venueName":"Запрещено","city":"Тверь","contact":"@denied"}
                            """.trimIndent(),
                        )
                    }
                assertEquals(HttpStatusCode.Forbidden, editResponse.status, "edit userId=$userId")
                assertApiErrorEnvelope(editResponse, ApiErrorCodes.FORBIDDEN)

                val cancelResponse =
                    client.post("/api/venue/ownership/applications/$requestId/cancel") {
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    }
                assertEquals(HttpStatusCode.Forbidden, cancelResponse.status, "cancel userId=$userId")
                assertApiErrorEnvelope(cancelResponse, ApiErrorCodes.FORBIDDEN)
            }

            val ownerListResponse =
                client.get("/api/venue/ownership") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, ownerListResponse.status)
            val ownerList =
                json.decodeFromString(VenueOwnershipResponse.serializer(), ownerListResponse.bodyAsText())
            assertEquals(listOf(requestId), ownerList.applications.map { it.id })
            assertEquals("Защищенная заявка", ownerList.applications.single().venueName)
            assertEquals("PENDING", ownerList.applications.single().status)
            assertEquals(1, countRows(jdbcUrl, "venue_connection_requests"))
        }

    @Test
    fun `platform owner approves terms links once and sees authoritative owner aggregation`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-onboarding-workflow")
            val config = buildConfig(jdbcUrl, platformOwnerId = PLATFORM_OWNER_ID)

            migrateFixture(jdbcUrl)
            environment { this.config = config }
            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            seedUser(jdbcUrl, OWNER_ID, "owner$OWNER_ID", "Анна", "Владелец")
            seedUser(jdbcUrl, PLATFORM_OWNER_ID, "platform$PLATFORM_OWNER_ID", "Петр", "Платформа")
            val existingVenueId =
                seedVenueMembership(
                    jdbcUrl = jdbcUrl,
                    userId = OWNER_ID,
                    role = "OWNER",
                    name = "Работающее заведение",
                    city = "Москва",
                )
            seedCommercialOwnerAccount(
                jdbcUrl = jdbcUrl,
                ownerUserId = OWNER_ID,
                venueId = existingVenueId,
                allowedVenuesCount = 2,
                updatedByUserId = PLATFORM_OWNER_ID,
            )
            val ownerToken = issueToken(config, OWNER_ID)
            val platformToken = issueToken(config, PLATFORM_OWNER_ID)

            val submitResponse =
                client.post("/api/venue/ownership/applications") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "venueName": "Второе заведение",
                          "city": "Сочи",
                          "contact": "@owner$OWNER_ID",
                          "comment": "Подключите к платформе"
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, submitResponse.status)
            val submitted =
                json.decodeFromString(
                    VenueConnectionApplicationWriteResponse.serializer(),
                    submitResponse.bodyAsText(),
                )
            val requestId = submitted.application.id

            val deniedPlatformList =
                client.get("/api/platform/onboarding/requests") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, deniedPlatformList.status)
            assertApiErrorEnvelope(deniedPlatformList, ApiErrorCodes.FORBIDDEN)

            val pendingListResponse =
                client.get("/api/platform/onboarding/requests?status=PENDING&q=$OWNER_ID") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }
            assertEquals(HttpStatusCode.OK, pendingListResponse.status)
            val pendingList =
                json.decodeFromString(
                    PlatformOnboardingRequestListResponse.serializer(),
                    pendingListResponse.bodyAsText(),
                )
            assertEquals(listOf(requestId), pendingList.requests.map { it.id })
            assertEquals(OWNER_ID, pendingList.requests.single().applicant.userId)
            assertEquals("@owner$OWNER_ID", pendingList.requests.single().contact)

            val approveResponse =
                client.post("/api/platform/onboarding/requests/$requestId/approve") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }
            assertEquals(HttpStatusCode.OK, approveResponse.status)
            val approved =
                json.decodeFromString(
                    PlatformOnboardingRequestResponse.serializer(),
                    approveResponse.bodyAsText(),
                )
            assertEquals("APPROVED", approved.request.status)
            assertFalse(approved.request.trialConfigured)

            val invalidTermsResponse =
                client.put("/api/platform/onboarding/requests/$requestId/commercial-terms") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "trialConfigured": true,
                          "currentPriceRub": 2500,
                          "futurePriceRub": 3000,
                          "futurePriceEffectiveOn": "   "
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, invalidTermsResponse.status)
            assertApiErrorEnvelope(invalidTermsResponse, ApiErrorCodes.INVALID_INPUT)

            val termsResponse =
                client.put("/api/platform/onboarding/requests/$requestId/commercial-terms") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "trialConfigured": true,
                          "trialEndsOn": "2026-09-30",
                          "currentPriceRub": 2500,
                          "futurePriceRub": 3000,
                          "futurePriceEffectiveOn": "2027-01-01",
                          "commercialNote": "Индивидуальные условия"
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, termsResponse.status)
            val withTerms =
                json.decodeFromString(
                    PlatformOnboardingRequestResponse.serializer(),
                    termsResponse.bodyAsText(),
                )
            assertTrue(withTerms.request.trialConfigured)
            assertEquals("2026-09-30", withTerms.request.trialEndsOn)
            assertEquals(2500L, withTerms.request.currentPriceRub)
            assertEquals(3000L, withTerms.request.futurePriceRub)

            val firstLinkResponse =
                client.post("/api/platform/onboarding/requests/$requestId/create-and-link") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }
            assertEquals(HttpStatusCode.OK, firstLinkResponse.status)
            val firstLink =
                json.decodeFromString(
                    PlatformOnboardingCreateLinkResponse.serializer(),
                    firstLinkResponse.bodyAsText(),
                )
            assertTrue(firstLink.created)
            assertEquals(firstLink.venueId, firstLink.request.linkedVenueId)

            val retryLinkResponse =
                client.post("/api/platform/onboarding/requests/$requestId/create-and-link") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }
            assertEquals(HttpStatusCode.OK, retryLinkResponse.status)
            val retryLink =
                json.decodeFromString(
                    PlatformOnboardingCreateLinkResponse.serializer(),
                    retryLinkResponse.bodyAsText(),
                )
            assertFalse(retryLink.created)
            assertEquals(firstLink.venueId, retryLink.venueId)
            assertEquals(firstLink.venueId, retryLink.request.linkedVenueId)

            val ownersResponse =
                client.get("/api/platform/owners?q=$OWNER_ID&status=DRAFT") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }
            assertEquals(HttpStatusCode.OK, ownersResponse.status)
            val owners =
                json.decodeFromString(
                    PlatformOperationalOwnerListResponse.serializer(),
                    ownersResponse.bodyAsText(),
                )
            val ownerSummary = owners.owners.single { it.userId == OWNER_ID }
            assertEquals(2, ownerSummary.venueCount)
            assertEquals(1, ownerSummary.venueStatusCounts["PUBLISHED"])
            assertEquals(1, ownerSummary.venueStatusCounts["DRAFT"])

            val ownerDetailResponse =
                client.get("/api/platform/owners/$OWNER_ID") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }
            assertEquals(HttpStatusCode.OK, ownerDetailResponse.status)
            val ownerDetail =
                json.decodeFromString(
                    PlatformOperationalOwnerResponse.serializer(),
                    ownerDetailResponse.bodyAsText(),
                )
            assertEquals(2, ownerDetail.owner.venueCount)
            assertEquals(setOf(existingVenueId, firstLink.venueId), ownerDetail.venues.map { it.id }.toSet())
            assertEquals("Сочи", ownerDetail.venues.single { it.id == firstLink.venueId }.city)

            val refreshedVenueMeResponse =
                client.get("/api/venue/me") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, refreshedVenueMeResponse.status)
            val refreshedVenueMe =
                json.decodeFromString(VenueMeResponse.serializer(), refreshedVenueMeResponse.bodyAsText())
            assertEquals(
                setOf(existingVenueId, firstLink.venueId),
                refreshedVenueMe.venues.map { it.venueId }.toSet(),
            )
            assertEquals(1, countRows(jdbcUrl, "venue_connection_requests"))
            assertEquals(2, countRows(jdbcUrl, "venues"))
            assertEquals(2, countRows(jdbcUrl, "venue_members"))
            assertEquals(0, countRows(jdbcUrl, "menu_categories"))
            assertNotNull(firstLink.request.linkedVenueId)
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun migrateFixture(jdbcUrl: String) {
        val dataSource =
            JdbcDataSource().apply {
                setURL(jdbcUrl)
                user = "sa"
                password = ""
            }
        migrateH2OnboardingFixture(dataSource)
    }

    private fun buildConfig(
        jdbcUrl: String,
        platformOwnerId: Long? = null,
    ): MapApplicationConfig {
        val values =
            mutableListOf(
                "app.env" to appEnv,
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to jdbcUrl,
                "db.user" to "sa",
                "db.password" to "",
                "venue.staffInviteSecretPepper" to "invite-pepper",
            )
        platformOwnerId?.let { values += "platform.ownerUserId" to it.toString() }
        return MapApplicationConfig(*values.toTypedArray())
    }

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
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO users (telegram_user_id, username, first_name, last_name)
                KEY (telegram_user_id)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, username)
                statement.setString(3, firstName)
                statement.setString(4, lastName)
                statement.executeUpdate()
            }
        }
    }

    private fun seedVenueMembership(
        jdbcUrl: String,
        userId: Long,
        role: String,
        name: String,
        city: String,
        status: VenueStatus = VenueStatus.PUBLISHED,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES (?, ?, 'Test address', ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setString(1, name)
                    statement.setString(2, city)
                    statement.setString(3, status.dbValue)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        check(keys.next()) { "Failed to insert venue" }
                        keys.getLong(1)
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

    private fun seedCommercialOwnerAccount(
        jdbcUrl: String,
        ownerUserId: Long,
        venueId: Long,
        allowedVenuesCount: Int,
        updatedByUserId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val accountId =
                connection.prepareStatement(
                    """
                    INSERT INTO venue_owner_accounts (
                        primary_owner_user_id, allowed_venues_count, updated_by_user_id
                    ) VALUES (?, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, ownerUserId)
                    statement.setInt(2, allowedVenuesCount)
                    statement.setLong(3, updatedByUserId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        check(keys.next()) { "Failed to insert owner account" }
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement("UPDATE venues SET owner_account_id = ? WHERE id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.setLong(2, venueId)
                check(statement.executeUpdate() == 1) { "Failed to attach venue to owner account" }
            }
        }
    }

    private fun countRows(
        jdbcUrl: String,
        tableName: String,
    ): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $tableName").use { resultSet ->
                    check(resultSet.next())
                    return resultSet.getInt(1)
                }
            }
        }
    }

    private fun countAudit(
        jdbcUrl: String,
        action: String,
        entityId: Long? = null,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val sql =
                if (entityId == null) {
                    "SELECT COUNT(*) FROM audit_log WHERE action = ?"
                } else {
                    "SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_id = ?"
                }
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, action)
                entityId?.let { statement.setLong(2, it) }
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }

    private fun countApplicationsByApplicant(
        jdbcUrl: String,
        applicantUserId: Long,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM venue_connection_requests WHERE telegram_user_id = ?",
            ).use { statement ->
                statement.setLong(1, applicantUserId)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }

    private fun countApplicationsByStatus(
        jdbcUrl: String,
        status: String,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM venue_connection_requests WHERE status = ?",
            ).use { statement ->
                statement.setString(1, status)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }

    private fun loadApplication(
        jdbcUrl: String,
        requestId: Long,
    ): StoredApplication =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT telegram_user_id, status, linked_venue_id
                FROM venue_connection_requests
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, requestId)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    StoredApplication(
                        applicantUserId = resultSet.getLong("telegram_user_id"),
                        status = resultSet.getString("status"),
                        linkedVenueId = resultSet.getLong("linked_venue_id").takeIf { !resultSet.wasNull() },
                    )
                }
            }
        }

    private fun loadSingleAudit(
        jdbcUrl: String,
        action: String,
    ): StoredAudit =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT actor_user_id, entity_id, payload_json
                FROM audit_log
                WHERE action = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, action)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    val audit =
                        StoredAudit(
                            actorUserId = resultSet.getLong("actor_user_id"),
                            entityId = resultSet.getLong("entity_id"),
                            payloadJson = resultSet.getString("payload_json"),
                        )
                    check(!resultSet.next()) { "Expected exactly one $action audit" }
                    audit
                }
            }
        }

    private fun rejectSubmissionAudit(jdbcUrl: String) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    ALTER TABLE audit_log
                    ADD CONSTRAINT reject_venue_onboarding_submit_audit
                    CHECK (action <> 'VENUE_CONNECTION_REQUEST_SUBMITTED')
                    """.trimIndent(),
                )
            }
        }
    }

    private data class StoredApplication(
        val applicantUserId: Long,
        val status: String,
        val linkedVenueId: Long?,
    )

    private data class StoredAudit(
        val actorUserId: Long,
        val entityId: Long,
        val payloadJson: String,
    )

    private companion object {
        const val OWNER_ID = 51_001L
        const val SECOND_OWNER_ID = 51_002L
        const val MANAGER_ID = 51_003L
        const val STAFF_ID = 51_004L
        const val UNAFFILIATED_ID = 51_005L
        const val PLATFORM_OWNER_ID = 51_006L
        const val ARCHIVED_OWNER_ID = 51_007L
        const val SPOOFED_AUTHORITY_ID = 51_008L
    }
}
