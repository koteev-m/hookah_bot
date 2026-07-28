package com.hookah.platform.backend.miniapp.venue.promotions

import kotlinx.serialization.Serializable

@Serializable
data class VenuePromotionListResponse(
    val venueId: Long,
    val timezone: String,
    val items: List<VenuePromotionDto>,
    val menuCategories: List<VenuePromotionMenuCategoryDto> = emptyList(),
    val menuItems: List<VenuePromotionMenuItemDto> = emptyList(),
)

@Serializable
data class VenuePromotionResponse(
    val promotion: VenuePromotionDto,
)

@Serializable
data class VenuePromotionDto(
    val id: Long,
    val title: String,
    val description: String,
    val terms: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val status: String,
    val templateType: String = "TEXT_ONLY",
    val rule: VenuePromotionRuleDto? = null,
)

@Serializable
data class VenuePromotionRuleDto(
    val id: Long,
    val version: Int,
    val windows: List<VenuePromotionWeekdayWindowDto>,
    val target: VenuePromotionTargetDto? = null,
    val discountPercent: Int? = null,
    val reward: VenuePromotionRewardDto? = null,
    val summary: VenuePromotionRuleSummaryDto? = null,
    val readyForActivation: Boolean,
    val validationIssues: List<String> = emptyList(),
)

@Serializable
data class VenuePromotionWeekdayWindowDto(
    val weekday: Int,
    val startLocal: String,
    val endLocal: String,
)

@Serializable
data class VenuePromotionTargetDto(
    val type: String,
    val menuItemId: Long? = null,
    val menuCategoryId: Long? = null,
    val label: String? = null,
)

@Serializable
data class VenuePromotionRuleMutationRequest(
    val windows: List<VenuePromotionWeekdayWindowDto>,
    val target: VenuePromotionTargetDto,
    val discountPercent: Int? = null,
    val reward: VenuePromotionRewardMutationRequest? = null,
)

@Serializable
data class VenuePromotionRewardMutationRequest(
    val mode: String,
    val fixedMenuItemId: Long? = null,
    val allowlistMenuItemIds: List<Long> = emptyList(),
)

@Serializable
data class VenuePromotionRewardDto(
    val mode: String,
    val fixedItem: VenuePromotionRewardItemDto? = null,
    val allowlist: List<VenuePromotionRewardItemDto> = emptyList(),
    val maxRewardsPerBatch: Int = 1,
)

@Serializable
data class VenuePromotionRewardItemDto(
    val menuItemId: Long,
    val name: String,
)

@Serializable
data class VenuePromotionRuleSummaryDto(
    val schedule: String,
    val trigger: String,
    val reward: String,
    val maximum: String = "Максимум: 1 подарок на заказ",
    val explanation: String =
        "Гость сам выбирает или подтверждает подарок. Подарок автоматически в заказ не добавляется.",
)

@Serializable
data class VenuePromotionMenuCategoryDto(
    val id: Long,
    val name: String,
)

@Serializable
data class VenuePromotionMenuItemDto(
    val id: Long,
    val name: String,
    val categoryId: Long,
)

@Serializable
data class VenuePromotionCreateRequest(
    val title: String,
    val description: String,
    val terms: String? = null,
    val startsAt: String,
    val endsAt: String,
    val templateType: String = "TEXT_ONLY",
    val rule: VenuePromotionRuleMutationRequest? = null,
)

@Serializable
data class VenuePromotionUpdateRequest(
    val title: String,
    val description: String,
    val terms: String? = null,
    val startsAt: String,
    val endsAt: String,
    val templateType: String = "TEXT_ONLY",
    val rule: VenuePromotionRuleMutationRequest? = null,
)

@Serializable
data class VenuePromotionStatusRequest(
    val status: String,
)
