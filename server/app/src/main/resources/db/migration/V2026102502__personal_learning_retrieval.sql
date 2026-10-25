-- P4: pgvector is a required deployment capability. There is no hash/dummy fallback.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE ma_note_request (
    account_id BIGINT NOT NULL REFERENCES app_user(account_id),
    idempotency_key VARCHAR(36) NOT NULL, note_id VARCHAR(36) NOT NULL,
    source_id VARCHAR(36) NOT NULL, request_sha256 CHAR(64) NOT NULL
        CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY(account_id,idempotency_key), UNIQUE(account_id,note_id),
    FOREIGN KEY(account_id,source_id) REFERENCES ma_source(account_id,source_id)
);
CREATE TABLE ma_note_version (
    source_id VARCHAR(36) NOT NULL, version BIGINT NOT NULL CHECK(version >= 1), title TEXT NOT NULL,
    PRIMARY KEY(source_id,version),
    FOREIGN KEY(source_id,version) REFERENCES ma_source_version(source_id,version)
);
CREATE TABLE ma_retrieval_chunk (
    chunk_id VARCHAR(36) PRIMARY KEY, account_id BIGINT NOT NULL, source_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL, anchor_id VARCHAR(36) NOT NULL, question_id VARCHAR(80),
    text TEXT NOT NULL, method_tags JSONB NOT NULL, terms JSONB NOT NULL,
    embedding vector NOT NULL, embedding_model TEXT NOT NULL,
    dimensions INTEGER NOT NULL CHECK(dimensions > 0), index_revision BIGINT NOT NULL CHECK(index_revision > 0),
    FOREIGN KEY(account_id,source_id) REFERENCES ma_source(account_id,source_id),
    FOREIGN KEY(source_id,version,anchor_id) REFERENCES ma_anchor(source_id,version,anchor_id),
    CHECK(vector_dims(embedding) = dimensions), UNIQUE(source_id,version,anchor_id,embedding_model)
);
CREATE INDEX ix_ma_chunk_owner ON ma_retrieval_chunk(account_id,source_id,version);
CREATE INDEX ix_ma_chunk_terms ON ma_retrieval_chunk USING gin(terms);
CREATE INDEX ix_ma_chunk_methods ON ma_retrieval_chunk USING gin(method_tags);
CREATE TABLE ma_index_job (
    event_id VARCHAR(36) PRIMARY KEY REFERENCES ma_outbox(event_id),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK(status IN ('PENDING','PROCESSING','DONE','FAILED')),
    call_state VARCHAR(16) NOT NULL DEFAULT 'NOT_SENT'
        CHECK(call_state IN ('NOT_SENT','CALL_INTENT','CONFIRMED')),
    fence BIGINT NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    failure_reason VARCHAR(64), completed_at TIMESTAMPTZ,
    ocr_calls INTEGER NOT NULL DEFAULT 0, embedding_requests INTEGER NOT NULL DEFAULT 0,
    embedding_tokens BIGINT, usage_uncertain BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE ma_query_embedding (
    step_id VARCHAR(36) PRIMARY KEY REFERENCES ma_step(step_id),
    account_id BIGINT NOT NULL REFERENCES app_user(account_id),
    run_id VARCHAR(36) NOT NULL REFERENCES ma_run(run_id),
    query_sha256 CHAR(64) NOT NULL CHECK(query_sha256 ~ '^[0-9a-f]{64}$'),
    embedding_model TEXT NOT NULL CHECK(length(embedding_model) > 0),
    dimensions INTEGER NOT NULL CHECK(dimensions > 0),
    call_state VARCHAR(16) NOT NULL CHECK(call_state IN ('CALL_INTENT','CONFIRMED')),
    embedding vector, embedding_requests INTEGER, embedding_tokens BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMPTZ,
    CHECK(embedding IS NULL OR vector_dims(embedding) = dimensions),
    CHECK((call_state = 'CALL_INTENT' AND embedding IS NULL AND embedding_requests IS NULL)
        OR (call_state = 'CONFIRMED' AND embedding IS NOT NULL AND embedding_requests >= 1
            AND confirmed_at IS NOT NULL))
);
-- Pin the exact immutable anchor digest at publication, including existing P1 publishers.
ALTER TABLE ma_citation ADD COLUMN content_sha256 CHAR(64);
UPDATE ma_citation c SET content_sha256 = a.content_sha256 FROM ma_anchor a
    WHERE a.anchor_id = c.anchor_id AND a.source_id = c.source_id AND a.version = c.version;
CREATE FUNCTION ma_pin_citation_hash() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected CHAR(64);
BEGIN
    SELECT content_sha256 INTO STRICT expected FROM ma_anchor
        WHERE anchor_id = NEW.anchor_id AND source_id = NEW.source_id AND version = NEW.version;
    IF NEW.content_sha256 IS NOT NULL AND NEW.content_sha256 <> expected THEN
        RAISE EXCEPTION 'citation anchor mismatch';
    END IF;
    NEW.content_sha256 := expected;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ma_citation_hash BEFORE INSERT ON ma_citation
    FOR EACH ROW EXECUTE FUNCTION ma_pin_citation_hash();
ALTER TABLE ma_citation ALTER COLUMN content_sha256 SET NOT NULL;
