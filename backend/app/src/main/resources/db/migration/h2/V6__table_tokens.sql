CREATE TABLE venue_tables (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    table_number INT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (venue_id, table_number)
);

CREATE INDEX idx_venue_tables_venue ON venue_tables (venue_id);

CREATE TABLE table_tokens (
    token VARCHAR(64) PRIMARY KEY,
    table_id BIGINT NOT NULL REFERENCES venue_tables(id) ON DELETE CASCADE,
    is_active BOOLEAN NOT NULL DEFAULT true,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP WITH TIME ZONE NULL,
    CHECK (char_length(token) <= 64)
);

CREATE UNIQUE INDEX uq_table_tokens_active ON table_tokens (table_id, is_active);
