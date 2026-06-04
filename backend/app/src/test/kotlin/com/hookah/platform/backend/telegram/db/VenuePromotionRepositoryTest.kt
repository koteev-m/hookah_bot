package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.miniapp.venue.menu.MenuSemanticType
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.sql.DriverManager
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
                    repository.setPromotionStatus(
                        venueId = fixture.visibleVenueId,
                        promotionId = created.id,
                        status = VenuePromotionStatus.ACTIVE,
                    ),
                )
            assertEquals(VenuePromotionStatus.ACTIVE, active.status)

            repository.archivePromotion(fixture.visibleVenueId, created.id)
            assertTrue(repository.listVenuePromotionsForManagement(fixture.visibleVenueId).isEmpty())
            assertEquals(
                listOf(created.id),
                repository.listArchivedPromotionsForManagement(fixture.visibleVenueId).map { it.id },
            )
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
                promotionRepository.setPromotionStatus(
                    venueId = fixture.visibleVenueId,
                    promotionId = activeBanner.id,
                    status = VenuePromotionStatus.ACTIVE,
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
                promotionRepository.setPromotionStatus(
                    venueId = fixture.visibleVenueId,
                    promotionId = noImageBanner.id,
                    status = VenuePromotionStatus.ACTIVE,
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
                promotionRepository.setPromotionStatus(
                    venueId = fixture.visibleVenueId,
                    promotionId = expiredBanner.id,
                    status = VenuePromotionStatus.ACTIVE,
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

            assertNotNull(promotionRepository.archivePromotion(fixture.visibleVenueId, activeBanner.id))
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
                promotionRepository.setPromotionStatus(
                    venueId = fixture.visibleVenueId,
                    promotionId = activePromotion.id,
                    status = VenuePromotionStatus.ACTIVE,
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

            assertEquals(VenuePromotionStatus.ACTIVE, created.status)
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
                promotionRepository.setPromotionStatus(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    status = VenuePromotionStatus.ACTIVE,
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
                promotionRepository.setPromotionStatus(
                    fixture.visibleVenueId,
                    promotion.id,
                    VenuePromotionStatus.PAUSED,
                ),
            )
            assertEquals(emptyList(), ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId).map { it.id })

            assertNotNull(
                promotionRepository.setPromotionStatus(
                    fixture.visibleVenueId,
                    promotion.id,
                    VenuePromotionStatus.ACTIVE,
                ),
            )
            assertEquals(
                listOf(rule.id),
                ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId).map { it.id },
            )

            assertNotNull(promotionRepository.archivePromotion(fixture.visibleVenueId, promotion.id))
            assertEquals(emptyList(), ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId).map { it.id })
            assertEquals(
                VenuePromotionStatus.ARCHIVED,
                ruleRepository.getRuleForManagement(fixture.visibleVenueId, rule.id)?.status,
            )
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
            assertEquals(emptyList(), ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId))

            assertNotNull(
                promotionRepository.setPromotionStatus(
                    fixture.visibleVenueId,
                    promotion.id,
                    VenuePromotionStatus.ACTIVE,
                ),
            )
            val active = ruleRepository.listActiveRulesForVenueAt(fixture.visibleVenueId).single()
            assertEquals(PromotionRuleType.GIFT_WITH_ITEM, active.ruleType)
            assertEquals(rewardItemId, active.reward?.rewardMenuItemId)

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
    fun `promotion rule repository updates stackability settings`() =
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

            val stackable =
                assertNotNull(
                    ruleRepository.updateRuleCompatibility(
                        venueId = fixture.visibleVenueId,
                        ruleId = rule.id,
                        stackable = true,
                        conflictGroup = "hookah",
                    ),
                )
            assertTrue(stackable.stackable)
            assertEquals("hookah", stackable.conflictGroup)

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
                promotionRepository.setPromotionStatus(
                    venueId = fixture.visibleVenueId,
                    promotionId = promotion.id,
                    status = VenuePromotionStatus.ACTIVE,
                ),
            )

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

    private companion object {
        const val OWNER_ID = 1001L
    }
}
