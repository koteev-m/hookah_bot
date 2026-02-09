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
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.LoggerFactory
import java.lang.RuntimeException

sealed class TelegramCallResult {
    data class Success(val responseJson: JsonElement?) : TelegramCallResult()

    data class Failure(
        val errorCode: Int?,
        val description: String?,
        val retryAfterSeconds: Int?,
    ) : TelegramCallResult()
}

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
        val payload = buildSendMessagePayload(json, chatId, text, replyMarkup)
        val payloadJson = json.encodeToJsonElement(payload)
        return runCatching {
            when (val result = callMethod("sendMessage", payloadJson)) {
                is TelegramCallResult.Success ->
                    result.responseJson?.let { responseJson ->
                        runCatching { json.decodeFromJsonElement(MessageId.serializer(), responseJson) }
                            .onFailure { throwable ->
                                logger.warn(
                                    "Telegram sendMessage failed: {}",
                                    sanitizeTelegramForLog(throwable.message),
                                )
                                logger.debugTelegramException(throwable) { "Telegram sendMessage decode exception" }
                            }
                            .getOrNull()
                    }
                is TelegramCallResult.Failure -> {
                    val safeDescription = sanitizeTelegramForLog(result.description)
                    logger.warn(
                        "Telegram sendMessage failed: {}{}",
                        safeDescription,
                        result.errorCode?.let { " (code=$it)" } ?: "",
                    )
                    null
                }
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

    suspend fun callMethod(
        method: String,
        payload: JsonElement,
    ): TelegramCallResult {
        val response: TelegramResponse<JsonElement> =
            client.post("$baseUrl/$method") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }.safeBody()
        if (response.ok.not()) {
            return TelegramCallResult.Failure(
                errorCode = response.errorCode,
                description = response.description,
                retryAfterSeconds = response.parameters?.retryAfterSeconds,
            )
        }
        return TelegramCallResult.Success(response.result)
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

    private suspend inline fun <reified T> HttpResponse.safeBody(): T = body()
}

class TelegramApiException(message: String) : RuntimeException(message)

@Serializable
private data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    val parameters: TelegramResponseParameters? = null,
)

@Serializable
private data class TelegramResponseParameters(
    @SerialName("retry_after") val retryAfterSeconds: Int? = null,
)
