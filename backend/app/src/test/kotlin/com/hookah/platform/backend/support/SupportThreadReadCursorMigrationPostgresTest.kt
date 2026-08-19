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

    private fun assertions(): SupportThreadReadCursorMigrationAssertions {
        val database = PostgresTestEnv.createDatabase()
        val dataSource = PostgresTestEnv.createDataSource(database, migrate = false)
        return SupportThreadReadCursorMigrationAssertions(
            dataSource = dataSource,
            location = "classpath:db/migration/postgresql",
            previousVersion = "125",
            expectedVersion = "126",
            ddlFailureContract = MigrationDdlFailureContract.TRANSACTIONAL,
            close = dataSource::close,
        )
    }
}
