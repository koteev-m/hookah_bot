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
    fun `reviewed photo migration reaches current head without changing cursor contract`() {
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
                ),
            ddlFailureContract = MigrationDdlFailureContract.TRANSACTIONAL,
            close = dataSource::close,
        )
    }
}
