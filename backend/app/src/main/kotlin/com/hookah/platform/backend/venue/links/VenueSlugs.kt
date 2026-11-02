package com.hookah.platform.backend.venue.links

import java.sql.Connection
import java.sql.SQLException
import java.util.Locale

object VenueSlugs {
    private val cyrillic =
        mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo",
            'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
            'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
            'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
            'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
            'і' to "i", 'ї' to "yi", 'є' to "ye", 'ґ' to "g", 'ў' to "u",
        )
    private val pattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

    fun valid(value: String): Boolean = value.length in 1..64 && pattern.matches(value)

    fun normalize(value: String): String {
        val transliterated = value.lowercase(Locale.ROOT).map { cyrillic[it] ?: it.toString() }.joinToString("")
        return transliterated
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-').take(64).trimEnd('-')
    }

    // The registry owns every issued spelling forever, including after physical venue deletion.
    fun reserve(
        connection: Connection,
        venueId: Long,
        slug: String,
    ): Boolean {
        require(valid(slug))
        val savepoint = if (!connection.autoCommit) connection.setSavepoint() else null
        try {
            connection.prepareStatement("INSERT INTO venue_public_addresses (slug, venue_id) VALUES (?, ?)").use {
                it.setString(1, slug)
                it.setLong(2, venueId)
                it.executeUpdate()
            }
            return true
        } catch (error: SQLException) {
            if (savepoint != null) connection.rollback(savepoint)
            if (error.sqlState != "23505") throw error
            return connection.prepareStatement("SELECT venue_id FROM venue_public_addresses WHERE slug = ?").use {
                it.setString(1, slug)
                it.executeQuery().use { rows -> rows.next() && rows.getLong(1) == venueId }
            }
        } finally {
            if (savepoint != null) connection.releaseSavepoint(savepoint)
        }
    }

    fun assign(
        connection: Connection,
        venueId: Long,
        name: String,
        city: String?,
        address: String?,
    ): String {
        val base = normalize(name).ifEmpty { "venue" }
        val detail = normalize(listOfNotNull(city, address).joinToString(" "))
        val detailed = if (detail.isEmpty()) base else base.take(31).trimEnd('-') + "-" + detail.take(32).trimEnd('-')
        val candidates =
            sequence {
                yield(base)
                if (detailed != base) yield(detailed)
                var attempt = 0
                while (true) {
                    val suffix = "-$venueId" + if (attempt == 0) "" else "-$attempt"
                    yield(detailed.take(64 - suffix.length).trimEnd('-') + suffix)
                    attempt++
                }
            }
        val slug = candidates.first { reserve(connection, venueId, it) }
        connection.prepareStatement("UPDATE venues SET public_slug = ? WHERE id = ? AND public_slug IS NULL").use {
            it.setString(1, slug)
            it.setLong(2, venueId)
            check(it.executeUpdate() == 1)
        }
        return slug
    }
}
