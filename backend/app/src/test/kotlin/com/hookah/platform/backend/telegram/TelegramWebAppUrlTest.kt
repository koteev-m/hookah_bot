package com.hookah.platform.backend.telegram

import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramWebAppUrlTest {
    @Test
    fun `build url without query`() {
        val url = buildWebAppUrl("https://example.com/miniapp/", mapOf("table_token" to "abc"))
        assertEquals("https://example.com/miniapp/?table_token=abc", url)
    }

    @Test
    fun `build url with existing query`() {
        val url =
            buildWebAppUrl(
                "https://example.com/miniapp/?screen=menu",
                mapOf("table_token" to "abc"),
            )
        assertEquals("https://example.com/miniapp/?screen=menu&table_token=abc", url)
    }

    @Test
    fun `build url with fragment`() {
        val url =
            buildWebAppUrl(
                "https://example.com/miniapp/#/route",
                mapOf("table_token" to "abc", "screen" to "order"),
            )
        assertEquals("https://example.com/miniapp/?table_token=abc&screen=order#/route", url)
    }

    @Test
    fun `build url encodes special characters`() {
        val token = "a&b=c+1"
        val url = buildWebAppUrl("https://example.com/miniapp/", mapOf("table_token" to token))
        assertEquals("https://example.com/miniapp/?table_token=a%26b%3Dc%2B1", url)
    }

    @Test
    fun `build telegram start url`() {
        val url = buildTelegramStartUrl("hookah_bot", "abc123")
        assertEquals("https://t.me/hookah_bot?start=abc123", url)
    }

    @Test
    fun `build telegram start url normalizes username and encodes param`() {
        val url = buildTelegramStartUrl("@hookah_bot", "a+b c")
        assertEquals("https://t.me/hookah_bot?start=a%2Bb+c", url)
    }
}
