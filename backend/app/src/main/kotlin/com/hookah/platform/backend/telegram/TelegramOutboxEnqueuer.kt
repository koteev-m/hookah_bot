package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TelegramOutboxEnqueuer(
    private val repository: TelegramOutboxRepository,
    private val json: Json,
) {
    suspend fun enqueueSendMessage(
        chatId: Long,
        text: String,
        replyMarkup: ReplyMarkup? = null,
        parseMode: String? = null,
        dedupeKey: String? = null,
    ) {
        val payload = buildSendMessagePayload(json, chatId, text, replyMarkup, parseMode)
        repository.enqueue(
            chatId = chatId,
            method = "sendMessage",
            payloadJson = json.encodeToString(SendMessagePayload.serializer(), payload),
            dedupeKey = dedupeKey,
        )
    }

    suspend fun enqueueEditMessageText(
        chatId: Long,
        messageId: Long,
        text: String,
        replyMarkup: ReplyMarkup? = null,
    ) {
        val payload = buildEditMessageTextPayload(json, chatId, messageId, text, replyMarkup)
        repository.enqueue(
            chatId = chatId,
            method = "editMessageText",
            payloadJson = json.encodeToString(EditMessageTextPayload.serializer(), payload),
        )
    }

    suspend fun enqueueSendPhoto(
        chatId: Long,
        photo: String,
        caption: String? = null,
        replyMarkup: ReplyMarkup? = null,
    ) {
        val payload = buildSendPhotoPayload(json, chatId, photo, caption, replyMarkup)
        repository.enqueue(
            chatId = chatId,
            method = "sendPhoto",
            payloadJson = json.encodeToString(SendPhotoPayload.serializer(), payload),
        )
    }

    suspend fun enqueueSendDocument(
        chatId: Long,
        document: String,
        caption: String? = null,
        replyMarkup: ReplyMarkup? = null,
    ) {
        val payload = buildSendDocumentPayload(json, chatId, document, caption, replyMarkup)
        repository.enqueue(
            chatId = chatId,
            method = "sendDocument",
            payloadJson = json.encodeToString(SendDocumentPayload.serializer(), payload),
        )
    }

    suspend fun enqueueAnswerCallbackQuery(
        chatId: Long,
        callbackQueryId: String,
        text: String? = null,
        showAlert: Boolean = false,
    ) {
        val payload: JsonObject =
            buildJsonObject {
                put("callback_query_id", callbackQueryId)
                text?.takeIf { it.isNotBlank() }?.let { put("text", it) }
                if (showAlert) {
                    put("show_alert", true)
                }
            }
        repository.enqueue(
            chatId = chatId,
            method = "answerCallbackQuery",
            payloadJson = json.encodeToString(JsonObject.serializer(), payload),
        )
    }
}
