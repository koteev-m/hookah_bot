package com.hookah.platform.backend.telegram

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

data class TelegramDownloadedFile(
    val bytes: ByteArray,
    val contentType: ContentType?,
)

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
        parseMode: String? = null,
    ): MessageId? {
        val payload = buildSendMessagePayload(json, chatId, text, replyMarkup, parseMode)
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

    suspend fun downloadFile(fileId: String): TelegramDownloadedFile? {
        val trimmedFileId = fileId.trim()
        if (trimmedFileId.isBlank()) {
            return null
        }
        return runCatching {
            val fileResponse: TelegramResponse<TelegramFileInfo> =
                client.get("$baseUrl/getFile") {
                    parameter("file_id", trimmedFileId)
                }.safeBody()
            if (fileResponse.ok.not()) {
                logger.warn(
                    "Telegram getFile failed: {}{}",
                    sanitizeTelegramForLog(fileResponse.description),
                    fileResponse.errorCode?.let { " (code=$it)" } ?: "",
                )
                return@runCatching null
            }

            val filePath = fileResponse.result?.filePath?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val response = client.get("https://api.telegram.org/file/bot$token/$filePath")
            if (!response.status.isSuccess()) {
                logger.warn("Telegram file download failed: status={}", response.status.value)
                return@runCatching null
            }
            TelegramDownloadedFile(
                bytes = response.body(),
                contentType = response.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it) },
            )
        }.onFailure { throwable ->
            logger.warn("Telegram file download failed: {}", sanitizeTelegramForLog(throwable.message))
            logger.debugTelegramException(throwable) { "Telegram file download exception" }
        }.getOrNull()
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

    suspend fun getChat(chatId: Long): Chat? {
        return runCatching {
            val response: TelegramResponse<Chat> =
                client.get("$baseUrl/getChat") {
                    parameter("chat_id", chatId)
                }.safeBody()
            if (response.ok.not()) {
                val safeDescription = sanitizeTelegramForLog(response.description)
                logger.warn(
                    "Telegram getChat failed: {}{}",
                    safeDescription,
                    response.errorCode?.let { " (code=$it)" } ?: "",
                )
                null
            } else {
                response.result
            }
        }.onFailure { throwable ->
            logger.warn("Telegram getChat failed: {}", sanitizeTelegramForLog(throwable.message))
            logger.debugTelegramException(throwable) { "Telegram getChat exception" }
        }.getOrNull()
    }

    suspend fun sendPhotoBytes(
        chatId: Long,
        photoBytes: ByteArray,
        filename: String,
        caption: String? = null,
        replyMarkup: ReplyMarkup? = null,
    ): Boolean {
        return runCatching {
            val replyMarkupJson = buildReplyMarkupPayload(json, replyMarkup)
            val response: TelegramResponse<JsonObject> =
                client.submitFormWithBinaryData(
                    url = "$baseUrl/sendPhoto",
                    formData =
                        formData {
                            append("chat_id", chatId.toString())
                            caption?.let { append("caption", it) }
                            replyMarkupJson?.let { replyMarkupElement ->
                                append("reply_markup", json.encodeToString(JsonElement.serializer(), replyMarkupElement))
                            }
                            append(
                                key = "photo",
                                value = photoBytes,
                                headers =
                                    Headers.build {
                                        append(HttpHeaders.ContentType, "image/png")
                                        append(
                                            HttpHeaders.ContentDisposition,
                                            "form-data; name=\"photo\"; filename=\"$filename\"",
                                        )
                                    },
                            )
                        },
                ).safeBody()
            if (response.ok.not()) {
                val safeDescription = sanitizeTelegramForLog(response.description)
                logger.warn(
                    "Telegram sendPhoto(bytes) failed: {}{}",
                    safeDescription,
                    response.errorCode?.let { " (code=$it)" } ?: "",
                )
                false
            } else {
                true
            }
        }.onFailure { throwable ->
            logger.warn("Telegram sendPhoto(bytes) failed: {}", sanitizeTelegramForLog(throwable.message))
            logger.debugTelegramException(throwable) { "Telegram sendPhoto(bytes) exception" }
        }.getOrDefault(false)
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

@Serializable
private data class TelegramFileInfo(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_unique_id") val fileUniqueId: String? = null,
    @SerialName("file_size") val fileSize: Int? = null,
    @SerialName("file_path") val filePath: String? = null,
)
