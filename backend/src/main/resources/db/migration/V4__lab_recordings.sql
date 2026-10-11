-- Additive only. Apply after V1-V3, before enabling LAB_RECORDING_ENABLED.
-- No employee data, API keys, request bodies or external detailed results are stored.
CREATE TABLE IF NOT EXISTS lab_campaigns (
    campaign_id VARCHAR(36) PRIMARY KEY,
    owner_account_id BIGINT NOT NULL REFERENCES user_accounts(id),
    plan_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS lab_runs (
    run_id VARCHAR(36) PRIMARY KEY,
    owner_account_id BIGINT NOT NULL REFERENCES user_accounts(id),
    campaign_id VARCHAR(36) REFERENCES lab_campaigns(campaign_id),
    case_id VARCHAR(36),
    instance_id VARCHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    planned_calls INTEGER NOT NULL CHECK (planned_calls BETWEEN 1 AND 20),
    observed_calls INTEGER NOT NULL DEFAULT 0 CHECK (observed_calls BETWEEN 0 AND 20),
    snapshot_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((campaign_id IS NULL AND case_id IS NULL) OR (campaign_id IS NOT NULL AND case_id IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS lab_runs_owner_updated_idx ON lab_runs(owner_account_id, updated_at);
CREATE INDEX IF NOT EXISTS lab_runs_case_idx ON lab_runs(campaign_id, case_id);
