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
import com.hookah.platform.backend.miniapp.guest.db.CreateInviteResult
import com.hookah.platform.backend.miniapp.guest.db.GuestTableContextLifecycleRepository
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
import java.security.SecureRandom
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
            ensureGuestActionAvailable(session.venueId, guestVenueRepository, subscriptionRepository)
        }

        if (platformAccess == null) {
            guestTabsRepository.ensurePersonalTab(
                venueId = session.venueId,
                tableSessionId = session.id,
                userId = userId,
            )
        }
        val tabs = guestTabsRepository.listTabsForUser(session.venueId, session.id, userId)
        call.respond(GuestTabsResponse(tabs = tabs.map { it.toDto() }))
    }

    post("/tabs/personal") {
        val request = call.receive<CreatePersonalTabRequest>()
        val userId = call.requireUserId()
        val session = guestTabsRepository.findActiveTableSession(request.tableSessionId) ?: throw NotFoundException()
        if (userId != platformOwnerUserId) {
            ensureGuestActionAvailable(session.venueId, guestVenueRepository, subscriptionRepository)
        }

        val tab =
            if (userId == platformOwnerUserId) {
                requireConfirmedPlatformGuestMutation(
                    userId = userId,
                    platformOwnerUserId = platformOwnerUserId,
                    lifecycleRepository = guestTableContextLifecycleRepository,
                    expectedVenueId = session.venueId,
                    expectedTableId = session.tableId,
                    expectedTableSessionId = session.id,
                    ttl = tableSessionConfig?.ttl,
                ) { connection, confirmed ->
                    guestTabsRepository.ensurePersonalTab(
                        connection = connection,
                        venueId = session.venueId,
                        tableSessionId = confirmed.tableSession.id,
                        userId = userId,
                    )
                }
            } else {
                guestTabsRepository.ensurePersonalTab(
                    venueId = session.venueId,
                    tableSessionId = session.id,
                    userId = userId,
                )
            }
        call.respond(GuestTabResponse(tab = tab.toDto()))
    }

    post("/tabs/shared") {
        val request = call.receive<CreateSharedTabRequest>()
        val userId = call.requireUserId()
        val session = guestTabsRepository.findActiveTableSession(request.tableSessionId) ?: throw NotFoundException()
        if (userId != platformOwnerUserId) {
            ensureGuestActionAvailable(session.venueId, guestVenueRepository, subscriptionRepository)
        }

        val tab =
            if (userId == platformOwnerUserId) {
                requireConfirmedPlatformGuestMutation(
                    userId = userId,
                    platformOwnerUserId = platformOwnerUserId,
                    lifecycleRepository = guestTableContextLifecycleRepository,
                    expectedVenueId = session.venueId,
                    expectedTableId = session.tableId,
                    expectedTableSessionId = session.id,
                    ttl = tableSessionConfig?.ttl,
                ) { connection, confirmed ->
                    guestTabsRepository.ensurePersonalTab(
                        connection = connection,
                        venueId = session.venueId,
                        tableSessionId = confirmed.tableSession.id,
                        userId = userId,
                    )
                    guestTabsRepository.createSharedTab(
                        connection = connection,
                        venueId = session.venueId,
                        tableSessionId = confirmed.tableSession.id,
                        ownerUserId = userId,
                    )
                }
            } else {
                guestTabsRepository.createSharedTab(
                    venueId = session.venueId,
                    tableSessionId = session.id,
                    ownerUserId = userId,
                )
            }
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
            if (userId != platformOwnerUserId) {
                ensureGuestActionAvailable(session.venueId, guestVenueRepository, subscriptionRepository)
            }

            val ttl = normalizeInviteTtl(request.ttlSeconds)
            val expiresAt = Instant.now().plus(ttl)
            if (userId == platformOwnerUserId) {
                val code =
                    requireConfirmedPlatformGuestMutation(
                        userId = userId,
                        platformOwnerUserId = platformOwnerUserId,
                        lifecycleRepository = guestTableContextLifecycleRepository,
                        expectedVenueId = session.venueId,
                        expectedTableId = session.tableId,
                        expectedTableSessionId = session.id,
                        ttl = tableSessionConfig?.ttl,
                    ) { connection, confirmed ->
                        guestTabsRepository.ensurePersonalTab(
                            connection = connection,
                            venueId = session.venueId,
                            tableSessionId = confirmed.tableSession.id,
                            userId = userId,
                        )
                        guestTabsRepository.deleteExpiredInvites(connection)
                        repeat(INVITE_CODE_MAX_ATTEMPTS) {
                            val candidate = generateInviteCode()
                            when (
                                guestTabsRepository.createInvite(
                                    connection = connection,
                                    tabId = tabId,
                                    venueId = session.venueId,
                                    tableSessionId = confirmed.tableSession.id,
                                    createdBy = userId,
                                    token = candidate,
                                    expiresAt = expiresAt,
                                )
                            ) {
                                CreateInviteResult.CREATED ->
                                    return@requireConfirmedPlatformGuestMutation candidate

                                CreateInviteResult.FORBIDDEN ->
                                    throw ForbiddenException("Only shared tab owner can create invites")

                                CreateInviteResult.TOKEN_CONFLICT -> Unit
                            }
                        }
                        throw InvalidInputException("Unable to generate invite code")
                    }
                call.respond(
                    CreateTabInviteResponse(
                        tabId = tabId,
                        token = code,
                        expiresAtEpochSeconds = expiresAt.epochSecond,
                    ),
                )
                return@post
            }

            guestTabsRepository.deleteExpiredInvites()
            repeat(INVITE_CODE_MAX_ATTEMPTS) {
                val code = generateInviteCode()
                when (
                    guestTabsRepository.createInvite(
                        tabId = tabId,
                        venueId = session.venueId,
                        tableSessionId = session.id,
                        createdBy = userId,
                        token = code,
                        expiresAt = expiresAt,
                    )
                ) {
                    CreateInviteResult.CREATED -> {
                        call.respond(
                            CreateTabInviteResponse(
                                tabId = tabId,
                                token = code,
                                expiresAtEpochSeconds = expiresAt.epochSecond,
                            ),
                        )
                        return@post
                    }

                    CreateInviteResult.FORBIDDEN -> {
                        throw ForbiddenException("Only shared tab owner can create invites")
                    }

                    CreateInviteResult.TOKEN_CONFLICT -> Unit
                }
            }
            throw InvalidInputException("Unable to generate invite code")
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
        if (token.length > 128) {
            throw InvalidInputException("token must be <= 128 characters")
        }
        val session = guestTabsRepository.findActiveTableSession(request.tableSessionId) ?: throw NotFoundException()
        if (userId != platformOwnerUserId) {
            ensureGuestActionAvailable(session.venueId, guestVenueRepository, subscriptionRepository)
        }

        val tab =
            if (userId == platformOwnerUserId) {
                requireConfirmedPlatformGuestMutation(
                    userId = userId,
                    platformOwnerUserId = platformOwnerUserId,
                    lifecycleRepository = guestTableContextLifecycleRepository,
                    expectedVenueId = session.venueId,
                    expectedTableId = session.tableId,
                    expectedTableSessionId = session.id,
                    ttl = tableSessionConfig?.ttl,
                ) { connection, confirmed ->
                    guestTabsRepository.ensurePersonalTab(
                        connection = connection,
                        venueId = session.venueId,
                        tableSessionId = confirmed.tableSession.id,
                        userId = userId,
                    )
                    guestTabsRepository.joinByInvite(
                        connection = connection,
                        venueId = session.venueId,
                        tableSessionId = confirmed.tableSession.id,
                        userId = userId,
                        token = token,
                    ) ?: throw NotFoundException("Invite is invalid or expired")
                }
            } else {
                guestTabsRepository.joinByInvite(
                    venueId = session.venueId,
                    tableSessionId = session.id,
                    userId = userId,
                    token = token,
                ) ?: throw NotFoundException("Invite is invalid or expired")
            }
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
