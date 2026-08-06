package com.hookah.platform.backend.miniapp.venue.promotions

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.PromotionLifecycleStaleException
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuRepository
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.miniapp.venue.resolveVenueRole
import com.hookah.platform.backend.telegram.db.GiftWithItemRewardInput
import com.hookah.platform.backend.telegram.db.HappyHoursRuleTargetInput
import com.hookah.platform.backend.telegram.db.PromotionRewardMode
import com.hookah.platform.backend.telegram.db.PromotionRuleReward
import com.hookah.platform.backend.telegram.db.PromotionRuleTargetType
import com.hookah.platform.backend.telegram.db.PromotionWeekdayWindow
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenuePromotion
import com.hookah.platform.backend.telegram.db.VenuePromotionLifecycleOutcome
import com.hookah.platform.backend.telegram.db.VenuePromotionLifecycleSource
import com.hookah.platform.backend.telegram.db.VenuePromotionRepository
import com.hookah.platform.backend.telegram.db.VenuePromotionRule
import com.hookah.platform.backend.telegram.db.VenuePromotionRuleRepository
import com.hookah.platform.backend.telegram.db.VenuePromotionStatus
import com.hookah.platform.backend.telegram.db.VenuePromotionTemplateType
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

private const val PROMOTION_TITLE_MAX_LENGTH = 80
private const val PROMOTION_DESCRIPTION_MAX_LENGTH = 1_000
private const val PROMOTION_TERMS_MAX_LENGTH = 1_000

