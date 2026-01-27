package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.MenuResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class VenueMenuRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `manager can manage categories and items`() = testApplication {
        val jdbcUrl = buildJdbcUrl("menu-crud")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
        val token = issueToken(config)

        val categoryResponse = client.post("/api/venue/menu/categories?venueId=$venueId") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody("""{"name":"Drinks"}""")
        }

        assertEquals(HttpStatusCode.OK, categoryResponse.status)
        val category = json.decodeFromString(VenueMenuCategoryDto.serializer(), categoryResponse.bodyAsText())
        assertEquals("Drinks", category.name)

        val updatedResponse = client.patch("/api/venue/menu/categories/${category.id}?venueId=$venueId") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody("""{"name":"Cocktails"}""")
        }

        assertEquals(HttpStatusCode.OK, updatedResponse.status)
        val updated = json.decodeFromString(VenueMenuCategoryDto.serializer(), updatedResponse.bodyAsText())
        assertEquals("Cocktails", updated.name)

        val itemResponse = client.post("/api/venue/menu/items?venueId=$venueId") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody(
                """
                    {
                      "categoryId": ${updated.id},
                      "name": "Lemonade",
                      "priceMinor": 250,
                      "currency": "RUB",
                      "isAvailable": true
                    }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, itemResponse.status)
        val item = json.decodeFromString(VenueMenuItemDto.serializer(), itemResponse.bodyAsText())
        assertEquals("Lemonade", item.name)
        assertTrue(item.isAvailable)

        val updateItemResponse = client.patch("/api/venue/menu/items/${item.id}?venueId=$venueId") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody("""{"name":"Orange","priceMinor":300,"isAvailable":false}""")
        }

        assertEquals(HttpStatusCode.OK, updateItemResponse.status)
        val updatedItem = json.decodeFromString(VenueMenuItemDto.serializer(), updateItemResponse.bodyAsText())
        assertEquals("Orange", updatedItem.name)
        assertFalse(updatedItem.isAvailable)

        val deleteItemResponse = client.delete("/api/venue/menu/items/${item.id}?venueId=$venueId") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, deleteItemResponse.status)

        val deleteCategoryResponse = client.delete("/api/venue/menu/categories/${updated.id}?venueId=$venueId") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, deleteCategoryResponse.status)
    }

    @Test
    fun `menu routes prefer venueId over path id`() = testApplication {
        val jdbcUrl = buildJdbcUrl("menu-venue-id-priority")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
        val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
        val token = issueToken(config)

        val createdCategoryIds = mutableListOf<Long>()
        var category: VenueMenuCategoryDto? = null
        for (attempt in 1..5) {
            val categoryResponse = client.post("/api/venue/menu/categories?venueId=$venueId") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $token")
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                }
                setBody("""{"name":"Soups-$attempt"}""")
            }

            assertEquals(HttpStatusCode.OK, categoryResponse.status)
            val created = json.decodeFromString(VenueMenuCategoryDto.serializer(), categoryResponse.bodyAsText())
            assertEquals("Soups-$attempt", created.name)
            createdCategoryIds.add(created.id)
            if (created.id != venueId) {
                category = created
                break
            }
        }
        val selectedCategory = requireNotNull(category) { "Failed to create category with id != venueId" }

        val updateResponse = client.patch("/api/venue/menu/categories/${selectedCategory.id}?venueId=$venueId") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody("""{"name":"Starters"}""")
        }

        assertEquals(HttpStatusCode.OK, updateResponse.status)
        val updated = json.decodeFromString(VenueMenuCategoryDto.serializer(), updateResponse.bodyAsText())
        assertEquals("Starters", updated.name)

        val deleteResponse = client.delete("/api/venue/menu/categories/${selectedCategory.id}?venueId=$venueId") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, deleteResponse.status)

        createdCategoryIds
            .filterNot { it == selectedCategory.id }
            .forEach { categoryId ->
                client.delete("/api/venue/menu/categories/$categoryId?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            }
    }

    @Test
    fun `reorder rejects foreign ids`() = testApplication {
        val jdbcUrl = buildJdbcUrl("menu-reorder")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
        val otherVenueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
        val token = issueToken(config)

        val categoryResponse = client.post("/api/venue/menu/categories?venueId=$venueId") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody("""{"name":"Main"}""")
        }
        val category = json.decodeFromString(VenueMenuCategoryDto.serializer(), categoryResponse.bodyAsText())

        val foreignCategoryResponse = client.post("/api/venue/menu/categories?venueId=$otherVenueId") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody("""{"name":"Foreign"}""")
        }
        val foreignCategory = json.decodeFromString(
            VenueMenuCategoryDto.serializer(),
            foreignCategoryResponse.bodyAsText()
        )

        val reorderResponse = client.post("/api/venue/menu/reorder/categories?venueId=$venueId") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody("""{"categoryIds":[${category.id},${foreignCategory.id}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, reorderResponse.status)
        assertApiErrorEnvelope(reorderResponse, ApiErrorCodes.INVALID_INPUT)
    }

    @Test
    fun `guest menu reflects availability`() = testApplication {
        val jdbcUrl = buildJdbcUrl("menu-availability")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
        val token = issueToken(config)

        val categoryResponse = client.post("/api/venue/menu/categories?venueId=$venueId") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody("""{"name":"Food"}""")
        }
        val category = json.decodeFromString(VenueMenuCategoryDto.serializer(), categoryResponse.bodyAsText())

        val itemResponse = client.post("/api/venue/menu/items?venueId=$venueId") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody(
                """
                    {
                      "categoryId": ${category.id},
                      "name": "Burger",
                      "priceMinor": 500,
                      "currency": "RUB",
                      "isAvailable": true
                    }
                """.trimIndent()
            )
        }
        val item = json.decodeFromString(VenueMenuItemDto.serializer(), itemResponse.bodyAsText())

        val availabilityResponse = client.patch(
            "/api/venue/menu/items/${item.id}/availability?venueId=$venueId"
        ) {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody("""{"isAvailable":false}""")
        }

        assertEquals(HttpStatusCode.OK, availabilityResponse.status)

        val guestMenuResponse = client.get("/api/guest/venue/$venueId/menu") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, guestMenuResponse.status)
        val menu = json.decodeFromString(MenuResponse.serializer(), guestMenuResponse.bodyAsText())
        assertEquals(1, menu.categories.size)
        val guestItem = menu.categories.first().items.firstOrNull()
        assertNotNull(guestItem)
        assertFalse(guestItem.isAvailable)
    }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String): MapApplicationConfig {
        return MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to ""
        )
    }

    private fun issueToken(config: MapApplicationConfig): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(TELEGRAM_USER_ID).token
    }

    private fun seedVenueWithRole(jdbcUrl: String, userId: Long, role: String): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    MERGE INTO users (telegram_user_id, username, first_name, last_name)
                    KEY (telegram_user_id)
                    VALUES (?, 'user', 'Test', 'User')
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
            val venueId = connection.prepareStatement(
                """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', 'active_published')
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { statement ->
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert venue")
                }
            }
            connection.prepareStatement(
                """
                    INSERT INTO venue_members (venue_id, user_id, role)
                    VALUES (?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
            return venueId
        }
    }

    @Serializable
    private data class VenueMenuCategoryDto(
        val id: Long,
        val name: String,
        val sortOrder: Int,
        val items: List<VenueMenuItemDto>
    )

    @Serializable
    private data class VenueMenuItemDto(
        val id: Long,
        val categoryId: Long,
        val name: String,
        val priceMinor: Long,
        val currency: String,
        val isAvailable: Boolean,
        val sortOrder: Int,
        val options: List<VenueMenuOptionDto>
    )

    @Serializable
    private data class VenueMenuOptionDto(
        val id: Long,
        val itemId: Long,
        val name: String,
        val priceDeltaMinor: Long,
        val isAvailable: Boolean,
        val sortOrder: Int
    )

    companion object {
        private const val TELEGRAM_USER_ID = 100500L
    }
}
