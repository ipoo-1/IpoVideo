ALTER TABLE analysis_tasks ADD COLUMN worker_id VARCHAR(64) NULL;
ALTER TABLE analysis_tasks ADD COLUMN lease_until TIMESTAMP NULL;
ALTER TABLE analysis_tasks ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;

CREATE INDEX idx_analysis_tasks_lease
    ON analysis_tasks (status, lease_until, id);
