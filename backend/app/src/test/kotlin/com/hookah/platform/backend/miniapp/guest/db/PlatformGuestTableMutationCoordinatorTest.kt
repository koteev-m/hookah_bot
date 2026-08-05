package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.miniapp.shift.CreateShiftExtensionRequestCommand
import com.hookah.platform.backend.miniapp.shift.ShiftExtensionRepository
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.support.SupportAssigneeScope
import com.hookah.platform.backend.support.SupportMessageSource
import com.hookah.platform.backend.support.SupportThreadCategory
import com.hookah.platform.backend.support.SupportThreadCreatedSource
import com.hookah.platform.backend.support.SupportThreadRepository
import com.hookah.platform.backend.support.SupportTicketCreateInput
import com.hookah.platform.backend.telegram.StaffCallReason
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlatformGuestTableMutationCoordinatorTest {
    @ParameterizedTest(name = "{0} production overload commits inside the confirmed transaction")
    @ValueSource(strings = ["ORDER", "STAFF_CALL", "TAB", "SHIFT_EXTENSION", "SUPPORT"])
    fun `every table-bound mutation family applies through its production connection overload`(familyName: String) =
        runBlocking {
            val family = AuthoritativeMutationFamily.valueOf(familyName)
            val fixture = createFixture("platform-mutation-family-applies-${family.name.lowercase()}")
            family.prepare(fixture)
            val beforeCounts = family.expectedCreatedTables.associateWith(fixture.database::countRows)

            val result =
                fixture.database.lifecycleRepository().withConfirmedPlatformGuestMutation(
                    actorUserId = fixture.database.actorUserId,
                    platformOwnerUserId = fixture.database.actorUserId,
                    tableToken = fixture.database.tableToken,
                    expectedVenueId = fixture.database.venueId,
                    expectedTableId = fixture.database.tableId,
                    expectedSessionId = fixture.tableSessionId,
                    ttl = Duration.ofHours(4),
                    now = fixture.now,
                ) { connection, confirmed ->
                    family.mutate(connection, confirmed, fixture)
                }

            assertIs<PlatformGuestTableMutationResult.Applied<*>>(result)
            family.expectedCreatedTables.forEach { table ->
                assertEquals(beforeCounts.getValue(table) + 1, fixture.database.countRows(table), "$family/$table")
            }
        }

    @ParameterizedTest(name = "{0} mutation loses to exit without an authoritative write")
    @ValueSource(strings = ["ORDER", "STAFF_CALL", "TAB", "SHIFT_EXTENSION", "SUPPORT"])
    fun `every table-bound mutation family fails closed when exit owns the context lock`(familyName: String) =
        runBlocking {
            val family = AuthoritativeMutationFamily.valueOf(familyName)
            val fixture = createFixture("platform-mutation-family-exit-wins-${family.name.lowercase()}")
            val exitLocked = CountDownLatch(1)
            val allowExit = CountDownLatch(1)
            val mutationStarted = CountDownLatch(1)
            val callbackInvoked = AtomicBoolean(false)
            val exitRepository =
                fixture.database.lifecycleRepository(
                    teardownCheckpoint = {
                        exitLocked.countDown()
                        check(allowExit.await(10, TimeUnit.SECONDS))
                    },
                )
            val mutationRepository = fixture.database.lifecycleRepository()
            val beforeCounts = family.expectedCreatedTables.associateWith(fixture.database::countRows)

            val exit =
                async(Dispatchers.IO) {
                    exitRepository.teardownByActorAndToken(
                        actorUserId = fixture.database.actorUserId,
                        tableToken = fixture.database.tableToken,
                        expectedTableSessionId = fixture.tableSessionId,
                        now = fixture.now,
                    )
                }
            check(exitLocked.await(10, TimeUnit.SECONDS))
            val mutation =
                async(Dispatchers.IO) {
                    mutationStarted.countDown()
                    mutationRepository.withConfirmedPlatformGuestMutation(
                        actorUserId = fixture.database.actorUserId,
                        platformOwnerUserId = fixture.database.actorUserId,
                        tableToken = fixture.database.tableToken,
                        expectedVenueId = fixture.database.venueId,
                        expectedTableId = fixture.database.tableId,
                        expectedSessionId = fixture.tableSessionId,
                        ttl = Duration.ofHours(4),
                        now = fixture.now,
                    ) { connection, confirmed ->
                        callbackInvoked.set(true)
                        family.mutate(connection, confirmed, fixture)
                    }
                }
            check(mutationStarted.await(10, TimeUnit.SECONDS))
            allowExit.countDown()

            assertIs<GuestTableTeardownResult.Cleared>(withTimeout(10_000) { exit.await() })
            assertEquals(
                PlatformGuestTableMutationResult.Denied,
                withTimeout(10_000) { mutation.await() },
            )
            assertEquals(false, callbackInvoked.get(), family.name)
            assertEquals(
                beforeCounts,
                family.expectedCreatedTables.associateWith(fixture.database::countRows),
                family.name,
            )
            assertEquals(fixture.sessionBefore, fixture.database.sessionSnapshot(fixture.tableSessionId))
        }

    @Test
    fun `exit lock winner removes context before mutation and mutation fails closed`() =
        runBlocking {
            val fixture = createFixture("platform-mutation-exit-wins")
            val exitLocked = CountDownLatch(1)
            val allowExit = CountDownLatch(1)
            val mutationStarted = CountDownLatch(1)
            val exitRepository =
                fixture.database.lifecycleRepository(
                    teardownCheckpoint = {
                        exitLocked.countDown()
                        check(allowExit.await(10, TimeUnit.SECONDS))
                    },
                )
            val mutationRepository = fixture.database.lifecycleRepository()
            val staffCallRepository = StaffCallRepository(fixture.database.dataSource)

            val exit =
                async(Dispatchers.IO) {
                    exitRepository.teardownByActorAndToken(
                        actorUserId = fixture.database.actorUserId,
                        tableToken = fixture.database.tableToken,
                        expectedTableSessionId = fixture.tableSessionId,
                        now = fixture.now,
                    )
                }
            check(exitLocked.await(10, TimeUnit.SECONDS))
            val mutation =
                async(Dispatchers.IO) {
                    mutationStarted.countDown()
                    mutationRepository.withConfirmedPlatformGuestMutation(
                        actorUserId = fixture.database.actorUserId,
                        platformOwnerUserId = fixture.database.actorUserId,
                        tableToken = fixture.database.tableToken,
                        expectedVenueId = fixture.database.venueId,
                        expectedTableId = fixture.database.tableId,
                        expectedSessionId = fixture.tableSessionId,
                        ttl = Duration.ofHours(4),
                        now = fixture.now,
                    ) { connection, confirmed ->
                        staffCallRepository.createGuestStaffCall(
                            connection = connection,
                            venueId = fixture.database.venueId,
                            tableId = fixture.database.tableId,
                            tableSessionId = confirmed.tableSession.id,
                            createdByUserId = fixture.database.actorUserId,
                            reason = StaffCallReason.COME,
                            comment = null,
                        )
                    }
                }
            check(mutationStarted.await(10, TimeUnit.SECONDS))
            allowExit.countDown()

            assertIs<GuestTableTeardownResult.Cleared>(withTimeout(10_000) { exit.await() })
            assertEquals(
                PlatformGuestTableMutationResult.Denied,
                withTimeout(10_000) { mutation.await() },
            )
            assertEquals(0, fixture.database.countRows("staff_calls"))
            assertEquals(fixture.sessionBefore, fixture.database.sessionSnapshot(fixture.tableSessionId))
        }

    @Test
    fun `mutation lock winner commits before exit and later mutation is denied`() =
        runBlocking {
            val fixture = createFixture("platform-mutation-write-wins")
            val mutationLocked = CountDownLatch(1)
            val allowMutation = CountDownLatch(1)
            val exitStarted = CountDownLatch(1)
            val mutationRepository =
                fixture.database.lifecycleRepository(
                    platformMutationCheckpoint = { checkpoint ->
                        if (checkpoint == PlatformGuestMutationCheckpoint.AFTER_CONTEXT_LOCK) {
                            mutationLocked.countDown()
                            check(allowMutation.await(10, TimeUnit.SECONDS))
                        }
                    },
                )
            val exitRepository = fixture.database.lifecycleRepository()
            val staffCallRepository = StaffCallRepository(fixture.database.dataSource)

            val mutation =
                async(Dispatchers.IO) {
                    mutationRepository.withConfirmedPlatformGuestMutation(
                        actorUserId = fixture.database.actorUserId,
                        platformOwnerUserId = fixture.database.actorUserId,
                        tableToken = fixture.database.tableToken,
                        expectedVenueId = fixture.database.venueId,
                        expectedTableId = fixture.database.tableId,
                        expectedSessionId = fixture.tableSessionId,
                        ttl = Duration.ofHours(4),
                        now = fixture.now,
                    ) { connection, confirmed ->
                        staffCallRepository.createGuestStaffCall(
                            connection = connection,
                            venueId = fixture.database.venueId,
                            tableId = fixture.database.tableId,
                            tableSessionId = confirmed.tableSession.id,
                            createdByUserId = fixture.database.actorUserId,
                            reason = StaffCallReason.COME,
                            comment = null,
                        )
                    }
                }
            check(mutationLocked.await(10, TimeUnit.SECONDS))
            val exit =
                async(Dispatchers.IO) {
                    exitStarted.countDown()
                    exitRepository.teardownByActorAndToken(
                        actorUserId = fixture.database.actorUserId,
                        tableToken = fixture.database.tableToken,
                        expectedTableSessionId = fixture.tableSessionId,
                        now = fixture.now.plusSeconds(1),
                    )
                }
            check(exitStarted.await(10, TimeUnit.SECONDS))
            allowMutation.countDown()

            assertIs<PlatformGuestTableMutationResult.Applied<*>>(withTimeout(10_000) { mutation.await() })
            assertIs<GuestTableTeardownResult.Cleared>(withTimeout(10_000) { exit.await() })
            val retry =
                mutationRepository.withConfirmedPlatformGuestMutation(
                    actorUserId = fixture.database.actorUserId,
                    platformOwnerUserId = fixture.database.actorUserId,
                    tableToken = fixture.database.tableToken,
                    expectedVenueId = fixture.database.venueId,
                    expectedTableId = fixture.database.tableId,
                    expectedSessionId = fixture.tableSessionId,
                    ttl = Duration.ofHours(4),
                    now = fixture.now.plusSeconds(2),
                ) { connection, confirmed ->
                    staffCallRepository.createGuestStaffCall(
                        connection = connection,
                        venueId = fixture.database.venueId,
                        tableId = fixture.database.tableId,
                        tableSessionId = confirmed.tableSession.id,
                        createdByUserId = fixture.database.actorUserId,
                        reason = StaffCallReason.COME,
                        comment = null,
                    )
                }
            assertEquals(PlatformGuestTableMutationResult.Denied, retry)
            assertEquals(1, fixture.database.countRows("staff_calls"))
        }

    @Test
    fun `mutation SQL failure rolls back domain row and session touch`() =
        runBlocking {
            val fixture = createFixture("platform-mutation-rollback")
            val staffCallRepository = StaffCallRepository(fixture.database.dataSource)

            val result =
                fixture.database.lifecycleRepository().withConfirmedPlatformGuestMutation(
                    actorUserId = fixture.database.actorUserId,
                    platformOwnerUserId = fixture.database.actorUserId,
                    tableToken = fixture.database.tableToken,
                    expectedVenueId = fixture.database.venueId,
                    expectedTableId = fixture.database.tableId,
                    expectedSessionId = fixture.tableSessionId,
                    ttl = Duration.ofHours(4),
                    now = fixture.now,
                ) { connection, confirmed ->
                    staffCallRepository.createGuestStaffCall(
                        connection = connection,
                        venueId = fixture.database.venueId,
                        tableId = fixture.database.tableId,
                        tableSessionId = confirmed.tableSession.id,
                        createdByUserId = fixture.database.actorUserId,
                        reason = StaffCallReason.COME,
                        comment = null,
                    )
                    throw SQLException("injected mutation failure")
                }

            assertEquals(PlatformGuestTableMutationResult.DatabaseUnavailable, result)
            assertEquals(0, fixture.database.countRows("staff_calls"))
            assertEquals(fixture.sessionBefore, fixture.database.sessionSnapshot(fixture.tableSessionId))
        }

    @Test
    fun `shift extension rejects stale pre-read after actor tab membership is removed before final transaction`() =
        runBlocking {
            val fixture = createFixture("platform-shift-extension-membership-race")
            AuthoritativeMutationFamily.SHIFT_EXTENSION.prepare(fixture)
            val tabsRepository = GuestTabsRepository(fixture.database.dataSource)
            val ordersRepository = OrdersRepository(fixture.database.dataSource)
            val shiftExtensionRepository = ShiftExtensionRepository(fixture.database.dataSource)
            val tab =
                tabsRepository.ensurePersonalTab(
                    venueId = fixture.database.venueId,
                    tableSessionId = fixture.tableSessionId,
                    userId = fixture.database.actorUserId,
                )
            val orderId =
                checkNotNull(
                    ordersRepository.getOrCreateActiveOrderId(
                        tableId = fixture.database.tableId,
                        venueId = fixture.database.venueId,
                        tableSessionId = fixture.tableSessionId,
                        venueZoneId = ZoneId.of("Europe/Moscow"),
                    ),
                )
            checkNotNull(
                ordersRepository.createOrderBatch(
                    orderId = orderId,
                    authorUserId = fixture.database.actorUserId,
                    guestComment = "stale Telegram pre-read",
                    tabId = tab.id,
                ),
            )

            val preloadedOrder =
                checkNotNull(
                    ordersRepository.findActiveOrderSummaryForTab(
                        tableSessionId = fixture.tableSessionId,
                        tabId = tab.id,
                    ),
                )
            assertEquals(orderId, preloadedOrder.id)
            assertEquals(
                1,
                fixture.database.update(
                    "DELETE FROM tab_member WHERE tab_id = ? AND user_id = ?",
                ) { statement ->
                    statement.setLong(1, tab.id)
                    statement.setLong(2, fixture.database.actorUserId)
                },
            )
            val contextBefore = fixture.database.contextSnapshot()
            val sessionBefore = fixture.database.sessionSnapshot(fixture.tableSessionId)

            val failure =
                runCatching {
                    fixture.database.lifecycleRepository().withConfirmedPlatformGuestMutation(
                        actorUserId = fixture.database.actorUserId,
                        platformOwnerUserId = fixture.database.actorUserId,
                        tableToken = fixture.database.tableToken,
                        expectedVenueId = fixture.database.venueId,
                        expectedTableId = fixture.database.tableId,
                        expectedSessionId = fixture.tableSessionId,
                        ttl = Duration.ofHours(4),
                        now = fixture.now,
                    ) { connection, confirmed ->
                        shiftExtensionRepository.createPendingRequest(
                            connection = connection,
                            command =
                                CreateShiftExtensionRequestCommand(
                                    venueId = confirmed.context.venueId,
                                    tableSessionId = confirmed.tableSession.id,
                                    tableId = confirmed.context.tableId,
                                    tabId = tab.id,
                                    orderId = preloadedOrder.id,
                                    requestedByUserId = fixture.database.actorUserId,
                                    currentOrderableUntil = confirmed.tableSession.expiresAt,
                                    idempotencyKey = "stale-membership",
                                    comment = null,
                                ),
                            now = fixture.now,
                        )
                    }
                }.exceptionOrNull()

            assertIs<ForbiddenException>(failure)
            assertEquals(0, fixture.database.countRows("shift_extension_requests"))
            assertEquals(contextBefore, fixture.database.contextSnapshot())
            assertEquals(sessionBefore, fixture.database.sessionSnapshot(fixture.tableSessionId))
        }

    private fun createFixture(name: String): MutationFixture {
        val database = GuestTableContextLifecycleTestDatabase.create(name)
        val now = Instant.parse("2026-08-05T10:00:00Z")
        val tableSessionId =
            database.createActiveSession(
                startedAt = now.minus(Duration.ofHours(2)),
                lastActivityAt = now.minus(Duration.ofHours(1)),
                expiresAt = now.plus(Duration.ofMinutes(30)),
            )
        database.insertContext(updatedAt = now.minus(Duration.ofMinutes(1)))
        return MutationFixture(
            database = database,
            tableSessionId = tableSessionId,
            now = now,
            sessionBefore = checkNotNull(database.sessionSnapshot(tableSessionId)),
        )
    }

    private fun GuestTableContextLifecycleTestDatabase.lifecycleRepository(
        platformMutationCheckpoint: (PlatformGuestMutationCheckpoint) -> Unit = {},
        teardownCheckpoint: (GuestTableTeardownCheckpoint) -> Unit = {},
    ): GuestTableContextLifecycleRepository {
        val analyticsEventRepository = AnalyticsEventRepository(dataSource)
        return GuestTableContextLifecycleRepository(
            dataSource = dataSource,
            tableTokenRepository = TableTokenRepository(dataSource),
            subscriptionRepository = SubscriptionRepository(dataSource),
            tableSessionRepository = TableSessionRepository(dataSource, analyticsEventRepository),
            guestTabsRepository = GuestTabsRepository(dataSource),
            chatContextRepository = ChatContextRepository(dataSource),
            dialogStateRepository = DialogStateRepository(dataSource, Json),
            platformMutationCheckpoint = platformMutationCheckpoint,
            teardownCheckpoint = teardownCheckpoint,
        )
    }

    private data class MutationFixture(
        val database: GuestTableContextLifecycleTestDatabase,
        val tableSessionId: Long,
        val now: Instant,
        val sessionBefore: TestSessionSnapshot,
    )

    private enum class AuthoritativeMutationFamily(
        val expectedCreatedTables: List<String>,
    ) {
        ORDER(listOf("orders", "order_batches", "tab", "tab_member")),
        STAFF_CALL(listOf("staff_calls")),
        TAB(listOf("tab", "tab_member")),
        SHIFT_EXTENSION(listOf("shift_extension_requests", "orders", "order_batches", "tab", "tab_member")),
        SUPPORT(listOf("support_threads", "support_messages")),
        ;

        fun prepare(fixture: MutationFixture) {
            if (this != SHIFT_EXTENSION) {
                return
            }
            fixture.database.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO shift_extension_settings (
                        venue_id,
                        enabled,
                        duration_minutes,
                        price_minor,
                        currency,
                        max_extensions_per_session
                    )
                    VALUES (?, TRUE, 60, 1000, 'RUB', 2)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, fixture.database.venueId)
                    statement.executeUpdate()
                }
            }
        }

        fun mutate(
            connection: java.sql.Connection,
            confirmed: ConfirmedPlatformGuestMutationContext,
            fixture: MutationFixture,
        ) {
            val database = fixture.database
            when (this) {
                ORDER -> {
                    val tab =
                        GuestTabsRepository(database.dataSource).ensurePersonalTab(
                            connection = connection,
                            venueId = database.venueId,
                            tableSessionId = confirmed.tableSession.id,
                            userId = database.actorUserId,
                        )
                    val repository = OrdersRepository(database.dataSource)
                    val orderId =
                        checkNotNull(
                            repository.getOrCreateActiveOrderId(
                                connection = connection,
                                tableId = database.tableId,
                                venueId = database.venueId,
                                tableSessionId = confirmed.tableSession.id,
                                venueZoneId = ZoneId.of("Europe/Moscow"),
                            ),
                        )
                    checkNotNull(
                        repository.createOrderBatch(
                            connection = connection,
                            orderId = orderId,
                            authorUserId = database.actorUserId,
                            guestComment = "race proof",
                            tabId = tab.id,
                        ),
                    )
                }

                STAFF_CALL ->
                    StaffCallRepository(database.dataSource).createGuestStaffCall(
                        connection = connection,
                        venueId = database.venueId,
                        tableId = database.tableId,
                        tableSessionId = confirmed.tableSession.id,
                        createdByUserId = database.actorUserId,
                        reason = StaffCallReason.COME,
                        comment = null,
                    )

                TAB ->
                    GuestTabsRepository(database.dataSource).createSharedTab(
                        connection = connection,
                        venueId = database.venueId,
                        tableSessionId = confirmed.tableSession.id,
                        ownerUserId = database.actorUserId,
                    )

                SHIFT_EXTENSION -> {
                    val tab =
                        GuestTabsRepository(database.dataSource).ensurePersonalTab(
                            connection = connection,
                            venueId = database.venueId,
                            tableSessionId = confirmed.tableSession.id,
                            userId = database.actorUserId,
                        )
                    val ordersRepository = OrdersRepository(database.dataSource)
                    val orderId =
                        checkNotNull(
                            ordersRepository.getOrCreateActiveOrderId(
                                connection = connection,
                                tableId = database.tableId,
                                venueId = database.venueId,
                                tableSessionId = confirmed.tableSession.id,
                                venueZoneId = ZoneId.of("Europe/Moscow"),
                            ),
                        )
                    checkNotNull(
                        ordersRepository.createOrderBatch(
                            connection = connection,
                            orderId = orderId,
                            authorUserId = database.actorUserId,
                            guestComment = "shift race proof",
                            tabId = tab.id,
                        ),
                    )
                    ShiftExtensionRepository(database.dataSource).createPendingRequest(
                        connection = connection,
                        command =
                            CreateShiftExtensionRequestCommand(
                                venueId = database.venueId,
                                tableSessionId = confirmed.tableSession.id,
                                tableId = database.tableId,
                                tabId = tab.id,
                                orderId = orderId,
                                requestedByUserId = database.actorUserId,
                                currentOrderableUntil = confirmed.tableSession.expiresAt,
                                idempotencyKey = "race-proof",
                                comment = null,
                            ),
                        now = fixture.now,
                    )
                }

                SUPPORT ->
                    SupportThreadRepository(database.dataSource).createTicket(
                        connection = connection,
                        input =
                            SupportTicketCreateInput(
                                guestUserId = database.actorUserId,
                                category = SupportThreadCategory.ORDER_SERVICE,
                                title = "Race proof",
                                message = "Race proof",
                                venueId = database.venueId,
                                tableId = database.tableId,
                                tableSessionId = confirmed.tableSession.id,
                                assigneeScope = SupportAssigneeScope.VENUE,
                                createdSource = SupportThreadCreatedSource.GUEST_MINIAPP,
                                messageSource = SupportMessageSource.GUEST_MINIAPP,
                            ),
                    )
            }
        }
    }
}
