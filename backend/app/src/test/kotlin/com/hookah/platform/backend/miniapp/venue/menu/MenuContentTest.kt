package com.hookah.platform.backend.miniapp.venue.menu

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.MenuFeaturedOperationConflictException
import com.hookah.platform.backend.api.MenuFeaturedStaleException
import com.hookah.platform.backend.miniapp.guest.db.GuestMenuRepository
import com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter
import com.hookah.platform.backend.test.PostgresTestEnv
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MenuContentH2Test : MenuContentContract(false)

class MenuContentPostgresTest : MenuContentContract(true)

abstract class MenuContentContract(private val postgres: Boolean) {
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val ds =
            if (postgres) {
                PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase())
            } else {
                JdbcDataSource().apply {
                    setURL(
                        "jdbc:h2:mem:menu-content-${UUID.randomUUID()};MODE=PostgreSQL;" +
                            "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                    )
                    user = "sa"
                    password = ""
                    Flyway.configure().dataSource(this).locations("classpath:db/migration/h2").load().migrate()
                }
            }
        try {
            val f = Fixture(ds)
            f.sql("INSERT INTO users (telegram_user_id) VALUES (9001001)")
            f.sql("INSERT INTO venues (name, city, address, status) VALUES ('Menu', 'Test', 'Test', 'PUBLISHED')")
            f.venueId = f.number("SELECT id FROM venues")
            f.categoryId =
                f.repository.createCategory(
                    f.venueId, "Меню", actorUserId = 9001001, source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                ).id
            block(f)
        } finally {
            (ds as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `description normalization unicode boundaries legacy edits and clear`() =
        runBlocking {
            fixture {
                val legacy = create()
                assertNull(legacy.description)
                val item = create("  Вкус\n\t манго\u00a0 и  мяты  ")
                assertEquals("Вкус манго и мяты", item.description)
                assertEquals(item.description, update(item.id, name = "Новое имя")?.description)
                assertEquals(item.description, update(item.id, available = false)?.description)
                update(item.id, available = true)
                val unicode = "🍋".repeat(500)
                assertEquals(unicode, update(item.id, description = unicode, specified = true)?.description)
                assertFailsWith<InvalidInputException> {
                    update(
                        item.id,
                        description = unicode + "🍋",
                        specified = true,
                    )
                }
                assertFailsWith<InvalidInputException> { create("я".repeat(501)) }
                assertEquals(unicode, repository.getMenu(venueId).single().items.first { it.id == item.id }.description)
                assertNull(update(item.id, description = " \t\n\u00a0", specified = true)?.description)
                update(item.id, description = "<img src=x onerror=alert(1)> & _text_", specified = true)
                assertEquals(
                    "<img src=x onerror=alert(1)> & _text_",
                    guest.categories().flatMap {
                        it.items
                    }.first { it.id == item.id }.description,
                )
                assertNull(update(item.id, description = null, specified = true)?.description)
                assertFalse(auditPayloads().contains("onerror"))
            }
        }

    @Test
    fun `description no-op and audit failure preserve committed data`() =
        runBlocking {
            fixture {
                val item = create("Private description")
                update(item.id, description = "New private text", specified = true)
                val count = number("SELECT COUNT(*) FROM audit_log WHERE action = 'MENU_ITEM_DESCRIPTION_CHANGED'")
                val timestamp = text("SELECT CAST(updated_at AS VARCHAR) FROM menu_items WHERE id = ${item.id}")
                update(item.id, description = " New  private text ", specified = true)
                assertEquals(
                    count,
                    number("SELECT COUNT(*) FROM audit_log WHERE action = 'MENU_ITEM_DESCRIPTION_CHANGED'"),
                )
                assertEquals(
                    timestamp,
                    text("SELECT CAST(updated_at AS VARCHAR) FROM menu_items WHERE id = ${item.id}"),
                )
                val failing =
                    VenueMenuRepository(
                        ds,
                        TransactionalAuditLogWriter {
                                _,
                                _,
                                _,
                                _,
                                _,
                                _,
                            ->
                            throw SQLException("audit failure")
                        },
                    )
                assertFailsWith<DatabaseUnavailableException> {
                    failing.updateItem(
                        venueId, item.id, null, "Must rollback", null, null, null, 9001001,
                        MenuItemAvailabilitySource.VENUE_MINI_APP,
                        description = "Must rollback", descriptionSpecified = true,
                    )
                }
                val saved = repository.getMenu(venueId).single().items.single()
                assertEquals(item.name, saved.name)
                assertEquals("New private text", saved.description)
                assertFalse(auditPayloads().contains("private", ignoreCase = true))
            }
        }

    @Test
    fun `featured zero one six seven ownership no-op and stale version`() =
        runBlocking {
            fixture {
                val ids = (1..7).map { create().id }
                assertEquals(emptyList(), featured())
                val noop = repository.updateFeatured(venueId, emptyList(), 0, operationId(), 9001001)
                assertEquals(emptyList(), noop.state.itemIds)
                assertEquals(0L, noop.state.version)
                assertEquals(0L, number("SELECT COUNT(*) FROM audit_log WHERE action = 'MENU_FEATURED_CHANGED'"))
                val pinId = operationId()
                repository.updateFeatured(venueId, ids.take(1), 0, pinId, 9001001)
                assertTrue(repository.updateFeatured(venueId, ids.take(1), 0, pinId, 9001001).replayed)
                assertEquals(1L, number("SELECT COUNT(*) FROM audit_log WHERE action = 'MENU_FEATURED_CHANGED'"))
                val six = ids.take(6).reversed()
                repository.updateFeatured(venueId, six, 1, operationId(), 9001001)
                assertEquals(six, featured())
                assertFailsWith<InvalidInputException> {
                    repository.updateFeatured(
                        venueId,
                        ids,
                        2,
                        operationId(),
                        9001001,
                    )
                }
                assertFailsWith<InvalidInputException> {
                    repository.updateFeatured(venueId, listOf(ids[0], ids[0]), 2, operationId(), 9001001)
                }
                assertFailsWith<InvalidInputException> {
                    repository.updateFeatured(venueId, listOf(Long.MAX_VALUE), 2, operationId(), 9001001)
                }
                assertFailsWith<MenuFeaturedStaleException> {
                    repository.updateFeatured(venueId, ids.takeLast(1), 1, operationId(), 9001001)
                }
                assertEquals(six, featured())
                sql("INSERT INTO venues (name, city, address, status) VALUES ('Other', 'Other', 'Other', 'PUBLISHED')")
                val other = number("SELECT MAX(id) FROM venues")
                assertFailsWith<InvalidInputException> {
                    repository.updateFeatured(other, ids.take(1), 0, operationId(), 9001001)
                }
                repository.updateFeatured(venueId, emptyList(), 2, operationId(), 9001001)
                assertEquals(emptyList(), featured())
                assertEquals(3L, version())
            }
        }

    @Test
    fun `featured audit failure rolls order back atomically`() =
        runBlocking {
            fixture {
                val ids = (1..3).map { create().id }
                repository.updateFeatured(venueId, ids, 0, operationId(), 9001001)
                val failing =
                    VenueMenuRepository(
                        ds,
                        TransactionalAuditLogWriter {
                                _,
                                _,
                                _,
                                _,
                                _,
                                _,
                            ->
                            throw SQLException("audit failure")
                        },
                    )
                assertFailsWith<DatabaseUnavailableException> {
                    failing.updateFeatured(
                        venueId,
                        ids.reversed(),
                        1,
                        operationId(),
                        9001001,
                    )
                }
                assertEquals(ids, featured())
                assertEquals(1L, version())
                assertEquals(1L, number("SELECT COUNT(*) FROM menu_featured_operations"))
                assertEquals(1L, number("SELECT COUNT(*) FROM audit_log WHERE action = 'MENU_FEATURED_CHANGED'"))
            }
        }

    @Test
    fun `stoplist category visibility move and delete retain a coherent selection`() =
        runBlocking {
            fixture {
                val item = create("Описание")
                repository.updateFeatured(venueId, listOf(item.id), 0, operationId(), 9001001)
                assertEquals(listOf(item.id), guestFeatured())
                update(item.id, available = false)
                assertEquals(emptyList(), guestFeatured())
                assertEquals(listOf(item.id), featured())
                update(item.id, available = true)
                sql("UPDATE menu_categories SET is_active = false WHERE id = $categoryId")
                assertEquals(emptyList(), guestFeatured())
                sql("UPDATE menu_categories SET is_active = true WHERE id = $categoryId")
                assertEquals(listOf(item.id), guestFeatured())
                val target =
                    repository.createCategory(
                        venueId,
                        "Другая",
                        actorUserId = 9001001,
                        source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                    )
                repository.updateItem(
                    venueId, item.id, target.id, null, null, null, null, 9001001,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                )
                assertEquals(listOf(item.id), guestFeatured())
                repository.deleteItem(venueId, item.id, 9001001, MenuItemDeleteSource.VENUE_MINI_APP)
                assertEquals(emptyList(), featured())
                assertEquals(emptyList(), guestFeatured())
            }
        }

    @Test
    fun `concurrent selections block on real PostgreSQL version row and have one CAS winner`() =
        runBlocking {
            fixture {
                val ids = (1..6).map { create().id }
                repository.updateFeatured(venueId, ids, 0, operationId(), 9001001)
                val results =
                    coroutineScope {
                        ds.connection.use { blocker ->
                            blocker.autoCommit = false
                            val blockerPid =
                                if (postgres) {
                                    blocker.createStatement().use { statement ->
                                        statement.executeQuery("SELECT pg_backend_pid()").use { rs ->
                                            rs.next()
                                            rs.getInt(1)
                                        }
                                    }
                                } else {
                                    null
                                }
                            if (postgres) {
                                blocker.createStatement().use {
                                    it.executeQuery(
                                        "SELECT menu_featured_version FROM venues " +
                                            "WHERE id = $venueId FOR NO KEY UPDATE",
                                    ).close()
                                }
                            }
                            val attempts =
                                listOf(ids.reversed(), ids.drop(1) + ids.first()).map { desired ->
                                    async(Dispatchers.IO) {
                                        runCatching {
                                            repository.updateFeatured(
                                                venueId,
                                                desired,
                                                1,
                                                operationId(),
                                                9001001,
                                            )
                                        }
                                    }
                                }
                            try {
                                if (blockerPid != null) {
                                    withTimeout(10000) {
                                        while (true) {
                                            blocker.createStatement().use {
                                                it.execute(
                                                    "SELECT pg_stat_clear_snapshot()",
                                                )
                                            }
                                            val blocked =
                                                blocker.createStatement().use { statement ->
                                                    statement.executeQuery(
                                                        "SELECT count(*) FROM pg_stat_activity " +
                                                            "WHERE $blockerPid = ANY(pg_blocking_pids(pid)) " +
                                                            "AND query LIKE 'SELECT menu_featured_version%'",
                                                    ).use { rs ->
                                                        rs.next()
                                                        rs.getInt(1)
                                                    }
                                                }
                                            if (blocked > 0) break
                                            delay(25)
                                        }
                                    }
                                }
                            } finally {
                                blocker.commit()
                            }
                            attempts.awaitAll()
                        }
                    }
                assertEquals(1, results.count { it.isSuccess })
                assertTrue(results.single { it.isFailure }.exceptionOrNull() is MenuFeaturedStaleException)
                assertEquals(results.single { it.isSuccess }.getOrThrow().state.itemIds, featured())
                assertEquals(2L, version())
                assertEquals(2L, number("SELECT COUNT(*) FROM audit_log WHERE action = 'MENU_FEATURED_CHANGED'"))
            }
        }

    @Test
    fun `concurrent legacy edits preserve description and featured metadata`() =
        runBlocking {
            fixture {
                val item = create("Исходное")
                repository.updateFeatured(venueId, listOf(item.id), 0, operationId(), 9001001)
                coroutineScope {
                    listOf(
                        async(Dispatchers.IO) { update(item.id, description = "Новое", specified = true) },
                        async(Dispatchers.IO) { update(item.id, name = "Новое имя") },
                    ).awaitAll()
                }
                val saved = repository.getMenu(venueId).single().items.single()
                assertEquals("Новое", saved.description)
                assertEquals("Новое имя", saved.name)
                assertEquals(1, saved.featuredOrder)
                assertEquals(10000L, saved.priceMinor)
            }
        }

    @Test
    fun `concurrent deletion and reorder cannot resurrect or retain a deleted featured item`() =
        runBlocking {
            fixture {
                val ids = (1..3).map { create().id }
                repository.updateFeatured(venueId, ids, 0, operationId(), 9001001)
                val outcomes =
                    coroutineScope {
                        val reorder =
                            async(Dispatchers.IO) {
                                runCatching {
                                    repository.updateFeatured(
                                        venueId,
                                        ids.reversed(),
                                        1,
                                        operationId(),
                                        9001001,
                                    )
                                }
                            }
                        val delete =
                            async(Dispatchers.IO) {
                                runCatching {
                                    repository.deleteItem(venueId, ids[1], 9001001, MenuItemDeleteSource.VENUE_MINI_APP)
                                }
                            }
                        reorder.await() to delete.await()
                    }
                outcomes.first.exceptionOrNull()?.let {
                    assertTrue(it is InvalidInputException || it is MenuFeaturedStaleException)
                }
                if (outcomes.second.isFailure) {
                    assertTrue(outcomes.second.exceptionOrNull() is DatabaseUnavailableException)
                    assertTrue(repository.deleteItem(venueId, ids[1], 9001001, MenuItemDeleteSource.VENUE_MINI_APP))
                } else {
                    assertTrue(outcomes.second.getOrThrow())
                }
                assertFalse(repository.getMenu(venueId).flatMap { it.items }.any { it.id == ids[1] })
                assertEquals(setOf(ids[0], ids[2]), featured().toSet())
                assertEquals(featured(), guestFeatured())
                assertEquals(1L, number("SELECT COUNT(*) FROM audit_log WHERE action = 'MENU_ITEM_DELETED'"))
            }
        }

    @Test
    fun `append only migration preserves nonempty legacy descriptions and adds no recommendations`() =
        runBlocking {
            val ds =
                if (postgres) {
                    PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase(), migrate = false)
                } else {
                    JdbcDataSource().apply {
                        setURL(
                            "jdbc:h2:mem:menu-upgrade-${UUID.randomUUID()};MODE=PostgreSQL;" +
                                "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        )
                        user = "sa"
                        password = ""
                    }
                }
            try {
                val location = "classpath:db/migration/" + if (postgres) "postgresql" else "h2"
                val prior = if (postgres) "133" else "134"
                Flyway.configure().dataSource(ds).locations(location).target(prior).load().migrate()
                val f = Fixture(ds)
                f.sql("INSERT INTO venues (name, city, address, status) VALUES ('Menu', 'Test', 'Test', 'PUBLISHED')")
                val venueId = f.number("SELECT id FROM venues")
                f.sql("INSERT INTO menu_categories (venue_id, name) VALUES ($venueId, 'Legacy')")
                val categoryId = f.number("SELECT id FROM menu_categories")
                f.sql(
                    "INSERT INTO menu_items (venue_id, category_id, name, price_minor, currency, description) " +
                        "VALUES ($venueId, $categoryId, 'Legacy', 12345, 'RUB', 'Сохранить без изменений')",
                )
                val flyway = Flyway.configure().dataSource(ds).locations(location).load()
                assertEquals(2, flyway.migrate().migrationsExecuted)
                flyway.validate()
                assertEquals(if (postgres) "135" else "136", flyway.info().current().version.version)
                val item = VenueMenuRepository(ds).getMenu(venueId).single().items.single()
                assertEquals("Сохранить без изменений", item.description)
                assertNull(item.featuredOrder)
                assertEquals(12345L, item.priceMinor)
                assertEquals(0, flyway.migrate().migrationsExecuted)
            } finally {
                (ds as? AutoCloseable)?.close()
            }
        }

    @Test
    fun `ABA replay after lost response and fresh application pool never overwrites newer state`() =
        runBlocking {
            fixture {
                val a = listOf(create().id)
                val b = listOf(create().id)
                repository.updateFeatured(venueId, a, 0, operationId(), 9001001)
                val lostOperation = operationId()
                val applied = repository.updateFeatured(venueId, b, 1, lostOperation, 9001001)
                assertEquals(2L, applied.appliedVersion)
                repository.updateFeatured(venueId, a, 2, operationId(), 9001001)
                val config =
                    ds.connection.use { connection ->
                        HikariConfig().apply {
                            jdbcUrl = connection.metaData.url
                            username = connection.metaData.userName
                            password = if (postgres) "hookah" else ""
                            maximumPoolSize = 2
                        }
                    }
                HikariDataSource(config).use { freshPool ->
                    val freshApplication = VenueMenuRepository(freshPool)
                    val replay = freshApplication.updateFeatured(venueId, b, 1, lostOperation, 9001001)
                    assertTrue(replay.replayed)
                    assertEquals(2L, replay.appliedVersion)
                    assertEquals(MenuFeaturedState(a, 3), replay.state)
                    assertFailsWith<MenuFeaturedOperationConflictException> {
                        freshApplication.updateFeatured(venueId, a, 1, lostOperation, 9001001)
                    }
                    assertFailsWith<MenuFeaturedOperationConflictException> {
                        freshApplication.updateFeatured(venueId, b, 3, lostOperation, 9001001)
                    }
                    assertFailsWith<MenuFeaturedStaleException> {
                        freshApplication.updateFeatured(venueId, a, 1, operationId(), 9001001)
                    }
                }
                assertEquals(a, featured())
                assertEquals(3L, version())
                assertEquals(3L, number("SELECT COUNT(*) FROM menu_featured_operations"))
                assertEquals(3L, number("SELECT COUNT(*) FROM audit_log WHERE action = 'MENU_FEATURED_CHANGED'"))
            }
        }

    @Test
    fun `empty selection keeps monotonic version and no-op receipts are durable without audits`() =
        runBlocking {
            fixture {
                val ids = listOf(create().id)
                val emptyOperation = operationId()
                repository.updateFeatured(venueId, emptyList(), 0, emptyOperation, 9001001)
                assertEquals(0L, version())
                repository.updateFeatured(venueId, ids, 0, operationId(), 9001001)
                repository.updateFeatured(venueId, emptyList(), 1, operationId(), 9001001)
                val replay = VenueMenuRepository(ds).updateFeatured(venueId, emptyList(), 0, emptyOperation, 9001001)
                assertTrue(replay.replayed)
                assertEquals(0L, replay.appliedVersion)
                assertEquals(MenuFeaturedState(emptyList(), 2), replay.state)
                assertFailsWith<MenuFeaturedStaleException> {
                    repository.updateFeatured(venueId, emptyList(), 0, operationId(), 9001001)
                }
                val noOpId = operationId()
                repository.updateFeatured(venueId, emptyList(), 2, noOpId, 9001001)
                repository.updateFeatured(venueId, emptyList(), 2, noOpId, 9001001)
                assertEquals(2L, version())
                assertEquals(4L, number("SELECT COUNT(*) FROM menu_featured_operations"))
                assertEquals(2L, number("SELECT COUNT(*) FROM audit_log WHERE action = 'MENU_FEATURED_CHANGED'"))
                repository.updateFeatured(venueId, ids, 2, operationId(), 9001001)
                assertEquals(3L, version())
            }
        }

    @Test
    fun `simultaneous replay of one operation writes exactly one audit version and receipt`() =
        runBlocking {
            fixture {
                val ids = listOf(create().id)
                val operation = operationId()
                val results =
                    coroutineScope {
                        (1..2).map {
                            async(Dispatchers.IO) { repository.updateFeatured(venueId, ids, 0, operation, 9001001) }
                        }.awaitAll()
                    }
                assertEquals(1, results.count { it.replayed })
                assertEquals(1, results.count { !it.replayed })
                assertTrue(results.all { it.state == MenuFeaturedState(ids, 1) })
                assertEquals(1L, number("SELECT COUNT(*) FROM menu_featured_operations"))
                assertEquals(1L, number("SELECT COUNT(*) FROM audit_log WHERE action = 'MENU_FEATURED_CHANGED'"))
            }
        }

    @Test
    fun `menu read snapshot pairs version and ordered ids during concurrent writes`() =
        runBlocking {
            fixture {
                val a = create().id
                val b = create().id
                coroutineScope {
                    val writer =
                        async(Dispatchers.IO) {
                            repeat(20) { index ->
                                repository.updateFeatured(
                                    venueId,
                                    listOf(if (index % 2 == 0) a else b),
                                    index.toLong(),
                                    operationId(),
                                    9001001,
                                )
                            }
                        }
                    repeat(20) {
                        val snapshot = repository.getMenuSnapshot(venueId)
                        val selected =
                            snapshot.categories.flatMap { it.items }.filter { it.featuredOrder != null }.map { it.id }
                        val expected =
                            if (snapshot.featuredVersion == 0L) {
                                emptyList()
                            } else {
                                listOf(if (snapshot.featuredVersion % 2 == 1L) a else b)
                            }
                        assertEquals(expected, selected)
                    }
                    writer.await()
                }
                assertEquals(20L, version())
            }
        }

    @Test
    fun `pinned deletion advances version and old completed operation stays a replay`() =
        runBlocking {
            fixture {
                val item = create("Описание")
                val operation = operationId()
                repository.updateFeatured(venueId, listOf(item.id), 0, operation, 9001001)
                update(item.id, name = "Другое имя", available = false)
                assertEquals(1L, version())
                repository.deleteItem(venueId, item.id, 9001001, MenuItemDeleteSource.VENUE_MINI_APP)
                assertEquals(2L, version())
                val replay = repository.updateFeatured(venueId, listOf(item.id), 0, operation, 9001001)
                assertTrue(replay.replayed)
                assertEquals(MenuFeaturedState(emptyList(), 2), replay.state)
                assertFailsWith<MenuFeaturedStaleException> {
                    repository.updateFeatured(venueId, emptyList(), 1, operationId(), 9001001)
                }
                assertEquals(1L, number("SELECT COUNT(*) FROM audit_log WHERE action = 'MENU_FEATURED_CHANGED'"))
            }
        }

    @Test
    fun `version migration preserves pins descriptions prices and empty ledger`() =
        runBlocking {
            val ds =
                if (postgres) {
                    PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase(), migrate = false)
                } else {
                    JdbcDataSource().apply {
                        setURL(
                            "jdbc:h2:mem:menu-version-upgrade-${UUID.randomUUID()};MODE=PostgreSQL;" +
                                "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        )
                        user = "sa"
                        password = ""
                    }
                }
            try {
                val location = "classpath:db/migration/" + if (postgres) "postgresql" else "h2"
                Flyway.configure().dataSource(
                    ds,
                ).locations(location).target(if (postgres) "134" else "135").load().migrate()
                val f = Fixture(ds)
                f.sql("INSERT INTO venues (name, city, address, status) VALUES ('Menu', 'Test', 'Test', 'PUBLISHED')")
                val venueId = f.number("SELECT id FROM venues")
                f.sql("INSERT INTO menu_categories (venue_id, name) VALUES ($venueId, 'Legacy')")
                val categoryId = f.number("SELECT id FROM menu_categories")
                f.sql(
                    "INSERT INTO menu_items (venue_id, category_id, name, price_minor, currency, " +
                        "description, featured_order) " +
                        "VALUES ($venueId, $categoryId, 'Legacy', 12345, 'RUB', 'Сохранить', 6)",
                )
                val before = VenueMenuRepository(ds).getMenu(venueId)
                val flyway = Flyway.configure().dataSource(ds).locations(location).load()
                assertEquals(1, flyway.migrate().migrationsExecuted)
                flyway.validate()
                val after = VenueMenuRepository(ds).getMenuSnapshot(venueId)
                assertEquals(before, after.categories)
                assertEquals(0L, after.featuredVersion)
                assertEquals(0L, f.number("SELECT COUNT(*) FROM menu_featured_operations"))
                assertEquals(0, flyway.migrate().migrationsExecuted)
            } finally {
                (ds as? AutoCloseable)?.close()
            }
        }

    private fun operationId() = UUID.randomUUID().toString()

    private class Fixture(val ds: DataSource) {
        val repository = VenueMenuRepository(ds)
        val guest = GuestMenuRepository(ds)
        var venueId = 0L
        var categoryId = 0L

        suspend fun create(description: String? = null): VenueMenuItem =
            assertNotNull(
                repository.createItem(
                    venueId, categoryId, "Товар", 10000, "RUB", true, 9001001,
                    MenuItemCreateSource.VENUE_MINI_APP, description = description,
                ),
            )

        suspend fun update(
            id: Long,
            name: String? = null,
            available: Boolean? = null,
            description: String? = null,
            specified: Boolean = false,
        ) = repository.updateItem(
            venueId, id, null, name, null, null, available, 9001001, MenuItemAvailabilitySource.TELEGRAM_BOT,
            description = description, descriptionSpecified = specified,
        )

        suspend fun GuestMenuRepository.categories() = getMenu(venueId).categories

        fun version() = number("SELECT menu_featured_version FROM venues WHERE id = $venueId")

        suspend fun featured() =
            repository.getMenu(venueId).flatMap { it.items }.filter { it.featuredOrder != null }
                .sortedBy { it.featuredOrder }.map { it.id }

        suspend fun guestFeatured() =
            guest.categories().flatMap { it.items }.filter { it.featuredOrder != null }
                .sortedBy { it.featuredOrder }.map { it.id }

        fun sql(sql: String) {
            ds.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        }

        fun number(sql: String): Long =
            ds.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery(sql).use {
                        it.next()
                        it.getLong(1)
                    }
                }
            }

        fun text(sql: String): String =
            ds.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery(sql).use {
                        it.next()
                        it.getString(1)
                    }
                }
            }

        fun auditPayloads() = text("SELECT COALESCE(MAX(payload_json), '') FROM audit_log")
    }
}
