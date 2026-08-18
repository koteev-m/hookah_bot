package com.hookah.platform.backend.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.Date
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal object BookingThreadIntegrityMigrationTestSupport {
    fun assertCleanSchemaWithoutDuplicatesIsUnchanged(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
        usesGeneratedBookingKey: Boolean,
    ) {
        migrateToPrevious(dataSource, location, previousVersion)
        val fixture = dataSource.connection.use(::seedCleanSchema)
        dataSource.connection.use(::assertKnownReferenceInventory)

        migrateToExpected(dataSource, location, expectedVersion)

        dataSource.connection.use { connection ->
            assertEquals(fixture.beforeMigration, loadDomainSnapshot(connection))
            assertKnownReferenceInventory(connection)
            assertUniqueInvariant(connection, fixture, usesGeneratedBookingKey)
        }
    }

    fun assertDuplicatesWithoutReadsAreMerged(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
        usesGeneratedBookingKey: Boolean,
    ) = assertSafeDuplicateMerge(
        dataSource = dataSource,
        location = location,
        previousVersion = previousVersion,
        expectedVersion = expectedVersion,
        usesGeneratedBookingKey = usesGeneratedBookingKey,
        readFixture = ReadFixture.NONE,
    )

    fun assertIdenticalReadsAreMergedLosslessly(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
        usesGeneratedBookingKey: Boolean,
    ) = assertSafeDuplicateMerge(
        dataSource = dataSource,
        location = location,
        previousVersion = previousVersion,
        expectedVersion = expectedVersion,
        usesGeneratedBookingKey = usesGeneratedBookingKey,
        readFixture = ReadFixture.IDENTICAL,
    )

    fun assertPhysicalReferenceInventoryFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
        configureReferenceShape: (Connection, ReferenceGuardFixture) -> Unit,
        loadReferenceRows: () -> List<String> = { emptyList() },
    ) {
        migrateToPrevious(dataSource, location, previousVersion)
        dataSource.connection.use { connection ->
            val fixture = seedDuplicateGroup(connection, ReadFixture.NONE)
            configureReferenceShape(
                connection,
                ReferenceGuardFixture(duplicateThreadId = fixture.duplicateTwoId),
            )
        }

        val beforeSnapshot =
            dataSource.connection.use { connection ->
                loadReferenceGuardSnapshot(connection, loadReferenceRows())
            }
        val failure =
            assertFailsWith<FlywayException> {
                flyway(dataSource, location, expectedVersion).migrate()
            }
        assertTrue(
            failure.stackTraceToString().contains("Unexpected support thread reference inventory"),
            failure.stackTraceToString(),
        )

        val afterSnapshot =
            dataSource.connection.use { connection ->
                loadReferenceGuardSnapshot(connection, loadReferenceRows())
            }
        assertEquals(beforeSnapshot, afterSnapshot)
        assertEquals(previousVersion, flyway(dataSource, location, expectedVersion).info().current().version.version)
        dataSource.connection.use { connection ->
            assertFalse(indexExists(connection, UNIQUE_INDEX))
        }
    }

    fun assertPartialReadCoverageFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnsafeFixtureFailsBeforeDomainMutation(
        dataSource = dataSource,
        location = location,
        previousVersion = previousVersion,
        expectedVersion = expectedVersion,
    ) { connection -> seedDuplicateGroup(connection, ReadFixture.PARTIAL) }

    fun assertDifferentReadTimestampsFailBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnsafeFixtureFailsBeforeDomainMutation(
        dataSource = dataSource,
        location = location,
        previousVersion = previousVersion,
        expectedVersion = expectedVersion,
    ) { connection -> seedDuplicateGroup(connection, ReadFixture.DIFFERENT_TIMESTAMPS) }

    fun assertOwnershipMismatchFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        assertUnsafeFixtureFailsBeforeDomainMutation(
            dataSource = dataSource,
            location = location,
            previousVersion = previousVersion,
            expectedVersion = expectedVersion,
            seedUnsafeFixture = ::seedOwnershipMismatch,
        )
    }

    fun assertNullBookingFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        assertUnsafeFixtureFailsBeforeDomainMutation(
            dataSource = dataSource,
            location = location,
            previousVersion = previousVersion,
            expectedVersion = expectedVersion,
            seedUnsafeFixture = ::seedNullBookingThread,
        )
    }

    fun assertMissingBookingFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        assertUnsafeFixtureFailsBeforeDomainMutation(
            dataSource = dataSource,
            location = location,
            previousVersion = previousVersion,
            expectedVersion = expectedVersion,
            seedUnsafeFixture = ::seedMissingBookingThread,
        )
    }

    fun assertConflictingStatusesFailBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        assertUnsafeFixtureFailsBeforeDomainMutation(
            dataSource = dataSource,
            location = location,
            previousVersion = previousVersion,
            expectedVersion = expectedVersion,
            seedUnsafeFixture = ::seedConflictingStatuses,
        )
    }

    fun assertUnknownReferenceFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        migrateToPrevious(dataSource, location, previousVersion)
        val fixture =
            dataSource.connection.use { connection ->
                val duplicateFixture = seedDuplicateGroup(connection, ReadFixture.NONE)
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE booking_thread_unhandled_reference (
                            id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            thread_id BIGINT NOT NULL REFERENCES support_threads(id)
                        )
                        """.trimIndent(),
                    )
                }
                connection.prepareStatement(
                    "INSERT INTO booking_thread_unhandled_reference (thread_id) VALUES (?)",
                ).use { statement ->
                    statement.setLong(1, duplicateFixture.duplicateTwoId)
                    assertEquals(1, statement.executeUpdate())
                }
                duplicateFixture
            }
        val beforeSnapshot = dataSource.connection.use(::loadDomainSnapshot)
        dataSource.connection.use { connection ->
            assertEquals(
                (KNOWN_REFERENCES + ReferenceRow("booking_thread_unhandled_reference", "thread_id"))
                    .sortedWith(compareBy(ReferenceRow::tableName, ReferenceRow::columnName)),
                loadReferenceInventory(connection),
            )
        }

        assertFailsWith<FlywayException> {
            flyway(dataSource, location, expectedVersion).migrate()
        }

        dataSource.connection.use { connection ->
            assertEquals(beforeSnapshot, loadDomainSnapshot(connection))
            assertEquals(
                1,
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM booking_thread_unhandled_reference WHERE thread_id = ?",
                ).use { statement ->
                    statement.setLong(1, fixture.duplicateTwoId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getInt(1)
                    }
                },
            )
        }
    }

    fun assertUnknownExplicitThreadIdColumnFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        migrateToPrevious(dataSource, location, previousVersion)
        val fixture =
            dataSource.connection.use { connection ->
                val duplicateFixture = seedDuplicateGroup(connection, ReadFixture.NONE)
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE booking_thread_unhandled_explicit_reference (
                            id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            thread_id BIGINT NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
                connection.prepareStatement(
                    "INSERT INTO booking_thread_unhandled_explicit_reference (thread_id) VALUES (?)",
                ).use { statement ->
                    statement.setLong(1, duplicateFixture.duplicateTwoId)
                    assertEquals(1, statement.executeUpdate())
                }
                duplicateFixture
            }
        val beforeSnapshot = dataSource.connection.use(::loadDomainSnapshot)
        dataSource.connection.use(::assertKnownReferenceInventory)

        assertFailsWith<FlywayException> {
            flyway(dataSource, location, expectedVersion).migrate()
        }

        dataSource.connection.use { connection ->
            assertEquals(beforeSnapshot, loadDomainSnapshot(connection))
            assertEquals(
                1,
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM booking_thread_unhandled_explicit_reference WHERE thread_id = ?",
                ).use { statement ->
                    statement.setLong(1, fixture.duplicateTwoId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getInt(1)
                    }
                },
            )
        }
    }

    fun assertUnknownAlternateThreadReferenceColumnFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        migrateToPrevious(dataSource, location, previousVersion)
        val fixture =
            dataSource.connection.use { connection ->
                val duplicateFixture = seedDuplicateGroup(connection, ReadFixture.NONE)
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE booking_thread_unhandled_alternate_reference (
                            id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            support_thread_id BIGINT NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
                connection.prepareStatement(
                    "INSERT INTO booking_thread_unhandled_alternate_reference (support_thread_id) VALUES (?)",
                ).use { statement ->
                    statement.setLong(1, duplicateFixture.duplicateTwoId)
                    assertEquals(1, statement.executeUpdate())
                }
                duplicateFixture
            }
        val beforeSnapshot = dataSource.connection.use(::loadDomainSnapshot)

        assertFailsWith<FlywayException> {
            flyway(dataSource, location, expectedVersion).migrate()
        }

        dataSource.connection.use { connection ->
            assertEquals(beforeSnapshot, loadDomainSnapshot(connection))
            assertEquals(
                1,
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM booking_thread_unhandled_alternate_reference WHERE support_thread_id = ?",
                ).use { statement ->
                    statement.setLong(1, fixture.duplicateTwoId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getInt(1)
                    }
                },
            )
        }
    }

    fun assertUnknownJsonReferenceFamilyFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        migrateToPrevious(dataSource, location, previousVersion)
        val fixture =
            dataSource.connection.use { connection ->
                val duplicateFixture = seedDuplicateGroup(connection, ReadFixture.NONE)
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE booking_thread_unhandled_json_reference (
                            id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            payload_json TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
                connection.prepareStatement(
                    "INSERT INTO booking_thread_unhandled_json_reference (payload_json) VALUES (?)",
                ).use { statement ->
                    statement.setString(1, "{\"threadId\":${duplicateFixture.duplicateTwoId}}")
                    assertEquals(1, statement.executeUpdate())
                }
                duplicateFixture
            }
        val beforeSnapshot = dataSource.connection.use(::loadDomainSnapshot)

        assertFailsWith<FlywayException> {
            flyway(dataSource, location, expectedVersion).migrate()
        }

        dataSource.connection.use { connection ->
            assertEquals(beforeSnapshot, loadDomainSnapshot(connection))
            assertEquals(
                "{\"threadId\":${fixture.duplicateTwoId}}",
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT payload_json FROM booking_thread_unhandled_json_reference ORDER BY id",
                    ).use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getString(1)
                    }
                },
            )
        }
    }

    fun assertAuditTicketIdIsRemapped(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        val (fixture, afterMigration) = migrateSafeAuditFixture(dataSource, location, previousVersion, expectedVersion)
        val remapped = afterMigration.audits.single { it.id == fixture.bookingAuditIds.last() }
        assertEquals(fixture.survivorId, auditTicketId(remapped))
    }

    fun assertAuditEntityAndPayloadReferencesMatch(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        val (fixture, afterMigration) = migrateSafeAuditFixture(dataSource, location, previousVersion, expectedVersion)
        fixture.bookingAuditIds.forEach { auditId ->
            val audit = afterMigration.audits.single { it.id == auditId }
            assertEquals(audit.entityId, auditTicketId(audit))
        }
    }

    fun assertAuditNonReferencePayloadFieldsArePreserved(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        val (fixture, afterMigration) = migrateSafeAuditFixture(dataSource, location, previousVersion, expectedVersion)
        val beforeById = fixture.beforeMigration.audits.associateBy(AuditRow::id)
        fixture.bookingAuditIds.forEach { auditId ->
            val before = beforeById.getValue(auditId)
            val after = afterMigration.audits.single { it.id == auditId }
            assertEquals(payloadWithoutTicketId(before.payloadJson), payloadWithoutTicketId(after.payloadJson))
            assertAuditEnvelopePreserved(before, after)
        }
    }

    fun assertAuditTicketIdOrderFormattingAndSafeFieldsAreSemantic(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        migrateToPrevious(dataSource, location, previousVersion)
        val (fixture, beforeById) =
            dataSource.connection.use { connection ->
                val seeded = seedDuplicateGroup(connection, ReadFixture.NONE)
                val payloads =
                    listOf(
                        productionStatusPayload(
                            ticketId = seeded.survivorId,
                            venueId = seeded.venueId,
                            source = "GUEST_MINIAPP",
                        ),
                        """{"ticketId":${seeded.duplicateOneId},"actorUserId":$AUDIT_ACTOR_USER_ID,""" +
                            """"source":"VENUE_MINIAPP","newStatus":"RESOLVED",""" +
                            """"oldStatus":"WAITING_USER","venueId":${seeded.venueId}}""",
                        """
                        {
                          "source": "GUEST_MINIAPP",
                          "metadata": {"conversationStatus": "OPEN", "retry": false},
                          "newStatus": "RESOLVED",
                          "ticketId" : ${seeded.duplicateTwoId},
                          "venueId": ${seeded.venueId},
                          "oldStatus": "WAITING_USER",
                          "actorUserId": $AUDIT_ACTOR_USER_ID
                        }
                        """.trimIndent(),
                    )
                seeded.bookingAuditIds.zip(payloads).forEach { (auditId, payload) ->
                    updateAuditPayload(connection, auditId, payload)
                }
                seeded to
                    loadAudits(connection)
                        .filter { it.id in seeded.bookingAuditIds }
                        .associateBy(AuditRow::id)
            }

        migrateToExpected(dataSource, location, expectedVersion)

        val afterById =
            dataSource.connection.use(::loadAudits)
                .filter { it.id in fixture.bookingAuditIds }
                .associateBy(AuditRow::id)
        fixture.bookingAuditIds.forEach { auditId ->
            val before = beforeById.getValue(auditId)
            val after = afterById.getValue(auditId)
            assertEquals(fixture.survivorId, after.entityId)
            assertEquals(fixture.survivorId, auditTicketId(after))
            assertEquals(payloadWithoutTicketId(before.payloadJson), payloadWithoutTicketId(after.payloadJson))
            assertAuditEnvelopePreserved(before, after)
        }
    }

    fun assertMissingAuditTicketIdFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnsafeAuditFailsBeforeDomainMutation(dataSource, location, previousVersion, expectedVersion) {
            connection, fixture ->
        updateAuditPayload(
            connection,
            fixture.bookingAuditIds.last(),
            productionStatusPayload(
                ticketId = null,
                venueId = fixture.venueId,
                source = "GUEST_MINIAPP",
            ),
        )
    }

    fun assertNonNumericAuditTicketIdFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnsafeAuditFailsBeforeDomainMutation(dataSource, location, previousVersion, expectedVersion) {
            connection, fixture ->
        updateAuditPayload(
            connection,
            fixture.bookingAuditIds.last(),
            productionStatusPayloadWithRawTicketId(
                rawTicketId = "\"${fixture.duplicateTwoId}\"",
                venueId = fixture.venueId,
                source = "GUEST_MINIAPP",
            ),
        )
    }

    fun assertOutOfRangeAuditTicketIdFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        migrateToPrevious(dataSource, location, previousVersion)
        dataSource.connection.use { connection ->
            val fixture = seedDuplicateGroup(connection, ReadFixture.NONE)
            updateAuditPayload(
                connection,
                fixture.bookingAuditIds.last(),
                productionStatusPayloadWithRawTicketId(
                    rawTicketId = "9223372036854775808",
                    venueId = fixture.venueId,
                    source = "GUEST_MINIAPP",
                ),
            )
        }
        val beforeSnapshot = dataSource.connection.use(::loadDomainSnapshot)

        val failure =
            assertFailsWith<FlywayException> {
                flyway(dataSource, location, expectedVersion).migrate()
            }
        val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
        val messages = causes.mapNotNull(Throwable::message)
        val sqlStates = causes.filterIsInstance<SQLException>().mapNotNull { it.sqlState }
        assertTrue(
            messages.any {
                it.contains("BOOKING_THREAD audit ticketId does not match entity_id") ||
                    it.contains("booking_thread_integrity_guard_valid", ignoreCase = true)
            },
            "Expected the intentional audit mismatch guard, but got: ${messages.joinToString(" | ")}",
        )
        assertTrue(
            sqlStates.any { it == "P0001" || it == "23513" },
            "Expected a guard SQLSTATE, but got: $sqlStates",
        )
        assertFalse(
            sqlStates.any { it.startsWith("22") },
            "Out-of-range ticketId must not fail through a data-conversion SQLSTATE: $sqlStates",
        )
        assertEquals(beforeSnapshot, dataSource.connection.use(::loadDomainSnapshot))
    }

    fun assertMismatchedAuditTicketIdFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnsafeAuditFailsBeforeDomainMutation(dataSource, location, previousVersion, expectedVersion) {
            connection, fixture ->
        updateAuditPayload(
            connection,
            fixture.bookingAuditIds.last(),
            productionStatusPayload(
                ticketId = fixture.duplicateOneId,
                venueId = fixture.venueId,
                source = "GUEST_MINIAPP",
            ),
        )
    }

    fun assertPayloadOnlyBookingReferenceFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnsafeAuditFailsBeforeDomainMutation(dataSource, location, previousVersion, expectedVersion) {
            connection, fixture ->
        connection.prepareStatement(
            "UPDATE audit_log SET entity_id = ? WHERE id = ?",
        ).use { statement ->
            statement.setLong(1, fixture.supportTicketId)
            statement.setLong(2, fixture.bookingAuditIds.last())
            assertEquals(1, statement.executeUpdate())
        }
    }

    fun assertPayloadOnlyBookingReferenceWithNullEntityFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnsafeAuditFailsBeforeDomainMutation(dataSource, location, previousVersion, expectedVersion) {
            connection, fixture ->
        connection.prepareStatement(
            "UPDATE audit_log SET entity_id = NULL WHERE id = ?",
        ).use { statement ->
            statement.setLong(1, fixture.bookingAuditIds.last())
            assertEquals(1, statement.executeUpdate())
        }
    }

    fun assertEscapedPayloadOnlyBookingReferenceWithNullEntityFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnsafeAuditFailsBeforeDomainMutation(dataSource, location, previousVersion, expectedVersion) {
            connection, fixture ->
        val escapedPayload =
            productionStatusPayload(
                ticketId = fixture.duplicateTwoId,
                venueId = fixture.venueId,
                source = "GUEST_MINIAPP",
            ).replace(
                "\"ticketId\":${fixture.duplicateTwoId}",
                "\"\\u0074icketId\":${fixture.duplicateTwoId}",
            )
        connection.prepareStatement(
            "UPDATE audit_log SET entity_id = NULL, payload_json = ? WHERE id = ?",
        ).use { statement ->
            statement.setString(1, escapedPayload)
            statement.setLong(2, fixture.bookingAuditIds.last())
            assertEquals(1, statement.executeUpdate())
        }
    }

    fun assertUnknownAuditActionAndEntityShapeFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnsafeAuditFailsBeforeDomainMutation(dataSource, location, previousVersion, expectedVersion) {
            connection, fixture ->
        connection.prepareStatement(
            "UPDATE audit_log SET action = ?, entity_type = ? WHERE id = ?",
        ).use { statement ->
            statement.setString(1, "LEGACY_BOOKING_THREAD_STATUS_CHANGED")
            statement.setString(2, "support_thread")
            statement.setLong(3, fixture.bookingAuditIds.last())
            assertEquals(1, statement.executeUpdate())
        }
    }

    fun assertKnownAuditAliasFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnsafeAuditFailsBeforeDomainMutation(dataSource, location, previousVersion, expectedVersion) {
            connection, fixture ->
        val payload =
            productionStatusPayload(
                ticketId = fixture.duplicateTwoId,
                venueId = fixture.venueId,
                source = "GUEST_MINIAPP",
            ).dropLast(1) + ",\"threadId\":${fixture.duplicateTwoId}}"
        updateAuditPayload(connection, fixture.bookingAuditIds.last(), payload)
    }

    fun assertEscapedKnownAuditAliasFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnsafeAuditFailsBeforeDomainMutation(dataSource, location, previousVersion, expectedVersion) {
            connection, fixture ->
        val payload =
            productionStatusPayload(
                ticketId = fixture.duplicateTwoId,
                venueId = fixture.venueId,
                source = "GUEST_MINIAPP",
            ).dropLast(1) + ",\"\\u0074hreadId\":${fixture.duplicateTwoId}}"
        updateAuditPayload(connection, fixture.bookingAuditIds.last(), payload)
    }

    fun assertNestedConversationThreadIdFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnknownRecursiveAuditKeyFails(
        dataSource,
        location,
        previousVersion,
        expectedVersion,
    ) { fixture -> "\"metadata\":{\"conversationThreadId\":${fixture.duplicateTwoId}}" }

    fun assertEscapedConversationThreadIdFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnknownRecursiveAuditKeyFails(
        dataSource,
        location,
        previousVersion,
        expectedVersion,
    ) { fixture -> "\"metadata\":{\"conversation\\u0054hreadId\":${fixture.duplicateTwoId}}" }

    fun assertNestedThreadIdFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnknownRecursiveAuditKeyFails(
        dataSource,
        location,
        previousVersion,
        expectedVersion,
    ) { fixture -> "\"metadata\":{\"thread_id\":${fixture.duplicateTwoId}}" }

    fun assertArrayTicketIdsFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnknownRecursiveAuditKeyFails(
        dataSource,
        location,
        previousVersion,
        expectedVersion,
    ) { fixture -> "\"metadata\":[{\"ticketIds\":[${fixture.duplicateTwoId}]}]" }

    fun assertNumericStringUnknownReferenceFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnknownRecursiveAuditKeyFails(
        dataSource,
        location,
        previousVersion,
        expectedVersion,
    ) { fixture -> "\"metadata\":{\"conversationThreadId\":\"${fixture.duplicateTwoId}\"}" }

    fun assertMixedPlainAndEscapedAuditTicketIdsFailBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnsafeAuditFailsBeforeDomainMutation(dataSource, location, previousVersion, expectedVersion) {
            connection, fixture ->
        val payload =
            productionStatusPayload(
                ticketId = fixture.duplicateTwoId,
                venueId = fixture.venueId,
                source = "GUEST_MINIAPP",
            ).dropLast(1) + ",\"\\u0074icketId\":${fixture.duplicateTwoId}}"
        updateAuditPayload(connection, fixture.bookingAuditIds.last(), payload)
    }

    fun assertNonObjectAuditPayloadFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnsafeAuditFailsBeforeDomainMutation(dataSource, location, previousVersion, expectedVersion) {
            connection, fixture ->
        updateAuditPayload(connection, fixture.bookingAuditIds.last(), "[]")
    }

    fun assertNestedAuditTicketIdFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnsafeAuditFailsBeforeDomainMutation(dataSource, location, previousVersion, expectedVersion) {
            connection, fixture ->
        val flatPayload =
            productionStatusPayload(
                ticketId = fixture.duplicateTwoId,
                venueId = fixture.venueId,
                source = "GUEST_MINIAPP",
            )
        val nestedPayload =
            flatPayload.replace(
                "\"ticketId\":${fixture.duplicateTwoId}",
                "\"details\":{\"ticketId\":${fixture.duplicateTwoId}}",
            )
        updateAuditPayload(connection, fixture.bookingAuditIds.last(), nestedPayload)
    }

    fun assertTopLevelAndConflictingNestedAuditTicketIdsFailBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) = assertUnsafeAuditFailsBeforeDomainMutation(dataSource, location, previousVersion, expectedVersion) {
            connection, fixture ->
        val payload =
            productionStatusPayload(
                ticketId = fixture.duplicateTwoId,
                venueId = fixture.venueId,
                source = "GUEST_MINIAPP",
            ).dropLast(1) + ",\"details\":{\"ticketId\":${fixture.duplicateOneId}}}"
        updateAuditPayload(connection, fixture.bookingAuditIds.last(), payload)
    }

    fun assertTopLevelAuditTicketIdWithNestedDataIsPreserved(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        migrateToPrevious(dataSource, location, previousVersion)
        val (fixture, beforeAudit) =
            dataSource.connection.use { connection ->
                val seeded = seedDuplicateGroup(connection, ReadFixture.NONE)
                val payload =
                    productionStatusPayload(
                        ticketId = seeded.duplicateTwoId,
                        venueId = seeded.venueId,
                        source = "GUEST_MINIAPP",
                    ).dropLast(1) +
                        ",\"metadata\":{\"channel\":\"MINIAPP\",\"retry\":false}," +
                        "\"note\":\"brace { is data\"}"
                updateAuditPayload(connection, seeded.bookingAuditIds.last(), payload)
                seeded to loadAudits(connection).single { it.id == seeded.bookingAuditIds.last() }
            }

        migrateToExpected(dataSource, location, expectedVersion)

        val afterAudit =
            dataSource.connection.use(::loadAudits).single { it.id == fixture.bookingAuditIds.last() }
        assertEquals(fixture.survivorId, afterAudit.entityId)
        assertEquals(fixture.survivorId, auditTicketId(afterAudit))
        assertEquals(
            payloadWithoutTicketId(beforeAudit.payloadJson),
            payloadWithoutTicketId(afterAudit.payloadJson),
        )
        assertAuditEnvelopePreserved(beforeAudit, afterAudit)
    }

    fun assertConversationStatusIsNotAReferenceKey(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        migrateToPrevious(dataSource, location, previousVersion)
        val fixture =
            dataSource.connection.use { connection ->
                seedDuplicateGroup(connection, ReadFixture.NONE).also { seeded ->
                    val payload =
                        productionStatusPayload(
                            ticketId = seeded.duplicateTwoId,
                            venueId = seeded.venueId,
                            source = "GUEST_MINIAPP",
                        ).dropLast(1) + ",\"metadata\":{\"conversationStatus\":\"OPEN\"}}"
                    updateAuditPayload(connection, seeded.bookingAuditIds.last(), payload)
                }
            }

        migrateToExpected(dataSource, location, expectedVersion)

        val remapped =
            dataSource.connection.use(::loadAudits).single { it.id == fixture.bookingAuditIds.last() }
        assertEquals(fixture.survivorId, remapped.entityId)
        assertEquals(fixture.survivorId, auditTicketId(remapped))
        assertEquals(
            "OPEN",
            Json.parseToJsonElement(remapped.payloadJson).jsonObject
                .getValue("metadata").jsonObject
                .getValue("conversationStatus").jsonPrimitive.content,
        )
    }

    fun assertKnownDurableJsonThreadReferenceFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        migrateToPrevious(dataSource, location, previousVersion)
        val payload =
            dataSource.connection.use { connection ->
                val fixture = seedDuplicateGroup(connection, ReadFixture.NONE)
                val json = "{\"chat_id\":9410001,\"threadId\":${fixture.duplicateTwoId},\"text\":\"shortcut\"}"
                connection.prepareStatement(
                    "INSERT INTO telegram_outbox (chat_id, method, payload_json) VALUES (?, ?, ?)",
                ).use { statement ->
                    statement.setLong(1, GUEST_USER_ID)
                    statement.setString(2, "sendMessage")
                    statement.setString(3, json)
                    assertEquals(1, statement.executeUpdate())
                }
                json
            }
        val beforeSnapshot = dataSource.connection.use(::loadDomainSnapshot)

        assertFailsWith<FlywayException> {
            flyway(dataSource, location, expectedVersion).migrate()
        }

        dataSource.connection.use { connection ->
            assertEquals(beforeSnapshot, loadDomainSnapshot(connection))
            assertEquals(
                payload,
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT payload_json FROM telegram_outbox ORDER BY id").use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getString(1)
                    }
                },
            )
        }
    }

    fun assertEscapedKnownDurableJsonThreadReferenceFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        migrateToPrevious(dataSource, location, previousVersion)
        val payload =
            dataSource.connection.use { connection ->
                val fixture = seedDuplicateGroup(connection, ReadFixture.NONE)
                val json =
                    "{\"chat_id\":9410001,\"\\u0074hreadId\":${fixture.duplicateTwoId},\"text\":\"shortcut\"}"
                connection.prepareStatement(
                    "INSERT INTO telegram_outbox (chat_id, method, payload_json) VALUES (?, ?, ?)",
                ).use { statement ->
                    statement.setLong(1, GUEST_USER_ID)
                    statement.setString(2, "sendMessage")
                    statement.setString(3, json)
                    assertEquals(1, statement.executeUpdate())
                }
                json
            }
        val beforeSnapshot = dataSource.connection.use(::loadDomainSnapshot)

        assertFailsWith<FlywayException> {
            flyway(dataSource, location, expectedVersion).migrate()
        }

        dataSource.connection.use { connection ->
            assertEquals(beforeSnapshot, loadDomainSnapshot(connection))
            assertEquals(
                payload,
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT payload_json FROM telegram_outbox ORDER BY id").use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getString(1)
                    }
                },
            )
        }
    }

    fun assertUnrelatedAuditWithCoincidentIdIsUnchanged(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        migrateToPrevious(dataSource, location, previousVersion)
        val auditId =
            dataSource.connection.use { connection ->
                val fixture = seedDuplicateGroup(connection, ReadFixture.NONE)
                insertAudit(
                    connection = connection,
                    action = "VENUE_PROFILE_UPDATED",
                    entityType = "venue",
                    entityId = fixture.duplicateTwoId,
                    payloadJson =
                        "{\"actorUserId\":$AUDIT_ACTOR_USER_ID," +
                            "\"venueId\":${fixture.duplicateTwoId},\"source\":\"VENUE_MINIAPP\"}",
                    createdAt = AUDIT_UNRELATED_AT,
                )
            }
        val before = dataSource.connection.use(::loadDomainSnapshot).audits.single { it.id == auditId }

        migrateToExpected(dataSource, location, expectedVersion)

        val after = dataSource.connection.use(::loadDomainSnapshot).audits.single { it.id == auditId }
        assertEquals(before, after)
    }

    fun assertMultipleAuditRowsPreserveCardinalityOrderAndTimestamps(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        migrateToPrevious(dataSource, location, previousVersion)
        val fixture = dataSource.connection.use { seedDuplicateGroup(it, ReadFixture.NONE) }
        val before = fixture.beforeMigration.audits

        migrateToExpected(dataSource, location, expectedVersion)

        val after = dataSource.connection.use(::loadDomainSnapshot).audits
        assertEquals(before.size, after.size)
        assertEquals(before.map(AuditRow::id), after.map(AuditRow::id))
        assertEquals(before.map(AuditRow::createdAt), after.map(AuditRow::createdAt))
        assertEquals(before.map(AuditRow::actorUserId), after.map(AuditRow::actorUserId))
        assertEquals(before.map(AuditRow::targetUserId), after.map(AuditRow::targetUserId))
    }

    fun prepareConcurrencyFixture(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
    ): MigrationConcurrencyFixture {
        migrateToPrevious(dataSource, location, previousVersion)
        return dataSource.connection.use { connection ->
            val fixture = seedDuplicateGroup(connection, ReadFixture.NONE)
            MigrationConcurrencyFixture(
                bookingId = fixture.bookingId,
                survivorThreadId = fixture.survivorId,
                duplicateThreadId = fixture.duplicateTwoId,
                readerUserId = GUEST_USER_ID,
            )
        }
    }

    fun loadDomainSnapshotIgnoringWriterReadForConcurrency(
        connection: Connection,
        fixture: MigrationConcurrencyFixture,
    ): Any {
        val snapshot = loadDomainSnapshot(connection)
        return snapshot.copy(
            reads =
                snapshot.reads.filterNot { read ->
                    read.threadId == fixture.duplicateThreadId && read.userId == fixture.readerUserId
                },
        )
    }

    fun flywayForConcurrency(
        dataSource: DataSource,
        location: String,
        expectedVersion: String,
    ): Flyway = flyway(dataSource, location, expectedVersion)

    fun currentVersion(
        dataSource: DataSource,
        location: String,
    ): String? = Flyway.configure().dataSource(dataSource).locations(location).load().info().current()?.version?.version

    private fun assertSafeDuplicateMerge(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
        usesGeneratedBookingKey: Boolean,
        readFixture: ReadFixture,
    ) {
        migrateToPrevious(dataSource, location, previousVersion)
        val fixture = dataSource.connection.use { seedDuplicateGroup(it, readFixture) }
        dataSource.connection.use(::assertKnownReferenceInventory)

        migrateToExpected(dataSource, location, expectedVersion)

        dataSource.connection.use { connection ->
            val afterMigration = loadDomainSnapshot(connection)
            val bookingThreads =
                afterMigration.threads.filter {
                    it.threadType == "BOOKING_THREAD" && it.bookingId == fixture.bookingId
                }
            assertEquals(1, bookingThreads.size)

            val survivor = bookingThreads.single()
            assertEquals(fixture.survivorId, survivor.id)
            assertEquals(fixture.venueId, survivor.venueId)
            assertEquals(GUEST_USER_ID, survivor.guestUserId)
            assertEquals("OTHER", survivor.category)
            assertEquals("WAITING_USER", survivor.status)
            assertEquals("survivor-title", survivor.title)
            assertEquals(EARLIEST_CREATED_AT, survivor.createdAt)
            assertEquals(LATEST_UPDATED_AT, survivor.updatedAt)
            assertEquals(LATEST_STORED_LAST_MESSAGE_AT, survivor.lastMessageAt)
            assertEquals("PLATFORM", survivor.assigneeScope)
            assertEquals("GUEST_MINIAPP", survivor.createdSource)

            val supportTicket = afterMigration.threads.single { it.id == fixture.supportTicketId }
            assertEquals(fixture.supportTicketBeforeMigration, supportTicket)
            assertEquals(2, afterMigration.threads.count { it.bookingId == fixture.bookingId })

            val expectedMessages =
                fixture.beforeMigration.messages.map { message ->
                    if (message.threadId in fixture.bookingThreadIds) {
                        message.copy(threadId = fixture.survivorId)
                    } else {
                        message
                    }
                }
            assertEquals(expectedMessages, afterMigration.messages)

            val expectedReads =
                buildList {
                    fixture.expectedMergedReads.forEach { (userId, lastReadAt) ->
                        add(ReadRow(fixture.survivorId, userId, lastReadAt))
                    }
                    addAll(
                        fixture.beforeMigration.reads.filter {
                            it.threadId !in fixture.bookingThreadIds
                        },
                    )
                }.sortedWith(compareBy(ReadRow::threadId, ReadRow::userId))
            assertEquals(expectedReads, afterMigration.reads)

            val expectedAudits =
                fixture.beforeMigration.audits.map { audit ->
                    if (audit.entityType == "support_ticket" && audit.entityId in fixture.bookingThreadIds) {
                        audit.copy(
                            entityId = fixture.survivorId,
                            payloadJson = replaceTicketId(audit.payloadJson, fixture.survivorId),
                        )
                    } else {
                        audit
                    }
                }
            assertAuditRowsSemanticallyEqual(expectedAudits, afterMigration.audits)

            assertKnownReferenceInventory(connection)
            assertUniqueInvariant(connection, fixture, usesGeneratedBookingKey)
        }
    }

    private fun assertUnsafeFixtureFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
        seedUnsafeFixture: (Connection) -> Unit,
    ) {
        migrateToPrevious(dataSource, location, previousVersion)

        dataSource.connection.use { connection ->
            seedUnsafeFixture(connection)
        }
        val beforeSnapshot = dataSource.connection.use(::loadDomainSnapshot)

        assertFailsWith<FlywayException> {
            flyway(dataSource, location, expectedVersion).migrate()
        }

        val afterSnapshot = dataSource.connection.use(::loadDomainSnapshot)
        assertEquals(beforeSnapshot, afterSnapshot)
    }

    private fun migrateSafeAuditFixture(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ): Pair<SafeFixture, DomainSnapshot> {
        migrateToPrevious(dataSource, location, previousVersion)
        val fixture = dataSource.connection.use { seedDuplicateGroup(it, ReadFixture.NONE) }
        migrateToExpected(dataSource, location, expectedVersion)
        return fixture to dataSource.connection.use(::loadDomainSnapshot)
    }

    private fun assertUnsafeAuditFailsBeforeDomainMutation(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
        makeUnsafe: (Connection, SafeFixture) -> Unit,
    ) {
        migrateToPrevious(dataSource, location, previousVersion)
        dataSource.connection.use { connection ->
            val fixture = seedDuplicateGroup(connection, ReadFixture.NONE)
            makeUnsafe(connection, fixture)
        }
        val beforeSnapshot = dataSource.connection.use(::loadDomainSnapshot)

        assertFailsWith<FlywayException> {
            flyway(dataSource, location, expectedVersion).migrate()
        }

        assertEquals(beforeSnapshot, dataSource.connection.use(::loadDomainSnapshot))
    }

    private fun assertUnknownRecursiveAuditKeyFails(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
        unknownMember: (SafeFixture) -> String,
    ) = assertUnsafeAuditFailsBeforeDomainMutation(dataSource, location, previousVersion, expectedVersion) {
            connection, fixture ->
        val payload =
            productionStatusPayload(
                ticketId = fixture.duplicateTwoId,
                venueId = fixture.venueId,
                source = "GUEST_MINIAPP",
            ).dropLast(1) + ",${unknownMember(fixture)}}"
        updateAuditPayload(connection, fixture.bookingAuditIds.last(), payload)
    }

    private fun seedCleanSchema(connection: Connection): SafeFixture {
        seedUsers(connection)
        val venueId = insertVenue(connection, "Clean booking integrity venue")
        val bookingId = insertBooking(connection, venueId, GUEST_USER_ID, displayNumber = 1)
        val threadId =
            insertThread(
                connection = connection,
                venueId = venueId,
                guestUserId = GUEST_USER_ID,
                bookingId = bookingId,
                category = "OTHER",
                status = "IN_PROGRESS",
                title = "clean-booking-thread",
                threadType = "BOOKING_THREAD",
                assigneeScope = "PLATFORM",
                createdSource = "GUEST_BOT",
                lastMessageAt = null,
                createdAt = CLEAN_CREATED_AT,
                updatedAt = CLEAN_UPDATED_AT,
            )
        val supportTicketId =
            insertThread(
                connection = connection,
                venueId = venueId,
                guestUserId = GUEST_USER_ID,
                bookingId = bookingId,
                category = "OTHER",
                status = "NEW",
                title = "clean-support-ticket",
                threadType = "SUPPORT_TICKET",
                assigneeScope = "VENUE",
                createdSource = "GUEST_MINIAPP",
                lastMessageAt = SUPPORT_TICKET_MESSAGE_AT,
                createdAt = SUPPORT_TICKET_CREATED_AT,
                updatedAt = SUPPORT_TICKET_UPDATED_AT,
            )
        insertMessage(
            connection,
            threadId,
            GUEST_USER_ID,
            "GUEST",
            "GUEST_BOT",
            "clean-booking-message",
            71_001L,
            MESSAGE_ONE_AT,
        )
        insertRead(connection, threadId, COMMON_READER_USER_ID, COMMON_READ_AT)
        val bookingAuditId =
            insertStatusAudit(
                connection = connection,
                threadId = threadId,
                venueId = venueId,
                source = "GUEST_MINIAPP",
                createdAt = AUDIT_ONE_AT,
            )

        return SafeFixture(
            venueId = venueId,
            bookingId = bookingId,
            survivorId = threadId,
            duplicateOneId = threadId,
            duplicateTwoId = threadId,
            supportTicketId = supportTicketId,
            supportTicketBeforeMigration = loadThreads(connection).single { it.id == supportTicketId },
            bookingAuditIds = listOf(bookingAuditId),
            expectedMergedReads = mapOf(COMMON_READER_USER_ID to COMMON_READ_AT),
            beforeMigration = loadDomainSnapshot(connection),
        )
    }

    private fun seedDuplicateGroup(
        connection: Connection,
        readFixture: ReadFixture,
    ): SafeFixture {
        seedUsers(connection)

        val venueId = insertVenue(connection, "Booking integrity venue")
        val bookingId = insertBooking(connection, venueId, GUEST_USER_ID, displayNumber = 1)

        val survivorId =
            insertThread(
                connection = connection,
                venueId = venueId,
                guestUserId = GUEST_USER_ID,
                bookingId = bookingId,
                category = "OTHER",
                status = "WAITING_USER",
                title = "survivor-title",
                threadType = "BOOKING_THREAD",
                assigneeScope = "PLATFORM",
                createdSource = "GUEST_MINIAPP",
                lastMessageAt = null,
                createdAt = SURVIVOR_CREATED_AT,
                updatedAt = SURVIVOR_UPDATED_AT,
            )
        val duplicateOneId =
            insertThread(
                connection = connection,
                venueId = venueId,
                guestUserId = GUEST_USER_ID,
                bookingId = bookingId,
                category = "BOOKING",
                status = "WAITING_USER",
                title = "duplicate-one-title",
                threadType = "BOOKING_THREAD",
                assigneeScope = "VENUE",
                createdSource = "BOOKING_FLOW",
                lastMessageAt = EARLIER_STORED_LAST_MESSAGE_AT,
                createdAt = EARLIEST_CREATED_AT,
                updatedAt = LATEST_UPDATED_AT,
            )
        val duplicateTwoId =
            insertThread(
                connection = connection,
                venueId = venueId,
                guestUserId = GUEST_USER_ID,
                bookingId = bookingId,
                category = "OTHER",
                status = "WAITING_USER",
                title = "duplicate-two-title",
                threadType = "BOOKING_THREAD",
                assigneeScope = "PLATFORM",
                createdSource = "GUEST_BOT",
                lastMessageAt = LATEST_STORED_LAST_MESSAGE_AT,
                createdAt = DUPLICATE_TWO_CREATED_AT,
                updatedAt = DUPLICATE_TWO_UPDATED_AT,
            )
        val supportTicketId =
            insertThread(
                connection = connection,
                venueId = venueId,
                guestUserId = GUEST_USER_ID,
                bookingId = bookingId,
                category = "OTHER",
                status = "NEW",
                title = "support-ticket-title",
                threadType = "SUPPORT_TICKET",
                assigneeScope = "VENUE",
                createdSource = "GUEST_MINIAPP",
                lastMessageAt = SUPPORT_TICKET_MESSAGE_AT,
                createdAt = SUPPORT_TICKET_CREATED_AT,
                updatedAt = SUPPORT_TICKET_UPDATED_AT,
            )
        val supportTicketBeforeMigration = loadThreads(connection).single { it.id == supportTicketId }

        insertMessage(
            connection,
            survivorId,
            GUEST_USER_ID,
            "GUEST",
            "GUEST_BOT",
            "survivor-message",
            72_001L,
            MESSAGE_ONE_AT,
        )
        insertMessage(
            connection,
            duplicateOneId,
            VENUE_AUTHOR_USER_ID,
            "VENUE",
            "VENUE_MINIAPP",
            "duplicate-one-message",
            72_002L,
            MESSAGE_TWO_AT,
        )
        insertMessage(
            connection,
            duplicateTwoId,
            GUEST_USER_ID,
            "GUEST",
            "GUEST_MINIAPP",
            "duplicate-two-message",
            72_003L,
            MESSAGE_THREE_AT,
        )
        insertMessage(
            connection,
            supportTicketId,
            null,
            "SYSTEM",
            "SYSTEM",
            "support-ticket-message",
            null,
            SUPPORT_TICKET_MESSAGE_AT,
        )

        val expectedMergedReads =
            when (readFixture) {
                ReadFixture.NONE -> emptyMap()
                ReadFixture.IDENTICAL -> {
                    listOf(survivorId, duplicateOneId, duplicateTwoId).forEach { threadId ->
                        insertRead(connection, threadId, COMMON_READER_USER_ID, COMMON_READ_AT)
                        insertRead(connection, threadId, SECOND_READER_USER_ID, SECOND_READ_AT)
                    }
                    mapOf(
                        COMMON_READER_USER_ID to COMMON_READ_AT,
                        SECOND_READER_USER_ID to SECOND_READ_AT,
                    )
                }
                ReadFixture.PARTIAL -> {
                    listOf(survivorId, duplicateOneId, duplicateTwoId).forEach { threadId ->
                        insertRead(connection, threadId, COMMON_READER_USER_ID, COMMON_READ_AT)
                    }
                    insertRead(connection, survivorId, PARTIAL_READER_USER_ID, PARTIAL_READ_AT)
                    insertRead(connection, duplicateOneId, PARTIAL_READER_USER_ID, PARTIAL_READ_AT)
                    emptyMap()
                }
                ReadFixture.DIFFERENT_TIMESTAMPS -> {
                    insertRead(connection, survivorId, COMMON_READER_USER_ID, COMMON_READ_AT)
                    insertRead(connection, duplicateOneId, COMMON_READER_USER_ID, DIFFERENT_READ_AT)
                    insertRead(connection, duplicateTwoId, COMMON_READER_USER_ID, COMMON_READ_AT)
                    emptyMap()
                }
            }
        insertRead(connection, supportTicketId, TICKET_READER_USER_ID, SUPPORT_TICKET_READ_AT)

        val bookingAuditIds =
            listOf(
                insertStatusAudit(connection, survivorId, venueId, "GUEST_MINIAPP", AUDIT_ONE_AT),
                insertStatusAudit(connection, duplicateOneId, venueId, "VENUE_MINIAPP", AUDIT_TWO_AT),
                insertStatusAudit(connection, duplicateTwoId, venueId, "GUEST_MINIAPP", AUDIT_THREE_AT),
            )
        insertStatusAudit(connection, supportTicketId, venueId, "VENUE_MINIAPP", AUDIT_FOUR_AT)

        return SafeFixture(
            venueId = venueId,
            bookingId = bookingId,
            survivorId = survivorId,
            duplicateOneId = duplicateOneId,
            duplicateTwoId = duplicateTwoId,
            supportTicketId = supportTicketId,
            supportTicketBeforeMigration = supportTicketBeforeMigration,
            bookingAuditIds = bookingAuditIds,
            expectedMergedReads = expectedMergedReads,
            beforeMigration = loadDomainSnapshot(connection),
        )
    }

    private fun seedOwnershipMismatch(connection: Connection) {
        seedUsers(connection)

        val canonicalVenueId = insertVenue(connection, "Canonical booking venue")
        val foreignVenueId = insertVenue(connection, "Foreign booking venue")
        val bookingId = insertBooking(connection, canonicalVenueId, GUEST_USER_ID, displayNumber = 1)

        val canonicalThreadId =
            insertThread(
                connection = connection,
                venueId = canonicalVenueId,
                guestUserId = GUEST_USER_ID,
                bookingId = bookingId,
                category = "OTHER",
                status = "WAITING_USER",
                title = "canonical-thread",
                threadType = "BOOKING_THREAD",
                assigneeScope = "PLATFORM",
                createdSource = "GUEST_MINIAPP",
                lastMessageAt = STALE_LAST_MESSAGE_AT,
                createdAt = SURVIVOR_CREATED_AT,
                updatedAt = SURVIVOR_UPDATED_AT,
            )
        val mismatchedThreadId =
            insertThread(
                connection = connection,
                venueId = foreignVenueId,
                guestUserId = FOREIGN_GUEST_USER_ID,
                bookingId = bookingId,
                category = "BOOKING",
                status = "WAITING_USER",
                title = "mismatched-thread",
                threadType = "BOOKING_THREAD",
                assigneeScope = "VENUE",
                createdSource = "BOOKING_FLOW",
                lastMessageAt = STALE_LAST_MESSAGE_AT,
                createdAt = DUPLICATE_ONE_CREATED_AT,
                updatedAt = DUPLICATE_ONE_UPDATED_AT,
            )

        insertMessage(
            connection,
            canonicalThreadId,
            GUEST_USER_ID,
            "GUEST",
            "GUEST_BOT",
            "canonical-message",
            73_001L,
            MESSAGE_ONE_AT,
        )
        insertMessage(
            connection,
            mismatchedThreadId,
            GUEST_USER_ID,
            "GUEST",
            "GUEST_BOT",
            "mismatched-message",
            73_002L,
            MESSAGE_TWO_AT,
        )
        insertStatusAudit(connection, canonicalThreadId, canonicalVenueId, "GUEST_MINIAPP", AUDIT_ONE_AT)
        insertStatusAudit(connection, mismatchedThreadId, foreignVenueId, "VENUE_MINIAPP", AUDIT_TWO_AT)
    }

    private fun seedNullBookingThread(connection: Connection) {
        seedUsers(connection)

        val venueId = insertVenue(connection, "Null booking venue")
        val threadId =
            insertThread(
                connection = connection,
                venueId = venueId,
                guestUserId = GUEST_USER_ID,
                bookingId = null,
                category = "BOOKING",
                status = "WAITING_USER",
                title = "null-booking-thread",
                threadType = "BOOKING_THREAD",
                assigneeScope = "VENUE",
                createdSource = "BOOKING_FLOW",
                lastMessageAt = MESSAGE_ONE_AT,
                createdAt = SURVIVOR_CREATED_AT,
                updatedAt = SURVIVOR_UPDATED_AT,
            )
        insertMessage(
            connection,
            threadId,
            GUEST_USER_ID,
            "GUEST",
            "GUEST_BOT",
            "null-booking-message",
            74_001L,
            MESSAGE_ONE_AT,
        )
        insertRead(connection, threadId, COMMON_READER_USER_ID, COMMON_READ_AT)
        insertStatusAudit(connection, threadId, venueId, "GUEST_MINIAPP", AUDIT_ONE_AT)
    }

    private fun seedMissingBookingThread(connection: Connection) {
        seedUsers(connection)
        dropBookingForeignKey(connection)
        val venueId = insertVenue(connection, "Missing booking venue")
        val threadId =
            insertThread(
                connection = connection,
                venueId = venueId,
                guestUserId = GUEST_USER_ID,
                bookingId = MISSING_BOOKING_ID,
                category = "BOOKING",
                status = "WAITING_USER",
                title = "missing-booking-thread",
                threadType = "BOOKING_THREAD",
                assigneeScope = "VENUE",
                createdSource = "BOOKING_FLOW",
                lastMessageAt = MESSAGE_ONE_AT,
                createdAt = SURVIVOR_CREATED_AT,
                updatedAt = SURVIVOR_UPDATED_AT,
            )
        insertMessage(
            connection,
            threadId,
            GUEST_USER_ID,
            "GUEST",
            "GUEST_BOT",
            "missing-booking-message",
            75_001L,
            MESSAGE_ONE_AT,
        )
        insertRead(connection, threadId, COMMON_READER_USER_ID, COMMON_READ_AT)
        insertStatusAudit(connection, threadId, venueId, "GUEST_MINIAPP", AUDIT_ONE_AT)
    }

    private fun seedConflictingStatuses(connection: Connection) {
        seedUsers(connection)

        val venueId = insertVenue(connection, "Conflicting statuses venue")
        val bookingId = insertBooking(connection, venueId, GUEST_USER_ID, displayNumber = 1)
        val firstThreadId =
            insertThread(
                connection = connection,
                venueId = venueId,
                guestUserId = GUEST_USER_ID,
                bookingId = bookingId,
                category = "BOOKING",
                status = "WAITING_USER",
                title = "status-conflict-one",
                threadType = "BOOKING_THREAD",
                assigneeScope = "VENUE",
                createdSource = "BOOKING_FLOW",
                lastMessageAt = MESSAGE_ONE_AT,
                createdAt = SURVIVOR_CREATED_AT,
                updatedAt = SURVIVOR_UPDATED_AT,
            )
        val secondThreadId =
            insertThread(
                connection = connection,
                venueId = venueId,
                guestUserId = GUEST_USER_ID,
                bookingId = bookingId,
                category = "BOOKING",
                status = "RESOLVED",
                title = "status-conflict-two",
                threadType = "BOOKING_THREAD",
                assigneeScope = "VENUE",
                createdSource = "BOOKING_FLOW",
                lastMessageAt = MESSAGE_TWO_AT,
                createdAt = DUPLICATE_ONE_CREATED_AT,
                updatedAt = DUPLICATE_ONE_UPDATED_AT,
            )
        insertMessage(
            connection,
            firstThreadId,
            GUEST_USER_ID,
            "GUEST",
            "GUEST_BOT",
            "status-conflict-message-one",
            76_001L,
            MESSAGE_ONE_AT,
        )
        insertMessage(
            connection,
            secondThreadId,
            GUEST_USER_ID,
            "GUEST",
            "GUEST_BOT",
            "status-conflict-message-two",
            76_002L,
            MESSAGE_TWO_AT,
        )
        insertStatusAudit(connection, firstThreadId, venueId, "GUEST_MINIAPP", AUDIT_ONE_AT)
        insertStatusAudit(connection, secondThreadId, venueId, "VENUE_MINIAPP", AUDIT_TWO_AT)
    }

    private fun seedUsers(connection: Connection) {
        listOf(
            GUEST_USER_ID,
            FOREIGN_GUEST_USER_ID,
            COMMON_READER_USER_ID,
            SECOND_READER_USER_ID,
            PARTIAL_READER_USER_ID,
            TICKET_READER_USER_ID,
            AUDIT_ACTOR_USER_ID,
            VENUE_AUTHOR_USER_ID,
        ).forEach { insertUser(connection, it) }
    }

    private fun insertUser(
        connection: Connection,
        userId: Long,
    ) {
        connection.prepareStatement(
            "INSERT INTO users (telegram_user_id, username) VALUES (?, ?)",
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, "booking_integrity_$userId")
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun insertVenue(
        connection: Connection,
        name: String,
    ): Long =
        insertReturningId(
            connection,
            """
            INSERT INTO venues (name, city, address, status)
            VALUES (?, 'Moscow', 'Migration street, 1', 'PUBLISHED')
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, name)
        }

    private fun insertBooking(
        connection: Connection,
        venueId: Long,
        guestUserId: Long,
        displayNumber: Int,
    ): Long =
        insertReturningId(
            connection,
            """
            INSERT INTO bookings (
                venue_id,
                user_id,
                scheduled_at,
                party_size,
                status,
                display_date,
                display_number
            )
            VALUES (?, ?, ?, 2, 'PENDING', ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, guestUserId)
            statement.setTimestamp(3, Timestamp.from(BOOKING_SCHEDULED_AT))
            statement.setDate(4, Date.valueOf(BOOKING_DISPLAY_DATE))
            statement.setInt(5, displayNumber)
        }

    private fun insertThread(
        connection: Connection,
        venueId: Long,
        guestUserId: Long,
        bookingId: Long?,
        category: String,
        status: String,
        title: String,
        threadType: String,
        assigneeScope: String,
        createdSource: String,
        lastMessageAt: Instant?,
        createdAt: Instant,
        updatedAt: Instant,
    ): Long =
        insertReturningId(
            connection,
            """
            INSERT INTO support_threads (
                venue_id,
                guest_user_id,
                category,
                status,
                booking_id,
                title,
                last_message_at,
                created_at,
                updated_at,
                thread_type,
                assignee_scope,
                created_source
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, guestUserId)
            statement.setString(3, category)
            statement.setString(4, status)
            if (bookingId == null) {
                statement.setNull(5, java.sql.Types.BIGINT)
            } else {
                statement.setLong(5, bookingId)
            }
            statement.setString(6, title)
            if (lastMessageAt == null) {
                statement.setNull(7, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
            } else {
                statement.setTimestamp(7, Timestamp.from(lastMessageAt))
            }
            statement.setTimestamp(8, Timestamp.from(createdAt))
            statement.setTimestamp(9, Timestamp.from(updatedAt))
            statement.setString(10, threadType)
            statement.setString(11, assigneeScope)
            statement.setString(12, createdSource)
        }

    private fun insertMessage(
        connection: Connection,
        threadId: Long,
        authorUserId: Long?,
        authorRole: String,
        source: String,
        text: String,
        telegramMessageId: Long?,
        createdAt: Instant,
    ): Long =
        insertReturningId(
            connection,
            """
            INSERT INTO support_messages (
                thread_id,
                author_user_id,
                author_role,
                source,
                text,
                telegram_message_id,
                created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, threadId)
            if (authorUserId == null) {
                statement.setNull(2, java.sql.Types.BIGINT)
            } else {
                statement.setLong(2, authorUserId)
            }
            statement.setString(3, authorRole)
            statement.setString(4, source)
            statement.setString(5, text)
            if (telegramMessageId == null) {
                statement.setNull(6, java.sql.Types.BIGINT)
            } else {
                statement.setLong(6, telegramMessageId)
            }
            statement.setTimestamp(7, Timestamp.from(createdAt))
        }

    private fun insertRead(
        connection: Connection,
        threadId: Long,
        userId: Long,
        lastReadAt: Instant,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO support_thread_reads (thread_id, user_id, last_read_at)
            VALUES (?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.setLong(2, userId)
            statement.setTimestamp(3, Timestamp.from(lastReadAt))
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun insertStatusAudit(
        connection: Connection,
        threadId: Long,
        venueId: Long,
        source: String,
        createdAt: Instant,
    ): Long =
        insertAudit(
            connection = connection,
            action = SUPPORT_TICKET_STATUS_CHANGED,
            entityType = "support_ticket",
            entityId = threadId,
            payloadJson = productionStatusPayload(threadId, venueId, source),
            createdAt = createdAt,
        )

    private fun insertAudit(
        connection: Connection,
        action: String,
        entityType: String,
        entityId: Long,
        payloadJson: String,
        createdAt: Instant,
    ): Long =
        insertReturningId(
            connection,
            """
            INSERT INTO audit_log (
                actor_user_id,
                action,
                entity_type,
                entity_id,
                payload_json,
                target_user_id,
                created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, AUDIT_ACTOR_USER_ID)
            statement.setString(2, action)
            statement.setString(3, entityType)
            statement.setLong(4, entityId)
            statement.setString(5, payloadJson)
            statement.setLong(6, GUEST_USER_ID)
            statement.setTimestamp(7, Timestamp.from(createdAt))
        }

    private fun updateAuditPayload(
        connection: Connection,
        auditId: Long,
        payloadJson: String,
    ) {
        connection.prepareStatement("UPDATE audit_log SET payload_json = ? WHERE id = ?").use { statement ->
            statement.setString(1, payloadJson)
            statement.setLong(2, auditId)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun productionStatusPayload(
        ticketId: Long?,
        venueId: Long,
        source: String,
    ): String =
        productionStatusPayloadWithRawTicketId(
            rawTicketId = ticketId?.toString(),
            venueId = venueId,
            source = source,
        )

    private fun productionStatusPayloadWithRawTicketId(
        rawTicketId: String?,
        venueId: Long,
        source: String,
    ): String =
        buildString {
            append("{\"actorUserId\":")
            append(AUDIT_ACTOR_USER_ID)
            if (rawTicketId != null) {
                append(",\"ticketId\":")
                append(rawTicketId)
            }
            append(",\"venueId\":")
            append(venueId)
            append(",\"oldStatus\":\"WAITING_USER\"")
            append(",\"newStatus\":\"RESOLVED\"")
            append(",\"source\":\"")
            append(source)
            append("\"}")
        }

    private fun insertReturningId(
        connection: Connection,
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit,
    ): Long =
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            bind(statement)
            assertEquals(1, statement.executeUpdate())
            statement.generatedKeys.use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getLong(1)
            }
        }

    private fun loadThreads(connection: Connection): List<ThreadRow> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT
                    id,
                    venue_id,
                    guest_user_id,
                    category,
                    status,
                    booking_id,
                    title,
                    last_message_at,
                    created_at,
                    updated_at,
                    thread_type,
                    assignee_scope,
                    created_source
                FROM support_threads
                ORDER BY id
                """.trimIndent(),
            ).use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            ThreadRow(
                                id = resultSet.getLong("id"),
                                venueId = resultSet.nullableLong("venue_id"),
                                guestUserId = resultSet.getLong("guest_user_id"),
                                category = resultSet.getString("category"),
                                status = resultSet.getString("status"),
                                bookingId = resultSet.nullableLong("booking_id"),
                                title = resultSet.getString("title"),
                                lastMessageAt = resultSet.instant("last_message_at"),
                                createdAt = resultSet.getTimestamp("created_at").toInstant(),
                                updatedAt = resultSet.getTimestamp("updated_at").toInstant(),
                                threadType = resultSet.getString("thread_type"),
                                assigneeScope = resultSet.getString("assignee_scope"),
                                createdSource = resultSet.getString("created_source"),
                            ),
                        )
                    }
                }
            }
        }

    private fun loadMessages(connection: Connection): List<MessageRow> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT
                    id,
                    thread_id,
                    author_user_id,
                    author_role,
                    source,
                    text,
                    telegram_message_id,
                    created_at
                FROM support_messages
                ORDER BY id
                """.trimIndent(),
            ).use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            MessageRow(
                                id = resultSet.getLong("id"),
                                threadId = resultSet.getLong("thread_id"),
                                authorUserId = resultSet.nullableLong("author_user_id"),
                                authorRole = resultSet.getString("author_role"),
                                source = resultSet.getString("source"),
                                text = resultSet.getString("text"),
                                telegramMessageId = resultSet.nullableLong("telegram_message_id"),
                                createdAt = resultSet.getTimestamp("created_at").toInstant(),
                            ),
                        )
                    }
                }
            }
        }

    private fun loadReads(connection: Connection): List<ReadRow> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT thread_id, user_id, last_read_at
                FROM support_thread_reads
                ORDER BY thread_id, user_id
                """.trimIndent(),
            ).use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            ReadRow(
                                threadId = resultSet.getLong("thread_id"),
                                userId = resultSet.getLong("user_id"),
                                lastReadAt = resultSet.getTimestamp("last_read_at").toInstant(),
                            ),
                        )
                    }
                }
            }
        }

    private fun loadAudits(connection: Connection): List<AuditRow> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT
                    id,
                    actor_user_id,
                    action,
                    entity_type,
                    entity_id,
                    payload_json,
                    target_user_id,
                    created_at
                FROM audit_log
                ORDER BY id
                """.trimIndent(),
            ).use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            AuditRow(
                                id = resultSet.getLong("id"),
                                actorUserId = resultSet.getLong("actor_user_id"),
                                action = resultSet.getString("action"),
                                entityType = resultSet.getString("entity_type"),
                                entityId = resultSet.nullableLong("entity_id"),
                                payloadJson = resultSet.getString("payload_json"),
                                targetUserId = resultSet.nullableLong("target_user_id"),
                                createdAt = resultSet.getTimestamp("created_at").toInstant(),
                            ),
                        )
                    }
                }
            }
        }

    private fun loadDomainSnapshot(connection: Connection): DomainSnapshot =
        DomainSnapshot(
            threads = loadThreads(connection),
            messages = loadMessages(connection),
            reads = loadReads(connection),
            audits = loadAudits(connection),
        )

    private fun loadReferenceGuardSnapshot(
        connection: Connection,
        referenceRows: List<String>,
    ): ReferenceGuardSnapshot =
        ReferenceGuardSnapshot(
            domain = loadDomainSnapshot(connection),
            referenceRows = referenceRows,
            constraints =
                loadStringRows(
                    connection,
                    """
                    WITH current_relations AS (
                        SELECT relation_table.oid AS relation_oid
                        FROM pg_catalog.pg_class relation_table
                        JOIN pg_catalog.pg_namespace relation_schema
                          ON relation_schema.oid = relation_table.relnamespace
                        WHERE relation_schema.nspname = CURRENT_SCHEMA()
                          AND relation_table.relname IN (
                              'support_threads',
                              'support_messages',
                              'support_thread_reads'
                          )
                    ), target_relation AS (
                        SELECT relation_oid
                        FROM current_relations
                        WHERE relation_oid = 'support_threads'::REGCLASS
                    )
                    SELECT
                        constraint_row.oid::TEXT,
                        constraint_row.conname,
                        constraint_row.contype::TEXT,
                        constraint_row.conrelid::TEXT,
                        source_table.relnamespace::TEXT,
                        source_schema.nspname,
                        source_table.relname,
                        constraint_row.confrelid::TEXT,
                        target_table.relnamespace::TEXT,
                        target_schema.nspname,
                        target_table.relname,
                        constraint_row.conkey::TEXT,
                        constraint_row.confkey::TEXT,
                        constraint_row.confupdtype::TEXT,
                        constraint_row.confdeltype::TEXT,
                        constraint_row.confmatchtype::TEXT,
                        constraint_row.convalidated::TEXT,
                        constraint_row.condeferrable::TEXT,
                        constraint_row.condeferred::TEXT,
                        pg_catalog.pg_get_constraintdef(constraint_row.oid, TRUE)
                    FROM pg_catalog.pg_constraint constraint_row
                    LEFT JOIN pg_catalog.pg_class source_table
                      ON source_table.oid = constraint_row.conrelid
                    LEFT JOIN pg_catalog.pg_namespace source_schema
                      ON source_schema.oid = source_table.relnamespace
                    LEFT JOIN pg_catalog.pg_class target_table
                      ON target_table.oid = constraint_row.confrelid
                    LEFT JOIN pg_catalog.pg_namespace target_schema
                      ON target_schema.oid = target_table.relnamespace
                    WHERE constraint_row.conrelid IN (
                        SELECT relation_oid FROM current_relations
                    )
                       OR constraint_row.confrelid = (
                           SELECT relation_oid FROM target_relation
                       )
                    ORDER BY constraint_row.oid
                    """.trimIndent(),
                ),
            indexes =
                loadStringRows(
                    connection,
                    """
                    SELECT
                        index_row.indexrelid::TEXT,
                        index_schema.oid::TEXT,
                        index_schema.nspname,
                        index_table.relname,
                        table_row.oid::TEXT,
                        table_schema.oid::TEXT,
                        table_schema.nspname,
                        table_row.relname,
                        index_row.indisunique::TEXT,
                        index_row.indisvalid::TEXT,
                        index_row.indisready::TEXT,
                        index_row.indkey::TEXT,
                        pg_catalog.pg_get_indexdef(index_row.indexrelid)
                    FROM pg_catalog.pg_index index_row
                    JOIN pg_catalog.pg_class index_table
                      ON index_table.oid = index_row.indexrelid
                    JOIN pg_catalog.pg_namespace index_schema
                      ON index_schema.oid = index_table.relnamespace
                    JOIN pg_catalog.pg_class table_row
                      ON table_row.oid = index_row.indrelid
                    JOIN pg_catalog.pg_namespace table_schema
                      ON table_schema.oid = table_row.relnamespace
                    WHERE table_schema.nspname = CURRENT_SCHEMA()
                      AND table_row.relname IN (
                          'support_threads',
                          'support_messages',
                          'support_thread_reads'
                      )
                    ORDER BY index_row.indexrelid
                    """.trimIndent(),
                ),
            flywayHistory =
                loadStringRows(
                    connection,
                    """
                    SELECT
                        installed_rank::TEXT,
                        version,
                        description,
                        type,
                        script,
                        checksum::TEXT,
                        success::TEXT
                    FROM flyway_schema_history
                    ORDER BY installed_rank
                    """.trimIndent(),
                ),
        )

    private fun loadStringRows(
        connection: Connection,
        sql: String,
    ): List<List<String?>> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                val columnCount = resultSet.metaData.columnCount
                buildList {
                    while (resultSet.next()) {
                        add((1..columnCount).map(resultSet::getString))
                    }
                }
            }
        }

    private fun indexExists(
        connection: Connection,
        indexName: String,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1
                FROM pg_catalog.pg_class index_table
                JOIN pg_catalog.pg_namespace index_schema
                  ON index_schema.oid = index_table.relnamespace
                WHERE index_schema.nspname = CURRENT_SCHEMA()
                  AND index_table.relname = ?
                  AND index_table.relkind = 'i'
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, indexName)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getBoolean(1)
            }
        }

    private fun auditTicketId(audit: AuditRow): Long =
        Json.parseToJsonElement(audit.payloadJson).jsonObject.getValue("ticketId").jsonPrimitive.content.toLong()

    private fun payloadWithoutTicketId(payloadJson: String): JsonObject =
        JsonObject(Json.parseToJsonElement(payloadJson).jsonObject - "ticketId")

    private fun replaceTicketId(
        payloadJson: String,
        ticketId: Long,
    ): String {
        val fields = Json.parseToJsonElement(payloadJson).jsonObject.toMutableMap()
        fields["ticketId"] = JsonPrimitive(ticketId)
        return JsonObject(fields).toString()
    }

    private fun assertAuditEnvelopePreserved(
        before: AuditRow,
        after: AuditRow,
    ) {
        assertEquals(before.id, after.id)
        assertEquals(before.actorUserId, after.actorUserId)
        assertEquals(before.action, after.action)
        assertEquals(before.entityType, after.entityType)
        assertEquals(before.targetUserId, after.targetUserId)
        assertEquals(before.createdAt, after.createdAt)
    }

    private fun assertAuditRowsSemanticallyEqual(
        expected: List<AuditRow>,
        actual: List<AuditRow>,
    ) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (expectedAudit, actualAudit) ->
            assertEquals(expectedAudit.copy(payloadJson = ""), actualAudit.copy(payloadJson = ""))
            assertEquals(
                Json.parseToJsonElement(expectedAudit.payloadJson),
                Json.parseToJsonElement(actualAudit.payloadJson),
            )
        }
    }

    private fun migrateToPrevious(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
    ) {
        val beforeFlyway = flyway(dataSource, location, previousVersion)
        beforeFlyway.migrate()
        assertEquals(previousVersion, beforeFlyway.info().current().version.version)
    }

    private fun migrateToExpected(
        dataSource: DataSource,
        location: String,
        expectedVersion: String,
    ) {
        val migrationFlyway = flyway(dataSource, location, expectedVersion)
        val migrationResult = migrationFlyway.migrate()
        assertEquals(1, migrationResult.migrationsExecuted)
        assertEquals(expectedVersion, migrationFlyway.info().current().version.version)
    }

    private fun assertKnownReferenceInventory(connection: Connection) {
        assertEquals(KNOWN_REFERENCES, loadReferenceInventory(connection))
    }

    private fun loadReferenceInventory(connection: Connection): List<ReferenceRow> {
        val metadata = connection.metaData
        return metadata.getExportedKeys(
            connection.catalog,
            metadataIdentifier(metadata, connection.schema),
            metadataIdentifier(metadata, "support_threads"),
        ).use { references ->
            buildList {
                while (references.next()) {
                    if (references.getString("PKCOLUMN_NAME").equals("id", ignoreCase = true)) {
                        add(
                            ReferenceRow(
                                tableName = references.getString("FKTABLE_NAME").lowercase(),
                                columnName = references.getString("FKCOLUMN_NAME").lowercase(),
                            ),
                        )
                    }
                }
            }.sortedWith(compareBy(ReferenceRow::tableName, ReferenceRow::columnName))
        }
    }

    private fun dropBookingForeignKey(connection: Connection) {
        val metadata = connection.metaData
        val constraints =
            metadata.getImportedKeys(
                connection.catalog,
                metadataIdentifier(metadata, connection.schema),
                metadataIdentifier(metadata, "support_threads"),
            ).use { references ->
                buildList {
                    while (references.next()) {
                        if (
                            references.getString("PKTABLE_NAME").equals("bookings", ignoreCase = true) &&
                            references.getString("FKCOLUMN_NAME").equals("booking_id", ignoreCase = true)
                        ) {
                            add(references.getString("FK_NAME"))
                        }
                    }
                }.distinct()
            }
        assertEquals(1, constraints.size)
        val quotedConstraint = "\"${constraints.single().replace("\"", "\"\"")}\""
        connection.createStatement().use { statement ->
            statement.execute("ALTER TABLE support_threads DROP CONSTRAINT $quotedConstraint")
        }
    }

    private fun assertUniqueInvariant(
        connection: Connection,
        fixture: SafeFixture,
        usesGeneratedBookingKey: Boolean,
    ) {
        assertUniqueIndex(connection, usesGeneratedBookingKey)
        if (usesGeneratedBookingKey) {
            assertEquals(fixture.bookingId, loadGeneratedBookingKey(connection, fixture.survivorId))
            assertNull(loadGeneratedBookingKey(connection, fixture.supportTicketId))
        } else {
            assertFalse(columnExists(connection, "booking_thread_booking_key"))
        }

        val uniqueFailure =
            assertFailsWith<SQLException> {
                insertThread(
                    connection = connection,
                    venueId = fixture.venueId,
                    guestUserId = GUEST_USER_ID,
                    bookingId = fixture.bookingId,
                    category = "BOOKING",
                    status = "IN_PROGRESS",
                    title = "must-be-rejected",
                    threadType = "BOOKING_THREAD",
                    assigneeScope = "VENUE",
                    createdSource = "BOOKING_FLOW",
                    lastMessageAt = null,
                    createdAt = DUPLICATE_TWO_CREATED_AT,
                    updatedAt = LATEST_UPDATED_AT,
                )
            }
        assertTrue(uniqueFailure.sqlState?.startsWith("23") == true)
        assertEquals(
            1,
            loadThreads(connection).count {
                it.threadType == "BOOKING_THREAD" && it.bookingId == fixture.bookingId
            },
        )
    }

    private fun assertUniqueIndex(
        connection: Connection,
        usesGeneratedBookingKey: Boolean,
    ) {
        val metadata = connection.metaData
        val columns = mutableListOf<String>()
        metadata.getIndexInfo(
            connection.catalog,
            metadataIdentifier(metadata, connection.schema),
            metadataIdentifier(metadata, "support_threads"),
            false,
            false,
        ).use { indexes ->
            while (indexes.next()) {
                if (indexes.getString("INDEX_NAME").equals(UNIQUE_INDEX, ignoreCase = true)) {
                    assertFalse(indexes.getBoolean("NON_UNIQUE"))
                    columns += indexes.getString("COLUMN_NAME").lowercase()
                }
            }
        }
        assertEquals(
            listOf(if (usesGeneratedBookingKey) "booking_thread_booking_key" else "booking_id"),
            columns,
        )
    }

    private fun loadGeneratedBookingKey(
        connection: Connection,
        threadId: Long,
    ): Long? =
        connection.prepareStatement(
            "SELECT booking_thread_booking_key FROM support_threads WHERE id = ?",
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.nullableLong("booking_thread_booking_key")
            }
        }

    private fun columnExists(
        connection: Connection,
        columnName: String,
    ): Boolean {
        val metadata = connection.metaData
        return metadata.getColumns(
            connection.catalog,
            metadataIdentifier(metadata, connection.schema),
            metadataIdentifier(metadata, "support_threads"),
            metadataIdentifier(metadata, columnName),
        ).use(ResultSet::next)
    }

    private fun flyway(
        dataSource: DataSource,
        location: String,
        targetVersion: String,
    ): Flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target(targetVersion)
            .load()

    private fun metadataIdentifier(
        metadata: DatabaseMetaData,
        identifier: String,
    ): String =
        when {
            metadata.storesUpperCaseIdentifiers() -> identifier.uppercase()
            metadata.storesLowerCaseIdentifiers() -> identifier.lowercase()
            else -> identifier
        }

    private fun ResultSet.nullableLong(column: String): Long? = getLong(column).takeUnless { wasNull() }

    private fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()

    data class ReferenceGuardFixture(
        val duplicateThreadId: Long,
    )

    private data class SafeFixture(
        val venueId: Long,
        val bookingId: Long,
        val survivorId: Long,
        val duplicateOneId: Long,
        val duplicateTwoId: Long,
        val supportTicketId: Long,
        val supportTicketBeforeMigration: ThreadRow,
        val bookingAuditIds: List<Long>,
        val expectedMergedReads: Map<Long, Instant>,
        val beforeMigration: DomainSnapshot,
    ) {
        val bookingThreadIds: Set<Long> = setOf(survivorId, duplicateOneId, duplicateTwoId)
    }

    private enum class ReadFixture {
        NONE,
        IDENTICAL,
        PARTIAL,
        DIFFERENT_TIMESTAMPS,
    }

    private data class ReferenceRow(
        val tableName: String,
        val columnName: String,
    )

    private data class DomainSnapshot(
        val threads: List<ThreadRow>,
        val messages: List<MessageRow>,
        val reads: List<ReadRow>,
        val audits: List<AuditRow>,
    )

    private data class ReferenceGuardSnapshot(
        val domain: DomainSnapshot,
        val referenceRows: List<String>,
        val constraints: List<List<String?>>,
        val indexes: List<List<String?>>,
        val flywayHistory: List<List<String?>>,
    )

    private data class ThreadRow(
        val id: Long,
        val venueId: Long?,
        val guestUserId: Long,
        val category: String,
        val status: String,
        val bookingId: Long?,
        val title: String,
        val lastMessageAt: Instant?,
        val createdAt: Instant,
        val updatedAt: Instant,
        val threadType: String,
        val assigneeScope: String,
        val createdSource: String,
    )

    private data class MessageRow(
        val id: Long,
        val threadId: Long,
        val authorUserId: Long?,
        val authorRole: String,
        val source: String,
        val text: String,
        val telegramMessageId: Long?,
        val createdAt: Instant,
    )

    private data class ReadRow(
        val threadId: Long,
        val userId: Long,
        val lastReadAt: Instant,
    )

    private data class AuditRow(
        val id: Long,
        val actorUserId: Long,
        val action: String,
        val entityType: String,
        val entityId: Long?,
        val payloadJson: String,
        val targetUserId: Long?,
        val createdAt: Instant,
    )

    data class MigrationConcurrencyFixture(
        val bookingId: Long,
        val survivorThreadId: Long,
        val duplicateThreadId: Long,
        val readerUserId: Long,
    )

    private const val GUEST_USER_ID = 9_410_001L
    private const val FOREIGN_GUEST_USER_ID = 9_410_002L
    private const val COMMON_READER_USER_ID = 9_410_003L
    private const val PARTIAL_READER_USER_ID = 9_410_004L
    private const val TICKET_READER_USER_ID = 9_410_005L
    private const val AUDIT_ACTOR_USER_ID = 9_410_006L
    private const val SECOND_READER_USER_ID = 9_410_007L
    private const val VENUE_AUTHOR_USER_ID = 9_410_008L
    private const val MISSING_BOOKING_ID = 9_410_999_999L
    private const val UNIQUE_INDEX = "uq_support_threads_booking_thread_booking_id"
    private const val SUPPORT_TICKET_STATUS_CHANGED = "SUPPORT_TICKET_STATUS_CHANGED"

    private val KNOWN_REFERENCES =
        listOf(
            ReferenceRow("support_messages", "thread_id"),
            ReferenceRow("support_thread_reads", "thread_id"),
        )

    private val BOOKING_DISPLAY_DATE = LocalDate.parse("2026-08-20")
    private val BOOKING_SCHEDULED_AT = Instant.parse("2026-08-20T18:00:00Z")
    private val STALE_LAST_MESSAGE_AT = Instant.parse("2026-08-15T09:00:00Z")
    private val CLEAN_CREATED_AT = Instant.parse("2026-08-15T08:00:00Z")
    private val CLEAN_UPDATED_AT = Instant.parse("2026-08-15T08:05:00Z")
    private val EARLIEST_CREATED_AT = Instant.parse("2026-08-15T09:55:00Z")
    private val SURVIVOR_CREATED_AT = Instant.parse("2026-08-15T10:05:00Z")
    private val SURVIVOR_UPDATED_AT = Instant.parse("2026-08-15T10:05:00Z")
    private val DUPLICATE_ONE_CREATED_AT = EARLIEST_CREATED_AT
    private val DUPLICATE_ONE_UPDATED_AT = Instant.parse("2026-08-15T10:30:00Z")
    private val LATEST_UPDATED_AT = DUPLICATE_ONE_UPDATED_AT
    private val DUPLICATE_TWO_CREATED_AT = Instant.parse("2026-08-15T10:02:00Z")
    private val DUPLICATE_TWO_UPDATED_AT = Instant.parse("2026-08-15T10:20:00Z")
    private val EARLIER_STORED_LAST_MESSAGE_AT = Instant.parse("2026-08-15T10:35:00Z")
    private val LATEST_STORED_LAST_MESSAGE_AT = Instant.parse("2026-08-15T10:40:00Z")
    private val SUPPORT_TICKET_CREATED_AT = Instant.parse("2026-08-15T10:03:00Z")
    private val SUPPORT_TICKET_UPDATED_AT = Instant.parse("2026-08-15T10:04:00Z")
    private val MESSAGE_ONE_AT = Instant.parse("2026-08-15T10:07:00Z")
    private val MESSAGE_TWO_AT = Instant.parse("2026-08-15T10:25:00Z")
    private val MESSAGE_THREE_AT = Instant.parse("2026-08-15T10:15:00Z")
    private val SUPPORT_TICKET_MESSAGE_AT = Instant.parse("2026-08-15T10:06:00Z")
    private val COMMON_READ_AT = Instant.parse("2026-08-15T10:11:00Z")
    private val DIFFERENT_READ_AT = Instant.parse("2026-08-15T10:12:00Z")
    private val SECOND_READ_AT = Instant.parse("2026-08-15T10:13:00Z")
    private val PARTIAL_READ_AT = Instant.parse("2026-08-15T10:14:00Z")
    private val SUPPORT_TICKET_READ_AT = Instant.parse("2026-08-15T10:16:00Z")
    private val AUDIT_ONE_AT = Instant.parse("2026-08-15T10:21:00Z")
    private val AUDIT_TWO_AT = Instant.parse("2026-08-15T10:22:00Z")
    private val AUDIT_THREE_AT = Instant.parse("2026-08-15T10:23:00Z")
    private val AUDIT_FOUR_AT = Instant.parse("2026-08-15T10:24:00Z")
    private val AUDIT_UNRELATED_AT = Instant.parse("2026-08-15T10:26:00Z")
}
