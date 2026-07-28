package com.hookah.platform.backend.promotions

import com.hookah.platform.backend.miniapp.venue.menu.MenuSemanticType
import com.hookah.platform.backend.telegram.db.PromotionRewardMode
import com.hookah.platform.backend.telegram.db.PromotionRuleReward
import com.hookah.platform.backend.telegram.db.PromotionRuleRewardOption
import com.hookah.platform.backend.telegram.db.PromotionRuleTarget
import com.hookah.platform.backend.telegram.db.PromotionRuleTargetType
import com.hookah.platform.backend.telegram.db.PromotionRuleType
import com.hookah.platform.backend.telegram.db.PromotionWeekdayWindow
import com.hookah.platform.backend.telegram.db.VenuePromotionRule
import com.hookah.platform.backend.telegram.db.VenuePromotionStatus
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromotionRuleEngineTest {
    @Test
    fun `rule engine applies percent to hookah items only`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems =
                    listOf(
                        cartItem(1L, "Кальян", 2, 100_000L, MenuSemanticType.HOOKAH),
                        cartItem(2L, "Чай", 1, 40_000L, MenuSemanticType.TEA),
                    ),
                activeRules = listOf(rule(101L, target = MenuSemanticType.HOOKAH, percent = 20)),
            )

        assertEquals(1, preview.adjustments.size)
        assertEquals("Кальян", preview.adjustments.single().itemName)
        assertEquals(40_000L, preview.totalPreviewDiscountMinor)
    }

    @Test
    fun `highest percent wins if two rules match`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH)),
                activeRules =
                    listOf(
                        rule(101L, target = MenuSemanticType.HOOKAH, percent = 10),
                        rule(102L, target = MenuSemanticType.HOOKAH, percent = 25),
                    ),
            )

        assertEquals(1, preview.adjustments.size)
        assertEquals(102L, preview.adjustments.single().ruleId)
        assertEquals(25_000L, preview.totalPreviewDiscountMinor)
    }

    @Test
    fun `percent and gift default non-stackable chooses best monetary benefit`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 200_000L, MenuSemanticType.HOOKAH)),
                activeRules =
                    listOf(
                        rule(101L, target = MenuSemanticType.HOOKAH, percent = 10),
                        giftRule(201L, target = MenuSemanticType.HOOKAH, rewardPriceMinor = 50_000L),
                    ),
            )

        assertTrue(preview.adjustments.isEmpty())
        assertEquals(1, preview.gifts.size)
        assertEquals(201L, preview.gifts.single().ruleId)
        assertEquals("Чай", preview.gifts.single().rewardItemName)
    }

    @Test
    fun `non-stackable percent with stackable gift chooses best monetary benefit`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 200_000L, MenuSemanticType.HOOKAH)),
                activeRules =
                    listOf(
                        rule(101L, target = MenuSemanticType.HOOKAH, percent = 10),
                        giftRule(
                            201L,
                            target = MenuSemanticType.HOOKAH,
                            rewardPriceMinor = 50_000L,
                            stackable = true,
                        ),
                    ),
            )

        assertTrue(preview.adjustments.isEmpty())
        assertEquals(1, preview.gifts.size)
        assertEquals(201L, preview.gifts.single().ruleId)
    }

    @Test
    fun `happy hours remains non-stackable when legacy metadata says stackable`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 200_000L, MenuSemanticType.HOOKAH)),
                activeRules =
                    listOf(
                        rule(101L, target = MenuSemanticType.HOOKAH, percent = 10, stackable = true),
                        giftRule(
                            201L,
                            target = MenuSemanticType.HOOKAH,
                            rewardPriceMinor = 50_000L,
                            stackable = true,
                        ),
                    ),
            )

        assertTrue(preview.adjustments.isEmpty())
        assertEquals(0L, preview.totalPreviewDiscountMinor)
        assertEquals(1, preview.gifts.size)
        assertEquals(201L, preview.gifts.single().ruleId)
    }

    @Test
    fun `happy hours never stack and winning discount is capped at item gross`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH)),
                activeRules =
                    listOf(
                        rule(101L, target = MenuSemanticType.HOOKAH, percent = 150, stackable = true),
                        rule(102L, target = MenuSemanticType.HOOKAH, percent = 80, stackable = true),
                    ),
            )

        assertEquals(1, preview.adjustments.size)
        assertEquals(101L, preview.adjustments.single().ruleId)
        assertEquals(100_000L, preview.totalPreviewDiscountMinor)
        assertEquals(0L, 100_000L - preview.adjustments.single().discountMinor)
    }

    @Test
    fun `non-stackable gift with stackable percent chooses best monetary benefit`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 200_000L, MenuSemanticType.HOOKAH)),
                activeRules =
                    listOf(
                        rule(101L, target = MenuSemanticType.HOOKAH, percent = 10, stackable = true),
                        giftRule(
                            201L,
                            target = MenuSemanticType.HOOKAH,
                            rewardPriceMinor = 50_000L,
                        ),
                    ),
            )

        assertTrue(preview.adjustments.isEmpty())
        assertEquals(1, preview.gifts.size)
        assertEquals(201L, preview.gifts.single().ruleId)
    }

    @Test
    fun `two gift rules for same trigger choose more expensive gift`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 200_000L, MenuSemanticType.HOOKAH)),
                activeRules =
                    listOf(
                        giftRule(
                            201L,
                            target = MenuSemanticType.HOOKAH,
                            rewardItemName = "Чай",
                            rewardPriceMinor = 30_000L,
                        ),
                        giftRule(
                            202L,
                            target = MenuSemanticType.HOOKAH,
                            rewardItemName = "Сок",
                            rewardPriceMinor = 50_000L,
                        ),
                    ),
            )

        assertEquals(1, preview.gifts.size)
        assertEquals(202L, preview.gifts.single().ruleId)
        assertEquals("Сок", preview.gifts.single().rewardItemName)
    }

    @Test
    fun `item-specific and category percent rules resolve by benefit not specificity`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян обычный", 1, 100_000L, MenuSemanticType.HOOKAH)),
                activeRules =
                    listOf(
                        rule(
                            101L,
                            target = MenuSemanticType.HOOKAH,
                            percent = 10,
                            targets =
                                listOf(
                                    PromotionRuleTarget(
                                        id = 1L,
                                        ruleId = 101L,
                                        targetType = PromotionRuleTargetType.MENU_ITEM,
                                        semanticType = null,
                                        menuItemId = 1L,
                                    ),
                                ),
                        ),
                        rule(102L, target = MenuSemanticType.HOOKAH, percent = 20),
                    ),
            )

        assertEquals(1, preview.adjustments.size)
        assertEquals(102L, preview.adjustments.single().ruleId)
        assertEquals(20_000L, preview.totalPreviewDiscountMinor)
    }

    @Test
    fun `priority tie-breaker is deterministic when monetary benefit is equal`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH)),
                activeRules =
                    listOf(
                        rule(101L, target = MenuSemanticType.HOOKAH, percent = 20, priority = 100),
                        rule(102L, target = MenuSemanticType.HOOKAH, percent = 20, priority = 50),
                    ),
            )

        assertEquals(1, preview.adjustments.size)
        assertEquals(102L, preview.adjustments.single().ruleId)
    }

    @Test
    fun `rule id is final deterministic tie-breaker regardless of input order`() {
        val cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH))
        val laterRule = rule(102L, target = MenuSemanticType.HOOKAH, percent = 20, priority = 50)
        val earlierRule = rule(101L, target = MenuSemanticType.HOOKAH, percent = 20, priority = 50)

        val forward =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cartItems,
                activeRules = listOf(laterRule, earlierRule),
            )
        val reverse =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cartItems,
                activeRules = listOf(earlierRule, laterRule),
            )

        assertEquals(101L, forward.adjustments.single().ruleId)
        assertEquals(101L, reverse.adjustments.single().ruleId)
    }

    @Test
    fun `multiple category rules apply to different item types`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems =
                    listOf(
                        cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH),
                        cartItem(2L, "Чай", 1, 40_000L, MenuSemanticType.TEA),
                    ),
                activeRules =
                    listOf(
                        rule(101L, target = MenuSemanticType.HOOKAH, percent = 20),
                        rule(102L, target = MenuSemanticType.TEA, percent = 10),
                    ),
            )

        assertEquals(2, preview.adjustments.size)
        assertEquals(24_000L, preview.totalPreviewDiscountMinor)
        assertEquals(setOf(101L, 102L), preview.adjustments.map { it.ruleId }.toSet())
    }

    @Test
    fun `menu category target applies only to items in the selected concrete category`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems =
                    listOf(
                        cartItem(
                            id = 1L,
                            name = "Кальян основной",
                            qty = 1,
                            priceMinor = 100_000L,
                            type = MenuSemanticType.HOOKAH,
                            menuCategoryId = 701L,
                        ),
                        cartItem(
                            id = 2L,
                            name = "Кальян другой категории",
                            qty = 1,
                            priceMinor = 200_000L,
                            type = MenuSemanticType.HOOKAH,
                            menuCategoryId = 702L,
                        ),
                    ),
                activeRules =
                    listOf(
                        rule(
                            id = 101L,
                            target = MenuSemanticType.HOOKAH,
                            percent = 20,
                            targets =
                                listOf(
                                    PromotionRuleTarget(
                                        id = 1L,
                                        ruleId = 101L,
                                        targetType = PromotionRuleTargetType.MENU_CATEGORY,
                                        semanticType = null,
                                        menuItemId = null,
                                        menuCategoryId = 701L,
                                    ),
                                ),
                        ),
                    ),
            )

        assertEquals(1, preview.adjustments.size)
        assertEquals(1L, preview.adjustments.single().menuItemId)
        assertEquals(20_000L, preview.totalPreviewDiscountMinor)
    }

    @Test
    fun `executable target marker without target row never falls back to broad semantic category`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems =
                    listOf(
                        cartItem(
                            id = 1L,
                            name = "Кальян",
                            qty = 1,
                            priceMinor = 100_000L,
                            type = MenuSemanticType.HOOKAH,
                            menuCategoryId = 701L,
                        ),
                    ),
                activeRules =
                    listOf(
                        rule(
                            id = 101L,
                            target = MenuSemanticType.HOOKAH,
                            percent = 20,
                            executableTargetType = PromotionRuleTargetType.MENU_CATEGORY,
                        ),
                    ),
            )

        assertTrue(preview.adjustments.isEmpty())
    }

    @Test
    fun `executable happy hours fails closed for empty or overlapping windows`() {
        val target =
            PromotionRuleTarget(
                id = 1L,
                ruleId = 101L,
                targetType = PromotionRuleTargetType.MENU_ITEM,
                semanticType = null,
                menuItemId = 1L,
            )
        val cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH))
        val emptyWindows =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cartItems,
                activeRules =
                    listOf(
                        rule(
                            id = 101L,
                            target = MenuSemanticType.HOOKAH,
                            percent = 20,
                            executableTargetType = PromotionRuleTargetType.MENU_ITEM,
                            targets = listOf(target),
                        ),
                    ),
            )
        val overlappingWindows =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cartItems,
                activeRules =
                    listOf(
                        rule(
                            id = 101L,
                            target = MenuSemanticType.HOOKAH,
                            percent = 20,
                            executableTargetType = PromotionRuleTargetType.MENU_ITEM,
                            targets = listOf(target),
                            weekdayWindows =
                                listOf(
                                    PromotionWeekdayWindow(4, 12 * 60, 18 * 60),
                                    PromotionWeekdayWindow(4, 17 * 60, 19 * 60),
                                ),
                        ),
                    ),
            )

        assertTrue(emptyWindows.adjustments.isEmpty())
        assertTrue(overlappingWindows.adjustments.isEmpty())
    }

    @Test
    fun `executable happy hours fails closed for multiple item targets`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems =
                    listOf(
                        cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH),
                        cartItem(2L, "Другой кальян", 1, 100_000L, MenuSemanticType.HOOKAH),
                    ),
                activeRules =
                    listOf(
                        rule(
                            id = 101L,
                            target = MenuSemanticType.HOOKAH,
                            percent = 20,
                            executableTargetType = PromotionRuleTargetType.MENU_ITEM,
                            weekdayWindows = listOf(PromotionWeekdayWindow(4, 0, 1440)),
                            targets =
                                listOf(
                                    PromotionRuleTarget(
                                        id = 1L,
                                        ruleId = 101L,
                                        targetType = PromotionRuleTargetType.MENU_ITEM,
                                        semanticType = null,
                                        menuItemId = 1L,
                                    ),
                                    PromotionRuleTarget(
                                        id = 2L,
                                        ruleId = 101L,
                                        targetType = PromotionRuleTargetType.MENU_ITEM,
                                        semanticType = null,
                                        menuItemId = 2L,
                                    ),
                                ),
                        ),
                    ),
            )

        assertTrue(preview.adjustments.isEmpty())
    }

    @Test
    fun `promotion lifecycle is checked together with weekday window`() {
        val cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH))
        val activeWindow = listOf(PromotionWeekdayWindow(4, 0, 1440))

        fun preview(rule: VenuePromotionRule) =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cartItems,
                activeRules = listOf(rule),
            )

        assertTrue(
            preview(
                rule(
                    id = 101L,
                    target = MenuSemanticType.HOOKAH,
                    percent = 20,
                    weekdayWindows = activeWindow,
                    promotionStartsAt = Instant.parse("2026-05-15T00:00:00Z"),
                ),
            ).adjustments.isEmpty(),
        )
        assertTrue(
            preview(
                rule(
                    id = 102L,
                    target = MenuSemanticType.HOOKAH,
                    percent = 20,
                    weekdayWindows = activeWindow,
                    promotionEndsAt = Instant.parse("2026-05-13T23:59:59Z"),
                ),
            ).adjustments.isEmpty(),
        )
    }

    @Test
    fun `menu item target applies only to selected item`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems =
                    listOf(
                        cartItem(1L, "Кальян обычный", 1, 100_000L, MenuSemanticType.HOOKAH),
                        cartItem(2L, "Премиум кальян", 1, 200_000L, MenuSemanticType.HOOKAH),
                    ),
                activeRules =
                    listOf(
                        rule(
                            101L,
                            target = MenuSemanticType.HOOKAH,
                            percent = 20,
                            targets =
                                listOf(
                                    PromotionRuleTarget(
                                        id = 1L,
                                        ruleId = 101L,
                                        targetType = PromotionRuleTargetType.MENU_ITEM,
                                        semanticType = null,
                                        menuItemId = 1L,
                                        menuItemName = "Кальян обычный",
                                    ),
                                ),
                        ),
                    ),
            )

        assertEquals(1, preview.adjustments.size)
        assertEquals("Кальян обычный", preview.adjustments.single().itemName)
        assertEquals(20_000L, preview.totalPreviewDiscountMinor)
    }

    @Test
    fun `menu item target supports multiple selected items`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems =
                    listOf(
                        cartItem(1L, "Кальян обычный", 1, 100_000L, MenuSemanticType.HOOKAH),
                        cartItem(2L, "Премиум кальян", 1, 200_000L, MenuSemanticType.HOOKAH),
                        cartItem(3L, "Чай", 1, 40_000L, MenuSemanticType.TEA),
                    ),
                activeRules =
                    listOf(
                        rule(
                            101L,
                            target = MenuSemanticType.HOOKAH,
                            percent = 10,
                            targets =
                                listOf(
                                    PromotionRuleTarget(
                                        id = 1L,
                                        ruleId = 101L,
                                        targetType = PromotionRuleTargetType.MENU_ITEM,
                                        semanticType = null,
                                        menuItemId = 1L,
                                    ),
                                    PromotionRuleTarget(
                                        id = 2L,
                                        ruleId = 101L,
                                        targetType = PromotionRuleTargetType.MENU_ITEM,
                                        semanticType = null,
                                        menuItemId = 2L,
                                    ),
                                ),
                        ),
                    ),
            )

        assertEquals(2, preview.adjustments.size)
        assertEquals(30_000L, preview.totalPreviewDiscountMinor)
        assertEquals(setOf("Кальян обычный", "Премиум кальян"), preview.adjustments.map { it.itemName }.toSet())
    }

    @Test
    fun `gift rule emits reward only when trigger target matches`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems =
                    listOf(
                        cartItem(1L, "Кальян обычный", 1, 100_000L, MenuSemanticType.HOOKAH),
                        cartItem(2L, "Лимонад", 1, 40_000L, MenuSemanticType.DRINK),
                    ),
                activeRules = listOf(giftRule(201L, target = MenuSemanticType.HOOKAH)),
            )

        assertTrue(preview.adjustments.isEmpty())
        assertEquals(1, preview.gifts.size)
        assertEquals("Кальян обычный", preview.gifts.single().triggerItemName)
        assertEquals("Чай", preview.gifts.single().rewardItemName)
    }

    @Test
    fun `gift menu item trigger applies only selected trigger item`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems =
                    listOf(
                        cartItem(1L, "Кальян обычный", 1, 100_000L, MenuSemanticType.HOOKAH),
                        cartItem(2L, "Премиум кальян", 1, 200_000L, MenuSemanticType.HOOKAH),
                    ),
                activeRules =
                    listOf(
                        giftRule(
                            201L,
                            target = MenuSemanticType.HOOKAH,
                            targets =
                                listOf(
                                    PromotionRuleTarget(
                                        id = 1L,
                                        ruleId = 201L,
                                        targetType = PromotionRuleTargetType.MENU_ITEM,
                                        semanticType = null,
                                        menuItemId = 2L,
                                    ),
                                ),
                        ),
                    ),
            )

        assertEquals(1, preview.gifts.size)
        assertEquals("Премиум кальян", preview.gifts.single().triggerItemName)
    }

    @Test
    fun `gift rule does not emit without trigger or unavailable reward`() {
        val noTrigger =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(2L, "Лимонад", 1, 40_000L, MenuSemanticType.DRINK)),
                activeRules = listOf(giftRule(201L, target = MenuSemanticType.HOOKAH)),
            )
        val unavailableReward =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH)),
                activeRules = listOf(giftRule(202L, target = MenuSemanticType.HOOKAH, rewardAvailable = false)),
            )

        assertTrue(noTrigger.gifts.isEmpty())
        assertTrue(unavailableReward.gifts.isEmpty())
    }

    @Test
    fun `choice gift exposes options until guest selects one reward`() {
        val rule =
            giftRule(
                203L,
                target = MenuSemanticType.HOOKAH,
                rewardMode = PromotionRewardMode.CHOICE_ITEMS,
                rewardOptions =
                    listOf(
                        rewardOption(4L, "Чай"),
                        rewardOption(5L, "Лимонад"),
                    ),
            )

        val withoutSelection =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH)),
                activeRules = listOf(rule),
            )
        val withSelection =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH)),
                activeRules = listOf(rule),
                selectedGiftChoices = mapOf(203L to 5L),
            )

        assertTrue(withoutSelection.gifts.isEmpty())
        assertEquals(1, withoutSelection.giftChoices.size)
        assertEquals(setOf(4L, 5L), withoutSelection.giftChoices.single().options.map { it.menuItemId }.toSet())
        assertEquals(1, withSelection.gifts.size)
        assertEquals("Лимонад", withSelection.gifts.single().rewardItemName)
        assertTrue(withSelection.giftChoices.isEmpty())
    }

    @Test
    fun `choice gift rejects unavailable selected reward and can be skipped`() {
        val rule =
            giftRule(
                204L,
                target = MenuSemanticType.HOOKAH,
                rewardMode = PromotionRewardMode.CHOICE_ITEMS,
                rewardOptions =
                    listOf(
                        rewardOption(4L, "Чай", available = false),
                        rewardOption(5L, "Лимонад"),
                    ),
            )

        val unavailableSelected =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH)),
                activeRules = listOf(rule),
                selectedGiftChoices = mapOf(204L to 4L),
            )
        val skipped =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH)),
                activeRules = listOf(rule),
                selectedGiftChoices = mapOf(204L to 4L),
                skippedGiftRuleIds = setOf(204L),
            )

        assertTrue(unavailableSelected.gifts.isEmpty())
        assertEquals(1, unavailableSelected.giftChoices.size)
        assertEquals(listOf(5L), unavailableSelected.giftChoices.single().options.map { it.menuItemId })
        assertTrue(skipped.gifts.isEmpty())
        assertTrue(skipped.giftChoices.isEmpty())
    }

    @Test
    fun `inactive and out of time rules are ignored`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH)),
                activeRules =
                    listOf(
                        rule(
                            101L,
                            target = MenuSemanticType.HOOKAH,
                            percent = 20,
                            status = VenuePromotionStatus.PAUSED,
                        ),
                        rule(
                            102L,
                            target = MenuSemanticType.HOOKAH,
                            percent = 30,
                            startsTime = LocalTime.of(20, 0),
                            endsTime = LocalTime.of(23, 0),
                        ),
                    ),
            )

        assertTrue(preview.adjustments.isEmpty())
    }

    @Test
    fun `day of week filter uses venue timezone`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T21:30:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH)),
                activeRules =
                    listOf(
                        rule(
                            101L,
                            target = MenuSemanticType.HOOKAH,
                            percent = 20,
                            weekdayWindows =
                                listOf(
                                    PromotionWeekdayWindow(
                                        weekday = 5,
                                        startsMinute = 0,
                                        endsMinute = 1440,
                                    ),
                                ),
                        ),
                    ),
            )

        assertEquals(20_000L, preview.totalPreviewDiscountMinor)
    }

    @Test
    fun `normalized window includes start and excludes end boundary`() {
        val rule =
            rule(
                id = 101L,
                target = MenuSemanticType.HOOKAH,
                percent = 20,
                weekdayWindows =
                    listOf(
                        PromotionWeekdayWindow(
                            weekday = 4,
                            startsMinute = 12 * 60,
                            endsMinute = 18 * 60,
                        ),
                    ),
            )
        val cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH))

        val atStart =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T09:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cartItems,
                activeRules = listOf(rule),
            )
        val immediatelyBeforeEnd =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T14:59:59Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cartItems,
                activeRules = listOf(rule),
            )
        val atEnd =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T15:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cartItems,
                activeRules = listOf(rule),
            )

        assertEquals(20_000L, atStart.totalPreviewDiscountMinor)
        assertEquals(20_000L, immediatelyBeforeEnd.totalPreviewDiscountMinor)
        assertTrue(atEnd.adjustments.isEmpty())
    }

    @Test
    fun `normalized schedule supports different windows on multiple weekdays`() {
        val rule =
            rule(
                id = 101L,
                target = MenuSemanticType.HOOKAH,
                percent = 20,
                weekdayWindows =
                    listOf(
                        PromotionWeekdayWindow(weekday = 4, startsMinute = 12 * 60, endsMinute = 18 * 60),
                        PromotionWeekdayWindow(weekday = 5, startsMinute = 12 * 60, endsMinute = 16 * 60),
                        PromotionWeekdayWindow(weekday = 7, startsMinute = 14 * 60, endsMinute = 17 * 60),
                    ),
            )
        val cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH))

        fun previewAt(now: String) =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse(now),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cartItems,
                activeRules = listOf(rule),
            )

        assertEquals(20_000L, previewAt("2026-05-14T14:59:59Z").totalPreviewDiscountMinor)
        assertEquals(20_000L, previewAt("2026-05-15T12:59:59Z").totalPreviewDiscountMinor)
        assertEquals(20_000L, previewAt("2026-05-17T13:59:59Z").totalPreviewDiscountMinor)
        assertTrue(previewAt("2026-05-15T13:00:00Z").adjustments.isEmpty())
        assertTrue(previewAt("2026-05-16T13:00:00Z").adjustments.isEmpty())
    }

    @Test
    fun `normalized windows take precedence over legacy schedule fields`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH)),
                activeRules =
                    listOf(
                        rule(
                            id = 101L,
                            target = MenuSemanticType.HOOKAH,
                            percent = 20,
                            startsTime = LocalTime.of(0, 0),
                            endsTime = LocalTime.of(23, 59),
                            daysOfWeek = setOf(4),
                            weekdayWindows =
                                listOf(
                                    PromotionWeekdayWindow(
                                        weekday = 5,
                                        startsMinute = 12 * 60,
                                        endsMinute = 18 * 60,
                                    ),
                                ),
                        ),
                    ),
            )

        assertTrue(preview.adjustments.isEmpty())
    }

    @Test
    fun `weekday and time schedule applies only inside window`() {
        val rule =
            rule(
                101L,
                target = MenuSemanticType.HOOKAH,
                percent = 20,
                startsTime = LocalTime.of(14, 0),
                endsTime = LocalTime.of(18, 0),
                daysOfWeek = setOf(4),
            )
        val inside =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH)),
                activeRules = listOf(rule),
            )
        val outsideTime =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T16:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH)),
                activeRules = listOf(rule),
            )
        val outsideDay =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-15T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH)),
                activeRules = listOf(rule),
            )

        assertEquals(20_000L, inside.totalPreviewDiscountMinor)
        assertTrue(outsideTime.adjustments.isEmpty())
        assertTrue(outsideDay.adjustments.isEmpty())
    }

    @Test
    fun `gift rule with empty schedule applies always`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T20:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH)),
                activeRules = listOf(giftRule(301L, target = MenuSemanticType.HOOKAH)),
            )

        assertEquals(1, preview.gifts.size)
        assertEquals("Чай", preview.gifts.single().rewardItemName)
    }

    @Test
    fun `gift rule schedule applies only inside venue local window`() {
        val rule =
            giftRule(
                302L,
                target = MenuSemanticType.HOOKAH,
                startsTime = LocalTime.of(14, 0),
                endsTime = LocalTime.of(18, 0),
                daysOfWeek = setOf(4),
            )
        val cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH))

        val inside =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cartItems,
                activeRules = listOf(rule),
            )
        val outsideTime =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T16:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cartItems,
                activeRules = listOf(rule),
            )
        val outsideDay =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-15T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cartItems,
                activeRules = listOf(rule),
            )

        assertEquals(1, inside.gifts.size)
        assertTrue(outsideTime.gifts.isEmpty())
        assertTrue(outsideDay.gifts.isEmpty())
    }

    @Test
    fun `fixed gift requires explicit accept and preserves skip as a server decision`() {
        val rule = giftRule(401L, target = MenuSemanticType.HOOKAH)
        val cart = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH))

        val offered =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cart,
                activeRules = listOf(rule),
            )
        val accepted =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cart,
                activeRules = listOf(rule),
                giftDecision =
                    PromotionGiftDecision(
                        action = PromotionGiftDecisionAction.ACCEPT_FIXED,
                        promotionId = 601L,
                        ruleId = 401L,
                        ruleVersion = 1,
                    ),
            )
        val skipped =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cart,
                activeRules = listOf(rule),
                giftDecision =
                    PromotionGiftDecision(
                        action = PromotionGiftDecisionAction.SKIP,
                        promotionId = 601L,
                        ruleId = 401L,
                        ruleVersion = 1,
                    ),
            )

        assertEquals(PromotionGiftOfferStatus.FIXED_GIFT_AVAILABLE, offered.giftOffer.status)
        assertTrue(offered.appliedGifts.isEmpty())
        assertTrue(!offered.giftDecisionResolved)
        assertEquals(PromotionGiftOfferStatus.GIFT_SELECTED, accepted.giftOffer.status)
        assertEquals("Чай", accepted.giftOffer.selectedRewardItem?.name)
        assertEquals(1, accepted.appliedGifts.size)
        assertTrue(accepted.giftDecisionResolved)
        assertEquals(PromotionGiftOfferStatus.GIFT_SKIPPED, skipped.giftOffer.status)
        assertTrue(skipped.appliedGifts.isEmpty())
        assertTrue(skipped.giftDecisionResolved)
    }

    @Test
    fun `gift does not use a trigger with incompatible manual discount`() {
        val rule = giftRule(407L, target = MenuSemanticType.HOOKAH)
        val discountedTrigger =
            cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH)
                .copy(hasIncompatibleManualDiscount = true)

        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(discountedTrigger),
                activeRules = listOf(rule),
            )

        assertEquals(PromotionGiftOfferStatus.NO_GIFT, preview.giftOffer.status)
        assertTrue(preview.appliedGifts.isEmpty())
        assertTrue(preview.gifts.isEmpty())
    }

    @Test
    fun `selectable gift exposes only eligible allowlist and rejects stale selection`() {
        val rule =
            giftRule(
                402L,
                target = MenuSemanticType.HOOKAH,
                rewardMode = PromotionRewardMode.CHOICE_ITEMS,
                rewardOptions =
                    listOf(
                        rewardOption(4L, "Чай"),
                        rewardOption(5L, "Лимонад", available = false),
                        rewardOption(6L, "Морс", requiresOptionSelection = true),
                    ),
            )
        val cart = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH))

        val offered =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cart,
                activeRules = listOf(rule),
            )
        val stale =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cart,
                activeRules = listOf(rule),
                giftDecision =
                    PromotionGiftDecision(
                        action = PromotionGiftDecisionAction.SELECT_ITEM,
                        promotionId = 601L,
                        ruleId = 402L,
                        ruleVersion = 1,
                        selectedMenuItemId = 5L,
                    ),
            )

        assertEquals(PromotionGiftOfferStatus.GIFT_CHOICE_REQUIRED, offered.giftOffer.status)
        assertEquals(listOf(4L), offered.giftOffer.selectableRewardItems.map { it.menuItemId })
        assertEquals(PromotionGiftOfferStatus.GIFT_CHOICE_REQUIRED, stale.giftOffer.status)
        assertTrue(stale.appliedGifts.isEmpty())
        assertTrue(!stale.giftDecisionResolved)
    }

    @Test
    fun `unsupported required reward option and all unavailable allowlist are safe unavailable states`() {
        val cart = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH))
        val fixed =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cart,
                activeRules =
                    listOf(
                        giftRule(
                            id = 403L,
                            target = MenuSemanticType.HOOKAH,
                            rewardRequiresOptionSelection = true,
                        ),
                    ),
            )
        val choice =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = cart,
                activeRules =
                    listOf(
                        giftRule(
                            id = 404L,
                            target = MenuSemanticType.HOOKAH,
                            rewardMode = PromotionRewardMode.CHOICE_ITEMS,
                            rewardOptions =
                                listOf(
                                    rewardOption(4L, "Чай", available = false),
                                    rewardOption(5L, "Морс", available = false),
                                ),
                        ),
                    ),
            )

        assertEquals(PromotionGiftOfferStatus.GIFT_UNAVAILABLE, fixed.giftOffer.status)
        assertEquals(PromotionGiftUnavailableReason.REQUIRED_OPTION_UNSUPPORTED, fixed.giftOffer.unavailableReason)
        assertTrue(fixed.giftDecisionResolved)
        assertEquals(PromotionGiftOfferStatus.GIFT_UNAVAILABLE, choice.giftOffer.status)
        assertEquals(PromotionGiftUnavailableReason.NO_AVAILABLE_REWARD_ITEMS, choice.giftOffer.unavailableReason)
        assertTrue(choice.giftDecisionResolved)
    }

    @Test
    fun `removed trigger invalidates serialized decision and multiple triggers still yield one gift`() {
        val firstRule =
            giftRule(
                id = 405L,
                target = MenuSemanticType.HOOKAH,
                rewardPriceMinor = 30_000L,
                targets =
                    listOf(
                        PromotionRuleTarget(
                            id = 1L,
                            ruleId = 405L,
                            targetType = PromotionRuleTargetType.MENU_ITEM,
                            semanticType = null,
                            menuItemId = 1L,
                        ),
                    ),
            )
        val secondRule =
            giftRule(
                id = 406L,
                target = MenuSemanticType.HOOKAH,
                rewardItemName = "Лимонад",
                rewardPriceMinor = 40_000L,
                targets =
                    listOf(
                        PromotionRuleTarget(
                            id = 2L,
                            ruleId = 406L,
                            targetType = PromotionRuleTargetType.MENU_ITEM,
                            semanticType = null,
                            menuItemId = 2L,
                        ),
                    ),
            )
        val both =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems =
                    listOf(
                        cartItem(1L, "Кальян", 2, 100_000L, MenuSemanticType.HOOKAH),
                        cartItem(2L, "Премиум кальян", 1, 200_000L, MenuSemanticType.HOOKAH),
                    ),
                activeRules = listOf(firstRule, secondRule),
            )
        val removed =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(2L, "Премиум кальян", 1, 200_000L, MenuSemanticType.HOOKAH)),
                activeRules = listOf(firstRule),
                giftDecision =
                    PromotionGiftDecision(
                        action = PromotionGiftDecisionAction.ACCEPT_FIXED,
                        promotionId = 601L,
                        ruleId = 405L,
                        ruleVersion = 1,
                    ),
            )

        assertEquals(406L, both.giftOffer.ruleId)
        assertEquals(1, both.gifts.size)
        assertEquals(PromotionGiftOfferStatus.NO_GIFT, removed.giftOffer.status)
        assertTrue(removed.appliedGifts.isEmpty())
        assertTrue(!removed.giftDecisionResolved)
    }

    private fun cartItem(
        id: Long,
        name: String,
        qty: Int,
        priceMinor: Long,
        type: MenuSemanticType,
        menuCategoryId: Long? = null,
    ): PromotionRuleCartItem =
        PromotionRuleCartItem(
            menuItemId = id,
            itemName = name,
            qty = qty,
            priceMinor = priceMinor,
            currency = "RUB",
            effectiveType = type,
            menuCategoryId = menuCategoryId,
        )

    private fun rule(
        id: Long,
        target: MenuSemanticType,
        percent: Int,
        status: VenuePromotionStatus = VenuePromotionStatus.ACTIVE,
        startsTime: LocalTime? = null,
        endsTime: LocalTime? = null,
        daysOfWeek: Set<Int>? = null,
        weekdayWindows: List<PromotionWeekdayWindow> = emptyList(),
        targets: List<PromotionRuleTarget> = emptyList(),
        executableTargetType: PromotionRuleTargetType? = null,
        priority: Int = 100,
        stackable: Boolean = false,
        promotionStartsAt: Instant? = null,
        promotionEndsAt: Instant? = null,
    ): VenuePromotionRule =
        VenuePromotionRule(
            id = id,
            promotionId = 501L,
            promotionTitle = "Счастливые часы",
            venueId = 10L,
            ruleType = PromotionRuleType.HAPPY_HOURS_PERCENT,
            targetType = PromotionRuleTargetType.CATEGORY_TYPE,
            targetValue = target,
            discountPercent = percent,
            startsTime = startsTime,
            endsTime = endsTime,
            daysOfWeek = daysOfWeek,
            weekdayWindows = weekdayWindows,
            executableTargetType = executableTargetType,
            promotionStartsAt = promotionStartsAt,
            promotionEndsAt = promotionEndsAt,
            status = status,
            priority = priority,
            stackable = stackable,
            targets = targets,
            createdByUserId = 200L,
            createdAt = Instant.parse("2026-05-14T10:00:00Z"),
            updatedAt = Instant.parse("2026-05-14T10:00:00Z"),
        )

    private fun giftRule(
        id: Long,
        target: MenuSemanticType,
        status: VenuePromotionStatus = VenuePromotionStatus.ACTIVE,
        rewardAvailable: Boolean = true,
        rewardMode: PromotionRewardMode = PromotionRewardMode.FIXED_ITEM,
        rewardOptions: List<PromotionRuleRewardOption> = emptyList(),
        targets: List<PromotionRuleTarget> = emptyList(),
        startsTime: LocalTime? = null,
        endsTime: LocalTime? = null,
        daysOfWeek: Set<Int>? = null,
        rewardItemName: String = "Чай",
        rewardPriceMinor: Long = 30_000L,
        rewardRequiresOptionSelection: Boolean = false,
        priority: Int = 100,
        stackable: Boolean = false,
    ): VenuePromotionRule =
        VenuePromotionRule(
            id = id,
            promotionId = 601L,
            promotionTitle = "Чай к кальяну",
            venueId = 10L,
            ruleType = PromotionRuleType.GIFT_WITH_ITEM,
            targetType = PromotionRuleTargetType.CATEGORY_TYPE,
            targetValue = target,
            discountPercent = 0,
            startsTime = startsTime,
            endsTime = endsTime,
            daysOfWeek = daysOfWeek,
            status = status,
            priority = priority,
            stackable = stackable,
            targets = targets,
            reward =
                PromotionRuleReward(
                    id = 301L,
                    ruleId = id,
                    rewardMenuItemId = 3L,
                    rewardMenuItemName = rewardItemName,
                    rewardQty = 1,
                    maxRewardsPerBatch = 1,
                    priceMinor = rewardPriceMinor,
                    currency = "RUB",
                    isAvailable = rewardAvailable,
                    requiresOptionSelection = rewardRequiresOptionSelection,
                    rewardMode = rewardMode,
                    options = rewardOptions,
                ),
            createdByUserId = 200L,
            createdAt = Instant.parse("2026-05-14T10:00:00Z"),
            updatedAt = Instant.parse("2026-05-14T10:00:00Z"),
        )

    private fun rewardOption(
        itemId: Long,
        name: String,
        available: Boolean = true,
        requiresOptionSelection: Boolean = false,
    ): PromotionRuleRewardOption =
        PromotionRuleRewardOption(
            id = itemId,
            rewardId = 301L,
            menuItemId = itemId,
            menuItemName = name,
            priceMinor = 30_000L,
            currency = "RUB",
            isAvailable = available,
            requiresOptionSelection = requiresOptionSelection,
        )
}