fun Route.venuePromotionRoutes(
    venueAccessRepository: VenueAccessRepository,
    venuePromotionRepository: VenuePromotionRepository,
    venueSettingsRepository: VenueSettingsRepository,
    venuePromotionRuleRepository: VenuePromotionRuleRepository? = null,
    venueMenuRepository: VenueMenuRepository? = null,
) {
    route("/venue/{venueId}/promotions") {
        get {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requirePromotionManagementAccess(venueAccessRepository, userId, venueId)
            val timezone =
                venueSettingsRepository.resolveZoneId(
                    venueId = venueId,
                    fallback = ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE),
                ).id
            val promotions =
                (
                    venuePromotionRepository.listVenuePromotionsForManagement(venueId, limit = 100) +
                        venuePromotionRepository.listArchivedPromotionsForManagement(venueId, limit = 100)
                ).filter {
                    it.templateType in
                        setOf(
                            VenuePromotionTemplateType.TEXT_ONLY,
                            VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                            VenuePromotionTemplateType.GIFT_WITH_ITEM,
                        )
                }
                    .sortedWith(compareByDescending<VenuePromotion> { it.updatedAt }.thenByDescending { it.id })
            val menu = venueMenuRepository?.getMenu(venueId).orEmpty()
            call.respond(
                VenuePromotionListResponse(
                    venueId = venueId,
                    timezone = timezone,
                    items =
                        promotions.map { promotion ->
                            promotion.toDto(venuePromotionRuleRepository)
                        },
                    menuCategories =
                        menu.map { category ->
                            VenuePromotionMenuCategoryDto(id = category.id, name = category.name)
                        },
                    menuItems =
                        menu.flatMap { category ->
                            category.items.map { item ->
                                VenuePromotionMenuItemDto(
                                    id = item.id,
                                    name = item.name,
                                    categoryId = category.id,
                                )
                            }
                        },
                ),
            )
        }

        post {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requirePromotionManagementAccess(venueAccessRepository, userId, venueId)
            val request = call.receive<VenuePromotionCreateRequest>()
            val templateType = parseEditableTemplateType(request.templateType)
            val zoneId =
                if (
                    templateType in
                    setOf(
                        VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                        VenuePromotionTemplateType.GIFT_WITH_ITEM,
                    )
                ) {
                    venueSettingsRepository.resolvePromotionZoneId(
                        venueId = venueId,
                        fallback = ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE),
                    )
                } else {
                    venueSettingsRepository.resolveZoneId(
                        venueId = venueId,
                        fallback = ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE),
                    )
                }
            val input = request.normalize(zoneId)
            val happyHoursInput =
                if (templateType == VenuePromotionTemplateType.HAPPY_HOURS_PERCENT) {
                    request.rule.normalizeHappyHoursRule()
                } else {
                    null
                }
            val giftInput =
                if (templateType == VenuePromotionTemplateType.GIFT_WITH_ITEM) {
                    request.rule.normalizeGiftWithItemRule()
                } else {
                    null
                }
            val created =
                when {
                    happyHoursInput != null -> {
                        val ruleRepository =
                            venuePromotionRuleRepository
                                ?: throw InvalidInputException("Настройка Happy Hours временно недоступна.")
                        runRuleMutation {
                            venuePromotionRepository.createPromotion(
                                venueId = venueId,
                                title = input.title,
                                description = input.description,
                                terms = input.terms,
                                startsAt = input.startsAt,
                                endsAt = input.endsAt,
                                templateType = templateType,
                                createdByUserId = userId,
                                afterInsert = { connection, promotionId ->
                                    ruleRepository.createHappyHoursDraftRule(
                                        connection = connection,
                                        venueId = venueId,
                                        promotionId = promotionId,
                                        target = happyHoursInput.target,
                                        discountPercent = happyHoursInput.discountPercent,
                                        weekdayWindows = happyHoursInput.windows,
                                        createdByUserId = userId,
                                    )
                                },
                            )
                        }
                    }
                    giftInput != null -> {
                        val ruleRepository =
                            venuePromotionRuleRepository
                                ?: throw InvalidInputException("Настройка подарочной акции временно недоступна.")
                        runRuleMutation {
                            venuePromotionRepository.createPromotion(
                                venueId = venueId,
                                title = input.title,
                                description = input.description,
                                terms = input.terms,
                                startsAt = input.startsAt,
                                endsAt = input.endsAt,
                                templateType = templateType,
                                createdByUserId = userId,
                                afterInsert = { connection, promotionId ->
                                    ruleRepository.createGiftWithItemDraftRule(
                                        connection = connection,
                                        venueId = venueId,
                                        promotionId = promotionId,
                                        target = giftInput.target,
                                        reward = giftInput.reward,
                                        weekdayWindows = giftInput.windows,
                                        createdByUserId = userId,
                                    )
                                },
                            )
                        }
                    }
                    else ->
                        venuePromotionRepository.createPromotion(
                            venueId = venueId,
                            title = input.title,
                            description = input.description,
                            terms = input.terms,
                            startsAt = input.startsAt,
                            endsAt = input.endsAt,
                            templateType = templateType,
                            createdByUserId = userId,
                        )
                }
            call.respond(VenuePromotionResponse(created.toDto(venuePromotionRuleRepository)))
        }

        put("/{promotionId}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requirePromotionManagementAccess(venueAccessRepository, userId, venueId)
            val promotionId = call.requirePromotionId()
            val current =
                venuePromotionRepository.getPromotionForManagement(venueId, promotionId)
                    .requireEditablePromotion()
            if (current.status == VenuePromotionStatus.ARCHIVED) {
                throw InvalidInputException("Архивную акцию нельзя редактировать.")
            }
            val zoneId =
                if (
                    current.templateType in
                    setOf(
                        VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                        VenuePromotionTemplateType.GIFT_WITH_ITEM,
                    )
                ) {
                    venueSettingsRepository.resolvePromotionZoneId(
                        venueId = venueId,
                        fallback = ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE),
                    )
                } else {
                    venueSettingsRepository.resolveZoneId(
                        venueId = venueId,
                        fallback = ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE),
                    )
                }
            val request = call.receive<VenuePromotionUpdateRequest>()
            val requestedTemplate = parseEditableTemplateType(request.templateType)
            if (requestedTemplate != current.templateType) {
                throw InvalidInputException("Тип созданной акции изменить нельзя.")
            }
            val input = request.normalize(zoneId)
            val updated =
                when (current.templateType) {
                    VenuePromotionTemplateType.HAPPY_HOURS_PERCENT -> {
                        val ruleRepository =
                            venuePromotionRuleRepository
                                ?: throw InvalidInputException("Настройка Happy Hours временно недоступна.")
                        val happyHoursInput = request.rule.normalizeHappyHoursRule()
                        runRuleMutation {
                            venuePromotionRepository.updatePromotion(
                                venueId = venueId,
                                promotionId = promotionId,
                                title = input.title,
                                description = input.description,
                                terms = input.terms,
                                clearTerms = input.terms == null,
                                startsAt = input.startsAt,
                                endsAt = input.endsAt,
                                afterUpdate = { connection, _ ->
                                    val currentRule =
                                        ruleRepository
                                            .listHappyHoursRulesForPromotionManagement(
                                                connection = connection,
                                                venueId = venueId,
                                                promotionId = promotionId,
                                            ).requireSingleEditableRule(
                                                multipleMessage =
                                                    "Несколько legacy-правил можно редактировать только в Telegram.",
                                                missingMessage = "Правило Happy Hours не настроено.",
                                            )
                                    ruleRepository.updateHappyHoursDraftRule(
                                        connection = connection,
                                        venueId = venueId,
                                        promotionId = promotionId,
                                        ruleId = currentRule.id,
                                        target = happyHoursInput.target,
                                        discountPercent = happyHoursInput.discountPercent,
                                        weekdayWindows = happyHoursInput.windows,
                                    ) ?: throw NotFoundException()
                                },
                            ) ?: throw NotFoundException()
                        }
                    }
                    VenuePromotionTemplateType.GIFT_WITH_ITEM -> {
                        val ruleRepository =
                            venuePromotionRuleRepository
                                ?: throw InvalidInputException("Настройка подарочной акции временно недоступна.")
                        val giftInput = request.rule.normalizeGiftWithItemRule()
                        runRuleMutation {
                            venuePromotionRepository.updatePromotion(
                                venueId = venueId,
                                promotionId = promotionId,
                                title = input.title,
                                description = input.description,
                                terms = input.terms,
                                clearTerms = input.terms == null,
                                startsAt = input.startsAt,
                                endsAt = input.endsAt,
                                afterUpdate = { connection, _ ->
                                    val currentRule =
                                        ruleRepository
                                            .listGiftWithItemRulesForPromotionManagement(
                                                connection = connection,
                                                venueId = venueId,
                                                promotionId = promotionId,
                                            ).requireSingleEditableRule(
                                                multipleMessage =
                                                    "Несколько legacy-правил можно редактировать только в Telegram.",
                                                missingMessage = "Правило подарка не настроено.",
                                            )
                                    ruleRepository.updateGiftWithItemDraftRule(
                                        connection = connection,
                                        venueId = venueId,
                                        promotionId = promotionId,
                                        ruleId = currentRule.id,
                                        target = giftInput.target,
                                        reward = giftInput.reward,
                                        weekdayWindows = giftInput.windows,
                                    ) ?: throw NotFoundException()
                                },
                            ) ?: throw NotFoundException()
                        }
                    }
                    else ->
                        venuePromotionRepository.updatePromotion(
                            venueId = venueId,
                            promotionId = promotionId,
                            title = input.title,
                            description = input.description,
                            terms = input.terms,
                            clearTerms = input.terms == null,
                            startsAt = input.startsAt,
                            endsAt = input.endsAt,
                        ) ?: throw NotFoundException()
                }
            call.respond(VenuePromotionResponse(updated.toDto(venuePromotionRuleRepository)))
        }

        post("/{promotionId}/status") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requirePromotionManagementAccess(venueAccessRepository, userId, venueId)
            val promotionId = call.requirePromotionId()
            val current =
                venuePromotionRepository.getPromotionForManagement(venueId, promotionId)
                    .requireEditablePromotion()
            if (current.status == VenuePromotionStatus.ARCHIVED) {
                throw InvalidInputException("Архивную акцию нельзя активировать или приостановить.")
            }
            val status = parseMutableStatus(call.receive<VenuePromotionStatusRequest>().status)
            if (status == VenuePromotionStatus.ACTIVE) {
                validatePromotionPeriod(current.startsAt, current.endsAt)
            }
            val mutation =
                venuePromotionRepository.mutatePromotionLifecycle(
                    venueId = venueId,
                    promotionId = promotionId,
                    expectedStatus = current.status,
                    targetStatus = status,
                    actorUserId = userId,
                    source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                ) ?: throw NotFoundException()
            if (mutation.outcome == VenuePromotionLifecycleOutcome.STALE) {
                throw PromotionLifecycleStaleException()
            }
            call.respond(VenuePromotionResponse(mutation.promotion.toDto(venuePromotionRuleRepository)))
        }

        delete("/{promotionId}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requirePromotionManagementAccess(venueAccessRepository, userId, venueId)
            val promotionId = call.requirePromotionId()
            val current =
                venuePromotionRepository.getPromotionForManagement(venueId, promotionId)
                    .requireEditablePromotion()
            val archived =
                if (current.status == VenuePromotionStatus.ARCHIVED) {
                    current
                } else {
                    val mutation =
                        venuePromotionRepository.mutatePromotionLifecycle(
                            venueId = venueId,
                            promotionId = promotionId,
                            expectedStatus = current.status,
                            targetStatus = VenuePromotionStatus.ARCHIVED,
                            actorUserId = userId,
                            source = VenuePromotionLifecycleSource.VENUE_MINI_APP,
                        ) ?: throw NotFoundException()
                    if (mutation.outcome == VenuePromotionLifecycleOutcome.STALE) {
                        throw PromotionLifecycleStaleException()
                    }
                    mutation.promotion
                }
            call.respond(VenuePromotionResponse(archived.toDto(venuePromotionRuleRepository)))
        }
    }
}

