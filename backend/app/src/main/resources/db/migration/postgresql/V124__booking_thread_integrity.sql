SET LOCAL lock_timeout = '30s';
SET LOCAL statement_timeout = '5min';

LOCK TABLE
    bookings,
    support_threads,
    support_messages,
    support_thread_reads,
    audit_log
IN EXCLUSIVE MODE;

CREATE TEMPORARY TABLE booking_thread_reference_inventory ON COMMIT DROP AS
SELECT
    'FOREIGN_KEY'::TEXT AS reference_kind,
    LOWER(fk.table_name) AS table_name,
    LOWER(fk.column_name) AS column_name,
    NULL::TEXT AS discriminator
FROM information_schema.referential_constraints reference
JOIN information_schema.key_column_usage fk
  ON fk.constraint_catalog = reference.constraint_catalog
 AND fk.constraint_schema = reference.constraint_schema
 AND fk.constraint_name = reference.constraint_name
JOIN information_schema.key_column_usage target
  ON target.constraint_catalog = reference.unique_constraint_catalog
 AND target.constraint_schema = reference.unique_constraint_schema
 AND target.constraint_name = reference.unique_constraint_name
 AND target.ordinal_position = fk.position_in_unique_constraint
WHERE LOWER(target.table_schema) = LOWER(CURRENT_SCHEMA())
  AND LOWER(target.table_name) = 'support_threads'
  AND LOWER(target.column_name) = 'id'
UNION ALL
SELECT
    'LOGICAL_AUDIT',
    'audit_log',
    'entity_id',
    'support_ticket';

CREATE TEMPORARY TABLE booking_thread_expected_json_inventory (
    table_name TEXT NOT NULL,
    column_name TEXT NOT NULL,
    PRIMARY KEY (table_name, column_name)
) ON COMMIT DROP;

INSERT INTO booking_thread_expected_json_inventory (table_name, column_name)
VALUES
    ('analytics_events', 'payload_json'),
    ('audit_log', 'payload_json'),
    ('billing_invoices', 'provider_raw_payload'),
    ('billing_notifications', 'payload_json'),
    ('billing_payments', 'raw_payload'),
    ('guest_batch_idempotency', 'response_snapshot'),
    ('menu_items', 'options'),
    ('order_batches', 'items_snapshot'),
    ('order_promotion_applications', 'schedule_snapshot_json'),
    ('order_promotion_applications', 'target_snapshot_json'),
    ('telegram_dialog_state', 'payload'),
    ('telegram_inbound_updates', 'payload_json'),
    ('telegram_outbox', 'payload_json'),
    ('venues', 'features'),
    ('venues', 'ui_layout'),
    ('visit_feedback', 'tags_json');

CREATE TEMPORARY TABLE booking_thread_audit_payload_inventory ON COMMIT DROP AS
SELECT
    audit.id AS audit_id,
    audit.payload_json IS JSON OBJECT AS payload_is_object,
    CASE
        WHEN audit.payload_json IS JSON
        THEN (audit.payload_json::JSONB)::TEXT
        ELSE audit.payload_json
    END AS normalized_payload,
    ticket.ticket_id_count,
    ticket.ticket_id_value
FROM audit_log audit
CROSS JOIN LATERAL (
    SELECT
        COUNT(*) FILTER (WHERE entry.key = 'ticketId') AS ticket_id_count,
        MIN(entry.value::TEXT) FILTER (WHERE entry.key = 'ticketId') AS ticket_id_value
    FROM JSON_EACH(
        CASE
            WHEN audit.payload_json IS JSON OBJECT
            THEN audit.payload_json::JSON
            ELSE '{}'::JSON
        END
    ) entry
) ticket;

CREATE TEMPORARY TABLE booking_thread_unknown_audit_reference_keys ON COMMIT DROP AS
WITH RECURSIVE audit_nodes(audit_id, node, depth) AS (
    SELECT audit.id, audit.payload_json::JSONB, 0
    FROM audit_log audit
    WHERE audit.payload_json IS JSON
    UNION ALL
    SELECT parent.audit_id, child.value, parent.depth + 1
    FROM audit_nodes parent
    CROSS JOIN LATERAL (
        SELECT object_child.value
        FROM JSONB_EACH(
            CASE WHEN JSONB_TYPEOF(parent.node) = 'object' THEN parent.node ELSE '{}'::JSONB END
        ) object_child
        UNION ALL
        SELECT array_child.value
        FROM JSONB_ARRAY_ELEMENTS(
            CASE WHEN JSONB_TYPEOF(parent.node) = 'array' THEN parent.node ELSE '[]'::JSONB END
        ) array_child
    ) child
), audit_keys AS (
    SELECT parent.audit_id, parent.depth, entry.key
    FROM audit_nodes parent
    CROSS JOIN LATERAL JSONB_EACH(
        CASE WHEN JSONB_TYPEOF(parent.node) = 'object' THEN parent.node ELSE '{}'::JSONB END
    ) entry
), normalized_keys AS (
    SELECT
        audit_id,
        depth,
        key,
        REGEXP_REPLACE(LOWER(NORMALIZE(key, NFKC)), '[_.[:space:]-]', '', 'g') AS compact_key
    FROM audit_keys
)
SELECT audit_id, depth, key
FROM normalized_keys
WHERE NOT (depth = 0 AND key = 'ticketId')
  AND compact_key ~ '(thread|ticket|conversation).*(ids|refs|id|ref)';

