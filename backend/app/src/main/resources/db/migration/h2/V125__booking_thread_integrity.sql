CREATE LOCAL TEMPORARY TABLE booking_thread_integrity_guard (
    valid INTEGER NOT NULL,
    CONSTRAINT booking_thread_integrity_guard_valid CHECK (valid = 1)
);

CREATE ALIAS IF NOT EXISTS BOOKING_HAS_UNKNOWN_AUDIT_THREAD_REFERENCE
FOR "com.hookah.platform.backend.support.BookingAuditReferencePolicy.hasUnknownThreadReferenceKey";

CREATE ALIAS IF NOT EXISTS BOOKING_COUNT_TOP_LEVEL_AUDIT_TICKET_IDS DETERMINISTIC
FOR "com.hookah.platform.backend.support.BookingAuditReferencePolicy.countTopLevelTicketIds";

CREATE ALIAS IF NOT EXISTS BOOKING_EXTRACT_TOP_LEVEL_AUDIT_TICKET_ID DETERMINISTIC
FOR "com.hookah.platform.backend.support.BookingAuditReferencePolicy.extractTopLevelTicketId";

CREATE ALIAS IF NOT EXISTS BOOKING_REMAP_TOP_LEVEL_AUDIT_TICKET_ID DETERMINISTIC
FOR "com.hookah.platform.backend.support.BookingAuditReferencePolicy.remapTopLevelTicketId";

CREATE LOCAL TEMPORARY TABLE booking_thread_expected_json_inventory (
    table_name VARCHAR NOT NULL,
    column_name VARCHAR NOT NULL,
    PRIMARY KEY (table_name, column_name)
);

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
    ('visit_feedback', 'tags_json');

CREATE LOCAL TEMPORARY TABLE booking_thread_audit_payload_inventory AS
SELECT
    audit.id AS audit_id,
    audit.payload_json IS JSON OBJECT AS payload_is_object,
    BOOKING_COUNT_TOP_LEVEL_AUDIT_TICKET_IDS(CAST(audit.payload_json AS VARCHAR)) AS ticket_id_count,
    BOOKING_EXTRACT_TOP_LEVEL_AUDIT_TICKET_ID(CAST(audit.payload_json AS VARCHAR)) AS ticket_id_value
FROM audit_log audit;

CREATE LOCAL TEMPORARY TABLE booking_thread_reference_inventory AS
SELECT
    CAST('FOREIGN_KEY' AS VARCHAR) AS reference_kind,
    LOWER(fk.table_name) AS table_name,
    LOWER(fk.column_name) AS column_name,
    CAST(NULL AS VARCHAR) AS discriminator
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

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM (
    SELECT
        COUNT(*) AS inbound_reference_count,
        SUM(
            CASE
                WHEN reference_kind = 'FOREIGN_KEY'
                 AND table_name = 'support_messages'
                 AND column_name = 'thread_id' THEN 1
                ELSE 0
            END
        ) AS message_reference_count,
        SUM(
            CASE
                WHEN reference_kind = 'FOREIGN_KEY'
                 AND table_name = 'support_thread_reads'
                 AND column_name = 'thread_id' THEN 1
                ELSE 0
            END
        ) AS read_reference_count,
        SUM(
            CASE
                WHEN reference_kind = 'LOGICAL_AUDIT'
                 AND table_name = 'audit_log'
                 AND column_name = 'entity_id'
                 AND discriminator = 'support_ticket' THEN 1
                ELSE 0
            END
        ) AS audit_reference_count
    FROM booking_thread_reference_inventory
) inventory
WHERE inventory.inbound_reference_count <> 3
   OR inventory.message_reference_count <> 1
   OR inventory.read_reference_count <> 1
   OR inventory.audit_reference_count <> 1;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM information_schema.columns columns
