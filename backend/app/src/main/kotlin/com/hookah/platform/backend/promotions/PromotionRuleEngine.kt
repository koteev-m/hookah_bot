package com.hookah.platform.backend.promotions

import com.hookah.platform.backend.miniapp.venue.menu.MenuSemanticType
import com.hookah.platform.backend.telegram.db.PromotionRewardMode
import com.hookah.platform.backend.telegram.db.PromotionRuleRewardOption
import com.hookah.platform.backend.telegram.db.PromotionRuleTarget
import com.hookah.platform.backend.telegram.db.PromotionRuleTargetType
import com.hookah.platform.backend.telegram.db.PromotionRuleType
import com.hookah.platform.backend.telegram.db.PromotionWeekdayWindow
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
    val menuCategoryId: Long? = null,
    val requiredOptionsSatisfied: Boolean = true,
    val hasIncompatibleManualDiscount: Boolean = false,
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

enum class PromotionGiftOfferStatus {
    NO_GIFT,
    FIXED_GIFT_AVAILABLE,
    GIFT_CHOICE_REQUIRED,
    GIFT_UNAVAILABLE,
    GIFT_SKIPPED,
    GIFT_SELECTED,
}

enum class PromotionGiftDecisionAction {
    ACCEPT_FIXED,
    SELECT_ITEM,
    SKIP,
}

enum class PromotionGiftUnavailableReason {
    REWARD_UNAVAILABLE,
    NO_AVAILABLE_REWARD_ITEMS,
    REQUIRED_OPTION_UNSUPPORTED,
    INVALID_REWARD_CONFIGURATION,
}

data class PromotionGiftDecision(
    val action: PromotionGiftDecisionAction,
    val promotionId: Long?,
    val ruleId: Long,
    val ruleVersion: Int,
    val selectedMenuItemId: Long? = null,
)

data class PromotionGiftRewardItem(
    val menuItemId: Long,
    val name: String,
    val originalUnitPriceMinor: Long,
    val currency: String,
)

data class PromotionGiftOffer(
    val status: PromotionGiftOfferStatus,
    val promotionId: Long? = null,
    val promotionTitle: String? = null,
    val ruleId: Long? = null,
    val ruleVersion: Int? = null,
    val triggerLineId: Long? = null,
    val triggerMenuItemId: Long? = null,
    val triggerItemName: String? = null,
    val fixedRewardItem: PromotionGiftRewardItem? = null,
    val selectableRewardItems: List<PromotionGiftRewardItem> = emptyList(),
    val selectedRewardItem: PromotionGiftRewardItem? = null,
    val unavailableReason: PromotionGiftUnavailableReason? = null,
)

