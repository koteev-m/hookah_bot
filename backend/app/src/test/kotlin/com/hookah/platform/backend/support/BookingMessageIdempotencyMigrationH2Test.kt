package com.hookah.platform.backend.support

import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import kotlin.test.Test

class BookingMessageIdempotencyMigrationH2Test {
    @Test
    fun `legacy and Telegram rows remain nullable on H2`() {
        assertions().assertLegacyRowsRemainNullable()
    }

    @Test
    fun `Mini App idempotency scope is unique on H2`() {
        assertions().assertMiniAppScopeIsUnique()
    }

    @Test
    fun `client message id scope is constrained on H2`() {
        assertions().assertClientMessageIdScopeIsConstrained()
    }

    @Test
    fun `client message id is bounded on H2`() {
        assertions().assertClientMessageIdIsBounded()
    }

    @Test
    fun `message idempotency metadata and migration version are exact on H2`() {
        assertions().assertExactMetadataAndMigrationVersion()
    }

    private fun assertions(): BookingMessageIdempotencyMigrationAssertions =
        BookingMessageIdempotencyMigrationAssertions(
            dataSource =
                JdbcDataSource().apply {
                    setURL(
                        "jdbc:h2:mem:booking-message-idempotency-${UUID.randomUUID()};MODE=PostgreSQL;" +
                            "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                    )
                    user = "sa"
                    password = ""
                },
            location = "classpath:db/migration/h2",
            previousVersion = "125",
            expectedVersion = "126",
        )
}
