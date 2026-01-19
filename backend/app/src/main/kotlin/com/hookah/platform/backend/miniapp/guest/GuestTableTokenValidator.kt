package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.InvalidInputException

private const val TABLE_TOKEN_MAX_LENGTH = 128

fun validateTableToken(rawToken: String?): String {
    val trimmed = rawToken?.trim() ?: throw InvalidInputException("tableToken is required")
    if (trimmed.isEmpty()) {
        throw InvalidInputException("tableToken is required")
    }
    if (trimmed.length > TABLE_TOKEN_MAX_LENGTH) {
        throw InvalidInputException("tableToken length must be <= $TABLE_TOKEN_MAX_LENGTH")
    }
    if (trimmed.any { it.code !in 0x21..0x7E }) {
        throw InvalidInputException("tableToken contains invalid characters")
    }
    return trimmed
}
