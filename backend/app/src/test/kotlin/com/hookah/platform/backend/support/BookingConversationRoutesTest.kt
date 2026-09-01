package com.hookah.platform.backend.support

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BookingConversationRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `v126 smoke rechecks old jwt before main conversation writes and venue unread work`() {
        val jdbcUrl = buildJdbcUrl("maintenance-old-jwt-main-conversation")
        val productConfig =
            buildConfig(
                jdbcUrl,
                "telegram.trafficPolicy" to "PRODUCT",
                "staging.maintenance.mode" to "OFF",
            )
        val deniedGuestToken = issueToken(productConfig, GUEST_A)
        val deniedManagerToken = issueToken(productConfig, MANAGER_A)
        val allowedManagerToken = issueToken(productConfig, MAINTENANCE_ALLOWED_MANAGER)

        testApplication {
            val maintenanceConfig =
                buildConfig(
                    jdbcUrl,
                    "telegram.trafficPolicy" to "PRODUCT",
                    "staging.maintenance.mode" to "V126_SMOKE",
                    "staging.maintenance.allowedUserIds" to MAINTENANCE_ALLOWED_MANAGER.toString(),
                    "staging.maintenance.allowedChatIds" to MAINTENANCE_ALLOWED_MANAGER.toString(),
                )
            environment { config = maintenanceConfig }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl, "Maintenance Venue")
            seedUsers(jdbcUrl, GUEST_A, MANAGER_A, MAINTENANCE_ALLOWED_MANAGER)
            seedVenueMember(jdbcUrl, venueId, MANAGER_A, "MANAGER")
            seedVenueMember(jdbcUrl, venueId, MAINTENANCE_ALLOWED_MANAGER, "MANAGER")
            val stateBefore = lookupMutationCounts(jdbcUrl)

            val allowedUnread =
                client.get("/api/venue/$venueId/support/unread-count") {
                    headers { append(HttpHeaders.Authorization, "Bearer $allowedManagerToken") }
                }
            assertEquals(HttpStatusCode.OK, allowedUnread.status, allowedUnread.bodyAsText())
            assertEquals(stateBefore, lookupMutationCounts(jdbcUrl))

            val deniedSupportCreate =
                client.post("/api/guest/support/threads") {
                    authorizedJson(deniedGuestToken)
                    setBody(
                        """
                        {
                          "category":"MINIAPP_TECHNICAL",
                          "title":"Maintenance denied",
                          "message":"must-not-reach-support-repository"
                        }
                        """.trimIndent(),
                    )
                }
            assertMaintenanceUnavailable(deniedSupportCreate, "must-not-reach-support-repository")
            assertEquals(stateBefore, lookupMutationCounts(jdbcUrl))

            val deniedUnread =
                client.get("/api/venue/$venueId/support/unread-count") {
                    headers { append(HttpHeaders.Authorization, "Bearer $deniedManagerToken") }
                }
            assertMaintenanceUnavailable(deniedUnread, "Maintenance Venue")
            assertEquals(stateBefore, lookupMutationCounts(jdbcUrl))
        }
    }

    @Test
    fun `guest open keeps one isolated booking thread per booking and tenant`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("booking-conversation-identity")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueA = seedVenue(jdbcUrl, "Booking Venue A")
            val venueB = seedVenue(jdbcUrl, "Booking Venue B")
            seedUsers(jdbcUrl, GUEST_A, GUEST_B, MANAGER_A, MANAGER_B, STAFF_B, PLATFORM_OWNER)
            seedVenueMember(jdbcUrl, venueA, MANAGER_A, "MANAGER")
            seedVenueMember(jdbcUrl, venueB, MANAGER_B, "MANAGER")
            seedVenueMember(jdbcUrl, venueB, STAFF_B, "STAFF")

            val guestAToken = issueToken(config, GUEST_A)
            val guestBToken = issueToken(config, GUEST_B)
            val managerAToken = issueToken(config, MANAGER_A)
            val managerBToken = issueToken(config, MANAGER_B)
            val commonTime = "2030-01-10T18:30:00Z"
            val guestABookingA1 = createBooking(guestAToken, venueA, commonTime)
            val guestABookingA2 = createBooking(guestAToken, venueA, "2030-01-10T16:30:00Z")
            val guestBBookingA = createBooking(guestBToken, venueA, commonTime)
            val guestABookingB = createBooking(guestAToken, venueB, commonTime)

            val firstOpen = openBookingThread(guestAToken, guestABookingA1)
            val repeatedOpen = openBookingThread(guestAToken, guestABookingA1)
            val secondBookingOpen = openBookingThread(guestAToken, guestABookingA2)
            val secondGuestOpen = openBookingThread(guestBToken, guestBBookingA)
            val secondVenueOpen = openBookingThread(guestAToken, guestABookingB)

            assertTrue(firstOpen.getValue("messages").jsonArray.isEmpty())
            val firstThreadId = threadId(firstOpen)
            assertEquals(firstThreadId, threadId(repeatedOpen))
            assertNotEquals(firstThreadId, threadId(secondBookingOpen))
            assertNotEquals(firstThreadId, threadId(secondGuestOpen))
            assertNotEquals(firstThreadId, threadId(secondVenueOpen))
            assertEquals(
                setOf(
                    firstThreadId,
                    threadId(secondBookingOpen),
                    threadId(secondGuestOpen),
                    threadId(secondVenueOpen),
                ),
                setOf(
                    bookingThread(jdbcUrl, guestABookingA1).threadId,
                    bookingThread(jdbcUrl, guestABookingA2).threadId,
                    bookingThread(jdbcUrl, guestBBookingA).threadId,
                    bookingThread(jdbcUrl, guestABookingB).threadId,
                ),
            )
            assertEquals(ThreadOwnership(firstThreadId, venueA, GUEST_A), bookingThread(jdbcUrl, guestABookingA1))
            assertEquals(
                ThreadOwnership(threadId(secondBookingOpen), venueA, GUEST_A),
                bookingThread(jdbcUrl, guestABookingA2),
            )
            assertEquals(
                ThreadOwnership(threadId(secondGuestOpen), venueA, GUEST_B),
                bookingThread(jdbcUrl, guestBBookingA),
            )
            assertEquals(
                ThreadOwnership(threadId(secondVenueOpen), venueB, GUEST_A),
                bookingThread(jdbcUrl, guestABookingB),
            )
            assertEquals(4, bookingThreadCount(jdbcUrl))
            assertEquals(0, tableCount(jdbcUrl, "support_messages"))

            assertEquals(
                setOf(firstThreadId, threadId(secondBookingOpen), threadId(secondVenueOpen)),
                listBookingThreadIds("/api/guest/support/threads?surface=CONVERSATIONS", guestAToken),
            )
            assertEquals(
                setOf(threadId(secondGuestOpen)),
                listBookingThreadIds("/api/guest/support/threads?surface=CONVERSATIONS", guestBToken),
            )
            assertEquals(
                setOf(firstThreadId, threadId(secondBookingOpen), threadId(secondGuestOpen)),
                listBookingThreadIds("/api/venue/$venueA/support/threads", managerAToken),
            )
            assertEquals(
                setOf(threadId(secondVenueOpen)),
                listBookingThreadIds("/api/venue/$venueB/support/threads", managerBToken),
            )
        }

    @Test
    fun `same service day bookings keep distinct stable labels across booking and conversation DTOs`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("booking-display-label-parity")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl, "Label Venue")
            seedUsers(jdbcUrl, GUEST_A, MANAGER_A)
            seedVenueMember(jdbcUrl, venueId, MANAGER_A, "MANAGER")
            val guestToken = issueToken(config, GUEST_A)
            val managerToken = issueToken(config, MANAGER_A)
            val firstBookingId = createBooking(guestToken, venueId, "2030-01-10T18:30:00Z")
            val secondBookingId = createBooking(guestToken, venueId, "2030-01-10T19:30:00Z")
            val firstThread = openBookingThread(guestToken, firstBookingId)
            val secondThread = openBookingThread(guestToken, secondBookingId)
            val firstThreadId = threadId(firstThread)
            val secondThreadId = threadId(secondThread)
            val expectedLabels =
                mapOf(
                    firstBookingId to "Бронь №1 · 10.01.2030, 21:30",
                    secondBookingId to "Бронь №2 · 10.01.2030, 22:30",
                )

            assertNotEquals(firstBookingId, secondBookingId)
            assertNotEquals(firstThreadId, secondThreadId)
            assertEquals(
                expectedLabels[firstBookingId],
                firstThread.getValue("thread").jsonObject.getValue("contextLabel").jsonPrimitive.content,
            )
            assertEquals(
                expectedLabels[secondBookingId],
                secondThread.getValue("thread").jsonObject.getValue("contextLabel").jsonPrimitive.content,
            )

            suspend fun bookingLabels(
                path: String,
                token: String,
            ): Map<Long, String> {
                val response =
                    client.get(path) {
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                return json.parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .associate { item ->
                        val value = item.jsonObject
                        value.getValue("bookingId").jsonPrimitive.content.toLong() to
                            value.getValue("displayLabel").jsonPrimitive.content
                    }
            }

            suspend fun conversationLabels(
                path: String,
                token: String,
            ): Map<Long, String> {
                val response =
                    client.get(path) {
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                return json.parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .associate { item ->
                        val value = item.jsonObject
                        val booking = value.getValue("booking").jsonObject
                        val contextLabel = value.getValue("contextLabel").jsonPrimitive.content
                        assertEquals(contextLabel, booking.getValue("displayLabel").jsonPrimitive.content)
                        value.getValue("bookingId").jsonPrimitive.content.toLong() to contextLabel
                    }
            }

            val guestBookingLabels = bookingLabels("/api/guest/bookings", guestToken)
            val venueBookingLabels = bookingLabels("/api/venue/bookings?venueId=$venueId", managerToken)
            val guestConversationLabels =
                conversationLabels("/api/guest/support/threads?surface=CONVERSATIONS", guestToken)
            val venueConversationLabels =
                conversationLabels(
                    "/api/venue/$venueId/support/threads?threadType=BOOKING_THREAD",
                    managerToken,
                )

            assertEquals(expectedLabels, guestBookingLabels)
            assertEquals(expectedLabels, venueBookingLabels)
            assertEquals(expectedLabels, guestConversationLabels)
            assertEquals(expectedLabels, venueConversationLabels)
            assertEquals(
                venueConversationLabels,
                conversationLabels(
                    "/api/venue/$venueId/support/threads?filter=active&threadType=BOOKING_THREAD",
                    managerToken,
                ),
            )
            assertEquals(
                venueBookingLabels,
                bookingLabels("/api/venue/bookings?venueId=$venueId", managerToken),
            )

            deleteVenueSettings(jdbcUrl, venueId)
            val productFallbackLabels =
                mapOf(
                    firstBookingId to "Бронь №1 · 10.01.2030, 21:30",
                    secondBookingId to "Бронь №2 · 10.01.2030, 22:30",
                )
            assertEquals(productFallbackLabels, bookingLabels("/api/guest/bookings", guestToken))
            assertEquals(
                productFallbackLabels,
                bookingLabels("/api/venue/bookings?venueId=$venueId", managerToken),
            )
            assertEquals(
                productFallbackLabels,
                conversationLabels("/api/guest/support/threads?surface=CONVERSATIONS", guestToken),
            )
            assertEquals(
                productFallbackLabels,
                conversationLabels(
                    "/api/venue/$venueId/support/threads?threadType=BOOKING_THREAD",
                    managerToken,
                ),
            )
        }

    @Test
    fun `venue conversation unread count is actor scoped booking isolated and excludes support`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-conversation-unread")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueA = seedVenue(jdbcUrl, "Unread Venue A")
            val venueB = seedVenue(jdbcUrl, "Unread Venue B")
            seedUsers(jdbcUrl, GUEST_A, MANAGER_A, MANAGER_B, STAFF_B, PLATFORM_OWNER)
            seedVenueMember(jdbcUrl, venueA, MANAGER_A, "MANAGER")
            seedVenueMember(jdbcUrl, venueA, STAFF_B, "STAFF")
            seedVenueMember(jdbcUrl, venueB, MANAGER_B, "MANAGER")
            val guestToken = issueToken(config, GUEST_A)
            val managerAToken = issueToken(config, MANAGER_A)
            val managerBToken = issueToken(config, MANAGER_B)
            val staffToken = issueToken(config, STAFF_B)
            val platformToken = issueToken(config, PLATFORM_OWNER)
            val firstBookingId = createBooking(guestToken, venueA, "2030-01-10T18:30:00Z")
            val secondBookingId = createBooking(guestToken, venueA, "2030-01-10T19:30:00Z")
            val thirdBookingId = createBooking(guestToken, venueA, "2030-01-10T19:45:00Z")
            val firstThreadId = threadId(openBookingThread(guestToken, firstBookingId))
            val secondThreadId = threadId(openBookingThread(guestToken, secondBookingId))
            val thirdThreadId = threadId(openBookingThread(guestToken, thirdBookingId))

            val guestMessage =
                client.post("/api/guest/support/threads/$firstThreadId/messages") {
                    authorizedJson(guestToken)
                    setBody(bookingMessageBody("Unread booking message"))
                }
            assertEquals(HttpStatusCode.OK, guestMessage.status, guestMessage.bodyAsText())
            val newerGuestMessage =
                client.post("/api/guest/support/threads/$secondThreadId/messages") {
                    authorizedJson(guestToken)
                    setBody(bookingMessageBody("Newer unread booking message"))
                }
            assertEquals(HttpStatusCode.OK, newerGuestMessage.status, newerGuestMessage.bodyAsText())
            val newestReadMessage =
                client.post("/api/venue/$venueA/support/threads/$thirdThreadId/messages") {
                    authorizedJson(managerAToken)
                    setBody(bookingMessageBody("Newest already-read venue reply"))
                }
            assertEquals(HttpStatusCode.OK, newestReadMessage.status, newestReadMessage.bodyAsText())
            val supportTicketId = seedSupportTicketMessage(jdbcUrl, venueA, GUEST_A)
            val readsBeforeRejectedSupportDetail = tableCount(jdbcUrl, "support_thread_reads")
            val rejectedSupportDetail =
                client.get(
                    "/api/venue/$venueA/support/threads/$supportTicketId" +
                        "?threadTypes=BOOKING_THREAD,VENUE_CHAT",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertDeniedWithoutFacts(
                rejectedSupportDetail,
                HttpStatusCode.NotFound,
                "Platform support ticket",
                "Platform-only support message",
            )
            assertEquals(readsBeforeRejectedSupportDetail, tableCount(jdbcUrl, "support_thread_reads"))

            suspend fun unreadCount(
                path: String,
                token: String,
            ): Pair<HttpResponse, Int?> {
                val response =
                    client.get(path) {
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    }
                val count =
                    if (response.status == HttpStatusCode.OK) {
                        json.parseToJsonElement(response.bodyAsText())
                            .jsonObject
                            .getValue("unreadCount")
                            .jsonPrimitive
                            .content
                            .toInt()
                    } else {
                        null
                    }
                return response to count
            }

            val (venueAUnreadResponse, venueAUnread) =
                unreadCount("/api/venue/$venueA/support/unread-count", managerAToken)
            assertEquals(HttpStatusCode.OK, venueAUnreadResponse.status, venueAUnreadResponse.bodyAsText())
            assertEquals("no-store", venueAUnreadResponse.headers[HttpHeaders.CacheControl])
            assertEquals(2, venueAUnread)
            assertEquals(
                0,
                unreadCount("/api/venue/$venueB/support/unread-count", managerBToken).second,
            )

            val inboxResponse =
                client.get(
                    "/api/venue/$venueA/support/threads?threadTypes=BOOKING_THREAD,VENUE_CHAT",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertEquals(HttpStatusCode.OK, inboxResponse.status, inboxResponse.bodyAsText())
            val inbox =
                json.parseToJsonElement(inboxResponse.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .map { it.jsonObject }
            assertEquals(listOf(secondThreadId, firstThreadId, thirdThreadId), inbox.map { it.threadIdValue() })
            assertEquals(listOf("1", "1", "0"), inbox.map { it.getValue("unreadCount").jsonPrimitive.content })
            assertTrue(inbox.none { it.getValue("threadId").jsonPrimitive.content.toLong() == supportTicketId })

            alignThreadLastMessageAt(jdbcUrl, firstThreadId, secondThreadId)
            val deterministicTieResponse =
                client.get(
                    "/api/venue/$venueA/support/threads?threadTypes=BOOKING_THREAD,VENUE_CHAT",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertEquals(HttpStatusCode.OK, deterministicTieResponse.status, deterministicTieResponse.bodyAsText())
            val deterministicTieThreadIds =
                json.parseToJsonElement(deterministicTieResponse.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .map { it.jsonObject.threadIdValue() }
            assertEquals(listOf(secondThreadId, firstThreadId, thirdThreadId), deterministicTieThreadIds)

            val readsBeforeOpen = tableCount(jdbcUrl, "support_thread_reads")
            val openExact =
                client.get(
                    "/api/venue/$venueA/support/threads/$firstThreadId" +
                        "?threadTypes=BOOKING_THREAD,VENUE_CHAT",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertEquals(HttpStatusCode.OK, openExact.status, openExact.bodyAsText())
            assertEquals("0", openExact.threadJson().getValue("unreadCount").jsonPrimitive.content)
            assertEquals(1, unreadCount("/api/venue/$venueA/support/unread-count", managerAToken).second)
            val readsAfterOpen = tableCount(jdbcUrl, "support_thread_reads")
            assertEquals(readsBeforeOpen + 1, readsAfterOpen)

            val afterFirstOpen =
                client.get(
                    "/api/venue/$venueA/support/threads?threadTypes=BOOKING_THREAD,VENUE_CHAT",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertEquals(HttpStatusCode.OK, afterFirstOpen.status, afterFirstOpen.bodyAsText())
            val unreadByThread =
                json.parseToJsonElement(afterFirstOpen.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .associate { item ->
                        item.jsonObject.threadIdValue() to
                            item.jsonObject.getValue("unreadCount").jsonPrimitive.content.toInt()
                    }
            assertEquals(0, unreadByThread[firstThreadId])
            assertEquals(1, unreadByThread[secondThreadId])
            assertEquals(0, unreadByThread[thirdThreadId])

            val repeatOpen =
                client.get(
                    "/api/venue/$venueA/support/threads/$firstThreadId" +
                        "?threadTypes=BOOKING_THREAD,VENUE_CHAT",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertEquals(HttpStatusCode.OK, repeatOpen.status, repeatOpen.bodyAsText())
            assertEquals(readsAfterOpen, tableCount(jdbcUrl, "support_thread_reads"))
            assertEquals(1, unreadCount("/api/venue/$venueA/support/unread-count", managerAToken).second)

            val openSecond =
                client.get(
                    "/api/venue/$venueA/support/threads/$secondThreadId" +
                        "?threadTypes=BOOKING_THREAD,VENUE_CHAT",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertEquals(HttpStatusCode.OK, openSecond.status, openSecond.bodyAsText())
            assertEquals(0, unreadCount("/api/venue/$venueA/support/unread-count", managerAToken).second)

            val foreign = unreadCount("/api/venue/$venueA/support/unread-count", managerBToken).first
            assertDeniedWithoutFacts(foreign, HttpStatusCode.Forbidden, "unreadCount", "Unread booking message")
            val staff = unreadCount("/api/venue/$venueA/support/unread-count", staffToken).first
            assertDeniedWithoutFacts(staff, HttpStatusCode.Forbidden, "unreadCount", "Unread booking message")

            val platformResponse =
                client.get("/api/platform/support/threads") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }
            assertEquals(HttpStatusCode.OK, platformResponse.status, platformResponse.bodyAsText())
            val platformThreadIds =
                json.parseToJsonElement(platformResponse.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .map { it.jsonObject.getValue("threadId").jsonPrimitive.content.toLong() }
            assertEquals(listOf(supportTicketId), platformThreadIds)
            assertFalse(firstThreadId in platformThreadIds)
            assertFalse(secondThreadId in platformThreadIds)
            assertFalse(thirdThreadId in platformThreadIds)
        }

    @Test
    fun `ordinary Guest surface contract rejects cross inbox opens without raw marker mutation or message facts`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-surface-contract")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl, "Guest Surface Venue")
            seedUsers(jdbcUrl, GUEST_A, MANAGER_A, PLATFORM_OWNER)
            seedVenueMember(jdbcUrl, venueId, MANAGER_A, "MANAGER")
            val guestToken = issueToken(config, GUEST_A)
            val managerToken = issueToken(config, MANAGER_A)
            val platformToken = issueToken(config, PLATFORM_OWNER)

            val bookingId = createBooking(guestToken, venueId, "2030-01-10T18:30:00Z")
            val bookingThreadId = threadId(openBookingThread(guestToken, bookingId))
            val bookingSecret = "booking-surface-secret"
            val bookingReply =
                client.post("/api/venue/$venueId/support/threads/$bookingThreadId/messages") {
                    authorizedJson(managerToken)
                    setBody(bookingMessageBody(bookingSecret))
                }
            assertEquals(HttpStatusCode.OK, bookingReply.status, bookingReply.bodyAsText())
            val bookingMessageId = responseMessageId(bookingReply)

            val venueChatCreate =
                client.post("/api/guest/support/venue-chats") {
                    authorizedJson(guestToken)
                    setBody("""{"venueId":$venueId}""")
                }
            assertEquals(HttpStatusCode.OK, venueChatCreate.status, venueChatCreate.bodyAsText())
            val venueChatThreadId = threadId(json.parseToJsonElement(venueChatCreate.bodyAsText()).jsonObject)
            val venueChatSecret = "venue-chat-surface-secret"
            val venueChatReply =
                client.post("/api/venue/$venueId/support/threads/$venueChatThreadId/messages") {
                    authorizedJson(managerToken)
                    setBody("""{"message":"$venueChatSecret"}""")
                }
            assertEquals(HttpStatusCode.OK, venueChatReply.status, venueChatReply.bodyAsText())
            val venueChatMessageId = responseMessageId(venueChatReply)

            val supportCreate =
                client.post("/api/guest/support/threads") {
                    authorizedJson(guestToken)
                    setBody(
                        """
                        {
                          "category":"MINIAPP_TECHNICAL",
                          "title":"Surface ticket",
                          "message":"support-initial"
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, supportCreate.status, supportCreate.bodyAsText())
            val supportThreadId = threadId(json.parseToJsonElement(supportCreate.bodyAsText()).jsonObject)
            val supportSecret = "support-surface-secret"
            val platformReply =
                client.post("/api/platform/support/threads/$supportThreadId/messages") {
                    authorizedJson(platformToken)
                    setBody("""{"message":"$supportSecret"}""")
                }
            assertEquals(HttpStatusCode.OK, platformReply.status, platformReply.bodyAsText())
            val supportMessageId = responseMessageId(platformReply)

            val bookingBefore = assertNotNull(threadReadMarker(jdbcUrl, bookingThreadId, GUEST_A))
            val venueChatBefore = assertNotNull(threadReadMarker(jdbcUrl, venueChatThreadId, GUEST_A))
            val supportBefore = assertNotNull(threadReadMarker(jdbcUrl, supportThreadId, GUEST_A))
            val auditBefore = tableCount(jdbcUrl, "audit_log")
            val outboxBefore = tableCount(jdbcUrl, "telegram_outbox")

            val supportViaConversations =
                client.get("/api/guest/support/threads/$supportThreadId?surface=CONVERSATIONS") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                }
            assertDeniedWithoutFacts(
                supportViaConversations,
                HttpStatusCode.NotFound,
                supportSecret,
                "support-initial",
            )
            assertEquals(supportBefore, threadReadMarker(jdbcUrl, supportThreadId, GUEST_A))

            val bookingViaSupport =
                client.get("/api/guest/support/threads/$bookingThreadId?surface=SUPPORT") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                }
            assertDeniedWithoutFacts(bookingViaSupport, HttpStatusCode.NotFound, bookingSecret)
            assertEquals(bookingBefore, threadReadMarker(jdbcUrl, bookingThreadId, GUEST_A))

            val venueChatViaSupport =
                client.get("/api/guest/support/threads/$venueChatThreadId?surface=SUPPORT") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                }
            assertDeniedWithoutFacts(venueChatViaSupport, HttpStatusCode.NotFound, venueChatSecret)
            assertEquals(venueChatBefore, threadReadMarker(jdbcUrl, venueChatThreadId, GUEST_A))
            assertEquals(auditBefore, tableCount(jdbcUrl, "audit_log"))
            assertEquals(outboxBefore, tableCount(jdbcUrl, "telegram_outbox"))

            val correctBooking =
                client.get("/api/guest/support/threads/$bookingThreadId?surface=CONVERSATIONS") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                }
            assertEquals(HttpStatusCode.OK, correctBooking.status, correctBooking.bodyAsText())
            assertTrue(correctBooking.bodyAsText().contains(bookingSecret))
            assertEquals(
                bookingMessageId,
                assertNotNull(threadReadMarker(jdbcUrl, bookingThreadId, GUEST_A)).lastReadMessageId,
            )
            assertEquals(venueChatBefore, threadReadMarker(jdbcUrl, venueChatThreadId, GUEST_A))
            assertEquals(supportBefore, threadReadMarker(jdbcUrl, supportThreadId, GUEST_A))

            val correctVenueChat =
                client.get("/api/guest/support/threads/$venueChatThreadId?surface=CONVERSATIONS") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                }
            assertEquals(HttpStatusCode.OK, correctVenueChat.status, correctVenueChat.bodyAsText())
            assertTrue(correctVenueChat.bodyAsText().contains(venueChatSecret))
            assertEquals(
                venueChatMessageId,
                assertNotNull(threadReadMarker(jdbcUrl, venueChatThreadId, GUEST_A)).lastReadMessageId,
            )

            val correctSupport =
                client.get("/api/guest/support/threads/$supportThreadId?surface=SUPPORT") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                }
            assertEquals(HttpStatusCode.OK, correctSupport.status, correctSupport.bodyAsText())
            assertTrue(correctSupport.bodyAsText().contains(supportSecret))
            assertEquals(
                supportMessageId,
                assertNotNull(threadReadMarker(jdbcUrl, supportThreadId, GUEST_A)).lastReadMessageId,
            )
        }

    @Test
    fun `exact Guest and Venue batches reconcile beyond capped inventory and stay read only`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("booking-conversation-exact-lookup")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueA = seedVenue(jdbcUrl, "Lookup Venue A")
            val venueB = seedVenue(jdbcUrl, "Lookup Venue B")
            seedUsers(jdbcUrl, GUEST_A, GUEST_B, MANAGER_A, MANAGER_B, STAFF_B, PLATFORM_OWNER)
            seedVenueMember(jdbcUrl, venueA, MANAGER_A, "MANAGER")
            seedVenueMember(jdbcUrl, venueA, PLATFORM_OWNER, "OWNER")
            seedVenueMember(jdbcUrl, venueA, STAFF_B, "STAFF")
            seedVenueMember(jdbcUrl, venueB, MANAGER_B, "MANAGER")

            val guestAToken = issueToken(config, GUEST_A)
            val guestBToken = issueToken(config, GUEST_B)
            val managerAToken = issueToken(config, MANAGER_A)
            val ownerAToken = issueToken(config, PLATFORM_OWNER)
            val managerBToken = issueToken(config, MANAGER_B)
            val staffAToken = issueToken(config, STAFF_B)
            val targetBookingId = createBooking(guestAToken, venueA, "2030-01-10T15:30:00Z")
            val noThreadBookingId = createBooking(guestAToken, venueA, "2030-01-10T16:30:00Z")
            val foreignBookingId = createBooking(guestBToken, venueB, "2030-01-10T17:30:00Z")
            val targetThreadId = threadId(openBookingThread(guestAToken, targetBookingId))
            ageThreadBeforeInventory(jdbcUrl, targetThreadId)
            val newerBookingIds = seedNewerBookingThreads(jdbcUrl, venueA, GUEST_A, 100)

            val venueBookings =
                client.get("/api/venue/bookings?venueId=$venueA") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertEquals(HttpStatusCode.OK, venueBookings.status, venueBookings.bodyAsText())
            assertTrue(
                json.parseToJsonElement(venueBookings.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .any { it.jsonObject.getValue("bookingId").jsonPrimitive.content.toLong() == targetBookingId },
            )

            val cappedInventory =
                client.get("/api/venue/$venueA/support/threads?threadType=BOOKING_THREAD") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertEquals(HttpStatusCode.OK, cappedInventory.status, cappedInventory.bodyAsText())
            val cappedIds =
                json.parseToJsonElement(cappedInventory.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .map { it.jsonObject.getValue("threadId").jsonPrimitive.content.toLong() }
            assertEquals(100, cappedIds.size)
            assertFalse(targetThreadId in cappedIds)

            val mutationCountsBefore = lookupMutationCounts(jdbcUrl)
            val targetStateBefore = threadState(jdbcUrl, targetThreadId)
            val lookupQuery = "$targetBookingId,$noThreadBookingId"
            val venueLookup =
                client.get("/api/venue/$venueA/support/booking-threads?bookingIds=$lookupQuery") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertBookingLookup(venueLookup, targetBookingId, targetThreadId, noThreadBookingId)
            val ownerLookup =
                client.get("/api/venue/$venueA/support/booking-threads?bookingIds=$lookupQuery") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerAToken") }
                }
            assertBookingLookup(ownerLookup, targetBookingId, targetThreadId, noThreadBookingId)
            val guestLookup =
                client.get("/api/guest/support/booking-threads?bookingIds=$lookupQuery") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestAToken") }
                }
            assertBookingLookup(guestLookup, targetBookingId, targetThreadId, noThreadBookingId)
            val repeatedLookup =
                client.get("/api/venue/$venueA/support/booking-threads?bookingIds=$lookupQuery") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertBookingLookup(repeatedLookup, targetBookingId, targetThreadId, noThreadBookingId)
            assertEquals(mutationCountsBefore, lookupMutationCounts(jdbcUrl))
            assertEquals(targetStateBefore, threadState(jdbcUrl, targetThreadId))
            assertEquals(0, bookingThreadCount(jdbcUrl, noThreadBookingId))

            val foreignVenueLookup =
                client.get(
                    "/api/venue/$venueA/support/booking-threads?bookingIds=$targetBookingId,$foreignBookingId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertDeniedWithoutFacts(foreignVenueLookup, HttpStatusCode.NotFound, "WITH_THREAD", "NO_THREAD", "items")
            val foreignGuestLookup =
                client.get(
                    "/api/guest/support/booking-threads?bookingIds=$targetBookingId,$foreignBookingId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestAToken") }
                }
            assertDeniedWithoutFacts(foreignGuestLookup, HttpStatusCode.NotFound, "WITH_THREAD", "NO_THREAD", "items")
            val missingLookup =
                client.get("/api/venue/$venueA/support/booking-threads?bookingIds=$targetBookingId,999999999") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertDeniedWithoutFacts(missingLookup, HttpStatusCode.NotFound, "WITH_THREAD", "NO_THREAD", "items")
            val staffLookup =
                client.get("/api/venue/$venueA/support/booking-threads?bookingIds=$targetBookingId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffAToken") }
                }
            assertDeniedWithoutFacts(staffLookup, HttpStatusCode.Forbidden, "WITH_THREAD", "NO_THREAD", "items")
            val wrongVenueLookup =
                client.get("/api/venue/$venueB/support/booking-threads?bookingIds=$targetBookingId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerBToken") }
                }
            assertDeniedWithoutFacts(wrongVenueLookup, HttpStatusCode.NotFound, "WITH_THREAD", "NO_THREAD", "items")

            val duplicateIds =
                client.get(
                    "/api/venue/$venueA/support/booking-threads?bookingIds=$targetBookingId,$targetBookingId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertEquals(HttpStatusCode.BadRequest, duplicateIds.status)
            val nonPositiveId =
                client.get("/api/venue/$venueA/support/booking-threads?bookingIds=0") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertEquals(HttpStatusCode.BadRequest, nonPositiveId.status)
            val repeatedParameter =
                client.get(
                    "/api/venue/$venueA/support/booking-threads?bookingIds=$targetBookingId" +
                        "&bookingIds=$noThreadBookingId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertEquals(HttpStatusCode.BadRequest, repeatedParameter.status)
            val tooManyIds = listOf(targetBookingId) + newerBookingIds
            val oversizedLookup =
                client.get(
                    "/api/venue/$venueA/support/booking-threads?bookingIds=${tooManyIds.joinToString(",")}",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertEquals(HttpStatusCode.BadRequest, oversizedLookup.status)
            assertEquals(mutationCountsBefore, lookupMutationCounts(jdbcUrl))

            val corruptBookingId = createBooking(guestAToken, venueA, "2030-01-10T18:30:00Z")
            val corruptThreadId = threadId(openBookingThread(guestAToken, corruptBookingId))
            corruptBookingThreadMetadata(jdbcUrl, corruptThreadId, venueB, GUEST_B)
            val corruptCountsBefore = lookupMutationCounts(jdbcUrl)
            val corruptLookup =
                client.get("/api/venue/$venueA/support/booking-threads?bookingIds=$corruptBookingId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertDeniedWithoutFacts(corruptLookup, HttpStatusCode.NotFound, "WITH_THREAD", "NO_THREAD", "items")
            assertEquals(corruptCountsBefore, lookupMutationCounts(jdbcUrl))

            val duplicateBookingId = createBooking(guestAToken, venueA, "2030-01-10T19:30:00Z")
            val duplicateThreadId = threadId(openBookingThread(guestAToken, duplicateBookingId))
            seedDuplicateBookingThread(jdbcUrl, duplicateThreadId)
            val duplicateCountsBefore = lookupMutationCounts(jdbcUrl)
            val duplicateLookup =
                client.get("/api/guest/support/booking-threads?bookingIds=$duplicateBookingId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestAToken") }
                }
            assertDeniedWithoutFacts(duplicateLookup, HttpStatusCode.NotFound, "WITH_THREAD", "NO_THREAD", "items")
            assertEquals(duplicateCountsBefore, lookupMutationCounts(jdbcUrl))
        }

    @Test
    fun `booking messages enforce guest venue staff and platform isolation without side effects`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("booking-conversation-rbac")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueA = seedVenue(jdbcUrl, "Booking Venue A")
            val venueB = seedVenue(jdbcUrl, "Booking Venue B")
            seedUsers(jdbcUrl, GUEST_A, GUEST_B, MANAGER_A, MANAGER_B, STAFF_B, PLATFORM_OWNER)
            seedVenueMember(jdbcUrl, venueA, MANAGER_A, "MANAGER")
            seedVenueMember(jdbcUrl, venueB, MANAGER_B, "MANAGER")
            seedVenueMember(jdbcUrl, venueB, STAFF_B, "STAFF")

            val guestAToken = issueToken(config, GUEST_A)
            val guestBToken = issueToken(config, GUEST_B)
            val managerAToken = issueToken(config, MANAGER_A)
            val managerBToken = issueToken(config, MANAGER_B)
            val staffBToken = issueToken(config, STAFF_B)
            val platformToken = issueToken(config, PLATFORM_OWNER)
            val bookingId = createBooking(guestAToken, venueA, "2030-01-10T18:30:00Z")
            val threadId = threadId(openBookingThread(guestAToken, bookingId))
            val guestText = "guest-private-booking-message"
            val venueText = "venue-private-booking-reply"
            val venueClientMessageId = UUID.randomUUID().toString()

            val guestReply =
                client.post("/api/guest/support/threads/$threadId/messages") {
                    authorizedJson(guestAToken)
                    setBody(bookingMessageBody(guestText))
                }
            assertEquals(HttpStatusCode.OK, guestReply.status)

            val venueReply =
                client.post("/api/venue/$venueA/support/threads/$threadId/messages") {
                    authorizedJson(managerAToken)
                    setBody(bookingMessageBody(venueText, venueClientMessageId))
                }
            assertEquals(HttpStatusCode.OK, venueReply.status)
            val venueReplyBody = json.parseToJsonElement(venueReply.bodyAsText()).jsonObject
            val venueMessageId =
                venueReplyBody
                    .getValue("message")
                    .jsonObject
                    .getValue("messageId")
                    .jsonPrimitive
                    .content
                    .toLong()
            val venueNotification =
                bookingMessageOutbox(
                    jdbcUrl,
                    "booking-thread-message:$venueMessageId:guest-notification",
                )
            val venueNotificationPayload = json.parseToJsonElement(venueNotification.payloadJson).jsonObject
            assertEquals(GUEST_A, venueNotification.chatId)
            assertEquals("sendMessage", venueNotification.method)
            assertEquals(
                "Сообщение от заведения по брони «Бронь №1 · 10.01.2030, 21:30» " +
                    "в «Booking Venue A»:\n\n$venueText",
                venueNotificationPayload.getValue("text").jsonPrimitive.content,
            )
            assertTrue(venueNotification.payloadJson.contains("guest_booking_reply:$venueA:$bookingId"))
            assertFalse(venueNotification.payloadJson.contains(MANAGER_A.toString()))
            assertEquals(
                threadId,
                venueReplyBody
                    .getValue("thread")
                    .jsonObject
                    .getValue("threadId")
                    .jsonPrimitive
                    .content
                    .toLong(),
            )
            assertEquals(1, bookingThreadCount(jdbcUrl, bookingId))

            val venueDetail =
                client.get("/api/venue/$venueA/support/threads/$threadId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertConversation(venueDetail, guestText, venueText)
            val guestDetail =
                client.get("/api/guest/support/threads/$threadId?surface=CONVERSATIONS") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestAToken") }
                }
            assertConversation(guestDetail, guestText, venueText)

            val expectedMessages =
                listOf(
                    MessageRow("GUEST", guestText),
                    MessageRow("VENUE", venueText),
                )
            assertEquals(expectedMessages, messageRows(jdbcUrl, threadId))
            val stateAfterVenueReply = threadState(jdbcUrl, threadId)
            val readsAfterVenueReply = tableCount(jdbcUrl, "support_thread_reads")
            val managerReadAfterVenueReply = threadReadAt(jdbcUrl, threadId, MANAGER_A)
            val outboxAfterVenueReply = tableCount(jdbcUrl, "telegram_outbox")
            val auditAfterVenueReply = tableCount(jdbcUrl, "audit_log")
            val venueReplay =
                client.post("/api/venue/$venueA/support/threads/$threadId/messages") {
                    authorizedJson(managerAToken)
                    setBody(bookingMessageBody(venueText, venueClientMessageId))
                }
            assertEquals(HttpStatusCode.OK, venueReplay.status)
            assertEquals(venueMessageId, responseMessageId(venueReplay))
            assertEquals(stateAfterVenueReply, threadState(jdbcUrl, threadId))
            assertEquals(readsAfterVenueReply, tableCount(jdbcUrl, "support_thread_reads"))
            assertEquals(managerReadAfterVenueReply, threadReadAt(jdbcUrl, threadId, MANAGER_A))
            assertEquals(outboxAfterVenueReply, tableCount(jdbcUrl, "telegram_outbox"))
            assertEquals(auditAfterVenueReply, tableCount(jdbcUrl, "audit_log"))
            assertEquals(expectedMessages, messageRows(jdbcUrl, threadId))
            val stateBeforeDenials = threadState(jdbcUrl, threadId)
            val readsBeforeDenials = tableCount(jdbcUrl, "support_thread_reads")
            val outboxBeforeDenials = tableCount(jdbcUrl, "telegram_outbox")
            val auditBeforeDenials = tableCount(jdbcUrl, "audit_log")

            val foreignGuestDetail =
                client.get("/api/guest/support/threads/$threadId?surface=CONVERSATIONS") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestBToken") }
                }
            assertDeniedWithoutFacts(foreignGuestDetail, HttpStatusCode.NotFound, guestText, venueText)
            val foreignGuestOpen =
                client.post("/api/guest/support/booking-threads/$bookingId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestBToken") }
                }
            assertDeniedWithoutFacts(foreignGuestOpen, HttpStatusCode.NotFound, guestText, venueText)
            val foreignGuestReply =
                client.post("/api/guest/support/threads/$threadId/messages") {
                    authorizedJson(guestBToken)
                    setBody("""{"message":"foreign-guest-write"}""")
                }
            assertDeniedWithoutFacts(foreignGuestReply, HttpStatusCode.NotFound, guestText, venueText)

            val foreignManagerDetail =
                client.get("/api/venue/$venueB/support/threads/$threadId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerBToken") }
                }
            assertDeniedWithoutFacts(foreignManagerDetail, HttpStatusCode.NotFound, guestText, venueText)
            val foreignManagerReply =
                client.post("/api/venue/$venueB/support/threads/$threadId/messages") {
                    authorizedJson(managerBToken)
                    setBody("""{"message":"foreign-manager-write"}""")
                }
            assertDeniedWithoutFacts(foreignManagerReply, HttpStatusCode.NotFound, guestText, venueText)
            val foreignManagerBookingReply =
                client.post("/api/venue/bookings/$bookingId/message?venueId=$venueB") {
                    authorizedJson(managerBToken)
                    setBody("""{"message":"foreign-manager-booking-write"}""")
                }
            assertDeniedWithoutFacts(foreignManagerBookingReply, HttpStatusCode.NotFound, guestText, venueText)

            val foreignStaffDetail =
                client.get("/api/venue/$venueB/support/threads/$threadId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffBToken") }
                }
            assertDeniedWithoutFacts(foreignStaffDetail, HttpStatusCode.Forbidden, guestText, venueText)
            val foreignStaffReply =
                client.post("/api/venue/$venueB/support/threads/$threadId/messages") {
                    authorizedJson(staffBToken)
                    setBody("""{"message":"foreign-staff-write"}""")
                }
            assertDeniedWithoutFacts(foreignStaffReply, HttpStatusCode.Forbidden, guestText, venueText)
            val foreignStaffBookingReply =
                client.post("/api/venue/bookings/$bookingId/message?venueId=$venueB") {
                    authorizedJson(staffBToken)
                    setBody("""{"message":"foreign-staff-booking-write"}""")
                }
            assertDeniedWithoutFacts(foreignStaffBookingReply, HttpStatusCode.Forbidden, guestText, venueText)

            val platformDetail =
                client.get("/api/platform/support/threads/$threadId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }
            assertDeniedWithoutFacts(platformDetail, HttpStatusCode.NotFound, guestText, venueText)
            val bookingEscalate =
                client.post("/api/venue/$venueA/support/threads/$threadId/escalate") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerAToken") }
                }
            assertDeniedWithoutFacts(bookingEscalate, HttpStatusCode.NotFound, guestText, venueText)

            assertEquals(expectedMessages, messageRows(jdbcUrl, threadId))
            assertEquals(stateBeforeDenials, threadState(jdbcUrl, threadId))
            assertEquals(1, bookingThreadCount(jdbcUrl, bookingId))
            assertEquals(readsBeforeDenials, tableCount(jdbcUrl, "support_thread_reads"))
            assertEquals(outboxBeforeDenials, tableCount(jdbcUrl, "telegram_outbox"))
            assertEquals(auditBeforeDenials, tableCount(jdbcUrl, "audit_log"))
            assertTrue(
                listBookingThreadIds("/api/guest/support/threads?surface=CONVERSATIONS", guestBToken).isEmpty(),
            )
        }

    @Test
    fun `booking thread metadata mismatch denies generic guest and venue replies without message facts`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("booking-conversation-metadata-mismatch")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val bookingVenue = seedVenue(jdbcUrl, "Canonical Booking Venue")
            val foreignVenue = seedVenue(jdbcUrl, "Foreign Metadata Venue")
            seedUsers(jdbcUrl, GUEST_A, GUEST_B, MANAGER_B, PLATFORM_OWNER)
            seedVenueMember(jdbcUrl, foreignVenue, MANAGER_B, "MANAGER")
            val guestAToken = issueToken(config, GUEST_A)
            val guestBToken = issueToken(config, GUEST_B)
            val managerBToken = issueToken(config, MANAGER_B)
            val bookingId = createBooking(guestAToken, bookingVenue, "2030-01-10T18:30:00Z")
            val threadId = threadId(openBookingThread(guestAToken, bookingId))
            corruptBookingThreadMetadata(
                jdbcUrl = jdbcUrl,
                threadId = threadId,
                venueId = foreignVenue,
                guestUserId = GUEST_B,
            )
            val readsBefore = tableCount(jdbcUrl, "support_thread_reads")
            val outboxBefore = tableCount(jdbcUrl, "telegram_outbox")
            val auditBefore = tableCount(jdbcUrl, "audit_log")

            val guestReply =
                client.post("/api/guest/support/threads/$threadId/messages") {
                    authorizedJson(guestBToken)
                    setBody("""{"message":"metadata-guest-write"}""")
                }
            assertDeniedWithoutFacts(guestReply, HttpStatusCode.NotFound, "metadata-guest-write")

            val venueReply =
                client.post("/api/venue/$foreignVenue/support/threads/$threadId/messages") {
                    authorizedJson(managerBToken)
                    setBody("""{"message":"metadata-venue-write"}""")
                }
            assertDeniedWithoutFacts(venueReply, HttpStatusCode.NotFound, "metadata-venue-write")

            assertTrue(messageRows(jdbcUrl, threadId).isEmpty())
            assertEquals(readsBefore, tableCount(jdbcUrl, "support_thread_reads"))
            assertEquals(outboxBefore, tableCount(jdbcUrl, "telegram_outbox"))
            assertEquals(auditBefore, tableCount(jdbcUrl, "audit_log"))
        }

    @Test
    fun `booking Mini App message ids are required and replay payload mismatch is conflict without writes`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("booking-message-idempotency-contract")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl, "Booking Idempotency Venue")
            seedUsers(jdbcUrl, GUEST_A, MANAGER_A)
            seedVenueMember(jdbcUrl, venueId, MANAGER_A, "MANAGER")
            val guestToken = issueToken(config, GUEST_A)
            val managerToken = issueToken(config, MANAGER_A)
            val bookingId = createBooking(guestToken, venueId, "2030-01-10T18:30:00Z")
            val threadId = threadId(openBookingThread(guestToken, bookingId))
            val outboxBefore = tableCount(jdbcUrl, "telegram_outbox")

            val missingGuestId =
                client.post("/api/guest/support/threads/$threadId/messages") {
                    authorizedJson(guestToken)
                    setBody("""{"message":"same normalized text"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, missingGuestId.status)
            assertEquals(ApiErrorCodes.INVALID_INPUT, errorCode(missingGuestId))

            val invalidGuestId =
                client.post("/api/guest/support/threads/$threadId/messages") {
                    authorizedJson(guestToken)
                    setBody(bookingMessageBody("same normalized text", "not-a-uuid"))
                }
            assertEquals(HttpStatusCode.BadRequest, invalidGuestId.status)
            assertEquals(ApiErrorCodes.INVALID_INPUT, errorCode(invalidGuestId))
            assertTrue(messageRows(jdbcUrl, threadId).isEmpty())
            assertEquals(outboxBefore, tableCount(jdbcUrl, "telegram_outbox"))

            val clientMessageId = UUID.randomUUID().toString()
            val first =
                client.post("/api/guest/support/threads/$threadId/messages") {
                    authorizedJson(guestToken)
                    setBody(bookingMessageBody("  same normalized text  ", clientMessageId))
                }
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(
                "true",
                json.parseToJsonElement(first.bodyAsText())
                    .jsonObject
                    .getValue("queued")
                    .jsonPrimitive
                    .content,
            )
            val firstMessageId = responseMessageId(first)
            val guestAck =
                bookingMessageOutbox(
                    jdbcUrl,
                    "booking-thread-message:$firstMessageId:guest-ack",
                )
            val guestAckPayload = json.parseToJsonElement(guestAck.payloadJson).jsonObject
            assertEquals(GUEST_A, guestAck.chatId)
            assertEquals("sendMessage", guestAck.method)
            assertEquals(
                "✅ Ответ отправлен заведению.",
                guestAckPayload.getValue("text").jsonPrimitive.content,
            )
            assertFalse(guestAck.payloadJson.contains("same normalized text"))
            val stateAfterFirst = threadState(jdbcUrl, threadId)
            val readsAfterFirst = tableCount(jdbcUrl, "support_thread_reads")
            val auditAfterFirst = tableCount(jdbcUrl, "audit_log")
            assertEquals(1, messageRows(jdbcUrl, threadId).size)
            assertEquals(outboxBefore + 1, tableCount(jdbcUrl, "telegram_outbox"))

            val replay =
                client.post("/api/guest/support/threads/$threadId/messages") {
                    authorizedJson(guestToken)
                    setBody(bookingMessageBody("same normalized text", clientMessageId))
                }
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals(
                "true",
                json.parseToJsonElement(replay.bodyAsText())
                    .jsonObject
                    .getValue("queued")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(firstMessageId, responseMessageId(replay))
            assertEquals(stateAfterFirst, threadState(jdbcUrl, threadId))
            assertEquals(readsAfterFirst, tableCount(jdbcUrl, "support_thread_reads"))
            assertEquals(auditAfterFirst, tableCount(jdbcUrl, "audit_log"))
            assertEquals(1, messageRows(jdbcUrl, threadId).size)
            assertEquals(outboxBefore + 1, tableCount(jdbcUrl, "telegram_outbox"))

            val mismatchText = "different private payload"
            val mismatch =
                client.post("/api/guest/support/threads/$threadId/messages") {
                    authorizedJson(guestToken)
                    setBody(bookingMessageBody(mismatchText, clientMessageId))
                }
            assertEquals(HttpStatusCode.Conflict, mismatch.status)
            assertEquals(ApiErrorCodes.BOOKING_MESSAGE_IDEMPOTENCY_PAYLOAD_MISMATCH, errorCode(mismatch))
            assertFalse(mismatch.bodyAsText().contains(mismatchText))
            assertEquals(stateAfterFirst, threadState(jdbcUrl, threadId))
            assertEquals(1, messageRows(jdbcUrl, threadId).size)
            assertEquals(outboxBefore + 1, tableCount(jdbcUrl, "telegram_outbox"))

            val missingVenueGenericId =
                client.post("/api/venue/$venueId/support/threads/$threadId/messages") {
                    authorizedJson(managerToken)
                    setBody("""{"message":"venue generic missing key"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, missingVenueGenericId.status)

            val missingVenueDedicatedId =
                client.post("/api/venue/bookings/$bookingId/message?venueId=$venueId") {
                    authorizedJson(managerToken)
                    setBody("""{"message":"venue dedicated missing key"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, missingVenueDedicatedId.status)

            closeThread(jdbcUrl, threadId)
            val stateAfterClose = threadState(jdbcUrl, threadId)
            val readAfterClose = threadReadAt(jdbcUrl, threadId, GUEST_A)
            val auditAfterClose = tableCount(jdbcUrl, "audit_log")
            val closedReplay =
                client.post("/api/guest/support/threads/$threadId/messages") {
                    authorizedJson(guestToken)
                    setBody(bookingMessageBody("same normalized text", clientMessageId))
                }
            assertEquals(HttpStatusCode.OK, closedReplay.status)
            assertEquals(firstMessageId, responseMessageId(closedReplay))

            val closedNewKey =
                client.post("/api/guest/support/threads/$threadId/messages") {
                    authorizedJson(guestToken)
                    setBody(bookingMessageBody("new write after close"))
                }
            assertEquals(HttpStatusCode.BadRequest, closedNewKey.status)
            assertEquals(ApiErrorCodes.INVALID_INPUT, errorCode(closedNewKey))
            assertEquals(stateAfterClose, threadState(jdbcUrl, threadId))
            assertEquals(readAfterClose, threadReadAt(jdbcUrl, threadId, GUEST_A))
            assertEquals(auditAfterClose, tableCount(jdbcUrl, "audit_log"))
            assertEquals(1, messageRows(jdbcUrl, threadId).size)
            assertEquals(outboxBefore + 1, tableCount(jdbcUrl, "telegram_outbox"))
        }

    @Test
    fun `venue booking reply denied by traffic policy is atomic and the same key retries after allow`() {
        val jdbcUrl = buildJdbcUrl("booking-message-traffic-policy")
        val initiallyAllowedConfig = buildAllowlistConfig(jdbcUrl, GUEST_A, MANAGER_A)
        var venueId = 0L
        var bookingId = 0L
        var threadId = 0L

        testApplication {
            environment { config = initiallyAllowedConfig }
            application { module() }
            client.get("/health")

            venueId = seedVenue(jdbcUrl, "Booking Traffic Policy Venue")
            seedUsers(jdbcUrl, GUEST_A, MANAGER_A)
            seedVenueMember(jdbcUrl, venueId, MANAGER_A, "MANAGER")
            val guestToken = issueToken(initiallyAllowedConfig, GUEST_A)
            bookingId = createBooking(guestToken, venueId, "2030-01-10T18:30:00Z")
            threadId = threadId(openBookingThread(guestToken, bookingId))
        }

        val clientMessageId = UUID.randomUUID().toString()
        val stateBefore = threadState(jdbcUrl, threadId)
        val readsBefore = tableCount(jdbcUrl, "support_thread_reads")
        val outboxBefore = tableCount(jdbcUrl, "telegram_outbox")
        val auditBefore = tableCount(jdbcUrl, "audit_log")
        val deniedConfig = buildAllowlistConfig(jdbcUrl, MANAGER_A)

        testApplication {
            environment { config = deniedConfig }
            application { module() }

            val response =
                client.post("/api/venue/$venueId/support/threads/$threadId/messages") {
                    authorizedJson(issueToken(deniedConfig, MANAGER_A))
                    setBody(bookingMessageBody("Retry after allowlist correction", clientMessageId))
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val body = response.bodyAsText()
            val errorCode =
                json.parseToJsonElement(body)
                    .jsonObject
                    .getValue("error")
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive
                    .content
            assertEquals(ApiErrorCodes.CONFIG_ERROR, errorCode)
            assertFalse(body.contains("ALLOWLIST", ignoreCase = true))
            assertFalse(body.contains(GUEST_A.toString()))
            assertFalse(body.contains(MANAGER_A.toString()))
        }

        assertEquals(stateBefore, threadState(jdbcUrl, threadId))
        assertTrue(messageRows(jdbcUrl, threadId).isEmpty())
        assertEquals(readsBefore, tableCount(jdbcUrl, "support_thread_reads"))
        assertEquals(outboxBefore, tableCount(jdbcUrl, "telegram_outbox"))
        assertEquals(auditBefore, tableCount(jdbcUrl, "audit_log"))

        testApplication {
            environment { config = initiallyAllowedConfig }
            application { module() }
            val managerToken = issueToken(initiallyAllowedConfig, MANAGER_A)

            val committed =
                client.post("/api/venue/$venueId/support/threads/$threadId/messages") {
                    authorizedJson(managerToken)
                    setBody(bookingMessageBody("Retry after allowlist correction", clientMessageId))
                }
            val replay =
                client.post("/api/venue/$venueId/support/threads/$threadId/messages") {
                    authorizedJson(managerToken)
                    setBody(bookingMessageBody("Retry after allowlist correction", clientMessageId))
                }

            assertEquals(HttpStatusCode.OK, committed.status)
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals(
                "true",
                json.parseToJsonElement(committed.bodyAsText())
                    .jsonObject
                    .getValue("queued")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                "true",
                json.parseToJsonElement(replay.bodyAsText())
                    .jsonObject
                    .getValue("queued")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(responseMessageId(committed), responseMessageId(replay))
        }

        assertEquals(listOf(MessageRow("VENUE", "Retry after allowlist correction")), messageRows(jdbcUrl, threadId))
        assertEquals(outboxBefore + 1, tableCount(jdbcUrl, "telegram_outbox"))
        assertEquals(auditBefore, tableCount(jdbcUrl, "audit_log"))
    }

    private suspend fun ApplicationTestBuilder.createBooking(
        token: String,
        venueId: Long,
        scheduledAt: String,
    ): Long {
        val response =
            client.post("/api/guest/booking/create") {
                authorizedJson(token)
                setBody("""{"venueId":$venueId,"scheduledAt":"$scheduledAt","partySize":2}""")
            }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.parseToJsonElement(response.bodyAsText())
            .jsonObject
            .getValue("bookingId")
            .jsonPrimitive
            .content
            .toLong()
    }

    private suspend fun ApplicationTestBuilder.openBookingThread(
        token: String,
        bookingId: Long,
    ): JsonObject {
        val response =
            client.post("/api/guest/support/booking-threads/$bookingId") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
            }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun threadId(detail: JsonObject): Long =
        detail.getValue("thread")
            .jsonObject
            .getValue("threadId")
            .jsonPrimitive
            .content
            .toLong()

    private fun JsonObject.threadIdValue(): Long = getValue("threadId").jsonPrimitive.content.toLong()

    private suspend fun HttpResponse.threadJson(): JsonObject =
        json.parseToJsonElement(bodyAsText()).jsonObject.getValue("thread").jsonObject

    private suspend fun assertBookingLookup(
        response: HttpResponse,
        withThreadBookingId: Long,
        threadId: Long,
        noThreadBookingId: Long,
    ) {
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val items =
            json.parseToJsonElement(response.bodyAsText())
                .jsonObject
                .getValue("items")
                .jsonArray
                .map { it.jsonObject }
        assertEquals(
            listOf(withThreadBookingId, noThreadBookingId),
            items.map { item ->
                item.getValue("bookingId").jsonPrimitive.content.toLong()
            },
        )
        assertEquals("WITH_THREAD", items[0].getValue("status").jsonPrimitive.content)
        assertEquals(
            threadId,
            items[0]
                .getValue("thread")
                .jsonObject
                .getValue("threadId")
                .jsonPrimitive
                .content
                .toLong(),
        )
        assertEquals("NO_THREAD", items[1].getValue("status").jsonPrimitive.content)
        assertTrue(items[1]["thread"] == null || items[1]["thread"].toString() == "null")
    }

    private suspend fun ApplicationTestBuilder.listBookingThreadIds(
        path: String,
        token: String,
    ): Set<Long> {
        val filter =
            if (path.startsWith("/api/guest/") && "surface=" !in path) {
                "surface=CONVERSATIONS"
            } else if (!path.startsWith("/api/guest/") && "threadType=" !in path) {
                "threadType=BOOKING_THREAD"
            } else {
                null
            }
        val requestPath =
            filter?.let {
                val separator = if ('?' in path) '&' else '?'
                "$path$separator$it"
            } ?: path
        val response =
            client.get(requestPath) {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
            }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        return json.parseToJsonElement(response.bodyAsText())
            .jsonObject
            .getValue("items")
            .jsonArray
            .mapTo(mutableSetOf()) { item ->
                item.jsonObject.getValue("threadId").jsonPrimitive.content.toLong()
            }
    }

    private suspend fun assertConversation(
        response: HttpResponse,
        guestText: String,
        venueText: String,
    ) {
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val messages =
            json.parseToJsonElement(response.bodyAsText())
                .jsonObject
                .getValue("messages")
                .jsonArray
        assertEquals(
            listOf(guestText, venueText),
            messages.map { it.jsonObject.getValue("text").jsonPrimitive.content },
        )
        assertEquals(
            listOf("GUEST", "VENUE"),
            messages.map { it.jsonObject.getValue("authorRole").jsonPrimitive.content },
        )
    }

    private suspend fun assertDeniedWithoutFacts(
        response: HttpResponse,
        expectedStatus: HttpStatusCode,
        vararg privateFacts: String,
    ) {
        val body = response.bodyAsText()
        assertEquals(expectedStatus, response.status, body)
        privateFacts.forEach { fact -> assertFalse(body.contains(fact), body) }
    }

    private suspend fun assertMaintenanceUnavailable(
        response: HttpResponse,
        vararg privateFacts: String,
    ) {
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
        val envelope = assertApiErrorEnvelope(response, ApiErrorCodes.SERVICE_UNAVAILABLE)
        assertEquals("Service unavailable", envelope.error.message)
        assertEquals(null, envelope.error.details)
        val body = response.bodyAsText()
        privateFacts.forEach { fact -> assertFalse(body.contains(fact), body) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authorizedJson(token: String) {
        headers {
            append(HttpHeaders.Authorization, "Bearer $token")
            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        }
    }

    private fun buildJdbcUrl(prefix: String): String =
        "jdbc:h2:mem:$prefix-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
            "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"

    private fun buildConfig(
        jdbcUrl: String,
        vararg additionalConfig: Pair<String, String>,
    ): MapApplicationConfig =
        MapApplicationConfig(
            "ktor.environment" to "test",
            "app.env" to "test",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
            "api.session.jwtSecret" to "secret-secret-secret-secret-secret",
            "api.session.issuer" to "hookah",
            "api.session.audience" to "miniapp",
            "api.session.ttlSeconds" to "3600",
            "platform.ownerUserId" to PLATFORM_OWNER.toString(),
            "billing.subscription.intervalSeconds" to "0",
            "venue.staffInviteSecretPepper" to "invite-pepper",
            *additionalConfig,
        )

    private fun buildAllowlistConfig(
        jdbcUrl: String,
        vararg allowedIds: Long,
    ): MapApplicationConfig {
        val ids = allowedIds.toSet().sorted().joinToString(",")
        return MapApplicationConfig(
            "ktor.environment" to "test",
            "app.env" to "test",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
            "api.session.jwtSecret" to "secret-secret-secret-secret-secret",
            "api.session.issuer" to "hookah",
            "api.session.audience" to "miniapp",
            "api.session.ttlSeconds" to "3600",
            "platform.ownerUserId" to PLATFORM_OWNER.toString(),
            "billing.subscription.intervalSeconds" to "0",
            "venue.staffInviteSecretPepper" to "invite-pepper",
            "telegram.trafficPolicy" to "ALLOWLIST",
            "telegram.allowedUserIds" to ids,
            "telegram.allowedChatIds" to ids,
        )
    }

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long,
    ): String {
        val tokenConfig =
            SessionTokenConfig(
                jwtSecret = config.property("api.session.jwtSecret").getString(),
                issuer = config.property("api.session.issuer").getString(),
                audience = config.property("api.session.audience").getString(),
                ttlSeconds = config.property("api.session.ttlSeconds").getString().toLong(),
            )
        return SessionTokenService(tokenConfig).issueToken(userId).token
    }

    private fun seedVenue(
        jdbcUrl: String,
        name: String,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val venueId =
                connection.prepareStatement(
                    "INSERT INTO venues (name, city, address, status) VALUES (?, 'City', 'Address', 'PUBLISHED')",
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setString(1, name)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        assertTrue(keys.next())
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                "INSERT INTO venue_settings (venue_id, timezone) VALUES (?, 'Europe/Moscow')",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
                VALUES (?, 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeUpdate()
            }
            seedDailyBookingHours(connection, venueId)
            venueId
        }

    private fun seedDailyBookingHours(
        connection: Connection,
        venueId: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO venue_booking_hours (venue_id, weekday, opens_at, closes_at, is_closed)
            VALUES (?, ?, ?, ?, FALSE)
            """.trimIndent(),
        ).use { statement ->
            (1..7).forEach { weekday ->
                statement.setLong(1, venueId)
                statement.setInt(2, weekday)
                statement.setTime(3, java.sql.Time.valueOf("00:00:00"))
                statement.setTime(4, java.sql.Time.valueOf("00:00:00"))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun seedUsers(
        jdbcUrl: String,
        vararg userIds: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO users (telegram_user_id, username, first_name, updated_at)
                VALUES (?, ?, 'Name', CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                userIds.forEach { userId ->
                    statement.setLong(1, userId)
                    statement.setString(2, "u$userId")
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun seedSupportTicketMessage(
        jdbcUrl: String,
        venueId: Long,
        guestUserId: Long,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val threadId =
                connection.prepareStatement(
                    """
                    INSERT INTO support_threads (
                        venue_id,
                        guest_user_id,
                        category,
                        status,
                        thread_type,
                        assignee_scope,
                        created_source,
                        title,
                        last_message_at,
                        updated_at
                    )
                    VALUES (?, ?, 'OTHER', 'NEW', 'SUPPORT_TICKET', 'PLATFORM', 'GUEST_MINIAPP',
                            'Platform support ticket', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, guestUserId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        assertTrue(keys.next())
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO support_messages (thread_id, author_user_id, author_role, source, text)
                VALUES (?, ?, 'GUEST', 'GUEST_MINIAPP', 'Platform-only support message')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.setLong(2, guestUserId)
                assertEquals(1, statement.executeUpdate())
            }
            threadId
        }

    private fun seedVenueMember(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        role: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_members (venue_id, user_id, role, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
        }
    }

    private fun deleteVenueSettings(
        jdbcUrl: String,
        venueId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("DELETE FROM venue_settings WHERE venue_id = ?").use { statement ->
                statement.setLong(1, venueId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun alignThreadLastMessageAt(
        jdbcUrl: String,
        firstThreadId: Long,
        secondThreadId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "UPDATE support_threads SET last_message_at = TIMESTAMP '2030-01-10 23:00:00' WHERE id IN (?, ?)",
            ).use { statement ->
                statement.setLong(1, firstThreadId)
                statement.setLong(2, secondThreadId)
                assertEquals(2, statement.executeUpdate())
            }
        }
    }

    private fun corruptBookingThreadMetadata(
        jdbcUrl: String,
        threadId: Long,
        venueId: Long,
        guestUserId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "UPDATE support_threads SET venue_id = ?, guest_user_id = ? WHERE id = ?",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, guestUserId)
                statement.setLong(3, threadId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun ageThreadBeforeInventory(
        jdbcUrl: String,
        threadId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "UPDATE support_threads SET created_at = ?, updated_at = ?, last_message_at = NULL WHERE id = ?",
            ).use { statement ->
                val oldTimestamp = Timestamp.from(Instant.parse("2020-01-01T00:00:00Z"))
                statement.setTimestamp(1, oldTimestamp)
                statement.setTimestamp(2, oldTimestamp)
                statement.setLong(3, threadId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun seedNewerBookingThreads(
        jdbcUrl: String,
        venueId: Long,
        guestUserId: Long,
        count: Int,
    ): List<Long> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.autoCommit = false
            try {
                val bookingIds =
                    buildList {
                        repeat(count) { index ->
                            val bookingId =
                                connection.prepareStatement(
                                    """
                                    INSERT INTO bookings (
                                        venue_id,
                                        user_id,
                                        scheduled_at,
                                        party_size,
                                        status,
                                        display_date,
                                        display_number
                                    )
                                    VALUES (?, ?, ?, 2, 'CANCELED', ?, ?)
                                    """.trimIndent(),
                                    Statement.RETURN_GENERATED_KEYS,
                                ).use { statement ->
                                    statement.setLong(1, venueId)
                                    statement.setLong(2, guestUserId)
                                    statement.setTimestamp(
                                        3,
                                        Timestamp.from(
                                            Instant.parse("2029-01-01T00:00:00Z").plusSeconds(index.toLong()),
                                        ),
                                    )
                                    statement.setDate(4, java.sql.Date.valueOf("2029-01-01"))
                                    statement.setInt(5, 10_000 + index)
                                    statement.executeUpdate()
                                    statement.generatedKeys.use { keys ->
                                        assertTrue(keys.next())
                                        keys.getLong(1)
                                    }
                                }
                            connection.prepareStatement(
                                """
                                INSERT INTO support_threads (
                                    venue_id,
                                    guest_user_id,
                                    category,
                                    status,
                                    booking_id,
                                    thread_type,
                                    assignee_scope,
                                    created_source,
                                    title,
                                    created_at,
                                    updated_at
                                )
                                VALUES (?, ?, 'BOOKING', 'IN_PROGRESS', ?, 'BOOKING_THREAD', 'VENUE', 'BOOKING_FLOW', ?, ?, ?)
                                """.trimIndent(),
                            ).use { statement ->
                                val timestamp =
                                    Timestamp.from(Instant.parse("2035-01-01T00:00:00Z").plusSeconds(index.toLong()))
                                statement.setLong(1, venueId)
                                statement.setLong(2, guestUserId)
                                statement.setLong(3, bookingId)
                                statement.setString(4, "Newer booking thread $index")
                                statement.setTimestamp(5, timestamp)
                                statement.setTimestamp(6, timestamp)
                                assertEquals(1, statement.executeUpdate())
                            }
                            add(bookingId)
                        }
                    }
                connection.commit()
                bookingIds
            } catch (exception: Throwable) {
                connection.rollback()
                throw exception
            }
        }

    private fun seedDuplicateBookingThread(
        jdbcUrl: String,
        threadId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP INDEX uq_support_threads_booking_thread_booking_id")
            }
            connection.prepareStatement(
                """
                INSERT INTO support_threads (
                    venue_id,
                    guest_user_id,
                    category,
                    status,
                    booking_id,
                    thread_type,
                    assignee_scope,
                    created_source,
                    title
                )
                SELECT venue_id,
                       guest_user_id,
                       category,
                       status,
                       booking_id,
                       thread_type,
                       assignee_scope,
                       created_source,
                       title || ' duplicate'
                FROM support_threads
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, threadId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private data class ThreadOwnership(
        val threadId: Long,
        val venueId: Long,
        val guestUserId: Long,
    )

    private fun bookingThread(
        jdbcUrl: String,
        bookingId: Long,
    ): ThreadOwnership =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT id, venue_id, guest_user_id
                FROM support_threads
                WHERE booking_id = ?
                  AND thread_type = 'BOOKING_THREAD'
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, bookingId)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    val result =
                        ThreadOwnership(
                            threadId = rows.getLong("id"),
                            venueId = rows.getLong("venue_id"),
                            guestUserId = rows.getLong("guest_user_id"),
                        )
                    assertFalse(rows.next())
                    result
                }
            }
        }

    private fun bookingThreadCount(
        jdbcUrl: String,
        bookingId: Long? = null,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val bookingFilter = if (bookingId == null) "" else "AND booking_id = ?"
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM support_threads
                WHERE thread_type = 'BOOKING_THREAD'
                  $bookingFilter
                """.trimIndent(),
            ).use { statement ->
                bookingId?.let { statement.setLong(1, it) }
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    rows.getInt(1)
                }
            }
        }

    private data class MessageRow(
        val authorRole: String,
        val text: String,
    )

    private fun messageRows(
        jdbcUrl: String,
        threadId: Long,
    ): List<MessageRow> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT author_role, text
                FROM support_messages
                WHERE thread_id = ?
                ORDER BY created_at, id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(MessageRow(rows.getString("author_role"), rows.getString("text")))
                        }
                    }
                }
            }
        }

    private data class ThreadState(
        val status: String,
        val assigneeScope: String,
        val updatedAt: String,
        val lastMessageAt: String?,
    )

    private data class TelegramOutboxRow(
        val chatId: Long,
        val method: String,
        val payloadJson: String,
    )

    private data class RawThreadReadMarker(
        val threadId: Long,
        val userId: Long,
        val lastReadMessageId: Long?,
        val lastReadAt: Instant?,
    )

    private fun threadState(
        jdbcUrl: String,
        threadId: Long,
    ): ThreadState =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT status, assignee_scope, updated_at, last_message_at
                FROM support_threads
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    ThreadState(
                        status = rows.getString("status"),
                        assigneeScope = rows.getString("assignee_scope"),
                        updatedAt = rows.getTimestamp("updated_at").toInstant().toString(),
                        lastMessageAt = rows.getTimestamp("last_message_at")?.toInstant()?.toString(),
                    )
                }
            }
        }

    private fun closeThread(
        jdbcUrl: String,
        threadId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "UPDATE support_threads SET status = 'CLOSED' WHERE id = ?",
            ).use { statement ->
                statement.setLong(1, threadId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun threadReadAt(
        jdbcUrl: String,
        threadId: Long,
        userId: Long,
    ): String =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT last_read_at FROM support_thread_reads WHERE thread_id = ? AND user_id = ?",
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.setLong(2, userId)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    rows.getTimestamp("last_read_at").toInstant().toString()
                }
            }
        }

    private fun threadReadMarker(
        jdbcUrl: String,
        threadId: Long,
        userId: Long,
    ): RawThreadReadMarker? =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT thread_id, user_id, last_read_message_id, last_read_at
                FROM support_thread_reads
                WHERE thread_id = ?
                  AND user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.setLong(2, userId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        val lastReadMessageId = rows.getLong("last_read_message_id")
                        val cursorWasNull = rows.wasNull()
                        RawThreadReadMarker(
                            threadId = rows.getLong("thread_id"),
                            userId = rows.getLong("user_id"),
                            lastReadMessageId = if (cursorWasNull) null else lastReadMessageId,
                            lastReadAt = rows.getTimestamp("last_read_at")?.toInstant(),
                        )
                    }
                }
            }
        }

    private fun bookingMessageOutbox(
        jdbcUrl: String,
        dedupeKey: String,
    ): TelegramOutboxRow =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT chat_id, method, payload_json FROM telegram_outbox WHERE dedupe_key = ?",
            ).use { statement ->
                statement.setString(1, dedupeKey)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    val row =
                        TelegramOutboxRow(
                            chatId = rows.getLong("chat_id"),
                            method = rows.getString("method"),
                            payloadJson = rows.getString("payload_json"),
                        )
                    assertFalse(rows.next())
                    row
                }
            }
        }

    private fun bookingMessageBody(
        message: String,
        clientMessageId: String = UUID.randomUUID().toString(),
    ): String =
        buildJsonObject {
            put("message", message)
            put("clientMessageId", clientMessageId)
        }.toString()

    private fun tableCount(
        jdbcUrl: String,
        tableName: String,
    ): Int {
        require(
            tableName in
                setOf("support_threads", "support_messages", "support_thread_reads", "telegram_outbox", "audit_log"),
        )
        return DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $tableName").use { rows ->
                    assertTrue(rows.next())
                    rows.getInt(1)
                }
            }
        }
    }

    private fun lookupMutationCounts(jdbcUrl: String): List<Int> =
        listOf(
            tableCount(jdbcUrl, "support_threads"),
            tableCount(jdbcUrl, "support_messages"),
            tableCount(jdbcUrl, "support_thread_reads"),
            tableCount(jdbcUrl, "telegram_outbox"),
            tableCount(jdbcUrl, "audit_log"),
        )

    private suspend fun errorCode(response: HttpResponse): String =
        json.parseToJsonElement(response.bodyAsText())
            .jsonObject
            .getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive
            .content

    private suspend fun responseMessageId(response: HttpResponse): Long =
        json.parseToJsonElement(response.bodyAsText())
            .jsonObject
            .getValue("message")
            .jsonObject
            .getValue("messageId")
            .jsonPrimitive
            .content
            .toLong()

    private companion object {
        const val GUEST_A = 410001L
        const val GUEST_B = 410002L
        const val MANAGER_A = 420001L
        const val MANAGER_B = 420002L
        const val STAFF_B = 430002L
        const val PLATFORM_OWNER = 440001L
        const val MAINTENANCE_ALLOWED_MANAGER = 450001L
    }
}
