CREATE TABLE IF NOT EXISTS venue_info_section_media (
    id BIGSERIAL PRIMARY KEY,
    section_id BIGINT NOT NULL REFERENCES venue_info_sections(id) ON DELETE CASCADE,
    media_type VARCHAR(16) NOT NULL CHECK (media_type IN ('image', 'pdf')),
    telegram_file_id TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_venue_info_section_media_section_sort
    ON venue_info_section_media (section_id, sort_order, id);
