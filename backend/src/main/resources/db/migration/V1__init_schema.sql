-- PerfTrace Database Schema V1

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS analysis_runs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    description VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'UPLOADING',
    error_message VARCHAR(1000),
    total_duration_seconds DOUBLE PRECISION,
    valid_data_duration_seconds DOUBLE PRECISION,
    cpu_bound_seconds DOUBLE PRECISION,
    gpu_bound_seconds DOUBLE PRECISION,
    balanced_seconds DOUBLE PRECISION,
    idle_seconds DOUBLE PRECISION,
    unknown_seconds DOUBLE PRECISION,
    throttle_duration_seconds DOUBLE PRECISION,
    hardware_flag_throttle_seconds DOUBLE PRECISION,
    heuristic_throttle_seconds DOUBLE PRECISION,
    peak_cpu_temp_c DOUBLE PRECISION,
    peak_gpu_temp_c DOUBLE PRECISION,
    avg_cpu_clock_mhz DOUBLE PRECISION,
    avg_gpu_clock_mhz DOUBLE PRECISION,
    total_rows INTEGER,
    valid_rows INTEGER,
    rejected_rows INTEGER,
    recognized_sensors VARCHAR(2000),
    missing_sensors VARCHAR(2000),
    data_quality_warnings VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_run_user ON analysis_runs(user_id);
CREATE INDEX IF NOT EXISTS idx_run_status ON analysis_runs(status);

CREATE TABLE IF NOT EXISTS telemetry_samples (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES analysis_runs(id) ON DELETE CASCADE,
    time_offset_seconds DOUBLE PRECISION NOT NULL,
    cpu_total_load DOUBLE PRECISION,
    cpu_max_thread_load DOUBLE PRECISION,
    cpu_temp_c DOUBLE PRECISION,
    cpu_clock_mhz DOUBLE PRECISION,
    gpu_load DOUBLE PRECISION,
    gpu_temp_c DOUBLE PRECISION,
    gpu_clock_mhz DOUBLE PRECISION,
    cpu_throttle_flag BOOLEAN,
    gpu_throttle_flag BOOLEAN,
    ram_usage_percent DOUBLE PRECISION,
    cpu_power_w DOUBLE PRECISION,
    gpu_power_w DOUBLE PRECISION
);

CREATE INDEX IF NOT EXISTS idx_sample_run_time ON telemetry_samples(run_id, time_offset_seconds);

CREATE TABLE IF NOT EXISTS detected_segments (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES analysis_runs(id) ON DELETE CASCADE,
    start_offset_seconds DOUBLE PRECISION NOT NULL,
    end_offset_seconds DOUBLE PRECISION NOT NULL,
    duration_seconds DOUBLE PRECISION NOT NULL,
    classification VARCHAR(20) NOT NULL,
    throttle_evidence VARCHAR(30) NOT NULL DEFAULT 'NONE',
    evidence_explanation VARCHAR(2000),
    avg_cpu_load DOUBLE PRECISION,
    avg_gpu_load DOUBLE PRECISION,
    avg_cpu_max_thread DOUBLE PRECISION,
    avg_cpu_temp_c DOUBLE PRECISION,
    avg_gpu_temp_c DOUBLE PRECISION,
    avg_cpu_clock_mhz DOUBLE PRECISION,
    avg_gpu_clock_mhz DOUBLE PRECISION,
    peak_cpu_temp_c DOUBLE PRECISION,
    peak_gpu_temp_c DOUBLE PRECISION,
    sample_count INTEGER
);

CREATE INDEX IF NOT EXISTS idx_segment_run_offset ON detected_segments(run_id, start_offset_seconds);
