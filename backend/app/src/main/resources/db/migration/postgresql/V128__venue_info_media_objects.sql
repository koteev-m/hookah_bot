ALTER TABLE venue_info_sections ADD COLUMN media_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE venue_info_section_media ADD COLUMN public_id VARCHAR(36);
ALTER TABLE venue_info_section_media ADD COLUMN source_type VARCHAR(24) NOT NULL DEFAULT 'TELEGRAM_FILE';
ALTER TABLE venue_info_section_media ADD COLUMN object_key VARCHAR(160);
ALTER TABLE venue_info_section_media ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'READY';
ALTER TABLE venue_info_section_media ADD COLUMN is_visible BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE venue_info_section_media ADD COLUMN mime_type VARCHAR(64);
ALTER TABLE venue_info_section_media ADD COLUMN size_bytes INT;
ALTER TABLE venue_info_section_media ADD COLUMN checksum_sha256 VARCHAR(64);
ALTER TABLE venue_info_section_media ADD COLUMN input_sha256 VARCHAR(64);
ALTER TABLE venue_info_section_media ADD COLUMN created_by_user_id BIGINT;
ALTER TABLE venue_info_section_media ADD COLUMN request_id VARCHAR(36);
ALTER TABLE venue_info_section_media ADD COLUMN expected_revision BIGINT;
ALTER TABLE venue_info_section_media ADD COLUMN replaces_id BIGINT;
ALTER TABLE venue_info_section_media ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
CREATE UNIQUE INDEX venue_info_media_public_id ON venue_info_section_media(public_id);
CREATE UNIQUE INDEX venue_info_media_request ON venue_info_section_media(section_id, created_by_user_id, request_id);
-- Technical deletion journal, not a second attachment list. Survives parent cascades.
CREATE TABLE venue_info_media_cleanup (
    object_key VARCHAR(160) PRIMARY KEY,
    public_id VARCHAR(36) NOT NULL,
    actor_id BIGINT NOT NULL,
    venue_id BIGINT NOT NULL,
    section_id BIGINT NOT NULL,
    due_at TIMESTAMP WITH TIME ZONE,
    deleted_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX venue_info_media_cleanup_due ON venue_info_media_cleanup(due_at);
CREATE UNIQUE INDEX venue_info_media_object_key ON venue_info_section_media(object_key);
ALTER TABLE venue_info_section_media ADD CONSTRAINT venue_info_media_source_check CHECK (source_type IN ('TELEGRAM_FILE','OBJECT_STORAGE'));
ALTER TABLE venue_info_section_media ADD CONSTRAINT venue_info_media_status_check CHECK (status IN ('UPLOADING','READY','FAILED','DELETED'));