private suspend fun requirePromotionManagementAccess(
    venueAccessRepository: VenueAccessRepository,
    userId: Long,
    venueId: Long,
) {
    val role = resolveVenueRole(venueAccessRepository, userId, venueId)
    if (role !in setOf(VenueRole.OWNER, VenueRole.MANAGER)) {
        throw ForbiddenException()
    }
}

private data class NormalizedPromotionInput(
    val title: String,
    val description: String,
    val terms: String?,
    val startsAt: Instant,
    val endsAt: Instant,
)

private data class NormalizedHappyHoursRuleInput(
    val windows: List<PromotionWeekdayWindow>,
    val target: HappyHoursRuleTargetInput,
    val discountPercent: Int,
)

private data class NormalizedGiftWithItemRuleInput(
    val windows: List<PromotionWeekdayWindow>,
    val target: HappyHoursRuleTargetInput,
    val reward: GiftWithItemRewardInput,
)

private fun VenuePromotionCreateRequest.normalize(zoneId: ZoneId): NormalizedPromotionInput =
    normalizePromotionInput(title, description, terms, startsAt, endsAt, zoneId)

private fun VenuePromotionUpdateRequest.normalize(zoneId: ZoneId): NormalizedPromotionInput =
    normalizePromotionInput(title, description, terms, startsAt, endsAt, zoneId)

