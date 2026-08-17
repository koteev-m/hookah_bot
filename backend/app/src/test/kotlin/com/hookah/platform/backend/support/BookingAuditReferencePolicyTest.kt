package com.hookah.platform.backend.support

import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookingAuditReferencePolicyTest {
    @Test
    fun `H2 policy decodes and recursively classifies audit reference keys`() {
        val cases =
            listOf(
                """{"ticketId":1}""" to false,
                """{"metadata":{"conversationThreadId":1}}""" to true,
                "{\"metadata\":{\"conversation\\u0054hreadId\":1}}" to true,
                """{"metadata":{"thread_id":1}}""" to true,
                """{"metadata":{"ticketIds":[1]}}""" to true,
                """{"metadata":{"conversationThreadId":"1"}}""" to true,
                """{"metadata":{"conversationStatus":"OPEN"}}""" to false,
            )

        cases.forEach { (payload, expected) ->
            assertEquals(expected, BookingAuditReferencePolicy.hasUnknownThreadReferenceKey(payload), payload)
        }
    }

    @Test
    fun `H2 policy extracts one semantic top level ticket id independent of order formatting and escaping`() {
        val cases =
            listOf(
                """{"actorUserId":7,"ticketId":41,"source":"GUEST_MINIAPP"}""",
                """{"ticketId":41,"actorUserId":7,"source":"GUEST_MINIAPP"}""",
                """
                {
                  "source": "GUEST_MINIAPP",
                  "metadata": {"safe": true},
                  "ticketId" : 41,
                  "actorUserId": 7
                }
                """.trimIndent(),
                """{"actorUserId":7,"\u0074icketId":41,"source":"GUEST_MINIAPP"}""",
            )

        cases.forEach { payload ->
            assertEquals(1, BookingAuditReferencePolicy.countTopLevelTicketIds(payload), payload)
            assertEquals(41L, BookingAuditReferencePolicy.extractTopLevelTicketId(payload), payload)
        }
    }

    @Test
    fun `H2 policy keeps duplicate detection and requires one exact JSON long`() {
        val duplicate = """{"ticketId":41,"\u0074icketId":41}"""
        assertEquals(2, BookingAuditReferencePolicy.countTopLevelTicketIds(duplicate))
        assertNull(BookingAuditReferencePolicy.extractTopLevelTicketId(duplicate))

        val invalidValues =
            listOf(
                """{"actorUserId":7}""" to 0,
                """{"ticketId":"41"}""" to 1,
                """{"ticketId":41.0}""" to 1,
                """{"ticketId":4.1e1}""" to 1,
                """{"ticketId":9223372036854775808}""" to 1,
                """{"metadata":{"ticketId":41}}""" to 0,
            )
        invalidValues.forEach { (payload, count) ->
            assertEquals(count, BookingAuditReferencePolicy.countTopLevelTicketIds(payload), payload)
            assertNull(BookingAuditReferencePolicy.extractTopLevelTicketId(payload), payload)
        }

        assertEquals(-1, BookingAuditReferencePolicy.countTopLevelTicketIds("[]"))
        assertEquals(-1, BookingAuditReferencePolicy.countTopLevelTicketIds("not-json"))
    }

    @Test
    fun `H2 policy remaps only the semantic top level ticket id and validates the old reference`() {
        val payload =
            """
            {
              "source": "GUEST_MINIAPP",
              "metadata": {"conversationStatus": "OPEN", "note": "brace { is data"},
              "ticketId": 41,
              "actorUserId": 7,
              "venueId": 9
            }
            """.trimIndent()
        val remapped = BookingAuditReferencePolicy.remapTopLevelTicketId(payload, 41, 17)

        assertEquals(17L, BookingAuditReferencePolicy.extractTopLevelTicketId(remapped))
        assertEquals(payloadWithoutTicketId(payload), payloadWithoutTicketId(remapped))
        assertFalse(BookingAuditReferencePolicy.hasUnknownThreadReferenceKey(remapped))
        assertFailsWith<IllegalArgumentException> {
            BookingAuditReferencePolicy.remapTopLevelTicketId(payload, 40, 17)
        }
        assertFailsWith<IllegalArgumentException> {
            BookingAuditReferencePolicy.remapTopLevelTicketId(
                """{"ticketId":41,"\u0074icketId":41}""",
                41,
                17,
            )
        }
    }

    @Test
    fun `PostgreSQL recursive key predicate matches H2 policy matrix`() {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database).use { dataSource ->
            dataSource.connection.use { connection ->
                val cases =
                    listOf(
                        AuditKeyCase("ticketId", 0, false),
                        AuditKeyCase("ticketId", 1, true),
                        AuditKeyCase("conversationThreadId", 1, true),
                        AuditKeyCase("thread_id", 2, true),
                        AuditKeyCase("ticketIds", 2, true),
                        AuditKeyCase("conversationStatus", 2, false),
                    )
                connection.prepareStatement(
                    """
                    SELECT NOT (? = 0 AND ? = 'ticketId')
                       AND REGEXP_REPLACE(LOWER(NORMALIZE(?, NFKC)), '[_.[:space:]-]', '', 'g')
                           ~ '(thread|ticket|conversation).*(ids|refs|id|ref)'
                    """.trimIndent(),
                ).use { statement ->
                    cases.forEach { case ->
                        statement.setInt(1, case.depth)
                        statement.setString(2, case.key)
                        statement.setString(3, case.key)
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals(case.unknown, rows.getBoolean(1), case.toString())
                            assertFalse(rows.next())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `deployment preflight embeds the exact recursive unknown-key predicate`() {
        val runbook = Files.readString(findRunbook())

        assertTrue(runbook.contains("WITH RECURSIVE audit_nodes(audit_id, node, depth) AS"))
        assertTrue(
            runbook.contains(
                "REGEXP_REPLACE(LOWER(NORMALIZE(key, NFKC)), '[_.[:space:]-]', '', 'g') AS compact_key",
            ),
        )
        assertTrue(runbook.contains("WHERE NOT (depth = 0 AND key = 'ticketId')"))
        assertTrue(runbook.contains("compact_key ~ '(thread|ticket|conversation).*(ids|refs|id|ref)'"))
        assertTrue(runbook.contains("FROM unknown_audit_reference_keys"))
    }

    private fun findRunbook(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("docs/DEPLOYMENT_RUNBOOK.md") }
            .firstOrNull(Files::isRegularFile)
            ?: error("docs/DEPLOYMENT_RUNBOOK.md not found from test working directory")

    private fun payloadWithoutTicketId(payloadJson: String): JsonObject =
        JsonObject(Json.parseToJsonElement(payloadJson).jsonObject - "ticketId")

    private data class AuditKeyCase(
        val key: String,
        val depth: Int,
        val unknown: Boolean,
    )
}
