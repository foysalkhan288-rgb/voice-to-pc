# Contributing

Thanks for your interest in contributing to SpeakType.

## Development setup

- Windows 10/11 with Python 3.11+ for SpeakType (`windows/setup.bat`).
- Android Studio / JDK 17 for the app (`android-app`).

## Running tests

```bash
cd windows
.venv\Scripts\python.exe -m pytest tests/ -q
```

```bash
cd android-app
.\gradlew.bat assembleDebug
```

## Pull requests

- Keep changes focused and minimal.
- Add tests for new app behavior.
- Do not commit build artifacts (`dist/`, `build/`, `*.apk`).
