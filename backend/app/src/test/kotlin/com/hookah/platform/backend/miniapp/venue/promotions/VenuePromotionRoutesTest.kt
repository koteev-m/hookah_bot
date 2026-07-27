package com.hookah.platform.backend.miniapp.venue.promotions

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.VenueResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VenuePromotionRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `owner manages informational promotion for own venue`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-promotion-owner")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, OWNER_ID, "OWNER")
            val token = issueToken(config, OWNER_ID)
            val createResponse =
                client.post("/api/venue/$venueId/promotions") {
                    authenticated(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "title": " Счастливые часы ",
                          "description": " Информационное предложение ",
                          "terms": " До закрытия ",
                          "startsAt": "2030-05-10T18:00",
                          "endsAt": "2030-05-10T22:00"
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, createResponse.status)
            val created =
                json.decodeFromString(
                    VenuePromotionResponse.serializer(),
                    createResponse.bodyAsText(),
                ).promotion
            assertEquals("Счастливые часы", created.title)
            assertEquals("Информационное предложение", created.description)
            assertEquals("До закрытия", created.terms)
            assertEquals("DRAFT", created.status)

            val listResponse =
                client.get("/api/venue/$venueId/promotions") {
                    authenticated(token)
                }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val list = json.decodeFromString(VenuePromotionListResponse.serializer(), listResponse.bodyAsText())
            assertEquals(venueId, list.venueId)
            assertTrue(list.timezone.isNotBlank())
            assertEquals(listOf(created.id), list.items.map { it.id })

            val updateResponse =
                client.put("/api/venue/$venueId/promotions/${created.id}") {
                    authenticated(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "title": "Вечерняя акция",
                          "description": "Обновлённое описание",
                          "terms": null,
                          "startsAt": "2030-05-10T19:00:00Z",
                          "endsAt": "2030-05-10T23:00:00Z"
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, updateResponse.status)
            val updated =
                json.decodeFromString(
                    VenuePromotionResponse.serializer(),
                    updateResponse.bodyAsText(),
                ).promotion
            assertEquals("Вечерняя акция", updated.title)
            assertNull(updated.terms)

            val activateResponse =
                client.post("/api/venue/$venueId/promotions/${created.id}/status") {
                    authenticated(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"status":"ACTIVE"}""")
                }
            assertEquals(HttpStatusCode.OK, activateResponse.status)
            assertEquals(
                "ACTIVE",
                json.decodeFromString(
                    VenuePromotionResponse.serializer(),
                    activateResponse.bodyAsText(),
                ).promotion.status,
            )

            val pauseResponse =
                client.post("/api/venue/$venueId/promotions/${created.id}/status") {
                    authenticated(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"status":"PAUSED"}""")
                }
            assertEquals(HttpStatusCode.OK, pauseResponse.status)

            val archiveResponse =
                client.delete("/api/venue/$venueId/promotions/${created.id}") {
                    authenticated(token)
                }
            assertEquals(HttpStatusCode.OK, archiveResponse.status)
            assertEquals(
                "ARCHIVED",
                json.decodeFromString(
                    VenuePromotionResponse.serializer(),
                    archiveResponse.bodyAsText(),
                ).promotion.status,
            )

            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.prepareStatement(
                    "SELECT created_by_user_id, template_type FROM venue_promotions WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, created.id)
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals(OWNER_ID, rs.getLong("created_by_user_id"))
                        assertEquals("TEXT_ONLY", rs.getString("template_type"))
                    }
                }
            }
        }

    @Test
    fun `manager can manage promotions while staff and foreign venue user are denied`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-promotion-rbac")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, MANAGER_ID, "MANAGER")
            val foreignVenueId = seedVenueMembership(jdbcUrl, FOREIGN_ID, "OWNER")
            val categoryId = insertMenuCategory(jdbcUrl, venueId, "Кальяны")
            val itemId = insertMenuItem(jdbcUrl, venueId, categoryId, "Кальян")
            val managerToken = issueToken(config, MANAGER_ID)
            val createResponse =
                client.post("/api/venue/$venueId/promotions") {
                    authenticated(managerToken)
                    contentType(ContentType.Application.Json)
                    setBody(happyHoursBody(menuItemId = itemId))
                }
            assertEquals(HttpStatusCode.OK, createResponse.status)
            val created =
                json.decodeFromString(
                    VenuePromotionResponse.serializer(),
                    createResponse.bodyAsText(),
                ).promotion
            val promotionId = created.id
            assertEquals("HAPPY_HOURS_PERCENT", created.templateType)
            assertEquals(1, assertNotNull(created.rule).version)

            val updateResponse =
                client.put("/api/venue/$venueId/promotions/$promotionId") {
                    authenticated(managerToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        happyHoursBody(
                            title = "Менеджер обновил",
                            menuCategoryId = categoryId,
                            discountPercent = 35,
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, updateResponse.status)
            val updated =
                json.decodeFromString(
                    VenuePromotionResponse.serializer(),
                    updateResponse.bodyAsText(),
                ).promotion
            assertEquals("Менеджер обновил", updated.title)
            assertEquals(2, assertNotNull(updated.rule).version)
            assertEquals("MENU_CATEGORY", updated.rule?.target?.type)
            assertEquals(categoryId, updated.rule?.target?.menuCategoryId)

            val archiveResponse =
                client.delete("/api/venue/$venueId/promotions/$promotionId") {
                    authenticated(managerToken)
                }
            assertEquals(HttpStatusCode.OK, archiveResponse.status)

            updateMembershipRole(jdbcUrl, venueId, MANAGER_ID, "STAFF")
            val staffResponse =
                client.get("/api/venue/$venueId/promotions") {
                    authenticated(managerToken)
                }
            assertEquals(HttpStatusCode.Forbidden, staffResponse.status)
            assertApiErrorEnvelope(staffResponse, ApiErrorCodes.FORBIDDEN)

            val staffCreateResponse =
                client.post("/api/venue/$venueId/promotions") {
                    authenticated(managerToken)
                    contentType(ContentType.Application.Json)
                    setBody(happyHoursBody(menuItemId = itemId))
                }
            assertEquals(HttpStatusCode.Forbidden, staffCreateResponse.status)
            assertApiErrorEnvelope(staffCreateResponse, ApiErrorCodes.FORBIDDEN)

            val staffUpdateResponse =
                client.put("/api/venue/$venueId/promotions/$promotionId") {
                    authenticated(managerToken)
                    contentType(ContentType.Application.Json)
                    setBody(happyHoursBody(menuItemId = itemId))
                }
            assertEquals(HttpStatusCode.Forbidden, staffUpdateResponse.status)
            assertApiErrorEnvelope(staffUpdateResponse, ApiErrorCodes.FORBIDDEN)

            val foreignResponse =
                client.get("/api/venue/$venueId/promotions") {
                    authenticated(issueToken(config, FOREIGN_ID))
                }
            assertEquals(HttpStatusCode.Forbidden, foreignResponse.status)
            assertApiErrorEnvelope(foreignResponse, ApiErrorCodes.FORBIDDEN)

            val foreignCreateResponse =
                client.post("/api/venue/$venueId/promotions") {
                    authenticated(issueToken(config, FOREIGN_ID))
                    contentType(ContentType.Application.Json)
                    setBody(happyHoursBody(menuItemId = itemId))
                }
            assertEquals(HttpStatusCode.Forbidden, foreignCreateResponse.status)
            assertApiErrorEnvelope(foreignCreateResponse, ApiErrorCodes.FORBIDDEN)

            val crossVenuePromotionResponse =
                client.get("/api/venue/$foreignVenueId/promotions") {
                    authenticated(managerToken)
                }
            assertEquals(HttpStatusCode.Forbidden, crossVenuePromotionResponse.status)
        }

    @Test
    fun `promotion validation rejects blank and invalid period`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-promotion-validation")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, OWNER_ID, "OWNER")
            val token = issueToken(config, OWNER_ID)

            val blankResponse =
                client.post("/api/venue/$venueId/promotions") {
                    authenticated(token)
                    contentType(ContentType.Application.Json)
                    setBody(validCreateBody(title = " ", description = " "))
                }
            assertEquals(HttpStatusCode.BadRequest, blankResponse.status)
            assertApiErrorEnvelope(blankResponse, ApiErrorCodes.INVALID_INPUT)

            val invalidPeriodResponse =
                client.post("/api/venue/$venueId/promotions") {
                    authenticated(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        validCreateBody(
                            startsAt = "2030-05-10T22:00:00Z",
                            endsAt = "2030-05-10T22:00:00Z",
                        ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, invalidPeriodResponse.status)
            assertApiErrorEnvelope(invalidPeriodResponse, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `invalid happy hours target is rejected without orphan promotion`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-promotion-invalid-target")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, OWNER_ID, "OWNER")
            val foreignVenueId = seedVenueMembership(jdbcUrl, FOREIGN_ID, "OWNER")
            val foreignCategoryId = insertMenuCategory(jdbcUrl, foreignVenueId, "Чужая категория")
            val foreignItemId =
                insertMenuItem(
                    jdbcUrl = jdbcUrl,
                    venueId = foreignVenueId,
                    categoryId = foreignCategoryId,
                    name = "Чужая позиция",
                )
            val token = issueToken(config, OWNER_ID)

            val response =
                client.post("/api/venue/$venueId/promotions") {
                    authenticated(token)
                    contentType(ContentType.Application.Json)
                    setBody(happyHoursBody(menuItemId = foreignItemId))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)

            val listResponse =
                client.get("/api/venue/$venueId/promotions") {
                    authenticated(token)
                }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            assertTrue(
                json.decodeFromString(
                    VenuePromotionListResponse.serializer(),
                    listResponse.bodyAsText(),
                ).items.isEmpty(),
            )
        }

    @Test
    fun `invalid happy hours update rolls back parent and rule changes`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-promotion-update-rollback")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, OWNER_ID, "OWNER")
            val categoryId = insertMenuCategory(jdbcUrl, venueId, "Кальяны")
            val itemId = insertMenuItem(jdbcUrl, venueId, categoryId, "Кальян")
            val foreignVenueId = seedVenueMembership(jdbcUrl, FOREIGN_ID, "OWNER")
            val foreignCategoryId = insertMenuCategory(jdbcUrl, foreignVenueId, "Чужая категория")
            val foreignItemId = insertMenuItem(jdbcUrl, foreignVenueId, foreignCategoryId, "Чужая позиция")
            val token = issueToken(config, OWNER_ID)

            val createResponse =
                client.post("/api/venue/$venueId/promotions") {
                    authenticated(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        happyHoursBody(
                            title = "Исходная акция",
                            menuItemId = itemId,
                            discountPercent = 50,
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, createResponse.status)
            val promotionId =
                json.decodeFromString(
                    VenuePromotionResponse.serializer(),
                    createResponse.bodyAsText(),
                ).promotion.id

            val updateResponse =
                client.put("/api/venue/$venueId/promotions/$promotionId") {
                    authenticated(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        happyHoursBody(
                            title = "Не должна сохраниться",
                            menuItemId = foreignItemId,
                            discountPercent = 25,
                            windows =
                                """
                                [
                                  {"weekday":6,"startLocal":"14:00","endLocal":"17:00"}
                                ]
                                """.trimIndent(),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, updateResponse.status)
            assertApiErrorEnvelope(updateResponse, ApiErrorCodes.INVALID_INPUT)

            val listResponse =
                client.get("/api/venue/$venueId/promotions") {
                    authenticated(token)
                }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val persisted =
                json.decodeFromString(
                    VenuePromotionListResponse.serializer(),
                    listResponse.bodyAsText(),
                ).items.single()
            assertEquals("Исходная акция", persisted.title)
            val persistedRule = assertNotNull(persisted.rule)
            assertEquals(1, persistedRule.version)
            assertEquals(50, persistedRule.discountPercent)
            assertEquals("MENU_ITEM", persistedRule.target?.type)
            assertEquals(itemId, persistedRule.target?.menuItemId)
            assertEquals(
                listOf(
                    VenuePromotionWeekdayWindowDto(weekday = 1, startLocal = "12:00", endLocal = "18:00"),
                    VenuePromotionWeekdayWindowDto(weekday = 5, startLocal = "12:00", endLocal = "16:00"),
                ),
                persistedRule.windows,
            )
        }

    @Test
    fun `owner creates updates and activates happy hours promotion`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-promotion-happy-hours")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, OWNER_ID, "OWNER")
            val categoryId = insertMenuCategory(jdbcUrl, venueId, "Кальяны")
            val itemId = insertMenuItem(jdbcUrl, venueId, categoryId, "Кальян классический")
            val token = issueToken(config, OWNER_ID)

            val createResponse =
                client.post("/api/venue/$venueId/promotions") {
                    authenticated(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        happyHoursBody(
                            title = "Счастливые часы",
                            menuItemId = itemId,
                            discountPercent = 50,
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, createResponse.status)
            val created =
                json.decodeFromString(
                    VenuePromotionResponse.serializer(),
                    createResponse.bodyAsText(),
                ).promotion
            assertEquals("DRAFT", created.status)
            assertEquals("HAPPY_HOURS_PERCENT", created.templateType)
            val createdRule = assertNotNull(created.rule)
            assertEquals(1, createdRule.version)
            assertEquals(50, createdRule.discountPercent)
            assertEquals(
                listOf(
                    VenuePromotionWeekdayWindowDto(weekday = 1, startLocal = "12:00", endLocal = "18:00"),
                    VenuePromotionWeekdayWindowDto(weekday = 5, startLocal = "12:00", endLocal = "16:00"),
                ),
                createdRule.windows,
            )
            assertEquals("MENU_ITEM", createdRule.target?.type)
            assertEquals(itemId, createdRule.target?.menuItemId)
            assertTrue(createdRule.readyForActivation)

            val listResponse =
                client.get("/api/venue/$venueId/promotions") {
                    authenticated(token)
                }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val list =
                json.decodeFromString(
                    VenuePromotionListResponse.serializer(),
                    listResponse.bodyAsText(),
                )
            assertEquals(listOf(created.id), list.items.map { it.id })
            assertEquals(listOf(categoryId), list.menuCategories.map { it.id })
            assertEquals(listOf(itemId), list.menuItems.map { it.id })

            val updateResponse =
                client.put("/api/venue/$venueId/promotions/${created.id}") {
                    authenticated(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        happyHoursBody(
                            title = "Счастливые часы выходного дня",
                            menuCategoryId = categoryId,
                            discountPercent = 25,
                            windows =
                                """
                                [
                                  {"weekday":6,"startLocal":"14:00","endLocal":"17:00"},
                                  {"weekday":7,"startLocal":"14:00","endLocal":"17:00"}
                                ]
                                """.trimIndent(),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, updateResponse.status)
            val updated =
                json.decodeFromString(
                    VenuePromotionResponse.serializer(),
                    updateResponse.bodyAsText(),
                ).promotion
            assertEquals("Счастливые часы выходного дня", updated.title)
            val updatedRule = assertNotNull(updated.rule)
            assertEquals(2, updatedRule.version)
            assertEquals(25, updatedRule.discountPercent)
            assertEquals("MENU_CATEGORY", updatedRule.target?.type)
            assertEquals(categoryId, updatedRule.target?.menuCategoryId)
            assertTrue(updatedRule.readyForActivation)

            val activateResponse =
                client.post("/api/venue/$venueId/promotions/${created.id}/status") {
                    authenticated(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"status":"ACTIVE"}""")
                }
            assertEquals(HttpStatusCode.OK, activateResponse.status)
            val activated =
                json.decodeFromString(
                    VenuePromotionResponse.serializer(),
                    activateResponse.bodyAsText(),
                ).promotion
            assertEquals("ACTIVE", activated.status)
            assertEquals("HAPPY_HOURS_PERCENT", activated.templateType)
            assertTrue(assertNotNull(activated.rule).readyForActivation)
        }

    @Test
    fun `incomplete happy hours promotion cannot activate and remains draft`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-promotion-incomplete-happy-hours")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, OWNER_ID, "OWNER")
            val token = issueToken(config, OWNER_ID)
            val now = Instant.now()
            val promotionId =
                insertPromotion(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    title = "Неполная акция",
                    status = "DRAFT",
                    startsAt = now.minusSeconds(3_600),
                    endsAt = now.plusSeconds(3_600),
                    templateType = "HAPPY_HOURS_PERCENT",
                )

            val listBeforeActivation =
                client.get("/api/venue/$venueId/promotions") {
                    authenticated(token)
                }
            assertEquals(HttpStatusCode.OK, listBeforeActivation.status)
            val incomplete =
                json.decodeFromString(
                    VenuePromotionListResponse.serializer(),
                    listBeforeActivation.bodyAsText(),
                ).items.single()
            assertEquals(promotionId, incomplete.id)
            assertEquals("HAPPY_HOURS_PERCENT", incomplete.templateType)
            assertEquals("DRAFT", incomplete.status)
            assertNull(incomplete.rule)

            val statusResponse =
                client.post("/api/venue/$venueId/promotions/$promotionId/status") {
                    authenticated(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"status":"ACTIVE"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, statusResponse.status)
            assertApiErrorEnvelope(statusResponse, ApiErrorCodes.INVALID_INPUT)

            val listAfterActivation =
                client.get("/api/venue/$venueId/promotions") {
                    authenticated(token)
                }
            assertEquals(HttpStatusCode.OK, listAfterActivation.status)
            assertEquals(
                "DRAFT",
                json.decodeFromString(
                    VenuePromotionListResponse.serializer(),
                    listAfterActivation.bodyAsText(),
                ).items.single().status,
            )
        }

    @Test
    fun `guest venue detail exposes only current active promotions for available venue`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-venue-promotions")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenueMembership(jdbcUrl, OWNER_ID, "OWNER")
            val hiddenVenueId =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    insertVenue(connection, "Hidden", VenueStatus.HIDDEN)
                }
            val blockedVenueId =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    insertVenue(connection, "Blocked", VenueStatus.PUBLISHED)
                }
            seedSubscription(jdbcUrl, blockedVenueId, "SUSPENDED_BY_PLATFORM")
            val now = Instant.now()
            val visibleId =
                insertPromotion(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    title = "Текущая акция",
                    status = "ACTIVE",
                    startsAt = now.minusSeconds(3_600),
                    endsAt = now.plusSeconds(3_600),
                )
            insertPromotion(jdbcUrl, venueId, "Черновик", "DRAFT", now.minusSeconds(3_600), now.plusSeconds(3_600))
            insertPromotion(jdbcUrl, venueId, "Пауза", "PAUSED", now.minusSeconds(3_600), now.plusSeconds(3_600))
            insertPromotion(jdbcUrl, venueId, "Архив", "ARCHIVED", now.minusSeconds(3_600), now.plusSeconds(3_600))
            insertPromotion(jdbcUrl, venueId, "Будущая", "ACTIVE", now.plusSeconds(3_600), now.plusSeconds(7_200))
            insertPromotion(jdbcUrl, venueId, "Истёкшая", "ACTIVE", now.minusSeconds(7_200), now.minusSeconds(3_600))
            insertPromotion(
                jdbcUrl,
                hiddenVenueId,
                "Скрытая",
                "ACTIVE",
                now.minusSeconds(3_600),
                now.plusSeconds(3_600),
            )
            insertPromotion(
                jdbcUrl,
                blockedVenueId,
                "Заблокированная",
                "ACTIVE",
                now.minusSeconds(3_600),
                now.plusSeconds(3_600),
            )
            val token = issueToken(config, OWNER_ID)

            val response =
                client.get("/api/guest/venue/$venueId") {
                    authenticated(token)
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val responseBody = response.bodyAsText()
            val venue = json.decodeFromString(VenueResponse.serializer(), responseBody).venue
            assertEquals(listOf(visibleId), venue.promotions.map { it.id })
            assertEquals("Текущая акция", venue.promotions.single().title)
            assertEquals("TEXT_ONLY", venue.promotions.single().templateType)
            assertFalse(venue.timezone.isNullOrBlank())
            assertFalse(responseBody.contains("createdBy"))
            assertFalse(responseBody.contains("\"templateType\""))

            val hiddenResponse =
                client.get("/api/guest/venue/$hiddenVenueId") {
                    authenticated(token)
                }
            assertEquals(HttpStatusCode.NotFound, hiddenResponse.status)

            val blockedResponse =
                client.get("/api/guest/venue/$blockedVenueId") {
                    authenticated(token)
                }
            assertEquals(HttpStatusCode.NotFound, blockedResponse.status)
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String): MapApplicationConfig =
        MapApplicationConfig(
            "app.env" to "test",
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
        )

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long,
    ): String = SessionTokenService(SessionTokenConfig.from(config, "test")).issueToken(userId).token

    private fun io.ktor.client.request.HttpRequestBuilder.authenticated(token: String) {
        headers { append(HttpHeaders.Authorization, "Bearer $token") }
    }

    private fun seedVenueMembership(
        jdbcUrl: String,
        userId: Long,
        role: String,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            seedUser(connection, userId)
            val venueId = insertVenue(connection, "Venue $userId", VenueStatus.PUBLISHED)
            connection.prepareStatement(
                "INSERT INTO venue_members (venue_id, user_id, role) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
            venueId
        }

    private fun seedUser(
        connection: Connection,
        userId: Long,
    ) {
        connection.prepareStatement(
            """
            MERGE INTO users (telegram_user_id, username, first_name, last_name)
            KEY (telegram_user_id)
            VALUES (?, ?, 'Test', 'User')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, "user$userId")
            statement.executeUpdate()
        }
    }

    private fun insertVenue(
        connection: Connection,
        name: String,
        status: VenueStatus,
    ): Long =
        connection.prepareStatement(
            "INSERT INTO venues (name, city, address, status) VALUES (?, 'Москва', 'Адрес', ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, name)
            statement.setString(2, status.dbValue)
            statement.executeUpdate()
            statement.generatedKeys.use { rs ->
                if (rs.next()) rs.getLong(1) else error("Failed to insert venue")
            }
        }

    private fun insertMenuCategory(
        jdbcUrl: String,
        venueId: Long,
        name: String,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO menu_categories (venue_id, name, sort_order, is_active)
                VALUES (?, ?, 0, true)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, name)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    if (keys.next()) keys.getLong(1) else error("Failed to insert menu category")
                }
            }
        }

    private fun insertMenuItem(
        jdbcUrl: String,
        venueId: Long,
        categoryId: Long,
        name: String,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO menu_items (
                    venue_id, category_id, name, price_minor, currency, is_available
                )
                VALUES (?, ?, ?, 200000, 'RUB', true)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, categoryId)
                statement.setString(3, name)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    if (keys.next()) keys.getLong(1) else error("Failed to insert menu item")
                }
            }
        }

    private fun updateMembershipRole(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        role: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "UPDATE venue_members SET role = ? WHERE venue_id = ? AND user_id = ?",
            ).use { statement ->
                statement.setString(1, role)
                statement.setLong(2, venueId)
                statement.setLong(3, userId)
                statement.executeUpdate()
            }
        }
    }

    private fun insertPromotion(
        jdbcUrl: String,
        venueId: Long,
        title: String,
        status: String,
        startsAt: Instant,
        endsAt: Instant,
        templateType: String = "TEXT_ONLY",
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            seedUser(connection, OWNER_ID)
            connection.prepareStatement(
                """
                INSERT INTO venue_promotions (
                    venue_id, title, description, terms, starts_at, ends_at, status, template_type, created_by_user_id
                )
                VALUES (?, ?, 'Описание', 'Условия', ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, title)
                statement.setTimestamp(3, Timestamp.from(startsAt))
                statement.setTimestamp(4, Timestamp.from(endsAt))
                statement.setString(5, status)
                statement.setString(6, templateType)
                statement.setLong(7, OWNER_ID)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert promotion")
                }
            }
        }

    private fun seedSubscription(
        jdbcUrl: String,
        venueId: Long,
        status: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "INSERT INTO venue_subscriptions (venue_id, status) VALUES (?, ?)",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, status)
                statement.executeUpdate()
            }
        }
    }

    private fun validCreateBody(
        title: String = "Акция",
        description: String = "Описание",
        startsAt: String = "2030-05-10T18:00:00Z",
        endsAt: String = "2030-05-10T22:00:00Z",
    ): String =
        """
        {
          "title": ${json.encodeToString(title)},
          "description": ${json.encodeToString(description)},
          "terms": null,
          "startsAt": ${json.encodeToString(startsAt)},
          "endsAt": ${json.encodeToString(endsAt)}
        }
        """.trimIndent()

    private fun happyHoursBody(
        title: String = "Акция",
        description: String = "Описание",
        startsAt: String = "2030-05-10T18:00:00Z",
        endsAt: String = "2030-05-10T22:00:00Z",
        menuItemId: Long? = null,
        menuCategoryId: Long? = null,
        discountPercent: Int = 50,
        windows: String =
            """
            [
              {"weekday":1,"startLocal":"12:00","endLocal":"18:00"},
              {"weekday":5,"startLocal":"12:00","endLocal":"16:00"}
            ]
            """.trimIndent(),
    ): String {
        require((menuItemId == null) != (menuCategoryId == null))
        val target =
            if (menuItemId != null) {
                """{"type":"MENU_ITEM","menuItemId":$menuItemId}"""
            } else {
                """{"type":"MENU_CATEGORY","menuCategoryId":$menuCategoryId}"""
            }
        return """
            {
              "title": ${json.encodeToString(title)},
              "description": ${json.encodeToString(description)},
              "terms": "Только в указанные часы",
              "startsAt": ${json.encodeToString(startsAt)},
              "endsAt": ${json.encodeToString(endsAt)},
              "templateType": "HAPPY_HOURS_PERCENT",
              "rule": {
                "windows": $windows,
                "target": $target,
                "discountPercent": $discountPercent
              }
            }
            """.trimIndent()
    }

    companion object {
        private const val OWNER_ID = 10101L
        private const val MANAGER_ID = 20202L
        private const val FOREIGN_ID = 30303L
    }
}
