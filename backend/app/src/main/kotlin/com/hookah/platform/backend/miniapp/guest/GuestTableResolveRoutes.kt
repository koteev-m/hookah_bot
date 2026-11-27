package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.ServiceSuspendedException
import com.hookah.platform.backend.api.SubscriptionBlockedException
import com.hookah.platform.backend.api.TableRejoinStaleException
import com.hookah.platform.backend.api.VisitVersionRequiredException
import com.hookah.platform.backend.miniapp.guest.api.RestoredTableContextResponse
import com.hookah.platform.backend.miniapp.guest.api.TableRejoinRequest
import com.hookah.platform.backend.miniapp.guest.api.TableResolveResponse
import com.hookah.platform.backend.miniapp.guest.api.TableRestoreResponse
import com.hookah.platform.backend.miniapp.guest.api.TableSessionEndRequest
import com.hookah.platform.backend.miniapp.guest.api.TableSessionEndResponse
import com.hookah.platform.backend.miniapp.guest.db.GuestOrderTransactionCoordinator
import com.hookah.platform.backend.miniapp.guest.db.GuestTableContextLifecycleRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTableTeardownResult
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.db.PlatformGuestTableResolveResult
import com.hookah.platform.backend.miniapp.guest.db.TableSessionEndBlockedReason
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRecord
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionStatus
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.subscription.VenueAvailabilityResolver
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.telegram.TableContext
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import java.time.Instant

