CREATE TABLE media_files (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    content_hash VARCHAR(64) NULL,
    upload_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

-- 查询“某个用户的视频列表”走组合索引 (user_id, upload_time)
CREATE INDEX idx_media_user_time ON media_files (user_id, upload_time);

-- 后台按状态扫任务走 (status, upload_time)
CREATE INDEX idx_media_status_time ON media_files (status, upload_time);
