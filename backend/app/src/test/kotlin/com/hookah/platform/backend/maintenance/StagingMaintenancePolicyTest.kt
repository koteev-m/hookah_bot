package com.hookah.platform.backend.maintenance

import com.hookah.platform.backend.telegram.CallbackQuery
import com.hookah.platform.backend.telegram.Chat
import com.hookah.platform.backend.telegram.Message
import com.hookah.platform.backend.telegram.TelegramUpdate
import com.hookah.platform.backend.telegram.User
import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StagingMaintenancePolicyTest {
    @Test
    fun `off is the default and ignores maintenance lists`() {
        val defaultPolicy = StagingMaintenancePolicy.from(MapApplicationConfig(), "staging")
        val ignoredLists =
            StagingMaintenancePolicy.from(
                MapApplicationConfig(
                    "staging.maintenance.mode" to "OFF",
                    "staging.maintenance.allowedUserIds" to "not-an-id",
                    "staging.maintenance.allowedChatIds" to "also-not-an-id",
                ),
                "staging",
            )

        listOf(defaultPolicy, ignoredLists).forEach { policy ->
            assertFalse(policy.active)
            assertTrue(policy.allowsAutonomousWrites)
            assertTrue(policy.allowsMiniAppUser(987654321L))
            assertTrue(policy.allowsOutboundChat(987654321L))
            assertEquals(
                StagingMaintenancePolicy.InboundDecision.Allowed,
                policy.evaluateInbound(privateMessage(987654321L)),
            )
        }
    }

    @Test
    fun `v126 smoke admits only exact reviewed private and group identities`() {
        val policy = activePolicy()

        assertEquals(
            StagingMaintenancePolicy.InboundDecision.Allowed,
            policy.evaluateInbound(privateMessage(ALLOWED_USER_ID)),
        )
        assertEquals(
            StagingMaintenancePolicy.InboundDecision.Allowed,
            policy.evaluateInbound(groupCallback(ALLOWED_USER_ID, ALLOWED_GROUP_ID)),
        )
        assertDenied(
            policy.evaluateInbound(privateMessage(DENIED_USER_ID)),
            StagingMaintenancePolicy.InboundDenialReason.ACTOR_NOT_ALLOWED,
        )
        assertDenied(
            policy.evaluateInbound(groupCallback(ALLOWED_USER_ID, DENIED_GROUP_ID)),
            StagingMaintenancePolicy.InboundDenialReason.CHAT_NOT_ALLOWED,
        )

        assertTrue(policy.allowsMiniAppUser(ALLOWED_USER_ID))
        assertFalse(policy.allowsMiniAppUser(DENIED_USER_ID))
        assertTrue(policy.allowsOutboundChat(ALLOWED_USER_ID))
        assertTrue(policy.allowsOutboundChat(ALLOWED_GROUP_ID))
        assertFalse(policy.allowsOutboundChat(DENIED_USER_ID))
        assertFalse(policy.allowsOutboundChat(DENIED_GROUP_ID))
        assertTrue(policy.allowsChatMemberLookup(ALLOWED_GROUP_ID, ALLOWED_USER_ID))
        assertFalse(policy.allowsChatMemberLookup(ALLOWED_GROUP_ID, DENIED_USER_ID))
        assertFalse(policy.allowsAutonomousWrites)
        assertFalse(
            policy.allowsBotGlobalOperation(
                StagingMaintenancePolicy.BotGlobalOperation.COMMAND_CONFIGURATION,
            ),
        )
        assertTrue(
            policy.allowsBotGlobalOperation(
                StagingMaintenancePolicy.BotGlobalOperation.GET_UPDATES,
            ),
        )
    }

    @Test
    fun `v126 smoke configuration fails closed without exposing restricted values`() {
        val invalidConfigurations =
            listOf(
                emptyMap(),
                mapOf(
                    "staging.maintenance.allowedUserIds" to ALLOWED_USER_ID.toString(),
                ),
                mapOf(
                    "staging.maintenance.allowedUserIds" to ALLOWED_USER_ID.toString(),
                    "staging.maintenance.allowedChatIds" to ALLOWED_GROUP_ID.toString(),
                ),
                mapOf(
                    "staging.maintenance.allowedUserIds" to "$ALLOWED_USER_ID,$ALLOWED_USER_ID",
                    "staging.maintenance.allowedChatIds" to ALLOWED_USER_ID.toString(),
                ),
                mapOf(
                    "staging.maintenance.allowedUserIds" to "0",
                    "staging.maintenance.allowedChatIds" to "0",
                ),
                mapOf(
                    "staging.maintenance.allowedUserIds" to " $ALLOWED_USER_ID",
                    "staging.maintenance.allowedChatIds" to ALLOWED_USER_ID.toString(),
                ),
            )

        invalidConfigurations.forEach { values ->
            val error =
                assertFailsWith<IllegalStateException> {
                    StagingMaintenancePolicy.from(
                        MapApplicationConfig(
                            "staging.maintenance.mode" to "V126_SMOKE",
                            *values.map { (key, value) -> key to value }.toTypedArray(),
                        ),
                        "staging",
                    )
                }
            assertTrue(error.message.orEmpty().contains("staging.maintenance."))
            assertFalse(error.message.orEmpty().contains(ALLOWED_USER_ID.toString()))
            assertFalse(error.message.orEmpty().contains(ALLOWED_GROUP_ID.toString()))
        }
    }

    @Test
    fun `v126 smoke is rejected outside staging and test`() {
        val error =
            assertFailsWith<IllegalStateException> {
                StagingMaintenancePolicy.from(activeConfig(), "production")
            }

        assertTrue(error.message.orEmpty().contains("restricted to staging"))
        assertFalse(error.message.orEmpty().contains(ALLOWED_USER_ID.toString()))
        assertFalse(error.message.orEmpty().contains(ALLOWED_GROUP_ID.toString()))
    }

    private fun activePolicy(): StagingMaintenancePolicy = StagingMaintenancePolicy.from(activeConfig(), "staging")

    private fun activeConfig(): MapApplicationConfig =
        MapApplicationConfig(
            "staging.maintenance.mode" to "V126_SMOKE",
            "staging.maintenance.allowedUserIds" to ALLOWED_USER_ID.toString(),
            "staging.maintenance.allowedChatIds" to "$ALLOWED_USER_ID,$ALLOWED_GROUP_ID",
        )

    private fun privateMessage(actorId: Long): TelegramUpdate =
        TelegramUpdate(
            updateId = 1L,
            message =
                Message(
                    messageId = 1L,
                    chat = Chat(id = actorId, type = "private"),
                    fromUser = User(id = actorId),
                    text = "/start",
                ),
        )

    private fun groupCallback(
        actorId: Long,
        chatId: Long,
    ): TelegramUpdate =
        TelegramUpdate(
            updateId = 2L,
            callbackQuery =
                CallbackQuery(
                    id = "callback",
                    from = User(id = actorId),
                    message = Message(messageId = 2L, chat = Chat(id = chatId, type = "supergroup")),
                    data = "sc_call_ack:1:2",
                ),
        )

    private fun assertDenied(
        decision: StagingMaintenancePolicy.InboundDecision,
        reason: StagingMaintenancePolicy.InboundDenialReason,
    ) {
        assertEquals(reason, assertIs<StagingMaintenancePolicy.InboundDecision.Denied>(decision).reason)
    }

    private companion object {
        const val ALLOWED_USER_ID = 711111111111111111L
        const val DENIED_USER_ID = 722222222222222222L
        const val ALLOWED_GROUP_ID = -733333333333333333L
        const val DENIED_GROUP_ID = -744444444444444444L
    }
}
