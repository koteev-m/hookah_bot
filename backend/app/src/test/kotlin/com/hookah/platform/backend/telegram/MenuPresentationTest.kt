package com.hookah.platform.backend.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MenuPresentationTest {
    @Test
    fun `descriptions are plain text and bounded without cutting an emoji surrogate pair`() {
        val unsafe = "<b>Манго & мята</b> _сырой текст_"
        assertEquals("\n$unsafe", menuDescriptionLine(unsafe))
        assertEquals("", menuDescriptionLine(null))
        assertEquals("", menuDescriptionLine(" "))
        val text = menuPlainText("🍋".repeat(2500), 3900)
        assertTrue(text.length < 4096)
        assertFalse(text.dropLast(1).last().isHighSurrogate())
        assertEquals("…", text.takeLast(1))
        assertTrue(menuDescriptionLine("🍋".repeat(500), 100).length <= 102)
    }

    @Test
    fun `featured buttons retain ordinary item identity and category navigation`() {
        val keyboard =
            TelegramKeyboards.inlineBotMenuCategories(
                categories = listOf(50L to "Напитки"),
                featuredItems = listOf(Triple(50L, 100L, "Чай"), Triple(50L, 101L, "Морс")),
            )
        assertEquals(
            listOf("bot_menu_item:50:100", "bot_menu_item:50:101", "bot_menu_category:50"),
            keyboard.inlineKeyboard.flatten().map { it.callbackData },
        )
    }
}
