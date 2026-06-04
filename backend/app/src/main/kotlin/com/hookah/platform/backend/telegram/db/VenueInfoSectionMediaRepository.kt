package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException
import javax.sql.DataSource

data class VenueInfoSectionMediaAttachment(
    val id: Long,
    val sectionId: Long,
    val mediaType: String,
    val telegramFileId: String,
    val sortOrder: Int,
)

class VenueInfoSectionMediaRepository(private val dataSource: DataSource?) {
    suspend fun addMediaAttachment(
        sectionId: Long,
        mediaType: String,
        telegramFileId: String,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val sectionExists =
                            connection.prepareStatement(
                                """
                                SELECT 1
                                FROM venue_info_sections
                                WHERE id = ?
                                LIMIT 1
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, sectionId)
                                statement.executeQuery().use { rs -> rs.next() }
                            }
                        if (!sectionExists) {
                            connection.rollback()
                            return@withContext false
                        }
                        val nextSortOrder =
                            connection.prepareStatement(
                                """
                                SELECT COALESCE(MAX(sort_order), -1) + 1 AS next_sort
                                FROM venue_info_section_media
                                WHERE section_id = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, sectionId)
                                statement.executeQuery().use { rs ->
                                    if (rs.next()) rs.getInt("next_sort") else 0
                                }
                            }
                        val inserted =
                            connection.prepareStatement(
                                """
                                INSERT INTO venue_info_section_media (
                                    section_id,
                                    media_type,
                                    telegram_file_id,
                                    sort_order
                                ) VALUES (?, ?, ?, ?)
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, sectionId)
                                statement.setString(2, mediaType)
                                statement.setString(3, telegramFileId)
                                statement.setInt(4, nextSortOrder)
                                statement.executeUpdate() > 0
                            }
                        if (!inserted) {
                            connection.rollback()
                            return@withContext false
                        }
                        connection.commit()
                        true
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun countBySectionIds(sectionIds: Collection<Long>): Map<Long, Int> {
        if (sectionIds.isEmpty()) return emptyMap()
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val placeholders = sectionIds.joinToString(",") { "?" }
                    connection.prepareStatement(
                        """
                        SELECT section_id, COUNT(*) AS media_count
                        FROM venue_info_section_media
                        WHERE section_id IN ($placeholders)
                        GROUP BY section_id
                        """.trimIndent(),
                    ).use { statement ->
                        sectionIds.forEachIndexed { index, sectionId ->
                            statement.setLong(index + 1, sectionId)
                        }
                        statement.executeQuery().use { rs ->
                            buildMap {
                                while (rs.next()) {
                                    put(rs.getLong("section_id"), rs.getInt("media_count"))
                                }
                            }
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun countBySectionId(sectionId: Long): Int {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT COUNT(*) AS media_count
                        FROM venue_info_section_media
                        WHERE section_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, sectionId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) rs.getInt("media_count") else 0
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listBySectionId(sectionId: Long): List<VenueInfoSectionMediaAttachment> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT id, section_id, media_type, telegram_file_id, sort_order
                        FROM venue_info_section_media
                        WHERE section_id = ?
                        ORDER BY sort_order ASC, id ASC
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, sectionId)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<VenueInfoSectionMediaAttachment>()
                            while (rs.next()) {
                                result +=
                                    VenueInfoSectionMediaAttachment(
                                        id = rs.getLong("id"),
                                        sectionId = rs.getLong("section_id"),
                                        mediaType = rs.getString("media_type").orEmpty(),
                                        telegramFileId = rs.getString("telegram_file_id").orEmpty(),
                                        sortOrder = rs.getInt("sort_order"),
                                    )
                            }
                            result
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findById(
        sectionId: Long,
        mediaId: Long,
    ): VenueInfoSectionMediaAttachment? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT id, section_id, media_type, telegram_file_id, sort_order
                        FROM venue_info_section_media
                        WHERE section_id = ? AND id = ?
                        LIMIT 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, sectionId)
                        statement.setLong(2, mediaId)
                        statement.executeQuery().use { rs ->
                            if (!rs.next()) {
                                null
                            } else {
                                VenueInfoSectionMediaAttachment(
                                    id = rs.getLong("id"),
                                    sectionId = rs.getLong("section_id"),
                                    mediaType = rs.getString("media_type").orEmpty(),
                                    telegramFileId = rs.getString("telegram_file_id").orEmpty(),
                                    sortOrder = rs.getInt("sort_order"),
                                )
                            }
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun deleteAttachment(
        sectionId: Long,
        mediaId: Long,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        DELETE FROM venue_info_section_media
                        WHERE section_id = ? AND id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, sectionId)
                        statement.setLong(2, mediaId)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }
}
