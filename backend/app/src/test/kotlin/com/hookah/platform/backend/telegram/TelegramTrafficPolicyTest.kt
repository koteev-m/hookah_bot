package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeFormat
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
        assertTrue(unrestricted.allowsChatMemberLookup(202, 101))
    }

    @Test
    fun `product permits structurally valid private traffic without static identities`() {
        val product = TelegramTrafficPolicy.product()

        assertEquals(
            TelegramTrafficPolicy.InboundDecision.Allowed,
            product.evaluateInbound(messageUpdate(actorId = 303, chatId = 303, chatType = "private", text = "/start")),
        )
        assertEquals(
            TelegramTrafficPolicy.InboundDecision.Allowed,
            product.evaluateInbound(
                callbackUpdate(
                    actorId = 404,
                    chatId = 404,
                    chatType = "private",
                    data = "staff_invite_accept:opaque",
                ),
            ),
        )
        assertDenied(
            product,
            messageUpdate(actorId = 0, chatId = 1, chatType = "private"),
            TelegramTrafficPolicy.InboundDenialReason.INVALID_ACTOR,
        )
        assertDenied(
            product,
            messageUpdate(actorId = 303, chatId = 404, chatType = "private"),
            TelegramTrafficPolicy.InboundDenialReason.PRIVATE_ACTOR_CHAT_MISMATCH,
        )
        assertDenied(
            product,
            messageUpdate(actorId = 303, chatId = -303, chatType = "private"),
            TelegramTrafficPolicy.InboundDenialReason.INVALID_CHAT,
        )
        assertDenied(
            product,
            TelegramUpdate(updateId = 3),
            TelegramTrafficPolicy.InboundDenialReason.UNSUPPORTED_UPDATE,
        )
        assertDenied(
            product,
            TelegramUpdate(
                updateId = 4,
                message = Message(messageId = 4, chat = Chat(id = 303, type = "private")),
            ),
            TelegramTrafficPolicy.InboundDenialReason.MISSING_ACTOR,
        )
        assertDenied(
            product,
            TelegramUpdate(
                updateId = 5,
                callbackQuery = CallbackQuery(id = "callback", from = User(id = 303)),
            ),
            TelegramTrafficPolicy.InboundDenialReason.MISSING_CHAT,
        )
    }

    @Test
    fun `product group policy admits only link commands and supported operational callbacks`() {
        val product = TelegramTrafficPolicy.product()
        listOf(
            "/link ABCD2345",
            "/LINK@venue_bot\tWXYZ6789",
            "/unlink",
            "/link_test@venue_bot",
        ).forEach { text ->
            assertEquals(
                TelegramTrafficPolicy.InboundDecision.Allowed,
                product.evaluateInbound(
                    messageUpdate(actorId = 303, chatId = -100500, chatType = "supergroup", text = text),
                ),
            )
        }

        supportedProductGroupCallbacks.forEach { data ->
            assertEquals(
                TelegramTrafficPolicy.InboundDecision.Allowed,
                product.evaluateInbound(
                    callbackUpdate(actorId = 303, chatId = -100500, chatType = "group", data = data),
                ),
            )
        }

        listOf(
            "hello",
            "/start",
            "/linkage ABCD2345",
            "link ABCD2345",
            "/link",
            "/link ABCD2345 EXTRA",
            "/link ABCD-EFGH",
            "/link ABCD\uD83D\uDD10",
            "/link ${"A".repeat(StaffChatLinkCodeFormat.MAX_CODE_LEN + 1)}",
        ).forEach { text ->
            assertDenied(
                product,
                messageUpdate(actorId = 303, chatId = -100500, chatType = "group", text = text),
                TelegramTrafficPolicy.InboundDenialReason.UNSUPPORTED_GROUP_TRAFFIC,
            )
        }
        listOf(null, "unknown_callback", "sc_ob_a:").forEach { data ->
            assertDenied(
                product,
                callbackUpdate(actorId = 303, chatId = -100500, chatType = "supergroup", data = data),
                TelegramTrafficPolicy.InboundDenialReason.UNSUPPORTED_GROUP_TRAFFIC,
            )
        }
        assertDenied(
            product,
            messageUpdate(actorId = 303, chatId = 303, chatType = "group", text = "/link ABCD2345"),
            TelegramTrafficPolicy.InboundDenialReason.INVALID_CHAT,
        )
    }

    @Test
    fun `product exposes structural Mini App and outbound decisions for repository authorization`() {
        val product = TelegramTrafficPolicy.product()

        assertTrue(product.productMode)
        assertTrue(product.allowsMiniAppUser(303))
        assertFalse(product.allowsMiniAppUser(0))
        assertFalse(product.allowsMiniAppUser(-303))
        assertTrue(product.allowsOutboundChat(303))
        assertTrue(product.allowsOutboundChat(-100500))
        assertFalse(product.allowsOutboundChat(0))
        assertTrue(product.allowsChatMemberLookup(-100500, 303))
        assertTrue(product.allowsChatMemberLookup(303, 303))
        assertFalse(product.allowsChatMemberLookup(404, 303))
        assertFalse(product.allowsChatMemberLookup(0, 303))
        assertTrue(product.outboundClaimScope.productAuthoritative)
        assertFalse(product.outboundClaimScope.unrestricted)
        assertEquals(null, product.outboundClaimScope.eligiblePositiveRecipientIds)
    }

    private fun assertAllowed(update: TelegramUpdate) {
        assertEquals(TelegramTrafficPolicy.InboundDecision.Allowed, policy.evaluateInbound(update))
    }

    private fun assertDenied(
        update: TelegramUpdate,
        expectedReason: TelegramTrafficPolicy.InboundDenialReason,
    ) {
        assertDenied(policy, update, expectedReason)
    }

    private fun assertDenied(
        evaluatedPolicy: TelegramTrafficPolicy,
        update: TelegramUpdate,
        expectedReason: TelegramTrafficPolicy.InboundDenialReason,
    ) {
        val decision = assertIs<TelegramTrafficPolicy.InboundDecision.Denied>(evaluatedPolicy.evaluateInbound(update))
        assertEquals(expectedReason, decision.reason)
    }

    private fun messageUpdate(
        actorId: Long,
        chatId: Long,
        chatType: String,
        text: String? = null,
    ): TelegramUpdate =
        TelegramUpdate(
            updateId = 1,
            message =
                Message(
                    messageId = 2,
                    chat = Chat(id = chatId, type = chatType),
                    fromUser = User(id = actorId),
                    text = text,
                ),
        )

    private fun callbackUpdate(
        actorId: Long,
        chatId: Long,
        chatType: String,
        data: String? = null,
    ): TelegramUpdate =
        TelegramUpdate(
            updateId = 1,
            callbackQuery =
                CallbackQuery(
                    id = "callback",
                    from = User(id = actorId),
                    message = Message(messageId = 2, chat = Chat(id = chatId, type = chatType)),
                    data = data,
                ),
        )

    private companion object {
        val supportedProductGroupCallbacks =
            listOf(
                "staff_booking_confirm:10:77",
                "staff_booking_message:10:77",
                "staff_booking_seated_ask:10:77",
                "staff_booking_noshow_ask:10:77",
                "staff_booking_seated_yes:10:77",
                "staff_booking_noshow_yes:10:77",
                "staff_booking_cancel_ask:10:77",
                "staff_booking_cancel_back:10:77",
                "staff_booking_cancel_yes:10:77:reason",
                "sbc_r:10:77:reason",
                "sbc_y:10:77:reason",
                "fb_reply:92",
                "sc_ob_a:10:57",
                "sc_ob_d:10:57",
                "sc_se_a:10:501",
                "sc_se_r:10:501",
                "sc_oc_ask:a:j:1l",
                "sc_oc_back:a:j:1l",
                "sc_or:a:j:1l",
                "sc_oc_yes:a:j:1l",
                "sc_call_ack:10:6",
                "sc_call_done:10:6",
            )
    }
}
