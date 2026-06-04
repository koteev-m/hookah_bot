package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Date
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuestVisitRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `guest visits list and detail are scoped to current user`() =
        testApplication {
            val jdbcUrl =
                "jdbc:h2:mem:guest-visits-${UUID.randomUUID()};MODE=PostgreSQL;" +
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

            val guestOne = 1001L
            val guestTwo = 1002L
            val venueId = seedVenueAndUsers(jdbcUrl, guestOne, guestTwo)
            val guestOneVisit = seedVisit(jdbcUrl, venueId, guestOne, "BOOKING_SEATED")
            val guestTwoVisit = seedVisit(jdbcUrl, venueId, guestTwo, "BOOKING_SEATED")
            val token = issueToken(config, guestOne)

            val listResponse =
                client.get("/api/guest/visits") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val list = json.parseToJsonElement(listResponse.bodyAsText()).jsonObject.getValue("items").jsonArray
            assertEquals(1, list.size)
            assertEquals(guestOneVisit, list.first().jsonObject.getValue("visitId").jsonPrimitive.content.toLong())
            assertEquals("Mix", list.first().jsonObject.getValue("venueName").jsonPrimitive.content)

            val detailResponse =
                client.get("/api/guest/visits/$guestOneVisit") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, detailResponse.status)
            val detail = json.parseToJsonElement(detailResponse.bodyAsText()).jsonObject.getValue("visit").jsonObject
            assertEquals(guestOneVisit, detail.getValue("visitId").jsonPrimitive.content.toLong())
            assertTrue(detail.getValue("orders").jsonArray.isEmpty())

            val forbiddenDetail =
                client.get("/api/guest/visits/$guestTwoVisit") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.NotFound, forbiddenDetail.status)
        }

    private fun seedVenueAndUsers(
        jdbcUrl: String,
        vararg userIds: Long,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            userIds.forEach { userId ->
                connection.prepareStatement(
                    """
                    INSERT INTO users (telegram_user_id, username, first_name)
                    VALUES (?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setString(2, "user$userId")
                    statement.setString(3, "User $userId")
                    statement.executeUpdate()
                }
            }
            connection.prepareStatement(
                """
                INSERT INTO venues (name, city, address, status)
                VALUES ('Mix', 'Москва', 'Тверская, 1', 'PUBLISHED')
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
                }
            }
        }

    private fun seedVisit(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        source: String,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO visits (venue_id, user_id, source, occurred_at, service_date)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, source)
                statement.setTimestamp(4, Timestamp.from(Instant.parse("2030-05-12T18:00:00Z")))
                statement.setDate(5, Date.valueOf(LocalDate.of(2030, 5, 12)))
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
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
}
