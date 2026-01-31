package com.hookah.platform.backend.platform

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteConfig
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class PlatformVenueListResponse(
    val venues: List<PlatformVenueSummaryDto>
)

@Serializable
data class PlatformVenueSummaryDto(
    val id: Long,
    val name: String,
    val status: String,
    val createdAt: String,
    val ownersCount: Int,
    val subscriptionSummary: PlatformSubscriptionSummaryDto?
)

@Serializable
data class PlatformVenueCreateRequest(
    val name: String,
    val city: String? = null,
    val address: String? = null
)

@Serializable
data class PlatformVenueResponse(
    val venue: PlatformVenueDetailDto
)

@Serializable
data class PlatformVenueDetailResponse(
    val venue: PlatformVenueDetailDto,
    val owners: List<PlatformVenueOwnerDto>,
    val subscriptionSummary: PlatformSubscriptionSummaryDto?
)

@Serializable
data class PlatformVenueDetailDto(
    val id: Long,
    val name: String,
    val city: String? = null,
    val address: String? = null,
    val status: String,
    val createdAt: String,
    val deletedAt: String? = null
)

@Serializable
data class PlatformVenueOwnerDto(
    val userId: Long,
    val role: String,
    val username: String? = null,
    val firstName: String? = null,
    val lastName: String? = null
)

@Serializable
data class PlatformSubscriptionSummaryDto(
    val trialEndDate: String? = null,
    val paidStartDate: String? = null,
    val isPaid: Boolean? = null
)

@Serializable
data class PlatformVenueStatusChangeRequest(
    val action: String
)

@Serializable
data class PlatformOwnerAssignRequest(
    val userId: Long,
    val role: String? = null
)

@Serializable
data class PlatformOwnerAssignResponse(
    val ok: Boolean,
    val alreadyMember: Boolean,
    val role: String
)

@Serializable
data class PlatformOwnerInviteRequest(
    val ttlSeconds: Long? = null
)

@Serializable
data class PlatformOwnerInviteResponse(
    val code: String,
    val expiresAt: String,
    val instructions: String,
    val deepLink: String?
)

