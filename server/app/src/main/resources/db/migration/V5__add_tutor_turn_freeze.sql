CREATE TABLE tutor_turn (
    turn_id VARCHAR(36) PRIMARY KEY,
    account_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    prompt VARCHAR(4000) NOT NULL CHECK (length(trim(prompt)) > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING_ANSWER',
    file_ref VARCHAR(36) UNIQUE,
    request_hash CHAR(64),
    answer_markdown TEXT,
    failure_code VARCHAR(64),
    failure_message VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tutor_turn_owned_question FOREIGN KEY (account_id, question_id)
        REFERENCES question(account_id, question_id),
    CONSTRAINT uk_tutor_turn_request UNIQUE (account_id, request_id),
    CONSTRAINT ck_tutor_turn_ids CHECK (
        turn_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND request_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_tutor_turn_file CHECK ((file_ref IS NULL) = (request_hash IS NULL)),
    CONSTRAINT ck_tutor_turn_result CHECK (
        (status = 'WAITING_ANSWER' AND answer_markdown IS NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'GENERATING' AND file_ref IS NOT NULL AND answer_markdown IS NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'SUCCEEDED' AND file_ref IS NOT NULL AND answer_markdown IS NOT NULL AND length(trim(answer_markdown)) > 0 AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'FAILED' AND answer_markdown IS NULL AND failure_code IS NOT NULL AND failure_message IS NOT NULL
            AND length(trim(failure_code)) > 0 AND length(trim(failure_message)) > 0))
);
CREATE UNIQUE INDEX uk_tutor_turn_active ON tutor_turn(account_id)
    WHERE status IN ('WAITING_ANSWER', 'GENERATING');
CREATE INDEX ix_tutor_turn_question_created ON tutor_turn(account_id, question_id, created_at DESC, turn_id DESC);
