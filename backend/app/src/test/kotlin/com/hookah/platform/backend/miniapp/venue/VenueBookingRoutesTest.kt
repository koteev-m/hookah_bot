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

            val confirmResponse =
                client.post("/api/venue/bookings/$bookingId/confirm?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, confirmResponse.status)

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
                    setBody("""{"scheduledAt":"2030-01-10T19:30:00Z"}""")
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

            val changeMessages = outboxTexts(jdbcUrl, GUEST_ID)
            assertTrue(changeMessages.any { it.contains("Booking Venue") && it.contains("перенесена") })
            assertTrue(changeMessages.any { it.contains("10.01.2030, 22:30") })
            assertFalse(changeMessages.any { it.contains("2030-01-10T19:30:00Z") })

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
            assertTrue(cancelMessage.contains("Booking Venue"))
            assertTrue(cancelMessage.contains("10.01.2030, 22:30"))
            assertTrue(cancelMessage.contains("Причина: зал закрыт"))
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

    companion object {
        private const val GUEST_ID = 424242L
        private const val MANAGER_ID = 777777L
        private const val STAFF_ID = 888888L
    }
}
