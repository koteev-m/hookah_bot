CREATE TABLE IF NOT EXISTS tab (
    id BIGSERIAL PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    table_session_id BIGINT NOT NULL REFERENCES table_sessions(id) ON DELETE CASCADE,
    type VARCHAR(16) NOT NULL CHECK (type IN ('PERSONAL', 'SHARED')),
    owner_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CLOSED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tab_personal_owner
    ON tab (table_session_id, owner_user_id)
    WHERE type = 'PERSONAL' AND status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_tab_session
    ON tab (table_session_id, status);

CREATE TABLE IF NOT EXISTS tab_member (
    tab_id BIGINT NOT NULL REFERENCES tab(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tab_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_tab_member_user
    ON tab_member (user_id, tab_id);

CREATE TABLE IF NOT EXISTS tab_invite (
    id BIGSERIAL PRIMARY KEY,
    tab_id BIGINT NOT NULL REFERENCES tab(id) ON DELETE CASCADE,
    token VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_tab_invite_active
    ON tab_invite (tab_id, expires_at);

ALTER TABLE order_batches
    ADD COLUMN IF NOT EXISTS tab_id BIGINT NULL REFERENCES tab(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_order_batches_tab
    ON order_batches (tab_id);
