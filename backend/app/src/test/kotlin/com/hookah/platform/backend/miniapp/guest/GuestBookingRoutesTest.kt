package com.hookah.platform.backend.miniapp.guest

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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

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
                json.parseToJsonElement(noShowResponse.bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content,
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
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venues (name, city, address, status)
                VALUES ('Booking Venue', 'City', 'Address', ?)
                """.trimIndent(),
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, status)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
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

    companion object {
        private const val TELEGRAM_USER_ID = 424242L
    }
}
