package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.VisitVersionRequiredException
import com.hookah.platform.backend.miniapp.guest.api.CreatePersonalTabRequest
import com.hookah.platform.backend.miniapp.guest.api.CreateSharedTabRequest
import com.hookah.platform.backend.miniapp.guest.api.CreateTabInviteRequest
import com.hookah.platform.backend.miniapp.guest.api.CreateTabInviteResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestTabDto
import com.hookah.platform.backend.miniapp.guest.api.GuestTabInviteTarget
import com.hookah.platform.backend.miniapp.guest.api.GuestTabResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestTabsResponse
import com.hookah.platform.backend.miniapp.guest.api.JoinTabRequest
import com.hookah.platform.backend.miniapp.guest.db.CreateInviteResult
import com.hookah.platform.backend.miniapp.guest.db.GuestTableContextLifecycleRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionContext
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.requireUserId
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
import java.time.Instant

private val inviteDefaultTtl: Duration = Duration.ofMinutes(15)
private val inviteMaxTtl: Duration = Duration.ofHours(24)
private const val INVITE_CODE_LENGTH: Int = 4
private const val INVITE_CODE_RANGE_EXCLUSIVE: Int = 10_000
private const val INVITE_CODE_MAX_ATTEMPTS: Int = 32
private val inviteCodeRandom = SecureRandom()