private fun normalizePromotionInput(
    rawTitle: String,
    rawDescription: String,
    rawTerms: String?,
    rawStartsAt: String,
    rawEndsAt: String,
    zoneId: ZoneId,
): NormalizedPromotionInput {
    val title = normalizeRequiredText(rawTitle, PROMOTION_TITLE_MAX_LENGTH, "Название акции")
    val description = normalizeRequiredText(rawDescription, PROMOTION_DESCRIPTION_MAX_LENGTH, "Описание")
    val terms = normalizeOptionalText(rawTerms, PROMOTION_TERMS_MAX_LENGTH, "Условия")
    val startsAt = parsePromotionInstant(rawStartsAt, zoneId, "Начало")
    val endsAt = parsePromotionInstant(rawEndsAt, zoneId, "Окончание")
    validatePromotionPeriod(startsAt, endsAt)
    return NormalizedPromotionInput(title, description, terms, startsAt, endsAt)
}

private fun normalizeRequiredText(
    value: String,
    maxLength: Int,
    field: String,
): String {
    val normalized = value.trim()
    if (normalized.isBlank()) {
        throw InvalidInputException("$field не может быть пустым.")
    }
    if (normalized.length > maxLength) {
        throw InvalidInputException("$field должно быть не длиннее $maxLength символов.")
    }
    return normalized
}

private fun normalizeOptionalText(
    value: String?,
    maxLength: Int,
    field: String,
): String? {
    val normalized = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (normalized.length > maxLength) {
        throw InvalidInputException("$field должны быть не длиннее $maxLength символов.")
    }
    return normalized
}

