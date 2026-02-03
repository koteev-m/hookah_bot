package com.hookah.platform.backend.security

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class IpAllowlistTest {
    @Test
    fun `allowlist parses ipv4 and cidr`() {
        val allowlist = IpAllowlist.parse("203.0.113.10,203.0.113.0/24")
        assertNotNull(allowlist)
    }

    @Test
    fun `allowlist rejects hostnames`() {
        assertFailsWith<IllegalStateException> {
            IpAllowlist.parse("localhost")
        }
        assertFailsWith<IllegalStateException> {
            IpAllowlist.parse("example.com")
        }
    }

    @Test
    fun `allowlist matches ipv4 correctly`() {
        val allowlist = IpAllowlist.parse("203.0.113.0/24")
        assertNotNull(allowlist)
        assertTrue(allowlist.isAllowed("203.0.113.10"))
        assertFalse(allowlist.isAllowed("203.0.114.10"))
    }
}
