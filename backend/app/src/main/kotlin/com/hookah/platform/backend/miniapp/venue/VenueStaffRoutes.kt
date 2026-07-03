package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteAcceptResult
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteConfig
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteRepository
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffMember
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffRemoveResult
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffRepository
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffUpdateResult
import com.hookah.platform.backend.miniapp.venue.staff.appendOwnerInviteAcceptAuditBestEffort
import com.hookah.platform.backend.platform.OwnerAccountAssignmentPreparationResult
import com.hookah.platform.backend.platform.VenueOwnerAccountRepository
import com.hookah.platform.backend.telegram.buildTelegramStartUrl
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class VenueStaffListResponse(
    val members: List<VenueStaffMemberDto>,
)

@Serializable
data class VenueStaffMemberDto(
    val userId: Long,
    val role: String,
    val createdAt: String,
    val invitedByUserId: Long? = null,
)

@Serializable
data class StaffInviteRequest(
    val role: String,
    val expiresIn: Long? = null,
)

@Serializable
data class StaffInviteResponse(
    val inviteCode: String,
    val expiresAt: String,
    val ttlSeconds: Long,
    val instructions: String,
    val role: String,
    val venueName: String,
    val startPayload: String,
    val deepLink: String? = null,
    val fallbackCommand: String,
    val copyText: String,
)

@Serializable
data class StaffInviteAcceptRequest(
    val inviteCode: String,
)

@Serializable
data class StaffInviteAcceptResponse(
    val venueId: Long,
    val member: VenueStaffMemberDto,
    val alreadyMember: Boolean,
)

@Serializable
data class StaffUpdateRoleRequest(
    val role: String,
)

@Serializable
data class StaffRemoveResponse(
    val ok: Boolean,
)

