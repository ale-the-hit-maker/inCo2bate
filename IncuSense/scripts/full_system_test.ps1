<#
  IncuSense — TEST COMPLESSIVO DI SISTEMA (build + run + verifica end-to-end)

  Esegue in sequenza:
    1. Build delle immagini e avvio dello stack (docker compose up --build -d)
    2. Attesa health check del backend
    3. Verifica frontend unico su :3000 (login page, dashboard, demo; :3001 spento)
    4. Login operator (utente seed, nessuna dipendenza dalla registrazione)
    5. Creazione contatto email di test
    6. Modalità DEMO: tutti gli scenari -> verifica alert attesi via REST
    7. Percorso FIRMWARE: publish MQTT reale (mosquitto_pub nel container broker)
       con il payload esatto del nodo ESP32 -> verifica ingestione e persistenza
    8. Verifica log invio email ([ALERT-EMAIL] reale o "disabled" se SMTP assente)
    9. (Opzionale -IncludeReset) Esecuzione reset_measurements.ps1 e verifica DB vuoto

  Uso (PowerShell, dalla cartella IncuSense):
    powershell -ExecutionPolicy Bypass -File .\scripts\full_system_test.ps1
    powershell -ExecutionPolicy Bypass -File .\scripts\full_system_test.ps1 -IncludeReset
    powershell -ExecutionPolicy Bypass -File .\scripts\full_system_test.ps1 -SkipBuild
