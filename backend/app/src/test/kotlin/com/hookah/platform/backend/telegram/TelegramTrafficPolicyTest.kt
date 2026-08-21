package com.hookah.platform.backend.telegram

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TelegramTrafficPolicyTest {
    private val policy =
        TelegramTrafficPolicy.from(
            MapApplicationConfig(
                "telegram.trafficPolicy" to "ALLOWLIST",
                "telegram.allowedUserIds" to "101,202",
                "telegram.allowedChatIds" to "101,202,-100500",
            ),
            "staging",
        )

    @Test
    fun `private update requires allowed matching actor and chat`() {
        assertAllowed(messageUpdate(actorId = 101, chatId = 101, chatType = "private"))
        assertDenied(
            messageUpdate(actorId = 303, chatId = 303, chatType = "private"),
            TelegramTrafficPolicy.InboundDenialReason.ACTOR_NOT_ALLOWED,
        )
        assertDenied(
            messageUpdate(actorId = 101, chatId = 202, chatType = "private"),
            TelegramTrafficPolicy.InboundDenialReason.PRIVATE_ACTOR_CHAT_MISMATCH,
        )
        assertDenied(
            messageUpdate(actorId = 101, chatId = -100500, chatType = "private"),
            TelegramTrafficPolicy.InboundDenialReason.INVALID_CHAT,
        )
    }

    @Test
    fun `group update requires allowed actor and exact negative chat`() {
        assertAllowed(messageUpdate(actorId = 101, chatId = -100500, chatType = "group"))
        assertAllowed(messageUpdate(actorId = 202, chatId = -100500, chatType = "supergroup"))
        assertDenied(
            messageUpdate(actorId = 303, chatId = -100500, chatType = "group"),
            TelegramTrafficPolicy.InboundDenialReason.ACTOR_NOT_ALLOWED,
        )
        assertDenied(
            messageUpdate(actorId = 101, chatId = -100501, chatType = "supergroup"),
            TelegramTrafficPolicy.InboundDenialReason.CHAT_NOT_ALLOWED,
        )
        assertDenied(
            messageUpdate(actorId = 101, chatId = 101, chatType = "group"),
            TelegramTrafficPolicy.InboundDenialReason.INVALID_CHAT,
        )
    }

    @Test
    fun `callback requires allowed actor and message chat`() {
        assertAllowed(callbackUpdate(actorId = 101, chatId = -100500, chatType = "supergroup"))
        assertDenied(
            callbackUpdate(actorId = 303, chatId = -100500, chatType = "supergroup"),
            TelegramTrafficPolicy.InboundDenialReason.ACTOR_NOT_ALLOWED,
        )
        assertDenied(
            callbackUpdate(actorId = 101, chatId = -100501, chatType = "supergroup"),
            TelegramTrafficPolicy.InboundDenialReason.CHAT_NOT_ALLOWED,
        )
        assertDenied(
            TelegramUpdate(
                updateId = 1,
                callbackQuery = CallbackQuery(id = "callback", from = User(id = 101), message = null),
            ),
            TelegramTrafficPolicy.InboundDenialReason.MISSING_CHAT,
        )
    }

    @Test
    fun `missing or ambiguous inbound identity is denied`() {
        val missingActor =
            TelegramUpdate(
                updateId = 1,
                message = Message(messageId = 2, chat = Chat(id = 101, type = "private")),
            )
        assertDenied(missingActor, TelegramTrafficPolicy.InboundDenialReason.MISSING_ACTOR)
        assertDenied(TelegramUpdate(updateId = 2), TelegramTrafficPolicy.InboundDenialReason.UNSUPPORTED_UPDATE)

        val both =
            messageUpdate(actorId = 101, chatId = 101, chatType = "private").copy(
                callbackQuery =
                    CallbackQuery(
                        id = "callback",
                        from = User(id = 101),
                        message = Message(messageId = 3, chat = Chat(id = 101, type = "private")),
                    ),
            )
        assertDenied(both, TelegramTrafficPolicy.InboundDenialReason.UNSUPPORTED_UPDATE)
        assertDenied(
            messageUpdate(actorId = 101, chatId = -100500, chatType = "channel"),
            TelegramTrafficPolicy.InboundDenialReason.UNSUPPORTED_CHAT_TYPE,
        )
    }

    @Test
    fun `mini app outbound and chat member decisions share the same policy`() {
        assertTrue(policy.allowsMiniAppUser(101))
        assertFalse(policy.allowsMiniAppUser(303))
        assertTrue(policy.allowsOutboundChat(101))
        assertTrue(policy.allowsOutboundChat(-100500))
        assertFalse(policy.allowsOutboundChat(303))
        assertFalse(policy.allowsOutboundChat(0))
        assertTrue(policy.allowsChatMemberLookup(-100500, 202))
        assertTrue(policy.allowsChatMemberLookup(101, 101))
        assertFalse(policy.allowsChatMemberLookup(101, 202))
        assertFalse(policy.allowsChatMemberLookup(-100500, 303))

        val unrestricted = TelegramTrafficPolicy.unrestricted()
        assertFalse(unrestricted.allowsMiniAppUser(0))
        assertFalse(unrestricted.allowsOutboundChat(0))
        assertFalse(unrestricted.allowsChatMemberLookup(0, 101))
        assertFalse(unrestricted.allowsChatMemberLookup(-100500, 0))
    }

    private fun assertAllowed(update: TelegramUpdate) {
        assertEquals(TelegramTrafficPolicy.InboundDecision.Allowed, policy.evaluateInbound(update))
    }

    private fun assertDenied(
        update: TelegramUpdate,
        expectedReason: TelegramTrafficPolicy.InboundDenialReason,
    ) {
        val decision = assertIs<TelegramTrafficPolicy.InboundDecision.Denied>(policy.evaluateInbound(update))
        assertEquals(expectedReason, decision.reason)
    }

    private fun messageUpdate(
        actorId: Long,
        chatId: Long,
        chatType: String,
    ): TelegramUpdate =
        TelegramUpdate(
            updateId = 1,
            message =
                Message(
                    messageId = 2,
                    chat = Chat(id = chatId, type = chatType),
                    fromUser = User(id = actorId),
                ),
        )

    private fun callbackUpdate(
        actorId: Long,
        chatId: Long,
        chatType: String,
    ): TelegramUpdate =
        TelegramUpdate(
            updateId = 1,
            callbackQuery =
                CallbackQuery(
                    id = "callback",
                    from = User(id = actorId),
                    message = Message(messageId = 2, chat = Chat(id = chatId, type = chatType)),
                ),
        )
}
