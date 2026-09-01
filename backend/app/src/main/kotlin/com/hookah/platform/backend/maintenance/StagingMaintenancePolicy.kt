package com.hookah.platform.backend.maintenance

import com.hookah.platform.backend.telegram.Chat
import com.hookah.platform.backend.telegram.TelegramUpdate
import io.ktor.server.config.ApplicationConfig
import java.util.Locale

class StagingMaintenancePolicy private constructor(
    val mode: Mode,
    allowedUserIds: Set<Long>,
    allowedChatIds: Set<Long>,
) {
    enum class Mode {
        OFF,
        V126_SMOKE,
    }

    enum class InboundDenialReason {
        UNSUPPORTED_UPDATE,
        MISSING_ACTOR,
        MISSING_CHAT,
        INVALID_ACTOR,
        INVALID_CHAT,
        ACTOR_NOT_ALLOWED,
        CHAT_NOT_ALLOWED,
        PRIVATE_ACTOR_CHAT_MISMATCH,
        UNSUPPORTED_CHAT_TYPE,
    }

    enum class BotGlobalOperation {
        GET_UPDATES,
        GET_WEBHOOK_INFO,
        FILE_DOWNLOAD,
        COMMAND_CONFIGURATION,
    }

    sealed interface InboundDecision {
        data object Allowed : InboundDecision

        class Denied internal constructor(
            val reason: InboundDenialReason,
        ) : InboundDecision {
            override fun toString(): String = "Denied(reason=$reason)"
        }
    }

    private val allowedUserIds = allowedUserIds.toSet()
    private val allowedChatIds = allowedChatIds.toSet()

    val active: Boolean = mode == Mode.V126_SMOKE
    val allowsAutonomousWrites: Boolean = !active
    internal val outboundEligibleChatIds: Set<Long> =
        if (active) allowedChatIds.toSet() else emptySet()

    fun evaluateInbound(update: TelegramUpdate): InboundDecision {
        if (!active) return InboundDecision.Allowed

        val hasMessage = update.message != null
        val hasCallback = update.callbackQuery != null
        if (hasMessage == hasCallback) {
            return denied(InboundDenialReason.UNSUPPORTED_UPDATE)
        }

        val actorId: Long
        val chat: Chat
        if (hasMessage) {
            val message = update.message!!
            actorId = message.fromUser?.id ?: return denied(InboundDenialReason.MISSING_ACTOR)
            chat = message.chat
        } else {
            val callback = update.callbackQuery!!
            actorId = callback.from.id
            chat = callback.message?.chat ?: return denied(InboundDenialReason.MISSING_CHAT)
        }

        if (actorId <= 0) return denied(InboundDenialReason.INVALID_ACTOR)
        if (actorId !in allowedUserIds) return denied(InboundDenialReason.ACTOR_NOT_ALLOWED)

        return when (chat.type) {
            "private" -> evaluatePrivateChat(actorId, chat.id)
            "group", "supergroup" -> evaluateGroupChat(chat.id)
            else -> denied(InboundDenialReason.UNSUPPORTED_CHAT_TYPE)
        }
    }

    fun allowsMiniAppUser(userId: Long): Boolean = !active || (userId > 0 && userId in allowedUserIds)

    fun allowsOutboundChat(chatId: Long): Boolean {
        if (chatId == 0L) return false
        if (!active) return true
        return when {
            chatId > 0 -> chatId in allowedUserIds && chatId in allowedChatIds
            chatId < 0 -> chatId in allowedChatIds
            else -> false
        }
    }

    fun allowsChatMemberLookup(
        chatId: Long,
        userId: Long,
    ): Boolean {
        if (chatId == 0L || userId <= 0) return false
        if (!active) return true
        if (userId !in allowedUserIds) return false
        return when {
            chatId > 0 -> chatId == userId && chatId in allowedChatIds
            chatId < 0 -> chatId in allowedChatIds
            else -> false
        }
    }

    fun allowsBotGlobalOperation(operation: BotGlobalOperation): Boolean =
        !active ||
            operation == BotGlobalOperation.GET_UPDATES ||
            operation == BotGlobalOperation.GET_WEBHOOK_INFO ||
            operation == BotGlobalOperation.FILE_DOWNLOAD

    override fun toString(): String =
        "StagingMaintenancePolicy(mode=$mode, allowedUsers=${allowedUserIds.size}, " +
            "allowedChats=${allowedChatIds.size})"

    private fun evaluatePrivateChat(
        actorId: Long,
        chatId: Long,
    ): InboundDecision {
        if (chatId <= 0) return denied(InboundDenialReason.INVALID_CHAT)
        if (actorId != chatId) return denied(InboundDenialReason.PRIVATE_ACTOR_CHAT_MISMATCH)
        if (chatId !in allowedChatIds) return denied(InboundDenialReason.CHAT_NOT_ALLOWED)
        return InboundDecision.Allowed
    }

    private fun evaluateGroupChat(chatId: Long): InboundDecision {
        if (chatId >= 0) return denied(InboundDenialReason.INVALID_CHAT)
        if (chatId !in allowedChatIds) return denied(InboundDenialReason.CHAT_NOT_ALLOWED)
        return InboundDecision.Allowed
    }

    private fun denied(reason: InboundDenialReason): InboundDecision = InboundDecision.Denied(reason)

    companion object {
        private const val MODE_KEY = "staging.maintenance.mode"
        private const val USER_IDS_KEY = "staging.maintenance.allowedUserIds"
        private const val CHAT_IDS_KEY = "staging.maintenance.allowedChatIds"

        fun from(
            config: ApplicationConfig,
            appEnv: String,
        ): StagingMaintenancePolicy {
            val rawMode = config.optionalString(MODE_KEY)
            val mode = resolveMode(rawMode)
            if (mode == Mode.OFF) return off()

            val normalizedEnv = appEnv.trim().lowercase(Locale.ROOT)
            if (normalizedEnv != "staging" && normalizedEnv != "test") {
                invalidConfig(MODE_KEY, "V126_SMOKE is restricted to staging")
            }

            val userIds = parseIds(config.optionalString(USER_IDS_KEY), USER_IDS_KEY, positiveOnly = true)
            val chatIds = parseIds(config.optionalString(CHAT_IDS_KEY), CHAT_IDS_KEY, positiveOnly = false)
            val positiveChatIds = chatIds.filterTo(mutableSetOf()) { it > 0 }
            if (positiveChatIds != userIds) {
                invalidConfig(CHAT_IDS_KEY, "positive private chats must exactly match the user set")
            }

            return StagingMaintenancePolicy(
                mode = Mode.V126_SMOKE,
                allowedUserIds = userIds,
                allowedChatIds = chatIds,
            )
        }

        fun off(): StagingMaintenancePolicy =
            StagingMaintenancePolicy(
                mode = Mode.OFF,
                allowedUserIds = emptySet(),
                allowedChatIds = emptySet(),
            )

        private fun resolveMode(rawMode: String?): Mode {
            if (rawMode == null) return Mode.OFF
            val normalized = rawMode.trim().uppercase(Locale.ROOT)
            if (normalized.isEmpty()) invalidConfig(MODE_KEY, "must not be blank")
            return Mode.entries.firstOrNull { it.name == normalized }
                ?: invalidConfig(MODE_KEY, "contains an unknown value")
        }

        private fun parseIds(
            raw: String?,
            key: String,
            positiveOnly: Boolean,
        ): Set<Long> {
            if (raw.isNullOrBlank()) invalidConfig(key, "must be nonempty in V126_SMOKE")
            val values = linkedSetOf<Long>()
            raw.split(',').forEach { token ->
                if (token.isEmpty() || token != token.trim()) {
                    invalidConfig(key, "must be a canonical comma-separated ID list")
                }
                val value =
                    token.toLongOrNull()
                        ?: invalidConfig(key, "must contain only canonical signed decimal IDs")
                if (value == 0L || (positiveOnly && value < 0)) {
                    invalidConfig(key, "contains an invalid ID")
                }
                if (value.toString() != token) {
                    invalidConfig(key, "must contain only canonical signed decimal IDs")
                }
                if (!values.add(value)) invalidConfig(key, "must not contain duplicates")
            }
            if (values.isEmpty()) invalidConfig(key, "must be nonempty in V126_SMOKE")
            return values
        }

        private fun invalidConfig(
            key: String,
            reason: String,
        ): Nothing = throw IllegalStateException("Invalid staging maintenance configuration: $key $reason")

        private fun ApplicationConfig.optionalString(path: String): String? = propertyOrNull(path)?.getString()
    }
}
