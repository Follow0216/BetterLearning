-- Organization and captured provenance are independent from immutable NOTE content versions.
CREATE TABLE ma_notebook (
    account_id BIGINT NOT NULL REFERENCES app_user(account_id),
    notebook_id VARCHAR(36) NOT NULL,
    name VARCHAR(64) NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 64),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, notebook_id)
);
CREATE TABLE ma_note_context (
    account_id BIGINT NOT NULL,
    note_id VARCHAR(36) NOT NULL,
    notebook_id VARCHAR(36),
    question_id BIGINT,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    result_id VARCHAR(36) REFERENCES ma_result(result_id),
    turn_id VARCHAR(36) REFERENCES tutor_turn(turn_id),
    request_id VARCHAR(36),
    source_id VARCHAR(36),
    source_version BIGINT,
    version_hash CHAR(64),
    excerpt_text TEXT,
    PRIMARY KEY (account_id, note_id),
    FOREIGN KEY (account_id, note_id) REFERENCES ma_note_request(account_id, note_id),
    FOREIGN KEY (account_id, notebook_id) REFERENCES ma_notebook(account_id, notebook_id),
    FOREIGN KEY (account_id, question_id) REFERENCES question(account_id, question_id),
    FOREIGN KEY (source_id, source_version) REFERENCES ma_source_version(source_id, version),
    CHECK ((source_id IS NULL) = (source_version IS NULL)),
    CHECK ((result_id IS NULL AND turn_id IS NULL AND excerpt_text IS NULL AND version_hash IS NULL)
        OR (question_id IS NOT NULL AND excerpt_text IS NOT NULL AND version_hash IS NOT NULL
            AND ((result_id IS NOT NULL AND turn_id IS NULL AND source_id IS NOT NULL)
                OR (result_id IS NULL AND turn_id IS NOT NULL AND source_id IS NULL))))
);
INSERT INTO ma_note_context(account_id,note_id) SELECT account_id,note_id FROM ma_note_request;
CREATE INDEX ix_ma_note_notebook ON ma_note_context(account_id,notebook_id,note_id);
CREATE INDEX ix_ma_note_question ON ma_note_context(account_id,question_id,note_id);
