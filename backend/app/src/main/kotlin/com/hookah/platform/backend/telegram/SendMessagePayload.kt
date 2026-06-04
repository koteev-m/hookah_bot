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
    @SerialName("parse_mode") val parseMode: String? = null,
    @SerialName("reply_markup") val replyMarkup: JsonElement? = null,
)

@Serializable
data class EditMessageTextPayload(
    @SerialName("chat_id") val chatId: Long,
    @SerialName("message_id") val messageId: Long,
    val text: String,
    @SerialName("reply_markup") val replyMarkup: JsonElement? = null,
)

@Serializable
data class SendPhotoPayload(
    @SerialName("chat_id") val chatId: Long,
    val photo: String,
    val caption: String? = null,
    @SerialName("reply_markup") val replyMarkup: JsonElement? = null,
)

@Serializable
data class SendDocumentPayload(
    @SerialName("chat_id") val chatId: Long,
    val document: String,
    val caption: String? = null,
    @SerialName("reply_markup") val replyMarkup: JsonElement? = null,
)

fun buildReplyMarkupPayload(
    json: Json,
    replyMarkup: ReplyMarkup?,
): JsonElement? =
    when (replyMarkup) {
        is ReplyKeyboardMarkup -> json.encodeToJsonElement(ReplyKeyboardMarkup.serializer(), replyMarkup)
        is ReplyKeyboardRemove -> json.encodeToJsonElement(ReplyKeyboardRemove.serializer(), replyMarkup)
        is InlineKeyboardMarkup -> json.encodeToJsonElement(InlineKeyboardMarkup.serializer(), replyMarkup)
        null -> null
    }

fun buildSendMessagePayload(
    json: Json,
    chatId: Long,
    text: String,
    replyMarkup: ReplyMarkup?,
    parseMode: String? = null,
): SendMessagePayload {
    return SendMessagePayload(
        chatId = chatId,
        text = text,
        parseMode = parseMode,
        replyMarkup = buildReplyMarkupPayload(json, replyMarkup),
    )
}

fun buildEditMessageTextPayload(
    json: Json,
    chatId: Long,
    messageId: Long,
    text: String,
    replyMarkup: ReplyMarkup?,
): EditMessageTextPayload =
    EditMessageTextPayload(
        chatId = chatId,
        messageId = messageId,
        text = text,
        replyMarkup = buildReplyMarkupPayload(json, replyMarkup),
    )

fun buildSendPhotoPayload(
    json: Json,
    chatId: Long,
    photo: String,
    caption: String?,
    replyMarkup: ReplyMarkup?,
): SendPhotoPayload =
    SendPhotoPayload(
        chatId = chatId,
        photo = photo,
        caption = caption,
        replyMarkup = buildReplyMarkupPayload(json, replyMarkup),
    )

fun buildSendDocumentPayload(
    json: Json,
    chatId: Long,
    document: String,
    caption: String?,
    replyMarkup: ReplyMarkup?,
): SendDocumentPayload =
    SendDocumentPayload(
        chatId = chatId,
        document = document,
        caption = caption,
        replyMarkup = buildReplyMarkupPayload(json, replyMarkup),
    )
