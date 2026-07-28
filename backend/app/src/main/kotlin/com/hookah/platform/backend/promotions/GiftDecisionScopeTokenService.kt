package com.hookah.platform.backend.promotions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Clock
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val GIFT_DECISION_TOKEN_VERSION = "v1"
private const val GIFT_DECISION_PURPOSE = "gift_decision"
private const val GIFT_DECISION_AUDIENCE = "hookah-order-submit"
private const val GIFT_DECISION_DOMAIN = "gift_decision/v1"
private const val DEFAULT_GIFT_DECISION_TTL_SECONDS = 10L * 60L

enum class GiftDecisionOfferType {
    FIXED_ITEM,
    SELECTABLE_ITEM,
}

data class GiftDecisionCommand(
    val action: PromotionGiftDecisionAction,
    val selectedMenuItemId: Long? = null,
    val decisionScopeToken: String,
)

object MiniAppGiftDecisionAdapter {
    fun toCommand(
        action: PromotionGiftDecisionAction,
        selectedMenuItemId: Long?,
        decisionScopeToken: String,
    ): GiftDecisionCommand =
        GiftDecisionCommand(
            action = action,
            selectedMenuItemId = selectedMenuItemId,
            decisionScopeToken = decisionScopeToken,
        )
}

object TelegramGiftDecisionAdapter {
    fun toCommand(
        offer: PromotionGiftOffer,
        action: PromotionGiftDecisionAction,
        selectedMenuItemId: Long?,
        decisionScopeToken: String?,
    ): GiftDecisionCommand? {
        val token = decisionScopeToken?.takeIf { it.isNotBlank() } ?: return null
        val identity = offer.decisionOfferIdentityOrNull() ?: return null
        val actionMatchesOffer =
            when (action) {
                PromotionGiftDecisionAction.ACCEPT_FIXED ->
                    identity.offerType == GiftDecisionOfferType.FIXED_ITEM &&
                        selectedMenuItemId == null
                PromotionGiftDecisionAction.SELECT_ITEM ->
                    identity.offerType == GiftDecisionOfferType.SELECTABLE_ITEM &&
                        selectedMenuItemId != null &&
                        offer.selectableRewardItems.any { it.menuItemId == selectedMenuItemId }
                PromotionGiftDecisionAction.SKIP -> selectedMenuItemId == null
            }
        if (!actionMatchesOffer) {
            return null
        }
        return GiftDecisionCommand(
            action = action,
            selectedMenuItemId = selectedMenuItemId,
            decisionScopeToken = token,
        )
    }
}

data class GiftDecisionCartItem(
    val menuItemId: Long,
    val quantity: Int,
    val selectedOptionIds: List<Long> = emptyList(),
    val note: String? = null,
)

data class GiftDecisionCartScope(
    val userId: Long,
    val venueId: Long,
    val tableSessionId: Long,
    val tabId: Long,
    val items: List<GiftDecisionCartItem>,
    val comment: String? = null,
)

data class GiftDecisionOfferIdentity(
    val promotionId: Long,
    val ruleId: Long,
    val ruleVersion: Int,
    val offerType: GiftDecisionOfferType,
)

@Serializable
data class GiftDecisionScopeClaims(
    val purpose: String,
    val audience: String,
    val userId: Long,
    val venueId: Long,
    val tableSessionId: Long,
    val tabId: Long,
    val cartFingerprint: String,
    val promotionId: Long,
    val ruleId: Long,
    val ruleVersion: Int,
    val offerType: String,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
)

data class IssuedGiftDecisionScope(
    val token: String,
    val cartFingerprint: String,
    val expiresAtEpochSeconds: Long,
)

class InvalidGiftDecisionScopeException : RuntimeException("Gift decision scope is invalid")

