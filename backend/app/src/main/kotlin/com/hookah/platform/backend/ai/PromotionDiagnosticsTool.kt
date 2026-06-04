package com.hookah.platform.backend.ai

import com.hookah.platform.backend.telegram.db.PromotionRewardMode
import com.hookah.platform.backend.telegram.db.PromotionRuleReward
import com.hookah.platform.backend.telegram.db.PromotionRuleTarget
import com.hookah.platform.backend.telegram.db.PromotionRuleTargetType
import com.hookah.platform.backend.telegram.db.PromotionRuleType
import com.hookah.platform.backend.telegram.db.VenuePromotion
import com.hookah.platform.backend.telegram.db.VenuePromotionRepository
import com.hookah.platform.backend.telegram.db.VenuePromotionRule
import com.hookah.platform.backend.telegram.db.VenuePromotionRuleRepository
import com.hookah.platform.backend.telegram.db.VenuePromotionStatus
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PromotionDiagnosticsRequest(
    val venueId: Long,
    val promotionId: Long,
    val ruleId: Long? = null,
    val now: Instant = Instant.now(),
    val venueZoneId: ZoneId = ZoneId.systemDefault(),
)

data class PromotionDiagnosticsResult(
    val venueId: Long,
    val promotionId: Long,
    val promotionTitle: String?,
    val summaryLines: List<String>,
    val likelyReasons: List<String>,
    val visibleToGuestNow: Boolean,
    val applicableNow: Boolean,
)

