package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.MenuResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class GuestVenueMenuRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `menu without auth returns unauthorized`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "app.env" to appEnv,
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to ""
            )
        }

        application { module() }

        val response = client.get("/api/guest/venue/1/menu")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.UNAUTHORIZED)
    }

    @Test
    fun `menu returns published categories and items`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-menu")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venues = seedVenues(jdbcUrl)
        val menu = seedMenu(jdbcUrl, venues.publishedId)
        val token = issueToken(config)

        val response = client.get("/api/guest/venue/${venues.publishedId}/menu") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString(MenuResponse.serializer(), response.bodyAsText())
        assertEquals(venues.publishedId, payload.venueId)
        assertEquals(2, payload.categories.size)
        assertEquals(menu.firstCategoryId, payload.categories[0].id)
        assertEquals(menu.secondCategoryId, payload.categories[1].id)

        val firstCategoryItems = payload.categories[0].items
        assertEquals(2, firstCategoryItems.size)
        assertEquals(menu.firstCategoryItemIds[0], firstCategoryItems[0].id)
        assertEquals(menu.firstCategoryItemIds[1], firstCategoryItems[1].id)
        assertFalse(firstCategoryItems[1].isAvailable)

        val secondCategoryItems = payload.categories[1].items
        assertEquals(1, secondCategoryItems.size)
        assertEquals(menu.secondCategoryItemId, secondCategoryItems[0].id)
        assertTrue(secondCategoryItems[0].isAvailable)
    }

    @Test
    fun `menu for hidden venue returns empty categories`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-hidden-menu")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venues = seedVenues(jdbcUrl)
        val token = issueToken(config)

        val response = client.get("/api/guest/venue/${venues.hiddenId}/menu") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString(MenuResponse.serializer(), response.bodyAsText())
        assertEquals(venues.hiddenId, payload.venueId)
        assertTrue(payload.categories.isEmpty())
    }

    @Test
    fun `menu for suspended venue returns locked by default`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-suspended-menu")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venues = seedVenues(jdbcUrl)
        val token = issueToken(config)

        val response = client.get("/api/guest/venue/${venues.suspendedId}/menu") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.Locked, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.SERVICE_SUSPENDED)
    }

    @Test
    fun `menu for suspended venue hides when configured`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-suspended-menu-hide")
        val config = buildConfig(jdbcUrl, suspendedMode = "hide")

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venues = seedVenues(jdbcUrl)
        val token = issueToken(config)

        val response = client.get("/api/guest/venue/${venues.suspendedId}/menu") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
    }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String, suspendedMode: String? = null): MapApplicationConfig {
        val entries = mutableListOf(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to ""
        )
        if (suspendedMode != null) {
            entries.add("api.guest.suspendedMode" to suspendedMode)
        }
        return MapApplicationConfig(*entries.toTypedArray())
    }

    private fun issueToken(config: MapApplicationConfig): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(TELEGRAM_USER_ID).token
    }

    private fun seedVenues(jdbcUrl: String): SeededVenues {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val publishedId = insertVenue(connection, "Published", "City", "Address", VenueStatuses.ACTIVE_PUBLISHED)
            val hiddenId = insertVenue(connection, "Hidden", "City", "Address", VenueStatuses.ACTIVE_HIDDEN)
            val suspendedId = insertVenue(connection, "Suspended", "City", "Address", VenueStatuses.SUSPENDED_BY_PLATFORM)
            return SeededVenues(publishedId, hiddenId, suspendedId)
        }
    }

    private fun seedMenu(jdbcUrl: String, venueId: Long): SeededMenu {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val secondCategoryId = insertMenuCategory(connection, venueId, "Category A", 2)
            val firstCategoryId = insertMenuCategory(connection, venueId, "Category B", 1)
            val firstItemId = insertMenuItem(
                connection,
                venueId,
                firstCategoryId,
                "Item One",
                1200,
                "RUB",
                true
            )
            val secondItemId = insertMenuItem(
                connection,
                venueId,
                firstCategoryId,
                "Item Two",
                900,
                "USD",
                false
            )
            val thirdItemId = insertMenuItem(
                connection,
                venueId,
                secondCategoryId,
                "Item Three",
                1500,
                "EUR",
                true
            )
            return SeededMenu(
                firstCategoryId = firstCategoryId,
                secondCategoryId = secondCategoryId,
                firstCategoryItemIds = listOf(firstItemId, secondItemId),
                secondCategoryItemId = thirdItemId
            )
        }
    }

    private fun insertVenue(
        connection: Connection,
        name: String,
        city: String,
        address: String,
        status: String
    ): Long {
        connection.prepareStatement(
            """
                INSERT INTO venues (name, city, address, status)
                VALUES (?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS
        ).use { statement ->
            statement.setString(1, name)
            statement.setString(2, city)
            statement.setString(3, address)
            statement.setString(4, status)
            statement.executeUpdate()
            statement.generatedKeys.use { rs ->
                if (rs.next()) {
                    return rs.getLong(1)
                }
            }
        }
        error("Failed to insert venue")
    }

    private fun insertMenuCategory(
        connection: Connection,
        venueId: Long,
        name: String,
        sortOrder: Int
    ): Long {
        connection.prepareStatement(
            """
                INSERT INTO menu_categories (venue_id, name, sort_order)
                VALUES (?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, name)
            statement.setInt(3, sortOrder)
            statement.executeUpdate()
            statement.generatedKeys.use { rs ->
                if (rs.next()) {
                    return rs.getLong(1)
                }
            }
        }
        error("Failed to insert menu category")
    }

    private fun insertMenuItem(
        connection: Connection,
        venueId: Long,
        categoryId: Long,
        name: String,
        priceMinor: Long,
        currency: String,
        isAvailable: Boolean
    ): Long {
        connection.prepareStatement(
            """
                INSERT INTO menu_items (venue_id, category_id, name, price_minor, currency, is_available)
                VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, categoryId)
            statement.setString(3, name)
            statement.setLong(4, priceMinor)
            statement.setString(5, currency)
            statement.setBoolean(6, isAvailable)
            statement.executeUpdate()
            statement.generatedKeys.use { rs ->
                if (rs.next()) {
                    return rs.getLong(1)
                }
            }
        }
        error("Failed to insert menu item")
    }

    private data class SeededVenues(
        val publishedId: Long,
        val hiddenId: Long,
        val suspendedId: Long
    )

    private data class SeededMenu(
        val firstCategoryId: Long,
        val secondCategoryId: Long,
        val firstCategoryItemIds: List<Long>,
        val secondCategoryItemId: Long
    )

    private companion object {
        const val TELEGRAM_USER_ID: Long = 777L
    }
}
