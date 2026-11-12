# Changelog

All notable changes to SpeakType are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/). This project uses semantic versioning for the Android `versionName` and the `vX.Y.Z` git tags.

## [v1.7.3] - 2026-11-13

### Changed — PC app (1.3.0)

- The desktop dashboard is redesigned to match the phone app's design language: the same dark/light palette, rounded cards, status dots and pills, segmented controls and typography across Dashboard, Pair phone, Settings and Security. All pairing, preview, tray and firewall behavior is unchanged.

### Internal — phone app

- Removed temporary diagnostic logging from the speech and send paths.

## [v1.7.2] - 2026-11-12

### Fixed — phone app

- Dictation no longer reports "No speech was recognized" when the phone's speech service returns an empty final result after good partial results (seen with the Google TTS on-device recognizer). The last partial transcript is kept and sent when the final result comes back empty, including after a no-match or silence timeout.

## [v1.7.1] - 2026-11-12

### Fixed — phone app

- Tap/hold dictation no longer fails with "the speech service does not support the selected language" on phones without the offline language pack. Recognition still prefers the offline pack, but when the service reports the language unavailable offline it retries once with online recognition allowed. The Recognition settings text now notes that audio may be recognized online when no offline pack is installed.
- Choosing English as the text format now actually locks recognition to English. Previously the language row showed "English (output lock)" but dictation still listened in the language picked on the language row, so English speech could be misrecognized. The lock now applies to both the main button and the notification-shade dictation.
- "Install update" no longer ends in the system's bare "App not installed" when the PC (or GitHub) offers an APK older than the installed app. The downloaded build's version is checked first; an older build is skipped with a clear message instead of reaching the system installer.

## [v1.7.0] - 2026-11-12

### Changed — recognition moves to the phone, no downloads anywhere

- The phone recognizes speech with its own on-device speech service (offline language packs preferred) and sends only text to the PC: partial results feed the live preview, the final transcript is typed once. Audio never leaves the phone.
- The PC's Whisper model, the one-time ~480 MB download, and the /v1/audio streaming endpoints are removed entirely. The PC applies the chosen output format (Bengali script / Banglish / English) and types via SendInput — nothing else.
- The dashboard no longer shows a PC model status, and the phone no longer waits for a PC model before dictating.

### Fixed

- A typing failure that inserts zero characters is reported as a failure and stays retryable instead of silently marking the message delivered.
- Decomposed Bangla (base letter + nukta, e.g. ড়) is composed before romanization, so Banglish output is correct even when the recognizer emits decomposed text.
- Preview updates coalesce to the newest partial so a slow link cannot build a backlog; session and send timeouts were tightened, and Cancel is hidden while a send is in flight.

## [v1.6.0] - 2026-11-11

### Changed — dictation now works on any phone

- The phone no longer depends on Android speech services or Google apps. It records raw microphone audio and streams it to the PC over the existing encrypted Wi-Fi link; the PC transcribes with its own offline Whisper model. Bangla and English work on every Android phone, with or without Google services, and audio never leaves the local network.
- The main screen shows the PC speech model's status (downloading / loading / ready) and explains what to wait for before dictating.
- Settings no longer offers speech-engine or online-recognition choices; the language and text format you pick are applied by the PC.
- The notification-shade quick dictation uses the same microphone pipeline and finalizes by itself after you stop speaking.

### Added — PC app (1.2.0)

- Built-in offline speech recognition (faster-whisper, small model): a one-time ~480 MB download, then fully offline on the CPU. The dashboard shows download progress with a retry button.
- Live transcript preview while you speak, transcribed on the PC from the streamed audio, in the text format chosen on the phone.

## [v1.5.3] - 2026-11-11

### Fixed — phone app

