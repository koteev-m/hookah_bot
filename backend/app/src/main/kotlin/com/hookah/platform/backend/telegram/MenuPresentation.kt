package com.hookah.platform.backend.telegram

internal fun menuPlainText(
    value: String,
    maxUnits: Int,
): String {
    if (value.length <= maxUnits) return value
    val end = if (value[maxUnits - 1].isHighSurrogate()) maxUnits - 1 else maxUnits
    return value.substring(0, end) + "…"
}

internal fun menuDescriptionLine(
    description: String?,
    maxUnits: Int = 160,
): String = description?.takeIf { it.isNotBlank() }?.let { "\n${menuPlainText(it, maxUnits)}" }.orEmpty()
