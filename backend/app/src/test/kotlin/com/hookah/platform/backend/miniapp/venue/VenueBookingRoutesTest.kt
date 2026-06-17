package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VenueBookingRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `venue can list and confirm active bookings`() =
        testApplication {
            val jdbcUrl =
                "jdbc:h2:mem:venue-bookings-${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
            val config =
                MapApplicationConfig(
                    "ktor.environment" to "test",
                    "db.jdbcUrl" to jdbcUrl,
                    "db.user" to "sa",
                    "db.password" to "",
                    "api.session.jwtSecret" to "secret-secret-secret-secret-secret",
                    "api.session.issuer" to "hookah",
                    "api.session.audience" to "miniapp",
                    "api.session.ttlSeconds" to "3600",
                )

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            seedSubscription(jdbcUrl, venueId)
            seedUser(jdbcUrl, GUEST_ID)
            seedUser(jdbcUrl, MANAGER_ID)
            seedVenueMember(jdbcUrl, venueId, MANAGER_ID, "MANAGER")

            val guestToken = issueToken(config, GUEST_ID)
            val managerToken = issueToken(config, MANAGER_ID)
            val createResponse =
                client.post("/api/guest/booking/create") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {"venueId":$venueId,"scheduledAt":"2030-01-10T18:30:00Z","partySize":4,"comment":"window"}
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, createResponse.status)
            val bookingId =
                json.parseToJsonElement(createResponse.bodyAsText())
                    .jsonObject
                    .getValue("bookingId")
                    .jsonPrimitive
                    .content
                    .toLong()

            val listResponse =
                client.get("/api/venue/bookings?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val item =
                json.parseToJsonElement(listResponse.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(bookingId.toString(), item.getValue("bookingId").jsonPrimitive.content)
            assertEquals("pending", item.getValue("status").jsonPrimitive.content)
            assertEquals("window", item.getValue("comment").jsonPrimitive.content)
            assertEquals("10.01.2030, 21:30", item.getValue("scheduledAtDisplay").jsonPrimitive.content)
            assertEquals("2030-01-10", item.getValue("scheduledLocalDate").jsonPrimitive.content)
            assertEquals("21:30", item.getValue("scheduledLocalTime").jsonPrimitive.content)
            assertEquals("2030-01-10", item.getValue("serviceDate").jsonPrimitive.content)
            assertEquals("10.01.2030, 22:00", item.getValue("arrivalDeadlineAtDisplay").jsonPrimitive.content)

            val confirmResponse =
                client.post("/api/venue/bookings/$bookingId/confirm?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, confirmResponse.status)
            val confirmMessage = outboxTexts(jdbcUrl, GUEST_ID).last()
            assertTrue(confirmMessage.contains("✅ Бронь №1 подтверждена"), confirmMessage)
            assertTrue(confirmMessage.contains("Заведение: Booking Venue"), confirmMessage)
            assertTrue(confirmMessage.contains("Время: 10.01.2030, 21:30"), confirmMessage)
            assertTrue(confirmMessage.contains("Гостей: 4"), confirmMessage)
            assertTrue(confirmMessage.contains("Держим до 22:00"), confirmMessage)
            assertFalse(confirmMessage.contains("UTC"), confirmMessage)

            val messageResponse =
                client.post("/api/venue/bookings/$bookingId/message?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $managerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"На 19:00 все столы заняты. Можем предложить 20:30?"}""")
                }
            assertEquals(HttpStatusCode.OK, messageResponse.status)
            val guestMessagePayload = outboxPayloadJson(jdbcUrl, GUEST_ID).last()
            val guestMessage =
                json.parseToJsonElement(guestMessagePayload)
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive
                    .content
            assertTrue(guestMessage.contains("Сообщение по вашей брони №1 в «Booking Venue»"), guestMessage)
            assertTrue(guestMessage.contains("На 19:00 все столы заняты. Можем предложить 20:30?"), guestMessage)
            assertFalse(guestMessage.contains("u$MANAGER_ID"), guestMessage)
            assertFalse(guestMessage.contains("@"), guestMessage)
            assertTrue(guestMessagePayload.contains("guest_booking_reply:$venueId:$bookingId"), guestMessagePayload)
            val messageResponseBody = json.parseToJsonElement(messageResponse.bodyAsText()).jsonObject
            val threadId =
                messageResponseBody
                    .getValue("thread")
                    .jsonObject
                    .getValue("threadId")
                    .jsonPrimitive
                    .content
                    .toLong()
            assertEquals(
                "BOOKING",
                messageResponseBody
                    .getValue("thread")
                    .jsonObject
                    .getValue("category")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                "Бронь №1",
                messageResponseBody
                    .getValue("thread")
                    .jsonObject
                    .getValue("contextLabel")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                "На 19:00 все столы заняты. Можем предложить 20:30?",
                messageResponseBody
                    .getValue("thread")
                    .jsonObject
                    .getValue("lastMessagePreview")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                "На 19:00 все столы заняты. Можем предложить 20:30?",
                messageResponseBody.getValue("message").jsonObject.getValue("text").jsonPrimitive.content,
            )
            assertEquals(1, supportThreadCount(jdbcUrl, venueId, bookingId))
            assertEquals(
                listOf("На 19:00 все столы заняты. Можем предложить 20:30?"),
                supportMessageTexts(jdbcUrl, threadId),
            )

            val secondThreadMessageResponse =
                client.post("/api/venue/$venueId/support/threads/$threadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $managerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Можем забронировать на 20:30."}""")
                }
            assertEquals(HttpStatusCode.OK, secondThreadMessageResponse.status)
            assertEquals(1, supportThreadCount(jdbcUrl, venueId, bookingId))
            assertEquals(
                listOf(
                    "На 19:00 все столы заняты. Можем предложить 20:30?",
                    "Можем забронировать на 20:30.",
                ),
                supportMessageTexts(jdbcUrl, threadId),
            )

            val guestThreadsResponse =
                client.get("/api/guest/support/threads?filter=active") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                }
            assertEquals(HttpStatusCode.OK, guestThreadsResponse.status)
            val guestThreadItem =
                json.parseToJsonElement(guestThreadsResponse.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(threadId.toString(), guestThreadItem.getValue("threadId").jsonPrimitive.content)
            assertEquals("Booking Venue", guestThreadItem.getValue("venueName").jsonPrimitive.content)
            assertEquals("Бронь №1", guestThreadItem.getValue("contextLabel").jsonPrimitive.content)
            assertEquals(
                "Можем забронировать на 20:30.",
                guestThreadItem.getValue("lastMessagePreview").jsonPrimitive.content,
            )
            assertEquals("2", guestThreadItem.getValue("unreadCount").jsonPrimitive.content)

            val guestThreadDetailResponse =
                client.get("/api/guest/support/threads/$threadId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                }
            assertEquals(HttpStatusCode.OK, guestThreadDetailResponse.status)
            assertEquals(
                "0",
                json.parseToJsonElement(guestThreadDetailResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
                    .getValue("unreadCount")
                    .jsonPrimitive
                    .content,
            )

            val guestThreadsAfterReadResponse =
                client.get("/api/guest/support/threads?filter=active") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                }
            assertEquals(HttpStatusCode.OK, guestThreadsAfterReadResponse.status)
            assertEquals(
                "0",
                json.parseToJsonElement(guestThreadsAfterReadResponse.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("unreadCount")
                    .jsonPrimitive
                    .content,
            )

            val guestReplyResponse =
                client.post("/api/guest/support/threads/$threadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Да, 20:30 подходит."}""")
                }
            assertEquals(HttpStatusCode.OK, guestReplyResponse.status)
            assertEquals(
                listOf(
                    "На 19:00 все столы заняты. Можем предложить 20:30?",
                    "Можем забронировать на 20:30.",
                    "Да, 20:30 подходит.",
                ),
                supportMessageTexts(jdbcUrl, threadId),
            )

            val venueThreadsAfterGuestReplyResponse =
                client.get("/api/venue/$venueId/support/threads?filter=active") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, venueThreadsAfterGuestReplyResponse.status)
            val venueThreadItem =
                json.parseToJsonElement(venueThreadsAfterGuestReplyResponse.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("Name", venueThreadItem.getValue("guestDisplayName").jsonPrimitive.content)
            assertEquals("Да, 20:30 подходит.", venueThreadItem.getValue("lastMessagePreview").jsonPrimitive.content)
            assertEquals("1", venueThreadItem.getValue("unreadCount").jsonPrimitive.content)

            val venueThreadDetailResponse =
                client.get("/api/venue/$venueId/support/threads/$threadId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, venueThreadDetailResponse.status)
            assertEquals(
                "0",
                json.parseToJsonElement(venueThreadDetailResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
                    .getValue("unreadCount")
                    .jsonPrimitive
                    .content,
            )

            val guestResolveThreadResponse =
                client.post("/api/guest/support/threads/$threadId/resolve") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                }
            assertEquals(HttpStatusCode.OK, guestResolveThreadResponse.status)
            assertEquals(
                "RESOLVED",
                json.parseToJsonElement(guestResolveThreadResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive
                    .content,
            )
            val guestActiveThreadsAfterResolveResponse =
                client.get("/api/guest/support/threads?filter=active") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                }
            assertEquals(HttpStatusCode.OK, guestActiveThreadsAfterResolveResponse.status)
            assertTrue(
                json.parseToJsonElement(guestActiveThreadsAfterResolveResponse.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .isEmpty(),
            )
            val guestResolvedThreadsResponse =
                client.get("/api/guest/support/threads?filter=resolved") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                }
            assertEquals(HttpStatusCode.OK, guestResolvedThreadsResponse.status)
            assertEquals(
                threadId.toString(),
                json.parseToJsonElement(guestResolvedThreadsResponse.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("threadId")
                    .jsonPrimitive
                    .content,
            )

            val venueReopenThreadResponse =
                client.post("/api/venue/$venueId/support/threads/$threadId/reopen") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, venueReopenThreadResponse.status)
            assertEquals(
                "OPEN",
                json.parseToJsonElement(venueReopenThreadResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive
                    .content,
            )

            val venueResolveThreadResponse =
                client.post("/api/venue/$venueId/support/threads/$threadId/resolve") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, venueResolveThreadResponse.status)
            val activeThreadsAfterResolveResponse =
                client.get("/api/venue/$venueId/support/threads?filter=active") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, activeThreadsAfterResolveResponse.status)
            assertTrue(
                json.parseToJsonElement(activeThreadsAfterResolveResponse.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .isEmpty(),
            )
            val resolvedThreadsResponse =
                client.get("/api/venue/$venueId/support/threads?filter=resolved") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, resolvedThreadsResponse.status)
            assertEquals(
                threadId.toString(),
                json.parseToJsonElement(resolvedThreadsResponse.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("threadId")
                    .jsonPrimitive
                    .content,
            )

            val guestReplyToResolvedThreadResponse =
                client.post("/api/guest/support/threads/$threadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Ещё актуально?"}""")
                }
            assertEquals(HttpStatusCode.OK, guestReplyToResolvedThreadResponse.status)
            assertEquals(
                "OPEN",
                json.parseToJsonElement(guestReplyToResolvedThreadResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive
                    .content,
            )
            val activeThreadsAfterResolvedReplyResponse =
                client.get("/api/venue/$venueId/support/threads?filter=active") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, activeThreadsAfterResolvedReplyResponse.status)
            assertEquals(
                threadId.toString(),
                json.parseToJsonElement(activeThreadsAfterResolvedReplyResponse.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("threadId")
                    .jsonPrimitive
                    .content,
            )

            val confirmedListResponse =
                client.get("/api/venue/bookings?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, confirmedListResponse.status)
            val confirmedItem =
                json.parseToJsonElement(confirmedListResponse.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("confirmed", confirmedItem.getValue("status").jsonPrimitive.content)

            val changeResponse =
                client.post("/api/venue/bookings/$bookingId/change?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $managerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {"scheduledLocalDate":"2030-01-10","scheduledLocalTime":"22:30","reasonText":"стол у окна"}
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, changeResponse.status)

            val changedListResponse =
                client.get("/api/venue/bookings?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, changedListResponse.status)
            val changedItem =
                json.parseToJsonElement(changedListResponse.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("changed", changedItem.getValue("status").jsonPrimitive.content)
            assertEquals("2030-01-10T19:30:00Z", changedItem.getValue("scheduledAt").jsonPrimitive.content)

            val changeMessage = outboxTexts(jdbcUrl, GUEST_ID).last()
            assertTrue(changeMessage.contains("🕒 Бронь №1 перенесена"), changeMessage)
            assertTrue(changeMessage.contains("Заведение: Booking Venue"), changeMessage)
            assertTrue(changeMessage.contains("Новое время: 10.01.2030, 22:30"), changeMessage)
            assertTrue(changeMessage.contains("Гостей: 4"), changeMessage)
            assertTrue(changeMessage.contains("Держим до 23:00"), changeMessage)
            assertTrue(changeMessage.contains("Комментарий: стол у окна"), changeMessage)
            assertFalse(changeMessage.contains("UTC"), changeMessage)
            assertFalse(changeMessage.contains("2030-01-10T19:30:00Z"), changeMessage)

            val cancelResponse =
                client.post("/api/venue/bookings/$bookingId/cancel?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $managerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"reasonText":"зал закрыт"}""")
                }
            assertEquals(HttpStatusCode.OK, cancelResponse.status)

            val cancelMessage = outboxTexts(jdbcUrl, GUEST_ID).last()
            assertTrue(cancelMessage.contains("❌ Бронь №1 отменена"), cancelMessage)
            assertTrue(cancelMessage.contains("Заведение: Booking Venue"), cancelMessage)
            assertTrue(cancelMessage.contains("Время брони: 10.01.2030, 22:30"), cancelMessage)
            assertTrue(cancelMessage.contains("Причина: зал закрыт"), cancelMessage)
            assertFalse(cancelMessage.contains("UTC"), cancelMessage)

            val invalidTransitionResponse =
                client.post("/api/venue/bookings/$bookingId/confirm?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.NotFound, invalidTransitionResponse.status)

            val secondCreateResponse =
                client.post("/api/guest/booking/create") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"venueId":$venueId,"scheduledAt":"2030-01-10T20:00:00Z","partySize":2}""")
                }
            assertEquals(HttpStatusCode.OK, secondCreateResponse.status)
            val secondBookingId =
                json.parseToJsonElement(secondCreateResponse.bodyAsText())
                    .jsonObject
                    .getValue("bookingId")
                    .jsonPrimitive
                    .content
                    .toLong()
            val blankCancelResponse =
                client.post("/api/venue/bookings/$secondBookingId/cancel?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $managerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"reasonText":"   "}""")
                }
            assertEquals(HttpStatusCode.OK, blankCancelResponse.status)
            val blankCancelMessage = outboxTexts(jdbcUrl, GUEST_ID).last()
            assertTrue(blankCancelMessage.contains("❌ Бронь №2 отменена"), blankCancelMessage)
            assertTrue(blankCancelMessage.contains("Время брони: 10.01.2030, 23:00"), blankCancelMessage)
            assertFalse(blankCancelMessage.contains("Причина:"), blankCancelMessage)
            assertFalse(blankCancelMessage.contains("не указана"), blankCancelMessage)
        }

    @Test
    fun `staff can view and mark booking arrival but cannot manage booking`() =
        testApplication {
            val jdbcUrl =
                "jdbc:h2:mem:venue-bookings-staff-${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
            val config =
                MapApplicationConfig(
                    "ktor.environment" to "test",
                    "db.jdbcUrl" to jdbcUrl,
                    "db.user" to "sa",
                    "db.password" to "",
                    "api.session.jwtSecret" to "secret-secret-secret-secret-secret",
                    "api.session.issuer" to "hookah",
                    "api.session.audience" to "miniapp",
                    "api.session.ttlSeconds" to "3600",
                )

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            seedSubscription(jdbcUrl, venueId)
            seedUser(jdbcUrl, GUEST_ID)
            seedUser(jdbcUrl, STAFF_ID)
            seedVenueMember(jdbcUrl, venueId, STAFF_ID, "STAFF")

            val guestToken = issueToken(config, GUEST_ID)
            val staffToken = issueToken(config, STAFF_ID)

            suspend fun createBooking(scheduledAt: String): Long {
                val response =
                    client.post("/api/guest/booking/create") {
                        headers {
                            append(HttpHeaders.Authorization, "Bearer $guestToken")
                            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        }
                        setBody(
                            """
                            {"venueId":$venueId,"scheduledAt":"$scheduledAt","partySize":2,"comment":"staff smoke"}
                            """.trimIndent(),
                        )
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                return json.parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue("bookingId")
                    .jsonPrimitive
                    .content
                    .toLong()
            }

            val bookingId = createBooking("2030-01-10T18:30:00Z")
            val listResponse =
                client.get("/api/venue/bookings?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.OK, listResponse.status)

            val confirmResponse =
                client.post("/api/venue/bookings/$bookingId/confirm?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, confirmResponse.status)

            val cancelResponse =
                client.post("/api/venue/bookings/$bookingId/cancel?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, cancelResponse.status)

            val changeResponse =
                client.post("/api/venue/bookings/$bookingId/change?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"scheduledAt":"2030-01-10T19:30:00Z"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, changeResponse.status)

            val messageResponse =
                client.post("/api/venue/bookings/$bookingId/message?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Можно уточнить время?"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, messageResponse.status)

            val resolveThreadResponse =
                client.post("/api/venue/$venueId/support/threads/1/resolve") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, resolveThreadResponse.status)

            val seatedId = createBooking("2030-01-11T18:30:00Z")
            val seatResponse =
                client.post("/api/venue/bookings/$seatedId/seat?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.OK, seatResponse.status)
            assertEquals(
                "seated",
                json.parseToJsonElement(seatResponse.bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content,
            )

            val noShowId = createBooking("2030-01-12T18:30:00Z")
            val noShowResponse =
                client.post("/api/venue/bookings/$noShowId/no-show?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.OK, noShowResponse.status)
            assertEquals(
                "no_show",
                json.parseToJsonElement(
                    noShowResponse.bodyAsText(),
                ).jsonObject.getValue("status").jsonPrimitive.content,
            )
        }

    @Test
    fun `venue booking list is empty-safe and foreign venue access is denied`() =
        testApplication {
            val jdbcUrl =
                "jdbc:h2:mem:venue-bookings-access-${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
            val config =
                MapApplicationConfig(
                    "ktor.environment" to "test",
                    "db.jdbcUrl" to jdbcUrl,
                    "db.user" to "sa",
                    "db.password" to "",
                    "api.session.jwtSecret" to "secret-secret-secret-secret-secret",
                    "api.session.issuer" to "hookah",
                    "api.session.audience" to "miniapp",
                    "api.session.ttlSeconds" to "3600",
                )

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            val foreignVenueId = seedVenue(jdbcUrl)
            seedSubscription(jdbcUrl, venueId)
            seedUser(jdbcUrl, GUEST_ID)
            seedUser(jdbcUrl, MANAGER_ID)
            seedVenueMember(jdbcUrl, venueId, MANAGER_ID, "MANAGER")

            val managerToken = issueToken(config, MANAGER_ID)
            val emptyListResponse =
                client.get("/api/venue/bookings?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, emptyListResponse.status)
            assertTrue(
                json.parseToJsonElement(emptyListResponse.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .isEmpty(),
            )

            val foreignListResponse =
                client.get("/api/venue/bookings?venueId=$foreignVenueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, foreignListResponse.status)

            val guestToken = issueToken(config, GUEST_ID)
            val createResponse =
                client.post("/api/guest/booking/create") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"venueId":$venueId,"scheduledAt":"2030-01-10T18:30:00Z","partySize":2}""")
                }
            assertEquals(HttpStatusCode.OK, createResponse.status)
            val bookingId =
                json.parseToJsonElement(createResponse.bodyAsText())
                    .jsonObject
                    .getValue("bookingId")
                    .jsonPrimitive
                    .content
                    .toLong()

            val foreignUpdateResponse =
                client.post("/api/venue/bookings/$bookingId/confirm?venueId=$foreignVenueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, foreignUpdateResponse.status)

            val foreignMessageResponse =
                client.post("/api/venue/bookings/$bookingId/message?venueId=$foreignVenueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $managerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Проверка"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, foreignMessageResponse.status)

            val validMessageResponse =
                client.post("/api/venue/bookings/$bookingId/message?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $managerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Проверка связи"}""")
                }
            assertEquals(HttpStatusCode.OK, validMessageResponse.status)
            val threadId =
                json.parseToJsonElement(validMessageResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
                    .getValue("threadId")
                    .jsonPrimitive
                    .content
                    .toLong()
            val foreignResolveResponse =
                client.post("/api/venue/$foreignVenueId/support/threads/$threadId/resolve") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, foreignResolveResponse.status)

            seedUser(jdbcUrl, STAFF_ID)
            val otherGuestToken = issueToken(config, STAFF_ID)
            val otherGuestResolveResponse =
                client.post("/api/guest/support/threads/$threadId/resolve") {
                    headers { append(HttpHeaders.Authorization, "Bearer $otherGuestToken") }
                }
            assertEquals(HttpStatusCode.NotFound, otherGuestResolveResponse.status)

            val blankMessageResponse =
                client.post("/api/venue/bookings/$bookingId/message?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $managerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"   "}""")
                }
            assertEquals(HttpStatusCode.BadRequest, blankMessageResponse.status)

            val longMessage = "x".repeat(1001)
            val longMessageResponse =
                client.post("/api/venue/bookings/$bookingId/message?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $managerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"$longMessage"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, longMessageResponse.status)
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

    private fun seedVenue(jdbcUrl: String): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Booking Venue', 'City', 'Address', 'PUBLISHED')
                    """.trimIndent(),
                    java.sql.Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO venue_settings (venue_id, timezone)
                VALUES (?, 'Europe/Moscow')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeUpdate()
            }
            venueId
        }

    private fun seedSubscription(
        jdbcUrl: String,
        venueId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
                VALUES (?, 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeUpdate()
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
                INSERT INTO users (telegram_user_id, username, first_name, updated_at)
                VALUES (?, ?, 'Name', CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, "u$userId")
                statement.executeUpdate()
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

    private fun outboxTexts(
        jdbcUrl: String,
        chatId: Long,
    ): List<String> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM telegram_outbox
                WHERE chat_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, chatId)
                statement.executeQuery().use { rs ->
                    val result = mutableListOf<String>()
                    while (rs.next()) {
                        val payload = json.parseToJsonElement(rs.getString("payload_json")).jsonObject
                        result.add(payload.getValue("text").jsonPrimitive.content)
                    }
                    result
                }
            }
        }

    private fun outboxPayloadJson(
        jdbcUrl: String,
        chatId: Long,
    ): List<String> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM telegram_outbox
                WHERE chat_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, chatId)
                statement.executeQuery().use { rs ->
                    val result = mutableListOf<String>()
                    while (rs.next()) {
                        result.add(rs.getString("payload_json"))
                    }
                    result
                }
            }
        }

    private fun supportThreadCount(
        jdbcUrl: String,
        venueId: Long,
        bookingId: Long,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM support_threads
                WHERE venue_id = ?
                  AND booking_id = ?
                  AND category = 'BOOKING'
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, bookingId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun supportMessageTexts(
        jdbcUrl: String,
        threadId: Long,
    ): List<String> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT text
                FROM support_messages
                WHERE thread_id = ?
                ORDER BY created_at, id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.executeQuery().use { rs ->
                    val result = mutableListOf<String>()
                    while (rs.next()) {
                        result.add(rs.getString("text"))
                    }
                    result
                }
            }
        }

    companion object {
        private const val GUEST_ID = 424242L
        private const val MANAGER_ID = 777777L
        private const val STAFF_ID = 888888L
    }
}
