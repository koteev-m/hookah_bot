package com.hookah.platform.backend.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class SendMessagePayload(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
    @SerialName("reply_markup") val replyMarkup: JsonElement? = null,
)

fun buildSendMessagePayload(
    json: Json,
    chatId: Long,
    text: String,
    replyMarkup: ReplyMarkup?,
): SendMessagePayload {
    val markup: JsonElement? =
        when (replyMarkup) {
            is ReplyKeyboardMarkup -> json.encodeToJsonElement(ReplyKeyboardMarkup.serializer(), replyMarkup)
            is InlineKeyboardMarkup -> json.encodeToJsonElement(InlineKeyboardMarkup.serializer(), replyMarkup)
            null -> null
        }
    return SendMessagePayload(chatId = chatId, text = text, replyMarkup = markup)
}
