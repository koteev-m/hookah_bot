package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.api.CartMenuSelectionKind
import com.hookah.platform.backend.api.CartMenuSelectionReason
import com.hookah.platform.backend.api.CartMenuSelectionUnavailableException
import com.hookah.platform.backend.api.OrderIdempotencyPayloadMismatchException
import com.hookah.platform.backend.miniapp.guest.db.GuestOrderTransactionCoordinator
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.menu.MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MENU_ITEM_DELETED_AUDIT_ACTION
import com.hookah.platform.backend.miniapp.venue.menu.MenuItemAvailabilitySource
import com.hookah.platform.backend.miniapp.venue.menu.MenuItemDeleteSource
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GuestOrderIdempotencyConcurrencyPostgresTest {
    @Test
    fun `two concurrent exact retries return one committed batch without new writes`() {
        withFixture { dataSource, fixture ->
            val scope = fixture.primaryPersonalScope()
            val initial = submit(ordersRepository(dataSource), fixture, scope, EXACT_RETRY_KEY)
            val before = readSnapshot(dataSource)
            val concurrentDataSource = TransactionStartBarrierDataSource(dataSource)
            val repository = ordersRepository(concurrentDataSource)

            val results =
                runConcurrently(
                    { submit(repository, fixture, scope, EXACT_RETRY_KEY) },
                    { submit(repository, fixture, scope, EXACT_RETRY_KEY) },
                ).map(Result<CreatedOrderBatch>::getOrThrow)

            assertEquals(setOf(initial.orderId), results.mapTo(mutableSetOf(), CreatedOrderBatch::orderId))
            assertEquals(setOf(initial.batchId), results.mapTo(mutableSetOf(), CreatedOrderBatch::batchId))
            assertTrue(results.all(CreatedOrderBatch::idempotencyReplay))
            assertEquals(2, concurrentDataSource.backendPids.size)
            assertEquals(before, readSnapshot(dataSource))
        }
    }

    @Test
    fun `concurrent exact retry and changed quantity yield replay and mismatch without writes`() {
        withFixture { dataSource, fixture ->
            val scope = fixture.primaryPersonalScope()
            val initial = submit(ordersRepository(dataSource), fixture, scope, PAYLOAD_MISMATCH_KEY)
            val before = readSnapshot(dataSource)
            val concurrentDataSource = TransactionStartBarrierDataSource(dataSource)
            val repository = ordersRepository(concurrentDataSource)

            val results =
                runConcurrently(
                    { submit(repository, fixture, scope, PAYLOAD_MISMATCH_KEY) },
                    {
                        submit(
                            repository = repository,
                            fixture = fixture,
                            scope = scope,
                            idempotencyKey = PAYLOAD_MISMATCH_KEY,
                            items = fixture.cartItems(qty = 2),
                        )
                    },
                )

            val replay = results[0].getOrThrow()
            assertEquals(initial.batchId, replay.batchId)
            assertTrue(replay.idempotencyReplay)
            assertIs<OrderIdempotencyPayloadMismatchException>(results[1].exceptionOrNull())
            assertEquals(2, concurrentDataSource.backendPids.size)
            assertEquals(before, readSnapshot(dataSource))
        }
    }

    @Test
    fun `two concurrent new submits with one key create one order and one idempotency row`() {
        withFixture { dataSource, fixture ->
            val scope = fixture.primaryPersonalScope()
            val concurrentDataSource = TransactionStartBarrierDataSource(dataSource)
            val repository = ordersRepository(concurrentDataSource)

            val results =
                runConcurrently(
                    { submit(repository, fixture, scope, CONCURRENT_NEW_KEY) },
                    { submit(repository, fixture, scope, CONCURRENT_NEW_KEY) },
                ).map(Result<CreatedOrderBatch>::getOrThrow)

            assertEquals(1, results.map(CreatedOrderBatch::orderId).distinct().size)
            assertEquals(1, results.map(CreatedOrderBatch::batchId).distinct().size)
            assertEquals(listOf(false, true), results.map(CreatedOrderBatch::idempotencyReplay).sorted())
            assertEquals(2, concurrentDataSource.backendPids.size)
            assertOrderWriteCounts(dataSource, expected = 1)
        }
    }

    @Test
    fun `same key on another tab in one session mismatches while exact retry succeeds`() {
        withFixture { dataSource, fixture ->
            val personalScope = fixture.primaryPersonalScope()
            val sharedScope = personalScope.copy(tabId = fixture.sharedTabId)
            val initial = submit(ordersRepository(dataSource), fixture, personalScope, TAB_SCOPE_KEY)
            val before = readSnapshot(dataSource)
            val concurrentDataSource = TransactionStartBarrierDataSource(dataSource)
            val repository = ordersRepository(concurrentDataSource)

            val results =
                runConcurrently(
                    { submit(repository, fixture, personalScope, TAB_SCOPE_KEY) },
                    { submit(repository, fixture, sharedScope, TAB_SCOPE_KEY) },
                )

            val replay = results[0].getOrThrow()
            assertEquals(initial.batchId, replay.batchId)
            assertTrue(replay.idempotencyReplay)
            assertIs<OrderIdempotencyPayloadMismatchException>(results[1].exceptionOrNull())
            assertEquals(2, concurrentDataSource.backendPids.size)
            assertEquals(before, readSnapshot(dataSource))
        }
    }

    @Test
    fun `same key in different sessions commits one batch in each scope`() {
        withFixture { dataSource, fixture ->
            val firstScope = fixture.primaryPersonalScope()
            val secondScope = fixture.secondaryScope
            val concurrentDataSource = TransactionStartBarrierDataSource(dataSource)
            val repository = ordersRepository(concurrentDataSource)

            val results =
                runConcurrently(
                    { submit(repository, fixture, firstScope, CROSS_SESSION_KEY) },
                    { submit(repository, fixture, secondScope, CROSS_SESSION_KEY) },
                ).map(Result<CreatedOrderBatch>::getOrThrow)

            assertTrue(results.none(CreatedOrderBatch::idempotencyReplay))
            assertEquals(2, results.map(CreatedOrderBatch::orderId).distinct().size)
            assertEquals(2, results.map(CreatedOrderBatch::batchId).distinct().size)
            assertEquals(2, concurrentDataSource.backendPids.size)
            assertEquals(1, countIdempotencyRows(dataSource, firstScope.tableSessionId, CROSS_SESSION_KEY))
            assertEquals(1, countIdempotencyRows(dataSource, secondScope.tableSessionId, CROSS_SESSION_KEY))
            assertOrderWriteCounts(dataSource, expected = 2)
        }
    }

    @Test
    fun `availability mutation wins before menu lock and stale submit leaves no writes`() {
        withFixture { dataSource, fixture ->
            val before = readSnapshot(dataSource)
            val submitDataSource = BeforeMenuSelectionLockDataSource(dataSource)
            val writerDataSource = RecordingDataSource(dataSource)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future =
                    executor.submit(
                        Callable {
                            runCatching {
                                submit(
                                    repository = ordersRepository(submitDataSource),
                                    fixture = fixture,
                                    scope = fixture.primaryPersonalScope(),
                                    idempotencyKey = AVAILABILITY_RACE_KEY,
                                )
                            }
                        },
                    )
                await(submitDataSource.menuLockReached, "submit menu lock")
                val unavailable =
                    assertNotNull(
                        runBlocking {
                            VenueMenuRepository(writerDataSource).setItemAvailability(
                                venueId = fixture.venueId,
                                itemId = fixture.itemId,
                                isAvailable = false,
                                actorUserId = AVAILABILITY_ACTOR_ID,
                                source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                            )
                        },
                    )
                assertFalse(unavailable.isAvailable)
                submitDataSource.allowMenuLock.countDown()

                assertItemIssue(
                    future.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).exceptionOrNull(),
                    CartMenuSelectionReason.UNAVAILABLE,
                )
                assertNotEquals(submitDataSource.backendPid.get(), writerDataSource.backendPid.get())
                assertEquals(before.copy(auditRows = before.auditRows + 1), readSnapshot(dataSource))
                assertFalse(loadMenuItemAvailability(dataSource, fixture.itemId))
                assertEquals(1, countMenuItemAvailabilityAudits(dataSource, fixture.itemId))
            } finally {
                submitDataSource.allowMenuLock.countDown()
                shutdown(executor)
            }
        }
    }

    @Test
    fun `delete wins before menu lock and stale submit leaves only delete audit`() {
        withFixture { dataSource, fixture ->
            val before = readSnapshot(dataSource)
            val submitDataSource = BeforeMenuSelectionLockDataSource(dataSource)
            val writerDataSource = RecordingDataSource(dataSource)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future =
                    executor.submit(
                        Callable {
                            runCatching {
                                submit(
                                    repository = ordersRepository(submitDataSource),
                                    fixture = fixture,
                                    scope = fixture.primaryPersonalScope(),
                                    idempotencyKey = DELETE_RACE_KEY,
                                )
                            }
                        },
                    )
                await(submitDataSource.menuLockReached, "submit menu lock")
                assertTrue(
                    runBlocking {
                        VenueMenuRepository(writerDataSource).deleteItem(
                            venueId = fixture.venueId,
                            itemId = fixture.itemId,
                            actorUserId = DELETE_ACTOR_ID,
                            source = MenuItemDeleteSource.VENUE_MINI_APP,
                        )
                    },
                )
                submitDataSource.allowMenuLock.countDown()

                assertItemIssue(
                    future.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).exceptionOrNull(),
                    CartMenuSelectionReason.REMOVED,
                )
                assertNotEquals(submitDataSource.backendPid.get(), writerDataSource.backendPid.get())
                assertEquals(before.copy(auditRows = before.auditRows + 1), readSnapshot(dataSource))
                assertEquals(0, countRows(dataSource, "menu_items"))
                assertEquals(1, countMenuItemDeleteAudits(dataSource, fixture.itemId))
            } finally {
                submitDataSource.allowMenuLock.countDown()
                shutdown(executor)
            }
        }
    }

    @Test
    fun `concurrent stale rejections do not touch session or create personal tab`() {
        withFixture(includePrimaryPersonalTab = false) { dataSource, fixture ->
            runBlocking {
                VenueMenuRepository(dataSource).setItemAvailability(
                    venueId = fixture.venueId,
                    itemId = fixture.itemId,
                    isAvailable = false,
                    actorUserId = AVAILABILITY_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                )
            }
            val before = readSnapshot(dataSource)
            assertEquals(0, countPrimaryPersonalTabs(dataSource, fixture))
            val concurrentDataSource = TransactionStartBarrierDataSource(dataSource)
            val ordersRepository = ordersRepository(concurrentDataSource)
            val tableSessionRepository = TableSessionRepository(concurrentDataSource)
            val guestTabsRepository = GuestTabsRepository(concurrentDataSource)
            val coordinator =
                GuestOrderTransactionCoordinator(
                    dataSource = concurrentDataSource,
                    tableTokenRepository = TableTokenRepository(concurrentDataSource),
                    subscriptionRepository = SubscriptionRepository(concurrentDataSource),
                    tableSessionRepository = tableSessionRepository,
                )
            val sharedScope = fixture.primaryScope(fixture.sharedTabId)

            val results =
                runConcurrently(
                    {
                        submitWithContext(
                            coordinator = coordinator,
                            ordersRepository = ordersRepository,
                            tableSessionRepository = tableSessionRepository,
                            guestTabsRepository = guestTabsRepository,
                            fixture = fixture,
                            scope = sharedScope,
                            idempotencyKey = "$STALE_CONTEXT_KEY-1",
                        )
                    },
                    {
                        submitWithContext(
                            coordinator = coordinator,
                            ordersRepository = ordersRepository,
                            tableSessionRepository = tableSessionRepository,
                            guestTabsRepository = guestTabsRepository,
                            fixture = fixture,
                            scope = sharedScope,
                            idempotencyKey = "$STALE_CONTEXT_KEY-2",
                        )
                    },
                )

            results.forEach { result ->
                assertItemIssue(result.exceptionOrNull(), CartMenuSelectionReason.UNAVAILABLE)
            }
            assertEquals(2, concurrentDataSource.backendPids.size)
            assertEquals(before, readSnapshot(dataSource))
            assertEquals(0, countPrimaryPersonalTabs(dataSource, fixture))
        }
    }

    @Test
    fun `concurrent exact retries lazily upgrade one legacy fingerprint without duplicates`() {
        withFixture { dataSource, fixture ->
            val scope = fixture.primaryPersonalScope()
            val initial = submit(ordersRepository(dataSource), fixture, scope, LEGACY_UPGRADE_KEY)
            clearRequestFingerprint(dataSource, scope.tableSessionId, LEGACY_UPGRADE_KEY)
            val before = readSnapshot(dataSource)
            val concurrentDataSource = TransactionStartBarrierDataSource(dataSource)
            val repository = ordersRepository(concurrentDataSource)

            val results =
                runConcurrently(
                    { submit(repository, fixture, scope, LEGACY_UPGRADE_KEY) },
                    { submit(repository, fixture, scope, LEGACY_UPGRADE_KEY) },
                ).map(Result<CreatedOrderBatch>::getOrThrow)

            assertEquals(setOf(initial.batchId), results.mapTo(mutableSetOf(), CreatedOrderBatch::batchId))
            assertTrue(results.all(CreatedOrderBatch::idempotencyReplay))
            assertEquals(2, concurrentDataSource.backendPids.size)
            assertEquals(before, readSnapshot(dataSource))
            val fingerprint =
                assertNotNull(loadRequestFingerprint(dataSource, scope.tableSessionId, LEGACY_UPGRADE_KEY))
            assertTrue(fingerprint.matches(REQUEST_FINGERPRINT_PATTERN))
            assertEquals(1, countIdempotencyRows(dataSource, scope.tableSessionId, LEGACY_UPGRADE_KEY))
        }
    }

    private fun withFixture(
        includePrimaryPersonalTab: Boolean = true,
        block: (DataSource, Fixture) -> Unit,
    ) {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database).use { dataSource ->
            block(dataSource, seedFixture(dataSource, includePrimaryPersonalTab))
        }
    }

    private fun ordersRepository(dataSource: DataSource): OrdersRepository =
        OrdersRepository(
            dataSource = dataSource,
            analyticsEventRepository = AnalyticsEventRepository(dataSource),
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )

    private fun submit(
        repository: OrdersRepository,
        fixture: Fixture,
        scope: OrderScope,
        idempotencyKey: String,
        items: List<OrderBatchItemInput> = fixture.cartItems(),
    ): CreatedOrderBatch =
        assertNotNull(
            runBlocking {
                repository.createGuestOrderBatch(
                    tableId = scope.tableId,
                    venueId = fixture.venueId,
                    tableSessionId = scope.tableSessionId,
                    userId = USER_ID,
                    idempotencyKey = idempotencyKey,
                    tabId = scope.tabId,
                    comment = COMMENT,
                    items = items,
                    venueZoneId = UTC,
                )
            },
        )

    private fun submitWithContext(
        coordinator: GuestOrderTransactionCoordinator,
        ordersRepository: OrdersRepository,
        tableSessionRepository: TableSessionRepository,
        guestTabsRepository: GuestTabsRepository,
        fixture: Fixture,
        scope: OrderScope,
        idempotencyKey: String,
    ): CreatedOrderBatch =
        runBlocking {
            coordinator.execute { connection ->
                assertNotNull(
                    ordersRepository.createGuestOrderBatch(
                        connection = connection,
                        tableId = scope.tableId,
                        venueId = fixture.venueId,
                        tableSessionId = scope.tableSessionId,
                        userId = USER_ID,
                        idempotencyKey = idempotencyKey,
                        tabId = scope.tabId,
                        comment = COMMENT,
                        items = fixture.cartItems(),
                        venueZoneId = UTC,
                        beforeAuthoritativeWrites = {
                            assertNotNull(
                                tableSessionRepository.touchActiveSession(
                                    connection = connection,
                                    tableSessionId = scope.tableSessionId,
                                    venueId = fixture.venueId,
                                    tableId = scope.tableId,
                                    ttl = Duration.ofHours(2),
                                    now = CONTEXT_TOUCH_NOW,
                                ),
                            )
                            guestTabsRepository.ensurePersonalTab(
                                connection = connection,
                                venueId = fixture.venueId,
                                tableSessionId = scope.tableSessionId,
                                userId = USER_ID,
                            )
                        },
                    ),
                )
            }
        }

    private fun <T> runConcurrently(
        first: () -> T,
        second: () -> T,
    ): List<Result<T>> {
        val executor = Executors.newFixedThreadPool(2)
        return try {
            listOf(first, second)
                .map { action -> executor.submit(Callable { runCatching(action) }) }
                .map { future -> future.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            shutdown(executor)
        }
    }

    private fun assertItemIssue(
        throwable: Throwable?,
        expectedReason: CartMenuSelectionReason,
    ) {
        val exception = assertIs<CartMenuSelectionUnavailableException>(throwable)
        val issue = exception.issues.single()
        assertEquals(CART_LINE_REF, issue.cartLineRef)
        assertEquals(CartMenuSelectionKind.ITEM, issue.selectionKind)
        assertEquals(expectedReason, issue.reason)
    }

    private fun assertOrderWriteCounts(
        dataSource: DataSource,
        expected: Int,
    ) {
        assertEquals(expected, countRows(dataSource, "orders"))
        assertEquals(expected, countRows(dataSource, "order_batches"))
        assertEquals(expected, countRows(dataSource, "order_batch_items"))
        assertEquals(expected, countRows(dataSource, "order_batch_item_options"))
        assertEquals(expected, countRows(dataSource, "guest_batch_idempotency"))
        assertEquals(expected, countRows(dataSource, "analytics_events"))
        assertEquals(0, countRows(dataSource, "telegram_outbox"))
    }

    private fun seedFixture(
        dataSource: DataSource,
        includePrimaryPersonalTab: Boolean,
    ): Fixture =
        dataSource.connection.use { connection ->
            insertUser(connection, USER_ID)
            insertUser(connection, DELETE_ACTOR_ID)
            insertUser(connection, AVAILABILITY_ACTOR_ID)
            val venueId = insertVenue(connection)
            val primaryTableId = insertTable(connection, venueId, tableNumber = 1)
            val primarySessionId = insertSession(connection, venueId, primaryTableId)
            val primaryPersonalTabId =
                if (includePrimaryPersonalTab) {
                    insertTab(connection, venueId, primarySessionId, type = "PERSONAL")
                } else {
                    null
                }
            val sharedTabId = insertTab(connection, venueId, primarySessionId, type = "SHARED")
            val secondaryTableId = insertTable(connection, venueId, tableNumber = 2)
            val secondarySessionId = insertSession(connection, venueId, secondaryTableId)
            val secondaryTabId = insertTab(connection, venueId, secondarySessionId, type = "PERSONAL")
            val categoryId = insertCategory(connection, venueId)
            val itemId = insertItem(connection, venueId, categoryId)
            val optionId = insertOption(connection, venueId, itemId)
            Fixture(
                venueId = venueId,
                primaryTableId = primaryTableId,
                primarySessionId = primarySessionId,
                primaryPersonalTabId = primaryPersonalTabId,
                sharedTabId = sharedTabId,
                secondaryScope =
                    OrderScope(
                        tableId = secondaryTableId,
                        tableSessionId = secondarySessionId,
                        tabId = secondaryTabId,
                    ),
                itemId = itemId,
                optionId = optionId,
            )
        }

    private fun insertUser(
        connection: Connection,
        userId: Long,
    ) {
        connection.prepareStatement(
            "INSERT INTO users (telegram_user_id, username) VALUES (?, ?)",
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, "concurrency_$userId")
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun insertVenue(connection: Connection): Long =
        insertReturningId(
            connection,
            """
            INSERT INTO venues (name, city, address, status)
            VALUES ('Concurrency venue', 'Moscow', 'Race street, 1', 'PUBLISHED')
            """.trimIndent(),
        )

    private fun insertTable(
        connection: Connection,
        venueId: Long,
        tableNumber: Int,
    ): Long =
        insertReturningId(
            connection,
            "INSERT INTO venue_tables (venue_id, table_number, is_active) VALUES (?, ?, TRUE)",
        ) { statement ->
            statement.setLong(1, venueId)
            statement.setInt(2, tableNumber)
        }

    private fun insertSession(
        connection: Connection,
        venueId: Long,
        tableId: Long,
    ): Long =
        insertReturningId(
            connection,
            """
            INSERT INTO table_sessions (
                venue_id,
                table_id,
                started_at,
                last_activity_at,
                expires_at,
                status
            )
            VALUES (?, ?, ?, ?, ?, 'ACTIVE')
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.setTimestamp(3, Timestamp.from(SESSION_STARTED_AT))
            statement.setTimestamp(4, Timestamp.from(SESSION_LAST_ACTIVITY_AT))
            statement.setTimestamp(5, Timestamp.from(SESSION_EXPIRES_AT))
        }

    private fun insertTab(
        connection: Connection,
        venueId: Long,
        tableSessionId: Long,
        type: String,
    ): Long {
        val tabId =
            insertReturningId(
                connection,
                """
                INSERT INTO tab (venue_id, table_session_id, type, owner_user_id, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """.trimIndent(),
            ) { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, tableSessionId)
                statement.setString(3, type)
                statement.setLong(4, USER_ID)
            }
        connection.prepareStatement(
            "INSERT INTO tab_member (tab_id, user_id, role) VALUES (?, ?, 'OWNER')",
        ).use { statement ->
            statement.setLong(1, tabId)
            statement.setLong(2, USER_ID)
            assertEquals(1, statement.executeUpdate())
        }
        return tabId
    }

    private fun insertCategory(
        connection: Connection,
        venueId: Long,
    ): Long =
        insertReturningId(
            connection,
            "INSERT INTO menu_categories (venue_id, name, sort_order) VALUES (?, 'Concurrency menu', 0)",
        ) { statement -> statement.setLong(1, venueId) }

    private fun insertItem(
        connection: Connection,
        venueId: Long,
        categoryId: Long,
    ): Long =
        insertReturningId(
            connection,
            """
            INSERT INTO menu_items (
                venue_id,
                category_id,
                name,
                price_minor,
                currency,
                is_available,
                sort_order
            )
            VALUES (?, ?, 'Concurrency item', 1000, 'RUB', TRUE, 0)
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, categoryId)
        }

    private fun insertOption(
        connection: Connection,
        venueId: Long,
        itemId: Long,
    ): Long =
        insertReturningId(
            connection,
            """
            INSERT INTO menu_item_options (
                venue_id,
                item_id,
                name,
                price_delta_minor,
                is_available,
                sort_order
            )
            VALUES (?, ?, 'Concurrency option', 100, TRUE, 0)
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, itemId)
        }

    private fun insertReturningId(
        connection: Connection,
        sql: String,
        bind: (PreparedStatement) -> Unit = {},
    ): Long =
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            bind(statement)
            assertEquals(1, statement.executeUpdate())
            statement.generatedKeys.use { keys ->
                assertTrue(keys.next())
                keys.getLong(1)
            }
        }

    private fun readSnapshot(dataSource: DataSource): AuthoritativeSnapshot =
        dataSource.connection.use { connection ->
            AuthoritativeSnapshot(
                sessions = readSessions(connection),
                tabs = readTabs(connection),
                members = readTabMembers(connection),
                exits = countRows(connection, "guest_table_session_exits"),
                orders = countRows(connection, "orders"),
                batches = countRows(connection, "order_batches"),
                batchItems = countRows(connection, "order_batch_items"),
                batchItemOptions = countRows(connection, "order_batch_item_options"),
                idempotencyRows = countRows(connection, "guest_batch_idempotency"),
                analyticsRows = countRows(connection, "analytics_events"),
                outboxRows = countRows(connection, "telegram_outbox"),
                dialogRows = countRows(connection, "telegram_dialog_state"),
                auditRows = countRows(connection, "audit_log"),
            )
        }

    private fun readSessions(connection: Connection): List<SessionRow> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT id, venue_id, table_id, started_at, last_activity_at, expires_at, ended_at, status
                FROM table_sessions
                ORDER BY id
                """.trimIndent(),
            ).use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            SessionRow(
                                id = resultSet.getLong("id"),
                                venueId = resultSet.getLong("venue_id"),
                                tableId = resultSet.getLong("table_id"),
                                startedAt = resultSet.getTimestamp("started_at").toInstant(),
                                lastActivityAt = resultSet.getTimestamp("last_activity_at").toInstant(),
                                expiresAt = resultSet.getTimestamp("expires_at").toInstant(),
                                endedAt = resultSet.getTimestamp("ended_at")?.toInstant(),
                                status = resultSet.getString("status"),
                            ),
                        )
                    }
                }
            }
        }

    private fun readTabs(connection: Connection): List<TabRow> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT id, venue_id, table_session_id, type, owner_user_id, status
                FROM tab
                ORDER BY id
                """.trimIndent(),
            ).use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            TabRow(
                                id = resultSet.getLong("id"),
                                venueId = resultSet.getLong("venue_id"),
                                tableSessionId = resultSet.getLong("table_session_id"),
                                type = resultSet.getString("type"),
                                ownerUserId = resultSet.getLong("owner_user_id"),
                                status = resultSet.getString("status"),
                            ),
                        )
                    }
                }
            }
        }

    private fun readTabMembers(connection: Connection): List<TabMemberRow> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT tab_id, user_id, role FROM tab_member ORDER BY tab_id, user_id",
            ).use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            TabMemberRow(
                                tabId = resultSet.getLong("tab_id"),
                                userId = resultSet.getLong("user_id"),
                                role = resultSet.getString("role"),
                            ),
                        )
                    }
                }
            }
        }

    private fun countRows(
        dataSource: DataSource,
        table: String,
    ): Int = dataSource.connection.use { connection -> countRows(connection, table) }

    private fun countRows(
        connection: Connection,
        table: String,
    ): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getInt(1)
            }
        }

    private fun countIdempotencyRows(
        dataSource: DataSource,
        tableSessionId: Long,
        key: String,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM guest_batch_idempotency
                WHERE table_session_id = ? AND idempotency_key = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, tableSessionId)
                statement.setString(2, key)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }

    private fun clearRequestFingerprint(
        dataSource: DataSource,
        tableSessionId: Long,
        key: String,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE guest_batch_idempotency
                SET request_fingerprint = NULL
                WHERE table_session_id = ? AND idempotency_key = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, tableSessionId)
                statement.setString(2, key)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun loadRequestFingerprint(
        dataSource: DataSource,
        tableSessionId: Long,
        key: String,
    ): String? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT request_fingerprint
                FROM guest_batch_idempotency
                WHERE table_session_id = ? AND idempotency_key = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, tableSessionId)
                statement.setString(2, key)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getString("request_fingerprint")
                }
            }
        }

    private fun loadMenuItemAvailability(
        dataSource: DataSource,
        itemId: Long,
    ): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT is_available FROM menu_items WHERE id = ?").use { statement ->
                statement.setLong(1, itemId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getBoolean("is_available")
                }
            }
        }

    private fun countMenuItemDeleteAudits(
        dataSource: DataSource,
        itemId: Long,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_id = ?",
            ).use { statement ->
                statement.setString(1, MENU_ITEM_DELETED_AUDIT_ACTION)
                statement.setLong(2, itemId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }

    private fun countMenuItemAvailabilityAudits(
        dataSource: DataSource,
        itemId: Long,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_id = ?",
            ).use { statement ->
                statement.setString(1, MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION)
                statement.setLong(2, itemId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }

    private fun countPrimaryPersonalTabs(
        dataSource: DataSource,
        fixture: Fixture,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM tab
                WHERE table_session_id = ?
                  AND owner_user_id = ?
                  AND type = 'PERSONAL'
                  AND status = 'ACTIVE'
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, fixture.primarySessionId)
                statement.setLong(2, USER_ID)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }

    private fun backendPid(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getInt(1)
            }
        }

    private fun await(
        latch: CountDownLatch,
        label: String,
    ) {
        assertTrue(latch.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Timed out waiting for $label")
    }

    private fun shutdown(executor: java.util.concurrent.ExecutorService) {
        executor.shutdownNow()
        assertTrue(executor.awaitTermination(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    private fun String.isGuestMenuItemLock(): Boolean {
        val normalized = trim().replace(WHITESPACE, " ").lowercase()
        return normalized.contains("select id") &&
            normalized.contains("from menu_items") &&
            normalized.contains("order by id") &&
            normalized.contains("for update")
    }

    private inner class TransactionStartBarrierDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        private val barrier = CyclicBarrier(2)
        val backendPids = ConcurrentHashMap.newKeySet<Int>()

        override fun getConnection(): Connection = wrap(delegate.connection)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = wrap(delegate.getConnection(username, password))

        private fun wrap(connection: Connection): Connection {
            var joinedBarrier = false
            return object : Connection by connection {
                override fun setAutoCommit(autoCommit: Boolean) {
                    connection.autoCommit = autoCommit
                    if (!autoCommit && !joinedBarrier) {
                        joinedBarrier = true
                        backendPids += backendPid(connection)
                        try {
                            barrier.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            throw SQLException("Timed out waiting for guest order transactions", e)
                        }
                    }
                }
            }
        }
    }

    private inner class BeforeMenuSelectionLockDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        val menuLockReached = CountDownLatch(1)
        val allowMenuLock = CountDownLatch(1)
        val backendPid = AtomicInteger()
        private val paused = AtomicBoolean()

        override fun getConnection(): Connection = wrap(delegate.connection)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = wrap(delegate.getConnection(username, password))

        private fun wrap(connection: Connection): Connection {
            backendPid.set(this@GuestOrderIdempotencyConcurrencyPostgresTest.backendPid(connection))
            return object : Connection by connection {
                override fun prepareStatement(sql: String): PreparedStatement {
                    val prepared = connection.prepareStatement(sql)
                    if (sql.isGuestMenuItemLock() && paused.compareAndSet(false, true)) {
                        return object : PreparedStatement by prepared {
                            override fun executeQuery(): ResultSet {
                                check(!connection.autoCommit) {
                                    "Guest menu selections must be locked in the order transaction"
                                }
                                menuLockReached.countDown()
                                if (!allowMenuLock.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                    throw SQLException("Timed out before guest menu selection lock")
                                }
                                return prepared.executeQuery()
                            }
                        }
                    }
                    return prepared
                }
            }
        }
    }

    private inner class RecordingDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        val backendPid = AtomicInteger()

        override fun getConnection(): Connection = record(delegate.connection)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = record(delegate.getConnection(username, password))

        private fun record(connection: Connection): Connection {
            backendPid.set(this@GuestOrderIdempotencyConcurrencyPostgresTest.backendPid(connection))
            return connection
        }
    }

    private data class Fixture(
        val venueId: Long,
        val primaryTableId: Long,
        val primarySessionId: Long,
        val primaryPersonalTabId: Long?,
        val sharedTabId: Long,
        val secondaryScope: OrderScope,
        val itemId: Long,
        val optionId: Long,
    ) {
        fun primaryPersonalScope(): OrderScope = primaryScope(checkNotNull(primaryPersonalTabId))

        fun primaryScope(tabId: Long): OrderScope =
            OrderScope(
                tableId = primaryTableId,
                tableSessionId = primarySessionId,
                tabId = tabId,
            )

        fun cartItems(qty: Int = 1): List<OrderBatchItemInput> =
            listOf(
                OrderBatchItemInput(
                    cartLineRef = CART_LINE_REF,
                    itemId = itemId,
                    qty = qty,
                    selectedOptionId = optionId,
                    preferenceNote = " medium ",
                ),
            )
    }

    private data class OrderScope(
        val tableId: Long,
        val tableSessionId: Long,
        val tabId: Long,
    )

    private data class AuthoritativeSnapshot(
        val sessions: List<SessionRow>,
        val tabs: List<TabRow>,
        val members: List<TabMemberRow>,
        val exits: Int,
        val orders: Int,
        val batches: Int,
        val batchItems: Int,
        val batchItemOptions: Int,
        val idempotencyRows: Int,
        val analyticsRows: Int,
        val outboxRows: Int,
        val dialogRows: Int,
        val auditRows: Int,
    )

    private data class SessionRow(
        val id: Long,
        val venueId: Long,
        val tableId: Long,
        val startedAt: Instant,
        val lastActivityAt: Instant,
        val expiresAt: Instant,
        val endedAt: Instant?,
        val status: String,
    )

    private data class TabRow(
        val id: Long,
        val venueId: Long,
        val tableSessionId: Long,
        val type: String,
        val ownerUserId: Long,
        val status: String,
    )

    private data class TabMemberRow(
        val tabId: Long,
        val userId: Long,
        val role: String,
    )

    private companion object {
        const val USER_ID = 9_310_001L
        const val DELETE_ACTOR_ID = 9_310_002L
        const val AVAILABILITY_ACTOR_ID = 9_310_003L
        const val CART_LINE_REF = "concurrency-line"
        const val COMMENT = "  concurrency comment  "
        const val EXACT_RETRY_KEY = "concurrent-exact-retry"
        const val PAYLOAD_MISMATCH_KEY = "concurrent-payload-mismatch"
        const val CONCURRENT_NEW_KEY = "concurrent-new-key"
        const val TAB_SCOPE_KEY = "concurrent-tab-scope"
        const val CROSS_SESSION_KEY = "concurrent-cross-session"
        const val AVAILABILITY_RACE_KEY = "concurrent-availability-race"
        const val DELETE_RACE_KEY = "concurrent-delete-race"
        const val STALE_CONTEXT_KEY = "concurrent-stale-context"
        const val LEGACY_UPGRADE_KEY = "concurrent-legacy-upgrade"
        const val WAIT_TIMEOUT_SECONDS = 30L
        val UTC: ZoneId = ZoneId.of("UTC")
        val NOW: Instant = Instant.parse("2026-08-11T12:00:00Z")
        val SESSION_STARTED_AT: Instant = NOW.minusSeconds(300)
        val SESSION_LAST_ACTIVITY_AT: Instant = NOW.minusSeconds(120)
        val SESSION_EXPIRES_AT: Instant = NOW.plusSeconds(7_200)
        val CONTEXT_TOUCH_NOW: Instant = NOW.plusSeconds(300)
        val REQUEST_FINGERPRINT_PATTERN = Regex("v1:[0-9a-f]{64}")
        val WHITESPACE = Regex("\\s+")
    }
}
