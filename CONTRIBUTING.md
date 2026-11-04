# Contributing

Thanks for your interest in contributing to SpeakType.

## Development setup

- Windows 10/11 with Python 3.11+ for SpeakType (`windows/setup.bat`).
- Android Studio / JDK 17 for the app (`android-app`).

### Windows helper (`windows/`)

1. Install Python 3.11 or newer on Windows 10/11.
2. Run `windows/setup.bat` — it creates `.venv`, installs `windows/requirements.txt`, then starts the app.
3. Run tests with pytest:

```bash
cd windows
.venv\Scripts\python.exe -m pytest tests/ -q
```

### Android app (`android-app/`)

1. Install JDK 17 and Android SDK platform 36.
2. Open `android-app/` in Android Studio, or build from the command line with the Gradle wrapper:

```bash
cd android-app
.\gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Running tests

```bash
cd windows
.venv\Scripts\python.exe -m pytest tests/ -q
```

```bash
cd android-app
.\gradlew.bat assembleDebug
```

CI mirrors these checks: `.github/workflows/build-android-apk.yml` builds the APK on Ubuntu and runs the Windows pytest suite on `windows-latest`.

## Pull requests

- Keep changes focused and minimal.
- Add tests for new app behavior.
- Do not commit build artifacts (`dist/`, `build/`, `*.apk`).

## Commit conventions

- Keep commits small and focused on one change.
- Use an imperative summary, optionally with a prefix, e.g. `windows: fix firewall check`, `android-app: bump versionCode`, `docs: update README`.
- Reference related issues in the body, e.g. `Closes #12`.

## Release steps

1. Bump `versionCode` in `android-app/app/build.gradle.kts` (and `versionName` if the release is user-visible). The signing key must stay the same — changing it forces an uninstall.
2. Update `CHANGELOG.md` with the user-visible changes.
3. Tag the release as `vX.Y.Z` (e.g. `v1.2.0`).
4. GitHub Actions publishes the build as the `latest` prerelease and uploads the `speaktype-debug-apk` artifact.
