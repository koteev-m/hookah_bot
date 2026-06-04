package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import javax.sql.DataSource
import org.slf4j.LoggerFactory

data class VenueSettings(
    val venueId: Long,
    val notifyOrdersEnabled: Boolean,
    val notifyStaffCallsEnabled: Boolean,
    val notifyCancellationsEnabled: Boolean,
    val timezone: String?,
    val publicReviewUrl: String? = null,
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

data class GuestPublicReviewCtaState(
    val userId: Long,
    val venueId: Long,
    val firstShownAt: Instant,
    val lastShownAt: Instant,
    val clickedAt: Instant?,
    val markedDoneAt: Instant?,
)

class VenueSettingsRepository(private val dataSource: DataSource?) {
    private val logger = LoggerFactory.getLogger(VenueSettingsRepository::class.java)

    companion object {
        const val DEFAULT_AUTO_TIMEZONE = "Europe/Moscow"
        const val MAX_PUBLIC_REVIEW_URL_LENGTH = 2048

        private val cityTimezoneRules =
            listOf(
                CityTimezoneRule("Asia/Tomsk", listOf("томск")),
                CityTimezoneRule("Europe/Moscow", listOf("москва", "москов", "санкт петербург", "спб", "петербург", "питер", "казань", "нижний новгород", "ростов", "краснодар", "сочи")),
                CityTimezoneRule("Asia/Yekaterinburg", listOf("екатеринбург", "свердловск", "челябинск", "уфа", "пермь", "тюмень")),
                CityTimezoneRule("Asia/Novosibirsk", listOf("новосибирск", "барнаул")),
                CityTimezoneRule("Asia/Omsk", listOf("омск")),
                CityTimezoneRule("Asia/Krasnoyarsk", listOf("красноярск")),
                CityTimezoneRule("Asia/Irkutsk", listOf("иркутск")),
                CityTimezoneRule("Asia/Yakutsk", listOf("якутск")),
                CityTimezoneRule("Asia/Vladivostok", listOf("владивосток")),
            )

        fun inferVenueTimezone(
            city: String?,
            address: String?,
        ): String? {
            val source = normalizeLocationForTimezone(listOfNotNull(city, address).joinToString(" "))
            if (source.isBlank()) return null
            return cityTimezoneRules.firstOrNull { rule ->
                rule.matches.any { value -> source.contains(value) }
            }?.timezone
        }

        fun resolveInferredVenueTimezone(
            city: String?,
            address: String?,
            fallback: String = DEFAULT_AUTO_TIMEZONE,
        ): String = inferVenueTimezone(city, address) ?: fallback

        private fun normalizeLocationForTimezone(value: String): String =
            value
                .lowercase(Locale.ROOT)
                .replace('ё', 'е')
                .replace(Regex("""[^\p{L}\p{Nd}]+"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
    }

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

    suspend fun updateTimezone(
        venueId: Long,
        timezone: String,
        fallbackTimezone: String,
    ): VenueSettings {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val existing =
                        selectSettings(connection, venueId)
                            ?: run {
                                insertDefaults(connection, venueId, fallbackTimezone)
                                selectSettings(connection, venueId) ?: defaultSettings(venueId, fallbackTimezone)
                            }
                    connection.prepareStatement(
                        """
                        UPDATE venue_settings
                        SET timezone = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE venue_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, timezone)
                        statement.setLong(2, venueId)
                        statement.executeUpdate()
                    }
                    selectSettings(connection, venueId) ?: existing.copy(timezone = timezone)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateTimezoneFromLocation(
        venueId: Long,
        city: String?,
        address: String?,
        fallbackTimezone: String = DEFAULT_AUTO_TIMEZONE,
    ): VenueSettings =
        updateTimezone(
            venueId = venueId,
            timezone = resolveInferredVenueTimezone(city, address, fallbackTimezone),
            fallbackTimezone = fallbackTimezone,
        )

    suspend fun resolveZoneId(
        venueId: Long,
        fallback: ZoneId = ZoneId.systemDefault(),
    ): ZoneId {
        val ds = dataSource ?: return fallback
        val raw =
            try {
                withContext(Dispatchers.IO) {
                    ds.connection.use { connection ->
                        selectSettings(connection, venueId)?.timezone
                    }
                }
            } catch (e: SQLException) {
                return fallback
            }
        val timezone = raw?.takeIf { it.isNotBlank() } ?: return fallback
        return runCatching { ZoneId.of(timezone) }
            .onFailure {
                logger.warn("Invalid venue timezone venue_id={} timezone={}", venueId, timezone)
            }
            .getOrDefault(fallback)
    }

    suspend fun getPublicReviewUrl(venueId: Long): String? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    selectSettings(connection, venueId)?.publicReviewUrl?.takeIf { isValidPublicReviewUrl(it) }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updatePublicReviewUrl(
        venueId: Long,
        url: String,
    ): VenueSettings {
        val normalized = normalizePublicReviewUrl(url)
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    ensureSettings(connection, venueId, DEFAULT_AUTO_TIMEZONE)
                    connection.prepareStatement(
                        """
                        UPDATE venue_settings
                        SET public_review_url = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE venue_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, normalized)
                        statement.setLong(2, venueId)
                        statement.executeUpdate()
                    }
                    selectSettings(connection, venueId) ?: defaultSettings(venueId, DEFAULT_AUTO_TIMEZONE)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun clearPublicReviewUrl(venueId: Long): VenueSettings {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    ensureSettings(connection, venueId, DEFAULT_AUTO_TIMEZONE)
                    connection.prepareStatement(
                        """
                        UPDATE venue_settings
                        SET public_review_url = NULL,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE venue_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeUpdate()
                    }
                    selectSettings(connection, venueId) ?: defaultSettings(venueId, DEFAULT_AUTO_TIMEZONE)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun hasPublicReviewCtaBeenShown(
        userId: Long,
        venueId: Long,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT 1
                        FROM guest_public_review_cta
                        WHERE user_id = ? AND venue_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.setLong(2, venueId)
                        statement.executeQuery().use { rs -> rs.next() }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun markPublicReviewCtaShown(
        userId: Long,
        venueId: Long,
        now: Instant = Instant.now(),
    ): GuestPublicReviewCtaState {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    upsertPublicReviewCta(connection, userId, venueId, now, CtaTimestampUpdate.SHOWN)
                    selectPublicReviewCta(connection, userId, venueId)
                        ?: error("public review CTA state was not saved")
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun markPublicReviewCtaClicked(
        userId: Long,
        venueId: Long,
        now: Instant = Instant.now(),
    ): GuestPublicReviewCtaState {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    upsertPublicReviewCta(connection, userId, venueId, now, CtaTimestampUpdate.CLICKED)
                    selectPublicReviewCta(connection, userId, venueId)
                        ?: error("public review CTA state was not saved")
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun markPublicReviewCtaDone(
        userId: Long,
        venueId: Long,
        now: Instant = Instant.now(),
    ): GuestPublicReviewCtaState {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    upsertPublicReviewCta(connection, userId, venueId, now, CtaTimestampUpdate.DONE)
                    selectPublicReviewCta(connection, userId, venueId)
                        ?: error("public review CTA state was not saved")
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
                   timezone,
                   public_review_url
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

    private fun ensureSettings(
        connection: Connection,
        venueId: Long,
        fallbackTimezone: String,
    ): VenueSettings =
        selectSettings(connection, venueId)
            ?: run {
                insertDefaults(connection, venueId, fallbackTimezone)
                selectSettings(connection, venueId) ?: defaultSettings(venueId, fallbackTimezone)
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
            publicReviewUrl = getString("public_review_url"),
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
            publicReviewUrl = null,
        )

    private fun normalizePublicReviewUrl(url: String): String {
        val normalized = url.trim()
        require(isValidPublicReviewUrl(normalized)) {
            "public review url must start with https:// and be at most $MAX_PUBLIC_REVIEW_URL_LENGTH characters"
        }
        return normalized
    }

    private fun isValidPublicReviewUrl(url: String): Boolean {
        val normalized = url.trim()
        return normalized.isNotBlank() &&
            normalized.length <= MAX_PUBLIC_REVIEW_URL_LENGTH &&
            normalized.startsWith("https://")
    }

    private fun upsertPublicReviewCta(
        connection: Connection,
        userId: Long,
        venueId: Long,
        now: Instant,
        update: CtaTimestampUpdate,
    ) {
        val timestamp = Timestamp.from(now)
        val updated =
            connection.prepareStatement(
                when (update) {
                    CtaTimestampUpdate.SHOWN ->
                        """
                        UPDATE guest_public_review_cta
                        SET last_shown_at = ?
                        WHERE user_id = ? AND venue_id = ?
                        """.trimIndent()
                    CtaTimestampUpdate.CLICKED ->
                        """
                        UPDATE guest_public_review_cta
                        SET clicked_at = ?,
                            last_shown_at = ?
                        WHERE user_id = ? AND venue_id = ?
                        """.trimIndent()
                    CtaTimestampUpdate.DONE ->
                        """
                        UPDATE guest_public_review_cta
                        SET marked_done_at = ?,
                            last_shown_at = ?
                        WHERE user_id = ? AND venue_id = ?
                        """.trimIndent()
                },
            ).use { statement ->
                when (update) {
                    CtaTimestampUpdate.SHOWN -> {
                        statement.setTimestamp(1, timestamp)
                        statement.setLong(2, userId)
                        statement.setLong(3, venueId)
                    }
                    CtaTimestampUpdate.CLICKED,
                    CtaTimestampUpdate.DONE,
                    -> {
                        statement.setTimestamp(1, timestamp)
                        statement.setTimestamp(2, timestamp)
                        statement.setLong(3, userId)
                        statement.setLong(4, venueId)
                    }
                }
                statement.executeUpdate()
            }
        if (updated > 0) return
        connection.prepareStatement(
            """
            INSERT INTO guest_public_review_cta (
                user_id,
                venue_id,
                first_shown_at,
                last_shown_at,
                clicked_at,
                marked_done_at
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, venueId)
            statement.setTimestamp(3, timestamp)
            statement.setTimestamp(4, timestamp)
            if (update == CtaTimestampUpdate.CLICKED) {
                statement.setTimestamp(5, timestamp)
            } else {
                statement.setTimestamp(5, null)
            }
            if (update == CtaTimestampUpdate.DONE) {
                statement.setTimestamp(6, timestamp)
            } else {
                statement.setTimestamp(6, null)
            }
            statement.executeUpdate()
        }
    }

    private fun selectPublicReviewCta(
        connection: Connection,
        userId: Long,
        venueId: Long,
    ): GuestPublicReviewCtaState? =
        connection.prepareStatement(
            """
            SELECT user_id,
                   venue_id,
                   first_shown_at,
                   last_shown_at,
                   clicked_at,
                   marked_done_at
            FROM guest_public_review_cta
            WHERE user_id = ? AND venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    GuestPublicReviewCtaState(
                        userId = rs.getLong("user_id"),
                        venueId = rs.getLong("venue_id"),
                        firstShownAt = rs.getTimestamp("first_shown_at").toInstant(),
                        lastShownAt = rs.getTimestamp("last_shown_at").toInstant(),
                        clickedAt = rs.getTimestamp("clicked_at")?.toInstant(),
                        markedDoneAt = rs.getTimestamp("marked_done_at")?.toInstant(),
                    )
                } else {
                    null
                }
            }
        }
}

private enum class CtaTimestampUpdate {
    SHOWN,
    CLICKED,
    DONE,
}

private data class CityTimezoneRule(
    val timezone: String,
    val matches: List<String>,
)
