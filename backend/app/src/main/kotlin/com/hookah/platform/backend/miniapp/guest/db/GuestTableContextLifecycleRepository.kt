package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.telegram.TableContext
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.StoredChatContext
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

enum class GuestTableActivationCheckpoint {
    AFTER_SESSION,
    AFTER_EXIT_CLEAR,
    AFTER_DIALOG_CLEAR,
    AFTER_CONTEXT_SAVE,
}

enum class PlatformGuestMutationCheckpoint {
    AFTER_CONTEXT_LOCK,
    BEFORE_MUTATION,
}

enum class GuestTableTeardownCheckpoint {
    AFTER_CONTEXT_LOCK,
}

enum class GuestTableActivationDeniedReason {
    INVALID_CONTEXT,
    TOKEN_UNAVAILABLE,
    TABLE_UNAVAILABLE,
    VENUE_UNAVAILABLE,
    SUBSCRIPTION_BLOCKED,
}

sealed class GuestTableActivationResult {
    data class Applied(
        val context: TableContext,
        val tableSession: TableSessionRecord,
    ) : GuestTableActivationResult()

    data class Denied(val reason: GuestTableActivationDeniedReason) : GuestTableActivationResult()

    object DatabaseUnavailable : GuestTableActivationResult()
}

data class GuestTableContextIdentity(
    val chatId: Long,
    val actorUserId: Long,
    val venueId: Long?,
    val tableId: Long?,
    val tableToken: String,
    val confirmedAt: Instant?,
)

sealed class GuestTableTeardownResult {
    data class Cleared(
        val identity: GuestTableContextIdentity,
        val tableSessionId: Long?,
        val exitRecorded: Boolean,
    ) : GuestTableTeardownResult()

    data class Blocked(
        val identity: GuestTableContextIdentity,
        val tableSessionId: Long,
        val reason: TableSessionEndBlockedReason,
    ) : GuestTableTeardownResult()

    object Missing : GuestTableTeardownResult()

    object Denied : GuestTableTeardownResult()

    object DatabaseUnavailable : GuestTableTeardownResult()
}

sealed class PlatformGuestTableResolveResult {
    data class Allowed(
        val context: TableContext,
        val tableSession: TableSessionRecord,
        val personalTab: GuestTabModel,
        val venueStatus: VenueStatus,
        val subscriptionStatus: SubscriptionStatus,
    ) : PlatformGuestTableResolveResult()

    object Denied : PlatformGuestTableResolveResult()

    object DatabaseUnavailable : PlatformGuestTableResolveResult()
}

sealed class PlatformGuestContextValidationResult {
    data class Active(
        val identity: GuestTableContextIdentity,
        val context: TableContext,
        val tableSession: TableSessionRecord,
        val venueStatus: VenueStatus,
        val subscriptionStatus: SubscriptionStatus,
    ) : PlatformGuestContextValidationResult()

    object Inactive : PlatformGuestContextValidationResult()

    object DatabaseUnavailable : PlatformGuestContextValidationResult()
}

data class ConfirmedPlatformGuestMutationContext(
    val identity: GuestTableContextIdentity,
    val context: TableContext,
    val tableSession: TableSessionRecord,
    val venueStatus: VenueStatus,
    val subscriptionStatus: SubscriptionStatus,
)

sealed class PlatformGuestTableMutationResult<out T> {
    data class Applied<T>(
        val context: ConfirmedPlatformGuestMutationContext,
        val value: T,
    ) : PlatformGuestTableMutationResult<T>()

    object Denied : PlatformGuestTableMutationResult<Nothing>()

    object DatabaseUnavailable : PlatformGuestTableMutationResult<Nothing>()
}

