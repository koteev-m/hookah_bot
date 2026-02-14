package com.hookah.platform.backend.billing

import com.hookah.platform.backend.security.constantTimeEquals
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface BillingSignatureAlgorithm {
    fun sign(
        payload: String,
        secret: String,
    ): String

    fun verify(
        payload: String,
        signature: String,
        secret: String,
    ): Boolean = constantTimeEquals(sign(payload, secret), signature)
}

class HmacSha256HexBillingSignatureAlgorithm : BillingSignatureAlgorithm {
    override fun sign(
        payload: String,
        secret: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
