CREATE TABLE analysis_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    media_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    goal VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    current_stage VARCHAR(64) NULL,
    progress INT NOT NULL DEFAULT 0,
    result LONGTEXT NULL,
    error_message VARCHAR(1000) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_analysis_tasks_media FOREIGN KEY (media_id) REFERENCES media_files (id),
    CONSTRAINT fk_analysis_tasks_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_analysis_tasks_user_time ON analysis_tasks (user_id, created_at);
CREATE INDEX idx_analysis_tasks_status_time ON analysis_tasks (status, created_at);
