package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteAcceptResult
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteConfig
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteRepository
import com.hookah.platform.backend.miniapp.venue.staff.StaffProfileWrite
import com.hookah.platform.backend.miniapp.venue.staff.StaffShiftWrite
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffMember
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffProfile
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

@Serializable
data class VenueStaffProfilesResponse(
    val profiles: List<VenueStaffProfileDto>,
)

@Serializable
data class VenueStaffProfileDto(
    val id: Long,
    val linkedUserId: Long? = null,
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

        get("/{venueId}/staff/profiles") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            val today = resolveVenueToday(venueSettingsRepository, venueId)
            val profiles =
                venueStaffProfileRepository.listProfiles(
                    venueId = venueId,
                    today = today,
                    linkedUserId = if (requesterRole == VenueRole.STAFF) userId else null,
                )
            call.respond(VenueStaffProfilesResponse(profiles = profiles.map { it.toDto() }))
        }

        post("/{venueId}/staff/profiles") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            if (requesterRole != VenueRole.OWNER) {
                throw ForbiddenException()
            }
            val request = call.receive<VenueStaffProfileCreateRequest>()
            requireLinkedUserInVenue(venueStaffRepository, venueId, request.linkedUserId)
            val profile =
                venueStaffProfileRepository.createProfile(
                    venueId = venueId,
                    actorUserId = userId,
                    input = request.toWrite(),
                )
            appendStaffProfileAuditBestEffort(
                auditLogRepository = auditLogRepository,
                actorUserId = userId,
                action = "staff_profile_created",
                venueId = venueId,
                profileId = profile.id,
            )
            call.respond(profile.toDto())
        }

        patch("/{venueId}/staff/profiles/{profileId}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val profileId = call.requireProfileId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            val current = venueStaffProfileRepository.findProfile(venueId, profileId) ?: throw NotFoundException()
            val request = call.receive<VenueStaffProfileUpdateRequest>()
            val write =
                when (requesterRole) {
                    VenueRole.OWNER -> {
                        val linkedUserId =
                            when {
                                request.unlinkUser -> null
                                request.linkedUserId != null -> request.linkedUserId
                                else -> current.linkedUserId
                            }
                        requireLinkedUserInVenue(venueStaffRepository, venueId, linkedUserId)
                        request.toOwnerWrite(current, linkedUserId)
                    }
                    VenueRole.STAFF -> {
                        if (current.linkedUserId != userId || request.hasOwnerOnlyFields()) {
                            throw ForbiddenException()
                        }
                        request.toOwnDraftWrite(current)
                    }
                    VenueRole.MANAGER -> throw ForbiddenException()
                }
            val nextVisibility = write.isGuestVisible
            val now = Instant.now()
            val publishedAt =
                if (nextVisibility) {
                    current.publishedAt ?: now
                } else {
                    current.publishedAt
                }
            val disabledAt =
                if (nextVisibility) {
                    null
                } else if (current.isGuestVisible) {
                    now
                } else {
                    current.disabledAt
                }
            val updated =
                venueStaffProfileRepository.updateProfile(
                    venueId = venueId,
                    profileId = profileId,
                    actorUserId = userId,
                    input = write,
                    publishedAt = publishedAt,
                    disabledAt = disabledAt,
                ) ?: throw NotFoundException()
            appendStaffProfileAuditBestEffort(
                auditLogRepository = auditLogRepository,
                actorUserId = userId,
                action =
                    if (requesterRole == VenueRole.STAFF) {
                        "staff_profile_own_draft_updated"
                    } else {
                        "staff_profile_updated"
                    },
                venueId = venueId,
                profileId = updated.id,
            )
            call.respond(updated.toDto())
        }

        post("/{venueId}/staff/profiles/{profileId}/publish") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val profileId = call.requireProfileId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            if (requesterRole != VenueRole.OWNER) {
                throw ForbiddenException()
            }
            val profile =
                venueStaffProfileRepository.publishProfile(
                    venueId = venueId,
                    profileId = profileId,
                    actorUserId = userId,
                ) ?: throw NotFoundException()
            appendStaffProfileAuditBestEffort(
                auditLogRepository = auditLogRepository,
                actorUserId = userId,
                action = "staff_profile_published",
                venueId = venueId,
                profileId = profile.id,
            )
            call.respond(profile.toDto())
        }

        post("/{venueId}/staff/profiles/{profileId}/hide") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val profileId = call.requireProfileId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            if (requesterRole != VenueRole.OWNER) {
                throw ForbiddenException()
            }
            val profile =
                venueStaffProfileRepository.hideProfile(
                    venueId = venueId,
                    profileId = profileId,
                    actorUserId = userId,
                ) ?: throw NotFoundException()
            appendStaffProfileAuditBestEffort(
                auditLogRepository = auditLogRepository,
                actorUserId = userId,
                action = "staff_profile_hidden",
                venueId = venueId,
                profileId = profile.id,
            )
            call.respond(profile.toDto())
        }

        get("/{venueId}/staff/shifts/today") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val requesterRole = resolveVenueRole(venueAccessRepository, userId, venueId)
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
            if (venueStaffProfileRepository.findProfile(venueId, profileId) == null) {
                throw NotFoundException()
            }
            val request = call.receive<VenueStaffShiftUpsertRequest>()
            val status = normalizeShiftStatus(request.status)
            if (requesterRole == VenueRole.MANAGER && status == STAFF_SHIFT_STATUS_SCHEDULED) {
                throw ForbiddenException()
            }
            val shift =
                venueStaffProfileRepository.upsertTodayShift(
                    venueId = venueId,
                    staffProfileId = profileId,
                    shiftDate = resolveVenueToday(venueSettingsRepository, venueId),
                    actorUserId = userId,
                    input =
                        StaffShiftWrite(
                            startsAt = parseNullableLocalTime(request.startsAt, "startsAt"),
                            endsAt = parseNullableLocalTime(request.endsAt, "endsAt"),
                            status = status,
                            isGuestVisible = request.isGuestVisible ?: true,
                            manuallyMarkedActive = status == STAFF_SHIFT_STATUS_ACTIVE,
                        ),
                )
            appendStaffShiftAuditBestEffort(
                auditLogRepository = auditLogRepository,
                actorUserId = userId,
                venueId = venueId,
                profileId = profileId,
                shiftId = shift.id,
                status = shift.status,
            )
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

