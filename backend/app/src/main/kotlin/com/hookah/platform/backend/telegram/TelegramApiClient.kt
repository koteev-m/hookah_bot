package com.hookah.platform.backend.telegram

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.LoggerFactory
import java.lang.RuntimeException

class TelegramApiClient(
    private val token: String,
    private val client: HttpClient,
    private val json: Json,
) {
    private val logger = LoggerFactory.getLogger(TelegramApiClient::class.java)
    private val baseUrl = "https://api.telegram.org/bot$token"

    suspend fun getUpdates(
        offset: Long?,
        timeoutSeconds: Int,
    ): List<TelegramUpdate> {
        val response: TelegramResponse<List<TelegramUpdate>> =
            client.get("$baseUrl/getUpdates") {
                offset?.let { parameter("offset", it) }
                parameter("timeout", timeoutSeconds)
            }.safeBody()
        if (response.ok.not()) {
            val safeDescription = sanitizeTelegramForLog(response.description)
            logger.warn("Telegram getUpdates failed: {}", safeDescription)
            throw TelegramApiException(safeDescription)
        }
        return response.result ?: emptyList()
    }

    suspend fun sendMessage(
        chatId: Long,
        text: String,
        replyMarkup: ReplyMarkup? = null,
    ): MessageId? {
        val payload = buildPayload(chatId, text, replyMarkup)
        return runCatching {
            val response: TelegramResponse<MessageId> =
                client.post("$baseUrl/sendMessage") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }.safeBody()
            if (response.ok.not()) {
                val safeDescription = sanitizeTelegramForLog(response.description)
                logger.warn(
                    "Telegram sendMessage failed: {}{}",
                    safeDescription,
                    response.errorCode?.let { " (code=$it)" } ?: "",
                )
                null
            } else {
                response.result
            }
        }.onFailure { throwable ->
            logger.warn("Telegram sendMessage failed: {}", sanitizeTelegramForLog(throwable.message))
            logger.debugTelegramException(throwable) { "Telegram sendMessage exception" }
        }.getOrNull()
    }

    suspend fun answerCallbackQuery(callbackQueryId: String) {
        runCatching {
            val response: TelegramResponse<Boolean> =
                client.post("$baseUrl/answerCallbackQuery") {
                    contentType(ContentType.Application.Json)
                    setBody(mapOf("callback_query_id" to callbackQueryId))
                }.safeBody()
            if (response.ok.not()) {
                val safeDescription = sanitizeTelegramForLog(response.description)
                logger.warn(
                    "answerCallbackQuery failed: {}{}",
                    safeDescription,
                    response.errorCode?.let { " (code=$it)" } ?: "",
                )
            }
        }.onFailure { throwable ->
            logger.warn("answerCallbackQuery failed: {}", sanitizeTelegramForLog(throwable.message))
            logger.debugTelegramException(throwable) { "answerCallbackQuery exception" }
        }
    }

    fun close() {
        client.close()
    }

    suspend fun getChatMember(
        chatId: Long,
        userId: Long,
    ): ChatMember? {
        return runCatching {
            val response: TelegramResponse<ChatMember> =
                client.get("$baseUrl/getChatMember") {
                    parameter("chat_id", chatId)
                    parameter("user_id", userId)
                }.safeBody()
            if (response.ok.not()) {
                val safeDescription = sanitizeTelegramForLog(response.description)
                logger.warn(
                    "Telegram getChatMember failed: {}{}",
                    safeDescription,
                    response.errorCode?.let { " (code=$it)" } ?: "",
                )
                null
            } else {
                response.result
            }
        }.onFailure { throwable ->
            logger.warn("Telegram getChatMember failed: {}", sanitizeTelegramForLog(throwable.message))
            logger.debugTelegramException(throwable) { "Telegram getChatMember exception" }
        }.getOrNull()
    }

    private fun buildPayload(
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

    private suspend inline fun <reified T> HttpResponse.safeBody(): T = body()
}

class TelegramApiException(message: String) : RuntimeException(message)

@Serializable
private data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
)

@Serializable
private data class SendMessagePayload(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
    @SerialName("reply_markup") val replyMarkup: JsonElement? = null,
)
