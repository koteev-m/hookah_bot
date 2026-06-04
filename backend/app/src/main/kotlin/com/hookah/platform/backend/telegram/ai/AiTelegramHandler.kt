package com.hookah.platform.backend.telegram.ai

import com.hookah.platform.backend.ai.AiAssistantPrincipal
import com.hookah.platform.backend.ai.AiAssistantRole
import com.hookah.platform.backend.ai.AiAssistantService
import com.hookah.platform.backend.ai.AiDraftTextCommand
import com.hookah.platform.backend.ai.AiDraftTextType
import com.hookah.platform.backend.ai.AiPromotionDiagnosticsCommand
import com.hookah.platform.backend.ai.AiVenueSummaryCommand
import com.hookah.platform.backend.ai.AiVenueSummaryType
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.platform.PlatformVenueRepository
import com.hookah.platform.backend.telegram.DialogState
import com.hookah.platform.backend.telegram.DialogStateType
import com.hookah.platform.backend.telegram.InlineKeyboardMarkup
import com.hookah.platform.backend.telegram.ReplyMarkup
import com.hookah.platform.backend.telegram.TelegramKeyboards
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.User
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.TelegramVenueContextRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenuePromotionRepository
import com.hookah.platform.backend.telegram.db.VenuePromotionStatus
import com.hookah.platform.backend.telegram.db.VenueRepository
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class AiTelegramHandler(
    private val outboxEnqueuer: TelegramOutboxEnqueuer,
    private val dialogStateRepository: DialogStateRepository,
    private val venueAccessRepository: VenueAccessRepository,
    private val venueContextRepository: TelegramVenueContextRepository,
    private val venueRepository: VenueRepository,
    private val platformVenueRepository: PlatformVenueRepository,
    private val venuePromotionRepository: VenuePromotionRepository,
    private val venueSettingsRepository: VenueSettingsRepository,
    private val aiAssistantService: AiAssistantService?,
) {
    private val logger = LoggerFactory.getLogger(AiTelegramHandler::class.java)

    val isEnabled: Boolean
        get() = aiAssistantService?.isEnabled == true

    suspend fun showRootForSelectedVenue(
        chatId: Long,
        from: User?,
    ): Boolean {
        val userId = from?.id ?: return false
        val access = resolveSelectedVenueAiAccess(chatId, userId, promptIfMultiple = true) ?: return false
        if (access.role !in setOf(AiVenueRole.OWNER, AiVenueRole.MANAGER)) {
            enqueueMessage(chatId, "Помощник доступен менеджеру или владельцу.")
            return true
        }
        showRootByVenueId(chatId, userId, access.venueId, AiAssistantOrigin.GENERAL)
        return true
    }

    suspend fun handleCallback(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        when {
            data.startsWith("venue_ai:") || data.startsWith("vm_ai:") -> showRoot(chatId, userId, data)
            data.startsWith("venue_ai_diag:") || data.startsWith("vm_ai_diag:") ->
                showPromotionDiagnosticsPicker(
                    chatId,
                    userId,
                    data,
                )
            data.startsWith("venue_ai_diag_p:") || data.startsWith("vm_ai_diag_p:") ->
                runPromotionDiagnostics(
                    chatId,
                    userId,
                    data,
                )
            data.startsWith("venue_ai_sum:") || data.startsWith("vm_ai_sum:") -> runVenueSummary(chatId, userId, data)
            data.startsWith("venue_ai_draft:") || data.startsWith("vm_ai_draft:") || data.startsWith("vm_ai_text:") ->
                promptDraft(chatId, userId, data)
        }
    }

    suspend fun handleDialogText(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val kind =
            DraftKind.fromState(state.state)
                ?: run {
                    enqueueMessage(chatId, "Не удалось подготовить черновик. Попробуйте ещё раз.")
                    return
                }
        val venueId = state.payload["venueId"]?.toLongOrNull()
        val origin = state.payload["origin"]?.let { runCatching { AiAssistantOrigin.valueOf(it) }.getOrNull() }
        val userId = from?.id
        if (venueId == null || origin == null || userId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось подготовить черновик. Откройте помощника ещё раз.")
            return
        }
        val role = requireAccess(chatId, userId, venueId) ?: return
        if (!isEnabled) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Помощник отключён в конфигурации.")
            return
        }
        val service = aiAssistantService
        if (service == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Помощник пока не настроен.", rootActions(venueId, origin))
            return
        }
        val answer =
            service.draftText(
                AiDraftTextCommand(
                    principal = AiAssistantPrincipal(userId = userId, role = role.toAiAssistantRole()),
                    venueId = venueId,
                    type = kind.aiType,
                    brief = text,
                ),
            )
        dialogStateRepository.clear(chatId)
        enqueueMessage(
            chatId,
            "${kind.title}\n\n${answer.text}",
            rootActions(venueId, origin),
        )
    }

    suspend fun cancelDialog(
        chatId: Long,
        state: DialogState,
    ) {
        val venueId = state.payload["venueId"]?.toLongOrNull()
        val origin = state.payload["origin"]?.let { runCatching { AiAssistantOrigin.valueOf(it) }.getOrNull() }
        dialogStateRepository.clear(chatId)
        if (venueId != null && origin != null) {
            enqueueMessage(chatId, "Черновик отменён.", rootActions(venueId, origin))
        } else {
            enqueueMessage(chatId, "Черновик отменён.")
        }
    }

    private suspend fun showRoot(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed =
            parseRootData(data)
                ?: run {
                    enqueueMessage(chatId, "Не удалось открыть помощника. Попробуйте ещё раз.")
                    return
                }
        showRootByVenueId(chatId, userId, parsed.first, parsed.second)
    }

    private suspend fun showRootByVenueId(
        chatId: Long,
        userId: Long,
        venueId: Long,
        origin: AiAssistantOrigin,
    ) {
        requireAccess(chatId, userId, venueId) ?: return
        if (!isEnabled) {
            enqueueMessage(chatId, "Помощник отключён в конфигурации.")
            return
        }
        dialogStateRepository.clear(chatId)
        val venueName = loadVenueName(venueId)
        enqueueMessage(
            chatId,
            "🤖 Помощник\n$venueName\n\n" +
                "Помощник объясняет настройки заведения и помогает разобраться с акциями, " +
                "отзывами, лояльностью и размещениями. " +
                "Он не меняет настройки без подтверждения.",
            rootActions(venueId, origin),
        )
    }

    private suspend fun showPromotionDiagnosticsPicker(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed =
            parseDiagnosticsData(data)
                ?: run {
                    enqueueMessage(chatId, "Не удалось открыть диагностику. Попробуйте ещё раз.")
                    return
                }
        val (venueId, origin) = parsed
        requireAccess(chatId, userId, venueId) ?: return
        if (!isEnabled) {
            enqueueMessage(chatId, "Помощник отключён в конфигурации.")
            return
        }
        val promotions =
            try {
                venuePromotionRepository.listVenuePromotionsForManagement(venueId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (promotions.isEmpty()) {
            enqueueMessage(
                chatId,
                "🔍 Почему акция не применяется?\n\nАкций пока нет. Создайте акцию в разделе «🎁 Акции».",
                rootActions(venueId, origin),
            )
            return
        }
        enqueueMessage(
            chatId,
            "🔍 Почему акция не применяется?\n\nВыберите акцию для диагностики.",
            TelegramKeyboards.inlineVenueAiPromotionDiagnosticsActions(
                venueId = venueId,
                promotions = promotions.map { it.id to "${humanizePromotionStatus(it.status)} · ${it.title}" },
                promotionCallbackPrefix = origin.diagnosticsPromotionCallbackPrefix,
                assistantCallbackData = origin.rootCallbackData(venueId),
            ),
        )
    }

    private suspend fun runPromotionDiagnostics(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed =
            parsePromotionDiagnosticsData(data)
                ?: run {
                    enqueueMessage(chatId, "Не удалось запустить диагностику. Попробуйте ещё раз.")
                    return
                }
        val role = requireAccess(chatId, userId, parsed.venueId) ?: return
        if (!isEnabled) {
            enqueueMessage(chatId, "Помощник отключён в конфигурации.")
            return
        }
        val service = aiAssistantService
        if (service == null) {
            enqueueMessage(chatId, "Помощник пока не настроен.", rootActions(parsed.venueId, parsed.origin))
            return
        }
        val answer =
            service.diagnosePromotion(
                AiPromotionDiagnosticsCommand(
                    principal = AiAssistantPrincipal(userId = userId, role = role.toAiAssistantRole()),
                    venueId = parsed.venueId,
                    promotionId = parsed.promotionId,
                    now = Instant.now(),
                    venueZoneId = resolveVenueZoneId(parsed.venueId),
                ),
            )
        enqueueMessage(chatId, answer.text, rootActions(parsed.venueId, parsed.origin))
    }

    private suspend fun runVenueSummary(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed =
            parseVenueSummaryData(data)
                ?: run {
                    enqueueMessage(chatId, "Не удалось подготовить сводку. Попробуйте ещё раз.")
                    return
                }
        val role = requireAccess(chatId, userId, parsed.venueId) ?: return
        if (!isEnabled) {
            enqueueMessage(chatId, "Помощник отключён в конфигурации.")
            return
        }
        val service = aiAssistantService
        if (service == null) {
            enqueueMessage(chatId, "Помощник пока не настроен.", rootActions(parsed.venueId, parsed.origin))
            return
        }
        val answer =
            service.summarizeVenue(
                AiVenueSummaryCommand(
                    principal = AiAssistantPrincipal(userId = userId, role = role.toAiAssistantRole()),
                    venueId = parsed.venueId,
                    type = parsed.type,
                    now = Instant.now(),
                    venueZoneId = resolveVenueZoneId(parsed.venueId),
                ),
            )
        enqueueMessage(chatId, answer.text, rootActions(parsed.venueId, parsed.origin))
    }

    private suspend fun promptDraft(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed =
            parseDraftData(data)
                ?: run {
                    enqueueMessage(chatId, "Не удалось открыть помощника. Попробуйте ещё раз.")
                    return
                }
        val (venueId, origin, kind) = parsed
        requireAccess(chatId, userId, venueId) ?: return
        if (!isEnabled) {
            enqueueMessage(chatId, "Помощник отключён в конфигурации.")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = kind.state,
                payload =
                    mapOf(
                        "venueId" to venueId.toString(),
                        "origin" to origin.name,
                    ),
            ),
        )
        enqueueMessage(chatId, kind.promptText, rootActions(venueId, origin))
    }

    private suspend fun requireAccess(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ): AiVenueRole? {
        val role =
            runCatching { venueAccessRepository.findVenueMembership(userId, venueId) }
                .onFailure { logger.warn("Failed to load venue membership for AI assistant", it) }
                .getOrNull()
                ?.role
                ?.let(::mapRole)
        if (role == null) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return null
        }
        if (role !in setOf(AiVenueRole.OWNER, AiVenueRole.MANAGER)) {
            enqueueMessage(chatId, "Помощник доступен менеджеру или владельцу.")
            return null
        }
        return role
    }

    private suspend fun resolveSelectedVenueAiAccess(
        chatId: Long,
        userId: Long,
        promptIfMultiple: Boolean,
    ): AiVenueAccess? {
        val accesses = loadVenueAccesses(userId)
        if (accesses.isEmpty()) return null
        val selectedVenueId =
            runCatching { venueContextRepository.getSelectedVenue(chatId, userId) }
                .onFailure { logger.warn("Failed to load selected venue context for AI assistant", it) }
                .getOrNull()
        val selectedAccess = selectedVenueId?.let { venueId -> accesses.firstOrNull { it.venueId == venueId } }
        if (selectedAccess != null) return selectedAccess
        if (selectedVenueId != null) {
            runCatching { venueContextRepository.clearSelectedVenue(chatId, userId) }
                .onFailure { logger.warn("Failed to clear stale selected venue context for AI assistant", it) }
        }
        if (accesses.size == 1) {
            saveSelectedVenueBestEffort(chatId, userId, accesses.single().venueId)
            return accesses.single()
        }
        if (promptIfMultiple) {
            showVenueSelector(chatId, accesses)
        }
        return null
    }

    private suspend fun loadVenueAccesses(userId: Long): List<AiVenueAccess> =
        runCatching { venueAccessRepository.listVenueMemberships(userId) }
            .onFailure { logger.warn("Failed to load venue memberships for AI assistant", it) }
            .getOrDefault(emptyList())
            .mapNotNull { membership ->
                val role = mapRole(membership.role) ?: return@mapNotNull null
                AiVenueAccess(venueId = membership.venueId, role = role)
            }.sortedWith(compareBy({ rolePriority(it.role) }, { it.venueId }))

    private suspend fun showVenueSelector(
        chatId: Long,
        accesses: List<AiVenueAccess>,
    ) {
        val buttons =
            accesses.map { access ->
                val venueName = loadVenueName(access.venueId)
                access.venueId to "$venueName · ${roleLabel(access.role)}"
            }
        enqueueMessage(chatId, "Выберите заведение", TelegramKeyboards.inlineVenueSelectorActions(buttons))
    }

    private suspend fun saveSelectedVenueBestEffort(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ) {
        runCatching { venueContextRepository.setSelectedVenue(chatId, userId, venueId) }
            .onFailure { logger.warn("Failed to save selected venue context for AI assistant", it) }
    }

    private suspend fun loadVenueName(venueId: Long): String {
        val fromPlatform =
            runCatching { platformVenueRepository.getVenueDetail(venueId)?.name }
                .onFailure { logger.warn("Failed to load platform venue for AI assistant", it) }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        if (fromPlatform != null) return fromPlatform
        return runCatching { venueRepository.findVenueById(venueId)?.name }
            .onFailure { logger.warn("Failed to load venue for AI assistant", it) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Заведение #$venueId"
    }

    private suspend fun resolveVenueZoneId(venueId: Long): ZoneId =
        try {
            venueSettingsRepository.resolveZoneId(venueId, ZoneId.systemDefault())
        } catch (_: DatabaseUnavailableException) {
            ZoneId.systemDefault()
        }

    private suspend fun enqueueMessage(
        chatId: Long,
        text: String,
        replyMarkup: ReplyMarkup? = null,
    ) {
        runCatching { outboxEnqueuer.enqueueSendMessage(chatId, text, replyMarkup) }
            .onFailure { logger.warn("Failed to enqueue AI assistant message", it) }
    }

    private fun rootActions(
        venueId: Long,
        origin: AiAssistantOrigin,
    ): InlineKeyboardMarkup =
        TelegramKeyboards.inlineVenueAiAssistantRootActions(
            venueId = venueId,
            diagnosticsCallbackData = origin.diagnosticsCallbackData(venueId),
            promotionSummaryCallbackData = origin.summaryCallbackData(venueId, AiVenueSummaryType.PROMOTION),
            feedbackSummaryCallbackData = origin.summaryCallbackData(venueId, AiVenueSummaryType.FEEDBACK),
            loyaltySummaryCallbackData = origin.summaryCallbackData(venueId, AiVenueSummaryType.LOYALTY),
            ordersSummaryCallbackData = origin.summaryCallbackData(venueId, AiVenueSummaryType.ORDERS),
            promotionTextCallbackData = origin.draftCallbackData(venueId, DraftKind.PROMOTION_TEXT),
            feedbackReplyCallbackData = origin.draftCallbackData(venueId, DraftKind.FEEDBACK_REPLY),
            bannerTextCallbackData = origin.draftCallbackData(venueId, DraftKind.BANNER_TEXT),
            backText = origin.backText,
            backCallbackData = origin.backCallbackData(venueId),
        )

    private fun parseRootData(data: String): Pair<Long, AiAssistantOrigin>? =
        when {
            data.startsWith("venue_ai:") ->
                parseVenueId(data, "venue_ai:")?.let { it to AiAssistantOrigin.GENERAL }
            data.startsWith("vm_ai:") ->
                parseVenueId(data, "vm_ai:")?.let { it to AiAssistantOrigin.MARKETING }
            else -> null
        }

    private fun parseDiagnosticsData(data: String): Pair<Long, AiAssistantOrigin>? =
        when {
            data.startsWith("venue_ai_diag:") ->
                parseVenueId(data, "venue_ai_diag:")?.let { it to AiAssistantOrigin.GENERAL }
            data.startsWith("vm_ai_diag:") ->
                parseVenueId(data, "vm_ai_diag:")?.let { it to AiAssistantOrigin.MARKETING }
            else -> null
        }

    private fun parsePromotionDiagnosticsData(data: String): PromotionDiagnosticsCallback? {
        val origin =
            when {
                data.startsWith("venue_ai_diag_p:") -> AiAssistantOrigin.GENERAL
                data.startsWith("vm_ai_diag_p:") -> AiAssistantOrigin.MARKETING
                else -> return null
            }
        val parts = data.removePrefix(origin.diagnosticsPromotionCallbackPrefix + ":").split(":")
        if (parts.size != 2) return null
        val venueId = parts[0].toLongOrNull() ?: return null
        val promotionId = parts[1].toLongOrNull() ?: return null
        return PromotionDiagnosticsCallback(venueId, promotionId, origin)
    }

    private fun parseVenueSummaryData(data: String): VenueSummaryCallback? {
        val origin =
            when {
                data.startsWith("venue_ai_sum:") -> AiAssistantOrigin.GENERAL
                data.startsWith("vm_ai_sum:") -> AiAssistantOrigin.MARKETING
                else -> return null
            }
        val prefix =
            when (origin) {
                AiAssistantOrigin.GENERAL -> "venue_ai_sum:"
                AiAssistantOrigin.MARKETING -> "vm_ai_sum:"
            }
        val parts = data.removePrefix(prefix).split(":")
        if (parts.size != 2) return null
        val type = AiVenueSummaryType.fromCallbackKey(parts[0]) ?: return null
        val venueId = parts[1].toLongOrNull() ?: return null
        return VenueSummaryCallback(venueId = venueId, type = type, origin = origin)
    }

    private fun parseDraftData(data: String): Triple<Long, AiAssistantOrigin, DraftKind>? {
        if (data.startsWith("vm_ai_text:")) {
            return parseVenueId(data, "vm_ai_text:")
                ?.let { Triple(it, AiAssistantOrigin.MARKETING, DraftKind.PROMOTION_TEXT) }
        }
        val origin =
            when {
                data.startsWith("venue_ai_draft:") -> AiAssistantOrigin.GENERAL
                data.startsWith("vm_ai_draft:") -> AiAssistantOrigin.MARKETING
                else -> return null
            }
        val prefix =
            when (origin) {
                AiAssistantOrigin.GENERAL -> "venue_ai_draft:"
                AiAssistantOrigin.MARKETING -> "vm_ai_draft:"
            }
        val parts = data.removePrefix(prefix).split(":")
        if (parts.size != 2) return null
        val kind = DraftKind.fromCallbackKey(parts[0]) ?: return null
        val venueId = parts[1].toLongOrNull() ?: return null
        return Triple(venueId, origin, kind)
    }

    private fun parseVenueId(
        data: String,
        prefix: String,
    ): Long? = data.removePrefix(prefix).toLongOrNull()

    private fun humanizePromotionStatus(status: VenuePromotionStatus): String =
        when (status) {
            VenuePromotionStatus.DRAFT -> "черновик"
            VenuePromotionStatus.ACTIVE -> "активна"
            VenuePromotionStatus.PAUSED -> "приостановлена"
            VenuePromotionStatus.ARCHIVED -> "в архиве"
        }

    private fun mapRole(role: String): AiVenueRole? =
        when (role.uppercase(Locale.ROOT)) {
            "OWNER" -> AiVenueRole.OWNER
            "ADMIN", "MANAGER" -> AiVenueRole.MANAGER
            "STAFF" -> AiVenueRole.STAFF
            else -> null
        }

    private fun rolePriority(role: AiVenueRole): Int =
        when (role) {
            AiVenueRole.OWNER -> 0
            AiVenueRole.MANAGER -> 1
            AiVenueRole.STAFF -> 2
        }

    private fun roleLabel(role: AiVenueRole): String =
        when (role) {
            AiVenueRole.OWNER -> "OWNER"
            AiVenueRole.MANAGER -> "MANAGER"
            AiVenueRole.STAFF -> "STAFF"
        }

    private fun AiVenueRole.toAiAssistantRole(): AiAssistantRole =
        when (this) {
            AiVenueRole.OWNER -> AiAssistantRole.OWNER
            AiVenueRole.MANAGER -> AiAssistantRole.MANAGER
            AiVenueRole.STAFF -> AiAssistantRole.STAFF
        }

    private enum class AiVenueRole {
        OWNER,
        MANAGER,
        STAFF,
    }

    private data class AiVenueAccess(
        val venueId: Long,
        val role: AiVenueRole,
    )

    private data class PromotionDiagnosticsCallback(
        val venueId: Long,
        val promotionId: Long,
        val origin: AiAssistantOrigin,
    )

    private data class VenueSummaryCallback(
        val venueId: Long,
        val type: AiVenueSummaryType,
        val origin: AiAssistantOrigin,
    )

    private enum class AiAssistantOrigin {
        GENERAL,
        MARKETING,
        ;

        val backText: String
            get() =
                when (this) {
                    GENERAL -> "↩️ К заведению"
                    MARKETING -> "↩️ К продвижению"
                }

        val diagnosticsPromotionCallbackPrefix: String
            get() =
                when (this) {
                    GENERAL -> "venue_ai_diag_p"
                    MARKETING -> "vm_ai_diag_p"
                }

        fun rootCallbackData(venueId: Long): String =
            when (this) {
                GENERAL -> "venue_ai:$venueId"
                MARKETING -> "vm_ai:$venueId"
            }

        fun diagnosticsCallbackData(venueId: Long): String =
            when (this) {
                GENERAL -> "venue_ai_diag:$venueId"
                MARKETING -> "vm_ai_diag:$venueId"
            }

        fun summaryCallbackData(
            venueId: Long,
            type: AiVenueSummaryType,
        ): String =
            when (this) {
                GENERAL -> "venue_ai_sum:${type.callbackKey}:$venueId"
                MARKETING -> "vm_ai_sum:${type.callbackKey}:$venueId"
            }

        fun draftCallbackData(
            venueId: Long,
            kind: DraftKind,
        ): String =
            when (this) {
                GENERAL -> "venue_ai_draft:${kind.callbackKey}:$venueId"
                MARKETING -> "vm_ai_draft:${kind.callbackKey}:$venueId"
            }

        fun backCallbackData(venueId: Long): String =
            when (this) {
                GENERAL -> "owner_venue_hub:$venueId"
                MARKETING -> "venue_marketing_root:$venueId"
            }
    }

    private enum class DraftKind(
        val callbackKey: String,
        val state: DialogStateType,
        val aiType: AiDraftTextType,
        val title: String,
        val promptText: String,
    ) {
        PROMOTION_TEXT(
            callbackKey = "promotion",
            state = DialogStateType.AI_WAIT_PROMOTION_TEXT_BRIEF,
            aiType = AiDraftTextType.PROMOTION_TEXT,
            title = "✍️ Помочь с текстом акции",
            promptText =
                "✍️ Помочь с текстом акции\n\n" +
                    "Коротко опишите акцию. Например: кальян + чай по будням до 18:00.\n\n" +
                    "Помощник подготовит только черновик: название, описание и условия. Он ничего не сохранит.",
        ),
        FEEDBACK_REPLY(
            callbackKey = "review",
            state = DialogStateType.AI_WAIT_REVIEW_REPLY_BRIEF,
            aiType = AiDraftTextType.FEEDBACK_REPLY,
            title = "💬 Черновик ответа на отзыв",
            promptText =
                "💬 Черновик ответа на отзыв\n\n" +
                    "Вставьте текст отзыва или комментария гостя.\n\n" +
                    "Помощник подготовит черновик ответа. Он не отправит сообщение гостю.",
        ),
        BANNER_TEXT(
            callbackKey = "banner",
            state = DialogStateType.AI_WAIT_BANNER_TEXT_BRIEF,
            aiType = AiDraftTextType.BANNER_TEXT,
            title = "🖼 Текст для баннера",
            promptText =
                "🖼 Текст для баннера\n\n" +
                    "Коротко опишите баннер или афишу.\n\n" +
                    "Помощник подготовит только черновик текста. Изображение не создаётся и ничего не сохраняется.",
        ),
        ;

        companion object {
            fun fromCallbackKey(key: String): DraftKind? = entries.firstOrNull { it.callbackKey == key }

            fun fromState(state: DialogStateType): DraftKind? = entries.firstOrNull { it.state == state }
        }
    }

    companion object {
        fun isCallbackData(data: String): Boolean =
            data.startsWith("venue_ai:") ||
                data.startsWith("venue_ai_diag:") ||
                data.startsWith("venue_ai_diag_p:") ||
                data.startsWith("venue_ai_sum:") ||
                data.startsWith("venue_ai_draft:") ||
                data.startsWith("vm_ai:") ||
                data.startsWith("vm_ai_diag:") ||
                data.startsWith("vm_ai_diag_p:") ||
                data.startsWith("vm_ai_sum:") ||
                data.startsWith("vm_ai_draft:") ||
                data.startsWith("vm_ai_text:")

        fun isDialogState(state: DialogStateType): Boolean = DraftKind.fromState(state) != null
    }
}
