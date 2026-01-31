package com.hookah.platform.backend.platform

import com.hookah.platform.backend.api.DatabaseUnavailableException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.LocalDate
import java.util.Locale
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PlatformSubscriptionSettings(
    val venueId: Long,
    val trialEndDate: LocalDate?,
    val paidStartDate: LocalDate?,
    val basePriceMinor: Int?,
    val priceOverrideMinor: Int?,
    val currency: String
)

data class PlatformPriceScheduleItem(
    val venueId: Long,
    val effectiveFrom: LocalDate,
    val priceMinor: Int,
    val currency: String
)

data class PlatformEffectivePrice(
    val priceMinor: Int,
    val currency: String
)

data class PlatformSubscriptionSnapshot(
    val settings: PlatformSubscriptionSettings,
    val schedule: List<PlatformPriceScheduleItem>,
    val effectivePriceToday: PlatformEffectivePrice?
)

class PlatformSubscriptionSettingsRepository(private val dataSource: DataSource?) {
    suspend fun getSubscriptionSnapshot(venueId: Long): PlatformSubscriptionSnapshot? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    if (!venueExists(connection, venueId)) {
                        return@use null
                    }
                    val settings = loadSettings(connection, venueId)
                        ?: PlatformSubscriptionSettings(
                            venueId = venueId,
                            trialEndDate = null,
                            paidStartDate = null,
                            basePriceMinor = null,
                            priceOverrideMinor = null,
                            currency = DEFAULT_CURRENCY
                        )
                    val schedule = loadSchedule(connection, venueId)
                    val effectivePriceToday = resolveEffectivePrice(settings, schedule, LocalDate.now())
                    PlatformSubscriptionSnapshot(
                        settings = settings,
                        schedule = schedule,
                        effectivePriceToday = effectivePriceToday
                    )
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateSettings(
        venueId: Long,
        update: PlatformSubscriptionSettingsUpdate,
        actorUserId: Long
    ): PlatformSubscriptionSettings? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    if (!venueExists(connection, venueId)) {
                        return@use null
                    }
                    val existing = loadSettings(connection, venueId)
                    val resolved = resolveSettingsUpdate(venueId, existing, update)
                    upsertSettings(connection, resolved, actorUserId)
                    resolved
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun replaceSchedule(
        venueId: Long,
        items: List<PlatformPriceScheduleItem>,
        actorUserId: Long
    ): List<PlatformPriceScheduleItem>? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        if (!venueExists(connection, venueId)) {
                            connection.rollback()
                            return@use null
                        }
                        connection.prepareStatement(
                            """
                                DELETE FROM venue_price_schedule
                                WHERE venue_id = ?
                            """.trimIndent()
                        ).use { statement ->
                            statement.setLong(1, venueId)
                            statement.executeUpdate()
                        }
                        if (items.isNotEmpty()) {
                            connection.prepareStatement(
                                """
                                    INSERT INTO venue_price_schedule (
                                        venue_id,
                                        effective_from,
                                        price_minor,
                                        currency,
                                        updated_at,
                                        updated_by_user_id
                                    )
                                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                                """.trimIndent()
                            ).use { statement ->
                                items.forEach { item ->
                                    statement.setLong(1, venueId)
                                    statement.setDate(2, java.sql.Date.valueOf(item.effectiveFrom))
                                    statement.setInt(3, item.priceMinor)
                                    statement.setString(4, item.currency)
                                    statement.setLong(5, actorUserId)
                                    statement.addBatch()
                                }
                                statement.executeBatch()
                            }
                        }
                        connection.commit()
                        items
                    } catch (e: SQLException) {
                        connection.rollback()
                        throw e
                    } finally {
                        connection.autoCommit = initialAutoCommit
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun resolveSettingsUpdate(
        venueId: Long,
        existing: PlatformSubscriptionSettings?,
        update: PlatformSubscriptionSettingsUpdate
    ): PlatformSubscriptionSettings {
        val existingCurrency = existing?.currency ?: DEFAULT_CURRENCY
        val currency = update.currency?.trim()?.uppercase(Locale.ROOT) ?: existingCurrency
        return PlatformSubscriptionSettings(
            venueId = venueId,
            trialEndDate = update.trialEndDate ?: existing?.trialEndDate,
            paidStartDate = update.paidStartDate ?: existing?.paidStartDate,
            basePriceMinor = update.basePriceMinor ?: existing?.basePriceMinor,
            priceOverrideMinor = update.priceOverrideMinor ?: existing?.priceOverrideMinor,
            currency = currency
        )
    }

    private fun resolveEffectivePrice(
        settings: PlatformSubscriptionSettings,
        schedule: List<PlatformPriceScheduleItem>,
        today: LocalDate
    ): PlatformEffectivePrice? {
        settings.priceOverrideMinor?.let { override ->
            return PlatformEffectivePrice(priceMinor = override, currency = settings.currency)
        }
        val scheduled = schedule.lastOrNull { !it.effectiveFrom.isAfter(today) }
        if (scheduled != null) {
            return PlatformEffectivePrice(priceMinor = scheduled.priceMinor, currency = scheduled.currency)
        }
        val base = settings.basePriceMinor ?: return null
        return PlatformEffectivePrice(priceMinor = base, currency = settings.currency)
    }

    private fun loadSettings(connection: Connection, venueId: Long): PlatformSubscriptionSettings? {
        connection.prepareStatement(
            """
                SELECT trial_end_date,
                       paid_start_date,
                       base_price_minor,
                       price_override_minor,
                       currency
                FROM venue_subscription_settings
                WHERE venue_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    return null
                }
                return PlatformSubscriptionSettings(
                    venueId = venueId,
                    trialEndDate = rs.getDate("trial_end_date")?.toLocalDate(),
                    paidStartDate = rs.getDate("paid_start_date")?.toLocalDate(),
                    basePriceMinor = rs.getNullableInt("base_price_minor"),
                    priceOverrideMinor = rs.getNullableInt("price_override_minor"),
                    currency = rs.getString("currency")
                )
            }
        }
    }

    private fun loadSchedule(connection: Connection, venueId: Long): List<PlatformPriceScheduleItem> {
        connection.prepareStatement(
            """
                SELECT effective_from, price_minor, currency
                FROM venue_price_schedule
                WHERE venue_id = ?
                ORDER BY effective_from ASC
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                val items = mutableListOf<PlatformPriceScheduleItem>()
                while (rs.next()) {
                    items.add(
                        PlatformPriceScheduleItem(
                            venueId = venueId,
                            effectiveFrom = rs.getDate("effective_from").toLocalDate(),
                            priceMinor = rs.getInt("price_minor"),
                            currency = rs.getString("currency")
                        )
                    )
                }
                return items
            }
        }
    }

    private fun upsertSettings(
        connection: Connection,
        settings: PlatformSubscriptionSettings,
        actorUserId: Long
    ) {
        connection.prepareStatement(
            """
                UPDATE venue_subscription_settings
                SET trial_end_date = ?,
                    paid_start_date = ?,
                    base_price_minor = ?,
                    price_override_minor = ?,
                    currency = ?,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by_user_id = ?
                WHERE venue_id = ?
            """.trimIndent()
        ).use { statement ->
            setDateOrNull(statement, 1, settings.trialEndDate)
            setDateOrNull(statement, 2, settings.paidStartDate)
            setIntOrNull(statement, 3, settings.basePriceMinor)
            setIntOrNull(statement, 4, settings.priceOverrideMinor)
            statement.setString(5, settings.currency)
            statement.setLong(6, actorUserId)
            statement.setLong(7, settings.venueId)
            val updated = statement.executeUpdate()
            if (updated > 0) {
                return
            }
        }
        try {
            connection.prepareStatement(
                """
                    INSERT INTO venue_subscription_settings (
                        venue_id,
                        trial_end_date,
                        paid_start_date,
                        base_price_minor,
                        price_override_minor,
                        currency,
                        updated_at,
                        updated_by_user_id
                    )
                    VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, settings.venueId)
                setDateOrNull(statement, 2, settings.trialEndDate)
                setDateOrNull(statement, 3, settings.paidStartDate)
                setIntOrNull(statement, 4, settings.basePriceMinor)
                setIntOrNull(statement, 5, settings.priceOverrideMinor)
                statement.setString(6, settings.currency)
                statement.setLong(7, actorUserId)
                statement.executeUpdate()
            }
        } catch (e: SQLException) {
            connection.prepareStatement(
                """
                    UPDATE venue_subscription_settings
                    SET trial_end_date = ?,
                        paid_start_date = ?,
                        base_price_minor = ?,
                        price_override_minor = ?,
                        currency = ?,
                        updated_at = CURRENT_TIMESTAMP,
                        updated_by_user_id = ?
                    WHERE venue_id = ?
                """.trimIndent()
            ).use { statement ->
                setDateOrNull(statement, 1, settings.trialEndDate)
                setDateOrNull(statement, 2, settings.paidStartDate)
                setIntOrNull(statement, 3, settings.basePriceMinor)
                setIntOrNull(statement, 4, settings.priceOverrideMinor)
                statement.setString(5, settings.currency)
                statement.setLong(6, actorUserId)
                statement.setLong(7, settings.venueId)
                statement.executeUpdate()
            }
        }
    }

    private fun venueExists(connection: Connection, venueId: Long): Boolean {
        connection.prepareStatement(
            "SELECT 1 FROM venues WHERE id = ?"
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                return rs.next()
            }
        }
    }

    private fun ResultSet.getNullableInt(column: String): Int? {
        val value = getInt(column)
        return if (wasNull()) null else value
    }

    private fun setDateOrNull(
        statement: java.sql.PreparedStatement,
        index: Int,
        value: LocalDate?
    ) {
        if (value == null) {
            statement.setNull(index, Types.DATE)
        } else {
            statement.setDate(index, java.sql.Date.valueOf(value))
        }
    }

    private fun setIntOrNull(statement: java.sql.PreparedStatement, index: Int, value: Int?) {
        if (value == null) {
            statement.setNull(index, Types.INTEGER)
        } else {
            statement.setInt(index, value)
        }
    }

    data class PlatformSubscriptionSettingsUpdate(
        val trialEndDate: LocalDate?,
        val paidStartDate: LocalDate?,
        val basePriceMinor: Int?,
        val priceOverrideMinor: Int?,
        val currency: String?
    )

    companion object {
        const val DEFAULT_CURRENCY = "RUB"
    }
}