DO $$
DECLARE
    inbound_reference_count BIGINT;
    message_reference_count BIGINT;
    read_reference_count BIGINT;
    audit_reference_count BIGINT;
BEGIN
    SELECT
        COUNT(*),
        COUNT(*) FILTER (
            WHERE reference_kind = 'FOREIGN_KEY'
              AND table_name = 'support_messages'
              AND column_name = 'thread_id'
        ),
        COUNT(*) FILTER (
            WHERE reference_kind = 'FOREIGN_KEY'
              AND table_name = 'support_thread_reads'
              AND column_name = 'thread_id'
        ),
        COUNT(*) FILTER (
            WHERE reference_kind = 'LOGICAL_AUDIT'
              AND table_name = 'audit_log'
              AND column_name = 'entity_id'
              AND discriminator = 'support_ticket'
        )
    INTO
        inbound_reference_count,
        message_reference_count,
        read_reference_count,
        audit_reference_count
    FROM booking_thread_reference_inventory;

    IF inbound_reference_count <> 3
       OR message_reference_count <> 1
       OR read_reference_count <> 1
       OR audit_reference_count <> 1
    THEN
        RAISE EXCEPTION
            'Unexpected support thread reference inventory';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM booking_thread_unknown_audit_reference_keys
    ) THEN
        RAISE EXCEPTION
            'Audit payload contains an unknown recursive thread reference key';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns columns
        WHERE LOWER(columns.table_schema) = LOWER(CURRENT_SCHEMA())
          AND REGEXP_REPLACE(LOWER(columns.column_name), '[^a-z0-9]', '', 'g') IN (
              'threadid',
              'supportthreadid',
              'bookingthreadid',
              'ticketid'
          )
          AND NOT (
              LOWER(columns.table_name) IN ('support_messages', 'support_thread_reads')
              AND LOWER(columns.column_name) = 'thread_id'
          )
    ) THEN
        RAISE EXCEPTION
            'Unexpected explicit support thread reference inventory';
    END IF;

    IF EXISTS (
        SELECT table_name, column_name
        FROM (
            SELECT LOWER(columns.table_name) AS table_name, LOWER(columns.column_name) AS column_name
            FROM information_schema.columns columns
            WHERE LOWER(columns.table_schema) = LOWER(CURRENT_SCHEMA())
              AND (
                  LOWER(columns.data_type) IN ('json', 'jsonb')
                  OR LOWER(columns.column_name) LIKE '%json%'
                  OR LOWER(columns.column_name) IN (
                      'payload',
                      'raw_payload',
                      'provider_raw_payload',
                      'features',
                      'ui_layout',
                      'options',
                      'items_snapshot',
                      'response_snapshot'
                  )
              )
        ) actual
        EXCEPT
        SELECT expected.table_name, expected.column_name
        FROM booking_thread_expected_json_inventory expected
    ) OR EXISTS (
        SELECT expected.table_name, expected.column_name
        FROM booking_thread_expected_json_inventory expected
        EXCEPT
        SELECT LOWER(columns.table_name), LOWER(columns.column_name)
        FROM information_schema.columns columns
        WHERE LOWER(columns.table_schema) = LOWER(CURRENT_SCHEMA())
          AND (
              LOWER(columns.data_type) IN ('json', 'jsonb')
              OR LOWER(columns.column_name) LIKE '%json%'
              OR LOWER(columns.column_name) IN (
                  'payload',
                  'raw_payload',
                  'provider_raw_payload',
                  'features',
                  'ui_layout',
                  'options',
                  'items_snapshot',
                  'response_snapshot'
              )
          )
    ) THEN
        RAISE EXCEPTION
            'Unexpected durable JSON reference inventory';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM (
            SELECT features::TEXT AS payload FROM venues
            UNION ALL SELECT ui_layout::TEXT FROM venues
            UNION ALL SELECT options::TEXT FROM menu_items
            UNION ALL SELECT items_snapshot::TEXT FROM order_batches
            UNION ALL SELECT payload::TEXT FROM telegram_dialog_state
            UNION ALL SELECT raw_payload FROM billing_payments
            UNION ALL SELECT provider_raw_payload FROM billing_invoices
            UNION ALL SELECT payload_json FROM billing_notifications
            UNION ALL SELECT payload_json FROM telegram_inbound_updates
            UNION ALL SELECT payload_json FROM telegram_outbox
            UNION ALL SELECT response_snapshot::TEXT FROM guest_batch_idempotency
            UNION ALL SELECT payload_json FROM analytics_events
            UNION ALL SELECT tags_json FROM visit_feedback
            UNION ALL SELECT schedule_snapshot_json FROM order_promotion_applications
            UNION ALL SELECT target_snapshot_json FROM order_promotion_applications
        ) durable_json
        WHERE (
            CASE
                WHEN durable_json.payload IS JSON
                THEN (durable_json.payload::JSONB)::TEXT
                ELSE durable_json.payload
            END
        ) ~
            '"(ticketId|threadId|thread_id|supportThreadId|support_thread_id|bookingThreadId|booking_thread_id|ticket_id)"[[:space:]]*:'
    ) THEN
        RAISE EXCEPTION
            'Unexpected durable JSON support thread reference';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM support_threads st
        WHERE st.thread_type = 'BOOKING_THREAD'
          AND st.booking_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'BOOKING_THREAD is missing its canonical booking';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM support_threads st
        LEFT JOIN bookings b ON b.id = st.booking_id
        WHERE st.thread_type = 'BOOKING_THREAD'
          AND st.booking_id IS NOT NULL
          AND b.id IS NULL
    ) THEN
        RAISE EXCEPTION
            'BOOKING_THREAD references a missing canonical booking';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM support_threads st
        JOIN bookings b ON b.id = st.booking_id
        WHERE st.thread_type = 'BOOKING_THREAD'
          AND (
              st.venue_id IS DISTINCT FROM b.venue_id
              OR st.guest_user_id IS DISTINCT FROM b.user_id
          )
    ) THEN
        RAISE EXCEPTION
            'BOOKING_THREAD ownership does not match the canonical booking';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM support_threads st
        WHERE st.thread_type = 'BOOKING_THREAD'
          AND st.booking_id IS NOT NULL
        GROUP BY st.booking_id
        HAVING COUNT(DISTINCT st.status) > 1
    ) THEN
        RAISE EXCEPTION
            'Duplicate BOOKING_THREAD rows have conflicting statuses';
    END IF;

    IF EXISTS (
        WITH duplicate_groups AS (
            SELECT booking_id, COUNT(*) AS thread_count
            FROM support_threads
            WHERE thread_type = 'BOOKING_THREAD'
              AND booking_id IS NOT NULL
            GROUP BY booking_id
            HAVING COUNT(*) > 1
        )
        SELECT 1
        FROM duplicate_groups groups
        JOIN support_threads threads
          ON threads.thread_type = 'BOOKING_THREAD'
         AND threads.booking_id = groups.booking_id
        JOIN support_thread_reads reads ON reads.thread_id = threads.id
        GROUP BY groups.booking_id, groups.thread_count, reads.user_id
        HAVING COUNT(*) <> groups.thread_count
    ) THEN
        RAISE EXCEPTION
            'Duplicate BOOKING_THREAD read markers have partial user coverage';
    END IF;

    IF EXISTS (
        WITH duplicate_groups AS (
            SELECT booking_id
            FROM support_threads
            WHERE thread_type = 'BOOKING_THREAD'
              AND booking_id IS NOT NULL
            GROUP BY booking_id
            HAVING COUNT(*) > 1
        )
        SELECT 1
        FROM duplicate_groups groups
        JOIN support_threads threads
          ON threads.thread_type = 'BOOKING_THREAD'
         AND threads.booking_id = groups.booking_id
        JOIN support_thread_reads reads ON reads.thread_id = threads.id
        GROUP BY groups.booking_id, reads.user_id
        HAVING COUNT(DISTINCT reads.last_read_at) <> 1
    ) THEN
        RAISE EXCEPTION
            'Duplicate BOOKING_THREAD read markers have conflicting timestamps';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM audit_log audit
        JOIN booking_thread_audit_payload_inventory payload
          ON payload.audit_id = audit.id
        JOIN support_threads thread
          ON thread.id = audit.entity_id
         AND thread.thread_type = 'BOOKING_THREAD'
        WHERE (
            audit.entity_type = 'support_ticket'
            OR audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
            OR payload.normalized_payload ~
                '"(ticketId|threadId|thread_id|supportThreadId|support_thread_id|bookingThreadId|booking_thread_id|ticket_id)"[[:space:]]*:'
        )
          AND (
              audit.entity_type <> 'support_ticket'
              OR audit.action <> 'SUPPORT_TICKET_STATUS_CHANGED'
          )
    ) THEN
        RAISE EXCEPTION
            'Unexpected BOOKING_THREAD audit action or entity shape';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM audit_log audit
        JOIN booking_thread_audit_payload_inventory payload
          ON payload.audit_id = audit.id
        WHERE payload.normalized_payload ~ '"ticketId"[[:space:]]*:'
          AND NOT payload.payload_is_object
    ) THEN
        RAISE EXCEPTION
            'Audit payload containing ticketId is not a JSON object';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM audit_log audit
        JOIN booking_thread_audit_payload_inventory payload
          ON payload.audit_id = audit.id
        WHERE payload.normalized_payload ~ '"ticketId"[[:space:]]*:'
          AND (
              payload.ticket_id_count <> 1
              OR REGEXP_COUNT(payload.normalized_payload, '"ticketId"[[:space:]]*:') <> 1
          )
    ) THEN
        RAISE EXCEPTION
            'Audit payload must contain exactly one top-level ticketId';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM audit_log audit
        JOIN booking_thread_audit_payload_inventory payload
          ON payload.audit_id = audit.id
        WHERE payload.normalized_payload ~ '"ticketId"[[:space:]]*:'
          AND payload.ticket_id_count = 1
          AND payload.ticket_id_value !~ '^-?(0|[1-9][0-9]*)$'
    ) THEN
        RAISE EXCEPTION
            'Audit ticketId must be an exact JSON integer';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM audit_log audit
        JOIN booking_thread_audit_payload_inventory payload
          ON payload.audit_id = audit.id
        WHERE payload.normalized_payload ~
            '"(threadId|thread_id|supportThreadId|support_thread_id|bookingThreadId|booking_thread_id|ticket_id)"[[:space:]]*:'
    ) THEN
        RAISE EXCEPTION
            'Audit payload contains an unsupported thread reference alias';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM audit_log audit
        JOIN booking_thread_audit_payload_inventory payload
          ON payload.audit_id = audit.id
        JOIN support_threads thread
          ON thread.thread_type = 'BOOKING_THREAD'
         AND payload.ticket_id_count = 1
         AND thread.id::TEXT = payload.ticket_id_value
        WHERE audit.entity_type <> 'support_ticket'
           OR audit.action <> 'SUPPORT_TICKET_STATUS_CHANGED'
           OR audit.entity_id IS DISTINCT FROM thread.id
    ) THEN
        RAISE EXCEPTION
            'Audit ticketId BOOKING_THREAD reference does not match its canonical entity';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM audit_log audit
        JOIN booking_thread_audit_payload_inventory payload
          ON payload.audit_id = audit.id
        JOIN support_threads thread
          ON thread.id = audit.entity_id
         AND thread.thread_type = 'BOOKING_THREAD'
        WHERE audit.entity_type = 'support_ticket'
          AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
          AND NOT payload.payload_is_object
    ) THEN
        RAISE EXCEPTION
            'BOOKING_THREAD audit payload is not a JSON object';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM audit_log audit
        JOIN booking_thread_audit_payload_inventory payload
          ON payload.audit_id = audit.id
        JOIN support_threads thread
          ON thread.id = audit.entity_id
         AND thread.thread_type = 'BOOKING_THREAD'
        WHERE audit.entity_type = 'support_ticket'
          AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
          AND (
              payload.ticket_id_count <> 1
              OR REGEXP_COUNT(payload.normalized_payload, '"ticketId"[[:space:]]*:') <> 1
          )
    ) THEN
        RAISE EXCEPTION
            'BOOKING_THREAD audit payload must contain exactly one ticketId';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM audit_log audit
        JOIN booking_thread_audit_payload_inventory payload
          ON payload.audit_id = audit.id
        JOIN support_threads thread
          ON thread.id = audit.entity_id
         AND thread.thread_type = 'BOOKING_THREAD'
        WHERE audit.entity_type = 'support_ticket'
          AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
          AND payload.ticket_id_count = 1
          AND payload.ticket_id_value !~ '^-?(0|[1-9][0-9]*)$'
    ) THEN
        RAISE EXCEPTION
            'BOOKING_THREAD audit ticketId must be an exact JSON integer';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM audit_log audit
        JOIN booking_thread_audit_payload_inventory payload
          ON payload.audit_id = audit.id
        JOIN support_threads thread
          ON thread.id = audit.entity_id
         AND thread.thread_type = 'BOOKING_THREAD'
        WHERE audit.entity_type = 'support_ticket'
          AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
          AND payload.normalized_payload ~
              '"(threadId|thread_id|supportThreadId|support_thread_id|bookingThreadId|booking_thread_id|ticket_id)"[[:space:]]*:'
    ) THEN
        RAISE EXCEPTION
            'BOOKING_THREAD audit payload contains an unsupported thread reference alias';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM audit_log audit
        JOIN booking_thread_audit_payload_inventory payload
          ON payload.audit_id = audit.id
        JOIN support_threads thread
          ON thread.id = audit.entity_id
         AND thread.thread_type = 'BOOKING_THREAD'
        WHERE audit.entity_type = 'support_ticket'
          AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
          AND payload.ticket_id_value IS DISTINCT FROM audit.entity_id::TEXT
    ) THEN
        RAISE EXCEPTION
            'BOOKING_THREAD audit ticketId does not match entity_id';
    END IF;
