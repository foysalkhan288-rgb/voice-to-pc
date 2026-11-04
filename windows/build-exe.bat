@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\pyinstaller.exe" (
  echo Run setup.bat first.
  pause
  exit /b 1
)
".venv\Scripts\pyinstaller.exe" --noconfirm --onefile --windowed --name "SpeakType" --icon "assets\speaktype.ico" --collect-all customtkinter --add-data "gui;gui" --add-data "settings.py;." --add-data "assets;assets" speaktype.py
if errorlevel 1 (
  echo Build failed.
  pause
  exit /b 1
)
echo Built: dist\SpeakType.exe
exit /b 0
