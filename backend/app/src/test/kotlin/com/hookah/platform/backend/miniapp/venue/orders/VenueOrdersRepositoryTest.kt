package com.hookah.platform.backend.miniapp.venue.orders

import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.telegram.db.LoyaltyProgramStatus
import com.hookah.platform.backend.telegram.db.LoyaltyRepository
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
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
            seedBatchItem(jdbcUrl, fixture.venueId, deliveredBatchId, "Исключённый кальян", categoryType = "HOOKAH", isExcluded = true)
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
                seedBatch(jdbcUrl, fixture.orderId, "DELIVERED", Instant.now().minusSeconds(20), authorUserId = STAFF_USER_ID)
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
            val selectedBatchItemId = seedBatchItem(jdbcUrl, fixture.venueId, batchId, "Кальян обычный", categoryType = "HOOKAH")
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

            val updated = repository.createOrUpdateDraftProgram(fixture.venueId, nthValue = 6, createdByUserId = STAFF_USER_ID)

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
            val batchItemId = seedBatchItem(jdbcUrl, fixture.venueId, batchId, "Кальян обычный", categoryType = "HOOKAH")
            val menuItemId =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    fetchMenuItemIdForBatchItem(connection, batchItemId)
                }
            val repository = LoyaltyRepository(dataSource(jdbcUrl))

            val earnUpdated = repository.replaceEarnTargetsWithMenuItems(fixture.venueId, programId, listOf(menuItemId))
            val rewardUpdated = repository.replaceRewardTargetsWithMenuItems(fixture.venueId, programId, listOf(menuItemId))

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

    private fun migratedJdbcUrl(prefix: String): String {
        val jdbcUrl =
            "jdbc:h2:mem:$prefix-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
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
            connection.prepareStatement("DELETE FROM loyalty_program_earn_targets WHERE program_id = ?").use { statement ->
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
            connection.prepareStatement("SELECT COUNT(*) FROM guest_loyalty_ledger WHERE program_id = ?").use { statement ->
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
