package com.hookah.platform.backend.ai

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackRepository
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackStatus
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackVenueFilter
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackVenueSummary
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.telegram.db.LoyaltyProgram
import com.hookah.platform.backend.telegram.db.LoyaltyProgramStatus
import com.hookah.platform.backend.telegram.db.LoyaltyProgramType
import com.hookah.platform.backend.telegram.db.LoyaltyRepository
import com.hookah.platform.backend.telegram.db.PromotionPlacementRepository
import com.hookah.platform.backend.telegram.db.PromotionPlacementStatus
import com.hookah.platform.backend.telegram.db.PromotionVenuePlacementRepository
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.VenuePromotion
import com.hookah.platform.backend.telegram.db.VenuePromotionRepository
import com.hookah.platform.backend.telegram.db.VenuePromotionStatus
import com.hookah.platform.backend.telegram.db.VenuePromotionTemplateType
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import com.hookah.platform.backend.telegram.db.VenueStatsReport
import com.hookah.platform.backend.telegram.db.VenueStatsRepository
import com.hookah.platform.backend.telegram.db.VenueStatsTopItem
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AiVenueSummaryToolTest {
    private val now = Instant.parse("2026-05-21T10:00:00Z")

    @Test
    fun `promotion summary returns scoped promotion context`() =
        runBlocking {
            val promotionRepository: VenuePromotionRepository = mockk()
            val bannerPlacementRepository: PromotionPlacementRepository = mockk()
            val topPlacementRepository: PromotionVenuePlacementRepository = mockk()
            val loyaltyRepository: LoyaltyRepository = mockk()
            val settingsRepository: VenueSettingsRepository = mockk()

            coEvery { promotionRepository.listVenuePromotionsForManagement(10L, 100) } returns
                listOf(
                    testPromotion(1L, VenuePromotionStatus.ACTIVE),
                    testPromotion(2L, VenuePromotionStatus.DRAFT),
                    testPromotion(3L, VenuePromotionStatus.PAUSED),
                )
            coEvery { promotionRepository.listArchivedPromotionsForManagement(10L, 100) } returns
                listOf(testPromotion(4L, VenuePromotionStatus.ARCHIVED))
            coEvery {
                bannerPlacementRepository.listForVenueManagement(10L, PromotionPlacementStatus.ACTIVE, 50, now)
            } returns emptyList()
            coEvery {
                bannerPlacementRepository.listForVenueManagement(10L, PromotionPlacementStatus.PENDING, 50, now)
            } returns emptyList()
            coEvery {
                topPlacementRepository.listForVenueManagement(10L, PromotionPlacementStatus.ACTIVE, 20, now)
            } returns emptyList()
            coEvery {
                topPlacementRepository.listForVenueManagement(10L, PromotionPlacementStatus.PENDING, 20, now)
            } returns emptyList()
            coEvery { loyaltyRepository.listProgramsForVenue(10L) } returns
                listOf(testLoyaltyProgram(LoyaltyProgramStatus.ACTIVE))
            coEvery { settingsRepository.getPublicReviewUrl(10L) } returns "https://reviews.example/mix"

            val result =
                tool(
                    promotionRepository = promotionRepository,
                    promotionPlacementRepository = bannerPlacementRepository,
                    promotionVenuePlacementRepository = topPlacementRepository,
                    loyaltyRepository = loyaltyRepository,
                    venueSettingsRepository = settingsRepository,
                ).run(summaryRequest(AiVenueSummaryType.PROMOTION))

            val summary = result.summaryLines.joinToString("\n")
            assertTrue(summary.contains("Активные акции: 1"))
            assertTrue(summary.contains("Черновики: 1"))
            assertTrue(summary.contains("Приостановленные: 1"))
            assertTrue(summary.contains("В архиве: 1"))
            assertTrue(summary.contains("Ссылка на отзывы: настроена"))
            assertEquals(AiVenueSummaryType.PROMOTION, result.type)
        }

    @Test
    fun `feedback summary sanitizes and truncates comments`() =
        runBlocking {
            val feedbackRepository: VisitFeedbackRepository = mockk()
            val longComment = "Очень " + "долго ".repeat(80)
            coEvery { feedbackRepository.listVenueFeedback(10L, VisitFeedbackVenueFilter.ALL, 50, 0) } returns
                listOf(
                    testFeedback(
                        feedbackId = 1L,
                        rating = 2,
                        comment = longComment,
                        requiresAnswer = true,
                    ),
                    testFeedback(
                        feedbackId = 2L,
                        rating = 5,
                        comment = "Все хорошо",
                        requiresAnswer = false,
                    ),
                )

            val result =
                tool(visitFeedbackRepository = feedbackRepository)
                    .run(summaryRequest(AiVenueSummaryType.FEEDBACK))

            val summary = result.summaryLines.joinToString("\n")
            val commentLine = result.summaryLines.first { it.startsWith("- Очень") }
            assertTrue(summary.contains("Последних отзывов в выборке: 2"))
            assertTrue(summary.contains("Низкие оценки: 1"))
            assertTrue(summary.contains("Требуют ответа: 1"))
            assertTrue(commentLine.length <= 222)
            assertFalse(commentLine.contains("\n"))
            assertTrue(result.sourceNotes.any { it.contains("очищены") })
        }

    @Test
    fun `loyalty summary works without active program`() =
        runBlocking {
            val loyaltyRepository: LoyaltyRepository = mockk()
            coEvery { loyaltyRepository.listProgramsForVenue(10L) } returns emptyList()

            val result =
                tool(loyaltyRepository = loyaltyRepository)
                    .run(summaryRequest(AiVenueSummaryType.LOYALTY))

            assertTrue(result.summaryLines.any { it.contains("не настроена") })
            assertTrue(result.attentionLines.any { it.contains("настройте программу") })
        }

    @Test
    fun `orders summary returns partial operational context`() =
        runBlocking {
            val statsRepository: VenueStatsRepository = mockk()
            val ordersRepository: VenueOrdersRepository = mockk()
            val staffCallRepository: StaffCallRepository = mockk()
            val zoneId = ZoneId.of("Europe/Moscow")
            val periodStart = now.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant()
            coEvery { statsRepository.loadVenueStats(10L, periodStart) } returns
                VenueStatsReport(
                    ordersCount = 2,
                    revenueMinor = 180_000,
                    averageCheckMinor = 90_000,
                    discountMinor = 0,
                    cancelledItemsCount = 1,
                    currency = "RUB",
                    topItems = listOf(VenueStatsTopItem("Кальян обычный", 3)),
                )
            coEvery { ordersRepository.listOperationalQueueByOrder(10L, 50) } returns emptyList()
            coEvery { staffCallRepository.listActiveByVenue(10L, 20) } returns emptyList()

            val result =
                tool(
                    venueStatsRepository = statsRepository,
                    venueOrdersRepository = ordersRepository,
                    staffCallRepository = staffCallRepository,
                ).run(summaryRequest(AiVenueSummaryType.ORDERS, venueZoneId = zoneId))

            val summary = result.summaryLines.joinToString("\n")
            assertTrue(summary.contains("Заказов сегодня: 2"))
            assertTrue(summary.contains("Закрытых заказов: 2"))
            assertTrue(summary.contains("Выручка сегодня:"))
            assertTrue(summary.contains("₽"))
            assertTrue(summary.contains("Кальян обычный: 3"))
            assertTrue(result.attentionLines.any { it.contains("отменённые") })
        }

    @Test
    fun `orders summary returns partial data when stats read model is unavailable`() =
        runBlocking {
            val statsRepository: VenueStatsRepository = mockk()
            val ordersRepository: VenueOrdersRepository = mockk()
            val staffCallRepository: StaffCallRepository = mockk()
            coEvery { statsRepository.loadVenueStats(any(), any()) } throws DatabaseUnavailableException()
            coEvery { ordersRepository.listOperationalQueueByOrder(10L, 50) } returns emptyList()
            coEvery { staffCallRepository.listActiveByVenue(10L, 20) } returns emptyList()

            val result =
                tool(
                    venueStatsRepository = statsRepository,
                    venueOrdersRepository = ordersRepository,
                    staffCallRepository = staffCallRepository,
                ).run(summaryRequest(AiVenueSummaryType.ORDERS))

            val summary = result.summaryLines.joinToString("\n")
            assertTrue(summary.contains("Заказов сегодня: нет данных"))
            assertTrue(summary.contains("Активных заказов: 0"))
            assertTrue(summary.contains("Выручка сегодня: нет данных"))
            assertTrue(result.attentionLines.any { it.contains("сводка неполная") })
            assertTrue(result.sourceNotes.any { it.contains("Статистика заказов недоступна") })
        }

    private fun summaryRequest(
        type: AiVenueSummaryType,
        venueZoneId: ZoneId = ZoneId.of("Europe/Moscow"),
    ): VenueSummaryRequest =
        VenueSummaryRequest(
            venueId = 10L,
            type = type,
            now = now,
            venueZoneId = venueZoneId,
        )

    private fun tool(
        promotionRepository: VenuePromotionRepository = mockk(relaxed = true),
        promotionPlacementRepository: PromotionPlacementRepository = mockk(relaxed = true),
        promotionVenuePlacementRepository: PromotionVenuePlacementRepository = mockk(relaxed = true),
        loyaltyRepository: LoyaltyRepository = mockk(relaxed = true),
        venueSettingsRepository: VenueSettingsRepository = mockk(relaxed = true),
        visitFeedbackRepository: VisitFeedbackRepository = mockk(relaxed = true),
        venueStatsRepository: VenueStatsRepository = mockk(relaxed = true),
        venueOrdersRepository: VenueOrdersRepository = mockk(relaxed = true),
        staffCallRepository: StaffCallRepository = mockk(relaxed = true),
    ): VenueSummaryTool =
        VenueSummaryTool(
            venuePromotionRepository = promotionRepository,
            promotionPlacementRepository = promotionPlacementRepository,
            promotionVenuePlacementRepository = promotionVenuePlacementRepository,
            loyaltyRepository = loyaltyRepository,
            venueSettingsRepository = venueSettingsRepository,
            visitFeedbackRepository = visitFeedbackRepository,
            venueStatsRepository = venueStatsRepository,
            venueOrdersRepository = venueOrdersRepository,
            staffCallRepository = staffCallRepository,
        )

    private fun testPromotion(
        id: Long,
        status: VenuePromotionStatus,
    ): VenuePromotion =
        VenuePromotion(
            id = id,
            venueId = 10L,
            venueName = "Mix",
            title = "Акция $id",
            description = "Описание",
            terms = null,
            startsAt = null,
            endsAt = null,
            status = status,
            createdByUserId = 200L,
            createdAt = Instant.parse("2026-05-20T10:00:00Z"),
            updatedAt = Instant.parse("2026-05-20T10:00:00Z"),
            templateType = VenuePromotionTemplateType.TEXT_ONLY,
        )

    private fun testLoyaltyProgram(status: LoyaltyProgramStatus): LoyaltyProgram =
        LoyaltyProgram(
            id = 301L,
            venueId = 10L,
            venueName = "Mix",
            programType = LoyaltyProgramType.NTH_HOOKAH_FREE,
            status = status,
            nthValue = 6,
            maxRedemptionsPerVisit = 1,
            createdByUserId = 200L,
            createdAt = Instant.parse("2026-05-20T10:00:00Z"),
            updatedAt = Instant.parse("2026-05-20T10:00:00Z"),
        )

    private fun testFeedback(
        feedbackId: Long,
        rating: Int,
        comment: String,
        requiresAnswer: Boolean,
    ): VisitFeedbackVenueSummary =
        VisitFeedbackVenueSummary(
            feedbackId = feedbackId,
            visitId = 44L,
            venueId = 10L,
            guestUserId = 300L + feedbackId,
            guestDisplayName = "Гость",
            rating = rating,
            comment = comment,
            status = VisitFeedbackStatus.SUBMITTED,
            occurredAt = Instant.parse("2026-05-20T10:00:00Z"),
            serviceDate = LocalDate.parse("2026-05-20"),
            hasStaffReply = !requiresAnswer,
            requiresAnswer = requiresAnswer,
        )
}
