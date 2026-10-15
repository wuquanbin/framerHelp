@echo off
chcp 65001 >nul
cd /d "%~dp0Vue3\foshanweather"
echo ==========================================
echo  Admin UI static preview server (4173)
echo  Keep this window open. Close it to stop.
echo ==========================================
echo.
npx vite preview --port 4173 --strictPort
pause >nul
