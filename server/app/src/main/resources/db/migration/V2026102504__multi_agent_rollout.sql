CREATE TABLE ma_rollout_gate (
    singleton_id integer PRIMARY KEY CHECK (singleton_id = 1),
    mode varchar(8) NOT NULL CHECK (mode IN ('LEGACY', 'V2', 'DRAIN')),
    revision bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO ma_rollout_gate (singleton_id, mode) VALUES (1, 'LEGACY');
