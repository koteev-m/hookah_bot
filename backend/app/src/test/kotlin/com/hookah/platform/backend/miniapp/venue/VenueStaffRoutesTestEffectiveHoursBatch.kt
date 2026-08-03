package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.telegram.db.VenueBookingHoursRepository
import kotlinx.coroutines.runBlocking
import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class VenueStaffRoutesTestEffectiveHoursBatch {
    @Test
    fun `effective hours range uses two batch queries with override precedence`() =
        runBlocking {
            val dataSource = testDataSource()
            val weeklyDate = LocalDate.parse("2030-01-07")
            val overrideDate = weeklyDate.plusWeeks(1)
            seedHours(dataSource, weeklyDate, overrideDate)
            val queryCount = AtomicInteger()
            val repository =
                VenueBookingHoursRepository(
                    CountingDataSource(dataSource) { queryCount.incrementAndGet() },
                )
            val requestedDates = (0L..30L).map(weeklyDate::plusDays).toSet()

            val result = repository.findByVenuesAndDates(mapOf(VENUE_ID to requestedDates))

            assertEquals(2, queryCount.get())
            val hoursByDate = result.getValue(VENUE_ID)
            val weekly = hoursByDate.getValue(weeklyDate)
            assertEquals(LocalTime.parse("18:00"), weekly.opensAt)
            assertEquals(LocalTime.parse("02:00"), weekly.closesAt)
            assertFalse(weekly.isClosed)
            val overridden = hoursByDate.getValue(overrideDate)
            assertEquals(LocalTime.parse("20:00"), overridden.opensAt)
            assertEquals(LocalTime.parse("04:00"), overridden.closesAt)
            assertFalse(overridden.isClosed)
            assertFalse(hoursByDate.containsKey(weeklyDate.plusDays(1)))
        }

    @Test
    fun `batch loading failure is not represented as unconfigured hours`() =
        runBlocking {
            val repository = VenueBookingHoursRepository(dataSource = null)

            assertFailsWith<DatabaseUnavailableException> {
                repository.findByVenuesAndDates(
                    mapOf(VENUE_ID to setOf(LocalDate.parse("2030-01-07"))),
                )
            }
            Unit
        }

    private fun testDataSource(): JdbcDataSource {
        val dataSource =
            JdbcDataSource().apply {
                setURL(
                    "jdbc:h2:mem:staff-effective-hours-${UUID.randomUUID()};MODE=PostgreSQL;" +
                        "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                )
                user = "sa"
                password = ""
            }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE venue_booking_hours (
                        venue_id BIGINT NOT NULL,
                        weekday SMALLINT NOT NULL,
                        opens_at TIME NOT NULL,
                        closes_at TIME NOT NULL,
                        is_closed BOOLEAN NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE venue_booking_hours_overrides (
                        venue_id BIGINT NOT NULL,
                        service_date DATE NOT NULL,
                        opens_at TIME NOT NULL,
                        closes_at TIME NOT NULL,
                        is_closed BOOLEAN NOT NULL,
                        guest_note VARCHAR NULL
                    )
                    """.trimIndent(),
                )
            }
        }
        return dataSource
    }

    private fun seedHours(
        dataSource: DataSource,
        weeklyDate: LocalDate,
        overrideDate: LocalDate,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_booking_hours (venue_id, weekday, opens_at, closes_at, is_closed)
                VALUES (?, ?, ?, ?, FALSE)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, VENUE_ID)
                statement.setInt(2, weeklyDate.dayOfWeek.value)
                statement.setObject(3, LocalTime.parse("18:00"))
                statement.setObject(4, LocalTime.parse("02:00"))
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO venue_booking_hours_overrides (
                    venue_id, service_date, opens_at, closes_at, is_closed
                )
                VALUES (?, ?, ?, ?, FALSE)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, VENUE_ID)
                statement.setObject(2, overrideDate)
                statement.setObject(3, LocalTime.parse("20:00"))
                statement.setObject(4, LocalTime.parse("04:00"))
                statement.executeUpdate()
            }
        }
    }

    private class CountingDataSource(
        private val delegate: DataSource,
        private val onPrepare: (String) -> Unit,
    ) : DataSource by delegate {
        override fun getConnection(): Connection = CountingConnection(delegate.connection, onPrepare)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = CountingConnection(delegate.getConnection(username, password), onPrepare)
    }

    private class CountingConnection(
        private val delegate: Connection,
        private val onPrepare: (String) -> Unit,
    ) : Connection by delegate {
        override fun prepareStatement(sql: String): PreparedStatement {
            onPrepare(sql)
            return delegate.prepareStatement(sql)
        }
    }

    private companion object {
        const val VENUE_ID = 10L
    }
}
