package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.api.OrderIdempotencyPayloadMismatchException
import com.hookah.platform.backend.api.OrderIdempotencyReplayUnverifiableException
import com.hookah.platform.backend.promotions.GiftDecisionScopeTokenService
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuestOrderIdempotencyFingerprintTest {
    @Test
    fun `exact and equivalent retries return one committed batch and preserve snapshot`() {
        val fixture = fixture()
        val key = "fingerprint-exact"
        val originalItems = fixture.orderItems()
        var venueZoneResolutionCount = 0

        val created =
            fixture.submit(
                idempotencyKey = key,
                items = originalItems,
                comment = "serve now",
                expectedPreviewFingerprint = "client-price-a",
                venueZoneIdProvider = {
                    venueZoneResolutionCount += 1
                    ZoneId.of("UTC")
                },
            )
        val afterCreate = fixture.businessCounts()
        val newRecord = fixture.idempotencyRecord(key)

        assertFalse(created.idempotencyReplay)
        assertEquals(1, venueZoneResolutionCount)
        assertTrue(assertNotNull(newRecord.requestFingerprint).matches(Regex("v1:[0-9a-f]{64}")))
        assertNull(newRecord.responseSnapshot)

        val exact =
            fixture.submit(
                idempotencyKey = key,
                items = originalItems,
                comment = "serve now",
                expectedPreviewFingerprint = "client-price-b",
                venueZoneIdProvider = { error("Exact replay must not resolve venue timezone") },
            )
        assertTrue(exact.idempotencyReplay)
        assertEquals(created.batchId, exact.batchId)
        assertEquals(afterCreate, fixture.businessCounts())

        fixture.execute(
            """
            UPDATE guest_batch_idempotency
            SET response_snapshot = ?
            WHERE table_session_id = ? AND idempotency_key = ?
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, "{\"legacy\":true}")
            statement.setLong(2, fixture.primaryScope.tableSessionId)
            statement.setString(3, key)
        }
        val equivalent =
            fixture.submit(
                idempotencyKey = key,
                items =
                    listOf(
                        originalItems[1].copy(cartLineRef = "different-base-ref"),
                        originalItems[0].copy(
                            cartLineRef = "different-option-ref",
                            preferenceNote = "  less ice  ",
                        ),
                    ),
                comment = "  serve now  ",
            )

        assertTrue(equivalent.idempotencyReplay)
        assertEquals(created.batchId, equivalent.batchId)
        assertEquals(afterCreate, fixture.businessCounts())
        val replayedRecord = fixture.idempotencyRecord(key)
        assertEquals(newRecord.requestFingerprint, replayedRecord.requestFingerprint)
        assertEquals("{\"legacy\":true}", replayedRecord.responseSnapshot)
    }

    @Test
    fun `business payload and authoritative actor or tab changes fail closed`() {
        val fixture = fixture()
        val key = "fingerprint-mismatch"
        val originalItems = fixture.orderItems()
        fixture.submit(key, originalItems, comment = "serve now")
        val committedCounts = fixture.businessCounts()
        val cases =
            listOf(
                MismatchCase(
                    name = "quantity",
                    items = originalItems.toMutableList().also { it[0] = it[0].copy(qty = 3) },
                ),
                MismatchCase(
                    name = "item",
                    items = originalItems.toMutableList().also { it[1] = it[1].copy(itemId = fixture.item3Id) },
                ),
                MismatchCase(
                    name = "option",
                    items =
                        originalItems.toMutableList().also {
                            it[0] = it[0].copy(selectedOptionId = fixture.option2Id)
                        },
                ),
                MismatchCase(
                    name = "normalized preference note",
                    items = originalItems.toMutableList().also { it[0] = it[0].copy(preferenceNote = "more ice") },
                ),
                MismatchCase(name = "comment", items = originalItems, comment = "later"),
                MismatchCase(
                    name = "tab",
                    items = originalItems,
                    scope = fixture.primaryScope.copy(tabId = fixture.alternateTabId),
                ),
                MismatchCase(
                    name = "actor",
                    items = originalItems,
                    userId = ACTOR_TWO,
                ),
            )

        cases.forEach { case ->
            val error =
                assertFailsWith<OrderIdempotencyPayloadMismatchException>(case.name) {
                    fixture.submit(
                        idempotencyKey = key,
                        items = case.items,
                        comment = case.comment,
                        scope = case.scope ?: fixture.primaryScope,
                        userId = case.userId,
                    )
                }
            assertEquals(ApiErrorCodes.ORDER_IDEMPOTENCY_PAYLOAD_MISMATCH, error.code, case.name)
            assertEquals(committedCounts, fixture.businessCounts(), case.name)
        }
    }

    @Test
    fun `multiple legacy actor rows for one session key are unverifiable without writes`() {
        val fixture = fixture()
        val key = "fingerprint-legacy-logical-duplicate"
        val temporarySecondKey = "fingerprint-legacy-logical-duplicate-second"
        val items = fixture.orderItems()
        fixture.submit(key, items, comment = "same payload")
        fixture.submit(
            idempotencyKey = temporarySecondKey,
            items = items,
            comment = "same payload",
            userId = ACTOR_TWO,
        )
        fixture.execute(
            """
            UPDATE guest_batch_idempotency
            SET request_fingerprint = NULL
            WHERE table_session_id = ? AND user_id = ? AND idempotency_key = ?
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, fixture.primaryScope.tableSessionId)
            statement.setLong(2, ACTOR_ONE)
            statement.setString(3, key)
        }
        fixture.execute(
            """
            UPDATE guest_batch_idempotency
            SET idempotency_key = ?, request_fingerprint = NULL
            WHERE table_session_id = ? AND user_id = ? AND idempotency_key = ?
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, key)
            statement.setLong(2, fixture.primaryScope.tableSessionId)
            statement.setLong(3, ACTOR_TWO)
            statement.setString(4, temporarySecondKey)
        }
        val committedCounts = fixture.businessCounts()
        assertEquals(2, fixture.countIdempotencyRows(key))
        assertEquals(0, fixture.countFingerprintedIdempotencyRows(key))

        val error =
            assertFailsWith<OrderIdempotencyReplayUnverifiableException> {
                fixture.submit(key, items, comment = "same payload")
            }

        assertEquals(ApiErrorCodes.ORDER_IDEMPOTENCY_REPLAY_UNVERIFIABLE, error.code)
        assertEquals(committedCounts, fixture.businessCounts())
        assertEquals(2, fixture.countIdempotencyRows(key))
        assertEquals(0, fixture.countFingerprintedIdempotencyRows(key))
    }

    @Test
    fun `same key in another table session is an independent operation`() {
        val fixture = fixture()
        val key = "fingerprint-two-sessions"
        val items = fixture.orderItems()

        val first = fixture.submit(key, items, comment = null, scope = fixture.primaryScope)
        val second = fixture.submit(key, items, comment = null, scope = fixture.secondaryScope)
        val secondReplay = fixture.submit(key, items, comment = null, scope = fixture.secondaryScope)

        assertFalse(first.idempotencyReplay)
        assertFalse(second.idempotencyReplay)
        assertNotEquals(first.batchId, second.batchId)
        assertTrue(secondReplay.idempotencyReplay)
        assertEquals(second.batchId, secondReplay.batchId)
        assertEquals(2, fixture.countRows("orders"))
        assertEquals(2, fixture.countRows("order_batches"))
        assertEquals(2, fixture.countRows("guest_batch_idempotency"))
    }

    @Test
    fun `known exact replay bypasses gift and changed menu validation`() {
        val fixture = fixture(withGiftDecisionService = true)
        val key = "fingerprint-known-stale-option"
        val items = fixture.orderItems().take(1)
        val created = fixture.submit(key, items, comment = null)
        val committedCounts = fixture.businessCounts()

        fixture.execute("UPDATE menu_item_options SET is_available = FALSE WHERE id = ?") { statement ->
            statement.setLong(1, fixture.option1Id)
        }
        val unavailableReplay =
            fixture.submit(
                idempotencyKey = key,
                items = items,
                comment = null,
                selectedGiftChoices = mapOf(999L to 1000L),
            )
        assertTrue(unavailableReplay.idempotencyReplay)
        assertEquals(created.batchId, unavailableReplay.batchId)

        fixture.execute("DELETE FROM menu_item_options WHERE id = ?") { statement ->
            statement.setLong(1, fixture.option1Id)
        }
        val deletedReplay =
            fixture.submit(
                idempotencyKey = key,
                items = items,
                comment = null,
                selectedGiftChoices = mapOf(999L to 1000L),
            )

        assertTrue(deletedReplay.idempotencyReplay)
        assertEquals(created.batchId, deletedReplay.batchId)
        assertEquals(committedCounts, fixture.businessCounts())
    }

    @Test
    fun `legacy immutable batch facts reject mismatch then lazily upgrade exact replay`() {
        val fixture = fixture()
        val key = "fingerprint-legacy"
        val items = fixture.orderItems()
        val created = fixture.submit(key, items, comment = "legacy comment")
        fixture.execute(
            """
            UPDATE guest_batch_idempotency
            SET request_fingerprint = NULL
            WHERE table_session_id = ? AND idempotency_key = ?
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, fixture.primaryScope.tableSessionId)
            statement.setString(2, key)
        }
        fixture.execute(
            "UPDATE order_batch_items SET is_excluded = TRUE, item_status = 'CANCELED' WHERE order_batch_id = ?",
        ) { statement -> statement.setLong(1, created.batchId) }
        val committedCounts = fixture.businessCounts()

        val mismatch =
            assertFailsWith<OrderIdempotencyPayloadMismatchException> {
                fixture.submit(
                    idempotencyKey = key,
                    items = items.toMutableList().also { it[0] = it[0].copy(qty = 3) },
                    comment = "legacy comment",
                )
            }
        assertEquals(ApiErrorCodes.ORDER_IDEMPOTENCY_PAYLOAD_MISMATCH, mismatch.code)
        assertNull(fixture.idempotencyRecord(key).requestFingerprint)
        assertEquals(committedCounts, fixture.businessCounts())

        val replay = fixture.submit(key, items, comment = "legacy comment")

        assertTrue(replay.idempotencyReplay)
        assertEquals(created.batchId, replay.batchId)
        assertTrue(
            assertNotNull(fixture.idempotencyRecord(key).requestFingerprint).matches(Regex("v1:[0-9a-f]{64}")),
        )
        assertEquals(committedCounts, fixture.businessCounts())
    }

    @Test
    fun `legacy option snapshot with deleted option id is unverifiable`() {
        val fixture = fixture()
        val key = "fingerprint-legacy-deleted-option"
        val items = fixture.orderItems().take(1)
        val created = fixture.submit(key, items, comment = null)
        fixture.execute(
            """
            UPDATE guest_batch_idempotency
            SET request_fingerprint = NULL
            WHERE table_session_id = ? AND idempotency_key = ?
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, fixture.primaryScope.tableSessionId)
            statement.setString(2, key)
        }
        fixture.execute("DELETE FROM menu_item_options WHERE id = ?") { statement ->
            statement.setLong(1, fixture.option1Id)
        }
        val committedCounts = fixture.businessCounts()

        val error =
            assertFailsWith<OrderIdempotencyReplayUnverifiableException> {
                fixture.submit(key, items, comment = null)
            }

        assertEquals(ApiErrorCodes.ORDER_IDEMPOTENCY_REPLAY_UNVERIFIABLE, error.code)
        assertNull(fixture.idempotencyRecord(key).requestFingerprint)
        assertEquals(created.batchId, fixture.idempotencyRecord(key).batchId)
        assertEquals(committedCounts, fixture.businessCounts())
    }

    private fun fixture(withGiftDecisionService: Boolean = false): Fixture {
        val jdbcUrl = migratedJdbcUrl()
        val dataSource = dataSource(jdbcUrl)
        val repository =
            OrdersRepository(
                dataSource = dataSource,
                giftDecisionScopeTokenService =
                    if (withGiftDecisionService) {
                        GiftDecisionScopeTokenService("idempotency-test-signing-secret")
                    } else {
                        null
                    },
            )
        return seedFixture(
            dataSource = dataSource,
            repository = repository,
        )
    }

    private fun migratedJdbcUrl(): String {
        val jdbcUrl =
            "jdbc:h2:mem:guest-idempotency-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
                "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        Flyway.configure()
            .dataSource(jdbcUrl, "sa", "")
            .locations("classpath:db/migration/h2")
            .load()
            .migrate()
        return jdbcUrl
    }

    private fun dataSource(jdbcUrl: String): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(jdbcUrl)
            user = "sa"
            password = ""
        }

    private fun seedFixture(
        dataSource: JdbcDataSource,
        repository: OrdersRepository,
    ): Fixture =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.executeUpdate(
                    "INSERT INTO users (telegram_user_id, first_name) VALUES (?, ?)",
                ) { statement ->
                    statement.setLong(1, ACTOR_ONE)
                    statement.setString(2, "Actor one")
                }
                connection.executeUpdate(
                    "INSERT INTO users (telegram_user_id, first_name) VALUES (?, ?)",
                ) { statement ->
                    statement.setLong(1, ACTOR_TWO)
                    statement.setString(2, "Actor two")
                }
                val venueId =
                    connection.generatedId(
                        """
                        INSERT INTO venues (name, city, address, status)
                        VALUES ('Venue', 'City', 'Address', 'PUBLISHED')
                        """.trimIndent(),
                    )
                val primaryTableId = connection.seedTable(venueId, 1)
                val primarySessionId = connection.seedSession(venueId, primaryTableId)
                val primaryTabId = connection.seedTab(venueId, primarySessionId, "PERSONAL", ACTOR_ONE)
                connection.seedTabMember(primaryTabId, ACTOR_ONE, "OWNER")
                connection.seedTabMember(primaryTabId, ACTOR_TWO, "MEMBER")
                val alternateTabId = connection.seedTab(venueId, primarySessionId, "SHARED", ACTOR_ONE)
                connection.seedTabMember(alternateTabId, ACTOR_ONE, "OWNER")

                val secondaryTableId = connection.seedTable(venueId, 2)
                val secondarySessionId = connection.seedSession(venueId, secondaryTableId)
                val secondaryTabId = connection.seedTab(venueId, secondarySessionId, "PERSONAL", ACTOR_ONE)
                connection.seedTabMember(secondaryTabId, ACTOR_ONE, "OWNER")

                val categoryId =
                    connection.generatedId(
                        "INSERT INTO menu_categories (venue_id, name, sort_order) VALUES (?, 'Category', 0)",
                    ) { statement -> statement.setLong(1, venueId) }
                val item1Id = connection.seedItem(venueId, categoryId, "Item one")
                val item2Id = connection.seedItem(venueId, categoryId, "Item two")
                val item3Id = connection.seedItem(venueId, categoryId, "Item three")
                val option1Id = connection.seedOption(venueId, item1Id, "Option one")
                val option2Id = connection.seedOption(venueId, item1Id, "Option two")
                connection.commit()
                Fixture(
                    dataSource = dataSource,
                    repository = repository,
                    venueId = venueId,
                    primaryScope = Scope(primaryTableId, primarySessionId, primaryTabId),
                    secondaryScope = Scope(secondaryTableId, secondarySessionId, secondaryTabId),
                    alternateTabId = alternateTabId,
                    item1Id = item1Id,
                    item2Id = item2Id,
                    item3Id = item3Id,
                    option1Id = option1Id,
                    option2Id = option2Id,
                )
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }

    private fun Connection.seedTable(
        venueId: Long,
        tableNumber: Int,
    ): Long =
        generatedId("INSERT INTO venue_tables (venue_id, table_number, is_active) VALUES (?, ?, TRUE)") {
            it.setLong(1, venueId)
            it.setInt(2, tableNumber)
        }

    private fun Connection.seedSession(
        venueId: Long,
        tableId: Long,
    ): Long =
        generatedId(
            """
            INSERT INTO table_sessions (venue_id, table_id, started_at, last_activity_at, expires_at, status)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE')
            """.trimIndent(),
        ) { statement ->
            val now = Instant.now()
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.setTimestamp(3, Timestamp.from(now))
            statement.setTimestamp(4, Timestamp.from(now))
            statement.setTimestamp(5, Timestamp.from(now.plusSeconds(7200)))
        }

    private fun Connection.seedTab(
        venueId: Long,
        tableSessionId: Long,
        type: String,
        ownerUserId: Long,
    ): Long =
        generatedId(
            "INSERT INTO tab (venue_id, table_session_id, type, owner_user_id, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
        ) { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableSessionId)
            statement.setString(3, type)
            statement.setLong(4, ownerUserId)
        }

    private fun Connection.seedTabMember(
        tabId: Long,
        userId: Long,
        role: String,
    ) {
        executeUpdate("INSERT INTO tab_member (tab_id, user_id, role) VALUES (?, ?, ?)") { statement ->
            statement.setLong(1, tabId)
            statement.setLong(2, userId)
            statement.setString(3, role)
        }
    }

    private fun Connection.seedItem(
        venueId: Long,
        categoryId: Long,
        name: String,
    ): Long =
        generatedId(
            """
            INSERT INTO menu_items (venue_id, category_id, name, price_minor, currency, is_available)
            VALUES (?, ?, ?, 100, 'RUB', TRUE)
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, categoryId)
            statement.setString(3, name)
        }

    private fun Connection.seedOption(
        venueId: Long,
        itemId: Long,
        name: String,
    ): Long =
        generatedId(
            """
            INSERT INTO menu_item_options (venue_id, item_id, name, price_delta_minor, is_available, sort_order)
            VALUES (?, ?, ?, 10, TRUE, 0)
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, itemId)
            statement.setString(3, name)
        }

    private fun Connection.generatedId(
        sql: String,
        bind: (PreparedStatement) -> Unit = {},
    ): Long =
        prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            bind(statement)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Expected a generated key" }
                keys.getLong(1)
            }
        }

    private fun Connection.executeUpdate(
        sql: String,
        bind: (PreparedStatement) -> Unit,
    ) {
        prepareStatement(sql).use { statement ->
            bind(statement)
            statement.executeUpdate()
        }
    }

    private data class Fixture(
        val dataSource: JdbcDataSource,
        val repository: OrdersRepository,
        val venueId: Long,
        val primaryScope: Scope,
        val secondaryScope: Scope,
        val alternateTabId: Long,
        val item1Id: Long,
        val item2Id: Long,
        val item3Id: Long,
        val option1Id: Long,
        val option2Id: Long,
    ) {
        fun orderItems(): List<OrderBatchItemInput> =
            listOf(
                OrderBatchItemInput(
                    cartLineRef = "option-line",
                    itemId = item1Id,
                    qty = 2,
                    selectedOptionId = option1Id,
                    preferenceNote = "less ice",
                ),
                OrderBatchItemInput(
                    cartLineRef = "base-line",
                    itemId = item2Id,
                    qty = 1,
                ),
            )

        fun submit(
            idempotencyKey: String,
            items: List<OrderBatchItemInput>,
            comment: String?,
            scope: Scope = primaryScope,
            userId: Long = ACTOR_ONE,
            expectedPreviewFingerprint: String? = null,
            selectedGiftChoices: Map<Long, Long> = emptyMap(),
            venueZoneIdProvider: ((Connection) -> ZoneId)? = null,
        ): CreatedOrderBatch =
            transaction { connection ->
                assertNotNull(
                    repository.createGuestOrderBatch(
                        connection = connection,
                        tableId = scope.tableId,
                        venueId = venueId,
                        tableSessionId = scope.tableSessionId,
                        userId = userId,
                        idempotencyKey = idempotencyKey,
                        tabId = scope.tabId,
                        comment = comment,
                        items = items,
                        expectedPreviewFingerprint = expectedPreviewFingerprint,
                        selectedGiftChoices = selectedGiftChoices,
                        venueZoneIdProvider = venueZoneIdProvider,
                    ),
                )
            }

        fun businessCounts(): BusinessCounts =
            BusinessCounts(
                orders = countRows("orders"),
                batches = countRows("order_batches"),
                batchItems = countRows("order_batch_items"),
                selectedOptions = countRows("order_batch_item_options"),
                idempotency = countRows("guest_batch_idempotency"),
                analytics = countRows("analytics_events"),
                outbox = countRows("telegram_outbox"),
            )

        fun countRows(table: String): Int =
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                        check(rs.next())
                        rs.getInt(1)
                    }
                }
            }

        fun countIdempotencyRows(idempotencyKey: String): Int =
            countIdempotencyRows(idempotencyKey, requireFingerprint = false)

        fun countFingerprintedIdempotencyRows(idempotencyKey: String): Int =
            countIdempotencyRows(idempotencyKey, requireFingerprint = true)

        private fun countIdempotencyRows(
            idempotencyKey: String,
            requireFingerprint: Boolean,
        ): Int =
            dataSource.connection.use { connection ->
                val fingerprintPredicate = if (requireFingerprint) "AND request_fingerprint IS NOT NULL" else ""
                connection.prepareStatement(
                    """
                    SELECT COUNT(*)
                    FROM guest_batch_idempotency
                    WHERE table_session_id = ? AND idempotency_key = ?
                    $fingerprintPredicate
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, primaryScope.tableSessionId)
                    statement.setString(2, idempotencyKey)
                    statement.executeQuery().use { rs ->
                        check(rs.next())
                        rs.getInt(1)
                    }
                }
            }

        fun idempotencyRecord(idempotencyKey: String): IdempotencyRecord =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT batch_id, request_fingerprint, response_snapshot
                    FROM guest_batch_idempotency
                    WHERE table_session_id = ? AND idempotency_key = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, primaryScope.tableSessionId)
                    statement.setString(2, idempotencyKey)
                    statement.executeQuery().use { rs ->
                        check(rs.next())
                        IdempotencyRecord(
                            batchId = rs.getLong("batch_id"),
                            requestFingerprint = rs.getString("request_fingerprint"),
                            responseSnapshot = rs.getString("response_snapshot"),
                        )
                    }
                }
            }

        fun execute(
            sql: String,
            bind: (PreparedStatement) -> Unit,
        ) {
            transaction { connection ->
                connection.prepareStatement(sql).use { statement ->
                    bind(statement)
                    statement.executeUpdate()
                }
            }
        }

        private fun <T> transaction(block: (Connection) -> T): T =
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val result = block(connection)
                    connection.commit()
                    result
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                }
            }
    }

    private data class Scope(
        val tableId: Long,
        val tableSessionId: Long,
        val tabId: Long,
    )

    private data class MismatchCase(
        val name: String,
        val items: List<OrderBatchItemInput>,
        val comment: String? = "serve now",
        val scope: Scope? = null,
        val userId: Long = ACTOR_ONE,
    )

    private data class BusinessCounts(
        val orders: Int,
        val batches: Int,
        val batchItems: Int,
        val selectedOptions: Int,
        val idempotency: Int,
        val analytics: Int,
        val outbox: Int,
    )

    private data class IdempotencyRecord(
        val batchId: Long,
        val requestFingerprint: String?,
        val responseSnapshot: String?,
    )

    private companion object {
        const val ACTOR_ONE = 11_001L
        const val ACTOR_TWO = 11_002L
    }
}
