package com.hookah.platform.backend.miniapp.guest.api

import kotlinx.serialization.Serializable

@Serializable
data class GuestTabDto(
    val id: Long,
    val tableSessionId: Long,
    val type: String,
    val ownerUserId: Long?,
    val status: String,
)

@Serializable
data class GuestTabsResponse(
    val tabs: List<GuestTabDto>,
)

@Serializable
data class CreatePersonalTabRequest(
    val tableSessionId: Long,
)

@Serializable
data class CreateSharedTabRequest(
    val tableSessionId: Long,
)

@Serializable
data class GuestTabResponse(
    val tab: GuestTabDto,
)

@Serializable
data class CreateTabInviteRequest(
    val tableSessionId: Long,
    val ttlSeconds: Long? = null,
)

@Serializable
data class CreateTabInviteResponse(
    val tabId: Long,
    val token: String,
    val expiresAtEpochSeconds: Long,
)

@Serializable
data class JoinTabRequest(
    val tableSessionId: Long,
    val token: String,
    val consent: Boolean,
)
