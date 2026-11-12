@echo off
rem CLI wrapper for Windows cmd/PowerShell: search "query"
cd /d "%~dp0"
docker compose exec server search %*
