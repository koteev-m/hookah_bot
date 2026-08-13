package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.api.MENU_ITEM_DELETE_BLOCKED_BY_FIXED_REWARD_MESSAGE
import com.hookah.platform.backend.miniapp.guest.api.MenuResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.menu.MENU_CATEGORIES_REORDERED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_CATEGORY_CREATED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_CATEGORY_DELETED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_CATEGORY_RENAMED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_CATEGORY_TYPE_CHANGED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_ITEMS_REORDERED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_ITEM_CATEGORY_MOVED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_ITEM_CREATED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_ITEM_DELETED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_ITEM_PRICE_CHANGED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_ITEM_RENAMED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_ITEM_TYPE_CHANGED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_OPTION_AVAILABILITY_CHANGED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_OPTION_CREATED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_OPTION_DELETED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_OPTION_PRICE_CHANGED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_OPTION_RENAMED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_SHIFT_CHECK_COMPLETED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MenuItemAvailabilitySource
import com.hookah.platform.backend.miniapp.venue.menu.MenuItemCreateSource
import com.hookah.platform.backend.miniapp.venue.menu.MenuOptionCreateSource
import com.hookah.platform.backend.miniapp.venue.menu.MenuSemanticType
import com.hookah.platform.backend.miniapp.venue.menu.MenuShiftCheckResponse
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuItem
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuOption
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuRepository
import com.hookah.platform.backend.miniapp.venue.menu.menuOrderSha256
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
import kotlinx.serialization.json.jsonObject
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
    fun `owner and manager menu management derive actor source and write no duplicate audits`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-management-audit-route")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 69_101L
            val managerId = 69_102L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, managerId, "MANAGER", venueId)
            val ownerToken = issueToken(config, ownerId)
            val managerToken = issueToken(config, managerId)

            suspend fun createCategory(
                token: String,
                name: String,
            ): VenueMenuCategoryDto {
                val response =
                    client.post(
                        "/api/venue/menu/categories?venueId=$venueId" +
                            "&actorUserId=999999&source=TELEGRAM_BOT",
                    ) {
                        headers {
                            append(HttpHeaders.Authorization, "Bearer $token")
                            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        }
                        setBody(
                            """
                            {
                              "name": "$name",
                              "actorUserId": 999999,
                              "source": "TELEGRAM_BOT",
                              "callbackData": "private callback"
                            }
                            """.trimIndent(),
                        )
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                return json.decodeFromString(VenueMenuCategoryDto.serializer(), response.bodyAsText())
            }

            val firstCategory = createCategory(ownerToken, "Private first category")
            val secondCategory = createCategory(managerToken, "Private second category")
            val categoryPatch =
                patchItem(
                    client,
                    ownerToken,
                    "/api/venue/menu/categories/${firstCategory.id}?venueId=$venueId" +
                        "&actorUserId=999999&source=TELEGRAM_BOT",
                    """
                    {
                      "name": "Private renamed category",
                      "categoryType": "DRINK",
                      "actorUserId": 999999,
                      "source": "TELEGRAM_BOT"
                    }
                    """.trimIndent(),
                )
            assertEquals(HttpStatusCode.OK, categoryPatch.status)

            val firstItemResponse =
                postMenuItem(
                    client,
                    ownerToken,
                    "/api/venue/menu/items?venueId=$venueId",
                    """
                    {
                      "categoryId": ${firstCategory.id},
                      "name": "Private first item",
                      "priceMinor": 100,
                      "currency": "RUB",
                      "isAvailable": true
                    }
                    """.trimIndent(),
                )
            val firstItem =
                json.decodeFromString(VenueMenuItemDto.serializer(), firstItemResponse.bodyAsText())
            val itemPatch =
                patchItem(
                    client,
                    managerToken,
                    "/api/venue/menu/items/${firstItem.id}?venueId=$venueId" +
                        "&actorUserId=999999&source=TELEGRAM_BOT",
                    """
                    {
                      "categoryId": ${secondCategory.id},
                      "name": "Private moved item",
                      "priceMinor": 250,
                      "currency": "RUB",
                      "itemType": "DRINK",
                      "actorUserId": 999999,
                      "source": "TELEGRAM_BOT"
                    }
                    """.trimIndent(),
                )
            assertEquals(HttpStatusCode.OK, itemPatch.status)

            val secondItemResponse =
                postMenuItem(
                    client,
                    managerToken,
                    "/api/venue/menu/items?venueId=$venueId",
                    """
                    {
                      "categoryId": ${secondCategory.id},
                      "name": "Private second item",
                      "priceMinor": 300,
                      "currency": "RUB",
                      "isAvailable": true
                    }
                    """.trimIndent(),
                )
            val secondItem =
                json.decodeFromString(VenueMenuItemDto.serializer(), secondItemResponse.bodyAsText())

            val categoryOrder = listOf(secondCategory.id, firstCategory.id)
            val categoryReorder =
                client.post("/api/venue/menu/reorder/categories?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $ownerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"categoryIds":$categoryOrder}""")
                }
            assertEquals(HttpStatusCode.OK, categoryReorder.status)
            val itemOrder = listOf(secondItem.id, firstItem.id)
            val itemReorder =
                client.post("/api/venue/menu/reorder/items?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $managerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"categoryId":${secondCategory.id},"itemIds":$itemOrder}""")
                }
            assertEquals(HttpStatusCode.OK, itemReorder.status)

            val expected =
                mapOf(
                    MENU_CATEGORY_CREATED_AUDIT_ACTION to listOf(ownerId, managerId),
                    MENU_CATEGORY_RENAMED_AUDIT_ACTION to listOf(ownerId),
                    MENU_CATEGORY_TYPE_CHANGED_AUDIT_ACTION to listOf(ownerId),
                    MENU_CATEGORIES_REORDERED_AUDIT_ACTION to listOf(ownerId),
                    MENU_ITEM_RENAMED_AUDIT_ACTION to listOf(managerId),
                    MENU_ITEM_PRICE_CHANGED_AUDIT_ACTION to listOf(managerId),
                    MENU_ITEM_TYPE_CHANGED_AUDIT_ACTION to listOf(managerId),
                    MENU_ITEM_CATEGORY_MOVED_AUDIT_ACTION to listOf(managerId),
                    MENU_ITEMS_REORDERED_AUDIT_ACTION to listOf(managerId),
                )
            expected.forEach { (action, expectedActors) ->
                val audits = menuDeleteAudits(jdbcUrl, action)
                assertEquals(expectedActors, audits.map { it.actorUserId }, action)
                audits.forEach { audit ->
                    assertEquals("VENUE_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
                    val serialized = audit.payload.toString()
                    assertFalse(serialized.contains("999999"))
                    assertFalse(serialized.contains("TELEGRAM_BOT"))
                    assertFalse(serialized.contains("Private"))
                    assertFalse(serialized.contains("callback"))
                }
            }
            val categoryReorderAudit =
                menuDeleteAudits(jdbcUrl, MENU_CATEGORIES_REORDERED_AUDIT_ACTION).single()
            assertEquals(
                menuOrderSha256(listOf(firstCategory.id, secondCategory.id)),
                categoryReorderAudit.payload.getValue("oldOrderSha256").jsonPrimitive.content,
            )
            assertEquals(
                menuOrderSha256(categoryOrder),
                categoryReorderAudit.payload.getValue("newOrderSha256").jsonPrimitive.content,
            )

            assertEquals(
                HttpStatusCode.OK,
                patchItem(
                    client,
                    ownerToken,
                    "/api/venue/menu/categories/${firstCategory.id}?venueId=$venueId",
                    """{"name":"Private renamed category","categoryType":"DRINK"}""",
                ).status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.post("/api/venue/menu/reorder/categories?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $ownerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"categoryIds":$categoryOrder}""")
                }.status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.post("/api/venue/menu/reorder/items?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $managerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"categoryId":${secondCategory.id},"itemIds":$itemOrder}""")
                }.status,
            )
            expected.forEach { (action, expectedActors) ->
                assertEquals(expectedActors.size, menuDeleteAudits(jdbcUrl, action).size, action)
            }
        }

    @Test
    fun `menu management denies staff foreign unaffiliated and platform-only before target facts`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-management-rbac-route")
            val platformOwnerId = 69_305L
            val config = buildConfig(jdbcUrl, platformOwnerId)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 69_301L
            val staffId = 69_302L
            val foreignManagerId = 69_303L
            val unaffiliatedId = 69_304L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, staffId, "STAFF", venueId)
            seedVenueWithRole(jdbcUrl, foreignManagerId, "MANAGER")
            seedUser(jdbcUrl, unaffiliatedId)
            seedUser(jdbcUrl, platformOwnerId)
            val repository = menuRepository(jdbcUrl)
            val category =
                repository.createCategory(
                    venueId,
                    "Protected category",
                    ownerId,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                )
            val item =
                requireNotNull(
                    repository.createItem(
                        venueId = venueId,
                        categoryId = category.id,
                        name = "Protected item",
                        priceMinor = 100,
                        currency = "RUB",
                        isAvailable = true,
                        actorUserId = ownerId,
                        source = MenuItemCreateSource.VENUE_MINI_APP,
                    ),
                )
            val stateBefore = repository.getMenu(venueId)
            val managementActions =
                listOf(
                    MENU_CATEGORY_CREATED_AUDIT_ACTION,
                    MENU_CATEGORY_RENAMED_AUDIT_ACTION,
                    MENU_CATEGORY_TYPE_CHANGED_AUDIT_ACTION,
                    MENU_CATEGORIES_REORDERED_AUDIT_ACTION,
                    MENU_ITEM_RENAMED_AUDIT_ACTION,
                    MENU_ITEM_PRICE_CHANGED_AUDIT_ACTION,
                    MENU_ITEM_TYPE_CHANGED_AUDIT_ACTION,
                    MENU_ITEM_CATEGORY_MOVED_AUDIT_ACTION,
                    MENU_ITEMS_REORDERED_AUDIT_ACTION,
                )
            val auditCountsBefore =
                managementActions.associateWith { action -> menuDeleteAudits(jdbcUrl, action).size }

            suspend fun assertForbidden(response: HttpResponse) {
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
            }

            listOf(staffId, foreignManagerId, unaffiliatedId, platformOwnerId).forEach { actorId ->
                val token = issueToken(config, actorId)
                assertForbidden(
                    client.post("/api/venue/menu/categories?venueId=$venueId") {
                        headers {
                            append(HttpHeaders.Authorization, "Bearer $token")
                            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        }
                        setBody("""{"name":""}""")
                    },
                )
                assertForbidden(
                    patchItem(
                        client,
                        token,
                        "/api/venue/menu/categories/${Long.MAX_VALUE}?venueId=$venueId",
                        """{"name":"","categoryType":"PRIVATE"}""",
                    ),
                )
                assertForbidden(
                    patchItem(
                        client,
                        token,
                        "/api/venue/menu/items/${Long.MAX_VALUE}?venueId=$venueId",
                        """{"categoryId":${Long.MAX_VALUE},"name":"","priceMinor":-1,"itemType":"PRIVATE"}""",
                    ),
                )
                assertForbidden(
                    client.post("/api/venue/menu/reorder/categories?venueId=$venueId") {
                        headers {
                            append(HttpHeaders.Authorization, "Bearer $token")
                            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        }
                        setBody("""{"categoryIds":[]}""")
                    },
                )
                assertForbidden(
                    client.post("/api/venue/menu/reorder/items?venueId=$venueId") {
                        headers {
                            append(HttpHeaders.Authorization, "Bearer $token")
                            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        }
                        setBody("""{"categoryId":${Long.MAX_VALUE},"itemIds":[]}""")
                    },
                )
            }

            assertEquals(stateBefore, repository.getMenu(venueId))
            assertEquals(category.id, repository.getMenu(venueId).single().id)
            assertEquals(item.id, repository.getMenu(venueId).single().items.single().id)
            auditCountsBefore.forEach { (action, expectedCount) ->
                assertEquals(expectedCount, menuDeleteAudits(jdbcUrl, action).size, action)
            }
        }

    @Test
    fun `category second audit failure returns safe error and rolls back compound patch`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-category-compound-audit-rollback-route")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 69_201L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            val repository = menuRepository(jdbcUrl)
            val category =
                repository.createCategory(
                    venueId,
                    "Original private category",
                    ownerId,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                )
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        ALTER TABLE audit_log
                        ADD CONSTRAINT reject_category_type_management_audit
                        CHECK (action <> '$MENU_CATEGORY_TYPE_CHANGED_AUDIT_ACTION')
                        """.trimIndent(),
                    )
                }
            }

            val response =
                patchItem(
                    client,
                    issueToken(config, ownerId),
                    "/api/venue/menu/categories/${category.id}?venueId=$venueId",
                    """{"name":"Must not leak or persist","categoryType":"DRINK"}""",
                )

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
            assertFalse(response.bodyAsText().contains(MENU_CATEGORY_TYPE_CHANGED_AUDIT_ACTION))
            assertFalse(response.bodyAsText().contains("Must not leak"))
            val saved = repository.getMenu(venueId).single()
            assertEquals(category.name, saved.name)
            assertEquals(category.categoryType, saved.categoryType)
            assertTrue(menuDeleteAudits(jdbcUrl, MENU_CATEGORY_RENAMED_AUDIT_ACTION).isEmpty())
            assertTrue(menuDeleteAudits(jdbcUrl, MENU_CATEGORY_TYPE_CHANGED_AUDIT_ACTION).isEmpty())
        }

    @Test
    fun `owner and manager item create derive actor and source and preserve response contract`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-item-create-audit-success")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 70_101L
            val managerId = 70_102L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, managerId, "MANAGER", venueId)
            val category =
                menuRepository(jdbcUrl).createCategory(
                    venueId,
                    "Private category",
                    ownerId,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                )

            val responses =
                listOf(
                    ownerId to "First private item",
                    managerId to "Second private item",
                ).mapIndexed { index, (actorId, name) ->
                    postMenuItem(
                        client = client,
                        token = issueToken(config, actorId),
                        url =
                            "/api/venue/menu/items?venueId=$venueId" +
                                "&actorUserId=999999&source=TELEGRAM_BOT",
                        body =
                            """
                            {
                              "categoryId": ${category.id},
                              "name": "$name",
                              "priceMinor": ${250 + index},
                              "currency": "rub",
                              "isAvailable": ${index == 0},
                              "actorUserId": 999999,
                              "source": "TELEGRAM_BOT",
                              "rawRequest": "private body"
                            }
                            """.trimIndent(),
                    )
                }

            responses.forEachIndexed { index, response ->
                assertEquals(HttpStatusCode.OK, response.status)
                val raw = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(
                    setOf(
                        "id",
                        "categoryId",
                        "name",
                        "priceMinor",
                        "currency",
                        "isAvailable",
                        "sortOrder",
                        "effectiveItemType",
                        "options",
                    ),
                    raw.keys,
                )
                val item = json.decodeFromString(VenueMenuItemDto.serializer(), raw.toString())
                assertEquals(category.id, item.categoryId)
                assertEquals(250L + index, item.priceMinor)
                assertEquals("RUB", item.currency)
                assertEquals(index == 0, item.isAvailable)
                assertEquals(index, item.sortOrder)
                assertEquals(null, item.itemType)
                assertEquals("OTHER", item.effectiveItemType)
                assertFalse(item.supportsBaseFlavorProfiles)
                assertEquals(0, item.missingBaseFlavorProfilesCount)
                assertTrue(item.options.isEmpty())
            }

            val createdItems = responses.map { json.decodeFromString(VenueMenuItemDto.serializer(), it.bodyAsText()) }
            val audits = menuItemCreateAudits(jdbcUrl)
            assertEquals(2, audits.size)
            assertEquals(listOf(ownerId, managerId), audits.map { it.actorUserId })
            assertEquals(createdItems.map { it.id }, audits.map { it.entityId })
            audits.forEachIndexed { index, audit ->
                assertEquals("menu_item", audit.entityType)
                assertEquals(setOf("venueId", "itemId", "source"), audit.payload.keys)
                assertEquals(venueId, audit.payload.getValue("venueId").jsonPrimitive.content.toLong())
                assertEquals(createdItems[index].id, audit.payload.getValue("itemId").jsonPrimitive.content.toLong())
                assertEquals("VENUE_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
                assertFalse(audit.payload.toString().contains(createdItems[index].name))
                assertFalse(audit.payload.toString().contains("private body"))
            }
        }

    @Test
    fun `item create denies staff foreign unaffiliated and platform-only actors before scope facts`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-item-create-audit-rbac")
            val platformOwnerId = 70_205L
            val config = buildConfig(jdbcUrl, platformOwnerId)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 70_201L
            val staffId = 70_202L
            val foreignManagerId = 70_203L
            val unaffiliatedId = 70_204L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, staffId, "STAFF", venueId)
            val foreignVenueId = seedVenueWithRole(jdbcUrl, foreignManagerId, "MANAGER")
            seedUser(jdbcUrl, unaffiliatedId)
            seedUser(jdbcUrl, platformOwnerId)
            val category =
                menuRepository(jdbcUrl).createCategory(
                    venueId,
                    "Private category",
                    ownerId,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                )
            val foreignCategory =
                menuRepository(jdbcUrl).createCategory(
                    foreignVenueId,
                    "Foreign private category",
                    foreignManagerId,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                )

            listOf(staffId, foreignManagerId, unaffiliatedId, platformOwnerId).forEach { actorId ->
                val denied =
                    postMenuItem(
                        client = client,
                        token = issueToken(config, actorId),
                        url = "/api/venue/menu/items?venueId=$venueId",
                        body =
                            """
                            {
                              "categoryId": ${category.id},
                              "name": "",
                              "priceMinor": -1,
                              "currency": "PRIVATE"
                            }
                            """.trimIndent(),
                    )
                assertEquals(HttpStatusCode.Forbidden, denied.status)
                assertApiErrorEnvelope(denied, ApiErrorCodes.FORBIDDEN)
            }

            listOf(foreignCategory.id, Long.MAX_VALUE).forEach { invalidCategoryId ->
                val invalidScope =
                    postMenuItem(
                        client = client,
                        token = issueToken(config, ownerId),
                        url = "/api/venue/menu/items?venueId=$venueId",
                        body =
                            """
                            {
                              "categoryId": $invalidCategoryId,
                              "name": "Valid private item",
                              "priceMinor": 100,
                              "currency": "RUB",
                              "isAvailable": true
                            }
                            """.trimIndent(),
                    )
                assertEquals(HttpStatusCode.BadRequest, invalidScope.status)
                assertApiErrorEnvelope(invalidScope, ApiErrorCodes.INVALID_INPUT)
            }

            assertTrue(menuRepository(jdbcUrl).getMenu(venueId).flatMap { it.items }.isEmpty())
            assertTrue(menuItemCreateAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `item create audit SQL failure returns safe error and rolls back physical item`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-item-create-audit-rollback")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 70_301L
            val managerId = 70_302L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, managerId, "MANAGER", venueId)
            val category =
                menuRepository(jdbcUrl).createCategory(
                    venueId,
                    "Private category",
                    ownerId,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                )
            val baselineResponse =
                postMenuItem(
                    client = client,
                    token = issueToken(config, ownerId),
                    url = "/api/venue/menu/items?venueId=$venueId",
                    body =
                        """
                        {
                          "categoryId": ${category.id},
                          "name": "Existing private item",
                          "priceMinor": 100,
                          "currency": "RUB",
                          "isAvailable": true
                        }
                        """.trimIndent(),
                )
            assertEquals(HttpStatusCode.OK, baselineResponse.status)
            val itemsBefore = menuRepository(jdbcUrl).getMenu(venueId)
            val auditsBefore = menuItemCreateAudits(jdbcUrl)
            assertEquals(1, itemsBefore.flatMap { it.items }.size)
            assertEquals(1, auditsBefore.size)
            rejectMenuItemCreateAuditsForActor(jdbcUrl, managerId)

            val response =
                postMenuItem(
                    client = client,
                    token = issueToken(config, managerId),
                    url = "/api/venue/menu/items?venueId=$venueId",
                    body =
                        """
                        {
                          "categoryId": ${category.id},
                          "name": "Rolled back private item",
                          "priceMinor": 999,
                          "currency": "RUB",
                          "isAvailable": false
                        }
                        """.trimIndent(),
                )

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
            assertFalse(response.bodyAsText().contains(MENU_ITEM_CREATED_AUDIT_ACTION))
            assertFalse(response.bodyAsText().contains("Rolled back private item"))
            assertEquals(itemsBefore, menuRepository(jdbcUrl).getMenu(venueId))
            assertEquals(auditsBefore, menuItemCreateAudits(jdbcUrl))
        }

    @Test
    fun `owner and manager item delete derive actor and source and preserve success response`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-item-delete-audit-success")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 71_001L
            val managerId = 71_002L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, managerId, "MANAGER", venueId)
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)

            val ownerResponse =
                client.delete(
                    "/api/venue/menu/items/${fixture.firstItem.id}" +
                        "?venueId=$venueId&actorUserId=999999&source=TELEGRAM_BOT",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, ownerId)}") }
                }
            val managerResponse =
                client.delete("/api/venue/menu/items/${fixture.secondItem.id}?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, managerId)}") }
                }

            listOf(ownerResponse, managerResponse).forEach { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(
                    mapOf("ok" to true),
                    json.parseToJsonElement(response.bodyAsText()).jsonObject
                        .mapValues { it.value.jsonPrimitive.content.toBoolean() },
                )
            }
            val audits = menuItemDeleteAudits(jdbcUrl)
            assertEquals(2, audits.size)
            assertEquals(listOf(ownerId, managerId), audits.map { it.actorUserId })
            assertEquals(
                listOf(fixture.firstItem.id, fixture.secondItem.id),
                audits.map { it.entityId },
            )
            audits.forEach { audit ->
                assertEquals("menu_item", audit.entityType)
                assertEquals("VENUE_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
                assertFalse(audit.payload.toString().contains("999999"))
                assertFalse(audit.payload.toString().contains("TELEGRAM_BOT"))
            }
        }

    @Test
    fun `fixed reward item delete returns safe conflict and leaves state unchanged`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-item-delete-fixed-reward")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 71_101L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)
            val ruleId = seedFixedRewardRule(jdbcUrl, venueId, fixture.firstItem.id, ownerId)
            val before = readFixedRewardState(jdbcUrl, ruleId)

            repeat(2) {
                val response =
                    client.delete("/api/venue/menu/items/${fixture.firstItem.id}?venueId=$venueId") {
                        headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, ownerId)}") }
                    }

                assertEquals(HttpStatusCode.Conflict, response.status)
                val error =
                    assertApiErrorEnvelope(
                        response,
                        ApiErrorCodes.MENU_ITEM_DELETE_BLOCKED_BY_FIXED_REWARD,
                    )
                assertEquals(MENU_ITEM_DELETE_BLOCKED_BY_FIXED_REWARD_MESSAGE, error.error.message)
                assertFalse(response.bodyAsText().contains("Private promotion title"))
                assertFalse(response.bodyAsText().contains("ruleId", ignoreCase = true))
                assertFalse(response.bodyAsText().contains("promotionId", ignoreCase = true))
                assertFalse(response.bodyAsText().contains("SQL", ignoreCase = true))
            }

            assertTrue(menuRepository(jdbcUrl).itemExists(venueId, fixture.firstItem.id))
            assertEquals(before, readFixedRewardState(jdbcUrl, ruleId))
            assertTrue(menuItemDeleteAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `staff foreign and unaffiliated actors cannot delete item or create audit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-item-delete-audit-denials")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 72_001L
            val staffId = 72_002L
            val foreignManagerId = 72_003L
            val guestId = 72_004L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, staffId, "STAFF", venueId)
            seedVenueWithRole(jdbcUrl, foreignManagerId, "MANAGER")
            seedUser(jdbcUrl, guestId)
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)

            listOf(staffId, foreignManagerId, guestId).forEach { actorId ->
                val response =
                    client.delete("/api/venue/menu/items/${fixture.firstItem.id}?venueId=$venueId") {
                        headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, actorId)}") }
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
            }

            assertTrue(menuRepository(jdbcUrl).itemExists(venueId, fixture.firstItem.id))
            assertTrue(menuItemDeleteAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `item delete audit failure returns safe error and rolls back delete`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-item-delete-audit-failure")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 73_001L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        ALTER TABLE audit_log
                        ADD CONSTRAINT reject_menu_item_delete_audit
                        CHECK (action <> '$MENU_ITEM_DELETED_AUDIT_ACTION')
                        """.trimIndent(),
                    )
                }
            }

            val response =
                client.delete("/api/venue/menu/items/${fixture.firstItem.id}?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, ownerId)}") }
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
            assertFalse(response.bodyAsText().contains("MENU_ITEM_DELETED"))
            assertTrue(menuRepository(jdbcUrl).itemExists(venueId, fixture.firstItem.id))
            assertTrue(menuItemDeleteAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `owner and manager category delete derive actor and source and preserve success response`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-category-delete-audit-success")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 74_001L
            val managerId = 74_002L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, managerId, "MANAGER", venueId)
            val repository = menuRepository(jdbcUrl)
            val ownerCategory =
                repository.createCategory(
                    venueId,
                    "Owner private category",
                    ownerId,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                )
            val managerCategory =
                repository.createCategory(
                    venueId,
                    "Manager private category",
                    managerId,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                )

            val ownerResponse =
                client.delete(
                    "/api/venue/menu/categories/${ownerCategory.id}" +
                        "?venueId=$venueId&actorUserId=999999&source=TELEGRAM_BOT",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, ownerId)}") }
                }
            val managerResponse =
                client.delete("/api/venue/menu/categories/${managerCategory.id}?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, managerId)}") }
                }

            listOf(ownerResponse, managerResponse).forEach { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(
                    mapOf("ok" to true),
                    json.parseToJsonElement(response.bodyAsText()).jsonObject
                        .mapValues { it.value.jsonPrimitive.content.toBoolean() },
                )
            }
            val audits = menuCategoryDeleteAudits(jdbcUrl)
            assertEquals(listOf(ownerId, managerId), audits.map { it.actorUserId })
            assertEquals(listOf(ownerCategory.id, managerCategory.id), audits.map { it.entityId })
            audits.forEach { audit ->
                assertEquals("menu_category", audit.entityType)
                assertEquals("VENUE_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
                assertFalse(audit.payload.toString().contains("999999"))
                assertFalse(audit.payload.toString().contains("TELEGRAM_BOT"))
            }
        }

    @Test
    fun `staff foreign and unaffiliated actors cannot delete category or create audit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-category-delete-audit-denials")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 75_001L
            val staffId = 75_002L
            val foreignManagerId = 75_003L
            val guestId = 75_004L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, staffId, "STAFF", venueId)
            seedVenueWithRole(jdbcUrl, foreignManagerId, "MANAGER")
            seedUser(jdbcUrl, guestId)
            val repository = menuRepository(jdbcUrl)
            val category =
                repository.createCategory(
                    venueId,
                    "Protected category",
                    ownerId,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                )

            listOf(staffId, foreignManagerId, guestId).forEach { actorId ->
                val response =
                    client.delete("/api/venue/menu/categories/${category.id}?venueId=$venueId") {
                        headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, actorId)}") }
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
            }

            assertTrue(repository.categoryExists(venueId, category.id))
            assertTrue(menuCategoryDeleteAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `owner and manager option delete derive exact actor source payload and preserve response`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-option-delete-audit-success")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 76_001L
            val managerId = 76_002L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, managerId, "MANAGER", venueId)
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)

            val ownerResponse =
                client.delete(
                    "/api/venue/menu/options/${fixture.firstOption.id}" +
                        "?venueId=$venueId&actorUserId=999999&source=TELEGRAM_BOT",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, ownerId)}") }
                }
            val managerResponse =
                client.delete("/api/venue/menu/options/${fixture.secondOption.id}?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, managerId)}") }
                }

            listOf(ownerResponse, managerResponse).forEach { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(
                    mapOf("ok" to true),
                    json.parseToJsonElement(response.bodyAsText()).jsonObject
                        .mapValues { it.value.jsonPrimitive.content.toBoolean() },
                )
            }

            val audits = menuOptionDeleteAudits(jdbcUrl)
            assertEquals(2, audits.size)
            assertEquals(listOf(ownerId, managerId), audits.map { it.actorUserId })
            assertEquals(
                listOf(fixture.firstOption.id, fixture.secondOption.id),
                audits.map { it.entityId },
            )
            val expectedItemIds = listOf(fixture.firstItem.id, fixture.secondItem.id)
            audits.forEachIndexed { index, audit ->
                assertEquals("menu_item_option", audit.entityType)
                assertEquals(
                    setOf("venueId", "itemId", "optionId", "source"),
                    audit.payload.keys,
                )
                assertEquals(venueId, audit.payload.getValue("venueId").jsonPrimitive.content.toLong())
                assertEquals(
                    expectedItemIds[index],
                    audit.payload.getValue("itemId").jsonPrimitive.content.toLong(),
                )
                assertEquals(
                    audit.entityId,
                    audit.payload.getValue("optionId").jsonPrimitive.content.toLong(),
                )
                assertEquals(
                    "VENUE_MINI_APP",
                    audit.payload.getValue("source").jsonPrimitive.content,
                )
                assertFalse(audit.payload.toString().contains("999999"))
                assertFalse(audit.payload.toString().contains("TELEGRAM_BOT"))
            }
        }

    @Test
    fun `staff foreign and unaffiliated actors cannot delete option or create audit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-option-delete-audit-denials")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 77_001L
            val staffId = 77_002L
            val foreignManagerId = 77_003L
            val guestId = 77_004L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, staffId, "STAFF", venueId)
            val foreignVenueId = seedVenueWithRole(jdbcUrl, foreignManagerId, "MANAGER")
            seedUser(jdbcUrl, guestId)
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)
            val foreignFixture = createShiftCheckFixture(jdbcUrl, foreignVenueId)

            listOf(staffId, foreignManagerId, guestId).forEach { actorId ->
                val response =
                    client.delete(
                        "/api/venue/menu/options/${fixture.firstOption.id}?venueId=$venueId",
                    ) {
                        headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, actorId)}") }
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
                assertFalse(response.bodyAsText().contains("First option"))
            }

            val foreignScopeResponse =
                client.delete(
                    "/api/venue/menu/options/${fixture.firstOption.id}?venueId=$foreignVenueId",
                ) {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer ${issueToken(config, foreignManagerId)}")
                    }
                }
            assertEquals(HttpStatusCode.NotFound, foreignScopeResponse.status)
            assertApiErrorEnvelope(foreignScopeResponse, ApiErrorCodes.NOT_FOUND)
            assertFalse(foreignScopeResponse.bodyAsText().contains("First option"))

            assertTrue(menuRepository(jdbcUrl).optionExists(venueId, fixture.firstOption.id))
            assertTrue(menuRepository(jdbcUrl).optionExists(foreignVenueId, foreignFixture.firstOption.id))
            assertTrue(menuOptionDeleteAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `repeated option delete returns not found without duplicate audit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-option-delete-audit-repeat")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 78_001L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)
            val url = "/api/venue/menu/options/${fixture.firstOption.id}?venueId=$venueId"

            val firstResponse =
                client.delete(url) {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, ownerId)}") }
                }
            val repeatedResponse =
                client.delete(url) {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, ownerId)}") }
                }

            assertEquals(HttpStatusCode.OK, firstResponse.status)
            assertEquals(
                mapOf("ok" to true),
                json.parseToJsonElement(firstResponse.bodyAsText()).jsonObject
                    .mapValues { it.value.jsonPrimitive.content.toBoolean() },
            )
            assertEquals(HttpStatusCode.NotFound, repeatedResponse.status)
            assertApiErrorEnvelope(repeatedResponse, ApiErrorCodes.NOT_FOUND)
            assertFalse(menuRepository(jdbcUrl).optionExists(venueId, fixture.firstOption.id))
            val audits = menuOptionDeleteAudits(jdbcUrl)
            assertEquals(1, audits.size)
            assertEquals(fixture.firstOption.id, audits.single().entityId)
            assertEquals(ownerId, audits.single().actorUserId)
        }

    @Test
    fun `option delete audit failure returns safe error and rolls back delete`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-option-delete-audit-failure")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 79_001L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        ALTER TABLE audit_log
                        ADD CONSTRAINT reject_menu_option_delete_audit
                        CHECK (action <> '$MENU_OPTION_DELETED_AUDIT_ACTION')
                        """.trimIndent(),
                    )
                }
            }

            val response =
                client.delete(
                    "/api/venue/menu/options/${fixture.firstOption.id}?venueId=$venueId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, ownerId)}") }
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
            assertFalse(response.bodyAsText().contains(MENU_OPTION_DELETED_AUDIT_ACTION))
            assertTrue(menuRepository(jdbcUrl).optionExists(venueId, fixture.firstOption.id))
            assertTrue(menuOptionDeleteAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `owner and manager option create derive exact actor source payload and preserve response dto`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-option-create-audit-success")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 79_011L
            val managerId = 79_012L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, managerId, "MANAGER", venueId)
            val item = createHookahMenuItem(jdbcUrl, venueId, "Create audit item")

            val ownerResponse =
                client.post(
                    "/api/venue/menu/options?venueId=$venueId&actorUserId=999999&source=TELEGRAM_BOT",
                ) {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer ${issueToken(config, ownerId)}")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "itemId": ${item.id},
                          "name": "Private owner option",
                          "priceDeltaMinor": 125,
                          "isAvailable": false,
                          "actorUserId": 999999,
                          "source": "TELEGRAM_BOT",
                          "callbackData": "private callback payload"
                        }
                        """.trimIndent(),
                    )
                }
            val managerResponse =
                client.post("/api/venue/menu/options?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer ${issueToken(config, managerId)}")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "itemId": ${item.id},
                          "name": "Ягодный",
                          "priceDeltaMinor": 0,
                          "isAvailable": true
                        }
                        """.trimIndent(),
                    )
                }

            val responses = listOf(ownerResponse, managerResponse)
            responses.forEach { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(
                    setOf("id", "itemId", "name", "priceDeltaMinor", "isAvailable", "sortOrder"),
                    json.parseToJsonElement(response.bodyAsText()).jsonObject.keys,
                )
            }
            val created =
                responses.map { response ->
                    json.decodeFromString(VenueMenuOptionDto.serializer(), response.bodyAsText())
                }
            assertEquals(listOf("Private owner option", "Ягодный"), created.map { it.name })
            assertEquals(listOf(125L, 0L), created.map { it.priceDeltaMinor })
            assertEquals(listOf(false, true), created.map { it.isAvailable })

            val audits = menuOptionCreateAudits(jdbcUrl)
            assertEquals(listOf(ownerId, managerId), audits.map { it.actorUserId })
            assertEquals(created.map { it.id }, audits.map { it.entityId })
            audits.forEach { audit ->
                assertEquals("menu_item_option", audit.entityType)
                assertEquals(
                    setOf("venueId", "itemId", "optionId", "source"),
                    audit.payload.keys,
                )
                assertEquals(venueId, audit.payload.getValue("venueId").jsonPrimitive.content.toLong())
                assertEquals(item.id, audit.payload.getValue("itemId").jsonPrimitive.content.toLong())
                assertEquals(audit.entityId, audit.payload.getValue("optionId").jsonPrimitive.content.toLong())
                assertEquals("VENUE_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
                assertFalse(audit.payload.toString().contains("Private owner option"))
                assertFalse(audit.payload.toString().contains("Ягодный"))
                assertFalse(audit.payload.toString().contains("999999"))
                assertFalse(audit.payload.toString().contains("TELEGRAM_BOT"))
                assertFalse(audit.payload.toString().contains("callback", ignoreCase = true))
                assertFalse(audit.payload.toString().contains("price", ignoreCase = true))
                assertFalse(audit.payload.toString().contains("availability", ignoreCase = true))
            }
        }

    @Test
    fun `staff foreign and unaffiliated option create denials write zero row and audit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-option-create-audit-denials")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 79_021L
            val staffId = 79_022L
            val foreignManagerId = 79_023L
            val unaffiliatedId = 79_024L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, staffId, "STAFF", venueId)
            seedVenueWithRole(jdbcUrl, foreignManagerId, "MANAGER")
            seedUser(jdbcUrl, unaffiliatedId)
            val item = createHookahMenuItem(jdbcUrl, venueId, "Protected create item")

            listOf(staffId, foreignManagerId, unaffiliatedId).forEach { actorId ->
                val response =
                    client.post("/api/venue/menu/options?venueId=$venueId") {
                        headers {
                            append(HttpHeaders.Authorization, "Bearer ${issueToken(config, actorId)}")
                            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        }
                        setBody(
                            """{"itemId":${item.id},"name":"Private denied option","isAvailable":true}""",
                        )
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
                assertFalse(response.bodyAsText().contains("Private denied option"))
            }

            assertTrue(menuRepository(jdbcUrl).getMenu(venueId).single().items.single().options.isEmpty())
            assertTrue(menuOptionCreateAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `canonical option create collision writes no additional row or audit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-option-create-audit-collision")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 79_031L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            val item = createHookahMenuItem(jdbcUrl, venueId, "Canonical collision item")
            val token = issueToken(config, ownerId)

            val first = postOption(client, token, venueId, item.id, "Ягодный")
            assertEquals(HttpStatusCode.OK, first.status)
            val auditBefore = menuOptionCreateAudits(jdbcUrl)
            assertEquals(1, auditBefore.size)

            val collision = postOption(client, token, venueId, item.id, "  ягодный  ")

            assertEquals(HttpStatusCode.BadRequest, collision.status)
            assertApiErrorEnvelope(collision, ApiErrorCodes.INVALID_INPUT)
            assertEquals(1, menuRepository(jdbcUrl).getMenu(venueId).single().items.single().options.size)
            assertEquals(auditBefore, menuOptionCreateAudits(jdbcUrl))
        }

    @Test
    fun `option create audit failure returns safe error and rolls back option`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-option-create-audit-failure")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 79_041L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            val item = createHookahMenuItem(jdbcUrl, venueId, "Create rollback item")
            rejectMenuOptionCreateAudits(jdbcUrl, "reject_menu_option_create_audit")

            val response = postOption(client, issueToken(config, ownerId), venueId, item.id, "Private rollback option")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
            assertFalse(response.bodyAsText().contains(MENU_OPTION_CREATED_AUDIT_ACTION))
            assertFalse(response.bodyAsText().contains("Private rollback option"))
            assertTrue(menuRepository(jdbcUrl).getMenu(venueId).single().items.single().options.isEmpty())
            assertTrue(menuOptionCreateAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `manager base profile bulk create audits exact cardinality actor source and repeat no-op`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-base-profile-create-audit-cardinality")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val managerId = 79_051L
            val venueId = seedVenueWithRole(jdbcUrl, managerId, "MANAGER")
            val item = createHookahMenuItem(jdbcUrl, venueId, "Bulk create item")
            val url =
                "/api/venue/menu/items/${item.id}/base-flavor-profiles" +
                    "?venueId=$venueId&actorUserId=999999&source=TELEGRAM_BOT"

            val first =
                client.post(url) {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, managerId)}") }
                }
            assertEquals(HttpStatusCode.OK, first.status)
            val applied =
                json.decodeFromString(ApplyBaseFlavorProfilesResponse.serializer(), first.bodyAsText())
            assertEquals(8, applied.addedCount)
            assertEquals(0, applied.existingCount)

            val auditsAfterFirst = menuOptionCreateAudits(jdbcUrl)
            assertEquals(8, auditsAfterFirst.size)
            assertEquals(applied.options.map { it.id }, auditsAfterFirst.map { it.entityId })
            auditsAfterFirst.forEach { audit ->
                assertEquals(managerId, audit.actorUserId)
                assertEquals("menu_item_option", audit.entityType)
                assertEquals(setOf("venueId", "itemId", "optionId", "source"), audit.payload.keys)
                assertEquals(venueId, audit.payload.getValue("venueId").jsonPrimitive.content.toLong())
                assertEquals(item.id, audit.payload.getValue("itemId").jsonPrimitive.content.toLong())
                assertEquals(audit.entityId, audit.payload.getValue("optionId").jsonPrimitive.content.toLong())
                assertEquals("VENUE_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
                assertFalse(audit.payload.toString().contains("999999"))
                assertFalse(audit.payload.toString().contains("TELEGRAM_BOT"))
                assertFalse(audit.payload.toString().contains("name", ignoreCase = true))
                assertFalse(audit.payload.toString().contains("price", ignoreCase = true))
            }

            val repeated =
                client.post(url) {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, managerId)}") }
                }
            assertEquals(HttpStatusCode.OK, repeated.status)
            val noOp =
                json.decodeFromString(ApplyBaseFlavorProfilesResponse.serializer(), repeated.bodyAsText())
            assertEquals(0, noOp.addedCount)
            assertEquals(8, noOp.existingCount)
            assertEquals(auditsAfterFirst, menuOptionCreateAudits(jdbcUrl))
        }

    @Test
    fun `base profile create audit failure returns safe error and rolls back bulk`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-base-profile-create-audit-failure")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 79_061L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            val item = createHookahMenuItem(jdbcUrl, venueId, "Bulk rollback item")
            rejectMenuOptionCreateAudits(jdbcUrl, "reject_menu_base_profile_create_audit")

            val response =
                client.post(
                    "/api/venue/menu/items/${item.id}/base-flavor-profiles?venueId=$venueId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, ownerId)}") }
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
            assertFalse(response.bodyAsText().contains(MENU_OPTION_CREATED_AUDIT_ACTION))
            assertTrue(menuRepository(jdbcUrl).getMenu(venueId).single().items.single().options.isEmpty())
            assertTrue(menuOptionCreateAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `owner manager and staff direct item availability derive actor source while denied and no-op write none`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-item-availability-audit-rbac")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 79_101L
            val managerId = 79_102L
            val staffId = 79_103L
            val foreignManagerId = 79_104L
            val unaffiliatedId = 79_105L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, managerId, "MANAGER", venueId)
            seedVenueWithRole(jdbcUrl, staffId, "STAFF", venueId)
            val foreignVenueId = seedVenueWithRole(jdbcUrl, foreignManagerId, "MANAGER")
            seedUser(jdbcUrl, unaffiliatedId)
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)
            val thirdItem =
                requireNotNull(
                    menuRepository(jdbcUrl).createItem(
                        venueId = venueId,
                        categoryId = fixture.firstItem.categoryId,
                        name = "Third private item name",
                        priceMinor = 300,
                        currency = "RUB",
                        isAvailable = true,
                        actorUserId = ownerId,
                        source = MenuItemCreateSource.VENUE_MINI_APP,
                    ),
                )
            clearSetupItemCreateAudits(jdbcUrl, listOf(thirdItem.id))
            val actorItems =
                listOf(
                    ownerId to fixture.firstItem,
                    managerId to fixture.secondItem,
                    staffId to thirdItem,
                )

            actorItems.forEachIndexed { index, (actorId, item) ->
                val response =
                    patchItem(
                        client,
                        issueToken(config, actorId),
                        "/api/venue/menu/items/${item.id}/availability" +
                            "?venueId=$venueId&actorUserId=999999&source=TELEGRAM_BOT",
                        """
                        {
                          "isAvailable": false,
                          "actorUserId": 999999,
                          "source": "TELEGRAM_BOT",
                          "callbackData": "private item callback $index"
                        }
                        """.trimIndent(),
                    )
                assertEquals(HttpStatusCode.OK, response.status)
                assertFalse(json.decodeFromString(VenueMenuItemDto.serializer(), response.bodyAsText()).isAvailable)
            }

            repeat(2) {
                val noOp =
                    patchItem(
                        client,
                        issueToken(config, staffId),
                        "/api/venue/menu/items/${thirdItem.id}/availability?venueId=$venueId",
                        """{"isAvailable":false}""",
                    )
                assertEquals(HttpStatusCode.OK, noOp.status)
                assertFalse(json.decodeFromString(VenueMenuItemDto.serializer(), noOp.bodyAsText()).isAvailable)
            }

            listOf(foreignManagerId, unaffiliatedId).forEach { deniedId ->
                val denied =
                    patchItem(
                        client,
                        issueToken(config, deniedId),
                        "/api/venue/menu/items/${fixture.firstItem.id}/availability?venueId=$venueId",
                        """{"isAvailable":true}""",
                    )
                assertEquals(HttpStatusCode.Forbidden, denied.status)
                assertApiErrorEnvelope(denied, ApiErrorCodes.FORBIDDEN)
            }
            val foreignScope =
                patchItem(
                    client,
                    issueToken(config, foreignManagerId),
                    "/api/venue/menu/items/${fixture.firstItem.id}/availability?venueId=$foreignVenueId",
                    """{"isAvailable":true}""",
                )
            assertEquals(HttpStatusCode.NotFound, foreignScope.status)
            assertApiErrorEnvelope(foreignScope, ApiErrorCodes.NOT_FOUND)

            val audits = menuItemAvailabilityChangeAudits(jdbcUrl)
            assertEquals(listOf(ownerId, managerId, staffId), audits.map { it.actorUserId })
            assertEquals(actorItems.map { it.second.id }, audits.map { it.entityId })
            assertEquals(
                List(actorItems.size) { MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION },
                auditActions(jdbcUrl).filter { it == MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION },
            )
            audits.forEachIndexed { index, audit ->
                assertEquals("menu_item", audit.entityType)
                assertEquals(
                    setOf("venueId", "itemId", "oldIsAvailable", "newIsAvailable", "source"),
                    audit.payload.keys,
                )
                assertEquals(venueId, audit.payload.getValue("venueId").jsonPrimitive.content.toLong())
                assertEquals(
                    actorItems[index].second.id,
                    audit.payload.getValue("itemId").jsonPrimitive.content.toLong(),
                )
                assertEquals("true", audit.payload.getValue("oldIsAvailable").jsonPrimitive.content)
                assertEquals("false", audit.payload.getValue("newIsAvailable").jsonPrimitive.content)
                assertEquals("VENUE_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
                val serialized = audit.payload.toString()
                assertFalse(serialized.contains("999999"))
                assertFalse(serialized.contains("TELEGRAM_BOT"))
                assertFalse(serialized.contains("private item callback"))
                assertFalse(serialized.contains(actorItems[index].second.name))
                listOf(
                    "price",
                    "currency",
                    "description",
                    "type",
                    "option",
                    "promotion",
                    "cart",
                    "order",
                    "request",
                    "initData",
                    "media",
                    "secret",
                    "username",
                    "phone",
                ).forEach { forbidden ->
                    assertFalse(serialized.contains(forbidden, ignoreCase = true), forbidden)
                }
            }
            assertTrue(
                menuRepository(jdbcUrl).getMenu(venueId)
                    .flatMap { it.items }
                    .filter { it.id in actorItems.map { actorItem -> actorItem.second.id } }
                    .none { it.isAvailable },
            )
        }

    @Test
    fun `direct item availability audit failure returns safe error and rolls back row`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-item-availability-audit-rollback")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 79_201L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        ALTER TABLE audit_log
                        ADD CONSTRAINT reject_menu_item_availability_audit
                        CHECK (action <> '$MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION')
                        """.trimIndent(),
                    )
                }
            }

            val response =
                patchItem(
                    client,
                    issueToken(config, ownerId),
                    "/api/venue/menu/items/${fixture.firstItem.id}/availability?venueId=$venueId",
                    """{"isAvailable":false}""",
                )

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
            assertFalse(response.bodyAsText().contains(MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION))
            val saved =
                menuRepository(jdbcUrl).getMenu(venueId)
                    .flatMap { it.items }
                    .single { it.id == fixture.firstItem.id }
            assertTrue(saved.isAvailable)
            assertEquals(fixture.firstItem.name, saved.name)
            assertEquals(fixture.firstItem.priceMinor, saved.priceMinor)
            assertTrue(menuItemAvailabilityChangeAudits(jdbcUrl).isEmpty())
            assertTrue(
                auditActions(jdbcUrl).none { it == MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION },
            )
        }

    @Test
    fun `owner and manager compound item availability audit while staff compound availability is denied`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-item-availability-compound-rbac")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 79_301L
            val managerId = 79_302L
            val staffId = 79_303L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, managerId, "MANAGER", venueId)
            seedVenueWithRole(jdbcUrl, staffId, "STAFF", venueId)
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)

            val ownerResponse =
                patchItem(
                    client,
                    issueToken(config, ownerId),
                    "/api/venue/menu/items/${fixture.firstItem.id}" +
                        "?venueId=$venueId&actorUserId=999999&source=TELEGRAM_BOT",
                    """
                    {
                      "isAvailable": false,
                      "actorUserId": 999999,
                      "source": "TELEGRAM_BOT"
                    }
                    """.trimIndent(),
                )
            val managerResponse =
                patchItem(
                    client,
                    issueToken(config, managerId),
                    "/api/venue/menu/items/${fixture.secondItem.id}?venueId=$venueId",
                    """
                    {
                      "name": "Manager metadata and availability",
                      "priceMinor": 250,
                      "isAvailable": false
                    }
                    """.trimIndent(),
                )
            val staffResponse =
                patchItem(
                    client,
                    issueToken(config, staffId),
                    "/api/venue/menu/items/${fixture.firstItem.id}?venueId=$venueId",
                    """{"isAvailable":true}""",
                )

            assertEquals(HttpStatusCode.OK, ownerResponse.status)
            assertFalse(json.decodeFromString(VenueMenuItemDto.serializer(), ownerResponse.bodyAsText()).isAvailable)
            assertEquals(HttpStatusCode.OK, managerResponse.status)
            val managerItem =
                json.decodeFromString(VenueMenuItemDto.serializer(), managerResponse.bodyAsText())
            assertEquals("Manager metadata and availability", managerItem.name)
            assertEquals(250L, managerItem.priceMinor)
            assertFalse(managerItem.isAvailable)
            assertEquals(HttpStatusCode.Forbidden, staffResponse.status)
            assertApiErrorEnvelope(staffResponse, ApiErrorCodes.FORBIDDEN)

            val audits = menuItemAvailabilityChangeAudits(jdbcUrl)
            assertEquals(listOf(ownerId, managerId), audits.map { it.actorUserId })
            assertEquals(
                listOf(fixture.firstItem.id, fixture.secondItem.id),
                audits.map { it.entityId },
            )
            assertEquals(
                listOf(
                    MENU_CATEGORY_CREATED_AUDIT_ACTION,
                    MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION,
                    MENU_ITEM_RENAMED_AUDIT_ACTION,
                    MENU_ITEM_PRICE_CHANGED_AUDIT_ACTION,
                    MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION,
                ),
                auditActions(jdbcUrl),
            )
            audits.forEach { audit ->
                assertEquals("menu_item", audit.entityType)
                assertEquals(
                    setOf("venueId", "itemId", "oldIsAvailable", "newIsAvailable", "source"),
                    audit.payload.keys,
                )
                assertEquals("true", audit.payload.getValue("oldIsAvailable").jsonPrimitive.content)
                assertEquals("false", audit.payload.getValue("newIsAvailable").jsonPrimitive.content)
                assertEquals("VENUE_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
                assertFalse(audit.payload.toString().contains("999999"))
                assertFalse(audit.payload.toString().contains("TELEGRAM_BOT"))
                assertFalse(audit.payload.toString().contains("price", ignoreCase = true))
                assertFalse(audit.payload.toString().contains("name", ignoreCase = true))
            }
            val savedItems =
                menuRepository(jdbcUrl).getMenu(venueId)
                    .flatMap { it.items }
                    .associateBy { it.id }
            assertFalse(savedItems.getValue(fixture.firstItem.id).isAvailable)
            assertEquals("Manager metadata and availability", savedItems.getValue(fixture.secondItem.id).name)
        }

    @Test
    fun `owner and manager compound option patch derives actor source and audits changed families`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-option-rename-audit-success")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 80_001L
            val managerId = 80_002L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, managerId, "MANAGER", venueId)
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)

            val ownerResponse =
                client.patch(
                    "/api/venue/menu/options/${fixture.firstOption.id}" +
                        "?venueId=$venueId&actorUserId=999999&source=TELEGRAM_BOT",
                ) {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer ${issueToken(config, ownerId)}")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "name": "Owner renamed option",
                          "priceDeltaMinor": 125,
                          "isAvailable": false,
                          "actorUserId": 999999,
                          "source": "TELEGRAM_BOT",
                          "rawRequest": "private request body"
                        }
                        """.trimIndent(),
                    )
                }
            val managerResponse =
                client.patch("/api/venue/menu/options/${fixture.secondOption.id}?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer ${issueToken(config, managerId)}")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"name":"Manager renamed option","priceDeltaMinor":225}""")
                }

            assertEquals(HttpStatusCode.OK, ownerResponse.status)
            assertEquals(
                setOf("id", "itemId", "name", "priceDeltaMinor", "isAvailable", "sortOrder"),
                json.parseToJsonElement(ownerResponse.bodyAsText()).jsonObject.keys,
            )
            val ownerOption = json.decodeFromString(VenueMenuOptionDto.serializer(), ownerResponse.bodyAsText())
            assertEquals("Owner renamed option", ownerOption.name)
            assertEquals(125L, ownerOption.priceDeltaMinor)
            assertFalse(ownerOption.isAvailable)
            assertEquals(HttpStatusCode.OK, managerResponse.status)
            assertEquals(
                "Manager renamed option",
                json.decodeFromString(VenueMenuOptionDto.serializer(), managerResponse.bodyAsText()).name,
            )
            assertEquals(
                225L,
                json.decodeFromString(VenueMenuOptionDto.serializer(), managerResponse.bodyAsText()).priceDeltaMinor,
            )

            val audits = menuOptionRenameAudits(jdbcUrl)
            assertEquals(2, audits.size)
            assertEquals(listOf(ownerId, managerId), audits.map { it.actorUserId })
            assertEquals(
                listOf(fixture.firstOption.id, fixture.secondOption.id),
                audits.map { it.entityId },
            )
            assertEquals(
                listOf(fixture.firstOption.name, fixture.secondOption.name),
                audits.map { it.payload.getValue("oldName").jsonPrimitive.content },
            )
            assertEquals(
                listOf("Owner renamed option", "Manager renamed option"),
                audits.map { it.payload.getValue("newName").jsonPrimitive.content },
            )
            audits.forEach { audit ->
                assertEquals("menu_item_option", audit.entityType)
                assertEquals(
                    setOf("venueId", "itemId", "optionId", "oldName", "newName", "source"),
                    audit.payload.keys,
                )
                assertEquals("VENUE_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
                assertFalse(audit.payload.toString().contains("999999"))
                assertFalse(audit.payload.toString().contains("TELEGRAM_BOT"))
                assertFalse(audit.payload.toString().contains("price", ignoreCase = true))
                assertFalse(audit.payload.toString().contains("availability", ignoreCase = true))
                assertFalse(audit.payload.toString().contains("private request body"))
            }
            val priceAudits = menuOptionPriceChangeAudits(jdbcUrl)
            assertEquals(2, priceAudits.size)
            assertEquals(listOf(ownerId, managerId), priceAudits.map { it.actorUserId })
            assertEquals(
                listOf(fixture.firstOption.id, fixture.secondOption.id),
                priceAudits.map { it.entityId },
            )
            assertEquals(
                listOf(fixture.firstOption.priceDeltaMinor, fixture.secondOption.priceDeltaMinor),
                priceAudits.map { it.payload.getValue("oldPriceDeltaMinor").jsonPrimitive.content.toLong() },
            )
            assertEquals(
                listOf(125L, 225L),
                priceAudits.map { it.payload.getValue("newPriceDeltaMinor").jsonPrimitive.content.toLong() },
            )
            priceAudits.forEach { audit ->
                assertEquals("menu_item_option", audit.entityType)
                assertEquals(
                    setOf(
                        "venueId",
                        "itemId",
                        "optionId",
                        "oldPriceDeltaMinor",
                        "newPriceDeltaMinor",
                        "source",
                    ),
                    audit.payload.keys,
                )
                assertEquals("VENUE_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
                assertFalse(audit.payload.toString().contains("999999"))
                assertFalse(audit.payload.toString().contains("TELEGRAM_BOT"))
                assertFalse(audit.payload.toString().contains("name", ignoreCase = true))
                assertFalse(audit.payload.toString().contains("availability", ignoreCase = true))
                assertFalse(audit.payload.toString().contains("private request body"))
            }
            val availabilityAudit = menuOptionAvailabilityChangeAudits(jdbcUrl).single()
            assertEquals(ownerId, availabilityAudit.actorUserId)
            assertEquals("menu_item_option", availabilityAudit.entityType)
            assertEquals(fixture.firstOption.id, availabilityAudit.entityId)
            assertEquals(
                setOf("venueId", "itemId", "optionId", "oldIsAvailable", "newIsAvailable", "source"),
                availabilityAudit.payload.keys,
            )
            assertEquals("true", availabilityAudit.payload.getValue("oldIsAvailable").jsonPrimitive.content)
            assertEquals("false", availabilityAudit.payload.getValue("newIsAvailable").jsonPrimitive.content)
            assertEquals("VENUE_MINI_APP", availabilityAudit.payload.getValue("source").jsonPrimitive.content)
            assertFalse(availabilityAudit.payload.toString().contains("999999"))
            assertFalse(availabilityAudit.payload.toString().contains("TELEGRAM_BOT"))
            assertFalse(availabilityAudit.payload.toString().contains("private request body"))
        }

    @Test
    fun `owner manager and staff direct availability derive actor source while denied actors write none`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-option-availability-audit-rbac")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 80_101L
            val managerId = 80_102L
            val staffId = 80_103L
            val foreignManagerId = 80_104L
            val unaffiliatedId = 80_105L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, managerId, "MANAGER", venueId)
            seedVenueWithRole(jdbcUrl, staffId, "STAFF", venueId)
            val foreignVenueId = seedVenueWithRole(jdbcUrl, foreignManagerId, "MANAGER")
            seedUser(jdbcUrl, unaffiliatedId)
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)
            val thirdOption =
                requireNotNull(
                    menuRepository(jdbcUrl).createOption(
                        venueId = venueId,
                        itemId = fixture.firstItem.id,
                        name = "Third private option",
                        priceDeltaMinor = 30,
                        isAvailable = true,
                        actorUserId = ownerId,
                        source = MenuOptionCreateSource.VENUE_MINI_APP,
                    ),
                )
            val actorOptions =
                listOf(
                    ownerId to fixture.firstOption,
                    managerId to fixture.secondOption,
                    staffId to thirdOption,
                )

            actorOptions.forEachIndexed { index, (actorId, option) ->
                val response =
                    patchOption(
                        client,
                        issueToken(config, actorId),
                        "/api/venue/menu/options/${option.id}/availability" +
                            "?venueId=$venueId&actorUserId=999999&source=TELEGRAM_BOT",
                        """
                        {
                          "isAvailable": false,
                          "actorUserId": 999999,
                          "source": "TELEGRAM_BOT",
                          "callbackData": "private callback $index"
                        }
                        """.trimIndent(),
                    )
                assertEquals(HttpStatusCode.OK, response.status)
                assertFalse(json.decodeFromString(VenueMenuOptionDto.serializer(), response.bodyAsText()).isAvailable)
            }
            val repeatedNoOp =
                patchOption(
                    client,
                    issueToken(config, staffId),
                    "/api/venue/menu/options/${thirdOption.id}/availability?venueId=$venueId",
                    """{"isAvailable":false}""",
                )
            assertEquals(HttpStatusCode.OK, repeatedNoOp.status)

            listOf(foreignManagerId, unaffiliatedId).forEach { deniedId ->
                val denied =
                    patchOption(
                        client,
                        issueToken(config, deniedId),
                        "/api/venue/menu/options/${fixture.firstOption.id}/availability?venueId=$venueId",
                        """{"isAvailable":true}""",
                    )
                assertEquals(HttpStatusCode.Forbidden, denied.status)
                assertApiErrorEnvelope(denied, ApiErrorCodes.FORBIDDEN)
            }
            val foreignScope =
                patchOption(
                    client,
                    issueToken(config, foreignManagerId),
                    "/api/venue/menu/options/${fixture.firstOption.id}/availability?venueId=$foreignVenueId",
                    """{"isAvailable":true}""",
                )
            assertEquals(HttpStatusCode.NotFound, foreignScope.status)
            assertApiErrorEnvelope(foreignScope, ApiErrorCodes.NOT_FOUND)

            val audits = menuOptionAvailabilityChangeAudits(jdbcUrl)
            assertEquals(listOf(ownerId, managerId, staffId), audits.map { it.actorUserId })
            assertEquals(actorOptions.map { it.second.id }, audits.map { it.entityId })
            audits.forEach { audit ->
                assertEquals("menu_item_option", audit.entityType)
                assertEquals(
                    setOf("venueId", "itemId", "optionId", "oldIsAvailable", "newIsAvailable", "source"),
                    audit.payload.keys,
                )
                assertEquals("true", audit.payload.getValue("oldIsAvailable").jsonPrimitive.content)
                assertEquals("false", audit.payload.getValue("newIsAvailable").jsonPrimitive.content)
                assertEquals("VENUE_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
                assertFalse(audit.payload.toString().contains("999999"))
                assertFalse(audit.payload.toString().contains("TELEGRAM_BOT"))
                assertFalse(audit.payload.toString().contains("private callback"))
                assertFalse(audit.payload.toString().contains("price", ignoreCase = true))
                assertFalse(audit.payload.toString().contains("name", ignoreCase = true))
            }
        }

    @Test
    fun `direct availability audit failure returns safe error and rolls back row`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-option-availability-audit-rollback")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 80_201L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        ALTER TABLE audit_log
                        ADD CONSTRAINT reject_menu_option_availability_audit
                        CHECK (action <> '$MENU_OPTION_AVAILABILITY_CHANGED_AUDIT_ACTION')
                        """.trimIndent(),
                    )
                }
            }

            val response =
                patchOption(
                    client,
                    issueToken(config, ownerId),
                    "/api/venue/menu/options/${fixture.firstOption.id}/availability?venueId=$venueId",
                    """{"isAvailable":false}""",
                )

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
            assertFalse(response.bodyAsText().contains(MENU_OPTION_AVAILABILITY_CHANGED_AUDIT_ACTION))
            val saved =
                menuRepository(jdbcUrl).getMenu(venueId)
                    .flatMap { it.items }
                    .flatMap { it.options }
                    .single { it.id == fixture.firstOption.id }
            assertEquals(fixture.firstOption, saved)
            assertTrue(menuOptionAvailabilityChangeAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `same price retries and availability only write no additional price or rename audit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-option-rename-audit-noop")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val managerId = 81_001L
            val venueId = seedVenueWithRole(jdbcUrl, managerId, "MANAGER")
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)
            val token = issueToken(config, managerId)
            val url = "/api/venue/menu/options/${fixture.firstOption.id}?venueId=$venueId"

            val sameName = patchOption(client, token, url, """{"name":"${fixture.firstOption.name}"}""")
            val repeatedSameName = patchOption(client, token, url, """{"name":"${fixture.firstOption.name}"}""")
            val priceOnly = patchOption(client, token, url, """{"priceDeltaMinor":321}""")
            val repeatedSamePrice = patchOption(client, token, url, """{"priceDeltaMinor":321}""")
            val secondRepeatedSamePrice = patchOption(client, token, url, """{"priceDeltaMinor":321}""")
            val availabilityOnly = patchOption(client, token, url, """{"isAvailable":false}""")
            val dedicatedAvailability =
                patchOption(
                    client,
                    token,
                    "/api/venue/menu/options/${fixture.firstOption.id}/availability?venueId=$venueId",
                    """{"isAvailable":true}""",
                )
            val repeatedDedicatedAvailability =
                patchOption(
                    client,
                    token,
                    "/api/venue/menu/options/${fixture.firstOption.id}/availability?venueId=$venueId",
                    """{"isAvailable":true}""",
                )

            listOf(
                sameName,
                repeatedSameName,
                priceOnly,
                repeatedSamePrice,
                secondRepeatedSamePrice,
                availabilityOnly,
                dedicatedAvailability,
                repeatedDedicatedAvailability,
            ).forEach {
                assertEquals(HttpStatusCode.OK, it.status)
            }
            val saved =
                menuRepository(jdbcUrl).getMenu(venueId)
                    .flatMap { it.items }
                    .flatMap { it.options }
                    .single { it.id == fixture.firstOption.id }
            assertEquals(fixture.firstOption.name, saved.name)
            assertEquals(321L, saved.priceDeltaMinor)
            assertTrue(saved.isAvailable)
            assertTrue(menuOptionRenameAudits(jdbcUrl).isEmpty())
            val priceAudit = menuOptionPriceChangeAudits(jdbcUrl).single()
            assertEquals(managerId, priceAudit.actorUserId)
            assertEquals(fixture.firstOption.id, priceAudit.entityId)
            assertEquals(
                fixture.firstOption.priceDeltaMinor,
                priceAudit.payload.getValue("oldPriceDeltaMinor").jsonPrimitive.content.toLong(),
            )
            assertEquals(
                321L,
                priceAudit.payload.getValue("newPriceDeltaMinor").jsonPrimitive.content.toLong(),
            )
            val availabilityAudits = menuOptionAvailabilityChangeAudits(jdbcUrl)
            assertEquals(2, availabilityAudits.size)
            assertEquals(
                listOf("false", "true"),
                availabilityAudits.map { it.payload.getValue("newIsAvailable").jsonPrimitive.content },
            )
        }

    @Test
    fun `staff foreign and unaffiliated compound option denials write zero audit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-option-rename-audit-denials")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 82_001L
            val staffId = 82_002L
            val foreignManagerId = 82_003L
            val guestId = 82_004L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, staffId, "STAFF", venueId)
            val foreignVenueId = seedVenueWithRole(jdbcUrl, foreignManagerId, "MANAGER")
            seedUser(jdbcUrl, guestId)
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)

            listOf(staffId, foreignManagerId, guestId).forEach { actorId ->
                val response =
                    patchOption(
                        client,
                        issueToken(config, actorId),
                        "/api/venue/menu/options/${fixture.firstOption.id}?venueId=$venueId",
                        """{"name":"Denied private rename","priceDeltaMinor":999,"isAvailable":false}""",
                    )
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
            }
            val foreignScope =
                patchOption(
                    client,
                    issueToken(config, foreignManagerId),
                    "/api/venue/menu/options/${fixture.firstOption.id}?venueId=$foreignVenueId",
                    """{"name":"Foreign scope rename","priceDeltaMinor":999}""",
                )
            assertEquals(HttpStatusCode.NotFound, foreignScope.status)
            assertApiErrorEnvelope(foreignScope, ApiErrorCodes.NOT_FOUND)

            val saved =
                menuRepository(jdbcUrl).getMenu(venueId)
                    .flatMap { it.items }
                    .flatMap { it.options }
                    .single { it.id == fixture.firstOption.id }
            assertEquals(fixture.firstOption.name, saved.name)
            assertTrue(menuOptionRenameAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionPriceChangeAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionAvailabilityChangeAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `canonical rename collision preserves option and writes zero audit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-option-rename-audit-collision")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 83_001L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            val repository = menuRepository(jdbcUrl)
            val category =
                repository.createCategory(
                    venueId,
                    "Кальянное меню",
                    ownerId,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                )
            repository.updateCategory(
                venueId = venueId,
                categoryId = category.id,
                name = null,
                categoryType = MenuSemanticType.HOOKAH,
                actorUserId = ownerId,
                source = MenuItemAvailabilitySource.VENUE_MINI_APP,
            )
            val item =
                requireNotNull(
                    repository.createItem(
                        venueId = venueId,
                        categoryId = category.id,
                        name = "Hookah",
                        priceMinor = 100_000,
                        currency = "RUB",
                        isAvailable = true,
                        actorUserId = ownerId,
                        source = MenuItemCreateSource.VENUE_MINI_APP,
                    ),
                )
            requireNotNull(
                repository.createOption(
                    venueId,
                    item.id,
                    "Ягодный",
                    100,
                    true,
                    ownerId,
                    MenuOptionCreateSource.VENUE_MINI_APP,
                ),
            )
            val custom =
                requireNotNull(
                    repository.createOption(
                        venueId,
                        item.id,
                        "Авторский",
                        200,
                        true,
                        ownerId,
                        MenuOptionCreateSource.VENUE_MINI_APP,
                    ),
                )

            val response =
                patchOption(
                    client,
                    issueToken(config, ownerId),
                    "/api/venue/menu/options/${custom.id}?venueId=$venueId",
                    """{"name":"ЯГОДНЫЙ","priceDeltaMinor":999,"isAvailable":false}""",
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
            val saved =
                repository.getMenu(venueId).flatMap { it.items }.flatMap { it.options }.single { it.id == custom.id }
            assertEquals(custom, saved)
            assertTrue(menuOptionRenameAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionPriceChangeAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionAvailabilityChangeAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `price audit failure returns safe error and rolls back compound patch and rename audit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("menu-option-rename-audit-rollback")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownerId = 84_001L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            val fixture = createShiftCheckFixture(jdbcUrl, venueId)
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        ALTER TABLE audit_log
                        ADD CONSTRAINT reject_menu_option_price_audit
                        CHECK (action <> '$MENU_OPTION_PRICE_CHANGED_AUDIT_ACTION')
                        """.trimIndent(),
                    )
                }
            }

            val response =
                patchOption(
                    client,
                    issueToken(config, ownerId),
                    "/api/venue/menu/options/${fixture.firstOption.id}?venueId=$venueId",
                    """{"name":"Rollback route name","priceDeltaMinor":999,"isAvailable":false}""",
                )

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
            assertFalse(response.bodyAsText().contains(MENU_OPTION_RENAMED_AUDIT_ACTION))
            assertFalse(response.bodyAsText().contains(MENU_OPTION_PRICE_CHANGED_AUDIT_ACTION))
            val saved =
                menuRepository(jdbcUrl).getMenu(venueId)
                    .flatMap { it.items }
                    .flatMap { it.options }
                    .single { it.id == fixture.firstOption.id }
            assertEquals(fixture.firstOption, saved)
            assertTrue(menuOptionRenameAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionPriceChangeAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionAvailabilityChangeAudits(jdbcUrl).isEmpty())
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
            assertTrue(menuItemAvailabilityChangeAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionAvailabilityChangeAudits(jdbcUrl).isEmpty())
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
            assertTrue(menuItemAvailabilityChangeAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionAvailabilityChangeAudits(jdbcUrl).isEmpty())
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
            assertTrue(menuItemAvailabilityChangeAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionAvailabilityChangeAudits(jdbcUrl).isEmpty())
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(
        jdbcUrl: String,
        platformOwnerId: Long? = null,
    ): MapApplicationConfig {
        val values =
            mutableListOf(
                "app.env" to appEnv,
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to jdbcUrl,
                "db.user" to "sa",
                "db.password" to "",
            )
        platformOwnerId?.let { values += "platform.ownerUserId" to it.toString() }
        return MapApplicationConfig(*values.toTypedArray())
    }

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long = TELEGRAM_USER_ID,
    ): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(userId).token
    }

    private fun seedUser(
        jdbcUrl: String,
        userId: Long,
    ) {
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
        }
    }

    private fun menuRepository(jdbcUrl: String): VenueMenuRepository =
        VenueMenuRepository(
            JdbcDataSource().apply {
                setURL(jdbcUrl)
                user = "sa"
                password = ""
            },
        )

    private fun menuItemDeleteAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_ITEM_DELETED_AUDIT_ACTION)

    private fun menuItemCreateAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_ITEM_CREATED_AUDIT_ACTION)

    private fun menuCategoryDeleteAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_CATEGORY_DELETED_AUDIT_ACTION)

    private fun menuOptionDeleteAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_OPTION_DELETED_AUDIT_ACTION)

    private fun menuOptionCreateAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_OPTION_CREATED_AUDIT_ACTION)

    private fun menuOptionRenameAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_OPTION_RENAMED_AUDIT_ACTION)

    private fun menuOptionPriceChangeAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_OPTION_PRICE_CHANGED_AUDIT_ACTION)

    private fun menuItemAvailabilityChangeAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION)

    private fun menuOptionAvailabilityChangeAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_OPTION_AVAILABILITY_CHANGED_AUDIT_ACTION)

    private suspend fun patchItem(
        client: HttpClient,
        token: String,
        url: String,
        body: String,
    ): HttpResponse =
        client.patch(url) {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody(body)
        }

    private suspend fun postMenuItem(
        client: HttpClient,
        token: String,
        url: String,
        body: String,
    ): HttpResponse =
        client.post(url) {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody(body)
        }

    private suspend fun patchOption(
        client: HttpClient,
        token: String,
        url: String,
        body: String,
    ): HttpResponse =
        client.patch(url) {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody(body)
        }

    private suspend fun postOption(
        client: HttpClient,
        token: String,
        venueId: Long,
        itemId: Long,
        name: String,
    ): HttpResponse =
        client.post("/api/venue/menu/options?venueId=$venueId") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody(
                """
                {
                  "itemId": $itemId,
                  "name": "$name",
                  "priceDeltaMinor": 0,
                  "isAvailable": true
                }
                """.trimIndent(),
            )
        }

    private suspend fun createHookahMenuItem(
        jdbcUrl: String,
        venueId: Long,
        name: String,
    ): VenueMenuItem {
        val repository = menuRepository(jdbcUrl)
        val category =
            repository.createCategory(
                venueId = venueId,
                name = "$name category",
                actorUserId = TELEGRAM_USER_ID,
                source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                categoryType = MenuSemanticType.HOOKAH,
            )
        return requireNotNull(
            repository.createItem(
                venueId = venueId,
                categoryId = category.id,
                name = name,
                priceMinor = 100,
                currency = "RUB",
                isAvailable = true,
                actorUserId = TELEGRAM_USER_ID,
                source = MenuItemCreateSource.VENUE_MINI_APP,
            ),
        )
    }

    private fun rejectMenuOptionCreateAudits(
        jdbcUrl: String,
        constraintName: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    ALTER TABLE audit_log
                    ADD CONSTRAINT $constraintName
                    CHECK (action <> '$MENU_OPTION_CREATED_AUDIT_ACTION')
                    """.trimIndent(),
                )
            }
        }
    }

    private fun rejectMenuItemCreateAuditsForActor(
        jdbcUrl: String,
        actorUserId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    ALTER TABLE audit_log
                    ADD CONSTRAINT reject_menu_item_create_audit_for_actor
                    CHECK (
                        action <> '$MENU_ITEM_CREATED_AUDIT_ACTION'
                        OR actor_user_id <> $actorUserId
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    private fun clearSetupItemCreateAudits(
        jdbcUrl: String,
        itemIds: List<Long>,
    ) {
        if (itemIds.isEmpty()) {
            return
        }
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val placeholders = itemIds.joinToString(",") { "?" }
            connection.prepareStatement(
                """
                DELETE FROM audit_log
                WHERE action = ?
                  AND entity_type = 'menu_item'
                  AND entity_id IN ($placeholders)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, MENU_ITEM_CREATED_AUDIT_ACTION)
                itemIds.forEachIndexed { index, itemId -> statement.setLong(index + 2, itemId) }
                statement.executeUpdate()
            }
        }
    }

    private fun clearSetupOptionCreateAudits(
        jdbcUrl: String,
        optionIds: List<Long>,
    ) {
        check(optionIds.size == 2)
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                DELETE FROM audit_log
                WHERE action = ?
                  AND entity_type = 'menu_item_option'
                  AND entity_id IN (?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, MENU_OPTION_CREATED_AUDIT_ACTION)
                statement.setLong(2, optionIds[0])
                statement.setLong(3, optionIds[1])
                statement.executeUpdate()
            }
        }
    }

    private fun menuDeleteAudits(
        jdbcUrl: String,
        action: String,
    ): List<MenuDeleteAuditRow> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT actor_user_id, entity_type, entity_id, payload_json
                FROM audit_log
                WHERE action = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, action)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                MenuDeleteAuditRow(
                                    actorUserId = rs.getLong("actor_user_id"),
                                    entityType = rs.getString("entity_type"),
                                    entityId = rs.getLong("entity_id"),
                                    payload = json.parseToJsonElement(rs.getString("payload_json")).jsonObject,
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun auditActions(jdbcUrl: String): List<String> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT action
                FROM audit_log
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.getString("action"))
                        }
                    }
                }
            }
        }

    private fun seedFixedRewardRule(
        jdbcUrl: String,
        venueId: Long,
        rewardItemId: Long,
        actorUserId: Long,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.autoCommit = false
            try {
                val promotionId =
                    connection.prepareStatement(
                        """
                        INSERT INTO venue_promotions (
                            venue_id,
                            title,
                            description,
                            status,
                            template_type,
                            created_by_user_id
                        )
                        VALUES (?, 'Private promotion title', 'Private promotion config', 'DRAFT',
                            'GIFT_WITH_ITEM', ?)
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, actorUserId)
                        statement.executeUpdate()
                        statement.generatedKeys.use { keys ->
                            check(keys.next())
                            keys.getLong(1)
                        }
                    }
                val ruleId =
                    connection.prepareStatement(
                        """
                        INSERT INTO promotion_rules (
                            promotion_id,
                            venue_id,
                            rule_type,
                            target_type,
                            target_value,
                            executable_target_type,
                            discount_percent,
                            status,
                            priority,
                            stackable,
                            max_applications_per_item,
                            version,
                            created_by_user_id
                        )
                        VALUES (?, ?, 'GIFT_WITH_ITEM', 'CATEGORY_TYPE', 'HOOKAH',
                            'MENU_ITEM', NULL, 'DRAFT', 100, FALSE, 1, 1, ?)
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.setLong(1, promotionId)
                        statement.setLong(2, venueId)
                        statement.setLong(3, actorUserId)
                        statement.executeUpdate()
                        statement.generatedKeys.use { keys ->
                            check(keys.next())
                            keys.getLong(1)
                        }
                    }
                connection.prepareStatement(
                    """
                    INSERT INTO promotion_rule_rewards (
                        rule_id,
                        reward_menu_item_id,
                        reward_mode,
                        reward_qty,
                        max_rewards_per_batch
                    )
                    VALUES (?, ?, 'FIXED_ITEM', 1, 1)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, ruleId)
                    statement.setLong(2, rewardItemId)
                    statement.executeUpdate()
                }
                connection.commit()
                ruleId
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    private fun readFixedRewardState(
        jdbcUrl: String,
        ruleId: Long,
    ): FixedRewardState =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    r.version,
                    r.status,
                    r.updated_at AS rule_updated_at,
                    reward.reward_menu_item_id,
                    reward.reward_mode,
                    reward.updated_at AS reward_updated_at
                FROM promotion_rules r
                JOIN promotion_rule_rewards reward ON reward.rule_id = r.id
                WHERE r.id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, ruleId)
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    FixedRewardState(
                        version = rs.getInt("version"),
                        status = rs.getString("status"),
                        ruleUpdatedAt = rs.getObject("rule_updated_at").toString(),
                        rewardMenuItemId = rs.getLong("reward_menu_item_id"),
                        rewardMode = rs.getString("reward_mode"),
                        rewardUpdatedAt = rs.getObject("reward_updated_at").toString(),
                    )
                }
            }
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
        val category =
            repository.createCategory(
                venueId,
                "Shift check",
                TELEGRAM_USER_ID,
                MenuItemAvailabilitySource.VENUE_MINI_APP,
            )
        val firstItem =
            requireNotNull(
                repository.createItem(
                    venueId = venueId,
                    categoryId = category.id,
                    name = "First private item name",
                    priceMinor = 100,
                    currency = "RUB",
                    isAvailable = true,
                    actorUserId = TELEGRAM_USER_ID,
                    source = MenuItemCreateSource.VENUE_MINI_APP,
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
                    actorUserId = TELEGRAM_USER_ID,
                    source = MenuItemCreateSource.VENUE_MINI_APP,
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
                    actorUserId = TELEGRAM_USER_ID,
                    source = MenuOptionCreateSource.VENUE_MINI_APP,
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
                    actorUserId = TELEGRAM_USER_ID,
                    source = MenuOptionCreateSource.VENUE_MINI_APP,
                ),
            )
        clearSetupOptionCreateAudits(jdbcUrl, listOf(firstOption.id, secondOption.id))
        clearSetupItemCreateAudits(jdbcUrl, listOf(firstItem.id, secondItem.id))
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

    private data class MenuDeleteAuditRow(
        val actorUserId: Long,
        val entityType: String,
        val entityId: Long,
        val payload: JsonObject,
    )

    private data class FixedRewardState(
        val version: Int,
        val status: String,
        val ruleUpdatedAt: String,
        val rewardMenuItemId: Long,
        val rewardMode: String,
        val rewardUpdatedAt: String,
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