- A speech-service rejection in the first seconds now falls through to the next language/engine even when room noise already registered on the RMS meter; previously that disabled the retry and ended the session with "Didn't hear anything" after a second.
- Hold mode: a click that captured no speech keeps the session alive (tap to start, tap again to send) instead of finalizing an empty utterance and showing a no-match error. Releasing after speech still sends.
- Notification-shade dictation retries once with English when the engine rejects the first request within three seconds, and reports an early failure as a speech-service problem.
- A link check that started before pairing can no longer finish seconds later and replace the fresh "Connected" state with "PC unreachable".
- Same-Wi-Fi rediscovery now accepts the PC whenever its pinned certificate matches, so an old stored hostname or a renamed PC no longer blocks reconnection after an IP change.
- The connection status names the address it tried and distinguishes a changed PC certificate ("scan the QR again") from a network failure.

### Fixed — PC app

- PC pairing now always advertises a reachable LAN address: VPN adapters and link-local addresses are skipped when the QR code is generated.
- The Pairing page shows the advertised address under the QR code and rotates an expired code automatically, so the code on screen is never dead.
- A failed scan now appears in Recent activity with the phone's address and the reason.

## [v1.5.1] - 2026-11-11

### Added

- Settings > Speech has a Test microphone action that records three seconds locally and reports whether any sound reaches the app, with the peak level.
- Settings > About has a Copy diagnostics action that copies the build, Android version, permissions, engines and last error in one go for reporting.

### Changed

- When the speech service reports no speech and no audio was ever heard, the status now points at Microphone access and the mic test instead of asking the user to speak clearly.

## [v1.5.0] - 2026-11-11

### Added

- Settings > Speech now has a Microphone access row (grant the permission or open the app's permission settings), a Speech engine picker that cycles through every recognition service installed on the phone, and a shortcut to the phone's own speech settings for enabling services or downloading offline languages.
- When the chosen engine rejects a request before any audio is heard, SpeakType now retries with the other language, then the on-device engine, then any other installed recognition service.

### Changed

- The first recognition request no longer asks Android 14+ for language switching. That optional feature was the most common reason a phone ended the session instantly; Auto (Bangla + English) now tries Bangla first and falls back to English.
- The About diagnostic now names the engine that failed.

## [v1.4.3] - 2026-11-11

### Fixed

- When the phone's speech service rejects the first request (missing Bangla model or unsupported Android 14 language-switch extras), the app now retries automatically with a plain language request instead of ending the session immediately.
- A failure in the first three seconds with no audio is reported as a speech-service problem, not as the mic hearing nothing.
- Settings > About now also shows which retry attempt failed.

## [v1.4.2] - 2026-11-10

### Changed

- In Hold mode a quick tap now starts a listening session that stays open instead of being dropped; tap again to send, or keep holding to use push-to-talk.
- An interrupted gesture (system dialog, call, parent scrolling) cancels the dictation silently instead of producing an empty-speech error.
- When the recognizer reports no speech almost immediately, the status now says the mic picked up no sound instead of blaming the speaker.
- Settings shows the last speech-service error and how long after start it arrived, so a failure can be reported without a cable.

## [v1.4.1] - 2026-11-09

### Fixed

- Hold mode no longer starts and instantly stops dictation when the disc is clicked; a click now shows a "hold the button" hint and the microphone session is dropped cleanly.
- Holding the disc can no longer be cancelled by a small finger drift inside the scrolling page.

## [v1.4.0] - 2026-11-09

### Added

- Dark theme, now the default look on Android, built from the same emerald tokens as the light theme; a Theme choice in Settings (System / Light / Dark) remembers the selection and restyles the system bars to match.
- Breathing glow and expanding rings around the talk disc while it is listening, plus a luminous edge on the disc, so an active microphone is unmistakable.

### Changed

- The talk disc, status pill, ripple tints and destructive buttons all derive their colours from the active palette instead of fixed light-mode values.

## [v1.3.0] - 2026-11-09

### Added

- Redesigned Android home screen: one large emerald talk disc that holds or taps to dictate, with a Hold/Tap toggle, live status and a floating Talk/Settings navigation pill.
- `TalkButton`, a drawn circular control with a microphone glyph, listening pulse, press animation and accessible labels; `SegmentedControl`, a reusable segmented pill used for the mode toggle and bottom navigation.

### Changed

- Moved pairing, recognition source, output format/language, app updates and notification-shade controls into a dedicated Settings page with an About card.
- The transcript retry/discard actions now appear only when a send is pending, directly under the live status on the home screen.

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
