package com.hookah.platform.backend.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TelegramUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: Message? = null,
    @SerialName("callback_query") val callbackQuery: CallbackQuery? = null,
)

@Serializable
data class Message(
    @SerialName("message_id") val messageId: Long,
    val chat: Chat,
    @SerialName("from") val fromUser: User? = null,
    val text: String? = null,
    @SerialName("web_app_data") val webAppData: WebAppData? = null,
)

@Serializable
data class Chat(
    val id: Long,
    val type: String,
)

@Serializable
data class User(
    val id: Long,
    val username: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
)

@Serializable
data class WebAppData(
    val data: String,
)

@Serializable
data class CallbackQuery(
    val id: String,
    val from: User,
    val message: Message? = null,
    val data: String? = null,
)

@Serializable
sealed interface ReplyMarkup

@Serializable
data class ReplyKeyboardMarkup(
    val keyboard: List<List<KeyboardButton>>,
    @SerialName("resize_keyboard") val resizeKeyboard: Boolean = true,
    @SerialName("one_time_keyboard") val oneTimeKeyboard: Boolean = false,
) : ReplyMarkup

@Serializable
data class KeyboardButton(
    val text: String,
    @SerialName("web_app") val webApp: WebAppInfo? = null,
)

@Serializable
data class WebAppInfo(
    val url: String,
)

@Serializable
data class InlineKeyboardMarkup(
    @SerialName("inline_keyboard") val inlineKeyboard: List<List<InlineKeyboardButton>>,
) : ReplyMarkup

@Serializable
data class InlineKeyboardButton(
    val text: String,
    @SerialName("callback_data") val callbackData: String? = null,
    @SerialName("web_app") val webApp: WebAppInfo? = null,
)

@Serializable
data class MessageId(
    @SerialName("message_id") val messageId: Long,
)

@Serializable
data class ChatMember(
    val user: User,
    val status: String,
)