private fun parsePromotionInstant(
    value: String,
    zoneId: ZoneId,
    field: String,
): Instant {
    val normalized = value.trim()
    if (normalized.isBlank()) {
        throw InvalidInputException("$field обязательно.")
    }
    return try {
        runCatching { Instant.parse(normalized) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(normalized).toInstant() }.getOrNull()
            ?: LocalDateTime.parse(normalized).atZone(zoneId).toInstant()
    } catch (_: DateTimeException) {
        throw InvalidInputException("$field должно быть корректной датой и временем.")
    }
}

private fun validatePromotionPeriod(
    startsAt: Instant?,
    endsAt: Instant?,
) {
    if (startsAt == null || endsAt == null) {
        throw InvalidInputException("Начало и окончание акции обязательны.")
    }
    if (!startsAt.isBefore(endsAt)) {
        throw InvalidInputException("Начало акции должно быть раньше окончания.")
    }
}

private fun parseMutableStatus(rawStatus: String): VenuePromotionStatus {
    return when (rawStatus.trim().uppercase(Locale.ROOT)) {
        VenuePromotionStatus.ACTIVE.dbValue -> VenuePromotionStatus.ACTIVE
        VenuePromotionStatus.PAUSED.dbValue -> VenuePromotionStatus.PAUSED
        else -> throw InvalidInputException("Статус должен быть ACTIVE или PAUSED.")
    }
}

private fun parseEditableTemplateType(rawTemplateType: String): VenuePromotionTemplateType =
    when (rawTemplateType.trim().uppercase(Locale.ROOT)) {
        VenuePromotionTemplateType.TEXT_ONLY.dbValue -> VenuePromotionTemplateType.TEXT_ONLY
        VenuePromotionTemplateType.HAPPY_HOURS_PERCENT.dbValue ->
            VenuePromotionTemplateType.HAPPY_HOURS_PERCENT
        VenuePromotionTemplateType.GIFT_WITH_ITEM.dbValue ->
            VenuePromotionTemplateType.GIFT_WITH_ITEM
        else -> throw InvalidInputException("Выберите поддерживаемый тип акции.")
    }

private fun VenuePromotionRuleMutationRequest?.normalizeHappyHoursRule(): NormalizedHappyHoursRuleInput {
    val request = this ?: throw InvalidInputException("Настройте условия Happy Hours.")
    val discountPercent =
        request.discountPercent
            ?: throw InvalidInputException("Укажите процент скидки.")
    if (discountPercent !in 1..100) {
        throw InvalidInputException("Процент скидки должен быть от 1 до 100.")
    }
    if (request.reward != null) {
        throw InvalidInputException("Для Happy Hours подарок не настраивается.")
    }
    return NormalizedHappyHoursRuleInput(
        windows = request.windows.normalizeRuleWindows(),
        target = request.target.normalizeRuleTarget(),
        discountPercent = discountPercent,
    )
}

private fun VenuePromotionRuleMutationRequest?.normalizeGiftWithItemRule(): NormalizedGiftWithItemRuleInput {
    val request = this ?: throw InvalidInputException("Настройте условия подарочной акции.")
    if (request.discountPercent != null) {
        throw InvalidInputException("Для подарочной акции процент скидки не настраивается.")
    }
    val rewardRequest = request.reward ?: throw InvalidInputException("Настройте подарок.")
    val rewardMode =
        when (rewardRequest.mode.trim().uppercase(Locale.ROOT)) {
            PromotionRewardMode.FIXED_ITEM.dbValue -> PromotionRewardMode.FIXED_ITEM
            PromotionRewardMode.CHOICE_ITEMS.dbValue -> PromotionRewardMode.CHOICE_ITEMS
            else -> throw InvalidInputException("Выберите фиксированный подарок или подарок на выбор.")
        }
    val reward =
        when (rewardMode) {
            PromotionRewardMode.FIXED_ITEM -> {
                if (rewardRequest.allowlistMenuItemIds.isNotEmpty()) {
                    throw InvalidInputException("Для фиксированного подарка список выбора должен быть пуст.")
                }
                GiftWithItemRewardInput(
                    mode = rewardMode,
                    fixedMenuItemId =
                        rewardRequest.fixedMenuItemId?.takeIf { it > 0L }
                            ?: throw InvalidInputException("Выберите подарок."),
                )
            }
            PromotionRewardMode.CHOICE_ITEMS -> {
                if (rewardRequest.fixedMenuItemId != null) {
                    throw InvalidInputException("Для подарка на выбор фиксированная позиция не указывается.")
                }
                val allowlistMenuItemIds = rewardRequest.allowlistMenuItemIds.distinct()
                if (allowlistMenuItemIds.isEmpty() || allowlistMenuItemIds.any { it <= 0L }) {
                    throw InvalidInputException("Добавьте хотя бы одну позицию в список подарков.")
                }
                GiftWithItemRewardInput(
                    mode = rewardMode,
                    allowlistMenuItemIds = allowlistMenuItemIds,
                )
            }
        }
    return NormalizedGiftWithItemRuleInput(
        windows = request.windows.normalizeRuleWindows(),
        target = request.target.normalizeRuleTarget(),
        reward = reward,
    )
}