data class PromotionRulePreviewResult(
    val adjustments: List<PromotionRulePreviewAdjustment>,
    val gifts: List<PromotionRulePreviewGift> = emptyList(),
    val giftChoices: List<PromotionRulePreviewGiftChoice> = emptyList(),
    val appliedGifts: List<PromotionRulePreviewGift> = emptyList(),
    val giftOffer: PromotionGiftOffer = PromotionGiftOffer(PromotionGiftOfferStatus.NO_GIFT),
    val giftDecisionResolved: Boolean = true,
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
        giftDecision: PromotionGiftDecision? = null,
    ): PromotionRulePreviewResult {
        val eligibleRules =
            activeRules
                .asSequence()
                .filter { it.venueId == venueId }
                .filter { it.status == VenuePromotionStatus.ACTIVE }
                .filter { it.matchesPromotionLifecycle(now) }
                .filter { it.hasValidExecutableConfiguration() }
                .filter { isScheduleActive(it, now, venueZoneId) }
                .toList()
        if (eligibleRules.isEmpty() || cartItems.isEmpty()) {
            return PromotionRulePreviewResult(
                adjustments = emptyList(),
                giftDecisionResolved = giftDecision == null,
            )
        }

        val percentCandidates =
            cartItems
                .filter { it.qty > 0 && it.priceMinor > 0 }
                .mapNotNull { item ->
                    eligibleRules
                        .asSequence()
                        .filter { it.ruleType == PromotionRuleType.HAPPY_HOURS_PERCENT }
                        .filter { it.matchesItem(item) }
                        .mapNotNull { rule -> rule.percentCandidate(item) }
                        .maxWithOrNull(::compareCandidateBenefit)
                }
        val giftCandidates =
            eligibleRules
                .asSequence()
                .filter { it.ruleType == PromotionRuleType.GIFT_WITH_ITEM }
                .mapNotNull { rule -> rule.giftCandidate(cartItems) }
                .toList()
        val selectedCandidates = resolveCandidates(percentCandidates + giftCandidates)
        val winningGiftCandidate = selectedCandidates.singleOrNull { it.giftCandidate != null }
        val effectiveGiftDecision =
            giftDecision
                ?: winningGiftCandidate?.let { candidate ->
                    val gift = requireNotNull(candidate.giftCandidate)
                    when {
                        candidate.ruleId in skippedGiftRuleIds ->
                            gift.toDecision(PromotionGiftDecisionAction.SKIP)
                        selectedGiftChoices[candidate.ruleId] != null ->
                            gift.toDecision(
                                action = PromotionGiftDecisionAction.SELECT_ITEM,
                                selectedMenuItemId = selectedGiftChoices[candidate.ruleId],
                            )
                        else -> null
                    }
                }
        val giftResolution = winningGiftCandidate?.giftCandidate?.resolve(effectiveGiftDecision)
        val appliedGifts = listOfNotNull(giftResolution?.appliedGift)
        val legacyVisibleGifts =
            when {
                appliedGifts.isNotEmpty() -> appliedGifts
                giftResolution?.offer?.status == PromotionGiftOfferStatus.FIXED_GIFT_AVAILABLE ->
                    listOfNotNull(winningGiftCandidate?.giftCandidate?.fixedPreviewGift())
                else -> emptyList()
            }
        val giftChoices =
            if (giftResolution?.offer?.status == PromotionGiftOfferStatus.GIFT_CHOICE_REQUIRED) {
                listOfNotNull(winningGiftCandidate?.giftCandidate?.previewChoice())
            } else {
                emptyList()
            }
        val giftOffer =
            giftResolution?.offer
                ?: PromotionGiftOffer(PromotionGiftOfferStatus.NO_GIFT)
        val decisionResolved =
            when (giftOffer.status) {
                PromotionGiftOfferStatus.GIFT_SELECTED,
                PromotionGiftOfferStatus.GIFT_SKIPPED,
                -> true
                PromotionGiftOfferStatus.NO_GIFT -> effectiveGiftDecision == null
                PromotionGiftOfferStatus.GIFT_UNAVAILABLE -> effectiveGiftDecision == null
                PromotionGiftOfferStatus.FIXED_GIFT_AVAILABLE,
                PromotionGiftOfferStatus.GIFT_CHOICE_REQUIRED,
                -> false
            }
        return PromotionRulePreviewResult(
            adjustments = selectedCandidates.mapNotNull { it.adjustment },
            gifts = legacyVisibleGifts,
            giftChoices = giftChoices,
            appliedGifts = appliedGifts,
            giftOffer = giftOffer,
            giftDecisionResolved = decisionResolved,
        )
    }

    fun isScheduleActive(
        rule: VenuePromotionRule,
        now: Instant,
        venueZoneId: ZoneId,
    ): Boolean = rule.matchesNow(now, venueZoneId)

    private fun VenuePromotionRule.percentCandidate(item: PromotionRuleCartItem): PromotionRuleCandidate? {
        val baseMinor = item.priceMinor * item.qty.toLong()
        val boundedPercent = discountPercent.coerceIn(0, 100)
        val discountMinor =
            (
                (baseMinor / 100L) * boundedPercent +
                    (baseMinor % 100L) * boundedPercent / 100L
            ).coerceAtMost(baseMinor)
        if (discountMinor <= 0L) return null
        return PromotionRuleCandidate(
            ruleId = id,
            ruleType = ruleType,
            conflictKey = resolveConflictKey(item.lineId, item.menuItemId),
            monetaryBenefit = discountMinor,
            itemGrossMinor = baseMinor,
            stackable = false,
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

    private fun VenuePromotionRule.giftCandidate(cartItems: List<PromotionRuleCartItem>): PromotionRuleCandidate? {
        val trigger =
            cartItems
                .asSequence()
                .filter {
                    it.qty > 0 &&
                        it.requiredOptionsSatisfied &&
                        !it.hasIncompatibleManualDiscount
                }
                .filter { matchesItem(it) }
                .sortedWith(
                    compareBy<PromotionRuleCartItem> { it.lineId ?: Long.MAX_VALUE }
                        .thenBy { it.menuItemId },
                )
                .firstOrNull()
                ?: return null
        val reward = reward
        val giftCandidate =
            PromotionGiftCandidate(
                promotionId = promotionId,
                promotionTitle = promotionTitle?.takeIf { it.isNotBlank() } ?: "Подарок при покупке",
                ruleId = id,
                ruleVersion = version,
                triggerLineId = trigger.lineId,
                triggerMenuItemId = trigger.menuItemId,
                triggerItemName = trigger.itemName,
                reward = reward,
            )
        return PromotionRuleCandidate(
            ruleId = id,
            ruleType = ruleType,
            conflictKey = resolveConflictKey(trigger.lineId, trigger.menuItemId),
            monetaryBenefit = giftCandidate.monetaryBenefit(),
            stackable = false,
            priority = priority,
            maxApplicationsPerItem = maxApplicationsPerItem,
            giftCandidate = giftCandidate,
        )
    }

    private fun resolveCandidates(candidates: List<PromotionRuleCandidate>): List<PromotionRuleCandidate> {
        val candidatesByConflict = candidates.groupBy { it.conflictKey }
        val firstPass =
            candidatesByConflict.mapValues { (_, candidatesForGroup) ->
                resolveConflictGroup(candidatesForGroup)
            }
        val giftWinners = firstPass.values.flatten().filter { it.giftCandidate != null }
        if (giftWinners.size <= 1) {
            return firstPass.values.flatten()
        }
        val winningGift = giftWinners.maxWithOrNull(::compareCandidateBenefit)
        return candidatesByConflict.flatMap { (conflictKey, candidatesForGroup) ->
            val current = firstPass[conflictKey].orEmpty()
            if (current.any { it.giftCandidate != null && it !== winningGift }) {
                resolveConflictGroup(candidatesForGroup.filter { it.giftCandidate == null })
            } else {
                current
            }
        }
    }

    private fun resolveConflictGroup(candidatesForGroup: List<PromotionRuleCandidate>): List<PromotionRuleCandidate> {
        if (candidatesForGroup.isEmpty()) return emptyList()
        return if (candidatesForGroup.any { !it.stackable }) {
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
        val giftCandidate: PromotionGiftCandidate? = null,
    )

    private fun VenuePromotionRule.matchesNow(
        now: Instant,
        zoneId: ZoneId,
    ): Boolean {
        val local = now.atZone(zoneId)
        if (
            ruleType == PromotionRuleType.HAPPY_HOURS_PERCENT &&
            executableTargetType != null &&
            weekdayWindows.isEmpty()
        ) {
            return false
        }
        if (weekdayWindows.isNotEmpty()) {
            val weekday = local.dayOfWeek.value
            val localMinute = local.hour * 60 + local.minute
            return weekdayWindows.any { window ->
                window.weekday == weekday &&
                    window.startsMinute in 0..1439 &&
                    window.endsMinute in 1..1440 &&
                    window.startsMinute < window.endsMinute &&
                    localMinute >= window.startsMinute &&
                    localMinute < window.endsMinute
            }
        }
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

    private fun VenuePromotionRule.matchesPromotionLifecycle(now: Instant): Boolean =
        (promotionStartsAt == null || !now.isBefore(promotionStartsAt)) &&
            (promotionEndsAt == null || !now.isAfter(promotionEndsAt))

    private fun VenuePromotionRule.hasValidExecutableConfiguration(): Boolean {
        val targetType = executableTargetType ?: return true
        if (
            stackable ||
            conflictGroup != null ||
            maxApplicationsPerItem != 1 ||
            !weekdayWindows.areValidWeekdayWindows()
        ) {
            return false
        }
        val validTarget =
            when (targetType) {
                PromotionRuleTargetType.MENU_ITEM ->
                    targets.singleOrNull()?.let { target ->
                        target.targetType == PromotionRuleTargetType.MENU_ITEM && target.menuItemId != null
                    } == true
                PromotionRuleTargetType.MENU_CATEGORY ->
                    targets.singleOrNull()?.let { target ->
                        target.targetType == PromotionRuleTargetType.MENU_CATEGORY && target.menuCategoryId != null
                    } == true
                PromotionRuleTargetType.CATEGORY_TYPE -> false
            }
        if (!validTarget) return false
        return when (ruleType) {
            PromotionRuleType.HAPPY_HOURS_PERCENT -> discountPercent in 1..100
            PromotionRuleType.GIFT_WITH_ITEM -> {
                val reward = reward ?: return false
                reward.rewardQty == 1 &&
                    reward.maxRewardsPerBatch == 1 &&
                    (
                        reward.rewardMode == PromotionRewardMode.FIXED_ITEM ||
                            reward.options.isNotEmpty()
                    )
            }
        }
    }

    private fun List<PromotionWeekdayWindow>.areValidWeekdayWindows(): Boolean {
        if (isEmpty()) {
            return false
        }
        return groupBy { it.weekday }.all { (weekday, windows) ->
            if (weekday !in 1..7) {
                return@all false
            }
            val sorted = windows.sortedWith(compareBy({ it.startsMinute }, { it.endsMinute }))
            sorted.all { window ->
                window.startsMinute in 0..1439 &&
                    window.endsMinute in 1..1440 &&
                    window.startsMinute < window.endsMinute
            } &&
                sorted.zipWithNext().all { (previous, next) ->
                    previous.endsMinute <= next.startsMinute
                }
        }
    }

    private fun VenuePromotionRule.previewLabel(): String =
        promotionTitle?.takeIf { it.isNotBlank() }
            ?: "Счастливые часы"

    private fun VenuePromotionRule.matchesItem(item: PromotionRuleCartItem): Boolean {
        val effectiveTargets =
            when {
                targets.isNotEmpty() -> targets
                executableTargetType != null -> emptyList()
                else ->
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
                PromotionRuleTargetType.MENU_CATEGORY ->
                    target.menuCategoryId != null && target.menuCategoryId == item.menuCategoryId
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

    private data class PromotionGiftCandidate(
        val promotionId: Long?,
        val promotionTitle: String,
        val ruleId: Long,
        val ruleVersion: Int,
        val triggerLineId: Long?,
        val triggerMenuItemId: Long,
        val triggerItemName: String,
        val reward: com.hookah.platform.backend.telegram.db.PromotionRuleReward?,
    ) {
        private fun fixedRewardOption(): PromotionRuleRewardOption? =
            reward?.takeIf { it.rewardMode == PromotionRewardMode.FIXED_ITEM }?.let {
                PromotionRuleRewardOption(
                    id = null,
                    rewardId = it.id,
                    menuItemId = it.rewardMenuItemId,
                    menuItemName = it.rewardMenuItemName,
                    priceMinor = it.priceMinor,
                    currency = it.currency,
                    isAvailable = it.isAvailable,
                    requiresOptionSelection = it.requiresOptionSelection,
                )
            }

        private fun availableChoiceOptions(): List<PromotionRuleRewardOption> =
            reward
                ?.takeIf { it.rewardMode == PromotionRewardMode.CHOICE_ITEMS }
                ?.options
                .orEmpty()
                .filter { it.isEligibleReward() }
                .distinctBy { it.menuItemId }
                .sortedBy { it.menuItemId }

        fun monetaryBenefit(): Long {
            val rewardQty = reward?.rewardQty?.coerceAtMost(1)?.coerceAtLeast(1) ?: 1
            return when (reward?.rewardMode) {
                PromotionRewardMode.FIXED_ITEM ->
                    fixedRewardOption()
                        ?.takeIf { it.isEligibleReward() }
                        ?.priceMinor
                        ?.times(rewardQty.toLong())
                        ?: 0L
                PromotionRewardMode.CHOICE_ITEMS ->
                    availableChoiceOptions().maxOfOrNull { it.priceMinor * rewardQty.toLong() } ?: 0L
                null -> 0L
            }
        }

        fun toDecision(
            action: PromotionGiftDecisionAction,
            selectedMenuItemId: Long? = null,
        ): PromotionGiftDecision =
            PromotionGiftDecision(
                action = action,
                promotionId = promotionId,
                ruleId = ruleId,
                ruleVersion = ruleVersion,
                selectedMenuItemId = selectedMenuItemId,
            )

        fun resolve(decision: PromotionGiftDecision?): PromotionGiftResolution {
            val identityMatches =
                decision != null &&
                    decision.promotionId == promotionId &&
                    decision.ruleId == ruleId &&
                    decision.ruleVersion == ruleVersion
            val fixed = fixedRewardOption()
            val availableChoices = availableChoiceOptions()
            val fixedOfferItem = fixed?.toOfferItem()
            val selectableOfferItems = availableChoices.map { it.toOfferItem() }
            val baseOffer =
                PromotionGiftOffer(
                    status = PromotionGiftOfferStatus.GIFT_UNAVAILABLE,
                    promotionId = promotionId,
                    promotionTitle = promotionTitle,
                    ruleId = ruleId,
                    ruleVersion = ruleVersion,
                    triggerLineId = triggerLineId,
                    triggerMenuItemId = triggerMenuItemId,
                    triggerItemName = triggerItemName,
                    fixedRewardItem = fixedOfferItem,
                    selectableRewardItems = selectableOfferItems,
                )
            if (identityMatches && decision?.action == PromotionGiftDecisionAction.SKIP) {
                return PromotionGiftResolution(
                    offer = baseOffer.copy(status = PromotionGiftOfferStatus.GIFT_SKIPPED),
                )
            }
            return when (reward?.rewardMode) {
                PromotionRewardMode.FIXED_ITEM -> {
                    val unavailableReason = fixed?.unavailableReason()
                    if (fixed == null || unavailableReason != null) {
                        PromotionGiftResolution(
                            offer =
                                baseOffer.copy(
                                    status = PromotionGiftOfferStatus.GIFT_UNAVAILABLE,
                                    unavailableReason =
                                        unavailableReason
                                            ?: PromotionGiftUnavailableReason.INVALID_REWARD_CONFIGURATION,
                                ),
                        )
                    } else if (
                        identityMatches &&
                        decision?.action == PromotionGiftDecisionAction.ACCEPT_FIXED
                    ) {
                        val gift = requireNotNull(fixedPreviewGift())
                        PromotionGiftResolution(
                            offer =
                                baseOffer.copy(
                                    status = PromotionGiftOfferStatus.GIFT_SELECTED,
                                    selectedRewardItem = fixed.toOfferItem(),
                                ),
                            appliedGift = gift,
                        )
                    } else {
                        PromotionGiftResolution(
                            offer = baseOffer.copy(status = PromotionGiftOfferStatus.FIXED_GIFT_AVAILABLE),
                        )
                    }
                }
                PromotionRewardMode.CHOICE_ITEMS -> {
                    if (availableChoices.isEmpty()) {
                        val unavailableReason =
                            if (reward.options.any { it.requiresOptionSelection }) {
                                PromotionGiftUnavailableReason.REQUIRED_OPTION_UNSUPPORTED
                            } else {
                                PromotionGiftUnavailableReason.NO_AVAILABLE_REWARD_ITEMS
                            }
                        PromotionGiftResolution(
                            offer =
                                baseOffer.copy(
                                    status = PromotionGiftOfferStatus.GIFT_UNAVAILABLE,
                                    unavailableReason = unavailableReason,
                                ),
                        )
                    } else {
                        val selected =
                            if (
                                identityMatches &&
                                decision?.action == PromotionGiftDecisionAction.SELECT_ITEM
                            ) {
                                availableChoices.firstOrNull {
                                    it.menuItemId == decision.selectedMenuItemId
                                }
                            } else {
                                null
                            }
                        if (selected == null) {
                            PromotionGiftResolution(
                                offer = baseOffer.copy(status = PromotionGiftOfferStatus.GIFT_CHOICE_REQUIRED),
                            )
                        } else {
                            val gift = requireNotNull(previewGift(selected))
                            PromotionGiftResolution(
                                offer =
                                    baseOffer.copy(
                                        status = PromotionGiftOfferStatus.GIFT_SELECTED,
                                        selectedRewardItem = selected.toOfferItem(),
                                    ),
                                appliedGift = gift,
                            )
                        }
                    }
                }
                null ->
                    PromotionGiftResolution(
                        offer =
                            baseOffer.copy(
                                unavailableReason = PromotionGiftUnavailableReason.INVALID_REWARD_CONFIGURATION,
                            ),
                    )
            }
        }

        fun fixedPreviewGift(): PromotionRulePreviewGift? =
            fixedRewardOption()
                ?.takeIf { it.isEligibleReward() }
                ?.let { previewGift(it) }

        private fun previewGift(option: PromotionRuleRewardOption): PromotionRulePreviewGift? {
            val configuredReward = reward ?: return null
            return PromotionRulePreviewGift(
                ruleId = ruleId,
                triggerLineId = triggerLineId,
                triggerMenuItemId = triggerMenuItemId,
                triggerItemName = triggerItemName,
                rewardMenuItemId = option.menuItemId,
                rewardItemName = option.menuItemName,
                rewardQty = configuredReward.rewardQty.coerceAtMost(1).coerceAtLeast(1),
                rewardPriceMinor = option.priceMinor,
                currency = option.currency,
                label = "${option.menuItemName} в подарок",
            )
        }

        fun previewChoice(): PromotionRulePreviewGiftChoice? {
            if (reward?.rewardMode != PromotionRewardMode.CHOICE_ITEMS) return null
            val options = availableChoiceOptions()
            if (options.isEmpty()) return null
            return PromotionRulePreviewGiftChoice(
                ruleId = ruleId,
                triggerLineId = triggerLineId,
                triggerMenuItemId = triggerMenuItemId,
                triggerItemName = triggerItemName,
                options = options,
            )
        }
    }

    private data class PromotionGiftResolution(
        val offer: PromotionGiftOffer,
        val appliedGift: PromotionRulePreviewGift? = null,
    )

    private fun PromotionRuleRewardOption.isEligibleReward(): Boolean =
        isAvailable && !requiresOptionSelection && priceMinor > 0L

    private fun PromotionRuleRewardOption.unavailableReason(): PromotionGiftUnavailableReason? =
        when {
            requiresOptionSelection -> PromotionGiftUnavailableReason.REQUIRED_OPTION_UNSUPPORTED
            !isAvailable || priceMinor <= 0L -> PromotionGiftUnavailableReason.REWARD_UNAVAILABLE
            else -> null
        }

    private fun PromotionRuleRewardOption.toOfferItem(): PromotionGiftRewardItem =
        PromotionGiftRewardItem(
            menuItemId = menuItemId,
            name = menuItemName,
            originalUnitPriceMinor = priceMinor.coerceAtLeast(0L),
            currency = currency,
        )
}
