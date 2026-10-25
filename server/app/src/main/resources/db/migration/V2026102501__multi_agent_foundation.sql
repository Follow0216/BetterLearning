-- P1: immutable source versions and account-scoped execution ledger.
CREATE TABLE ma_source (
    source_id VARCHAR(36) PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES app_user(account_id),
    type VARCHAR(24) NOT NULL CHECK (type IN ('QUESTION','SAVED_ANSWER','TUTOR_SNAPSHOT','TUTOR_REPLY','NOTE')),
    origin_id VARCHAR(160) NOT NULL,
    title TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DELETED')),
    current_version BIGINT NOT NULL DEFAULT 0 CHECK (current_version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (account_id,type,origin_id), UNIQUE (account_id,source_id)
);
CREATE TABLE ma_source_version (
    source_id VARCHAR(36) NOT NULL REFERENCES ma_source(source_id),
    version BIGINT NOT NULL CHECK (version >= 1),
    content_sha256 CHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    status VARCHAR(20) NOT NULL DEFAULT 'INDEX_PENDING' CHECK (status IN ('INDEX_PENDING','READY','FAILED','WITHDRAWN')),
    index_revision BIGINT NOT NULL DEFAULT 1 CHECK (index_revision >= 1),
    answer_state VARCHAR(16) CHECK (answer_state IN ('DRAFT','COMPLETED')),
    file_ref VARCHAR(36), body_markdown TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (source_id,version)
);
CREATE INDEX ix_ma_version_file ON ma_source_version(file_ref) WHERE file_ref IS NOT NULL;
CREATE TABLE ma_source_operation (
    account_id BIGINT NOT NULL REFERENCES app_user(account_id),
    operation_id VARCHAR(160) NOT NULL,
    payload_fingerprint CHAR(64) NOT NULL CHECK (payload_fingerprint ~ '^[0-9a-f]{64}$'),
    source_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (account_id,operation_id),
    FOREIGN KEY (source_id,version) REFERENCES ma_source_version(source_id,version)
);
CREATE TABLE ma_source_cursor (
    cursor_id VARCHAR(36) PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES app_user(account_id),
    type VARCHAR(24) NOT NULL,
    before_time TIMESTAMPTZ NOT NULL,
    before_id VARCHAR(36) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP + INTERVAL '1 day'
);
CREATE TABLE ma_anchor (
    anchor_id VARCHAR(36) PRIMARY KEY, source_id VARCHAR(36) NOT NULL, version BIGINT NOT NULL,
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('MARKDOWN_BLOCK','HANDWRITING_STEP','QUESTION_BLOCK')),
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0), content_sha256 CHAR(64) NOT NULL,
    locator_json TEXT NOT NULL, text TEXT,
    FOREIGN KEY (source_id,version) REFERENCES ma_source_version(source_id,version),
    UNIQUE (source_id,version,kind,ordinal), UNIQUE (source_id,version,anchor_id)
);
CREATE TABLE ma_request (
    request_id VARCHAR(36) NOT NULL, account_id BIGINT NOT NULL, question_id BIGINT NOT NULL,
    prompt VARCHAR(4000) NOT NULL CHECK (length(trim(prompt)) > 0),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('HINT','FULL_SOLUTION')),
    snapshot_kind VARCHAR(20) NOT NULL CHECK (snapshot_kind IN ('NEW_CAPTURE','REUSE_REQUEST')),
    source_request_id VARCHAR(36), snapshot_origin_request_id VARCHAR(36),
    snapshot_file_ref VARCHAR(36), snapshot_sha256 CHAR(64), snapshot_source_id VARCHAR(36), snapshot_version BIGINT,
    current_run_id VARCHAR(36), status VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0, input_deadline_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (account_id,question_id) REFERENCES question(account_id,question_id),
    FOREIGN KEY (snapshot_source_id,snapshot_version) REFERENCES ma_source_version(source_id,version),
    CHECK ((snapshot_file_ref IS NULL) = (snapshot_source_id IS NULL)),
    CHECK (status IN ('WAITING_INPUT','QUEUED','RUNNING','CANCELLING','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')),
    PRIMARY KEY (account_id,request_id)
);
CREATE UNIQUE INDEX uk_ma_request_active ON ma_request(account_id)
    WHERE status IN ('WAITING_INPUT','QUEUED','RUNNING','CANCELLING');
