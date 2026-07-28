package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.promotions.GiftDecisionCommand
import com.hookah.platform.backend.promotions.GiftDecisionScopeTokenService
import com.hookah.platform.backend.promotions.PromotionGiftDecisionAction
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromotionConfigurationConcurrencyPostgresTest {
    @Test
    fun `submit that locks version one persists the complete old configuration while edit waits`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val submitDataSource = ChildReadBarrierDataSource(dataSource)
                val editDataSource = TrackedPromotionMutationDataSource(dataSource)
                val submitRepository = ordersRepository(submitDataSource, OLD_ACTIVE_INSTANT)

                val (submittedValue, _) =
                    runWithBlockedMutation(
                        observerDataSource = dataSource,
                        snapshotDataSource = submitDataSource,
                        mutationDataSource = editDataSource,
                        snapshotAction = {
                            runBlocking {
                                submitRepository.createGuestOrderBatch(
                                    tableId = fixture.tableId,
                                    venueId = fixture.venueId,
                                    tableSessionId = fixture.tableSessionId,
                                    userId = USER_ID,
                                    idempotencyKey = "promotion-concurrency-old",
                                    tabId = fixture.tabId,
                                    comment = null,
                                    items = fixture.cartItems,
                                    venueZoneId = UTC,
                                )
                            }
                        },
                        mutationAction = {
                            runBlocking {
                                editToVersionTwo(editDataSource, fixture)
                            }
                        },
                    )

                val submitted = assertNotNull(submittedValue)
                assertSubmittedOldPricing(submitted, fixture)
                assertLedgerSnapshot(
                    snapshot = readLedgerSnapshot(dataSource, submitted.batchId),
                    expectedTitle = OLD_TITLE,
                    expectedVersion = 1,
                    expectedPercent = 20,
                    expectedItemId = fixture.itemAId,
                    expectedStartsMinute = 10 * 60,
                    expectedEndsMinute = 12 * 60,
                    expectedOriginalMinor = 10_000L,
                    expectedDiscountMinor = 2_000L,
                    expectedFinalMinor = 8_000L,
                )
                assertLiveVersionTwo(dataSource, fixture)

                val immutableSnapshot = readLedgerSnapshot(dataSource, submitted.batchId)
                assertEquals(1, immutableSnapshot.ruleVersion)
                assertEquals(20, immutableSnapshot.applicationDiscountPercent)
                assertEquals(fixture.itemAId, immutableSnapshot.adjustedMenuItemId)
            }
        }

    @Test
    fun `submit after committed edit persists the complete new configuration`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                assertNotNull(editToVersionTwo(dataSource, fixture))
                assertLiveVersionTwo(dataSource, fixture)

                val submitted =
                    assertNotNull(
                        ordersRepository(dataSource, NEW_ACTIVE_INSTANT).createGuestOrderBatch(
                            tableId = fixture.tableId,
                            venueId = fixture.venueId,
                            tableSessionId = fixture.tableSessionId,
                            userId = USER_ID,
                            idempotencyKey = "promotion-concurrency-new",
                            tabId = fixture.tabId,
                            comment = null,
                            items = fixture.cartItems,
                            venueZoneId = UTC,
                        ),
                    )

                val pricing = assertNotNull(submitted.pricing)
                assertEquals(30_000L, pricing.grossTotalMinor)
                assertEquals(10_000L, pricing.promoDiscountTotalMinor)
                assertEquals(20_000L, pricing.finalPayableTotalMinor)
                assertNull(pricing.items.single { it.itemId == fixture.itemAId }.promotionAdjustment)
                val itemB = pricing.items.single { it.itemId == fixture.itemBId }
                assertEquals(10_000L, itemB.discountMinor)
                assertEquals(2, itemB.promotionAdjustment?.ruleVersion)

                assertLedgerSnapshot(
                    snapshot = readLedgerSnapshot(dataSource, submitted.batchId),
                    expectedTitle = NEW_TITLE,
                    expectedVersion = 2,
                    expectedPercent = 50,
                    expectedItemId = fixture.itemBId,
                    expectedStartsMinute = 14 * 60,
                    expectedEndsMinute = 18 * 60,
                    expectedOriginalMinor = 20_000L,
                    expectedDiscountMinor = 10_000L,
                    expectedFinalMinor = 10_000L,
                )
            }
        }

    @Test
    fun `concurrent pause waits for submit and later submits see the complete paused state`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val submitDataSource = ChildReadBarrierDataSource(dataSource)
                val pauseDataSource = TrackedPromotionMutationDataSource(dataSource)

                val (submittedValue, _) =
                    runWithBlockedMutation(
                        observerDataSource = dataSource,
                        snapshotDataSource = submitDataSource,
                        mutationDataSource = pauseDataSource,
                        snapshotAction = {
                            runBlocking {
                                ordersRepository(submitDataSource, OLD_ACTIVE_INSTANT).createGuestOrderBatch(
                                    tableId = fixture.tableId,
                                    venueId = fixture.venueId,
                                    tableSessionId = fixture.tableSessionId,
                                    userId = USER_ID,
                                    idempotencyKey = "promotion-concurrency-pause-before",
                                    tabId = fixture.tabId,
                                    comment = null,
                                    items = fixture.cartItems,
                                    venueZoneId = UTC,
                                )
                            }
                        },
                        mutationAction = {
                            runBlocking {
                                pausePromotion(pauseDataSource, fixture)
                            }
                        },
                    )

                val submitted = assertNotNull(submittedValue)
                assertSubmittedOldPricing(submitted, fixture)
                assertLedgerSnapshot(
                    snapshot = readLedgerSnapshot(dataSource, submitted.batchId),
                    expectedTitle = OLD_TITLE,
                    expectedVersion = 1,
                    expectedPercent = 20,
                    expectedItemId = fixture.itemAId,
                    expectedStartsMinute = 10 * 60,
                    expectedEndsMinute = 12 * 60,
                    expectedOriginalMinor = 10_000L,
                    expectedDiscountMinor = 2_000L,
                    expectedFinalMinor = 8_000L,
                )

                val promotion =
                    assertNotNull(
                        VenuePromotionRepository(dataSource)
                            .getPromotionForManagement(fixture.venueId, fixture.promotionId),
                    )
                val rule =
                    assertNotNull(
                        VenuePromotionRuleRepository(dataSource)
                            .getRuleForManagement(fixture.venueId, fixture.ruleId),
                    )
                assertEquals(VenuePromotionStatus.PAUSED, promotion.status)
                assertEquals(VenuePromotionStatus.PAUSED, rule.status)

                val submittedAfterPause =
                    assertNotNull(
                        ordersRepository(dataSource, OLD_ACTIVE_INSTANT).createGuestOrderBatch(
                            tableId = fixture.tableId,
                            venueId = fixture.venueId,
                            tableSessionId = fixture.tableSessionId,
                            userId = USER_ID,
                            idempotencyKey = "promotion-concurrency-pause-after",
                            tabId = fixture.tabId,
                            comment = null,
                            items = fixture.cartItems,
                            venueZoneId = UTC,
                        ),
                    )
                val pricingAfterPause = assertNotNull(submittedAfterPause.pricing)
                assertTrue(submittedAfterPause.promotionDiscounts.isEmpty())
                assertEquals(0L, pricingAfterPause.promoDiscountTotalMinor)
                assertEquals(30_000L, pricingAfterPause.finalPayableTotalMinor)
                assertEquals(1, countRows(dataSource, "order_promotion_applications"))
                assertEquals(1, countRows(dataSource, "order_batch_item_promotion_adjustments"))
            }
        }

    @Test
    fun `failure after promotion ledger writes rolls back the whole financial submit`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val baseline = readFinancialState(dataSource)
                val inFlightState = AtomicReference<FinancialState?>()
                val failingDataSource =
                    FailBeforeIdempotencyDataSource(dataSource) { connection ->
                        inFlightState.set(readFinancialState(connection))
                    }

                assertFailsWith<DatabaseUnavailableException> {
                    ordersRepository(failingDataSource, OLD_ACTIVE_INSTANT).createGuestOrderBatch(
                        tableId = fixture.tableId,
                        venueId = fixture.venueId,
                        tableSessionId = fixture.tableSessionId,
                        userId = USER_ID,
                        idempotencyKey = "promotion-concurrency-forced-rollback",
                        tabId = fixture.tabId,
                        comment = null,
                        items = fixture.cartItems,
                        venueZoneId = UTC,
                    )
                }

                val observedInFlight = assertNotNull(inFlightState.get())
                assertEquals(1, observedInFlight.orders)
                assertEquals(1, observedInFlight.batches)
                assertEquals(2, observedInFlight.batchItems)
                assertEquals(1, observedInFlight.applications)
                assertEquals(1, observedInFlight.adjustments)
                assertEquals(0, observedInFlight.idempotencyRows)
                assertEquals(30_000L, observedInFlight.grossMinor)
                assertEquals(2_000L, observedInFlight.discountMinor)

                assertEquals(baseline, readFinancialState(dataSource))
            }
        }

    @Test
    fun `preview holds one complete configuration snapshot and remains read only`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val previewDataSource = ChildReadBarrierDataSource(dataSource)
                val editDataSource = TrackedPromotionMutationDataSource(dataSource)

                val (previewValue, _) =
                    runWithBlockedMutation(
                        observerDataSource = dataSource,
                        snapshotDataSource = previewDataSource,
                        mutationDataSource = editDataSource,
                        snapshotAction = {
                            runBlocking {
                                ordersRepository(previewDataSource, OLD_ACTIVE_INSTANT).previewGuestOrderBatch(
                                    venueId = fixture.venueId,
                                    userId = USER_ID,
                                    items = fixture.cartItems,
                                    venueZoneId = UTC,
                                )
                            }
                        },
                        mutationAction = {
                            runBlocking {
                                editToVersionTwo(editDataSource, fixture)
                            }
                        },
                    )

                val oldPreview = assertNotNull(previewValue)
                assertEquals(2_000L, oldPreview.promoDiscountTotalMinor)
                assertEquals(
                    1,
                    oldPreview.items.single { it.itemId == fixture.itemAId }.promotionAdjustment?.ruleVersion,
                )
                assertNull(oldPreview.items.single { it.itemId == fixture.itemBId }.promotionAdjustment)
                assertEquals(0, countRows(dataSource, "orders"))
                assertEquals(0, countRows(dataSource, "order_batches"))
                assertEquals(0, countRows(dataSource, "order_promotion_applications"))

                val newPreview =
                    assertNotNull(
                        ordersRepository(dataSource, NEW_ACTIVE_INSTANT).previewGuestOrderBatch(
                            venueId = fixture.venueId,
                            userId = USER_ID,
                            items = fixture.cartItems,
                            venueZoneId = UTC,
                        ),
                    )
                assertEquals(10_000L, newPreview.promoDiscountTotalMinor)
                assertNull(newPreview.items.single { it.itemId == fixture.itemAId }.promotionAdjustment)
                assertEquals(
                    2,
                    newPreview.items.single { it.itemId == fixture.itemBId }.promotionAdjustment?.ruleVersion,
                )
                assertEquals(0, countRows(dataSource, "orders"))
                assertEquals(0, countRows(dataSource, "order_batches"))
                assertEquals(0, countRows(dataSource, "order_promotion_applications"))
            }
        }

    @Test
    fun `simultaneous fixed gift submits with one idempotency key persist one complete result`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedGiftConcurrencyFixture(dataSource)
                val scopeService =
                    GiftDecisionScopeTokenService(
                        signingSecret = GIFT_SCOPE_SECRET,
                        clock = Clock.fixed(OLD_ACTIVE_INSTANT, ZoneOffset.UTC),
                    )
                val preview =
                    assertNotNull(
                        ordersRepository(
                            dataSource = dataSource,
                            now = OLD_ACTIVE_INSTANT,
                            giftDecisionScopeTokenService = scopeService,
                        ).previewGuestOrderBatch(
                            venueId = fixture.venueId,
                            userId = USER_ID,
                            items = fixture.cartItems,
                            venueZoneId = UTC,
                            tableSessionId = fixture.tableSessionId,
                            tabId = fixture.tabId,
                            comment = null,
                        ),
                    )
                assertEquals(
                    com.hookah.platform.backend.promotions.PromotionGiftOfferStatus.FIXED_GIFT_AVAILABLE,
                    preview.giftOffer.status,
                )
                val command =
                    GiftDecisionCommand(
                        action = PromotionGiftDecisionAction.ACCEPT_FIXED,
                        decisionScopeToken = assertNotNull(preview.decisionScopeToken),
                    )
                val submitDataSource = SubmitTransactionBarrierDataSource(dataSource)
                val repository =
                    ordersRepository(
                        dataSource = submitDataSource,
                        now = OLD_ACTIVE_INSTANT,
                        giftDecisionScopeTokenService =
                            GiftDecisionScopeTokenService(
                                signingSecret = GIFT_SCOPE_SECRET,
                                clock = Clock.fixed(OLD_ACTIVE_INSTANT, ZoneOffset.UTC),
                            ),
                    )
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val futures =
                        (1..2).map {
                            executor.submit(
                                Callable {
                                    runBlocking {
                                        repository.createGuestOrderBatch(
                                            tableId = fixture.tableId,
                                            venueId = fixture.venueId,
                                            tableSessionId = fixture.tableSessionId,
                                            userId = USER_ID,
                                            idempotencyKey = "gift-same-idempotency-key",
                                            tabId = fixture.tabId,
                                            comment = null,
                                            items = fixture.cartItems,
                                            venueZoneId = UTC,
                                            giftDecisionCommand = command,
                                            expectedPreviewFingerprint = preview.pricingFingerprint,
                                        )
                                    }
                                },
                            )
                        }
                    val results =
                        futures.map { future ->
                            assertNotNull(future.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        }

                    assertEquals(2, submitDataSource.backendPids.size)
                    assertEquals(1, results.map { it.batchId }.distinct().size)
                    assertEquals(listOf(false, true), results.map { it.idempotencyReplay }.sorted())
                    assertEquals(1, countRows(dataSource, "order_batches"))
                    assertEquals(
                        1,
                        countWhere(
                            dataSource,
                            "order_batch_items",
                            "id IN (SELECT reward_order_batch_item_id FROM order_promotion_reward_items)",
                        ),
                    )
                    assertEquals(1, countRows(dataSource, "order_promotion_applications"))
                    assertEquals(1, countRows(dataSource, "order_batch_item_promotion_adjustments"))
                    assertEquals(1, countRows(dataSource, "order_promotion_reward_items"))
                    assertEquals(1, countRows(dataSource, "guest_batch_idempotency"))
                } finally {
                    executor.shutdownNow()
                    executor.awaitTermination(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
        }

    private suspend fun seedFixture(dataSource: DataSource): PromotionFixture {
        val base =
            dataSource.connection.use { connection ->
                val originalAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        """
                        INSERT INTO users (telegram_user_id, first_name)
                        VALUES (?, 'Promotion concurrency guest')
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, USER_ID)
                        statement.executeUpdate()
                    }
                    val venueId =
                        insertReturningId(
                            connection,
                            """
                            INSERT INTO venues (name, city, address, status)
                            VALUES ('Promotion concurrency venue', 'Moscow', 'Address', ?)
                            """.trimIndent(),
                        ) { statement ->
                            statement.setString(1, VenueStatus.PUBLISHED.dbValue)
                        }
                    val categoryId =
                        insertReturningId(
                            connection,
                            """
                            INSERT INTO menu_categories (venue_id, name, category_type)
                            VALUES (?, 'Hookahs', 'HOOKAH')
                            """.trimIndent(),
                        ) { statement ->
                            statement.setLong(1, venueId)
                        }
                    val itemAId =
                        insertMenuItem(
                            connection = connection,
                            venueId = venueId,
                            categoryId = categoryId,
                            name = "Item A",
                            priceMinor = 10_000L,
                        )
                    val itemBId =
                        insertMenuItem(
                            connection = connection,
                            venueId = venueId,
                            categoryId = categoryId,
                            name = "Item B",
                            priceMinor = 20_000L,
                        )
                    val tableId =
                        insertReturningId(
                            connection,
                            """
                            INSERT INTO venue_tables (venue_id, table_number, is_active)
                            VALUES (?, 1, TRUE)
                            """.trimIndent(),
                        ) { statement ->
                            statement.setLong(1, venueId)
                        }
                    val tableSessionId =
                        insertReturningId(
                            connection,
                            """
                            INSERT INTO table_sessions (
                                venue_id,
                                table_id,
                                started_at,
                                last_activity_at,
                                expires_at,
                                status
                            )
                            VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                            """.trimIndent(),
                        ) { statement ->
                            statement.setLong(1, venueId)
                            statement.setLong(2, tableId)
                            statement.setTimestamp(3, Timestamp.from(PROMOTION_START))
                            statement.setTimestamp(4, Timestamp.from(PROMOTION_START))
                            statement.setTimestamp(5, Timestamp.from(PROMOTION_END))
                        }
                    val tabId =
                        insertReturningId(
                            connection,
                            """
                            INSERT INTO tab (venue_id, table_session_id, type, owner_user_id, status)
                            VALUES (?, ?, 'PERSONAL', ?, 'ACTIVE')
                            """.trimIndent(),
                        ) { statement ->
                            statement.setLong(1, venueId)
                            statement.setLong(2, tableSessionId)
                            statement.setLong(3, USER_ID)
                        }
                    connection.prepareStatement(
                        """
                        INSERT INTO tab_member (tab_id, user_id, role)
                        VALUES (?, ?, 'OWNER')
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, tabId)
                        statement.setLong(2, USER_ID)
                        statement.executeUpdate()
                    }
                    connection.commit()
                    BaseFixture(
                        venueId = venueId,
                        tableId = tableId,
                        tableSessionId = tableSessionId,
                        tabId = tabId,
                        itemAId = itemAId,
                        itemBId = itemBId,
                    )
                } catch (e: Exception) {
                    connection.rollback()
                    throw e
                } finally {
                    connection.autoCommit = originalAutoCommit
                }
            }

        val promotionRepository = VenuePromotionRepository(dataSource)
        val ruleRepository = VenuePromotionRuleRepository(dataSource)
        val promotion =
            promotionRepository.createPromotion(
                venueId = base.venueId,
                title = OLD_TITLE,
                description = "Version one",
                terms = null,
                startsAt = PROMOTION_START,
                endsAt = PROMOTION_END,
                templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                createdByUserId = USER_ID,
            )
        val rule =
            ruleRepository.createHappyHoursDraftRule(
                venueId = base.venueId,
                promotionId = promotion.id,
                target =
                    HappyHoursRuleTargetInput(
                        targetType = PromotionRuleTargetType.MENU_ITEM,
                        menuItemId = base.itemAId,
                    ),
                discountPercent = 20,
                weekdayWindows =
                    listOf(
                        PromotionWeekdayWindow(
                            weekday = MONDAY,
                            startsMinute = 10 * 60,
                            endsMinute = 12 * 60,
                        ),
                    ),
                createdByUserId = USER_ID,
            )
        assertNotNull(
            promotionRepository.setPromotionStatus(
                venueId = base.venueId,
                promotionId = promotion.id,
                status = VenuePromotionStatus.ACTIVE,
                afterUpdate = { connection, _ ->
                    ruleRepository.synchronizeHappyHoursPromotionStatus(
                        connection = connection,
                        venueId = base.venueId,
                        promotionId = promotion.id,
                        status = VenuePromotionStatus.ACTIVE,
                    )
                },
            ),
        )
        return PromotionFixture(
            venueId = base.venueId,
            tableId = base.tableId,
            tableSessionId = base.tableSessionId,
            tabId = base.tabId,
            itemAId = base.itemAId,
            itemBId = base.itemBId,
            promotionId = promotion.id,
            ruleId = rule.id,
        )
    }

    private suspend fun seedGiftConcurrencyFixture(dataSource: DataSource): GiftConcurrencyFixture {
        val base =
            dataSource.connection.use { connection ->
                val originalAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        """
                        INSERT INTO users (telegram_user_id, first_name)
                        VALUES (?, 'Gift concurrency guest')
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, USER_ID)
                        statement.executeUpdate()
                    }
                    val venueId =
                        insertReturningId(
                            connection,
                            """
                            INSERT INTO venues (name, city, address, status)
                            VALUES ('Gift concurrency venue', 'Moscow', 'Address', ?)
                            """.trimIndent(),
                        ) { statement ->
                            statement.setString(1, VenueStatus.PUBLISHED.dbValue)
                        }
                    val categoryId =
                        insertReturningId(
                            connection,
                            """
                            INSERT INTO menu_categories (venue_id, name, category_type)
                            VALUES (?, 'Gift fixture', 'HOOKAH')
                            """.trimIndent(),
                        ) { statement ->
                            statement.setLong(1, venueId)
                        }
                    val triggerItemId =
                        insertMenuItem(
                            connection = connection,
                            venueId = venueId,
                            categoryId = categoryId,
                            name = "Gift trigger",
                            priceMinor = 10_000L,
                        )
                    val rewardItemId =
                        insertMenuItem(
                            connection = connection,
                            venueId = venueId,
                            categoryId = categoryId,
                            name = "Gift reward",
                            priceMinor = 2_000L,
                        )
                    val tableId =
                        insertReturningId(
                            connection,
                            """
                            INSERT INTO venue_tables (venue_id, table_number, is_active)
                            VALUES (?, 2, TRUE)
                            """.trimIndent(),
                        ) { statement ->
                            statement.setLong(1, venueId)
                        }
                    val tableSessionId =
                        insertReturningId(
                            connection,
                            """
                            INSERT INTO table_sessions (
                                venue_id,
                                table_id,
                                started_at,
                                last_activity_at,
                                expires_at,
                                status
                            )
                            VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                            """.trimIndent(),
                        ) { statement ->
                            statement.setLong(1, venueId)
                            statement.setLong(2, tableId)
                            statement.setTimestamp(3, Timestamp.from(PROMOTION_START))
                            statement.setTimestamp(4, Timestamp.from(PROMOTION_START))
                            statement.setTimestamp(5, Timestamp.from(PROMOTION_END))
                        }
                    val tabId =
                        insertReturningId(
                            connection,
                            """
                            INSERT INTO tab (venue_id, table_session_id, type, owner_user_id, status)
                            VALUES (?, ?, 'PERSONAL', ?, 'ACTIVE')
                            """.trimIndent(),
                        ) { statement ->
                            statement.setLong(1, venueId)
                            statement.setLong(2, tableSessionId)
                            statement.setLong(3, USER_ID)
                        }
                    connection.prepareStatement(
                        """
                        INSERT INTO tab_member (tab_id, user_id, role)
                        VALUES (?, ?, 'OWNER')
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, tabId)
                        statement.setLong(2, USER_ID)
                        statement.executeUpdate()
                    }
                    connection.commit()
                    GiftConcurrencyFixture(
                        venueId = venueId,
                        tableId = tableId,
                        tableSessionId = tableSessionId,
                        tabId = tabId,
                        triggerItemId = triggerItemId,
                        rewardItemId = rewardItemId,
                    )
                } catch (e: Exception) {
                    connection.rollback()
                    throw e
                } finally {
                    connection.autoCommit = originalAutoCommit
                }
            }
        val promotionRepository = VenuePromotionRepository(dataSource)
        val ruleRepository = VenuePromotionRuleRepository(dataSource)
        val promotion =
            promotionRepository.createPromotion(
                venueId = base.venueId,
                title = "Gift concurrency",
                description = "One fixed gift",
                terms = null,
                startsAt = PROMOTION_START,
                endsAt = PROMOTION_END,
                templateType = VenuePromotionTemplateType.GIFT_WITH_ITEM,
                createdByUserId = USER_ID,
            )
        ruleRepository.createGiftWithItemDraftRule(
            venueId = base.venueId,
            promotionId = promotion.id,
            target =
                HappyHoursRuleTargetInput(
                    targetType = PromotionRuleTargetType.MENU_ITEM,
                    menuItemId = base.triggerItemId,
                ),
            reward =
                GiftWithItemRewardInput(
                    mode = PromotionRewardMode.FIXED_ITEM,
                    fixedMenuItemId = base.rewardItemId,
                ),
            weekdayWindows =
                listOf(
                    PromotionWeekdayWindow(
                        weekday = MONDAY,
                        startsMinute = 0,
                        endsMinute = 24 * 60,
                    ),
                ),
            createdByUserId = USER_ID,
        )
        assertNotNull(
            promotionRepository.setPromotionStatus(
                venueId = base.venueId,
                promotionId = promotion.id,
                status = VenuePromotionStatus.ACTIVE,
                afterUpdate = { connection, _ ->
                    ruleRepository.synchronizeGiftWithItemPromotionStatus(
                        connection = connection,
                        venueId = base.venueId,
                        promotionId = promotion.id,
                        status = VenuePromotionStatus.ACTIVE,
                    )
                },
            ),
        )
        return base
    }

    private suspend fun editToVersionTwo(
        dataSource: DataSource,
        fixture: PromotionFixture,
    ): VenuePromotion? {
        val promotionRepository = VenuePromotionRepository(dataSource)
        val ruleRepository = VenuePromotionRuleRepository(dataSource)
        return promotionRepository.updatePromotion(
            venueId = fixture.venueId,
            promotionId = fixture.promotionId,
            title = NEW_TITLE,
            afterUpdate = { connection, _ ->
                checkNotNull(
                    ruleRepository.updateHappyHoursDraftRule(
                        connection = connection,
                        venueId = fixture.venueId,
                        promotionId = fixture.promotionId,
                        ruleId = fixture.ruleId,
                        target =
                            HappyHoursRuleTargetInput(
                                targetType = PromotionRuleTargetType.MENU_ITEM,
                                menuItemId = fixture.itemBId,
                            ),
                        discountPercent = 50,
                        weekdayWindows =
                            listOf(
                                PromotionWeekdayWindow(
                                    weekday = MONDAY,
                                    startsMinute = 14 * 60,
                                    endsMinute = 18 * 60,
                                ),
                            ),
                    ),
                )
            },
        )
    }

    private suspend fun pausePromotion(
        dataSource: DataSource,
        fixture: PromotionFixture,
    ): VenuePromotion? {
        val promotionRepository = VenuePromotionRepository(dataSource)
        val ruleRepository = VenuePromotionRuleRepository(dataSource)
        return promotionRepository.setPromotionStatus(
            venueId = fixture.venueId,
            promotionId = fixture.promotionId,
            status = VenuePromotionStatus.PAUSED,
            afterUpdate = { connection, _ ->
                ruleRepository.synchronizeHappyHoursPromotionStatus(
                    connection = connection,
                    venueId = fixture.venueId,
                    promotionId = fixture.promotionId,
                    status = VenuePromotionStatus.PAUSED,
                )
            },
        )
    }

    private fun ordersRepository(
        dataSource: DataSource,
        now: Instant,
        giftDecisionScopeTokenService: GiftDecisionScopeTokenService? = null,
    ): OrdersRepository =
        OrdersRepository(
            dataSource = dataSource,
            analyticsEventRepository = AnalyticsEventRepository(dataSource),
            promotionApplicationRepository = PromotionApplicationRepository(dataSource),
            venuePromotionRuleRepository = VenuePromotionRuleRepository(dataSource),
            clock = Clock.fixed(now, ZoneOffset.UTC),
            giftDecisionScopeTokenService = giftDecisionScopeTokenService,
        )

    private fun assertSubmittedOldPricing(
        submitted: CreatedOrderBatch,
        fixture: PromotionFixture,
    ) {
        val pricing = assertNotNull(submitted.pricing)
        assertEquals(30_000L, pricing.grossTotalMinor)
        assertEquals(2_000L, pricing.promoDiscountTotalMinor)
        assertEquals(28_000L, pricing.finalPayableTotalMinor)
        val itemA = pricing.items.single { it.itemId == fixture.itemAId }
        assertEquals(2_000L, itemA.discountMinor)
        assertEquals(1, itemA.promotionAdjustment?.ruleVersion)
        assertNull(pricing.items.single { it.itemId == fixture.itemBId }.promotionAdjustment)
    }

    private suspend fun assertLiveVersionTwo(
        dataSource: DataSource,
        fixture: PromotionFixture,
    ) {
        val promotion =
            assertNotNull(
                VenuePromotionRepository(dataSource)
                    .getPromotionForManagement(fixture.venueId, fixture.promotionId),
            )
        val rule =
            assertNotNull(
                VenuePromotionRuleRepository(dataSource)
                    .getRuleForManagement(fixture.venueId, fixture.ruleId),
            )
        assertEquals(NEW_TITLE, promotion.title)
        assertEquals(2, rule.version)
        assertEquals(50, rule.discountPercent)
        assertEquals(PromotionRuleTargetType.MENU_ITEM, rule.executableTargetType)
        assertEquals(fixture.itemBId, rule.targets.single().menuItemId)
        assertEquals(
            listOf(
                PromotionWeekdayWindow(
                    weekday = MONDAY,
                    startsMinute = 14 * 60,
                    endsMinute = 18 * 60,
                ),
            ),
            rule.weekdayWindows,
        )
    }

    private fun assertLedgerSnapshot(
        snapshot: LedgerSnapshot,
        expectedTitle: String,
        expectedVersion: Int,
        expectedPercent: Int,
        expectedItemId: Long,
        expectedStartsMinute: Int,
        expectedEndsMinute: Int,
        expectedOriginalMinor: Long,
        expectedDiscountMinor: Long,
        expectedFinalMinor: Long,
    ) {
        assertEquals(expectedTitle, snapshot.title)
        assertEquals(expectedVersion, snapshot.ruleVersion)
        assertEquals(expectedPercent, snapshot.applicationDiscountPercent)
        assertEquals(expectedPercent, snapshot.adjustmentDiscountPercent)
        assertEquals(expectedItemId, snapshot.adjustedMenuItemId)
        assertEquals(expectedOriginalMinor, snapshot.applicationOriginalMinor)
        assertEquals(expectedDiscountMinor, snapshot.applicationDiscountMinor)
        assertEquals(expectedFinalMinor, snapshot.applicationFinalMinor)
        assertEquals(expectedOriginalMinor, snapshot.adjustmentOriginalMinor)
        assertEquals(expectedDiscountMinor, snapshot.adjustmentDiscountMinor)
        assertEquals(expectedFinalMinor, snapshot.adjustmentFinalMinor)

        val schedule = Json.parseToJsonElement(snapshot.scheduleSnapshotJson).jsonObject
        assertEquals(expectedVersion, schedule.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(PROMOTION_START.toString(), schedule.getValue("promotionStartsAt").jsonPrimitive.content)
        assertEquals(PROMOTION_END.toString(), schedule.getValue("promotionEndsAt").jsonPrimitive.content)
        val windows = schedule.getValue("windows").jsonArray
        assertEquals(1, windows.size)
        val window = windows.single().jsonObject
        assertEquals(MONDAY, window.getValue("weekday").jsonPrimitive.content.toInt())
        assertEquals(expectedStartsMinute, window.getValue("startsMinute").jsonPrimitive.content.toInt())
        assertEquals(expectedEndsMinute, window.getValue("endsMinute").jsonPrimitive.content.toInt())

        val target = Json.parseToJsonElement(snapshot.targetSnapshotJson).jsonObject
        val targets = target.getValue("targets").jsonArray
        assertEquals(1, targets.size)
        val targetItem = targets.single().jsonObject
        assertEquals(PromotionRuleTargetType.MENU_ITEM.dbValue, targetItem.getValue("type").jsonPrimitive.content)
        assertEquals(expectedItemId, targetItem.getValue("menuItemId").jsonPrimitive.content.toLong())
    }

    private fun readLedgerSnapshot(
        dataSource: DataSource,
        batchId: Long,
    ): LedgerSnapshot =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    opa.title_snapshot,
                    opa.rule_version,
                    opa.discount_percent AS application_discount_percent,
                    opa.discount_total_minor AS application_discount_minor,
                    opa.schedule_snapshot_json,
                    opa.target_snapshot_json,
                    opa.original_total_minor AS application_original_minor,
                    opa.final_total_minor AS application_final_minor,
                    adjustment.menu_item_id,
                    adjustment.discount_percent AS adjustment_discount_percent,
                    adjustment.original_amount_minor AS adjustment_original_minor,
                    adjustment.discount_minor AS adjustment_discount_minor,
                    adjustment.final_amount_minor AS adjustment_final_minor
                FROM order_promotion_applications opa
                JOIN order_batch_item_promotion_adjustments adjustment
                  ON adjustment.application_id = opa.id
                WHERE opa.batch_id = ?
                ORDER BY adjustment.id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, batchId)
                statement.executeQuery().use { rs ->
                    assertTrue(rs.next(), "Expected one promotion ledger row for batch $batchId")
                    val snapshot =
                        LedgerSnapshot(
                            title = rs.getString("title_snapshot"),
                            ruleVersion = rs.getInt("rule_version"),
                            applicationDiscountPercent = rs.getInt("application_discount_percent"),
                            adjustmentDiscountPercent = rs.getInt("adjustment_discount_percent"),
                            adjustedMenuItemId = rs.getLong("menu_item_id"),
                            applicationOriginalMinor = rs.getLong("application_original_minor"),
                            applicationDiscountMinor = rs.getLong("application_discount_minor"),
                            applicationFinalMinor = rs.getLong("application_final_minor"),
                            adjustmentOriginalMinor = rs.getLong("adjustment_original_minor"),
                            adjustmentDiscountMinor = rs.getLong("adjustment_discount_minor"),
                            adjustmentFinalMinor = rs.getLong("adjustment_final_minor"),
                            scheduleSnapshotJson = rs.getString("schedule_snapshot_json"),
                            targetSnapshotJson = rs.getString("target_snapshot_json"),
                        )
                    assertFalse(rs.next(), "Expected exactly one promotion ledger row for batch $batchId")
                    snapshot
                }
            }
        }

    private fun readFinancialState(dataSource: DataSource): FinancialState =
        dataSource.connection.use(::readFinancialState)

    private fun readFinancialState(connection: Connection): FinancialState =
        FinancialState(
            orders = countRows(connection, "orders"),
            batches = countRows(connection, "order_batches"),
            batchItems = countRows(connection, "order_batch_items"),
            applications = countRows(connection, "order_promotion_applications"),
            adjustments = countRows(connection, "order_batch_item_promotion_adjustments"),
            rewardItems = countRows(connection, "order_promotion_reward_items"),
            idempotencyRows = countRows(connection, "guest_batch_idempotency"),
            grossMinor =
                selectLong(
                    connection,
                    """
                    SELECT COALESCE(SUM(COALESCE(base_unit_price_minor_snapshot, 0) * qty), 0)
                    FROM order_batch_items
                    """.trimIndent(),
                ),
            discountMinor =
                selectLong(
                    connection,
                    """
                    SELECT COALESCE(SUM(discount_minor), 0)
                    FROM order_batch_item_promotion_adjustments
                    """.trimIndent(),
                ),
        )

    private fun countRows(
        dataSource: DataSource,
        table: String,
    ): Int = dataSource.connection.use { connection -> countRows(connection, table) }

    private fun countWhere(
        dataSource: DataSource,
        table: String,
        predicate: String,
    ): Int {
        require(table in FINANCIAL_TABLES)
        require(
            predicate ==
                "id IN (SELECT reward_order_batch_item_id FROM order_promotion_reward_items)",
        )
        return dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE $predicate").use { statement ->
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
    }

    private fun countRows(
        connection: Connection,
        table: String,
    ): Int {
        require(table in FINANCIAL_TABLES)
        return connection.prepareStatement("SELECT COUNT(*) FROM $table").use { statement ->
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun selectLong(
        connection: Connection,
        sql: String,
    ): Long =
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun insertMenuItem(
        connection: Connection,
        venueId: Long,
        categoryId: Long,
        name: String,
        priceMinor: Long,
    ): Long =
        insertReturningId(
            connection,
            """
            INSERT INTO menu_items (
                venue_id,
                category_id,
                name,
                price_minor,
                currency,
                is_available,
                item_type
            )
            VALUES (?, ?, ?, ?, 'RUB', TRUE, 'HOOKAH')
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, categoryId)
            statement.setString(3, name)
            statement.setLong(4, priceMinor)
        }

    private fun insertReturningId(
        connection: Connection,
        sql: String,
        bind: (PreparedStatement) -> Unit,
    ): Long =
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            bind(statement)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Expected generated key" }
                keys.getLong(1)
            }
        }

    private fun <T, U> runWithBlockedMutation(
        observerDataSource: DataSource,
        snapshotDataSource: ChildReadBarrierDataSource,
        mutationDataSource: TrackedPromotionMutationDataSource,
        snapshotAction: () -> T,
        mutationAction: () -> U,
    ): Pair<T, U> {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val snapshotFuture = executor.submit(Callable<T> { snapshotAction() })
            assertTrue(
                snapshotDataSource.childReadReached.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Snapshot transaction did not reach the first child configuration query",
            )

            val mutationFuture = executor.submit(Callable<U> { mutationAction() })
            assertTrue(
                mutationDataSource.mutationAttempted.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Promotion mutation did not attempt its parent update",
            )
            val snapshotPid = snapshotDataSource.backendPid.get()
            val mutationPid = mutationDataSource.backendPid.get()
            assertTrue(snapshotPid > 0, "Missing PostgreSQL PID for snapshot transaction")
            assertTrue(mutationPid > 0, "Missing PostgreSQL PID for mutation transaction")

            val blockObservation =
                observerDataSource.connection.use { observer ->
                    val probe =
                        awaitPostgresBlock(
                            observer = observer,
                            blockedPid = mutationPid,
                            blockerPid = snapshotPid,
                            mutationFuture = mutationFuture,
                        )
                    PostgresBlockObservation(
                        blocked = probe.blocked,
                        diagnostic =
                            "${probe.diagnostic}. " +
                                describePostgresActivity(
                                    observer = observer,
                                    snapshotPid = snapshotPid,
                                    mutationPid = mutationPid,
                                ),
                    )
                }
            assertTrue(
                blockObservation.blocked,
                "PostgreSQL did not report the promotion mutation blocked by the snapshot transaction. " +
                    blockObservation.diagnostic,
            )

            snapshotDataSource.allowChildRead.countDown()
            val snapshotResult = snapshotFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val mutationResult = mutationFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return snapshotResult to mutationResult
        } finally {
            snapshotDataSource.allowChildRead.countDown()
            executor.shutdownNow()
            executor.awaitTermination(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun awaitPostgresBlock(
        observer: Connection,
        blockedPid: Int,
        blockerPid: Int,
        mutationFuture: Future<*>,
    ): PostgresBlockProbe {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS)
        var lastDiagnostic = "No matching PostgreSQL lock edge for mutation pid=$blockedPid"
        return observer.prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1
                FROM pg_locks blocked
                JOIN pg_locks blocking
                  ON blocking.locktype = blocked.locktype
                 AND blocking.database IS NOT DISTINCT FROM blocked.database
                 AND blocking.relation IS NOT DISTINCT FROM blocked.relation
                 AND blocking.page IS NOT DISTINCT FROM blocked.page
                 AND blocking.tuple IS NOT DISTINCT FROM blocked.tuple
                 AND blocking.virtualxid IS NOT DISTINCT FROM blocked.virtualxid
                 AND blocking.transactionid IS NOT DISTINCT FROM blocked.transactionid
                 AND blocking.classid IS NOT DISTINCT FROM blocked.classid
                 AND blocking.objid IS NOT DISTINCT FROM blocked.objid
                 AND blocking.objsubid IS NOT DISTINCT FROM blocked.objsubid
                WHERE blocked.pid = ?
                  AND NOT blocked.granted
                  AND blocking.pid = ?
                  AND blocking.granted
            )
            """.trimIndent(),
        ).use statementUse@{ statement ->
            statement.setInt(1, blockedPid)
            statement.setInt(2, blockerPid)
            while (System.nanoTime() < deadline) {
                statement.executeQuery().use { rs ->
                    if (rs.next() && rs.getBoolean(1)) {
                        lastDiagnostic =
                            "PostgreSQL pg_locks reports mutation pid=$blockedPid blocked by snapshot pid=$blockerPid"
                        return@statementUse PostgresBlockProbe(blocked = true, diagnostic = lastDiagnostic)
                    }
                }
                if (mutationFuture.isDone) {
                    return@statementUse PostgresBlockProbe(blocked = false, diagnostic = lastDiagnostic)
                }
                Thread.yield()
            }
            PostgresBlockProbe(blocked = false, diagnostic = lastDiagnostic)
        }
    }

    private fun backendPid(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { rs ->
                check(rs.next())
                rs.getInt(1)
            }
        }

    private fun describePostgresActivity(
        observer: Connection,
        snapshotPid: Int,
        mutationPid: Int,
    ): String =
        observer.prepareStatement(
            """
            SELECT pid, state, wait_event_type, wait_event, pg_blocking_pids(pid), query
            FROM pg_stat_activity
            WHERE pid IN (?, ?)
            ORDER BY pid
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, snapshotPid)
            statement.setInt(2, mutationPid)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            "pid=${rs.getInt("pid")}, state=${rs.getString("state")}, " +
                                "wait=${rs.getString("wait_event_type")}/${rs.getString("wait_event")}, " +
                                "blockers=${rs.getString("pg_blocking_pids")}, " +
                                "query=${rs.getString("query").normalizedSql()}",
                        )
                    }
                }.joinToString(" | ")
            }
        }

    private fun String.normalizedSql(): String =
        trim()
            .replace(WHITESPACE, " ")
            .lowercase()

    private inner class ChildReadBarrierDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        val childReadReached = CountDownLatch(1)
        val allowChildRead = CountDownLatch(1)
        val backendPid = AtomicInteger()
        private val blocked = AtomicBoolean()

        override fun getConnection(): Connection = wrap(delegate.connection)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = wrap(delegate.getConnection(username, password))

        private fun wrap(connection: Connection): Connection {
            backendPid.set(this@PromotionConfigurationConcurrencyPostgresTest.backendPid(connection))
            return object : Connection by connection {
                override fun prepareStatement(sql: String): PreparedStatement {
                    val normalized = sql.normalizedSql()
                    if (
                        normalized.contains("from promotion_rule_targets prt") &&
                        blocked.compareAndSet(false, true)
                    ) {
                        check(!connection.autoCommit) {
                            "Promotion configuration children must be read in the caller transaction"
                        }
                        childReadReached.countDown()
                        if (!allowChildRead.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            throw SQLException("Timed out waiting to read promotion configuration children")
                        }
                    }
                    return connection.prepareStatement(sql)
                }
            }
        }
    }

    private inner class TrackedPromotionMutationDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        val mutationAttempted = CountDownLatch(1)
        val backendPid = AtomicInteger()
        private val signalled = AtomicBoolean()

        override fun getConnection(): Connection = wrap(delegate.connection)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = wrap(delegate.getConnection(username, password))

        private fun wrap(connection: Connection): Connection {
            backendPid.set(this@PromotionConfigurationConcurrencyPostgresTest.backendPid(connection))
            return object : Connection by connection {
                override fun prepareStatement(sql: String): PreparedStatement {
                    val prepared = connection.prepareStatement(sql)
                    if (!sql.normalizedSql().startsWith("update venue_promotions")) {
                        return prepared
                    }
                    return object : PreparedStatement by prepared {
                        override fun executeUpdate(): Int {
                            if (signalled.compareAndSet(false, true)) {
                                mutationAttempted.countDown()
                            }
                            return prepared.executeUpdate()
                        }
                    }
                }
            }
        }
    }

    private inner class FailBeforeIdempotencyDataSource(
        private val delegate: DataSource,
        private val beforeFailure: (Connection) -> Unit,
    ) : DataSource by delegate {
        private val failed = AtomicBoolean()

        override fun getConnection(): Connection = wrap(delegate.connection)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = wrap(delegate.getConnection(username, password))

        private fun wrap(connection: Connection): Connection =
            object : Connection by connection {
                override fun prepareStatement(sql: String): PreparedStatement {
                    if (
                        sql.normalizedSql().startsWith("insert into guest_batch_idempotency") &&
                        failed.compareAndSet(false, true)
                    ) {
                        beforeFailure(connection)
                        throw SQLException("Synthetic failure before batch idempotency", "XX999")
                    }
                    return connection.prepareStatement(sql)
                }
            }
    }

    private inner class SubmitTransactionBarrierDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        private val barrier = CyclicBarrier(2)
        val backendPids = ConcurrentHashMap.newKeySet<Int>()

        override fun getConnection(): Connection = wrap(delegate.connection)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = wrap(delegate.getConnection(username, password))

        private fun wrap(connection: Connection): Connection {
            var joinedBarrier = false
            return object : Connection by connection {
                override fun setAutoCommit(autoCommit: Boolean) {
                    connection.autoCommit = autoCommit
                    if (!autoCommit && !joinedBarrier) {
                        joinedBarrier = true
                        backendPids.add(
                            this@PromotionConfigurationConcurrencyPostgresTest.backendPid(connection),
                        )
                        try {
                            barrier.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            throw SQLException("Timed out waiting for both submit transactions", e)
                        }
                    }
                }
            }
        }
    }

    private data class BaseFixture(
        val venueId: Long,
        val tableId: Long,
        val tableSessionId: Long,
        val tabId: Long,
        val itemAId: Long,
        val itemBId: Long,
    )

    private data class PromotionFixture(
        val venueId: Long,
        val tableId: Long,
        val tableSessionId: Long,
        val tabId: Long,
        val itemAId: Long,
        val itemBId: Long,
        val promotionId: Long,
        val ruleId: Long,
    ) {
        val cartItems: List<OrderBatchItemInput>
            get() =
                listOf(
                    OrderBatchItemInput(itemId = itemAId, qty = 1),
                    OrderBatchItemInput(itemId = itemBId, qty = 1),
                )
    }

    private data class GiftConcurrencyFixture(
        val venueId: Long,
        val tableId: Long,
        val tableSessionId: Long,
        val tabId: Long,
        val triggerItemId: Long,
        val rewardItemId: Long,
    ) {
        val cartItems: List<OrderBatchItemInput>
            get() = listOf(OrderBatchItemInput(itemId = triggerItemId, qty = 1))
    }

    private data class LedgerSnapshot(
        val title: String,
        val ruleVersion: Int,
        val applicationDiscountPercent: Int,
        val adjustmentDiscountPercent: Int,
        val adjustedMenuItemId: Long,
        val applicationOriginalMinor: Long,
        val applicationDiscountMinor: Long,
        val applicationFinalMinor: Long,
        val adjustmentOriginalMinor: Long,
        val adjustmentDiscountMinor: Long,
        val adjustmentFinalMinor: Long,
        val scheduleSnapshotJson: String,
        val targetSnapshotJson: String,
    )

    private data class FinancialState(
        val orders: Int,
        val batches: Int,
        val batchItems: Int,
        val applications: Int,
        val adjustments: Int,
        val rewardItems: Int,
        val idempotencyRows: Int,
        val grossMinor: Long,
        val discountMinor: Long,
    )

    private data class PostgresBlockObservation(
        val blocked: Boolean,
        val diagnostic: String,
    )

    private data class PostgresBlockProbe(
        val blocked: Boolean,
        val diagnostic: String,
    )

    private companion object {
        const val USER_ID = 900_001L
        const val MONDAY = 1
        const val WAIT_TIMEOUT_SECONDS = 30L
        const val OLD_TITLE = "Promotion V1"
        const val NEW_TITLE = "Promotion V2"
        const val GIFT_SCOPE_SECRET = "promotion-concurrency-gift-scope-secret"

        val UTC: ZoneId = ZoneId.of("UTC")
        val PROMOTION_START: Instant = Instant.parse("2024-01-01T00:00:00Z")
        val PROMOTION_END: Instant = Instant.parse("2024-01-31T23:59:59Z")
        val OLD_ACTIVE_INSTANT: Instant = Instant.parse("2024-01-01T11:00:00Z")
        val NEW_ACTIVE_INSTANT: Instant = Instant.parse("2024-01-01T15:00:00Z")
        val WHITESPACE = Regex("\\s+")
        val FINANCIAL_TABLES =
            setOf(
                "orders",
                "order_batches",
                "order_batch_items",
                "order_promotion_applications",
                "order_batch_item_promotion_adjustments",
                "order_promotion_reward_items",
                "guest_batch_idempotency",
            )
    }
}
