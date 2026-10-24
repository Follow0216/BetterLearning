ALTER TABLE question ADD CONSTRAINT uk_question_account_id UNIQUE (account_id, question_id);
CREATE TABLE question_answer (
    account_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    file_ref VARCHAR(36) NOT NULL UNIQUE,
    save_request_id VARCHAR(36) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',
    saved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (account_id, question_id),
    CONSTRAINT fk_question_answer_owner FOREIGN KEY (account_id, question_id)
        REFERENCES question(account_id, question_id),
    CONSTRAINT ck_question_answer_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_question_answer_completed CHECK ((state = 'COMPLETED') = (completed_at IS NOT NULL))
);
