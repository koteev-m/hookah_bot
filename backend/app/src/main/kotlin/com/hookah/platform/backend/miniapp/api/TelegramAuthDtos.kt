package com.hookah.platform.backend.miniapp.api

import kotlinx.serialization.Serializable

@Serializable
data class TelegramAuthRequest(val initData: String)

@Serializable
data class TelegramAuthResponse(
    val token: String,
    val expiresAtEpochSeconds: Long,
    val user: MiniAppUserDto
)

@Serializable
data class MiniAppUserDto(
    val telegramUserId: Long,
    val username: String? = null,
    val firstName: String? = null,
    val lastName: String? = null
)
