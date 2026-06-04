package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.util.Locale
import javax.sql.DataSource

enum class VenuePromotionMediaType(val dbValue: String) {
    IMAGE("IMAGE"),
    ;

    companion object {
        fun fromDb(value: String?): VenuePromotionMediaType? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.dbValue == normalized }
        }
    }
}

data class VenuePromotionMedia(
    val id: Long,
    val promotionId: Long,
    val mediaType: VenuePromotionMediaType,
    val telegramFileId: String,
    val caption: String?,
    val sortOrder: Int,
    val createdAt: Instant,
)

class VenuePromotionMediaRepository(private val dataSource: DataSource?) {
    suspend fun addMedia(
        venueId: Long,
        promotionId: Long,
        mediaType: VenuePromotionMediaType,
        fileId: String,
        caption: String? = null,
    ): VenuePromotionMedia? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val normalizedFileId = requireFileId(fileId)
        val normalizedCaption = caption?.trim()?.takeIf { it.isNotBlank() }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    if (!promotionBelongsToVenue(connection, venueId, promotionId)) {
                        return@withContext null
                    }
                    val nextSortOrder = nextSortOrder(connection, promotionId)
                    val id =
                        connection.prepareStatement(
                            """
                            INSERT INTO venue_promotion_media (
                                promotion_id, media_type, telegram_file_id, caption, sort_order
                            ) VALUES (?, ?, ?, ?, ?)
                            """.trimIndent(),
                            Statement.RETURN_GENERATED_KEYS,
                        ).use { statement ->
                            statement.setLong(1, promotionId)
                            statement.setString(2, mediaType.dbValue)
                            statement.setString(3, normalizedFileId)
                            statement.setString(4, normalizedCaption)
                            statement.setInt(5, nextSortOrder)
                            statement.executeUpdate()
                            statement.generatedKeys.use { keys ->
                                if (!keys.next()) throw SQLException("No generated key for venue promotion media")
                                keys.getLong(1)
                            }
                        }
                    selectMedia(connection, id)
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun replacePrimaryImage(
        venueId: Long,
        promotionId: Long,
        fileId: String,
        caption: String? = null,
    ): VenuePromotionMedia? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val normalizedFileId = requireFileId(fileId)
        val normalizedCaption = caption?.trim()?.takeIf { it.isNotBlank() }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        if (!promotionBelongsToVenue(connection, venueId, promotionId)) {
                            connection.rollback()
                            return@withContext null
                        }
                        connection.prepareStatement(
                            """
                            DELETE FROM venue_promotion_media
                            WHERE promotion_id = ? AND media_type = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, promotionId)
                            statement.setString(2, VenuePromotionMediaType.IMAGE.dbValue)
                            statement.executeUpdate()
                        }
                        val id =
                            connection.prepareStatement(
                                """
                                INSERT INTO venue_promotion_media (
                                    promotion_id, media_type, telegram_file_id, caption, sort_order
                                ) VALUES (?, ?, ?, ?, 0)
                                """.trimIndent(),
                                Statement.RETURN_GENERATED_KEYS,
                            ).use { statement ->
                                statement.setLong(1, promotionId)
                                statement.setString(2, VenuePromotionMediaType.IMAGE.dbValue)
                                statement.setString(3, normalizedFileId)
                                statement.setString(4, normalizedCaption)
                                statement.executeUpdate()
                                statement.generatedKeys.use { keys ->
                                    if (!keys.next()) throw SQLException("No generated key for venue promotion media")
                                    keys.getLong(1)
                                }
                            }
                        val media = selectMedia(connection, id)
                        connection.commit()
                        media
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching {
                            connection.autoCommit = initialAutoCommit
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun deletePrimaryImage(
        venueId: Long,
        promotionId: Long,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    if (!promotionBelongsToVenue(connection, venueId, promotionId)) {
                        return@withContext false
                    }
                    connection.prepareStatement(
                        """
                        DELETE FROM venue_promotion_media
                        WHERE promotion_id = ? AND media_type = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, promotionId)
                        statement.setString(2, VenuePromotionMediaType.IMAGE.dbValue)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun deleteMedia(
        venueId: Long,
        promotionId: Long,
        mediaId: Long,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    if (!promotionBelongsToVenue(connection, venueId, promotionId)) {
                        return@withContext false
                    }
                    connection.prepareStatement(
                        """
                        DELETE FROM venue_promotion_media
                        WHERE promotion_id = ? AND id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, promotionId)
                        statement.setLong(2, mediaId)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listByPromotionId(promotionId: Long): List<VenuePromotionMedia> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT id, promotion_id, media_type, telegram_file_id, caption, sort_order, created_at
                        FROM venue_promotion_media
                        WHERE promotion_id = ?
                        ORDER BY sort_order ASC, id ASC
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, promotionId)
                        statement.executeQuery().use { rs -> rs.toMediaList() }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun getPrimaryImage(promotionId: Long): VenuePromotionMedia? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT id, promotion_id, media_type, telegram_file_id, caption, sort_order, created_at
                        FROM venue_promotion_media
                        WHERE promotion_id = ? AND media_type = ?
                        ORDER BY sort_order ASC, id ASC
                        LIMIT 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, promotionId)
                        statement.setString(2, VenuePromotionMediaType.IMAGE.dbValue)
                        statement.executeQuery().use { rs -> if (rs.next()) rs.toMedia() else null }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun promotionBelongsToVenue(
        connection: Connection,
        venueId: Long,
        promotionId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT 1
            FROM venue_promotions
            WHERE venue_id = ? AND id = ?
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, promotionId)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun nextSortOrder(
        connection: Connection,
        promotionId: Long,
    ): Int =
        connection.prepareStatement(
            """
            SELECT COALESCE(MAX(sort_order), -1) + 1 AS next_sort
            FROM venue_promotion_media
            WHERE promotion_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, promotionId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getInt("next_sort") else 0 }
        }

    private fun selectMedia(
        connection: Connection,
        mediaId: Long,
    ): VenuePromotionMedia? =
        connection.prepareStatement(
            """
            SELECT id, promotion_id, media_type, telegram_file_id, caption, sort_order, created_at
            FROM venue_promotion_media
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, mediaId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toMedia() else null }
        }

    private fun ResultSet.toMediaList(): List<VenuePromotionMedia> {
        val result = mutableListOf<VenuePromotionMedia>()
        while (next()) {
            toMedia()?.let { result += it }
        }
        return result
    }

    private fun ResultSet.toMedia(): VenuePromotionMedia? {
        val mediaType = VenuePromotionMediaType.fromDb(getString("media_type")) ?: return null
        return VenuePromotionMedia(
            id = getLong("id"),
            promotionId = getLong("promotion_id"),
            mediaType = mediaType,
            telegramFileId = getString("telegram_file_id"),
            caption = getString("caption"),
            sortOrder = getInt("sort_order"),
            createdAt = getTimestamp("created_at").toInstant(),
        )
    }

    private fun requireFileId(fileId: String): String {
        val trimmed = fileId.trim()
        require(trimmed.isNotBlank()) { "telegram file id must not be blank" }
        return trimmed
    }
}