fun Route.guestTabsRoutes(
    guestTabsRepository: GuestTabsRepository,
    guestVenueRepository: GuestVenueRepository,
    subscriptionRepository: SubscriptionRepository,
    tableSessionConfig: TableSessionConfig? = null,
    platformOwnerUserId: Long? = null,
    guestTableContextLifecycleRepository: GuestTableContextLifecycleRepository? = null,
) {
    get("/tabs") {
        val tableSessionId = parseTableSessionId(call.request.queryParameters["table_session_id"])
        val userId = call.requireUserId()
        val session = guestTabsRepository.findActiveTableSession(tableSessionId) ?: throw NotFoundException()
        val platformAccess =
            requirePlatformGuestSessionAccessIfNeeded(
                userId = userId,
                platformOwnerUserId = platformOwnerUserId,
                lifecycleRepository = guestTableContextLifecycleRepository,
                venueId = session.venueId,
                tableId = session.tableId,
                tableSessionId = session.id,
                ttl = tableSessionConfig?.ttl,
            )
        if (userId != platformOwnerUserId) {
            ensureGuestActionAvailable(session.venueId, guestVenueRepository, subscriptionRepository, readOnly = true)
        }

        val tabs =
            if (platformAccess == null) {
                guestTabsRepository.listOrdinaryGuestTabs(session, userId)
            } else {
                guestTabsRepository.listTabsForUser(session.venueId, session.id, userId)
            }
        call.respond(GuestTabsResponse(tabs = tabs.map { it.toDto() }))
    }

    suspend fun <T> mutate(
        sessionId: Long,
        generation: String?,
        userId: Long,
        readOnly: Boolean = false,
        action: (Connection, TableSessionContext) -> T,
    ): T {
        val expected = requiredVisitGeneration(sessionId, generation)
        val session = guestTabsRepository.findActiveTableSession(sessionId) ?: throw NotFoundException()
        return guestTabsRepository.mutateParticipation(
            userId,
            session,
            expected,
            platformOwnerUserId,
            guestTableContextLifecycleRepository,
            tableSessionConfig?.ttl,
            touchOnSuccess = !readOnly,
        ) { connection ->
            if (!readOnly && userId == platformOwnerUserId) {
                guestTabsRepository.ensurePersonalTab(
                    connection,
                    session.venueId,
                    sessionId,
                    userId,
                )
            }
            action(connection, session)
        }
    }

    post("/tabs/personal") {
        val request = call.receive<CreatePersonalTabRequest>()
        val userId = call.requireUserId()
        val tab =
            mutate(request.tableSessionId, request.expectedVisitGeneration, userId) { connection, session ->
                guestTabsRepository.ordinaryPersonalTab(connection, session, userId)
            }
        call.respond(GuestTabResponse(tab.toDto()))
    }
    post("/tabs/shared") {
        val request = call.receive<CreateSharedTabRequest>()
        val userId = call.requireUserId()
        val tab =
            mutate(request.tableSessionId, request.expectedVisitGeneration, userId) { connection, session ->
                guestTabsRepository.createSharedTab(connection, session.venueId, session.id, userId)
            }
        call.respond(GuestTabResponse(tab.toDto()))
    }
    post("/tabs/{tabId}/invite") {
        val request = call.receive<CreateTabInviteRequest>()
        val tabId =
            call.parameters["tabId"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw InvalidInputException("Invalid tabId")
        val userId = call.requireUserId()
        val expiresAt = Instant.now().plus(normalizeInviteTtl(request.ttlSeconds))
        val code =
            mutate(request.tableSessionId, request.expectedVisitGeneration, userId) { connection, session ->
                guestTabsRepository.deleteExpiredInvites(connection)
                repeat(INVITE_CODE_MAX_ATTEMPTS) {
                    val candidate = generateInviteCode()
                    when (
                        guestTabsRepository.createInvite(
                            connection,
                            tabId,
                            session.venueId,
                            session.id,
                            userId,
                            candidate,
                            expiresAt,
                        )
                    ) {
                        CreateInviteResult.CREATED -> return@mutate candidate
                        CreateInviteResult.FORBIDDEN -> throw ForbiddenException(
                            "Only shared tab owner can create invites",
                        )
                        CreateInviteResult.TOKEN_CONFLICT -> Unit
                    }
                }
                throw InvalidInputException("Unable to generate invite code")
            }
        call.respond(CreateTabInviteResponse(tabId, code, expiresAt.epochSecond))
    }
    post("/tabs/join-target") {
        val request = call.receive<JoinTabRequest>()
        val userId = call.requireUserId()
        val token = request.token.trim()
        if (token.isEmpty() || token.length > 128) throw InvalidInputException("Invalid invite")
        val target =
            mutate(
                request.tableSessionId,
                request.expectedVisitGeneration,
                userId,
                readOnly = true,
            ) { connection, session ->
                guestTabsRepository.findInviteForUpdate(connection, session.venueId, session.id, token)
                    ?: throw NotFoundException("Invite is invalid or expired")
            }
        call.respond(GuestTabInviteTarget(target.first.toString(), target.second.id))
    }
    post("/tabs/join") {
        val request = call.receive<JoinTabRequest>()
        val userId = call.requireUserId()
        val token = request.token.trim()
        if (!request.consent || token.isEmpty() || token.length > 128) {
            throw InvalidInputException(
                "Invalid invite or missing consent",
            )
        }
        val inviteId = request.inviteId?.toLongOrNull()?.takeIf { it > 0 } ?: throw VisitVersionRequiredException()
        val tabId = request.expectedTabId?.takeIf { it > 0 } ?: throw VisitVersionRequiredException()
        val tab =
            mutate(request.tableSessionId, request.expectedVisitGeneration, userId) { connection, session ->
                guestTabsRepository.joinByInvite(
                    connection, session.venueId, session.id, userId, token, inviteId, tabId,
                )
                    ?: throw NotFoundException("Invite is invalid or expired")
            }
        call.respond(GuestTabResponse(tab.toDto()))
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

private fun generateInviteCode(): String =
    inviteCodeRandom.nextInt(INVITE_CODE_RANGE_EXCLUSIVE).toString().padStart(INVITE_CODE_LENGTH, '0')

private fun com.hookah.platform.backend.miniapp.guest.db.GuestTabModel.toDto(): GuestTabDto =
    GuestTabDto(
        id = id,
        tableSessionId = tableSessionId,
        type = type,
        ownerUserId = ownerUserId,
        status = status,
    )
