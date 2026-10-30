$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$caseTenant='demo-'+[Guid]::NewGuid().ToString('N').Substring(0,12)
$accessToken=(docker compose exec -T api java -jar /app/app.jar --issue-token $caseTenant admin demo-admin).Trim()
if ($LASTEXITCODE -ne 0) {throw 'Start the stack with run.ps1 first.'}
$base='http://localhost:8106/api/v1'
$headers=@{Authorization="Bearer $accessToken"}
function Wait-Plan([string]$jobId) {
    for($i=0;$i -lt 300;$i++) {
        $job=Invoke-RestMethod "$base/jobs/$jobId" -Headers $headers
        if($job.status -eq 'SUCCEEDED') {return $job.result}
        if($job.status -in @('FAILED','SUPERSEDED')) {throw "Job ended: $($job.status) $($job.error)"}
        Start-Sleep -Milliseconds 100
    }
    throw 'Timed out waiting for the durable result.'
}
Invoke-RestMethod "$base/datasets" -Method Post -Headers $headers -ContentType 'application/json' -Body (Get-Content -Raw examples/demo-dataset.json)
$jh=@{Authorization="Bearer $accessToken";'Idempotency-Key'='initial'}
$job=Invoke-RestMethod "$base/plans" -Method Post -Headers $jh
$initial=Wait-Plan $job.jobId
Invoke-RestMethod "$base/plans/$initial/validate" -Method Post -Headers $headers
$event=@{eventId='drift';sequence=1;at=0;kind='soc';assetId='ev-b';energyWh=18000;location='office'} | ConvertTo-Json
$job=Invoke-RestMethod "$base/disruptions" -Method Post -Headers $headers -ContentType 'application/json' -Body $event
$repaired=Wait-Plan $job.jobId
Invoke-RestMethod "$base/plans/$repaired/validate" -Method Post -Headers $headers
$failure=@{eventId='plug-failed';sequence=1;at=0;kind='charger_failure';assetId='plug-1'} | ConvertTo-Json
$job=Invoke-RestMethod "$base/disruptions" -Method Post -Headers $headers -ContentType 'application/json' -Body $failure
$failed=Wait-Plan $job.jobId
Invoke-RestMethod "$base/plans/$failed/validate" -Method Post -Headers $headers
Invoke-RestMethod "$base/plans/$failed/backups" -Headers $headers | ConvertTo-Json -Depth 5
Write-Output 'Open http://localhost:8106/demo and paste this short-lived local token; do not publish it:'
Write-Output $accessToken
