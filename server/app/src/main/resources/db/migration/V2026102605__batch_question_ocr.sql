-- 独立短生命周期批次身份；只保存文本、结构化草稿和进度，不保存原始图片。
CREATE TABLE ocr_batch (
    account_id BIGINT NOT NULL REFERENCES app_user(account_id) ON DELETE CASCADE,
    request_id VARCHAR(36) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    completed_tiles INTEGER NOT NULL DEFAULT 0,
    total_tiles INTEGER NOT NULL DEFAULT 0,
    result_json TEXT,
    error_code VARCHAR(64),
    message VARCHAR(256),
    worker_owner VARCHAR(36),
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(account_id,request_id),
    CONSTRAINT ck_ocr_batch_status CHECK(status IN
        ('QUEUED','RUNNING','COMPLETED','INCOMPLETE','EMPTY','FAILED','CANCELLED','UNKNOWN')),
    CONSTRAINT ck_ocr_batch_progress CHECK(completed_tiles >= 0 AND total_tiles >= completed_tiles),
    CONSTRAINT ck_ocr_batch_lease CHECK((worker_owner IS NULL) = (lease_until IS NULL)
        AND (status NOT IN ('QUEUED','RUNNING') OR worker_owner IS NOT NULL)
        AND (worker_owner IS NULL OR status IN ('QUEUED','RUNNING','CANCELLED'))),
    CONSTRAINT ck_ocr_batch_result_budget CHECK(result_json IS NULL OR octet_length(result_json) <= 4194304)
);
CREATE UNIQUE INDEX uk_ocr_batch_active_account ON ocr_batch(account_id)
    WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX ix_ocr_batch_worker_lease ON ocr_batch(lease_until)
    WHERE worker_owner IS NOT NULL;
