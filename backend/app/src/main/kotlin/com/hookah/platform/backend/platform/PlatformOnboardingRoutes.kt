package com.hookah.platform.backend.platform

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.onboarding.VenueOnboardingService
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestApplicantRecord
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestCreateLinkResult
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestMutationResult
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestRecord
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestRepository
import com.hookah.platform.backend.telegram.db.VenueOnboardingSource
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class PlatformOnboardingRequestListResponse(
    val requests: List<PlatformOnboardingRequestDto>,
)

@Serializable
data class PlatformOnboardingRequestResponse(
    val request: PlatformOnboardingRequestDto,
)

@Serializable
data class PlatformOnboardingRequestDto(
    val id: Long,
    val applicant: PlatformOnboardingApplicantDto,
    val venueName: String,
    val city: String,
    val contact: String,
    val comment: String? = null,
    val status: String,
    val createdAt: String,
    val linkedVenueId: Long? = null,
    val trialConfigured: Boolean,
    val trialEndsOn: String? = null,
    val currentPriceRub: Long? = null,
    val futurePriceRub: Long? = null,
    val futurePriceEffectiveOn: String? = null,
    val commercialNote: String? = null,
)

@Serializable
data class PlatformOnboardingApplicantDto(
    val userId: Long,
    val username: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
)

@Serializable
data class PlatformOnboardingTermsRequest(
    val trialConfigured: Boolean,
    val trialEndsOn: String? = null,
    val currentPriceRub: Long,
    val futurePriceRub: Long? = null,
    val futurePriceEffectiveOn: String? = null,
    val commercialNote: String? = null,
)

@Serializable
data class PlatformOnboardingCreateLinkResponse(
    val request: PlatformOnboardingRequestDto,
    val venueId: Long,
    val created: Boolean,
)

@Serializable
data class PlatformOperationalOwnerListResponse(
    val owners: List<PlatformOperationalOwnerDto>,
)

@Serializable
data class PlatformOperationalOwnerResponse(
    val owner: PlatformOperationalOwnerDto,
    val venues: List<PlatformOperationalOwnerVenueDto>,
)

@Serializable
data class PlatformOperationalOwnerDto(
    val userId: Long,
    val username: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val venueCount: Int,
    val venueStatusCounts: Map<String, Int>,
)

@Serializable
data class PlatformOperationalOwnerVenueDto(
    val id: Long,
    val name: String,
    val city: String? = null,
    val status: String,
    val createdAt: String,
)

