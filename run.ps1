$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
if (-not (Test-Path -LiteralPath '.env')) {
    $jwtBytes=New-Object byte[] 48
    $dbBytes=New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($jwtBytes)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($dbBytes)
    @("EV_JWT_SECRET=$([Convert]::ToHexString($jwtBytes))","EV_DB_PASSWORD=$([Convert]::ToHexString($dbBytes))") | Set-Content -Encoding ascii '.env'
}
docker compose up --build -d
if ($LASTEXITCODE -ne 0) {throw 'Case 6 Compose startup failed. Check Docker Desktop and case-reserved ports.'}
$ready=$false
for($attempt=0;$attempt -lt 120;$attempt++) {
    try {$health=Invoke-RestMethod 'http://127.0.0.1:8106/health' -TimeoutSec 2; if($health.status -eq 'ok') {$ready=$true;break}} catch {}
    Start-Sleep -Milliseconds 500
}
if(-not $ready) {throw 'Java API readiness timed out. Inspect docker compose logs api.'}
Write-Output 'Java API: http://localhost:8106/docs ; timeline/SOC: http://localhost:8106/demo'
