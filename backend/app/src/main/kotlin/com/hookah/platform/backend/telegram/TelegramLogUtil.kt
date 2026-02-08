package com.hookah.platform.backend.telegram

import kotlinx.serialization.json.JsonObject
import org.slf4j.Logger

fun sanitizeForLog(
    text: String?,
    maxLen: Int = 200,
): String {
    return (text ?: "")
        .replace(CONTROL_CHARS_REGEX, " ")
        .trim()
        .take(maxLen)
}

fun sanitizeTelegramForLog(
    text: String?,
    maxLen: Int = 200,
): String {
    val normalized =
        (text ?: "")
            .replace(CONTROL_CHARS_REGEX, " ")
    val redacted = redactTelegramTokens(normalized)
    return redacted.trim().take(maxLen)
}

fun redactTelegramTokens(text: String): String {
    return text
        .replace(TOKEN_REGEX, "<bot_token_redacted>")
        .replace(BOT_TOKEN_REGEX, "bot<redacted>")
}

fun telegramStackTraceForLog(
    t: Throwable,
    maxLen: Int = 8000,
): String {
    val writer = java.io.StringWriter()
    java.io.PrintWriter(writer).use { pw ->
        t.printStackTrace(pw)
    }
    val redacted = redactTelegramTokens(writer.toString())
    return redacted.take(maxLen)
}

inline fun Logger.debugTelegramException(
    t: Throwable,
    maxMessageLen: Int = 500,
    message: () -> String,
) {
    if (!isDebugEnabled) return
    val safeMessage = sanitizeTelegramForLog(message(), maxLen = maxMessageLen)
    debug("{}: {}", safeMessage, telegramStackTraceForLog(t))
}

/**
 * Builds a bounded, Telegram-safe summary of JSON object keys for debug logging.
 *
 * Rationale:
 * - Each key is sanitized *before* truncation to avoid partial token leaks if a token
 *   starts near the truncation boundary.
 * - sampleLen = maxKeyLen + 120 provides a security buffer to catch complete token
 *   patterns even when maxKeyLen is small, while staying bounded (capped at 512).
 * - Bounds: maxKeys limits how many keys we inspect, maxKeyLen caps the stored length
 *   per key, and maxTotalLen caps the entire summary string.
 */
fun summarizeJsonKeysForLog(
    obj: JsonObject,
    maxKeys: Int = 20,
    maxKeyLen: Int = 40,
    maxTotalLen: Int = 200,
): String {
    val builder = StringBuilder()
    var appended = 0
    val sampleLen = (maxKeyLen + 120).coerceAtMost(512)
    for (key in obj.keys.asSequence().take(maxKeys)) {
        if (builder.length >= maxTotalLen) break
        val keySample = sanitizeTelegramForLog(key.take(sampleLen), maxLen = sampleLen).trim()
        if (keySample.isEmpty()) continue
        val safeKey = keySample.take(maxKeyLen)
        if (safeKey.isEmpty()) continue

        val availableForKey = maxTotalLen - builder.length - if (builder.isNotEmpty()) 1 else 0
        if (availableForKey <= 0) break

        val keyPart = safeKey.take(availableForKey)
        if (keyPart.isEmpty()) break

        if (builder.isNotEmpty()) {
            builder.append(',')
        }
        builder.append(keyPart)
        appended++
    }
    val more = (obj.size - appended).coerceAtLeast(0)
    if (more > 0 && builder.length < maxTotalLen) {
        val suffix = "...(+$more more)"
        val available = maxTotalLen - builder.length
        builder.append(suffix.take(available))
    }
    return sanitizeTelegramForLog(builder.toString(), maxLen = maxTotalLen)
}

private val CONTROL_CHARS_REGEX = Regex("[\\r\\n\\t]")
private val TOKEN_REGEX = Regex("(?<![A-Za-z0-9_-])\\d{5,}:[A-Za-z0-9_-]{10,}(?![A-Za-z0-9_-])")
private val BOT_TOKEN_REGEX = Regex("(?i)bot\\d{5,}:[A-Za-z0-9_-]{10,}")
