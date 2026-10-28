-- Staff photo ledger deliberately survives profile/venue deletion for durable byte cleanup.
CREATE TABLE staff_profile_photos (
    id VARCHAR(36) PRIMARY KEY,
    venue_id BIGINT NOT NULL,
    profile_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    object_key VARCHAR(100) NOT NULL UNIQUE,
    checksum VARCHAR(64) NOT NULL,
    byte_count INTEGER NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    reserved_revision BIGINT NOT NULL,
    linked_user_id BIGINT NULL,
    state VARCHAR(24) NOT NULL CHECK (state IN ('UPLOADING','PENDING','APPROVED','HIDDEN','DELETE_PENDING','DELETED')),
    consent_kind VARCHAR(24) NULL CHECK (consent_kind IN ('SELF','OWNER_RECORDED')),
    consent_actor_id BIGINT NULL,
    consent_at TIMESTAMP WITH TIME ZONE NULL,
    consent_policy VARCHAR(32) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    cleanup_after TIMESTAMP WITH TIME ZONE NOT NULL,
    cleanup_attempts INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMP WITH TIME ZONE NULL,
    UNIQUE (venue_id, profile_id, actor_id, request_id),
    CHECK (byte_count > 0 AND byte_count <= 2097152),
    CHECK (width > 0 AND width <= 1024 AND height > 0 AND height <= 1024)
);
CREATE INDEX idx_staff_photos_cleanup ON staff_profile_photos (state, cleanup_after);
CREATE INDEX idx_staff_photos_profile ON staff_profile_photos (venue_id, profile_id);
CREATE TABLE staff_profile_photo_state (
    profile_id BIGINT PRIMARY KEY,
    venue_id BIGINT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    current_id VARCHAR(36) NULL,
    candidate_id VARCHAR(36) NULL,
    FOREIGN KEY (venue_id, profile_id) REFERENCES staff_profiles (venue_id, id) ON DELETE CASCADE
);
