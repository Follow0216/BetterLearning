-- Keep paid-call identities and accounting after source erasure, but remove OCR and vectors.
ALTER TABLE ma_index_stage ADD COLUMN purged_at TIMESTAMPTZ;
ALTER TABLE ma_index_stage ALTER COLUMN input_sha256 DROP NOT NULL;

DO $$
DECLARE old_constraint TEXT;
BEGIN
    SELECT conname INTO STRICT old_constraint FROM pg_constraint
    WHERE conrelid = 'ma_index_stage'::regclass AND contype = 'c'
        AND pg_get_constraintdef(oid) LIKE '%output_json%'
        AND pg_get_constraintdef(oid) LIKE '%call_state%';
    EXECUTE format('ALTER TABLE ma_index_stage DROP CONSTRAINT %I', old_constraint);
END;
$$;

CREATE FUNCTION ma_scrub_index_stages(p_source VARCHAR,p_version BIGINT) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    -- Withdraw/replay/sweep already hold the account row. Lock every affected job
    -- before touching its stages, matching claim and the store's account/job order.
    PERFORM j.event_id FROM ma_index_job j
    WHERE EXISTS (SELECT 1 FROM ma_index_stage st WHERE st.event_id = j.event_id
        AND st.source_id = p_source AND (p_version IS NULL OR st.source_version = p_version))
    ORDER BY j.event_id FOR UPDATE OF j;
    UPDATE ma_index_stage SET call_state = CASE WHEN call_state = 'CALL_INTENT' THEN 'UNKNOWN'
            ELSE call_state END,
        input_sha256 = NULL,output_json = NULL,output_sha256 = NULL,
        purged_at = COALESCE(purged_at,CURRENT_TIMESTAMP)
    WHERE source_id = p_source AND (p_version IS NULL OR source_version = p_version)
        AND (purged_at IS NULL OR input_sha256 IS NOT NULL OR output_json IS NOT NULL
            OR output_sha256 IS NOT NULL OR call_state = 'CALL_INTENT');
    UPDATE ma_index_job j SET usage_uncertain = TRUE
    WHERE EXISTS (SELECT 1 FROM ma_index_stage st WHERE st.event_id = j.event_id
        AND st.source_id = p_source AND (p_version IS NULL OR st.source_version = p_version)
        AND st.call_state = 'UNKNOWN');
END;
$$;

-- Deletion-journal replay runs before serving requests, including after a database restore.
CREATE OR REPLACE FUNCTION ma_apply_source_deletion(p_source VARCHAR, p_account BIGINT,
        p_deleted TIMESTAMPTZ, p_sequence BIGINT, p_hash VARCHAR) RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM ma_source WHERE source_id=p_source AND account_id<>p_account)
        OR EXISTS(SELECT 1 FROM ma_source_deletion WHERE source_id=p_source AND account_id<>p_account) THEN
        RAISE EXCEPTION 'deletion identity mismatch';
    END IF;
    INSERT INTO ma_source_deletion(source_id,account_id,deleted_at,journal_sequence,journal_hash)
        VALUES(p_source,p_account,p_deleted,p_sequence,p_hash) ON CONFLICT(source_id) DO NOTHING;
    UPDATE ma_source SET status='DELETED',title='',
        origin_id=CASE WHEN status='ACTIVE' THEN source_id || '#deleted:' || source_id ELSE origin_id END,
        deleted_at=COALESCE(deleted_at,p_deleted) WHERE source_id=p_source AND account_id=p_account;
    UPDATE ma_source_version SET status='WITHDRAWN' WHERE source_id=p_source;
    PERFORM ma_scrub_index_stages(p_source,NULL);
    RETURN TRUE;
END;
$$;

-- The regular retention sweep also clears stages for retired, unreferenced versions.
CREATE OR REPLACE FUNCTION ma_scrub_source_version(p_source VARCHAR,p_version BIGINT)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    PERFORM ma_scrub_index_stages(p_source,p_version);
    DELETE FROM ma_retrieval_chunk WHERE source_id=p_source AND version=p_version;
    UPDATE ma_note_version SET title='' WHERE source_id=p_source AND version=p_version;
    UPDATE ma_index_job SET status='DONE',fence=fence+1,lease_until=NULL,completed_at=CURRENT_TIMESTAMP
        WHERE event_id IN (SELECT event_id FROM ma_outbox WHERE aggregate_type='SOURCE'
            AND aggregate_id=p_source AND aggregate_version=p_version) AND status<>'DONE';
    UPDATE ma_anchor SET text=NULL,locator_json='{}' WHERE source_id=p_source AND version=p_version;
    UPDATE ma_source_version SET body_markdown=NULL,status='WITHDRAWN',
        purged_at=COALESCE(purged_at,CURRENT_TIMESTAMP),
        purge_reason=CASE WHEN EXISTS(SELECT 1 FROM ma_source s WHERE s.source_id=p_source AND s.status='DELETED')
            THEN 'SOURCE_DELETED' ELSE 'OLD_UNREFERENCED' END WHERE source_id=p_source AND version=p_version;
    UPDATE ma_outbox SET status='DELIVERED' WHERE aggregate_type='SOURCE' AND aggregate_id=p_source
        AND aggregate_version=p_version AND kind IN ('SOURCE_VERSION_CREATED','SOURCE_REINDEX');
    RETURN TRUE;
END;
$$;

-- Upgrade databases whose source purge finished before this stage cache existed.
SELECT ma_scrub_index_stages(st.source_id,st.source_version)
FROM (SELECT DISTINCT stage.source_id,stage.source_version FROM ma_index_stage stage
    JOIN ma_source s ON s.source_id=stage.source_id
    JOIN ma_source_version v ON v.source_id=stage.source_id AND v.version=stage.source_version
    LEFT JOIN ma_source_deletion d ON d.source_id=stage.source_id
    WHERE v.purged_at IS NOT NULL OR v.status='WITHDRAWN' OR s.status='DELETED'
        OR d.source_id IS NOT NULL OR d.database_purged_at IS NOT NULL) st;

ALTER TABLE ma_index_stage ADD CONSTRAINT ck_ma_index_stage_payload_retention CHECK (
    (call_state = 'CONFIRMED' AND request_count = 1 AND confirmed_at IS NOT NULL
        AND ((purged_at IS NULL AND input_sha256 IS NOT NULL
                AND output_json IS NOT NULL AND output_sha256 IS NOT NULL)
            OR (purged_at IS NOT NULL AND input_sha256 IS NULL
                AND output_json IS NULL AND output_sha256 IS NULL)))
    OR (call_state IN ('CALL_INTENT','UNKNOWN') AND request_count IS NULL
        AND total_tokens IS NULL AND confirmed_at IS NULL
        AND output_json IS NULL AND output_sha256 IS NULL
        AND ((purged_at IS NULL AND input_sha256 IS NOT NULL)
            OR (purged_at IS NOT NULL AND call_state = 'UNKNOWN'
                AND input_sha256 IS NULL)))
);
