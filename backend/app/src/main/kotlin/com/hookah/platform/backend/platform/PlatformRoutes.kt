package com.hookah.platform.backend.platform

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.miniapp.venue.requireUserId
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class PlatformMeResponse(
    val ok: Boolean,
    val ownerUserId: Long
)

@Serializable
data class PlatformUserListResponse(
    val users: List<PlatformUserDto>
)

@Serializable
data class PlatformUserDto(
    val userId: Long,
    val username: String? = null,
    val displayName: String,
    val lastSeenAt: String
)

fun ApplicationCall.requirePlatformOwner(platformConfig: PlatformConfig): Long {
    val userId = requireUserId()
    val ownerId = platformConfig.ownerUserId ?: throw ForbiddenException()
    if (userId != ownerId) {
        throw ForbiddenException()
    }
    return userId
}

fun Route.platformRoutes(
    platformConfig: PlatformConfig,
    platformVenueRepository: PlatformVenueRepository,
    platformUserRepository: PlatformUserRepository,
    auditLogRepository: com.hookah.platform.backend.miniapp.venue.AuditLogRepository,
    subscriptionSettingsRepository: PlatformSubscriptionSettingsRepository,
    platformVenueMemberRepository: PlatformVenueMemberRepository,
    staffInviteRepository: com.hookah.platform.backend.miniapp.venue.staff.StaffInviteRepository,
    staffInviteConfig: com.hookah.platform.backend.miniapp.venue.staff.StaffInviteConfig
) {
    route("/platform") {
        get("/me") {
            call.requirePlatformOwner(platformConfig)
            call.respond(PlatformMeResponse(ok = true, ownerUserId = platformConfig.ownerUserId!!))
        }
        get("/users") {
            call.requirePlatformOwner(platformConfig)
            val query = call.request.queryParameters["q"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            val users = platformUserRepository.listUsers(query = query, limit = limit)
            call.respond(
                PlatformUserListResponse(
                    users = users.map { it.toDto() }
                )
            )
        }
    }
    platformVenueRoutes(
        platformConfig = platformConfig,
        venueRepository = platformVenueRepository,
        auditLogRepository = auditLogRepository,
        subscriptionSettingsRepository = subscriptionSettingsRepository,
        platformVenueMemberRepository = platformVenueMemberRepository,
        staffInviteRepository = staffInviteRepository,
        staffInviteConfig = staffInviteConfig
    )
}

private fun PlatformTelegramUser.toDto(): PlatformUserDto {
    val nameParts = listOfNotNull(firstName?.trim(), lastName?.trim())
        .filter { it.isNotBlank() }
    val displayName = when {
        nameParts.isNotEmpty() -> nameParts.joinToString(" ")
        !username.isNullOrBlank() -> username
        else -> "User ${userId}"
    }
    return PlatformUserDto(
        userId = userId,
        username = username?.takeIf { it.isNotBlank() },
        displayName = displayName,
        lastSeenAt = lastSeenAt.toString()
    )
}
