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

            val cancelResponse =
                client.post("/api/guest/booking/cancel?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"bookingId":$bookingId}""")
                }
            assertEquals(HttpStatusCode.OK, cancelResponse.status)

            assertEquals(2, outboxCountForChat(jdbcUrl, TELEGRAM_USER_ID))
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
