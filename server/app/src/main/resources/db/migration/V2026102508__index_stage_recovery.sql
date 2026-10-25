-- Each paid request has a durable intent and an independently reusable confirmed result.
ALTER TABLE ma_index_job ADD COLUMN index_protocol VARCHAR(48);

CREATE TABLE ma_index_stage (
    event_id VARCHAR(36) NOT NULL REFERENCES ma_index_job(event_id),
    phase VARCHAR(16) NOT NULL CHECK (phase IN ('OCR_STEP','EMBED_BATCH')),
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    account_id BIGINT NOT NULL REFERENCES app_user(account_id),
    source_id VARCHAR(36) NOT NULL,
    source_version BIGINT NOT NULL CHECK (source_version >= 1),
    index_revision BIGINT NOT NULL CHECK (index_revision >= 1),
    source_sha256 CHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    file_ref VARCHAR(36),
    input_sha256 CHAR(64) NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
    model TEXT NOT NULL CHECK (length(model) > 0),
    dimensions INTEGER NOT NULL CHECK (dimensions >= 0),
    schema_version VARCHAR(64) NOT NULL CHECK (length(schema_version) > 0),
    call_state VARCHAR(16) NOT NULL CHECK (call_state IN ('CALL_INTENT','CONFIRMED','UNKNOWN')),
    intent_fence BIGINT NOT NULL CHECK (intent_fence >= 1),
    output_json TEXT, output_sha256 CHAR(64)
        CHECK (output_sha256 IS NULL OR output_sha256 ~ '^[0-9a-f]{64}$'),
    request_count INTEGER, total_tokens BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMPTZ,
    PRIMARY KEY(event_id,phase,ordinal),
    FOREIGN KEY(account_id,source_id) REFERENCES ma_source(account_id,source_id),
    FOREIGN KEY(source_id,source_version) REFERENCES ma_source_version(source_id,version),
    CHECK ((phase = 'OCR_STEP' AND dimensions = 0 AND file_ref IS NOT NULL)
        OR (phase = 'EMBED_BATCH' AND dimensions > 0)),
    CHECK ((call_state = 'CONFIRMED' AND output_json IS NOT NULL AND output_sha256 IS NOT NULL
            AND request_count = 1 AND confirmed_at IS NOT NULL)
        OR (call_state IN ('CALL_INTENT','UNKNOWN') AND output_json IS NULL
            AND output_sha256 IS NULL AND request_count IS NULL AND total_tokens IS NULL
            AND confirmed_at IS NULL))
);

ALTER TABLE ma_retrieval_chunk ADD COLUMN method_metadata JSONB;
