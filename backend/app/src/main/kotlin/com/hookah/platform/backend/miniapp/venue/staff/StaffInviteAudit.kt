package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.Logger
import java.time.Instant

const val VENUE_OWNER_INVITE_CREATE_AUDIT_ACTION = "VENUE_OWNER_INVITE_CREATE"
const val VENUE_OWNER_INVITE_ACCEPT_AUDIT_ACTION = "VENUE_OWNER_INVITE_ACCEPT"

suspend fun appendOwnerInviteCreateAuditBestEffort(
    auditLogRepository: AuditLogRepository,
    actorUserId: Long,
    venueId: Long,
    ttlSeconds: Long,
    expiresAt: Instant,
    deepLinkAvailable: Boolean,
    logger: Logger,
) {
    runCatching {
        auditLogRepository.appendJson(
            actorUserId = actorUserId,
            action = VENUE_OWNER_INVITE_CREATE_AUDIT_ACTION,
            entityType = "venue",
            entityId = venueId,
            payload =
                buildJsonObject {
                    put("venueId", venueId)
                    put("role", VenueRole.OWNER.name)
                    put("ttlSeconds", ttlSeconds)
                    put("expiresAt", expiresAt.toString())
                    put("deepLinkAvailable", deepLinkAvailable)
                },
        )
    }.onFailure { error ->
        logger.warn(
            "Failed to append owner invite create audit venueId={} actorUserId={}: {}",
            venueId,
            actorUserId,
            sanitizeTelegramForLog(error.message),
        )
    }
}

suspend fun appendOwnerInviteAcceptAuditBestEffort(
    auditLogRepository: AuditLogRepository,
    result: StaffInviteAcceptResult.Success,
    logger: Logger,
) {
    if (!result.invitedRole.equals(VenueRole.OWNER.name, ignoreCase = true)) {
        return
    }
    runCatching {
        auditLogRepository.appendJson(
            actorUserId = result.member.userId,
            action = VENUE_OWNER_INVITE_ACCEPT_AUDIT_ACTION,
            entityType = "venue",
            entityId = result.member.venueId,
            payload =
                buildJsonObject {
                    put("venueId", result.member.venueId)
                    put("role", VenueRole.OWNER.name)
                    put("acceptedUserId", result.member.userId)
                    put("inviteCreatedByUserId", result.inviteCreatedByUserId)
                    put("alreadyMember", result.alreadyMember)
                    put("roleChanged", result.roleChanged)
                    put("keptHigherRole", result.keptHigherRole)
                },
        )
    }.onFailure { error ->
        logger.warn(
            "Failed to append owner invite accept audit venueId={} acceptedUserId={}: {}",
            result.member.venueId,
            result.member.userId,
            sanitizeTelegramForLog(error.message),
        )
    }
}
