package com.hookah.platform.backend.venue.links

import java.sql.Connection

fun backfillVenuePublicAddresses(connection: Connection) {
    data class Venue(val id: Long, val name: String, val city: String?, val address: String?)
    val venues =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT id, name, city, address FROM venues WHERE public_slug IS NULL ORDER BY id",
            ).use {
                    rows ->
                buildList {
                    while (rows.next()) {
                        add(Venue(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4)))
                    }
                }
            }
        }
    venues.forEach { VenueSlugs.assign(connection, it.id, it.name, it.city, it.address, legacyBackfill = true) }
}
