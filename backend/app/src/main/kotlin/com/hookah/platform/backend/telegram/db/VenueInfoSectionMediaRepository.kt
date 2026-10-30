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
    val publicId: String? = null,
    val sourceType: String = "TELEGRAM_FILE",
    val objectKey: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Int? = null,
    val checksum: String? = null,
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
                                FOR UPDATE
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
                        connection.prepareStatement(
                            "UPDATE venue_info_sections SET media_revision=media_revision+1 WHERE id=?",
                        ).use {
                            it.setLong(1, sectionId)
                            it.executeUpdate()
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
                        WHERE status='READY' AND is_visible=TRUE AND section_id IN ($placeholders)
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
                        WHERE section_id = ? AND status='READY' AND is_visible=TRUE
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
                        SELECT *
                        FROM venue_info_section_media
                        WHERE section_id = ? AND status='READY' AND is_visible=TRUE
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
                                        publicId = rs.getString("public_id"), sourceType = rs.getString("source_type"),
                                        objectKey = rs.getString("object_key"), mimeType = rs.getString("mime_type"),
                                        sizeBytes = rs.getObject("size_bytes")?.let { rs.getInt("size_bytes") },
                                        checksum = rs.getString("checksum_sha256"),
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
                        SELECT *
                        FROM venue_info_section_media
                        WHERE section_id = ? AND id = ? AND status='READY' AND is_visible=TRUE
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
                                    publicId = rs.getString("public_id"), sourceType = rs.getString("source_type"),
                                    objectKey = rs.getString("object_key"), mimeType = rs.getString("mime_type"),
                                    sizeBytes =
                                        rs.getObject(
                                            "size_bytes",
                                        )?.let { rs.getInt("size_bytes") },
                                    checksum = rs.getString("checksum_sha256"),
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

    suspend fun findByReference(
        sectionId: Long,
        reference: String,
    ): VenueInfoSectionMediaAttachment? {
        reference.toLongOrNull()?.let { return findById(sectionId, it) }
        return findManagedByReference(sectionId, reference)?.takeIf { row ->
            listBySectionId(sectionId).any { it.id == row.id }
        }
    }

    suspend fun findManagedByReference(
        sectionId: Long,
        reference: String,
    ): VenueInfoSectionMediaAttachment? =
        withContext(Dispatchers.IO) {
            val ds = dataSource ?: throw DatabaseUnavailableException()
            try {
                ds.connection.use { c ->
                    c.prepareStatement(
                        """
                        SELECT *
                        FROM venue_info_section_media
                        WHERE section_id=?
                        AND ${if (reference.toLongOrNull() != null) "id" else "public_id"}=?
                        AND status='READY'
                        """,
                    ).use {
                            s ->
                        s.setLong(1, sectionId)
                        if (reference.toLongOrNull() != null) {
                            s.setLong(
                                2,
                                reference.toLong(),
                            )
                        } else {
                            s.setString(2, reference)
                        }
                        s.executeQuery().use { rs ->
                            if (!rs.next()) {
                                null
                            } else {
                                VenueInfoSectionMediaAttachment(
                                    rs.getLong(
                                        "id",
                                    ),
                                    sectionId,
                                    rs.getString(
                                        "media_type",
                                    ),
                                    rs.getString(
                                        "telegram_file_id",
                                    ),
                                    rs.getInt(
                                        "sort_order",
                                    ),
                                    rs.getString(
                                        "public_id",
                                    ),
                                    rs.getString(
                                        "source_type",
                                    ),
                                    rs.getString("object_key"), rs.getString("mime_type"),
                                    rs.getObject("size_bytes")?.let {
                                        rs.getInt("size_bytes")
                                    },
                                    rs.getString("checksum_sha256"),
                                )
                            }
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }

    suspend fun deleteAttachment(
        sectionId: Long,
        mediaId: Long,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val ds = dataSource ?: throw DatabaseUnavailableException()
            try {
                ds.connection.use { c ->
                    c.autoCommit = false
                    try {
                        c.prepareStatement("SELECT id FROM venue_info_sections WHERE id=? FOR UPDATE").use {
                                s ->
                            s.setLong(1, sectionId)
                            s.executeQuery().close()
                        }
                        val deleted =
                            c.prepareStatement(
                                """
                                UPDATE venue_info_section_media SET status='DELETED',deleted_at=CURRENT_TIMESTAMP
                                WHERE section_id=?
                                AND id=?
                                AND status='READY'
                                """,
                            ).use {
                                    s ->
                                s.setLong(1, sectionId)
                                s.setLong(2, mediaId)
                                s.executeUpdate() > 0
                            }
                        if (deleted) {
                            c.prepareStatement(
                                "UPDATE venue_info_sections SET media_revision=media_revision+1 WHERE id=?",
                            ).use {
                                    s ->
                                s.setLong(1, sectionId)
                                s.executeUpdate()
                            }
                            c.prepareStatement(
                                """
                                UPDATE venue_info_media_cleanup SET due_at=?
                                WHERE public_id IN (SELECT public_id
                                FROM venue_info_section_media
                                WHERE id=?)
                                """,
                            ).use {
                                    s ->
                                s.setTimestamp(
                                    1,
                                    java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(7 * 86400)),
                                )
                                s.setLong(2, mediaId)
                                s.executeUpdate()
                            }
                        }
                        c.commit()
                        deleted
                    } catch (e: Exception) {
                        c.rollback()
                        throw e
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
}