private fun List<VenuePromotionWeekdayWindowDto>.normalizeRuleWindows(): List<PromotionWeekdayWindow> {
    if (isEmpty()) {
        throw InvalidInputException("Добавьте хотя бы одно временное окно.")
    }
    return map { window ->
        PromotionWeekdayWindow(
            weekday = window.weekday,
            startsMinute = parseLocalMinute(window.startLocal, allowEndOfDay = false),
            endsMinute = parseLocalMinute(window.endLocal, allowEndOfDay = true),
        )
    }
}

private fun VenuePromotionTargetDto.normalizeRuleTarget(): HappyHoursRuleTargetInput {
    val targetType =
        when (type.trim().uppercase(Locale.ROOT)) {
            PromotionRuleTargetType.MENU_ITEM.dbValue -> PromotionRuleTargetType.MENU_ITEM
            PromotionRuleTargetType.MENU_CATEGORY.dbValue -> PromotionRuleTargetType.MENU_CATEGORY
            else -> throw InvalidInputException("Выберите категорию или позицию меню.")
        }
    return when (targetType) {
        PromotionRuleTargetType.MENU_ITEM ->
            HappyHoursRuleTargetInput(
                targetType = targetType,
                menuItemId =
                    menuItemId?.takeIf { it > 0L }
                        ?: throw InvalidInputException("Выберите позицию меню."),
            )
        PromotionRuleTargetType.MENU_CATEGORY ->
            HappyHoursRuleTargetInput(
                targetType = targetType,
                menuCategoryId =
                    menuCategoryId?.takeIf { it > 0L }
                        ?: throw InvalidInputException("Выберите категорию меню."),
            )
        PromotionRuleTargetType.CATEGORY_TYPE ->
            throw InvalidInputException("Выберите категорию или позицию меню.")
    }
}

private fun parseLocalMinute(
    raw: String,
    allowEndOfDay: Boolean,
): Int {
    val normalized = raw.trim()
    if (allowEndOfDay && normalized == "24:00") {
        return 24 * 60
    }
    val time =
        try {
            LocalTime.parse(normalized)
        } catch (_: DateTimeException) {
            throw InvalidInputException("Время должно быть указано в формате ЧЧ:ММ.")
        }
    if (time.second != 0 || time.nano != 0) {
        throw InvalidInputException("Время должно быть указано с точностью до минуты.")
    }
    return time.hour * 60 + time.minute
}

private suspend fun <T> runRuleMutation(block: suspend () -> T): T =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        throw InvalidInputException(
            when {
                e.message?.contains("overlap", ignoreCase = true) == true ->
                    "Временные окна одного дня не должны пересекаться."
                e.message?.contains("target", ignoreCase = true) == true ->
                    "Выбранная категория или позиция недоступна для этого заведения."
                e.message?.contains("reward", ignoreCase = true) == true ||
                    e.message?.contains("allowlist", ignoreCase = true) == true ->
                    "Выбранный подарок недоступен для этого заведения."
                else -> "Проверьте настройки акции."
            },
        )
    }

private fun List<VenuePromotionRule>.requireSingleEditableRule(
    multipleMessage: String,
    missingMessage: String,
): VenuePromotionRule =
    singleOrNull()
        ?: if (size > 1) {
            throw InvalidInputException(multipleMessage)
        } else {
            throw InvalidInputException(missingMessage)
        }

