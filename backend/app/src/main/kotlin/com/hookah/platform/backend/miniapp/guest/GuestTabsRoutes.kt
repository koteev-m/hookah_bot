package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.api.CreatePersonalTabRequest
import com.hookah.platform.backend.miniapp.guest.api.CreateSharedTabRequest
import com.hookah.platform.backend.miniapp.guest.api.CreateTabInviteRequest
import com.hookah.platform.backend.miniapp.guest.api.CreateTabInviteResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestTabDto
import com.hookah.platform.backend.miniapp.guest.api.GuestTabResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestTabsResponse
import com.hookah.platform.backend.miniapp.guest.api.JoinTabRequest
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.requireUserId
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val inviteDefaultTtl: Duration = Duration.ofMinutes(15)
private val inviteMaxTtl: Duration = Duration.ofHours(24)

fun Route.guestTabsRoutes(
    guestTabsRepository: GuestTabsRepository,
    guestVenueRepository: GuestVenueRepository,
    subscriptionRepository: SubscriptionRepository,
) {
    get("/tabs") {
        val tableSessionId = parseTableSessionId(call.request.queryParameters["table_session_id"])
        val userId = call.requireUserId()
        val session = guestTabsRepository.findActiveTableSession(tableSessionId) ?: throw NotFoundException()
        ensureGuestActionAvailable(session.venueId, guestVenueRepository, subscriptionRepository)

        guestTabsRepository.ensurePersonalTab(
            venueId = session.venueId,
            tableSessionId = session.id,
            userId = userId,
        )
        val tabs = guestTabsRepository.listTabsForUser(session.venueId, session.id, userId)
        call.respond(GuestTabsResponse(tabs = tabs.map { it.toDto() }))
    }

    post("/tabs/personal") {
        val request = call.receive<CreatePersonalTabRequest>()
        val userId = call.requireUserId()
        val session = guestTabsRepository.findActiveTableSession(request.tableSessionId) ?: throw NotFoundException()
        ensureGuestActionAvailable(session.venueId, guestVenueRepository, subscriptionRepository)

        val tab =
            guestTabsRepository.ensurePersonalTab(
                venueId = session.venueId,
                tableSessionId = session.id,
                userId = userId,
            )
        call.respond(GuestTabResponse(tab = tab.toDto()))
    }

    post("/tabs/shared") {
        val request = call.receive<CreateSharedTabRequest>()
        val userId = call.requireUserId()
        val session = guestTabsRepository.findActiveTableSession(request.tableSessionId) ?: throw NotFoundException()
        ensureGuestActionAvailable(session.venueId, guestVenueRepository, subscriptionRepository)

        val tab =
            guestTabsRepository.createSharedTab(
                venueId = session.venueId,
                tableSessionId = session.id,
                ownerUserId = userId,
            )
        call.respond(GuestTabResponse(tab = tab.toDto()))
    }

    route("/tabs/{tabId}/invite") {
        post {
            val tabId =
                call.parameters["tabId"]?.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw InvalidInputException("tabId must be positive")
            val request = call.receive<CreateTabInviteRequest>()
            val userId = call.requireUserId()
            val session =
                guestTabsRepository.findActiveTableSession(request.tableSessionId)
                    ?: throw NotFoundException()
            ensureGuestActionAvailable(session.venueId, guestVenueRepository, subscriptionRepository)

            val ttl = normalizeInviteTtl(request.ttlSeconds)
            val token = UUID.randomUUID().toString().replace("-", "")
            val expiresAt = Instant.now().plus(ttl)
            val created =
                guestTabsRepository.createInvite(
                    tabId = tabId,
                    venueId = session.venueId,
                    tableSessionId = session.id,
                    createdBy = userId,
                    token = token,
                    expiresAt = expiresAt,
                )
            if (!created) {
                throw ForbiddenException("Only shared tab owner can create invites")
            }
            call.respond(
                CreateTabInviteResponse(
                    tabId = tabId,
                    token = token,
                    expiresAtEpochSeconds = expiresAt.epochSecond,
                ),
            )
        }
    }

    post("/tabs/join") {
        val request = call.receive<JoinTabRequest>()
        val userId = call.requireUserId()
        if (!request.consent) {
            throw InvalidInputException("consent must be true")
        }
        val token = request.token.trim()
        if (token.isEmpty()) {
            throw InvalidInputException("token must not be blank")
        }
        val session = guestTabsRepository.findActiveTableSession(request.tableSessionId) ?: throw NotFoundException()
        ensureGuestActionAvailable(session.venueId, guestVenueRepository, subscriptionRepository)

        val tab =
            guestTabsRepository.joinByInvite(
                venueId = session.venueId,
                tableSessionId = session.id,
                userId = userId,
                token = token,
            ) ?: throw NotFoundException("Invite is invalid or expired")
        call.respond(GuestTabResponse(tab = tab.toDto()))
    }
}

private fun parseTableSessionId(raw: String?): Long {
    val parsed = raw?.toLongOrNull() ?: throw InvalidInputException("table_session_id must be positive")
    if (parsed <= 0) {
        throw InvalidInputException("table_session_id must be positive")
    }
    return parsed
}

private fun normalizeInviteTtl(rawTtlSeconds: Long?): Duration {
    if (rawTtlSeconds == null) {
        return inviteDefaultTtl
    }
    if (rawTtlSeconds <= 0) {
        throw InvalidInputException("ttlSeconds must be positive")
    }
    val ttl = Duration.ofSeconds(rawTtlSeconds)
    if (ttl > inviteMaxTtl) {
        throw InvalidInputException("ttlSeconds must be <= ${inviteMaxTtl.seconds}")
    }
    return ttl
}

private fun com.hookah.platform.backend.miniapp.guest.db.GuestTabModel.toDto(): GuestTabDto =
    GuestTabDto(
        id = id,
        tableSessionId = tableSessionId,
        type = type,
        ownerUserId = ownerUserId,
        status = status,
    )
