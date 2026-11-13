# Architecture

SpeakType has two codebases that communicate over the local network only. There is no cloud relay.

## Component map

| Part | Path | Role |
|---|---|---|
| Windows service | `windows/speaktype.py` | TLS HTTPS server, pairing, transcript injection, APK serving |
| Windows GUI | `windows/gui/app.py` plus `windows/gui/pages/` | Dashboard, Pair phone, Settings, Security, tray |
| Windows settings | `windows/settings.py` | Persistent settings under `%LOCALAPPDATA%\SpeakType` |
| Android UI | `android-app/app/src/main/java/com/ashiqe/voicetopc/MainActivity.kt` | Launcher activity, pairing, dictation, update check |
| Android service | `android-app/app/src/main/java/com/ashiqe/voicetopc/DictationService.kt` | Foreground dictation while another app owns the screen |
| Android tile and boot | `DictationTileService.kt`, `BootReceiver.kt`, `DictationNotifications.kt` | Quick-settings tile, ready-notification re-post after reboot |

## Android

### MainActivity

The single launcher activity. It owns the visible UI (Talk/Settings page switch at the top, the hold-to-talk button and Hold/Tap mode tabs at the bottom of the Talk screen), runtime permissions (`RECORD_AUDIO` for dictation, `CAMERA` for the pairing QR), the pairing QR scanner (ZXing), the output-mode button (Bengali script, Banglish, English), network rediscovery, and the in-app update flow (PC-served build when paired, GitHub releases otherwise, compared by SHA-256, with a versionCode downgrade guard before installing).

Pairing state (host, port, session token, certificate fingerprint) is persisted in `SharedPreferences` with the token encrypted by AES-GCM with a key in the Android Keystore (`speak_type_connection_key`). The speech and UI settings (output mode, language mode, talk mode, theme) are stored in the same preferences file.

TLS is pinned: the HTTPS client validates the server certificate against the SHA-256 fingerprint scanned from the QR code (`X509TrustManager` plus `SSLContext`). A fingerprint mismatch fails the connection.

### DictationService

A foreground service with the `microphone` type. It owns one `SpeechRecognizer` per session, the `LISTENING` / `FINALIZING` / `SENDING` state machine, partial-result streaming to the PC preview, and the final send. A notification and a finalize/session timeout guard keep a stuck recognizer from leaving the service running. The quick-tap entry (notification shade `ACTION_QUICK`) listens and then finalizes by itself shortly after end-of-speech, so one tap sends.

`DictationNotifications` owns the permanent ready notification in the shade; the service removes it while a session owns the shade and restores it when the session ends. `BootReceiver` re-posts the ready notification after reboot. `DictationTileService` is the Quick Settings tile entry point.

Speech comes from Android's speech service, preferring the on-device recognizer. If the service reports offline recognition unavailable (errors 12/13), the session retries once online with the same recognizer. Some on-device services return an empty final result after good partials; the last partial is then used as the transcript.

## Windows

### `speaktype.py` — `SpeakTypeService`

Owns the TLS server lifecycle (`VoiceHTTPServer`, a `ThreadingHTTPServer` on `0.0.0.0`), pairing state, certificate lifecycle, and the APK release watcher.

- TLS 1.2 minimum, self-signed certificate (`SpeakType`, 2048-bit RSA, 10 years) generated with the `cryptography` package if missing or corrupt.
- `ensure_certificate()` reuses the existing certificate, otherwise regenerates it. The SHA-256 fingerprint is shown on the Security screen and embedded in the QR payload.
- Session token is 256-bit random (`secrets.token_urlsafe(32)`), persisted under `%LOCALAPPDATA%\SpeakType` with DPAPI (`CryptProtectData`) so only the current user can read it.
- Pairing code is a one-time 180-second bootstrap token, issued only by `new_pairing()` when the Pair phone screen is open.
- Input injection uses `SendInput` with `KEYEVENTF_UNICODE` events per UTF-16 code unit, per character, with an optional 0–100 ms typing delay. It checks `OpenInputDesktop` so it refuses on secure desktops (logon, UAC). It never uses the clipboard and never moves focus.
- Firewall handling shells out to `netsh advfirewall` for inspection and rule creation, with elevation via `ShellExecuteW runas` (`--allow-firewall` helper mode).
- Autostart writes `HKCU\...\Run\SpeakType` via `winreg`.
- A named mutex (`Local\SpeakType.SingleInstance`) prevents a second instance after autostart.

### GUI (`windows/gui/`)

`gui/app.py` builds the CustomTkinter window, tray icon (`pystray`), and page navigation. Pages:

- `dashboard.py` — listening/paired state, PC name and address, typed count, activity feed, Live preview panel, update banner.
- `pairing.py` — QR code and one-time pairing code.
- `settings_page.py` — port, typing delay, notifications, start minimized, start with Windows, firewall, theme.
- `security.py` — pinned fingerprint, replace certificate, unpair, open data folder.
- `theme.py` — dark and light themes.

Settings (`windows/settings.py`) live in `settings.json` and are applied without restart except port, which restarts the listener.

## IPC and endpoints

There is no separate IPC channel. The phone and the PC talk over HTTPS on the local network to the `/v1` endpoints served by `VoiceRequestHandler` in `speaktype.py`:

- `GET /v1/health` — discovery probe (no auth).
- `GET /v1/app-release` — served APK metadata (auth).
- `GET /v1/app.apk` — served APK bytes (auth).
- `POST /v1/pair` — exchange the bootstrap code for a session token.
- `POST /v1/send` — type one final transcript into the foreground window (auth).
- `POST /v1/preview` — dashboard-only partial transcript (auth, never typed).

See [API.md](API.md) for request and response shapes.

Boundaries:

- Only private, loopback, or link-local client addresses are served; anything else gets `403 local_network_only`.
- Request bodies are capped at 64 KB; `text` is capped at 12,000 characters.
- `preview` traffic keeps only the newest text and expires after 6 seconds.
- Delivered message ids are persisted (`delivered-ids.json`, max 512) so a retry after a timeout is answered as a duplicate instead of double-typing. Unpairing clears them.

## Data flow

1. Pairing: the PC shows a QR code containing host, port, bootstrap code, and fingerprint. The phone POSTs the code to `/v1/pair`, receives a session token, and stores it encrypted.
2. Dictation: the phone recognises speech, POSTs partials to `/v1/preview` for the dashboard Live preview panel, then POSTs the final transcript (with a message id) to `/v1/send`. The PC appends a trailing space and types it once into the focused window, then returns `{"ok": true}`.
3. Update: the PC serves the newest complete APK it can find (Gradle output or `%LOCALAPPDATA%\SpeakType\speaktype.apk`). The phone compares SHA-256 via `/v1/app-release` and, if different, downloads via `/v1/app.apk`.

## Storage

All PC state lives under `%LOCALAPPDATA%\SpeakType`: `server-key.pem`, `server-cert.pem`, `session-token.dpapi`, `settings.json`, `delivered-ids.json`, and `speaktype.apk`. No transcript history is kept on either side.
