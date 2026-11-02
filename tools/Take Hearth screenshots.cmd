@echo off
rem Takes screenshots of Hearth TV in light and dark mode (living room TV). Saves them in the shots folder.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0hearth-screenshots.ps1" -TvIp 192.168.0.32