WHERE LOWER(columns.table_schema) = LOWER(CURRENT_SCHEMA())
  AND REPLACE(LOWER(columns.column_name), '_', '') IN (
      'threadid',
      'supportthreadid',
      'bookingthreadid',
      'ticketid'
  )
  AND NOT (
      LOWER(columns.table_name) IN ('support_messages', 'support_thread_reads')
      AND LOWER(columns.column_name) = 'thread_id'
  )
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM (
    SELECT inventory.table_name, inventory.column_name
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
        UNION ALL
        SELECT expected.table_name, expected.column_name
        FROM booking_thread_expected_json_inventory expected
    ) inventory
    GROUP BY inventory.table_name, inventory.column_name
    HAVING COUNT(*) <> 2
) unexpected_json_inventory
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM (
    SELECT CAST(options AS VARCHAR) AS payload FROM menu_items
    UNION ALL SELECT CAST(items_snapshot AS VARCHAR) FROM order_batches
    UNION ALL SELECT CAST(payload AS VARCHAR) FROM telegram_dialog_state
    UNION ALL SELECT CAST(raw_payload AS VARCHAR) FROM billing_payments
    UNION ALL SELECT CAST(provider_raw_payload AS VARCHAR) FROM billing_invoices
    UNION ALL SELECT CAST(payload_json AS VARCHAR) FROM billing_notifications
    UNION ALL SELECT CAST(payload_json AS VARCHAR) FROM telegram_inbound_updates
    UNION ALL SELECT CAST(payload_json AS VARCHAR) FROM telegram_outbox
    UNION ALL SELECT CAST(response_snapshot AS VARCHAR) FROM guest_batch_idempotency
    UNION ALL SELECT CAST(payload_json AS VARCHAR) FROM analytics_events
    UNION ALL SELECT CAST(tags_json AS VARCHAR) FROM visit_feedback
    UNION ALL SELECT CAST(schedule_snapshot_json AS VARCHAR) FROM order_promotion_applications
    UNION ALL SELECT CAST(target_snapshot_json AS VARCHAR) FROM order_promotion_applications
) durable_json
WHERE REGEXP_SUBSTR(
    CASE
        WHEN durable_json.payload IS JSON
        THEN CAST(durable_json.payload FORMAT JSON AS VARCHAR)
        ELSE durable_json.payload
    END,
    '"(ticketId|threadId|thread_id|supportThreadId|support_thread_id|bookingThreadId|booking_thread_id|ticket_id)"[[:space:]]*:'
) IS NOT NULL
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM support_threads st
WHERE st.thread_type = 'BOOKING_THREAD'
  AND st.booking_id IS NULL
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM support_threads st
LEFT JOIN bookings b ON b.id = st.booking_id
WHERE st.thread_type = 'BOOKING_THREAD'
  AND st.booking_id IS NOT NULL
  AND b.id IS NULL
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM support_threads st
JOIN bookings b ON b.id = st.booking_id
WHERE st.thread_type = 'BOOKING_THREAD'
  AND (
      st.venue_id IS DISTINCT FROM b.venue_id
      OR st.guest_user_id IS DISTINCT FROM b.user_id
  )
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM support_threads st
WHERE st.thread_type = 'BOOKING_THREAD'
  AND st.booking_id IS NOT NULL
GROUP BY st.booking_id
HAVING COUNT(DISTINCT st.status) > 1
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM (
    SELECT groups.booking_id, reads.user_id
    FROM (
        SELECT booking_id, COUNT(*) AS thread_count
        FROM support_threads
        WHERE thread_type = 'BOOKING_THREAD'
          AND booking_id IS NOT NULL
        GROUP BY booking_id
        HAVING COUNT(*) > 1
    ) groups
    JOIN support_threads threads
      ON threads.thread_type = 'BOOKING_THREAD'
     AND threads.booking_id = groups.booking_id
    JOIN support_thread_reads reads ON reads.thread_id = threads.id
    GROUP BY groups.booking_id, groups.thread_count, reads.user_id
    HAVING COUNT(*) <> groups.thread_count
) unsafe_partial_reads
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM (
    SELECT groups.booking_id, reads.user_id
    FROM (
        SELECT booking_id
        FROM support_threads
        WHERE thread_type = 'BOOKING_THREAD'
          AND booking_id IS NOT NULL
        GROUP BY booking_id
        HAVING COUNT(*) > 1
    ) groups
    JOIN support_threads threads
      ON threads.thread_type = 'BOOKING_THREAD'
     AND threads.booking_id = groups.booking_id
    JOIN support_thread_reads reads ON reads.thread_id = threads.id
    GROUP BY groups.booking_id, reads.user_id
    HAVING COUNT(DISTINCT reads.last_read_at) <> 1
) unsafe_read_timestamps
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM audit_log audit
WHERE BOOKING_HAS_UNKNOWN_AUDIT_THREAD_REFERENCE(CAST(audit.payload_json AS VARCHAR))
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM audit_log audit
JOIN support_threads thread
  ON thread.id = audit.entity_id
 AND thread.thread_type = 'BOOKING_THREAD'
