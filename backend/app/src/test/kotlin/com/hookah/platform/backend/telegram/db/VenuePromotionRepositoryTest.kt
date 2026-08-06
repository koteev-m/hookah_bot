package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.miniapp.venue.menu.MenuSemanticType
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VenuePromotionRepositoryTest {
    @Test
    fun `management CRUD keeps promotions venue scoped`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-promotions-crud")
            val fixture = seedFixture(jdbcUrl)
            val repository = VenuePromotionRepository(dataSource(jdbcUrl))

            val created =
                repository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "  Сет на компанию  ",
                    description = "  Кальян и чай для компании  ",
                    terms = "  До 23:00  ",
                    createdByUserId = OWNER_ID,
                )

            assertEquals("Сет на компанию", created.title)
            assertEquals("Кальян и чай для компании", created.description)
            assertEquals("До 23:00", created.terms)
            assertEquals(VenuePromotionStatus.DRAFT, created.status)
            assertEquals(VenuePromotionTemplateType.TEXT_ONLY, created.templateType)
            assertEquals(
                listOf(created.id),
                repository.listVenuePromotionsForManagement(fixture.visibleVenueId).map { it.id },
            )
            assertNull(repository.getPromotionForManagement(fixture.otherVenueId, created.id))

            val updated =
                assertNotNull(
                    repository.updatePromotion(
                        venueId = fixture.visibleVenueId,
                        promotionId = created.id,
                        title = "Новый сет",
                        description = "Новое описание",
                        clearTerms = true,
                    ),
                )
            assertEquals("Новый сет", updated.title)
            assertEquals("Новое описание", updated.description)
            assertNull(updated.terms)

            val active =
                assertNotNull(
                    repository.applyLifecycleForTest(
                        venueId = fixture.visibleVenueId,
                        promotionId = created.id,
                        expectedStatus = VenuePromotionStatus.DRAFT,
                        targetStatus = VenuePromotionStatus.ACTIVE,
                    ),
                )
            assertEquals(VenuePromotionStatus.ACTIVE, active.status)

            repository.applyLifecycleForTest(
                venueId = fixture.visibleVenueId,
                promotionId = created.id,
                expectedStatus = VenuePromotionStatus.ACTIVE,
                targetStatus = VenuePromotionStatus.ARCHIVED,
            )
            assertTrue(repository.listVenuePromotionsForManagement(fixture.visibleVenueId).isEmpty())
            assertEquals(
                listOf(created.id),
                repository.listArchivedPromotionsForManagement(fixture.visibleVenueId).map { it.id },
            )
        }

    @Test
    fun `lifecycle active and paused transitions synchronize rules and append safe audits`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-lifecycle-status-audit")
            val fixture = seedFixture(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val ruleRepository = VenuePromotionRuleRepository(dataSource)
            val promotionRepository = VenuePromotionRepository(dataSource, ruleRepository)
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Счастливые часы",
                    description = "Скидка на кальяны",
                    terms = null,
                    startsAt = Instant.parse("2030-01-01T00:00:00Z"),
                    endsAt = Instant.parse("2030-12-31T23:59:59Z"),
                    templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                    createdByUserId = OWNER_ID,
                )
            val rule =
                ruleRepository.createHappyHoursRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    discountPercent = 20,
                    createdByUserId = OWNER_ID,
                    startsTime = LocalTime.of(14, 0),
                    endsTime = LocalTime.of(18, 0),
                    daysOfWeek = setOf(1, 2, 3, 4, 5),
                )

            val activated =
                assertNotNull(
                    promotionRepository.mutatePromotionLifecycle(
                        venueId = fixture.visibleVenueId,
                        promotionId = promotion.id,
                        expectedStatus = VenuePromotionStatus.DRAFT,
                        targetStatus = VenuePromotionStatus.ACTIVE,
                        actorUserId = OWNER_ID,
                        source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                    ),
                )
            assertEquals(VenuePromotionLifecycleOutcome.APPLIED, activated.outcome)
            assertEquals(VenuePromotionStatus.ACTIVE, activated.promotion.status)
            assertEquals(
                VenuePromotionStatus.ACTIVE,
                ruleRepository.getRuleForManagement(fixture.visibleVenueId, rule.id)?.status,
            )

            val paused =
                assertNotNull(
                    promotionRepository.mutatePromotionLifecycle(
                        venueId = fixture.visibleVenueId,
                        promotionId = promotion.id,
                        expectedStatus = VenuePromotionStatus.ACTIVE,
                        targetStatus = VenuePromotionStatus.PAUSED,
                        actorUserId = OWNER_ID,
                        source = VenuePromotionLifecycleSource.TELEGRAM_BOT,
                    ),
                )
            assertEquals(VenuePromotionLifecycleOutcome.APPLIED, paused.outcome)
            assertEquals(VenuePromotionStatus.PAUSED, paused.promotion.status)
            assertEquals(
                VenuePromotionStatus.PAUSED,
                ruleRepository.getRuleForManagement(fixture.visibleVenueId, rule.id)?.status,
            )

            val audits = readPromotionLifecycleAudits(jdbcUrl, promotion.id)
            assertEquals(2, audits.size)
            assertPromotionAuditEnvelope(
                audit = audits[0],
                action = VENUE_PROMOTION_STATUS_CHANGED_ACTION,
                promotionId = promotion.id,
            )
            assertPromotionAuditPayload(
                payload = audits[0].payload,
                venueId = fixture.visibleVenueId,
                promotionId = promotion.id,
                templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                oldStatus = VenuePromotionStatus.DRAFT,
                newStatus = VenuePromotionStatus.ACTIVE,
                source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                expectedRules =
                    listOf(
                        ExpectedAuditRule(
                            ruleId = rule.id,
                            version = rule.version,
                            oldStatus = VenuePromotionStatus.DRAFT,
                            newStatus = VenuePromotionStatus.ACTIVE,
                        ),
                    ),
            )
            assertPromotionAuditEnvelope(
                audit = audits[1],
                action = VENUE_PROMOTION_STATUS_CHANGED_ACTION,
                promotionId = promotion.id,
            )
            assertPromotionAuditPayload(
                payload = audits[1].payload,
                venueId = fixture.visibleVenueId,
                promotionId = promotion.id,
                templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                oldStatus = VenuePromotionStatus.ACTIVE,
                newStatus = VenuePromotionStatus.PAUSED,
                source = VenuePromotionLifecycleSource.TELEGRAM_BOT,
                expectedRules =
                    listOf(
                        ExpectedAuditRule(
                            ruleId = rule.id,
                            version = rule.version,
                            oldStatus = VenuePromotionStatus.ACTIVE,
                            newStatus = VenuePromotionStatus.PAUSED,
                        ),
                    ),
            )
        }

    @Test
    fun `lifecycle audit uses empty rules for promotion without rules`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-lifecycle-no-rules-audit")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Информационная акция",
                    description = "Без правил",
                    terms = null,
                    createdByUserId = OWNER_ID,
                )

            val mutation =
                assertNotNull(
                    promotionRepository.mutatePromotionLifecycle(
                        venueId = fixture.visibleVenueId,
                        promotionId = promotion.id,
                        expectedStatus = VenuePromotionStatus.DRAFT,
                        targetStatus = VenuePromotionStatus.ACTIVE,
                        actorUserId = OWNER_ID,
                        source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                    ),
                )

            assertEquals(VenuePromotionLifecycleOutcome.APPLIED, mutation.outcome)
            val audit = readPromotionLifecycleAudits(jdbcUrl, promotion.id).single()
            assertPromotionAuditPayload(
                payload = audit.payload,
                venueId = fixture.visibleVenueId,
                promotionId = promotion.id,
                templateType = VenuePromotionTemplateType.TEXT_ONLY,
                oldStatus = VenuePromotionStatus.DRAFT,
                newStatus = VenuePromotionStatus.ACTIVE,
                source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                expectedRules = emptyList(),
            )
        }

    @Test
    fun `lifecycle archive audit captures multiple rules in deterministic safe order`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-lifecycle-archive-audit")
            val fixture = seedFixture(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val ruleRepository = VenuePromotionRuleRepository(dataSource)
            val promotionRepository = VenuePromotionRepository(dataSource, ruleRepository)
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Несколько правил",
                    description = "Legacy Happy Hours",
                    terms = null,
                    templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                    createdByUserId = OWNER_ID,
                )
            val firstRule =
                ruleRepository.createHappyHoursRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    discountPercent = 20,
                    createdByUserId = OWNER_ID,
                )
            val secondRule =
                ruleRepository.createHappyHoursRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    targetValue = MenuSemanticType.TEA,
                    discountPercent = 10,
                    createdByUserId = OWNER_ID,
                )

            val archived =
                assertNotNull(
                    promotionRepository.mutatePromotionLifecycle(
                        venueId = fixture.visibleVenueId,
                        promotionId = promotion.id,
                        expectedStatus = VenuePromotionStatus.DRAFT,
                        targetStatus = VenuePromotionStatus.ARCHIVED,
                        actorUserId = OWNER_ID,
                        source = VenuePromotionLifecycleSource.TELEGRAM_BOT,
                    ),
                )

            assertEquals(VenuePromotionLifecycleOutcome.APPLIED, archived.outcome)
            assertEquals(VenuePromotionStatus.ARCHIVED, archived.promotion.status)
            val audit = readPromotionLifecycleAudits(jdbcUrl, promotion.id).single()
            assertPromotionAuditEnvelope(
                audit = audit,
                action = VENUE_PROMOTION_ARCHIVED_ACTION,
                promotionId = promotion.id,
            )
            assertPromotionAuditPayload(
                payload = audit.payload,
                venueId = fixture.visibleVenueId,
                promotionId = promotion.id,
                templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                oldStatus = VenuePromotionStatus.DRAFT,
                newStatus = VenuePromotionStatus.ARCHIVED,
                source = VenuePromotionLifecycleSource.TELEGRAM_BOT,
                expectedRules =
                    listOf(firstRule, secondRule).map { rule ->
                        ExpectedAuditRule(
                            ruleId = rule.id,
                            version = rule.version,
                            oldStatus = VenuePromotionStatus.DRAFT,
                            newStatus = VenuePromotionStatus.ARCHIVED,
                        )
                    },
            )
        }

    @Test
    fun `lifecycle no-op repeated archive and not found write no audit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-lifecycle-no-op-audit")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val activePromotionId =
                insertPromotion(
                    jdbcUrl = jdbcUrl,
                    venueId = fixture.visibleVenueId,
                    title = "Уже активна",
                    status = VenuePromotionStatus.ACTIVE,
                    startsAt = null,
                    endsAt = null,
                )
            val archivedPromotionId =
                insertPromotion(
                    jdbcUrl = jdbcUrl,
                    venueId = fixture.visibleVenueId,
                    title = "Уже архивирована",
                    status = VenuePromotionStatus.ARCHIVED,
                    startsAt = null,
                    endsAt = null,
                )

            val repeatedStatus =
                assertNotNull(
                    promotionRepository.mutatePromotionLifecycle(
                        venueId = fixture.visibleVenueId,
                        promotionId = activePromotionId,
                        expectedStatus = VenuePromotionStatus.DRAFT,
                        targetStatus = VenuePromotionStatus.ACTIVE,
                        actorUserId = OWNER_ID,
                        source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                    ),
                )
            val repeatedArchive =
                assertNotNull(
                    promotionRepository.mutatePromotionLifecycle(
                        venueId = fixture.visibleVenueId,
                        promotionId = archivedPromotionId,
                        expectedStatus = VenuePromotionStatus.ACTIVE,
                        targetStatus = VenuePromotionStatus.ARCHIVED,
                        actorUserId = OWNER_ID,
                        source = VenuePromotionLifecycleSource.TELEGRAM_BOT,
                    ),
                )
            val missing =
                promotionRepository.mutatePromotionLifecycle(
                    venueId = fixture.visibleVenueId,
                    promotionId = Long.MAX_VALUE,
                    expectedStatus = VenuePromotionStatus.DRAFT,
                    targetStatus = VenuePromotionStatus.ACTIVE,
                    actorUserId = OWNER_ID,
                    source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                )

            assertEquals(VenuePromotionLifecycleOutcome.NO_OP, repeatedStatus.outcome)
            assertEquals(VenuePromotionLifecycleOutcome.NO_OP, repeatedArchive.outcome)
            assertNull(missing)
            assertTrue(readPromotionLifecycleAudits(jdbcUrl, activePromotionId).isEmpty())
            assertTrue(readPromotionLifecycleAudits(jdbcUrl, archivedPromotionId).isEmpty())
            assertEquals(0, countPromotionLifecycleAudits(jdbcUrl))
        }

    @Test
    fun `lifecycle stale returns authoritative promotion and writes no audit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-lifecycle-stale-no-audit")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Конкурентное изменение",
                    description = "Описание",
                    terms = null,
                    createdByUserId = OWNER_ID,
                )

            val stale =
                assertNotNull(
                    promotionRepository.mutatePromotionLifecycle(
                        venueId = fixture.visibleVenueId,
                        promotionId = promotion.id,
                        expectedStatus = VenuePromotionStatus.ACTIVE,
                        targetStatus = VenuePromotionStatus.PAUSED,
                        actorUserId = OWNER_ID,
                        source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                    ),
                )

            assertEquals(VenuePromotionLifecycleOutcome.STALE, stale.outcome)
            assertEquals(promotion.id, stale.promotion.id)
            assertEquals(VenuePromotionStatus.DRAFT, stale.promotion.status)
            assertEquals(
                VenuePromotionStatus.DRAFT,
                promotionRepository.getPromotionForManagement(fixture.visibleVenueId, promotion.id)?.status,
            )
            assertTrue(readPromotionLifecycleAudits(jdbcUrl, promotion.id).isEmpty())
            assertEquals(0, countPromotionLifecycleAudits(jdbcUrl))
        }

    @Test
    fun `status audit failure rolls back parent rules timestamps and visibility`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-lifecycle-status-audit-rollback")
            val fixture = seedFixture(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val ruleRepository = VenuePromotionRuleRepository(dataSource)
            val promotionRepository = VenuePromotionRepository(dataSource, ruleRepository)
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Rollback status",
                    description = "Скидка",
                    terms = null,
                    startsAt = Instant.parse("2030-01-01T00:00:00Z"),
                    endsAt = Instant.parse("2030-12-31T23:59:59Z"),
                    templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                    createdByUserId = OWNER_ID,
                )
            val rule =
                ruleRepository.createHappyHoursRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    discountPercent = 20,
                    createdByUserId = OWNER_ID,
                    startsTime = LocalTime.of(14, 0),
                    endsTime = LocalTime.of(18, 0),
                    daysOfWeek = setOf(1, 2, 3),
                )
            val before = readLifecycleSnapshot(jdbcUrl, promotion.id)
            val failingRepository =
                VenuePromotionRepository(
                    dataSource = dataSource,
                    ruleRepository = ruleRepository,
                    auditLogWriter = failingAuditLogWriter(),
                )

            assertFailsWith<DatabaseUnavailableException> {
                failingRepository.mutatePromotionLifecycle(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    expectedStatus = VenuePromotionStatus.DRAFT,
                    targetStatus = VenuePromotionStatus.ACTIVE,
                    actorUserId = OWNER_ID,
                    source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                )
            }

            assertEquals(before, readLifecycleSnapshot(jdbcUrl, promotion.id))
            assertEquals(VenuePromotionStatus.DRAFT, before.promotionStatus)
            assertEquals(listOf(rule.id), before.rules.map { it.ruleId })
            assertTrue(readPromotionLifecycleAudits(jdbcUrl, promotion.id).isEmpty())
            assertNull(
                promotionRepository.getPromotionForGuest(
                    promotionId = promotion.id,
                    now = Instant.parse("2030-06-01T12:00:00Z"),
                ),
            )
            assertTrue(ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId).isEmpty())
        }

    @Test
    fun `archive audit failure rolls back parent rules versions and timestamps`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-lifecycle-archive-audit-rollback")
            val fixture = seedFixture(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val ruleRepository = VenuePromotionRuleRepository(dataSource)
            val promotionRepository = VenuePromotionRepository(dataSource, ruleRepository)
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Rollback archive",
                    description = "Legacy rule",
                    terms = null,
                    templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                    createdByUserId = OWNER_ID,
                )
            val rule =
                ruleRepository.createHappyHoursRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    discountPercent = 20,
                    createdByUserId = OWNER_ID,
                )
            val before = readLifecycleSnapshot(jdbcUrl, promotion.id)
            val failingRepository =
                VenuePromotionRepository(
                    dataSource = dataSource,
                    ruleRepository = ruleRepository,
                    auditLogWriter = failingAuditLogWriter(),
                )

            assertFailsWith<DatabaseUnavailableException> {
                failingRepository.mutatePromotionLifecycle(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    expectedStatus = VenuePromotionStatus.DRAFT,
                    targetStatus = VenuePromotionStatus.ARCHIVED,
                    actorUserId = OWNER_ID,
                    source = VenuePromotionLifecycleSource.TELEGRAM_BOT,
                )
            }

            assertEquals(before, readLifecycleSnapshot(jdbcUrl, promotion.id))
            assertEquals(VenuePromotionStatus.DRAFT, before.promotionStatus)
            assertEquals(listOf(rule.id), before.rules.map { it.ruleId })
            assertTrue(readPromotionLifecycleAudits(jdbcUrl, promotion.id).isEmpty())
        }

    @Test
    fun `management CRUD persists happy hours template type`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-promotions-template")
            val fixture = seedFixture(jdbcUrl)
            val repository = VenuePromotionRepository(dataSource(jdbcUrl))

            val created =
                repository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Счастливые часы",
                    description = "20% на кальяны",
                    terms = null,
                    templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                    createdByUserId = OWNER_ID,
                )

            assertEquals(VenuePromotionTemplateType.HAPPY_HOURS_PERCENT, created.templateType)
            assertEquals(
                VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                repository.getPromotionForManagement(fixture.visibleVenueId, created.id)?.templateType,
            )
        }

    @Test
    fun `promotion media repository replaces and deletes primary image`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-promotion-media")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val mediaRepository = VenuePromotionMediaRepository(dataSource(jdbcUrl))
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Афиша",
                    description = "Живая музыка",
                    terms = null,
                    templateType = VenuePromotionTemplateType.BANNER,
                    createdByUserId = OWNER_ID,
                )

            val added =
                assertNotNull(
                    mediaRepository.addMedia(
                        venueId = fixture.visibleVenueId,
                        promotionId = promotion.id,
                        mediaType = VenuePromotionMediaType.IMAGE,
                        fileId = "photo-one",
                    ),
                )
            assertEquals("photo-one", added.telegramFileId)
            assertEquals("photo-one", mediaRepository.getPrimaryImage(promotion.id)?.telegramFileId)

            val replacement =
                assertNotNull(
                    mediaRepository.replacePrimaryImage(
                        venueId = fixture.visibleVenueId,
                        promotionId = promotion.id,
                        fileId = "photo-two",
                    ),
                )
            assertEquals("photo-two", replacement.telegramFileId)
            assertEquals(listOf("photo-two"), mediaRepository.listByPromotionId(promotion.id).map { it.telegramFileId })
            assertNull(mediaRepository.replacePrimaryImage(fixture.otherVenueId, promotion.id, "wrong-venue"))

            assertTrue(mediaRepository.deletePrimaryImage(fixture.visibleVenueId, promotion.id))
            assertNull(mediaRepository.getPrimaryImage(promotion.id))
            assertTrue(mediaRepository.listByPromotionId(promotion.id).isEmpty())
        }

    @Test
    fun `promotion placement repository manages requests and guest visibility`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-placements")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val mediaRepository = VenuePromotionMediaRepository(dataSource(jdbcUrl))
            val placementRepository = PromotionPlacementRepository(dataSource(jdbcUrl))
            val activeBanner =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Афиша",
                    description = "Живая музыка",
                    terms = null,
                    templateType = VenuePromotionTemplateType.BANNER,
                    createdByUserId = OWNER_ID,
                )
            assertNotNull(
                promotionRepository.applyLifecycleForTest(
                    venueId = fixture.visibleVenueId,
                    promotionId = activeBanner.id,
                    expectedStatus = VenuePromotionStatus.DRAFT,
                    targetStatus = VenuePromotionStatus.ACTIVE,
                ),
            )
            assertNotNull(mediaRepository.replacePrimaryImage(fixture.visibleVenueId, activeBanner.id, "photo-one"))

            val request =
                assertNotNull(
                    placementRepository.createRequest(
                        promotionId = activeBanner.id,
                        venueId = fixture.visibleVenueId,
                        surface = PromotionPlacementSurface.GLOBAL_PROMOTIONS_TOP,
                        requestedByUserId = OWNER_ID,
                    ),
                )
            assertEquals(PromotionPlacementStatus.PENDING, request.status)
            assertEquals(listOf(request.id), placementRepository.listPending().map { it.id })
            assertEquals(
                listOf(request.id),
                placementRepository
                    .listForVenueManagement(fixture.visibleVenueId, PromotionPlacementStatus.PENDING)
                    .map { it.id },
            )

            val now = Instant.parse("2026-05-18T10:00:00Z")
            val activePlacement =
                assertNotNull(
                    placementRepository.approveForPeriod(
                        id = request.id,
                        platformUserId = OWNER_ID,
                        startsAt = now,
                        endsAt = now.plusSeconds(7 * 24 * 60 * 60),
                    ),
                )
            assertEquals(PromotionPlacementStatus.ACTIVE, activePlacement.status)
            assertEquals(
                listOf(activePlacement.id),
                placementRepository.listActiveForGlobalPromotions(now = now).map { it.id },
            )
            assertEquals(
                listOf(activePlacement.id),
                placementRepository.listActiveForPlatformManagement(now = now).map { it.id },
            )
            assertEquals(
                listOf(activePlacement.id),
                placementRepository
                    .listForVenueManagement(fixture.visibleVenueId, PromotionPlacementStatus.ACTIVE, now = now)
                    .map { it.id },
            )
            assertNotNull(placementRepository.getForVenueManagement(fixture.visibleVenueId, activePlacement.id))
            assertNull(placementRepository.getForVenueManagement(fixture.otherVenueId, activePlacement.id))
            assertTrue(placementRepository.listActiveForVenuePromotions(fixture.visibleVenueId, now = now).isEmpty())

            val noImageBanner =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Без картинки",
                    description = "Текст",
                    terms = null,
                    templateType = VenuePromotionTemplateType.BANNER,
                    createdByUserId = OWNER_ID,
                )
            assertNotNull(
                promotionRepository.applyLifecycleForTest(
                    venueId = fixture.visibleVenueId,
                    promotionId = noImageBanner.id,
                    expectedStatus = VenuePromotionStatus.DRAFT,
                    targetStatus = VenuePromotionStatus.ACTIVE,
                ),
            )
            val noImageRequest =
                assertNotNull(
                    placementRepository.createRequest(
                        promotionId = noImageBanner.id,
                        venueId = fixture.visibleVenueId,
                        surface = PromotionPlacementSurface.GLOBAL_PROMOTIONS_TOP,
                        requestedByUserId = OWNER_ID,
                    ),
                )
            assertNotNull(
                placementRepository.approveForPeriod(
                    id = noImageRequest.id,
                    platformUserId = OWNER_ID,
                    startsAt = now,
                    endsAt = now.plusSeconds(7 * 24 * 60 * 60),
                ),
            )
            assertEquals(
                listOf(activePlacement.id),
                placementRepository.listActiveForGlobalPromotions(now = now).map { it.id },
            )

            val expiredBanner =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Истёкшая афиша",
                    description = "Текст",
                    terms = null,
                    templateType = VenuePromotionTemplateType.BANNER,
                    createdByUserId = OWNER_ID,
                )
            assertNotNull(
                promotionRepository.applyLifecycleForTest(
                    venueId = fixture.visibleVenueId,
                    promotionId = expiredBanner.id,
                    expectedStatus = VenuePromotionStatus.DRAFT,
                    targetStatus = VenuePromotionStatus.ACTIVE,
                ),
            )
            assertNotNull(
                mediaRepository.replacePrimaryImage(fixture.visibleVenueId, expiredBanner.id, "photo-expired"),
            )
            val expiredRequest =
                assertNotNull(
                    placementRepository.createRequest(
                        promotionId = expiredBanner.id,
                        venueId = fixture.visibleVenueId,
                        surface = PromotionPlacementSurface.GLOBAL_PROMOTIONS_TOP,
                        requestedByUserId = OWNER_ID,
                    ),
                )
            val expiredPlacement =
                assertNotNull(
                    placementRepository.approveForPeriod(
                        id = expiredRequest.id,
                        platformUserId = OWNER_ID,
                        startsAt = now.minusSeconds(2 * 24 * 60 * 60),
                        endsAt = now.minusSeconds(24 * 60 * 60),
                    ),
                )
            assertEquals(
                listOf(activePlacement.id),
                placementRepository.listActiveForGlobalPromotions(now = now).map { it.id },
            )
            assertFalse(
                placementRepository.listActiveForPlatformManagement(
                    now = now,
                ).map { it.id }.contains(expiredPlacement.id),
            )
            assertTrue(
                placementRepository.listFinishedForPlatformManagement(
                    now = now,
                ).map { it.id }.contains(expiredPlacement.id),
            )
            assertTrue(
                placementRepository.listFinishedForVenueManagement(fixture.visibleVenueId, now = now).map {
                    it.id
                }.contains(expiredPlacement.id),
            )

            val textPromotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Простая",
                    description = "Текст",
                    terms = null,
                    templateType = VenuePromotionTemplateType.TEXT_ONLY,
                    createdByUserId = OWNER_ID,
                )
            assertNull(
                placementRepository.createRequest(
                    promotionId = textPromotion.id,
                    venueId = fixture.visibleVenueId,
                    surface = PromotionPlacementSurface.GLOBAL_PROMOTIONS_TOP,
                    requestedByUserId = OWNER_ID,
                ),
            )

            assertNotNull(
                promotionRepository.applyLifecycleForTest(
                    venueId = fixture.visibleVenueId,
                    promotionId = activeBanner.id,
                    expectedStatus = VenuePromotionStatus.ACTIVE,
                    targetStatus = VenuePromotionStatus.ARCHIVED,
                ),
            )
            assertTrue(placementRepository.listActiveForGlobalPromotions().isEmpty())
            val archivedPlacement = assertNotNull(placementRepository.archive(activePlacement.id))
            assertEquals(PromotionPlacementStatus.ARCHIVED, archivedPlacement.status)
            assertTrue(
                placementRepository.listFinishedForPlatformManagement(
                    now = now,
                ).map { it.id }.contains(activePlacement.id),
            )
        }

    @Test
    fun `venue top placement repository manages requests and guest visibility`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-venue-placements")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val placementRepository = PromotionVenuePlacementRepository(dataSource(jdbcUrl))
            val now = Instant.parse("2026-05-18T10:00:00Z")
            val activePromotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Счастливые часы",
                    description = "Скидка на кальяны",
                    terms = null,
                    createdByUserId = OWNER_ID,
                )
            assertNotNull(
                promotionRepository.applyLifecycleForTest(
                    venueId = fixture.visibleVenueId,
                    promotionId = activePromotion.id,
                    expectedStatus = VenuePromotionStatus.DRAFT,
                    targetStatus = VenuePromotionStatus.ACTIVE,
                ),
            )

            val request = assertNotNull(placementRepository.createRequest(fixture.visibleVenueId, OWNER_ID))
            assertEquals(PromotionPlacementStatus.PENDING, request.status)
            assertEquals(listOf(request.id), placementRepository.listPendingForPlatform().map { it.id })

            val active =
                assertNotNull(
                    placementRepository.approve(
                        id = request.id,
                        platformUserId = OWNER_ID,
                        startsAt = now,
                        endsAt = now.plusSeconds(7 * 24 * 60 * 60),
                    ),
                )
            assertEquals(PromotionPlacementStatus.ACTIVE, active.status)
            assertTrue(placementRepository.listPendingForPlatform().isEmpty())
            assertEquals(listOf(active.id), placementRepository.listActiveForGlobalFeed(now = now).map { it.id })
            assertEquals(
                listOf(active.id),
                placementRepository.listActiveForPlatformManagement(now = now).map { it.id },
            )
            assertEquals(
                listOf(active.id),
                placementRepository
                    .listForVenueManagement(fixture.visibleVenueId, PromotionPlacementStatus.ACTIVE, now = now)
                    .map { it.id },
            )

            val noPromoRequest = assertNotNull(placementRepository.createRequest(fixture.otherVenueId, OWNER_ID))
            assertNotNull(
                placementRepository.approve(
                    id = noPromoRequest.id,
                    platformUserId = OWNER_ID,
                    startsAt = now,
                    endsAt = now.plusSeconds(7 * 24 * 60 * 60),
                ),
            )
            assertEquals(listOf(active.id), placementRepository.listActiveForGlobalFeed(now = now).map { it.id })

            val futureStartRequest = assertNotNull(placementRepository.createRequest(fixture.visibleVenueId, OWNER_ID))
            val futureStart =
                assertNotNull(
                    placementRepository.approve(
                        id = futureStartRequest.id,
                        platformUserId = OWNER_ID,
                        startsAt = now.plusSeconds(24 * 60 * 60),
                        endsAt = now.plusSeconds(8 * 24 * 60 * 60),
                    ),
                )
            assertTrue(placementRepository.listPendingForPlatform().none { it.id == futureStart.id })
            assertTrue(
                placementRepository.listActiveForPlatformManagement(now = now).map { it.id }.contains(futureStart.id),
            )
            assertEquals(listOf(active.id), placementRepository.listActiveForGlobalFeed(now = now).map { it.id })

            val expiredRequest = assertNotNull(placementRepository.createRequest(fixture.visibleVenueId, OWNER_ID))
            val expired =
                assertNotNull(
                    placementRepository.approve(
                        id = expiredRequest.id,
                        platformUserId = OWNER_ID,
                        startsAt = now.minusSeconds(2 * 24 * 60 * 60),
                        endsAt = now.minusSeconds(24 * 60 * 60),
                    ),
                )
            assertEquals(listOf(active.id), placementRepository.listActiveForGlobalFeed(now = now).map { it.id })
            assertTrue(
                placementRepository.listFinishedForPlatformManagement(now = now).map { it.id }.contains(expired.id),
            )
            assertNull(placementRepository.getForVenueManagement(fixture.otherVenueId, active.id))
            assertNotNull(placementRepository.getForVenueManagement(fixture.visibleVenueId, active.id))

            val archived = assertNotNull(placementRepository.archive(active.id))
            assertEquals(PromotionPlacementStatus.ARCHIVED, archived.status)
            assertTrue(placementRepository.listActiveForGlobalFeed(now = now).isEmpty())
        }

    @Test
    fun `guest list shows only active visible and in-period promotions`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-promotions-visibility")
            val fixture = seedFixture(jdbcUrl)
            val repository = VenuePromotionRepository(dataSource(jdbcUrl))
            val now = Instant.parse("2026-05-14T12:00:00Z")

            val visibleActive =
                insertPromotion(
                    jdbcUrl,
                    fixture.visibleVenueId,
                    "Активная",
                    VenuePromotionStatus.ACTIVE,
                    now.minusSeconds(3600),
                    now.plusSeconds(3600),
                )
            insertPromotion(jdbcUrl, fixture.visibleVenueId, "Черновик", VenuePromotionStatus.DRAFT, null, null)
            insertPromotion(jdbcUrl, fixture.visibleVenueId, "Пауза", VenuePromotionStatus.PAUSED, null, null)
            insertPromotion(jdbcUrl, fixture.visibleVenueId, "Архив", VenuePromotionStatus.ARCHIVED, null, null)
            insertPromotion(
                jdbcUrl,
                fixture.visibleVenueId,
                "Будущая",
                VenuePromotionStatus.ACTIVE,
                now.plusSeconds(3600),
                null,
            )
            insertPromotion(
                jdbcUrl,
                fixture.visibleVenueId,
                "Истекшая",
                VenuePromotionStatus.ACTIVE,
                null,
                now.minusSeconds(3600),
            )
            insertPromotion(
                jdbcUrl,
                fixture.hiddenVenueId,
                "Скрытое заведение",
                VenuePromotionStatus.ACTIVE,
                null,
                null,
            )
            insertPromotion(jdbcUrl, fixture.blockedVenueId, "Блок подписки", VenuePromotionStatus.ACTIVE, null, null)

            val promotions = repository.listActivePromotionsForGuest(now = now)

            assertEquals(listOf(visibleActive), promotions.map { it.id })
            assertEquals(visibleActive, repository.getPromotionForGuest(visibleActive, now)?.id)
            assertNull(repository.getPromotionForGuest(visibleActive + 1000, now))
        }

    @Test
    fun `guest list returns multiple active visible promotions`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-promotions-multiple-active")
            val fixture = seedFixture(jdbcUrl)
            val repository = VenuePromotionRepository(dataSource(jdbcUrl))
            val now = Instant.parse("2026-05-14T12:00:00Z")
            val first =
                insertPromotion(
                    jdbcUrl,
                    fixture.visibleVenueId,
                    "Первая",
                    VenuePromotionStatus.ACTIVE,
                    now.minusSeconds(7200),
                    null,
                )
            val second =
                insertPromotion(
                    jdbcUrl,
                    fixture.visibleVenueId,
                    "Вторая",
                    VenuePromotionStatus.ACTIVE,
                    now.minusSeconds(3600),
                    null,
                )

            val promotions = repository.listActivePromotionsForGuest(now = now)

            assertEquals(listOf(second, first), promotions.map { it.id })
        }

    @Test
    fun `guest promotion venue feed groups active promotions by venue`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-promotions-feed")
            val fixture = seedFixture(jdbcUrl)
            val repository = VenuePromotionRepository(dataSource(jdbcUrl))
            val now = Instant.parse("2026-05-14T12:00:00Z")
            val excluded =
                insertPromotion(
                    jdbcUrl,
                    fixture.visibleVenueId,
                    "Продвигаемая",
                    VenuePromotionStatus.ACTIVE,
                    now.minusSeconds(300),
                    null,
                )
            val visible =
                insertPromotion(
                    jdbcUrl,
                    fixture.visibleVenueId,
                    "Счастливые часы",
                    VenuePromotionStatus.ACTIVE,
                    now.minusSeconds(600),
                    null,
                )
            insertPromotion(
                jdbcUrl,
                fixture.visibleVenueId,
                "Афиша",
                VenuePromotionStatus.ACTIVE,
                now.minusSeconds(700),
                null,
            )
            insertPromotion(
                jdbcUrl,
                fixture.visibleVenueId,
                "Сет",
                VenuePromotionStatus.ACTIVE,
                now.minusSeconds(800),
                null,
            )
            insertPromotion(
                jdbcUrl,
                fixture.visibleVenueId,
                "Поздний чай",
                VenuePromotionStatus.ACTIVE,
                now.minusSeconds(900),
                null,
            )
            val other =
                insertPromotion(
                    jdbcUrl,
                    fixture.otherVenueId,
                    "Другое заведение",
                    VenuePromotionStatus.ACTIVE,
                    now.minusSeconds(1200),
                    null,
                )

            val feed =
                repository.listPromotionVenuesForGuest(
                    limit = 5,
                    now = now,
                    excludePromotionIds = setOf(excluded),
                )

            val mix = assertNotNull(feed.firstOrNull { it.venueId == fixture.visibleVenueId })
            assertEquals("Mix", mix.venueName)
            assertEquals("Москва", mix.city)
            assertEquals("Тверская, 1", mix.address)
            assertEquals(4, mix.promotionsCount)
            assertEquals(listOf(visible), mix.previewPromotions.map { it.id }.take(1))
            assertEquals(3, mix.previewPromotions.size)
            assertTrue(mix.previewPromotions.none { it.id == excluded })
            assertTrue(
                feed.any {
                        item ->
                    item.venueId == fixture.otherVenueId && item.previewPromotions.any { it.id == other }
                },
            )
        }

    @Test
    fun `guest promotion venue feed filters hidden inactive future expired and blocked promotions`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-promotions-feed-visibility")
            val fixture = seedFixture(jdbcUrl)
            val repository = VenuePromotionRepository(dataSource(jdbcUrl))
            val now = Instant.parse("2026-05-14T12:00:00Z")
            val visible =
                insertPromotion(
                    jdbcUrl,
                    fixture.visibleVenueId,
                    "Активная",
                    VenuePromotionStatus.ACTIVE,
                    now.minusSeconds(3600),
                    now.plusSeconds(3600),
                )
            insertPromotion(jdbcUrl, fixture.visibleVenueId, "Черновик", VenuePromotionStatus.DRAFT, null, null)
            insertPromotion(jdbcUrl, fixture.visibleVenueId, "Пауза", VenuePromotionStatus.PAUSED, null, null)
            insertPromotion(jdbcUrl, fixture.visibleVenueId, "Архив", VenuePromotionStatus.ARCHIVED, null, null)
            insertPromotion(
                jdbcUrl,
                fixture.visibleVenueId,
                "Будущая",
                VenuePromotionStatus.ACTIVE,
                now.plusSeconds(3600),
                null,
            )
            insertPromotion(
                jdbcUrl,
                fixture.visibleVenueId,
                "Истекшая",
                VenuePromotionStatus.ACTIVE,
                null,
                now.minusSeconds(3600),
            )
            insertPromotion(jdbcUrl, fixture.hiddenVenueId, "Скрытое", VenuePromotionStatus.ACTIVE, null, null)
            insertPromotion(jdbcUrl, fixture.blockedVenueId, "Блок", VenuePromotionStatus.ACTIVE, null, null)

            val feed = repository.listPromotionVenuesForGuest(now = now)

            assertEquals(listOf(fixture.visibleVenueId), feed.map { it.venueId })
            assertEquals(listOf(visible), feed.single().previewPromotions.map { it.id })
            assertEquals(1, feed.single().promotionsCount)
        }

    @Test
    fun `guest promotion venue feed supports offset pagination`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-promotions-feed-pagination")
            seedFixture(jdbcUrl)
            val repository = VenuePromotionRepository(dataSource(jdbcUrl))
            val now = Instant.parse("2026-05-14T12:00:00Z")
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                (1..6).forEach { index ->
                    val venueId = insertVenue(connection, "Venue $index", VenueStatus.PUBLISHED.dbValue)
                    insertPromotion(
                        jdbcUrl,
                        venueId,
                        "Акция $index",
                        VenuePromotionStatus.ACTIVE,
                        now.minusSeconds(index.toLong()),
                        null,
                    )
                }
            }

            val firstPage = repository.listPromotionVenuesForGuest(limit = 5, offset = 0, now = now)
            val secondPage = repository.listPromotionVenuesForGuest(limit = 5, offset = 5, now = now)

            assertEquals(5, firstPage.size)
            assertEquals(1, secondPage.size)
            assertEquals("Venue 6", secondPage.single().venueName)
        }

    @Test
    fun `venue guest list is scoped by venue and visibility`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-promotions-venue-list")
            val fixture = seedFixture(jdbcUrl)
            val repository = VenuePromotionRepository(dataSource(jdbcUrl))
            val now = Instant.parse("2026-05-14T12:00:00Z")
            val first =
                insertPromotion(jdbcUrl, fixture.visibleVenueId, "Первая", VenuePromotionStatus.ACTIVE, null, null)
            insertPromotion(jdbcUrl, fixture.otherVenueId, "Другая", VenuePromotionStatus.ACTIVE, null, null)
            insertPromotion(
                jdbcUrl,
                fixture.visibleVenueId,
                "Будущая",
                VenuePromotionStatus.ACTIVE,
                now.plusSeconds(3600),
                null,
            )

            val venuePromotions = repository.listActivePromotionsForVenue(fixture.visibleVenueId, now = now)

            assertEquals(listOf(first), venuePromotions.map { it.id })
        }

    @Test
    fun `private preview list ignores parent availability and keeps child eligibility`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-promotions-private-preview")
            val fixture = seedFixture(jdbcUrl)
            val repository = VenuePromotionRepository(dataSource(jdbcUrl))
            val now = Instant.parse("2026-05-14T12:00:00Z")
            val (draftVenueId, blockedDraftVenueId) =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    val draft = insertVenue(connection, "Draft preview", VenueStatus.DRAFT.dbValue)
                    val blockedDraft = insertVenue(connection, "Blocked draft", VenueStatus.DRAFT.dbValue)
                    insertSubscription(connection, draft, "ACTIVE")
                    insertSubscription(connection, blockedDraft, "SUSPENDED_BY_PLATFORM")
                    connection.prepareStatement(
                        "UPDATE venue_subscriptions SET status = ? WHERE venue_id = ?",
                    ).use { statement ->
                        statement.setString(1, "SUSPENDED_BY_PLATFORM")
                        statement.setLong(2, fixture.visibleVenueId)
                        statement.executeUpdate()
                    }
                    draft to blockedDraft
                }

            val current =
                insertPromotion(
                    jdbcUrl,
                    draftVenueId,
                    "Активная",
                    VenuePromotionStatus.ACTIVE,
                    now.minusSeconds(3_600),
                    now.plusSeconds(3_600),
                )
            insertPromotion(jdbcUrl, draftVenueId, "Черновик", VenuePromotionStatus.DRAFT, null, null)
            insertPromotion(jdbcUrl, draftVenueId, "Пауза", VenuePromotionStatus.PAUSED, null, null)
            insertPromotion(jdbcUrl, draftVenueId, "Архив", VenuePromotionStatus.ARCHIVED, null, null)
            insertPromotion(
                jdbcUrl,
                draftVenueId,
                "Будущая",
                VenuePromotionStatus.ACTIVE,
                now.plusSeconds(3_600),
                null,
            )
            insertPromotion(
                jdbcUrl,
                draftVenueId,
                "Истекшая",
                VenuePromotionStatus.ACTIVE,
                null,
                now.minusSeconds(3_600),
            )
            val blocked =
                insertPromotion(
                    jdbcUrl,
                    blockedDraftVenueId,
                    "Блок подписки",
                    VenuePromotionStatus.ACTIVE,
                    null,
                    null,
                )
            val published =
                insertPromotion(
                    jdbcUrl,
                    fixture.visibleVenueId,
                    "Заблокированное опубликованное заведение",
                    VenuePromotionStatus.ACTIVE,
                    null,
                    null,
                )

            val promotions =
                repository.listActivePromotionsForPrivatePreview(
                    venueId = draftVenueId,
                    now = now,
                )

            assertEquals(listOf(current), promotions.map { it.id })
            assertEquals(
                listOf(blocked),
                repository
                    .listActivePromotionsForPrivatePreview(
                        venueId = blockedDraftVenueId,
                        now = now,
                    ).map { it.id },
            )
            assertEquals(
                listOf(published),
                repository
                    .listActivePromotionsForPrivatePreview(
                        venueId = fixture.visibleVenueId,
                        now = now,
                    ).map { it.id },
            )
        }

    @Test
    fun `promotion rule repository lists empty rules for draft promotion`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-rules-empty")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val ruleRepository = VenuePromotionRuleRepository(dataSource(jdbcUrl))
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Счастливые часы",
                    description = "20% на кальяны",
                    terms = null,
                    createdByUserId = OWNER_ID,
                )

            assertEquals(
                emptyList(),
                ruleRepository.listRulesForPromotionManagement(fixture.visibleVenueId, promotion.id),
            )
            assertEquals(emptyList(), ruleRepository.listRulesForVenueManagement(fixture.visibleVenueId))
        }

    @Test
    fun `promotion rule repository creates activates and lists happy hours rules`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-rules-crud")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val ruleRepository = VenuePromotionRuleRepository(dataSource(jdbcUrl))
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Счастливые часы",
                    description = "20% на кальяны",
                    terms = null,
                    createdByUserId = OWNER_ID,
                )

            val created =
                ruleRepository.createHappyHoursRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    discountPercent = 20,
                    createdByUserId = OWNER_ID,
                    startsTime = LocalTime.of(14, 0),
                    endsTime = LocalTime.of(18, 0),
                    daysOfWeek = setOf(1, 2, 3, 4, 5),
                )

            assertEquals(VenuePromotionStatus.DRAFT, created.status)
            assertEquals(MenuSemanticType.HOOKAH, created.targetValue)
            assertEquals(20, created.discountPercent)
            assertEquals(setOf(1, 2, 3, 4, 5), created.daysOfWeek)
            assertEquals(PromotionRuleTargetType.CATEGORY_TYPE, created.targets.single().targetType)
            assertEquals(MenuSemanticType.HOOKAH, created.targets.single().semanticType)
            assertEquals(
                listOf(
                    created.id,
                ),
                ruleRepository.listRulesForPromotionManagement(fixture.visibleVenueId, promotion.id).map {
                    it.id
                },
            )
            assertEquals(emptyList(), ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId))

            val edited =
                assertNotNull(
                    ruleRepository.updateHappyHoursRule(
                        venueId = fixture.visibleVenueId,
                        ruleId = created.id,
                        targetValue = MenuSemanticType.TEA,
                        discountPercent = 10,
                    ),
                )
            assertEquals(MenuSemanticType.TEA, edited.targetValue)
            assertEquals(10, edited.discountPercent)
            assertEquals(PromotionRuleTargetType.CATEGORY_TYPE, edited.targets.single().targetType)
            assertEquals(MenuSemanticType.TEA, edited.targets.single().semanticType)

            val active =
                assertNotNull(
                    ruleRepository.setRuleStatus(fixture.visibleVenueId, created.id, VenuePromotionStatus.ACTIVE),
                )
            assertEquals(VenuePromotionStatus.ACTIVE, active.status)
            assertEquals(emptyList(), ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId))
            assertNotNull(
                promotionRepository.applyLifecycleForTest(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    expectedStatus = VenuePromotionStatus.DRAFT,
                    targetStatus = VenuePromotionStatus.ACTIVE,
                ),
            )
            assertEquals(
                listOf(created.id),
                ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId).map { it.id },
            )
            assertNull(ruleRepository.getRuleForManagement(fixture.otherVenueId, created.id))

            val scheduled =
                assertNotNull(
                    ruleRepository.updateRuleSchedule(
                        venueId = fixture.visibleVenueId,
                        ruleId = created.id,
                        startsTime = LocalTime.of(15, 0),
                        endsTime = LocalTime.of(19, 0),
                        daysOfWeek = setOf(1, 2, 3),
                    ),
                )
            assertEquals(LocalTime.of(15, 0), scheduled.startsTime)
            assertEquals(LocalTime.of(19, 0), scheduled.endsTime)
            assertEquals(setOf(1, 2, 3), scheduled.daysOfWeek)

            val always = assertNotNull(ruleRepository.clearRuleSchedule(fixture.visibleVenueId, created.id))
            assertNull(always.startsTime)
            assertNull(always.endsTime)
            assertNull(always.daysOfWeek)
        }

    @Test
    fun `active promotion rules require active parent promotion`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-rules-parent-status")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val ruleRepository = VenuePromotionRuleRepository(dataSource(jdbcUrl))
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Счастливые часы",
                    description = "20% на кальяны",
                    terms = null,
                    createdByUserId = OWNER_ID,
                )
            val rule =
                ruleRepository.createHappyHoursRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    discountPercent = 20,
                    createdByUserId = OWNER_ID,
                )
            assertNotNull(ruleRepository.setRuleStatus(fixture.visibleVenueId, rule.id, VenuePromotionStatus.ACTIVE))

            assertEquals(emptyList(), ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId).map { it.id })

            assertNotNull(
                promotionRepository.applyLifecycleForTest(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    expectedStatus = VenuePromotionStatus.DRAFT,
                    targetStatus = VenuePromotionStatus.PAUSED,
                ),
            )
            assertEquals(emptyList(), ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId).map { it.id })

            assertNotNull(
                promotionRepository.applyLifecycleForTest(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    expectedStatus = VenuePromotionStatus.PAUSED,
                    targetStatus = VenuePromotionStatus.ACTIVE,
                ),
            )
            assertEquals(
                listOf(rule.id),
                ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId).map { it.id },
            )

            assertNotNull(
                promotionRepository.applyLifecycleForTest(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    expectedStatus = VenuePromotionStatus.ACTIVE,
                    targetStatus = VenuePromotionStatus.ARCHIVED,
                ),
            )
            assertEquals(emptyList(), ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId).map { it.id })
            assertEquals(
                VenuePromotionStatus.ARCHIVED,
                ruleRepository.getRuleForManagement(fixture.visibleVenueId, rule.id)?.status,
            )
        }

    @Test
    fun `phase two happy hours item draft persists windows and increments version`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-rules-phase-two-item")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val ruleRepository = VenuePromotionRuleRepository(dataSource(jdbcUrl))
            val categoryId =
                insertMenuCategory(jdbcUrl, fixture.visibleVenueId, "Кальяны", MenuSemanticType.HOOKAH)
            val menuItemId = insertMenuItem(jdbcUrl, fixture.visibleVenueId, categoryId, "Кальян")
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Счастливые часы",
                    description = "Скидка на кальян",
                    terms = "По будням",
                    startsAt = Instant.parse("2030-01-01T00:00:00Z"),
                    endsAt = Instant.parse("2030-12-31T23:59:59Z"),
                    templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                    createdByUserId = OWNER_ID,
                )
            val expectedWindows =
                listOf(
                    PromotionWeekdayWindow(weekday = 1, startsMinute = 12 * 60, endsMinute = 18 * 60),
                    PromotionWeekdayWindow(weekday = 5, startsMinute = 12 * 60, endsMinute = 16 * 60),
                    PromotionWeekdayWindow(weekday = 6, startsMinute = 14 * 60, endsMinute = 17 * 60),
                )

            val created =
                ruleRepository.createHappyHoursDraftRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    target =
                        HappyHoursRuleTargetInput(
                            targetType = PromotionRuleTargetType.MENU_ITEM,
                            menuItemId = menuItemId,
                        ),
                    discountPercent = 40,
                    weekdayWindows = expectedWindows.reversed(),
                    createdByUserId = OWNER_ID,
                )

            assertEquals(VenuePromotionStatus.DRAFT, created.status)
            assertEquals(1, created.version)
            assertEquals(expectedWindows, created.weekdayWindows)
            assertEquals(PromotionRuleTargetType.MENU_ITEM, created.executableTargetType)
            assertEquals(PromotionRuleTargetType.MENU_ITEM, created.targets.single().targetType)
            assertEquals(menuItemId, created.targets.single().menuItemId)
            assertEquals("Кальян", created.targets.single().menuItemName)
            assertEquals(
                created,
                ruleRepository.listRulesForPromotionManagement(fixture.visibleVenueId, promotion.id).single(),
            )

            val readiness =
                ruleRepository.validateHappyHoursActivationReadiness(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                )
            assertTrue(readiness.isReady, readiness.errors.joinToString())
            assertEquals(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE, readiness.venueTimezone)
            assertEquals(created.id, readiness.rule?.id)

            val updatedWindows =
                listOf(
                    PromotionWeekdayWindow(weekday = 2, startsMinute = 13 * 60, endsMinute = 18 * 60),
                    PromotionWeekdayWindow(weekday = 4, startsMinute = 13 * 60, endsMinute = 18 * 60),
                )
            val updated =
                assertNotNull(
                    ruleRepository.updateHappyHoursDraftRule(
                        venueId = fixture.visibleVenueId,
                        promotionId = promotion.id,
                        ruleId = created.id,
                        target =
                            HappyHoursRuleTargetInput(
                                targetType = PromotionRuleTargetType.MENU_ITEM,
                                menuItemId = menuItemId,
                            ),
                        discountPercent = 50,
                        weekdayWindows = updatedWindows,
                    ),
                )

            assertEquals(2, updated.version)
            assertEquals(50, updated.discountPercent)
            assertEquals(updatedWindows, updated.weekdayWindows)
            assertEquals(2, ruleRepository.getRuleForManagement(fixture.visibleVenueId, created.id)?.version)

            val active =
                assertNotNull(
                    promotionRepository.applyLifecycleForTest(
                        venueId = fixture.visibleVenueId,
                        promotionId = promotion.id,
                        expectedStatus = VenuePromotionStatus.DRAFT,
                        targetStatus = VenuePromotionStatus.ACTIVE,
                    ),
                )
            assertEquals(VenuePromotionStatus.ACTIVE, active.status)
            assertEquals(
                VenuePromotionStatus.ACTIVE,
                ruleRepository.getRuleForManagement(fixture.visibleVenueId, created.id)?.status,
            )

            val paused =
                assertNotNull(
                    promotionRepository.applyLifecycleForTest(
                        venueId = fixture.visibleVenueId,
                        promotionId = promotion.id,
                        expectedStatus = VenuePromotionStatus.ACTIVE,
                        targetStatus = VenuePromotionStatus.PAUSED,
                    ),
                )
            assertEquals(VenuePromotionStatus.PAUSED, paused.status)
            assertEquals(
                VenuePromotionStatus.PAUSED,
                ruleRepository.getRuleForManagement(fixture.visibleVenueId, created.id)?.status,
            )
        }

    @Test
    fun `gift draft persists fixed and selectable rewards with safe availability hydration`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-rules-gift-draft")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val ruleRepository = VenuePromotionRuleRepository(dataSource(jdbcUrl))
            val triggerCategoryId =
                insertMenuCategory(jdbcUrl, fixture.visibleVenueId, "Кальяны", MenuSemanticType.HOOKAH)
            val triggerItemId =
                insertMenuItem(jdbcUrl, fixture.visibleVenueId, triggerCategoryId, "Кальян")
            val rewardCategoryId =
                insertMenuCategory(jdbcUrl, fixture.visibleVenueId, "Чай", MenuSemanticType.TEA)
            val teaId =
                insertMenuItem(jdbcUrl, fixture.visibleVenueId, rewardCategoryId, "Чай")
            val lemonadeId =
                insertMenuItem(jdbcUrl, fixture.visibleVenueId, rewardCategoryId, "Лимонад")
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Подарок при покупке",
                    description = "Чай к кальяну",
                    terms = "Один подарок",
                    startsAt = Instant.parse("2030-01-01T00:00:00Z"),
                    endsAt = Instant.parse("2030-12-31T23:59:59Z"),
                    templateType = VenuePromotionTemplateType.GIFT_WITH_ITEM,
                    createdByUserId = OWNER_ID,
                )
            val windows =
                listOf(
                    PromotionWeekdayWindow(weekday = 1, startsMinute = 12 * 60, endsMinute = 18 * 60),
                    PromotionWeekdayWindow(weekday = 5, startsMinute = 12 * 60, endsMinute = 18 * 60),
                )

            val created =
                ruleRepository.createGiftWithItemDraftRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    target =
                        HappyHoursRuleTargetInput(
                            targetType = PromotionRuleTargetType.MENU_ITEM,
                            menuItemId = triggerItemId,
                        ),
                    reward =
                        GiftWithItemRewardInput(
                            mode = PromotionRewardMode.FIXED_ITEM,
                            fixedMenuItemId = teaId,
                        ),
                    weekdayWindows = windows,
                    createdByUserId = OWNER_ID,
                )

            assertEquals(VenuePromotionStatus.DRAFT, created.status)
            assertEquals(PromotionRuleTargetType.MENU_ITEM, created.executableTargetType)
            assertEquals(triggerItemId, created.targets.single().menuItemId)
            assertEquals(windows, created.weekdayWindows)
            assertEquals(PromotionRewardMode.FIXED_ITEM, created.reward?.rewardMode)
            assertEquals(teaId, created.reward?.rewardMenuItemId)
            assertEquals(1, created.reward?.rewardQty)
            assertEquals(1, created.reward?.maxRewardsPerBatch)
            assertTrue(created.reward?.isAvailable == true)
            assertFalse(created.reward?.requiresOptionSelection == true)

            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO menu_item_options (
                        venue_id, item_id, name, price_delta_minor, is_available, sort_order
                    )
                    VALUES (?, ?, 'Обязательный выбор', 0, TRUE, 0)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, fixture.visibleVenueId)
                    statement.setLong(2, teaId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "UPDATE menu_categories SET is_active = FALSE WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, rewardCategoryId)
                    statement.executeUpdate()
                }
            }
            val unavailableFixed =
                assertNotNull(ruleRepository.getRuleForManagement(fixture.visibleVenueId, created.id))
            assertFalse(unavailableFixed.reward?.isAvailable == true)
            assertTrue(unavailableFixed.reward?.requiresOptionSelection == true)

            val updated =
                assertNotNull(
                    ruleRepository.updateGiftWithItemDraftRule(
                        venueId = fixture.visibleVenueId,
                        promotionId = promotion.id,
                        ruleId = created.id,
                        target =
                            HappyHoursRuleTargetInput(
                                targetType = PromotionRuleTargetType.MENU_CATEGORY,
                                menuCategoryId = triggerCategoryId,
                            ),
                        reward =
                            GiftWithItemRewardInput(
                                mode = PromotionRewardMode.CHOICE_ITEMS,
                                allowlistMenuItemIds = listOf(teaId, lemonadeId),
                            ),
                        weekdayWindows = windows,
                    ),
                )

            assertEquals(2, updated.version)
            assertEquals(PromotionRuleTargetType.MENU_CATEGORY, updated.executableTargetType)
            assertEquals(triggerCategoryId, updated.targets.single().menuCategoryId)
            assertEquals(PromotionRewardMode.CHOICE_ITEMS, updated.reward?.rewardMode)
            assertEquals(listOf(teaId, lemonadeId), updated.reward?.options?.map { it.menuItemId })
            assertTrue(updated.reward?.options?.all { !it.isAvailable } == true)
            assertEquals(
                listOf(true, false),
                updated.reward?.options?.map { it.requiresOptionSelection },
            )
            val readiness =
                ruleRepository.validateGiftWithItemActivationReadiness(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                )
            assertTrue(readiness.isReady, readiness.errors.joinToString())

            val active =
                assertNotNull(
                    promotionRepository.applyLifecycleForTest(
                        venueId = fixture.visibleVenueId,
                        promotionId = promotion.id,
                        expectedStatus = VenuePromotionStatus.DRAFT,
                        targetStatus = VenuePromotionStatus.ACTIVE,
                    ),
                )
            assertEquals(VenuePromotionStatus.ACTIVE, active.status)
            assertEquals(
                VenuePromotionStatus.ACTIVE,
                ruleRepository.getRuleForManagement(fixture.visibleVenueId, created.id)?.status,
            )
        }

    @Test
    fun `legacy single and multi rule happy hours can pause and reactivate without conversion`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-phase-two-legacy-reactivation")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val ruleRepository = VenuePromotionRuleRepository(dataSource(jdbcUrl))
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Legacy Happy Hours",
                    description = "Старые правила Telegram",
                    terms = null,
                    startsAt = Instant.parse("2030-01-01T00:00:00Z"),
                    endsAt = Instant.parse("2030-12-31T23:59:59Z"),
                    templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                    createdByUserId = OWNER_ID,
                )
            val legacyRules =
                listOf(
                    ruleRepository.createHappyHoursRule(
                        venueId = fixture.visibleVenueId,
                        promotionId = promotion.id,
                        targetValue = MenuSemanticType.HOOKAH,
                        discountPercent = 20,
                        createdByUserId = OWNER_ID,
                        startsTime = LocalTime.of(12, 0),
                        endsTime = LocalTime.of(18, 0),
                        daysOfWeek = setOf(1, 2, 3, 4),
                    ),
                    ruleRepository.createHappyHoursRule(
                        venueId = fixture.visibleVenueId,
                        promotionId = promotion.id,
                        targetValue = MenuSemanticType.TEA,
                        discountPercent = 10,
                        createdByUserId = OWNER_ID,
                        startsTime = LocalTime.of(14, 0),
                        endsTime = LocalTime.of(17, 0),
                        daysOfWeek = setOf(5, 6, 7),
                    ),
                )

            val readiness =
                ruleRepository.validateHappyHoursActivationReadiness(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                )
            assertTrue(readiness.isReady, readiness.errors.joinToString())
            assertEquals(2, readiness.ruleCount)
            assertEquals(legacyRules.first().id, readiness.rule?.id)

            suspend fun setPromotionAndRuleStatus(
                expectedStatus: VenuePromotionStatus,
                targetStatus: VenuePromotionStatus,
            ) {
                assertNotNull(
                    promotionRepository.applyLifecycleForTest(
                        venueId = fixture.visibleVenueId,
                        promotionId = promotion.id,
                        expectedStatus = expectedStatus,
                        targetStatus = targetStatus,
                    ),
                )
            }

            setPromotionAndRuleStatus(VenuePromotionStatus.DRAFT, VenuePromotionStatus.ACTIVE)
            assertEquals(
                setOf(VenuePromotionStatus.ACTIVE),
                legacyRules
                    .map { ruleRepository.getRuleForManagement(fixture.visibleVenueId, it.id)?.status }
                    .toSet(),
            )

            setPromotionAndRuleStatus(VenuePromotionStatus.ACTIVE, VenuePromotionStatus.PAUSED)
            assertEquals(
                setOf(VenuePromotionStatus.PAUSED),
                legacyRules
                    .map { ruleRepository.getRuleForManagement(fixture.visibleVenueId, it.id)?.status }
                    .toSet(),
            )

            setPromotionAndRuleStatus(VenuePromotionStatus.PAUSED, VenuePromotionStatus.ACTIVE)
            assertEquals(
                setOf(VenuePromotionStatus.ACTIVE),
                legacyRules
                    .map { ruleRepository.getRuleForManagement(fixture.visibleVenueId, it.id)?.status }
                    .toSet(),
            )
        }

    @Test
    fun `phase two parent update callback and lifecycle audit failure roll back atomically`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-phase-two-atomic-rollback")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val ruleRepository = VenuePromotionRuleRepository(dataSource(jdbcUrl))
            val categoryId =
                insertMenuCategory(jdbcUrl, fixture.visibleVenueId, "Кальяны", MenuSemanticType.HOOKAH)
            val menuItemId = insertMenuItem(jdbcUrl, fixture.visibleVenueId, categoryId, "Кальян")
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Исходная акция",
                    description = "Исходное описание",
                    terms = null,
                    startsAt = Instant.parse("2030-01-01T00:00:00Z"),
                    endsAt = Instant.parse("2030-12-31T23:59:59Z"),
                    templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                    createdByUserId = OWNER_ID,
                )
            val originalWindows =
                listOf(
                    PromotionWeekdayWindow(weekday = 1, startsMinute = 12 * 60, endsMinute = 18 * 60),
                )
            val rule =
                ruleRepository.createHappyHoursDraftRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    target =
                        HappyHoursRuleTargetInput(
                            targetType = PromotionRuleTargetType.MENU_ITEM,
                            menuItemId = menuItemId,
                        ),
                    discountPercent = 50,
                    weekdayWindows = originalWindows,
                    createdByUserId = OWNER_ID,
                )

            assertFailsWith<IllegalStateException> {
                promotionRepository.updatePromotion(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    title = "Не должна сохраниться",
                    afterUpdate = { connection, _ ->
                        assertNotNull(
                            ruleRepository.updateHappyHoursDraftRule(
                                connection = connection,
                                venueId = fixture.visibleVenueId,
                                promotionId = promotion.id,
                                ruleId = rule.id,
                                target =
                                    HappyHoursRuleTargetInput(
                                        targetType = PromotionRuleTargetType.MENU_ITEM,
                                        menuItemId = menuItemId,
                                    ),
                                discountPercent = 25,
                                weekdayWindows =
                                    listOf(
                                        PromotionWeekdayWindow(
                                            weekday = 6,
                                            startsMinute = 14 * 60,
                                            endsMinute = 17 * 60,
                                        ),
                                    ),
                            ),
                        )
                        error("force update rollback")
                    },
                )
            }
            assertEquals(
                "Исходная акция",
                promotionRepository.getPromotionForManagement(fixture.visibleVenueId, promotion.id)?.title,
            )
            val ruleAfterUpdateRollback =
                assertNotNull(ruleRepository.getRuleForManagement(fixture.visibleVenueId, rule.id))
            assertEquals(1, ruleAfterUpdateRollback.version)
            assertEquals(50, ruleAfterUpdateRollback.discountPercent)
            assertEquals(originalWindows, ruleAfterUpdateRollback.weekdayWindows)

            val failingLifecycleRepository =
                VenuePromotionRepository(
                    dataSource = dataSource(jdbcUrl),
                    ruleRepository = ruleRepository,
                    auditLogWriter = failingAuditLogWriter(),
                )
            assertFailsWith<DatabaseUnavailableException> {
                failingLifecycleRepository.mutatePromotionLifecycle(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    expectedStatus = VenuePromotionStatus.DRAFT,
                    targetStatus = VenuePromotionStatus.ACTIVE,
                    actorUserId = OWNER_ID,
                    source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                )
            }
            assertEquals(
                VenuePromotionStatus.DRAFT,
                promotionRepository.getPromotionForManagement(fixture.visibleVenueId, promotion.id)?.status,
            )
            assertEquals(
                VenuePromotionStatus.DRAFT,
                ruleRepository.getRuleForManagement(fixture.visibleVenueId, rule.id)?.status,
            )
            assertTrue(readPromotionLifecycleAudits(jdbcUrl, promotion.id).isEmpty())
        }

    @Test
    fun `phase two happy hours category draft rejects overlapping and foreign targets`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-rules-phase-two-category")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val ruleRepository = VenuePromotionRuleRepository(dataSource(jdbcUrl))
            val categoryId =
                insertMenuCategory(jdbcUrl, fixture.visibleVenueId, "Кальяны", MenuSemanticType.HOOKAH)
            val foreignCategoryId =
                insertMenuCategory(jdbcUrl, fixture.otherVenueId, "Чужие кальяны", MenuSemanticType.HOOKAH)
            val foreignMenuItemId =
                insertMenuItem(jdbcUrl, fixture.otherVenueId, foreignCategoryId, "Чужой кальян")
            val categoryPromotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Скидка на категорию",
                    description = "Скидка на все кальяны",
                    terms = null,
                    startsAt = Instant.parse("2030-01-01T00:00:00Z"),
                    endsAt = Instant.parse("2030-12-31T23:59:59Z"),
                    templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                    createdByUserId = OWNER_ID,
                )
            val categoryRule =
                ruleRepository.createHappyHoursDraftRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = categoryPromotion.id,
                    target =
                        HappyHoursRuleTargetInput(
                            targetType = PromotionRuleTargetType.MENU_CATEGORY,
                            menuCategoryId = categoryId,
                        ),
                    discountPercent = 25,
                    weekdayWindows =
                        listOf(
                            PromotionWeekdayWindow(weekday = 1, startsMinute = 12 * 60, endsMinute = 18 * 60),
                        ),
                    createdByUserId = OWNER_ID,
                )

            assertEquals(PromotionRuleTargetType.MENU_CATEGORY, categoryRule.executableTargetType)
            assertEquals(PromotionRuleTargetType.MENU_CATEGORY, categoryRule.targets.single().targetType)
            assertEquals(categoryId, categoryRule.targets.single().menuCategoryId)
            assertEquals("Кальяны", categoryRule.targets.single().menuCategoryName)
            assertEquals(
                categoryId,
                ruleRepository.listRuleTargets(categoryRule.id).single().menuCategoryId,
            )
            val invalidPromotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Некорректная акция",
                    description = "Проверка валидации",
                    terms = null,
                    startsAt = Instant.parse("2030-01-01T00:00:00Z"),
                    endsAt = Instant.parse("2030-12-31T23:59:59Z"),
                    templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                    createdByUserId = OWNER_ID,
                )
            assertFailsWith<IllegalArgumentException> {
                ruleRepository.createHappyHoursDraftRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = invalidPromotion.id,
                    target =
                        HappyHoursRuleTargetInput(
                            targetType = PromotionRuleTargetType.MENU_CATEGORY,
                            menuCategoryId = categoryId,
                        ),
                    discountPercent = 20,
                    weekdayWindows =
                        listOf(
                            PromotionWeekdayWindow(weekday = 3, startsMinute = 12 * 60, endsMinute = 16 * 60),
                            PromotionWeekdayWindow(weekday = 3, startsMinute = 15 * 60, endsMinute = 18 * 60),
                        ),
                    createdByUserId = OWNER_ID,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                ruleRepository.createHappyHoursDraftRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = invalidPromotion.id,
                    target =
                        HappyHoursRuleTargetInput(
                            targetType = PromotionRuleTargetType.MENU_CATEGORY,
                            menuCategoryId = foreignCategoryId,
                        ),
                    discountPercent = 20,
                    weekdayWindows =
                        listOf(
                            PromotionWeekdayWindow(weekday = 3, startsMinute = 12 * 60, endsMinute = 16 * 60),
                        ),
                    createdByUserId = OWNER_ID,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                ruleRepository.createHappyHoursDraftRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = invalidPromotion.id,
                    target =
                        HappyHoursRuleTargetInput(
                            targetType = PromotionRuleTargetType.MENU_ITEM,
                            menuItemId = foreignMenuItemId,
                        ),
                    discountPercent = 20,
                    weekdayWindows =
                        listOf(
                            PromotionWeekdayWindow(weekday = 3, startsMinute = 12 * 60, endsMinute = 16 * 60),
                        ),
                    createdByUserId = OWNER_ID,
                )
            }
            assertTrue(
                ruleRepository.listRulesForPromotionManagement(fixture.visibleVenueId, invalidPromotion.id).isEmpty(),
            )
            assertTrue(
                VenueMenuRepository(dataSource(jdbcUrl))
                    .deleteCategory(fixture.visibleVenueId, categoryId),
            )
            val categoryRuleAfterCascade =
                assertNotNull(ruleRepository.getRuleForManagement(fixture.visibleVenueId, categoryRule.id))
            assertEquals(categoryRule.version + 1, categoryRuleAfterCascade.version)
            assertTrue(categoryRuleAfterCascade.targets.isEmpty())
        }

    @Test
    fun `phase two activation readiness rejects incomplete parent timezone windows and target`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-rules-phase-two-readiness")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val ruleRepository = VenuePromotionRuleRepository(dataSource(jdbcUrl))
            val categoryId =
                insertMenuCategory(jdbcUrl, fixture.visibleVenueId, "Кальяны", MenuSemanticType.HOOKAH)
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Неполная акция",
                    description = "Нельзя активировать",
                    terms = null,
                    templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                    createdByUserId = OWNER_ID,
                )
            val rule =
                ruleRepository.createHappyHoursDraftRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    target =
                        HappyHoursRuleTargetInput(
                            targetType = PromotionRuleTargetType.MENU_CATEGORY,
                            menuCategoryId = categoryId,
                        ),
                    discountPercent = 30,
                    weekdayWindows =
                        listOf(
                            PromotionWeekdayWindow(weekday = 7, startsMinute = 14 * 60, endsMinute = 17 * 60),
                        ),
                    createdByUserId = OWNER_ID,
                )
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.prepareStatement(
                    """
                    MERGE INTO venue_settings (venue_id, timezone)
                    KEY (venue_id)
                    VALUES (?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, fixture.visibleVenueId)
                    statement.setString(2, "Invalid/Timezone")
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "DELETE FROM promotion_rule_weekday_windows WHERE rule_id = ?",
                ).use { statement ->
                    statement.setLong(1, rule.id)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "DELETE FROM promotion_rule_menu_category_targets WHERE rule_id = ?",
                ).use { statement ->
                    statement.setLong(1, rule.id)
                    statement.executeUpdate()
                }
            }

            val readiness =
                ruleRepository.validateHappyHoursActivationReadiness(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                )

            assertFalse(readiness.isReady)
            assertTrue(readiness.errors.any { it.contains("общий период") })
            assertTrue(readiness.errors.any { it.contains("Часовой пояс") })
            assertTrue(readiness.errors.any { it.contains("временное окно") })
            assertTrue(readiness.errors.any { it.contains("ровно одна категория или позиция") })
            assertTrue(readiness.rule?.targets?.isEmpty() == true)
        }

    @Test
    fun `phase two migration backfills legacy happy hours and keeps applications readable`() =
        runBlocking {
            val jdbcUrl =
                "jdbc:h2:mem:promotion-phase-two-backfill-${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
            migrateJdbcUrl(jdbcUrl, "119")
            val fixture = seedFixture(jdbcUrl)
            val categoryId =
                insertMenuCategory(jdbcUrl, fixture.visibleVenueId, "Кальяны", MenuSemanticType.HOOKAH)
            val menuItemId = insertMenuItem(jdbcUrl, fixture.visibleVenueId, categoryId, "Кальян")
            val secondMenuItemId =
                insertMenuItem(jdbcUrl, fixture.visibleVenueId, categoryId, "Премиум кальян")
            val legacy =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_name = 'promotion_rules'
                          AND column_name = 'version'
                        """.trimIndent(),
                    ).use { statement ->
                        statement.executeQuery().use { rs ->
                            assertTrue(rs.next())
                            assertEquals(0, rs.getInt(1))
                        }
                    }
                    val promotionId =
                        connection.insertGeneratedId(
                            """
                            INSERT INTO venue_promotions (
                                venue_id,
                                title,
                                description,
                                starts_at,
                                ends_at,
                                status,
                                template_type,
                                created_by_user_id
                            )
                            VALUES (?, 'Legacy Happy Hours', 'Legacy rule', ?, ?, 'ACTIVE', 'HAPPY_HOURS_PERCENT', ?)
                            """.trimIndent(),
                        ) { statement ->
                            statement.setLong(1, fixture.visibleVenueId)
                            statement.setTimestamp(2, Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")))
                            statement.setTimestamp(3, Timestamp.from(Instant.parse("2100-01-01T00:00:00Z")))
                            statement.setLong(4, OWNER_ID)
                        }
                    val ruleId =
                        connection.insertGeneratedId(
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
                            VALUES (?, ?, 'HAPPY_HOURS_PERCENT', 'CATEGORY_TYPE', 'HOOKAH', 50, ?, ?, '1,3,5', 'ACTIVE', 100, ?)
                            """.trimIndent(),
                        ) { statement ->
                            statement.setLong(1, promotionId)
                            statement.setLong(2, fixture.visibleVenueId)
                            statement.setTime(3, java.sql.Time.valueOf(LocalTime.of(12, 0)))
                            statement.setTime(4, java.sql.Time.valueOf(LocalTime.of(18, 0)))
                            statement.setLong(5, OWNER_ID)
                        }
                    connection.prepareStatement(
                        """
                        INSERT INTO promotion_rule_targets (rule_id, target_type, semantic_type, menu_item_id)
                        VALUES (?, 'MENU_ITEM', NULL, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, ruleId)
                        statement.setLong(2, menuItemId)
                        statement.executeUpdate()
                        statement.setLong(2, secondMenuItemId)
                        statement.executeUpdate()
                    }
                    val fallbackDaysRuleId =
                        connection.insertGeneratedId(
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
                            VALUES (
                                ?, ?, 'HAPPY_HOURS_PERCENT', 'CATEGORY_TYPE', 'TEA', 10,
                                ?, ?, 'bad,value', 'DRAFT', 100, ?
                            )
                            """.trimIndent(),
                        ) { statement ->
                            statement.setLong(1, promotionId)
                            statement.setLong(2, fixture.visibleVenueId)
                            statement.setTime(3, java.sql.Time.valueOf(LocalTime.of(10, 0)))
                            statement.setTime(4, java.sql.Time.valueOf(LocalTime.of(11, 0)))
                            statement.setLong(5, OWNER_ID)
                        }
                    connection.prepareStatement(
                        """
                        INSERT INTO promotion_rule_targets (rule_id, target_type, semantic_type, menu_item_id)
                        VALUES (?, 'CATEGORY_TYPE', 'TEA', NULL)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, fallbackDaysRuleId)
                        statement.executeUpdate()
                    }
                    val tableId =
                        connection.insertGeneratedId(
                            """
                            INSERT INTO venue_tables (venue_id, table_number)
                            VALUES (?, 1)
                            """.trimIndent(),
                        ) { statement ->
                            statement.setLong(1, fixture.visibleVenueId)
                        }
                    val tableSessionId =
                        connection.insertGeneratedId(
                            """
                            INSERT INTO table_sessions (
                                venue_id,
                                table_id,
                                started_at,
                                last_activity_at,
                                expires_at,
                                ended_at,
                                status
                            )
                            VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'ENDED')
                            """.trimIndent(),
                        ) { statement ->
                            statement.setLong(1, fixture.visibleVenueId)
                            statement.setLong(2, tableId)
                        }
                    val orderId =
                        connection.insertGeneratedId(
                            """
                            INSERT INTO orders (venue_id, table_id, table_session_id, status)
                            VALUES (?, ?, ?, 'CLOSED')
                            """.trimIndent(),
                        ) { statement ->
                            statement.setLong(1, fixture.visibleVenueId)
                            statement.setLong(2, tableId)
                            statement.setLong(3, tableSessionId)
                        }
                    val batchId =
                        connection.insertGeneratedId(
                            """
                            INSERT INTO order_batches (order_id, author_user_id, source, status)
                            VALUES (?, ?, 'MINIAPP', 'DELIVERED')
                            """.trimIndent(),
                        ) { statement ->
                            statement.setLong(1, orderId)
                            statement.setLong(2, OWNER_ID)
                        }
                    val batchItemId =
                        connection.insertGeneratedId(
                            """
                            INSERT INTO order_batch_items (order_batch_id, menu_item_id, qty)
                            VALUES (?, ?, 2)
                            """.trimIndent(),
                        ) { statement ->
                            statement.setLong(1, batchId)
                            statement.setLong(2, menuItemId)
                        }
                    connection.prepareStatement(
                        """
                        INSERT INTO order_batch_item_options (
                            order_batch_item_id,
                            menu_item_option_id,
                            option_name_snapshot,
                            price_delta_minor_snapshot
                        )
                        VALUES (?, NULL, 'Legacy premium option', 25000)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, batchItemId)
                        statement.executeUpdate()
                    }
                    val applicationId =
                        connection.insertGeneratedId(
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
                            VALUES (?, ?, ?, ?, ?, ?, 'Legacy Happy Hours', 'HAPPY_HOURS_PERCENT',
                                'CATEGORY_TYPE', 'HOOKAH', 50, 125000, 'RUB', ?)
                            """.trimIndent(),
                        ) { statement ->
                            statement.setLong(1, orderId)
                            statement.setLong(2, batchId)
                            statement.setLong(3, fixture.visibleVenueId)
                            statement.setLong(4, OWNER_ID)
                            statement.setLong(5, promotionId)
                            statement.setLong(6, ruleId)
                            statement.setString(7, "legacy:$orderId:$ruleId")
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
                        VALUES (?, ?, ?, 125000, 50, 125000, 2, 'RUB')
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, applicationId)
                        statement.setLong(2, batchItemId)
                        statement.setLong(3, menuItemId)
                        statement.executeUpdate()
                    }
                    LegacyPromotionFixture(
                        promotionId = promotionId,
                        ruleId = ruleId,
                        fallbackDaysRuleId = fallbackDaysRuleId,
                        orderId = orderId,
                        batchItemId = batchItemId,
                    )
                }

            migrateJdbcUrl(jdbcUrl, "120")

            val ruleRepository = VenuePromotionRuleRepository(dataSource(jdbcUrl))
            val migratedRule =
                assertNotNull(ruleRepository.getRuleForManagement(fixture.visibleVenueId, legacy.ruleId))
            assertEquals(1, migratedRule.version)
            assertEquals(legacy.promotionId, migratedRule.promotionId)
            assertEquals(VenuePromotionStatus.ACTIVE, migratedRule.status)
            assertEquals(
                listOf(
                    PromotionWeekdayWindow(weekday = 1, startsMinute = 12 * 60, endsMinute = 18 * 60),
                    PromotionWeekdayWindow(weekday = 3, startsMinute = 12 * 60, endsMinute = 18 * 60),
                    PromotionWeekdayWindow(weekday = 5, startsMinute = 12 * 60, endsMinute = 18 * 60),
                ),
                migratedRule.weekdayWindows,
            )
            assertEquals(null, migratedRule.executableTargetType)
            assertEquals(
                setOf(menuItemId, secondMenuItemId),
                migratedRule.targets.mapNotNull { it.menuItemId }.toSet(),
            )
            assertEquals(
                legacy.ruleId,
                ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId).single().id,
            )
            assertEquals(
                (1..7).toList(),
                assertNotNull(
                    ruleRepository.getRuleForManagement(fixture.visibleVenueId, legacy.fallbackDaysRuleId),
                ).weekdayWindows.map { it.weekday },
            )
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.prepareStatement(
                    """
                    SELECT item_name_snapshot, base_unit_price_minor_snapshot, currency_snapshot
                    FROM order_batch_items
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, legacy.batchItemId)
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals("Кальян", rs.getString("item_name_snapshot"))
                        assertEquals(100_000L, rs.getLong("base_unit_price_minor_snapshot"))
                        assertEquals("RUB", rs.getString("currency_snapshot"))
                    }
                }
            }

            val adjustment =
                PromotionApplicationRepository(dataSource(jdbcUrl))
                    .findAdjustmentsByOrder(legacy.orderId)
                    .single()
            assertEquals(1, adjustment.ruleVersion)
            assertNull(adjustment.scheduleSnapshotJson)
            assertNull(adjustment.targetSnapshotJson)
            assertNull(adjustment.venueTimezoneSnapshot)
            assertNull(adjustment.itemNameSnapshot)
            assertEquals(100_000L, adjustment.baseUnitPriceMinor)
            assertEquals(25_000L, adjustment.selectedOptionDeltaMinor)
            assertEquals(250_000L, adjustment.originalAmountMinor)
            assertEquals(125_000L, adjustment.finalAmountMinor)
            assertNotNull(adjustment.appliedAt)
            Unit
        }

    @Test
    fun `promotion rule repository replaces category target with menu item targets`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-rules-menu-item-targets")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val ruleRepository = VenuePromotionRuleRepository(dataSource(jdbcUrl))
            val categoryId = insertMenuCategory(jdbcUrl, fixture.visibleVenueId, "Кальяны", MenuSemanticType.HOOKAH)
            val ordinaryHookah = insertMenuItem(jdbcUrl, fixture.visibleVenueId, categoryId, "Кальян обычный")
            val premiumHookah = insertMenuItem(jdbcUrl, fixture.visibleVenueId, categoryId, "Премиум кальян")
            val otherCategoryId = insertMenuCategory(jdbcUrl, fixture.otherVenueId, "Кальяны", MenuSemanticType.HOOKAH)
            val crossVenueItem = insertMenuItem(jdbcUrl, fixture.otherVenueId, otherCategoryId, "Чужой кальян")
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Счастливые часы",
                    description = "20% на кальяны",
                    terms = null,
                    createdByUserId = OWNER_ID,
                )
            val rule =
                ruleRepository.createHappyHoursRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    discountPercent = 20,
                    createdByUserId = OWNER_ID,
                )

            val selectionItems =
                ruleRepository.listMenuItemsForTargetSelection(
                    fixture.visibleVenueId,
                    MenuSemanticType.HOOKAH,
                )
            assertEquals(listOf(ordinaryHookah, premiumHookah), selectionItems.map { it.id })

            val updated =
                assertNotNull(
                    ruleRepository.replaceRuleTargetsWithMenuItems(
                        venueId = fixture.visibleVenueId,
                        ruleId = rule.id,
                        menuItemIds = listOf(ordinaryHookah, premiumHookah),
                    ),
                )

            assertEquals(
                listOf(ordinaryHookah, premiumHookah),
                updated.targets.map { it.menuItemId },
            )
            assertTrue(updated.targets.all { it.targetType == PromotionRuleTargetType.MENU_ITEM })
            assertTrue(
                VenueMenuRepository(dataSource(jdbcUrl))
                    .deleteItem(fixture.visibleVenueId, ordinaryHookah),
            )
            val targetsAfterCascade =
                assertNotNull(ruleRepository.getRuleForManagement(fixture.visibleVenueId, rule.id))
            assertEquals(updated.version + 1, targetsAfterCascade.version)
            assertEquals(
                listOf(premiumHookah),
                targetsAfterCascade.targets.map { it.menuItemId },
            )

            assertFailsWith<IllegalArgumentException> {
                ruleRepository.replaceRuleTargetsWithMenuItems(
                    venueId = fixture.visibleVenueId,
                    ruleId = rule.id,
                    menuItemIds = listOf(crossVenueItem),
                )
            }
            Unit
        }

    @Test
    fun `promotion rule repository creates gift with item rule and loads reward config`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-rules-gift")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val ruleRepository = VenuePromotionRuleRepository(dataSource(jdbcUrl))
            val triggerCategoryId =
                insertMenuCategory(jdbcUrl, fixture.visibleVenueId, "Кальяны", MenuSemanticType.HOOKAH)
            insertMenuItem(jdbcUrl, fixture.visibleVenueId, triggerCategoryId, "Кальян обычный")
            val rewardCategoryId = insertMenuCategory(jdbcUrl, fixture.visibleVenueId, "Чай", MenuSemanticType.TEA)
            val rewardItemId = insertMenuItem(jdbcUrl, fixture.visibleVenueId, rewardCategoryId, "Чай")
            val updatedRewardItemId = insertMenuItem(jdbcUrl, fixture.visibleVenueId, rewardCategoryId, "Морс")
            val otherCategoryId = insertMenuCategory(jdbcUrl, fixture.otherVenueId, "Чай", MenuSemanticType.TEA)
            val crossVenueRewardId = insertMenuItem(jdbcUrl, fixture.otherVenueId, otherCategoryId, "Чужой чай")
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Чай к кальяну",
                    description = "Подарок к кальяну",
                    terms = null,
                    templateType = VenuePromotionTemplateType.GIFT_WITH_ITEM,
                    createdByUserId = OWNER_ID,
                )

            val created =
                ruleRepository.createGiftWithItemRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    rewardMenuItemId = rewardItemId,
                    createdByUserId = OWNER_ID,
                )

            assertEquals(PromotionRuleType.GIFT_WITH_ITEM, created.ruleType)
            assertEquals(MenuSemanticType.HOOKAH, created.targetValue)
            assertEquals(0, created.discountPercent)
            assertEquals(rewardItemId, created.reward?.rewardMenuItemId)
            assertEquals("Чай", created.reward?.rewardMenuItemName)
            assertEquals(PromotionRewardMode.FIXED_ITEM, created.reward?.rewardMode)
            assertEquals(1, created.reward?.rewardQty)
            assertEquals(PromotionRuleTargetType.CATEGORY_TYPE, created.targets.single().targetType)
            assertEquals(1, created.version)
            assertEquals(emptyList(), ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId))

            val activation =
                assertNotNull(
                    promotionRepository.mutatePromotionLifecycle(
                        venueId = fixture.visibleVenueId,
                        promotionId = promotion.id,
                        expectedStatus = VenuePromotionStatus.DRAFT,
                        targetStatus = VenuePromotionStatus.ACTIVE,
                        actorUserId = OWNER_ID,
                        source = VenuePromotionLifecycleSource.TELEGRAM_BOT,
                    ),
                )
            assertEquals(VenuePromotionLifecycleOutcome.APPLIED, activation.outcome)
            val active = ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId).single()
            assertEquals(PromotionRuleType.GIFT_WITH_ITEM, active.ruleType)
            assertEquals(rewardItemId, active.reward?.rewardMenuItemId)
            val activationAudit = readPromotionLifecycleAudits(jdbcUrl, promotion.id).single()
            assertPromotionAuditPayload(
                payload = activationAudit.payload,
                venueId = fixture.visibleVenueId,
                promotionId = promotion.id,
                templateType = VenuePromotionTemplateType.GIFT_WITH_ITEM,
                oldStatus = VenuePromotionStatus.DRAFT,
                newStatus = VenuePromotionStatus.ACTIVE,
                source = VenuePromotionLifecycleSource.TELEGRAM_BOT,
                expectedRules =
                    listOf(
                        ExpectedAuditRule(
                            ruleId = created.id,
                            version = created.version,
                            oldStatus = VenuePromotionStatus.ACTIVE,
                            newStatus = VenuePromotionStatus.ACTIVE,
                        ),
                    ),
            )

            val pause =
                assertNotNull(
                    promotionRepository.mutatePromotionLifecycle(
                        venueId = fixture.visibleVenueId,
                        promotionId = promotion.id,
                        expectedStatus = VenuePromotionStatus.ACTIVE,
                        targetStatus = VenuePromotionStatus.PAUSED,
                        actorUserId = OWNER_ID,
                        source = VenuePromotionLifecycleSource.TELEGRAM_BOT,
                    ),
                )
            assertEquals(VenuePromotionLifecycleOutcome.APPLIED, pause.outcome)
            assertEquals(
                VenuePromotionStatus.ACTIVE,
                ruleRepository.getRuleForManagement(fixture.visibleVenueId, created.id)?.status,
            )
            assertPromotionAuditPayload(
                payload = readPromotionLifecycleAudits(jdbcUrl, promotion.id).last().payload,
                venueId = fixture.visibleVenueId,
                promotionId = promotion.id,
                templateType = VenuePromotionTemplateType.GIFT_WITH_ITEM,
                oldStatus = VenuePromotionStatus.ACTIVE,
                newStatus = VenuePromotionStatus.PAUSED,
                source = VenuePromotionLifecycleSource.TELEGRAM_BOT,
                expectedRules =
                    listOf(
                        ExpectedAuditRule(
                            ruleId = created.id,
                            version = created.version,
                            oldStatus = VenuePromotionStatus.ACTIVE,
                            newStatus = VenuePromotionStatus.ACTIVE,
                        ),
                    ),
            )
            assertNotNull(
                promotionRepository.mutatePromotionLifecycle(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    expectedStatus = VenuePromotionStatus.PAUSED,
                    targetStatus = VenuePromotionStatus.ACTIVE,
                    actorUserId = OWNER_ID,
                    source = VenuePromotionLifecycleSource.TELEGRAM_BOT,
                ),
            )
            assertEquals(3, readPromotionLifecycleAudits(jdbcUrl, promotion.id).size)

            val updatedReward =
                assertNotNull(
                    ruleRepository.updateGiftWithItemReward(
                        venueId = fixture.visibleVenueId,
                        ruleId = created.id,
                        rewardMenuItemId = updatedRewardItemId,
                    ),
                )
            assertEquals(updatedRewardItemId, updatedReward.reward?.rewardMenuItemId)
            assertEquals("Морс", updatedReward.reward?.rewardMenuItemName)
            assertEquals(PromotionRewardMode.FIXED_ITEM, updatedReward.reward?.rewardMode)
            assertEquals(2, updatedReward.version)

            val choiceReward =
                assertNotNull(
                    ruleRepository.updateGiftWithItemRewardOptions(
                        venueId = fixture.visibleVenueId,
                        ruleId = created.id,
                        rewardMenuItemIds = listOf(rewardItemId, updatedRewardItemId),
                    ),
                )
            assertEquals(PromotionRewardMode.CHOICE_ITEMS, choiceReward.reward?.rewardMode)
            assertEquals(
                setOf(rewardItemId, updatedRewardItemId),
                choiceReward.reward?.options?.map { it.menuItemId }?.toSet(),
            )
            assertEquals(3, choiceReward.version)
            assertTrue(
                VenueMenuRepository(dataSource(jdbcUrl))
                    .deleteItem(fixture.visibleVenueId, updatedRewardItemId),
            )
            val rewardAfterCascade =
                assertNotNull(ruleRepository.getRuleForManagement(fixture.visibleVenueId, created.id))
            assertEquals(4, rewardAfterCascade.version)
            assertEquals(
                setOf(rewardItemId),
                rewardAfterCascade.reward?.options?.map { it.menuItemId }?.toSet(),
            )
            assertFailsWith<DatabaseUnavailableException> {
                VenueMenuRepository(dataSource(jdbcUrl))
                    .deleteItem(fixture.visibleVenueId, rewardItemId)
            }
            assertEquals(
                4,
                ruleRepository.getRuleForManagement(fixture.visibleVenueId, created.id)?.version,
            )

            assertFailsWith<IllegalArgumentException> {
                ruleRepository.createGiftWithItemRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    rewardMenuItemId = crossVenueRewardId,
                    createdByUserId = OWNER_ID,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                ruleRepository.updateGiftWithItemRewardOptions(
                    venueId = fixture.visibleVenueId,
                    ruleId = created.id,
                    rewardMenuItemIds = listOf(rewardItemId),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                ruleRepository.updateGiftWithItemRewardOptions(
                    venueId = fixture.visibleVenueId,
                    ruleId = created.id,
                    rewardMenuItemIds = listOf(rewardItemId, crossVenueRewardId),
                )
            }
            Unit
        }

    @Test
    fun `promotion rule repository validates percent and time window`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-rules-validation")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val ruleRepository = VenuePromotionRuleRepository(dataSource(jdbcUrl))
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Счастливые часы",
                    description = "20% на кальяны",
                    terms = null,
                    createdByUserId = OWNER_ID,
                )

            assertFailsWith<IllegalArgumentException> {
                ruleRepository.createHappyHoursRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    discountPercent = 0,
                    createdByUserId = OWNER_ID,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                ruleRepository.createHappyHoursRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    discountPercent = 20,
                    createdByUserId = OWNER_ID,
                    startsTime = LocalTime.of(18, 0),
                    endsTime = LocalTime.of(14, 0),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                ruleRepository.updateRuleSchedule(
                    venueId = fixture.visibleVenueId,
                    ruleId = promotion.id,
                    startsTime = LocalTime.of(14, 0),
                    endsTime = LocalTime.of(18, 0),
                    daysOfWeek = emptySet(),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                ruleRepository.updateRuleSchedule(
                    venueId = fixture.visibleVenueId,
                    ruleId = promotion.id,
                    startsTime = LocalTime.of(18, 0),
                    endsTime = LocalTime.of(2, 0),
                    daysOfWeek = setOf(5),
                )
            }
            Unit
        }

    @Test
    fun `promotion rule repository keeps happy hours non-stackable`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-rules-stackability")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val ruleRepository = VenuePromotionRuleRepository(dataSource(jdbcUrl))
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Счастливые часы",
                    description = "20% на кальяны",
                    terms = null,
                    templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                    createdByUserId = OWNER_ID,
                )
            val rule =
                ruleRepository.createHappyHoursRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    discountPercent = 20,
                    createdByUserId = OWNER_ID,
                )

            assertFalse(rule.stackable)
            assertEquals(null, rule.conflictGroup)
            assertEquals(1, rule.maxApplicationsPerItem)

            assertFailsWith<IllegalArgumentException> {
                runBlocking {
                    ruleRepository.updateRuleCompatibility(
                        venueId = fixture.visibleVenueId,
                        ruleId = rule.id,
                        stackable = true,
                        conflictGroup = "hookah",
                    )
                }
            }

            val nonStackable =
                assertNotNull(
                    ruleRepository.updateRuleCompatibility(
                        venueId = fixture.visibleVenueId,
                        ruleId = rule.id,
                        stackable = false,
                    ),
                )
            assertFalse(nonStackable.stackable)
            assertEquals(null, nonStackable.conflictGroup)

            assertEquals(null, ruleRepository.updateRuleCompatibility(fixture.otherVenueId, rule.id, stackable = true))
            Unit
        }

    @Test
    fun `promotion rule archive hides rule from management and active lists`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-rules-archive")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val ruleRepository = VenuePromotionRuleRepository(dataSource(jdbcUrl))
            val promotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Счастливые часы",
                    description = "20% на кальяны",
                    terms = null,
                    templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                    createdByUserId = OWNER_ID,
                )
            val rule =
                ruleRepository.createHappyHoursRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    discountPercent = 20,
                    createdByUserId = OWNER_ID,
                )
            assertNotNull(
                ruleRepository.setRuleStatus(
                    venueId = fixture.visibleVenueId,
                    ruleId = rule.id,
                    status = VenuePromotionStatus.ACTIVE,
                ),
            )
            setPromotionStatusForLegacyFixture(jdbcUrl, promotion.id, VenuePromotionStatus.ACTIVE)

            assertEquals(
                listOf(
                    rule.id,
                ),
                ruleRepository.listRulesForPromotionManagement(fixture.visibleVenueId, promotion.id).map {
                    it.id
                },
            )
            assertEquals(
                listOf(rule.id),
                ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId).map { it.id },
            )

            val archived = assertNotNull(ruleRepository.archiveRule(fixture.visibleVenueId, promotion.id, rule.id))

            assertEquals(VenuePromotionStatus.ARCHIVED, archived.status)
            assertTrue(ruleRepository.listRulesForPromotionManagement(fixture.visibleVenueId, promotion.id).isEmpty())
            assertTrue(ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId).isEmpty())
            assertEquals(
                VenuePromotionStatus.ARCHIVED,
                ruleRepository.getRuleForManagement(fixture.visibleVenueId, rule.id)?.status,
            )
            assertNull(ruleRepository.archiveRule(fixture.otherVenueId, promotion.id, rule.id))
        }

    @Test
    fun `promotion rule repository detects duplicate happy hours and gift rules`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("promotion-rules-duplicates")
            val fixture = seedFixture(jdbcUrl)
            val promotionRepository = VenuePromotionRepository(dataSource(jdbcUrl))
            val ruleRepository = VenuePromotionRuleRepository(dataSource(jdbcUrl))
            val hookahCategoryId =
                insertMenuCategory(jdbcUrl, fixture.visibleVenueId, "Кальяны", MenuSemanticType.HOOKAH)
            val hookahItemId = insertMenuItem(jdbcUrl, fixture.visibleVenueId, hookahCategoryId, "Кальян обычный")
            val teaCategoryId = insertMenuCategory(jdbcUrl, fixture.visibleVenueId, "Чай", MenuSemanticType.TEA)
            val teaItemId = insertMenuItem(jdbcUrl, fixture.visibleVenueId, teaCategoryId, "Чай")
            val juiceItemId = insertMenuItem(jdbcUrl, fixture.visibleVenueId, teaCategoryId, "Сок")
            val happyPromotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Счастливые часы",
                    description = "20% на кальяны",
                    terms = null,
                    templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                    createdByUserId = OWNER_ID,
                )
            val happyRule =
                ruleRepository.createHappyHoursRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = happyPromotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    discountPercent = 20,
                    createdByUserId = OWNER_ID,
                    startsTime = LocalTime.of(14, 0),
                    endsTime = LocalTime.of(18, 0),
                    daysOfWeek = setOf(1, 2, 3, 4, 5),
                )
            val itemHappyRule =
                ruleRepository.createHappyHoursRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = happyPromotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    discountPercent = 10,
                    createdByUserId = OWNER_ID,
                ).let { created ->
                    assertNotNull(
                        ruleRepository.replaceRuleTargetsWithMenuItems(
                            venueId = fixture.visibleVenueId,
                            ruleId = created.id,
                            menuItemIds = listOf(hookahItemId),
                        ),
                    )
                }

            assertEquals(
                happyRule.id,
                ruleRepository.findDuplicateHappyHoursRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = happyPromotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    discountPercent = 20,
                    startsTime = LocalTime.of(14, 0),
                    endsTime = LocalTime.of(18, 0),
                    daysOfWeek = setOf(1, 2, 3, 4, 5),
                )?.id,
            )
            assertNull(
                ruleRepository.findDuplicateHappyHoursRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = happyPromotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    discountPercent = 15,
                    startsTime = LocalTime.of(14, 0),
                    endsTime = LocalTime.of(18, 0),
                    daysOfWeek = setOf(1, 2, 3, 4, 5),
                ),
            )
            assertEquals(
                itemHappyRule.id,
                ruleRepository.findDuplicateHappyHoursRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = happyPromotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    targetMenuItemIds = listOf(hookahItemId),
                    discountPercent = 10,
                )?.id,
            )

            val giftPromotion =
                promotionRepository.createPromotion(
                    venueId = fixture.visibleVenueId,
                    title = "Чай к кальяну",
                    description = "Подарок к кальяну",
                    terms = null,
                    templateType = VenuePromotionTemplateType.GIFT_WITH_ITEM,
                    createdByUserId = OWNER_ID,
                )
            val fixedGift =
                ruleRepository.createGiftWithItemRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = giftPromotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    rewardMenuItemId = teaItemId,
                    createdByUserId = OWNER_ID,
                )
            val choiceGift =
                ruleRepository.createGiftWithItemRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = giftPromotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    rewardMenuItemId = teaItemId,
                    createdByUserId = OWNER_ID,
                ).let { created ->
                    assertNotNull(
                        ruleRepository.replaceRuleTargetsWithMenuItems(
                            venueId = fixture.visibleVenueId,
                            ruleId = created.id,
                            menuItemIds = listOf(hookahItemId),
                        ),
                    )
                    assertNotNull(
                        ruleRepository.updateGiftWithItemRewardOptions(
                            venueId = fixture.visibleVenueId,
                            ruleId = created.id,
                            rewardMenuItemIds = listOf(teaItemId, juiceItemId),
                        ),
                    )
                }

            assertEquals(
                fixedGift.id,
                ruleRepository.findDuplicateGiftWithItemRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = giftPromotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    rewardMode = PromotionRewardMode.FIXED_ITEM,
                    rewardMenuItemId = teaItemId,
                )?.id,
            )
            assertEquals(
                choiceGift.id,
                ruleRepository.findDuplicateGiftWithItemRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = giftPromotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    targetMenuItemIds = listOf(hookahItemId),
                    rewardMode = PromotionRewardMode.CHOICE_ITEMS,
                    rewardOptionMenuItemIds = listOf(juiceItemId, teaItemId),
                )?.id,
            )

            assertNotNull(ruleRepository.archiveRule(fixture.visibleVenueId, giftPromotion.id, fixedGift.id))
            assertNull(
                ruleRepository.findDuplicateGiftWithItemRule(
                    venueId = fixture.visibleVenueId,
                    promotionId = giftPromotion.id,
                    targetValue = MenuSemanticType.HOOKAH,
                    rewardMode = PromotionRewardMode.FIXED_ITEM,
                    rewardMenuItemId = teaItemId,
                ),
            )
        }

    private suspend fun VenuePromotionRepository.applyLifecycleForTest(
        venueId: Long,
        promotionId: Long,
        expectedStatus: VenuePromotionStatus,
        targetStatus: VenuePromotionStatus,
    ): VenuePromotion? =
        mutatePromotionLifecycle(
            venueId = venueId,
            promotionId = promotionId,
            expectedStatus = expectedStatus,
            targetStatus = targetStatus,
            actorUserId = OWNER_ID,
            source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
        )?.promotion

    private fun setPromotionStatusForLegacyFixture(
        jdbcUrl: String,
        promotionId: Long,
        status: VenuePromotionStatus,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "UPDATE venue_promotions SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            ).use { statement ->
                statement.setString(1, status.dbValue)
                statement.setLong(2, promotionId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun readPromotionLifecycleAudits(
        jdbcUrl: String,
        promotionId: Long,
    ): List<PromotionAuditRow> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT actor_user_id, action, entity_type, entity_id, payload_json
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
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                PromotionAuditRow(
                                    actorUserId = resultSet.getLong("actor_user_id"),
                                    action = resultSet.getString("action"),
                                    entityType = resultSet.getString("entity_type"),
                                    entityId = resultSet.getLong("entity_id"),
                                    payload =
                                        Json.parseToJsonElement(
                                            resultSet.getString("payload_json"),
                                        ).jsonObject,
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun countPromotionLifecycleAudits(jdbcUrl: String): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM audit_log WHERE action IN (?, ?)",
            ).use { statement ->
                statement.setString(1, VENUE_PROMOTION_STATUS_CHANGED_ACTION)
                statement.setString(2, VENUE_PROMOTION_ARCHIVED_ACTION)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }

    private fun assertPromotionAuditEnvelope(
        audit: PromotionAuditRow,
        action: String,
        promotionId: Long,
    ) {
        assertEquals(OWNER_ID, audit.actorUserId)
        assertEquals(action, audit.action)
        assertEquals("venue_promotion", audit.entityType)
        assertEquals(promotionId, audit.entityId)
    }

    private fun assertPromotionAuditPayload(
        payload: JsonObject,
        venueId: Long,
        promotionId: Long,
        templateType: VenuePromotionTemplateType,
        oldStatus: VenuePromotionStatus,
        newStatus: VenuePromotionStatus,
        source: VenuePromotionLifecycleSource,
        expectedRules: List<ExpectedAuditRule>,
    ) {
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
            payload.keys,
        )
        assertEquals(venueId, payload.getValue("venueId").jsonPrimitive.content.toLong())
        assertEquals(promotionId, payload.getValue("promotionId").jsonPrimitive.content.toLong())
        assertEquals(templateType.dbValue, payload.getValue("templateType").jsonPrimitive.content)
        assertEquals(oldStatus.dbValue, payload.getValue("oldStatus").jsonPrimitive.content)
        assertEquals(newStatus.dbValue, payload.getValue("newStatus").jsonPrimitive.content)
        assertEquals(source.name, payload.getValue("source").jsonPrimitive.content)

        val rules = payload.getValue("rules").jsonArray
        assertEquals(expectedRules.size, rules.size)
        rules.zip(expectedRules).forEach { (ruleElement, expectedRule) ->
            val rule = ruleElement.jsonObject
            assertEquals(setOf("ruleId", "version", "oldStatus", "newStatus"), rule.keys)
            assertEquals(expectedRule.ruleId, rule.getValue("ruleId").jsonPrimitive.content.toLong())
            assertEquals(expectedRule.version, rule.getValue("version").jsonPrimitive.content.toInt())
            assertEquals(expectedRule.oldStatus.dbValue, rule.getValue("oldStatus").jsonPrimitive.content)
            assertEquals(expectedRule.newStatus.dbValue, rule.getValue("newStatus").jsonPrimitive.content)
        }
        assertEquals(expectedRules.map { it.ruleId }.sorted(), expectedRules.map { it.ruleId })
    }

    private fun readLifecycleSnapshot(
        jdbcUrl: String,
        promotionId: Long,
    ): LifecycleSnapshot =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val promotion =
                connection.prepareStatement(
                    "SELECT status, updated_at FROM venue_promotions WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, promotionId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        Pair(
                            assertNotNull(VenuePromotionStatus.fromDb(resultSet.getString("status"))),
                            resultSet.getTimestamp("updated_at").toInstant(),
                        )
                    }
                }
            val rules =
                connection.prepareStatement(
                    """
                    SELECT id, version, status, updated_at
                    FROM promotion_rules
                    WHERE promotion_id = ?
                    ORDER BY id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, promotionId)
                    statement.executeQuery().use { resultSet ->
                        buildList {
                            while (resultSet.next()) {
                                add(
                                    LifecycleRuleSnapshot(
                                        ruleId = resultSet.getLong("id"),
                                        version = resultSet.getInt("version"),
                                        status =
                                            assertNotNull(
                                                VenuePromotionStatus.fromDb(
                                                    resultSet.getString("status"),
                                                ),
                                            ),
                                        updatedAt = resultSet.getTimestamp("updated_at").toInstant(),
                                    ),
                                )
                            }
                        }
                    }
                }
            LifecycleSnapshot(
                promotionStatus = promotion.first,
                promotionUpdatedAt = promotion.second,
                rules = rules,
            )
        }

    private fun failingAuditLogWriter(): TransactionalAuditLogWriter =
        TransactionalAuditLogWriter { _, _, _, _, _, _ ->
            throw SQLException("forced promotion lifecycle audit failure")
        }

    private fun migratedJdbcUrl(name: String): String {
        val jdbcUrl =
            "jdbc:h2:mem:$name-${UUID.randomUUID()};MODE=PostgreSQL;" +
                "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
        Flyway
            .configure()
            .dataSource(jdbcUrl, "sa", "")
            .locations("classpath:db/migration/h2")
            .load()
            .migrate()
        return jdbcUrl
    }

    private fun migrateJdbcUrl(
        jdbcUrl: String,
        targetVersion: String,
    ) {
        Flyway
            .configure()
            .dataSource(jdbcUrl, "sa", "")
            .locations("classpath:db/migration/h2")
            .target(MigrationVersion.fromVersion(targetVersion))
            .load()
            .migrate()
    }

    private fun Connection.insertGeneratedId(
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit,
    ): Long =
        prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            bind(statement)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                assertTrue(keys.next())
                keys.getLong(1)
            }
        }

    private fun dataSource(jdbcUrl: String): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(jdbcUrl)
            user = "sa"
            password = ""
        }

    private fun seedFixture(jdbcUrl: String): PromotionFixture =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            insertUser(connection, OWNER_ID)
            val visibleVenueId = insertVenue(connection, "Mix", VenueStatus.PUBLISHED.dbValue)
            val otherVenueId = insertVenue(connection, "Other", VenueStatus.PUBLISHED.dbValue)
            val hiddenVenueId = insertVenue(connection, "Hidden", VenueStatus.HIDDEN.dbValue)
            val blockedVenueId = insertVenue(connection, "Blocked", VenueStatus.PUBLISHED.dbValue)
            insertSubscription(connection, blockedVenueId, "SUSPENDED_BY_PLATFORM")
            PromotionFixture(
                visibleVenueId = visibleVenueId,
                otherVenueId = otherVenueId,
                hiddenVenueId = hiddenVenueId,
                blockedVenueId = blockedVenueId,
            )
        }

    private fun insertUser(
        connection: Connection,
        userId: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO users (telegram_user_id, username, first_name)
            VALUES (?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, "owner$userId")
            statement.setString(3, "Owner")
            statement.executeUpdate()
        }
    }

    private fun insertVenue(
        connection: Connection,
        name: String,
        status: String,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO venues (name, city, address, status)
            VALUES (?, 'Москва', 'Тверская, 1', ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, name)
            statement.setString(2, status)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun insertSubscription(
        connection: Connection,
        venueId: Long,
        status: String,
    ) {
        connection.prepareStatement(
            """
            MERGE INTO venue_subscriptions (venue_id, status, updated_at)
            KEY (venue_id)
            VALUES (?, ?, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, status)
            statement.executeUpdate()
        }
    }

    private fun insertMenuCategory(
        jdbcUrl: String,
        venueId: Long,
        name: String,
        categoryType: MenuSemanticType,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO menu_categories (venue_id, name, sort_order, category_type)
                VALUES (?, ?, 0, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, name)
                statement.setString(3, categoryType.dbValue)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
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
                INSERT INTO menu_items (venue_id, category_id, name, price_minor, currency, is_available)
                VALUES (?, ?, ?, 100000, 'RUB', true)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, categoryId)
                statement.setString(3, name)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
                }
            }
        }

    private fun insertPromotion(
        jdbcUrl: String,
        venueId: Long,
        title: String,
        status: VenuePromotionStatus,
        startsAt: Instant?,
        endsAt: Instant?,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_promotions (
                    venue_id, title, description, starts_at, ends_at, status, created_by_user_id
                )
                VALUES (?, ?, 'Описание акции', ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, title)
                statement.setTimestamp(3, startsAt?.let { Timestamp.from(it) })
                statement.setTimestamp(4, endsAt?.let { Timestamp.from(it) })
                statement.setString(5, status.dbValue)
                statement.setLong(6, OWNER_ID)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
                }
            }
        }

    private data class PromotionFixture(
        val visibleVenueId: Long,
        val otherVenueId: Long,
        val hiddenVenueId: Long,
        val blockedVenueId: Long,
    )

    private data class PromotionAuditRow(
        val actorUserId: Long,
        val action: String,
        val entityType: String,
        val entityId: Long,
        val payload: JsonObject,
    )

    private data class ExpectedAuditRule(
        val ruleId: Long,
        val version: Int,
        val oldStatus: VenuePromotionStatus,
        val newStatus: VenuePromotionStatus,
    )

    private data class LifecycleSnapshot(
        val promotionStatus: VenuePromotionStatus,
        val promotionUpdatedAt: Instant,
        val rules: List<LifecycleRuleSnapshot>,
    )

    private data class LifecycleRuleSnapshot(
        val ruleId: Long,
        val version: Int,
        val status: VenuePromotionStatus,
        val updatedAt: Instant,
    )

    private data class LegacyPromotionFixture(
        val promotionId: Long,
        val ruleId: Long,
        val fallbackDaysRuleId: Long,
        val orderId: Long,
        val batchItemId: Long,
    )

    private companion object {
        const val OWNER_ID = 1001L
    }
}
