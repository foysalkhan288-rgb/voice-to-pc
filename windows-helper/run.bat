@echo off
setlocal
cd /d "%~dp0"
if exist ".venv\Scripts\python.exe" (
  ".venv\Scripts\python.exe" voice_to_text_helper.py
) else (
  py -3 voice_to_text_helper.py
)
if errorlevel 1 pause
