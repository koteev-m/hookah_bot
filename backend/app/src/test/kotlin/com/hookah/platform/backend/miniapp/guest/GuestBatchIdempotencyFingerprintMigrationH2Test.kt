package com.hookah.platform.backend.miniapp.guest

import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import kotlin.test.Test

class GuestBatchIdempotencyFingerprintMigrationH2Test {
    @Test
    fun `migration adds nullable fingerprint and preserves idempotency rows on H2`() {
        GuestBatchIdempotencyFingerprintMigrationTestSupport.assertMigrationSchemaAndExistingRows(
            dataSource = dataSource(),
            location = "classpath:db/migration/h2",
            previousVersion = "123",
            expectedVersion = "124",
        )
    }

    @Test
    fun `legacy and fingerprint writers remain compatible on H2`() {
        GuestBatchIdempotencyFingerprintMigrationTestSupport.assertLegacyAndFingerprintWriterCompatibility(
            dataSource = dataSource(),
            location = "classpath:db/migration/h2",
            expectedVersion = "124",
        )
    }

    private fun dataSource(): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(
                "jdbc:h2:mem:guest-idempotency-fingerprint-${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            )
            user = "sa"
            password = ""
        }
}
