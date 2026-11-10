package com.hookah.platform.backend.miniapp.venue.menu

import com.hookah.platform.backend.api.InvalidInputException

const val MENU_DESCRIPTION_MAX_CODE_POINTS = 500

fun normalizeMenuDescription(value: String?): String? {
    if (value == null) return null
    val points = value.codePoints().toArray()
    if (points.any { it in 0xD800..0xDFFF }) {
        throw InvalidInputException("Описание содержит некорректный Unicode.")
    }
    val normalized =
        buildString {
            points.forEach { point ->
                if (Character.isWhitespace(point) || Character.isSpaceChar(point) || Character.isISOControl(point)) {
                    append(' ')
                } else {
                    appendCodePoint(point)
                }
            }
        }.trim().replace(Regex(" +"), " ")
    if (normalized.codePointCount(0, normalized.length) > MENU_DESCRIPTION_MAX_CODE_POINTS) {
        throw InvalidInputException("Описание: не более 500 символов.")
    }
    return normalized.ifEmpty { null }
}
