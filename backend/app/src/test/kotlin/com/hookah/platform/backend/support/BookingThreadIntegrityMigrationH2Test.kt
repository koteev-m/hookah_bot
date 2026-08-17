package com.hookah.platform.backend.support

import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import kotlin.test.Test

class BookingThreadIntegrityMigrationH2Test {
    @Test
    fun `clean schema stays unchanged and uniqueness is enforced on H2`() {
        support().assertCleanSchemaWithoutDuplicatesIsUnchanged()
    }

    @Test
    fun `duplicates without reads merge losslessly on H2`() {
        support().assertDuplicatesWithoutReadsAreMerged()
    }

    @Test
    fun `identical complete reads merge losslessly on H2`() {
        support().assertIdenticalReadsAreMergedLosslessly()
    }

    @Test
    fun `partial read coverage fails before domain mutation on H2`() {
        support().assertPartialReadCoverageFailsBeforeDomainMutation()
    }

    @Test
    fun `different read timestamps fail before domain mutation on H2`() {
        support().assertDifferentReadTimestampsFailBeforeDomainMutation()
    }

    @Test
    fun `ownership mismatch fails before domain mutation on H2`() {
        support().assertOwnershipMismatchFailsBeforeDomainMutation()
    }

    @Test
    fun `null booking fails before domain mutation on H2`() {
        support().assertNullBookingFailsBeforeDomainMutation()
    }

    @Test
    fun `missing booking fails before domain mutation on H2`() {
        support().assertMissingBookingFailsBeforeDomainMutation()
    }

    @Test
    fun `conflicting duplicate statuses fail before domain mutation on H2`() {
        support().assertConflictingStatusesFailBeforeDomainMutation()
    }

    @Test
    fun `unknown reference fails before domain mutation on H2`() {
        support().assertUnknownReferenceFailsBeforeDomainMutation()
    }

    @Test
    fun `unknown explicit thread id column fails before domain mutation on H2`() {
        support().assertUnknownExplicitThreadIdColumnFailsBeforeDomainMutation()
    }

    @Test
    fun `unknown alternate thread reference column fails before domain mutation on H2`() {
        support().assertUnknownAlternateThreadReferenceColumnFailsBeforeDomainMutation()
    }

    @Test
    fun `unknown JSON reference family fails before domain mutation on H2`() {
        support().assertUnknownJsonReferenceFamilyFailsBeforeDomainMutation()
    }

    @Test
    fun `audit ticket id remaps to the survivor on H2`() {
        support().assertAuditTicketIdIsRemapped()
    }

    @Test
    fun `audit entity and payload references match on H2`() {
        support().assertAuditEntityAndPayloadReferencesMatch()
    }

    @Test
    fun `audit non-reference payload fields are preserved on H2`() {
        support().assertAuditNonReferencePayloadFieldsArePreserved()
    }

    @Test
    fun `audit ticket id remap ignores key order formatting and safe fields on H2`() {
        support().assertAuditTicketIdOrderFormattingAndSafeFieldsAreSemantic()
    }

    @Test
    fun `missing audit ticket id fails before domain mutation on H2`() {
        support().assertMissingAuditTicketIdFailsBeforeDomainMutation()
    }

    @Test
    fun `non-numeric audit ticket id fails before domain mutation on H2`() {
        support().assertNonNumericAuditTicketIdFailsBeforeDomainMutation()
    }

    @Test
    fun `out-of-range audit ticket id fails before domain mutation on H2`() {
        support().assertOutOfRangeAuditTicketIdFailsBeforeDomainMutation()
    }

    @Test
    fun `mismatched audit ticket id fails before domain mutation on H2`() {
        support().assertMismatchedAuditTicketIdFailsBeforeDomainMutation()
    }

    @Test
    fun `payload-only booking thread audit reference fails before domain mutation on H2`() {
        support().assertPayloadOnlyBookingReferenceFailsBeforeDomainMutation()
    }