class GiftDecisionScopeTokenService(
    signingSecret: String,
    private val clock: Clock = Clock.systemUTC(),
    private val ttlSeconds: Long = DEFAULT_GIFT_DECISION_TTL_SECONDS,
) {
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        }
    private val signingKey =
        hmacSha256(
            key = signingSecret.toByteArray(StandardCharsets.UTF_8),
            value = GIFT_DECISION_DOMAIN.toByteArray(StandardCharsets.UTF_8),
        )

    init {
        require(signingSecret.isNotBlank()) { "Gift decision signing secret must not be blank" }
        require(ttlSeconds > 0L) { "Gift decision token TTL must be positive" }
    }

    fun issue(
        scope: GiftDecisionCartScope,
        offer: GiftDecisionOfferIdentity,
        now: Instant = clock.instant(),
    ): IssuedGiftDecisionScope {
        val expiresAt = now.plusSeconds(ttlSeconds)
        val fingerprint = canonicalCartFingerprint(scope, offer)
        val claims =
            GiftDecisionScopeClaims(
                purpose = GIFT_DECISION_PURPOSE,
                audience = GIFT_DECISION_AUDIENCE,
                userId = scope.userId,
                venueId = scope.venueId,
                tableSessionId = scope.tableSessionId,
                tabId = scope.tabId,
                cartFingerprint = fingerprint,
                promotionId = offer.promotionId,
                ruleId = offer.ruleId,
                ruleVersion = offer.ruleVersion,
                offerType = offer.offerType.name,
                issuedAtEpochSeconds = now.epochSecond,
                expiresAtEpochSeconds = expiresAt.epochSecond,
            )
        val encodedClaims =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                json.encodeToString(GiftDecisionScopeClaims.serializer(), claims)
                    .toByteArray(StandardCharsets.UTF_8),
            )
        val signedValue = "$GIFT_DECISION_TOKEN_VERSION.$encodedClaims"
        val signature =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                hmacSha256(
                    key = signingKey,
                    value = "$GIFT_DECISION_DOMAIN.$signedValue".toByteArray(StandardCharsets.UTF_8),
                ),
            )
        return IssuedGiftDecisionScope(
            token = "$signedValue.$signature",
            cartFingerprint = fingerprint,
            expiresAtEpochSeconds = expiresAt.epochSecond,
        )
    }

    fun verify(
        token: String,
        expectedScope: GiftDecisionCartScope,
        now: Instant = clock.instant(),
    ): GiftDecisionScopeClaims {
        val parts = token.split('.')
        if (parts.size != 3 || parts[0] != GIFT_DECISION_TOKEN_VERSION) {
            throw InvalidGiftDecisionScopeException()
        }
        val signedValue = "${parts[0]}.${parts[1]}"
        val actualSignature =
            decodeBase64Url(parts[2])
        val expectedSignature =
            hmacSha256(
                key = signingKey,
                value = "$GIFT_DECISION_DOMAIN.$signedValue".toByteArray(StandardCharsets.UTF_8),
            )
        if (!MessageDigest.isEqual(actualSignature, expectedSignature)) {
            throw InvalidGiftDecisionScopeException()
        }
        val claims =
            runCatching {
                json.decodeFromString(
                    GiftDecisionScopeClaims.serializer(),
                    decodeBase64Url(parts[1]).toString(StandardCharsets.UTF_8),
                )
            }.getOrElse { throw InvalidGiftDecisionScopeException() }
        if (
            claims.purpose != GIFT_DECISION_PURPOSE ||
            claims.audience != GIFT_DECISION_AUDIENCE ||
            claims.userId != expectedScope.userId ||
            claims.venueId != expectedScope.venueId ||
            claims.tableSessionId != expectedScope.tableSessionId ||
            claims.tabId != expectedScope.tabId ||
            claims.ruleId <= 0L ||
            claims.ruleVersion <= 0 ||
            claims.issuedAtEpochSeconds > now.epochSecond ||
            claims.expiresAtEpochSeconds <= now.epochSecond ||
            claims.expiresAtEpochSeconds <= claims.issuedAtEpochSeconds ||
            claims.expiresAtEpochSeconds - claims.issuedAtEpochSeconds > ttlSeconds
        ) {
            throw InvalidGiftDecisionScopeException()
        }
        val offerType =
            runCatching { GiftDecisionOfferType.valueOf(claims.offerType) }
                .getOrElse { throw InvalidGiftDecisionScopeException() }
        val expectedFingerprint =
            canonicalCartFingerprint(
                scope = expectedScope,
                offer =
                    GiftDecisionOfferIdentity(
                        promotionId = claims.promotionId,
                        ruleId = claims.ruleId,
                        ruleVersion = claims.ruleVersion,
                        offerType = offerType,
                    ),
            )
        if (!constantTimeEquals(claims.cartFingerprint, expectedFingerprint)) {
            throw InvalidGiftDecisionScopeException()
        }
        return claims
    }

    fun cartFingerprint(
        scope: GiftDecisionCartScope,
        offer: GiftDecisionOfferIdentity?,
    ): String =
        if (offer == null) {
            sha256Hex(canonicalCartPayload(scope, promotionContext = "none"))
        } else {
            canonicalCartFingerprint(scope, offer)
        }

    private fun canonicalCartFingerprint(
        scope: GiftDecisionCartScope,
        offer: GiftDecisionOfferIdentity,
    ): String =
        sha256Hex(
            canonicalCartPayload(
                scope = scope,
                promotionContext =
                    canonicalFields(
                        offer.promotionId.toString(),
                        offer.ruleId.toString(),
                        offer.ruleVersion.toString(),
                        offer.offerType.name,
                    ),
            ),
        )

    private fun canonicalCartPayload(
        scope: GiftDecisionCartScope,
        promotionContext: String,
    ): String {
        val canonicalItems =
            scope.items
                .map { item ->
                    canonicalFields(
                        item.menuItemId.toString(),
                        item.quantity.toString(),
                        item.selectedOptionIds.sorted().joinToString(","),
                        normalizeText(item.note),
                    )
                }
                .sorted()
        return canonicalFields(
            GIFT_DECISION_DOMAIN,
            scope.userId.toString(),
            scope.venueId.toString(),
            scope.tableSessionId.toString(),
            scope.tabId.toString(),
            normalizeText(scope.comment),
            canonicalItems.joinToString(separator = ""),
            promotionContext,
        )
    }

    private fun normalizeText(value: String?): String =
        Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun canonicalFields(vararg fields: String): String =
        fields.joinToString(separator = "") { value -> "${value.toByteArray(StandardCharsets.UTF_8).size}:$value" }

    private fun decodeBase64Url(value: String): ByteArray =
        runCatching { Base64.getUrlDecoder().decode(value) }
            .getOrElse { throw InvalidGiftDecisionScopeException() }

    private fun constantTimeEquals(
        left: String,
        right: String,
    ): Boolean =
        MessageDigest.isEqual(
            left.toByteArray(StandardCharsets.US_ASCII),
            right.toByteArray(StandardCharsets.US_ASCII),
        )

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun hmacSha256(
        key: ByteArray,
        value: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value)
        }
}

