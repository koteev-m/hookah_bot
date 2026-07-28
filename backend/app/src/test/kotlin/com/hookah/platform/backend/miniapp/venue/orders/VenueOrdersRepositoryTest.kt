package com.hookah.platform.backend.miniapp.venue.orders

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.miniapp.guest.db.VisitRepository
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.telegram.db.LoyaltyProgramStatus
import com.hookah.platform.backend.telegram.db.LoyaltyRepository
import com.hookah.platform.backend.telegram.db.OrdersRepository
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VenueOrdersRepositoryTest {
    @Test
    fun `updateBatchStatus changes only selected batch`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("batch-specific-update")
            val fixture = seedActiveOrder(jdbcUrl)
            val firstBatchId = seedBatch(jdbcUrl, fixture.orderId, "NEW", Instant.now().minusSeconds(60))
            val secondBatchId = seedBatch(jdbcUrl, fixture.orderId, "NEW", Instant.now())
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))

            val result =
                repository.updateBatchStatus(
                    venueId = fixture.venueId,
                    batchId = firstBatchId,
                    expectedCurrentStatus = OrderBatchStatus.NEW,
                    nextStatus = OrderBatchStatus.ACCEPTED,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )

            assertNotNull(result)
            assertTrue(result.applied)
            assertEquals(firstBatchId, result.batchId)
            assertEquals(OrderWorkflowStatus.ACCEPTED, result.status)
            assertEquals("ACCEPTED", fetchBatchStatus(jdbcUrl, firstBatchId))
            assertEquals("NEW", fetchBatchStatus(jdbcUrl, secondBatchId))
        }

    @Test
    fun `updateBatchStatus with wrong expected status does not update`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("batch-stale-update")
            val fixture = seedActiveOrder(jdbcUrl)
            val batchId = seedBatch(jdbcUrl, fixture.orderId, "ACCEPTED", Instant.now())
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))

            val result =
                repository.updateBatchStatus(
                    venueId = fixture.venueId,
                    batchId = batchId,
                    expectedCurrentStatus = OrderBatchStatus.NEW,
                    nextStatus = OrderBatchStatus.ACCEPTED,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )

            assertNotNull(result)
            assertFalse(result.applied)
            assertEquals(OrderWorkflowStatus.ACCEPTED, result.status)
            assertEquals("ACCEPTED", fetchBatchStatus(jdbcUrl, batchId))
        }

    @Test
    fun `listOperationalQueueByOrder includes delivered active order`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("delivered-active-queue")
            val fixture = seedActiveOrder(jdbcUrl)
            val batchId = seedBatch(jdbcUrl, fixture.orderId, "DELIVERED", Instant.now())
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))

            val result = repository.listOperationalQueueByOrder(fixture.venueId, 20)

            assertEquals(1, result.size)
            val item = result.single()
            assertEquals(fixture.orderId, item.orderId)
            assertEquals(batchId, item.batchId)
            assertEquals(OrderWorkflowStatus.DELIVERED, item.status)
            assertEquals(1, item.activeBatchesCount)
            assertEquals("Максим", item.guestDisplayName)
        }

    @Test
    fun `listOperationalQueueByOrder excludes closed delivered order`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("closed-delivered-queue")
            val fixture = seedActiveOrder(jdbcUrl, orderStatus = "CLOSED")
            seedBatch(jdbcUrl, fixture.orderId, "DELIVERED", Instant.now())
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))

            val result = repository.listOperationalQueueByOrder(fixture.venueId, 20)

            assertTrue(result.isEmpty())
        }

    @Test
    fun `operational queue payable keeps promotion price snapshot after menu price edit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("operational-queue-promotion-price-snapshot")
            val fixture = seedActiveOrder(jdbcUrl)
            val batchId = seedBatch(jdbcUrl, fixture.orderId, "DELIVERED", Instant.now())
            val batchItemId =
                seedBatchItem(jdbcUrl, fixture.venueId, batchId, "Кальян", priceMinor = 200_000)
            seedPromotionAdjustment(
                jdbcUrl = jdbcUrl,
                fixture = fixture,
                batchId = batchId,
                batchItemId = batchItemId,
                title = "Счастливые часы",
                ruleType = "HAPPY_HOURS_PERCENT",
                discountMinor = 100_000,
                discountPercent = 50,
            )
            updateMenuItemPriceForBatchItem(jdbcUrl, batchItemId, priceMinor = 400_000)
            updateMenuItemCurrencyForBatchItem(jdbcUrl, batchItemId, currency = "USD")
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))

            val result = repository.listOperationalQueueByOrder(fixture.venueId, 20)

            assertEquals(100_000L, result.single().payableMinor)
            assertEquals(100_000L, result.single().promoDiscountMinor)
            assertEquals("RUB", result.single().currency)
        }

    @Test
    fun `loadOrderDetail returns guest display name from batch author`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("order-detail-author-guest-name")
            val fixture = seedActiveOrder(jdbcUrl)
            val batchId = seedBatch(jdbcUrl, fixture.orderId, "NEW", Instant.now())
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))

            val detail = repository.loadOrderDetail(fixture.venueId, fixture.orderId)

            assertNotNull(detail)
            val batch = detail.batches.single { it.batchId == batchId }
            assertEquals(GUEST_USER_ID, batch.authorUserId)
            assertEquals("Максим", batch.guestDisplayName)
        }

    @Test
    fun `loadOrderDetail returns guest display name from idempotency fallback`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("order-detail-idempotency-guest-name")
            val fixture = seedActiveOrder(jdbcUrl)
            val batchId = seedBatch(jdbcUrl, fixture.orderId, "NEW", Instant.now(), authorUserId = null)
            seedGuestBatchIdempotency(jdbcUrl, fixture, batchId)
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))

            val detail = repository.loadOrderDetail(fixture.venueId, fixture.orderId)

            assertNotNull(detail)
            val batch = detail.batches.single { it.batchId == batchId }
            assertEquals(GUEST_USER_ID, batch.authorUserId)
            assertEquals("Максим", batch.guestDisplayName)
        }

    @Test
    fun `loadOrderDetail returns promotion breakdown by names`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("order-detail-promo-breakdown")
            val fixture = seedActiveOrder(jdbcUrl)
            val batchId = seedBatch(jdbcUrl, fixture.orderId, "DELIVERED", Instant.now())
            val hookahBatchItemId = seedBatchItem(jdbcUrl, fixture.venueId, batchId, "Кальян", priceMinor = 200_000)
            val juiceBatchItemId = seedBatchItem(jdbcUrl, fixture.venueId, batchId, "Сок", priceMinor = 50_000)
            seedPromotionAdjustment(
                jdbcUrl = jdbcUrl,
                fixture = fixture,
                batchId = batchId,
                batchItemId = hookahBatchItemId,
                title = "Счастливые часы",
                ruleType = "HAPPY_HOURS_PERCENT",
                discountMinor = 20_000,
                discountPercent = 10,
            )
            seedPromotionAdjustment(
                jdbcUrl = jdbcUrl,
                fixture = fixture,
                batchId = batchId,
                batchItemId = juiceBatchItemId,
                title = "Чай к кальяну",
                ruleType = "GIFT_WITH_ITEM",
                discountMinor = 50_000,
                discountPercent = 100,
                rewardLabel = "Сок в подарок",
                triggerBatchItemId = hookahBatchItemId,
            )
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))

            val detail = repository.loadOrderDetail(fixture.venueId, fixture.orderId)

            assertNotNull(detail)
            assertEquals(
                listOf("Счастливые часы" to 20_000L, "Сок в подарок" to 50_000L),
                detail.promotionDiscounts.map { it.label to it.discountMinor },
            )
            assertEquals(detail.promotionDiscounts, detail.batches.single().promotionDiscounts)
            val items = detail.batches.single().items.associateBy { it.batchItemId }
            assertFalse(assertNotNull(items[hookahBatchItemId]).isPromotionReward)
            assertTrue(assertNotNull(items[hookahBatchItemId]).hasActivePromotionReward)
            assertTrue(assertNotNull(items[juiceBatchItemId]).isPromotionReward)
            assertFalse(assertNotNull(items[juiceBatchItemId]).hasActivePromotionReward)
        }

    @Test
    fun `manual item discount is rejected when promotion adjustment exists`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-manual-discount-conflict")
            val fixture = seedActiveOrder(jdbcUrl)
            val batchId = seedBatch(jdbcUrl, fixture.orderId, "ACCEPTED", Instant.now())
            val batchItemId =
                seedBatchItem(jdbcUrl, fixture.venueId, batchId, "Кальян", priceMinor = 200_000)
            seedPromotionAdjustment(
                jdbcUrl = jdbcUrl,
                fixture = fixture,
                batchId = batchId,
                batchItemId = batchItemId,
                title = "Счастливые часы",
                ruleType = "HAPPY_HOURS_PERCENT",
                discountMinor = 100_000,
                discountPercent = 50,
            )
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))

            val error =
                assertFailsWith<InvalidInputException> {
                    repository.setBatchItemDiscountPercent(
                        venueId = fixture.venueId,
                        orderId = fixture.orderId,
                        batchItemId = batchItemId,
                        discountPercent = 10,
                        actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.MANAGER),
                    )
                }

            assertEquals(
                "На эту позицию уже действует акция. Ручную скидку применить нельзя.",
                error.message,
            )
            assertEquals(100_000L to 50, fetchPromotionAdjustment(jdbcUrl, batchItemId))
        }

    @Test
    fun `staff cannot apply manual discount through repository`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("manual-discount-staff-forbidden")
            val fixture = seedActiveOrder(jdbcUrl)
            val gift = seedGiftFixture(jdbcUrl, fixture)
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))
            val beforeBill =
                assertNotNull(repository.loadOrderDetail(fixture.venueId, fixture.orderId))
                    .toOrderBillSnapshot()
            val beforeCounts = fetchPromotionLedgerCounts(jdbcUrl, fixture.orderId)

            assertFailsWith<ForbiddenException> {
                repository.setBatchItemDiscountPercent(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    batchItemId = gift.triggerBatchItemId,
                    discountPercent = 15,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )
            }

            assertNull(fetchBatchItemDiscountPercent(jdbcUrl, gift.triggerBatchItemId))
            assertEquals(beforeCounts, fetchPromotionLedgerCounts(jdbcUrl, fixture.orderId))
            assertEquals(
                beforeBill.finalPayableTotalMinor,
                assertNotNull(repository.loadOrderDetail(fixture.venueId, fixture.orderId))
                    .toOrderBillSnapshot()
                    .finalPayableTotalMinor,
            )
        }

    @Test
    fun `manual item discount is rejected for active gift reward and linked trigger`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("gift-link-manual-discount-conflict")
            val fixture = seedActiveOrder(jdbcUrl)
            val gift = seedGiftFixture(jdbcUrl, fixture)
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))
            val beforeBill =
                assertNotNull(repository.loadOrderDetail(fixture.venueId, fixture.orderId))
                    .toOrderBillSnapshot()
            val beforeCounts = fetchPromotionLedgerCounts(jdbcUrl, fixture.orderId)

            val triggerError =
                assertFailsWith<InvalidInputException> {
                    repository.setBatchItemDiscountPercent(
                        venueId = fixture.venueId,
                        orderId = fixture.orderId,
                        batchItemId = gift.triggerBatchItemId,
                        discountPercent = 15,
                        actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.MANAGER),
                    )
                }
            val rewardError =
                assertFailsWith<InvalidInputException> {
                    repository.setBatchItemDiscountPercent(
                        venueId = fixture.venueId,
                        orderId = fixture.orderId,
                        batchItemId = gift.rewardBatchItemId,
                        discountPercent = 15,
                        actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.MANAGER),
                    )
                }

            val expectedMessage = "На эту позицию уже действует акция. Ручную скидку применить нельзя."
            assertEquals(expectedMessage, triggerError.message)
            assertEquals(expectedMessage, rewardError.message)
            assertNull(fetchBatchItemDiscountPercent(jdbcUrl, gift.triggerBatchItemId))
            assertNull(fetchBatchItemDiscountPercent(jdbcUrl, gift.rewardBatchItemId))
            assertEquals(beforeCounts, fetchPromotionLedgerCounts(jdbcUrl, fixture.orderId))
            assertEquals(
                beforeBill.finalPayableTotalMinor,
                assertNotNull(repository.loadOrderDetail(fixture.venueId, fixture.orderId))
                    .toOrderBillSnapshot()
                    .finalPayableTotalMinor,
            )
        }

    @Test
    fun `promotion reward linkage blocks manual discount without relying on adjustment row`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("gift-link-manual-discount-no-adjustment")
            val fixture = seedActiveOrder(jdbcUrl)
            val gift = seedGiftFixture(jdbcUrl, fixture)
            deletePromotionAdjustment(jdbcUrl, gift.rewardBatchItemId)
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))

            val error =
                assertFailsWith<InvalidInputException> {
                    repository.setBatchItemDiscountPercent(
                        venueId = fixture.venueId,
                        orderId = fixture.orderId,
                        batchItemId = gift.rewardBatchItemId,
                        discountPercent = 10,
                        actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.MANAGER),
                    )
                }

            assertEquals(
                "На эту позицию уже действует акция. Ручную скидку применить нельзя.",
                error.message,
            )
            assertNull(fetchBatchItemDiscountPercent(jdbcUrl, gift.rewardBatchItemId))
            assertEquals(
                PromotionLedgerCounts(
                    applications = 1,
                    adjustments = 0,
                    rewardLinks = 1,
                ),
                fetchPromotionLedgerCounts(jdbcUrl, fixture.orderId),
            )
        }

    @Test
    fun `manual trigger discount follows normal policy after reward-only cancellation or exclusion`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("gift-trigger-discount-after-reward-inactive")
            val canceledFixture = seedActiveOrder(jdbcUrl)
            val canceledGift = seedGiftFixture(jdbcUrl, canceledFixture)
            val excludedFixture = seedActiveOrder(jdbcUrl)
            val excludedGift = seedGiftFixture(jdbcUrl, excludedFixture)
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))

            val canceledReward =
                repository.cancelBatchItemAsUnavailable(
                    venueId = canceledFixture.venueId,
                    orderId = canceledFixture.orderId,
                    batchItemId = canceledGift.rewardBatchItemId,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )
            assertTrue(assertNotNull(canceledReward).applied)
            assertEquals("ACTIVE", fetchBatchItemStatus(jdbcUrl, canceledGift.triggerBatchItemId))
            assertTrue(
                repository.setBatchItemDiscountPercent(
                    venueId = canceledFixture.venueId,
                    orderId = canceledFixture.orderId,
                    batchItemId = canceledGift.triggerBatchItemId,
                    discountPercent = 10,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.MANAGER),
                ),
            )

            assertTrue(
                repository.excludeBatchItemFromBill(
                    venueId = excludedFixture.venueId,
                    orderId = excludedFixture.orderId,
                    batchItemId = excludedGift.rewardBatchItemId,
                    reasonText = "Подарок не выдан",
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                ),
            )
            assertEquals("ACTIVE", fetchBatchItemStatus(jdbcUrl, excludedGift.triggerBatchItemId))
            assertTrue(
                repository.setBatchItemDiscountPercent(
                    venueId = excludedFixture.venueId,
                    orderId = excludedFixture.orderId,
                    batchItemId = excludedGift.triggerBatchItemId,
                    discountPercent = 20,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.MANAGER),
                ),
            )

            assertEquals(10, fetchBatchItemDiscountPercent(jdbcUrl, canceledGift.triggerBatchItemId))
            assertEquals(20, fetchBatchItemDiscountPercent(jdbcUrl, excludedGift.triggerBatchItemId))
            assertEquals("CANCELED", fetchBatchItemStatus(jdbcUrl, canceledGift.rewardBatchItemId))
            assertTrue(fetchBatchItemLifecycle(jdbcUrl, excludedGift.rewardBatchItemId).isExcluded)
        }

    @Test
    fun `manual item discount does not disclose promotion on foreign batch item`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-manual-discount-foreign-item")
            val ownFixture = seedActiveOrder(jdbcUrl)
            seedBatch(jdbcUrl, ownFixture.orderId, "ACCEPTED", Instant.now())
            val foreignFixture = seedActiveOrder(jdbcUrl)
            val foreignBatchId = seedBatch(jdbcUrl, foreignFixture.orderId, "ACCEPTED", Instant.now())
            val foreignBatchItemId =
                seedBatchItem(
                    jdbcUrl,
                    foreignFixture.venueId,
                    foreignBatchId,
                    "Чужой кальян",
                    priceMinor = 200_000,
                )
            seedPromotionAdjustment(
                jdbcUrl = jdbcUrl,
                fixture = foreignFixture,
                batchId = foreignBatchId,
                batchItemId = foreignBatchItemId,
                title = "Чужая акция",
                ruleType = "HAPPY_HOURS_PERCENT",
                discountMinor = 100_000,
                discountPercent = 50,
            )
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))

            val result =
                repository.setBatchItemDiscountPercent(
                    venueId = ownFixture.venueId,
                    orderId = ownFixture.orderId,
                    batchItemId = foreignBatchItemId,
                    discountPercent = 10,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.MANAGER),
                )

            assertFalse(result)
            assertEquals(100_000L to 50, fetchPromotionAdjustment(jdbcUrl, foreignBatchItemId))
        }

    @Test
    fun `closing order accrues loyalty only for eligible paid hookah and is idempotent`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("loyalty-close-eligible")
            val fixture = seedActiveOrder(jdbcUrl)
            val programId = seedActiveLoyaltyProgram(jdbcUrl, fixture.venueId, nthValue = 5)
            val deliveredBatchId = seedBatch(jdbcUrl, fixture.orderId, "DELIVERED", Instant.now())
            seedBatchItem(jdbcUrl, fixture.venueId, deliveredBatchId, "Кальян обычный", categoryType = "HOOKAH")
            seedBatchItem(jdbcUrl, fixture.venueId, deliveredBatchId, "Чай", categoryType = "TEA")
            seedBatchItem(
                jdbcUrl,
                fixture.venueId,
                deliveredBatchId,
                "Исключённый кальян",
                categoryType = "HOOKAH",
                isExcluded = true,
            )
            val canceledItemId =
                seedBatchItem(jdbcUrl, fixture.venueId, deliveredBatchId, "Отменённый кальян", categoryType = "HOOKAH")
            markBatchItemStatus(jdbcUrl, canceledItemId, "CANCELED")
            val rewardItemId =
                seedBatchItem(jdbcUrl, fixture.venueId, deliveredBatchId, "Подарочный кальян", categoryType = "HOOKAH")
            seedPromotionAdjustment(
                jdbcUrl = jdbcUrl,
                fixture = fixture,
                batchId = deliveredBatchId,
                batchItemId = rewardItemId,
                title = "Подарок",
                ruleType = "GIFT_WITH_ITEM",
                discountMinor = 100_000,
                discountPercent = 100,
                rewardLabel = "Подарочный кальян",
            )
            val rejectedBatchId = seedBatch(jdbcUrl, fixture.orderId, "REJECTED", Instant.now().minusSeconds(30))
            seedBatchItem(jdbcUrl, fixture.venueId, rejectedBatchId, "Отклонённый кальян", categoryType = "HOOKAH")
            val staffBatchId =
                seedBatch(
                    jdbcUrl,
                    fixture.orderId,
                    "DELIVERED",
                    Instant.now().minusSeconds(20),
                    authorUserId = STAFF_USER_ID,
                )
            seedVenueMember(jdbcUrl, fixture.venueId, STAFF_USER_ID, "STAFF")
            seedBatchItem(jdbcUrl, fixture.venueId, staffBatchId, "Staff кальян", categoryType = "HOOKAH")
            val repository =
                VenueOrdersRepository(
                    dataSource = dataSource(jdbcUrl),
                    loyaltyRepository = LoyaltyRepository(dataSource(jdbcUrl)),
                )

            val first =
                repository.updateOrderStatus(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    nextStatus = OrderWorkflowStatus.CLOSED,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )
            val second =
                repository.updateOrderStatus(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    nextStatus = OrderWorkflowStatus.CLOSED,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )

            assertNotNull(first)
            assertTrue(first.applied)
            assertNotNull(second)
            assertFalse(second.applied)
            assertEquals(1 to 0, fetchLoyaltyProgress(jdbcUrl, programId, GUEST_USER_ID))
            assertEquals(1, fetchLoyaltyLedgerCount(jdbcUrl, programId))
        }

    @Test
    fun `closing order accrues loyalty only for selected earn menu item target`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("loyalty-close-selected-earn")
            val fixture = seedActiveOrder(jdbcUrl)
            val programId = seedActiveLoyaltyProgram(jdbcUrl, fixture.venueId, nthValue = 5)
            val batchId = seedBatch(jdbcUrl, fixture.orderId, "DELIVERED", Instant.now())
            val selectedBatchItemId =
                seedBatchItem(jdbcUrl, fixture.venueId, batchId, "Кальян обычный", categoryType = "HOOKAH")
            seedBatchItem(jdbcUrl, fixture.venueId, batchId, "Премиум кальян", categoryType = "HOOKAH")
            val selectedMenuItemId =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    fetchMenuItemIdForBatchItem(connection, selectedBatchItemId)
                }
            replaceLoyaltyEarnTargetWithMenuItem(jdbcUrl, programId, selectedMenuItemId)
            val repository =
                VenueOrdersRepository(
                    dataSource = dataSource(jdbcUrl),
                    loyaltyRepository = LoyaltyRepository(dataSource(jdbcUrl)),
                )

            val result =
                repository.updateOrderStatus(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    nextStatus = OrderWorkflowStatus.CLOSED,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )

            assertNotNull(result)
            assertTrue(result.applied)
            assertEquals(1 to 0, fetchLoyaltyProgress(jdbcUrl, programId, GUEST_USER_ID))
            assertEquals(1, fetchLoyaltyLedgerCount(jdbcUrl, programId))
        }

    @Test
    fun `closing order rolls loyalty progress into available reward`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("loyalty-close-rollover")
            val fixture = seedActiveOrder(jdbcUrl)
            val programId = seedActiveLoyaltyProgram(jdbcUrl, fixture.venueId, nthValue = 3)
            val batchId = seedBatch(jdbcUrl, fixture.orderId, "DELIVERED", Instant.now())
            seedBatchItem(jdbcUrl, fixture.venueId, batchId, "Два кальяна", categoryType = "HOOKAH", qty = 2)
            val repository =
                VenueOrdersRepository(
                    dataSource = dataSource(jdbcUrl),
                    loyaltyRepository = LoyaltyRepository(dataSource(jdbcUrl)),
                )

            val result =
                repository.updateOrderStatus(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    nextStatus = OrderWorkflowStatus.CLOSED,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )

            assertNotNull(result)
            assertTrue(result.applied)
            assertEquals(0 to 1, fetchLoyaltyProgress(jdbcUrl, programId, GUEST_USER_ID))
            assertEquals(1, fetchLoyaltyLedgerCount(jdbcUrl, programId))
        }

    @Test
    fun `closing order gives sixth hookah reward after five paid hookahs`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("loyalty-close-sixth-after-five")
            val fixture = seedActiveOrder(jdbcUrl)
            val programId = seedActiveLoyaltyProgram(jdbcUrl, fixture.venueId, nthValue = 6)
            val batchId = seedBatch(jdbcUrl, fixture.orderId, "DELIVERED", Instant.now())
            seedBatchItem(jdbcUrl, fixture.venueId, batchId, "Пять кальянов", categoryType = "HOOKAH", qty = 5)
            val repository =
                VenueOrdersRepository(
                    dataSource = dataSource(jdbcUrl),
                    loyaltyRepository = LoyaltyRepository(dataSource(jdbcUrl)),
                )

            val result =
                repository.updateOrderStatus(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    nextStatus = OrderWorkflowStatus.CLOSED,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )

            assertNotNull(result)
            assertTrue(result.applied)
            assertEquals(0 to 1, fetchLoyaltyProgress(jdbcUrl, programId, GUEST_USER_ID))
            assertEquals(1, fetchLoyaltyLedgerCount(jdbcUrl, programId))
        }

    @Test
    fun `closing order rolls over extra paid hookahs after sixth hookah reward`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("loyalty-close-sixth-rollover")
            val fixture = seedActiveOrder(jdbcUrl)
            val programId = seedActiveLoyaltyProgram(jdbcUrl, fixture.venueId, nthValue = 6)
            val batchId = seedBatch(jdbcUrl, fixture.orderId, "DELIVERED", Instant.now())
            seedBatchItem(jdbcUrl, fixture.venueId, batchId, "Шесть кальянов", categoryType = "HOOKAH", qty = 6)
            val repository =
                VenueOrdersRepository(
                    dataSource = dataSource(jdbcUrl),
                    loyaltyRepository = LoyaltyRepository(dataSource(jdbcUrl)),
                )

            val result =
                repository.updateOrderStatus(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    nextStatus = OrderWorkflowStatus.CLOSED,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )

            assertNotNull(result)
            assertTrue(result.applied)
            assertEquals(1 to 1, fetchLoyaltyProgress(jdbcUrl, programId, GUEST_USER_ID))
            assertEquals(1, fetchLoyaltyLedgerCount(jdbcUrl, programId))
        }

    @Test
    fun `active loyalty program remains active when nth value changes`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("loyalty-edit-n-active")
            val fixture = seedActiveOrder(jdbcUrl)
            val programId = seedActiveLoyaltyProgram(jdbcUrl, fixture.venueId, nthValue = 5)
            val repository = LoyaltyRepository(dataSource(jdbcUrl))

            val updated =
                repository.createOrUpdateDraftProgram(
                    fixture.venueId,
                    nthValue = 6,
                    createdByUserId = STAFF_USER_ID,
                )

            assertEquals(programId, updated.id)
            assertEquals(6, updated.nthValue)
            assertEquals(LoyaltyProgramStatus.ACTIVE, updated.status)
            assertEquals("ACTIVE", fetchLoyaltyProgramStatus(jdbcUrl, programId))
        }

    @Test
    fun `active loyalty program remains active when earn and reward targets change`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("loyalty-edit-target-active")
            val fixture = seedActiveOrder(jdbcUrl)
            val programId = seedActiveLoyaltyProgram(jdbcUrl, fixture.venueId, nthValue = 5)
            val batchId = seedBatch(jdbcUrl, fixture.orderId, "DELIVERED", Instant.now())
            val batchItemId =
                seedBatchItem(jdbcUrl, fixture.venueId, batchId, "Кальян обычный", categoryType = "HOOKAH")
            val menuItemId =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    fetchMenuItemIdForBatchItem(connection, batchItemId)
                }
            val repository = LoyaltyRepository(dataSource(jdbcUrl))

            val earnUpdated = repository.replaceEarnTargetsWithMenuItems(fixture.venueId, programId, listOf(menuItemId))
            val rewardUpdated =
                repository.replaceRewardTargetsWithMenuItems(
                    fixture.venueId,
                    programId,
                    listOf(menuItemId),
                )

            assertEquals(LoyaltyProgramStatus.ACTIVE, earnUpdated?.status)
            assertEquals(LoyaltyProgramStatus.ACTIVE, rewardUpdated?.status)
            assertEquals("ACTIVE", fetchLoyaltyProgramStatus(jdbcUrl, programId))
        }

    @Test
    fun `cancelBatchItemAsUnavailable cancels only selected active item`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("cancel-batch-item-unavailable")
            val fixture = seedActiveOrder(jdbcUrl)
            val batchId = seedBatch(jdbcUrl, fixture.orderId, "ACCEPTED", Instant.now())
            val firstItemId = seedBatchItem(jdbcUrl, fixture.venueId, batchId, "Кальян обычный", priceMinor = 110_000)
            val secondItemId = seedBatchItem(jdbcUrl, fixture.venueId, batchId, "Вода", priceMinor = 40_000)
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))

            val result =
                repository.cancelBatchItemAsUnavailable(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    batchItemId = firstItemId,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )

            assertNotNull(result)
            assertTrue(result.applied)
            assertEquals(batchId, result.batchId)
            assertEquals("Кальян обычный", result.itemName)
            assertEquals(GUEST_USER_ID, result.guestUserId)
            assertEquals("CANCELED", fetchBatchItemStatus(jdbcUrl, firstItemId))
            assertEquals("ACTIVE", fetchBatchItemStatus(jdbcUrl, secondItemId))
            assertEquals("ACCEPTED", fetchBatchStatus(jdbcUrl, batchId))

            val detail = repository.loadOrderDetail(fixture.venueId, fixture.orderId)
            assertNotNull(detail)
            val canceled = detail.batches.single().items.single { it.batchItemId == firstItemId }
            val active = detail.batches.single().items.single { it.batchItemId == secondItemId }
            assertEquals(OrderBatchItemStatus.CANCELED, canceled.itemStatus)
            assertEquals("Позиция закончилась", canceled.canceledReasonText)
            assertEquals(OrderBatchItemStatus.ACTIVE, active.itemStatus)
        }

    @Test
    fun `cancelBatchItemAsUnavailable is idempotent for already canceled item`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("cancel-batch-item-idempotent")
            val fixture = seedActiveOrder(jdbcUrl)
            val batchId = seedBatch(jdbcUrl, fixture.orderId, "ACCEPTED", Instant.now())
            val batchItemId = seedBatchItem(jdbcUrl, fixture.venueId, batchId, "Кальян обычный")
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))

            val first =
                repository.cancelBatchItemAsUnavailable(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    batchItemId = batchItemId,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )
            val second =
                repository.cancelBatchItemAsUnavailable(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    batchItemId = batchItemId,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )

            assertNotNull(first)
            assertTrue(first.applied)
            assertNotNull(second)
            assertFalse(second.applied)
            assertEquals("CANCELED", fetchBatchItemStatus(jdbcUrl, batchItemId))
        }

    @Test
    fun `cancelBatchItemAsUnavailable does not roll back item cancel when audit actor is missing`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("cancel-batch-item-audit-missing-actor")
            val fixture = seedActiveOrder(jdbcUrl)
            val batchId = seedBatch(jdbcUrl, fixture.orderId, "ACCEPTED", Instant.now())
            val batchItemId = seedBatchItem(jdbcUrl, fixture.venueId, batchId, "Кальян обычный")
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))

            val result =
                repository.cancelBatchItemAsUnavailable(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    batchItemId = batchItemId,
                    actor = OrderActionActor(userId = 999_999_001L, role = VenueRole.STAFF),
                )

            assertNotNull(result)
            assertTrue(result.applied)
            assertEquals("CANCELED", fetchBatchItemStatus(jdbcUrl, batchItemId))
        }

    @Test
    fun `cancelBatchItemAsUnavailable rejects closed order rejected batch and excluded item`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("cancel-batch-item-forbidden")
            val fixture = seedActiveOrder(jdbcUrl)
            val rejectedBatchId = seedBatch(jdbcUrl, fixture.orderId, "REJECTED", Instant.now())
            val rejectedItemId = seedBatchItem(jdbcUrl, fixture.venueId, rejectedBatchId, "Кальян обычный")
            val acceptedBatchId = seedBatch(jdbcUrl, fixture.orderId, "ACCEPTED", Instant.now())
            val excludedItemId = seedBatchItem(jdbcUrl, fixture.venueId, acceptedBatchId, "Вода", isExcluded = true)
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))

            val rejectedResult =
                repository.cancelBatchItemAsUnavailable(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    batchItemId = rejectedItemId,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )
            val excludedResult =
                repository.cancelBatchItemAsUnavailable(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    batchItemId = excludedItemId,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )

            assertNull(rejectedResult)
            assertNull(excludedResult)
            assertEquals("ACTIVE", fetchBatchItemStatus(jdbcUrl, rejectedItemId))
            assertEquals("ACTIVE", fetchBatchItemStatus(jdbcUrl, excludedItemId))

            markOrderClosed(jdbcUrl, fixture.orderId)
            val closedResult =
                repository.cancelBatchItemAsUnavailable(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    batchItemId = excludedItemId,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )
            assertNull(closedResult)
        }

    @Test
    fun `canceling promotion trigger atomically cancels linked reward and preserves ledger`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("gift-trigger-cancel")
            val fixture = seedActiveOrder(jdbcUrl)
            val gift = seedGiftFixture(jdbcUrl, fixture)
            val remainingItemId =
                seedBatchItem(
                    jdbcUrl = jdbcUrl,
                    venueId = fixture.venueId,
                    batchId = gift.batchId,
                    name = "Вода",
                    priceMinor = 40_000,
                )
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))
            val ledgerBefore = fetchPromotionLedgerCounts(jdbcUrl, fixture.orderId)
            val ledgerSnapshotBefore = fetchGiftPromotionLedgerSnapshot(jdbcUrl, fixture.orderId)
            assertEquals(gift.triggerBatchItemId, ledgerSnapshotBefore.triggerBatchItemId)
            assertEquals(gift.rewardBatchItemId, ledgerSnapshotBefore.rewardBatchItemId)
            val initialItems =
                assertNotNull(repository.loadOrderDetail(fixture.venueId, fixture.orderId))
                    .batches
                    .single()
                    .items
                    .associateBy { it.batchItemId }
            assertTrue(assertNotNull(initialItems[gift.triggerBatchItemId]).hasActivePromotionReward)

            val first =
                repository.cancelBatchItemAsUnavailable(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    batchItemId = gift.triggerBatchItemId,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )
            val ledgerSnapshotAfterFirst = fetchGiftPromotionLedgerSnapshot(jdbcUrl, fixture.orderId)
            val second =
                repository.cancelBatchItemAsUnavailable(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    batchItemId = gift.triggerBatchItemId,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )

            assertTrue(assertNotNull(first).applied)
            assertFalse(assertNotNull(second).applied)
            val trigger = fetchBatchItemLifecycle(jdbcUrl, gift.triggerBatchItemId)
            val reward = fetchBatchItemLifecycle(jdbcUrl, gift.rewardBatchItemId)
            assertEquals("CANCELED", trigger.itemStatus)
            assertEquals("ITEM_UNAVAILABLE", trigger.canceledReasonCode)
            assertEquals("CANCELED", reward.itemStatus)
            assertEquals("PROMOTION_TRIGGER_CANCELED", reward.canceledReasonCode)
            assertEquals("Связанный подарок отменён вместе с условием акции.", reward.canceledReasonText)
            assertEquals(ledgerBefore, fetchPromotionLedgerCounts(jdbcUrl, fixture.orderId))
            assertEquals(ledgerSnapshotBefore, ledgerSnapshotAfterFirst)
            assertEquals(ledgerSnapshotBefore, fetchGiftPromotionLedgerSnapshot(jdbcUrl, fixture.orderId))
            assertEquals(1, fetchOrderAuditCount(jdbcUrl, fixture.orderId, "CANCEL_ITEM_UNAVAILABLE"))
            assertEquals(0, countOrphanActiveRewards(jdbcUrl, fixture.orderId))

            val detail = assertNotNull(repository.loadOrderDetail(fixture.venueId, fixture.orderId))
            val items = detail.batches.single().items.associateBy { it.batchItemId }
            assertFalse(assertNotNull(items[gift.triggerBatchItemId]).hasActivePromotionReward)
            assertTrue(assertNotNull(items[gift.rewardBatchItemId]).isPromotionReward)
            val bill = detail.toOrderBillSnapshot()
            assertEquals(listOf(remainingItemId), bill.activeItems.map { it.batchItemId })
            assertEquals(
                setOf(gift.triggerBatchItemId, gift.rewardBatchItemId),
                bill.excludedItems.filter { it.status == "canceled" }.map { it.batchItemId }.toSet(),
            )
            assertEquals(40_000L, bill.finalPayableTotalMinor)
            assertTrue(bill.promoDiscounts.isEmpty())
        }

    @Test
    fun `excluding promotion trigger atomically excludes linked reward and is idempotent`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("gift-trigger-exclude")
            val fixture = seedActiveOrder(jdbcUrl)
            val gift = seedGiftFixture(jdbcUrl, fixture)
            val remainingItemId =
                seedBatchItem(
                    jdbcUrl = jdbcUrl,
                    venueId = fixture.venueId,
                    batchId = gift.batchId,
                    name = "Чай",
                    priceMinor = 30_000,
                )
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))
            val ledgerBefore = fetchPromotionLedgerCounts(jdbcUrl, fixture.orderId)
            val ledgerSnapshotBefore = fetchGiftPromotionLedgerSnapshot(jdbcUrl, fixture.orderId)
            assertEquals(gift.triggerBatchItemId, ledgerSnapshotBefore.triggerBatchItemId)
            assertEquals(gift.rewardBatchItemId, ledgerSnapshotBefore.rewardBatchItemId)

            val first =
                repository.excludeBatchItemFromBill(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    batchItemId = gift.triggerBatchItemId,
                    reasonText = "Позицию не готовим",
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )
            val ledgerSnapshotAfterFirst = fetchGiftPromotionLedgerSnapshot(jdbcUrl, fixture.orderId)
            val second =
                repository.excludeBatchItemFromBill(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    batchItemId = gift.triggerBatchItemId,
                    reasonText = "Повтор",
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )

            assertTrue(first)
            assertTrue(second)
            val trigger = fetchBatchItemLifecycle(jdbcUrl, gift.triggerBatchItemId)
            val reward = fetchBatchItemLifecycle(jdbcUrl, gift.rewardBatchItemId)
            assertTrue(trigger.isExcluded)
            assertEquals("Позицию не готовим", trigger.excludedReasonText)
            assertTrue(reward.isExcluded)
            assertEquals("Связанный подарок исключён вместе с условием акции.", reward.excludedReasonText)
            assertEquals(ledgerBefore, fetchPromotionLedgerCounts(jdbcUrl, fixture.orderId))
            assertEquals(ledgerSnapshotBefore, ledgerSnapshotAfterFirst)
            assertEquals(ledgerSnapshotBefore, fetchGiftPromotionLedgerSnapshot(jdbcUrl, fixture.orderId))
            assertEquals(1, fetchOrderAuditCount(jdbcUrl, fixture.orderId, "EXCLUDE_ITEM_FROM_BILL"))
            assertEquals(0, countOrphanActiveRewards(jdbcUrl, fixture.orderId))

            val bill =
                assertNotNull(repository.loadOrderDetail(fixture.venueId, fixture.orderId))
                    .toOrderBillSnapshot()
            assertEquals(listOf(remainingItemId), bill.activeItems.map { it.batchItemId })
            assertEquals(
                setOf(gift.triggerBatchItemId, gift.rewardBatchItemId),
                bill.excludedItems.filter { it.status == "excluded" }.map { it.batchItemId }.toSet(),
            )
            assertEquals(30_000L, bill.finalPayableTotalMinor)
            assertTrue(bill.promoDiscounts.isEmpty())
        }

    @Test
    fun `coupled gift lifecycle keeps guest venue history and staff persisted facts aligned`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("gift-coupled-presentation-parity")
            val sharedDataSource = dataSource(jdbcUrl)
            val repository = VenueOrdersRepository(sharedDataSource)
            val guestOrdersRepository = OrdersRepository(sharedDataSource)

            val canceledFixture = seedActiveOrder(jdbcUrl)
            val canceledGift = seedGiftFixture(jdbcUrl, canceledFixture)
            val remainingCanceledItemId =
                seedBatchItem(
                    jdbcUrl = jdbcUrl,
                    venueId = canceledFixture.venueId,
                    batchId = canceledGift.batchId,
                    name = "Вода",
                    priceMinor = 40_000,
                )
            val canceledLedgerBefore = fetchPromotionLedgerCounts(jdbcUrl, canceledFixture.orderId)

            assertTrue(
                assertNotNull(
                    repository.cancelBatchItemAsUnavailable(
                        venueId = canceledFixture.venueId,
                        orderId = canceledFixture.orderId,
                        batchItemId = canceledGift.triggerBatchItemId,
                        actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                    ),
                ).applied,
            )

            val guestCanceledOrder =
                assertNotNull(guestOrdersRepository.findActiveOrderDetails(canceledFixture.tableSessionId))
            val guestCanceledItems = guestCanceledOrder.batches.flatMap { batch -> batch.items }
            assertEquals(listOf("Вода"), guestCanceledItems.map { item -> item.itemName })
            assertTrue(guestCanceledOrder.promotionDiscounts.isEmpty())
            assertEquals(
                40_000L,
                guestCanceledItems.sumOf { item ->
                    (item.priceMinor ?: 0L) * item.qty - item.promoDiscountMinor
                },
            )
            assertTrue(guestCanceledItems.none { item -> item.isPromotionReward })

            val venueCanceledDetail =
                assertNotNull(repository.loadOrderDetail(canceledFixture.venueId, canceledFixture.orderId))
            val venueCanceledBill = venueCanceledDetail.toOrderBillSnapshot()
            val staffChatPersistedSourceBill =
                assertNotNull(
                    VenueOrdersRepository(sharedDataSource)
                        .loadOrderDetail(canceledFixture.venueId, canceledFixture.orderId),
                ).toOrderBillSnapshot()
            assertEquals(venueCanceledBill, staffChatPersistedSourceBill)
            assertEquals(listOf(remainingCanceledItemId), venueCanceledBill.activeItems.map { it.batchItemId })
            assertEquals(
                setOf(canceledGift.triggerBatchItemId, canceledGift.rewardBatchItemId),
                venueCanceledBill.excludedItems
                    .filter { item -> item.status == "canceled" }
                    .map { item -> item.batchItemId }
                    .toSet(),
            )
            assertEquals(40_000L, venueCanceledBill.finalPayableTotalMinor)
            assertTrue(venueCanceledBill.promoDiscounts.isEmpty())
            assertEquals(canceledLedgerBefore, fetchPromotionLedgerCounts(jdbcUrl, canceledFixture.orderId))
            assertEquals(0, countOrphanActiveRewards(jdbcUrl, canceledFixture.orderId))

            val canceledItemsById =
                venueCanceledDetail.batches.single().items.associateBy { item -> item.batchItemId }
            val canceledTrigger = assertNotNull(canceledItemsById[canceledGift.triggerBatchItemId])
            val canceledReward = assertNotNull(canceledItemsById[canceledGift.rewardBatchItemId])
            assertFalse(canceledTrigger.hasActivePromotionReward)
            assertTrue(canceledReward.isPromotionReward)
            assertEquals("PROMOTION_TRIGGER_CANCELED", canceledReward.canceledReasonCode)
            assertEquals(
                "Связанный подарок отменён вместе с условием акции.",
                canceledReward.canceledReasonText,
            )

            markOrderClosed(jdbcUrl, canceledFixture.orderId)
            val visitId = seedOrderClosedVisit(jdbcUrl, canceledFixture)
            val history =
                assertNotNull(
                    VisitRepository(sharedDataSource).getGuestVisitDetail(
                        userId = GUEST_USER_ID,
                        visitId = visitId,
                    ),
                )
            val historyOrder = history.orders.single()
            val historyItems = historyOrder.items.associateBy { item -> item.itemName }
            val historyTrigger = assertNotNull(historyItems["Кальян"])
            val historyReward = assertNotNull(historyItems["Чай"])
            assertEquals("CANCELED", historyTrigger.itemStatus)
            assertEquals("TRIGGER", historyTrigger.promotionLinkRole)
            assertEquals("Чай в подарок", historyTrigger.promotionLabel)
            assertEquals(0L, historyTrigger.totalMinor)
            assertEquals("CANCELED", historyReward.itemStatus)
            assertEquals("REWARD", historyReward.promotionLinkRole)
            assertEquals("Чай в подарок", historyReward.promotionLabel)
            assertEquals(
                "Связанный подарок отменён вместе с условием акции.",
                historyReward.canceledReasonText,
            )
            assertEquals(0L, historyReward.totalMinor)
            assertEquals(40_000L, historyOrder.totalMinor)
            assertFalse(historyOrder.promotionDiscounts.single().isActive)
            assertEquals(canceledLedgerBefore, fetchPromotionLedgerCounts(jdbcUrl, canceledFixture.orderId))

            val excludedFixture = seedActiveOrder(jdbcUrl)
            val excludedGift = seedGiftFixture(jdbcUrl, excludedFixture)
            val remainingExcludedItemId =
                seedBatchItem(
                    jdbcUrl = jdbcUrl,
                    venueId = excludedFixture.venueId,
                    batchId = excludedGift.batchId,
                    name = "Сок",
                    priceMinor = 30_000,
                )
            val excludedLedgerBefore = fetchPromotionLedgerCounts(jdbcUrl, excludedFixture.orderId)

            assertTrue(
                repository.excludeBatchItemFromBill(
                    venueId = excludedFixture.venueId,
                    orderId = excludedFixture.orderId,
                    batchItemId = excludedGift.triggerBatchItemId,
                    reasonText = "Условие акции исключено",
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                ),
            )

            val guestExcludedOrder =
                assertNotNull(guestOrdersRepository.findActiveOrderDetails(excludedFixture.tableSessionId))
            val guestExcludedItems = guestExcludedOrder.batches.flatMap { batch -> batch.items }
            assertEquals(listOf("Сок"), guestExcludedItems.map { item -> item.itemName })
            assertTrue(guestExcludedOrder.promotionDiscounts.isEmpty())
            assertEquals(
                30_000L,
                guestExcludedItems.sumOf { item ->
                    (item.priceMinor ?: 0L) * item.qty - item.promoDiscountMinor
                },
            )
            assertTrue(guestExcludedItems.none { item -> item.isPromotionReward })

            val venueExcludedDetail =
                assertNotNull(repository.loadOrderDetail(excludedFixture.venueId, excludedFixture.orderId))
            val venueExcludedBill = venueExcludedDetail.toOrderBillSnapshot()
            val staffChatExcludedPersistedSourceBill =
                assertNotNull(
                    VenueOrdersRepository(sharedDataSource)
                        .loadOrderDetail(excludedFixture.venueId, excludedFixture.orderId),
                ).toOrderBillSnapshot()
            assertEquals(venueExcludedBill, staffChatExcludedPersistedSourceBill)
            assertEquals(listOf(remainingExcludedItemId), venueExcludedBill.activeItems.map { it.batchItemId })
            assertEquals(
                setOf(excludedGift.triggerBatchItemId, excludedGift.rewardBatchItemId),
                venueExcludedBill.excludedItems
                    .filter { item -> item.status == "excluded" }
                    .map { item -> item.batchItemId }
                    .toSet(),
            )
            assertEquals(30_000L, venueExcludedBill.finalPayableTotalMinor)
            assertTrue(venueExcludedBill.promoDiscounts.isEmpty())
            assertEquals(excludedLedgerBefore, fetchPromotionLedgerCounts(jdbcUrl, excludedFixture.orderId))
            assertEquals(0, countOrphanActiveRewards(jdbcUrl, excludedFixture.orderId))
            assertEquals(
                "Связанный подарок исключён вместе с условием акции.",
                fetchBatchItemLifecycle(jdbcUrl, excludedGift.rewardBatchItemId).excludedReasonText,
            )
        }

    @Test
    fun `linked reward restore requires active trigger and remains one-way`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("gift-reward-restore-requires-trigger")
            val fixture = seedActiveOrder(jdbcUrl)
            val gift = seedGiftFixture(jdbcUrl, fixture)
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))
            val ledgerBefore = fetchPromotionLedgerCounts(jdbcUrl, fixture.orderId)

            assertTrue(
                repository.excludeBatchItemFromBill(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    batchItemId = gift.triggerBatchItemId,
                    reasonText = "Условие исключено",
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                ),
            )

            assertFalse(
                repository.restoreBatchItemToBill(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    batchItemId = gift.rewardBatchItemId,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                ),
            )
            assertTrue(fetchBatchItemLifecycle(jdbcUrl, gift.triggerBatchItemId).isExcluded)
            assertTrue(fetchBatchItemLifecycle(jdbcUrl, gift.rewardBatchItemId).isExcluded)

            assertTrue(
                repository.restoreBatchItemToBill(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    batchItemId = gift.triggerBatchItemId,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                ),
            )
            assertFalse(fetchBatchItemLifecycle(jdbcUrl, gift.triggerBatchItemId).isExcluded)
            assertTrue(fetchBatchItemLifecycle(jdbcUrl, gift.rewardBatchItemId).isExcluded)
            val triggerOnlyBill =
                assertNotNull(repository.loadOrderDetail(fixture.venueId, fixture.orderId))
                    .toOrderBillSnapshot()
            assertEquals(listOf(gift.triggerBatchItemId), triggerOnlyBill.activeItems.map { it.batchItemId })
            assertEquals(200_000L, triggerOnlyBill.finalPayableTotalMinor)
            assertTrue(triggerOnlyBill.promoDiscounts.isEmpty())

            assertTrue(
                repository.restoreBatchItemToBill(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    batchItemId = gift.rewardBatchItemId,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                ),
            )
            assertFalse(fetchBatchItemLifecycle(jdbcUrl, gift.rewardBatchItemId).isExcluded)
            val restoredBill =
                assertNotNull(repository.loadOrderDetail(fixture.venueId, fixture.orderId))
                    .toOrderBillSnapshot()
            assertEquals(
                setOf(gift.triggerBatchItemId, gift.rewardBatchItemId),
                restoredBill.activeItems.map { it.batchItemId }.toSet(),
            )
            assertEquals(200_000L, restoredBill.finalPayableTotalMinor)
            assertEquals(50_000L, restoredBill.promoDiscountTotalMinor)
            assertEquals(ledgerBefore, fetchPromotionLedgerCounts(jdbcUrl, fixture.orderId))
            assertEquals(2, fetchOrderAuditCount(jdbcUrl, fixture.orderId, "RESTORE_ITEM_TO_BILL"))
            assertEquals(0, countOrphanActiveRewards(jdbcUrl, fixture.orderId))
        }

    @Test
    fun `inactive reward stays one-way when linked trigger later mutates`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("gift-reward-one-way")
            val canceledFixture = seedActiveOrder(jdbcUrl)
            val canceledGift = seedGiftFixture(jdbcUrl, canceledFixture)
            val excludedFixture = seedActiveOrder(jdbcUrl)
            val excludedGift = seedGiftFixture(jdbcUrl, excludedFixture)
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))

            assertTrue(
                assertNotNull(
                    repository.cancelBatchItemAsUnavailable(
                        venueId = canceledFixture.venueId,
                        orderId = canceledFixture.orderId,
                        batchItemId = canceledGift.rewardBatchItemId,
                        actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                    ),
                ).applied,
            )
            val rewardCancelBefore = fetchBatchItemLifecycle(jdbcUrl, canceledGift.rewardBatchItemId)
            assertEquals("ACTIVE", fetchBatchItemStatus(jdbcUrl, canceledGift.triggerBatchItemId))
            assertTrue(
                assertNotNull(
                    repository.cancelBatchItemAsUnavailable(
                        venueId = canceledFixture.venueId,
                        orderId = canceledFixture.orderId,
                        batchItemId = canceledGift.triggerBatchItemId,
                        actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                    ),
                ).applied,
            )
            assertEquals(rewardCancelBefore, fetchBatchItemLifecycle(jdbcUrl, canceledGift.rewardBatchItemId))

            assertTrue(
                repository.excludeBatchItemFromBill(
                    venueId = excludedFixture.venueId,
                    orderId = excludedFixture.orderId,
                    batchItemId = excludedGift.rewardBatchItemId,
                    reasonText = "Подарок отдельно исключён",
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                ),
            )
            val rewardExcludeBefore = fetchBatchItemLifecycle(jdbcUrl, excludedGift.rewardBatchItemId)
            assertFalse(fetchBatchItemLifecycle(jdbcUrl, excludedGift.triggerBatchItemId).isExcluded)
            assertTrue(
                repository.excludeBatchItemFromBill(
                    venueId = excludedFixture.venueId,
                    orderId = excludedFixture.orderId,
                    batchItemId = excludedGift.triggerBatchItemId,
                    reasonText = "Условие отдельно исключено",
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                ),
            )
            assertEquals(rewardExcludeBefore, fetchBatchItemLifecycle(jdbcUrl, excludedGift.rewardBatchItemId))
            assertEquals(0, countOrphanActiveRewards(jdbcUrl, canceledFixture.orderId))
            assertEquals(0, countOrphanActiveRewards(jdbcUrl, excludedFixture.orderId))
        }

    @Test
    fun `linked reward update failure rolls back trigger reward bill and audit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("gift-trigger-cancel-rollback")
            val fixture = seedActiveOrder(jdbcUrl)
            val gift = seedGiftFixture(jdbcUrl, fixture)
            val delegate = dataSource(jdbcUrl)
            val repository = VenueOrdersRepository(failingOnLinkedRewardCancelDataSource(delegate))
            val beforeLedger = fetchPromotionLedgerCounts(jdbcUrl, fixture.orderId)
            val beforeBill =
                assertNotNull(
                    VenueOrdersRepository(delegate).loadOrderDetail(fixture.venueId, fixture.orderId),
                ).toOrderBillSnapshot()

            assertFailsWith<DatabaseUnavailableException> {
                repository.cancelBatchItemAsUnavailable(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    batchItemId = gift.triggerBatchItemId,
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )
            }

            assertEquals("ACTIVE", fetchBatchItemStatus(jdbcUrl, gift.triggerBatchItemId))
            assertEquals("ACTIVE", fetchBatchItemStatus(jdbcUrl, gift.rewardBatchItemId))
            assertEquals(beforeLedger, fetchPromotionLedgerCounts(jdbcUrl, fixture.orderId))
            assertEquals(0, fetchOrderAuditCount(jdbcUrl, fixture.orderId, "CANCEL_ITEM_UNAVAILABLE"))
            assertEquals(0, countOrphanActiveRewards(jdbcUrl, fixture.orderId))
            assertEquals(
                beforeBill,
                assertNotNull(
                    VenueOrdersRepository(delegate).loadOrderDetail(fixture.venueId, fixture.orderId),
                ).toOrderBillSnapshot(),
            )
        }

    @Test
    fun `whole batch reject keeps linked gift ledger and makes trigger and reward nonpayable`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("gift-whole-batch-reject")
            val fixture = seedActiveOrder(jdbcUrl)
            val gift = seedGiftFixture(jdbcUrl, fixture)
            val repository = VenueOrdersRepository(dataSource(jdbcUrl))
            val ledgerBefore = fetchPromotionLedgerCounts(jdbcUrl, fixture.orderId)

            val result =
                repository.rejectLatestBatch(
                    venueId = fixture.venueId,
                    orderId = fixture.orderId,
                    reasonCode = "VENUE_REJECTED",
                    reasonText = "Заказ отклонён",
                    actor = OrderActionActor(userId = STAFF_USER_ID, role = VenueRole.STAFF),
                )

            assertTrue(assertNotNull(result).applied)
            assertEquals("REJECTED", fetchBatchStatus(jdbcUrl, gift.batchId))
            assertEquals("ACTIVE", fetchBatchItemStatus(jdbcUrl, gift.triggerBatchItemId))
            assertEquals("ACTIVE", fetchBatchItemStatus(jdbcUrl, gift.rewardBatchItemId))
            assertEquals(ledgerBefore, fetchPromotionLedgerCounts(jdbcUrl, fixture.orderId))
            val bill =
                assertNotNull(repository.loadOrderDetail(fixture.venueId, fixture.orderId))
                    .toOrderBillSnapshot()
            assertTrue(bill.activeItems.isEmpty())
            assertEquals(0L, bill.finalPayableTotalMinor)
            assertEquals(
                setOf(gift.triggerBatchItemId, gift.rewardBatchItemId),
                bill.excludedItems.filter { it.status == "rejected_batch" }.map { it.batchItemId }.toSet(),
            )
        }

    private fun migratedJdbcUrl(prefix: String): String {
        val jdbcUrl =
            "jdbc:h2:mem:$prefix-${UUID.randomUUID()};MODE=PostgreSQL;" +
                "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        Flyway.configure()
            .dataSource(jdbcUrl, "sa", "")
            .locations("classpath:db/migration/h2")
            .load()
            .migrate()
        return jdbcUrl
    }

    private fun dataSource(jdbcUrl: String): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(jdbcUrl)
            user = "sa"
            password = ""
        }

    private fun failingOnLinkedRewardCancelDataSource(delegate: DataSource): DataSource =
        object : DataSource by delegate {
            override fun getConnection(): Connection = failingOnLinkedRewardCancelConnection(delegate.connection)

            override fun getConnection(
                username: String?,
                password: String?,
            ): Connection = failingOnLinkedRewardCancelConnection(delegate.getConnection(username, password))
        }

    private fun failingOnLinkedRewardCancelConnection(delegate: Connection): Connection =
        Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, arguments ->
            val result = invokeDelegate(method, delegate, arguments)
            val sql = arguments?.firstOrNull() as? String
            if (
                method.name == "prepareStatement" &&
                sql?.contains("SET item_status = 'CANCELED'") == true &&
                result is PreparedStatement
            ) {
                failingOnLinkedRewardCancelStatement(result)
            } else {
                result
            }
        } as Connection

    private fun failingOnLinkedRewardCancelStatement(delegate: PreparedStatement): PreparedStatement {
        var reasonCode: String? = null
        return Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
        ) { _, method, arguments ->
            if (
                method.name == "setString" &&
                arguments?.getOrNull(0) == 1 &&
                arguments.getOrNull(1) is String
            ) {
                reasonCode = arguments[1] as String
            }
            if (method.name == "executeUpdate" && reasonCode == "PROMOTION_TRIGGER_CANCELED") {
                throw SQLException("Injected linked reward cancellation failure")
            }
            invokeDelegate(method, delegate, arguments)
        } as PreparedStatement
    }

    private fun invokeDelegate(
        method: Method,
        delegate: Any,
        arguments: Array<out Any?>?,
    ): Any? =
        try {
            method.invoke(delegate, *(arguments ?: emptyArray()))
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }

    private fun seedActiveOrder(
        jdbcUrl: String,
        orderStatus: String = "ACTIVE",
    ): OrderFixture {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO users (telegram_user_id, username, first_name, last_name, guest_display_name)
                KEY (telegram_user_id)
                VALUES (?, 'staff', 'Staff', 'User', NULL)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, STAFF_USER_ID)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                MERGE INTO users (telegram_user_id, username, first_name, last_name, guest_display_name)
                KEY (telegram_user_id)
                VALUES (?, 'guest', 'Guest', 'User', 'Максим')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, GUEST_USER_ID)
                statement.executeUpdate()
            }
            val venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', 'PUBLISHED')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert venue")
                    }
                }
            val tableId =
                connection.prepareStatement(
                    """
                    INSERT INTO venue_tables (venue_id, table_number, is_active)
                    VALUES (?, 10, true)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert table")
                    }
                }
            val now = Instant.now()
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
                    statement.setTimestamp(3, Timestamp.from(now))
                    statement.setTimestamp(4, Timestamp.from(now))
                    statement.setTimestamp(5, Timestamp.from(now.plusSeconds(7200)))
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert table session")
                    }
                }
            val orderId =
                connection.prepareStatement(
                    """
                    INSERT INTO orders (venue_id, table_id, table_session_id, status)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, tableId)
                    statement.setLong(3, tableSessionId)
                    statement.setString(4, orderStatus)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert order")
                    }
                }
            return OrderFixture(venueId = venueId, orderId = orderId, tableSessionId = tableSessionId)
        }
    }

    private fun seedBatch(
        jdbcUrl: String,
        orderId: Long,
        status: String,
        createdAt: Instant,
        authorUserId: Long? = GUEST_USER_ID,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            return connection.prepareStatement(
                """
                INSERT INTO order_batches (order_id, created_at, updated_at, author_user_id, source, status, guest_comment)
                VALUES (?, ?, ?, ?, 'MINIAPP', ?, NULL)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                val timestamp = Timestamp.from(createdAt)
                statement.setLong(1, orderId)
                statement.setTimestamp(2, timestamp)
                statement.setTimestamp(3, timestamp)
                if (authorUserId != null) {
                    statement.setLong(4, authorUserId)
                } else {
                    statement.setNull(4, java.sql.Types.BIGINT)
                }
                statement.setString(5, status)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert batch")
                }
            }
        }
    }

    private fun seedGuestBatchIdempotency(
        jdbcUrl: String,
        fixture: OrderFixture,
        batchId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO guest_batch_idempotency (
                    venue_id, table_session_id, user_id, idempotency_key, order_id, batch_id
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, fixture.venueId)
                statement.setLong(2, fixture.tableSessionId)
                statement.setLong(3, GUEST_USER_ID)
                statement.setString(4, "idem-$batchId")
                statement.setLong(5, fixture.orderId)
                statement.setLong(6, batchId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedBatchItem(
        jdbcUrl: String,
        venueId: Long,
        batchId: Long,
        name: String,
        priceMinor: Long = 100_000,
        isExcluded: Boolean = false,
        categoryType: String = "OTHER",
        qty: Int = 1,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val categoryId =
                connection.prepareStatement(
                    """
                    INSERT INTO menu_categories (venue_id, name, sort_order, is_active, category_type)
                    VALUES (?, ?, 0, true, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setString(2, "Меню $name")
                    statement.setString(3, categoryType)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert category")
                    }
                }
            val menuItemId =
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
                        if (rs.next()) rs.getLong(1) else error("Failed to insert item")
                    }
                }
            return connection.prepareStatement(
                """
                INSERT INTO order_batch_items (order_batch_id, menu_item_id, qty, is_excluded)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, batchId)
                statement.setLong(2, menuItemId)
                statement.setInt(3, qty)
                statement.setBoolean(4, isExcluded)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert batch item")
                }
            }
        }
    }

    private fun seedGiftFixture(
        jdbcUrl: String,
        fixture: OrderFixture,
    ): GiftFixture {
        val batchId = seedBatch(jdbcUrl, fixture.orderId, "ACCEPTED", Instant.now())
        val triggerBatchItemId =
            seedBatchItem(
                jdbcUrl = jdbcUrl,
                venueId = fixture.venueId,
                batchId = batchId,
                name = "Кальян",
                priceMinor = 200_000,
                categoryType = "HOOKAH",
            )
        val rewardBatchItemId =
            seedBatchItem(
                jdbcUrl = jdbcUrl,
                venueId = fixture.venueId,
                batchId = batchId,
                name = "Чай",
                priceMinor = 50_000,
                categoryType = "TEA",
            )
        seedPromotionAdjustment(
            jdbcUrl = jdbcUrl,
            fixture = fixture,
            batchId = batchId,
            batchItemId = rewardBatchItemId,
            title = "Чай к кальяну",
            ruleType = "GIFT_WITH_ITEM",
            discountMinor = 50_000,
            discountPercent = 100,
            rewardLabel = "Чай в подарок",
            triggerBatchItemId = triggerBatchItemId,
        )
        return GiftFixture(
            batchId = batchId,
            triggerBatchItemId = triggerBatchItemId,
            rewardBatchItemId = rewardBatchItemId,
        )
    }

    private fun seedPromotionAdjustment(
        jdbcUrl: String,
        fixture: OrderFixture,
        batchId: Long,
        batchItemId: Long,
        title: String,
        ruleType: String,
        discountMinor: Long,
        discountPercent: Int,
        rewardLabel: String? = null,
        triggerBatchItemId: Long? = null,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val menuItemId = fetchMenuItemIdForBatchItem(connection, batchItemId)
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
                        status,
                        priority,
                        created_by_user_id
                    )
                    VALUES (NULL, ?, ?, 'CATEGORY_TYPE', 'HOOKAH', ?, 'ACTIVE', 100, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, fixture.venueId)
                    statement.setString(2, ruleType)
                    if (ruleType == "GIFT_WITH_ITEM") {
                        statement.setNull(3, java.sql.Types.INTEGER)
                    } else {
                        statement.setInt(3, discountPercent)
                    }
                    statement.setLong(4, STAFF_USER_ID)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert promotion rule")
                    }
                }
            val applicationId =
                connection.prepareStatement(
                    """
                    INSERT INTO order_promotion_applications (
                        order_id,
                        batch_id,
                        venue_id,
                        user_id,
                        promotion_id,
                        rule_id,
                        title_snapshot,
                        rule_type,
                        target_type,
                        target_value,
                        discount_percent,
                        discount_total_minor,
                        currency,
                        dedupe_key
                    )
                    VALUES (?, ?, ?, ?, NULL, ?, ?, ?, 'CATEGORY_TYPE', 'HOOKAH', ?, ?, 'RUB', ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, fixture.orderId)
                    statement.setLong(2, batchId)
                    statement.setLong(3, fixture.venueId)
                    statement.setLong(4, GUEST_USER_ID)
                    statement.setLong(5, ruleId)
                    statement.setString(6, title)
                    statement.setString(7, ruleType)
                    if (ruleType == "GIFT_WITH_ITEM") {
                        statement.setNull(8, java.sql.Types.INTEGER)
                    } else {
                        statement.setInt(8, discountPercent)
                    }
                    statement.setLong(9, discountMinor)
                    statement.setString(10, "venue-order-test:${fixture.orderId}:$batchItemId:$ruleType")
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert promotion application")
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO order_batch_item_promotion_adjustments (
                    application_id,
                    order_batch_item_id,
                    menu_item_id,
                    discount_minor,
                    discount_percent,
                    original_price_minor,
                    quantity,
                    currency
                )
                VALUES (?, ?, ?, ?, ?, ?, 1, 'RUB')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, applicationId)
                statement.setLong(2, batchItemId)
                statement.setLong(3, menuItemId)
                statement.setLong(4, discountMinor)
                statement.setInt(5, discountPercent)
                statement.setLong(6, discountMinor * 100 / discountPercent.coerceAtLeast(1))
                statement.executeUpdate()
            }
            if (rewardLabel != null) {
                connection.prepareStatement(
                    """
                    INSERT INTO order_promotion_reward_items (
                        application_id,
                        trigger_order_batch_item_id,
                        reward_order_batch_item_id,
                        reward_menu_item_id,
                        reward_qty,
                        label_snapshot
                    )
                    VALUES (?, ?, ?, ?, 1, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, applicationId)
                    if (triggerBatchItemId == null) {
                        statement.setNull(2, java.sql.Types.BIGINT)
                    } else {
                        statement.setLong(2, triggerBatchItemId)
                    }
                    statement.setLong(3, batchItemId)
                    statement.setLong(4, menuItemId)
                    statement.setString(5, rewardLabel)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun deletePromotionAdjustment(
        jdbcUrl: String,
        batchItemId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "DELETE FROM order_batch_item_promotion_adjustments WHERE order_batch_item_id = ?",
            ).use { statement ->
                statement.setLong(1, batchItemId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun fetchPromotionLedgerCounts(
        jdbcUrl: String,
        orderId: Long,
    ): PromotionLedgerCounts =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    COUNT(DISTINCT application.id) AS applications,
                    COUNT(DISTINCT adjustment.id) AS adjustments,
                    COUNT(DISTINCT reward_link.id) AS reward_links
                FROM order_promotion_applications application
                LEFT JOIN order_batch_item_promotion_adjustments adjustment
                  ON adjustment.application_id = application.id
                LEFT JOIN order_promotion_reward_items reward_link
                  ON reward_link.application_id = application.id
                WHERE application.order_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, orderId)
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    PromotionLedgerCounts(
                        applications = rs.getInt("applications"),
                        adjustments = rs.getInt("adjustments"),
                        rewardLinks = rs.getInt("reward_links"),
                    )
                }
            }
        }

    private fun fetchGiftPromotionLedgerSnapshot(
        jdbcUrl: String,
        orderId: Long,
    ): GiftPromotionLedgerSnapshot =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    application.id AS application_id,
                    reward_link.id AS reward_link_id,
                    reward_link.trigger_order_batch_item_id,
                    reward_link.reward_order_batch_item_id,
                    application.rule_id,
                    application.title_snapshot,
                    application.rule_type,
                    application.target_type,
                    application.target_value,
                    application.discount_total_minor,
                    application.rule_version,
                    application.schedule_snapshot_json,
                    application.target_snapshot_json,
                    application.original_total_minor,
                    application.final_total_minor,
                    application.venue_timezone_snapshot,
                    reward_link.reward_menu_item_id,
                    reward_link.reward_qty,
                    reward_link.label_snapshot
                FROM order_promotion_applications application
                JOIN order_promotion_reward_items reward_link
                  ON reward_link.application_id = application.id
                WHERE application.order_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, orderId)
                statement.executeQuery().use { rs ->
                    assertTrue(rs.next(), "Expected gift promotion ledger for order $orderId")
                    val snapshot =
                        GiftPromotionLedgerSnapshot(
                            applicationId = rs.getLong("application_id"),
                            rewardLinkId = rs.getLong("reward_link_id"),
                            triggerBatchItemId = rs.getLong("trigger_order_batch_item_id"),
                            rewardBatchItemId = rs.getLong("reward_order_batch_item_id"),
                            ruleId = rs.getLong("rule_id"),
                            titleSnapshot = rs.getString("title_snapshot"),
                            ruleType = rs.getString("rule_type"),
                            targetType = rs.getString("target_type"),
                            targetValue = rs.getString("target_value"),
                            discountTotalMinor = rs.getLong("discount_total_minor"),
                            ruleVersion = rs.getInt("rule_version"),
                            scheduleSnapshotJson = rs.getString("schedule_snapshot_json"),
                            targetSnapshotJson = rs.getString("target_snapshot_json"),
                            originalTotalMinor = rs.getLong("original_total_minor"),
                            finalTotalMinor = rs.getLong("final_total_minor"),
                            venueTimezoneSnapshot = rs.getString("venue_timezone_snapshot"),
                            rewardMenuItemId = rs.getLong("reward_menu_item_id"),
                            rewardQty = rs.getInt("reward_qty"),
                            labelSnapshot = rs.getString("label_snapshot"),
                        )
                    assertFalse(rs.next(), "Expected exactly one gift promotion ledger for order $orderId")
                    snapshot
                }
            }
        }

    private fun fetchBatchItemDiscountPercent(
        jdbcUrl: String,
        batchItemId: Long,
    ): Int? =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection
                .prepareStatement("SELECT discount_percent FROM order_batch_items WHERE id = ?")
                .use { statement ->
                    statement.setLong(1, batchItemId)
                    statement.executeQuery().use { rs ->
                        check(rs.next())
                        rs.getInt("discount_percent").let { value -> if (rs.wasNull()) null else value }
                    }
                }
        }

    private fun fetchBatchItemLifecycle(
        jdbcUrl: String,
        batchItemId: Long,
    ): BatchItemLifecycle =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COALESCE(item_status, 'ACTIVE') AS item_status,
                       is_excluded,
                       canceled_reason_code,
                       canceled_reason_text,
                       excluded_reason_text
                FROM order_batch_items
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, batchItemId)
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    BatchItemLifecycle(
                        itemStatus = rs.getString("item_status"),
                        isExcluded = rs.getBoolean("is_excluded"),
                        canceledReasonCode = rs.getString("canceled_reason_code"),
                        canceledReasonText = rs.getString("canceled_reason_text"),
                        excludedReasonText = rs.getString("excluded_reason_text"),
                    )
                }
            }
        }

    private fun fetchOrderAuditCount(
        jdbcUrl: String,
        orderId: Long,
        action: String,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM order_audit_log WHERE order_id = ? AND action = ?",
            ).use { statement ->
                statement.setLong(1, orderId)
                statement.setString(2, action)
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getInt(1)
                }
            }
        }

    private fun countOrphanActiveRewards(
        jdbcUrl: String,
        orderId: Long,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM order_promotion_reward_items reward_link
                JOIN order_promotion_applications application
                  ON application.id = reward_link.application_id
                JOIN order_batch_items trigger_item
                  ON trigger_item.id = reward_link.trigger_order_batch_item_id
                JOIN order_batches trigger_batch
                  ON trigger_batch.id = trigger_item.order_batch_id
                JOIN order_batch_items reward_item
                  ON reward_item.id = reward_link.reward_order_batch_item_id
                WHERE application.order_id = ?
                  AND reward_item.is_excluded = FALSE
                  AND COALESCE(reward_item.item_status, 'ACTIVE') = 'ACTIVE'
                  AND (
                      trigger_item.is_excluded = TRUE
                      OR COALESCE(trigger_item.item_status, 'ACTIVE') <> 'ACTIVE'
                      OR trigger_batch.status = 'REJECTED'
                  )
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, orderId)
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getInt(1)
                }
            }
        }

    private fun fetchMenuItemIdForBatchItem(
        connection: java.sql.Connection,
        batchItemId: Long,
    ): Long =
        connection.prepareStatement("SELECT menu_item_id FROM order_batch_items WHERE id = ?").use { statement ->
            statement.setLong(1, batchItemId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getLong("menu_item_id") else error("Missing batch item $batchItemId")
            }
        }

    private fun updateMenuItemPriceForBatchItem(
        jdbcUrl: String,
        batchItemId: Long,
        priceMinor: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                UPDATE menu_items
                SET price_minor = ?
                WHERE id = (
                    SELECT menu_item_id
                    FROM order_batch_items
                    WHERE id = ?
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, priceMinor)
                statement.setLong(2, batchItemId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun updateMenuItemCurrencyForBatchItem(
        jdbcUrl: String,
        batchItemId: Long,
        currency: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                UPDATE menu_items
                SET currency = ?
                WHERE id = (
                    SELECT menu_item_id
                    FROM order_batch_items
                    WHERE id = ?
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, currency)
                statement.setLong(2, batchItemId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun fetchPromotionAdjustment(
        jdbcUrl: String,
        batchItemId: Long,
    ): Pair<Long, Int>? =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT discount_minor, discount_percent
                FROM order_batch_item_promotion_adjustments
                WHERE order_batch_item_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, batchItemId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong("discount_minor") to rs.getInt("discount_percent") else null
                }
            }
        }

    private fun seedActiveLoyaltyProgram(
        jdbcUrl: String,
        venueId: Long,
        nthValue: Int,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
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
                    VALUES (?, 'NTH_HOOKAH_FREE', 'ACTIVE', ?, 1, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setInt(2, nthValue)
                    statement.setLong(3, STAFF_USER_ID)
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
            return programId
        }
    }

    private fun replaceLoyaltyEarnTargetWithMenuItem(
        jdbcUrl: String,
        programId: Long,
        menuItemId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("DELETE FROM loyalty_program_earn_targets WHERE program_id = ?").use {
                    statement ->
                statement.setLong(1, programId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO loyalty_program_earn_targets (program_id, target_type, menu_item_id)
                VALUES (?, 'MENU_ITEM', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, programId)
                statement.setLong(2, menuItemId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedVenueMember(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        role: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
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
    }

    private fun markBatchItemStatus(
        jdbcUrl: String,
        batchItemId: Long,
        status: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("UPDATE order_batch_items SET item_status = ? WHERE id = ?").use { statement ->
                statement.setString(1, status)
                statement.setLong(2, batchItemId)
                statement.executeUpdate()
            }
        }
    }

    private fun fetchLoyaltyProgress(
        jdbcUrl: String,
        programId: Long,
        userId: Long,
    ): Pair<Int, Int> {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT progress_count, rewards_available
                FROM guest_loyalty_progress
                WHERE program_id = ? AND user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, programId)
                statement.setLong(2, userId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getInt("progress_count") to rs.getInt("rewards_available")
                    }
                }
            }
        }
        return 0 to 0
    }

    private fun fetchLoyaltyLedgerCount(
        jdbcUrl: String,
        programId: Long,
    ): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM guest_loyalty_ledger WHERE program_id = ?").use {
                    statement ->
                statement.setLong(1, programId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) return rs.getInt(1)
                }
            }
        }
        return 0
    }

    private fun fetchLoyaltyProgramStatus(
        jdbcUrl: String,
        programId: Long,
    ): String {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT status FROM loyalty_programs WHERE id = ?").use { statement ->
                statement.setLong(1, programId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) return rs.getString("status")
                }
            }
        }
        error("Missing loyalty program $programId")
    }

    private fun fetchBatchItemStatus(
        jdbcUrl: String,
        batchItemId: Long,
    ): String {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT item_status FROM order_batch_items WHERE id = ?").use { statement ->
                statement.setLong(1, batchItemId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) return rs.getString("item_status")
                }
            }
        }
        error("Missing batch item $batchItemId")
    }

    private fun markOrderClosed(
        jdbcUrl: String,
        orderId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("UPDATE orders SET status = 'CLOSED' WHERE id = ?").use { statement ->
                statement.setLong(1, orderId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedOrderClosedVisit(
        jdbcUrl: String,
        fixture: OrderFixture,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO visits (
                    venue_id,
                    user_id,
                    table_session_id,
                    order_id,
                    source,
                    occurred_at,
                    service_date
                )
                VALUES (?, ?, ?, ?, 'ORDER_CLOSED', CURRENT_TIMESTAMP, CURRENT_DATE)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, fixture.venueId)
                statement.setLong(2, GUEST_USER_ID)
                statement.setLong(3, fixture.tableSessionId)
                statement.setLong(4, fixture.orderId)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    check(keys.next())
                    keys.getLong(1)
                }
            }
        }

    private fun fetchBatchStatus(
        jdbcUrl: String,
        batchId: Long,
    ): String {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT status FROM order_batches WHERE id = ?").use { statement ->
                statement.setLong(1, batchId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) return rs.getString("status")
                }
            }
        }
        error("Missing batch $batchId")
    }

    private data class GiftFixture(
        val batchId: Long,
        val triggerBatchItemId: Long,
        val rewardBatchItemId: Long,
    )

    private data class PromotionLedgerCounts(
        val applications: Int,
        val adjustments: Int,
        val rewardLinks: Int,
    )

    private data class GiftPromotionLedgerSnapshot(
        val applicationId: Long,
        val rewardLinkId: Long,
        val triggerBatchItemId: Long,
        val rewardBatchItemId: Long,
        val ruleId: Long,
        val titleSnapshot: String,
        val ruleType: String,
        val targetType: String,
        val targetValue: String,
        val discountTotalMinor: Long,
        val ruleVersion: Int,
        val scheduleSnapshotJson: String?,
        val targetSnapshotJson: String?,
        val originalTotalMinor: Long,
        val finalTotalMinor: Long,
        val venueTimezoneSnapshot: String?,
        val rewardMenuItemId: Long,
        val rewardQty: Int,
        val labelSnapshot: String,
    )

    private data class BatchItemLifecycle(
        val itemStatus: String,
        val isExcluded: Boolean,
        val canceledReasonCode: String?,
        val canceledReasonText: String?,
        val excludedReasonText: String?,
    )

    private data class OrderFixture(
        val venueId: Long,
        val orderId: Long,
        val tableSessionId: Long,
    )

    private companion object {
        const val STAFF_USER_ID = 42L
        const val GUEST_USER_ID = 200L
    }
}