    @Test
    fun `payload-only booking thread audit with null entity fails before domain mutation on H2`() {
        support().assertPayloadOnlyBookingReferenceWithNullEntityFailsBeforeDomainMutation()
    }

    @Test
    fun `escaped payload-only booking thread audit with null entity fails before domain mutation on H2`() {
        support().assertEscapedPayloadOnlyBookingReferenceWithNullEntityFailsBeforeDomainMutation()
    }

    @Test
    fun `unknown audit action and entity shape fails before domain mutation on H2`() {
        support().assertUnknownAuditActionAndEntityShapeFailsBeforeDomainMutation()
    }

    @Test
    fun `known audit thread alias fails before domain mutation on H2`() {
        support().assertKnownAuditAliasFailsBeforeDomainMutation()
    }

    @Test
    fun `escaped known audit thread alias fails before domain mutation on H2`() {
        support().assertEscapedKnownAuditAliasFailsBeforeDomainMutation()
    }

    @Test
    fun `nested conversation thread id fails before domain mutation on H2`() {
        support().assertNestedConversationThreadIdFailsBeforeDomainMutation()
    }

    @Test
    fun `escaped conversation thread id fails before domain mutation on H2`() {
        support().assertEscapedConversationThreadIdFailsBeforeDomainMutation()
    }

    @Test
    fun `nested thread id fails before domain mutation on H2`() {
        support().assertNestedThreadIdFailsBeforeDomainMutation()
    }

    @Test
    fun `array ticket ids fail before domain mutation on H2`() {
        support().assertArrayTicketIdsFailsBeforeDomainMutation()
    }

    @Test
    fun `numeric string unknown reference fails before domain mutation on H2`() {
        support().assertNumericStringUnknownReferenceFailsBeforeDomainMutation()
    }

    @Test
    fun `plain and escaped duplicate audit ticket ids fail before domain mutation on H2`() {
        support().assertMixedPlainAndEscapedAuditTicketIdsFailBeforeDomainMutation()
    }

    @Test
    fun `non-object audit payload fails before domain mutation on H2`() {
        support().assertNonObjectAuditPayloadFailsBeforeDomainMutation()
    }

    @Test
    fun `nested-only audit ticket id fails before domain mutation on H2`() {
        support().assertNestedAuditTicketIdFailsBeforeDomainMutation()
    }

    @Test
    fun `top-level and conflicting nested audit ticket ids fail before domain mutation on H2`() {
        support().assertTopLevelAndConflictingNestedAuditTicketIdsFailBeforeDomainMutation()
    }

    @Test
    fun `top-level audit ticket id with nested data is preserved on H2`() {
        support().assertTopLevelAuditTicketIdWithNestedDataIsPreserved()
    }

    @Test
    fun `conversation status is not treated as a reference on H2`() {
        support().assertConversationStatusIsNotAReferenceKey()
    }

    @Test
    fun `known durable JSON thread reference fails before domain mutation on H2`() {
        support().assertKnownDurableJsonThreadReferenceFailsBeforeDomainMutation()
    }

    @Test
    fun `escaped known durable JSON thread reference fails before domain mutation on H2`() {
        support().assertEscapedKnownDurableJsonThreadReferenceFailsBeforeDomainMutation()
    }

    @Test
    fun `unrelated audit with coincident id is unchanged on H2`() {
        support().assertUnrelatedAuditWithCoincidentIdIsUnchanged()
    }

    @Test
    fun `multiple audit rows preserve cardinality order and timestamps on H2`() {
        support().assertMultipleAuditRowsPreserveCardinalityOrderAndTimestamps()
    }

    private fun support(): MigrationAssertions =
        MigrationAssertions(
            dataSource = dataSource(),
            location = "classpath:db/migration/h2",
            previousVersion = "124",
            expectedVersion = "125",
            usesGeneratedBookingKey = true,
        )

    private fun dataSource(): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(
                "jdbc:h2:mem:booking-thread-integrity-${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            )
            user = "sa"
            password = ""
        }
}
