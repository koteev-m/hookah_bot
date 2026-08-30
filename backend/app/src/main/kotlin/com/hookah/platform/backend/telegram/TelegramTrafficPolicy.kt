package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeFormat
import io.ktor.server.config.ApplicationConfig
import java.util.Locale

class TelegramTrafficPolicy private constructor(
    private val mode: Mode,
    allowedUserIds: Set<Long>,
    allowedChatIds: Set<Long>,
) {
    enum class Mode {
        ALLOWLIST,
        PRODUCT,
        UNRESTRICTED,
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
        UNSUPPORTED_GROUP_TRAFFIC,
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
    internal val productMode: Boolean = mode == Mode.PRODUCT

    internal val outboundClaimScope: TelegramOutboundClaimScope =
        TelegramOutboundClaimScope(
            unrestricted = mode == Mode.UNRESTRICTED,
            productAuthoritative = mode == Mode.PRODUCT,
            eligibleChatIds =
                if (mode != Mode.ALLOWLIST) {
                    emptySet()
                } else {
                    allowedChatIds.filterTo(mutableSetOf()) { chatId ->
                        chatId < 0 || chatId in allowedUserIds
                    }
                },
        )

    fun evaluateInbound(update: TelegramUpdate): InboundDecision {
        if (mode == Mode.UNRESTRICTED) {
            return InboundDecision.Allowed
        }

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

        if (actorId <= 0) {
            return denied(InboundDenialReason.INVALID_ACTOR)
        }
        if (mode == Mode.ALLOWLIST && actorId !in allowedUserIds) {
            return denied(InboundDenialReason.ACTOR_NOT_ALLOWED)
        }

        return when (chat.type) {
            "private" -> evaluatePrivateChat(actorId, chat.id)
            "group", "supergroup" -> evaluateGroupChat(update, chat.id)
            else -> denied(InboundDenialReason.UNSUPPORTED_CHAT_TYPE)
        }
    }

    fun allowsMiniAppUser(userId: Long): Boolean {
        if (userId <= 0) return false
        return mode != Mode.ALLOWLIST ||
            (userId in allowedUserIds && userId in allowedChatIds)
    }

    fun allowsOutboundChat(chatId: Long): Boolean {
        if (chatId == 0L) return false
        if (mode != Mode.ALLOWLIST) {
            return true
        }
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
        if (mode == Mode.UNRESTRICTED) {
            return true
        }
        if (mode == Mode.PRODUCT) {
            return chatId < 0 || chatId == userId
        }
        if (userId !in allowedUserIds) {
            return false
        }
        return when {
            chatId > 0 -> chatId == userId && chatId in allowedChatIds
            chatId < 0 -> chatId in allowedChatIds
            else -> false
        }
    }

    override fun toString(): String =
        "TelegramTrafficPolicy(mode=$mode, allowedUsers=${allowedUserIds.size}, allowedChats=${allowedChatIds.size})"

    private fun evaluatePrivateChat(
        actorId: Long,
        chatId: Long,
    ): InboundDecision {
        if (chatId <= 0) {
            return denied(InboundDenialReason.INVALID_CHAT)
        }
        if (actorId != chatId) {
            return denied(InboundDenialReason.PRIVATE_ACTOR_CHAT_MISMATCH)
        }
        if (mode == Mode.ALLOWLIST && chatId !in allowedChatIds) {
            return denied(InboundDenialReason.CHAT_NOT_ALLOWED)
        }
        return InboundDecision.Allowed
    }

    private fun evaluateGroupChat(
        update: TelegramUpdate,
        chatId: Long,
    ): InboundDecision {
        if (chatId >= 0) {
            return denied(InboundDenialReason.INVALID_CHAT)
        }
        if (mode == Mode.ALLOWLIST && chatId !in allowedChatIds) {
            return denied(InboundDenialReason.CHAT_NOT_ALLOWED)
        }
        if (mode == Mode.PRODUCT && !isSupportedProductGroupTraffic(update)) {
            return denied(InboundDenialReason.UNSUPPORTED_GROUP_TRAFFIC)
        }
        return InboundDecision.Allowed
    }

    private fun isSupportedProductGroupTraffic(update: TelegramUpdate): Boolean {
        update.message?.let { message ->
            val text = message.text?.trim() ?: return false
            val commandToken = text.takeWhile { !it.isWhitespace() }
            val commandName =
                commandToken
                    .takeIf { it.startsWith('/') }
                    ?.substringBefore('@')
                    ?.lowercase(Locale.ROOT)
            if (commandName !in PRODUCT_GROUP_COMMANDS) return false
            if (commandName != "/link") return true
            val arguments = text.drop(commandToken.length).trim().split(Regex("\\s+"))
            return arguments.size == 1 && StaffChatLinkCodeFormat.normalizeCode(arguments.single()) != null
        }

        val callbackData = update.callbackQuery?.data ?: return false
        return PRODUCT_GROUP_CALLBACK_PREFIXES.any { prefix ->
            callbackData.length > prefix.length && callbackData.startsWith(prefix)
        }
    }

    private fun denied(reason: InboundDenialReason): InboundDecision = InboundDecision.Denied(reason)

    companion object {
        private const val POLICY_KEY = "telegram.trafficPolicy"
        private const val USER_IDS_KEY = "telegram.allowedUserIds"
        private const val CHAT_IDS_KEY = "telegram.allowedChatIds"

        fun from(
            config: ApplicationConfig,
            appEnv: String,
        ): TelegramTrafficPolicy {
            val normalizedEnv = appEnv.trim().lowercase(Locale.ROOT)
            val rawPolicy = config.optionalString(POLICY_KEY)
            val rawUserIds = config.optionalString(USER_IDS_KEY)
            val rawChatIds = config.optionalString(CHAT_IDS_KEY)
            val mode = resolveMode(rawPolicy, normalizedEnv)

            if (mode != Mode.ALLOWLIST) {
                if (!rawUserIds.isNullOrBlank()) {
                    invalidConfig(USER_IDS_KEY, null, "must be absent when policy is ${mode.name}")
                }
                if (!rawChatIds.isNullOrBlank()) {
                    invalidConfig(CHAT_IDS_KEY, null, "must be absent when policy is ${mode.name}")
                }
                return if (mode == Mode.PRODUCT) product() else unrestricted()
            }

            val userIds = parseIds(rawUserIds, USER_IDS_KEY, allowNegative = false)
            val chatIds = parseIds(rawChatIds, CHAT_IDS_KEY, allowNegative = true)
            return TelegramTrafficPolicy(
                mode = Mode.ALLOWLIST,
                allowedUserIds = userIds,
                allowedChatIds = chatIds,
            )
        }

        fun unrestricted(): TelegramTrafficPolicy =
            TelegramTrafficPolicy(
                mode = Mode.UNRESTRICTED,
                allowedUserIds = emptySet(),
                allowedChatIds = emptySet(),
            )

        fun product(): TelegramTrafficPolicy =
            TelegramTrafficPolicy(
                mode = Mode.PRODUCT,
                allowedUserIds = emptySet(),
                allowedChatIds = emptySet(),
            )

        private fun resolveMode(
            rawPolicy: String?,
            appEnv: String,
        ): Mode {
            if (rawPolicy == null) {
                if (appEnv == "staging") {
                    invalidConfig(POLICY_KEY, null, "ALLOWLIST or PRODUCT is required in staging")
                }
                return Mode.UNRESTRICTED
            }
            val normalized = rawPolicy.trim().uppercase(Locale.ROOT)
            if (normalized.isEmpty()) {
                invalidConfig(POLICY_KEY, null, "must not be blank")
            }
            val mode =
                Mode.entries.firstOrNull { it.name == normalized }
                    ?: invalidConfig(POLICY_KEY, null, "contains an unknown value")
            if (appEnv == "staging" && mode == Mode.UNRESTRICTED) {
                invalidConfig(POLICY_KEY, null, "ALLOWLIST or PRODUCT is required in staging")
            }
            return mode
        }

        private fun parseIds(
            rawValue: String?,
            key: String,
            allowNegative: Boolean,
        ): Set<Long> {
            if (rawValue == null || rawValue.trim().isEmpty()) {
                invalidConfig(key, null, "must contain at least one ID")
            }

            val result = linkedSetOf<Long>()
            rawValue.split(',').forEachIndexed { index, rawToken ->
                val token = rawToken.trim()
                val position = index + 1
                if (token.isEmpty()) {
                    invalidConfig(key, position, "must not be empty")
                }
                val pattern = if (allowNegative) CHAT_ID_PATTERN else USER_ID_PATTERN
                if (!pattern.matches(token)) {
                    invalidConfig(key, position, "must use canonical decimal format")
                }
                val parsed =
                    token.toLongOrNull()
                        ?: invalidConfig(key, position, "is outside the signed 64-bit range")
                if (!result.add(parsed)) {
                    invalidConfig(key, position, "duplicates an earlier ID")
                }
            }
            return result
        }

        private fun invalidConfig(
            key: String,
            position: Int?,
            reason: String,
        ): Nothing {
            val location = position?.let { " token #$it" }.orEmpty()
            throw IllegalStateException("$key$location $reason")
        }

        private val USER_ID_PATTERN = Regex("[1-9][0-9]*")
        private val CHAT_ID_PATTERN = Regex("-?[1-9][0-9]*")
        private val PRODUCT_GROUP_COMMANDS = setOf("/link", "/unlink", "/link_test")
        private val PRODUCT_GROUP_CALLBACK_PREFIXES =
            listOf(
                "staff_booking_confirm:",
                "staff_booking_message:",
                "staff_booking_seated_ask:",
                "staff_booking_noshow_ask:",
                "staff_booking_seated_yes:",
                "staff_booking_noshow_yes:",
                "staff_booking_cancel_ask:",
                "staff_booking_cancel_back:",
                "staff_booking_cancel_yes:",
                "sbc_r:",
                "sbc_y:",
                "fb_reply:",
                "sc_ob_a:",
                "sc_ob_d:",
                "sc_se_a:",
                "sc_se_r:",
                "sc_oc_ask:",
                "sc_oc_back:",
                "sc_or:",
                "sc_oc_yes:",
                "sc_call_ack:",
                "sc_call_done:",
            )
    }
}

class TelegramOutboundClaimScope internal constructor(
    internal val unrestricted: Boolean,
    internal val productAuthoritative: Boolean = false,
    eligibleChatIds: Set<Long>,
) {
    internal val eligibleChatIds: Set<Long> = eligibleChatIds.toSet()
    internal val eligiblePositiveRecipientIds: List<Long>? =
        if (unrestricted || productAuthoritative) {
            null
        } else {
            this.eligibleChatIds.asSequence().filter { it > 0 }.sorted().toList()
        }

    override fun toString(): String =
        "TelegramOutboundClaimScope(" +
            "unrestricted=$unrestricted, " +
            "productAuthoritative=$productAuthoritative, " +
            "eligibleChats=${eligibleChatIds.size})"
}

private fun ApplicationConfig.optionalString(path: String): String? = propertyOrNull(path)?.getString()
