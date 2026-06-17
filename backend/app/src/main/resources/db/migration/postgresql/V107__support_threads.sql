CREATE TABLE IF NOT EXISTS support_threads (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    guest_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    category VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    booking_id BIGINT REFERENCES bookings(id) ON DELETE SET NULL,
    order_id BIGINT REFERENCES orders(id) ON DELETE SET NULL,
    table_session_id BIGINT REFERENCES table_sessions(id) ON DELETE SET NULL,
    title TEXT NOT NULL,
    last_message_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_support_threads_category
        CHECK (category IN ('BOOKING', 'GENERAL', 'ORDER', 'TABLE', 'PLATFORM')),
    CONSTRAINT chk_support_threads_status
        CHECK (status IN ('OPEN', 'RESOLVED', 'CLOSED'))
);

CREATE INDEX IF NOT EXISTS idx_support_threads_venue_updated
    ON support_threads (venue_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_support_threads_guest_updated
    ON support_threads (guest_user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_support_threads_booking
    ON support_threads (venue_id, booking_id);

CREATE TABLE IF NOT EXISTS support_messages (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    thread_id BIGINT NOT NULL REFERENCES support_threads(id) ON DELETE CASCADE,
    author_user_id BIGINT,
    author_role VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    text TEXT NOT NULL,
    telegram_message_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_support_messages_author_role
        CHECK (author_role IN ('GUEST', 'VENUE', 'PLATFORM', 'SYSTEM')),
    CONSTRAINT chk_support_messages_source
        CHECK (source IN ('GUEST_BOT', 'GUEST_MINIAPP', 'VENUE_MINIAPP', 'STAFF_CHAT', 'SYSTEM'))
);

CREATE INDEX IF NOT EXISTS idx_support_messages_thread_created
    ON support_messages (thread_id, created_at ASC);