private fun VenuePromotion?.requireEditablePromotion(): VenuePromotion =
    this?.takeIf {
        it.templateType in
            setOf(
                VenuePromotionTemplateType.TEXT_ONLY,
                VenuePromotionTemplateType.HAPPY_HOURS_PERCENT,
                VenuePromotionTemplateType.GIFT_WITH_ITEM,
            )
    } ?: throw NotFoundException()

private fun io.ktor.server.application.ApplicationCall.requirePromotionId(): Long {
    val raw = parameters["promotionId"] ?: throw InvalidInputException("promotionId is required")
    return raw.toLongOrNull()?.takeIf { it > 0L }
        ?: throw InvalidInputException("promotionId must be a positive number")
}

private data class PromotionRuleReadinessView(
    val isReady: Boolean,
    val errors: List<String>,
    val rule: VenuePromotionRule?,
    val ruleCount: Int,
)

private suspend fun VenuePromotion.toDto(ruleRepository: VenuePromotionRuleRepository?): VenuePromotionDto {
    val readiness =
        when {
            ruleRepository == null -> null
            templateType == VenuePromotionTemplateType.HAPPY_HOURS_PERCENT ->
                ruleRepository.validateHappyHoursActivationReadiness(venueId, id).let {
                    PromotionRuleReadinessView(
                        isReady = it.isReady,
                        errors = it.errors,
                        rule = it.rule,
                        ruleCount = it.ruleCount,
                    )
                }
            templateType == VenuePromotionTemplateType.GIFT_WITH_ITEM ->
                ruleRepository.validateGiftWithItemActivationReadiness(venueId, id).let {
                    PromotionRuleReadinessView(
                        isReady = it.isReady,
                        errors = it.errors,
                        rule = it.rule,
                        ruleCount = it.ruleCount,
                    )
                }
            else -> null
        }
    val rule = readiness?.rule
    val target = rule?.toEditableTargetDto()
    val reward =
        rule
            ?.reward
            ?.takeIf { templateType == VenuePromotionTemplateType.GIFT_WITH_ITEM }
            ?.toDto()
    val extraIssues =
        when {
            readiness?.ruleCount?.let { it > 1 } == true ->
                listOf(
                    "Акция содержит несколько legacy-правил Telegram. " +
                        "Здесь их можно включать и приостанавливать, " +
                        "а редактировать — в Telegram.",
                )
            rule != null && target == null ->
                listOf(
                    "Legacy-правило настроено в Telegram " +
                        "и недоступно для редактирования здесь.",
                )
            else -> emptyList()
        }
    return VenuePromotionDto(
        id = id,
        title = title,
        description = description,
        terms = terms,
        startsAt = startsAt?.toString(),
        endsAt = endsAt?.toString(),
        status = status.dbValue,
        templateType = templateType.dbValue,
        rule =
            rule?.let {
                VenuePromotionRuleDto(
                    id = it.id,
                    version = it.version,
                    windows =
                        it.weekdayWindows
                            .sortedWith(
                                compareBy<PromotionWeekdayWindow> { window -> window.weekday }
                                    .thenBy { window -> window.startsMinute },
                            )
                            .map { window ->
                                VenuePromotionWeekdayWindowDto(
                                    weekday = window.weekday,
                                    startLocal = formatLocalMinute(window.startsMinute),
                                    endLocal = formatLocalMinute(window.endsMinute),
                                )
                            },
                    target = target,
                    discountPercent =
                        it.discountPercent.takeIf {
                            templateType == VenuePromotionTemplateType.HAPPY_HOURS_PERCENT
                        },
                    reward = reward,
                    summary =
                        if (
                            templateType == VenuePromotionTemplateType.GIFT_WITH_ITEM &&
                            target != null &&
                            reward != null
                        ) {
                            it.toGiftSummary(target, reward)
                        } else {
                            null
                        },
                    readyForActivation = readiness?.isReady == true,
                    validationIssues = (readiness?.errors.orEmpty() + extraIssues).distinct(),
                )
            },
    )
}

private fun VenuePromotionRule.toEditableTargetDto(): VenuePromotionTargetDto? {
    val target = targets.singleOrNull() ?: return null
    return when (target.targetType) {
        PromotionRuleTargetType.MENU_ITEM ->
            VenuePromotionTargetDto(
                type = target.targetType.dbValue,
                menuItemId = target.menuItemId,
                label = target.menuItemName,
            )
        PromotionRuleTargetType.MENU_CATEGORY ->
            VenuePromotionTargetDto(
                type = target.targetType.dbValue,
                menuCategoryId = target.menuCategoryId,
                label = target.menuCategoryName,
            )
        PromotionRuleTargetType.CATEGORY_TYPE -> null
    }
}

