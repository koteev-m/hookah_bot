package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.MenuResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.menu.MENU_SHIFT_CHECK_COMPLETED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MenuShiftCheckResponse
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuItem
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuOption
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuRepository
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.h2.jdbcx.JdbcDataSource
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

            val stopOptionResponse =
                client.patch("/api/venue/menu/options/${activeOption.id}/availability?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"isAvailable":false}""")
                }

            assertEquals(HttpStatusCode.OK, stopOptionResponse.status)

            val guestMenuAfterOptionStopResponse =
                client.get("/api/guest/venue/$venueId/menu") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, guestMenuAfterOptionStopResponse.status)
            val guestMenuAfterOptionStop =
                json.decodeFromString(MenuResponse.serializer(), guestMenuAfterOptionStopResponse.bodyAsText())
            val guestItemsAfterOptionStop =
                guestMenuAfterOptionStop.categories.flatMap { it.items }.associateBy { it.id }
            assertTrue(guestItemsAfterOptionStop.containsKey(hookahItem.id))
            assertTrue(guestItemsAfterOptionStop.getValue(hookahItem.id).options.isEmpty())
            assertTrue(guestItemsAfterOptionStop.containsKey(waterItem.id))

            val stopItemResponse =
                client.patch("/api/venue/menu/items/${hookahItem.id}/availability?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"isAvailable":false}""")
                }

            assertEquals(HttpStatusCode.OK, stopItemResponse.status)

            val guestMenuAfterItemStopResponse =
                client.get("/api/guest/venue/$venueId/menu") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, guestMenuAfterItemStopResponse.status)
            val guestMenuAfterItemStop =
                json.decodeFromString(MenuResponse.serializer(), guestMenuAfterItemStopResponse.bodyAsText())
            val guestItemsAfterItemStop =
                guestMenuAfterItemStop.categories.flatMap { it.items }.associateBy { it.id }
            assertFalse(guestItemsAfterItemStop.containsKey(hookahItem.id))
            assertTrue(guestItemsAfterItemStop.containsKey(waterItem.id))
        }

    @Test
    fun `base flavor profiles apply only to hookah items and stay item scoped`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-base-flavor-profiles")
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
                          "name": "Кальян классический",
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
            assertEquals(8, hookahItem.missingBaseFlavorProfilesCount)
            assertTrue(hookahItem.supportsBaseFlavorProfiles)

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
            assertEquals(0, waterItem.missingBaseFlavorProfilesCount)
            assertFalse(waterItem.supportsBaseFlavorProfiles)

            val applyResponse =
                client.post("/api/venue/menu/items/${hookahItem.id}/base-flavor-profiles?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, applyResponse.status)
            val applied =
                json.decodeFromString(
                    ApplyBaseFlavorProfilesResponse.serializer(),
                    applyResponse.bodyAsText(),
                )
            assertEquals(hookahItem.id, applied.itemId)
            assertEquals(8, applied.addedCount)
            assertEquals(0, applied.existingCount)
            assertEquals(8, applied.options.size)
            assertTrue(applied.options.all { it.itemId == hookahItem.id })
            assertTrue(applied.options.all { it.priceDeltaMinor == 0L && it.isAvailable })

            val repeatResponse =
                client.post("/api/venue/menu/items/${hookahItem.id}/base-flavor-profiles?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, repeatResponse.status)
            val repeated =
                json.decodeFromString(
                    ApplyBaseFlavorProfilesResponse.serializer(),
                    repeatResponse.bodyAsText(),
                )
            assertEquals(0, repeated.addedCount)
            assertEquals(8, repeated.existingCount)
            assertEquals(8, repeated.options.size)

            val invalidWaterResponse =
                client.post("/api/venue/menu/items/${waterItem.id}/base-flavor-profiles?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.BadRequest, invalidWaterResponse.status)
            assertApiErrorEnvelope(invalidWaterResponse, ApiErrorCodes.INVALID_INPUT)

            val partialItem =
                client.post("/api/venue/menu/items?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "categoryId": ${category.id},
                          "name": "Кальян премиум",
                          "priceMinor": 260000,
                          "currency": "RUB",
                          "isAvailable": true
                        }
                        """.trimIndent(),
                    )
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(VenueMenuItemDto.serializer(), response.bodyAsText())
                }

            client.post("/api/venue/menu/options?venueId=$venueId") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $token")
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                }
                setBody("""{"itemId":${partialItem.id},"name":"ягодный","priceDeltaMinor":0,"isAvailable":true}""")
            }.also { response ->
                assertEquals(HttpStatusCode.OK, response.status)
            }

            val partialApplyResponse =
                client.post("/api/venue/menu/items/${partialItem.id}/base-flavor-profiles?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, partialApplyResponse.status)
            val partiallyApplied =
                json.decodeFromString(
                    ApplyBaseFlavorProfilesResponse.serializer(),
                    partialApplyResponse.bodyAsText(),
                )
            assertEquals(7, partiallyApplied.addedCount)
            assertEquals(1, partiallyApplied.existingCount)
            assertEquals(8, partiallyApplied.options.size)
            assertEquals(1, partiallyApplied.options.count { it.name.equals("ягодный", ignoreCase = true) })

            val menuResponse =
                client.get("/api/venue/menu?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, menuResponse.status)
            val menu = json.decodeFromString(VenueMenuResponse.serializer(), menuResponse.bodyAsText())
            val venueItems = menu.categories.flatMap { it.items }.associateBy { it.id }
            assertEquals(0, venueItems.getValue(hookahItem.id).missingBaseFlavorProfilesCount)
            assertEquals(0, venueItems.getValue(waterItem.id).missingBaseFlavorProfilesCount)
            assertEquals(8, venueItems.getValue(hookahItem.id).options.size)
            assertTrue(venueItems.getValue(waterItem.id).options.isEmpty())

            val guestMenuResponse =
                client.get("/api/guest/venue/$venueId/menu") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, guestMenuResponse.status)
            val guestMenu = json.decodeFromString(MenuResponse.serializer(), guestMenuResponse.bodyAsText())
            val guestItems = guestMenu.categories.flatMap { it.items }.associateBy { it.id }
            assertEquals(8, guestItems.getValue(hookahItem.id).options.size)
            assertTrue(guestItems.getValue(waterItem.id).options.isEmpty())

            val legacyCategory =
                client.post("/api/venue/menu/categories?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"name":"Кальянное меню"}""")
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(VenueMenuCategoryDto.serializer(), response.bodyAsText())
                }
            assertEquals("OTHER", legacyCategory.categoryType)

            val legacyHookahItem =
                client.post("/api/venue/menu/items?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "categoryId": ${legacyCategory.id},
                          "name": "Кальян legacy",
                          "priceMinor": 190000,
                          "currency": "RUB",
                          "isAvailable": true
                        }
                        """.trimIndent(),
                    )
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    json.decodeFromString(VenueMenuItemDto.serializer(), response.bodyAsText())
                }
            assertEquals("OTHER", legacyHookahItem.effectiveItemType)
            assertTrue(legacyHookahItem.supportsBaseFlavorProfiles)
            assertEquals(8, legacyHookahItem.missingBaseFlavorProfilesCount)
        }

    @Test
    fun `staff cannot apply base flavor profiles`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-base-flavor-staff")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val staffUserId = 200502L
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
                    setBody("""{"name":"Кальянное меню","categoryType":"HOOKAH"}""")
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

            val staffResponse =
                client.post("/api/venue/menu/items/${item.id}/base-flavor-profiles?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }

            assertEquals(HttpStatusCode.Forbidden, staffResponse.status)
            assertApiErrorEnvelope(staffResponse, ApiErrorCodes.FORBIDDEN)
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
    fun `staff can manage stoplist availability but cannot manage menu content`() =
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

            val optionAvailabilityResponse =
                client.patch("/api/venue/menu/options/${option.id}/availability?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"isAvailable":false}""")
                }

            assertEquals(HttpStatusCode.OK, optionAvailabilityResponse.status)
            val stoppedOption =
                json.decodeFromString(VenueMenuOptionDto.serializer(), optionAvailabilityResponse.bodyAsText())
            assertFalse(stoppedOption.isAvailable)

            val guestMenuAfterOptionStopResponse =
                client.get("/api/guest/venue/$venueId/menu") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.OK, guestMenuAfterOptionStopResponse.status)
            val guestMenuAfterOptionStop =
                json.decodeFromString(MenuResponse.serializer(), guestMenuAfterOptionStopResponse.bodyAsText())
            val guestItemAfterOptionStop =
                guestMenuAfterOptionStop.categories.flatMap { it.items }.single { it.id == item.id }
            assertTrue(guestItemAfterOptionStop.options.isEmpty())

            val itemAvailabilityResponse =
                client.patch("/api/venue/menu/items/${item.id}/availability?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"isAvailable":false}""")
                }

            assertEquals(HttpStatusCode.OK, itemAvailabilityResponse.status)
            val stoppedItem =
                json.decodeFromString(
                    VenueMenuItemDto.serializer(),
                    itemAvailabilityResponse.bodyAsText(),
                )
            assertFalse(stoppedItem.isAvailable)

            val guestMenuAfterItemStopResponse =
                client.get("/api/guest/venue/$venueId/menu") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.OK, guestMenuAfterItemStopResponse.status)
            val guestMenuAfterItemStop =
                json.decodeFromString(MenuResponse.serializer(), guestMenuAfterItemStopResponse.bodyAsText())
            assertTrue(guestMenuAfterItemStop.categories.flatMap { it.items }.none { it.id == item.id })

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

    @Test
    fun `owner and manager can complete shift check while staff and foreign users are denied`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-shift-check-rbac")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, OWNER_USER_ID, "OWNER")
            seedVenueWithRole(jdbcUrl, MANAGER_USER_ID, "MANAGER", venueId)
            seedVenueWithRole(jdbcUrl, STAFF_USER_ID, "STAFF", venueId)
            seedVenueWithRole(jdbcUrl, FOREIGN_USER_ID, "OWNER")

            val ownerResponse =
                postShiftCheck(
                    client = client,
                    token = issueToken(config, OWNER_USER_ID),
                    venueId = venueId,
                    body = """{"items":[],"options":[]}""",
                )
            assertEquals(HttpStatusCode.OK, ownerResponse.status)
            val ownerResult =
                json.decodeFromString(MenuShiftCheckResponse.serializer(), ownerResponse.bodyAsText())
            assertEquals(0, ownerResult.changedItemCount)
            assertEquals(0, ownerResult.changedOptionCount)

            val managerResponse =
                postShiftCheck(
                    client = client,
                    token = issueToken(config, MANAGER_USER_ID),
                    venueId = venueId,
                    body = """{"items":[],"options":[]}""",
                )
            assertEquals(HttpStatusCode.OK, managerResponse.status)

            val staffResponse =
                postShiftCheck(
                    client = client,
                    token = issueToken(config, STAFF_USER_ID),
                    venueId = venueId,
                    body = """{"items":[],"options":[]}""",
                )
            assertEquals(HttpStatusCode.Forbidden, staffResponse.status)
            assertApiErrorEnvelope(staffResponse, ApiErrorCodes.FORBIDDEN)

            val foreignResponse =
                postShiftCheck(
                    client = client,
                    token = issueToken(config, FOREIGN_USER_ID),
                    venueId = venueId,
                    body = """{"items":[],"options":[]}""",
                )
            assertEquals(HttpStatusCode.Forbidden, foreignResponse.status)
            assertApiErrorEnvelope(foreignResponse, ApiErrorCodes.FORBIDDEN)

            val audits = shiftCheckAuditPayloads(jdbcUrl)
            assertEquals(2, audits.size)
            assertTrue(audits.all { it.getValue("changedItemCount").jsonPrimitive.int == 0 })
            assertTrue(audits.all { it.getValue("changedOptionCount").jsonPrimitive.int == 0 })
        }

    @Test
    fun `mixed shift check uses one batch updates guest menu and writes one safe audit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-shift-check-success")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, MANAGER_USER_ID, "MANAGER")
            val token = issueToken(config, MANAGER_USER_ID)
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)
            val response =
                postShiftCheck(
                    client = client,
                    token = token,
                    venueId = venueId,
                    body =
                        """
                        {
                          "items": [{
                            "itemId": ${fixture.firstItem.id},
                            "expectedIsAvailable": true,
                            "desiredIsAvailable": false
                          }],
                          "options": [{
                            "optionId": ${fixture.secondOption.id},
                            "itemId": ${fixture.secondItem.id},
                            "expectedIsAvailable": true,
                            "desiredIsAvailable": false
                          }]
                        }
                        """.trimIndent(),
                )

            assertEquals(HttpStatusCode.OK, response.status)
            val result = json.decodeFromString(MenuShiftCheckResponse.serializer(), response.bodyAsText())
            assertEquals(venueId, result.venueId)
            assertEquals(1, result.changedItemCount)
            assertEquals(1, result.changedOptionCount)
            assertEquals(2, result.reviewedItemCount)
            assertEquals(2, result.reviewedOptionCount)
            assertEquals(1, result.availableItemCount)
            assertEquals(1, result.availableOptionCount)
            val savedItems = result.categories.flatMap { it.items }.associateBy { it.id }
            assertFalse(savedItems.getValue(fixture.firstItem.id).isAvailable)
            assertFalse(
                savedItems
                    .getValue(fixture.secondItem.id)
                    .options
                    .single { it.id == fixture.secondOption.id }
                    .isAvailable,
            )

            val guestResponse =
                client.get("/api/guest/venue/$venueId/menu") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, guestResponse.status)
            val guestMenu = json.decodeFromString(MenuResponse.serializer(), guestResponse.bodyAsText())
            val guestItems = guestMenu.categories.flatMap { it.items }
            assertTrue(guestItems.none { it.id == fixture.firstItem.id })
            val remainingGuestItem = guestItems.single { it.id == fixture.secondItem.id }
            assertTrue(remainingGuestItem.options.none { it.id == fixture.secondOption.id })

            val audit = shiftCheckAuditPayloads(jdbcUrl).single()
            assertEquals(
                setOf(
                    "actorUserId",
                    "venueId",
                    "changedItemCount",
                    "changedOptionCount",
                    "itemsMadeAvailable",
                    "itemsMadeUnavailable",
                    "optionsMadeAvailable",
                    "optionsMadeUnavailable",
                    "reviewedItemCount",
                    "reviewedOptionCount",
                ),
                audit.keys,
            )
            assertEquals(1, audit.getValue("changedItemCount").jsonPrimitive.int)
            assertEquals(1, audit.getValue("changedOptionCount").jsonPrimitive.int)
            assertEquals(
                listOf(fixture.firstItem.id),
                audit.getValue("itemsMadeUnavailable").jsonArray.map { it.jsonPrimitive.content.toLong() },
            )
            assertEquals(
                listOf(fixture.secondOption.id),
                audit.getValue("optionsMadeUnavailable").jsonArray.map { it.jsonPrimitive.content.toLong() },
            )
            assertFalse(audit.toString().contains(fixture.firstItem.name))
            assertFalse(audit.toString().contains(fixture.secondOption.name))
            assertFalse(audit.toString().contains("price", ignoreCase = true))
            assertFalse(audit.toString().contains("telegram", ignoreCase = true))
        }

    @Test
    fun `invalid shift check batches reject duplicates missing foreign mismatch oversize and stale atomically`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-shift-check-invalid")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, MANAGER_USER_ID, "MANAGER")
            val foreignVenueId = seedVenueWithRole(jdbcUrl, FOREIGN_USER_ID, "OWNER")
            val token = issueToken(config, MANAGER_USER_ID)
            val own = createShiftCheckFixture(jdbcUrl, venueId)
            val foreign = createShiftCheckFixture(jdbcUrl, foreignVenueId)

            suspend fun assertRejected(
                body: String,
                status: HttpStatusCode,
                code: String,
            ) {
                val response = postShiftCheck(client, token, venueId, body)
                assertEquals(status, response.status)
                assertApiErrorEnvelope(response, code)
                val savedResponse =
                    client.get("/api/venue/menu?venueId=$venueId") {
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    }
                assertEquals(HttpStatusCode.OK, savedResponse.status)
                val saved = json.decodeFromString(VenueMenuResponse.serializer(), savedResponse.bodyAsText())
                assertTrue(saved.categories.flatMap { it.items }.all { it.isAvailable })
                assertTrue(
                    saved.categories
                        .flatMap { it.items }
                        .flatMap { it.options }
                        .all { it.isAvailable },
                )
                assertEquals(0, shiftCheckAuditPayloads(jdbcUrl).size)
            }

            assertRejected(
                body =
                    """
                    {
                      "items": [
                        {
                          "itemId": ${own.firstItem.id},
                          "expectedIsAvailable": true,
                          "desiredIsAvailable": false
                        },
                        {
                          "itemId": ${own.firstItem.id},
                          "expectedIsAvailable": true,
                          "desiredIsAvailable": false
                        }
                      ],
                      "options": []
                    }
                    """.trimIndent(),
                status = HttpStatusCode.BadRequest,
                code = ApiErrorCodes.INVALID_INPUT,
            )

            assertRejected(
                body =
                    """
                    {
                      "items": [],
                      "options": [
                        {
                          "optionId": ${own.firstOption.id},
                          "itemId": ${own.firstItem.id},
                          "expectedIsAvailable": true,
                          "desiredIsAvailable": false
                        },
                        {
                          "optionId": ${own.firstOption.id},
                          "itemId": ${own.firstItem.id},
                          "expectedIsAvailable": true,
                          "desiredIsAvailable": false
                        }
                      ]
                    }
                    """.trimIndent(),
                status = HttpStatusCode.BadRequest,
                code = ApiErrorCodes.INVALID_INPUT,
            )

            assertRejected(
                body = """{"items":[],"options":[],"actorUserId":$MANAGER_USER_ID}""",
                status = HttpStatusCode.BadRequest,
                code = ApiErrorCodes.INVALID_INPUT,
            )

            assertRejected(
                body =
                    """
                    {
                      "items": [{
                        "itemId": ${own.firstItem.id},
                        "expectedIsAvailable": true,
                        "desiredIsAvailable": false,
                        "name": "Подмена названия"
                      }],
                      "options": []
                    }
                    """.trimIndent(),
                status = HttpStatusCode.BadRequest,
                code = ApiErrorCodes.INVALID_INPUT,
            )

            assertRejected(
                body =
                    """
                    {
                      "items": [],
                      "options": [{
                        "optionId": ${own.firstOption.id},
                        "itemId": ${own.firstItem.id},
                        "expectedIsAvailable": true,
                        "desiredIsAvailable": false,
                        "priceDeltaMinor": 1
                      }]
                    }
                    """.trimIndent(),
                status = HttpStatusCode.BadRequest,
                code = ApiErrorCodes.INVALID_INPUT,
            )

            assertRejected(
                body =
                    shiftCheckBody(
                        itemChanges =
                            listOf(
                                Triple(own.firstItem.id, true, false),
                                Triple(Long.MAX_VALUE, true, false),
                            ),
                    ),
                status = HttpStatusCode.NotFound,
                code = ApiErrorCodes.NOT_FOUND,
            )

            assertRejected(
                body =
                    shiftCheckBody(
                        itemChanges = listOf(Triple(own.firstItem.id, true, false)),
                        optionChanges =
                            listOf(
                                ShiftCheckOptionJson(
                                    optionId = Long.MAX_VALUE,
                                    itemId = own.secondItem.id,
                                ),
                            ),
                    ),
                status = HttpStatusCode.NotFound,
                code = ApiErrorCodes.NOT_FOUND,
            )

            assertRejected(
                body =
                    shiftCheckBody(
                        itemChanges =
                            listOf(
                                Triple(own.firstItem.id, true, false),
                                Triple(foreign.firstItem.id, true, false),
                            ),
                    ),
                status = HttpStatusCode.NotFound,
                code = ApiErrorCodes.NOT_FOUND,
            )

            assertRejected(
                body =
                    shiftCheckBody(
                        itemChanges = listOf(Triple(own.firstItem.id, true, false)),
                        optionChanges =
                            listOf(
                                ShiftCheckOptionJson(
                                    optionId = foreign.firstOption.id,
                                    itemId = own.secondItem.id,
                                ),
                            ),
                    ),
                status = HttpStatusCode.NotFound,
                code = ApiErrorCodes.NOT_FOUND,
            )

            assertRejected(
                body =
                    shiftCheckBody(
                        optionChanges =
                            listOf(
                                ShiftCheckOptionJson(
                                    optionId = own.firstOption.id,
                                    itemId = own.secondItem.id,
                                ),
                            ),
                    ),
                status = HttpStatusCode.BadRequest,
                code = ApiErrorCodes.INVALID_INPUT,
            )

            assertRejected(
                body =
                    shiftCheckBody(
                        itemChanges =
                            (1L..501L).map { id ->
                                Triple(100_000L + id, true, false)
                            },
                    ),
                status = HttpStatusCode.BadRequest,
                code = ApiErrorCodes.INVALID_INPUT,
            )

            assertRejected(
                body =
                    shiftCheckBody(
                        itemChanges =
                            listOf(
                                Triple(own.firstItem.id, false, true),
                                Triple(own.secondItem.id, true, false),
                            ),
                    ),
                status = HttpStatusCode.Conflict,
                code = ApiErrorCodes.MENU_SHIFT_CHECK_STALE,
            )
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

    private suspend fun postShiftCheck(
        client: HttpClient,
        token: String,
        venueId: Long,
        body: String,
    ): HttpResponse =
        client.post("/api/venue/menu/shift-check?venueId=$venueId") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody(body)
        }

    private suspend fun createShiftCheckFixture(
        jdbcUrl: String,
        venueId: Long,
    ): ShiftCheckFixture {
        val dataSource =
            JdbcDataSource().apply {
                setURL(jdbcUrl)
                user = "sa"
                password = ""
            }
        val repository = VenueMenuRepository(dataSource)
        val category = repository.createCategory(venueId, "Shift check")
        val firstItem =
            requireNotNull(
                repository.createItem(
                    venueId = venueId,
                    categoryId = category.id,
                    name = "First private item name",
                    priceMinor = 100,
                    currency = "RUB",
                    isAvailable = true,
                ),
            )
        val secondItem =
            requireNotNull(
                repository.createItem(
                    venueId = venueId,
                    categoryId = category.id,
                    name = "Second item",
                    priceMinor = 200,
                    currency = "RUB",
                    isAvailable = true,
                ),
            )
        val firstOption =
            requireNotNull(
                repository.createOption(
                    venueId = venueId,
                    itemId = firstItem.id,
                    name = "First option",
                    priceDeltaMinor = 10,
                    isAvailable = true,
                ),
            )
        val secondOption =
            requireNotNull(
                repository.createOption(
                    venueId = venueId,
                    itemId = secondItem.id,
                    name = "Second private option name",
                    priceDeltaMinor = 20,
                    isAvailable = true,
                ),
            )
        return ShiftCheckFixture(firstItem, secondItem, firstOption, secondOption)
    }

    private fun shiftCheckAuditPayloads(jdbcUrl: String): List<JsonObject> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM audit_log
                WHERE action = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, MENU_SHIFT_CHECK_COMPLETED_AUDIT_ACTION)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(json.parseToJsonElement(rs.getString("payload_json")) as JsonObject)
                        }
                    }
                }
            }
        }

    private fun shiftCheckBody(
        itemChanges: List<Triple<Long, Boolean, Boolean>> = emptyList(),
        optionChanges: List<ShiftCheckOptionJson> = emptyList(),
    ): String {
        val itemsJson =
            itemChanges.joinToString(",") { (itemId, expected, desired) ->
                """
                {
                  "itemId": $itemId,
                  "expectedIsAvailable": $expected,
                  "desiredIsAvailable": $desired
                }
                """.trimIndent()
            }
        val optionsJson =
            optionChanges.joinToString(",") { option ->
                """
                {
                  "optionId": ${option.optionId},
                  "itemId": ${option.itemId},
                  "expectedIsAvailable": ${option.expectedIsAvailable},
                  "desiredIsAvailable": ${option.desiredIsAvailable}
                }
                """.trimIndent()
            }
        return """{"items":[$itemsJson],"options":[$optionsJson]}"""
    }

    private data class ShiftCheckFixture(
        val firstItem: VenueMenuItem,
        val secondItem: VenueMenuItem,
        val firstOption: VenueMenuOption,
        val secondOption: VenueMenuOption,
    )

    private data class ShiftCheckOptionJson(
        val optionId: Long,
        val itemId: Long,
        val expectedIsAvailable: Boolean = true,
        val desiredIsAvailable: Boolean = false,
    )

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
        val supportsBaseFlavorProfiles: Boolean = false,
        val missingBaseFlavorProfilesCount: Int = 0,
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

    @Serializable
    private data class ApplyBaseFlavorProfilesResponse(
        val itemId: Long,
        val addedCount: Int,
        val existingCount: Int,
        val options: List<VenueMenuOptionDto>,
    )

    companion object {
        private const val TELEGRAM_USER_ID = 100500L
        private const val OWNER_USER_ID = 100501L
        private const val MANAGER_USER_ID = 100502L
        private const val STAFF_USER_ID = 100503L
        private const val FOREIGN_USER_ID = 100504L
    }
}
