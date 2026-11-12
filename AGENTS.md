# SpeakType — agent operating brief

Two independent apps in one repo: `windows/` (Python 3.11+ desktop tray app) and `android-app/`
(Kotlin, minSdk 26 / targetSdk 36). They pair over local Wi-Fi and speak one HTTPS contract;
a change to the wire protocol must keep both sides in sync (see `docs/API.md`).

## Critical rules
- Never change the Android signing key. Every build uses the committed fixed key
  `android-app/app/keystore/speaktype-debug.jks`; a different key forces an uninstall and drops
  the phone pairing.
- Never commit a release keystore or build artifacts (`dist/`, `build/`, `*.apk`). Only the shared
  debug key is tracked; release keys come from GitHub Secrets (see `RELEASING.md`).
- Bump `versionCode` (+1) and `versionName` in `android-app/app/build.gradle.kts` for every
  published build — Android rejects an equal/lower `versionCode`. Update `CHANGELOG.md` before tagging.

## Windows (`windows/`)
Uses a private `.venv`, not global Python. Run these from `windows/` on Windows (cmd/PowerShell).
- First-time setup (creates `.venv`, installs deps, launches): `setup.bat`
- Run the app: `run.bat`
- Tests: `.venv\Scripts\python.exe -m pytest tests/ -q`
- Build standalone exe (requires `setup.bat` first): `build-exe.bat` → `dist\SpeakType.exe`

## Android (`android-app/`)
Requires JDK 17+ and Android SDK platform 36. Run from `android-app/` on Windows.
- Build debug APK (the local compile check): `.\gradlew.bat assembleDebug`
  → `app/build/outputs/apk/debug/app-debug.apk`
- Runtime smoke test is CI/emulator-only (needs `adb` and `$RUNNER_TEMP`): `scripts/emulator-smoke-test.sh`.
  Do not run it against a physical device.

## Icons
Every icon (exe, tray, window, Android launcher, logo) is generated from one drawing. After changing
artwork, run from the repo root: `windows/.venv/Scripts/python.exe tools/make_icons.py`, then rebuild.
Do not hand-edit individual icon files.

## Deeper docs
Architecture `docs/ARCHITECTURE.md` · Wire protocol `docs/API.md` · Troubleshooting
`docs/TROUBLESHOOTING.md` · Release/signing `RELEASING.md`