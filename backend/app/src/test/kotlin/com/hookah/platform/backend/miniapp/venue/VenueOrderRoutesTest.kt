package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.get
import io.ktor.client.request.headers
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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VenueOrderRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `manager can update status and audit is recorded`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-status")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val tableId = seedTable(jdbcUrl, venueId, 10)
            seedMenu(jdbcUrl, venueId)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val batchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now().minusSeconds(120))
            seedBatchItem(jdbcUrl, batchId)
            val token = issueToken(config)

            val response =
                client.post("/api/venue/orders/$orderId/status?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"nextStatus":"accepted"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)

            val auditResponse =
                client.get("/api/venue/orders/$orderId/audit?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, auditResponse.status)
            val audit = json.decodeFromString(OrderAuditResponse.serializer(), auditResponse.bodyAsText())
            assertTrue(audit.items.isNotEmpty())
            val entry = audit.items.first()
            assertEquals("STATUS_CHANGE", entry.action)
            assertEquals("new", entry.fromStatus)
            assertEquals("accepted", entry.toStatus)
            assertEquals(1, countAnalyticsEvents(jdbcUrl, "batch_status_changed", venueId))
        }

    @Test
    fun `invalid status transition returns error`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-invalid")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val tableId = seedTable(jdbcUrl, venueId, 1)
            seedMenu(jdbcUrl, venueId)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val batchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now())
            seedBatchItem(jdbcUrl, batchId)
            val token = issueToken(config)

            val response =
                client.post("/api/venue/orders/$orderId/status?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"nextStatus":"delivering"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `mini app status updates notify guest and accepted order can be delivered`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-status-notify")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            seedUser(jdbcUrl, GUEST_USER_ID)
            val tableId = seedTable(jdbcUrl, venueId, 11)
            seedMenu(jdbcUrl, venueId)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val batchId =
                seedBatch(
                    jdbcUrl = jdbcUrl,
                    orderId = orderId,
                    status = "NEW",
                    createdAt = Instant.now().minusSeconds(120),
                    authorUserId = GUEST_USER_ID,
                )
            seedBatchItem(jdbcUrl, batchId)
            val token = issueToken(config)

            val acceptResponse =
                client.post("/api/venue/orders/$orderId/status?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"nextStatus":"accepted"}""")
                }

            assertEquals(HttpStatusCode.OK, acceptResponse.status)
            val accepted = json.decodeFromString(OrderStatusResponse.serializer(), acceptResponse.bodyAsText())
            assertEquals("accepted", accepted.status)
            assertTrue(outboxPayloads(jdbcUrl, GUEST_USER_ID).any { it.contains("Ваш заказ принят") })

            val deliverResponse =
                client.post("/api/venue/orders/$orderId/status?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"nextStatus":"delivered"}""")
                }

            assertEquals(HttpStatusCode.OK, deliverResponse.status)
            val delivered = json.decodeFromString(OrderStatusResponse.serializer(), deliverResponse.bodyAsText())
            assertEquals("delivered", delivered.status)
            assertTrue(outboxPayloads(jdbcUrl, GUEST_USER_ID).any { it.contains("Ваш заказ доставлен") })
        }

    @Test
    fun `manager can close delivered order and guest is notified`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-status-close-notify")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            seedUser(jdbcUrl, GUEST_USER_ID)
            val tableId = seedTable(jdbcUrl, venueId, 12)
            seedMenu(jdbcUrl, venueId)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val batchId =
                seedBatch(
                    jdbcUrl = jdbcUrl,
                    orderId = orderId,
                    status = "DELIVERED",
                    createdAt = Instant.now().minusSeconds(120),
                    authorUserId = GUEST_USER_ID,
                )
            seedBatchItem(jdbcUrl, batchId)
            val token = issueToken(config)

            val response =
                client.post("/api/venue/orders/$orderId/status?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"nextStatus":"closed"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val closed = json.decodeFromString(OrderStatusResponse.serializer(), response.bodyAsText())
            assertEquals("closed", closed.status)
            assertTrue(outboxPayloads(jdbcUrl, GUEST_USER_ID).any { it.contains("Счёт закрыт") })
        }

    @Test
    fun `manager can close delivered order through close endpoint`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-close-endpoint")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            seedUser(jdbcUrl, GUEST_USER_ID)
            val tableId = seedTable(jdbcUrl, venueId, 13)
            seedMenu(jdbcUrl, venueId)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val batchId =
                seedBatch(
                    jdbcUrl = jdbcUrl,
                    orderId = orderId,
                    status = "DELIVERED",
                    createdAt = Instant.now().minusSeconds(120),
                    authorUserId = GUEST_USER_ID,
                )
            seedBatchItem(jdbcUrl, batchId)
            val token = issueToken(config)

            val response =
                client.post("/api/venue/orders/$orderId/close?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val closed = json.decodeFromString(OrderStatusResponse.serializer(), response.bodyAsText())
            assertEquals("closed", closed.status)
            assertTrue(outboxPayloads(jdbcUrl, GUEST_USER_ID).any { it.contains("Счёт закрыт") })

            val queueResponse =
                client.get("/api/venue/orders/queue?venueId=$venueId&status=delivered&limit=10") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, queueResponse.status)
            val queue = json.decodeFromString(OrdersQueueResponse.serializer(), queueResponse.bodyAsText())
            assertTrue(queue.items.none { it.batchId == batchId })
        }

    @Test
    fun `staff can close delivered order through close endpoint`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-close-staff")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val tableId = seedTable(jdbcUrl, venueId, 17)
            seedMenu(jdbcUrl, venueId)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val batchId = seedBatch(jdbcUrl, orderId, "DELIVERED", Instant.now().minusSeconds(120))
            seedBatchItem(jdbcUrl, batchId)
            val token = issueToken(config)

            val response =
                client.post("/api/venue/orders/$orderId/close?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val closed = json.decodeFromString(OrderStatusResponse.serializer(), response.bodyAsText())
            assertEquals("closed", closed.status)

            val detailResponse =
                client.get("/api/venue/orders/$orderId?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, detailResponse.status)
            val detail = json.decodeFromString(OrderDetailResponse.serializer(), detailResponse.bodyAsText())
            assertEquals("closed", detail.order.status)
        }

    @Test
    fun `staff cannot reject orders`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-reject")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val tableId = seedTable(jdbcUrl, venueId, 2)
            seedMenu(jdbcUrl, venueId)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val batchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now())
            seedBatchItem(jdbcUrl, batchId)
            val token = issueToken(config)

            val response =
                client.post("/api/venue/orders/$orderId/reject?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"reasonCode":"guest_request"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `queue pagination returns newest first`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-queue")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val tableId = seedTable(jdbcUrl, venueId, 5)
            seedMenu(jdbcUrl, venueId)
            val olderOrderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE", displayNumber = 41)
            val olderBatchId = seedBatch(jdbcUrl, olderOrderId, "NEW", Instant.now().minusSeconds(300))
            val newerOrderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE", displayNumber = 42)
            val newerBatchId = seedBatch(jdbcUrl, newerOrderId, "NEW", Instant.now().minusSeconds(10))
            seedBatchItem(jdbcUrl, olderBatchId)
            seedBatchItem(jdbcUrl, newerBatchId)
            val token = issueToken(config)

            val firstPage =
                client.get("/api/venue/orders/queue?venueId=$venueId&status=new&limit=1") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, firstPage.status)
            val payload = json.decodeFromString(OrdersQueueResponse.serializer(), firstPage.bodyAsText())
            assertEquals(1, payload.items.size)
            assertEquals(newerOrderId, payload.items.first().orderId)
            assertEquals(newerBatchId, payload.items.first().batchId)
            assertEquals(42, payload.items.first().displayNumber)
            val cursor = payload.nextCursor
            assertNotNull(cursor)

            val secondPage =
                client.get(
                    "/api/venue/orders/queue?venueId=$venueId&status=new&limit=1&cursor=$cursor",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, secondPage.status)
            val payload2 = json.decodeFromString(OrdersQueueResponse.serializer(), secondPage.bodyAsText())
            assertEquals(1, payload2.items.size)
            assertEquals(olderOrderId, payload2.items.first().orderId)
            assertEquals(olderBatchId, payload2.items.first().batchId)
        }

    @Test
    fun `queue lists active orders from different sessions at same table`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-queue-same-table-sessions")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val tableId = seedTable(jdbcUrl, venueId, 12)
            seedMenu(jdbcUrl, venueId)
            val firstOrderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val secondOrderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val firstBatchId = seedBatch(jdbcUrl, firstOrderId, "NEW", Instant.now().minusSeconds(60))
            val secondBatchId = seedBatch(jdbcUrl, secondOrderId, "NEW", Instant.now())
            seedBatchItem(jdbcUrl, firstBatchId)
            seedBatchItem(jdbcUrl, secondBatchId)
            val token = issueToken(config)

            val response =
                client.get("/api/venue/orders/queue?venueId=$venueId&status=new&limit=10") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(OrdersQueueResponse.serializer(), response.bodyAsText())
            val batchIds = payload.items.map { it.batchId }.toSet()
            assertEquals(setOf(firstBatchId, secondBatchId), batchIds)
        }

    @Test
    fun `queue all returns one card per order with multiple active batches`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-queue-all-one-card")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val tableId = seedTable(jdbcUrl, venueId, 18)
            seedMenu(jdbcUrl, venueId)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE", displayNumber = 77)
            val firstBatchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now().minusSeconds(60))
            val secondBatchId = seedBatch(jdbcUrl, orderId, "ACCEPTED", Instant.now())
            seedBatchItem(jdbcUrl, firstBatchId)
            seedBatchItem(jdbcUrl, secondBatchId)
            val token = issueToken(config)

            val response =
                client.get("/api/venue/orders/queue?venueId=$venueId&status=all&limit=10") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(OrdersQueueResponse.serializer(), response.bodyAsText())
            assertEquals(1, payload.items.size)
            val item = payload.items.single()
            assertEquals(orderId, item.orderId)
            assertEquals(secondBatchId, item.batchId)
            assertEquals(77, item.displayNumber)
            assertEquals(2, item.activeBatchesCount)
        }

    @Test
    fun `queue status filter returns one card per order with multiple matching batches`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-queue-status-one-card")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val tableId = seedTable(jdbcUrl, venueId, 19)
            seedMenu(jdbcUrl, venueId)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE", displayNumber = 78)
            val firstBatchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now().minusSeconds(60))
            val secondBatchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now())
            seedBatchItem(jdbcUrl, firstBatchId)
            seedBatchItem(jdbcUrl, secondBatchId)
            val token = issueToken(config)

            val response =
                client.get("/api/venue/orders/queue?venueId=$venueId&status=new&limit=10") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(OrdersQueueResponse.serializer(), response.bodyAsText())
            assertEquals(1, payload.items.size)
            val item = payload.items.single()
            assertEquals(orderId, item.orderId)
            assertEquals(secondBatchId, item.batchId)
            assertEquals(78, item.displayNumber)
            assertEquals(2, item.activeBatchesCount)
        }

    @Test
    fun `order detail returns batches and items`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-detail")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val tableId = seedTable(jdbcUrl, venueId, 3)
            seedMenu(jdbcUrl, venueId)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val batchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now())
            seedBatchItem(jdbcUrl, batchId)
            val token = issueToken(config)

            val response =
                client.get("/api/venue/orders/$orderId?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(OrderDetailResponse.serializer(), response.bodyAsText())
            assertEquals("new", payload.order.status)
            assertTrue(payload.order.batches.isNotEmpty())
            val firstBatch = payload.order.batches.first()
            assertEquals(batchId, firstBatch.batchId)
            assertTrue(firstBatch.items.isNotEmpty())
        }

    @Test
    fun `order detail returns full bill totals discounts and non payable items`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-detail-bill")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val tableId = seedTable(jdbcUrl, venueId, 9)
            val normalItemId = seedMenu(jdbcUrl, venueId, "Кальян обычный", 110_000)
            val loyaltyItemId = seedMenu(jdbcUrl, venueId, "Кальян бонусный", 50_000)
            val excludedItemId = seedMenu(jdbcUrl, venueId, "Чай", 30_000)
            val canceledItemId = seedMenu(jdbcUrl, venueId, "Лимонад", 40_000)
            val rejectedItemId = seedMenu(jdbcUrl, venueId, "Закуска", 70_000)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE", displayNumber = 42)
            val activeBatchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now())
            val normalBatchItemId =
                seedBatchItem(
                    jdbcUrl = jdbcUrl,
                    batchId = activeBatchId,
                    menuItemId = normalItemId,
                    discountPercent = 10,
                )
            val loyaltyBatchItemId =
                seedBatchItem(
                    jdbcUrl = jdbcUrl,
                    batchId = activeBatchId,
                    menuItemId = loyaltyItemId,
                )
            seedBatchItem(
                jdbcUrl = jdbcUrl,
                batchId = activeBatchId,
                menuItemId = excludedItemId,
                isExcluded = true,
                excludedReasonText = "Не учитывать",
            )
            seedBatchItem(
                jdbcUrl = jdbcUrl,
                batchId = activeBatchId,
                menuItemId = canceledItemId,
                itemStatus = "CANCELED",
                canceledReasonText = "Нет в наличии",
            )
            val rejectedBatchId = seedBatch(jdbcUrl, orderId, "REJECTED", Instant.now().plusSeconds(1))
            markBatchRejected(jdbcUrl, rejectedBatchId, "NO_STOCK", "Нет позиции")
            seedBatchItem(
                jdbcUrl = jdbcUrl,
                batchId = rejectedBatchId,
                menuItemId = rejectedItemId,
            )
            seedPromotionAdjustment(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                orderId = orderId,
                batchId = activeBatchId,
                batchItemId = normalBatchItemId,
                menuItemId = normalItemId,
                title = "Счастливые часы",
                ruleType = "HAPPY_HOURS_PERCENT",
                discountMinor = 11_000,
                discountPercent = 10,
                originalPriceMinor = 110_000,
            )
            seedPromotionAdjustment(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                orderId = orderId,
                batchId = activeBatchId,
                batchItemId = loyaltyBatchItemId,
                menuItemId = loyaltyItemId,
                title = "Лояльность: бесплатный кальян",
                ruleType = "LOYALTY_NTH_HOOKAH",
                discountMinor = 50_000,
                discountPercent = null,
                originalPriceMinor = 50_000,
            )
            val token = issueToken(config)

            val response =
                client.get("/api/venue/orders/$orderId?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(OrderDetailResponse.serializer(), response.bodyAsText())
            assertEquals(42, payload.order.displayNumber)
            val bill = payload.order.bill
            assertEquals(160_000, bill.grossTotalMinor)
            assertEquals(11_000, bill.manualDiscountTotalMinor)
            assertEquals(11_000, bill.promoDiscountTotalMinor)
            assertEquals(50_000, bill.loyaltyDiscountTotalMinor)
            assertEquals(30_000, bill.excludedTotalMinor)
            assertEquals(40_000, bill.canceledTotalMinor)
            assertEquals(70_000, bill.rejectedTotalMinor)
            assertEquals(88_000, bill.finalPayableTotalMinor)
            assertEquals(listOf("Счастливые часы"), bill.promoDiscounts.map { it.label })
            assertEquals(listOf("Лояльность: бесплатный кальян"), bill.loyaltyDiscounts.map { it.label })
            assertEquals(setOf("excluded", "canceled", "rejected_batch"), bill.excludedItems.map { it.status }.toSet())
            val excludedByStatus = bill.excludedItems.associateBy { it.status }
            val excludedItem = assertNotNull(excludedByStatus["excluded"])
            assertEquals("Чай", excludedItem.name)
            assertEquals(30_000, excludedItem.lineGrossMinor)
            assertEquals("Не учитывать", excludedItem.reason)
            val canceledItem = assertNotNull(excludedByStatus["canceled"])
            assertEquals("Лимонад", canceledItem.name)
            assertEquals(40_000, canceledItem.lineGrossMinor)
            assertEquals("Нет в наличии", canceledItem.reason)
            val rejectedItem = assertNotNull(excludedByStatus["rejected_batch"])
            assertEquals("Закуска", rejectedItem.name)
            assertEquals(70_000, rejectedItem.lineGrossMinor)
            assertEquals("Нет позиции", rejectedItem.reason)
            val normalItem = payload.order.batches.first { it.batchId == activeBatchId }.items.first { it.itemId == normalItemId }
            assertEquals(110_000, normalItem.priceMinor)
            assertEquals("RUB", normalItem.currency)
            assertEquals(110_000, normalItem.lineGrossMinor)
            assertEquals(11_000, normalItem.manualDiscountMinor)
            assertEquals(11_000, normalItem.promoDiscountMinor)
            assertEquals(88_000, normalItem.linePayableMinor)
            assertEquals(10, normalItem.discountPercent)
            val loyaltyItem = payload.order.batches.first { it.batchId == activeBatchId }.items.first { it.itemId == loyaltyItemId }
            assertEquals(50_000, loyaltyItem.lineGrossMinor)
            assertEquals(0, loyaltyItem.manualDiscountMinor)
            assertEquals(50_000, loyaltyItem.promoDiscountMinor)
            assertEquals(0, loyaltyItem.linePayableMinor)
        }

    @Test
    fun `manager can edit bill item discount exclusion and restore`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-bill-edit-manager")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val tableId = seedTable(jdbcUrl, venueId, 14)
            val itemId = seedMenu(jdbcUrl, venueId, "Кальян", 10_000)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val batchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now())
            val batchItemId = seedBatchItem(jdbcUrl, batchId, itemId)
            val token = issueToken(config)

            val discountResponse =
                client.post("/api/venue/orders/$orderId/items/$batchItemId/discount?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"discountPercent":25}""")
                }

            assertEquals(HttpStatusCode.OK, discountResponse.status)
            val discounted = json.decodeFromString(OrderBillItemAdjustmentResponse.serializer(), discountResponse.bodyAsText())
            val discountedItem = discounted.order.batches.single().items.single()
            assertEquals(25, discountedItem.discountPercent)
            assertEquals(2_500, discountedItem.manualDiscountMinor)
            assertEquals(7_500, discounted.order.bill.finalPayableTotalMinor)

            val excludeResponse =
                client.post("/api/venue/orders/$orderId/items/$batchItemId/exclude?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"reasonText":"Гость попросил убрать"}""")
                }

            assertEquals(HttpStatusCode.OK, excludeResponse.status)
            val excluded = json.decodeFromString(OrderBillItemAdjustmentResponse.serializer(), excludeResponse.bodyAsText())
            assertEquals(0, excluded.order.bill.finalPayableTotalMinor)
            assertEquals(10_000, excluded.order.bill.excludedTotalMinor)
            val excludedItem = excluded.order.batches.single().items.single()
            assertTrue(excludedItem.isExcluded)
            assertEquals("Гость попросил убрать", excludedItem.excludedReasonText)

            val restoreResponse =
                client.post("/api/venue/orders/$orderId/items/$batchItemId/restore?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, restoreResponse.status)
            val restored = json.decodeFromString(OrderBillItemAdjustmentResponse.serializer(), restoreResponse.bodyAsText())
            assertEquals(0, restored.order.bill.excludedTotalMinor)
            assertEquals(7_500, restored.order.bill.finalPayableTotalMinor)
            assertEquals(false, restored.order.batches.single().items.single().isExcluded)

            val removeDiscountResponse =
                client.post("/api/venue/orders/$orderId/items/$batchItemId/discount?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"discountPercent":0}""")
                }

            assertEquals(HttpStatusCode.OK, removeDiscountResponse.status)
            val withoutDiscount =
                json.decodeFromString(OrderBillItemAdjustmentResponse.serializer(), removeDiscountResponse.bodyAsText())
            assertEquals(10_000, withoutDiscount.order.bill.finalPayableTotalMinor)
            assertEquals(0, withoutDiscount.order.bill.manualDiscountTotalMinor)
            assertEquals(null, withoutDiscount.order.batches.single().items.single().discountPercent)
        }

    @Test
    fun `bill item adjustments do not leak to another order with same menu item`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-bill-edit-scoped")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val tableId = seedTable(jdbcUrl, venueId, 21)
            val itemId = seedMenu(jdbcUrl, venueId, "Кальян", 10_000)
            val firstOrderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE", displayNumber = 101)
            val firstBatchId = seedBatch(jdbcUrl, firstOrderId, "NEW", Instant.now().minusSeconds(60))
            val firstBatchItemId = seedBatchItem(jdbcUrl, firstBatchId, itemId)
            val secondOrderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE", displayNumber = 102)
            val secondBatchId = seedBatch(jdbcUrl, secondOrderId, "NEW", Instant.now())
            val secondBatchItemId = seedBatchItem(jdbcUrl, secondBatchId, itemId)
            val token = issueToken(config)

            val discountResponse =
                client.post("/api/venue/orders/$firstOrderId/items/$firstBatchItemId/discount?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"discountPercent":50}""")
                }
            assertEquals(HttpStatusCode.OK, discountResponse.status)

            val excludeResponse =
                client.post("/api/venue/orders/$firstOrderId/items/$firstBatchItemId/exclude?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"reasonText":"Тестовое исключение"}""")
                }
            assertEquals(HttpStatusCode.OK, excludeResponse.status)

            val secondDetailResponse =
                client.get("/api/venue/orders/$secondOrderId?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, secondDetailResponse.status)
            val secondDetail =
                json.decodeFromString(OrderDetailResponse.serializer(), secondDetailResponse.bodyAsText())
            assertEquals(10_000, secondDetail.order.bill.grossTotalMinor)
            assertEquals(0, secondDetail.order.bill.manualDiscountTotalMinor)
            assertEquals(0, secondDetail.order.bill.excludedTotalMinor)
            assertEquals(10_000, secondDetail.order.bill.finalPayableTotalMinor)
            val secondItem = secondDetail.order.batches.single().items.single { it.batchItemId == secondBatchItemId }
            assertEquals(false, secondItem.isExcluded)
            assertEquals(null, secondItem.excludedReasonText)
            assertEquals(null, secondItem.discountPercent)
            assertEquals(0, secondItem.manualDiscountMinor)
        }

    @Test
    fun `closing one order does not close another active order`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-close-scoped")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val tableId = seedTable(jdbcUrl, venueId, 22)
            seedMenu(jdbcUrl, venueId)
            val firstOrderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE", displayNumber = 201)
            val firstBatchId = seedBatch(jdbcUrl, firstOrderId, "DELIVERED", Instant.now().minusSeconds(60))
            seedBatchItem(jdbcUrl, firstBatchId)
            val secondOrderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE", displayNumber = 202)
            val secondBatchId = seedBatch(jdbcUrl, secondOrderId, "DELIVERED", Instant.now())
            seedBatchItem(jdbcUrl, secondBatchId)
            val token = issueToken(config)

            val closeResponse =
                client.post("/api/venue/orders/$firstOrderId/close?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, closeResponse.status)

            val firstDetailResponse =
                client.get("/api/venue/orders/$firstOrderId?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            val secondDetailResponse =
                client.get("/api/venue/orders/$secondOrderId?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, firstDetailResponse.status)
            assertEquals(HttpStatusCode.OK, secondDetailResponse.status)
            val firstDetail =
                json.decodeFromString(OrderDetailResponse.serializer(), firstDetailResponse.bodyAsText())
            val secondDetail =
                json.decodeFromString(OrderDetailResponse.serializer(), secondDetailResponse.bodyAsText())
            assertEquals("closed", firstDetail.order.status)
            assertEquals("delivered", secondDetail.order.status)
        }

    @Test
    fun `owner can edit bill item`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-bill-edit-owner")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val tableId = seedTable(jdbcUrl, venueId, 15)
            val itemId = seedMenu(jdbcUrl, venueId, "Чай", 5_000)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val batchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now())
            val batchItemId = seedBatchItem(jdbcUrl, batchId, itemId)
            val token = issueToken(config)

            val discountResponse =
                client.post("/api/venue/orders/$orderId/items/$batchItemId/discount?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"discountPercent":10}""")
                }

            assertEquals(HttpStatusCode.OK, discountResponse.status)
            val discounted = json.decodeFromString(OrderBillItemAdjustmentResponse.serializer(), discountResponse.bodyAsText())
            assertEquals(4_500, discounted.order.bill.finalPayableTotalMinor)

            val excludeResponse =
                client.post("/api/venue/orders/$orderId/items/$batchItemId/exclude?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"reasonText":"Комплимент"}""")
                }

            assertEquals(HttpStatusCode.OK, excludeResponse.status)
            val excluded = json.decodeFromString(OrderBillItemAdjustmentResponse.serializer(), excludeResponse.bodyAsText())
            assertEquals(0, excluded.order.bill.finalPayableTotalMinor)
            assertEquals(5_000, excluded.order.bill.excludedTotalMinor)
        }

    @Test
    fun `staff cannot edit bill items`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-bill-edit-staff")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val tableId = seedTable(jdbcUrl, venueId, 16)
            val itemId = seedMenu(jdbcUrl, venueId, "Кальян", 10_000)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val batchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now())
            val batchItemId = seedBatchItem(jdbcUrl, batchId, itemId)
            val token = issueToken(config)

            val discountResponse =
                client.post("/api/venue/orders/$orderId/items/$batchItemId/discount?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"discountPercent":10}""")
                }
            assertEquals(HttpStatusCode.Forbidden, discountResponse.status)
            assertApiErrorEnvelope(discountResponse, ApiErrorCodes.FORBIDDEN)

            val excludeResponse =
                client.post("/api/venue/orders/$orderId/items/$batchItemId/exclude?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"reasonText":"test"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, excludeResponse.status)
            assertApiErrorEnvelope(excludeResponse, ApiErrorCodes.FORBIDDEN)

            val restoreResponse =
                client.post("/api/venue/orders/$orderId/items/$batchItemId/restore?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.Forbidden, restoreResponse.status)
            assertApiErrorEnvelope(restoreResponse, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `order audit returns empty list when no entries`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-audit-empty")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val tableId = seedTable(jdbcUrl, venueId, 6)
            seedMenu(jdbcUrl, venueId)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val batchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now())
            seedBatchItem(jdbcUrl, batchId)
            val token = issueToken(config)

            val response =
                client.get("/api/venue/orders/$orderId/audit?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(OrderAuditResponse.serializer(), response.bodyAsText())
            assertTrue(payload.items.isEmpty())
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

    private fun issueToken(config: MapApplicationConfig): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(TELEGRAM_USER_ID).token
    }

    private fun seedVenueWithRole(
        jdbcUrl: String,
        userId: Long,
        role: String,
    ): Long {
        seedUser(jdbcUrl, userId)
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val venueId =
                connection.prepareStatement(
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
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
            return venueId
        }
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

    private fun seedTable(
        jdbcUrl: String,
        venueId: Long,
        tableNumber: Int,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            return connection.prepareStatement(
                """
                INSERT INTO venue_tables (venue_id, table_number, is_active)
                VALUES (?, ?, true)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setInt(2, tableNumber)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert table")
                }
            }
        }
    }

    private fun seedMenu(
        jdbcUrl: String,
        venueId: Long,
        itemName: String = "Item",
        priceMinor: Long = 100,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val categoryId =
                connection.prepareStatement(
                    """
                    INSERT INTO menu_categories (venue_id, name, sort_order)
                    VALUES (?, 'Category', 0)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert category")
                    }
                }
            return connection.prepareStatement(
                """
                INSERT INTO menu_items (venue_id, category_id, name, price_minor, currency, is_available)
                VALUES (?, ?, ?, ?, 'RUB', true)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, categoryId)
                statement.setString(3, itemName)
                statement.setLong(4, priceMinor)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert menu item")
                }
            }
        }
    }

    private fun seedOrder(
        jdbcUrl: String,
        venueId: Long,
        tableId: Long,
        status: String,
        displayNumber: Int = 1,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val now = Instant.now()
            val tableSessionId =
                connection.prepareStatement(
                    """
                    INSERT INTO table_sessions (
                        venue_id, table_id, started_at, last_activity_at, expires_at, ended_at, status
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, tableId)
                    statement.setTimestamp(3, Timestamp.from(now))
                    statement.setTimestamp(4, Timestamp.from(now))
                    statement.setTimestamp(5, Timestamp.from(now.plusSeconds(7200)))
                    if (status == "ACTIVE") {
                        statement.setNull(6, java.sql.Types.TIMESTAMP)
                        statement.setString(7, "ACTIVE")
                    } else {
                        statement.setTimestamp(6, Timestamp.from(now))
                        statement.setString(7, "ENDED")
                    }
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert table session")
                    }
                }
            return connection.prepareStatement(
                """
                INSERT INTO orders (venue_id, table_id, table_session_id, status, display_number, display_date)
                VALUES (?, ?, ?, ?, ?, CURRENT_DATE)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, tableId)
                statement.setLong(3, tableSessionId)
                statement.setString(4, status)
                statement.setInt(5, displayNumber)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert order")
                }
            }
        }
    }

    private fun seedBatch(
        jdbcUrl: String,
        orderId: Long,
        status: String,
        createdAt: Instant,
        authorUserId: Long? = null,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            return connection.prepareStatement(
                """
                INSERT INTO order_batches (
                    order_id, created_at, updated_at, author_user_id, source, status, guest_comment
                ) VALUES (?, ?, ?, ?, 'MINIAPP', ?, NULL)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                val ts = Timestamp.from(createdAt)
                statement.setLong(1, orderId)
                statement.setTimestamp(2, ts)
                statement.setTimestamp(3, ts)
                if (authorUserId == null) {
                    statement.setNull(4, java.sql.Types.BIGINT)
                } else {
                    statement.setLong(4, authorUserId)
                }
                statement.setString(5, status)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert batch")
                }
            }
        }
    }

    private fun outboxPayloads(
        jdbcUrl: String,
        chatId: Long,
    ): List<String> {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            return connection.prepareStatement(
                """
                SELECT payload_json
                FROM telegram_outbox
                WHERE chat_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, chatId)
                statement.executeQuery().use { rs ->
                    val result = mutableListOf<String>()
                    while (rs.next()) {
                        result.add(rs.getString("payload_json"))
                    }
                    result
                }
            }
        }
    }

    private fun seedBatchItem(
        jdbcUrl: String,
        batchId: Long,
        menuItemId: Long? = null,
        qty: Int = 1,
        isExcluded: Boolean = false,
        excludedReasonText: String? = null,
        discountPercent: Int? = null,
        itemStatus: String = "ACTIVE",
        canceledReasonText: String? = null,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val resolvedMenuItemId =
                menuItemId ?:
                connection.prepareStatement(
                    "SELECT id FROM menu_items LIMIT 1",
                ).use { statement ->
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Missing menu item")
                    }
                }
            return connection.prepareStatement(
                """
                INSERT INTO order_batch_items (
                    order_batch_id, menu_item_id, qty, is_excluded, excluded_reason_text,
                    discount_percent, item_status, canceled_reason_text
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, batchId)
                statement.setLong(2, resolvedMenuItemId)
                statement.setInt(3, qty)
                statement.setBoolean(4, isExcluded)
                statement.setString(5, excludedReasonText)
                if (discountPercent == null) {
                    statement.setNull(6, java.sql.Types.INTEGER)
                } else {
                    statement.setInt(6, discountPercent)
                }
                statement.setString(7, itemStatus)
                statement.setString(8, canceledReasonText)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert batch item")
                }
            }
        }
    }

    private fun markBatchRejected(
        jdbcUrl: String,
        batchId: Long,
        reasonCode: String,
        reasonText: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                UPDATE order_batches
                SET rejected_reason_code = ?, rejected_reason_text = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, reasonCode)
                statement.setString(2, reasonText)
                statement.setLong(3, batchId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedPromotionAdjustment(
        jdbcUrl: String,
        venueId: Long,
        orderId: Long,
        batchId: Long,
        batchItemId: Long,
        menuItemId: Long,
        title: String,
        ruleType: String,
        discountMinor: Long,
        discountPercent: Int?,
        originalPriceMinor: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val promotionId =
                connection.prepareStatement(
                    """
                    INSERT INTO venue_promotions (
                        venue_id, title, description, status, created_by_user_id, template_type
                    )
                    VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setString(2, title)
                    statement.setString(3, title)
                    statement.setLong(4, TELEGRAM_USER_ID)
                    statement.setString(5, ruleType)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert promotion")
                    }
                }
            val ruleId =
                connection.prepareStatement(
                    """
                    INSERT INTO promotion_rules (
                        promotion_id, venue_id, rule_type, target_type, target_value,
                        discount_percent, status, created_by_user_id
                    )
                    VALUES (?, ?, ?, 'CATEGORY_TYPE', 'HOOKAH', ?, 'ACTIVE', ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, promotionId)
                    statement.setLong(2, venueId)
                    statement.setString(3, ruleType)
                    if (discountPercent == null) {
                        statement.setNull(4, java.sql.Types.INTEGER)
                    } else {
                        statement.setInt(4, discountPercent)
                    }
                    statement.setLong(5, TELEGRAM_USER_ID)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert rule")
                    }
                }
            val applicationId =
                connection.prepareStatement(
                    """
                    INSERT INTO order_promotion_applications (
                        order_id, batch_id, venue_id, user_id, promotion_id, rule_id,
                        title_snapshot, rule_type, target_type, target_value,
                        discount_percent, discount_total_minor, currency, dedupe_key
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CATEGORY_TYPE', 'HOOKAH', ?, ?, 'RUB', ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, orderId)
                    statement.setLong(2, batchId)
                    statement.setLong(3, venueId)
                    statement.setLong(4, TELEGRAM_USER_ID)
                    statement.setLong(5, promotionId)
                    statement.setLong(6, ruleId)
                    statement.setString(7, title)
                    statement.setString(8, ruleType)
                    if (discountPercent == null) {
                        statement.setNull(9, java.sql.Types.INTEGER)
                    } else {
                        statement.setInt(9, discountPercent)
                    }
                    statement.setLong(10, discountMinor)
                    statement.setString(11, "test:$batchItemId:$ruleType")
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert promotion application")
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO order_batch_item_promotion_adjustments (
                    application_id, order_batch_item_id, menu_item_id, discount_minor,
                    discount_percent, original_price_minor, quantity, currency
                )
                VALUES (?, ?, ?, ?, ?, ?, 1, 'RUB')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, applicationId)
                statement.setLong(2, batchItemId)
                statement.setLong(3, menuItemId)
                statement.setLong(4, discountMinor)
                statement.setInt(5, discountPercent ?: 100)
                statement.setLong(6, originalPriceMinor)
                statement.executeUpdate()
            }
        }
    }

    @Serializable
    private data class OrdersQueueResponse(
        val items: List<OrderQueueItemDto>,
        val nextCursor: String?,
    )

    @Serializable
    private data class OrderQueueItemDto(
        val orderId: Long = 0,
        val batchId: Long,
        val displayNumber: Int? = null,
        val activeBatchesCount: Int = 1,
    )

    @Serializable
    private data class OrderDetailResponse(
        val order: OrderDetailDto,
    )

    @Serializable
    private data class OrderDetailDto(
        val displayNumber: Int? = null,
        val status: String,
        val bill: OrderBillDto,
        val batches: List<OrderBatchDto>,
    )

    @Serializable
    private data class OrderBatchDto(
        val batchId: Long,
        val promotionDiscounts: List<OrderBillDiscountDto> = emptyList(),
        val items: List<OrderBatchItemDto>,
    )

    @Serializable
    private data class OrderBatchItemDto(
        val batchItemId: Long = 0,
        val itemId: Long,
        val priceMinor: Long? = null,
        val currency: String? = null,
        val lineGrossMinor: Long = 0,
        val manualDiscountMinor: Long = 0,
        val promoDiscountMinor: Long = 0,
        val linePayableMinor: Long = 0,
        val isExcluded: Boolean = false,
        val excludedReasonText: String? = null,
        val discountPercent: Int? = null,
    )

    @Serializable
    private data class OrderBillDto(
        val grossTotalMinor: Long,
        val manualDiscountTotalMinor: Long,
        val promoDiscountTotalMinor: Long,
        val loyaltyDiscountTotalMinor: Long,
        val excludedTotalMinor: Long,
        val canceledTotalMinor: Long,
        val rejectedTotalMinor: Long,
        val finalPayableTotalMinor: Long,
        val promoDiscounts: List<OrderBillDiscountDto> = emptyList(),
        val loyaltyDiscounts: List<OrderBillDiscountDto> = emptyList(),
        val excludedItems: List<OrderBillExcludedItemDto> = emptyList(),
    )

    @Serializable
    private data class OrderBillDiscountDto(
        val label: String,
    )

    @Serializable
    private data class OrderBillExcludedItemDto(
        val status: String,
        val name: String,
        val lineGrossMinor: Long,
        val reason: String? = null,
    )

    @Serializable
    private data class OrderAuditResponse(
        val items: List<OrderAuditEntryDto>,
    )

    @Serializable
    private data class OrderStatusResponse(
        val status: String,
    )

    @Serializable
    private data class OrderBillItemAdjustmentResponse(
        val order: OrderDetailDto,
    )

    @Serializable
    private data class OrderAuditEntryDto(
        val action: String,
        val fromStatus: String,
        val toStatus: String,
    )

    private companion object {
        const val TELEGRAM_USER_ID: Long = 909L
        const val GUEST_USER_ID: Long = 808L
    }
}
