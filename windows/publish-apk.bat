@echo off
setlocal
cd /d "%~dp0"
set "source=%~1"
if "%source%"=="" set "source=..\app-debug.apk"
if not exist "%source%" (
  echo APK not found: %source%
  echo Usage: publish-apk.bat [path-to-apk]
  exit /b 1
)
if not exist "%LOCALAPPDATA%\SpeakType" mkdir "%LOCALAPPDATA%\SpeakType"
copy /y "%source%" "%LOCALAPPDATA%\SpeakType\speaktype.apk" >nul
if errorlevel 1 (
  echo Could not copy the APK.
  exit /b 1
)
echo Published:
echo   %source%
echo to:
echo   %LOCALAPPDATA%\SpeakType\speaktype.apk
echo On the phone, open SpeakType and tap Check for app update.
exit /b 0
