package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("VenueRoutes")

@Serializable
data class VenueMeResponse(
    val userId: Long,
    val venues: List<VenueAccessDto>
)

@Serializable
data class VenueAccessDto(
    val venueId: Long,
    val role: String,
    val permissions: List<String>
)

@Serializable
data class StaffChatLinkCodeResponse(
    val code: String,
    val expiresAt: String,
    val ttlSeconds: Long
)

@Serializable
data class StaffChatStatusResponse(
    val venueId: Long,
    val isLinked: Boolean,
    val chatId: Long? = null,
    val linkedAt: String? = null,
    val linkedByUserId: Long? = null,
    val activeCodeHint: String? = null,
    val activeCodeExpiresAt: String? = null
)

fun Route.venueRoutes(
    venueAccessRepository: VenueAccessRepository,
    staffChatLinkCodeRepository: StaffChatLinkCodeRepository,
    venueRepository: VenueRepository
) {
    route("/venue") {
        get("/me") {
            val userId = call.requireUserId()
            val memberships = venueAccessRepository.listVenueMemberships(userId)
            val venues = memberships.mapNotNull { membership ->
                val role = VenueRoleMapping.fromDb(membership.role)
                if (role == null) {
                    logger.warn("Unknown venue role {} for userId={} venueId={}", membership.role, userId, membership.venueId)
                    return@mapNotNull null
                }
                val permissions = VenuePermissions.forRole(role)
                VenueAccessDto(
                    venueId = membership.venueId,
                    role = role.name,
                    permissions = permissions.map { it.name }
                )
            }
            if (venues.isEmpty()) {
                throw ForbiddenException()
            }
            call.respond(VenueMeResponse(userId = userId, venues = venues))
        }

        post("/{venueId}/staff-chat/link-code") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(
                venueAccessRepository = venueAccessRepository,
                userId = userId,
                venueId = venueId
            )
            val permissions = VenuePermissions.forRole(role)
            if (!permissions.contains(VenuePermission.STAFF_CHAT_LINK)) {
                throw ForbiddenException()
            }
            val venueExists = venueRepository.findVenueById(venueId) != null
            if (!venueExists) {
                throw NotFoundException()
            }
            val created = staffChatLinkCodeRepository.createLinkCode(venueId, userId)
                ?: throw DatabaseUnavailableException()
            logger.info(
                "Generated staff chat link code venueId={} by userId={} expiresAt={}",
                venueId,
                userId,
                created.expiresAt
            )
            call.respond(
                StaffChatLinkCodeResponse(
                    code = created.code,
                    expiresAt = created.expiresAt.toString(),
                    ttlSeconds = created.ttlSeconds
                )
            )
        }

        get("/{venueId}/staff-chat") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            resolveVenueRole(
                venueAccessRepository = venueAccessRepository,
                userId = userId,
                venueId = venueId
            )
            val status = venueRepository.findStaffChatStatus(venueId) ?: throw NotFoundException()
            val activeCode = staffChatLinkCodeRepository.findActiveCodeForVenue(venueId)
            call.respond(
                StaffChatStatusResponse(
                    venueId = status.venueId,
                    isLinked = status.staffChatId != null,
                    chatId = status.staffChatId,
                    linkedAt = status.linkedAt?.toString(),
                    linkedByUserId = status.linkedByUserId,
                    activeCodeHint = activeCode?.codeHint,
                    activeCodeExpiresAt = activeCode?.expiresAt?.toString()
                )
            )
        }

        post("/{venueId}/staff-chat/unlink") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(
                venueAccessRepository = venueAccessRepository,
                userId = userId,
                venueId = venueId
            )
            val permissions = VenuePermissions.forRole(role)
            if (!permissions.contains(VenuePermission.STAFF_CHAT_LINK)) {
                throw ForbiddenException()
            }
            val venueExists = venueRepository.findVenueById(venueId) != null
            if (!venueExists) {
                throw NotFoundException()
            }
            when (venueRepository.unlinkStaffChatByVenueId(venueId, userId)) {
                is com.hookah.platform.backend.telegram.db.UnlinkResult.Success -> {
                    call.respond(mapOf("ok" to true))
                }
                com.hookah.platform.backend.telegram.db.UnlinkResult.NotLinked -> {
                    call.respond(mapOf("ok" to true))
                }
                com.hookah.platform.backend.telegram.db.UnlinkResult.DatabaseError -> {
                    throw DatabaseUnavailableException()
                }
            }
        }
    }
}
