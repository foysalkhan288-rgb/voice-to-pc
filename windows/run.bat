@echo off
setlocal
cd /d "%~dp0"
if exist ".venv\Scripts\pythonw.exe" (
  start "" ".venv\Scripts\pythonw.exe" speaktype.py
) else (
  start "" pyw -3 speaktype.py
)
exit /b 0
