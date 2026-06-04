package com.hookah.platform.backend.ai

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackRepository
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackVenueFilter
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.telegram.db.LoyaltyProgramStatus
import com.hookah.platform.backend.telegram.db.LoyaltyProgramTarget
import com.hookah.platform.backend.telegram.db.LoyaltyProgramTargetType
import com.hookah.platform.backend.telegram.db.LoyaltyRepository
import com.hookah.platform.backend.telegram.db.PromotionPlacementRepository
import com.hookah.platform.backend.telegram.db.PromotionPlacementStatus
import com.hookah.platform.backend.telegram.db.PromotionVenuePlacementRepository
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.VenuePromotionRepository
import com.hookah.platform.backend.telegram.db.VenuePromotionStatus
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import com.hookah.platform.backend.telegram.db.VenueStatsRepository
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

enum class AiVenueSummaryType(
    val callbackKey: String,
    val toolName: String,
    val title: String,
) {
    PROMOTION("promotion", AiAssistantService.TOOL_SUMMARY_PROMOTION, "📣 Сводка по продвижению"),
    FEEDBACK("feedback", AiAssistantService.TOOL_SUMMARY_FEEDBACK, "⭐ Сводка по отзывам"),
    LOYALTY("loyalty", AiAssistantService.TOOL_SUMMARY_LOYALTY, "🎁 Сводка по лояльности"),
    ORDERS("orders", AiAssistantService.TOOL_SUMMARY_ORDERS, "🧾 Сводка по заказам"),
    ;

    companion object {
        fun fromCallbackKey(value: String): AiVenueSummaryType? = entries.firstOrNull { it.callbackKey == value }
    }
}

data class AiVenueSummaryCommand(
    val principal: AiAssistantPrincipal,
    val venueId: Long,
    val type: AiVenueSummaryType,
    val now: Instant = Instant.now(),
    val venueZoneId: ZoneId = ZoneId.systemDefault(),
)

data class VenueSummaryRequest(
    val venueId: Long,
    val type: AiVenueSummaryType,
    val now: Instant = Instant.now(),
    val venueZoneId: ZoneId = ZoneId.systemDefault(),
)

data class VenueSummaryResult(
    val venueId: Long,
    val type: AiVenueSummaryType,
    val title: String,
    val summaryLines: List<String>,
    val attentionLines: List<String>,
    val sourceNotes: List<String> = emptyList(),
)

