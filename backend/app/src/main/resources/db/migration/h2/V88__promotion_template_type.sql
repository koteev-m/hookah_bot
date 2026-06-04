ALTER TABLE venue_promotions
    ADD COLUMN IF NOT EXISTS template_type VARCHAR(32) NOT NULL DEFAULT 'TEXT_ONLY';

ALTER TABLE venue_promotions
    DROP CONSTRAINT IF EXISTS venue_promotions_template_type_check;

ALTER TABLE venue_promotions
    ADD CONSTRAINT venue_promotions_template_type_check
        CHECK (
            template_type IN (
                'TEXT_ONLY',
                'BANNER',
                'HAPPY_HOURS_PERCENT',
                'BIRTHDAY_DISCOUNT',
                'COMBO',
                'GIFT_WITH_ITEM',
                'NEW_GUEST_OFFER',
                'PROMO_CODE',
                'LOYALTY_NTH_HOOKAH'
            )
        );
