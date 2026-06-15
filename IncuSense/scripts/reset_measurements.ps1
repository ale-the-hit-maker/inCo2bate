# ============================================================
# IncuSense — Reset dei dati di misurazione (soft reset)
#
# Azzera: misurazioni, alert, stato di salute/drift dei sensori.
# Conserva: utenti, lab, hub registrati, contatti notifica,
#           curve di riferimento drift.
#
# Uso (PowerShell, dalla cartella IncuSense):
#   .\scripts\reset_measurements.ps1
#
# Per un reset TOTALE (incluso utenti/lab — ricrea il DB da zero):
#   docker compose down -v ; docker compose up --build -d
# ============================================================

$ErrorActionPreference = "Stop"

Write-Host "[1/3] Azzeramento misurazioni, alert e stato drift..." -ForegroundColor Cyan
docker compose exec -T timescaledb psql -U incusense_user -d incusense -c @"
TRUNCATE TABLE measurements;
TRUNCATE TABLE alerts;
DELETE FROM sensor_health;
"@

if ($LASTEXITCODE -ne 0) {
    Write-Host "ERRORE: il database non risponde. I container sono avviati? (docker compose up -d)" -ForegroundColor Red
    exit 1
}

Write-Host "[2/3] Riavvio del backend (svuota cache in memoria: cooldown alert, calibrazioni runtime)..." -ForegroundColor Cyan
docker compose restart backend

Write-Host "[3/3] Attesa health check backend..." -ForegroundColor Cyan
$deadline = (Get-Date).AddSeconds(120)
do {
    Start-Sleep -Seconds 5
    $health = ""
    try { $health = (Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:8080/actuator/health" -TimeoutSec 5).Content } catch {}
} while (-not $health.Contains('"UP"') -and (Get-Date) -lt $deadline)

if ($health.Contains('"UP"')) {
    Write-Host "Reset completato. Piattaforma pronta: http://localhost:3000" -ForegroundColor Green
} else {
    Write-Host "Reset dati eseguito, ma il backend non ha ancora superato l'health check: controlla 'docker compose logs backend'." -ForegroundColor Yellow
}
