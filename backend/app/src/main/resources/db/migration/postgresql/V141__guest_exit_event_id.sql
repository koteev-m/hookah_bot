ALTER TABLE guest_table_session_exits
    ADD COLUMN exit_event_id BIGINT GENERATED ALWAYS AS IDENTITY (NO CYCLE);

ALTER TABLE guest_table_session_exits
    ADD CONSTRAINT uq_guest_table_session_exit_event UNIQUE (exit_event_id);
