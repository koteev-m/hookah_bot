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

@Serializable
data class VenueStaffListResponse(
    val members: List<VenueStaffMemberDto>
)

@Serializable
data class VenueStaffMemberDto(
    val userId: Long,
    val role: String,
    val createdAt: String,
    val invitedByUserId: Long? = null
)

@Serializable
data class StaffInviteRequest(
    val role: String,
    val expiresIn: Long? = null
)

@Serializable
data class StaffInviteResponse(
    val inviteCode: String,
    val expiresAt: String,
    val ttlSeconds: Long,
    val instructions: String
)

@Serializable
data class StaffInviteAcceptRequest(
    val inviteCode: String
)

@Serializable
data class StaffInviteAcceptResponse(
    val venueId: Long,
    val member: VenueStaffMemberDto,
    val alreadyMember: Boolean
)

@Serializable
data class StaffUpdateRoleRequest(
    val role: String
)

@Serializable
data class StaffRemoveResponse(
    val ok: Boolean
)

fun Route.venueStaffRoutes(
    venueAccessRepository: VenueAccessRepository,
    venueStaffRepository: VenueStaffRepository,
    staffInviteRepository: StaffInviteRepository,
    staffInviteConfig: StaffInviteConfig
) {
    route("/venue") {
        get("/{venueId}/staff") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            resolveVenueRole(venueAccessRepository, userId, venueId)
            val members = venueStaffRepository.listMembers(venueId)
            call.respond(
                VenueStaffListResponse(
                    members = members.map { it.toDto() }
                )
            )
        }

        post("/{venueId}/staff/invites") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            val request = call.receive<StaffInviteRequest>()
            val targetRole = parseVenueRole(request.role)
            if (requesterRole == VenueRole.STAFF) {
                throw ForbiddenException()
            }
            if (requesterRole == VenueRole.MANAGER && targetRole != VenueRole.STAFF) {
                throw ForbiddenException()
            }
            val ttlSeconds = resolveInviteTtl(request.expiresIn, staffInviteConfig)
            val result = staffInviteRepository.createInvite(
                venueId = venueId,
                createdByUserId = userId,
                role = targetRole.name,
                ttlSeconds = ttlSeconds
            ) ?: throw DatabaseUnavailableException()
            call.respond(
                StaffInviteResponse(
                    inviteCode = result.code,
                    expiresAt = result.expiresAt.toString(),
                    ttlSeconds = result.ttlSeconds,
                    instructions = "Передайте код сотруднику. Он должен открыть мини‑приложение и принять инвайт."
                )
            )
        }

        post("/staff/invites/accept") {
            val userId = call.requireUserId()
            val request = call.receive<StaffInviteAcceptRequest>()
            val result = staffInviteRepository.acceptInvite(
                code = request.inviteCode,
                userId = userId,
                createMember = { connection, venueId, role, invitedByUserId ->
                    venueStaffRepository.createMemberInTransaction(connection, venueId, userId, role, invitedByUserId)
                }
            )
            when (result) {
                is StaffInviteAcceptResult.Success -> {
                    call.respond(
                        StaffInviteAcceptResponse(
                            venueId = result.member.venueId,
                            member = result.member.toDto(),
                            alreadyMember = result.alreadyMember
                        )
                    )
                }
                StaffInviteAcceptResult.InvalidOrExpired -> throw InvalidInputException(
                    message = "Invite code is invalid or expired"
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
            val targetUserId = call.parameters["userId"]?.toLongOrNull()
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
            val targetUserId = call.parameters["userId"]?.toLongOrNull()
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

private fun VenueStaffMember.toDto(): VenueStaffMemberDto = VenueStaffMemberDto(
    userId = userId,
    role = role,
    createdAt = createdAt.toString(),
    invitedByUserId = invitedByUserId
)

private fun parseVenueRole(rawRole: String): VenueRole {
    val role = VenueRoleMapping.fromDb(rawRole)
    return role ?: throw InvalidInputException("role must be one of OWNER, MANAGER, STAFF")
}

private fun resolveInviteTtl(requestedTtl: Long?, config: StaffInviteConfig): Long {
    val ttl = requestedTtl ?: config.defaultTtlSeconds
    if (ttl < 60) {
        throw InvalidInputException("expiresIn must be >= 60 seconds")
    }
    if (ttl > config.maxTtlSeconds) {
        throw InvalidInputException("expiresIn must be <= ${config.maxTtlSeconds} seconds")
    }
    return ttl
}