END $$;

DROP TABLE booking_thread_reference_inventory;
DROP TABLE booking_thread_expected_json_inventory;
DROP TABLE booking_thread_audit_payload_inventory;
DROP TABLE booking_thread_unknown_audit_reference_keys;

CREATE TEMPORARY TABLE booking_thread_merge_map ON COMMIT DROP AS
SELECT
    st.id AS thread_id,
    st.booking_id,
    MIN(st.id) OVER (PARTITION BY st.booking_id) AS survivor_id,
    COUNT(*) OVER (PARTITION BY st.booking_id) AS thread_count,
    MIN(st.created_at) OVER (PARTITION BY st.booking_id) AS merged_created_at,
    MAX(st.updated_at) OVER (PARTITION BY st.booking_id) AS merged_updated_at,
    MAX(st.last_message_at) OVER (PARTITION BY st.booking_id) AS merged_last_message_at
FROM support_threads st
WHERE st.thread_type = 'BOOKING_THREAD'
  AND st.booking_id IS NOT NULL;

CREATE TEMPORARY TABLE booking_thread_merged_reads ON COMMIT DROP AS
SELECT
    map.survivor_id AS thread_id,
    reads.user_id,
    reads.last_read_at
FROM booking_thread_merge_map map
JOIN support_thread_reads reads ON reads.thread_id = map.thread_id
WHERE map.thread_count > 1
GROUP BY map.survivor_id, map.thread_count, reads.user_id, reads.last_read_at
HAVING COUNT(*) = map.thread_count;

