ALTER TABLE venues ADD COLUMN menu_featured_version BIGINT NOT NULL DEFAULT 0
    CHECK (menu_featured_version >= 0);

CREATE TABLE menu_featured_operations (
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    operation_id VARCHAR(36) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    applied_version BIGINT NOT NULL CHECK (applied_version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (venue_id, operation_id)
);