fun Route.guestTableResolveRoutes(
    transactions: GuestOrderTransactionCoordinator,
    tableTokenResolver: suspend (String) -> TableContext?,
    guestVenueRepository: GuestVenueRepository,
    subscriptionRepository: SubscriptionRepository,
    tableSessionRepository: TableSessionRepository,
    tableSessionConfig: TableSessionConfig,
    guestTabsRepository: GuestTabsRepository,
    platformOwnerUserId: Long? = null,
    guestTableContextLifecycleRepository: GuestTableContextLifecycleRepository? = null,
    afterPlatformGuestTeardown: (suspend (GuestTableTeardownResult.Cleared) -> Unit)? = null,
) {
    suspend fun ordinaryResolve(
        userId: Long,
        pointer: TableContext,
        requestedId: Long?,
        allowCreate: Boolean,
        exitEventId: Long? = null,
        verifyOnly: Boolean = false,
    ): TableResolveResponse =
        transactions.execute { connection ->
            // Bot teardown locks the chat before the physical table. Keep that lock order.
            val rejoinWithMembership =
                requestedId != null && exitEventId != null &&
                    tableSessionRepository.hasActiveUserTabMembership(connection, userId, pointer.venueId, requestedId)
            val exitedBotChat =
                if (requestedId != null && exitEventId != null) {
                    guestTableContextLifecycleRepository?.findExitedBotChat(
                        connection,
                        userId,
                        pointer,
                        requestedId,
                        exitEventId,
                        forUpdate = true,
                        includeBillOwners = rejoinWithMembership,
                    )
                } else {
                    null
                }
            val table =
                transactions.lockPublishedTable(
                    connection,
                    pointer.tableToken,
                    pointer.venueId,
                    pointer.tableId,
                )
            val venueId = table.venueId
            val tableId = table.tableId
            var subscription = subscriptionRepository.getSubscriptionStatus(connection, venueId, forUpdate = true)
            val id =
                requestedId ?: if (allowCreate) {
                    tableSessionRepository.findActiveSessionForUpdate(connection, venueId, tableId, Instant.now())
                } else {
                    null
                }
            val existing =
                id?.let {
                    tableSessionRepository.findSessionForTable(connection, it, venueId, tableId, forUpdate = true)
                }
            val now = Instant.now()
            val active =
                existing?.takeIf {
                    it.status == TableSessionStatus.ACTIVE && it.endedAt == null && it.expiresAt > now
                }
            val marker = active?.let { tableSessionRepository.findExitEventId(connection, userId, it.id) }

            val firstEntry = allowCreate && requestedId == null && marker == null && exitEventId == null
            if (subscription == SubscriptionStatus.UNKNOWN && firstEntry) {
                // Only the existing first-entry operation may provision a trial.
                subscriptionRepository.ensureRowExistsForVenue(connection, venueId)
                subscription = subscriptionRepository.getSubscriptionStatus(connection, venueId, forUpdate = true)
            }
            val availability = VenueAvailabilityResolver.resolve(VenueStatus.PUBLISHED, subscription)
            if (exitEventId != null) {
                if (!availability.available) throw SubscriptionBlockedException()
                if (active == null || exitEventId != marker) throw TableRejoinStaleException()
                // A detached chat found through existing membership does not replace that authorization.
                if ((exitedBotChat == null || rejoinWithMembership) &&
                    !tableSessionRepository.hasActiveUserTabMembership(
                        connection,
                        userId,
                        venueId,
                        active.id,
                        forUpdate = true,
                    )
                ) {
                    throw ForbiddenException("Посещение недоступно. Отсканируйте QR стола заново.")
                }
                if (!tableSessionRepository.consumeExitEvent(connection, userId, active.id, exitEventId)) {
                    throw TableRejoinStaleException()
                }
                if (exitedBotChat != null) {
                    checkNotNull(
                        guestTableContextLifecycleRepository,
                    ).resumeBotChat(connection, exitedBotChat, userId, table, active.id, now)
                }
            }

            val state =
                when {
                    !availability.available && requestedId == null ->
                        ResolvedTableSession(
                            null,
                            active = false,
                            inactiveReason = "TABLE_SESSION_MISSING",
                        )
                    active != null && availability.available && marker != null && exitEventId == null ->
                        ResolvedTableSession(active, active = false, inactiveReason = "TABLE_SESSION_EXITED")
                    active != null && requestedId != null && exitEventId == null -> {
                        if (availability.available) {
                            val confirmed =
                                tableSessionRepository.hasActiveUserTabMembership(
                                    connection,
                                    userId,
                                    venueId,
                                    active.id,
                                ) ||
                                    guestTableContextLifecycleRepository?.hasExactBotVisit(
                                        connection, userId, table, active.id, now,
                                    ) == true
                            if (!confirmed) {
                                throw ForbiddenException("Посещение недоступно. Отсканируйте QR стола заново.")
                            }
                        }
                        ResolvedTableSession(active, active = true, inactiveReason = null)
                    }
                    active != null -> {
                        val session =
                            if (availability.available) {
                                tableSessionRepository.touchActiveSession(
                                    connection,
                                    active.id,
                                    venueId,
                                    tableId,
                                    tableSessionConfig.ttl,
                                    now,
                                )
                                    ?: throw TableRejoinStaleException()
                            } else {
                                active
                            }
                        ResolvedTableSession(session, active = true, inactiveReason = null)
                    }
                    requestedId != null ->
                        ResolvedTableSession(
                            existing,
                            requestedId,
                            active = false,
                            inactiveReason = existing?.inactiveReason(now) ?: "TABLE_SESSION_ENDED",
                        )
                    allowCreate && availability.available ->
                        ResolvedTableSession(
                            tableSessionRepository.resolveActiveSession(
                                connection,
                                venueId,
                                tableId,
                                tableSessionConfig.ttl,
                                now,
                            ),
                            active = true,
                            inactiveReason = null,
                        )
                    else -> ResolvedTableSession(null, active = false, inactiveReason = "TABLE_SESSION_MISSING")
                }
            if ((firstEntry || exitEventId != null) && state.active && availability.available) {
                guestTabsRepository.ensurePersonalTab(connection, venueId, state.tableSessionId, userId)
            }
            val challengeRequested = (allowCreate && requestedId == null) || (verifyOnly && requestedId == active?.id)
            val challenge =
                marker?.takeIf {
                    challengeRequested && state.inactiveReason == "TABLE_SESSION_EXITED" &&
                        (
                            tableSessionRepository.hasActiveUserTabMembership(
                                connection, userId, venueId, state.tableSessionId,
                            ) ||
                                guestTableContextLifecycleRepository?.findExitedBotChat(
                                    connection, userId, table, state.tableSessionId, it, forUpdate = false,
                                ) != null
                        )
                }
            TableResolveResponse(
                venueId = venueId,
                venueName = table.venueName,
                tableId = tableId,
                tableSessionId = state.tableSessionId,
                tableSessionStatus = state.status,
                tableSessionActive = state.active,
                tableSessionInactiveReason = state.inactiveReason,
                tableNumber = table.tableNumber.toString(),
                venueStatus = availability.venueStatus.dbValue,
                subscriptionStatus = availability.subscriptionStatus,
                available = availability.available,
                unavailableReason = availability.reason,
                exitEventId = challenge?.toString(),
                visitGeneration =
                    if (state.active && availability.available) {
                        tableSessionRepository.visitGeneration(
                            connection,
                            userId,
                            state.tableSessionId,
                        ).toString()
                    } else {
                        null
                    },
            )
        }

    suspend fun restorePlatformVisit(
        userId: Long,
        tableToken: String,
        venueId: Long,
        tableId: Long,
        sessionId: Long,
        memberTabId: Long? = null,
    ): RestoredTableContextResponse? =
        when (
            val guarded =
                guestTableContextLifecycleRepository?.resolvePlatformMiniApp(
                    actorUserId = userId,
                    tableToken = tableToken,
                    expectedVenueId = venueId,
                    expectedTableId = tableId,
                    requestedTableSessionId = sessionId,
                    ttl = tableSessionConfig.ttl,
                    verifyOnly = true,
                ) ?: PlatformGuestTableResolveResult.DatabaseUnavailable
        ) {
            is PlatformGuestTableResolveResult.Allowed ->
                RestoredTableContextResponse(
                    tableToken = guarded.context.tableToken,
                    tabId = guarded.personalTab?.id ?: memberTabId,
                    venueId = guarded.context.venueId,
                    venueName = guarded.context.venueName,
                    tableId = guarded.context.tableId,
                    tableSessionId = guarded.tableSession.id,
                    visitGeneration = guarded.visitGeneration.toString(),
                    tableSessionStatus = guarded.tableSession.status.name,
                    tableSessionActive = true,
                    tableNumber = guarded.context.tableNumber.toString(),
                    venueStatus = guarded.venueStatus.dbValue,
                    subscriptionStatus = guarded.subscriptionStatus.wire,
                    available = true,
                    unavailableReason = null,
                )
            PlatformGuestTableResolveResult.Denied -> null
            PlatformGuestTableResolveResult.DatabaseUnavailable -> throw DatabaseUnavailableException()
        }

    get("/table/restore") {
        val userId = call.requireUserId()
        val query = call.request.queryParameters
        val exact = query.contains("tableToken") || query.contains("tableSessionId")
        val allVisits = query["scope"] == "active-visits"
        if (allVisits && exact) throw InvalidInputException("Choose either active visits or an exact session")
        val exactToken = if (exact) validateTableToken(query["tableToken"]) else null
        val exactSession =
            if (exact) {
                query["tableSessionId"]?.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw InvalidInputException("tableSessionId must be a positive number")
            } else {
                null
            }
        val botVisits =
            guestTableContextLifecycleRepository?.activeBotVisits(
                userId,
                allowMember = allVisits && userId != platformOwnerUserId,
                expectedToken = exactToken,
                expectedSession = exactSession,
            ) ?: throw DatabaseUnavailableException()
        // Confirmation belongs to this actor. Order edits and other guests' activity
        // cannot select a different venue. Equal confirmations do not break a tie.
        val latestConfirmation = botVisits.maxOfOrNull { it.identity.confirmedAt ?: Instant.MIN }
        val latest =
            botVisits.filter { (it.identity.confirmedAt ?: Instant.MIN) == latestConfirmation }
                .distinctBy { it.tableSession.id to it.context.tableToken }
        if (!allVisits && latest.size > 1) {
            call.respondNoRestoredTableContext()
            return@get
        }
        for (bot in if (allVisits) botVisits else latest) {
            if (bot.venueStatus == VenueStatus.PAUSED || bot.subscriptionStatus.isBlockedForGuest()) {
                if (exact && (userId != platformOwnerUserId || botVisits.size == 1)) {
                    call.respond(
                        TableRestoreResponse(
                            null,
                            if (bot.venueStatus == VenueStatus.PAUSED) "SERVICE_SUSPENDED" else "SUBSCRIPTION_BLOCKED",
                        ),
                    )
                    return@get
                }
                continue
            }
            val table = bot.context
            val member =
                guestTabsRepository.findLatestRestorableTableContext(userId, table.tableToken, bot.tableSession.id)
            val context =
                if (userId == platformOwnerUserId) {
                    restorePlatformVisit(
                        userId,
                        table.tableToken,
                        table.venueId,
                        table.tableId,
                        bot.tableSession.id,
                        member?.tabId,
                    )
                } else {
                    RestoredTableContextResponse(
                        tableToken = table.tableToken,
                        tabId = member?.tabId,
                        venueId = table.venueId,
                        venueName = table.venueName,
                        tableId = table.tableId,
                        tableSessionId = bot.tableSession.id,
                        visitGeneration = bot.visitGeneration.toString(),
                        tableSessionStatus = bot.tableSession.status.name,
                        tableSessionActive = true,
                        tableNumber = table.tableNumber.toString(),
                        venueStatus = bot.venueStatus.dbValue,
                        subscriptionStatus = bot.subscriptionStatus.wire,
                        available = true,
                        unavailableReason = null,
                    )
                }
            if (context != null) {
                call.respond(TableRestoreResponse(context))
                return@get
            }
        }
        if (userId == platformOwnerUserId) {
            call.respondNoRestoredTableContext()
            return@get
        }
        val candidates =
            if (exactToken != null && exactSession != null) {
                listOfNotNull(guestTabsRepository.findLatestRestorableTableContext(userId, exactToken, exactSession))
            } else {
                guestTabsRepository.activeVisitContexts(userId)
            }
        var selected: RestoredTableContextResponse? = null
        for (restored in candidates.distinctBy { it.tableSessionId to it.tableToken }) {
            val venue = guestVenueRepository.findVenueByIdForGuest(restored.venueId)
            if (exact && venue?.status == VenueStatus.PAUSED) {
                call.respond(TableRestoreResponse(null, "SERVICE_SUSPENDED"))
                return@get
            }
            if (venue == null || venue.status != VenueStatus.PUBLISHED) continue
            val subscriptionStatus =
                subscriptionRepository.findSubscriptionState(restored.venueId)?.status ?: SubscriptionStatus.UNKNOWN
            val availability = VenueAvailabilityResolver.resolve(venue.status, subscriptionStatus)
            if (!availability.available) {
                if (exact) {
                    call.respond(TableRestoreResponse(null, availability.reason))
                    return@get
                }
                continue
            }
            if (selected != null) {
                call.respondNoRestoredTableContext()
                return@get
            }
            selected =
                RestoredTableContextResponse(
                    tableToken = restored.tableToken,
                    tabId = restored.tabId,
                    venueId = restored.venueId,
                    venueName = venue.name,
                    tableId = restored.tableId,
                    tableSessionId = restored.tableSessionId,
                    visitGeneration = restored.visitGeneration.toString(),
                    tableSessionStatus = restored.tableSessionStatus,
                    tableSessionActive = true,
                    tableNumber = restored.tableNumber.toString(),
                    venueStatus = availability.venueStatus.dbValue,
                    subscriptionStatus = availability.subscriptionStatus,
                    available = true,
                    unavailableReason = null,
                )
            if (allVisits) break // Work-mode admission only needs proof that a visit exists.
        }
        call.respond(TableRestoreResponse(selected))
    }

    get("/table/resolve") {
        val userId = call.requireUserId()
        val rawToken = call.request.queryParameters["tableToken"]
        val token = validateTableToken(rawToken)
        val requestedTableSessionId =
            call.request.queryParameters["tableSessionId"]?.let { raw ->
                raw.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw InvalidInputException("tableSessionId must be a positive number")
            }
        val allowCreateSession = call.request.queryParameters["resolveMode"] == "create"
        val verifyOnly = call.request.queryParameters["verifyOnly"] == "true"
        if (verifyOnly && (allowCreateSession || requestedTableSessionId == null)) {
            throw InvalidInputException("Verification requires an exact existing session")
        }

        suspend fun checkSuspendedParticipation() {
            if (requestedTableSessionId == null) return
            val member =
                if (userId == platformOwnerUserId) {
                    null
                } else {
                    guestTabsRepository.findLatestRestorableTableContext(userId, token, requestedTableSessionId)
                }
            if (member != null &&
                guestVenueRepository.findVenueByIdForGuest(member.venueId)?.status == VenueStatus.PAUSED
            ) {
                throw ServiceSuspendedException()
            }
            if (member == null && guestTableContextLifecycleRepository?.activeBotVisits(
                    userId, false, token, requestedTableSessionId,
                )?.singleOrNull()?.venueStatus == VenueStatus.PAUSED
            ) {
                throw ServiceSuspendedException()
            }
        }
        val table =
            tableTokenResolver(token)
                ?: run {
                    checkSuspendedParticipation()
                    if (userId == platformOwnerUserId) throw ForbiddenException(PLATFORM_GUEST_RECONFIRM_MESSAGE)
                    throw NotFoundException()
                }
        if (userId == platformOwnerUserId) {
            when (
                val guarded =
                    guestTableContextLifecycleRepository?.resolvePlatformMiniApp(
                        actorUserId = userId,
                        tableToken = token,
                        expectedVenueId = table.venueId,
                        expectedTableId = table.tableId,
                        requestedTableSessionId = requestedTableSessionId,
                        ttl = tableSessionConfig.ttl,
                        verifyOnly = !allowCreateSession,
                        createPersonalTab = allowCreateSession && requestedTableSessionId == null,
                    ) ?: PlatformGuestTableResolveResult.DatabaseUnavailable
            ) {
                is PlatformGuestTableResolveResult.Allowed ->
                    call.respond(
                        TableResolveResponse(
                            venueId = guarded.context.venueId,
                            venueName = guarded.context.venueName,
                            tableId = guarded.context.tableId,
                            tableSessionId = guarded.tableSession.id,
                            visitGeneration = guarded.visitGeneration.toString(),
                            tableSessionStatus = guarded.tableSession.status.name,
                            tableSessionActive = true,
                            tableSessionInactiveReason = null,
                            tableNumber = guarded.context.tableNumber.toString(),
                            venueStatus = guarded.venueStatus.dbValue,
                            subscriptionStatus = guarded.subscriptionStatus.wire,
                            available = true,
                            unavailableReason = null,
                        ),
                    )
                PlatformGuestTableResolveResult.Denied -> {
                    if (requestedTableSessionId != null) {
                        val exact =
                            guestTableContextLifecycleRepository?.activeBotVisits(
                                userId, false, token, requestedTableSessionId,
                            ) ?: throw DatabaseUnavailableException()
                        if (exact.singleOrNull()?.venueStatus == VenueStatus.PAUSED) throw ServiceSuspendedException()
                        if (exact.singleOrNull()?.subscriptionStatus?.isBlockedForGuest() == true) {
                            throw SubscriptionBlockedException()
                        }
                    }
                    throw ForbiddenException(PLATFORM_GUEST_RECONFIRM_MESSAGE)
                }
                PlatformGuestTableResolveResult.DatabaseUnavailable -> throw DatabaseUnavailableException()
            }
            return@get
        }
        val resolved =
            try {
                ordinaryResolve(userId, table, requestedTableSessionId, allowCreateSession, verifyOnly = verifyOnly)
            } catch (failure: NotFoundException) {
                checkSuspendedParticipation()
                throw failure
            }
        call.respond(resolved)
    }

    post("/table/session/rejoin") {
        val userId = call.requireUserId()
        if (userId == platformOwnerUserId) throw ForbiddenException(PLATFORM_GUEST_RECONFIRM_MESSAGE)
        val request = call.receive<TableRejoinRequest>()
        val token = validateTableToken(request.tableToken)
        val eventId =
            request.exitEventId.toLongOrNull()?.takeIf { it > 0 && it.toString() == request.exitEventId }
                ?: throw InvalidInputException("Invalid exitEventId")
        if (request.tableSessionId <= 0) throw InvalidInputException("Invalid tableSessionId")
        val table = tableTokenResolver(token) ?: throw NotFoundException()
        call.respond(ordinaryResolve(userId, table, request.tableSessionId, allowCreate = false, exitEventId = eventId))
    }

    post("/table/session/end") {
        val userId = call.requireUserId()
        val request = call.receive<TableSessionEndRequest>()
        val token = validateTableToken(request.tableToken)
        val expected = parseVisitGeneration(request.expectedVisitGeneration) ?: throw VisitVersionRequiredException()
        val tableSessionId =
            request.tableSessionId.takeIf { it > 0 }
                ?: throw InvalidInputException("tableSessionId must be a positive number")
        if (userId != platformOwnerUserId) {
            val table = tableTokenResolver(token) ?: throw NotFoundException()
            val result =
                tableSessionRepository.endUserTableSession(
                    userId = userId,
                    tableToken = token,
                    venueId = table.venueId,
                    tableId = table.tableId,
                    tableSessionId = tableSessionId,
                    expectedVisitGeneration = expected,
                )
            if (result != null) {
                call.respond(
                    TableSessionEndResponse(
                        ended = result.ended,
                        tableSessionId = result.tableSessionId,
                        blockedReason = result.blockedReason?.name,
                        message = result.blockedReason.endVisitMessage(result.ended),
                    ),
                )
                return@post
            }
        }
        when (
            val guarded =
                guestTableContextLifecycleRepository?.teardownByActorAndToken(
                    actorUserId = userId,
                    tableToken = token,
                    expectedTableSessionId = tableSessionId,
                    ordinaryBotVisit = userId != platformOwnerUserId,
                    expectedVisitGeneration = expected,
                ) ?: GuestTableTeardownResult.DatabaseUnavailable
        ) {
            is GuestTableTeardownResult.Cleared -> {
                try {
                    if (userId == platformOwnerUserId) {
                        afterPlatformGuestTeardown?.invoke(guarded)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Authoritative teardown is already committed; menu notification is best-effort.
                }
                call.respond(
                    TableSessionEndResponse(
                        ended = true,
                        tableSessionId = guarded.tableSessionId ?: tableSessionId,
                        blockedReason = null,
                        message = "Визит завершён. Чтобы снова заказать за столом, отсканируйте QR.",
                    ),
                )
            }
            is GuestTableTeardownResult.Blocked ->
                call.respond(
                    TableSessionEndResponse(
                        ended = false,
                        tableSessionId = guarded.tableSessionId,
                        blockedReason = guarded.reason.name,
                        message = guarded.reason.endVisitMessage(ended = false),
                    ),
                )
            GuestTableTeardownResult.Missing,
            GuestTableTeardownResult.Denied,
            -> {
                if (tableSessionRepository.hasCommittedExit(userId, token, tableSessionId, expected)) {
                    call.respond(TableSessionEndResponse(true, tableSessionId, null, null))
                } else if (userId == platformOwnerUserId) {
                    throw ForbiddenException(PLATFORM_GUEST_RECONFIRM_MESSAGE)
                } else {
                    throw NotFoundException()
                }
            }
            GuestTableTeardownResult.DatabaseUnavailable -> throw DatabaseUnavailableException()
        }
    }
}

private data class ResolvedTableSession(
    val session: TableSessionRecord?,
    val requestedSessionId: Long? = null,
    val active: Boolean,
    val inactiveReason: String?,
) {
    val tableSessionId: Long = session?.id ?: requestedSessionId ?: 0L
    val status: String =
        if (!active && inactiveReason == "TABLE_SESSION_EXPIRED") {
            "EXPIRED"
        } else {
            session?.status?.name ?: "UNKNOWN"
        }
}

private fun TableSessionRecord.inactiveReason(now: Instant): String =
    if (expiresAt <= now) "TABLE_SESSION_EXPIRED" else "TABLE_SESSION_ENDED"

private suspend fun io.ktor.server.application.ApplicationCall.respondNoRestoredTableContext() {
    respond(TableRestoreResponse(context = null))
}

private fun TableSessionEndBlockedReason?.endVisitMessage(ended: Boolean): String? =
    when (this) {
        TableSessionEndBlockedReason.ACTIVE_ORDER -> "Сначала закройте счёт. После этого визит можно завершить."
        TableSessionEndBlockedReason.ACTIVE_STAFF_CALL ->
            "Дождитесь завершения вызова персонала или обратитесь к сотруднику."
        null -> if (ended) "Визит завершён. Чтобы снова заказать за столом, отсканируйте QR." else null
    }

internal const val PLATFORM_GUEST_RECONFIRM_MESSAGE =
    "Откройте QR-код ещё раз и подтвердите вход в Telegram."

internal fun parseVisitGeneration(raw: String?): Long? =
    raw?.let {
        it.toLongOrNull()?.takeIf { version -> version >= 0 && version.toString() == raw }
            ?: throw InvalidInputException("Invalid expectedVisitGeneration")
    }
