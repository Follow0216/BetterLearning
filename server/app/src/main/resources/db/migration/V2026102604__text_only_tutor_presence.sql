-- AUTO resolves once inside the account-locked request creation transaction.
ALTER TABLE ma_request DROP CONSTRAINT ma_request_snapshot_kind_check;
ALTER TABLE ma_request ADD CONSTRAINT ma_request_snapshot_kind_check
    CHECK (snapshot_kind IN ('NEW_CAPTURE','REUSE_REQUEST','NO_ANSWER'));
ALTER TABLE ma_request ADD COLUMN requested_snapshot_kind VARCHAR(20) DEFAULT 'NEW_CAPTURE';
ALTER TABLE ma_request ADD COLUMN input_selection_reason VARCHAR(24);
ALTER TABLE ma_request ADD COLUMN question_markdown TEXT;
UPDATE ma_request SET requested_snapshot_kind=snapshot_kind;
ALTER TABLE ma_request ALTER COLUMN requested_snapshot_kind SET NOT NULL;
ALTER TABLE ma_request ADD CONSTRAINT ck_ma_requested_snapshot_kind
    CHECK (requested_snapshot_kind IN ('AUTO','NEW_CAPTURE','REUSE_REQUEST'));
ALTER TABLE ma_request ADD CONSTRAINT ck_ma_input_selection_reason
    CHECK (input_selection_reason IN ('TABLET_AVAILABLE','NO_DEVICE','OFFLINE','OTHER_QUESTION'));
ALTER TABLE ma_request ADD CONSTRAINT ck_ma_no_answer
    CHECK (snapshot_kind <> 'NO_ANSWER' OR (requested_snapshot_kind='AUTO' AND question_markdown IS NOT NULL
        AND source_request_id IS NULL AND snapshot_origin_request_id IS NULL AND snapshot_file_ref IS NULL
        AND snapshot_sha256 IS NULL AND snapshot_source_id IS NULL AND snapshot_version IS NULL));

CREATE TABLE ma_tablet_presence (
    account_id BIGINT NOT NULL REFERENCES app_user(account_id),
    session_id VARCHAR(36) NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence BETWEEN 1 AND 9007199254740991),
    question_id BIGINT,
    active BOOLEAN NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    CHECK (active = (question_id IS NOT NULL)),
    PRIMARY KEY (account_id, session_id)
);
