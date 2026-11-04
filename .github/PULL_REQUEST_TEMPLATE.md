## What

<!-- Describe your change and why. Link related issues with Closes #... -->

## Checklist

- [ ] Builds the APK (`cd android-app && .\gradlew.bat assembleDebug`, or the GitHub Actions `speaktype-debug-apk` artifact is green)
- [ ] Runs Windows tests (`cd windows && .venv\Scripts\python.exe -m pytest tests/ -q`)
- [ ] Bumps `versionCode` in `android-app/app/build.gradle.kts` (Android changes only)
- [ ] Updates `CHANGELOG.md` (add an entry, or create the file if missing)
- [ ] Keeps changes focused and minimal; adds tests for new behavior
- [ ] Does not commit build artifacts (`dist/`, `build/`, `*.apk`)

## Notes

<!-- Anything reviewers should know: testing done, screenshots, breaking changes. -->
