package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.miniapp.venue.menu.MENU_CATEGORY_DELETED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_ITEM_DELETED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MenuCategoryDeleteSource
import com.hookah.platform.backend.miniapp.venue.menu.MenuItemDeleteSource
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuRepository
import com.hookah.platform.backend.promotions.GiftDecisionCommand
import com.hookah.platform.backend.promotions.GiftDecisionScopeTokenService
import com.hookah.platform.backend.promotions.PromotionGiftDecisionAction
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

    @Test
    fun `promotion creation commits parent initial rule and one exact audit on PostgreSQL`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val ruleRepository = VenuePromotionRuleRepository(dataSource)
                var createdRule: VenuePromotionRule? = null
                val promotion =
                    VenuePromotionRepository(dataSource, ruleRepository).createPromotion(
                        venueId = fixture.venueId,
                        title = "PostgreSQL creation audit",
                        description = "Private configuration",
                        terms = "Private terms",
                        templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                        createdByUserId = USER_ID,
                        source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                        afterInsert = { connection, promotionId ->
                            createdRule =
                                ruleRepository.createHappyHoursDraftRule(
                                    connection = connection,
                                    venueId = fixture.venueId,
                                    promotionId = promotionId,
                                    target =
                                        HappyHoursRuleTargetInput(
                                            targetType = PromotionRuleTargetType.MENU_ITEM,
                                            menuItemId = fixture.itemAId,
                                        ),
                                    discountPercent = 25,
                                    weekdayWindows =
                                        listOf(
                                            PromotionWeekdayWindow(
                                                weekday = MONDAY,
                                                startsMinute = 12 * 60,
                                                endsMinute = 14 * 60,
                                            ),
                                        ),
                                    createdByUserId = USER_ID,
                                )
                        },
                    )

                val rule = assertNotNull(createdRule)
                assertNotNull(
                    VenuePromotionRepository(dataSource)
                        .getPromotionForManagement(fixture.venueId, promotion.id),
                )
                assertEquals(
                    rule,
                    VenuePromotionRuleRepository(dataSource)
                        .getRuleForManagement(fixture.venueId, rule.id),
                )
                val audit = readPromotionCreationAudits(dataSource, promotion.id).single()
                assertEquals(USER_ID, audit.actorUserId)
                assertEquals(VENUE_PROMOTION_CREATED_ACTION, audit.action)
                assertEquals(
                    setOf("venueId", "promotionId", "templateType", "status", "source", "rules"),
                    audit.payload.keys,
                )
                assertEquals(fixture.venueId, audit.payload.getValue("venueId").jsonPrimitive.content.toLong())
                assertEquals(promotion.id, audit.payload.getValue("promotionId").jsonPrimitive.content.toLong())
                assertEquals(
                    VenuePromotionTemplateType.HAPPY_HOURS_PERCENT.dbValue,
                    audit.payload.getValue("templateType").jsonPrimitive.content,
                )
                assertEquals(VenuePromotionStatus.DRAFT.dbValue, audit.payload.getValue("status").jsonPrimitive.content)
                assertEquals(
                    VenuePromotionLifecycleSource.VENUE_MINI_APP.name,
                    audit.payload.getValue("source").jsonPrimitive.content,
                )
                val auditedRule = audit.payload.getValue("rules").jsonArray.single().jsonObject
                assertEquals(setOf("ruleId", "version", "status"), auditedRule.keys)
                assertEquals(rule.id, auditedRule.getValue("ruleId").jsonPrimitive.content.toLong())
                assertEquals(rule.version, auditedRule.getValue("version").jsonPrimitive.content.toInt())
                assertEquals(rule.status.dbValue, auditedRule.getValue("status").jsonPrimitive.content)
            }
        }

    @Test
    fun `promotion creation audit failure rolls back parent and initial rule on PostgreSQL`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val parentCountBefore = countRows(dataSource, "venue_promotions")
                val ruleCountBefore = countRows(dataSource, "promotion_rules")
                val auditCountBefore = readPromotionCreationAudits(dataSource).size
                val realAuditWriter = AuditLogRepository(dataSource)
                val failingAuditWriter =
                    TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                        realAuditWriter.appendJson(
                            connection = connection,
                            actorUserId = actorUserId,
                            action = action,
                            entityType = entityType,
                            entityId = entityId,
                            payload = payload,
                        )
                        throw SQLException("Synthetic promotion creation audit failure", "XX999")
                    }
                val ruleRepository = VenuePromotionRuleRepository(dataSource)
                val failingRepository =
                    VenuePromotionRepository(
                        dataSource = dataSource,
                        ruleRepository = ruleRepository,
                        auditLogWriter = failingAuditWriter,
                    )

                assertFailsWith<DatabaseUnavailableException> {
                    failingRepository.createPromotion(
                        venueId = fixture.venueId,
                        title = "Must rollback",
                        description = "Private configuration",
                        terms = null,
                        templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                        createdByUserId = USER_ID,
                        source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                        afterInsert = { connection, promotionId ->
                            ruleRepository.createHappyHoursDraftRule(
                                connection = connection,
                                venueId = fixture.venueId,
                                promotionId = promotionId,
                                target =
                                    HappyHoursRuleTargetInput(
                                        targetType = PromotionRuleTargetType.MENU_ITEM,
                                        menuItemId = fixture.itemAId,
                                    ),
                                discountPercent = 25,
                                weekdayWindows =
                                    listOf(
                                        PromotionWeekdayWindow(
                                            weekday = MONDAY,
                                            startsMinute = 12 * 60,
                                            endsMinute = 14 * 60,
                                        ),
                                    ),
                                createdByUserId = USER_ID,
                            )
                        },
                    )
                }

                assertEquals(parentCountBefore, countRows(dataSource, "venue_promotions"))
                assertEquals(ruleCountBefore, countRows(dataSource, "promotion_rules"))
                assertEquals(auditCountBefore, readPromotionCreationAudits(dataSource).size)
            }
        }

    @Test
    fun `promotion lifecycle audit failure rolls back status archive rules and timestamps`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val baselinePromotion =
                    assertNotNull(
                        VenuePromotionRepository(dataSource)
                            .getPromotionForManagement(fixture.venueId, fixture.promotionId),
                    )
                val baselineRule =
                    assertNotNull(
                        VenuePromotionRuleRepository(dataSource)
                            .getRuleForManagement(fixture.venueId, fixture.ruleId),
                    )
                val baselineAuditCount = readPromotionLifecycleAudits(dataSource, fixture.promotionId).size
                val realAuditWriter = AuditLogRepository(dataSource)
                val failingAuditWriter =
                    TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                        realAuditWriter.appendJson(
                            connection = connection,
                            actorUserId = actorUserId,
                            action = action,
                            entityType = entityType,
                            entityId = entityId,
                            payload = payload,
                        )
                        throw SQLException("Synthetic promotion lifecycle audit failure", "XX999")
                    }
                val failingRepository =
                    VenuePromotionRepository(
                        dataSource = dataSource,
                        ruleRepository = VenuePromotionRuleRepository(dataSource),
                        auditLogWriter = failingAuditWriter,
                    )

                listOf(VenuePromotionStatus.PAUSED, VenuePromotionStatus.ARCHIVED).forEach { target ->
                    assertFailsWith<DatabaseUnavailableException> {
                        failingRepository.mutatePromotionLifecycle(
                            venueId = fixture.venueId,
                            promotionId = fixture.promotionId,
                            expectedStatus = VenuePromotionStatus.ACTIVE,
                            targetStatus = target,
                            actorUserId = USER_ID,
                            source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                        )
                    }

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
                    assertEquals(baselinePromotion, promotion)
                    assertEquals(baselineRule, rule)
                    assertEquals(
                        baselineAuditCount,
                        readPromotionLifecycleAudits(dataSource, fixture.promotionId).size,
                    )
                    assertNotNull(
                        VenuePromotionRepository(dataSource)
                            .getPromotionForGuest(fixture.promotionId, OLD_ACTIVE_INSTANT),
                    )
                }
            }
        }

    @Test
    fun `concurrent lifecycle statuses commit one winner and one matching audit`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource, activate = false)
                val calls =
                    listOf(
                        LifecycleRaceCall(
                            targetStatus = VenuePromotionStatus.ACTIVE,
                            source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                        ),
                        LifecycleRaceCall(
                            targetStatus = VenuePromotionStatus.PAUSED,
                            source = VenuePromotionLifecycleSource.TELEGRAM_BOT,
                        ),
                    )

                assertLifecycleRace(dataSource, fixture, calls)
            }
        }

    @Test
    fun `concurrent lifecycle status and archive commit one consistent audited snapshot`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource, activate = false)
                val calls =
                    listOf(
                        LifecycleRaceCall(
                            targetStatus = VenuePromotionStatus.ACTIVE,
                            source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                        ),
                        LifecycleRaceCall(
                            targetStatus = VenuePromotionStatus.ARCHIVED,
                            source = VenuePromotionLifecycleSource.TELEGRAM_BOT,
                        ),
                    )

                assertLifecycleRace(dataSource, fixture, calls)
            }
        }

    @Test
    fun `concurrent lifecycle and configuration update preserve audited rule snapshot`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val lifecycleDataSource = HeldPromotionParentLockDataSource(dataSource)
                val configurationDataSource = TrackedPromotionMutationDataSource(dataSource)
                val (paused, edited) =
                    runWithHeldPromotionParentLock(
                        observerDataSource = dataSource,
                        lockHolderDataSource = lifecycleDataSource,
                        blockedDataSource = configurationDataSource,
                        lockHolderAction = {
                            runBlocking { pausePromotion(lifecycleDataSource, fixture) }
                        },
                        blockedAction = {
                            runBlocking { editToVersionTwo(configurationDataSource, fixture) }
                        },
                    )
                assertNotNull(paused)
                assertNotNull(edited)

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
                assertEquals(VenuePromotionStatus.PAUSED, promotion.status)
                assertEquals(2, rule.version)
                assertEquals(VenuePromotionStatus.PAUSED, rule.status)

                val audits = readPromotionLifecycleAudits(dataSource, fixture.promotionId)
                assertEquals(2, audits.size)
                val pauseAudit = audits.last()
                assertEquals(VENUE_PROMOTION_STATUS_CHANGED_ACTION, pauseAudit.action)
                assertEquals(
                    VenuePromotionStatus.ACTIVE.dbValue,
                    pauseAudit.payload.getValue("oldStatus").jsonPrimitive.content,
                )
                assertEquals(
                    VenuePromotionStatus.PAUSED.dbValue,
                    pauseAudit.payload.getValue("newStatus").jsonPrimitive.content,
                )
                val auditedRule = pauseAudit.payload.getValue("rules").jsonArray.single().jsonObject
                assertEquals(1, auditedRule.getValue("version").jsonPrimitive.content.toInt())
                assertEquals(
                    VenuePromotionStatus.ACTIVE.dbValue,
                    auditedRule.getValue("oldStatus").jsonPrimitive.content,
                )
                assertEquals(
                    VenuePromotionStatus.PAUSED.dbValue,
                    auditedRule.getValue("newStatus").jsonPrimitive.content,
                )
            }
        }

    @Test
    fun `menu item delete and promotion configuration race has atomic audited winners`() =
        runBlocking {
            val deleteWinsDatabase = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(deleteWinsDatabase).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val deleteDataSource = HeldMenuDeletePromotionLockDataSource(dataSource)
                val configurationDataSource = TrackedPromotionMutationDataSource(dataSource)

                val (deleted, edited) =
                    runWithHeldMenuDeletePromotionLock(
                        observerDataSource = dataSource,
                        deleteDataSource = deleteDataSource,
                        blockedDataSource = configurationDataSource,
                        deleteAction = {
                            runBlocking {
                                VenueMenuRepository(deleteDataSource).deleteItem(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemAId,
                                    actorUserId = USER_ID,
                                    source = MenuItemDeleteSource.VENUE_MINI_APP,
                                )
                            }
                        },
                        blockedAction = {
                            runBlocking { editToVersionTwo(configurationDataSource, fixture) }
                        },
                    )

                assertTrue(deleted)
                assertNotNull(edited)
                assertFalse(menuItemExists(dataSource, fixture.itemAId))
                val finalRule =
                    assertNotNull(
                        VenuePromotionRuleRepository(dataSource)
                            .getRuleForManagement(fixture.venueId, fixture.ruleId),
                    )
                assertEquals(3, finalRule.version)
                assertEquals(listOf(fixture.itemBId), finalRule.targets.map { it.menuItemId })
                val audit = readMenuItemDeleteAudits(dataSource, fixture.itemAId).single()
                assertEquals(USER_ID, audit.actorUserId)
                assertEquals("menu_item", audit.entityType)
                assertEquals(fixture.itemAId, audit.entityId)
                assertEquals("VENUE_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
                val affected = audit.payload.getValue("affectedPromotionRules").jsonObject
                assertEquals(1, affected.getValue("totalCount").jsonPrimitive.content.toInt())
                assertEquals(
                    listOf(fixture.ruleId),
                    affected.getValue("sampleRuleIds").jsonArray.map { it.jsonPrimitive.content.toLong() },
                )
                assertEquals(0, affected.getValue("omittedCount").jsonPrimitive.content.toInt())
            }

            val configurationWinsDatabase = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(configurationWinsDatabase).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val configurationDataSource = HeldPromotionConfigurationLockDataSource(dataSource)
                val deleteDataSource = TrackedMenuDeleteDataSource(dataSource)

                val (edited, deleteFailure) =
                    runWithHeldConfigurationParentLock(
                        configurationDataSource = configurationDataSource,
                        blockedDataSource = deleteDataSource,
                        configurationAction = {
                            runBlocking { editToVersionTwo(configurationDataSource, fixture) }
                        },
                        blockedAction = {
                            runCatching {
                                runBlocking {
                                    VenueMenuRepository(deleteDataSource).deleteItem(
                                        venueId = fixture.venueId,
                                        itemId = fixture.itemBId,
                                        actorUserId = USER_ID,
                                        source = MenuItemDeleteSource.VENUE_MINI_APP,
                                    )
                                }
                            }.exceptionOrNull()
                        },
                    )

                assertNotNull(edited)
                assertTrue(deleteFailure is DatabaseUnavailableException)
                assertTrue(menuItemExists(dataSource, fixture.itemBId))
                val finalRule =
                    assertNotNull(
                        VenuePromotionRuleRepository(dataSource)
                            .getRuleForManagement(fixture.venueId, fixture.ruleId),
                    )
                assertEquals(2, finalRule.version)
                assertEquals(listOf(fixture.itemBId), finalRule.targets.map { it.menuItemId })
                assertTrue(readMenuItemDeleteAudits(dataSource, fixture.itemBId).isEmpty())
            }
        }

    @Test
    fun `menu category delete and promotion configuration race has atomic audited winners`() =
        runBlocking {
            val deleteWinsDatabase = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(deleteWinsDatabase).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val categoryId = insertMenuCategory(dataSource, fixture.venueId, "Delete wins category")
                assertNotNull(editToCategoryTarget(dataSource, fixture, categoryId))
                val deleteDataSource = HeldMenuDeletePromotionLockDataSource(dataSource)
                val configurationDataSource = TrackedPromotionMutationDataSource(dataSource)

                val (deleted, edited) =
                    runWithHeldMenuDeletePromotionLock(
                        observerDataSource = dataSource,
                        deleteDataSource = deleteDataSource,
                        blockedDataSource = configurationDataSource,
                        deleteAction = {
                            runBlocking {
                                VenueMenuRepository(deleteDataSource).deleteCategory(
                                    venueId = fixture.venueId,
                                    categoryId = categoryId,
                                    actorUserId = USER_ID,
                                    source = MenuCategoryDeleteSource.VENUE_MINI_APP,
                                )
                            }
                        },
                        blockedAction = {
                            runBlocking { editToVersionTwo(configurationDataSource, fixture) }
                        },
                    )

                assertTrue(deleted)
                assertNotNull(edited)
                assertFalse(menuCategoryExists(dataSource, categoryId))
                val finalRule =
                    assertNotNull(
                        VenuePromotionRuleRepository(dataSource)
                            .getRuleForManagement(fixture.venueId, fixture.ruleId),
                    )
                assertEquals(4, finalRule.version)
                assertEquals(listOf(fixture.itemBId), finalRule.targets.map { it.menuItemId })
                val audit = readMenuCategoryDeleteAudits(dataSource, categoryId).single()
                assertEquals(USER_ID, audit.actorUserId)
                assertEquals("menu_category", audit.entityType)
                assertEquals(categoryId, audit.entityId)
                assertEquals("VENUE_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
                val affected = audit.payload.getValue("affectedPromotionRules").jsonObject
                assertEquals(1, affected.getValue("totalCount").jsonPrimitive.content.toInt())
                assertEquals(
                    listOf(fixture.ruleId),
                    affected.getValue("sampleRuleIds").jsonArray.map { it.jsonPrimitive.content.toLong() },
                )
                assertEquals(0, affected.getValue("omittedCount").jsonPrimitive.content.toInt())
            }

            val configurationWinsDatabase = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(configurationWinsDatabase).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val categoryId = insertMenuCategory(dataSource, fixture.venueId, "Configuration wins category")
                val configurationDataSource = HeldPromotionConfigurationLockDataSource(dataSource)
                val deleteDataSource = TrackedMenuDeleteDataSource(dataSource)

                val (edited, deleteFailure) =
                    runWithHeldConfigurationParentLock(
                        configurationDataSource = configurationDataSource,
                        blockedDataSource = deleteDataSource,
                        configurationAction = {
                            runBlocking { editToCategoryTarget(configurationDataSource, fixture, categoryId) }
                        },
                        blockedAction = {
                            runCatching {
                                runBlocking {
                                    VenueMenuRepository(deleteDataSource).deleteCategory(
                                        venueId = fixture.venueId,
                                        categoryId = categoryId,
                                        actorUserId = USER_ID,
                                        source = MenuCategoryDeleteSource.VENUE_MINI_APP,
                                    )
                                }
                            }.exceptionOrNull()
                        },
                    )

                assertNotNull(edited)
                assertTrue(deleteFailure is DatabaseUnavailableException)
                assertTrue(menuCategoryExists(dataSource, categoryId))
                val finalRule =
                    assertNotNull(
                        VenuePromotionRuleRepository(dataSource)
                            .getRuleForManagement(fixture.venueId, fixture.ruleId),
                    )
                assertEquals(2, finalRule.version)
                assertEquals(listOf(categoryId), finalRule.targets.map { it.menuCategoryId })
                assertTrue(readMenuCategoryDeleteAudits(dataSource, categoryId).isEmpty())
            }
        }

    private suspend fun seedFixture(
        dataSource: DataSource,
        activate: Boolean = true,
    ): PromotionFixture {
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
                source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
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
        if (activate) {
            assertEquals(
                VenuePromotionLifecycleOutcome.APPLIED,
                assertNotNull(
                    promotionRepository.mutatePromotionLifecycle(
                        venueId = base.venueId,
                        promotionId = promotion.id,
                        expectedStatus = VenuePromotionStatus.DRAFT,
                        targetStatus = VenuePromotionStatus.ACTIVE,
                        actorUserId = USER_ID,
                        source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                    ),
                ).outcome,
            )
        }
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
                source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
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
        assertEquals(
            VenuePromotionLifecycleOutcome.APPLIED,
            assertNotNull(
                promotionRepository.mutatePromotionLifecycle(
                    venueId = base.venueId,
                    promotionId = promotion.id,
                    expectedStatus = VenuePromotionStatus.DRAFT,
                    targetStatus = VenuePromotionStatus.ACTIVE,
                    actorUserId = USER_ID,
                    source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                ),
            ).outcome,
        )
        return base
    }

    private suspend fun editToVersionTwo(
        dataSource: DataSource,
        fixture: PromotionFixture,
    ): VenuePromotion? =
        editPromotionTarget(
            dataSource = dataSource,
            fixture = fixture,
            target =
                HappyHoursRuleTargetInput(
                    targetType = PromotionRuleTargetType.MENU_ITEM,
                    menuItemId = fixture.itemBId,
                ),
        )

    private suspend fun editToCategoryTarget(
        dataSource: DataSource,
        fixture: PromotionFixture,
        categoryId: Long,
    ): VenuePromotion? =
        editPromotionTarget(
            dataSource = dataSource,
            fixture = fixture,
            target =
                HappyHoursRuleTargetInput(
                    targetType = PromotionRuleTargetType.MENU_CATEGORY,
                    menuCategoryId = categoryId,
                ),
        )

    private suspend fun editPromotionTarget(
        dataSource: DataSource,
        fixture: PromotionFixture,
        target: HappyHoursRuleTargetInput,
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
                        target = target,
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
        return promotionRepository.mutatePromotionLifecycle(
            venueId = fixture.venueId,
            promotionId = fixture.promotionId,
            expectedStatus = VenuePromotionStatus.ACTIVE,
            targetStatus = VenuePromotionStatus.PAUSED,
            actorUserId = USER_ID,
            source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
        )?.promotion
    }

    private fun assertLifecycleRace(
        dataSource: DataSource,
        fixture: PromotionFixture,
        calls: List<LifecycleRaceCall>,
    ) {
        require(calls.size == 2)
        val lockHolderDataSource = HeldPromotionParentLockDataSource(dataSource)
        val blockedDataSource = TrackedPromotionMutationDataSource(dataSource)
        val lockHolderRepository = VenuePromotionRepository(lockHolderDataSource)
        val blockedRepository = VenuePromotionRepository(blockedDataSource)
        val (winnerMutation, loserMutation) =
            runWithHeldPromotionParentLock(
                observerDataSource = dataSource,
                lockHolderDataSource = lockHolderDataSource,
                blockedDataSource = blockedDataSource,
                lockHolderAction = {
                    runBlocking {
                        lockHolderRepository.mutatePromotionLifecycle(
                            venueId = fixture.venueId,
                            promotionId = fixture.promotionId,
                            expectedStatus = VenuePromotionStatus.DRAFT,
                            targetStatus = calls[0].targetStatus,
                            actorUserId = USER_ID,
                            source = calls[0].source,
                        )
                    }
                },
                blockedAction = {
                    runBlocking {
                        blockedRepository.mutatePromotionLifecycle(
                            venueId = fixture.venueId,
                            promotionId = fixture.promotionId,
                            expectedStatus = VenuePromotionStatus.DRAFT,
                            targetStatus = calls[1].targetStatus,
                            actorUserId = USER_ID,
                            source = calls[1].source,
                        )
                    }
                },
            )
        val results =
            listOf(
                calls[0] to assertNotNull(winnerMutation),
                calls[1] to assertNotNull(loserMutation),
            )
        assertEquals(1, results.count { (_, mutation) -> mutation.outcome == VenuePromotionLifecycleOutcome.APPLIED })
        assertEquals(1, results.count { (_, mutation) -> mutation.outcome == VenuePromotionLifecycleOutcome.STALE })
        val winner = results.single { (_, mutation) -> mutation.outcome == VenuePromotionLifecycleOutcome.APPLIED }
        val finalPromotion =
            assertNotNull(
                runBlocking {
                    VenuePromotionRepository(dataSource)
                        .getPromotionForManagement(fixture.venueId, fixture.promotionId)
                },
            )
        val finalRule =
            assertNotNull(
                runBlocking {
                    VenuePromotionRuleRepository(dataSource)
                        .getRuleForManagement(fixture.venueId, fixture.ruleId)
                },
            )
        assertEquals(winner.first.targetStatus, finalPromotion.status)
        assertEquals(finalPromotion.status, finalRule.status)

        val audit = readPromotionLifecycleAudits(dataSource, fixture.promotionId).single()
        assertEquals(USER_ID, audit.actorUserId)
        assertEquals(
            if (finalPromotion.status == VenuePromotionStatus.ARCHIVED) {
                VENUE_PROMOTION_ARCHIVED_ACTION
            } else {
                VENUE_PROMOTION_STATUS_CHANGED_ACTION
            },
            audit.action,
        )
        assertEquals(
            setOf(
                "venueId",
                "promotionId",
                "templateType",
                "oldStatus",
                "newStatus",
                "source",
                "rules",
            ),
            audit.payload.keys,
        )
        assertEquals(fixture.venueId, audit.payload.getValue("venueId").jsonPrimitive.content.toLong())
        assertEquals(fixture.promotionId, audit.payload.getValue("promotionId").jsonPrimitive.content.toLong())
        assertEquals(
            VenuePromotionTemplateType.HAPPY_HOURS_PERCENT.dbValue,
            audit.payload.getValue("templateType").jsonPrimitive.content,
        )
        assertEquals(VenuePromotionStatus.DRAFT.dbValue, audit.payload.getValue("oldStatus").jsonPrimitive.content)
        assertEquals(finalPromotion.status.dbValue, audit.payload.getValue("newStatus").jsonPrimitive.content)
        assertEquals(winner.first.source.name, audit.payload.getValue("source").jsonPrimitive.content)
        val rulePayload = audit.payload.getValue("rules").jsonArray.single().jsonObject
        assertEquals(setOf("ruleId", "version", "oldStatus", "newStatus"), rulePayload.keys)
        assertEquals(fixture.ruleId, rulePayload.getValue("ruleId").jsonPrimitive.content.toLong())
        assertEquals(1, rulePayload.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(VenuePromotionStatus.DRAFT.dbValue, rulePayload.getValue("oldStatus").jsonPrimitive.content)
        assertEquals(finalRule.status.dbValue, rulePayload.getValue("newStatus").jsonPrimitive.content)
    }

    private fun readPromotionLifecycleAudits(
        dataSource: DataSource,
        promotionId: Long,
    ): List<PromotionLifecycleAudit> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT actor_user_id, action, payload_json
                FROM audit_log
                WHERE entity_type = 'venue_promotion'
                  AND entity_id = ?
                  AND action IN (?, ?)
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, promotionId)
                statement.setString(2, VENUE_PROMOTION_STATUS_CHANGED_ACTION)
                statement.setString(3, VENUE_PROMOTION_ARCHIVED_ACTION)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                PromotionLifecycleAudit(
                                    actorUserId = rs.getLong("actor_user_id"),
                                    action = rs.getString("action"),
                                    payload = Json.parseToJsonElement(rs.getString("payload_json")).jsonObject,
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun readPromotionCreationAudits(
        dataSource: DataSource,
        promotionId: Long? = null,
    ): List<PromotionLifecycleAudit> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT actor_user_id, action, payload_json
                FROM audit_log
                WHERE action = ?
                  AND (? IS NULL OR entity_id = ?)
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, VENUE_PROMOTION_CREATED_ACTION)
                if (promotionId == null) {
                    statement.setNull(2, java.sql.Types.BIGINT)
                    statement.setNull(3, java.sql.Types.BIGINT)
                } else {
                    statement.setLong(2, promotionId)
                    statement.setLong(3, promotionId)
                }
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                PromotionLifecycleAudit(
                                    actorUserId = rs.getLong("actor_user_id"),
                                    action = rs.getString("action"),
                                    payload = Json.parseToJsonElement(rs.getString("payload_json")).jsonObject,
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun readMenuItemDeleteAudits(
        dataSource: DataSource,
        itemId: Long,
    ): List<MenuDeleteAudit> = readMenuDeleteAudits(dataSource, MENU_ITEM_DELETED_AUDIT_ACTION, itemId)

    private fun readMenuCategoryDeleteAudits(
        dataSource: DataSource,
        categoryId: Long,
    ): List<MenuDeleteAudit> = readMenuDeleteAudits(dataSource, MENU_CATEGORY_DELETED_AUDIT_ACTION, categoryId)

    private fun readMenuDeleteAudits(
        dataSource: DataSource,
        action: String,
        entityId: Long,
    ): List<MenuDeleteAudit> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT actor_user_id, entity_type, entity_id, payload_json
                FROM audit_log
                WHERE action = ?
                  AND entity_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, action)
                statement.setLong(2, entityId)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                MenuDeleteAudit(
                                    actorUserId = rs.getLong("actor_user_id"),
                                    entityType = rs.getString("entity_type"),
                                    entityId = rs.getLong("entity_id"),
                                    payload = Json.parseToJsonElement(rs.getString("payload_json")).jsonObject,
                                ),
                            )
                        }
                    }
                }
            }
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

    private fun menuItemExists(
        dataSource: DataSource,
        itemId: Long,
    ): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT 1
                FROM menu_items
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, itemId)
                statement.executeQuery().use { rs -> rs.next() }
            }
        }

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
        require(table in FINANCIAL_TABLES + PROMOTION_CREATION_TABLES)
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

    private fun insertMenuCategory(
        dataSource: DataSource,
        venueId: Long,
        name: String,
    ): Long =
        dataSource.connection.use { connection ->
            insertReturningId(
                connection,
                """
                INSERT INTO menu_categories (venue_id, name, category_type)
                VALUES (?, ?, 'HOOKAH')
                """.trimIndent(),
            ) { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, name)
            }
        }

    private fun menuCategoryExists(
        dataSource: DataSource,
        categoryId: Long,
    ): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM menu_categories WHERE id = ?",
            ).use { statement ->
                statement.setLong(1, categoryId)
                statement.executeQuery().use { rs -> rs.next() }
            }
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
                "Promotion mutation did not attempt its parent lock or update",
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

    private fun <T, U> runWithHeldPromotionParentLock(
        observerDataSource: DataSource,
        lockHolderDataSource: HeldPromotionParentLockDataSource,
        blockedDataSource: TrackedPromotionMutationDataSource,
        lockHolderAction: () -> T,
        blockedAction: () -> U,
    ): Pair<T, U> {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val lockHolderFuture = executor.submit(Callable<T> { lockHolderAction() })
            assertTrue(
                lockHolderDataSource.parentLockAcquired.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Lifecycle transaction did not acquire the promotion parent lock",
            )

            val blockedFuture = executor.submit(Callable<U> { blockedAction() })
            assertTrue(
                blockedDataSource.mutationAttempted.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Concurrent promotion mutation did not attempt its parent lock or update",
            )
            val lockHolderPid = lockHolderDataSource.backendPid.get()
            val blockedPid = blockedDataSource.backendPid.get()
            assertTrue(lockHolderPid > 0, "Missing PostgreSQL PID for lifecycle lock holder")
            assertTrue(blockedPid > 0, "Missing PostgreSQL PID for blocked promotion mutation")

            val blockObservation =
                observerDataSource.connection.use { observer ->
                    val probe =
                        awaitPostgresBlock(
                            observer = observer,
                            blockedPid = blockedPid,
                            blockerPid = lockHolderPid,
                            mutationFuture = blockedFuture,
                        )
                    PostgresBlockObservation(
                        blocked = probe.blocked,
                        diagnostic =
                            "${probe.diagnostic}. " +
                                describePostgresActivity(
                                    observer = observer,
                                    snapshotPid = lockHolderPid,
                                    mutationPid = blockedPid,
                                ),
                    )
                }
            assertTrue(
                blockObservation.blocked,
                "PostgreSQL did not report the concurrent promotion mutation blocked by the lifecycle lock. " +
                    blockObservation.diagnostic,
            )

            lockHolderDataSource.allowLifecycleMutation.countDown()
            val lockHolderResult = lockHolderFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val blockedResult = blockedFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return lockHolderResult to blockedResult
        } finally {
            lockHolderDataSource.allowLifecycleMutation.countDown()
            executor.shutdownNow()
            executor.awaitTermination(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun <T, U> runWithHeldMenuDeletePromotionLock(
        observerDataSource: DataSource,
        deleteDataSource: HeldMenuDeletePromotionLockDataSource,
        blockedDataSource: TrackedPromotionMutationDataSource,
        deleteAction: () -> T,
        blockedAction: () -> U,
    ): Pair<T, U> {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val deleteFuture = executor.submit(Callable<T> { deleteAction() })
            assertTrue(
                deleteDataSource.parentLockAcquired.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Menu delete did not acquire the promotion parent lock",
            )

            val blockedFuture = executor.submit(Callable<U> { blockedAction() })
            assertTrue(
                blockedDataSource.mutationAttempted.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Promotion configuration did not attempt its parent lock",
            )
            val deletePid = deleteDataSource.backendPid.get()
            val blockedPid = blockedDataSource.backendPid.get()
            assertTrue(deletePid > 0, "Missing PostgreSQL PID for menu delete")
            assertTrue(blockedPid > 0, "Missing PostgreSQL PID for promotion configuration")

            observerDataSource.connection.use { observer ->
                val probe =
                    awaitPostgresBlock(
                        observer = observer,
                        blockedPid = blockedPid,
                        blockerPid = deletePid,
                        mutationFuture = blockedFuture,
                    )
                assertTrue(
                    probe.blocked,
                    "PostgreSQL did not report configuration blocked by menu delete. " +
                        probe.diagnostic,
                )
            }

            deleteDataSource.allowDelete.countDown()
            return deleteFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) to
                blockedFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            deleteDataSource.allowDelete.countDown()
            executor.shutdownNow()
            executor.awaitTermination(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun <T, U> runWithHeldConfigurationParentLock(
        configurationDataSource: HeldPromotionConfigurationLockDataSource,
        blockedDataSource: TrackedMenuDeleteDataSource,
        configurationAction: () -> T,
        blockedAction: () -> U,
    ): Pair<T, U> {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val configurationFuture = executor.submit(Callable<T> { configurationAction() })
            assertTrue(
                configurationDataSource.parentLockAcquired.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Promotion configuration did not reach the new reference write",
            )

            val blockedFuture = executor.submit(Callable<U> { blockedAction() })
            assertTrue(
                blockedDataSource.deleteLockAttempted.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Menu delete did not attempt its entity NOWAIT lock",
            )
            val blockedResult = blockedFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            configurationDataSource.allowConfiguration.countDown()
            return configurationFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) to blockedResult
        } finally {
            configurationDataSource.allowConfiguration.countDown()
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

    private inner class HeldPromotionParentLockDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        val parentLockAcquired = CountDownLatch(1)
        val allowLifecycleMutation = CountDownLatch(1)
        val backendPid = AtomicInteger()
        private val held = AtomicBoolean()

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
                    val normalized = sql.normalizedSql()
                    if (
                        normalized.contains("from venue_promotions p") &&
                        normalized.contains("for update") &&
                        held.compareAndSet(false, true)
                    ) {
                        return object : PreparedStatement by prepared {
                            override fun executeQuery(): java.sql.ResultSet {
                                check(!connection.autoCommit) {
                                    "Promotion parent lock must be acquired in the lifecycle transaction"
                                }
                                val resultSet = prepared.executeQuery()
                                parentLockAcquired.countDown()
                                if (!allowLifecycleMutation.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                    resultSet.close()
                                    throw SQLException("Timed out while holding promotion parent lock")
                                }
                                return resultSet
                            }
                        }
                    }
                    return prepared
                }
            }
        }
    }

    private inner class HeldMenuDeletePromotionLockDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        val parentLockAcquired = CountDownLatch(1)
        val allowDelete = CountDownLatch(1)
        val backendPid = AtomicInteger()
        private val held = AtomicBoolean()

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
                    val normalized = sql.normalizedSql()
                    if (
                        normalized.contains("from venue_promotions") &&
                        !normalized.contains("from venue_promotions p") &&
                        normalized.contains("for update") &&
                        held.compareAndSet(false, true)
                    ) {
                        return object : PreparedStatement by prepared {
                            override fun executeQuery(): java.sql.ResultSet {
                                check(!connection.autoCommit) {
                                    "Menu delete promotion locks must share the delete transaction"
                                }
                                val resultSet = prepared.executeQuery()
                                parentLockAcquired.countDown()
                                if (!allowDelete.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                    resultSet.close()
                                    throw SQLException("Timed out while holding menu delete promotion lock")
                                }
                                return resultSet
                            }
                        }
                    }
                    return prepared
                }
            }
        }
    }

    private inner class HeldPromotionConfigurationLockDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        val parentLockAcquired = CountDownLatch(1)
        val allowConfiguration = CountDownLatch(1)
        val backendPid = AtomicInteger()
        private val held = AtomicBoolean()

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
                    val normalized = sql.normalizedSql()
                    if (
                        (
                            normalized.startsWith("insert into promotion_rule_targets") ||
                                normalized.startsWith("insert into promotion_rule_menu_category_targets")
                        ) &&
                        held.compareAndSet(false, true)
                    ) {
                        return object : PreparedStatement by prepared {
                            override fun executeUpdate(): Int {
                                check(!connection.autoCommit) {
                                    "Promotion reference write must run in the configuration transaction"
                                }
                                val updated = prepared.executeUpdate()
                                parentLockAcquired.countDown()
                                if (!allowConfiguration.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                    throw SQLException("Timed out while holding new promotion reference")
                                }
                                return updated
                            }
                        }
                    }
                    return prepared
                }
            }
        }
    }

    private inner class TrackedMenuDeleteDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        val deleteLockAttempted = CountDownLatch(1)
        private val signalled = AtomicBoolean()

        override fun getConnection(): Connection = wrap(delegate.connection)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = wrap(delegate.getConnection(username, password))

        private fun wrap(connection: Connection): Connection {
            return object : Connection by connection {
                override fun prepareStatement(sql: String): PreparedStatement {
                    val prepared = connection.prepareStatement(sql)
                    val normalized = sql.normalizedSql()
                    if (
                        (
                            normalized.contains("from menu_items") ||
                                normalized.contains("from menu_categories")
                        ) &&
                        normalized.contains("for update nowait")
                    ) {
                        return object : PreparedStatement by prepared {
                            override fun executeQuery(): java.sql.ResultSet {
                                if (signalled.compareAndSet(false, true)) {
                                    deleteLockAttempted.countDown()
                                }
                                return prepared.executeQuery()
                            }
                        }
                    }
                    return prepared
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
                    val normalized = sql.normalizedSql()
                    if (normalized.startsWith("update venue_promotions")) {
                        return object : PreparedStatement by prepared {
                            override fun executeUpdate(): Int {
                                signalMutationAttempt()
                                return prepared.executeUpdate()
                            }
                        }
                    }
                    if (
                        normalized.contains("from venue_promotions p") &&
                        normalized.contains("for update")
                    ) {
                        return object : PreparedStatement by prepared {
                            override fun executeQuery(): java.sql.ResultSet {
                                signalMutationAttempt()
                                return prepared.executeQuery()
                            }
                        }
                    }
                    return prepared
                }
            }
        }

        private fun signalMutationAttempt() {
            if (signalled.compareAndSet(false, true)) {
                mutationAttempted.countDown()
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

    private data class LifecycleRaceCall(
        val targetStatus: VenuePromotionStatus,
        val source: VenuePromotionLifecycleSource,
    )

    private data class PromotionLifecycleAudit(
        val actorUserId: Long,
        val action: String,
        val payload: JsonObject,
    )

    private data class MenuDeleteAudit(
        val actorUserId: Long,
        val entityType: String,
        val entityId: Long,
        val payload: JsonObject,
    )

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
        val PROMOTION_CREATION_TABLES = setOf("venue_promotions", "promotion_rules")
    }
}
