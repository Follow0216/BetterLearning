-- Explicit, audited operator recovery; no automatic update of existing tutor turns.
CREATE TABLE ma_legacy_generation_recovery (
    operation_id VARCHAR(36) PRIMARY KEY,
    turn_id VARCHAR(36) NOT NULL UNIQUE,
    account_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    file_ref VARCHAR(36) NOT NULL,
    observed_updated_at TIMESTAMPTZ NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    operator_ref VARCHAR(120) NOT NULL,
    incident_ref VARCHAR(120) NOT NULL,
    database_principal VARCHAR(120) NOT NULL,
    executors_stopped_confirmed BOOLEAN NOT NULL DEFAULT TRUE CHECK (executors_stopped_confirmed),
    unknown_outcome_accepted BOOLEAN NOT NULL DEFAULT TRUE CHECK (unknown_outcome_accepted),
    recovered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
-- No FK: retain the operation evidence independently of later business-data lifecycle changes.

-- Also fences older binaries: this particular unknown-outcome terminal state can never be retried or overwritten.
CREATE FUNCTION ma_legacy_unknown_outcome_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status='FAILED' AND OLD.failure_code='GENERATION_OUTCOME_UNKNOWN'
        AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'legacy generation outcome is permanently unknown' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ma_legacy_unknown_outcome_terminal BEFORE UPDATE ON tutor_turn
    FOR EACH ROW EXECUTE FUNCTION ma_legacy_unknown_outcome_guard();
