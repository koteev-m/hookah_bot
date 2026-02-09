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
    ) {
        val payload = buildSendMessagePayload(json, chatId, text, replyMarkup)
        repository.enqueue(
            chatId = chatId,
            method = "sendMessage",
            payloadJson = json.encodeToString(SendMessagePayload.serializer(), payload),
        )
    }

    suspend fun enqueueAnswerCallbackQuery(
        chatId: Long,
        callbackQueryId: String,
    ) {
        val payload: JsonObject =
            buildJsonObject {
                put("callback_query_id", callbackQueryId)
            }
        repository.enqueue(
            chatId = chatId,
            method = "answerCallbackQuery",
            payloadJson = json.encodeToString(JsonObject.serializer(), payload),
        )
    }
}
