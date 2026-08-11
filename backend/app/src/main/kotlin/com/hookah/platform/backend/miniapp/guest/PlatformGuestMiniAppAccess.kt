package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.miniapp.guest.db.ConfirmedPlatformGuestMutationContext
import com.hookah.platform.backend.miniapp.guest.db.GuestTableContextLifecycleRepository
import com.hookah.platform.backend.miniapp.guest.db.PlatformGuestTableMutationResult
import com.hookah.platform.backend.miniapp.guest.db.PlatformGuestTableResolveResult
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
