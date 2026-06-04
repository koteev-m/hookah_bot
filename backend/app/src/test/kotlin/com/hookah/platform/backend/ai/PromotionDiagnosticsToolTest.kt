package com.hookah.platform.backend.ai

import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.menu.MenuSemanticType
import com.hookah.platform.backend.telegram.db.PromotionRuleTarget
import com.hookah.platform.backend.telegram.db.PromotionRuleTargetType
import com.hookah.platform.backend.telegram.db.PromotionRuleType
import com.hookah.platform.backend.telegram.db.VenuePromotion
import com.hookah.platform.backend.telegram.db.VenuePromotionRepository
import com.hookah.platform.backend.telegram.db.VenuePromotionRule
import com.hookah.platform.backend.telegram.db.VenuePromotionRuleRepository
import com.hookah.platform.backend.telegram.db.VenuePromotionStatus
import com.hookah.platform.backend.telegram.db.VenuePromotionTemplateType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class PromotionDiagnosticsToolTest {
    private val promotionRepository: VenuePromotionRepository = mockk()
    private val ruleRepository: VenuePromotionRuleRepository = mockk()
    private val tool = PromotionDiagnosticsTool(promotionRepository, ruleRepository)

    @Test
    fun `diagnostics explains archived promotion`() =
        runBlocking {
            coEvery { promotionRepository.getPromotionForManagement(10L, 501L) } returns
                testPromotion(status = VenuePromotionStatus.ARCHIVED)
            coEvery { ruleRepository.listRulesForPromotionManagement(10L, 501L) } returns
                listOf(testRule(status = VenuePromotionStatus.ACTIVE))

            val result = tool.run(testRequest())

            assertFalse(result.visibleToGuestNow)
            assertFalse(result.applicableNow)
            assertTrue(result.likelyReasons.any { it.contains("архиве") })
            assertTrue(result.summaryLines.any { it.contains("Статус акции: в архиве") })
        }

    @Test
    fun `diagnostics explains schedule mismatch`() =
        runBlocking {
            coEvery { promotionRepository.getPromotionForManagement(10L, 501L) } returns
                testPromotion(status = VenuePromotionStatus.ACTIVE)
            coEvery { ruleRepository.listRulesForPromotionManagement(10L, 501L) } returns
                listOf(
                    testRule(
                        status = VenuePromotionStatus.ACTIVE,
                        startsTime = LocalTime.of(20, 0),
                        endsTime = LocalTime.of(23, 0),
                        daysOfWeek = setOf(1),
                    ),
                )

            val result =
                tool.run(
                    testRequest(
                        now = Instant.parse("2026-05-20T12:00:00Z"),
                        venueZoneId = ZoneId.of("Europe/Moscow"),
                    ),
                )

            assertTrue(result.visibleToGuestNow)
            assertFalse(result.applicableNow)
            assertTrue(result.likelyReasons.any { it.contains("Расписание активных правил") })
            assertTrue(result.summaryLines.any { it.contains("Расписание активно сейчас: нет") })
        }

    @Test
    fun `diagnostics explains inactive parent and rule status`() =
        runBlocking {
            coEvery { promotionRepository.getPromotionForManagement(10L, 501L) } returns
                testPromotion(status = VenuePromotionStatus.PAUSED)
            coEvery { ruleRepository.listRulesForPromotionManagement(10L, 501L) } returns
                listOf(testRule(status = VenuePromotionStatus.DRAFT))

            val result = tool.run(testRequest())

            assertFalse(result.visibleToGuestNow)
            assertFalse(result.applicableNow)
            assertTrue(result.likelyReasons.any { it.contains("приостановлена") })
            assertTrue(result.likelyReasons.any { it.contains("не в статусе ACTIVE") })
        }

    @Test
    fun `fake ai client returns deterministic explanation`() =
        runBlocking {
            val response =
                FakeAiAssistantClient().complete(
                    AiAssistantCompletionRequest(
                        systemPromptVersion = "ai-assistant-v1a",
                        toolName = "promotion_diagnostics",
                        sanitizedPrompt = "Акция в архиве.",
                        maxOutputTokens = 100,
                    ),
                )

            assertTrue(response.text.contains("🤖 Диагностика акции"))
            assertTrue(response.text.contains("Акция в архиве."))
            assertTrue(response.text.contains("Я ничего не изменил"))
        }

    @Test
    fun `audit logger writes metadata without raw prompt`() =
        runBlocking {
            val auditLogRepository: AuditLogRepository = mockk()
            coEvery { auditLogRepository.appendJson(any(), any(), any(), any(), any()) } returns Unit
            val logger = AuditLogAiAuditLogger(auditLogRepository)

            logger.log(
                AiAuditEvent(
                    principal = AiAssistantPrincipal(userId = 200L, role = AiAssistantRole.OWNER),
                    venueId = 10L,
                    entityId = 501L,
                    toolName = "promotion_diagnostics",
                    promptVersion = "ai-assistant-v1a",
                    success = true,
                ),
            )

            coVerify {
                auditLogRepository.appendJson(
                    actorUserId = 200L,
                    action = "AI_TOOL_CALL",
                    entityType = "AI_ASSISTANT",
                    entityId = 501L,
                    payload =
                        match<JsonObject> { payload ->
                            val text = payload.toString()
                            text.contains("promotion_diagnostics") &&
                                text.contains("ai-assistant-v1a") &&
                                !text.contains("rawPrompt") &&
                                !text.contains("secret")
                        },
                )
            }
        }

    private fun testRequest(
        now: Instant = Instant.parse("2026-05-20T12:00:00Z"),
        venueZoneId: ZoneId = ZoneId.of("Europe/Moscow"),
    ): PromotionDiagnosticsRequest =
        PromotionDiagnosticsRequest(
            venueId = 10L,
            promotionId = 501L,
            now = now,
            venueZoneId = venueZoneId,
        )

    private fun testPromotion(status: VenuePromotionStatus): VenuePromotion =
        VenuePromotion(
            id = 501L,
            venueId = 10L,
            venueName = "Mix",
            title = "Счастливые часы",
            description = "Описание",
            terms = null,
            startsAt = null,
            endsAt = null,
            status = status,
            createdByUserId = 200L,
            createdAt = Instant.parse("2026-05-14T10:00:00Z"),
            updatedAt = Instant.parse("2026-05-14T10:00:00Z"),
            templateType = VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
        )

    private fun testRule(
        status: VenuePromotionStatus,
        startsTime: LocalTime? = null,
        endsTime: LocalTime? = null,
        daysOfWeek: Set<Int>? = null,
    ): VenuePromotionRule =
        VenuePromotionRule(
            id = 601L,
            promotionId = 501L,
            promotionTitle = "Счастливые часы",
            venueId = 10L,
            ruleType = PromotionRuleType.HAPPY_HOURS_PERCENT,
            targetType = PromotionRuleTargetType.CATEGORY_TYPE,
            targetValue = MenuSemanticType.HOOKAH,
            discountPercent = 10,
            startsTime = startsTime,
            endsTime = endsTime,
            daysOfWeek = daysOfWeek,
            status = status,
            priority = 100,
            createdByUserId = 200L,
            createdAt = Instant.parse("2026-05-14T10:00:00Z"),
            updatedAt = Instant.parse("2026-05-14T10:00:00Z"),
            targets =
                listOf(
                    PromotionRuleTarget(
                        id = 1L,
                        ruleId = 601L,
                        targetType = PromotionRuleTargetType.CATEGORY_TYPE,
                        semanticType = MenuSemanticType.HOOKAH,
                        menuItemId = null,
                    ),
                ),
        )
}
