package com.hookah.platform.backend.telegram

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TelegramProductAbuseLimiterTest {
    @Test
    fun `bounds private spam per actor and chat`() {
        var now = 1_000L
        val limiter =
            limiter(
                now = { now },
                privateLimit = TelegramProductAbuseLimiter.Limit(2, Duration.ofSeconds(10)),
            )
        val update = privateMessage(text = "/menu")

        assertIs<TelegramProductAbuseLimiter.Decision.Allowed>(limiter.evaluate(update))
        assertIs<TelegramProductAbuseLimiter.Decision.Allowed>(limiter.evaluate(update))
        assertEquals(
            TelegramProductAbuseLimiter.Decision.Denied(TelegramProductAbuseLimiter.Category.PRIVATE_TRAFFIC),
            limiter.evaluate(update),
        )

        now += Duration.ofSeconds(10).toMillis()
        assertIs<TelegramProductAbuseLimiter.Decision.Allowed>(limiter.evaluate(update))
    }

    @Test
    fun `bounds staff invite attempts independently from ordinary private traffic`() {
        val limiter =
            limiter(
                privateLimit = TelegramProductAbuseLimiter.Limit(10, Duration.ofMinutes(1)),
                inviteLimit = TelegramProductAbuseLimiter.Limit(1, Duration.ofMinutes(1)),
            )

        assertIs<TelegramProductAbuseLimiter.Decision.Allowed>(
            limiter.evaluate(privateMessage(text = "/start staff_invite_FRESHCODE")),
        )
        assertEquals(
            TelegramProductAbuseLimiter.Decision.Denied(TelegramProductAbuseLimiter.Category.STAFF_INVITE),
            limiter.evaluate(privateMessage(text = "/start staff_invite_ANOTHERCODE")),
        )
        assertIs<TelegramProductAbuseLimiter.Decision.Allowed>(limiter.evaluate(privateMessage(text = "/menu")))
    }

    @Test
    fun `same invite token is bounded across rotating private actors without storing the token as a key`() {
        val limiter =
            limiter(
                privateLimit = TelegramProductAbuseLimiter.Limit(10, Duration.ofMinutes(1)),
                inviteLimit = TelegramProductAbuseLimiter.Limit(10, Duration.ofMinutes(1)),
                inviteTokenLimit = TelegramProductAbuseLimiter.Limit(1, Duration.ofMinutes(1)),
            )

        assertIs<TelegramProductAbuseLimiter.Decision.Allowed>(
            limiter.evaluate(privateMessage("/start staff_invite_SHARED_SECRET_CODE", userId = 42)),
        )
        assertEquals(
            TelegramProductAbuseLimiter.Decision.Denied(TelegramProductAbuseLimiter.Category.STAFF_INVITE),
            limiter.evaluate(privateMessage("/start staff_invite_SHARED_SECRET_CODE", userId = 43)),
        )
        assertIs<TelegramProductAbuseLimiter.Decision.Allowed>(
            limiter.evaluate(privateMessage("/start staff_invite_DIFFERENT_CODE", userId = 44)),
        )
    }

    @Test
    fun `bounds group link attempts more tightly than other linked chat operations`() {
        val limiter =
            limiter(
                groupLimit = TelegramProductAbuseLimiter.Limit(10, Duration.ofMinutes(1)),
                groupLinkLimit = TelegramProductAbuseLimiter.Limit(1, Duration.ofMinutes(1)),
            )

        assertIs<TelegramProductAbuseLimiter.Decision.Allowed>(limiter.evaluate(groupMessage("/link ABCD234")))
        assertEquals(
            TelegramProductAbuseLimiter.Decision.Denied(TelegramProductAbuseLimiter.Category.GROUP_LINK),
            limiter.evaluate(groupMessage("/link WXYZ567")),
        )
        assertIs<TelegramProductAbuseLimiter.Decision.Allowed>(limiter.evaluate(groupMessage("/link_test")))
    }

    @Test
    fun `fails closed when the bounded identity bucket capacity is exhausted`() {
        var now = 1_000L
        val limiter =
            limiter(
                now = { now },
                privateLimit = TelegramProductAbuseLimiter.Limit(10, Duration.ofSeconds(10)),
                maxTrackedKeys = 1,
            )

        assertIs<TelegramProductAbuseLimiter.Decision.Allowed>(limiter.evaluate(privateMessage("/menu", 42)))
        assertEquals(
            TelegramProductAbuseLimiter.Decision.Denied(TelegramProductAbuseLimiter.Category.PRIVATE_TRAFFIC),
            limiter.evaluate(privateMessage("/menu", 43)),
        )

        now += Duration.ofSeconds(10).toMillis()
        assertIs<TelegramProductAbuseLimiter.Decision.Allowed>(limiter.evaluate(privateMessage("/menu", 43)))
        assertEquals(1, limiter.trackedScopedBucketCount())
    }

    @Test
    fun `category global layer bounds rotating private actors at webhook ingress`() {
        val limiter =
            limiter(
                privateGlobalLimit = TelegramProductAbuseLimiter.Limit(2, Duration.ofMinutes(1)),
            )

        assertIs<TelegramProductAbuseLimiter.Decision.Allowed>(
            limiter.evaluateCoarse(privateMessage("/menu", userId = 41)),
        )
        assertIs<TelegramProductAbuseLimiter.Decision.Allowed>(
            limiter.evaluateCoarse(privateMessage("/menu", userId = 42)),
        )
        assertEquals(
            TelegramProductAbuseLimiter.Decision.Denied(TelegramProductAbuseLimiter.Category.PRIVATE_TRAFFIC),
            limiter.evaluateCoarse(privateMessage("/menu", userId = 43)),
        )
        assertEquals(0, limiter.trackedScopedBucketCount())
    }

    @Test
    fun `category global layer bounds rotating groups and link codes`() {
        val limiter =
            limiter(
                groupGlobalLimit = TelegramProductAbuseLimiter.Limit(10, Duration.ofMinutes(1)),
                groupLinkGlobalLimit = TelegramProductAbuseLimiter.Limit(2, Duration.ofMinutes(1)),
            )

        assertIs<TelegramProductAbuseLimiter.Decision.Allowed>(
            limiter.evaluate(groupMessage("/link ABCD234", chatId = -101)),
        )
        assertIs<TelegramProductAbuseLimiter.Decision.Allowed>(
            limiter.evaluate(groupMessage("/link WXYZ567", chatId = -102)),
        )
        assertEquals(
            TelegramProductAbuseLimiter.Decision.Denied(TelegramProductAbuseLimiter.Category.GROUP_LINK),
            limiter.evaluate(groupMessage("/link JKLM289", chatId = -103)),
        )
    }

    private fun limiter(
        now: () -> Long = { 1_000L },
        privateLimit: TelegramProductAbuseLimiter.Limit =
            TelegramProductAbuseLimiter.Limit(10, Duration.ofMinutes(1)),
        inviteLimit: TelegramProductAbuseLimiter.Limit =
            TelegramProductAbuseLimiter.Limit(10, Duration.ofMinutes(1)),
        groupLimit: TelegramProductAbuseLimiter.Limit =
            TelegramProductAbuseLimiter.Limit(10, Duration.ofMinutes(1)),
        groupLinkLimit: TelegramProductAbuseLimiter.Limit =
            TelegramProductAbuseLimiter.Limit(10, Duration.ofMinutes(1)),
        privateGlobalLimit: TelegramProductAbuseLimiter.Limit =
            TelegramProductAbuseLimiter.Limit(100, Duration.ofMinutes(1)),
        inviteGlobalLimit: TelegramProductAbuseLimiter.Limit =
            TelegramProductAbuseLimiter.Limit(100, Duration.ofMinutes(1)),
        groupGlobalLimit: TelegramProductAbuseLimiter.Limit =
            TelegramProductAbuseLimiter.Limit(100, Duration.ofMinutes(1)),
        groupLinkGlobalLimit: TelegramProductAbuseLimiter.Limit =
            TelegramProductAbuseLimiter.Limit(100, Duration.ofMinutes(1)),
        inviteTokenLimit: TelegramProductAbuseLimiter.Limit =
            TelegramProductAbuseLimiter.Limit(100, Duration.ofMinutes(1)),
        maxTrackedKeys: Int = 20_000,
    ) = TelegramProductAbuseLimiter(
        nowMillis = now,
        limits =
            mapOf(
                TelegramProductAbuseLimiter.Category.PRIVATE_TRAFFIC to privateLimit,
                TelegramProductAbuseLimiter.Category.STAFF_INVITE to inviteLimit,
                TelegramProductAbuseLimiter.Category.GROUP_OPERATION to groupLimit,
                TelegramProductAbuseLimiter.Category.GROUP_LINK to groupLinkLimit,
            ),
        globalLimits =
            mapOf(
                TelegramProductAbuseLimiter.Category.PRIVATE_TRAFFIC to privateGlobalLimit,
                TelegramProductAbuseLimiter.Category.STAFF_INVITE to inviteGlobalLimit,
                TelegramProductAbuseLimiter.Category.GROUP_OPERATION to groupGlobalLimit,
                TelegramProductAbuseLimiter.Category.GROUP_LINK to groupLinkGlobalLimit,
            ),
        inviteTokenLimit = inviteTokenLimit,
        maxTrackedKeys = maxTrackedKeys,
        inviteDigestKey = ByteArray(32) { 7 },
    )

    private fun privateMessage(
        text: String,
        userId: Long = 42,
    ): TelegramUpdate =
        TelegramUpdate(
            updateId = 1,
            message =
                Message(
                    messageId = 1,
                    chat = Chat(id = userId, type = "private"),
                    fromUser = User(id = userId),
                    text = text,
                ),
        )

    private fun groupMessage(
        text: String,
        chatId: Long = -100,
    ): TelegramUpdate =
        TelegramUpdate(
            updateId = 1,
            message =
                Message(
                    messageId = 1,
                    chat = Chat(id = chatId, type = "supergroup"),
                    fromUser = User(id = 42),
                    text = text,
                ),
        )
}
