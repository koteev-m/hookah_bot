package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

data class VenueSettings(
    val venueId: Long,
    val notifyOrdersEnabled: Boolean,
    val notifyStaffCallsEnabled: Boolean,
    val notifyCancellationsEnabled: Boolean,
    val timezone: String?,
)

enum class VenueNotificationSetting {
    ORDERS,
    STAFF_CALLS,
    CANCELLATIONS,
}

data class VenueOperationalResetResult(
    val ordersDeleted: Int,
    val batchesDeleted: Int,
    val itemsDeleted: Int,
    val staffCallsDeleted: Int,
)

class VenueSettingsRepository(private val dataSource: DataSource?) {
    suspend fun find(venueId: Long): VenueSettings? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    selectSettings(connection, venueId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun getOrCreate(
        venueId: Long,
        fallbackTimezone: String,
    ): VenueSettings {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    selectSettings(connection, venueId)
                        ?: run {
                            insertDefaults(connection, venueId, fallbackTimezone)
                            selectSettings(connection, venueId) ?: defaultSettings(venueId, fallbackTimezone)
                        }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun toggleNotification(
        venueId: Long,
        setting: VenueNotificationSetting,
        fallbackTimezone: String,
    ): VenueSettings {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val current =
                        selectSettings(connection, venueId)
                            ?: run {
                                insertDefaults(connection, venueId, fallbackTimezone)
                                selectSettings(connection, venueId) ?: defaultSettings(venueId, fallbackTimezone)
                            }
                    val (column, nextValue) =
                        when (setting) {
                            VenueNotificationSetting.ORDERS ->
                                "notify_orders_enabled" to !current.notifyOrdersEnabled
                            VenueNotificationSetting.STAFF_CALLS ->
                                "notify_staff_calls_enabled" to !current.notifyStaffCallsEnabled
                            VenueNotificationSetting.CANCELLATIONS ->
                                "notify_cancellations_enabled" to !current.notifyCancellationsEnabled
                        }
                    connection.prepareStatement(
                        """
                        UPDATE venue_settings
                        SET $column = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE venue_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setBoolean(1, nextValue)
                        statement.setLong(2, venueId)
                        statement.executeUpdate()
                    }
                    selectSettings(connection, venueId) ?: defaultSettings(venueId, fallbackTimezone)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun resetDevOperationalData(venueId: Long): VenueOperationalResetResult {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val previousAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val itemsCount =
                            count(
                                connection,
                                """
                                SELECT COUNT(*)
                                FROM order_batch_items obi
                                JOIN order_batches ob ON ob.id = obi.order_batch_id
                                JOIN orders o ON o.id = ob.order_id
                                WHERE o.venue_id = ?
                                """.trimIndent(),
                                venueId,
                            )
                        val batchesCount =
                            count(
                                connection,
                                """
                                SELECT COUNT(*)
                                FROM order_batches ob
                                JOIN orders o ON o.id = ob.order_id
                                WHERE o.venue_id = ?
                                """.trimIndent(),
                                venueId,
                            )
                        val ordersCount = count(connection, "SELECT COUNT(*) FROM orders WHERE venue_id = ?", venueId)
                        val staffCallsCount = count(connection, "SELECT COUNT(*) FROM staff_calls WHERE venue_id = ?", venueId)

                        executeDelete(connection, "DELETE FROM staff_calls WHERE venue_id = ?", venueId)
                        executeDelete(connection, "DELETE FROM orders WHERE venue_id = ?", venueId)

                        connection.commit()
                        VenueOperationalResetResult(
                            ordersDeleted = ordersCount,
                            batchesDeleted = batchesCount,
                            itemsDeleted = itemsCount,
                            staffCallsDeleted = staffCallsCount,
                        )
                    } catch (e: SQLException) {
                        connection.rollback()
                        throw e
                    } finally {
                        connection.autoCommit = previousAutoCommit
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun selectSettings(
        connection: Connection,
        venueId: Long,
    ): VenueSettings? =
        connection.prepareStatement(
            """
            SELECT venue_id,
                   notify_orders_enabled,
                   notify_staff_calls_enabled,
                   notify_cancellations_enabled,
                   timezone
            FROM venue_settings
            WHERE venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toVenueSettings() else null }
        }

    private fun insertDefaults(
        connection: Connection,
        venueId: Long,
        timezone: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO venue_settings (
                venue_id,
                notify_orders_enabled,
                notify_staff_calls_enabled,
                notify_cancellations_enabled,
                timezone
            )
            VALUES (?, TRUE, TRUE, TRUE, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, timezone)
            statement.executeUpdate()
        }
    }

    private fun count(
        connection: Connection,
        sql: String,
        venueId: Long,
    ): Int =
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun executeDelete(
        connection: Connection,
        sql: String,
        venueId: Long,
    ) {
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, venueId)
            statement.executeUpdate()
        }
    }

    private fun ResultSet.toVenueSettings(): VenueSettings =
        VenueSettings(
            venueId = getLong("venue_id"),
            notifyOrdersEnabled = getBoolean("notify_orders_enabled"),
            notifyStaffCallsEnabled = getBoolean("notify_staff_calls_enabled"),
            notifyCancellationsEnabled = getBoolean("notify_cancellations_enabled"),
            timezone = getString("timezone"),
        )

    private fun defaultSettings(
        venueId: Long,
        timezone: String,
    ): VenueSettings =
        VenueSettings(
            venueId = venueId,
            notifyOrdersEnabled = true,
            notifyStaffCallsEnabled = true,
            notifyCancellationsEnabled = true,
            timezone = timezone,
        )
}
