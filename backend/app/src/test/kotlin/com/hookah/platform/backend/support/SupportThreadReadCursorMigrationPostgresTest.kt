package com.hookah.platform.backend.support

import com.hookah.platform.backend.test.PostgresTestEnv
import kotlin.test.Test

class SupportThreadReadCursorMigrationPostgresTest {
    @Test
    fun `legacy marker remains unbackfilled on PostgreSQL`() {
        assertions().assertLegacyMarkerRemainsUnbackfilled()
    }

    @Test
    fun `cursor metadata index and migration head are exact on PostgreSQL`() {
        assertions().assertExactMetadataIndexAndHead()
    }

    @Test
    fun `incompatible cursor column fails closed on PostgreSQL`() {
        assertions().assertIncompatibleCursorColumnFailsClosed()
    }

    @Test
    fun `conflicting unread index follows PostgreSQL rollback contract`() {
        assertions().assertConflictingUnreadIndexFollowsFailureContract()
    }

    @Test
    fun `reviewed following migrations reach current head without changing cursor contract`() {
        assertions().assertForwardMigrationReachesCurrentHead()
    }

    private fun assertions(): SupportThreadReadCursorMigrationAssertions {
        val database = PostgresTestEnv.createDatabase()
        val dataSource = PostgresTestEnv.createDataSource(database, migrate = false)
        return SupportThreadReadCursorMigrationAssertions(
            dataSource = dataSource,
            location = "classpath:db/migration/postgresql",
            previousVersion = "125",
            expectedVersion = "126",
            expectedFollowingMigrations =
                mapOf(
                    "127" to "V127__staff_profile_photos.sql",
                    "128" to "V128__venue_info_media_objects.sql",
                    "129" to "V129__venue_public_addresses.sql",
                    "130" to "V130__backfill_venue_public_addresses.sql",
                    "131" to "V131__venue_accounting_shifts.sql",
                    "132" to "V132__halls_seating_table_booking.sql",
                ),
            ddlFailureContract = MigrationDdlFailureContract.TRANSACTIONAL,
            close = dataSource::close,
        )
    }
}
