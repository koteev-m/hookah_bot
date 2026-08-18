package com.hookah.platform.backend.support

import com.hookah.platform.backend.test.PostgresTestEnv
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `composite expected-looking foreign key fails before domain mutation on PostgreSQL`() {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database, migrate = false).use { dataSource ->
            BookingThreadIntegrityMigrationTestSupport.assertPhysicalReferenceInventoryFailsBeforeDomainMutation(
                dataSource = dataSource,
                location = POSTGRES_MIGRATION_LOCATION,
                previousVersion = PREVIOUS_VERSION,
                expectedVersion = EXPECTED_VERSION,
                configureReferenceShape = { connection, _ ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "ALTER TABLE support_threads ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1",
                        )
                        statement.execute(
                            "ALTER TABLE support_messages ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1",
                        )
                        statement.execute(
                            """
                            ALTER TABLE support_threads
                            ADD CONSTRAINT uq_support_threads_id_tenant UNIQUE (id, tenant_id)
                            """.trimIndent(),
                        )
                        statement.execute(
                            "ALTER TABLE support_messages DROP CONSTRAINT support_messages_thread_id_fkey",
                        )
                        statement.execute(
                            """
                            ALTER TABLE support_messages
                            ADD CONSTRAINT support_messages_thread_tenant_fkey
                            FOREIGN KEY (thread_id, tenant_id)
                            REFERENCES support_threads(id, tenant_id)
                            ON DELETE CASCADE
                            """.trimIndent(),
                        )
                    }
                },
                loadReferenceRows = {
                    queryRows(
                        dataSource,
                        """
                        SELECT 'thread', id::TEXT, NULL::TEXT, tenant_id::TEXT
                        FROM support_threads
                        UNION ALL
                        SELECT 'message', id::TEXT, thread_id::TEXT, tenant_id::TEXT
                        FROM support_messages
                        ORDER BY 1, 2
                        """.trimIndent(),
                    )
                },
            )
        }
    }

    @Test
    fun `foreign key to non-id target column fails before domain mutation on PostgreSQL`() {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database, migrate = false).use { dataSource ->
            BookingThreadIntegrityMigrationTestSupport.assertPhysicalReferenceInventoryFailsBeforeDomainMutation(
                dataSource = dataSource,
                location = POSTGRES_MIGRATION_LOCATION,
                previousVersion = PREVIOUS_VERSION,
                expectedVersion = EXPECTED_VERSION,
                configureReferenceShape = { connection, fixture ->
                    connection.createStatement().use { statement ->
                        statement.execute("ALTER TABLE support_threads ADD COLUMN reference_key BIGINT")
                        statement.execute(
                            "UPDATE support_threads SET reference_key = id + $REFERENCE_KEY_OFFSET",
                        )
                        statement.execute("ALTER TABLE support_threads ALTER COLUMN reference_key SET NOT NULL")
                        statement.execute(
                            """
                            ALTER TABLE support_threads
                            ADD CONSTRAINT uq_support_threads_reference_key UNIQUE (reference_key)
                            """.trimIndent(),
                        )
                        statement.execute(
                            """
                            CREATE TABLE non_id_booking_thread_reference (
                                id BIGINT PRIMARY KEY,
                                target_reference BIGINT NOT NULL,
                                CONSTRAINT non_id_booking_thread_reference_fkey
                                    FOREIGN KEY (target_reference)
                                    REFERENCES support_threads(reference_key)
                                    ON DELETE CASCADE
                            )
                            """.trimIndent(),
                        )
                        statement.execute(
                            """
                            INSERT INTO non_id_booking_thread_reference (id, target_reference)
                            VALUES (1, ${fixture.duplicateThreadId + REFERENCE_KEY_OFFSET})
                            """.trimIndent(),
                        )
                    }
                },
                loadReferenceRows = {
                    queryRows(
                        dataSource,
                        """
                        SELECT 'thread', id::TEXT, reference_key::TEXT
                        FROM support_threads
                        UNION ALL
                        SELECT 'reference', id::TEXT, target_reference::TEXT
                        FROM non_id_booking_thread_reference
                        ORDER BY 1, 2
                        """.trimIndent(),
                    )
                },
            )
        }
    }

    @Test
    fun `external same-name relation cannot replace expected local foreign key on PostgreSQL`() {
        val database = PostgresTestEnv.createDatabase()
        val externalSchema = uniqueIdentifier("v124_same_name")
        PostgresTestEnv.createDataSource(database, migrate = false).use { dataSource ->
            try {
                BookingThreadIntegrityMigrationTestSupport.assertPhysicalReferenceInventoryFailsBeforeDomainMutation(
                    dataSource = dataSource,
                    location = POSTGRES_MIGRATION_LOCATION,
                    previousVersion = PREVIOUS_VERSION,
                    expectedVersion = EXPECTED_VERSION,
                    configureReferenceShape = { connection, fixture ->
                        connection.createStatement().use { statement ->
                            statement.execute("CREATE SCHEMA $externalSchema")
                            statement.execute(
                                "ALTER TABLE support_messages DROP CONSTRAINT support_messages_thread_id_fkey",
                            )
                            statement.execute(
                                """
                                CREATE TABLE $externalSchema.support_messages (
                                    id BIGINT PRIMARY KEY,
                                    thread_id BIGINT NOT NULL,
                                    CONSTRAINT external_support_messages_thread_id_fkey
                                        FOREIGN KEY (thread_id)
                                        REFERENCES ${database.schema}.support_threads(id)
                                        ON DELETE CASCADE
                                )
                                """.trimIndent(),
                            )
                            statement.execute(
                                """
                                INSERT INTO $externalSchema.support_messages (id, thread_id)
                                VALUES (1, ${fixture.duplicateThreadId})
                                """.trimIndent(),
                            )
                        }
                    },
                    loadReferenceRows = {
                        queryRows(
                            dataSource,
                            "SELECT id::TEXT, thread_id::TEXT FROM $externalSchema.support_messages ORDER BY id",
                        )
                    },
                )
            } finally {
                dropSchema(dataSource, externalSchema)
            }
        }
    }

    @Test
    fun `additional external foreign key fails before domain mutation on PostgreSQL`() {
        val database = PostgresTestEnv.createDatabase()
        val externalSchema = uniqueIdentifier("v124_extra_reference")
        PostgresTestEnv.createDataSource(database, migrate = false).use { dataSource ->
            try {
                BookingThreadIntegrityMigrationTestSupport.assertPhysicalReferenceInventoryFailsBeforeDomainMutation(
                    dataSource = dataSource,
                    location = POSTGRES_MIGRATION_LOCATION,
                    previousVersion = PREVIOUS_VERSION,
                    expectedVersion = EXPECTED_VERSION,
                    configureReferenceShape = { connection, fixture ->
                        connection.createStatement().use { statement ->
                            statement.execute("CREATE SCHEMA $externalSchema")
                            statement.execute(
                                """
                                CREATE TABLE $externalSchema.extra_booking_thread_reference (
                                    id BIGINT PRIMARY KEY,
                                    thread_id BIGINT NOT NULL,
                                    CONSTRAINT extra_booking_thread_reference_fkey
                                        FOREIGN KEY (thread_id)
                                        REFERENCES ${database.schema}.support_threads(id)
                                        ON DELETE CASCADE
                                )
                                """.trimIndent(),
                            )
                            statement.execute(
                                """
                                INSERT INTO $externalSchema.extra_booking_thread_reference (id, thread_id)
                                VALUES (1, ${fixture.duplicateThreadId})
                                """.trimIndent(),
                            )
                        }
                    },
                    loadReferenceRows = {
                        queryRows(
                            dataSource,
                            """
                            SELECT id::TEXT, thread_id::TEXT
                            FROM $externalSchema.extra_booking_thread_reference
                            ORDER BY id
                            """.trimIndent(),
                        )
                    },
                )
            } finally {
                dropSchema(dataSource, externalSchema)
            }
        }
    }

    @Test
    fun `cross owner inbound reference remains visible to fail closed inventory on PostgreSQL`() {
        val database = PostgresTestEnv.createDatabase()
        val targetRole = uniqueIdentifier("v124_target")
        val sourceRole = uniqueIdentifier("v124_source")
        val sourceSchema = uniqueIdentifier("v124_source_schema")
        val rolePassword = "v124-test-password"
        val adminDataSource = PostgresTestEnv.createDataSource(database, migrate = false)
        var targetRoleCreated = false
        var sourceRoleCreated = false
        var sourceSchemaCreated = false

        try {
            adminDataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE ROLE $targetRole LOGIN PASSWORD '$rolePassword'")
                    targetRoleCreated = true
                    statement.execute("ALTER SCHEMA ${database.schema} OWNER TO $targetRole")
                }
            }

            PostgresTestEnv.createDataSource(
                database.copy(user = targetRole, password = rolePassword),
                migrate = false,
            ).use { targetDataSource ->
                BookingThreadIntegrityMigrationTestSupport.assertPhysicalReferenceInventoryFailsBeforeDomainMutation(
                    dataSource = targetDataSource,
                    location = POSTGRES_MIGRATION_LOCATION,
                    previousVersion = PREVIOUS_VERSION,
                    expectedVersion = EXPECTED_VERSION,
                    configureReferenceShape = { targetConnection, fixture ->
                        adminDataSource.connection.use { connection ->
                            connection.createStatement().use { statement ->
                                statement.execute("CREATE ROLE $sourceRole NOLOGIN")
                                sourceRoleCreated = true
                                statement.execute("CREATE SCHEMA $sourceSchema AUTHORIZATION $sourceRole")
                                sourceSchemaCreated = true
                                statement.execute(
                                    """
                                    CREATE TABLE $sourceSchema.hidden_booking_thread_reference (
                                        id BIGINT PRIMARY KEY,
                                        thread_id BIGINT NOT NULL,
                                        CONSTRAINT hidden_booking_thread_reference_fk
                                            FOREIGN KEY (thread_id)
                                            REFERENCES ${database.schema}.support_threads(id)
                                            ON DELETE CASCADE
                                    )
                                    """.trimIndent(),
                                )
                                statement.execute(
                                    """
                                    INSERT INTO $sourceSchema.hidden_booking_thread_reference (id, thread_id)
                                    VALUES (1, ${fixture.duplicateThreadId})
                                    """.trimIndent(),
                                )
                                statement.execute(
                                    "ALTER TABLE $sourceSchema.hidden_booking_thread_reference OWNER TO $sourceRole",
                                )
                            }
                        }

                        assertEquals(
                            0,
                            queryCount(
                                targetConnection,
                                """
                                SELECT COUNT(*)
                                FROM information_schema.referential_constraints
                                WHERE constraint_schema = '$sourceSchema'
                                  AND constraint_name = 'hidden_booking_thread_reference_fk'
                                """.trimIndent(),
                            ),
                        )
                        assertEquals(
                            1,
                            queryCount(
                                targetConnection,
                                """
                                SELECT COUNT(*)
                                FROM pg_catalog.pg_constraint constraint_row
                                WHERE constraint_row.conname = 'hidden_booking_thread_reference_fk'
                                  AND constraint_row.confrelid =
                                      '${database.schema}.support_threads'::REGCLASS
                                """.trimIndent(),
                            ),
                        )
                    },
                    loadReferenceRows = {
                        queryRows(
                            adminDataSource,
                            """
                            SELECT id::TEXT, thread_id::TEXT
                            FROM $sourceSchema.hidden_booking_thread_reference
                            ORDER BY id
                            """.trimIndent(),
                        )
                    },
                )
            }
        } finally {
            adminDataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    if (sourceSchemaCreated) {
                        statement.execute("DROP SCHEMA $sourceSchema CASCADE")
                    }
                    if (sourceRoleCreated) {
                        statement.execute("DROP ROLE $sourceRole")
                    }
                    if (targetRoleCreated) {
                        statement.execute("REASSIGN OWNED BY $targetRole TO ${database.user}")
                        statement.execute("DROP OWNED BY $targetRole")
                        statement.execute("DROP ROLE $targetRole")
                    }
                }
            }
            adminDataSource.close()
        }
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

    private fun queryRows(
        dataSource: DataSource,
        sql: String,
    ): List<String> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { resultSet ->
                    val columnCount = resultSet.metaData.columnCount
                    buildList {
                        while (resultSet.next()) {
                            add(
                                (1..columnCount).joinToString(separator = "|") { column ->
                                    resultSet.getString(column) ?: "<NULL>"
                                },
                            )
                        }
                    }
                }
            }
        }

    private fun queryCount(
        connection: java.sql.Connection,
        sql: String,
    ): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getInt(1)
            }
        }

    private fun dropSchema(
        dataSource: DataSource,
        schema: String,
    ) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }

    private fun uniqueIdentifier(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "").lowercase()}"

    private companion object {
        const val POSTGRES_MIGRATION_LOCATION = "classpath:db/migration/postgresql"
        const val PREVIOUS_VERSION = "123"
        const val EXPECTED_VERSION = "124"
        const val REFERENCE_KEY_OFFSET = 1_000_000L
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
