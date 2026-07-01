package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.telegram.BillPaymentMethod
import com.hookah.platform.backend.telegram.StaffCallReason
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StaffCallRepositoryTest {
    @Test
    fun `staff call can move from new to ack to done`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("staff-call-status")
            val fixture = seedStaffCall(jdbcUrl, status = "NEW")
            val repository = StaffCallRepository(dataSource(jdbcUrl))

            val ack = repository.ackStaffCall(fixture.venueId, fixture.staffCallId, STAFF_USER_ID)
            assertNotNull(ack)
            assertTrue(ack.applied)
            assertEquals(StaffCallStatus.ACK, ack.status)
            assertEquals("Максим", ack.guestDisplayName)
            assertEquals("ACK", fetchStaffCallStatus(jdbcUrl, fixture.staffCallId))

            val done = repository.doneStaffCall(fixture.venueId, fixture.staffCallId, STAFF_USER_ID)
            assertNotNull(done)
            assertTrue(done.applied)
            assertEquals(StaffCallStatus.DONE, done.status)
            assertEquals("Максим", done.guestDisplayName)
            assertEquals("DONE", fetchStaffCallStatus(jdbcUrl, fixture.staffCallId))
        }

    @Test
    fun `duplicate staff call transitions return current state without update`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("staff-call-stale")
            val fixture = seedStaffCall(jdbcUrl, status = "ACK")
            val repository = StaffCallRepository(dataSource(jdbcUrl))

            val duplicateAck = repository.ackStaffCall(fixture.venueId, fixture.staffCallId, STAFF_USER_ID)
            assertNotNull(duplicateAck)
            assertFalse(duplicateAck.applied)
            assertEquals(StaffCallStatus.ACK, duplicateAck.status)
            assertEquals("Максим", duplicateAck.guestDisplayName)
            assertEquals("ACK", fetchStaffCallStatus(jdbcUrl, fixture.staffCallId))
        }

    @Test
    fun `listActiveByVenue returns guest display name`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("staff-call-list-guest-name")
            val fixture = seedStaffCall(jdbcUrl, status = "NEW")
            val repository = StaffCallRepository(dataSource(jdbcUrl))

            val calls = repository.listActiveByVenue(fixture.venueId, limit = 20)

            assertEquals(1, calls.size)
            assertEquals(fixture.staffCallId, calls.single().id)
            assertEquals("Максим", calls.single().guestDisplayName)
        }

    @Test
    fun `createGuestStaffCall stores created by user id for later guest display name`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("guest-staff-call-created-by")
            val fixture = seedStaffCall(jdbcUrl, status = "DONE")
            val repository = StaffCallRepository(dataSource(jdbcUrl))

            val created =
                repository.createGuestStaffCall(
                    venueId = fixture.venueId,
                    tableId = fixture.tableId,
                    tableSessionId = fixture.tableSessionId,
                    createdByUserId = GUEST_USER_ID,
                    reason = StaffCallReason.BILL,
                    comment = "Картой",
                )

            assertEquals(GUEST_USER_ID, fetchStaffCallCreatedByUserId(jdbcUrl, created.id))
            val calls = repository.listActiveByVenue(fixture.venueId, limit = 20)
            val createdCall = calls.single { it.id == created.id }
            assertEquals("Максим", createdCall.guestDisplayName)
        }

    @Test
    fun `listByGuestTableSession returns current guest call statuses only`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("staff-call-guest-status")
            val fixture = seedStaffCall(jdbcUrl, status = "DONE")
            val repository = StaffCallRepository(dataSource(jdbcUrl))

            val calls =
                repository.listByGuestTableSession(
                    venueId = fixture.venueId,
                    tableId = fixture.tableId,
                    tableSessionId = fixture.tableSessionId,
                    userId = GUEST_USER_ID,
                    limit = 20,
                )

            assertEquals(1, calls.size)
            assertEquals(fixture.staffCallId, calls.single().id)
            assertEquals("DONE", calls.single().status)
            assertEquals(fixture.tableId, calls.single().tableId)
        }

    @Test
    fun `completeActiveBillRequestsForOrder marks only related active bill requests done`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("staff-call-complete-bill")
            val fixture = seedStaffCall(jdbcUrl, status = "DONE")
            val repository = StaffCallRepository(dataSource(jdbcUrl))
            val orderId = seedOrder(jdbcUrl, fixture)
            val otherOrderId = seedOrder(jdbcUrl, fixture, status = "CLOSED")
            val newBillCallId =
                seedLinkedStaffCall(
                    jdbcUrl = jdbcUrl,
                    fixture = fixture,
                    orderId = orderId,
                    reason = StaffCallReason.BILL,
                    status = "NEW",
                )
            val ackBillCallId =
                seedLinkedStaffCall(
                    jdbcUrl = jdbcUrl,
                    fixture = fixture,
                    orderId = orderId,
                    reason = StaffCallReason.BILL,
                    status = "ACK",
                )
            val genericCallId =
                seedLinkedStaffCall(
                    jdbcUrl = jdbcUrl,
                    fixture = fixture,
                    orderId = orderId,
                    reason = StaffCallReason.COME,
                    status = "ACK",
                )
            val otherOrderBillCallId =
                seedLinkedStaffCall(
                    jdbcUrl = jdbcUrl,
                    fixture = fixture,
                    orderId = otherOrderId,
                    reason = StaffCallReason.BILL,
                    status = "ACK",
                )

            val completed = repository.completeActiveBillRequestsForOrder(fixture.venueId, orderId)

            assertEquals(setOf(newBillCallId, ackBillCallId), completed.map { it.result.staffCallId }.toSet())
            assertEquals(setOf(StaffCallStatus.NEW, StaffCallStatus.ACK), completed.map { it.fromStatus }.toSet())
            completed.forEach { completion ->
                assertTrue(completion.result.applied)
                assertEquals(StaffCallStatus.DONE, completion.result.status)
                assertEquals(StaffCallReason.BILL, completion.result.reason)
                assertEquals(orderId, completion.result.orderId)
            }
            assertEquals("DONE", fetchStaffCallStatus(jdbcUrl, newBillCallId))
            assertEquals("DONE", fetchStaffCallStatus(jdbcUrl, ackBillCallId))
            assertEquals("ACK", fetchStaffCallStatus(jdbcUrl, genericCallId))
            assertEquals("ACK", fetchStaffCallStatus(jdbcUrl, otherOrderBillCallId))
        }

    private fun migratedJdbcUrl(prefix: String): String {
        val jdbcUrl =
            "jdbc:h2:mem:$prefix-${UUID.randomUUID()};MODE=PostgreSQL;" +
                "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
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

    private fun seedStaffCall(
        jdbcUrl: String,
        status: String,
    ): StaffCallFixture {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO users (telegram_user_id, username, first_name, last_name, guest_display_name)
                KEY (telegram_user_id)
                VALUES (?, 'staff', 'Staff', 'User', NULL)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, STAFF_USER_ID)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                MERGE INTO users (telegram_user_id, username, first_name, last_name, guest_display_name)
                KEY (telegram_user_id)
                VALUES (?, 'guest', 'Guest', 'User', 'Максим')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, GUEST_USER_ID)
                statement.executeUpdate()
            }
            val venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', 'PUBLISHED')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert venue")
                    }
                }
            val tableId =
                connection.prepareStatement(
                    """
                    INSERT INTO venue_tables (venue_id, table_number, is_active)
                    VALUES (?, 10, true)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert table")
                    }
                }
            val tableSessionId =
                connection.prepareStatement(
                    """
                    INSERT INTO table_sessions (venue_id, table_id, started_at, last_activity_at, expires_at, status)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    val now = Instant.now()
                    statement.setLong(1, venueId)
                    statement.setLong(2, tableId)
                    statement.setTimestamp(3, Timestamp.from(now))
                    statement.setTimestamp(4, Timestamp.from(now))
                    statement.setTimestamp(5, Timestamp.from(now.plusSeconds(7200)))
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert table session")
                    }
                }
            val callId =
                connection.prepareStatement(
                    """
                    INSERT INTO staff_calls (
                        venue_id, table_id, table_session_id, created_by_user_id, reason, comment, status, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, tableId)
                    statement.setLong(3, tableSessionId)
                    statement.setLong(4, GUEST_USER_ID)
                    statement.setString(5, StaffCallReason.COME.name)
                    statement.setString(6, "Нужна помощь")
                    statement.setString(7, status)
                    statement.setTimestamp(8, Timestamp.from(Instant.now()))
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert staff call")
                    }
                }
            return StaffCallFixture(
                venueId = venueId,
                tableId = tableId,
                tableSessionId = tableSessionId,
                staffCallId = callId,
            )
        }
    }

    private fun seedOrder(
        jdbcUrl: String,
        fixture: StaffCallFixture,
        status: String = "ACTIVE",
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO orders (venue_id, table_id, table_session_id, status)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, fixture.venueId)
                statement.setLong(2, fixture.tableId)
                statement.setLong(3, fixture.tableSessionId)
                statement.setString(4, status)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert order")
                }
            }
        }

    private fun seedLinkedStaffCall(
        jdbcUrl: String,
        fixture: StaffCallFixture,
        orderId: Long,
        reason: StaffCallReason,
        status: String,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO staff_calls (
                    venue_id, table_id, table_session_id, created_by_user_id, reason, comment, status, order_id,
                    payment_method, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, fixture.venueId)
                statement.setLong(2, fixture.tableId)
                statement.setLong(3, fixture.tableSessionId)
                statement.setLong(4, GUEST_USER_ID)
                statement.setString(5, reason.name)
                statement.setString(6, "Комментарий")
                statement.setString(7, status)
                statement.setLong(8, orderId)
                if (reason == StaffCallReason.BILL) {
                    statement.setString(9, BillPaymentMethod.CARD.name)
                } else {
                    statement.setNull(9, java.sql.Types.VARCHAR)
                }
                statement.setTimestamp(10, Timestamp.from(Instant.now()))
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert linked staff call")
                }
            }
        }

    private fun fetchStaffCallStatus(
        jdbcUrl: String,
        staffCallId: Long,
    ): String {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT status FROM staff_calls WHERE id = ?").use { statement ->
                statement.setLong(1, staffCallId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) return rs.getString("status")
                }
            }
        }
        error("Missing staff call $staffCallId")
    }

    private fun fetchStaffCallCreatedByUserId(
        jdbcUrl: String,
        staffCallId: Long,
    ): Long? {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT created_by_user_id FROM staff_calls WHERE id = ?").use { statement ->
                statement.setLong(1, staffCallId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        val value = rs.getLong("created_by_user_id")
                        return if (rs.wasNull()) null else value
                    }
                }
            }
        }
        error("Missing staff call $staffCallId")
    }

    private data class StaffCallFixture(
        val venueId: Long,
        val tableId: Long,
        val tableSessionId: Long,
        val staffCallId: Long,
    )

    private companion object {
        const val STAFF_USER_ID = 42L
        const val GUEST_USER_ID = 200L
    }
}
