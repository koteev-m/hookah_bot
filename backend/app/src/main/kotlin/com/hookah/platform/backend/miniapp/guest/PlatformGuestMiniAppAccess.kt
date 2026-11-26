package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.VisitVersionRequiredException
import com.hookah.platform.backend.miniapp.guest.db.ConfirmedPlatformGuestMutationContext
import com.hookah.platform.backend.miniapp.guest.db.GuestTableContextLifecycleRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.PlatformGuestTableMutationResult
import com.hookah.platform.backend.miniapp.guest.db.PlatformGuestTableResolveResult
import com.hookah.platform.backend.miniapp.guest.db.TableSessionContext
import com.hookah.platform.backend.telegram.TableContext
import java.sql.Connection
import java.time.Duration

internal suspend fun requirePlatformGuestTokenAccessIfNeeded(
    userId: Long,
    platformOwnerUserId: Long?,
    lifecycleRepository: GuestTableContextLifecycleRepository?,
    tableToken: String,
    table: TableContext,
    requestedTableSessionId: Long?,
    ttl: Duration?,
): PlatformGuestTableResolveResult.Allowed? {
    if (userId != platformOwnerUserId) {
        return null
    }
    val resolvedTtl = ttl ?: throw DatabaseUnavailableException()
    return requirePlatformGuestAccess(
        lifecycleRepository?.resolvePlatformMiniApp(
            actorUserId = userId,
            tableToken = tableToken,
            expectedVenueId = table.venueId,
            expectedTableId = table.tableId,
            requestedTableSessionId = requestedTableSessionId,
            ttl = resolvedTtl,
            verifyOnly = true,
        ) ?: PlatformGuestTableResolveResult.DatabaseUnavailable,
    )
}

internal suspend fun requirePlatformGuestSessionAccessIfNeeded(
    userId: Long,
    platformOwnerUserId: Long?,
    lifecycleRepository: GuestTableContextLifecycleRepository?,
    venueId: Long,
    tableId: Long,
    tableSessionId: Long,
    ttl: Duration?,
): PlatformGuestTableResolveResult.Allowed? {
    if (userId != platformOwnerUserId) {
        return null
    }
    val resolvedTtl = ttl ?: throw DatabaseUnavailableException()
    return requirePlatformGuestAccess(
        lifecycleRepository?.resolvePlatformMiniAppBySession(
            actorUserId = userId,
            expectedVenueId = venueId,
            expectedTableId = tableId,
            requestedTableSessionId = tableSessionId,
            ttl = resolvedTtl,
        ) ?: PlatformGuestTableResolveResult.DatabaseUnavailable,
    )
}

internal suspend fun <T> requireConfirmedPlatformGuestMutation(
    userId: Long,
    platformOwnerUserId: Long?,
    lifecycleRepository: GuestTableContextLifecycleRepository?,
    chatId: Long? = null,
    tableToken: String? = null,
    expectedVenueId: Long? = null,
    expectedTableId: Long? = null,
    expectedTableSessionId: Long? = null,
    ttl: Duration?,
    touchSessionBeforeMutation: Boolean = true,
    mutation: (Connection, ConfirmedPlatformGuestMutationContext) -> T,
): T {
    val resolvedTtl = ttl ?: throw DatabaseUnavailableException()
    val result =
        lifecycleRepository?.withConfirmedPlatformGuestMutation(
            actorUserId = userId,
            platformOwnerUserId = platformOwnerUserId,
            chatId = chatId,
            tableToken = tableToken,
            expectedVenueId = expectedVenueId,
            expectedTableId = expectedTableId,
            expectedSessionId = expectedTableSessionId,
            ttl = resolvedTtl,
            touchSessionBeforeMutation = touchSessionBeforeMutation,
            mutation = mutation,
        ) ?: PlatformGuestTableMutationResult.DatabaseUnavailable
    return when (result) {
        is PlatformGuestTableMutationResult.Applied -> result.value
        PlatformGuestTableMutationResult.Denied -> throw ForbiddenException(PLATFORM_GUEST_RECONFIRM_MESSAGE)
        PlatformGuestTableMutationResult.DatabaseUnavailable -> throw DatabaseUnavailableException()
    }
}

private fun requirePlatformGuestAccess(
    result: PlatformGuestTableResolveResult,
): PlatformGuestTableResolveResult.Allowed =
    when (result) {
        is PlatformGuestTableResolveResult.Allowed -> result
        PlatformGuestTableResolveResult.Denied -> throw ForbiddenException(PLATFORM_GUEST_RECONFIRM_MESSAGE)
        PlatformGuestTableResolveResult.DatabaseUnavailable -> throw DatabaseUnavailableException()
    }

internal fun requiredVisitGeneration(
    sessionId: Long,
    generation: String?,
): Long {
    if (sessionId <= 0) throw VisitVersionRequiredException()
    return parseVisitGeneration(generation) ?: throw VisitVersionRequiredException()
}

internal fun requiredOrderId(orderId: Long?): Long = orderId?.takeIf { it > 0 } ?: throw VisitVersionRequiredException()

internal suspend fun <T> GuestTabsRepository.mutateParticipation(
    userId: Long,
    session: TableSessionContext,
    generation: Long,
    ownerId: Long?,
    lifecycle: GuestTableContextLifecycleRepository?,
    ttl: Duration?,
    tableToken: String? = null,
    chatId: Long? = null,
    requiredTab: Long? = null,
    touchOnSuccess: Boolean = true,
    mutation: (Connection) -> T,
): T {
    fun applyAuthorized(connection: Connection): T {
        if (requiredTab != null) {
            val member = isTabMember(connection, requiredTab, session.venueId, session.id, userId, forUpdate = true)
            if (!member) throw ForbiddenException()
        }
        return mutation(connection)
    }

    if (userId != ownerId) {
        return withActiveOrdinarySession(
            session,
            userId,
            lifecycle.takeIf {
                requiredTab == null || chatId != null
            },
            generation,
            tableToken,
            chatId,
            operation = ::applyAuthorized,
        )
    }
    return requireConfirmedPlatformGuestMutation(
        userId, ownerId, lifecycle, chatId = chatId, tableToken = tableToken, expectedVenueId = session.venueId,
        expectedTableId = session.tableId,
        expectedTableSessionId = session.id,
        ttl = ttl,
        touchSessionBeforeMutation = false,
    ) { connection, confirmed ->
        checkGeneration(connection, userId, confirmed.tableSession.id, generation)
        applyAuthorized(
            connection,
        ).also { if (touchOnSuccess) touchParticipation(connection, session, checkNotNull(ttl)) }
    }
}
