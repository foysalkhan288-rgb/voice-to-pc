@echo off
setlocal
cd /d "%~dp0"
if exist ".venv\Scripts\pythonw.exe" (
  start "" ".venv\Scripts\pythonw.exe" voice_to_text_helper.py
) else (
  start "" pyw -3 voice_to_text_helper.py
)
exit /b 0
