package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.telegram.TableContext
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.StoredChatContext
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import com.hookah.platform.backend.telegram.db.TableTokenState
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

data class MiniAppFallbackVisit(val tableSessionId: Long?)

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
        val personalTab: GuestTabModel?,
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
        expectedExitEventId: Long? = null,
        ordinaryBot: Boolean = false,
        fallback: MiniAppFallbackVisit? = null,
    ): GuestTableActivationResult =
        transaction(GuestTableActivationResult.DatabaseUnavailable) { connection ->
            if (!chatContextRepository.lockActorForBinding(connection, actorUserId)) {
                return@transaction GuestTableActivationResult.Denied(GuestTableActivationDeniedReason.INVALID_CONTEXT)
            }
            val existing = chatContextRepository.lockBinding(connection, chatId)
            if (existing != null && existing.userId != actorUserId) {
                return@transaction GuestTableActivationResult.Denied(GuestTableActivationDeniedReason.INVALID_CONTEXT)
            }
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
            val venueAvailable =
                canEnterBotVenue(connection, actorUserId, tableState, ordinaryBot, lockMember = true)
            if (!venueAvailable) {
                return@transaction GuestTableActivationResult.Denied(
                    GuestTableActivationDeniedReason.VENUE_UNAVAILABLE,
                )
            }
            val sessionId =
                tableSessionRepository.findActiveSessionForUpdate(connection, expectedVenueId, expectedTableId, now)
            val staleConfirmation = GuestTableActivationResult.Denied(GuestTableActivationDeniedReason.INVALID_CONTEXT)
            if (fallback != null) {
                if (sessionId == null || expectedExitEventId != null ||
                    (fallback.tableSessionId != null && fallback.tableSessionId != sessionId)
                ) {
                    return@transaction staleConfirmation
                }
                val authorized =
                    if (existing != null) {
                        val bound = existing.context
                        bound != null && bound.tableSessionId == sessionId && bound.venueId == expectedVenueId &&
                            bound.tableId == expectedTableId && bound.tableToken == tableToken
                    } else {
                        fallback.tableSessionId == sessionId &&
                            tableSessionRepository.hasActiveUserTabMembership(
                                connection, actorUserId, expectedVenueId, sessionId, forUpdate = true,
                            )
                    }
                if (!authorized) return@transaction staleConfirmation
            }
            if (expectedExitEventId == null) {
                val exited = sessionId != null && tableSessionRepository.hasUserExit(connection, actorUserId, sessionId)
                if (exited) {
                    return@transaction staleConfirmation
                }
            } else {
                if (sessionId == null) return@transaction staleConfirmation
                if (ordinaryBot &&
                    !ordinaryRejoinBound(
                        connection, actorUserId, chatId, expectedVenueId, expectedTableId, sessionId,
                        expectedExitEventId,
                    )
                ) {
                    return@transaction staleConfirmation
                }
                if (tableSessionRepository.findExitEventId(connection, actorUserId, sessionId) != expectedExitEventId) {
                    return@transaction staleConfirmation
                }
            }
            var subscriptionStatus =
                subscriptionRepository.getSubscriptionStatus(
                    connection = connection,
                    venueId = expectedVenueId,
                    forUpdate = true,
                )
            if (fallback == null && expectedExitEventId == null && subscriptionStatus == SubscriptionStatus.UNKNOWN) {
                subscriptionRepository.ensureRowExistsForVenue(connection, expectedVenueId)
                subscriptionStatus = subscriptionRepository.getSubscriptionStatus(connection, expectedVenueId)
            }
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
            if (expectedExitEventId != null &&
                !tableSessionRepository.consumeExitEvent(
                    connection, actorUserId, tableSession.id, expectedExitEventId,
                )
            ) {
                connection.rollback()
                return@transaction staleConfirmation
            }
            activationCheckpoint(GuestTableActivationCheckpoint.AFTER_EXIT_CLEAR)
            dialogStateRepository.clear(connection, chatId)
            activationCheckpoint(GuestTableActivationCheckpoint.AFTER_DIALOG_CLEAR)
            chatContextRepository.saveContext(
                connection = connection,
                chatId = chatId,
                userId = actorUserId,
                context = tableState.context,
                updatedAt = now,
                tableSessionId = tableSession.id,
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
        ordinaryBotVisit: Boolean = false,
    ): GuestTableTeardownResult =
        transaction(GuestTableTeardownResult.DatabaseUnavailable) { connection ->
            val stored =
                chatContextRepository.getForUpdate(connection, chatId, userId = null)
                    ?: run {
                        dialogStateRepository.clear(connection, chatId)
                        return@transaction GuestTableTeardownResult.Missing
                    }
            if (stored.userId != actorUserId) return@transaction GuestTableTeardownResult.Denied
            teardownCheckpoint(GuestTableTeardownCheckpoint.AFTER_CONTEXT_LOCK)
            teardownLockedContext(
                connection = connection,
                stored = stored,
                actorUserId = actorUserId,
                expectedTableSessionId = null,
                now = now,
                retainBotChatForRejoin = ordinaryBotVisit,
            )
        }

    suspend fun teardownByActorAndToken(
        actorUserId: Long,
        tableToken: String,
        expectedTableSessionId: Long? = null,
        now: Instant = Instant.now(),
        ordinaryBotVisit: Boolean = false,
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
            val stored = matchingContexts.single()
            if (ordinaryBotVisit) {
                val visit = validBotVisit(connection, actorUserId, stored, now, allowMember = false, forUpdate = true)
                if (visit == null || visit.tableSession.id != expectedTableSessionId) {
                    return@transaction GuestTableTeardownResult.Denied
                }
            }
            teardownCheckpoint(GuestTableTeardownCheckpoint.AFTER_CONTEXT_LOCK)
            teardownLockedContext(
                connection = connection,
                stored = stored,
                actorUserId = actorUserId,
                expectedTableSessionId = expectedTableSessionId,
                now = now,
                retainBotChatForRejoin = ordinaryBotVisit,
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
        verifyOnly: Boolean = false,
        createPersonalTab: Boolean = false,
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
                verifyOnly = verifyOnly,
                createPersonalTab = createPersonalTab,
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
        touchSessionBeforeMutation: Boolean = true,
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
            val boundId = stored.tableSessionId ?: return@mutationTransaction PlatformGuestTableMutationResult.Denied
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
                    tableSessionId = boundId,
                    now = now,
                    forUpdate = true,
                ) ?: return@mutationTransaction PlatformGuestTableMutationResult.Denied
            if (expectedSessionId != null && tableSession.id != expectedSessionId) {
                return@mutationTransaction PlatformGuestTableMutationResult.Denied
            }
            if (tableSessionRepository.hasUserExit(connection, actorUserId, tableSession.id)) {
                return@mutationTransaction PlatformGuestTableMutationResult.Denied
            }
            val mutationSession =
                if (touchSessionBeforeMutation) {
                    tableSessionRepository.touchActiveSession(
                        connection = connection,
                        tableSessionId = tableSession.id,
                        venueId = venueId,
                        tableId = tableId,
                        ttl = ttl,
                        now = now,
                    ) ?: return@mutationTransaction PlatformGuestTableMutationResult.Denied
                } else {
                    tableSession
                }
            val confirmedContext =
                ConfirmedPlatformGuestMutationContext(
                    identity = stored.toIdentity(),
                    context = tableState.context,
                    tableSession = mutationSession,
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
            validBotVisit(connection, actorUserId, stored, now, allowMember = false)
                ?: PlatformGuestContextValidationResult.Inactive
        }

    suspend fun ordinaryBotVisit(
        actorUserId: Long,
        chatId: Long,
        expectedTable: TableContext? = null,
        expectedSessionId: Long? = null,
        activityTtl: Duration? = null,
    ): PlatformGuestContextValidationResult =
        transaction(PlatformGuestContextValidationResult.DatabaseUnavailable) { connection ->
            val stored =
                if (activityTtl == null) {
                    chatContextRepository.get(connection, chatId)
                } else {
                    chatContextRepository.getForUpdate(connection, chatId)
                }
            if (stored == null || stored.userId != actorUserId ||
                (
                    expectedTable != null && (
                        expectedSessionId == null || stored.tableSessionId != expectedSessionId ||
                            stored.venueId != expectedTable.venueId || stored.tableId != expectedTable.tableId ||
                            stored.tableToken != expectedTable.tableToken
                    )
                )
            ) {
                return@transaction PlatformGuestContextValidationResult.Inactive
            }
            val visit =
                validBotVisit(
                    connection, actorUserId, stored, Instant.now(),
                    allowMember = true,
                    forUpdate = activityTtl != null,
                )
                    ?: return@transaction PlatformGuestContextValidationResult.Inactive
            if (activityTtl == null) return@transaction visit
            val refreshed =
                tableSessionRepository.touchActiveSession(
                    connection = connection,
                    tableSessionId = visit.tableSession.id,
                    venueId = visit.context.venueId,
                    tableId = visit.context.tableId,
                    ttl = activityTtl,
                    now = Instant.now(),
                ) ?: return@transaction PlatformGuestContextValidationResult.Inactive
            visit.copy(tableSession = refreshed)
        }

    fun findExitedBotChat(
        connection: Connection,
        userId: Long,
        table: TableContext,
        sessionId: Long,
        eventId: Long,
        forUpdate: Boolean,
    ): Long? =
        chatContextRepository.findExitedBotChat(
            connection,
            userId,
            table.venueId,
            table.tableId,
            sessionId,
            eventId,
            forUpdate,
        )

    fun resumeBotChat(
        connection: Connection,
        chatId: Long,
        userId: Long,
        table: TableContext,
        tableSessionId: Long,
        now: Instant,
    ) {
        chatContextRepository.saveContext(connection, chatId, userId, table, now, tableSessionId)
    }

    fun lockBotVisitForPersonalTab(
        connection: Connection,
        userId: Long,
        session: TableSessionContext,
    ): Boolean {
        val stored =
            chatContextRepository.findActorTableContextsForUpdate(
                connection, userId, session.venueId, session.tableId,
            ).singleOrNull() ?: return false
        val visit = validBotVisit(connection, userId, stored, Instant.now(), allowMember = false, forUpdate = true)
        return visit?.tableSession?.id == session.id
    }

    suspend fun ordinaryBotCanRejoin(
        actor: Long,
        chatId: Long,
        venueId: Long,
        tableId: Long,
        event: Long,
    ): Boolean? =
        transaction<Boolean?>(null) { connection ->
            chatContextRepository.getForUpdate(connection, chatId)
            val session =
                tableSessionRepository.findActiveSessionForUpdate(connection, venueId, tableId, Instant.now())
                    ?: return@transaction false
            tableSessionRepository.findExitEventId(connection, actor, session) == event &&
                ordinaryRejoinBound(connection, actor, chatId, venueId, tableId, session, event)
        }

    private fun ordinaryRejoinBound(
        connection: Connection,
        actor: Long,
        chatId: Long,
        venueId: Long,
        tableId: Long,
        session: Long,
        event: Long,
    ): Boolean =
        tableSessionRepository.hasActiveUserTabMembership(connection, actor, venueId, session, forUpdate = true) ||
            chatContextRepository.findExitedBotChat(
                connection, actor, venueId, tableId, session, event, forUpdate = true,
            ) == chatId

    fun hasExactBotVisit(
        connection: Connection,
        userId: Long,
        table: TableContext,
        sessionId: Long,
        now: Instant,
    ): Boolean =
        chatContextRepository.findActorContexts(connection, userId).any { stored ->
            stored.tableToken == table.tableToken &&
                validBotVisit(connection, userId, stored, now, allowMember = false)?.tableSession?.id == sessionId
        }

    suspend fun activeBotVisit(
        actorUserId: Long,
        allowMember: Boolean,
        expectedToken: String? = null,
        expectedSession: Long? = null,
    ): PlatformGuestContextValidationResult =
        transaction(PlatformGuestContextValidationResult.DatabaseUnavailable) { connection ->
            val now = Instant.now()
            chatContextRepository.findActorContexts(connection, actorUserId).firstNotNullOfOrNull { stored ->
                if (expectedToken != null && stored.tableToken != expectedToken) return@firstNotNullOfOrNull null
                validBotVisit(connection, actorUserId, stored, now, allowMember)
                    ?.takeIf { expectedSession == null || it.tableSession.id == expectedSession }
            } ?: PlatformGuestContextValidationResult.Inactive
        }

    private fun canEnterBotVenue(
        connection: Connection,
        actorUserId: Long,
        state: TableTokenState,
        allowMember: Boolean,
        lockMember: Boolean = false,
    ): Boolean {
        if (state.venueStatus == VenueStatus.PUBLISHED) return true
        if (!allowMember || state.venueStatus == null || state.venueStatus == VenueStatus.DELETED) return false
        val lock = if (lockMember) " FOR UPDATE" else ""
        return connection.prepareStatement("SELECT 1 FROM venue_members WHERE user_id = ? AND venue_id = ?$lock").use {
            it.setLong(1, actorUserId)
            it.setLong(2, state.context.venueId)
            it.executeQuery().use { rows -> rows.next() }
        }
    }

    private fun validBotVisit(
        connection: Connection,
        actorUserId: Long,
        stored: StoredChatContext,
        now: Instant,
        allowMember: Boolean,
        forUpdate: Boolean = false,
    ): PlatformGuestContextValidationResult.Active? {
        val venueId = stored.venueId ?: return null
        val tableId = stored.tableId ?: return null
        val boundId = stored.tableSessionId ?: return null
        val tableState =
            if (forUpdate) {
                tableTokenRepository.resolveForUpdate(connection, stored.tableToken, venueId, tableId)
            } else {
                tableTokenRepository.resolveActiveState(connection, stored.tableToken)
            } ?: return null
        if (
            tableState.context.venueId != venueId ||
            tableState.context.tableId != tableId ||
            !tableState.tokenActive ||
            !tableState.tableActive ||
            !canEnterBotVenue(connection, actorUserId, tableState, allowMember, lockMember = forUpdate)
        ) {
            return null
        }
        val subscriptionStatus = subscriptionRepository.getSubscriptionStatus(connection, venueId, forUpdate)
        if (subscriptionStatus.isBlockedForGuest()) {
            return null
        }
        val tableSession =
            tableSessionRepository.findActiveSessionForContext(
                connection = connection,
                venueId = venueId,
                tableId = tableId,
                tableSessionId = boundId,
                now = now,
                forUpdate = forUpdate,
            ) ?: return null
        if (tableSessionRepository.hasUserExit(connection, actorUserId, tableSession.id)) {
            return null
        }
        return PlatformGuestContextValidationResult.Active(
            identity = stored.toIdentity(),
            context = tableState.context,
            tableSession = tableSession,
            venueStatus = checkNotNull(tableState.venueStatus),
            subscriptionStatus = subscriptionStatus,
        )
    }

    private fun teardownLockedContext(
        connection: Connection,
        stored: StoredChatContext,
        actorUserId: Long,
        expectedTableSessionId: Long?,
        now: Instant,
        retainBotChatForRejoin: Boolean = false,
    ): GuestTableTeardownResult {
        val identity = stored.toIdentityOrNull() ?: return GuestTableTeardownResult.Denied
        val venueId = stored.venueId
        val tableId = stored.tableId
        val boundId = stored.tableSessionId
        if (expectedTableSessionId != null && boundId != null && boundId != expectedTableSessionId) {
            return GuestTableTeardownResult.Denied
        }
        val memberSession =
            if (boundId != null && venueId != null && tableId != null) {
                tableSessionRepository.findActiveUserSessionForTeardown(
                    connection = connection,
                    userId = actorUserId,
                    venueId = venueId,
                    tableId = tableId,
                    expectedTableSessionId = boundId,
                    now = now,
                )
            } else {
                null
            }
        val botSession =
            if (memberSession == null && venueId != null && tableId != null && boundId != null) {
                val table = tableTokenRepository.resolveForUpdate(connection, stored.tableToken, venueId, tableId)
                if (table == null) {
                    null
                } else {
                    tableSessionRepository.findActiveSessionForContext(
                        connection,
                        venueId,
                        tableId,
                        boundId,
                        now,
                        forUpdate = true,
                    )
                }
            } else {
                null
            }
        val tableSession = memberSession ?: botSession
        var exitRecorded = false
        if (tableSession != null && venueId != null && tableId != null) {
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
        if (botSession != null && retainBotChatForRejoin) {
            chatContextRepository.detachTableToken(connection, identity.chatId, actorUserId, now)
        } else {
            chatContextRepository.clear(connection, identity.chatId, actorUserId)
        }
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
        verifyOnly: Boolean = false,
        createPersonalTab: Boolean = false,
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
        val boundId = stored.tableSessionId ?: return PlatformGuestTableResolveResult.Denied
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
                tableSessionId = boundId,
                now = now,
                forUpdate = true,
            ) ?: return PlatformGuestTableResolveResult.Denied
        if (requestedTableSessionId != null && requestedTableSessionId != tableSession.id) {
            return PlatformGuestTableResolveResult.Denied
        }
        if (tableSessionRepository.hasUserExit(connection, actorUserId, tableSession.id)) {
            return PlatformGuestTableResolveResult.Denied
        }
        val session =
            if (verifyOnly) {
                tableSession
            } else {
                tableSessionRepository.touchActiveSession(
                    connection = connection,
                    tableSessionId = tableSession.id,
                    venueId = expectedVenueId,
                    tableId = expectedTableId,
                    ttl = ttl,
                    now = now,
                ) ?: return PlatformGuestTableResolveResult.Denied
            }
        val personalTab =
            if (createPersonalTab && !verifyOnly) {
                guestTabsRepository.ensurePersonalTab(connection, expectedVenueId, session.id, actorUserId)
            } else {
                guestTabsRepository.findPersonalTabForUpdate(connection, expectedVenueId, session.id, actorUserId)
            }
        return PlatformGuestTableResolveResult.Allowed(
            context = tableState.context,
            tableSession = session,
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
