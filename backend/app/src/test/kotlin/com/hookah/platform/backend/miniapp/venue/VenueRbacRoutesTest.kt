package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.VenueResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.telegram.StaffChatNotificationResult
import com.hookah.platform.backend.telegram.StaffChatNotifier
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VenueRbacRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `venue me without auth returns unauthorized`() =
        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.env" to appEnv,
                        "api.session.jwtSecret" to "test-secret",
                        "db.jdbcUrl" to "",
                    )
            }

            application { module() }

            val response = client.get("/api/venue/me")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.UNAUTHORIZED)
        }

    @Test
    fun `owner can generate staff chat link code`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-owner")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val token = issueToken(config)

            val response =
                client.post("/api/venue/$venueId/staff-chat/link-code") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(StaffChatLinkCodeResponse.serializer(), response.bodyAsText())
            assertTrue(payload.code.isNotBlank())
            assertTrue(payload.ttlSeconds > 0)
        }

    @Test
    fun `manager can generate staff chat link code`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-manager")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val token = issueToken(config)

            val response =
                client.post("/api/venue/$venueId/staff-chat/link-code") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(StaffChatLinkCodeResponse.serializer(), response.bodyAsText())
            assertTrue(payload.code.isNotBlank())
            assertTrue(payload.ttlSeconds > 0)
        }

    @Test
    fun `staff cannot generate staff chat link code`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-staff")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val token = issueToken(config)

            val response =
                client.post("/api/venue/$venueId/staff-chat/link-code") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `legacy admin can generate staff chat link code as manager alias`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-admin-link-code")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "ADMIN")
            val token = issueToken(config)

            val response =
                client.post("/api/venue/$venueId/staff-chat/link-code") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(StaffChatLinkCodeResponse.serializer(), response.bodyAsText())
            assertTrue(payload.code.isNotBlank())
            assertTrue(payload.ttlSeconds > 0)
        }

    @Test
    fun `owner can read public card settings`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-public-card-owner-read")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val token = issueToken(config)

            val response =
                client.get("/api/venue/$venueId/public-card") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload =
                json.decodeFromString(VenuePublicCardSettingsResponse.serializer(), response.bodyAsText())
            assertEquals(venueId, payload.venueId)
            assertEquals("Venue", payload.name)
            assertEquals("City", payload.city)
            assertEquals("Address", payload.address)
            assertEquals(null, payload.guestContact)
            assertEquals(null, payload.cardDescription)
        }

    @Test
    fun `manager can update public card settings and guest card reflects them`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-public-card-manager-update")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val token = issueToken(config)
            val request =
                VenuePublicCardSettingsUpdateRequest(
                    city = " Москва ",
                    address = " Новый Арбат, 24 ",
                    guestContact = " +7 999 000-00-00 ",
                    cardDescription = " Авторские чаши и спокойная посадка. ",
                )

            val updateResponse =
                client.put("/api/venue/$venueId/public-card") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(VenuePublicCardSettingsUpdateRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.OK, updateResponse.status)
            val updated =
                json.decodeFromString(VenuePublicCardSettingsResponse.serializer(), updateResponse.bodyAsText())
            assertEquals("Venue", updated.name)
            assertEquals("Москва", updated.city)
            assertEquals("Новый Арбат, 24", updated.address)
            assertEquals("+7 999 000-00-00", updated.guestContact)
            assertEquals("Авторские чаши и спокойная посадка.", updated.cardDescription)

            val guestResponse =
                client.get("/api/guest/venue/$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, guestResponse.status)
            val guestPayload = json.decodeFromString(VenueResponse.serializer(), guestResponse.bodyAsText())
            assertEquals("Москва", guestPayload.venue.city)
            assertEquals("Новый Арбат, 24", guestPayload.venue.address)
            assertEquals("+7 999 000-00-00", guestPayload.venue.guestContact)
            assertEquals("Авторские чаши и спокойная посадка.", guestPayload.venue.cardDescription)
        }

    @Test
    fun `blank public card fields are normalized to null`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-public-card-blank")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val token = issueToken(config)
            val request =
                VenuePublicCardSettingsUpdateRequest(
                    city = " ",
                    address = "",
                    guestContact = "\t",
                    cardDescription = "\n",
                )

            val response =
                client.put("/api/venue/$venueId/public-card") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(VenuePublicCardSettingsUpdateRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload =
                json.decodeFromString(VenuePublicCardSettingsResponse.serializer(), response.bodyAsText())
            assertEquals(null, payload.city)
            assertEquals(null, payload.address)
            assertEquals(null, payload.guestContact)
            assertEquals(null, payload.cardDescription)
        }

    @Test
    fun `staff cannot read or update public card settings`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-public-card-staff-denied")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val token = issueToken(config)

            val readResponse =
                client.get("/api/venue/$venueId/public-card") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, readResponse.status)
            assertApiErrorEnvelope(readResponse, ApiErrorCodes.FORBIDDEN)

            val updateResponse =
                client.put("/api/venue/$venueId/public-card") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenuePublicCardSettingsUpdateRequest.serializer(),
                            VenuePublicCardSettingsUpdateRequest(city = "Москва"),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.Forbidden, updateResponse.status)
            assertApiErrorEnvelope(updateResponse, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `foreign venue cannot update public card settings`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-public-card-foreign")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val foreignVenueId = seedVenueMembership(jdbcUrl, 777L, "OWNER")
            val token = issueToken(config)

            val response =
                client.put("/api/venue/$foreignVenueId/public-card") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenuePublicCardSettingsUpdateRequest.serializer(),
                            VenuePublicCardSettingsUpdateRequest(city = "Москва"),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `invalid public card settings update preserves existing values`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-public-card-invalid")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val token = issueToken(config)
            val invalid =
                VenuePublicCardSettingsUpdateRequest(
                    city = "М".repeat(121),
                    address = "Новый Арбат, 24",
                    guestContact = "+7 999 000-00-00",
                    cardDescription = "Описание",
                )

            val invalidResponse =
                client.put("/api/venue/$venueId/public-card") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(VenuePublicCardSettingsUpdateRequest.serializer(), invalid))
                }

            assertEquals(HttpStatusCode.BadRequest, invalidResponse.status)
            assertApiErrorEnvelope(invalidResponse, ApiErrorCodes.INVALID_INPUT)

            val readResponse =
                client.get("/api/venue/$venueId/public-card") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, readResponse.status)
            val payload =
                json.decodeFromString(VenuePublicCardSettingsResponse.serializer(), readResponse.bodyAsText())
            assertEquals("City", payload.city)
            assertEquals("Address", payload.address)
            assertEquals(null, payload.guestContact)
            assertEquals(null, payload.cardDescription)
        }

    @Test
    fun `manager cannot unlink staff chat`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-manager-unlink")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val token = issueToken(config)

            val response =
                client.post("/api/venue/$venueId/staff-chat/unlink") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `owner sees staff chat status with masked chat id`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-owner-chat-status")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            linkStaffChat(jdbcUrl, venueId, chatId = -1001234567890L, userId = TELEGRAM_USER_ID)
            val token = issueToken(config)

            val response =
                client.get("/api/venue/$venueId/staff-chat") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(StaffChatStatusResponse.serializer(), response.bodyAsText())
            assertTrue(payload.isLinked)
            assertEquals(null, payload.chatId)
            assertEquals("-100...7890", payload.maskedChatId)
            assertTrue(payload.testCommand?.startsWith("/link_test") == true)
        }

    @Test
    fun `staff cannot read staff chat status or send test message`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-staff-chat-denied")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val token = issueToken(config)

            val statusResponse =
                client.get("/api/venue/$venueId/staff-chat") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, statusResponse.status)
            assertApiErrorEnvelope(statusResponse, ApiErrorCodes.FORBIDDEN)

            val testResponse =
                client.post("/api/venue/$venueId/staff-chat/test") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, testResponse.status)
            assertApiErrorEnvelope(testResponse, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `owner can unlink linked staff chat and repeated unlink is safe`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-owner-unlink")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            linkStaffChat(jdbcUrl, venueId, chatId = -1001234567890L, userId = TELEGRAM_USER_ID)
            val token = issueToken(config)

            val first =
                client.post("/api/venue/$venueId/staff-chat/unlink") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, first.status)

            val statusAfterUnlink =
                client.get("/api/venue/$venueId/staff-chat") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, statusAfterUnlink.status)
            val payload =
                json.decodeFromString(StaffChatStatusResponse.serializer(), statusAfterUnlink.bodyAsText())
            assertFalse(payload.isLinked)
            assertEquals(null, payload.maskedChatId)

            val second =
                client.post("/api/venue/$venueId/staff-chat/unlink") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, second.status)
        }

    @Test
    fun `owner can queue staff chat test message through notifier`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-owner-test-chat")
            val config = buildConfig(jdbcUrl)
            val staffChatNotifier = mockk<StaffChatNotifier>()

            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(staffChatNotifier = staffChatNotifier),
                )
            }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val token = issueToken(config)
            coEvery { staffChatNotifier.notifyTestMessageNow(venueId) } returns
                StaffChatNotificationResult.SENT_OR_QUEUED

            val response =
                client.post("/api/venue/$venueId/staff-chat/test") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(StaffChatTestResponse.serializer(), response.bodyAsText())
            assertEquals("QUEUED", payload.result)
            assertTrue(payload.queued)
            assertEquals("Тестовое сообщение поставлено в отправку.", payload.message)
            coVerify(exactly = 1) { staffChatNotifier.notifyTestMessageNow(venueId) }
        }

    @Test
    fun `manager test message behavior follows staff chat link permission`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-manager-test-chat")
            val config = buildConfig(jdbcUrl)
            val staffChatNotifier = mockk<StaffChatNotifier>()

            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(staffChatNotifier = staffChatNotifier),
                )
            }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val token = issueToken(config)
            coEvery { staffChatNotifier.notifyTestMessageNow(venueId) } returns
                StaffChatNotificationResult.SKIPPED_NO_STAFF_CHAT

            val response =
                client.post("/api/venue/$venueId/staff-chat/test") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(StaffChatTestResponse.serializer(), response.bodyAsText())
            assertEquals("NO_STAFF_CHAT", payload.result)
            assertFalse(payload.queued)
            assertEquals("Чат не подключён.", payload.message)
            coVerify(exactly = 1) { staffChatNotifier.notifyTestMessageNow(venueId) }
        }

    @Test
    fun `foreign venue cannot send staff chat test message`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-test-chat-foreign")
            val config = buildConfig(jdbcUrl)
            val staffChatNotifier = mockk<StaffChatNotifier>(relaxed = true)

            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(staffChatNotifier = staffChatNotifier),
                )
            }

            client.get("/health")

            seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val foreignVenueId = seedVenueMembership(jdbcUrl, 777L, "OWNER")
            val token = issueToken(config)

            val response =
                client.post("/api/venue/$foreignVenueId/staff-chat/test") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
            coVerify(exactly = 0) { staffChatNotifier.notifyTestMessageNow(any()) }
        }

    @Test
    fun `venue me returns permissions for each role`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-me")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerVenueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val managerVenueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val staffVenueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val token = issueToken(config)

            val response =
                client.get("/api/venue/me") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(VenueMeResponse.serializer(), response.bodyAsText())
            assertEquals(TELEGRAM_USER_ID, payload.userId)

            val venuesById = payload.venues.associateBy { it.venueId }
            assertEquals(setOf(ownerVenueId, managerVenueId, staffVenueId), venuesById.keys)

            assertEquals("OWNER", venuesById.getValue(ownerVenueId).role)
            assertEquals("Venue", venuesById.getValue(ownerVenueId).venueName)
            assertEquals("City", venuesById.getValue(ownerVenueId).venueCity)
            assertEquals("PUBLISHED", venuesById.getValue(ownerVenueId).venueStatus)
            assertEquals(
                setOf(
                    "STAFF_CHAT_LINK",
                    "VENUE_SETTINGS",
                    "ORDER_STATUS_UPDATE",
                    "ORDER_QUEUE_VIEW",
                    "BOOKING_VIEW",
                    "BOOKING_ARRIVAL_UPDATE",
                    "BOOKING_MANAGE",
                    "SHIFT_EXTENSION_VIEW",
                    "SHIFT_EXTENSION_CONFIRM",
                    "SHIFT_EXTENSION_SETTINGS",
                    "MENU_VIEW",
                    "MENU_MANAGE",
                    "MENU_AVAILABILITY_MANAGE",
                    "TABLE_VIEW",
                    "TABLE_MANAGE",
                    "TABLE_TOKEN_ROTATE",
                    "TABLE_TOKEN_ROTATE_ALL",
                    "TABLE_QR_EXPORT",
                ),
                venuesById.getValue(ownerVenueId).permissions.toSet(),
            )

            assertEquals("MANAGER", venuesById.getValue(managerVenueId).role)
            assertEquals(
                setOf(
                    "STAFF_CHAT_LINK",
                    "ORDER_STATUS_UPDATE",
                    "ORDER_QUEUE_VIEW",
                    "BOOKING_VIEW",
                    "BOOKING_ARRIVAL_UPDATE",
                    "BOOKING_MANAGE",
                    "SHIFT_EXTENSION_VIEW",
                    "SHIFT_EXTENSION_CONFIRM",
                    "SHIFT_EXTENSION_SETTINGS",
                    "MENU_VIEW",
                    "MENU_MANAGE",
                    "MENU_AVAILABILITY_MANAGE",
                    "TABLE_VIEW",
                    "TABLE_MANAGE",
                    "TABLE_QR_EXPORT",
                ),
                venuesById.getValue(managerVenueId).permissions.toSet(),
            )

            assertEquals("STAFF", venuesById.getValue(staffVenueId).role)
            assertEquals(
                setOf(
                    "ORDER_QUEUE_VIEW",
                    "ORDER_STATUS_UPDATE",
                    "BOOKING_VIEW",
                    "BOOKING_ARRIVAL_UPDATE",
                    "SHIFT_EXTENSION_VIEW",
                    "SHIFT_EXTENSION_CONFIRM",
                    "MENU_VIEW",
                    "MENU_AVAILABILITY_MANAGE",
                    "TABLE_VIEW",
                ),
                venuesById.getValue(staffVenueId).permissions.toSet(),
            )
        }

    @Test
    fun `venue me ignores deleted venue memberships`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-me-deleted")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER", VenueStatus.DELETED)
            val token = issueToken(config)

            val response =
                client.get("/api/venue/me") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `venue me maps legacy admin to manager permissions`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-admin-alias")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "ADMIN")
            val token = issueToken(config)

            val response =
                client.get("/api/venue/me") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(VenueMeResponse.serializer(), response.bodyAsText())
            val venue = payload.venues.single { it.venueId == venueId }
            assertEquals("MANAGER", venue.role)
            assertEquals(
                setOf(
                    "STAFF_CHAT_LINK",
                    "ORDER_STATUS_UPDATE",
                    "ORDER_QUEUE_VIEW",
                    "BOOKING_VIEW",
                    "BOOKING_ARRIVAL_UPDATE",
                    "BOOKING_MANAGE",
                    "SHIFT_EXTENSION_VIEW",
                    "SHIFT_EXTENSION_CONFIRM",
                    "SHIFT_EXTENSION_SETTINGS",
                    "MENU_VIEW",
                    "MENU_MANAGE",
                    "MENU_AVAILABILITY_MANAGE",
                    "TABLE_VIEW",
                    "TABLE_MANAGE",
                    "TABLE_QR_EXPORT",
                ),
                venue.permissions.toSet(),
            )
        }

    @Test
    fun `staff can list acknowledge and close staff calls`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-staff-calls")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val staffCallId = seedStaffCall(jdbcUrl, venueId, status = "NEW")
            val oldStaffCallId =
                seedStaffCall(
                    jdbcUrl,
                    venueId,
                    status = "NEW",
                    createdAt = Instant.now().minusSeconds(48 * 60 * 60),
                    tableNumber = 13,
                )
            val doneStaffCallId = seedStaffCall(jdbcUrl, venueId, status = "DONE", tableNumber = 14)
            val token = issueToken(config)

            val listResponse =
                client.get("/api/venue/$venueId/staff-calls") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, listResponse.status)
            val listPayload = json.decodeFromString(VenueStaffCallsResponse.serializer(), listResponse.bodyAsText())
            assertEquals(listOf(staffCallId), listPayload.items.map { it.id })
            assertTrue(oldStaffCallId !in listPayload.items.map { it.id })
            assertTrue(doneStaffCallId !in listPayload.items.map { it.id })
            assertEquals("NEW", listPayload.items.single().status)
            assertEquals("Консультация", listPayload.items.single().reasonLabel)

            val ackResponse =
                client.post("/api/venue/$venueId/staff-calls/$staffCallId/ack") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, ackResponse.status)
            val ackPayload = json.decodeFromString(VenueStaffCallActionResponse.serializer(), ackResponse.bodyAsText())
            assertTrue(ackPayload.applied)
            assertEquals("ACK", ackPayload.call.status)

            val doneResponse =
                client.post("/api/venue/$venueId/staff-calls/$staffCallId/done") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, doneResponse.status)
            val donePayload =
                json.decodeFromString(
                    VenueStaffCallActionResponse.serializer(),
                    doneResponse.bodyAsText(),
                )
            assertTrue(donePayload.applied)
            assertEquals("DONE", donePayload.call.status)

            val listAfterDoneResponse =
                client.get("/api/venue/$venueId/staff-calls") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, listAfterDoneResponse.status)
            val listAfterDonePayload =
                json.decodeFromString(VenueStaffCallsResponse.serializer(), listAfterDoneResponse.bodyAsText())
            assertEquals(emptyList(), listAfterDonePayload.items.map { it.id })
        }

    @Test
    fun `owner can acknowledge and close staff calls`() =
        assertVenueRoleCanAcknowledgeAndCloseStaffCall("OWNER", "venue-owner-staff-calls")

    @Test
    fun `manager can acknowledge and close staff calls`() =
        assertVenueRoleCanAcknowledgeAndCloseStaffCall("MANAGER", "venue-manager-staff-calls")

    @Test
    fun `done from new staff call is not applied`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-staff-call-invalid-transition")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val staffCallId = seedStaffCall(jdbcUrl, venueId, status = "NEW")
            val token = issueToken(config)

            val doneResponse =
                client.post("/api/venue/$venueId/staff-calls/$staffCallId/done") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, doneResponse.status)
            val payload =
                json.decodeFromString(VenueStaffCallActionResponse.serializer(), doneResponse.bodyAsText())
            assertFalse(payload.applied)
            assertEquals("NEW", payload.call.status)

            val listResponse =
                client.get("/api/venue/$venueId/staff-calls") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val listPayload = json.decodeFromString(VenueStaffCallsResponse.serializer(), listResponse.bodyAsText())
            assertEquals(listOf(staffCallId), listPayload.items.map { it.id })
        }

    @Test
    fun `staff cannot access staff calls from another venue`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-staff-calls-forbidden")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val foreignVenueId = seedVenueMembership(jdbcUrl, 777L, "STAFF")
            val token = issueToken(config)

            val response =
                client.get("/api/venue/$foreignVenueId/staff-calls") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `staff call reason labels preserve consultation coals and bill`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-staff-call-labels")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            seedStaffCall(jdbcUrl, venueId, status = "NEW", reason = "COME", tableNumber = 12)
            seedStaffCall(jdbcUrl, venueId, status = "NEW", reason = "COALS", tableNumber = 13)
            seedStaffCall(jdbcUrl, venueId, status = "NEW", reason = "BILL", tableNumber = 14)
            val token = issueToken(config)

            val response =
                client.get("/api/venue/$venueId/staff-calls") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(VenueStaffCallsResponse.serializer(), response.bodyAsText())
            assertEquals(
                setOf("Консультация", "Заменить угли", "Принести счёт"),
                payload.items.map { it.reasonLabel }.toSet(),
            )
        }

    private fun assertVenueRoleCanAcknowledgeAndCloseStaffCall(
        role: String,
        prefix: String,
    ) = testApplication {
        val jdbcUrl = buildJdbcUrl(prefix)
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, role)
        val staffCallId = seedStaffCall(jdbcUrl, venueId, status = "NEW")
        val token = issueToken(config)

        val ackResponse =
            client.post("/api/venue/$venueId/staff-calls/$staffCallId/ack") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
            }
        assertEquals(HttpStatusCode.OK, ackResponse.status)
        val ackPayload = json.decodeFromString(VenueStaffCallActionResponse.serializer(), ackResponse.bodyAsText())
        assertTrue(ackPayload.applied)
        assertEquals("ACK", ackPayload.call.status)

        val doneResponse =
            client.post("/api/venue/$venueId/staff-calls/$staffCallId/done") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
            }
        assertEquals(HttpStatusCode.OK, doneResponse.status)
        val donePayload =
            json.decodeFromString(VenueStaffCallActionResponse.serializer(), doneResponse.bodyAsText())
        assertTrue(donePayload.applied)
        assertEquals("DONE", donePayload.call.status)
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
        )
    }

    private fun issueToken(config: MapApplicationConfig): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(TELEGRAM_USER_ID).token
    }

    private fun seedVenueMembership(
        jdbcUrl: String,
        userId: Long,
        role: String,
        status: VenueStatus = VenueStatus.PUBLISHED,
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
            val venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setString(1, status.dbValue)
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
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
            return venueId
        }
    }

    private fun seedStaffCall(
        jdbcUrl: String,
        venueId: Long,
        status: String,
        createdAt: Instant = Instant.now(),
        tableNumber: Int = 12,
        reason: String = "COME",
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val tableId =
                connection.prepareStatement(
                    """
                    INSERT INTO venue_tables (venue_id, table_number, is_active)
                    VALUES (?, ?, true)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setInt(2, tableNumber)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert table")
                    }
                }
            return connection.prepareStatement(
                """
                INSERT INTO staff_calls (venue_id, table_id, reason, comment, status, created_at)
                VALUES (?, ?, ?, 'Нужна помощь', ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, tableId)
                statement.setString(3, reason)
                statement.setString(4, status)
                statement.setTimestamp(5, Timestamp.from(createdAt))
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert staff call")
                }
            }
        }
    }

    private fun linkStaffChat(
        jdbcUrl: String,
        venueId: Long,
        chatId: Long,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                UPDATE venues
                SET staff_chat_id = ?,
                    staff_chat_linked_at = CURRENT_TIMESTAMP,
                    staff_chat_linked_by_user_id = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, chatId)
                statement.setLong(2, userId)
                statement.setLong(3, venueId)
                statement.executeUpdate()
            }
        }
    }

    @Serializable
    private data class StaffChatLinkCodeResponse(
        val code: String,
        val expiresAt: String,
        val ttlSeconds: Long,
    )

    @Serializable
    private data class StaffChatStatusResponse(
        val venueId: Long,
        val isLinked: Boolean,
        val chatId: Long? = null,
        val maskedChatId: String? = null,
        val testCommand: String? = null,
    )

    @Serializable
    private data class StaffChatTestResponse(
        val result: String,
        val queued: Boolean,
        val message: String,
    )

    @Serializable
    private data class VenueMeResponse(
        val userId: Long,
        val venues: List<VenueAccessDto>,
    )

    @Serializable
    private data class VenueAccessDto(
        val venueId: Long,
        val venueName: String? = null,
        val venueCity: String? = null,
        val venueStatus: String? = null,
        val role: String,
        val permissions: List<String>,
    )

    @Serializable
    private data class VenueStaffCallsResponse(
        val items: List<VenueStaffCallDto>,
    )

    @Serializable
    private data class VenueStaffCallActionResponse(
        val call: VenueStaffCallDto,
        val applied: Boolean,
    )

    @Serializable
    private data class VenueStaffCallDto(
        val id: Long,
        val status: String,
        val reasonLabel: String,
    )

    private companion object {
        const val TELEGRAM_USER_ID: Long = 909L
    }
}