private fun PromotionRuleReward.toDto(): VenuePromotionRewardDto =
    when (rewardMode) {
        PromotionRewardMode.FIXED_ITEM ->
            VenuePromotionRewardDto(
                mode = rewardMode.dbValue,
                fixedItem =
                    VenuePromotionRewardItemDto(
                        menuItemId = rewardMenuItemId,
                        name = rewardMenuItemName,
                    ),
                maxRewardsPerBatch = maxRewardsPerBatch,
            )
        PromotionRewardMode.CHOICE_ITEMS ->
            VenuePromotionRewardDto(
                mode = rewardMode.dbValue,
                allowlist =
                    options.map { option ->
                        VenuePromotionRewardItemDto(
                            menuItemId = option.menuItemId,
                            name = option.menuItemName,
                        )
                    },
                maxRewardsPerBatch = maxRewardsPerBatch,
            )
    }

private fun VenuePromotionRule.toGiftSummary(
    target: VenuePromotionTargetDto,
    reward: VenuePromotionRewardDto,
): VenuePromotionRuleSummaryDto {
    val triggerLabel = target.label?.takeIf { it.isNotBlank() } ?: "Без названия"
    val trigger =
        when (target.type) {
            PromotionRuleTargetType.MENU_CATEGORY.dbValue ->
                "При заказе: Категория «$triggerLabel»"
            else -> "При заказе: Позиция «$triggerLabel»"
        }
    val rewardText =
        when (reward.mode) {
            PromotionRewardMode.FIXED_ITEM.dbValue ->
                "Подарок: «${reward.fixedItem?.name ?: "Без названия"}»"
            else -> {
                val names = reward.allowlist.map { it.name }
                val visibleNames =
                    names
                        .take(3)
                        .joinToString(", ")
                        .let { if (names.size > 3) "$it…" else it }
                        .ifBlank { "не настроен" }
                "Подарок: $visibleNames на выбор"
            }
        }
    return VenuePromotionRuleSummaryDto(
        schedule = summarizeWeekdayWindows(weekdayWindows),
        trigger = trigger,
        reward = rewardText,
    )
}

private fun summarizeWeekdayWindows(windows: List<PromotionWeekdayWindow>): String {
    if (windows.isEmpty()) return "Расписание не настроено"
    return windows
        .groupBy { it.startsMinute to it.endsMinute }
        .entries
        .sortedWith(
            compareBy<Map.Entry<Pair<Int, Int>, List<PromotionWeekdayWindow>>> {
                it.value.minOfOrNull { window -> window.weekday } ?: Int.MAX_VALUE
            }.thenBy { it.key.first },
        )
        .joinToString("; ") { (timeRange, groupedWindows) ->
            val weekdays = summarizeWeekdays(groupedWindows.map { it.weekday })
            "$weekdays, ${formatLocalMinute(timeRange.first)}–${formatLocalMinute(timeRange.second)}"
        }
}

private fun summarizeWeekdays(weekdays: List<Int>): String {
    val labels = listOf("", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    val sorted = weekdays.filter { it in 1..7 }.distinct().sorted()
    if (sorted.isEmpty()) return "Дни не выбраны"
    val ranges = mutableListOf<IntRange>()
    var rangeStart = sorted.first()
    var previous = rangeStart
    sorted.drop(1).forEach { weekday ->
        if (weekday == previous + 1) {
            previous = weekday
        } else {
            ranges += rangeStart..previous
            rangeStart = weekday
            previous = weekday
        }
    }
    ranges += rangeStart..previous
    return ranges.joinToString(", ") { range ->
        if (range.first == range.last) {
            labels[range.first]
        } else {
            "${labels[range.first]}–${labels[range.last]}"
        }
    }
}

private fun formatLocalMinute(minutes: Int): String =
    if (minutes == 24 * 60) {
        "24:00"
    } else {
        "%02d:%02d".format(Locale.ROOT, minutes / 60, minutes % 60)
    }
