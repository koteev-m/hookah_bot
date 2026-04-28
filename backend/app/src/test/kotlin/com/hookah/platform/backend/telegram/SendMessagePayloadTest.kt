package com.hookah.platform.backend.telegram

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SendMessagePayloadTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `build payload with reply keyboard remove`() {
        val payload =
            buildSendMessagePayload(
                json = json,
                chatId = 123L,
                text = "text",
                replyMarkup = ReplyKeyboardRemove(removeKeyboard = true),
            )

        val encoded = json.encodeToString(SendMessagePayload.serializer(), payload)
        assertEquals(
            """{"chat_id":123,"text":"text","reply_markup":{"remove_keyboard":true}}""",
            encoded,
        )
    }

    @Test
    fun `build edit payload with inline keyboard`() {
        val payload =
            buildEditMessageTextPayload(
                json = json,
                chatId = 123L,
                messageId = 77L,
                text = "updated",
                replyMarkup =
                    InlineKeyboardMarkup(
                        inlineKeyboard =
                            listOf(
                                listOf(
                                    InlineKeyboardButton(
                                        text = "OK",
                                        callbackData = "ok",
                                    ),
                                ),
                            ),
                    ),
            )

        val encoded = json.encodeToString(EditMessageTextPayload.serializer(), payload)
        assertEquals(
            """{"chat_id":123,"message_id":77,"text":"updated","reply_markup":{"inline_keyboard":[[{"text":"OK","callback_data":"ok"}]]}}""",
            encoded,
        )
    }

    @Test
    fun `build send photo payload with caption and inline keyboard`() {
        val payload =
            buildSendPhotoPayload(
                json = json,
                chatId = 123L,
                photo = "https://example.com/menu.png",
                caption = "Меню",
                replyMarkup =
                    InlineKeyboardMarkup(
                        inlineKeyboard =
                            listOf(
                                listOf(
                                    InlineKeyboardButton(
                                        text = "Назад",
                                        callbackData = "back",
                                    ),
                                ),
                            ),
                    ),
            )

        val encoded = json.encodeToString(SendPhotoPayload.serializer(), payload)
        assertEquals(
            """{"chat_id":123,"photo":"https://example.com/menu.png","caption":"Меню","reply_markup":{"inline_keyboard":[[{"text":"Назад","callback_data":"back"}]]}}""",
            encoded,
        )
    }
}
