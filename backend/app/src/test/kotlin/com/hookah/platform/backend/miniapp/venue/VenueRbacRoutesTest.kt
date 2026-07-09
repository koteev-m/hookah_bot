package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.VenueResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.location.VenueLocationProvider
import com.hookah.platform.backend.miniapp.venue.location.VenueLocationResolveProviderRequest
import com.hookah.platform.backend.miniapp.venue.location.VenueLocationResolveProviderResult
import com.hookah.platform.backend.miniapp.venue.location.VenueLocationResolvedItem
import com.hookah.platform.backend.miniapp.venue.location.VenueLocationSuggestProviderRequest
import com.hookah.platform.backend.miniapp.venue.location.VenueLocationSuggestProviderResult
import com.hookah.platform.backend.miniapp.venue.location.VenueLocationSuggestionItem
import com.hookah.platform.backend.module
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.telegram.StaffChatNotificationResult
import com.hookah.platform.backend.telegram.StaffChatNotifier
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `owner and manager can read venue feedback while staff and foreign venue are denied`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-feedback-rbac")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val foreignVenueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID + 1, "OWNER")
            seedSubmittedFeedback(jdbcUrl, venueId, guestUserId = 9010L)
            val token = issueToken(config)

            val ownerResponse =
                client.get("/api/venue/$venueId/feedback?filter=all") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, ownerResponse.status)
            val body = json.parseToJsonElement(ownerResponse.bodyAsText()).jsonObject
            assertEquals("1", body.getValue("summary").jsonObject.getValue("count").jsonPrimitive.content)
            val item = body.getValue("items").jsonArray.single().jsonObject
            assertEquals("5", item.getValue("rating").jsonPrimitive.content)
            assertEquals("Гость 9010", item.getValue("guestLabel").jsonPrimitive.content)
            assertEquals("Все отлично", item.getValue("comment").jsonPrimitive.content)
            assertEquals(2, item.getValue("tags").jsonArray.size)

            updateVenueMemberRole(jdbcUrl, venueId, TELEGRAM_USER_ID, "MANAGER")
            val managerResponse =
                client.get("/api/venue/$venueId/feedback?filter=low") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, managerResponse.status)

            updateVenueMemberRole(jdbcUrl, venueId, TELEGRAM_USER_ID, "STAFF")
            val staffResponse =
                client.get("/api/venue/$venueId/feedback") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.Forbidden, staffResponse.status)

            val foreignResponse =
                client.get("/api/venue/$foreignVenueId/feedback") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.Forbidden, foreignResponse.status)
        }

    @Test
    fun `owner can manage public review link and guest rating five sees it`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-public-review-url")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val guestUserId = 9020L
            seedUser(jdbcUrl, guestUserId)
            val visitId = seedCompletedVisit(jdbcUrl, venueId, guestUserId)
            val ownerToken = issueToken(config)
            val guestToken = issueToken(config, guestUserId)

            val saveResponse =
                client.put("/api/venue/$venueId/public-review-url") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"publicReviewUrl":"https://yandex.ru/maps/org/mix/reviews"}""")
                }
            assertEquals(HttpStatusCode.OK, saveResponse.status)
            val saved = json.parseToJsonElement(saveResponse.bodyAsText()).jsonObject
            assertEquals(
                "https://yandex.ru/maps/org/mix/reviews",
                saved.getValue("publicReviewUrl").jsonPrimitive.content,
            )

            val guestSubmit =
                client.post("/api/guest/visits/$visitId/feedback") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"rating":5}""")
                }
            assertEquals(HttpStatusCode.OK, guestSubmit.status)
            val feedback =
                json
                    .parseToJsonElement(guestSubmit.bodyAsText())
                    .jsonObject
                    .getValue("feedback")
                    .jsonObject
            assertEquals(
                "https://yandex.ru/maps/org/mix/reviews",
                feedback.getValue("publicReviewUrl").jsonPrimitive.content,
            )

            val clearResponse =
                client.delete("/api/venue/$venueId/public-review-url") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, clearResponse.status)
            val cleared = json.parseToJsonElement(clearResponse.bodyAsText()).jsonObject
            assertEquals(null, cleared["publicReviewUrl"]?.jsonPrimitive?.contentOrNull)
        }

    @Test
    fun `public review link settings reject unsafe values and are owner only`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-public-review-url-rbac")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val token = issueToken(config)

            val unsafe =
                client.put("/api/venue/$venueId/public-review-url") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"publicReviewUrl":"http://unsafe.example/reviews"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, unsafe.status)

            updateVenueMemberRole(jdbcUrl, venueId, TELEGRAM_USER_ID, "MANAGER")
            val managerRead =
                client.get("/api/venue/$venueId/public-review-url") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.Forbidden, managerRead.status)

            updateVenueMemberRole(jdbcUrl, venueId, TELEGRAM_USER_ID, "STAFF")
            val staffUpdate =
                client.put("/api/venue/$venueId/public-review-url") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"publicReviewUrl":"https://yandex.ru/maps/org/mix/reviews"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, staffUpdate.status)
        }

    @Test
    fun `owner and manager can open low feedback venue chat while staff and foreign venue are denied`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-feedback-follow-up")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val foreignVenueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID + 1, "OWNER")
            val feedbackId = seedSubmittedFeedback(jdbcUrl, venueId, guestUserId = 9030L, rating = 2)
            val ownerToken = issueToken(config)

            val ownerResponse =
                client.post("/api/venue/$venueId/feedback/$feedbackId/follow-up") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, ownerResponse.status)
            val ownerBody = json.parseToJsonElement(ownerResponse.bodyAsText()).jsonObject
            assertEquals("VENUE_CHAT", ownerBody.getValue("threadType").jsonPrimitive.content)
            assertEquals(1, countRows(jdbcUrl, "support_threads"))
            val threadId = ownerBody.getValue("threadId").jsonPrimitive.content.toLong()
            assertEquals(1, countRows(jdbcUrl, "support_messages"))
            assertEquals(0, countSupportThreadsByType(jdbcUrl, "SUPPORT_TICKET"))
            assertEquals(0, countRows(jdbcUrl, "telegram_outbox"))
            val context = supportMessageTexts(jdbcUrl, threadId).single()
            assertTrue(context.contains("Отзыв после визита"))
            assertTrue(context.contains("Оценка: 2/5"))
            assertTrue(context.contains("Теги: сервис"))
            assertTrue(context.contains("Комментарий: Все отлично"))
            assertTrue(context.contains("Дата визита: 2030-05-12"))

            updateVenueMemberRole(jdbcUrl, venueId, TELEGRAM_USER_ID, "MANAGER")
            val managerResponse =
                client.post("/api/venue/$venueId/feedback/$feedbackId/follow-up") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, managerResponse.status)
            assertEquals(1, countRows(jdbcUrl, "support_threads"))
            assertEquals(1, countRows(jdbcUrl, "support_messages"))

            updateVenueMemberRole(jdbcUrl, venueId, TELEGRAM_USER_ID, "STAFF")
            val staffResponse =
                client.post("/api/venue/$venueId/feedback/$feedbackId/follow-up") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, staffResponse.status)

            updateVenueMemberRole(jdbcUrl, venueId, TELEGRAM_USER_ID, "OWNER")
            val foreignResponse =
                client.post("/api/venue/$foreignVenueId/feedback/$feedbackId/follow-up") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, foreignResponse.status)
        }

    @Test
    fun `follow-up reuses active venue chat and creates new one when old chat is closed`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-feedback-follow-up-active")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val feedbackId = seedSubmittedFeedback(jdbcUrl, venueId, guestUserId = 9040L, rating = 1)
            val activeThreadId = seedVenueChat(jdbcUrl, venueId, guestUserId = 9040L, status = "IN_PROGRESS")
            val token = issueToken(config)

            val activeResponse =
                client.post("/api/venue/$venueId/feedback/$feedbackId/follow-up") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, activeResponse.status)
            val activeBody = json.parseToJsonElement(activeResponse.bodyAsText()).jsonObject
            assertEquals(activeThreadId, activeBody.getValue("threadId").jsonPrimitive.content.toLong())
            assertEquals(1, countRows(jdbcUrl, "support_threads"))
            assertEquals(1, supportMessageTexts(jdbcUrl, activeThreadId).count { it.contains("Отзыв после визита") })

            markSupportThreadStatus(jdbcUrl, activeThreadId, "CLOSED")
            val secondFeedbackId = seedSubmittedFeedback(jdbcUrl, venueId, guestUserId = 9040L, rating = 3)
            val closedResponse =
                client.post("/api/venue/$venueId/feedback/$secondFeedbackId/follow-up") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, closedResponse.status)
            val closedBody = json.parseToJsonElement(closedResponse.bodyAsText()).jsonObject
            val newThreadId = closedBody.getValue("threadId").jsonPrimitive.content.toLong()
            assertTrue(newThreadId != activeThreadId)
            assertEquals(2, countRows(jdbcUrl, "support_threads"))
            assertEquals(1, supportMessageTexts(jdbcUrl, newThreadId).count { it.contains("Оценка: 3/5") })
        }

    @Test
    fun `follow-up rejects non-low feedback`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-feedback-follow-up-rating")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val feedbackId = seedSubmittedFeedback(jdbcUrl, venueId, guestUserId = 9050L, rating = 4)
            val token = issueToken(config)

            val response =
                client.post("/api/venue/$venueId/feedback/$feedbackId/follow-up") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, countRows(jdbcUrl, "support_threads"))
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
                    countryCode = " ru ",
                    formattedAddress = " Россия, Москва, Новый Арбат, 24 ",
                    latitude = 55.7522,
                    longitude = 37.6156,
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
            assertEquals("RU", updated.countryCode)
            assertEquals("Россия, Москва, Новый Арбат, 24", updated.formattedAddress)
            assertEquals("Москва, Новый Арбат, 24", updated.displayAddress)
            assertEquals(55.7522, updated.latitude)
            assertEquals(37.6156, updated.longitude)
            assertEquals("https://yandex.ru/maps/?rtext=~55.7522,37.6156&rtt=auto", updated.routeUrl)
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
            assertEquals("RU", guestPayload.venue.countryCode)
            assertEquals("Москва, Новый Арбат, 24", guestPayload.venue.displayAddress)
            assertEquals("https://yandex.ru/maps/?rtext=~55.7522,37.6156&rtt=auto", guestPayload.venue.routeUrl)
            assertEquals("+7 999 000-00-00", guestPayload.venue.guestContact)
            assertEquals("Авторские чаши и спокойная посадка.", guestPayload.venue.cardDescription)
        }

    @Test
    fun `manager can use venue-scoped location suggestions and resolve`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-location-suggestions")
            val config = buildConfig(jdbcUrl)
            val provider = FakeVenueLocationProvider()

            environment { this.config = config }
            application { moduleWithOverrides(ModuleOverrides(venueLocationProvider = provider)) }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val token = issueToken(config)

            val suggestionsUrl =
                "/api/venue/$venueId/location/suggestions" +
                    "?kind=address" +
                    "&query=%D0%90%D1%80%D0%B1%D0%B0%D1%82" +
                    "&countryCode=ru" +
                    "&city=%D0%9C%D0%BE%D1%81%D0%BA%D0%B2%D0%B0" +
                    "&sessionToken=test-session"
            val suggestionsResponse =
                client.get(suggestionsUrl) {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, suggestionsResponse.status)
            val suggestions =
                json.decodeFromString(VenueLocationSuggestionsResponse.serializer(), suggestionsResponse.bodyAsText())
            assertFalse(suggestions.unavailable)
            assertEquals("Новый Арбат, 24", suggestions.items.single().address)
            assertEquals("RU", provider.suggestRequests.single().countryCode)
            assertEquals("test-session", provider.suggestRequests.single().sessionToken)

            val resolveResponse =
                client.post("/api/venue/$venueId/location/resolve") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            VenueLocationResolveRequest.serializer(),
                            VenueLocationResolveRequest(
                                providerUri = "ymapsbm1://geo?data=test",
                                query = "Новый Арбат, 24",
                                countryCode = "RU",
                                city = "Москва",
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, resolveResponse.status)
            val resolved =
                json.decodeFromString(VenueLocationResolveResponse.serializer(), resolveResponse.bodyAsText())
            assertEquals(55.7522, resolved.location?.latitude)
            assertEquals(37.6156, resolved.location?.longitude)
            assertEquals("ymapsbm1://geo?data=test", provider.resolveRequests.single().providerUri)
        }

    @Test
    fun `staff cannot use location provider routes`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-location-staff-denied")
            val config = buildConfig(jdbcUrl)
            val provider = FakeVenueLocationProvider()

            environment { this.config = config }
            application { moduleWithOverrides(ModuleOverrides(venueLocationProvider = provider)) }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val token = issueToken(config)

            val response =
                client.get("/api/venue/$venueId/location/suggestions?kind=city&query=%D0%9C%D0%BE&countryCode=RU") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
            assertEquals(emptyList(), provider.suggestRequests)
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
    fun `invalid public card coordinates preserve existing values`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-public-card-invalid-coordinates")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val token = issueToken(config)
            val invalid =
                VenuePublicCardSettingsUpdateRequest(
                    city = "Москва",
                    address = "Новый Арбат, 24",
                    countryCode = "RU",
                    formattedAddress = "Москва, Новый Арбат, 24",
                    latitude = 95.0,
                    longitude = 37.6156,
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
            assertNull(payload.latitude)
            assertNull(payload.longitude)
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
                    "SUPPORT_VIEW",
                    "SUPPORT_MANAGE",
                    "SHIFT_EXTENSION_VIEW",
                    "SHIFT_EXTENSION_CONFIRM",
                    "SHIFT_EXTENSION_SETTINGS",
                    "FEEDBACK_VIEW",
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
                    "SUPPORT_VIEW",
                    "SUPPORT_MANAGE",
                    "SHIFT_EXTENSION_VIEW",
                    "SHIFT_EXTENSION_CONFIRM",
                    "SHIFT_EXTENSION_SETTINGS",
                    "FEEDBACK_VIEW",
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
                    "SUPPORT_VIEW",
                    "SUPPORT_MANAGE",
                    "SHIFT_EXTENSION_VIEW",
                    "SHIFT_EXTENSION_CONFIRM",
                    "SHIFT_EXTENSION_SETTINGS",
                    "FEEDBACK_VIEW",
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
            val ackAudit = fetchStaffCallAuditRows(jdbcUrl)
            assertEquals(1, ackAudit.size)
            assertStaffCallAuditRow(
                row = ackAudit.single(),
                expectedActorUserId = TELEGRAM_USER_ID,
                expectedAction = STAFF_CALL_ACK_AUDIT_ACTION,
                expectedVenueId = venueId,
                expectedStaffCallId = staffCallId,
                expectedFromStatus = "NEW",
                expectedToStatus = "ACK",
                expectedSource = STAFF_CALL_AUDIT_SOURCE_VENUE_MINIAPP,
                expectedTableNumber = 12,
            )

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
            val doneAudit = fetchStaffCallAuditRows(jdbcUrl)
            assertEquals(2, doneAudit.size)
            assertStaffCallAuditRow(
                row = doneAudit[1],
                expectedActorUserId = TELEGRAM_USER_ID,
                expectedAction = STAFF_CALL_DONE_AUDIT_ACTION,
                expectedVenueId = venueId,
                expectedStaffCallId = staffCallId,
                expectedFromStatus = "ACK",
                expectedToStatus = "DONE",
                expectedSource = STAFF_CALL_AUDIT_SOURCE_VENUE_MINIAPP,
                expectedTableNumber = 12,
            )

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
            assertEquals(emptyList(), fetchStaffCallAuditRows(jdbcUrl))
        }

    @Test
    fun `staff cannot access or audit staff calls from another venue`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-staff-calls-forbidden")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            seedVenueMembership(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val foreignVenueId = seedVenueMembership(jdbcUrl, 777L, "STAFF")
            val foreignStaffCallId = seedStaffCall(jdbcUrl, foreignVenueId, status = "NEW")
            val token = issueToken(config)

            val response =
                client.get("/api/venue/$foreignVenueId/staff-calls") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)

            val ackResponse =
                client.post("/api/venue/$foreignVenueId/staff-calls/$foreignStaffCallId/ack") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, ackResponse.status)
            assertApiErrorEnvelope(ackResponse, ApiErrorCodes.FORBIDDEN)
            assertEquals(emptyList(), fetchStaffCallAuditRows(jdbcUrl))
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
                setOf("Консультация", "Заменить угли", "Запрос счёта"),
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

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long = TELEGRAM_USER_ID,
    ): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(userId).token
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
                VALUES (?, ?, ?, '')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, "guest$userId")
                statement.setString(3, "Гость $userId")
                statement.executeUpdate()
            }
        }
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

    private fun updateVenueMemberRole(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        role: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                UPDATE venue_members
                SET role = ?
                WHERE venue_id = ? AND user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, role)
                statement.setLong(2, venueId)
                statement.setLong(3, userId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedCompletedVisit(
        jdbcUrl: String,
        venueId: Long,
        guestUserId: Long,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            return connection.prepareStatement(
                """
                INSERT INTO visits (venue_id, user_id, source, occurred_at, service_date)
                VALUES (?, ?, 'ORDER_CLOSED', ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, guestUserId)
                statement.setTimestamp(3, Timestamp.from(Instant.parse("2030-05-12T18:00:00Z")))
                statement.setDate(4, java.sql.Date.valueOf(java.time.LocalDate.of(2030, 5, 12)))
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert visit")
                }
            }
        }
    }

    private fun seedSubmittedFeedback(
        jdbcUrl: String,
        venueId: Long,
        guestUserId: Long,
        rating: Int = 5,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO users (telegram_user_id, username, first_name)
                KEY (telegram_user_id)
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, guestUserId)
                statement.setString(2, "guest$guestUserId")
                statement.setString(3, "Гость $guestUserId")
                statement.executeUpdate()
            }
            val visitId =
                connection.prepareStatement(
                    """
                    INSERT INTO visits (venue_id, user_id, source, occurred_at, service_date)
                    VALUES (?, ?, 'ORDER_CLOSED', ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, guestUserId)
                    statement.setTimestamp(3, Timestamp.from(Instant.parse("2030-05-12T18:00:00Z")))
                    statement.setDate(4, java.sql.Date.valueOf(java.time.LocalDate.of(2030, 5, 12)))
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert visit")
                    }
                }
            return connection.prepareStatement(
                """
                INSERT INTO visit_feedback (visit_id, venue_id, user_id, rating, comment, status, tags_json)
                VALUES (?, ?, ?, ?, 'Все отлично', 'SUBMITTED', '["service","taste"]')
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, visitId)
                statement.setLong(2, venueId)
                statement.setLong(3, guestUserId)
                statement.setInt(4, rating)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert feedback")
                }
            }
        }
    }

    private fun countRows(
        jdbcUrl: String,
        tableName: String,
    ): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $tableName").use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    private fun countSupportThreadsByType(
        jdbcUrl: String,
        threadType: String,
    ): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM support_threads
                WHERE thread_type = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, threadType)
                statement.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    private fun seedVenueChat(
        jdbcUrl: String,
        venueId: Long,
        guestUserId: Long,
        status: String,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            return connection.prepareStatement(
                """
                INSERT INTO support_threads (
                    venue_id,
                    guest_user_id,
                    category,
                    status,
                    thread_type,
                    assignee_scope,
                    created_source,
                    title
                )
                VALUES (?, ?, 'OTHER', ?, 'VENUE_CHAT', 'VENUE', 'GUEST_MINIAPP', 'Старый чат')
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, guestUserId)
                statement.setString(3, status)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert support thread")
                }
            }
        }
    }

    private fun markSupportThreadStatus(
        jdbcUrl: String,
        threadId: Long,
        status: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                UPDATE support_threads
                SET status = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, status)
                statement.setLong(2, threadId)
                statement.executeUpdate()
            }
        }
    }

    private fun supportMessageTexts(
        jdbcUrl: String,
        threadId: Long,
    ): List<String> {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT text
                FROM support_messages
                WHERE thread_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.executeQuery().use { rs ->
                    val messages = mutableListOf<String>()
                    while (rs.next()) {
                        messages += rs.getString("text")
                    }
                    return messages
                }
            }
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

    private fun fetchStaffCallAuditRows(jdbcUrl: String): List<AuditRow> {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT actor_user_id, action, entity_type, entity_id, payload_json
                FROM audit_log
                WHERE action IN (?, ?)
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, STAFF_CALL_ACK_AUDIT_ACTION)
                statement.setString(2, STAFF_CALL_DONE_AUDIT_ACTION)
                statement.executeQuery().use { rs ->
                    val rows = mutableListOf<AuditRow>()
                    while (rs.next()) {
                        rows +=
                            AuditRow(
                                actorUserId = rs.getLong("actor_user_id"),
                                action = rs.getString("action"),
                                entityType = rs.getString("entity_type"),
                                entityId = rs.getLong("entity_id"),
                                payload =
                                    json.parseToJsonElement(rs.getString("payload_json"))
                                        .jsonObject,
                            )
                    }
                    return rows
                }
            }
        }
    }

    private fun assertStaffCallAuditRow(
        row: AuditRow,
        expectedActorUserId: Long,
        expectedAction: String,
        expectedVenueId: Long,
        expectedStaffCallId: Long,
        expectedFromStatus: String,
        expectedToStatus: String,
        expectedSource: String,
        expectedTableNumber: Int,
    ) {
        assertEquals(expectedActorUserId, row.actorUserId)
        assertEquals(expectedAction, row.action)
        assertEquals("venue", row.entityType)
        assertEquals(expectedVenueId, row.entityId)
        assertEquals(expectedVenueId.toString(), row.payload.getValue("venueId").jsonPrimitive.content)
        assertEquals(expectedStaffCallId.toString(), row.payload.getValue("staffCallId").jsonPrimitive.content)
        assertEquals(expectedFromStatus, row.payload.getValue("fromStatus").jsonPrimitive.content)
        assertEquals(expectedToStatus, row.payload.getValue("toStatus").jsonPrimitive.content)
        assertEquals(expectedSource, row.payload.getValue("source").jsonPrimitive.content)
        assertEquals(expectedTableNumber.toString(), row.payload.getValue("tableNumber").jsonPrimitive.content)
        assertFalse("comment" in row.payload)
        assertFalse("guestDisplayName" in row.payload)
    }

    private data class AuditRow(
        val actorUserId: Long,
        val action: String,
        val entityType: String,
        val entityId: Long,
        val payload: JsonObject,
    )

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

    private class FakeVenueLocationProvider : VenueLocationProvider {
        val suggestRequests = mutableListOf<VenueLocationSuggestProviderRequest>()
        val resolveRequests = mutableListOf<VenueLocationResolveProviderRequest>()

        override suspend fun suggest(
            request: VenueLocationSuggestProviderRequest,
        ): VenueLocationSuggestProviderResult {
            suggestRequests += request
            return VenueLocationSuggestProviderResult(
                items =
                    listOf(
                        VenueLocationSuggestionItem(
                            id = "ymapsbm1://geo?data=test",
                            title = "Новый Арбат, 24",
                            subtitle = "Москва",
                            countryCode = "RU",
                            city = "Москва",
                            address = "Новый Арбат, 24",
                            formattedAddress = "Москва, Новый Арбат, 24",
                            providerUri = "ymapsbm1://geo?data=test",
                        ),
                    ),
            )
        }

        override suspend fun resolve(
            request: VenueLocationResolveProviderRequest,
        ): VenueLocationResolveProviderResult {
            resolveRequests += request
            return VenueLocationResolveProviderResult(
                location =
                    VenueLocationResolvedItem(
                        countryCode = "RU",
                        city = "Москва",
                        address = "Новый Арбат, 24",
                        formattedAddress = "Москва, Новый Арбат, 24",
                        latitude = 55.7522,
                        longitude = 37.6156,
                    ),
            )
        }
    }

    private companion object {
        const val TELEGRAM_USER_ID: Long = 909L
    }
}
