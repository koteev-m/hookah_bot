package com.hookah.platform.backend.miniapp.guest

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
import io.ktor.http.contentType
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
            val canceledVisit = seedBookingVisit(jdbcUrl, venueId, guestOne, "CANCELED")
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

            val filteredDetail =
                client.get("/api/guest/visits/$canceledVisit") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.NotFound, filteredDetail.status)
        }

    @Test
    fun `guest can submit feedback only for own visible completed visit`() =
        testApplication {
            val jdbcUrl =
                "jdbc:h2:mem:guest-visit-feedback-${UUID.randomUUID()};MODE=PostgreSQL;" +
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

            val guestOne = 1101L
            val guestTwo = 1102L
            val venueId = seedVenueAndUsers(jdbcUrl, guestOne, guestTwo)
            val ownVisit = seedVisit(jdbcUrl, venueId, guestOne, "ORDER_CLOSED")
            val foreignVisit = seedVisit(jdbcUrl, venueId, guestTwo, "ORDER_CLOSED")
            val canceledVisit = seedBookingVisit(jdbcUrl, venueId, guestOne, "CANCELED")
            val token = issueToken(config, guestOne)

            val detailBefore =
                client.get("/api/guest/visits/$ownVisit") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, detailBefore.status)
            val feedbackBefore =
                json
                    .parseToJsonElement(detailBefore.bodyAsText())
                    .jsonObject
                    .getValue("visit")
                    .jsonObject
                    .getValue("feedback")
                    .jsonObject
            assertEquals("true", feedbackBefore.getValue("eligible").jsonPrimitive.content)
            assertEquals("false", feedbackBefore.getValue("submitted").jsonPrimitive.content)

            val submit =
                client.post("/api/guest/visits/$ownVisit/feedback") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"rating":5,"tags":["service","taste"],"comment":"Спасибо"}""")
                }
            assertEquals(HttpStatusCode.OK, submit.status)
            val feedback =
                json
                    .parseToJsonElement(submit.bodyAsText())
                    .jsonObject
                    .getValue("feedback")
                    .jsonObject
            assertEquals("true", feedback.getValue("submitted").jsonPrimitive.content)
            assertEquals("5", feedback.getValue("rating").jsonPrimitive.content)
            assertEquals(2, feedback.getValue("tags").jsonArray.size)
            assertEquals("Спасибо", feedback.getValue("comment").jsonPrimitive.content)

            val duplicate =
                client.post("/api/guest/visits/$ownVisit/feedback") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"rating":1,"tags":["price"],"comment":"overwrite"}""")
                }
            assertEquals(HttpStatusCode.OK, duplicate.status)
            val duplicateFeedback =
                json
                    .parseToJsonElement(duplicate.bodyAsText())
                    .jsonObject
                    .getValue("feedback")
                    .jsonObject
            assertEquals("5", duplicateFeedback.getValue("rating").jsonPrimitive.content)
            assertEquals("Спасибо", duplicateFeedback.getValue("comment").jsonPrimitive.content)

            val invalidRating =
                client.post("/api/guest/visits/$ownVisit/feedback") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"rating":0}""")
                }
            assertEquals(HttpStatusCode.BadRequest, invalidRating.status)

            val invalidTag =
                client.post("/api/guest/visits/$ownVisit/feedback") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"rating":4,"tags":["unknown"]}""")
                }
            assertEquals(HttpStatusCode.BadRequest, invalidTag.status)

            val tooManyTags =
                client.post("/api/guest/visits/$ownVisit/feedback") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"rating":4,"tags":["service","hookah_quality","taste","speed","atmosphere","price"]}""")
                }
            assertEquals(HttpStatusCode.BadRequest, tooManyTags.status)

            val foreignSubmit =
                client.post("/api/guest/visits/$foreignVisit/feedback") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"rating":5}""")
                }
            assertEquals(HttpStatusCode.NotFound, foreignSubmit.status)

            val filteredSubmit =
                client.post("/api/guest/visits/$canceledVisit/feedback") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"rating":5}""")
                }
            assertEquals(HttpStatusCode.NotFound, filteredSubmit.status)
        }

    @Test
    fun `guest visit detail returns only owned closed order lines`() =
        testApplication {
            val jdbcUrl =
                "jdbc:h2:mem:guest-visit-detail-${UUID.randomUUID()};MODE=PostgreSQL;" +
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

            val guestOne = 2001L
            val guestTwo = 2002L
            val sharedMember = 2003L
            val venueId = seedVenueAndUsers(jdbcUrl, guestOne, guestTwo, sharedMember)
            val fixture = seedClosedOrderVisitDetails(jdbcUrl, venueId, guestOne, guestTwo, sharedMember)
            val guestOneToken = issueToken(config, guestOne)

            val detailResponse =
                client.get("/api/guest/visits/${fixture.guestOneVisitId}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestOneToken") }
                }
            assertEquals(HttpStatusCode.OK, detailResponse.status)
            val body = detailResponse.bodyAsText()
            val visit = json.parseToJsonElement(body).jsonObject.getValue("visit").jsonObject
            val order = visit.getValue("orders").jsonArray.single().jsonObject
            val item = order.getValue("items").jsonArray.single().jsonObject
            val promotionDiscounts = order.getValue("promotionDiscounts").jsonArray
            val selectedOption = item.getValue("selectedOption").jsonObject

            assertEquals("Owned Hookah", item.getValue("itemName").jsonPrimitive.content)
            assertEquals("Ягодный микс", selectedOption.getValue("name").jsonPrimitive.content)
            assertEquals(250L, selectedOption.getValue("priceDeltaMinor").jsonPrimitive.content.toLong())
            assertEquals("покрепче", item.getValue("preferenceNote").jsonPrimitive.content)
            assertEquals(1250L, item.getValue("priceMinor").jsonPrimitive.content.toLong())
            assertEquals(1250L, order.getValue("totalMinor").jsonPrimitive.content.toLong())
            assertEquals(0, promotionDiscounts.size)
            assertEquals(false, body.contains("Foreign Hookah"))
            assertEquals(false, body.contains("Shared Hookah"))

            val foreignDetail =
                client.get("/api/guest/visits/${fixture.guestTwoVisitId}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestOneToken") }
                }
            assertEquals(HttpStatusCode.NotFound, foreignDetail.status)

            val sharedMemberToken = issueToken(config, sharedMember)
            val sharedMemberDetail =
                client.get("/api/guest/visits/${fixture.guestOneVisitId}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $sharedMemberToken") }
                }
            assertEquals(HttpStatusCode.NotFound, sharedMemberDetail.status)
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

    private fun seedBookingVisit(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        bookingStatus: String,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val bookingId =
                connection.prepareStatement(
                    """
                    INSERT INTO bookings (
                        venue_id,
                        user_id,
                        scheduled_at,
                        party_size,
                        status,
                        display_date,
                        display_number
                    )
                    VALUES (?, ?, ?, 2, ?, ?, 1)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, userId)
                    statement.setTimestamp(3, Timestamp.from(Instant.parse("2030-05-12T18:00:00Z")))
                    statement.setString(4, bookingStatus)
                    statement.setDate(5, Date.valueOf(LocalDate.of(2030, 5, 12)))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO visits (venue_id, user_id, booking_id, source, occurred_at, service_date)
                VALUES (?, ?, ?, 'BOOKING_SEATED', ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setLong(3, bookingId)
                statement.setTimestamp(4, Timestamp.from(Instant.parse("2030-05-12T18:10:00Z")))
                statement.setDate(5, Date.valueOf(LocalDate.of(2030, 5, 12)))
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
                }
            }
        }

    private fun seedClosedOrderVisitDetails(
        jdbcUrl: String,
        venueId: Long,
        guestOne: Long,
        guestTwo: Long,
        sharedMember: Long,
    ): ClosedOrderVisitFixture =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val tableId =
                connection.prepareStatement(
                    """
                    INSERT INTO venue_tables (venue_id, table_number, is_active)
                    VALUES (?, 4, true)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            val categoryId =
                connection.prepareStatement(
                    """
                    INSERT INTO menu_categories (venue_id, name, sort_order, is_active)
                    VALUES (?, 'Hookah', 0, true)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            val ownedItem = insertMenuItem(connection, venueId, categoryId, "Owned Hookah", 1000L)
            val foreignItem = insertMenuItem(connection, venueId, categoryId, "Foreign Hookah", 2000L)
            val sharedItem = insertMenuItem(connection, venueId, categoryId, "Shared Hookah", 3000L)
            val now = Instant.parse("2030-05-12T18:00:00Z")
            val tableSessionId =
                connection.prepareStatement(
                    """
                    INSERT INTO table_sessions (venue_id, table_id, started_at, last_activity_at, expires_at, status)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, tableId)
                    statement.setTimestamp(3, Timestamp.from(now.minusSeconds(3600)))
                    statement.setTimestamp(4, Timestamp.from(now))
                    statement.setTimestamp(5, Timestamp.from(now.plusSeconds(3600)))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            val orderId =
                connection.prepareStatement(
                    """
                    INSERT INTO orders (venue_id, table_id, table_session_id, status, display_number, display_date)
                    VALUES (?, ?, ?, 'CLOSED', 42, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, tableId)
                    statement.setLong(3, tableSessionId)
                    statement.setDate(4, Date.valueOf(LocalDate.of(2030, 5, 12)))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }

            val guestOneTab = insertTab(connection, venueId, tableSessionId, guestOne, "PERSONAL")
            val guestTwoTab = insertTab(connection, venueId, tableSessionId, guestTwo, "PERSONAL")
            val sharedTab = insertTab(connection, venueId, tableSessionId, null, "SHARED")
            insertTabMember(connection, sharedTab, guestOne)
            insertTabMember(connection, sharedTab, sharedMember)

            val guestOneBatch = insertBatch(connection, orderId, guestOneTab, null)
            val guestOneBatchItem = insertBatchItem(connection, guestOneBatch, ownedItem, preferenceNote = "покрепче")
            insertBatchItemOption(connection, guestOneBatchItem, "Ягодный микс", 250L)
            insertGuestBatchIdempotency(
                connection,
                venueId,
                tableSessionId,
                guestOne,
                orderId,
                guestOneBatch,
                "guest-one-detail",
            )

            val guestTwoBatch = insertBatch(connection, orderId, guestTwoTab, null)
            insertBatchItem(connection, guestTwoBatch, foreignItem)
            insertGuestBatchIdempotency(
                connection,
                venueId,
                tableSessionId,
                guestTwo,
                orderId,
                guestTwoBatch,
                "guest-two-detail",
            )

            val sharedBatch = insertBatch(connection, orderId, sharedTab, guestTwo)
            insertBatchItem(connection, sharedBatch, sharedItem)

            ClosedOrderVisitFixture(
                guestOneVisitId =
                    seedVisit(
                        connection = connection,
                        venueId = venueId,
                        userId = guestOne,
                        source = "ORDER_CLOSED",
                        tableSessionId = tableSessionId,
                        orderId = orderId,
                    ),
                guestTwoVisitId =
                    seedVisit(
                        connection = connection,
                        venueId = venueId,
                        userId = guestTwo,
                        source = "ORDER_CLOSED",
                        tableSessionId = tableSessionId,
                        orderId = orderId,
                    ),
            )
        }

    private fun insertMenuItem(
        connection: java.sql.Connection,
        venueId: Long,
        categoryId: Long,
        name: String,
        priceMinor: Long,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO menu_items (venue_id, category_id, name, price_minor, currency, is_available)
            VALUES (?, ?, ?, ?, 'RUB', true)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, categoryId)
            statement.setString(3, name)
            statement.setLong(4, priceMinor)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun insertTab(
        connection: java.sql.Connection,
        venueId: Long,
        tableSessionId: Long,
        ownerUserId: Long?,
        type: String,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO tab (venue_id, table_session_id, type, owner_user_id, status)
            VALUES (?, ?, ?, ?, 'ACTIVE')
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableSessionId)
            statement.setString(3, type)
            if (ownerUserId == null) {
                statement.setNull(4, java.sql.Types.BIGINT)
            } else {
                statement.setLong(4, ownerUserId)
            }
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun insertTabMember(
        connection: java.sql.Connection,
        tabId: Long,
        userId: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO tab_member (tab_id, user_id, role)
            VALUES (?, ?, 'MEMBER')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, tabId)
            statement.setLong(2, userId)
            statement.executeUpdate()
        }
    }

    private fun insertBatch(
        connection: java.sql.Connection,
        orderId: Long,
        tabId: Long,
        authorUserId: Long?,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO order_batches (order_id, tab_id, author_user_id, source, status, guest_comment)
            VALUES (?, ?, ?, 'MINIAPP', 'DELIVERED', NULL)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.setLong(2, tabId)
            if (authorUserId == null) {
                statement.setNull(3, java.sql.Types.BIGINT)
            } else {
                statement.setLong(3, authorUserId)
            }
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun insertBatchItem(
        connection: java.sql.Connection,
        batchId: Long,
        itemId: Long,
        preferenceNote: String? = null,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO order_batch_items (order_batch_id, menu_item_id, qty, is_excluded, item_status, preference_note)
            VALUES (?, ?, 1, false, 'ACTIVE', ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, batchId)
            statement.setLong(2, itemId)
            statement.setString(3, preferenceNote)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun insertBatchItemOption(
        connection: java.sql.Connection,
        batchItemId: Long,
        optionName: String,
        priceDeltaMinor: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO order_batch_item_options (
                order_batch_item_id,
                menu_item_option_id,
                option_name_snapshot,
                price_delta_minor_snapshot
            )
            VALUES (?, NULL, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, batchItemId)
            statement.setString(2, optionName)
            statement.setLong(3, priceDeltaMinor)
            statement.executeUpdate()
        }
    }

    private fun insertGuestBatchIdempotency(
        connection: java.sql.Connection,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
        orderId: Long,
        batchId: Long,
        idempotencyKey: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO guest_batch_idempotency (venue_id, table_session_id, user_id, idempotency_key, order_id, batch_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableSessionId)
            statement.setLong(3, userId)
            statement.setString(4, idempotencyKey)
            statement.setLong(5, orderId)
            statement.setLong(6, batchId)
            statement.executeUpdate()
        }
    }

    private fun seedVisit(
        connection: java.sql.Connection,
        venueId: Long,
        userId: Long,
        source: String,
        tableSessionId: Long,
        orderId: Long,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO visits (venue_id, user_id, table_session_id, order_id, source, occurred_at, service_date)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, userId)
            statement.setLong(3, tableSessionId)
            statement.setLong(4, orderId)
            statement.setString(5, source)
            statement.setTimestamp(6, Timestamp.from(Instant.parse("2030-05-12T18:30:00Z")))
            statement.setDate(7, Date.valueOf(LocalDate.of(2030, 5, 12)))
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private data class ClosedOrderVisitFixture(
        val guestOneVisitId: Long,
        val guestTwoVisitId: Long,
    )

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
