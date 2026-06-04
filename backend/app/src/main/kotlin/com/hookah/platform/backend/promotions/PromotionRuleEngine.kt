package com.hookah.platform.backend.promotions

import com.hookah.platform.backend.miniapp.venue.menu.MenuSemanticType
import com.hookah.platform.backend.telegram.db.PromotionRewardMode
import com.hookah.platform.backend.telegram.db.PromotionRuleRewardOption
import com.hookah.platform.backend.telegram.db.PromotionRuleTarget
import com.hookah.platform.backend.telegram.db.PromotionRuleTargetType
import com.hookah.platform.backend.telegram.db.PromotionRuleType
import com.hookah.platform.backend.telegram.db.VenuePromotionRule
import com.hookah.platform.backend.telegram.db.VenuePromotionStatus
import java.time.Instant
import java.time.ZoneId

data class PromotionRuleCartItem(
    val lineId: Long? = null,
    val menuItemId: Long,
    val itemName: String,
    val qty: Int,
    val priceMinor: Long,
    val currency: String,
    val effectiveType: MenuSemanticType,
)

data class PromotionRulePreviewAdjustment(
    val ruleId: Long,
    val lineId: Long?,
    val menuItemId: Long,
    val label: String,
    val itemName: String,
    val discountMinor: Long,
    val currency: String,
    val percent: Int,
)

data class PromotionRulePreviewGift(
    val ruleId: Long,
    val triggerLineId: Long?,
    val triggerMenuItemId: Long,
    val triggerItemName: String,
    val rewardMenuItemId: Long,
    val rewardItemName: String,
    val rewardQty: Int,
    val rewardPriceMinor: Long,
    val currency: String,
    val label: String,
)

data class PromotionRulePreviewGiftChoice(
    val ruleId: Long,
    val triggerLineId: Long?,
    val triggerMenuItemId: Long,
    val triggerItemName: String,
    val options: List<PromotionRuleRewardOption>,
)

data class PromotionRulePreviewResult(
    val adjustments: List<PromotionRulePreviewAdjustment>,
    val gifts: List<PromotionRulePreviewGift> = emptyList(),
    val giftChoices: List<PromotionRulePreviewGiftChoice> = emptyList(),
) {
    val totalPreviewDiscountMinor: Long = adjustments.sumOf { it.discountMinor }
}

object PromotionRuleEngine {
    fun preview(
        venueId: Long,
        now: Instant,
        venueZoneId: ZoneId,
        cartItems: List<PromotionRuleCartItem>,
        activeRules: List<VenuePromotionRule>,
        selectedGiftChoices: Map<Long, Long> = emptyMap(),
        skippedGiftRuleIds: Set<Long> = emptySet(),
    ): PromotionRulePreviewResult {
        val eligibleRules =
            activeRules
                .asSequence()
	                .filter { it.venueId == venueId }
	                .filter { it.status == VenuePromotionStatus.ACTIVE }
	                .filter { it.matchesNow(now, venueZoneId) }
	                .toList()
        if (eligibleRules.isEmpty() || cartItems.isEmpty()) {
            return PromotionRulePreviewResult(emptyList())
        }

        val percentCandidates =
            cartItems
                .filter { it.qty > 0 && it.priceMinor > 0 }
                .flatMap { item ->
                    eligibleRules
                        .asSequence()
                        .filter { it.ruleType == PromotionRuleType.HAPPY_HOURS_PERCENT }
                        .filter { it.matchesItem(item) }
                        .mapNotNull { rule -> rule.percentCandidate(item) }
                        .toList()
                }
        val giftCandidates =
            eligibleRules
                .asSequence()
                .filter { it.ruleType == PromotionRuleType.GIFT_WITH_ITEM }
                .filter { it.id !in skippedGiftRuleIds }
                .mapNotNull { rule -> rule.giftCandidate(cartItems, selectedGiftChoices[rule.id]) }
                .toList()
        val selectedCandidates = resolveCandidates(percentCandidates + giftCandidates)
        return PromotionRulePreviewResult(
            adjustments = selectedCandidates.mapNotNull { it.adjustment },
            gifts = selectedCandidates.mapNotNull { it.gift },
            giftChoices = selectedCandidates.mapNotNull { it.giftChoice },
        )
    }

    private fun VenuePromotionRule.percentCandidate(item: PromotionRuleCartItem): PromotionRuleCandidate? {
        val baseMinor = item.priceMinor * item.qty.toLong()
        val discountMinor = (baseMinor * discountPercent / 100).coerceAtMost(baseMinor)
        if (discountMinor <= 0L) return null
        return PromotionRuleCandidate(
            ruleId = id,
            ruleType = ruleType,
            conflictKey = resolveConflictKey(item.lineId, item.menuItemId),
            monetaryBenefit = discountMinor,
            itemGrossMinor = baseMinor,
            stackable = stackable,
            priority = priority,
            maxApplicationsPerItem = maxApplicationsPerItem,
            adjustment =
                PromotionRulePreviewAdjustment(
                    ruleId = id,
                    lineId = item.lineId,
                    menuItemId = item.menuItemId,
                    label = previewLabel(),
                    itemName = item.itemName,
                    discountMinor = discountMinor,
                    currency = item.currency,
                    percent = discountPercent,
                ),
        )
    }

