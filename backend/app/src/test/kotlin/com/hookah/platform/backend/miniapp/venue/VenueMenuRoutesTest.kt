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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VenueMenuRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `manager can manage categories and items`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-crud")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val token = issueToken(config)

            val categoryResponse =
                client.post("/api/venue/menu/categories?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"name":"Drinks"}""")
                }

            assertEquals(HttpStatusCode.OK, categoryResponse.status)
            val category = json.decodeFromString(VenueMenuCategoryDto.serializer(), categoryResponse.bodyAsText())
            assertEquals("Drinks", category.name)
            assertEquals("OTHER", category.categoryType)

            val updatedResponse =
                client.patch("/api/venue/menu/categories/${category.id}?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"name":"Cocktails"}""")
                }

            assertEquals(HttpStatusCode.OK, updatedResponse.status)
            val updated = json.decodeFromString(VenueMenuCategoryDto.serializer(), updatedResponse.bodyAsText())
            assertEquals("Cocktails", updated.name)

            val typedCategoryResponse =
                client.patch("/api/venue/menu/categories/${category.id}?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"categoryType":"DRINK"}""")
                }

            assertEquals(HttpStatusCode.OK, typedCategoryResponse.status)
            val typedCategory =
                json.decodeFromString(
                    VenueMenuCategoryDto.serializer(),
                    typedCategoryResponse.bodyAsText(),
                )
            assertEquals("DRINK", typedCategory.categoryType)

            val itemResponse =
                client.post("/api/venue/menu/items?venueId=$venueId") {
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
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, itemResponse.status)
            val item = json.decodeFromString(VenueMenuItemDto.serializer(), itemResponse.bodyAsText())
            assertEquals("Lemonade", item.name)
            assertTrue(item.isAvailable)
            assertEquals(null, item.itemType)
            assertEquals("DRINK", item.effectiveItemType)

            val updateItemResponse =
                client.patch("/api/venue/menu/items/${item.id}?venueId=$venueId") {
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

            val typedItemResponse =
                client.patch("/api/venue/menu/items/${item.id}?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"itemType":"DRINK"}""")
                }

            assertEquals(HttpStatusCode.OK, typedItemResponse.status)
            val typedItem = json.decodeFromString(VenueMenuItemDto.serializer(), typedItemResponse.bodyAsText())
            assertEquals("DRINK", typedItem.itemType)
            assertEquals("DRINK", typedItem.effectiveItemType)

            val deleteItemResponse =
                client.delete("/api/venue/menu/items/${item.id}?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, deleteItemResponse.status)

            val deleteCategoryResponse =
                client.delete("/api/venue/menu/categories/${updated.id}?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, deleteCategoryResponse.status)
        }

    @Test
    fun `menu routes prefer venueId over path id`() =
        testApplication {
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
                val categoryResponse =
                    client.post("/api/venue/menu/categories?venueId=$venueId") {
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

            val updateResponse =
                client.patch("/api/venue/menu/categories/${selectedCategory.id}?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"name":"Starters"}""")
                }

            assertEquals(HttpStatusCode.OK, updateResponse.status)
            val updated = json.decodeFromString(VenueMenuCategoryDto.serializer(), updateResponse.bodyAsText())
            assertEquals("Starters", updated.name)

            val deleteResponse =
                client.delete("/api/venue/menu/categories/${selectedCategory.id}?venueId=$venueId") {
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
    fun `menu options stay scoped to owning item in venue and guest responses`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-option-item-scope")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val token = issueToken(config)

            val category =
                client.post("/api/venue/menu/categories?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"name":"Кальянное меню","categoryType":"HOOKAH"}""")
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(VenueMenuCategoryDto.serializer(), response.bodyAsText())
                }

            val hookahItem =
                client.post("/api/venue/menu/items?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "categoryId": ${category.id},
                          "name": "Кальян",
                          "priceMinor": 180000,
                          "currency": "RUB",
                          "isAvailable": true
                        }
                        """.trimIndent(),
                    )
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(VenueMenuItemDto.serializer(), response.bodyAsText())
                }

            val waterItem =
                client.post("/api/venue/menu/items?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "categoryId": ${category.id},
                          "name": "Вода",
                          "priceMinor": 20000,
                          "currency": "RUB",
                          "isAvailable": true,
                          "itemType": "DRINK"
                        }
                        """.trimIndent(),
                    )
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(VenueMenuItemDto.serializer(), response.bodyAsText())
                }

            val activeOption =
                client.post("/api/venue/menu/options?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "itemId": ${hookahItem.id},
                          "name": "Яблоко",
                          "priceDeltaMinor": 0,
                          "isAvailable": true
                        }
                        """.trimIndent(),
                    )
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(VenueMenuOptionDto.serializer(), response.bodyAsText())
                }

            val unavailableOption =
                client.post("/api/venue/menu/options?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "itemId": ${hookahItem.id},
                          "name": "Недоступный вкус",
                          "priceDeltaMinor": 0,
                          "isAvailable": false
                        }
                        """.trimIndent(),
                    )
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(VenueMenuOptionDto.serializer(), response.bodyAsText())
                }

            val venueMenuResponse =
                client.get("/api/venue/menu?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, venueMenuResponse.status)
            val venueMenu = json.decodeFromString(VenueMenuResponse.serializer(), venueMenuResponse.bodyAsText())
            val venueItems = venueMenu.categories.flatMap { it.items }.associateBy { it.id }
            assertEquals(
                listOf(activeOption.id, unavailableOption.id),
                venueItems.getValue(hookahItem.id).options.map { it.id },
            )
            assertTrue(venueItems.getValue(waterItem.id).options.isEmpty())

            val guestMenuResponse =
                client.get("/api/guest/venue/$venueId/menu") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, guestMenuResponse.status)
            val guestMenu = json.decodeFromString(MenuResponse.serializer(), guestMenuResponse.bodyAsText())
            val guestItems = guestMenu.categories.flatMap { it.items }.associateBy { it.id }
            assertEquals(listOf(activeOption.id), guestItems.getValue(hookahItem.id).options.map { it.id })
            assertTrue(guestItems.getValue(waterItem.id).options.isEmpty())
        }

    @Test
    fun `reorder rejects foreign ids`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-reorder")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val otherVenueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val token = issueToken(config)

            val categoryResponse =
                client.post("/api/venue/menu/categories?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"name":"Main"}""")
                }
            val category = json.decodeFromString(VenueMenuCategoryDto.serializer(), categoryResponse.bodyAsText())

            val foreignCategoryResponse =
                client.post("/api/venue/menu/categories?venueId=$otherVenueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"name":"Foreign"}""")
                }
            val foreignCategory =
                json.decodeFromString(
                    VenueMenuCategoryDto.serializer(),
                    foreignCategoryResponse.bodyAsText(),
                )

            val reorderResponse =
                client.post("/api/venue/menu/reorder/categories?venueId=$venueId") {
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
    fun `guest menu reflects availability`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-availability")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val token = issueToken(config)

            val categoryResponse =
                client.post("/api/venue/menu/categories?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"name":"Food"}""")
                }
            val category = json.decodeFromString(VenueMenuCategoryDto.serializer(), categoryResponse.bodyAsText())

            val itemResponse =
                client.post("/api/venue/menu/items?venueId=$venueId") {
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
                        """.trimIndent(),
                    )
                }
            val item = json.decodeFromString(VenueMenuItemDto.serializer(), itemResponse.bodyAsText())

            val availabilityResponse =
                client.patch(
                    "/api/venue/menu/items/${item.id}/availability?venueId=$venueId",
                ) {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"isAvailable":false}""")
                }

            assertEquals(HttpStatusCode.OK, availabilityResponse.status)

            val guestMenuResponse =
                client.get("/api/guest/venue/$venueId/menu") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, guestMenuResponse.status)
            val menu = json.decodeFromString(MenuResponse.serializer(), guestMenuResponse.bodyAsText())
            assertEquals(1, menu.categories.size)
            assertTrue(menu.categories.first().items.isEmpty())
        }

    @Test
    fun `staff can view menu but cannot manage item or option availability`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-staff-availability")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val staffUserId = 200501L
            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            seedVenueWithRole(jdbcUrl, staffUserId, "STAFF", venueId)
            val managerToken = issueToken(config)
            val staffToken = issueToken(config, staffUserId)

            val category =
                client.post("/api/venue/menu/categories?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $managerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"name":"Hookahs"}""")
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(VenueMenuCategoryDto.serializer(), response.bodyAsText())
                }

            val item =
                client.post("/api/venue/menu/items?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $managerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "categoryId": ${category.id},
                          "name": "Classic",
                          "priceMinor": 90000,
                          "currency": "RUB",
                          "isAvailable": true
                        }
                        """.trimIndent(),
                    )
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(VenueMenuItemDto.serializer(), response.bodyAsText())
                }

            val option =
                client.post("/api/venue/menu/options?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $managerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"itemId":${item.id},"name":"Mint","priceDeltaMinor":0,"isAvailable":true}""")
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(VenueMenuOptionDto.serializer(), response.bodyAsText())
                }

            val viewResponse =
                client.get("/api/venue/menu?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }

            assertEquals(HttpStatusCode.OK, viewResponse.status)
            val viewPayload = json.decodeFromString(VenueMenuResponse.serializer(), viewResponse.bodyAsText())
            assertEquals(1, viewPayload.categories.size)

            val itemAvailabilityResponse =
                client.patch("/api/venue/menu/items/${item.id}/availability?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"isAvailable":false}""")
                }

            assertEquals(HttpStatusCode.Forbidden, itemAvailabilityResponse.status)
            assertApiErrorEnvelope(itemAvailabilityResponse, ApiErrorCodes.FORBIDDEN)

            val optionAvailabilityResponse =
                client.patch("/api/venue/menu/options/${option.id}/availability?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"isAvailable":false}""")
                }

            assertEquals(HttpStatusCode.Forbidden, optionAvailabilityResponse.status)
            assertApiErrorEnvelope(optionAvailabilityResponse, ApiErrorCodes.FORBIDDEN)

            val createCategoryResponse =
                client.post("/api/venue/menu/categories?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"name":"Staff category"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, createCategoryResponse.status)
            assertApiErrorEnvelope(createCategoryResponse, ApiErrorCodes.FORBIDDEN)

            val updateCategoryResponse =
                client.patch("/api/venue/menu/categories/${category.id}?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"name":"Staff rename"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, updateCategoryResponse.status)
            assertApiErrorEnvelope(updateCategoryResponse, ApiErrorCodes.FORBIDDEN)

            val editPriceResponse =
                client.patch("/api/venue/menu/items/${item.id}?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"priceMinor":100000}""")
                }
            assertEquals(HttpStatusCode.Forbidden, editPriceResponse.status)
            assertApiErrorEnvelope(editPriceResponse, ApiErrorCodes.FORBIDDEN)

            val editCategoryTypeResponse =
                client.patch("/api/venue/menu/categories/${category.id}?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"categoryType":"HOOKAH"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, editCategoryTypeResponse.status)
            assertApiErrorEnvelope(editCategoryTypeResponse, ApiErrorCodes.FORBIDDEN)

            val reorderCategoriesResponse =
                client.post("/api/venue/menu/reorder/categories?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"categoryIds":[${category.id}]}""")
                }
            assertEquals(HttpStatusCode.Forbidden, reorderCategoriesResponse.status)
            assertApiErrorEnvelope(reorderCategoriesResponse, ApiErrorCodes.FORBIDDEN)

            val createItemResponse =
                client.post("/api/venue/menu/items?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "categoryId": ${category.id},
                          "name": "Staff item",
                          "priceMinor": 10000,
                          "currency": "RUB",
                          "isAvailable": true
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.Forbidden, createItemResponse.status)
            assertApiErrorEnvelope(createItemResponse, ApiErrorCodes.FORBIDDEN)

            val reorderItemsResponse =
                client.post("/api/venue/menu/reorder/items?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"categoryId":${category.id},"itemIds":[${item.id}]}""")
                }
            assertEquals(HttpStatusCode.Forbidden, reorderItemsResponse.status)
            assertApiErrorEnvelope(reorderItemsResponse, ApiErrorCodes.FORBIDDEN)

            val deleteItemResponse =
                client.delete("/api/venue/menu/items/${item.id}?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, deleteItemResponse.status)
            assertApiErrorEnvelope(deleteItemResponse, ApiErrorCodes.FORBIDDEN)

            val deleteCategoryResponse =
                client.delete("/api/venue/menu/categories/${category.id}?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, deleteCategoryResponse.status)
            assertApiErrorEnvelope(deleteCategoryResponse, ApiErrorCodes.FORBIDDEN)

            val createOptionResponse =
                client.post("/api/venue/menu/options?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"itemId":${item.id},"name":"Staff option","priceDeltaMinor":0,"isAvailable":true}""")
                }
            assertEquals(HttpStatusCode.Forbidden, createOptionResponse.status)
            assertApiErrorEnvelope(createOptionResponse, ApiErrorCodes.FORBIDDEN)

            val updateOptionResponse =
                client.patch("/api/venue/menu/options/${option.id}?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"name":"Staff option rename"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, updateOptionResponse.status)
            assertApiErrorEnvelope(updateOptionResponse, ApiErrorCodes.FORBIDDEN)

            val deleteOptionResponse =
                client.delete("/api/venue/menu/options/${option.id}?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, deleteOptionResponse.status)
            assertApiErrorEnvelope(deleteOptionResponse, ApiErrorCodes.FORBIDDEN)
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
            "db.password" to "",
        )
    }

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long = TELEGRAM_USER_ID,
    ): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(userId).token
    }

    private fun seedVenueWithRole(
        jdbcUrl: String,
        userId: Long,
        role: String,
        venueId: Long? = null,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO users (telegram_user_id, username, first_name, last_name)
                KEY (telegram_user_id)
                VALUES (?, 'user', 'Test', 'User')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
            val resolvedVenueId =
                venueId ?: connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setString(1, VenueStatus.PUBLISHED.dbValue)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert venue")
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO venue_members (venue_id, user_id, role)
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, resolvedVenueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
            return resolvedVenueId
        }
    }

    @Serializable
    private data class VenueMenuResponse(
        val venueId: Long,
        val categories: List<VenueMenuCategoryDto>,
    )

    @Serializable
    private data class VenueMenuCategoryDto(
        val id: Long,
        val name: String,
        val sortOrder: Int,
        val categoryType: String = "OTHER",
        val items: List<VenueMenuItemDto>,
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
        val itemType: String? = null,
        val effectiveItemType: String = "OTHER",
        val options: List<VenueMenuOptionDto>,
    )

    @Serializable
    private data class VenueMenuOptionDto(
        val id: Long,
        val itemId: Long,
        val name: String,
        val priceDeltaMinor: Long,
        val isAvailable: Boolean,
        val sortOrder: Int,
    )

    companion object {
        private const val TELEGRAM_USER_ID = 100500L
    }
}
