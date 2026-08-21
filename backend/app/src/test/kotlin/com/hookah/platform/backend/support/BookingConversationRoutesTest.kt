package com.hookah.platform.backend.support

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
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
import kotlin.test.assertTrue

class BookingConversationRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

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
                listBookingThreadIds("/api/guest/support/threads", guestAToken),
            )
            assertEquals(
                setOf(threadId(secondGuestOpen)),
                listBookingThreadIds("/api/guest/support/threads", guestBToken),
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
                "Сообщение от заведения по обращению «Бронь №1» " +
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
                client.get("/api/guest/support/threads/$threadId") {
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
                client.get("/api/guest/support/threads/$threadId") {
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
            assertTrue(listBookingThreadIds("/api/guest/support/threads", guestBToken).isEmpty())
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
        val separator = if ('?' in path) '&' else '?'
        val response =
            client.get("$path${separator}threadType=BOOKING_THREAD") {
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

    private fun io.ktor.client.request.HttpRequestBuilder.authorizedJson(token: String) {
        headers {
            append(HttpHeaders.Authorization, "Bearer $token")
            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        }
    }

    private fun buildJdbcUrl(prefix: String): String =
        "jdbc:h2:mem:$prefix-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
            "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"

    private fun buildConfig(jdbcUrl: String): MapApplicationConfig =
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
    }
}
