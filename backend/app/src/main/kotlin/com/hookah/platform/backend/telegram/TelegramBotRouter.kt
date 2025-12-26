package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.StaffCallReason
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.IdempotencyRepository
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.LinkAndBindResult
import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeFormat
import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeRepository
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import com.hookah.platform.backend.telegram.db.UserRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.UnlinkResult
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import com.hookah.platform.backend.telegram.debugTelegramException
import com.hookah.platform.backend.telegram.summarizeJsonKeysForLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Locale

class TelegramBotRouter(
    private val config: TelegramBotConfig,
    private val apiClient: TelegramApiClient,
    private val idempotencyRepository: IdempotencyRepository,
    private val userRepository: UserRepository,
    private val tableTokenRepository: TableTokenRepository,
    private val chatContextRepository: ChatContextRepository,
    private val dialogStateRepository: DialogStateRepository,
    private val ordersRepository: OrdersRepository,
    private val staffCallRepository: StaffCallRepository,
    private val staffChatLinkCodeRepository: StaffChatLinkCodeRepository,
    private val venueRepository: VenueRepository,
    private val venueAccessRepository: VenueAccessRepository,
    private val json: Json,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(TelegramBotRouter::class.java)

    suspend fun process(update: TelegramUpdate) {
        val chatId = update.message?.chat?.id ?: update.callbackQuery?.message?.chat?.id
        val messageId = update.message?.messageId ?: update.callbackQuery?.message?.messageId
        if (chatId != null) {
            val acquired = idempotencyRepository.tryAcquire(update.updateId, chatId, messageId)
            if (!acquired) return
        }

        when {
            update.message != null -> handleMessage(update.message)
            update.callbackQuery != null -> handleCallback(update.callbackQuery)
            else -> logger.debug("Ignored update without message or callback id={} ", update.updateId)
        }
    }

    private suspend fun handleMessage(message: Message) {
        val from = message.fromUser
        if (from != null) {
            safeUpsertUser(from)
        }
        val chatId = message.chat.id
        val text = message.text?.trim()
        val state = dialogStateRepository.get(chatId)
        val command = parseCommand(text)

        when {
            command?.name == "/start" -> handleStartCommand(chatId, from, text ?: "")
            command?.name == "/link" -> handleLinkCommand(message, command.argument)
            command?.name == "/unlink" -> handleUnlinkCommand(message)
            command?.name == "/link_test" -> handleLinkTestCommand(message)
            text == "🧾 Активный заказ" -> showActiveOrder(chatId)
            text == "✍️ Быстрый заказ" -> startQuickOrder(chatId)
            text == "🛎️ Вызов персонала" -> showStaffCallReasons(chatId)
            text == "🔁 Сменить стол" -> clearContextAndAskRescan(chatId)
            text == "🏠 В каталог" -> showMainMenu(chatId, from)
            state.state == DialogStateType.QUICK_ORDER_WAIT_TEXT && !text.isNullOrBlank() ->
                proceedQuickOrderText(chatId, text)
            state.state == DialogStateType.STAFF_CALL_WAIT_COMMENT && !text.isNullOrBlank() ->
                proceedStaffCallComment(chatId, text)
            message.webAppData != null -> handleWebAppData(chatId, from, message.webAppData)
            else -> sendFallback(chatId, from)
        }
    }

    private suspend fun handleWebAppData(chatId: Long, from: User?, webAppData: WebAppData) {
        val data = webAppData.data
        when (data) {
            "start_quick_order" -> startQuickOrder(chatId)
            "call_staff" -> showStaffCallReasons(chatId)
            "open_active_order" -> showActiveOrder(chatId)
            else -> handleJsonWebAppData(chatId, from, data)
        }
    }

    private suspend fun handleJsonWebAppData(chatId: Long, from: User?, data: String) {
        val parsed = runCatching { json.decodeFromString<JsonElement>(data) }.getOrNull()
        val obj = parsed as? JsonObject ?: return
        val cmd = obj["cmd"]?.jsonPrimitive?.content
        val token = obj["table_token"]?.jsonPrimitive?.content
        if (!token.isNullOrBlank()) {
            val applied = applyTableToken(chatId, from, token)
            if (!applied) {
                sendFallback(chatId, from, "QR недействителен или база недоступна. Используйте меню ниже.")
                return
            }
        }
        when (cmd) {
            "call_staff" -> {
                val reasonRaw = obj["reason"]?.jsonPrimitive?.content
                val reason = reasonRaw?.let { parseStaffCallReason(it) } ?: StaffCallReason.OTHER
                val comment = obj["comment"]?.jsonPrimitive?.content
                createStaffCall(chatId, reason, comment)
            }
            "start_quick_order" -> startQuickOrder(chatId)
            "open_active_order" -> showActiveOrder(chatId)
            null -> if (logger.isDebugEnabled) {
                val keysSummary = summarizeJsonKeysForLog(obj)
                logger.debug(
                    "web_app_data missing cmd keys_count={} keys={} raw_len={}",
                    obj.size,
                    keysSummary,
                    data.length
                )
            }
            else -> if (logger.isDebugEnabled) {
                logger.debug("Unsupported web_app_data cmd: {}", sanitizeTelegramForLog(cmd))
            }
        }
    }

    private suspend fun handleCallback(callbackQuery: CallbackQuery) {
        val chatId = callbackQuery.message?.chat?.id
        if (chatId == null) {
            apiClient.answerCallbackQuery(callbackQuery.id)
            return
        }
        safeUpsertUser(callbackQuery.from)
        when (callbackQuery.data) {
            "quick_order_confirm" -> confirmQuickOrder(chatId)
            "quick_order_edit" -> {
                dialogStateRepository.set(
                    chatId,
                    DialogState(DialogStateType.QUICK_ORDER_WAIT_TEXT)
                )
                apiClient.sendMessage(chatId, "Напишите детали заказа.")
            }
            "quick_order_cancel" -> {
                dialogStateRepository.clear(chatId)
                apiClient.sendMessage(chatId, "Быстрый заказ отменён.")
            }
            null -> Unit
            else -> handleStaffCallCallback(chatId, callbackQuery.data)
        }
        apiClient.answerCallbackQuery(callbackQuery.id)
    }

    private suspend fun handleStaffCallCallback(chatId: Long, data: String) {
        if (!data.startsWith("staff_call_reason:")) return
        val reason = parseStaffCallReason(data.removePrefix("staff_call_reason:"))
        if (reason == StaffCallReason.OTHER) {
            dialogStateRepository.set(chatId, DialogState(DialogStateType.STAFF_CALL_WAIT_COMMENT))
            apiClient.sendMessage(chatId, "Опишите, что нужно сделать.")
        } else {
            createStaffCall(chatId, reason, null)
        }
    }

    private suspend fun handleStartCommand(chatId: Long, from: User?, text: String) {
        val parts = text.trim().split(Regex("\\s+"))
        val command = parts.firstOrNull()?.substringBefore("@") ?: ""
        val token = if (command == "/start") parts.getOrNull(1) else null
        if (token.isNullOrBlank()) {
            showMainMenu(chatId, from)
            return
        }
        val applied = applyTableToken(chatId, from, token)
        if (!applied) {
            sendFallback(chatId, from, "QR недействителен или база недоступна. Используйте меню ниже.")
        }
    }

    private suspend fun applyTableToken(chatId: Long, from: User?, token: String): Boolean {
        val context = tableTokenRepository.resolve(token) ?: return false
        val userId = from?.id ?: return false
        chatContextRepository.saveContext(chatId, userId, context)
        dialogStateRepository.clear(chatId)
        apiClient.sendMessage(
            chatId,
            "Вы за столом №${context.tableNumber} в ${context.venueName}",
            TelegramKeyboards.tableContext(context, config.webAppPublicUrl)
        )
        return true
    }

    private suspend fun showMainMenu(chatId: Long, from: User?) {
        val userId = from?.id
        val hasVenueRole = userId?.let { venueAccessRepository.hasVenueRole(it) } ?: false
        val isOwner = config.platformOwnerId?.let { owner -> owner == userId } ?: false
        apiClient.sendMessage(
            chatId,
            "Добро пожаловать! Выберите действие.",
            TelegramKeyboards.mainMenu(hasVenueRole, isOwner, config.webAppPublicUrl)
        )
        dialogStateRepository.clear(chatId)
    }

    private suspend fun showActiveOrder(chatId: Long) {
        val context = loadContext(chatId)
        if (context == null) {
            askScanQr(chatId)
            return
        }
        val summary = ordersRepository.findActiveOrderSummary(context.table.tableId)
        if (summary != null) {
            apiClient.sendMessage(
                chatId,
                "Активный заказ №${summary.id} (${summary.status}).",
                TelegramKeyboards.inlineOpenActiveOrder(
                    config.webAppPublicUrl,
                    context.table.tableToken
                )
            )
        } else {
            val replyMarkup = TelegramKeyboards.inlineOpenMenu(config.webAppPublicUrl, context.table.tableToken)
            apiClient.sendMessage(chatId, "Активных заказов нет.", replyMarkup)
        }
    }

    private suspend fun startQuickOrder(chatId: Long) {
        val context = loadContext(chatId)
        if (context == null) {
            askScanQr(chatId)
            return
        }
        dialogStateRepository.set(chatId, DialogState(DialogStateType.QUICK_ORDER_WAIT_TEXT))
        apiClient.sendMessage(chatId, "Опишите, что хотите заказать.")
    }

    private suspend fun proceedQuickOrderText(chatId: Long, text: String) {
        dialogStateRepository.set(
            chatId,
            DialogState(DialogStateType.QUICK_ORDER_WAIT_CONFIRM, mapOf("text" to text))
        )
        apiClient.sendMessage(
            chatId,
            "Отправить запрос в заведение?\n\n$text",
            TelegramKeyboards.inlineConfirmQuickOrder()
        )
    }

    private suspend fun confirmQuickOrder(chatId: Long) {
        val context = loadContext(chatId)
        if (context == null) {
            askScanQr(chatId)
            return
        }
        val state = dialogStateRepository.get(chatId)
        val text = state.payload["text"]
        if (text.isNullOrBlank()) {
            apiClient.sendMessage(chatId, "Нет текста заказа, отправьте заново.")
            dialogStateRepository.set(chatId, DialogState(DialogStateType.QUICK_ORDER_WAIT_TEXT))
            return
        }
        val orderId = ordersRepository.getOrCreateActiveOrderId(
            context.table.tableId,
            context.table.venueId
        )
        if (orderId == null) {
            apiClient.sendMessage(chatId, "База недоступна, попробуйте позже.")
            return
        }
        ordersRepository.createOrderBatch(orderId, context.userId, text)
        dialogStateRepository.clear(chatId)
        apiClient.sendMessage(chatId, "Запрос отправлен, ожидайте подтверждения.")
        notifyStaffChat(
            context,
            "🆕 Быстрый заказ (чат)\n${context.table.venueName}\n" +
                "Стол №${context.table.tableNumber}\nТекст: $text"
        )
    }

    private suspend fun showStaffCallReasons(chatId: Long) {
        val context = loadContext(chatId)
        if (context == null) {
            askScanQr(chatId)
            return
        }
        dialogStateRepository.clear(chatId)
        apiClient.sendMessage(chatId, "Выберите причину:", TelegramKeyboards.inlineStaffCallReasons())
    }

    private suspend fun proceedStaffCallComment(chatId: Long, comment: String) {
        createStaffCall(chatId, StaffCallReason.OTHER, comment)
    }

    private suspend fun createStaffCall(chatId: Long, reason: StaffCallReason, comment: String?) {
        val context = loadContext(chatId)
        if (context == null) {
            askScanQr(chatId)
            return
        }
        if (reason == StaffCallReason.OTHER && comment.isNullOrBlank()) {
            dialogStateRepository.set(chatId, DialogState(DialogStateType.STAFF_CALL_WAIT_COMMENT))
            apiClient.sendMessage(chatId, "Опишите, что нужно сделать.")
            return
        }
        staffCallRepository.createStaffCall(
            context.table.venueId,
            context.table.tableId,
            context.userId,
            reason,
            comment
        ) ?: run {
            apiClient.sendMessage(chatId, "База недоступна, попробуйте позже.")
            return
        }
        dialogStateRepository.clear(chatId)
        apiClient.sendMessage(chatId, "Персонал уведомлён, ожидайте.")
        val commentPart = comment?.takeIf { it.isNotBlank() }?.let { "\nКомментарий: $it" } ?: ""
        notifyStaffChat(
            context,
            "🛎️ Вызов персонала\n${context.table.venueName}\n" +
                "Стол №${context.table.tableNumber}\nПричина: $reason$commentPart"
        )
    }

    private suspend fun notifyStaffChat(context: ResolvedChatContext, message: String) {
        val chatId = context.table.staffChatId ?: return
        scope.launch { apiClient.sendMessage(chatId, message) }
    }

    private suspend fun clearContextAndAskRescan(chatId: Long) {
        val storedContext = chatContextRepository.get(chatId)
        chatContextRepository.clear(chatId)
        dialogStateRepository.clear(chatId)
        val userId = storedContext?.userId
        val hasVenueRole = userId?.let { venueAccessRepository.hasVenueRole(it) } ?: false
        val isOwner = config.platformOwnerId?.let { owner -> owner == userId } ?: false
        apiClient.sendMessage(
            chatId,
            "Контекст сброшен. Отсканируйте QR на столе или откройте каталог.",
            TelegramKeyboards.mainMenu(hasVenueRole, isOwner, config.webAppPublicUrl)
        )
    }

    private suspend fun sendFallback(chatId: Long, from: User?, text: String = "Используйте меню ниже.") {
        val context = loadContext(chatId)
        if (context != null) {
            apiClient.sendMessage(
                chatId,
                text,
                TelegramKeyboards.tableContext(context.table, config.webAppPublicUrl)
            )
            return
        }
        val stored = chatContextRepository.get(chatId)
        val userId = from?.id ?: stored?.userId
        val hasVenueRole = userId?.let { venueAccessRepository.hasVenueRole(it) } ?: false
        val isOwner = config.platformOwnerId?.let { owner -> owner == userId } ?: false
        apiClient.sendMessage(
            chatId,
            text,
            TelegramKeyboards.mainMenu(hasVenueRole, isOwner, config.webAppPublicUrl)
        )
    }

    private suspend fun loadContext(chatId: Long): ResolvedChatContext? {
        val saved = chatContextRepository.get(chatId) ?: return null
        val resolved = tableTokenRepository.resolve(saved.tableToken) ?: return null
        return ResolvedChatContext(resolved, saved.userId)
    }

    private suspend fun askScanQr(chatId: Long) {
        apiClient.sendMessage(chatId, "Сначала отсканируйте QR на столе.")
    }

    private fun parseCommand(text: String?): ParsedCommand? {
        if (text.isNullOrBlank() || !text.startsWith("/")) return null
        val parts = text.trim().split(Regex("\\s+"))
        if (parts.isEmpty()) return null
        val name = parts.first().substringBefore("@").lowercase(Locale.ROOT)
        return ParsedCommand(name = name, argument = parts.getOrNull(1))
    }

    private suspend fun handleLinkCommand(message: Message, code: String?) {
        val context = resolveGroupCommandContext(message) ?: return
        val chatId = context.chatId
        val userId = context.userId
        if (code.isNullOrBlank()) {
            apiClient.sendMessage(chatId, "Использование: /link <код>. Код генерируется в режиме заведения.")
            return
        }
        val normalizedCode = StaffChatLinkCodeFormat.normalizeCode(code)
        if (normalizedCode == null) {
            apiClient.sendMessage(chatId, "Код недействителен или истёк. Сгенерируйте новый в режиме заведения.")
            return
        }
        val consumeResult = staffChatLinkCodeRepository.linkAndBindWithCode(
            normalizedCode,
            userId,
            chatId,
            message.messageId,
            authorize = { connection, venueId ->
                venueAccessRepository.hasVenueAdminOrOwner(connection, userId, venueId)
            },
            bind = { connection, venueId ->
                venueRepository.bindStaffChatInTransaction(connection, venueId, chatId, userId)
            }
        )
        when (consumeResult) {
            is LinkAndBindResult.Success -> {
                apiClient.sendMessage(
                    chatId,
                    "✅ Чат привязан к заведению ${consumeResult.venueName}. Уведомления о заказах будут приходить сюда."
                )
            }

            is LinkAndBindResult.AlreadyBoundSameChat -> {
                apiClient.sendMessage(
                    chatId,
                    "Этот чат уже привязан к заведению ${consumeResult.venueName}."
                )
            }

            is LinkAndBindResult.ChatAlreadyLinked -> {
                apiClient.sendMessage(
                    chatId,
                    "Этот чат уже привязан к другому заведению. Сначала выполните /unlink в этом чате."
                )
            }

            is LinkAndBindResult.Unauthorized -> {
                apiClient.sendMessage(chatId, "Недостаточно прав.")
            }

            LinkAndBindResult.InvalidOrExpired -> {
                apiClient.sendMessage(chatId, "Код недействителен или истёк. Сгенерируйте новый в режиме заведения.")
            }

            LinkAndBindResult.DatabaseError -> {
                apiClient.sendMessage(chatId, "База недоступна, попробуйте позже.")
            }
        }
    }

    private suspend fun handleUnlinkCommand(message: Message) {
        val context = resolveGroupCommandContext(message) ?: return
        val chatId = context.chatId
        val venue = venueRepository.findVenueByStaffChatId(chatId)
        if (venue == null) {
            apiClient.sendMessage(chatId, "Этот чат не привязан.")
            return
        }
        val userId = context.userId
        val hasRole = venueAccessRepository.hasVenueAdminOrOwner(userId, venue.id)
        if (!hasRole) {
            apiClient.sendMessage(chatId, "Недостаточно прав.")
            return
        }
        when (val result = venueRepository.unlinkStaffChatByChatId(chatId, userId)) {
            is UnlinkResult.Success -> {
                apiClient.sendMessage(chatId, "✅ Чат отвязан.")
            }
            UnlinkResult.NotLinked -> {
                apiClient.sendMessage(chatId, "Этот чат не привязан.")
            }
            UnlinkResult.DatabaseError -> {
                apiClient.sendMessage(chatId, "База недоступна, попробуйте позже.")
            }
        }
    }

    private suspend fun handleLinkTestCommand(message: Message) {
        val context = resolveGroupCommandContext(message) ?: return
        val chatId = context.chatId
        val venue = venueRepository.findVenueByStaffChatId(chatId)
        if (venue == null) {
            apiClient.sendMessage(chatId, "Этот чат не привязан. Сгенерируйте код и выполните /link <код>.")
            return
        }
        val userId = context.userId
        val hasRole = venueAccessRepository.hasVenueAdminOrOwner(userId, venue.id)
        if (!hasRole) {
            apiClient.sendMessage(chatId, "Недостаточно прав.")
            return
        }
        val ts = Instant.now().toString()
        val text = "✅ Тестовое уведомление. Чат привязан к ${venue.name}. (ts=$ts)"
        apiClient.sendMessage(chatId, text)
    }

    private suspend fun ensureChatAdmin(chatId: Long, userId: Long): ChatAdminCheckResult {
        if (!config.requireStaffChatAdmin) return ChatAdminCheckResult.Allowed
        val member = apiClient.getChatMember(chatId, userId) ?: return ChatAdminCheckResult.Failed
        return when (member.status) {
            "creator", "administrator" -> ChatAdminCheckResult.Allowed
            else -> ChatAdminCheckResult.NotAllowed
        }
    }

    private suspend fun resolveGroupCommandContext(message: Message): GroupCommandContext? {
        val chatId = message.chat.id
        if (!isGroupChat(message.chat.type)) {
            apiClient.sendMessage(chatId, "Эту команду нужно отправить в групповом чате персонала.")
            return null
        }
        val userId = message.fromUser?.id
        if (userId == null) {
            apiClient.sendMessage(chatId, "Недостаточно прав.")
            return null
        }
        return when (val chatAdminCheck = ensureChatAdmin(chatId, userId)) {
            ChatAdminCheckResult.Failed -> {
                apiClient.sendMessage(chatId, "Не удалось проверить права в чате, попробуйте позже.")
                null
            }
            ChatAdminCheckResult.NotAllowed -> {
                apiClient.sendMessage(chatId, "Недостаточно прав.")
                null
            }
            ChatAdminCheckResult.Allowed -> GroupCommandContext(chatId, userId)
        }
    }

    private fun isGroupChat(type: String): Boolean = type == "group" || type == "supergroup"

    private fun parseStaffCallReason(value: String): StaffCallReason =
        runCatching { StaffCallReason.valueOf(value.uppercase(Locale.ROOT)) }
            .getOrDefault(StaffCallReason.OTHER)

    private suspend fun safeUpsertUser(user: User) {
        runCatching { userRepository.upsert(user) }
            .onFailure { logBestEffort("user upsert", it) }
    }

    private fun logBestEffort(operation: String, throwable: Throwable) {
        logger.warn("Best-effort {} failed: {}", operation, sanitizeTelegramForLog(throwable.message))
        logger.debugTelegramException(throwable) { "Best-effort exception for $operation" }
    }
}

private data class ParsedCommand(
    val name: String,
    val argument: String?
)

private sealed interface ChatAdminCheckResult {
    data object Allowed : ChatAdminCheckResult
    data object NotAllowed : ChatAdminCheckResult
    data object Failed : ChatAdminCheckResult
}

private data class GroupCommandContext(
    val chatId: Long,
    val userId: Long
)
