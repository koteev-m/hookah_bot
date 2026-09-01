package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.maintenance.StagingMaintenancePolicy
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.lang.RuntimeException

sealed class TelegramCallResult {
    data class Success(val responseJson: JsonElement?) : TelegramCallResult()

    data class Failure(
        val errorCode: Int?,
        val description: String?,
        val retryAfterSeconds: Int?,
        val origin: Origin = Origin.BOT_API,
    ) : TelegramCallResult() {
        enum class Origin {
            BOT_API,
            LOCAL_ENVELOPE_VALIDATION,
        }
    }

    data object TrafficDenied : TelegramCallResult()
}

data class TelegramDownloadedFile(
    val bytes: ByteArray,
    val contentType: ContentType?,
)

class TelegramApiClient(
    private val token: String,
    private val client: HttpClient,
    private val json: Json,
    private val trafficPolicy: TelegramTrafficPolicy,
    private val maintenancePolicy: StagingMaintenancePolicy = StagingMaintenancePolicy.off(),
) {
    private val logger = LoggerFactory.getLogger(TelegramApiClient::class.java)
    private val baseUrl = "https://api.telegram.org/bot$token"

    suspend fun getUpdates(
        offset: Long?,
        timeoutSeconds: Int,
    ): List<TelegramUpdate> {
        check(
            maintenancePolicy.allowsBotGlobalOperation(
                StagingMaintenancePolicy.BotGlobalOperation.GET_UPDATES,
            ),
        )
        val response: TelegramResponse<List<TelegramUpdate>> =
            client.get("$baseUrl/getUpdates") {
                offset?.let { parameter("offset", it) }
                parameter("timeout", timeoutSeconds)
            }.safeBody()
        if (response.ok.not()) {
            logApiFailure("getUpdates", response.errorCode, response.parameters?.retryAfterSeconds)
            throw TelegramApiException("Telegram getUpdates failed")
        }
        return response.result ?: emptyList()
    }

    suspend fun getWebhookInfo(): TelegramWebhookInfo {
        check(
            maintenancePolicy.allowsBotGlobalOperation(
                StagingMaintenancePolicy.BotGlobalOperation.GET_WEBHOOK_INFO,
            ),
        )
        val response: TelegramResponse<TelegramWebhookInfo> =
            client.get("$baseUrl/getWebhookInfo").safeBody()
        if (response.ok.not()) {
            logApiFailure("getWebhookInfo", response.errorCode, response.parameters?.retryAfterSeconds)
            throw TelegramApiException("Telegram getWebhookInfo failed")
        }
        return response.result ?: throw TelegramApiException("Telegram getWebhookInfo returned no result")
    }

    suspend fun sendMessage(
        chatId: Long,
        text: String,
        replyMarkup: ReplyMarkup? = null,
        parseMode: String? = null,
    ): MessageId? {
        if (!trafficPolicy.allowsOutboundChat(chatId) || !maintenancePolicy.allowsOutboundChat(chatId)) return null
        val payload = buildSendMessagePayload(json, chatId, text, replyMarkup, parseMode)
        val payloadJson = json.encodeToJsonElement(payload)
        return runCatching {
            when (val result = callMethod("sendMessage", payloadJson)) {
                is TelegramCallResult.Success ->
                    result.responseJson?.let { responseJson ->
                        runCatching { json.decodeFromJsonElement(MessageId.serializer(), responseJson) }
                            .onFailure { throwable ->
                                logApiException("sendMessage response decode", throwable)
                            }
                            .getOrNull()
                    }
                is TelegramCallResult.Failure -> {
                    logApiFailure("sendMessage", result.errorCode, result.retryAfterSeconds)
                    null
                }
                TelegramCallResult.TrafficDenied -> null
            }
        }.onFailure { throwable ->
            logApiException("sendMessage", throwable)
        }.getOrNull()
    }

    suspend fun dispatchOutbox(
        envelopeChatId: Long,
        method: String,
        payload: JsonElement,
    ): TelegramCallResult {
        if (
            !trafficPolicy.allowsOutboundChat(envelopeChatId) ||
            !maintenancePolicy.allowsOutboundChat(envelopeChatId)
        ) {
            return TelegramCallResult.TrafficDenied
        }
        if (!isValidOutboxEnvelope(envelopeChatId, method, payload)) {
            return TelegramCallResult.Failure(
                errorCode = null,
                description = null,
                retryAfterSeconds = null,
                origin = TelegramCallResult.Failure.Origin.LOCAL_ENVELOPE_VALIDATION,
            )
        }
        return callMethod(method, payload)
    }

    suspend fun deleteMyCommands(scopeType: String): TelegramCallResult {
        if (
            !maintenancePolicy.allowsBotGlobalOperation(
                StagingMaintenancePolicy.BotGlobalOperation.COMMAND_CONFIGURATION,
            )
        ) {
            return TelegramCallResult.TrafficDenied
        }
        require(scopeType in commandScopeTypes) { "Unsupported Telegram command scope" }
        return callMethod(
            "deleteMyCommands",
            buildJsonObject {
                put(
                    "scope",
                    buildJsonObject {
                        put("type", scopeType)
                    },
                )
            },
        )
    }

    suspend fun setMyCommands(
        scopeType: String?,
        commands: JsonArray,
    ): TelegramCallResult {
        if (
            !maintenancePolicy.allowsBotGlobalOperation(
                StagingMaintenancePolicy.BotGlobalOperation.COMMAND_CONFIGURATION,
            )
        ) {
            return TelegramCallResult.TrafficDenied
        }
        require(scopeType == null || scopeType in commandScopeTypes) { "Unsupported Telegram command scope" }
        return callMethod(
            "setMyCommands",
            buildJsonObject {
                scopeType?.let {
                    put(
                        "scope",
                        buildJsonObject {
                            put("type", it)
                        },
                    )
                }
                put("commands", commands)
            },
        )
    }

    suspend fun setCommandsMenuButton(): TelegramCallResult {
        if (
            !maintenancePolicy.allowsBotGlobalOperation(
                StagingMaintenancePolicy.BotGlobalOperation.COMMAND_CONFIGURATION,
            )
        ) {
            return TelegramCallResult.TrafficDenied
        }
        return callMethod(
            "setChatMenuButton",
            buildJsonObject {
                put(
                    "menu_button",
                    buildJsonObject {
                        put("type", "commands")
                    },
                )
            },
        )
    }

    private suspend fun callMethod(
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
        if (
            !maintenancePolicy.allowsBotGlobalOperation(
                StagingMaintenancePolicy.BotGlobalOperation.FILE_DOWNLOAD,
            )
        ) {
            return null
        }
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
                logApiFailure("getFile", fileResponse.errorCode, fileResponse.parameters?.retryAfterSeconds)
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
            logApiException("file download", throwable)
        }.getOrNull()
    }

    fun close() {
        client.close()
    }

    suspend fun getChatMember(
        chatId: Long,
        userId: Long,
    ): ChatMember? {
        if (
            !trafficPolicy.allowsChatMemberLookup(chatId, userId) ||
            !maintenancePolicy.allowsChatMemberLookup(chatId, userId)
        ) {
            return null
        }
        return runCatching {
            val response: TelegramResponse<ChatMember> =
                client.get("$baseUrl/getChatMember") {
                    parameter("chat_id", chatId)
                    parameter("user_id", userId)
                }.safeBody()
            if (response.ok.not()) {
                logApiFailure("getChatMember", response.errorCode, response.parameters?.retryAfterSeconds)
                null
            } else {
                response.result
            }
        }.onFailure { throwable ->
            logApiException("getChatMember", throwable)
        }.getOrNull()
    }

    suspend fun getChat(chatId: Long): Chat? {
        if (!trafficPolicy.allowsOutboundChat(chatId) || !maintenancePolicy.allowsOutboundChat(chatId)) return null
        return runCatching {
            val response: TelegramResponse<Chat> =
                client.get("$baseUrl/getChat") {
                    parameter("chat_id", chatId)
                }.safeBody()
            if (response.ok.not()) {
                logApiFailure("getChat", response.errorCode, response.parameters?.retryAfterSeconds)
                null
            } else {
                response.result
            }
        }.onFailure { throwable ->
            logApiException("getChat", throwable)
        }.getOrNull()
    }

    suspend fun sendPhotoBytes(
        chatId: Long,
        photoBytes: ByteArray,
        filename: String,
        caption: String? = null,
        replyMarkup: ReplyMarkup? = null,
    ): Boolean {
        if (!trafficPolicy.allowsOutboundChat(chatId) || !maintenancePolicy.allowsOutboundChat(chatId)) return false
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
                                append(
                                    "reply_markup",
                                    json.encodeToString(JsonElement.serializer(), replyMarkupElement),
                                )
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
                logApiFailure("sendPhoto", response.errorCode, response.parameters?.retryAfterSeconds)
                false
            } else {
                true
            }
        }.onFailure { throwable ->
            logApiException("sendPhoto", throwable)
        }.getOrDefault(false)
    }

    suspend fun deleteMessage(
        chatId: Long,
        messageId: Long,
    ): Boolean {
        if (!trafficPolicy.allowsOutboundChat(chatId) || !maintenancePolicy.allowsOutboundChat(chatId)) return false
        val result =
            runCatching {
                callMethod(
                    "deleteMessage",
                    buildJsonObject {
                        put("chat_id", chatId)
                        put("message_id", messageId)
                    },
                )
            }.onFailure { throwable ->
                logApiException("deleteMessage", throwable)
            }.getOrNull()
        return result is TelegramCallResult.Success
    }

    private fun isValidOutboxEnvelope(
        envelopeChatId: Long,
        method: String,
        payload: JsonElement,
    ): Boolean {
        if (method !in outboxMethods) return false
        val payloadObject = payload as? JsonObject ?: return false
        val payloadChatId = (payloadObject["chat_id"] as? JsonPrimitive)?.longOrNull
        if (payloadChatId != null && payloadChatId != envelopeChatId) return false
        return when (method) {
            "sendMessage", "editMessageText", "sendPhoto", "sendDocument" -> payloadChatId == envelopeChatId
            "answerCallbackQuery" ->
                (payloadObject["callback_query_id"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.isNotBlank() == true
            else -> false
        }
    }

    private suspend inline fun <reified T> HttpResponse.safeBody(): T = body()

    private fun logApiFailure(
        operation: String,
        errorCode: Int?,
        retryAfterSeconds: Int?,
    ) {
        logger.warn(
            "Telegram API operation failed operation={} error_code={} retry_after_seconds={}",
            operation,
            errorCode,
            retryAfterSeconds,
        )
    }

    private fun logApiException(
        operation: String,
        throwable: Throwable,
    ) {
        logger.warn(
            "Telegram API operation failed operation={} error_type={}",
            operation,
            throwable::class.java.simpleName,
        )
    }

    private companion object {
        val commandScopeTypes =
            setOf(
                "default",
                "all_private_chats",
                "all_group_chats",
                "all_chat_administrators",
            )
        val outboxMethods =
            setOf(
                "sendMessage",
                "editMessageText",
                "sendPhoto",
                "sendDocument",
                "answerCallbackQuery",
            )
    }
}

class TelegramApiException(message: String) : RuntimeException(message)

@Serializable
data class TelegramWebhookInfo(
    val url: String,
)

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