fun PromotionGiftOffer.decisionOfferIdentityOrNull(): GiftDecisionOfferIdentity? {
    val currentPromotionId = promotionId ?: return null
    val currentRuleId = ruleId ?: return null
    val currentRuleVersion = ruleVersion ?: return null
    val type =
        when (status) {
            PromotionGiftOfferStatus.FIXED_GIFT_AVAILABLE -> GiftDecisionOfferType.FIXED_ITEM
            PromotionGiftOfferStatus.GIFT_CHOICE_REQUIRED -> GiftDecisionOfferType.SELECTABLE_ITEM
            else -> return null
        }
    return GiftDecisionOfferIdentity(
        promotionId = currentPromotionId,
        ruleId = currentRuleId,
        ruleVersion = currentRuleVersion,
        offerType = type,
    )
}

fun GiftDecisionScopeClaims.toPromotionGiftDecision(command: GiftDecisionCommand): PromotionGiftDecision =
    PromotionGiftDecision(
        action = command.action,
        promotionId = promotionId,
        ruleId = ruleId,
        ruleVersion = ruleVersion,
        selectedMenuItemId = command.selectedMenuItemId,
    )

fun PromotionGiftOffer.matchesAuthoritativeScope(
    claims: GiftDecisionScopeClaims,
    command: GiftDecisionCommand,
): Boolean {
    val identity = decisionOfferIdentityOrNull() ?: return false
    if (
        identity.promotionId != claims.promotionId ||
        identity.ruleId != claims.ruleId ||
        identity.ruleVersion != claims.ruleVersion ||
        identity.offerType.name != claims.offerType
    ) {
        return false
    }
    return when (command.action) {
        PromotionGiftDecisionAction.ACCEPT_FIXED ->
            identity.offerType == GiftDecisionOfferType.FIXED_ITEM &&
                command.selectedMenuItemId == null
        PromotionGiftDecisionAction.SELECT_ITEM ->
            identity.offerType == GiftDecisionOfferType.SELECTABLE_ITEM &&
                command.selectedMenuItemId != null &&
                selectableRewardItems.any { it.menuItemId == command.selectedMenuItemId }
        PromotionGiftDecisionAction.SKIP -> command.selectedMenuItemId == null
    }
}
