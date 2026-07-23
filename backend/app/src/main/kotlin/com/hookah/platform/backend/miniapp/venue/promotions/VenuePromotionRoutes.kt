package com.hookah.platform.backend.miniapp.venue.promotions

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.miniapp.venue.resolveVenueRole
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenuePromotion
import com.hookah.platform.backend.telegram.db.VenuePromotionRepository
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
            val items =
                (
                    venuePromotionRepository.listVenuePromotionsForManagement(venueId, limit = 100) +
                        venuePromotionRepository.listArchivedPromotionsForManagement(venueId, limit = 100)
                ).filter { it.templateType == VenuePromotionTemplateType.TEXT_ONLY }
                    .sortedWith(compareByDescending<VenuePromotion> { it.updatedAt }.thenByDescending { it.id })
            call.respond(
                VenuePromotionListResponse(
                    venueId = venueId,
                    timezone = timezone,
                    items = items.map { it.toDto() },
                ),
            )
        }

        post {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requirePromotionManagementAccess(venueAccessRepository, userId, venueId)
            val zoneId =
                venueSettingsRepository.resolveZoneId(
                    venueId = venueId,
                    fallback = ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE),
                )
            val request = call.receive<VenuePromotionCreateRequest>()
            val input = request.normalize(zoneId)
            val created =
                venuePromotionRepository.createPromotion(
                    venueId = venueId,
                    title = input.title,
                    description = input.description,
                    terms = input.terms,
                    startsAt = input.startsAt,
                    endsAt = input.endsAt,
                    templateType = VenuePromotionTemplateType.TEXT_ONLY,
                    createdByUserId = userId,
                )
            call.respond(VenuePromotionResponse(created.toDto()))
        }

        put("/{promotionId}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requirePromotionManagementAccess(venueAccessRepository, userId, venueId)
            val promotionId = call.requirePromotionId()
            val current =
                venuePromotionRepository.getPromotionForManagement(venueId, promotionId)
                    .requireSimpleInformationalPromotion()
            if (current.status == VenuePromotionStatus.ARCHIVED) {
                throw InvalidInputException("Архивную акцию нельзя редактировать.")
            }
            val zoneId =
                venueSettingsRepository.resolveZoneId(
                    venueId = venueId,
                    fallback = ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE),
                )
            val input = call.receive<VenuePromotionUpdateRequest>().normalize(zoneId)
            val updated =
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
            call.respond(VenuePromotionResponse(updated.toDto()))
        }

        post("/{promotionId}/status") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requirePromotionManagementAccess(venueAccessRepository, userId, venueId)
            val promotionId = call.requirePromotionId()
            val current =
                venuePromotionRepository.getPromotionForManagement(venueId, promotionId)
                    .requireSimpleInformationalPromotion()
            if (current.status == VenuePromotionStatus.ARCHIVED) {
                throw InvalidInputException("Архивную акцию нельзя активировать или приостановить.")
            }
            val status = parseMutableStatus(call.receive<VenuePromotionStatusRequest>().status)
            if (status == VenuePromotionStatus.ACTIVE) {
                validatePromotionPeriod(current.startsAt, current.endsAt)
            }
            val updated =
                venuePromotionRepository.setPromotionStatus(venueId, promotionId, status)
                    ?: throw NotFoundException()
            call.respond(VenuePromotionResponse(updated.toDto()))
        }

        delete("/{promotionId}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requirePromotionManagementAccess(venueAccessRepository, userId, venueId)
            val promotionId = call.requirePromotionId()
            val current =
                venuePromotionRepository.getPromotionForManagement(venueId, promotionId)
                    .requireSimpleInformationalPromotion()
            val archived =
                if (current.status == VenuePromotionStatus.ARCHIVED) {
                    current
                } else {
                    venuePromotionRepository.archivePromotion(venueId, promotionId)
                        ?: throw NotFoundException()
                }
            call.respond(VenuePromotionResponse(archived.toDto()))
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

private fun VenuePromotion?.requireSimpleInformationalPromotion(): VenuePromotion =
    this?.takeIf { it.templateType == VenuePromotionTemplateType.TEXT_ONLY }
        ?: throw NotFoundException()

private fun io.ktor.server.application.ApplicationCall.requirePromotionId(): Long {
    val raw = parameters["promotionId"] ?: throw InvalidInputException("promotionId is required")
    return raw.toLongOrNull()?.takeIf { it > 0L }
        ?: throw InvalidInputException("promotionId must be a positive number")
}

private fun VenuePromotion.toDto(): VenuePromotionDto =
    VenuePromotionDto(
        id = id,
        title = title,
        description = description,
        terms = terms,
        startsAt = startsAt?.toString(),
        endsAt = endsAt?.toString(),
        status = status.dbValue,
    )
