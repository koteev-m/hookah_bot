package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.telegram.StaffCallReason
import java.util.Locale

private const val REASON_MAX_LENGTH = 32
private const val COMMENT_MAX_LENGTH = 500
private val REASON_REGEX = Regex("^[A-Z0-9_]{1,32}$")

fun normalizeStaffCallReason(rawReason: String): StaffCallReason {
    val trimmed = rawReason.trim()
    if (trimmed.isEmpty()) {
        throw InvalidInputException("reason is required")
    }
    val normalized = trimmed.uppercase(Locale.ROOT)
    if (normalized.length !in 1..REASON_MAX_LENGTH) {
        throw InvalidInputException("reason length must be <= $REASON_MAX_LENGTH")
    }
    if (!REASON_REGEX.matches(normalized)) {
        throw InvalidInputException("reason must match ${REASON_REGEX.pattern}")
    }
    return runCatching { StaffCallReason.valueOf(normalized) }
        .getOrElse {
            throw InvalidInputException(
                "reason must be one of ${StaffCallReason.values().joinToString { it.name }}",
            )
        }
}

fun normalizeStaffCallComment(comment: String?): String? {
    val trimmed = comment?.trim().orEmpty()
    if (trimmed.isEmpty()) {
        return null
    }
    if (trimmed.length > COMMENT_MAX_LENGTH) {
        throw InvalidInputException("comment length must be <= $COMMENT_MAX_LENGTH")
    }
    return trimmed
}
