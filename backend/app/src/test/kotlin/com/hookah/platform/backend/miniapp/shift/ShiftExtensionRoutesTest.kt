package com.hookah.platform.backend.miniapp.shift

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.ActiveOrderResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.module
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.telegram.StaffBillUpdateNotifier
import com.hookah.platform.backend.telegram.StaffBillUpdatedNotification
import com.hookah.platform.backend.telegram.StaffChatNotificationResult
import com.hookah.platform.backend.test.assertApiErrorEnvelope
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
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShiftExtensionRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `guest sees extension option and creates one pending request idempotently`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("shift-extension-options")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val fixture = seedActiveTableOrderFixture(jdbcUrl)
            seedShiftExtensionSettings(jdbcUrl, fixture.venueId, priceMinor = 5000)
            seedVenueTimezone(jdbcUrl, fixture.venueId, "Europe/Moscow")

            val token = issueToken(config, GUEST_USER_ID)
            val optionsResponse =
                client.get(
                    "/api/guest/table/extension-options?tableToken=${fixture.tableToken}" +
                        "&tableSessionId=${fixture.tableSessionId}&tabId=${fixture.tabId}",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, optionsResponse.status)
            val options =
                json.decodeFromString(
                    GuestShiftExtensionOptionsResponse.serializer(),
                    optionsResponse.bodyAsText(),
                )
            assertTrue(options.available)
            assertEquals(60, options.durationMinutes)
            assertEquals(5000, options.priceMinor)
            assertEquals(fixture.orderId, options.orderId)
            assertTrue(options.currentOrderableUntil?.contains("+03:00") == true)
            assertTrue(options.currentOrderableUntil?.contains("Z") != true)

            val firstResponse =
                createGuestExtensionRequest(
                    token = token,
                    fixture = fixture,
                    idempotencyKey = "extend-once",
                )
            val secondResponse =
                createGuestExtensionRequest(
                    token = token,
                    fixture = fixture,
                    idempotencyKey = "extend-once",
                )

            assertEquals(HttpStatusCode.OK, firstResponse.status)
            assertEquals(HttpStatusCode.OK, secondResponse.status)
            val first =
                json.decodeFromString(
                    ShiftExtensionRequestResponse.serializer(),
                    firstResponse.bodyAsText(),
                )
            val second =
                json.decodeFromString(
                    ShiftExtensionRequestResponse.serializer(),
                    secondResponse.bodyAsText(),
                )
            assertEquals(first.request.id, second.request.id)
            assertEquals("pending", first.request.status)
            assertTrue(first.request.requestedUntil.contains("+03:00"))
            assertEquals(1, countRows(jdbcUrl, "shift_extension_requests"))
            assertEquals(0, countRows(jdbcUrl, "order_service_charges"))
        }

    @Test
    fun `staff approves fixed-price extension and bill snapshot includes service charge`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("shift-extension-approve")
            val config = buildConfig(jdbcUrl)
            val notifier = CapturingStaffBillUpdateNotifier()

            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(
                        staffBillUpdateNotifier = notifier,
                    ),
                )
            }

            client.get("/health")

            val fixture = seedActiveTableOrderFixture(jdbcUrl, staffRole = "STAFF")
            seedShiftExtensionSettings(jdbcUrl, fixture.venueId, priceMinor = 5000)
            val guestToken = issueToken(config, GUEST_USER_ID)
            val staffToken = issueToken(config, STAFF_USER_ID)
            val request =
                json.decodeFromString(
                    ShiftExtensionRequestResponse.serializer(),
                    createGuestExtensionRequest(
                        token = guestToken,
                        fixture = fixture,
                        idempotencyKey = "approve-extension",
                    ).bodyAsText(),
                ).request
            val expiresAtBeforeApprove = tableSessionExpiresAt(jdbcUrl, fixture.tableSessionId)

            val approveResponse =
                client.post("/api/venue/${fixture.venueId}/shift-extension-requests/${request.id}/approve") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }

            assertEquals(HttpStatusCode.OK, approveResponse.status)
            val approval =
                json.decodeFromString(
                    ShiftExtensionDecisionResponse.serializer(),
                    approveResponse.bodyAsText(),
                )
            assertTrue(approval.applied)
            assertEquals("approved", approval.request.status)
            assertEquals(1, countRows(jdbcUrl, "order_service_charges"))
            val approvedExpiresAt = tableSessionExpiresAt(jdbcUrl, fixture.tableSessionId)
            assertTrue(approvedExpiresAt.isAfter(expiresAtBeforeApprove))

            val activeOrderResponse =
                client.get(
                    "/api/guest/order/active?tableToken=${fixture.tableToken}" +
                        "&tableSessionId=${fixture.tableSessionId}&tabId=${fixture.tabId}",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                }
            assertEquals(HttpStatusCode.OK, activeOrderResponse.status)
            val activeOrder =
                json.decodeFromString(ActiveOrderResponse.serializer(), activeOrderResponse.bodyAsText()).order
            assertNotNull(activeOrder)
            assertEquals(15000, activeOrder.finalPayableTotalMinor)
            assertEquals(1, activeOrder.serviceCharges.size)
            assertEquals("Продление работы на 1 час", activeOrder.serviceCharges.single().label)
            assertTrue(!tableSessionExpiresAt(jdbcUrl, fixture.tableSessionId).isBefore(approvedExpiresAt))

            val notification = notifier.events.single()
            assertEquals(fixture.orderId, notification.orderId)
            assertEquals(15000, notification.bill.finalPayableTotalMinor)
            assertEquals("Продление работы на 1 час", notification.bill.serviceCharges.single().label)
        }

    @Test
    fun `staff rejects extension without bill or session mutation`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("shift-extension-reject")
            val config = buildConfig(jdbcUrl)
            val notifier = CapturingStaffBillUpdateNotifier()

            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(
                        staffBillUpdateNotifier = notifier,
                    ),
                )
            }

            client.get("/health")

            val fixture = seedActiveTableOrderFixture(jdbcUrl, staffRole = "STAFF")
            seedShiftExtensionSettings(jdbcUrl, fixture.venueId, priceMinor = 5000)
            val originalExpiresAt = tableSessionExpiresAt(jdbcUrl, fixture.tableSessionId)
            val guestToken = issueToken(config, GUEST_USER_ID)
            val staffToken = issueToken(config, STAFF_USER_ID)
            val request =
                json.decodeFromString(
                    ShiftExtensionRequestResponse.serializer(),
                    createGuestExtensionRequest(
                        token = guestToken,
                        fixture = fixture,
                        idempotencyKey = "reject-extension",
                    ).bodyAsText(),
                ).request
            val expiresAtBeforeReject = tableSessionExpiresAt(jdbcUrl, fixture.tableSessionId)

            val rejectResponse =
                client.post("/api/venue/${fixture.venueId}/shift-extension-requests/${request.id}/reject") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                    setBody(
                        json.encodeToString(
                            ShiftExtensionDecisionRequest.serializer(),
                            ShiftExtensionDecisionRequest("Гости уходят"),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, rejectResponse.status)
            val rejection =
                json.decodeFromString(
                    ShiftExtensionDecisionResponse.serializer(),
                    rejectResponse.bodyAsText(),
                )
            assertTrue(rejection.applied)
            assertEquals("rejected", rejection.request.status)
            assertEquals("Гости уходят", rejection.request.rejectReason)
            assertEquals(0, countRows(jdbcUrl, "order_service_charges"))
            assertEquals(expiresAtBeforeReject, tableSessionExpiresAt(jdbcUrl, fixture.tableSessionId))
            assertEquals(1, notifier.events.size)
            assertEquals(10000, notifier.events.single().bill.finalPayableTotalMinor)
        }

    @Test
    fun `another guest cannot request extension for чужая tab`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("shift-extension-other-user")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val fixture = seedActiveTableOrderFixture(jdbcUrl)
            seedShiftExtensionSettings(jdbcUrl, fixture.venueId, priceMinor = 5000)
            val otherToken = issueToken(config, OTHER_GUEST_USER_ID)

            val response =
                createGuestExtensionRequest(
                    token = otherToken,
                    fixture = fixture,
                    idempotencyKey = "other-user",
                )

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
            assertEquals(0, countRows(jdbcUrl, "shift_extension_requests"))
        }

    @Test
    fun `closed order cannot be extended`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("shift-extension-closed-order")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val fixture = seedActiveTableOrderFixture(jdbcUrl, orderStatus = "CLOSED", batchStatus = "DELIVERED")
            seedShiftExtensionSettings(jdbcUrl, fixture.venueId, priceMinor = 5000)
            val token = issueToken(config, GUEST_USER_ID)

            val response =
                createGuestExtensionRequest(
                    token = token,
                    fixture = fixture,
                    idempotencyKey = "closed-order",
                )

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
            assertEquals(0, countRows(jdbcUrl, "shift_extension_requests"))
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.createGuestExtensionRequest(
        token: String,
        fixture: ShiftExtensionFixture,
        idempotencyKey: String,
    ) = client.post("/api/guest/table/extension-requests") {
        contentType(ContentType.Application.Json)
        headers { append(HttpHeaders.Authorization, "Bearer $token") }
        setBody(
            json.encodeToString(
                GuestShiftExtensionRequest.serializer(),
                GuestShiftExtensionRequest(
                    tableToken = fixture.tableToken,
                    tableSessionId = fixture.tableSessionId,
                    tabId = fixture.tabId,
                    idempotencyKey = idempotencyKey,
                    comment = "Хотим продлить",
                ),
            ),
        )
    }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String): MapApplicationConfig =
        MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
        )

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long,
    ): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(userId).token
    }

    private fun seedActiveTableOrderFixture(
        jdbcUrl: String,
        staffRole: String = "MANAGER",
        orderStatus: String = "ACTIVE",
        batchStatus: String = "NEW",
    ): ShiftExtensionFixture {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            seedUser(connection, GUEST_USER_ID)
            seedUser(connection, STAFF_USER_ID)
            val venueId = insertVenue(connection)
            insertSubscription(connection, venueId)
            insertVenueMember(connection, venueId, STAFF_USER_ID, staffRole)
            val tableId = insertTable(connection, venueId, tableNumber = 7)
            val tableToken = "shift-token-$venueId"
            insertTableToken(connection, tableId, tableToken)
            val tableSessionId = insertTableSession(connection, venueId, tableId)
            val tabId = insertPersonalTab(connection, venueId, tableSessionId, GUEST_USER_ID)
            val categoryId = insertMenuCategory(connection, venueId)
            val itemId = insertMenuItem(connection, venueId, categoryId, "Кальян", priceMinor = 10000)
            val orderId = insertOrder(connection, venueId, tableId, tableSessionId, orderStatus)
            val batchId = insertBatch(connection, orderId, tabId, GUEST_USER_ID, batchStatus)
            insertBatchItem(connection, batchId, itemId)
            return ShiftExtensionFixture(
                venueId = venueId,
                tableId = tableId,
                tableToken = tableToken,
                tableSessionId = tableSessionId,
                tabId = tabId,
                orderId = orderId,
            )
        }
    }

    private fun seedShiftExtensionSettings(
        jdbcUrl: String,
        venueId: Long,
        priceMinor: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO shift_extension_settings (venue_id, enabled, duration_minutes, price_minor, currency)
                VALUES (?, TRUE, 60, ?, 'RUB')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, priceMinor)
                statement.executeUpdate()
            }
        }
    }

    private fun seedVenueTimezone(
        jdbcUrl: String,
        venueId: Long,
        timezone: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO venue_settings (venue_id, timezone)
                KEY (venue_id)
                VALUES (?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, timezone)
                statement.executeUpdate()
            }
        }
    }

    private fun insertVenue(connection: java.sql.Connection): Long =
        connection.prepareStatement(
            """
            INSERT INTO venues (name, city, address, status)
            VALUES ('Venue', 'City', 'Address', ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, VenueStatus.PUBLISHED.dbValue)
            statement.executeUpdate()
            statement.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else error("Failed to insert venue") }
        }

    private fun insertSubscription(
        connection: java.sql.Connection,
        venueId: Long,
    ) {
        val now = Instant.now()
        connection.prepareStatement(
            """
            INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
            VALUES (?, 'ACTIVE', ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setTimestamp(2, Timestamp.from(now.plus(14, ChronoUnit.DAYS)))
            statement.setTimestamp(3, Timestamp.from(now))
            statement.setTimestamp(4, Timestamp.from(now))
            statement.executeUpdate()
        }
    }

    private fun insertVenueMember(
        connection: java.sql.Connection,
        venueId: Long,
        userId: Long,
        role: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO venue_members (venue_id, user_id, role)
            VALUES (?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, userId)
            statement.setString(3, role)
            statement.executeUpdate()
        }
    }

    private fun insertTable(
        connection: java.sql.Connection,
        venueId: Long,
        tableNumber: Int,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO venue_tables (venue_id, table_number, is_active)
            VALUES (?, ?, TRUE)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setInt(2, tableNumber)
            statement.executeUpdate()
            statement.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else error("Failed to insert table") }
        }

    private fun insertTableToken(
        connection: java.sql.Connection,
        tableId: Long,
        token: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO table_tokens (token, table_id, is_active)
            VALUES (?, ?, TRUE)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, token)
            statement.setLong(2, tableId)
            statement.executeUpdate()
        }
    }

    private fun insertTableSession(
        connection: java.sql.Connection,
        venueId: Long,
        tableId: Long,
    ): Long {
        val now = Instant.now()
        return connection.prepareStatement(
            """
            INSERT INTO table_sessions (venue_id, table_id, started_at, last_activity_at, expires_at, status)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE')
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.setTimestamp(3, Timestamp.from(now.minusSeconds(300)))
            statement.setTimestamp(4, Timestamp.from(now))
            statement.setTimestamp(5, Timestamp.from(now.plusSeconds(7200)))
            statement.executeUpdate()
            statement.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else error("Failed to insert session") }
        }
    }

    private fun insertPersonalTab(
        connection: java.sql.Connection,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
    ): Long {
        val tabId =
            connection.prepareStatement(
                """
                INSERT INTO tab (venue_id, table_session_id, type, owner_user_id, status)
                VALUES (?, ?, 'PERSONAL', ?, 'ACTIVE')
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, tableSessionId)
                statement.setLong(3, userId)
                statement.executeUpdate()
                statement.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else error("Failed to insert tab") }
            }
        connection.prepareStatement(
            """
            INSERT INTO tab_member (tab_id, user_id, role)
            VALUES (?, ?, 'OWNER')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, tabId)
            statement.setLong(2, userId)
            statement.executeUpdate()
        }
        return tabId
    }

    private fun insertMenuCategory(
        connection: java.sql.Connection,
        venueId: Long,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO menu_categories (venue_id, name, sort_order)
            VALUES (?, 'Category', 0)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeUpdate()
            statement.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else error("Failed to insert category") }
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
            VALUES (?, ?, ?, ?, 'RUB', TRUE)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, categoryId)
            statement.setString(3, name)
            statement.setLong(4, priceMinor)
            statement.executeUpdate()
            statement.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else error("Failed to insert item") }
        }

    private fun insertOrder(
        connection: java.sql.Connection,
        venueId: Long,
        tableId: Long,
        tableSessionId: Long,
        status: String,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO orders (venue_id, table_id, table_session_id, status, display_number, display_date)
            VALUES (?, ?, ?, ?, 1, CURRENT_DATE)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.setLong(3, tableSessionId)
            statement.setString(4, status)
            statement.executeUpdate()
            statement.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else error("Failed to insert order") }
        }

    private fun insertBatch(
        connection: java.sql.Connection,
        orderId: Long,
        tabId: Long,
        authorUserId: Long,
        status: String,
    ): Long {
        val now = Instant.now()
        return connection.prepareStatement(
            """
            INSERT INTO order_batches (
                order_id, tab_id, author_user_id, created_at, updated_at, source, status, guest_comment
            )
            VALUES (?, ?, ?, ?, ?, 'MINIAPP', ?, NULL)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.setLong(2, tabId)
            statement.setLong(3, authorUserId)
            statement.setTimestamp(4, Timestamp.from(now.minusSeconds(120)))
            statement.setTimestamp(5, Timestamp.from(now.minusSeconds(120)))
            statement.setString(6, status)
            statement.executeUpdate()
            statement.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else error("Failed to insert batch") }
        }
    }

    private fun insertBatchItem(
        connection: java.sql.Connection,
        batchId: Long,
        itemId: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO order_batch_items (order_batch_id, menu_item_id, qty)
            VALUES (?, ?, 1)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, batchId)
            statement.setLong(2, itemId)
            statement.executeUpdate()
        }
    }

    private fun seedUser(
        connection: java.sql.Connection,
        userId: Long,
    ) {
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

    private fun tableSessionExpiresAt(
        jdbcUrl: String,
        tableSessionId: Long,
    ): Instant =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT expires_at FROM table_sessions WHERE id = ?",
            ).use { statement ->
                statement.setLong(1, tableSessionId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getTimestamp(1).toInstant() else error("Missing table session")
                }
            }
        }

    private fun countRows(
        jdbcUrl: String,
        tableName: String,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM $tableName").use { statement ->
                statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }

    private data class ShiftExtensionFixture(
        val venueId: Long,
        val tableId: Long,
        val tableToken: String,
        val tableSessionId: Long,
        val tabId: Long,
        val orderId: Long,
    )

    private class CapturingStaffBillUpdateNotifier : StaffBillUpdateNotifier {
        val events = mutableListOf<StaffBillUpdatedNotification>()

        override suspend fun notifyBillUpdatedNow(event: StaffBillUpdatedNotification): StaffChatNotificationResult {
            events += event
            return StaffChatNotificationResult.SENT_OR_QUEUED
        }
    }

    private companion object {
        const val GUEST_USER_ID = 10101L
        const val OTHER_GUEST_USER_ID = 20202L
        const val STAFF_USER_ID = 30303L
    }
}