fun Route.venueStaffRoutes(
    venueAccessRepository: VenueAccessRepository,
    venueStaffRepository: VenueStaffRepository,
    staffInviteRepository: StaffInviteRepository,
    staffInviteConfig: StaffInviteConfig,
    venueOwnerAccountRepository: VenueOwnerAccountRepository = VenueOwnerAccountRepository(null),
    auditLogRepository: AuditLogRepository = AuditLogRepository(null),
    telegramBotUsername: String? = null,
) {
    val logger = LoggerFactory.getLogger("VenueStaffRoutes")
    route("/venue") {
        get("/{venueId}/staff") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            if (requesterRole == VenueRole.STAFF) {
                throw ForbiddenException()
            }
            val members = venueStaffRepository.listMembers(venueId)
            call.respond(
                VenueStaffListResponse(
                    members = members.map { it.toDto() },
                ),
            )
        }

        post("/{venueId}/staff/invites") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val requesterMembership =
                venueAccessRepository.findVenueMembership(userId, venueId)
                    ?: throw ForbiddenException()
            val requesterRole =
                VenueRoleMapping.fromDb(requesterMembership.role)
                    ?: throw ForbiddenException()
            val request = call.receive<StaffInviteRequest>()
            val targetRole = parseVenueRole(request.role)
            if (targetRole == VenueRole.OWNER) {
                throw InvalidInputException("OWNER cannot be assigned from venue staff invite flow")
            }
            if (requesterRole == VenueRole.STAFF) {
                throw ForbiddenException()
            }
            if (requesterRole == VenueRole.MANAGER && targetRole != VenueRole.STAFF) {
                throw ForbiddenException()
            }
            val ttlSeconds = resolveInviteTtl(request.expiresIn, staffInviteConfig)
            val result =
                staffInviteRepository.createInvite(
                    venueId = venueId,
                    createdByUserId = userId,
                    role = targetRole.name,
                    ttlSeconds = ttlSeconds,
                ) ?: throw DatabaseUnavailableException()
            val startPayload = buildStaffInviteStartPayload(result.code)
            val deepLink =
                telegramBotUsername
                    ?.trim()
                    ?.removePrefix("@")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { buildTelegramStartUrl(it, startPayload) }
            val fallbackCommand = "/start $startPayload"
            val copyText = deepLink ?: fallbackCommand
            val venueName =
                requesterMembership.venueName
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Заведение #$venueId"
            call.respond(
                StaffInviteResponse(
                    inviteCode = result.code,
                    expiresAt = result.expiresAt.toString(),
                    ttlSeconds = result.ttlSeconds,
                    instructions =
                        buildStaffInviteInstructions(
                            role = targetRole.name,
                            venueName = venueName,
                            deepLink = deepLink,
                            fallbackCommand = fallbackCommand,
                        ),
                    role = targetRole.name,
                    venueName = venueName,
                    startPayload = startPayload,
                    deepLink = deepLink,
                    fallbackCommand = fallbackCommand,
                    copyText = copyText,
                ),
            )
        }

        post("/staff/invites/accept") {
            val userId = call.requireUserId()
            val request = call.receive<StaffInviteAcceptRequest>()
            val result =
                staffInviteRepository.acceptInvite(
                    code = request.inviteCode,
                    userId = userId,
                    createMember = createMember@{ connection, venueId, role, invitedByUserId ->
                        if (role.equals(VenueRole.OWNER.name, ignoreCase = true)) {
                            when (
                                venueOwnerAccountRepository.prepareOwnerAssignmentInTransaction(
                                    connection = connection,
                                    venueId = venueId,
                                    ownerUserId = userId,
                                    defaultLimit = 1,
                                    updatedByUserId = invitedByUserId,
                                )
                            ) {
                                is OwnerAccountAssignmentPreparationResult.Success -> Unit
                                else -> return@createMember null
                            }
                        }
                        venueStaffRepository.createMemberInTransaction(
                            connection,
                            venueId,
                            userId,
                            role,
                            invitedByUserId,
                        )
                    },
                )
            when (result) {
                is StaffInviteAcceptResult.Success -> {
                    appendOwnerInviteAcceptAuditBestEffort(auditLogRepository, result, logger)
                    call.respond(
                        StaffInviteAcceptResponse(
                            venueId = result.member.venueId,
                            member = result.member.toDto(),
                            alreadyMember = result.alreadyMember,
                        ),
                    )
                }
                StaffInviteAcceptResult.InvalidOrExpired -> throw InvalidInputException(
                    message = "Invite code is invalid or expired",
                )
                StaffInviteAcceptResult.DatabaseError -> throw DatabaseUnavailableException()
            }
        }

        patch("/{venueId}/staff/{userId}") {
            val requesterId = call.requireUserId()
            val venueId = call.requireVenueId()
            val requesterRole = resolveVenueRole(venueAccessRepository, requesterId, venueId)
            if (requesterRole != VenueRole.OWNER) {
                throw ForbiddenException()
            }
            val targetUserId =
                call.parameters["userId"]?.toLongOrNull()
                    ?: throw InvalidInputException("userId must be a number")
            val request = call.receive<StaffUpdateRoleRequest>()
            val newRole = parseVenueRole(request.role)
            when (val result = venueStaffRepository.updateRoleWithOwnerGuard(venueId, targetUserId, newRole.name)) {
                is VenueStaffUpdateResult.Success -> call.respond(result.member.toDto())
                VenueStaffUpdateResult.NotFound -> throw NotFoundException()
                VenueStaffUpdateResult.LastOwner -> throw InvalidInputException("Cannot remove the last owner")
                VenueStaffUpdateResult.DatabaseError -> throw DatabaseUnavailableException()
            }
        }

        delete("/{venueId}/staff/{userId}") {
            val requesterId = call.requireUserId()
            val venueId = call.requireVenueId()
            val requesterRole = resolveVenueRole(venueAccessRepository, requesterId, venueId)
            if (requesterRole != VenueRole.OWNER) {
                throw ForbiddenException()
            }
            val targetUserId =
                call.parameters["userId"]?.toLongOrNull()
                    ?: throw InvalidInputException("userId must be a number")
            when (venueStaffRepository.removeMemberWithOwnerGuard(venueId, targetUserId)) {
                VenueStaffRemoveResult.Success -> call.respond(StaffRemoveResponse(ok = true))
                VenueStaffRemoveResult.NotFound -> throw NotFoundException()
                VenueStaffRemoveResult.LastOwner -> throw InvalidInputException("Cannot remove the last owner")
                VenueStaffRemoveResult.DatabaseError -> throw DatabaseUnavailableException()
            }
        }
    }
}

private fun VenueStaffMember.toDto(): VenueStaffMemberDto =
    VenueStaffMemberDto(
        userId = userId,
        role = role,
        createdAt = createdAt.toString(),
        invitedByUserId = invitedByUserId,
    )

private fun parseVenueRole(rawRole: String): VenueRole {
    if (rawRole.trim().equals("ADMIN", ignoreCase = true)) {
        throw InvalidInputException("ADMIN is a legacy alias and cannot be assigned")
    }
    val role = VenueRoleMapping.fromDb(rawRole)
    return role ?: throw InvalidInputException("role must be one of OWNER, MANAGER, STAFF")
}

private fun resolveInviteTtl(
    requestedTtl: Long?,
    config: StaffInviteConfig,
): Long {
    val ttl = requestedTtl ?: config.defaultTtlSeconds
    if (ttl < 60) {
        throw InvalidInputException("expiresIn must be >= 60 seconds")
    }
    if (ttl > config.maxTtlSeconds) {
        throw InvalidInputException("expiresIn must be <= ${config.maxTtlSeconds} seconds")
    }
    return ttl
}

private fun buildStaffInviteStartPayload(code: String): String = "staff_invite_$code"

private fun buildStaffInviteInstructions(
    role: String,
    venueName: String,
    deepLink: String?,
    fallbackCommand: String,
): String =
    buildString {
        append("Передайте сотруднику приглашение.")
        append("\nЗаведение: $venueName")
        append("\nРоль: $role")
        if (deepLink != null) {
            append("\nСсылка: $deepLink")
        } else {
            append("\nСсылка недоступна: не задан TELEGRAM_BOT_USERNAME.")
        }
        append("\nЗапасная команда: $fallbackCommand")
    }