CREATE INDEX ix_ma_request_page ON ma_request(account_id,question_id,created_at DESC,request_id DESC);
CREATE TABLE ma_run (
    run_id VARCHAR(36) PRIMARY KEY, request_id VARCHAR(36) NOT NULL, account_id BIGINT NOT NULL,
    run_no INTEGER NOT NULL CHECK (run_no >= 1), retry_of_run_id VARCHAR(36) REFERENCES ma_run(run_id),
    status VARCHAR(20) NOT NULL CHECK (status IN ('QUEUED','RUNNING','CANCELLING','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')),
    revision BIGINT NOT NULL DEFAULT 0, reason VARCHAR(32), cancel_requested_at TIMESTAMPTZ,
    queue_deadline_at TIMESTAMPTZ NOT NULL, run_deadline_at TIMESTAMPTZ NOT NULL,
    model_calls INTEGER NOT NULL DEFAULT 0 CHECK (model_calls BETWEEN 0 AND 8),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (account_id,request_id) REFERENCES ma_request(account_id,request_id), UNIQUE (request_id,run_no)
);
ALTER TABLE ma_request ADD FOREIGN KEY (current_run_id) REFERENCES ma_run(run_id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE ma_retry (
    account_id BIGINT NOT NULL, idempotency_key VARCHAR(36) NOT NULL, request_id VARCHAR(36) NOT NULL,
    target_run_id VARCHAR(36) NOT NULL REFERENCES ma_run(run_id), run_id VARCHAR(36) NOT NULL REFERENCES ma_run(run_id),
    PRIMARY KEY (account_id,idempotency_key), FOREIGN KEY (account_id,request_id) REFERENCES ma_request(account_id,request_id)
);
CREATE TABLE ma_step (
    step_id VARCHAR(36) PRIMARY KEY, run_id VARCHAR(36) NOT NULL REFERENCES ma_run(run_id),
    role VARCHAR(16) NOT NULL CHECK (role IN ('RETRIEVE','SOLVE','DIAGNOSE','TEACH','REVIEW')),
    sequence INTEGER NOT NULL CHECK (sequence >= 1), status VARCHAR(16) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0, input_digest CHAR(64) NOT NULL, output_digest CHAR(64),
    UNIQUE (run_id,role,sequence)
);
CREATE TABLE ma_attempt (
    attempt_id VARCHAR(36) PRIMARY KEY, step_id VARCHAR(36) NOT NULL REFERENCES ma_step(step_id),
    attempt_no INTEGER NOT NULL CHECK (attempt_no >= 1), status VARCHAR(16) NOT NULL,
    provider_call_state VARCHAR(16) NOT NULL CHECK (provider_call_state IN ('NOT_SENT','CALL_INTENT','CONFIRMED','UNKNOWN')),
    fence BIGINT NOT NULL CHECK (fence >= 1), started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ, output TEXT, output_digest CHAR(64),
    provider_deadline_at TIMESTAMPTZ, provider_finished_at TIMESTAMPTZ, UNIQUE (step_id,attempt_no)
);
CREATE TABLE ma_evidence (
    evidence_id VARCHAR(36) PRIMARY KEY, run_id VARCHAR(36) NOT NULL REFERENCES ma_run(run_id),
    step_id VARCHAR(36) NOT NULL REFERENCES ma_step(step_id), type VARCHAR(20) NOT NULL,
    source_id VARCHAR(36), version BIGINT, anchor_id VARCHAR(36), claim TEXT NOT NULL, confidence DOUBLE PRECISION,
    FOREIGN KEY (source_id,version,anchor_id) REFERENCES ma_anchor(source_id,version,anchor_id),
    CHECK (type IN ('CURRENT_SNAPSHOT','HISTORY','DERIVATION')),
    CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1)
);
CREATE TABLE ma_result (
    result_id VARCHAR(36) PRIMARY KEY, run_id VARCHAR(36) NOT NULL UNIQUE REFERENCES ma_run(run_id),
    mode VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL CHECK (status IN ('VERIFIED','WITHHELD')),
    body_markdown TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE ma_citation (
    citation_id VARCHAR(36) PRIMARY KEY, result_id VARCHAR(36) NOT NULL REFERENCES ma_result(result_id),
    source_id VARCHAR(36) NOT NULL, version BIGINT NOT NULL, anchor_id VARCHAR(36) NOT NULL,
    label VARCHAR(64) NOT NULL, source_type VARCHAR(24) NOT NULL,
    FOREIGN KEY (source_id,version,anchor_id) REFERENCES ma_anchor(source_id,version,anchor_id)
);
CREATE TABLE ma_outbox (
    event_id VARCHAR(36) PRIMARY KEY, account_id BIGINT NOT NULL REFERENCES app_user(account_id),
    aggregate_type VARCHAR(16) NOT NULL, aggregate_id VARCHAR(36) NOT NULL, aggregate_version BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL, payload_digest CHAR(64) NOT NULL,
    index_revision BIGINT NOT NULL DEFAULT 0 CHECK (index_revision >= 0),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DELIVERED','FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (aggregate_type,aggregate_id,aggregate_version,kind,index_revision)
);
CREATE INDEX ix_ma_outbox_pending ON ma_outbox(created_at,event_id) WHERE status <> 'DELIVERED';

CREATE TABLE ma_cursor (
    cursor_id VARCHAR(36) PRIMARY KEY, account_id BIGINT NOT NULL REFERENCES app_user(account_id),
    question_id BIGINT NOT NULL, before_time TIMESTAMPTZ NOT NULL, before_id VARCHAR(36) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP + INTERVAL '1 day'
);

-- 旧题目与当前已明确保存的作答形成首个资料版本；原始文件不在迁移中读取或推断完好性。
INSERT INTO ma_source(source_id, account_id, type, origin_id, title, status, current_version, created_at)
SELECT gen_random_uuid()::text, q.account_id, 'QUESTION', q.question_id::text,
       q.subject, 'ACTIVE', 1, q.created_at
FROM question q;

INSERT INTO ma_source_version(source_id, version, content_sha256, status, body_markdown, created_at)
SELECT s.source_id, 1,
       encode(sha256(convert_to(replace(q.body_markdown, E'\r\n', E'\n'), 'UTF8')), 'hex'),
       'INDEX_PENDING', replace(q.body_markdown, E'\r\n', E'\n'), q.created_at
FROM question q
JOIN ma_source s ON s.account_id = q.account_id AND s.type = 'QUESTION'
    AND s.origin_id = q.question_id::text;

INSERT INTO ma_source(source_id, account_id, type, origin_id, title, status, current_version, created_at)
SELECT gen_random_uuid()::text, a.account_id, 'SAVED_ANSWER', a.question_id::text,
       '作答 ' || a.question_id::text, 'ACTIVE', 1, a.saved_at
FROM question_answer a;

INSERT INTO ma_source_version(source_id, version, content_sha256, status,
        answer_state, file_ref, created_at)
SELECT s.source_id, 1, a.request_hash, 'INDEX_PENDING',
       CASE WHEN a.state = 'COMPLETED' THEN 'COMPLETED' ELSE 'DRAFT' END,
       a.file_ref, a.saved_at
FROM question_answer a
JOIN ma_source s ON s.account_id = a.account_id AND s.type = 'SAVED_ANSWER'
    AND s.origin_id = a.question_id::text;

INSERT INTO ma_outbox(event_id, account_id, aggregate_type, aggregate_id, aggregate_version,
        kind, payload_digest, index_revision, status, created_at)
SELECT gen_random_uuid()::text, s.account_id, 'SOURCE', v.source_id, v.version,
       'SOURCE_VERSION_CREATED', v.content_sha256, v.index_revision, 'PENDING', v.created_at
FROM ma_source_version v JOIN ma_source s ON s.source_id = v.source_id;
