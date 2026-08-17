package com.hookah.platform.backend.support

import com.hookah.platform.backend.test.PostgresTestEnv
import kotlin.test.Test

class BookingThreadIntegrityMigrationPostgresTest {
    @Test
    fun `clean schema stays unchanged and uniqueness is enforced on PostgreSQL`() {
        support().assertCleanSchemaWithoutDuplicatesIsUnchanged()
    }

    @Test
    fun `duplicates without reads merge losslessly on PostgreSQL`() {
        support().assertDuplicatesWithoutReadsAreMerged()
    }

    @Test
    fun `identical complete reads merge losslessly on PostgreSQL`() {
        support().assertIdenticalReadsAreMergedLosslessly()
    }

    @Test
    fun `partial read coverage fails before domain mutation on PostgreSQL`() {
        support().assertPartialReadCoverageFailsBeforeDomainMutation()
    }

    @Test
    fun `different read timestamps fail before domain mutation on PostgreSQL`() {
        support().assertDifferentReadTimestampsFailBeforeDomainMutation()
    }

    @Test
    fun `ownership mismatch fails before domain mutation on PostgreSQL`() {
        support().assertOwnershipMismatchFailsBeforeDomainMutation()
    }

    @Test
    fun `null booking fails before domain mutation on PostgreSQL`() {
        support().assertNullBookingFailsBeforeDomainMutation()
    }

    @Test
    fun `missing booking fails before domain mutation on PostgreSQL`() {
        support().assertMissingBookingFailsBeforeDomainMutation()
    }

    @Test
    fun `conflicting duplicate statuses fail before domain mutation on PostgreSQL`() {
        support().assertConflictingStatusesFailBeforeDomainMutation()
    }

    @Test
    fun `unknown reference fails before domain mutation on PostgreSQL`() {
        support().assertUnknownReferenceFailsBeforeDomainMutation()
    }

    @Test
    fun `unknown explicit thread id column fails before domain mutation on PostgreSQL`() {
        support().assertUnknownExplicitThreadIdColumnFailsBeforeDomainMutation()
    }

    @Test
    fun `unknown alternate thread reference column fails before domain mutation on PostgreSQL`() {
        support().assertUnknownAlternateThreadReferenceColumnFailsBeforeDomainMutation()
    }

    @Test
    fun `unknown JSON reference family fails before domain mutation on PostgreSQL`() {
        support().assertUnknownJsonReferenceFamilyFailsBeforeDomainMutation()
    }

    @Test
    fun `audit ticket id remaps to the survivor on PostgreSQL`() {
        support().assertAuditTicketIdIsRemapped()
    }

    @Test
    fun `audit entity and payload references match on PostgreSQL`() {
        support().assertAuditEntityAndPayloadReferencesMatch()
    }

    @Test
    fun `audit non-reference payload fields are preserved on PostgreSQL`() {
        support().assertAuditNonReferencePayloadFieldsArePreserved()
    }

    @Test
    fun `audit ticket id remap ignores key order formatting and safe fields on PostgreSQL`() {
        support().assertAuditTicketIdOrderFormattingAndSafeFieldsAreSemantic()
    }

    @Test
    fun `missing audit ticket id fails before domain mutation on PostgreSQL`() {
        support().assertMissingAuditTicketIdFailsBeforeDomainMutation()
    }

    @Test
    fun `non-numeric audit ticket id fails before domain mutation on PostgreSQL`() {
        support().assertNonNumericAuditTicketIdFailsBeforeDomainMutation()
    }

    @Test
    fun `out-of-range audit ticket id fails before domain mutation on PostgreSQL`() {
        support().assertOutOfRangeAuditTicketIdFailsBeforeDomainMutation()
    }

    @Test
    fun `mismatched audit ticket id fails before domain mutation on PostgreSQL`() {
        support().assertMismatchedAuditTicketIdFailsBeforeDomainMutation()
    }

    @Test
    fun `payload-only booking thread audit reference fails before domain mutation on PostgreSQL`() {
        support().assertPayloadOnlyBookingReferenceFailsBeforeDomainMutation()
    }

    @Test
    fun `payload-only booking thread audit with null entity fails before domain mutation on PostgreSQL`() {
        support().assertPayloadOnlyBookingReferenceWithNullEntityFailsBeforeDomainMutation()
    }

