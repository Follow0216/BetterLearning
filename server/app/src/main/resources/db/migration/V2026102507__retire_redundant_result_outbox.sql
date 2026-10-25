-- Stop all old publishers before upgrading: this is a one-time, evidence-gated retirement.
-- SOURCE_VERSION_CREATED already carries TUTOR_REPLY indexing in the publication transaction.
ALTER TABLE ma_outbox DROP CONSTRAINT ma_outbox_status_check;
ALTER TABLE ma_outbox ADD CONSTRAINT ma_outbox_status_check
    CHECK (status IN ('PENDING','DELIVERED','FAILED','RETIRED'));
ALTER TABLE ma_outbox ADD COLUMN retired_at TIMESTAMPTZ;
ALTER TABLE ma_outbox ADD COLUMN retired_reason VARCHAR(64);
ALTER TABLE ma_outbox ADD COLUMN retired_by VARCHAR(128);
ALTER TABLE ma_outbox ADD COLUMN retired_from_status VARCHAR(16);
ALTER TABLE ma_outbox ADD CONSTRAINT ma_outbox_retirement_check CHECK (
    (status = 'RETIRED' AND aggregate_type = 'RESULT' AND kind = 'RESULT_PUBLISHED'
        AND aggregate_version = 1 AND index_revision = 0
        AND retired_at IS NOT NULL AND retired_reason IS NOT NULL AND length(trim(retired_reason)) > 0
        AND retired_by IS NOT NULL AND length(trim(retired_by)) > 0
        AND retired_from_status IS NOT NULL AND retired_from_status IN ('PENDING','FAILED'))
    OR (status <> 'RETIRED' AND retired_at IS NULL AND retired_reason IS NULL
        AND retired_by IS NULL AND retired_from_status IS NULL)
);
DROP INDEX ix_ma_outbox_pending;
CREATE INDEX ix_ma_outbox_pending ON ma_outbox(created_at,event_id)
    WHERE status IN ('PENDING','FAILED');

-- Preserve event identity, ownership, creation time and digest; never pretend delivery.
-- Missing, withdrawn or inconsistent publication proof remains pending for investigation.
UPDATE ma_outbox e SET status = 'RETIRED', retired_at = CURRENT_TIMESTAMP,
    retired_reason = 'REDUNDANT_RESULT_PUBLICATION',
    retired_by = 'V2026102507__retire_redundant_result_outbox.sql', retired_from_status = e.status
WHERE e.aggregate_type = 'RESULT' AND e.kind = 'RESULT_PUBLISHED'
    AND e.aggregate_version = 1 AND e.index_revision = 0 AND e.status IN ('PENDING','FAILED')
    AND e.payload_digest = encode(sha256(convert_to(e.aggregate_id || ':1:RESULT_PUBLISHED', 'UTF8')), 'hex')
    AND NOT EXISTS (SELECT 1 FROM ma_index_job j WHERE j.event_id = e.event_id)
    AND EXISTS (
        SELECT 1 FROM ma_result r
        JOIN ma_run n ON n.run_id = r.run_id AND n.account_id = e.account_id AND n.status = 'SUCCEEDED'
        JOIN ma_request q ON q.account_id = n.account_id AND q.request_id = n.request_id AND q.mode = r.mode
        JOIN ma_source s ON s.account_id = n.account_id AND s.type = 'TUTOR_REPLY'
            AND s.origin_id = r.result_id AND s.status = 'ACTIVE' AND s.current_version = 1
        JOIN ma_source_version v ON v.source_id = s.source_id AND v.version = 1
            AND v.body_markdown = r.body_markdown AND v.purged_at IS NULL
            AND v.status IN ('INDEX_PENDING','READY','FAILED') AND v.file_ref IS NULL AND v.answer_state IS NULL
            AND v.content_sha256 = encode(sha256(convert_to(r.body_markdown, 'UTF8')), 'hex')
        JOIN ma_source_operation o ON o.account_id = n.account_id AND o.operation_id = 'result:' || r.result_id
            AND o.source_id = s.source_id AND o.version = v.version
        JOIN ma_outbox se ON se.account_id = n.account_id AND se.aggregate_type = 'SOURCE'
            AND se.aggregate_id = s.source_id AND se.aggregate_version = v.version
            AND se.kind = 'SOURCE_VERSION_CREATED' AND se.index_revision = 1
            AND se.payload_digest = v.content_sha256 AND se.status IN ('PENDING','FAILED','DELIVERED')
        WHERE r.result_id = e.aggregate_id AND r.status = 'VERIFIED'
    );
