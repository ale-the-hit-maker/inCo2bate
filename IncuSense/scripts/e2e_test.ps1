<#
  IncuSense v3 — End-to-end test: sensing unit (firmware) -> platform -> DB.

  Reproduces the EXACT MQTT publish the firmware performs (same lab-scoped topic
  and same JSON payload, incl. the v3 drift fields raw_adc / sensor_response),
  then verifies ingestion, REST exposure, TimescaleDB persistence, alerts,
  sensor health, and tenant isolation.

  Prereq: stack up & healthy ->  cd IncuSense ; $env:DOCKER_BUILDKIT=0 ; docker compose up -d
  Run:    powershell -ExecutionPolicy Bypass -File .\scripts\e2e_test.ps1
#>

$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
$pass = 0; $fail = 0
function Check($name, $cond) {
    if ($cond) { Write-Host "  [PASS] $name" -ForegroundColor Green; $script:pass++ }
    else       { Write-Host "  [FAIL] $name" -ForegroundColor Red;   $script:fail++ }
}
# Retry wrapper: Docker Desktop's host->container port proxy is occasionally flaky.
function Req($method, $path, $headers, $bodyObj) {
    for ($try = 1; $try -le 5; $try++) {
        try {
            $p = @{ Uri = "$base$path"; Method = $method; TimeoutSec = 8 }
            if ($headers) { $p.Headers = $headers }
            if ($bodyObj) { $p.Body = ($bodyObj | ConvertTo-Json); $p.ContentType = 'application/json' }
            return Invoke-RestMethod @p
        } catch {
            $resp = $_.Exception.Response
            if ($resp) { throw }              # real HTTP error -> propagate
            if ($try -eq 5) { throw }         # transport flake -> retry
            Start-Sleep -Milliseconds 600
        }
    }
}

# 1) Register a new user + lab ---------------------------------------------------
$u = "tester_$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
$auth = Req 'Post' '/api/auth/register' $null @{ username=$u; email="$u@lab.org"; password="pw123456"; labDisplayName="E2E Test Lab" }
$labId = $auth.labId; $token = $auth.token
Write-Host "Registered '$u' -> labId=$labId"
Check "register returns labId" ([bool]$labId)
Check "register returns token" ([bool]$token)
$hdr = @{ Authorization = "Bearer $token" }

# 2) Firmware's exact topic + hub key -------------------------------------------
$hubKey = "${labId}_inc_01_node_a"
$topic  = "incusense/labs/$labId/hubs/$hubKey/telemetry"
Write-Host "Publishing to: $topic"

# 3) Publish telemetry exactly as the firmware serializes it (BOM-free via file) -
function Publish($co2, $rail, $resp, $adc) {
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $json = "{`"ts`":$ts,`"co2_ppm`":$co2,`"heater_temp`":250.14,`"env_temp`":37.25,`"env_hum`":95.30,`"rail_12v`":$rail,`"raw_adc`":$adc,`"sensor_response`":$resp}"
    $f = "$env:TEMP\incusense_payload.json"
    [System.IO.File]::WriteAllText($f, $json)           # UTF-8 NO BOM
    docker cp $f incusense-mqtt:/tmp/payload.json | Out-Null
    docker exec incusense-mqtt mosquitto_pub -h localhost -t $topic -f /tmp/payload.json
}
Publish 50000 12.05 0.180 1800
Start-Sleep -Milliseconds 400
Publish 50120 12.04 0.182 1805
Start-Sleep -Milliseconds 400
Publish 49980 12.06 0.179 1798
Start-Sleep -Milliseconds 400
Publish 50050 10.90 0.181 1802   # rail_12v=10.9 -> CRITICAL RAIL_12V
Start-Sleep -Seconds 2

# 4) Verify via REST (lab-scoped) -----------------------------------------------
$hubs = Req 'Get' '/api/hubs' $hdr $null
Check "hub auto-created under lab" ([bool]($hubs | Where-Object { $_.hubKey -eq $hubKey }))

$latest = Req 'Get' '/api/measurements/latest?limit=10' $hdr $null
Check "measurements returned via REST" ($latest.Count -ge 4)
$top = $latest | Select-Object -First 1
Check "drift field raw_adc persisted+exposed" ($null -ne $top.rawAdc)
Check "drift field sensor_response persisted+exposed" ($null -ne $top.sensorResponse)

$alerts = Req 'Get' '/api/alerts' $hdr $null
Check "CRITICAL RAIL_12V alert raised" ([bool]($alerts | Where-Object { $_.ruleKey -eq 'RAIL_12V' -and $_.level -eq 'CRITICAL' }))

$health = Req 'Get' "/api/hubs/$hubKey/health" $hdr $null
Check "sensor health record present" ($null -ne $health.status)
Write-Host "  health: status=$($health.status) operatingHours=$($health.operatingHours) drift=$($health.observedDriftPct)"

# 5) Verify directly in TimescaleDB ---------------------------------------------
$dbCount = (docker exec incusense-db psql -U incusense_user -d incusense -t -A -c `
    "SELECT count(*) FROM measurements m JOIN sensing_hubs h ON h.id=m.hub_id JOIN labs l ON l.id=h.lab_id WHERE l.lab_id='$labId';").Trim()
Check "rows present in measurements table (DB)" ([int]$dbCount -ge 4)
Write-Host "  DB rows for lab ${labId}: $dbCount"
docker exec incusense-db psql -U incusense_user -d incusense -c `
    "SELECT recorded_at, co2_ppm, rail_12v, raw_adc, sensor_response FROM measurements m JOIN sensing_hubs h ON h.id=m.hub_id JOIN labs l ON l.id=h.lab_id WHERE l.lab_id='$labId' ORDER BY recorded_at DESC LIMIT 3;"

# 6) Tenant isolation -----------------------------------------------------------
$u2 = "tester2_$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
$auth2 = Req 'Post' '/api/auth/register' $null @{ username=$u2; email="$u2@lab.org"; password="pw123456"; labDisplayName="Other Lab" }
$hdr2 = @{ Authorization = "Bearer $($auth2.token)" }
$hubs2 = Req 'Get' '/api/hubs' $hdr2 $null
Check "tenant isolation: other lab sees no hubs" ($hubs2.Count -eq 0)
$iso = 0
try { Invoke-RestMethod -Uri "$base/api/hubs/$hubKey/measurements" -Headers $hdr2 -TimeoutSec 8 | Out-Null }
catch { $iso = [int]$_.Exception.Response.StatusCode.value__ }
Check "tenant isolation: cross-lab hub access -> 403" ($iso -eq 403)

Write-Host ""
Write-Host "==================== RESULT: $pass passed, $fail failed ====================" -ForegroundColor Cyan
if ($fail -gt 0) { exit 1 }
