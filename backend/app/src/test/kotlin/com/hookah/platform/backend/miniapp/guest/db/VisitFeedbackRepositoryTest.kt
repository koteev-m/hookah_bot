package com.hookah.platform.backend.miniapp.guest.db

import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VisitFeedbackRepositoryTest {
    @Test
    fun `closed order visit schedules one idempotent feedback request`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-feedback-schedule")
            val fixture = seedBase(jdbcUrl)
            val visitId = seedClosedOrderVisit(jdbcUrl, fixture, GUEST_USER)
            val repository = VisitFeedbackRepository(dataSource(jdbcUrl))
            val now = Instant.parse("2030-05-10T12:00:00Z")

            val first = repository.scheduleFeedbackRequestForVisit(visitId, now)
            val second = repository.scheduleFeedbackRequestForVisit(visitId, now.plusSeconds(60))

            assertNotNull(first)
            assertNotNull(second)
            assertEquals(first.id, second.id)
            assertEquals(Instant.parse("2030-05-11T09:00:00Z"), first.scheduledFor)
            repository.markRequestSent(first.id, Instant.parse("2030-05-11T09:01:00Z"))
            val sent = repository.scheduleFeedbackRequestForVisit(visitId, now.plusSeconds(3600))
            assertNotNull(sent)
            assertEquals(first.id, sent.id)
            assertEquals(Instant.parse("2030-05-11T09:00:00Z"), sent.scheduledFor)
            assertEquals(VisitFeedbackRequestStatus.SENT, sent.status)
            assertEquals(1, countRows(jdbcUrl, "visit_feedback_requests"))
        }

    @Test
    fun `booking only visit does not schedule feedback request`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-feedback-booking-only")
            val fixture = seedBase(jdbcUrl)
            val visitId = seedBookingOnlyVisit(jdbcUrl, fixture, GUEST_USER)
            val repository = VisitFeedbackRepository(dataSource(jdbcUrl))

            assertNull(repository.scheduleFeedbackRequestForVisit(visitId, Instant.parse("2030-05-10T12:00:00Z")))
            assertEquals(0, countRows(jdbcUrl, "visit_feedback_requests"))
        }

    @Test
    fun `feedback request is scheduled for next venue local noon`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-feedback-next-noon")
            val fixture = seedBase(jdbcUrl)
            val visitId = seedClosedOrderVisit(jdbcUrl, fixture, GUEST_USER)
            val repository = VisitFeedbackRepository(dataSource(jdbcUrl))

            val request = repository.scheduleFeedbackRequestForVisit(visitId, Instant.parse("2030-05-10T17:30:00Z"))

            assertNotNull(request)
            assertEquals(Instant.parse("2030-05-11T09:00:00Z"), request.scheduledFor)
        }

    @Test
    fun `feedback request scheduling uses venue timezone`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-feedback-timezone")
            val fixture = seedBase(jdbcUrl)
            updateVenueTimezone(jdbcUrl, fixture.venueId, "Asia/Tomsk")
            val visitId = seedClosedOrderVisit(jdbcUrl, fixture, GUEST_USER)
            val repository = VisitFeedbackRepository(dataSource(jdbcUrl))

            val request = repository.scheduleFeedbackRequestForVisit(visitId, Instant.parse("2030-05-10T17:30:00Z"))

            assertNotNull(request)
            assertEquals(Instant.parse("2030-05-12T05:00:00Z"), request.scheduledFor)
        }

    @Test
    fun `pick due requests returns only due pending requests`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-feedback-due")
            val fixture = seedBase(jdbcUrl)
            val dueVisitId = seedClosedOrderVisit(jdbcUrl, fixture, GUEST_USER)
            val futureVisitId = seedClosedOrderVisit(jdbcUrl, fixture, OTHER_GUEST)
            val repository = VisitFeedbackRepository(dataSource(jdbcUrl))
            repository.scheduleFeedbackRequestForVisit(dueVisitId, Instant.parse("2030-05-10T12:00:00Z"))
            repository.scheduleFeedbackRequestForVisit(futureVisitId, Instant.parse("2030-05-11T12:00:00Z"))

            val due = repository.pickDueRequests(Instant.parse("2030-05-11T09:01:00Z"), limit = 10)

            assertEquals(listOf(dueVisitId), due.map { it.visitId })
            assertEquals(1, due.single().attempts)
        }

    @Test
    fun `rating skip comment and ownership rules are enforced`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-feedback-submit")
            val fixture = seedBase(jdbcUrl)
            val visitId = seedClosedOrderVisit(jdbcUrl, fixture, GUEST_USER)
            val repository = VisitFeedbackRepository(dataSource(jdbcUrl))

            val rating = repository.submitRating(visitId, GUEST_USER, 2)
            assertNotNull(rating)
            assertEquals(2, rating.rating)
            assertEquals(VisitFeedbackStatus.SUBMITTED, rating.status)
            assertNull(repository.submitRating(visitId, OTHER_GUEST, 5))
            assertFailsWith<IllegalArgumentException> { repository.submitRating(visitId, GUEST_USER, 6) }

            val commented = repository.saveComment(visitId, GUEST_USER, "Все хорошо")
            assertNotNull(commented)
            assertEquals("Все хорошо", commented.comment)
            assertEquals(true, repository.markCommentStaffNotified(commented.id))
            val updated = repository.saveComment(visitId, GUEST_USER, "Все хорошо")
            assertNotNull(updated)
            assertNotNull(updated.commentStaffNotifiedAt)
            assertEquals(false, repository.markCommentStaffNotified(commented.id))

            val skipped = repository.skipFeedback(visitId, GUEST_USER)
            assertNotNull(skipped)
            assertEquals(VisitFeedbackStatus.SUBMITTED, skipped.status)
            assertEquals(2, skipped.rating)
        }

    @Test
    fun `feedback thread messages are saved for staff and guest`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-feedback-messages")
            val fixture = seedBase(jdbcUrl)
            val visitId = seedClosedOrderVisit(jdbcUrl, fixture, GUEST_USER)
            val repository = VisitFeedbackRepository(dataSource(jdbcUrl))
            val feedback = repository.submitRating(visitId, GUEST_USER, 2)
            assertNotNull(feedback)

            val thread = repository.findFeedbackThread(feedback.id)
            assertNotNull(thread)
            assertEquals(fixture.venueId, thread.venueId)
            assertEquals(GUEST_USER, thread.guestUserId)
            assertEquals(2, thread.rating)

            val staffMessage =
                repository.saveFeedbackMessage(
                    feedbackId = feedback.id,
                    senderType = VisitFeedbackMessageSender.STAFF,
                    senderUserId = null,
                    messageText = "Извините, хотим разобраться",
                )
            val guestMessage =
                repository.saveFeedbackMessage(
                    feedbackId = feedback.id,
                    senderType = VisitFeedbackMessageSender.GUEST,
                    senderUserId = GUEST_USER,
                    messageText = "Долго ждали заказ",
                )

            assertNotNull(staffMessage)
            assertEquals(VisitFeedbackMessageSender.STAFF, staffMessage.senderType)
            assertNotNull(guestMessage)
            assertEquals(VisitFeedbackMessageSender.GUEST, guestMessage.senderType)
            assertEquals(2, countRows(jdbcUrl, "visit_feedback_messages"))
            assertNull(repository.saveFeedbackMessage(999_999L, VisitFeedbackMessageSender.STAFF, null, "Нет отзыва"))
            assertFailsWith<IllegalArgumentException> {
                repository.saveFeedbackMessage(feedback.id, VisitFeedbackMessageSender.STAFF, null, "")
            }
        }

    @Test
    fun `venue feedback list and detail include guest comment and messages`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-feedback-management")
            val fixture = seedBase(jdbcUrl)
            val visitId = seedClosedOrderVisit(jdbcUrl, fixture, GUEST_USER)
            val otherVisitId = seedClosedOrderVisit(jdbcUrl, fixture, OTHER_GUEST)
            val repository = VisitFeedbackRepository(dataSource(jdbcUrl))
            val feedback = repository.submitRating(visitId, GUEST_USER, 2)
            assertNotNull(feedback)
            repository.saveComment(visitId, GUEST_USER, "Долго ждали заказ")
            repository.saveFeedbackMessage(
                feedbackId = feedback.id,
                senderType = VisitFeedbackMessageSender.STAFF,
                senderUserId = null,
                messageText = "Извините, хотим разобраться",
            )
            repository.saveFeedbackMessage(
                feedbackId = feedback.id,
                senderType = VisitFeedbackMessageSender.GUEST,
                senderUserId = GUEST_USER,
                messageText = "Спасибо за ответ",
            )
            repository.skipFeedback(otherVisitId, OTHER_GUEST)

            val list = repository.listVenueFeedback(fixture.venueId, limit = 10)
            val detail = repository.getVenueFeedbackDetail(fixture.venueId, feedback.id)

            assertEquals(1, list.size)
            assertEquals(feedback.id, list.single().feedbackId)
            assertEquals("User $GUEST_USER", list.single().guestDisplayName)
            assertEquals(2, list.single().rating)
            assertEquals("Долго ждали заказ", list.single().comment)
            assertEquals(true, list.single().hasStaffReply)
            assertEquals(true, list.single().requiresAnswer)
            assertNotNull(detail)
            assertEquals("Mix", detail.venueName)
            assertEquals("User $GUEST_USER", detail.guestDisplayName)
            assertEquals(LocalDate.of(2030, 5, 10), detail.serviceDate)
            assertEquals(true, detail.hasStaffReply)
            assertEquals(true, detail.requiresAnswer)
            assertEquals(2, detail.messages.size)
            assertEquals(VisitFeedbackMessageSender.STAFF, detail.messages[0].senderType)
            assertEquals(VisitFeedbackMessageSender.GUEST, detail.messages[1].senderType)
            assertNull(repository.getVenueFeedbackDetail(fixture.venueId + 1, feedback.id))
        }

    @Test
    fun `venue feedback filters unanswered low and all feedback`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-feedback-filters")
            val fixture = seedBase(jdbcUrl)
            val visitId = seedClosedOrderVisit(jdbcUrl, fixture, GUEST_USER)
            val repository = VisitFeedbackRepository(dataSource(jdbcUrl))
            val feedback = repository.submitRating(visitId, GUEST_USER, 2)
            assertNotNull(feedback)

            assertEquals(1, repository.listVenueFeedback(fixture.venueId, VisitFeedbackVenueFilter.NEEDS_REPLY).size)
            assertEquals(1, repository.listVenueFeedback(fixture.venueId, VisitFeedbackVenueFilter.LOW).size)
            assertEquals(1, repository.listVenueFeedback(fixture.venueId, VisitFeedbackVenueFilter.ALL).size)

            repository.saveFeedbackMessage(
                feedbackId = feedback.id,
                senderType = VisitFeedbackMessageSender.STAFF,
                senderUserId = null,
                messageText = "Извините, разберемся",
                now = Instant.parse("2030-05-10T10:00:00Z"),
            )
            assertEquals(0, repository.listVenueFeedback(fixture.venueId, VisitFeedbackVenueFilter.NEEDS_REPLY).size)
            val answeredDetail = repository.getVenueFeedbackDetail(fixture.venueId, feedback.id)
            assertNotNull(answeredDetail)
            assertEquals(false, answeredDetail.requiresAnswer)

            repository.saveFeedbackMessage(
                feedbackId = feedback.id,
                senderType = VisitFeedbackMessageSender.GUEST,
                senderUserId = GUEST_USER,
                messageText = "Спасибо, но проблема осталась",
                now = Instant.parse("2030-05-10T10:05:00Z"),
            )
            val needsReply = repository.listVenueFeedback(fixture.venueId, VisitFeedbackVenueFilter.NEEDS_REPLY)
            assertEquals(1, needsReply.size)
            assertEquals(true, needsReply.single().requiresAnswer)
        }

    private fun updateVenueTimezone(
        jdbcUrl: String,
        venueId: Long,
        timezone: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("UPDATE venue_settings SET timezone = ? WHERE venue_id = ?").use { statement ->
                statement.setString(1, timezone)
                statement.setLong(2, venueId)
                statement.executeUpdate()
            }
        }
    }

    private fun migratedJdbcUrl(prefix: String): String {
        val jdbcUrl =
            "jdbc:h2:mem:$prefix-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
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

    private fun seedBase(jdbcUrl: String): Fixture =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            listOf(GUEST_USER, OTHER_GUEST).forEach { userId ->
                connection.prepareStatement(
                    """
                    INSERT INTO users (telegram_user_id, username, first_name)
                    VALUES (?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setString(2, "user$userId")
                    statement.setString(3, "User $userId")
                    statement.executeUpdate()
                }
            }
            val venueId =
                connection.prepareStatement(
                    "INSERT INTO venues (name, city, address, status) VALUES ('Mix', 'Москва', 'Address', 'PUBLISHED')",
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                "INSERT INTO venue_settings (venue_id, timezone) VALUES (?, 'Europe/Moscow')",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeUpdate()
            }
            val tableId =
                connection.prepareStatement(
                    "INSERT INTO venue_tables (venue_id, table_number, is_active) VALUES (?, 1, true)",
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            Fixture(venueId, tableId)
        }

    private fun seedClosedOrderVisit(
        jdbcUrl: String,
        fixture: Fixture,
        userId: Long,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val now = Instant.parse("2030-05-10T10:00:00Z")
            val tableSessionId = insertTableSession(connection, fixture, now)
            val orderId =
                connection.prepareStatement(
                    """
                    INSERT INTO orders (venue_id, table_id, table_session_id, status, display_number, display_date)
                    VALUES (?, ?, ?, 'CLOSED', 1, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, fixture.venueId)
                    statement.setLong(2, fixture.tableId)
                    statement.setLong(3, tableSessionId)
                    statement.setDate(4, java.sql.Date.valueOf(LocalDate.of(2030, 5, 10)))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO visits (venue_id, user_id, table_session_id, order_id, source, occurred_at, service_date)
                VALUES (?, ?, ?, ?, 'ORDER_CLOSED', ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, fixture.venueId)
                statement.setLong(2, userId)
                statement.setLong(3, tableSessionId)
                statement.setLong(4, orderId)
                statement.setTimestamp(5, Timestamp.from(now))
                statement.setDate(6, java.sql.Date.valueOf(LocalDate.of(2030, 5, 10)))
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
                }
            }
        }

    private fun seedBookingOnlyVisit(
        jdbcUrl: String,
        fixture: Fixture,
        userId: Long,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO visits (venue_id, user_id, source, occurred_at, service_date)
                VALUES (?, ?, 'BOOKING_SEATED', ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, fixture.venueId)
                statement.setLong(2, userId)
                statement.setTimestamp(3, Timestamp.from(Instant.parse("2030-05-10T10:00:00Z")))
                statement.setDate(4, java.sql.Date.valueOf(LocalDate.of(2030, 5, 10)))
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
                }
            }
        }

    private fun insertTableSession(
        connection: Connection,
        fixture: Fixture,
        now: Instant,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO table_sessions (venue_id, table_id, started_at, last_activity_at, expires_at, status)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE')
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, fixture.venueId)
            statement.setLong(2, fixture.tableId)
            statement.setTimestamp(3, Timestamp.from(now))
            statement.setTimestamp(4, Timestamp.from(now))
            statement.setTimestamp(5, Timestamp.from(now.plusSeconds(7200)))
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun countRows(
        jdbcUrl: String,
        tableName: String,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $tableName").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private data class Fixture(
        val venueId: Long,
        val tableId: Long,
    )

    private companion object {
        const val GUEST_USER: Long = 1001L
        const val OTHER_GUEST: Long = 1002L
    }
}
