package com.hookah.platform.backend.miniapp.venue.accounting

import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountingCreateResultPostgresTest : AccountingCreateResultContract() {
    override fun database() = PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase())

    @Test fun `concurrent CREATE receipts remain distinct and ledger failure rolls back`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                val inputs = (1..8).map { splitManual() }
                val results =
                    kotlinx.coroutines.coroutineScope {
                        inputs.mapIndexed {
                                i,
                                body,
                            ->
                            async { repo.execute(1, if (i % 2 == 0) 101 else 102, body) }
                        }.map { it.await() }
                    }
                assertEquals(8, results.map { it.createdSaleId }.distinct().size)
                for ((i, body) in inputs.withIndex()) assertEquals(
                    results[i].createdSaleId,
                    repo.execute(1, if (i % 2 == 0) 101 else 102, body).createdSaleId,
                )
                assertEquals(8, repo.state(1, 101).current!!.totals.salesCount)
                val counts =
                    ds.connection.use {
                            c ->
                        listOf(
                            "venue_accounting_sales",
                            "venue_accounting_commands",
                            "venue_accounting_events",
                            "audit_log",
                        ).map {
                            c.count(it)
                        }
                    }
                SqlBarrier(ds, "INSERT INTO venue_accounting_commands", after = true, hold = false, fail = true).use {
                        broken ->
                    kotlin.test.assertFailsWith<com.hookah.platform.backend.api.DatabaseUnavailableException> {
                        AccountingRepository(broken).execute(1, 101, splitManual())
                    }
                }
                assertEquals(
                    counts,
                    ds.connection.use {
                            c ->
                        listOf(
                            "venue_accounting_sales",
                            "venue_accounting_commands",
                            "venue_accounting_events",
                            "audit_log",
                        ).map {
                            c.count(it)
                        }
                    },
                )
            }
        }
}

class AccountingCreateResultH2Test : AccountingCreateResultContract() {
    override fun database() =
        com.zaxxer.hikari.HikariDataSource(
            com.zaxxer.hikari.HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:create_result_${java.util.UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
                username = "sa"
                password = ""
            },
        ).also {
            org.flywaydb.core.Flyway.configure().dataSource(
                it,
            ).locations("classpath:db/migration/h2").load().migrate()
        }
}

abstract class AccountingCreateResultContract {
    abstract fun database(): com.zaxxer.hikari.HikariDataSource

    @Test fun `CREATE identity survives reload and identical concurrent sale`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                val create = splitManual()
                val initial = repo.execute(1, 101, create)
                val originalId = initial.sales.single().id
                assertEquals(originalId, initial.createdSaleId)
                val reloaded = repo.state(1, 101).current!!
                assertEquals(originalId, reloaded.sales.single().id)
                val concurrent = repo.execute(1, 102, splitManual().copy(shiftId = initial.shiftId))
                assertEquals(2, concurrent.sales.size)
                val replay = AccountingRepository(ds).execute(1, 101, create)
                val result = Json.parseToJsonElement(Json.encodeToString(replay)).jsonObject
                assertEquals(originalId, result["createdSaleId"]?.jsonPrimitive?.content)
                assertEquals(2, replay.totals.salesCount)
            }
        }

    @Test fun `CREATE receipt is actor payload and venue bound with fresh access check and no duplicate`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                val create = splitManual()
                val original = repo.execute(1, 101, create)
                kotlin.test.assertFailsWith<com.hookah.platform.backend.api.AccountingConflictException> {
                    repo.execute(1, 102, create)
                }
                kotlin.test.assertFailsWith<com.hookah.platform.backend.api.AccountingConflictException> {
                    repo.execute(1, 101, create.copy(reason = "changed"))
                }
                kotlin.test.assertFailsWith<com.hookah.platform.backend.api.ForbiddenException> {
                    repo.execute(
                        2,
                        101,
                        create,
                    )
                }
                ds.connection.use {
                    it.createStatement().executeUpdate(
                        "DELETE FROM venue_members WHERE venue_id=1 AND user_id=101",
                    )
                }
                kotlin.test.assertFailsWith<com.hookah.platform.backend.api.ForbiddenException> {
                    repo.execute(
                        1,
                        101,
                        create,
                    )
                }
                assertEquals(1, repo.detail(1, 102, original.shiftId).totals.salesCount)
            }
        }

    @Test fun `legacy identity stays unknown and receipt preserves frozen report and fingerprint`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                val create = splitManual()
                val original = repo.execute(1, 101, create)
                val fingerprint =
                    ds.connection.use {
                            c ->
                        c.createStatement().executeQuery("SELECT fingerprint FROM venue_accounting_commands").use {
                            it.next()
                            it.getString(1)
                        }
                    }
                val encoded = Json { encodeDefaults = true }.encodeToString(create)
                assertEquals(
                    java.security.MessageDigest.getInstance(
                        "SHA-256",
                    ).digest(encoded.toByteArray()).joinToString("") {
                        "%02x".format(it)
                    },
                    fingerprint,
                )
                val plain = repo.detail(1, 101, original.shiftId)
                assertEquals(original.copy(createdSaleId = null), plain)
                val closed =
                    repo.execute(
                        1,
                        101,
                        command(
                            "CLOSE",
                            plain.shiftId,
                        ).copy(expectedReportHash = plain.reportHash, acknowledgeUnpaid = true),
                    )
                assertEquals(original.createdSaleId, repo.execute(1, 101, create).createdSaleId)
                kotlin.test.assertNull(closed.createdSaleId)
                ds.connection.use { c ->
                    c.createStatement().executeQuery("SELECT final_report FROM venue_accounting_shifts").use {
                        it.next()
                        kotlin.test.assertFalse(it.getString(1).contains("createdSaleId"))
                    }
                    c.createStatement().executeUpdate("UPDATE venue_accounting_commands SET created_sale_id=NULL")
                }
                kotlin.test.assertNull(repo.execute(1, 101, create).createdSaleId)
            }
        }

    @Test fun `CREATE audit failure rolls back identity sale shift and history`() =
        runBlocking {
            database().use { ds ->
                seedAccounting(ds)
                val audit = com.hookah.platform.backend.miniapp.venue.AuditLogRepository(ds)
                val broken =
                    com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter {
                            c,
                            actor,
                            action,
                            type,
                            id,
                            payload,
                        ->
                        if (action == "VENUE_ACCOUNTING_CREATE") {
                            throw java.sql.SQLException(
                                "Synthetic CREATE audit failure",
                            )
                        }
                        audit.appendJson(c, actor, action, type, id, payload)
                    }
                kotlin.test.assertFailsWith<com.hookah.platform.backend.api.DatabaseUnavailableException> {
                    AccountingRepository(ds, broken).execute(1, 101, splitManual())
                }
                ds.connection.use {
                        c ->
                    for (table in listOf(
                        "venue_accounting_sales",
                        "venue_accounting_shifts",
                        "venue_accounting_commands",
                        "venue_accounting_events",
                        "audit_log",
                    )) assertEquals(0, c.count(table), table)
                }
            }
        }
}
