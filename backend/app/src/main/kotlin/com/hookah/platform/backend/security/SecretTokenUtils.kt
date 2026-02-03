package com.hookah.platform.backend.security

import java.net.InetAddress
import java.security.MessageDigest

fun constantTimeEquals(expected: String, provided: String?): Boolean {
    if (provided == null) return false
    return MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        provided.toByteArray(Charsets.UTF_8)
    )
}

class IpAllowlist private constructor(private val entries: List<IpRange>) {
    fun isAllowed(clientIp: String?): Boolean {
        if (clientIp.isNullOrBlank()) return false
        val address = parseAddress(clientIp) ?: return false
        return entries.any { it.matches(address) }
    }

    companion object {
        fun parse(raw: String?): IpAllowlist? {
            val items = raw
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            if (items.isEmpty()) return null

            val entries = items.map { value ->
                IpRange.parse(value) ?: error("Invalid IP allowlist entry: $value")
            }
            return IpAllowlist(entries)
        }
    }
}

private data class IpRange(
    val networkBytes: ByteArray,
    val prefixLength: Int
) {
    fun matches(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != networkBytes.size) return false
        val masked = applyMask(bytes, prefixLength)
        return masked.contentEquals(networkBytes)
    }

    companion object {
        fun parse(raw: String): IpRange? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            val parts = trimmed.split('/', limit = 2)
            val address = parseAddress(parts[0]) ?: return null
            val maxBits = address.address.size * 8
            val prefix = if (parts.size == 2) {
                parts[1].toIntOrNull()?.takeIf { it in 0..maxBits } ?: return null
            } else {
                maxBits
            }

            val networkBytes = applyMask(address.address, prefix)
            return IpRange(networkBytes = networkBytes, prefixLength = prefix)
        }
    }
}

private fun applyMask(bytes: ByteArray, prefixLength: Int): ByteArray {
    val result = bytes.copyOf()
    val fullBytes = prefixLength / 8
    val remainder = prefixLength % 8
    for (i in fullBytes until result.size) {
        result[i] = if (i == fullBytes && remainder > 0) {
            val mask = (0xFF shl (8 - remainder)) and 0xFF
            (result[i].toInt() and mask).toByte()
        } else {
            0
        }
    }
    return result
}

private fun parseAddress(value: String): InetAddress? {
    return try {
        InetAddress.getByName(value)
    } catch (e: Exception) {
        null
    }
}
