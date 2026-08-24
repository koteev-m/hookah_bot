package com.hookah.platform.backend.telegram

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.server.config.MapApplicationConfig
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramTrafficPolicyConfigTest {
    @Test
    fun `staging requires explicit allowlist with both lists`() {
        val missing =
            assertFailsWith<IllegalStateException> {
                TelegramTrafficPolicy.from(MapApplicationConfig(), "staging")
            }
        assertContains(missing.message.orEmpty(), "telegram.trafficPolicy")

        listOf("", "UNRESTRICTED", "not-a-policy").forEach { value ->
            assertFailsWith<IllegalStateException> {
                TelegramTrafficPolicy.from(
                    MapApplicationConfig("telegram.trafficPolicy" to value),
                    "staging",
                )
            }
        }

        assertFailsWith<IllegalStateException> {
            TelegramTrafficPolicy.from(
                MapApplicationConfig(
                    "telegram.trafficPolicy" to "ALLOWLIST",
                    "telegram.allowedUserIds" to "1",
                ),
                "staging",
            )
        }
    }

    @Test
    fun `non-staging environments preserve unrestricted behavior when policy is absent`() {
        listOf("dev", "test", "prod", "production").forEach { appEnv ->
            val policy = TelegramTrafficPolicy.from(MapApplicationConfig(), appEnv)
            assertTrue(policy.allowsMiniAppUser(123))
            assertTrue(policy.allowsOutboundChat(-456))
        }
    }

    @Test
    fun `unrestricted rejects nonempty allowlist values`() {
        val error =
            assertFailsWith<IllegalStateException> {
                TelegramTrafficPolicy.from(
                    MapApplicationConfig(
                        "telegram.trafficPolicy" to "UNRESTRICTED",
                        "telegram.allowedUserIds" to "712345678901234567",
                    ),
                    "test",
                )
            }
        assertContains(error.message.orEmpty(), "telegram.allowedUserIds")
        assertFalse(error.message.orEmpty().contains("712345678901234567"))
    }

    @Test
    fun `parser accepts canonical positive users and signed chats with surrounding whitespace`() {
        val policy =
            TelegramTrafficPolicy.from(
                MapApplicationConfig(
                    "telegram.trafficPolicy" to " allowlist ",
                    "telegram.allowedUserIds" to " 1, 9223372036854775807 ",
                    "telegram.allowedChatIds" to " 1, -1001234567890, -9223372036854775808 ",
                ),
                "staging",
            )

        assertTrue(policy.allowsMiniAppUser(1))
        assertTrue(policy.allowsOutboundChat(-1001234567890))
        assertTrue(policy.allowsOutboundChat(Long.MIN_VALUE))
        assertFalse(policy.allowsMiniAppUser(Long.MAX_VALUE))
    }

    @Test
    fun `parser rejects invalid empty and duplicate configuration without echoing values`() {
        val invalidUserLists =
            listOf(
                "",
                " ",
                "0",
                "-1",
                "+1",
                "01",
                "1,",
                ",1",
                "1,,2",
                "1 2",
                "1,1",
                "9223372036854775808",
            )
        invalidUserLists.forEach { value ->
            val error =
                assertFailsWith<IllegalStateException> {
                    allowlist(userIds = value)
                }
            assertContains(error.message.orEmpty(), "telegram.allowedUserIds")
            if (value.length >= 10) {
                assertFalse(error.message.orEmpty().contains(value))
            }
        }

        val invalidChatLists =
            listOf("", "0", "-0", "+1", "01", "-01", "-1,-1", "1, 1", "-9223372036854775809")
        invalidChatLists.forEach { value ->
            val error =
                assertFailsWith<IllegalStateException> {
                    allowlist(chatIds = value)
                }
            assertContains(error.message.orEmpty(), "telegram.allowedChatIds")
        }
    }

    @Test
    fun `safe string representations expose counts but not identities`() {
        val policy = allowlist(userIds = "711111111111111111", chatIds = "711111111111111111,-1002222222222")

        val rendered = policy.toString() + policy.outboundClaimScope.toString()
        assertContains(rendered, "allowedUsers=1")
        assertContains(rendered, "eligibleChats=2")
        assertFalse(rendered.contains("711111111111111111"))
        assertFalse(rendered.contains("-1002222222222"))
    }

    @Test
    fun `invalid configuration emits no logs and errors do not expose raw identity values`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        rootLogger.addAppender(appender)
        try {
            val error =
                assertFailsWith<IllegalStateException> {
                    allowlist(
                        userIds = "$SENSITIVE_USER_ID,$SENSITIVE_USER_ID",
                        chatIds = "$SENSITIVE_USER_ID,$SENSITIVE_GROUP_ID",
                    )
                }

            assertContains(error.message.orEmpty(), "telegram.allowedUserIds")
            assertFalse(error.message.orEmpty().contains(SENSITIVE_USER_ID))
            assertFalse(error.message.orEmpty().contains(SENSITIVE_GROUP_ID))
            val logs = appender.list.joinToString("\n") { it.formattedMessage }
            assertTrue(logs.isEmpty())
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun allowlist(
        userIds: String = "1",
        chatIds: String = "1,-1001",
    ): TelegramTrafficPolicy =
        TelegramTrafficPolicy.from(
            MapApplicationConfig(
                "telegram.trafficPolicy" to "ALLOWLIST",
                "telegram.allowedUserIds" to userIds,
                "telegram.allowedChatIds" to chatIds,
            ),
            "staging",
        )

    private companion object {
        const val SENSITIVE_USER_ID = "711111111111111111"
        const val SENSITIVE_GROUP_ID = "-1002222222222"
    }
}
