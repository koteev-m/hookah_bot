ALTER TABLE venue_members
    DROP CONSTRAINT IF EXISTS venue_members_role_check;

ALTER TABLE venue_members
    ADD CONSTRAINT venue_members_role_check
        CHECK (role IN ('OWNER', 'ADMIN', 'MANAGER', 'STAFF'));
