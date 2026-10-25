-- P6: retain identities and references; never cascade-delete published requests/results.
ALTER TABLE ma_source ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE ma_source_version ADD COLUMN purged_at TIMESTAMPTZ;
ALTER TABLE ma_source_version ADD COLUMN purge_reason VARCHAR(24);
CREATE TABLE ma_source_deletion (
    source_id VARCHAR(36) PRIMARY KEY, account_id BIGINT NOT NULL, deleted_at TIMESTAMPTZ NOT NULL,
    journal_sequence BIGINT NOT NULL CHECK (journal_sequence > 0), journal_hash CHAR(64) NOT NULL,
    database_purged_at TIMESTAMPTZ, hold_reason VARCHAR(32), checked_at TIMESTAMPTZ
);
-- No FK: deletion may refer to a source absent from an older restored backup.
CREATE TABLE ma_deletion_replay (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton), sequence BIGINT NOT NULL DEFAULT 0,
    hash CHAR(64) NOT NULL DEFAULT repeat('0',64), replayed_at TIMESTAMPTZ
);
INSERT INTO ma_deletion_replay(singleton) VALUES(TRUE);
CREATE INDEX ix_ma_retention_versions ON ma_source_version(created_at) WHERE purged_at IS NULL;

CREATE FUNCTION ma_apply_source_deletion(p_source VARCHAR, p_account BIGINT, p_deleted TIMESTAMPTZ,
        p_sequence BIGINT, p_hash VARCHAR) RETURNS BOOLEAN LANGUAGE plpgsql AS $$
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
    RETURN TRUE;
END;
$$;
CREATE FUNCTION ma_source_tombstone_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status='ACTIVE' AND EXISTS(SELECT 1 FROM ma_source_deletion WHERE source_id=NEW.source_id) THEN
        RAISE EXCEPTION 'source has been withdrawn';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ma_source_no_resurrection BEFORE INSERT OR UPDATE ON ma_source
    FOR EACH ROW EXECUTE FUNCTION ma_source_tombstone_guard();

-- New copies and purge share the source row lock in addition to account locks.
CREATE FUNCTION ma_retained_version_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source_state VARCHAR; cleared TIMESTAMPTZ;
BEGIN
    IF NEW.source_id IS NULL THEN RETURN NEW; END IF;
    SELECT status INTO source_state FROM ma_source WHERE source_id=NEW.source_id FOR SHARE;
    SELECT purged_at INTO cleared FROM ma_source_version
        WHERE source_id=NEW.source_id AND version=NEW.version FOR SHARE;
    IF source_state IS DISTINCT FROM 'ACTIVE' OR cleared IS NOT NULL THEN
        RAISE EXCEPTION 'source version is unavailable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ma_anchor_retained BEFORE INSERT ON ma_anchor
    FOR EACH ROW EXECUTE FUNCTION ma_retained_version_guard();
CREATE TRIGGER ma_evidence_retained BEFORE INSERT ON ma_evidence
    FOR EACH ROW EXECUTE FUNCTION ma_retained_version_guard();
CREATE TRIGGER ma_citation_retained BEFORE INSERT ON ma_citation
    FOR EACH ROW EXECUTE FUNCTION ma_retained_version_guard();
DO $$ BEGIN
    IF to_regclass('ma_retrieval_chunk') IS NOT NULL THEN
        EXECUTE 'CREATE TRIGGER ma_chunk_retained BEFORE INSERT ON ma_retrieval_chunk '
            || 'FOR EACH ROW EXECUTE FUNCTION ma_retained_version_guard()';
    END IF;
END $$;

-- P4 tables optional until its frozen migration is integrated. Fixed SQL, no input identifiers.
CREATE FUNCTION ma_scrub_source_version(p_source VARCHAR,p_version BIGINT) RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    IF to_regclass('ma_retrieval_chunk') IS NOT NULL THEN
        EXECUTE 'DELETE FROM ma_retrieval_chunk WHERE source_id=$1 AND version=$2' USING p_source,p_version;
    END IF;
    IF to_regclass('ma_note_version') IS NOT NULL THEN
        EXECUTE 'UPDATE ma_note_version SET title='''' WHERE source_id=$1 AND version=$2' USING p_source,p_version;
    END IF;
    IF to_regclass('ma_index_job') IS NOT NULL THEN
        EXECUTE 'UPDATE ma_index_job SET status=''DONE'',fence=fence+1,lease_until=NULL,completed_at=CURRENT_TIMESTAMP '
            || 'WHERE event_id IN (SELECT event_id FROM ma_outbox WHERE aggregate_type=''SOURCE'' '
            || 'AND aggregate_id=$1 AND aggregate_version=$2) AND status<>''DONE''' USING p_source,p_version;
    END IF;
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
