package com.hookah.platform.backend.miniapp.venue.menu

import kotlinx.serialization.Serializable

@Serializable
data class VenueMenuResponse(
    val venueId: Long,
    val categories: List<VenueMenuCategoryDto>,
)

@Serializable
data class VenueMenuCategoryDto(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val items: List<VenueMenuItemDto>,
)

@Serializable
data class VenueMenuItemDto(
    val id: Long,
    val categoryId: Long,
    val name: String,
    val priceMinor: Long,
    val currency: String,
    val isAvailable: Boolean,
    val sortOrder: Int,
    val options: List<VenueMenuOptionDto>,
)

@Serializable
data class VenueMenuOptionDto(
    val id: Long,
    val itemId: Long,
    val name: String,
    val priceDeltaMinor: Long,
    val isAvailable: Boolean,
    val sortOrder: Int,
)

@Serializable
data class CreateCategoryRequest(
    val name: String,
)

@Serializable
data class UpdateCategoryRequest(
    val name: String? = null,
)

@Serializable
data class CreateItemRequest(
    val categoryId: Long,
    val name: String,
    val priceMinor: Long,
    val currency: String = "RUB",
    val isAvailable: Boolean = true,
)

@Serializable
data class UpdateItemRequest(
    val categoryId: Long? = null,
    val name: String? = null,
    val priceMinor: Long? = null,
    val currency: String? = null,
    val isAvailable: Boolean? = null,
)

@Serializable
data class AvailabilityRequest(
    val isAvailable: Boolean,
)

@Serializable
data class ReorderCategoriesRequest(
    val categoryIds: List<Long>,
)

@Serializable
data class ReorderItemsRequest(
    val categoryId: Long,
    val itemIds: List<Long>,
)

@Serializable
data class CreateOptionRequest(
    val itemId: Long,
    val name: String,
    val priceDeltaMinor: Long = 0,
    val isAvailable: Boolean = true,
)

@Serializable
data class UpdateOptionRequest(
    val name: String? = null,
    val priceDeltaMinor: Long? = null,
    val isAvailable: Boolean? = null,
)
