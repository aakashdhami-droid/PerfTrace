-- PerfTrace Database Schema V2
-- Workload metadata supplied by the user. Never inferred automatically.

ALTER TABLE analysis_runs
    ADD COLUMN IF NOT EXISTS workload_type VARCHAR(30) NOT NULL DEFAULT 'UNSPECIFIED';

CREATE INDEX IF NOT EXISTS idx_run_user_workload ON analysis_runs(user_id, workload_type);
