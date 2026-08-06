package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.StaffProfileLinkConflictException
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteAcceptResult
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteConfig
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteRepository
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteRevokeResult
import com.hookah.platform.backend.miniapp.venue.staff.StaffProfileMutationResult
import com.hookah.platform.backend.miniapp.venue.staff.StaffProfileWrite
import com.hookah.platform.backend.miniapp.venue.staff.StaffShiftWrite
import com.hookah.platform.backend.miniapp.venue.staff.StaffTodayShiftMutationResult
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffMember
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffModuleGuard
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffMutationSource
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffProfile
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffProfileAccess
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffProfileLinkState
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffProfileRepository
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffProfileWithTodayShift
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffRemoveResult
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffRepository
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffShift
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffUpdateResult
import com.hookah.platform.backend.miniapp.venue.staff.appendOwnerInviteAcceptAuditBestEffort
import com.hookah.platform.backend.platform.OwnerAccountAssignmentPreparationResult
import com.hookah.platform.backend.platform.VenueOwnerAccountRepository
import com.hookah.platform.backend.telegram.buildTelegramStartUrl
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

@Serializable
data class VenueStaffListResponse(
    val members: List<VenueStaffMemberDto>,
)

@Serializable
data class VenueStaffMemberDto(
    val userId: Long,
    val displayName: String,
    val username: String? = null,
    val role: String,
    val active: Boolean,
    val linkedStaffProfileId: Long? = null,
    val linkedStaffProfileDisplayName: String? = null,
    val profileLinkState: String,
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
data class StaffPendingInvitesResponse(
    val invites: List<StaffPendingInviteDto>,
)

@Serializable
data class StaffPendingInviteDto(
    val handle: String,
    val role: String,
    val status: String,
    val createdAt: String,
    val expiresAt: String,
)

@Serializable
data class StaffInviteRevokeResponse(
    val ok: Boolean,
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

@Serializable
data class VenueStaffProfilesResponse(
    val profiles: List<VenueStaffProfileDto>,
)

@Serializable
data class VenueStaffProfileDto(
    val id: Long,
    val linkedUserId: Long? = null,
    val linkageClass: String,
    val canManage: Boolean,
    val isSelf: Boolean,
    val displayName: String,
    val roleLabel: String? = null,
    val subtype: String,
    val photoRef: String? = null,
    val bio: String? = null,
    val tags: List<String> = emptyList(),
    val isGuestVisible: Boolean,
    val publishedAt: String? = null,
    val disabledAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val todayShift: VenueStaffShiftDto? = null,
)

@Serializable
data class VenueStaffShiftDto(
    val id: Long,
    val staffProfileId: Long,
    val shiftDate: String,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val status: String,
    val isGuestVisible: Boolean,
    val manuallyMarkedActive: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class VenueStaffProfileCreateRequest(
    val displayName: String,
    val roleLabel: String? = null,
    val subtype: String = STAFF_PROFILE_SUBTYPE_OTHER,
    val linkedUserId: Long? = null,
    val photoRef: String? = null,
    val bio: String? = null,
    val tags: List<String> = emptyList(),
    val isGuestVisible: Boolean = false,
)

@Serializable
data class VenueStaffProfileCreateFromMemberRequest(
    val userId: Long,
    val subtype: String,
    val roleLabel: String? = null,
)

@Serializable
data class VenueStaffProfileUpdateRequest(
    val displayName: String? = null,
    val roleLabel: String? = null,
    val subtype: String? = null,
    val linkedUserId: Long? = null,
    val unlinkUser: Boolean = false,
    val photoRef: String? = null,
    val bio: String? = null,
    val tags: List<String>? = null,
    val isGuestVisible: Boolean? = null,
)

@Serializable
data class VenueStaffShiftUpsertRequest(
    val status: String = STAFF_SHIFT_STATUS_ACTIVE,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val isGuestVisible: Boolean? = null,
)

@Serializable
data class VenueStaffShiftResponse(
    val shift: VenueStaffShiftDto,
)

@Serializable
data class VenueStaffTodayShiftsResponse(
    val shifts: List<VenueStaffShiftDto>,
)

fun Route.venueStaffRoutes(
    venueAccessRepository: VenueAccessRepository,
    venueStaffRepository: VenueStaffRepository,
    venueStaffProfileRepository: VenueStaffProfileRepository,
    staffInviteRepository: StaffInviteRepository,
    staffInviteConfig: StaffInviteConfig,
    venueSettingsRepository: VenueSettingsRepository,
    venueOwnerAccountRepository: VenueOwnerAccountRepository = VenueOwnerAccountRepository(null),
    auditLogRepository: AuditLogRepository = AuditLogRepository(null),
    staffModuleGuard: VenueStaffModuleGuard,
    telegramBotUsername: String? = null,
) {
    val logger = LoggerFactory.getLogger("VenueStaffRoutes")
    route("/venue") {
        get("/{venueId}/staff") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            requesterRole.requireStaffPermission(VenuePermission.STAFF_ACCESS_VIEW)
            val members =
                venueStaffRepository.listMembers(
                    venueId = venueId,
                    allowedRoles = setOf(VenueRole.STAFF).takeIf { requesterRole == VenueRole.MANAGER },
                )
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
            when (targetRole) {
                VenueRole.STAFF ->
                    requesterRole.requireStaffPermission(VenuePermission.STAFF_INVITE_CREATE_STAFF)
                VenueRole.MANAGER ->
                    requesterRole.requireStaffPermission(VenuePermission.STAFF_INVITE_CREATE_MANAGER)
                VenueRole.OWNER -> error("OWNER invite was rejected above")
            }
            val ttlSeconds = resolveInviteTtl(request.expiresIn, staffInviteConfig)
            val result =
                staffInviteRepository.createInvite(
                    venueId = venueId,
                    createdByUserId = userId,
                    role = targetRole.name,
                    ttlSeconds = ttlSeconds,
                    auditLogRepository = auditLogRepository,
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

        get("/{venueId}/staff/invites") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            requesterRole.requireStaffPermission(VenuePermission.STAFF_ACCESS_VIEW)
            val invites =
                staffInviteRepository.listPendingInvites(
                    venueId = venueId,
                    allowedRoles = requesterRole.pendingInviteRolesForRead(),
                ) ?: throw DatabaseUnavailableException()
            call.respond(
                StaffPendingInvitesResponse(
                    invites =
                        invites.map { invite ->
                            StaffPendingInviteDto(
                                handle = invite.handle,
                                role = invite.role,
                                status = STAFF_INVITE_PENDING_STATUS,
                                createdAt = invite.createdAt.toString(),
                                expiresAt = invite.expiresAt.toString(),
                            )
                        },
                ),
            )
        }

        post("/{venueId}/staff/invites/{handle}/revoke") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val handle =
                call.parameters["handle"]
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw InvalidInputException("invite handle is required")
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            val allowedRoles = requesterRole.pendingInviteRolesForRevoke()
            when (
                staffInviteRepository.revokePendingInvite(
                    venueId = venueId,
                    handle = handle,
                    actorUserId = userId,
                    allowedRoles = allowedRoles,
                    auditLogRepository = auditLogRepository,
                )
            ) {
                is StaffInviteRevokeResult.Success -> call.respond(StaffInviteRevokeResponse(ok = true))
                StaffInviteRevokeResult.InvalidOrExpired ->
                    throw InvalidInputException("Invite is not pending or cannot be revoked")
                StaffInviteRevokeResult.DatabaseError -> throw DatabaseUnavailableException()
            }
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
                    val member =
                        venueStaffRepository.findMember(result.member.venueId, result.member.userId)
                            ?: result.member
                    call.respond(
                        StaffInviteAcceptResponse(
                            venueId = result.member.venueId,
                            member = member.toDto(),
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

        get("/{venueId}/staff/profiles") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            staffModuleGuard.requireEnabledAfterAccessCheck(venueId)
            val today = resolveVenueToday(venueSettingsRepository, venueId)
            val profiles =
                venueStaffProfileRepository.listProfiles(
                    venueId = venueId,
                    today = today,
                    linkedUserId = if (requesterRole == VenueRole.STAFF) userId else null,
                    requesterUserId = userId,
                    requesterRole = requesterRole,
                )
            call.respond(VenueStaffProfilesResponse(profiles = profiles.map { it.toDto() }))
        }

        post("/{venueId}/staff/profiles") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            requesterRole.requireStaffPermission(VenuePermission.STAFF_PROFILE_MANAGE_STAFF)
            staffModuleGuard.requireEnabledAfterAccessCheck(venueId)
            val request = call.receive<VenueStaffProfileCreateRequest>()
            if (request.linkedUserId != null) {
                throw InvalidInputException("Linked profile creation requires the member flow")
            }
            val result =
                venueStaffProfileRepository.createProfile(
                    venueId = venueId,
                    actorUserId = userId,
                    actorRole = requesterRole,
                    input = request.toWrite(),
                    auditLogRepository = auditLogRepository,
                )
            call.respond(result.requireProfileMutationSuccess().toDto())
        }

        post("/{venueId}/staff/profiles/from-member") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            requesterRole.requireStaffPermission(VenuePermission.STAFF_PROFILE_MANAGE_STAFF)
            staffModuleGuard.requireEnabledAfterAccessCheck(venueId)
            val request = call.receive<VenueStaffProfileCreateFromMemberRequest>()
            val subtype = normalizeProfileSubtype(request.subtype)
            val roleLabel = normalizeCreateFromMemberRoleLabel(subtype, request.roleLabel)
            val result =
                venueStaffProfileRepository.createProfileFromMember(
                    venueId = venueId,
                    actorUserId = userId,
                    actorRole = requesterRole,
                    targetUserId = request.userId,
                    subtype = subtype,
                    roleLabel = roleLabel,
                    auditLogRepository = auditLogRepository,
                )
            call.respond(result.requireProfileMutationSuccess().toDto())
        }

        patch("/{venueId}/staff/profiles/{profileId}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val profileId = call.requireProfileId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            val request = call.receive<VenueStaffProfileUpdateRequest>()
            when (requesterRole) {
                VenueRole.OWNER -> requesterRole.requireStaffPermission(VenuePermission.STAFF_PROFILE_MANAGE_STAFF)
                VenueRole.MANAGER -> {
                    if (request.hasManagementFields()) {
                        requesterRole.requireStaffPermission(VenuePermission.STAFF_PROFILE_MANAGE_STAFF)
                    } else {
                        requesterRole.requireStaffPermission(VenuePermission.STAFF_PROFILE_EDIT_OWN)
                    }
                }
                VenueRole.STAFF -> {
                    requesterRole.requireStaffPermission(VenuePermission.STAFF_PROFILE_EDIT_OWN)
                    if (request.hasManagementFields()) throw ForbiddenException()
                }
            }
            staffModuleGuard.requireEnabledAfterAccessCheck(venueId)
            val result =
                venueStaffProfileRepository.updateProfile(
                    venueId = venueId,
                    profileId = profileId,
                    actorUserId = userId,
                    actorRole = requesterRole,
                    selfEditOnlyRequest = !request.hasManagementFields(),
                    auditLogRepository = auditLogRepository,
                    buildInput = { current ->
                        if (requesterRole == VenueRole.STAFF) {
                            request.toOwnDraftWrite(current)
                        } else {
                            val linkedUserId =
                                when {
                                    request.unlinkUser -> null
                                    request.linkedUserId != null -> request.linkedUserId
                                    else -> current.linkedUserId
                                }
                            request.toManagementWrite(current, linkedUserId)
                        }
                    },
                )
            call.respond(result.requireProfileMutationSuccess().toDto())
        }

        post("/{venueId}/staff/profiles/{profileId}/publish") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val profileId = call.requireProfileId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            requesterRole.requireStaffPermission(VenuePermission.STAFF_PROFILE_PUBLISH_STAFF)
            staffModuleGuard.requireEnabledAfterAccessCheck(venueId)
            val result =
                venueStaffProfileRepository.publishProfile(
                    venueId = venueId,
                    profileId = profileId,
                    actorUserId = userId,
                    actorRole = requesterRole,
                    auditLogRepository = auditLogRepository,
                )
            call.respond(result.requireProfileMutationSuccess().toDto())
        }

        post("/{venueId}/staff/profiles/{profileId}/hide") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val profileId = call.requireProfileId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            requesterRole.requireStaffPermission(VenuePermission.STAFF_PROFILE_PUBLISH_STAFF)
            staffModuleGuard.requireEnabledAfterAccessCheck(venueId)
            val result =
                venueStaffProfileRepository.hideProfile(
                    venueId = venueId,
                    profileId = profileId,
                    actorUserId = userId,
                    actorRole = requesterRole,
                    auditLogRepository = auditLogRepository,
                )
            call.respond(result.requireProfileMutationSuccess().toDto())
        }

        get("/{venueId}/staff/shifts/today") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            staffModuleGuard.requireEnabledAfterAccessCheck(venueId)
            val today = resolveVenueToday(venueSettingsRepository, venueId)
            val profiles =
                venueStaffProfileRepository.listProfiles(
                    venueId = venueId,
                    today = today,
                    linkedUserId = if (requesterRole == VenueRole.STAFF) userId else null,
                )
            call.respond(
                VenueStaffTodayShiftsResponse(
                    shifts = profiles.mapNotNull { it.todayShift?.toDto() },
                ),
            )
        }

        post("/{venueId}/staff/profiles/{profileId}/today-shift") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val profileId = call.requireProfileId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            if (requesterRole == VenueRole.STAFF) {
                throw ForbiddenException()
            }
            staffModuleGuard.requireManualTodaySourceAfterAccessCheck(venueId)
            val request = call.receive<VenueStaffShiftUpsertRequest>()
            val status = normalizeShiftStatus(request.status)
            if (requesterRole == VenueRole.MANAGER && status == STAFF_SHIFT_STATUS_SCHEDULED) {
                throw ForbiddenException()
            }
            val result =
                venueStaffProfileRepository.upsertTodayShift(
                    venueId = venueId,
                    staffProfileId = profileId,
                    shiftDate = resolveVenueToday(venueSettingsRepository, venueId),
                    actorUserId = userId,
                    actorRole = requesterRole,
                    input =
                        StaffShiftWrite(
                            startsAt = parseNullableLocalTime(request.startsAt, "startsAt"),
                            endsAt = parseNullableLocalTime(request.endsAt, "endsAt"),
                            status = status,
                            isGuestVisible = request.isGuestVisible ?: true,
                            manuallyMarkedActive = status == STAFF_SHIFT_STATUS_ACTIVE,
                        ),
                    auditLogRepository = auditLogRepository,
                )
            val shift = result.requireTodayShiftMutationSuccess()
            call.respond(VenueStaffShiftResponse(shift = shift.toDto()))
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
            when (
                val result =
                    venueStaffRepository.updateRoleWithOwnerGuard(
                        venueId = venueId,
                        actorUserId = requesterId,
                        targetUserId = targetUserId,
                        newRole = newRole,
                        source = VenueStaffMutationSource.VENUE_MINI_APP,
                    )
            ) {
                is VenueStaffUpdateResult.Success -> call.respond(result.member.toDto())
                VenueStaffUpdateResult.Forbidden -> throw ForbiddenException()
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
            when (
                venueStaffRepository.removeMemberWithOwnerGuard(
                    venueId = venueId,
                    actorUserId = requesterId,
                    targetUserId = targetUserId,
                    source = VenueStaffMutationSource.VENUE_MINI_APP,
                )
            ) {
                VenueStaffRemoveResult.Success -> call.respond(StaffRemoveResponse(ok = true))
                VenueStaffRemoveResult.Forbidden -> throw ForbiddenException()
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
        displayName = displayName,
        username = username,
        role = role,
        active = active,
        linkedStaffProfileId = linkedStaffProfileId,
        linkedStaffProfileDisplayName = linkedStaffProfileDisplayName,
        profileLinkState = profileLinkState.name,
    )

private fun VenueStaffProfileWithTodayShift.toDto(): VenueStaffProfileDto =
    profile.toDto(
        access = checkNotNull(access) { "Private staff profile projection is required" },
        todayShift = todayShift,
    )

private fun StaffProfileMutationResult.Success.toDto(): VenueStaffProfileDto = profile.toDto(access = access)

private fun VenueStaffProfile.toDto(
    access: VenueStaffProfileAccess,
    todayShift: VenueStaffShift? = null,
): VenueStaffProfileDto =
    VenueStaffProfileDto(
        id = id,
        linkedUserId = access.linkedUserId,
        linkageClass = access.linkageClass.name,
        canManage = access.canManage,
        isSelf = access.isSelf,
        displayName = displayName,
        roleLabel = roleLabel,
        subtype = subtype,
        photoRef = photoRef,
        bio = bio,
        tags = tags,
        isGuestVisible = isGuestVisible,
        publishedAt = publishedAt?.toString(),
        disabledAt = disabledAt?.toString(),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        todayShift = todayShift?.toDto(),
    )

private fun VenueStaffShift.toDto(): VenueStaffShiftDto =
    VenueStaffShiftDto(
        id = id,
        staffProfileId = staffProfileId,
        shiftDate = shiftDate.toString(),
        startsAt = startsAt?.toString(),
        endsAt = endsAt?.toString(),
        status = status,
        isGuestVisible = isGuestVisible,
        manuallyMarkedActive = manuallyMarkedActive,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

private fun VenueStaffProfileCreateRequest.toWrite(): StaffProfileWrite =
    StaffProfileWrite(
        linkedUserId = null,
        displayName = normalizeRequiredText(displayName, "displayName", STAFF_PROFILE_DISPLAY_NAME_MAX_LENGTH),
        roleLabel = normalizeNullableText(roleLabel, STAFF_PROFILE_SHORT_TEXT_MAX_LENGTH),
        subtype = normalizeProfileSubtype(subtype),
        photoRef = normalizeNullableText(photoRef, STAFF_PROFILE_PHOTO_REF_MAX_LENGTH),
        bio = normalizeNullableText(bio, STAFF_PROFILE_BIO_MAX_LENGTH),
        tags = normalizeProfileTags(tags),
        isGuestVisible = isGuestVisible,
    )

private fun VenueStaffProfileUpdateRequest.toManagementWrite(
    current: VenueStaffProfile,
    linkedUserId: Long?,
): StaffProfileWrite =
    StaffProfileWrite(
        linkedUserId = linkedUserId,
        displayName =
            displayName?.let { normalizeRequiredText(it, "displayName", STAFF_PROFILE_DISPLAY_NAME_MAX_LENGTH) }
                ?: current.displayName,
        roleLabel =
            roleLabel?.let { normalizeNullableText(it, STAFF_PROFILE_SHORT_TEXT_MAX_LENGTH) }
                ?: current.roleLabel,
        subtype = subtype?.let { normalizeProfileSubtype(it) } ?: current.subtype,
        photoRef = photoRef?.let { normalizeNullableText(it, STAFF_PROFILE_PHOTO_REF_MAX_LENGTH) } ?: current.photoRef,
        bio = bio?.let { normalizeNullableText(it, STAFF_PROFILE_BIO_MAX_LENGTH) } ?: current.bio,
        tags = tags?.let { normalizeProfileTags(it) } ?: current.tags,
        isGuestVisible = isGuestVisible ?: current.isGuestVisible,
    )

private fun VenueStaffProfileUpdateRequest.toOwnDraftWrite(current: VenueStaffProfile): StaffProfileWrite =
    StaffProfileWrite(
        linkedUserId = current.linkedUserId,
        displayName = current.displayName,
        roleLabel = current.roleLabel,
        subtype = current.subtype,
        photoRef = photoRef?.let { normalizeNullableText(it, STAFF_PROFILE_PHOTO_REF_MAX_LENGTH) } ?: current.photoRef,
        bio = bio?.let { normalizeNullableText(it, STAFF_PROFILE_BIO_MAX_LENGTH) } ?: current.bio,
        tags = tags?.let { normalizeProfileTags(it) } ?: current.tags,
        isGuestVisible = current.isGuestVisible,
    )

private fun VenueStaffProfileUpdateRequest.hasManagementFields(): Boolean =
    displayName != null ||
        roleLabel != null ||
        subtype != null ||
        linkedUserId != null ||
        unlinkUser ||
        isGuestVisible != null

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

private const val STAFF_INVITE_PENDING_STATUS = "PENDING"

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

private fun VenueRole.requireStaffPermission(permission: VenuePermission) {
    if (permission !in VenuePermissions.forRole(this)) {
        throw ForbiddenException()
    }
}

private fun VenueRole.pendingInviteRolesForRead(): Set<String> =
    buildSet {
        val permissions = VenuePermissions.forRole(this@pendingInviteRolesForRead)
        if (VenuePermission.STAFF_INVITE_REVOKE_STAFF in permissions) add(VenueRole.STAFF.name)
        if (VenuePermission.STAFF_INVITE_REVOKE_MANAGER in permissions) add(VenueRole.MANAGER.name)
    }

private fun VenueRole.pendingInviteRolesForRevoke(): Set<String> {
    val roles = pendingInviteRolesForRead()
    if (roles.isEmpty()) throw ForbiddenException()
    return roles
}

private fun StaffProfileMutationResult.requireProfileMutationSuccess(): StaffProfileMutationResult.Success =
    when (this) {
        is StaffProfileMutationResult.Success -> this
        StaffProfileMutationResult.NotFound -> throw NotFoundException()
        StaffProfileMutationResult.InvalidLink ->
            throw InvalidInputException("Selected staff member is unavailable")
        StaffProfileMutationResult.Forbidden -> throw ForbiddenException()
        is StaffProfileMutationResult.LinkConflict ->
            throw StaffProfileLinkConflictException(
                message =
                    when (profileLinkState) {
                        VenueStaffProfileLinkState.DUPLICATE_LINK_DETECTED ->
                            "К этому сотруднику привязано несколько карточек. Выберите основную и отвяжите остальные."
                        else -> "К этому сотруднику уже привязана активная карточка."
                    },
                details =
                    buildJsonObject {
                        put("profileLinkState", profileLinkState.name)
                        linkedStaffProfileId?.let { put("linkedStaffProfileId", it) }
                    },
            )
    }

private fun StaffTodayShiftMutationResult.requireTodayShiftMutationSuccess(): VenueStaffShift =
    when (this) {
        is StaffTodayShiftMutationResult.Success -> shift
        StaffTodayShiftMutationResult.NotFound -> throw NotFoundException()
        StaffTodayShiftMutationResult.Forbidden -> throw ForbiddenException()
    }

private suspend fun resolveVenueToday(
    venueSettingsRepository: VenueSettingsRepository,
    venueId: Long,
): LocalDate {
    val zoneId = venueSettingsRepository.resolveZoneId(venueId)
    return LocalDateTime.ofInstant(Instant.now(), zoneId).toLocalDate()
}

private fun io.ktor.server.application.ApplicationCall.requireProfileId(): Long =
    parameters["profileId"]?.toLongOrNull()
        ?: throw InvalidInputException("profileId must be a number")

private fun normalizeRequiredText(
    raw: String,
    fieldName: String,
    maxLength: Int,
): String {
    val value = raw.trim()
    if (value.isEmpty()) {
        throw InvalidInputException("$fieldName must not be blank")
    }
    if (value.length > maxLength) {
        throw InvalidInputException("$fieldName must be <= $maxLength characters")
    }
    return value
}

private fun normalizeNullableText(
    raw: String?,
    maxLength: Int,
): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.length > maxLength) {
        throw InvalidInputException("Text field must be <= $maxLength characters")
    }
    return value
}

private fun normalizeProfileSubtype(raw: String): String {
    val value = raw.trim().lowercase(Locale.ROOT)
    if (value !in STAFF_PROFILE_SUBTYPES) {
        throw InvalidInputException("subtype must be one of hookah_master, waiter, admin, other")
    }
    return value
}

private fun normalizeCreateFromMemberRoleLabel(
    subtype: String,
    rawRoleLabel: String?,
): String? {
    val roleLabel = normalizeNullableText(rawRoleLabel, STAFF_PROFILE_SHORT_TEXT_MAX_LENGTH)
    if (subtype == STAFF_PROFILE_SUBTYPE_OTHER) {
        return roleLabel ?: throw InvalidInputException("roleLabel is required for subtype other")
    }
    if (roleLabel != null) {
        throw InvalidInputException("roleLabel is allowed only for subtype other")
    }
    return null
}

private fun normalizeProfileTags(raw: List<String>): List<String> {
    if (raw.size > STAFF_PROFILE_TAGS_MAX_COUNT) {
        throw InvalidInputException("tags must contain <= $STAFF_PROFILE_TAGS_MAX_COUNT items")
    }
    return raw
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map {
            if (it.length > STAFF_PROFILE_TAG_MAX_LENGTH) {
                throw InvalidInputException("tag must be <= $STAFF_PROFILE_TAG_MAX_LENGTH characters")
            }
            it
        }
        .distinct()
}

private fun normalizeShiftStatus(raw: String): String {
    val value = raw.trim().lowercase(Locale.ROOT)
    if (value !in STAFF_SHIFT_STATUSES) {
        throw InvalidInputException("status must be one of scheduled, active, completed, canceled")
    }
    return value
}

private fun parseNullableLocalTime(
    raw: String?,
    fieldName: String,
): LocalTime? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { LocalTime.parse(value) }
        .getOrElse { throw InvalidInputException("$fieldName must be HH:mm or HH:mm:ss") }
}

private const val STAFF_PROFILE_SUBTYPE_OTHER = "other"
private const val STAFF_SHIFT_STATUS_SCHEDULED = "scheduled"
private const val STAFF_SHIFT_STATUS_ACTIVE = "active"
private const val STAFF_PROFILE_DISPLAY_NAME_MAX_LENGTH = 120
private const val STAFF_PROFILE_SHORT_TEXT_MAX_LENGTH = 120
private const val STAFF_PROFILE_PHOTO_REF_MAX_LENGTH = 512
private const val STAFF_PROFILE_BIO_MAX_LENGTH = 1000
private const val STAFF_PROFILE_TAGS_MAX_COUNT = 8
private const val STAFF_PROFILE_TAG_MAX_LENGTH = 40

private val STAFF_PROFILE_SUBTYPES = setOf("hookah_master", "waiter", "admin", STAFF_PROFILE_SUBTYPE_OTHER)
private val STAFF_SHIFT_STATUSES =
    setOf(
        STAFF_SHIFT_STATUS_SCHEDULED,
        STAFF_SHIFT_STATUS_ACTIVE,
        "completed",
        "canceled",
    )