fun Route.platformVenueRoutes(
    platformConfig: PlatformConfig,
    venueRepository: PlatformVenueRepository,
    auditLogRepository: AuditLogRepository,
    platformVenueMemberRepository: PlatformVenueMemberRepository,
    staffInviteRepository: StaffInviteRepository,
    staffInviteConfig: StaffInviteConfig
) {
    route("/platform/venues") {
        get {
            call.requirePlatformOwner(platformConfig)
            val statusRaw = call.request.queryParameters["status"]
            val subscriptionRaw = call.request.queryParameters["subscription"]
            val query = call.request.queryParameters["q"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

            val status = when (statusRaw?.trim()?.lowercase()) {
                null, "", "any" -> null
                else -> VenueStatus.fromDb(statusRaw)
                    ?: throw InvalidInputException("status must be one of: DRAFT, PUBLISHED, HIDDEN, PAUSED, SUSPENDED, ARCHIVED, DELETED, any")
            }
            val subscriptionFilter = when (subscriptionRaw?.trim()?.lowercase()) {
                null, "", "any" -> null
                "trial_active" -> SubscriptionFilter.TRIAL_ACTIVE
                "paid" -> SubscriptionFilter.PAID
                "none" -> SubscriptionFilter.NONE
                else -> throw InvalidInputException("subscription must be one of: trial_active, paid, none, any")
            }

            val venues = venueRepository.listVenues(
                PlatformVenueFilter(
                    status = status,
                    subscriptionFilter = subscriptionFilter,
                    query = query,
                    limit = limit,
                    offset = offset
                )
            )
            call.respond(
                PlatformVenueListResponse(
                    venues = venues.map { it.toSummaryDto() }
                )
            )
        }

        post {
            val actorUserId = call.requirePlatformOwner(platformConfig)
            val request = call.receive<PlatformVenueCreateRequest>()
            val name = request.name.trim()
            if (name.isEmpty()) {
                throw InvalidInputException("name is required")
            }
            val created = venueRepository.createVenue(
                name = name,
                city = request.city?.trim().orEmpty().ifBlank { null },
                address = request.address?.trim().orEmpty().ifBlank { null }
            )
            auditLogRepository.appendJson(
                actorUserId = actorUserId,
                action = "VENUE_CREATE",
                entityType = "venue",
                entityId = created.id,
                payload = buildJsonObject {
                    put("venueId", created.id)
                    put("status", created.status.dbValue)
                }
            )
            call.respond(PlatformVenueResponse(venue = created.toDetailDto()))
        }

        get("/{venueId}") {
            call.requirePlatformOwner(platformConfig)
            val venueId = call.parameters["venueId"]?.toLongOrNull()
                ?: throw InvalidInputException("venueId must be a number")
            val venue = venueRepository.getVenueDetail(venueId) ?: throw NotFoundException()
            val owners = venueRepository.listOwners(venueId)
            val subscriptionSummary = venueRepository.getSubscriptionSummary(venueId)
            call.respond(
                PlatformVenueDetailResponse(
                    venue = venue.toDetailDto(),
                    owners = owners.map { it.toOwnerDto() },
                    subscriptionSummary = subscriptionSummary?.toDto()
                )
            )
        }

        post("/{venueId}/status") {
            val actorUserId = call.requirePlatformOwner(platformConfig)
            val venueId = call.parameters["venueId"]?.toLongOrNull()
                ?: throw InvalidInputException("venueId must be a number")
            val request = call.receive<PlatformVenueStatusChangeRequest>()
            val action = VenueStatusAction.fromWire(request.action)
                ?: throw InvalidInputException("action must be one of: publish, hide, pause, suspend, archive, delete")

            when (val result = venueRepository.updateStatus(venueId, action)) {
                is VenueStatusChangeResult.Success -> {
                    auditLogRepository.appendJson(
                        actorUserId = actorUserId,
                        action = "VENUE_STATUS_CHANGE",
                        entityType = "venue",
                        entityId = venueId,
                        payload = buildJsonObject {
                            put("action", action.wire)
                            put("fromStatus", result.fromStatus.dbValue)
                            put("toStatus", result.toStatus.dbValue)
                            put("at", Instant.now().toString())
                        }
                    )
                    call.respond(PlatformVenueResponse(venue = result.venue.toDetailDto()))
                }
                VenueStatusChangeResult.NotFound -> throw NotFoundException()
                VenueStatusChangeResult.InvalidTransition -> throw InvalidInputException("Invalid status transition")
                VenueStatusChangeResult.MissingOwner -> throw InvalidInputException("Venue must have at least one owner or admin before publishing")
                VenueStatusChangeResult.AlreadyDeleted -> throw InvalidInputException("Venue already deleted")
                VenueStatusChangeResult.DatabaseError -> throw com.hookah.platform.backend.api.DatabaseUnavailableException()
            }
        }

        post("/{venueId}/owners") {
            val actorUserId = call.requirePlatformOwner(platformConfig)
            val venueId = call.parameters["venueId"]?.toLongOrNull()
                ?: throw InvalidInputException("venueId must be a number")
            val request = call.receive<PlatformOwnerAssignRequest>()
            val role = parseOwnerRole(request.role)
            when (val result = platformVenueMemberRepository.assignOwner(venueId, request.userId, role, actorUserId)) {
                is PlatformOwnerAssignmentResult.Success -> {
                    auditLogRepository.appendJson(
                        actorUserId = actorUserId,
                        action = "VENUE_OWNER_ASSIGN",
                        entityType = "venue",
                        entityId = venueId,
                        payload = buildJsonObject {
                            put("venueId", venueId)
                            put("userId", request.userId)
                            put("role", role)
                        }
                    )
                    call.respond(
                        PlatformOwnerAssignResponse(
                            ok = true,
                            alreadyMember = result.alreadyMember,
                            role = result.member.role
                        )
                    )
                }
                PlatformOwnerAssignmentResult.NotFound -> throw NotFoundException()
                PlatformOwnerAssignmentResult.DatabaseError -> throw com.hookah.platform.backend.api.DatabaseUnavailableException()
            }
        }

        post("/{venueId}/owner-invite") {
            val actorUserId = call.requirePlatformOwner(platformConfig)
            val venueId = call.parameters["venueId"]?.toLongOrNull()
                ?: throw InvalidInputException("venueId must be a number")
            val request = call.receive<PlatformOwnerInviteRequest>()
            val ttlSeconds = resolveInviteTtl(request.ttlSeconds, staffInviteConfig)
            val result = staffInviteRepository.createInvite(
                venueId = venueId,
                createdByUserId = actorUserId,
                role = "OWNER",
                ttlSeconds = ttlSeconds
            ) ?: throw com.hookah.platform.backend.api.DatabaseUnavailableException()
            call.respond(
                PlatformOwnerInviteResponse(
                    code = result.code,
                    expiresAt = result.expiresAt.toString(),
                    instructions = "Передайте код владельцу. Он должен открыть мини‑приложение и принять инвайт.",
                    deepLink = null
                )
            )
        }
    }
}

private fun PlatformVenueSummary.toSummaryDto(): PlatformVenueSummaryDto = PlatformVenueSummaryDto(
    id = id,
    name = name,
    status = status.dbValue,
    createdAt = createdAt.toString(),
    ownersCount = ownersCount,
    subscriptionSummary = subscriptionSummary.toDto()
)

private fun PlatformVenueDetail.toDetailDto(): PlatformVenueDetailDto = PlatformVenueDetailDto(
    id = id,
    name = name,
    city = city,
    address = address,
    status = status.dbValue,
    createdAt = createdAt.toString(),
    deletedAt = deletedAt?.toString()
)

private fun PlatformVenueOwner.toOwnerDto(): PlatformVenueOwnerDto = PlatformVenueOwnerDto(
    userId = userId,
    role = role,
    username = username,
    firstName = firstName,
    lastName = lastName
)

private fun PlatformSubscriptionSummary.toDto(): PlatformSubscriptionSummaryDto = PlatformSubscriptionSummaryDto(
    trialEndDate = trialEndDate?.toString(),
    paidStartDate = paidStartDate?.toString(),
    isPaid = isPaid
)

private fun parseOwnerRole(rawRole: String?): String {
    val role = rawRole?.trim()?.takeIf { it.isNotEmpty() } ?: "OWNER"
    return when (role.uppercase()) {
        "OWNER" -> "OWNER"
        "ADMIN" -> "ADMIN"
        else -> throw InvalidInputException("role must be OWNER or ADMIN")
    }
}

private fun resolveInviteTtl(requestedTtl: Long?, config: StaffInviteConfig): Long {
    val ttl = requestedTtl ?: config.defaultTtlSeconds
    if (ttl < 60) {
        throw InvalidInputException("ttlSeconds must be >= 60 seconds")
    }
    if (ttl > config.maxTtlSeconds) {
        throw InvalidInputException("ttlSeconds must be <= ${config.maxTtlSeconds} seconds")
    }
    return ttl
}
