ALTER TABLE visit_feedback
    ADD COLUMN IF NOT EXISTS comment_staff_notified_at TIMESTAMPTZ NULL;
