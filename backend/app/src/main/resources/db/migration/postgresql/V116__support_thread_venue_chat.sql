ALTER TABLE support_threads
    DROP CONSTRAINT IF EXISTS chk_support_threads_thread_type;

ALTER TABLE support_threads
    ADD CONSTRAINT chk_support_threads_thread_type
        CHECK (thread_type IN ('BOOKING_THREAD', 'SUPPORT_TICKET', 'VENUE_CHAT'));
