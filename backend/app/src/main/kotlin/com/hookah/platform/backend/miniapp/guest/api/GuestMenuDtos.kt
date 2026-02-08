package com.hookah.platform.backend.miniapp.guest.api

import kotlinx.serialization.Serializable

@Serializable
data class MenuResponse(
    val venueId: Long,
    val categories: List<MenuCategoryDto>,
)

@Serializable
data class MenuCategoryDto(
    val id: Long,
    val name: String,
    val items: List<MenuItemDto>,
)

@Serializable
data class MenuItemDto(
    val id: Long,
    val name: String,
    val priceMinor: Long,
    val currency: String,
    val isAvailable: Boolean,
)