class VenueSummaryTool(
    private val venuePromotionRepository: VenuePromotionRepository,
    private val promotionPlacementRepository: PromotionPlacementRepository,
    private val promotionVenuePlacementRepository: PromotionVenuePlacementRepository,
    private val loyaltyRepository: LoyaltyRepository,
    private val venueSettingsRepository: VenueSettingsRepository,
    private val visitFeedbackRepository: VisitFeedbackRepository,
    private val venueStatsRepository: VenueStatsRepository,
    private val venueOrdersRepository: VenueOrdersRepository,
    private val staffCallRepository: StaffCallRepository,
) {
    suspend fun run(request: VenueSummaryRequest): VenueSummaryResult =
        when (request.type) {
            AiVenueSummaryType.PROMOTION -> promotionSummary(request)
            AiVenueSummaryType.FEEDBACK -> feedbackSummary(request)
            AiVenueSummaryType.LOYALTY -> loyaltySummary(request)
            AiVenueSummaryType.ORDERS -> ordersSummary(request)
        }

    private suspend fun promotionSummary(request: VenueSummaryRequest): VenueSummaryResult {
        val promotions = venuePromotionRepository.listVenuePromotionsForManagement(request.venueId, limit = 100)
        val archived = venuePromotionRepository.listArchivedPromotionsForManagement(request.venueId, limit = 100)
        val activePromotions = promotions.count { it.status == VenuePromotionStatus.ACTIVE }
        val draftPromotions = promotions.count { it.status == VenuePromotionStatus.DRAFT }
        val pausedPromotions = promotions.count { it.status == VenuePromotionStatus.PAUSED }
        val activeBannerPlacements =
            promotionPlacementRepository.listForVenueManagement(
                venueId = request.venueId,
                status = PromotionPlacementStatus.ACTIVE,
                limit = 50,
                now = request.now,
            )
        val pendingBannerPlacements =
            promotionPlacementRepository.listForVenueManagement(
                venueId = request.venueId,
                status = PromotionPlacementStatus.PENDING,
                limit = 50,
                now = request.now,
            )
        val activeTopPlacements =
            promotionVenuePlacementRepository.listForVenueManagement(
                venueId = request.venueId,
                status = PromotionPlacementStatus.ACTIVE,
                limit = 20,
                now = request.now,
            )
        val pendingTopPlacements =
            promotionVenuePlacementRepository.listForVenueManagement(
                venueId = request.venueId,
                status = PromotionPlacementStatus.PENDING,
                limit = 20,
                now = request.now,
            )
        val loyaltyProgram = loyaltyRepository.listProgramsForVenue(request.venueId).firstOrNull()
        val publicReviewUrlConfigured = !venueSettingsRepository.getPublicReviewUrl(request.venueId).isNullOrBlank()
        val attention =
            buildList {
                if (activePromotions == 0) add("Нет активных акций для гостей.")
                if (draftPromotions > 0) add("Есть черновики акций: $draftPromotions.")
                if (pausedPromotions > 0) add("Есть приостановленные акции: $pausedPromotions.")
                if (pendingBannerPlacements.isNotEmpty() || pendingTopPlacements.isNotEmpty()) {
                    add("Есть заявки на размещение на проверке.")
                }
                if (!publicReviewUrlConfigured) add("Ссылка на публичный отзыв не настроена.")
                if (loyaltyProgram == null) add("Программа лояльности не настроена.")
            }
        return VenueSummaryResult(
            venueId = request.venueId,
            type = request.type,
            title = request.type.title,
            summaryLines =
                listOf(
                    "Активные акции: $activePromotions",
                    "Черновики: $draftPromotions",
                    "Приостановленные: $pausedPromotions",
                    "В архиве: ${archived.size}",
                    "Активные баннерные размещения: ${activeBannerPlacements.size}",
                    "Заявки на баннерные размещения: ${pendingBannerPlacements.size}",
                    "Топ в Акциях: ${formatTopPlacementStatus(activeTopPlacements.size, pendingTopPlacements.size)}",
                    "Лояльность: ${loyaltyProgram?.status?.dbValue ?: "не настроена"}",
                    "Ссылка на отзывы: ${if (publicReviewUrlConfigured) "настроена" else "не настроена"}",
                ),
            attentionLines = attention.ifEmpty { listOf("Критичных блокеров в продвижении не найдено.") },
        )
    }

    private suspend fun feedbackSummary(request: VenueSummaryRequest): VenueSummaryResult {
        val feedback = visitFeedbackRepository.listVenueFeedback(request.venueId, VisitFeedbackVenueFilter.ALL, limit = 50)
        val lowRatings = feedback.count { (it.rating ?: 5) <= 3 }
        val needsReply = feedback.count { it.requiresAnswer }
        val ratings = feedback.mapNotNull { it.rating }
        val averageRating = if (ratings.isEmpty()) null else ratings.average()
        val comments =
            feedback
                .mapNotNull { it.comment }
                .mapNotNull(::sanitizeComment)
                .take(5)
        val attention =
            buildList {
                if (needsReply > 0) add("Есть отзывы, на которые стоит ответить: $needsReply.")
                if (lowRatings > 0) add("Есть низкие оценки: $lowRatings.")
                if (feedback.isEmpty()) add("Отзывов пока нет.")
            }
        return VenueSummaryResult(
            venueId = request.venueId,
            type = request.type,
            title = request.type.title,
            summaryLines =
                buildList {
                    add("Последних отзывов в выборке: ${feedback.size}")
                    add("Низкие оценки: $lowRatings")
                    add("Требуют ответа: $needsReply")
                    add("Средняя оценка: ${averageRating?.let { "%.1f".format(it) } ?: "нет данных"}")
                    if (comments.isNotEmpty()) {
                        add("Недавние комментарии:")
                        comments.forEach { add("- $it") }
                    }
                },
            attentionLines = attention.ifEmpty { listOf("Явных проблем в последних отзывах не видно.") },
            sourceNotes = listOf("Комментарии очищены и обрезаны перед отправкой в AI context."),
        )
    }

    private suspend fun loyaltySummary(request: VenueSummaryRequest): VenueSummaryResult {
        val program = loyaltyRepository.listProgramsForVenue(request.venueId).firstOrNull()
            ?: return VenueSummaryResult(
                venueId = request.venueId,
                type = request.type,
                title = request.type.title,
                summaryLines = listOf("Программа лояльности не настроена."),
                attentionLines = listOf("Если лояльность нужна, настройте программу в разделе «📣 Продвижение → 🎁 Лояльность»."),
            )
        val earnTargets = loyaltyRepository.listEarnTargets(request.venueId, program.id)
        val rewardTargets = loyaltyRepository.listRewardTargets(request.venueId, program.id)
        val progressSummary = loyaltyRepository.getProgressSummary(request.venueId, program.id)
        return VenueSummaryResult(
            venueId = request.venueId,
            type = request.type,
            title = request.type.title,
            summaryLines =
                listOf(
                    "Статус программы: ${program.status.dbValue}",
                    "Механика: каждый ${program.nthValue}-й кальян бесплатно",
                    "Что засчитывается: ${formatLoyaltyTargets(earnTargets)}",
                    "Что можно получить бесплатно: ${formatLoyaltyTargets(rewardTargets)}",
                    "Гостей с прогрессом или бонусами: ${progressSummary.usersWithProgress}",
                    "Доступных бонусов у гостей: ${progressSummary.rewardsAvailable}",
                ),
            attentionLines =
                buildList {
                    if (program.status != LoyaltyProgramStatus.ACTIVE) add("Программа не включена.")
                    if (earnTargets.isEmpty()) add("Не заданы позиции, которые засчитываются в прогресс.")
                    if (rewardTargets.isEmpty()) add("Не заданы позиции, которые можно получить бесплатно.")
                    if (progressSummary.rewardsAvailable > 0) add("У гостей уже есть доступные бонусы.")
                }.ifEmpty { listOf("Лояльность настроена и готова к работе.") },
        )
    }

    private suspend fun ordersSummary(request: VenueSummaryRequest): VenueSummaryResult {
        val periodStart = request.now.atZone(request.venueZoneId).toLocalDate().atStartOfDay(request.venueZoneId).toInstant()
        val sourceNotes = mutableListOf<String>()
        val stats =
            try {
                venueStatsRepository.loadVenueStats(request.venueId, periodStart)
            } catch (_: DatabaseUnavailableException) {
                sourceNotes += "Статистика заказов недоступна, сводка собрана по доступным данным."
                null
            }
        val activeOrders =
            try {
                venueOrdersRepository.listOperationalQueueByOrder(request.venueId, limit = 50)
            } catch (_: DatabaseUnavailableException) {
                sourceNotes += "Очередь активных заказов недоступна."
                null
            }
        val staffCalls =
            try {
                staffCallRepository.listActiveByVenue(request.venueId, limit = 20)
            } catch (_: DatabaseUnavailableException) {
                sourceNotes += "Активные вызовы персонала недоступны."
                null
            }
        val activeOrderIds = activeOrders.orEmpty().map { it.orderId }.toSet()
        val closedOrdersApprox = stats?.let { max(it.ordersCount - activeOrderIds.size, 0) }
        return VenueSummaryResult(
            venueId = request.venueId,
            type = request.type,
            title = request.type.title,
            summaryLines =
                buildList {
                    add("Заказов сегодня: ${stats?.ordersCount ?: "нет данных"}")
                    add("Активных заказов: ${activeOrders?.let { activeOrderIds.size } ?: "нет данных"}")
                    add("Закрытых заказов: ${closedOrdersApprox ?: "нет данных"}")
                    add("Активных вызовов персонала: ${staffCalls?.size ?: "нет данных"}")
                    add("Выручка сегодня: ${stats?.let { formatMoney(it.revenueMinor, it.currency) } ?: "нет данных"}")
                    if (stats?.topItems?.isNotEmpty() == true) {
                        add("Топ позиций:")
                        stats.topItems.forEach { add("- ${it.itemName}: ${it.qty}") }
                    }
                },
            attentionLines =
                buildList {
                    if (!staffCalls.isNullOrEmpty()) add("Есть активные вызовы персонала.")
                    if (activeOrderIds.isNotEmpty()) add("Есть активные заказы в работе.")
                    if (stats != null && stats.cancelledItemsCount > 0) {
                        add("Есть отменённые/исключённые позиции за период: ${stats.cancelledItemsCount}.")
                    }
                    if (sourceNotes.isNotEmpty()) add("Часть данных недоступна, сводка неполная.")
                }.ifEmpty { listOf("Смена выглядит спокойно: активных блокеров по заказам не найдено.") },
            sourceNotes =
                sourceNotes +
                    "Закрытые заказы рассчитаны по доступной read model как заказы за день минус активные заказы.",
        )
    }

    private fun formatTopPlacementStatus(
        active: Int,
        pending: Int,
    ): String =
        when {
            active > 0 -> "активно ($active)"
            pending > 0 -> "заявка на проверке ($pending)"
            else -> "не активно"
        }

    private fun formatLoyaltyTargets(targets: List<LoyaltyProgramTarget>): String {
        if (targets.isEmpty()) return "не заданы"
        val category = targets.any { it.targetType == LoyaltyProgramTargetType.CATEGORY_TYPE }
        if (category) return "все кальяны"
        val names = targets.mapNotNull { it.menuItemName ?: it.menuItemId?.let { id -> "позиция #$id" } }
        return names.take(5).joinToString(", ") + if (names.size > 5) ", +${names.size - 5} ещё" else ""
    }

    private fun sanitizeComment(raw: String): String? =
        raw
            .replace(Regex("""\s+"""), " ")
            .trim()
            .takeIf { it.isNotBlank() }
            ?.take(220)

    private fun formatMoney(
        amountMinor: Long,
        currency: String,
    ): String {
        val whole = amountMinor / 100
        val formatted = "%,d".format(whole).replace(',', ' ')
        return "$formatted ${if (currency == "RUB") "₽" else currency}"
    }
}
