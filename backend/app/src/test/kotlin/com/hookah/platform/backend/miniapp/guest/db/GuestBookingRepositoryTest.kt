package com.hookah.platform.backend.miniapp.guest.db

import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.DriverManager
import java.sql.Statement
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class GuestBookingRepositoryTest {
    @Test
    fun `new booking gets default arrival deadline and stays active until deadline`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-deadline")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            val scheduledAt = Instant.now().minus(Duration.ofMinutes(10))

            val booking =
                repository.create(
                    venueId = fixture.venueId,
                    userId = fixture.userId,
                    scheduledAt = scheduledAt,
                    partySize = 2,
                    comment = null,
                )

            assertEquals(scheduledAt.plus(Duration.ofMinutes(30)), booking.arrivalDeadlineAt)
            val active = repository.listActiveByVenue(fixture.venueId)
            assertEquals(listOf(booking.id), active.map { it.id })
        }

    @Test
    fun `changed booking recalculates arrival deadline`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-deadline-change")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            val booking =
                repository.create(
                    venueId = fixture.venueId,
                    userId = fixture.userId,
                    scheduledAt = Instant.parse("2030-01-10T18:00:00Z"),
                    partySize = 2,
                    comment = null,
                )

            val changedAt = Instant.parse("2030-01-10T20:00:00Z")
            val changed =
                repository.updateByGuest(
                    bookingId = booking.id,
                    venueId = fixture.venueId,
                    userId = fixture.userId,
                    scheduledAt = changedAt,
                    partySize = 3,
                    comment = null,
                )

            assertNotNull(changed)
            assertEquals(changedAt.plus(Duration.ofMinutes(30)), changed.arrivalDeadlineAt)
        }

    @Test
    fun `booking display date can use selected service date for overnight shifts`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-service-date")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            val venueZone = ZoneId.of("Europe/Moscow")
            val fridayServiceDate = LocalDate.of(2030, 5, 10)
            val saturdayServiceDate = fridayServiceDate.plusDays(1)
            val fridayNightActualAt =
                LocalDateTime
                    .of(fridayServiceDate.plusDays(1), LocalTime.of(2, 0))
                    .atZone(venueZone)
                    .toInstant()
            val saturdayEveningActualAt =
                LocalDateTime
                    .of(saturdayServiceDate, LocalTime.of(20, 0))
                    .atZone(venueZone)
                    .toInstant()
            val anotherFridayNightActualAt =
                LocalDateTime
                    .of(fridayServiceDate.plusDays(1), LocalTime.of(3, 0))
                    .atZone(venueZone)
                    .toInstant()

            val fridayNight =
                repository.create(
                    venueId = fixture.venueId,
                    userId = fixture.userId,
                    scheduledAt = fridayNightActualAt,
                    partySize = 2,
                    comment = null,
                    venueZoneId = venueZone,
                    serviceDate = fridayServiceDate,
                )
            val saturdayEvening =
                repository.create(
                    venueId = fixture.venueId,
                    userId = fixture.userId,
                    scheduledAt = saturdayEveningActualAt,
                    partySize = 2,
                    comment = null,
                    venueZoneId = venueZone,
                    serviceDate = saturdayServiceDate,
                )
            val anotherFridayNight =
                repository.create(
                    venueId = fixture.venueId,
                    userId = fixture.userId,
                    scheduledAt = anotherFridayNightActualAt,
                    partySize = 2,
                    comment = null,
                    venueZoneId = venueZone,
                    serviceDate = fridayServiceDate,
                )

            assertEquals(fridayNightActualAt, fridayNight.scheduledAt)
            assertEquals(fridayServiceDate, fridayNight.displayDate)
            assertEquals(1, fridayNight.displayNumber)
            assertEquals(saturdayServiceDate, saturdayEvening.displayDate)
            assertEquals(1, saturdayEvening.displayNumber)
            assertEquals(fridayServiceDate, anotherFridayNight.displayDate)
            assertEquals(2, anotherFridayNight.displayNumber)
        }

    @Test
    fun `hold minutes can be updated to allowed values and affect new booking deadline`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-hold-settings")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))

            listOf(10, 15, 30, 45, 60, 240).forEach { minutes ->
                assertEquals(minutes, repository.updateHoldMinutes(fixture.venueId, minutes))
                assertEquals(minutes, repository.getHoldMinutes(fixture.venueId))
            }

            val scheduledAt = Instant.parse("2030-01-10T18:00:00Z")
            val booking =
                repository.create(
                    venueId = fixture.venueId,
                    userId = fixture.userId,
                    scheduledAt = scheduledAt,
                    partySize = 2,
                    comment = null,
                )

            assertEquals(scheduledAt.plus(Duration.ofMinutes(240)), booking.arrivalDeadlineAt)
            listOf(0, 9, 241).forEach { invalid ->
                try {
                    repository.updateHoldMinutes(fixture.venueId, invalid)
                    fail("$invalid minutes must be rejected")
                } catch (_: IllegalArgumentException) {
                }
            }
        }

    @Test
    fun `active list falls back to scheduled at plus hold minutes when arrival deadline is missing`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-active-null-deadline")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            repository.updateHoldMinutes(fixture.venueId, 60)
            val activeScheduledAt = Instant.now().minus(Duration.ofMinutes(45))
            val expiredScheduledAt = Instant.now().minus(Duration.ofMinutes(75))

            val activeId = insertLegacyBooking(jdbcUrl, fixture, activeScheduledAt, displayNumber = 1)
            val expiredId = insertLegacyBooking(jdbcUrl, fixture, expiredScheduledAt, displayNumber = 2)

            val activeIds = repository.listActiveByVenue(fixture.venueId).map { it.id }

            assertTrue(activeId in activeIds)
            assertTrue(expiredId !in activeIds)
        }

    @Test
    fun `seated no show and expired are terminal and excluded from active list`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-terminal")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            val scheduledAt = Instant.now().plus(Duration.ofHours(1))

            val seated =
                repository.create(fixture.venueId, fixture.userId, scheduledAt, 2, null)
            val noShow =
                repository.create(fixture.venueId, fixture.userId, scheduledAt.plusSeconds(60), 2, null)
            val expired =
                repository.create(fixture.venueId, fixture.userId, Instant.now().minus(Duration.ofHours(2)), 2, null)

            val seatedResult = repository.markSeated(fixture.venueId, seated.id, actorUserId = 900L)
            val noShowResult = repository.markNoShow(fixture.venueId, noShow.id, actorUserId = 900L)
            val expiredCount = repository.expireOverdue(now = Instant.now())

            assertEquals(BookingStatus.SEATED, seatedResult?.status)
            assertNotNull(seatedResult?.seatedAt)
            assertEquals(BookingStatus.NO_SHOW, noShowResult?.status)
            assertNotNull(noShowResult?.noShowAt)
            assertEquals(1, expiredCount)

            val activeIds = repository.listActiveByVenue(fixture.venueId).map { it.id }
            assertTrue(seated.id !in activeIds)
            assertTrue(noShow.id !in activeIds)
            assertTrue(expired.id !in activeIds)
        }

    @Test
    fun `expire overdue bookings expires active statuses only and is idempotent`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-expiry-active-statuses")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            val now = Instant.now()
            val scheduledAt = now.minus(Duration.ofHours(2))

            val pending = repository.create(fixture.venueId, fixture.userId, scheduledAt, 2, null)
            val confirmed = repository.create(fixture.venueId, fixture.userId, scheduledAt.plusSeconds(1), 2, null)
            val changed = repository.create(fixture.venueId, fixture.userId, scheduledAt.plusSeconds(2), 2, null)
            val canceled = repository.create(fixture.venueId, fixture.userId, scheduledAt.plusSeconds(3), 2, null)
            val seated = repository.create(fixture.venueId, fixture.userId, scheduledAt.plusSeconds(4), 2, null)
            val noShow = repository.create(fixture.venueId, fixture.userId, scheduledAt.plusSeconds(5), 2, null)
            val alreadyExpired = repository.create(fixture.venueId, fixture.userId, scheduledAt.plusSeconds(6), 2, null)

            assertEquals(BookingStatus.CONFIRMED, repository.updateByVenue(confirmed.id, fixture.venueId, BookingStatus.CONFIRMED)?.status)
            assertEquals(BookingStatus.CHANGED, repository.updateByVenue(changed.id, fixture.venueId, BookingStatus.CHANGED)?.status)
            assertEquals(BookingStatus.CANCELED, repository.updateByVenue(canceled.id, fixture.venueId, BookingStatus.CANCELED)?.status)
            assertEquals(BookingStatus.SEATED, repository.markSeated(fixture.venueId, seated.id)?.status)
            assertEquals(BookingStatus.NO_SHOW, repository.markNoShow(fixture.venueId, noShow.id)?.status)
            forceBookingStatus(jdbcUrl, alreadyExpired.id, BookingStatus.EXPIRED)
            assertEquals(1, repository.expireOverdueBookings(now = now, limit = 1).expiredCount)

            val result = repository.expireOverdueBookings(now = now, limit = 10)

            assertEquals(2, result.expiredCount)
            assertEquals(0, repository.expireOverdueBookings(now = now, limit = 10).expiredCount)
            assertEquals(BookingStatus.EXPIRED, repository.findByVenue(pending.id, fixture.venueId)?.status)
            assertEquals(BookingStatus.EXPIRED, repository.findByVenue(confirmed.id, fixture.venueId)?.status)
            assertEquals(BookingStatus.EXPIRED, repository.findByVenue(changed.id, fixture.venueId)?.status)
            assertEquals(BookingStatus.CANCELED, repository.findByVenue(canceled.id, fixture.venueId)?.status)
            assertEquals(BookingStatus.SEATED, repository.findByVenue(seated.id, fixture.venueId)?.status)
            assertEquals(BookingStatus.NO_SHOW, repository.findByVenue(noShow.id, fixture.venueId)?.status)
            assertEquals(BookingStatus.EXPIRED, repository.findByVenue(alreadyExpired.id, fixture.venueId)?.status)
            assertNotNull(repository.findByVenue(pending.id, fixture.venueId)?.expiredAt)
            assertNull(repository.findByVenue(seated.id, fixture.venueId)?.expiredAt)
        }

    @Test
    fun `expire overdue bookings uses arrival deadline before scheduled fallback`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-expiry-arrival-deadline")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            val now = Instant.now()
            val scheduledPast = now.minus(Duration.ofHours(2))
            val scheduledFuture = now.plus(Duration.ofHours(2))

            val stillHeldId =
                insertBookingWithDeadline(
                    jdbcUrl = jdbcUrl,
                    fixture = fixture,
                    scheduledAt = scheduledPast,
                    arrivalDeadlineAt = now.plus(Duration.ofMinutes(5)),
                    displayNumber = 1,
                )
            val overdueByDeadlineId =
                insertBookingWithDeadline(
                    jdbcUrl = jdbcUrl,
                    fixture = fixture,
                    scheduledAt = scheduledFuture,
                    arrivalDeadlineAt = now.minus(Duration.ofMinutes(5)),
                    displayNumber = 2,
                )

            assertEquals(1, repository.expireOverdueBookings(now = now, limit = 10).expiredCount)

            assertEquals(BookingStatus.PENDING, repository.findByVenue(stillHeldId, fixture.venueId)?.status)
            assertEquals(BookingStatus.EXPIRED, repository.findByVenue(overdueByDeadlineId, fixture.venueId)?.status)
        }

    @Test
    fun `expire overdue bookings uses hold minutes fallback when arrival deadline is missing`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-expiry-null-deadline")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            repository.updateHoldMinutes(fixture.venueId, 60)
            val now = Instant.now()
            val stillHeldId =
                insertLegacyBooking(jdbcUrl, fixture, now.minus(Duration.ofMinutes(45)), displayNumber = 1)
            val overdueId =
                insertLegacyBooking(jdbcUrl, fixture, now.minus(Duration.ofMinutes(75)), displayNumber = 2)

            assertEquals(1, repository.expireOverdueBookings(now = now, limit = 10).expiredCount)

            assertEquals(BookingStatus.PENDING, repository.findByVenue(stillHeldId, fixture.venueId)?.status)
            assertEquals(BookingStatus.EXPIRED, repository.findByVenue(overdueId, fixture.venueId)?.status)
        }

    @Test
    fun `terminal booking cannot transition back through visit actions`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-terminal-guard")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            val booking =
                repository.create(
                    venueId = fixture.venueId,
                    userId = fixture.userId,
                    scheduledAt = Instant.now().plus(Duration.ofHours(1)),
                    partySize = 2,
                    comment = null,
                )

            assertNotNull(repository.markSeated(fixture.venueId, booking.id, actorUserId = 900L))

            assertNull(repository.markNoShow(fixture.venueId, booking.id, actorUserId = 900L))
        }

    @Test
    fun `strong advance confirmed booking creates day of visit and pre visit reminders`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-reminders-advance")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            val zoneId = ZoneId.of("Europe/Moscow")
            val serviceDate = LocalDate.of(2030, 5, 10)
            val scheduledAt = LocalDateTime.of(serviceDate.plusDays(1), LocalTime.of(2, 0)).atZone(zoneId).toInstant()
            val now = LocalDateTime.of(2030, 5, 6, 12, 0).atZone(zoneId).toInstant()
            val booking =
                repository.create(
                    venueId = fixture.venueId,
                    userId = fixture.userId,
                    scheduledAt = scheduledAt,
                    partySize = 2,
                    comment = null,
                    venueZoneId = zoneId,
                    serviceDate = serviceDate,
                )
            assertEquals(BookingStatus.CONFIRMED, repository.updateByVenue(booking.id, fixture.venueId, BookingStatus.CONFIRMED)?.status)

            val result = repository.scheduleRemindersForBooking(booking.id, now = now, venueZoneId = zoneId)
            val reminders = listReminders(jdbcUrl, booking.id)

            assertEquals(2, result.pendingCount)
            assertEquals(
                LocalDateTime.of(serviceDate, LocalTime.of(11, 0)).atZone(zoneId).toInstant(),
                reminders.first { it.kind == BookingReminderKind.DAY_OF_VISIT }.scheduledFor,
            )
            assertEquals(scheduledAt.minus(Duration.ofHours(2)), reminders.first { it.kind == BookingReminderKind.PRE_VISIT }.scheduledFor)
            assertTrue(reminders.all { it.status == BookingReminderStatus.PENDING })
        }

    @Test
    fun `booking confirmed one or two days before service date creates only pre visit reminder`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-reminders-near")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            val zoneId = ZoneId.of("Europe/Moscow")
            val serviceDate = LocalDate.of(2030, 5, 10)
            val scheduledAt = LocalDateTime.of(serviceDate, LocalTime.of(20, 0)).atZone(zoneId).toInstant()
            val now = LocalDateTime.of(2030, 5, 8, 12, 0).atZone(zoneId).toInstant()
            val booking = repository.create(fixture.venueId, fixture.userId, scheduledAt, 2, null, zoneId, serviceDate)
            repository.updateByVenue(booking.id, fixture.venueId, BookingStatus.CONFIRMED)

            val result = repository.scheduleRemindersForBooking(booking.id, now = now, venueZoneId = zoneId)
            val reminders = listReminders(jdbcUrl, booking.id)

            assertEquals(1, result.pendingCount)
            assertEquals(listOf(BookingReminderKind.PRE_VISIT), reminders.map { it.kind })
            assertEquals(scheduledAt.minus(Duration.ofHours(2)), reminders.single().scheduledFor)
        }

    @Test
    fun `same day booking reminder policy uses one hour or skips close booking`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-reminders-same-day")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            val zoneId = ZoneId.of("Europe/Moscow")
            val serviceDate = LocalDate.of(2030, 5, 10)
            val scheduledAt = LocalDateTime.of(serviceDate, LocalTime.of(20, 0)).atZone(zoneId).toInstant()
            val booking = repository.create(fixture.venueId, fixture.userId, scheduledAt, 2, null, zoneId, serviceDate)
            repository.updateByVenue(booking.id, fixture.venueId, BookingStatus.CONFIRMED)

            val earlyNow = LocalDateTime.of(serviceDate, LocalTime.of(14, 0)).atZone(zoneId).toInstant()
            assertEquals(1, repository.scheduleRemindersForBooking(booking.id, now = earlyNow, venueZoneId = zoneId).pendingCount)
            assertEquals(scheduledAt.minus(Duration.ofHours(1)), listReminders(jdbcUrl, booking.id).single().scheduledFor)

            val closeNow = LocalDateTime.of(serviceDate, LocalTime.of(18, 0)).atZone(zoneId).toInstant()
            val closeResult = repository.scheduleRemindersForBooking(booking.id, now = closeNow, venueZoneId = zoneId)
            val reminders = listReminders(jdbcUrl, booking.id)

            assertEquals(0, closeResult.pendingCount)
            assertTrue(reminders.none { it.status == BookingReminderStatus.PENDING })
            assertTrue(reminders.any { it.status == BookingReminderStatus.SKIPPED })
        }

    @Test
    fun `terminal status cancels pending reminders and guest confirmation is idempotent`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-reminders-terminal")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            val zoneId = ZoneId.of("Europe/Moscow")
            val serviceDate = LocalDate.of(2030, 5, 10)
            val scheduledAt = LocalDateTime.of(serviceDate, LocalTime.of(20, 0)).atZone(zoneId).toInstant()
            val now = LocalDateTime.of(2030, 5, 8, 12, 0).atZone(zoneId).toInstant()
            val booking = repository.create(fixture.venueId, fixture.userId, scheduledAt, 2, null, zoneId, serviceDate)
            repository.updateByVenue(booking.id, fixture.venueId, BookingStatus.CONFIRMED)
            repository.scheduleRemindersForBooking(booking.id, now = now, venueZoneId = zoneId)

            val confirmed = repository.markGuestConfirmed(booking.id, fixture.userId, now.plusSeconds(30))
            assertEquals(now.plusSeconds(30), confirmed?.lastGuestConfirmationAt)
            repository.updateByVenue(booking.id, fixture.venueId, BookingStatus.CANCELED)

            assertTrue(listReminders(jdbcUrl, booking.id).all { it.status == BookingReminderStatus.CANCELED })
            assertNull(repository.markGuestConfirmed(booking.id, fixture.userId, now.plusSeconds(60)))
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

    private fun seedVenueAndUser(jdbcUrl: String): BookingFixture =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Booking Venue', 'City', 'Address', 'PUBLISHED')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            val userId = 424242L
            connection.prepareStatement(
                """
                INSERT INTO users (telegram_user_id, username, first_name, updated_at)
                VALUES (?, 'guest', 'Guest', CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
            BookingFixture(venueId = venueId, userId = userId)
        }

    private fun insertLegacyBooking(
        jdbcUrl: String,
        fixture: BookingFixture,
        scheduledAt: Instant,
        displayNumber: Int,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO bookings (
                    venue_id,
                    user_id,
                    scheduled_at,
                    party_size,
                    comment,
                    status,
                    display_date,
                    display_number,
                    arrival_deadline_at
                )
                VALUES (?, ?, ?, 2, NULL, 'PENDING', CURRENT_DATE, ?, NULL)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, fixture.venueId)
                statement.setLong(2, fixture.userId)
                statement.setTimestamp(3, java.sql.Timestamp.from(scheduledAt))
                statement.setInt(4, displayNumber)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
                }
            }
        }

    private fun insertBookingWithDeadline(
        jdbcUrl: String,
        fixture: BookingFixture,
        scheduledAt: Instant,
        arrivalDeadlineAt: Instant,
        displayNumber: Int,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO bookings (
                    venue_id,
                    user_id,
                    scheduled_at,
                    party_size,
                    comment,
                    status,
                    display_date,
                    display_number,
                    arrival_deadline_at
                )
                VALUES (?, ?, ?, 2, NULL, 'PENDING', CURRENT_DATE, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, fixture.venueId)
                statement.setLong(2, fixture.userId)
                statement.setTimestamp(3, java.sql.Timestamp.from(scheduledAt))
                statement.setInt(4, displayNumber)
                statement.setTimestamp(5, java.sql.Timestamp.from(arrivalDeadlineAt))
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
                }
            }
        }

    private fun forceBookingStatus(
        jdbcUrl: String,
        bookingId: Long,
        status: BookingStatus,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                UPDATE bookings
                SET status = ?,
                    expired_at = CASE WHEN ? = 'EXPIRED' THEN CURRENT_TIMESTAMP ELSE expired_at END
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setString(2, status.name)
                statement.setLong(3, bookingId)
                statement.executeUpdate()
            }
        }
    }

    private fun listReminders(
        jdbcUrl: String,
        bookingId: Long,
    ): List<ReminderRow> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT kind, scheduled_for, status
                FROM booking_reminders
                WHERE booking_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, bookingId)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                ReminderRow(
                                    kind = BookingReminderKind.valueOf(rs.getString("kind")),
                                    scheduledFor = rs.getTimestamp("scheduled_for").toInstant(),
                                    status = BookingReminderStatus.valueOf(rs.getString("status")),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private data class ReminderRow(
        val kind: BookingReminderKind,
        val scheduledFor: Instant,
        val status: BookingReminderStatus,
    )

    private data class BookingFixture(
        val venueId: Long,
        val userId: Long,
    )
}