    private fun VenuePromotionRule.giftCandidate(
        cartItems: List<PromotionRuleCartItem>,
        selectedRewardMenuItemId: Long? = null,
    ): PromotionRuleCandidate? {
        val gift = previewGift(cartItems, selectedRewardMenuItemId)
        if (gift != null) {
            return PromotionRuleCandidate(
                ruleId = id,
                ruleType = ruleType,
                conflictKey = resolveConflictKey(gift.triggerLineId, gift.triggerMenuItemId),
                monetaryBenefit = gift.rewardPriceMinor * gift.rewardQty.toLong(),
                stackable = stackable,
                priority = priority,
                maxApplicationsPerItem = maxApplicationsPerItem,
                gift = gift,
            )
        }
        val giftChoice = previewGiftChoice(cartItems, selectedRewardMenuItemId) ?: return null
        val reward = reward ?: return null
        val benefit =
            giftChoice.options
                .maxOfOrNull { it.priceMinor * reward.rewardQty.toLong() }
                ?: return null
        if (benefit <= 0L) return null
        return PromotionRuleCandidate(
            ruleId = id,
            ruleType = ruleType,
            conflictKey = resolveConflictKey(giftChoice.triggerLineId, giftChoice.triggerMenuItemId),
            monetaryBenefit = benefit,
            stackable = stackable,
            priority = priority,
            maxApplicationsPerItem = maxApplicationsPerItem,
            giftChoice = giftChoice,
        )
    }

    private fun resolveCandidates(candidates: List<PromotionRuleCandidate>): List<PromotionRuleCandidate> =
        candidates
            .groupBy { it.conflictKey }
            .values
            .flatMap { candidatesForGroup ->
                if (candidatesForGroup.any { !it.stackable }) {
                    listOfNotNull(candidatesForGroup.maxWithOrNull(::compareCandidateBenefit))
                } else {
                    capStackableAdjustments(
                        candidatesForGroup
                        .groupBy { it.ruleId }
                        .values
                        .flatMap { ruleCandidates ->
                            ruleCandidates
                                .sortedWith(candidateDisplayOrder)
                                .take(ruleCandidates.first().maxApplicationsPerItem.coerceAtLeast(1))
                        },
                    )
                }
            }

    private fun capStackableAdjustments(candidates: List<PromotionRuleCandidate>): List<PromotionRuleCandidate> {
        val remainingGrossByConflictKey =
            candidates
                .mapNotNull { candidate ->
                    if (candidate.adjustment == null) {
                        null
                    } else {
                        candidate.itemGrossMinor?.let { candidate.conflictKey to it }
                    }
                }
                .toMap()
                .toMutableMap()
        if (remainingGrossByConflictKey.isEmpty()) return candidates
        return candidates.mapNotNull { candidate ->
            val adjustment = candidate.adjustment ?: return@mapNotNull candidate
            val remainingGross = remainingGrossByConflictKey[candidate.conflictKey] ?: return@mapNotNull candidate
            if (remainingGross <= 0L) {
                return@mapNotNull null
            }
            val cappedDiscount = adjustment.discountMinor.coerceAtMost(remainingGross)
            remainingGrossByConflictKey[candidate.conflictKey] = remainingGross - cappedDiscount
            if (cappedDiscount <= 0L) {
                null
            } else {
                candidate.copy(
                    monetaryBenefit = cappedDiscount,
                    adjustment = adjustment.copy(discountMinor = cappedDiscount),
                )
            }
        }
    }

    private fun compareCandidateBenefit(
        first: PromotionRuleCandidate,
        second: PromotionRuleCandidate,
    ): Int =
        when {
            first.monetaryBenefit != second.monetaryBenefit ->
                first.monetaryBenefit.compareTo(second.monetaryBenefit)
            first.priority != second.priority ->
                second.priority.compareTo(first.priority)
            else ->
                second.ruleId.compareTo(first.ruleId)
        }

    private val candidateDisplayOrder =
        compareBy<PromotionRuleCandidate> { it.priority }
            .thenBy { it.ruleId }

    private data class PromotionRuleCandidate(
        val ruleId: Long,
        val ruleType: PromotionRuleType,
        val conflictKey: String,
        val monetaryBenefit: Long,
        val itemGrossMinor: Long? = null,
        val stackable: Boolean,
        val priority: Int,
        val maxApplicationsPerItem: Int,
        val adjustment: PromotionRulePreviewAdjustment? = null,
        val gift: PromotionRulePreviewGift? = null,
        val giftChoice: PromotionRulePreviewGiftChoice? = null,
    )

