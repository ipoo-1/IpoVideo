-- Stage 2 初始化脚本：创建数据库和应用账号
-- 用法：mysql -u root -p < init-mysql.sql

CREATE DATABASE IF NOT EXISTS dovideo
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'dovideo'@'localhost' IDENTIFIED BY 'dovideo123';

GRANT ALL PRIVILEGES ON dovideo.* TO 'dovideo'@'localhost';

FLUSH PRIVILEGES;
