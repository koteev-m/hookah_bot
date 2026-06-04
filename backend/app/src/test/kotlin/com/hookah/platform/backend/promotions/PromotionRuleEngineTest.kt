package com.hookah.platform.backend.promotions

import com.hookah.platform.backend.miniapp.venue.menu.MenuSemanticType
import com.hookah.platform.backend.telegram.db.PromotionRewardMode
import com.hookah.platform.backend.telegram.db.PromotionRuleReward
import com.hookah.platform.backend.telegram.db.PromotionRuleRewardOption
import com.hookah.platform.backend.telegram.db.PromotionRuleTarget
import com.hookah.platform.backend.telegram.db.PromotionRuleTargetType
import com.hookah.platform.backend.telegram.db.PromotionRuleType
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
    fun `stackable percent with stackable gift applies both`() {
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

        assertEquals(1, preview.adjustments.size)
        assertEquals(20_000L, preview.totalPreviewDiscountMinor)
        assertEquals(1, preview.gifts.size)
        assertEquals(201L, preview.gifts.single().ruleId)
    }

    @Test
    fun `stackable percent discounts are capped at item gross`() {
        val preview =
            PromotionRuleEngine.preview(
                venueId = 10L,
                now = Instant.parse("2026-05-14T12:00:00Z"),
                venueZoneId = ZoneId.of("Europe/Moscow"),
                cartItems = listOf(cartItem(1L, "Кальян", 1, 100_000L, MenuSemanticType.HOOKAH)),
                activeRules =
                    listOf(
                        rule(101L, target = MenuSemanticType.HOOKAH, percent = 80, stackable = true),
                        rule(102L, target = MenuSemanticType.HOOKAH, percent = 50, stackable = true),
                    ),
            )

        assertEquals(2, preview.adjustments.size)
        assertEquals(100_000L, preview.totalPreviewDiscountMinor)
        assertEquals(80_000L, preview.adjustments.first { it.ruleId == 101L }.discountMinor)
        assertEquals(20_000L, preview.adjustments.first { it.ruleId == 102L }.discountMinor)
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
                            daysOfWeek = setOf(5),
                        ),
                    ),
            )

        assertEquals(20_000L, preview.totalPreviewDiscountMinor)
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

    private fun cartItem(
        id: Long,
        name: String,
        qty: Int,
        priceMinor: Long,
        type: MenuSemanticType,
    ): PromotionRuleCartItem =
        PromotionRuleCartItem(
            menuItemId = id,
            itemName = name,
            qty = qty,
            priceMinor = priceMinor,
            currency = "RUB",
            effectiveType = type,
        )

    private fun rule(
        id: Long,
        target: MenuSemanticType,
        percent: Int,
        status: VenuePromotionStatus = VenuePromotionStatus.ACTIVE,
        startsTime: LocalTime? = null,
        endsTime: LocalTime? = null,
        daysOfWeek: Set<Int>? = null,
        targets: List<PromotionRuleTarget> = emptyList(),
        priority: Int = 100,
        stackable: Boolean = false,
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
    ): PromotionRuleRewardOption =
        PromotionRuleRewardOption(
            id = itemId,
            rewardId = 301L,
            menuItemId = itemId,
            menuItemName = name,
            priceMinor = 30_000L,
            currency = "RUB",
            isAvailable = available,
        )
}
