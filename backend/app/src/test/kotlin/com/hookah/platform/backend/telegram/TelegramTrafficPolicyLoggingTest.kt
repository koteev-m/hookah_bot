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

class TelegramTrafficPolicyLoggingTest {
    @Test
    fun `policy diagnostics and configuration failures do not expose identities`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(TelegramTrafficPolicyLoggingTest::class.java) as Logger
        logger.addAppender(appender)
        try {
            val policy =
                TelegramTrafficPolicy.from(
                    MapApplicationConfig(
                        "telegram.trafficPolicy" to "ALLOWLIST",
                        "telegram.allowedUserIds" to ALLOWED_USER_ID.toString(),
                        "telegram.allowedChatIds" to "$ALLOWED_USER_ID,$ALLOWED_GROUP_ID",
                    ),
                    "staging",
                )
            val decision =
                policy.evaluateInbound(
                    TelegramUpdate(
                        updateId = UPDATE_ID_SENTINEL,
                        message =
                            Message(
                                messageId = 1,
                                chat = Chat(id = DENIED_USER_ID, type = "private"),
                                fromUser = User(id = DENIED_USER_ID),
                                text = PAYLOAD_SENTINEL,
                            ),
                    ),
                )
            logger.info(
                "Telegram traffic policy resolved policy={} scope={} decision={}",
                policy,
                policy.outboundClaimScope,
                decision,
            )

            val configError =
                assertFailsWith<IllegalStateException> {
                    TelegramTrafficPolicy.from(
                        MapApplicationConfig(
                            "telegram.trafficPolicy" to "ALLOWLIST",
                            "telegram.allowedUserIds" to "$ALLOWED_USER_ID,$ALLOWED_USER_ID",
                            "telegram.allowedChatIds" to ALLOWED_USER_ID.toString(),
                        ),
                        "staging",
                    )
                }
            logger.warn("Telegram traffic policy rejected reason={}", configError.message)

            val logs = appender.list.joinToString("\n") { it.formattedMessage }
            assertContains(logs, "allowedUsers=1")
            assertContains(logs, "eligibleChats=2")
            assertContains(logs, "ACTOR_NOT_ALLOWED")
            assertContains(logs, "token #2 duplicates an earlier ID")
            listOf(
                ALLOWED_USER_ID.toString(),
                ALLOWED_GROUP_ID.toString(),
                DENIED_USER_ID.toString(),
                UPDATE_ID_SENTINEL.toString(),
                PAYLOAD_SENTINEL,
            ).forEach { sentinel -> assertFalse(logs.contains(sentinel)) }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private companion object {
        const val ALLOWED_USER_ID = 711111111111111111L
        const val ALLOWED_GROUP_ID = -1002222222222L
        const val DENIED_USER_ID = 722222222222222222L
        const val UPDATE_ID_SENTINEL = 733333333333333333L
        const val PAYLOAD_SENTINEL = "POLICY_PAYLOAD_SENTINEL"
    }
}
