package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class GuestFavoritesRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `favorite venue routes are scoped to current user and idempotent`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-favorites-venues")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")
            val fixture = seedFixture(jdbcUrl)
            val token = issueToken(config, GUEST_ONE)

            val postResponse =
                client.post("/api/guest/favorites/venues/${fixture.visibleVenueId}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            val secondPostResponse =
                client.post("/api/guest/favorites/venues/${fixture.visibleVenueId}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, postResponse.status)
            assertEquals(HttpStatusCode.OK, secondPostResponse.status)

            val listResponse =
                client.get("/api/guest/favorites/venues") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            val venues = json.parseToJsonElement(listResponse.bodyAsText()).jsonObject.getValue("venues").jsonArray
            assertEquals(1, venues.size)
            assertEquals(fixture.visibleVenueId, venues.first().jsonObject.getValue("venueId").jsonPrimitive.content.toLong())

            val deleteResponse =
                client.delete("/api/guest/favorites/venues/${fixture.visibleVenueId}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, deleteResponse.status)

            val emptyResponse =
                client.get("/api/guest/favorites/venues") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            val emptyVenues = json.parseToJsonElement(emptyResponse.bodyAsText()).jsonObject.getValue("venues").jsonArray
            assertEquals(0, emptyVenues.size)
        }

    @Test
    fun `favorite item routes validate venue and availability`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-favorites-items")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")
            val fixture = seedFixture(jdbcUrl)
            val token = issueToken(config, GUEST_ONE)

            val addAvailable =
                client.post("/api/guest/favorites/items/${fixture.availableItemId}?venueId=${fixture.visibleVenueId}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            val addUnavailable =
                client.post("/api/guest/favorites/items/${fixture.unavailableItemId}?venueId=${fixture.visibleVenueId}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, addAvailable.status)
            assertEquals(HttpStatusCode.NotFound, addUnavailable.status)
            assertApiErrorEnvelope(addUnavailable, ApiErrorCodes.NOT_FOUND)

            val listResponse =
                client.get("/api/guest/favorites/items?venueId=${fixture.visibleVenueId}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            val items = json.parseToJsonElement(listResponse.bodyAsText()).jsonObject.getValue("items").jsonArray
            assertEquals(1, items.size)
            assertEquals(fixture.availableItemId, items.first().jsonObject.getValue("itemId").jsonPrimitive.content.toLong())
        }

    private fun buildJdbcUrl(name: String): String =
        "jdbc:h2:mem:$name-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"

    private fun buildConfig(jdbcUrl: String): MapApplicationConfig =
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

    private fun seedFixture(jdbcUrl: String): FavoritesRouteFixture =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            insertUser(connection, GUEST_ONE)
            insertUser(connection, GUEST_TWO)
            val visibleVenueId = insertVenue(connection, "Mix", VenueStatus.PUBLISHED.dbValue)
            val categoryId = insertCategory(connection, visibleVenueId)
            FavoritesRouteFixture(
                visibleVenueId = visibleVenueId,
                availableItemId = insertItem(connection, visibleVenueId, categoryId, "Кальян", available = true),
                unavailableItemId = insertItem(connection, visibleVenueId, categoryId, "Сок", available = false),
            )
        }

    private fun insertUser(
        connection: Connection,
        userId: Long,
    ) {
        connection.prepareStatement(
            "INSERT INTO users (telegram_user_id, username, first_name) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, "user$userId")
            statement.setString(3, "User $userId")
            statement.executeUpdate()
        }
    }

    private fun insertVenue(
        connection: Connection,
        name: String,
        status: String,
    ): Long =
        connection.prepareStatement(
            "INSERT INTO venues (name, city, address, status) VALUES (?, 'Москва', 'Тверская, 1', ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, name)
            statement.setString(2, status)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun insertCategory(
        connection: Connection,
        venueId: Long,
    ): Long =
        connection.prepareStatement(
            "INSERT INTO menu_categories (venue_id, name) VALUES (?, 'Кальяны')",
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun insertItem(
        connection: Connection,
        venueId: Long,
        categoryId: Long,
        name: String,
        available: Boolean,
    ): Long =
        connection.prepareStatement(
            "INSERT INTO menu_items (venue_id, category_id, name, price_minor, currency, is_available) VALUES (?, ?, ?, 1000, 'RUB', ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, categoryId)
            statement.setString(3, name)
            statement.setBoolean(4, available)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
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

    private data class FavoritesRouteFixture(
        val visibleVenueId: Long,
        val availableItemId: Long,
        val unavailableItemId: Long,
    )

    private companion object {
        const val GUEST_ONE = 1001L
        const val GUEST_TWO = 1002L
    }
}