    @Test
    fun `escaped payload-only booking thread audit with null entity fails before domain mutation on PostgreSQL`() {
        support().assertEscapedPayloadOnlyBookingReferenceWithNullEntityFailsBeforeDomainMutation()
    }

    @Test
    fun `unknown audit action and entity shape fails before domain mutation on PostgreSQL`() {
        support().assertUnknownAuditActionAndEntityShapeFailsBeforeDomainMutation()
    }

    @Test
    fun `known audit thread alias fails before domain mutation on PostgreSQL`() {
        support().assertKnownAuditAliasFailsBeforeDomainMutation()
    }

    @Test
    fun `escaped known audit thread alias fails before domain mutation on PostgreSQL`() {
        support().assertEscapedKnownAuditAliasFailsBeforeDomainMutation()
    }

    @Test
    fun `nested conversation thread id fails before domain mutation on PostgreSQL`() {
        support().assertNestedConversationThreadIdFailsBeforeDomainMutation()
    }

    @Test
    fun `escaped conversation thread id fails before domain mutation on PostgreSQL`() {
        support().assertEscapedConversationThreadIdFailsBeforeDomainMutation()
    }

    @Test
    fun `nested thread id fails before domain mutation on PostgreSQL`() {
        support().assertNestedThreadIdFailsBeforeDomainMutation()
    }

    @Test
    fun `array ticket ids fail before domain mutation on PostgreSQL`() {
        support().assertArrayTicketIdsFailsBeforeDomainMutation()
    }

    @Test
    fun `numeric string unknown reference fails before domain mutation on PostgreSQL`() {
        support().assertNumericStringUnknownReferenceFailsBeforeDomainMutation()
    }

    @Test
    fun `plain and escaped duplicate audit ticket ids fail before domain mutation on PostgreSQL`() {
        support().assertMixedPlainAndEscapedAuditTicketIdsFailBeforeDomainMutation()
    }

    @Test
    fun `non-object audit payload fails before domain mutation on PostgreSQL`() {
        support().assertNonObjectAuditPayloadFailsBeforeDomainMutation()
    }

    @Test
    fun `nested-only audit ticket id fails before domain mutation on PostgreSQL`() {
        support().assertNestedAuditTicketIdFailsBeforeDomainMutation()
    }

    @Test
    fun `top-level and conflicting nested audit ticket ids fail before domain mutation on PostgreSQL`() {
        support().assertTopLevelAndConflictingNestedAuditTicketIdsFailBeforeDomainMutation()
    }

    @Test
    fun `top-level audit ticket id with nested data is preserved on PostgreSQL`() {
        support().assertTopLevelAuditTicketIdWithNestedDataIsPreserved()
    }

    @Test
    fun `conversation status is not treated as a reference on PostgreSQL`() {
        support().assertConversationStatusIsNotAReferenceKey()
    }

    @Test
    fun `known durable JSON thread reference fails before domain mutation on PostgreSQL`() {
        support().assertKnownDurableJsonThreadReferenceFailsBeforeDomainMutation()
    }

    @Test
    fun `escaped known durable JSON thread reference fails before domain mutation on PostgreSQL`() {
        support().assertEscapedKnownDurableJsonThreadReferenceFailsBeforeDomainMutation()
    }

    @Test
    fun `unrelated audit with coincident id is unchanged on PostgreSQL`() {
        support().assertUnrelatedAuditWithCoincidentIdIsUnchanged()
    }

    @Test
    fun `multiple audit rows preserve cardinality order and timestamps on PostgreSQL`() {
        support().assertMultipleAuditRowsPreserveCardinalityOrderAndTimestamps()
    }

    private fun support(): MigrationAssertions {
        val database = PostgresTestEnv.createDatabase()
        val dataSource = PostgresTestEnv.createDataSource(database, migrate = false)
        return MigrationAssertions(
            dataSource = dataSource,
            location = "classpath:db/migration/postgresql",
            previousVersion = "123",
            expectedVersion = "124",
            usesGeneratedBookingKey = false,
            close = dataSource::close,
        )
    }
}

