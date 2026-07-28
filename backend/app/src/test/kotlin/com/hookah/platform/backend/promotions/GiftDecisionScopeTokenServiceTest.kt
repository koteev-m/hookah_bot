package com.hookah.platform.backend.promotions

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GiftDecisionScopeTokenServiceTest {
    private val issuedAt = Instant.parse("2026-07-28T10:00:00Z")
    private val clock = Clock.fixed(issuedAt, ZoneOffset.UTC)
    private val service = GiftDecisionScopeTokenService("scope-test-secret", clock)
    private val scope =
        GiftDecisionCartScope(
            userId = 101L,
            venueId = 201L,
            tableSessionId = 301L,
            tabId = 401L,
            comment = "  У окна \n без льда  ",
            items =
                listOf(
                    GiftDecisionCartItem(
                        menuItemId = 501L,
                        quantity = 2,
                        selectedOptionIds = listOf(702L, 701L),
                        note = "  крепость\tсредняя ",
                    ),
                    GiftDecisionCartItem(menuItemId = 502L, quantity = 1),
                ),
        )
    private val offer =
        GiftDecisionOfferIdentity(
            promotionId = 601L,
            ruleId = 602L,
            ruleVersion = 3,
            offerType = GiftDecisionOfferType.SELECTABLE_ITEM,
        )

    @Test
    fun `same user venue session tab and canonical cart is accepted after fresh instance`() {
        val issued = service.issue(scope, offer)
        val reorderedEquivalentScope =
            scope.copy(
                comment = "У окна без льда",
                items =
                    scope.items
                        .reversed()
                        .map { item ->
                            if (item.menuItemId == 501L) {
                                item.copy(
                                    selectedOptionIds = item.selectedOptionIds.reversed(),
                                    note = "крепость средняя",
                                )
                            } else {
                                item
                            }
                        },
            )

        val restartedService = GiftDecisionScopeTokenService("scope-test-secret", clock)
        val claims = restartedService.verify(issued.token, reorderedEquivalentScope)

        assertEquals(scope.userId, claims.userId)
        assertEquals(scope.venueId, claims.venueId)
        assertEquals(scope.tableSessionId, claims.tableSessionId)
        assertEquals(scope.tabId, claims.tabId)
        assertEquals(offer.ruleId, claims.ruleId)
        assertEquals(offer.ruleVersion, claims.ruleVersion)
        assertEquals(offer.offerType.name, claims.offerType)
        assertEquals(issued.cartFingerprint, claims.cartFingerprint)
    }

    @Test
    fun `identity and cart mutations reject prior scope`() {
        val token = service.issue(scope, offer).token
        val mismatches =
            listOf(
                scope.copy(userId = 102L),
                scope.copy(venueId = 202L),
                scope.copy(tableSessionId = 302L),
                scope.copy(tabId = 402L),
                scope.copy(items = scope.items.map { if (it.menuItemId == 501L) it.copy(quantity = 3) else it }),
                scope.copy(
                    items =
                        scope.items.map {
                            if (it.menuItemId == 501L) it.copy(selectedOptionIds = listOf(703L)) else it
                        },
                ),
                scope.copy(
                    items =
                        scope.items.map {
                            if (it.menuItemId == 501L) it.copy(note = "крепость высокая") else it
                        },
                ),
                scope.copy(items = scope.items.filterNot { it.menuItemId == 501L }),
                scope.copy(comment = "За баром"),
            )

        mismatches.forEach { mismatch ->
            assertFailsWith<InvalidGiftDecisionScopeException> {
                service.verify(token, mismatch)
            }
        }
    }

    @Test
    fun `expired and tampered token are rejected`() {
        val issued = service.issue(scope, offer)
        val expiredService =
            GiftDecisionScopeTokenService(
                signingSecret = "scope-test-secret",
                clock = Clock.fixed(issuedAt.plusSeconds(601L), ZoneOffset.UTC),
            )
        val tampered =
            issued.token.dropLast(1) +
                if (issued.token.last() == 'A') "B" else "A"

        assertFailsWith<InvalidGiftDecisionScopeException> {
            expiredService.verify(issued.token, scope)
        }
        assertFailsWith<InvalidGiftDecisionScopeException> {
            service.verify(tampered, scope)
        }
    }

    @Test
    fun `token payload contains scope but no trusted financial facts`() {
        val issued = service.issue(scope, offer)
        val payload =
            Base64.getUrlDecoder()
                .decode(issued.token.split('.')[1])
                .toString(Charsets.UTF_8)

        assertTrue(payload.contains("\"purpose\":\"gift_decision\""))
        assertTrue(payload.contains("\"audience\":\"hookah-order-submit\""))
        assertTrue(payload.contains("\"cartFingerprint\""))
        assertFalse(payload.contains("price", ignoreCase = true))
        assertFalse(payload.contains("discount", ignoreCase = true))
        assertFalse(payload.contains("amount", ignoreCase = true))
        assertFalse(issued.token.contains("50000"))
    }

    @Test
    fun `current offer rejects old rule version and reward outside current allowlist`() {
        val issued = service.issue(scope, offer)
        val claims = service.verify(issued.token, scope)
        val selectableOffer =
            PromotionGiftOffer(
                status = PromotionGiftOfferStatus.GIFT_CHOICE_REQUIRED,
                promotionId = offer.promotionId,
                ruleId = offer.ruleId,
                ruleVersion = offer.ruleVersion,
                selectableRewardItems =
                    listOf(
                        PromotionGiftRewardItem(
                            menuItemId = 801L,
                            name = "Чай",
                            originalUnitPriceMinor = 300L,
                            currency = "RUB",
                        ),
                    ),
            )

        assertTrue(
            selectableOffer.matchesAuthoritativeScope(
                claims = claims,
                command =
                    GiftDecisionCommand(
                        action = PromotionGiftDecisionAction.SELECT_ITEM,
                        selectedMenuItemId = 801L,
                        decisionScopeToken = issued.token,
                    ),
            ),
        )
        assertFalse(
            selectableOffer.copy(ruleVersion = offer.ruleVersion + 1)
                .matchesAuthoritativeScope(
                    claims = claims,
                    command =
                        GiftDecisionCommand(
                            action = PromotionGiftDecisionAction.SELECT_ITEM,
                            selectedMenuItemId = 801L,
                            decisionScopeToken = issued.token,
                        ),
                ),
        )
        assertFalse(
            selectableOffer.matchesAuthoritativeScope(
                claims = claims,
                command =
                    GiftDecisionCommand(
                        action = PromotionGiftDecisionAction.SELECT_ITEM,
                        selectedMenuItemId = 802L,
                        decisionScopeToken = issued.token,
                    ),
            ),
        )
    }

    @Test
    fun `skip scope from prior cart or tab is rejected before current offer resolution`() {
        val issued = service.issue(scope, offer)
        val skip =
            GiftDecisionCommand(
                action = PromotionGiftDecisionAction.SKIP,
                decisionScopeToken = issued.token,
            )

        assertEquals(PromotionGiftDecisionAction.SKIP, skip.action)
        assertFailsWith<InvalidGiftDecisionScopeException> {
            service.verify(
                token = skip.decisionScopeToken,
                expectedScope =
                    scope.copy(
                        tabId = scope.tabId + 1,
                        items = scope.items.map { it.copy(quantity = it.quantity + 1) },
                    ),
            )
        }
    }
}
