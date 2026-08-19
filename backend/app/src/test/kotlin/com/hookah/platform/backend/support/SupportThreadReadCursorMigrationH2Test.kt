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
            ddlFailureContract = MigrationDdlFailureContract.NON_TRANSACTIONAL,
        )
}
