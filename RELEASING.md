# Releasing SpeakType

How versioned releases and production signing work.

## Versioning

- `android-app/app/build.gradle.kts`: bump `versionCode` (integer, +1 every release) and `versionName` (`X.Y.Z`) for every published build. Android rejects updates with an equal/lower `versionCode`.
- Tag releases as `vX.Y.Z` (e.g. `v1.2.0`). A `latest` prerelease tag is updated on every push to `main` by Actions and is what phones poll via `releases/tags/latest`.
- Update `CHANGELOG.md` before tagging.

## GitHub Actions (automatic)

Every push to `main` and every pull request runs `.github/workflows/ci.yml`:

1. Windows pytest suite on `windows-latest` (gating, not advisory)
2. `assembleDebug` → `app-debug.apk`
3. `assembleRelease` → `app-release.apk` (signed, see below)
4. `SHA256SUMS.txt` for both files
5. On pushes to `main`, publishes all three as the rolling `latest` prerelease — the tag phones poll via `releases/tags/latest` for in-app updates

Every `v*` tag (or a manual run of the Release workflow with an existing tag) runs `.github/workflows/release.yml`:

1. Builds both APKs and the Windows `SpeakType.exe` (PyInstaller on `windows-latest`)
2. Publishes a versioned, non-prerelease GitHub release named `SpeakType vX.Y.Z` with all three artifacts plus `SHA256SUMS.txt`, using the matching `CHANGELOG.md` section as the release notes

Manual rebuilds: Actions tab → `CI` or `Release` → `Run workflow`.

## Release signing (production)

Debug builds use the shared `android-app/app/keystore/speaktype-debug.jks` (committed so installs upgrade in place). A **release keystore must never be committed** — it is ignored by `.gitignore`.

To sign production builds:

1. Create a keystore locally (keep a safe backup — losing it breaks upgrades):
   ```sh
   keytool -genkeypair -v -keystore release.jks -alias speaktype -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Add GitHub Secrets (repo → Settings → Secrets → Actions):
   - `KEYSTORE_BASE64` = `base64 -w0 release.jks`
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
3. The workflow restores the keystore from `KEYSTORE_BASE64` and `assembleRelease` picks it up via `KEYSTORE_FILE` env. Without these secrets the release build falls back to the debug key so CI stays green.

After switching to a real release key, existing installs must be uninstalled once (key changed) or forever upgrade with the *new* key.

## Windows exe

- Tagged releases build `SpeakType.exe` in Actions (PyInstaller, same flags as `windows/build-exe.bat`) and attach it to the versioned release.
- Locally, `windows/dist/SpeakType.exe` is built with `windows/build-exe.bat` (requires the venv from `setup.bat`).
- Notarization/code-signing for the exe is not set up yet; distributing unsigned exes will trigger SmartScreen. A code-signing certificate is the next hardening step.

## Checklist

- [ ] Bump `versionCode`/`versionName`, update `CHANGELOG.md`
- [ ] Push to `main`, wait for Actions `CI` ✓
- [ ] Verify `latest` release has `app-debug.apk`, `app-release.apk`, `SHA256SUMS.txt`
- [ ] Push the `vX.Y.Z` tag, wait for Actions `Release` ✓
- [ ] Verify the versioned release has both APKs, `SpeakType.exe`, `SHA256SUMS.txt`, and changelog notes
- [ ] Install the new APK on a phone, confirm auto-update prompt from PC/GitHub
