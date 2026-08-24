package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection

enum class TelegramOutboxEnqueueOutcome {
    ENQUEUED,
    SKIPPED_TRAFFIC_POLICY,
}

class TelegramOutboxEnqueuer(
    private val repository: TelegramOutboxRepository,
    private val json: Json,
    private val trafficPolicy: TelegramTrafficPolicy,
) {
    suspend fun enqueueSendMessage(
        chatId: Long,
        text: String,
        replyMarkup: ReplyMarkup? = null,
        parseMode: String? = null,
        dedupeKey: String? = null,
    ): TelegramOutboxEnqueueOutcome {
        if (!trafficPolicy.allowsOutboundChat(chatId)) {
            return TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY
        }
        val payload = buildSendMessagePayload(json, chatId, text, replyMarkup, parseMode)
        repository.enqueue(
            chatId = chatId,
            method = "sendMessage",
            payloadJson = json.encodeToString(SendMessagePayload.serializer(), payload),
            dedupeKey = dedupeKey,
        )
        return TelegramOutboxEnqueueOutcome.ENQUEUED
    }

    fun enqueueBookingSendMessageInTransaction(
        connection: Connection,
        chatId: Long,
        text: String,
        replyMarkup: ReplyMarkup? = null,
        parseMode: String? = null,
        dedupeKey: String? = null,
    ): TelegramOutboxEnqueueOutcome {
        if (!trafficPolicy.allowsOutboundChat(chatId)) {
            return TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY
        }
        val payload = buildSendMessagePayload(json, chatId, text, replyMarkup, parseMode)
        repository.enqueueStrictBookingOnConnection(
            connection = connection,
            chatId = chatId,
            method = "sendMessage",
            payloadJson = json.encodeToString(SendMessagePayload.serializer(), payload),
            dedupeKey = dedupeKey,
        )
        return TelegramOutboxEnqueueOutcome.ENQUEUED
    }

    suspend fun enqueueEditMessageText(
        chatId: Long,
        messageId: Long,
        text: String,
        replyMarkup: ReplyMarkup? = null,
        dedupeKey: String? = null,
    ): TelegramOutboxEnqueueOutcome {
        if (!trafficPolicy.allowsOutboundChat(chatId)) {
            return TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY
        }
        val payload = buildEditMessageTextPayload(json, chatId, messageId, text, replyMarkup)
        repository.enqueue(
            chatId = chatId,
            method = "editMessageText",
            payloadJson = json.encodeToString(EditMessageTextPayload.serializer(), payload),
            dedupeKey = dedupeKey,
        )
        return TelegramOutboxEnqueueOutcome.ENQUEUED
    }

    suspend fun enqueueSendPhoto(
        chatId: Long,
        photo: String,
        caption: String? = null,
        replyMarkup: ReplyMarkup? = null,
    ): TelegramOutboxEnqueueOutcome {
        if (!trafficPolicy.allowsOutboundChat(chatId)) {
            return TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY
        }
        val payload = buildSendPhotoPayload(json, chatId, photo, caption, replyMarkup)
        repository.enqueue(
            chatId = chatId,
            method = "sendPhoto",
            payloadJson = json.encodeToString(SendPhotoPayload.serializer(), payload),
        )
        return TelegramOutboxEnqueueOutcome.ENQUEUED
    }

    suspend fun enqueueSendDocument(
        chatId: Long,
        document: String,
        caption: String? = null,
        replyMarkup: ReplyMarkup? = null,
    ): TelegramOutboxEnqueueOutcome {
        if (!trafficPolicy.allowsOutboundChat(chatId)) {
            return TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY
        }
        val payload = buildSendDocumentPayload(json, chatId, document, caption, replyMarkup)
        repository.enqueue(
            chatId = chatId,
            method = "sendDocument",
            payloadJson = json.encodeToString(SendDocumentPayload.serializer(), payload),
        )
        return TelegramOutboxEnqueueOutcome.ENQUEUED
    }

    suspend fun enqueueAnswerCallbackQuery(
        chatId: Long,
        callbackQueryId: String,
        text: String? = null,
        showAlert: Boolean = false,
    ): TelegramOutboxEnqueueOutcome {
        if (!trafficPolicy.allowsOutboundChat(chatId)) {
            return TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY
        }
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
        return TelegramOutboxEnqueueOutcome.ENQUEUED
    }
}
