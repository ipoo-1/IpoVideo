param(
    [switch]$UseRealAI
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

Write-Host "Starting local middleware..."
docker compose up -d mysql redis minio qdrant rocketmq-namesrv rocketmq-broker

$env:DB_URL = "jdbc:mysql://localhost:3306/dovideo?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true"
$env:DB_USERNAME = "dovideo"
$env:DB_PASSWORD = "dovideo123"
$env:MINIO_ENDPOINT = "http://localhost:9000"
$env:MINIO_ACCESS_KEY = "minioadmin"
$env:MINIO_SECRET_KEY = "minioadmin"
$env:MINIO_BUCKET = "media"
$env:REDIS_HOST = "localhost"
$env:REDIS_PORT = "6379"
$env:ROCKETMQ_NAME_SERVER = "127.0.0.1:9876"
$env:QDRANT_URL = "http://localhost:6333"
$env:ANALYSIS_DEMO_MODE = if ($UseRealAI) { "false" } else { "true" }

Write-Host "Starting IpoVideo backend..."
Set-Location "$repo\backend"
.\mvnw.cmd spring-boot:run
