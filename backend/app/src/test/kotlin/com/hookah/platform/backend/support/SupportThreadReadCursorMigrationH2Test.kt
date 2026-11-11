package com.hookah.platform.backend.support

import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import kotlin.test.Test

class SupportThreadReadCursorMigrationH2Test {
    @Test
    fun `legacy marker remains unbackfilled on H2`() {
        assertions().assertLegacyMarkerRemainsUnbackfilled()
    }

    @Test
    fun `cursor metadata index and migration head are exact on H2`() {
        assertions().assertExactMetadataIndexAndHead()
    }

    @Test
    fun `incompatible cursor column fails closed on H2`() {
        assertions().assertIncompatibleCursorColumnFailsClosed()
    }

    @Test
    fun `conflicting unread index follows H2 failure contract`() {
        assertions().assertConflictingUnreadIndexFollowsFailureContract()
    }

    @Test
    fun `reviewed following migrations reach current head without changing cursor contract`() {
        assertions().assertForwardMigrationReachesCurrentHead()
    }

    private fun assertions(): SupportThreadReadCursorMigrationAssertions =
        SupportThreadReadCursorMigrationAssertions(
            dataSource =
                JdbcDataSource().apply {
                    setURL(
                        "jdbc:h2:mem:support-thread-read-cursor-${UUID.randomUUID()};MODE=PostgreSQL;" +
                            "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                    )
                    user = "sa"
                    password = ""
                },
            location = "classpath:db/migration/h2",
            previousVersion = "126",
            expectedVersion = "127",
            expectedFollowingMigrations =
                mapOf(
                    "128" to "V128__staff_profile_photos.sql",
                    "129" to "V129__venue_info_media_objects.sql",
                    "130" to "V130__venue_public_addresses.sql",
                    "131" to "V131__backfill_venue_public_addresses.sql",
                    "132" to "V132__venue_accounting_shifts.sql",
                    "133" to "V133__halls_seating_table_booking.sql",
                    "134" to "V134__venue_dashboard_monitor_indexes.sql",
                    "135" to "V135__menu_featured.sql",
                    "136" to "V136__menu_featured_operations.sql",
                    "137" to "V137__loyalty_paid_lifecycle.sql",
                    "138" to "V138__loyalty_unit_corrections.sql",
                ),
            ddlFailureContract = MigrationDdlFailureContract.NON_TRANSACTIONAL,
        )
}
