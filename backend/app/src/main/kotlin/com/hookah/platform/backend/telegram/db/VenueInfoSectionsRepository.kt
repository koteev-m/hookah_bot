package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException
import javax.sql.DataSource

data class VenueInfoSection(
    val id: Long,
    val venueId: Long,
    val title: String,
    val sectionType: String,
    val sortOrder: Int,
    val isVisible: Boolean,
    val textContent: String?,
)

class VenueInfoSectionsRepository(private val dataSource: DataSource?) {
    private data class DefaultSection(
        val type: String,
        val title: String,
        val sortOrder: Int,
    )

    private val defaultSections =
        listOf(
            DefaultSection(type = "about", title = "О заведении", sortOrder = 10),
            DefaultSection(type = "rules", title = "Правила посещения", sortOrder = 20),
            DefaultSection(type = "cork_fee", title = "Пробковый сбор", sortOrder = 30),
            DefaultSection(type = "faq", title = "FAQ", sortOrder = 40),
            DefaultSection(type = "menu", title = "Меню", sortOrder = 50),
        )

    suspend fun ensureDefaultSections(venueId: Long): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val venueExists =
                            connection.prepareStatement(
                                """
                                SELECT 1
                                FROM venues
                                WHERE id = ?
                                LIMIT 1
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, venueId)
                                statement.executeQuery().use { rs -> rs.next() }
                            }
                        if (!venueExists) {
                            connection.rollback()
                            return@withContext false
                        }
                        val existingTypes =
                            connection.prepareStatement(
                                """
                                SELECT section_type
                                FROM venue_info_sections
                                WHERE venue_id = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, venueId)
                                statement.executeQuery().use { rs ->
                                    buildSet {
                                        while (rs.next()) {
                                            add(rs.getString("section_type").orEmpty())
                                        }
                                    }
                                }
                            }
                        defaultSections
                            .filterNot { section -> existingTypes.contains(section.type) }
                            .forEach { section ->
                                connection.prepareStatement(
                                    """
                                    INSERT INTO venue_info_sections (
                                        venue_id,
                                        title,
                                        section_type,
                                        sort_order,
                                        is_visible,
                                        text_content
                                    ) VALUES (?, ?, ?, ?, ?, ?)
                                    """.trimIndent(),
                                ).use { statement ->
                                    statement.setLong(1, venueId)
                                    statement.setString(2, section.title)
                                    statement.setString(3, section.type)
                                    statement.setInt(4, section.sortOrder)
                                    statement.setBoolean(5, true)
                                    statement.setString(6, null)
                                    statement.executeUpdate()
                                }
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

    suspend fun listSections(venueId: Long): List<VenueInfoSection> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT id, venue_id, title, section_type, sort_order, is_visible, text_content
                        FROM venue_info_sections
                        WHERE venue_id = ?
                        ORDER BY sort_order ASC, id ASC
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<VenueInfoSection>()
                            while (rs.next()) {
                                result += mapSection(rs)
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

    suspend fun findSectionById(
        venueId: Long,
        sectionId: Long,
    ): VenueInfoSection? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT id, venue_id, title, section_type, sort_order, is_visible, text_content
                        FROM venue_info_sections
                        WHERE venue_id = ? AND id = ?
                        LIMIT 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, sectionId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) mapSection(rs) else null
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun createCustomSection(
        venueId: Long,
        title: String,
    ): VenueInfoSection? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val venueExists =
                            connection.prepareStatement(
                                """
                                SELECT 1
                                FROM venues
                                WHERE id = ?
                                LIMIT 1
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, venueId)
                                statement.executeQuery().use { rs -> rs.next() }
                            }
                        if (!venueExists) {
                            connection.rollback()
                            return@withContext null
                        }
                        val nextSortOrder =
                            connection.prepareStatement(
                                """
                                SELECT COALESCE(MAX(sort_order), 0) + 10 AS next_sort
                                FROM venue_info_sections
                                WHERE venue_id = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, venueId)
                                statement.executeQuery().use { rs ->
                                    if (rs.next()) rs.getInt("next_sort") else 10
                                }
                            }
                        val sectionId =
                            connection.prepareStatement(
                                """
                                INSERT INTO venue_info_sections (
                                    venue_id,
                                    title,
                                    section_type,
                                    sort_order,
                                    is_visible,
                                    text_content
                                ) VALUES (?, ?, ?, ?, ?, ?)
                                """.trimIndent(),
                                java.sql.Statement.RETURN_GENERATED_KEYS,
                            ).use { statement ->
                                statement.setLong(1, venueId)
                                statement.setString(2, title)
                                statement.setString(3, "custom")
                                statement.setInt(4, nextSortOrder)
                                statement.setBoolean(5, true)
                                statement.setString(6, null)
                                statement.executeUpdate()
                                statement.generatedKeys.use { keys ->
                                    if (keys.next()) keys.getLong(1) else null
                                }
                            }
                        if (sectionId == null) {
                            connection.rollback()
                            return@withContext null
                        }
                        connection.commit()
                        VenueInfoSection(
                            id = sectionId,
                            venueId = venueId,
                            title = title,
                            sectionType = "custom",
                            sortOrder = nextSortOrder,
                            isVisible = true,
                            textContent = null,
                        )
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

    suspend fun updateSectionText(
        venueId: Long,
        sectionId: Long,
        textContent: String?,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        UPDATE venue_info_sections
                        SET text_content = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE venue_id = ? AND id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, textContent)
                        statement.setLong(2, venueId)
                        statement.setLong(3, sectionId)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun toggleSectionVisibility(
        venueId: Long,
        sectionId: Long,
    ): VenueInfoSection? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE venue_info_sections
                            SET is_visible = CASE WHEN is_visible THEN FALSE ELSE TRUE END,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE venue_id = ? AND id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, venueId)
                            statement.setLong(2, sectionId)
                            statement.executeUpdate() > 0
                        }
                    if (!updated) {
                        null
                    } else {
                        findSectionById(venueId, sectionId)
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun mapSection(rs: java.sql.ResultSet): VenueInfoSection =
        VenueInfoSection(
            id = rs.getLong("id"),
            venueId = rs.getLong("venue_id"),
            title = rs.getString("title"),
            sectionType = rs.getString("section_type"),
            sortOrder = rs.getInt("sort_order"),
            isVisible = rs.getBoolean("is_visible"),
            textContent = rs.getString("text_content"),
        )
}
