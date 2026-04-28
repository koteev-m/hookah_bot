package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

class VenueMenuSectionImagesRepository(private val dataSource: DataSource?) {
    private val botTestVenueNames =
        listOf(
            "Тестовая кальянная",
            "Дым и Лёд",
            "Hookah Lounge 24",
        )

    suspend fun listImageUrlsForCategory(
        venueId: Long,
        categoryId: Long,
    ): List<String> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT mci.image_url
                        FROM menu_category_images mci
                        JOIN menu_categories mc ON mc.id = mci.category_id
                        WHERE mc.venue_id = ?
                          AND mc.id = ?
                        ORDER BY mci.sort_order, mci.id
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, categoryId)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<String>()
                            while (rs.next()) {
                                rs.getString("image_url")?.trim()?.takeIf { it.isNotBlank() }?.let { result += it }
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

    suspend fun ensureBotTestVenueMenuSectionImages() {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        botTestVenueNames.forEach { venueName ->
                            findVenueCategories(connection, venueName).forEach { category ->
                                connection.prepareStatement(
                                    """
                                    INSERT INTO menu_category_images (category_id, image_url, sort_order)
                                    SELECT ?, ?, ?
                                    WHERE NOT EXISTS (
                                        SELECT 1
                                        FROM menu_category_images
                                        WHERE category_id = ?
                                    )
                                    """.trimIndent(),
                                ).use { statement ->
                                    statement.setLong(1, category.id)
                                    statement.setString(
                                        2,
                                        buildBotTestImageUrl(
                                            venueName = category.venueName,
                                            categoryName = category.categoryName,
                                        ),
                                    )
                                    statement.setInt(3, 0)
                                    statement.setLong(4, category.id)
                                    statement.executeUpdate()
                                }
                            }
                        }
                        connection.commit()
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        connection.autoCommit = true
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun findVenueCategories(
        connection: Connection,
        venueName: String,
    ): List<VenueCategoryRef> {
        connection.prepareStatement(
            """
            SELECT mc.id, mc.name, v.name AS venue_name
            FROM menu_categories mc
            JOIN venues v ON v.id = mc.venue_id
            WHERE LOWER(v.name) = LOWER(?)
            ORDER BY mc.sort_order, mc.id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, venueName)
            statement.executeQuery().use { rs ->
                val result = mutableListOf<VenueCategoryRef>()
                while (rs.next()) {
                    result +=
                        VenueCategoryRef(
                            id = rs.getLong("id"),
                            categoryName = rs.getString("name"),
                            venueName = rs.getString("venue_name"),
                        )
                }
                return result
            }
        }
    }

    private fun buildBotTestImageUrl(
        venueName: String,
        categoryName: String,
    ): String {
        val text =
            URLEncoder.encode(
                "$venueName • $categoryName",
                StandardCharsets.UTF_8,
            )
        return "https://placehold.co/1200x1600/png?text=$text"
    }

    private data class VenueCategoryRef(
        val id: Long,
        val categoryName: String,
        val venueName: String,
    )
}
