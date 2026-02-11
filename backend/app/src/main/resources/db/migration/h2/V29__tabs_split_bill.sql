CREATE TABLE IF NOT EXISTS tab (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL,
    table_session_id BIGINT NOT NULL,
    type VARCHAR(16) NOT NULL,
    owner_user_id BIGINT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT fk_tab_venue FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE,
    CONSTRAINT fk_tab_table_session FOREIGN KEY (table_session_id) REFERENCES table_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_tab_owner FOREIGN KEY (owner_user_id) REFERENCES users(telegram_user_id) ON DELETE SET NULL,
    CONSTRAINT chk_tab_type CHECK (type IN ('PERSONAL', 'SHARED')),
    CONSTRAINT chk_tab_status CHECK (status IN ('ACTIVE', 'CLOSED'))
);


CREATE INDEX IF NOT EXISTS idx_tab_session
    ON tab (table_session_id, status);

CREATE TABLE IF NOT EXISTS tab_member (
    tab_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tab_id, user_id),
    CONSTRAINT fk_tab_member_tab FOREIGN KEY (tab_id) REFERENCES tab(id) ON DELETE CASCADE,
    CONSTRAINT fk_tab_member_user FOREIGN KEY (user_id) REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    CONSTRAINT chk_tab_member_role CHECK (role IN ('OWNER', 'MEMBER'))
);

CREATE INDEX IF NOT EXISTS idx_tab_member_user
    ON tab_member (user_id, tab_id);

CREATE TABLE IF NOT EXISTS tab_invite (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tab_id BIGINT NOT NULL,
    token VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tab_invite_tab FOREIGN KEY (tab_id) REFERENCES tab(id) ON DELETE CASCADE,
    CONSTRAINT fk_tab_invite_creator FOREIGN KEY (created_by) REFERENCES users(telegram_user_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_tab_invite_active
    ON tab_invite (tab_id, expires_at);

ALTER TABLE order_batches
    ADD COLUMN IF NOT EXISTS tab_id BIGINT NULL;

ALTER TABLE order_batches
    ADD CONSTRAINT IF NOT EXISTS fk_order_batches_tab
    FOREIGN KEY (tab_id) REFERENCES tab(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_order_batches_tab
    ON order_batches (tab_id);
