CREATE TABLE IF NOT EXISTS staff_profiles (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    linked_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL,
    display_name VARCHAR(120) NOT NULL,
    role_label VARCHAR(120) NULL,
    subtype VARCHAR(32) NOT NULL DEFAULT 'other',
    photo_ref TEXT NULL,
    bio TEXT NULL,
    tags TEXT NULL,
    is_guest_visible BOOLEAN NOT NULL DEFAULT FALSE,
    created_by_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    updated_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL,
    published_at TIMESTAMPTZ NULL,
    disabled_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT staff_profiles_subtype_check
        CHECK (subtype IN ('hookah_master', 'waiter', 'admin', 'other')),
    CONSTRAINT staff_profiles_display_name_not_blank
        CHECK (char_length(btrim(display_name)) > 0),
    CONSTRAINT staff_profiles_venue_id_id_unique UNIQUE (venue_id, id)
);

CREATE INDEX IF NOT EXISTS idx_staff_profiles_venue ON staff_profiles (venue_id);
CREATE INDEX IF NOT EXISTS idx_staff_profiles_linked_user ON staff_profiles (linked_user_id);
CREATE INDEX IF NOT EXISTS idx_staff_profiles_guest_visible
    ON staff_profiles (venue_id, is_guest_visible, published_at)
    WHERE disabled_at IS NULL;

CREATE TABLE IF NOT EXISTS staff_shifts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL,
    staff_profile_id BIGINT NOT NULL,
    shift_date DATE NOT NULL,
    starts_at TIME NULL,
    ends_at TIME NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    is_guest_visible BOOLEAN NOT NULL DEFAULT TRUE,
    manually_marked_active BOOLEAN NOT NULL DEFAULT FALSE,
    created_by_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    updated_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT staff_shifts_profile_same_venue_fk
        FOREIGN KEY (venue_id, staff_profile_id)
        REFERENCES staff_profiles (venue_id, id)
        ON DELETE CASCADE,
    CONSTRAINT staff_shifts_status_check
        CHECK (status IN ('scheduled', 'active', 'completed', 'canceled')),
    CONSTRAINT staff_shifts_one_per_profile_date UNIQUE (staff_profile_id, shift_date)
);

CREATE INDEX IF NOT EXISTS idx_staff_shifts_venue_date ON staff_shifts (venue_id, shift_date);
CREATE INDEX IF NOT EXISTS idx_staff_shifts_profile ON staff_shifts (staff_profile_id);
