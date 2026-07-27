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
    val discountPercent: Int,
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
