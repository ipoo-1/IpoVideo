$ErrorActionPreference = 'Stop'
$base = 'http://localhost:9090'
$tempDir = Join-Path $env:TEMP 'dovideo-stage6-demo'
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

try {
    $null = Invoke-RestMethod -Uri "$base/health" -TimeoutSec 3
} catch {
    Write-Host "Backend is not running at $base."
    Write-Host "Start it first: cd D:\No1\DOVideo-AI-main\rebuild\backend; .\mvnw.cmd spring-boot:run"
    exit 1
}

# 1) register + login a fresh demo user
$username = 'chunk_demo_' + (Get-Date -Format 'HHmmss')
$regBody = @{ username = $username; password = 'secret123'; nickname = 'demo' } | ConvertTo-Json
Invoke-RestMethod -Uri "$base/api/auth/register" -Method Post -ContentType 'application/json' -Body $regBody | Out-Null
$loginBody = @{ username = $username; password = 'secret123' } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$base/api/auth/login" -Method Post -ContentType 'application/json' -Body $loginBody
$token = $login.data.token
Write-Host "1) logged in: $username"

# 2) init chunk upload: 3 parts
$init = Invoke-RestMethod -Uri "$base/api/media/upload/init" -Method Post `
    -Headers @{ Authorization = "Bearer $token" } `
    -ContentType 'application/json' `
    -Body '{"filename":"demo.mp4","totalParts":3}'
$uploadId = $init.data.uploadId
Write-Host "2) init uploadId=$uploadId totalParts=3"

# prepare part files (parts 1 and 2 must be >= 5MiB for MinIO compose)
$part1 = Join-Path $tempDir 'part1.bin'
$part2 = Join-Path $tempDir 'part2.bin'
$part3 = Join-Path $tempDir 'part3.bin'
$bigPart = New-Object byte[] (6 * 1024 * 1024)
[System.IO.File]::WriteAllBytes($part1, $bigPart)
[System.IO.File]::WriteAllBytes($part2, $bigPart)
[System.IO.File]::WriteAllBytes($part3, [byte[]](1, 2, 3))

# 3) upload part 1 and part 3, intentionally skip part 2
$null = curl.exe -s -X POST -H "Authorization: Bearer $token" `
    -F "uploadId=$uploadId" -F "partNumber=1" -F "file=@$part1" "$base/api/media/upload/part"
$null = curl.exe -s -X POST -H "Authorization: Bearer $token" `
    -F "uploadId=$uploadId" -F "partNumber=3" -F "file=@$part3" "$base/api/media/upload/part"
Write-Host "3) uploaded part 1 and part 3, skipped part 2"

# 4) complete with missing part 2 -> expect 400
try {
    $null = Invoke-RestMethod -Uri "$base/api/media/upload/complete" -Method Post `
        -Headers @{ Authorization = "Bearer $token" } `
        -ContentType 'application/json' `
        -Body ('{"uploadId":"' + $uploadId + '"}')
    Write-Host "4) unexpected: complete succeeded without part 2"
} catch {
    Write-Host ("4) complete with missing part -> HTTP {0} (expected 400)" -f $_.Exception.Response.StatusCode.value__)
}

# 5) upload the missing part 2
$null = curl.exe -s -X POST -H "Authorization: Bearer $token" `
    -F "uploadId=$uploadId" -F "partNumber=2" -F "file=@$part2" "$base/api/media/upload/part"
Write-Host "5) uploaded part 2"

# 6) complete again -> expect success
$done = Invoke-RestMethod -Uri "$base/api/media/upload/complete" -Method Post `
    -Headers @{ Authorization = "Bearer $token" } `
    -ContentType 'application/json' `
    -Body ('{"uploadId":"' + $uploadId + '"}')
Write-Host ("6) complete -> code={0} filePath={1}" -f $done.code, $done.data.filePath)

Write-Host ""
Write-Host "7) verify in MinIO console (http://localhost:9001, minioadmin/minioadmin):"
Write-Host "   media bucket should contain the final object; tmp/ should be empty"
Write-Host "   then check MySQL media_files.file_path"
