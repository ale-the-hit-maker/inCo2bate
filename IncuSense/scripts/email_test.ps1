<#
  Verifica la pipeline di notifica EMAIL usando Mailpit (cattura-email locale).
  Prerequisito:
     docker compose -f docker-compose.yml -f docker-compose.mailtest.yml up -d
  Esecuzione:
     powershell -ExecutionPolicy Bypass -File .\scripts\email_test.ps1
  Le email catturate sono anche visibili su http://localhost:8025
#>
$ErrorActionPreference = 'Stop'
$net  = 'incusense_default'
$curl = 'curlimages/curl:latest'
$work = Join-Path $PSScriptRoot '.tmp'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$testEmail = 'destinatario.test@example.com'

function Post($path,$obj){
    [System.IO.File]::WriteAllText("$work\body.json", ($obj | ConvertTo-Json -Compress))
    $o = docker run --rm --network $net -v "${work}:/work" $curl -s -H "Content-Type: application/json" -X POST --data "@/work/body.json" "http://backend:8080$path"
    return ($o | ConvertFrom-Json)
}

# 1) Login con l'operatore pre-registrato (lab_alpha) e contatto di notifica
$auth = Post '/api/auth/login' @{ username="operator"; password="operator123" }
$labId = $auth.labId
Write-Host "Lab: $labId (operator)"
[System.IO.File]::WriteAllText("$work\c.json", (@{ label="Test"; channel="EMAIL"; target=$testEmail; minLevel="WARN"; enabled=$true } | ConvertTo-Json -Compress))
docker run --rm --network $net -v "${work}:/work" $curl -s -H "Content-Type: application/json" -H "Authorization: Bearer $($auth.token)" -X POST --data "@/work/c.json" "http://backend:8080/api/notification-contacts" | Out-Null
Write-Host "Contatto di notifica creato -> $testEmail"

# 2) Telemetria con guasto (rail_12v=10.9 -> alert CRITICAL RAIL_12V)
#    Hub univoco per evitare il cooldown anti-ripetizione (per-hub+regola, default 5 min).
$hubKey = "${labId}_mailtest_$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
$topic  = "incusense/labs/$labId/hubs/$hubKey/telemetry"
$ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$json = "{`"ts`":$ts,`"co2_ppm`":50000.0,`"heater_temp`":250.1,`"env_temp`":37.2,`"env_hum`":95.3,`"rail_12v`":10.90,`"raw_adc`":1802,`"sensor_response`":0.181}"
[System.IO.File]::WriteAllText("$work\payload.json",$json)
docker cp "$work\payload.json" incusense-mqtt:/tmp/payload.json | Out-Null
docker exec incusense-mqtt mosquitto_pub -h localhost -t $topic -f /tmp/payload.json
Write-Host "Telemetria con guasto pubblicata su $topic"
Start-Sleep -Seconds 3

# 3) Verifica su Mailpit (API)
$mp = docker run --rm --network $net $curl -s "http://mailpit:8025/api/v1/messages" | ConvertFrom-Json
Write-Host ""
Write-Host "Email catturate da Mailpit: $($mp.total)"
$msg = $mp.messages | Where-Object { ($_.To | ForEach-Object { $_.Address }) -contains $testEmail } | Select-Object -First 1
if ($msg) {
    Write-Host "  [PASS] email recapitata a $testEmail" -ForegroundColor Green
    Write-Host "    From:    $($msg.From.Address)"
    Write-Host "    Subject: $($msg.Subject)"
    Write-Host "    Vedi il corpo completo su http://localhost:8025"
} else {
    Write-Host "  [FAIL] nessuna email per $testEmail trovata in Mailpit" -ForegroundColor Red
    exit 1
}