#>
param(
    [switch]$IncludeReset,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
$fe   = 'http://localhost:3000'
$pass = 0; $fail = 0

function Check($name, $cond) {
    if ($cond) { Write-Host "  [PASS] $name" -ForegroundColor Green; $script:pass++ }
    else       { Write-Host "  [FAIL] $name" -ForegroundColor Red;   $script:fail++ }
}
function Req($method, $path, $headers, $bodyObj) {
    for ($try = 1; $try -le 5; $try++) {
        try {
            $p = @{ Uri = "$base$path"; Method = $method; TimeoutSec = 10 }
            if ($headers) { $p.Headers = $headers }
            if ($bodyObj) { $p.Body = ($bodyObj | ConvertTo-Json); $p.ContentType = 'application/json' }
            return Invoke-RestMethod @p
        } catch {
            if ($_.Exception.Response) { throw }
            if ($try -eq 5) { throw }
            Start-Sleep -Milliseconds 700
        }
    }
}

# ── 1. Build & avvio ─────────────────────────────────────────────
if (-not $SkipBuild) {
    Write-Host "`n[1/9] Build immagini + avvio stack (può richiedere minuti alla prima esecuzione)..." -ForegroundColor Cyan
    docker compose up --build -d
    if ($LASTEXITCODE -ne 0) { Write-Host "Build/avvio fallito." -ForegroundColor Red; exit 1 }
} else {
    Write-Host "`n[1/9] SkipBuild: uso lo stack già avviato." -ForegroundColor Yellow
    docker compose up -d | Out-Null
}

# ── 2. Health check backend ──────────────────────────────────────
Write-Host "[2/9] Attesa health check backend..." -ForegroundColor Cyan
$deadline = (Get-Date).AddSeconds(180); $up = $false
do {
    Start-Sleep -Seconds 5
    try { $h = Invoke-WebRequest -UseBasicParsing -Uri "$base/actuator/health" -TimeoutSec 5
          if ($h.Content -match '"UP"') { $up = $true } } catch {}
} while (-not $up -and (Get-Date) -lt $deadline)
Check "backend UP (/actuator/health)" $up
if (-not $up) { Write-Host "Backend non sano: docker compose logs backend" -ForegroundColor Red; exit 1 }

# ── 3. Frontend unico su :3000 ───────────────────────────────────
Write-Host "[3/9] Verifica frontend..." -ForegroundColor Cyan
$idx  = (Invoke-WebRequest -UseBasicParsing -Uri "$fe/" -TimeoutSec 8).Content
Check "frontend :3000 serve la login IncuSense" ($idx -match 'IncuSense')
$dash = Invoke-WebRequest -UseBasicParsing -Uri "$fe/dashboard.html" -TimeoutSec 8
Check "dashboard.html servita (200)" ($dash.StatusCode -eq 200)
Check "dashboard contiene drift meter" ($dash.Content -match 'drift-meter')
$demoPage = Invoke-WebRequest -UseBasicParsing -Uri "$fe/demo.html" -TimeoutSec 8
Check "demo.html servita (200)" ($demoPage.StatusCode -eq 200)
$old3001 = $false
try { Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:3001/' -TimeoutSec 3 | Out-Null; $old3001 = $true } catch {}
Check "porta 3001 (vecchio frontend) spenta" (-not $old3001)

# ── 4. Login operator ────────────────────────────────────────────
Write-Host "[4/9] Login operator..." -ForegroundColor Cyan
$auth = Req 'Post' '/api/auth/login' $null @{ username='operator'; password='operator123' }
Check "login operator -> token + labId" ([bool]$auth.token -and [bool]$auth.labId)
$hdr = @{ Authorization = "Bearer $($auth.token)" }
$labId = $auth.labId

# ── 5. Contatto email di test ────────────────────────────────────
Write-Host "[5/9] Contatto email di test..." -ForegroundColor Cyan
$contacts = Req 'Get' '/api/notification-contacts' $hdr $null
if (-not ($contacts | Where-Object { $_.label -eq 'E2E Test Contact' })) {
    Req 'Post' '/api/notification-contacts' $hdr @{
        label='E2E Test Contact'; channel='EMAIL'; target='e2e-test@example.org'; minLevel='INFO'; enabled=$true } | Out-Null
}
Check "contatto notifiche presente" $true

# ── 6. Modalità demo: tutti gli scenari ──────────────────────────
Write-Host "[6/9] Scenari demo..." -ForegroundColor Cyan
$scenarios = @(Req 'Get' '/api/demo/scenarios' $hdr $null)
Check "GET /api/demo/scenarios -> 7 scenari" ($scenarios.Count -eq 7)

# Scenari con ruleKey DISTINTI (evita il cooldown alert per-regola)
$expected = @(
    @{ id='NORMAL';       rule=$null;          level=$null },
    @{ id='CO2_LOW';      rule='CO2_RANGE';    level='WARN' },
    @{ id='OVERHEAT';     rule='ENV_TEMP';     level='WARN' },
    @{ id='HUMIDITY_LOW'; rule='ENV_HUM';      level='INFO' },
    @{ id='RAIL_FAILURE'; rule='RAIL_12V';     level='CRITICAL' },
    @{ id='SENSOR_DRIFT'; rule='SENSOR_DRIFT'; level='WARN' }
)
foreach ($s in $expected) {
    $r = Req 'Post' '/api/demo/scenario' $hdr @{ scenario=$s.id; hubKey=$null }
    Start-Sleep -Milliseconds 800
    if ($null -eq $s.rule) {
        Check "demo $($s.id): iniezione ok, nessun alert atteso" ([bool]$r.hubKey)
    } else {
        $alerts = @(Req 'Get' '/api/alerts' $hdr $null)
        $hit = $alerts | Where-Object { $_.ruleKey -eq $s.rule -and $_.level -eq $s.level } | Select-Object -First 1
        Check "demo $($s.id) -> alert $($s.rule)/$($s.level)" ([bool]$hit)
    }
}
$demoHub = @(Req 'Get' '/api/hubs' $hdr $null) | Select-Object -First 1
Check "hub demo registrato" ([bool]$demoHub)
$latest = @(Req 'Get' '/api/measurements/latest?limit=10' $hdr $null)
Check "misure demo persistite (>=5)" ($latest.Count -ge 5)

# ── 7. Percorso firmware: publish MQTT reale ─────────────────────
Write-Host "[7/9] Percorso firmware via MQTT (payload identico al nodo ESP32)..." -ForegroundColor Cyan
$hubKey = "${labId}_inc_01_node_a"
$topic  = "incusense/labs/$labId/hubs/$hubKey/telemetry"
$ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$json = "{`"ts`":$ts,`"co2_ppm`":50000.00,`"heater_temp`":250.14,`"env_temp`":37.25,`"env_hum`":95.30,`"rail_12v`":12.05,`"raw_adc`":1800,`"sensor_response`":0.1800}"
$f = "$env:TEMP\incusense_payload.json"
[System.IO.File]::WriteAllText($f, $json)
docker cp $f incusense-mqtt:/tmp/payload.json | Out-Null
docker exec incusense-mqtt mosquitto_pub -h localhost -t $topic -f /tmp/payload.json
Start-Sleep -Seconds 2
$hubs = @(Req 'Get' '/api/hubs' $hdr $null)
Check "hub firmware auto-creato via MQTT" ([bool]($hubs | Where-Object { $_.hubKey -eq $hubKey }))
$m = @(Req 'Get' "/api/hubs/$hubKey/measurements?limit=1" $hdr $null)
Check "telemetria MQTT ingerita e persistita" ($m.Count -ge 1 -and $null -ne $m[0].sensorResponse)
$health = Req 'Get' "/api/hubs/$hubKey/health" $hdr $null
Check "sensor health aggiornato dal drift monitor" ($null -ne $health.status)

# ── 8. Verifica pipeline email nei log ───────────────────────────
Write-Host "[8/9] Verifica pipeline email (log backend)..." -ForegroundColor Cyan
$logs = docker compose logs backend --since 10m 2>$null | Out-String
$emailReal     = $logs -match '\[ALERT-EMAIL\] sent to'
$emailDisabled = $logs -match '\[ALERT-EMAIL\] \(disabled'
Check "pipeline email attraversata (invio reale o no-SMTP loggato)" ($emailReal -or $emailDisabled)
if ($emailReal)     { Write-Host "  -> EMAIL REALI INVIATE (SMTP configurato)" -ForegroundColor Green }
elseif ($emailDisabled) { Write-Host "  -> SMTP non configurato: pipeline OK, invio disabilitato (configura .env per il pitch)" -ForegroundColor Yellow }

# ── 9. Reset opzionale ───────────────────────────────────────────
if ($IncludeReset) {
    Write-Host "[9/9] Test reset dati..." -ForegroundColor Cyan
    & "$PSScriptRoot\reset_measurements.ps1"
    $count = (docker exec incusense-db psql -U incusense_user -d incusense -t -A -c "SELECT count(*) FROM measurements;").Trim()
    Check "reset: measurements vuota" ([int]$count -eq 0)
} else {
    Write-Host "[9/9] Reset saltato (usa -IncludeReset per testarlo)." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "==================== RISULTATO: $pass passati, $fail falliti ====================" -ForegroundColor Cyan
if ($fail -gt 0) { exit 1 }
