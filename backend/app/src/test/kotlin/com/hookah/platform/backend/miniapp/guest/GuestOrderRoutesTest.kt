package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.ActiveOrderResponse
import com.hookah.platform.backend.miniapp.guest.api.AddBatchItemDto
import com.hookah.platform.backend.miniapp.guest.api.AddBatchRequest
import com.hookah.platform.backend.miniapp.guest.api.AddBatchResponse
import com.hookah.platform.backend.miniapp.guest.api.CartPreviewRequest
import com.hookah.platform.backend.miniapp.guest.api.CartPreviewResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus
import com.hookah.platform.backend.module
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.platform.PlatformMarkInvoicePaidRequest
import com.hookah.platform.backend.telegram.NewBatchNotification
import com.hookah.platform.backend.telegram.StaffChatNotificationResult
import com.hookah.platform.backend.telegram.StaffChatNotifier
import com.hookah.platform.backend.telegram.db.OrdersRepository
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
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.h2.jdbcx.JdbcDataSource
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class GuestOrderRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `active returns null when no active order`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-none")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 4)
            seedTableToken(jdbcUrl, tableId, "active-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)

            val token = issueToken(config)
            val response =
                client.get(
                    "/api/guest/order/active?tableToken=active-token" +
                        "&tableSessionId=$tableSessionId&tabId=$personalTabId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(ActiveOrderResponse.serializer(), response.bodyAsText())
            assertNull(payload.order)
        }

    @Test
    fun `add-batch with same idempotency key returns same batch`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-add")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 8)
            seedTableToken(jdbcUrl, tableId, "batch-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Tea")
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)

            val token = issueToken(config)
            val request =
                AddBatchRequest(
                    tableToken = "batch-token",
                    tableSessionId = tableSessionId,
                    tabId = personalTabId,
                    idempotencyKey = "idem-batch-token-1",
                    items = listOf(AddBatchItemDto(itemId = itemId, qty = 2)),
                    comment = "First batch",
                )
            val firstResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }
            val firstPayload = json.decodeFromString(AddBatchResponse.serializer(), firstResponse.bodyAsText())

            val secondResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            request.copy(
                                comment = "Second batch",
                                items = listOf(AddBatchItemDto(itemId = itemId, qty = 5)),
                            ),
                        ),
                    )
                }
            val secondPayload = json.decodeFromString(AddBatchResponse.serializer(), secondResponse.bodyAsText())

            assertEquals(HttpStatusCode.OK, firstResponse.status)
            assertEquals(HttpStatusCode.OK, secondResponse.status)
            assertEquals(firstPayload.orderId, secondPayload.orderId)
            assertEquals(firstPayload.batchId, secondPayload.batchId)

            val activeResponse =
                client.get(
                    "/api/guest/order/active?tableToken=batch-token" +
                        "&tableSessionId=$tableSessionId&tabId=$personalTabId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, activeResponse.status)
            val activePayload = json.decodeFromString(ActiveOrderResponse.serializer(), activeResponse.bodyAsText())
            val order = activePayload.order
            assertNotNull(order)
            assertEquals(tableSessionId, order.tableSessionId)
            assertEquals(personalTabId, order.tabId)
            assertEquals(1, order.batches.size)
            val activeItem = order.batches.first().items.first()
            assertEquals(itemId, activeItem.itemId)
            assertEquals("Tea", activeItem.name)
            assertEquals(200L, activeItem.lineGrossMinor)
            assertEquals(200L, order.grossTotalMinor)
            assertEquals(200L, order.finalPayableTotalMinor)
            assertEquals(1, countAnalyticsEvents(jdbcUrl, "batch_created", venueId))
        }

    @Test
    fun `add-batch in different table sessions creates different active orders for same table`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-session-scope")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 15)
            seedTableToken(jdbcUrl, tableId, "session-scope-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val firstSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val secondSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val firstTabId = seedPersonalTab(jdbcUrl, venueId, firstSessionId, TELEGRAM_USER_ID)
            val secondTabId = seedPersonalTab(jdbcUrl, venueId, secondSessionId, TELEGRAM_USER_ID)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Session scoped item")

            val token = issueToken(config)
            val firstResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            AddBatchRequest(
                                tableToken = "session-scope-token",
                                tableSessionId = firstSessionId,
                                tabId = firstTabId,
                                idempotencyKey = "session-scope-1",
                                items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
                                comment = "first session",
                            ),
                        ),
                    )
                }
            val secondResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            AddBatchRequest(
                                tableToken = "session-scope-token",
                                tableSessionId = secondSessionId,
                                tabId = secondTabId,
                                idempotencyKey = "session-scope-2",
                                items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
                                comment = "second session",
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, firstResponse.status)
            assertEquals(HttpStatusCode.OK, secondResponse.status)
            val firstPayload = json.decodeFromString(AddBatchResponse.serializer(), firstResponse.bodyAsText())
            val secondPayload = json.decodeFromString(AddBatchResponse.serializer(), secondResponse.bodyAsText())
            assertTrue(firstPayload.orderId != secondPayload.orderId)
            assertEquals(firstSessionId, fetchOrderTableSessionId(jdbcUrl, firstPayload.orderId))
            assertEquals(secondSessionId, fetchOrderTableSessionId(jdbcUrl, secondPayload.orderId))

            val firstActiveResponse =
                client.get(
                    "/api/guest/order/active?tableToken=session-scope-token" +
                        "&tableSessionId=$firstSessionId&tabId=$firstTabId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            val secondActiveResponse =
                client.get(
                    "/api/guest/order/active?tableToken=session-scope-token" +
                        "&tableSessionId=$secondSessionId&tabId=$secondTabId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, firstActiveResponse.status)
            assertEquals(HttpStatusCode.OK, secondActiveResponse.status)
            val firstActiveOrder =
                json.decodeFromString(ActiveOrderResponse.serializer(), firstActiveResponse.bodyAsText()).order
            val secondActiveOrder =
                json.decodeFromString(ActiveOrderResponse.serializer(), secondActiveResponse.bodyAsText()).order
            assertEquals(firstPayload.orderId, firstActiveOrder?.orderId)
            assertEquals(firstSessionId, firstActiveOrder?.tableSessionId)
            assertEquals(firstTabId, firstActiveOrder?.tabId)
            assertEquals(secondPayload.orderId, secondActiveOrder?.orderId)
            assertEquals(secondSessionId, secondActiveOrder?.tableSessionId)
            assertEquals(secondTabId, secondActiveOrder?.tabId)
        }

    @Test
    fun `active explicit tab from another table session is forbidden`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-active-cross-session-tab")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 18)
            seedTableToken(jdbcUrl, tableId, "active-cross-session-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val firstSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val secondSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val firstTabId = seedPersonalTab(jdbcUrl, venueId, firstSessionId, TELEGRAM_USER_ID)
            val secondTabId = seedPersonalTab(jdbcUrl, venueId, secondSessionId, TELEGRAM_USER_ID)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Cross session item")
            val token = issueToken(config)

            val addResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            AddBatchRequest(
                                tableToken = "active-cross-session-token",
                                tableSessionId = firstSessionId,
                                tabId = firstTabId,
                                idempotencyKey = "active-cross-session-owner",
                                items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
                                comment = null,
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, addResponse.status)

            val response =
                client.get(
                    "/api/guest/order/active?tableToken=active-cross-session-token" +
                        "&tableSessionId=$firstSessionId&tabId=$secondTabId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `add-batch with different idempotency keys creates two batches`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-add-idem-two")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 11)
            seedTableToken(jdbcUrl, tableId, "batch-two-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Mocha")
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)

            val token = issueToken(config)
            val firstRequest =
                AddBatchRequest(
                    tableToken = "batch-two-token",
                    tableSessionId = tableSessionId,
                    tabId = personalTabId,
                    idempotencyKey = "idem-batch-two-1",
                    items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
                    comment = "First",
                )
            val secondRequest = firstRequest.copy(idempotencyKey = "idem-batch-two-2", comment = "Second")

            val firstResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), firstRequest))
                }
            val secondResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), secondRequest))
                }

            val firstPayload = json.decodeFromString(AddBatchResponse.serializer(), firstResponse.bodyAsText())
            val secondPayload = json.decodeFromString(AddBatchResponse.serializer(), secondResponse.bodyAsText())

            assertEquals(HttpStatusCode.OK, firstResponse.status)
            assertEquals(HttpStatusCode.OK, secondResponse.status)
            assertEquals(firstPayload.orderId, secondPayload.orderId)
            assertTrue(firstPayload.batchId != secondPayload.batchId)
        }

    @Test
    fun `add-batch notifies staff chat live message for first and additional batch`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-staff-chat-live")
            val config = buildConfig(jdbcUrl)
            val staffChatNotifier: StaffChatNotifier = mockk()
            val notifications = mutableListOf<NewBatchNotification>()
            coEvery { staffChatNotifier.notifyNewBatchNow(any()) } answers {
                notifications += invocation.args[0] as NewBatchNotification
                StaffChatNotificationResult.SENT_OR_QUEUED
            }

            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(staffChatNotifier = staffChatNotifier),
                )
            }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 12)
            seedTableToken(jdbcUrl, tableId, "staff-chat-live-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Live item", priceMinor = 1_000)
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)
            val token = issueToken(config)
            val firstRequest =
                AddBatchRequest(
                    tableToken = "staff-chat-live-token",
                    tableSessionId = tableSessionId,
                    tabId = personalTabId,
                    idempotencyKey = "staff-chat-live-1",
                    items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
                    comment = "First",
                )
            val secondRequest =
                firstRequest.copy(
                    idempotencyKey = "staff-chat-live-2",
                    items = listOf(AddBatchItemDto(itemId = itemId, qty = 2)),
                    comment = "Additional",
                )

            val firstResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), firstRequest))
                }
            val secondResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), secondRequest))
                }
            val firstPayload = json.decodeFromString(AddBatchResponse.serializer(), firstResponse.bodyAsText())
            val secondPayload = json.decodeFromString(AddBatchResponse.serializer(), secondResponse.bodyAsText())

            assertEquals(HttpStatusCode.OK, firstResponse.status)
            assertEquals(HttpStatusCode.OK, secondResponse.status)
            assertEquals(firstPayload.orderId, secondPayload.orderId)
            assertEquals(2, notifications.size)

            val firstNotification = notifications[0]
            val secondNotification = notifications[1]
            assertEquals(venueId, firstNotification.venueId)
            assertEquals(firstPayload.orderId, firstNotification.orderId)
            assertEquals(firstPayload.batchId, firstNotification.batchId)
            assertTrue(firstNotification.isFirstBatch)
            assertEquals(OrderWorkflowStatus.NEW, firstNotification.status)
            assertNotNull(firstNotification.updatedAt)
            assertEquals(1_000, firstNotification.bill?.finalPayableTotalMinor)

            assertEquals(venueId, secondNotification.venueId)
            assertEquals(secondPayload.orderId, secondNotification.orderId)
            assertEquals(secondPayload.batchId, secondNotification.batchId)
            assertTrue(!secondNotification.isFirstBatch)
            assertEquals(OrderWorkflowStatus.NEW, secondNotification.status)
            assertNotNull(secondNotification.updatedAt)
            assertEquals(3_000, secondNotification.bill?.finalPayableTotalMinor)
        }

    @Test
    fun `add-batch idempotency replay still invokes staff chat notifier for recovery dedupe`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-staff-chat-replay")
            val config = buildConfig(jdbcUrl)
            val staffChatNotifier: StaffChatNotifier = mockk()
            val notifications = mutableListOf<NewBatchNotification>()
            coEvery { staffChatNotifier.notifyNewBatchNow(any()) } answers {
                notifications += invocation.args[0] as NewBatchNotification
                StaffChatNotificationResult.SENT_OR_QUEUED
            }

            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(staffChatNotifier = staffChatNotifier),
                )
            }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 13)
            seedTableToken(jdbcUrl, tableId, "staff-chat-replay-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Replay item", priceMinor = 1_000)
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)
            val token = issueToken(config)
            val request =
                AddBatchRequest(
                    tableToken = "staff-chat-replay-token",
                    tableSessionId = tableSessionId,
                    tabId = personalTabId,
                    idempotencyKey = "staff-chat-replay-1",
                    items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
                    comment = "Replay",
                )

            val firstResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }
            val secondResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }
            val firstPayload = json.decodeFromString(AddBatchResponse.serializer(), firstResponse.bodyAsText())
            val secondPayload = json.decodeFromString(AddBatchResponse.serializer(), secondResponse.bodyAsText())

            assertEquals(HttpStatusCode.OK, firstResponse.status)
            assertEquals(HttpStatusCode.OK, secondResponse.status)
            assertEquals(firstPayload.orderId, secondPayload.orderId)
            assertEquals(firstPayload.batchId, secondPayload.batchId)
            assertEquals(2, notifications.size)
            assertEquals(firstPayload.batchId, notifications[0].batchId)
            assertEquals(firstPayload.batchId, notifications[1].batchId)
            assertEquals(1_000, notifications[1].bill?.finalPayableTotalMinor)
        }

    @Test
    fun `user active summaries do not include other personal tab items`() =
        testApplication {
            val guestA = 10101L
            val guestB = 20202L
            val jdbcUrl = buildJdbcUrl("guest-order-summary-personal-tab-scope")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 13)
            seedTableToken(jdbcUrl, tableId, "summary-personal-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val guestAItemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Guest A item")
            val guestBItemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Guest B item")
            val guestATabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, guestA)
            val guestBTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, guestB)

            val guestAToken = issueToken(config, guestA)
            val guestBToken = issueToken(config, guestB)
            val guestAResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $guestAToken") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            AddBatchRequest(
                                tableToken = "summary-personal-token",
                                tableSessionId = tableSessionId,
                                tabId = guestATabId,
                                idempotencyKey = "summary-personal-a",
                                items = listOf(AddBatchItemDto(itemId = guestAItemId, qty = 1)),
                                comment = null,
                            ),
                        ),
                    )
                }
            val guestBResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $guestBToken") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            AddBatchRequest(
                                tableToken = "summary-personal-token",
                                tableSessionId = tableSessionId,
                                tabId = guestBTabId,
                                idempotencyKey = "summary-personal-b",
                                items = listOf(AddBatchItemDto(itemId = guestBItemId, qty = 1)),
                                comment = null,
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, guestAResponse.status)
            assertEquals(HttpStatusCode.OK, guestBResponse.status)

            val summaries =
                OrdersRepository(h2DataSource(jdbcUrl))
                    .listActiveOrderSummariesForUser(userId = guestA, limit = 5)

            assertEquals(1, summaries.size)
            assertEquals(listOf("Guest A item"), summaries.single().items.map { it.itemName })
            assertTrue(summaries.single().items.none { it.itemId == guestBItemId })
        }

    @Test
    fun `user active summaries include shared tab items only for members`() =
        testApplication {
            val guestA = 30303L
            val guestB = 40404L
            val jdbcUrl = buildJdbcUrl("guest-order-summary-shared-tab-scope")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 14)
            seedTableToken(jdbcUrl, tableId, "summary-shared-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val sharedItemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Shared tab item")
            val sharedTabId = seedSharedTab(jdbcUrl, venueId, tableSessionId, guestB)

            val guestBToken = issueToken(config, guestB)
            val addSharedResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $guestBToken") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            AddBatchRequest(
                                tableToken = "summary-shared-token",
                                tableSessionId = tableSessionId,
                                tabId = sharedTabId,
                                idempotencyKey = "summary-shared-b",
                                items = listOf(AddBatchItemDto(itemId = sharedItemId, qty = 2)),
                                comment = null,
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, addSharedResponse.status)

            val repository = OrdersRepository(h2DataSource(jdbcUrl))
            val beforeJoin = repository.listActiveOrderSummariesForUser(userId = guestA, limit = 5)
            assertTrue(beforeJoin.none { summary -> summary.items.any { item -> item.itemId == sharedItemId } })

            addTabMember(jdbcUrl, sharedTabId, guestA, role = "MEMBER")

            val afterJoin = repository.listActiveOrderSummariesForUser(userId = guestA, limit = 5)
            assertEquals(1, afterJoin.size)
            assertEquals(listOf("Shared tab item"), afterJoin.single().items.map { it.itemName })
            assertEquals("SHARED", afterJoin.single().tabType)
        }

    @Test
    fun `add-batch stores miniapp source`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-source")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 7)
            seedTableToken(jdbcUrl, tableId, "source-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Latte")
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)

            val token = issueToken(config)
            val request =
                AddBatchRequest(
                    tableToken = "source-token",
                    tableSessionId = tableSessionId,
                    tabId = personalTabId,
                    idempotencyKey = "idem-source-token-1",
                    items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
                    comment = "Source check",
                )
            val response =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(AddBatchResponse.serializer(), response.bodyAsText())
            val source = fetchOrderBatchSource(jdbcUrl, payload.batchId)
            assertEquals("MINIAPP", source)
        }

    @Test
    fun `add-batch persists promotion ledger and active summary uses promo discount`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-promo-ledger")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 17)
            seedTableToken(jdbcUrl, tableId, "promo-ledger-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            setMenuCategoryType(jdbcUrl, categoryId, "HOOKAH")
            val hookahItemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Promo hookah")
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)
            seedHappyHoursRule(jdbcUrl, venueId, TELEGRAM_USER_ID, discountPercent = 20, status = "ACTIVE")

            val token = issueToken(config)
            val request =
                AddBatchRequest(
                    tableToken = "promo-ledger-token",
                    tableSessionId = tableSessionId,
                    tabId = personalTabId,
                    idempotencyKey = "idem-promo-ledger",
                    items = listOf(AddBatchItemDto(itemId = hookahItemId, qty = 2)),
                    comment = null,
                )
            val response =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }
            val replay =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(HttpStatusCode.OK, replay.status)
            val orderSummary =
                OrdersRepository(h2DataSource(jdbcUrl))
                    .listActiveOrderSummariesForUser(userId = TELEGRAM_USER_ID, limit = 5)
                    .single()
            val summary =
                orderSummary
                    .items
                    .single()
            assertEquals(hookahItemId, summary.itemId)
            assertEquals(40L, summary.promoDiscountMinor)
            assertEquals(
                listOf("Счастливые часы" to 40L),
                orderSummary.promotionDiscounts.map { it.label to it.discountMinor },
            )
            assertEquals(1, countPromotionApplications(jdbcUrl))
            assertEquals(1, countPromotionAdjustments(jdbcUrl))
        }

    @Test
    fun `cart preview returns promo discount without creating order and matches submit result`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-cart-preview-promo")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 18)
            seedTableToken(jdbcUrl, tableId, "cart-preview-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            setMenuCategoryType(jdbcUrl, categoryId, "HOOKAH")
            val hookahItemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Preview hookah")
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)
            seedHappyHoursRule(jdbcUrl, venueId, TELEGRAM_USER_ID, discountPercent = 20, status = "ACTIVE")
            val token = issueToken(config)
            val items = listOf(AddBatchItemDto(itemId = hookahItemId, qty = 2))

            val previewResponse =
                client.post("/api/guest/order/preview") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(
                        json.encodeToString(
                            CartPreviewRequest.serializer(),
                            CartPreviewRequest(
                                tableToken = "cart-preview-token",
                                tableSessionId = tableSessionId,
                                tabId = personalTabId,
                                items = items,
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, previewResponse.status)
            val previewPayload = json.decodeFromString(CartPreviewResponse.serializer(), previewResponse.bodyAsText())
            val preview = previewPayload.preview
            assertEquals(200L, preview.grossTotalMinor)
            assertEquals(40L, preview.promoDiscountTotalMinor)
            assertEquals(0L, preview.loyaltyDiscountTotalMinor)
            assertEquals(160L, preview.finalPayableTotalMinor)
            assertEquals(listOf("Счастливые часы" to 40L), preview.discounts.map { it.label to it.discountMinor })
            assertEquals(200L, preview.items.single { it.itemId == hookahItemId }.lineGrossMinor)
            assertEquals(40L, preview.items.single { it.itemId == hookahItemId }.discountMinor)
            assertEquals(160L, preview.items.single { it.itemId == hookahItemId }.linePayableMinor)
            assertEquals(0, countRows(jdbcUrl, "orders"))
            assertEquals(0, countRows(jdbcUrl, "order_batches"))
            assertEquals(0, countPromotionApplications(jdbcUrl))
            assertEquals(0, countPromotionAdjustments(jdbcUrl))

            val submitResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            AddBatchRequest(
                                tableToken = "cart-preview-token",
                                tableSessionId = tableSessionId,
                                tabId = personalTabId,
                                idempotencyKey = "idem-cart-preview",
                                items = items,
                                comment = null,
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, submitResponse.status)

            val activeResponse =
                client.get(
                    "/api/guest/order/active?tableToken=cart-preview-token" +
                        "&tableSessionId=$tableSessionId&tabId=$personalTabId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, activeResponse.status)
            val activePayload = json.decodeFromString(ActiveOrderResponse.serializer(), activeResponse.bodyAsText())
            val order = assertNotNull(activePayload.order)
            assertEquals(preview.grossTotalMinor, order.grossTotalMinor)
            assertEquals(preview.promoDiscountTotalMinor, order.promoDiscountTotalMinor)
            assertEquals(preview.loyaltyDiscountTotalMinor, order.loyaltyDiscountTotalMinor)
            assertEquals(preview.finalPayableTotalMinor, order.finalPayableTotalMinor)
            assertEquals(
                preview.discounts.map {
                    it.label to it.discountMinor
                },
                order.discounts.map { it.label to it.discountMinor },
            )
        }

    @Test
    fun `add-batch inserts gift reward item with promotion ledger adjustment`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-gift-ledger")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 27)
            seedTableToken(jdbcUrl, tableId, "gift-ledger-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val hookahCategoryId = seedMenuCategory(jdbcUrl, venueId)
            setMenuCategoryType(jdbcUrl, hookahCategoryId, "HOOKAH")
            val hookahItemId = seedMenuItem(jdbcUrl, venueId, hookahCategoryId, "Кальян обычный")
            val teaCategoryId = seedMenuCategory(jdbcUrl, venueId)
            setMenuCategoryType(jdbcUrl, teaCategoryId, "TEA")
            val teaItemId = seedMenuItem(jdbcUrl, venueId, teaCategoryId, "Чай")
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)
            seedGiftWithItemRule(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                userId = TELEGRAM_USER_ID,
                rewardMenuItemId = teaItemId,
                status = "ACTIVE",
            )

            val token = issueToken(config)
            val request =
                AddBatchRequest(
                    tableToken = "gift-ledger-token",
                    tableSessionId = tableSessionId,
                    tabId = personalTabId,
                    idempotencyKey = "idem-gift-ledger",
                    items = listOf(AddBatchItemDto(itemId = hookahItemId, qty = 1)),
                    comment = null,
                )
            val response =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }
            val replay =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(HttpStatusCode.OK, replay.status)
            val batchId = json.decodeFromString(AddBatchResponse.serializer(), response.bodyAsText()).batchId
            val summary =
                OrdersRepository(h2DataSource(jdbcUrl))
                    .listActiveOrderSummariesForUser(userId = TELEGRAM_USER_ID, limit = 5)
                    .single()
            assertEquals(listOf(hookahItemId, teaItemId), summary.items.map { it.itemId })
            assertEquals(100L, summary.items.single { it.itemId == teaItemId }.promoDiscountMinor)
            assertTrue(summary.items.single { it.itemId == teaItemId }.isPromotionReward)
            assertEquals(
                listOf("Чай в подарок" to 100L),
                summary.promotionDiscounts.map { it.label to it.discountMinor },
            )
            assertEquals(2, countBatchItems(jdbcUrl, batchId))
            assertEquals(1, countPromotionApplications(jdbcUrl))
            assertEquals(1, countPromotionAdjustments(jdbcUrl))
            assertEquals(1, countRows(jdbcUrl, "order_promotion_reward_items"))
        }

    @Test
    fun `add-batch applies loyalty redemption to cheapest eligible hookah and is idempotent`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-loyalty-redemption")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 47)
            seedTableToken(jdbcUrl, tableId, "loyalty-redemption-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val hookahCategoryId = seedMenuCategory(jdbcUrl, venueId)
            setMenuCategoryType(jdbcUrl, hookahCategoryId, "HOOKAH")
            val regularHookahId = seedMenuItem(jdbcUrl, venueId, hookahCategoryId, "Кальян обычный", priceMinor = 2_000)
            val lightHookahId = seedMenuItem(jdbcUrl, venueId, hookahCategoryId, "Кальян лёгкий", priceMinor = 1_500)
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)
            val programId = seedActiveLoyaltyProgramWithReward(jdbcUrl, venueId, TELEGRAM_USER_ID, rewardsAvailable = 1)

            val token = issueToken(config)
            val request =
                AddBatchRequest(
                    tableToken = "loyalty-redemption-token",
                    tableSessionId = tableSessionId,
                    tabId = personalTabId,
                    idempotencyKey = "idem-loyalty-redemption",
                    items =
                        listOf(
                            AddBatchItemDto(itemId = regularHookahId, qty = 1),
                            AddBatchItemDto(itemId = lightHookahId, qty = 1),
                        ),
                    comment = null,
                )
            val response =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }
            val replay =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(HttpStatusCode.OK, replay.status)
            val summary =
                OrdersRepository(h2DataSource(jdbcUrl))
                    .listActiveOrderSummariesForUser(userId = TELEGRAM_USER_ID, limit = 5)
                    .single()
            assertEquals(
                listOf("Лояльность: бесплатный кальян" to 1_500L),
                summary.promotionDiscounts.map {
                    it.label to it.discountMinor
                },
            )
            assertEquals(1_500L, summary.items.single { it.itemId == lightHookahId }.promoDiscountMinor)
            assertEquals(0L, summary.items.single { it.itemId == regularHookahId }.promoDiscountMinor)
            assertEquals(1, countRows(jdbcUrl, "loyalty_redemptions"))
            assertEquals(1, countPromotionApplications(jdbcUrl))
            assertEquals(1, countPromotionAdjustments(jdbcUrl))
            assertEquals(0, fetchLoyaltyRewardsAvailable(jdbcUrl, programId, TELEGRAM_USER_ID))
            assertEquals(1, fetchLoyaltyLedgerCount(jdbcUrl, programId))
        }

    @Test
    fun `add-batch does not redeem loyalty for non eligible reward target`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-loyalty-non-eligible")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 48)
            seedTableToken(jdbcUrl, tableId, "loyalty-non-eligible-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val hookahCategoryId = seedMenuCategory(jdbcUrl, venueId)
            setMenuCategoryType(jdbcUrl, hookahCategoryId, "HOOKAH")
            val regularHookahId = seedMenuItem(jdbcUrl, venueId, hookahCategoryId, "Кальян обычный", priceMinor = 2_000)
            val premiumHookahId = seedMenuItem(jdbcUrl, venueId, hookahCategoryId, "Премиум кальян", priceMinor = 3_000)
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)
            val programId =
                seedActiveLoyaltyProgramWithReward(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    userId = TELEGRAM_USER_ID,
                    rewardsAvailable = 1,
                    rewardMenuItemIds = listOf(premiumHookahId),
                )

            val token = issueToken(config)
            val response =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            AddBatchRequest(
                                tableToken = "loyalty-non-eligible-token",
                                tableSessionId = tableSessionId,
                                tabId = personalTabId,
                                idempotencyKey = "idem-loyalty-non-eligible",
                                items = listOf(AddBatchItemDto(itemId = regularHookahId, qty = 1)),
                                comment = null,
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(0, countRows(jdbcUrl, "loyalty_redemptions"))
            assertEquals(0, countPromotionApplications(jdbcUrl))
            assertEquals(1, fetchLoyaltyRewardsAvailable(jdbcUrl, programId, TELEGRAM_USER_ID))
        }

    @Test
    fun `add-batch loyalty free hookah wins over percent discount on same item`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-loyalty-wins-percent")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 49)
            seedTableToken(jdbcUrl, tableId, "loyalty-wins-percent-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val hookahCategoryId = seedMenuCategory(jdbcUrl, venueId)
            setMenuCategoryType(jdbcUrl, hookahCategoryId, "HOOKAH")
            val hookahItemId = seedMenuItem(jdbcUrl, venueId, hookahCategoryId, "Кальян обычный", priceMinor = 2_000)
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)
            seedActiveLoyaltyProgramWithReward(jdbcUrl, venueId, TELEGRAM_USER_ID, rewardsAvailable = 1)
            seedHappyHoursRule(jdbcUrl, venueId, TELEGRAM_USER_ID, discountPercent = 20, status = "ACTIVE")

            val token = issueToken(config)
            val response =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            AddBatchRequest(
                                tableToken = "loyalty-wins-percent-token",
                                tableSessionId = tableSessionId,
                                tabId = personalTabId,
                                idempotencyKey = "idem-loyalty-wins-percent",
                                items = listOf(AddBatchItemDto(itemId = hookahItemId, qty = 1)),
                                comment = null,
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val summary =
                OrdersRepository(h2DataSource(jdbcUrl))
                    .listActiveOrderSummariesForUser(userId = TELEGRAM_USER_ID, limit = 5)
                    .single()
            assertEquals(
                listOf("Лояльность: бесплатный кальян" to 2_000L),
                summary.promotionDiscounts.map {
                    it.label to it.discountMinor
                },
            )
            assertEquals(2_000L, summary.items.single().promoDiscountMinor)
            val finalPayableMinor =
                summary.items.single().priceMinor!! * summary.items.single().qty -
                    summary.items.single().promoDiscountMinor
            assertEquals(
                0L,
                finalPayableMinor,
            )
            assertEquals(1, countRows(jdbcUrl, "loyalty_redemptions"))
            assertEquals(1, countPromotionApplications(jdbcUrl))
            assertEquals(1, countPromotionAdjustments(jdbcUrl))
        }

    @Test
    fun `add-batch loyalty applies to cheapest hookah and percent applies to other hookah`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-loyalty-cheapest-percent-other")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 50)
            seedTableToken(jdbcUrl, tableId, "loyalty-cheapest-percent-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val hookahCategoryId = seedMenuCategory(jdbcUrl, venueId)
            setMenuCategoryType(jdbcUrl, hookahCategoryId, "HOOKAH")
            val regularHookahId = seedMenuItem(jdbcUrl, venueId, hookahCategoryId, "Кальян обычный", priceMinor = 2_000)
            val lightHookahId = seedMenuItem(jdbcUrl, venueId, hookahCategoryId, "Кальян лёгкий", priceMinor = 1_000)
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)
            seedActiveLoyaltyProgramWithReward(jdbcUrl, venueId, TELEGRAM_USER_ID, rewardsAvailable = 1)
            seedHappyHoursRule(jdbcUrl, venueId, TELEGRAM_USER_ID, discountPercent = 20, status = "ACTIVE")

            val token = issueToken(config)
            val response =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            AddBatchRequest(
                                tableToken = "loyalty-cheapest-percent-token",
                                tableSessionId = tableSessionId,
                                tabId = personalTabId,
                                idempotencyKey = "idem-loyalty-cheapest-percent",
                                items =
                                    listOf(
                                        AddBatchItemDto(itemId = regularHookahId, qty = 1),
                                        AddBatchItemDto(itemId = lightHookahId, qty = 1),
                                    ),
                                comment = null,
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val summary =
                OrdersRepository(h2DataSource(jdbcUrl))
                    .listActiveOrderSummariesForUser(userId = TELEGRAM_USER_ID, limit = 5)
                    .single()
            assertEquals(
                setOf("Лояльность: бесплатный кальян" to 1_000L, "Счастливые часы" to 400L),
                summary.promotionDiscounts.map { it.label to it.discountMinor }.toSet(),
            )
            assertEquals(1_000L, summary.items.single { it.itemId == lightHookahId }.promoDiscountMinor)
            assertEquals(400L, summary.items.single { it.itemId == regularHookahId }.promoDiscountMinor)
            assertEquals(2, countPromotionApplications(jdbcUrl))
            assertEquals(2, countPromotionAdjustments(jdbcUrl))
        }

    @Test
    fun `add-batch persists only best non-stackable promotion but stacks explicit gift`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-promo-stackability")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 37)
            seedTableToken(jdbcUrl, tableId, "stackability-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val hookahCategoryId = seedMenuCategory(jdbcUrl, venueId)
            setMenuCategoryType(jdbcUrl, hookahCategoryId, "HOOKAH")
            val hookahItemId = seedMenuItem(jdbcUrl, venueId, hookahCategoryId, "Кальян", priceMinor = 2_000)
            val drinkCategoryId = seedMenuCategory(jdbcUrl, venueId)
            setMenuCategoryType(jdbcUrl, drinkCategoryId, "DRINK")
            val juiceItemId = seedMenuItem(jdbcUrl, venueId, drinkCategoryId, "Сок", priceMinor = 500)
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)
            seedHappyHoursRule(jdbcUrl, venueId, TELEGRAM_USER_ID, discountPercent = 10, status = "ACTIVE")
            seedGiftWithItemRule(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                userId = TELEGRAM_USER_ID,
                rewardMenuItemId = juiceItemId,
                status = "ACTIVE",
            )

            val token = issueToken(config)
            val request =
                AddBatchRequest(
                    tableToken = "stackability-token",
                    tableSessionId = tableSessionId,
                    tabId = personalTabId,
                    idempotencyKey = "idem-stackability-best",
                    items = listOf(AddBatchItemDto(itemId = hookahItemId, qty = 1)),
                    comment = null,
                )
            val response =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val firstSummary =
                OrdersRepository(h2DataSource(jdbcUrl))
                    .listActiveOrderSummariesForUser(userId = TELEGRAM_USER_ID, limit = 5)
                    .single()
            assertEquals(listOf(hookahItemId, juiceItemId), firstSummary.items.map { it.itemId })
            assertEquals(
                listOf("Сок в подарок" to 500L),
                firstSummary.promotionDiscounts.map { it.label to it.discountMinor },
            )
            assertEquals(1, countPromotionApplications(jdbcUrl))
            assertEquals(1, countPromotionAdjustments(jdbcUrl))

            val giftOnlyStackableTableId = seedTable(jdbcUrl, venueId, 39)
            seedTableToken(jdbcUrl, giftOnlyStackableTableId, "stackability-token-gift-only")
            val giftOnlyStackableSessionId = seedTableSession(jdbcUrl, venueId, giftOnlyStackableTableId)
            val giftOnlyStackableTabId = seedPersonalTab(jdbcUrl, venueId, giftOnlyStackableSessionId, TELEGRAM_USER_ID)
            setGiftRulesStackableOnly(jdbcUrl)
            val giftOnlyStackableRequest =
                AddBatchRequest(
                    tableToken = "stackability-token-gift-only",
                    tableSessionId = giftOnlyStackableSessionId,
                    tabId = giftOnlyStackableTabId,
                    idempotencyKey = "idem-stackability-gift-only",
                    items = listOf(AddBatchItemDto(itemId = hookahItemId, qty = 1)),
                    comment = null,
                )
            val giftOnlyStackableResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), giftOnlyStackableRequest))
                }

            assertEquals(HttpStatusCode.OK, giftOnlyStackableResponse.status)
            val giftOnlyStackableSummary =
                OrdersRepository(h2DataSource(jdbcUrl))
                    .listActiveOrderSummariesForUser(userId = TELEGRAM_USER_ID, limit = 5)
                    .maxBy { it.orderId }
            assertEquals(
                listOf("Сок в подарок" to 500L),
                giftOnlyStackableSummary.promotionDiscounts.map {
                    it.label to it.discountMinor
                },
            )

            val stackableTableId = seedTable(jdbcUrl, venueId, 38)
            seedTableToken(jdbcUrl, stackableTableId, "stackability-token-2")
            val stackableSessionId = seedTableSession(jdbcUrl, venueId, stackableTableId)
            val stackableTabId = seedPersonalTab(jdbcUrl, venueId, stackableSessionId, TELEGRAM_USER_ID)
            setAllPromotionRulesStackable(jdbcUrl)
            val stackableRequest =
                AddBatchRequest(
                    tableToken = "stackability-token-2",
                    tableSessionId = stackableSessionId,
                    tabId = stackableTabId,
                    idempotencyKey = "idem-stackability-both",
                    items = listOf(AddBatchItemDto(itemId = hookahItemId, qty = 1)),
                    comment = null,
                )
            val stackableResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), stackableRequest))
                }

            assertEquals(HttpStatusCode.OK, stackableResponse.status)
            val secondSummary =
                OrdersRepository(h2DataSource(jdbcUrl))
                    .listActiveOrderSummariesForUser(userId = TELEGRAM_USER_ID, limit = 5)
                    .maxBy { it.orderId }
            assertEquals(
                setOf("Счастливые часы" to 200L, "Сок в подарок" to 500L),
                secondSummary.promotionDiscounts.map { it.label to it.discountMinor }.toSet(),
            )
        }

    @Test
    fun `add-batch does not persist promo ledger when rule is paused`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-promo-paused")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 18)
            seedTableToken(jdbcUrl, tableId, "promo-paused-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            setMenuCategoryType(jdbcUrl, categoryId, "HOOKAH")
            val hookahItemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Paused promo hookah")
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)
            seedHappyHoursRule(jdbcUrl, venueId, TELEGRAM_USER_ID, discountPercent = 20, status = "PAUSED")

            val token = issueToken(config)
            val response =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            AddBatchRequest(
                                tableToken = "promo-paused-token",
                                tableSessionId = tableSessionId,
                                tabId = personalTabId,
                                idempotencyKey = "idem-promo-paused",
                                items = listOf(AddBatchItemDto(itemId = hookahItemId, qty = 1)),
                                comment = null,
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(0, countPromotionApplications(jdbcUrl))
            assertEquals(0, countPromotionAdjustments(jdbcUrl))
        }

    @Test
    fun `add-batch does not persist promo ledger when parent promotion is archived`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-promo-parent-archived")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 19)
            seedTableToken(jdbcUrl, tableId, "promo-parent-archived-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            setMenuCategoryType(jdbcUrl, categoryId, "HOOKAH")
            val hookahItemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Archived promo hookah")
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)
            seedHappyHoursRule(
                jdbcUrl,
                venueId,
                TELEGRAM_USER_ID,
                discountPercent = 20,
                status = "ACTIVE",
                promotionStatus = "ARCHIVED",
            )

            val token = issueToken(config)
            val response =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            AddBatchRequest(
                                tableToken = "promo-parent-archived-token",
                                tableSessionId = tableSessionId,
                                tabId = personalTabId,
                                idempotencyKey = "idem-promo-parent-archived",
                                items = listOf(AddBatchItemDto(itemId = hookahItemId, qty = 1)),
                                comment = null,
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(0, countPromotionApplications(jdbcUrl))
            assertEquals(0, countPromotionAdjustments(jdbcUrl))
        }

    @Test
    fun `add-batch accepts missing comment`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-missing-comment")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 5)
            seedTableToken(jdbcUrl, tableId, "missing-comment-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Matcha")
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)

            val token = issueToken(config)
            val requestBody =
                """
                {
                  "tableToken": "missing-comment-token",
                  "tableSessionId": $tableSessionId,
                  "tabId": $personalTabId,
                  "idempotencyKey": "idem-missing-comment-token-1",
                  "items": [
                    {
                      "itemId": $itemId,
                      "qty": 1
                    }
                  ]
                }
                """.trimIndent()
            val response =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(requestBody)
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `add-batch for paused venue returns not found`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-paused")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PAUSED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 9)
            seedTableToken(jdbcUrl, tableId, "paused-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Herbal Tea")
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)

            val token = issueToken(config)
            val request =
                AddBatchRequest(
                    tableToken = "paused-token",
                    tableSessionId = tableSessionId,
                    tabId = personalTabId,
                    idempotencyKey = "idem-paused-token-1",
                    items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
                    comment = null,
                )
            val response =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
        }

    @Test
    fun `add-batch on чужой tab returns forbidden`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-foreign-tab")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 12)
            seedTableToken(jdbcUrl, tableId, "foreign-tab-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Soda")
            val foreignTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, userId = 99999)

            val token = issueToken(config)
            val request =
                AddBatchRequest(
                    tableToken = "foreign-tab-token",
                    tableSessionId = tableSessionId,
                    tabId = foreignTabId,
                    idempotencyKey = "idem-foreign-tab-1",
                    items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
                    comment = null,
                )
            val response =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `active explicit personal tab does not expose another guest tab`() =
        testApplication {
            val guestA = 50505L
            val guestB = 60606L
            val jdbcUrl = buildJdbcUrl("guest-order-active-foreign-personal")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 16)
            seedTableToken(jdbcUrl, tableId, "active-foreign-personal-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val guestBTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, guestB)

            val response =
                client.get(
                    "/api/guest/order/active?tableToken=active-foreign-personal-token" +
                        "&tableSessionId=$tableSessionId&tabId=$guestBTabId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, guestA)}") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `active explicit shared tab requires membership and returns shared batches for member`() =
        testApplication {
            val guestA = 70707L
            val guestB = 80808L
            val jdbcUrl = buildJdbcUrl("guest-order-active-shared-member")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 17)
            seedTableToken(jdbcUrl, tableId, "active-shared-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val sharedItemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Shared active item")
            val sharedTabId = seedSharedTab(jdbcUrl, venueId, tableSessionId, guestB)
            val guestBToken = issueToken(config, guestB)

            val addResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $guestBToken") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            AddBatchRequest(
                                tableToken = "active-shared-token",
                                tableSessionId = tableSessionId,
                                tabId = sharedTabId,
                                idempotencyKey = "active-shared-owner",
                                items = listOf(AddBatchItemDto(itemId = sharedItemId, qty = 1)),
                                comment = null,
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, addResponse.status)

            val guestAToken = issueToken(config, guestA)
            val beforeJoinResponse =
                client.get(
                    "/api/guest/order/active?tableToken=active-shared-token" +
                        "&tableSessionId=$tableSessionId&tabId=$sharedTabId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestAToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, beforeJoinResponse.status)
            assertApiErrorEnvelope(beforeJoinResponse, ApiErrorCodes.FORBIDDEN)

            addTabMember(jdbcUrl, sharedTabId, guestA, role = "MEMBER")

            val afterJoinResponse =
                client.get(
                    "/api/guest/order/active?tableToken=active-shared-token" +
                        "&tableSessionId=$tableSessionId&tabId=$sharedTabId",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestAToken") }
                }
            assertEquals(HttpStatusCode.OK, afterJoinResponse.status)
            val payload = json.decodeFromString(ActiveOrderResponse.serializer(), afterJoinResponse.bodyAsText())
            val order = payload.order
            assertNotNull(order)
            assertEquals(tableSessionId, order.tableSessionId)
            assertEquals(sharedTabId, order.tabId)
            assertEquals(1, order.batches.size)
            assertEquals(sharedItemId, order.batches.single().items.single().itemId)
        }

    @Test
    fun `blocked subscription rejects add-batch`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-blocked")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 2)
            seedTableToken(jdbcUrl, tableId, "blocked-token")
            seedSubscription(jdbcUrl, venueId, "SUSPENDED_BY_PLATFORM")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Coffee")
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)

            val token = issueToken(config)
            val request =
                AddBatchRequest(
                    tableToken = "blocked-token",
                    tableSessionId = tableSessionId,
                    tabId = personalTabId,
                    idempotencyKey = "idem-blocked-token-1",
                    items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
                    comment = null,
                )
            val response =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.Locked, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.SUBSCRIPTION_BLOCKED)
        }

    @Test
    fun `mark paid unblocks add-batch`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-mark-paid")
            val ownerId = 5151L
            val config = buildConfig(jdbcUrl, platformOwnerId = ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 6)
            seedTableToken(jdbcUrl, tableId, "paid-token")
            seedSubscription(jdbcUrl, venueId, "SUSPENDED_BY_PLATFORM")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Cappuccino")
            val invoiceId = seedInvoice(jdbcUrl, venueId, status = "PAST_DUE")
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)

            val guestToken = issueToken(config, TELEGRAM_USER_ID)
            val request =
                AddBatchRequest(
                    tableToken = "paid-token",
                    tableSessionId = tableSessionId,
                    tabId = personalTabId,
                    idempotencyKey = "idem-paid-token-1",
                    items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
                    comment = "test",
                )
            val blockedResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.Locked, blockedResponse.status)
            assertApiErrorEnvelope(blockedResponse, ApiErrorCodes.SUBSCRIPTION_BLOCKED)

            val ownerToken = issueToken(config, ownerId)
            val markPaidResponse =
                client.post("/api/platform/invoices/$invoiceId/mark-paid") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformMarkInvoicePaidRequest.serializer(),
                            PlatformMarkInvoicePaidRequest(),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, markPaidResponse.status)

            val unblockedResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.OK, unblockedResponse.status)
            val payload = json.decodeFromString(AddBatchResponse.serializer(), unblockedResponse.bodyAsText())
            assertNotNull(payload.orderId)
        }

    @Test
    fun `add-batch rate limit blocks spam`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-rate-limit")
            val config =
                buildConfig(
                    jdbcUrl = jdbcUrl,
                    guestAddBatchMaxRequests = 1,
                    guestAddBatchWindowSeconds = 60,
                )

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 3)
            seedTableToken(jdbcUrl, tableId, "rate-limit-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val tableSessionId = seedTableSession(jdbcUrl, venueId, tableId)
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Espresso")
            val personalTabId = seedPersonalTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)

            val token = issueToken(config)
            val request =
                AddBatchRequest(
                    tableToken = "rate-limit-token",
                    tableSessionId = tableSessionId,
                    tabId = personalTabId,
                    idempotencyKey = "idem-rate-limit-token-1",
                    items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
                    comment = null,
                )

            val firstResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }
            val secondResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            request.copy(idempotencyKey = "idem-rate-limit-token-2"),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, firstResponse.status)
            assertEquals(HttpStatusCode.TooManyRequests, secondResponse.status)
            assertApiErrorEnvelope(secondResponse, ApiErrorCodes.RATE_LIMITED)
        }

    @Test
    fun `expired session rejects add-batch`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-order-expired-session")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 7)
            seedTableToken(jdbcUrl, tableId, "expired-session-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Latte")
            val expiredSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES),
                )

            val token = issueToken(config)
            val request =
                AddBatchRequest(
                    tableToken = "expired-session-token",
                    tableSessionId = expiredSessionId,
                    tabId = 1,
                    idempotencyKey = "idem-expired-session-1",
                    items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
                    comment = null,
                )

            val response =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
        }

    @Test
    fun `invalid table token rejects add-batch without resolver`() =
        testApplication {
            var resolveCalls = 0
            val config =
                MapApplicationConfig(
                    "app.env" to appEnv,
                    "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to "",
                )

            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(
                        tableTokenResolver = {
                            resolveCalls += 1
                            fail("tableTokenResolver must not be called for invalid tokens")
                        },
                    ),
                )
            }

            val token = issueToken(config)
            val request =
                AddBatchRequest(
                    tableToken = "bad token",
                    tableSessionId = 1,
                    tabId = 1,
                    idempotencyKey = "idem-invalid-token-1",
                    items = listOf(AddBatchItemDto(itemId = 1, qty = 1)),
                    comment = null,
                )
            val response =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(json.encodeToString(AddBatchRequest.serializer(), request))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
            assertEquals(0, resolveCalls)
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(
        jdbcUrl: String,
        platformOwnerId: Long? = null,
        guestAddBatchMaxRequests: Int? = null,
        guestAddBatchWindowSeconds: Long? = null,
    ): MapApplicationConfig {
        val entries =
            mutableListOf(
                "app.env" to appEnv,
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to jdbcUrl,
                "db.user" to "sa",
                "db.password" to "",
            )
        if (platformOwnerId != null) {
            entries.add("platform.ownerUserId" to platformOwnerId.toString())
        }
        if (guestAddBatchMaxRequests != null) {
            entries.add("guest.rateLimit.addBatch.maxRequests" to guestAddBatchMaxRequests.toString())
        }
        if (guestAddBatchWindowSeconds != null) {
            entries.add("guest.rateLimit.addBatch.windowSeconds" to guestAddBatchWindowSeconds.toString())
        }
        return MapApplicationConfig(*entries.toTypedArray())
    }

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long = TELEGRAM_USER_ID,
    ): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(userId).token
    }

    private fun seedVenue(
        jdbcUrl: String,
        status: String,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venues (name, city, address, status)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, "Venue")
                statement.setString(2, "City")
                statement.setString(3, "Address")
                statement.setString(4, status)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert venue")
    }

    private fun seedTable(
        jdbcUrl: String,
        venueId: Long,
        tableNumber: Int,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_tables (venue_id, table_number)
                VALUES (?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setInt(2, tableNumber)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert table")
    }

    private fun seedTableToken(
        jdbcUrl: String,
        tableId: Long,
        token: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO table_tokens (token, table_id, is_active)
                VALUES (?, ?, true)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, token)
                statement.setLong(2, tableId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedTableSession(
        jdbcUrl: String,
        venueId: Long,
        tableId: Long,
        expiresAt: Instant = Instant.now().plus(1, ChronoUnit.HOURS),
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO table_sessions (venue_id, table_id, started_at, last_activity_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                val now = Instant.now()
                statement.setLong(1, venueId)
                statement.setLong(2, tableId)
                statement.setTimestamp(3, Timestamp.from(now))
                statement.setTimestamp(4, Timestamp.from(now))
                statement.setTimestamp(5, Timestamp.from(expiresAt))
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert table session")
    }

    private fun seedPersonalTab(
        jdbcUrl: String,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            ensureUser(connection, userId)
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
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        val tabId = rs.getLong(1)
                        connection.prepareStatement(
                            """
                            INSERT INTO tab_member (tab_id, user_id, role)
                            VALUES (?, ?, 'OWNER')
                            """.trimIndent(),
                        ).use { membership ->
                            membership.setLong(1, tabId)
                            membership.setLong(2, userId)
                            membership.executeUpdate()
                        }
                        return tabId
                    }
                }
            }
        }
        error("Failed to insert personal tab")
    }

    private fun seedSharedTab(
        jdbcUrl: String,
        venueId: Long,
        tableSessionId: Long,
        ownerUserId: Long,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            ensureUser(connection, ownerUserId)
            connection.prepareStatement(
                """
                INSERT INTO tab (venue_id, table_session_id, type, owner_user_id, status)
                VALUES (?, ?, 'SHARED', ?, 'ACTIVE')
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, tableSessionId)
                statement.setLong(3, ownerUserId)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        val tabId = rs.getLong(1)
                        addTabMember(connection, tabId, ownerUserId, role = "OWNER")
                        return tabId
                    }
                }
            }
        }
        error("Failed to insert shared tab")
    }

    private fun addTabMember(
        jdbcUrl: String,
        tabId: Long,
        userId: Long,
        role: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            addTabMember(connection, tabId, userId, role)
        }
    }

    private fun addTabMember(
        connection: java.sql.Connection,
        tabId: Long,
        userId: Long,
        role: String,
    ) {
        ensureUser(connection, userId)
        connection.prepareStatement(
            """
            INSERT INTO tab_member (tab_id, user_id, role)
            VALUES (?, ?, ?)
            """.trimIndent(),
        ).use { membership ->
            membership.setLong(1, tabId)
            membership.setLong(2, userId)
            membership.setString(3, role)
            membership.executeUpdate()
        }
    }

    private fun h2DataSource(jdbcUrl: String): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(jdbcUrl)
            user = "sa"
            password = ""
        }

    private fun ensureUser(
        connection: java.sql.Connection,
        userId: Long,
    ) {
        val exists =
            connection.prepareStatement(
                """
                SELECT 1 FROM users WHERE telegram_user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { it.next() }
            }
        if (exists) {
            return
        }
        connection.prepareStatement(
            """
            INSERT INTO users (telegram_user_id)
            VALUES (?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeUpdate()
        }
    }

    private fun countAnalyticsEvents(
        jdbcUrl: String,
        eventType: String,
        venueId: Long,
    ): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM analytics_events WHERE event_type = ? AND venue_id = ?",
            ).use { statement ->
                statement.setString(1, eventType)
                statement.setLong(2, venueId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getInt(1)
                    }
                }
            }
        }
        return 0
    }

    private fun seedSubscription(
        jdbcUrl: String,
        venueId: Long,
        status: String,
    ) {
        val now = Instant.now()
        val trialEnd = now.plus(14, ChronoUnit.DAYS)
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, status.uppercase())
                statement.setTimestamp(3, Timestamp.from(trialEnd))
                statement.setTimestamp(4, Timestamp.from(now))
                statement.setTimestamp(5, Timestamp.from(now))
                statement.executeUpdate()
            }
        }
    }

    private fun seedInvoice(
        jdbcUrl: String,
        venueId: Long,
        status: String,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO billing_invoices (
                    venue_id,
                    period_start,
                    period_end,
                    due_at,
                    amount_minor,
                    currency,
                    description,
                    provider,
                    provider_invoice_id,
                    payment_url,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                val today = LocalDate.now()
                statement.setLong(1, venueId)
                statement.setDate(2, java.sql.Date.valueOf(today))
                statement.setDate(3, java.sql.Date.valueOf(today.plusDays(30)))
                statement.setTimestamp(4, Timestamp.from(Instant.now().plusSeconds(86_400)))
                statement.setInt(5, 12000)
                statement.setString(6, "RUB")
                statement.setString(7, "Subscription")
                statement.setString(8, "FAKE")
                statement.setString(9, "fake-invoice-$venueId")
                statement.setString(10, "fake://invoice/fake-invoice-$venueId")
                statement.setString(11, status)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert invoice")
    }

    private fun seedMenuCategory(
        jdbcUrl: String,
        venueId: Long,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO menu_categories (venue_id, name, sort_order)
                VALUES (?, ?, 0)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, "Category")
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert category")
    }

    private fun seedMenuItem(
        jdbcUrl: String,
        venueId: Long,
        categoryId: Long,
        name: String,
        priceMinor: Long = 100,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
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
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert item")
    }

    private fun setMenuCategoryType(
        jdbcUrl: String,
        categoryId: Long,
        categoryType: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                UPDATE menu_categories
                SET category_type = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, categoryType)
                statement.setLong(2, categoryId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedHappyHoursRule(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        discountPercent: Int,
        status: String,
        promotionStatus: String = "ACTIVE",
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            ensureUser(connection, userId)
            val promotionId =
                connection.prepareStatement(
                    """
                    INSERT INTO venue_promotions (
                        venue_id,
                        title,
                        description,
                        terms,
                        status,
                        created_by_user_id,
                        template_type
                    )
                    VALUES (?, 'Счастливые часы', 'Скидка на кальяны', NULL, ?, ?, 'HAPPY_HOURS_PERCENT')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setString(2, promotionStatus)
                    statement.setLong(3, userId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert promotion")
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO promotion_rules (
                    promotion_id,
                    venue_id,
                    rule_type,
                    target_type,
                    target_value,
                    discount_percent,
                    starts_time,
                    ends_time,
                    days_of_week,
                    status,
                    priority,
                    created_by_user_id
                )
                VALUES (?, ?, 'HAPPY_HOURS_PERCENT', 'CATEGORY_TYPE', 'HOOKAH', ?, NULL, NULL, NULL, ?, 100, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, promotionId)
                statement.setLong(2, venueId)
                statement.setInt(3, discountPercent)
                statement.setString(4, status)
                statement.setLong(5, userId)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert promotion rule")
    }

    private fun seedGiftWithItemRule(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        rewardMenuItemId: Long,
        status: String,
        promotionStatus: String = "ACTIVE",
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            ensureUser(connection, userId)
            val promotionId =
                connection.prepareStatement(
                    """
                    INSERT INTO venue_promotions (
                        venue_id,
                        title,
                        description,
                        terms,
                        status,
                        created_by_user_id,
                        template_type
                    )
                    VALUES (?, 'Чай к кальяну', 'Подарок к позиции', NULL, ?, ?, 'GIFT_WITH_ITEM')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setString(2, promotionStatus)
                    statement.setLong(3, userId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert promotion")
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
                        discount_percent,
                        starts_time,
                        ends_time,
                        days_of_week,
                        status,
                        priority,
                        created_by_user_id
                    )
                    VALUES (?, ?, 'GIFT_WITH_ITEM', 'CATEGORY_TYPE', 'HOOKAH', NULL, NULL, NULL, NULL, ?, 100, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, promotionId)
                    statement.setLong(2, venueId)
                    statement.setString(3, status)
                    statement.setLong(4, userId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert promotion rule")
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO promotion_rule_targets (rule_id, target_type, semantic_type, menu_item_id)
                VALUES (?, 'CATEGORY_TYPE', 'HOOKAH', NULL)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, ruleId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO promotion_rule_rewards (rule_id, reward_menu_item_id, reward_qty, max_rewards_per_batch)
                VALUES (?, ?, 1, 1)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, ruleId)
                statement.setLong(2, rewardMenuItemId)
                statement.executeUpdate()
            }
            return ruleId
        }
    }

    private fun setGiftRulesStackableOnly(jdbcUrl: String) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                UPDATE promotion_rules
                SET stackable = CASE WHEN rule_type = 'GIFT_WITH_ITEM' THEN TRUE ELSE FALSE END
                """.trimIndent(),
            ).use { statement ->
                statement.executeUpdate()
            }
        }
    }

    private fun setAllPromotionRulesStackable(jdbcUrl: String) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                UPDATE promotion_rules
                SET stackable = TRUE
                """.trimIndent(),
            ).use { statement ->
                statement.executeUpdate()
            }
        }
    }

    private fun seedActiveLoyaltyProgramWithReward(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        rewardsAvailable: Int,
        rewardMenuItemIds: List<Long> = emptyList(),
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            ensureUser(connection, userId)
            val programId =
                connection.prepareStatement(
                    """
                    INSERT INTO loyalty_programs (
                        venue_id,
                        program_type,
                        status,
                        nth_value,
                        max_redemptions_per_visit,
                        created_by_user_id
                    )
                    VALUES (?, 'NTH_HOOKAH_FREE', 'ACTIVE', 6, 1, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, userId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert loyalty program")
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO loyalty_program_earn_targets (program_id, target_type, semantic_type)
                VALUES (?, 'CATEGORY_TYPE', 'HOOKAH')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, programId)
                statement.executeUpdate()
            }
            if (rewardMenuItemIds.isEmpty()) {
                connection.prepareStatement(
                    """
                    INSERT INTO loyalty_program_reward_targets (program_id, target_type, semantic_type)
                    VALUES (?, 'CATEGORY_TYPE', 'HOOKAH')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, programId)
                    statement.executeUpdate()
                }
            } else {
                connection.prepareStatement(
                    """
                    INSERT INTO loyalty_program_reward_targets (program_id, target_type, menu_item_id)
                    VALUES (?, 'MENU_ITEM', ?)
                    """.trimIndent(),
                ).use { statement ->
                    rewardMenuItemIds.distinct().forEach { itemId ->
                        statement.setLong(1, programId)
                        statement.setLong(2, itemId)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
            connection.prepareStatement(
                """
                INSERT INTO guest_loyalty_progress (
                    program_id,
                    venue_id,
                    user_id,
                    progress_count,
                    rewards_available,
                    rewards_reserved
                )
                VALUES (?, ?, ?, 0, ?, 0)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, programId)
                statement.setLong(2, venueId)
                statement.setLong(3, userId)
                statement.setInt(4, rewardsAvailable)
                statement.executeUpdate()
            }
            return programId
        }
    }

    private fun fetchLoyaltyRewardsAvailable(
        jdbcUrl: String,
        programId: Long,
        userId: Long,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT rewards_available
                FROM guest_loyalty_progress
                WHERE program_id = ? AND user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, programId)
                statement.setLong(2, userId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt("rewards_available") else 0
                }
            }
        }

    private fun fetchLoyaltyLedgerCount(
        jdbcUrl: String,
        programId: Long,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM guest_loyalty_ledger WHERE program_id = ?").use {
                    statement ->
                statement.setLong(1, programId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun countPromotionApplications(jdbcUrl: String): Int = countRows(jdbcUrl, "order_promotion_applications")

    private fun countPromotionAdjustments(jdbcUrl: String): Int =
        countRows(jdbcUrl, "order_batch_item_promotion_adjustments")

    private fun countBatchItems(
        jdbcUrl: String,
        batchId: Long,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM order_batch_items WHERE order_batch_id = ?").use {
                    statement ->
                statement.setLong(1, batchId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun countRows(
        jdbcUrl: String,
        tableName: String,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM $tableName").use { statement ->
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun fetchOrderBatchSource(
        jdbcUrl: String,
        batchId: Long,
    ): String? {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT source
                FROM order_batches
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, batchId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getString("source")
                    }
                }
            }
        }
        return null
    }

    private fun fetchOrderTableSessionId(
        jdbcUrl: String,
        orderId: Long,
    ): Long? {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT table_session_id
                FROM orders
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, orderId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getLong("table_session_id")
                    }
                }
            }
        }
        return null
    }

    private companion object {
        const val TELEGRAM_USER_ID: Long = 456L
    }
}
