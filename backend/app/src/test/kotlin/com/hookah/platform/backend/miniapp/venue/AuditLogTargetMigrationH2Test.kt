package com.hookah.platform.backend.miniapp.venue

import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import kotlin.test.Test

class AuditLogTargetMigrationH2Test {
    @Test
    fun `migration adds exact target column foreign key and index and preserves rows on H2`() {
        AuditLogTargetMigrationTestSupport.assertMigrationSchemaAndExistingRows(
            dataSource = dataSource(),
            location = "classpath:db/migration/h2",
            previousVersion = "122",
            expectedVersion = "123",
        )
    }

    @Test
    fun `legacy and targeted writers preserve target-only audit evidence on H2`() {
        AuditLogTargetMigrationTestSupport.assertLegacyAndTargetedWriterCompatibility(
            dataSource = dataSource(),
            location = "classpath:db/migration/h2",
            expectedVersion = "123",
        )
    }

    private fun dataSource(): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(
                "jdbc:h2:mem:audit-target-${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            )
            user = "sa"
            password = ""
        }
}
