CREATE TABLE question_collection (
    collection_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES app_user(account_id),
    name VARCHAR(64) NOT NULL CHECK (length(trim(name)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_question_collection_account_id UNIQUE (account_id, collection_id)
);

CREATE TABLE question (
    question_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES app_user(account_id),
    collection_id BIGINT,
    request_id VARCHAR(36) NOT NULL,
    subject VARCHAR(32) NOT NULL,
    body_markdown TEXT NOT NULL CHECK (length(body_markdown) > 0),
    summary VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_question_account_request UNIQUE (account_id, request_id),
    CONSTRAINT fk_question_owned_collection FOREIGN KEY (account_id, collection_id)
        REFERENCES question_collection(account_id, collection_id),
    CONSTRAINT ck_question_request_id CHECK (
        request_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_question_subject CHECK (subject IN ('HIGHER_MATHEMATICS', 'OTHER'))
);

CREATE INDEX ix_question_account_created ON question(account_id, created_at DESC, question_id DESC);
CREATE INDEX ix_question_account_collection_created
    ON question(account_id, collection_id, created_at DESC, question_id DESC);
