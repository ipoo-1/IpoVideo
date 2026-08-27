$ErrorActionPreference = 'Stop'
$base = 'http://localhost:9090'
$file = 'D:\No1\DOVideo-AI-main\README.md'

try {
    $null = Invoke-RestMethod -Uri "$base/health" -TimeoutSec 3
} catch {
    Write-Host "Backend is not running at $base."
    Write-Host "Start it first: cd D:\No1\DOVideo-AI-main\rebuild\backend; .\mvnw.cmd spring-boot:run"
    exit 1
}

# 1) register + login a fresh demo user
$username = 'demo_' + (Get-Date -Format 'HHmmss')
$authBody = @{ username = $username; password = 'secret123'; nickname = 'demo' } | ConvertTo-Json
Invoke-RestMethod -Uri "$base/api/auth/register" -Method Post -ContentType 'application/json' -Body $authBody | Out-Null
$loginBody = @{ username = $username; password = 'secret123' } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$base/api/auth/login" -Method Post -ContentType 'application/json' -Body $loginBody
$token = $login.data.token
Write-Host "1) logged in: $username"

# 2) upload a dummy file as the video
$uploadJson = curl.exe -s -X POST -H "Authorization: Bearer $token" -F "file=@$file" "$base/api/media/upload"
$upload = $uploadJson | ConvertFrom-Json
$mediaId = $upload.data.id
Write-Host "2) uploaded mediaId=$mediaId"

# 3) create task, measure how fast the API returns
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$createBody = @{ mediaId = $mediaId; goal = 'organize key points' } | ConvertTo-Json
$created = Invoke-RestMethod -Uri "$base/api/tasks" -Method Post `
    -Headers @{ Authorization = "Bearer $token" } `
    -ContentType 'application/json' -Body $createBody
$sw.Stop()
$taskId = $created.data.id
Write-Host ("3) create returned in {0} ms, status={1}  <-- async proof" -f $sw.ElapsedMilliseconds, $created.data.status)

# 4) open SSE connection in background so we can watch pushed events
$sseUrl = "$base/api/tasks/$taskId/events?token=$token"
$sseJob = Start-Job -ScriptBlock { param($url) curl.exe -s -N $url } -ArgumentList $sseUrl

# 5) poll status while the worker thread runs
$final = $created.data
for ($i = 0; $i -lt 200; $i++) {
    Start-Sleep -Milliseconds 200
    $task = Invoke-RestMethod -Uri "$base/api/tasks/$taskId" -Headers @{ Authorization = "Bearer $token" }
    $final = $task.data
    Write-Host ("   status={0} progress={1} stage={2}" -f $final.status, $final.progress, $final.currentStage)
    if ($final.status -eq 'SUCCESS' -or $final.status -eq 'FAILED') {
        break
    }
}
Write-Host "4) final status=$($final.status)"

# 6) collect what SSE pushed
$null = Wait-Job -Job $sseJob -Timeout 5
$sseOutput = Receive-Job -Job $sseJob 2>$null
Stop-Job -Job $sseJob -ErrorAction SilentlyContinue
Remove-Job -Job $sseJob -Force -ErrorAction SilentlyContinue
Write-Host ""
Write-Host "5) SSE events received:"
$sseOutput | ForEach-Object { Write-Host "   $_" }
Write-Host ""
Write-Host "Demo finished."
