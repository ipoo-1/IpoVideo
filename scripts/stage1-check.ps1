$ErrorActionPreference = 'Stop'
$base = 'http://localhost:9090'

try {
    $health = Invoke-RestMethod -Uri "$base/health" -TimeoutSec 3
} catch {
    Write-Host "Backend is not running at $base."
    Write-Host "Start it first: cd D:\No1\DOVideo-AI-main\rebuild\backend; .\mvnw.cmd spring-boot:run"
    exit 1
}

Write-Host ""
Write-Host "1) /health"
$health | ConvertTo-Json -Compress

$username = 'coder_' + (Get-Date -Format 'HHmmss')
$body = @{ username = $username; password = 'secret123'; nickname = 'student' } | ConvertTo-Json

Write-Host ""
Write-Host "2) register ($username)"
$reg = Invoke-RestMethod -Uri "$base/api/auth/register" -Method Post -ContentType 'application/json' -Body $body
$reg | ConvertTo-Json -Compress

Write-Host ""
Write-Host "3) login"
$login = Invoke-RestMethod -Uri "$base/api/auth/login" -Method Post -ContentType 'application/json' -Body $body
$token = $login.data.token
Write-Host ("token prefix: " + $token.Substring(0, 12))

Write-Host ""
Write-Host "4) /api/me"
$me = Invoke-RestMethod -Uri "$base/api/me" -Headers @{ Authorization = "Bearer $token" }
$me | ConvertTo-Json -Compress

Write-Host ""
Write-Host "Stage 1 verification complete."
