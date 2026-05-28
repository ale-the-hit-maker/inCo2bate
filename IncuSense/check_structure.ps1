# check_structure.ps1 - verifies the IncuSense folder structure on Windows.
# Run from the IncuSense folder: .\check_structure.ps1

$root = Get-Location
$ok = $true

$required = @(
    "docker-compose.yml",
    "mosquitto\config\mosquitto.conf",
    "nginx\nginx.conf",
    "backend\Dockerfile",
    "backend\pom.xml",
    "backend\src\main\java\com\incusense\IncuSenseApplication.java",
    "backend\src\main\java\com\incusense\config\MqttConfig.java",
    "backend\src\main\java\com\incusense\config\SecurityConfig.java",
    "backend\src\main\java\com\incusense\config\WebSocketConfig.java",
    "backend\src\main\java\com\incusense\model\SensingHub.java",
    "backend\src\main\java\com\incusense\model\Measurement.java",
    "backend\src\main\java\com\incusense\model\MeasurementId.java",
    "backend\src\main\java\com\incusense\model\Entities.java",
    "backend\src\main\java\com\incusense\dto\Dtos.java",
    "backend\src\main\java\com\incusense\repository\Repositories.java",
    "backend\src\main\java\com\incusense\service\MqttIngestionService.java",
    "backend\src\main\java\com\incusense\service\AlertService.java",
    "backend\src\main\java\com\incusense\service\CalibrationService.java",
    "backend\src\main\java\com\incusense\service\UserDetailsServiceImpl.java",
    "backend\src\main\java\com\incusense\controller\Controllers.java",
    "backend\src\main\java\com\incusense\security\JwtTokenProvider.java",
    "backend\src\main\java\com\incusense\security\JwtAuthenticationFilter.java",
    "backend\src\main\resources\application.yml",
    "backend\src\main\resources\db\migration\V1__init_schema.sql",
    "frontend\index.html",
    "frontend\dashboard.html",
    "frontend\css\style.css",
    "frontend\js\api.js",
    "frontend\js\dashboard.js"
)

Write-Host "`n=== IncuSense - Folder structure check ===" -ForegroundColor Cyan
Write-Host "Root: $root`n"

foreach ($f in $required) {
    $full = Join-Path $root $f
    if (Test-Path $full) {
        Write-Host "  [OK] $f" -ForegroundColor Green
    } else {
        Write-Host "  [MISSING] $f" -ForegroundColor Red
        $ok = $false
    }
}

Write-Host ""
if ($ok) {
    Write-Host "Structure complete. You can proceed with: docker compose up --build -d" -ForegroundColor Green
    exit 0
}

Write-Host "Missing files. Add the missing files before running docker compose." -ForegroundColor Red
exit 1