    private fun VenuePromotionRule.matchesNow(
        now: Instant,
        zoneId: ZoneId,
    ): Boolean {
        val local = now.atZone(zoneId)
        val allowedDays = daysOfWeek
        if (allowedDays != null && local.dayOfWeek.value !in allowedDays) {
            return false
        }
        val start = startsTime
        val end = endsTime
        if (start == null && end == null) {
            return true
        }
        if (start == null || end == null || !start.isBefore(end)) {
            return false
        }
        val time = local.toLocalTime()
        return !time.isBefore(start) && time.isBefore(end)
    }

	    private fun VenuePromotionRule.previewLabel(): String =
	        promotionTitle?.takeIf { it.isNotBlank() }
	            ?: "Счастливые часы"

        private fun VenuePromotionRule.previewGift(
            cartItems: List<PromotionRuleCartItem>,
            selectedRewardMenuItemId: Long? = null,
        ): PromotionRulePreviewGift? {
            val reward = reward ?: return null
            if (reward.rewardQty <= 0 || reward.maxRewardsPerBatch <= 0) {
                return null
            }
            val trigger =
                cartItems
                    .filter { it.qty > 0 }
                    .firstOrNull { matchesItem(it) }
                    ?: return null
            val rewardItem =
                when (reward.rewardMode) {
                    PromotionRewardMode.FIXED_ITEM ->
                        PromotionRuleRewardOption(
                            id = null,
                            rewardId = reward.id,
                            menuItemId = reward.rewardMenuItemId,
                            menuItemName = reward.rewardMenuItemName,
                            priceMinor = reward.priceMinor,
                            currency = reward.currency,
                            isAvailable = reward.isAvailable,
                        )
                    PromotionRewardMode.CHOICE_ITEMS ->
                        reward.options.firstOrNull { option ->
                            option.menuItemId == selectedRewardMenuItemId
                        } ?: return null
                }
            if (!rewardItem.isAvailable || rewardItem.priceMinor <= 0L) {
                return null
            }
            return PromotionRulePreviewGift(
                ruleId = id,
                triggerLineId = trigger.lineId,
                triggerMenuItemId = trigger.menuItemId,
                triggerItemName = trigger.itemName,
                rewardMenuItemId = rewardItem.menuItemId,
                rewardItemName = rewardItem.menuItemName,
                rewardQty = reward.rewardQty,
                rewardPriceMinor = rewardItem.priceMinor,
                currency = rewardItem.currency,
                label = "${rewardItem.menuItemName} в подарок",
            )
        }

        private fun VenuePromotionRule.previewGiftChoice(
            cartItems: List<PromotionRuleCartItem>,
            selectedRewardMenuItemId: Long?,
        ): PromotionRulePreviewGiftChoice? {
            val reward = reward ?: return null
            if (reward.rewardMode != PromotionRewardMode.CHOICE_ITEMS) return null
            val trigger =
                cartItems
                    .filter { it.qty > 0 }
                    .firstOrNull { matchesItem(it) }
                    ?: return null
            val availableOptions =
                reward.options
                    .filter { it.isAvailable && it.priceMinor > 0L }
                    .distinctBy { it.menuItemId }
            if (availableOptions.isEmpty()) return null
            val selectedAvailable = selectedRewardMenuItemId?.let { selected ->
                availableOptions.any { it.menuItemId == selected }
            } ?: false
            if (selectedAvailable) return null
            return PromotionRulePreviewGiftChoice(
                ruleId = id,
                triggerLineId = trigger.lineId,
                triggerMenuItemId = trigger.menuItemId,
                triggerItemName = trigger.itemName,
                options = availableOptions,
            )
        }

	    private fun VenuePromotionRule.matchesItem(item: PromotionRuleCartItem): Boolean {
	        val effectiveTargets =
	            targets.ifEmpty {
	                listOf(
                    PromotionRuleTarget(
	                        id = null,
	                        ruleId = id,
	                        targetType = targetType,
	                        semanticType = targetValue,
	                        menuItemId = null,
	                    ),
	                )
	            }
	        return effectiveTargets.any { target ->
	            when (target.targetType) {
	                PromotionRuleTargetType.CATEGORY_TYPE -> target.semanticType == item.effectiveType
	                PromotionRuleTargetType.MENU_ITEM -> target.menuItemId == item.menuItemId
	            }
	        }
	    }

    private fun VenuePromotionRule.resolveConflictKey(
        lineId: Long?,
        menuItemId: Long,
    ): String =
        conflictGroup?.takeIf { it.isNotBlank() }?.let { "GROUP:$it" }
            ?: lineId?.let { "ITEM:$it" }
            ?: "MENU_ITEM:$menuItemId"
	}
