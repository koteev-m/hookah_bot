package com.hookah.platform.backend.miniapp.venue.tables

import kotlinx.serialization.Serializable

@Serializable
data class VenueTablesResponse(
    val tables: List<VenueTableDto>
)

@Serializable
data class VenueTableDto(
    val tableId: Long,
    val tableNumber: Int,
    val tableLabel: String,
    val isActive: Boolean,
    val activeTokenIssuedAt: String?
)

@Serializable
data class VenueTableBatchCreateRequest(
    val count: Int,
    val startNumber: Int? = null,
    val prefix: String? = null
)

@Serializable
data class VenueTableBatchCreateResponse(
    val count: Int,
    val tables: List<VenueTableCreatedDto>
)

@Serializable
data class VenueTableCreatedDto(
    val tableId: Long,
    val tableNumber: Int,
    val tableLabel: String,
    val activeTokenIssuedAt: String
)

@Serializable
data class VenueTableTokenRotateResponse(
    val tableId: Long,
    val tableNumber: Int,
    val tableLabel: String,
    val activeTokenIssuedAt: String
)

@Serializable
data class VenueTableRotateTokensRequest(
    val tableIds: List<Long>? = null
)

@Serializable
data class VenueTableRotateTokensResponse(
    val rotatedCount: Int,
    val tableIds: List<Long>
)
