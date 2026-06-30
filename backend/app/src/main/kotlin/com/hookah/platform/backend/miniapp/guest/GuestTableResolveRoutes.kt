package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.api.RestoredTableContextResponse
import com.hookah.platform.backend.miniapp.guest.api.TableResolveResponse
import com.hookah.platform.backend.miniapp.guest.api.TableRestoreResponse
import com.hookah.platform.backend.miniapp.guest.api.TableSessionEndRequest
import com.hookah.platform.backend.miniapp.guest.api.TableSessionEndResponse
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionEndBlockedReason
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRecord
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
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
import java.time.Instant

fun Route.guestTableResolveRoutes(
    tableTokenResolver: suspend (String) -> TableContext?,
    guestVenueRepository: GuestVenueRepository,
    subscriptionRepository: SubscriptionRepository,
    tableSessionRepository: TableSessionRepository,
    tableSessionConfig: TableSessionConfig,
    guestTabsRepository: GuestTabsRepository,
) {
    get("/table/restore") {
        val userId = call.requireUserId()
        val restored = guestTabsRepository.findLatestRestorableTableContext(userId)
        if (restored == null) {
            call.respondNoRestoredTableContext()
            return@get
        }

        val venue = guestVenueRepository.findVenueByIdForGuest(restored.venueId)
        if (venue == null || venue.status != VenueStatus.PUBLISHED) {
            call.respondNoRestoredTableContext()
            return@get
        }

        val subscriptionStatus = subscriptionRepository.getSubscriptionStatus(restored.venueId)
        val availability = VenueAvailabilityResolver.resolve(venue.status, subscriptionStatus)
        if (!availability.available) {
            call.respondNoRestoredTableContext()
            return@get
        }

        val activeSession =
            tableSessionRepository.touchActiveSession(
                tableSessionId = restored.tableSessionId,
                venueId = restored.venueId,
                tableId = restored.tableId,
                ttl = tableSessionConfig.ttl,
            )
        if (activeSession == null) {
            call.respondNoRestoredTableContext()
            return@get
        }

        call.respond(
            TableRestoreResponse(
                context =
                    RestoredTableContextResponse(
                        tableToken = restored.tableToken,
                        tabId = restored.tabId,
                        venueId = restored.venueId,
                        venueName = venue.name,
                        tableId = restored.tableId,
                        tableSessionId = activeSession.id,
                        tableSessionStatus = activeSession.status.name,
                        tableSessionActive = true,
                        tableSessionInactiveReason = null,
                        tableNumber = restored.tableNumber.toString(),
                        venueStatus = availability.venueStatus.dbValue,
                        subscriptionStatus = availability.subscriptionStatus,
                        available = true,
                        unavailableReason = null,
                    ),
            ),
        )
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

        val table = tableTokenResolver(token) ?: throw NotFoundException()
        val venue = ensureVenuePublishedForGuest(table.venueId, guestVenueRepository)
        val subscriptionStatus = subscriptionRepository.getSubscriptionStatus(table.venueId)
        val availability = VenueAvailabilityResolver.resolve(venue.status, subscriptionStatus)
        val now = Instant.now()
        val sessionState =
            if (requestedTableSessionId != null) {
                val activeSession =
                    tableSessionRepository.touchActiveSession(
                        tableSessionId = requestedTableSessionId,
                        venueId = table.venueId,
                        tableId = table.tableId,
                        ttl = tableSessionConfig.ttl,
                        now = now,
                    )
                if (activeSession != null) {
                    ResolvedTableSession(activeSession, active = true, inactiveReason = null)
                } else {
                    val staleSession =
                        tableSessionRepository.findSessionForTable(
                            tableSessionId = requestedTableSessionId,
                            venueId = table.venueId,
                            tableId = table.tableId,
                        )
                    ResolvedTableSession(
                        session = staleSession,
                        requestedSessionId = requestedTableSessionId,
                        active = false,
                        inactiveReason = staleSession?.inactiveReason(now) ?: "TABLE_SESSION_ENDED",
                    )
                }
            } else if (allowCreateSession) {
                ResolvedTableSession(
                    session =
                        tableSessionRepository.resolveActiveSession(
                            venueId = table.venueId,
                            tableId = table.tableId,
                            ttl = tableSessionConfig.ttl,
                            now = now,
                        ),
                    active = true,
                    inactiveReason = null,
                )
            } else {
                ResolvedTableSession(
                    session = null,
                    active = false,
                    inactiveReason = "TABLE_SESSION_MISSING",
                )
            }

        if (sessionState.active) {
            guestTabsRepository.ensurePersonalTab(
                venueId = table.venueId,
                tableSessionId = sessionState.tableSessionId,
                userId = userId,
            )
            tableSessionRepository.clearUserExit(userId, sessionState.tableSessionId)
        }

        call.respond(
            TableResolveResponse(
                venueId = table.venueId,
                venueName = venue.name,
                tableId = table.tableId,
                tableSessionId = sessionState.tableSessionId,
                tableSessionStatus = sessionState.status,
                tableSessionActive = sessionState.active,
                tableSessionInactiveReason = sessionState.inactiveReason,
                tableNumber = table.tableNumber.toString(),
                venueStatus = availability.venueStatus.dbValue,
                subscriptionStatus = availability.subscriptionStatus,
                available = availability.available,
                unavailableReason = availability.reason,
            ),
        )
    }

    post("/table/session/end") {
        val userId = call.requireUserId()
        val request = call.receive<TableSessionEndRequest>()
        val token = validateTableToken(request.tableToken)
        val tableSessionId =
            request.tableSessionId.takeIf { it > 0 }
                ?: throw InvalidInputException("tableSessionId must be a positive number")
        val table = tableTokenResolver(token) ?: throw NotFoundException()
        val result =
            tableSessionRepository.endUserTableSession(
                userId = userId,
                tableToken = token,
                venueId = table.venueId,
                tableId = table.tableId,
                tableSessionId = tableSessionId,
            ) ?: throw NotFoundException()

        call.respond(
            TableSessionEndResponse(
                ended = result.ended,
                tableSessionId = result.tableSessionId,
                blockedReason = result.blockedReason?.name,
                message = result.blockedReason.endVisitMessage(result.ended),
            ),
        )
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
