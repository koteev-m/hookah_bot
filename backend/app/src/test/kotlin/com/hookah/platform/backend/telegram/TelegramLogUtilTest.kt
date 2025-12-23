package com.hookah.platform.backend.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class TelegramLogUtilTest {
    @Test
    fun `redacts token in url`() {
        val input = "https://api.telegram.org/bot123456:ABCdef_-123/sendMessage"
        val sanitized = sanitizeTelegramForLog(input)
        assertFalse(sanitized.contains("123456:ABCdef_-123"))
        assertFalse(sanitized.contains("bot123456:ABCdef_-123"))
        assertTrue(sanitized.contains("<bot_token_redacted>") || sanitized.contains("bot<redacted>"))
    }

    @Test
    fun `redacts bare token`() {
        val input = "123456:ABCdef_-123"
        val sanitized = sanitizeTelegramForLog(input)
        assertEquals("<bot_token_redacted>", sanitized)
    }

    @Test
    fun `redaction happens before truncation`() {
        val token = "123456:ABCdef_-123"
        val input = "prefix $token suffix" + "x".repeat(300)
        val sanitized = sanitizeTelegramForLog(input, maxLen = 30)
        assertFalse(sanitized.contains(token))
        assertTrue(sanitized.contains("<bot_token_redacted>"))
        assertTrue(sanitized.length <= 30)
    }

    @Test
    fun `stacktrace redacts token`() {
        val token = "123456:ABCdef_-123"
        val exception = RuntimeException("https://api.telegram.org/bot$token/sendMessage")
        val trace = telegramStackTraceForLog(exception)
        assertFalse(trace.contains(token))
        assertTrue(trace.contains("<bot_token_redacted>") || trace.contains("bot<redacted>"))
    }

    @Test
    fun `stacktrace respects max length`() {
        val longMessage = "token 123456:ABCdef_-123 " + "x".repeat(10_000)
        val exception = RuntimeException(longMessage)
        val trace = telegramStackTraceForLog(exception, maxLen = 200)
        assertTrue(trace.length <= 200)
    }

    @Test
    fun `summarizeJsonKeysForLog is bounded and redacts`() {
        val longKey = "bot123456:ABCdef_-123" + "x".repeat(10_000)
        val keys = (1..30).associate { "key$it$longKey" to JsonPrimitive("v") }
        val obj = JsonObject(keys)
        val summary = summarizeJsonKeysForLog(
            obj = obj,
            maxKeys = 20,
            maxKeyLen = 40,
            maxTotalLen = 200
        )
        assertTrue(summary.length <= 200)
        assertTrue(summary.isNotEmpty())
        assertFalse(summary.contains("\n") || summary.contains("\t"))
        assertFalse(summary.contains("bot123456:ABCdef_-123"))
        assertTrue(summary.contains("<bot_token_redacted>") || summary.contains("bot<redacted>"))
    }

    @Test
    fun `sanitizeTelegramForLog strips control chars and redacts token`() {
        val input = "line1\n\tbot123456:ABCdef_-123 line2"
        val sanitized = sanitizeTelegramForLog(input)
        assertFalse(sanitized.contains("\n") || sanitized.contains("\t"))
        assertFalse(sanitized.contains("bot123456:ABCdef_-123"))
        assertTrue(sanitized.contains("<bot_token_redacted>") || sanitized.contains("bot<redacted>"))
    }

    @Test
    fun `sanitizeTelegramForLog redacts token ending with dash`() {
        val input = "123456:ABCdef_-123-"
        val sanitized = sanitizeTelegramForLog(input)
        assertFalse(sanitized.contains("123456:ABCdef_-123-"))
        assertTrue(sanitized.contains("<bot_token_redacted>"))
    }

    @Test
    fun `sanitizeTelegramForLog redacts uppercase bot prefix`() {
        val input = "BOT123456:ABCdef_-123"
        val sanitized = sanitizeTelegramForLog(input)
        assertFalse(sanitized.contains("BOT123456:ABCdef_-123"))
        assertTrue(sanitized.contains("<bot_token_redacted>") || sanitized.contains("bot<redacted>"))
    }

    @Test
    fun `summarizeJsonKeysForLog avoids partial token leaks near boundary`() {
        val prefix = "a".repeat(30)
        val token = "bot123456:ABCdef_-123"
        val key = prefix + token + "zzz"
        val obj = JsonObject(mapOf(key to JsonPrimitive("v")))
        val summary = summarizeJsonKeysForLog(obj, maxKeys = 5, maxKeyLen = 40, maxTotalLen = 200)
        assertTrue(summary.length <= 200)
        assertFalse(summary.contains("\n") || summary.contains("\t"))
        assertFalse(summary.contains("bot123456:ABCdef_-123"))
        assertFalse(summary.contains("123456:ABCdef_-123"))

        val wider = summarizeJsonKeysForLog(obj, maxKeys = 5, maxKeyLen = 100, maxTotalLen = 200)
        assertTrue(wider.contains("<bot_token_redacted>") || wider.contains("bot<redacted>"))
    }

    @Test
    fun `summarizeJsonKeysForLog does not leave trailing comma when space is tiny`() {
        val obj = JsonObject(mapOf("k1" to JsonPrimitive("v1"), "k2" to JsonPrimitive("v2")))
        val summary = summarizeJsonKeysForLog(obj, maxKeys = 5, maxKeyLen = 2, maxTotalLen = 3)
        assertTrue(summary.length <= 3)
        assertFalse(summary.endsWith(","))
    }

    @Test
    fun `summarizeJsonKeysForLog skips empty sanitized keys`() {
        val obj = JsonObject(
            mapOf(
                "\n\t" to JsonPrimitive("v"),
                "ok" to JsonPrimitive("v2")
            )
        )
        val summary = summarizeJsonKeysForLog(obj, maxKeys = 5, maxKeyLen = 5, maxTotalLen = 10)
        assertFalse(summary.contains("\n") || summary.contains("\t"))
        assertFalse(summary.endsWith(","))
        assertTrue(summary.isNotBlank())
    }
}
