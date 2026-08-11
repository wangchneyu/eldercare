@echo off
rem P1-06 Edge Relay 部署栈启动与冒烟
cd /d "%~dp0"

docker compose up -d --build
if errorlevel 1 exit /b 1

echo Waiting for edge-relay health...
for /l %%i in (1,1,30) do (
  curl -fsS http://localhost:8091/actuator/health >nul 2>&1
  if not errorlevel 1 goto healthy
  timeout /t 1 >nul
)
echo edge-relay health check FAILED
exit /b 1

:healthy
echo edge-relay health: UP
echo --- edge_ metrics (prometheus) ---
curl -fsS http://localhost:8091/actuator/prometheus | findstr "^edge_"
docker compose ps