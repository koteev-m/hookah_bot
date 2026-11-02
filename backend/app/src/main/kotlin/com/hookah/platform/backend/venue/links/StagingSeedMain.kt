package com.hookah.platform.backend.venue.links

import java.sql.Connection
import java.sql.DriverManager

object StagingSeedMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val parameters =
            StagingSeedParameters(
                ownerId = requireNotNull(System.getenv("SEED_OWNER_ID")).toLong(),
                managerId = System.getenv("SEED_MANAGER_ID")?.takeIf(String::isNotBlank)?.toLong(),
                staffId = System.getenv("SEED_STAFF_ID")?.takeIf(String::isNotBlank)?.toLong(),
                name = requireNotNull(System.getenv("SEED_VENUE_NAME")),
                city = requireNotNull(System.getenv("SEED_VENUE_CITY")),
                address = requireNotNull(System.getenv("SEED_VENUE_ADDRESS")),
            )
        val sql = System.`in`.bufferedReader().readText()
        DriverManager.getConnection(
            requireNotNull(System.getenv("DB_JDBC_URL")),
            requireNotNull(System.getenv("DB_USER")),
            requireNotNull(System.getenv("DB_PASSWORD")),
        ).use { connection ->
            val result = seedStagingVenue(connection, parameters, sql)
            println("Seeded published venue ${result.venueId}; public slug: ${result.slug}")
        }
    }
}

internal data class StagingSeedParameters(
    val ownerId: Long,
    val managerId: Long?,
    val staffId: Long?,
    val name: String,
    val city: String,
    val address: String,
)

internal data class StagingSeedResult(val venueId: Long, val slug: String)

internal fun seedStagingVenue(
    connection: Connection,
    parameters: StagingSeedParameters,
    sql: String,
): StagingSeedResult {
    check(connection.autoCommit)
    connection.autoCommit = false
    try {
        connection.prepareStatement(
            """
            CREATE TEMP TABLE staging_seed_params ON COMMIT DROP AS
            SELECT CAST(? AS BIGINT) AS owner_id, CAST(? AS BIGINT) AS manager_id,
                   CAST(? AS BIGINT) AS staff_id, CAST(? AS TEXT) AS venue_name,
                   CAST(? AS TEXT) AS venue_city, CAST(? AS TEXT) AS venue_address
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, parameters.ownerId)
            statement.setObject(2, parameters.managerId, java.sql.Types.BIGINT)
            statement.setObject(3, parameters.staffId, java.sql.Types.BIGINT)
            statement.setString(4, parameters.name)
            statement.setString(5, parameters.city)
            statement.setString(6, parameters.address)
            statement.executeUpdate()
        }
        connection.createStatement().use { it.execute(sql) }
        val result =
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT v.id, v.name, v.city, v.address, v.public_slug
                    FROM venues v JOIN staging_seed_venue sv ON sv.venue_id = v.id
                    FOR UPDATE OF v
                    """.trimIndent(),
                ).use { rows ->
                    check(rows.next())
                    val id = rows.getLong("id")
                    val slug =
                        rows.getString("public_slug")
                            ?: VenueSlugs.assign(
                                connection,
                                id,
                                rows.getString("name"),
                                rows.getString("city"),
                                rows.getString("address"),
                            )
                    StagingSeedResult(id, slug)
                }
            }
        connection.commit()
        return result
    } catch (error: Exception) {
        connection.rollback()
        throw error
    } finally {
        connection.autoCommit = true
    }
}
