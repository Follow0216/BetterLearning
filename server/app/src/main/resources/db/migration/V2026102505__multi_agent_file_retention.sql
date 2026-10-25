-- A file tombstone is committed before filesystem I/O. Every new reference locks the
-- same row, so a reference cannot appear between the last check and tombstoning.
CREATE TABLE ma_file_gc (
    file_ref VARCHAR(36) PRIMARY KEY,
    state VARCHAR(20) NOT NULL CHECK (state IN ('TRACKED','HOLD','DELETED')),
    hold_reason VARCHAR(32),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    physical_deleted_at TIMESTAMPTZ,
    CHECK (state <> 'HOLD' OR hold_reason IS NOT NULL)
);

CREATE FUNCTION ma_guard_file_reference() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE gc_state VARCHAR(20);
DECLARE new_ref VARCHAR(36);
BEGIN
    IF TG_TABLE_NAME = 'ma_request' THEN
        new_ref := NEW.snapshot_file_ref;
    ELSE
        new_ref := NEW.file_ref;
    END IF;
    IF new_ref IS NULL THEN
        RETURN NEW;
    END IF;
    INSERT INTO ma_file_gc(file_ref,state) VALUES (new_ref,'TRACKED')
        ON CONFLICT (file_ref) DO NOTHING;
    SELECT state INTO gc_state FROM ma_file_gc WHERE file_ref = new_ref FOR UPDATE;
    IF gc_state = 'DELETED' THEN
        RAISE EXCEPTION 'file reference is tombstoned' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_question_answer_file_guard BEFORE INSERT OR UPDATE OF file_ref ON question_answer
    FOR EACH ROW EXECUTE FUNCTION ma_guard_file_reference();
CREATE TRIGGER tr_tutor_turn_file_guard BEFORE INSERT OR UPDATE OF file_ref ON tutor_turn
    FOR EACH ROW EXECUTE FUNCTION ma_guard_file_reference();
CREATE TRIGGER tr_ma_request_file_guard BEFORE INSERT OR UPDATE OF snapshot_file_ref ON ma_request
    FOR EACH ROW EXECUTE FUNCTION ma_guard_file_reference();
CREATE TRIGGER tr_ma_source_version_file_guard BEFORE INSERT OR UPDATE OF file_ref ON ma_source_version
    FOR EACH ROW EXECUTE FUNCTION ma_guard_file_reference();
