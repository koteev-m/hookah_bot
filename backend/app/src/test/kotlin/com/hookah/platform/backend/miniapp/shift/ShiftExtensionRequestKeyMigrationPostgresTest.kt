package com.hookah.platform.backend.miniapp.shift

import com.hookah.platform.backend.test.PostgresTestEnv
import org.flywaydb.core.Flyway
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShiftExtensionRequestKeyMigrationPostgresTest {
    @Test
    fun `review09 additive migration preserves the legacy request charge and original replay`() {
        PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase(), migrate = false).use { ds ->
            Flyway.configure().dataSource(
                ds,
            ).locations("classpath:db/migration/postgresql").target("145").load().migrate()
            ds.connection.use { connection ->
                connection.createStatement().use { sql ->
                    listOf(
                        "INSERT INTO users(telegram_user_id) VALUES (501)",
                        "INSERT INTO venues(id,name,city,address,status) " +
                            "OVERRIDING SYSTEM VALUE VALUES (101,'Venue','City','Address','PUBLISHED')",
                        "INSERT INTO venue_tables(id,venue_id,table_number) " +
                            "OVERRIDING SYSTEM VALUE VALUES (201,101,1)",
                        "INSERT INTO table_sessions" +
                            "(id,venue_id,table_id,started_at,last_activity_at,expires_at,status) " +
                            "OVERRIDING SYSTEM VALUE " +
                            "VALUES (301,101,201,now(),now(),now()+interval '1 hour','ACTIVE')",
                        "INSERT INTO tab(id,venue_id,table_session_id,type,owner_user_id,status) " +
                            "OVERRIDING SYSTEM VALUE VALUES (401,101,301,'PERSONAL',501,'ACTIVE')",
                        "INSERT INTO orders(id,venue_id,table_id,table_session_id,status,display_number,display_date)" +
                            " OVERRIDING SYSTEM VALUE " +
                            "VALUES (601,101,201,301,'ACTIVE',1,CURRENT_DATE)",
                        """
                        INSERT INTO shift_extension_requests(
                            id,venue_id,table_session_id,table_id,tab_id,order_id,requested_by_user_id,status,
                            duration_minutes,price_minor,current_orderable_until,requested_until,comment,idempotency_key
                        ) OVERRIDING SYSTEM VALUE
                        VALUES (701,101,301,201,401,601,501,'APPROVED',60,5000,now(),now(),'legacy','legacy-key')
                        """.trimIndent(),
                        """
                        INSERT INTO order_service_charges(
                            order_id,venue_id,table_session_id,tab_id,source,source_request_id,
                            label,unit_price_minor,total_minor
                        ) VALUES (601,101,301,401,'SHIFT_EXTENSION',701,'Продление',5000,5000)
                        """.trimIndent(),
                    ).forEach { sql.executeUpdate(it) }
                }
            }
            Flyway.configure().dataSource(
                ds,
            ).locations("classpath:db/migration/postgresql").target("146").load().migrate()
            ds.connection.use { connection ->
                connection.createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO shift_extension_request_keys " +
                            "(venue_id,table_session_id,requested_by_user_id,idempotency_key," +
                            "visit_generation,request_id,request_comment) " +
                            "VALUES(101,301,501,'old-alias',17,701,'legacy')",
                    )
                }
            }
            Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate()
            ds.connection.use { connection ->
                val replay =
                    ShiftExtensionRepository(ds).findExactReplay(
                        connection,
                        CreateShiftExtensionRequestCommand(
                            101, 301, 201, 401, 601, 501, Instant.now(), "legacy-key", "legacy",
                        ),
                    )
                assertEquals(701L, assertNotNull(replay).id)
                assertEquals(ShiftExtensionRequestStatus.APPROVED, replay.status)
                val alias =
                    ShiftExtensionRepository(ds).findExactReplay(
                        connection,
                        CreateShiftExtensionRequestCommand(
                            101, 301, 201, 401, 601, 501, Instant.now(), "old-alias", "legacy", visitGeneration = 17,
                        ),
                    )
                assertEquals(replay, alias)
                connection.createStatement().use { sql ->
                    sql.executeQuery("SELECT COUNT(*),SUM(total_minor) FROM order_service_charges").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(1, rows.getInt(1))
                        assertEquals(5000L, rows.getLong(2))
                    }
                    sql.executeQuery("SELECT idempotency_key, terms_revision FROM shift_extension_request_keys").use {
                            rows ->
                        assertTrue(rows.next())
                        assertEquals("old-alias", rows.getString(1))
                        assertEquals(null, rows.getString(2))
                        assertTrue(!rows.next())
                    }
                }
            }
        }
    }
}
