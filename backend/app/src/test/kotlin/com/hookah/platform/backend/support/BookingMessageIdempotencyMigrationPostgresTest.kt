package com.hookah.platform.backend.support

import com.hookah.platform.backend.test.PostgresTestEnv
import kotlin.test.Test

class BookingMessageIdempotencyMigrationPostgresTest {
    @Test
    fun `legacy and Telegram rows remain nullable on PostgreSQL`() {
        assertions().assertLegacyRowsRemainNullable()
    }

    @Test
    fun `Mini App idempotency scope is unique on PostgreSQL`() {
        assertions().assertMiniAppScopeIsUnique()
    }

    @Test
    fun `client message id scope is constrained on PostgreSQL`() {
        assertions().assertClientMessageIdScopeIsConstrained()
    }

    @Test
    fun `client message id is bounded on PostgreSQL`() {
        assertions().assertClientMessageIdIsBounded()
    }

    @Test
    fun `message idempotency metadata and migration head are exact on PostgreSQL`() {
        assertions().assertExactMetadataAndMigrationHead()
    }

    private fun assertions(): BookingMessageIdempotencyMigrationAssertions {
        val database = PostgresTestEnv.createDatabase()
        val dataSource = PostgresTestEnv.createDataSource(database, migrate = false)
        return BookingMessageIdempotencyMigrationAssertions(
            dataSource = dataSource,
            location = "classpath:db/migration/postgresql",
            previousVersion = "124",
            expectedVersion = "125",
            close = dataSource::close,
        )
    }
}