fun Route.platformOnboardingRoutes(
    platformConfig: PlatformConfig,
    venueOnboardingService: VenueOnboardingService,
    platformVenueRepository: PlatformVenueRepository,
) {
    route("/platform/onboarding/requests") {
        get {
            call.requirePlatformOwner(platformConfig)
            val statuses = parseOnboardingStatuses(call.request.queryParameters["status"])
            val query = call.request.queryParameters["q"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val requests = venueOnboardingService.listPlatformApplications(statuses, query, limit, offset)
            call.respond(PlatformOnboardingRequestListResponse(requests.map { it.toPlatformDto() }))
        }

        get("/{requestId}") {
            call.requirePlatformOwner(platformConfig)
            val request =
                venueOnboardingService.findPlatformApplication(call.requireOnboardingRequestId())
                    ?: throw NotFoundException()
            call.respond(PlatformOnboardingRequestResponse(request.toPlatformDto()))
        }

        post("/{requestId}/approve") {
            val actorUserId = call.requirePlatformOwner(platformConfig)
            call.respondDecision(
                venueOnboardingService.decideApplication(
                    requestId = call.requireOnboardingRequestId(),
                    actorUserId = actorUserId,
                    approved = true,
                    source = VenueOnboardingSource.PLATFORM_MINI_APP,
                ),
                venueOnboardingService,
            )
        }

        post("/{requestId}/reject") {
            val actorUserId = call.requirePlatformOwner(platformConfig)
            call.respondDecision(
                venueOnboardingService.decideApplication(
                    requestId = call.requireOnboardingRequestId(),
                    actorUserId = actorUserId,
                    approved = false,
                    source = VenueOnboardingSource.PLATFORM_MINI_APP,
                ),
                venueOnboardingService,
            )
        }

        post("/{requestId}/close") {
            val actorUserId = call.requirePlatformOwner(platformConfig)
            call.respondDecision(
                venueOnboardingService.closeApprovedApplication(
                    requestId = call.requireOnboardingRequestId(),
                    actorUserId = actorUserId,
                    source = VenueOnboardingSource.PLATFORM_MINI_APP,
                ),
                venueOnboardingService,
            )
        }

        put("/{requestId}/commercial-terms") {
            val actorUserId = call.requirePlatformOwner(platformConfig)
            val requestId = call.requireOnboardingRequestId()
            val terms = call.receive<PlatformOnboardingTermsRequest>().normalized()
            call.respondDecision(
                venueOnboardingService.updateCommercialTerms(
                    requestId = requestId,
                    actorUserId = actorUserId,
                    trialConfigured = terms.trialConfigured,
                    trialEndsOn = terms.trialEndsOn,
                    currentPriceRub = terms.currentPriceRub,
                    futurePriceRub = terms.futurePriceRub,
                    futurePriceEffectiveOn = terms.futurePriceEffectiveOn,
                    commercialNote = terms.commercialNote,
                    source = VenueOnboardingSource.PLATFORM_MINI_APP,
                ),
                venueOnboardingService,
            )
        }

        post("/{requestId}/create-and-link") {
            val actorUserId = call.requirePlatformOwner(platformConfig)
            when (
                val result =
                    venueOnboardingService.createDraftAndLink(
                        requestId = call.requireOnboardingRequestId(),
                        actorUserId = actorUserId,
                        source = VenueOnboardingSource.PLATFORM_MINI_APP,
                    )
            ) {
                is VenueConnectionRequestCreateLinkResult.Success -> {
                    val applicant =
                        venueOnboardingService.findPlatformApplication(result.request.id)
                            ?: throw NotFoundException()
                    call.respond(
                        PlatformOnboardingCreateLinkResponse(
                            request = applicant.toPlatformDto(),
                            venueId = result.venueId,
                            created = result.created,
                        ),
                    )
                }
                is VenueConnectionRequestCreateLinkResult.QuotaExceeded ->
                    throw InvalidInputException(
                        "Лимит заведений исчерпан: ${result.usedVenuesCount} из ${result.allowedVenuesCount}. " +
                            "Сначала увеличьте лимит владельца.",
                    )
                is VenueConnectionRequestCreateLinkResult.CommercialTermsMissing ->
                    throw InvalidInputException("Сначала заполните корректные коммерческие условия.")
                VenueConnectionRequestCreateLinkResult.ApplicantNotOperationalOwner ->
                    throw InvalidInputException(
                        "У заявителя больше нет активной роли Owner. Сначала восстановите операционный доступ.",
                    )
                is VenueConnectionRequestCreateLinkResult.InvalidState ->
                    throw InvalidInputException("Создать заведение можно только из одобренной заявки.")
                VenueConnectionRequestCreateLinkResult.NotFound -> throw NotFoundException()
            }
        }
    }

    route("/platform/owners") {
        get {
            call.requirePlatformOwner(platformConfig)
            val query = call.request.queryParameters["q"]
            val status = parseVenueStatus(call.request.queryParameters["status"])
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val owners = platformVenueRepository.listOperationalOwners(query, status, limit, offset)
            call.respond(PlatformOperationalOwnerListResponse(owners.map { it.toDto() }))
        }

        get("/{userId}") {
            call.requirePlatformOwner(platformConfig)
            val userId = call.parameters["userId"]?.toLongOrNull()?.takeIf { it > 0L } ?: throw NotFoundException()
            val portfolio = platformVenueRepository.getOperationalOwner(userId) ?: throw NotFoundException()
            call.respond(
                PlatformOperationalOwnerResponse(
                    owner = portfolio.owner.toDto(),
                    venues = portfolio.venues.map { it.toDto() },
                ),
            )
        }
    }
}

private data class NormalizedPlatformOnboardingTerms(
    val trialConfigured: Boolean,
    val trialEndsOn: LocalDate?,
    val currentPriceRub: Long,
    val futurePriceRub: Long?,
    val futurePriceEffectiveOn: LocalDate?,
    val commercialNote: String?,
)

private fun PlatformOnboardingTermsRequest.normalized(): NormalizedPlatformOnboardingTerms {
    if (!trialConfigured) throw InvalidInputException("trialConfigured must be true")
    if (currentPriceRub < 0 || currentPriceRub > Int.MAX_VALUE / 100L) {
        throw InvalidInputException("currentPriceRub is out of range")
    }
    val normalizedTrialEndsOn = parseOptionalDate(trialEndsOn, "trialEndsOn")
    val normalizedFuturePriceEffectiveOn =
        parseOptionalDate(futurePriceEffectiveOn, "futurePriceEffectiveOn")
    if ((futurePriceRub == null) != (normalizedFuturePriceEffectiveOn == null)) {
        throw InvalidInputException("futurePriceRub and futurePriceEffectiveOn must be provided together")
    }
    if (futurePriceRub != null && (futurePriceRub <= 0 || futurePriceRub > Int.MAX_VALUE / 100L)) {
        throw InvalidInputException("futurePriceRub is out of range")
    }
    val note = commercialNote?.trim().orEmpty()
    if (note.length > 1000) throw InvalidInputException("commercialNote length must be <= 1000")
    return NormalizedPlatformOnboardingTerms(
        trialConfigured = true,
        trialEndsOn = normalizedTrialEndsOn,
        currentPriceRub = currentPriceRub,
        futurePriceRub = futurePriceRub,
        futurePriceEffectiveOn = normalizedFuturePriceEffectiveOn,
        commercialNote = note.ifBlank { null },
    )
}

private suspend fun ApplicationCall.respondDecision(
    result: VenueConnectionRequestMutationResult,
    venueOnboardingService: VenueOnboardingService,
) {
    when (result) {
        is VenueConnectionRequestMutationResult.Success -> {
            val request =
                venueOnboardingService.findPlatformApplication(result.request.id)
                    ?: throw NotFoundException()
            respond(PlatformOnboardingRequestResponse(request.toPlatformDto()))
        }
        is VenueConnectionRequestMutationResult.InvalidState ->
            throw InvalidInputException("Заявка уже обработана или действие недоступно в текущем статусе.")
        VenueConnectionRequestMutationResult.ApplicantNotOperationalOwner ->
            throw InvalidInputException("У заявителя больше нет активной роли Owner.")
        VenueConnectionRequestMutationResult.NotFound -> throw NotFoundException()
    }
}

private fun ApplicationCall.requireOnboardingRequestId(): Long =
    parameters["requestId"]?.toLongOrNull()?.takeIf { it > 0L } ?: throw NotFoundException()

private fun parseOnboardingStatuses(raw: String?): Set<String>? {
    if (raw.isNullOrBlank() || raw.equals("any", ignoreCase = true)) return null
    val supported =
        setOf(
            VenueConnectionRequestRepository.STATUS_PENDING,
            VenueConnectionRequestRepository.STATUS_APPROVED,
            VenueConnectionRequestRepository.STATUS_REJECTED,
            VenueConnectionRequestRepository.STATUS_CANCELLED,
        )
    val parsed = raw.split(',').map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
    if (parsed.isEmpty() || !supported.containsAll(parsed)) {
        throw InvalidInputException("status must contain only PENDING, APPROVED, REJECTED or CANCELLED")
    }
    return parsed
}

private fun parseVenueStatus(raw: String?): VenueStatus? {
    if (raw.isNullOrBlank() || raw.equals("any", ignoreCase = true)) return null
    return VenueStatus.fromDb(raw) ?: throw InvalidInputException("Unsupported venue status")
}

private fun parseOptionalDate(
    raw: String?,
    field: String,
): LocalDate? =
    raw?.trim()?.takeIf { it.isNotBlank() }?.let { value ->
        runCatching { LocalDate.parse(value) }.getOrElse { throw InvalidInputException("$field must be ISO date") }
    }

private fun VenueConnectionRequestApplicantRecord.toPlatformDto(): PlatformOnboardingRequestDto =
    request.toPlatformDto(
        PlatformOnboardingApplicantDto(
            userId = request.telegramUserId,
            username = username,
            firstName = firstName,
            lastName = lastName,
        ),
    )

private fun VenueConnectionRequestRecord.toPlatformDto(
    applicant: PlatformOnboardingApplicantDto,
): PlatformOnboardingRequestDto =
    PlatformOnboardingRequestDto(
        id = id,
        applicant = applicant,
        venueName = venueName,
        city = city,
        contact = contact,
        comment = comment,
        status = status,
        createdAt = createdAt.toString(),
        linkedVenueId = linkedVenueId,
        trialConfigured = trialConfigured,
        trialEndsOn = trialEndsOn?.toString(),
        currentPriceRub = currentPriceRub,
        futurePriceRub = futurePriceRub,
        futurePriceEffectiveOn = futurePriceEffectiveOn?.toString(),
        commercialNote = commercialNote,
    )

private fun PlatformOperationalOwnerSummary.toDto(): PlatformOperationalOwnerDto =
    PlatformOperationalOwnerDto(
        userId = userId,
        username = username,
        firstName = firstName,
        lastName = lastName,
        venueCount = venueCount,
        venueStatusCounts = venueStatusCounts,
    )

private fun PlatformOperationalOwnerVenue.toDto(): PlatformOperationalOwnerVenueDto =
    PlatformOperationalOwnerVenueDto(
        id = id,
        name = name,
        city = city,
        status = status.dbValue,
        createdAt = createdAt.toString(),
    )
