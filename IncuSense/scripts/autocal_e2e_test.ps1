<#
  IncuSense — End-to-end test dell'AUTO-CALIBRAZIONE (loop chiuso piattaforma -> nodo).

  Esercita il contratto completo introdotto in V5:
    backend --(OFFSET_CAL su .../commands)--> nodo ;  nodo --(ACK su .../ack)--> backend ;
    lifecycle CalibrationEvent: PENDING -> ACKED -> VERIFIED, esposto da /api/calibration-events.

  Usa mosquitto_sub/pub dentro il container broker per simulare il nodo (nessun hardware).

  PREREQUISITI:
    1) stack su e sano:  cd IncuSense ; $env:DOCKER_BUILDKIT=0 ; docker compose up -d
    2) auto-calibrazione ABILITATA nel backend:  impostare AUTOCAL_ENABLED=true
       (env del servizio 'backend' in docker-compose.yml) e riavviare il backend.
  RUN:  powershell -ExecutionPolicy Bypass -File .\scripts\autocal_e2e_test.ps1
#>

$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
$pass = 0; $fail = 0
function Check($name, $cond) {
    if ($cond) { Write-Host "  [PASS] $name" -ForegroundColor Green; $script:pass++ }
    else       { Write-Host "  [FAIL] $name" -ForegroundColor Red;   $script:fail++ }
}
function Req($method, $path, $headers, $bodyObj) {
    $p = @{ Uri = "$base$path"; Method = $method; TimeoutSec = 8 }
    if ($headers) { $p.Headers = $headers }
    if ($bodyObj) { $p.Body = ($bodyObj | ConvertTo-Json); $p.ContentType = 'application/json' }
    return Invoke-RestMethod @p
}

# 1) Utente + lab ---------------------------------------------------------------
$u = "autocal_$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
$auth = Req 'Post' '/api/auth/register' $null @{ username=$u; email="$u@lab.org"; password="pw123456"; labDisplayName="AutoCal Lab" }
$labId = $auth.labId; $token = $auth.token; $hdr = @{ Authorization = "Bearer $token" }
$hubKey = "${labId}_inc_01_node_a"
$telemetry = "incusense/labs/$labId/hubs/$hubKey/telemetry"
$cmdTopic  = "incusense/labs/$labId/hubs/$hubKey/commands"
$ackTopic  = "incusense/labs/$labId/hubs/$hubKey/ack"
Write-Host "lab=$labId hub=$hubKey"

function PubTelemetry($co2, $resp) {
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $json = "{`"ts`":$ts,`"co2_ppm`":$co2,`"heater_temp`":250.0,`"env_temp`":37.0,`"env_hum`":95.0,`"rail_12v`":12.0,`"raw_adc`":1750,`"sensor_response`":$resp}"
    $f = "$env:TEMP\incusense_autocal.json"
    [System.IO.File]::WriteAllText($f, $json)
    docker cp $f incusense-mqtt:/tmp/ac.json | Out-Null
    docker exec incusense-mqtt mosquitto_pub -h localhost -t $telemetry -f /tmp/ac.json
}

# 2) Crea hub + sensor_health, assegna la curva Na:In2O3 e fissa l'install baseline
PubTelemetry 50000 0.20
Start-Sleep -Milliseconds 600
$curves = Req 'Get' '/api/drift-curves' $hdr $null
$curve  = $curves | Where-Object { $_.sensorType -eq 'NaIn2O3_CO2' } | Select-Object -First 1
Check "curva NaIn2O3 presente con coefficienti" ([bool]$curve -and ($curve.pointsJson -match '"b"\s*:\s*0.254'))
Req 'Post' "/api/hubs/$hubKey/health/assign-curve" $hdr @{ curveId=$curve.id; installResponse=0.20 } | Out-Null

# 3) Verifica che l'auto-cal sia abilitata (trigger manuale). Se 409 -> non abilitata.
$enabled = $true
try {
    # 3a) Avvia un subscriber sul topic comandi PRIMA del trigger (cattura 1 messaggio)
    $job = Start-Job -ScriptBlock { param($t)
        docker exec incusense-mqtt mosquitto_sub -h localhost -t $t -C 1 -W 12
    } -ArgumentList $cmdTopic
    Start-Sleep -Milliseconds 800

    $ev = Req 'Post' "/api/hubs/$hubKey/autocal/trigger" $hdr $null
    Check "trigger manuale -> evento PENDING" ($ev.status -eq 'PENDING' -and $ev.command -eq 'OFFSET_CAL')
    $eventId = $ev.id

    $cmd = Receive-Job -Job $job -Wait -ErrorAction SilentlyContinue
    Remove-Job $job -Force -ErrorAction SilentlyContinue
    Write-Host "  comando catturato sul broker: $cmd"
    Check "OFFSET_CAL pubblicato su .../commands con event_id" ([bool]($cmd -match '"command"\s*:\s*"OFFSET_CAL"') -and ($cmd -match "$eventId"))

    # 4) Il 'nodo' risponde con ACK OK
    $ackJson = "{`"hub_id`":`"$hubKey`",`"command`":`"OFFSET_CAL`",`"status`":`"OK`",`"event_id`":$eventId,`"ts`":$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())}"
    $af = "$env:TEMP\incusense_ack.json"; [System.IO.File]::WriteAllText($af, $ackJson)
    docker cp $af incusense-mqtt:/tmp/ack.json | Out-Null
    docker exec incusense-mqtt mosquitto_pub -h localhost -t $ackTopic -f /tmp/ack.json
    Start-Sleep -Milliseconds 800
    $events = Req 'Get' '/api/calibration-events' $hdr $null
    $mine = $events | Where-Object { $_.id -eq $eventId } | Select-Object -First 1
    Check "evento -> ACKED dopo ACK del nodo" ($mine.status -eq 'ACKED')

    # 5) Telemetria a regime al setpoint -> verifica post-cal -> VERIFIED
    for ($i=0; $i -lt 3; $i++) { PubTelemetry 50000 0.20; Start-Sleep -Milliseconds 500 }
    Start-Sleep -Milliseconds 600
    $events = Req 'Get' '/api/calibration-events' $hdr $null
    $mine = $events | Where-Object { $_.id -eq $eventId } | Select-Object -First 1
    Check "evento -> VERIFIED dopo telemetria a regime" ($mine.status -eq 'VERIFIED')
}
catch {
    $code = $null; if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode.value__ }
    if ($code -eq 409) {
        Write-Host "  [INFO] Auto-calibrazione DISABILITATA (HTTP 409)." -ForegroundColor Yellow
        Write-Host "         Imposta AUTOCAL_ENABLED=true sul servizio backend e riavvia, poi rilancia." -ForegroundColor Yellow
        $enabled = $false
    } else { throw }
}

Write-Host ""
if (-not $enabled) {
    Write-Host "==================== AUTOCAL OFF: test saltato ====================" -ForegroundColor Yellow
    exit 0
}
Write-Host "==================== RESULT: $pass passed, $fail failed ====================" -ForegroundColor Cyan
if ($fail -gt 0) { exit 1 }
