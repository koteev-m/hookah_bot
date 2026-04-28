CREATE TABLE users (
    telegram_user_id BIGINT PRIMARY KEY,
    username TEXT NULL,
    first_name TEXT NULL,
    last_name TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE venues (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name TEXT NOT NULL,
    city TEXT NULL,
    address TEXT NULL,
    status TEXT NOT NULL
        CHECK (
            status IN (
                'draft',
                'onboarding',
                'active_published',
                'active_hidden',
                'paused_by_owner',
                'suspended_by_platform',
                'archived',
                'deletion_requested',
                'deleted'
            )
        ),
    features JSONB NOT NULL DEFAULT '{}'::jsonb,
    ui_layout JSONB NOT NULL DEFAULT '{}'::jsonb,
    staff_chat_id BIGINT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_venues_status ON venues (status);
CREATE INDEX idx_venues_name ON venues (name);
CREATE INDEX idx_venues_city ON venues (city);

CREATE TABLE venue_members (
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MANAGER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (venue_id, user_id)
);

CREATE INDEX idx_venue_members_user ON venue_members (user_id);

CREATE TABLE venue_tables (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    table_number INT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (venue_id, table_number)
);

CREATE INDEX idx_venue_tables_venue ON venue_tables (venue_id);

CREATE TABLE table_tokens (
    token VARCHAR(64) PRIMARY KEY,
    table_id BIGINT NOT NULL REFERENCES venue_tables(id) ON DELETE CASCADE,
    is_active BOOLEAN NOT NULL DEFAULT true,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ NULL,
    CHECK (char_length(token) <= 64)
);

CREATE UNIQUE INDEX uq_table_tokens_active ON table_tokens (table_id) WHERE is_active;

CREATE TABLE menu_categories (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_menu_categories_venue ON menu_categories (venue_id);
CREATE INDEX idx_menu_categories_venue_sort ON menu_categories (venue_id, sort_order);

CREATE TABLE menu_items (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    category_id BIGINT NOT NULL REFERENCES menu_categories(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    description TEXT NULL,
    price_minor INT NOT NULL CHECK (price_minor >= 0),
    currency CHAR(3) NOT NULL DEFAULT 'RUB',
    is_available BOOLEAN NOT NULL DEFAULT true,
    options JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_menu_items_venue ON menu_items (venue_id);
CREATE INDEX idx_menu_items_category ON menu_items (category_id);

CREATE TABLE orders (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    table_id BIGINT NOT NULL REFERENCES venue_tables(id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'CLOSED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_orders_active_table ON orders (table_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_orders_venue ON orders (venue_id);
CREATE INDEX idx_orders_status ON orders (status);

CREATE TABLE order_batches (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    author_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL,
    source TEXT NOT NULL CHECK (source IN ('MINIAPP', 'CHAT', 'STAFF')),
    status TEXT NOT NULL CHECK (
        status IN ('NEW', 'ACCEPTED', 'PREPARING', 'DELIVERING', 'DELIVERED', 'REJECTED')
    ),
    items_snapshot JSONB NOT NULL DEFAULT '[]'::jsonb,
    guest_comment TEXT NULL
);

CREATE INDEX idx_order_batches_order ON order_batches (order_id);
CREATE INDEX idx_order_batches_status ON order_batches (status);

CREATE TABLE billing_plans (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    price_minor INT NOT NULL CHECK (price_minor >= 0),
    currency CHAR(3) NOT NULL DEFAULT 'RUB',
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE venue_subscriptions (
    venue_id BIGINT PRIMARY KEY REFERENCES venues(id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK (status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'SUSPENDED')),
    plan_id BIGINT NULL REFERENCES billing_plans(id) ON DELETE SET NULL,
    trial_end_date DATE NULL,
    paid_start_date DATE NULL,
    price_override_minor INT NULL CHECK (price_override_minor >= 0),
    next_due_date DATE NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE invoices (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    amount_minor INT NOT NULL CHECK (amount_minor >= 0),
    currency CHAR(3) NOT NULL DEFAULT 'RUB',
    status TEXT NOT NULL CHECK (status IN ('DUE', 'PAID', 'CANCELLED')),
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at TIMESTAMPTZ NULL,
    external_ref TEXT NULL
);

CREATE INDEX idx_invoices_venue ON invoices (venue_id);
CREATE INDEX idx_invoices_status ON invoices (status);

CREATE TABLE audit_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL,
    venue_id BIGINT NULL REFERENCES venues(id) ON DELETE SET NULL,
    action TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_audit_log_venue ON audit_log (venue_id);
CREATE INDEX idx_audit_log_action ON audit_log (action);
