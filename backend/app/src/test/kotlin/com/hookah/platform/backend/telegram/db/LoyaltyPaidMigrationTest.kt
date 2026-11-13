package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.test.PostgresTestEnv
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoyaltyPaidMigrationTest {
    @Test
    fun `PostgreSQL nonempty cutover preserves legacy balances SQL checksums and history`() {
        PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase(), migrate = false).use { ds ->
            verify(ds, "postgresql", "135", "136")
        }
    }

    @Test
    fun `H2 nonempty cutover preserves legacy balances SQL checksums and history`() {
        val ds =
            JdbcDataSource().apply {
                setURL(
                    "jdbc:h2:mem:loyalty-migrate-" +
                        "${UUID.randomUUID()};MODE=PostgreSQL;DATABASE" +
                        "_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                )
                user = "sa"
                password = ""
            }
        verify(ds, "h2", "136", "137")
    }

    @Test
    fun `PostgreSQL unit correction upgrade preserves nonempty issued expenses bills and receipts`() {
        PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase(), migrate = false).use {
            verifyCompensationUpgrade(it, "postgresql", "136")
        }
    }

    @Test
    fun `H2 unit correction upgrade preserves nonempty issued expenses bills and receipts`() {
        val ds =
            JdbcDataSource().apply {
                setURL(
                    "jdbc:h2:mem:unit-migrate-${UUID.randomUUID()}" +
                        ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CL" +
                        "OSE_DELAY=-1",
                )
                user = "sa"
                password = ""
            }
        verifyCompensationUpgrade(ds, "h2", "137")
    }

    private fun verifyCompensationUpgrade(
        ds: javax.sql.DataSource,
        dialect: String,
        previous: String,
    ) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/$dialect").target(previous).load().migrate()
        ds.connection.use { c ->
            c.le("INSERT INTO users (telegram_user_id) VALUES (9100001),(9100002)")
            val venue = c.li("INSERT INTO venues(name,city,address,status) VALUES ('Upgrade','T','T','PUBLISHED')")
            val table = c.li("INSERT INTO venue_tables(venue_id,table_number) VALUES (?,1)", venue)
            val session =
                c.li(
                    "INSERT INTO " +
                        "table_sessions(venue_id,table_id,status,start" +
                        "ed_at,last_activity_at,expires_at) VALUES " +
                        "(?,?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMES" +
                        "TAMP,TIMESTAMP '2099-01-01 00:00:00')",
                    venue,
                    table,
                )
            val shift =
                c.li(
                    "INSERT INTO " +
                        "venue_accounting_shifts(venue_id,status,open_" +
                        "slot,timezone,opened_at) VALUES " +
                        "(?,'OPEN',1,'UTC',CURRENT_TIMESTAMP)",
                    venue,
                )
            val program =
                c.li(
                    "INSERT INTO " +
                        "loyalty_programs(venue_id,program_type,status" +
                        ",nth_value,created_by_user_id) VALUES " +
                        "(?,'NTH_HOOKAH_FREE','ACTIVE',3,9100001)",
                    venue,
                )
            val category =
                c.li(
                    "INSERT INTO " +
                        "menu_categories(venue_id,name,category_type) " +
                        "VALUES (?,'Hookahs','HOOKAH')",
                    venue,
                )
            val item =
                c.li(
                    "INSERT INTO " +
                        "menu_items(venue_id,category_id,name,price_mi" +
                        "nor,currency) VALUES " +
                        "(?,?,'Original',1000,'RUB')",
                    venue,
                    category,
                )
            for (kind in listOf("earn", "reward")) c.le(
                "INSERT INTO " +
                    "loyalty_program_${kind}_targets(program_id,ta" +
                    "rget_type,semantic_type) VALUES " +
                    "(?,'CATEGORY_TYPE','HOOKAH')",
                program,
            )
            c.le(
                "INSERT INTO " +
                    "loyalty_terms_versions(id,program_id,nth_valu" +
                    "e,origin) VALUES " +
                    "('upgrade-terms',?,3,'PAYMENT_POLICY')",
                program,
            )
            for (kind in listOf("EARN", "REWARD")) c.le(
                "INSERT INTO " +
                    "loyalty_terms_targets(version_id,target_kind," +
                    "target_type,semantic_type) VALUES " +
                    "('upgrade-terms',?,'CATEGORY_TYPE','HOOKAH')",
                kind,
            )
            c.le(
                "INSERT INTO " +
                    "loyalty_accumulation_cycles(id,program_id,ven" +
                    "ue_id,user_id,terms_id,state) VALUES " +
                    "('upgrade-cycle',?,?,9100002,'upgrade-terms'," +
                    "'AVAILABLE')",
                program,
                venue,
            )
            c.le(
                "INSERT INTO " +
                    "guest_loyalty_progress(program_id,venue_id,us" +
                    "er_id,rewards_available) VALUES " +
                    "(?,?,9100002,1)",
                program,
                venue,
            )

            fun order(qty: Int): List<Long> {
                val order =
                    c.li(
                        "INSERT INTO " +
                            "orders(venue_id,table_id,table_session_id,sta" +
                            "tus,accounting_shift_id) VALUES " +
                            "(?,?,?,'CLOSED',?)",
                        venue,
                        table,
                        session,
                        shift,
                    )
                val batch =
                    c.li(
                        "INSERT INTO " +
                            "order_batches(order_id,author_user_id,source," +
                            "status) VALUES " +
                            "(?,9100002,'MINIAPP','DELIVERED')",
                        order,
                    )
                val line =
                    c.li(
                        "INSERT INTO " +
                            "order_batch_items(order_batch_id,menu_item_id" +
                            ",qty,base_unit_price_minor_snapshot,item_name" +
                            "_snapshot,currency_snapshot,loyalty_semantic_" +
                            "type_snapshot,fulfilled_at,fulfilled_by_user_" +
                            "id) VALUES " +
                            "(?,?,?,1000,'Original','RUB','HOOKAH',TIMESTA" +
                            "MP '2026-09-30 10:00:00',9100001)",
                        batch,
                        item,
                        qty,
                    )
                return listOf(order, batch, line)
            }
            val source = order(2)
            val quote = order(3)
            val units =
                (1..2).map { index ->
                    val id =
                        c.li(
                            "INSERT INTO " +
                                "loyalty_paid_units(cycle_id,order_id,batch_id" +
                                ",order_batch_item_id,unit_index,payment_fact)" +
                                " VALUES ('upgrade-cycle',?,?,?,?,?)",
                            source[0],
                            source[1],
                            source[2],
                            index,
                            "sale:order:${source[0]}:v:1",
                        )
                    c.le(
                        "INSERT INTO " +
                            "guest_loyalty_ledger(program_id,venue_id,user" +
                            "_id,event_type,delta_progress,delta_rewards,o" +
                            "rder_id,batch_id,order_batch_item_id,dedupe_k" +
                            "ey,origin,cycle_id,payment_fact,paid_unit_id)" +
                            " VALUES " +
                            "(?,?,9100002,'EARN',1,?,?,?,?,?,'PAYMENT_POLI" +
                            "CY','upgrade-cycle',?,?)",
                        program, venue, if (index == 2) 1 else 0, source[0], source[1], source[2],
                        "upgrade-earn:$index", "sale:order:${source[0]}:v:1", id,
                    )
                    id
                }
            c.autoCommit = false
            LoyaltyRepository(
                ds,
            ).applyRedemptionForBatch(
                c,
                quote[0],
                quote[1],
                venue,
                9100002,
                listOf(
                    LoyaltyCheckoutItem(
                        quote[2],
                        item,
                        "Original",
                        3,
                        1000,
                        "RUB",
                        com.hookah.platform.backend.miniapp.venue.menu.MenuSemanticType.HOOKAH,
                    ),
                ),
            )
            c.commit()
            c.autoCommit = true
            val redemption = c.lq("SELECT id FROM loyalty_redemptions") { it.getLong(1) }.single()
            c.le(
                "UPDATE loyalty_redemptions SET " +
                    "status='APPLIED',issued_at=TIMESTAMP " +
                    "'2026-09-30 " +
                    "10:01:00',issued_by_user_id=9100001,delivery_" +
                    "version=1 WHERE id=?",
                redemption,
            )
            c.le("UPDATE loyalty_accumulation_cycles SET state='APPLIED' WHERE id='upgrade-cycle'")
            c.le("UPDATE guest_loyalty_progress SET rewards_reserved=0")
            c.le("UPDATE loyalty_paid_units SET active=FALSE,generation=2 WHERE id=?", units.first())
            c.le(
                "INSERT INTO " +
                    "guest_loyalty_ledger(program_id,venue_id,user" +
                    "_id,event_type,delta_progress,delta_rewards,o" +
                    "rder_id,dedupe_key,origin,cycle_id,paid_unit_" +
                    "id) VALUES " +
                    "(?,?,9100002,'ADJUST',-1,0,?,'upgrade-compens" +
                    "ated','PAYMENT_POLICY','upgrade-cycle',?)",
                program,
                venue,
                source[0],
                units.first(),
            )
            c.le(
                "INSERT INTO " +
                    "loyalty_correction_expenses(cycle_id,active_s" +
                    "lot,venue_id,order_id,redemption_id,amount_mi" +
                    "nor,reason,created_at) VALUES " +
                    "('upgrade-cycle',1,?,?,?,1000,'Историческая " +
                    "компенсация',TIMESTAMP '2026-10-01 12:00:00')",
                venue,
                source[0],
                redemption,
            )
            for (o in listOf(source, quote)) {
                val data =
                    com.hookah.platform.backend.miniapp.venue.accounting.AccountingSale(
                        "order:${o[0]}",
                        table,
                        "1",
                        "DIGITAL",
                        orderId = o[0],
                        createdAt = "2026-09-30T10:00:00Z",
                        lines =
                            listOf(
                                com.hookah.platform.backend.miniapp.venue.accounting.AccountingLine(
                                    "item:${o[2]}",
                                    "Original",
                                    if (o == source)2 else 3,
                                    1000,
                                    menuItemId = item,
                                    source = "DIGITAL",
                                    baseDiscountMinor = if (o == quote)1000 else 0,
                                ),
                            ),
                        payments =
                            listOf(
                                com.hookah.platform.backend.miniapp.venue.accounting.AccountingPayment(
                                    "receipt:${o[0]}",
                                    "CASH",
                                    2000,
                                    "2026-09-30T10:02:00Z",
                                ),
                            ),
                    )
                c.le(
                    "INSERT INTO " +
                        "venue_accounting_sales(id,shift_id,snapshot_j" +
                        "son) VALUES (?,?,?)",
                    data.id,
                    shift,
                    kotlinx.serialization.json.Json.encodeToString(
                        com.hookah.platform.backend.miniapp.venue.accounting.AccountingSale.serializer(),
                        data,
                    ),
                )
            }

            fun snapshot(): List<List<String?>> =
                listOf(
                    "SELECT id,status,accounting_shift_id,updated_at FROM orders ORDER BY id",
                    "SELECT " +
                        "id,qty,base_unit_price_minor_snapshot,item_na" +
                        "me_snapshot,currency_snapshot FROM " +
                        "order_batch_items ORDER BY id",
                    "SELECT id,snapshot_json FROM venue_accounting_sales ORDER BY id",
                    "SELECT " +
                        "id,event_type,delta_progress,delta_rewards,de" +
                        "dupe_key,paid_unit_id FROM " +
                        "guest_loyalty_ledger ORDER BY id",
                    "SELECT " +
                        "program_id,user_id,progress_count,rewards_ava" +
                        "ilable,rewards_reserved,updated_at FROM " +
                        "guest_loyalty_progress",
                    "SELECT id,amount_minor,reason,created_at,resolved_at FROM loyalty_correction_expenses ORDER BY id",
                ).flatMap { sql -> c.lq(sql) { r -> (1..r.metaData.columnCount).map { r.getString(it) } } }
            val before = snapshot()
            val history =
                c.lq(
                    "SELECT \"version\",\"script\",\"checksum\" " +
                        "FROM \"flyway_schema_history\" ORDER BY " +
                        "\"installed_rank\"",
                ) { r -> (1..3).map { r.getString(it) } }
            val current = Flyway.configure().dataSource(ds).locations("classpath:db/migration/$dialect").load()
            val expectedTail =
                if (dialect == "postgresql") {
                    listOf(
                        "V137__loyalty_unit_corrections.sql",
                        "V138__analytics_transition_facts.sql",
                        "V139__order_closed_fact.sql",
                    )
                } else {
                    listOf(
                        "V138__loyalty_unit_corrections.sql",
                        "V139__analytics_transition_facts.sql",
                        "V140__order_closed_fact.sql",
                    )
                }
            assertEquals(expectedTail, current.info().pending().map { it.script })
            assertEquals(expectedTail.size, current.migrate().migrationsExecuted)
            current.validate()
            assertEquals(if (dialect == "postgresql") "139" else "140", current.info().current().version.version)
            assertEquals(before, snapshot())
            assertEquals(
                history,
                c.lq(
                    "SELECT \"version\",\"script\",\"checksum\" " +
                        "FROM \"flyway_schema_history\" ORDER BY " +
                        "\"installed_rank\"",
                ) { r -> (1..3).map { r.getString(it) } }.dropLast(expectedTail.size),
            )
            assertEquals(
                listOf("VENUE_COMPENSATION"),
                c.lq("SELECT funding_basis FROM loyalty_redemptions") { it.getString(1) },
            )
            assertEquals(
                listOf(1),
                c.lq(
                    "SELECT delivery_version_snapshot FROM " +
                        "loyalty_correction_expenses",
                ) { it.getInt(1) },
            )
            assertTrue(c.lq("SELECT id FROM loyalty_unit_corrections") { it.getLong(1) }.isEmpty())
            assertTrue(c.lq("SELECT redemption_id FROM loyalty_compensation_cancellations") { it.getLong(1) }.isEmpty())
            assertEquals(0, current.migrate().migrationsExecuted)
        }
    }

    private fun verify(
        ds: javax.sql.DataSource,
        dialect: String,
        previous: String,
        next: String,
    ) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/$dialect").target(previous).load().migrate()
        ds.connection.use { c ->
            c.le("INSERT INTO users (telegram_user_id) VALUES (9100001),(9100002)")
            val venue =
                c.li(
                    "INSERT INTO venues " +
                        "(name,city,address,status) VALUES " +
                        "('Legacy','Test','Test','PUBLISHED')",
                )
            val program =
                c.li(
                    "INSERT INTO loyalty_programs " +
                        "(venue_id,program_type,status,nth_value,creat" +
                        "ed_by_user_id) VALUES " +
                        "(?,'NTH_HOOKAH_FREE','ACTIVE',5,9100001)",
                    venue,
                )
            c.le(
                "INSERT INTO loyalty_program_earn_targets " +
                    "(program_id,target_type,semantic_type) " +
                    "VALUES (?,'CATEGORY_TYPE','HOOKAH')",
                program,
            )
            c.le(
                "INSERT INTO loyalty_program_reward_targets " +
                    "(program_id,target_type,semantic_type) " +
                    "VALUES (?,'CATEGORY_TYPE','HOOKAH')",
                program,
            )
            c.le(
                "INSERT INTO guest_loyalty_progress " +
                    "(program_id,venue_id,user_id,progress_count,r" +
                    "ewards_available,rewards_reserved) VALUES " +
                    "(?,?,9100002,3,2,0)",
                program,
                venue,
            )
            c.le(
                "INSERT INTO guest_loyalty_ledger " +
                    "(program_id,venue_id,user_id,event_type,delta" +
                    "_progress,delta_rewards,dedupe_key) VALUES " +
                    "(?,?,9100002,'EARN',11,2,'legacy-closed-fact'" +
                    ")",
                program,
                venue,
            )
            val before =
                c.lq(
                    "SELECT " +
                        "program_id,user_id,progress_count,rewards_ava" +
                        "ilable,rewards_reserved,updated_at FROM " +
                        "guest_loyalty_progress",
                ) { r ->
                    (1..6).map {
                        r.getString(it)
                    }
                }
            val history =
                c.lq(
                    "SELECT \"version\", \"script\", \"checksum\" " +
                        "FROM \"flyway_schema_history\" ORDER BY " +
                        "\"installed_rank\"",
                ) { r -> (1..3).map { r.getString(it) } }
            val flyway =
                Flyway.configure().dataSource(
                    ds,
                ).locations("classpath:db/migration/$dialect").target(next).load()
            assertEquals(1, flyway.migrate().migrationsExecuted)
            flyway.validate()
            assertEquals(
                before,
                c.lq(
                    "SELECT " +
                        "program_id,user_id,progress_count,rewards_ava" +
                        "ilable,rewards_reserved,updated_at FROM " +
                        "guest_loyalty_progress",
                ) { r ->
                    (1..6).map {
                        r.getString(it)
                    }
                },
            )
            assertEquals(
                history,
                c.lq(
                    "SELECT \"version\", \"script\", \"checksum\" " +
                        "FROM \"flyway_schema_history\" ORDER BY " +
                        "\"installed_rank\"",
                ) { r -> (1..3).map { r.getString(it) } }.dropLast(1),
            )
            assertEquals(listOf("LEGACY"), c.lq("SELECT origin FROM guest_loyalty_ledger") { it.getString(1) })
            assertEquals(
                listOf(3 to 2),
                c.lq(
                    "SELECT progress_initial,rewards_remaining " +
                        "FROM loyalty_legacy_balances",
                ) { it.getInt(1) to it.getInt(2) },
            )
            assertEquals(listOf(3), c.lq("SELECT legacy_units FROM loyalty_accumulation_cycles") { it.getInt(1) })
            assertEquals(
                listOf("LEGACY_CUTOVER"),
                c.lq("SELECT origin FROM loyalty_terms_versions") { it.getString(1) },
            )
            assertTrue(c.lq("SELECT id FROM loyalty_paid_units") { it.getLong(1) }.isEmpty())
            assertEquals(0, flyway.migrate().migrationsExecuted)
        }
    }
}
