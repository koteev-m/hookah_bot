package com.hookah.platform.backend.miniapp.security

import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MiniAppAbuseProtectionTest {
    @Test
    fun `auth limits unvalidated sources and validated subjects independently`() {
        val now = AtomicLong(1_000L)
        val protection =
            MiniAppAbuseProtection(
                config =
                    MiniAppAbuseConfig(
                        authPreGlobal = policy(100),
                        authPreSource = policy(2),
                        authPostGlobal = policy(100),
                        authPostSubject = policy(1),
                    ),
                nowMillis = now::get,
                digestKey = DIGEST_KEY,
            )

        assertEquals(MiniAppRateLimitDecision.Allowed, protection.tryAuthPreValidation("source-a"))
        assertEquals(MiniAppRateLimitDecision.Allowed, protection.tryAuthPreValidation("source-a"))
        assertIs<MiniAppRateLimitDecision.Denied>(protection.tryAuthPreValidation("source-a"))
        assertEquals(MiniAppRateLimitDecision.Allowed, protection.tryAuthPreValidation("source-b"))

        assertEquals(MiniAppRateLimitDecision.Allowed, protection.tryAuthPostValidation(11L))
        assertIs<MiniAppRateLimitDecision.Denied>(protection.tryAuthPostValidation(11L))
        assertEquals(MiniAppRateLimitDecision.Allowed, protection.tryAuthPostValidation(12L))

        now.addAndGet(WINDOW.toMillis() + 1L)
        assertEquals(MiniAppRateLimitDecision.Allowed, protection.tryAuthPostValidation(11L))
    }

    @Test
    fun `invite acceptance limits a token digest across different subjects`() {
        val protection =
            MiniAppAbuseProtection(
                config =
                    MiniAppAbuseConfig(
                        inviteAcceptGlobal = policy(100),
                        inviteAcceptSubject = policy(100),
                        inviteAcceptDigest = policy(2),
                    ),
                digestKey = DIGEST_KEY,
            )

        assertEquals(MiniAppRateLimitDecision.Allowed, protection.tryInviteAccept(1L, "SAME-CODE"))
        assertEquals(MiniAppRateLimitDecision.Allowed, protection.tryInviteAccept(2L, "SAME-CODE"))
        assertIs<MiniAppRateLimitDecision.Denied>(protection.tryInviteAccept(3L, "SAME-CODE"))
        assertEquals(MiniAppRateLimitDecision.Allowed, protection.tryInviteAccept(3L, "OTHER-CODE"))
    }

    @Test
    fun `invite creation is scoped to actor and venue and expired buckets are reclaimed`() {
        val now = AtomicLong(5_000L)
        val protection =
            MiniAppAbuseProtection(
                config =
                    MiniAppAbuseConfig(
                        inviteCreateGlobal = policy(100),
                        inviteCreateActorVenue = policy(1),
                        maxTrackedBuckets = 4,
                        cleanupEvery = 1,
                    ),
                nowMillis = now::get,
                digestKey = DIGEST_KEY,
            )

        assertEquals(MiniAppRateLimitDecision.Allowed, protection.tryInviteCreate(1L, 10L))
        assertIs<MiniAppRateLimitDecision.Denied>(protection.tryInviteCreate(1L, 10L))
        assertEquals(MiniAppRateLimitDecision.Allowed, protection.tryInviteCreate(1L, 20L))
        assertEquals(MiniAppRateLimitDecision.Allowed, protection.tryInviteCreate(2L, 10L))

        now.addAndGet(WINDOW.toMillis() + 1L)
        assertEquals(MiniAppRateLimitDecision.Allowed, protection.tryInviteCreate(1L, 10L))
        assertEquals(2, protection.bucketCountForTesting())
    }

    private fun policy(maxAttempts: Int): MiniAppRateLimitPolicy = MiniAppRateLimitPolicy(maxAttempts, WINDOW)

    private companion object {
        val WINDOW: Duration = Duration.ofSeconds(10)
        val DIGEST_KEY: ByteArray = ByteArray(32) { 7 }
    }
}