class PromotionDiagnosticsTool(
    private val promotionRepository: VenuePromotionRepository,
    private val ruleRepository: VenuePromotionRuleRepository,
) {
    suspend fun run(request: PromotionDiagnosticsRequest): PromotionDiagnosticsResult {
        val promotion = promotionRepository.getPromotionForManagement(request.venueId, request.promotionId)
        if (promotion == null) {
            return PromotionDiagnosticsResult(
                venueId = request.venueId,
                promotionId = request.promotionId,
                promotionTitle = null,
                summaryLines = listOf("Акция не найдена в этом заведении."),
                likelyReasons = listOf("Акция не найдена или относится к другому заведению."),
                visibleToGuestNow = false,
                applicableNow = false,
            )
        }

        val rules =
            if (request.ruleId != null) {
                listOfNotNull(ruleRepository.getRuleForManagement(request.venueId, request.ruleId))
            } else {
                ruleRepository.listRulesForPromotionManagement(request.venueId, request.promotionId)
            }
        val visible = promotion.isVisibleAt(request.now)
        val activeMatchingRules =
            rules.filter { rule ->
                rule.status == VenuePromotionStatus.ACTIVE && rule.matchesSchedule(request.now, request.venueZoneId)
            }
        val reasons = buildLikelyReasons(promotion, rules, activeMatchingRules, request.now, request.venueZoneId)
        return PromotionDiagnosticsResult(
            venueId = request.venueId,
            promotionId = request.promotionId,
            promotionTitle = promotion.title,
            summaryLines =
                buildList {
                    add("Акция: ${promotion.title}")
                    add("Тип: ${promotion.templateType.dbValue}")
                    add("Статус акции: ${humanizeStatus(promotion.status)}")
                    add("Период акции: ${formatInstantRange(promotion.startsAt, promotion.endsAt, request.venueZoneId)}")
                    add("Видна гостю сейчас: ${if (visible) "да" else "нет"}")
                    if (rules.isEmpty()) {
                        add("Правила: не найдены")
                    } else {
                        rules.forEachIndexed { index, rule ->
                            add("")
                            add("Правило ${index + 1}: ${humanizeRuleType(rule.ruleType)}")
                            add("Статус правила: ${humanizeStatus(rule.status)}")
                            add("Расписание: ${formatSchedule(rule)}")
                            add("Расписание активно сейчас: ${if (rule.matchesSchedule(request.now, request.venueZoneId)) "да" else "нет"}")
                            add("Цели: ${formatTargets(rule.targets)}")
                            if (rule.ruleType == PromotionRuleType.HAPPY_HOURS_PERCENT) {
                                add("Скидка: ${rule.discountPercent}%")
                            }
                            rule.reward?.let { add("Подарок: ${formatReward(it)}") }
                            add("Совместимость: ${if (rule.stackable) "можно суммировать" else "не суммировать"}")
                            add("Conflict group: ${rule.conflictGroup ?: "по умолчанию"}")
                        }
                    }
                },
            likelyReasons = reasons,
            visibleToGuestNow = visible,
            applicableNow = visible && activeMatchingRules.isNotEmpty(),
        )
    }

    private fun buildLikelyReasons(
        promotion: VenuePromotion,
        rules: List<VenuePromotionRule>,
        activeMatchingRules: List<VenuePromotionRule>,
        now: Instant,
        zoneId: ZoneId,
    ): List<String> =
        buildList {
            when (promotion.status) {
                VenuePromotionStatus.ACTIVE -> Unit
                VenuePromotionStatus.ARCHIVED -> add("Акция в архиве, поэтому скрыта от гостей и не применяется.")
                VenuePromotionStatus.PAUSED -> add("Акция приостановлена. Включите акцию, чтобы она стала видна гостям.")
                VenuePromotionStatus.DRAFT -> add("Акция в черновике. Она не видна гостям и не применяется.")
            }
            if (promotion.startsAt != null && promotion.startsAt > now) {
                add("Акция ещё не началась: старт ${formatInstant(promotion.startsAt, zoneId)}.")
            }
            if (promotion.endsAt != null && promotion.endsAt < now) {
                add("Срок акции закончился: ${formatInstant(promotion.endsAt, zoneId)}.")
            }
            if (rules.isEmpty()) {
                add("У акции нет активных правил настройки.")
            }
            val inactiveRules = rules.filter { it.status != VenuePromotionStatus.ACTIVE }
            if (inactiveRules.isNotEmpty()) {
                add("Есть правила не в статусе ACTIVE: ${inactiveRules.joinToString { "#${it.id} ${humanizeStatus(it.status)}" }}.")
            }
            val scheduledOut = rules.filter { it.status == VenuePromotionStatus.ACTIVE && !it.matchesSchedule(now, zoneId) }
            if (scheduledOut.isNotEmpty()) {
                add("Расписание активных правил сейчас не совпадает с текущим временем заведения.")
            }
            val unavailableRewards =
                rules.mapNotNull { it.reward }
                    .filter { reward ->
                        when (reward.rewardMode) {
                            PromotionRewardMode.FIXED_ITEM -> !reward.isAvailable
                            PromotionRewardMode.CHOICE_ITEMS -> reward.options.none { it.isAvailable }
                        }
                    }
            if (unavailableRewards.isNotEmpty()) {
                add("Подарочная позиция или варианты подарка сейчас недоступны в меню.")
            }
            if (promotion.isVisibleAt(now) && activeMatchingRules.isEmpty() && rules.isNotEmpty()) {
                add("Акция включена, но сейчас нет правила, которое одновременно активно и попадает в расписание.")
            }
            if (isEmpty()) {
                add("По настройкам не видно блокеров: акция включена, период подходит, правила активны.")
            }
        }

    private fun VenuePromotion.isVisibleAt(now: Instant): Boolean =
        status == VenuePromotionStatus.ACTIVE &&
            (startsAt == null || startsAt <= now) &&
            (endsAt == null || endsAt >= now)

    private fun VenuePromotionRule.matchesSchedule(
        now: Instant,
        zoneId: ZoneId,
    ): Boolean {
        val local = now.atZone(zoneId)
        if (daysOfWeek != null && local.dayOfWeek.value !in daysOfWeek) return false
        val start = startsTime
        val end = endsTime
        if (start == null && end == null) return true
        if (start == null || end == null || !start.isBefore(end)) return false
        val time = local.toLocalTime()
        return !time.isBefore(start) && time.isBefore(end)
    }

    private fun formatSchedule(rule: VenuePromotionRule): String {
        val days = rule.daysOfWeek
        val time = formatTimeRange(rule.startsTime, rule.endsTime)
        return when {
            days.isNullOrEmpty() && time == null -> "всегда"
            days.isNullOrEmpty() -> time ?: "всегда"
            else -> "${days.sorted().joinToString(", ") { humanizeDay(it) }}${time?.let { " · $it" } ?: ""}"
        }
    }

    private fun formatTimeRange(
        startsTime: LocalTime?,
        endsTime: LocalTime?,
    ): String? =
        if (startsTime == null || endsTime == null) null else "${startsTime.format(timeFormatter)}-${endsTime.format(timeFormatter)}"

    private fun formatTargets(targets: List<PromotionRuleTarget>): String =
        if (targets.isEmpty()) {
            "не заданы"
        } else {
            targets.joinToString { target ->
                when (target.targetType) {
                    PromotionRuleTargetType.CATEGORY_TYPE ->
                        humanizeSemanticType(target.semanticType?.name)
                    PromotionRuleTargetType.MENU_ITEM ->
                        target.menuItemName ?: "позиция #${target.menuItemId}"
                }
            }
        }

    private fun formatReward(reward: PromotionRuleReward): String =
        when (reward.rewardMode) {
            PromotionRewardMode.FIXED_ITEM ->
                "${reward.rewardMenuItemName} ×${reward.rewardQty}${if (reward.isAvailable) "" else " (недоступно)"}"
            PromotionRewardMode.CHOICE_ITEMS -> {
                val options = reward.options.take(5).joinToString { option ->
                    option.menuItemName + if (option.isAvailable) "" else " (недоступно)"
                }
                "на выбор: $options" + if (reward.options.size > 5) ", +${reward.options.size - 5} ещё" else ""
            }
        }

    private fun formatInstantRange(
        startsAt: Instant?,
        endsAt: Instant?,
        zoneId: ZoneId,
    ): String =
        when {
            startsAt == null && endsAt == null -> "без ограничений"
            startsAt != null && endsAt != null -> "${formatInstant(startsAt, zoneId)} - ${formatInstant(endsAt, zoneId)}"
            startsAt != null -> "с ${formatInstant(startsAt, zoneId)}"
            else -> "до ${formatInstant(endsAt!!, zoneId)}"
        }

    private fun formatInstant(
        value: Instant,
        zoneId: ZoneId,
    ): String = value.atZone(zoneId).format(dateTimeFormatter)

    private fun humanizeStatus(status: VenuePromotionStatus): String =
        when (status) {
            VenuePromotionStatus.DRAFT -> "черновик"
            VenuePromotionStatus.ACTIVE -> "включена"
            VenuePromotionStatus.PAUSED -> "приостановлена"
            VenuePromotionStatus.ARCHIVED -> "в архиве"
        }

    private fun humanizeRuleType(ruleType: PromotionRuleType): String =
        when (ruleType) {
            PromotionRuleType.HAPPY_HOURS_PERCENT -> "Счастливые часы"
            PromotionRuleType.GIFT_WITH_ITEM -> "Подарок к позиции"
        }

    private fun humanizeSemanticType(value: String?): String =
        when (value?.uppercase(Locale.ROOT)) {
            "HOOKAH" -> "кальяны"
            "TEA" -> "чай"
            "DRINK" -> "напитки"
            "FOOD" -> "еда"
            "OTHER" -> "другое"
            null -> "категория не задана"
            else -> value
        }

    private fun humanizeDay(value: Int): String =
        when (value) {
            1 -> "пн"
            2 -> "вт"
            3 -> "ср"
            4 -> "чт"
            5 -> "пт"
            6 -> "сб"
            7 -> "вс"
            else -> value.toString()
        }

    private companion object {
        val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
