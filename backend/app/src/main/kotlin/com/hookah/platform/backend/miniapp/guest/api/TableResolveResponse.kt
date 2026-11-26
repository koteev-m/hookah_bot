package com.hookah.platform.backend.miniapp.guest.api

import kotlinx.serialization.Serializable

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class TableResolveResponse(
    val venueId: Long,
    val venueName: String,
    val tableId: Long,
    val tableSessionId: Long,
    val tableSessionStatus: String,
    val tableSessionActive: Boolean,
    val tableSessionInactiveReason: String? = null,
    val tableNumber: String,
    val venueStatus: String,
    val subscriptionStatus: String,
    val available: Boolean,
    val unavailableReason: String?,
    val exitEventId: String? = null,
    val visitGeneration: String? = null,
    @kotlinx.serialization.EncodeDefault val visitProtocol: Int = 1,
)

@Serializable
data class TableRejoinRequest(
    val tableToken: String,
    val tableSessionId: Long,
    val exitEventId: String,
)

@Serializable
data class TableSessionEndRequest(
    val tableToken: String,
    val tableSessionId: Long,
    val expectedVisitGeneration: String? = null,
)

@Serializable
data class TableSessionEndResponse(
    val ended: Boolean,
    val tableSessionId: Long,
    val blockedReason: String?,
    val message: String?,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class TableRestoreResponse(
    val context: RestoredTableContextResponse?,
    val unavailableReason: String? = null,
    @kotlinx.serialization.EncodeDefault val visitProtocol: Int = 1,
)

@Serializable
data class RestoredTableContextResponse(
    val tableToken: String,
    val tabId: Long?,
    val venueId: Long,
    val venueName: String,
    val tableId: Long,
    val tableSessionId: Long,
    val tableSessionStatus: String,
    val tableSessionActive: Boolean,
    val tableSessionInactiveReason: String? = null,
    val tableNumber: String,
    val venueStatus: String,
    val subscriptionStatus: String,
    val available: Boolean,
    val unavailableReason: String?,
    val visitGeneration: String? = null,
)