internal class MigrationAssertions(
    private val dataSource: javax.sql.DataSource,
    private val location: String,
    private val previousVersion: String,
    private val expectedVersion: String,
    private val usesGeneratedBookingKey: Boolean,
    private val close: () -> Unit = {},
) {
    fun assertCleanSchemaWithoutDuplicatesIsUnchanged() =
        run {
            try {
                BookingThreadIntegrityMigrationTestSupport.assertCleanSchemaWithoutDuplicatesIsUnchanged(
                    dataSource,
                    location,
                    previousVersion,
                    expectedVersion,
                    usesGeneratedBookingKey,
                )
            } finally {
                close()
            }
        }

    fun assertDuplicatesWithoutReadsAreMerged() =
        run {
            try {
                BookingThreadIntegrityMigrationTestSupport.assertDuplicatesWithoutReadsAreMerged(
                    dataSource,
                    location,
                    previousVersion,
                    expectedVersion,
                    usesGeneratedBookingKey,
                )
            } finally {
                close()
            }
        }

    fun assertIdenticalReadsAreMergedLosslessly() =
        run {
            try {
                BookingThreadIntegrityMigrationTestSupport.assertIdenticalReadsAreMergedLosslessly(
                    dataSource,
                    location,
                    previousVersion,
                    expectedVersion,
                    usesGeneratedBookingKey,
                )
            } finally {
                close()
            }
        }

    fun assertPartialReadCoverageFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertPartialReadCoverageFailsBeforeDomainMutation)

    fun assertDifferentReadTimestampsFailBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertDifferentReadTimestampsFailBeforeDomainMutation)

    fun assertOwnershipMismatchFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertOwnershipMismatchFailsBeforeDomainMutation)

    fun assertNullBookingFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertNullBookingFailsBeforeDomainMutation)

    fun assertMissingBookingFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertMissingBookingFailsBeforeDomainMutation)

    fun assertConflictingStatusesFailBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertConflictingStatusesFailBeforeDomainMutation)

    fun assertUnknownReferenceFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertUnknownReferenceFailsBeforeDomainMutation)

    fun assertUnknownExplicitThreadIdColumnFailsBeforeDomainMutation() =
        unsafe(
            BookingThreadIntegrityMigrationTestSupport::assertUnknownExplicitThreadIdColumnFailsBeforeDomainMutation,
        )

    fun assertUnknownAlternateThreadReferenceColumnFailsBeforeDomainMutation() =
        unsafe { dataSource, location, previousVersion, expectedVersion ->
            BookingThreadIntegrityMigrationTestSupport
                .assertUnknownAlternateThreadReferenceColumnFailsBeforeDomainMutation(
                    dataSource,
                    location,
                    previousVersion,
                    expectedVersion,
                )
        }

    fun assertUnknownJsonReferenceFamilyFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertUnknownJsonReferenceFamilyFailsBeforeDomainMutation)

    fun assertAuditTicketIdIsRemapped() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertAuditTicketIdIsRemapped)

    fun assertAuditEntityAndPayloadReferencesMatch() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertAuditEntityAndPayloadReferencesMatch)

    fun assertAuditNonReferencePayloadFieldsArePreserved() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertAuditNonReferencePayloadFieldsArePreserved)

    fun assertAuditTicketIdOrderFormattingAndSafeFieldsAreSemantic() =
        unsafe(
            BookingThreadIntegrityMigrationTestSupport::assertAuditTicketIdOrderFormattingAndSafeFieldsAreSemantic,
        )

    fun assertMissingAuditTicketIdFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertMissingAuditTicketIdFailsBeforeDomainMutation)

    fun assertNonNumericAuditTicketIdFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertNonNumericAuditTicketIdFailsBeforeDomainMutation)

    fun assertOutOfRangeAuditTicketIdFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertOutOfRangeAuditTicketIdFailsBeforeDomainMutation)

    fun assertMismatchedAuditTicketIdFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertMismatchedAuditTicketIdFailsBeforeDomainMutation)

    fun assertPayloadOnlyBookingReferenceFailsBeforeDomainMutation() =
        unsafe(
            BookingThreadIntegrityMigrationTestSupport::assertPayloadOnlyBookingReferenceFailsBeforeDomainMutation,
        )

    fun assertPayloadOnlyBookingReferenceWithNullEntityFailsBeforeDomainMutation() =
        unsafe { dataSource, location, previousVersion, expectedVersion ->
            BookingThreadIntegrityMigrationTestSupport
                .assertPayloadOnlyBookingReferenceWithNullEntityFailsBeforeDomainMutation(
                    dataSource,
                    location,
                    previousVersion,
                    expectedVersion,
                )
        }

    fun assertEscapedPayloadOnlyBookingReferenceWithNullEntityFailsBeforeDomainMutation() =
        unsafe { dataSource, location, previousVersion, expectedVersion ->
            BookingThreadIntegrityMigrationTestSupport
                .assertEscapedPayloadOnlyBookingReferenceWithNullEntityFailsBeforeDomainMutation(
                    dataSource,
                    location,
                    previousVersion,
                    expectedVersion,
                )
        }

    fun assertUnknownAuditActionAndEntityShapeFailsBeforeDomainMutation() =
        unsafe(
            BookingThreadIntegrityMigrationTestSupport::assertUnknownAuditActionAndEntityShapeFailsBeforeDomainMutation,
        )

    fun assertKnownAuditAliasFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertKnownAuditAliasFailsBeforeDomainMutation)

    fun assertEscapedKnownAuditAliasFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertEscapedKnownAuditAliasFailsBeforeDomainMutation)

    fun assertNestedConversationThreadIdFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertNestedConversationThreadIdFailsBeforeDomainMutation)

    fun assertEscapedConversationThreadIdFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertEscapedConversationThreadIdFailsBeforeDomainMutation)

    fun assertNestedThreadIdFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertNestedThreadIdFailsBeforeDomainMutation)

    fun assertArrayTicketIdsFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertArrayTicketIdsFailsBeforeDomainMutation)

    fun assertNumericStringUnknownReferenceFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertNumericStringUnknownReferenceFailsBeforeDomainMutation)

    fun assertMixedPlainAndEscapedAuditTicketIdsFailBeforeDomainMutation() =
        unsafe { dataSource, location, previousVersion, expectedVersion ->
            BookingThreadIntegrityMigrationTestSupport
                .assertMixedPlainAndEscapedAuditTicketIdsFailBeforeDomainMutation(
                    dataSource,
                    location,
                    previousVersion,
                    expectedVersion,
                )
        }

    fun assertNonObjectAuditPayloadFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertNonObjectAuditPayloadFailsBeforeDomainMutation)

    fun assertNestedAuditTicketIdFailsBeforeDomainMutation() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertNestedAuditTicketIdFailsBeforeDomainMutation)

    fun assertTopLevelAndConflictingNestedAuditTicketIdsFailBeforeDomainMutation() =
        unsafe { dataSource, location, previousVersion, expectedVersion ->
            BookingThreadIntegrityMigrationTestSupport
                .assertTopLevelAndConflictingNestedAuditTicketIdsFailBeforeDomainMutation(
                    dataSource,
                    location,
                    previousVersion,
                    expectedVersion,
                )
        }

    fun assertTopLevelAuditTicketIdWithNestedDataIsPreserved() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertTopLevelAuditTicketIdWithNestedDataIsPreserved)

    fun assertConversationStatusIsNotAReferenceKey() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertConversationStatusIsNotAReferenceKey)

    fun assertKnownDurableJsonThreadReferenceFailsBeforeDomainMutation() =
        unsafe(
            BookingThreadIntegrityMigrationTestSupport::assertKnownDurableJsonThreadReferenceFailsBeforeDomainMutation,
        )

    fun assertEscapedKnownDurableJsonThreadReferenceFailsBeforeDomainMutation() =
        unsafe { dataSource, location, previousVersion, expectedVersion ->
            BookingThreadIntegrityMigrationTestSupport
                .assertEscapedKnownDurableJsonThreadReferenceFailsBeforeDomainMutation(
                    dataSource,
                    location,
                    previousVersion,
                    expectedVersion,
                )
        }

    fun assertUnrelatedAuditWithCoincidentIdIsUnchanged() =
        unsafe(BookingThreadIntegrityMigrationTestSupport::assertUnrelatedAuditWithCoincidentIdIsUnchanged)

    fun assertMultipleAuditRowsPreserveCardinalityOrderAndTimestamps() =
        unsafe(
            BookingThreadIntegrityMigrationTestSupport::assertMultipleAuditRowsPreserveCardinalityOrderAndTimestamps,
        )

    private fun unsafe(assertion: (javax.sql.DataSource, String, String, String) -> Unit) {
        try {
            assertion(dataSource, location, previousVersion, expectedVersion)
        } finally {
            close()
        }
    }
}
