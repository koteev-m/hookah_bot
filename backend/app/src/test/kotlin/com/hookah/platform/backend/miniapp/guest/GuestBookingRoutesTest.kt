package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.VenueStatus
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
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuestBookingRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `guest and venue booking flow works`() =
        testApplication {
            val jdbcUrl =
                "jdbc:h2:mem:booking-flow-${UUID.randomUUID()};MODE=PostgreSQL;" +
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

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            setVenueTimezone(jdbcUrl, venueId, "Europe/Moscow")
            seedSubscription(jdbcUrl, venueId)
            seedUser(jdbcUrl, TELEGRAM_USER_ID)
            val managerId = 777777L
            seedUser(jdbcUrl, managerId)
            seedVenueMember(jdbcUrl, venueId, managerId, "MANAGER")

            val guestToken = issueToken(config, TELEGRAM_USER_ID)
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
            val created = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
            val bookingId = created.getValue("bookingId").jsonPrimitive.content.toLong()
            assertEquals("pending", created.getValue("status").jsonPrimitive.content)
            assertEquals("2030-01-10" to 1, bookingDisplay(jdbcUrl, bookingId))

            val updateResponse =
                client.post("/api/guest/booking/update?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {"bookingId":$bookingId,"scheduledAt":"2030-01-10T19:00:00Z","partySize":5,"comment":"near stage"}
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, updateResponse.status)

            val managerToken = issueToken(config, managerId)
            val confirmResponse =
                client.post("/api/venue/bookings/$bookingId/confirm?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, confirmResponse.status)

            val changeResponse =
                client.post("/api/venue/bookings/$bookingId/change?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $managerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"scheduledAt":"2030-01-10T20:00:00Z"}""")
                }
            assertEquals(HttpStatusCode.OK, changeResponse.status)

            val listResponse =
                client.get("/api/guest/booking?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val list = json.parseToJsonElement(listResponse.bodyAsText()).jsonObject.getValue("items").jsonArray
            assertEquals(1, list.size)
            assertEquals("changed", list.first().jsonObject.getValue("status").jsonPrimitive.content)

            val guestConfirmResponse =
                client.post("/api/guest/booking/confirm?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"bookingId":$bookingId}""")
                }
            assertEquals(HttpStatusCode.OK, guestConfirmResponse.status)
            val guestConfirmed = json.parseToJsonElement(guestConfirmResponse.bodyAsText()).jsonObject
            assertEquals("changed", guestConfirmed.getValue("status").jsonPrimitive.content)
            check(guestConfirmed.getValue("lastGuestConfirmationAt").jsonPrimitive.content.isNotBlank())

            val cancelResponse =
                client.post("/api/guest/booking/cancel?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"bookingId":$bookingId}""")
                }
            assertEquals(HttpStatusCode.OK, cancelResponse.status)

            val seatedId = createBooking(client, guestToken, venueId, "2030-01-12T18:30:00Z")
            val seatResponse =
                client.post("/api/venue/bookings/$seatedId/seat?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, seatResponse.status)
            assertEquals(
                "seated",
                json.parseToJsonElement(seatResponse.bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content,
            )

            val noShowId = createBooking(client, guestToken, venueId, "2030-01-12T19:30:00Z")
            val noShowResponse =
                client.post("/api/venue/bookings/$noShowId/no-show?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, noShowResponse.status)
            assertEquals(
                "no_show",
                json.parseToJsonElement(
                    noShowResponse.bodyAsText(),
                ).jsonObject.getValue("status").jsonPrimitive.content,
            )

            assertEquals(2, outboxCountForChat(jdbcUrl, TELEGRAM_USER_ID))
        }

    @Test
    fun `booking display number increments per venue and booking date`() =
        testApplication {
            val jdbcUrl =
                "jdbc:h2:mem:booking-display-${UUID.randomUUID()};MODE=PostgreSQL;" +
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

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            seedSubscription(jdbcUrl, venueId)
            seedUser(jdbcUrl, TELEGRAM_USER_ID)
            val guestToken = issueToken(config, TELEGRAM_USER_ID)

            val firstId = createBooking(client, guestToken, venueId, "2030-01-10T18:30:00Z")
            setBookingCreatedAt(jdbcUrl, firstId, "2030-01-01T10:00:00Z")
            val secondId = createBooking(client, guestToken, venueId, "2030-01-10T19:30:00Z")
            setBookingCreatedAt(jdbcUrl, secondId, "2030-01-02T10:00:00Z")
            val nextDateId = createBooking(client, guestToken, venueId, "2030-01-11T18:00:00Z")
            setBookingCreatedAt(jdbcUrl, nextDateId, "2030-01-02T11:00:00Z")

            assertEquals("2030-01-10" to 1, bookingDisplay(jdbcUrl, firstId))
            assertEquals("2030-01-10" to 2, bookingDisplay(jdbcUrl, secondId))
            assertEquals("2030-01-11" to 1, bookingDisplay(jdbcUrl, nextDateId))
        }

    @Test
    fun `guest attendance route notifies staff chat only once`() =
        testApplication {
            val jdbcUrl =
                "jdbc:h2:mem:booking-attendance-route-${UUID.randomUUID()};MODE=PostgreSQL;" +
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

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            seedSubscription(jdbcUrl, venueId)
            seedUser(jdbcUrl, TELEGRAM_USER_ID)
            seedUser(jdbcUrl, MANAGER_ID)
            seedVenueMember(jdbcUrl, venueId, MANAGER_ID, "MANAGER")
            val guestToken = issueToken(config, TELEGRAM_USER_ID)
            val managerToken = issueToken(config, MANAGER_ID)
            val bookingId = createBooking(client, guestToken, venueId, "2030-01-10T18:30:00Z")
            val confirmResponse =
                client.post("/api/venue/bookings/$bookingId/confirm?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, confirmResponse.status)
            linkStaffChat(jdbcUrl, venueId, STAFF_CHAT_ID)

            repeat(2) {
                val guestConfirmResponse =
                    client.post("/api/guest/booking/confirm?venueId=$venueId") {
                        headers {
                            append(HttpHeaders.Authorization, "Bearer $guestToken")
                            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        }
                        setBody("""{"bookingId":$bookingId}""")
                    }
                assertEquals(HttpStatusCode.OK, guestConfirmResponse.status)
                assertEquals(
                    "confirmed",
                    json.parseToJsonElement(guestConfirmResponse.bodyAsText())
                        .jsonObject
                        .getValue("status")
                        .jsonPrimitive
                        .content,
                )
            }

            assertEquals(1, outboxCountForChat(jdbcUrl, STAFF_CHAT_ID))
            val staffText = outboxTextForChat(jdbcUrl, STAFF_CHAT_ID)
            assertTrue(staffText.contains("✅ Гость подтвердил визит"))
            assertTrue(staffText.contains("Бронь №1 · 10.01.2030, 21:30"))
            assertTrue(staffText.contains("Гость: имя не указано"))
            assertTrue(staffText.contains("Гостей: 2"))
            assertTrue(staffText.contains("Держим стол до 22:00"))
            assertFalse(staffText.contains(TELEGRAM_USER_ID.toString()))
            assertFalse(staffText.contains("u$TELEGRAM_USER_ID"))
        }

    @Test
    fun `booking display date uses venue timezone`() =
        testApplication {
            val jdbcUrl =
                "jdbc:h2:mem:booking-display-zone-${UUID.randomUUID()};MODE=PostgreSQL;" +
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

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            seedSubscription(jdbcUrl, venueId)
            seedUser(jdbcUrl, TELEGRAM_USER_ID)
            setVenueTimezone(jdbcUrl, venueId, "Pacific/Honolulu")
            val guestToken = issueToken(config, TELEGRAM_USER_ID)

            val bookingId = createBooking(client, guestToken, venueId, "2030-01-10T02:30:00Z")

            assertEquals("2030-01-09" to 1, bookingDisplay(jdbcUrl, bookingId))
        }

    @Test
    fun `guest active booking list is user scoped sorted and uses public venue local labels`() =
        testApplication {
            val jdbcUrl =
                "jdbc:h2:mem:booking-active-list-${UUID.randomUUID()};MODE=PostgreSQL;" +
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

            val firstVenueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue, name = "Микс")
            val secondVenueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue, name = "Дым")
            seedSubscription(jdbcUrl, firstVenueId)
            seedSubscription(jdbcUrl, secondVenueId)
            seedUser(jdbcUrl, TELEGRAM_USER_ID)
            seedUser(jdbcUrl, TELEGRAM_USER_ID + 1)
            setVenueTimezone(jdbcUrl, firstVenueId, "Europe/Moscow")
            setVenueTimezone(jdbcUrl, secondVenueId, "Asia/Yekaterinburg")
            val guestToken = issueToken(config, TELEGRAM_USER_ID)
            val foreignGuestToken = issueToken(config, TELEGRAM_USER_ID + 1)

            val laterBookingId = createBooking(client, guestToken, firstVenueId, "2030-01-10T18:00:00Z")
            val earlierBookingId = createBooking(client, guestToken, secondVenueId, "2030-01-09T17:00:00Z")
            createBooking(client, foreignGuestToken, firstVenueId, "2030-01-09T16:00:00Z")
            setBookingStatus(jdbcUrl, laterBookingId, "CONFIRMED")

            val response =
                client.get("/api/guest/bookings") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val items = json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("items").jsonArray
            assertEquals(2, items.size)
            val first = items[0].jsonObject
            val second = items[1].jsonObject
            assertEquals(earlierBookingId.toString(), first.getValue("bookingId").jsonPrimitive.content)
            assertEquals("Дым", first.getValue("venueName").jsonPrimitive.content)
            assertEquals("Бронь №1", first.getValue("displayLabel").jsonPrimitive.content)
            assertEquals("09.01.2030, 22:00", first.getValue("scheduledAtDisplay").jsonPrimitive.content)
            assertEquals(laterBookingId.toString(), second.getValue("bookingId").jsonPrimitive.content)
            assertEquals("Микс", second.getValue("venueName").jsonPrimitive.content)
            assertEquals("Бронь №1", second.getValue("displayLabel").jsonPrimitive.content)
            assertEquals("Подтверждена", second.getValue("statusLabel").jsonPrimitive.content)
            assertEquals("10.01.2030, 21:00", second.getValue("scheduledAtDisplay").jsonPrimitive.content)
            assertEquals("21:30", second.getValue("arrivalDeadlineTimeDisplay").jsonPrimitive.content)
            assertEquals("true", second.getValue("canChange").jsonPrimitive.content)
            assertEquals("true", second.getValue("canCancel").jsonPrimitive.content)
        }

    @Test
    fun `guest booking create and update reject schedule violations`() =
        testApplication {
            val jdbcUrl =
                "jdbc:h2:mem:booking-schedule-guard-${UUID.randomUUID()};MODE=PostgreSQL;" +
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

            seedUser(jdbcUrl, TELEGRAM_USER_ID)
            val guestToken = issueToken(config, TELEGRAM_USER_ID)
            val missingScheduleVenueId =
                seedVenue(
                    jdbcUrl = jdbcUrl,
                    status = VenueStatus.PUBLISHED.dbValue,
                    name = "Missing schedule",
                    seedBookingHours = false,
                )
            seedSubscription(jdbcUrl, missingScheduleVenueId)
            setVenueTimezone(jdbcUrl, missingScheduleVenueId, "UTC")

            suspend fun createResponse(
                venueId: Long,
                scheduledAt: String,
            ) = client.post("/api/guest/booking/create") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $guestToken")
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                }
                setBody("""{"venueId":$venueId,"scheduledAt":"$scheduledAt","partySize":2}""")
            }

            val missingScheduleResponse = createResponse(missingScheduleVenueId, "2030-01-10T18:00:00Z")
            assertEquals(HttpStatusCode.BadRequest, missingScheduleResponse.status)
            val missingScheduleError =
                json.parseToJsonElement(missingScheduleResponse.bodyAsText())
                    .jsonObject
                    .getValue("error")
                    .jsonObject
            assertEquals(
                ApiErrorCodes.VENUE_SCHEDULE_NOT_CONFIGURED,
                missingScheduleError.getValue("code").jsonPrimitive.content,
            )
            assertEquals(
                "Заведение пока не настроило график бронирования.",
                missingScheduleError.getValue("message").jsonPrimitive.content,
            )

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue, name = "Guarded schedule")
            seedSubscription(jdbcUrl, venueId)
            setVenueTimezone(jdbcUrl, venueId, "UTC")

            val closedWeekdayDate = LocalDate.of(2030, 1, 10)
            setWeekdayHours(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                weekday = closedWeekdayDate.dayOfWeek.value,
                opensAt = "00:00:00",
                closesAt = "00:00:00",
                isClosed = true,
            )
            val closedWeekdayResponse = createResponse(venueId, "2030-01-10T18:00:00Z")
            assertEquals(HttpStatusCode.BadRequest, closedWeekdayResponse.status)
            val closedWeekdayError =
                json.parseToJsonElement(closedWeekdayResponse.bodyAsText())
                    .jsonObject
                    .getValue("error")
                    .jsonObject
            assertEquals(
                ApiErrorCodes.VENUE_CLOSED_ON_SELECTED_DATE,
                closedWeekdayError.getValue("code").jsonPrimitive.content,
            )
            assertEquals(
                "На выбранную дату заведение не работает. Выберите другую дату.",
                closedWeekdayError.getValue("message").jsonPrimitive.content,
            )

            setWeekdayHours(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                weekday = closedWeekdayDate.dayOfWeek.value,
                opensAt = "18:00:00",
                closesAt = "22:00:00",
                isClosed = false,
            )
            val beforeOpenResponse = createResponse(venueId, "2030-01-10T17:30:00Z")
            assertEquals(HttpStatusCode.BadRequest, beforeOpenResponse.status)
            val beforeOpenError =
                json.parseToJsonElement(beforeOpenResponse.bodyAsText())
                    .jsonObject
                    .getValue("error")
                    .jsonObject
            assertEquals(
                ApiErrorCodes.VENUE_BOOKING_OUTSIDE_HOURS,
                beforeOpenError.getValue("code").jsonPrimitive.content,
            )
            assertEquals(
                "На выбранное время бронь недоступна. В этот день заведение работает с 18:00 до 22:00.",
                beforeOpenError.getValue("message").jsonPrimitive.content,
            )
            val inHoursResponse = createResponse(venueId, "2030-01-10T19:00:00Z")
            assertEquals(HttpStatusCode.OK, inHoursResponse.status)

            val closedOverrideDate = LocalDate.of(2030, 1, 11)
            setDateOverrideHours(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                serviceDate = closedOverrideDate,
                opensAt = "00:00:00",
                closesAt = "00:00:00",
                isClosed = true,
                guestNote = "ремонт",
            )
            val closedOverrideResponse = createResponse(venueId, "2030-01-11T19:00:00Z")
            assertEquals(HttpStatusCode.BadRequest, closedOverrideResponse.status)
            val closedOverrideError =
                json.parseToJsonElement(closedOverrideResponse.bodyAsText())
                    .jsonObject
                    .getValue("error")
                    .jsonObject
            assertEquals(
                ApiErrorCodes.VENUE_CLOSED_ON_SELECTED_DATE,
                closedOverrideError.getValue("code").jsonPrimitive.content,
            )
            assertEquals(
                "На выбранную дату заведение не работает: ремонт. Выберите другую дату.",
                closedOverrideError.getValue("message").jsonPrimitive.content,
            )

            val openOverrideDate = LocalDate.of(2030, 1, 12)
            setDateOverrideHours(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                serviceDate = openOverrideDate,
                opensAt = "20:00:00",
                closesAt = "23:00:00",
                isClosed = false,
            )
            val overrideBeforeOpenResponse = createResponse(venueId, "2030-01-12T19:30:00Z")
            assertEquals(HttpStatusCode.BadRequest, overrideBeforeOpenResponse.status)
            val overrideBeforeOpenError =
                json.parseToJsonElement(overrideBeforeOpenResponse.bodyAsText())
                    .jsonObject
                    .getValue("error")
                    .jsonObject
            assertEquals(
                ApiErrorCodes.VENUE_BOOKING_OUTSIDE_HOURS,
                overrideBeforeOpenError.getValue("code").jsonPrimitive.content,
            )
            assertEquals(
                "На выбранное время бронь недоступна. В этот день заведение работает с 20:00 до 23:00.",
                overrideBeforeOpenError.getValue("message").jsonPrimitive.content,
            )
            val bookingId = createBooking(client, guestToken, venueId, "2030-01-12T20:30:00Z")

            val overnightDate = LocalDate.of(2030, 1, 13)
            setDateOverrideHours(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                serviceDate = overnightDate,
                opensAt = "18:00:00",
                closesAt = "02:00:00",
                isClosed = false,
            )
            setDateOverrideHours(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                serviceDate = overnightDate.plusDays(1),
                opensAt = "00:00:00",
                closesAt = "00:00:00",
                isClosed = true,
            )
            val overnightAllowedResponse = createResponse(venueId, "2030-01-14T00:30:00Z")
            assertEquals(HttpStatusCode.OK, overnightAllowedResponse.status)
            val overnightTooLateResponse = createResponse(venueId, "2030-01-14T01:30:00Z")
            assertEquals(HttpStatusCode.BadRequest, overnightTooLateResponse.status)

            val updateClosedOverrideResponse =
                client.post("/api/guest/booking/update?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"bookingId":$bookingId,"scheduledAt":"2030-01-11T19:00:00Z","partySize":2}""")
                }
            assertEquals(HttpStatusCode.BadRequest, updateClosedOverrideResponse.status)
            val updateClosedOverrideError =
                json.parseToJsonElement(updateClosedOverrideResponse.bodyAsText())
                    .jsonObject
                    .getValue("error")
                    .jsonObject
            assertEquals(
                ApiErrorCodes.VENUE_CLOSED_ON_SELECTED_DATE,
                updateClosedOverrideError.getValue("code").jsonPrimitive.content,
            )

            val moscowVenueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue, name = "Moscow schedule")
            seedSubscription(jdbcUrl, moscowVenueId)
            setVenueTimezone(jdbcUrl, moscowVenueId, "Europe/Moscow")
            val moscowLocalDate = LocalDate.of(2030, 1, 15)
            setWeekdayHours(
                jdbcUrl = jdbcUrl,
                venueId = moscowVenueId,
                weekday = moscowLocalDate.dayOfWeek.value,
                opensAt = "21:00:00",
                closesAt = "23:00:00",
                isClosed = false,
            )
            val moscowBeforeOpenResponse = createResponse(moscowVenueId, "2030-01-15T17:30:00Z")
            assertEquals(HttpStatusCode.BadRequest, moscowBeforeOpenResponse.status)
            val moscowInHoursResponse = createResponse(moscowVenueId, "2030-01-15T18:30:00Z")
            assertEquals(HttpStatusCode.OK, moscowInHoursResponse.status)
        }

    @Test
    fun `dialog state allows booking communication states`() =
        testApplication {
            val jdbcUrl =
                "jdbc:h2:mem:booking-dialog-state-${UUID.randomUUID()};MODE=PostgreSQL;" +
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

            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                listOf(
                    100L to "VENUE_BOOKING_WAIT_GUEST_MESSAGE",
                    101L to "VENUE_BOOKING_CANCEL_WAIT_REASON",
                    102L to "GUEST_BOOKING_WAIT_REPLY",
                    103L to "VENUE_BOOKING_HOLD_WAIT_CUSTOM_MINUTES",
                ).forEach { (chatId, state) ->
                    connection.prepareStatement(
                        """
                        INSERT INTO telegram_dialog_state (chat_id, state, payload)
                        VALUES (?, ?, ?::jsonb)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, chatId)
                        statement.setString(2, state)
                        statement.setString(3, """{"venue_id":"1","booking_id":"2"}""")
                        assertEquals(1, statement.executeUpdate())
                    }
                }
            }
        }

    private suspend fun createBooking(
        client: io.ktor.client.HttpClient,
        guestToken: String,
        venueId: Long,
        scheduledAt: String,
    ): Long {
        val response =
            client.post("/api/guest/booking/create") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $guestToken")
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                }
                setBody(
                    """
                    {"venueId":$venueId,"scheduledAt":"$scheduledAt","partySize":2,"comment":null}
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

    private fun setBookingCreatedAt(
        jdbcUrl: String,
        bookingId: Long,
        createdAt: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                UPDATE bookings
                SET created_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, createdAt)
                statement.setLong(2, bookingId)
                statement.executeUpdate()
            }
        }
    }

    private fun bookingDisplay(
        jdbcUrl: String,
        bookingId: Long,
    ): Pair<String, Int> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT display_date, display_number
                FROM bookings
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, bookingId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getDate("display_date").toLocalDate().toString() to rs.getInt("display_number")
                }
            }
        }

    private fun setVenueTimezone(
        jdbcUrl: String,
        venueId: Long,
        timezone: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_settings (
                    venue_id,
                    notify_orders_enabled,
                    notify_staff_calls_enabled,
                    notify_cancellations_enabled,
                    timezone
                )
                VALUES (?, TRUE, TRUE, TRUE, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, timezone)
                statement.executeUpdate()
            }
        }
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
        status: String,
        name: String = "Booking Venue",
        seedBookingHours: Boolean = true,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES (?, 'City', 'Address', ?)
                    """.trimIndent(),
                    java.sql.Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setString(1, name)
                    statement.setString(2, status)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            if (seedBookingHours) {
                seedDailyBookingHours(connection, venueId)
            }
            venueId
        }

    private fun seedDailyBookingHours(
        connection: java.sql.Connection,
        venueId: Long,
        opensAt: String = "00:00:00",
        closesAt: String = "00:00:00",
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
                statement.setTime(3, java.sql.Time.valueOf(opensAt))
                statement.setTime(4, java.sql.Time.valueOf(closesAt))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun setWeekdayHours(
        jdbcUrl: String,
        venueId: Long,
        weekday: Int,
        opensAt: String,
        closesAt: String,
        isClosed: Boolean,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                UPDATE venue_booking_hours
                SET opens_at = ?, closes_at = ?, is_closed = ?
                WHERE venue_id = ? AND weekday = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setTime(1, java.sql.Time.valueOf(opensAt))
                statement.setTime(2, java.sql.Time.valueOf(closesAt))
                statement.setBoolean(3, isClosed)
                statement.setLong(4, venueId)
                statement.setInt(5, weekday)
                statement.executeUpdate()
            }
        }
    }

    private fun setDateOverrideHours(
        jdbcUrl: String,
        venueId: Long,
        serviceDate: LocalDate,
        opensAt: String,
        closesAt: String,
        isClosed: Boolean,
        guestNote: String? = null,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_booking_hours_overrides
                    (venue_id, service_date, opens_at, closes_at, is_closed, guest_note)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setDate(2, java.sql.Date.valueOf(serviceDate))
                statement.setTime(3, java.sql.Time.valueOf(opensAt))
                statement.setTime(4, java.sql.Time.valueOf(closesAt))
                statement.setBoolean(5, isClosed)
                statement.setString(6, guestNote)
                statement.executeUpdate()
            }
        }
    }

    private fun setBookingStatus(
        jdbcUrl: String,
        bookingId: Long,
        status: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                UPDATE bookings
                SET status = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, status)
                statement.setLong(2, bookingId)
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

    private fun linkStaffChat(
        jdbcUrl: String,
        venueId: Long,
        staffChatId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                UPDATE venues
                SET staff_chat_id = ?,
                    staff_chat_linked_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, staffChatId)
                statement.setLong(2, venueId)
                statement.executeUpdate()
            }
        }
    }

    private fun outboxCountForChat(
        jdbcUrl: String,
        chatId: Long,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM telegram_outbox WHERE chat_id = ?").use { statement ->
                statement.setLong(1, chatId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun outboxTextForChat(
        jdbcUrl: String,
        chatId: Long,
    ): String =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM telegram_outbox
                WHERE chat_id = ? AND method = 'sendMessage'
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, chatId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    json.parseToJsonElement(rs.getString("payload_json"))
                        .jsonObject
                        .getValue("text")
                        .jsonPrimitive
                        .content
                }
            }
        }

    companion object {
        private const val TELEGRAM_USER_ID = 424242L
        private const val MANAGER_ID = 515151L
        private const val STAFF_CHAT_ID = -900900L
    }
}
