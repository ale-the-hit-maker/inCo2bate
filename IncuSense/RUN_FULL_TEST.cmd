@echo off
REM ============================================================
REM IncuSense - Lancia il test complessivo di sistema.
REM Doppio click per eseguire. Output: scripts\test_result.log
REM ============================================================
cd /d "%~dp0"
echo Avvio test complessivo IncuSense (build + run + verifiche)...
echo I risultati appariranno qui e in scripts\test_result.log
powershell -NoProfile -ExecutionPolicy Bypass -Command "& '.\scripts\full_system_test.ps1' 2>&1 | Tee-Object -FilePath '.\scripts\test_result.log'; Add-Content '.\scripts\test_result.log' ('EXITCODE ' + $LASTEXITCODE)"
echo.
echo Test terminato. Log: scripts\test_result.log
pause
