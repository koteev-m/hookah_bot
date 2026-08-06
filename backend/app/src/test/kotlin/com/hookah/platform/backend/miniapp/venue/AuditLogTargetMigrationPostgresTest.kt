package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.test.PostgresTestEnv
import kotlin.test.Test

class AuditLogTargetMigrationPostgresTest {
    @Test
    fun `migration adds exact target column foreign key and index and preserves rows on PostgreSQL`() {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database, migrate = false).use { dataSource ->
            AuditLogTargetMigrationTestSupport.assertMigrationSchemaAndExistingRows(
                dataSource = dataSource,
                location = "classpath:db/migration/postgresql",
                previousVersion = "121",
                expectedVersion = "122",
            )
        }
    }

    @Test
    fun `legacy and targeted writers preserve target-only audit evidence on PostgreSQL`() {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database, migrate = false).use { dataSource ->
            AuditLogTargetMigrationTestSupport.assertLegacyAndTargetedWriterCompatibility(
                dataSource = dataSource,
                location = "classpath:db/migration/postgresql",
                expectedVersion = "122",
            )
        }
    }
}
