<#
  IncuSense v3 E2E test over the Docker COMPOSE NETWORK (bypasses the host port proxy).
  Auth model: self-registration is DISABLED; uses the pre-seeded accounts
     operator / operator123  (lab_alpha, sees the default firmware data)
     admin    / admin123     (lab_system, used here only for tenant-isolation check)
  REST via a curl container -> http://backend:8080 ; MQTT via the mosquitto container.
  Run:  powershell -ExecutionPolicy Bypass -File .\scripts\e2e_test_net.ps1
#>
$ErrorActionPreference = 'Stop'
$net  = 'incusense_default'
$curl = 'curlimages/curl:latest'
$work = Join-Path $PSScriptRoot '.tmp'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$pass = 0; $fail = 0
function Check($n,$c){ if($c){Write-Host "  [PASS] $n" -ForegroundColor Green;$script:pass++}else{Write-Host "  [FAIL] $n" -ForegroundColor Red;$script:fail++} }

function Post($path,$obj){
    [System.IO.File]::WriteAllText("$work\body.json", ($obj | ConvertTo-Json -Compress))
    $o = docker run --rm --network $net -v "${work}:/work" $curl -s -H "Content-Type: application/json" -X POST --data "@/work/body.json" "http://backend:8080$path"
    if([string]::IsNullOrWhiteSpace($o)){ return $null }
    return ($o | ConvertFrom-Json)
}
function Get-($path,$token){
    $o = docker run --rm --network $net $curl -s -H "Authorization: Bearer $token" "http://backend:8080$path"
    if([string]::IsNullOrWhiteSpace($o)){ return $null }
    return ($o | ConvertFrom-Json)
}
function StatusPost($path,$obj){
    [System.IO.File]::WriteAllText("$work\body.json", ($obj | ConvertTo-Json -Compress))
    return (docker run --rm --network $net -v "${work}:/work" $curl -s -o /dev/null -w "%{http_code}" -H "Content-Type: application/json" -X POST --data "@/work/body.json" "http://backend:8080$path")
}
function StatusGet($path,$token){
    return (docker run --rm --network $net $curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $token" "http://backend:8080$path")
}

# 0) Self-registration must be disabled --------------------------------------------
$regCode = StatusPost '/api/auth/register' @{ username="nope"; email="n@n.org"; password="pw123456"; labDisplayName="X" }
Check "self-registration disabled (register -> 403)" ($regCode -eq '403')

# 1) Login as the pre-seeded operator (lab_alpha) ----------------------------------
$op = Post '/api/auth/login' @{ username="operator"; password="operator123" }
$token = $op.token; $labId = $op.labId
Write-Host "Logged in as operator -> labId=$labId"
Check "operator login ok" ([bool]$token)
Check "operator is in lab_alpha" ($labId -eq 'lab_alpha')

# 2) Publish telemetry exactly as firmware (default hub on lab_alpha) ---------------
$hubKey = "${labId}_inc_01_node_a"
$topic  = "incusense/labs/$labId/hubs/$hubKey/telemetry"
Write-Host "Publishing to: $topic"
$baseTs = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
function Publish($off,$co2,$rail,$resp,$adc){
    $ts = $baseTs + $off
    $json="{`"ts`":$ts,`"co2_ppm`":$co2,`"heater_temp`":250.14,`"env_temp`":37.25,`"env_hum`":95.30,`"rail_12v`":$rail,`"raw_adc`":$adc,`"sensor_response`":$resp}"
    [System.IO.File]::WriteAllText("$work\payload.json",$json)
    docker cp "$work\payload.json" incusense-mqtt:/tmp/payload.json | Out-Null
    docker exec incusense-mqtt mosquitto_pub -h localhost -t $topic -f /tmp/payload.json
}
Publish 0 50000 12.05 0.180 1800; Start-Sleep -Milliseconds 300
Publish 1 50120 12.04 0.182 1805; Start-Sleep -Milliseconds 300
Publish 2 49980 12.06 0.179 1798; Start-Sleep -Milliseconds 300
Publish 3 50050 10.90 0.181 1802   # rail_12v=10.9 -> CRITICAL RAIL_12V
Start-Sleep -Seconds 2

# 3) REST verification (lab-scoped) -------------------------------------------------
$hubs = Get- '/api/hubs' $token
Check "hub auto-created under lab_alpha" ([bool]($hubs | Where-Object { $_.hubKey -eq $hubKey }))
$latest = Get- '/api/measurements/latest?limit=10' $token
Check "measurements via REST (>=4)" ($latest.Count -ge 4)
$top = $latest | Select-Object -First 1
Check "raw_adc persisted+exposed" ($null -ne $top.rawAdc)
Check "sensor_response persisted+exposed" ($null -ne $top.sensorResponse)
$alerts = Get- '/api/alerts' $token
Check "CRITICAL RAIL_12V alert raised" ([bool]($alerts | Where-Object { $_.ruleKey -eq 'RAIL_12V' -and $_.level -eq 'CRITICAL' }))
$health = Get- "/api/hubs/$hubKey/health" $token
Check "sensor health present" ($health.status -in @('OK','DEGRADING','CRITICAL','UNKNOWN'))
Write-Host "  health: status=$($health.status) hours=$($health.operatingHours) drift=$($health.observedDriftPct) resp=$($health.lastResponse)"

# 4) TimescaleDB direct check -------------------------------------------------------
$dbCount = (docker exec incusense-db psql -U incusense_user -d incusense -t -A -c "SELECT count(*) FROM measurements m JOIN sensing_hubs h ON h.id=m.hub_id JOIN labs l ON l.id=h.lab_id WHERE l.lab_id='$labId';").Trim()
Check "rows in measurements table (DB) >=4" ([int]$dbCount -ge 4)
Write-Host "  DB rows for ${labId}: $dbCount"
docker exec incusense-db psql -U incusense_user -d incusense -c "SELECT recorded_at, co2_ppm, rail_12v, raw_adc, sensor_response FROM measurements m JOIN sensing_hubs h ON h.id=m.hub_id JOIN labs l ON l.id=h.lab_id WHERE l.lab_id='$labId' ORDER BY recorded_at DESC LIMIT 3;"

# 5) Tenant isolation: admin (lab_system) must NOT see lab_alpha's hub --------------
$adm = Post '/api/auth/login' @{ username="admin"; password="admin123" }
$hubsAdmin = Get- '/api/hubs' $adm.token
Check "other lab (admin/lab_system) sees no lab_alpha hubs" (-not ($hubsAdmin | Where-Object { $_.hubKey -eq $hubKey }))
$code = StatusGet "/api/hubs/$hubKey/measurements" $adm.token
Check "cross-lab hub access -> 403" ($code -eq '403')

Write-Host ""
Write-Host "==================== RESULT: $pass passed, $fail failed ====================" -ForegroundColor Cyan
if ($fail -gt 0) { exit 1 }
