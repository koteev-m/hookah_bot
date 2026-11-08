package com.hookah.platform.backend.miniapp.venue.accounting

import com.hookah.platform.backend.test.PostgresTestEnv
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountingMigrationTest {
    @Test
    fun `H2 forward migration preserves old history and order snapshots`() {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:accounting_forward_${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
                username = "sa"
                password = ""
            },
        ).use { verifyForward(it, "h2", "131", "132") }
    }

    @Test
    fun `PostgreSQL forward migration preserves old history and order snapshots`() {
        PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase(), migrate = false).use {
            verifyForward(it, "postgresql", "130", "131")
        }
    }

    private fun verifyForward(
        ds: HikariDataSource,
        dialect: String,
        previous: String,
        next: String,
    ) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/$dialect").target(previous).load().migrate()
        seedAccounting(ds)
        seedDigitalOrder(ds, 31, closed = true)
        ds.connection.use { c ->
            val oldHistory = history(c)
            val oldOrder = order(c)
            val forward =
                Flyway.configure().dataSource(
                    ds,
                ).locations("classpath:db/migration/$dialect").target(next).load()
            assertEquals(1, forward.migrate().migrationsExecuted)
            assertEquals(next, forward.info().current().version.version)
            assertEquals(next, forward.configuration.target.version)
            forward.validate()
            assertEquals(oldHistory, history(c).dropLast(1))
            assertEquals(oldOrder, order(c))
            assertEquals(0, c.count("orders", "accounting_shift_id IS NOT NULL"))
            assertEquals(0, c.count("venue_accounting_shifts"))
            assertEquals(0, c.count("venue_accounting_events"))
            assertEquals(0, forward.migrate().migrationsExecuted)
        }
    }

    private fun history(c: Connection): List<List<String?>> =
        c.createStatement().use { s ->
            s.executeQuery(
                "SELECT \"installed_rank\", \"version\", \"script\", \"checksum\", \"success\" " +
                    "FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"",
            ).use { rs -> buildList { while (rs.next()) add((1..5).map { rs.getString(it) }) } }
        }

    private fun order(c: Connection): List<String?> =
        c.createStatement().use { s ->
            s.executeQuery(
                "SELECT o.id, o.status, o.created_at, i.item_name_snapshot, i.base_unit_price_minor_snapshot, " +
                    "i.currency_snapshot FROM orders o JOIN order_batches b ON b.order_id = o.id " +
                    "JOIN order_batch_items i ON i.order_batch_id = b.id WHERE o.id = 31",
            ).use { rs ->
                check(rs.next())
                (1..6).map { rs.getString(it) }
            }
        }
}
