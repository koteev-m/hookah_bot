ALTER TABLE venue_settings
    ADD COLUMN team_schedule_module_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN guest_team_visible BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN today_staff_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD CONSTRAINT chk_venue_settings_today_staff_source
        CHECK (today_staff_source IN ('MANUAL', 'SCHEDULE'));