UPDATE support_messages messages
SET thread_id = map.survivor_id
FROM booking_thread_merge_map map
WHERE messages.thread_id = map.thread_id
  AND map.thread_id <> map.survivor_id;

DELETE FROM support_thread_reads reads
USING booking_thread_merge_map map
WHERE reads.thread_id = map.thread_id
  AND map.thread_count > 1;

INSERT INTO support_thread_reads (thread_id, user_id, last_read_at)
SELECT thread_id, user_id, last_read_at
FROM booking_thread_merged_reads;

UPDATE audit_log audit
SET entity_id = map.survivor_id,
    payload_json = JSONB_SET(
        audit.payload_json::JSONB,
        '{ticketId}',
        TO_JSONB(map.survivor_id),
        FALSE
    )::TEXT
FROM booking_thread_merge_map map
WHERE audit.entity_type = 'support_ticket'
  AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
  AND audit.entity_id = map.thread_id
  AND map.thread_id <> map.survivor_id;

UPDATE support_threads survivor
SET created_at = map.merged_created_at,
    updated_at = map.merged_updated_at,
    last_message_at = map.merged_last_message_at
FROM booking_thread_merge_map map
WHERE survivor.id = map.survivor_id
  AND map.thread_id = map.survivor_id
  AND map.thread_count > 1;

DELETE FROM support_threads duplicate
USING booking_thread_merge_map map
WHERE duplicate.id = map.thread_id
  AND map.thread_id <> map.survivor_id;

CREATE UNIQUE INDEX uq_support_threads_booking_thread_booking_id
    ON support_threads (booking_id)
    WHERE thread_type = 'BOOKING_THREAD'
      AND booking_id IS NOT NULL;