WHERE (
    audit.entity_type = 'support_ticket'
    OR audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
    OR REGEXP_SUBSTR(
        CASE
            WHEN audit.payload_json IS JSON
            THEN CAST(audit.payload_json FORMAT JSON AS VARCHAR)
            ELSE audit.payload_json
        END,
        '"(ticketId|threadId|thread_id|supportThreadId|support_thread_id|bookingThreadId|booking_thread_id|ticket_id)"[[:space:]]*:'
    ) IS NOT NULL
)
  AND (
      audit.entity_type <> 'support_ticket'
      OR audit.action <> 'SUPPORT_TICKET_STATUS_CHANGED'
  )
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM audit_log audit
WHERE REGEXP_SUBSTR(
    CASE
        WHEN audit.payload_json IS JSON
        THEN CAST(audit.payload_json FORMAT JSON AS VARCHAR)
        ELSE audit.payload_json
    END,
    '"ticketId"[[:space:]]*:'
) IS NOT NULL
  AND NOT (audit.payload_json IS JSON OBJECT)
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM booking_thread_audit_payload_inventory payload
WHERE payload.ticket_id_count > 1
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM booking_thread_audit_payload_inventory payload
WHERE payload.ticket_id_count = 1
  AND payload.ticket_id_value IS NULL
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM audit_log audit
WHERE REGEXP_SUBSTR(
    CASE
        WHEN audit.payload_json IS JSON
        THEN CAST(audit.payload_json FORMAT JSON AS VARCHAR)
        ELSE audit.payload_json
    END,
    '"(threadId|thread_id|supportThreadId|support_thread_id|bookingThreadId|booking_thread_id|ticket_id)"[[:space:]]*:'
) IS NOT NULL
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM audit_log audit
JOIN booking_thread_audit_payload_inventory payload ON payload.audit_id = audit.id
JOIN support_threads thread
  ON thread.thread_type = 'BOOKING_THREAD'
 AND payload.ticket_id_count = 1
 AND payload.ticket_id_value = thread.id
WHERE audit.entity_type <> 'support_ticket'
   OR audit.action <> 'SUPPORT_TICKET_STATUS_CHANGED'
   OR audit.entity_id IS NULL
   OR audit.entity_id <> thread.id
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM audit_log audit
JOIN support_threads thread
  ON thread.id = audit.entity_id
 AND thread.thread_type = 'BOOKING_THREAD'
WHERE audit.entity_type = 'support_ticket'
  AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
  AND NOT (audit.payload_json IS JSON OBJECT)
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM audit_log audit
JOIN booking_thread_audit_payload_inventory payload ON payload.audit_id = audit.id
JOIN support_threads thread
  ON thread.id = audit.entity_id
 AND thread.thread_type = 'BOOKING_THREAD'
WHERE audit.entity_type = 'support_ticket'
  AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
  AND payload.ticket_id_count <> 1
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM audit_log audit
JOIN booking_thread_audit_payload_inventory payload ON payload.audit_id = audit.id
JOIN support_threads thread
  ON thread.id = audit.entity_id
 AND thread.thread_type = 'BOOKING_THREAD'
WHERE audit.entity_type = 'support_ticket'
  AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
  AND payload.ticket_id_count = 1
  AND payload.ticket_id_value IS NULL
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM audit_log audit
JOIN support_threads thread
  ON thread.id = audit.entity_id
 AND thread.thread_type = 'BOOKING_THREAD'
WHERE audit.entity_type = 'support_ticket'
  AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
  AND REGEXP_SUBSTR(
      CASE
          WHEN audit.payload_json IS JSON
          THEN CAST(audit.payload_json FORMAT JSON AS VARCHAR)
          ELSE audit.payload_json
      END,
      '"(threadId|thread_id|supportThreadId|support_thread_id|bookingThreadId|booking_thread_id|ticket_id)"[[:space:]]*:'
  ) IS NOT NULL
FETCH FIRST 1 ROW ONLY;

INSERT INTO booking_thread_integrity_guard (valid)
SELECT 0
FROM audit_log audit
JOIN booking_thread_audit_payload_inventory payload ON payload.audit_id = audit.id
JOIN support_threads thread
  ON thread.id = audit.entity_id
 AND thread.thread_type = 'BOOKING_THREAD'
WHERE audit.entity_type = 'support_ticket'
  AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
  AND payload.ticket_id_value IS DISTINCT FROM audit.entity_id
FETCH FIRST 1 ROW ONLY;

DROP TABLE booking_thread_reference_inventory;
DROP TABLE booking_thread_expected_json_inventory;
DROP TABLE booking_thread_audit_payload_inventory;
DROP ALIAS BOOKING_HAS_UNKNOWN_AUDIT_THREAD_REFERENCE;
DROP ALIAS BOOKING_COUNT_TOP_LEVEL_AUDIT_TICKET_IDS;
DROP ALIAS BOOKING_EXTRACT_TOP_LEVEL_AUDIT_TICKET_ID;
DROP TABLE booking_thread_integrity_guard;

CREATE TABLE booking_thread_merge_map (
    thread_id BIGINT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    survivor_id BIGINT NOT NULL,
    thread_count BIGINT NOT NULL,
    merged_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    merged_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    merged_last_message_at TIMESTAMP WITH TIME ZONE
);

