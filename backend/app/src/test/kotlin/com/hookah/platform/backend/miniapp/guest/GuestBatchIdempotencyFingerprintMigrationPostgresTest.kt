package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.test.PostgresTestEnv
import kotlin.test.Test

class GuestBatchIdempotencyFingerprintMigrationPostgresTest {
    @Test
    fun `migration adds nullable fingerprint and preserves idempotency rows on PostgreSQL`() {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database, migrate = false).use { dataSource ->
            GuestBatchIdempotencyFingerprintMigrationTestSupport.assertMigrationSchemaAndExistingRows(
                dataSource = dataSource,
                location = "classpath:db/migration/postgresql",
                previousVersion = "122",
                expectedVersion = "123",
            )
        }
    }

    @Test
    fun `legacy and fingerprint writers remain compatible on PostgreSQL`() {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database, migrate = false).use { dataSource ->
            GuestBatchIdempotencyFingerprintMigrationTestSupport.assertLegacyAndFingerprintWriterCompatibility(
                dataSource = dataSource,
                location = "classpath:db/migration/postgresql",
                expectedVersion = "123",
            )
        }
    }
}