class GuestTableContextLifecycleRepository(
    private val dataSource: DataSource?,
    private val tableTokenRepository: TableTokenRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val tableSessionRepository: TableSessionRepository,
    private val guestTabsRepository: GuestTabsRepository,
    private val chatContextRepository: ChatContextRepository,
    private val dialogStateRepository: DialogStateRepository,
    private val activationCheckpoint: (GuestTableActivationCheckpoint) -> Unit = {},
    private val platformMutationCheckpoint: (PlatformGuestMutationCheckpoint) -> Unit = {},
    private val teardownCheckpoint: (GuestTableTeardownCheckpoint) -> Unit = {},
) {
    suspend fun activate(
        actorUserId: Long,
        chatId: Long,
        tableToken: String,
        expectedVenueId: Long,
        expectedTableId: Long,
        ttl: Duration,
        now: Instant = Instant.now(),
    ): GuestTableActivationResult =
        transaction(GuestTableActivationResult.DatabaseUnavailable) { connection ->
            chatContextRepository.getForUpdate(connection, chatId)
            val tableState =
                tableTokenRepository.resolveForUpdate(
                    connection = connection,
                    token = tableToken,
                    expectedVenueId = expectedVenueId,
                    expectedTableId = expectedTableId,
                ) ?: return@transaction GuestTableActivationResult.Denied(
                    GuestTableActivationDeniedReason.INVALID_CONTEXT,
                )
            if (!tableState.tokenActive) {
                return@transaction GuestTableActivationResult.Denied(
                    GuestTableActivationDeniedReason.TOKEN_UNAVAILABLE,
                )
            }
            if (!tableState.tableActive) {
                return@transaction GuestTableActivationResult.Denied(
                    GuestTableActivationDeniedReason.TABLE_UNAVAILABLE,
                )
            }
            if (tableState.venueStatus != VenueStatus.PUBLISHED) {
                return@transaction GuestTableActivationResult.Denied(
                    GuestTableActivationDeniedReason.VENUE_UNAVAILABLE,
                )
            }
            val subscriptionStatus =
                subscriptionRepository.getSubscriptionStatus(
                    connection = connection,
                    venueId = expectedVenueId,
                    forUpdate = true,
                )
            if (subscriptionStatus.isBlockedForGuest()) {
                return@transaction GuestTableActivationResult.Denied(
                    GuestTableActivationDeniedReason.SUBSCRIPTION_BLOCKED,
                )
            }
            val tableSession =
                tableSessionRepository.resolveActiveSession(
                    connection = connection,
                    venueId = expectedVenueId,
                    tableId = expectedTableId,
                    ttl = ttl,
                    now = now,
                )
            activationCheckpoint(GuestTableActivationCheckpoint.AFTER_SESSION)
            tableSessionRepository.clearUserExit(connection, actorUserId, tableSession.id)
            activationCheckpoint(GuestTableActivationCheckpoint.AFTER_EXIT_CLEAR)
            dialogStateRepository.clear(connection, chatId)
            activationCheckpoint(GuestTableActivationCheckpoint.AFTER_DIALOG_CLEAR)
            chatContextRepository.saveContext(
                connection = connection,
                chatId = chatId,
                userId = actorUserId,
                context = tableState.context,
                updatedAt = now,
            )
            activationCheckpoint(GuestTableActivationCheckpoint.AFTER_CONTEXT_SAVE)
            GuestTableActivationResult.Applied(
                context = tableState.context,
                tableSession = tableSession,
            )
        }

    suspend fun teardownByChat(
        actorUserId: Long,
        chatId: Long,
        now: Instant = Instant.now(),
    ): GuestTableTeardownResult =
        transaction(GuestTableTeardownResult.DatabaseUnavailable) { connection ->
            val stored =
                chatContextRepository.getForUpdate(connection, chatId, actorUserId)
                    ?: run {
                        dialogStateRepository.clear(connection, chatId)
                        return@transaction GuestTableTeardownResult.Missing
                    }
            teardownCheckpoint(GuestTableTeardownCheckpoint.AFTER_CONTEXT_LOCK)
            teardownLockedContext(
                connection = connection,
                stored = stored,
                actorUserId = actorUserId,
                expectedTableSessionId = null,
                now = now,
            )
        }

    suspend fun teardownByActorAndToken(
        actorUserId: Long,
        tableToken: String,
        expectedTableSessionId: Long? = null,
        now: Instant = Instant.now(),
    ): GuestTableTeardownResult =
        transaction(GuestTableTeardownResult.DatabaseUnavailable) { connection ->
            val matchingContexts =
                chatContextRepository.findActorTokenContextsForUpdate(
                    connection = connection,
                    userId = actorUserId,
                    tableToken = tableToken,
                )
            if (matchingContexts.isEmpty()) {
                return@transaction GuestTableTeardownResult.Missing
            }
            if (matchingContexts.size != 1) {
                return@transaction GuestTableTeardownResult.Denied
            }
            teardownCheckpoint(GuestTableTeardownCheckpoint.AFTER_CONTEXT_LOCK)
            teardownLockedContext(
                connection = connection,
                stored = matchingContexts.single(),
                actorUserId = actorUserId,
                expectedTableSessionId = expectedTableSessionId,
                now = now,
            )
        }

    suspend fun resolvePlatformMiniApp(
        actorUserId: Long,
        tableToken: String,
        expectedVenueId: Long,
        expectedTableId: Long,
        requestedTableSessionId: Long?,
        ttl: Duration,
        now: Instant = Instant.now(),
    ): PlatformGuestTableResolveResult =
        transaction(PlatformGuestTableResolveResult.DatabaseUnavailable) { connection ->
            resolvePlatformMiniAppLocked(
                connection = connection,
                actorUserId = actorUserId,
                tableToken = tableToken,
                expectedVenueId = expectedVenueId,
                expectedTableId = expectedTableId,
                requestedTableSessionId = requestedTableSessionId,
                ttl = ttl,
                now = now,
            )
        }

    suspend fun resolvePlatformMiniAppBySession(
        actorUserId: Long,
        expectedVenueId: Long,
        expectedTableId: Long,
        requestedTableSessionId: Long,
        ttl: Duration,
        now: Instant = Instant.now(),
    ): PlatformGuestTableResolveResult =
        transaction(PlatformGuestTableResolveResult.DatabaseUnavailable) { connection ->
            val matchingContexts =
                chatContextRepository.findActorTableContextsForUpdate(
                    connection = connection,
                    userId = actorUserId,
                    venueId = expectedVenueId,
                    tableId = expectedTableId,
                )
            if (matchingContexts.size != 1) {
                return@transaction PlatformGuestTableResolveResult.Denied
            }
            resolvePlatformMiniAppLocked(
                connection = connection,
                actorUserId = actorUserId,
                tableToken = matchingContexts.single().tableToken,
                expectedVenueId = expectedVenueId,
                expectedTableId = expectedTableId,
                requestedTableSessionId = requestedTableSessionId,
                ttl = ttl,
                now = now,
                lockedContext = matchingContexts.single(),
            )
        }

    suspend fun <T> withConfirmedPlatformGuestMutation(
        actorUserId: Long,
        platformOwnerUserId: Long?,
        chatId: Long? = null,
        tableToken: String? = null,
        expectedVenueId: Long? = null,
        expectedTableId: Long? = null,
        expectedSessionId: Long? = null,
        ttl: Duration,
        now: Instant = Instant.now(),
        mutation: (Connection, ConfirmedPlatformGuestMutationContext) -> T,
    ): PlatformGuestTableMutationResult<T> =
        mutationTransaction { connection ->
            if (platformOwnerUserId == null || actorUserId != platformOwnerUserId) {
                return@mutationTransaction PlatformGuestTableMutationResult.Denied
            }
            val matchingContexts =
                when {
                    chatId != null ->
                        listOfNotNull(
                            chatContextRepository.getForUpdate(
                                connection = connection,
                                chatId = chatId,
                                userId = actorUserId,
                            ),
                        )

                    tableToken != null ->
                        chatContextRepository.findActorTokenContextsForUpdate(
                            connection = connection,
                            userId = actorUserId,
                            tableToken = tableToken,
                            venueId = expectedVenueId,
                            tableId = expectedTableId,
                        )

                    expectedVenueId != null && expectedTableId != null ->
                        chatContextRepository.findActorTableContextsForUpdate(
                            connection = connection,
                            userId = actorUserId,
                            venueId = expectedVenueId,
                            tableId = expectedTableId,
                        )

                    else -> emptyList()
                }
            if (matchingContexts.size != 1) {
                return@mutationTransaction PlatformGuestTableMutationResult.Denied
            }
            platformMutationCheckpoint(PlatformGuestMutationCheckpoint.AFTER_CONTEXT_LOCK)
            val stored = matchingContexts.single()
            val storedChatId = stored.chatId ?: return@mutationTransaction PlatformGuestTableMutationResult.Denied
            val venueId = stored.venueId ?: return@mutationTransaction PlatformGuestTableMutationResult.Denied
            val tableId = stored.tableId ?: return@mutationTransaction PlatformGuestTableMutationResult.Denied
            val confirmedAt = stored.updatedAt ?: return@mutationTransaction PlatformGuestTableMutationResult.Denied
            if (
                stored.userId != actorUserId ||
                (chatId != null && storedChatId != chatId) ||
                (tableToken != null && stored.tableToken != tableToken) ||
                (expectedVenueId != null && venueId != expectedVenueId) ||
                (expectedTableId != null && tableId != expectedTableId)
            ) {
                return@mutationTransaction PlatformGuestTableMutationResult.Denied
            }
            val tableState =
                tableTokenRepository.resolveForUpdate(
                    connection = connection,
                    token = stored.tableToken,
                    expectedVenueId = venueId,
                    expectedTableId = tableId,
                )
                    ?: return@mutationTransaction PlatformGuestTableMutationResult.Denied
            if (
                tableState.context.venueId != venueId ||
                tableState.context.tableId != tableId ||
                !tableState.tokenActive ||
                !tableState.tableActive ||
                tableState.venueStatus != VenueStatus.PUBLISHED
            ) {
                return@mutationTransaction PlatformGuestTableMutationResult.Denied
            }
            val subscriptionStatus =
                subscriptionRepository.getSubscriptionStatus(
                    connection = connection,
                    venueId = venueId,
                    forUpdate = true,
                )
            if (subscriptionStatus.isBlockedForGuest()) {
                return@mutationTransaction PlatformGuestTableMutationResult.Denied
            }
            val tableSession =
                tableSessionRepository.findActiveSessionForContext(
                    connection = connection,
                    venueId = venueId,
                    tableId = tableId,
                    contextUpdatedAt = confirmedAt,
                    now = now,
                    forUpdate = true,
                ) ?: return@mutationTransaction PlatformGuestTableMutationResult.Denied
            if (expectedSessionId != null && tableSession.id != expectedSessionId) {
                return@mutationTransaction PlatformGuestTableMutationResult.Denied
            }
            if (tableSessionRepository.hasUserExit(connection, actorUserId, tableSession.id)) {
                return@mutationTransaction PlatformGuestTableMutationResult.Denied
            }
            val touchedSession =
                tableSessionRepository.touchActiveSession(
                    connection = connection,
                    tableSessionId = tableSession.id,
                    venueId = venueId,
                    tableId = tableId,
                    ttl = ttl,
                    now = now,
                ) ?: return@mutationTransaction PlatformGuestTableMutationResult.Denied
            val confirmedContext =
                ConfirmedPlatformGuestMutationContext(
                    identity = stored.toIdentity(),
                    context = tableState.context,
                    tableSession = touchedSession,
                    venueStatus = VenueStatus.PUBLISHED,
                    subscriptionStatus = subscriptionStatus,
                )
            platformMutationCheckpoint(PlatformGuestMutationCheckpoint.BEFORE_MUTATION)
            PlatformGuestTableMutationResult.Applied(
                context = confirmedContext,
                value = mutation(connection, confirmedContext),
            )
        }

    suspend fun validatePlatformBotContext(
        actorUserId: Long,
        chatId: Long,
        now: Instant = Instant.now(),
    ): PlatformGuestContextValidationResult =
        transaction(PlatformGuestContextValidationResult.DatabaseUnavailable) { connection ->
            val stored = chatContextRepository.get(connection, chatId)
            if (stored == null || stored.userId != actorUserId) {
                return@transaction PlatformGuestContextValidationResult.Inactive
            }
            val venueId = stored.venueId ?: return@transaction PlatformGuestContextValidationResult.Inactive
            val tableId = stored.tableId ?: return@transaction PlatformGuestContextValidationResult.Inactive
            val confirmedAt = stored.updatedAt ?: return@transaction PlatformGuestContextValidationResult.Inactive
            val tableState =
                tableTokenRepository.resolveActiveState(connection, stored.tableToken)
                    ?: return@transaction PlatformGuestContextValidationResult.Inactive
            if (
                tableState.context.venueId != venueId ||
                tableState.context.tableId != tableId ||
                !tableState.tokenActive ||
                !tableState.tableActive ||
                tableState.venueStatus != VenueStatus.PUBLISHED
            ) {
                return@transaction PlatformGuestContextValidationResult.Inactive
            }
            val subscriptionStatus = subscriptionRepository.getSubscriptionStatus(connection, venueId)
            if (subscriptionStatus.isBlockedForGuest()) {
                return@transaction PlatformGuestContextValidationResult.Inactive
            }
            val tableSession =
                tableSessionRepository.findActiveSessionForContext(
                    connection = connection,
                    venueId = venueId,
                    tableId = tableId,
                    contextUpdatedAt = confirmedAt,
                    now = now,
                    forUpdate = false,
                ) ?: return@transaction PlatformGuestContextValidationResult.Inactive
            if (tableSessionRepository.hasUserExit(connection, actorUserId, tableSession.id)) {
                return@transaction PlatformGuestContextValidationResult.Inactive
            }
            PlatformGuestContextValidationResult.Active(
                identity = stored.toIdentity(),
                context = tableState.context,
                tableSession = tableSession,
                venueStatus = VenueStatus.PUBLISHED,
                subscriptionStatus = subscriptionStatus,
            )
        }

    private fun teardownLockedContext(
        connection: Connection,
        stored: StoredChatContext,
        actorUserId: Long,
        expectedTableSessionId: Long?,
        now: Instant,
    ): GuestTableTeardownResult {
        val identity = stored.toIdentityOrNull() ?: return GuestTableTeardownResult.Denied
        val venueId = stored.venueId
        val tableId = stored.tableId
        val tableSession =
            if (venueId != null && tableId != null) {
                tableSessionRepository.findActiveUserSessionForTeardown(
                    connection = connection,
                    userId = actorUserId,
                    venueId = venueId,
                    tableId = tableId,
                    expectedTableSessionId = expectedTableSessionId,
                    now = now,
                )
            } else {
                null
            }
        if (
            expectedTableSessionId != null &&
            tableSession == null &&
            venueId != null &&
            tableId != null
        ) {
            val activeActorSession =
                tableSessionRepository.findActiveUserSessionForTeardown(
                    connection = connection,
                    userId = actorUserId,
                    venueId = venueId,
                    tableId = tableId,
                    expectedTableSessionId = null,
                    now = now,
                )
            val confirmedAt = stored.updatedAt
            if (activeActorSession != null && confirmedAt != null && activeActorSession.startedAt <= confirmedAt) {
                return GuestTableTeardownResult.Denied
            }
        }
        var exitRecorded = false
        if (
            tableSession != null &&
            venueId != null &&
            tableId != null &&
            tableSessionRepository.hasActiveUserTabMembership(connection, actorUserId, venueId, tableSession.id)
        ) {
            val blockedReason =
                when {
                    tableSessionRepository.hasActiveUserOrderObligation(
                        connection = connection,
                        userId = actorUserId,
                        venueId = venueId,
                        tableId = tableId,
                        tableSessionId = tableSession.id,
                    ) -> TableSessionEndBlockedReason.ACTIVE_ORDER

                    tableSessionRepository.hasActiveUserStaffCall(
                        connection = connection,
                        userId = actorUserId,
                        venueId = venueId,
                        tableId = tableId,
                        tableSessionId = tableSession.id,
                    ) -> TableSessionEndBlockedReason.ACTIVE_STAFF_CALL

                    else -> null
                }
            if (blockedReason != null) {
                return GuestTableTeardownResult.Blocked(
                    identity = identity,
                    tableSessionId = tableSession.id,
                    reason = blockedReason,
                )
            }
            tableSessionRepository.recordUserExit(connection, actorUserId, tableSession.id, now)
            exitRecorded = true
        }
        chatContextRepository.clear(connection, identity.chatId, actorUserId)
        dialogStateRepository.clear(connection, identity.chatId)
        return GuestTableTeardownResult.Cleared(
            identity = identity,
            tableSessionId = tableSession?.id,
            exitRecorded = exitRecorded,
        )
    }

    private fun resolvePlatformMiniAppLocked(
        connection: Connection,
        actorUserId: Long,
        tableToken: String,
        expectedVenueId: Long,
        expectedTableId: Long,
        requestedTableSessionId: Long?,
        ttl: Duration,
        now: Instant,
        lockedContext: StoredChatContext? = null,
    ): PlatformGuestTableResolveResult {
        val matchingContexts =
            if (lockedContext == null) {
                chatContextRepository.findActorTokenContextsForUpdate(
                    connection = connection,
                    userId = actorUserId,
                    tableToken = tableToken,
                    venueId = expectedVenueId,
                    tableId = expectedTableId,
                )
            } else {
                listOf(lockedContext)
            }
        if (matchingContexts.size != 1) {
            return PlatformGuestTableResolveResult.Denied
        }
        val stored = matchingContexts.single()
        if (
            stored.userId != actorUserId ||
            stored.tableToken != tableToken ||
            stored.venueId != expectedVenueId ||
            stored.tableId != expectedTableId
        ) {
            return PlatformGuestTableResolveResult.Denied
        }
        val confirmedAt = stored.updatedAt ?: return PlatformGuestTableResolveResult.Denied
        val tableState =
            tableTokenRepository.resolveForUpdate(
                connection = connection,
                token = tableToken,
                expectedVenueId = expectedVenueId,
                expectedTableId = expectedTableId,
            )
                ?: return PlatformGuestTableResolveResult.Denied
        if (
            tableState.context.venueId != expectedVenueId ||
            tableState.context.tableId != expectedTableId ||
            !tableState.tokenActive ||
            !tableState.tableActive ||
            tableState.venueStatus != VenueStatus.PUBLISHED
        ) {
            return PlatformGuestTableResolveResult.Denied
        }
        val subscriptionStatus =
            subscriptionRepository.getSubscriptionStatus(
                connection = connection,
                venueId = expectedVenueId,
                forUpdate = true,
            )
        if (subscriptionStatus.isBlockedForGuest()) {
            return PlatformGuestTableResolveResult.Denied
        }
        val tableSession =
            tableSessionRepository.findActiveSessionForContext(
                connection = connection,
                venueId = expectedVenueId,
                tableId = expectedTableId,
                contextUpdatedAt = confirmedAt,
                now = now,
                forUpdate = true,
            ) ?: return PlatformGuestTableResolveResult.Denied
        if (requestedTableSessionId != null && requestedTableSessionId != tableSession.id) {
            return PlatformGuestTableResolveResult.Denied
        }
        if (tableSessionRepository.hasUserExit(connection, actorUserId, tableSession.id)) {
            return PlatformGuestTableResolveResult.Denied
        }
        val touchedSession =
            tableSessionRepository.touchActiveSession(
                connection = connection,
                tableSessionId = tableSession.id,
                venueId = expectedVenueId,
                tableId = expectedTableId,
                ttl = ttl,
                now = now,
            ) ?: return PlatformGuestTableResolveResult.Denied
        val personalTab =
            guestTabsRepository.ensurePersonalTab(
                connection = connection,
                venueId = expectedVenueId,
                tableSessionId = touchedSession.id,
                userId = actorUserId,
            )
        return PlatformGuestTableResolveResult.Allowed(
            context = tableState.context,
            tableSession = touchedSession,
            personalTab = personalTab,
            venueStatus = VenueStatus.PUBLISHED,
            subscriptionStatus = subscriptionStatus,
        )
    }

    private suspend fun <T> transaction(
        databaseFailure: T,
        block: (Connection) -> T,
    ): T {
        val ds = dataSource ?: return databaseFailure
        return withContext(Dispatchers.IO) {
            val connection =
                try {
                    ds.connection
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@withContext databaseFailure
                }
            try {
                connection.autoCommit = false
                try {
                    val result = block(connection)
                    connection.commit()
                    result
                } catch (e: CancellationException) {
                    runCatching { connection.rollback() }
                    throw e
                } catch (_: Exception) {
                    runCatching { connection.rollback() }
                    databaseFailure
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                runCatching { connection.rollback() }
                databaseFailure
            } finally {
                runCatching { connection.close() }
            }
        }
    }

    private suspend fun <T> mutationTransaction(
        block: (Connection) -> PlatformGuestTableMutationResult<T>,
    ): PlatformGuestTableMutationResult<T> {
        val ds = dataSource ?: return PlatformGuestTableMutationResult.DatabaseUnavailable
        return withContext(Dispatchers.IO) {
            val connection =
                try {
                    ds.connection
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@withContext PlatformGuestTableMutationResult.DatabaseUnavailable
                }
            try {
                connection.autoCommit = false
                try {
                    val result = block(connection)
                    connection.commit()
                    result
                } catch (e: CancellationException) {
                    runCatching { connection.rollback() }
                    throw e
                } catch (_: java.sql.SQLException) {
                    runCatching { connection.rollback() }
                    PlatformGuestTableMutationResult.DatabaseUnavailable
                } catch (e: Exception) {
                    runCatching { connection.rollback() }
                    throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: java.sql.SQLException) {
                runCatching { connection.rollback() }
                PlatformGuestTableMutationResult.DatabaseUnavailable
            } finally {
                runCatching { connection.close() }
            }
        }
    }

    private fun StoredChatContext.toIdentity(): GuestTableContextIdentity = checkNotNull(toIdentityOrNull())

    private fun StoredChatContext.toIdentityOrNull(): GuestTableContextIdentity? {
        val storedChatId = chatId ?: return null
        return GuestTableContextIdentity(
            chatId = storedChatId,
            actorUserId = userId,
            venueId = venueId,
            tableId = tableId,
            tableToken = tableToken,
            confirmedAt = updatedAt,
        )
    }
}