INSERT INTO booking_thread_merge_map (
    thread_id,
    booking_id,
    survivor_id,
    thread_count,
    merged_created_at,
    merged_updated_at,
    merged_last_message_at
)
SELECT
    st.id,
    st.booking_id,
    MIN(st.id) OVER (PARTITION BY st.booking_id),
    COUNT(*) OVER (PARTITION BY st.booking_id),
    MIN(st.created_at) OVER (PARTITION BY st.booking_id),
    MAX(st.updated_at) OVER (PARTITION BY st.booking_id),
    MAX(st.last_message_at) OVER (PARTITION BY st.booking_id)
FROM support_threads st
WHERE st.thread_type = 'BOOKING_THREAD'
  AND st.booking_id IS NOT NULL;

CREATE TABLE booking_thread_merged_reads (
    thread_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    last_read_at TIMESTAMP WITH TIME ZONE NOT NULL
);

INSERT INTO booking_thread_merged_reads (thread_id, user_id, last_read_at)
SELECT
    map.survivor_id,
    reads.user_id,
    reads.last_read_at
FROM booking_thread_merge_map map
JOIN support_thread_reads reads ON reads.thread_id = map.thread_id
WHERE map.thread_count > 1
GROUP BY map.survivor_id, map.thread_count, reads.user_id, reads.last_read_at
HAVING COUNT(*) = map.thread_count;

CREATE TABLE booking_thread_audit_remap (
    audit_id BIGINT PRIMARY KEY,
    survivor_id BIGINT NOT NULL,
    payload_json CHARACTER LARGE OBJECT NOT NULL
);

INSERT INTO booking_thread_audit_remap (audit_id, survivor_id, payload_json)
SELECT
    audit.id,
    map.survivor_id,
    BOOKING_REMAP_TOP_LEVEL_AUDIT_TICKET_ID(
        CAST(audit.payload_json AS VARCHAR),
        map.thread_id,
        map.survivor_id
    )
FROM audit_log audit
JOIN booking_thread_merge_map map ON map.thread_id = audit.entity_id
WHERE audit.entity_type = 'support_ticket'
  AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
  AND map.thread_id <> map.survivor_id;

DROP ALIAS BOOKING_REMAP_TOP_LEVEL_AUDIT_TICKET_ID;

UPDATE support_messages messages
SET thread_id = (
    SELECT map.survivor_id
    FROM booking_thread_merge_map map
    WHERE map.thread_id = messages.thread_id
)
WHERE EXISTS (
    SELECT 1
    FROM booking_thread_merge_map map
    WHERE map.thread_id = messages.thread_id
      AND map.thread_id <> map.survivor_id
);

DELETE FROM support_thread_reads reads
WHERE reads.thread_id IN (
    SELECT map.thread_id
    FROM booking_thread_merge_map map
    WHERE map.thread_count > 1
);

INSERT INTO support_thread_reads (thread_id, user_id, last_read_at)
SELECT thread_id, user_id, last_read_at
FROM booking_thread_merged_reads;

UPDATE audit_log audit
SET entity_id = (
        SELECT remap.survivor_id
        FROM booking_thread_audit_remap remap
        WHERE remap.audit_id = audit.id
    ),
    payload_json = (
        SELECT remap.payload_json
        FROM booking_thread_audit_remap remap
        WHERE remap.audit_id = audit.id
    )
WHERE audit.id IN (SELECT remap.audit_id FROM booking_thread_audit_remap remap);

UPDATE support_threads survivor
SET created_at = (
        SELECT map.merged_created_at
        FROM booking_thread_merge_map map
        WHERE map.thread_id = survivor.id
          AND map.survivor_id = survivor.id
          AND map.thread_count > 1
    ),
    updated_at = (
        SELECT map.merged_updated_at
        FROM booking_thread_merge_map map
        WHERE map.thread_id = survivor.id
          AND map.survivor_id = survivor.id
          AND map.thread_count > 1
    ),
    last_message_at = (
        SELECT map.merged_last_message_at
        FROM booking_thread_merge_map map
        WHERE map.thread_id = survivor.id
          AND map.survivor_id = survivor.id
          AND map.thread_count > 1
    )
WHERE survivor.id IN (
    SELECT map.survivor_id
    FROM booking_thread_merge_map map
    WHERE map.thread_count > 1
);

DELETE FROM support_threads duplicate
WHERE duplicate.id IN (
    SELECT map.thread_id
    FROM booking_thread_merge_map map
    WHERE map.thread_id <> map.survivor_id
);

DROP TABLE booking_thread_merged_reads;
DROP TABLE booking_thread_audit_remap;
DROP TABLE booking_thread_merge_map;

ALTER TABLE support_threads
    ADD COLUMN booking_thread_booking_key BIGINT
    GENERATED ALWAYS AS (
        CASE
            WHEN thread_type = 'BOOKING_THREAD' AND booking_id IS NOT NULL THEN booking_id
            ELSE NULL
        END
    );

CREATE UNIQUE INDEX uq_support_threads_booking_thread_booking_id
    ON support_threads (booking_thread_booking_key);
