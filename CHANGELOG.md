# Changelog

All notable changes to SpeakType are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/). This project uses semantic versioning for the Android `versionName` and the `vX.Y.Z` git tags.

## [Unreleased]

## [v1.2.0] - 2026-11-05

### Added

- Production-ready open-source polish: README badges/install/settings docs, ARCHITECTURE/API/TROUBLESHOOTING guides, issue/PR templates, Dependabot, `.gitattributes`/`.editorconfig` (`8a3b0fd`).
- Release APK build with SHA-256 checksums (`app-release.apk` + `SHA256SUMS.txt` on the `latest` release) and env-based release signing that falls back to the debug key (`712cf21`).

### Changed

- Rebrand to SpeakType and rename the Windows helper to `windows`; fix the GitHub APK build (`36200b1`).
- Simplify Actions to a green APK build (remove emulator gating) (`5c9e3cc`).
- Bump `appcompat` to 1.8.0, `customtkinter` to >=6.0.0; repo description/topics/homepage set to the `latest` release.

### Fixed

- Fix the GitHub update check to use the `voice-to-pc` repository and `tags/latest` for prerelease builds (`fd4ce7f`).

## [v1.1.0] - 2026-10-29

Tag: `49549da` — Add open-source metadata (LICENSE, CONTRIBUTING, SECURITY, issue template).

### Added

- PC settings GUI and Android dictation service (`1e5655e`).
- Bump GUI version to 1.1.0 and add helper test job to CI (`312b0e1`).
- Add autostart-by-default, build script, and standalone exe support (`6f6c786`).
- Document standalone exe build (`0b33f1d`).
- Add GitHub release publishing and in-app GitHub update check (`23c8048`).
- Add open-source metadata: LICENSE, CONTRIBUTING, SECURITY, issue template (`49549da`).

### Changed

- Clean project structure and ignore build caches (`bf93925`).
- Ignore stray APK build artifacts (`0c4d385`).

### Fixed

- Regenerate corrupt TLS certificates instead of failing (`8ecfd50`).
- Fix Android HTTP method: `postJson` must use POST (`ab3edbc`).
- Increase Android HTTP read timeout for slow typing (`96e4bef`).
- Clear delivered message ids on unpair (`ef7e07c`).

## [v1.0.6] - 2026-10-19

Tag: `6b140e3` — Preview speech on the PC while dictating (v1.0.6).

### Added

- Preview speech on the PC while dictating. The recognizer requests partial results and streams them to the PC, which shows them in a Live preview panel on the dashboard. Previews never reach the keyboard: the focused window still receives one block when the final transcript arrives, so nothing typed can ever need retracting. Preview updates keep only the newest text and drop the rest, so a slow link cannot build a backlog ahead of the final send. The panel falls back to a hint once the text is older than a few seconds or when the final transcript is typed (`6b140e3`).

### Earlier history

See `git log --oneline` for the full history before `v1.0.6` (for example `382b34d` fixed-key signing and in-app updates for v1.0.5, `9f3391e` hold-to-talk send on release for v1.0.4, and `a9322f9` English output, hold-to-talk, persistent pairing, and helper dashboard for v1.0.3).