private fun VenueStaffProfileWithTodayShift.toDto(): VenueStaffProfileDto = profile.toDto(todayShift = todayShift)

private fun VenueStaffProfile.toDto(todayShift: VenueStaffShift? = null): VenueStaffProfileDto =
    VenueStaffProfileDto(
        id = id,
        linkedUserId = linkedUserId,
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
        linkedUserId = linkedUserId,
        displayName = normalizeRequiredText(displayName, "displayName", STAFF_PROFILE_DISPLAY_NAME_MAX_LENGTH),
        roleLabel = normalizeNullableText(roleLabel, STAFF_PROFILE_SHORT_TEXT_MAX_LENGTH),
        subtype = normalizeProfileSubtype(subtype),
        photoRef = normalizeNullableText(photoRef, STAFF_PROFILE_PHOTO_REF_MAX_LENGTH),
        bio = normalizeNullableText(bio, STAFF_PROFILE_BIO_MAX_LENGTH),
        tags = normalizeProfileTags(tags),
        isGuestVisible = isGuestVisible,
    )

private fun VenueStaffProfileUpdateRequest.toOwnerWrite(
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

private fun VenueStaffProfileUpdateRequest.hasOwnerOnlyFields(): Boolean =
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

private suspend fun requireLinkedUserInVenue(
    venueStaffRepository: VenueStaffRepository,
    venueId: Long,
    linkedUserId: Long?,
) {
    if (linkedUserId == null) {
        return
    }
    venueStaffRepository.findMember(venueId, linkedUserId)
        ?: throw InvalidInputException("linkedUserId must be an existing venue member")
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

private suspend fun appendStaffProfileAuditBestEffort(
    auditLogRepository: AuditLogRepository,
    actorUserId: Long,
    action: String,
    venueId: Long,
    profileId: Long,
) {
    runCatching {
        auditLogRepository.appendJson(
            actorUserId = actorUserId,
            action = action,
            entityType = "staff_profile",
            entityId = profileId,
            payload =
                buildJsonObject {
                    put("venueId", venueId)
                    put("profileId", profileId)
                },
        )
    }
}

private suspend fun appendStaffShiftAuditBestEffort(
    auditLogRepository: AuditLogRepository,
    actorUserId: Long,
    venueId: Long,
    profileId: Long,
    shiftId: Long,
    status: String,
) {
    runCatching {
        auditLogRepository.appendJson(
            actorUserId = actorUserId,
            action = "staff_shift_marked_$status",
            entityType = "staff_shift",
            entityId = shiftId,
            payload =
                buildJsonObject {
                    put("venueId", venueId)
                    put("profileId", profileId)
                    put("shiftId", shiftId)
                    put("status", status)
                },
        )
    }
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
